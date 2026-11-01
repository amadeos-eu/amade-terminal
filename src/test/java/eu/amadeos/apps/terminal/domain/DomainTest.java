package eu.amadeos.apps.terminal.domain;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainTest {
    @Test
    void parsesExecuteWithArguments() {
        LaunchOptions o = LaunchOptions.parse(new String[]{"-T", "Root Shell", "-e", "sudo", "sh"});
        assertEquals(List.of("sudo", "sh"), o.command());
        assertEquals("Root Shell", o.title());
    }

    @Test
    void parsesSingleCommandStringThroughShell() {
        LaunchOptions o = LaunchOptions.parse(new String[]{"-e", "ls -l /tmp"});
        assertEquals(List.of("/bin/sh", "-c", "ls -l /tmp"), o.command());
    }

    @Test
    void parsesWorkingDirectoryHoldAndVersion() {
        LaunchOptions o = LaunchOptions.parse(new String[]{"--working-directory", "/tmp", "--hold"});
        assertEquals(Path.of("/tmp"), o.directory());
        assertTrue(o.hold());
        assertEquals(Path.of("/srv"), LaunchOptions.parse(new String[]{"--workdir=/srv"}).directory());
        assertTrue(LaunchOptions.parse(new String[]{"--version"}).version());
        assertEquals(List.of("/bin/sh", "-c", "top"), LaunchOptions.parse(new String[]{"--command=top"}).command());
    }

    @Test
    void doubleDashTakesTheRest() {
        assertEquals(List.of("vim", "-T", "x"), LaunchOptions.parse(new String[]{"--", "vim", "-T", "x"}).command());
        assertTrue(LaunchOptions.parse(new String[]{}).command().isEmpty());
    }

    @Test
    void commandPrefersCommandLineThenPreferencesThenShell() {
        TerminalSettings s = TerminalSettings.DEFAULTS;
        Map<String, String> env = Map.of("SHELL", "/bin/sh", "USER", "x");
        assertEquals(List.of("htop"), ShellCommand.command(new LaunchOptions(List.of("htop"), null, null, false,
                false), s, env::get, u -> null));
        assertEquals(List.of("/bin/sh"), ShellCommand.command(LaunchOptions.SHELL, s, env::get, u -> null));
        TerminalSettings custom = new TerminalSettings(s.scheme(), s.fontFamily(), s.fontSize(), s.cursor(),
                s.cursorBlink(), s.columns(), s.rows(), s.scrollback(), s.brightBold(), "tmux", true, true, false,
                true, true, true, true);
        assertEquals(List.of("/bin/sh", "-c", "tmux"), ShellCommand.command(LaunchOptions.SHELL, custom, env::get,
                u -> null));
    }

    @Test
    void shellFallsBackToPasswdThenBash() {
        TerminalSettings login = new TerminalSettings(ColorScheme.NIGHT, "x", 13, CursorShape.BLOCK, true, 80, 24,
                100, true, "", true, true, false, true, true, true, true);
        Map<String, String> env = Map.of("SHELL", "/no/such/shell", "USER", "ralf");
        assertEquals(List.of("/bin/sh", "-l"), ShellCommand.command(LaunchOptions.SHELL, login, env::get,
                u -> "/bin/sh"));
        assertEquals("/bin/bash", ShellCommand.command(LaunchOptions.SHELL, login, env::get, u -> null).get(0));
    }

    @Test
    void environmentSetsTermAndRemovesForeignVariables() {
        Map<String, String> env = ShellCommand.environment("1.0.0");
        assertEquals("xterm-256color", env.get("TERM"));
        assertEquals("truecolor", env.get("COLORTERM"));
        assertTrue(env.containsKey("LINES"));
        assertNull(env.get("LINES"));
    }

    @Test
    void quotesPaths() {
        assertEquals("/tmp/a.txt '/tmp/My Files' 'it'\\''s' ",
                ShellCommand.quotePaths(List.of(Path.of("/tmp/a.txt"), Path.of("/tmp/My Files"), Path.of("it's"))));
        assertEquals("''", ShellCommand.quote(""));
    }

    @Test
    void passwdShellOfUnknownUser() {
        assertNull(ShellCommand.passwdShell("no-such-user-here"));
        assertNull(ShellCommand.passwdShell(null));
        assertTrue(ShellCommand.passwdShell("root") == null || ShellCommand.passwdShell("root").startsWith("/"));
    }

    @Test
    void tabTitles() {
        assertEquals("~/Documents", TabTitles.shorten("ralf@amadeos: ~/Documents"));
        assertEquals("htop", TabTitles.shorten("htop"));
        assertEquals("vim: notes.txt", TabTitles.shorten("vim: notes.txt"));
        String longTitle = TabTitles.shorten("/usr/share/doc/a-very-long-package-name/examples");
        assertEquals(TabTitles.MAX, longTitle.length());
        assertTrue(longTitle.contains("…"));
        assertEquals("", TabTitles.shorten(null));
    }

    @Test
    void folderWithTilde() {
        Path home = Path.of("/home/ralf");
        assertEquals("~", TabTitles.folder(home, home));
        assertEquals("~/Documents", TabTitles.folder(home.resolve("Documents"), home));
        assertEquals("/etc", TabTitles.folder(Path.of("/etc"), home));
    }

    @Test
    void pasteChecks() {
        assertEquals(List.of("a", "b"), PasteText.lines("a\nb\n"));
        assertEquals(List.of("a", "", "b"), PasteText.lines("a\r\n\r\nb"));
        assertTrue(PasteText.needsWarning("a\nb", false));
        assertFalse(PasteText.needsWarning("a\nb", true));
        assertFalse(PasteText.needsWarning("one line", false));
        assertEquals("cd x rm -rf build ./gradlew build", PasteText.oneLine("cd x\nrm -rf build\n\n./gradlew build\n"));
    }

    @Test
    void schemesAndPalettes() {
        assertTrue(ColorScheme.NIGHT.palette(Color.WHITE, Color.BLACK, Color.BLUE, Color.WHITE).isDark());
        assertFalse(ColorScheme.PAPER.palette(Color.WHITE, Color.BLACK, Color.BLUE, Color.WHITE).isDark());
        Palette desktop = ColorScheme.DESKTOP.palette(new Color(0xF0F0E8), new Color(0x101010), Color.BLUE,
                Color.WHITE);
        assertEquals(0xF0F0E8, desktop.background());
        assertEquals(0x101010, desktop.foreground());
        assertEquals(ColorScheme.AMBER, ColorScheme.byId("amber").orElseThrow());
        assertTrue(ColorScheme.byId("nope").isEmpty());
        Palette green = ColorScheme.GREEN.palette(Color.WHITE, Color.BLACK, Color.BLUE, Color.WHITE);
        for (int i = 0; i < 16; i++) {
            int c = green.ansi(i);
            assertTrue(((c >> 8) & 0xFF) >= ((c >> 16) & 0xFF), "colour " + i + " is green");
        }
    }

    @Test
    void paletteNeedsSixteenColours() {
        try {
            new Palette(0, 0, 0, 0, 0, 0, new int[3]);
            throw new AssertionError("expected an exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("16"));
        }
    }

    @Test
    void cursorShapesFromPrograms() {
        assertEquals(CursorShape.BAR, CursorShape.fromDecscusr(6, CursorShape.BLOCK));
        assertEquals(CursorShape.UNDERLINE, CursorShape.fromDecscusr(3, CursorShape.BLOCK));
        assertEquals(CursorShape.BLOCK, CursorShape.fromDecscusr(0, CursorShape.BLOCK));
        assertTrue(CursorShape.blinksFor(1, false));
        assertFalse(CursorShape.blinksFor(2, true));
        assertTrue(CursorShape.blinksFor(0, true));
        assertEquals(CursorShape.BAR, CursorShape.byId("bar").orElseThrow());
    }

    @Test
    void settingsAreClamped() {
        TerminalSettings s = new TerminalSettings(ColorScheme.NIGHT, "", 2, CursorShape.BLOCK, true, 1, 1, -5, true,
                null, false, true, false, true, true, true, true);
        assertEquals(TerminalSettings.MIN_FONT, s.fontSize());
        assertEquals(20, s.columns());
        assertEquals(5, s.rows());
        assertEquals(0, s.scrollback());
        assertEquals(TerminalSettings.DEFAULT_FONT, s.fontFamily());
        assertEquals("", s.command());
        assertEquals(TerminalSettings.MAX_FONT, s.withFontSize(99).fontSize());
        assertEquals(20, s.withFontSize(20).fontSize());
        assertFalse(s.withPasteWarning(false).pasteWarning());
    }
}
