package es.lifefit.watch;

import java.util.Locale;

public final class NotificationRouting {
    private NotificationRouting() {
    }

    public static boolean isAllowed(boolean relayEnabled, boolean appEnabled) {
        return relayEnabled && appEnabled;
    }

    public static boolean isWhatsAppDirect(Boolean groupConversation) {
        return Boolean.FALSE.equals(groupConversation);
    }

    public static boolean isRingingAlarm(String packageName, String channel, String category,
            boolean ongoing, boolean insistent, boolean fullScreen) {
        boolean xiaomiAlarm = "com.android.deskclock".equals(packageName)
                && "channel_id_deskclock_alarm".equals(channel) && ongoing && fullScreen;
        return xiaomiAlarm || ("alarm".equals(category) && (insistent || fullScreen));
    }

    public static int teamsNotificationType(boolean smsIcon) {
        return smsIcon ? NotificationPayload.TYPE_SMS : NotificationPayload.TYPE_WHATSAPP;
    }

    public static int teamsSenderPrefixLength(String text, String title, boolean groupConversation) {
        int separator = text.indexOf(": ");
        if (separator <= 0 || separator > 80) {
            return 0;
        }
        String prefix = text.substring(0, separator);
        if (prefix.equals(title) || ((groupConversation || prefix.contains(" ")) && prefix.matches(
                "(?U)[\\p{L}\\p{M}][\\p{L}\\p{M}'\\u2019.-]*(?:\\s+[\\p{L}\\p{M}][\\p{L}\\p{M}'\\u2019.-]*){0,5}"))) {
            return separator + 2;
        }
        return 0;
    }

    public static String abbreviatedName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Teams";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        int firstCodePoint = parts[1].codePointAt(0);
        String initial = new String(Character.toChars(firstCodePoint)).toUpperCase(Locale.ROOT);
        return parts[0] + " " + initial + ".";
    }
}