package org.python.core;

/**
 * A module: a name and the dictionary it owns.
 *
 * {@code __main__} is one of these from the first slice, because a module-level code object runs with
 * a module's dict as its globals and {@code LOAD_NAME} writes straight into it. The import system that
 * populates the rest of them is M4.
 */
public final class PyModule extends PyObject {

    private final String name;
    private final PyDict dictionary;
    private PyObject doc = PyNone.INSTANCE;

    public PyModule(String name) {
        this(name, PyDict.empty());
    }

    PyModule(String name, PyDict dictionary) {
        super(PyType.MODULE);
        this.name = name;
        this.dictionary = dictionary;
        dictionary.put(PyStr.interned("__name__"), PyStr.of(name));
        dictionary.put(PyStr.interned("__doc__"), doc);
    }

    public String name() {
        return name;
    }

    /** The namespace {@code LOAD_NAME} and {@code STORE_NAME} use. */
    public PyDict dictionary() {
        return dictionary;
    }

    public void set(String key, PyObject value) {
        dictionary.put(PyStr.interned(key), value);
    }

    public PyObject get(String key) {
        return dictionary.get(PyStr.interned(key));
    }

    /**
     * Module attributes are dictionary entries, with the one exception CPython also has:
     * {@code __dict__} is the dictionary itself, and only that name is read-only.
     */
    @Override
    public PyObject getAttr(String attribute) {
        if ("__dict__".equals(attribute)) {
            return dictionary;
        }
        PyObject value = dictionary.get(PyStr.interned(attribute));
        return value == null ? PyNotImplemented.INSTANCE : value;
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        dictionary.put(PyStr.interned(attribute), value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code del module.name}: the entry goes, and a name the dictionary never held is refused as a read
     * of it is.
     *
     * <p>{@code del sys.excepthook} is how a program asks CPython to print an escaping exception itself,
     * so the launcher's read of the name has to be able to come back missing rather than as {@code None}
     * — which CPython would call, and answer {@code 'NoneType' object is not callable}.
     */
    @Override
    public PyObject delAttr(String attribute) {
        if ("__dict__".equals(attribute)) {
            throw Slots.attributeError("readonly attribute");
        }
        return dictionary.remove(PyStr.interned(attribute)) == null
                ? PyNotImplemented.INSTANCE
                : PyNone.INSTANCE;
    }

    @Override
    public PyObject getItem(PyObject key) {
        String name = Numbers.asText(key);
        if (name == null) {
            throw Slots.typeError(
                    "module indices must be str, not %s", key.getType().name());
        }
        PyObject value = dictionary.get(PyStr.of(name));
        if (value == null) {
            throw Slots.keyError(key);
        }
        return value;
    }

    @Override
    public String repr() {
        return "<module '" + name + "'>";
    }
}
