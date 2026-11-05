package org.python.core;

/**
 * The three cursors whose builtin is the type the call makes, and whose type lives in {@code bltinmodule.c}.
 *
 * <p>{@code zip}, {@code map} and {@code filter} are {@code PyZip_Type} ({@code :3314}), {@code PyMap_Type}
 * ({@code :1629}) and {@code PyFilter_Type} ({@code :642}), and {@code _PyBuiltin_Init} ({@code :3434})
 * files each of them in {@code builtins} with {@code SETBUILTIN} and the type, not with a function — which
 * is why the value one of these calls hands out has a name a program can reach, and why each of the three
 * books is the five rows all four cursors of this kind file — {@code __new__}, the two slot copies, the
 * pickle and the sentence — plus whatever the cursor alone can carry.
 *
 * <p>What the three carry apart from each other is {@code strict}, and it is the reason two of them file a
 * {@code __setstate__} the arguments cannot express: {@code zip} and {@code map} keep one flag, read out of
 * the keyword arguments by the {@code p} format — which asks the truth of the value and so hears a {@code
 * __bool__} that complains before any iterable is asked for an iterator — and written back by the restart
 * row. {@code filter} has neither, so its book is five names and its constructor is the only one of the four
 * that refuses a keyword in the legacy family's words rather than in a table's.
 *
 * <p>The walk is where the two that hold several sources differ from a cursor over one: the step that finds
 * nothing stops there and leaves the sources after it alone, so {@code zip([], rest)} ends without ever
 * asking {@code rest} — unless {@code strict} is on, in which case the same step goes on to read the rest of
 * them, and the answer it gets decides which of the two complaints it writes. Either way a refusal that is
 * not the end of an iteration is not the end of the walk: a {@code __next__} that raises a {@code KeyError}
 * travels out of {@code list(zip(x, y))} unchanged, in the strict and the plain cursor alike.
 */
final class BuiltinCursors {

    /** {@code reduce_doc}, the sentence {@code bltinmodule.c} writes once for all three. */
    private static final String REDUCE_DOC = "Return state information for pickling.";

    /** {@code setstate_doc}, the sentence of the two rows {@code filter} has no need of. */
    private static final String SETSTATE_DOC = "Set state information for unpickling.";

    /** {@code zip_methods} ({@code :3293}): the pickle and the flag, beside the two slot copies. */
    private static final Members ZIP_BOOK =
            Members.of(PyType.ZIP)
                    .addNoArgument("__reduce__", REDUCE_DOC, self -> ((Zipped) self).reduce())
                    .addOne(
                            "__setstate__",
                            SETSTATE_DOC,
                            (self, state) -> {
                                ((Zipped) self).strictTo(state);
                                return PyNone.INSTANCE;
                            });

    /** {@code map_methods} ({@code :1612}): the same two rows, over a cursor that also holds a function. */
    private static final Members MAP_BOOK =
            Members.of(PyType.MAP)
                    .addNoArgument("__reduce__", REDUCE_DOC, self -> ((Mapped) self).reduce())
                    .addOne(
                            "__setstate__",
                            SETSTATE_DOC,
                            (self, state) -> {
                                ((Mapped) self).strictTo(state);
                                return PyNone.INSTANCE;
                            });

    /** {@code filter_methods} ({@code :629}): one row, because nothing about a filter is a state. */
    private static final Members FILTER_BOOK =
            Members.of(PyType.FILTER)
                    .addNoArgument("__reduce__", REDUCE_DOC, self -> ((Filtered) self).reduce());

    private BuiltinCursors() {
    }

    /**
     * {@code zip_new}: the flag, then as many sources as the call gave.
     *
     * <p>There is no {@code zip_vectorcall}, so {@code zip(...)} and {@code zip.__new__(zip, ...)} reach this
     * one path, and the {@code |$p} parse that names itself runs over an empty tuple: a positional is not
     * among the arguments it counts, which is why {@code zip(1, x=2)} complains about {@code x} and never
     * asks the integer to be walked.
     */
    static PyObject zipping(PyObject[] args, String[] kwNames) {
        boolean strict = strictFlag("zip", args, kwNames);
        return new Zipped(walked(args, kwNames), strict);
    }

    /**
     * {@code map_new}, which {@code map_vectorcall} hands its keyword calls to.
     *
     * <p>The vectorcall is the only one of the two that counts arguments itself, and it counts a call with no
     * keywords: a name in the argument list sends the whole call back through {@code _PyObject_MakeTpCall},
     * where the flag is parsed before the count and only the positionals are still arguments at all. That is
     * what makes {@code map(f, x=1)} about {@code x} while {@code map(strict=True)} is about the two
     * arguments that never arrived — and why one Java path serves both spellings.
     */
    static PyObject mapping(PyObject[] args, String[] kwNames) {
        boolean strict = strictFlag("map", args, kwNames);
        int sources = Arguments.positional(args, kwNames);
        if (sources < 2) {
            throw Slots.typeError("map() must have at least two arguments.");
        }
        PyObject function = args[0];
        PyIterator[] walked = new PyIterator[sources - 1];
        for (int i = 1; i < sources; i++) {
            walked[i - 1] = Slots.iterator(args[i]);
        }
        return new Mapped(function, walked, strict);
    }

    /**
     * {@code filter_vectorcall} and {@code filter_new}, which refuse in the same words.
     *
     * <p>The keywords first, because {@code function} and {@code iterable} are positional-only, then the
     * count in the family that says {@code filter expected 2 arguments, got 1}, then {@code PyObject_GetIter}
     * on the second of the two — so nothing a filter holds is ever called, and {@code filter(1, 1)} is about
     * the {@code int} nobody asked to be walked rather than about the {@code int} nobody called.
     */
    static PyObject filtering(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("filter()", kwNames);
        Arguments.exactly("filter", args, kwNames, 2);
        return new Filtered(args[0], Slots.iterator(args[1]));
    }

    /**
     * {@code |$p:zip} and {@code |$p:map} over an empty tuple: the one keyword-only flag of the family.
     *
     * <p>The {@code p} format is a truth test rather than a type check, so any value at all answers it — and
     * the answer is the flag, which is why {@code strict=[]} turns a strict cursor back into a plain one
     * instead of refusing the list.
     */
    private static boolean strictFlag(String name, PyObject[] args, String[] kwNames) {
        if (kwNames == null) {
            return false;
        }
        PyObject[] given = Arguments.keywordOnly(name, args, kwNames, new String[] {"strict"});
        return given[0] != null && Slots.truth(given[0]);
    }

    /** {@code PyObject_GetIter} over every positional of the call, in the order the call gave them. */
    private static PyIterator[] walked(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        PyIterator[] sources = new PyIterator[positional];
        for (int i = 0; i < positional; i++) {
            sources[i] = Slots.iterator(args[i]);
        }
        return sources;
    }

    /** {@code argument%s%zd}: the one source a call may blame, or the run of them from the first. */
    private static String preceding(int at) {
        return at == 1 ? "argument 1" : "arguments 1-" + at;
    }

    /** CPython's {@code lz->ittuple} and {@code lz->strict}, the second of them a row can rewrite. */
    private static final class Zipped extends PyIterator {

        private final PyIterator[] sources;

        private boolean strict;

        Zipped(PyIterator[] sources, boolean strict) {
            super(PyType.ZIP);
            this.sources = sources;
            this.strict = strict;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<zip object at 0x" + Integer.toHexString(System.identityHashCode(this)) + ">";
        }

        /**
         * {@code zip_next}: one item per source, and the round ends at the first source that has none.
         *
         * <p>A cursor over nothing ends at once rather than handing out the empty tuple for ever: the count of
         * sources is read before any of them is walked, and a call with none in it is the one {@code zip} that
         * has no answer to give.
         *
         * <p>The sources after the one that ran out are not asked in a plain cursor, which is what lets a
         * shared iterator keep an item a {@code zip} never took. In a strict one the same step goes on to
         * read them, and ends the walk without a complaint only when every one of them is empty too.
         */
        @Override
        public PyObject nextOrNull() {
            if (sources.length == 0) {
                return null;
            }
            PyObject[] pulled = new PyObject[sources.length];
            for (int i = 0; i < sources.length; i++) {
                PyObject item = sources[i].nextOrNull();
                if (item == null) {
                    return strict ? imbalance(i) : null;
                }
                pulled[i] = item;
            }
            return PyTuple.of(pulled);
        }

        /** {@code zip_next}'s {@code check} block, after a source that ended rather than complained. */
        private PyObject imbalance(int at) {
            if (at > 0) {
                throw Slots.valueError(
                        "zip() argument %d is shorter than %s", at + 1, preceding(at));
            }
            for (int later = 1; later < sources.length; later++) {
                if (sources[later].nextOrNull() != null) {
                    throw Slots.valueError(
                            "zip() argument %d is longer than %s", later + 1, preceding(later));
                }
            }
            return null;
        }

        /**
         * {@code zip_reduce}: the tuple of iterators the cursor was built from, and the flag beside it.
         *
         * <p>The three-item form is what a strict cursor answers, and the third item is the state the two
         * arguments cannot carry — the iterators are handed back entire, so what being found again makes is a
         * cursor over the rest of each source, and the flag is the one thing that has to be written after it.
         */
        PyObject reduce() {
            return strict
                    ? PyTuple.of(getType(), PyTuple.of(sources), PyBool.TRUE)
                    : PyTuple.of(getType(), PyTuple.of(sources));
        }

        /** {@code zip_setstate}: the truth of the state, and nothing else about the cursor. */
        void strictTo(PyObject state) {
            strict = Slots.truth(state);
        }
    }

    /** CPython's {@code lz->iters}, {@code lz->func} and {@code lz->strict}. */
    private static final class Mapped extends PyIterator {

        private final PyObject function;
        private final PyIterator[] sources;

        private boolean strict;

        Mapped(PyObject function, PyIterator[] sources, boolean strict) {
            super(PyType.MAP);
            this.function = function;
            this.sources = sources;
            this.strict = strict;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<map object at 0x" + Integer.toHexString(System.identityHashCode(this)) + ">";
        }

        /**
         * {@code map_next}: the argument list, then the call — and no call without a full list.
         *
         * <p>A source that ends the walk ends it before the function is reached, so a one-source map over an
         * empty iterable never calls anything, and the imbalance the strict cursor complains of is written
         * with the same two sentences {@code zip} uses, about the arguments rather than about a tuple.
         */
        @Override
        public PyObject nextOrNull() {
            PyObject[] pulled = new PyObject[sources.length];
            for (int i = 0; i < sources.length; i++) {
                PyObject item = sources[i].nextOrNull();
                if (item == null) {
                    return strict ? imbalance(i) : null;
                }
                pulled[i] = item;
            }
            return Slots.call(function, pulled, null);
        }

        /** {@code map_next}'s {@code check} block, which is the same walk {@code zip_next} makes. */
        private PyObject imbalance(int at) {
            if (at > 0) {
                throw Slots.valueError(
                        "map() argument %d is shorter than %s", at + 1, preceding(at));
            }
            for (int later = 1; later < sources.length; later++) {
                if (sources[later].nextOrNull() != null) {
                    throw Slots.valueError(
                            "map() argument %d is longer than %s", later + 1, preceding(later));
                }
            }
            return null;
        }

        /**
         * {@code map_reduce}: the function in front of the iterators, in the one hand that is the argument
         * list — which is what makes a found {@code map} a call rather than a cursor copied from a cursor.
         */
        PyObject reduce() {
            PyObject[] payload = new PyObject[sources.length + 1];
            payload[0] = function;
            System.arraycopy(sources, 0, payload, 1, sources.length);
            return strict
                    ? PyTuple.of(getType(), PyTuple.of(payload), PyBool.TRUE)
                    : PyTuple.of(getType(), PyTuple.of(payload));
        }

        /** {@code map_setstate}: the same flag, on a cursor that holds a function beside it. */
        void strictTo(PyObject state) {
            strict = Slots.truth(state);
        }
    }

    /** CPython's {@code lz->func} and {@code lz->it}. */
    private static final class Filtered extends PyIterator {

        private final PyObject predicate;

        /** {@code checktrue}: {@code None} and the {@code bool} type both ask the value its own truth. */
        private final boolean readsTruth;

        private final PyIterator source;

        Filtered(PyObject predicate, PyIterator source) {
            super(PyType.FILTER);
            this.predicate = predicate;
            this.readsTruth = predicate == PyNone.INSTANCE || predicate == PyType.BOOL;
            this.source = source;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<filter object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }

        /**
         * {@code filter_next}: keep walking until something is true.
         *
         * <p>The two ways of asking are not one way written twice — {@code None} and {@code bool} skip the
         * call and test the value itself, while anything else is called and its answer tested, so a {@code
         * __bool__} that complains is heard either as the value's own refusal or as the one the predicate
         * made on the way back. A value that is merely false is dropped and the source asked again.
         */
        @Override
        public PyObject nextOrNull() {
            while (true) {
                PyObject value = source.nextOrNull();
                if (value == null) {
                    return null;
                }
                if (Slots.truth(readsTruth ? value : Slots.call(
                        predicate, new PyObject[] {value}, null))) {
                    return value;
                }
            }
        }

        /** {@code filter_reduce}: {@code "O(OO)"} over the predicate and the one iterator. */
        PyObject reduce() {
            return PyTuple.of(getType(), PyTuple.of(predicate, source));
        }
    }
}
