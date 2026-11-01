package eu.amadeos.apps.terminal.ui;

import eu.amadeos.apps.terminal.OnEdt;
import eu.amadeos.apps.terminal.bootstrap.TerminalModule;
import eu.amadeos.apps.terminal.domain.LaunchOptions;
import eu.amadeos.apps.terminal.domain.TerminalSettings;
import eu.amadeos.apps.terminal.repository.TerminalPreferences;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.LocaleStore;
import eu.amadeos.commons.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The real window with a real shell on a pseudo terminal. Needs a display. */
class MainWindowTest {
    @TempDir
    Path dir;

    private TerminalApp app;
    private MainWindow window;
    private final AtomicBoolean exited = new AtomicBoolean();
    private final List<String> opened = new ArrayList<>();

    private void open() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Path prefs = dir.resolve("terminal.properties");
        Files.writeString(prefs, """
                command=PS1='$ ' exec /bin/sh -i
                close.confirm=false
                paste.warning=false
                bell.notify=false
                bell.sound=false
                """);
        Messages msg = Messages.load(TerminalModule.class, TerminalModule.MESSAGES, Locale.ENGLISH);
        LocaleSettings locales = new LocaleSettings(new LocaleStore(dir), dir.resolve("language.conf"), Map.of());
        DesktopLauncher launcher = new DesktopLauncher() {
            @Override
            public void browse(String uri) {
                opened.add(uri);
            }

            @Override
            public void open(Path file) {
                opened.add(file.toString());
            }
        };
        OnEdt.run(() -> {
            app = new TerminalApp(msg, locales, new TerminalPreferences(prefs), launcher, "test",
                    () -> exited.set(true));
            window = app.openWindow(LaunchOptions.SHELL);
        });
    }

    @AfterEach
    void close() throws Exception {
        if (app != null) OnEdt.run(() -> app.windows().forEach(w -> w.closeWindow()));
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            AtomicBoolean ok = new AtomicBoolean();
            OnEdt.run(() -> ok.set(condition.getAsBoolean()));
            if (ok.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private TerminalTab tab() {
        return window.current().orElseThrow();
    }

    private static int rowWith(TerminalTab tab, String text) {
        for (int r = 0; r < tab.emulator().rows(); r++) {
            if (tab.view().rowText(r).equals(text)) return r;
        }
        return -1;
    }

    private void type(String s) throws Exception {
        OnEdt.run(() -> tab().send(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static JMenuItem item(MenuElement root, String name) {
        for (MenuElement e : root.getSubElements()) {
            if (e instanceof JMenuItem m && name.equals(m.getName())) return m;
            JMenuItem found = item(e, name);
            if (found != null) return found;
        }
        return null;
    }

    private void click(String name) throws Exception {
        OnEdt.run(() -> {
            JMenuBar bar = window.getJMenuBar();
            JMenuItem m = item(bar, name);
            assertNotNull(m, name);
            m.doClick(0);
        });
    }

    @Test
    void runsTheShellAndShowsItsOutput() throws Exception {
        open();
        type("echo hello-$((40+2))\r");
        await("output", () -> rowWith(tab(), "hello-42") >= 0);
        OnEdt.run(() -> assertEquals(1, window.terminalTabs().size()));
    }

    @Test
    void newTabAndCloseTab() throws Exception {
        open();
        click("menu.file.new-tab");
        OnEdt.run(() -> assertEquals(2, window.terminalTabs().size()));
        TerminalTab second = tab();
        click("menu.tabs.previous");
        OnEdt.run(() -> assertTrue(tab() != second));
        click("menu.tabs.next");
        OnEdt.run(() -> assertEquals(second, tab()));
        click("menu.file.close-tab");
        OnEdt.run(() -> {
            assertEquals(1, window.terminalTabs().size());
            assertTrue(second.hasEnded());
        });
        assertFalse(exited.get());
    }

    @Test
    void copyPutsTheSelectionOnTheClipboard() throws Exception {
        open();
        type("echo copy-me-please\r");
        await("output", () -> rowWith(tab(), "copy-me-please") >= 0);
        OnEdt.run(() -> {
            int row = rowWith(tab(), "copy-me-please");
            tab().view().select(row, 0, row, 7);
            assertTrue(tab().view().hasSelection());
            assertEquals("copy-me", tab().view().selectedText());
        });
        click("menu.edit.copy");
        AtomicReference<Object> text = new AtomicReference<>();
        OnEdt.run(() -> {
            try {
                text.set(Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertEquals("copy-me", text.get());
    }

    @Test
    void pasteSendsTheClipboardToTheShell() throws Exception {
        open();
        OnEdt.run(() -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection("echo pasted-$((6*7))"), null));
        click("menu.edit.paste");
        type("\r");
        await("pasted command", () -> rowWith(tab(), "pasted-42") >= 0);
    }

    @Test
    void selectAllSelectsTheScreen() throws Exception {
        open();
        type("echo all-of-it\r");
        await("output", () -> rowWith(tab(), "all-of-it") >= 0);
        click("menu.edit.select-all");
        OnEdt.run(() -> assertTrue(tab().view().selectedText().contains("all-of-it")));
    }

    @Test
    void zoomChangesTheFontAndNormalGoesBack() throws Exception {
        open();
        int before = app.settings().fontSize();
        click("menu.view.zoom-in");
        assertEquals(before + 1, app.settings().fontSize());
        click("menu.view.zoom-out");
        click("menu.view.zoom-out");
        assertEquals(before - 1, app.settings().fontSize());
        click("menu.view.zoom-normal");
        assertEquals(TerminalSettings.DEFAULTS.fontSize(), app.settings().fontSize());
        assertTrue(Files.readString(dir.resolve("terminal.properties")).contains("font.size="));
    }

    @Test
    void findBarFindsText() throws Exception {
        open();
        type("echo needle-one; echo needle-two\r");
        await("output", () -> rowWith(tab(), "needle-two") >= 0);
        click("menu.edit.find");
        OnEdt.run(() -> {
            assertTrue(tab().isFindVisible());
            tab().findBar().setQuery("needle-");
        });
        await("hits", () -> tab().findBar().current() >= 0);
        OnEdt.run(() -> tab().hideFind());
        OnEdt.run(() -> assertFalse(tab().isFindVisible()));
    }

    @Test
    void clearScrollbackEmptiesTheHistory() throws Exception {
        open();
        type("i=0; while [ $i -lt 100 ]; do echo line $i; i=$((i+1)); done\r");
        await("history", () -> tab().emulator().historySize() > 0);
        click("menu.terminal.clear");
        OnEdt.run(() -> assertEquals(0, tab().emulator().historySize()));
    }

    @Test
    void titleFollowsTheProgram() throws Exception {
        open();
        type("printf '\\033]0;my-title\\007'\r");
        await("title", () -> window.getTitle().contains("my-title"));
    }

    @Test
    void exitingTheShellClosesTheWindowAndTheProgram() throws Exception {
        open();
        type("exit\r");
        await("exit", exited::get);
        OnEdt.run(() -> assertTrue(app.windows().isEmpty()));
    }

    @Test
    void handbookOpensInTheBrowser() throws Exception {
        open();
        click("menu.help.handbook");
        assertEquals(List.of(TerminalApp.HANDBOOK), opened);
    }

    @Test
    void openFolderOpensTheShellFolder() throws Exception {
        open();
        type("cd /tmp\r");
        await("cd", () -> tab().currentDirectory().map(Path::toString).orElse("").equals("/tmp"));
        click("menu.terminal.open-folder");
        assertEquals(List.of("/tmp"), opened);
    }

    @Test
    void secondWindow() throws Exception {
        open();
        click("menu.file.new-window");
        OnEdt.run(() -> assertEquals(2, app.windows().size()));
        OnEdt.run(() -> app.windows().get(1).closeWindow());
        OnEdt.run(() -> assertEquals(1, app.windows().size()));
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(exited.get());
    }
}
