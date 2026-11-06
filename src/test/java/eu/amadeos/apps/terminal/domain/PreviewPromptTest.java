package eu.amadeos.apps.terminal.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewPromptTest {
    @Test
    void userAndShortHostName() {
        assertEquals("anna@laptop", PreviewPrompt.of("anna", "laptop.example.org"));
        assertEquals("anna@amadeos", PreviewPrompt.of(" anna ", "amadeos\n"));
    }

    @Test
    void neutralNamesWhenMissing() {
        assertEquals("user@amadeos", PreviewPrompt.of(null, ""));
        assertEquals("user@amadeos", PreviewPrompt.of(" ", null));
    }

    @Test
    void hostNameFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hostname");
        Files.writeString(file, "\n  desk \nignored\n");
        assertEquals("desk", PreviewPrompt.hostName(file));
    }

    @Test
    void currentIsUserAtHost() {
        String prompt = PreviewPrompt.current();
        assertTrue(prompt.matches("[^@\\s]+@[^@.\\s]+"), prompt);
        assertTrue(prompt.startsWith(System.getProperty("user.name") + "@"), prompt);
    }
}
