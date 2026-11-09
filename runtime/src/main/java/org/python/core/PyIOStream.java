package org.python.core;

import java.util.Set;
import java.util.function.Supplier;

/**
 * What {@code _io._IOBase} gives the two binary layers: the dictionary every stream owns, the closed
 * guard whose two sentences a row picks by kind, the four questions a subclass asks itself, the
 * {@code readlines} that iterates the stream and the pickle refusal.
 *
 * <p>The closedness is not stored here. {@code _IOBase} has a flag of its own, but {@code _io} builds a
 * reading stream as three objects that ask one another — the wrapper asks the buffer's {@code closed}
 * and the buffer asks the raw's — so the answer a layer gives is always its raw's, and
 * {@link #closedFlag()} is how a subclass says where its answer comes from. That is also why closing
 * the descriptor closes the two objects above it: nothing propagates, everyone reads the same flag.
 *
 * <p>{@code TextIOWrapper} repeats three of these bodies rather than extending this class. Its guards
 * were written before there was anything to share, its {@code readlines} counts characters rather than
 * lines, and the two sentences it refuses with are the same words for a different reason — see
 * {@link PyStream}.
 */
abstract class PyIOStream extends PyIterator {

    /** What {@code CHECK_CLOSED} says inside a method of the stream itself. */
    static final String CLOSED = "I/O operation on closed file.";

    /** What the getters and the descriptor questions say, which is the same sentence with no full stop. */
    static final String CLOSED_GETTER = "I/O operation on closed file";

    /** What the buffered layer says of a descriptor it no longer holds. */
    static final String DETACHED = "raw stream has been detached";

    /** {@code _IOBase.__dict__}: where {@code FileIO} keeps the {@code name} its constructor filed. */
    private final PyDict dictionary = PyDict.empty();

    /** {@code _IOBase._finalizing}, the one member of the base that a program may write. */
    private boolean finalizing;

    PyIOStream(PyType type) {
        super(type);
    }

    /** Whether the stream this one rests on has been closed. */
    abstract boolean closedFlag();

    /** The rows {@code _io} files as getsets with no setter, which refuse in the first sentence. */
    abstract Set<String> notWritable();

    /** The rows {@code _io} files as {@code Py_READONLY} members, which refuse by the field alone. */
    abstract Set<String> readOnly();

    /** The name the pickle sentence quotes, which is the class's own rather than its module's. */
    final String shortName() {
        return getType().name();
    }

    final void checkOpen() {
        if (closedFlag()) {
            throw Slots.valueError(CLOSED);
        }
    }

    final void checkGetter() {
        if (closedFlag()) {
            throw Slots.valueError(CLOSED_GETTER);
        }
    }

    /**
     * {@code _PyIOBase_check_readable} and its two neighbours: ask the stream, and refuse unless the
     * answer is exactly True.
     */
    final PyObject checked(
            String row, String refusal, PyObject[] args, String[] kwNames, Supplier<PyObject> ask) {
        Arguments.none("_IOBase." + row + "()", args, kwNames);
        PyObject answer = ask.get();
        if (answer != PyBool.TRUE) {
            throw Slots.unsupportedOperation("File or stream is %s.", refusal);
        }
        return answer;
    }

    /** {@code _checkClosed}, which has nothing to refuse and only the guard to run. */
    final PyObject checkClosedRow(PyObject[] args, String[] kwNames) {
        Arguments.none("_IOBase._checkClosed()", args, kwNames);
        checkOpen();
        return PyNone.INSTANCE;
    }

    /**
     * {@code _IOBase.readlines(hint)}: the lines the hint covers, counted in lines.
     *
     * <p>The row loops {@code extend(self)} over the stream's own cursor, so a layer reads its lines the
     * way its own {@code readline} makes them and the count is of lines rather than of characters —
     * which is what separates this from the text stream's version of the same row.
     */
    final PyObject readLines(long hint) {
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
            int size = ((PyBytes) line).getValue().length;
            if (size > hint - length) {
                return answer;
            }
            length += size;
        }
    }

    /** The row's own conversion and guard order: clinic first, then the descriptor questions. */
    final PyObject readLines(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("_IOBase.readlines()", kwNames);
        Arguments.atMost("readlines", args, kwNames, 1);
        return readLines(args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1));
    }

    /**
     * {@code _IOBase.writelines(lines)}: hand each item to this stream's own {@code write}.
     *
     * <p>The row checks closedness before it asks for the attribute rather than as it writes, so an empty
     * iterable answers None on a stream that could not write a single octet but refuses a closed one, and
     * a {@code write} a program filed in the dictionary is the one that runs.
     */
    final PyObject writeLines(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("_IOBase.writelines()", args, kwNames);
        checkOpen();
        PyIterator lines = Slots.iterator(args[0]);
        PyObject write = Slots.getAttr(this, "write");
        PyObject line;
        while ((line = lines.nextOrNull()) != null) {
            Slots.call(write, new PyObject[] {line}, null);
        }
        return PyNone.INSTANCE;
    }

    /** {@code _IOBase.__getstate__}, which {@code _io} refuses so that no stream looks picklable. */
    final PyObject getState(PyObject[] args, String[] kwNames) {
        Arguments.none(shortName() + ".__getstate__()", args, kwNames);
        throw Slots.typeError("cannot pickle '%s' instances", shortName());
    }

    /**
     * {@code _IOBase._dealloc_warn}, whose only visible half is that it takes one argument.
     *
     * <p>The row is inherited, but the refusal names the class the row was reached through, which is the
     * buffered reader on {@code sys.stdin.buffer} and the file object on the layer under it.
     */
    final PyObject deallocWarn(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne(shortName() + "._dealloc_warn()", args, kwNames);
        return PyNone.INSTANCE;
    }

    /**
     * {@code _IOBase.__enter__}: the stream itself.
     *
     * <p>The row runs {@code _PyIOBase_check_closed}, which asks the stream's own {@code closed}
     * attribute and so reads the descriptor two layers down, and refuses with the full stop.
     */
    final PyObject enter(PyObject[] args, String[] kwNames) {
        Arguments.none("_IOBase.__enter__()", args, kwNames);
        checkOpen();
        return this;
    }

    /**
     * {@code _IOBase.__exit__}, which closes and pays no attention to what it is handed.
     *
     * <p>The row asks for the {@code close} attribute rather than running the class's own, so the method a
     * program filed in the dictionary is the one that answers.
     */
    final PyObject exit(PyObject[] args) {
        return Slots.call(Slots.getAttr(this, "close"), new PyObject[0], null);
    }

    /**
     * {@code _IOBase.close}, the body a stream runs before it does anything of its own.
     *
     * <p>What it keeps is not the derived {@code closed} but a flag of its own filed in the instance
     * dictionary under {@code __IOBase_closed}, and the two answers are asked apart: {@code iobase_is_closed}
     * reads the flag to decide whether there is anything to do, and a stream that has been closed below
     * itself — a descriptor that the layer above closed — has no flag and runs this body again. The flush
     * is this stream's own row reached through Python, and a flush that refuses does not stop the flag from
     * being filed; it is kept and raised after.
     */
    final PyObject internalClose() {
        if (dictionary.get(PyStr.interned("__IOBase_closed")) != null) {
            return PyNone.INSTANCE;
        }
        PyRaise refused = null;
        try {
            Slots.call(Slots.getAttr(this, "flush"), new PyObject[0], null);
        } catch (PyRaise flushFailure) {
            refused = flushFailure;
        }
        dictionary.put(PyStr.interned("__IOBase_closed"), PyBool.TRUE);
        if (refused != null) {
            throw refused;
        }
        return PyNone.INSTANCE;
    }

    /** {@code s.close()}, which every layer ends up asking the descriptor to do. */
    abstract PyObject close();

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__dict__" -> dictionary;
            case "_finalizing" -> PyBool.of(finalizing);
            default -> {
                // A method row is not a data descriptor, so a name a program wrote covers the class's own
                // `fileno` just as it covers one the class never filed — which is why the dictionary is
                // asked before the table.
                PyObject held = dictionary.get(PyStr.interned(attribute));
                yield held == null ? Members.find(this, attribute) : held;
            }
        };
    }

    @Override
    PyDict attributeStore() {
        return dictionary;
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        writeRefused(attribute);
        if (attribute.equals("_finalizing")) {
            finalizing = value == PyBool.TRUE || Slots.truth(value);
            return PyNone.INSTANCE;
        }
        GenericAttributes.write(this, attribute, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        writeRefused(attribute);
        if (attribute.equals("_finalizing")) {
            // A member is not a getset, so this is not the `cannot be deleted` sentence: `member_set` is
            // handed a null value and refuses the numeric or char field by its own words, as a TypeError.
            throw Slots.typeError("can't delete numeric/char attribute");
        }
        GenericAttributes.delete(this, attribute);
        return PyNone.INSTANCE;
    }

    /** The two refusals of the two kinds of row, which are the same words whether written or deleted. */
    private void writeRefused(String attribute) {
        if (attribute.equals("__dict__")) {
            throw Slots.attributeError(
                    "attribute '__dict__' of '_io._IOBase' objects is not writable");
        }
        if (notWritable().contains(attribute)) {
            throw Slots.attributeError(
                    "attribute '%s' of '%s' objects is not writable",
                    attribute, getType().filedName());
        }
        if (readOnly().contains(attribute)) {
            throw Slots.attributeError("readonly attribute");
        }
    }
}
