package org.python.core;

import java.math.BigInteger;

/**
 * Python's {@code range}: a sequence of integers, held as its three parameters rather than its
 * elements.
 *
 * That representation is the point — {@code range(2**100)} is a value that fits, and {@code len},
 * {@code [i]} and {@code in} are all arithmetic on it. The parameters are {@code BigInteger} for the
 * same reason {@link PyInt} uses it: {@code range} accepts values wider than a {@code long}, and a
 * {@code long} here would quietly truncate a loop bound. Each is kept as the {@link PyInt} a program
 * reads back out by name, because {@code r.start is r.start} is the same object however wide the
 * bound is.
 */
public final class PyRange extends PyObject {

    private static final BigInteger ONE = BigInteger.ONE;

    /**
     * The four rows {@code range_methods} files (Objects/rangeobject.c:766).
     *
     * <p>Two of the four sentences open with {@code rangeobject.count(value)}, which names the method by
     * the type it is reached through rather than by itself, so the signature knife finds no table above
     * either text and both {@code METH_O} rows read {@code ($self, object, /)} out of their flag — the
     * same invented table {@code slice.indices} answers with. {@code __reduce__} is the one row of the
     * four filed with no {@code ml_doc} beside it at all, so its {@code __doc__} is {@code None} while its
     * signature is still the {@code ($self, /)} a {@code METH_NOARGS} answers.
     */
    private static final Members METHODS = Members.of(PyType.RANGE)
            .addNoArgument(
                    "__reversed__",
                    "Return a reverse iterator.",
                    self -> ((PyRange) self).reversedIterator())
            .addNoArgument("__reduce__", self -> ((PyRange) self).reduced())
            .addOne(
                    "count",
                    "rangeobject.count(value) -> integer -- return number of occurrences of value",
                    (self, value) -> PyInt.of(((PyRange) self).counted(value)))
            .addOne(
                    "index",
                    "rangeobject.index(value) -> integer -- return index of value.\n"
                            + "Raise ValueError if the value is not present.",
                    (self, value) -> ((PyRange) self).positionOf(value));

    private final PyInt start;
    private final PyInt stop;
    private final PyInt step;

    private PyRange(PyInt start, PyInt stop, PyInt step) {
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
        return new PyRange(PyInt.of(start), PyInt.of(stop), PyInt.of(step));
    }

    /** The three parameters as the members hand them out — the very objects, not copies. */
    PyInt start() {
        return start;
    }

    PyInt stop() {
        return stop;
    }

    PyInt step() {
        return step;
    }

    private BigInteger startValue() {
        return start.getValue();
    }

    private BigInteger stopValue() {
        return stop.getValue();
    }

    private BigInteger stepValue() {
        return step.getValue();
    }

    /** How many values the range holds, as CPython's {@code compute_length} does it. */
    public BigInteger lengthValue() {
        BigInteger difference =
                stepValue().signum() > 0
                        ? stopValue().subtract(startValue())
                        : startValue().subtract(stopValue());
        if (difference.signum() <= 0) {
            return BigInteger.ZERO;
        }
        // One more than the number of whole steps that fit in the gap *below* the last value:
        // (diff - 1) / |step| + 1, which is why range(3) is three values and not four. The
        // dividend is never negative here, so truncating division is floor division.
        return difference.subtract(ONE).divide(stepValue().abs()).add(ONE);
    }

    /** The value at an index already known to be in range. */
    public PyObject valueAt(BigInteger index) {
        return PyInt.of(startValue().add(index.multiply(stepValue())));
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

    /** {@code range_bool}: the length the range worked out for itself, asked whether it is anything. */
    @Override
    public PyObject truth() {
        return PyBool.of(lengthValue().signum() != 0);
    }

    @Override
    public PyObject getItem(PyObject key) {
        if (key instanceof PySlice slice) {
            return sliced(slice);
        }
        // A range's own subscript is arbitrary-precision all the way down: range_subscript asks
        // PyNumber_Index rather than PyNumber_AsSsize_t, so no width is ever refused for not fitting.
        return itemAt(Numbers.index(key, "range"));
    }

    /**
     * {@code compute_slice}: the slice folded against this range's length, then read back as elements.
     *
     * <p>Both halves are the arbitrary-precision ones — {@code _PySlice_GetLongIndices} and {@code
     * compute_item} — so {@code range(10)[2 ** 70:]} is the empty {@code range(10, 10)} rather than a
     * refusal, and the answer's step is the product of the two steps, which is what makes
     * {@code range(0, 20, 3)[::2]} run at six. A slice of a range is a range, and of the same kind the
     * slice was read from: {@code make_range_object} is handed {@code Py_TYPE(r)}.
     */
    private PyRange sliced(PySlice slice) {
        BigInteger[] folded = Slices.longIndices(slice, lengthValue());
        BigInteger step = stepValue();
        return of(
                startValue().add(folded[0].multiply(step)),
                startValue().add(folded[1].multiply(step)),
                step.multiply(folded[2]));
    }

    /** {@code range_reduce}: the class, and the three bounds it would be built from again. */
    PyObject reduced() {
        return PyTuple.of(PyType.RANGE, PyTuple.of(start, stop, step));
    }

    /**
     * {@code range_reverse}: an iterator over {@code range(start + (n - 1) * step, start - step, -step)},
     * which is the same walk in the other order.
     *
     * <p>The fold belongs to {@link RangeIterators}, because which of the two cursors answers is the
     * question the reversal has to answer for itself: the C function converts six numbers rather than
     * three, so {@code range(0, 10, -(2 ** 63))} walks forwards in machine words and backwards in Python
     * integers, even though the range is empty and neither walk produces a value.
     */
    private PyObject reversedIterator() {
        return RangeIterators.reversed(this);
    }

    /** {@code range_contains}: the arithmetic for an integer, a walk for anything else. */
    @Override
    public PyObject contains(PyObject item) {
        BigInteger whole = Numbers.asInteger(item);
        if (whole != null) {
            return PyBool.of(containsLong(whole));
        }
        return PyBool.of(searched(item, false) >= 0);
    }

    /** {@code range_count}. */
    private long counted(PyObject target) {
        BigInteger whole = Numbers.asInteger(target);
        if (whole != null) {
            return containsLong(whole) ? 1 : 0;
        }
        return searched(target, true);
    }

    /**
     * {@code range_index}: the arithmetic for an integer, the walk for anything else.
     *
     * <p>The two refusals are different sentences because the two halves are different code: the
     * arithmetic one is the range's own, and a value the walk never finds is refused by the sequence
     * search the list and the tuple share — so {@code range(3).index("x")} names no range at all.
     */
    private PyObject positionOf(PyObject target) {
        BigInteger whole = Numbers.asInteger(target);
        if (whole != null) {
            if (!containsLong(whole)) {
                throw Slots.valueError("range.index(x): x not in range");
            }
            BigInteger offset = whole.subtract(startValue());
            if (stepValue().equals(ONE)) {
                return PyInt.of(offset);
            }
            return PyInt.of(Numeric.divmod(offset, stepValue())[0]);
        }
        long at = searched(target, false);
        if (at < 0) {
            throw Slots.valueError("sequence.index(x): x not in sequence");
        }
        return PyInt.of(at);
    }

    /** {@code range_contains_long}: between the two bounds, and a whole number of steps from {@code start}. */
    private boolean containsLong(BigInteger value) {
        boolean between =
                stepValue().signum() > 0
                        ? startValue().compareTo(value) <= 0 && value.compareTo(stopValue()) < 0
                        : value.compareTo(startValue()) <= 0 && stopValue().compareTo(value) < 0;
        return between && value.subtract(startValue()).mod(stepValue().abs()).signum() == 0;
    }

    /**
     * {@code _PySequence_IterSearch} over the values this range produces: the index of the first one
     * equal to {@code target}, or — for the caller that is counting — how many are, in which case the
     * walk has no reason to stop early.
     *
     * <p>Only a value that is not an integer comes here, since {@link #containsLong} answers those
     * without walking. Each comparison is {@code ==} against one element, asked as the operator asks it,
     * which is what lets {@code 3.0} be found in {@code range(1, 9, 2)} and {@code True} in {@code range(3)}
     * while {@code 1.5} is in neither — and lets a value whose own {@code __eq__} accepts an integer be
     * found in every element of the range, though the integer answers it with a decline.
     */
    private long searched(PyObject target, boolean counting) {
        BigInteger length = lengthValue();
        long found = counting ? 0 : -1;
        for (BigInteger index = BigInteger.ZERO; index.compareTo(length) < 0; index = index.add(ONE)) {
            if (Slots.compareTrue(CompareOp.EQ, valueAt(index), target)) {
                if (!counting) {
                    return index.longValue();
                }
                found++;
            }
        }
        return found;
    }

    @Override
    public PyObject iterator() {
        return RangeIterators.of(this);
    }

    @Override
    public boolean eq(PyObject other) {
        // An empty range equals an empty list in CPython only through ==, which is type-aware; here, as
        // there, a range is never equal to a sequence of the same values.
        return other instanceof PyRange range && sameRange(this, range);
    }

    /**
     * {@code range_richcompare}: the two equalities, and nothing else answered.
     *
     * <p>{@code PyRange_Check} is exact, so a value that is not a range is declined by both sides and
     * {@code range(3) == 3} falls back on identity; the four orderings decline even between two ranges,
     * which is what makes {@code range(3) < range(4)} the {@code TypeError} naming both types.
     */
    public static PyObject compare(CompareOp cmp, PyObject left, PyObject right) {
        if (!(left instanceof PyRange a) || !(right instanceof PyRange b)) {
            return PyNotImplemented.INSTANCE;
        }
        if (cmp != CompareOp.EQ && cmp != CompareOp.NE) {
            return PyNotImplemented.INSTANCE;
        }
        boolean same = sameRange(a, b);
        return PyBool.of(cmp == CompareOp.EQ ? same : !same);
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return compare(cmp, this, other);
    }

    /**
     * {@code range_equals}: the length first, then the start, and the step only where it can still
     * matter.
     *
     * <p>The two short circuits are what make the equality read as if it were about values rather than
     * parameters: every empty range equals every other, and a one-value range is equal to another on that
     * one value alone, so {@code range(1) == range(0, 1, 5)} is true while {@code range(1) == range(1, 2,
     * 5)} is not — the starts differ, and the length is too short to excuse it.
     */
    private static boolean sameRange(PyRange left, PyRange right) {
        if (left == right) {
            return true;
        }
        BigInteger length = left.lengthValue();
        if (!length.equals(right.lengthValue())) {
            return false;
        }
        if (length.signum() == 0) {
            return true;
        }
        if (!left.startValue().equals(right.startValue())) {
            return false;
        }
        if (length.equals(ONE)) {
            return true;
        }
        return left.stepValue().equals(right.stepValue());
    }

    /**
     * {@code range_hash}: the tuple CPython builds and hashes, with a bound left out where the range
     * cannot reach it.
     *
     * <p>An empty range hashes as {@code (0, None, None)} and a single-value one as {@code (1, start,
     * None)}, so two ranges of one value share a hash whatever their steps, exactly as they share an
     * {@link #eq answer}. A range of more than one value hashes its own bounds, which are integers and so
     * hash the same here as in the reference; the two that need {@code hash(None)} carry this runtime's
     * fixed number for it rather than an address.
     */
    @Override
    public long hash() {
        BigInteger length = lengthValue();
        long none = PyNone.INSTANCE.hash();
        long[] lanes;
        if (length.signum() == 0) {
            lanes = new long[] {PyHash.intHash(length), none, none};
        } else if (length.equals(ONE)) {
            lanes = new long[] {PyHash.intHash(length), PyHash.intHash(startValue()), none};
        } else {
            lanes =
                    new long[] {
                        PyHash.intHash(length),
                        PyHash.intHash(startValue()),
                        PyHash.intHash(stepValue())
                    };
        }
        return PyHash.tupleHash(lanes);
    }

    @Override
    public String repr() {
        if (stepValue().equals(ONE)) {
            return "range(" + start.repr() + ", " + stop.repr() + ")";
        }
        return "range(" + start.repr() + ", " + stop.repr() + ", " + step.repr() + ")";
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }
}
