package es.lifefit.watch;

public final class WatchBattery {
    public static final int GROUP = 2;
    public static final int REQUEST = 27;
    public static final int RESPONSE = 28;

    public final int percentage;
    public final boolean charging;

    private WatchBattery(int percentage, boolean charging) {
        this.percentage = percentage;
        this.charging = charging;
    }

    public static WatchBattery fromResponse(byte[] body) {
        if (body == null || body.length < 8 || (body[0] & 0xff) != GROUP
                || body[1] != 0 || (body[2] & 0xff) != RESPONSE) {
            return null;
        }
        int payloadLength = ((body[3] & 0xff) << 8) | (body[4] & 0xff);
        int charging = body[5] & 0xff;
        int percentage = body[7] & 0xff;
        if (payloadLength < 3 || payloadLength != body.length - 5
                || charging > 1 || percentage > 100) {
            return null;
        }
        return new WatchBattery(percentage, charging == 1);
    }
}