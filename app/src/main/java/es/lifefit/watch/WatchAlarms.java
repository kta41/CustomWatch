package es.lifefit.watch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class WatchAlarms {
    public static final int GROUP = 2;
    public static final int REQUEST = 3;
    public static final int RESPONSE = 4;

    public static final class Alarm {
        public final int id;
        public final int year;
        public final int month;
        public final int day;
        public final int hour;
        public final int minute;
        public final int repeat;
        public final boolean enabled;
        public final String label;

        private Alarm(int id, int year, int month, int day, int hour, int minute,
                int repeat, boolean enabled, String label) {
            this.id = id;
            this.year = year;
            this.month = month;
            this.day = day;
            this.hour = hour;
            this.minute = minute;
            this.repeat = repeat;
            this.enabled = enabled;
            this.label = label;
        }
    }

    public static List<Alarm> fromResponse(byte[] body) {
        if (body == null || body.length < 5 || (body[0] & 0xff) != GROUP
                || body[1] != 0 || (body[2] & 0xff) != RESPONSE) return null;
        int length = ((body[3] & 0xff) << 8) | (body[4] & 0xff);
        if (length != body.length - 5) return null;
        List<Alarm> alarms = new ArrayList<>();
        for (int offset = 5; offset < body.length;) {
            int size = body[offset++] & 0xff;
            if (size < 5 || size > body.length - offset) return null;
            int hour = ((body[offset + 1] << 4) & 16) | ((body[offset + 2] >> 4) & 15);
            int minute = ((body[offset + 2] << 2) & 63) | ((body[offset + 3] >> 6) & 3);
            if (hour > 23 || minute > 59) return null;
            int flags = body[offset + 4] & 0xff;
                int year = ((body[offset] & 0xff) >> 2) + 2000;
                int month = ((body[offset] << 2) & 15) | ((body[offset + 1] >> 6) & 3);
                int day = (body[offset + 1] >> 1) & 31;
                int id = (body[offset + 3] >> 3) & 7;
            String label = new String(body, offset + 5, size - 5, StandardCharsets.UTF_8);
                alarms.add(new Alarm(id, year, month, day, hour, minute, flags & 127,
                    (flags & 128) != 0, label));
            offset += size;
        }
        return alarms;
    }
}