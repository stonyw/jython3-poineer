package org.python.core;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code sys.stdin}, {@code sys.stdout} and {@code sys.stderr}: a text stream over a Java file
 * descriptor.
 *
 * CPython builds these in {@code _io}, and only their being reachable from {@code print}, from {@code
 * input} and from {@code sys} matters here. What does matter is that both paths write through this one
 * object: a script that interleaves {@code print} with {@code sys.stdout.write} has to see one buffer,
 * not two that flush at different times.
 *
 * <p>The stream re-encodes as UTF-8 whatever it is given, which is why {@code encoding} reads
 * {@code 'utf-8'} rather than echoing the JVM's {@code stdout.encoding}. The two agree under the
 * build's {@code -Dfile.encoding=UTF-8}, and disagreeing is visible rather than silent.
 *
 * <p>One class serves both directions, and which way it goes decides the rest of the face: the members
 * of the other direction are refused. The reference refuses them as {@code io.UnsupportedOperation},
 * which is what they arrive as here too — the type is built in {@link ExceptionTypes}, and the
 * sentences are the ones {@code _io} writes: {@code not readable} and {@code not writable} from the
 * stream's own methods, {@code File or stream is not readable.} and its two neighbours from the
 * {@code _check} questions a subclass asks.
 *
 * <p>Reading is the transcription of {@code textio.c}: a chunk of octets at a time, decoded through an
 * incremental decoder that keeps the half character it cannot finish for the next chunk, and a cursor
 * into the text that comes out. {@code sys.stdin} is opened with {@code newline='\n'}, so nothing is
 * translated and only a '\n' ends a line — a '\r' is ordinary text, which is why {@code
 * input('a\rb')} answers {@code 'a\rb'} and not {@code 'a'}. Every answer is the same whether the
 * descriptor is a pipe or a file, because the loops reassemble what the reads hand out; only the
 * position questions below notice the difference.
 *
 * <p>{@code reconfigure(newline=...)} is what moves the stream off that opening setting, and it is the
 * only way, because the reference locks the three fields while there is decoded text the program has not
 * taken: the guard is the decoded buffer rather than a "has ever read" flag, which is why {@code read(0)}
 * leaves the stream reconfigurable, and why a stream drained to the end of its descriptor can be
 * reconfigured again. The four values mean what {@code textio.c} says they mean. {@code None}
 * translates both '\r' and '\r\n' to '\n' as the text arrives; {@code ''} also recognises all three
 * endings but hands them back as they were spelled; {@code '\n'}, {@code '\r'} and {@code '\r\n'}
 * recognise only themselves and translate nothing. A '\r' that ends a chunk is held back rather than
 * guessed at, because the octet after it decides whether the ending is '\r' or '\r\n', and the same pass
 * fills in {@code newlines}: the endings a universal mode has met, reported in the reference's fixed order
 * rather than in the order they arrived.
 *
 * <p>Under a reading stream are the two layers the reference puts there: {@code sys.stdin.buffer} is a
 * {@link PyBufferedReader} over a {@link PyFileIO}, and this stream asks them rather than the descriptor.
 * The chunk loop goes through {@code buffer.read1} and a whole read through {@code buffer.read}, so the
 * octets arrive a storeful at a time rather than a request at a time; {@code readable}, {@code writable},
 * {@code seekable}, {@code isatty}, {@code fileno}, {@code flush}, {@code close}, {@code name} and
 * {@code closed} all forward, and the refusals they pick up on the way down — the descriptor's {@code
 * raw stream has been detached}, the {@code I/O operation on closed file} — are the sentences this stream
 * answers with. {@code seekable} is also remembered, because {@code seek} and {@code tell} consult the
 * memory rather than the buffer: on the pipe this runtime reads from it is False, so they refuse
 * {@code underlying stream is not seekable} even after the buffer below has been detached.
 *
 * <p>{@code _io._IOBase} gives every stream a dictionary of its own, and the interpreter's stream
 * creation files {@code mode} in it before the object reaches {@code sys}. That is where the stream's
 * {@code mode} really lives: the type declares no member of that name, so {@code s.mode} is an ordinary
 * attribute a program can rewrite, and the repr reads it back. The members the type does declare refuse
 * a write in two sentences, and which one a name gets is decided by the row {@code _io} files for it — a
 * row this runtime does not have, so the refusals are declared here instead. See {@link #setAttr}.
 */
public final class PyStream extends PyIterator {

    /**
     * {@code type(sys.stdin)}: the top of the chain {@code _io} files, and the only one of its six classes
     * a program meets without naming it.
     *
     * <p>It is declared over {@link PyType#TEXT_IO_BASE} rather than born at {@code object}, which is what
     * gives {@code TextIOWrapper.__mro__} its four names and {@code isinstance(sys.stdin, IOBase)} its
     * answer. The rows the two base classes hold are in {@link IoBases}, and the ones here are only the
     * wrapper's own.
     */
    static final PyType TYPE =
            PyType.declare("TextIOWrapper", PyType.TEXT_IO_BASE)
                    .filedUnder("_io.TextIOWrapper")
                    .documented(PyType.TEXTIOWRAPPER_DOC);

    /** What {@code _io} says when a method of the stream itself meets a closed descriptor. */
    private static final String CLOSED = "I/O operation on closed file.";

    /** What the {@code io} module's own getters say, which is the same sentence with no full stop. */
    private static final String CLOSED_GETTER = "I/O operation on closed file";

    /** The rows {@code _io} files as getsets with no setter, which refuse in the first sentence. */
    private static final Set<String> NOT_WRITABLE =
            Set.of("name", "closed", "errors", "newlines", "buffer");

    /** The rows {@code _io} files as {@code Py_READONLY} members, which refuse by the field alone. */
    private static final Set<String> READ_ONLY = Set.of("encoding", "line_buffering", "write_through");

    /** What {@code CHECK_ATTACHED} says once the stream has handed its buffer over. */
    private static final String DETACHED = "underlying buffer has been detached";

    /** {@code _io.TextIOWrapper_reconfigure}'s sentence, which is the one row of the type with a doc. */
    private static final String RECONFIGURE_DOC =
            "Reconfigure the text stream with new parameters.\n\nThis also does an implicit stream flush.";

    /**
     * The two sentences {@code _io.TextIOWrapper} files over {@code seek} and {@code tell}.
     *
     * <p>The pairs are the type's own, not the ones {@code _IOBase} writes: this layer is the one that
     * restricts a cookie to what a previous {@code tell} answered, which is what the four combinations
     * below describe. They are the only two rows of the book whose sentence is longer than one line, and
     * the reference keeps the clinic table's wrapping word for word.
     */
    private static final String SEEK_DOC =
            "Set the stream position, and return the new stream position.\n\n  cookie\n    Zero or an"
                    + " opaque number returned by tell().\n  whence\n    The relative position to seek"
                    + " from.\n\nFour operations are supported, given by the following argument\n"
                    + "combinations:\n\n- seek(0, SEEK_SET): Rewind to the start of the stream.\n-"
                    + " seek(cookie, SEEK_SET): Restore a previous position;\n  'cookie' must be a number"
                    + " returned by tell().\n- seek(0, SEEK_END): Fast-forward to the end of the"
                    + " stream.\n- seek(0, SEEK_CUR): Leave the current stream position unchanged.\n\nAny"
                    + " other argument combinations are invalid,\nand may raise exceptions.";

    private static final String TELL_DOC =
            "Return the stream position as an opaque number.\n\nThe return value of tell() can be given"
                    + " as input to seek(), to\nrestore a previous stream position.";

    private final String name;
    private final PrintStream out;

    /** The two layers under a reading stream, and nothing at all under a writing one. */
    private final PyBufferedReader buffer;

    /** {@code self->raw}: the descriptor the buffer rests on, cached to ask it about closedness. */
    private final PyFileIO raw;

    /** {@code self->encoding}: reported as it was handed over, which is why {@code reconfigure} keeps its spelling. */
    private String encoding = "utf-8";

    /** {@code self->errors}: the handler name, kept as handed over and never looked up here. */
    private String errors;

    /** {@code self->line_buffering} and {@code self->write_through}: what the two rows report. */
    private boolean lineBuffered;
    private boolean writeThrough;

    /** {@code self->finalizing}: the {@code T_BOOL} member a program may write. */
    private boolean finalizing;

    private final int fd;

    /** {@code _IOBase.__dict__}: the one dictionary this stream's own attributes are kept in. */
    private final PyDict dictionary = PyDict.empty();

    /** {@code self->detached}: whether this stream has handed its buffer over. */
    private boolean detached;

    /** The writing stream's own flag, which is what it has instead of the descriptor's. */
    private boolean closed;

    /** {@code _CHUNK_SIZE}: how many octets a read of the descriptor is worth asking for. */
    private long chunkSize = 8192;

    /** {@code decoded_chars}: the text the last chunk produced, and {@code decoded_chars_used} into it. */
    private String pending = "";
    private int pendingUsed;

    /** The octets the incremental decoder could not finish, kept for the chunk that follows. */
    private byte[] carried = new byte[0];

    /** {@code b2cratio}: what the last chunk's octets cost per character, which sizes the next request. */
    private double bytesPerChar;

    /**
     * {@code self->readnl}: which ending ends a line. Null is the universal mode that translates, the empty
     * string the universal mode that keeps what it was given, and anything else is the one ending the
     * stream was opened with — {@code sys.stdin} starts with {@code "\n"}.
     */
    private String newline = "\n";

    /** {@code self->seen_newlines}: one bit per ending met in a universal mode, in the reference's order. */
    private int seenNewlines;

    /**
     * Whether the stream holds decoded characters the program has not taken yet — the reference's
     * {@code decoded_chars != NULL}, which is the condition {@code reconfigure} asks about.
     *
     * <p>It is not a "has ever read" flag: the reference creates the buffer with the first chunk and
     * throws it away the moment a read finds the end of the descriptor, so a stream drained to EOF can be
     * reconfigured again. Reading nothing at all ({@code read(0)}) never creates it either.
     */
    private boolean hasDecoded;

    /** A '\r' the last chunk ended on, held back because only the next character can tell what it is. */
    private boolean carriedReturn;

    /**
     * @param autoFlush whether the descriptor is drained on every newline, which is what a terminal
     *     gets and a pipe does not
     */
    PyStream(String name, PrintStream target, int fd, String errors, boolean autoFlush) {
        super(TYPE);
        this.name = name;
        this.out = new PrintStream(target, autoFlush, StandardCharsets.UTF_8);
        this.buffer = null;
        this.raw = null;
        this.errors = errors;
        this.lineBuffered = autoFlush;
        this.fd = fd;
        declareMode("w");
    }

    /** {@code sys.stdin}: the same descriptor type, reading, over the two layers it asks. */
    PyStream(String name, InputStream source, int fd, String errors) {
        super(TYPE);
        this.name = name;
        this.out = null;
        this.buffer = new PyBufferedReader(new PyFileIO(name, source, fd, false));
        // `_textiowrapper_detect_buffer_attributes` caches the descriptor as the answer to `closed`, and
        // keeps answering from the cache after the buffer has been detached from it.
        this.raw = this.buffer.descriptor();
        this.errors = errors;
        this.lineBuffered = false;
        this.fd = fd;
        declareMode("r");
    }

    /** The first entry the stream's dictionary is made with, which is where {@code s.mode} is read from. */
    private void declareMode(String mode) {
        dictionary.put(PyStr.interned("mode"), PyStr.of(mode));
    }

    /** Write from Java, the way {@code print} does, without going through the {@code write} slot. */
    public void writeText(String text) {
        if (closed) {
            throw Slots.valueError(CLOSED);
        }
        out.print(text);
    }

    public void flush() {
        if (!reading()) {
            out.flush();
        }
    }

    private boolean reading() {
        return buffer != null;
    }

    /** {@code buffer_access_safe} and {@code CHECK_ATTACHED}: the buffer, if this stream still has one. */
    private PyBufferedReader attached() {
        if (detached) {
            throw Slots.valueError(DETACHED);
        }
        return buffer;
    }

    /** {@code buffer_getattr} and {@code buffer_callmethod_*}: the question asked of the buffer. */
    private PyObject askBuffer(String row, PyObject[] args) {
        return Slots.call(Slots.getAttr(attached(), row), args, null);
    }

    /** {@code _PyFile_Flush(self)}: this stream's own {@code flush} row, reached through Python. */
    private void flushSelf() {
        Slots.call(Slots.getAttr(this, "flush"), new PyObject[0], null);
    }

    /**
     * {@code reconfigure}: what a program says about the stream after it was opened.
     *
     * <p>The five keywords are clinic's, in clinic's order, and they are all optional; none of them is a
     * positional argument, which is a sentence of its own rather than "too many arguments". The types are
     * checked as the arguments are unpacked — the three strings are {@code str or None}, the two flags are
     * counted as integers with {@code None} left meaning "keep" — and the newline is only checked once all
     * five conversions are done, which is the order the reference's single {@code PyArg_ParseTupleAndKeywords}
     * gives. What the newline may be is the four values {@code textio.c} accepts, and anything else is the
     * {@code ValueError} it raises.
     *
     * <p>The guard that comes before all of that is the decoded buffer: while the stream holds text the
     * program has not taken, the three fields the decoder works from cannot move, and the reference says so
     * in one sentence whatever the value was — the same sentence for an identical encoding, for an explicit
     * {@code None}, and for a newline that is not legal at all. The two flags and a call with no keywords
     * are still allowed, and so is {@code reconfigure(encoding=None)}: a value that is not given is not a
     * change. Draining the descriptor drops the buffer, so the lock is a property of what is held rather
     * than of what has happened.
     *
     * <p>What follows is {@code CHECK_ATTACHED}, then {@code CHECK_CLOSED}, then the codec lookup — the
     * reference asks the registry for the name but stores the name the program gave, so {@code
     * encoding='LATIN-1'} reads back in that spelling — and finally the flush that is the second half of the
     * sentence in {@link #RECONFIGURE_DOC}. Only after the flush are the fields written.
     */
    private PyObject reconfigure(PyObject[] args, String[] kwNames) {
        PyObject[] values =
                Arguments.unpackKeywords(
                        "reconfigure",
                        args,
                        kwNames,
                        new String[] {"encoding", "errors", "newline", "line_buffering", "write_through"},
                        0,
                        0);
        String newEncoding = optionalText(values[0], "encoding");
        String newErrors = optionalText(values[1], "errors");
        String newNewline = optionalText(values[2], "newline");
        boolean newlineGiven = values[2] != null;
        Boolean newLineBuffering = optionalCount(values[3]);
        Boolean newWriteThrough = optionalCount(values[4]);
        if (hasDecoded && (newEncoding != null || newErrors != null || newlineGiven)) {
            throw Slots.unsupportedOperation(
                    "It is not possible to set the encoding or newline of stream after the first read");
        }
        if (newlineGiven
                && newNewline != null
                && !newNewline.isEmpty()
                && !newNewline.equals("\n")
                && !newNewline.equals("\r")
                && !newNewline.equals("\r\n")) {
            throw Slots.valueError("illegal newline value: %s", newNewline);
        }
        checkAttached();
        checkOpen();
        if (newEncoding != null) {
            // The lookup is all this runtime keeps of the new codec: the decoder underneath is fixed, so a
            // name that resolves changes what is reported and not what is read. See PLAN-progress.md.
            Encoder.codec(newEncoding);
        }
        flushSelf();
        if (newEncoding != null) {
            encoding = newEncoding;
        }
        if (newErrors != null) {
            errors = newErrors;
        }
        if (newlineGiven) {
            newline = newNewline;
        }
        if (newLineBuffering != null) {
            lineBuffered = newLineBuffering;
        }
        if (newWriteThrough != null) {
            writeThrough = newWriteThrough;
        }
        return PyNone.INSTANCE;
    }

    /** One of {@code reconfigure}'s three string keywords, or null where the program passed none or None. */
    private static String optionalText(PyObject value, String parameter) {
        if (value == null || value == PyNone.INSTANCE) {
            return null;
        }
        if (!(value instanceof PyStr text)) {
            throw Slots.typeError(
                    "reconfigure() argument '%s' must be str or None, not %s",
                    parameter, Slots.argumentName(value));
        }
        return text.getValue();
    }

    /** One of the two flag keywords, counted the way {@code PyArg_ParseTupleAndKeywords}'s {@code p} does. */
    private static Boolean optionalCount(PyObject value) {
        if (value == null || value == PyNone.INSTANCE) {
            return null;
        }
        return Numbers.count(value) != 0;
    }

    /**
     * {@code CHECK_CLOSED}'s question, which is the shortcut of {@code _PyIOBase_check_closed}: the
     * descriptor cached out of {@code buffer.raw}, and not the buffer's answer.
     *
     * <p>The two differ in one direction only. A buffer that has been detached from its own descriptor
     * still leaves this stream asking the descriptor, so {@code tell} and {@code seek} reach the memory
     * below rather than the refusal of the layer between.
     */
    private boolean isClosed() {
        return reading() ? raw.closedFlag() : closed;
    }

    /** {@code _PyIOBase_check_closed}: the derived attribute, which is the buffer's answer. */
    private void checkVirtual() {
        if (Slots.truth(closedValue())) {
            throw Slots.valueError(CLOSED);
        }
    }

    /** What reading {@code s.closed} answers, from whichever layer the reference reads it. */
    private PyObject closedValue() {
        return reading() ? Slots.getAttr(attached(), "closed") : PyBool.of(closed);
    }

    /** {@code CHECK_CLOSED}, the guard every method of the stream itself runs first. */
    private void checkOpen() {
        if (isClosed()) {
            throw Slots.valueError(CLOSED);
        }
    }

    /** The same guard as the rows that refuse with no full stop, which is the writing stream's own. */
    private void checkGetter() {
        if (isClosed()) {
            throw Slots.valueError(CLOSED_GETTER);
        }
    }

    /** The guard of the reading methods: attached, then open, then the direction. */
    private void checkReadable() {
        checkAttached();
        checkOpen();
        if (!reading()) {
            throw Slots.unsupportedOperation("not readable");
        }
    }

    /** {@code CHECK_ATTACHED}, the guard of every row that reaches the text or the buffer at all. */
    private void checkAttached() {
        if (detached) {
            throw Slots.valueError(DETACHED);
        }
    }

    /**
     * {@code _PyIOBase_check_readable} and its two neighbours: ask the stream, and refuse unless the
     * answer is exactly True.
     *
     * <p>Three of these rows are the reason the sentences come in pairs. Each one calls the stream's own
     * question — {@code readable}, {@code seekable}, {@code writable} — so a closed stream is refused by
     * that question's getter sentence rather than by this row's, and a stream that simply answers False
     * is refused by {@code File or stream is not readable.} beside it. The answer the question gave is
     * what the row hands back, which is why {@code sys.stdin._checkReadable()} is True and not None.
     */
    private PyObject checked(
            String row, String refusal, PyObject[] args, String[] kwNames, Supplier<PyObject> ask) {
        Arguments.none("_IOBase." + row + "()", args, kwNames);
        PyObject answer = ask.get();
        if (answer != PyBool.TRUE) {
            throw Slots.unsupportedOperation("File or stream is %s.", refusal);
        }
        return answer;
    }

    /** {@code s.write(text)}, which answers with how many characters it took. */
    private PyObject write(PyObject text) {
        // The conversion is clinic's, and it runs before the impl body — so a value that is not text is
        // refused even by a stream that has been closed or opened the other way round.
        String value = Numbers.asText(text);
        if (value == null) {
            throw Slots.typeError(
                    "write() argument must be str, not %s", Slots.argumentName(text));
        }
        checkAttached();
        checkOpen();
        if (reading()) {
            throw Slots.unsupportedOperation("not writable");
        }
        writeText(value);
        return PyInt.of(characters(value));
    }

    /**
     * {@code s.read(n)}: the next {@code n} characters, or the rest of the descriptor.
     *
     * <p>The reference concatenates what is already decoded with as many chunks as the request needs, and
     * counts the request in characters rather than in octets. A negative request is the one branch that
     * reads the descriptor whole, because the buffer's own {@code read()} answers the rest at once;
     * anything left over from the last chunk still goes in front of it.
     */
    private PyObject read(long wanted) {
        checkReadable();
        if (wanted < 0) {
            String rest = take(-1) + settle(decode(readAllOctets(), true), true);
            // The whole read runs the descriptor dry, and the reference drops the decoded buffer at the
            // end of it, which is why the stream is reconfigurable again afterwards.
            hasDecoded = false;
            return PyStr.of(rest);
        }
        StringBuilder answer = new StringBuilder();
        long remaining = wanted;
        String first = take(wanted);
        remaining -= characters(first);
        answer.append(first);
        while (remaining > 0) {
            if (!readChunk(remaining)) {
                break;
            }
            String next = take(remaining);
            remaining -= characters(next);
            answer.append(next);
        }
        return PyStr.of(answer.toString());
    }

    /**
     * {@code s.readlines(hint)}: the lines that make up {@code hint} characters, and the line that
     * overshoots with them.
     *
     * <p>The row is {@code _io._IOBase}'s and its loop is {@code extend(self)} — this stream's own
     * cursor, closed or not. A hint that is zero or negative is the whole descriptor; otherwise each
     * line is measured and the loop leaves after the one that no longer fits, which is why a hint of
     * five over lines of five, five, twelve and four characters gives two lines and not one.
     */
    private PyObject readlines(long hint) {
        PyList answer = PyList.empty();
        PyIterator lines = Slots.iterator(this);
        if (hint <= 0) {
            PyObject line;
            while ((line = lines.nextOrNull()) != null) {
                answer.add(line);
            }
            return answer;
        }
        long length = 0;
        while (true) {
            PyObject line = lines.nextOrNull();
            if (line == null) {
                return answer;
            }
            answer.add(line);
            long size = characters(((PyStr) line).getValue());
            if (size > hint - length) {
                return answer;
            }
            length += size;
        }
    }

    /**
     * {@code s.readline(n)} and the step of this stream's iteration: one line, with its terminator, and
     * the empty text at the end of the descriptor.
     *
     * <p>Transcribed from {@code _textiowrapper_readline}. The line is built from as many chunks as it
     * takes, and a limit is arithmetic over both: it is compared against the terminator it has found
     * before the chunk is cut, so a limit that lands inside a line ends the line there and leaves the
     * rest — terminator included — for the next read of the same line.
     *
     * <p>The terminator is whatever {@code readnl} says it is, so a stream opened with {@code newline='\n'}
     * — which is how {@code sys.stdin} starts — ends only on a '\n' and treats a '\r' as ordinary text,
     * while a universal mode ends on any of the three. The chunk has already been settled by the time the
     * scan runs, so a '\r\n' cut between two chunks has been put back together here.
     */
    private String readlineText(long limit) {
        checkReadable();
        StringBuilder line = new StringBuilder();
        long chunked = 0;
        while (true) {
            boolean more = true;
            while (pending.isEmpty()) {
                more = readChunk(0);
                if (!more) {
                    break;
                }
            }
            if (!more) {
                replacePending("");
                return line.toString();
            }
            int start = pendingUsed;
            int end = terminator(start);
            if (end >= 0) {
                if (limit >= 0 && end - start + chunked >= limit) {
                    end = start + (int) (limit - chunked);
                }
                pendingUsed = end;
                return line.append(cut(pending, start, end)).toString();
            }
            end = characters(pending);
            if (limit >= 0 && end - start + chunked >= limit) {
                pendingUsed = start + (int) (limit - chunked);
                return line.append(cut(pending, start, pendingUsed)).toString();
            }
            if (end > start) {
                line.append(cut(pending, start, end));
                chunked += end - start;
            }
            replacePending("");
        }
    }

    /**
     * {@code read_chunk}: one read of the descriptor, decoded, and whether it is worth calling again.
     *
     * <p>The size hint is a count of characters and the request it sizes is in octets, which is why the
     * ratio of the last chunk multiplies it — and why a chunk of ASCII asks for what it needs while a
     * chunk of a multi-byte encoding asks for the bytes its characters really cost. The answer is false
     * only when the descriptor ran out and nothing came of it, which is what ends the loops above.
     */
    private boolean readChunk(long sizeHint) {
        long wanted = sizeHint > 0 ? (long) (Math.max(bytesPerChar, 1.0) * sizeHint) : 0;
        byte[] octets = readOctets(Math.max(chunkSize, wanted));
        boolean atEnd = octets.length == 0;
        hasDecoded = !atEnd;
        String text = settle(decode(octets, atEnd), atEnd);
        int characters = characters(text);
        bytesPerChar = characters > 0 ? (double) octets.length / characters : 0.0;
        replacePending(text);
        return !(atEnd && characters == 0);
    }

    /** Whether this stream is in one of the two universal modes, which are the ones that look at endings. */
    private boolean universal() {
        return newline == null || newline.isEmpty();
    }

    /**
     * {@code translate_newlines}: the chunk as {@code readnl} says it should be read.
     *
     * <p>A chunk that ends on a '\r' is not translated yet: the character after it decides whether the
     * ending is a lone '\r' or the first half of a '\r\n', and that character is in the next chunk. The
     * held '\r' is put in front of it on arrival, which is why the pair is only ever looked at whole.
     * Nothing is held back at the end of the descriptor — there is no next character, so the '\r' is an
     * ending of its own.
     */
    private String settle(String text, boolean atEnd) {
        if (holdsReturn()) {
            if (carriedReturn) {
                text = "\r" + text;
                carriedReturn = false;
            }
            if (!atEnd && text.endsWith("\r")) {
                text = text.substring(0, text.length() - 1);
                carriedReturn = true;
            }
        }
        if (!universal()) {
            return text;
        }
        noteEndings(text);
        return newline == null ? text.replace("\r\n", "\n").replace('\r', '\n') : text;
    }

    /**
     * Whether a chunk's closing '\r' has to wait for the character after it.
     *
     * <p>The two universal modes hold it because the decoder they run through does; the {@code "\r\n"}
     * mode holds it because the line scan refuses to take a '\r' it cannot pair, which is the same answer
     * arrived at from the other side. The single-character modes and the default {@code "\n"} never need
     * to: a '\r' is text to them, and where it is cannot change what ends a line.
     */
    private boolean holdsReturn() {
        return universal() || "\r\n".equals(newline);
    }

    /** Fills in {@code seen_newlines} from the chunk, before {@code None} is allowed to rewrite it. */
    private void noteEndings(String text) {
        for (int at = 0; at < text.length(); at++) {
            char unit = text.charAt(at);
            if (unit == '\r') {
                if (at + 1 < text.length() && text.charAt(at + 1) == '\n') {
                    seenNewlines |= 4;
                    at++;
                } else {
                    seenNewlines |= 1;
                }
            } else if (unit == '\n') {
                seenNewlines |= 2;
            }
        }
    }

    /** {@code s.newlines}: the endings a universal mode has met, in the reference's fixed order. */
    private PyObject newlinesAnswer() {
        checkAttached();
        return switch (seenNewlines) {
            case 0 -> PyNone.INSTANCE;
            case 1 -> PyStr.of("\r");
            case 2 -> PyStr.of("\n");
            case 4 -> PyStr.of("\r\n");
            default -> {
                PyList found = PyList.empty();
                if ((seenNewlines & 1) != 0) {
                    found.add(PyStr.of("\r"));
                }
                if ((seenNewlines & 2) != 0) {
                    found.add(PyStr.of("\n"));
                }
                if ((seenNewlines & 4) != 0) {
                    found.add(PyStr.of("\r\n"));
                }
                yield PyTuple.of(found.getItems().toArray(new PyObject[0]));
            }
        };
    }

    /** {@code decoded_chars} and its cursor reset, which is what a consumed chunk leaves behind. */
    private void replacePending(String text) {
        pending = text;
        pendingUsed = 0;
    }

    /** {@code get_decoded_chars(n)}: the next {@code n} characters of the chunk, or all of what is left. */
    private String take(long wanted) {
        long available = characters(pending) - pendingUsed;
        int given = (int) (wanted < 0 || wanted > available ? available : wanted);
        String answer = cut(pending, pendingUsed, pendingUsed + given);
        pendingUsed += given;
        return answer;
    }

    /**
     * Where the line ends in the current chunk, counted as the reference counts it: up to and including.
     *
     * <p>Which endings count is what {@code readnl} says. The two universal modes take all three, and a
     * '\r' that is followed by a '\n' is the longer ending rather than two.
     */
    private int terminator(int from) {
        int start = pending.offsetByCodePoints(0, from);
        if ("\r\n".equals(newline)) {
            int at = pending.indexOf("\r\n", start);
            return at < 0 ? -1 : pending.codePointCount(0, at + 2);
        }
        if (newline != null && !newline.isEmpty()) {
            int at = pending.indexOf(newline.charAt(0), start);
            return at < 0 ? -1 : pending.codePointCount(0, at + 1);
        }
        for (int at = start; at < pending.length(); at++) {
            char unit = pending.charAt(at);
            if (unit == '\n') {
                return pending.codePointCount(0, at + 1);
            }
            if (unit == '\r') {
                boolean paired = at + 1 < pending.length() && pending.charAt(at + 1) == '\n';
                return pending.codePointCount(0, paired ? at + 2 : at + 1);
            }
        }
        return -1;
    }

    /** {@code buffer.read1(n)}: the octets one chunk is built from, however the store holds them. */
    private byte[] readOctets(long wanted) {
        return octetsOf(askBuffer("read1", new PyObject[] {PyInt.of(wanted)}));
    }

    /** {@code buffer.read()}: the rest of the store and of the descriptor under it. */
    private byte[] readAllOctets() {
        return octetsOf(askBuffer("read", new PyObject[0]));
    }

    /** The one answer a chunk can be built from, which is the sentence the reference writes for any other. */
    private static byte[] octetsOf(PyObject answer) {
        if (!(answer instanceof PyBytes octets)) {
            throw Slots.osError("read() should return bytes");
        }
        return octets.getValue();
    }

    /**
     * The incremental decoder's {@code decode(octets, atEnd)}, over the octets it is still holding.
     *
     * <p>The half character a chunk ends on is not an error and is not thrown away: it is kept for the
     * next chunk, where the octets that complete it arrive. At the end of the descriptor there is no next
     * chunk, so the tail goes to the handler instead — which is why {@code b'caf\xc3'} reads as {@code
     * 'caf\udcc3'} under the surrogate escape {@code sys.stdin} is opened with.
     */
    private String decode(byte[] octets, boolean atEnd) {
        byte[] input = octets;
        if (carried.length != 0) {
            input = Arrays.copyOf(carried, carried.length + octets.length);
            System.arraycopy(octets, 0, input, carried.length, octets.length);
        }
        carried = new byte[0];
        if (atEnd) {
            return Decoder.decode(input, "utf-8", errors);
        }
        int held = unfinishedTail(input);
        if (held == 0) {
            return Decoder.decode(input, "utf-8", errors);
        }
        carried = Arrays.copyOfRange(input, input.length - held, input.length);
        return Decoder.decode(Arrays.copyOfRange(input, 0, input.length - held), "utf-8", errors);
    }

    /**
     * How many octets at the end of this run cannot be a character on their own.
     *
     * <p>The scan is the UTF-8 one: at most four octets, a continuation octet means the lead is further
     * back, and an ASCII octet ends the scan because whatever is before it is already whole. A lead that
     * cannot start a character — {@code 0xC0}, {@code 0xC1}, anything above {@code 0xF4} — reports
     * nothing held, because it is not an unfinished character but a broken one, and belongs to the
     * handler rather than to the next chunk.
     */
    private static int unfinishedTail(byte[] input) {
        for (int at = input.length - 1, seen = 1; at >= 0 && seen <= 4; at--, seen++) {
            int octet = input[at] & 0xff;
            if (octet < 0x80) {
                return 0;
            }
            if (octet < 0xC0) {
                continue;
            }
            int width = octet >= 0xF0 && octet <= 0xF4
                    ? 4
                    : octet >= 0xE0 && octet <= 0xEF ? 3 : octet >= 0xC2 && octet <= 0xDF ? 2 : 0;
            return seen < width ? seen : 0;
        }
        return 0;
    }

    /** How CPython counts a string's length: in characters, not in the UTF-16 units Java stores. */
    private static int characters(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String cut(String text, int from, int to) {
        int end = Math.max(from, to);
        return text.substring(text.offsetByCodePoints(0, from), text.offsetByCodePoints(0, end));
    }

    /**
     * {@code s.close()}: attached, then asked of the buffer whether there is anything left to close, then
     * the stream's own flush, then the buffer's.
     *
     * <p>Nothing here sets a flag of this stream's: a reading stream has no closedness of its own, and
     * after {@code s.detach()} the very first guard is the one that refuses. A writing stream keeps its
     * own flag because it has no layer under it to keep one for it.
     */
    private PyObject closeAnswer() {
        checkAttached();
        if (reading()) {
            if (Slots.truth(Slots.getAttr(attached(), "closed"))) {
                return PyNone.INSTANCE;
            }
            flushSelf();
            return askBuffer("close", new PyObject[0]);
        }
        if (closed) {
            return PyNone.INSTANCE;
        }
        closed = true;
        flush();
        return PyNone.INSTANCE;
    }

    /** {@code s.detach()}: the flush first, and then the buffer is no longer this stream's to ask. */
    private PyObject detachAnswer() {
        // `_PyFile_Flush(self)` runs before `buffer_access_safe`, so a buffer the program has detached
        // below this stream is refused by the flush's own question rather than by this row's.
        flushSelf();
        checkAttached();
        if (!reading()) {
            // A writing stream has no buffer of its own in this runtime to hand over: see the deviation
            // note in PLAN-progress.md.
            throw Slots.unsupportedOperation("detach");
        }
        detached = true;
        return buffer;
    }

    /** {@code readable}, which is the buffer's answer and never this stream's direction. */
    private PyObject readableAnswer() {
        if (reading()) {
            return askBuffer("readable", new PyObject[0]);
        }
        checkGetter();
        return PyBool.FALSE;
    }

    private PyObject writableAnswer() {
        if (reading()) {
            return askBuffer("writable", new PyObject[0]);
        }
        checkGetter();
        return PyBool.TRUE;
    }

    /** {@code seekable}, which is asked of the buffer and answered False by the descriptor below it. */
    private PyObject seekableAnswer() {
        if (reading()) {
            return askBuffer("seekable", new PyObject[0]);
        }
        checkGetter();
        return PyBool.FALSE;
    }

    /** {@code isatty}, which is the descriptor's answer about the terminal it was opened from. */
    private PyObject isattyAnswer() {
        if (reading()) {
            return askBuffer("isatty", new PyObject[0]);
        }
        checkGetter();
        return PyBool.of(isTty());
    }

    private PyObject filenoAnswer() {
        if (reading()) {
            return askBuffer("fileno", new PyObject[0]);
        }
        checkGetter();
        return PyInt.of(fd);
    }

    /**
     * {@code tell} and {@code seek}'s shared refusal, which comes out of the memory of the buffer's
     * {@code seekable} rather than out of the buffer: the two rows ask {@code self->seekable}, and this
     * stream is built on a pipe that answered False when it was asked.
     */
    private PyObject tellAnswer() {
        checkAttached();
        checkOpen();
        throw Slots.unsupportedOperation("underlying stream is not seekable");
    }

    private static final Members METHODS = Members.of(TYPE)
            .addOne("write", (self, text) -> ((PyStream) self).write(text))
            // The row is _IOBase's: it asks the derived `closed` attribute, and each line through the
            // `write` attribute, so either of the two may be a name the program filed itself.
            .add("writelines", (self, args, kwNames) -> {
                Arguments.exactlyOne("_IOBase.writelines()", args, kwNames);
                PyStream stream = (PyStream) self;
                stream.checkVirtual();
                PyIterator lines = Slots.iterator(args[0]);
                PyObject write = Slots.getAttr(self, "write");
                PyObject line;
                while ((line = lines.nextOrNull()) != null) {
                    Slots.call(write, new PyObject[] {line}, null);
                }
                return PyNone.INSTANCE;
            })
            .add("read", (self, args, kwNames) -> {
                Arguments.noKeywords("TextIOWrapper.read()", kwNames);
                Arguments.atMost("read", args, kwNames, 1);
                return ((PyStream) self)
                        .read(args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1));
            })
            .add("readline", (self, args, kwNames) -> {
                Arguments.noKeywords("TextIOWrapper.readline()", kwNames);
                Arguments.atMost("readline", args, kwNames, 1);
                return PyStr.of(
                        ((PyStream) self)
                                .readlineText(args.length == 0 ? -1 : Numbers.size(args[0])));
            })
            .add("readlines", (self, args, kwNames) -> {
                Arguments.noKeywords("_IOBase.readlines()", kwNames);
                Arguments.atMost("readlines", args, kwNames, 1);
                return ((PyStream) self)
                        .readlines(args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1));
            })
            .add("seek", SEEK_DOC, (self, args, kwNames) -> {
                Arguments.noKeywords("TextIOWrapper.seek()", kwNames);
                Arguments.between("seek", args, kwNames, 1, 2);
                PyStream stream = (PyStream) self;
                // The cookie is an object as far as this method is concerned — it only ever reaches a
                // seekable stream — but whence is converted, and clinic converts it before the body runs.
                if (args.length > 1) {
                    Numbers.size(args[1]);
                }
                return stream.tellAnswer();
            })
            .addNoArgument("tell", TELL_DOC, self -> ((PyStream) self).tellAnswer())
            .add("truncate", (self, args, kwNames) -> {
                Arguments.noKeywords("TextIOWrapper.truncate()", kwNames);
                Arguments.atMost("truncate", args, kwNames, 1);
                PyStream stream = (PyStream) self;
                // The position is passed on unconverted, because it only ever reaches a stream that can
                // truncate. What runs first is the flush this row does before asking the buffer.
                stream.checkAttached();
                stream.flushSelf();
                if (!stream.reading()) {
                    throw Slots.unsupportedOperation("truncate");
                }
                return stream.askBuffer(
                        "truncate", new PyObject[] {args.length == 0 ? PyNone.INSTANCE : args[0]});
            })
            .addNoArgument("flush", self -> {
                PyStream stream = (PyStream) self;
                stream.checkAttached();
                stream.checkOpen();
                if (stream.reading()) {
                    stream.askBuffer("flush", new PyObject[0]);
                } else {
                    stream.flush();
                }
                return PyNone.INSTANCE;
            })
            .addNoArgument("close", self -> ((PyStream) self).closeAnswer())
            .addNoArgument("detach", self -> ((PyStream) self).detachAnswer())
            .addNoArgument("isatty", self -> ((PyStream) self).isattyAnswer())
            .addNoArgument("writable", self -> ((PyStream) self).writableAnswer())
            .addNoArgument("readable", self -> ((PyStream) self).readableAnswer())
            .addNoArgument("seekable", self -> ((PyStream) self).seekableAnswer())
            .addNoArgument("fileno", self -> ((PyStream) self).filenoAnswer())
            .add(
                    "reconfigure",
                    RECONFIGURE_DOC,
                    (self, args, kwNames) -> ((PyStream) self).reconfigure(args, kwNames))
            .add("_checkClosed", (self, args, kwNames) -> {
                Arguments.none("_IOBase._checkClosed()", args, kwNames);
                ((PyStream) self).checkVirtual();
                return PyNone.INSTANCE;
            })
            .add(
                    "_checkReadable",
                    (self, args, kwNames) ->
                            ((PyStream) self)
                                    .checked(
                                            "_checkReadable",
                                            "not readable",
                                            args,
                                            kwNames,
                                            () -> ((PyStream) self).readableAnswer()))
            .add(
                    "_checkSeekable",
                    (self, args, kwNames) ->
                            ((PyStream) self)
                                    .checked(
                                            "_checkSeekable",
                                            "not seekable",
                                            args,
                                            kwNames,
                                            () -> ((PyStream) self).seekableAnswer()))
            .add(
                    "_checkWritable",
                    (self, args, kwNames) ->
                            ((PyStream) self)
                                    .checked(
                                            "_checkWritable",
                                            "not writable",
                                            args,
                                            kwNames,
                                            () -> ((PyStream) self).writableAnswer()))
            // `_io` files this row as `_PyIOBase_cannot_pickle` on all three classes, so no stream of the
            // three looks picklable — and it names the class the row was reached through, which is not the
            // `object.__getstate__` that a stream with no row of its own falls back to.
            .add("__getstate__", (self, args, kwNames) -> {
                Arguments.none("TextIOWrapper.__getstate__()", args, kwNames);
                throw Slots.typeError("cannot pickle 'TextIOWrapper' instances");
            });

    private boolean isTty() {
        // Java reports one console for the process; CPython asks each descriptor. Which descriptor is
        // attached to a terminal is M4's problem, along with the REPL that would notice.
        return System.console() != null;
    }

    /** {@code iter(sys.stdin)}: this stream is its own cursor, and a closed one is not iterable. */
    @Override
    public PyObject iterator() {
        // The guard is `_IOBase`'s, so it asks this stream's own `closed` attribute rather than the
        // descriptor under it — which is what makes a detached buffer refuse the iteration.
        checkVirtual();
        return this;
    }

    /**
     * {@code next(sys.stdin)}: the next line, and the end of the iteration at the end of the descriptor.
     *
     * <p>The reference clears its telling position here, which is what makes {@code tell()} refuse with
     * {@code telling position disabled by next() call} on a seekable stream afterwards. There is no
     * position to disable on this one, since the position questions all refuse already.
     */
    @Override
    public PyObject nextOrNull() {
        String line = readlineText(-1);
        return line.isEmpty() ? null : PyStr.of(line);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            // The five rows that ask only of the stream itself, whatever the layer below it says.
            case "encoding" -> PyStr.of(encoding);
            case "errors" -> PyStr.of(errors);
            case "line_buffering" -> PyBool.of(lineBuffered);
            case "write_through" -> PyBool.of(writeThrough);
            case "_finalizing" -> PyBool.of(finalizing);
            case "name" -> reading() ? Slots.getAttr(attached(), "name") : PyStr.of(name);
            case "closed" -> closedValue();
            case "buffer" -> {
                if (!reading()) {
                    // A writing stream has no buffer of its own in this runtime, so the row that would
                    // report one is the AttributeError of a name that was never filed: see the deviation
                    // note in PLAN-progress.md.
                    yield Members.find(this, attribute);
                }
                yield attached();
            }
            // The reference works this out as it reads, which is why the answer is the set of endings the
            // chunks have actually carried rather than anything the opening mode predicts: a stream that
            // has read nothing answers None even in a universal mode.
            case "newlines" -> newlinesAnswer();
            case "_CHUNK_SIZE" -> {
                checkAttached();
                yield PyInt.of(chunkSize);
            }
            case "__enter__" -> new PyNativeFunction("__enter__", (args, kwNames) -> {
                Arguments.none("_IOBase.__enter__()", args, kwNames);
                checkVirtual();
                return this;
            });
            // The reference's exit row takes the exception it is handed and pays no attention to it,
            // arity included: `s.__exit__()` closes the stream as surely as `with` does.
            case "__exit__" -> new PyNativeFunction("__exit__", (args, kwNames) -> closeAnswer());
            case "__doc__" -> PyNone.INSTANCE;
            case "__dict__" -> dictionary;
            default -> {
                // The names the switch above answers are the rows _io files as data descriptors, and those
                // are asked before the dictionary; everything else — mode, and any name a program wrote —
                // comes out of the dictionary before the type is asked.
                PyObject held = dictionary.get(PyStr.interned(attribute));
                yield held == null ? Members.find(this, attribute) : held;
            }
        };
    }

    @Override
    PyDict attributeStore() {
        return dictionary;
    }

    /**
     * {@code s.name = value} for a stream, which is the generic write after the refused names have had
     * their say.
     *
     * <p>The two sentences are the two kinds of row {@code _io} files: {@code name}, {@code closed},
     * {@code errors} and {@code newlines} are getsets whose write half is a null pointer, and {@code
     * encoding}, {@code line_buffering} and {@code write_through} are fields marked {@code Py_READONLY}.
     * A delete reaches the same row with nothing handed over and is refused in the same words. {@code
     * _CHUNK_SIZE} is a getset that does have a setter, and it converts the value as {@code
     * PyNumber_AsSsize_t} with a {@code ValueError} to overflow with, then refuses a count that is not
     * strictly positive; it is the field of the stream rather than of its type, so it is stored apart
     * from the dictionary and a write reaches it even after the stream has closed. {@code mode} is none
     * of these, and so lands in the dictionary like any name of the program's own. The dictionary itself
     * is the one name {@code _IOBase} files, and it can only be read.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        writeRefused(attribute);
        if (attribute.equals("_CHUNK_SIZE")) {
            // CHECK_ATTACHED_INT comes before the conversion, so a detached stream refuses an uncountable
            // value with the attachment sentence rather than with the TypeError of the conversion.
            checkAttached();
            long size = Numbers.count(value);
            if (size <= 0) {
                throw Slots.valueError("a strictly positive integer is required");
            }
            chunkSize = size;
            return PyNone.INSTANCE;
        }
        if (attribute.equals("_finalizing")) {
            // A `T_BOOL` member: `PyMember_SetOne` refuses anything that is not a bool, in its own words,
            // rather than counting it the way `bool(value)` would.
            if (!(value instanceof PyBool flag)) {
                throw Slots.typeError("attribute value type must be bool");
            }
            finalizing = flag == PyBool.TRUE;
            return PyNone.INSTANCE;
        }
        GenericAttributes.write(this, attribute, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        writeRefused(attribute);
        if (attribute.equals("_CHUNK_SIZE")) {
            // The row has a setter and no deleter, which is a third refusal of the three kinds of row.
            throw Slots.attributeError(
                    "attribute '_CHUNK_SIZE' of '_io.TextIOWrapper' objects cannot be deleted");
        }
        if (attribute.equals("_finalizing")) {
            // A `T_BOOL` field's deleter, which is `structmember.c`'s sentence and not the row's.
            throw Slots.typeError("can't delete numeric/char attribute");
        }
        GenericAttributes.delete(this, attribute);
        return PyNone.INSTANCE;
    }

    private void writeRefused(String attribute) {
        if (attribute.equals("__dict__")) {
            // The row is filed by _io._IOBase, the type that gives the stream its dictionary, and names
            // that type rather than the stream's own.
            throw Slots.attributeError(
                    "attribute '__dict__' of '_io._IOBase' objects is not writable");
        }
        if (NOT_WRITABLE.contains(attribute)) {
            throw Slots.attributeError(
                    "attribute '%s' of '_io.TextIOWrapper' objects is not writable", attribute);
        }
        if (READ_ONLY.contains(attribute)) {
            throw Slots.attributeError("readonly attribute");
        }
    }

    @Override
    public String repr() {
        PyObject name;
        try {
            name = Slots.getAttr(this, "name");
        } catch (PyRaise refused) {
            // The name is read through the buffer, and only the two sentences that mean there is no name
            // to read are cleared: the ValueError of a stream whose buffer has gone, and the AttributeError
            // of the layer below that lost the entry its constructor filed. A mode that refuses is not
            // cleared, and neither is any other name.
            if (!isUnnamed(refused)) {
                throw refused;
            }
            name = null;
        }
        // The mode is read as an attribute too, which is why rewriting the dictionary entry rewrites this
        // line, and why deleting it loses the field altogether.
        PyObject mode = dictionary.get(PyStr.interned("mode"));
        return "<_io.TextIOWrapper"
                + (name == null ? "" : " name=" + Slots.repr(name))
                + (mode == null ? "" : " mode=" + Slots.repr(mode))
                + " encoding="
                + Slots.repr(PyStr.of(encoding))
                + ">";
    }

    /** Whether a refusal of the repr's name question is one of the two that only say there is no name. */
    private static boolean isUnnamed(PyRaise refused) {
        PyType kind = refused.value().getType();
        return kind == ExceptionTypes.VALUE_ERROR || kind == ExceptionTypes.ATTRIBUTE_ERROR;
    }
}
