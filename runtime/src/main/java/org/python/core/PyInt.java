package org.python.core;

import java.math.BigInteger;

/** Python's {@code int}: arbitrary precision, with the small-value cache CPython relies on. */
public final class PyInt extends PyObject {

    /** CPython caches -5..256; identity of those values is observable through {@code is}. */
    private static final int CACHE_MIN = -5;
    private static final int CACHE_MAX = 256;
    private static final PyInt[] CACHE = new PyInt[CACHE_MAX - CACHE_MIN + 1];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new PyInt(BigInteger.valueOf(i + CACHE_MIN));
        }
    }

    private final BigInteger value;

    private PyInt(BigInteger value) {
        super(PyType.INT);
        this.value = value;
    }

    public static PyInt of(long v) {
        if (v >= CACHE_MIN && v <= CACHE_MAX) {
            return CACHE[(int) (v - CACHE_MIN)];
        }
        return new PyInt(BigInteger.valueOf(v));
    }

    public static PyInt of(BigInteger v) {
        if (v.bitLength() < 32) {
            return of(v.longValue());
        }
        return new PyInt(v);
    }

    public BigInteger getValue() {
        return value;
    }

    /** True when the value survives a round trip through {@code long} exactly. */
    public boolean fitsLong() {
        return value.bitLength() < 64;
    }

    @Override
    public long hash() {
        return PyHash.intHash(value);
    }

    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyInt i) {
            return value.equals(i.value);
        }
        if (other instanceof PyBool b) {
            return b.eq(this);
        }
        if (other instanceof PyFloat f) {
            double d = f.getValue();
            return !Double.isNaN(d) && !Double.isInfinite(d)
                    && new java.math.BigDecimal(value).compareTo(new java.math.BigDecimal(d)) == 0;
        }
        if (other instanceof PyComplex c) {
            return c.imaginaryIsZero() && eq(c.realPart());
        }
        return false;
    }

    @Override
    public String repr() {
        return value.toString();
    }
}
