package es.lifefit.watch;

public final class WatchActivity {
    public static final int GROUP = 5;
    public static final int REQUEST = 33;
    public static final int RESPONSE = 34;

    public final long steps;
    public final double kilometers;
    public final double calories;
    public final long deepSleepMinutes;
    public final long lightSleepMinutes;

    private WatchActivity(long steps, long distance, long calories, long deepSleepMinutes, long lightSleepMinutes) {
        this.steps = steps;
        this.kilometers = distance / 1000.0;
        this.calories = calories / 1000.0;
        this.deepSleepMinutes = deepSleepMinutes;
        this.lightSleepMinutes = lightSleepMinutes;
    }

    public static WatchActivity fromResponse(byte[] body) {
        if (body == null || body.length < 35 || (body[0] & 0xff) != GROUP
                || body[1] != 0 || (body[2] & 0xff) != RESPONSE) return null;
        int length = ((body[3] & 0xff) << 8) | (body[4] & 0xff);
        if (length != body.length - 5) return null;
        return new WatchActivity(unsignedInt(body, 5), unsignedInt(body, 9), unsignedInt(body, 13),
            unsignedInt(body, 17), unsignedInt(body, 21));
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24) | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }
}