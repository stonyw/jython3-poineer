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

    // --- numeric slots ---------------------------------------------------------------------
    //
    // A bool computes as the integer it stands for, which is why every one of these answers is an
    // {@code int} rather than another bool: {@code True + True} is {@code 2}.

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return Numeric.apply(op, this, other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
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

    /** {@code bool(False)} is false, which the fallthrough to {@code __len__} would not say. */
    @Override
    public PyObject truth() {
        return this;
    }

    /**
     * The attributes a {@code bool} answers.
     *
     * The four numeric properties are worth reading off the integer this bool stands for, and everything
     * else — {@code bit_length} and the rest — is found by the walk of {@code bool}'s lookup order, which
     * reaches {@code int}'s table. See {@link #adaptedFor}, which is what lets an {@code int} body run on
     * a receiver that is a {@code bool} in Java.
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "real", "numerator" -> asInt();
            case "imag" -> PyInt.ZERO;
            case "denominator" -> PyInt.ONE;
            default -> Members.find(this, name);
        };
    }

    /** An {@code int} method asked of a {@code bool} runs on the integer the bool is. */
    @Override
    PyObject adaptedFor(PyType declaring) {
        return declaring == PyType.INT ? asInt() : this;
    }

    private PyInt asInt() {
        return PyInt.of(value ? 1 : 0);
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
