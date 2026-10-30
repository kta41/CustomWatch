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
        String exportDatabase = "export_test_" + UUID.randomUUID() + ".db";
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
            testSleepExport(context, exportDatabase);
            result.putString("result", "Sleep archive, health, notification settings and export: OK");
            status = Activity.RESULT_OK;
        } catch (Throwable exception) {
            result.putString("error", exception.toString());
        } finally {
            context.deleteDatabase(database);
            context.deleteDatabase(healthDatabase);
            context.deleteDatabase(exportDatabase);
            context.deleteSharedPreferences(settingsName);
            finish(status, result);
        }
    }

    private static void testSleepExport(Context context, String database) throws Exception {
        String date = "2026-10-29";
        String oldSource = UUID.randomUUID().toString();
        try (android.database.sqlite.SQLiteDatabase legacy = context.openOrCreateDatabase(database, Context.MODE_PRIVATE, null)) {
            legacy.execSQL("CREATE TABLE identity (id INTEGER PRIMARY KEY CHECK(id=1), source TEXT NOT NULL)");
            legacy.execSQL("CREATE TABLE revisions (date TEXT PRIMARY KEY, digest TEXT NOT NULL, revision INTEGER NOT NULL)");
            legacy.execSQL("INSERT INTO identity VALUES (1, ?)", new Object[]{oldSource});
            legacy.execSQL("INSERT INTO revisions VALUES (?, 'legacy', 42)", new Object[]{date});
            legacy.setVersion(1);
        }
        List<WatchSleepHistory.Sample> samples = Arrays.asList(
                new WatchSleepHistory.Sample(date, 0, 10, 3),
                new WatchSleepHistory.Sample(date, 10, 30, 1),
                new WatchSleepHistory.Sample(date, 20, 40, 2),
                new WatchSleepHistory.Sample(date, 30, 50, 2),
                new WatchSleepHistory.Sample(date, 50, 60, 3));
        SleepMetrics metrics = SleepMetrics.from(date, samples);
        SleepRepository.Result input = new SleepRepository.Result(Arrays.asList(
                new SleepRepository.Night(metrics, java.util.TimeZone.getTimeZone("Europe/Madrid"))), true);
        org.json.JSONObject first = SleepExport.document(context, "test-device", input, database);
        org.json.JSONObject same = SleepExport.document(context, "test-device", input, database);
        org.json.JSONObject other = SleepExport.document(context, "other-device", input, database);
        org.json.JSONObject night = first.getJSONArray("nights").getJSONObject(0);
        if (first.getInt("schema_version") != 1 || !first.getBoolean("unreadable")
                || !UUID.fromString(first.getString("source_id")).toString().equals(same.getString("source_id"))
                || first.getString("source_id").equals(other.getString("source_id"))
                || first.getString("source_id").equals(oldSource) || first.toString().contains("test-device")
                || other.getJSONArray("nights").getJSONObject(0).getLong("revision") != 1
                || night.getLong("revision") != 1 || same.getJSONArray("nights").getJSONObject(0).getLong("revision") != 1
                || !night.getString("date").equals(date) || !night.getString("time_zone").equals("Europe/Madrid")
                || night.getLong("start_ms") != 10 || night.getLong("end_ms") != 50
                || night.getLong("deep_ms") != 20 || night.getLong("light_ms") != 20 || night.getLong("awake_ms") != 0
                || !night.getBoolean("overlapping") || night.getJSONArray("samples").length() != 4
                || !night.getJSONArray("samples").getJSONObject(0).getString("state").equals("awake")) {
            throw new AssertionError("Export contract or unchanged revision failed");
        }
        SleepRepository.Selector selector = new SleepRepository.Selector();
        selector.add(SleepMetrics.from(date, Arrays.asList(new WatchSleepHistory.Sample(date, 10, 30, 2))),
                java.util.TimeZone.getTimeZone("UTC"));
        selector.add(metrics, java.util.TimeZone.getTimeZone("Europe/Madrid"));
        org.json.JSONObject partial = SleepExport.document(context, "test-device", selector.result(true), database);
        if (!first.toString().equals(partial.toString())) {
            throw new AssertionError("Partial transfer changed export or revision");
        }
        SleepRepository.Selector conflict = new SleepRepository.Selector();
        conflict.add(SleepMetrics.from(date, Arrays.asList(new WatchSleepHistory.Sample(date, 0, 20, 1))),
                java.util.TimeZone.getTimeZone("UTC"));
        conflict.add(SleepMetrics.from(date, Arrays.asList(new WatchSleepHistory.Sample(date, 10, 30, 1))),
                java.util.TimeZone.getTimeZone("UTC"));
        try {
            SleepExport.document(context, "test-device", conflict.result(false), database);
            throw new AssertionError("Ambiguous export was accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains(date)) throw expected;
        }
        if (!first.toString().equals(SleepExport.document(context, "test-device", input, database).toString())) {
            throw new AssertionError("Ambiguous export changed revision state");
        }
        SleepMetrics changed = SleepMetrics.from(date, Arrays.asList(new WatchSleepHistory.Sample(date, 10, 40, 1)));
        SleepRepository.Result corrected = new SleepRepository.Result(Arrays.asList(
                new SleepRepository.Night(changed, java.util.TimeZone.getTimeZone("UTC"))), false);
        org.json.JSONObject next = SleepExport.document(context, "test-device", corrected, database);
        org.json.JSONObject reverted = SleepExport.document(context, "test-device", input, database);
        org.json.JSONObject otherAgain = SleepExport.document(context, "other-device", input, database);
        if (next.getJSONArray("nights").getJSONObject(0).getLong("revision") != 2
                || reverted.getJSONArray("nights").getJSONObject(0).getLong("revision") != 3
                || !next.getString("source_id").equals(first.getString("source_id"))
                || otherAgain.getJSONArray("nights").getJSONObject(0).getLong("revision") != 1) {
            throw new AssertionError("Corrected export revision failed");
        }
        try (android.database.sqlite.SQLiteDatabase legacy = context.openOrCreateDatabase(database, Context.MODE_PRIVATE, null);
                android.database.Cursor identity = legacy.rawQuery("SELECT source FROM identity WHERE id=1", null);
                android.database.Cursor revision = legacy.rawQuery("SELECT digest, revision FROM revisions WHERE date=?", new String[]{date})) {
            if (!identity.moveToFirst() || !oldSource.equals(identity.getString(0)) || !revision.moveToFirst()
                    || !"legacy".equals(revision.getString(0)) || revision.getLong(1) != 42) {
                throw new AssertionError("Legacy export state was changed during migration");
            }
        }
    }
}