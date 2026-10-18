package org.python.core;

public final class PyNone extends PyObject {

    public static final PyNone INSTANCE = new PyNone();

    private PyNone() {
        super(PyType.NONE);
    }

    /** {@code None} is false, and has no length for {@link Slots#truth} to fall back to. */
    @Override
    public PyObject truth() {
        return PyBool.FALSE;
    }

    /** CPython hashes {@code None} by address; a fixed value is the only reproducible choice. */
    @Override
    public long hash() {
        return PyHash.textHash("None");
    }

    @Override
    public String str() {
        return "None";
    }

    @Override
    public String repr() {
        return "None";
    }
}
