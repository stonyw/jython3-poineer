package org.python.core;

/**
 * The cursor {@code reversed} makes for the sequences that have no walk of their own to hand out.
 *
 * <p>{@code enumobject.c} writes two things in one file: the type and the constructor that is also the
 * builtin. {@code reversed_vectorcall} ({@code :423}) is the type's own {@code tp_vectorcall}, so
 * {@code reversed(x)} is {@code reversed_new_impl} ({@code :380}) with the class filled in, and
 * {@code reversed.__new__(reversed, x)} is the same function through {@code tp_new_wrapper} — which is why
 * the two spellings refuse a bad call in the same words and why the name in {@code builtins} is a type
 * rather than the function {@code map} and {@code zip} are.
 *
 * <p>What the constructor asks, in its order: the {@code __reversed__} of the value's <em>type</em>, which a
 * list, a range, a dict and a window each answer with a cursor of their own and which a {@code None} in that
 * position refuses as firmly as an absence; then {@code PySequence_Check}, which is the protocol rather than
 * a try at it, so a class with no {@code __getitem__} is refused whatever it may hold; then {@code
 * PySequence_Size}, whose refusal a class with {@code __getitem__} but no {@code __len__} reaches. Only then
 * is the cursor made — and made holding the sequence, not a copy of it, which is the whole of the difference
 * between this cursor and the seven that walk a hash table: every step reads the item again, so a source
 * cut down during the walk is noticed by the read rather than by a count, and the complaint it brings back
 * is the one the source makes.
 *
 * <p>The one row this cursor files that a hash cursor does not is {@code __setstate__}, and it is the row
 * with the strangest guard: a cursor that has run out has {@code -1} in its index, and the restart reads
 * that and does nothing at all — the comment in the C says it is only there for backwards compatibility, and
 * it is what makes an empty source unrestartable for as long as it lives, since {@code n - 1} for a source
 * of nought <em>is</em> that marker.
 */
final class Reversed {

    /** {@code length_hint_doc}, the same sentence all five cursor files write. */
    private static final String LENGTH_HINT_DOC =
            "Private method returning an estimate of len(list(it)).";

    /** {@code reduce_doc}. */
    private static final String REDUCE_DOC = "Return state information for pickling.";

    /** {@code setstate_doc}, the sentence of the row the seven hash cursors have no need of. */
    private static final String SETSTATE_DOC = "Set state information for unpickling.";

    /**
     * The three rows of {@code reversediter_methods} ({@code :538}).
     *
     * <p>{@code __iter__} and {@code __next__} are not here: they are the two slot wrappers {@code TypeDict}
     * files beside these, and {@code __new__} is the creator above them, which is filed from the constructor
     * rather than from this table because a type's {@code tp_new} is not one of its {@code PyMethodDef}s.
     */
    private static final Members BOOK =
            Members.of(PyType.REVERSED)
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

    private Reversed() {
    }

    /**
     * {@code reversed_new_impl}: the special, then the protocol, then the count.
     *
     * <p>What the special hands back is returned without being looked at, so {@code reversed} of a class
     * whose {@code __reversed__} answers a list is that list and not a cursor — the one builtin call in this
     * family that can hand back a value of a type it did not make.
     */
    static PyObject over(PyObject source) {
        PyObject hook = Dirs.lookupSpecial(source, "__reversed__");
        if (hook == null) {
            if (!Sequences.isSequence(source)) {
                throw notReversible(source);
            }
            return new Cursor(PyType.REVERSED, source, counted(source) - 1);
        }
        if (hook == PyNone.INSTANCE) {
            throw notReversible(source);
        }
        return Slots.call(hook, new PyObject[0], null);
    }

    /** {@code "'%.200s' object is not reversible"}, worded from the value's class either way. */
    private static PyRaise notReversible(PyObject source) {
        return Slots.typeError("'%s' object is not reversible", source.getType().name());
    }

    /** {@code PySequence_Size}: the count, refused in the words {@code len()} uses. */
    private static long counted(PyObject source) {
        return Numbers.size(Slots.length(source));
    }

    /**
     * CPython's two words: {@code ro->index} and {@code ro->seq}, the second of them dropped by the step
     * that finds nothing under the first.
     */
    private static final class Cursor extends PyIterator {

        /** Null once a step has run past the end, which is what no later {@code __setstate__} puts back. */
        private PyObject source;

        /** {@code ro->index}: the position of the next item, or the {@code -1} that there is not one. */
        private long at;

        Cursor(PyType type, PyObject source, long at) {
            super(type);
            this.source = source;
            this.at = at;
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

        /**
         * {@code reversed_next}: the item at the index, read from the sequence as it stands now.
         *
         * <p>Two of the source's own refusals are the end of the walk rather than an error — a class that
         * counts out with {@code IndexError} or {@code StopIteration} is a sequence as far as this is
         * concerned — and both are cleared on the way to the same marker the empty source is born with.
         * Anything else the read brings back is left standing: a {@code KeyError} from a mapping that
         * subscripts by position travels out of {@code list(reversed(x))} unchanged.
         */
        @Override
        public PyObject nextOrNull() {
            if (at >= 0) {
                try {
                    PyObject item = Slots.getItem(source, PyInt.of(at));
                    at--;
                    return item;
                } catch (PyRaise refusal) {
                    if (!ended(refusal)) {
                        throw refusal;
                    }
                }
            }
            at = -1;
            source = null;
            return null;
        }

        /** Whether the refusal {@code reversed_next} clears rather than reports. */
        private static boolean ended(PyRaise refusal) {
            PyType kind = refusal.value().exceptionType();
            return kind.matches(ExceptionTypes.INDEX_ERROR)
                    || kind.matches(ExceptionTypes.STOP_ITERATION);
        }

        /**
         * {@code reversed_len}: the index plus one, unless the sequence has become shorter than that, in
         * which case nought — and the count is asked of the live source, so a {@code __len__} that complains
         * is heard here too.
         *
         * <p>The {@code -1} is read before anything is measured, which is why a spent cursor answers nought
         * without touching the sequence it dropped.
         */
        PyObject hint() {
            if (at == -1) {
                return PyInt.ZERO;
            }
            long position = at + 1;
            return PyInt.of(counted(source) < position ? 0 : position);
        }

        /**
         * {@code "O(O)n"} over the sequence and the index still standing, {@code "O(())"} once they are
         * gone: the payload names the cursor's own type rather than {@code iter}, and hands that type the
         * sequence entire rather than the rest of the walk — which is what the {@code __setstate__} beside it
         * is for.
         *
         * <p>The dead half writes no index at all, and its argument list holds not the source but the empty
         * tuple, since {@code ()} is a value inside the {@code ( … )} that makes the list: what being found
         * again hands back is a cursor over nothing, with nothing for a state to jump into.
         */
        PyObject reduce() {
            return at == -1
                    ? PyTuple.of(getType(), PyTuple.of(PyTuple.EMPTY))
                    : PyTuple.of(getType(), PyTuple.of(source), PyInt.of(at));
        }

        /**
         * {@code reversed_setstate}: the number first, in {@code PyLong_AsSsize_t}'s own words, and the
         * guard after it.
         *
         * <p>The window is {@code [-1, n - 1]} and the top of it is the marker at the bottom, so a cursor
         * put at {@code -1} by this row is as spent as one that walked out — while a cursor that is already
         * spent never reaches the comparison at all and so never asks its source how long it is.
         */
        void stepTo(PyObject state) {
            long index = Numbers.asSsizeT(state);
            if (at != -1) {
                long last = counted(source) - 1;
                at = index < -1 ? -1 : Math.min(index, last);
            }
        }
    }
}
