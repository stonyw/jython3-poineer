package org.python.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

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
 * <p>One class serves both directions, and which way it goes decides the rest of the face: the members of
 * the other direction are refused. The reference refuses them as {@code io.UnsupportedOperation}, a type of
 * the {@code io} module this runtime has not built, so they arrive as a plain {@code OSError} carrying the
 * same sentence.
 *
 * <p>{@code _io._IOBase} gives every stream a dictionary of its own, and the interpreter's stream
 * creation files {@code mode} in it before the object reaches {@code sys}. That is where the stream's
 * {@code mode} really lives: the type declares no member of that name, so {@code s.mode} is an ordinary
 * attribute a program can rewrite, and the repr reads it back. The members the type does declare refuse a
 * write in two sentences, and which one a name gets is decided by the row {@code _io} files for it — a
 * row this runtime does not have, so the six refusals are declared here instead. See {@link #setAttr}.
 */
public final class PyStream extends PyObject {

    static final PyType TYPE = PyType.builtin("TextIOWrapper").filedUnder("_io.TextIOWrapper");

    /** What {@code _io} says when a method of the stream itself meets a closed descriptor. */
    private static final String CLOSED = "I/O operation on closed file.";

    /** What the {@code io} module's own getters say, which is the same sentence with no full stop. */
    private static final String CLOSED_GETTER = "I/O operation on closed file";

    /** The rows {@code _io} files as getsets with no setter, which refuse in the first sentence. */
    private static final Set<String> NOT_WRITABLE = Set.of("name", "closed", "errors");

    /** The rows {@code _io} files as {@code Py_READONLY} members, which refuse by the field alone. */
    private static final Set<String> READ_ONLY = Set.of("encoding", "line_buffering", "write_through");

    private final String name;
    private final PrintStream out;
    private final InputStream in;
    private final String errors;
    private final boolean lineBuffered;
    private final int fd;

    /** {@code _IOBase.__dict__}: the one dictionary this stream's own attributes are kept in. */
    private final PyDict dictionary = PyDict.empty();

    private boolean closed;

    /** Whether a '\r' ended the last line and may still turn out to be half of a '\r\n'. */
    private boolean heldCarriageReturn;

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

    /** {@code s.write(text)}, which answers with how many characters it took. */
    private PyObject write(PyObject text) {
        if (closed) {
            throw Slots.valueError(CLOSED);
        }
        if (reading()) {
            throw Slots.osError("not writable");
        }
        String value = Numbers.asText(text);
        if (value == null) {
            throw Slots.typeError(
                    "write() argument must be str, not %s", text.getType().name());
        }
        writeText(value);
        return PyInt.of(value.codePointCount(0, value.length()));
    }

    /**
     * {@code s.readline()} on the reading stream: one line, with its terminator, and {@code ''} at the
     * end of the descriptor.
     *
     * <p>The reference translates newlines as it decodes, so a '\r' or a '\r\n' on the descriptor comes
     * back as the '\n' that {@code input} then strips. Only the terminator is translated, and a '\r' at
     * the end of one line is held rather than answered so that the '\n' that may follow it is swallowed
     * by this same stream later, not read as an empty line of its own.
     */
    private PyObject readline() {
        if (reading()) {
            if (closed) {
                throw Slots.valueError(CLOSED);
            }
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean ended = false;
            try {
                int next;
                while ((next = in.read()) != -1) {
                    if (heldCarriageReturn) {
                        heldCarriageReturn = false;
                        if (next == '\n') {
                            continue;
                        }
                    }
                    line.write(next);
                    if (next == '\n' || next == '\r') {
                        heldCarriageReturn = next == '\r';
                        ended = true;
                        break;
                    }
                }
            } catch (IOException refusal) {
                // The reference answers an I/O failure with an errno, which this runtime has no way to
                // ask the descriptor for, so the sentence carries only what went wrong.
                throw Slots.osError("reading error");
            }
            String text = Decoder.decode(line.toByteArray(), "utf-8", errors);
            if (ended && text.endsWith("\r")) {
                text = text.substring(0, text.length() - 1) + "\n";
            }
            return PyStr.of(text);
        }
        if (closed) {
            throw Slots.valueError(CLOSED);
        }
        throw Slots.osError("not readable");
    }

    private static final Members METHODS = Members.of(TYPE)
            .addOne("write", (self, text) -> ((PyStream) self).write(text))
            .addOne("writelines", (self, lines) -> {
                PyIterator iterator = Slots.iterator(lines);
                PyObject line;
                while ((line = iterator.nextOrNull()) != null) {
                    ((PyStream) self).write(line);
                }
                return PyNone.INSTANCE;
            })
            .addNoArgument("readline", self -> ((PyStream) self).readline())
            .addNoArgument("flush", self -> {
                if (((PyStream) self).closed) {
                    throw Slots.valueError(CLOSED);
                }
                ((PyStream) self).flush();
                return PyNone.INSTANCE;
            })
            .addNoArgument("close", self -> {
                PyStream stream = (PyStream) self;
                if (stream.closed) {
                    return PyNone.INSTANCE;
                }
                stream.closed = true;
                if (!stream.reading()) {
                    stream.flush();
                }
                return PyNone.INSTANCE;
            })
            .addNoArgument(
                    "isatty",
                    self -> {
                        if (((PyStream) self).closed) {
                            throw Slots.valueError(CLOSED_GETTER);
                        }
                        return PyBool.of(((PyStream) self).isTty());
                    })
            .addNoArgument(
                    "writable",
                    self -> {
                        PyStream stream = (PyStream) self;
                        if (stream.closed && !stream.reading()) {
                            throw Slots.valueError(CLOSED_GETTER);
                        }
                        return PyBool.of(!stream.reading());
                    })
            .addNoArgument(
                    "readable",
                    self -> {
                        PyStream stream = (PyStream) self;
                        if (stream.closed && stream.reading()) {
                            throw Slots.valueError(CLOSED_GETTER);
                        }
                        return PyBool.of(stream.reading());
                    });

    private boolean isTty() {
        // Java reports one console for the process; CPython asks each descriptor. Which descriptor is
        // attached to a terminal is M4's problem, along with the REPL that would notice.
        return System.console() != null;
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
            case "fileno" -> new PyNativeFunction("fileno", (args, kwNames) -> {
                Arguments.none("fileno", args, kwNames);
                if (closed) {
                    throw Slots.valueError(CLOSED_GETTER);
                }
                return PyInt.of(fd);
            });
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
     * {@code s.name = value} for a stream, which is the generic write after six names have refused it.
     *
     * <p>The two sentences are the two kinds of row {@code _io} files: {@code name}, {@code closed} and
     * {@code errors} are getsets whose write half is a null pointer, and {@code encoding}, {@code
     * line_buffering} and {@code write_through} are fields marked {@code Py_READONLY}. A delete reaches the
     * same row with nothing handed over and is refused in the same words; {@code mode} is neither, and so
     * lands in the dictionary like any name of the program's own. The dictionary itself is the one name
     * {@code _IOBase} files, and it can only be read.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        writeRefused(attribute);
        GenericAttributes.write(this, attribute, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        writeRefused(attribute);
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
