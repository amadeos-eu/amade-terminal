package eu.amadeos.apps.terminal.emulator;

/** What the emulator tells the window. All calls come from the thread that feeds the emulator. */
public interface TerminalListener {
    /** Bytes the terminal answers to the program (cursor position, device attributes, colours). */
    default void respond(String text) {
    }

    default void bell() {
    }

    /** The program set the window title (OSC 0 or 2). */
    default void titleChanged(String title) {
    }

    /** The shell told the current folder (OSC 7). */
    default void directoryChanged(String path) {
    }

    /** The program put text on the clipboard (OSC 52). */
    default void clipboardSet(String text) {
    }

    /** The colour (0xRRGGBB) of a palette index 0-15 or of {@link #FOREGROUND}, {@link #BACKGROUND}, {@link #CURSOR}. */
    default int color(int which) {
        return 0;
    }

    int FOREGROUND = -1;
    int BACKGROUND = -2;
    int CURSOR = -3;
}
