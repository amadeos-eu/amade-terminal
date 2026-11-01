package eu.amadeos.apps.terminal.emulator;

import eu.amadeos.apps.terminal.emulator.TerminalEmulator.MouseEncoding;
import eu.amadeos.apps.terminal.emulator.TerminalEmulator.MouseMode;

/** Mouse events for programs that asked for them (htop, mc, vim with {@code mouse=a}). */
public final class MouseEncoder {
    public static final int LEFT = 0;
    public static final int MIDDLE = 1;
    public static final int RIGHT = 2;
    public static final int WHEEL_UP = 64;
    public static final int WHEEL_DOWN = 65;

    public enum Action { PRESS, RELEASE, DRAG, MOVE }

    private MouseEncoder() {
    }

    /**
     * The report for an event at cell {@code col}, {@code row} (0-based), or null if the mode does not report it.
     * {@code mods} as in {@link KeyEncoder}.
     */
    public static String encode(MouseMode mode, MouseEncoding encoding, Action action, int button, int col, int row,
                                int mods) {
        if (mode == MouseMode.NONE) return null;
        boolean wheel = button >= 64;
        switch (action) {
            case RELEASE -> {
                if (mode == MouseMode.X10 || wheel) return null;
            }
            case DRAG -> {
                if (mode != MouseMode.BUTTON && mode != MouseMode.ANY) return null;
            }
            case MOVE -> {
                if (mode != MouseMode.ANY) return null;
            }
            default -> {
            }
        }
        int code = button;
        if (action == MouseEncoder.Action.MOVE) code = 3 + 32;
        else if (action == MouseEncoder.Action.DRAG) code = button + 32;
        if (mode != MouseMode.X10) {
            if ((mods & KeyEncoder.SHIFT) != 0) code += 4;
            if ((mods & KeyEncoder.ALT) != 0) code += 8;
            if ((mods & KeyEncoder.CTRL) != 0) code += 16;
        }
        int x = col + 1, y = row + 1;
        if (encoding == MouseEncoding.SGR) {
            return "\u001b[<" + code + ";" + x + ";" + y + (action == MouseEncoder.Action.RELEASE ? "m" : "M");
        }
        if (action == MouseEncoder.Action.RELEASE) code = 3 | (code & ~3 & ~64);
        if (encoding == MouseEncoding.URXVT) {
            return "\u001b[" + (code + 32) + ";" + x + ";" + y + "M";
        }
        if (encoding == MouseEncoding.UTF8) {
            if (x > 2015 || y > 2015) return null;
            return "\u001b[M" + (char) (code + 32) + (char) (x + 32) + (char) (y + 32);
        }
        if (x > 223 || y > 223) return null;
        return "\u001b[M" + (char) (code + 32) + (char) (x + 32) + (char) (y + 32);
    }
}
