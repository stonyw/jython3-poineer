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
     * An index, as a sequence wants it: an integer, or a {@code long} of that integer.
     *
     * @param what the word CPython puts in front of "indices" — "list", "tuple", or "byte" for a
     *     {@code bytes}, which is the one sequence whose refusal names the element rather than itself
     */
    static BigInteger index(PyObject key, String what) {
        BigInteger value = asInteger(key);
        if (value == null) {
            throw Slots.typeError(
                    "%s indices must be integers or slices, not %s", what, key.getType().name());
        }
        return value;
    }

    /**
     * A bound of a slice, or of the {@code start}/{@code stop} pair a search takes.
     *
     * CPython words this refusal differently from an index's — the slice path can also accept
     * {@code None} and anything with {@code __index__}, and says so.
     */
    static BigInteger sliceIndex(PyObject value) {
        BigInteger number = asInteger(value);
        if (number == null) {
            throw Slots.typeError(
                    "slice indices must be integers or None or have an __index__ method");
        }
        return number;
    }

    /**
     * A count of work rather than a position: {@code maxsplit}, {@code replace}'s {@code count}.
     *
     * <p>CPython reads these into a {@code Py_ssize_t}, which is wider than the {@code int} an index
     * has to be and refuses an overflow in its own words: {@code split(b'a', 2**40)} is a list of the
     * whole bytes, while {@code split(b'a', 10**30)} is an {@code OverflowError}. Neither of those
     * numbers is ever an index, so neither gets {@link #fitsInt}'s message.
     */
    static long size(PyObject value) {
        BigInteger number = asInteger(value);
        if (number == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().name());
        }
        // bitLength() counts the magnitude's bits, and a ssize_t is one signed 64-bit word:
        // 2**63 - 1 and -(2**63) both need 63 of them, and either number one wider does not fit.
        if (number.bitLength() > 63) {
            throw Slots.overflowError("Python int too large to convert to C ssize_t");
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
        BigInteger number = asInteger(value);
        if (number == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().name());
        }
        if (!fitsInt(number)) {
            throw Slots.overflowError("Python int too large to convert to C int");
        }
        return number.intValue();
    }

    /**
     * An integer index made non-negative against {@code length}, and checked.
     *
     * @param outOfRange the sequence's whole sentence for an index past the end, since {@code bytes}
     *     says only "index out of range" while the others name themselves
     * @throws IndexError for a value outside the sequence, including one too wide for an {@code int}
     */
    static int wrapIndex(BigInteger value, int length, String outOfRange) {
        if (!fitsInt(value)) {
            // CPython checks the width before the bounds, so a huge index is not "out of range".
            throw Slots.indexError("cannot fit 'int' into an index-sized integer");
        }
        int at = value.intValue();
        if (at < 0) {
            at += length;
        }
        if (at < 0 || at >= length) {
            throw Slots.indexError(outOfRange);
        }
        return at;
    }

    /** {@code o} as a Java {@code String}, if it is a {@code str} and nothing else. */
    static String asText(PyObject o) {
        return o instanceof PyStr s ? s.getValue() : null;
    }
}
