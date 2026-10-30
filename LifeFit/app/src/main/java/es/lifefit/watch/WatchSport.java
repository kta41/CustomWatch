package es.lifefit.watch;

public final class WatchSport {
    public static final int GROUP = 5;
    public static final int REQUEST_STATUS = 72;
    public static final int RESPONSE_STATUS = 73;

    public static Integer statusFromResponse(byte[] body) {
        if (body == null || body.length < 13 || (body[0] & 0xff) != GROUP
                || body[1] != 0 || (body[2] & 0xff) != RESPONSE_STATUS) return null;
        int length = ((body[3] & 0xff) << 8) | (body[4] & 0xff);
        if (length != body.length - 5) return null;
        return body[11] & 0xff;
    }
}