package es.lifefit.watch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    private EditText address;
    private TextView status;
    private TextView battery;
    private TextView health;
    private TextView activity;
    private TextView sleep;
    private TextView sleepHistoryStatus;
    private TextView sleepHistory;
    private TextView sport;
    private TextView weather;
    private TextView music;
    private TextView access;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus();
        }
    };

    @Override
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(20, 43, 42));
        getWindow().setNavigationBarColor(Color.rgb(20, 43, 42));
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(28), dp(24), dp(28));
        layout.setBackgroundColor(Color.rgb(249, 250, 247));
        scroll.addView(layout);

        TextView title = label("CustomWatch", 30, Color.rgb(20, 43, 42));
        layout.addView(title);
        status = label("Desconectado", 16, Color.rgb(20, 43, 42));
        layout.addView(status);
        LinearLayout navigation = new LinearLayout(this);
        Button watchTab = button("Reloj");
        Button healthTab = button("Salud");
        Button notificationsTab = button("Avisos");
        navigation.addView(watchTab, new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(healthTab, new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(notificationsTab, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(navigation);
        LinearLayout watchSection = new LinearLayout(this);
        watchSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout notificationSection = new LinearLayout(this);
        LinearLayout healthSection = new LinearLayout(this);
        healthSection.setOrientation(LinearLayout.VERTICAL);
        healthSection.setVisibility(View.GONE);
        notificationSection.setOrientation(LinearLayout.VERTICAL);
        notificationSection.setVisibility(View.GONE);
        layout.addView(watchSection);
        layout.addView(healthSection);
        layout.addView(notificationSection);
        watchTab.setEnabled(false);
        watchTab.setOnClickListener(view -> {
            watchSection.setVisibility(View.VISIBLE);
            healthSection.setVisibility(View.GONE);
            notificationSection.setVisibility(View.GONE);
            watchTab.setEnabled(false);
            healthTab.setEnabled(true);
            notificationsTab.setEnabled(true);
        });
        notificationsTab.setOnClickListener(view -> {
            watchSection.setVisibility(View.GONE);
            healthSection.setVisibility(View.GONE);
            notificationSection.setVisibility(View.VISIBLE);
            watchTab.setEnabled(true);
            healthTab.setEnabled(true);
            notificationsTab.setEnabled(false);
        });
        healthTab.setOnClickListener(view -> {
            watchSection.setVisibility(View.GONE);
            healthSection.setVisibility(View.VISIBLE);
            notificationSection.setVisibility(View.GONE);
            watchTab.setEnabled(true);
            healthTab.setEnabled(false);
            notificationsTab.setEnabled(true);
        });
        watchSection.addView(label("RELOJ", 13, Color.rgb(137, 71, 51)));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setText(getPreferences(MODE_PRIVATE).getString("address", ""));
        address.setContentDescription("Dirección Bluetooth del reloj");
        watchSection.addView(address);
        Button connect = button("Conectar");
        connect.setOnClickListener(view -> connect());
        watchSection.addView(connect);
        Button disconnect = button("Desconectar");
        disconnect.setOnClickListener(view -> stopService(new Intent(this, WatchService.class)));
        watchSection.addView(disconnect);
        battery = label(WatchService.batteryStatus(), 16, Color.rgb(20, 43, 42));
        watchSection.addView(battery);
        Button refreshBattery = button("Consultar batería");
        refreshBattery.setOnClickListener(view -> {
            if (!WatchService.requestBattery()) battery.setText("Conecta el reloj antes de consultar");
        });
        watchSection.addView(refreshBattery);
        Button findWatch = button("Buscar reloj");
        findWatch.setOnClickListener(view -> {
            if (!WatchService.findWatch()) status.setText("Conecta el reloj antes de buscarlo");
        });
        watchSection.addView(findWatch);
        weather = label(WatchService.weatherStatus(), 16, Color.rgb(20, 43, 42));
        watchSection.addView(weather);
        Button testWeather = button("Probar clima");
        testWeather.setOnClickListener(view -> new AlertDialog.Builder(this)
            .setTitle("Clima de prueba")
            .setMessage("Se enviarán 20 °C y una ciudad ficticia. Elige un formato de prueba.")
            .setNegativeButton("Básico", (dialog, which) -> {
                if (!WatchService.sendWeatherTest(false)) status.setText("Conecta el reloj antes de enviar");
            })
            .setPositiveButton("Con fecha", (dialog, which) -> {
                if (!WatchService.sendWeatherTest(true)) status.setText("Conecta el reloj antes de enviar");
            }).show());
        watchSection.addView(testWeather);
        music = label(WatchService.musicStatus(), 16, Color.rgb(20, 43, 42));
        watchSection.addView(music);
        Switch musicControl = new Switch(this);
        musicControl.setText("Controlar música desde el reloj");
        musicControl.setTextSize(16);
        musicControl.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("music_control", false));
        musicControl.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("music_control", checked).apply());
        watchSection.addView(musicControl);

        healthSection.addView(label("ACTIVIDAD DIARIA", 13, Color.rgb(137, 71, 51)));
        activity = label(WatchService.activityStatus(), 16, Color.rgb(20, 43, 42));
        healthSection.addView(activity);
        Button refreshActivity = button("Consultar actividad");
        refreshActivity.setOnClickListener(view -> {
            if (!WatchService.requestActivity()) activity.setText("Conecta el reloj antes de consultar");
        });
        healthSection.addView(refreshActivity);
        healthSection.addView(label("SUEÑO (EXPERIMENTAL)", 13, Color.rgb(137, 71, 51)));
        sleep = label(WatchService.sleepStatus(), 16, Color.rgb(20, 43, 42));
        healthSection.addView(sleep);
        Button refreshSleep = button("Consultar sueño");
        refreshSleep.setOnClickListener(view -> {
            if (!WatchService.requestActivity()) sleep.setText("Conecta el reloj antes de consultar");
        });
        healthSection.addView(refreshSleep);
        sleepHistoryStatus = label(WatchService.sleepHistoryStatus(), 16, Color.rgb(20, 43, 42));
        healthSection.addView(sleepHistoryStatus);
        WatchService.loadSleepHistory(this, address.getText().toString().trim());
        sleepHistory = label(WatchService.sleepHistoryData(), 16, Color.rgb(20, 43, 42));
        healthSection.addView(sleepHistory);
        healthSection.addView(label("El historial se guarda solo en este móvil y se elimina al desinstalar CustomWatch.",
                14, Color.rgb(67, 83, 79)));
        Button syncSleepHistory = button("Sincronizar historial de sueño");
        syncSleepHistory.setOnClickListener(view -> new AlertDialog.Builder(this)
            .setTitle("Sincronizar historial")
            .setMessage("El reloj podría retirar los registros al sincronizarlos. CustomWatch los guardará en este móvil antes de confirmar la transferencia.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sincronizar", (dialog, which) -> {
                if (!WatchService.requestSleepHistory()) sleepHistoryStatus.setText("Conecta el reloj antes de sincronizar");
            }).show());
        healthSection.addView(syncSleepHistory);
        healthSection.addView(label("ÚLTIMA MEDICIÓN", 13, Color.rgb(137, 71, 51)));
        health = label(WatchService.healthStatus(), 16, Color.rgb(20, 43, 42));
        healthSection.addView(health);
        healthSection.addView(label("El reloj no indica la fecha de esta medición.", 14, Color.rgb(67, 83, 79)));
        Button refreshHealth = button("Consultar medición");
        refreshHealth.setOnClickListener(view -> {
            if (!WatchService.requestHealth()) health.setText("Conecta el reloj antes de consultar");
        });
        healthSection.addView(refreshHealth);
        sport = label(WatchService.sportStatus(), 16, Color.rgb(20, 43, 42));
        healthSection.addView(sport);
        Button refreshSport = button("Consultar estado deportivo");
        refreshSport.setOnClickListener(view -> {
            if (!WatchService.requestSportStatus()) sport.setText("Conecta el reloj antes de consultar");
        });
        healthSection.addView(refreshSport);

        TextView notifications = label("NOTIFICACIONES", 13, Color.rgb(137, 71, 51));
        notifications.setPadding(0, dp(24), 0, 0);
        notificationSection.addView(notifications);
        Switch whatsapp = new Switch(this);
        whatsapp.setText("WhatsApp");
        whatsapp.setTextSize(16);
        NotificationSettings notificationSettings = new NotificationSettings(this);
        whatsapp.setChecked(notificationSettings.appEnabled("com.whatsapp")
            || notificationSettings.appEnabled("com.whatsapp.w4b"));
        whatsapp.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("whatsapp", checked).apply();
            notificationSettings.setAppEnabled("com.whatsapp", checked);
            notificationSettings.setAppEnabled("com.whatsapp.w4b", checked);
        });
        notificationSection.addView(whatsapp);
        Switch teams = new Switch(this);
        teams.setText("Teams");
        teams.setTextSize(16);
        teams.setChecked(notificationSettings.appEnabled("com.microsoft.teams"));
        teams.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("teams", checked).apply();
            notificationSettings.setAppEnabled("com.microsoft.teams", checked);
        });
        notificationSection.addView(teams);
        Switch teamsSmsIcon = new Switch(this);
        teamsSmsIcon.setText("Icono SMS para Teams");
        teamsSmsIcon.setTextSize(16);
        teamsSmsIcon.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("teams_sms_icon", true));
        teamsSmsIcon.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("teams_sms_icon", checked).apply());
        notificationSection.addView(teamsSmsIcon);
        Switch phoneAlarms = new Switch(this);
        phoneAlarms.setText("Vibrar con alarmas del móvil (experimental)");
        phoneAlarms.setTextSize(16);
        phoneAlarms.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("phone_alarms", false));
        phoneAlarms.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("phone_alarms", checked).apply());
        notificationSection.addView(phoneAlarms);
        Button alarmTest = button("Simular alarma del móvil");
        alarmTest.setOnClickListener(view -> simulateAlarm());
        notificationSection.addView(alarmTest);
        Button appNotifications = button("Permitir notificaciones de CustomWatch");
        appNotifications.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        notificationSection.addView(appNotifications);
        access = label("", 14, Color.rgb(67, 83, 79));
        notificationSection.addView(access);
        notificationSection.addView(label("Android permite leer todas las notificaciones; CustomWatch solo procesa las aplicaciones activadas.", 14, Color.rgb(67, 83, 79)));
        Button permission = button("Acceso a notificaciones");
        permission.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        notificationSection.addView(permission);
        Button test = button("Enviar aviso de prueba");
        test.setOnClickListener(view -> {
            if (!WatchService.sendNotification("CustomWatch", "Prueba de aviso")) {
                status.setText("Conecta el reloj antes de enviar");
            }
        });
        notificationSection.addView(test);
        Button teamsTest = button("Enviar prueba de Teams");
        teamsTest.setOnClickListener(view -> {
            int type = NotificationRouting.teamsNotificationType(
                    getSharedPreferences("settings", MODE_PRIVATE).getBoolean("teams_sms_icon", true));
            if (!WatchService.sendNotification(type, "CustomWatch", "Prueba de Teams")) {
                status.setText("Conecta el reloj antes de enviar");
            }
        });
        notificationSection.addView(teamsTest);
        Button longTest = button("Enviar aviso largo de prueba");
        longTest.setOnClickListener(view -> {
            String message = "01: Este es un mensaje largo para comprobar el reloj. "
                    + "02: Deberias poder leer el principio sin problemas. "
                    + "03: Este tramo continua para medir el limite. "
                    + "04: Los mensajes pueden contener varias frases. "
                    + "05: Esta parte ayuda a identificar el corte. "
                    + "06: Si ves este final, no se ha perdido nada. FIN";
            if (!WatchService.sendNotification("CustomWatch", message)) {
                status.setText("Conecta el reloj antes de enviar");
            }
        });
        notificationSection.addView(longTest);
        setContentView(scroll);
        IntentFilter filter = new IntentFilter(WatchService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter, "es.lifefit.watch.permission.STATUS", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        access.setText(enabled != null && enabled.contains(getPackageName() + "/")
                ? "Acceso a notificaciones activado" : "Acceso a notificaciones desactivado");
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            connect();
        } else if (requestCode == 2) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                simulateAlarm();
            } else {
                status.setText("Permite las notificaciones de CustomWatch en sus ajustes para probar");
            }
        }
    }

    private void simulateAlarm() {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("phone_alarms", false)) {
            status.setText("Activa las alarmas del móvil antes de probar");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) {
            status.setText("Permite las notificaciones de CustomWatch en sus ajustes para probar");
            return;
        }
        String channel = "alarm_test";
        NotificationChannel testChannel = new NotificationChannel(channel, "Pruebas de alarma",
                NotificationManager.IMPORTANCE_LOW);
        testChannel.setSound(null, null);
        testChannel.enableVibration(false);
        manager.createNotificationChannel(testChannel);
        Notification testAlarm = new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Alarma simulada de CustomWatch")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build();
        testAlarm.flags |= Notification.FLAG_INSISTENT;
        int notificationId = (int) android.os.SystemClock.elapsedRealtime();
        manager.notify(notificationId, testAlarm);
        new android.os.Handler(getMainLooper()).postDelayed(() -> manager.cancel(notificationId), 15000);
        status.setText("Alarma simulada enviada; comprueba la vibración del reloj");
    }

    private void connect() {
        String value = address.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(value)) {
            status.setText("Dirección Bluetooth no válida");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("address", value).apply();
        Intent intent = new Intent(this, WatchService.class).putExtra("address", value);
        startForegroundService(intent);
    }

    private void updateStatus() {
        status.setText(WatchService.status());
        battery.setText(WatchService.batteryStatus());
        health.setText(WatchService.healthStatus());
        activity.setText(WatchService.activityStatus());
        sleep.setText(WatchService.sleepStatus());
        sleepHistoryStatus.setText(WatchService.sleepHistoryStatus());
        sleepHistory.setText(WatchService.sleepHistoryData());
        sport.setText(WatchService.sportStatus());
        weather.setText(WatchService.weatherStatus());
        music.setText(WatchService.musicStatus());
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private Button button(String text) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}