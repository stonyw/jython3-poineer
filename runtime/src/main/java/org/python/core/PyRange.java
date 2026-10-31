package org.python.core;

import java.math.BigInteger;

/**
 * Python's {@code range}: a sequence of integers, held as its three parameters rather than its
 * elements.
 *
 * That representation is the point — {@code range(2**100)} is a value that fits, and {@code len},
 * {@code [i]} and {@code in} are all arithmetic on it. The parameters are {@code BigInteger} for the
 * same reason {@link PyInt} uses it: {@code range} accepts values wider than a {@code long}, and a
 * {@code long} here would quietly truncate a loop bound.
 */
public final class PyRange extends PyObject {

    private static final BigInteger ONE = BigInteger.ONE;

    private final BigInteger start;
    private final BigInteger stop;
    private final BigInteger step;

    private PyRange(BigInteger start, BigInteger stop, BigInteger step) {
        super(PyType.RANGE);
        this.start = start;
        this.stop = stop;
        this.step = step;
    }

    /** The three-argument form, with a zero step already refused. */
    public static PyRange of(BigInteger start, BigInteger stop, BigInteger step) {
        if (step.signum() == 0) {
            throw Slots.valueError("range() arg 3 must not be zero");
        }
        return new PyRange(start, stop, step);
    }

    /** How many values the range holds, as CPython's {@code compute_length} does it. */
    public BigInteger lengthValue() {
        BigInteger difference =
                step.signum() > 0 ? stop.subtract(start) : start.subtract(stop);
        if (difference.signum() <= 0) {
            return BigInteger.ZERO;
        }
        // One more than the number of whole steps that fit in the gap *below* the last value:
        // (diff - 1) / |step| + 1, which is why range(3) is three values and not four. The
        // dividend is never negative here, so truncating division is floor division.
        return difference.subtract(ONE).divide(step.abs()).add(ONE);
    }

    /** The value at an index already known to be in range. */
    public PyObject valueAt(BigInteger index) {
        return PyInt.of(start.add(index.multiply(step)));
    }

    public PyObject itemAt(BigInteger index) {
        BigInteger length = lengthValue();
        BigInteger at = index.signum() < 0 ? index.add(length) : index;
        if (at.signum() < 0 || at.compareTo(length) >= 0) {
            throw Slots.indexError("range object index out of range");
        }
        return valueAt(at);
    }

    /**
     * {@code range_length}, which is {@code PyLong_AsSsize_t} over the length the range computed for
     * itself. The arithmetic is arbitrary precision on both sides — {@code range(Big(), Big())} is a
     * range of two values — but the count is handed back as a machine word, so a range longer than
     * sixty-three bits answers {@code len()} with an {@code OverflowError} about a C {@code ssize_t}
     * where {@code r[2 ** 70]} is an {@code IndexError} about the position.
     */
    @Override
    public PyObject length() {
        BigInteger length = lengthValue();
        if (length.bitLength() > 63) {
            throw Slots.overflowError("Python int too large to convert to C ssize_t");
        }
        return PyInt.of(length);
    }

    @Override
    public PyObject getItem(PyObject key) {
        if (key instanceof PySlice) {
            // Slicing a range yields another range. Deferred: the slice-index arithmetic is shared
            // with str, bytes and list and lands with them.
            throw Slots.typeError("range indices must be integers");
        }
        // A range's own subscript is arbitrary-precision all the way down: range_subscript asks
        // PyNumber_Index rather than PyNumber_AsSsize_t, so no width is ever refused for not fitting.
        return itemAt(Numbers.index(key, "range"));
    }

    @Override
    public PyObject contains(PyObject item) {
        BigInteger value = Numbers.asInteger(item);
        if (value == null) {
            return PyBool.FALSE;
        }
        BigInteger offset = value.subtract(start);
        if (offset.signum() != 0 && offset.signum() != step.signum()) {
            return PyBool.FALSE;
        }
        BigInteger[] divided = offset.divideAndRemainder(step);
        if (divided[1].signum() != 0) {
            return PyBool.FALSE;
        }
        BigInteger length = lengthValue();
        return PyBool.of(divided[0].signum() >= 0 && divided[0].compareTo(length) < 0);
    }

    @Override
    public PyObject iterator() {
        return new RangeIterator(this, BigInteger.ZERO);
    }

    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyRange r)) {
            // An empty range equals an empty list in CPython only through ==, which is type-aware;
            // here, as there, a range is never equal to a sequence of the same values.
            return false;
        }
        BigInteger mine = lengthValue();
        if (!mine.equals(r.lengthValue())) {
            return false;
        }
        if (mine.signum() == 0) {
            return true;
        }
        return start.equals(r.start) && step.equals(r.step);
    }

    @Override
    public long hash() {
        // CPython hashes a range as the tuple (len, start, step, 1), unless it holds one value, in
        // which case it hashes as that value.
        BigInteger length = lengthValue();
        if (length.equals(ONE)) {
            return PyInt.of(start).hash();
        }
        long[] lanes = {
            PyHash.intHash(length), PyHash.intHash(start), PyHash.intHash(step), 1L,
        };
        return PyHash.tupleHash(lanes);
    }

    @Override
    public String repr() {
        if (step.equals(ONE)) {
            return "range(" + start + ", " + stop + ")";
        }
        return "range(" + start + ", " + stop + ", " + step + ")";
    }

    private static final class RangeIterator extends PyIterator {

        private final PyRange range;
        private BigInteger index;

        RangeIterator(PyRange range, BigInteger index) {
            this.range = range;
            this.index = index;
        }

        @Override
        public PyObject nextOrNull() {
            if (index.compareTo(range.lengthValue()) >= 0) {
                return null;
            }
            PyObject value = range.valueAt(index);
            index = index.add(ONE);
            return value;
        }
    }
}
