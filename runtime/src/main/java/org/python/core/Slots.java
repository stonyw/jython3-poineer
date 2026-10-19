package org.python.core;

/**
 * The slot protocols: the rules for who gets asked, in what order, and what the message says when
 * nobody answers.
 *
 * Every method here is a translation of CPython's {@code Objects/typeobject.c} of the same name,
 * kept out of {@link PyObject} because the order of attempts is the part worth reading once, and
 * because a value's own slot methods ({@link PyObject#binaryOp} and friends) should stay busy
 * answering for their type instead of negotiating with the other operand.
 *
 * Two rules from CPython are reproduced rather than approximated. The left operand is asked first
 * and the right operand's reflected slot only after a refusal — with the exception of a proper
 * subclass on the right, which CPython asks first, and which cannot happen until user-defined classes
 * exist (M3). And a refusal is {@link PyNotImplemented}, never an error: only the protocol knows
 * whether both sides declined.
 */
public final class Slots {

    private Slots() {
    }

    /** {@code left op right}. */
    public static PyObject binary(BinaryOp op, PyObject left, PyObject right) {
        PyObject result = left.binaryOp(op, right);
        if (result != PyNotImplemented.INSTANCE) {
            return result;
        }
        result = right.reflectedOp(op, left);
        if (result != PyNotImplemented.INSTANCE) {
            return result;
        }
        throw typeError(
                "unsupported operand type(s) for %s: '%s' and '%s'",
                op.symbol(), left.getType().name(), right.getType().name());
    }

    /**
     * {@code left op= right}, which is {@link #binary} unless {@code augmented} says the target may
     * change in place. {@code left} is then replaced by whatever this returns — CPython does the same,
     * and a type that mutates returns itself.
     */
    public static PyObject augmented(BinaryOp op, PyObject left, PyObject right, boolean augmented) {
        if (augmented) {
            PyObject inPlace = left.inplaceOp(op, right);
            if (inPlace != PyNotImplemented.INSTANCE) {
                return inPlace;
            }
        }
        return binary(op, left, right);
    }

    public static PyObject unary(UnaryOp op, PyObject value) {
        PyObject result = value.unaryOp(op);
        if (result != PyNotImplemented.INSTANCE) {
            return result;
        }
        throw typeError(
                "bad operand type for unary %s: '%s'", op.symbol(), value.getType().name());
    }

    /**
     * {@code abs(x)}.
     *
     * It is a slot ({@code nb_absolute}) but not an operator Python spells with a symbol, so its
     * refusal reads differently from {@link #unary}'s — {@code bad operand type for abs(): 'str'} —
     * which is why it does not share that method's message.
     */
    public static PyObject absolute(PyObject value) {
        PyObject result = value.unaryOp(UnaryOp.ABSOLUTE);
        if (result != PyNotImplemented.INSTANCE) {
            return result;
        }
        throw typeError("bad operand type for abs(): '%s'", value.getType().name());
    }

    /**
     * {@code left cmp right}.
     *
     * When both sides refuse an equality test the answer is identity, which is CPython's fallback and
     * not an optimisation: {@code [] == []} is False because {@code list.__eq__} answered, while
     * {@code object() == object()} reaches here. For an ordering test there is no fallback — refusing
     * twice is a {@code TypeError}.
     */
    public static PyObject compare(CompareOp cmp, PyObject left, PyObject right) {
        PyObject result = left.richCompare(cmp, right);
        if (result != PyNotImplemented.INSTANCE) {
            return result;
        }
        result = right.richCompare(cmp.reflected(), left);
        if (result != PyNotImplemented.INSTANCE) {
            return result;
        }
        if (cmp == CompareOp.EQ) {
            return PyBool.of(left == right);
        }
        if (cmp == CompareOp.NE) {
            return PyBool.of(left != right);
        }
        throw typeError(
                "'%s' not supported between instances of '%s' and '%s'",
                cmp.symbol(), left.getType().name(), right.getType().name());
    }

    /** {@code left cmp right} already reduced to a Java boolean, as an {@code if} needs. */
    public static boolean compareTrue(CompareOp cmp, PyObject left, PyObject right) {
        return truth(compare(cmp, left, right));
    }

    /**
     * {@code bool(x)}: {@code __bool__}, then {@code __len__}, then true.
     *
     * The fallthrough order is CPython's {@code PyObject_IsTrue}, and it is what makes an empty tuple
     * false without {@code PyTuple} having to say so.
     */
    public static boolean truth(PyObject value) {
        PyObject answer = value.truth();
        if (answer == PyNotImplemented.INSTANCE) {
            return !isEmpty(value);
        }
        if (answer instanceof PyBool bool) {
            return bool.getValue();
        }
        throw typeError("__bool__ should return bool, not '%s'", answer.getType().name());
    }

    private static boolean isEmpty(PyObject value) {
        PyObject length = value.length();
        if (length == PyNotImplemented.INSTANCE) {
            return false;
        }
        if (!(length instanceof PyInt i)) {
            throw typeError("'%.200s' object representing its length is not an int",
                    length.getType().name());
        }
        if (!i.fitsLong()) {
            throw overflowError("cannot fit 'int' into an index-sized integer");
        }
        long v = i.getValue().longValue();
        if (v < 0) {
            throw valueError("__len__() should return >= 0");
        }
        return v == 0;
    }

    /** {@code len(x)} as a Python value, so a huge length stays a Python int. */
    public static PyObject length(PyObject value) {
        PyObject answer = value.length();
        if (answer == PyNotImplemented.INSTANCE) {
            throw typeError("object of type '%s' has no len()", value.getType().name());
        }
        return answer;
    }

    /** {@code x[key]}. */
    public static PyObject getItem(PyObject container, PyObject key) {
        PyObject answer = container.getItem(key);
        if (answer != PyNotImplemented.INSTANCE) {
            return answer;
        }
        throw typeError(
                "'%s' object is not subscriptable", container.getType().name());
    }

    public static void setItem(PyObject container, PyObject key, PyObject value) {
        PyObject answer = container.setItem(key, value);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw typeError(
                "'%s' object does not support item assignment", container.getType().name());
    }

    public static void delItem(PyObject container, PyObject key) {
        PyObject answer = container.delItem(key);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw typeError(
                "'%s' object does not support item deletion", container.getType().name());
    }

    /**
     * {@code item in container}, as {@code CONTAINS_OP} wants it: both sides get a chance, and a
     * refusal for a value that has neither {@code __contains__} nor an iteration protocol is an
     * error rather than a false.
     */
    public static boolean contains(PyObject container, PyObject item) {
        PyObject answer = container.contains(item);
        if (answer instanceof PyBool bool) {
            return bool.getValue();
        }
        if (answer != PyNotImplemented.INSTANCE) {
            // A __contains__ that answers with a non-bool is still tested for truth, as in CPython.
            return truth(answer);
        }
        if (container.iterator() == PyNotImplemented.INSTANCE) {
            // 3.14's wording for `1 in 2`, which is not the same message as `iter(1)`: containment
            // asks for a container, and gets an answer neither way.
            throw typeError(
                    "argument of type '%s' is not a container or iterable",
                    container.getType().name());
        }
        PyIterator iterator = iterator(container);
        while (true) {
            PyObject found = iterator.nextOrNull();
            if (found == null) {
                return false;
            }
            if (compareTrue(CompareOp.EQ, found, item)) {
                return true;
            }
        }
    }

    /** {@code iter(x)}. */
    public static PyIterator iterator(PyObject iterable) {
        PyObject answer = iterable.iterator();
        if (answer == PyNotImplemented.INSTANCE) {
            throw typeError("'%s' object is not iterable", iterable.getType().name());
        }
        if (answer instanceof PyIterator iterator) {
            return iterator;
        }
        // A __iter__ that returns something that is not an iterator is CPython's next TypeError.
        throw typeError("iter() returned non-iterator of type '%s'", answer.getType().name());
    }

    /** {@code getattr(x, name)} for the builtin types. */
    public static PyObject getAttr(PyObject target, String name) {
        PyObject answer = target.getAttr(name);
        if (answer != PyNotImplemented.INSTANCE) {
            return answer;
        }
        // CPython names a class by what it is, because the reader of `str.nonsense` has to be told the
        // attribute was looked up on the type rather than on one of its values.
        if (target instanceof PyType kind) {
            throw attributeError("type object '%s' has no attribute '%s'", kind.name(), name);
        }
        throw attributeError(
                "'%s' object has no attribute '%s'", target.getType().name(), name);
    }

    public static void setAttr(PyObject target, String name, PyObject value) {
        PyObject answer = target.setAttr(name, value);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw attributeError(
                "'%s' object has no attribute '%s'", target.getType().name(), name);
    }

    /** {@code del target.name}, which a type may refuse even when it accepts an assignment. */
    public static void delAttr(PyObject target, String name) {
        PyObject answer = target.delAttr(name);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw attributeError(
                "'%.200s' object has no attribute '%s'", target.getType().name(), name);
    }

    /** Whether {@code value} can be iterated, which is what {@code *} and {@code in} ask before failing. */
    public static boolean isIterable(PyObject value) {
        return value.iterator() != PyNotImplemented.INSTANCE;
    }

    /**
     * Whether {@code value} may be merged as a mapping.
     *
     * CPython answers this with a type flag, {@code Py_TPFLAGS_MAPPING}, which is set when a class
     * declares {@code keys} or inherits it from {@code dict}. Flags belong to the class system at M3,
     * so until then the only mapping this runtime recognises is a dict — which is what {@code **} in a
     * call and {@code {**a}} actually receive from code CPython compiled.
     */
    public static boolean isMapping(PyObject value) {
        return value instanceof PyDict;
    }

    /**
     * Whether {@code value} may be called, which is what {@code callable()} answers and what any caller
     * of {@link #call} is about to find out the hard way.
     *
     * <p>One list, read by both the builtin and the interop layer: a caller who is told
     * {@code callable(x)} is true and then gets a {@code TypeError} from a Java parameter has been lied
     * to. Every callable value in this runtime is one of these Java classes; a user-defined class and an
     * instance with {@code __call__} join the list when the class system arrives (M3).
     */
    public static boolean isCallable(PyObject value) {
        return value instanceof PyType
                || value instanceof PyMethodDescriptor
                || value instanceof PyFunction
                || value instanceof PyNativeFunction
                || value instanceof PyBoundMethod
                || value instanceof PyBaseException;
    }

    /**
     * {@code callable(*args, **kwNames)}: the calling convention the interpreter uses, in which the
     * keyword values are the trailing entries of {@code args}.
     */
    public static PyObject call(PyObject callable, PyObject[] args, PyTuple kwNames) {
        PyObject answer = callable.call(args, kwNames);
        if (answer != PyNotImplemented.INSTANCE) {
            return answer;
        }
        throw typeError("'%s' object is not callable", callable.getType().name());
    }

    public static String str(PyObject value) {
        return value.str();
    }

    public static String repr(PyObject value) {
        return value.repr();
    }

    // --- raising, with CPython's wording -------------------------------------------------

    public static PyRaise typeError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.TYPE_ERROR, text(format, args));
    }

    public static PyRaise valueError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.VALUE_ERROR, text(format, args));
    }

    public static PyRaise indexError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.INDEX_ERROR, text(format, args));
    }

    public static PyRaise keyError(PyObject missing) {
        return PyRaise.of(new PyBaseException(ExceptionTypes.KEY_ERROR, new PyObject[] {missing}));
    }

    public static PyRaise attributeError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.ATTRIBUTE_ERROR, text(format, args));
    }

    public static PyRaise nameError(String name) {
        return PyRaise.raise(
                ExceptionTypes.NAME_ERROR, "name '" + name + "' is not defined");
    }

    public static PyRaise importError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.IMPORT_ERROR, text(format, args));
    }

    /**
     * A codec name with no codec behind it.
     *
     * CPython's {@code LookupError} from {@code codecs.lookup} carries no name, unlike
     * {@code KeyError}, so it takes a message here.
     */
    public static PyRaise lookupError(String message) {
        return PyRaise.raise(ExceptionTypes.LOOKUP_ERROR, message);
    }

    /**
     * {@code UnicodeEncodeError} and {@code UnicodeDecodeError}.
     *
     * CPython builds these from five arguments — encoding, object, start, end, reason — and joins them
     * in {@code __str__}. The five-argument form needs the codec to report a range, which belongs to
     * the codec registry at M4; until then the codec itself formats the sentence, which is what a
     * script reads either way. The class is right, so {@code except UnicodeEncodeError} works.
     */
    public static PyRaise unicodeEncode(String message) {
        return PyRaise.raise(ExceptionTypes.UNICODE_ENCODE_ERROR, message);
    }

    public static PyRaise unicodeDecode(String message) {
        return PyRaise.raise(ExceptionTypes.UNICODE_DECODE_ERROR, message);
    }

    public static PyRaise zeroDivision(String message) {
        return PyRaise.raise(ExceptionTypes.ZERO_DIVISION_ERROR, message);
    }

    public static PyRaise overflowError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.OVERFLOW_ERROR, text(format, args));
    }

    public static PyRaise runtimeError(String message) {
        return PyRaise.raise(ExceptionTypes.RUNTIME_ERROR, message);
    }

    /**
     * An allocation Java would have failed at anyway, raised before asking for it.
     *
     * CPython's {@code MemoryError} comes out of a failed {@code malloc} and carries no message; an
     * integer this runtime declines to build is the same outcome reached by a different route, and is
     * reported with the same empty exception rather than a sentence invented to fill the gap.
     */
    public static PyRaise memoryError() {
        return PyRaise.of(new PyBaseException(ExceptionTypes.MEMORY_ERROR, new PyObject[0]));
    }

    /** A feature this slice does not cover yet, reported instead of quietly half-done. */
    public static PyRaise notImplemented(String what) {
        return PyRaise.raise(ExceptionTypes.NOT_IMPLEMENTED_ERROR, what + " is not implemented");
    }

    /**
     * {@code %s}/{@code %d} formatting with Python's type names already quoted, so a message reads
     * like CPython's rather than like a Java stack trace. The one exception is {@code %.200s}, which
     * CPython uses to bound a name; it is accepted and not truncated, because names here come from
     * the code object.
     */
    private static String text(String format, Object... args) {
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            converted[i] = args[i] instanceof PyObject value ? value.repr() : args[i];
        }
        return format.replace("%.200s", "%s").formatted(converted);
    }
}
