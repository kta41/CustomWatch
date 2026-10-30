import es.lifefit.watch.ProtocolFrame;
import es.lifefit.watch.NotificationPayload;
import es.lifefit.watch.NotificationRouting;
import es.lifefit.watch.WatchBattery;
import es.lifefit.watch.WatchHealth;
import es.lifefit.watch.WatchAlarms;
import es.lifefit.watch.WatchSport;
import es.lifefit.watch.WatchWeather;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ProtocolFrameTest {
    public static void main(String[] args) {
        check(ProtocolFrame.checksum("123456789".getBytes(StandardCharsets.US_ASCII)) == 0xbb3d);
        check(Arrays.equals(ProtocolFrame.acknowledgement(0x1234),
                new byte[]{(byte) 0xab, 0x10, 0, 0, 0, 0, 0x12, 0x34}));
        byte[] frame = ProtocolFrame.command(4, 12, new byte[]{1, 2, 3}, 0x1234);
        check(frame.length == 16 && frame[0] == (byte) 0xab && frame[3] == 8);
        check(frame[6] == 0x12 && frame[7] == 0x34);
        check(Arrays.equals(Arrays.copyOfRange(frame, 8, 16), new byte[]{4, 0, 12, 0, 3, 1, 2, 3}));
        check((((frame[4] & 0xff) << 8) | (frame[5] & 0xff))
                == ProtocolFrame.checksum(Arrays.copyOfRange(frame, 8, frame.length)));
        byte[] otherApp = ProtocolFrame.command(NotificationPayload.GROUP_NOTIFICATION,
            NotificationPayload.TYPE_OTHER_APP, NotificationPayload.legacy("Ana P.", "Hola"), 2);
        check((otherApp[8] & 0xff) == 4 && (otherApp[10] & 0xff) == 0xff);
        check((((otherApp[4] & 0xff) << 8) | (otherApp[5] & 0xff))
            == ProtocolFrame.checksum(Arrays.copyOfRange(otherApp, 8, otherApp.length)));
        check(NotificationRouting.teamsNotificationType(true) == NotificationPayload.TYPE_SMS);
        check(NotificationRouting.teamsNotificationType(false) == NotificationPayload.TYPE_WHATSAPP);
        byte[] sms = ProtocolFrame.command(NotificationPayload.GROUP_NOTIFICATION,
            NotificationRouting.teamsNotificationType(true), NotificationPayload.legacy("Ana P.", "Hola"), 3);
        check((sms[8] & 0xff) == 4 && (sms[10] & 0xff) == 4);
        WatchBattery battery = WatchBattery.fromResponse(new byte[]{2, 0, 28, 0, 3, 1, 4, 87});
        check(battery != null && battery.percentage == 87 && battery.charging);
        check(WatchBattery.fromResponse(new byte[]{2, 0, 28, 0, 3, 0, 4}) == null);
        check(WatchBattery.fromResponse(new byte[]{2, 0, 28, 0, 2, 0, 4, 87}) == null);
        check(WatchBattery.fromResponse(new byte[]{2, 0, 28, 0, 3, 0, 4, 101}) == null);
        check(WatchBattery.fromResponse(new byte[]{2, 0, 29, 0, 3, 0, 4, 87}) == null);
        byte[] findWatch = ProtocolFrame.command(2, 59, new byte[0], 4);
        check(Arrays.equals(Arrays.copyOfRange(findWatch, 8, findWatch.length), new byte[]{2, 0, 59, 0, 0}));
        WatchHealth health = WatchHealth.fromResponse(new byte[]{5, 0, 67, 0, 4, 72, 98, 80, 120});
        check(health != null && health.heartRate == 72 && health.oxygen == 98
            && health.diastolic == 80 && health.systolic == 120 && health.bodyTemperatureHundredths == null);
        health = WatchHealth.fromResponse(new byte[]{5, 0, 67, 0, 8, 72, 98, 80, 120,
            0x0d, 0x48, 0x0e, 0x4c});
        check(health != null && health.bodyTemperatureHundredths == 3660);
        health = WatchHealth.fromResponse(new byte[]{5, 0, 67, 0, 5, 72, 98, 80, 120, 10});
        check(health != null && health.bodyTemperatureHundredths == null);
        check(WatchHealth.fromResponse(new byte[]{5, 0, 67, 0, 4, 72, 98, 80}) == null);
        check(WatchHealth.fromResponse(new byte[]{5, 0, 67, 0, 3, 72, 98, 80, 120}) == null);
        check(WatchHealth.fromResponse(new byte[]{5, 0, 66, 0, 4, 72, 98, 80, 120}) == null);
        check(WatchAlarms.fromResponse(new byte[]{2, 0, 4, 0, 0}).isEmpty());
        java.util.List<WatchAlarms.Alarm> alarms = WatchAlarms.fromResponse(
            new byte[]{2, 0, 4, 0, 6, 5, 0, 0, 0x7b, 0x40, (byte) 0x81});
        check(alarms != null && alarms.size() == 1 && alarms.get(0).hour == 7
            && alarms.get(0).minute == 45 && alarms.get(0).enabled && alarms.get(0).repeat == 1);
        alarms = WatchAlarms.fromResponse(new byte[]{2, 0, 4, 0, 6, 5, 103, 62, 0x7b, 0x58, (byte) 0x83});
        check(alarms != null && alarms.get(0).id == 3 && alarms.get(0).year == 2025
            && alarms.get(0).month == 12 && alarms.get(0).day == 31
            && alarms.get(0).hour == 7 && alarms.get(0).minute == 45 && alarms.get(0).repeat == 3);
        check(WatchAlarms.fromResponse(new byte[]{2, 0, 4, 0, 2, 5, 0}) == null);
        check(WatchAlarms.fromResponse(new byte[]{2, 0, 4, 0, 1, 0}) == null);
        check(WatchSport.statusFromResponse(new byte[]{5, 0, 73, 0, 8, 0, 0, 0, 0, 0, 0, 2, 0}) == 2);
        check(WatchSport.statusFromResponse(new byte[]{5, 0, 73, 0, 8, 0, 0, 0, 0, 0, 0, 2}) == null);
        check(WatchSport.statusFromResponse(new byte[]{5, 0, 72, 0, 8, 0, 0, 0, 0, 0, 0, 2, 0}) == null);
        check(Arrays.equals(WatchWeather.testPayload(), new byte[]{20, 15, 25, 0, 6,
            'P', 'r', 'u', 'e', 'b', 'a', 0, 0, 0, 0, 0, 0}));
        byte[] weather = ProtocolFrame.command(WatchWeather.GROUP, WatchWeather.SET, WatchWeather.testPayload(), 5);
        check(weather[8] == 2 && weather[10] == 54 && weather[12] == 17);
        System.out.println("ProtocolFrame: OK");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }
}