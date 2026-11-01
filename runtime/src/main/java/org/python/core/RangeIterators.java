package org.python.core;

import java.math.BigInteger;

/**
 * The two cursors a range hands out, which are one pair of names over two bodies.
 *
 * <p>CPython keeps them in {@code rangeobject.c} next to the range itself, and the reason there is more
 * than one is width: {@code range_iter} converts the three bounds to C {@code long} and, if the count and
 * the step that would be added at the end survive the conversion, walks with three machine words. Only
 * when one of those conversions fails does it build the other, which holds three Python integers and
 * adds them. Python sees the difference in three places: the name of the type, the number a
 * {@code __length_hint__} answers, and what {@code __setstate__} accepts as an index.
 */
final class RangeIterators {

    /** {@code length_hint_doc}, written once in the C file and read by both method tables. */
    private static final String LENGTH_HINT_DOC =
            "Private method returning an estimate of len(list(it)).";

    /** {@code reduce_doc}, the same sentence {@code range} and {@code slice} file their own row with. */
    private static final String REDUCE_DOC = "Return state information for pickling.";

    /** {@code setstate_doc}. */
    private static final String SETSTATE_DOC = "Set state information for unpickling.";

    private static final BigInteger ONE = BigInteger.ONE;

    /**
     * The three rows {@code rangeiter_methods} files, over the state kept in C {@code long}s.
     *
     * <p>{@code __iter__} and {@code __next__} are not here: they are slot wrappers, which {@code
     * TypeDict} files beside these from the value's own slots.
     */
    private static final Members FAST =
            Members.of(PyType.RANGE_ITERATOR)
                    .addNoArgument(
                            "__length_hint__",
                            LENGTH_HINT_DOC,
                            self -> PyInt.of(((Fast) self).remaining))
                    .addNoArgument("__reduce__", REDUCE_DOC, RangeIterators::reduceFast)
                    .addOne(
                            "__setstate__",
                            SETSTATE_DOC,
                            (self, state) -> {
                                ((Fast) self).stepTo(state);
                                return PyNone.INSTANCE;
                            });

    /** The same three names over arbitrary-precision state. */
    private static final Members WIDE =
            Members.of(PyType.LONG_RANGE_ITERATOR)
                    .addNoArgument(
                            "__length_hint__",
                            LENGTH_HINT_DOC,
                            self -> PyInt.of(((Wide) self).remaining))
                    .addNoArgument("__reduce__", REDUCE_DOC, RangeIterators::reduceWide)
                    .addOne(
                            "__setstate__",
                            SETSTATE_DOC,
                            (self, state) -> {
                                ((Wide) self).stepTo(state);
                                return PyNone.INSTANCE;
                            });

    private RangeIterators() {
    }

    /**
     * {@code range_iter}: the fast cursor if the bounds, the count and the last step all fit a C
     * {@code long}, the other one if any of them does not.
     */
    static PyObject of(PyRange range) {
        Long start = asLong(range.start().getValue());
        Long stop = asLong(range.stop().getValue());
        Long step = asLong(range.step().getValue());
        if (start != null && stop != null && step != null) {
            BigInteger length = range.lengthValue();
            if (length.bitLength() < Long.SIZE
                    && (length.signum() == 0 || !overruns(start, stop, step))) {
                return new Fast(start, step, length.longValue());
            }
        }
        return new Wide(range.start().getValue(), range.step().getValue(), range.lengthValue());
    }

    /**
     * {@code range_reverse}: the same walk in the other order, and a stricter gate.
     *
     * <p>The C function converts the three bounds and then asks about two more numbers the reversal has
     * to write into the cursor — {@code -step} and {@code start - step} — so {@code
     * range(0, 10, -(2 ** 63))} gets a fast cursor walking forwards and a wide one walking back, even
     * though the range is empty and neither walk produces a value.
     */
    static PyObject reversed(PyRange range) {
        Long start = asLong(range.start().getValue());
        Long stop = asLong(range.stop().getValue());
        Long step = asLong(range.step().getValue());
        if (start != null && stop != null && step != null && step != Long.MIN_VALUE) {
            BigInteger length = range.lengthValue();
            if (reversalFits(start, step) && length.bitLength() < Long.SIZE) {
                long newStop = start - step;
                return new Fast(newStop + length.longValue() * step, -step, length.longValue());
            }
        }
        BigInteger length = range.lengthValue();
        return new Wide(
                range.start().getValue().add(length.subtract(ONE).multiply(range.step().getValue())),
                range.step().getValue().negate(),
                length);
    }

    /** {@code PyLong_AsLong}'s answer, or null when the number is wider than a C {@code long}. */
    private static Long asLong(BigInteger value) {
        return value.bitLength() < Long.SIZE ? value.longValue() : null;
    }

    /**
     * {@code range_iter}'s guard on the step that is never taken: the cursor adds {@code step} once more
     * than there are values, so {@code start + len * step} has to fit too.
     *
     * <p>C writes the test as two comparisons that cannot overflow, on {@code stop} rather than on the
     * product, and that is the shape kept here — the product is what {@code rangeiter_next} computes.
     */
    private static boolean overruns(long start, long stop, long step) {
        return step > 0
                ? stop > Long.MAX_VALUE - (step - 1)
                : stop < Long.MIN_VALUE + (-1 - step);
    }

    /**
     * {@code range_reverse}'s two unsigned checks on {@code start - step}, which is the bound the reversed
     * cursor walks to.
     *
     * <p>The C source rearranges each comparison to a subtraction of unsigned longs precisely so that it
     * cannot overflow; {@link Long#compareUnsigned} is the same arithmetic, and the same rule that makes
     * {@code range(-2 ** 63, 0)} too wide to reverse.
     */
    private static boolean reversalFits(long start, long step) {
        return step > 0
                ? Long.compareUnsigned(start - Long.MIN_VALUE, step) >= 0
                : Long.compareUnsigned(Long.MAX_VALUE - start, -step) >= 0;
    }

    /** {@code Py_BuildValue("N(N)O", iter, range, None)} — the builtin reached for at the call. */
    private static PyObject reducedTo(PyRange rest) {
        return PyTuple.of(
                PyRuntime.builtin("iter"),
                PyTuple.of(rest),
                PyNone.INSTANCE);
    }

    /** {@code rangeiter_reduce}: the remaining values as a range, computed in C longs. */
    private static PyObject reduceFast(PyObject self) {
        Fast iterator = (Fast) self;
        return reducedTo(
                PyRange.of(
                        BigInteger.valueOf(iterator.start),
                        BigInteger.valueOf(iterator.start + iterator.remaining * iterator.step),
                        BigInteger.valueOf(iterator.step)));
    }

    /** {@code longrangeiter_reduce}: the same three numbers, added in whatever width they need. */
    private static PyObject reduceWide(PyObject self) {
        Wide iterator = (Wide) self;
        return reducedTo(
                PyRange.of(
                        iterator.start,
                        iterator.start.add(iterator.remaining.multiply(iterator.step)),
                        iterator.step));
    }

    /**
     * The cursor that counts in C {@code long}s: {@code _PyRangeIterObject}, three words and a
     * {@code tp_basicsize} of forty.
     */
    private static final class Fast extends PyIterator {

        private long start;
        private final long step;
        private long remaining;

        Fast(long start, long step, long remaining) {
            super(PyType.RANGE_ITERATOR);
            this.start = start;
            this.step = step;
            this.remaining = remaining;
        }

        @Override
        public PyObject nextOrNull() {
            if (remaining <= 0) {
                return null;
            }
            long value = start;
            start = value + step;
            remaining--;
            return PyInt.of(value);
        }

        /**
         * {@code rangeiter_setstate}: {@code PyLong_AsLong} first, then a clip that refuses nothing.
         *
         * <p>The index is not an index here but a count of values to skip, so a negative one and one past
         * the end are both accepted and pulled inside, and the exhaustion a {@code StopIteration} would
         * name is simply an iterator with no values left.
         */
        void stepTo(PyObject state) {
            BigInteger index = Numbers.asIndex(state);
            if (index.bitLength() >= Long.SIZE) {
                throw Slots.overflowError("Python int too large to convert to C long");
            }
            long at = index.longValue();
            if (at < 0) {
                at = 0;
            } else if (at > remaining) {
                at = remaining;
            }
            start += at * step;
            remaining -= at;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<" + getType().name() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }
    }

    /** The cursor that counts in Python integers: {@code longrangeiterobject}, three pointers. */
    private static final class Wide extends PyIterator {

        private BigInteger start;
        private final BigInteger step;
        private BigInteger remaining;

        Wide(BigInteger start, BigInteger step, BigInteger remaining) {
            super(PyType.LONG_RANGE_ITERATOR);
            this.start = start;
            this.step = step;
            this.remaining = remaining;
        }

        @Override
        public PyObject nextOrNull() {
            if (remaining.signum() <= 0) {
                return null;
            }
            PyObject value = PyInt.of(start);
            start = start.add(step);
            remaining = remaining.subtract(ONE);
            return value;
        }

        /**
         * {@code longrangeiter_setstate}: an exact int or nothing, and then the same clip.
         *
         * <p>The fast cursor converts, so it takes a bool and anything with an {@code __index__}; this one
         * compares the type, so {@code True} is refused for being a bool and the complaint names what came
         * back rather than what was asked — {@code state must be an int, not bool}.
         */
        void stepTo(PyObject state) {
            if (state.getType() != PyType.INT) {
                throw Slots.typeError(
                        "state must be an int, not %s", state.getType().filedName());
            }
            BigInteger at = ((PyInt) state).getValue();
            if (at.signum() < 0) {
                at = BigInteger.ZERO;
            } else if (at.compareTo(remaining) > 0) {
                at = remaining;
            }
            start = start.add(at.multiply(step));
            remaining = remaining.subtract(at);
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<" + getType().name() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }
    }
}
