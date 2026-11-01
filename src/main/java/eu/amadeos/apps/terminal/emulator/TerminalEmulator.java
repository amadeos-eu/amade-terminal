package eu.amadeos.apps.terminal.emulator;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * An xterm-compatible terminal: it takes the program's output and keeps the screen, the cursor, the modes and
 * the scrollback. No Swing in here; the view paints what {@link #line(int)} returns. Not thread-safe: the caller
 * synchronizes on the emulator.
 * <p>
 * Supported: the VT100/VT220 cursor, erase, insert and delete sequences, scroll regions, origin mode, tab stops,
 * the DEC line drawing characters, SGR with 16, 256 and true colours, the alternate screen (47, 1047, 1049),
 * mouse reporting (9, 1000, 1002, 1003 in the X10, UTF-8, SGR and urxvt encodings), focus events, bracketed
 * paste, cursor styles, window title and its stack, OSC 4/7/8/10/11/12/52/104, device attributes and status
 * reports, wide characters and combining marks. Lines that wrap are marked, so resizing rewraps them.
 */
public final class TerminalEmulator implements ParserHandler {

    public enum MouseMode { NONE, X10, NORMAL, BUTTON, ANY }

    public enum MouseEncoding { DEFAULT, UTF8, SGR, URXVT }

    private static final int CHARSET_ASCII = 0;
    private static final int CHARSET_DEC_GRAPHICS = 1;
    private static final int CHARSET_UK = 2;
    /** DEC special graphics for 0x5F to 0x7E: the line drawing characters of mc, dialog and old programs. */
    private static final String DEC_GRAPHICS =
            " ◆▒␉␌␍␊°±␤␋┘┐┌└┼"
                    + "⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·";

    private final Parser parser = new Parser(this);
    private final Utf8Decoder utf8 = new Utf8Decoder();
    private TerminalListener listener = new TerminalListener() {
    };

    private int cols;
    private int rows;
    private final Scrollback history;
    private Line[] mainLines;
    private Line[] altLines;
    private boolean altActive;

    private int cx;
    private int cy;
    private boolean wrapPending;
    private Style style = Style.DEFAULT;
    private int top;
    private int bottom;
    private boolean[] tabs;

    private boolean insertMode;
    private boolean newLineMode;
    private boolean appCursorKeys;
    private boolean appKeypad;
    private boolean originMode;
    private boolean autoWrap = true;
    private boolean reverseVideo;
    private boolean cursorVisible = true;
    private boolean cursorBlinkMode;
    private boolean bracketedPaste;
    private boolean focusEvents;
    private boolean altScroll = true;
    private int cursorStyle;
    private MouseMode mouseMode = MouseMode.NONE;
    private MouseEncoding mouseEncoding = MouseEncoding.DEFAULT;

    private final int[] charsets = new int[4];
    private int gl;

    private Saved savedMain;
    private Saved savedAlt;

    private String title = "";
    private final Deque<String> titleStack = new ArrayDeque<>();
    private String directory;
    private final Map<Integer, Integer> palette = new HashMap<>();
    private final Map<Integer, String> links = new HashMap<>();
    private final Map<String, Integer> linkIds = new HashMap<>();
    private int lastPrinted = -1;
    private long scrolledIntoHistory;
    private long droppedFromHistory;
    private long changes;
    private boolean allowClipboard = true;

    private record Saved(int x, int y, Style style, boolean wrapPending, boolean origin, int[] charsets, int gl,
                         boolean autoWrap) {
    }

    public TerminalEmulator(int cols, int rows, int scrollback) {
        this.cols = Math.max(2, cols);
        this.rows = Math.max(1, rows);
        history = new Scrollback(scrollback);
        mainLines = blankLines(this.rows, this.cols);
        altLines = blankLines(this.rows, this.cols);
        top = 0;
        bottom = this.rows - 1;
        resetTabs();
    }

    public void setListener(TerminalListener listener) {
        this.listener = listener;
    }

    // ================================================================ input from the program

    /** Feeds bytes the program wrote. */
    public void feed(byte[] bytes, int offset, int length) {
        utf8.decode(bytes, offset, length, parser::accept);
        changes++;
    }

    /** Feeds text (for tests). */
    public void feed(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        feed(b, 0, b.length);
    }

    // ================================================================ what the view reads

    public int columns() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    /** Number of history lines in front of the screen; 0 on the alternate screen (it has none). */
    public int historySize() {
        return altActive ? 0 : history.size();
    }

    /** Line {@code i}: {@code 0 .. historySize()-1} is the scrollback, then the screen rows. */
    public Line line(int i) {
        int h = historySize();
        return i < h ? history.get(i) : screen()[i - h];
    }

    /** Row {@code r} of the screen. */
    public Line screenLine(int r) {
        return screen()[r];
    }

    public int cursorX() {
        return Math.min(cx, cols - 1);
    }

    public int cursorY() {
        return cy;
    }

    public boolean cursorVisible() {
        return cursorVisible;
    }

    /** DECSCUSR: 0 the user's choice, 1/2 block, 3/4 underline, 5/6 bar (odd ones blink). */
    public int cursorStyle() {
        return cursorStyle;
    }

    public boolean cursorBlinkMode() {
        return cursorBlinkMode;
    }

    public boolean isAlternateScreen() {
        return altActive;
    }

    public boolean appCursorKeys() {
        return appCursorKeys;
    }

    public boolean appKeypad() {
        return appKeypad;
    }

    public boolean bracketedPaste() {
        return bracketedPaste;
    }

    public boolean focusEvents() {
        return focusEvents;
    }

    public boolean alternateScroll() {
        return altScroll;
    }

    public boolean reverseVideo() {
        return reverseVideo;
    }

    public MouseMode mouseMode() {
        return mouseMode;
    }

    public MouseEncoding mouseEncoding() {
        return mouseEncoding;
    }

    public String title() {
        return title;
    }

    /** The folder the shell reported with OSC 7, or null. */
    public String directory() {
        return directory;
    }

    /** The URI of a hyperlink id (OSC 8), or null. */
    public String link(int id) {
        return links.get(id);
    }

    /** A colour the program set with OSC 4 for a palette index (0xRRGGBB), or -1. */
    public int paletteOverride(int index) {
        return palette.getOrDefault(index, -1);
    }

    /** All lines that ever went into the scrollback; the view uses it to keep its place while output arrives. */
    public long scrolledIntoHistory() {
        return scrolledIntoHistory;
    }

    /** All lines the full scrollback ever dropped; selections use it to keep pointing at the same text. */
    public long droppedFromHistory() {
        return droppedFromHistory;
    }

    /** Counts every feed and change, so the view knows when to repaint. */
    public long changes() {
        return changes;
    }

    public void setAllowClipboard(boolean allow) {
        allowClipboard = allow;
    }

    public void setScrollbackCapacity(int lines) {
        droppedFromHistory += history.setCapacity(lines);
        changes++;
    }

    /** Clears the scrollback (Terminal > Clear Scrollback). */
    public void clearHistory() {
        droppedFromHistory += history.size();
        history.clear();
        changes++;
    }

    // ================================================================ resize

    /**
     * A new size. On the main screen the lines are rewrapped to the new width (the text the program wrote in one
     * go stays together), and a taller window pulls lines back from the scrollback. The alternate screen is cut
     * or padded: its program redraws it after SIGWINCH.
     */
    public void resize(int newCols, int newRows) {
        newCols = Math.max(2, newCols);
        newRows = Math.max(1, newRows);
        if (newCols == cols && newRows == rows) return;
        if (altActive) {
            altLines = cut(altLines, newCols, newRows);
            reflowMain(newCols, newRows, false);
            cx = Math.min(cx, newCols - 1);
            cy = Math.min(cy, newRows - 1);
        } else {
            altLines = blankLines(newRows, newCols);
            reflowMain(newCols, newRows, true);
        }
        cols = newCols;
        rows = newRows;
        top = 0;
        bottom = rows - 1;
        wrapPending = false;
        boolean[] old = tabs;
        resetTabs();
        for (int i = 0; i < Math.min(old.length, tabs.length); i++) tabs[i] = old[i];
        savedMain = clamp(savedMain);
        savedAlt = clamp(savedAlt);
        changes++;
    }

    private Saved clamp(Saved s) {
        if (s == null) return null;
        return new Saved(Math.min(s.x, cols - 1), Math.min(s.y, rows - 1), s.style, false, s.origin, s.charsets, s.gl,
                s.autoWrap);
    }

    private static Line[] cut(Line[] lines, int newCols, int newRows) {
        Line[] out = new Line[newRows];
        for (int i = 0; i < newRows; i++) out[i] = i < lines.length ? lines[i].resized(newCols) : new Line(newCols);
        return out;
    }

    /** Rewraps scrollback and main screen; when {@code moveCursor}, the cursor follows its character. */
    private void reflowMain(int newCols, int newRows, boolean moveCursor) {
        // the rows that count: up to the cursor or the last row with something on it
        int lastRow = moveCursor ? cy : -1;
        for (int r = rows - 1; r > lastRow; r--) {
            if (!mainLines[r].isBlank()) {
                lastRow = r;
                break;
            }
        }
        int h = history.size();
        int total = h + lastRow + 1;
        int cursorLine = h + cy;
        int cursorCol = Math.min(cx, cols - 1);

        // --8<-- [start:reflow]
        java.util.List<Line> out = new java.util.ArrayList<>();
        int newCursorLine = -1;
        int newCursorCol = 0;
        int i = 0;
        while (i < total) {
            // one logical line: rows joined by the wrap mark
            int start = i;
            while (i < total - 1 && lineAt(i, h).wrapped()) i++;
            int end = i;
            i++;
            int firstOut = out.size();
            Line current = new Line(newCols);
            int col = 0;
            for (int li = start; li <= end; li++) {
                Line src = lineAt(li, h);
                int len = li < end ? src.columns() : src.usedLength();
                if (li == cursorLine && moveCursor) len = Math.max(len, cursorCol + 1);
                for (int c = 0; c < len; c++) {
                    int cp = src.codePoint(c);
                    if (cp == Line.WIDE_TAIL) continue;
                    int w = src.isWide(c) ? 2 : 1;
                    if (col + w > newCols) {
                        current.setWrapped(true);
                        out.add(current);
                        current = new Line(newCols);
                        col = 0;
                    }
                    if (li == cursorLine && c == cursorCol && moveCursor) {
                        newCursorLine = out.size();
                        newCursorCol = col;
                    }
                    current.copyCell(src, c, col);
                    if (w == 2) current.copyCell(src, c + 1, col + 1);
                    col += w;
                }
            }
            if (moveCursor && cursorLine >= start && cursorLine <= end && newCursorLine < 0) {
                newCursorLine = out.size();
                newCursorCol = Math.min(col, newCols - 1);
            }
            out.add(current);
            if (out.size() == firstOut) out.add(new Line(newCols));
        }
        // --8<-- [end:reflow]
        if (moveCursor && newCursorLine < 0) {
            newCursorLine = out.size();
            newCursorCol = 0;
        }
        while (moveCursor && out.size() <= newCursorLine) out.add(new Line(newCols));

        int screenStart = Math.max(0, out.size() - newRows);
        if (moveCursor && newCursorLine < screenStart) screenStart = newCursorLine;
        history.clear();
        for (int k = 0; k < screenStart; k++) {
            if (history.add(out.get(k))) droppedFromHistory++;
        }
        Line[] screen = new Line[newRows];
        for (int r = 0; r < newRows; r++) {
            int k = screenStart + r;
            screen[r] = k < out.size() ? out.get(k) : new Line(newCols);
        }
        if (screenStart + newRows < out.size()) screen[newRows - 1].setWrapped(false);
        mainLines = screen;
        if (moveCursor) {
            cy = Math.min(newRows - 1, newCursorLine - screenStart);
            cx = Math.min(newCursorCol, newCols - 1);
        }
    }

    private Line lineAt(int i, int h) {
        return i < h ? history.get(i) : mainLines[i - h];
    }

    // ================================================================ parser callbacks

    // --8<-- [start:print]
    @Override
    public void print(int cp) {
        if (cp < 0x80 && cp >= 0x20) {
            int set = charsets[gl];
            if (set == CHARSET_DEC_GRAPHICS && cp >= 0x5F && cp <= 0x7E) cp = DEC_GRAPHICS.charAt(cp - 0x5F);
            else if (set == CHARSET_UK && cp == '#') cp = 0xA3;
        }
        int width = CharWidth.of(cp);
        Line[] screen = screen();
        if (width == 0) {
            // a combining mark joins the character before the cursor
            int x = wrapPending ? cx : cx - 1;
            if (x >= 0) screen[cy].combine(x, cp);
            return;
        }
        if (wrapPending && autoWrap) {
            screen[cy].setWrapped(true);
            newLine();
            cx = 0;
        }
        wrapPending = false;
        if (width == 2 && cx == cols - 1) {
            if (autoWrap) {
                screen[cy].erase(cx, cols, style.background());
                screen[cy].setWrapped(true);
                newLine();
                cx = 0;
            } else {
                width = 1;
                cp = '?';
            }
        }
        Line line = screen()[cy];
        if (insertMode) line.insertBlanks(cx, width, style.background());
        line.set(cx, cp, width, style);
        lastPrinted = cp;
        cx += width;
        if (cx >= cols) {
            cx = cols - 1;
            wrapPending = autoWrap;
            if (!autoWrap) cx = cols - 1;
        }
    }
    // --8<-- [end:print]

    @Override
    public void execute(int c) {
        switch (c) {
            case 0x07 -> listener.bell();
            case 0x08 -> {
                if (wrapPending) wrapPending = false;
                else if (cx > 0) cx--;
            }
            case 0x09 -> tab(1);
            case 0x0A, 0x0B, 0x0C -> {
                index();
                if (newLineMode) cx = 0;
            }
            case 0x0D -> {
                cx = 0;
                wrapPending = false;
            }
            case 0x0E -> gl = 1;
            case 0x0F -> gl = 0;
            default -> {
            }
        }
    }

    @Override
    public void escDispatch(String im, int f) {
        if (im.isEmpty()) {
            switch (f) {
                case '7' -> saveCursor();
                case '8' -> restoreCursor();
                case 'D' -> index();
                case 'E' -> {
                    index();
                    cx = 0;
                }
                case 'H' -> tabs[Math.min(cx, cols - 1)] = true;
                case 'M' -> reverseIndex();
                case 'c' -> fullReset();
                case '=' -> appKeypad = true;
                case '>' -> appKeypad = false;
                default -> {
                }
            }
        } else if (im.length() == 1 && "()*+".indexOf(im.charAt(0)) >= 0) {
            int g = "()*+".indexOf(im.charAt(0));
            charsets[g] = switch (f) {
                case '0' -> CHARSET_DEC_GRAPHICS;
                case 'A' -> CHARSET_UK;
                default -> CHARSET_ASCII;
            };
        } else if (im.equals("#") && f == '8') {
            // DECALN: fill the screen with E
            for (Line l : screen()) {
                for (int i = 0; i < cols; i++) l.set(i, 'E', 1, Style.DEFAULT);
                l.setWrapped(false);
            }
            cx = 0;
            cy = 0;
        }
    }

    @Override
    public void csiDispatch(String prefix, Params p, String im, int f) {
        if (prefix.equals("?")) {
            privateCsi(p, im, f);
            return;
        }
        if (prefix.equals(">")) {
            if (f == 'c' && im.isEmpty()) listener.respond("\u001b[>1;10;0c");
            return; // modifyOtherKeys (>m) and the like
        }
        if (!prefix.isEmpty()) return;
        if (!im.isEmpty()) {
            if (im.equals(" ") && f == 'q') {
                cursorStyle = Math.min(6, p.get(0, 0));
            } else if (im.equals("!") && f == 'p') {
                softReset();
            } else if (im.equals("$") && f == 'p') {
                reportMode(false, p.get(0, 0));
            }
            return;
        }
        switch (f) {
            case '@' -> screen()[cy].insertBlanks(cx, p.count(0, 1), style.background());
            case 'A' -> cursorUp(p.count(0, 1));
            case 'B', 'e' -> cursorDown(p.count(0, 1));
            case 'C', 'a' -> cursorForward(p.count(0, 1));
            case 'D' -> cursorBack(p.count(0, 1));
            case 'E' -> {
                cursorDown(p.count(0, 1));
                cx = 0;
            }
            case 'F' -> {
                cursorUp(p.count(0, 1));
                cx = 0;
            }
            case 'G', '`' -> moveTo(p.count(0, 1) - 1, cy);
            case 'H', 'f' -> moveToOrigin(p.count(1, 1) - 1, p.count(0, 1) - 1);
            case 'I' -> tab(p.count(0, 1));
            case 'J' -> eraseDisplay(p.get(0, 0));
            case 'K' -> eraseLine(p.get(0, 0));
            case 'L' -> insertLines(p.count(0, 1));
            case 'M' -> deleteLines(p.count(0, 1));
            case 'P' -> screen()[cy].deleteCells(Math.min(cx, cols - 1), p.count(0, 1), style.background());
            case 'S' -> scrollUp(top, bottom, p.count(0, 1));
            case 'T' -> {
                if (p.size() <= 1) scrollDown(top, bottom, p.count(0, 1));
            }
            case 'X' -> {
                int x = Math.min(cx, cols - 1);
                screen()[cy].erase(x, x + p.count(0, 1), style.background());
                wrapPending = false;
            }
            case 'Z' -> backTab(p.count(0, 1));
            case 'b' -> {
                if (lastPrinted >= 0) {
                    int n = Math.min(p.count(0, 1), cols * rows);
                    for (int i = 0; i < n; i++) print(lastPrinted);
                }
            }
            case 'c' -> {
                if (p.get(0, 0) == 0) listener.respond("\u001b[?62;22c");
            }
            case 'd' -> moveToOrigin(cx, p.count(0, 1) - 1);
            case 'g' -> {
                int mode = p.get(0, 0);
                if (mode == 0) tabs[Math.min(cx, cols - 1)] = false;
                else if (mode == 3) Arrays.fill(tabs, false);
            }
            case 'h', 'l' -> {
                boolean on = f == 'h';
                for (int i = 0; i < p.size(); i++) {
                    int m = p.get(i, 0);
                    if (m == 4) insertMode = on;
                    else if (m == 20) newLineMode = on;
                }
            }
            case 'm' -> sgr(p);
            case 'n' -> {
                int m = p.get(0, 0);
                if (m == 5) listener.respond("\u001b[0n");
                else if (m == 6) listener.respond("\u001b[" + (cursorRowForReport() + 1) + ";" + (cursorX() + 1) + "R");
            }
            case 'r' -> {
                int t = p.count(0, 1) - 1;
                int b = p.count(1, rows) - 1;
                if (t < b && b < rows) {
                    top = t;
                    bottom = b;
                    moveToOrigin(0, 0);
                }
            }
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 't' -> windowOps(p);
            default -> {
            }
        }
    }

    private int cursorRowForReport() {
        return originMode ? cy - top : cy;
    }

    private void privateCsi(Params p, String im, int f) {
        if (im.equals("$") && f == 'p') {
            reportMode(true, p.get(0, 0));
            return;
        }
        if (!im.isEmpty()) return;
        switch (f) {
            case 'h', 'l' -> {
                for (int i = 0; i < p.size(); i++) setPrivateMode(p.get(i, 0), f == 'h');
            }
            case 'n' -> {
                if (p.get(0, 0) == 6) {
                    listener.respond("\u001b[?" + (cursorRowForReport() + 1) + ";" + (cursorX() + 1) + "R");
                }
            }
            case 'J' -> eraseDisplay(p.get(0, 0));
            case 'K' -> eraseLine(p.get(0, 0));
            default -> {
            }
        }
    }

    private void setPrivateMode(int mode, boolean on) {
        switch (mode) {
            case 1 -> appCursorKeys = on;
            case 3 -> {
                // DECCOLM: the window keeps its size, but the screen is cleared as on a VT100
                eraseDisplay(2);
                top = 0;
                bottom = rows - 1;
                moveToOrigin(0, 0);
            }
            case 5 -> reverseVideo = on;
            case 6 -> {
                originMode = on;
                moveToOrigin(0, 0);
            }
            case 7 -> {
                autoWrap = on;
                if (!on) wrapPending = false;
            }
            case 12 -> cursorBlinkMode = on;
            case 25 -> cursorVisible = on;
            case 9 -> mouseMode = on ? MouseMode.X10 : MouseMode.NONE;
            case 1000 -> mouseMode = on ? MouseMode.NORMAL : MouseMode.NONE;
            case 1002 -> mouseMode = on ? MouseMode.BUTTON : MouseMode.NONE;
            case 1003 -> mouseMode = on ? MouseMode.ANY : MouseMode.NONE;
            case 1004 -> focusEvents = on;
            case 1005 -> mouseEncoding = on ? MouseEncoding.UTF8 : MouseEncoding.DEFAULT;
            case 1006 -> mouseEncoding = on ? MouseEncoding.SGR : MouseEncoding.DEFAULT;
            case 1015 -> mouseEncoding = on ? MouseEncoding.URXVT : MouseEncoding.DEFAULT;
            case 1007 -> altScroll = on;
            case 47, 1047 -> switchScreen(on, mode == 1047 && !on);
            case 1048 -> {
                if (on) saveCursor();
                else restoreCursor();
            }
            case 1049 -> {
                if (on) {
                    saveCursor();
                    switchScreen(true, false);
                    clearScreen(altLines);
                } else {
                    switchScreen(false, false);
                    restoreCursor();
                }
            }
            case 2004 -> bracketedPaste = on;
            default -> {
            }
        }
    }

    /** DECRQM: tells whether a mode is set (1), reset (2) or unknown (0). */
    private void reportMode(boolean dec, int mode) {
        int state;
        if (dec) {
            Boolean v = switch (mode) {
                case 1 -> appCursorKeys;
                case 5 -> reverseVideo;
                case 6 -> originMode;
                case 7 -> autoWrap;
                case 12 -> cursorBlinkMode;
                case 25 -> cursorVisible;
                case 1000 -> mouseMode == MouseMode.NORMAL;
                case 1002 -> mouseMode == MouseMode.BUTTON;
                case 1003 -> mouseMode == MouseMode.ANY;
                case 1004 -> focusEvents;
                case 1006 -> mouseEncoding == MouseEncoding.SGR;
                case 1049, 1047, 47 -> altActive;
                case 2004 -> bracketedPaste;
                default -> null;
            };
            state = v == null ? 0 : v ? 1 : 2;
            listener.respond("\u001b[?" + mode + ";" + state + "$y");
        } else {
            state = mode == 4 ? (insertMode ? 1 : 2) : mode == 20 ? (newLineMode ? 1 : 2) : 0;
            listener.respond("\u001b[" + mode + ";" + state + "$y");
        }
    }

    private void windowOps(Params p) {
        switch (p.get(0, 0)) {
            case 18 -> listener.respond("\u001b[8;" + rows + ";" + cols + "t");
            case 22 -> {
                if (titleStack.size() < 10) titleStack.push(title);
            }
            case 23 -> {
                if (!titleStack.isEmpty()) setTitle(titleStack.pop());
            }
            default -> {
            }
        }
    }

    @Override
    public void oscDispatch(String data) {
        int semi = data.indexOf(';');
        String number = semi < 0 ? data : data.substring(0, semi);
        String arg = semi < 0 ? "" : data.substring(semi + 1);
        int code;
        try {
            code = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return;
        }
        switch (code) {
            case 0, 2 -> setTitle(arg);
            case 4 -> osc4(arg);
            case 7 -> setDirectory(arg);
            case 8 -> osc8(arg);
            case 10, 11, 12 -> {
                if (arg.equals("?")) {
                    int which = code == 10 ? TerminalListener.FOREGROUND
                            : code == 11 ? TerminalListener.BACKGROUND : TerminalListener.CURSOR;
                    listener.respond("\u001b]" + code + ";" + xcolor(listener.color(which)) + "\u001b\\");
                }
            }
            case 52 -> osc52(arg);
            case 104 -> {
                if (arg.isEmpty()) palette.clear();
                else for (String s : arg.split(";")) palette.remove(parseInt(s, -1));
                changes++;
            }
            default -> {
            }
        }
    }

    private void osc4(String arg) {
        String[] parts = arg.split(";");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            int index = parseInt(parts[i], -1);
            if (index < 0 || index > 255) continue;
            if (parts[i + 1].equals("?")) {
                int rgb = palette.getOrDefault(index, index < 16 ? listener.color(index) : Attr.paletteRgb(index));
                listener.respond("\u001b]4;" + index + ";" + xcolor(rgb) + "\u001b\\");
            } else {
                int rgb = parseColor(parts[i + 1]);
                if (rgb >= 0) palette.put(index, rgb);
            }
        }
        changes++;
    }

    private void osc8(String arg) {
        int semi = arg.indexOf(';');
        if (semi < 0) return;
        String uri = arg.substring(semi + 1);
        if (uri.isEmpty()) {
            style = style.withLink(0);
            return;
        }
        Integer id = linkIds.get(uri);
        if (id == null) {
            if (links.size() > 50_000) {
                links.clear();
                linkIds.clear();
            }
            id = links.size() + 1;
            links.put(id, uri);
            linkIds.put(uri, id);
        }
        style = style.withLink(id);
    }

    private void osc52(String arg) {
        int semi = arg.indexOf(';');
        if (semi < 0 || !allowClipboard) return;
        String data = arg.substring(semi + 1);
        if (data.equals("?")) return; // reading the clipboard is never allowed
        try {
            listener.clipboardSet(new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            // not base64: ignored
        }
    }

    private void setDirectory(String uri) {
        if (uri.isEmpty()) return;
        try {
            String path = uri.startsWith("file:") ? URI.create(uri).getPath() : uri;
            if (path != null && path.startsWith("/")) {
                directory = path;
                listener.directoryChanged(path);
            }
        } catch (IllegalArgumentException e) {
            // ignored
        }
    }

    private void setTitle(String t) {
        if (t.length() > 1024) t = t.substring(0, 1024);
        title = t;
        listener.titleChanged(t);
    }

    @Override
    public void dcsDispatch(String prefix, Params params, String im, int f, String data) {
        // DECRQSS for the cursor style and SGR, which some programs ask for; everything else is ignored
        if (prefix.isEmpty() && im.equals("$") && f == 'q') {
            if (data.equals(" q")) listener.respond("\u001bP1$r" + cursorStyle + " q\u001b\\");
            else if (data.equals("r")) listener.respond("\u001bP1$r" + (top + 1) + ";" + (bottom + 1) + "r\u001b\\");
            else listener.respond("\u001bP0$r\u001b\\");
        }
    }

    // ================================================================ SGR

    private void sgr(Params p) {
        if (p.size() == 0) {
            style = style.reset();
            return;
        }
        for (int i = 0; i < p.size(); i++) {
            int v = p.get(i, 0);
            // sub-parameters belong to the one before; skip the ones we do not use
            switch (v) {
                case 0 -> style = style.reset();
                case 1 -> style = style.with(Attr.BOLD);
                case 2 -> style = style.with(Attr.DIM);
                case 3 -> style = style.with(Attr.ITALIC);
                case 4 -> {
                    if (p.isSub(i + 1)) {
                        int kind = p.get(i + 1, 1);
                        style = style.without(Attr.UNDERLINE | Attr.DOUBLE_UNDERLINE);
                        if (kind == 2) style = style.with(Attr.DOUBLE_UNDERLINE);
                        else if (kind != 0) style = style.with(Attr.UNDERLINE);
                        i = skipSubs(p, i);
                    } else {
                        style = style.with(Attr.UNDERLINE);
                    }
                }
                case 5, 6 -> style = style.with(Attr.BLINK);
                case 7 -> style = style.with(Attr.INVERSE);
                case 8 -> style = style.with(Attr.HIDDEN);
                case 9 -> style = style.with(Attr.STRIKE);
                case 21 -> style = style.with(Attr.DOUBLE_UNDERLINE);
                case 22 -> style = style.without(Attr.BOLD | Attr.DIM);
                case 23 -> style = style.without(Attr.ITALIC);
                case 24 -> style = style.without(Attr.UNDERLINE | Attr.DOUBLE_UNDERLINE);
                case 25 -> style = style.without(Attr.BLINK);
                case 27 -> style = style.without(Attr.INVERSE);
                case 28 -> style = style.without(Attr.HIDDEN);
                case 29 -> style = style.without(Attr.STRIKE);
                case 39 -> style = style.withForeground(Attr.DEFAULT);
                case 49 -> style = style.withBackground(Attr.DEFAULT);
                case 38, 48, 58 -> {
                    int[] color = new int[1];
                    i = extendedColor(p, i, color);
                    if (color[0] != Integer.MIN_VALUE) {
                        if (v == 38) style = style.withForeground(color[0]);
                        else if (v == 48) style = style.withBackground(color[0]);
                    }
                }
                default -> {
                    if (v >= 30 && v <= 37) style = style.withForeground(v - 30);
                    else if (v >= 40 && v <= 47) style = style.withBackground(v - 40);
                    else if (v >= 90 && v <= 97) style = style.withForeground(v - 90 + 8);
                    else if (v >= 100 && v <= 107) style = style.withBackground(v - 100 + 8);
                    else i = skipSubs(p, i);
                }
            }
        }
    }

    private static int skipSubs(Params p, int i) {
        while (p.isSub(i + 1)) i++;
        return i;
    }

    /**
     * Reads {@code 38;5;n}, {@code 38;2;r;g;b} or the colon forms {@code 38:5:n}, {@code 38:2::r:g:b},
     * {@code 38:2:r:g:b}; returns the index of the last parameter used.
     */
    private static int extendedColor(Params p, int i, int[] out) {
        out[0] = Integer.MIN_VALUE;
        if (p.isSub(i + 1)) {
            int end = skipSubs(p, i);
            int kind = p.get(i + 1, -1);
            int n = end - i; // number of sub-parameters
            if (kind == 5 && n >= 2) {
                out[0] = clamp255(p.get(i + 2, 0));
            } else if (kind == 2) {
                // with a colour space id (38:2:id:r:g:b) there are 5 sub-parameters, without it 4
                int first = n >= 5 ? i + 3 : i + 2;
                if (n >= 4) out[0] = Attr.rgb(clamp255(p.get(first, 0)) << 16 | clamp255(p.get(first + 1, 0)) << 8
                        | clamp255(p.get(first + 2, 0)));
            }
            return end;
        }
        int kind = p.get(i + 1, -1);
        if (kind == 5) {
            if (i + 2 < p.size()) out[0] = clamp255(p.get(i + 2, 0));
            return i + 2;
        }
        if (kind == 2) {
            if (i + 4 < p.size()) {
                out[0] = Attr.rgb(clamp255(p.get(i + 2, 0)) << 16 | clamp255(p.get(i + 3, 0)) << 8
                        | clamp255(p.get(i + 4, 0)));
            }
            return i + 4;
        }
        return i + 1;
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ================================================================ cursor and screen operations

    private Line[] screen() {
        return altActive ? altLines : mainLines;
    }

    private void moveTo(int x, int y) {
        cx = Math.max(0, Math.min(cols - 1, x));
        cy = Math.max(0, Math.min(rows - 1, y));
        wrapPending = false;
    }

    /** CUP and VPA: with origin mode the rows count from the top of the scroll region and stay inside it. */
    private void moveToOrigin(int x, int y) {
        if (originMode) {
            cx = Math.max(0, Math.min(cols - 1, x));
            cy = Math.max(top, Math.min(bottom, y + top));
            wrapPending = false;
        } else {
            moveTo(x, y);
        }
    }

    private void cursorUp(int n) {
        int limit = cy >= top ? top : 0;
        cy = Math.max(limit, cy - n);
        wrapPending = false;
    }

    private void cursorDown(int n) {
        int limit = cy <= bottom ? bottom : rows - 1;
        cy = Math.min(limit, cy + n);
        wrapPending = false;
    }

    private void cursorForward(int n) {
        cx = Math.min(cols - 1, cx + n);
        wrapPending = false;
    }

    private void cursorBack(int n) {
        cx = Math.max(0, Math.min(cx, cols - 1) - n);
        wrapPending = false;
    }

    private void tab(int n) {
        int x = Math.min(cx, cols - 1);
        for (int k = 0; k < n; k++) {
            x++;
            while (x < cols - 1 && !tabs[x]) x++;
            if (x >= cols - 1) {
                x = cols - 1;
                break;
            }
        }
        cx = x;
        wrapPending = false;
    }

    private void backTab(int n) {
        int x = Math.min(cx, cols - 1);
        for (int k = 0; k < n && x > 0; k++) {
            x--;
            while (x > 0 && !tabs[x]) x--;
        }
        cx = x;
        wrapPending = false;
    }

    private void resetTabs() {
        tabs = new boolean[cols];
        for (int i = 8; i < cols; i += 8) tabs[i] = true;
    }

    /** LF: down one row, scrolling the region at its bottom. */
    private void index() {
        wrapPending = false;
        if (cy == bottom) scrollUp(top, bottom, 1);
        else if (cy < rows - 1) cy++;
    }

    private void newLine() {
        if (cy == bottom) scrollUp(top, bottom, 1);
        else if (cy < rows - 1) cy++;
    }

    private void reverseIndex() {
        wrapPending = false;
        if (cy == top) scrollDown(top, bottom, 1);
        else if (cy > 0) cy--;
    }

    private void scrollUp(int from, int to, int n) {
        Line[] screen = screen();
        n = Math.min(n, to - from + 1);
        boolean keep = !altActive && from == 0;
        for (int i = 0; i < n; i++) {
            Line gone = screen[from];
            if (keep) {
                scrolledIntoHistory++;
                if (history.add(gone)) droppedFromHistory++;
            }
            System.arraycopy(screen, from + 1, screen, from, to - from);
            screen[to] = new Line(cols, style.background());
        }
    }

    private void scrollDown(int from, int to, int n) {
        Line[] screen = screen();
        n = Math.min(n, to - from + 1);
        for (int i = 0; i < n; i++) {
            System.arraycopy(screen, from, screen, from + 1, to - from);
            screen[from] = new Line(cols, style.background());
        }
    }

    private void insertLines(int n) {
        if (cy < top || cy > bottom) return;
        scrollDown(cy, bottom, n);
        cx = 0;
        wrapPending = false;
    }

    private void deleteLines(int n) {
        if (cy < top || cy > bottom) return;
        Line[] screen = screen();
        n = Math.min(n, bottom - cy + 1);
        for (int i = 0; i < n; i++) {
            System.arraycopy(screen, cy + 1, screen, cy, bottom - cy);
            screen[bottom] = new Line(cols, style.background());
        }
        cx = 0;
        wrapPending = false;
    }

    private void eraseDisplay(int mode) {
        Line[] screen = screen();
        int bg = style.background();
        switch (mode) {
            case 0 -> {
                eraseLine(0);
                for (int r = cy + 1; r < rows; r++) screen[r] = new Line(cols, bg);
            }
            case 1 -> {
                for (int r = 0; r < cy; r++) screen[r] = new Line(cols, bg);
                eraseLine(1);
            }
            case 2 -> {
                // like other terminals, keep what was on the screen: push it into the scrollback first
                if (!altActive) {
                    int used = 0;
                    for (int r = 0; r < rows; r++) if (!screen[r].isBlank()) used = r + 1;
                    for (int r = 0; r < used; r++) {
                        scrolledIntoHistory++;
                        if (history.add(screen[r])) droppedFromHistory++;
                    }
                }
                for (int r = 0; r < rows; r++) screen[r] = new Line(cols, bg);
            }
            case 3 -> clearHistory();
            default -> {
            }
        }
        wrapPending = false;
    }

    private void eraseLine(int mode) {
        Line line = screen()[cy];
        int x = Math.min(cx, cols - 1);
        int bg = style.background();
        switch (mode) {
            case 0 -> {
                line.erase(x, cols, bg);
                line.setWrapped(false);
            }
            case 1 -> line.erase(0, x + 1, bg);
            case 2 -> {
                line.erase(0, cols, bg);
                line.setWrapped(false);
            }
            default -> {
            }
        }
        wrapPending = false;
    }

    private void clearScreen(Line[] lines) {
        for (int r = 0; r < lines.length; r++) lines[r] = new Line(cols, style.background());
    }

    private void switchScreen(boolean alt, boolean clearOnLeave) {
        if (alt == altActive) return;
        if (!alt && clearOnLeave) clearScreen(altLines);
        altActive = alt;
        wrapPending = false;
        changes++;
    }

    private void saveCursor() {
        Saved s = new Saved(cx, cy, style, wrapPending, originMode, charsets.clone(), gl, autoWrap);
        if (altActive) savedAlt = s;
        else savedMain = s;
    }

    private void restoreCursor() {
        Saved s = altActive ? savedAlt : savedMain;
        if (s == null) {
            moveTo(0, 0);
            style = Style.DEFAULT;
            originMode = false;
            return;
        }
        cx = Math.min(s.x, cols - 1);
        cy = Math.min(s.y, rows - 1);
        style = s.style;
        wrapPending = s.wrapPending;
        originMode = s.origin;
        System.arraycopy(s.charsets, 0, charsets, 0, 4);
        gl = s.gl;
    }

    private void softReset() {
        cursorVisible = true;
        top = 0;
        bottom = rows - 1;
        insertMode = false;
        originMode = false;
        autoWrap = true;
        appCursorKeys = false;
        appKeypad = false;
        style = Style.DEFAULT;
        Arrays.fill(charsets, CHARSET_ASCII);
        gl = 0;
        savedMain = null;
        savedAlt = null;
        wrapPending = false;
    }

    /** RIS, and Terminal > Reset: everything back to the start, the scrollback stays. */
    public void fullReset() {
        softReset();
        if (altActive) altActive = false;
        clearScreen(altLines);
        clearScreen(mainLines);
        cx = 0;
        cy = 0;
        newLineMode = false;
        reverseVideo = false;
        cursorBlinkMode = false;
        cursorStyle = 0;
        bracketedPaste = false;
        focusEvents = false;
        altScroll = true;
        mouseMode = MouseMode.NONE;
        mouseEncoding = MouseEncoding.DEFAULT;
        palette.clear();
        resetTabs();
        parser.reset();
        utf8.reset();
        changes++;
    }

    // ================================================================ helpers

    private static Line[] blankLines(int rows, int cols) {
        Line[] l = new Line[rows];
        for (int i = 0; i < rows; i++) l[i] = new Line(cols);
        return l;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code rgb:rrrr/gggg/bbbb}, the form xterm answers colour queries with. */
    static String xcolor(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return String.format("rgb:%02x%02x/%02x%02x/%02x%02x", r, r, g, g, b, b);
    }

    /** {@code #rrggbb} or {@code rgb:r/g/b} (1 to 4 hex digits each); -1 if neither. */
    static int parseColor(String s) {
        try {
            if (s.startsWith("#") && s.length() == 7) return Integer.parseInt(s.substring(1), 16);
            if (s.startsWith("rgb:")) {
                String[] c = s.substring(4).split("/");
                if (c.length != 3) return -1;
                int v = 0;
                for (String part : c) {
                    if (part.isEmpty() || part.length() > 4) return -1;
                    int x = Integer.parseInt(part, 16);
                    int max = (1 << (4 * part.length())) - 1;
                    v = v << 8 | Math.round(x * 255f / max);
                }
                return v;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return -1;
    }
}
