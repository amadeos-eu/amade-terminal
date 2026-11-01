package eu.amadeos.apps.terminal.pty;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The master side of a Unix pseudo terminal ({@code /dev/ptmx}). The program runs on the slave side
 * ({@code /dev/pts/N}) and sees a real terminal there: line editing, Ctrl+C as a signal, the window size.
 */
public final class UnixPty {
    private final int fd;
    private final String slave;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Freed by the garbage collector: the reader thread may still be inside read() when the tab closes. */
    private final Arena arena = Arena.ofAuto();
    private final MemorySegment readBuffer;
    private final MemorySegment readErrno;
    private final MemorySegment pollfd;

    private UnixPty(int fd, String slave) {
        this.fd = fd;
        this.slave = slave;
        readBuffer = arena.allocate(65536);
        readErrno = Libc.errnoSegment(arena);
        pollfd = arena.allocate(8);
    }

    // --8<-- [start:open]
    /** Opens a new pseudo terminal with a window size. */
    public static UnixPty open(int cols, int rows) throws IOException {
        int fd = Libc.posixOpenpt(Libc.O_RDWR | Libc.O_NOCTTY | Libc.O_CLOEXEC);
        if (fd < 0) throw new IOException("posix_openpt failed");
        if (Libc.grantpt(fd) != 0 || Libc.unlockpt(fd) != 0) {
            Libc.close(fd);
            throw new IOException("grantpt/unlockpt failed");
        }
        String name = Libc.ptsname(fd);
        if (name == null) {
            Libc.close(fd);
            throw new IOException("ptsname failed");
        }
        UnixPty pty = new UnixPty(fd, name);
        pty.resize(cols, rows, 0, 0);
        return pty;
    }
    // --8<-- [end:open]

    /** The slave device, e.g. {@code /dev/pts/3}. */
    public String slaveName() {
        return slave;
    }

    // --8<-- [start:read]
    /**
     * Reads what the program wrote; blocks until there is something. Returns -1 when the program side is
     * closed (all programs on the terminal have ended), or when nothing came for a moment and {@code giveUp}
     * says so (the shell ended, but a program it left in the background still holds the terminal).
     */
    public int read(byte[] buffer, java.util.function.BooleanSupplier giveUp) throws IOException {
        while (true) {
            if (closed.get()) return -1;
            int ready = Libc.pollIn(fd, pollfd, 250);
            if (ready == 0) {
                if (giveUp.getAsBoolean()) return -1;
                continue;
            }
            long n = Libc.read(fd, readBuffer, Math.min(buffer.length, readBuffer.byteSize()), readErrno);
            if (n > 0) {
                MemorySegment.copy(readBuffer, ValueLayout.JAVA_BYTE, 0, buffer, 0, (int) n);
                return (int) n;
            }
            if (n == 0 || n == -Libc.EIO) return -1;
            if (n != -Libc.EINTR && n != -Libc.EAGAIN) throw new IOException("read failed: errno " + -n);
        }
    }
    // --8<-- [end:read]

    /** Sends bytes to the program (keys, pasted text, answers). */
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (closed.get() || length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(length);
            MemorySegment.copy(bytes, offset, buf, ValueLayout.JAVA_BYTE, 0, length);
            MemorySegment errno = Libc.errnoSegment(a);
            long done = 0;
            while (done < length) {
                long n = Libc.write(fd, buf.asSlice(done), length - done, errno);
                if (n >= 0) done += n;
                else if (n != -Libc.EINTR && n != -Libc.EAGAIN) throw new IOException("write failed: errno " + -n);
            }
        }
    }

    // --8<-- [start:resize]
    /** The new window size; the kernel sends SIGWINCH to the program, which redraws. */
    public void resize(int cols, int rows, int widthPixels, int heightPixels) {
        if (closed.get()) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ws = a.allocate(8);
            ws.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
            ws.set(ValueLayout.JAVA_SHORT, 2, (short) cols);
            ws.set(ValueLayout.JAVA_SHORT, 4, (short) widthPixels);
            ws.set(ValueLayout.JAVA_SHORT, 6, (short) heightPixels);
            Libc.ioctl(fd, Libc.TIOCSWINSZ, ws);
        }
    }
    // --8<-- [end:resize]

    /** The process group in the foreground of the terminal (the program the user is running), or -1. */
    public int foregroundProcessGroup() {
        if (closed.get()) return -1;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pgrp = a.allocate(4);
            return Libc.ioctl(fd, Libc.TIOCGPGRP, pgrp) == 0 ? pgrp.get(ValueLayout.JAVA_INT, 0) : -1;
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Closes the master side; called by the reader once the program side is gone. */
    public void close() {
        if (closed.compareAndSet(false, true)) Libc.close(fd);
    }
}
