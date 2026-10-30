# LifeFit

Aplicacion Android experimental para conectar el reloj Prixton Life Fit y reenviar notificaciones de WhatsApp, WhatsApp Business y Microsoft Teams. Se han verificado en el reloj la conexion, la visualizacion de mensajes de WhatsApp y Teams, y un aviso de prueba con remitente. La seleccion de chats de WhatsApp necesita comprobacion con notificaciones reales. Una confirmacion BLE no demuestra por si sola que el reloj haya mostrado el aviso.

La pantalla del reloj permite consultar la bateria y buscar el reloj. La consulta devolvio un 40 % en una prueba real y el usuario confirmo que la busqueda funciona. Se han anadido consultas experimentales de alarmas (solo lectura), ultima medicion de salud y estado deportivo, y una prueba manual de clima; no se han comprobado en el reloj. La prueba de clima sustituye el dato mostrado por valores ficticios y solicita confirmacion. La respuesta de salud no indica la fecha; la temperatura es solo una interpretacion tentativa y los datos no son aptos para uso medico.

La actividad diaria y el historial de sueno aun no se sincronizan. El estado deportivo es una consulta experimental, no una gestion de entrenamientos. El clima no se actualiza automaticamente y la musica aun no esta implementada. La edicion de alarmas y otros ajustes tambien estan pendientes. Esta version no sustituye completamente a la aplicacion original.

Protocolos observados en el SDK original: salud grupo 5 claves 66/67, estado deportivo grupo 5 claves 72/73, alarmas grupo 2 claves 3/4 y clima basico grupo 2 clave 54. El clima tiene otra variante segun las capacidades del dispositivo. El SDK envia informacion de musica por grupo 2 clave 148 y estado por clave 147, pero los eventos desde el reloj y su integracion con Android no estan implementados. La sincronizacion historica comienza con grupo 5 claves 5/6 y luego transfiere bloques por tipo; no se ha establecido todavia como preservar y confirmar todos los registros. Estas observaciones estaticas no demuestran compatibilidad con el reloj.

La aplicacion solicita permiso Bluetooth y acceso a notificaciones. Android concede al servicio acceso a todas las notificaciones; LifeFit solo procesa las aplicaciones activadas. Para WhatsApp solo reenvia notificaciones que Android identifica explicitamente como conversaciones individuales; puede omitir mensajes si faltan esos metadatos. Teams acepta conversaciones individuales y de grupo y abrevia los nombres de remitente cuando estan disponibles. El formato depende de los datos que proporcione Teams. El tipo generico de otras aplicaciones no mostro un aviso durante la prueba; un aviso de prueba con el tipo SMS si aparecio con un icono verde, pero queda por comprobar un mensaje real de Teams con ese tipo. Se puede desactivar el interruptor de icono SMS para recuperar el tipo WhatsApp. No guarda el contenido ni solicita permiso de Internet. Puede ser necesario desconectar la aplicacion original antes de conectar LifeFit.

Para generar la APK de depuracion se necesitan JDK 17 o superior, Android SDK con plataforma y herramientas de compilacion 35, y Gradle 8.9:

```sh
gradle :app:assembleDebug
```

La APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Es una compilacion de depuracion, no una version publicada ni verificada por Google Play. MIUI y Play Protect pueden impedir su instalacion. Android puede restringir de nuevo el acceso a notificaciones tras actualizar la APK. Las restricciones de bateria del fabricante tambien pueden detener el servicio.