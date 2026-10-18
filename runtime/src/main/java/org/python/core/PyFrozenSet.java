package org.python.core;

import java.util.LinkedHashSet;
import java.util.Set;

public final class PyFrozenSet extends PySet {

    public static final PyFrozenSet EMPTY = new PyFrozenSet(new LinkedHashSet<>());

    PyFrozenSet(Set<PyObject> items) {
        super(items, PyType.FROZENSET);
    }

    public static PyFrozenSet of(PyObject... items) {
        if (items.length == 0) {
            return EMPTY;
        }
        PyFrozenSet s = new PyFrozenSet(new LinkedHashSet<>());
        for (PyObject item : items) {
            s.items.add(item);
        }
        return s;
    }

    /** Order-independent, as CPython's is; the mixing itself need not match. */
    @Override
    public long hash() {
        long acc = 0;
        for (PyObject item : items) {
            acc += item.hash() * 0x9E3779B97F4A7C15L;
        }
        return PyHash.avoidMinusOne(acc);
    }
}
