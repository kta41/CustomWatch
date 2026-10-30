#!/usr/bin/env python3
"""Incremental sleep export synchronization using the Python standard library."""

import datetime
import argparse
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import signal
import ssl
import stat
import sys
import threading
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, Request, build_opener
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

MAX_EXPORT_BYTES = 16 * 1024 * 1024
MAX_PUSH_BYTES = 16 * 1024 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_CONFIG_BYTES = 64 * 1024
MAX_MILLISECONDS = 253402300799999
MAX_REVISION = 2147483647
MAX_NIGHTS_PER_BATCH = 3660
MAX_SAMPLES_PER_NIGHT = 10000
MAX_SAMPLES_PER_BATCH = 100000
STATUS_PAGE_SIZE = 500
DEFAULT_CONFIG = Path.home() / '.config' / 'customwatch' / 'orbit-sync.json'


class SyncError(Exception):
    pass


class HTTPConflict(SyncError):
    pass


def require(condition, message):
    if not condition:
        raise SyncError(message)


def exact_keys(value, keys):
    return isinstance(value, dict) and set(value) == set(keys)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def valid_date(value):
    if not isinstance(value, str):
        return False
    try:
        return datetime.date.fromisoformat(value).isoformat() == value
    except ValueError:
        return False


def valid_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


def validate_export(data):
    require(exact_keys(data, ('schema_version', 'source_id', 'nights', 'unreadable')),
            'Formato de exportacion incorrecto')
    require(integer(data['schema_version']) and data['schema_version'] == 1,
            'Version de exportacion no admitida')
    require(valid_uuid(data['source_id']), 'source_id invalido')
    require(isinstance(data['unreadable'], bool), 'unreadable invalido')
    require(isinstance(data['nights'], list), 'nights invalido')
    dates = set()
    for night in data['nights']:
        require(exact_keys(night, ('date', 'time_zone', 'revision', 'start_ms', 'end_ms',
                                   'deep_ms', 'light_ms', 'awake_ms', 'overlapping', 'samples')),
                'Noche invalida')
        require(valid_date(night['date']) and night['date'] not in dates, 'Fecha invalida o duplicada')
        dates.add(night['date'])
        require(isinstance(night['time_zone'], str) and 1 <= len(night['time_zone']) <= 128,
                'Zona horaria invalida')
        try:
            night['time_zone'].encode('utf-8')
            if not re.fullmatch(r'GMT[+-](?:[01][0-9]|2[0-3]):[0-5][0-9]', night['time_zone']):
                ZoneInfo(night['time_zone'])
        except (UnicodeEncodeError, ValueError, ZoneInfoNotFoundError) as error:
            raise SyncError('Zona horaria invalida') from error
        require(integer(night['revision']) and 1 <= night['revision'] <= MAX_REVISION,
                'Revision invalida')
        for field in ('start_ms', 'end_ms', 'deep_ms', 'light_ms', 'awake_ms'):
            require(integer(night[field]) and 0 <= night[field] <= MAX_MILLISECONDS,
                    'Duracion o fecha invalida')
        require(night['start_ms'] < night['end_ms'], 'Intervalo de noche invalido')
        require(isinstance(night['overlapping'], bool), 'overlapping invalido')
        require(isinstance(night['samples'], list) and len(night['samples']) <= MAX_SAMPLES_PER_NIGHT,
                'samples invalido')
        totals = {'deep': 0, 'light': 0, 'awake': 0}
        previous_end = -1
        for sample in night['samples']:
            require(exact_keys(sample, ('start_ms', 'end_ms', 'state')), 'Muestra invalida')
            require(integer(sample['start_ms']) and integer(sample['end_ms'])
                    and 0 <= sample['start_ms'] < sample['end_ms'] <= MAX_MILLISECONDS
                    and sample['start_ms'] >= previous_end, 'Intervalo invalido')
            require(sample['state'] in ('deep', 'light', 'awake'), 'Estado invalido')
            previous_end = sample['end_ms']
            totals[sample['state']] += max(0, min(sample['end_ms'], night['end_ms'])
                                           - max(sample['start_ms'], night['start_ms']))
        require(all(night[state + '_ms'] == totals[state] for state in totals),
                'Duraciones no coinciden con las muestras')
        require(totals['deep'] + totals['light'] > 0, 'Noche sin muestras dormidas')
    return data


def parse_json(raw):
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Clave JSON duplicada')
            result[key] = value
        return result

    try:
        return json.loads(raw, object_pairs_hook=unique_pairs,
                          parse_constant=lambda value: (_ for _ in ()).throw(SyncError('JSON invalido')))
    except (ValueError, UnicodeDecodeError, RecursionError) as error:
        raise SyncError('JSON invalido') from error


def private_directory(path):
    info = path.stat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
            and not info.st_mode & 0o022, 'Directorio de configuracion inseguro')


def private_file(path, limit):
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                and not info.st_mode & 0o077, 'Permisos de configuracion inseguros')
        raw = stream.read(limit + 1)
        require(len(raw) <= limit, 'Configuracion demasiado grande')
        return raw


def load_config(path):
    try:
        private_directory(path.parent)
        config = parse_json(private_file(path, MAX_CONFIG_BYTES))
    except OSError as error:
        raise SyncError('No se puede leer la configuracion privada') from error
    require(isinstance(config, dict) and set(config) <= {
        'url', 'token', 'export_path', 'ca_file', 'interval_seconds', 'retry_seconds'
    } and {'url', 'token', 'export_path'} <= set(config), 'Configuracion invalida')
    url = config['url']
    require(isinstance(url, str) and url.isascii() and all(33 <= ord(char) <= 126 for char in url),
            'URL invalida')
    try:
        parsed = urlsplit(url)
        require(parsed.scheme == 'https' and bool(parsed.hostname) and parsed.username is None
                and parsed.password is None and parsed.path in ('', '/')
                and not parsed.query and not parsed.fragment and parsed.port != 0, 'URL HTTPS invalida')
    except ValueError as error:
        raise SyncError('URL HTTPS invalida') from error
    token = config['token']
    require(isinstance(token, str) and len(token) >= 32 and token.isascii()
            and all(33 <= ord(char) <= 126 for char in token), 'Token ausente o invalido')
    require(isinstance(config['export_path'], str) and os.path.isabs(config['export_path']),
            'export_path debe ser absoluto')
    if 'ca_file' in config:
        require(isinstance(config['ca_file'], str) and os.path.isabs(config['ca_file']),
                'ca_file debe ser absoluto')
    for key, default in (('interval_seconds', 300), ('retry_seconds', 30)):
        value = config.get(key, default)
        require(integer(value) and 1 <= value <= 86400, 'Intervalo invalido')
        config[key] = value
    config['url'] = url.rstrip('/')
    return config


def read_once(path):
    with open(path, 'rb') as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode), 'Exportacion no es un archivo regular')
        require(before.st_size <= MAX_EXPORT_BYTES, 'Exportacion demasiado grande')
        raw = stream.read(MAX_EXPORT_BYTES + 1)
        after = os.fstat(stream.fileno())
        require(len(raw) <= MAX_EXPORT_BYTES, 'Exportacion demasiado grande')
        require((before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'Exportacion cambiante')
        return raw


def read_export(path, attempts=3, pause=time.sleep):
    for attempt in range(attempts):
        try:
            first = read_once(path)
            validate_export(parse_json(first))
            pause(0.2)
            second = read_once(path)
            require(first == second, 'Exportacion cambiante')
            return validate_export(parse_json(second))
        except (OSError, SyncError):
            if attempt + 1 < attempts:
                pause(0.5)
    raise SyncError('Exportacion ilegible o inestable; se reintentara')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def make_opener(config):
    try:
        context = ssl.create_default_context()
        if 'ca_file' in config:
            context.load_verify_locations(cafile=config['ca_file'])
        return build_opener(ProxyHandler({}), HTTPSHandler(context=context), NoRedirect())
    except (OSError, ssl.SSLError, ValueError) as error:
        raise SyncError('No se pudo configurar la verificacion TLS') from error


def request_json(opener, config, path, body=None):
    headers = {'Accept': 'application/json', 'X-Sleep-Sync-Token': config['token']}
    payload = None
    if body is not None:
        payload = encode_json(body)
        require(len(payload) <= MAX_PUSH_BYTES, 'Solicitud demasiado grande')
        headers['Content-Type'] = 'application/json'
    request = Request(config['url'] + path, data=payload, headers=headers,
                      method='POST' if body is not None else 'GET')
    try:
        with opener.open(request, timeout=15) as response:
            require(response.status == 200, 'Respuesta HTTP no satisfactoria')
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            require(len(raw) <= MAX_RESPONSE_BYTES, 'Respuesta demasiado grande')
            return parse_json(raw)
    except HTTPError as error:
        if error.code == 409:
            raise HTTPConflict('Conflicto HTTP 409: el lote no se declara sincronizado') from error
        raise SyncError('Servidor rechazo la solicitud (HTTP {})'.format(error.code)) from error
    except (URLError, OSError, TimeoutError, ValueError, http.client.HTTPException) as error:
        raise SyncError('Servidor no disponible o fallo de TLS') from error


def get_remote(opener, config, source_id):
    revisions = {}
    offset = 0
    while True:
        path = '/api/sleep/sync/status?' + urlencode(
            {'source_id': source_id, 'offset': offset, 'limit': STATUS_PAGE_SIZE})
        result = request_json(opener, config, path)
        require(exact_keys(result, ('source_id', 'nights', 'next_offset'))
                and result['source_id'] == source_id and isinstance(result['nights'], list)
                and len(result['nights']) <= STATUS_PAGE_SIZE, 'Estado remoto invalido')
        for night in result['nights']:
                require(exact_keys(night, ('date', 'revision', 'content_hash', 'deleted'))
                    and valid_date(night['date'])
                    and integer(night['revision']) and 1 <= night['revision'] <= MAX_REVISION
                    and isinstance(night['deleted'], bool)
                    and ((night['deleted'] and night['content_hash'] is None)
                     or (not night['deleted'] and isinstance(night['content_hash'], str)
                         and len(night['content_hash']) == 64
                         and all(char in '0123456789abcdef' for char in night['content_hash'])))
                    and night['date'] not in revisions, 'Revision remota invalida')
                revisions[night['date']] = (night['revision'], night['content_hash'], night['deleted'])
        next_offset = result['next_offset']
        if next_offset is None:
            return revisions
        require(integer(next_offset) and len(result['nights']) > 0
            and next_offset == offset + len(result['nights']), 'Offset remoto invalido')
        offset = next_offset


def encode_json(value):
    return json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def night_hash(night):
    canonical = json.dumps(night, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def batches(nights, source_id, unreadable):
    batch = []
    sample_count = 0
    empty_export = {'schema_version': 1, 'source_id': source_id, 'nights': [], 'unreadable': unreadable}
    empty_size = len(encode_json(empty_export))
    batch_size = empty_size
    for night in nights:
        count = len(night['samples'])
        night_size = len(encode_json(night))
        require(empty_size + night_size <= MAX_PUSH_BYTES,
                'Noche {} supera el limite de bytes de envio'.format(night['date']))
        if batch and (len(batch) == MAX_NIGHTS_PER_BATCH
                      or sample_count + count > MAX_SAMPLES_PER_BATCH
                      or batch_size + 1 + night_size > MAX_PUSH_BYTES):
            yield batch
            batch = []
            sample_count = 0
            batch_size = empty_size
        batch_size += night_size + (1 if batch else 0)
        batch.append(night)
        sample_count += count
    if batch:
        yield batch


def push_batch(opener, config, data, batch):
    export = {'schema_version': 1, 'source_id': data['source_id'],
              'nights': batch, 'unreadable': data['unreadable']}
    try:
        result = request_json(opener, config, '/api/sleep/sync/push', export)
    except HTTPConflict:
        if len(batch) == 1:
            print('Conflicto HTTP 409 para {}'.format(batch[0]['date']), file=sys.stderr)
            return True
        midpoint = len(batch) // 2
        left_conflict = push_batch(opener, config, data, batch[:midpoint])
        right_conflict = push_batch(opener, config, data, batch[midpoint:])
        return left_conflict or right_conflict
    fields = ('imported', 'updated', 'duplicates', 'stale')
    require(exact_keys(result, fields) and all(integer(result[field]) and result[field] >= 0
            for field in fields) and sum(result[field] for field in fields) == len(batch),
            'Resultado de envio invalido; el estado sigue pendiente de comprobacion')
    print('Importadas: {imported}; actualizadas: {updated}; duplicadas: {duplicates}; '
          'obsoletas: {stale}'.format(**result))
    if result['stale']:
        print('Conflicto: el servidor rechazo revisiones obsoletas', file=sys.stderr)
    return bool(result['stale'])


def sync_once(config, opener, send=True):
    data = read_export(config['export_path'])
    if data['unreadable']:
        print('Advertencia: al menos una transferencia anterior es ilegible; '
              'se conservan los datos existentes', file=sys.stderr)
    remote = get_remote(opener, config, data['source_id'])
    pending = []
    conflicts = []
    for night in data['nights']:
        revision, content_hash, deleted = remote.get(night['date'], (0, None, False))
        if deleted:
            conflicts.append((night['date'], 'borrada en ORBIT'))
        elif night['revision'] > revision:
            pending.append(night)
        elif night['revision'] < revision:
            conflicts.append((night['date'], 'revision remota superior'))
        elif night_hash(night) != content_hash:
            conflicts.append((night['date'], 'misma revision con contenido diferente'))
    for date, reason in conflicts:
        print('Conflicto: {} para {}'.format(reason, date), file=sys.stderr)
    print('Pendientes: {}; conflictos: {}'.format(len(pending), len(conflicts)))
    if not send:
        return bool(conflicts)
    push_conflict = False
    for batch in batches(pending, data['source_id'], data['unreadable']):
        if push_batch(opener, config, data, batch):
            push_conflict = True
    final_remote = get_remote(opener, config, data['source_id'])
    verification_conflict = False
    for date, previous in remote.items():
        if previous[2] and final_remote.get(date) != previous:
            print('Conflicto: estado final no coincide para {}'.format(date), file=sys.stderr)
            verification_conflict = True
    for night in data['nights']:
        previous = remote.get(night['date'])
        if previous is not None and previous[2]:
            continue
        expected = (night['revision'], night_hash(night), False)
        if final_remote.get(night['date']) != expected:
            print('Conflicto: estado final no coincide para {}'.format(night['date']), file=sys.stderr)
            verification_conflict = True
    return bool(conflicts or push_conflict or verification_conflict)


def acquire_lock(config_path):
    private_directory(config_path.parent)
    flags = os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
    try:
        descriptor = os.open(str(config_path) + '.lock', flags, 0o600)
        info = os.fstat(descriptor)
        if not (stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                and not info.st_mode & 0o077):
            os.close(descriptor)
            raise SyncError('Archivo de bloqueo inseguro')
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(descriptor)
            raise SyncError('Ya hay otra sincronizacion en ejecucion')
        return descriptor
    except OSError as error:
        raise SyncError('No se puede adquirir el bloqueo') from error


def main(argv=None):
    parser = argparse.ArgumentParser(description='Sincronizacion incremental de sueno')
    parser.add_argument('command', choices=('sync', 'status', 'watch'))
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG, help='Ruta a configuracion privada')
    args = parser.parse_args(argv)
    lock = None
    try:
        config = load_config(args.config)
        lock = acquire_lock(args.config)
        opener = make_opener(config)
        if args.command != 'watch':
            return 2 if sync_once(config, opener, send=args.command == 'sync') else 0
        stopped = threading.Event()
        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, lambda _signum, _frame: stopped.set())
        while not stopped.is_set():
            try:
                conflict = sync_once(config, opener)
                delay = max(config['interval_seconds'], config['retry_seconds'], 300) if conflict \
                    else config['interval_seconds']
            except SyncError as error:
                print('Error: {}'.format(error), file=sys.stderr, flush=True)
                delay = config['retry_seconds']
            stopped.wait(delay)
        return 0
    except (SyncError, OSError) as error:
        print('Error: {}'.format(error) if isinstance(error, SyncError) else 'Error de acceso local',
              file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    finally:
        if lock is not None:
            os.close(lock)


if __name__ == '__main__':
    sys.exit(main())