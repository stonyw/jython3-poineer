package org.python.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Python's {@code dict}. Insertion order is language-visible, hence {@code LinkedHashMap}. */
public final class PyDict extends PyObject {

    private final Map<PyObject, PyObject> items;

    private PyDict(Map<PyObject, PyObject> items) {
        super(PyType.DICT);
        this.items = items;
    }

    public static PyDict empty() {
        return new PyDict(new LinkedHashMap<>());
    }

    public int length() {
        return items.size();
    }

    public PyObject get(PyObject key) {
        return items.get(key);
    }

    public void put(PyObject key, PyObject value) {
        items.put(key, value);
    }

    public Map<PyObject, PyObject> getItems() {
        return items;
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyDict d && items.equals(d.items);
    }

    @Override
    public String repr() {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<PyObject, PyObject> e : items.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(e.getKey().repr()).append(": ").append(e.getValue().repr());
        }
        return out.append('}').toString();
    }
}
