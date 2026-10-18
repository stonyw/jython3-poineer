package org.python.core;

/**
 * {@code NotImplemented}: the answer a slot gives when it declines to handle the other operand.
 *
 * Declining is what makes CPython's reflected-operator protocol work, so it has to be representable
 * inside the JVM rather than flattened into a Java exception. {@link Slots} is the only place that
 * looks for it; a value of this reaching Python code would be a bug in {@code Slots}, not a feature.
 */
public final class PyNotImplemented extends PyObject {

    public static final PyNotImplemented INSTANCE = new PyNotImplemented();

    /** Marks "this type does not define that slot", for {@link Slots}. */
    static final PyObject DECLINED = INSTANCE;

    private PyNotImplemented() {
        super(PyType.NOTIMP);
    }

    @Override
    public String str() {
        return "NotImplemented";
    }

    @Override
    public String repr() {
        return "NotImplemented";
    }
}
