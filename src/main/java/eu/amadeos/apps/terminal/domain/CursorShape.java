package eu.amadeos.apps.terminal.domain;

import java.util.Arrays;
import java.util.Optional;

/** The cursor's look; a program can change it with DECSCUSR ({@code CSI n SP q}). */
public enum CursorShape {
    BLOCK, UNDERLINE, BAR;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public String textKey() {
        return "cursor." + id();
    }

    public static Optional<CursorShape> byId(String id) {
        return Arrays.stream(values()).filter(s -> s.id().equals(id)).findFirst();
    }

    /** The shape a program asked for with DECSCUSR (0 = the user's choice). */
    public static CursorShape fromDecscusr(int style, CursorShape user) {
        return switch (style) {
            case 1, 2 -> BLOCK;
            case 3, 4 -> UNDERLINE;
            case 5, 6 -> BAR;
            default -> user;
        };
    }

    /** Whether the cursor blinks for a DECSCUSR value (0 = the user's choice). */
    public static boolean blinksFor(int style, boolean user) {
        return style == 0 ? user : style % 2 == 1;
    }
}
