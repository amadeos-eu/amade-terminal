package eu.amadeos.apps.terminal.emulator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading text out of the emulator: the text of a selection, words for double-click, links under the mouse and
 * search hits. Lines are the emulator's {@link TerminalEmulator#line(int)} indices; rows that the program wrote
 * in one go (marked as wrapped) count as one line, so a long path or URL is found and selected as a whole.
 */
public final class TextModel {
    /** Characters that belong to a word for double-click, besides letters and digits: paths and URLs stay whole. */
    public static final String WORD_CHARS = "-_.~/:@%+#?&=$!*";

    private static final Pattern URL = Pattern.compile(
            "(?:https?|ftp|file|mailto)://?[^\\s<>\"'`{}|\\\\^]+|www\\.[^\\s<>\"'`{}|\\\\^]+\\.[^\\s<>\"'`{}|\\\\^]+");

    /** A range from (line, col) to (endLine, endCol), the end being exclusive. */
    public record Range(int line, int col, int endLine, int endCol) {
        public boolean contains(int l, int c) {
            if (l < line || l > endLine) return false;
            if (l == line && c < col) return false;
            return l != endLine || c < endCol;
        }
    }

    /** A link: its target and where it is on the screen. */
    public record Link(String target, Range range) {
    }

    private TextModel() {
    }

    // ---------------------------------------------------------------- selection text

    /**
     * The text from (fromLine, fromCol) to (toLine, toCol), the end exclusive. Rows end with a line break unless
     * they wrap into the next one; blanks at the end of a row are left out.
     */
    public static String text(TerminalEmulator e, int fromLine, int fromCol, int toLine, int toCol) {
        int total = e.historySize() + e.rows();
        fromLine = Math.max(0, fromLine);
        toLine = Math.min(total - 1, toLine);
        StringBuilder sb = new StringBuilder();
        for (int l = fromLine; l <= toLine; l++) {
            Line line = e.line(l);
            int from = l == fromLine ? fromCol : 0;
            int to = l == toLine ? toCol : line.columns();
            boolean wrapsOn = line.wrapped() && l < toLine;
            String part = line.text(from, to);
            if (!wrapsOn) part = stripTrailing(part);
            sb.append(part);
            if (l < toLine && !line.wrapped()) sb.append('\n');
        }
        return sb.toString();
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.substring(0, end);
    }

    // ---------------------------------------------------------------- logical lines

    /** The text of one logical line and where each char of it is on the screen. */
    public static final class Logical {
        final String text;
        final int[] lines;
        final int[] cols;
        final int[] widths;
        final int first;
        final int last;

        Logical(String text, int[] lines, int[] cols, int[] widths, int first, int last) {
            this.text = text;
            this.lines = lines;
            this.cols = cols;
            this.widths = widths;
            this.first = first;
            this.last = last;
        }

        public String text() {
            return text;
        }

        /** The screen range of the chars {@code start} to {@code end} (exclusive). */
        public Range range(int start, int end) {
            int s = Math.min(start, text.length() - 1);
            int e = Math.max(s, end - 1);
            return new Range(lines[s], cols[s], lines[e], cols[e] + widths[e]);
        }

        /** The char index at a cell, or -1. */
        public int indexOf(int line, int col) {
            for (int i = 0; i < text.length(); i++) {
                if (lines[i] == line && col >= cols[i] && col < cols[i] + widths[i]) return i;
            }
            return -1;
        }
    }

    /** The logical line around physical line {@code line}. */
    public static Logical logical(TerminalEmulator e, int line) {
        int first = line;
        while (first > 0 && e.line(first - 1).wrapped()) first--;
        int total = e.historySize() + e.rows();
        int last = line;
        while (last < total - 1 && e.line(last).wrapped()) last++;
        return logical(e, first, last);
    }

    static Logical logical(TerminalEmulator e, int first, int last) {
        StringBuilder sb = new StringBuilder();
        List<int[]> map = new ArrayList<>();
        for (int l = first; l <= last; l++) {
            Line line = e.line(l);
            int len = l < last ? line.columns() : line.usedLength();
            for (int c = 0; c < len; c++) {
                int cp = line.codePoint(c);
                if (cp == Line.WIDE_TAIL) continue;
                int w = line.isWide(c) ? 2 : 1;
                String s = Character.toString(cp == Line.EMPTY ? ' ' : cp);
                String comb = line.combining(c);
                if (comb != null) s += comb;
                for (int k = 0; k < s.length(); k++) map.add(new int[]{l, c, w});
                sb.append(s);
            }
        }
        int n = map.size();
        int[] lines = new int[n], cols = new int[n], widths = new int[n];
        for (int i = 0; i < n; i++) {
            lines[i] = map.get(i)[0];
            cols[i] = map.get(i)[1];
            widths[i] = map.get(i)[2];
        }
        return new Logical(sb.toString(), lines, cols, widths, first, last);
    }

    // ---------------------------------------------------------------- words and links

    /** The word at a cell for double-click; a blank cell gives just itself. */
    public static Range word(TerminalEmulator e, int line, int col) {
        Logical lg = logical(e, line);
        int i = lg.indexOf(line, col);
        if (i < 0 || !isWordChar(lg.text.charAt(i))) return new Range(line, col, line, col + 1);
        int s = i, t = i;
        while (s > 0 && isWordChar(lg.text.charAt(s - 1))) s--;
        while (t < lg.text.length() - 1 && isWordChar(lg.text.charAt(t + 1))) t++;
        // a word does not end with punctuation (the full stop after a path in a sentence)
        while (t > s && ".,;:!?".indexOf(lg.text.charAt(t)) >= 0) t--;
        return lg.range(s, t + 1);
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || WORD_CHARS.indexOf(c) >= 0 || Character.isSurrogate(c);
    }

    /** The whole logical line for triple-click. */
    public static Range wholeLine(TerminalEmulator e, int line) {
        Logical lg = logical(e, line);
        return new Range(lg.first, 0, lg.last, e.columns());
    }

    /**
     * The link at a cell: a hyperlink the program set (OSC 8) or a URL in the text. Returns null if there is
     * none.
     */
    public static Link linkAt(TerminalEmulator e, int line, int col) {
        int total = e.historySize() + e.rows();
        if (line < 0 || line >= total || col < 0 || col >= e.columns()) return null;
        Line l = e.line(line);
        int id = l.link(col);
        if (id != 0 && e.link(id) != null) {
            int s = col, t = col;
            while (s > 0 && l.link(s - 1) == id) s--;
            while (t < l.columns() - 1 && l.link(t + 1) == id) t++;
            return new Link(e.link(id), new Range(line, s, line, t + 1));
        }
        Logical lg = logical(e, line);
        int i = lg.indexOf(line, col);
        if (i < 0) return null;
        Matcher m = URL.matcher(lg.text);
        while (m.find()) {
            int end = trimEnd(lg.text, m.start(), m.end());
            if (i >= m.start() && i < end) {
                String target = lg.text.substring(m.start(), end);
                if (target.startsWith("www.")) target = "https://" + target;
                return new Link(target, lg.range(m.start(), end));
            }
        }
        return null;
    }

    /** URLs often stand before a full stop or inside brackets; those are not part of them. */
    static int trimEnd(String text, int start, int end) {
        while (end > start) {
            char c = text.charAt(end - 1);
            if (".,;:!?'\"".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' || c == ']') {
                String inside = text.substring(start, end);
                char open = c == ')' ? '(' : '[';
                if (count(inside, open) < count(inside, c)) end--;
                else break;
            } else {
                break;
            }
        }
        return end;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    // ---------------------------------------------------------------- search

    /**
     * All places of {@code query} in the scrollback and on the screen, oldest first; at most {@code limit}.
     * A broken regular expression gives no hits.
     */
    public static List<Range> find(TerminalEmulator e, String query, boolean matchCase, boolean regex, int limit) {
        List<Range> hits = new ArrayList<>();
        if (query.isEmpty()) return hits;
        Pattern p;
        try {
            int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            p = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (RuntimeException ex) {
            return hits;
        }
        int total = e.historySize() + e.rows();
        int l = 0;
        while (l < total && hits.size() < limit) {
            int first = l;
            while (l < total - 1 && e.line(l).wrapped()) l++;
            Logical lg = logical(e, first, l);
            l++;
            if (lg.text.isEmpty()) continue;
            Matcher m = p.matcher(lg.text);
            while (m.find() && hits.size() < limit) {
                if (m.end() == m.start()) {
                    if (m.end() >= lg.text.length()) break;
                    continue;
                }
                hits.add(lg.range(m.start(), m.end()));
            }
        }
        return hits;
    }
}
