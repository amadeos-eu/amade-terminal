package eu.amadeos.apps.terminal.domain;

import java.util.Arrays;

/**
 * The colours of a scheme as {@code 0xRRGGBB}: text, background, cursor, selection and the 16 ANSI colours
 * (0-7 normal, 8-15 bright).
 */
public record Palette(int foreground, int background, int cursor, int selectionBackground, int selectionForeground,
                      int searchHit, int[] ansi) {
    public Palette {
        if (ansi.length != 16) throw new IllegalArgumentException("16 ANSI colours needed");
        ansi = ansi.clone();
    }

    public int ansi(int index) {
        return ansi[index];
    }

    @Override
    public int[] ansi() {
        return ansi.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Palette p && p.foreground == foreground && p.background == background
                && p.cursor == cursor && p.selectionBackground == selectionBackground
                && p.selectionForeground == selectionForeground && p.searchHit == searchHit
                && Arrays.equals(p.ansi, ansi);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(ansi) + foreground * 7 + background;
    }

    /** True if the background is dark (so the window can pick a fitting look for its scrollbar and find bar). */
    public boolean isDark() {
        int r = background >> 16 & 0xFF, g = background >> 8 & 0xFF, b = background & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b < 128;
    }
}
