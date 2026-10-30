package es.lifefit.watch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProductActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(14, 17, 20);
    private static final int SURFACE = Color.rgb(25, 30, 32);
    private static final int BORDER = Color.rgb(51, 60, 58);
    private static final int TEXT = Color.rgb(239, 242, 235);
    private static final int MUTED = Color.rgb(174, 184, 178);
    private static final int ACCENT = Color.rgb(178, 204, 157);
    private static final int WARM = Color.rgb(217, 180, 145);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private final Runnable refresh = this::loadData;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("notificaciones".equals(page)) return;
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 250);
        }
    };
    private LinearLayout content;
    private LinearLayout navigation;
    private ScrollView scroll;
    private SleepRepository.Result sleep;
    private List<HealthRepository.Day> days = Collections.emptyList();
    private HealthRepository.Pulse pulse;
    private List<AppEntry> applications;
    private String loadError;
    private boolean loading = true;
    private boolean appsLoading;
    private boolean destroyed;
    private int generation;
    private int selectedNight;
    private String page = "inicio";
    private String message = "";

    private static final class AppEntry {
        final String packageName;
        final String label;
        final android.graphics.drawable.Drawable icon;

        AppEntry(String packageName, String label, android.graphics.drawable.Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WatchService.loadBattery(this, address());
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(24), dp(18), dp(24), dp(32));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new LinearLayout(this);
        navigation.setBackgroundColor(SURFACE);
        navigation.setPadding(dp(2), dp(4), dp(2), dp(4));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(68)));
        setContentView(root);
        IntentFilter filter = new IntentFilter(WatchService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter, "es.lifefit.watch.permission.STATUS", null);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        loadData();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if ("notificaciones".equals(page)) navigate("ajustes");
        else if (!"inicio".equals(page)) navigate("inicio");
        else super.onBackPressed();
    }

    private String address() {
        return getSharedPreferences("MainActivity", MODE_PRIVATE).getString("address", "");
    }

    private void loadData() {
        final int request = ++generation;
        final String device = address();
        worker.execute(() -> {
            SleepRepository.Result loadedSleep = null;
            List<HealthRepository.Day> loadedDays = Collections.emptyList();
            HealthRepository.Pulse loadedPulse = null;
            String error = null;
            try {
                loadedSleep = SleepRepository.load(getApplicationContext(), device);
                try (HealthRepository repository = new HealthRepository(getApplicationContext())) {
                    loadedDays = repository.activity(device);
                    loadedPulse = repository.pulse(device);
                }
            } catch (RuntimeException exception) {
                error = "No se pudieron leer todos los datos guardados.";
            }
            final SleepRepository.Result resultSleep = loadedSleep;
            final List<HealthRepository.Day> resultDays = loadedDays;
            final HealthRepository.Pulse resultPulse = loadedPulse;
            final String resultError = error;
            runOnUiThread(() -> {
                if (destroyed || request != generation) return;
                sleep = resultSleep;
                days = resultDays;
                pulse = resultPulse;
                loadError = resultError;
                loading = false;
                if (!"notificaciones".equals(page)) render();
            });
        });
    }

    private void navigate(String next) {
        page = next;
        message = "";
        scroll.scrollTo(0, 0);
        if ("notificaciones".equals(next) && applications == null && !appsLoading) loadApplications();
        render();
    }

    private void render() {
        if (content == null) return;
        int previousScroll = scroll.getScrollY();
        content.removeAllViews();
        navigation.removeAllViews();
        if ("inicio".equals(page)) dashboard();
        else if ("sueno".equals(page)) sleepScreen();
        else if ("actividad".equals(page)) activityScreen();
        else if ("pulso".equals(page)) pulseScreen();
        else if ("notificaciones".equals(page)) notificationsScreen();
        else settingsScreen();
        nav("Inicio", "inicio", android.R.drawable.ic_menu_view);
        nav("Sueño", "sueno", android.R.drawable.ic_menu_recent_history);
        nav("Actividad", "actividad", android.R.drawable.ic_menu_compass);
        nav("Pulso", "pulso", android.R.drawable.ic_menu_info_details);
        nav("Ajustes", "ajustes", android.R.drawable.ic_menu_preferences);
        scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    private void nav(String label, String key, int icon) {
        boolean active = key.equals(page) || ("ajustes".equals(key) && "notificaciones".equals(page));
        TextView item = text(label, 10, active ? ACCENT : MUTED, active);
        item.setGravity(Gravity.CENTER);
        item.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        item.setCompoundDrawablePadding(dp(3));
        item.setCompoundDrawableTintList(ColorStateList.valueOf(active ? ACCENT : MUTED));
        item.setContentDescription(label + (active ? ", seleccionado" : ""));
        item.setOnClickListener(view -> navigate(key));
        navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void header(String title, String subtitle) {
        TextView brand = text("C U S T O M W A T C H", 11, ACCENT, true);
        content.addView(brand);
        space(16);
        content.addView(text(title, 30, TEXT, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            space(5);
            content.addView(text(subtitle, 14, MUTED, false));
        }
        space(24);
        if (loadError != null) notice(loadError);
        if (!WatchService.storageStatus().isEmpty()) notice(WatchService.storageStatus());
        if (!message.isEmpty()) notice(message);
    }

    private void dashboard() {
        header("Tu resumen", (WatchService.isConnected() ? "Conectado" : WatchService.status())
            + "  ·  " + WatchService.batteryStatus());
        if (loading) {
            notice("Cargando datos guardados...");
            return;
        }
        content.addView(text("ÚLTIMO SUEÑO", 11, ACCENT, true));
        space(10);
        SleepRepository.Night night = sleep == null || sleep.nights.isEmpty() ? null : sleep.nights.get(0);
        if (night == null) {
            content.addView(text("Sin sueño guardado", 22, TEXT, true));
            space(4);
            content.addView(text("Los intervalos disponibles aparecerán aquí.", 14, MUTED, false));
        } else {
            content.addView(text(duration(night.metrics.sleepMillis()), 38, TEXT, true));
            space(4);
            content.addView(text(prettyDay(night.metrics.date) + "  ·  " + time(night.metrics.start, night.timeZone)
                    + " – " + time(night.metrics.end, night.timeZone), 14, MUTED, false));
        }
        space(16);
        link("Ver sueño", () -> navigate("sueno"));
        divider();
        content.addView(text("ACTIVIDAD Y PULSO", 11, ACCENT, true));
        space(14);
        LinearLayout row = new LinearLayout(this);
        LinearLayout steps = metricTile("PASOS", days.isEmpty() ? "—" : number(days.get(0).steps),
                days.isEmpty() ? "Sin consulta" : "Última consulta", () -> navigate("actividad"));
        LinearLayout heart = metricTile("PULSO", pulse == null ? "—" : String.valueOf(pulse.value),
                pulse == null ? "Sin lectura" : "lpm · fecha desconocida", () -> navigate("pulso"));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMargins(dp(6), 0, 0, 0);
        row.addView(steps, first);
        row.addView(heart, second);
        content.addView(row);
        space(16);
        content.addView(action("Actualizar resumen", true, () -> {
            boolean activityRequested = WatchService.requestActivity();
            boolean pulseRequested = WatchService.requestHealth();
            boolean batteryRequested = WatchService.requestBattery();
            if (!activityRequested || !pulseRequested || !batteryRequested) {
                showMessage("Conecta el reloj antes de actualizar.");
            }
        }));
        if (WatchService.activityStatus().contains("consultando") || WatchService.healthStatus().contains("Consultando")) {
            space(8);
            content.addView(text("Actualizando el resumen...", 13, MUTED, false));
        } else if (WatchService.activityStatus().contains("sin respuesta") || WatchService.healthStatus().contains("Sin respuesta")) {
            space(8);
            content.addView(text("No se pudo actualizar todo el resumen. Revisa la conexión y vuelve a intentarlo.", 13, WARM, false));
        }
        divider();
        content.addView(text("Tus datos, en tu móvil", 16, TEXT, true));
        space(5);
        content.addView(text("Se eliminan al borrar los datos o desinstalar CustomWatch.", 13, MUTED, false));
    }

    private SleepRepository.Night currentNight() {
        if (sleep == null || sleep.nights.isEmpty()) return null;
        selectedNight = Math.max(0, Math.min(selectedNight, sleep.nights.size() - 1));
        return sleep.nights.get(selectedNight);
    }

    private void sleepScreen() {
        header("Sueño", "Tus noches guardadas en este móvil");
        if (loading) { notice("Cargando sueño..."); return; }
        SleepRepository.Night night = currentNight();
        if (night == null) {
            empty("Todavía no hay noches guardadas", "No hay intervalos de sueño disponibles para mostrar.");
            return;
        }
        SleepMetrics metrics = night.metrics;
        if (sleep.nights.size() > 1) {
            LinearLayout chooser = new LinearLayout(this);
            Button previous = action("Anterior", false, () -> { selectedNight = Math.min(selectedNight + 1, sleep.nights.size() - 1); render(); });
            Button next = action("Siguiente", false, () -> { selectedNight = Math.max(0, selectedNight - 1); render(); });
            previous.setEnabled(selectedNight < sleep.nights.size() - 1);
            next.setEnabled(selectedNight > 0);
            chooser.addView(previous, new LinearLayout.LayoutParams(0, dp(48), 1));
            chooser.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
            content.addView(chooser);
            space(16);
        }
        content.addView(text(prettyDay(metrics.date), 14, ACCENT, true));
        space(8);
        content.addView(text(duration(metrics.sleepMillis()), 40, TEXT, true));
        space(6);
        content.addView(text("Tiempo dormido  ·  " + time(metrics.start, night.timeZone) + " – "
                + time(metrics.end, night.timeZone), 14, MUTED, false));
        divider();
        content.addView(text("Fases del sueño", 19, TEXT, true));
        space(4);
        content.addView(text("Horas en " + night.timeZone.getID(), 12, MUTED, false));
        space(12);
        SleepTimelineView timeline = new SleepTimelineView(this, metrics, night.timeZone);
        content.addView(timeline, new LinearLayout.LayoutParams(-1, dp(180)));
        TextView detail = text("Selecciona un intervalo para ver su duración", 13, MUTED, false);
        detail.setMinHeight(dp(40));
        content.addView(detail);
        timeline.setOnSelection(detail::setText);
        space(12);
        statistic("Profundo", duration(metrics.deepMillis), ACCENT);
        statistic("Ligero", duration(metrics.lightMillis), TEXT);
        statistic("Despierto durante el periodo", duration(metrics.awakeMillis), WARM);
        if (metrics.overlapping) notice("Se han omitido intervalos solapados.");
        if (sleep.unreadable) notice("Algunos registros guardados no se pudieron interpretar.");
        space(10);
        content.addView(text("Las fases son las comunicadas por el reloj y no se han contrastado por separado. Sin valoración de calidad.", 13, MUTED, false));
    }

    private void activityScreen() {
        header("Actividad", "Resumen recibido del reloj");
        if (loading) { notice("Cargando actividad..."); return; }
        if (days.isEmpty()) {
            empty("Sin actividad consultada", "Conecta el reloj para consultar el resumen disponible.");
        } else {
            HealthRepository.Day latest = days.get(0);
            content.addView(text("ÚLTIMO TOTAL RECIBIDO", 11, ACCENT, true));
            space(8);
            content.addView(text(number(latest.steps), 40, TEXT, true));
            content.addView(text("pasos  ·  consultado " + observed(latest.observedAt), 13, MUTED, false));
            space(18);
            statistic("Distancia", String.format(Locale.getDefault(), "%.2f km", latest.kilometers), TEXT);
            statistic("Energía", String.format(Locale.getDefault(), "%.1f kcal", latest.calories), TEXT);
            space(6);
            content.addView(text("Distancia y energía son interpretaciones del protocolo pendientes de contraste.", 12, MUTED, false));
            divider();
            content.addView(text("Consultas guardadas", 19, TEXT, true));
            space(5);
            content.addView(text("Fecha de consulta; el reloj no proporciona fecha ni distribución horaria.", 13, MUTED, false));
            space(18);
            long maximum = 0;
            for (HealthRepository.Day day : days) maximum = Math.max(maximum, day.steps);
            for (HealthRepository.Day day : days) {
                LinearLayout line = new LinearLayout(this);
                TextView dayLabel = text(prettyDay(day.date), 12, MUTED, false);
                line.addView(dayLabel, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(text(number(day.steps) + " pasos", 13, TEXT, true));
                content.addView(line);
                space(6);
                LinearLayout track = new LinearLayout(this);
                track.setBackground(shape(BORDER, 4));
                View fill = new View(this);
                fill.setBackground(shape(ACCENT, 4));
                float fraction = maximum == 0 ? 0 : (float) day.steps / maximum;
                track.addView(fill, new LinearLayout.LayoutParams(0, dp(8), fraction));
                if (fraction < 1) track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(8), 1 - fraction));
                track.setContentDescription(prettyDay(day.date) + ": " + number(day.steps) + " pasos, fecha de consulta");
                content.addView(track);
                space(18);
            }
        }
        int goal = getSharedPreferences("settings", MODE_PRIVATE).getInt("steps_goal", 0);
        divider();
        content.addView(text("Objetivo personal", 19, TEXT, true));
        space(6);
        content.addView(text(goal > 0 ? number(goal) + " pasos" : "Sin objetivo configurado", 14, MUTED, false));
        if (goal > 0 && !days.isEmpty()) {
            long steps = days.get(0).steps;
            space(8);
            content.addView(text(String.format(Locale.getDefault(), "%.0f%% del objetivo · última consulta",
                100.0 * steps / goal), 14, ACCENT, true));
        }
        space(10);
        link(goal > 0 ? "Cambiar objetivo" : "Definir objetivo", this::editGoal);
        space(12);
        content.addView(action("Consultar actividad", true, () -> {
            if (!WatchService.requestActivity()) showMessage("Conecta el reloj antes de consultar.");
        }));
        space(5);
        content.addView(text(WatchService.activityStatus(), 12, MUTED, false));
    }

    private void pulseScreen() {
        header("Pulso", "Última lectura disponible");
        if (loading) { notice("Cargando pulso..."); return; }
        if (pulse == null) {
            empty("Sin lectura de pulso", "Todavía no se ha recibido una medición.");
        } else {
            content.addView(text("ÚLTIMA LECTURA", 11, ACCENT, true));
            space(10);
            content.addView(text(pulse.value + "  lpm", 40, TEXT, true));
            space(6);
            content.addView(text("Fecha de medición desconocida · consultado " + observed(pulse.observedAt), 13, MUTED, false));
        }
        divider();
        content.addView(text("Evolución", 19, TEXT, true));
        space(8);
        content.addView(text("No hay una serie de mediciones con fecha. No se pueden calcular mínimo, máximo ni media.", 14, MUTED, false));
        space(20);
        content.addView(action("Consultar pulso", true, () -> {
            if (!WatchService.requestHealth()) showMessage("Conecta el reloj antes de consultar.");
        }));
        space(6);
        content.addView(text(WatchService.healthStatus(), 12, MUTED, false));
    }

    private void settingsScreen() {
        header("Ajustes", "Reloj, avisos y privacidad");
        content.addView(text("RELOJ", 11, ACCENT, true));
        space(10);
        content.addView(text(WatchService.status(), 17, TEXT, true));
        space(5);
        content.addView(text(WatchService.batteryStatus(), 14, MUTED, false));
        space(14);
        content.addView(action("Conectar reloj", true, this::connect));
        space(8);
        content.addView(action("Buscar reloj", false, () -> {
            if (!WatchService.findWatch()) showMessage("Conecta el reloj antes de buscarlo.");
        }));
        space(8);
        content.addView(action("Consultar batería", false, () -> {
            if (!WatchService.requestBattery()) showMessage("Conecta el reloj antes de consultar.");
        }));
        space(8);
        link("Cambiar dirección Bluetooth", this::editAddress);
        space(12);
        link("Desconectar reloj", () -> stopService(new Intent(this, WatchService.class)));
        divider();
        content.addView(text("NOTIFICACIONES", 11, ACCENT, true));
        space(10);
        content.addView(text("Aplicaciones y acceso", 18, TEXT, true));
        space(4);
        content.addView(text(listenerEnabled() ? "Acceso de Android activado" : "Acceso de Android desactivado", 13, MUTED, false));
        space(10);
        content.addView(action("Gestionar notificaciones", false, () -> navigate("notificaciones")));
        divider();
        content.addView(text("CONTROLES DEL RELOJ", 11, ACCENT, true));
        space(10);
        settingsSwitch("Control de música", "music_control", false);
        settingsSwitch("Vibrar con alarmas del móvil", "phone_alarms", false);
        content.addView(text("Las alarmas usan la función de búsqueda del reloj; no muestran una alarma nativa.", 12, MUTED, false));
        divider();
        content.addView(text("PRIVACIDAD", 11, ACCENT, true));
        space(10);
        content.addView(text("Los datos se guardan en este móvil. Borrar los datos o desinstalar CustomWatch los elimina; no hay copia automática.", 14, MUTED, false));
        space(14);
        link("Herramientas avanzadas", () -> startActivity(new Intent(this, MainActivity.class)));
    }

    private void notificationsScreen() {
        header("Notificaciones", "Selecciona qué aplicaciones reenvía CustomWatch");
        NotificationSettings settings = new NotificationSettings(this);
        Switch global = switchView("Reenvío al reloj", settings.relayEnabled());
        global.setOnCheckedChangeListener((button, checked) -> settings.setRelayEnabled(checked));
        content.addView(global);
        space(8);
        content.addView(text("El interruptor general pausa el reenvío sin cambiar las selecciones. Estas no modifican los permisos de cada aplicación en Android.", 13, MUTED, false));
        divider();
        content.addView(text("Acceso de Android", 18, TEXT, true));
        space(5);
        content.addView(text(listenerEnabled() ? "Activado" : "Desactivado: CustomWatch no puede recibir avisos", 14,
                listenerEnabled() ? ACCENT : WARM, false));
        space(10);
        content.addView(action("Abrir ajustes de acceso", false,
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        space(8);
        link("Notificaciones propias de CustomWatch", () -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        divider();
        content.addView(text("Aplicaciones instaladas", 18, TEXT, true));
        space(5);
        content.addView(text("Selección para CustomWatch; el permiso individual de Android no se puede comprobar aquí. Activa solo las aplicaciones cuyos avisos quieras compartir con el reloj.", 13, MUTED, false));
        space(12);
        if (applications == null) {
            content.addView(text(appsLoading ? "Cargando aplicaciones..." : "No se pudo cargar la lista.", 14, MUTED, false));
            return;
        }
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint("Buscar aplicación");
        search.setContentDescription("Buscar aplicaciones por nombre o paquete");
        content.addView(search);
        LinearLayout appList = column();
        content.addView(appList);
        Runnable filter = () -> renderApplications(appList, search.getText().toString(), settings);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { filter.run(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        filter.run();
        divider();
        settingsSwitch("Usar icono SMS para Teams", "teams_sms_icon", true);
        space(4);
        content.addView(text("Las demás aplicaciones usan el tipo SMS del reloj. WhatsApp solo reenvía conversaciones identificadas como individuales. La visualización depende del reloj.", 12, MUTED, false));
    }

    private void renderApplications(LinearLayout list, String query, NotificationSettings settings) {
        list.removeAllViews();
        String filter = query.trim().toLowerCase(Locale.ROOT);
        List<AppEntry> matches = new ArrayList<>();
        for (AppEntry app : applications) {
            if (app.label.toLowerCase(Locale.ROOT).contains(filter) || app.packageName.toLowerCase(Locale.ROOT).contains(filter)) {
                matches.add(app);
            }
        }
        TextView count = text(matches.size() + (matches.size() == 1 ? " aplicación" : " aplicaciones"), 12, MUTED, false);
        count.setPadding(0, dp(12), 0, dp(4));
        list.addView(count);
        appendApplications(list, matches, 0, settings);
    }

    private void appendApplications(LinearLayout list, List<AppEntry> matches, int start, NotificationSettings settings) {
        int end = Math.min(start + 50, matches.size());
        for (int index = start; index < end; index++) {
            AppEntry app = matches.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            Switch toggle = switchView(app.label, settings.appEnabled(app.packageName));
            toggle.setSingleLine(true);
            toggle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            toggle.setPadding(dp(14), 0, 0, 0);
            toggle.setContentDescription(app.label + ", reenvío en CustomWatch");
            toggle.setOnCheckedChangeListener((button, checked) -> settings.setAppEnabled(app.packageName, checked));
            row.addView(toggle, new LinearLayout.LayoutParams(0, dp(48), 1));
            list.addView(row);
        }
        if (end < matches.size()) {
            Button more = action("Mostrar más aplicaciones", false, null);
            more.setOnClickListener(view -> {
                list.removeView(more);
                appendApplications(list, matches, end, settings);
            });
            list.addView(more, new LinearLayout.LayoutParams(-1, dp(48)));
        }
    }

    private void loadApplications() {
        appsLoading = true;
        worker.execute(() -> {
            List<AppEntry> found = new ArrayList<>();
            try {
                PackageManager manager = getPackageManager();
                for (ApplicationInfo application : manager.getInstalledApplications(0)) {
                    String name = application.packageName;
                    if (name.equals(getPackageName()) || !application.enabled) continue;
                    found.add(new AppEntry(name, application.loadLabel(manager).toString(), application.loadIcon(manager)));
                }
                found.sort((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.label, second.label));
            } catch (RuntimeException exception) {
                found = null;
            }
            final List<AppEntry> result = found;
            runOnUiThread(() -> {
                if (destroyed) return;
                appsLoading = false;
                applications = result;
                if ("notificaciones".equals(page)) render();
            });
        });
    }

    private boolean listenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        for (String value : enabled.split(":")) {
            android.content.ComponentName component = android.content.ComponentName.unflattenFromString(value);
            if (component != null && component.equals(new android.content.ComponentName(this, NotificationRelayService.class))) return true;
        }
        return false;
    }

    private void connect() {
        String value = address();
        if (!BluetoothAdapter.checkBluetoothAddress(value)) { showMessage("Dirección Bluetooth no válida."); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        try {
            startForegroundService(new Intent(this, WatchService.class).putExtra("address", value));
        } catch (RuntimeException exception) {
            showMessage("No se pudo iniciar la conexión Bluetooth.");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) connect();
        else if (code == 1) showMessage("El permiso Bluetooth es necesario para conectar.");
    }

    private void editAddress() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(address());
        input.setContentDescription("Dirección Bluetooth del reloj");
        new AlertDialog.Builder(this).setTitle("Dirección Bluetooth").setView(input)
                .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", (dialog, which) -> {
                    String value = input.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if (!BluetoothAdapter.checkBluetoothAddress(value)) { showMessage("Dirección Bluetooth no válida."); return; }
                    getSharedPreferences("MainActivity", MODE_PRIVATE).edit().putString("address", value).apply();
                    selectedNight = 0;
                    loadData();
                }).show();
    }

    private void editGoal() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Pasos");
        int goal = getSharedPreferences("settings", MODE_PRIVATE).getInt("steps_goal", 0);
        if (goal > 0) input.setText(String.valueOf(goal));
        input.setContentDescription("Objetivo personal de pasos");
        new AlertDialog.Builder(this).setTitle("Objetivo personal de pasos").setView(input)
                .setNegativeButton("Cancelar", null).setNeutralButton("Eliminar", (dialog, which) -> {
                    getSharedPreferences("settings", MODE_PRIVATE).edit().remove("steps_goal").apply();
                    render();
                }).setPositiveButton("Guardar", (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString());
                        if (value <= 0) throw new NumberFormatException();
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("steps_goal", value).apply();
                        render();
                    } catch (NumberFormatException exception) { showMessage("Introduce un objetivo válido."); }
                }).show();
    }

    private void settingsSwitch(String title, String key, boolean fallback) {
        SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        Switch control = switchView(title, preferences.getBoolean(key, fallback));
        control.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean(key, checked).apply());
        content.addView(control, new LinearLayout.LayoutParams(-1, dp(54)));
    }

    private Switch switchView(String title, boolean checked) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextSize(15);
        control.setTextColor(TEXT);
        control.setChecked(checked);
        control.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, MUTED}));
        control.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(89, 113, 81), BORDER}));
        control.setMinHeight(dp(48));
        return control;
    }

    private LinearLayout metricTile(String label, String value, String caption, Runnable click) {
        LinearLayout tile = column();
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        tile.setBackground(shape(SURFACE, 8));
        tile.setMinimumHeight(dp(132));
        tile.addView(text(label, 11, ACCENT, true));
        TextView valueView = text(value, 27, TEXT, true);
        valueView.setSingleLine(true);
        valueView.setAutoSizeTextTypeUniformWithConfiguration(14, 27, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        valueView.setPadding(0, dp(12), 0, dp(4));
        tile.addView(valueView);
        tile.addView(text(caption, 12, MUTED, false));
        tile.setOnClickListener(view -> click.run());
        tile.setFocusable(true);
        tile.setContentDescription(label + ": " + value + ", " + caption);
        return tile;
    }

    private void statistic(String label, String value, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(text(label, 14, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(value, 15, color, true));
        content.addView(row);
    }

    private void empty(String title, String detail) {
        content.addView(text(title, 21, TEXT, true));
        space(7);
        content.addView(text(detail, 14, MUTED, false));
    }

    private void notice(String value) {
        TextView notice = text(value, 13, WARM, false);
        notice.setPadding(dp(12), dp(10), dp(12), dp(10));
        notice.setBackground(shape(SURFACE, 6));
        content.addView(notice);
        space(14);
    }

    private void showMessage(String value) {
        message = value;
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
        render();
    }

    private void link(String label, Runnable click) {
        TextView link = text(label, 14, ACCENT, true);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setMinHeight(dp(44));
        link.setOnClickListener(view -> click.run());
        content.addView(link);
    }

    private Button action(String title, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(primary ? BACKGROUND : TEXT);
        button.setBackground(shape(primary ? ACCENT : SURFACE, 6));
        if (click != null) button.setOnClickListener(view -> click.run());
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void space(int height) {
        content.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void divider() {
        space(24);
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        content.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        space(24);
    }

    private String prettyDay(String date) {
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            parser.setLenient(false);
            return new SimpleDateFormat("d MMM yyyy", new Locale("es", "ES")).format(parser.parse(date));
        } catch (Exception exception) { return date; }
    }

    private String time(long millis, TimeZone zone) {
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm", Locale.getDefault());
        formatter.setTimeZone(zone);
        return formatter.format(new Date(millis));
    }

    private String observed(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, new Locale("es", "ES")).format(new Date(millis));
    }

    private String duration(long millis) {
        long minutes = millis / 60000;
        return minutes >= 60 ? (minutes / 60) + " h " + String.format(Locale.ROOT, "%02d", minutes % 60) + " min" : minutes + " min";
    }

    private String number(long value) {
        return String.format(new Locale("es", "ES"), "%,d", value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}