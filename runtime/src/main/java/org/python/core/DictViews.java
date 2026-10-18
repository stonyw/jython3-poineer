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
 */
final class DictViews {

    private DictViews() {
    }

    private static final PyType KEYS = PyType.declare("dict_keys", PyType.TYPE);
    private static final PyType VALUES = PyType.declare("dict_values", PyType.TYPE);
    private static final PyType ITEMS = PyType.declare("dict_items", PyType.TYPE);

    static PyObject keys(PyDict mapping) {
        return new Keys(mapping);
    }

    static PyObject values(PyDict mapping) {
        return new Values(mapping);
    }

    static PyObject items(PyDict mapping) {
        return new Items(mapping);
    }

    /** What the three share: the dict they read, its length, and how it is shown. */
    private abstract static class View extends PyObject {

        final PyDict mapping;
        private final Members methods;

        View(PyDict mapping, PyType type, Members methods) {
            super(type);
            this.mapping = mapping;
            this.methods = methods;
        }

        /** The values the view shows, in the dict's own order. */
        abstract PyObject[] elements();

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

        @Override
        public PyObject getAttr(String name) {
            if ("mapping".equals(name)) {
                // A mappingproxy has no M1 equivalent, and handing back the dict itself would be a
                // promise the view could not keep: the proxy refuses what this dict allows.
                throw Slots.notImplemented("the 'mapping' attribute of a dict view");
            }
            return methods.bind(this, name);
        }
    }

    /** {@code dict_keys} and {@code dict_items}, the two views CPython treats as sets. */
    private abstract static class SetLike extends View {

        SetLike(PyDict mapping, PyType type, Members methods) {
            super(mapping, type, methods);
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
         */
        @Override
        public PyObject richCompare(CompareOp cmp, PyObject other) {
            if (!(other instanceof PySet || other instanceof SetLike)) {
                return super.richCompare(cmp, other);
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

        /** {@code view.isdisjoint(other)}: the shorter walk when the other side has a length. */
        private PyObject disjoint(PyObject other) {
            if (other == this) {
                return PyBool.of(mapping.size() == 0);
            }
            if (other instanceof PySet || other instanceof SetLike) {
                return PyBool.of(sizeOf(other) > mapping.size()
                        ? !containedIn(other)
                        : !covers(other));
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

        /** The one method the two set-like views share. */
        private static Members methods(String typeName) {
            return Members.of(typeName)
                    .addOne("isdisjoint", (self, other) -> ((SetLike) self).disjoint(other));
        }
    }

    /** {@code d.keys()}: membership is the dict's own lookup, message and all. */
    private static final class Keys extends SetLike {

        private static final Members METHODS = SetLike.methods("dict_keys");

        Keys(PyDict mapping) {
            super(mapping, KEYS, METHODS);
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
    }

    /** {@code d.items()}: a member is a pair, and only a two-element tuple can be one. */
    private static final class Items extends SetLike {

        private static final Members METHODS = SetLike.methods("dict_items");

        Items(PyDict mapping) {
            super(mapping, ITEMS, METHODS);
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
    }

    /** {@code d.values()}: a window with a length, an iteration and nothing else. */
    private static final class Values extends View {

        private static final Members METHODS = Members.of("dict_values");

        Values(PyDict mapping) {
            super(mapping, VALUES, METHODS);
        }

        @Override
        PyObject[] elements() {
            return mapping.values();
        }

        @Override
        public PyObject iterator() {
            return mapping.valueIterator();
        }

        /**
         * A values view has no {@code __eq__}, so CPython leaves it {@code object}'s identity hash —
         * which is what makes it the one dict view that can be a dict key. An address is not
         * reproducible here either, so the Java identity hash stands in for it.
         */
        @Override
        public long hash() {
            return PyHash.avoidMinusOne(System.identityHashCode(this));
        }
    }
}
