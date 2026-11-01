package eu.amadeos.apps.terminal.domain;

import java.nio.file.Path;

/** Short tab titles from what the shell or program sets. */
public final class TabTitles {
    public static final int MAX = 28;

    private TabTitles() {
    }

    /**
     * The tab title for a window title: "ralf@amadeos: ~/Documents" (the prompt of Debian's bash) becomes
     * "~/Documents"; long titles are cut in the middle.
     */
    public static String shorten(String title) {
        String t = title == null ? "" : title.strip();
        int colon = t.indexOf(": ");
        if (colon > 0 && t.substring(0, colon).matches("[^\\s@]+@[^\\s:]+")) t = t.substring(colon + 2);
        if (t.length() > MAX) t = t.substring(0, MAX / 2 - 1) + "…" + t.substring(t.length() - (MAX - MAX / 2));
        return t;
    }

    /** A folder as the shell shows it: the home folder as ~. */
    public static String folder(Path dir, Path home) {
        if (dir == null) return "";
        if (home != null && dir.equals(home)) return "~";
        if (home != null && dir.startsWith(home)) return "~/" + home.relativize(dir);
        return dir.toString();
    }
}
