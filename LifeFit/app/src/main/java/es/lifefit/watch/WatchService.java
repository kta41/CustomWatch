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
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    private static volatile String alarmsStatus = "Alarmas: sin consultar";
    private static volatile String sportStatus = "Deporte: sin consultar";
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
    private boolean alarmsPending;
    private int alarmsRequestId;
    private boolean sportPending;
    private int sportRequestId;
    private int sequence = 1;
    private int pendingSequence = -1;
    private int mtu = 23;
    private String address;
    private final Runnable reconnect = this::connect;

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

    public static String healthStatus() {
        return healthStatus;
    }

    public static String alarmsStatus() {
        return alarmsStatus;
    }

    public static String sportStatus() {
        return sportStatus;
    }

    public static boolean sendWeatherTest() {
        WatchService service = instance;
        if (service == null || !service.ready) return false;
        service.handler.post(() -> {
            if (!service.ready) return;
            service.queueFrame(ProtocolFrame.command(WatchWeather.GROUP, WatchWeather.SET,
                    WatchWeather.testPayload(), service.sequence++ & 0xffff));
            service.setStatus("Clima de prueba en cola; comprueba el reloj");
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
            batteryStatus = "Batería: consultando...";
            service.sendBroadcast(new Intent(ACTION_STATUS).setPackage(service.getPackageName()));
            service.queueFrame(ProtocolFrame.command(WatchBattery.GROUP, WatchBattery.REQUEST,
                    new byte[0], service.sequence++ & 0xffff));
            service.handler.postDelayed(() -> {
                if (service.batteryPending && service.batteryRequestId == requestId) {
                    service.batteryPending = false;
                    batteryStatus = "Batería: sin respuesta";
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
                .setContentTitle("LifeFit")
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
        ready = false;
        batteryPending = false;
        batteryRequestId++;
        batteryStatus = "Batería: sin consultar";
        healthPending = false;
        healthRequestId++;
        healthStatus = "Sin consultar";
        alarmsPending = false;
        alarmsRequestId++;
        alarmsStatus = "Alarmas: sin consultar";
        sportPending = false;
        sportRequestId++;
        sportStatus = "Deporte: sin consultar";
        writing = false;
        pendingSequence = -1;
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
            if (flags == 0x10 && length == 0 && incomingSequence == pendingSequence) {
                pendingSequence = -1;
                setStatus("Entrega BLE confirmada");
                sendNextMessage();
            } else if (flags == 0 && length > 0) {
                byte[] body = Arrays.copyOfRange(buffer, offset + 8, offset + 8 + length);
                int expected = ((buffer[offset + 4] & 0xff) << 8) | (buffer[offset + 5] & 0xff);
                if (ProtocolFrame.checksum(body) == expected) {
                    WatchBattery battery = WatchBattery.fromResponse(body);
                    if (batteryPending && battery != null) {
                        batteryPending = false;
                        batteryStatus = "Batería: " + battery.percentage + "%"
                                + (battery.charging ? " · cargando" : "");
                        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
                    }
                    WatchHealth health = WatchHealth.fromResponse(body);
                    if (healthPending && health != null) {
                        healthPending = false;
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
                    queueFrame(ProtocolFrame.acknowledgement(incomingSequence));
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
            setStatus("Conectado · envío experimental");
        }
    }
}