package eu.amadeos.apps.terminal.repository;

import com.google.inject.Inject;
import eu.amadeos.apps.terminal.domain.ColorScheme;
import eu.amadeos.apps.terminal.domain.CursorShape;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.config.PropertiesFile;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * The Preferences of amade-terminal in {@code ~/.config/amadeos/terminal/terminal.properties}. Unknown or broken
 * values fall back to the defaults.
 */
public class TerminalPreferences {
    public static final String FILE = "terminal.properties";

    private final Path file;

    @Inject
    public TerminalPreferences(AppDirectories directories) {
        this(directories.configFile(FILE));
    }

    public TerminalPreferences(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public TerminalSettings load() {
        Properties p = PropertiesFile.load(file);
        TerminalSettings d = TerminalSettings.DEFAULTS;
        return new TerminalSettings(
                ColorScheme.byId(p.getProperty("scheme", "")).orElse(d.scheme()),
                p.getProperty("font.family", d.fontFamily()),
                integer(p, "font.size", d.fontSize()),
                CursorShape.byId(p.getProperty("cursor.shape", "")).orElse(d.cursor()),
                bool(p, "cursor.blink", d.cursorBlink()),
                integer(p, "window.columns", d.columns()),
                integer(p, "window.rows", d.rows()),
                integer(p, "scrollback", d.scrollback()),
                bool(p, "bold.bright", d.brightBold()),
                p.getProperty("command", d.command()),
                bool(p, "login-shell", d.loginShell()),
                bool(p, "paste.warning", d.pasteWarning()),
                bool(p, "bell.sound", d.bellSound()),
                bool(p, "bell.flash", d.bellFlash()),
                bool(p, "bell.notify", d.bellNotify()),
                bool(p, "close.confirm", d.confirmClose()),
                bool(p, "clipboard.allow", d.allowClipboard()));
    }

    public void save(TerminalSettings s) {
        Properties p = new Properties();
        p.setProperty("scheme", s.scheme().id());
        p.setProperty("font.family", s.fontFamily());
        p.setProperty("font.size", Integer.toString(s.fontSize()));
        p.setProperty("cursor.shape", s.cursor().id());
        p.setProperty("cursor.blink", Boolean.toString(s.cursorBlink()));
        p.setProperty("window.columns", Integer.toString(s.columns()));
        p.setProperty("window.rows", Integer.toString(s.rows()));
        p.setProperty("scrollback", Integer.toString(s.scrollback()));
        p.setProperty("bold.bright", Boolean.toString(s.brightBold()));
        p.setProperty("command", s.command());
        p.setProperty("login-shell", Boolean.toString(s.loginShell()));
        p.setProperty("paste.warning", Boolean.toString(s.pasteWarning()));
        p.setProperty("bell.sound", Boolean.toString(s.bellSound()));
        p.setProperty("bell.flash", Boolean.toString(s.bellFlash()));
        p.setProperty("bell.notify", Boolean.toString(s.bellNotify()));
        p.setProperty("close.confirm", Boolean.toString(s.confirmClose()));
        p.setProperty("clipboard.allow", Boolean.toString(s.allowClipboard()));
        PropertiesFile.store(file, p, "amade-terminal preferences");
    }

    private static int integer(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "").strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.strip());
    }
}
