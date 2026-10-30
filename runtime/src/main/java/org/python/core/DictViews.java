package org.python.core;

/**
 * The three windows a dict opens onto itself: {@code dict_keys}, {@code dict_values} and
 * {@code dict_items}.
 *
 * A view holds the dict rather than a copy of it, so its length, its iteration and its {@code repr}
 * follow every later {@code d[k] = v} — and its iteration carries the dict's own complaint when the
 * dict changes size underneath it.
 *
 * Two of the three are set-like, and the difference is CPython's, not a choice: {@code dict_keys} and
 * {@code dict_items} get the four set operators, a subset comparison and {@code isdisjoint}, while
 * {@code dict_values} has no number slots and no {@code __eq__} at all, so it compares by identity,
 * is hashable, and answers {@code v & x} with a {@code TypeError}. Every result of a view's set
 * operation is a plain {@code set}, because CPython files the view's values into one before it
 * starts, and the operator then complains in the set's words rather than the view's.
 *
 * <p>The three types keep their rows in one place here as much as CPython does: {@code dictview_repr},
 * {@code dictview_len} and one {@code tp_iter} are shared by all three, while {@code dictviews_as_number}
 * and {@code dictview_richcompare} are written into only two of the three structs, and {@code
 * dictvalues_methods} is {@code dictkeys_methods} without {@code isdisjoint}.
 */
final class DictViews {

    private DictViews() {
    }

    // Under `object`, as CPython's three view types are: a base of `type` would put `type.__dict__` and
    // `type.__dir__` in a value's MRO, and those getters cast their receiver to a `PyType`. None of the
    // three can be called — CPython gives each an empty `tp_new`, so a view only ever comes from a dict.
    private static final PyType KEYS = PyType.DICT_KEYS;
    private static final PyType VALUES = PyType.DICT_VALUES;
    private static final PyType ITEMS = PyType.DICT_ITEMS;

    /** The three {@code PyDoc_STRVAR}s beside the method lists, each of which is the row's whole sentence. */
    private static final String ISDISJOINT_DOC =
            "Return True if the view and the given iterable have a null intersection.";

    private static final String REVERSED_KEYS_DOC =
            "Return a reverse iterator over the dict keys.";
    private static final String REVERSED_ITEMS_DOC =
            "Return a reverse iterator over the dict items.";
    private static final String REVERSED_VALUES_DOC =
            "Return a reverse iterator over the dict values.";

    static PyObject keys(PyDict mapping) {
        return new Keys(mapping);
    }

    static PyObject values(PyDict mapping) {
        return new Values(mapping);
    }

    static PyObject items(PyDict mapping) {
        return new Items(mapping);
    }

    /**
     * {@code view.mapping}, reached the way the type dictionary reaches it: through a descriptor written
     * for the type rather than through a value that knows its own name is filed.
     *
     * <p>The receiver is already the right kind of view — {@code PyGetSetDescriptor} checks it against the
     * class that declares the row before calling this — and a fresh window is made every time, because
     * {@code dictview_mapping} hands out a new {@code PyDictProxyObject} rather than one the view holds.
     */
    static PyObject mappingOf(PyObject view) {
        return ((View) view).window();
    }

    /** What the three share: the dict they read, its length, and how it is shown. */
    private abstract static class View extends PyObject {

        final PyDict mapping;

        View(PyDict mapping, PyType type) {
            super(type);
            this.mapping = mapping;
        }

        /** The values the view shows, in the dict's own order. */
        abstract PyObject[] elements();

        /** The same values from the last one written back to the first, which is what {@code reversed()} asks. */
        abstract PyIterator backwards();

        /**
         * {@code view.isdisjoint} and {@code view.__reversed__}, bound the way every other built-in value
         * binds its two method rows: through the table rather than through the dictionary, because a read
         * that reached the {@code method_descriptor} there would ask this same read for the method again.
         */
        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public PyObject length() {
            return PyInt.of(mapping.size());
        }

        /**
         * CPython's {@code %s(%R)} over the same values as a list, which is why an empty view reads as
         * {@code dict_keys([])} and a pair shows as a tuple inside it.
         */
        @Override
        public String repr() {
            return getType().name() + "(" + PyList.of(elements()).repr() + ")";
        }

        /** The window over the dict this view reads, made fresh. */
        PyObject window() {
            return PyMappingProxy.of(mapping);
        }
    }

    /** {@code dict_keys} and {@code dict_items}, the two views CPython treats as sets. */
    private abstract static class SetLike extends View {

        SetLike(PyDict mapping, PyType type) {
            super(mapping, type);
        }

        /** Whether one value is a member, in the view's own words. */
        abstract boolean holds(PyObject element);

        @Override
        public PyObject contains(PyObject element) {
            return PyBool.of(holds(element));
        }

        /**
         * CPython's {@code dictview_richcompare}, which is offered only to a set or to another
         * set-like view and never orders anything: the length decides first, and then whether every
         * value on one side is on the other. {@code <} is a proper subset.
         *
         * <p>The guard is the function's first line and it declines with {@code NotImplemented} rather than
         * answering, so {@code keys == [1]} is the identity comparison that {@code PyObject_RichCompare} falls
         * back to, not the {@code False} an equality written over all operands would give.
         */
        @Override
        public PyObject richCompare(CompareOp cmp, PyObject other) {
            if (!(other instanceof PySet || other instanceof SetLike)) {
                return PyNotImplemented.INSTANCE;
            }
            int length = mapping.size();
            int otherLength = sizeOf(other);
            return switch (cmp) {
                case EQ -> PyBool.of(length == otherLength && containedIn(other));
                case NE -> PyBool.of(length != otherLength || !containedIn(other));
                case LT -> PyBool.of(length < otherLength && containedIn(other));
                case LE -> PyBool.of(length <= otherLength && containedIn(other));
                case GT -> PyBool.of(length > otherLength && covers(other));
                case GE -> PyBool.of(length >= otherLength && covers(other));
            };
        }

        /**
         * The four set operations, each of which starts by filing this view's values into a plain set
         * and then applies the other side to it, exactly as CPython's {@code dictviews_} functions do.
         */
        @Override
        public PyObject binaryOp(BinaryOp op, PyObject other) {
            return switch (op) {
                case AND -> intersected(other);
                case OR -> filed().update(other);
                case SUB -> filed().differenceUpdate(other);
                case XOR -> symmetricallyWith(other);
                default -> PyNotImplemented.INSTANCE;
            };
        }

        /**
         * The same slots, which CPython runs with the arguments in their original order — so a view on
         * the right of a non-set behaves as though the value on the left had been filed into a set
         * first. {@code [2] - d.keys()} is therefore {@code {2}}, and {@code 2 - d.keys()} is a
         * complaint about an int not being iterable rather than about an unusable operand. Intersection
         * is the one CPython swaps internally, so it answers as itself.
         */
        @Override
        public PyObject reflectedOp(BinaryOp op, PyObject other) {
            return switch (op) {
                case AND -> intersected(other);
                case OR -> PySet.collected(other).update(this);
                case SUB -> PySet.collected(other).differenceUpdate(this);
                case XOR -> PySet.collected(other).symmetricDifferenceUpdate(this);
                default -> PyNotImplemented.INSTANCE;
            };
        }

        /** This view's values, filed into a plain set. */
        private PySet filed() {
            PySet out = PySet.empty();
            for (PyObject element : elements()) {
                out.add(element);
            }
            return out;
        }

        /** {@code view & other}: the other side is walked, and what this view holds is kept. */
        private PySet intersected(PyObject other) {
            PySet out = PySet.empty();
            PyIterator iterator = Slots.iterator(other);
            PyObject element;
            while ((element = iterator.nextOrNull()) != null) {
                if (holds(element)) {
                    out.add(element);
                }
            }
            return out;
        }

        /** {@code view ^ other}, which CPython computes pair by pair only between two item views. */
        private PyObject symmetricallyWith(PyObject other) {
            if (this instanceof Items && other instanceof Items items) {
                return pairsMissingFrom(items);
            }
            return filed().symmetricDifferenceUpdate(other);
        }

        /**
         * CPython's {@code dictitems_xor}: a pair cancels only when both dicts have that key with an
         * equal value, so a key the two disagree about contributes both of its pairs.
         */
        private PyObject pairsMissingFrom(Items other) {
            PySet out = PySet.empty();
            for (PyObject pair : elements()) {
                if (!cancelled(pair, other.mapping)) {
                    out.add(pair);
                }
            }
            for (PyObject pair : other.elements()) {
                if (!cancelled(pair, mapping)) {
                    out.add(pair);
                }
            }
            return out;
        }

        /** Whether a pair is the same entry of another dict, which is what makes it cancel. */
        private static boolean cancelled(PyObject pair, PyDict dict) {
            PyTuple entry = (PyTuple) pair;
            PyObject found = dict.get(entry.at(0));
            return found != null && Slots.compareTrue(CompareOp.EQ, found, entry.at(1));
        }

        /** CPython's {@code all_contained_in(self, other)}, asked through {@code other}'s own test. */
        private boolean containedIn(PyObject other) {
            for (PyObject element : elements()) {
                if (!Slots.contains(other, element)) {
                    return false;
                }
            }
            return true;
        }

        /** The same test the other way round, walking {@code other} and asking this view. */
        private boolean covers(PyObject other) {
            PyIterator iterator = Slots.iterator(other);
            PyObject element;
            while ((element = iterator.nextOrNull()) != null) {
                if (!holds(element)) {
                    return false;
                }
            }
            return true;
        }

        /** Whether this view has any value in common with the other side, asked through {@code other}'s test. */
        private boolean overlapsWith(PyObject other) {
            for (PyObject element : elements()) {
                if (Slots.contains(other, element)) {
                    return true;
                }
            }
            return false;
        }

        /** {@code view.isdisjoint(other)}: the shorter walk when the other side has a length. */
        private PyObject disjoint(PyObject other) {
            if (other == this) {
                return PyBool.of(mapping.size() == 0);
            }
            if ((other instanceof PySet || other instanceof SetLike)
                    && sizeOf(other) > mapping.size()) {
                // {@code dictviews_isdisjoint} swaps the two sides when the other is a set with more
                // values than this view, so that the shorter of the two is the one walked. The test is the
                // same either way — one common value is enough — and only the direction of the walk moves.
                return PyBool.of(!overlapsWith(other));
            }
            // Anything else is iterated and tested against the view, which is where the dict's own
            // key message comes from for `isdisjoint([[1]])`.
            PyIterator iterator = Slots.iterator(other);
            PyObject element;
            while ((element = iterator.nextOrNull()) != null) {
                if (holds(element)) {
                    return PyBool.FALSE;
                }
            }
            return PyBool.TRUE;
        }

        /** The length CPython reads with {@code PyObject_Size}, which both kinds answer. */
        private static int sizeOf(PyObject other) {
            return other instanceof PySet set ? set.size() : ((SetLike) other).mapping.size();
        }
    }

    /** The method list a set-like view files: {@code dictkeys_methods} and its {@code dictitems} twin. */
    private static Members setMethods(PyType type, String reversedDoc) {
        return Members.of(type)
                .addOne(
                        "isdisjoint",
                        ISDISJOINT_DOC,
                        (self, other) -> ((SetLike) self).disjoint(other))
                .addNoArgument("__reversed__", reversedDoc, self -> ((View) self).backwards());
    }

    /** The one method {@code dictvalues_methods} files — the list without {@code isdisjoint}. */
    private static Members valuesMethods() {
        return Members.of(VALUES)
                .addNoArgument("__reversed__", REVERSED_VALUES_DOC, self -> ((View) self).backwards());
    }

    /**
     * The three method lists, filed as this class is initialized.
     *
     * <p>Nothing here reads them back: {@link Members#of} installs each one where a type dictionary finds
     * it, and {@code TypeDict} asks {@link Members} for the table of the type it is filling. Making the
     * tables a field of this class rather than a step of that fill is what lets {@code dict_keys.isdisjoint}
     * answer before the interpreter has made a single view.
     */
    private static final Members KEY_METHODS = setMethods(KEYS, REVERSED_KEYS_DOC);

    private static final Members ITEM_METHODS = setMethods(ITEMS, REVERSED_ITEMS_DOC);
    private static final Members VALUE_METHODS = valuesMethods();

    /** {@code d.keys()}: membership is the dict's own lookup, message and all. */
    private static final class Keys extends SetLike {

        Keys(PyDict mapping) {
            super(mapping, KEYS);
        }

        @Override
        boolean holds(PyObject element) {
            return mapping.hasKey(element);
        }

        @Override
        PyObject[] elements() {
            return mapping.keys();
        }

        @Override
        public PyObject iterator() {
            return mapping.keyIterator();
        }

        @Override
        PyIterator backwards() {
            return mapping.reverseKeyIterator();
        }
    }

    /** {@code d.items()}: a member is a pair, and only a two-element tuple can be one. */
    private static final class Items extends SetLike {

        Items(PyDict mapping) {
            super(mapping, ITEMS);
        }

        /**
         * A value that is not a two-element tuple is not a pair, which is a false rather than an
         * error; the key of one that is gets looked up, and an unusable key is refused in the dict's
         * own words.
         */
        @Override
        boolean holds(PyObject element) {
            if (!(element instanceof PyTuple pair) || pair.size() != 2) {
                return false;
            }
            PyObject found = mapping.get(pair.at(0));
            return found != null && Slots.compareTrue(CompareOp.EQ, found, pair.at(1));
        }

        @Override
        PyObject[] elements() {
            return mapping.pairs();
        }

        @Override
        public PyObject iterator() {
            return mapping.itemIterator();
        }

        @Override
        PyIterator backwards() {
            return mapping.reverseItemIterator();
        }
    }

    /** {@code d.values()}: a window with a length, an iteration and nothing else. */
    private static final class Values extends View {

        Values(PyDict mapping) {
            super(mapping, VALUES);
        }

        @Override
        PyObject[] elements() {
            return mapping.values();
        }

        @Override
        public PyObject iterator() {
            return mapping.valueIterator();
        }

        @Override
        PyIterator backwards() {
            return mapping.reverseValueIterator();
        }

        /**
         * A values view files no comparison row at all — CPython's struct writes {@code 0} for {@code
         * tp_richcompare} beside it ({@code Objects/dictobject.c:6759}), which takes {@code
         * tp_hash} with it in the pair the inheritance rule copies — so it keeps {@code object}'s identity
         * hash and is the one dict view that can be a dict key. An address is not reproducible here either,
         * so the Java identity hash stands in for it.
         */
        @Override
        public long hash() {
            return PyHash.avoidMinusOne(System.identityHashCode(this));
        }
    }
}
