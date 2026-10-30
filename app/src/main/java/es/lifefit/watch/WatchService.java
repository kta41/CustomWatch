package es.lifefit.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class WatchService extends Service {
    public static final String ACTION_STATUS = "es.lifefit.watch.STATUS";
    private static final UUID SERVICE = UUID.fromString("000001ff-3c17-d293-8e48-14fe2e4da212");
    private static final UUID WRITE = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static volatile WatchService instance;
    private static volatile String currentStatus = "Desconectado";
    private static volatile String batteryStatus = "Batería: sin consultar";
    private static volatile String healthStatus = "Sin consultar";
    private static volatile String activityStatus = "Actividad: sin consultar";
    private static volatile String storageStatus = "";
    private static volatile String sleepStatus = "Sueño: sin consultar";
    private static volatile String sleepHistoryStatus = "Historial: sin sincronizar";
    private static volatile String sleepHistoryData = "Historial: sin registros guardados";
    private static volatile String alarmsStatus = "Alarmas: sin consultar";
    private static volatile String sportStatus = "Deporte: sin consultar";
    private static volatile String weatherStatus = "Clima: sin probar";
    private static volatile String musicStatus = "Música: esperando controles del reloj";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private final ArrayDeque<PendingNotification> messages = new ArrayDeque<>();
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private volatile boolean ready;
    private boolean writing;
    private boolean batteryPending;
    private int batteryRequestId;
    private boolean healthPending;
    private int healthRequestId;
    private boolean activityPending;
    private int activityRequestId;
    private WatchSleepSync sleepSync;
    private int sleepSyncRequestId;
    private boolean alarmsPending;
    private int alarmsRequestId;
    private boolean sportPending;
    private int sportRequestId;
    private int sequence = 1;
    private int pendingSequence = -1;
    private int weatherSequence = -1;
    private int musicSequence = -1;
    private int mtu = 23;
    private String address;
    private final Runnable reconnect = this::connect;
    private final Runnable periodicBattery = new Runnable() {
        @Override public void run() {
            if (!ready) return;
            requestBattery();
            handler.postDelayed(this, 10 * 60 * 1000L);
        }
    };

    private static final class PendingNotification {
        final int type;
        final byte[] payload;

        PendingNotification(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    public static String status() {
        return currentStatus;
    }

    public static String batteryStatus() {
        return batteryStatus;
    }

    public static boolean isConnected() {
        WatchService service = instance;
        return service != null && service.ready;
    }

    public static void loadBattery(Context context, String device) {
        if (device == null || device.isEmpty()) return;
        android.content.SharedPreferences preferences = context.getSharedPreferences("battery", MODE_PRIVATE);
        int percentage = preferences.getInt(device + ":percentage", -1);
        long observed = preferences.getLong(device + ":observed", 0);
        if (percentage < 0 || observed <= 0) {
            batteryStatus = "Batería: sin datos";
            return;
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(observed));
        batteryStatus = "Batería: " + percentage + "%"
                + (preferences.getBoolean(device + ":charging", false) ? " · cargando" : "")
                + " · consultada " + when;
    }

    public static String healthStatus() {
        return healthStatus;
    }

    public static String activityStatus() {
        return activityStatus;
    }

    public static String storageStatus() {
        return storageStatus;
    }

    public static String sleepStatus() {
        return sleepStatus;
    }

    public static String sleepHistoryStatus() {
        return sleepHistoryStatus;
    }

    public static String sleepHistoryData() {
        return sleepHistoryData;
    }

    public static void loadSleepHistory(Context context, String device) {
        if (device == null || device.isEmpty()) return;
        try (WatchSleepArchive archive = new WatchSleepArchive(context.getApplicationContext())) {
            List<List<WatchSleepHistory.Sample>> transfers = new ArrayList<>();
            boolean unreadable = false;
            for (WatchSleepArchive.Transfer transfer : archive.load(device)) {
                try {
                    transfers.add(WatchSleepHistory.decode(transfer.data, transfer.timestamped,
                            TimeZone.getTimeZone(transfer.timeZone)));
                } catch (IllegalArgumentException exception) {
                    unreadable = true;
                }
            }
            sleepHistoryData = WatchSleepHistory.summarize(transfers)
                    + (unreadable ? "\nHay datos guardados pendientes de interpretar" : "");
        } catch (RuntimeException exception) {
            sleepHistoryData = "No se pudo leer el historial guardado";
        }
    }

    public static boolean requestSleepHistory() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.sleepSync != null) return;
            service.sleepSync = new WatchSleepSync();
            sleepHistoryStatus = "Historial: consultando configuración...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchSleepCapabilities.GROUP, WatchSleepCapabilities.REQUEST,
                    new byte[0], service.sequence++ & 0xffff));
            service.sleepSyncTimeout();
        });
        return true;
    }

    public static String alarmsStatus() {
        return alarmsStatus;
    }

    public static String sportStatus() {
        return sportStatus;
    }

    public static String weatherStatus() {
        return weatherStatus;
    }

    public static String musicStatus() {
        return musicStatus;
    }

    public static boolean sendMusicTest() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.musicSequence != -1) return;
            int sentSequence = service.sequence++ & 0xffff;
            service.musicSequence = sentSequence;
            musicStatus = "Música: enviando prueba...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchMusic.GROUP, WatchMusic.SET_INFO,
                    WatchMusic.testPayload(), sentSequence));
            service.handler.postDelayed(() -> {
                if (service.musicSequence == sentSequence) {
                    service.musicSequence = -1;
                    musicStatus = "Música: sin confirmación BLE";
                    service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
                }
            }, 8000);
        });
        return true;
    }

    public static boolean sendWeatherTest(boolean extended) {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.weatherSequence != -1) return;
            int sentSequence = service.sequence++ & 0xffff;
            service.weatherSequence = sentSequence;
            weatherStatus = "Clima: enviando prueba...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchWeather.GROUP, WatchWeather.SET,
                    extended ? WatchWeather.extendedTestPayload(java.util.Calendar.getInstance())
                        : WatchWeather.testPayload(), sentSequence));
            service.handler.postDelayed(() -> {
                if (service.weatherSequence == sentSequence) {
                    service.weatherSequence = -1;
                    weatherStatus = "Clima: sin confirmación BLE";
                    service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
                }
            }, 8000);
        });
        return true;
    }

    public static boolean requestSportStatus() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.sportPending) return;
            service.sportPending = true;
            int requestId = ++service.sportRequestId;
            sportStatus = "Deporte: consultando...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchSport.GROUP, WatchSport.REQUEST_STATUS,
                    new byte[0], service.sequence++ & 0xffff));
            service.handler.postDelayed(() -> {
                if (service.sportPending && service.sportRequestId == requestId) {
                    service.sportPending = false;
                    sportStatus = "Deporte: sin respuesta del reloj";
                    service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
                }
            }, 8000);
        });
        return true;
    }

    public static boolean requestAlarms() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.alarmsPending) return;
            service.alarmsPending = true;
            int requestId = ++service.alarmsRequestId;
            alarmsStatus = "Alarmas: consultando...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchAlarms.GROUP, WatchAlarms.REQUEST,
                    new byte[0], service.sequence++ & 0xffff));
            service.handler.postDelayed(() -> {
                if (service.alarmsPending && service.alarmsRequestId == requestId) {
                    service.alarmsPending = false;
                    alarmsStatus = "Alarmas: sin respuesta del reloj";
                    service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
                }
            }, 8000);
        });
        return true;
    }

    public static boolean requestHealth() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.healthPending) return;
            service.healthPending = true;
            int requestId = ++service.healthRequestId;
            healthStatus = "Consultando...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchHealth.GROUP, WatchHealth.REQUEST,
                    new byte[0], service.sequence++ & 0xffff));
            service.handler.postDelayed(() -> {
                if (service.healthPending && service.healthRequestId == requestId) {
                    service.healthPending = false;
                    healthStatus = "Sin respuesta del reloj o función no compatible";
                    service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
                }
            }, 8000);
        });
        return true;
    }

    public static boolean requestActivity() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.activityPending) return;
            service.activityPending = true;
            int requestId = ++service.activityRequestId;
            activityStatus = "Actividad: consultando...";
            sleepStatus = "Sueño: consultando...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchActivity.GROUP, WatchActivity.REQUEST,
                    new byte[0], service.sequence++ & 0xffff));
            service.handler.postDelayed(() -> {
                if (service.activityPending && service.activityRequestId == requestId) {
                    service.activityPending = false;
                    activityStatus = "Actividad: sin respuesta del reloj o función no compatible";
                    sleepStatus = "Sueño: sin respuesta del reloj o función no compatible";
                    service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
                }
            }, 8000);
        });
        return true;
    }

    public static boolean findWatch() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready) return;
            service.queueFrame(ProtocolFrame.command(2, 59, new byte[0], service.sequence++ & 0xffff));
            service.setStatus("Solicitud de búsqueda enviada");
        });
        return true;
    }

    public static boolean requestBattery() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready || service.batteryPending) return;
            service.batteryPending = true;
            int requestId = ++service.batteryRequestId;
            loadBattery(service, service.address);
            batteryStatus += " · actualizando";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchBattery.GROUP, WatchBattery.REQUEST,
                    new byte[0], service.sequence++ & 0xffff));
            service.handler.postDelayed(() -> {
                if (service.batteryPending && service.batteryRequestId == requestId) {
                    service.batteryPending = false;
                    loadBattery(service, service.address);
                    batteryStatus += " · sin respuesta";
                    service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
                }
            }, 8000);
        });
        return true;
    }

    public static boolean sendNotification(String sender, String message) {
        return sendNotification(NotificationPayload.TYPE_WHATSAPP, sender, message);
    }

    public static boolean sendNotification(int type, String sender, String message) {
        WatchService service = instance;
        if (service == null || !service.ready) {
            return false;
        }
        byte[] payload = NotificationPayload.legacy(sender, message);
        service.handler.post(() -> service.enqueueMessage(new PendingNotification(type, payload)));
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("watch", "Conexión del reloj", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "watch")
                .setContentTitle("CustomWatch")
                .setContentText("Conexión con el reloj activa")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("address")) {
            address = intent.getStringExtra("address");
            loadBattery(this, address);
            closeConnection();
            connect();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        closeConnection();
        setStatus("Desconectado");
        super.onDestroy();
    }

    private void connect() {
        handler.removeCallbacks(reconnect);
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setStatus("Bluetooth desactivado");
                return;
            }
            setStatus("Conectando...");
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                setStatus("No se pudo iniciar la conexión");
            }
        } catch (SecurityException | IllegalArgumentException exception) {
            setStatus("Comprueba los permisos de Bluetooth");
        }
    }

    private void closeConnection() {
        handler.removeCallbacks(reconnect);
        handler.removeCallbacks(periodicBattery);
        ready = false;
        batteryPending = false;
        batteryRequestId++;
        loadBattery(this, address);
        healthPending = false;
        healthRequestId++;
        healthStatus = "Sin consultar";
        activityPending = false;
        activityRequestId++;
        activityStatus = "Actividad: sin consultar";
        sleepStatus = "Sueño: sin consultar";
        if (sleepSync != null) sleepHistoryStatus = "Historial: sincronización interrumpida";
        sleepSync = null;
        sleepSyncRequestId++;
        alarmsPending = false;
        alarmsRequestId++;
        alarmsStatus = "Alarmas: sin consultar";
        sportPending = false;
        sportRequestId++;
        sportStatus = "Deporte: sin consultar";
        writing = false;
        pendingSequence = -1;
        weatherSequence = -1;
        weatherStatus = "Clima: sin probar";
        musicSequence = -1;
        musicStatus = "Música: esperando controles del reloj";
        writes.clear();
        messages.clear();
        incoming.reset();
        mtu = 23;
        writeCharacteristic = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
            }
            gatt = null;
        }
    }

    private void setStatus(String status) {
        currentStatus = status;
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }

    private void enqueueMessage(PendingNotification notification) {
        if (!ready || messages.size() >= 10) {
            setStatus("No se ha enviado el aviso");
            return;
        }
        messages.add(notification);
        sendNextMessage();
    }

    private void sendNextMessage() {
        if (!ready || pendingSequence != -1 || messages.isEmpty()) {
            return;
        }
        int sentSequence = sequence++ & 0xffff;
        pendingSequence = sentSequence;
        PendingNotification notification = messages.remove();
        queueFrame(ProtocolFrame.command(NotificationPayload.GROUP_NOTIFICATION,
            notification.type, notification.payload, sentSequence));
        setStatus("Enviando aviso...");
        handler.postDelayed(() -> {
            if (pendingSequence == sentSequence) {
                pendingSequence = -1;
                setStatus("El reloj no confirmó el aviso");
                sendNextMessage();
            }
        }, 8000);
    }

    private void queueFrame(byte[] frame) {
        int chunkSize = Math.max(20, mtu - 3);
        for (int offset = 0; offset < frame.length; offset += chunkSize) {
            writes.add(Arrays.copyOfRange(frame, offset, Math.min(frame.length, offset + chunkSize)));
        }
        writeNext();
    }

    private void writeNext() {
        if (writing || gatt == null || writeCharacteristic == null || writes.isEmpty()) {
            return;
        }
        byte[] value = writes.remove();
        try {
            writing = true;
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            writeCharacteristic.setValue(value);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                writing = false;
                writes.clear();
                pendingSequence = -1;
                setStatus("Error al escribir en el reloj");
            }
        } catch (SecurityException exception) {
            writing = false;
            setStatus("Permiso Bluetooth revocado");
        }
    }

    private void received(byte[] value) {
        incoming.write(value, 0, value.length);
        byte[] buffer = incoming.toByteArray();
        int offset = 0;
        while (buffer.length - offset >= 8) {
            if ((buffer[offset] & 0xff) != 0xab) {
                offset++;
                continue;
            }
            int length = ((buffer[offset + 2] & 0xff) << 8) | (buffer[offset + 3] & 0xff);
            if (length > 4096) {
                offset++;
                continue;
            }
            if (buffer.length - offset < length + 8) {
                break;
            }
            int incomingSequence = ((buffer[offset + 6] & 0xff) << 8) | (buffer[offset + 7] & 0xff);
            int flags = buffer[offset + 1] & 0xf0;
            if (flags == 0x10 && length == 0) {
                if (incomingSequence == pendingSequence) {
                    pendingSequence = -1;
                    setStatus("Entrega BLE confirmada");
                    sendNextMessage();
                }
                if (incomingSequence == weatherSequence) {
                    weatherSequence = -1;
                    weatherStatus = "Clima: recibido por BLE; visualización no confirmada";
                    sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                }
                if (incomingSequence == musicSequence) {
                    musicSequence = -1;
                    musicStatus = "Música: recibida por BLE; visualización no confirmada";
                    sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                }
            } else if (flags == 0 && length > 0) {
                byte[] body = Arrays.copyOfRange(buffer, offset + 8, offset + 8 + length);
                int expected = ((buffer[offset + 4] & 0xff) << 8) | (buffer[offset + 5] & 0xff);
                if (ProtocolFrame.checksum(body) == expected) {
                    WatchActivity activity = WatchActivity.fromResponse(body);
                    if (activityPending && activity != null) {
                        activityPending = false;
                        try (HealthRepository repository = new HealthRepository(this)) {
                            repository.saveActivity(address, activity, System.currentTimeMillis());
                            storageStatus = "";
                        } catch (RuntimeException exception) {
                            storageStatus = "No se pudo guardar la actividad en el móvil";
                        }
                        activityStatus = String.format(Locale.getDefault(),
                                "Actividad de hoy: %d pasos\nDistancia: %.2f km\nCalorías: %.1f kcal",
                                activity.steps, activity.kilometers, activity.calories);
                        long totalSleepMinutes = activity.deepSleepMinutes + activity.lightSleepMinutes;
                        sleepStatus = String.format(Locale.getDefault(),
                            "Total: %d h %02d min\nProfundo: %d h %02d min\nLigero: %d h %02d min",
                            totalSleepMinutes / 60, totalSleepMinutes % 60,
                            activity.deepSleepMinutes / 60, activity.deepSleepMinutes % 60,
                            activity.lightSleepMinutes / 60, activity.lightSleepMinutes % 60);
                        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                    }
                    WatchBattery battery = WatchBattery.fromResponse(body);
                    if (batteryPending && battery != null) {
                        batteryPending = false;
                        getSharedPreferences("battery", MODE_PRIVATE).edit()
                            .putInt(address + ":percentage", battery.percentage)
                            .putBoolean(address + ":charging", battery.charging)
                            .putLong(address + ":observed", System.currentTimeMillis()).apply();
                        loadBattery(this, address);
                        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                    }
                    WatchHealth health = WatchHealth.fromResponse(body);
                    if (healthPending && health != null) {
                        healthPending = false;
                        if (health.heartRate > 0) {
                            try (HealthRepository repository = new HealthRepository(this)) {
                                repository.savePulse(address, health.heartRate, System.currentTimeMillis());
                                storageStatus = "";
                            } catch (RuntimeException exception) {
                                storageStatus = "No se pudo guardar el pulso en el móvil";
                            }
                        }
                        healthStatus = "Pulso: " + healthValue(health.heartRate, " lpm")
                                + "\nOxígeno: " + healthValue(health.oxygen <= 100 ? health.oxygen : 0, "%")
                                + "\nPresión: " + (health.systolic == 0 || health.diastolic == 0
                                ? "no disponible" : health.systolic + "/" + health.diastolic + " mmHg")
                                + "\nPosible temperatura corporal (sin verificar): "
                            + (health.bodyTemperatureHundredths == null || health.bodyTemperatureHundredths == 0
                                ? "no disponible" : String.format(Locale.getDefault(), "%.2f °C",
                                    health.bodyTemperatureHundredths / 100.0));
                        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                    }
                    List<WatchAlarms.Alarm> alarms = WatchAlarms.fromResponse(body);
                    if (alarmsPending && alarms != null) {
                        alarmsPending = false;
                        StringBuilder text = new StringBuilder(alarms.isEmpty() ? "Sin alarmas" : "Alarmas:");
                        for (WatchAlarms.Alarm alarm : alarms) {
                            text.append(String.format(Locale.ROOT, "\n%02d:%02d", alarm.hour, alarm.minute));
                            text.append(alarm.enabled ? " · activada" : " · desactivada");
                            if (alarm.repeat != 0) {
                                String[] days = {"L", "M", "X", "J", "V", "S", "D"};
                                text.append(" · ");
                                for (int day = 0; day < days.length; day++) {
                                    if ((alarm.repeat & (1 << day)) != 0) text.append(days[day]).append(' ');
                                }
                            } else if (alarm.year > 2000 && alarm.month >= 1 && alarm.month <= 12
                                    && alarm.day >= 1 && alarm.day <= 31) {
                                text.append(String.format(Locale.ROOT, " · %02d/%02d/%04d",
                                        alarm.day, alarm.month, alarm.year));
                            }
                            if (!alarm.label.isEmpty()) text.append(" · ").append(alarm.label);
                        }
                        alarmsStatus = text.toString();
                        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                    }
                    Integer sport = WatchSport.statusFromResponse(body);
                    if (sportPending && sport != null) {
                        sportPending = false;
                        sportStatus = "Deporte: estado " + sport;
                        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                    }
                    int musicControl = WatchMusic.controlFromResponse(body);
                    if (musicControl >= 0
                            && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("music_control", false)) {
                        handleMusicControl(musicControl);
                    }
                    queueFrame(ProtocolFrame.acknowledgement(incomingSequence));
                    if (sleepSync != null) handleSleepHistory(incomingSequence, body);
                }
            }
            offset += length + 8;
        }
        incoming.reset();
        incoming.write(buffer, offset, buffer.length - offset);
    }

    private static String healthValue(int value, String unit) {
        return value == 0 ? "no disponible" : value + unit;
    }

    private void sleepSyncTimeout() {
        int requestId = ++sleepSyncRequestId;
        handler.postDelayed(() -> {
            if (sleepSync != null && sleepSyncRequestId == requestId) {
                sleepSync = null;
                sleepHistoryStatus = "Historial: sin respuesta o transferencia interrumpida";
                sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
            }
        }, 15000);
    }

    private void handleSleepHistory(int incomingSequence, byte[] body) {
        WatchSleepSync.Result result = sleepSync.accept(incomingSequence, body);
        switch (result) {
            case REQUEST_METADATA:
                sleepHistoryStatus = "Historial: consultando registros...";
                queueFrame(ProtocolFrame.command(5, 5, new byte[0], sequence++ & 0xffff));
                break;
            case REQUEST_SLEEP:
                sleepHistoryStatus = "Historial: recibiendo datos...";
                queueFrame(ProtocolFrame.command(WatchSleepTransfer.GROUP, WatchSleepTransfer.REQUEST,
                        new byte[]{WatchSleepTransfer.SLEEP_TYPE}, sequence++ & 0xffff));
                break;
            case PROGRESS:
            case DUPLICATE:
                break;
            case EMPTY:
                sleepSync = null;
                sleepSyncRequestId++;
                sleepHistoryStatus = "Historial: el reloj no anuncia registros pendientes";
                break;
            case SAVE:
                boolean hasData = sleepSync.completedData().length > 0;
                try (WatchSleepArchive archive = new WatchSleepArchive(this)) {
                    archive.save(address, sleepSync.timestamped(), TimeZone.getDefault().getID(), sleepSync.completedData());
                } catch (RuntimeException exception) {
                    sleepSync = null;
                    sleepSyncRequestId++;
                    sleepHistoryStatus = "Historial: no se pudo guardar; sin confirmar";
                    break;
                }
                sleepSync = null;
                sleepSyncRequestId++;
                queueFrame(ProtocolFrame.command(WatchSleepTransfer.GROUP, WatchSleepTransfer.CONFIRM,
                        new byte[]{0}, sequence++ & 0xffff));
                loadSleepHistory(this, address);
                sleepHistoryStatus = hasData ? "Historial guardado; confirmación solicitada"
                    : "Sin registros nuevos; confirmación solicitada";
                break;
            case ERROR:
                sleepSync = null;
                sleepSyncRequestId++;
                sleepHistoryStatus = "Historial: transferencia inválida; sin confirmar";
                break;
            default:
                return;
        }
        if (sleepSync != null) sleepSyncTimeout();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }

    private void handleMusicControl(int control) {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return;
        String action;
        if (control <= 4) {
            int key = control <= 2 ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    : control == 3 ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            action = control <= 2 ? "reproducir/pausar" : control == 3 ? "siguiente" : "anterior";
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        } else {
            action = control == 5 ? "subir volumen" : "bajar volumen";
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    control == 5 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        }
        musicStatus = "Música: " + action + " recibido";
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            handler.post(() -> {
                if (connection != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                    setStatus("Descubriendo servicios...");
                    try {
                        if (!connection.discoverServices()) setStatus("No se pudieron descubrir servicios");
                    } catch (SecurityException exception) {
                        setStatus("Permiso Bluetooth revocado");
                    }
                } else {
                    closeConnection();
                    setStatus("Desconectado · reintentando");
                    handler.postDelayed(reconnect, 5000);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            handler.post(() -> {
                if (connection != gatt) return;
                BluetoothGattService service = connection.getService(SERVICE);
                BluetoothGattCharacteristic notify = service == null ? null : service.getCharacteristic(NOTIFY);
                writeCharacteristic = service == null ? null : service.getCharacteristic(WRITE);
                BluetoothGattDescriptor descriptor = notify == null ? null : notify.getDescriptor(CCCD);
                if (status != BluetoothGatt.GATT_SUCCESS || descriptor == null || writeCharacteristic == null) {
                    setStatus("El reloj no ofrece el servicio esperado");
                    return;
                }
                try {
                    if (!connection.setCharacteristicNotification(notify, true)) {
                        setStatus("No se pudieron activar notificaciones BLE");
                        return;
                    }
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!connection.writeDescriptor(descriptor)) setStatus("Error al activar notificaciones BLE");
                } catch (SecurityException exception) {
                    setStatus("Permiso Bluetooth revocado");
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (connection != gatt || !CCCD.equals(descriptor.getUuid())) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setStatus("No se pudieron activar notificaciones BLE");
                    return;
                }
                try {
                    if (!connection.requestMtu(191)) {
                        finishSetup(connection);
                    } else {
                        handler.postDelayed(() -> finishSetup(connection), 4000);
                    }
                } catch (SecurityException exception) {
                    setStatus("Permiso Bluetooth revocado");
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt connection, int requestedMtu, int status) {
            handler.post(() -> {
                if (connection != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = requestedMtu;
                finishSetup(connection);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> {
                if (connection != gatt) return;
                writing = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    writes.clear();
                    pendingSequence = -1;
                    setStatus("Error al escribir en el reloj");
                    return;
                }
                writeNext();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT >= 33) return;
            byte[] value = characteristic.getValue();
            handleNotification(connection, characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(connection, characteristic, value);
        }
    };

    private void handleNotification(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value != null && NOTIFY.equals(characteristic.getUuid())) {
            byte[] copy = value.clone();
            handler.post(() -> { if (connection == gatt) received(copy); });
        }
    }

    private void finishSetup(BluetoothGatt connection) {
        if (connection == gatt && !ready) {
            ready = true;
            setStatus("Conectado");
            handler.removeCallbacks(periodicBattery);
            handler.postDelayed(periodicBattery, 5000);
        }
    }
}