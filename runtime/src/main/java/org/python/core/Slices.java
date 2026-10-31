package org.python.core;

import java.math.BigInteger;

/**
 * Index and slice arithmetic, shared by every sequence.
 *
 * CPython computes a slice in two steps — {@code PySlice_Unpack}, which turns the three optional
 * values into clamped integers, and {@code PySlice_AdjustIndices}, which folds negatives into the
 * length and counts the result — and both are reproduced here rather than approximated, because
 * {@code a[::0]}, {@code a[-2**100:]} and an empty reversed slice are all observable.
 */
final class Slices {

    /** A slice resolved against a length: the indices it selects are {@code start, start+step, …} for {@code count} steps. */
    record Range(int start, int stop, int step, int count) {

        /** The {@code i}-th index this range selects. */
        int at(int i) {
            return start + i * step;
        }
    }

    private Slices() {
    }

    /** {@code seq[key]} where the key is a slice, which every sequence has to be ready for. */
    static boolean isSlice(PyObject key) {
        return key instanceof PySlice;
    }

    /**
     * An index, wrapped if negative, with the sequence's own two refusals.
     *
     * @param indices the word CPython puts in front of "indices must be integers or slices"
     * @param outOfRange the whole sentence for an index past the end
     */
    static int index(PyObject key, int length, String indices, String outOfRange) {
        return Numbers.wrapIndex(key, Numbers.index(key, indices), length, outOfRange);
    }

    /**
     * {@code slice_richcompare}: the three fields packed as a tuple, with the pointer test in front.
     *
     * <p>Both operands have to be slices or the whole thing declines — {@code slice(1) == (1, None, None)}
     * is answered by neither side and falls back on identity, so it is false. The pointer test is the
     * source's own, with its own {@code XXX Do we really need this shortcut?} beside it: it hands {@code
     * True} to {@code __eq__}, {@code __le__} and {@code __ge__} and {@code False} to the other three
     * before any field is looked at, and the order the fields would be compared in answers the same way
     * for every value Python can put in one.
     */
    static PyObject compare(CompareOp cmp, PyObject left, PyObject right) {
        if (!(left instanceof PySlice a) || !(right instanceof PySlice b)) {
            return PyNotImplemented.INSTANCE;
        }
        if (left == right) {
            return PyBool.of(cmp == CompareOp.EQ || cmp == CompareOp.LE || cmp == CompareOp.GE);
        }
        return Sequences.compare(cmp, a.parts(), b.parts());
    }

    /**
     * {@code slice_indices}: the length read as an index, and the three bounds folded against it.
     *
     * <p>This is {@code _PySlice_GetLongIndices}, the arbitrary-precision half of the slice arithmetic —
     * {@link #of} is the machine-word half a sequence uses to pick its elements. The two differ in two
     * visible ways: nothing here is clamped to a word, so {@code slice(2 ** 100, None, None).indices(3)}
     * answers {@code (3, 3, 1)} the same as the narrow one, and the step is reported whole rather than
     * folded into a size — {@code slice(None, None, -(2 ** 70) - 5).indices(10)} answers the full negative
     * number as its own third element.
     *
     * <p>The order is the source's, and it decides which of the four refusals a call gets: the length is
     * {@code PyNumber_Index} and so speaks the index protocol's own sentences, a negative length is the
     * {@code ValueError} about length rather than about a bound, the step is read before either bound and
     * is the one bound whose zero is refused, and a bad {@code start} outranks a bad {@code stop}. Bounds
     * are folded the way a normal slice clips: a negative one gains the length and is then only lifted to
     * the lower edge, a non-negative one is only pressed down to the upper.
     */
    static PyObject indices(PySlice slice, PyObject lengthValue) {
        BigInteger length = Numbers.asIndex(lengthValue);
        if (length.signum() < 0) {
            throw Slots.valueError("length should not be negative");
        }
        BigInteger step;
        boolean backwards;
        if (slice.step() == PyNone.INSTANCE) {
            step = BigInteger.ONE;
            backwards = false;
        } else {
            step = Numbers.sliceIndex(slice.step());
            if (step.signum() == 0) {
                throw Slots.valueError("slice step cannot be zero");
            }
            backwards = step.signum() < 0;
        }
        BigInteger lower = backwards ? BigInteger.ONE.negate() : BigInteger.ZERO;
        BigInteger upper = backwards ? length.subtract(BigInteger.ONE) : length;
        BigInteger start =
                slice.start() == PyNone.INSTANCE
                        ? (backwards ? upper : lower)
                        : clipped(Numbers.sliceIndex(slice.start()), length, lower, upper);
        BigInteger stop =
                slice.stop() == PyNone.INSTANCE
                        ? (backwards ? lower : upper)
                        : clipped(Numbers.sliceIndex(slice.stop()), length, lower, upper);
        return PyTuple.of(PyInt.of(start), PyInt.of(stop), PyInt.of(step));
    }

    /**
     * One bound against the two edges: a negative one gains the length and is then lifted no lower than
     * {@code lower}, a non-negative one is pressed no higher than {@code upper} — and never the other way
     * round, which is what keeps {@code slice(-100, 100, 7).indices(10)} at {@code (0, 10, 7)}.
     */
    private static BigInteger clipped(
            BigInteger bound, BigInteger length, BigInteger lower, BigInteger upper) {
        if (bound.signum() < 0) {
            BigInteger folded = bound.add(length);
            return folded.compareTo(lower) < 0 ? lower : folded;
        }
        return bound.compareTo(upper) > 0 ? upper : bound;
    }

    /** {@code slice.indices(length)}, as CPython resolves it. */
    static Range of(PySlice slice, int length) {
        long step = bound(slice.step(), 1, length, false);
        if (step == 0) {
            throw Slots.valueError("slice step cannot be zero");
        }
        boolean backwards = step < 0;
        long start = bound(slice.start(), backwards ? Long.MAX_VALUE : 0L, length, backwards);
        long stop = bound(slice.stop(), backwards ? Long.MIN_VALUE : Long.MAX_VALUE, length, backwards);
        // CPython leaves these defaults in place and lets the folding below turn them into the real
        // bounds: MAX_VALUE >= length becomes length-1 when going backwards, and MIN_VALUE + length
        // stays negative so it lands on the -1 that means "run to and including index 0".

        if (start < 0) {
            start += length;
            if (start < 0) {
                start = backwards ? -1 : 0;
            }
        } else if (start >= length) {
            start = backwards ? length - 1 : length;
        }
        if (stop < 0) {
            stop += length;
            if (stop < 0) {
                stop = backwards ? -1 : 0;
            }
        } else if (stop >= length) {
            stop = backwards ? length - 1 : length;
        }

        long count;
        if (backwards) {
            count = stop < start ? (start - stop - 1) / -step + 1 : 0;
        } else {
            count = start < stop ? (stop - start - 1) / step + 1 : 0;
        }
        return new Range((int) start, (int) stop, (int) step, (int) count);
    }

    /**
     * One bound of a slice: {@code None} becomes {@code whenNone}, a number is checked, and anything
     * too far outside the sequence keeps its sign but saturates — which is what makes
     * {@code a[2**100:]} an empty slice instead of an error.
     */
    private static long bound(PyObject value, long whenNone, int length, boolean backwards) {
        if (value == PyNone.INSTANCE) {
            return whenNone;
        }
        BigInteger index = Numbers.sliceIndex(value);
        if (index.signum() < 0) {
            return index.compareTo(BigInteger.valueOf(-(long) length - 1)) < 0
                    ? Integer.MIN_VALUE
                    : index.longValue();
        }
        return index.compareTo(BigInteger.valueOf(length)) > 0
                ? (backwards ? length - 1L : Integer.MAX_VALUE)
                : index.longValue();
    }

    /** The indices {@code range} selects, for a sequence that cannot index itself in place. */
    static int[] indicesOf(Range range) {
        int[] indices = new int[range.count()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = range.at(i);
        }
        return indices;
    }
}
