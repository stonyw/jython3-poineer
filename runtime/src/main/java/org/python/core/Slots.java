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
 * subclass on the right, which CPython asks first and {@link #compare} does too. And a refusal of
 * those two-way slots is {@link PyNotImplemented}, never an error: only the protocol knows whether
 * both sides declined.
 *
 * <p>A third rule is visible in every message here: a type is named by {@link PyType#filedName()}, the
 * name it is written down as, and not by the bare half Python reads as {@code __name__}. For all but a
 * named sequence the two are one string, and for that one the difference is the whole of the report —
 * {@code 'sys.version_info' object has no attribute 'nope'}.
 */
public final class Slots {

    /**
     * What a slot answers when the type behind the value fills that slot with nothing.
     *
     * <p>It is deliberately not {@link PyNotImplemented}. For the slots that run the reflected-operator
     * protocol — the {@code nb_*} and {@code sq_compare} families and their reflected twins — CPython's
     * refusal <em>is</em> that value, and {@link #binary}, {@link #unary} and {@link #compare} read it as
     * one. Every other slot is a function that simply returns what it was given: {@code PyObject_GetItem}
     * hands back whatever {@code mp_subscript} answered, so {@code {"k": NotImplemented}["k"]} reads the
     * value, while the same answer from a type with no subscript at all is {@code 'dict' object is not
     * subscriptable}. Sharing one object between the two made the first look like the second.
     *
     * <p>Public because a value defined outside this package still has to decline: the Java interop's
     * wrappers override {@link PyObject#getAttr} and the container slots, and the only way one of them can
     * say "ask the next one" is to answer with this marker. A {@link PyNotImplemented} from such an
     * override is the value, exactly as it is for a dict holding it.
     */
    public static final PyObject DECLINED = new Declined();

    /** A marker with no Python-visible behaviour: a value reaching it from Python code is a bug. */
    private static final class Declined extends PyObject {

        @Override
        public String repr() {
            return "<slot declination>";
        }
    }

    /**
     * Whether an answer declines a question that runs the reflected protocol. Both currencies count:
     * {@link #DECLINED} for a Java slot with no body behind it, {@link PyNotImplemented} for a slot — built
     * in or written in Python — that answered with the value CPython itself reads as a decline.
     */
    static boolean declined(PyObject answer) {
        return answer == DECLINED || answer == PyNotImplemented.INSTANCE;
    }

    private Slots() {
    }

    /** {@code left op right}. */
    public static PyObject binary(BinaryOp op, PyObject left, PyObject right) {
        PyObject result = left.binaryOp(op, right);
        if (!declined(result)) {
            return result;
        }
        result = right.reflectedOp(op, left);
        if (!declined(result)) {
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
            if (!declined(inPlace)) {
                return inPlace;
            }
        }
        return binary(op, left, right);
    }

    public static PyObject unary(UnaryOp op, PyObject value) {
        PyObject result = value.unaryOp(op);
        if (!declined(result)) {
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
        if (!declined(result)) {
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
            if (!declined(result)) {
                return result;
            }
            reversedFirst = true;
        }
        PyObject result = left.richCompare(cmp, right);
        if (!declined(result)) {
            return result;
        }
        if (!reversedFirst) {
            result = right.richCompare(cmp.reflected(), left);
            if (!declined(result)) {
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
     * <p>The fallthrough order is CPython's {@code PyObject_IsTrue}, and it is what makes an empty tuple
     * false without {@code PyTuple} having to say so. An answer that is the value {@code NotImplemented} is
     * not a fallthrough: {@code notimplemented_bool} ({@code Objects/object.c:2365}) is the only entry of
     * {@code notimplemented_as_number}, so a bare {@code bool(NotImplemented)} — and every {@code if} that
     * reaches it — refuses in those words rather than borrowing the length.
     */
    public static boolean truth(PyObject value) {
        PyObject answer = value.truth();
        if (answer == DECLINED) {
            return !isEmpty(value);
        }
        if (answer instanceof PyBool bool) {
            return bool.getValue();
        }
        throw typeError("__bool__ should return bool, returned %s", answer.getType().filedName());
    }

    private static boolean isEmpty(PyObject value) {
        PyObject length = value.length();
        if (length == DECLINED) {
            return false;
        }
        return counted(length) == 0;
    }

    /** {@code len(x)} as a Python value, so a huge length stays a Python int. */
    public static PyObject length(PyObject value) {
        PyObject answer = value.length();
        if (answer == DECLINED) {
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
     *
     * <p>The order is the source's: {@code _PyNumber_Index} first, then {@code _PyLong_IsNegative}, then
     * {@code PyNumber_AsSsize_t}, so {@code __len__} returning {@code -(2 ** 70)} is refused for being
     * negative rather than for not fitting. What the last step names is {@code int}, always, because the
     * object it looks at is the number the conversion produced rather than the answer that arrived.
     */
    private static long counted(PyObject answer) {
        java.math.BigInteger value = Numbers.asIndex(answer);
        if (value.signum() < 0) {
            throw valueError("__len__() should return >= 0");
        }
        if (value.bitLength() > 63) {
            throw overflowError("cannot fit 'int' into an index-sized integer");
        }
        return value.longValue();
    }

    /**
     * {@code x[key]}.
     *
     * <p>A class is named by what it is rather than by its metatype, because {@code int[x]} has to say
     * {@code type 'int' is not subscriptable} while every other value says {@code '<type>' object is not
     * subscriptable} — the two wordings CPython keeps in {@code PyObject_GetItem} itself.
     *
     * <p>Whatever the subscript answers is handed back, including the value {@code NotImplemented}:
     * {@code PyObject_GetItem} never looks at the result, so {@code {"k": NotImplemented}["k"]} and a class
     * whose {@code __getitem__} returns it both read the value. Only a type with no {@code mp_subscript}
     * behind it declines, and that is {@link #DECLINED}. The same rule holds for the two writing halves.
     */
    public static PyObject getItem(PyObject container, PyObject key) {
        PyObject answer = container.getItem(key);
        if (answer != DECLINED) {
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
        if (answer != DECLINED) {
            return;
        }
        throw typeError(
                "'%s' object does not support item assignment", container.getType().filedName());
    }

    public static void delItem(PyObject container, PyObject key) {
        PyObject answer = container.delItem(key);
        if (answer != DECLINED) {
            return;
        }
        throw typeError(
                "'%s' object does not support item deletion", container.getType().filedName());
    }

    /**
     * {@code item in container}, as {@code CONTAINS_OP} wants it: both sides get a chance, and a
     * refusal for a value that has neither {@code __contains__} nor an iteration protocol is an
     * error rather than a false.
     *
     * <p>An answer that is not a bool is tested for truth rather than read as a decline, which is what
     * {@code slot_sq_contains} does with whatever {@code __contains__} handed back — and the value {@code
     * NotImplemented} is truth-tested like anything else, so {@code 1 in x} says the boolean-context refusal
     * where {@code x.__contains__(1)} says the value.
     */
    public static boolean contains(PyObject container, PyObject item) {
        PyObject answer = container.contains(item);
        if (answer instanceof PyBool bool) {
            return bool.getValue();
        }
        if (answer != DECLINED) {
            return truth(answer);
        }
        if (container.iterator() == DECLINED) {
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
        if (answer == DECLINED) {
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
        if (answer == DECLINED) {
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
        if (answer != DECLINED) {
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
        // The last question a read asks is the one CPython's generic read asks first for a value whose own
        // type keeps a dictionary: the MRO is walked and the first entry under the name is run through its
        // descriptor, which is how a built-in value answers `__doc__` — a plain string its type files — and
        // how the members a type publishes but this runtime reads from a C field anyway are answered twice
        // the same way.
        PyObject published = TypeDict.read(target, name);
        if (published != DECLINED) {
            return published;
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
     *
     * <p>The last step is the same one {@link #getAttr} takes, because it is the same lookup with a
     * different refusal: {@code functools_wraps} asks a wrapped value for its four names with this
     * function, and one of those four — {@code __doc__} — is not a member of any value but a sentence its
     * type files. So {@code staticmethod(5).__dict__} holds an {@code int}'s {@code __doc__} and nothing
     * else, which needs the class dictionary to be walked here as much as it is in the complaining read.
     */
    public static PyObject findAttr(PyObject target, String name) {
        PyObject answer = target.getAttr(name);
        if (answer != DECLINED) {
            return answer;
        }
        if (name.equals(ClassAttribute.NAME)) {
            return ClassAttribute.read(target);
        }
        PyObject published = TypeDict.read(target, name);
        return published == DECLINED ? null : published;
    }

    /**
     * {@code target.name = value}: CPython's {@code PyObject_SetAttr}, which is the value's own {@code
     * tp_setattro} and nothing else.
     *
     * <p>A value that writes no attributes of its own — a Java object reached through the interop layer —
     * falls back to {@link GenericAttributes}, so the one algorithm that knows the difference between a
     * read-only member and a missing one answers for it too. Every runtime type writes its own attributes
     * or uses that algorithm directly, which is why {@code object.__setattr__} is not routed through here:
     * it must not consult the {@code __setattr__} the value's class declared, or {@code
     * super().__setattr__(name, value)} would only come back round to the same method.
     */
    public static void setAttr(PyObject target, String name, PyObject value) {
        if (name.equals(ClassAttribute.NAME) && !target.filedByItsClass(name)) {
            ClassAttribute.write(target, value);
            return;
        }
        PyObject answer = target.setAttr(name, value);
        if (answer != DECLINED) {
            return;
        }
        GenericAttributes.write(target, name, value);
    }

    /** {@code del target.name}, which a type may refuse even when it accepts an assignment. */
    public static void delAttr(PyObject target, String name) {
        if (name.equals(ClassAttribute.NAME) && !target.filedByItsClass(name)) {
            ClassAttribute.delete();
            return;
        }
        PyObject answer = target.delAttr(name);
        if (answer != DECLINED) {
            return;
        }
        GenericAttributes.delete(target, name);
    }

    /** Whether {@code value} can be iterated, which is what {@code *} and {@code in} ask before failing. */
    public static boolean isIterable(PyObject value) {
        return value.iterator() != DECLINED;
    }

    /**
     * Whether {@code value} may be merged as a mapping.
     *
     * CPython answers this with a type flag, {@code Py_TPFLAGS_MAPPING}, which is set when a class
     * declares {@code keys} or inherits it from {@code dict}. A pattern match asks that flag directly and
     * gets CPython's answer — see {@link PyType#MAPPING}; what is reachable here are the two types that set
     * the flag beside {@code dict} itself, so a window over a type's namespace unpacks as its mapping does
     * and a class that merely declares {@code keys} still does not.
     */
    public static boolean isMapping(PyObject value) {
        return value instanceof PyDict || value instanceof PyMappingProxy;
    }

    /**
     * Whether {@code value} may be called, which is what {@code callable()} answers and what any caller
     * of {@link #call} is about to find out the hard way.
     *
     * <p>One question, read by both the builtin and the interop layer: a caller who is told
     * {@code callable(x)} is true and then gets a {@code TypeError} from a Java parameter has been lied
     * to. The answer is the value's own, because CPython reads {@code tp_call} off the type and a type
     * fills that slot by implementing it — see {@link PyObject#isCallable}. A class Python wrote joins
     * only when it declares {@code __call__}, which is CPython's rule too: an instance of a class that
     * defines no {@code __call__} has no call slot, and {@code callable(Marker())} is false. Of the three
     * descriptor builtins only a {@code staticmethod} answers calls, because CPython made it callable in
     * 3.11 and left {@code classmethod} and {@code property} out: {@code callable(C.__dict__["m"])} is
     * false. An exception <em>instance</em> is not callable either, which is what makes
     * {@code callable(ValueError("x"))} false and lets {@code split(ValueError("x"))} refuse the matcher
     * before trying to call it.
     */
    public static boolean isCallable(PyObject value) {
        return value.isCallable();
    }

    /**
     * {@code callable(*args, **kwNames)}: the calling convention the interpreter uses, in which the
     * keyword values are the trailing entries of {@code args}.
     *
     * <p>Nothing is decided here. A value that has no call slot refuses in {@link PyObject#call}, which
     * leaves {@link PyNotImplemented} free to be what it is — the answer a comparison gives when it does
     * not know how to compare.
     */
    public static PyObject call(PyObject callable, PyObject[] args, PyTuple kwNames) {
        return callable.call(args, kwNames);
    }

    public static String str(PyObject value) {
        return value.str();
    }

    public static String repr(PyObject value) {
        return value.repr();
    }

    /**
     * How a refusal of an argument names the value it refused: the type's name, except for {@code None}.
     *
     * <p>{@code _PyArg_BadArgument} and {@code converterr} both write {@code None} where the rest of the
     * interpreter writes {@code NoneType}, because those two sentences are about the argument rather than
     * about a type: {@code b''.center(5, None)} says {@code must be a byte string of length 1, not None}
     * while {@code None in b''} says {@code argument should be integer or bytes-like object, not 'NoneType'}.
     * The name printed is the type's {@code tp_name}, so a named sequence is called by its dotted name.
     */
    static String argumentName(PyObject value) {
        return value == PyNone.INSTANCE ? "None" : value.getType().filedName();
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

    /**
     * {@code OSError} with a sentence and no errno, which is what a descriptor that simply fails reports.
     *
     * <p>The reference gets its errno from the system call that failed; this runtime reads through Java,
     * which hands back an exception rather than a number, so the sentence carries only what went wrong.
     * A stream that is open but going the wrong way does not report here — see {@link
     * #unsupportedOperation}.
     */
    public static PyRaise osError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.OS_ERROR, text(format, args));
    }

    /**
     * {@code io.UnsupportedOperation} with a sentence and no errno: the refusal of a stream asked to do
     * what its own kind cannot.
     *
     * <p>{@code _PyIO_raise_error} and {@code _unsupported} in {@code _io} raise this for {@code
     * "not readable"}, {@code "underlying stream is not seekable"} and the bare {@code "truncate"} a
     * read-only buffer answers. It is a subclass of both {@code OSError} and {@code ValueError}, so a
     * program that caught either by those names still catches it — which is how the reference's own test
     * suite reads these three sentences.
     */
    public static PyRaise unsupportedOperation(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.UNSUPPORTED_OPERATION, text(format, args));
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
     * {@code make_encode_exception} ({@code Objects/unicodeobject.c:7279}): the leaf called with the range a
     * codec gave up on.
     *
     * <p>Five fields, not one sentence: {@code UnicodeEncodeError_str} writes the message out of the
     * encoding, the object and the range, so a value that carries only a message reads back the empty
     * string. {@code start} and {@code end} count code points, and a run of consecutive characters the
     * codec cannot write is one range rather than the first character of it.
     */
    public static PyRaise unicodeEncode(
            String encoding, String text, long start, long end, String reason) {
        return PyBaseException.unicodeError(
                ExceptionTypes.UNICODE_ENCODE_ERROR,
                PyStr.of(encoding),
                PyStr.of(text),
                start,
                end,
                PyStr.of(reason));
    }

    /** {@code PyUnicodeDecodeError_Create}: the same five fields, with the octets in place of the text. */
    public static PyRaise unicodeDecode(
            String encoding, byte[] octets, long start, long end, String reason) {
        return PyBaseException.unicodeError(
                ExceptionTypes.UNICODE_DECODE_ERROR,
                PyStr.of(encoding),
                PyBytes.of(octets),
                start,
                end,
                PyStr.of(reason));
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
     * {@code PyErr_BadInternalCall} and {@code intern_strings}: the two refusals of a C API that says the
     * caller handed it something no caller should.
     *
     * <p>The reference names the C file and line the call came from, which is where the difference between
     * the two builds shows: {@code jy3} says only what was refused. Both of them are unreachable from
     * Python except by building a code object by hand, which is the one thing that reads this back.
     */
    public static PyRaise systemError(String message) {
        return PyRaise.raise(ExceptionTypes.SYSTEM_ERROR, message);
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
     * {@code BufferError}: what the buffer protocol answers a request it cannot grant with.
     *
     * <p>It is a sibling of {@code Exception} rather than of {@code ValueError}, which is why a read of
     * {@code b''.__buffer__(1)} is refused as this and not as a number out of range.
     */
    public static PyRaise bufferError(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.BUFFER_ERROR, text(format, args));
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
