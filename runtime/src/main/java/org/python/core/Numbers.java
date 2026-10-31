package org.python.core;

import java.math.BigInteger;

/**
 * Conversions between the numeric types, and the index tests that guard them.
 *
 * These are the questions the tower keeps asking — is this an integer, is this a real number, is this
 * usable as an index — and they are answered here once so that {@link PyInt}, {@link PyFloat} and
 * {@link PyComplex} can each decline in a single line what they cannot compute.
 */
final class Numbers {

    private Numbers() {
    }

    /** The value of {@code o} as an integer, or null when it is not an {@code int} or {@code bool}. */
    static BigInteger asInteger(PyObject o) {
        if (o instanceof PyInt i) {
            return i.getValue();
        }
        if (o instanceof PyBool b) {
            return b.getValue() ? BigInteger.ONE : BigInteger.ZERO;
        }
        return null;
    }

    /** The value of {@code o} as a real number, or null when it is not an int, bool or float. */
    static Double asReal(PyObject o) {
        if (o instanceof PyFloat f) {
            return f.getValue();
        }
        BigInteger integer = asInteger(o);
        return integer == null ? null : integer.doubleValue();
    }

    /** Both parts of the tower that a {@code complex} accepts. */
    static boolean isReal(PyObject o) {
        return asReal(o) != null;
    }

    /**
     * Whether CPython would read {@code value} as an index at all: {@code _PyIndex_Check}.
     *
     * <p>The question is about the type, not about the answer, and it is asked before the answer is read
     * because a caller that skips it refuses in the wrong words. A class files {@code __index__} with
     * anything at all — {@code None}, a number, a {@code staticmethod} — and the class system fills the
     * slot from whatever it finds, so {@code [0,1,2][X()]} for a class holding {@code __index__ = None}
     * is the complaint about calling a {@code NoneType} rather than {@code list indices must be integers
     * or slices, not X}. An integer needs no slot to be an index, and is one whatever it files.
     */
    static boolean isIndex(PyObject value) {
        return asInteger(value) != null || Specials.member(value.getType(), "__index__") != null;
    }

    /**
     * {@code _PyNumber_Index}: the integer a value is when something asks it as an index.
     *
     * <p>Three steps, and the first is why the last two are invisible to an {@code int}. A long — a bool
     * or a subclass included — is handed back unread, so {@code [0,1,2][True]} is {@code 2} and a class
     * that subclasses {@code int} and redefines {@code __index__} is still read as the number it is.
     * Anything else is asked its own {@code __index__} with no arguments, and what answers has to be a
     * long: a str, a float and the value {@code NotImplemented} are each refused as {@code __index__
     * returned non-int (type X)}, naming the answer rather than the value asked.
     *
     * <p>CPython additionally warns when the answer is a strict subclass of {@code int} —
     * {@code __index__} returning {@code True} is a {@code DeprecationWarning} whose value is still
     * {@code 1}. This runtime has no warnings machinery to write it with, so the number it stands for is
     * taken without the complaint.
     */
    static BigInteger asIndex(PyObject value) {
        BigInteger number = asInteger(value);
        if (number != null) {
            return number;
        }
        PyObject declared = Specials.member(value.getType(), "__index__");
        if (declared == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().filedName());
        }
        PyObject answer =
                declared instanceof PyFunction
                        ? Specials.invoke(value, "__index__")
                        : Slots.call(
                                Descriptors.get(declared, value, value.getType()),
                                new PyObject[0],
                                null);
        BigInteger index = asInteger(answer);
        if (index == null) {
            throw Slots.typeError(
                    "__index__ returned non-int (type %s)", answer.getType().filedName());
        }
        return index;
    }

    /**
     * An index, as a sequence wants it: an integer, or anything with {@code __index__}.
     *
     * @param what the word CPython puts in front of "indices" — "list", "tuple", "range", or "byte" for a
     *     {@code bytes}, which is the one sequence whose refusal names the element rather than itself
     */
    static BigInteger index(PyObject key, String what) {
        if (!isIndex(key)) {
            throw Slots.typeError(
                    "%s indices must be integers or slices, not %s", what, key.getType().filedName());
        }
        return asIndex(key);
    }

    /**
     * A bound of a slice.
     *
     * CPython words this refusal differently from an index's — the slice path can also accept
     * {@code None} and anything with {@code __index__}, and says so.
     */
    static BigInteger sliceIndex(PyObject value) {
        return indexOr(value, "slice indices must be integers or None or have an __index__ method");
    }

    /**
     * The {@code start} or {@code stop} of a {@code list}/{@code tuple} search, where {@code None} is
     * already taken as the whole sequence and so is not in the sentence.
     *
     * <p>The two forms are one function in CPython twice over — {@code _PyEval_SliceIndex} and
     * {@code _PyEval_SliceIndexNotNone} ({@code Python/ceval.c:3091} and {@code :3112}) — and the clinic
     * files the second one only into {@code index} and {@code count}. So {@code "ab"[Bad:None]} blames
     * the slice path with {@code None} in its list of acceptables while
     * {@code [1].index(1, Bad)} says only {@code slice indices must be integers or have an __index__
     * method}.
     */
    static BigInteger searchIndex(PyObject value) {
        return indexOr(value, "slice indices must be integers or have an __index__ method");
    }

    private static BigInteger indexOr(PyObject value, String refusal) {
        if (!isIndex(value)) {
            throw Slots.typeError("%s", refusal);
        }
        return asIndex(value);
    }

    /**
     * A count of work rather than a position: {@code maxsplit}, {@code replace}'s {@code count}.
     *
     * <p>CPython reads these into a {@code Py_ssize_t}, which is wider than the {@code int} an index
     * has to be and refuses an overflow in its own words: {@code split(b'a', 2**40)} is a list of the
     * whole bytes, while {@code split(b'a', 10**30)} is an {@code OverflowError}. Neither of those
     * numbers is ever an index, so neither gets {@link #fitsInt}'s message.
     *
     * <p>The argument's {@code __index__} is asked first, because {@code %n} in CPython's own argument
     * parser is {@code _PyNumber_Index} and {@code PyLong_AsSsize_t} and nothing else: a width may be a
     * value of any class that can answer as a number.
     */
    static long size(PyObject value) {
        BigInteger number = asIndex(value);
        // bitLength() counts the magnitude's bits, and a ssize_t is one signed 64-bit word:
        // 2**63 - 1 and -(2**63) both need 63 of them, and either number one wider does not fit.
        if (number.bitLength() > 63) {
            throw Slots.overflowError("Python int too large to convert to C ssize_t");
        }
        return number.longValue();
    }

    /**
     * {@code PyNumber_AsSsize_t(value, PyExc_ValueError)}: a count asked of a value, with the overflow
     * complained about as a bad value rather than as a type.
     *
     * <p>The second argument is the whole difference from {@link #size} and it is visible in one word of
     * the sentence: {@code OSError.characters_written = 2**70} is {@code ValueError: cannot fit 'int' into
     * an index-sized integer} where {@code to_bytes(2**70)} is the {@code OverflowError} about a C
     * {@code ssize_t}. What arrives is a {@code Py_ssize_t} rather than an index, so the window is the
     * same sixty-four bits and only the refusal changes.
     *
     * <p>The name inside the sentence is the asked value's type, not the index it answered with —
     * {@code characters_written = Big()} where {@code __index__} returns {@code 2**70} is
     * {@code cannot fit 'Big' into an index-sized integer}, because {@code PyNumber_AsSsize_t} formats
     * the object it was handed before it converted it.
     */
    static long count(PyObject value) {
        BigInteger number = asIndex(value);
        if (number.bitLength() > 63) {
            throw Slots.valueError(
                    "cannot fit '%s' into an index-sized integer", value.getType().filedName());
        }
        return number.longValue();
    }

    /**
     * {@code PyNumber_AsSsize_t(value, PyExc_OverflowError)}: the count of a sequence repeat.
     *
     * <p>Of the three forms of this one converter this is the middle: the overflow is an
     * {@code OverflowError} like {@link #size}'s but says "index-sized" like {@link #count}'s, and it is
     * the only one whose refusal of a value with no {@code __index__} at all is not its own — that
     * refusal belongs to the caller, which has to say {@code can't multiply sequence by non-int of type
     * 'X'} where {@code _PyIndex_Check} failed, and {@code __index__ returned non-int} where the answer
     * was not a number.
     */
    static long repeat(PyObject value) {
        BigInteger number = asIndex(value);
        if (number.bitLength() > 63) {
            throw Slots.overflowError(
                    "cannot fit '%s' into an index-sized integer", value.getType().filedName());
        }
        return number.longValue();
    }

    /**
     * Whether an integer of any size is one Java can hold in an {@code int}, which is what a Python
     * index has to be before it can address anything.
     */
    static boolean fitsInt(BigInteger value) {
        // bitLength() counts the bits a magnitude needs, and an int has one of them for its sign.
        return value.bitLength() < 32;
    }

    /**
     * A length a padding or an expanded run of tabs has asked for, made into one Java can address.
     *
     * <p>The width of a padding is a {@code Py_ssize_t} on both sides, so a request wider than an array
     * gets past {@link #size} and reaches the allocator: CPython's {@code malloc} fails and answers an
     * empty {@code MemoryError}, and a Java string or array cannot even be numbered that far, so this
     * refuses the length the same way rather than inventing a sentence for it. The paddings of
     * {@code bytes} and of {@code str} both end up here; {@code bytes} has a refusal of its own one
     * step earlier, in {@link #byteSize}.
     *
     * <p>The bound is a few octets short of an {@code int} because an array carries a header: HotSpot
     * refuses a {@code byte[]} of the full {@code Integer.MAX_VALUE} with a Java {@code OutOfMemoryError}
     * that no Python {@code except} can catch — it takes {@code 2 ** 31 - 3} here and refuses the two
     * lengths above it — and the JDK's own collections stop at {@code Integer.MAX_VALUE - 8}. Anything
     * this side of that is a length a Java array can hold, so refusing it would be inventing a failure
     * the machine does not have.
     */
    static int total(long length) {
        if (length > ARRAY_MAX) {
            throw Slots.memoryError();
        }
        return (int) length;
    }

    /** The longest array this runtime can hand a Python object, which is short of a full {@code int}. */
    private static final long ARRAY_MAX = Integer.MAX_VALUE - 8L;

    /**
     * The widest {@code bytes} this CPython will try to allocate, which is short of a machine word.
     *
     * <p>{@code _PyBytes_FromSize} refuses a size that would not fit beside the header it gives a bytes
     * object — {@code size > PY_SSIZE_T_MAX - PyBytesObject_SIZE} — and says so before asking the
     * allocator, so a padding that wide answers {@code OverflowError: byte string is too large} where
     * one octet narrower answers {@code MemoryError}. Measured on this build, where the header is
     * {@code 24 + 8 + 1} octets: {@code b'ab'.center(2 ** 63 - 33)} overflows and
     * {@code center(2 ** 63 - 34)} runs out of memory. A {@code str} has no such check, because its
     * {@code pad()} adds the two margins back into the width it was given, which {@link #size} has
     * already bounded — so the widest paddings of {@code str} only ever fail as memory does.
     */
    static void byteSize(long size) {
        if (size > Long.MAX_VALUE - BYTES_HEADER) {
            throw Slots.overflowError("byte string is too large");
        }
    }

    /** What this CPython adds to the octets of a bytes object before it asks for memory. */
    private static final long BYTES_HEADER = 33;

    /**
     * A number CPython reads into a plain C {@code int}, which is half as wide as the word a
     * {@link #size size} is read into.
     *
     * <p>{@code bytes.expandtabs} and {@code str.expandtabs} take their tab size this way, and the
     * narrowness shows: {@code expandtabs(2 ** 31)} overflows while {@code split(sep, 2 ** 31)} is a
     * count like any other. The refusal of a number that is not one is the same sentence {@link #size}
     * uses, because both are read by {@code PyLong_AsInt} and {@code PyLong_AsSsize_t} rather than by a
     * converter that names the parameter.
     */
    static int cInteger(PyObject value) {
        BigInteger number = asIndex(value);
        if (!fitsInt(number)) {
            throw Slots.overflowError("Python int too large to convert to C int");
        }
        return number.intValue();
    }

    /**
     * {@code PyNumber_AsSsize_t(value, NULL)}: the clipping form, which refuses nothing for width.
     *
     * <p>A bound that no machine word holds is read as the widest one that does, which is why
     * {@code "abc".center(2 ** 70)} is an overflow while {@code a[2 ** 70:]} is simply an empty slice:
     * the slice path takes this form and the width path takes {@link #size}'s.
     */
    static long clipped(PyObject value) {
        BigInteger number = asIndex(value);
        if (number.bitLength() > 63) {
            return number.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return number.longValue();
    }

    /**
     * An integer index made non-negative against {@code length}, and checked.
     *
     * @param item the value the index was read from, since the width refusal names its type: a
     *     {@code __index__} of {@code 2 ** 70} is {@code cannot fit 'Big' into an index-sized integer}
     *     while the same number written as a literal says {@code 'int'}
     * @param outOfRange the sequence's whole sentence for an index past the end, since {@code bytes}
     *     says only "index out of range" while the others name themselves
     * @throws IndexError for a value outside the sequence, including one too wide for a machine word
     */
    static int wrapIndex(PyObject item, BigInteger value, int length, String outOfRange) {
        if (value.bitLength() > 63) {
            // CPython checks the width before the bounds, so a huge index is not "out of range".
            throw Slots.indexError(
                    "cannot fit '%s' into an index-sized integer", item.getType().filedName());
        }
        // A word-wide index is still past the end, and only the width refusal above is about width:
        // a[2 ** 40] is an IndexError about a range, not one about an int that will not fit.
        long at = value.longValue();
        if (at < 0) {
            at += length;
        }
        if (at < 0 || at >= length) {
            throw Slots.indexError(outOfRange);
        }
        return (int) at;
    }

    /** {@code o} as a Java {@code String}, if it is a {@code str} and nothing else. */
    static String asText(PyObject o) {
        return o instanceof PyStr s ? s.getValue() : null;
    }
}
