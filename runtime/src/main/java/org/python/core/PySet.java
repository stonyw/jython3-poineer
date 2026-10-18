package org.python.core;

import java.util.LinkedHashSet;
import java.util.Set;

public class PySet extends PyObject {

    final Set<PyObject> items;

    PySet(Set<PyObject> items, PyType type) {
        super(type);
        this.items = items;
    }

    public static PySet empty() {
        return new PySet(new LinkedHashSet<>(), PyType.SET);
    }

    public static PySet of(PyObject... items) {
        PySet s = empty();
        for (PyObject item : items) {
            s.items.add(item);
        }
        return s;
    }

    public int length() {
        return items.size();
    }

    public void add(PyObject item) {
        items.add(item);
    }

    public Set<PyObject> getItems() {
        return items;
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PySet s && items.equals(s.items);
    }

    /**
     * Iteration order is insertion order. CPython's is a hash-table artefact that cannot be
     * reproduced without its seed, so repr ordering is a known divergence.
     */
    @Override
    public String repr() {
        if (items.isEmpty()) {
            return type == PyType.FROZENSET ? "frozenset()" : "set()";
        }
        StringBuilder out = new StringBuilder(type == PyType.FROZENSET ? "frozenset({" : "{");
        boolean first = true;
        for (PyObject item : items) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(item.repr());
        }
        return out.append(type == PyType.FROZENSET ? "})" : "}").toString();
    }
}
