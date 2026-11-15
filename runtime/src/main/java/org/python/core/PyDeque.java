package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Python's {@code collections.deque}: a doubly-ended queue with an optional bound.
 *
 * <p>The storage is an {@link ArrayList} here, so an operation at the left end moves every element. That
 * is a performance difference from CPython's blocks and nothing else: every answer this class gives is
 * meant to be the one the reference gives, including the words of its refusals and which end loses an
 * element when a bounded deque overflows.
 *
 * <p>A deque counts its structural changes in {@link #state}. The iterators, {@code count}, {@code index},
 * {@code remove} and {@code in} all snapshot that count and refuse when it moved, which is CPython's
 * {@code deque mutated during iteration} and is visible precisely because a value being compared is
 * allowed to mutate the deque it lives in.
 */
public class PyDeque extends PyObject {

    /** The element storage, oldest first. */
    final List<PyObject> items = new ArrayList<>();

    /** The bound, or -1 when unbounded; {@code maxlen} shows as {@code None} in the unbounded case. */
    private long maxlen = -1;

    /**
     * How many structural changes this deque has made.
     *
     * <p>Every append, pop, insert, delete, clear, extend item and effective rotate moves it; a
     * replacement through {@code d[i] = x} and {@code reverse} do not, which is exactly CPython's set.
     * The iterators and the searches compare a snapshot against this before each step.
     */
    long state;

    private static final String[] PARAMETERS = {"iterable", "maxlen"};

    private static final String INIT_DOC =
            "Initialize self.  See help(type(self)) for accurate signature.";
    private static final String INSERT_DOC =
            "Insert x into the deque at position i.";
    private static final String INDEX_DOC =
            "Return first index of value.\n\nRaises ValueError if the value is not present.";
    private static final String ROTATE_DOC =
            "Rotate the deque n steps to the right (default n=1).  If n is negative, rotates left.";
    private static final String REVERSED_DOC =
            "Return a reverse iterator over the deque.";
    private static final String REDUCE_DOC =
            "Return state information for pickling.";
    private static final String COPY_DOC =
            "Return a shallow copy of a deque.";

    private static final Members METHODS = Members.of(PyType.DEQUE)
            .addValue("maxlen", new PyGetSetDescriptor(
                    PyType.DEQUE, "maxlen",
                    "maximum size of a deque or None if unbounded",
                    self -> ((PyDeque) self).maxlenValue(), null))
            .addOne("append", "Add an element to the right side of the deque.",
                    (self, item) -> ((PyDeque) self).appendRight(item))
            .addOne("appendleft", "Add an element to the left side of the deque.",
                    (self, item) -> ((PyDeque) self).appendLeft(item))
            .addOne("extend", "Extend the right side of the deque with elements from the iterable.",
                    (self, iterable) -> {
                        ((PyDeque) self).extendRight(iterable);
                        return PyNone.INSTANCE;
                    })
            .addOne("extendleft", "Extend the left side of the deque with elements from the iterable.",
                    (self, iterable) -> {
                        ((PyDeque) self).extendLeft(iterable);
                        return PyNone.INSTANCE;
                    })
            .addNoArgument("pop", "Remove and return the rightmost element.",
                    self -> ((PyDeque) self).removeLast())
            .addNoArgument("popleft", "Remove and return the leftmost element.",
                    self -> ((PyDeque) self).removeFirst())
            .add("insert", INSERT_DOC, PyDeque::insertMethod)
            .addOne("remove", "Remove the first occurrence of value.",
                    (self, value) -> ((PyDeque) self).removeValue(value))
            .addNoArgument("clear", "Remove all elements from the deque.",
                    self -> ((PyDeque) self).clearItems())
            .addNoArgument("copy", COPY_DOC, self -> ((PyDeque) self).copied())
            .addOne("count", "Return number of occurrences of value.",
                    (self, value) -> ((PyDeque) self).countOccurrences(value))
            .add("index", INDEX_DOC, PyDeque::indexMethod)
            .addNoArgument("reverse", "Reverse *IN PLACE*.",
                    self -> ((PyDeque) self).reverseItems())
            .add("rotate", ROTATE_DOC, PyDeque::rotateMethod)
            .add("__init__", INIT_DOC, PyDeque::initializeMethod)
            .addNoArgument("__reversed__", REVERSED_DOC,
                    self -> SequenceIterators.dequeBackwards((PyDeque) self))
            .addNoArgument("__copy__", COPY_DOC, self -> ((PyDeque) self).copied())
            .addNoArgument("__reduce__", REDUCE_DOC, PyDeque::reduced)
            .addValue("__doc__", PyType.DEQUE.getAttr("__doc__"));

    /** An empty, unbounded deque of the base type. */
    PyDeque() {
        this(-1, PyType.DEQUE);
    }

    PyDeque(long maxlen, PyType kind) {
        super(kind);
        this.maxlen = maxlen;
    }

    /** {@code deque(iterable[, maxlen])}: the constructor CPython reaches through {@code tp_new}. */
    static PyObject called(PyObject[] args, PyTuple keywordNames) {
        PyDeque result = new PyDeque();
        result.initialize(args, PyBoundMethod.keywordNames(keywordNames));
        return result;
    }

    /**
     * A fresh deque of the same class and bound, used by {@code copy}, {@code +} and {@code *}.
     *
     * <p>CPython builds these through {@code tp_new} rather than through the allocation routine, so a
     * class that replaced {@code __new__} is consulted and a creator that hands back something other
     * than a deque is refused — {@code "X() must return a deque, not list"} — instead of being quietly
     * replaced by a deque of the wrong class.
     */
    PyDeque likeThis() {
        PyType kind = getType();
        if (kind == PyType.DEQUE) {
            return new PyDeque(maxlen, PyType.DEQUE);
        }
        PyObject declared = Specials.lookUp(kind, "__new__");
        PyObject made;
        if (declared == null) {
            made = new PyDequeSubclass(kind, -1);
        } else {
            PyObject creator = declared instanceof PyClassMethod
                    ? Descriptors.get(declared, null, kind)
                    : declared;
            PyObject[] withClass = {kind};
            made = creator instanceof PyFunction plain
                    ? plain.call(withClass, PyTuple.EMPTY)
                    : Slots.call(creator, withClass, PyTuple.EMPTY);
        }
        if (!(made instanceof PyDeque deque)) {
            throw Slots.typeError(
                    "%s() must return a deque, not %s",
                    kind.name(), made.getType().filedName());
        }
        deque.maxlen = maxlen;
        return deque;
    }

    // ---------------------------------------------------------------- construction

    /**
     * {@code deque.__init__}: a bound read before anything is thrown away.
     *
     * <p>CPython reads and converts both arguments first, then replaces the bound, then clears, then
     * extends. A bad {@code maxlen} therefore leaves an existing deque untouched, and a re-run with the
     * bound left out resets the deque to unbounded rather than keeping the old one.
     */
    private static PyObject initializeMethod(PyObject self, PyObject[] args, String[] kwNames) {
        ((PyDeque) self).initialize(args, kwNames);
        return PyNone.INSTANCE;
    }

    void initialize(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.parameters(
                "deque", args, kwNames, PARAMETERS, new PyObject[] {null, null}, 0);
        long bound = -1;
        if (values[1] != null && values[1] != PyNone.INSTANCE) {
            bound = Numbers.asSsizeT(values[1]);
            if (bound < 0) {
                throw Slots.valueError("maxlen must be non-negative");
            }
        }
        maxlen = bound;
        if (!items.isEmpty()) {
            items.clear();
            state++;
        }
        if (values[0] != null) {
            extendRight(values[0]);
        }
    }

    // ---------------------------------------------------------------- method bodies

    private PyObject appendRight(PyObject item) {
        addLast(item);
        return PyNone.INSTANCE;
    }

    private PyObject appendLeft(PyObject item) {
        addFirst(item);
        return PyNone.INSTANCE;
    }

    private static PyObject insertMethod(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("deque.insert", kwNames);
        Arguments.exactly("insert", args, kwNames, 2);
        ((PyDeque) self).insertAt(Numbers.size(args[0]), args[1]);
        return PyNone.INSTANCE;
    }

    private static PyObject indexMethod(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("deque.index", kwNames);
        Arguments.between("index", args, kwNames, 1, 3);
        PyDeque deque = (PyDeque) self;
        BigInteger startBound = Sequences.bound(args.length > 1 ? args[1] : null);
        BigInteger stopBound = Sequences.bound(args.length > 2 ? args[2] : null);
        int size = deque.items.size();
        int start = Sequences.folded(startBound, size, 0);
        int stop = Sequences.folded(stopBound, size, Integer.MAX_VALUE);
        return PyInt.of(deque.indexOf(args[0], start, stop));
    }

    private static PyObject rotateMethod(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("deque.rotate", kwNames);
        Arguments.atMost("rotate", args, kwNames, 1);
        long steps = args.length == 0 ? 1 : Numbers.size(args[0]);
        ((PyDeque) self).rotate(steps);
        return PyNone.INSTANCE;
    }

    private PyObject removeLast() {
        if (items.isEmpty()) {
            throw Slots.indexError("pop from an empty deque");
        }
        state++;
        return items.remove(items.size() - 1);
    }

    private PyObject removeFirst() {
        if (items.isEmpty()) {
            throw Slots.indexError("pop from an empty deque");
        }
        state++;
        return items.remove(0);
    }

    private PyObject removeValue(PyObject value) {
        long expected = state;
        for (int i = 0; i < items.size(); i++) {
            boolean match = Slots.compareTrue(CompareOp.EQ, items.get(i), value);
            if (state != expected) {
                throw Slots.indexError("deque mutated during iteration");
            }
            if (match) {
                state++;
                items.remove(i);
                return PyNone.INSTANCE;
            }
        }
        throw Slots.valueError("deque.remove(x): x not in deque");
    }

    private PyObject clearItems() {
        if (!items.isEmpty()) {
            items.clear();
            state++;
        }
        return PyNone.INSTANCE;
    }

    private PyObject copied() {
        PyDeque result = likeThis();
        result.items.addAll(items);
        return result;
    }

    private PyObject countOccurrences(PyObject value) {
        long expected = state;
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            if (Slots.compareTrue(CompareOp.EQ, items.get(i), value)) {
                count++;
            }
            if (state != expected) {
                throw Slots.runtimeError("deque mutated during iteration");
            }
        }
        return PyInt.of(count);
    }

    private PyObject reverseItems() {
        Collections.reverse(items);
        return PyNone.INSTANCE;
    }

    private int indexOf(PyObject value, int start, int stop) {
        long expected = state;
        int size = items.size();
        for (int i = 0; i < size; i++) {
            // CPython compares every element, including those before `start`, so an __eq__
            // that mutates the deque is caught wherever it sits.
            if (Slots.compareTrue(CompareOp.EQ, items.get(i), value) && i >= start && i < stop) {
                return i;
            }
            if (state != expected) {
                throw Slots.runtimeError("deque mutated during iteration");
            }
        }
        throw Slots.valueError("deque.index(x): x not in deque");
    }

    private void insertAt(long index, PyObject value) {
        if (maxlen >= 0 && items.size() >= maxlen) {
            throw Slots.indexError("deque already at its maximum size");
        }
        int position = index < 0
                ? (int) Math.max(0, items.size() + index)
                : (int) Math.min(index, items.size());
        items.add(position, value);
        state++;
    }

    /** {@code deque.rotate(n)}: a count that divides out to nothing leaves the state alone. */
    private void rotate(long steps) {
        if (steps == 0 || items.isEmpty()) {
            return;
        }
        steps %= items.size();
        if (steps == 0) {
            return;
        }
        state++;
        Collections.rotate(items, (int) steps);
    }

    // ---------------------------------------------------------------- storage primitives

    void addLast(PyObject item) {
        if (maxlen == 0) {
            return;
        }
        items.add(item);
        state++;
        trimRight();
    }

    void addFirst(PyObject item) {
        if (maxlen == 0) {
            return;
        }
        items.add(0, item);
        state++;
        trimLeft();
    }

    private void trimRight() {
        while (maxlen >= 0 && items.size() > maxlen) {
            items.remove(0);
        }
    }

    private void trimLeft() {
        while (maxlen >= 0 && items.size() > maxlen) {
            items.remove(items.size() - 1);
        }
    }

    void extendRight(PyObject iterable) {
        if (iterable == this) {
            for (PyObject item : new ArrayList<>(items)) {
                addLast(item);
            }
            return;
        }
        PyIterator iterator = Slots.iterator(iterable);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            addLast(item);
        }
    }

    void extendLeft(PyObject iterable) {
        if (iterable == this) {
            for (PyObject item : new ArrayList<>(items)) {
                addFirst(item);
            }
            return;
        }
        PyIterator iterator = Slots.iterator(iterable);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            addFirst(item);
        }
    }

    private PyObject repeated(long times) {
        PyDeque result = likeThis();
        if (times <= 0 || items.isEmpty()) {
            return result;
        }
        int size = items.size();
        BigInteger copies = BigInteger.valueOf(size).multiply(BigInteger.valueOf(times));
        BigInteger total = maxlen >= 0
                ? copies.min(BigInteger.valueOf(maxlen))
                : copies;
        if (maxlen < 0 && total.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw Slots.memoryError();
        }
        BigInteger start = copies.subtract(total);
        int count = total.intValue();
        BigInteger stride = BigInteger.valueOf(size);
        for (int i = 0; i < count; i++) {
            result.items.add(items.get(start.add(BigInteger.valueOf(i)).mod(stride).intValue()));
        }
        return result;
    }

    private void multipliedInPlace(long times) {
        if (times <= 0) {
            if (!items.isEmpty()) {
                items.clear();
                state++;
            }
            return;
        }
        if (times == 1 || items.isEmpty()) {
            return;
        }
        int size = items.size();
        BigInteger copies = BigInteger.valueOf(size).multiply(BigInteger.valueOf(times));
        BigInteger total = maxlen >= 0 ? copies.min(BigInteger.valueOf(maxlen)) : copies;
        if (maxlen < 0 && total.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw Slots.memoryError();
        }
        BigInteger start = copies.subtract(total);
        int count = total.intValue();
        BigInteger stride = BigInteger.valueOf(size);
        List<PyObject> original = new ArrayList<>(items);
        items.clear();
        for (int i = 0; i < count; i++) {
            items.add(original.get(start.add(BigInteger.valueOf(i)).mod(stride).intValue()));
        }
        state++;
    }

    private PyObject added(PyObject other) {
        if (!(other instanceof PyDeque right)) {
            throw Slots.typeError(
                    "can only concatenate deque (not \"%s\") to deque", other.getType().name());
        }
        PyDeque result = likeThis();
        result.items.addAll(items);
        for (PyObject item : right.items) {
            result.addLast(item);
        }
        return result;
    }

    // ---------------------------------------------------------------- reduction

    private static PyObject reduced(PyObject self) {
        PyDeque deque = (PyDeque) self;
        PyObject arguments = deque.maxlen < 0
                ? PyTuple.EMPTY
                : PyTuple.of(PyTuple.EMPTY, PyInt.of(deque.maxlen));
        return PyTuple.of(
                self.getType(),
                arguments,
                TypeDict.stateOf(self),
                Slots.getIter(self));
    }

    // ---------------------------------------------------------------- object protocol

    @Override
    public PyObject getAttr(String name) {
        if (name.equals("maxlen")) {
            return maxlenValue();
        }
        return Members.find(this, name);
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        GenericAttributes.write(this, name, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String name) {
        GenericAttributes.delete(this, name);
        return PyNone.INSTANCE;
    }

    private PyObject maxlenValue() {
        return maxlen < 0 ? PyNone.INSTANCE : PyInt.of(maxlen);
    }

    @Override
    public long hash() {
        throw Slots.typeError("unhashable type: '%s'", getType().filedName());
    }

    /*
     * The slot bodies the type dictionary's wrappers call.
     *
     * A subclass's `__getitem__` reaches the wrapper filed under the name before it reaches `super()`,
     * so a wrapper that called the virtual slot would be sent straight back to the subclass. These
     * entry points are the same bodies under names no subclass overrides; the virtual slots above them
     * are what a plain deque and a `super()` call reach.
     */

    @Override
    public PyObject length() {
        return baseLength();
    }

    PyObject baseLength() {
        return PyInt.of(items.size());
    }

    @Override
    public PyObject truth() {
        return baseTruth();
    }

    PyObject baseTruth() {
        return PyBool.of(!items.isEmpty());
    }

    @Override
    public PyObject getItem(PyObject key) {
        return baseItem(key);
    }

    PyObject baseItem(PyObject key) {
        if (!Numbers.isIndex(key)) {
            throw Slots.typeError(
                    "sequence index must be integer, not '%s'", key.getType().filedName());
        }
        return items.get(Numbers.wrapIndex(key, Numbers.asIndex(key), items.size(),
                "deque index out of range"));
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        return baseSetItem(key, value);
    }

    PyObject baseSetItem(PyObject key, PyObject value) {
        items.set(checkedIndex(key), value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delItem(PyObject key) {
        return baseDelItem(key);
    }

    PyObject baseDelItem(PyObject key) {
        items.remove(checkedIndex(key));
        state++;
        return PyNone.INSTANCE;
    }

    /**
     * {@code deque_ass_item}'s index: {@code PyNumber_AsSsize_t(key, PyExc_OverflowError)}, folded
     * and bounds-checked. Unlike {@link #getItem}, a width overflow here is an {@code OverflowError}
     * with the "index-sized" sentence, which is what {@link Numbers#repeat} writes.
     */
    private int checkedIndex(PyObject key) {
        long index = Numbers.repeat(key);
        if (index < 0) {
            index += items.size();
        }
        if (index < 0 || index >= items.size()) {
            throw Slots.indexError("deque index out of range");
        }
        return (int) index;
    }

    @Override
    public PyObject contains(PyObject value) {
        return baseContains(value);
    }

    PyObject baseContains(PyObject value) {
        long expected = state;
        for (int i = 0; i < items.size(); i++) {
            if (Slots.compareTrue(CompareOp.EQ, items.get(i), value)) {
                return PyBool.TRUE;
            }
            if (state != expected) {
                throw Slots.runtimeError("deque mutated during iteration");
            }
        }
        return PyBool.FALSE;
    }

    @Override
    public PyIterator iterator() {
        return baseIterator();
    }

    PyIterator baseIterator() {
        return SequenceIterators.deque(this);
    }

    /** How many elements this deque holds, for the iterators and the constructors. */
    int size() {
        return items.size();
    }

    /** The element at a position already known to be in range. */
    PyObject at(int index) {
        return items.get(index);
    }

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return baseBinaryOp(op, other);
    }

    PyObject baseBinaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            return added(other);
        }
        if (op == BinaryOp.MUL) {
            return repeated(Sequences.multiplier(other));
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return baseReflectedOp(op, other);
    }

    PyObject baseReflectedOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.MUL ? repeated(Sequences.multiplier(other))
                : PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        return baseInplaceOp(op, other);
    }

    PyObject baseInplaceOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            extendRight(other);
            return this;
        }
        if (op == BinaryOp.MUL) {
            multipliedInPlace(Numbers.repeat(other));
            return this;
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject richCompare(CompareOp op, PyObject other) {
        return baseCompare(op, other);
    }

    PyObject baseCompare(CompareOp op, PyObject other) {
        if (!(other instanceof PyDeque deque)) {
            return PyNotImplemented.INSTANCE;
        }
        return Sequences.compare(op, items.toArray(new PyObject[0]),
                deque.items.toArray(new PyObject[0]));
    }

    @Override
    public boolean eq(PyObject other) {
        return baseEq(other);
    }

    boolean baseEq(PyObject other) {
        return other instanceof PyDeque deque && items.equals(deque.items);
    }

    @Override
    public String repr() {
        return baseRepr();
    }

    /** The repr text with CPython's re-entry guard around it. */
    String baseRepr() {
        if (enteringRepr(this)) {
            return "[...]";
        }
        try {
            return named();
        } finally {
            leavingRepr(this);
        }
    }

    /** The repr text without CPython's re-entry guard, which the caller owns. */
    String named() {
        StringBuilder text = new StringBuilder(getType().name()).append("([");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(Slots.repr(items.get(i)));
        }
        text.append(']');
        if (maxlen >= 0) {
            text.append(", maxlen=").append(maxlen);
        }
        return text.append(')').toString();
    }

}
