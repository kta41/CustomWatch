import es.lifefit.watch.ProtocolFrame;
import es.lifefit.watch.NotificationPayload;
import es.lifefit.watch.NotificationRouting;
import es.lifefit.watch.WatchBattery;
import es.lifefit.watch.WatchHealth;
import es.lifefit.watch.WatchAlarms;
import es.lifefit.watch.WatchSport;
import es.lifefit.watch.WatchWeather;
import es.lifefit.watch.WatchMusic;
import es.lifefit.watch.WatchActivity;
import es.lifefit.watch.WatchSleepTransfer;
import es.lifefit.watch.WatchSleepCapabilities;
import es.lifefit.watch.WatchSleepHistory;
import es.lifefit.watch.WatchSleepSync;
import es.lifefit.watch.SleepMetrics;
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
        byte[] activityResponse = new byte[35];
        activityResponse[0] = 5;
        activityResponse[2] = 34;
        activityResponse[4] = 30;
        activityResponse[7] = 0x13;
        activityResponse[8] = (byte) 0x88;
        activityResponse[11] = 0x0b;
        activityResponse[12] = (byte) 0xb8;
        activityResponse[14] = 3;
        activityResponse[15] = (byte) 0xd0;
        activityResponse[16] = (byte) 0x90;
        activityResponse[20] = 80;
        activityResponse[24] = (byte) 248;
        WatchActivity activity = WatchActivity.fromResponse(activityResponse);
        check(activity != null && activity.steps == 5000 && activity.kilometers == 3.0 && activity.calories == 250.0
            && activity.deepSleepMinutes == 80 && activity.lightSleepMinutes == 248);
        activityResponse[19] = 1;
        activityResponse[23] = 1;
        activity = WatchActivity.fromResponse(activityResponse);
        check(activity != null && activity.deepSleepMinutes == 336 && activity.lightSleepMinutes == 504);
        activityResponse[17] = (byte) 0xff;
        activityResponse[18] = (byte) 0xff;
        activityResponse[19] = (byte) 0xff;
        activityResponse[20] = (byte) 0xff;
        activity = WatchActivity.fromResponse(activityResponse);
        check(activity != null && activity.deepSleepMinutes == 0xffffffffL);
        activityResponse[2] = 33;
        check(WatchActivity.fromResponse(activityResponse) == null);
        activityResponse[2] = 34;
        activityResponse[4] = 29;
        check(WatchActivity.fromResponse(activityResponse) == null);
        check(WatchActivity.fromResponse(Arrays.copyOf(activityResponse, 34)) == null);
        check(WatchActivity.fromResponse(null) == null);
        check(WatchActivity.fromResponse(new byte[]{5, 0, 34, 0, 0}) == null);
        WatchSleepTransfer transfer = new WatchSleepTransfer();
        check(transfer.accept(new byte[]{2, 0, 7, 0, 0}) == WatchSleepTransfer.Result.IGNORED);
        check(transfer.accept(new byte[]{5, 0, 7, 0, 0}) == WatchSleepTransfer.Result.STARTED);
        check(transfer.accept(new byte[]{5, 0, 48, 0, 2, 1, 2}) == WatchSleepTransfer.Result.PROGRESS);
        check(transfer.accept(new byte[]{5, 0, 48, 0, 1, 3}) == WatchSleepTransfer.Result.PROGRESS);
        check(transfer.accept(new byte[]{5, 0, 8, 0, 4, 0, 0, 0, 3}) == WatchSleepTransfer.Result.COMPLETE);
        check(Arrays.equals(transfer.completedData(), new byte[]{1, 2, 3}));
        transfer = new WatchSleepTransfer();
        check(transfer.accept(new byte[]{5, 0, 48, 0, 1, 1}) == WatchSleepTransfer.Result.ERROR);
        transfer = new WatchSleepTransfer();
        check(transfer.accept(new byte[]{5, 0, 7, 0, 0}) == WatchSleepTransfer.Result.STARTED);
        check(transfer.accept(new byte[]{5, 0, 48, 0, 1, 1}) == WatchSleepTransfer.Result.PROGRESS);
        check(transfer.accept(new byte[]{5, 0, 8, 0, 4, 0, 0, 0, 2}) == WatchSleepTransfer.Result.ERROR);
        transfer = new WatchSleepTransfer();
        check(transfer.accept(new byte[]{5, 0, 7, 0, 0}) == WatchSleepTransfer.Result.STARTED);
        check(transfer.accept(new byte[]{5, 0, 48, 0, 2, 1}) == WatchSleepTransfer.Result.ERROR);
        transfer = new WatchSleepTransfer();
        check(transfer.accept(new byte[]{5, 0, 7, 0, 0}) == WatchSleepTransfer.Result.STARTED);
        check(transfer.accept(new byte[]{5, 0, 8, 0, 4, 0, 0, 0, 0}) == WatchSleepTransfer.Result.COMPLETE);
        check(transfer.completedData().length == 0);
        transfer = new WatchSleepTransfer();
        check(transfer.accept(new byte[]{5, 0, 7, 0, 0}) == WatchSleepTransfer.Result.STARTED);
        check(transfer.accept(new byte[]{5, 0, 7, 0, 0}) == WatchSleepTransfer.Result.ERROR);
        check(transfer.accept(new byte[]{5, 0, 8, 0, 4, 0, 0, 0, 0}) == WatchSleepTransfer.Result.ERROR);
        boolean rejectedIncomplete = false;
        try {
            transfer.completedData();
        } catch (IllegalStateException expected) {
            rejectedIncomplete = true;
        }
        check(rejectedIncomplete);
        transfer = new WatchSleepTransfer();
        check(transfer.accept(new byte[]{5, 0, 7, 0, 0}) == WatchSleepTransfer.Result.STARTED);
        check(transfer.accept(new byte[]{5, 0, 48, 0, 1, 42}) == WatchSleepTransfer.Result.PROGRESS);
        check(transfer.accept(new byte[]{5, 0, 8, 0, 4, 0, 0, 1, 0}) == WatchSleepTransfer.Result.ERROR);
        byte[] capabilities = new byte[45];
        capabilities[0] = 2;
        capabilities[2] = 33;
        capabilities[4] = 40;
        capabilities[5] = 17;
        capabilities[6] = 38;
        check(Boolean.FALSE.equals(WatchSleepCapabilities.timestampedSamples(capabilities)));
        capabilities[43] = 0x10;
        check(Boolean.TRUE.equals(WatchSleepCapabilities.timestampedSamples(capabilities)));
        capabilities[6] = 36;
        check(WatchSleepCapabilities.timestampedSamples(capabilities) == null);
        capabilities[6] = 39;
        check(WatchSleepCapabilities.timestampedSamples(capabilities) == null);
        capabilities[6] = 38;
        capabilities[5] = 18;
        check(WatchSleepCapabilities.timestampedSamples(capabilities) == null);
        check(WatchSleepCapabilities.timestampedSamples(null) == null);
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        byte[] sleepBlock = {0, 3, 53, 94, 5, 40, 0, 5, 1, 2, 3};
        java.util.List<WatchSleepHistory.Sample> samples = WatchSleepHistory.decode(sleepBlock, false, utc);
        check(samples.size() == 3 && samples.get(0).date.equals("2026-10-31"));
        check(samples.get(0).state == 1 && samples.get(1).state == 2 && samples.get(2).state == 3);
        check(samples.get(0).end - samples.get(0).start == 300000L);
        check(samples.get(1).end - samples.get(0).end == 300000L);
        byte[] timestampedBlock = {0, 2, 53, 94, 5, 40, 0, 0,
            53, 94, 5, 45, 1, 53, 94, 5, 50, 0};
        samples = WatchSleepHistory.decode(timestampedBlock, true, utc);
        check(samples.size() == 2 && samples.get(0).state == 1 && samples.get(1).state == 3);
        check(samples.get(1).start == samples.get(0).end && samples.get(1).end - samples.get(1).start == 300000L);
        SleepMetrics metrics = SleepMetrics.from("2026-10-31", samples);
        check(metrics != null && metrics.sleepMillis() == 300000L && metrics.deepMillis == 300000L);
        check(metrics.start == samples.get(0).start && metrics.end == samples.get(0).end);
        check(SleepMetrics.from("2026-10-30", samples) == null);
        java.util.List<WatchSleepHistory.Sample> overlapping = new java.util.ArrayList<>(samples);
        overlapping.add(samples.get(0));
        SleepMetrics deduplicated = SleepMetrics.from("2026-10-31", overlapping);
        check(deduplicated.overlapping && deduplicated.sleepMillis() == 300000L);
        byte[] gaps = {0, 3, 53, 94, 5, 40, 0, 10, 1, 3, 2};
        java.util.List<WatchSleepHistory.Sample> gapSamples = WatchSleepHistory.decode(gaps, false, utc);
        SleepMetrics gapMetrics = SleepMetrics.from("2026-10-31", gapSamples);
        check(gapMetrics != null && gapMetrics.deepMillis == 300000L && gapMetrics.lightMillis == 300000L
            && gapMetrics.awakeMillis == 300000L && gapMetrics.end - gapMetrics.start == 1500000L);
        String summary = WatchSleepHistory.summarize(Arrays.asList(samples, samples));
        check(summary.contains("2026-10-31") && summary.contains("0 h 05 min"));
        check(!summary.contains("0 h 10 min"));
        boolean rejectedTruncated = false;
        try {
            WatchSleepHistory.decode(Arrays.copyOf(sleepBlock, sleepBlock.length - 1), false, utc);
        } catch (IllegalArgumentException expected) {
            rejectedTruncated = true;
        }
        check(rejectedTruncated);
        capabilities[5] = 17;
        WatchSleepSync sync = new WatchSleepSync();
        check(sync.accept(1, capabilities) == WatchSleepSync.Result.REQUEST_METADATA);
        check(sync.timestamped());
        check(sync.accept(2, new byte[]{5, 0, 6, 0, 4, 0, 0, 0, 1}) == WatchSleepSync.Result.REQUEST_SLEEP);
        check(sync.accept(3, new byte[]{5, 0, 7, 0, 0}) == WatchSleepSync.Result.PROGRESS);
        check(sync.accept(4, new byte[]{5, 0, 48, 0, 1, 42}) == WatchSleepSync.Result.PROGRESS);
        check(sync.accept(4, new byte[]{5, 0, 48, 0, 1, 42}) == WatchSleepSync.Result.DUPLICATE);
        check(sync.accept(5, new byte[]{5, 0, 8, 0, 4, 0, 0, 0, 1}) == WatchSleepSync.Result.SAVE);
        check(Arrays.equals(sync.completedData(), new byte[]{42}));
        sync = new WatchSleepSync();
        check(sync.accept(1, capabilities) == WatchSleepSync.Result.REQUEST_METADATA);
        check(sync.accept(2, new byte[]{5, 0, 6, 0, 4, 0, 0, 0, 0}) == WatchSleepSync.Result.EMPTY);
        sync = new WatchSleepSync();
        check(sync.accept(1, capabilities) == WatchSleepSync.Result.REQUEST_METADATA);
        check(sync.accept(2, new byte[]{5, 0, 6, 0, 4, 0, 0, 0, 1}) == WatchSleepSync.Result.REQUEST_SLEEP);
        check(sync.accept(3, new byte[]{5, 0, 7, 0, 0}) == WatchSleepSync.Result.PROGRESS);
        check(sync.accept(3, new byte[]{5, 0, 48, 0, 1, 42}) == WatchSleepSync.Result.ERROR);
        check(Arrays.equals(WatchWeather.testPayload(), new byte[]{20, 15, 25, 0, 6,
            'P', 'r', 'u', 'e', 'b', 'a', 0, 0, 0, 0, 0, 0}));
        byte[] weather = ProtocolFrame.command(WatchWeather.GROUP, WatchWeather.SET, WatchWeather.testPayload(), 5);
        check(weather[8] == 2 && weather[10] == 54 && weather[12] == 17);
        java.util.Calendar time = new java.util.GregorianCalendar(2025, 0, 2, 3, 4, 5);
        check(Arrays.equals(WatchWeather.extendedTestPayload(time), new byte[]{20, 15, 25, 0, 6,
            'P', 'r', 'u', 'e', 'b', 'a', 100, 68, 49, 5, 0, 0, 0, 0, 0, 0, 0}));
        byte[] music = WatchMusic.testPayload();
        check(music[0] == 18 && music[1] == 17 && music[2] == 0 && music[3] == 2
            && (music[4] & 0xff) == 191 && (music[5] & 0xff) == 32);
        check(new String(music, 6, 18, StandardCharsets.UTF_8).equals("Prueba CustomWatch"));
        check(new String(music, 24, 17, StandardCharsets.UTF_8).equals("Artista de prueba"));
        byte[] musicFrame = ProtocolFrame.command(WatchMusic.GROUP, WatchMusic.SET_INFO, music, 6);
        check(musicFrame[8] == 2 && (musicFrame[10] & 0xff) == 148);
        for (int control = 0; control <= 6; control++) {
            check(WatchMusic.controlFromResponse(new byte[]{7, 0, 3, 0, 1, (byte) control}) == control);
        }
        check(WatchMusic.controlFromResponse(new byte[]{7, 0, 3, 0, 1, 7}) == -1);
        check(WatchMusic.controlFromResponse(new byte[]{7, 0, 3, 0, 2, 1}) == -1);
        check(WatchMusic.controlFromResponse(new byte[]{7, 0, 4, 0, 1, 1}) == -1);
        System.out.println("ProtocolFrame: OK");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }
}