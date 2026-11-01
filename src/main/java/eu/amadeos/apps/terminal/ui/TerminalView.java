package eu.amadeos.apps.terminal.ui;

import eu.amadeos.apps.terminal.domain.CursorShape;
import eu.amadeos.apps.terminal.domain.Palette;
import eu.amadeos.apps.terminal.domain.ShellCommand;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import eu.amadeos.apps.terminal.emulator.Attr;
import eu.amadeos.apps.terminal.emulator.KeyEncoder;
import eu.amadeos.apps.terminal.emulator.KeyEncoder.Key;
import eu.amadeos.apps.terminal.emulator.Line;
import eu.amadeos.apps.terminal.emulator.MouseEncoder;
import eu.amadeos.apps.terminal.emulator.TerminalEmulator;
import eu.amadeos.apps.terminal.emulator.TextModel;
import eu.amadeos.apps.terminal.emulator.TextModel.Range;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The terminal screen: paints the emulator's cells with a monospace font and turns keys, mouse and pasted text
 * into bytes for the program. Selecting text copies it to the PRIMARY selection (middle click pastes it);
 * with mouse reporting on (htop, mc), Shift lets the mouse select anyway.
 */
public final class TerminalView extends JComponent {
    /** What the view needs from its tab. */
    public interface Host {
        /** Bytes for the program. */
        void send(byte[] bytes);

        /** Text to paste (from a key, the menu or the middle button), not yet checked or framed. */
        void paste(String text, boolean fromSelection);

        void openLink(String target);

        void showMenu(MouseEvent e);

        /** The view has a new size in cells. */
        void sizeChanged(int cols, int rows, int widthPixels, int heightPixels);

        /** The view scrolled or new output arrived (the scrollbar follows). */
        void viewChanged();

        /** Ctrl+wheel: bigger (+1) or smaller (-1) font. */
        void zoom(int direction);
    }

    static final int PAD = 4;
    private static final int BLINK_MS = 530;

    private final TerminalEmulator emu;
    private final Host host;
    private Set<KeyStroke> reserved = Set.of();
    private TerminalSettings settings;
    private Palette palette;
    private Font plain;
    private Font bold;
    private Font italic;
    private Font boldItalic;
    private int cw = 8;
    private int ch = 16;
    private int ascent = 12;
    private final Map<Integer, Color> colors = new HashMap<>();
    private final Map<Integer, Font> fallbacks = new HashMap<>();

    private int scrollOffset;
    private long lastChanges = -1;
    private long lastScrolled;

    // selection: global lines (emulator line + dropped lines) so it stays on its text while output arrives
    private long anchorLine = -1;
    private int anchorCol;
    private long headLine;
    private int headCol;
    private int unit; // 1 char, 2 word, 3 line
    private boolean selecting;

    private List<Range> hits = List.of();
    private long hitsDropped;
    private int currentHit = -1;
    private TextModel.Link hoverLink;
    private long hoverDropped;

    private boolean blinkOn = true;
    private boolean focused;
    private long flashUntil;
    private boolean suppressTyped;
    private char highSurrogate;
    private int lastReportCol = -1;
    private int lastReportRow = -1;
    private int pressedButton = -1;
    private Point dragPoint;

    private final Timer refresh;
    private final Timer blink;
    private final Timer autoScroll;

    public TerminalView(TerminalEmulator emu, TerminalSettings settings, Palette palette, Host host) {
        this.emu = emu;
        this.host = host;
        setOpaque(true);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setName("terminal");
        apply(settings, palette);

        refresh = new Timer(16, e -> poll());
        refresh.start();
        blink = new Timer(BLINK_MS, e -> {
            blinkOn = !blinkOn;
            repaintCursor();
        });
        blink.start();
        autoScroll = new Timer(60, e -> dragScroll());

        addKeyListener(new Keys());
        Mouse mouse = new Mouse();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                focused = true;
                blinkOn = true;
                if (emu.focusEvents()) host.send(bytes(KeyEncoder.focus(true)));
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                focused = false;
                if (emu.focusEvents()) host.send(bytes(KeyEncoder.focus(false)));
                repaint();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                host.sizeChanged(columnsFor(getWidth()), rowsFor(getHeight()), getWidth(), getHeight());
            }
        });
        setTransferHandler(new Drop());
    }

    // ================================================================ settings

    /** New preferences or colours: fonts, cell size and colours are worked out again. */
    public void apply(TerminalSettings newSettings, Palette newPalette) {
        settings = newSettings;
        palette = newPalette;
        colors.clear();
        plain = new Font(newSettings.fontFamily(), Font.PLAIN, newSettings.fontSize());
        // Java answers an unknown family with Dialog, which is not monospaced
        if (plain.getFamily().equals(Font.DIALOG) && !newSettings.fontFamily().equalsIgnoreCase(Font.DIALOG)) {
            plain = new Font(Font.MONOSPACED, Font.PLAIN, newSettings.fontSize());
        }
        fallbacks.clear();
        bold = plain.deriveFont(Font.BOLD);
        italic = plain.deriveFont(Font.ITALIC);
        boldItalic = plain.deriveFont(Font.BOLD | Font.ITALIC);
        FontMetrics fm = getFontMetrics(plain);
        cw = Math.max(1, fm.charWidth('M'));
        ch = Math.max(1, fm.getHeight());
        ascent = fm.getAscent();
        setBackground(color(palette.background()));
        setPreferredSize(new Dimension(newSettings.columns() * cw + 2 * PAD, newSettings.rows() * ch + 2 * PAD));
        revalidate();
        repaint();
    }

    /** Keys the window's menus use (Ctrl+Shift+C ...); the view leaves them alone. */
    public void setReservedKeys(Set<KeyStroke> keys) {
        reserved = Set.copyOf(keys);
    }

    public int cellWidth() {
        return cw;
    }

    public int cellHeight() {
        return ch;
    }

    public int columnsFor(int width) {
        return Math.max(2, (width - 2 * PAD) / cw);
    }

    public int rowsFor(int height) {
        return Math.max(1, (height - 2 * PAD) / ch);
    }

    /** The size in pixels for a number of columns and rows. */
    public Dimension sizeFor(int cols, int rows) {
        return new Dimension(cols * cw + 2 * PAD, rows * ch + 2 * PAD);
    }

    public void stop() {
        refresh.stop();
        blink.stop();
        autoScroll.stop();
    }

    // ================================================================ output and scrolling

    // --8<-- [start:poll]
    private void poll() {
        long changes;
        synchronized (emu) {
            changes = emu.changes();
            if (changes == lastChanges) return;
            long scrolled = emu.scrolledIntoHistory();
            if (scrollOffset > 0) {
                scrollOffset = (int) Math.min(emu.historySize(), scrollOffset + (scrolled - lastScrolled));
            }
            if (emu.isAlternateScreen()) scrollOffset = 0;
            lastScrolled = scrolled;
        }
        lastChanges = changes;
        blinkOn = true;
        repaint();
        host.viewChanged();
    }
    // --8<-- [end:poll]

    /** Lines scrolled back from the live screen (0 = following the output). */
    public int scrollOffset() {
        return scrollOffset;
    }

    public void scrollTo(int offset) {
        synchronized (emu) {
            scrollOffset = Math.max(0, Math.min(emu.historySize(), offset));
        }
        repaint();
        host.viewChanged();
    }

    public void scrollBy(int lines) {
        scrollTo(scrollOffset + lines);
    }

    public void scrollToBottom() {
        if (scrollOffset != 0) scrollTo(0);
    }

    public void scrollToTop() {
        synchronized (emu) {
            scrollTo(emu.historySize());
        }
    }

    public void pageUp() {
        scrollBy(Math.max(1, emu.rows() - 1));
    }

    public void pageDown() {
        scrollBy(-Math.max(1, emu.rows() - 1));
    }

    /** Shows the bell: a short flash. */
    public void flash() {
        flashUntil = System.currentTimeMillis() + 120;
        repaint();
        new Timer(130, e -> {
            ((Timer) e.getSource()).stop();
            repaint();
        }).start();
    }

    // ================================================================ selection

    public boolean hasSelection() {
        Range r = selection();
        return r != null && (r.line() != r.endLine() || r.col() != r.endCol());
    }

    /** The selected text, or "". */
    public String selectedText() {
        synchronized (emu) {
            Range r = selection();
            if (r == null) return "";
            return TextModel.text(emu, r.line(), r.col(), r.endLine(), r.endCol());
        }
    }

    public void selectAll() {
        synchronized (emu) {
            long dropped = emu.droppedFromHistory();
            anchorLine = dropped;
            anchorCol = 0;
            int last = emu.historySize() + emu.rows() - 1;
            while (last > 0 && emu.line(last).isBlank()) last--;
            headLine = dropped + last;
            headCol = emu.columns();
            unit = 1;
        }
        copyToPrimary();
        repaint();
    }

    public void clearSelection() {
        anchorLine = -1;
        repaint();
    }

    /** The selection as emulator lines, normalized, or null. Call with the emulator locked. */
    Range selection() {
        if (anchorLine < 0) return null;
        long dropped = emu.droppedFromHistory();
        int total = emu.historySize() + emu.rows();
        int al = (int) Math.max(0, Math.min(total - 1, anchorLine - dropped));
        int hl = (int) Math.max(0, Math.min(total - 1, headLine - dropped));
        int ac = anchorCol, hc = headCol;
        if (anchorLine - dropped < 0) ac = 0;
        boolean forward = al < hl || (al == hl && ac <= hc);
        int sl = forward ? al : hl, sc = forward ? ac : hc, el = forward ? hl : al, ec = forward ? hc : ac;
        if (unit == 2) {
            Range a = TextModel.word(emu, sl, Math.min(sc, emu.columns() - 1));
            Range b = TextModel.word(emu, el, Math.max(0, Math.min(ec - 1, emu.columns() - 1)));
            if (forward && al == hl && ac == hc) b = a;
            return new Range(a.line(), a.col(), b.endLine(), b.endCol());
        }
        if (unit == 3) {
            Range a = TextModel.wholeLine(emu, sl);
            Range b = TextModel.wholeLine(emu, el);
            return new Range(a.line(), 0, b.endLine(), b.endCol());
        }
        return new Range(sl, sc, el, ec);
    }

    private void copyToPrimary() {
        String text = selectedText();
        if (text.isEmpty()) return;
        Clipboard primary = Toolkit.getDefaultToolkit().getSystemSelection();
        if (primary != null) primary.setContents(new StringSelection(text), null);
    }

    /** Edit > Copy. */
    public void copy() {
        String text = selectedText();
        if (!text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }

    // ================================================================ search hits

    /** Marks search hits; {@code current} is scrolled into view. */
    public void setHits(List<Range> newHits, int current) {
        synchronized (emu) {
            hits = List.copyOf(newHits);
            hitsDropped = emu.droppedFromHistory();
            currentHit = current;
            if (current >= 0 && current < hits.size()) {
                int line = hits.get(current).line();
                int top = emu.historySize() - scrollOffset;
                if (line < top || line >= top + emu.rows()) {
                    scrollOffset = Math.max(0, Math.min(emu.historySize(), emu.historySize() - line + emu.rows() / 2));
                }
            }
        }
        repaint();
        host.viewChanged();
    }

    // ================================================================ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        synchronized (emu) {
            boolean reverse = emu.reverseVideo();
            Color defaultBg = color(reverse ? palette.foreground() : palette.background());
            g.setColor(defaultBg);
            g.fillRect(0, 0, getWidth(), getHeight());
            int rows = emu.rows();
            int cols = emu.columns();
            int h = emu.historySize();
            int top = h - scrollOffset;
            Range sel = selection();
            long dropped = emu.droppedFromHistory();
            int hitShift = (int) (dropped - hitsDropped);
            int linkShift = (int) (dropped - hoverDropped);
            for (int r = 0; r < rows; r++) {
                int lineIndex = top + r;
                if (lineIndex < 0 || lineIndex >= h + rows) continue;
                Line line = emu.line(lineIndex);
                int y = PAD + r * ch;
                int n = Math.min(cols, line.columns());
                for (int c = 0; c < n; c++) {
                    int cp = line.codePoint(c);
                    if (cp == Line.WIDE_TAIL) continue;
                    int width = line.isWide(c) ? 2 : 1;
                    int x = PAD + c * cw;
                    int attr = line.attributes(c);
                    int fg = foreground(line.foreground(c), attr, reverse);
                    int bg = background(line.background(c), reverse);
                    if ((attr & Attr.INVERSE) != 0) {
                        int t = fg;
                        fg = bg;
                        bg = t;
                    }
                    boolean selected = sel != null && sel.contains(lineIndex, c);
                    int hit = hitAt(lineIndex + hitShift, c);
                    if (selected) {
                        bg = palette.selectionBackground();
                        fg = palette.selectionForeground();
                    } else if (hit >= 0) {
                        bg = hit == currentHit ? palette.searchHit() : blend(palette.searchHit(), palette.background(), 0.5);
                        fg = hit == currentHit ? 0x000000 : palette.foreground();
                    }
                    if (bg != (defaultBg.getRGB() & 0xFFFFFF)) {
                        g.setColor(color(bg));
                        g.fillRect(x, y, cw * width, ch);
                    }
                    if ((attr & Attr.DIM) != 0) fg = blend(fg, bg, 0.5);
                    boolean hidden = (attr & Attr.HIDDEN) != 0;
                    if (cp != Line.EMPTY && cp != ' ' && !hidden) {
                        g.setColor(color(fg));
                        if (BoxDrawing.handles(cp)) {
                            BoxDrawing.draw(g, cp, x, y, cw * width, ch);
                        } else {
                            String text = Character.toString(cp);
                            String comb = line.combining(c);
                            if (comb != null) text += comb;
                            g.setFont(font(attr, cp));
                            g.drawString(text, x, y + ascent);
                        }
                    }
                    boolean linkHere = hoverLink != null && hoverLink.range().contains(lineIndex + linkShift, c);
                    if ((attr & (Attr.UNDERLINE | Attr.DOUBLE_UNDERLINE)) != 0 || linkHere) {
                        g.setColor(color(fg));
                        int uy = y + ascent + 2;
                        g.fillRect(x, uy, cw * width, 1);
                        if ((attr & Attr.DOUBLE_UNDERLINE) != 0) g.fillRect(x, uy + 2, cw * width, 1);
                    }
                    if ((attr & Attr.STRIKE) != 0) {
                        g.setColor(color(fg));
                        g.fillRect(x, y + ch / 2, cw * width, 1);
                    }
                }
            }
            paintCursor(g, top, h, rows);
        }
        if (System.currentTimeMillis() < flashUntil) {
            Composite c = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
            g.setColor(color(palette.foreground()));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setComposite(c);
        }
    }

    private void paintCursor(Graphics2D g, int top, int h, int rows) {
        if (!emu.cursorVisible()) return;
        int line = h + emu.cursorY();
        if (line < top || line >= top + rows) return;
        int x = PAD + emu.cursorX() * cw;
        int y = PAD + (line - top) * ch;
        Line l = emu.line(line);
        int width = l.isWide(emu.cursorX()) ? 2 : 1;
        CursorShape shape = CursorShape.fromDecscusr(emu.cursorStyle(), settings.cursor());
        boolean blinks = CursorShape.blinksFor(emu.cursorStyle(), settings.cursorBlink()) || emu.cursorBlinkMode();
        g.setColor(color(palette.cursor()));
        if (!focused) {
            g.drawRect(x, y, cw * width - 1, ch - 1);
            return;
        }
        if (blinks && !blinkOn) return;
        switch (shape) {
            case BLOCK -> {
                g.fillRect(x, y, cw * width, ch);
                int cp = l.codePoint(emu.cursorX());
                if (cp != Line.EMPTY && cp != ' ') {
                    g.setColor(color(palette.background()));
                    if (BoxDrawing.handles(cp)) BoxDrawing.draw(g, cp, x, y, cw * width, ch);
                    else {
                        g.setFont(font(l.attributes(emu.cursorX()), cp));
                        g.drawString(Character.toString(cp), x, y + ascent);
                    }
                }
            }
            case UNDERLINE -> g.fillRect(x, y + ch - 2, cw * width, 2);
            case BAR -> g.fillRect(x, y, 2, ch);
        }
    }

    private void repaintCursor() {
        synchronized (emu) {
            int line = emu.historySize() + emu.cursorY();
            int top = emu.historySize() - scrollOffset;
            repaint(0, PAD + (line - top) * ch, getWidth(), ch);
        }
    }

    private int hitAt(int line, int col) {
        for (int i = 0; i < hits.size(); i++) {
            Range r = hits.get(i);
            if (r.line() > line) break;
            if (r.contains(line, col)) return i;
        }
        return -1;
    }

    private Font font(int attr, int cp) {
        boolean b = (attr & Attr.BOLD) != 0, i = (attr & Attr.ITALIC) != 0;
        Font f = b && i ? boldItalic : b ? bold : i ? italic : plain;
        if (cp < 0x80 || f.canDisplay(cp)) return f;
        return fallbacks.computeIfAbsent(f.getStyle(), style -> new Font(Font.MONOSPACED, style, f.getSize()));
    }

    /** The RGB of a foreground colour, bold in bright colours if chosen. */
    int foreground(int color, int attr, boolean reverse) {
        if (color == Attr.DEFAULT) return reverse ? palette.background() : palette.foreground();
        if (settings.brightBold() && (attr & Attr.BOLD) != 0 && color >= 0 && color < 8) color += 8;
        return rgb(color);
    }

    int background(int color, boolean reverse) {
        if (color == Attr.DEFAULT) return reverse ? palette.foreground() : palette.background();
        return rgb(color);
    }

    private int rgb(int color) {
        if (Attr.isRgb(color)) return Attr.rgbValue(color);
        int override = emu.paletteOverride(color);
        if (override >= 0) return override;
        return color < 16 ? palette.ansi(color) : Attr.paletteRgb(color);
    }

    private Color color(int rgb) {
        return colors.computeIfAbsent(rgb & 0xFFFFFF, Color::new);
    }

    /** A colour for the emulator's answers to colour queries (OSC 4, 10, 11, 12). */
    int colorFor(int which) {
        Palette p = palette;
        return switch (which) {
            case eu.amadeos.apps.terminal.emulator.TerminalListener.FOREGROUND -> p.foreground();
            case eu.amadeos.apps.terminal.emulator.TerminalListener.BACKGROUND -> p.background();
            case eu.amadeos.apps.terminal.emulator.TerminalListener.CURSOR -> p.cursor();
            default -> which >= 0 && which < 16 ? p.ansi(which) : 0;
        };
    }

    static int blend(int a, int b, double f) {
        int r = (int) ((a >> 16 & 0xFF) * f + (b >> 16 & 0xFF) * (1 - f));
        int g = (int) ((a >> 8 & 0xFF) * f + (b >> 8 & 0xFF) * (1 - f));
        int bl = (int) ((a & 0xFF) * f + (b & 0xFF) * (1 - f));
        return r << 16 | g << 8 | bl;
    }

    // ================================================================ keyboard

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void type(String s) {
        if (s == null || s.isEmpty()) return;
        scrollToBottom();
        blinkOn = true;
        host.send(bytes(s));
    }

    static int modifiers(InputEvent e) {
        int m = 0;
        if (e.isShiftDown()) m |= KeyEncoder.SHIFT;
        if (e.isAltDown() && !e.isAltGraphDown()) m |= KeyEncoder.ALT;
        if (e.isMetaDown()) m |= KeyEncoder.ALT;
        if (e.isControlDown()) m |= KeyEncoder.CTRL;
        return m;
    }

    static Key key(KeyEvent e) {
        return switch (e.getKeyCode()) {
            case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> Key.UP;
            case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> Key.DOWN;
            case KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> Key.LEFT;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> Key.RIGHT;
            case KeyEvent.VK_HOME -> Key.HOME;
            case KeyEvent.VK_END -> Key.END;
            case KeyEvent.VK_INSERT -> Key.INSERT;
            case KeyEvent.VK_DELETE -> Key.DELETE;
            case KeyEvent.VK_PAGE_UP -> Key.PAGE_UP;
            case KeyEvent.VK_PAGE_DOWN -> Key.PAGE_DOWN;
            case KeyEvent.VK_F1 -> Key.F1;
            case KeyEvent.VK_F2 -> Key.F2;
            case KeyEvent.VK_F3 -> Key.F3;
            case KeyEvent.VK_F4 -> Key.F4;
            case KeyEvent.VK_F5 -> Key.F5;
            case KeyEvent.VK_F6 -> Key.F6;
            case KeyEvent.VK_F7 -> Key.F7;
            case KeyEvent.VK_F8 -> Key.F8;
            case KeyEvent.VK_F9 -> Key.F9;
            case KeyEvent.VK_F10 -> Key.F10;
            case KeyEvent.VK_F11 -> Key.F11;
            case KeyEvent.VK_F12 -> Key.F12;
            case KeyEvent.VK_BACK_SPACE -> Key.BACKSPACE;
            case KeyEvent.VK_TAB -> Key.TAB;
            case KeyEvent.VK_ENTER -> e.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD ? Key.KEYPAD_ENTER : Key.ENTER;
            case KeyEvent.VK_ESCAPE -> Key.ESCAPE;
            default -> null;
        };
    }

    /** The character of a key for Ctrl and Alt combinations, from the key code (Ctrl changes the key char). */
    static int baseChar(KeyEvent e) {
        int code = e.getKeyCode();
        boolean shift = e.isShiftDown();
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) return (shift ? 'A' : 'a') + code - KeyEvent.VK_A;
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) return '0' + code - KeyEvent.VK_0;
        return switch (code) {
            case KeyEvent.VK_SPACE -> ' ';
            case KeyEvent.VK_OPEN_BRACKET -> '[';
            case KeyEvent.VK_CLOSE_BRACKET -> ']';
            case KeyEvent.VK_BACK_SLASH -> '\\';
            case KeyEvent.VK_MINUS -> shift ? '_' : '-';
            case KeyEvent.VK_SLASH -> shift ? '?' : '/';
            case KeyEvent.VK_BACK_QUOTE -> '`';
            case KeyEvent.VK_PERIOD -> '.';
            case KeyEvent.VK_COMMA -> ',';
            case KeyEvent.VK_SEMICOLON -> ';';
            case KeyEvent.VK_EQUALS -> '=';
            default -> {
                char c = e.getKeyChar();
                yield c != KeyEvent.CHAR_UNDEFINED && c >= 0x20 && c != 0x7F ? c : -1;
            }
        };
    }

    private final class Keys implements KeyListener {
        @Override
        public void keyPressed(KeyEvent e) {
            suppressTyped = false;
            KeyStroke ks = KeyStroke.getKeyStrokeForEvent(e);
            if (reserved.contains(ks)) return;
            int code = e.getKeyCode();
            if (e.isShiftDown() && !e.isControlDown() && !e.isAltDown()) {
                switch (code) {
                    case KeyEvent.VK_PAGE_UP -> {
                        pageUp();
                        e.consume();
                        return;
                    }
                    case KeyEvent.VK_PAGE_DOWN -> {
                        pageDown();
                        e.consume();
                        return;
                    }
                    case KeyEvent.VK_HOME -> {
                        if (!emu.isAlternateScreen()) {
                            scrollToTop();
                            e.consume();
                            return;
                        }
                    }
                    case KeyEvent.VK_END -> {
                        if (!emu.isAlternateScreen()) {
                            scrollToBottom();
                            e.consume();
                            return;
                        }
                    }
                    default -> {
                    }
                }
            }
            if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT
                    || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_META || code == KeyEvent.VK_CAPS_LOCK) {
                return;
            }
            Key key = key(e);
            if (key != null) {
                type(KeyEncoder.key(key, modifiers(e), emu.appCursorKeys(), emu.appKeypad()));
                e.consume();
                suppressTyped = true;
                return;
            }
            boolean ctrl = e.isControlDown() && !e.isAltGraphDown();
            boolean alt = (e.isAltDown() && !e.isAltGraphDown()) || e.isMetaDown();
            if (ctrl || alt) {
                int c = ctrl ? baseChar(e) : altChar(e);
                if (c < 0) return;
                int mods = (ctrl ? KeyEncoder.CTRL : 0) | (alt ? KeyEncoder.ALT : 0);
                type(KeyEncoder.withModifiers(c, mods));
                e.consume();
                suppressTyped = true;
            }
        }

        private int altChar(KeyEvent e) {
            char c = e.getKeyChar();
            if (c != KeyEvent.CHAR_UNDEFINED && c >= 0x20 && c != 0x7F) return c;
            return baseChar(e);
        }

        @Override
        public void keyTyped(KeyEvent e) {
            if (suppressTyped) {
                suppressTyped = false;
                e.consume();
                return;
            }
            char c = e.getKeyChar();
            if (c == KeyEvent.CHAR_UNDEFINED || c < 0x20 || c == 0x7F) return;
            if (e.isControlDown() && !e.isAltGraphDown()) return;
            if (Character.isHighSurrogate(c)) {
                highSurrogate = c;
                return;
            }
            String s = Character.isLowSurrogate(c) && highSurrogate != 0
                    ? new String(new char[]{highSurrogate, c}) : String.valueOf(c);
            highSurrogate = 0;
            type(s);
            e.consume();
        }

        @Override
        public void keyReleased(KeyEvent e) {
        }
    }

    // ================================================================ mouse

    private int cellCol(int x, boolean boundary) {
        double c = (x - PAD) / (double) cw;
        int col = boundary ? (int) Math.round(c) : (int) Math.floor(c);
        return Math.max(0, Math.min(emu.columns() - (boundary ? 0 : 1), col));
    }

    private int cellRow(int y) {
        return Math.max(0, Math.min(emu.rows() - 1, (y - PAD) / ch));
    }

    private boolean reporting(MouseEvent e) {
        return emu.mouseMode() != TerminalEmulator.MouseMode.NONE && !e.isShiftDown();
    }

    private void report(MouseEncoder.Action action, int button, MouseEvent e) {
        int col = cellCol(e.getX(), false), row = cellRow(e.getY());
        if ((action == MouseEncoder.Action.DRAG || action == MouseEncoder.Action.MOVE)
                && col == lastReportCol && row == lastReportRow) {
            return;
        }
        lastReportCol = col;
        lastReportRow = row;
        String s = MouseEncoder.encode(emu.mouseMode(), emu.mouseEncoding(), action, button, col, row, modifiers(e));
        if (s != null) {
            boolean latin1 = emu.mouseEncoding() == TerminalEmulator.MouseEncoding.DEFAULT;
            host.send(s.getBytes(latin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
        }
    }

    private static int button(MouseEvent e) {
        return switch (e.getButton()) {
            case MouseEvent.BUTTON2 -> MouseEncoder.MIDDLE;
            case MouseEvent.BUTTON3 -> MouseEncoder.RIGHT;
            default -> MouseEncoder.LEFT;
        };
    }

    private void extendSelection(Point p) {
        synchronized (emu) {
            int row = cellRow(p.y);
            int line = emu.historySize() - scrollOffset + row;
            headLine = emu.droppedFromHistory() + line;
            headCol = cellCol(p.x, true);
        }
        repaint();
    }

    private void dragScroll() {
        if (dragPoint == null) return;
        if (dragPoint.y < 0) scrollBy(1);
        else if (dragPoint.y > getHeight()) scrollBy(-1);
        else return;
        extendSelection(new Point(dragPoint.x, Math.max(0, Math.min(getHeight() - 1, dragPoint.y))));
    }

    private TextModel.Link linkAt(Point p) {
        synchronized (emu) {
            int line = emu.historySize() - scrollOffset + cellRow(p.y);
            return TextModel.linkAt(emu, line, cellCol(p.x, false));
        }
    }

    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            if (e.isPopupTrigger() && !reporting(e)) {
                host.showMenu(e);
                return;
            }
            if (reporting(e)) {
                pressedButton = button(e);
                lastReportCol = -1;
                report(MouseEncoder.Action.PRESS, pressedButton, e);
                return;
            }
            if (SwingUtilities.isMiddleMouseButton(e)) {
                pastePrimary();
                return;
            }
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            if (e.isControlDown()) {
                TextModel.Link link = linkAt(e.getPoint());
                if (link != null) {
                    host.openLink(link.target());
                    return;
                }
            }
            synchronized (emu) {
                int line = emu.historySize() - scrollOffset + cellRow(e.getY());
                long global = emu.droppedFromHistory() + line;
                int clicks = Math.min(3, e.getClickCount());
                if (e.isShiftDown() && anchorLine >= 0 && clicks == 1) {
                    headLine = global;
                    headCol = cellCol(e.getX(), true);
                } else {
                    unit = clicks;
                    anchorLine = global;
                    anchorCol = cellCol(e.getX(), unit == 1);
                    headLine = global;
                    headCol = unit == 1 ? anchorCol : anchorCol + 1;
                }
            }
            selecting = true;
            repaint();
            if (e.getClickCount() >= 2) copyToPrimary();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (pressedButton >= 0) {
                report(MouseEncoder.Action.DRAG, pressedButton, e);
                return;
            }
            if (!selecting) return;
            dragPoint = e.getPoint();
            extendSelection(new Point(e.getX(), Math.max(0, Math.min(getHeight() - 1, e.getY()))));
            if (e.getY() < 0 || e.getY() > getHeight()) {
                if (!autoScroll.isRunning()) autoScroll.start();
            } else {
                autoScroll.stop();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (pressedButton >= 0) {
                report(MouseEncoder.Action.RELEASE, pressedButton, e);
                pressedButton = -1;
                return;
            }
            if (e.isPopupTrigger() && !reporting(e)) {
                host.showMenu(e);
                return;
            }
            autoScroll.stop();
            dragPoint = null;
            if (selecting) {
                selecting = false;
                if (hasSelection()) copyToPrimary();
                else if (unit == 1) clearSelection();
            }
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            if (emu.mouseMode() == TerminalEmulator.MouseMode.ANY && !e.isShiftDown()) {
                report(MouseEncoder.Action.MOVE, 0, e);
            }
            TextModel.Link link = linkAt(e.getPoint());
            if (!java.util.Objects.equals(link, hoverLink)) {
                hoverLink = link;
                hoverDropped = emu.droppedFromHistory();
                setCursor(link != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                setToolTipText(link != null ? link.target() : null);
                repaint();
            }
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (hoverLink != null) {
                hoverLink = null;
                repaint();
            }
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            int notches = e.getWheelRotation();
            if (notches == 0) return;
            if (e.isControlDown()) {
                host.zoom(notches < 0 ? 1 : -1);
                return;
            }
            if (reporting(e)) {
                int b = notches < 0 ? MouseEncoder.WHEEL_UP : MouseEncoder.WHEEL_DOWN;
                for (int i = 0; i < Math.abs(notches); i++) report(MouseEncoder.Action.PRESS, b, e);
                return;
            }
            if (emu.isAlternateScreen()) {
                if (emu.alternateScroll()) {
                    Key k = notches < 0 ? Key.UP : Key.DOWN;
                    String s = KeyEncoder.key(k, 0, emu.appCursorKeys(), false);
                    host.send(bytes(s.repeat(3 * Math.abs(notches))));
                }
                return;
            }
            scrollBy(-3 * notches);
        }
    }

    /** Middle click: the PRIMARY selection (what was selected last, here or in another program). */
    public void pastePrimary() {
        Clipboard primary = Toolkit.getDefaultToolkit().getSystemSelection();
        String text = null;
        try {
            if (primary != null && primary.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                text = (String) primary.getData(DataFlavor.stringFlavor);
            }
        } catch (Exception ex) {
            text = null;
        }
        if (text == null) text = selectedText();
        if (text != null && !text.isEmpty()) host.paste(text, true);
    }

    /** Files dropped from Files become their quoted paths; dropped text is pasted. */
    private final class Drop extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                Transferable t = support.getTransferable();
                if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    type(ShellCommand.quotePaths(files.stream().map(File::toPath).toList()));
                    return true;
                }
                if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    String s = (String) t.getTransferData(DataFlavor.stringFlavor);
                    if (s.startsWith("file://")) {
                        List<Path> paths = s.lines().filter(l -> l.startsWith("file://"))
                                .map(l -> Path.of(java.net.URI.create(l.strip()))).toList();
                        type(ShellCommand.quotePaths(paths));
                    } else {
                        host.paste(s, false);
                    }
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
            return false;
        }
    }

    // ================================================================ for tests

    String rowText(int row) {
        synchronized (emu) {
            return emu.line(emu.historySize() - scrollOffset + row).text().stripTrailing();
        }
    }

    TextModel.Link hoverLink() {
        return hoverLink;
    }

    /** Simulates a selection from cell to cell (screen rows of the current view). */
    void select(int row1, int col1, int row2, int col2) {
        synchronized (emu) {
            long base = emu.droppedFromHistory() + emu.historySize() - scrollOffset;
            anchorLine = base + row1;
            anchorCol = col1;
            headLine = base + row2;
            headCol = col2;
            unit = 1;
        }
        repaint();
    }
}
