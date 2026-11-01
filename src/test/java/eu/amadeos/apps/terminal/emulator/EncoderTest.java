package eu.amadeos.apps.terminal.emulator;

import eu.amadeos.apps.terminal.emulator.KeyEncoder.Key;
import eu.amadeos.apps.terminal.emulator.TerminalEmulator.MouseEncoding;
import eu.amadeos.apps.terminal.emulator.TerminalEmulator.MouseMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EncoderTest {
    private static final String ESC = "\u001b";

    @Test
    void cursorKeysNormalAndApplication() {
        assertEquals(ESC + "[A", KeyEncoder.key(Key.UP, 0, false, false));
        assertEquals(ESC + "OA", KeyEncoder.key(Key.UP, 0, true, false));
        assertEquals(ESC + "[D", KeyEncoder.key(Key.LEFT, 0, false, false));
    }

    @Test
    void cursorKeysWithModifiers() {
        assertEquals(ESC + "[1;5C", KeyEncoder.key(Key.RIGHT, KeyEncoder.CTRL, false, false));
        assertEquals(ESC + "[1;2A", KeyEncoder.key(Key.UP, KeyEncoder.SHIFT, true, false));
        assertEquals(ESC + "[1;3D", KeyEncoder.key(Key.LEFT, KeyEncoder.ALT, false, false));
    }

    @Test
    void homeEndAndEditingKeys() {
        assertEquals(ESC + "[H", KeyEncoder.key(Key.HOME, 0, false, false));
        assertEquals(ESC + "OF", KeyEncoder.key(Key.END, 0, true, false));
        assertEquals(ESC + "[3~", KeyEncoder.key(Key.DELETE, 0, false, false));
        assertEquals(ESC + "[5;5~", KeyEncoder.key(Key.PAGE_UP, KeyEncoder.CTRL, false, false));
        assertEquals(ESC + "[2~", KeyEncoder.key(Key.INSERT, 0, false, false));
    }

    @Test
    void functionKeys() {
        assertEquals(ESC + "OP", KeyEncoder.key(Key.F1, 0, false, false));
        assertEquals(ESC + "[15~", KeyEncoder.key(Key.F5, 0, false, false));
        assertEquals(ESC + "[24~", KeyEncoder.key(Key.F12, 0, false, false));
        assertEquals(ESC + "[1;2P", KeyEncoder.key(Key.F1, KeyEncoder.SHIFT, false, false));
        assertEquals(ESC + "[21;5~", KeyEncoder.key(Key.F10, KeyEncoder.CTRL, false, false));
    }

    @Test
    void backspaceTabEnterEscape() {
        assertEquals("\u007f", KeyEncoder.key(Key.BACKSPACE, 0, false, false));
        assertEquals("\b", KeyEncoder.key(Key.BACKSPACE, KeyEncoder.CTRL, false, false));
        assertEquals(ESC + "\u007f", KeyEncoder.key(Key.BACKSPACE, KeyEncoder.ALT, false, false));
        assertEquals("\t", KeyEncoder.key(Key.TAB, 0, false, false));
        assertEquals(ESC + "[Z", KeyEncoder.key(Key.TAB, KeyEncoder.SHIFT, false, false));
        assertEquals("\r", KeyEncoder.key(Key.ENTER, 0, false, false));
        assertEquals(ESC + "OM", KeyEncoder.key(Key.KEYPAD_ENTER, 0, false, true));
        assertEquals(ESC, KeyEncoder.key(Key.ESCAPE, 0, false, false));
    }

    @Test
    void controlCharacters() {
        assertEquals("\u0003", KeyEncoder.withModifiers('c', KeyEncoder.CTRL));
        assertEquals("\u0003", KeyEncoder.withModifiers('C', KeyEncoder.CTRL));
        assertEquals("\u0000", KeyEncoder.withModifiers(' ', KeyEncoder.CTRL));
        assertEquals("\u001b", KeyEncoder.withModifiers('[', KeyEncoder.CTRL));
        assertEquals("\u001f", KeyEncoder.withModifiers('_', KeyEncoder.CTRL));
        assertEquals("\u001c", KeyEncoder.withModifiers('\\', KeyEncoder.CTRL));
        assertNull(KeyEncoder.withModifiers('ä', KeyEncoder.CTRL));
    }

    @Test
    void altPutsEscapeInFront() {
        assertEquals(ESC + "b", KeyEncoder.withModifiers('b', KeyEncoder.ALT));
        assertEquals(ESC + ".", KeyEncoder.withModifiers('.', KeyEncoder.ALT));
        assertEquals(ESC + "\u0012", KeyEncoder.withModifiers('r', KeyEncoder.ALT | KeyEncoder.CTRL));
    }

    @Test
    void focusReports() {
        assertEquals(ESC + "[I", KeyEncoder.focus(true));
        assertEquals(ESC + "[O", KeyEncoder.focus(false));
    }

    @Test
    void pasteTurnsLineBreaksIntoReturns() {
        assertEquals("a\rb\r", KeyEncoder.paste("a\nb\r\n", false));
    }

    @Test
    void bracketedPasteFramesTextAndRemovesEndMarker() {
        assertEquals(ESC + "[200~ls\rrm x" + ESC + "[201~", KeyEncoder.paste("ls\nrm" + ESC + "[201~ x", true));
    }

    @Test
    void mouseX10Encoding() {
        assertEquals(ESC + "[M !!", MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.DEFAULT,
                MouseEncoder.Action.PRESS, MouseEncoder.LEFT, 0, 0, 0));
        assertEquals(ESC + "[M#!!", MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.DEFAULT,
                MouseEncoder.Action.RELEASE, MouseEncoder.LEFT, 0, 0, 0));
    }

    @Test
    void mouseSgrEncoding() {
        assertEquals(ESC + "[<0;5;3M", MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.SGR,
                MouseEncoder.Action.PRESS, MouseEncoder.LEFT, 4, 2, 0));
        assertEquals(ESC + "[<2;5;3m", MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.SGR,
                MouseEncoder.Action.RELEASE, MouseEncoder.RIGHT, 4, 2, 0));
        assertEquals(ESC + "[<64;1;1M", MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.SGR,
                MouseEncoder.Action.PRESS, MouseEncoder.WHEEL_UP, 0, 0, 0));
        assertEquals(ESC + "[<20;1;1M", MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.SGR,
                MouseEncoder.Action.PRESS, MouseEncoder.LEFT, 0, 0, KeyEncoder.CTRL | KeyEncoder.SHIFT));
    }

    @Test
    void mouseModesFilterEvents() {
        assertNull(MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.SGR, MouseEncoder.Action.DRAG,
                MouseEncoder.LEFT, 1, 1, 0));
        assertEquals(ESC + "[<32;2;2M", MouseEncoder.encode(MouseMode.BUTTON, MouseEncoding.SGR,
                MouseEncoder.Action.DRAG, MouseEncoder.LEFT, 1, 1, 0));
        assertNull(MouseEncoder.encode(MouseMode.BUTTON, MouseEncoding.SGR, MouseEncoder.Action.MOVE, 0, 1, 1, 0));
        assertEquals(ESC + "[<35;2;2M", MouseEncoder.encode(MouseMode.ANY, MouseEncoding.SGR,
                MouseEncoder.Action.MOVE, 0, 1, 1, 0));
        assertNull(MouseEncoder.encode(MouseMode.X10, MouseEncoding.DEFAULT, MouseEncoder.Action.RELEASE,
                MouseEncoder.LEFT, 1, 1, 0));
        assertNull(MouseEncoder.encode(MouseMode.NONE, MouseEncoding.SGR, MouseEncoder.Action.PRESS, 0, 1, 1, 0));
    }

    @Test
    void mouseUrxvtAndLimits() {
        assertEquals(ESC + "[32;10;20M", MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.URXVT,
                MouseEncoder.Action.PRESS, MouseEncoder.LEFT, 9, 19, 0));
        assertNull(MouseEncoder.encode(MouseMode.NORMAL, MouseEncoding.DEFAULT, MouseEncoder.Action.PRESS,
                MouseEncoder.LEFT, 300, 1, 0));
    }
}
