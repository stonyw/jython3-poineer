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
 * subclass on the right, which CPython asks first and {@link #compare} does too. And a refusal is
 * {@link PyNotImplemented}, never an error: only the protocol knows whether both sides declined.
 *
 * <p>A third rule is visible in every message here: a type is named by {@link PyType#filedName()}, the
 * name it is written down as, and not by the bare half Python reads as {@code __name__}. For all but a
 * named sequence the two are one string, and for that one the difference is the whole of the report —
 * {@code 'sys.version_info' object has no attribute 'nope'}.
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
                op.symbol(), left.getType().filedName(), right.getType().filedName());
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
                "bad operand type for unary %s: '%s'", op.symbol(), value.getType().filedName());
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
        throw typeError("bad operand type for abs(): '%s'", value.getType().filedName());
    }

    /**
     * {@code left cmp right}.
     *
     * When both sides refuse an equality test the answer is identity, which is CPython's fallback and
     * not an optimisation: {@code [] == []} is False because {@code list.__eq__} answered, while
     * {@code object() == object()} reaches here. For an ordering test there is no fallback — refusing
     * twice is a {@code TypeError}.
     *
     * <p>CPython asks the right operand first when its type is a proper subclass of the left's, because
     * a subclass is the more specific answer to the question. That priority is only worth reproducing
     * for a class Python wrote: a built-in type's comparison is a Java method rather than a looked-up
     * method, and swapping it in front of {@code int}'s own answer would change {@code 1 == True} from
     * the arithmetic CPython does to a method call it never makes.
     */
    public static PyObject compare(CompareOp cmp, PyObject left, PyObject right) {
        boolean reversedFirst = false;
        if (right instanceof PyInstance
                && right.getType() != left.getType()
                && right.getType().matches(left.getType())) {
            PyObject result = right.richCompare(cmp.reflected(), left);
            if (result != PyNotImplemented.INSTANCE) {
                return result;
            }
            reversedFirst = true;
        }
        PyObject result = left.richCompare(cmp, right);
        if (result != PyNotImplemented.INSTANCE) {
            return result;
        }
        if (!reversedFirst) {
            result = right.richCompare(cmp.reflected(), left);
            if (result != PyNotImplemented.INSTANCE) {
                return result;
            }
        }
        if (cmp == CompareOp.EQ) {
            return PyBool.of(left == right);
        }
        if (cmp == CompareOp.NE) {
            return PyBool.of(left != right);
        }
        throw typeError(
                "'%s' not supported between instances of '%s' and '%s'",
                cmp.symbol(), left.getType().filedName(), right.getType().filedName());
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
        throw typeError("__bool__ should return bool, returned %s", answer.getType().filedName());
    }

    private static boolean isEmpty(PyObject value) {
        PyObject length = value.length();
        if (length == PyNotImplemented.INSTANCE) {
            return false;
        }
        return counted(length) == 0;
    }

    /** {@code len(x)} as a Python value, so a huge length stays a Python int. */
    public static PyObject length(PyObject value) {
        PyObject answer = value.length();
        if (answer == PyNotImplemented.INSTANCE) {
            throw typeError("object of type '%s' has no len()", value.getType().filedName());
        }
        return PyInt.of(counted(answer));
    }

    /**
     * A {@code __len__} answer reduced to a size, which is what {@code slot_sq_length} does with a
     * Python-level one.
     *
     * <p>The three refusals are CPython's and each is a different exception: an answer that is not an
     * integer at all is a {@code TypeError} about interpretation, one too wide for a machine word is an
     * {@code OverflowError}, and a negative one is a {@code ValueError} — a length is a count, and only
     * the last of those is about the number rather than its type.
     */
    private static long counted(PyObject answer) {
        java.math.BigInteger value = Numbers.asInteger(answer);
        if (value == null) {
            throw typeError(
                    "'%s' object cannot be interpreted as an integer", answer.getType().filedName());
        }
        if (value.bitLength() >= 64) {
            throw overflowError("cannot fit 'int' into an index-sized integer");
        }
        long length = value.longValue();
        if (length < 0) {
            throw valueError("__len__() should return >= 0");
        }
        return length;
    }

    /**
     * {@code x[key]}.
     *
     * <p>A class is named by what it is rather than by its metatype, because {@code int[x]} has to say
     * {@code type 'int' is not subscriptable} while every other value says {@code '<type>' object is not
     * subscriptable} — the two wordings CPython keeps in {@code PyObject_GetItem} itself.
     */
    public static PyObject getItem(PyObject container, PyObject key) {
        PyObject answer = container.getItem(key);
        if (answer != PyNotImplemented.INSTANCE) {
            return answer;
        }
        if (container instanceof PyType kind) {
            throw typeError("type '%s' is not subscriptable", kind.filedName());
        }
        throw typeError(
                "'%s' object is not subscriptable", container.getType().filedName());
    }

    public static void setItem(PyObject container, PyObject key, PyObject value) {
        PyObject answer = container.setItem(key, value);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw typeError(
                "'%s' object does not support item assignment", container.getType().filedName());
    }

    public static void delItem(PyObject container, PyObject key) {
        PyObject answer = container.delItem(key);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw typeError(
                "'%s' object does not support item deletion", container.getType().filedName());
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
                    container.getType().filedName());
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
            throw typeError("'%s' object is not iterable", iterable.getType().filedName());
        }
        if (answer instanceof PyIterator iterator) {
            return iterator;
        }
        // A __iter__ that returns something that is not an iterator is CPython's next TypeError. That
        // question is the same one asIterator answers: a value of a class that declares __next__ *is* an
        // iterator, however ordinary a PyInstance it looks like from Java.
        PyIterator iterator = asIterator(answer);
        if (iterator != null) {
            return iterator;
        }
        throw typeError("iter() returned non-iterator of type '%s'", answer.getType().filedName());
    }

    /**
     * The iterator an object answers with, or null when it is not one.
     *
     * <p>CPython's test is {@code PyIter_Check}: the type has to have an iteration slot, and for a class
     * Python wrote that means a {@code __next__}. A {@link PyIterator} is the runtime's own answer to the
     * same question, so both are accepted. The two callers word their refusal differently — {@code iter()}
     * complains about what it was handed, {@code next()} about what it was asked to drive — so the choice
     * of message stays with them.
     */
    public static PyIterator asIterator(PyObject value) {
        if (value instanceof PyIterator iterator) {
            return iterator;
        }
        if (value instanceof PyInstance instance) {
            return instance.declaredIterator();
        }
        return null;
    }

    /**
     * {@code GET_YIELD_FROM_ITER}: the iterator a delegation stands on, kept as the value it is.
     *
     * <p>Not {@link #iterator}: {@code yield from} puts the answer of {@code __iter__} straight on the
     * frame's value stack and asks <em>that</em> object for every step, because {@code gi_yieldfrom} names
     * it and because {@code send}, {@code throw} and {@code close} are called on it. Wrapping a class
     * written in Python in the runtime's own driver would lose both. The two refusals are {@code iter()}'s,
     * since this is {@code PyObject_GetIter} with the result kept rather than converted.
     */
    public static PyObject getIter(PyObject iterable) {
        PyObject answer = iterable.iterator();
        if (answer == PyNotImplemented.INSTANCE) {
            throw typeError("'%s' object is not iterable", iterable.getType().filedName());
        }
        if (asIterator(answer) != null) {
            return answer;
        }
        throw typeError("iter() returned non-iterator of type '%s'", answer.getType().filedName());
    }

    /** {@code getattr(x, name)} for the builtin types. */
    public static PyObject getAttr(PyObject target, String name) {
        PyObject answer = target.getAttr(name);
        if (answer != PyNotImplemented.INSTANCE) {
            return answer;
        }
        if (name.equals(ClassAttribute.NAME)) {
            return ClassAttribute.read(target);
        }
        // CPython names a class by what it is, because the reader of `str.nonsense` has to be told the
        // attribute was looked up on the type rather than on one of its values.
        if (target instanceof PyType kind) {
            throw failedRead(
                    target, name, "type object '%s' has no attribute '%s'", kind.filedName(), name);
        }
        // A module names itself, for the same reason again: `sys.excepthook` after the name has been
        // deleted is a question about which namespace was asked, and module_getattro answers it with the
        // module's own __name__ rather than with the type every module shares.
        if (target instanceof PyModule module) {
            throw failedRead(
                    target, name, "module '%s' has no attribute '%s'", module.name(), name);
        }
        throw failedRead(
                target, name, "'%s' object has no attribute '%s'", target.getType().filedName(), name);
    }

    /**
     * CPython's {@code PyObject_GetOptionalAttr}: the member under {@code name}, or null where there is
     * none — and no complaint, which is the whole difference from {@link #getAttr}.
     *
     * <p>A protocol that is offered rather than required is read this way. A delegation asks its delegate
     * for {@code throw} and for {@code close} and takes the absence of either as the answer: an iterator
     * that cannot be thrown into is one the exception goes back to the delegating frame for, and one with
     * no close needs no closing.
     */
    public static PyObject findAttr(PyObject target, String name) {
        PyObject answer = target.getAttr(name);
        if (answer != PyNotImplemented.INSTANCE) {
            return answer;
        }
        return name.equals(ClassAttribute.NAME) ? ClassAttribute.read(target) : null;
    }

    public static void setAttr(PyObject target, String name, PyObject value) {
        if (name.equals(ClassAttribute.NAME) && !target.filedByItsClass(name)) {
            ClassAttribute.write(target, value);
            return;
        }
        PyObject answer = target.setAttr(name, value);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw attributeError(
                "'%s' object has no attribute '%s'", target.getType().filedName(), name);
    }

    /** {@code del target.name}, which a type may refuse even when it accepts an assignment. */
    public static void delAttr(PyObject target, String name) {
        if (name.equals(ClassAttribute.NAME) && !target.filedByItsClass(name)) {
            ClassAttribute.delete();
            return;
        }
        PyObject answer = target.delAttr(name);
        if (answer != PyNotImplemented.INSTANCE) {
            return;
        }
        throw attributeError(
                "'%.200s' object has no attribute '%s'", target.getType().filedName(), name);
    }

    /** Whether {@code value} can be iterated, which is what {@code *} and {@code in} ask before failing. */
    public static boolean isIterable(PyObject value) {
        return value.iterator() != PyNotImplemented.INSTANCE;
    }

    /**
     * Whether {@code value} may be merged as a mapping.
     *
     * CPython answers this with a type flag, {@code Py_TPFLAGS_MAPPING}, which is set when a class
     * declares {@code keys} or inherits it from {@code dict}. A pattern match asks that flag directly and
     * gets CPython's answer — see {@link PyType#MAPPING}; this question is still a dict, because a merge
     * reaches it only through code that hands {@code **} a dict, and CPython's own refusal of the frame
     * that also carries the flag says the same words this one does.
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
     * to. A class Python wrote joins it only when it declares {@code __call__}, which is CPython's rule
     * too: an instance of a class that defines no {@code __call__} has no call slot, and
     * {@code callable(Marker())} is false. Of the three descriptor builtins only a {@code staticmethod}
     * object is on the list, because CPython made it callable in 3.11 and left {@code classmethod} and
     * {@code property} out: {@code callable(C.__dict__["m"])} is false. An exception <em>instance</em> is
     * not on it either, which is what makes {@code callable(ValueError("x"))} false and lets
     * {@code split(ValueError("x"))} refuse the matcher before trying to call it.
     */
    public static boolean isCallable(PyObject value) {
        return value instanceof PyType
                || value instanceof PyMethodDescriptor
                || value instanceof PyFunction
                || value instanceof PyStaticMethod
                || value instanceof PyNativeFunction
                || value instanceof PyBoundMethod
                || (value instanceof PyInstance instance
                        && Specials.defines(instance.getType(), "__call__"));
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
        throw typeError("'%s' object is not callable", callable.getType().filedName());
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

    /**
     * {@code AttributeError} for a read that could not find the name, with the two members CPython hangs on
     * it before the report is written.
     *
     * <p>{@code _PyObject_SetAttributeErrorContext} is called by every route that fails a member read —
     * {@code PyObject_GetAttr}, {@code _PyObject_GetMethod}, the generic read, and the refusal of a write
     * to an object with no dictionary — and never by the routes that complain about a member that exists
     * and will not be written. Which of those a failure is decides which of jy3's raises arrive named.
     */
    public static PyRaise failedRead(PyObject target, String name, String format, Object... args) {
        PyRaise failure = attributeError(format, args);
        failure.value().asFailedRead(target, name);
        return failure;
    }

    /**
     * {@code NameError} for a name that was not there, carrying the name it missed the way {@code
     * _PyEval_FormatExcCheckArg} does.
     */
    public static PyRaise nameError(String name) {
        PyRaise failure =
                PyRaise.raise(ExceptionTypes.NAME_ERROR, "name '" + name + "' is not defined");
        failure.value().asMissingName(name);
        return failure;
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
