package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
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

    private final List<PyObject> items;

    private PyList(List<PyObject> items) {
        super(PyType.LIST);
        this.items = items;
    }

    public static PyList empty() {
        return new PyList(new ArrayList<>());
    }

    public static PyList of(PyObject... items) {
        return new PyList(new ArrayList<>(List.of(items)));
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
            return gathered(Sequences.gather(elements(), Slices.of((PySlice) key, items.size())));
        }
        return items.get(Slices.index(key, items.size(), "list"));
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
        for (PyObject candidate : items) {
            if (candidate.eq(item)) {
                return PyBool.TRUE;
            }
        }
        return PyBool.FALSE;
    }

    @Override
    public PyObject iterator() {
        return PyIterator.of(items);
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

    /** Element-wise order with length as the tie-break, as for a {@code tuple}. */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyList list
                ? Sequences.compare(cmp, elements(), list.elements())
                : super.richCompare(cmp, other);
    }

    @Override
    public PyObject getAttr(String name) {
        return METHODS.bind(this, name);
    }

    /** Mutable, therefore unhashable; {@link #hashCode()} inherits the same refusal. */
    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyList list) || list.items.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).eq(list.items.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String repr() {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(items.get(i).repr());
        }
        return out.append(']').toString();
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
        return new PyList(new ArrayList<>(Arrays.asList(elements)));
    }

    /** {@code l[a:b] = value} and {@code l[a:b:c] = value}, and the same two in deletion form. */
    private void assign(PySlice key, PyObject value) {
        // The replacement is materialised before the list is touched, so `l[::-1] = l` reverses.
        PyObject[] replacement = value == null ? null : materialise(value);
        Slices.Range range = Slices.of(key, items.size());
        if (value == null) {
            if (range.step() == 1) {
                items.subList(range.start(), range.start() + range.count()).clear();
                return;
            }
            for (int i = range.count() - 1; i >= 0; i--) {
                items.remove(range.at(i));
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
        if (!Slots.isIterable(value)) {
            throw Slots.typeError("must assign iterable to extended slice");
        }
        return drain(Slots.iterator(value));
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
     */
    private int position(PyObject key) {
        BigInteger value = Numbers.index(key, "list");
        if (!Numbers.fitsInt(value)) {
            throw Slots.indexError("list assignment index out of range");
        }
        int at = value.intValue();
        if (at < 0) {
            at += items.size();
        }
        if (at < 0 || at >= items.size()) {
            throw Slots.indexError("list assignment index out of range");
        }
        return at;
    }

    /**
     * An index for {@code pop} and {@code insert}, which CPython converts as an {@code __index__}
     * rather than as a subscript: a float is refused in other words, and an integer too wide for a
     * machine word is an {@code OverflowError} instead of an {@code IndexError}.
     */
    private static long counted(PyObject key) {
        BigInteger value = Numbers.asInteger(key);
        if (value == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", key.getType().name());
        }
        if (value.bitLength() >= 64) {
            throw Slots.overflowError("Python int too large to convert to C ssize_t");
        }
        return value.longValue();
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
     */
    private void sort(PyObject keyFunction, PyObject reverse) {
        boolean backwards = Slots.truth(reverse);
        PyObject[] before = elements();
        Ordered[] order = new Ordered[before.length];
        for (int i = 0; i < before.length; i++) {
            PyObject key = keyFunction == null || keyFunction == PyNone.INSTANCE
                    ? before[i]
                    : Slots.call(keyFunction, new PyObject[] {before[i]}, null);
            order[i] = new Ordered(before[i], key);
        }
        Arrays.sort(order, (left, right) -> versus(left.key(), right.key(), backwards));
        items.clear();
        for (Ordered entry : order) {
            items.add(entry.element());
        }
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
                PyObject[] elements = ((PyList) self).elements();
                return PyInt.of(Sequences.countOf(elements, item, 0, elements.length));
            })
            .addOne("extend", (self, iterable) -> {
                ((PyList) self).extend(iterable);
                return PyNone.INSTANCE;
            })
            .add("index", (self, args, kwNames) -> {
                noKeywords("list.index()", kwNames);
                Arguments.between("index", args, kwNames, 1, 3);
                PyList list = (PyList) self;
                PyObject[] elements = list.elements();
                int found = Sequences.indexOf(
                        elements,
                        args[0],
                        args.length > 1 ? Sequences.searchStart(args[1], elements.length) : 0,
                        args.length > 2
                                ? Sequences.searchStop(args[2], elements.length)
                                : elements.length);
                if (found < 0) {
                    throw Slots.valueError("list.index(x): x not in list");
                }
                return PyInt.of(found);
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
                if (list.items.isEmpty()) {
                    throw Slots.indexError("pop from empty list");
                }
                return list.items.remove(args.length == 0 ? list.size() - 1 : list.popped(counted(args[0])));
            })
            .addOne("remove", (self, item) -> {
                PyList list = (PyList) self;
                int found = Sequences.indexOf(list.elements(), item, 0, list.size());
                if (found < 0) {
                    throw Slots.valueError("list.remove(x): x not in list");
                }
                list.items.remove(found);
                return PyNone.INSTANCE;
            })
            .addNoArgument("reverse", self -> {
                Collections.reverse(((PyList) self).items);
                return PyNone.INSTANCE;
            })
            .add("sort", (self, args, kwNames) -> {
                PyObject[] parameters = sortParameters(args, kwNames);
                ((PyList) self).sort(parameters[0], parameters[1]);
                return PyNone.INSTANCE;
            });

    /** {@code a.extend(b)}: append every value of an iterable, which may be the list itself. */
    private void extend(PyObject iterable) {
        // CPython copies a list argument for exactly this case: `a.extend(a)` appends one pass.
        if (iterable instanceof PyList list) {
            items.addAll(Arrays.asList(list.elements()));
            return;
        }
        for (PyObject item : drain(Slots.iterator(iterable))) {
            items.add(item);
        }
    }
}
