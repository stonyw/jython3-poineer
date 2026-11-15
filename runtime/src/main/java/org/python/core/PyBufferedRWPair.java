package org.python.core;

import java.util.Set;

/**
 * {@code _io.BufferedRWPair}: a reader and a writer over one stream, kept as two objects.
 *
 * <p>Unlike {@code BufferedRandom} the two halves are independent — there is no shared position and no
 * store of this object's own. {@code read}, {@code read1}, {@code readinto}, {@code readinto1},
 * {@code peek}, {@code readline} and {@code readlines} ask the reader; {@code write}, {@code writelines}
 * and {@code flush} ask the writer. {@code close} closes the reader and then the writer, still closing the
 * writer when the reader refuses, and raising the reader's failure with the writer's as its context.
 *
 * <p>{@code seek}, {@code tell} and {@code truncate} are not the interface here: the reference answers
 * {@code UnsupportedOperation} for all three, and {@code seekable} is False. {@code closed} is the
 * writer's flag, which is what makes a reader whose {@code close} refused leave the pair open.
 */
public final class PyBufferedRWPair extends PyIOStream {

    /** The reader half: everything that produces octets is delegated here. */
    private PyObject reader;

    /** The writer half: everything that consumes octets is delegated here. */
    private PyObject writer;

    /** {@code buffer_size}, converted like the reader's and otherwise unused by this delegation. */
    private int bufferSize;

    /** Whether {@code __init__} has run; {@code tp.__new__} makes a shell with no halves. */
    private boolean initialized;

    /** The two answers the constructor asks once, which the direction rows report. */
    private boolean readable;
    private boolean writable;

    private PyBufferedRWPair(PyType type) {
        super(type);
    }

    /**
     * {@code tp_new} for this type: a call with the two halves both makes and fills the object, while
     * {@code tp.__new__(tp)} with nothing makes the shell that {@code __init__} fills afterwards.
     */
    static PyBufferedRWPair construct(PyType kind, PyObject[] args, String[] kwNames) {
        PyBufferedRWPair pair = new PyBufferedRWPair(kind);
        pair.initRow(args, kwNames);
        return pair;
    }

    /**
     * {@code BufferedRWPair(reader, writer, buffer_size=DEFAULT_BUFFER_SIZE)}, which takes no keywords.
     *
     * <p>The two halves are asked whether they can do their half before the object is usable: a reader
     * that says it is not readable, or a writer that says it is not writable, is an
     * {@code UnsupportedOperation} at construction rather than a refusal later.
     */
    private PyObject initRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedRWPair", kwNames);
        if (args.length < 2) {
            throw Slots.typeError(
                    "BufferedRWPair expected at least 2 arguments, got %d", args.length);
        }
        if (args.length > 3) {
            throw Slots.typeError(
                    "BufferedRWPair expected at most 3 arguments, got %d", args.length);
        }
        initialize(args[0], args[1],
                args.length > 2
                        ? PyBufferedReader.bufferSizeValue(args[2])
                        : PyBufferedReader.DEFAULT_BUFFER_SIZE);
        return PyNone.INSTANCE;
    }

    private void initialize(PyObject reader, PyObject writer, int bufferSize) {
        this.writer = writer;
        this.bufferSize = bufferSize;
        this.initialized = false;
        this.readable = asks(reader, "readable");
        if (!readable) {
            throw Slots.unsupportedOperation("File or stream is not readable.");
        }
        this.writable = asks(writer, "writable");
        if (!writable) {
            throw Slots.unsupportedOperation("File or stream is not writable.");
        }
        // The reference wraps the reader in a BufferedReader rather than delegating to it directly,
        // which is why `peek` and `read1` answer the same for a pair whose reader is a raw stream.
        this.reader = new PyBufferedReader(
                reader, PyType.BUFFERED_READER, true, false, bufferSize);
        this.initialized = true;
    }

    /**
     * {@code _io.BufferedRWPair.__new__(cls)}: the shell {@code __init__} fills.
     *
     * <p>{@code tp_new} reads no arguments — {@code __new__(cls, reader, writer)} is still the empty
     * shell — and refuses another kind with {@code object.__new__}'s own words.
     */
    private static PyObject newRow(PyObject kind, PyObject[] args, String[] kwNames) {
        if (!(kind instanceof PyType subtype)) {
            throw Slots.typeError(
                    "object.__new__(X): X is not a type object (%s)", kind.getType().name());
        }
        if (!subtype.matches(PyType.BUFFERED_RW_PAIR)) {
            throw Slots.typeError(
                    "object.__new__(%s) is not safe, use %s.__new__()",
                    subtype.filedName(), subtype.filedName());
        }
        return new PyBufferedRWPair(subtype);
    }

    private static boolean asks(PyObject half, String row) {
        return Slots.truth(Slots.call(Slots.getAttr(half, row), new PyObject[0], null));
    }

    private void checkInitialized() {
        if (!initialized) {
            throw Slots.valueError("I/O operation on uninitialized object");
        }
    }

    /** Iteration is the reader's lines: empty means the end, as it does for every binary stream. */
    @Override
    public PyObject nextOrNull() {
        checkInitialized();
        PyObject line = forward(reader, "readline", PyInt.of(-1));
        if (line instanceof PyBytes bytes && bytes.getValue().length == 0) {
            return null;
        }
        return line;
    }

    @Override
    boolean closedFlag() {
        checkInitialized();
        return Slots.truth(Slots.getAttr(writer, "closed"));
    }

    @Override
    Set<String> notWritable() {
        return Set.of("closed");
    }

    @Override
    Set<String> readOnly() {
        return Set.of();
    }

    @Override
    public PyObject getAttr(String attribute) {
        if (attribute.equals("closed") && initialized) {
            return Slots.getAttr(writer, "closed");
        }
        return super.getAttr(attribute);
    }

    /** {@code close}: both halves, reader first, writer even when the reader refused. */
    @Override
    PyObject close() {
        checkInitialized();
        PyRaise readerFailure = null;
        PyRaise writerFailure = null;
        try {
            Slots.call(Slots.getAttr(reader, "close"), new PyObject[0], null);
        } catch (PyRaise failure) {
            readerFailure = failure;
        }
        try {
            Slots.call(Slots.getAttr(writer, "close"), new PyObject[0], null);
        } catch (PyRaise failure) {
            writerFailure = failure;
        }
        if (readerFailure != null) {
            if (writerFailure != null) {
                readerFailure.value().setContext(writerFailure.value());
            }
            throw readerFailure;
        }
        if (writerFailure != null) {
            throw writerFailure;
        }
        return PyNone.INSTANCE;
    }

    private PyObject readRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedRWPair.read()", kwNames);
        Arguments.atMost("read", args, kwNames, 1);
        checkInitialized();
        long wanted = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        return forward(reader, "read", PyInt.of(wanted));
    }

    private PyObject read1Row(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedRWPair.read1()", kwNames);
        Arguments.atMost("read1", args, kwNames, 1);
        checkInitialized();
        long wanted = args.length == 0 ? -1 : Numbers.size(args[0]);
        return forward(reader, "read1", PyInt.of(wanted));
    }

    private PyObject peekRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedRWPair.peek()", kwNames);
        Arguments.atMost("peek", args, kwNames, 1);
        checkInitialized();
        if (args.length > 0) {
            Numbers.size(args[0]);
        }
        return forward(reader, "peek", PyInt.of(args.length == 0 ? 0 : Numbers.size(args[0])));
    }

    private PyObject readlineRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BufferedRWPair.readline()", kwNames);
        Arguments.atMost("readline", args, kwNames, 1);
        checkInitialized();
        long wanted = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        return forward(reader, "readline", PyInt.of(wanted));
    }

    private PyObject readintoRow(boolean single, PyObject[] args, String[] kwNames) {
        String name = single ? "readinto1" : "readinto";
        Arguments.noKeywords("BufferedRWPair." + name + "()", kwNames);
        Arguments.exactlyOne("BufferedRWPair." + name + "()", args, kwNames);
        checkInitialized();
        return Slots.call(Slots.getAttr(reader, name), args, null);
    }

    private PyObject writeRow(PyObject[] args, String[] kwNames) {
        Arguments.onlyPositional("write", args, kwNames, 1);
        checkInitialized();
        return forward(writer, "write", args[0]);
    }

    private static PyObject forward(PyObject half, String row, PyObject argument) {
        return Slots.call(Slots.getAttr(half, row), new PyObject[] {argument}, null);
    }

    private static Members table(PyType type) {
        return Members.of(type)
            .add("read", (self, args, kwNames) -> ((PyBufferedRWPair) self).readRow(args, kwNames))
            .add("read1", (self, args, kwNames) -> ((PyBufferedRWPair) self).read1Row(args, kwNames))
            .add("peek", (self, args, kwNames) -> ((PyBufferedRWPair) self).peekRow(args, kwNames))
            .add("readline", (self, args, kwNames) ->
                    ((PyBufferedRWPair) self).readlineRow(args, kwNames))
            .add("readlines", (self, args, kwNames) -> {
                Arguments.noKeywords("BufferedRWPair.readlines()", kwNames);
                Arguments.atMost("readlines", args, kwNames, 1);
                ((PyBufferedRWPair) self).checkInitialized();
                return Slots.call(
                        Slots.getAttr(((PyBufferedRWPair) self).reader, "readlines"), args, null);
            })
            .add("readinto", (self, args, kwNames) ->
                    ((PyBufferedRWPair) self).readintoRow(false, args, kwNames))
            .add("readinto1", (self, args, kwNames) ->
                    ((PyBufferedRWPair) self).readintoRow(true, args, kwNames))
            .add("write", (self, args, kwNames) -> ((PyBufferedRWPair) self).writeRow(args, kwNames))
            .add("writelines", (self, args, kwNames) -> ((PyIOStream) self).writeLines(args, kwNames))
            .addNoArgument("flush", self -> {
                PyBufferedRWPair pair = (PyBufferedRWPair) self;
                pair.checkInitialized();
                return Slots.call(Slots.getAttr(pair.writer, "flush"), new PyObject[0], null);
            })
            .addNoArgument("close", self -> ((PyBufferedRWPair) self).close())
            .addNoArgument("readable", self -> {
                ((PyBufferedRWPair) self).checkInitialized();
                return PyBool.of(((PyBufferedRWPair) self).readable);
            })
            .add("writable", (self, args, kwNames) -> {
                Arguments.none("_IOBase.writable()", args, kwNames);
                ((PyBufferedRWPair) self).checkInitialized();
                return PyBool.of(((PyBufferedRWPair) self).writable);
            })
            .addNoArgument("seekable", self -> {
                ((PyBufferedRWPair) self).checkInitialized();
                return PyBool.FALSE;
            })
            .addNoArgument("isatty", self -> {
                PyBufferedRWPair pair = (PyBufferedRWPair) self;
                pair.checkInitialized();
                return PyBool.of(asks(pair.reader, "isatty") || asks(pair.writer, "isatty"));
            })
            .addNoArgument("fileno", self -> {
                PyBufferedRWPair pair = (PyBufferedRWPair) self;
                pair.checkInitialized();
                return Slots.call(Slots.getAttr(pair.reader, "fileno"), new PyObject[0], null);
            })
            // The three position rows of a stream with two independent halves: the reference refuses
            // each with the name of the row it is not.
            .add("seek", (self, args, kwNames) -> {
                throw Slots.unsupportedOperation("seek");
            })
            .add("tell", (self, args, kwNames) -> {
                throw Slots.unsupportedOperation("seek");
            })
            .add("truncate", (self, args, kwNames) -> {
                throw Slots.unsupportedOperation("truncate");
            })
            .add("detach", (self, args, kwNames) -> {
                throw Slots.unsupportedOperation("detach");
            })
            .add("_checkClosed", (self, args, kwNames) ->
                    ((PyIOStream) self).checkClosedRow(args, kwNames))
            .add("_checkReadable", (self, args, kwNames) ->
                    ((PyBufferedRWPair) self).checked(
                            "_checkReadable", "not readable", args, kwNames,
                            () -> Slots.call(
                                    Slots.getAttr(self, "readable"), new PyObject[0], null)))
            .add("_checkSeekable", (self, args, kwNames) ->
                    ((PyBufferedRWPair) self).checked(
                            "_checkSeekable", "not seekable", args, kwNames,
                            () -> Slots.call(
                                    Slots.getAttr(self, "seekable"), new PyObject[0], null)))
            .add("_checkWritable", (self, args, kwNames) ->
                    ((PyBufferedRWPair) self).checked(
                            "_checkWritable", "not writable", args, kwNames,
                            () -> Slots.call(
                                    Slots.getAttr(self, "writable"), new PyObject[0], null)))
            .add("_dealloc_warn", (self, args, kwNames) ->
                    ((PyIOStream) self).deallocWarn(args, kwNames))
            .add("__getstate__", (self, args, kwNames) -> ((PyIOStream) self).getState(args, kwNames))
            .add("__enter__", (self, args, kwNames) -> ((PyIOStream) self).enter(args, kwNames))
            .add("__exit__", (self, args, kwNames) -> ((PyIOStream) self).exit(args))
            .addClassNamed("__new__", PyBufferedRWPair::newRow)
            .add("__init__", (self, args, kwNames) ->
                    ((PyBufferedRWPair) self).initRow(args, kwNames));
    }

    /** Registers the rows above the first time the type is asked for them. */
    private static final Members METHODS = table(PyType.BUFFERED_RW_PAIR);
}
