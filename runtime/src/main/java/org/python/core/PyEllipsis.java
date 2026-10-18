package org.python.core;

public final class PyEllipsis extends PyObject {

    public static final PyEllipsis INSTANCE = new PyEllipsis();

    private PyEllipsis() {
        super(PyType.ELLIPSIS);
    }

    @Override
    public long hash() {
        return PyHash.textHash("Ellipsis");
    }

    @Override
    public String repr() {
        return "Ellipsis";
    }
}
