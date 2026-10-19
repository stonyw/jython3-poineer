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
     * Whether an integer of any size is one Java can hold in an {@code int}, which is what a Python
     * index has to be before it can address anything.
     */
    static boolean fitsInt(BigInteger value) {
        // bitLength() counts the bits a magnitude needs, and an int has one of them for its sign.
        return value.bitLength() < 32;
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
