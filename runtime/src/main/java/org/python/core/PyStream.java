package org.python.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * <p>There is no buffered or raw layer beneath this stream, and that is where the reading face stops
 * matching. {@code seekable()} asks the buffer, and there is no buffer to ask, so it answers False
 * everywhere and {@code seek} and {@code tell} refuse with {@code underlying stream is not seekable} —
 * which is what the reference says of a pipe, and not what it says of a redirected file. {@code
 * truncate} reaches the buffer's own refusal for a reading stream, {@code truncate}, and for a writing
 * one the reference reaches the descriptor and answers an errno this runtime cannot ask for.
 *
 * <p>{@code _io._IOBase} gives every stream a dictionary of its own, and the interpreter's stream
 * creation files {@code mode} in it before the object reaches {@code sys}. That is where the stream's
 * {@code mode} really lives: the type declares no member of that name, so {@code s.mode} is an ordinary
 * attribute a program can rewrite, and the repr reads it back. The members the type does declare refuse
 * a write in two sentences, and which one a name gets is decided by the row {@code _io} files for it — a
 * row this runtime does not have, so the refusals are declared here instead. See {@link #setAttr}.
 */
public final class PyStream extends PyIterator {

    static final PyType TYPE = PyType.builtin("TextIOWrapper").filedUnder("_io.TextIOWrapper");

    /** What {@code _io} says when a method of the stream itself meets a closed descriptor. */
    private static final String CLOSED = "I/O operation on closed file.";

    /** What the {@code io} module's own getters say, which is the same sentence with no full stop. */
    private static final String CLOSED_GETTER = "I/O operation on closed file";

    /** The rows {@code _io} files as getsets with no setter, which refuse in the first sentence. */
    private static final Set<String> NOT_WRITABLE = Set.of("name", "closed", "errors", "newlines");

    /** The rows {@code _io} files as {@code Py_READONLY} members, which refuse by the field alone. */
    private static final Set<String> READ_ONLY = Set.of("encoding", "line_buffering", "write_through");

    /**
     * The most octets one read of the descriptor is asked for, however big the chunk the stream was
     * told to use.
     *
     * <p>{@code _CHUNK_SIZE} is a writable field of the reference's stream and a program may set it to
     * {@code 2 ** 40}, which the reference hands to {@code read()} on the raw descriptor. That read
     * allocates the buffer inside {@code FileIO}, and a Java array of the same request is allocated
     * here — so the request is cut to what one read of any descriptor is worth anyway, since the loops
     * above reassemble the text whatever size each read came back with.
     */
    private static final int MAX_RAW_READ = 1 << 16;

    private final String name;
    private final PrintStream out;
    private final InputStream in;
    private final String errors;
    private final boolean lineBuffered;
    private final int fd;

    /** {@code _IOBase.__dict__}: the one dictionary this stream's own attributes are kept in. */
    private final PyDict dictionary = PyDict.empty();

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
     * @param autoFlush whether the descriptor is drained on every newline, which is what a terminal
     *     gets and a pipe does not
     */
    PyStream(String name, PrintStream target, int fd, String errors, boolean autoFlush) {
        super(TYPE);
        this.name = name;
        this.out = new PrintStream(target, autoFlush, StandardCharsets.UTF_8);
        this.in = null;
        this.errors = errors;
        this.lineBuffered = autoFlush;
        this.fd = fd;
        declareMode("w");
    }

    /** {@code sys.stdin}: the same descriptor type, reading. */
    PyStream(String name, InputStream source, int fd, String errors) {
        super(TYPE);
        this.name = name;
        this.out = null;
        this.in = source;
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
        if (reading()) {
            // A reading stream has nothing of its own to drain, and the reference answers None for it.
            return;
        }
        out.flush();
    }

    private boolean reading() {
        return in != null;
    }

    /** {@code CHECK_CLOSED}, the guard every method of the stream itself runs first. */
    private void checkOpen() {
        if (closed) {
            throw Slots.valueError(CLOSED);
        }
    }

    /** The same guard as the getters and the descriptor questions use, which drops the full stop. */
    private void checkGetter() {
        if (closed) {
            throw Slots.valueError(CLOSED_GETTER);
        }
    }

    /** The guard of the reading methods: the stream's own, then the direction. */
    private void checkReadable() {
        checkOpen();
        if (!reading()) {
            throw Slots.unsupportedOperation("not readable");
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
            return PyStr.of(take(-1) + decode(readAllOctets(), true));
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
     * <p>The terminator is the '\n' the stream was opened with, and only that: a '\r' is text like any
     * other character, so {@code 'a\rb\n'} is one line of five characters.
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
        String text = decode(octets, atEnd);
        int characters = characters(text);
        bytesPerChar = characters > 0 ? (double) octets.length / characters : 0.0;
        replacePending(text);
        return !(atEnd && characters == 0);
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

    /** Where the line ends in the current chunk, counted as the reference counts it: up to and including. */
    private int terminator(int from) {
        int at = pending.indexOf('\n', pending.offsetByCodePoints(0, from));
        return at < 0 ? -1 : pending.codePointCount(0, at + 1);
    }

    private byte[] readOctets(long wanted) {
        byte[] buffer = new byte[(int) Math.min(Math.max(wanted, 1), MAX_RAW_READ)];
        try {
            int got = in.read(buffer);
            return got <= 0 ? new byte[0] : Arrays.copyOf(buffer, got);
        } catch (IOException refusal) {
            // The reference answers an I/O failure with an errno, which this runtime has no way to ask
            // the descriptor for, so the sentence carries only what went wrong.
            throw Slots.osError("reading error");
        }
    }

    /** The rest of the descriptor, which is the {@code buffer.read()} a whole-file {@code read()} does. */
    private byte[] readAllOctets() {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[MAX_RAW_READ];
        try {
            int got;
            while ((got = in.read(buffer)) > 0) {
                all.write(buffer, 0, got);
            }
        } catch (IOException refusal) {
            throw Slots.osError("reading error");
        }
        return all.toByteArray();
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

    private PyObject closeAnswer() {
        if (closed) {
            return PyNone.INSTANCE;
        }
        closed = true;
        if (!reading()) {
            flush();
        }
        return PyNone.INSTANCE;
    }

    private PyObject readableAnswer() {
        if (closed && reading()) {
            throw Slots.valueError(CLOSED_GETTER);
        }
        return PyBool.of(reading());
    }

    private PyObject writableAnswer() {
        if (closed && !reading()) {
            throw Slots.valueError(CLOSED_GETTER);
        }
        return PyBool.of(!reading());
    }

    /** {@code seekable}, which is the buffer's answer: here there is no buffer, so it is always False. */
    private PyObject seekableAnswer() {
        checkGetter();
        return PyBool.FALSE;
    }

    private PyObject tellAnswer() {
        checkOpen();
        throw Slots.unsupportedOperation("underlying stream is not seekable");
    }

    private static final Members METHODS = Members.of(TYPE)
            .addOne("write", (self, text) -> ((PyStream) self).write(text))
            // The row is _IOBase's, and a METH_O refusal names the class that files it.
            .add("writelines", (self, args, kwNames) -> {
                Arguments.exactlyOne("_IOBase.writelines()", args, kwNames);
                PyStream stream = (PyStream) self;
                stream.checkOpen();
                PyIterator lines = Slots.iterator(args[0]);
                PyObject line;
                while ((line = lines.nextOrNull()) != null) {
                    stream.write(line);
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
            .add("seek", (self, args, kwNames) -> {
                Arguments.noKeywords("TextIOWrapper.seek()", kwNames);
                Arguments.between("seek", args, kwNames, 1, 2);
                PyStream stream = (PyStream) self;
                // The cookie is an object as far as this method is concerned — it only ever reaches a
                // seekable stream — but whence is converted, and clinic converts it before the body runs.
                if (args.length > 1) {
                    Numbers.size(args[1]);
                }
                stream.checkOpen();
                throw Slots.unsupportedOperation("underlying stream is not seekable");
            })
            .addNoArgument("tell", self -> ((PyStream) self).tellAnswer())
            .add("truncate", (self, args, kwNames) -> {
                Arguments.noKeywords("TextIOWrapper.truncate()", kwNames);
                Arguments.atMost("truncate", args, kwNames, 1);
                PyStream stream = (PyStream) self;
                // The position is passed on unconverted, because it only ever reaches a stream that can
                // truncate. What runs first is the flush this row does before asking the buffer.
                stream.checkOpen();
                throw Slots.unsupportedOperation("truncate");
            })
            .addNoArgument("flush", self -> {
                PyStream stream = (PyStream) self;
                stream.checkOpen();
                stream.flush();
                return PyNone.INSTANCE;
            })
            .addNoArgument("close", self -> ((PyStream) self).closeAnswer())
            .addNoArgument(
                    "isatty",
                    self -> {
                        ((PyStream) self).checkGetter();
                        return PyBool.of(((PyStream) self).isTty());
                    })
            .addNoArgument("writable", self -> ((PyStream) self).writableAnswer())
            .addNoArgument("readable", self -> ((PyStream) self).readableAnswer())
            .addNoArgument("seekable", self -> ((PyStream) self).seekableAnswer())
            .add("_checkClosed", (self, args, kwNames) -> {
                Arguments.none("_IOBase._checkClosed()", args, kwNames);
                ((PyStream) self).checkOpen();
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
                                            () -> ((PyStream) self).writableAnswer()));

    private boolean isTty() {
        // Java reports one console for the process; CPython asks each descriptor. Which descriptor is
        // attached to a terminal is M4's problem, along with the REPL that would notice.
        return System.console() != null;
    }

    /** {@code iter(sys.stdin)}: this stream is its own cursor, and a closed one is not iterable. */
    @Override
    public PyObject iterator() {
        checkOpen();
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
            case "encoding" -> PyStr.of("utf-8");
            case "errors" -> PyStr.of(errors);
            case "name" -> PyStr.of(name);
            case "closed" -> PyBool.of(closed);
            case "line_buffering" -> PyBool.of(lineBuffered);
            case "write_through" -> PyBool.FALSE;
            // The reference works this out as it reads, and universal newlines is what would fill it in.
            // sys.stdin is opened with newline='\n', so no line ending is ever translated and none is
            // ever found.
            case "newlines" -> PyNone.INSTANCE;
            case "_CHUNK_SIZE" -> PyInt.of(chunkSize);
            case "fileno" -> new PyNativeFunction("fileno", (args, kwNames) -> {
                Arguments.none("fileno", args, kwNames);
                checkGetter();
                return PyInt.of(fd);
            });
            case "__enter__" -> new PyNativeFunction("__enter__", (args, kwNames) -> {
                Arguments.none("_IOBase.__enter__()", args, kwNames);
                checkOpen();
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
            long size = Numbers.count(value);
            if (size <= 0) {
                throw Slots.valueError("a strictly positive integer is required");
            }
            chunkSize = size;
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
        // The name and the mode are read as attributes, which is why rewriting the mode in the dictionary
        // rewrites this line, and why a stream whose mode entry has been deleted loses the field altogether.
        PyObject mode = dictionary.get(PyStr.interned("mode"));
        return "<_io.TextIOWrapper name="
                + Slots.repr(PyStr.of(name))
                + (mode == null ? "" : " mode=" + Slots.repr(mode))
                + " encoding="
                + Slots.repr(PyStr.of("utf-8"))
                + ">";
    }
}
