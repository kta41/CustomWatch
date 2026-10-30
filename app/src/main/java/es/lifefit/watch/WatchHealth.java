package es.lifefit.watch;

public final class WatchHealth {
    public static final int GROUP = 5;
    public static final int REQUEST = 66;
    public static final int RESPONSE = 67;

    public final int heartRate;
    public final int oxygen;
    public final int diastolic;
    public final int systolic;
        public final Integer bodyTemperatureHundredths;

        private WatchHealth(int heartRate, int oxygen, int diastolic, int systolic,
            Integer bodyTemperatureHundredths) {
        this.heartRate = heartRate;
        this.oxygen = oxygen;
        this.diastolic = diastolic;
        this.systolic = systolic;
        this.bodyTemperatureHundredths = bodyTemperatureHundredths;
    }

    public static WatchHealth fromResponse(byte[] body) {
        if (body == null || body.length < 9 || (body[0] & 0xff) != GROUP
                || body[1] != 0 || (body[2] & 0xff) != RESPONSE) return null;
        int length = ((body[3] & 0xff) << 8) | (body[4] & 0xff);
        if (length != body.length - 5) return null;
        Integer temperature = length == 8 || length == 9
            ? (int) (short) (((body[11] & 0xff) << 8) | (body[12] & 0xff)) : null;
        return new WatchHealth(body[5] & 0xff, body[6] & 0xff,
            body[7] & 0xff, body[8] & 0xff, temperature);
    }
}