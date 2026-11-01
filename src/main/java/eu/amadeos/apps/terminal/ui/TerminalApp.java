package eu.amadeos.apps.terminal.ui;

import eu.amadeos.apps.terminal.domain.LaunchOptions;
import eu.amadeos.apps.terminal.domain.Palette;
import eu.amadeos.apps.terminal.domain.ShellCommand;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import eu.amadeos.apps.terminal.repository.TerminalPreferences;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.desktop.Notification;
import eu.amadeos.commons.desktop.Notifications;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The open terminal windows of this program, the Preferences they share, and what they ask of the desktop
 * (links, folders, notifications). New windows (File > New Window) open in the same program.
 */
public final class TerminalApp {
    private static final Logger LOG = LoggerFactory.getLogger(TerminalApp.class);
    /** The handbook section about the terminal. */
    public static final String HANDBOOK = "https://docs.amadeos.eu/amadeos/42-text-editor/#terminal";

    private final Messages msg;
    private final LocaleSettings locales;
    private final TerminalPreferences preferences;
    private final DesktopLauncher launcher;
    private final Runnable exit;
    private final String version;
    private final List<MainWindow> windows = new ArrayList<>();
    private TerminalSettings settings;

    public TerminalApp(Messages msg, LocaleSettings locales, TerminalPreferences preferences, DesktopLauncher launcher,
                       String version, Runnable exit) {
        this.msg = msg;
        this.locales = locales;
        this.preferences = preferences;
        this.launcher = launcher;
        this.version = version;
        this.exit = exit;
        this.settings = preferences.load();
    }

    public Messages messages() {
        return msg;
    }

    public LocaleSettings locales() {
        return locales;
    }

    public TerminalSettings settings() {
        return settings;
    }

    public String version() {
        return version;
    }

    /** The colours of the chosen scheme; "Desktop theme" follows the current amadeOS theme. */
    public Palette palette() {
        DesktopTheme t = AmadeUI.theme();
        return settings.scheme().palette(t.fieldBackground(), t.fieldText(), t.selectionBackground(),
                t.selectionText());
    }

    /** Opens a new window with one tab. */
    public MainWindow openWindow(LaunchOptions options) {
        MainWindow window = new MainWindow(this);
        windows.add(window);
        window.newTab(options);
        window.pack();
        window.setLocationByPlatform(true);
        window.setVisible(true);
        window.focusTerminal();
        return window;
    }

    void windowClosed(MainWindow window) {
        windows.remove(window);
        if (windows.isEmpty()) exit.run();
    }

    public List<MainWindow> windows() {
        return List.copyOf(windows);
    }

    /** New Preferences: saved, and every window takes them at once. */
    public void setSettings(TerminalSettings newSettings) {
        settings = newSettings;
        try {
            preferences.save(newSettings);
        } catch (RuntimeException e) {
            LOG.warn("Could not save the preferences", e);
        }
        reapply();
    }

    /** The desktop theme changed: the "Desktop theme" colours follow. */
    public void reapply() {
        for (MainWindow w : windows) w.apply(settings, palette());
    }

    /** Starts the program of a new tab. */
    void start(TerminalTab tab, LaunchOptions options, Path directory) {
        List<String> command = ShellCommand.command(options, settings, System::getenv, ShellCommand::passwdShell);
        Map<String, String> env = ShellCommand.environment(version);
        Path dir = directory != null && Files.isDirectory(directory) ? directory
                : Path.of(System.getProperty("user.home"));
        try {
            tab.start(command, dir, env);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not start {}", command, e);
            tab.showMessage(msg.format("tab.start-failed", String.join(" ", command), e.getMessage()));
        }
    }

    void openLink(String target) {
        try {
            if (target.startsWith("/") || target.startsWith("~/")) {
                String path = target.startsWith("~/") ? System.getProperty("user.home") + target.substring(1) : target;
                launcher.open(Path.of(path));
            } else {
                launcher.browse(target);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open {}", target, e);
        }
    }

    void openFolder(Path dir) {
        try {
            launcher.open(dir);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open {}", dir, e);
        }
    }

    void openHandbook() {
        openLink(HANDBOOK);
    }

    /** A bell in a tab the user is not looking at. */
    void notifyBell(String tabTitle, Runnable show) {
        try {
            Notifications.send(Notification.of(msg.format("notify.bell.title", tabTitle))
                    .body(msg.get("notify.bell.body"))
                    .icon("amade-terminal")
                    .defaultAction(show)
                    .category("im.received")
                    .transientOnly());
        } catch (RuntimeException e) {
            LOG.debug("No notification", e);
        }
    }
}
