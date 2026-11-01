package eu.amadeos.apps.terminal.ui;

import eu.amadeos.apps.terminal.domain.LaunchOptions;
import eu.amadeos.apps.terminal.domain.Palette;
import eu.amadeos.apps.terminal.domain.TabTitles;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import eu.amadeos.apps.terminal.emulator.TerminalEmulator;
import eu.amadeos.apps.terminal.emulator.TerminalListener;
import eu.amadeos.apps.terminal.emulator.TextModel;
import eu.amadeos.apps.terminal.pty.PtySession;
import eu.amadeos.commons.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One tab: a program on its own pseudo terminal, the emulator, the view with its scrollbar and the find bar.
 */
public final class TerminalTab extends JPanel implements TerminalView.Host {
    /** What the tab tells its window. Called on the event thread. */
    public interface Owner {
        void titleChanged(TerminalTab tab);

        void ended(TerminalTab tab);

        void bell(TerminalTab tab);

        void openLink(String target);

        void showMenu(TerminalTab tab, MouseEvent e);

        void paste(TerminalTab tab, String text);

        void zoom(int direction);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TerminalTab.class);

    private final Owner owner;
    private final Messages msg;
    private final TerminalEmulator emu;
    private final TerminalView view;
    private final JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL);
    private final FindBar find;
    private final String fixedTitle;
    private final boolean hold;
    private PtySession session;
    private boolean adjusting;
    private boolean bellMark;
    private boolean ended;
    private int lastCols;
    private int lastRows;

    public TerminalTab(Owner owner, Messages msg, TerminalSettings settings, Palette palette, LaunchOptions options) {
        super(new BorderLayout());
        this.owner = owner;
        this.msg = msg;
        this.fixedTitle = options.title();
        this.hold = options.hold();
        emu = new TerminalEmulator(settings.columns(), settings.rows(), settings.scrollback());
        emu.setAllowClipboard(settings.allowClipboard());
        view = new TerminalView(emu, settings, palette, this);
        find = new FindBar(msg, this::search, this::hideFind);
        find.setVisible(false);
        bar.setName("scrollbar");
        bar.addAdjustmentListener(e -> {
            if (adjusting) return;
            synchronized (emu) {
                view.scrollTo(emu.historySize() - e.getValue());
            }
        });
        JPanel screen = new JPanel(new BorderLayout());
        screen.add(view, BorderLayout.CENTER);
        screen.add(bar, BorderLayout.EAST);
        add(screen, BorderLayout.CENTER);
        add(find, BorderLayout.SOUTH);
        emu.setListener(new Events());
    }

    /** Starts the program; call once. */
    public void start(List<String> command, Path directory, Map<String, String> environment) throws IOException {
        session = PtySession.start(command, directory, environment, emu.columns(), emu.rows(),
                (bytes, n) -> {
                    synchronized (emu) {
                        emu.feed(bytes, 0, n);
                    }
                },
                code -> SwingUtilities.invokeLater(() -> exited(code)));
    }

    /** Shows a message in the terminal (start failures, the end of a held program). */
    public void showMessage(String text) {
        synchronized (emu) {
            emu.feed("\r\n\u001b[1m" + text.replace("\n", "\r\n") + "\u001b[0m\r\n");
        }
    }

    private void exited(int code) {
        if (ended) return;
        if (hold) {
            showMessage(msg.format("tab.ended", code));
            ended = true;
            owner.titleChanged(this);
            return;
        }
        ended = true;
        owner.ended(this);
    }

    public TerminalView view() {
        return view;
    }

    public TerminalEmulator emulator() {
        return emu;
    }

    public Optional<PtySession> session() {
        return Optional.ofNullable(session);
    }

    public boolean hasEnded() {
        return ended;
    }

    public void apply(TerminalSettings settings, Palette palette) {
        synchronized (emu) {
            emu.setScrollbackCapacity(settings.scrollback());
            emu.setAllowClipboard(settings.allowClipboard());
        }
        view.apply(settings, palette);
    }

    // ---------------------------------------------------------------- title

    /** The full title: the fixed one, the one the program set, or the folder of the shell. */
    public String title() {
        if (fixedTitle != null) return fixedTitle;
        String t;
        synchronized (emu) {
            t = emu.title();
        }
        if (t != null && !t.isBlank()) return t;
        if (session != null) {
            Optional<String> program = session.foregroundProgram();
            if (program.isPresent()) return program.get();
            Optional<Path> dir = session.currentDirectory();
            if (dir.isPresent()) return TabTitles.folder(dir.get(), Path.of(System.getProperty("user.home")));
        }
        return msg.get("tab.untitled");
    }

    /** The short title for the tab, with a mark after a bell while the tab was in the background. */
    public String tabTitle() {
        return (bellMark ? "• " : "") + TabTitles.shorten(title());
    }

    public void setBellMark(boolean on) {
        if (bellMark != on) {
            bellMark = on;
            owner.titleChanged(this);
        }
    }

    // ---------------------------------------------------------------- closing

    /** The program that would be ended by closing the tab (not the shell itself), if any. */
    public Optional<String> runningProgram() {
        if (session == null || ended) return Optional.empty();
        return session.foregroundProgram();
    }

    /** Ends the program (SIGHUP) and stops painting. */
    public void close() {
        ended = true;
        view.stop();
        if (session != null) session.hangUp();
    }

    /** The folder of the shell, for a new tab in the same place and for Open Folder in Files. */
    public Optional<Path> currentDirectory() {
        String d;
        synchronized (emu) {
            d = emu.directory();
        }
        if (session != null) {
            Optional<Path> proc = session.currentDirectory();
            if (proc.isPresent()) return proc;
        }
        return Optional.ofNullable(d).map(Path::of);
    }

    // ---------------------------------------------------------------- actions

    public void reset() {
        synchronized (emu) {
            emu.fullReset();
        }
        if (session != null) send("\f".getBytes(StandardCharsets.US_ASCII));
    }

    public void clearScrollback() {
        synchronized (emu) {
            emu.clearHistory();
        }
        view.scrollTo(0);
    }

    public void showFind() {
        find.setVisible(true);
        String selected = view.selectedText();
        if (!selected.isEmpty() && !selected.contains("\n")) find.setQuery(selected);
        find.focusField();
        revalidate();
    }

    public void hideFind() {
        find.setVisible(false);
        view.setHits(List.of(), -1);
        view.requestFocusInWindow();
        revalidate();
    }

    public boolean isFindVisible() {
        return find.isVisible();
    }

    FindBar findBar() {
        return find;
    }

    /** Runs the search; {@code step} -1 for previous, +1 for next, 0 for a new query. */
    private void search(String query, boolean matchCase, boolean regex, int step) {
        List<TextModel.Range> hits;
        synchronized (emu) {
            hits = TextModel.find(emu, query, matchCase, regex, 10_000);
        }
        int current;
        if (step == 0 || find.current() >= hits.size()) current = hits.size() - 1;
        else current = hits.isEmpty() ? -1 : Math.floorMod(find.current() + step, hits.size());
        find.showResult(current, hits.size());
        view.setHits(hits, current);
    }

    // ---------------------------------------------------------------- TerminalView.Host

    @Override
    public void send(byte[] bytes) {
        if (session == null || ended) return;
        try {
            session.write(bytes);
        } catch (IOException e) {
            LOG.debug("Writing to the terminal failed", e);
        }
    }

    @Override
    public void paste(String text, boolean fromSelection) {
        owner.paste(this, text);
    }

    @Override
    public void openLink(String target) {
        owner.openLink(target);
    }

    @Override
    public void showMenu(MouseEvent e) {
        owner.showMenu(this, e);
    }

    @Override
    public void sizeChanged(int cols, int rows, int widthPixels, int heightPixels) {
        if (cols == lastCols && rows == lastRows) return;
        // a rewrap moves the text to other lines: a selection would point at something else
        if (cols != lastCols && lastCols != 0) view.clearSelection();
        lastCols = cols;
        lastRows = rows;
        synchronized (emu) {
            emu.resize(cols, rows);
        }
        if (session != null) session.resize(cols, rows, widthPixels, heightPixels);
        viewChanged();
    }

    @Override
    public void viewChanged() {
        adjusting = true;
        try {
            synchronized (emu) {
                int h = emu.historySize();
                int rows = emu.rows();
                bar.setValues(h - view.scrollOffset(), rows, 0, h + rows);
                bar.setBlockIncrement(Math.max(1, rows - 1));
                bar.setEnabled(h > 0);
            }
        } finally {
            adjusting = false;
        }
    }

    @Override
    public void zoom(int direction) {
        owner.zoom(direction);
    }

    /** The emulator's events come on the reader thread; the window hears them on the event thread. */
    private final class Events implements TerminalListener {
        @Override
        public void respond(String text) {
            send(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void bell() {
            SwingUtilities.invokeLater(() -> owner.bell(TerminalTab.this));
        }

        @Override
        public void titleChanged(String title) {
            SwingUtilities.invokeLater(() -> owner.titleChanged(TerminalTab.this));
        }

        @Override
        public void clipboardSet(String text) {
            SwingUtilities.invokeLater(() -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null));
        }

        @Override
        public int color(int which) {
            return view.colorFor(which);
        }
    }
}
