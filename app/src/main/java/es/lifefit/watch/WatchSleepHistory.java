package es.lifefit.watch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

public final class WatchSleepHistory {
    public static final class Sample {
        public final String date;
        public final long start;
        public final long end;
        public final int state;

        Sample(String date, long start, long end, int state) {
            this.date = date;
            this.start = start;
            this.end = end;
            this.state = state;
        }
    }

    public static List<Sample> decode(byte[] data, boolean timestamped, TimeZone zone) {
        if (data == null || zone == null) throw new IllegalArgumentException("Missing sleep data");
        List<Sample> samples = new ArrayList<>();
        int width = timestamped ? 5 : 1;
        int offset = 0;
        while (offset < data.length) {
            if (data.length - offset < 8) throw new IllegalArgumentException("Incomplete sleep header");
            int count = unsignedShort(data, offset);
            long first = dateTime(data, offset + 2, zone);
            int interval = unsignedShort(data, offset + 6);
            offset += 8;
            if (count > (data.length - offset) / width) throw new IllegalArgumentException("Incomplete sleep block");
            Calendar day = new GregorianCalendar(zone);
            day.setTimeInMillis(first);
            if (day.get(Calendar.HOUR_OF_DAY) > 12) day.add(Calendar.DAY_OF_MONTH, 1);
            String date = String.format(Locale.ROOT, "%04d-%02d-%02d", day.get(Calendar.YEAR),
                    day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
            long previous = first;
            for (int index = 0; index < count; index++) {
                long end = timestamped ? dateTime(data, offset, zone) : first + index * interval * 60000L;
                int state = data[offset + width - 1] & 0xff;
                offset += width;
                if (timestamped) {
                    if (end <= previous) continue;
                    if (state <= 3) samples.add(new Sample(date, previous, end, state == 0 ? 3 : state));
                    previous = end;
                } else if (state >= 1 && state <= 3) {
                    samples.add(new Sample(date, end - 300000L, end, state));
                }
            }
        }
        return samples;
    }

    public static String summarize(List<List<Sample>> newestFirst) {
        Map<String, List<Sample>> days = new TreeMap<>(Collections.reverseOrder());
        for (List<Sample> transfer : newestFirst) {
            Map<String, List<Sample>> transferDays = new TreeMap<>();
            for (Sample sample : transfer) {
                List<Sample> day = transferDays.get(sample.date);
                if (day == null) {
                    day = new ArrayList<>();
                    transferDays.put(sample.date, day);
                }
                day.add(sample);
            }
            for (Map.Entry<String, List<Sample>> entry : transferDays.entrySet()) {
                if (!days.containsKey(entry.getKey())) days.put(entry.getKey(), entry.getValue());
            }
        }
        if (days.isEmpty()) return "Historial: sin registros guardados";
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<Sample>> entry : days.entrySet()) {
            List<Sample> day = entry.getValue();
            day.sort(Comparator.comparingLong(sample -> sample.start));
            long[] totals = new long[4];
            long lastEnd = Long.MIN_VALUE;
            boolean overlapping = false;
            for (Sample sample : day) {
                if (sample.start < lastEnd) {
                    overlapping = true;
                    continue;
                }
                totals[sample.state] += sample.end - sample.start;
                lastEnd = sample.end;
            }
            if (text.length() > 0) text.append('\n');
            long deep = totals[1] / 60000;
            long light = totals[2] / 60000;
            text.append(String.format(Locale.ROOT, "%s  ·  %d h %02d min\nProfundo: %d min  ·  Ligero: %d min",
                    entry.getKey(), (deep + light) / 60, (deep + light) % 60, deep, light));
            if (overlapping) text.append("  ·  registros solapados omitidos");
        }
        return text.toString();
    }

    private static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long dateTime(byte[] data, int offset, TimeZone zone) {
        int first = data[offset] & 0xff;
        int second = data[offset + 1] & 0xff;
        int minutes = unsignedShort(data, offset + 2);
        Calendar calendar = new GregorianCalendar(zone);
        calendar.clear();
        calendar.setLenient(false);
        calendar.set(2000 + ((first & 0x7f) >> 1), ((first & 1) << 3 | second >> 5) - 1,
                second & 31, minutes / 60, minutes % 60);
        return calendar.getTimeInMillis();
    }
}