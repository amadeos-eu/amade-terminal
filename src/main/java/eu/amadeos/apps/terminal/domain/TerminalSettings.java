package eu.amadeos.apps.terminal.domain;

/**
 * The choices of the Preferences dialog.
 *
 * @param command        a command to start instead of the user's shell; empty for the shell
 * @param scrollback     lines kept above the screen; {@link #UNLIMITED} for (almost) no limit
 * @param pasteWarning   ask before pasting several lines into a program that does not use bracketed paste
 * @param bellSound      the bell beeps
 * @param bellFlash      the bell flashes the terminal
 * @param bellNotify     a bell in a tab you are not looking at shows a notification
 * @param confirmClose   ask before closing a tab or window where a program still runs
 * @param allowClipboard programs may put text on the clipboard (OSC 52); reading it is never allowed
 */
public record TerminalSettings(ColorScheme scheme, String fontFamily, int fontSize, CursorShape cursor,
                               boolean cursorBlink, int columns, int rows, int scrollback, boolean brightBold,
                               String command, boolean loginShell, boolean pasteWarning, boolean bellSound,
                               boolean bellFlash, boolean bellNotify, boolean confirmClose, boolean allowClipboard) {
    public static final int UNLIMITED = 1_000_000;
    public static final int MIN_FONT = 6;
    public static final int MAX_FONT = 48;
    public static final String DEFAULT_FONT = "DejaVu Sans Mono";

    public static final TerminalSettings DEFAULTS = new TerminalSettings(ColorScheme.NIGHT, DEFAULT_FONT, 13,
            CursorShape.BLOCK, true, 84, 24, 10_000, true, "", false, true, false, true, true, true, true);

    public TerminalSettings {
        fontSize = Math.max(MIN_FONT, Math.min(MAX_FONT, fontSize));
        columns = Math.max(20, Math.min(500, columns));
        rows = Math.max(5, Math.min(200, rows));
        scrollback = Math.max(0, Math.min(UNLIMITED, scrollback));
        if (fontFamily == null || fontFamily.isBlank()) fontFamily = DEFAULT_FONT;
        if (command == null) command = "";
    }

    public TerminalSettings withFontSize(int size) {
        return new TerminalSettings(scheme, fontFamily, size, cursor, cursorBlink, columns, rows, scrollback,
                brightBold, command, loginShell, pasteWarning, bellSound, bellFlash, bellNotify, confirmClose,
                allowClipboard);
    }

    public TerminalSettings withPasteWarning(boolean on) {
        return new TerminalSettings(scheme, fontFamily, fontSize, cursor, cursorBlink, columns, rows, scrollback,
                brightBold, command, loginShell, on, bellSound, bellFlash, bellNotify, confirmClose, allowClipboard);
    }

    public boolean unlimitedScrollback() {
        return scrollback >= UNLIMITED;
    }
}
