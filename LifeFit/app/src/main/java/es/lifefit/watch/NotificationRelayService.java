package es.lifefit.watch;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.List;

public class NotificationRelayService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification posted) {
        String packageName = posted.getPackageName();
        boolean whatsapp = "com.whatsapp".equals(packageName) || "com.whatsapp.w4b".equals(packageName);
        boolean teams = "com.microsoft.teams".equals(packageName);
        if ((!whatsapp || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("whatsapp", false))
            && (!teams || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("teams", false))) {
            return;
        }
        Notification notification = posted.getNotification();
        if (notification == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
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
            : NotificationPayload.TYPE_WHATSAPP;
        WatchService.sendNotification(type, title == null ? "WhatsApp" : title.toString(), text.toString());
    }
}