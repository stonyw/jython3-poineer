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
        return Numbers.wrapIndex(Numbers.index(key, indices), length, outOfRange);
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
