package eu.amadeos.apps.terminal.emulator;

import eu.amadeos.apps.terminal.emulator.TextModel.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextModelTest {
    private static TerminalEmulator term(int cols, int rows, String text) {
        TerminalEmulator t = new TerminalEmulator(cols, rows, 100);
        t.feed(text);
        return t;
    }

    @Test
    void textOfSelectionAcrossLines() {
        TerminalEmulator t = term(10, 3, "hello\r\nworld");
        assertEquals("llo\nwor", TextModel.text(t, 0, 2, 1, 3));
    }

    @Test
    void wrappedLinesAreCopiedWithoutBreak() {
        TerminalEmulator t = term(5, 3, "abcdefgh");
        assertEquals("abcdefgh", TextModel.text(t, 0, 0, 1, 5));
    }

    @Test
    void trailingBlanksAreDropped() {
        TerminalEmulator t = term(10, 3, "ab\r\ncd");
        assertEquals("ab\ncd", TextModel.text(t, 0, 0, 1, 10));
    }

    @Test
    void wideCharactersCopiedOnce() {
        TerminalEmulator t = term(10, 2, "日本");
        assertEquals("日本", TextModel.text(t, 0, 0, 0, 10));
    }

    @Test
    void doubleClickSelectsPath() {
        TerminalEmulator t = term(40, 2, "see /home/ralf/My-Docs/a.txt. ok");
        Range w = TextModel.word(t, 0, 10);
        assertEquals(new Range(0, 4, 0, 28), w);
        assertEquals("/home/ralf/My-Docs/a.txt", TextModel.text(t, 0, w.col(), 0, w.endCol()));
    }

    @Test
    void doubleClickOnBlankSelectsOneCell() {
        TerminalEmulator t = term(20, 2, "a   b");
        assertEquals(new Range(0, 2, 0, 3), TextModel.word(t, 0, 2));
    }

    @Test
    void wordAcrossWrap() {
        TerminalEmulator t = term(6, 3, "x longword");
        Range w = TextModel.word(t, 1, 1);
        assertEquals(new Range(0, 2, 1, 4), w);
        assertEquals("longword", TextModel.text(t, 0, 2, 1, 4));
    }

    @Test
    void tripleClickSelectsLogicalLine() {
        TerminalEmulator t = term(5, 3, "abcdefg");
        assertEquals(new Range(0, 0, 1, 5), TextModel.wholeLine(t, 1));
    }

    @Test
    void findsUrlUnderMouse() {
        TerminalEmulator t = term(60, 2, "docs (see https://docs.amadeos.eu/amadeos/42-x/).");
        TextModel.Link link = TextModel.linkAt(t, 0, 15);
        assertEquals("https://docs.amadeos.eu/amadeos/42-x/", link.target());
        assertNull(TextModel.linkAt(t, 0, 1));
    }

    @Test
    void urlWithBalancedBrackets() {
        TerminalEmulator t = term(60, 2, "https://en.wikipedia.org/wiki/Motif_(software) x");
        assertEquals("https://en.wikipedia.org/wiki/Motif_(software)", TextModel.linkAt(t, 0, 5).target());
    }

    @Test
    void wwwGetsHttps() {
        TerminalEmulator t = term(40, 2, "go to www.amadeos.eu now");
        assertEquals("https://www.amadeos.eu", TextModel.linkAt(t, 0, 8).target());
    }

    @Test
    void urlAcrossWrappedRows() {
        TerminalEmulator t = term(12, 3, "https://amadeos.eu/abc");
        TextModel.Link link = TextModel.linkAt(t, 1, 2);
        assertEquals("https://amadeos.eu/abc", link.target());
        assertEquals(new Range(0, 0, 1, 10), link.range());
    }

    @Test
    void findsAllHitsCaseInsensitive() {
        TerminalEmulator t = term(20, 3, "Failed one\r\nfailed two\r\nok");
        List<Range> hits = TextModel.find(t, "failed", false, false, 100);
        assertEquals(List.of(new Range(0, 0, 0, 6), new Range(1, 0, 1, 6)), hits);
        assertEquals(1, TextModel.find(t, "failed", true, false, 100).size());
    }

    @Test
    void findsInScrollback() {
        TerminalEmulator t = term(10, 2, "needle\r\na\r\nb\r\nc");
        List<Range> hits = TextModel.find(t, "needle", false, false, 100);
        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).line());
        assertTrue(t.historySize() > 0);
    }

    @Test
    void regexSearchAndBrokenRegex() {
        TerminalEmulator t = term(20, 2, "a1 b22 c333");
        assertEquals(3, TextModel.find(t, "[0-9]+", false, true, 100).size());
        assertEquals(0, TextModel.find(t, "[", false, true, 100).size());
        assertEquals(0, TextModel.find(t, "[", false, false, 100).size());
        assertEquals(0, TextModel.find(t, "", false, false, 100).size());
    }

    @Test
    void searchLimit() {
        TerminalEmulator t = term(20, 2, "xxxxxxxxxx");
        assertEquals(3, TextModel.find(t, "x", false, false, 3).size());
    }

    @Test
    void rangeContains() {
        Range r = new Range(1, 5, 2, 3);
        assertTrue(r.contains(1, 5));
        assertTrue(r.contains(1, 50));
        assertTrue(r.contains(2, 2));
        assertTrue(!r.contains(2, 3));
        assertTrue(!r.contains(1, 4));
    }
}
