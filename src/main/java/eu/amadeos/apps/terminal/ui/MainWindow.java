package eu.amadeos.apps.terminal.ui;

import eu.amadeos.apps.terminal.bootstrap.AmadeTerminalApplication;
import eu.amadeos.apps.terminal.domain.LaunchOptions;
import eu.amadeos.apps.terminal.domain.Palette;
import eu.amadeos.apps.terminal.domain.PasteText;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import eu.amadeos.apps.terminal.emulator.KeyEncoder;
import eu.amadeos.apps.terminal.emulator.TextModel;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.BevelBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A terminal window: menus and the tabs. */
public final class MainWindow extends JFrame implements TerminalTab.Owner {
    private static final Logger LOG = LoggerFactory.getLogger(MainWindow.class);
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;

    private final TerminalApp app;
    private final Messages msg;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<String, JMenuItem> items = new LinkedHashMap<>();
    private final Set<KeyStroke> reserved = new HashSet<>();
    private final Timer titles;
    private boolean addingTab;
    private boolean fullScreen;
    private Dimension windowedSize;

    public MainWindow(TerminalApp app) {
        this.app = app;
        this.msg = app.messages();
        setName("terminal-window");
        setTitle(msg.get("window.title"));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImage(icon());
        setJMenuBar(menuBar(app.locales()));
        tabs.setName("tabs");
        tabs.setFocusable(false);
        tabs.addTab("+", new JPanel());
        tabs.setToolTipTextAt(0, msg.get("menu.file.new-tab"));
        tabs.addChangeListener(e -> tabSelected());
        tabs.addMouseListener(new TabMouse());
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        content.add(tabs, BorderLayout.CENTER);
        setContentPane(content);
        bindExtraKeys();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeWindow();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                current().ifPresent(t -> t.setBellMark(false));
            }
        });
        titles = new Timer(1000, e -> refreshTitles());
        titles.start();
    }

    private static Image icon() {
        try (InputStream in = MainWindow.class.getResourceAsStream("/icons/amade-terminal.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    // ================================================================ menus

    private JMenuBar menuBar(LocaleSettings locales) {
        JMenuBar bar = new JMenuBar();
        bar.setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED));

        JMenu file = menu(bar, "menu.file");
        file.add(item("menu.file.new-tab", key(KeyEvent.VK_T, CTRL | SHIFT), () -> newTab(LaunchOptions.SHELL)));
        file.add(item("menu.file.new-window", key(KeyEvent.VK_N, CTRL | SHIFT), this::newWindow));
        file.addSeparator();
        file.add(item("menu.file.close-tab", key(KeyEvent.VK_W, CTRL | SHIFT),
                () -> current().ifPresent(t -> closeTab(t, true))));
        file.add(item("menu.file.close-window", key(KeyEvent.VK_Q, CTRL | SHIFT), this::closeWindow));

        JMenu edit = menu(bar, "menu.edit");
        edit.add(item("menu.edit.copy", key(KeyEvent.VK_C, CTRL | SHIFT), this::copy));
        edit.add(item("menu.edit.paste", key(KeyEvent.VK_V, CTRL | SHIFT), this::pasteClipboard));
        edit.add(item("menu.edit.paste-selection", key(KeyEvent.VK_INSERT, SHIFT),
                () -> current().ifPresent(t -> t.view().pastePrimary())));
        edit.add(item("menu.edit.select-all", key(KeyEvent.VK_A, CTRL | SHIFT),
                () -> current().ifPresent(t -> t.view().selectAll())));
        edit.addSeparator();
        edit.add(item("menu.edit.find", key(KeyEvent.VK_F, CTRL | SHIFT), () -> current().ifPresent(TerminalTab::showFind)));
        edit.addSeparator();
        edit.add(item("menu.edit.preferences", key(KeyEvent.VK_COMMA, CTRL | SHIFT), this::preferences));

        JMenu view = menu(bar, "menu.view");
        view.add(item("menu.view.zoom-in", key(KeyEvent.VK_EQUALS, CTRL), () -> zoom(1)));
        view.add(item("menu.view.zoom-out", key(KeyEvent.VK_MINUS, CTRL), () -> zoom(-1)));
        view.add(item("menu.view.zoom-normal", key(KeyEvent.VK_0, CTRL), () -> zoom(0)));
        view.addSeparator();
        view.add(item("menu.view.full-screen", key(KeyEvent.VK_F11, 0), this::toggleFullScreen));

        JMenu terminal = menu(bar, "menu.terminal");
        terminal.add(item("menu.terminal.clear", key(KeyEvent.VK_K, CTRL | SHIFT),
                () -> current().ifPresent(TerminalTab::clearScrollback)));
        terminal.add(item("menu.terminal.reset", null, () -> current().ifPresent(TerminalTab::reset)));
        terminal.addSeparator();
        terminal.add(item("menu.terminal.open-folder", null, this::openFolder));

        JMenu tabMenu = menu(bar, "menu.tabs");
        tabMenu.add(item("menu.tabs.previous", key(KeyEvent.VK_PAGE_UP, CTRL), () -> selectRelative(-1)));
        tabMenu.add(item("menu.tabs.next", key(KeyEvent.VK_PAGE_DOWN, CTRL), () -> selectRelative(1)));
        tabMenu.addSeparator();
        tabMenu.add(item("menu.tabs.move-left", key(KeyEvent.VK_PAGE_UP, CTRL | SHIFT), () -> moveCurrent(-1)));
        tabMenu.add(item("menu.tabs.move-right", key(KeyEvent.VK_PAGE_DOWN, CTRL | SHIFT), () -> moveCurrent(1)));

        if (locales != null) {
            JMenu options = menu(bar, "menu.options");
            options.add(languageMenu(locales));
        }

        JMenu help = menu(bar, "menu.help");
        help.add(item("menu.help.handbook", null, app::openHandbook));
        help.add(item("menu.help.about", null, this::about));
        return bar;
    }

    /** Keys without a menu entry: Alt+1..9 for the tabs, the zoom keys of the number pad. */
    private void bindExtraKeys() {
        Map<KeyStroke, Runnable> keys = new LinkedHashMap<>();
        for (int i = 1; i <= 9; i++) {
            int index = i - 1;
            keys.put(key(KeyEvent.VK_0 + i, ALT), () -> selectTab(index));
        }
        keys.put(key(KeyEvent.VK_ADD, CTRL), () -> zoom(1));
        keys.put(key(KeyEvent.VK_PLUS, CTRL), () -> zoom(1));
        keys.put(key(KeyEvent.VK_EQUALS, CTRL | SHIFT), () -> zoom(1));
        keys.put(key(KeyEvent.VK_SUBTRACT, CTRL), () -> zoom(-1));
        keys.put(key(KeyEvent.VK_NUMPAD0, CTRL), () -> zoom(0));
        JComponent root = getRootPane();
        int n = 0;
        for (Map.Entry<KeyStroke, Runnable> k : keys.entrySet()) {
            String name = "extra-" + n++;
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k.getKey(), name);
            Runnable r = k.getValue();
            root.getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    r.run();
                }
            });
            reserved.add(k.getKey());
        }
    }

    private JMenu languageMenu(LocaleSettings locales) {
        JMenu menu = Mnemonics.apply(new JMenu(), msg.get("menu.options.language"));
        menu.setName("menu.options.language");
        Messages commons = Messages.commons(msg.locale());
        List<Optional<Language>> choices = new ArrayList<>();
        choices.add(Optional.empty());
        for (Language language : Language.values()) choices.add(Optional.of(language));
        ButtonGroup group = new ButtonGroup();
        Optional<Language> chosen = locales.applicationLanguage();
        for (Optional<Language> choice : choices) {
            String label = choice.map(Language::nativeName).orElseGet(() -> commons.format("language.follow-desktop",
                    locales.desktopResolution().language().nativeName()));
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, choice.equals(chosen));
            item.setName(choice.map(Language::code).orElse(LocaleSettings.FOLLOW_DESKTOP));
            item.addActionListener(e -> setLanguage(locales, choice));
            group.add(item);
            menu.add(item);
            if (choice.isEmpty()) menu.addSeparator();
        }
        return menu;
    }

    private void setLanguage(LocaleSettings locales, Optional<Language> language) {
        try {
            locales.setApplicationLanguage(language);
            JOptionPane.showMessageDialog(this, Messages.commons(msg.locale()).get("language.restart"),
                    msg.get("window.title"), JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            LOG.warn("Could not save the language", e);
        }
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private JMenu menu(JMenuBar bar, String key) {
        JMenu menu = Mnemonics.apply(new JMenu(), msg.get(key));
        menu.setName(key);
        bar.add(menu);
        return menu;
    }

    private JMenuItem item(String key, KeyStroke accelerator, Runnable action) {
        JMenuItem item = Mnemonics.apply(new JMenuItem(), msg.get(key));
        item.setName(key);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
            reserved.add(accelerator);
        }
        item.addActionListener(e -> action.run());
        items.put(key, item);
        return item;
    }

    // ================================================================ tabs

    /** The tab in front. */
    public Optional<TerminalTab> current() {
        Component c = tabs.getSelectedComponent();
        return c instanceof TerminalTab t ? Optional.of(t) : Optional.empty();
    }

    public List<TerminalTab> terminalTabs() {
        List<TerminalTab> list = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof TerminalTab t) list.add(t);
        }
        return list;
    }

    /** A new tab; without a folder of its own it starts where the current tab's shell is. */
    public TerminalTab newTab(LaunchOptions options) {
        Path dir = options.directory();
        if (dir == null) dir = current().flatMap(TerminalTab::currentDirectory).orElse(null);
        TerminalTab tab = new TerminalTab(this, msg, app.settings(), app.palette(), options);
        tab.view().setReservedKeys(reserved);
        addingTab = true;
        try {
            int index = tabs.getTabCount() - 1;
            tabs.insertTab(tab.tabTitle(), null, tab, null, index);
            tabs.setTabComponentAt(index, new TabHeader(tab));
            tabs.setSelectedIndex(index);
        } finally {
            addingTab = false;
        }
        app.start(tab, options, dir);
        SwingUtilities.invokeLater(tab.view()::requestFocusInWindow);
        refreshTitles();
        return tab;
    }

    private void newWindow() {
        LaunchOptions here = new LaunchOptions(List.of(), null,
                current().flatMap(TerminalTab::currentDirectory).orElse(null), false, false);
        app.openWindow(here);
    }

    private void tabSelected() {
        if (addingTab) return;
        int plus = tabs.getTabCount() - 1;
        if (tabs.getSelectedIndex() == plus && plus >= 0) {
            newTab(LaunchOptions.SHELL);
            return;
        }
        current().ifPresent(t -> {
            t.setBellMark(false);
            SwingUtilities.invokeLater(() -> t.view().requestFocusInWindow());
        });
        refreshTitles();
    }

    private void selectTab(int index) {
        List<TerminalTab> list = terminalTabs();
        if (index >= 0 && index < list.size()) tabs.setSelectedComponent(list.get(index));
    }

    private void selectRelative(int step) {
        List<TerminalTab> list = terminalTabs();
        if (list.size() < 2) return;
        int i = current().map(list::indexOf).orElse(0);
        tabs.setSelectedComponent(list.get(Math.floorMod(i + step, list.size())));
    }

    private void moveCurrent(int step) {
        Optional<TerminalTab> cur = current();
        if (cur.isEmpty()) return;
        int from = tabs.indexOfComponent(cur.get());
        int to = from + step;
        if (to < 0 || to >= tabs.getTabCount() - 1) return;
        moveTab(from, to);
    }

    /** Moves a tab to another place (Tabs > Move, or dragging its header). */
    void moveTab(int from, int to) {
        if (from == to || from < 0 || to < 0 || to >= tabs.getTabCount() - 1) return;
        TerminalTab tab = (TerminalTab) tabs.getComponentAt(from);
        Component header = tabs.getTabComponentAt(from);
        addingTab = true;
        try {
            tabs.removeTabAt(from);
            tabs.insertTab(tab.tabTitle(), null, tab, null, to);
            tabs.setTabComponentAt(to, header);
            tabs.setSelectedIndex(to);
        } finally {
            addingTab = false;
        }
        tab.view().requestFocusInWindow();
    }

    /** Closes a tab; asks first if a program still runs in it (and the Preferences say so). */
    public void closeTab(TerminalTab tab, boolean ask) {
        if (ask && app.settings().confirmClose()) {
            Optional<String> program = tab.runningProgram();
            if (program.isPresent() && !confirm(msg.format("close.tab.running", program.get()),
                    msg.get("close.tab.title"), msg.get("close.tab.button"))) {
                return;
            }
        }
        tab.close();
        int index = tabs.indexOfComponent(tab);
        if (index < 0) return;
        addingTab = true;
        try {
            tabs.removeTabAt(index);
            int count = tabs.getTabCount() - 1;
            if (count > 0) tabs.setSelectedIndex(Math.min(index, count - 1));
        } finally {
            addingTab = false;
        }
        if (terminalTabs().isEmpty()) {
            disposeWindow();
        } else {
            current().ifPresent(t -> t.view().requestFocusInWindow());
            refreshTitles();
        }
    }

    /** Closes the window; with several tabs or running programs it asks first. */
    public void closeWindow() {
        List<TerminalTab> list = terminalTabs();
        if (app.settings().confirmClose()) {
            List<String> running = list.stream().flatMap(t -> t.runningProgram().stream()).toList();
            String question = !running.isEmpty() ? msg.format("close.window.running", String.join(", ", running))
                    : list.size() > 1 ? msg.format("close.window.tabs", list.size()) : null;
            if (question != null && !confirm(question, msg.get("close.window.title"),
                    msg.get("close.window.button"))) {
                return;
            }
        }
        for (TerminalTab t : list) t.close();
        disposeWindow();
    }

    private void disposeWindow() {
        titles.stop();
        if (fullScreen) toggleFullScreen();
        dispose();
        app.windowClosed(this);
    }

    private boolean confirm(String question, String title, String yes) {
        Object[] options = {yes, msg.get("button.cancel")};
        int answer = JOptionPane.showOptionDialog(this, question, title, JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return answer == 0;
    }

    /** Keeps tab texts and the window title up to date (the program in front can change any time). */
    void refreshTitles() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof TerminalTab t) {
                String text = t.tabTitle();
                if (tabs.getTabComponentAt(i) instanceof TabHeader h) h.setText(text, t.title());
                if (!text.equals(tabs.getTitleAt(i))) tabs.setTitleAt(i, text);
            }
        }
        String title = current().map(t -> msg.format("window.title.tab", t.title())).orElse(msg.get("window.title"));
        if (!title.equals(getTitle())) setTitle(title);
    }

    public void focusTerminal() {
        current().ifPresent(t -> t.view().requestFocusInWindow());
    }

    // ================================================================ TerminalTab.Owner

    @Override
    public void titleChanged(TerminalTab tab) {
        refreshTitles();
    }

    @Override
    public void ended(TerminalTab tab) {
        closeTab(tab, false);
    }

    @Override
    public void bell(TerminalTab tab) {
        TerminalSettings s = app.settings();
        if (s.bellFlash()) tab.view().flash();
        if (s.bellSound()) Toolkit.getDefaultToolkit().beep();
        boolean inFront = isActive() && current().map(t -> t == tab).orElse(false);
        if (!inFront) {
            tab.setBellMark(current().map(t -> t != tab).orElse(true) || !isActive());
            if (s.bellNotify() && !isActive()) {
                app.notifyBell(tab.title(), () -> SwingUtilities.invokeLater(() -> {
                    toFront();
                    tabs.setSelectedComponent(tab);
                }));
            }
        }
    }

    @Override
    public void openLink(String target) {
        app.openLink(target);
    }

    @Override
    public void zoom(int direction) {
        TerminalSettings s = app.settings();
        int size = direction == 0 ? TerminalSettings.DEFAULTS.fontSize() : s.fontSize() + direction;
        if (size != s.fontSize()) app.setSettings(s.withFontSize(size));
    }

    /**
     * Pastes text into a tab: with a warning first if it has several lines and the program would run them at
     * once (no bracketed paste).
     */
    @Override
    public void paste(TerminalTab tab, String text) {
        if (text == null || text.isEmpty()) return;
        boolean bracketed;
        synchronized (tab.emulator()) {
            bracketed = tab.emulator().bracketedPaste();
        }
        String toSend = text;
        if (app.settings().pasteWarning() && PasteText.needsWarning(text, bracketed)) {
            PasteWarning.Choice choice = PasteWarning.ask(this, msg, PasteText.lines(text));
            switch (choice) {
                case CANCEL -> {
                    return;
                }
                case ONE_LINE -> toSend = PasteText.oneLine(text);
                case PASTE_AND_STOP_ASKING -> app.setSettings(app.settings().withPasteWarning(false));
                default -> {
                }
            }
        }
        tab.view().scrollToBottom();
        tab.send(KeyEncoder.paste(toSend, bracketed).getBytes(StandardCharsets.UTF_8));
        tab.view().requestFocusInWindow();
    }

    @Override
    public void showMenu(TerminalTab tab, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        menu.setName("context-menu");
        boolean selection = tab.view().hasSelection();
        JMenuItem copy = popupItem("menu.edit.copy", this::copy);
        copy.setEnabled(selection);
        menu.add(copy);
        menu.add(popupItem("menu.edit.paste", this::pasteClipboard));
        menu.add(popupItem("menu.edit.paste-selection", () -> tab.view().pastePrimary()));
        menu.add(popupItem("menu.edit.select-all", () -> tab.view().selectAll()));
        TextModel.Link link = tab.view().hoverLink();
        if (link != null) {
            menu.addSeparator();
            menu.add(popupItem("menu.link.open", () -> app.openLink(link.target())));
            menu.add(popupItem("menu.link.copy", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(link.target()), null)));
        }
        menu.addSeparator();
        menu.add(popupItem("menu.edit.find", tab::showFind));
        menu.add(popupItem("menu.terminal.clear", tab::clearScrollback));
        menu.add(popupItem("menu.terminal.reset", tab::reset));
        menu.addSeparator();
        menu.add(popupItem("menu.file.new-tab", () -> newTab(LaunchOptions.SHELL)));
        menu.add(popupItem("menu.terminal.open-folder", this::openFolder));
        menu.add(popupItem("menu.view.full-screen", this::toggleFullScreen));
        menu.add(popupItem("menu.edit.preferences", this::preferences));
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private JMenuItem popupItem(String key, Runnable action) {
        JMenuItem item = Mnemonics.apply(new JMenuItem(), msg.get(key));
        item.setName(key);
        JMenuItem main = items.get(key);
        if (main != null && main.getAccelerator() != null) item.setAccelerator(main.getAccelerator());
        item.addActionListener(e -> action.run());
        return item;
    }

    // ================================================================ actions

    private void copy() {
        current().ifPresent(t -> t.view().copy());
    }

    private void pasteClipboard() {
        current().ifPresent(t -> {
            try {
                var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    paste(t, (String) clipboard.getData(DataFlavor.stringFlavor));
                }
            } catch (Exception e) {
                LOG.debug("Nothing to paste", e);
            }
        });
    }

    private void openFolder() {
        current().flatMap(TerminalTab::currentDirectory).ifPresent(app::openFolder);
    }

    private void preferences() {
        PreferencesDialog dialog = new PreferencesDialog(this, msg, app.settings(), app.palette(),
                (settings) -> settings.scheme().palette(
                        eu.amadeos.commons.swing.AmadeUI.theme().fieldBackground(),
                        eu.amadeos.commons.swing.AmadeUI.theme().fieldText(),
                        eu.amadeos.commons.swing.AmadeUI.theme().selectionBackground(),
                        eu.amadeos.commons.swing.AmadeUI.theme().selectionText()));
        dialog.setVisible(true);
        dialog.result().ifPresent(app::setSettings);
        focusTerminal();
    }

    void apply(TerminalSettings settings, Palette palette) {
        for (TerminalTab t : terminalTabs()) t.apply(settings, palette);
    }

    void toggleFullScreen() {
        GraphicsDevice device = getGraphicsConfiguration() == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                : getGraphicsConfiguration().getDevice();
        fullScreen = !fullScreen;
        if (fullScreen) {
            windowedSize = getSize();
            if (device.isFullScreenSupported()) device.setFullScreenWindow(this);
            else setExtendedState(MAXIMIZED_BOTH);
        } else {
            if (device.getFullScreenWindow() == this) device.setFullScreenWindow(null);
            setExtendedState(NORMAL);
            if (windowedSize != null) setSize(windowedSize);
        }
        focusTerminal();
    }

    private void about() {
        JOptionPane.showMessageDialog(this,
                "<html><center><h2>amade-terminal " + AmadeTerminalApplication.version() + "</h2>"
                        + "<p>" + msg.get("dialog.about.text") + "</p><br>"
                        + "<small>&copy; 2026 datazuul.com &bull; EUPL-1.2</small></center></html>",
                msg.get("dialog.about.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    // ================================================================ tab headers

    /** A tab header: the title and a × that closes the tab; middle click closes too, dragging moves the tab. */
    private final class TabHeader extends JPanel {
        private final JLabel label = new JLabel();

        TabHeader(TerminalTab tab) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            setOpaque(false);
            add(label);
            JButton close = new JButton("×");
            close.setName("tab.close");
            close.setMargin(new Insets(0, 3, 0, 3));
            close.setFocusable(false);
            close.setBorderPainted(false);
            close.setContentAreaFilled(false);
            close.setToolTipText(msg.get("menu.file.close-tab"));
            close.addActionListener(e -> closeTab(tab, true));
            add(close);
            label.setText(tab.tabTitle());
            TabMouse forward = new TabMouse();
            label.addMouseListener(forward);
            label.addMouseMotionListener(forward);
            addMouseListener(forward);
            addMouseMotionListener(forward);
        }

        void setText(String text, String tooltip) {
            if (!text.equals(label.getText())) label.setText(text);
            setToolTipText(tooltip);
        }
    }

    /** Clicks on the tab headers: select, middle click closes, drag to reorder. */
    private final class TabMouse extends MouseAdapter {
        private int dragFrom = -1;

        private int indexAt(MouseEvent e) {
            java.awt.Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), tabs);
            return tabs.indexAtLocation(p.x, p.y);
        }

        @Override
        public void mousePressed(MouseEvent e) {
            int i = indexAt(e);
            if (i < 0) return;
            if (SwingUtilities.isMiddleMouseButton(e) && tabs.getComponentAt(i) instanceof TerminalTab t) {
                closeTab(t, true);
                return;
            }
            if (SwingUtilities.isLeftMouseButton(e)) {
                if (e.getComponent() != tabs) tabs.setSelectedIndex(i);
                dragFrom = tabs.getComponentAt(i) instanceof TerminalTab ? i : -1;
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            int to = indexAt(e);
            if (dragFrom >= 0 && to >= 0 && to != dragFrom && tabs.getComponentAt(to) instanceof TerminalTab) {
                moveTab(dragFrom, to);
            }
            dragFrom = -1;
        }
    }

    // ================================================================ for tests and screenshots

    JTabbedPane tabbedPane() {
        return tabs;
    }

    JMenuItem menuItem(String key) {
        return items.get(key);
    }

    Set<KeyStroke> reservedKeys() {
        return Set.copyOf(reserved);
    }
}
