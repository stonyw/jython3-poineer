package org.python.core;

/**
 * Python's {@code tuple}: a fixed array of values.
 *
 * Immutability is what lets a tuple be a dictionary key, and is why {@link #fill(int, PyObject)}
 * exists at all: the marshal reader needs to publish a tuple before its elements are known, and that
 * one window is the only time a tuple's contents are not final.
 *
 * <p>Not final, because {@link PyVersionInfo} is a tuple that reports a type of its own — the one way
 * CPython's named sequences exist, and the reason every operation below builds a {@code PyTuple} rather
 * than {@code getClass().cast(this)}: {@code version[:2]} is a plain tuple in CPython, and a slice that
 * kept the subtype would be a wrong answer rather than a stylistic one.
 */
public class PyTuple extends PyObject {

    public static final PyTuple EMPTY = new PyTuple(new PyObject[0]);

    private final PyObject[] items;

    PyTuple(PyObject[] items, PyType kind) {
        super(kind);
        this.items = items;
    }

    private PyTuple(PyObject[] items) {
        this(items, PyType.TUPLE);
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
        return items[Slices.index(key, items.length, "tuple", "tuple index out of range")];
    }

    /** {@code a + b}, which is defined for a tuple and a tuple only. */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            // `tuple_concat` raises rather than declining, so `() + []` names only the right operand.
            if (!(other instanceof PyTuple t)) {
                throw Slots.typeError(
                        "can only concatenate tuple (not \"%s\") to tuple", other.getType().name());
            }
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
     *
     * <p>A foreign operand is declined rather than answered: {@code tuple_tp_richcompare} returns
     * {@code NULL, NULL} without an error when the other value is not a tuple, so {@code (1,).__eq__([1])}
     * is {@code NotImplemented} and the {@code ==} operator is left to fall back on identity.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyTuple t
                ? Sequences.compare(cmp, items, t.items)
                : PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    /** {@code count} and {@code index}, the only two a tuple has, since nothing may change it. */
    private static final Members METHODS = Members.of(PyType.TUPLE)
            // Pickle's one argument: a tuple is rebuilt from the tuple it already is, which is why
            // {@code (1, 2).__getnewargs__()} is {@code ((1, 2),)} and not the two elements loose. The
            // entry is the one in either of these two tables whose {@code ml_doc} is NULL, so the table
            // transcribes no sentence — which is the deviation and the reference agreeing for once.
            .addNoArgument("__getnewargs__", self -> PyTuple.of(self))
            .addOne("count", (self, item) ->
                    PyInt.of(Sequences.countOf(((PyTuple) self).items, item, 0, ((PyTuple) self).size())))
            .add("index", (self, args, kwNames) -> {
                Arguments.noKeywords("tuple.index()", kwNames);
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
