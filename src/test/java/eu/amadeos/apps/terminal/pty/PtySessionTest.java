package eu.amadeos.apps.terminal.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs real programs on a real pseudo terminal. */
class PtySessionTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();

    private PtySession run(Path dir, int cols, int rows, String script) throws Exception {
        return PtySession.start(List.of("/bin/sh", "-c", script), dir, Map.of("TERM", "xterm-256color"), cols, rows,
                (b, n) -> {
                    synchronized (out) {
                        out.write(b, 0, n);
                    }
                }, exit::complete);
    }

    private String output() {
        synchronized (out) {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void programSeesATerminal() throws Exception {
        run(null, 80, 24, "if [ -t 0 ] && [ -t 1 ]; then echo tty-yes; fi; echo $TERM");
        assertEquals(0, exit.get(10, TimeUnit.SECONDS));
        assertTrue(output().contains("tty-yes"), output());
        assertTrue(output().contains("xterm-256color"));
    }

    @Test
    void lineEndingsComeAsCrLf() throws Exception {
        run(null, 80, 24, "printf 'a\\nb\\n'");
        exit.get(10, TimeUnit.SECONDS);
        assertEquals("a\r\nb\r\n", output());
    }

    @Test
    void windowSizeReachesProgram() throws Exception {
        run(null, 97, 31, "stty size");
        exit.get(10, TimeUnit.SECONDS);
        assertEquals("31 97", output().strip());
    }

    @Test
    void resizeIsSeenAfterwards() throws Exception {
        PtySession s = run(null, 80, 24, "read x; stty size");
        Thread.sleep(300);
        s.resize(120, 40, 0, 0);
        s.write("\r".getBytes());
        exit.get(10, TimeUnit.SECONDS);
        assertTrue(output().contains("40 120"), output());
    }

    @Test
    void inputReachesProgram() throws Exception {
        PtySession s = run(null, 80, 24, "read line; echo \"got:$line\"");
        s.write("hello\r".getBytes());
        exit.get(10, TimeUnit.SECONDS);
        assertTrue(output().contains("got:hello"), output());
    }

    @Test
    void ctrlCInterruptsForegroundProgram() throws Exception {
        PtySession s = run(null, 80, 24, "trap 'echo interrupted; exit 3' INT; while :; do sleep 0.1; done");
        Thread.sleep(500);
        s.write(new byte[]{3});
        assertEquals(3, exit.get(10, TimeUnit.SECONDS));
        assertTrue(output().contains("interrupted"), output());
    }

    @Test
    void exitCodeIsReported() throws Exception {
        PtySession s = run(null, 80, 24, "exit 7");
        assertEquals(7, exit.get(10, TimeUnit.SECONDS));
        assertTrue(s.awaitEnd(5000));
        assertFalse(s.isAlive());
        assertEquals(7, s.exitCode());
    }

    @Test
    void startsInWorkingDirectory(@TempDir Path dir) throws Exception {
        Path real = dir.toRealPath();
        run(real, 80, 24, "pwd");
        exit.get(10, TimeUnit.SECONDS);
        assertEquals(real.toString(), output().strip());
    }

    @Test
    void knowsCurrentDirectoryAndForegroundProgram(@TempDir Path dir) throws Exception {
        Path real = dir.toRealPath();
        PtySession s = PtySession.start(List.of("/bin/bash", "--norc", "-i"), real, Map.of("TERM", "dumb"), 80, 24,
                (b, n) -> {
                }, exit::complete);
        Thread.sleep(500);
        assertEquals(real, s.currentDirectory().orElseThrow());
        assertTrue(s.foregroundProgram().isEmpty());
        s.write("sleep 30\r".getBytes());
        Thread.sleep(700);
        assertEquals("sleep", s.foregroundProgram().orElse(""));
        s.hangUp();
        exit.get(10, TimeUnit.SECONDS);
        assertFalse(s.isAlive());
    }

    @Test
    void hangUpEndsShell() throws Exception {
        PtySession s = PtySession.start(List.of("/bin/bash", "--norc", "-i"), null, Map.of(), 80, 24, (b, n) -> {
        }, exit::complete);
        Thread.sleep(300);
        s.hangUp();
        exit.get(10, TimeUnit.SECONDS);
        assertTrue(s.awaitEnd(5000));
    }

    @Test
    void leftoverBackgroundProgramDoesNotKeepTabOpen() throws Exception {
        run(null, 80, 24, "sleep 30 & exit 0");
        assertEquals(0, exit.get(10, TimeUnit.SECONDS));
    }

    @Test
    void environmentCanRemoveVariables() throws Exception {
        PtySession.start(List.of("/bin/sh", "-c", "echo \"[${LINES-unset}]\""), null,
                java.util.Collections.singletonMap("LINES", null), 80, 24, (b, n) -> {
                    synchronized (out) {
                        out.write(b, 0, n);
                    }
                }, exit::complete);
        exit.get(10, TimeUnit.SECONDS);
        assertEquals("[unset]", output().strip());
    }

    @Test
    void missingWorkingDirectoryFallsBack() throws Exception {
        run(Path.of("/does/not/exist"), 80, 24, "echo ok");
        exit.get(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(Path.of("/")));
        assertEquals("ok", output().strip());
    }
}
