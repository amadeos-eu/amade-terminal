package eu.amadeos.apps.terminal.domain;

import java.util.List;

/** Checks on text before it is pasted into the terminal. */
public final class PasteText {
    private PasteText() {
    }

    /** The lines of the text, a line break at the very end not counting as an extra empty line. */
    public static List<String> lines(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        return List.of(t.split("\n", -1));
    }

    /**
     * True if pasting runs commands at once: the text has a line break and the program does not use bracketed
     * paste (in the shell, bash uses it and a pasted block waits for Enter).
     */
    public static boolean needsWarning(String text, boolean bracketedPaste) {
        return !bracketedPaste && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0);
    }

    /** "Paste as One Line": the lines joined with spaces, no line break at the end. */
    public static String oneLine(String text) {
        return String.join(" ", lines(text).stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
    }
}
