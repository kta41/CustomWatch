package es.lifefit.watch;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;

public final class WatchWeather {
    public static final int GROUP = 2;
    public static final int SET = 54;

    public static byte[] testPayload() {
        byte[] city = "Prueba".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[11 + city.length];
        payload[0] = 20;
        payload[1] = 15;
        payload[2] = 25;
        payload[3] = 0;
        payload[4] = (byte) city.length;
        System.arraycopy(city, 0, payload, 5, city.length);
        return payload;
    }

    public static byte[] extendedTestPayload(Calendar time) {
        byte[] basic = testPayload();
        int cityEnd = 5 + (basic[4] & 0xff);
        byte[] payload = new byte[basic.length + 5];
        System.arraycopy(basic, 0, payload, 0, cityEnd);
        int year = time.get(Calendar.YEAR) - 2000;
        int month = time.get(Calendar.MONTH) + 1;
        int day = time.get(Calendar.DAY_OF_MONTH);
        int hour = time.get(Calendar.HOUR_OF_DAY);
        int minute = time.get(Calendar.MINUTE);
        payload[cityEnd] = (byte) ((year << 2) | (month >> 2));
        payload[cityEnd + 1] = (byte) ((month << 6) | (day << 1) | (hour >> 4));
        payload[cityEnd + 2] = (byte) ((hour << 4) | (minute >> 2));
        payload[cityEnd + 3] = (byte) (time.get(Calendar.SECOND) | (minute << 6));
        System.arraycopy(basic, cityEnd, payload, cityEnd + 5, basic.length - cityEnd);
        return payload;
    }
}