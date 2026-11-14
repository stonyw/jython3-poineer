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
    private static final int BUFFER_SIZE = 8192;

    /** The five sentences {@code CHECK_CLOSED} says from inside the five rows that carry it. */
    private static final String READ = "read of closed file";

    private static final String PEEK = "peek of closed file";
    private static final String READLINE = "readline of closed file";
    private static final String SEEK = "seek of closed file";
    private static final String TRUNCATE = "truncate of closed file";

    private PyObject descriptor;

    /** {@code self->buffer}, with {@code self->pos} into it and {@code self->read_end} at its far edge. */
    private final byte[] store = new byte[BUFFER_SIZE];
    private int pos;
    private int readEnd = -1;

    /** Whether {@code detach()} has taken the descriptor away, which is the first question of every row. */
    private boolean detached;

    /** The two directions the mode string chose, which decide what the rows below refuse. */
    private boolean readable;
    private boolean writable;

    /**
     * {@code self->buffer}: the octets a writer has accepted but not yet handed to the descriptor.
     *
     * <p>The reference keeps one array for both directions and this layer keeps a growable one for the
     * outgoing half, since only a writer accumulates — a reader's octets live in {@link #store}.
     */
    private final ByteArrayOutputStream outgoing = new ByteArrayOutputStream();

    PyBufferedReader(PyFileIO raw) {
        this(raw, PyType.BUFFERED_READER, true, false);
    }

    PyBufferedReader(PyObject raw, PyType type, boolean readable, boolean writable) {
        super(type);
        this.descriptor = raw;
        this.readable = readable;
        this.writable = writable;
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
        return new PyBufferedReader(values[0], kind, readable, writable);
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
        initialize(values[0]);
        return PyNone.INSTANCE;
    }

    /** Point this layer at {@code raw}: the descriptor and the cursors, as {@code buffered_init} does. */
    private void initialize(PyObject raw) {
        descriptor = raw;
        detached = false;
        readable = getType().matches(PyType.BUFFERED_READER)
                || getType().matches(PyType.BUFFERED_RANDOM);
        writable = getType().matches(PyType.BUFFERED_WRITER)
                || getType().matches(PyType.BUFFERED_RANDOM);
        pos = 0;
        readEnd = -1;
        outgoing.reset();
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

    /** {@code READAHEAD}: the octets still held, and 0 for a store that has been reset. */
    private long readahead() {
        return readEnd < 0 ? 0 : readEnd - pos;
    }

    /** {@code _bufferedreader_reset_buf}, which is the third state of the store rather than an empty one. */
    private void reset() {
        readEnd = -1;
    }

    /** {@code CHECK_CLOSED}: a closed descriptor is only a refusal when the store cannot answer instead. */
    private void checkClosed(String sentence) {
        if (closedFlag() && readahead() == 0) {
            throw Slots.valueError(sentence);
        }
    }

    /** The direction guard of a layer that files both halves of the rows whatever its mode was. */
    private void requireReadable(String row) {
        if (!readable) {
            throw Slots.unsupportedOperation(row);
        }
    }

    /**
     * Hand the outgoing octets over before reading, which is what a {@code '+'} stream needs: the
     * descriptor holds what a write has accepted, and one read cannot see it until the buffer is drained.
     */
    private void drain() {
        if (outgoing.size() > 0) {
            flushOutgoing();
        }
    }

    /** {@code _bufferedwriter_flush_unlocked}: the octets the writer accepted, in one write for as long. */
    private void flushOutgoing() {
        if (outgoing.size() == 0) {
            return;
        }
        byte[] octets = outgoing.toByteArray();
        outgoing.reset();
        emit(octets);
    }

    /**
     * One read of the raw layer, which is {@code PyObject_CallMethod(self->raw, "read", "n")} whenever
     * the raw is not a descriptor this runtime owns. A Python raw ({@code _FileInFile}) answers the row
     * itself, so the call has to go through the object, not through a Java field.
     */
    private byte[] fetch(long wanted) {
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

    /** The write half of {@link #fetch}: {@code self->raw.write} has to take every octet it was given. */
    private void emit(byte[] octets) {
        if (octets.length == 0) {
            return;
        }
        PyObject target = held();
        if (target instanceof PyFileIO file) {
            file.writeOctets(octets);
            return;
        }
        PyObject answer = Slots.call(
                Slots.getAttr(target, "write"), new PyObject[] {PyBytes.of(octets)}, null);
        if (!(answer instanceof PyInt written) || written.getValue().longValue() != octets.length) {
            throw Slots.osError("raw write() returned invalid length");
        }
    }

    /**
     * {@code _bufferedreader_fill_buffer}: one read of the descriptor into the tail of the store.
     *
     * <p>A dry descriptor leaves the cursors where they were, which is how a reset store stays reset
     * through a {@code peek} that had to ask and got nothing.
     *
     * @return how many octets came, and 0 at the end of the descriptor
     */
    private int fill() {
        drain();
        int start = readEnd < 0 ? 0 : readEnd;
        byte[] chunk = fetch(BUFFER_SIZE - start);
        if (chunk.length == 0) {
            return 0;
        }
        System.arraycopy(chunk, 0, store, start, chunk.length);
        readEnd = start + chunk.length;
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

    /** {@code buffer.read(n)}: {@code n} octets, all that is left for a negative count, nothing for 0. */
    private PyObject read(long wanted) {
        requireReadable("read");
        held();
        if (wanted < -1) {
            throw Slots.valueError("read length must be non-negative or -1");
        }
        checkClosed(READ);
        if (wanted == -1) {
            return PyBytes.of(readAll());
        }
        byte[] fast = readFast(wanted);
        return PyBytes.of(fast == null ? readGeneric(wanted) : fast);
    }

    /**
     * {@code _bufferedreader_read_all}: the store, and then whatever the descriptor still has.
     *
     * <p>The store is copied and reset before the descriptor is asked, so a {@code read()} that reaches a
     * closed descriptor with octets in hand reports the descriptor's refusal and not a sentence of this
     * layer's — and the octets it had are gone either way.
     */
    private byte[] readAll() {
        byte[] buffered = take(readahead());
        reset();
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        writeInto(answer, buffered);
        writeInto(answer, restartOfRaw());
        return answer.toByteArray();
    }

    /**
     * The descriptor's own {@code readall()}, or — for a raw object that has only {@code read}, as
     * tarfile's {@code _FileInFile} does — {@code read} in blockfuls until one comes back empty. That
     * second shape is what {@code _bufferedreader_read_all} falls back to.
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
            if (rest instanceof PyNone) {
                return new byte[0];
            }
            throw Slots.typeError("readall() should return bytes");
        }
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        while (true) {
            PyObject chunk = Slots.call(
                    Slots.getAttr(source, "read"), new PyObject[] {PyInt.of(BUFFER_SIZE)}, null);
            if (chunk instanceof PyNone) {
                return answer.toByteArray();
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
     * one fill per block, and it is skipped entirely below {@code BUFFER_SIZE} — where the store is emptied
     * into the answer, reset, and refilled until the count is reached. The octets beyond the count stay in
     * the store for the next row.
     */
    private byte[] readGeneric(long wanted) {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        writeInto(answer, take(readahead()));
        long remaining = wanted - answer.size();
        reset();
        while (remaining > 0 && (remaining & ~(long) (BUFFER_SIZE - 1)) != 0) {
            byte[] chunk = fetch(remaining & ~(long) (BUFFER_SIZE - 1));
            if (chunk.length == 0) {
                return answer.toByteArray();
            }
            writeInto(answer, chunk);
            remaining -= chunk.length;
        }
        pos = 0;
        readEnd = 0;
        while (remaining > 0 && readEnd < BUFFER_SIZE) {
            int got = fill();
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
        return answer.toByteArray();
    }

    /** {@code buffer.read1(n)}: the store if it holds anything, and one read of the descriptor if not. */
    private PyObject read1(long wanted) {
        requireReadable("read1");
        held();
        // A negative count is one storeful, and the answer to a dry store is that one read whatever the
        // count was, so what a read1 fetches beyond its own answer is lost rather than buffered.
        long limit = wanted < 0 ? BUFFER_SIZE : wanted;
        checkClosed(READ);
        if (limit == 0) {
            return PyBytes.of(new byte[0]);
        }
        long have = readahead();
        if (have > 0) {
            return PyBytes.of(take(Math.min(have, limit)));
        }
        reset();
        return PyBytes.of(fetch(limit));
    }

    /** {@code buffer.peek(n)}: the whole store, which the count never limits. */
    private PyObject peek() {
        requireReadable("peek");
        held();
        checkClosed(PEEK);
        if (readahead() > 0) {
            return PyBytes.of(heldOctets());
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

    /** {@code buffer.close()}: nothing to do once the descriptor says it is closed, and both closed below. */
    @Override
    public PyObject close() {
        held();
        if (Slots.truth(Slots.getAttr(descriptor, "closed"))) {
            return PyNone.INSTANCE;
        }
        Slots.call(Slots.getAttr(this, "flush"), new PyObject[0], null);
        PyObject answer = ask("close");
        pos = 0;
        readEnd = 0;
        return answer;
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
        flushOutgoing();
        return Slots.call(Slots.getAttr(descriptor, "truncate"), args, null);
    }

    /**
     * {@code buffer.write(data)}: the octets are taken first, and the store hands them over in blocks.
     *
     * <p>The count is the octets accepted, not the ones written: anything below one block stays here
     * until a flush, a blockful, or a close. A write also drops whatever the store was holding for a
     * read, because a {@code '+'} stream may not answer from a buffer the write has just overtaken.
     */
    private PyObject write(PyObject data) {
        if (!writable) {
            throw Slots.unsupportedOperation("write");
        }
        byte[] octets = PyFileIO.octetsToWrite(data);
        held();
        checkClosed("write to closed file");
        pos = 0;
        readEnd = -1;
        if (outgoing.size() + octets.length >= BUFFER_SIZE) {
            flushOutgoing();
            if (octets.length >= BUFFER_SIZE) {
                emit(octets);
                return PyInt.of(octets.length);
            }
        }
        outgoing.write(octets, 0, octets.length);
        return PyInt.of(octets.length);
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
        long offset = Numbers.cLong(args[0]);
        if (writable) {
            flushOutgoing();
        }
        if (whence == 1) {
            long rawPosition = Numbers.cLong(ask("tell"));
            long target = rawPosition - readahead() + offset;
            if (readEnd >= 0 && target >= rawPosition - readahead() && target <= rawPosition) {
                pos = (int) (readEnd - (rawPosition - target));
                return PyInt.of(target);
            }
            PyObject answer = Slots.call(
                    Slots.getAttr(descriptor, "seek"), new PyObject[] {PyInt.of(target)}, null);
            reset();
            return answer;
        }
        PyObject answer = Slots.call(
                Slots.getAttr(descriptor, "seek"),
                new PyObject[] {args[0], PyInt.of(whence)},
                null);
        reset();
        return answer;
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
            // The two fill rows of this layer: the buffer protocol they ask for is later M3 work, so what
            // is filed here is the refusal the converter makes before any body has run.
            .add("readinto", (self, args, kwNames) -> {
                Arguments.exactlyOne("BufferedReader.readinto()", args, kwNames);
                throw Slots.typeError(
                        "readinto() argument must be read-write bytes-like object, not %s",
                        Slots.argumentName(args[0]));
            })
            .add("readinto1", (self, args, kwNames) -> {
                Arguments.exactlyOne("BufferedReader.readinto1()", args, kwNames);
                throw Slots.typeError(
                        "readinto1() argument must be read-write bytes-like object, not %s",
                        Slots.argumentName(args[0]));
            })
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
                if (buffer.writable) {
                    buffer.flushOutgoing();
                }
                // The descriptor's pointer is past the octets the store has already read ahead of,
                // so the position this layer reports is that pointer less the unread tail.
                return PyInt.of(Numbers.cLong(buffer.ask("tell")) - buffer.readahead());
            })
            // BufferedReader's `flush` is `_io._Buffered.flush` filed as the simple one: CHECK_INITIALIZED
            // and then nothing but the descriptor's row — including the full stop it refuses a closed
            // descriptor with, which is the sentence here rather than the `flush of closed file` the
            // writer's version of the row says.
            .addNoArgument("flush", self -> {
                PyBufferedReader buffer = (PyBufferedReader) self;
                buffer.held();
                if (buffer.writable) {
                    buffer.checkClosed("flush of closed file");
                    buffer.flushOutgoing();
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
