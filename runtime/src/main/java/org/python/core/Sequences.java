package org.python.core;

import java.math.BigInteger;

/**
 * The parts of the sequence protocol that every sequence implements the same way.
 *
 * CPython's list, tuple, str and bytes comparisons are one algorithm ({@code listitemcomparison}
 * feeding {@code PyObject_RichCompare} element by element, with length as the tie-break), and its
 * slice gathering is one algorithm too ({@code PySequence_GetItem} over a resolved range). Repeating
 * either in each type is how they drift apart, so both live here.
 */
final class Sequences {

    private Sequences() {
    }

    /**
     * {@code PySequence_Check}: whether {@code value} carries the indexed protocol at all.
     *
     * <p>The test is CPython's slot, not a try at the call: a mapping is subscriptable but is not a
     * sequence, because {@code dict}, {@code module} and {@code type} fill {@code mp_subscript} where the
     * sequence protocols fill {@code sq_item}. A class a person writes passes when it declares
     * {@code __getitem__}, which is the same reason it can be walked by index at all, and one that only
     * declares {@code __iter__} fails — the distinction {@code BaseExceptionGroup}'s second argument is
     * refused on.
     */
    static boolean isSequence(PyObject value) {
        if (value instanceof PyDict || value instanceof PyModule || value instanceof PyType) {
            return false;
        }
        if (value instanceof PyStr
                || value instanceof PyBytes
                || value instanceof PyTuple
                || value instanceof PyList
                || value instanceof PyRange) {
            return true;
        }
        return Specials.member(value.getType(), "__getitem__") != null;
    }

    /**
     * {@code left cmp right} for two sequences of already-extracted elements.
     *
     * Elements are compared with {@link Slots}, so a mixed pair inside a sequence raises in the
     * caller's frame rather than answering a lie — which is CPython's behaviour for
     * {@code (1, "a") < (2, "b")} and worth keeping.
     */
    static PyObject compare(CompareOp cmp, PyObject[] left, PyObject[] right) {
        int common = Math.min(left.length, right.length);
        for (int i = 0; i < common; i++) {
            if (!left[i].eq(right[i])) {
                return Slots.compare(cmp, left[i], right[i]);
            }
        }
        return ordered(cmp, Integer.compare(left.length, right.length));
    }

    /** The elements a range selects, in the order the range walks them. */
    static PyObject[] gather(PyObject[] items, Slices.Range range) {
        PyObject[] out = new PyObject[range.count()];
        for (int i = 0; i < out.length; i++) {
            out[i] = items[range.at(i)];
        }
        return out;
    }

    /**
     * {@code seq * n} for a repeatable sequence, with the count already read as an index.
     *
     * <p>The count has already been refused for width by {@link #multiplier}; what is left is the width
     * of the result, and a Java array is the narrower ceiling here. CPython's list and tuple answer a
     * product too wide for a {@code Py_ssize_t} with {@code PyErr_NoMemory()}, which is the bare
     * {@code MemoryError} that {@link Numbers#total} also answers for a length no Java array can carry.
     */
    static PyObject[] repeat(PyObject[] items, long times) {
        int total = scaled(items.length, times, null);
        if (total == 0) {
            return new PyObject[0];
        }
        PyObject[] out = new PyObject[total];
        for (int at = 0; at < out.length; ) {
            for (PyObject item : items) {
                out[at++] = item;
            }
        }
        return out;
    }

    /**
     * How many elements a repeat of {@code each} run {@code times} over comes to, refused in the words
     * the repeating type files.
     *
     * <p>CPython tests the product one way in all four sequences — {@code input_size >
     * PY_SSIZE_T_MAX / n}, the division that keeps the test itself from overflowing — and then differs
     * in what it answers: {@code str} raises {@code repeated string is too long} and {@code bytes}
     * {@code repeated bytes are too long}, both an {@code OverflowError} naming the result, while a
     * list and a tuple fail the allocation and answer a {@code MemoryError}. Passing {@code null} for
     * {@code tooLong} asks for that answer, which is also the only honest one for a length past what a
     * Java array can be numbered to.
     */
    static int scaled(long each, long times, String tooLong) {
        if (each == 0 || times <= 0) {
            return 0;
        }
        if (each > Long.MAX_VALUE / times) {
            throw tooLong == null ? Slots.memoryError() : Slots.overflowError(tooLong);
        }
        return Numbers.total(each * times);
    }

    /**
     * {@code seq * n} for the four built-in sequences that repeat, counted in elements.
     *
     * <p>An empty sequence or a non-positive count is the empty result before anything is measured, as
     * it is in CPython: {@code [] * -(2 ** 70)} is {@code []}, because the sign is read first.
     */
    static PyObject[] multiplied(PyObject[] items, PyObject times) {
        return repeat(items, multiplier(times));
    }

    /**
     * The multiplier of {@code seq * n}, read the way CPython's sequence repeat reads it.
     *
     * <p>Both refusals are the converter's own and are worth telling apart, because they are what a
     * class with a bad {@code __index__} sees: {@code "a" * 2.0} is refused by the slot test, in the
     * words of {@code sequence_repeat}, which raises {@code TypeError} itself instead of answering
     * {@code NotImplemented} — so the sentence names the multiplier and not the pair of operands —
     * while {@code "a" * BadIndex()} reaches the conversion and is the complaint about the answer.
     */
    static long multiplier(PyObject times) {
        if (!Numbers.isIndex(times)) {
            throw Slots.typeError(
                    "can't multiply sequence by non-int of type '%s'",
                    times.getType().filedName());
        }
        return Numbers.repeat(times);
    }

    private static PyObject ordered(CompareOp cmp, int signal) {
        return PyObject.ordered(cmp, signal);
    }

    /** How many elements equal {@code target}, which is {@code count} for a tuple and a list. */
    static int countOf(PyObject[] items, PyObject target, int from, int to) {
        int found = 0;
        for (int i = from; i < to; i++) {
            if (items[i].eq(target)) {
                found++;
            }
        }
        return found;
    }

    /**
     * The first index of {@code target} in {@code items[from..to)}, or -1.
     *
     * Comparison is by {@code ==}, as CPython's sequence search does it, so a tuple containing a
     * {@code 1} is found by {@code index(True)}.
     */
    static int indexOf(PyObject[] items, PyObject target, int from, int to) {
        for (int i = from; i < to; i++) {
            if (items[i].eq(target)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The {@code start} of a {@code list} or {@code tuple} {@code index} search, resolved the way
     * CPython resolves it: {@code None} means the whole sequence, a negative bound counts back from
     * the end, and a bound beyond either end is clamped rather than refused.
     *
     * <p>{@code None} is not in the refusal because it has already been taken as the whole sequence —
     * these two are the only callers of {@link Numbers#searchIndex}.
     */
    static int searchStart(PyObject bound, int length) {
        return bound == null || bound == PyNone.INSTANCE
                ? 0
                : clamp(Numbers.searchIndex(bound), 0, length);
    }

    /**
     * The {@code start} of a {@code find}, {@code count} or bytes edge test, which differs from
     * {@link #searchStart} in the one case an empty needle can see: a start past the end is left there
     * rather than pulled back, because {@code "abc".find("", 4)} is {@code -1} and not {@code 3}, and
     * {@code b"abc".startswith(b"", 5)} is {@code False} and not {@code True}.
     *
     * <p>A start further past the end than an {@code int} holds answers the same as one just past it,
     * so it is left at the first position no caller's test can read as inside the sequence.
     */
    static int searchFrom(PyObject bound, int length) {
        if (bound == null || bound == PyNone.INSTANCE) {
            return 0;
        }
        BigInteger value = Numbers.sliceIndex(bound);
        if (value.signum() < 0) {
            return value.compareTo(BigInteger.valueOf(-(long) length)) < 0
                    ? 0
                    : length + value.intValue();
        }
        return value.compareTo(BigInteger.valueOf(length)) > 0 ? length + 1 : value.intValue();
    }

    static int searchStop(PyObject bound, int length) {
        return bound == null || bound == PyNone.INSTANCE
                ? length
                : clamp(Numbers.sliceIndex(bound), 0, length);
    }

    /** {@code list} and {@code tuple} {@code index} read their {@code stop} as their {@code start}. */
    static int indexStop(PyObject bound, int length) {
        return bound == null || bound == PyNone.INSTANCE
                ? length
                : clamp(Numbers.searchIndex(bound), 0, length);
    }

    private static int clamp(BigInteger value, int low, int high) {
        if (value.signum() < 0) {
            return value.compareTo(BigInteger.valueOf(-(long) high)) < 0
                    ? low
                    : Math.max(low, high + value.intValue());
        }
        return value.compareTo(BigInteger.valueOf(high)) > 0 ? high : value.intValue();
    }
}
