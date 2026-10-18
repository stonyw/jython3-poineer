package org.python.core;

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

    public int length() {
        return items.length;
    }

    public PyObject at(int index) {
        return items[index];
    }

    public PyObject[] getArray() {
        return items.clone();
    }

    public boolean contains(PyObject other) {
        for (PyObject item : items) {
            if (item.eq(other)) {
                return true;
            }
        }
        return false;
    }

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
