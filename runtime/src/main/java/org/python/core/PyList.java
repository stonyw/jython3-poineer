package org.python.core;

import java.util.ArrayList;
import java.util.List;

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

    public int length() {
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

    /** Mutable, therefore unhashable; {@link #hashCode()} inherits the same refusal. */
    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyList l) || l.items.size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).eq(l.items.get(i))) {
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
}
