package org.python.core;

import java.io.ByteArrayOutputStream;

/**
 * The four abstract bases {@code _io} puts under every stream: {@code _IOBase}, {@code _TextIOBase},
 * {@code _RawIOBase} and {@code _BufferedIOBase}.
 *
 * <p>Each is a real type with a book of its own, because the reference's are: {@code vars} of one shows
 * the rows its C {@code PyMethodDef} table and its getset list declare, {@code isinstance} walks through
 * them, and a subclass inherits what they file. Their values hold nothing but an instance dictionary —
 * every method here is a stub that refuses, which is what "abstract base" means in {@code _io}.
 *
 * <p>The bodies are written against the value rather than the Java class: {@code close} calls the
 * value's own {@code flush}, {@code tell} calls its {@code seek}, and {@code readline} asks for its
 * {@code peek} and {@code read}. That is what makes a base value refuse with the message its subclass
 * would, and what lets one body serve whichever subclass inherited it.
 */
final class IoBases {

    private IoBases() {}

    /** The rows {@code _IOBase} files, in the reference's order. */
    static final Members IO_BASE = Members.of(PyType.IO_BASE);

    /** The rows {@code _TextIOBase} files, in the reference's order. */
    static final Members TEXT_IO_BASE = Members.of(PyType.TEXT_IO_BASE);

    /** The rows {@code _RawIOBase} files, in the reference's order. */
    static final Members RAW_IO_BASE = Members.of(PyType.RAW_IO_BASE);

    /** The rows {@code _BufferedIOBase} files, in the reference's order. */
    static final Members BUFFERED_IO_BASE = Members.of(PyType.BUFFERED_IO_BASE);

    private static final PyObject[] NO_ARGUMENTS = new PyObject[0];

    static {
        IO_BASE.add("seek", IoBases::seek)
                .addNoArgument("tell", IoBases::tell)
                .add("truncate", IoBases::truncate)
                .addNoArgument("flush", IoBases::flush)
                .addNoArgument("close", IoBases::close)
                .addNoArgument("seekable", self -> notClosed(self))
                .addNoArgument("readable", self -> notClosed(self))
                .addNoArgument("writable", self -> notClosed(self))
                .addNoArgument("_checkClosed", IoBases::checkClosedMethod)
                .addNoArgument(
                        "_checkSeekable",
                        self -> checked(self, "seekable", "File or stream is not seekable."))
                .addNoArgument(
                        "_checkReadable",
                        self -> checked(self, "readable", "File or stream is not readable."))
                .addNoArgument(
                        "_checkWritable",
                        self -> checked(self, "writable", "File or stream is not writable."))
                .add("fileno", (self, args, kwNames) -> fileno(args, kwNames))
                .addNoArgument("isatty", self -> notClosed(self))
                .addNoArgument("__enter__", IoBases::enter)
                .add("__exit__", (self, args, kwNames) -> exit(self, kwNames))
                .add("readline", IoBases::readline)
                .add("readlines", IoBases::readlines)
                .addOne("writelines", IoBases::writelines);

        TEXT_IO_BASE.add("detach", (self, args, kwNames) -> detach(args, kwNames))
                .add("read", (self, args, kwNames) -> unsupportedSize("read", args, kwNames))
                .add("readline", (self, args, kwNames) -> unsupportedSize("readline", args, kwNames))
                .add("write", IoBases::textWrite);

        RAW_IO_BASE.add("read", IoBases::rawRead)
                .addNoArgument("readall", self -> {
                    throw PyRaise.raise(ExceptionTypes.NOT_IMPLEMENTED_ERROR);
                })
                .add("readinto", (self, args, kwNames) -> rawUnsupported("_RawIOBase.readinto()", kwNames))
                .add("write", (self, args, kwNames) -> rawUnsupported("_RawIOBase.write()", kwNames));

        BUFFERED_IO_BASE.add("detach", (self, args, kwNames) -> detach(args, kwNames))
                .add("read", (self, args, kwNames) -> unsupportedSize("read", args, kwNames))
                .add("read1", (self, args, kwNames) -> unsupportedSize("read1", args, kwNames))
                .add("readinto", (self, args, kwNames) -> readInto("readinto", args, kwNames))
                .add("readinto1", (self, args, kwNames) -> readInto("readinto1", args, kwNames))
                .add("write", (self, args, kwNames) -> bufferedWrite(args, kwNames));
    }

    /** Whether {@code type} is one of the four bases, and so made here. */
    static boolean owns(PyType type) {
        return type == PyType.IO_BASE
                || type == PyType.TEXT_IO_BASE
                || type == PyType.RAW_IO_BASE
                || type == PyType.BUFFERED_IO_BASE;
    }

    /**
     * A bare base value: the reference's {@code tp_new} of these types takes nothing and leaves the
     * dictionary empty.
     */
    static PyObject instantiate(PyType type, PyObject[] args, PyTuple keywordNames) {
        if (args.length > 0 || keywordNames != null && keywordNames.size() > 0) {
            throw Slots.typeError("%s() takes no arguments", type.filedName());
        }
        return new Value(type);
    }

    /** {@code __dict__}: the value's own storage, made on the first read. */
    static PyObject dictionary(PyObject self) {
        PyDict store = self.attributeStore();
        return store == null ? PyDict.empty() : store;
    }

    /** {@code closed}: whether {@code close} has written its marker. */
    static PyObject closed(PyObject self) {
        return PyBool.of(isClosed(self));
    }

    /** The three {@code _TextIOBase} fields, which a bare base answers with {@code None}. */
    static PyObject absentField(PyObject self) {
        return PyNone.INSTANCE;
    }

    /** {@code __iter__}: a closed stream refuses, an open one is its own iterator. */
    static PyObject iterate(PyObject self) {
        checkClosed(self);
        return self;
    }

    /** {@code __next__}: the next line, with {@code StopIteration} at the empty one. */
    static PyObject advance(PyObject self) {
        PyObject line = Slots.call(Slots.getAttr(self, "readline"), NO_ARGUMENTS, null);
        if (Numbers.size(Slots.length(line)) > 0) {
            return line;
        }
        throw PyRaise.raise(ExceptionTypes.STOP_ITERATION);
    }

    /** {@code __del__}: close a stream that is still open, ignoring what the close raises. */
    static PyObject finalize(PyObject self) {
        Boolean closed = null;
        try {
            PyObject marker = Slots.findAttr(self, "closed");
            if (marker != null) {
                closed = Slots.truth(marker);
            }
        } catch (PyRaise unusable) {
            closed = null;
        }
        if (Boolean.FALSE.equals(closed)) {
            try {
                self.setAttr("_finalizing", PyBool.TRUE);
            } catch (PyRaise ignored) {
                // The reference clears the error and closes anyway.
            }
            try {
                Slots.call(Slots.getAttr(self, "close"), NO_ARGUMENTS, null);
            } catch (PyRaise ignored) {
                // Unraisable in the reference; a finalizer has nowhere to report it.
            }
        }
        return PyNone.INSTANCE;
    }

    private static PyObject seek(PyObject self, PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "seek", args, kwNames, new String[] {"", ""}, 1, 2);
        Numbers.size(values[0]);
        if (values[1] != null) {
            Numbers.size(values[1]);
        }
        throw Slots.unsupportedOperation("seek");
    }

    private static PyObject tell(PyObject self) {
        return Slots.call(Slots.getAttr(self, "seek"), new PyObject[] {PyInt.of(0), PyInt.of(1)}, null);
    }

    private static PyObject truncate(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.unpackKeywords("truncate", args, kwNames, new String[] {""}, 0, 1);
        throw Slots.unsupportedOperation("truncate");
    }

    private static PyObject flush(PyObject self) {
        checkClosed(self);
        return PyNone.INSTANCE;
    }

    private static PyObject close(PyObject self) {
        if (isClosed(self)) {
            return PyNone.INSTANCE;
        }
        PyRaise pending = null;
        try {
            Slots.call(Slots.getAttr(self, "flush"), NO_ARGUMENTS, null);
        } catch (PyRaise failure) {
            pending = failure;
        }
        try {
            self.setAttr("__IOBase_closed", PyBool.TRUE);
        } catch (PyRaise failure) {
            if (pending == null) {
                pending = failure;
            }
        }
        if (pending != null) {
            throw pending;
        }
        return PyNone.INSTANCE;
    }

    private static PyObject checkClosedMethod(PyObject self) {
        checkClosed(self);
        return PyNone.INSTANCE;
    }

    private static PyObject checked(PyObject self, String name, String refusal) {
        PyObject verdict = Slots.call(Slots.getAttr(self, name), NO_ARGUMENTS, null);
        if (verdict != PyBool.TRUE) {
            throw Slots.unsupportedOperation(refusal);
        }
        return PyNone.INSTANCE;
    }

    private static PyObject notClosed(PyObject self) {
        checkClosed(self);
        return PyBool.FALSE;
    }

    private static PyObject enter(PyObject self) {
        checkClosed(self);
        return self;
    }

    private static PyObject exit(PyObject self, String[] kwNames) {
        Arguments.noKeywords("_IOBase.__exit__()", kwNames);
        return Slots.call(Slots.getAttr(self, "close"), NO_ARGUMENTS, null);
    }

    private static PyObject fileno(PyObject[] args, String[] kwNames) {
        if (args.length > 0 || kwNames != null) {
            throw Slots.typeError("fileno() takes no arguments");
        }
        throw Slots.unsupportedOperation("fileno");
    }

    private static PyObject readline(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_IOBase.readline() takes no keyword arguments");
        }
        if (args.length > 1) {
            throw Slots.typeError("readline expected at most 1 argument, got %d", args.length);
        }
        long limit = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        PyObject peek = Slots.findAttr(self, "peek");
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (limit < 0 || line.size() < limit) {
            long nreadahead = 1;
            if (peek != null) {
                PyObject ahead = Slots.call(peek, new PyObject[] {PyInt.of(1)}, null);
                if (!(ahead instanceof PyBytes bytes)) {
                    throw Slots.osError(
                            "peek() should have returned a bytes object, not '%s'",
                            ahead.getType().name());
                }
                byte[] readahead = bytes.getValue();
                if (readahead.length > 0) {
                    int n = 0;
                    while (n < readahead.length && (limit < 0 || n < limit)) {
                        if (readahead[n++] == '\n') {
                            break;
                        }
                    }
                    nreadahead = n;
                }
            }
            PyObject next = Slots.call(
                    Slots.getAttr(self, "read"), new PyObject[] {PyInt.of(nreadahead)}, null);
            if (!(next instanceof PyBytes bytes)) {
                throw Slots.osError(
                        "read() should have returned a bytes object, not '%s'", next.getType().name());
            }
            byte[] read = bytes.getValue();
            if (read.length == 0) {
                break;
            }
            line.writeBytes(read);
            if (read[read.length - 1] == '\n') {
                break;
            }
        }
        return PyBytes.wrap(line.toByteArray());
    }

    private static PyObject readlines(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_IOBase.readlines() takes no keyword arguments");
        }
        if (args.length > 1) {
            throw Slots.typeError("readlines expected at most 1 argument, got %d", args.length);
        }
        long hint = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        checkClosed(self);
        PyList lines = PyList.empty();
        if (hint <= 0) {
            Slots.call(Slots.getAttr(lines, "extend"), new PyObject[] {self}, null);
            return lines;
        }
        PyIterator iterator = Slots.iterator(self);
        PyObject line;
        while ((line = iterator.nextOrNull()) != null) {
            long length = Numbers.size(Slots.length(line));
            lines.add(line);
            if (length > hint - lines.size()) {
                break;
            }
        }
        return lines;
    }

    private static PyObject writelines(PyObject self, PyObject lines) {
        checkClosed(self);
        PyIterator iterator = Slots.iterator(lines);
        PyObject line;
        while ((line = iterator.nextOrNull()) != null) {
            Slots.call(Slots.getAttr(self, "write"), new PyObject[] {line}, null);
        }
        return PyNone.INSTANCE;
    }

    private static PyObject detach(PyObject[] args, String[] kwNames) {
        if (args.length > 0 || kwNames != null) {
            throw Slots.typeError("detach() takes no arguments");
        }
        throw Slots.unsupportedOperation("detach");
    }

    private static PyObject unsupportedSize(String name, PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(name, args, kwNames, new String[] {""}, 0, 1);
        if (values[0] != null) {
            Numbers.size(values[0]);
        }
        throw Slots.unsupportedOperation(name);
    }

    private static PyObject textWrite(PyObject self, PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords("write", args, kwNames, new String[] {""}, 1, 1);
        if (!(values[0] instanceof PyStr)) {
            throw Slots.typeError(
                    "write() argument 1 must be str, not %s", values[0].getType().name());
        }
        throw Slots.unsupportedOperation("write");
    }

    private static PyObject bufferedWrite(PyObject[] args, String[] kwNames) {
        Arguments.unpackKeywords("write", args, kwNames, new String[] {""}, 1, 1);
        throw Slots.unsupportedOperation("write");
    }

    private static PyObject rawRead(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_RawIOBase.read() takes no keyword arguments");
        }
        if (args.length > 1) {
            throw Slots.typeError("read expected at most 1 argument, got %d", args.length);
        }
        if (args.length == 1) {
            Numbers.size(args[0]);
        }
        // A negative size asks for readall, anything else for readinto, and both refuse here.
        throw PyRaise.raise(ExceptionTypes.NOT_IMPLEMENTED_ERROR);
    }

    private static PyObject rawUnsupported(String qualified, String[] kwNames) {
        Arguments.noKeywords(qualified, kwNames);
        throw PyRaise.raise(ExceptionTypes.NOT_IMPLEMENTED_ERROR);
    }

    private static PyObject readInto(String name, PyObject[] args, String[] kwNames) {
        String qualified = "_BufferedIOBase." + name;
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError("%s() takes no keyword arguments", qualified);
        }
        if (args.length != 1) {
            throw Slots.typeError(
                    "%s() takes exactly one argument (%d given)", qualified, args.length);
        }
        throw Slots.typeError(
                "%s() argument must be read-write bytes-like object, not %s",
                name, args[0].getType().name());
    }

    private static boolean isClosed(PyObject self) {
        return Slots.findAttr(self, "__IOBase_closed") != null;
    }

    private static void checkClosed(PyObject self) {
        PyObject closed = Slots.findAttr(self, "closed");
        if (closed != null && Slots.truth(closed)) {
            throw Slots.valueError("I/O operation on closed file.");
        }
    }

    /**
     * A bare value of one of the four bases: an instance dictionary and the inherited rows.
     *
     * <p>It is an iterator of its own, because {@code _IOBase} is one: a value that inherits {@code
     * readline} from a base but not {@code __next__} would otherwise lose the protocol.
     */
    static final class Value extends PyIterator {

        private PyDict dictionary;

        Value(PyType type) {
            super(type);
        }

        @Override
        PyDict attributeStore() {
            if (dictionary == null) {
                dictionary = PyDict.empty();
            }
            return dictionary;
        }

        @Override
        public PyObject getAttr(String attribute) {
            PyDict store = dictionary;
            if (store != null) {
                PyObject value = store.get(PyStr.interned(attribute));
                if (value != null) {
                    return value;
                }
            }
            return Members.find(this, attribute);
        }

        @Override
        public String repr() {
            return "<" + getType().filedName() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }

        @Override
        public PyObject iterator() {
            return iterate(this);
        }

        @Override
        public PyObject nextOrNull() {
            PyObject line = Slots.call(Slots.getAttr(this, "readline"), NO_ARGUMENTS, null);
            return Numbers.size(Slots.length(line)) > 0 ? line : null;
        }
    }
}
