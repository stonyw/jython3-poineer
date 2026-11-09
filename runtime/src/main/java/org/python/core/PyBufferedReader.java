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

    private final PyFileIO descriptor;

    /** {@code self->buffer}, with {@code self->pos} into it and {@code self->read_end} at its far edge. */
    private final byte[] store = new byte[BUFFER_SIZE];
    private int pos;
    private int readEnd = -1;

    /** Whether {@code detach()} has taken the descriptor away, which is the first question of every row. */
    private boolean detached;

    PyBufferedReader(PyFileIO raw) {
        super(PyType.BUFFERED_READER);
        this.descriptor = raw;
    }

    /**
     * The descriptor, whether or not it is still held: what {@code sys.stdin} caches as its own answer to
     * being closed, exactly as {@code _textiowrapper_set_buffer} caches the one this layer reports.
     */
    PyFileIO descriptor() {
        return descriptor;
    }

    /** The descriptor, or the refusal that says there no longer is one. */
    private PyFileIO held() {
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

    /**
     * {@code _bufferedreader_fill_buffer}: one read of the descriptor into the tail of the store.
     *
     * <p>A dry descriptor leaves the cursors where they were, which is how a reset store stays reset
     * through a {@code peek} that had to ask and got nothing.
     *
     * @return how many octets came, and 0 at the end of the descriptor
     */
    private int fill() {
        int start = readEnd < 0 ? 0 : readEnd;
        byte[] chunk = held().octets(BUFFER_SIZE - start);
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
        PyObject rest = ask("readall");
        if (rest instanceof PyBytes tail) {
            writeInto(answer, tail.getValue());
        } else if (!(rest instanceof PyNone)) {
            throw Slots.typeError("readall() should return bytes");
        }
        return answer.toByteArray();
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
            byte[] chunk = held().octets(remaining & ~(long) (BUFFER_SIZE - 1));
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
        return PyBytes.of(held().octets(limit));
    }

    /** {@code buffer.peek(n)}: the whole store, which the count never limits. */
    private PyObject peek() {
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
        checkClosed(TRUNCATE);
        throw Slots.unsupportedOperation("truncate");
    }

    /**
     * {@code buffer.seek(cookie, whence)}: the whence is checked before the stream is even looked at.
     *
     * <p>The range is the one {@code _io._Buffered.seek} allows rather than the pair the operating system
     * answers, so {@code seek(0, 9)} is {@code whence value 9 unsupported} here and {@code [Errno 29]
     * Illegal seek} on the descriptor below. macOS files {@code SEEK_DATA} and {@code SEEK_HOLE} beside the
     * three the language names, and the row lets all five through to the seekable question — which, for a
     * pipe, is the last thing this row ever asks.
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
        Numbers.cLong(args[0]);
        PyObject answer =
                Slots.call(
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

    private static final Members METHODS = Members.of(PyType.BUFFERED_READER)
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
            // A reader files no write rows of its own, so these two are `_IOBase`'s — and `write` among
            // them refuses by the direction of the layer with no closed guard in front of it.
            .add("write", (self, args, kwNames) -> {
                Arguments.onlyPositional("write", args, kwNames, 1);
                throw Slots.unsupportedOperation("write");
            })
            .add("writelines", (self, args, kwNames) -> ((PyIOStream) self).writeLines(args, kwNames))
            .add("truncate", (self, args, kwNames) ->
                    ((PyBufferedReader) self).truncate(args, kwNames))
            .add("seek", (self, args, kwNames) ->
                    ((PyBufferedReader) self).seek(args, kwNames))
            .addNoArgument("tell", self -> {
                ((PyBufferedReader) self).held();
                return ((PyBufferedReader) self).ask("tell");
            })
            // BufferedReader's `flush` is `_io._Buffered.flush` filed as the simple one: CHECK_INITIALIZED
            // and then nothing but the descriptor's row — including the full stop it refuses a closed
            // descriptor with, which is the sentence here rather than the `flush of closed file` the
            // writer's version of the row says.
            .addNoArgument("flush", self -> {
                ((PyBufferedReader) self).held();
                return ((PyBufferedReader) self).ask("flush");
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
                return PyBool.FALSE;
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
            .add("__exit__", (self, args, kwNames) -> ((PyIOStream) self).exit(args));

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
        PyObject name;
        try {
            name = Slots.getAttr(this, "name");
        } catch (PyRaise refused) {
            if (refused.value().getType() != ExceptionTypes.VALUE_ERROR
                    && refused.value().getType() != ExceptionTypes.ATTRIBUTE_ERROR) {
                throw refused;
            }
            return "<_io.BufferedReader>";
        }
        return "<_io.BufferedReader name=" + Slots.repr(name) + ">";
    }
}
