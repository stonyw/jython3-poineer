package org.python.core;

import java.math.BigInteger;

/**
 * The cursor {@code enumerate} makes, and the half of {@code enumobject.c} that is not {@link Reversed}.
 *
 * <p>{@code enumerate} is a type in {@code builtins} ({@code PyEnum_Type}, {@code :314}), so the pair it
 * hands out is of a name a program can reach, and {@code enumerate[int]} is answerable because {@code
 * enum_methods} ({@code :307}) files {@code __class_getitem__} over {@code Py_GenericAlias} — the one cursor
 * of the family that is generic over what it walks.
 *
 * <p>The constructor is the one in this family written twice. {@code enumerate_vectorcall} ({@code :104})
 * counts by hand and knows only {@code iterable} and {@code start}, so {@code enumerate()} complains
 * {@code missing required argument 'iterable'}; {@code enumerate.__new__} runs the clinic table over the same
 * two names, and that sentence has {@code (pos 1)} on the end of it. Two spellings of one bad call therefore
 * disagree, which is the opposite of what {@code reversed} does with its two. What both agree on is the order
 * after the count: {@code PyNumber_Index} on the start, then {@code PyObject_GetIter} on the iterable, so
 * {@code enumerate(1, start='x')} complains about the string and never asks the integer to be walked.
 *
 * <p>The count itself is kept as wide as Python integers are, because {@code enum_new_impl} ({@code :48})
 * keeps it two ways: {@code PyLong_AsSsize_t} over the {@code __index__} answer, and when that overflows a
 * {@code PyErr_Clear()} and an {@code en_longindex} that adds one at a time. The machine-word path is
 * invisible from Python — {@code enum_next} ({@code :239}) leaves it for the wide one the moment the count
 * reaches {@code PY_SSIZE_T_MAX}, which is why {@code enumerate([1, 2], 2 ** 63 - 1)} counts on into
 * sixty-four-bit overflow rather than wrapping — so only the wide one is reproduced here.
 */
final class Enumerate {

    /** {@code reduce_doc}, the sentence the pickling row of every cursor in the family carries. */
    private static final String REDUCE_DOC = "Return state information for pickling.";

    /**
     * {@code enum_methods} ({@code :307}): the table's two rows, of which only the pickle is filed here.
     *
     * <p>The other is {@code __class_getitem__}, which binds the class rather than a value and is filed by
     * {@code TypeDict} from the {@code METH_O|METH_CLASS} flag beside the table. It is beside the two slots
     * the third name {@code enumerate} has that {@code object} does not file — the pickle, the constructor
     * and the sentence are rows {@code object} files too — which is what makes {@code dir()} twenty-seven.
     */
    private static final Members BOOK =
            Members.of(PyType.ENUMERATE).addNoArgument("__reduce__", REDUCE_DOC, self -> reduce(self));

    private Enumerate() {
    }

    /** {@code enum_new_impl}: the count already read, and the source not yet walked. */
    static PyObject over(PyObject source, BigInteger at) {
        return new Cursor(Slots.iterator(source), at);
    }

    /** The two parameters of {@code enumerate}, in the order both of its argument paths name them. */
    private static final String[] NAMES = {"iterable", "start"};

    /**
     * {@code enumerate_vectorcall}: the one constructor in {@code builtins} written by hand beside a clinic
     * table, and the reason two spellings of one bad call disagree.
     *
     * <p>It counts {@code iterable} and {@code start} itself, in three cases and with the keyword names
     * checked one position at a time — so {@code enumerate(1, iterable=2)} complains that {@code iterable}
     * is not the name this case allows, which is {@code start}, while the clinic path accepts the same call
     * and puts its own {@code (pos 1)} on the end of the sentence about a name nobody has.
     */
    static PyObject calling(PyObject[] args, String[] kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = Arguments.positional(args, kwNames);
        int given = positional + keywords;
        if (given == 2) {
            if (keywords == 1) {
                named(kwNames, 0, "start");
            } else if (keywords == 2) {
                if ("start".equals(kwNames[0])) {
                    named(kwNames, 1, "iterable");
                    return counted(args[1], args[0]);
                }
                named(kwNames, 0, "iterable");
                named(kwNames, 1, "start");
            }
            return counted(args[0], args[1]);
        }
        if (given == 1) {
            if (keywords == 1) {
                named(kwNames, 0, "iterable");
            }
            return counted(args[0], null);
        }
        if (positional == 0) {
            throw Slots.typeError("enumerate() missing required argument 'iterable'");
        }
        throw Slots.typeError("enumerate() takes at most 2 arguments (%d given)", given);
    }

    /**
     * {@code enum_new}: the clinic table over the same two names, reached through {@code __new__}.
     *
     * <p>{@code _PyArg_UnpackKeywords} with one required and one optional parameter, which is what gives a
     * call with no iterable the sentence with {@code (pos 1)} on the end of it — and gives it before the
     * names the call did use are looked at, so {@code enumerate.__new__(enumerate, x=1)} is about the
     * argument that never arrived rather than the one that has no name.
     */
    static PyObject viaNew(PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords("enumerate", args, kwNames, NAMES, 1, 2);
        return counted(given[0], given[1]);
    }

    /** {@code check_keyword}: the name that position of this hand-written path allows. */
    private static void named(String[] kwNames, int at, String name) {
        if (!name.equals(kwNames[at])) {
            throw Slots.typeError(
                    "'%s' is an invalid keyword argument for enumerate()", kwNames[at]);
        }
    }

    /**
     * {@code enum_new_impl}: the count first and the walk after it.
     *
     * <p>{@code PyNumber_Index} is asked of the start before {@code PyObject_GetIter} is asked of the
     * iterable, and the absence of a start is the parameter's own default rather than a value to be read, so
     * {@code enumerate(1, start='x')} complains about the string and never asks the integer to be walked.
     */
    private static PyObject counted(PyObject source, PyObject start) {
        return over(source, start == null ? BigInteger.ZERO : Numbers.asIndex(start));
    }

    /** {@code enum_reduce}: {@code "O(On)"}, the type as its own callable and the count it would resume at. */
    private static PyObject reduce(PyObject self) {
        Cursor cursor = (Cursor) self;
        return PyTuple.of(PyType.ENUMERATE, PyTuple.of(cursor.source, PyInt.of(cursor.at)));
    }

    /** CPython's {@code en_sit} and {@code en_index}, the cursor and the count the next pair carries. */
    private static final class Cursor extends PyIterator {

        private static final BigInteger ONE = BigInteger.ONE;

        /** Held to the end: a spent {@code enumerate} pickles the source it ran out of, not nothing. */
        private final PyIterator source;

        private BigInteger at;

        Cursor(PyIterator source, BigInteger at) {
            super(PyType.ENUMERATE);
            this.source = source;
            this.at = at;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<enumerate object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }

        /**
         * {@code enum_next}: the item first, and the marker of a source that has run out before the count.
         *
         * <p>Nothing of the pair is made when there is no item, so a count a call could not answer is never
         * asked for the step that ends the walk.
         */
        @Override
        public PyObject nextOrNull() {
            PyObject value = source.nextOrNull();
            if (value == null) {
                return null;
            }
            PyObject index = PyInt.of(at);
            at = at.add(ONE);
            return PyTuple.of(index, value);
        }
    }
}
