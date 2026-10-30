package es.lifefit.watch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SleepMetrics {
    public final String date;
    public final List<WatchSleepHistory.Sample> samples;
    public final long start;
    public final long end;
    public final long deepMillis;
    public final long lightMillis;
    public final long awakeMillis;
    public final boolean overlapping;

    private SleepMetrics(String date, List<WatchSleepHistory.Sample> samples, long start, long end,
            long deepMillis, long lightMillis, long awakeMillis, boolean overlapping) {
        this.date = date;
        this.samples = samples;
        this.start = start;
        this.end = end;
        this.deepMillis = deepMillis;
        this.lightMillis = lightMillis;
        this.awakeMillis = awakeMillis;
        this.overlapping = overlapping;
    }

    public long sleepMillis() {
        return deepMillis + lightMillis;
    }

    public static SleepMetrics from(String date, List<WatchSleepHistory.Sample> input) {
        List<WatchSleepHistory.Sample> ordered = new ArrayList<>();
        for (WatchSleepHistory.Sample sample : input) {
            if (sample.date.equals(date) && sample.end > sample.start && sample.state >= 1 && sample.state <= 3) {
                ordered.add(sample);
            }
        }
        ordered.sort(Comparator.comparingLong(sample -> sample.start));
        List<WatchSleepHistory.Sample> valid = new ArrayList<>();
        long lastEnd = Long.MIN_VALUE;
        boolean overlaps = false;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (WatchSleepHistory.Sample sample : ordered) {
            if (sample.start < lastEnd) {
                overlaps = true;
                continue;
            }
            valid.add(sample);
            lastEnd = sample.end;
            if (sample.state != 3) {
                start = Math.min(start, sample.start);
                end = Math.max(end, sample.end);
            }
        }
        if (start == Long.MAX_VALUE) return null;
        long[] totals = new long[4];
        for (WatchSleepHistory.Sample sample : valid) {
            long visibleStart = Math.max(start, sample.start);
            long visibleEnd = Math.min(end, sample.end);
            if (visibleEnd > visibleStart) totals[sample.state] += visibleEnd - visibleStart;
        }
        return new SleepMetrics(date, valid, start, end, totals[1], totals[2], totals[3], overlaps);
    }
}