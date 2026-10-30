package es.lifefit.watch;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

public final class SleepRepository {
    public static final class Night {
        public final SleepMetrics metrics;
        public final TimeZone timeZone;
        public final boolean ambiguous;

        Night(SleepMetrics metrics, TimeZone timeZone) {
            this(metrics, timeZone, false);
        }

        Night(SleepMetrics metrics, TimeZone timeZone, boolean ambiguous) {
            this.metrics = metrics;
            this.timeZone = timeZone;
            this.ambiguous = ambiguous;
        }
    }

    public static final class Result {
        public final List<Night> nights;
        public final boolean unreadable;
        public final boolean ambiguous;

        Result(List<Night> nights, boolean unreadable) {
            this.nights = nights;
            this.unreadable = unreadable;
            boolean conflict = false;
            for (Night night : nights) conflict |= night.ambiguous;
            this.ambiguous = conflict;
        }
    }

    static final class Selector {
        private final Map<String, List<Night>> candidates = new TreeMap<>(Collections.reverseOrder());

        void add(SleepMetrics metrics, TimeZone zone) {
            if (metrics == null) return;
            if (!candidates.containsKey(metrics.date)) candidates.put(metrics.date, new ArrayList<>());
            candidates.get(metrics.date).add(new Night(metrics, zone));
        }

        Result result(boolean unreadable) {
            List<Night> nights = new ArrayList<>();
            for (List<Night> day : candidates.values()) {
                List<Night> maximal = new ArrayList<>();
                for (int index = 0; index < day.size(); index++) {
                    Night candidate = day.get(index);
                    boolean dominated = false;
                    for (int otherIndex = 0; otherIndex < day.size(); otherIndex++) {
                        if (otherIndex == index) continue;
                        Night other = day.get(otherIndex);
                        if (contains(other.metrics, candidate.metrics)
                                && (otherIndex < index || !contains(candidate.metrics, other.metrics))) {
                            dominated = true;
                            break;
                        }
                    }
                    if (!dominated) maximal.add(candidate);
                }
                Night selected = maximal.get(0);
                nights.add(new Night(selected.metrics, selected.timeZone, maximal.size() > 1));
            }
            return new Result(nights, unreadable);
        }
    }

    private static boolean contains(SleepMetrics outer, SleepMetrics inner) {
        int outerIndex = 0;
        for (WatchSleepHistory.Sample sample : inner.samples) {
            long start = Math.max(inner.start, sample.start);
            long end = Math.min(inner.end, sample.end);
            if (end <= start) continue;
            long covered = start;
            while (outerIndex < outer.samples.size() && covered < end) {
                WatchSleepHistory.Sample interval = outer.samples.get(outerIndex);
                long intervalStart = Math.max(outer.start, interval.start);
                long intervalEnd = Math.min(outer.end, interval.end);
                if (intervalEnd <= covered) {
                    outerIndex++;
                } else if (intervalStart > covered) {
                    return false;
                } else {
                    covered = intervalEnd;
                }
            }
            if (covered < end) return false;
        }
        return true;
    }

    public static Result load(Context context, String device) {
        Selector selector = new Selector();
        boolean unreadable = false;
        try (WatchSleepArchive archive = new WatchSleepArchive(context)) {
            for (WatchSleepArchive.Transfer transfer : archive.load(device)) {
                try {
                    TimeZone zone = TimeZone.getTimeZone(transfer.timeZone);
                    List<WatchSleepHistory.Sample> samples = WatchSleepHistory.decode(transfer.data, transfer.timestamped, zone);
                    Map<String, List<WatchSleepHistory.Sample>> days = new TreeMap<>();
                    for (WatchSleepHistory.Sample sample : samples) {
                        if (!days.containsKey(sample.date)) days.put(sample.date, new ArrayList<>());
                        days.get(sample.date).add(sample);
                    }
                    for (Map.Entry<String, List<WatchSleepHistory.Sample>> day : days.entrySet()) {
                        SleepMetrics metrics = SleepMetrics.from(day.getKey(), day.getValue());
                        selector.add(metrics, zone);
                    }
                } catch (IllegalArgumentException exception) {
                    unreadable = true;
                }
            }
        }
        return selector.result(unreadable);
    }
}