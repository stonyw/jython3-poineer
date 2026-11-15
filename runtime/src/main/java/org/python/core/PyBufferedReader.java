package org.python.core;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Set;

/**
 * {@code sys.stdin.buffer}: the octet store the text stream reads through, and the layer that decides
 * how often the descriptor is touched.
 *
 * <p>The store is one array of {@code io.DEFAULT_BUFFER_SIZE} octets and the whole of this class is the
 * arithmetic on its two cursors, which the reference keeps in a peculiar pair of states: {@code pos} is
 * where the next octet comes from and {@code read_end} is where the store stops, and a row that has given
 * up on the store <em>resets</em> it rather than emptying it — {@code read_end = -1}, which is a third
 * answer to {@code how much do you hold} besides zero and a count. The {@code read} of a closed descriptor
 * with an emptied store and of a closed one still holding its octets are refused by different sentences
 * for that reason: {@code CHECK_CLOSED} only complains when the store has nothing left to answer with.
 *
 * <p>Three rows read the descriptor, and they read it differently. {@code read1} hands out what the store
 * holds and, when it holds nothing, one read of the descriptor <em>into its own answer</em> — which is why
 * the octets a {@code read1} fetches past its own count are not there for the next caller to find.
 * {@code peek} fills once and then shows the whole store however small a count was asked for, since the
 * count is only a hint and shifting the store would cost a copy. {@code read} loops the fill until it has
 * what was asked for or the descriptor is dry, and only asks the descriptor for whole blocks when the
 * request is bigger than the store — so {@code read(3)} over a pipe holding thirty-seven octets leaves
 * thirty-four behind for the {@code peek(1)} that follows it and gives the descriptor back nothing.
 *
 * <p>Nothing here owns a closed flag: {@code closed} is asked of the descriptor through Python, which is
 * what makes {@code raw.close()} close all three objects and every guard above this one read the same
 * answer. The one state this layer keeps of its own is whether it still holds the descriptor, and that
 * answer comes first in every row: after {@code detach()} the rows refuse {@code raw stream has been
 * detached} — except {@code writable()}, which is the base class's and refuses by the direction of the
 * layer, {@code write}, which is the same row, and {@code repr}, which drops the name field it can no
 * longer read.
 */
public final class PyBufferedReader extends PyIOStream {

    /** {@code io.DEFAULT_BUFFER_SIZE}, which is what one fill of the store asks the descriptor for. */
    static final int DEFAULT_BUFFER_SIZE = 131072;

    /** The five sentences {@code CHECK_CLOSED} says from inside the five rows that carry it. */
    private static final String READ = "read of closed file";

    private static final String PEEK = "peek of closed file";
    private static final String READLINE = "readline of closed file";
    private static final String SEEK = "seek of closed file";
    private static final String TRUNCATE = "truncate of closed file";

    private PyObject descriptor;

    /** {@code self->buffer_size}, which every blockful below is measured in. */
    private int bufferSize;

    /**
     * {@code self->buffer}: the one array both directions share.
     *
     * <p>{@code self->pos} is the cursor, {@code read_end} the far edge of what a read has put here and
     * {@code write_pos}..{@code write_end} the bytes a write has accepted but not yet handed over. The
     * reference keeps one array precisely so a write overwrites the read-ahead in place and a later
     * {@code seek} back into the store reads the new octets out of it.
     */
    private byte[] store;

    private int pos;
    private int readEnd = -1;
    private int writePos;
    private int writeEnd = -1;

    /**
     * {@code self->raw_pos}: the descriptor position that corresponds to {@code pos}, or -1 when it is
     * not known. {@code RAW_OFFSET} is {@code raw_pos - pos}, and that is what turns the descriptor's
     * pointer into the logical stream position.
     */
    private long rawPos;

    /** {@code self->abs_pos}: the cached answer of the descriptor's {@code tell}, -1 when not known. */
    private long absPos = -1;

    /** Whether {@code detach()} has taken the descriptor away, which is the first question of every row. */
    private boolean detached;

    /** {@code self->initialized}: a failed {@code __init__} leaves the layer unable to answer at all. */
    private boolean initialized = true;

    /** The two directions the mode string chose, which decide what the rows below refuse. */
    private boolean readable;
    private boolean writable;

    PyBufferedReader(PyFileIO raw) {
        this(raw, PyType.BUFFERED_READER, true, false, DEFAULT_BUFFER_SIZE);
    }

    PyBufferedReader(PyObject raw, PyType type, boolean readable, boolean writable) {
        this(raw, type, readable, writable, DEFAULT_BUFFER_SIZE);
    }

    PyBufferedReader(
            PyObject raw, PyType type, boolean readable, boolean writable, int bufferSize) {
        super(type);
        this.descriptor = raw;
        this.readable = readable;
        this.writable = writable;
        initializeStore(bufferSize);
    }

    /**
     * The cleared value {@code object.__new__(_io.BufferedReader)} answers with.
     *
     * <p>The reference's {@code tp_new} for these three types <em>is</em> {@code object_new}, so the call
     * is legal and leaves an object whose {@code __init__} has not run: every row refuses with
     * {@code I/O operation on uninitialized object} until it has.
     */
    private PyBufferedReader(PyType kind) {
        super(kind);
        initializeStore(DEFAULT_BUFFER_SIZE);
        initialized = false;
    }

    /** {@code tp.__new__(tp)} for one of the three buffered types: an uninitialized shell. */
    static PyBufferedReader shell(PyType kind) {
        return new PyBufferedReader(kind);
    }

    /**
     * {@code self->buffer_size = size; self->buffer = NULL}, which the reference does in
     * {@code buffered_init} and repeats when {@code __init__} runs a second time.
     */
    private void initializeStore(int size) {
        bufferSize = size;
        store = new byte[size];
        pos = 0;
        readEnd = -1;
        writePos = 0;
        writeEnd = -1;
        rawPos = 0;
        absPos = -1;
    }

    /**
     * The {@code buffer_size} of {@code BufferedReader(raw, buffer_size=DEFAULT_BUFFER_SIZE)}.
     *
     * <p>{@code buffered_init} reads it as a {@code Py_ssize_t} and refuses the two ends of the range
     * itself — {@code buffer size must be strictly positive} for zero and every negative number — while a
     * number too wide for a {@code ssize_t} is the converter's own {@code OverflowError}.
     */
    static int bufferSizeValue(PyObject value) {
        if (value == null) {
            return DEFAULT_BUFFER_SIZE;
        }
        long size = Numbers.size(value);
        if (size <= 0) {
            throw Slots.valueError("buffer size must be strictly positive");
        }
        // A size beyond the int range is accepted by the reference and only allocated when needed; this
        // runtime keeps the cursors in an int, so it reads such a request as the widest store it can hold.
        return (int) Math.min(size, Integer.MAX_VALUE - 8);
    }

    /**
     * A class written over {@code BufferedReader}, {@code BufferedWriter} or {@code BufferedRandom}
     * makes the same layer over whatever raw object it was handed: the reference reads the raw through
     * {@code self->raw}, so a Python raw (a {@code RawIOBase} subclass, {@code tarfile}'s
     * {@code _FileInFile}) is as good as a descriptor.
     */
    static PyBufferedReader construct(PyType kind, PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                kind.name(), args, kwNames, new String[] {"raw", "buffer_size"}, 1, 2);
        boolean writable = kind.matches(PyType.BUFFERED_WRITER)
                || kind.matches(PyType.BUFFERED_RANDOM);
        boolean readable = kind.matches(PyType.BUFFERED_READER)
                || kind.matches(PyType.BUFFERED_RANDOM);
        // A subclass's `tp_new` is handed the subclass's own arguments, which need not have a buffer size
        // in second place — `__init__` is where the reference converts the size, and where this layer
        // re-points at the raw. The three built-in types have no `__init__` of their own, so for them this
        // is the only place the size is seen.
        boolean builtin = kind == PyType.BUFFERED_READER
                || kind == PyType.BUFFERED_WRITER
                || kind == PyType.BUFFERED_RANDOM;
        return new PyBufferedReader(
                values[0], kind, readable, writable,
                builtin ? bufferSizeValue(values[1]) : DEFAULT_BUFFER_SIZE);
    }

    /**
     * {@code io.BufferedReader.__init__(raw, buffer_size=DEFAULT_BUFFER_SIZE)}.
     *
     * <p>The row exists for the classes written over this one. The reference builds a subclass's value
     * in {@code tp_new} from whatever the call was given and then lets the Python {@code __init__} run;
     * a class whose {@code __init__} is its own (tarfile's {@code ExFileObject}) calls
     * {@code super().__init__(fileobj)} with the raw it built, and that call is what re-points the layer.
     */
    private PyObject initRow(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                getType().name() + ".__init__",
                args,
                kwNames,
                new String[] {"raw", "buffer_size"},
                1,
                2);
        // `buffered_init` writes the new raw before it checks the size, so a refusal leaves the layer
        // uninitialized rather than pointed at the raw it had a moment ago.
        initialized = false;
        initialize(values[0], bufferSizeValue(values[1]));
        return PyNone.INSTANCE;
    }

    /** Point this layer at {@code raw}: the descriptor and the cursors, as {@code buffered_init} does. */
    private void initialize(PyObject raw, int size) {
        descriptor = raw;
        detached = false;
        readable = getType().matches(PyType.BUFFERED_READER)
                || getType().matches(PyType.BUFFERED_RANDOM);
        writable = getType().matches(PyType.BUFFERED_WRITER)
                || getType().matches(PyType.BUFFERED_RANDOM);
        initializeStore(size);
        initialized = true;
        // `_buffered_init` asks the descriptor where it is and remembers the answer, clearing the
        // failure when the descriptor has no tell — that cached answer is `RAW_TELL` from then on.
        try {
            rawTell();
        } catch (PyRaise notSeekable) {
            absPos = -1;
        }
    }

    /**
     * The descriptor, whether or not it is still held: what {@code sys.stdin} caches as its own answer to
     * being closed, exactly as {@code _textiowrapper_set_buffer} caches the one this layer reports.
     */
    PyObject descriptor() {
        return descriptor;
    }

    /** The same answer when the raw layer is a descriptor this runtime can read directly. */
    PyFileIO fileDescriptor() {
        return descriptor instanceof PyFileIO file ? file : null;
    }

    /** The descriptor, or the refusal that says there no longer is one. */
    private PyObject held() {
        if (detached) {
            throw Slots.valueError(DETACHED);
        }
        return descriptor;
    }

    /** Ask the descriptor a row of its own, which is how the reference reaches through {@code self->raw}. */
    private PyObject ask(String row) {
        return Slots.call(Slots.getAttr(descriptor, row), new PyObject[0], null);
    }

    @Override
    boolean closedFlag() {
        if (detached) {
            throw Slots.valueError(DETACHED);
        }
        if (!initialized) {
            throw Slots.valueError("I/O operation on uninitialized object");
        }
        return Slots.truth(Slots.getAttr(descriptor, "closed"));
    }

    @Override
    Set<String> notWritable() {
        return Set.of("name", "mode", "closed");
    }

    @Override
    Set<String> readOnly() {
        return Set.of("raw");
    }

    /** {@code VALID_READ_BUFFER}: the store holds octets for a reader, rather than being reset. */
    private boolean validRead() {
        return readable && readEnd != -1;
    }

    /** {@code VALID_WRITE_BUFFER}: a write has octets waiting for the descriptor. */
    private boolean validWrite() {
        return writable && writeEnd != -1;
    }

    /** {@code READAHEAD}: the octets still held, and 0 for a store that has been reset. */
    private long readahead() {
        return validRead() ? readEnd - pos : 0;
    }

    /** {@code RAW_OFFSET}: how far the descriptor's pointer runs ahead of the logical position. */
    private long rawOffset() {
        return (validRead() || validWrite()) && rawPos >= 0 ? rawPos - pos : 0;
    }

    /** {@code ADJUST_POSITION}, which drags the read edge along when the cursor passes it. */
    private void adjustPosition(int newPos) {
        pos = newPos;
        if (validRead() && readEnd < pos) {
            readEnd = pos;
        }
    }

    /** {@code _bufferedreader_reset_buf}, which is the third state of the store rather than an empty one. */
    private void resetReadBuf() {
        readEnd = -1;
    }

    private void reset() {
        pos = 0;
        readEnd = -1;
    }

    /** {@code _bufferedwriter_reset_buf}. */
    private void resetWriteBuf() {
        writePos = 0;
        writeEnd = -1;
    }

    /** {@code _buffered_raw_tell}, which caches the answer in {@code abs_pos}. */
    private long rawTell() {
        if (absPos != -1) {
            return absPos;
        }
        absPos = Numbers.cLong(ask("tell"));
        return absPos;
    }

    /** {@code _buffered_raw_seek}: two arguments, and the answer is the descriptor's new position. */
    private long rawSeek(long target, int whence) {
        PyObject answer = Slots.call(
                Slots.getAttr(held(), "seek"),
                new PyObject[] {PyInt.of(target), PyInt.of(whence)},
                null);
        absPos = Numbers.cLong(answer);
        return absPos;
    }

    /** {@code CHECK_CLOSED}: a closed descriptor is only a refusal when the store cannot answer instead. */
    private void checkClosed(String sentence) {
        if (closedFlag() && readahead() == 0) {
            throw Slots.valueError(sentence);
        }
    }

    /** The direction guard of a layer that files both halves of the rows whatever its mode was. */
    private void requireReadable(String row) {
        if (!initialized) {
            throw Slots.valueError("I/O operation on uninitialized object");
        }
        if (!readable) {
            throw Slots.unsupportedOperation(row);
        }
    }

    /**
     * {@code _bufferedwriter_flush_unlocked}: hand the accepted octets to the descriptor.
     *
     * <p>The descriptor is first rewound by {@code RAW_OFFSET + (pos - write_pos)} — how far it ran
     * ahead when the store was filled, plus how far the cursor moved past the first pending octet — so
     * the write lands where the logical stream says it should. A non-blocking descriptor that stops
     * taking octets raises {@code BlockingIOError}, as the reference's own flush does.
     */
    private void flushUnlocked() {
        if (validWrite() && writePos != writeEnd) {
            long rewind = rawOffset() + (pos - writePos);
            if (rewind != 0) {
                rawSeek(-rewind, 1);
                rawPos -= rewind;
            }
            while (writePos < writeEnd) {
                int taken = emit(Arrays.copyOfRange(store, writePos, writeEnd));
                if (taken == 0) {
                    throw wouldBlock(0);
                }
                writePos += taken;
                rawPos = writePos;
            }
        }
        // This is what makes `VALID_WRITE_BUFFER` false after a flush, and hence RAW_OFFSET zero when
        // the read buffer is empty too.
        resetWriteBuf();
    }

    /** {@code buffered_flush_and_rewind_unlocked}: flush, then pull the descriptor back to the cursor. */
    private void flushAndRewind() {
        flushUnlocked();
        if (readable) {
            rawSeek(-rawOffset(), 1);
            resetReadBuf();
        }
    }

    /** The {@code BlockingIOError} a non-blocking descriptor reports, with the octets it took so far. */
    private static PyRaise wouldBlock(int charactersWritten) {
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.BLOCKING_IO_ERROR,
                new PyObject[] {
                    PyInt.of(35),
                    PyStr.of("write could not complete without blocking"),
                    PyInt.of(charactersWritten)
                }));
    }

    /**
     * One read of the raw layer, which is {@code PyObject_CallMethod(self->raw, "read", "n")} whenever
     * the raw is not a descriptor this runtime owns. A Python raw ({@code _FileInFile}) answers the row
     * itself, so the call has to go through the object, not through a Java field.
     */
    private byte[] fetch(long wanted) {
        byte[] octets = fetchRaw(wanted);
        if (octets != null && octets.length > 0 && absPos != -1) {
            absPos += octets.length;
        }
        return octets;
    }

    private byte[] fetchRaw(long wanted) {
        PyObject source = held();
        if (source instanceof PyFileIO file) {
            return file.octets(wanted);
        }
        if (!declaresRead(source)) {
            // A raw that only answers `readinto`, which is the row the C buffer actually asks of its
            // descriptor: `rawiobase_readinto` in the reference is what `_FileInFile` inherits, and a
            // raw written that way has no `read` of its own to call.
            long ask = Math.min(wanted, Integer.MAX_VALUE - 8);
            PyByteArray into = PyByteArray.wrap(new byte[(int) ask]);
            PyObject count = Slots.call(
                    Slots.getAttr(source, "readinto"), new PyObject[] {into}, null);
            if (count == PyNone.INSTANCE) {
                // The raw layer's would-block answer, which is this layer's signal to stop reading.
                return null;
            }
            if (!(count instanceof PyInt given)) {
                throw Slots.typeError(
                        "readinto() should return int, not %s", Slots.argumentName(count));
            }
            long size = given.getValue().longValue();
            if (size < 0 || size > into.size()) {
                throw Slots.typeError("readinto() returned invalid length");
            }
            return Arrays.copyOf(into.raw(), (int) size);
        }
        PyObject answer = Slots.call(
                Slots.getAttr(source, "read"), new PyObject[] {PyInt.of(wanted)}, null);
        if (answer == PyNone.INSTANCE) {
            return null;
        }
        if (!(answer instanceof PyBytes octets)) {
            throw Slots.typeError(
                    "read() should return bytes, not %s", Slots.argumentName(answer));
        }
        return octets.getValue();
    }

    /** Whether {@code source} itself answers {@code read}, rather than inheriting the abstract refusal. */
    private static boolean declaresRead(PyObject source) {
        for (PyType owner : source.getType().mro()) {
            if (owner == PyType.RAW_IO_BASE || owner == PyType.IO_BASE || owner == PyType.OBJECT) {
                return false;
            }
            if (Specials.declared(owner, "read") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The write half of {@link #fetch}: {@code self->raw.write} takes what it can and says how many.
     *
     * <p>A raw that answers {@code None} — a non-blocking descriptor with no room — took nothing, and a
     * short {@code int} took that many. Both are {@code BlockingIOError} material to the caller, which is
     * why the count is returned rather than checked here.
     *
     * @return the octets the raw layer took, 0 when it would block
     */
    private int emit(byte[] octets) {
        if (octets.length == 0) {
            return 0;
        }
        int taken = emitRaw(octets);
        if (taken > 0 && absPos != -1) {
            absPos += taken;
        }
        return taken;
    }

    private int emitRaw(byte[] octets) {
        PyObject target = held();
        if (target instanceof PyFileIO file) {
            return file.writeOctets(octets);
        }
        PyObject answer = Slots.call(
                Slots.getAttr(target, "write"), new PyObject[] {PyBytes.of(octets)}, null);
        if (answer == PyNone.INSTANCE) {
            return 0;
        }
        if (answer instanceof PyInt written) {
            long count = written.getValue().longValue();
            if (count < 0 || count > octets.length) {
                throw Slots.osError("raw write() returned invalid length");
            }
            return (int) count;
        }
        throw Slots.osError("raw write() returned invalid length");
    }

    /**
     * {@code _bufferedreader_fill_buffer}: one read of the descriptor into the tail of the store.
     *
     * <p>A dry descriptor leaves the cursors where they were, which is how a reset store stays reset
     * through a {@code peek} that had to ask and got nothing.
     *
     * @return how many octets came, 0 at the end of the descriptor and -1 when it would block
     */
    private int fill() {
        int start = readEnd < 0 ? 0 : readEnd;
        byte[] chunk = fetch(bufferSize - start);
        if (chunk == null) {
            return -1;
        }
        if (chunk.length == 0) {
            return 0;
        }
        System.arraycopy(chunk, 0, store, start, chunk.length);
        readEnd = start + chunk.length;
        // `_bufferedreader_fill_buffer` remembers where the descriptor stopped: RAW_OFFSET is measured
        // from it, so a read-ahead is what makes `tell` answer the logical position and not the pointer.
        rawPos = readEnd;
        return chunk.length;
    }

    /** The next {@code wanted} octets of the store, which the callers only ask for when there are some. */
    private byte[] take(long wanted) {
        int given = (int) Math.min(wanted, readahead());
        byte[] answer = Arrays.copyOfRange(store, pos, pos + given);
        pos += given;
        return answer;
    }

    /** What the store holds without consuming it — {@code peek}'s answer, and the empty store's. */
    private byte[] heldOctets() {
        return Arrays.copyOfRange(store, pos, Math.max(pos, readEnd));
    }

    /** {@code _bufferedreader_read_fast}: the answer out of the store, or null when it does not reach. */
    private byte[] readFast(long wanted) {
        return wanted > readahead() ? null : take(wanted);
    }

    /**
     * {@code buffer.read(n)}: {@code n} octets, all that is left for a negative count, nothing for 0.
     *
     * <p>A non-blocking descriptor with nothing to give answers {@code None}, and one that gave part of
     * what was asked answers that part.
     */
    private PyObject read(long wanted) {
        requireReadable("read");
        held();
        if (wanted < -1) {
            throw Slots.valueError("read length must be non-negative or -1");
        }
        checkClosed(READ);
        if (wanted == -1) {
            return readAll();
        }
        byte[] fast = readFast(wanted);
        if (fast != null) {
            return PyBytes.of(fast);
        }
        byte[] generic = readGeneric(wanted);
        return generic == null ? PyNone.INSTANCE : PyBytes.of(generic);
    }

    /**
     * {@code _bufferedreader_read_all}: the store, and then whatever the descriptor still has.
     *
     * <p>The store is copied and reset before the descriptor is asked, so a {@code read()} that reaches a
     * closed descriptor with octets in hand reports the descriptor's refusal and not a sentence of this
     * layer's — and the octets it had are gone either way. A non-blocking descriptor that would block
     * before answering anything makes this {@code None}; one that already gave octets leaves them in the
     * answer.
     */
    private PyObject readAll() {
        byte[] buffered = take(readahead());
        if (writable) {
            flushAndRewind();
        }
        reset();
        byte[] rest = restartOfRaw();
        if (rest == null) {
            return buffered.length == 0 ? PyNone.INSTANCE : PyBytes.of(buffered);
        }
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        writeInto(answer, buffered);
        writeInto(answer, rest);
        return PyBytes.of(answer.toByteArray());
    }

    /**
     * The descriptor's own {@code readall()}, or — for a raw object that has only {@code read}, as
     * tarfile's {@code _FileInFile} does — {@code read} in blockfuls until one comes back empty. That
     * second shape is what {@code _bufferedreader_read_all} falls back to.
     *
     * @return the octets, or null when a non-blocking descriptor would block before giving any
     */
    private byte[] restartOfRaw() {
        PyObject source = held();
        PyObject method;
        try {
            method = Slots.getAttr(source, "readall");
        } catch (PyRaise missing) {
            method = null;
        }
        if (method != null) {
            PyObject rest = Slots.call(method, new PyObject[0], null);
            if (rest instanceof PyBytes tail) {
                return tail.getValue();
            }
            if (rest == PyNone.INSTANCE) {
                return null;
            }
            throw Slots.typeError("readall() should return bytes");
        }
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        while (true) {
            PyObject chunk = Slots.call(
                    Slots.getAttr(source, "read"), new PyObject[] {PyInt.of(bufferSize)}, null);
            if (chunk == PyNone.INSTANCE) {
                return answer.size() == 0 ? null : answer.toByteArray();
            }
            if (!(chunk instanceof PyBytes given)) {
                throw Slots.typeError("read() should return bytes");
            }
            if (given.getValue().length == 0) {
                return answer.toByteArray();
            }
            writeInto(answer, given.getValue());
        }
    }

    /**
     * {@code _bufferedreader_read_generic}: the store, whole blocks straight from the descriptor, then
     * fills until the count is reached.
     *
     * <p>The middle loop is what makes a request bigger than the store cost few system calls rather than
     * one fill per block, and it is skipped entirely below {@code buffer_size} — where the store is emptied
     * into the answer, reset, and refilled until the count is reached. The octets beyond the count stay in
     * the store for the next row.
     */
    private byte[] readGeneric(long wanted) {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        writeInto(answer, take(readahead()));
        long remaining = wanted - answer.size();
        if (writable) {
            flushAndRewind();
        }
        reset();
        boolean wouldBlock = false;
        while (remaining >= bufferSize) {
            // Whole blocks go straight from the descriptor, which is what makes a request bigger than the
            // store cost few calls rather than one fill per block.
            byte[] chunk = fetch(remaining - remaining % bufferSize);
            if (chunk == null) {
                wouldBlock = true;
                break;
            }
            if (chunk.length == 0) {
                return answer.toByteArray();
            }
            writeInto(answer, chunk);
            remaining -= chunk.length;
        }
        if (!wouldBlock) {
            pos = 0;
            readEnd = 0;
            rawPos = 0;
            while (remaining > 0 && readEnd < bufferSize) {
                int got = fill();
                if (got < 0) {
                    wouldBlock = true;
                    break;
                }
                if (got == 0) {
                    break;
                }
                long given = Math.min(remaining, got);
                writeInto(answer, take(given));
                remaining -= given;
                if (remaining == 0) {
                    break;
                }
            }
        }
        if (answer.size() == 0 && wouldBlock) {
            return null;
        }
        return answer.toByteArray();
    }

    /** {@code buffer.read1(n)}: the store if it holds anything, and one read of the descriptor if not. */
    private PyObject read1(long wanted) {
        requireReadable("read1");
        held();
        // A negative count is one storeful, and the answer to a dry store is that one read whatever the
        // count was, so what a read1 fetches beyond its own answer is lost rather than buffered.
        long limit = wanted < 0 ? bufferSize : wanted;
        checkClosed(READ);
        if (limit == 0) {
            return PyBytes.of(new byte[0]);
        }
        long have = readahead();
        if (have > 0) {
            return PyBytes.of(take(Math.min(have, limit)));
        }
        if (writable) {
            flushAndRewind();
        }
        resetReadBuf();
        byte[] fetched = fetch(limit);
        return PyBytes.of(fetched == null ? new byte[0] : fetched);
    }

    /**
     * {@code buffer.readinto(target)}: the store, then fills of the descriptor, poured into the caller's
     * buffer. {@code readinto1} stops after the store and at most one read of the descriptor.
     *
     * <p>The target is any writable buffer — a {@code bytearray}, a writable {@code memoryview}, an
     * {@code array.array} — and the answer is the number of octets written, {@code None} when a
     * non-blocking descriptor would block before giving any, and 0 at the end of the descriptor. The
     * cursors move exactly as they do for {@code read}, which is what keeps {@code tell} and {@code seek}
     * correct after a fill through a view.
     */
    private PyObject readIntoRow(boolean single, PyObject[] args, String[] kwNames) {
        String name = single ? "readinto1" : "readinto";
        Arguments.noKeywords("BufferedReader." + name + "()", kwNames);
        Arguments.exactlyOne("BufferedReader." + name + "()", args, kwNames);
        WritableBuffer target = WritableBuffer.of(args[0]);
        if (target == null) {
            throw Slots.typeError(
                    "%s() argument must be read-write bytes-like object, not %s",
                    name, Slots.argumentName(args[0]));
        }
        requireReadable(name);
        held();
        checkClosed(READ);
        int length = target.length();
        if (length == 0) {
            return PyInt.of(0);
        }
        int total = 0;
        long have = readahead();
        if (have > 0) {
            int given = (int) Math.min(have, length);
            target.write(0, take(given), given);
            total += given;
            if (given == length) {
                // The store answered the whole request, which the reference returns without touching
                // the descriptor at all.
                return PyInt.of(total);
            }
        }
        if (writable) {
            flushAndRewind();
        }
        resetReadBuf();
        pos = 0;
        if (single) {
            int remaining = length - total;
            if (remaining > 0) {
                if (length > bufferSize) {
                    // A request wider than the store is read straight into the caller's span rather than
                    // through the store — one read of the descriptor either way, but of the whole
                    // remainder rather than of one block.
                    byte[] chunk = fetch(remaining);
                    if (chunk == null) {
                        return total == 0 ? PyNone.INSTANCE : PyInt.of(total);
                    }
                    int given = Math.min(chunk.length, remaining);
                    target.write(total, chunk, given);
                    total += given;
                } else if (total == 0) {
                    // A dry store within one block wide: one fill, then copy what it brought.
                    int got = fill();
                    if (got < 0) {
                        return PyNone.INSTANCE;
                    }
                    if (got > 0) {
                        int given = Math.min(got, length);
                        target.write(0, take(given), given);
                        total += given;
                        if (readahead() == 0) {
                            reset();
                        }
                    }
                }
            }
            return PyInt.of(total);
        }
        boolean wouldBlock = false;
        while (total < length) {
            if (readahead() == 0) {
                reset();
            }
            int got = fill();
            if (got < 0) {
                wouldBlock = true;
                break;
            }
            if (got == 0) {
                break;
            }
            int given = Math.min(got, length - total);
            target.write(total, take(given), given);
            total += given;
            if (readahead() == 0) {
                reset();
            }
        }
        if (total == 0 && wouldBlock) {
            return PyNone.INSTANCE;
        }
        return PyInt.of(total);
    }

    /** {@code buffer.peek(n)}: the whole store, which the count never limits. */
    private PyObject peek() {
        requireReadable("peek");
        held();
        checkClosed(PEEK);
        if (readahead() > 0) {
            return PyBytes.of(heldOctets());
        }
        if (writable) {
            flushAndRewind();
        }
        reset();
        fill();
        pos = 0;
        return PyBytes.of(heldOctets());
    }

    /**
     * {@code buffer.readline(n)}: the store scanned for a '\n', one fill at a time.
     *
     * <p>The limit counts octets, and the first scan is over at most that many of them — which is what
     * makes a limit inside the store cost no read at all and a terminator beyond it still found. A line
     * that runs past the end of the store is the one case the loop fills for, and each fill starts the
     * scan over at 0 because the store has just been reset.
     */
    private PyObject readline(long limit) {
        requireReadable("readline");
        held();
        checkClosed(READLINE);
        int scanned = (int) (limit >= 0 ? Math.min(readahead(), limit) : readahead());
        int stop = indexOf(store, pos, pos + scanned, '\n');
        if (stop >= 0) {
            byte[] line = Arrays.copyOfRange(store, pos, stop + 1);
            pos = stop + 1;
            return PyBytes.of(line);
        }
        if (scanned == limit) {
            byte[] line = Arrays.copyOfRange(store, pos, pos + scanned);
            pos += scanned;
            return PyBytes.of(line);
        }
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        if (scanned > 0) {
            writeInto(line, Arrays.copyOfRange(store, pos, pos + scanned));
            pos += scanned;
            if (limit >= 0) {
                limit -= scanned;
            }
        }
        if (writable) {
            flushAndRewind();
        }
        while (true) {
            reset();
            int got = fill();
            if (got <= 0) {
                break;
            }
            if (limit >= 0 && got > limit) {
                got = (int) limit;
            }
            stop = indexOf(store, 0, got, '\n');
            if (stop >= 0) {
                writeInto(line, Arrays.copyOfRange(store, 0, stop + 1));
                pos = stop + 1;
                break;
            }
            writeInto(line, Arrays.copyOfRange(store, 0, got));
            if (got == limit) {
                pos = got;
                break;
            }
            if (limit >= 0) {
                limit -= got;
            }
        }
        return PyBytes.of(line.toByteArray());
    }

    /** Where the octet is in the store, or -1 — the scan the reference does over the same bytes. */
    private static int indexOf(byte[] haystack, int from, int to, int needle) {
        for (int at = from; at < to; at++) {
            if (haystack[at] == needle) {
                return at;
            }
        }
        return -1;
    }

    private static void writeInto(ByteArrayOutputStream out, byte[] octets) {
        out.write(octets, 0, octets.length);
    }

    /**
     * {@code buffer.detach()}: hand over the descriptor, and keep the refusal for every row that needs it.
     *
     * <p>What the row does before taking the descriptor away is flush <em>this</em> object, through its own
     * attribute, which is why a closed descriptor is refused by its own full stop rather than by a sentence
     * of this layer's. The store keeps whatever it holds; no row reaches it again.
     */
    private PyObject detach() {
        held();
        Slots.call(Slots.getAttr(this, "flush"), new PyObject[0], null);
        detached = true;
        return descriptor;
    }

    /** {@code buffer.close()}: flush first, then the descriptor, and the descriptor closes either way. */
    @Override
    public PyObject close() {
        held();
        if (Slots.truth(Slots.getAttr(descriptor, "closed"))) {
            return PyNone.INSTANCE;
        }
        // The reference flushes through the object's own row — so an instance-level `flush` is honoured —
        // and remembers a failure rather than giving up: the descriptor is closed regardless.
        PyRaise flushFailure = null;
        try {
            Slots.call(Slots.getAttr(this, "flush"), new PyObject[0], null);
        } catch (PyRaise failure) {
            flushFailure = failure;
        }
        PyRaise closeFailure = null;
        try {
            ask("close");
        } catch (PyRaise failure) {
            closeFailure = failure;
        }
        pos = 0;
        readEnd = 0;
        if (closeFailure != null) {
            if (flushFailure != null) {
                // The close failure is what the caller sees; the flush failure is its context.
                closeFailure.value().setContext(flushFailure.value());
            }
            throw closeFailure;
        }
        if (flushFailure != null) {
            throw flushFailure;
        }
        return PyNone.INSTANCE;
    }

    private PyObject truncate(PyObject[] args, String[] kwNames) {
        Arguments.parameters(
                "truncate",
                args,
                kwNames,
                new String[] {"_"},
                new PyObject[] {PyNone.INSTANCE},
                0);
        held();
        if (!writable) {
            throw Slots.unsupportedOperation("truncate");
        }
        checkClosed(TRUNCATE);
        flushAndRewind();
        PyObject position = args.length > 0 ? args[0] : PyNone.INSTANCE;
        PyObject answer = Slots.call(
                Slots.getAttr(descriptor, "truncate"), new PyObject[] {position}, null);
        // Reset the cached position: the descriptor may have moved it.
        try {
            rawTell();
        } catch (PyRaise notSeekable) {
            absPos = -1;
        }
        return answer;
    }

    /**
     * {@code buffer.write(data)}: the octets are taken first, and the store hands them over in blocks.
     *
     * <p>The count is the octets accepted, not the ones written: anything below one block stays here
     * until a flush, a blockful, or a close. A write also drops whatever the store was holding for a
     * read, because a {@code '+'} stream may not answer from a buffer the write has just overtaken.
     *
     * <p>A non-blocking descriptor that fills up is the third answer. The octets already accepted from
     * this call — handed to the descriptor or still in the store, which will be flushed later — are the
     * {@code characters_written} the {@code BlockingIOError} carries, and the rest of the call is
     * dropped: the caller is told to retry from there, exactly as the reference does.
     */
    private PyObject write(PyObject data) {
        if (!initialized) {
            throw Slots.valueError("I/O operation on uninitialized object");
        }
        if (!writable) {
            throw Slots.unsupportedOperation("write");
        }
        byte[] octets = PyFileIO.octetsToWrite(data);
        held();
        checkClosed("write to closed file");
        // The fast path: the octets fit from the cursor and are copied into the store in place. A write
        // does not reset the read buffer — it overwrites the read-ahead it has run past, and that is what
        // makes a later seek back into the store read the new octets out of it.
        if (!validRead() && !validWrite()) {
            pos = 0;
            rawPos = 0;
        }
        int avail = bufferSize - pos;
        if (octets.length <= avail && octets.length < bufferSize) {
            System.arraycopy(octets, 0, store, pos, octets.length);
            if (!validWrite() || writePos > pos) {
                writePos = pos;
            }
            adjustPosition(pos + octets.length);
            if (pos > writeEnd) {
                writeEnd = pos;
            }
            return PyInt.of(octets.length);
        }

        // Hand over what the store already holds. A non-blocking descriptor that fills up leaves the
        // accepted octets in the store for the caller to retry from.
        try {
            flushUnlocked();
        } catch (PyRaise refusal) {
            if (!blocked(refusal)) {
                throw refusal;
            }
            if (readable) {
                resetReadBuf();
            }
            // Make some place by shifting the buffer down, and buffer as much as there is room for.
            System.arraycopy(store, writePos, store, 0, writeEnd - writePos);
            writeEnd -= writePos;
            rawPos -= writePos;
            pos -= writePos;
            writePos = 0;
            avail = bufferSize - writeEnd;
            if (octets.length <= avail) {
                System.arraycopy(octets, 0, store, writeEnd, octets.length);
                writeEnd += octets.length;
                pos += octets.length;
                return PyInt.of(octets.length);
            }
            System.arraycopy(octets, 0, store, writeEnd, avail);
            writeEnd += avail;
            pos += avail;
            throw wouldBlock(avail);
        }

        // A read-ahead that was filled but not modified still has to be pulled back, which is the
        // issue-6629 adjustment the flush alone does not make.
        long offset = rawOffset();
        if (offset != 0) {
            rawSeek(-offset, 1);
            rawPos -= offset;
        }

        int remaining = octets.length;
        int written = 0;
        while (remaining >= bufferSize) {
            int taken = emit(Arrays.copyOfRange(octets, written, octets.length));
            if (taken == 0) {
                // The raw file is non-blocking and took nothing: buffer as much as possible.
                if (remaining > bufferSize) {
                    System.arraycopy(octets, written, store, 0, bufferSize);
                    rawPos = 0;
                    adjustPosition(bufferSize);
                    writeEnd = bufferSize;
                    written += bufferSize;
                    throw wouldBlock(written);
                }
                break;
            }
            written += taken;
            remaining -= taken;
        }
        if (readable) {
            resetReadBuf();
        }
        if (remaining > 0) {
            System.arraycopy(octets, written, store, 0, remaining);
            written += remaining;
        }
        writePos = 0;
        writeEnd = remaining;
        adjustPosition(remaining);
        rawPos = 0;
        return PyInt.of(written);
    }

    /** Whether a refused flush is the {@code BlockingIOError} a non-blocking descriptor raises. */
    private static boolean blocked(PyRaise refusal) {
        return refusal.value().getType() == ExceptionTypes.BLOCKING_IO_ERROR;
    }

    /**
     * {@code buffer.seek(cookie, whence)}: the whence is checked before the stream is even looked at.
     *
     * <p>The range is the one {@code _io._Buffered.seek} allows rather than the pair the operating system
     * answers, so {@code seek(0, 9)} is {@code whence value 9 unsupported} here and {@code [Errno 29]
     * Illegal seek} on the descriptor below. macOS files {@code SEEK_DATA} and {@code SEEK_HOLE} beside the
     * three the language names, and the row lets all five through to the seekable question — which, for a
     * pipe, is the last thing this row ever asks.
     *
     * <p>A relative seek is <em>not</em> forwarded as one. The descriptor's pointer is past every octet the
     * store has already read ahead of, so {@code seek(0, 1)} after a {@code read(512)} would land a whole
     * store away from where the caller is. The reference turns the pair into an absolute position —
     * {@code raw.tell() - readahead() + offset} — and, when that position is one the store already holds,
     * only moves {@code pos} instead of touching the descriptor at all: {@code seek(0, 1)} after
     * {@code read(512)} leaves the descriptor at 8192 and answers 512. That is the path
     * {@code gzip.GzipFile.tell()} takes (an {@code IOBase.tell()} that is really {@code seek(0, 1)}), so
     * asking a gzip stream where it is no longer moves it.
     */
    private PyObject seek(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedReader.seek()", kwNames);
        Arguments.between("seek", args, kwNames, 1, 2);
        int whence = args.length > 1 ? Numbers.cInteger(args[1]) : 0;
        if (whence < 0 || (whence > 2 && whence != 3 && whence != 4)) {
            throw Slots.valueError("whence value %d unsupported", whence);
        }
        held();
        checkClosed(SEEK);
        if (ask("seekable") != PyBool.TRUE) {
            throw Slots.unsupportedOperation("File or stream is not seekable.");
        }
        long target = Numbers.cLong(args[0]);
        // Seeking inside the store costs no descriptor call at all: the reference turns the pair into an
        // offset from the cursor and only moves `pos` when it lands in the octets already held.
        if ((whence == 0 || whence == 1) && readable) {
            long current = rawTell();
            long avail = readahead();
            if (avail > 0) {
                long offset = whence == 0 ? target - (current - rawOffset()) : target;
                if (offset >= -pos && offset <= avail) {
                    pos += (int) offset;
                    // GH-95782: a seek before the start of the stream answers zero rather than a
                    // negative position.
                    return PyInt.of(Math.max(current - avail + offset, 0));
                }
            }
        }
        // The fallback: hand over the writes and let the descriptor seek. A relative seek is relative to
        // the logical position, which is the pointer less what the store read ahead of it.
        if (writable) {
            flushUnlocked();
        }
        if (whence == 1) {
            target -= rawOffset();
        }
        long answer = rawSeek(target, whence);
        rawPos = -1;
        if (readable) {
            resetReadBuf();
        }
        return PyInt.of(answer);
    }

    /** Clinic's {@code (self, size=-1, /)} over {@code Py_ssize_t(accept={int, NoneType})}. */
    private PyObject readRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedReader.read()", kwNames);
        Arguments.atMost("read", args, kwNames, 1);
        return read(args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1));
    }

    /** The same slot without the {@code None}: {@code read1} converts with plain {@code getarg_psize}. */
    private PyObject read1Row(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedReader.read1()", kwNames);
        Arguments.atMost("read1", args, kwNames, 1);
        return read1(args.length == 0 ? -1 : Numbers.size(args[0]));
    }

    /** {@code peek}'s count, which is converted and then ignored. */
    private PyObject peekRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedReader.peek()", kwNames);
        Arguments.atMost("peek", args, kwNames, 1);
        if (args.length > 0) {
            Numbers.size(args[0]);
        }
        return peek();
    }

    private PyObject readlineRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedReader.readline()", kwNames);
        Arguments.atMost("readline", args, kwNames, 1);
        return readline(args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1));
    }

    private static Members table(PyType type) {
        return Members.of(type)
            .add("read", (self, args, kwNames) ->
                    ((PyBufferedReader) self).readRow(args, kwNames))
            .add("read1", (self, args, kwNames) ->
                    ((PyBufferedReader) self).read1Row(args, kwNames))
            .add("peek", (self, args, kwNames) -> ((PyBufferedReader) self).peekRow(args, kwNames))
            .add("readline", (self, args, kwNames) ->
                    ((PyBufferedReader) self).readlineRow(args, kwNames))
            .add("readlines", (self, args, kwNames) -> ((PyIOStream) self).readLines(args, kwNames))
            // The two fill rows of this layer: the buffer protocol they ask for is the common writable
            // view, so a bytearray, a writable memoryview and an array.array all answer.
            .add("readinto", (self, args, kwNames) ->
                    ((PyBufferedReader) self).readIntoRow(false, args, kwNames))
            .add("readinto1", (self, args, kwNames) ->
                    ((PyBufferedReader) self).readIntoRow(true, args, kwNames))
            // The rows above file a write of their own; the three types this class can wear differ only in
            // which direction their bodies let through, so the refusals and the work share one table.
            .add("write", (self, args, kwNames) -> {
                Arguments.onlyPositional("write", args, kwNames, 1);
                return ((PyBufferedReader) self).write(args[0]);
            })
            .add("writelines", (self, args, kwNames) -> ((PyIOStream) self).writeLines(args, kwNames))
            .add("truncate", (self, args, kwNames) ->
                    ((PyBufferedReader) self).truncate(args, kwNames))
            .add("seek", (self, args, kwNames) ->
                    ((PyBufferedReader) self).seek(args, kwNames))
            .addNoArgument("tell", self -> {
                PyBufferedReader buffer = (PyBufferedReader) self;
                buffer.held();
                // The reference asks the descriptor every time — the cache is only for the seek fast path —
                // which is what makes `tell` on a closed buffer refuse at all.
                long position = Numbers.cLong(buffer.ask("tell")) - buffer.rawOffset();
                return PyInt.of(Math.max(position, 0));
            })
            // `flush` is `_io._Buffered.flush`: a writable layer hands over its octets and rewinds the
            // descriptor to the logical position, which drops the read-ahead; a reader only asks its
            // descriptor to flush and keeps what it holds.
            .addNoArgument("flush", self -> {
                PyBufferedReader buffer = (PyBufferedReader) self;
                buffer.held();
                buffer.checkClosed("flush of closed file");
                if (buffer.writable) {
                    buffer.flushAndRewind();
                    return PyNone.INSTANCE;
                }
                return buffer.ask("flush");
            })
            .addNoArgument("close", self -> ((PyBufferedReader) self).close())
            .addNoArgument("detach", self -> ((PyBufferedReader) self).detach())
            .addNoArgument("fileno", self -> {
                ((PyBufferedReader) self).held();
                return ((PyBufferedReader) self).ask("fileno");
            })
            .addNoArgument("isatty", self -> {
                ((PyBufferedReader) self).held();
                return ((PyBufferedReader) self).ask("isatty");
            })
            .addNoArgument("readable", self -> {
                ((PyBufferedReader) self).held();
                return ((PyBufferedReader) self).ask("readable");
            })
            .addNoArgument("seekable", self -> {
                ((PyBufferedReader) self).held();
                return ((PyBufferedReader) self).ask("seekable");
            })
            .add("writable", (self, args, kwNames) -> {
                Arguments.none("_IOBase.writable()", args, kwNames);
                return PyBool.of(((PyBufferedReader) self).writable);
            })
            .add("_checkClosed", (self, args, kwNames) ->
                    ((PyIOStream) self).checkClosedRow(args, kwNames))
            .add("_checkReadable", (self, args, kwNames) ->
                    ((PyBufferedReader) self).checked(
                            "_checkReadable", "not readable", args, kwNames,
                            () -> Slots.call(
                                    Slots.getAttr(self, "readable"), new PyObject[0], null)))
            .add("_checkSeekable", (self, args, kwNames) ->
                    ((PyBufferedReader) self).checked(
                            "_checkSeekable", "not seekable", args, kwNames,
                            () -> Slots.call(
                                    Slots.getAttr(self, "seekable"), new PyObject[0], null)))
            .add("_checkWritable", (self, args, kwNames) ->
                    ((PyBufferedReader) self).checked(
                            "_checkWritable", "not writable", args, kwNames,
                            () -> Slots.call(
                                    Slots.getAttr(self, "writable"), new PyObject[0], null)))
            .add("_dealloc_warn", (self, args, kwNames) ->
                    ((PyIOStream) self).deallocWarn(args, kwNames))
            .add("__getstate__", (self, args, kwNames) -> ((PyIOStream) self).getState(args, kwNames))
            .add("__enter__", (self, args, kwNames) -> ((PyIOStream) self).enter(args, kwNames))
            .add("__init__", (self, args, kwNames) ->
                    ((PyBufferedReader) self).initRow(args, kwNames))
            .add("__exit__", (self, args, kwNames) -> ((PyIOStream) self).exit(args));
    }

    private static final Members METHODS = table(PyType.BUFFERED_READER);
    private static final Members WRITER_METHODS = table(PyType.BUFFERED_WRITER);
    private static final Members RANDOM_METHODS = table(PyType.BUFFERED_RANDOM);

    /** {@code iter(buffer)}: the store is its own cursor, and the descriptor's flag says whether it runs. */
    @Override
    public PyObject iterator() {
        checkOpen();
        return this;
    }

    @Override
    public PyObject nextOrNull() {
        held();
        PyBytes line = (PyBytes) readline(-1);
        return line.getValue().length == 0 ? null : line;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            // `raw` is the one row of this layer filed as a struct member rather than a getset, so it
            // answers `None` where the four below refuse: there is no descriptor left to report.
            case "raw" -> detached ? PyNone.INSTANCE : descriptor;
            case "closed" -> Slots.getAttr(held(), "closed");
            case "name" -> Slots.getAttr(held(), "name");
            case "mode" -> Slots.getAttr(held(), "mode");
            default -> super.getAttr(attribute);
        };
    }

    /**
     * {@code repr(buffer)}: the name of the descriptor, which is the only part this layer has to say.
     *
     * <p>The name is read as this object's own attribute, which is what decides the two ways the field
     * disappears. A descriptor whose constructor-filed entry has been deleted is a missing attribute, and
     * {@code PyObject_GetOptionalAttr} — the row the reference uses — answers no value rather than an
     * error; a detached buffer is the one error the same row lets reach its own check, and clears there
     * for the comment's own reason. Both print the repr without the name.
     */
    @Override
    public String repr() {
        String typeName = getType().name();
        PyObject name;
        try {
            name = Slots.getAttr(this, "name");
        } catch (PyRaise refused) {
            if (refused.value().getType() != ExceptionTypes.VALUE_ERROR
                    && refused.value().getType() != ExceptionTypes.ATTRIBUTE_ERROR) {
                throw refused;
            }
            return "<_io." + typeName + ">";
        }
        return "<_io." + typeName + " name=" + Slots.repr(name) + ">";
    }
}
