package es.lifefit.watch;

import java.util.List;
import java.util.TimeZone;

public final class WatchSleepHistorySyntheticTest {
    public static void main(String[] args) {
        byte[] data = {0, 3, 53, 94, 5, 40, 0, 5,
                53, 94, 5, 45, 1, 53, 94, 5, 50, 9, 53, 94, 5, 55, 2};
        List<WatchSleepHistory.Sample> samples = WatchSleepHistory.decode(data, true, TimeZone.getTimeZone("UTC"));
        if (samples.size() != 2 || samples.get(0).state != 1 || samples.get(1).state != 2
                || samples.get(0).end - samples.get(0).start != 300000L
                || samples.get(1).start - samples.get(0).end != 300000L
                || samples.get(1).end - samples.get(1).start != 300000L) {
            throw new AssertionError("Invalid state must leave a gap between valid samples");
        }
    }
}