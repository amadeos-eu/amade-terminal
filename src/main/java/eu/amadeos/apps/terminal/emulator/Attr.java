package eu.amadeos.apps.terminal.emulator;

/**
 * The attributes of a cell as bits of an {@code int}, and the encoding of colours: {@link #DEFAULT} is the
 * colour scheme's foreground or background, 0 to 255 are the indexed colours (16 of the scheme, the 6x6x6 cube
 * and 24 greys), and {@link #rgb(int)} marks a true colour.
 */
public final class Attr {
    public static final int BOLD = 1;
    public static final int DIM = 1 << 1;
    public static final int ITALIC = 1 << 2;
    public static final int UNDERLINE = 1 << 3;
    public static final int BLINK = 1 << 4;
    public static final int INVERSE = 1 << 5;
    public static final int HIDDEN = 1 << 6;
    public static final int STRIKE = 1 << 7;
    public static final int DOUBLE_UNDERLINE = 1 << 8;

    /** The colour of the scheme (foreground or background). */
    public static final int DEFAULT = -1;
    private static final int RGB_FLAG = 0x1000000;

    private Attr() {
    }

    /** A true colour, {@code 0xRRGGBB}. */
    public static int rgb(int rgb) {
        return RGB_FLAG | (rgb & 0xFFFFFF);
    }

    public static boolean isRgb(int color) {
        return color >= RGB_FLAG;
    }

    public static boolean isIndexed(int color) {
        return color >= 0 && color < 256;
    }

    /** The {@code 0xRRGGBB} of a true colour. */
    public static int rgbValue(int color) {
        return color & 0xFFFFFF;
    }

    /**
     * The colour of an index of the 256 colour palette beyond the 16 of the scheme: the 6x6x6 cube (16-231) and
     * the grey ramp (232-255), as xterm defines them.
     */
    public static int paletteRgb(int index) {
        if (index < 16 || index > 255) throw new IllegalArgumentException("not a fixed palette colour: " + index);
        if (index >= 232) {
            int v = 8 + (index - 232) * 10;
            return v << 16 | v << 8 | v;
        }
        int i = index - 16;
        return level(i / 36) << 16 | level(i / 6 % 6) << 8 | level(i % 6);
    }

    private static int level(int step) {
        return step == 0 ? 0 : 55 + step * 40;
    }
}
