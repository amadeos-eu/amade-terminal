package eu.amadeos.apps.terminal.ui;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;

/**
 * Draws the box drawing characters (U+2500 to U+257F) and block elements (U+2580 to U+259F) as lines and areas
 * that fill the whole cell, so the frames of mc, htop or dialog have no gaps between rows, whatever the font.
 */
final class BoxDrawing {
    /** For U+2500..U+257F: weights up, right, down, left (0 none, 1 light, 2 heavy, 3 double); "...." = font. */
    private static final String LINES =
            "0101" + "0202" + "1010" + "2020" + "0101" + "0202" + "1010" + "2020"
                    + "0101" + "0202" + "1010" + "2020" + "0110" + "0210" + "0120" + "0220"
                    + "0011" + "0012" + "0021" + "0022" + "1100" + "1200" + "2100" + "2200"
                    + "1001" + "1002" + "2001" + "2002" + "1110" + "1210" + "2110" + "1120"
                    + "2120" + "2210" + "1220" + "2220" + "1011" + "1012" + "2011" + "1021"
                    + "2021" + "2012" + "1022" + "2022" + "0111" + "0112" + "0211" + "0212"
                    + "0121" + "0122" + "0221" + "0222" + "1101" + "1102" + "1201" + "1202"
                    + "2101" + "2102" + "2201" + "2202" + "1111" + "1112" + "1211" + "1212"
                    + "2111" + "1121" + "2121" + "2112" + "2211" + "1122" + "1221" + "2212"
                    + "1222" + "2122" + "2221" + "2222" + "0101" + "0202" + "1010" + "2020"
                    + "0303" + "3030" + "0310" + "0130" + "0330" + "0013" + "0031" + "0033"
                    + "1300" + "3100" + "3300" + "1003" + "3001" + "3003" + "1310" + "3130"
                    + "3330" + "1013" + "3031" + "3033" + "0313" + "0131" + "0333" + "1303"
                    + "3101" + "3303" + "1313" + "3131" + "3333" + "0110" + "0011" + "1001"
                    + "1100" + "...." + "...." + "...." + "0001" + "1000" + "0100" + "0010"
                    + "0002" + "2000" + "0200" + "0020" + "0201" + "1020" + "0102" + "2010";

    private BoxDrawing() {
    }

    static boolean handles(int cp) {
        if (cp >= 0x2580 && cp <= 0x259F) return true;
        if (cp < 0x2500 || cp > 0x257F) return false;
        return LINES.charAt((cp - 0x2500) * 4) != '.';
    }

    /** Draws the character in the cell at x, y of w x h with the current colour. */
    static void draw(Graphics2D g, int cp, int x, int y, int w, int h) {
        if (cp >= 0x2580) {
            block(g, cp, x, y, w, h);
            return;
        }
        int i = (cp - 0x2500) * 4;
        int up = LINES.charAt(i) - '0', right = LINES.charAt(i + 1) - '0';
        int down = LINES.charAt(i + 2) - '0', left = LINES.charAt(i + 3) - '0';
        int t = Math.max(1, Math.round(w / 8f));
        int cx = x + (w - t) / 2;
        int cy = y + (h - t) / 2;
        segment(g, up, cx, y, t, cy - y + t, true);
        segment(g, down, cx, cy, t, y + h - cy, true);
        segment(g, left, x, cy, cx - x + t, t, false);
        segment(g, right, cx, cy, x + w - cx, t, false);
    }

    private static void segment(Graphics2D g, int weight, int x, int y, int w, int h, boolean vertical) {
        if (weight == 0) return;
        int t = vertical ? w : h;
        if (weight == 1) {
            g.fillRect(x, y, w, h);
        } else if (weight == 2) {
            if (vertical) g.fillRect(x - t / 2, y, w + t, h);
            else g.fillRect(x, y - t / 2, w, h + t);
        } else {
            int gap = Math.max(1, t);
            if (vertical) {
                g.fillRect(x - gap, y, t, h);
                g.fillRect(x + gap, y, t, h);
            } else {
                g.fillRect(x, y - gap, w, t);
                g.fillRect(x, y + gap, w, t);
            }
        }
    }

    private static void block(Graphics2D g, int cp, int x, int y, int w, int h) {
        int hw = w / 2, hh = h / 2;
        switch (cp) {
            case 0x2580 -> g.fillRect(x, y, w, hh);
            case 0x2590 -> g.fillRect(x + hw, y, w - hw, h);
            case 0x2591, 0x2592, 0x2593 -> {
                Composite c = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (cp - 0x2590) * 0.25f));
                g.fillRect(x, y, w, h);
                g.setComposite(c);
            }
            case 0x2594 -> g.fillRect(x, y, w, Math.max(1, h / 8));
            case 0x2595 -> g.fillRect(x + w - Math.max(1, w / 8), y, Math.max(1, w / 8), h);
            case 0x2596 -> g.fillRect(x, y + hh, hw, h - hh);
            case 0x2597 -> g.fillRect(x + hw, y + hh, w - hw, h - hh);
            case 0x2598 -> g.fillRect(x, y, hw, hh);
            case 0x259D -> g.fillRect(x + hw, y, w - hw, hh);
            case 0x2599 -> quads(g, x, y, w, h, true, false, true, true);
            case 0x259A -> quads(g, x, y, w, h, true, false, false, true);
            case 0x259B -> quads(g, x, y, w, h, true, true, true, false);
            case 0x259C -> quads(g, x, y, w, h, true, true, false, true);
            case 0x259E -> quads(g, x, y, w, h, false, true, true, false);
            case 0x259F -> quads(g, x, y, w, h, false, true, true, true);
            default -> {
                if (cp >= 0x2581 && cp <= 0x2588) {
                    int part = Math.round(h * (cp - 0x2580) / 8f);
                    g.fillRect(x, y + h - part, w, part);
                } else if (cp >= 0x2589 && cp <= 0x258F) {
                    int part = Math.round(w * (0x2590 - cp) / 8f);
                    g.fillRect(x, y, part, h);
                }
            }
        }
    }

    private static void quads(Graphics2D g, int x, int y, int w, int h, boolean ul, boolean ur, boolean ll, boolean lr) {
        int hw = w / 2, hh = h / 2;
        if (ul) g.fillRect(x, y, hw, hh);
        if (ur) g.fillRect(x + hw, y, w - hw, hh);
        if (ll) g.fillRect(x, y + hh, hw, h - hh);
        if (lr) g.fillRect(x + hw, y + hh, w - hw, h - hh);
    }
}
