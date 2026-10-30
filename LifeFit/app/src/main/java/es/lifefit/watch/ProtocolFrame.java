package es.lifefit.watch;

public final class ProtocolFrame {
    private ProtocolFrame() {
    }

    public static byte[] command(int group, int key, byte[] payload, int sequence) {
        if (group < 0 || group > 255 || key < 0 || key > 255 || payload.length > 511) {
            throw new IllegalArgumentException("Invalid command");
        }
        byte[] body = new byte[payload.length + 5];
        body[0] = (byte) group;
        body[2] = (byte) key;
        body[3] = (byte) (payload.length >>> 8);
        body[4] = (byte) payload.length;
        System.arraycopy(payload, 0, body, 5, payload.length);
        byte[] frame = new byte[body.length + 8];
        frame[0] = (byte) 0xab;
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        int checksum = checksum(body);
        frame[4] = (byte) (checksum >>> 8);
        frame[5] = (byte) checksum;
        frame[6] = (byte) (sequence >>> 8);
        frame[7] = (byte) sequence;
        System.arraycopy(body, 0, frame, 8, body.length);
        return frame;
    }

    public static byte[] acknowledgement(int sequence) {
        return new byte[]{(byte) 0xab, 0x10, 0, 0, 0, 0,
                (byte) (sequence >>> 8), (byte) sequence};
    }

    public static int checksum(byte[] bytes) {
        int crc = 0;
        for (byte value : bytes) {
            crc ^= value & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
            }
        }
        return crc;
    }
}