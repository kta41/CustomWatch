package es.lifefit.watch;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class SleepArchiveInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Context context = getTargetContext();
        String database = "sleep_test_" + UUID.randomUUID() + ".db";
        String healthDatabase = "health_test_" + UUID.randomUUID() + ".db";
        String settingsName = "settings_test_" + UUID.randomUUID();
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            byte[] data = {0, 3, 53, 94, 5, 40, 0, 5, 1, 2, 3};
            try (WatchSleepArchive archive = new WatchSleepArchive(context, database)) {
                archive.save("test-device", false, "UTC", data);
                archive.save("test-device", false, "UTC", data);
            }
            try (WatchSleepArchive archive = new WatchSleepArchive(context, database)) {
                List<WatchSleepArchive.Transfer> saved = archive.load("test-device");
                if (saved.size() != 1 || !Arrays.equals(saved.get(0).data, data)
                        || saved.get(0).timestamped || !saved.get(0).timeZone.equals("UTC")
                        || !archive.load("other-device").isEmpty()) {
                    throw new AssertionError("Archive persistence or deduplication failed");
                }
                if (WatchSleepHistory.decode(saved.get(0).data, false, java.util.TimeZone.getTimeZone("UTC")).size() != 3) {
                    throw new AssertionError("Archived data cannot be decoded");
                }
            }
            byte[] activityResponse = new byte[35];
            activityResponse[0] = 5;
            activityResponse[2] = 34;
            activityResponse[4] = 30;
            activityResponse[8] = 42;
            WatchActivity activity = WatchActivity.fromResponse(activityResponse);
            try (HealthRepository repository = new HealthRepository(context, healthDatabase)) {
                repository.saveActivity("test-device", activity, 1000000000L);
                repository.saveActivity("test-device", activity, 1000000001L);
                repository.savePulse("test-device", 72, 1000000000L);
            }
            try (HealthRepository repository = new HealthRepository(context, healthDatabase)) {
                if (repository.activity("test-device").size() != 1
                        || repository.activity("test-device").get(0).steps != 42
                        || repository.activity("test-device").get(0).observedAt != 1000000001L
                        || !repository.activity("other-device").isEmpty()
                        || repository.pulse("test-device").value != 72
                        || repository.pulse("other-device") != null) {
                    throw new AssertionError("Health persistence or isolation failed");
                }
            }
            android.content.SharedPreferences preferences = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE);
            preferences.edit().putBoolean("whatsapp", true).putBoolean("teams", true).commit();
            NotificationSettings settings = new NotificationSettings(preferences);
            if (!settings.shouldForward("com.whatsapp") || !settings.shouldForward("com.microsoft.teams")
                    || settings.shouldForward("another.app")) throw new AssertionError("Legacy preferences not preserved");
            settings.setAppEnabled("com.whatsapp", false);
            settings.setAppEnabled("another.app", true);
            if (settings.shouldForward("com.whatsapp") || !settings.shouldForward("com.whatsapp.w4b")
                    || !settings.shouldForward("another.app")) throw new AssertionError("Per-app selection failed");
            settings.setRelayEnabled(false);
            if (settings.shouldForward("another.app") || !settings.appEnabled("another.app")) {
                throw new AssertionError("Global selection failed");
            }
            result.putString("result", "Sleep archive, health and notification settings: OK");
            status = Activity.RESULT_OK;
        } catch (Throwable exception) {
            result.putString("error", exception.toString());
        } finally {
            context.deleteDatabase(database);
            context.deleteDatabase(healthDatabase);
            context.deleteSharedPreferences(settingsName);
            finish(status, result);
        }
    }
}