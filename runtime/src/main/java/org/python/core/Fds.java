package org.python.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * The small integer file descriptors this runtime hands out, and the pipes behind them.
 *
 * <p>Jython runs on the JVM, so there is no {@code pipe(2)} to call and no descriptor table shared with
 * a child process. What {@code subprocess} needs, however, is only the shape of one: {@code os.pipe()}
 * returning a read end and a write end, a child writing into the write end while the parent reads from
 * the read end, and {@code select} being able to say when the read end has something. This class is that
 * table: descriptors start at three, each one names an end of a {@link Pipe}, and the reads block until
 * a byte or an end-of-file arrives, which is what a descriptor read does.
 *
 * <p>The two behaviours that differ from a kernel table are recorded here rather than hidden. Closing a
 * descriptor whose pipe still holds unread bytes discards them, and a descriptor may be {@linkplain
 * #retain(int) retained} by a child emulation so that the parent closing its copy does not close the
 * pipe end underneath the child — the reference gets that for free because the child holds its own copy
 * of the file description after {@code fork}.
 */
final class Fds {

    /** Descriptors 0, 1 and 2 belong to the streams the runtime started with, so the table starts here. */
    private static final int FIRST = 3;

    /** One pipe: a byte queue and the state of its two ends. */
    private static final class Pipe {
        private byte[] buffer = new byte[8192];
        private int start;
        private int length;
        private boolean writerClosed;
        private boolean readerClosed;
    }

    /**
     * One descriptor's entry: which end of which pipe it is, or the null device.
     *
     * <p>{@link #references} is the count of descriptors naming this entry, and it is shared by retained
     * copies, so the pipe end really closes when the last of them does.
     */
    private static final class Slot {
        final Pipe pipe;
        final boolean writable;
        final boolean devnull;
        int references = 1;

        Slot(Pipe pipe, boolean writable, boolean devnull) {
            this.pipe = pipe;
            this.writable = writable;
            this.devnull = devnull;
        }
    }

    private static final ArrayList<Slot> SLOTS = new ArrayList<>();

    private Fds() {
    }

    // ---- descriptors -----------------------------------------------------------------------

    /** {@code pipe(2)}: the read end first, then the write end, both at the lowest free descriptors. */
    static synchronized int[] pipe() {
        Pipe pipe = new Pipe();
        int read = allocate(new Slot(pipe, false, false));
        int write = allocate(new Slot(pipe, true, false));
        return new int[] {read, write};
    }

    /** {@code open("/dev/null", O_RDWR)}: a descriptor every read answers end-of-file and every write eats. */
    static synchronized int devnull() {
        return allocate(new Slot(null, false, true));
    }

    /** {@code dup(2)}: another descriptor naming the same pipe end, which the parent may close alone. */
    static synchronized int dup(int fd) {
        Slot slot = slot(fd);
        slot.references++;
        return allocate(slot);
    }

    /**
     * The descriptor a child emulation keeps after the parent closes its copy.
     *
     * <p>Only the reference count differs from {@link #dup(int)} in intent; the mechanism is the same.
     */
    static int retain(int fd) {
        return dup(fd);
    }

    /** {@code close(2)}: drops this descriptor, closing the pipe end when the last one naming it goes. */
    static void close(int fd) {
        Slot slot;
        synchronized (Fds.class) {
            slot = slot(fd);
            SLOTS.set(fd, null);
            slot.references--;
            if (slot.references > 0) {
                return;
            }
        }
        if (slot.devnull) {
            return;
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            if (slot.writable) {
                pipe.writerClosed = true;
            } else {
                pipe.readerClosed = true;
                pipe.buffer = new byte[0];
                pipe.start = 0;
                pipe.length = 0;
            }
            pipe.notifyAll();
        }
    }

    /** {@code close(2)} for callers that cannot report an error, such as a pumping thread. */
    static void closeQuietly(int fd) {
        try {
            close(fd);
        } catch (PyRaise ignored) {
            // The descriptor was already closed, which is what this call was asking for anyway.
        }
    }

    // ---- reads and writes ------------------------------------------------------------------

    /**
     * {@code read(2)}: up to {@code length} bytes, empty at end-of-file.
     *
     * <p>A read on an open pipe with nothing buffered waits, because that is what a blocking descriptor
     * does. {@code subprocess} only reads after {@code select} said the end is ready, so the wait is
     * never the one the communication loop sits in.
     */
    static byte[] read(int fd, int length) {
        Slot slot = slot(fd);
        if (slot.devnull) {
            return new byte[0];
        }
        if (slot.writable) {
            throw badFd(fd);
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            while (pipe.length == 0 && !pipe.writerClosed && !pipe.readerClosed) {
                try {
                    pipe.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            int count = Math.min(Math.max(length, 0), pipe.length);
            byte[] bytes = new byte[count];
            for (int i = 0; i < count; i++) {
                bytes[i] = pipe.buffer[(pipe.start + i) % pipe.buffer.length];
            }
            pipe.start = (pipe.start + count) % pipe.buffer.length;
            pipe.length -= count;
            return bytes;
        }
    }

    /** {@code write(2)}: appends to the pipe, or eats the bytes when this is the null device. */
    static int write(int fd, byte[] bytes) {
        Slot slot = slot(fd);
        if (slot.devnull) {
            return bytes.length;
        }
        if (!slot.writable) {
            throw badFd(fd);
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            if (pipe.readerClosed) {
                throw PyRaise.of(PyOSError.create(
                        ExceptionTypes.BROKEN_PIPE_ERROR,
                        new PyObject[] {PyInt.of(32), PyStr.of("Broken pipe")}));
            }
            append(pipe, bytes);
            pipe.notifyAll();
        }
        return bytes.length;
    }

    /** {@link #write(int, byte[])} for a pumping thread, which has nobody to raise to. */
    static void writeQuietly(int fd, byte[] bytes) {
        try {
            write(fd, bytes);
        } catch (PyRaise ignored) {
            // The parent stopped reading; a real child would take SIGPIPE and die. Nothing to report.
        }
    }

    // ---- what select asks ------------------------------------------------------------------

    /** Whether a read would return a byte or end-of-file. */
    static boolean readable(int fd) {
        Slot slot = peek(fd);
        if (slot == null) {
            return true;
        }
        if (slot.devnull) {
            return true;
        }
        if (slot.writable) {
            return false;
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            return pipe.length > 0 || pipe.writerClosed || pipe.readerClosed;
        }
    }

    /** Whether a write would be accepted. */
    static boolean writable(int fd) {
        Slot slot = peek(fd);
        if (slot == null || slot.devnull) {
            return true;
        }
        if (!slot.writable) {
            return false;
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            return !pipe.readerClosed;
        }
    }

    /** How many bytes a read would return without waiting. */
    static int available(int fd) {
        Slot slot = peek(fd);
        if (slot == null || slot.devnull) {
            return 0;
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            return pipe.length;
        }
    }

    /** Whether this table handed the number out and it has not been closed since. */
    static boolean known(int fd) {
        return peek(fd) != null;
    }

    /** Whether the descriptor is the null device {@code os.devnull} opens. */
    static boolean isDevnull(int fd) {
        Slot slot = peek(fd);
        return slot != null && slot.devnull;
    }

    // ---- the adapter {@code io.open(fd)} needs ---------------------------------------------

    /** A stream over a readable descriptor, which is what {@code io.open(fd, 'rb')} wraps. */
    static InputStream inputStream(int fd) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = Fds.read(fd, 1);
                return one.length == 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] into, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                byte[] bytes = Fds.read(fd, length);
                if (bytes.length == 0) {
                    return -1;
                }
                System.arraycopy(bytes, 0, into, offset, bytes.length);
                return bytes.length;
            }

            @Override
            public int available() {
                return Fds.available(fd);
            }

            @Override
            public void close() {
                Fds.closeQuietly(fd);
            }
        };
    }

    /** A stream over a writable descriptor, which is what {@code io.open(fd, 'wb')} wraps. */
    static OutputStream outputStream(int fd) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                Fds.write(fd, new byte[] {(byte) b});
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) {
                    return;
                }
                Fds.write(fd, Arrays.copyOfRange(bytes, offset, offset + length));
            }

            @Override
            public void close() {
                Fds.closeQuietly(fd);
            }
        };
    }

    // ---- internals -------------------------------------------------------------------------

    private static synchronized int allocate(Slot slot) {
        int size = SLOTS.size();
        for (int fd = FIRST; fd < size; fd++) {
            if (SLOTS.get(fd) == null) {
                SLOTS.set(fd, slot);
                return fd;
            }
        }
        while (SLOTS.size() < FIRST) {
            SLOTS.add(null);
        }
        SLOTS.add(slot);
        return SLOTS.size() - 1;
    }

    private static synchronized Slot slot(int fd) {
        Slot slot = peek(fd);
        if (slot == null) {
            throw badFd(fd);
        }
        return slot;
    }

    private static synchronized Slot peek(int fd) {
        return fd >= FIRST && fd < SLOTS.size() ? SLOTS.get(fd) : null;
    }

    private static PyRaise badFd(int fd) {
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.OS_ERROR,
                new PyObject[] {PyInt.of(9), PyStr.of("Bad file descriptor")}));
    }

    private static void append(Pipe pipe, byte[] bytes) {
        if (pipe.length + bytes.length > pipe.buffer.length) {
            int wanted = Math.max(pipe.buffer.length * 2, pipe.length + bytes.length);
            byte[] grown = new byte[wanted];
            for (int i = 0; i < pipe.length; i++) {
                grown[i] = pipe.buffer[(pipe.start + i) % pipe.buffer.length];
            }
            pipe.buffer = grown;
            pipe.start = 0;
        }
        int tail = (pipe.start + pipe.length) % pipe.buffer.length;
        for (byte b : bytes) {
            pipe.buffer[tail] = b;
            tail = (tail + 1) % pipe.buffer.length;
        }
        pipe.length += bytes.length;
    }
}
