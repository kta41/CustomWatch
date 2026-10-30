package es.lifefit.watch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class WatchSleepSync {
    public enum Result { IGNORED, DUPLICATE, REQUEST_METADATA, REQUEST_SLEEP, PROGRESS, EMPTY, SAVE, ERROR }
    private enum State { CAPABILITIES, METADATA, TRANSFER, FINISHED, FAILED }

    private State state = State.CAPABILITIES;
    private Boolean timestamped;
    private final WatchSleepTransfer transfer = new WatchSleepTransfer();
    private final Map<Integer, byte[]> frames = new HashMap<>();

    public Result accept(int sequence, byte[] body) {
        if (state == State.CAPABILITIES && matches(body, 2, 33)) {
            timestamped = WatchSleepCapabilities.timestampedSamples(body);
            if (timestamped == null) return fail();
            state = State.METADATA;
            return Result.REQUEST_METADATA;
        }
        if (state == State.METADATA && matches(body, 5, 6)) {
            if (body.length < 9 || ((body[3] & 0xff) << 8 | body[4] & 0xff) != body.length - 5) return fail();
            if ((body[5] | body[6] | body[7] | body[8]) == 0) {
                state = State.FINISHED;
                return Result.EMPTY;
            }
            state = State.TRANSFER;
            return Result.REQUEST_SLEEP;
        }
        if (state != State.TRANSFER || body == null || body.length < 3 || body[0] != 5
                || (body[2] != 7 && body[2] != 8 && body[2] != 48)) return Result.IGNORED;
        byte[] previous = frames.get(sequence);
        if (previous != null) return Arrays.equals(previous, body) ? Result.DUPLICATE : fail();
        frames.put(sequence, body.clone());
        WatchSleepTransfer.Result result = transfer.accept(body);
        if (result == WatchSleepTransfer.Result.ERROR) return fail();
        if (result == WatchSleepTransfer.Result.COMPLETE) {
            state = State.FINISHED;
            return Result.SAVE;
        }
        return result == WatchSleepTransfer.Result.IGNORED ? Result.IGNORED : Result.PROGRESS;
    }

    public boolean timestamped() {
        if (timestamped == null) throw new IllegalStateException("Unknown sleep format");
        return timestamped;
    }

    public byte[] completedData() {
        return transfer.completedData();
    }

    private Result fail() {
        state = State.FAILED;
        return Result.ERROR;
    }

    private static boolean matches(byte[] body, int group, int key) {
        return body != null && body.length >= 5 && (body[0] & 0xff) == group
                && body[1] == 0 && (body[2] & 0xff) == key;
    }
}