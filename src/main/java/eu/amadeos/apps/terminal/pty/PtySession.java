package eu.amadeos.apps.terminal.pty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * A program (usually the shell) running on its own pseudo terminal. It is started with
 * {@code setsid --ctty}: a session of its own with the terminal as controlling terminal, as a real login on a
 * console would have, so job control, Ctrl+C and Ctrl+Z work. No fork from Java and no native code of our own:
 * a small {@code sh} opens the terminal device for reading and writing and execs setsid.
 */
public final class PtySession {
    /** What the reader thread calls with the program's output. */
    public interface Output {
        void received(byte[] bytes, int length);
    }

    private static final Logger LOG = LoggerFactory.getLogger(PtySession.class);
    /** Opens the terminal device as stdin, stdout and stderr, then gives the program a session of its own. */
    static final String START = "exec setsid --ctty --wait \"$@\" <>\"$AMADE_TERMINAL_TTY\" >&0 2>&0";
    private static final String TTY_VARIABLE = "AMADE_TERMINAL_TTY";

    private final UnixPty pty;
    private final Process process;
    private final Thread reader;
    private volatile int exitCode = -1;

    private PtySession(UnixPty pty, Process process, Output output, IntConsumer exited) {
        this.pty = pty;
        this.process = process;
        reader = Thread.ofPlatform().daemon().name("pty-reader-" + process.pid()).start(() -> read(output, exited));
    }

    /**
     * Starts {@code command} in {@code directory} on a new terminal of {@code cols} x {@code rows}.
     * {@code environment} replaces the inherited variables of the same name; a null value removes one.
     */
    public static PtySession start(List<String> command, Path directory, Map<String, String> environment, int cols,
                                   int rows, Output output, IntConsumer exited) throws IOException {
        UnixPty pty = UnixPty.open(cols, rows);
        List<String> argv = new ArrayList<>(List.of("/bin/sh", "-c", START, "sh"));
        argv.addAll(command);
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (directory != null && Files.isDirectory(directory)) pb.directory(directory.toFile());
        Map<String, String> env = pb.environment();
        environment.forEach((k, v) -> {
            if (v == null) env.remove(k);
            else env.put(k, v);
        });
        env.put(TTY_VARIABLE, pty.slaveName());
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            pty.close();
            throw e;
        }
        return new PtySession(pty, process, output, exited);
    }

    private void read(Output output, IntConsumer exited) {
        byte[] buffer = new byte[65536];
        try {
            int n;
            while ((n = pty.read(buffer, this::shellGone)) >= 0) output.received(buffer, n);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Reading the terminal failed", e);
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
            exitCode = process.isAlive() ? -1 : process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pty.close();
        exited.accept(exitCode);
    }

    private long goneSince;

    /** True once the shell has been gone for a second: then the terminal closes even if a leftover holds it. */
    private boolean shellGone() {
        if (process.isAlive()) return false;
        long now = System.nanoTime();
        if (goneSince == 0) goneSince = now;
        return now - goneSince > 1_000_000_000L;
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return !pty.isClosed() && process.isAlive();
    }

    /** The exit code once the program has ended, else -1. */
    public int exitCode() {
        return exitCode;
    }

    public void write(byte[] bytes) throws IOException {
        pty.write(bytes, 0, bytes.length);
    }

    public void resize(int cols, int rows, int widthPixels, int heightPixels) {
        pty.resize(cols, rows, widthPixels, heightPixels);
    }

    /**
     * The name of the program running in the foreground ({@code htop}, {@code vim}) when it is not the shell
     * itself: closing the tab would end it.
     */
    public Optional<String> foregroundProgram() {
        int pgrp = pty.foregroundProcessGroup();
        if (pgrp <= 0 || pgrp == process.pid()) return Optional.empty();
        try {
            return Optional.of(Files.readString(Path.of("/proc", Integer.toString(pgrp), "comm")).strip());
        } catch (IOException e) {
            return Optional.of("?");
        }
    }

    /** The folder the shell is in now. */
    public Optional<Path> currentDirectory() {
        try {
            return Optional.of(Files.readSymbolicLink(Path.of("/proc", Long.toString(process.pid()), "cwd")));
        } catch (IOException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    /**
     * Ends the session as closing a terminal does: SIGHUP to the foreground program and to the shell, and a
     * SIGKILL for whatever is still there a few seconds later.
     */
    public void hangUp() {
        if (!process.isAlive()) {
            pty.close();
            return;
        }
        int pgrp = pty.foregroundProcessGroup();
        if (pgrp > 0) Libc.kill(-pgrp, Libc.SIGHUP);
        int pid = (int) process.pid();
        Libc.kill(-pid, Libc.SIGHUP);
        Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    Libc.kill(-pid, Libc.SIGKILL);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Waits until the reader has seen the end (for tests). */
    public boolean awaitEnd(long millis) throws InterruptedException {
        reader.join(millis);
        return !reader.isAlive();
    }
}
