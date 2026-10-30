package es.lifefit.watch;

public final class WatchSleepCapabilities {
    public static final int GROUP = 2;
    public static final int REQUEST = 32;
    public static final int RESPONSE = 33;

    public static Boolean timestampedSamples(byte[] body) {
        if (body == null || body.length < 7 || (body[0] & 0xff) != GROUP
                || body[1] != 0 || (body[2] & 0xff) != RESPONSE) return null;
        int length = ((body[3] & 0xff) << 8) | (body[4] & 0xff);
        if (length != body.length - 5 || (body[5] & 0xff) != 17) return null;
        int descriptorLength = body[6] & 0xff;
        if (descriptorLength < 37 || descriptorLength > length - 2) return null;
        return (body[7 + 36] & 0x10) != 0;
    }
}