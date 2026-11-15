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

    /**
     * The three descriptors every process starts with: standard input, output and error.
     *
     * <p>A JVM has no descriptor table of its own, but {@code sys.stdin} and the console behind it are
     * still numbers 0, 1 and 2 to a program. They are kept outside {@link #SLOTS} so the allocator keeps
     * handing out numbers from three, and they are never closed by {@link #close}: the console is the
     * process's, not this table's.
     */
    private static final Slot[] STANDARDS = {new Slot(0), new Slot(1), new Slot(2)};

    /**
     * How much a pipe holds: 64 KiB, the capacity of a kernel pipe on the reference platform.
     *
     * <p>The queue used to grow without limit, which made a non-blocking write over a pipe that nobody
     * was reading succeed forever. A bound is what makes "the pipe is full" a state the descriptor can
     * report, and it is the same state the reference reports with {@code EAGAIN}.
     */
    static final int PIPE_CAPACITY = 64 * 1024;

    /**
     * {@code PIPE_BUF}: the largest write a pipe accepts atomically.
     *
     * <p>A write of this size or less never lands partially: either all of it fits and is queued, or —
     * when the descriptor is non-blocking — none of it is, and the write reports that it would block.
     */
    static final int PIPE_BUF = 4096;

    /** One pipe: a byte queue of {@link #PIPE_CAPACITY} octets and the state of its two ends. */
    private static final class Pipe {
        private final byte[] buffer = new byte[PIPE_CAPACITY];
        private int start;
        private int length;
        private boolean writerClosed;
        private boolean readerClosed;
    }

    /**
     * What a descriptor-backed Java stream reports when {@link #read} came back with {@code null}.
     *
     * <p>{@link java.io.InputStream} has no third answer between "these bytes" and "end of file", so a
     * would-block read leaves through this carrier and the {@code _io} layer above turns it back into the
     * {@code None} the reference returns.
     */
    static final class WouldBlock extends IOException {
        WouldBlock() {
            super("Resource temporarily unavailable");
        }
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
        /** A real file behind this descriptor, or null for a pipe end and the null device. */
        final java.io.RandomAccessFile file;
        /** Where that file was opened, so {@code fstat} can answer with the file's own numbers. */
        final java.nio.file.Path path;
        /** Whether every write seeks to the end first, which is what {@code O_APPEND} asks for. */
        final boolean appending;
        /** 0, 1 or 2 for one of the inherited console descriptors, -1 for everything this table made. */
        final int standard;
        int references = 1;
        /** Whether a read with nothing buffered waits for a writer, as {@code set_blocking(fd, True)} says. */
        volatile boolean blocking = true;

        Slot(Pipe pipe, boolean writable, boolean devnull) {
            this(pipe, writable, devnull, null, null, false, -1);
        }

        Slot(java.io.RandomAccessFile file, java.nio.file.Path path,
                boolean writable, boolean appending) {
            this(null, writable, false, file, path, appending, -1);
        }

        /** A standard descriptor: number 0 reads, 1 and 2 write, and the JVM owns the console. */
        Slot(int standard) {
            this(null, standard != 0, false, null, null, false, standard);
        }

        private Slot(Pipe pipe, boolean writable, boolean devnull,
                java.io.RandomAccessFile file, java.nio.file.Path path, boolean appending,
                int standard) {
            this.pipe = pipe;
            this.writable = writable;
            this.devnull = devnull;
            this.file = file;
            this.path = path;
            this.appending = appending;
            this.standard = standard;
        }
    }

    private static final ArrayList<Slot> SLOTS = new ArrayList<>();

    /**
     * {@code FD_CLOEXEC}, per descriptor number rather than per file description.
     *
     * <p>The reference keeps this flag in the kernel's descriptor table, where {@code dup} clears it on
     * the copy alone. There is no {@code exec} here for it to matter to, but {@code os.get_inheritable}
     * and {@code os.set_inheritable} are asked about descriptors this table knows, so the answer is
     * remembered beside the number. A descriptor with no entry falls back to the rule the reference
     * follows: the three inherited ones are inheritable, everything opened here is not.
     */
    private static final java.util.Map<Integer, Boolean> INHERITABLE = new java.util.HashMap<>();

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

    /**
     * A descriptor for a handle someone else opened: {@code open()}'s stream asks for one the first time
     * {@code fileno()} is called, so that the number and the stream read and write the same position.
     */
    static synchronized int register(
            java.io.RandomAccessFile file, java.nio.file.Path path,
            boolean writable, boolean appending) {
        return allocate(new Slot(file, path, writable, appending));
    }

    /**
     * {@code open(2)} on a real file: a descriptor over a random-access handle.
     *
     * <p>The handle is what keeps {@code seek} and {@code tell} meaningful to whoever wraps this number,
     * because the position lives in the handle rather than in this table. O_APPEND is the one flag the
     * handle cannot take, so it is remembered on the slot and every write seeks to the end first.
     */
    static synchronized int open(java.nio.file.Path path, boolean read, boolean write,
            boolean append, boolean create, boolean exclusive, boolean truncate) throws IOException {
        if (exclusive && java.nio.file.Files.exists(path)) {
            throw new java.nio.file.FileAlreadyExistsException(path.toString());
        }
        if (create && !java.nio.file.Files.exists(path)) {
            java.nio.file.Files.createFile(path);
        }
        boolean writable = write || append;
        java.io.RandomAccessFile file =
                new java.io.RandomAccessFile(path.toFile(), writable ? "rw" : "r");
        if (truncate && writable) {
            file.setLength(0);
        }
        return allocate(new Slot(file, path, writable, append));
    }

    /** Whether the number names a real file rather than a pipe end or the null device. */
    static boolean isFile(int fd) {
        Slot slot = peek(fd);
        return slot != null && slot.file != null;
    }

    /** The handle a real-file descriptor names, which is what {@code io.open(fd, 'r+b')} wraps. */
    static java.io.RandomAccessFile file(int fd) {
        return slot(fd).file;
    }

    /** Where a real-file descriptor was opened. */
    static java.nio.file.Path path(int fd) {
        return slot(fd).path;
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

    /**
     * {@code closerange(2)}: close every descriptor in {@code [first, second)}, ignoring the ones that
     * were never open.
     *
     * <p>A range covers numbers this table never handed out, which the reference's own loop also walks
     * past: {@code EBADF} is what it ignores rather than what it reports.
     */
    static void closerange(int first, int second) {
        for (int fd = Math.max(first, 0); fd < second; fd++) {
            try {
                close(fd);
            } catch (PyRaise ignored) {
                // The number was not open, which is the state closerange is asking for anyway.
            }
        }
    }

    /** {@code get_inheritable(fd)}: whether an {@code exec} would pass this descriptor to a child. */
    static boolean inheritable(int fd) {
        Slot slot = slot(fd);
        synchronized (Fds.class) {
            Boolean flag = INHERITABLE.get(fd);
            return flag != null ? flag : slot.standard >= 0;
        }
    }

    /** {@code set_inheritable(fd, inheritable)}: the {@code FD_CLOEXEC} flag, remembered per number. */
    static void setInheritable(int fd, boolean value) {
        slot(fd);
        synchronized (Fds.class) {
            INHERITABLE.put(fd, value);
        }
    }

    /** {@code close(2)}: drops this descriptor, closing the pipe end when the last one naming it goes. */
    static void close(int fd) {
        if (fd >= 0 && fd < FIRST) {
            // Closing the inherited console is not something this runtime can do for the process, and
            // nothing it hands out points at one: the number stays open, as the JVM's own does.
            return;
        }
        Slot slot;
        synchronized (Fds.class) {
            slot = slot(fd);
            SLOTS.set(fd, null);
            INHERITABLE.remove(fd);
            slot.references--;
            if (slot.references > 0) {
                return;
            }
        }
        if (slot.devnull || slot.standard >= 0) {
            return;
        }
        if (slot.file != null) {
            try {
                slot.file.close();
            } catch (IOException ignored) {
                // Closing a descriptor that is already gone is what close(2) reports as EBADF, but the
                // table has already dropped the number, so nothing here can be retried.
            }
            return;
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            if (slot.writable) {
                pipe.writerClosed = true;
            } else {
                pipe.readerClosed = true;
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
     * {@code read(2)}: up to {@code length} bytes, empty at end-of-file, {@code null} would-block.
     *
     * <p>A read on an open blocking pipe with nothing buffered waits, because that is what such a
     * descriptor does. A non-blocking one answers {@code null} instead — the {@code None} the raw
     * {@code _io} layer reports — while an empty blocking pipe whose writer has closed is end-of-file
     * and stays an empty array.
     */
    static byte[] read(int fd, int length) {
        Slot slot = slot(fd);
        if (slot.standard >= 0) {
            if (slot.standard != 0) {
                throw badFd(fd);
            }
            byte[] bytes = new byte[Math.max(length, 0)];
            try {
                // The one call that may wait for a terminal or a pipe holding this process's input.
                int count = Gil.blockingIO(() -> System.in.read(bytes));
                if (count <= 0) {
                    return new byte[0];
                }
                return count == bytes.length ? bytes : Arrays.copyOf(bytes, count);
            } catch (IOException error) {
                throw ioFailure();
            }
        }
        if (slot.file != null) {
            byte[] bytes = new byte[Math.max(length, 0)];
            // The lock is given up before the monitor is taken and taken back after it is dropped, so
            // no thread ever waits for this monitor while holding the interpreter lock.
            int holds = Gil.suspend();
            try {
                synchronized (slot) {
                    int count;
                    try {
                        count = slot.file.read(bytes);
                    } catch (IOException error) {
                        throw ioFailure();
                    }
                    if (count <= 0) {
                        return new byte[0];
                    }
                    return count == bytes.length ? bytes : Arrays.copyOf(bytes, count);
                }
            } finally {
                Gil.resume(holds);
            }
        }
        if (slot.devnull) {
            return new byte[0];
        }
        if (slot.writable) {
            throw badFd(fd);
        }
        Pipe pipe = slot.pipe;
        // The pipe waits on a monitor that a writer must also take, so the interpreter lock is given
        // up around the monitor rather than inside it: the writer needs the lock to reach this pipe.
        int holds = Gil.suspend();
        try {
            synchronized (pipe) {
                while (slot.blocking && pipe.length == 0 && !pipe.writerClosed
                        && !pipe.readerClosed) {
                    try {
                        pipe.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (pipe.length == 0 && !pipe.writerClosed && !pipe.readerClosed) {
                    // Nothing to give and the writer is still there: only a non-blocking descriptor
                    // reaches this row without having waited, and its answer is would-block.
                    return null;
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
        } finally {
            Gil.resume(holds);
        }
    }

    /**
     * {@code write(2)}: appends what the pipe can hold, or eats the bytes on the null device.
     *
     * <p>A blocking pipe waits until every octet is queued, which is why the interpreter lock is given
     * up around the monitor exactly as {@link #read} does. A non-blocking one takes what fits and
     * answers how many those were; zero means the write would have blocked. A write of
     * {@link #PIPE_BUF} octets or fewer is all-or-nothing, so a pipe without room for the whole of it
     * reports zero instead of a partial count.
     */
    static int write(int fd, byte[] bytes) {
        Slot slot = slot(fd);
        if (slot.standard >= 0) {
            if (slot.standard == 0) {
                throw badFd(fd);
            }
            java.io.PrintStream console = slot.standard == 1 ? System.out : System.err;
            synchronized (console) {
                console.write(bytes, 0, bytes.length);
                console.flush();
            }
            return bytes.length;
        }
        if (slot.file != null) {
            if (!slot.writable) {
                throw badFd(fd);
            }
            int holds = Gil.suspend();
            try {
                synchronized (slot) {
                    try {
                        if (slot.appending) {
                            slot.file.seek(slot.file.length());
                        }
                        slot.file.write(bytes);
                        return bytes.length;
                    } catch (IOException error) {
                        throw ioFailure();
                    }
                }
            } finally {
                Gil.resume(holds);
            }
        }
        if (slot.devnull) {
            return bytes.length;
        }
        if (!slot.writable) {
            throw badFd(fd);
        }
        Pipe pipe = slot.pipe;
        int holds = Gil.suspend();
        try {
            synchronized (pipe) {
                if (pipe.readerClosed) {
                    throw brokenPipe();
                }
                if (!slot.blocking) {
                    int room = pipe.buffer.length - pipe.length;
                    if (bytes.length <= PIPE_BUF && room < bytes.length) {
                        return 0;
                    }
                    int count = Math.min(room, bytes.length);
                    append(pipe, bytes, 0, count);
                    if (count > 0) {
                        pipe.notifyAll();
                    }
                    return count;
                }
                int written = 0;
                while (written < bytes.length) {
                    if (pipe.readerClosed) {
                        if (written == 0) {
                            throw brokenPipe();
                        }
                        break;
                    }
                    int room = pipe.buffer.length - pipe.length;
                    if (room > 0) {
                        int count = Math.min(room, bytes.length - written);
                        append(pipe, bytes, written, count);
                        written += count;
                        pipe.notifyAll();
                    }
                    if (written < bytes.length) {
                        try {
                            pipe.wait();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                return written;
            }
        } finally {
            Gil.resume(holds);
        }
    }

    /** {@code EPIPE}, which a write to a pipe whose reader has gone reports. */
    private static PyRaise brokenPipe() {
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.BROKEN_PIPE_ERROR,
                new PyObject[] {PyInt.of(32), PyStr.of("Broken pipe")}));
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
        if (slot.file != null) {
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

    /**
     * Whether a write would be accepted.
     *
     * <p>A pipe whose reader has closed still answers true: {@code write(2)} would return at once with
     * {@code EPIPE}, and that is exactly what the kernel reports through {@code POLLERR} — a descriptor
     * that cannot make progress is "ready", not "busy". Saying false here deadlocks {@code
     * subprocess.Popen.communicate}, which waits for the write end to become ready and never gets to the
     * write that would raise the {@code BrokenPipeError} it hides. A full pipe, on the other hand, is
     * busy until its reader takes something, which is what makes a bounded queue visible to {@code
     * select}.
     */
    static boolean writable(int fd) {
        Slot slot = peek(fd);
        if (slot == null || slot.devnull) {
            return true;
        }
        if (slot.pipe == null) {
            return slot.writable;
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            return !slot.writable
                    || pipe.readerClosed
                    || pipe.length < pipe.buffer.length;
        }
    }

    /** How many bytes a read would return without waiting. */
    static int available(int fd) {
        Slot slot = peek(fd);
        if (slot == null || slot.devnull) {
            return 0;
        }
        if (slot.file != null) {
            return 0;
        }
        Pipe pipe = slot.pipe;
        synchronized (pipe) {
            return pipe.length;
        }
    }

    /** Whether this table handed the number out and it has not been closed since. */
    static boolean known(int fd) {
        return (fd >= 0 && fd < FIRST) || peek(fd) != null;
    }

    /** Whether the descriptor is the null device {@code os.devnull} opens. */
    static boolean isDevnull(int fd) {
        Slot slot = peek(fd);
        return slot != null && slot.devnull;
    }

    /**
     * {@code os.get_blocking(fd)}: the flag {@link #setBlocking} last wrote, true until it is changed.
     *
     * <p>Only the descriptors this table hands out have the flag. A socket's number belongs to the
     * socket layer, which has its own blocking mode; asking here about one is an unknown descriptor,
     * the same answer a closed fd gets.
     */
    static boolean blocking(int fd) {
        return slot(fd).blocking;
    }

    /** {@code os.set_blocking(fd, blocking)}: whether a read with nothing buffered waits. */
    static void setBlocking(int fd, boolean blocking) {
        slot(fd).blocking = blocking;
    }

    /** The bytes buffered in the pipe a descriptor names, which is what {@code fstat} reports. */
    static long size(int fd) {
        Slot slot = slot(fd);
        if (slot.file != null) {
            synchronized (slot) {
                try {
                    return slot.file.length();
                } catch (IOException error) {
                    throw ioFailure();
                }
            }
        }
        if (slot.pipe == null) {
            // The null device holds nothing, and `fstat` on it reports st_size 0.
            return 0;
        }
        synchronized (slot.pipe) {
            return slot.pipe.length;
        }
    }

    // ---- the adapter {@code io.open(fd)} needs ---------------------------------------------

    /** A stream over a readable descriptor, which is what {@code io.open(fd, 'rb')} wraps. */
    static InputStream inputStream(int fd) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = Fds.read(fd, 1);
                if (one == null) {
                    throw new WouldBlock();
                }
                return one.length == 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] into, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                byte[] bytes = Fds.read(fd, length);
                if (bytes == null) {
                    throw new WouldBlock();
                }
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

    /**
     * A stream over a writable descriptor, which is what {@code io.open(fd, 'wb')} wraps.
     *
     * <p>{@link #writeCount} is the row the {@code _io} layer below asks: it is the one answer that can
     * say "the pipe took this many and no more", which the {@link OutputStream} interface cannot carry.
     * The overrides that make it one are for callers outside that layer and refuse a short write rather
     * than pretending it finished.
     */
    static final class DescriptorOutput extends OutputStream {
        private final int fd;

        DescriptorOutput(int fd) {
            this.fd = fd;
        }

        int writeCount(byte[] bytes) {
            return Fds.write(fd, bytes);
        }

        @Override
        public void write(int b) throws IOException {
            if (writeCount(new byte[] {(byte) b}) == 0) {
                throw new WouldBlock();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            int written = writeCount(Arrays.copyOfRange(bytes, offset, offset + length));
            if (written < length) {
                throw new WouldBlock();
            }
        }

        @Override
        public void close() {
            Fds.closeQuietly(fd);
        }
    }

    /** A stream over a writable descriptor, which is what {@code io.open(fd, 'wb')} wraps. */
    static OutputStream outputStream(int fd) {
        return new DescriptorOutput(fd);
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
        if (slot == null && fd >= 0 && fd < FIRST) {
            slot = STANDARDS[fd];
        }
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

    /** The errno a failing read or write on a real file reports: EIO, the one Java leaves unspoken. */
    private static PyRaise ioFailure() {
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.OS_ERROR,
                new PyObject[] {PyInt.of(5), PyStr.of("Input/output error")}));
    }

    private static void append(Pipe pipe, byte[] bytes, int offset, int count) {
        int tail = (pipe.start + pipe.length) % pipe.buffer.length;
        for (int i = 0; i < count; i++) {
            pipe.buffer[tail] = bytes[offset + i];
            tail = (tail + 1) % pipe.buffer.length;
        }
        pipe.length += count;
    }
}
