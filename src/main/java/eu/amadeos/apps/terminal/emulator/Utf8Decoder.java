package eu.amadeos.apps.terminal.emulator;

import java.util.function.IntConsumer;

/**
 * Turns the bytes from the program into code points, also when a character is split between two reads. Broken
 * sequences become U+FFFD, as in other terminals.
 */
public final class Utf8Decoder {
    public static final int REPLACEMENT = 0xFFFD;

    private int codePoint;
    private int needed;
    private int min;

    public void decode(byte[] bytes, int offset, int length, IntConsumer out) {
        for (int i = offset; i < offset + length; i++) {
            int b = bytes[i] & 0xFF;
            if (needed > 0) {
                if ((b & 0xC0) == 0x80) {
                    codePoint = codePoint << 6 | (b & 0x3F);
                    if (--needed == 0) {
                        if (codePoint < min || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                            out.accept(REPLACEMENT);
                        } else {
                            out.accept(codePoint);
                        }
                    }
                    continue;
                }
                needed = 0;
                out.accept(REPLACEMENT);
            }
            if (b < 0x80) {
                out.accept(b);
            } else if ((b & 0xE0) == 0xC0) {
                codePoint = b & 0x1F; needed = 1; min = 0x80;
            } else if ((b & 0xF0) == 0xE0) {
                codePoint = b & 0x0F; needed = 2; min = 0x800;
            } else if ((b & 0xF8) == 0xF0) {
                codePoint = b & 0x07; needed = 3; min = 0x10000;
            } else {
                out.accept(REPLACEMENT);
            }
        }
    }

    public void reset() {
        needed = 0;
    }
}
