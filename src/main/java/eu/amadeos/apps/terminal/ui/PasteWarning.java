package eu.amadeos.apps.terminal.ui;

import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.List;

/** "The clipboard holds 3 lines": shows the lines before they run, with Paste, Paste as One Line and Cancel. */
final class PasteWarning extends JDialog {
    enum Choice { PASTE, ONE_LINE, CANCEL, PASTE_AND_STOP_ASKING }

    private Choice choice = Choice.CANCEL;

    private PasteWarning(Window owner, Messages msg, List<String> lines) {
        super(owner, msg.format("paste.title", lines.size()), ModalityType.APPLICATION_MODAL);
        setName("paste-warning");
        JPanel p = new JPanel(new BorderLayout(12, 10));
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));
        JLabel icon = new JLabel(UIManager.getIcon("OptionPane.warningIcon"));
        icon.setVerticalAlignment(JLabel.TOP);
        p.add(icon, BorderLayout.WEST);
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.add(left(new JLabel("<html><b>" + msg.format("paste.heading", lines.size()) + "</b></html>")));
        c.add(Box.createVerticalStrut(6));
        c.add(left(new JLabel("<html><div style='width:320px'>" + msg.get("paste.text") + "</div></html>")));
        c.add(Box.createVerticalStrut(6));
        JTextArea area = new JTextArea(String.join("\n", lines));
        area.setName("paste.lines");
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(AmadeUI.scaled(12))));
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(AmadeUI.sunken());
        scroll.setPreferredSize(new Dimension(380, Math.min(200, 24 + 18 * lines.size())));
        c.add(left(scroll));
        c.add(Box.createVerticalStrut(8));
        JCheckBox stop = Mnemonics.apply(new JCheckBox(), msg.get("paste.stop-asking"));
        stop.setName("paste.stop-asking");
        c.add(left(stop));
        p.add(c, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton oneLine = Mnemonics.apply(new JButton(), msg.get("paste.one-line"));
        oneLine.setName("paste.one-line");
        oneLine.addActionListener(e -> close(Choice.ONE_LINE));
        JButton paste = Mnemonics.apply(new JButton(), msg.get("paste.paste"));
        paste.setName("paste.paste");
        paste.addActionListener(e -> close(stop.isSelected() ? Choice.PASTE_AND_STOP_ASKING : Choice.PASTE));
        JButton cancel = new JButton(msg.get("button.cancel"));
        cancel.setName("paste.cancel");
        cancel.addActionListener(e -> close(Choice.CANCEL));
        b.add(oneLine);
        b.add(paste);
        b.add(cancel);
        p.add(b, BorderLayout.SOUTH);
        setContentPane(p);
        getRootPane().setDefaultButton(cancel);
        getRootPane().registerKeyboardAction(e -> close(Choice.CANCEL), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    private static <C extends JComponent> C left(C c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private void close(Choice c) {
        choice = c;
        dispose();
    }

    static Choice ask(Window owner, Messages msg, List<String> lines) {
        PasteWarning dialog = new PasteWarning(owner, msg, lines);
        dialog.setVisible(true);
        return dialog.choice;
    }

    /** For screenshots: the dialog without waiting for an answer. */
    static JDialog preview(Window owner, Messages msg, List<String> lines) {
        PasteWarning d = new PasteWarning(owner, msg, lines);
        d.setModalityType(ModalityType.MODELESS);
        return d;
    }
}
