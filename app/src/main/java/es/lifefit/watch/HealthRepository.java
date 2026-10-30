package es.lifefit.watch;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HealthRepository extends SQLiteOpenHelper {
    public static final class Day {
        public final String date;
        public final long observedAt;
        public final long steps;
        public final double kilometers;
        public final double calories;

        Day(String date, long observedAt, long steps, double kilometers, double calories) {
            this.date = date;
            this.observedAt = observedAt;
            this.steps = steps;
            this.kilometers = kilometers;
            this.calories = calories;
        }
    }

    public static final class Pulse {
        public final int value;
        public final long observedAt;

        Pulse(int value, long observedAt) {
            this.value = value;
            this.observedAt = observedAt;
        }
    }

    public HealthRepository(Context context) {
        this(context, "health_observations.db");
    }

    HealthRepository(Context context, String name) {
        super(context, name, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE activity (device TEXT NOT NULL, day TEXT NOT NULL, observed INTEGER NOT NULL, "
                + "steps INTEGER NOT NULL, km REAL NOT NULL, calories REAL NOT NULL, PRIMARY KEY(device, day))");
        database.execSQL("CREATE TABLE pulse (device TEXT PRIMARY KEY, observed INTEGER NOT NULL, value INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported health database version");
    }

    public void saveActivity(String device, WatchActivity activity, long observedAt) {
        if (device == null || activity == null) throw new IllegalArgumentException("Missing activity data");
        ContentValues values = new ContentValues();
        values.put("device", device);
        values.put("day", new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(observedAt)));
        values.put("observed", observedAt);
        values.put("steps", activity.steps);
        values.put("km", activity.kilometers);
        values.put("calories", activity.calories);
        if (getWritableDatabase().insertWithOnConflict("activity", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new IllegalStateException("Activity observation was not stored");
        }
    }

    public void savePulse(String device, int pulse, long observedAt) {
        if (device == null || pulse <= 0 || pulse > 255) throw new IllegalArgumentException("Invalid pulse");
        ContentValues values = new ContentValues();
        values.put("device", device);
        values.put("observed", observedAt);
        values.put("value", pulse);
        if (getWritableDatabase().insertWithOnConflict("pulse", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new IllegalStateException("Pulse observation was not stored");
        }
    }

    public List<Day> activity(String device) {
        List<Day> days = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("activity",
                new String[]{"day", "observed", "steps", "km", "calories"}, "device=?",
                new String[]{device}, null, null, "observed DESC", "90")) {
            while (cursor.moveToNext()) days.add(new Day(cursor.getString(0), cursor.getLong(1),
                    cursor.getLong(2), cursor.getDouble(3), cursor.getDouble(4)));
        }
        return days;
    }

    public Pulse pulse(String device) {
        try (Cursor cursor = getReadableDatabase().query("pulse", new String[]{"value", "observed"},
                "device=?", new String[]{device}, null, null, null)) {
            return cursor.moveToFirst() ? new Pulse(cursor.getInt(0), cursor.getLong(1)) : null;
        }
    }
}