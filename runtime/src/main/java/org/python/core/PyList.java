package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Python's {@code list}: a growable array of values.
 *
 * Identity is the part the interpreter keeps. {@code a += b}, {@code a *= n}, {@code a.sort()} and
 * {@code a.reverse()} all change this object and hand the same one back, so a second name for the
 * list sees the mutation — which is why {@link #inplaceOp} returns {@code this} rather than a fresh
 * list, and why {@link #extend} copies a list argument before appending to itself.
 *
 * Indexing has three voices, and all three are visible. A subscript says {@code list index out of
 * range}; an assignment or a deletion says {@code list assignment index out of range}; and
 * {@code pop} and {@code insert} convert with {@code __index__} rather than as a subscript, so a
 * float is refused in yet other words and an index too wide for a machine word is an
 * {@code OverflowError}.
 */
public final class PyList extends PyObject {

    /**
     * The element storage, which counts the structural changes a sort must notice.
     *
     * <p>{@code list.sort} empties the list while keys and comparisons run, and CPython reports
     * {@code list modified during sort} when the storage was replaced or grown meanwhile. The JVM has no
     * {@code ob_item} pointer to compare, so the storage counts the changes itself;
     * {@link #clear()} of an already empty list is not one, which is what makes a key or comparison that
     * calls {@code clear()} harmless in the reference too.
     */
    static final class Items extends ArrayList<PyObject> {
        int mutations;

        Items() {}

        Items(Collection<PyObject> initial) {
            super(initial);
        }

        @Override
        public boolean add(PyObject item) {
            mutations++;
            return super.add(item);
        }

        @Override
        public void add(int index, PyObject item) {
            mutations++;
            super.add(index, item);
        }

        @Override
        public boolean addAll(Collection<? extends PyObject> values) {
            mutations++;
            return super.addAll(values);
        }

        @Override
        public boolean addAll(int index, Collection<? extends PyObject> values) {
            mutations++;
            return super.addAll(index, values);
        }

        @Override
        public PyObject remove(int index) {
            mutations++;
            return super.remove(index);
        }

        @Override
        public boolean remove(Object item) {
            mutations++;
            return super.remove(item);
        }

        @Override
        public void clear() {
            if (!isEmpty()) {
                mutations++;
            }
            super.clear();
        }
    }

    private final Items items;

    private PyList(Items items) {
        super(PyType.LIST);
        this.items = items;
    }

    public static PyList empty() {
        return new PyList(new Items());
    }

    public static PyList of(PyObject... items) {
        return new PyList(new Items(List.of(items)));
    }

    /**
     * The elements a value keeps where a {@link PyList} is expected: a plain list is its own storage, and
     * a class Python wrote under {@code list} keeps one beside the attributes it also has.
     *
     * <p>The inherited methods of {@code list} are written against a {@code PyList}, so a call of one on
     * a subclass — bound through {@link Members}, or against another value's {@code +=} — has to reach
     * the same items without treating the subclass instance as the storage itself.
     *
     * @return the list, or null when the value keeps no list at all
     */
    static PyList bodyOf(PyObject value) {
        if (value instanceof PyList list) {
            return list;
        }
        return value instanceof PyListInstance subclass ? subclass.entries() : null;
    }

    /** The element count for Java; {@link #length()} is the Python slot. */
    public int size() {
        return items.size();
    }

    public PyObject at(int index) {
        return items.get(index);
    }

    public void add(PyObject item) {
        items.add(item);
    }

    public List<PyObject> getItems() {
        return items;
    }

    // --- slots -------------------------------------------------------------------------

    @Override
    public PyObject length() {
        return PyInt.of(items.size());
    }

    /** {@code l[i]} and {@code l[a:b:c]}; a slice always copies, an index wraps negatives. */
    @Override
    public PyObject getItem(PyObject key) {
        if (Slices.isSlice(key)) {
            // The range is resolved first: an `__index__` may change this very list, and CPython
            // measures the list only afterwards — the copy below must be the post-mutation one.
            Slices.Range range = Slices.of((PySlice) key, items::size);
            return gathered(Sequences.gather(elements(), range));
        }
        return items.get(
                Slices.index(key, items::size, "list", "list index out of range"));
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        if (Slices.isSlice(key)) {
            assign((PySlice) key, value);
        } else {
            items.set(position(key), value);
        }
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delItem(PyObject key) {
        if (Slices.isSlice(key)) {
            assign((PySlice) key, null);
        } else {
            items.remove(position(key));
        }
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject contains(PyObject item) {
        // CPython's list_contains asks Py_SIZE and PyList_GET_ITEM again on every step, because
        // `__eq__` is arbitrary code that may grow or shrink this very list. RichCompareBool's
        // identity fast path comes first, so an object that is `==` to itself by identity never
        // reaches a user `__eq__` at all.
        for (int i = 0; i < items.size(); i++) {
            PyObject candidate = items.get(i);
            if (candidate == item || Slots.compareTrue(CompareOp.EQ, candidate, item)) {
                return PyBool.TRUE;
            }
        }
        return PyBool.FALSE;
    }

    @Override
    public PyObject iterator() {
        return SequenceIterators.list(this);
    }

    /** {@code a + b} joins two lists; {@code a * n} repeats one. */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            // CPython raises here rather than declining, so `[] + ()` names only the right operand.
            if (!(other instanceof PyList list)) {
                throw Slots.typeError(
                        "can only concatenate list (not \"%s\") to list", other.getType().name());
            }
            PyObject[] joined = concat(elements(), list.elements());
            return gathered(joined);
        }
        if (op == BinaryOp.MUL) {
            return gathered(Sequences.multiplied(elements(), other));
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        // Only `__rmul__`: a list has no `__radd__`, so `1 + [1]` reports the int's own refusal.
        return op == BinaryOp.MUL ? binaryOp(op, other) : PyNotImplemented.INSTANCE;
    }

    /** {@code a += b} extends in place, {@code a *= n} repeats in place, and both return {@code a}. */
    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            extend(other);
            return this;
        }
        if (op == BinaryOp.MUL) {
            // The count is checked before anything is thrown away, so `a *= 1.0` leaves `a` alone.
            PyObject[] repeated = Sequences.multiplied(elements(), other);
            replace(0, items.size(), repeated);
            return this;
        }
        return PyNotImplemented.INSTANCE;
    }

    /**
     * Element-wise order with length as the tie-break, as for a {@code tuple}, and declined rather than
     * answered for anything that is not a list — {@code [1].__eq__((1,))} is {@code NotImplemented}, and the
     * {@code ==} operator falls back on identity.
     *
     * <p>CPython's {@code list_richcompare} re-reads both lengths and both items on every step and
     * decides {@code ==} by the lengths <em>after</em> the loop: a comparison that empties both lists
     * ends with {@code i} past both lengths, which makes them equal. That is the fix for the segfault
     * in bpo-38588, and it is why this cannot go through {@link Sequences#compare} on snapshots.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (!(other instanceof PyList list)) {
            return PyNotImplemented.INSTANCE;
        }
        int i = 0;
        while (i < items.size() && i < list.items.size()) {
            PyObject left = items.get(i);
            PyObject right = list.items.get(i);
            // RichCompareBool's identity fast path, then the full protocol; user code may rewrite
            // either list, so the next turn measures both again.
            if (left == right || Slots.compareTrue(CompareOp.EQ, left, right)) {
                i++;
            } else {
                break;
            }
        }
        boolean withinBoth = i < items.size() && i < list.items.size();
        if (withinBoth) {
            // The pair that differed is still there, so the answer is that comparison — for == it
            // is simply false and for != simply true.
            return cmp == CompareOp.EQ
                    ? PyBool.FALSE
                    : cmp == CompareOp.NE
                            ? PyBool.TRUE
                            : Slots.compare(cmp, items.get(i), list.items.get(i));
        }
        return Sequences.ordered(cmp, Integer.compare(items.size(), list.items.size()));
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    /** Mutable, therefore unhashable; {@link #hashCode()} inherits the same refusal. */
    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyList list)) {
            return false;
        }
        // CPython's list_richcompare re-reads both sizes and both items on every step, because a
        // user `__eq__` may change either list. Comparing stale lengths would walk off the end.
        int i = 0;
        while (i < items.size() && i < list.items.size()) {
            PyObject left = items.get(i);
            PyObject right = list.items.get(i);
            if (!(left == right || Slots.compareTrue(CompareOp.EQ, left, right))) {
                return false;
            }
            i++;
        }
        return items.size() == list.items.size();
    }

    @Override
    public String repr() {
        if (enteringRepr(this)) {
            return "[...]";
        }
        try {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(items.get(i).repr());
            }
            return out.append(']').toString();
        } finally {
            leavingRepr(this);
        }
    }

    // --- slot helpers --------------------------------------------------------------------

    /** The elements as an array, which is what {@link Sequences} walks. */
    private PyObject[] elements() {
        return items.toArray(new PyObject[0]);
    }

    private static PyObject[] concat(PyObject[] head, PyObject[] tail) {
        PyObject[] joined = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    private static PyList gathered(PyObject[] elements) {
        return new PyList(new Items(Arrays.asList(elements)));
    }

    /**
     * {@code l.__init__(iterable)}: the receiver emptied, and only then extended from the argument.
     *
     * <p>That order is {@code list___init___impl}, and it is the whole difference between this and a copy:
     * {@code l.__init__(l)} is an empty list, because the iterable it extends from is the list that was just
     * cleared, and a refused {@code l.__init__(5)} still leaves an empty list behind. {@code l.extend(l)} is
     * the other case — the contents are still there to be read, so they are read twice.
     */
    void initialized(PyObject iterable) {
        items.clear();
        if (iterable != null) {
            extend(iterable);
        }
    }

    /** {@code l[a:b] = value} and {@code l[a:b:c] = value}, and the same two in deletion form. */
    private void assign(PySlice key, PyObject value) {
        // The replacement is materialised before the list is touched, so `l[::-1] = l` reverses;
        // the length, though, is read only after the slice's own `__index__` calls have run.
        PyObject[] replacement = value == null ? null : materialise(value);
        Slices.Range range = Slices.of(key, items::size);
        if (value == null) {
            if (range.step() == 1) {
                items.subList(range.start(), range.start() + range.count()).clear();
                return;
            }
            // Each removal shifts the indices above it, so the highest index has to go first. A
            // backward slice already names its indices in that order; a forward one does not.
            if (range.step() < 0) {
                for (int i = 0; i < range.count(); i++) {
                    items.remove(range.at(i));
                }
            } else {
                for (int i = range.count() - 1; i >= 0; i--) {
                    items.remove(range.at(i));
                }
            }
            return;
        }
        if (range.step() == 1) {
            replace(range.start(), range.start() + range.count(), replacement);
            return;
        }
        // An extended slice names its slots up front: the replacement has to fill exactly them.
        if (replacement.length != range.count()) {
            throw Slots.valueError(
                    "attempt to assign sequence of size %d to extended slice of size %d",
                    replacement.length, range.count());
        }
        for (int i = 0; i < range.count(); i++) {
            items.set(range.at(i), replacement[i]);
        }
    }

    /** Overwrite {@code items[from, to)} with {@code replacement}. */
    private void replace(int from, int to, PyObject[] replacement) {
        items.subList(from, to).clear();
        items.addAll(from, Arrays.asList(replacement));
    }

    /** The values of an iterable, or CPython's slice-assignment refusal. */
    private static PyObject[] materialise(PyObject value) {
        if (value instanceof PyList list) {
            return list.elements();
        }
        // `__iter__` runs arbitrary code, so it is asked once here and the answer is drained; asking
        // `isIterable` first would call it a second time and duplicate its side effects.
        PyIterator iterator = Slots.iteratorOrNull(value);
        if (iterator == null) {
            throw Slots.typeError("must assign iterable to extended slice");
        }
        return drain(iterator);
    }

    private static PyObject[] drain(PyIterator iterator) {
        List<PyObject> out = new ArrayList<>();
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            out.add(item);
        }
        return out.toArray(new PyObject[0]);
    }

    /**
     * An index for {@code l[i] = v} and {@code del l[i]}: {@link Slices}' arithmetic, in the
     * assignment voice CPython uses for both.
     *
     * <p>{@code list_ass_subscript} converts with {@code PyNumber_AsSsize_t(item, PyExc_IndexError)}, so
     * an index too wide for a machine word is refused as an {@code IndexError} about fitting, and only one
     * that fits gets the chance to be out of range: {@code data[2 ** 70] = 1} says {@code cannot fit 'int'
     * into an index-sized integer} where {@code data[2 ** 40] = 1} says {@code list assignment index out
     * of range}. {@link PyTuple} and {@link PyStr} read a subscript the same way, with their own
     * out-of-range sentence.
     */
    private int position(PyObject key) {
        return Numbers.wrapIndex(
                key, Numbers.index(key, "list"), items.size(), "list assignment index out of range");
    }

    /**
     * An index for {@code pop} and {@code insert}, which CPython converts as an {@code __index__}
     * rather than as a subscript: a float is refused in other words, and an integer too wide for a
     * machine word is an {@code OverflowError} instead of an {@code IndexError}.
     *
     * <p>{@code list_pop_impl(self, Py_ssize_t index)} is a clinic function, and clinic's {@code
     * Py_ssize_t} converter is {@code _PyNumber_Index} followed by {@code PyLong_AsSsize_t} — the pair
     * behind {@link Numbers#size}, which is why {@code pop(2 ** 70)} complains about a C {@code ssize_t}
     * where {@code [2 ** 70]} complains about an index-sized integer.
     */
    private static long counted(PyObject key) {
        return Numbers.size(key);
    }

    /** An index wrapped against this list, for {@code pop}, which bounds-checks. */
    private int popped(long value) {
        long at = value < 0 ? value + items.size() : value;
        if (at < 0 || at >= items.size()) {
            throw Slots.indexError("pop index out of range");
        }
        return (int) at;
    }

    /** An index clamped into this list, for {@code insert}, which never bounds-checks. */
    private int inserted(long value) {
        long at = value < 0 ? value + items.size() : value;
        return (int) Math.max(0, Math.min(at, items.size()));
    }

    /**
     * The keyword-only parameters of {@code sort}, checked in CPython's order: the count of
     * everything that arrived first, then the positional count, then the names — so
     * {@code sort(1, a=1, b=1)} complains about three arguments before it notices {@code a}.
     */
    private static PyObject[] sortParameters(PyObject[] args, String[] kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        // Keyword values ride at the end of args, so the positional count is what is left over.
        int positional = args.length - keywords;
        if (args.length > 2) {
            throw Slots.typeError(
                    "sort() takes at most 2 %sarguments (%d given)",
                    positional == 0 ? "keyword " : "", args.length);
        }
        if (positional != 0) {
            throw Slots.typeError("sort() takes no positional arguments");
        }
        PyObject[] parameters = {PyNone.INSTANCE, PyBool.FALSE};
        for (int i = 0; i < keywords; i++) {
            switch (kwNames[i]) {
                case "key" -> parameters[0] = args[positional + i];
                case "reverse" -> parameters[1] = args[positional + i];
                default -> throw Slots.typeError(
                        "sort() got an unexpected keyword argument '%s'", kwNames[i]);
            }
        }
        return parameters;
    }

    /**
     * {@code a.sort(key=…, reverse=…)}: CPython's Timsort, which is stable, and which leaves the
     * list exactly as it found it when a key function or a comparison fails. The second property is
     * free here because the order is computed over a separate array and only written back once the
     * whole of it is known.
     *
     * <p>CPython empties the list for the duration of the sort — a key function or a comparison that
     * looks at the list sees {@code []} — and remembers the original storage. If a callback touches
     * that storage, the sort reports {@code list modified during sort}; if a callback raises, the
     * original contents are put back before the exception leaves. {@link Items#mutations} is the
     * Java stand-in for comparing CPython's {@code ob_item} pointer.
     */
    private void sort(PyObject keyFunction, PyObject reverse) {
        boolean backwards = Slots.truth(reverse);
        PyObject[] before = elements();
        Ordered[] order = new Ordered[before.length];
        items.clear();
        int stamp = items.mutations;
        try {
            for (int i = 0; i < before.length; i++) {
                PyObject key = keyFunction == null || keyFunction == PyNone.INSTANCE
                        ? before[i]
                        : Slots.call(keyFunction, new PyObject[] {before[i]}, null);
                order[i] = new Ordered(before[i], key);
            }
            if (items.mutations != stamp) {
                throw Slots.valueError("list modified during sort");
            }
            Sequences.tolerantSort(
                    order, (left, right) -> versus(left.key(), right.key(), backwards));
            if (items.mutations != stamp) {
                throw Slots.valueError("list modified during sort");
            }
        } catch (RuntimeException failed) {
            restore(before);
            throw failed;
        }
        for (Ordered entry : order) {
            items.add(entry.element());
        }
    }

    /** Put {@code original} back after a sort whose callbacks changed or abandoned the list. */
    private void restore(PyObject[] original) {
        items.clear();
        items.addAll(Arrays.asList(original));
    }

    /**
     * One element and the value it is sorted by, which is the element itself without a key function.
     * The array of them is sorted by {@link Arrays}, whose sort is stable as CPython's is.
     */
    private record Ordered(PyObject element, PyObject key) {
    }

    /**
     * CPython's ordering, which asks only {@code <}: less-than either way, and equal when neither
     * holds. Asking nothing else is what lets a mixed list raise in the caller's frame instead of
     * being silently ordered by a Java comparison.
     */
    private static int versus(PyObject left, PyObject right, boolean reverse) {
        if (Slots.compareTrue(CompareOp.LT, left, right)) {
            return reverse ? 1 : -1;
        }
        if (Slots.compareTrue(CompareOp.LT, right, left)) {
            return reverse ? -1 : 1;
        }
        return 0;
    }

    /** A legacy method's keyword refusal, which comes before any counting. */
    private static void noKeywords(String qualified, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s takes no keyword arguments", qualified);
        }
    }

    // --- methods -------------------------------------------------------------------------

    /** The method table, in CPython's order. */
    private static final Members METHODS = Members.of(PyType.LIST)
            .addOne("append", (self, item) -> {
                ((PyList) self).items.add(item);
                return PyNone.INSTANCE;
            })
            .addNoArgument("clear", self -> {
                ((PyList) self).items.clear();
                return PyNone.INSTANCE;
            })
            .addNoArgument("copy", self -> gathered(((PyList) self).elements()))
            .addOne("count", (self, item) -> {
                // CPython's list_count re-reads Py_SIZE each step, so an `__eq__` that appends is
                // counted in the walk it is extending; a snapshot would stop one short.
                PyList list = (PyList) self;
                int found = 0;
                for (int i = 0; i < list.items.size(); i++) {
                    PyObject candidate = list.items.get(i);
                    if (candidate == item || Slots.compareTrue(CompareOp.EQ, candidate, item)) {
                        found++;
                    }
                }
                return PyInt.of(found);
            })
            .addOne("extend", (self, iterable) -> {
                ((PyList) self).extend(iterable);
                return PyNone.INSTANCE;
            })
            .add("index", (self, args, kwNames) -> {
                noKeywords("list.index()", kwNames);
                Arguments.between("index", args, kwNames, 1, 3);
                PyList list = (PyList) self;
                // CPython converts both bounds before it reads Py_SIZE and then re-reads the size on
                // every step: an `__index__` may clear the list and an `__eq__` may shrink it, so
                // neither the length nor the elements may be taken before the callbacks run.
                BigInteger start = Sequences.bound(args.length > 1 ? args[1] : null);
                BigInteger stop = Sequences.bound(args.length > 2 ? args[2] : null);
                int size = list.items.size();
                int startAt = Sequences.folded(start, size, 0);
                // CPython's default stop is PY_SSIZE_T_MAX, not the length it happens to see: an
                // `__eq__` that appends is still inside the walk the caller asked for.
                int stopAt = Sequences.folded(stop, size, Integer.MAX_VALUE);
                for (int i = startAt; i < stopAt && i < list.items.size(); i++) {
                    PyObject candidate = list.items.get(i);
                    if (candidate == args[0]
                            || Slots.compareTrue(CompareOp.EQ, candidate, args[0])) {
                        return PyInt.of(i);
                    }
                }
                throw Slots.valueError("list.index(x): x not in list");
            })
            .add("insert", (self, args, kwNames) -> {
                noKeywords("list.insert()", kwNames);
                Arguments.exactly("insert", args, kwNames, 2);
                PyList list = (PyList) self;
                list.items.add(list.inserted(counted(args[0])), args[1]);
                return PyNone.INSTANCE;
            })
            .add("pop", (self, args, kwNames) -> {
                noKeywords("list.pop()", kwNames);
                Arguments.atMost("pop", args, kwNames, 1);
                PyList list = (PyList) self;
                // CPython converts the index during argument parsing and only then asks whether the
                // list is empty, so an `__index__` that empties it is heard as "pop from empty list".
                boolean defaulted = args.length == 0;
                long index = defaulted ? -1 : counted(args[0]);
                if (list.items.isEmpty()) {
                    throw Slots.indexError("pop from empty list");
                }
                return list.items.remove(defaulted ? list.size() - 1 : list.popped(index));
            })
            .addOne("remove", (self, item) -> {
                PyList list = (PyList) self;
                // CPython's list_remove reads Py_SIZE and ob_item[i] again on every step, because
                // `__eq__` is arbitrary code that may shrink this very list. Iterating a snapshot
                // would leave a stale bound behind and could reach an index that no longer exists.
                for (int i = 0; i < list.items.size(); i++) {
                    PyObject candidate = list.items.get(i);
                    if (candidate == item || Slots.compareTrue(CompareOp.EQ, candidate, item)) {
                        // The comparison may have shortened the list; CPython deletes through
                        // list_ass_slice, whose clamp removes nothing once `i` is past the end.
                        if (i < list.items.size()) {
                            list.items.remove(i);
                        }
                        return PyNone.INSTANCE;
                    }
                }
                throw Slots.valueError("list.remove(x): x not in list");
            })
            .addNoArgument("reverse", self -> {
                Collections.reverse(((PyList) self).items);
                return PyNone.INSTANCE;
            })
            .add("sort", (self, args, kwNames) -> {
                PyObject[] parameters = sortParameters(args, kwNames);
                ((PyList) self).sort(parameters[0], parameters[1]);
                return PyNone.INSTANCE;
            })
            // The three names CPython files among its slots rather than copying them out of the sequence
            // protocol. `__getitem__` is here because `list_subscript` answers a slice as well as an index
            // and is therefore written down in `list_methods`, unlike the `bytes` and `tuple` copies of
            // `sq_item`; `__reversed__` is the one a `reversed()` call asks before it measures the list; and
            // `__sizeof__` counts a C struct this runtime does not have, so it says nought.
            .add(
                    "__getitem__",
                    (self, args, kwNames) -> {
                        Arguments.exactlyOne("list.__getitem__()", args, kwNames);
                        return self.getItem(args[0]);
                    })
            .add(
                    "__reversed__",
                    (self, args, kwNames) -> {
                        Arguments.none("list.__reversed__()", args, kwNames);
                        return SequenceIterators.listBackwards((PyList) self);
                    })
            .add(
                    "__sizeof__",
                    (self, args, kwNames) -> {
                        Arguments.none("list.__sizeof__()", args, kwNames);
                        return PyInt.of(0);
                    })
            // The fourteen sentences `list_methods` files, each the whole `ml_doc`: the row's own name, its
            // argument table, the `--` knife, and the prose. Thirteen are read from
            // `Objects/clinic/listobject.c.h` through the `*_METHODDEF` macro the table names; `__getitem__`
            // is the one row written by hand, text and all (listobject.c:3577). The table's fifteenth row is
            // `__class_getitem__`, a sentence with no table, whose `($type, object, /)` both runtimes read
            // out of the METH_O|METH_CLASS flags rather than out of a text.
            .document(
                    "__getitem__",
                    "__getitem__($self, index, /)\n--\n\nReturn self[index].")
            .document(
                    "__reversed__",
                    "__reversed__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a reverse iterator over the list.")
            .document(
                    "__sizeof__",
                    "__sizeof__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the size of the list in memory, in bytes.")
            .document(
                    "clear",
                    "clear($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove all items from list.")
            .document(
                    "copy",
                    "copy($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a shallow copy of the list.")
            .document(
                    "append",
                    "append($self, object, /)\n"
                            + "--\n"
                            + "\n"
                            + "Append object to the end of the list.")
            .document(
                    "insert",
                    "insert($self, index, object, /)\n"
                            + "--\n"
                            + "\n"
                            + "Insert object before index.")
            .document(
                    "extend",
                    "extend($self, iterable, /)\n"
                            + "--\n"
                            + "\n"
                            + "Extend list by appending elements from the iterable.")
            .document(
                    "pop",
                    "pop($self, index=-1, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove and return item at index (default last).\n"
                            + "\n"
                            + "Raises IndexError if list is empty or index is out of range.")
            .document(
                    "remove",
                    "remove($self, value, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove first occurrence of value.\n"
                            + "\n"
                            + "Raises ValueError if the value is not present.")
            .document(
                    "index",
                    "index($self, value, start=0, stop=sys.maxsize, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return first index of value.\n"
                            + "\n"
                            + "Raises ValueError if the value is not present.")
            .document(
                    "count",
                    "count($self, value, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return number of occurrences of value.")
            .document(
                    "reverse",
                    "reverse($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Reverse *IN PLACE*.")
            .document(
                    "sort",
                    "sort($self, /, *, key=None, reverse=False)\n"
                            + "--\n"
                            + "\n"
                            + "Sort the list in ascending order and return None.\n"
                            + "\n"
                            + "The sort is in-place (i.e. the list itself is modified) and stable\n"
                            + "(i.e. the order of two equal elements is maintained).\n"
                            + "\n"
                            + "If a key function is given, apply it once to each list item and sort\n"
                            + "them, ascending or descending, according to their function values.\n"
                            + "\n"
                            + "The reverse flag can be set to sort in descending order.");

    /** {@code a.extend(b)}: append every value of an iterable, which may be the list itself. */
    private void extend(PyObject iterable) {
        // CPython copies a list argument for exactly this case: `a.extend(a)` appends one pass.
        if (iterable instanceof PyList list) {
            items.addAll(Arrays.asList(list.elements()));
            return;
        }
        PyIterator iterator = Slots.iterator(iterable);
        items.ensureCapacity(items.size() + Slots.lengthHint(iterable, 8));
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            items.add(item);
        }
    }

    /** Room for {@code extra} more values; what a constructor's length hint asked to reserve. */
    void reserve(int extra) {
        items.ensureCapacity(items.size() + extra);
    }
}
