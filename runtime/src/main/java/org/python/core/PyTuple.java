package org.python.core;

/**
 * Python's {@code tuple}: a fixed array of values.
 *
 * Immutability is what lets a tuple be a dictionary key, and is why {@link #fill(int, PyObject)}
 * exists at all: the marshal reader needs to publish a tuple before its elements are known, and that
 * one window is the only time a tuple's contents are not final.
 */
public final class PyTuple extends PyObject {

    public static final PyTuple EMPTY = new PyTuple(new PyObject[0]);

    private final PyObject[] items;

    private PyTuple(PyObject[] items) {
        super(PyType.TUPLE);
        this.items = items;
    }

    public static PyTuple of(PyObject... items) {
        return items.length == 0 ? EMPTY : new PyTuple(items.clone());
    }

    /**
     * A tuple of known length whose items arrive afterwards.
     *
     * A marshal stream may reference a tuple before its elements have been read — CPython's reader
     * registers the tuple object first and fills it in place — so a reader needs an instance to hand
     * out while it is still incomplete. Every index must be filled before the value can escape.
     */
    public static PyTuple filling(int length) {
        return length == 0 ? EMPTY : new PyTuple(new PyObject[length]);
    }

    public void fill(int index, PyObject value) {
        items[index] = value;
    }

    public int size() {
        return items.length;
    }

    public PyObject at(int index) {
        return items[index];
    }

    public PyObject[] getArray() {
        return items.clone();
    }

    /** The elements as an array, for the sequence protocol; a tuple is already one. */
    PyObject[] elements() {
        return items;
    }

    @Override
    public PyObject length() {
        return PyInt.of(items.length);
    }

    /** {@code t[i]} and {@code t[a:b:c]}. */
    @Override
    public PyObject getItem(PyObject key) {
        if (Slices.isSlice(key)) {
            return new PyTuple(Sequences.gather(items, Slices.of((PySlice) key, items.length)));
        }
        return items[Slices.index(key, items.length, "tuple")];
    }

    /** {@code a + b}, which is defined for a tuple and a tuple only. */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD && other instanceof PyTuple t) {
            PyObject[] joined = new PyObject[items.length + t.items.length];
            System.arraycopy(items, 0, joined, 0, items.length);
            System.arraycopy(t.items, 0, joined, items.length, t.items.length);
            return new PyTuple(joined);
        }
        if (op == BinaryOp.MUL) {
            return new PyTuple(Sequences.multiplied(items, other));
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.MUL ? binaryOp(op, other) : PyNotImplemented.INSTANCE;
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

    /**
     * Ordering, element by element with length as the tie-break — CPython's rule, and observable:
     * {@code (1, 2) < (1, 2, 0)} is true because the prefix is equal and the shorter comes first.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyTuple t
                ? Sequences.compare(cmp, items, t.items)
                : super.richCompare(cmp, other);
    }

    @Override
    public PyObject getAttr(String name) {
        return METHODS.bind(this, name);
    }

    /** {@code count} and {@code index}, the only two a tuple has, since nothing may change it. */
    private static final Members METHODS = Members.of("tuple")
            .addOne("count", (self, item) ->
                    PyInt.of(Sequences.countOf(((PyTuple) self).items, item, 0, ((PyTuple) self).size())))
            .add("index", (self, args, kwNames) -> {
                PyTuple tuple = (PyTuple) self;
                Arguments.between("index", args, kwNames, 1, 3);
                int found = Sequences.indexOf(
                        tuple.items,
                        args[0],
                        args.length > 1 ? Sequences.searchStart(args[1], tuple.size()) : 0,
                        args.length > 2 ? Sequences.searchStop(args[2], tuple.size()) : tuple.size());
                if (found < 0) {
                    throw Slots.valueError("tuple.index(x): x not in tuple");
                }
                return PyInt.of(found);
            });

    @Override
    public long hash() {
        long[] lanes = new long[items.length];
        for (int i = 0; i < items.length; i++) {
            lanes[i] = items[i].hash();
        }
        return PyHash.tupleHash(lanes);
    }

    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyTuple t) || t.items.length != items.length) {
            return false;
        }
        for (int i = 0; i < items.length; i++) {
            if (!items[i].eq(t.items[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String repr() {
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(items[i].repr());
        }
        if (items.length == 1) {
            out.append(',');
        }
        return out.append(')').toString();
    }
}
