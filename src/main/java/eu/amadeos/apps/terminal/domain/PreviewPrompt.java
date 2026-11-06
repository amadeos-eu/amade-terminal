package eu.amadeos.apps.terminal.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The {@code user@host} of the prompt in the preferences preview: the real one, like Debian's bash shows it. */
public final class PreviewPrompt {
    static final String FALLBACK_USER = "user";
    static final String FALLBACK_HOST = "amadeos";
    private static final Path HOSTNAME = Path.of("/etc/hostname");

    private PreviewPrompt() {
    }

    /** The logged-in user and this computer's name. */
    public static String current() {
        return of(System.getProperty("user.name"), hostName(HOSTNAME));
    }

    /** {@code user@host}, with a neutral name for a part that is missing. */
    static String of(String user, String host) {
        String u = user == null || user.isBlank() ? FALLBACK_USER : user.strip();
        String h = host == null || host.isBlank() ? FALLBACK_HOST : host.strip();
        int dot = h.indexOf('.');
        if (dot > 0) h = h.substring(0, dot); // bash's \h: the name up to the first dot
        return u + "@" + h;
    }

    /** The first line of {@code /etc/hostname}, or the HOSTNAME variable; null when neither is there. */
    static String hostName(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String line = Files.readString(file).lines().map(String::strip).filter(s -> !s.isEmpty())
                        .findFirst().orElse(null);
                if (line != null) return line;
            }
        } catch (IOException | RuntimeException ignored) {
            // fall back to the environment
        }
        return System.getenv("HOSTNAME");
    }
}
