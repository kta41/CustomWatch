# CustomWatch

Aplicacion Android experimental para el reloj **Prixton Life Fit**. Implementa un cliente Bluetooth Low Energy (BLE) propio y parte del protocolo observado en el dispositivo. No implementa todas las funciones del reloj y no esta afiliada a Prixton.

## Estado funcional

| Funcion | Implementacion | Estado |
| --- | --- | --- |
| Conexion BLE | Servicio en primer plano, reconexion y cola de escritura | Conexion comprobada en el Prixton de prueba. |
| Bateria | Consulta al conectar y cada diez minutos; conserva la ultima lectura | El porcentaje y estado de carga se han comprobado con el reloj. |
| Buscar reloj | Orden de busqueda | Confirmada la vibracion; el reloj muestra la pantalla de busqueda. |
| WhatsApp | Avisos de WhatsApp y Business clasificados como conversacion individual | Recepcion comprobada; filtrado con conversaciones reales pendiente de verificar. |
| Teams | Avisos individuales y de grupo, abreviacion del remitente | Recepcion comprobada. El icono SMS verde se comprobo con un aviso sintetico, no con un mensaje real de Teams. |
| Musica | Los botones del reloj envian controles multimedia y volumen al telefono | El usuario ha confirmado que funciona. No se muestran titulos ni artistas. |
| Alarma del movil | Detecta ciertos avisos activos y repite la orden de buscar reloj | La simulacion anterior vibro, pero muestra la pantalla de busqueda, **no una pantalla de alarma**. Repeticion y alarma real pendientes de comprobacion fisica. |
| Actividad | Consulta pasos, distancia y calorias y guarda la ultima consulta de cada fecha | El reloj no envia fecha ni reparto horario; la fecha guardada es la de consulta. Las unidades siguen pendientes de comprobacion en el reloj. |
| Salud | Consulta de la ultima medicion | Experimental; la respuesta no aporta fecha y la temperatura es una interpretacion sin verificar. No usar para fines medicos. |
| Deporte | Consulta de estado | Experimental; no implementa gestion de entrenamientos. |
| Clima | Pruebas manuales con datos ficticios | No se ha observado cambio visible con la prueba basica. El formato con fecha y la compatibilidad estan pendientes; no hay clima real ni actualizacion automatica. |
| Sueno | Resumen diario e historial experimental guardado en el movil | Una noche sincronizada mostro 5 h 28 min, igual al total del reloj; desglose sin contrastar de forma independiente. |

La confirmacion BLE solo indica que el reloj ha confirmado una trama. No prueba que haya mostrado contenido, vibrado o aceptado semanticamente el comando.

## Interfaz y datos

La navegacion principal contiene Inicio, Sueno, Actividad, Pulso y Ajustes. Sueno dibuja los intervalos reales guardados, permite seleccionar cada fase y mantiene visibles los huecos; el periodo se limita al primer y ultimo intervalo de sueno, sin contar como dormidos los intervalos despierto. No se calcula una puntuacion de calidad ni se infiere REM. El objetivo de pasos es opcional y lo introduce el usuario. Las consultas de actividad guardan su fecha de recepcion, no una fecha de medicion suministrada por el reloj. Pulso guarda solamente la ultima lectura, cuya fecha de medicion es desconocida; no se generan tendencias ni estadisticas de ella.

En Inicio, **Actualizar resumen** solicita actividad, ultima lectura de pulso y bateria; no solicita ni sincroniza el historial de sueno. La bateria muestra la ultima lectura conservada y su fecha de consulta; se vuelve a consultar al conectar y cada diez minutos mientras la conexion permanezca activa.

La informacion de salud y las preferencias permanecen en almacenamiento privado local; la app no solicita acceso a Internet. Las bases de datos no tienen copia automatica: desinstalar o borrar los datos de CustomWatch elimina el historial. Las pantallas anteriores y las herramientas manuales permanecen accesibles en Ajustes, Herramientas avanzadas.

## Arquitectura

El proyecto contiene una sola aplicacion Java nativa, sin framework de interfaz externo ni dependencias de ejecucion adicionales:

```text
app/src/main/AndroidManifest.xml
app/src/main/java/es/lifefit/watch/
	MainActivity.java              Interfaz, permisos y acciones de prueba
	ProductActivity.java           Navegacion y pantallas principales
	SleepTimelineView.java         Grafico interactivo de intervalos de sueno
	SleepRepository.java           Lectura tipada del archivo de sueno
	SleepMetrics.java              Calculos a partir de intervalos no solapados
	HealthRepository.java          Persistencia de consultas de actividad y ultima lectura de pulso
	NotificationSettings.java      Seleccion global y por aplicacion
	WatchService.java              Conexion BLE, colas, recepcion y estado
	NotificationRelayService.java Listener y seleccion de avisos del movil
	NotificationRouting.java       Reglas de seleccion y formato de remitentes
	NotificationPayload.java       Codificacion de texto y remitente
	ProtocolFrame.java              Tramas y CRC
	WatchActivity.java             Resumen de pasos, distancia, calorias y sueno
	WatchBattery.java              Respuesta de bateria
	WatchHealth.java               Respuesta de ultima medicion
	WatchSport.java                Respuesta de estado deportivo
	WatchWeather.java              Datos ficticios de prueba de clima
	WatchMusic.java                Decodificacion de controles multimedia
	WatchAlarms.java               Decodificacion de alarmas del reloj, sin UI activa
tests/                           Pruebas Java independientes de Android
```

`WatchService` usa un `Handler` en el hilo principal para serializar cambios de estado y operaciones GATT. Tras descubrir el servicio, activa el descriptor de notificaciones, solicita un MTU de 191 y se considera listo al finalizar la negociacion o su espera. Fragmenta las tramas segun el MTU, escribe con respuesta GATT y ensambla los fragmentos recibidos. Reintenta la conexion tras una desconexion mientras el servicio siga activo.

Los avisos tienen una cola propia de hasta diez mensajes y esperan confirmacion por numero de secuencia durante ocho segundos. Las consultas mantienen sus propios estados y plazos, pero comparten la cola de escritura GATT; una operacion sin respuesta informa de tiempo agotado. Las respuestas recibidas se comprueban con CRC antes de procesarlas y confirmarlas. La conexion no implica vinculacion automatica con la app original; puede ser necesario desconectarla antes de usar CustomWatch.

## Instalacion y ejecucion

Requisitos: Android 8.0 o posterior (API 26), Bluetooth LE, JDK 17 o superior, Android SDK con plataforma y Build Tools 35, y Gradle 8.9. El proyecto utiliza Android Gradle Plugin 8.7.3 y no incluye Gradle Wrapper.

Desde este directorio:

```sh
export JAVA_HOME=/ruta/al/jdk
export ANDROID_HOME=/ruta/al/android-sdk
gradle :app:assembleDebug
```

El resultado es `app/build/outputs/apk/debug/CustomWatch-debug.apk`. Es una APK de depuracion, no firmada para publicacion. Las APK y los archivos de firma estan excluidos del repositorio. El identificador Android `es.lifefit.watch` se conserva por compatibilidad con la instalacion y sus datos; no es el nombre visible de la aplicacion.

1. Instala o actualiza la APK en el telefono y abre CustomWatch.
2. Concede el permiso Bluetooth solicitado y conecta con la direccion BLE del reloj.
3. En **Ajustes > Gestionar notificaciones**, habilita el acceso de Android y selecciona las aplicaciones que quieras reenviar. Android puede restringir nuevamente este acceso despues de actualizar una APK instalada fuera de Play Store.
4. Activa los controles de musica o las alarmas del movil solo si los necesitas. Para la prueba de alarma tambien hay que permitir que CustomWatch publique notificaciones.
5. Si MIUI detiene el servicio en segundo plano, revisa las restricciones de bateria de la aplicacion. No es necesario alterar ajustes de seguridad del dispositivo para usar funciones ya instaladas.

## Flujo de avisos

El listener de Android tiene acceso tecnico a todas las notificaciones, aunque CustomWatch solo procesa las aplicaciones activadas y los metadatos usados para detectar alarmas. No se guardan los mensajes ni se solicita permiso de Internet. Los ajustes de los interruptores se guardan en `SharedPreferences` locales. El interruptor general pausa el reenvio sin borrar la seleccion individual; el control de alarmas es independiente. Las preferencias anteriores de WhatsApp, WhatsApp Business y Teams se respetan hasta que se modifica su seleccion individual.

La lista usa `QUERY_ALL_PACKAGES` para incluir aplicaciones instaladas habilitadas, tambien las que no tienen actividad de lanzador. El listado se usa solo en el dispositivo y no se transmite. Esta visibilidad amplia esta sujeta a las politicas de Google Play y requeriria justificar la funcionalidad principal antes de publicar alli. Los interruptores solo autorizan el reenvio en CustomWatch: no cambian ni confirman el permiso de notificaciones propio de otra aplicacion. Para que un aviso llegue, Android debe permitirlo, CustomWatch debe tener acceso de listener y el reloj debe estar conectado. Las aplicaciones adicionales utilizan el tipo SMS ya usado en este reloj; no se ha comprobado individualmente su visualizacion con cada aplicacion.

- **WhatsApp:** solo se reenvia cuando `EXTRA_IS_GROUP_CONVERSATION` indica explicitamente que no es un grupo. Si falta ese dato, se omite para evitar reenviar grupos.
- **Teams:** acepta conversaciones individuales y de grupo. Intenta obtener el remitente del mensaje estructurado o de un prefijo; abrevia nombres a nombre y primera inicial del siguiente componente. La calidad depende de los metadatos que entregue Teams.
- **Tipo visual:** WhatsApp usa el tipo `0x0c`; Teams usa por defecto SMS `0x04`, configurable a tipo WhatsApp. El tipo generico `0xff` no produjo un aviso visible en una prueba anterior.
- **Contenido:** el formato usado en el reloj reserva 20 bytes UTF-8 para el remitente, con relleno cero, seguidos de longitud de dos bytes y hasta 240 bytes UTF-8 de texto. El texto enviado incluye el remitente como prefijo; los recortes respetan limites de caracteres UTF-8.

### Alarma del movil

Con la opcion activada, CustomWatch identifica una notificacion de alarma generica si su categoria es `alarm` y tiene indicador insistente o pantalla completa. Para la app Reloj del telefono Xiaomi tambien acepta el paquete `com.android.deskclock`, canal `channel_id_deskclock_alarm`, aviso persistente y pantalla completa. Estas condiciones proceden de analisis estatico de la app del telefono; Android o versiones diferentes pueden exponer otros metadatos.

Ante una coincidencia, se envia la orden de buscar reloj y se repite cada diez segundos mientras quede alguna alarma detectada y la opcion este activada. Las actualizaciones del mismo aviso no crean ciclos adicionales. Si el reloj esta desconectado, se vuelve a intentar en el siguiente intervalo; al reconectar el listener se recuperan las alarmas activas. Al retirar la ultima notificacion se dejan de enviar ordenes, pero no se cancela una vibracion ya iniciada. No hay vibracion continua ni pantalla nativa de alarma. Desactivar la opcion detiene los reintentos como maximo en el siguiente intervalo.

**Simular alarma del movil** publica durante 15 segundos una notificacion silenciosa de CustomWatch con categoria de alarma e indicador insistente. El listener la procesa por la misma ruta y el usuario ha confirmado que el reloj vibra. Esta prueba no activa la app Reloj ni demuestra que una alarma real se detecte, y actualmente abre la pantalla de busqueda del dispositivo.

Segun el usuario, este modelo no permite configurar alarmas en el reloj: recibe las del movil. El SDK generico incluye operaciones de lectura y escritura de listas de alarmas para dispositivos compatibles, pero eso no demuestra que este Prixton las admita ni resuelve la alarma activa del movil. No se ha identificado todavia el aviso correcto para mostrar una alarma, ni su ciclo de inicio y final. CustomWatch no escribe listas de alarmas.

### Pista pendiente: llamadas

El SDK declara `TYPE_TELEPHONE_COMING = 0x01`, `TYPE_TELEPHONE_LISTENED = 0x02`, `TYPE_TELEPHONE_REJECT = 0x03` y `TYPE_TELEPHONE_MISSED = 0x19`. Tambien expone envio y retirada de notificaciones; la retirada usa grupo `0x02`, clave `0x8c` en el SDK analizado. Son observaciones estaticas, no una implementacion ni una prueba en el reloj. Investigar deteccion, privacidad, formato, finalizacion y compatibilidad antes de incorporar llamadas. No se utilizan estos tipos para simular una alarma.

## Protocolo BLE observado

Identificadores GATT empleados:

| Elemento | UUID |
| --- | --- |
| Servicio | `000001ff-3c17-d293-8e48-14fe2e4da212` |
| Escritura | `0000ff02-0000-1000-8000-00805f9b34fb` |
| Notificacion | `0000ff03-0000-1000-8000-00805f9b34fb` |
| Descriptor CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

La trama que implementa `ProtocolFrame` es:

```text
Cabecera: AB flags bodyLen[2] crc[2] sequence[2]
Cuerpo:   group 00 key payloadLen[2] payload[payloadLen]
ACK:      AB 10 00 00 00 00 sequence[2]
```

Las longitudes, CRC y secuencia se codifican con el byte mas significativo primero. El CRC del cuerpo es CRC-16/ARC, valor inicial `0`, polinomio reflejado `0xa001`; el vector ASCII `123456789` da `0xbb3d`. La construccion admite hasta 511 bytes de payload. El receptor limita el cuerpo a 4096 bytes y confirma las tramas con CRC valido.

| Funcion | Grupo | Claves | Detalles |
| --- | --- | --- | --- |
| Avisos | `0x04` | Tipo de aviso | Ejemplos: SMS `0x04`, WhatsApp `0x0c`. |
| Buscar reloj | `0x02` | `0x3b` | Sin payload; uso actual para alarma del movil. |
| Resumen diario | `0x05` | `0x21` / `0x22` | Solicitud / respuesta; primeros tres enteros de cuatro bytes para pasos, distancia y calorias. |
| Salud | `0x05` | `0x42` / `0x43` | Ultima medicion, sin fecha. |
| Deporte | `0x05` | `0x48` / `0x49` | Solicitud / respuesta de estado. |
| Clima | `0x02` | `0x36` | Formato basico o extendido con fecha y previsiones. |
| Controles musicales | `0x07` | `0x03` | Valores 0-2 reproducir/pausar, 3 siguiente, 4 anterior, 5 subir y 6 bajar volumen. |
| Lista de alarmas en SDK generico | `0x02` | `0x03` / `0x04` | Lectura de lista; no hay evidencia de soporte en este modelo y no equivale a recibir una alarma del movil. |

El resumen diario convierte distancia y calorias dividiendo los enteros por 1000 para mostrar kilometros y kcal. Esta conversion sigue el analisis del SDK y de la app original; queda por compararla con el reloj real. El SDK tambien expone `setVibration(boolean, int)` como grupo `0x02`, clave `0x8a`, payload de dos bytes; no se ha establecido que muestre una alarma ni se usa en CustomWatch.

Las variantes de clima dependen de capacidades del dispositivo que CustomWatch todavia no consulta. Los dos botones envian 20 grados y la ciudad ficticia `Prueba`; podrian sustituir datos del reloj. La sincronizacion historica original consulta grupo `0x05`, claves `0x05`/`0x06`, y transfiere bloques por tipo con confirmacion final.

### Investigacion del sueno

El usuario indica que el reloj tiene un modo de sueno que se activa en el propio reloj y que la app original muestra un analisis de fases, incluido REM. Todavia no se ha observado esa secuencia en el dispositivo ni se ha determinado si envia datos en directo o registros almacenados.

En el analisis estatico, `kf.s.q()` selecciona el tipo de sincronizacion `2` y utiliza `vf.m` para interpretar registros de sueno. Este decodificador admite dos formatos, seleccionados segun capacidades del dispositivo: muestras de un byte asociadas al intervalo del bloque o registros de cinco bytes con fecha y estado. El SDK agrupa los datos en segmentos con hora de inicio, hora de final y estado; en `SleepItemData` los estados declarados son `1` profundo, `2` ligero y `3` despierto. Esta ruta no identifica REM por separado; eso no descarta otra ruta o un calculo adicional de la app. No se conoce aun el formato utilizado por este Prixton ni la precision de las fases.

CustomWatch consulta el resumen diario con la misma solicitud `0x05/0x21` que utiliza para actividad. La respuesta `0x05/0x22` contiene, segun el SDK, dos enteros de cuatro bytes para minutos de sueno profundo y ligero a partir de los offsets 12 y 16 del payload; el total es su suma. Dos consultas al Prixton dieron 5 h 28 min, coincidiendo con el total indicado por el usuario en el reloj. El desglose no se ha contrastado de forma independiente, y el resumen no proporciona REM ni detalle temporal. Las fases son orientativas, no datos medicos.

La sincronizacion experimental consulta primero el descriptor de capacidades (`0x02/0x20` / `0x02/0x21`) y los metadatos (`0x05/0x05` / `0x05/0x06`). Solicita el tipo `2` con `0x05/0x01`, acumula bloques `0x05/0x30` y comprueba su longitud contra `0x05/0x08`. Guarda los bytes completos, el dispositivo, el formato y la zona horaria en SQLite privado antes de solicitar confirmacion de exito `0x05/0x20`. Las transferencias identicas se deduplican; las incompletas, incorrectas o que no se pueden guardar no reciben confirmacion de exito. Las confirmaciones de transporte BLE siguen siendo independientes. No se ha establecido si iniciar o confirmar una transferencia modifica los datos del reloj.

En el Prixton se recibio una noche de 5 h 28 min, con 37 min profundos y 4 h 51 min ligeros. El total coincide con lo indicado en el reloj; las fases coinciden con la consulta diaria de la app, pero no se han contrastado independientemente. Una segunda transferencia no contenia datos; el historial guardado siguio visible tras reiniciar CustomWatch. Esto no demuestra cuantos dias conserva el reloj ni que toda sincronizacion sea inocua. Los calculos por muestras son experimentales, omiten solapamientos y para cada fecha muestran la transferencia mas reciente que contiene muestras. No se calcula REM.

**El historial solo se guarda en el almacenamiento privado de CustomWatch en ese movil. Desinstalar la app o borrar sus datos lo elimina; no hay copia de seguridad automatica ni exportacion.**

## Pruebas

Las pruebas puras no necesitan un dispositivo Android. Desde este directorio:

```bash
mkdir -p /tmp/customwatch-tests
"$JAVA_HOME/bin/javac" -d /tmp/customwatch-tests \
	app/src/main/java/es/lifefit/watch/{NotificationRouting,NotificationPayload,ProtocolFrame,WatchBattery,WatchHealth,WatchAlarms,WatchSport,WatchWeather,WatchMusic,WatchActivity,WatchSleepTransfer,WatchSleepCapabilities,WatchSleepHistory,WatchSleepSync,SleepMetrics}.java \
	tests/{NotificationPayloadTest,ProtocolFrameTest}.java
"$JAVA_HOME/bin/java" -cp /tmp/customwatch-tests ProtocolFrameTest
"$JAVA_HOME/bin/java" -cp /tmp/customwatch-tests NotificationPayloadTest
```

Cubren ejemplos de tramas, CRC, enrutamiento, longitudes y decodificacion de respuestas. Son pruebas sinteticas: no demuestran la interfaz del reloj ni su firmware. Para validar cambios BLE hay que conectar el dispositivo y comprobar por separado el estado de transporte y el resultado fisico.

## Evidencia y limites

El protocolo se infirio mediante analisis estatico, una captura BLE y pruebas en el dispositivo. La deteccion especifica de alarma del telefono se contrasto con analisis estatico de su app Reloj. Las observaciones del SDK no garantizan que todas las funciones esten implementadas o habilitadas en este modelo o firmware.

No se incluyen APK de terceros ni volcados de notificaciones o claves en el repositorio. Antes de compartir registros o capturas, elimina datos personales, identificadores del dispositivo y credenciales.