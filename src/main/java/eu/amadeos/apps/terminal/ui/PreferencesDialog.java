package eu.amadeos.apps.terminal.ui;

import eu.amadeos.apps.terminal.domain.ColorScheme;
import eu.amadeos.apps.terminal.domain.CursorShape;
import eu.amadeos.apps.terminal.domain.Palette;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import eu.amadeos.apps.terminal.emulator.TerminalEmulator;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Edit > Preferences: Appearance (with a live preview), Behaviour and the list of keys. */
public final class PreferencesDialog extends JDialog {
    private final Messages msg;
    private final Function<TerminalSettings, Palette> paletteFor;
    private TerminalSettings result;

    private final JComboBox<ColorScheme> scheme = new JComboBox<>(ColorScheme.values());
    private final JComboBox<String> font;
    private final JSpinner fontSize;
    private final JRadioButton block;
    private final JRadioButton underline;
    private final JRadioButton bar;
    private final JCheckBox blink;
    private final JSpinner columns;
    private final JSpinner rows;
    private final JSpinner scrollback;
    private final JCheckBox unlimited;
    private final JCheckBox brightBold;
    private final JTextField command = new JTextField(24);
    private final JCheckBox loginShell;
    private final JCheckBox pasteWarning;
    private final JCheckBox bellSound;
    private final JCheckBox bellFlash;
    private final JCheckBox bellNotify;
    private final JCheckBox confirmClose;
    private final JCheckBox allowClipboard;
    private final TerminalView preview;
    private final TerminalEmulator previewEmulator = new TerminalEmulator(52, 5, 0);

    public PreferencesDialog(Window owner, Messages msg, TerminalSettings s, Palette palette,
                             Function<TerminalSettings, Palette> paletteFor) {
        super(owner, msg.get("prefs.title"), ModalityType.APPLICATION_MODAL);
        this.msg = msg;
        this.paletteFor = paletteFor;
        setName("preferences");

        scheme.setName("prefs.scheme");
        scheme.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof ColorScheme c) setText(Mnemonics.strip(msg.get(c.textKey())));
                return this;
            }
        });
        scheme.setSelectedItem(s.scheme());
        List<String> fonts = monospacedFonts();
        if (!fonts.contains(s.fontFamily())) fonts.add(0, s.fontFamily());
        font = new JComboBox<>(fonts.toArray(String[]::new));
        font.setName("prefs.font");
        font.setSelectedItem(s.fontFamily());
        fontSize = spinner("prefs.font-size", s.fontSize(), TerminalSettings.MIN_FONT, TerminalSettings.MAX_FONT, 1);
        block = radio(CursorShape.BLOCK, s.cursor());
        underline = radio(CursorShape.UNDERLINE, s.cursor());
        bar = radio(CursorShape.BAR, s.cursor());
        ButtonGroup shapes = new ButtonGroup();
        shapes.add(block);
        shapes.add(underline);
        shapes.add(bar);
        blink = check("prefs.cursor-blink", s.cursorBlink());
        columns = spinner("prefs.columns", s.columns(), 20, 500, 1);
        rows = spinner("prefs.rows", s.rows(), 5, 200, 1);
        scrollback = spinner("prefs.scrollback", s.unlimitedScrollback() ? 10_000 : s.scrollback(), 0,
                TerminalSettings.UNLIMITED - 1, 1000);
        unlimited = check("prefs.unlimited", s.unlimitedScrollback());
        unlimited.addActionListener(e -> scrollback.setEnabled(!unlimited.isSelected()));
        scrollback.setEnabled(!s.unlimitedScrollback());
        brightBold = check("prefs.bright-bold", s.brightBold());
        command.setName("prefs.command");
        command.setText(s.command());
        loginShell = check("prefs.login-shell", s.loginShell());
        pasteWarning = check("prefs.paste-warning", s.pasteWarning());
        bellSound = check("prefs.bell-sound", s.bellSound());
        bellFlash = check("prefs.bell-flash", s.bellFlash());
        bellNotify = check("prefs.bell-notify", s.bellNotify());
        confirmClose = check("prefs.confirm-close", s.confirmClose());
        allowClipboard = check("prefs.clipboard", s.allowClipboard());

        preview = new TerminalView(previewEmulator, previewSettings(s), palette, new NoHost());
        preview.setName("prefs.preview");
        preview.setFocusable(false);
        previewEmulator.feed("\u001b[1;32mralf@amadeos\u001b[0m:\u001b[1;34m~\u001b[0m$ ls --color\r\n"
                + "\u001b[1;34mDocuments\u001b[0m  \u001b[1;32mbackup.sh\u001b[0m  \u001b[1;31mphotos.tar.gz\u001b[0m"
                + "  \u001b[36mlink\u001b[0m\r\n"
                + "\u001b[40m  \u001b[41m  \u001b[42m  \u001b[43m  \u001b[44m  \u001b[45m  \u001b[46m  \u001b[47m  "
                + "\u001b[100m  \u001b[101m  \u001b[102m  \u001b[103m  \u001b[104m  \u001b[105m  \u001b[106m  "
                + "\u001b[107m  \u001b[0m\r\n"
                + "\u001b[1;32mralf@amadeos\u001b[0m:\u001b[1;34m~\u001b[0m$ ");

        JTabbedPane pages = new JTabbedPane();
        pages.setName("prefs.pages");
        pages.addTab(Mnemonics.strip(msg.get("prefs.page.appearance")), appearance());
        pages.addTab(Mnemonics.strip(msg.get("prefs.page.behaviour")), behaviour());
        pages.addTab(Mnemonics.strip(msg.get("prefs.page.keys")), keys());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton ok = new JButton(msg.get("button.ok"));
        ok.setName("prefs.ok");
        ok.addActionListener(e -> {
            result = settings();
            dispose();
        });
        JButton cancel = new JButton(msg.get("button.cancel"));
        cancel.setName("prefs.cancel");
        cancel.addActionListener(e -> dispose());
        buttons.add(ok);
        buttons.add(cancel);
        JPanel all = new JPanel(new BorderLayout());
        all.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        all.add(pages, BorderLayout.CENTER);
        all.add(buttons, BorderLayout.SOUTH);
        setContentPane(all);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        for (JComponent c : List.of(scheme, font, block, underline, bar, blink, brightBold)) {
            if (c instanceof JComboBox<?> b) b.addActionListener(e -> updatePreview());
            else if (c instanceof javax.swing.AbstractButton b) b.addActionListener(e -> updatePreview());
        }
        fontSize.addChangeListener(e -> updatePreview());
        pack();
        setLocationRelativeTo(owner);
    }

    /** The new settings after OK; empty after Cancel. */
    public Optional<TerminalSettings> result() {
        return Optional.ofNullable(result);
    }

    TerminalSettings settings() {
        CursorShape shape = underline.isSelected() ? CursorShape.UNDERLINE : bar.isSelected() ? CursorShape.BAR
                : CursorShape.BLOCK;
        return new TerminalSettings((ColorScheme) scheme.getSelectedItem(), (String) font.getSelectedItem(),
                (Integer) fontSize.getValue(), shape, blink.isSelected(), (Integer) columns.getValue(),
                (Integer) rows.getValue(), unlimited.isSelected() ? TerminalSettings.UNLIMITED
                : (Integer) scrollback.getValue(), brightBold.isSelected(), command.getText().strip(),
                loginShell.isSelected(), pasteWarning.isSelected(), bellSound.isSelected(), bellFlash.isSelected(),
                bellNotify.isSelected(), confirmClose.isSelected(), allowClipboard.isSelected());
    }

    private void updatePreview() {
        TerminalSettings s = settings();
        preview.apply(previewSettings(s), paletteFor.apply(s));
        pack();
    }

    private static TerminalSettings previewSettings(TerminalSettings s) {
        return new TerminalSettings(s.scheme(), s.fontFamily(), s.fontSize(), s.cursor(), false, 52, 5, 0,
                s.brightBold(), "", false, true, false, false, false, false, false);
    }

    private JComponent appearance() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 8);
        int y = 0;
        y = row(p, g, y, "prefs.scheme", scheme);
        y = row(p, g, y, "prefs.font", flow(font, fontSize));
        y = row(p, g, y, "prefs.cursor", flow(block, underline, bar, blink));
        y = row(p, g, y, "prefs.size", flow(columns, label("prefs.columns-unit"), rows, label("prefs.rows-unit")));
        y = row(p, g, y, "prefs.scrollback", flow(scrollback, label("prefs.lines-unit"), unlimited));
        y = row(p, g, y, "prefs.bold", brightBold);
        g.gridx = 0;
        g.gridy = y;
        g.gridwidth = 2;
        g.fill = GridBagConstraints.HORIZONTAL;
        JPanel box = new JPanel(new BorderLayout());
        box.setBorder(BorderFactory.createTitledBorder(Mnemonics.strip(msg.get("prefs.preview"))));
        box.add(preview);
        p.add(box, g);
        return p;
    }

    private JComponent behaviour() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 4, 4, 8);
        int y = 0;
        y = row(p, g, y, "prefs.command", command);
        y = row(p, g, y, null, small("prefs.command-hint"));
        y = row(p, g, y, null, loginShell);
        y = row(p, g, y, "prefs.paste", pasteWarning);
        y = row(p, g, y, "prefs.bell", column(bellFlash, bellSound, bellNotify));
        y = row(p, g, y, "prefs.closing", confirmClose);
        y = row(p, g, y, "prefs.programs", allowClipboard);
        g.gridy = y;
        g.weighty = 1;
        p.add(Box.createVerticalGlue(), g);
        return p;
    }

    private JComponent keys() {
        String[][] rowsData = {
                {"Ctrl+Shift+C", "menu.edit.copy"}, {"Ctrl+Shift+V", "menu.edit.paste"},
                {"Shift+Insert, " + msg.get("keys.middle-click"), "menu.edit.paste-selection"},
                {"Ctrl+Shift+A", "menu.edit.select-all"}, {"Ctrl+Shift+F", "menu.edit.find"},
                {"Ctrl+Shift+T", "menu.file.new-tab"}, {"Ctrl+Shift+N", "menu.file.new-window"},
                {"Ctrl+Shift+W", "menu.file.close-tab"}, {"Ctrl+Shift+Q", "menu.file.close-window"},
                {"Ctrl+PgUp, Ctrl+PgDn", "keys.switch-tabs"}, {"Ctrl+Shift+PgUp, Ctrl+Shift+PgDn", "keys.move-tabs"},
                {"Alt+1 … Alt+9", "keys.tab-n"}, {"Shift+PgUp, Shift+PgDn", "keys.scroll-page"},
                {"Shift+Home, Shift+End", "keys.scroll-ends"}, {"Ctrl+Shift+K", "menu.terminal.clear"},
                {"Ctrl++, Ctrl+-, Ctrl+0", "keys.zoom"}, {"F11", "menu.view.full-screen"},
                {"Ctrl+" + msg.get("keys.click"), "keys.open-link"},
                {"Shift+" + msg.get("keys.drag"), "keys.select-anyway"},
        };
        DefaultTableModel model = new DefaultTableModel(new Object[]{msg.get("keys.key"), msg.get("keys.action")}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (String[] r : rowsData) model.addRow(new Object[]{r[0], Mnemonics.strip(msg.get(r[1]))});
        JTable table = new JTable(model);
        table.setName("prefs.keys");
        table.setRowHeight(Math.round(AmadeUI.scaled(20)));
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(AmadeUI.sunken());
        scroll.setPreferredSize(new Dimension(480, 260));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        p.add(small("keys.hint"), BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------- helpers

    private int row(JPanel p, GridBagConstraints g, int y, String key, JComponent c) {
        g.gridx = 0;
        g.gridy = y;
        g.gridwidth = 1;
        g.fill = GridBagConstraints.NONE;
        if (key != null) {
            JLabel l = Mnemonics.apply(new JLabel(), msg.get(key));
            l.setLabelFor(c instanceof JPanel ? (Component) ((JPanel) c).getComponent(0) : c);
            p.add(l, g);
        }
        g.gridx = 1;
        p.add(c, g);
        return y + 1;
    }

    private JLabel label(String key) {
        return new JLabel(msg.get(key));
    }

    private JLabel small(String key) {
        JLabel l = new JLabel("<html><div style='width:340px'>" + msg.get(key) + "</div></html>");
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() * 0.9f));
        return l;
    }

    private JCheckBox check(String key, boolean selected) {
        JCheckBox b = Mnemonics.apply(new JCheckBox(), msg.get(key));
        b.setName(key);
        b.setSelected(selected);
        return b;
    }

    private JRadioButton radio(CursorShape shape, CursorShape selected) {
        JRadioButton b = Mnemonics.apply(new JRadioButton(), msg.get(shape.textKey()));
        b.setName(shape.textKey());
        b.setSelected(shape == selected);
        return b;
    }

    private static JSpinner spinner(String name, int value, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step));
        s.setName(name);
        return s;
    }

    private static JPanel flow(JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        for (JComponent c : cs) {
            if (p.getComponentCount() > 0) p.add(Box.createHorizontalStrut(6));
            p.add(c);
        }
        return p;
    }

    private static JPanel column(JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JComponent c : cs) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(c);
        }
        return p;
    }

    /** Font families whose letters all have the same width. */
    static List<String> monospacedFonts() {
        List<String> list = new ArrayList<>();
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            Font f = new Font(family, Font.PLAIN, 13);
            if (!f.canDisplay('a')) continue;
            FontMetrics fm = g.getFontMetrics(f);
            if (fm.charWidth('i') == fm.charWidth('M') && fm.charWidth('M') == fm.charWidth('.')) list.add(family);
        }
        g.dispose();
        return list;
    }

    /** The preview shows sample text only. */
    private static final class NoHost implements TerminalView.Host {
        @Override
        public void send(byte[] bytes) {
        }

        @Override
        public void paste(String text, boolean fromSelection) {
        }

        @Override
        public void openLink(String target) {
        }

        @Override
        public void showMenu(java.awt.event.MouseEvent e) {
        }

        @Override
        public void sizeChanged(int cols, int rows, int widthPixels, int heightPixels) {
        }

        @Override
        public void viewChanged() {
        }

        @Override
        public void zoom(int direction) {
        }
    }
}
