package eu.amadeos.apps.terminal.emulator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalEmulatorTest {
    private static final String ESC = "\u001b";
    private static final String CSI = ESC + "[";

    private final List<String> responses = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> clipboard = new ArrayList<>();
    private int bells;

    private TerminalEmulator term(int cols, int rows) {
        TerminalEmulator t = new TerminalEmulator(cols, rows, 100);
        t.setListener(new TerminalListener() {
            @Override
            public void respond(String text) {
                responses.add(text);
            }

            @Override
            public void bell() {
                bells++;
            }

            @Override
            public void titleChanged(String title) {
                titles.add(title);
            }

            @Override
            public void clipboardSet(String text) {
                clipboard.add(text);
            }

            @Override
            public int color(int which) {
                return which == BACKGROUND ? 0x1E2226 : 0xD8D4C8;
            }
        });
        return t;
    }

    private static String row(TerminalEmulator t, int r) {
        return t.screenLine(r).text().stripTrailing();
    }

    private static String screen(TerminalEmulator t) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < t.rows(); r++) {
            if (r > 0) sb.append('|');
            sb.append(row(t, r));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- printing and wrapping

    @Test
    void printsText() {
        TerminalEmulator t = term(10, 3);
        t.feed("hello");
        assertEquals("hello", row(t, 0));
        assertEquals(5, t.cursorX());
    }

    @Test
    void crLfMovesToNextLine() {
        TerminalEmulator t = term(10, 3);
        t.feed("ab\r\ncd");
        assertEquals("ab|cd|", screen(t));
    }

    @Test
    void lineFeedKeepsColumn() {
        TerminalEmulator t = term(10, 3);
        t.feed("ab\ncd");
        assertEquals("ab|  cd|", screen(t));
    }

    @Test
    void newLineModeAddsCarriageReturn() {
        TerminalEmulator t = term(10, 3);
        t.feed(CSI + "20hab\ncd");
        assertEquals("ab|cd|", screen(t));
    }

    @Test
    void wrapsAtEndAndMarksTheLine() {
        TerminalEmulator t = term(5, 3);
        t.feed("abcdefg");
        assertEquals("abcde|fg|", screen(t));
        assertTrue(t.screenLine(0).wrapped());
        assertFalse(t.screenLine(1).wrapped());
    }

    @Test
    void cursorWaitsAtLastColumnUntilNextCharacter() {
        TerminalEmulator t = term(5, 3);
        t.feed("abcde");
        assertEquals(4, t.cursorX());
        assertEquals(0, t.cursorY());
        t.feed("\r\n");
        assertEquals("abcde||", screen(t));
        assertFalse(t.screenLine(0).wrapped());
    }

    @Test
    void autowrapOffOverwritesLastColumn() {
        TerminalEmulator t = term(5, 2);
        t.feed(CSI + "?7labcdefg");
        assertEquals("abcdg|", screen(t));
    }

    @Test
    void scrollsIntoHistory() {
        TerminalEmulator t = term(5, 2);
        t.feed("1\r\n2\r\n3\r\n4");
        assertEquals("3|4", screen(t));
        assertEquals(2, t.historySize());
        assertEquals("1", t.line(0).text().strip());
        assertEquals("2", t.line(1).text().strip());
        assertEquals(2, t.scrolledIntoHistory());
    }

    @Test
    void historyDropsOldestWhenFull() {
        TerminalEmulator t = new TerminalEmulator(5, 2, 3);
        for (int i = 0; i < 10; i++) t.feed(i + "\r\n");
        assertEquals(3, t.historySize());
        assertEquals(6, t.droppedFromHistory());
        assertEquals("6", t.line(0).text().strip());
    }

    @Test
    void backspaceMovesLeft() {
        TerminalEmulator t = term(10, 2);
        t.feed("abc\b\bX");
        assertEquals("aXc", row(t, 0));
    }

    @Test
    void tabStopsEveryEightColumns() {
        TerminalEmulator t = term(20, 2);
        t.feed("a\tb\tc");
        assertEquals("a       b       c", row(t, 0));
    }

    @Test
    void setAndClearTabStops() {
        TerminalEmulator t = term(20, 2);
        t.feed(CSI + "3g" + CSI + "4G" + ESC + "H\r\tx");
        assertEquals("   x", row(t, 0));
        t.feed("\r" + CSI + "Z" + CSI + "2Iy");
        assertEquals(19, t.cursorX());
    }

    @Test
    void backTab() {
        TerminalEmulator t = term(20, 2);
        t.feed(CSI + "18G" + CSI + "Zx");
        assertEquals(16, row(t, 0).indexOf('x'));
    }

    @Test
    void bellCallsListener() {
        TerminalEmulator t = term(10, 2);
        t.feed("\u0007");
        assertEquals(1, bells);
    }

    // ---------------------------------------------------------------- cursor movement

    @Test
    void cursorPosition() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "3;4Hx");
        assertEquals("   x", row(t, 2));
    }

    @Test
    void cursorPositionDefaultsToHome() {
        TerminalEmulator t = term(10, 5);
        t.feed("abc" + CSI + "Hx");
        assertEquals("xbc", row(t, 0));
    }

    @Test
    void cursorPositionIsClamped() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "99;99H");
        assertEquals(9, t.cursorX());
        assertEquals(4, t.cursorY());
    }

    @Test
    void relativeMoves() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "3;3H" + CSI + "A" + CSI + "2C" + CSI + "B" + CSI + "D");
        assertEquals(3, t.cursorX());
        assertEquals(2, t.cursorY());
        t.feed(CSI + "0A");
        assertEquals(1, t.cursorY());
    }

    @Test
    void nextAndPreviousLine() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "2;5H" + CSI + "2E");
        assertEquals(0, t.cursorX());
        assertEquals(3, t.cursorY());
        t.feed(CSI + "5G" + CSI + "F");
        assertEquals(0, t.cursorX());
        assertEquals(2, t.cursorY());
    }

    @Test
    void columnAndRowAbsolute() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "7G" + CSI + "4d");
        assertEquals(6, t.cursorX());
        assertEquals(3, t.cursorY());
    }

    @Test
    void saveAndRestoreCursorWithStyle() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "2;3H" + CSI + "31m" + ESC + "7" + CSI + "0m" + CSI + "H" + ESC + "8x");
        assertEquals(1, t.cursorY());
        assertEquals(1, t.screenLine(1).foreground(2));
    }

    @Test
    void scoSaveAndRestore() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "3;3H" + CSI + "s" + CSI + "H" + CSI + "u");
        assertEquals(2, t.cursorX());
        assertEquals(2, t.cursorY());
    }

    @Test
    void reverseIndexAtTopScrollsDown() {
        TerminalEmulator t = term(5, 3);
        t.feed("a\r\nb\r\nc" + CSI + "H" + ESC + "M");
        assertEquals("|a|b", screen(t));
    }

    @Test
    void indexAndNextLineEscapes() {
        TerminalEmulator t = term(5, 3);
        t.feed("ab" + ESC + "Dc" + ESC + "Ed");
        assertEquals("ab|  c|d", screen(t));
    }

    // ---------------------------------------------------------------- erasing

    @Test
    void eraseToEndOfLine() {
        TerminalEmulator t = term(10, 2);
        t.feed("abcdef" + CSI + "4G" + CSI + "K");
        assertEquals("abc", row(t, 0));
    }

    @Test
    void eraseToStartOfLine() {
        TerminalEmulator t = term(10, 2);
        t.feed("abcdef" + CSI + "3G" + CSI + "1K");
        assertEquals("   def", row(t, 0));
    }

    @Test
    void eraseWholeLine() {
        TerminalEmulator t = term(10, 2);
        t.feed("abcdef" + CSI + "2K");
        assertEquals("", row(t, 0));
    }

    @Test
    void eraseBelow() {
        TerminalEmulator t = term(5, 3);
        t.feed("aaa\r\nbbb\r\nccc" + CSI + "2;2H" + CSI + "J");
        assertEquals("aaa|b|", screen(t));
    }

    @Test
    void eraseAbove() {
        TerminalEmulator t = term(5, 3);
        t.feed("aaa\r\nbbb\r\nccc" + CSI + "2;2H" + CSI + "1J");
        assertEquals("|  b|ccc", screen(t));
    }

    @Test
    void clearScreenKeepsTextInScrollback() {
        TerminalEmulator t = term(5, 3);
        t.feed("one\r\ntwo" + CSI + "H" + CSI + "2J");
        assertEquals("||", screen(t));
        assertEquals(2, t.historySize());
        assertEquals("one", t.line(0).text().strip());
    }

    @Test
    void eraseScrollbackWithThree() {
        TerminalEmulator t = term(5, 2);
        t.feed("1\r\n2\r\n3\r\n4" + CSI + "3J");
        assertEquals(0, t.historySize());
        assertEquals("3|4", screen(t));
    }

    @Test
    void eraseUsesBackgroundColour() {
        TerminalEmulator t = term(5, 2);
        t.feed(CSI + "44m" + CSI + "2K");
        assertEquals(4, t.screenLine(0).background(3));
    }

    @Test
    void eraseCharacters() {
        TerminalEmulator t = term(10, 2);
        t.feed("abcdef" + CSI + "2G" + CSI + "3X");
        assertEquals("a   ef", row(t, 0));
    }

    // ---------------------------------------------------------------- insert and delete

    @Test
    void insertCharacters() {
        TerminalEmulator t = term(8, 2);
        t.feed("abcdef" + CSI + "3G" + CSI + "2@");
        assertEquals("ab  cdef", row(t, 0));
    }

    @Test
    void deleteCharacters() {
        TerminalEmulator t = term(8, 2);
        t.feed("abcdef" + CSI + "2G" + CSI + "2P");
        assertEquals("adef", row(t, 0));
    }

    @Test
    void insertMode() {
        TerminalEmulator t = term(8, 2);
        t.feed("abc" + CSI + "G" + CSI + "4hXY" + CSI + "4lZ");
        assertEquals("XYZbc", row(t, 0));
    }

    @Test
    void insertLines() {
        TerminalEmulator t = term(5, 4);
        t.feed("a\r\nb\r\nc\r\nd" + CSI + "2;1H" + CSI + "L");
        assertEquals("a||b|c", screen(t));
    }

    @Test
    void deleteLines() {
        TerminalEmulator t = term(5, 4);
        t.feed("a\r\nb\r\nc\r\nd" + CSI + "2;1H" + CSI + "2M");
        assertEquals("a|d||", screen(t));
    }

    @Test
    void repeatLastCharacter() {
        TerminalEmulator t = term(10, 2);
        t.feed("x" + CSI + "4b");
        assertEquals("xxxxx", row(t, 0));
    }

    // ---------------------------------------------------------------- scroll regions

    @Test
    void scrollRegionKeepsStatusLine() {
        TerminalEmulator t = term(10, 4);
        t.feed(CSI + "4;1Hstatus" + CSI + "1;3r" + CSI + "3;1H1\r\n2\r\n3\r\n4");
        assertEquals("2|3|4|status", screen(t));
        assertEquals(3, t.historySize(), "a region at the top feeds the history, as in xterm");
    }

    @Test
    void regionNotAtTopKeepsNothingInHistory() {
        TerminalEmulator t = term(10, 4);
        t.feed("top" + CSI + "2;4r" + CSI + "4;1Ha\r\nb\r\nc");
        assertEquals("top|a|b|c", screen(t));
        assertEquals(0, t.historySize());
    }

    @Test
    void scrollUpAndDownCommands() {
        TerminalEmulator t = term(5, 3);
        t.feed("a\r\nb\r\nc" + CSI + "S");
        assertEquals("b|c|", screen(t));
        t.feed(CSI + "2T");
        assertEquals("||b", screen(t));
    }

    @Test
    void originModeCountsFromRegion() {
        TerminalEmulator t = term(10, 6);
        t.feed(CSI + "3;5r" + CSI + "?6h" + CSI + "1;1Hx" + CSI + "9;1Hy");
        assertEquals("x", row(t, 2));
        assertEquals("y", row(t, 4));
        responses.clear();
        t.feed(CSI + "6n");
        assertEquals(CSI + "3;2R", responses.get(0));
    }

    @Test
    void cursorUpStopsAtRegionTop() {
        TerminalEmulator t = term(10, 6);
        t.feed(CSI + "2;5r" + CSI + "4;1H" + CSI + "10A");
        assertEquals(1, t.cursorY());
    }

    // ---------------------------------------------------------------- SGR

    @Test
    void basicColours() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "31;42mx" + CSI + "0my");
        Line l = t.screenLine(0);
        assertEquals(1, l.foreground(0));
        assertEquals(2, l.background(0));
        assertEquals(Attr.DEFAULT, l.foreground(1));
    }

    @Test
    void brightColours() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "91;104mx");
        assertEquals(9, t.screenLine(0).foreground(0));
        assertEquals(12, t.screenLine(0).background(0));
    }

    @Test
    void colours256() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "38;5;208;48;5;17mx");
        assertEquals(208, t.screenLine(0).foreground(0));
        assertEquals(17, t.screenLine(0).background(0));
    }

    @Test
    void trueColourWithSemicolons() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "38;2;255;128;0mx");
        int c = t.screenLine(0).foreground(0);
        assertTrue(Attr.isRgb(c));
        assertEquals(0xFF8000, Attr.rgbValue(c));
    }

    @Test
    void trueColourWithColons() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "38:2::1:2:3mx" + CSI + "48:2:4:5:6my");
        assertEquals(0x010203, Attr.rgbValue(t.screenLine(0).foreground(0)));
        assertEquals(0x040506, Attr.rgbValue(t.screenLine(0).background(1)));
    }

    @Test
    void attributesOnAndOff() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "1;3;4;7;9ma" + CSI + "22;23;24;27;29mb");
        int a = t.screenLine(0).attributes(0);
        assertEquals(Attr.BOLD | Attr.ITALIC | Attr.UNDERLINE | Attr.INVERSE | Attr.STRIKE, a);
        assertEquals(0, t.screenLine(0).attributes(1));
    }

    @Test
    void curlyUnderlineCountsAsUnderline() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "4:3mx" + CSI + "4:0my");
        assertEquals(Attr.UNDERLINE, t.screenLine(0).attributes(0));
        assertEquals(0, t.screenLine(0).attributes(1));
    }

    @Test
    void emptySgrResets() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "1;31m" + CSI + "mx");
        assertEquals(0, t.screenLine(0).attributes(0));
        assertEquals(Attr.DEFAULT, t.screenLine(0).foreground(0));
    }

    @Test
    void underlineColourIsSkipped() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "58;2;1;2;3;31mx");
        assertEquals(1, t.screenLine(0).foreground(0));
    }

    @Test
    void paletteColours() {
        assertEquals(0x000000, Attr.paletteRgb(16));
        assertEquals(0xFFFFFF, Attr.paletteRgb(231));
        assertEquals(0x5F87AF, Attr.paletteRgb(67));
        assertEquals(0x080808, Attr.paletteRgb(232));
        assertEquals(0xEEEEEE, Attr.paletteRgb(255));
    }

    // ---------------------------------------------------------------- characters

    @Test
    void utf8AcrossFeeds() {
        TerminalEmulator t = term(10, 2);
        byte[] b = "äöü".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        t.feed(b, 0, 3);
        t.feed(b, 3, b.length - 3);
        assertEquals("äöü", row(t, 0));
    }

    @Test
    void brokenUtf8BecomesReplacementCharacter() {
        TerminalEmulator t = term(10, 2);
        t.feed(new byte[]{'a', (byte) 0xC3, 'b'}, 0, 3);
        assertEquals("a�b", row(t, 0));
    }

    @Test
    void wideCharactersTakeTwoCells() {
        TerminalEmulator t = term(10, 2);
        t.feed("日本x");
        Line l = t.screenLine(0);
        assertEquals('日', l.codePoint(0));
        assertEquals(Line.WIDE_TAIL, l.codePoint(1));
        assertEquals('x', l.codePoint(4));
        assertEquals(5, t.cursorX());
        assertEquals("日本x", row(t, 0));
    }

    @Test
    void wideCharacterAtLastColumnWraps() {
        TerminalEmulator t = term(5, 2);
        t.feed("abcd日");
        assertEquals("abcd", row(t, 0));
        assertEquals("日", row(t, 1));
        assertTrue(t.screenLine(0).wrapped());
    }

    @Test
    void emojiIsWide() {
        assertEquals(2, CharWidth.of(0x1F600));
        assertEquals(1, CharWidth.of('a'));
        assertEquals(0, CharWidth.of(0x0301));
        assertEquals(2, CharWidth.of('한'));
        assertEquals(1, CharWidth.of('ä'));
    }

    @Test
    void overwritingHalfAWideCharacterBlanksTheOtherHalf() {
        TerminalEmulator t = term(10, 2);
        t.feed("日" + CSI + "2Gx");
        Line l = t.screenLine(0);
        assertEquals(Line.EMPTY, l.codePoint(0));
        assertEquals('x', l.codePoint(1));
    }

    @Test
    void combiningMarkJoinsCharacter() {
        TerminalEmulator t = term(10, 2);
        t.feed("éx");
        assertEquals("éx", row(t, 0));
        assertEquals(2, t.cursorX());
    }

    @Test
    void decLineDrawing() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + "(0lqk" + ESC + "(Bq");
        assertEquals("┌─┐q", row(t, 0));
    }

    @Test
    void shiftOutUsesG1() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + ")0a\u000eq\u000fq");
        assertEquals("a─q", row(t, 0));
    }

    @Test
    void decAlignmentFillsWithE() {
        TerminalEmulator t = term(3, 2);
        t.feed(ESC + "#8");
        assertEquals("EEE|EEE", screen(t));
    }

    // ---------------------------------------------------------------- modes

    @Test
    void alternateScreenRestoresShell() {
        TerminalEmulator t = term(10, 3);
        t.feed("$ ls\r\nfile\r\n$ " + CSI + "?1049h" + CSI + "H" + CSI + "2Jfull screen");
        assertTrue(t.isAlternateScreen());
        assertEquals("full scree|n|", screen(t));
        t.feed(CSI + "?1049l");
        assertFalse(t.isAlternateScreen());
        assertEquals("$ ls|file|$", screen(t));
        assertEquals(2, t.cursorX());
        assertEquals(2, t.cursorY());
    }

    @Test
    void alternateScreenHasNoHistory() {
        TerminalEmulator t = term(10, 2);
        t.feed("a\r\nb\r\nc");
        int before = t.historySize();
        t.feed(CSI + "?1049h1\r\n2\r\n3\r\n4");
        assertEquals(0, t.historySize());
        t.feed(CSI + "?1049l");
        assertEquals(before, t.historySize());
    }

    @Test
    void mode47KeepsAlternateContent() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?47hx" + CSI + "?47l" + CSI + "?47h");
        assertEquals("x", row(t, 0));
    }

    @Test
    void mode1047ClearsOnLeave() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?1047hx" + CSI + "?1047l" + CSI + "?1047h");
        assertEquals("", row(t, 0));
    }

    @Test
    void cursorKeysAndKeypadModes() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?1h" + ESC + "=");
        assertTrue(t.appCursorKeys());
        assertTrue(t.appKeypad());
        t.feed(CSI + "?1l" + ESC + ">");
        assertFalse(t.appCursorKeys());
        assertFalse(t.appKeypad());
    }

    @Test
    void mouseModes() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?1002h" + CSI + "?1006h");
        assertEquals(TerminalEmulator.MouseMode.BUTTON, t.mouseMode());
        assertEquals(TerminalEmulator.MouseEncoding.SGR, t.mouseEncoding());
        t.feed(CSI + "?1002l");
        assertEquals(TerminalEmulator.MouseMode.NONE, t.mouseMode());
    }

    @Test
    void bracketedPasteAndFocusModes() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?2004h" + CSI + "?1004h");
        assertTrue(t.bracketedPaste());
        assertTrue(t.focusEvents());
    }

    @Test
    void cursorVisibilityAndStyle() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?25l" + CSI + "5 q");
        assertFalse(t.cursorVisible());
        assertEquals(5, t.cursorStyle());
        t.feed(CSI + "?25h");
        assertTrue(t.cursorVisible());
    }

    @Test
    void reverseVideoMode() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?5h");
        assertTrue(t.reverseVideo());
    }

    @Test
    void fullResetClearsEverything() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "31mabc" + CSI + "?1h" + CSI + "?2004h" + ESC + "c");
        assertEquals("|", screen(t));
        assertFalse(t.appCursorKeys());
        assertFalse(t.bracketedPaste());
        t.feed("x");
        assertEquals(Attr.DEFAULT, t.screenLine(0).foreground(0));
    }

    @Test
    void softResetKeepsScreen() {
        TerminalEmulator t = term(10, 2);
        t.feed("abc" + CSI + "?25l" + CSI + "!p");
        assertEquals("abc", row(t, 0));
        assertTrue(t.cursorVisible());
    }

    // ---------------------------------------------------------------- answers

    @Test
    void deviceAttributes() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "c" + CSI + ">c");
        assertEquals(List.of(CSI + "?62;22c", CSI + ">1;10;0c"), responses);
    }

    @Test
    void statusAndCursorReport() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "5n" + CSI + "3;7H" + CSI + "6n");
        assertEquals(List.of(CSI + "0n", CSI + "3;7R"), responses);
    }

    @Test
    void reportsTextAreaSize() {
        TerminalEmulator t = term(80, 24);
        t.feed(CSI + "18t");
        assertEquals(CSI + "8;24;80t", responses.get(0));
    }

    @Test
    void reportsModes() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?2004h" + CSI + "?2004$p" + CSI + "?1$p" + CSI + "?9999$p");
        assertEquals(List.of(CSI + "?2004;1$y", CSI + "?1;2$y", CSI + "?9999;0$y"), responses);
    }

    @Test
    void answersColourQueries() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + "]11;?\u0007");
        assertEquals(ESC + "]11;rgb:1e1e/2222/2626" + ESC + "\\", responses.get(0));
    }

    @Test
    void cursorStyleQuery() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "2 q" + ESC + "P$q q" + ESC + "\\");
        assertEquals(ESC + "P1$r2 q" + ESC + "\\", responses.get(0));
    }

    // ---------------------------------------------------------------- OSC

    @Test
    void titleWithBelAndSt() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + "]0;ralf@amadeos: ~\u0007" + ESC + "]2;htop" + ESC + "\\x");
        assertEquals(List.of("ralf@amadeos: ~", "htop"), titles);
        assertEquals("htop", t.title());
        assertEquals("x", row(t, 0));
    }

    @Test
    void titleStack() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + "]2;one\u0007" + CSI + "22t" + ESC + "]2;two\u0007" + CSI + "23t");
        assertEquals("one", t.title());
    }

    @Test
    void currentDirectoryFromOsc7() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + "]7;file://amadeos/home/ralf/My%20Documents\u0007");
        assertEquals("/home/ralf/My Documents", t.directory());
    }

    @Test
    void hyperlinks() {
        TerminalEmulator t = term(20, 2);
        t.feed(ESC + "]8;;https://amadeos.eu\u0007site" + ESC + "]8;;\u0007 x");
        Line l = t.screenLine(0);
        assertEquals("https://amadeos.eu", t.link(l.link(0)));
        assertEquals(0, l.link(5));
        TextModel.Link link = TextModel.linkAt(t, 0, 2);
        assertEquals("https://amadeos.eu", link.target());
        assertEquals(new TextModel.Range(0, 0, 0, 4), link.range());
    }

    @Test
    void clipboardFromOsc52() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + "]52;c;aGVsbG8=\u0007" + ESC + "]52;c;?\u0007");
        assertEquals(List.of("hello"), clipboard);
        t.setAllowClipboard(false);
        t.feed(ESC + "]52;c;aGk=\u0007");
        assertEquals(1, clipboard.size());
    }

    @Test
    void paletteFromOsc4() {
        TerminalEmulator t = term(10, 2);
        t.feed(ESC + "]4;1;#ff0000;2;rgb:00/ff/00\u0007");
        assertEquals(0xFF0000, t.paletteOverride(1));
        assertEquals(0x00FF00, t.paletteOverride(2));
        t.feed(ESC + "]104\u0007");
        assertEquals(-1, t.paletteOverride(1));
    }

    @Test
    void parsesXColours() {
        assertEquals(0xFF8000, TerminalEmulator.parseColor("rgb:ffff/8080/0000"));
        assertEquals(0xFF8000, TerminalEmulator.parseColor("#ff8000"));
        assertEquals(-1, TerminalEmulator.parseColor("red"));
    }

    // ---------------------------------------------------------------- parser robustness

    @Test
    void unknownSequencesAreIgnored() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "?999h" + CSI + "5;6;7y" + ESC + "P+q544e" + ESC + "\\" + ESC + "_apc" + ESC + "\\ok");
        assertEquals("ok", row(t, 0));
    }

    @Test
    void controlCharacterInsideSequenceIsExecuted() {
        TerminalEmulator t = term(10, 3);
        t.feed("ab" + CSI + "\r2Cx");
        assertEquals("abx", row(t, 0));
    }

    @Test
    void cancelAbortsSequence() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "31\u0018x");
        assertEquals(Attr.DEFAULT, t.screenLine(0).foreground(0));
        assertEquals("x", row(t, 0));
    }

    @Test
    void hugeParametersDoNotOverflow() {
        TerminalEmulator t = term(10, 2);
        t.feed(CSI + "99999999999999999999C" + CSI + "99999999999b");
        assertEquals(9, t.cursorX());
    }

    @Test
    void manyParametersAreCut() {
        TerminalEmulator t = term(10, 2);
        StringBuilder sb = new StringBuilder(CSI);
        for (int i = 0; i < 100; i++) sb.append("1;");
        t.feed(sb + "31mx");
        assertEquals("x", row(t, 0));
    }

    // ---------------------------------------------------------------- resize and reflow

    @Test
    void narrowerWindowRewrapsLongLine() {
        TerminalEmulator t = term(10, 4);
        t.feed("abcdefgh\r\n$ ");
        t.resize(4, 4);
        assertEquals("abcd|efgh|$|", screen(t));
        assertTrue(t.screenLine(0).wrapped());
        assertEquals(2, t.cursorX());
        assertEquals(2, t.cursorY());
    }

    @Test
    void widerWindowJoinsWrappedLines() {
        TerminalEmulator t = term(4, 4);
        t.feed("abcdefgh\r\n$ ");
        t.resize(10, 4);
        assertEquals("abcdefgh|$||", screen(t));
        assertEquals(1, t.cursorY());
        assertEquals(2, t.cursorX());
    }

    @Test
    void shorterWindowMovesLinesIntoHistory() {
        TerminalEmulator t = term(10, 4);
        t.feed("1\r\n2\r\n3\r\n4");
        t.resize(10, 2);
        assertEquals("3|4", screen(t));
        assertEquals(2, t.historySize());
        assertEquals(1, t.cursorY());
    }

    @Test
    void tallerWindowPullsLinesBack() {
        TerminalEmulator t = term(10, 2);
        t.feed("1\r\n2\r\n3\r\n4");
        t.resize(10, 4);
        assertEquals("1|2|3|4", screen(t));
        assertEquals(0, t.historySize());
        assertEquals(3, t.cursorY());
    }

    @Test
    void reflowKeepsWideCharactersTogether() {
        TerminalEmulator t = term(10, 3);
        t.feed("ab日本");
        t.resize(5, 3);
        assertEquals("ab日|本|", screen(t));
    }

    @Test
    void alternateScreenIsCutNotRewrapped() {
        TerminalEmulator t = term(10, 3);
        t.feed(CSI + "?1049habcdefghij");
        t.resize(5, 3);
        assertEquals("abcde||", screen(t));
        t.feed(CSI + "?1049l");
        assertFalse(t.isAlternateScreen());
    }

    @Test
    void resizeKeepsCursorInsideScreen() {
        TerminalEmulator t = term(20, 10);
        t.feed(CSI + "10;20H");
        t.resize(5, 3);
        assertTrue(t.cursorX() < 5);
        assertTrue(t.cursorY() < 3);
    }

    @Test
    void resizeResetsScrollRegion() {
        TerminalEmulator t = term(10, 5);
        t.feed(CSI + "2;3r");
        t.resize(10, 6);
        t.feed(CSI + "6;1H\n");
        assertEquals(5, t.cursorY());
    }

    @Test
    void smallerScrollbackDropsOldLines() {
        TerminalEmulator t = term(5, 2);
        for (int i = 0; i < 20; i++) t.feed(i + "\r\n");
        t.setScrollbackCapacity(5);
        assertEquals(5, t.historySize());
        t.clearHistory();
        assertEquals(0, t.historySize());
        assertNull(t.directory());
    }
}
