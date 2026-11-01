package eu.amadeos.apps.terminal.domain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The command line, compatible with what desktops and Debian's {@code x-terminal-emulator} expect from a
 * terminal: {@code -e command args...} (also {@code -x} and {@code --}), {@code -T}/{@code --title},
 * {@code --working-directory DIR} (also {@code -w}, {@code --workdir}), {@code --hold}.
 *
 * @param command   the program and its arguments; empty for the shell
 * @param title     a fixed title for the tab, or null
 * @param directory the folder to start in, or null
 * @param hold      keep the tab open after the program ended
 */
public record LaunchOptions(List<String> command, String title, Path directory, boolean hold, boolean version) {
    public static final LaunchOptions SHELL = new LaunchOptions(List.of(), null, null, false, false);

    public LaunchOptions {
        command = List.copyOf(command);
    }

    public Optional<String> fixedTitle() {
        return Optional.ofNullable(title);
    }

    public static LaunchOptions parse(String[] args) {
        List<String> command = new ArrayList<>();
        String title = null;
        Path dir = null;
        boolean hold = false;
        boolean version = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-e", "-x", "--execute", "--" -> {
                    for (int k = i + 1; k < args.length; k++) command.add(args[k]);
                    i = args.length;
                }
                case "-T", "--title", "-title" -> {
                    if (i + 1 < args.length) title = args[++i];
                }
                case "-w", "--workdir", "--working-directory" -> {
                    if (i + 1 < args.length) dir = Path.of(args[++i]);
                }
                case "--hold", "-hold", "-H" -> hold = true;
                case "--version", "-v" -> version = true;
                default -> {
                    if (a.startsWith("--title=")) title = a.substring(8);
                    else if (a.startsWith("--working-directory=")) dir = Path.of(a.substring(20));
                    else if (a.startsWith("--workdir=")) dir = Path.of(a.substring(10));
                    else if (a.startsWith("--command=")) command.addAll(List.of("/bin/sh", "-c", a.substring(10)));
                }
            }
        }
        // "-e 'ls -l'" as one argument, as some programs call terminals: run it through the shell
        if (command.size() == 1 && command.get(0).contains(" ") && !new java.io.File(command.get(0)).exists()) {
            command = List.of("/bin/sh", "-c", command.get(0));
        }
        return new LaunchOptions(command, title, dir, hold, version);
    }
}
