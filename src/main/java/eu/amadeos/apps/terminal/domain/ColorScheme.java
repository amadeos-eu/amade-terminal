package eu.amadeos.apps.terminal.domain;

import java.awt.Color;
import java.util.Arrays;
import java.util.Optional;

/**
 * The colour schemes of the Preferences. "Desktop theme" takes the text field colours of the current amadeOS
 * theme, so the terminal looks like the rest of the desktop.
 */
public enum ColorScheme {
    NIGHT("night", new Palette(0xD8D4C8, 0x1E2226, 0xD8D4C8, 0x4A5E78, 0xEEEAE0, 0xE6B84C, new int[]{
            0x2E3436, 0xE05A4F, 0x8AC25C, 0xE6B84C, 0x6A9FD8, 0xB88CC8, 0x5CB8B0, 0xD8D4C8,
            0x888C88, 0xF07A70, 0xA8DC78, 0xF6D46C, 0x8CBCF0, 0xD0A8E0, 0x7CD8D0, 0xFFFFFF})),
    PAPER("paper", new Palette(0x2A2A2A, 0xFBF7EC, 0x2A2A2A, 0xB8CCE4, 0x1A1A1A, 0xF0C850, new int[]{
            0x2A2A2A, 0xB82820, 0x3A7A1E, 0x8A6400, 0x2A58A8, 0x8A3C98, 0x1E7A78, 0xC8C4B8,
            0x6A6A66, 0xD84038, 0x4C9A2C, 0xA87C00, 0x3C70C8, 0xA850B8, 0x2C9896, 0xFFFFFF})),
    DESKTOP("desktop", null),
    GREEN("green", mono(0x33FF66, 0x0A140C)),
    AMBER("amber", mono(0xFFB000, 0x160F00));

    private final String id;
    private final Palette fixed;

    ColorScheme(String id, Palette fixed) {
        this.id = id;
        this.fixed = fixed;
    }

    public String id() {
        return id;
    }

    public String textKey() {
        return "scheme." + id;
    }

    public static Optional<ColorScheme> byId(String id) {
        return Arrays.stream(values()).filter(s -> s.id.equals(id)).findFirst();
    }

    /** The colours; "Desktop theme" needs the field colours of the theme ({@code text} on {@code field}). */
    public Palette palette(Color field, Color text, Color selection, Color selectionText) {
        if (fixed != null) return fixed;
        int bg = field.getRGB() & 0xFFFFFF;
        int fg = text.getRGB() & 0xFFFFFF;
        boolean dark = new Palette(fg, bg, fg, 0, 0, 0, new int[16]).isDark();
        int[] ansi = (dark ? NIGHT.fixed : PAPER.fixed).ansi();
        return new Palette(fg, bg, fg, selection.getRGB() & 0xFFFFFF, selectionText.getRGB() & 0xFFFFFF,
                dark ? NIGHT.fixed.searchHit() : PAPER.fixed.searchHit(), ansi);
    }

    /** A one-colour screen like old monitors: all ANSI colours as shades of the one colour. */
    private static Palette mono(int color, int background) {
        int[] ansi = new int[16];
        for (int i = 0; i < 16; i++) {
            boolean bright = i >= 8;
            int base = i % 8;
            double level = base == 0 ? 0.35 : base == 7 ? 0.85 : 0.6 + 0.04 * base;
            if (bright) level = Math.min(1.0, level + 0.15);
            ansi[i] = scale(color, level);
        }
        return new Palette(scale(color, 0.85), background, color, scale(color, 0.45), background, color, ansi);
    }

    private static int scale(int rgb, double f) {
        int r = (int) Math.round((rgb >> 16 & 0xFF) * f);
        int g = (int) Math.round((rgb >> 8 & 0xFF) * f);
        int b = (int) Math.round((rgb & 0xFF) * f);
        return r << 16 | g << 8 | b;
    }
}
