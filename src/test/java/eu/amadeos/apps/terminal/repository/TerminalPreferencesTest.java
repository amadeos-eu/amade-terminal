package eu.amadeos.apps.terminal.repository;

import eu.amadeos.apps.terminal.domain.ColorScheme;
import eu.amadeos.apps.terminal.domain.CursorShape;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalPreferencesTest {
    @Test
    void defaultsWithoutFile(@TempDir Path dir) {
        assertEquals(TerminalSettings.DEFAULTS, new TerminalPreferences(dir.resolve("t.properties")).load());
    }

    @Test
    void savesAndLoads(@TempDir Path dir) {
        TerminalPreferences p = new TerminalPreferences(dir.resolve("t.properties"));
        TerminalSettings s = new TerminalSettings(ColorScheme.AMBER, "Liberation Mono", 15, CursorShape.BAR, false,
                100, 30, TerminalSettings.UNLIMITED, false, "tmux", true, false, true, false, false, false, false);
        p.save(s);
        assertEquals(s, p.load());
    }

    @Test
    void brokenValuesFallBack(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.properties");
        Files.writeString(f, "scheme=purple\nfont.size=huge\ncursor.shape=star\nwindow.rows=7\n");
        TerminalSettings s = new TerminalPreferences(f).load();
        assertEquals(ColorScheme.NIGHT, s.scheme());
        assertEquals(13, s.fontSize());
        assertEquals(CursorShape.BLOCK, s.cursor());
        assertEquals(7, s.rows());
    }
}
