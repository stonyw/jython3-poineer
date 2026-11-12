package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code collections.deque}: a sequence that grows and shrinks at both ends.
 *
 * <p>The storage here is an {@link ArrayList}, so {@code appendleft} and {@code popleft} move the tail
 * rather than being the constant-time operations the reference's block ring makes them. What is kept is
 * the semantics a program can observe: the {@code maxlen} bound drops from the opposite end when it is
 * exceeded, iteration walks the current contents, and the type is unhashable.
 *
 * <p>Named gaps, all of them denied methods rather than wrong ones: no {@code insert}, {@code index} or
 * {@code __reduce__}, and {@code iter(d)} answers with the tuple cursor's type rather than a cursor of
 * its own. {@code collections} uses the three that matter — {@code append}, {@code pop} and
 * {@code __len__} — and {@code ExitStack} is built on exactly those.
 */
public final class PyDeque extends PyObject {

    private static final String APPEND_DOC = "Add an element to the right side of the deque.";
    private static final String APPENDLEFT_DOC = "Add an element to the left side of the deque.";
    private static final String CLEAR_DOC = "Remove all elements from the deque.";
    private static final String COPY_DOC = "Return a shallow copy of a deque.";
    private static final String COUNT_DOC =
            "Return number of occurrences of value.";
    private static final String EXTEND_DOC =
            "Extend the right side of the deque with elements from the iterable.";
    private static final String EXTENDLEFT_DOC =
            "Extend the left side of the deque with elements from the iterable.";
    private static final String POP_DOC = "Remove and return the rightmost element.";
    private static final String POPLEFT_DOC = "Remove and return the leftmost element.";
    private static final String REMOVE_DOC =
            "Remove the first occurrence of value.";
    private static final String REVERSE_DOC = "Reverse *IN PLACE*.";
    private static final String ROTATE_DOC =
            "Rotate the deque n steps to the right (default 1).";

    private static final String[] PARAMETERS = {"iterable", "maxlen"};

    private static final Members METHODS = Members.of(PyType.DEQUE)
            .addField("maxlen", "maximum size of a deque or None if unbounded",
                    self -> {
                        int maxlen = ((PyDeque) self).maxlen;
                        return maxlen < 0 ? PyNone.INSTANCE : PyInt.of(maxlen);
                    })
            .addOne("append", APPEND_DOC, (self, item) -> ((PyDeque) self).addLast(item))
            .addOne("appendleft", APPENDLEFT_DOC, (self, item) -> ((PyDeque) self).addFirst(item))
            .addNoArgument("clear", CLEAR_DOC, self -> {
                ((PyDeque) self).items.clear();
                return PyNone.INSTANCE;
            })
            .addNoArgument("copy", COPY_DOC, self -> ((PyDeque) self).copied())
            .addOne("count", COUNT_DOC, (self, value) -> PyInt.of(((PyDeque) self).countOf(value)))
            .addOne("extend", EXTEND_DOC, (self, iterable) -> ((PyDeque) self).extendRight(iterable))
            .addOne("extendleft", EXTENDLEFT_DOC,
                    (self, iterable) -> ((PyDeque) self).extendLeft(iterable))
            .addNoArgument("pop", POP_DOC, self -> ((PyDeque) self).removeLast())
            .addNoArgument("popleft", POPLEFT_DOC, self -> ((PyDeque) self).removeFirst())
            .addOne("remove", REMOVE_DOC, (self, value) -> ((PyDeque) self).removeValue(value))
            .addNoArgument("reverse", REVERSE_DOC, self -> {
                java.util.Collections.reverse(((PyDeque) self).items);
                return PyNone.INSTANCE;
            })
            .addOne("rotate", ROTATE_DOC, (self, steps) ->
                    ((PyDeque) self).rotate(Numbers.asIndex(steps).intValueExact()))
            .addValue("__doc__", PyType.DEQUE.getAttr("__doc__"));

    private final List<PyObject> items = new ArrayList<>();

    /** The bound on the length, or a negative number for an unbounded deque. */
    private final int maxlen;

    private PyDeque(int maxlen) {
        super(PyType.DEQUE);
        this.maxlen = maxlen;
    }

    /**
     * {@code deque(iterable, maxlen)}: the call {@link Constructors#instantiate} sends here.
     *
     * <p>{@code maxlen} is read before the iterable is drained, because the reference refuses a negative
     * bound whether or not there is anything to put in it.
     */
    static PyObject called(PyObject[] args, PyTuple keywordNames) {
        String[] names = new String[keywordNames == null ? 0 : keywordNames.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = Numbers.asText(keywordNames.at(i));
        }
        PyObject[] values = Arguments.parameters(
                "deque", args, names, PARAMETERS,
                new PyObject[] {null, PyNone.INSTANCE}, 0);
        int bound = -1;
        if (values[1] != PyNone.INSTANCE) {
            bound = Numbers.asIndex(values[1]).intValueExact();
            if (bound < 0) {
                throw Slots.valueError("maxlen must be non-negative");
            }
        }
        PyDeque result = new PyDeque(bound);
        if (values[0] != null) {
            result.extendRight(values[0]);
        }
        return result;
    }

    private PyObject copied() {
        PyDeque copy = new PyDeque(maxlen);
        copy.items.addAll(items);
        return copy;
    }

    // --- storage -------------------------------------------------------------------------------------

    private PyObject addLast(PyObject item) {
        if (maxlen == 0) {
            return PyNone.INSTANCE;
        }
        items.add(item);
        discardOverflow();
        return PyNone.INSTANCE;
    }

    private PyObject addFirst(PyObject item) {
        if (maxlen == 0) {
            return PyNone.INSTANCE;
        }
        items.add(0, item);
        discardOverflow();
        return PyNone.INSTANCE;
    }

    private void discardOverflow() {
        if (maxlen > 0 && items.size() > maxlen) {
            items.remove(0);
        }
    }

    private PyObject removeLast() {
        if (items.isEmpty()) {
            throw Slots.indexError("pop from an empty deque");
        }
        return items.remove(items.size() - 1);
    }

    private PyObject removeFirst() {
        if (items.isEmpty()) {
            throw Slots.indexError("pop from an empty deque");
        }
        return items.remove(0);
    }

    private PyObject removeValue(PyObject value) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).eq(value)) {
                return items.remove(i);
            }
        }
        throw Slots.valueError("deque.remove(x): x not in deque");
    }

    private int countOf(PyObject value) {
        int found = 0;
        for (PyObject candidate : items) {
            if (candidate.eq(value)) {
                found++;
            }
        }
        return found;
    }

    private PyObject extendRight(PyObject iterable) {
        if (iterable instanceof PyDeque other) {
            for (PyObject item : new ArrayList<>(other.items)) {
                addLast(item);
            }
            return PyNone.INSTANCE;
        }
        PyIterator iterator = Slots.iterator(iterable);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            addLast(item);
        }
        return PyNone.INSTANCE;
    }

    private PyObject extendLeft(PyObject iterable) {
        PyIterator iterator = Slots.iterator(iterable);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            addFirst(item);
        }
        return PyNone.INSTANCE;
    }

    private PyObject rotate(int steps) {
        int size = items.size();
        if (size == 0) {
            return PyNone.INSTANCE;
        }
        int shift = Math.floorMod(steps, size);
        if (shift == 0) {
            return PyNone.INSTANCE;
        }
        for (int i = 0; i < shift; i++) {
            addFirst(removeLast());
        }
        return PyNone.INSTANCE;
    }

    // --- slots ---------------------------------------------------------------------------------------

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        throw Slots.attributeError("'%s' object has no attribute '%s' and no __dict__ for setting"
                + " new attributes", getType().filedName(), name);
    }

    @Override
    public PyObject delAttr(String name) {
        return setAttr(name, null);
    }

    @Override
    public PyObject length() {
        return PyInt.of(items.size());
    }

    @Override
    public PyObject getItem(PyObject key) {
        if (Slices.isSlice(key)) {
            throw Slots.typeError("sequence index must be integer, not 'slice'");
        }
        return items.get(Slices.index(key, items.size(), "deque", "deque index out of range"));
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        if (Slices.isSlice(key)) {
            throw Slots.typeError("sequence index must be integer, not 'slice'");
        }
        items.set(Slices.index(key, items.size(), "deque", "deque index out of range"), value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delItem(PyObject key) {
        if (Slices.isSlice(key)) {
            throw Slots.typeError("sequence index must be integer, not 'slice'");
        }
        items.remove(Slices.index(key, items.size(), "deque", "deque index out of range"));
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject contains(PyObject item) {
        return PyBool.of(countOf(item) > 0);
    }

    @Override
    public PyObject iterator() {
        return SequenceIterators.of(this);
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (cmp != CompareOp.EQ && cmp != CompareOp.NE || !(other instanceof PyDeque deque)) {
            return PyNotImplemented.INSTANCE;
        }
        return Sequences.compare(cmp, items.toArray(new PyObject[0]),
                deque.items.toArray(new PyObject[0]));
    }

    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyDeque deque) || deque.items.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).eq(deque.items.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String repr() {
        StringBuilder out = new StringBuilder("deque([");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(items.get(i).repr());
        }
        out.append(']');
        if (maxlen >= 0) {
            out.append(", maxlen=").append(maxlen);
        }
        return out.append(')').toString();
    }
}
