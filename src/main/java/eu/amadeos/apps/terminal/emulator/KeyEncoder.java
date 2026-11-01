package eu.amadeos.apps.terminal.emulator;

/**
 * What a key sends to the program, in xterm's encoding: cursor keys in normal or application mode, function
 * keys, modifiers as {@code ;m} parameter (1 + Shift 1 + Alt 2 + Ctrl 4), Ctrl with a letter as control
 * character, Alt as an ESC in front.
 */
public final class KeyEncoder {
    /** Keys that are not characters. */
    public enum Key {
        UP, DOWN, RIGHT, LEFT, HOME, END, INSERT, DELETE, PAGE_UP, PAGE_DOWN,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
        BACKSPACE, TAB, ENTER, KEYPAD_ENTER, ESCAPE
    }

    public static final int SHIFT = 1;
    public static final int ALT = 2;
    public static final int CTRL = 4;

    private static final String ESC = "\u001b";

    private KeyEncoder() {
    }

    /** The bytes (as text) for a special key, or null if it sends nothing. */
    // --8<-- [start:keys]
    public static String key(Key key, int mods, boolean appCursor, boolean appKeypad) {
        int m = 1 + mods;
        return switch (key) {
            case UP -> cursor('A', mods, appCursor);
            case DOWN -> cursor('B', mods, appCursor);
            case RIGHT -> cursor('C', mods, appCursor);
            case LEFT -> cursor('D', mods, appCursor);
            case HOME -> cursor('H', mods, appCursor);
            case END -> cursor('F', mods, appCursor);
            case INSERT -> tilde(2, mods);
            case DELETE -> tilde(3, mods);
            case PAGE_UP -> tilde(5, mods);
            case PAGE_DOWN -> tilde(6, mods);
            case F1 -> ss3('P', mods);
            case F2 -> ss3('Q', mods);
            case F3 -> ss3('R', mods);
            case F4 -> ss3('S', mods);
            case F5 -> tilde(15, mods);
            case F6 -> tilde(17, mods);
            case F7 -> tilde(18, mods);
            case F8 -> tilde(19, mods);
            case F9 -> tilde(20, mods);
            case F10 -> tilde(21, mods);
            case F11 -> tilde(23, mods);
            case F12 -> tilde(24, mods);
            case BACKSPACE -> ((mods & ALT) != 0 ? ESC : "") + ((mods & CTRL) != 0 ? "\b" : "\u007f");
    // --8<-- [end:keys]
            case TAB -> (mods & SHIFT) != 0 ? ESC + "[Z" : ((mods & ALT) != 0 ? ESC : "") + "\t";
            case ENTER -> ((mods & ALT) != 0 ? ESC : "") + "\r";
            case KEYPAD_ENTER -> appKeypad ? ESC + "OM" : ((mods & ALT) != 0 ? ESC : "") + "\r";
            case ESCAPE -> ((mods & ALT) != 0 ? ESC : "") + ESC;
        };
    }

    private static String cursor(char c, int mods, boolean app) {
        if (mods != 0) return ESC + "[1;" + (1 + mods) + c;
        return ESC + (app ? "O" : "[") + c;
    }

    private static String ss3(char c, int mods) {
        if (mods != 0) return ESC + "[1;" + (1 + mods) + c;
        return ESC + "O" + c;
    }

    private static String tilde(int n, int mods) {
        if (mods != 0) return ESC + "[" + n + ";" + (1 + mods) + "~";
        return ESC + "[" + n + "~";
    }

    /**
     * A character key pressed together with Ctrl and/or Alt: Ctrl makes it a control character (Ctrl+A is 0x01,
     * Ctrl+Space and Ctrl+2 are NUL, Ctrl+[ is ESC ...), Alt puts ESC in front. Returns null when the
     * combination has no meaning (the key then sends nothing).
     */
    public static String withModifiers(int codePoint, int mods) {
        String base;
        if ((mods & CTRL) != 0) {
            int c = control(codePoint);
            if (c < 0) return null;
            base = Character.toString(c);
        } else {
            base = Character.toString(codePoint);
        }
        return (mods & ALT) != 0 ? ESC + base : base;
    }

    /** The control character for Ctrl with a key, or -1. */
    static int control(int c) {
        if (c >= 'a' && c <= 'z') return c - 'a' + 1;
        if (c >= '@' && c <= '_') return c - '@';
        return switch (c) {
            case ' ', '2', '`' -> 0;
            case '3' -> 0x1b;
            case '4' -> 0x1c;
            case '5' -> 0x1d;
            case '6', '~' -> 0x1e;
            case '7', '/', '-' -> 0x1f;
            case '8', '?' -> 0x7f;
            default -> -1;
        };
    }

    /** Focus in and out ({@code CSI I}, {@code CSI O}) for programs that asked with mode 1004. */
    public static String focus(boolean gained) {
        return ESC + (gained ? "[I" : "[O");
    }

    /**
     * Pasted text: line breaks become CR (what Enter sends); with bracketed paste the text is framed by
     * {@code CSI 200~} and {@code CSI 201~}, and an end marker inside the text is removed so that pasted text
     * can never end the paste early.
     */
    // --8<-- [start:paste]
    public static String paste(String text, boolean bracketed) {
        String t = text.replace("\r\n", "\r").replace('\n', '\r');
        if (!bracketed) return t;
        t = t.replace(ESC + "[201~", "").replace(ESC + "[200~", "");
        return ESC + "[200~" + t + ESC + "[201~";
    }
    // --8<-- [end:paste]
}
