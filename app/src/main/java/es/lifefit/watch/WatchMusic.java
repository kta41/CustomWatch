package es.lifefit.watch;

import java.nio.charset.StandardCharsets;

public final class WatchMusic {
    public static final int GROUP = 2;
    public static final int SET_INFO = 148;

    public static int controlFromResponse(byte[] body) {
        if (body == null || body.length != 6 || (body[0] & 0xff) != 7
                || body[1] != 0 || body[2] != 3 || body[3] != 0 || body[4] != 1) return -1;
        int control = body[5] & 0xff;
        return control <= 6 ? control : -1;
    }

    public static byte[] testPayload() {
        byte[] title = "Prueba CustomWatch".getBytes(StandardCharsets.UTF_8);
        byte[] artist = "Artista de prueba".getBytes(StandardCharsets.UTF_8);
        int duration = 180000;
        byte[] payload = new byte[6 + title.length + artist.length];
        payload[0] = (byte) title.length;
        payload[1] = (byte) artist.length;
        payload[2] = (byte) (duration >>> 24);
        payload[3] = (byte) (duration >>> 16);
        payload[4] = (byte) (duration >>> 8);
        payload[5] = (byte) duration;
        System.arraycopy(title, 0, payload, 6, title.length);
        System.arraycopy(artist, 0, payload, 6 + title.length, artist.length);
        return payload;
    }
}