package eu.amadeos.apps.terminal.emulator;

import java.util.Arrays;

/**
 * One row of cells. A cell holds a code point ({@link #EMPTY} for a blank never written, {@link #WIDE_TAIL}
 * for the right half of a wide character), foreground and background colour, attributes, a hyperlink id and,
 * rarely, combining characters. {@link #wrapped()} says that the text goes on in the next row (the program wrote
 * past the end of this one), so copying and rewrapping join the two.
 */
public final class Line {
    public static final int EMPTY = 0;
    public static final int WIDE_TAIL = -1;

    private int[] cp;
    private int[] fg;
    private int[] bg;
    private int[] attr;
    private int[] link;
    private String[] combining;
    private boolean wrapped;

    public Line(int columns) {
        this(columns, Attr.DEFAULT);
    }

    public Line(int columns, int background) {
        cp = new int[columns];
        fg = new int[columns];
        bg = new int[columns];
        attr = new int[columns];
        link = new int[columns];
        Arrays.fill(fg, Attr.DEFAULT);
        Arrays.fill(bg, background);
    }

    public int columns() {
        return cp.length;
    }

    public int codePoint(int col) {
        return cp[col];
    }

    public int foreground(int col) {
        return fg[col];
    }

    public int background(int col) {
        return bg[col];
    }

    public int attributes(int col) {
        return attr[col];
    }

    public int link(int col) {
        return link[col];
    }

    /** Combining characters after the base character, or null. */
    public String combining(int col) {
        return combining == null ? null : combining[col];
    }

    public boolean wrapped() {
        return wrapped;
    }

    public void setWrapped(boolean wrapped) {
        this.wrapped = wrapped;
    }

    /** True if the cell holds the left half of a wide character. */
    public boolean isWide(int col) {
        return col + 1 < cp.length && cp[col + 1] == WIDE_TAIL;
    }

    /** Writes a character; a wide character takes this and the next cell. */
    public void set(int col, int codePoint, int width, Style style) {
        breakWide(col);
        if (width == 2 && col + 1 < cp.length) breakWide(col + 1);
        put(col, codePoint, style);
        if (width == 2 && col + 1 < cp.length) put(col + 1, WIDE_TAIL, style);
    }

    private void put(int col, int codePoint, Style style) {
        cp[col] = codePoint;
        fg[col] = style.foreground();
        bg[col] = style.background();
        attr[col] = style.attributes();
        link[col] = style.link();
        if (combining != null) combining[col] = null;
    }

    /** Adds a combining character to the cell. */
    public void combine(int col, int codePoint) {
        if (cp[col] == WIDE_TAIL && col > 0) col--;
        if (cp[col] == EMPTY) return;
        if (combining == null) combining = new String[cp.length];
        String before = combining[col];
        String add = Character.toString(codePoint);
        if (before == null) combining[col] = add;
        else if (before.length() < 16) combining[col] = before + add;
    }

    /** Overwriting one half of a wide character blanks the other half. */
    private void breakWide(int col) {
        if (cp[col] == WIDE_TAIL && col > 0) {
            blank(col - 1, bg[col - 1]);
        } else if (col + 1 < cp.length && cp[col + 1] == WIDE_TAIL) {
            blank(col + 1, bg[col + 1]);
        }
    }

    private void blank(int col, int background) {
        cp[col] = EMPTY;
        fg[col] = Attr.DEFAULT;
        bg[col] = background;
        attr[col] = 0;
        link[col] = 0;
        if (combining != null) combining[col] = null;
    }

    /** Blanks the cells {@code from} (inclusive) to {@code to} (exclusive) with a background colour. */
    public void erase(int from, int to, int background) {
        from = Math.max(0, from);
        to = Math.min(cp.length, to);
        if (from >= to) return;
        breakWide(from);
        if (to < cp.length) breakWide(to);
        for (int i = from; i < to; i++) blank(i, background);
    }

    /** Inserts {@code n} blanks at {@code col}, shifting the rest right; cells pushed past the end are lost. */
    public void insertBlanks(int col, int n, int background) {
        int len = cp.length;
        if (col >= len) return;
        n = Math.min(n, len - col);
        breakWide(col);
        int move = len - col - n;
        shift(col, col + n, move);
        for (int i = col; i < col + n; i++) blank(i, background);
        if (isWideHeadAtEnd()) blank(len - 1, background);
    }

    private boolean isWideHeadAtEnd() {
        int last = cp.length - 1;
        int c = cp[last];
        return c != EMPTY && c != WIDE_TAIL && CharWidth.of(c) == 2;
    }

    /** Deletes {@code n} cells at {@code col}, shifting the rest left and filling the end with blanks. */
    public void deleteCells(int col, int n, int background) {
        int len = cp.length;
        if (col >= len) return;
        n = Math.min(n, len - col);
        breakWide(col);
        if (col + n < len) breakWide(col + n);
        int move = len - col - n;
        shift(col + n, col, move);
        for (int i = len - n; i < len; i++) blank(i, background);
    }

    private void shift(int from, int to, int count) {
        if (count <= 0) return;
        System.arraycopy(cp, from, cp, to, count);
        System.arraycopy(fg, from, fg, to, count);
        System.arraycopy(bg, from, bg, to, count);
        System.arraycopy(attr, from, attr, to, count);
        System.arraycopy(link, from, link, to, count);
        if (combining != null) System.arraycopy(combining, from, combining, to, count);
    }

    /** Copies one cell from another line. */
    public void copyCell(Line from, int fromCol, int toCol) {
        cp[toCol] = from.cp[fromCol];
        fg[toCol] = from.fg[fromCol];
        bg[toCol] = from.bg[fromCol];
        attr[toCol] = from.attr[fromCol];
        link[toCol] = from.link[fromCol];
        String comb = from.combining(fromCol);
        if (comb != null) {
            if (combining == null) combining = new String[cp.length];
            combining[toCol] = comb;
        } else if (combining != null) {
            combining[toCol] = null;
        }
    }

    /** The same cells in a line of another width: cut or padded with blanks. */
    public Line resized(int columns) {
        Line l = new Line(columns);
        int n = Math.min(columns, cp.length);
        for (int i = 0; i < n; i++) l.copyCell(this, i, i);
        if (n > 0 && n < cp.length && l.isWideHeadAtEnd()) l.blank(n - 1, Attr.DEFAULT);
        l.wrapped = wrapped && columns >= cp.length;
        return l;
    }

    /** Number of cells up to the last one that is not a default blank. */
    public int usedLength() {
        for (int i = cp.length - 1; i >= 0; i--) {
            if (cp[i] != EMPTY || bg[i] != Attr.DEFAULT || attr[i] != 0) return i + 1;
        }
        return 0;
    }

    public boolean isBlank() {
        return usedLength() == 0;
    }

    /** The text of the cells {@code from} to {@code to}: blanks as spaces, wide tails left out. */
    public String text(int from, int to) {
        StringBuilder sb = new StringBuilder();
        to = Math.min(to, cp.length);
        for (int i = Math.max(0, from); i < to; i++) {
            int c = cp[i];
            if (c == WIDE_TAIL) continue;
            sb.appendCodePoint(c == EMPTY ? ' ' : c);
            String comb = combining(i);
            if (comb != null) sb.append(comb);
        }
        return sb.toString();
    }

    /** The whole row as text, see {@link #text(int, int)}. */
    public String text() {
        return text(0, cp.length);
    }

    @Override
    public String toString() {
        return text() + (wrapped ? "↵" : "");
    }
}
