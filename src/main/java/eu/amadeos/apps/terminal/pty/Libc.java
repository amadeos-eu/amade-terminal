package eu.amadeos.apps.terminal.pty;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The few C library calls a pseudo terminal needs, through Java's foreign function API (no native library of our
 * own, no JNA). Linux values for the flags and ioctl numbers; amade-terminal is a Linux program.
 */
final class Libc {
    static final int O_RDWR = 2;
    static final int O_NOCTTY = 0400;
    static final int O_CLOEXEC = 02000000;
    static final long TIOCSWINSZ = 0x5414;
    static final long TIOCGPGRP = 0x540F;
    static final int EINTR = 4;
    static final int EAGAIN = 11;
    static final int EIO = 5;
    static final int SIGHUP = 1;
    static final int SIGKILL = 9;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup C = LINKER.defaultLookup();
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option ERRNO_OPTION = Linker.Option.captureCallState("errno");

    private static final MethodHandle POSIX_OPENPT = handle("posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GRANTPT = handle("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle UNLOCKPT = handle("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle PTSNAME_R = handle("ptsname_r",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle IOCTL = LINKER.downcallHandle(C.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2));
    private static final MethodHandle READ = LINKER.downcallHandle(C.find("read").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), ERRNO_OPTION);
    private static final MethodHandle WRITE = LINKER.downcallHandle(C.find("write").orElseThrow(),
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), ERRNO_OPTION);
    private static final MethodHandle CLOSE = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle POLL = handle("poll",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle KILL = handle("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

    private Libc() {
    }

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(C.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
    }

    static int posixOpenpt(int flags) {
        try {
            return (int) POSIX_OPENPT.invokeExact(flags);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int grantpt(int fd) {
        try {
            return (int) GRANTPT.invokeExact(fd);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int unlockpt(int fd) {
        try {
            return (int) UNLOCKPT.invokeExact(fd);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static String ptsname(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(128);
            int r = (int) PTSNAME_R.invokeExact(fd, buf, 128L);
            return r == 0 ? buf.getString(0) : null;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int ioctl(int fd, long request, MemorySegment arg) {
        try {
            return (int) IOCTL.invokeExact(fd, request, arg);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** read(2); returns the count, or minus errno. */
    static long read(int fd, MemorySegment buf, long count, MemorySegment errno) {
        try {
            long n = (long) READ.invokeExact(errno, fd, buf, count);
            return n >= 0 ? n : -(int) ERRNO.get(errno, 0L);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** write(2); returns the count, or minus errno. */
    static long write(int fd, MemorySegment buf, long count, MemorySegment errno) {
        try {
            long n = (long) WRITE.invokeExact(errno, fd, buf, count);
            return n >= 0 ? n : -(int) ERRNO.get(errno, 0L);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int close(int fd) {
        try {
            return (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** poll(2) on one descriptor for input; returns &gt;0 when there is something to read (or a hang-up). */
    static int pollIn(int fd, MemorySegment pollfd, int timeoutMillis) {
        pollfd.set(JAVA_INT, 0, fd);
        pollfd.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 4, (short) 1);
        pollfd.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 6, (short) 0);
        try {
            return (int) POLL.invokeExact(pollfd, 1L, timeoutMillis);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int kill(int pid, int signal) {
        try {
            return (int) KILL.invokeExact(pid, signal);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Memory for the errno that {@link #read} and {@link #write} capture. */
    static MemorySegment errnoSegment(Arena arena) {
        return arena.allocate(CAPTURE);
    }
}
