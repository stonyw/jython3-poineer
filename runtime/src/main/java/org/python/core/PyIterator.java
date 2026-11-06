package org.python.core;

import java.util.List;

/**
 * An iterator: the object {@code GET_ITER} produces and {@code FOR_ITER} drives.
 *
 * The end of iteration is a Java {@code null} from {@link #nextOrNull()} rather than a raised
 * {@code StopIteration}, because in CPython that exception is an internal signal too — it never
 * escapes a {@code for} loop, and {@code FOR_ITER} tests for it by identity. It is still a Python
 * visible exception, so {@code next(it)} and {@code StopIteration} as a value live in
 * {@link ExceptionTypes} and {@link Builtins}, not here.
 */
public abstract class PyIterator extends PyObject {

    protected PyIterator() {
        this(PyType.ITERATOR);
    }

    /**
     * An iterator whose type is its own, which is what {@code generator} is: CPython declares a
     * {@code PyType} with a {@code tp_iternext} of its own rather than reusing {@code iterator}.
     */
    protected PyIterator(PyType type) {
        super(type);
    }

    /** @return the next value, or null when the iteration is over. */
    public abstract PyObject nextOrNull();

    /**
     * The exception the step that just ended this iteration was made of, or null when it simply ran out.
     *
     * <p>CPython keeps the difference inside {@code tp_iternext}: a generator whose body executes {@code
     * return 42} comes back through {@code gen_iternext} with a {@code StopIteration} carrying 42, while a
     * finished one returns null with no exception at all and {@code next()} has to invent the bare one. Two
     * of {@code next}'s three outcomes are that difference, and {@code FOR_ITER} ignores it — CPython's
     * loop ends the iteration on either.
     *
     * <p>An iterator that reports null here has already stopped, so the answer belongs to the step that
     * stopped it: {@code next(it)} is asked after reading it, and a second read gives nothing, exactly as a
     * second {@code next()} on a finished generator does.
     */
    public PyBaseException exhaustion() {
        return null;
    }

    /** {@code iter(it)} is {@code it}: an iterator is its own iterator. */
    @Override
    public PyObject iterator() {
        return this;
    }

    /**
     * {@code tp_iternext} read as an attribute: {@code it.__next__()}, which raises the end rather than
     * signalling it.
     *
     * <p>{@code Builtins.next} and a {@code for} loop are the two other readers of the same slot, and each
     * has somewhere to put a null — a default to hand back, a loop to leave. {@code __next__} has neither,
     * so CPython's {@code slot_tp_iternext} turns the null into the exception {@code PyIter_Next} would
     * have set: the one the step that ended the iteration was made of when there was one, and a bare
     * {@code StopIteration} invented at this point when the cursor simply ran out.
     */
    public PyObject stepped() {
        PyObject value = nextOrNull();
        if (value != null) {
            return value;
        }
        PyBaseException stopped = exhaustion();
        if (stopped != null) {
            throw PyRaise.reraise(stopped);
        }
        throw PyRaise.of(new PyBaseException(ExceptionTypes.STOP_ITERATION, new PyObject[0]));
    }

    @Override
    public String repr() {
        return "<iterator object>";
    }

    /** Iteration over a fixed array, which is what a {@code tuple} or a {@code str} hands out. */
    public static PyIterator of(PyObject[] items) {
        return new ArrayIterator(items, 0);
    }

    /**
     * A one-shot cursor over values that are already made, reported as a type of its own.
     *
     * <p>{@code line_iterator} and {@code positions_iterator} are CPython's two: a code object's locations
     * are decoded once by the front-end that reads them, and what Python is handed is the cursor rather
     * than the tuple, because {@code type(co.co_lines())} names the run and not a {@code tuple}. CPython
     * keeps a pointer into the table and steps one entry at a time; this keeps an index into the values
     * and cannot tell the difference from the outside, since neither iterator can be rewound.
     *
     * <p>{@code co_branches()} is the third, and the only one of the three that reads no table: it walks the
     * code array for the opcodes with two ways. CPython gives it its own {@code PyTypeObject} all the same,
     * and writes the same {@code tp_name} into that one as into the line cursor's, so the name Python prints
     * cannot tell those two apart either — which is why the type is asked for here rather than worked out
     * from the values.
     */
    public static PyIterator of(PyType type, PyObject[] items) {
        return new FixedIterator(type, items);
    }

    public static PyIterator of(List<PyObject> items) {
        return new ListIterator(items);
    }

    private static final class ArrayIterator extends PyIterator {

        private final PyObject[] items;
        private int at;

        ArrayIterator(PyObject[] items, int at) {
            this.items = items;
            this.at = at;
        }

        @Override
        public PyObject nextOrNull() {
            return at >= items.length ? null : items[at++];
        }
    }

    /**
     * A cursor over values that were decoded before it was made.
     *
     * <p>Its own type, and its own repr, because CPython's three uses of this are {@code line_iterator} twice
     * over and {@code positions_iterator}, never {@code iterator} — a program that prints the type of what
     * {@code co_lines()} answered is the only way to tell them apart, and both reprs carry an address no
     * differential test compares.
     */
    private static final class FixedIterator extends PyIterator {

        private final PyObject[] items;
        private int at;

        FixedIterator(PyType type, PyObject[] items) {
            super(type);
            this.items = items;
        }

        @Override
        public PyObject nextOrNull() {
            return at >= items.length ? null : items[at++];
        }

        @Override
        public String repr() {
            return "<" + getType().name() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }
    }

    /**
     * Iteration over a snapshot list, made by {@code reversed} out of a sequence type that has no
     * cursor of its own yet.
     *
     * <p>No size guard stands here, and none should: {@code listiter_next}
     * ({@code Objects/listobject.c:4040}) reads {@code PyList_GET_SIZE} on every step and so happily
     * walks items appended after the cursor was made — the slice that measured the six sequence
     * cursors confirmed a growing list is silently seen rather than complained about. The
     * {@code changed size during iteration} refusals belong to the dict and set cursors, not to a
     * list's, and this list is a snapshot nothing outside this iterator can reach anyway.
     */
    private static final class ListIterator extends PyIterator {

        private final List<PyObject> items;
        private int at;

        ListIterator(List<PyObject> items) {
            this.items = items;
        }

        @Override
        public PyObject nextOrNull() {
            return at >= items.size() ? null : items.get(at++);
        }
    }
}
