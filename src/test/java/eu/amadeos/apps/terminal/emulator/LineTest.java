package eu.amadeos.apps.terminal.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineTest {
    private static Line line(String text, int cols) {
        Line l = new Line(cols);
        int col = 0;
        for (int cp : text.codePoints().toArray()) {
            int w = CharWidth.of(cp);
            l.set(col, cp, w, Style.DEFAULT);
            col += w;
        }
        return l;
    }

    @Test
    void insertPushesWideCharacterOffTheEnd() {
        Line l = line("abc日", 5);
        l.insertBlanks(0, 1, Attr.DEFAULT);
        assertEquals(" abc ", l.text());
    }

    @Test
    void deleteInsideWideCharacterBlanksIt() {
        Line l = line("a日b", 5);
        l.deleteCells(2, 1, Attr.DEFAULT);
        assertEquals("a b", l.text().stripTrailing());
    }

    @Test
    void resizedKeepsCells() {
        Line l = line("hello", 5);
        assertEquals("hel", l.resized(3).text());
        assertEquals("hello   ", l.resized(8).text());
    }

    @Test
    void usedLengthIgnoresDefaultBlanks() {
        Line l = line("ab", 10);
        assertEquals(2, l.usedLength());
        l.erase(5, 6, 4);
        assertEquals(6, l.usedLength());
        assertTrue(new Line(4).isBlank());
    }

    @Test
    void scrollbackRingGrowsAndDrops() {
        Scrollback s = new Scrollback(3);
        assertTrue(!s.add(new Line(1)));
        s.add(new Line(2));
        s.add(new Line(3));
        assertTrue(s.add(new Line(4)));
        assertEquals(3, s.size());
        assertEquals(2, s.get(0).columns());
        assertEquals(4, s.removeLast().columns());
        assertEquals(1, s.setCapacity(1));
        assertEquals(3, s.get(0).columns());
    }

    @Test
    void zeroScrollbackKeepsNothing() {
        Scrollback s = new Scrollback(0);
        assertTrue(s.add(new Line(1)));
        assertEquals(0, s.size());
    }
}
