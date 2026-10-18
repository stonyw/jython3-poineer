package org.python.core;

import java.util.Arrays;

public final class PyBytes extends PyObject {

    public static final PyBytes EMPTY = new PyBytes(new byte[0]);

    private final byte[] value;

    private PyBytes(byte[] value) {
        super(PyType.BYTES);
        this.value = value;
    }

    public static PyBytes of(byte[] value) {
        return value.length == 0 ? EMPTY : new PyBytes(value.clone());
    }

    /** Without copying: for literals read straight out of a marshal stream. */
    public static PyBytes wrap(byte[] value) {
        return value.length == 0 ? EMPTY : new PyBytes(value);
    }

    public byte[] getValue() {
        return value.clone();
    }

    public int length() {
        return value.length;
    }

    @Override
    public long hash() {
        return PyHash.bytesHash(value);
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyBytes b && Arrays.equals(value, b.value);
    }

    @Override
    public String repr() {
        return PyRepr.quoteBytes(value);
    }
}
