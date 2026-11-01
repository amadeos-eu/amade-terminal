package eu.amadeos.apps.terminal.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import eu.amadeos.apps.terminal.repository.TerminalPreferences;
import eu.amadeos.apps.terminal.ui.TerminalApp;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;

import java.util.Locale;

/**
 * amade-terminal bindings. Theme, language, directories and the other shared services come from
 * {@link eu.amadeos.commons.app.CommonsModule}.
 */
public final class TerminalModule extends AbstractModule {
    /** The texts, {@code messages.properties} and its translations. */
    public static final String MESSAGES = "eu.amadeos.apps.terminal.messages";

    @Provides
    @Singleton
    Messages messages(Locale locale) {
        return Messages.load(TerminalModule.class, MESSAGES, locale);
    }

    @Provides
    @Singleton
    TerminalPreferences preferences(AppDirectories directories) {
        return new TerminalPreferences(directories);
    }

    @Provides
    @Singleton
    TerminalApp app(Messages msg, LocaleSettings locales, TerminalPreferences preferences, DesktopLauncher launcher) {
        return new TerminalApp(msg, locales, preferences, launcher, AmadeTerminalApplication.version(),
                () -> System.exit(0));
    }
}
