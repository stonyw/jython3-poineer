package org.python.core;

import java.math.BigInteger;

public final class PyBool extends PyObject {

    public static final PyBool TRUE = new PyBool(true);
    public static final PyBool FALSE = new PyBool(false);

    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger ZERO = BigInteger.ZERO;

    private final boolean value;

    private PyBool(boolean value) {
        super(PyType.BOOL);
        this.value = value;
    }

    public static PyBool of(boolean value) {
        return value ? TRUE : FALSE;
    }

    public boolean getValue() {
        return value;
    }

    /** {@code hash(True) == hash(1)} is an invariant of the numeric tower, not an optimisation. */
    @Override
    public long hash() {
        return value ? 1 : 0;
    }

    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyBool b) {
            return value == b.value;
        }
        if (other instanceof PyInt i) {
            return i.getValue().equals(value ? ONE : ZERO);
        }
        return other instanceof PyFloat f && f.getValue() == (value ? 1.0 : 0.0);
    }

    @Override
    public String str() {
        return value ? "True" : "False";
    }

    @Override
    public String repr() {
        return str();
    }
}
