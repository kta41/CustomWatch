package es.lifefit.watch;

import java.io.ByteArrayOutputStream;

public final class WatchSleepTransfer {
    public static final int GROUP = 5;
    public static final int REQUEST = 1;
    public static final int SLEEP_TYPE = 2;
    public static final int CONFIRM = 32;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    public enum Result { IGNORED, STARTED, PROGRESS, COMPLETE, ERROR }

    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private boolean started;
    private boolean finished;

    public Result accept(byte[] body) {
        if (body == null || body.length < 5 || (body[0] & 0xff) != GROUP || body[1] != 0) {
            return Result.IGNORED;
        }
        int key = body[2] & 0xff;
        if (key != 7 && key != 48 && key != 8) return Result.IGNORED;
        int length = ((body[3] & 0xff) << 8) | (body[4] & 0xff);
        if (finished || length != body.length - 5) return fail();
        if (key == 7) {
            if (started) return fail();
            started = true;
            return Result.STARTED;
        }
        if (!started) return fail();
        if (key == 48) {
            if (length > MAX_BYTES - data.size()) return fail();
            data.write(body, 5, length);
            return Result.PROGRESS;
        }
        if (length != 4) return fail();
        long expected = ((long) (body[5] & 0xff) << 24) | ((long) (body[6] & 0xff) << 16)
                | ((long) (body[7] & 0xff) << 8) | (body[8] & 0xff);
        if (expected != data.size()) return fail();
        finished = true;
        return Result.COMPLETE;
    }

    public byte[] completedData() {
        if (!finished || !started) throw new IllegalStateException("Transfer not complete");
        return data.toByteArray();
    }

    private Result fail() {
        started = false;
        finished = true;
        return Result.ERROR;
    }
}