package eu.amadeos.apps.terminal.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Which program a new tab runs and with which environment. */
public final class ShellCommand {
    private ShellCommand() {
    }

    /**
     * The command: the one from the command line, else the one from the Preferences (through {@code sh -c}),
     * else the user's shell ({@code $SHELL}, then the passwd entry, then bash), as login shell if chosen.
     */
    public static List<String> command(LaunchOptions options, TerminalSettings settings, Function<String, String> env,
                                       Function<String, String> passwdShell) {
        if (!options.command().isEmpty()) return options.command();
        if (!settings.command().isBlank()) return List.of("/bin/sh", "-c", settings.command());
        String shell = env.apply("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
            shell = passwdShell.apply(env.apply("USER"));
        }
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) shell = "/bin/bash";
        List<String> cmd = new ArrayList<>();
        cmd.add(shell);
        if (settings.loginShell()) cmd.add("-l");
        return cmd;
    }

    /** The shell of a user from {@code /etc/passwd}, or null. */
    public static String passwdShell(String user) {
        if (user == null) return null;
        try {
            for (String line : Files.readAllLines(Path.of("/etc/passwd"))) {
                String[] f = line.split(":");
                if (f.length >= 7 && f[0].equals(user)) return f[6];
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return null;
    }

    /**
     * The variables a terminal sets: what kind of terminal it is ({@code TERM=xterm-256color}, true colour), and
     * none of the variables of the terminal amade-terminal itself may have been started from.
     */
    public static Map<String, String> environment(String version) {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("TERM_PROGRAM", "amade-terminal");
        env.put("TERM_PROGRAM_VERSION", version);
        for (String gone : List.of("LINES", "COLUMNS", "TERMCAP", "WINDOWID", "VTE_VERSION", "KONSOLE_VERSION",
                "GNOME_TERMINAL_SCREEN", "TMUX", "STY")) {
            env.put(gone, null);
        }
        return env;
    }

    /** Paths dropped onto the terminal, quoted for the shell and separated by spaces. */
    public static String quotePaths(List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(quote(p.toString()));
        }
        return sb.append(' ').toString();
    }

    /** A word the shell reads back unchanged: plain if safe, else in single quotes. */
    public static String quote(String s) {
        if (!s.isEmpty() && s.matches("[A-Za-z0-9_./+:@%=,-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
