package eu.amadeos.apps.terminal.emulator;

/** The colours, attributes and hyperlink new characters get (set with SGR and OSC 8). */
public record Style(int foreground, int background, int attributes, int link) {
    public static final Style DEFAULT = new Style(Attr.DEFAULT, Attr.DEFAULT, 0, 0);

    public Style withForeground(int color) {
        return new Style(color, background, attributes, link);
    }

    public Style withBackground(int color) {
        return new Style(foreground, color, attributes, link);
    }

    public Style with(int attribute) {
        return new Style(foreground, background, attributes | attribute, link);
    }

    public Style without(int attribute) {
        return new Style(foreground, background, attributes & ~attribute, link);
    }

    public Style withLink(int id) {
        return new Style(foreground, background, attributes, id);
    }

    /** The style after SGR 0: no colours and attributes, the hyperlink stays (it ends with OSC 8 only). */
    public Style reset() {
        return new Style(Attr.DEFAULT, Attr.DEFAULT, 0, link);
    }
}
