import es.lifefit.watch.NotificationPayload;
import es.lifefit.watch.NotificationRouting;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NotificationPayloadTest {
    public static void main(String[] args) {
        check(NotificationRouting.isWhatsAppDirect(false));
        check(!NotificationRouting.isWhatsAppDirect(true));
        check(!NotificationRouting.isWhatsAppDirect(null));
        check(NotificationRouting.abbreviatedName("María García López").equals("María G."));
        check(NotificationRouting.abbreviatedName("  Ana   pérez  ").equals("Ana P."));
        check(NotificationRouting.abbreviatedName("Ana").equals("Ana"));
        check(NotificationRouting.abbreviatedName(null).equals("Teams"));
        check(NotificationRouting.teamsSenderPrefixLength("Ana Pérez: Hola", "Grupo", true) == 11);
        check(NotificationRouting.teamsSenderPrefixLength("Ana Pérez: Hola", "Ana Pérez", false) == 11);
        check(NotificationRouting.teamsSenderPrefixLength("Ana Pérez: Hola", "Grupo", false) == 11);
        check(NotificationRouting.teamsSenderPrefixLength("Hola: qué tal", "Ana Pérez", false) == 0);
        check(NotificationRouting.teamsSenderPrefixLength("¿Qué tal?: Hola", "Grupo", true) == 0);
        byte[] legacy = NotificationPayload.legacy("LifeFit", "Prueba de aviso");
        check(legacy.length == 46);
        check(new String(legacy, 0, 7, StandardCharsets.UTF_8).equals("LifeFit"));
        check(Arrays.equals(Arrays.copyOfRange(legacy, 7, 20), new byte[13]));
        check(legacy[20] == 0 && legacy[21] == 24);
        check(new String(legacy, 22, 24, StandardCharsets.UTF_8).equals("LifeFit: Prueba de aviso"));
        byte[] noSender = NotificationPayload.legacy(null, "Hola");
        check(noSender[20] == 0 && noSender[21] == 4);
        check(new String(noSender, 22, 4, StandardCharsets.UTF_8).equals("Hola"));
        byte[] legacyUnicode = NotificationPayload.legacy("a".repeat(19) + "\u00e9", "b".repeat(239) + "\u00e9");
        check(legacyUnicode[19] == 0 && legacyUnicode[20] == 0 && (legacyUnicode[21] & 0xff) == 240);
        check(new String(legacyUnicode, 22, 240, StandardCharsets.UTF_8).startsWith("a".repeat(19) + ": "));
        check(Arrays.equals(NotificationPayload.extended(null, null), new byte[]{0, 0, 0}));
        check(Arrays.equals(NotificationPayload.extended("Ana", "Hola"),
                new byte[]{3, 'A', 'n', 'a', 0, 4, 'H', 'o', 'l', 'a'}));
        byte[] truncated = NotificationPayload.extended("a".repeat(63) + "\u00e9", "b".repeat(300));
        check((truncated[0] & 0xff) == 63);
        check((((truncated[64] & 0xff) << 8) | (truncated[65] & 0xff)) == 177);
        check(truncated.length == 243);
        check(new String(truncated, 1, 63, StandardCharsets.UTF_8).equals("a".repeat(63)));
        check(NotificationPayload.GROUP_NOTIFICATION == 4 && NotificationPayload.TYPE_WHATSAPP == 12);
        System.out.println("NotificationPayload: OK");
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }
}