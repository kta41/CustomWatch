package es.lifefit.watch;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NotificationRelayService extends NotificationListenerService {
    private final Set<String> activeAlarms = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable alarmPulse = new Runnable() {
        @Override
        public void run() {
            if (activeAlarms.isEmpty()
                    || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("phone_alarms", false)) {
                activeAlarms.clear();
                return;
            }
            WatchService.findWatch();
            handler.postDelayed(this, 10000);
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification notification : active) {
                if (isActiveAlarm(notification)) onNotificationPosted(notification);
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacks(alarmPulse);
        activeAlarms.clear();
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification posted) {
        Notification notification = posted.getNotification();
        if (notification == null) return;
        if (isActiveAlarm(posted)) {
            boolean firstAlarm = activeAlarms.isEmpty();
            if (activeAlarms.add(posted.getKey()) && firstAlarm) {
                handler.post(alarmPulse);
            }
            return;
        }
        removeAlarm(posted.getKey());
        String packageName = posted.getPackageName();
        boolean whatsapp = "com.whatsapp".equals(packageName) || "com.whatsapp.w4b".equals(packageName);
        boolean teams = "com.microsoft.teams".equals(packageName);
        if (getPackageName().equals(packageName) || !new NotificationSettings(this).shouldForward(packageName)) {
            return;
        }
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }
        Bundle extras = notification.extras;
        if (extras == null) {
            return;
        }
        if (whatsapp) {
            Boolean groupConversation = extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)
                    ? extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) : null;
            if (!NotificationRouting.isWhatsAppDirect(groupConversation)) {
                return;
            }
        }
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if ((text == null || text.length() == 0) && !whatsapp && !teams) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        if (teams) {
            boolean groupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
            boolean structuredMessage = false;
            boolean hasSender = false;
            Parcelable[] bundledMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (Build.VERSION.SDK_INT >= 30 && bundledMessages != null) {
                List<Notification.MessagingStyle.Message> messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundledMessages);
                if (!messages.isEmpty()) {
                    Notification.MessagingStyle.Message latest = messages.get(messages.size() - 1);
                    CharSequence sender = Build.VERSION.SDK_INT >= 28 && latest.getSenderPerson() != null
                            ? latest.getSenderPerson().getName() : latest.getSender();
                    if (sender != null && sender.length() > 0) {
                        title = sender;
                        hasSender = true;
                    }
                    if (latest.getText() != null) {
                        text = latest.getText();
                        structuredMessage = true;
                    }
                }
            }
            if (!structuredMessage && text != null) {
                int prefixLength = NotificationRouting.teamsSenderPrefixLength(text.toString(),
                        title == null ? null : title.toString(), groupConversation);
                if (prefixLength > 0) {
                    title = text.subSequence(0, prefixLength - 2);
                    text = text.subSequence(prefixLength, text.length());
                    hasSender = true;
                }
            }
            if (groupConversation && !hasSender) title = null;
            title = NotificationRouting.abbreviatedName(title == null ? null : title.toString());
        }
        if (text == null || text.length() == 0) {
            return;
        }
        int type = teams ? NotificationRouting.teamsNotificationType(
            getSharedPreferences("settings", MODE_PRIVATE).getBoolean("teams_sms_icon", true))
            : whatsapp ? NotificationPayload.TYPE_WHATSAPP : NotificationPayload.TYPE_SMS;
        String fallback = whatsapp ? "WhatsApp" : teams ? "Teams" : packageName;
        if (!whatsapp && !teams && (title == null || title.length() == 0)) {
            try {
                fallback = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)).toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            }
        }
        WatchService.sendNotification(type, title == null || title.length() == 0 ? fallback : title.toString(), text.toString());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification posted) {
        removeAlarm(posted.getKey());
    }

    private void removeAlarm(String key) {
        if (activeAlarms.remove(key) && activeAlarms.isEmpty()) {
            handler.removeCallbacks(alarmPulse);
        }
    }

    private boolean isActiveAlarm(StatusBarNotification posted) {
        Notification notification = posted.getNotification();
        return notification != null
                && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("phone_alarms", false)
                && NotificationRouting.isRingingAlarm(posted.getPackageName(), notification.getChannelId(),
                    notification.category, (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0,
                        (notification.flags & Notification.FLAG_INSISTENT) != 0,
                        notification.fullScreenIntent != null);
    }
}