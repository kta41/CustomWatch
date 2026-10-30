# Pruebas sin dispositivo

Con Gradle 8.9, el SDK Android 35 y JDK 17 o posterior:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Las pruebas JVM usan JUnit 4.13.2 y Hamcrest 1.3 incluidos en la distribución de Gradle 8.9, sin descargas adicionales. No se conectan dispositivos ni se utilizan datos reales. La instrumentación se compila, pero no se ejecuta en este comando.