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

        Night(SleepMetrics metrics, TimeZone timeZone) {
            this.metrics = metrics;
            this.timeZone = timeZone;
        }
    }

    public static final class Result {
        public final List<Night> nights;
        public final boolean unreadable;

        Result(List<Night> nights, boolean unreadable) {
            this.nights = nights;
            this.unreadable = unreadable;
        }
    }

    public static Result load(Context context, String device) {
        Map<String, Night> nights = new TreeMap<>(Collections.reverseOrder());
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
                        if (nights.containsKey(day.getKey())) continue;
                        SleepMetrics metrics = SleepMetrics.from(day.getKey(), day.getValue());
                        if (metrics != null) nights.put(day.getKey(), new Night(metrics, zone));
                    }
                } catch (IllegalArgumentException exception) {
                    unreadable = true;
                }
            }
        }
        return new Result(new ArrayList<>(nights.values()), unreadable);
    }
}