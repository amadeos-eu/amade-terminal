package eu.amadeos.apps.terminal.ui;

import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/** Find in the scrollback: below the terminal, Enter for the next hit, Shift+Enter the previous, Esc closes. */
final class FindBar extends JPanel {
    interface Search {
        void run(String query, boolean matchCase, boolean regex, int step);
    }

    private final Messages msg;
    private final Search search;
    private final JTextField field = new JTextField(18);
    private final JCheckBox matchCase;
    private final JCheckBox regex;
    private final JLabel result = new JLabel();
    private int current = -1;

    FindBar(Messages msg, Search search, Runnable close) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 3));
        this.msg = msg;
        this.search = search;
        setName("find-bar");
        setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        JLabel label = Mnemonics.apply(new JLabel(), msg.get("find.label"));
        label.setLabelFor(field);
        field.setName("find.field");
        add(label);
        add(field);
        JButton previous = Mnemonics.apply(new JButton(), msg.get("find.previous"));
        previous.setName("find.previous");
        previous.addActionListener(e -> run(-1));
        JButton next = Mnemonics.apply(new JButton(), msg.get("find.next"));
        next.setName("find.next");
        next.addActionListener(e -> run(1));
        matchCase = Mnemonics.apply(new JCheckBox(), msg.get("find.match-case"));
        matchCase.setName("find.match-case");
        matchCase.addActionListener(e -> run(0));
        regex = Mnemonics.apply(new JCheckBox(), msg.get("find.regex"));
        regex.setName("find.regex");
        regex.addActionListener(e -> run(0));
        JButton closeButton = new JButton("×");
        closeButton.setName("find.close");
        closeButton.setToolTipText(msg.get("find.close"));
        closeButton.addActionListener(e -> close.run());
        result.setName("find.result");
        add(previous);
        add(next);
        add(matchCase);
        add(regex);
        add(result);
        add(closeButton);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                run(0);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                run(0);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next", () -> run(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previous", () -> run(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", close);
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        AbstractAction a = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };
        field.getActionMap().put(name, a);
        getActionMap().put(name, a);
    }

    private void run(int step) {
        search.run(field.getText(), matchCase.isSelected(), regex.isSelected(), step);
    }

    void setQuery(String text) {
        field.setText(text);
    }

    void focusField() {
        field.requestFocusInWindow();
        field.selectAll();
        run(0);
    }

    int current() {
        return current;
    }

    /** "3 of 12", "No matches" or nothing for an empty field. */
    void showResult(int index, int count) {
        current = index;
        if (field.getText().isEmpty()) result.setText("");
        else if (count == 0) result.setText(msg.get("find.none"));
        else result.setText(msg.format("find.count", index + 1, count));
    }

    String resultText() {
        return result.getText();
    }

    JTextField field() {
        return field;
    }
}
