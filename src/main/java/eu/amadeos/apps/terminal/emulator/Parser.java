package eu.amadeos.apps.terminal.emulator;

/**
 * The state machine that splits the program's output into characters and control sequences, after the DEC/ANSI
 * parser state diagram (vt100.net/emu/dec_ansi_parser) that xterm and most terminals follow. It works on code
 * points, so UTF-8 is decoded before; C1 controls as single bytes are not used (the 8-bit range is UTF-8).
 */
public final class Parser {
    private enum State {
        GROUND, ESCAPE, ESCAPE_INTERMEDIATE, CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, CSI_IGNORE,
        DCS_ENTRY, DCS_PARAM, DCS_INTERMEDIATE, DCS_PASSTHROUGH, DCS_IGNORE, OSC_STRING, SOS_PM_APC_STRING
    }

    /** OSC 52 (clipboard) can carry large texts; longer strings are cut. */
    static final int MAX_STRING = 1 << 20;

    private final ParserHandler handler;
    private final Params params = new Params();
    private final StringBuilder intermediates = new StringBuilder();
    private final StringBuilder string = new StringBuilder();
    private String prefix = "";
    private int dcsFinal;
    private State state = State.GROUND;

    public Parser(ParserHandler handler) {
        this.handler = handler;
    }

    public void reset() {
        state = State.GROUND;
        clear();
    }

    public void accept(int c) {
        // "anywhere" transitions
        if (c == 0x18 || c == 0x1A) {
            leaveString();
            handler.execute(c);
            state = State.GROUND;
            return;
        }
        if (c == 0x1B) {
            leaveString();
            clear();
            state = State.ESCAPE;
            return;
        }
        switch (state) {
            case GROUND -> {
                if (c < 0x20) handler.execute(c);
                else if (c == 0x7F) { /* ignored */ }
                else if (c >= 0x80 && c < 0xA0) { /* C1 in UTF-8: ignored */ }
                else handler.print(c);
            }
            case ESCAPE -> {
                if (c < 0x20) handler.execute(c);
                else if (c <= 0x2F) { intermediates.appendCodePoint(c); state = State.ESCAPE_INTERMEDIATE; }
                else if (c == '[') { clear(); state = State.CSI_ENTRY; }
                else if (c == ']') { clear(); state = State.OSC_STRING; }
                else if (c == 'P') { clear(); state = State.DCS_ENTRY; }
                else if (c == 'X' || c == '^' || c == '_') state = State.SOS_PM_APC_STRING;
                else if (c <= 0x7E) { handler.escDispatch("", c); state = State.GROUND; }
            }
            case ESCAPE_INTERMEDIATE -> {
                if (c < 0x20) handler.execute(c);
                else if (c <= 0x2F) intermediates.appendCodePoint(c);
                else if (c <= 0x7E) { handler.escDispatch(intermediates.toString(), c); state = State.GROUND; }
            }
            case CSI_ENTRY, CSI_PARAM -> csiParam(c);
            case CSI_INTERMEDIATE -> {
                if (c < 0x20) handler.execute(c);
                else if (c <= 0x2F) intermediates.appendCodePoint(c);
                else if (c <= 0x3F) state = State.CSI_IGNORE;
                else if (c <= 0x7E) csiDispatch(c);
            }
            case CSI_IGNORE -> {
                if (c < 0x20) handler.execute(c);
                else if (c >= 0x40 && c <= 0x7E) state = State.GROUND;
            }
            case DCS_ENTRY, DCS_PARAM -> dcsParam(c);
            case DCS_INTERMEDIATE -> {
                if (c < 0x20) { /* ignored */ }
                else if (c <= 0x2F) intermediates.appendCodePoint(c);
                else if (c <= 0x3F) state = State.DCS_IGNORE;
                else if (c <= 0x7E) { dcsFinal = c; state = State.DCS_PASSTHROUGH; }
            }
            case DCS_PASSTHROUGH -> {
                if (c != 0x7F && string.length() < MAX_STRING) string.appendCodePoint(c);
            }
            case DCS_IGNORE, SOS_PM_APC_STRING -> { /* until ST */ }
            case OSC_STRING -> {
                if (c == 0x07) {
                    handler.oscDispatch(string.toString());
                    string.setLength(0);
                    state = State.GROUND;
                } else if (c >= 0x20 && string.length() < MAX_STRING) {
                    string.appendCodePoint(c);
                }
            }
        }
    }

    private void csiParam(int c) {
        if (c < 0x20) {
            handler.execute(c);
        } else if (c >= '0' && c <= '9') {
            params.digit(c - '0');
            state = State.CSI_PARAM;
        } else if (c == ';' || c == ':') {
            if (params.size() == 0) params.begin(false);
            params.begin(c == ':');
            state = State.CSI_PARAM;
        } else if (c >= 0x3C && c <= 0x3F) {
            if (state == State.CSI_ENTRY && prefix.isEmpty()) prefix = Character.toString(c);
            else state = State.CSI_IGNORE;
        } else if (c <= 0x2F) {
            intermediates.appendCodePoint(c);
            state = State.CSI_INTERMEDIATE;
        } else if (c >= 0x40 && c <= 0x7E) {
            csiDispatch(c);
        }
    }

    private void dcsParam(int c) {
        if (c < 0x20) {
            // ignored
        } else if (c >= '0' && c <= '9') {
            params.digit(c - '0');
            state = State.DCS_PARAM;
        } else if (c == ';' || c == ':') {
            if (params.size() == 0) params.begin(false);
            params.begin(c == ':');
            state = State.DCS_PARAM;
        } else if (c >= 0x3C && c <= 0x3F) {
            if (state == State.DCS_ENTRY && prefix.isEmpty()) prefix = Character.toString(c);
            else state = State.DCS_IGNORE;
        } else if (c <= 0x2F) {
            intermediates.appendCodePoint(c);
            state = State.DCS_INTERMEDIATE;
        } else if (c >= 0x40 && c <= 0x7E) {
            dcsFinal = c;
            state = State.DCS_PASSTHROUGH;
        }
    }

    private void csiDispatch(int c) {
        if (params.size() > 0 && params.raw(params.size() - 1) == Params.OMITTED && params.size() == 1) {
            params.clear();
        }
        handler.csiDispatch(prefix, params, intermediates.toString(), c);
        state = State.GROUND;
    }

    /** Leaving an OSC or DCS string (with ESC, CAN or SUB) ends it. */
    private void leaveString() {
        if (state == State.OSC_STRING) {
            handler.oscDispatch(string.toString());
        } else if (state == State.DCS_PASSTHROUGH) {
            handler.dcsDispatch(prefix, params, intermediates.toString(), dcsFinal, string.toString());
        }
        string.setLength(0);
    }

    private void clear() {
        params.clear();
        intermediates.setLength(0);
        string.setLength(0);
        prefix = "";
    }
}
