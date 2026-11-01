package eu.amadeos.apps.terminal.bootstrap;

import eu.amadeos.apps.terminal.domain.LaunchOptions;
import eu.amadeos.apps.terminal.ui.TerminalApp;
import eu.amadeos.commons.app.AmadeApplication;
import eu.amadeos.commons.app.AppInfo;
import eu.amadeos.commons.theme.ThemeStore;

import javax.swing.SwingUtilities;

public final class AmadeTerminalApplication {
    public static final AppInfo APP = new AppInfo("amade-terminal", "Terminal", "terminal");

    private AmadeTerminalApplication() {
    }

    /** The version from the jar's manifest; "dev" when run from the IDE. */
    public static String version() {
        String version = AmadeTerminalApplication.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    // --8<-- [start:main]
    public static void main(String[] args) {
        LaunchOptions options = LaunchOptions.parse(args);
        if (options.version()) {
            System.out.println("amade-terminal " + version());
            return;
        }
        AmadeApplication.launch(APP, injector -> {
            TerminalApp app = injector.getInstance(TerminalApp.class);
            // "Desktop theme" colours follow the amadeOS theme while the terminal runs
            injector.getInstance(ThemeStore.class).addListener(t -> SwingUtilities.invokeLater(app::reapply));
            app.openWindow(options);
        }, new TerminalModule());
    }
    // --8<-- [end:main]
}
