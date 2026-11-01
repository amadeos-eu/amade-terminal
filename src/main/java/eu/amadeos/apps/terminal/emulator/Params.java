package eu.amadeos.apps.terminal.emulator;

/**
 * The numeric parameters of a control sequence. A parameter that was left out is -1. Parameters joined with a
 * colon (SGR {@code 38:2::255:0:0}) are marked as sub-parameters of the one before.
 */
public final class Params {
    public static final int MAX = 32;
    static final int OMITTED = -1;

    private final int[] values = new int[MAX];
    private final boolean[] sub = new boolean[MAX];
    private int count;

    void clear() {
        count = 0;
    }

    void begin(boolean subParameter) {
        if (count < MAX) {
            values[count] = OMITTED;
            sub[count] = subParameter;
            count++;
        }
    }

    void digit(int d) {
        if (count == 0) begin(false);
        int i = count - 1;
        int v = values[i] == OMITTED ? 0 : values[i];
        values[i] = Math.min(65535, v * 10 + d);
    }

    public int size() {
        return count;
    }

    /** The raw value: -1 when left out (or beyond the end). */
    public int raw(int i) {
        return i < count ? values[i] : OMITTED;
    }

    /** The value, or {@code fallback} when left out. */
    public int get(int i, int fallback) {
        int v = raw(i);
        return v == OMITTED ? fallback : v;
    }

    /** The value, or {@code fallback} when left out or 0 (the rule for counts and positions). */
    public int count(int i, int fallback) {
        int v = raw(i);
        return v <= 0 ? fallback : v;
    }

    /** True if parameter {@code i} was joined to the one before with a colon. */
    public boolean isSub(int i) {
        return i < count && sub[i];
    }

    /** For tests: builds parameters from values, -1 for a left-out one. */
    public static Params of(int... values) {
        Params p = new Params();
        for (int v : values) {
            p.begin(false);
            if (v >= 0) p.values[p.count - 1] = v;
        }
        return p;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(sub[i] ? ':' : ';');
            if (values[i] != OMITTED) sb.append(values[i]);
        }
        return sb.toString();
    }
}
