package org.python.core;

/**
 * The six cursors the four sequence types hand out, which are one book filed six times.
 *
 * <p>CPython writes the book into five method tables across four C files — {@code listiter_methods} ({@code
 * listobject.c:3961}) and {@code listreviter_methods} ({@code :4115}), {@code tupleiter_methods} ({@code
 * tupleobject.c:1133}), {@code striter_methods} ({@code bytesobject.c:3465}) and {@code unicodeiter_methods}
 * ({@code unicodeobject.c:16474}), which the two string cursors share because they are one struct with two
 * {@code tp_iternext}s and {@code unicode_iter} asks the string which one it wants. What the six have in
 * common is that every one of them is two words, {@code it_index} and {@code it_seq}, and the three rows
 * Python can read out of them are the three this class files: an estimate of what is left, the state to
 * pickle, and the state to come back from.
 *
 * <p>Those three rows disagree in two places a program can see. The list cursors read the list as it is now,
 * so a walk notices what was appended to it and stops short when the list is cut down; the four that hold a
 * tuple, bytes or text read a size that cannot move, and so report the index they ran out at rather than a
 * spent marker. And a spent cursor reports an empty source rather than the one it dropped, each body making
 * its own: {@code listiter_reduce_general} builds {@code "N(N)"} over a fresh {@code PyList_New(0)} and
 * {@code unicodeiter_reduce} the same over an empty string, while {@code tupleiter_reduce} and {@code
 * striter_reduce} — the bytes cursor's own, named for the struct CPython inherited — build {@code "N(())"},
 * so an exhausted {@code bytes_iterator} pickles as a call of {@code iter} on an empty <em>tuple</em>.
 */
final class SequenceIterators {

    /** {@code length_hint_doc}, written once in each of the four files that needs it. */
    private static final String LENGTH_HINT_DOC =
            "Private method returning an estimate of len(list(it)).";

    /** {@code reduce_doc}, the same sentence as the range cursors' and for the same reason. */
    private static final String REDUCE_DOC = "Return state information for pickling.";

    /** {@code setstate_doc}. */
    private static final String SETSTATE_DOC = "Set state information for unpickling.";

    /**
     * The three rows of one {@code Xiter_methods} list.
     *
     * <p>{@code __iter__} and {@code __next__} are not here: they are slot wrappers, which {@code TypeDict}
     * files beside these from the value's own slots, as it does for the two range cursors.
     */
    private static Members book(PyType owner) {
        return Members.of(owner)
                .addNoArgument(
                        "__length_hint__", LENGTH_HINT_DOC, self -> ((Cursor) self).hint())
                .addNoArgument("__reduce__", REDUCE_DOC, self -> ((Cursor) self).reduce())
                .addOne(
                        "__setstate__",
                        SETSTATE_DOC,
                        (self, state) -> {
                            ((Cursor) self).stepTo(state);
                            return PyNone.INSTANCE;
                        });
    }

    private static final Members LIST = book(PyType.LIST_ITERATOR);
    private static final Members LIST_BACK = book(PyType.LIST_REVERSE_ITERATOR);
    private static final Members TUPLE = book(PyType.TUPLE_ITERATOR);
    private static final Members BYTES = book(PyType.BYTES_ITERATOR);
    private static final Members TEXT = book(PyType.STR_ITERATOR);
    private static final Members ASCII_TEXT = book(PyType.STR_ASCII_ITERATOR);

    private SequenceIterators() {
    }

    /** {@code list_iter}: the cursor that keeps a pointer to the list and reads it at every step. */
    static PyIterator list(PyList source) {
        return new ListForward(source);
    }

    /** {@code list.__reversed__}: the same pointer, walking back from the item below the last one. */
    static PyIterator listBackwards(PyList source) {
        return new ListBackward(source);
    }

    /**
     * {@code tuple_iter}, {@code bytes_iter} and {@code unicode_iter}, which are the same three lines over
     * three sources — only the string picks between two types.
     */
    static PyIterator of(PyObject source) {
        return new Fixed(source);
    }

    /**
     * CPython's two words: an index and the source it points into, the second of them dropped by the step
     * that finds there is nothing left.
     */
    private abstract static class Cursor extends PyIterator {

        /** Null once a step has run past the end, which is what no {@code __setstate__} can undo. */
        PyObject source;

        /** {@code it_index}: the position of the next item, or the marker that there is not one. */
        int at;

        Cursor(PyType type, PyObject source) {
            super(type);
            this.source = source;
        }

        /** Whether {@code __reduce__} reports the source and the index in it, rather than an empty one. */
        abstract boolean live();

        /** {@code Py_SIZE(it->it_seq)} as it is now, which is the whole of the list cursor's difference. */
        abstract int size();

        abstract PyObject item(int index);

        abstract PyObject hint();

        abstract PyObject reduce();

        /**
         * {@code Xiter_setstate}: the number is read as a {@code Py_ssize_t} before the guard on
         * {@code it_seq}, so a cursor that has run out still refuses a float.
         */
        abstract void stepTo(PyObject state);

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<" + getType().name() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }

        /**
         * {@code "N(O)n"} over the pair still standing, {@code "N(N)"} over an empty source — both of
         * them a one-item argument tuple, which is what makes the payload {@code (iter, (seq,), index)}.
         */
        final PyObject reported(String name, PyObject spent) {
            return live()
                    ? PyTuple.of(PyRuntime.builtin(name), PyTuple.of(source), PyInt.of(at))
                    : PyTuple.of(PyRuntime.builtin(name), PyTuple.of(spent));
        }

        /** The clip C writes as two comparisons, pulling the number inside {@code [low, high]}. */
        static int clip(long index, long low, long high) {
            return (int) (index < low ? low : Math.min(index, high));
        }
    }

    /** The two cursors a list hands out, which read a source that may be changing underneath them. */
    private abstract static class ListCursor extends Cursor {

        ListCursor(PyType type, PyList source) {
            super(type, source);
        }

        @Override
        final int size() {
            return ((PyList) source).size();
        }

        @Override
        final PyObject item(int index) {
            return ((PyList) source).at(index);
        }

        /**
         * The step past the last item spends the cursor the way both C functions do it: the index goes to
         * {@code -1} and the pointer is dropped, so a later {@code __setstate__} has nothing to clip into.
         */
        final PyObject stepOff() {
            at = -1;
            source = null;
            return null;
        }
    }

    /** {@code listiterobject}: the index walks up, and {@code -1} is the marker that there is no next. */
    private static final class ListForward extends ListCursor {

        ListForward(PyList source) {
            super(PyType.LIST_ITERATOR, source);
        }

        @Override
        public PyObject nextOrNull() {
            if (at < 0) {
                return null;
            }
            if (at >= size()) {
                return stepOff();
            }
            return item(at++);
        }

        /** {@code listiter_len}: the count comes off the list as it stands, and a negative one is nought. */
        @Override
        PyObject hint() {
            return PyInt.of(at >= 0 ? Math.max(0, size() - at) : 0);
        }

        @Override
        PyObject reduce() {
            return reported("iter", PyList.empty());
        }

        /**
         * {@code listiter_setstate}: the window is {@code [-1, len]}, so the number that spends the cursor
         * is one it can be put back from — the pointer is still held until a step actually runs past the end.
         */
        @Override
        void stepTo(PyObject state) {
            long index = Numbers.asSsizeT(state);
            if (source != null) {
                at = clip(index, -1, size());
            }
        }

        @Override
        boolean live() {
            return at >= 0;
        }
    }

    /** {@code listreviterobject}: the index walks down from the last item, and nought is the last of them. */
    private static final class ListBackward extends ListCursor {

        ListBackward(PyList source) {
            super(PyType.LIST_REVERSE_ITERATOR, source);
            this.at = source.size() - 1;
        }

        @Override
        public PyObject nextOrNull() {
            if (at < 0) {
                return null;
            }
            if (at >= size()) {
                return stepOff();
            }
            PyObject value = item(at);
            at--;
            return value;
        }

        /**
         * {@code listreviter_len}: the count is the index plus one, which needs no reading of the list —
         * until the list gets shorter than that and the answer becomes nought.
         */
        @Override
        PyObject hint() {
            long remaining = at + 1L;
            return PyInt.of(source == null || size() < remaining ? 0 : (int) remaining);
        }

        @Override
        PyObject reduce() {
            return reported("reversed", PyList.empty());
        }

        /** {@code listreviter_setstate}: the window is {@code [-1, len - 1]}, the last item being index 0. */
        @Override
        void stepTo(PyObject state) {
            long index = Numbers.asSsizeT(state);
            if (source != null) {
                at = clip(index, -1, size() - 1L);
            }
        }

        @Override
        boolean live() {
            return at >= 0;
        }
    }

    /**
     * The cursor {@code tupleobject.c}, {@code bytesobject.c} and {@code unicodeobject.c} each write for
     * itself, over a source that cannot change.
     *
     * <p>None of the three resets its index when it runs out: the C structs keep {@code it_index} where it
     * landed and clear only {@code it_seq}, which is what an exhausted cursor reports as the whole of its
     * state. {@code __setstate__} therefore does nothing at all to a spent one, and a float handed to it is
     * still refused.
     */
    private static final class Fixed extends Cursor {

        /** The value {@code __reduce__} reports once the source has been dropped. */
        private final PyObject spent;

        Fixed(PyObject source) {
            super(typeOf(source), source);
            this.spent = emptyOf(source);
        }

        @Override
        public PyObject nextOrNull() {
            if (source == null) {
                return null;
            }
            if (at < size()) {
                return item(at++);
            }
            source = null;
            return null;
        }

        /** {@code tupleiter_len}, {@code striter_len} and {@code unicodeiter_len} are the same three lines. */
        @Override
        PyObject hint() {
            return PyInt.of(source == null ? 0 : size() - at);
        }

        @Override
        PyObject reduce() {
            return reported("iter", spent);
        }

        /** {@code ..._setstate}: the window is {@code [0, len]}, and a spent cursor takes nothing. */
        @Override
        void stepTo(PyObject state) {
            long index = Numbers.asSsizeT(state);
            if (source != null) {
                at = clip(index, 0, size());
            }
        }

        @Override
        boolean live() {
            return source != null;
        }

        @Override
        int size() {
            if (source instanceof PyStr text) {
                return text.codePointLength();
            }
            return source instanceof PyBytes octets ? octets.size() : ((PyTuple) source).size();
        }

        @Override
        PyObject item(int index) {
            if (source instanceof PyStr text) {
                return text.characterAt(index);
            }
            return source instanceof PyBytes octets
                    ? PyInt.of(octets.byteAt(index) & 0xff)
                    : ((PyTuple) source).at(index);
        }

        /** {@code unicode_iter}'s two types over one struct, and the other two files' one type each. */
        private static PyType typeOf(PyObject source) {
            if (source instanceof PyStr text) {
                return text.isAscii() ? PyType.STR_ASCII_ITERATOR : PyType.STR_ITERATOR;
            }
            return source instanceof PyBytes ? PyType.BYTES_ITERATOR : PyType.TUPLE_ITERATOR;
        }

        /**
         * The empty source of the type's own, except for {@code bytes}: {@code striter_reduce} hands
         * {@code "N(())"} where {@code unicodeiter_reduce} hands {@code "N(N)"} over an empty string, so an
         * exhausted octet cursor pickles as a call of {@code iter} on an empty tuple.
         */
        private static PyObject emptyOf(PyObject source) {
            return source instanceof PyStr ? PyStr.of("") : PyTuple.EMPTY;
        }
    }
}
