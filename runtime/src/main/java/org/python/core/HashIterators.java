package org.python.core;

import java.util.Arrays;

/**
 * The seven cursors the two hash tables hand out, which are one book filed seven times.
 *
 * <p>CPython writes the book twice and points the types at it. {@code dictiter_methods} ({@code
 * Objects/dictobject.c:5211}) is the {@code tp_methods} of all six dict cursors — the three that read the
 * table forwards, made by {@code dict_iter} ({@code :4978}), {@code dictvalues_iter} ({@code :6703}) and
 * {@code dictitems_iter} ({@code :6591}), and the three that read it backwards, which are the same struct
 * started at the last entry and share one {@code tp_iternext} that asks its own type which of the three to
 * hand back ({@code :5840}) — and {@code setiter_methods} ({@code Objects/setobject.c:895}) is the table of
 * the one {@code set_iterator}, which {@code set} and {@code frozenset} share, and which a {@code
 * mappingproxy} hands out for its own keys by asking the dictionary underneath. Both tables are two {@code
 * PyMethodDef} rows, the estimate and the pickle, and neither has a {@code __setstate__} beside them: a
 * cursor over a hash table is a position in a table that may have been rebuilt since, so there is nothing
 * to put back.
 *
 * <p>What the two C structs hold is a pointer, a position, a count and the count the table had when the
 * cursor was made — {@code di_dict}, {@code di_pos}, {@code len}, {@code di_used}, and the set's four with
 * {@code si_} in front. The last of the four is why these cursors are the ones that complain: every step
 * compares it against the live size and refuses when they have parted, and the refusal marks the cursor
 * {@code -1}, which no later change to the table can talk out of being a refusal. The three the sequence
 * cursors file become the two here, and the walk that could not change becomes a walk that may, but only
 * between the counts.
 */
final class HashIterators {

    /** {@code length_hint_doc}, written once in each of the two files that needs it. */
    private static final String LENGTH_HINT_DOC =
            "Private method returning an estimate of len(list(it)).";

    /** {@code reduce_doc}, the same sentence as the sequence cursors' and for the same reason. */
    private static final String REDUCE_DOC = "Return state information for pickling.";

    /**
     * The two rows of one {@code Xiter_methods} list.
     *
     * <p>{@code __iter__} and {@code __next__} are not here: they are slot wrappers, which {@code TypeDict}
     * files beside these from the value's own slots, as it does for the range's and the sequences' cursors.
     * There is no third row either — none of the seven C types can be restarted from a saved state.
     */
    private static Members book(PyType owner) {
        return Members.of(owner)
                .addNoArgument(
                        "__length_hint__", LENGTH_HINT_DOC, self -> ((Cursor) self).hint())
                .addNoArgument("__reduce__", REDUCE_DOC, self -> ((Cursor) self).reduce());
    }

    private static final Members KEYS = book(PyType.DICT_KEY_ITERATOR);
    private static final Members VALUES = book(PyType.DICT_VALUE_ITERATOR);
    private static final Members ITEMS = book(PyType.DICT_ITEM_ITERATOR);
    private static final Members KEYS_BACK = book(PyType.DICT_REVERSE_KEY_ITERATOR);
    private static final Members VALUES_BACK = book(PyType.DICT_REVERSE_VALUE_ITERATOR);
    private static final Members ITEMS_BACK = book(PyType.DICT_REVERSE_ITEM_ITERATOR);
    private static final Members SET = book(PyType.SET_ITERATOR);

    private HashIterators() {
    }

    /** {@code dict_iter} and {@code dictkeys_iter}: the keys in the order they were written. */
    static PyIterator keys(PyDict source) {
        return new Forward(PyType.DICT_KEY_ITERATOR, source, source.keys());
    }

    /** {@code dictvalues_iter} and {@code dict.__reversed__} reversed: the values in that order. */
    static PyIterator values(PyDict source) {
        return new Forward(PyType.DICT_VALUE_ITERATOR, source, source.values());
    }

    /** {@code dictitems_iter}: the pairs, each built as the step that hands it out reaches it. */
    static PyIterator items(PyDict source) {
        return new Forward(PyType.DICT_ITEM_ITERATOR, source, source.pairs());
    }

    /** {@code dict.__reversed__}: the keys from the last entry of the table back to the first. */
    static PyIterator backwardsKeys(PyDict source) {
        return new Backward(PyType.DICT_REVERSE_KEY_ITERATOR, source, source.keys());
    }

    /** The values of {@code reversed(d.values())}, read from the tail of the table. */
    static PyIterator backwardsValues(PyDict source) {
        return new Backward(PyType.DICT_REVERSE_VALUE_ITERATOR, source, source.values());
    }

    /** The pairs of {@code reversed(d.items())}, read from the tail of the table. */
    static PyIterator backwardsItems(PyDict source) {
        return new Backward(PyType.DICT_REVERSE_ITEM_ITERATOR, source, source.pairs());
    }

    /** {@code set_iter}: the one cursor two set types and a window's keys all hand out. */
    static PyIterator over(PySet source) {
        return new Forward(PyType.SET_ITERATOR, source, source.elements());
    }

    /**
     * CPython's four words: a table, a position in it, the count still to hand out, and the size the table
     * had when the cursor was made.
     *
     * <p>The third is a difference of positions here, because a cursor walks an array taken from the table
     * rather than the table itself — see {@link #count()} for what that costs.
     */
    private abstract static class Cursor extends PyIterator {

        /** {@code di_dict} and {@code si_set}: null once a step has run past the last entry. */
        PyObject source;

        /** The entries as the table held them when the cursor was made. */
        final PyObject[] itinerary;

        /** {@code di_used} and {@code si_used}: {@code -1} once a step has refused the table. */
        int used;

        /** {@code di_pos} and {@code si_pos}: the next entry for a forward cursor, the last for a reverse. */
        int at;

        Cursor(PyType type, PyObject source, PyObject[] itinerary) {
            super(type);
            this.source = source;
            this.itinerary = itinerary;
            this.used = count();
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

        /** Whether the position still has an entry under it. */
        abstract boolean holds();

        /** The entry at the position, and the move to the next one — {@code di_pos = i+1} or {@code i-1}. */
        abstract PyObject hand();

        /** The entries still to hand out, in the order the walk would hand them. */
        abstract PyObject[] left();

        /**
         * {@code ma_used} or {@code so->used}: the count the C guard compares its captured one against.
         *
         * <p>Only a size, which is the whole of what a cursor here notices: writing a value over a key that
         * is already in the table moves nothing and is not complained about, and a table that is cut down
         * and rebuilt to the same count between two steps has parted from the array this cursor took.
         */
        final int count() {
            return source instanceof PyDict dict ? dict.size() : ((PySet) source).size();
        }

        /** Which of the two files wrote the refusal this cursor makes. */
        final String refusal() {
            return source instanceof PyDict
                    ? "dictionary changed size during iteration"
                    : "Set changed size during iteration";
        }

        /** {@code dictiter_len} and {@code setiter_len}: the count, or nought once the two have parted. */
        PyObject hint() {
            return PyInt.of(source != null && used == count() ? remaining() : 0);
        }

        /**
         * {@code "N(N)"} over a list of what the walk still has, which is the one payload shape of the
         * seven: {@code dictiter_reduce} and {@code setiter_reduce} are the same six lines.
         *
         * <p>The list is made by driving a <em>copy</em> of the cursor through {@code PySequence_List}, so
         * the cursor a program holds is neither consumed by the question nor marked by the refusal it may
         * bring back — the copy is the one that runs out, or the one that says the table changed.
         */
        PyObject reduce() {
            PyObject[] rest = reported();
            return PyTuple.of(PyRuntime.builtin("iter"), PyTuple.of(PyList.of(rest)));
        }

        /** The rest of the walk, asked the way {@code PySequence_List} asks it and without marking. */
        private PyObject[] reported() {
            if (source == null) {
                return new PyObject[0];
            }
            if (used != count()) {
                throw Slots.runtimeError(refusal());
            }
            return left();
        }

        /** {@code di->len} and {@code si->len}: the count still to hand out, in the order of the walk. */
        final int remaining() {
            return Math.max(0, itinerary.length - walked());
        }

        /** How many entries the cursor has already handed out. */
        abstract int walked();

        /**
         * The step, in the order C writes it: a spent cursor stops without asking anything, a table that
         * has changed size is refused and the refusal kept, and only then is the position read.
         */
        @Override
        public final PyObject nextOrNull() {
            if (source == null) {
                return null;
            }
            if (used != count()) {
                used = -1;
                throw Slots.runtimeError(refusal());
            }
            if (!holds()) {
                source = null;
                return null;
            }
            return hand();
        }
    }

    /** The four cursors that start at the first entry and count up. */
    private static final class Forward extends Cursor {

        Forward(PyType type, PyObject source, PyObject[] itinerary) {
            super(type, source, itinerary);
        }

        @Override
        boolean holds() {
            return at < itinerary.length;
        }

        @Override
        PyObject hand() {
            return itinerary[at++];
        }

        @Override
        PyObject[] left() {
            return Arrays.copyOfRange(itinerary, at, itinerary.length);
        }

        @Override
        int walked() {
            return at;
        }
    }

    /**
     * The three cursors that start at the last entry and count down.
     *
     * <p>They read the same array as their forward neighbours, from the far end of it: CPython's {@code
     * dictiter_new} gives a reverse cursor {@code dk_nentries - 1} for a starting position and nothing else
     * differs, which is why an empty dict hands out a cursor that is already spent.
     */
    private static final class Backward extends Cursor {

        Backward(PyType type, PyObject source, PyObject[] itinerary) {
            super(type, source, itinerary);
            this.at = itinerary.length - 1;
        }

        @Override
        boolean holds() {
            return at >= 0;
        }

        @Override
        PyObject hand() {
            return itinerary[at--];
        }

        @Override
        PyObject[] left() {
            PyObject[] rest = Arrays.copyOfRange(itinerary, 0, at + 1);
            for (int i = 0; i < rest.length / 2; i++) {
                PyObject swap = rest[i];
                rest[i] = rest[rest.length - 1 - i];
                rest[rest.length - 1 - i] = swap;
            }
            return rest;
        }

        @Override
        int walked() {
            return itinerary.length - 1 - at;
        }
    }
}
