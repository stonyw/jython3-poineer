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

    /** The two values the rest of the tower keeps needing — both from the cache, so they are shared. */
    public static final PyInt ZERO = of(0);
    public static final PyInt ONE = of(1);

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

    // --- numeric slots ---------------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return Numeric.apply(op, this, other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        // The tower is closed: any number on either side is computed here, so nothing reflects back.
        return Numeric.apply(op, other, this);
    }

    @Override
    public PyObject unaryOp(UnaryOp op) {
        return Numeric.unary(op, this);
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return Numeric.compare(cmp, this, other);
    }

    /** Zero is the only false integer, and it is false on its value, not on a length it does not have. */
    @Override
    public PyObject truth() {
        return PyBool.of(value.signum() != 0);
    }

    /**
     * The attributes an {@code int} answers.
     *
     * {@code real}, {@code imag}, {@code numerator} and {@code denominator} are the four properties
     * that make an integer look like the rest of the tower to a generic numeric function; they are
     * attributes rather than methods, so they cannot live in {@link #METHODS}.
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "real", "numerator" -> this;
            case "imag" -> ZERO;
            case "denominator" -> ONE;
            default -> Members.find(this, name);
        };
    }

    private static final Members METHODS = Members.of(PyType.INT)
            .addNoArgument("bit_length", self -> PyInt.of(
                    ((PyInt) self).value.abs().bitLength()))
            .addNoArgument("conjugate", self -> self)
            .addNoArgument("as_integer_ratio", self -> PyTuple.of((PyInt) self, ONE));

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
