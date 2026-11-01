package eu.amadeos.apps.terminal.emulator;

/** What the {@link Parser} found in the program's output. */
public interface ParserHandler {
    /** A character to show. */
    void print(int codePoint);

    /** A C0 control character (BEL, BS, HT, LF, CR, ...). */
    void execute(int control);

    /** {@code ESC intermediates final}, e.g. {@code ESC 7} or {@code ESC ( 0}. */
    void escDispatch(String intermediates, int finalChar);

    /** {@code CSI private params intermediates final}; {@code prefix} is the private marker (?, >, =, <) or empty. */
    void csiDispatch(String prefix, Params params, String intermediates, int finalChar);

    /** An operating system command, e.g. {@code 0;title}. */
    void oscDispatch(String data);

    /** A device control string; most are ignored. */
    void dcsDispatch(String prefix, Params params, String intermediates, int finalChar, String data);
}
