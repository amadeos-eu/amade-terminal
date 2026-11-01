package eu.amadeos.apps.terminal.ui;

import eu.amadeos.apps.terminal.bootstrap.TerminalModule;
import eu.amadeos.apps.terminal.domain.ColorScheme;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.i18n.TranslationCheck;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationTest {
    private static Messages texts(Language language) {
        return Messages.load(TerminalModule.class, TerminalModule.MESSAGES, Locale.forLanguageTag(language.code()));
    }

    @Test
    void everyLanguageHasEveryText() {
        assertEquals(Map.of(), TranslationCheck.problems(TerminalModule.class, TerminalModule.MESSAGES));
    }

    /** No two entries share a mnemonic letter in the menu bar or in one menu. */
    @Test
    void mnemonicsAreUnique() {
        Map<String, List<String>> groups = Map.of(
                "bar", List.of("menu.file", "menu.edit", "menu.view", "menu.terminal", "menu.tabs", "menu.options",
                        "menu.help"),
                "file", List.of("menu.file.new-tab", "menu.file.new-window", "menu.file.close-tab",
                        "menu.file.close-window"),
                "edit", List.of("menu.edit.copy", "menu.edit.paste", "menu.edit.paste-selection",
                        "menu.edit.select-all", "menu.edit.find", "menu.edit.preferences"),
                "view", List.of("menu.view.zoom-in", "menu.view.zoom-out", "menu.view.zoom-normal",
                        "menu.view.full-screen"),
                "terminal", List.of("menu.terminal.clear", "menu.terminal.reset", "menu.terminal.open-folder"),
                "tabs", List.of("menu.tabs.previous", "menu.tabs.next", "menu.tabs.move-left",
                        "menu.tabs.move-right"),
                "help", List.of("menu.help.handbook", "menu.help.about"));
        for (Language language : Language.values()) {
            Messages texts = texts(language);
            for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                Map<Integer, String> used = new HashMap<>();
                for (String key : group.getValue()) {
                    Mnemonics.Text text = Mnemonics.parse(texts.get(key));
                    assertTrue(text.index() >= 0, language + " " + key + " has no mnemonic");
                    String before = used.put(text.keyCode(), key);
                    assertNull(before, language + " " + group.getKey() + ": " + key + " and " + before
                            + " share a letter");
                }
            }
        }
    }

    @Test
    void everySchemeHasAName() {
        for (Language language : Language.values()) {
            Messages texts = texts(language);
            for (ColorScheme s : ColorScheme.values()) {
                assertTrue(!texts.get("scheme." + s.id()).startsWith("!"), language + " " + s);
            }
        }
    }

    @Test
    void germanNames() {
        assertEquals("Grüner Bildschirm", texts(Language.GERMAN).get("scheme.green"));
    }
}
