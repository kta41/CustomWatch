# Puente de sueno para Termux

El puente usa Python 3 y su biblioteca estandar. No instala paquetes, no crea la
exportacion y no configura ni inicia el servidor. Necesita un servidor compatible
con el contrato de sincronizacion de sueno v1 y un JSON de exportacion ya
disponible como archivo local legible por Termux.

## Preparacion manual

En Termux, prepara un directorio privado y un archivo de configuracion:

```sh
mkdir -p "$HOME/.config/customwatch"
chmod 700 "$HOME/.config/customwatch"
touch "$HOME/.config/customwatch/orbit-sync.json"
chmod 600 "$HOME/.config/customwatch/orbit-sync.json"
```

Edita ese archivo localmente con un editor privado. Introduce el token en el
editor, no en comandos, argumentos, variables de entorno ni historial del shell.
El esquema es el siguiente; reemplaza los marcadores por los valores reales:

```json
{
  "url": "https://servidor-ejemplo",
  "token": "INTRODUCIR_TOKEN_EN_EL_EDITOR",
  "export_path": "/ruta/absoluta/export.json",
  "ca_file": "/ruta/absoluta/ca-adicional.pem",
  "interval_seconds": 300,
  "retry_seconds": 30
}
```

`ca_file`, `interval_seconds` y `retry_seconds` son opcionales. La CA adicional
se suma al almacen de confianza predeterminado; el certificado y el nombre del
servidor se verifican siempre. La URL debe ser el origen HTTPS explicito, sin
usuario, contrasena, ruta, consulta ni fragmento. No se admiten HTTP,
redirecciones ni descubrimiento automatico. El acceso a la exportacion depende
de los permisos Android/Termux y de la forma en que el archivo llegue al
almacenamiento compartido; el puente no configura permisos SAF ni genera archivos.

El archivo de configuracion debe ser regular, propiedad del usuario y no
accesible a grupo ni otros; su directorio debe ser propiedad del usuario y no
permitir escritura de grupo u otros. Se rechazan enlaces simbolicos al archivo
de configuracion. Manten el token y las copias de seguridad fuera de carpetas
compartidas. El token solo se envia en `X-Sleep-Sync-Token`; el cuerpo JSON y la
salida no lo incluyen. Usa un token aleatorio de al menos 32 caracteres ASCII
imprimibles sin espacios, igual al configurado en el backend. Python debe tener
acceso a una base de zonas horarias IANA para validar `time_zone`; tambien se
aceptan desplazamientos fijos `GMT+HH:MM` y `GMT-HH:MM`, de 00:00 a 23:59.

## Uso

Desde la carpeta del puente:

```sh
python3 sleep_sync.py status
python3 sleep_sync.py sync
python3 sleep_sync.py watch
```

Puede seleccionarse otra configuracion con `--config /ruta/privada/config.json`.
`status` consulta sin enviar; `sync` hace una sola pasada; `watch` continua hasta
SIGINT o SIGTERM. El intervalo normal predeterminado es 300 segundos, el de
reintento es 30 segundos y ambos aceptan enteros de 1 a 86400. Un error activa
el intervalo de reintento. Un conflicto espera el mayor de ambos intervalos y
300 segundos para evitar consultas repetitivas; cada pasada consulta el estado
remoto. Las solicitudes tienen un timeout de 15 segundos. El bloqueo
exclusivo es por archivo de configuracion y se mantiene hasta terminar, tambien
en `status`; usa una unica configuracion para una misma fuente. La salida limpia
de `watch` espera a que termine la solicitud en curso. `sync` y `status` devuelven
0 en exito, 1 en error y 2 ante conflictos. `watch` informa de errores y continua.

Se comparan las revisiones por fecha y `source_id`, consultando la fuente de
cada exportacion incluso si cambia entre pasadas. Se envian solo noches nuevas
o con revision local superior, en lotes de hasta 3660 noches, 100000 muestras
y 16 MiB de JSON UTF-8, con hasta 10000 muestras por noche. Si una sola noche
excede el limite de envio se indica su fecha y se interrumpe la pasada. Una revision igual solo se considera
sincronizada si coincide el hash; contenido distinto, una revision remota
superior o una respuesta `stale` se informan como conflicto. Si la fecha esta
borrada en ORBIT se informa como conflicto y no se envia, sea cual sea la
revision local. Las otras noches pendientes si se envian. Un HTTP 409 divide
el lote recursivamente; se informa la fecha de cada noche conflictiva y se
envian las restantes. La pasada devuelve conflicto y se vuelve a consultar el
servidor en la siguiente pasada. El archivo
local nunca se modifica ni se borra, tampoco tras errores. Los lotes anteriores
pueden haberse confirmado: cada pasada consulta el servidor de nuevo. El servidor
debe clasificar cada noche enviada en exactamente uno de `imported`, `updated`,
`duplicates` o `stale`.

El estado remoto se consulta con `source_id`, `offset=0` y `limit=500`; cada
respuesta debe contener `source_id`, `nights` y `next_offset` (entero o null).
Una continuacion debe ser exactamente `offset + len(nights)`; se rechazan
saltos, paginas vacias con continuacion, fechas repetidas y cambios de fuente.
Cada respuesta tiene un limite de 1 MiB, sin limite artificial para el historial
completo. Cada noche contiene `date`, `revision`, `content_hash` y `deleted`.
Una noche viva tiene `deleted: false` y un hash; una borrada tiene
`deleted: true` y `content_hash: null`. El hash es
SHA-256 hexadecimal en minusculas del
JSON de la noche codificado en UTF-8 con `sort_keys=True`,
`separators=(',', ':')` y `ensure_ascii=False`. El backend debe implementar este
contrato y utilizar el mismo calculo para los hashes almacenados; el estado
antiguo sin hashes se rechaza, no se presupone sincronizado. El hash detecta
diferencias, pero no autentica datos.

Al final de cada `sync`, incluso si no habia envios, se vuelve a consultar el
estado completo. Se comprueba revision y hash de todas las noches locales,
incluidas las enviadas, y que los borrados detectados sigan presentes con su
revision, tambien si no figuran en la exportacion. Una discrepancia se informa como conflicto y la siguiente pasada
vuelve a consultar; si falla la consulta final, la pasada termina con error.
El backend debe proteger la reimportacion de noches borradas incluso si el
borrado ocurre entre la consulta y el envio. Las paginas no son una instantanea:
una modificacion concurrente puede desplazar entradas o suceder despues de su
lectura. La comprobacion final detecta discrepancias observables, pero no garantiza
una instantanea linealizable ni ausencia de cambios posteriores.

La exportacion v1 debe contener exactamente `schema_version`, `source_id`,
`nights` y `unreadable`, con los campos y tipos del contrato. Una exportacion
marcada `unreadable: true` indica que al menos una transferencia anterior fallo:
se muestra una advertencia y se importan las noches validas sin borrar datos.
Se rechazan noches sin tiempo dormido efectivo, intervalos invalidos, muestras
desordenadas o solapadas y totales que no coincidan con las muestras recortadas
al intervalo de la noche. Se rechazan JSON invalido, claves
duplicadas, campos ajenos al contrato, fechas duplicadas y archivos mayores de
16 MiB. Se valida y compara el contenido de dos lecturas separadas; si falla se
reintenta hasta tres veces por pasada. Esto detecta muchos cambios y truncados,
pero no garantiza una instantanea atomica ni detecta toda escritura pausada: SAF
no ofrece aqui una garantia de atomicidad ni de confidencialidad: el proveedor o
otras aplicaciones autorizadas pueden acceder a la exportacion. Protege tanto el
archivo como sus copias. Un productor debe evitar publicar
exportaciones parciales que sean JSON valido, especialmente durante un largo
periodo de inactividad.

## Pruebas

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_sleep_sync
```

Las pruebas usan datos sinteticos y transporte simulado; no necesitan red ni
dispositivos. Instalar o ejecutar el puente contra un servidor real es una
operacion manual posterior.