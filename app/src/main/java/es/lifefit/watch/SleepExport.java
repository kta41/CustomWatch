package es.lifefit.watch;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class SleepExport {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final String PREFERENCES = "sleep_export";

    private SleepExport() {}

    private static final class State extends SQLiteOpenHelper {
        State(Context context, String name) {
            super(context, name, null, 2);
        }

        @Override public void onConfigure(SQLiteDatabase database) {
            database.execSQL("PRAGMA synchronous=FULL");
        }

        @Override public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE identity (id INTEGER PRIMARY KEY CHECK(id=1), source TEXT NOT NULL)");
            database.execSQL("CREATE TABLE revisions (date TEXT PRIMARY KEY, digest TEXT NOT NULL, revision INTEGER NOT NULL)");
            createDeviceTables(database);
        }

        @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            if (oldVersion == 1 && newVersion == 2) createDeviceTables(database);
            else throw new IllegalStateException("Unsupported export state version");
        }

        private void createDeviceTables(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE device_identities (device TEXT PRIMARY KEY, source TEXT NOT NULL UNIQUE)");
            database.execSQL("CREATE TABLE device_revisions (source TEXT NOT NULL, date TEXT NOT NULL, "
                    + "digest TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(source, date))");
        }
    }

    static JSONObject document(Context context, String device) throws JSONException {
        return document(context, device, SleepRepository.load(context, device), "sleep_export.db");
    }

    static JSONObject document(Context context, String device, SleepRepository.Result result, String databaseName) throws JSONException {
        if (device == null || device.isEmpty()) throw new IllegalArgumentException("Selecciona un reloj primero.");
        if (result.ambiguous) {
            for (SleepRepository.Night night : result.nights) {
                if (night.ambiguous) throw new IllegalStateException("Exportación bloqueada: coberturas de sueño incompatibles para "
                        + night.metrics.date + ". No se ha modificado el documento.");
            }
        }
        try (State state = new State(context, databaseName)) {
            SQLiteDatabase database = state.getWritableDatabase();
            database.beginTransaction();
            try {
                String source;
                try (Cursor cursor = database.rawQuery("SELECT source FROM device_identities WHERE device=?", new String[]{device})) {
                    source = cursor.moveToFirst() ? cursor.getString(0) : null;
                }
                if (source == null) {
                    source = UUID.randomUUID().toString();
                    ContentValues identity = new ContentValues();
                    identity.put("device", device);
                    identity.put("source", source);
                    database.insertOrThrow("device_identities", null, identity);
                }
                JSONArray nights = new JSONArray();
                for (SleepRepository.Night night : result.nights) {
                    JSONObject item = night(night);
                    String digest = digest(item.toString());
                    long revision = 1;
                        try (Cursor cursor = database.query("device_revisions", new String[]{"digest", "revision"},
                            "source=? AND date=?", new String[]{source, night.metrics.date}, null, null, null)) {
                        if (cursor.moveToFirst()) {
                            revision = cursor.getLong(1);
                            if (!digest.equals(cursor.getString(0))) {
                                if (revision == Long.MAX_VALUE) throw new IllegalStateException("Revision exhausted");
                                revision++;
                            }
                        }
                    }
                    ContentValues values = new ContentValues();
                    values.put("source", source);
                    values.put("date", night.metrics.date);
                    values.put("digest", digest);
                    values.put("revision", revision);
                    if (database.insertWithOnConflict("device_revisions", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                        throw new IllegalStateException("No se pudo guardar la revisión.");
                    }
                    item.put("revision", revision);
                    nights.put(item);
                }
                JSONObject document = new JSONObject();
                document.put("schema_version", 1);
                document.put("source_id", source);
                document.put("nights", nights);
                document.put("unreadable", result.unreadable);
                database.setTransactionSuccessful();
                return document;
            } finally {
                database.endTransaction();
            }
        }
    }

    private static JSONObject night(SleepRepository.Night night) throws JSONException {
        SleepMetrics metrics = night.metrics;
        JSONObject item = new JSONObject();
        item.put("date", metrics.date);
        item.put("time_zone", night.timeZone.getID());
        item.put("start_ms", metrics.start);
        item.put("end_ms", metrics.end);
        item.put("deep_ms", metrics.deepMillis);
        item.put("light_ms", metrics.lightMillis);
        item.put("awake_ms", metrics.awakeMillis);
        item.put("overlapping", metrics.overlapping);
        JSONArray samples = new JSONArray();
        for (WatchSleepHistory.Sample sample : metrics.samples) {
            JSONObject entry = new JSONObject();
            entry.put("start_ms", sample.start);
            entry.put("end_ms", sample.end);
            entry.put("state", sample.state == 1 ? "deep" : sample.state == 2 ? "light" : "awake");
            samples.put(entry);
        }
        item.put("samples", samples);
        return item;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String pendingKey(String destination) {
        return "pending_" + digest(destination);
    }

    static synchronized boolean selectDevice(Context context, String device) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putString("address", device).commit();
    }

    static synchronized boolean selectDestination(Context context, String destination) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("uri", destination)
                .remove("error").commit();
    }

    public static void exportAsync(Context context, String expectedDevice, boolean automatic, Runnable finished) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            String destination = preferences.getString("uri", "");
            try {
                if (destination.isEmpty()) throw new IOException("Selecciona un documento de exportación.");
                if (expectedDevice == null || expectedDevice.isEmpty()) throw new IOException("Selecciona un reloj primero.");
                JSONObject result = document(app, expectedDevice);
                byte[] data = result.toString().getBytes(StandardCharsets.UTF_8);
                synchronized (SleepExport.class) {
                if (!expectedDevice.equals(app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("address", ""))
                        || !destination.equals(preferences.getString("uri", ""))) {
                    throw new IOException("Cambió el reloj o el documento seleccionado; vuelve a exportar.");
                }
                if (automatic && preferences.getBoolean(pendingKey(destination), false)) {
                    throw new IOException("La exportación anterior quedó pendiente; reintenta manualmente.");
                }
                if (!preferences.edit().putBoolean(pendingKey(destination), true).commit()) {
                    throw new IOException("No se pudo marcar la exportación pendiente.");
                }
                try (OutputStream output = app.getContentResolver().openOutputStream(Uri.parse(destination), "wt")) {
                    if (output == null) throw new IOException("El proveedor no abrió el documento.");
                    output.write(data);
                    output.flush();
                }
                try (InputStream input = app.getContentResolver().openInputStream(Uri.parse(destination))) {
                    if (input == null) throw new IOException("El proveedor no abrió el documento para verificarlo.");
                    byte[] buffer = new byte[8192];
                    int offset = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (count > data.length - offset) throw new IOException("El documento exportado no coincide.");
                        for (int index = 0; index < count; index++) {
                            if (buffer[index] != data[offset + index]) throw new IOException("El documento exportado no coincide.");
                        }
                        offset += count;
                    }
                    if (offset != data.length) throw new IOException("El documento exportado está incompleto.");
                }
                if (!preferences.edit().putLong("last_success", System.currentTimeMillis())
                    .putString("last_uri", destination).putString("last_device", expectedDevice)
                    .putBoolean("last_unreadable", result.getBoolean("unreadable"))
                    .remove(pendingKey(destination)).remove("error").remove("error_uri").commit()) {
                    preferences.edit().putBoolean(pendingKey(destination), true).commit();
                    throw new IOException("No se pudo guardar el estado de exportación.");
                }
                }
            } catch (Exception exception) {
                preferences.edit().putString("error", exception.getMessage() == null
                    ? "No se pudo exportar el sueño." : exception.getMessage())
                    .putString("error_uri", destination).commit();
            }
            app.sendBroadcast(new Intent(WatchService.ACTION_STATUS).setPackage(app.getPackageName()));
            if (finished != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(finished);
        });
    }
}