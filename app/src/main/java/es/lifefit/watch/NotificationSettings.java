package es.lifefit.watch;

import android.content.Context;
import android.content.SharedPreferences;

public final class NotificationSettings {
    private static final String PREFIX = "notification_app:";
    private final SharedPreferences preferences;

    public NotificationSettings(Context context) {
        this(context.getSharedPreferences("settings", Context.MODE_PRIVATE));
    }

    NotificationSettings(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public boolean relayEnabled() {
        return preferences.getBoolean("notification_relay_enabled", true);
    }

    public void setRelayEnabled(boolean enabled) {
        preferences.edit().putBoolean("notification_relay_enabled", enabled).apply();
    }

    public boolean appEnabled(String packageName) {
        String key = PREFIX + packageName;
        if (preferences.contains(key)) return preferences.getBoolean(key, false);
        if ("com.whatsapp".equals(packageName) || "com.whatsapp.w4b".equals(packageName)) {
            return preferences.getBoolean("whatsapp", false);
        }
        if ("com.microsoft.teams".equals(packageName)) return preferences.getBoolean("teams", false);
        return false;
    }

    public void setAppEnabled(String packageName, boolean enabled) {
        preferences.edit().putBoolean(PREFIX + packageName, enabled).apply();
    }

    public boolean shouldForward(String packageName) {
        return NotificationRouting.isAllowed(relayEnabled(), appEnabled(packageName));
    }
}