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
        super(PyType.ITERATOR);
    }

    /** @return the next value, or null when the iteration is over. */
    public abstract PyObject nextOrNull();

    /** {@code iter(it)} is {@code it}: an iterator is its own iterator. */
    @Override
    public PyObject iterator() {
        return this;
    }

    @Override
    public String repr() {
        return "<iterator object>";
    }

    /** Iteration over a fixed array, which is what a {@code tuple} or a {@code str} hands out. */
    public static PyIterator of(PyObject[] items) {
        return new ArrayIterator(items, 0);
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
     * Iteration over a list, which differs from the array case in one way that matters: CPython
     * detects the list changing size under the iterator and raises {@code RuntimeError}, where an
     * index-based loop would silently skip or repeat. That check is the length this iterator
     * remembers, and it is deliberately the only mutation guard here.
     */
    private static final class ListIterator extends PyIterator {

        private final List<PyObject> items;
        private int at;
        private final int expectedSize;

        ListIterator(List<PyObject> items) {
            this.items = items;
            this.expectedSize = items.size();
        }

        @Override
        public PyObject nextOrNull() {
            if (items.size() != expectedSize) {
                throw Slots.runtimeError("list changed size during iteration");
            }
            return at >= items.size() ? null : items.get(at++);
        }
    }
}
