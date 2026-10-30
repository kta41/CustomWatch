package es.lifefit.watch;

import java.nio.charset.StandardCharsets;

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
}