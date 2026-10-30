# CustomWatch

> A local-first Android companion for the Prixton Life Fit smartwatch, built around an independently researched Bluetooth Low Energy protocol.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-Native-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Bluetooth](https://img.shields.io/badge/Bluetooth-Low%20Energy-0082FC?style=flat-square&logo=bluetooth&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.9-02303A?style=flat-square&logo=gradle&logoColor=white)
![Status](https://img.shields.io/badge/Status-Experimental-D49A36?style=flat-square)

CustomWatch is a native Android application that connects directly to the watch, keeps health observations on the phone and relays selected notifications. This independent project is not affiliated with Prixton and does not implement every feature of the watch.

## Features

| Area | Current behavior | Verification and limitations |
| --- | --- | --- |
| Dashboard | Request activity, the latest pulse reading and battery together. | Does not synchronize sleep history. The watch supplies no measurement timestamp for pulse or activity. |
| Sleep | Browse locally archived intervals, select graph segments and see deep, light and awake periods. | One night was received; its total matched the watch. Individual phases have not been independently verified. REM and sleep scores are not inferred. |
| Activity | Display the latest steps, distance and calories; optionally set a personal steps goal. | Records use the date of the phone's query, not a watch-provided measurement date. Distance and calorie units need independent confirmation. |
| Pulse | Show the last available reading. | No fabricated history, trends, averages or measurement date. |
| Battery | Query after connecting and every ten minutes while connected; retain the last reading. | A manual query and the initial automatic query have been tested. A full ten-minute cycle has not yet been verified. |
| Notifications | Select installed apps independently and relay their notifications to the watch. | Android notification access is required. WhatsApp individual messages and Teams were tested; display for every other app is not verified. |
| Music | Use the watch controls for playback, tracks and phone volume. | Playback controls were confirmed; track titles are not sent to the watch. |
| Find watch | Trigger the watch's find screen and vibration. | Confirmed on the test device. The optional phone-alarm relay uses this command, not a native alarm screen. |

Advanced tools include experimental sport and weather requests and the original sleep-history transfer. Do not assume that those commands are supported on other devices or that a transfer leaves watch data unchanged. No additional sleep history is required for the main dashboard.

## Device and Firmware

| Item | Observed or known value |
| --- | --- |
| Tested watch | Prixton Life Fit; model identifier `CS-SLXJ-268` matches a commercial listing. |
| Bluetooth name | `HLifeFit_191` on the tested unit. |
| Watch firmware version | **Unknown / not independently verified.** Compatibility may differ between firmware versions and individual devices. |
| Custom firmware | None. CustomWatch is an Android companion; it does not flash, replace or update the watch firmware. |

Protocol behavior is based on static analysis, a limited BLE capture, and tests on one watch. An acknowledged BLE frame does not by itself prove that the watch displayed or performed the requested action. See the [Spanish technical reference](TECHNICAL_REFERENCE.es.md) for supported commands and unresolved questions.

## Getting Started

**Requirements:** Android 8.0 / API 26 or newer, a compatible BLE watch, JDK 17 or newer, Android SDK platform and Build Tools 35, and Gradle 8.9. The project uses Android Gradle Plugin 8.7.3 and does not include a Gradle Wrapper.

```bash
git clone https://github.com/kta41/CustomWatch.git
cd CustomWatch

export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/CustomWatch-debug.apk`. It is signed with a development key and is not a publication-ready release. Android updates preserve app data only when the application ID and signing certificate are compatible; this project retains the existing `es.lifefit.watch` application ID for that reason.

1. Install the APK, open CustomWatch and grant the requested Bluetooth permissions.
2. Connect using the watch's BLE address. Disconnect the original companion if it prevents a connection.
3. Under **Ajustes > Gestionar notificaciones**, enable Android notification access and select the apps to relay. Android may restrict notification access again after a sideloaded update.
4. Enable music controls or phone-alarm forwarding only if needed. If the phone stops the service in the background, review its battery restrictions.

The interface currently uses Spanish labels. The debug APK and signing keys are not committed to this repository.

## BLE Protocol

| Role | UUID |
| --- | --- |
| Service | `000001ff-3c17-d293-8e48-14fe2e4da212` |
| Write characteristic | `0000ff02-0000-1000-8000-00805f9b34fb` |
| Notification characteristic | `0000ff03-0000-1000-8000-00805f9b34fb` |
| Client configuration descriptor | `00002902-0000-1000-8000-00805f9b34fb` |

```text
Header: AB flags bodyLen[2] crc[2] sequence[2]
Body:   group 00 key payloadLen[2] payload[payloadLen]
ACK:    AB 10 00 00 00 00 sequence[2]
```

Lengths, CRC and sequence use big-endian byte order. The body checksum is CRC-16/ARC; the `123456789` test vector produces `0xbb3d`. The app enables GATT notifications, negotiates an MTU, serializes writes and validates incoming frame checksums.

| Operation | Group | Request / response key |
| --- | --- | --- |
| Battery | `0x02` | `0x1b` / `0x1c` |
| Find watch | `0x02` | `0x3b` |
| Daily activity and sleep summary | `0x05` | `0x21` / `0x22` |
| Latest health reading | `0x05` | `0x42` / `0x43` |
| Notification | `0x04` | Type-specific, including SMS `0x04` and WhatsApp `0x0c` |
| Music control | `0x07` | `0x03` |

The [technical reference](TECHNICAL_REFERENCE.es.md) documents payloads, notification routing, sleep transfers and commands whose compatibility is still uncertain.

## Privacy and Data

- Health observations, sleep archives and settings remain in private local Android storage. CustomWatch does not request Internet access.
- Uninstalling the app or clearing its data removes local history. Automatic backups and device transfers of private app data are disabled; there is no export yet.
- The notification listener can technically access notifications from the phone; CustomWatch processes selected apps and alarm-detection metadata. Notification messages are not saved.
- `QUERY_ALL_PACKAGES` is used to list enabled installed apps, including apps without launcher activities. Its use is subject to Google Play policy review. App selections do not change another app's Android notification permissions.
- Review logs, evidence and screenshots for device identifiers, personal data and credentials before sharing them.

## Project Layout

```text
app/                         Native Android app and BLE service
app/src/main/java/           UI, persistence, notification routing and protocol
app/src/debug/               Isolated Android instrumentation checks
tests/                       Standalone Java protocol and notification tests
TECHNICAL_REFERENCE.es.md    Detailed feature, protocol and research reference
```

## Testing

```bash
gradle :app:assembleDebug :app:lintDebug

mkdir -p /tmp/customwatch-tests
"$JAVA_HOME/bin/javac" -d /tmp/customwatch-tests \
  app/src/main/java/es/lifefit/watch/{NotificationRouting,NotificationPayload,ProtocolFrame,WatchBattery,WatchHealth,WatchAlarms,WatchSport,WatchWeather,WatchMusic,WatchActivity,WatchSleepTransfer,WatchSleepCapabilities,WatchSleepHistory,WatchSleepSync,SleepMetrics}.java \
  tests/{NotificationPayloadTest,ProtocolFrameTest}.java
"$JAVA_HOME/bin/java" -cp /tmp/customwatch-tests ProtocolFrameTest
"$JAVA_HOME/bin/java" -cp /tmp/customwatch-tests NotificationPayloadTest
```

These tests use synthetic examples; hardware behavior and firmware compatibility require separate device testing. The sleep archive must not be re-synchronized merely to run the standalone tests.
