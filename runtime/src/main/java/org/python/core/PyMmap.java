package org.python.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * Python's {@code mmap.mmap}: a window onto a file's octets.
 *
 * <p>The window is a {@link MappedByteBuffer} where the JVM can give one — {@code ACCESS_READ} maps
 * {@code READ_ONLY}, {@code ACCESS_WRITE} maps {@code READ_WRITE}, and {@code ACCESS_COPY} maps
 * {@code PRIVATE}, which is the copy-on-write page view CPython asks {@code mmap} for. Two cases have
 * no JVM mapping: a private map over a descriptor opened read-only (the JVM insists a {@code PRIVATE}
 * channel be writable) is copied onto the heap once and never written back, and an anonymous mapping is
 * heap storage. Both keep the observable semantics — reads see the file, writes are lost at close — and
 * are named in the PLAN as the point where the JVM stands in for the kernel.
 *
 * <p>{@code resize} is the one method that cannot be done: the JVM maps a fixed range and has no
 * {@code mremap}, which is also what the reference answers on macOS, where it raises
 * {@code SystemError: mmap: resizing not available--no mremap()}. That is therefore not a deviation.
 *
 * <p>Every operation re-checks {@link #closed} after its arguments are converted, because converting an
 * argument can run {@code __index__} and that code may have closed the map — which is what
 * {@code test_mmap_closed_by_int_scenarios} measures.
 */
class PyMmap extends PyObject {

    static final int ACCESS_DEFAULT = 0;
    static final int ACCESS_READ = 1;
    static final int ACCESS_WRITE = 2;
    static final int ACCESS_COPY = 3;

    static final int MAP_SHARED = 1;
    static final int MAP_PRIVATE = 2;
    static final int MAP_ANONYMOUS = 4096;
    static final int PROT_READ = 1;
    static final int PROT_WRITE = 2;

    /** {@code PAGESIZE} and {@code ALLOCATIONGRANULARITY}: the mapping unit macOS insists offsets sit on. */
    static final int PAGESIZE = 16384;

    private static final String[] PARAMETERS =
            {"fileno", "length", "flags", "prot", "access", "offset", "trackfd"};

    private FileChannel channel;
    private MappedByteBuffer mapped;
    private ByteBuffer heap;
    private boolean zeroed;
    private int mapLength;
    private int access;
    private int fd;
    private long offset;
    private int cursor;
    private boolean closed;
    private boolean trackfd = true;

    PyMmap(PyType kind) {
        super(kind);
    }

    /** {@code mmap.mmap(...)}, the exact type's constructor, with no class argument to unpack. */
    static PyObject called(PyType kind, PyObject[] args, String[] kwNames) {
        PyMmap made = new PyMmap(kind);
        made.initialize(args, kwNames);
        return made;
    }

    /** The same for a class written over {@code mmap.mmap}: the buffer is the same, plus a dictionary. */
    static PyObject subclass(PyType kind, PyObject[] args, String[] kwNames) {
        PyMmap made = new PyMmapSubclass(kind);
        made.initialize(args, kwNames);
        return made;
    }

    // --- construction ------------------------------------------------------------------

    /**
     * {@code mmap_new}'s argument handling and every refusal it makes, in the order it makes them.
     *
     * <p>{@code trackfd} is keyword-only, which is why seven values arrive through six positional
     * slots. {@code access} is checked against {@code flags} and {@code prot} by <em>value</em>: naming
     * the defaults explicitly is not a conflict, naming anything else beside {@code access} is.
     */
    private void initialize(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 6) {
            throw Slots.typeError(
                    "function takes at most 6 positional arguments (%d given)", positional);
        }
        PyObject[] values = new PyObject[PARAMETERS.length];
        for (int i = 0; i < positional; i++) {
            values[i] = args[i];
        }
        if (kwNames != null) {
            for (int k = 0; k < kwNames.length; k++) {
                int at = indexOf(PARAMETERS, kwNames[k]);
                if (at < 0) {
                    throw Slots.typeError(
                            "this function got an unexpected keyword argument '%s'", kwNames[k]);
                }
                if (values[at] != null) {
                    throw Slots.typeError(
                            "argument for function given by name ('%s') and position (%d)",
                            kwNames[k], at + 1);
                }
                values[at] = args[positional + k];
            }
        }
        for (int i = 0; i < 2; i++) {
            if (values[i] == null) {
                throw Slots.typeError(
                        "function missing required argument '%s' (pos %d)", PARAMETERS[i], i + 1);
            }
        }
        int descriptor = Numbers.cInteger(values[0]);
        long length = Numbers.size(values[1]);
        int flags = values[2] == null ? MAP_SHARED : Numbers.cInteger(values[2]);
        int prot = values[3] == null ? PROT_READ | PROT_WRITE : Numbers.cInteger(values[3]);
        int asked = values[4] == null ? ACCESS_DEFAULT : Numbers.cInteger(values[4]);
        long start = values[5] == null ? 0 : Numbers.size(values[5]);
        trackfd = values[6] == null || Slots.truth(values[6]);

        if (length < 0) {
            throw Slots.overflowError("memory mapped length must be positive");
        }
        if (start < 0) {
            throw Slots.overflowError("memory mapped offset must be positive");
        }
        if (asked < ACCESS_DEFAULT || asked > ACCESS_COPY) {
            throw Slots.valueError("mmap invalid access parameter.");
        }
        if (asked != ACCESS_DEFAULT
                && (flags != MAP_SHARED || prot != (PROT_READ | PROT_WRITE))) {
            throw Slots.valueError("mmap can't specify both access and flags, prot.");
        }
        // `prot` without a write bit is a read-only window whatever the flags said, and the reference
        // reports it as ACCESS_READ rather than as the default.
        access = asked == ACCESS_DEFAULT && (prot & PROT_WRITE) == 0 ? ACCESS_READ : asked;
        offset = start;

        if (descriptor == -1) {
            anonymous(length);
            return;
        }
        fromFile(descriptor, length, flags, prot);
    }

    /** {@code mmap(-1, length)}: storage with no file behind it. */
    private void anonymous(long length) {
        if (length <= 0) {
            throw osError(22, "Invalid argument");
        }
        int size = checkedLength(length);
        fd = -1;
        mapLength = size;
        if (access == ACCESS_READ) {
            // A read-only anonymous window reads as zeroes and nothing may write it, so no storage is
            // needed at all — which is what keeps the guard-page probes in test_mmap cheap.
            zeroed = true;
        } else {
            heap = ByteBuffer.allocate(size);
        }
    }

    /** {@code mmap(fd, length, ...)}: the window over a real file, with the reference's refusals. */
    private void fromFile(int descriptor, long length, int flags, int prot) {
        if (!Fds.isOpen(descriptor)) {
            throw osError(9, "Bad file descriptor");
        }
        if (!Fds.isFile(descriptor)) {
            throw osError(22, "Invalid argument");
        }
        long fileSize = Fds.size(descriptor);
        if (length == 0) {
            if (fileSize == 0) {
                throw Slots.valueError("cannot mmap an empty file");
            }
            if (offset >= fileSize) {
                throw Slots.valueError("mmap offset is greater than file size");
            }
            length = fileSize - offset;
        } else if (offset > fileSize || length > fileSize - offset) {
            throw Slots.valueError("mmap length is greater than file size");
        }
        if (offset % PAGESIZE != 0) {
            throw osError(22, "Invalid argument");
        }
        int size = checkedLength(length);
        boolean writable = Fds.writable(descriptor);
        boolean readWrite =
                access == ACCESS_WRITE || (access == ACCESS_DEFAULT && (prot & PROT_WRITE) != 0);
        if (readWrite && !writable) {
            throw PyRaise.of(
                    PyOSError.create(
                            ExceptionTypes.PERMISSION_ERROR,
                            new PyObject[] {PyInt.of(13), PyStr.of("Permission denied")}));
        }
        fd = descriptor;
        mapLength = size;
        try {
            RandomAccessFile file = Fds.file(descriptor);
            channel = file.getChannel();
            FileChannel.MapMode mode =
                    access == ACCESS_READ
                            ? FileChannel.MapMode.READ_ONLY
                            : readWrite
                                    ? FileChannel.MapMode.READ_WRITE
                                    : FileChannel.MapMode.PRIVATE;
            if (access == ACCESS_COPY && !writable) {
                // The JVM requires a writable channel even for a private map, which the kernel does not.
                // Copying once is the same private view: reads see the file, writes vanish at close.
                heap = ByteBuffer.allocate(size);
                readFully(offset, heap, size);
            } else {
                mapped = channel.map(mode, offset, size);
            }
        } catch (IOException failure) {
            throw osError(5, failure.getMessage() == null ? "Input/output error" : failure.getMessage());
        }
    }

    private static int checkedLength(long length) {
        if (length > Integer.MAX_VALUE) {
            // A JVM buffer is int-addressed; the reference can map more, and this is the named limit.
            throw Slots.overflowError("mmap length is too large");
        }
        return (int) length;
    }

    private void readFully(long from, ByteBuffer into, int count) throws IOException {
        long at = from;
        int left = count;
        while (left > 0) {
            int read = channel.read(into, at);
            if (read < 0) {
                break;
            }
            at += read;
            left -= read;
        }
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // --- state -------------------------------------------------------------------------

    private void open() {
        if (closed) {
            throw Slots.valueError("mmap closed or invalid");
        }
    }

    private void requireWritable() {
        if (access == ACCESS_READ) {
            throw Slots.typeError("mmap can't modify a readonly memory map.");
        }
    }

    private int octetAt(int index) {
        if (zeroed) {
            return 0;
        }
        return (mapped != null ? mapped.get(index) : heap.get(index)) & 0xff;
    }

    private void setOctet(int index, int value) {
        if (mapped != null) {
            mapped.put(index, (byte) value);
        } else if (heap != null) {
            heap.put(index, (byte) value);
        }
    }

    private void copyOut(int from, byte[] into, int at, int count) {
        if (zeroed) {
            Arrays.fill(into, at, at + count, (byte) 0);
            return;
        }
        ByteBuffer source = mapped != null ? mapped.duplicate() : heap.duplicate();
        source.position(from);
        source.get(into, at, count);
    }

    private void copyIn(int at, byte[] from, int off, int count) {
        if (mapped != null) {
            MappedByteBuffer target = mapped.duplicate();
            target.position(at);
            target.put(from, off, count);
        } else if (heap != null) {
            ByteBuffer target = heap.duplicate();
            target.position(at);
            target.put(from, off, count);
        }
    }

    /** Every octet of the window, for {@code memoryview(m)} and the readers built on it. */
    byte[] octets() {
        open();
        byte[] out = new byte[mapLength];
        copyOut(0, out, 0, mapLength);
        return out;
    }

    // --- the buffer a memoryview takes --------------------------------------------------

    /**
     * The length of the buffer {@code memoryview(m)} acquires; a closed map refuses it with the
     * sentence every other operation on a closed map uses.
     *
     * <p>Nothing is pinned by the export: the JVM gives no handle on a mapping to hold open, so a view
     * that outlives {@code close()} refuses its next read instead of keeping the map alive. CPython
     * counts the export and refuses the {@code close} with {@code BufferError} instead; the PLAN names
     * this as a deviation.
     */
    int viewLength() {
        open();
        return mapLength;
    }

    /** {@code ACCESS_READ} exports a read-only buffer; the other two modes let a view write through. */
    boolean viewReadOnly() {
        return access == ACCESS_READ;
    }

    /** One octet, for a view's reads. */
    int viewOctetAt(int index) {
        open();
        return octetAt(index);
    }

    /** One octet, for a view's writes. */
    void viewWriteOctet(int index, byte value) {
        open();
        copyIn(index, new byte[] {value}, 0, 1);
    }

    // --- methods -----------------------------------------------------------------------

    static PyObject read(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.noKeywords("mmap.read()", kwNames);
        Arguments.atMost("read", rest, null, 1);
        long wanted = -1;
        if (rest.length == 1 && rest[0] != PyNone.INSTANCE) {
            if (!Numbers.isIndex(rest[0])) {
                throw Slots.typeError(
                        "argument should be integer or None, not '%s'", rest[0].getType().name());
            }
            wanted = Numbers.size(rest[0]);
        }
        self.open();
        int remaining = self.mapLength - self.cursor;
        int count = wanted < 0 ? remaining : (int) Math.min(wanted, remaining);
        byte[] out = new byte[count];
        self.copyOut(self.cursor, out, 0, count);
        self.cursor += count;
        return PyBytes.wrap(out);
    }

    static PyObject readByte(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        Arguments.none("mmap.read_byte()", Arrays.copyOfRange(args, 1, args.length), kwNames);
        self.open();
        if (self.cursor >= self.mapLength) {
            throw Slots.valueError("read byte out of range");
        }
        return PyInt.of(self.octetAt(self.cursor++));
    }

    static PyObject readline(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        // 3.14's readline takes nothing at all: the size hint the older signature had is gone, and the
        // reference answers `mmap.readline() takes no arguments (1 given)` even for an int.
        Arguments.none("mmap.readline()", Arrays.copyOfRange(args, 1, args.length), kwNames);
        self.open();
        int limit = self.mapLength - self.cursor;
        int at = self.cursor;
        while (at - self.cursor < limit && self.octetAt(at) != '\n') {
            at++;
        }
        if (at - self.cursor < limit) {
            at++;
        }
        byte[] out = new byte[at - self.cursor];
        self.copyOut(self.cursor, out, 0, out.length);
        self.cursor = at;
        return PyBytes.wrap(out);
    }

    static PyObject write(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.exactlyOne("mmap.write()", rest, kwNames);
        byte[] data = bufferOf(rest[0]);
        self.open();
        self.requireWritable();
        if (data.length > self.mapLength - self.cursor) {
            throw Slots.valueError("data out of range");
        }
        self.copyIn(self.cursor, data, 0, data.length);
        self.cursor += data.length;
        return PyInt.of(data.length);
    }

    static PyObject writeByte(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.exactlyOne("mmap.write_byte()", rest, kwNames);
        BigInteger value = Numbers.asIndex(rest[0]);
        if (value.signum() < 0) {
            throw Slots.overflowError("unsigned byte integer is less than minimum");
        }
        if (value.compareTo(BigInteger.valueOf(255)) > 0) {
            throw Slots.overflowError("unsigned byte integer is greater than maximum");
        }
        self.open();
        self.requireWritable();
        if (self.cursor >= self.mapLength) {
            throw Slots.valueError("write byte out of range");
        }
        self.setOctet(self.cursor++, value.intValue());
        return PyNone.INSTANCE;
    }

    static PyObject seek(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.noKeywords("mmap.seek()", kwNames);
        Arguments.between("seek", rest, null, 1, 2);
        long position = Numbers.size(rest[0]);
        int whence = rest.length > 1 ? Numbers.cInteger(rest[1]) : 0;
        if (whence < 0 || whence > 2) {
            throw Slots.valueError("unknown seek type");
        }
        self.open();
        long base = whence == 1 ? self.cursor : whence == 2 ? self.mapLength : 0;
        long target = base + position;
        if (target < 0 || target > self.mapLength) {
            throw Slots.valueError("seek out of range");
        }
        self.cursor = (int) target;
        return PyInt.of(self.cursor);
    }

    static PyObject tell(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        Arguments.none("mmap.tell()", Arrays.copyOfRange(args, 1, args.length), kwNames);
        self.open();
        return PyInt.of(self.cursor);
    }

    static PyObject size(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        Arguments.none("mmap.size()", Arrays.copyOfRange(args, 1, args.length), kwNames);
        self.open();
        if (self.fd == -1 || !self.trackfd) {
            throw osError(9, "Bad file descriptor");
        }
        // The reference answers the file's size, not the mapped window's, and a sparse file can be
        // larger than an int, so the count stays long.
        return PyInt.of(Fds.size(self.fd));
    }

    static PyObject find(PyObject[] args, String[] kwNames) {
        return find(args, kwNames, false);
    }

    static PyObject rfind(PyObject[] args, String[] kwNames) {
        return find(args, kwNames, true);
    }

    private static PyObject find(PyObject[] args, String[] kwNames, boolean right) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        String name = right ? "rfind" : "find";
        Arguments.noKeywords("mmap." + name + "()", kwNames);
        Arguments.between(name, rest, null, 1, 3);
        byte[] needle = bufferOf(rest[0]);
        long askedStart = rest.length > 1 && rest[1] != PyNone.INSTANCE ? Numbers.size(rest[1]) : 0;
        Long askedEnd =
                rest.length > 2 && rest[2] != PyNone.INSTANCE ? Numbers.size(rest[2]) : null;
        self.open();
        long start = askedStart < 0 ? askedStart + self.mapLength : askedStart;
        if (start < 0) {
            start = 0;
        } else if (start > self.mapLength) {
            start = self.mapLength;
        }
        long end = askedEnd == null ? self.mapLength : askedEnd;
        if (end < 0) {
            end += self.mapLength;
            if (end < 0) {
                end = 0;
            }
        } else if (end > self.mapLength) {
            end = self.mapLength;
        }
        if (end < start) {
            return PyInt.of(-1);
        }
        if (needle.length == 0) {
            return PyInt.of((int) (right ? end : start));
        }
        int last = (int) end - needle.length;
        if (right) {
            for (int at = last; at >= start; at--) {
                if (self.matches(at, needle)) {
                    return PyInt.of(at);
                }
            }
        } else {
            for (int at = (int) start; at <= last; at++) {
                if (self.matches(at, needle)) {
                    return PyInt.of(at);
                }
            }
        }
        return PyInt.of(-1);
    }

    private boolean matches(int at, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (octetAt(at + i) != (needle[i] & 0xff)) {
                return false;
            }
        }
        return true;
    }

    static PyObject move(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.noKeywords("mmap.move()", kwNames);
        Arguments.exactly("move", rest, null, 3);
        long destination = Numbers.size(rest[0]);
        long source = Numbers.size(rest[1]);
        long count = Numbers.size(rest[2]);
        self.open();
        self.requireWritable();
        if (destination < 0
                || source < 0
                || count < 0
                || source > self.mapLength
                || destination > self.mapLength
                || count > self.mapLength - source
                || count > self.mapLength - destination) {
            throw Slots.valueError("source, destination, or count out of range");
        }
        byte[] held = new byte[(int) count];
        self.copyOut((int) source, held, 0, held.length);
        self.copyIn((int) destination, held, 0, held.length);
        return PyNone.INSTANCE;
    }

    static PyObject flush(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.noKeywords("mmap.flush()", kwNames);
        Arguments.atMost("flush", rest, null, 2);
        // Only `size` has a `None` default: the reference converts the offset unconditionally, so
        // `flush(None)` is the ordinary integer-conversion refusal.
        long from = rest.length > 0 ? Numbers.size(rest[0]) : 0;
        Long asked = rest.length > 1 && rest[1] != PyNone.INSTANCE ? Numbers.size(rest[1]) : null;
        self.open();
        // The default length is the whole mapping, not the remainder after `from`: the reference's
        // `flush(1)` is therefore out of range on any non-empty map, while `flush(0)` and `flush()`
        // are the whole extent.
        long length = asked == null ? self.mapLength : asked;
        if (from < 0 || length < 0 || from + length > self.mapLength) {
            throw Slots.valueError("flush values out of range");
        }
        if (self.access == ACCESS_READ || self.access == ACCESS_COPY) {
            // Nothing this map wrote may reach the file, so the reference answers before `msync`;
            // an unaligned offset is not an error on a map with nothing to sync.
            return PyNone.INSTANCE;
        }
        if (self.mapped != null) {
            // `msync` refuses an address that is not page-aligned, which is what the reference reports
            // as EINVAL rather than as a Python-level range complaint.
            if (from % PAGESIZE != 0) {
                throw osError(22, "Invalid argument");
            }
            self.mapped.force();
        }
        return PyNone.INSTANCE;
    }

    static PyObject resize(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.exactlyOne("mmap.resize()", rest, kwNames);
        long wanted = Numbers.size(rest[0]);
        self.open();
        if (wanted < 0) {
            throw Slots.valueError("new size out of range");
        }
        if (!self.trackfd) {
            // Checked before the read-only refusal: a map made with trackfd=False answers this even
            // when it is also read-only.
            throw Slots.valueError("mmap can't resize with trackfd=False.");
        }
        if (self.access == ACCESS_READ || self.access == ACCESS_COPY) {
            throw Slots.typeError(
                    "mmap can't resize a readonly or copy-on-write memory map.");
        }
        // `MappedByteBuffer` fixes its range at mapping time and the JVM has no `mremap`; macOS has none
        // either, so the reference answers the same way here.
        throw Slots.systemError("mmap: resizing not available--no mremap()");
    }

    static PyObject madvise(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        Arguments.noKeywords("mmap.madvise()", kwNames);
        Arguments.between("madvise", rest, null, 1, 3);
        int option = Numbers.cInteger(rest[0]);
        long start = rest.length > 1 ? Numbers.size(rest[1]) : 0;
        long length = rest.length > 2 ? Numbers.size(rest[2]) : 0;
        self.open();
        if (option < 0 || option > 8) {
            throw osError(22, "Invalid argument");
        }
        if (start < 0 || start >= self.mapLength) {
            throw Slots.valueError("madvise start out of bounds");
        }
        if (length < 0) {
            throw Slots.valueError("madvise length invalid");
        }
        if (length > Integer.MAX_VALUE) {
            throw Slots.overflowError("madvise length too large");
        }
        // Advisory only: there is no JVM call that asks the kernel for a read-ahead policy, and nothing
        // observable depends on one.
        return PyNone.INSTANCE;
    }

    static PyObject close(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        Arguments.none("mmap.close()", Arrays.copyOfRange(args, 1, args.length), kwNames);
        self.closed = true;
        self.mapped = null;
        self.heap = null;
        self.channel = null;
        return PyNone.INSTANCE;
    }

    static PyObject seekable(PyObject[] args, String[] kwNames) {
        PyMmap self = (PyMmap) args[0];
        Arguments.none("mmap.seekable()", Arrays.copyOfRange(args, 1, args.length), kwNames);
        // No validity check: the reference answers True even on a closed map.
        return PyBool.TRUE;
    }

    // --- the sequence protocol ---------------------------------------------------------

    @Override
    public PyObject length() {
        open();
        return PyInt.of(mapLength);
    }

    @Override
    public PyObject getItem(PyObject key) {
        // The validity check precedes everything, even the key's type: a closed map answers
        // `mmap closed or invalid` for `m[None]` in the reference.
        open();
        if (Slices.isSlice(key)) {
            // The bounds convert through `__index__`, which may close the map; the reference re-checks
            // validity after unpacking and before reading, but the step-zero refusal comes first.
            Slices.Range range = Slices.of((PySlice) key, mapLength);
            open();
            byte[] out = new byte[range.count()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) octetAt(range.at(i));
            }
            return PyBytes.wrap(out);
        }
        if (!Numbers.isIndex(key)) {
            throw Slots.typeError("mmap indices must be integers");
        }
        BigInteger index = Numbers.asIndex(key);
        // The range refusal precedes the validity one: `m[X(1000)]` on a map X closed is an IndexError,
        // while an in-range index that closed it is the closed error.
        int at = Numbers.wrapIndex(key, index, mapLength, "mmap index out of range");
        open();
        return PyInt.of(octetAt(at));
    }

    /**
     * {@code m[k] = v} and {@code m[i:j] = b}: the writable refusal is the outermost check, ahead of
     * the key's type and of the closed check, and the value's type is ahead of the closed check too.
     */
    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        requireWritable();
        if (Slices.isSlice(key)) {
            // The bytes-like refusal and the slice unpack both precede the validity check: a map that
            // was already closed still answers `mmap object doesn't support...`-style errors here, and
            // one closed by a bound's `__index__` answers the closed sentence only after the unpack.
            byte[] data = bufferOf(value);
            Slices.Range range = Slices.of((PySlice) key, mapLength);
            open();
            if (data.length != range.count()) {
                throw Slots.indexError("mmap slice assignment is wrong size");
            }
            for (int i = 0; i < data.length; i++) {
                setOctet(range.at(i), data[i] & 0xff);
            }
            return PyNone.INSTANCE;
        }
        if (!Numbers.isIndex(key)) {
            throw Slots.typeError("mmap indices must be integer");
        }
        if (!Numbers.isIndex(value)) {
            throw Slots.typeError("mmap item value must be an int");
        }
        BigInteger index = Numbers.asIndex(key);
        long octet = Numbers.size(value);
        // The value's range refusal precedes validity, and validity precedes the index range: the
        // reference answers the value error for `m[X(1000)] = 999`, the closed error for `= 5`.
        if (octet < 0 || octet > 255) {
            throw Slots.valueError("mmap item value must be in range(0, 256)");
        }
        open();
        int at = Numbers.wrapIndex(key, index, mapLength, "mmap index out of range");
        setOctet(at, (int) octet);
        return PyNone.INSTANCE;
    }

    /** {@code del m[k]}: the reference refuses every form, with a sentence per form. */
    @Override
    public PyObject delItem(PyObject key) {
        requireWritable();
        if (Slices.isSlice(key)) {
            throw Slots.typeError("mmap object doesn't support slice deletion");
        }
        if (!Numbers.isIndex(key)) {
            throw Slots.typeError("mmap indices must be integer");
        }
        throw Slots.typeError("mmap doesn't support item deletion");
    }

    @Override
    public PyObject iterator() {
        return new MmapCursor(this);
    }

    /** {@code iter(m)}: a plain sequence iterator over one-octet {@code bytes}, independent of {@code pos}. */
    private static final class MmapCursor extends PyIterator {

        private final PyMmap map;
        private int at;

        MmapCursor(PyMmap map) {
            this.map = map;
        }

        @Override
        public PyObject nextOrNull() {
            map.open();
            if (at >= map.mapLength) {
                return null;
            }
            return PyBytes.of(new byte[] {(byte) map.octetAt(at++)});
        }
    }

    /**
     * {@code mmap.__enter__()}: entering answers the same map, after the closed check.
     *
     * <p>Both halves are ordinary methods rather than slot wrappers: a wrapper descriptor would count
     * the call's arguments against the slot it fills, and {@code __enter__} is handed none while
     * {@code __exit__} is handed three.
     */
    static PyObject enter(PyObject[] args, String[] kwNames, String qualified) {
        PyMmap self = (PyMmap) args[0];
        Arguments.none(qualified + "()", Arrays.copyOfRange(args, 1, args.length), kwNames);
        self.open();
        return self;
    }

    /** {@code mmap.__exit__(*exc_info)}: the three exception values are ignored and the map is closed. */
    static PyObject exit(PyObject[] args, String[] kwNames, String qualified) {
        PyMmap self = (PyMmap) args[0];
        Arguments.noKeywords(qualified + "()", kwNames);
        self.closed = true;
        self.mapped = null;
        self.heap = null;
        self.channel = null;
        return PyNone.INSTANCE;
    }

    /** {@code m.closed}: whether {@code close} has run, which every operation also checks. */
    boolean isClosed() {
        return closed;
    }

    @Override
    public String repr() {
        if (closed) {
            return "<" + filedName() + " closed=True>";
        }
        return "<"
                + filedName()
                + " closed=False, access="
                + accessName()
                + ", length="
                + mapLength
                + ", pos="
                + cursor
                + ", offset="
                + offset
                + ">";
    }

    private String filedName() {
        return getType() == PyType.MMAP ? "mmap.mmap" : getType().name();
    }

    private String accessName() {
        switch (access) {
            case ACCESS_READ:
                return "ACCESS_READ";
            case ACCESS_WRITE:
                return "ACCESS_WRITE";
            case ACCESS_COPY:
                return "ACCESS_COPY";
            default:
                return "ACCESS_DEFAULT";
        }
    }

    // --- buffers -----------------------------------------------------------------------

    /**
     * The octets of a bytes-like argument.
     *
     * <p>The five native runs go through {@link PyMemoryView#bufferOctets}, which is also the reader
     * {@code re} uses. Anything else is asked for {@code __buffer__} — that is how
     * {@code test_setitem_slice_resize_reentrancy} hands its own object in — and only then refused in
     * the reference's words.
     */
    private static byte[] bufferOf(PyObject value) {
        if (value instanceof PyBytes
                || value instanceof PyByteArray
                || value instanceof PyArray
                || value instanceof PyMemoryView
                || value instanceof PyMmap) {
            return PyMemoryView.bufferOctets(value);
        }
        if (Specials.lookUp(value.getType(), "__buffer__") != null) {
            PyObject view = Specials.invoke(value, "__buffer__", PyInt.of(0));
            return PyMemoryView.bufferOctets(view);
        }
        throw Slots.typeError(
                "a bytes-like object is required, not '%s'", value.getType().filedName());
    }

    private static PyRaise osError(int number, String message) {
        return PyRaise.of(
                PyOSError.create(
                        ExceptionTypes.OS_ERROR,
                        new PyObject[] {PyInt.of(number), PyStr.of(message)}));
    }
}
