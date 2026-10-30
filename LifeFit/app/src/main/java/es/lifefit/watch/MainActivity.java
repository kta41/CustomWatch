package es.lifefit.watch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
    private TextView alarms;
    private TextView sport;
    private TextView access;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus();
        }
    };

    @Override
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

        TextView title = label("LifeFit", 30, Color.rgb(20, 43, 42));
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
        address.setText(getPreferences(MODE_PRIVATE).getString("address", "[redacted]"));
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
        alarms = label(WatchService.alarmsStatus(), 16, Color.rgb(20, 43, 42));
        watchSection.addView(alarms);
        Button refreshAlarms = button("Consultar alarmas");
        refreshAlarms.setOnClickListener(view -> {
            if (!WatchService.requestAlarms()) alarms.setText("Conecta el reloj antes de consultar");
        });
        watchSection.addView(refreshAlarms);
        Button testWeather = button("Probar clima");
        testWeather.setOnClickListener(view -> new AlertDialog.Builder(this)
            .setTitle("Clima de prueba")
            .setMessage("Se enviarán 20 °C y una ciudad de prueba, sustituyendo el clima actual del reloj.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar", (dialog, which) -> {
                if (!WatchService.sendWeatherTest()) status.setText("Conecta el reloj antes de enviar");
            }).show());
        watchSection.addView(testWeather);

        healthSection.addView(label("ÚLTIMA MEDICIÓN", 13, Color.rgb(137, 71, 51)));
        health = label(WatchService.healthStatus(), 16, Color.rgb(20, 43, 42));
        healthSection.addView(health);
        healthSection.addView(label("El reloj no indica la fecha de esta medición. Datos orientativos, no aptos para uso médico.", 14, Color.rgb(67, 83, 79)));
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
        whatsapp.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("whatsapp", false));
        whatsapp.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("whatsapp", checked).apply());
        notificationSection.addView(whatsapp);
        Switch teams = new Switch(this);
        teams.setText("Teams");
        teams.setTextSize(16);
        teams.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("teams", false));
        teams.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("teams", checked).apply());
        notificationSection.addView(teams);
        Switch teamsSmsIcon = new Switch(this);
        teamsSmsIcon.setText("Icono SMS para Teams");
        teamsSmsIcon.setTextSize(16);
        teamsSmsIcon.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("teams_sms_icon", true));
        teamsSmsIcon.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("teams_sms_icon", checked).apply());
        notificationSection.addView(teamsSmsIcon);
        access = label("", 14, Color.rgb(67, 83, 79));
        notificationSection.addView(access);
        notificationSection.addView(label("Android permite leer todas las notificaciones; LifeFit solo procesa las aplicaciones activadas.", 14, Color.rgb(67, 83, 79)));
        Button permission = button("Acceso a notificaciones");
        permission.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        notificationSection.addView(permission);
        Button test = button("Enviar aviso de prueba");
        test.setOnClickListener(view -> {
            if (!WatchService.sendNotification("LifeFit", "Prueba de aviso")) {
                status.setText("Conecta el reloj antes de enviar");
            }
        });
        notificationSection.addView(test);
        Button teamsTest = button("Enviar prueba de Teams");
        teamsTest.setOnClickListener(view -> {
            int type = NotificationRouting.teamsNotificationType(
                    getSharedPreferences("settings", MODE_PRIVATE).getBoolean("teams_sms_icon", true));
            if (!WatchService.sendNotification(type, "LifeFit", "Prueba de Teams")) {
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
            if (!WatchService.sendNotification("LifeFit", message)) {
                status.setText("Conecta el reloj antes de enviar");
            }
        });
        notificationSection.addView(longTest);
        setContentView(scroll);
        IntentFilter filter = new IntentFilter(WatchService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
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
        }
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
        alarms.setText(WatchService.alarmsStatus());
        sport.setText(WatchService.sportStatus());
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