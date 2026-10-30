import copy
import contextlib
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

import sleep_sync


def example():
    return {
        'schema_version': 1,
        'source_id': '7d2449ea-4b6a-4e8d-a5a2-fd60cc192250',
        'unreadable': False,
        'nights': [{
            'date': '2026-10-30', 'time_zone': 'Europe/Madrid', 'revision': 1,
            'start_ms': 100, 'end_ms': 200, 'deep_ms': 50, 'light_ms': 50,
            'awake_ms': 0, 'overlapping': False,
            'samples': [{'start_ms': 100, 'end_ms': 150, 'state': 'deep'},
                        {'start_ms': 150, 'end_ms': 200, 'state': 'light'}],
        }],
    }


class ExportValidationTests(unittest.TestCase):
    def test_valid_export(self):
        self.assertEqual(sleep_sync.validate_export(example()), example())

    def test_fixed_gmt_offsets(self):
        for zone in ('GMT+00:00', 'GMT-00:00', 'GMT+23:59', 'GMT-23:59'):
            with self.subTest(zone=zone):
                data = example()
                data['nights'][0]['time_zone'] = zone
                self.assertEqual(sleep_sync.validate_export(data), data)
        for zone in ('GMT+24:00', 'GMT-00:60', 'GMT+1:00', 'GMT+01:0'):
            with self.subTest(zone=zone):
                data = example()
                data['nights'][0]['time_zone'] = zone
                with self.assertRaises(sleep_sync.SyncError):
                    sleep_sync.validate_export(data)

    def test_invalid_export_fields(self):
        for field, value in [('revision', True), ('revision', 0), ('date', '20261030'),
                             ('time_zone', '\ud800'),
                             ('samples', [{'start_ms': 0, 'end_ms': 1, 'state': 'other'}])]:
            with self.subTest(field=field, value=value):
                data = copy.deepcopy(example())
                data['nights'][0][field] = value
                with self.assertRaises(sleep_sync.SyncError):
                    sleep_sync.validate_export(data)

    def test_duplicate_json_keys(self):
        with self.assertRaises(sleep_sync.SyncError):
            sleep_sync.parse_json(b'{"a":1,"a":2}')

    def test_clipped_intervals_and_bounds(self):
        data = example()
        data['nights'][0].update(
            samples=[{'start_ms': 50, 'end_ms': 150, 'state': 'deep'},
                     {'start_ms': 150, 'end_ms': 250, 'state': 'light'}])
        self.assertEqual(sleep_sync.validate_export(data), data)
        for field, value in [('revision', 2147483648), ('start_ms', -1),
                             ('end_ms', 253402300800000), ('awake_ms', True)]:
            with self.subTest(field=field):
                invalid = copy.deepcopy(data)
                invalid['nights'][0][field] = value
                with self.assertRaises(sleep_sync.SyncError):
                    sleep_sync.validate_export(invalid)


class Response:
    status = 200

    def __init__(self, value):
        self.data = io.BytesIO(json.dumps(value).encode())

    def read(self, count):
        return self.data.read(count)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.data.close()


class FakeOpener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return Response(response)


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.export_path = self.directory / 'export.json'
        self.config_path = self.directory / 'config.json'
        self.secret = 'synthetic-secret-3fa456-0123456789abcdef'
        self.config_data = {'url': 'https://sleep.example', 'token': self.secret,
                            'export_path': str(self.export_path)}
        self.write_config()
        self.write_export(example())
        self.output = io.StringIO()
        self.errors = io.StringIO()
        stdout_redirect = contextlib.redirect_stdout(self.output)
        stdout_redirect.__enter__()
        self.addCleanup(stdout_redirect.__exit__, None, None, None)
        stderr_redirect = contextlib.redirect_stderr(self.errors)
        stderr_redirect.__enter__()
        self.addCleanup(stderr_redirect.__exit__, None, None, None)
        original_read = sleep_sync.read_export
        patcher = mock.patch.object(sleep_sync, 'read_export',
                                    side_effect=lambda path: original_read(path, pause=lambda seconds: None))
        patcher.start()
        self.addCleanup(patcher.stop)

    def write_config(self):
        self.config_path.write_text(json.dumps(self.config_data), encoding='utf-8')
        self.config_path.chmod(0o600)

    def write_export(self, data):
        self.export_path.write_text(json.dumps(data), encoding='utf-8')

    def status(self, nights, next_offset=None):
        entries = []
        for item in nights:
            night = copy.deepcopy(example()['nights'][0])
            night.update(date=item['date'], revision=item['revision'])
            default_hash = None if item.get('deleted') is True else sleep_sync.night_hash(night)
            entries.append({'deleted': False, **item,
                            'content_hash': item.get('content_hash', default_hash)})
        return {'source_id': example()['source_id'], 'nights': entries, 'next_offset': next_offset}

    def status_pages_for(self, data):
        entries = [{'date': night['date'], 'revision': night['revision'], 'deleted': False,
                    'content_hash': sleep_sync.night_hash(night)} for night in data['nights']]
        pages = []
        for offset in range(0, len(entries), sleep_sync.STATUS_PAGE_SIZE):
            page = entries[offset:offset + sleep_sync.STATUS_PAGE_SIZE]
            next_offset = offset + len(page) if offset + len(page) < len(entries) else None
            pages.append({'source_id': data['source_id'], 'nights': page, 'next_offset': next_offset})
        return pages or [{'source_id': data['source_id'], 'nights': [], 'next_offset': None}]

    def config(self):
        return sleep_sync.load_config(self.config_path)

    def test_only_newer_nights_sent_and_remote_conflict_preserved(self):
        data = example()
        for date, revision in [('2026-10-28', 2), ('2026-10-29', 1), ('2026-10-27', 1)]:
            night = copy.deepcopy(data['nights'][0])
            night.update(date=date, revision=revision)
            data['nights'].append(night)
        self.write_export(data)
        remote = self.status([{'date': '2026-10-28', 'revision': 1},
                              {'date': '2026-10-29', 'revision': 2},
                              {'date': '2026-10-27', 'revision': 1}])
        opener = FakeOpener(remote, {'imported': 1, 'updated': 1, 'duplicates': 0, 'stale': 0},
                    *self.status_pages_for(data))
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual(len(opener.requests), 3)
        self.assertEqual(opener.requests[0].full_url,
                         'https://sleep.example/api/sleep/sync/status?source_id=' + data['source_id']
                         + '&offset=0&limit=500')
        sent = json.loads(opener.requests[1].data)
        self.assertEqual([night['date'] for night in sent['nights']], ['2026-10-30', '2026-10-28'])
        self.assertEqual(set(sent), {'schema_version', 'source_id', 'nights', 'unreadable'})
        self.assertNotIn(self.secret, opener.requests[1].data.decode())
        for request in opener.requests:
            self.assertEqual(request.get_header('X-sleep-sync-token'), self.secret)
            self.assertNotIn(self.secret, request.full_url)
        self.assertIn('2026-10-29', self.errors.getvalue())
        self.assertEqual(json.loads(self.export_path.read_text()), data)

    def test_status_never_posts_and_equal_revision_skipped(self):
        opener = FakeOpener(self.status([{'date': '2026-10-30', 'revision': 1}]))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener, send=False))
        self.assertEqual(len(opener.requests), 1)
        self.assertIn('Pendientes: 0', self.output.getvalue())

    def test_final_status_verifies_sent_night(self):
        night = {'date': '2026-10-30', 'revision': 1}
        opener = FakeOpener(self.status([]),
                            {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            self.status([night]))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual([request.method for request in opener.requests], ['GET', 'POST', 'GET'])

    def test_final_status_reports_concurrent_changes(self):
        night = {'date': '2026-10-30', 'revision': 1}
        for final in ([], [{**night, 'revision': 2}],
                      [{**night, 'content_hash': '0' * 64}],
                      [{**night, 'deleted': True}]):
            with self.subTest(final=final):
                opener = FakeOpener(self.status([]),
                                    {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                                    self.status(final))
                self.assertTrue(sleep_sync.sync_once(self.config(), opener))
                self.assertIn('estado final no coincide para 2026-10-30', self.errors.getvalue())

    def test_final_status_rechecks_previously_matching_night_without_push(self):
        night = {'date': '2026-10-30', 'revision': 1}
        opener = FakeOpener(self.status([night]), self.status([{**night, 'content_hash': '0' * 64}]))
        with mock.patch.object(sleep_sync, 'make_opener', return_value=opener):
            self.assertEqual(sleep_sync.main(['sync', '--config', str(self.config_path)]), 2)
        self.assertEqual([request.method for request in opener.requests], ['GET', 'GET'])

    def test_final_status_checks_confirmed_tombstone(self):
        night = {'date': '2026-10-30', 'revision': 1}
        tombstone = self.status([{**night, 'deleted': True}])
        for final in ([], [night], [{**night, 'revision': 2, 'deleted': True}]):
            with self.subTest(final=final):
                self.errors.seek(0)
                self.errors.truncate(0)
                opener = FakeOpener(tombstone, self.status(final))
                self.assertTrue(sleep_sync.sync_once(self.config(), opener))
                self.assertIn('estado final no coincide para 2026-10-30', self.errors.getvalue())
                self.assertEqual([request.method for request in opener.requests], ['GET', 'GET'])

    def test_final_status_checks_tombstones_outside_export(self):
        data = {**example(), 'nights': []}
        self.write_export(data)
        tombstone = self.status([{'date': '2026-10-29', 'revision': 1, 'deleted': True}])
        opener = FakeOpener(tombstone, self.status([]))
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        self.assertIn('estado final no coincide para 2026-10-29', self.errors.getvalue())

    def test_final_status_failure_is_not_success(self):
        night = {'date': '2026-10-30', 'revision': 1}
        for final in (URLError('synthetic'), self.status([night], 2)):
            with self.subTest(final=final):
                opener = FakeOpener(self.status([]),
                                    {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0}, final)
                with mock.patch.object(sleep_sync, 'make_opener', return_value=opener):
                    self.assertEqual(sleep_sync.main(['sync', '--config', str(self.config_path)]), 1)

    def test_final_conflict_retries_on_next_cycle(self):
        night = {'date': '2026-10-30', 'revision': 1}
        opener = FakeOpener(self.status([]),
                            {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            self.status([]), self.status([]),
                            {'imported': 0, 'updated': 0, 'duplicates': 1, 'stale': 0},
                            self.status([night]))
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual(opener.requests[1].data, opener.requests[4].data)

    def test_stale_response_is_conflict(self):
        opener = FakeOpener(self.status([]), {'imported': 0, 'updated': 0, 'duplicates': 0, 'stale': 1},
                    self.status([]))
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        self.assertIn('obsoletas', self.errors.getvalue())

    def test_http_failure_preserves_pending_on_next_attempt(self):
        failure = HTTPError('https://sleep.example', 503, self.secret, {}, None)
        opener = FakeOpener(self.status([]), failure, self.status([]),
                            {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            *self.status_pages_for(example()))
        with self.assertRaises(sleep_sync.SyncError) as caught:
            sleep_sync.sync_once(self.config(), opener)
        self.assertNotIn(self.secret, str(caught.exception))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual(json.loads(opener.requests[1].data), json.loads(opener.requests[3].data))

    def test_bad_token_and_network_error_are_sanitized(self):
        for error in (HTTPError('https://sleep.example', 401, self.secret, {}, None),
                      URLError(self.secret)):
            with self.subTest(error=type(error)):
                opener = FakeOpener(error)
                with self.assertRaises(sleep_sync.SyncError) as caught:
                    sleep_sync.sync_once(self.config(), opener)
                self.assertNotIn(self.secret, str(caught.exception))
                self.assertEqual(len(opener.requests), 1)

    def test_invalid_remote_or_push_response_fails_closed(self):
        for response in ({'source_id': 'other', 'nights': []},
                         self.status([{'date': '2026-10-30', 'revision': True}]),
                         {'source_id': example()['source_id'],
                          'nights': [{'date': '2026-10-30', 'revision': 1}]},
                         self.status([{'date': '2026-10-30', 'revision': 1,
                                       'content_hash': 'G' * 64}]),
                         self.status([{'date': '2026-10-30', 'revision': 1,
                                       'content_hash': 'a' * 63}])):
            opener = FakeOpener(response)
            with self.assertRaises(sleep_sync.SyncError):
                sleep_sync.sync_once(self.config(), opener)
            self.assertEqual(len(opener.requests), 1)
        opener = FakeOpener(self.status([]), {'imported': 0, 'updated': 0, 'duplicates': 0, 'stale': 0})
        with self.assertRaises(sleep_sync.SyncError):
            sleep_sync.sync_once(self.config(), opener)

    def test_private_configuration_and_https_enforced(self):
        self.config_path.chmod(0o644)
        with self.assertRaises(sleep_sync.SyncError):
            self.config()
        self.config_path.chmod(0o600)
        for key, value in [('token', ''), ('token', 'x' * 31), ('token', 'token\nheader'),
                           ('url', 'http://sleep.example'), ('url', 'https://user:pass@sleep.example'),
                           ('url', 'https://sleep.example/other')]:
            with self.subTest(key=key, value=value):
                original = self.config_data[key]
                self.config_data[key] = value
                self.write_config()
                with self.assertRaises(sleep_sync.SyncError):
                    self.config()
                self.config_data[key] = original
        del self.config_data['token']
        self.write_config()
        with self.assertRaises(sleep_sync.SyncError):
            self.config()

    def test_minimum_length_token_accepted(self):
        self.config_data['token'] = 'x' * 32
        self.write_config()
        self.assertEqual(self.config()['token'], 'x' * 32)

    def test_symlink_and_insecure_config_directory_rejected(self):
        linked = self.directory / 'linked.json'
        linked.symlink_to(self.config_path)
        with self.assertRaises(sleep_sync.SyncError):
            sleep_sync.load_config(linked)
        self.directory.chmod(0o777)
        with self.assertRaises(sleep_sync.SyncError):
            self.config()
        self.directory.chmod(0o700)

    def test_exclusive_lock(self):
        first = sleep_sync.acquire_lock(self.config_path)
        try:
            with self.assertRaises(sleep_sync.SyncError):
                sleep_sync.acquire_lock(self.config_path)
        finally:
            os.close(first)
        second = sleep_sync.acquire_lock(self.config_path)
        os.close(second)

    def test_read_retry_and_incomplete_exports(self):
        good = json.dumps(example()).encode()
        with mock.patch.object(sleep_sync, 'read_once', side_effect=[b'{', good, good]):
            self.assertEqual(sleep_sync.read_export('ignored'), example())
        data = example()
        data['unreadable'] = True
        self.write_export(data)
        opener = FakeOpener(self.status([]), {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                    *self.status_pages_for(data))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertTrue(json.loads(opener.requests[1].data)['unreadable'])
        self.assertIn('Advertencia', self.errors.getvalue())
        self.assertEqual(json.loads(self.export_path.read_text()), data)

    def test_equal_revision_with_different_content_is_conflict(self):
        remote = self.status([{'date': '2026-10-30', 'revision': 1, 'content_hash': '0' * 64}])
        opener = FakeOpener(remote, remote)
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual(len(opener.requests), 2)
        self.assertIn('contenido diferente', self.errors.getvalue())

    def test_tombstone_blocks_every_local_revision_and_returns_conflict(self):
        for command in ('status', 'sync'):
            for local_revision in (1, 2, 3):
                with self.subTest(command=command, local_revision=local_revision):
                    data = example()
                    data['nights'][0]['revision'] = local_revision
                    self.write_export(data)
                    original = self.export_path.read_bytes()
                    tombstone = self.status([{'date': '2026-10-30', 'revision': 2, 'deleted': True}])
                    opener = FakeOpener(tombstone, tombstone) if command == 'sync' else FakeOpener(tombstone)
                    self.errors.truncate(0)
                    self.errors.seek(0)
                    with mock.patch.object(sleep_sync, 'make_opener', return_value=opener):
                        self.assertEqual(sleep_sync.main([command, '--config', str(self.config_path)]), 2)
                    self.assertEqual([request.method for request in opener.requests],
                                     ['GET', 'GET'] if command == 'sync' else ['GET'])
                    self.assertIn('borrada en ORBIT para 2026-10-30', self.errors.getvalue())
                    self.assertEqual(self.export_path.read_bytes(), original)

    def test_mixed_pages_block_tombstone_but_send_other_pending_nights(self):
        data = example()
        data['nights'] = [{**data['nights'][0], 'date': date, 'revision': revision}
                          for date, revision in [('2026-10-28', 2), ('2026-10-29', 1),
                                                 ('2026-10-30', 5), ('2026-10-31', 1)]]
        self.write_export(data)
        original = self.export_path.read_bytes()
        opener = FakeOpener(
            self.status([{'date': '2026-10-28', 'revision': 1},
                         {'date': '2026-10-29', 'revision': 1}], 2),
            self.status([{'date': '2026-10-30', 'revision': 1, 'deleted': True}]),
            {'imported': 1, 'updated': 1, 'duplicates': 0, 'stale': 0},
            self.status([{'date': '2026-10-28', 'revision': 2},
                         {'date': '2026-10-29', 'revision': 1}], 2),
            self.status([{'date': '2026-10-30', 'revision': 1, 'deleted': True},
                         {'date': '2026-10-31', 'revision': 1}]))
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual([request.method for request in opener.requests], ['GET', 'GET', 'POST', 'GET', 'GET'])
        self.assertEqual([night['date'] for night in json.loads(opener.requests[2].data)['nights']],
                         ['2026-10-28', '2026-10-31'])
        self.assertIn('borrada en ORBIT para 2026-10-30', self.errors.getvalue())
        self.assertEqual(self.export_path.read_bytes(), original)

    def test_empty_status_and_empty_final_page(self):
        opener = FakeOpener(self.status([]),
                            {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            *self.status_pages_for(example()))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual(json.loads(opener.requests[1].data)['nights'], example()['nights'])
        opener = FakeOpener(self.status([{'date': '2026-10-30', 'revision': 1}], 1),
                            self.status([]), *self.status_pages_for(example()))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual([request.method for request in opener.requests], ['GET', 'GET', 'GET'])

    def test_invalid_deleted_hash_combinations_fail_before_push(self):
        for deleted, content_hash in [(True, 'a' * 64), (False, None), (1, None),
                                      (0, 'a' * 64), ('true', None), (None, None),
                                      (False, 'A' * 64)]:
            with self.subTest(deleted=deleted, content_hash=content_hash):
                opener = FakeOpener(self.status([{'date': '2026-10-30', 'revision': 1,
                                                  'deleted': deleted, 'content_hash': content_hash}]))
                with self.assertRaises(sleep_sync.SyncError):
                    sleep_sync.sync_once(self.config(), opener)
                self.assertEqual([request.method for request in opener.requests], ['GET'])

    def test_duplicate_date_across_live_and_tombstone_pages_fails_closed(self):
        opener = FakeOpener(self.status([{'date': '2026-10-30', 'revision': 1}], 1),
                            self.status([{'date': '2026-10-30', 'revision': 2, 'deleted': True}]))
        with self.assertRaises(sleep_sync.SyncError):
            sleep_sync.sync_once(self.config(), opener)
        self.assertEqual([request.method for request in opener.requests], ['GET', 'GET'])

    def test_hash_canonical_unicode(self):
        night = example()['nights'][0]
        night['time_zone'] = 'Zürich'
        canonical = json.dumps(night, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        self.assertEqual(sleep_sync.night_hash(night), hashlib.sha256(canonical.encode('utf-8')).hexdigest())
        self.assertNotEqual(sleep_sync.night_hash(night), hashlib.sha256(
            json.dumps(night, sort_keys=True, separators=(',', ':')).encode()).hexdigest())

    def test_409_preserves_file_and_retries(self):
        data = example()
        failure = HTTPError('https://sleep.example', 409, 'conflict', {}, None)
        opener = FakeOpener(self.status([]), failure, self.status([]), self.status([]),
                    {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                    *self.status_pages_for(data))
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        self.assertIn('409 para 2026-10-30', self.errors.getvalue())
        self.assertEqual(json.loads(self.export_path.read_text()), data)
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual(opener.requests[1].data, opener.requests[4].data)

    def test_source_change_uses_new_status(self):
        first = example()
        second = copy.deepcopy(first)
        second['source_id'] = '7d2449ea-4b6a-4e8d-a5a2-fd60cc192251'
        opener = FakeOpener(self.status([]), {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            *self.status_pages_for(first),
                            {'source_id': second['source_id'], 'nights': [], 'next_offset': None},
                            {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            *self.status_pages_for(second))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.write_export(second)
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertIn(second['source_id'], opener.requests[3].full_url)
        self.assertEqual(json.loads(opener.requests[4].data)['source_id'], second['source_id'])

    def test_paginated_status_and_large_history(self):
        first = datetime.date(2000, 1, 1)
        nights = [{'date': (first + datetime.timedelta(days=index)).isoformat(), 'revision': 1}
                  for index in range(4001)]
        pages = [self.status(nights[offset:offset + 500],
                             offset + 500 if offset + 500 < len(nights) else None)
                 for offset in range(0, len(nights), 500)]
        opener = FakeOpener(*pages)
        remote = sleep_sync.get_remote(opener, self.config(), example()['source_id'])
        self.assertEqual(len(remote), 4001)
        for index, request in enumerate(opener.requests):
            query = parse_qs(urlsplit(request.full_url).query)
            self.assertEqual(query, {'source_id': [example()['source_id']],
                                     'offset': [str(index * 500)], 'limit': ['500']})

    def test_invalid_pagination_fails_before_push(self):
        entry = {'date': '2026-10-30', 'revision': 1}
        invalid_pages = [
            [self.status([], 0)], [self.status([], 1)],
            [self.status([entry], 2)],
            [self.status([entry], True)], [self.status([entry], -1)],
            [self.status([entry], 0)], [self.status([entry], '1')],
            [self.status([entry], 1), self.status([{'date': '2026-10-29', 'revision': 1}], 3)],
            [self.status([entry], 1), self.status([entry])],
            [self.status([entry], 1), {**self.status([]), 'source_id': 'other'}],
            [self.status([entry], 1), self.status([], 1)],
            [self.status([{'date': (datetime.date(2020, 1, 1) + datetime.timedelta(days=index)).isoformat(),
                           'revision': 1} for index in range(501)])],
        ]
        for pages in invalid_pages:
            with self.subTest(pages=len(pages), next_offset=pages[0]['next_offset']):
                opener = FakeOpener(*pages)
                with self.assertRaises(sleep_sync.SyncError):
                    sleep_sync.sync_once(self.config(), opener)
                self.assertTrue(all(request.method == 'GET' for request in opener.requests))

    def test_response_limit_is_applied_to_each_page(self):
        first = self.status([{'date': '2026-10-29', 'revision': 1}], 1)
        second = self.status([{'date': '2026-10-30', 'revision': 1},
                              {'date': '2026-10-28', 'revision': 1}])
        opener = FakeOpener(first, second)
        with mock.patch.object(sleep_sync, 'MAX_RESPONSE_BYTES', len(json.dumps(first).encode())):
            with self.assertRaisesRegex(sleep_sync.SyncError, 'Respuesta demasiado grande'):
                sleep_sync.sync_once(self.config(), opener)
        self.assertEqual(parse_qs(urlsplit(opener.requests[1].full_url).query)['offset'], ['1'])

    def test_409_divides_and_sends_other_nights(self):
        data = example()
        data['nights'] = [{**data['nights'][0], 'date': date}
                          for date in ('2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30')]
        self.write_export(data)
        conflict = HTTPError('https://sleep.example', 409, self.secret, {}, None)
        ok_one = {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0}
        ok_two = {'imported': 2, 'updated': 0, 'duplicates': 0, 'stale': 0}
        opener = FakeOpener(self.status([]), conflict, conflict, ok_one, conflict, ok_two,
                    self.status([{'date': date, 'revision': 1}
                         for date in ('2026-10-27', '2026-10-29', '2026-10-30')]))
        self.assertTrue(sleep_sync.sync_once(self.config(), opener))
        sent = [[night['date'] for night in json.loads(request.data)['nights']]
                for request in opener.requests if request.method == 'POST']
        self.assertEqual(sent, [['2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30'],
                                ['2026-10-27', '2026-10-28'], ['2026-10-27'],
                                ['2026-10-28'], ['2026-10-29', '2026-10-30']])
        self.assertIn('409 para 2026-10-28', self.errors.getvalue())
        self.assertNotIn(self.secret, self.output.getvalue() + self.errors.getvalue())
        self.assertEqual(json.loads(self.export_path.read_text()), data)

    def test_main_returns_conflict_for_single_409(self):
        conflict = HTTPError('https://sleep.example', 409, self.secret, {}, None)
        with mock.patch.object(sleep_sync, 'make_opener',
                       return_value=FakeOpener(self.status([]), conflict, self.status([]))):
            self.assertEqual(sleep_sync.main(['sync', '--config', str(self.config_path)]), 2)
        self.assertNotIn(self.secret, self.errors.getvalue())

    def test_batch_byte_boundaries_and_oversized_night(self):
        data = example()
        night = data['nights'][0]
        another = {**night, 'date': '2026-10-29'}
        export = {'schema_version': 1, 'source_id': data['source_id'],
                  'nights': [night, another], 'unreadable': False}
        exact = len(sleep_sync.encode_json(export))
        with mock.patch.object(sleep_sync, 'MAX_PUSH_BYTES', exact):
            self.assertEqual(list(sleep_sync.batches([night, another], data['source_id'], False)),
                             [[night, another]])
        with mock.patch.object(sleep_sync, 'MAX_PUSH_BYTES', exact - 1):
            opener = FakeOpener(self.status([]),
                                {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                                {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                                *self.status_pages_for({**data, 'nights': [night, another]}))
            self.write_export({**data, 'nights': [night, another]})
            self.assertFalse(sleep_sync.sync_once(self.config(), opener))
            self.assertTrue(all(len(request.data) <= exact - 1 for request in opener.requests
                                if request.method == 'POST'))
        one_size = len(sleep_sync.encode_json({**export, 'nights': [night]}))
        with mock.patch.object(sleep_sync, 'MAX_PUSH_BYTES', one_size - 1):
            with self.assertRaisesRegex(sleep_sync.SyncError, '2026-10-30.*limite'):
                list(sleep_sync.batches([night], data['source_id'], False))

    def test_large_batches_follow_night_limit(self):
        data = example()
        template = data['nights'][0]
        first = datetime.date(2010, 1, 1)
        data['nights'] = [{**template, 'date': (first + datetime.timedelta(days=index)).isoformat()}
                          for index in range(3661)]
        self.write_export(data)
        opener = FakeOpener(self.status([]),
                            {'imported': 3660, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                            *self.status_pages_for(data))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        self.assertEqual([len(json.loads(request.data)['nights']) for request in opener.requests
                  if request.method == 'POST'],
                         [3660, 1])

    def test_sample_count_limits_batches(self):
        night = example()['nights'][0]
        night.update(start_ms=0, end_ms=10000, deep_ms=10000, light_ms=0,
                     samples=[{'start_ms': index, 'end_ms': index + 1, 'state': 'deep'}
                              for index in range(10000)])
        nights = [{**night, 'date': (datetime.date(2026, 1, 1) + datetime.timedelta(days=index)).isoformat()}
                  for index in range(11)]
        self.write_export({**example(), 'nights': nights})
        opener = FakeOpener(self.status([]),
                    {'imported': 10, 'updated': 0, 'duplicates': 0, 'stale': 0},
                    {'imported': 1, 'updated': 0, 'duplicates': 0, 'stale': 0},
                    *self.status_pages_for({**example(), 'nights': nights}))
        self.assertFalse(sleep_sync.sync_once(self.config(), opener))
        sent = [json.loads(request.data)['nights'] for request in opener.requests if request.method == 'POST']
        self.assertEqual([len(batch) for batch in sent], [10, 1])
        self.assertEqual([sum(len(night['samples']) for night in batch) for batch in sent],
                 [100000, 10000])

    def test_invalid_export_never_requests(self):
        for mutation in ('totals', 'overlap', 'no_sleep', 'too_many_samples', 'bad_zone', 'interval'):
            with self.subTest(mutation=mutation):
                data = example()
                night = data['nights'][0]
                if mutation == 'totals':
                    night['deep_ms'] += 1
                elif mutation == 'overlap':
                    night['samples'][1]['start_ms'] = 149
                elif mutation == 'no_sleep':
                    night.update(deep_ms=0, light_ms=0, awake_ms=100,
                                 samples=[{'start_ms': 100, 'end_ms': 200, 'state': 'awake'}])
                elif mutation == 'too_many_samples':
                    night['samples'] = night['samples'] * 5001
                elif mutation == 'bad_zone':
                    night['time_zone'] = 'Not/AZone'
                else:
                    night['end_ms'] = night['start_ms']
                self.write_export(data)
                opener = FakeOpener()
                with self.assertRaises(sleep_sync.SyncError):
                    sleep_sync.sync_once(self.config(), opener)
                self.assertEqual(opener.requests, [])

    def test_mutable_and_oversized_exports_not_sent(self):
        good = json.dumps(example()).encode()
        changed = json.dumps({**example(), 'nights': []}).encode()
        with mock.patch.object(sleep_sync, 'read_once', side_effect=[good, changed] * 3):
            with self.assertRaises(sleep_sync.SyncError):
                sleep_sync.read_export('ignored')
        with mock.patch.object(sleep_sync, 'MAX_EXPORT_BYTES', 4):
            with self.assertRaises(sleep_sync.SyncError):
                sleep_sync.read_once(self.export_path)

    def test_tls_verification_and_redirects(self):
        opener = sleep_sync.make_opener(self.config())
        handler = next(item for item in opener.handlers if isinstance(item, sleep_sync.HTTPSHandler))
        self.assertTrue(handler._context.check_hostname)
        self.assertEqual(handler._context.verify_mode, sleep_sync.ssl.CERT_REQUIRED)
        self.assertIsNone(sleep_sync.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other'))

    def test_additional_ca_is_loaded_and_invalid_ca_fails_closed(self):
        config = {**self.config(), 'ca_file': '/synthetic/ca.pem'}
        context = mock.Mock()
        with mock.patch.object(sleep_sync.ssl, 'create_default_context', return_value=context), \
             mock.patch.object(sleep_sync, 'build_opener'):
            sleep_sync.make_opener(config)
        context.load_verify_locations.assert_called_once_with(cafile='/synthetic/ca.pem')
        context.load_verify_locations.side_effect = sleep_sync.ssl.SSLError('bad CA')
        with mock.patch.object(sleep_sync.ssl, 'create_default_context', return_value=context):
            with self.assertRaises(sleep_sync.SyncError):
                sleep_sync.make_opener(config)

    def test_main_never_prints_token_on_failure(self):
        with mock.patch.object(sleep_sync, 'make_opener', return_value=FakeOpener(URLError(self.secret))):
            self.assertEqual(sleep_sync.main(['sync', '--config', str(self.config_path)]), 1)
        self.assertNotIn(self.secret, self.output.getvalue() + self.errors.getvalue())

    def test_watch_retries_then_uses_normal_interval_and_releases_lock(self):
        class FakeEvent:
            def __init__(self):
                self.delays = []

            def is_set(self):
                return len(self.delays) >= 2

            def wait(self, delay):
                self.delays.append(delay)

            def set(self):
                pass

        event = FakeEvent()
        with mock.patch.object(sleep_sync, 'make_opener', return_value=FakeOpener()), \
             mock.patch.object(sleep_sync.threading, 'Event', return_value=event), \
             mock.patch.object(sleep_sync.signal, 'signal') as signals, \
             mock.patch.object(sleep_sync, 'sync_once', side_effect=[sleep_sync.SyncError('fallo'), False]):
            self.assertEqual(sleep_sync.main(['watch', '--config', str(self.config_path)]), 0)
        self.assertEqual(event.delays, [30, 300])
        self.assertEqual(signals.call_count, 2)
        descriptor = sleep_sync.acquire_lock(self.config_path)
        os.close(descriptor)

    def test_watch_spaces_persistent_tombstone_conflicts(self):
        class FakeEvent:
            def __init__(self):
                self.delays = []

            def is_set(self):
                return len(self.delays) >= 2

            def wait(self, delay):
                self.delays.append(delay)

            def set(self):
                pass

        event = FakeEvent()
        self.config_data.update(interval_seconds=1, retry_seconds=2)
        self.write_config()
        tombstone = self.status([{'date': '2026-10-30', 'revision': 1, 'deleted': True}])
        opener = FakeOpener(tombstone, tombstone, tombstone, tombstone)
        with mock.patch.object(sleep_sync, 'make_opener', return_value=opener), \
             mock.patch.object(sleep_sync.threading, 'Event', return_value=event), \
             mock.patch.object(sleep_sync.signal, 'signal'):
            self.assertEqual(sleep_sync.main(['watch', '--config', str(self.config_path)]), 0)
        self.assertEqual(event.delays, [300, 300])
        self.assertEqual([request.method for request in opener.requests], ['GET', 'GET', 'GET', 'GET'])


if __name__ == '__main__':
    unittest.main()