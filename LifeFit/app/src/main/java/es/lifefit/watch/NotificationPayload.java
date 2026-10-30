package es.lifefit.watch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class NotificationPayload {
    public static final int GROUP_NOTIFICATION = 4;
    public static final int TYPE_SMS = 4;
    public static final int TYPE_WHATSAPP = 12;
    public static final int TYPE_OTHER_APP = 0xff;

    private NotificationPayload() {
    }

    public static byte[] legacy(String sender, String message) {
        byte[] senderBytes = utf8Prefix(sender, 20);
        String displayName = new String(senderBytes, StandardCharsets.UTF_8).trim();
        String body = message == null ? "" : message;
        if (!displayName.isEmpty()) {
            body = displayName + ": " + body;
        }
        byte[] messageBytes = utf8Prefix(body, 240);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(senderBytes, 0, senderBytes.length);
        for (int index = senderBytes.length; index < 20; index++) {
            output.write(0);
        }
        output.write(messageBytes.length >>> 8);
        output.write(messageBytes.length & 0xff);
        output.write(messageBytes, 0, messageBytes.length);
        return output.toByteArray();
    }

    public static byte[] extended(String sender, String message) {
        byte[] senderBytes = utf8Prefix(sender, 64);
        byte[] messageBytes = utf8Prefix(message, 240 - senderBytes.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(senderBytes.length);
        output.write(senderBytes, 0, senderBytes.length);
        output.write(messageBytes.length >>> 8);
        output.write(messageBytes.length & 0xff);
        output.write(messageBytes, 0, messageBytes.length);
        return output.toByteArray();
    }

    private static byte[] utf8Prefix(String value, int limit) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            if (output.size() + encoded.length > limit) {
                break;
            }
            output.write(encoded, 0, encoded.length);
            offset += Character.charCount(codePoint);
        }
        return output.toByteArray();
    }
}