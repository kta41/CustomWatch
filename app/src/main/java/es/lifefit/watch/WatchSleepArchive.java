package es.lifefit.watch;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class WatchSleepArchive extends SQLiteOpenHelper {
    public static final class Transfer {
        public final byte[] data;
        public final boolean timestamped;
        public final String timeZone;

        Transfer(byte[] data, boolean timestamped, String timeZone) {
            this.data = data;
            this.timestamped = timestamped;
            this.timeZone = timeZone;
        }
    }

    public WatchSleepArchive(Context context) {
        this(context, "sleep_history.db");
    }

    WatchSleepArchive(Context context, String name) {
        super(context, name, null, 1);
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        database.execSQL("PRAGMA synchronous=FULL");
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE transfers (id INTEGER PRIMARY KEY, device TEXT NOT NULL, "
                + "digest TEXT NOT NULL, timestamped INTEGER NOT NULL, timezone TEXT NOT NULL, "
                + "received INTEGER NOT NULL, data BLOB NOT NULL, "
                + "UNIQUE(device, digest, timestamped, timezone))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported sleep archive version");
    }

    public void save(String device, boolean timestamped, String timeZone, byte[] data) {
        if (device == null || timeZone == null || data == null) throw new IllegalArgumentException("Missing transfer metadata");
        ContentValues values = new ContentValues();
        values.put("device", device);
        values.put("digest", digest(data));
        values.put("timestamped", timestamped ? 1 : 0);
        values.put("timezone", timeZone);
        values.put("received", System.currentTimeMillis());
        values.put("data", data);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.insertWithOnConflict("transfers", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            try (Cursor cursor = database.query("transfers", new String[]{"data"},
                    "device=? AND digest=? AND timestamped=? AND timezone=?",
                    new String[]{device, values.getAsString("digest"), timestamped ? "1" : "0", timeZone},
                    null, null, null)) {
                if (!cursor.moveToFirst() || !Arrays.equals(data, cursor.getBlob(0))) {
                    throw new IllegalStateException("Sleep transfer was not stored");
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public List<Transfer> load(String device) {
        List<Transfer> transfers = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("transfers", new String[]{"data", "timestamped", "timezone"},
                "device=?", new String[]{device}, null, null, "id DESC")) {
            while (cursor.moveToNext()) {
                transfers.add(new Transfer(cursor.getBlob(0), cursor.getInt(1) != 0, cursor.getString(2)));
            }
        }
        return transfers;
    }

    private static String digest(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}