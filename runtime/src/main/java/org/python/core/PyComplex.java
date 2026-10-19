package org.python.core;

public final class PyComplex extends PyObject {

    private final double real;
    private final double imaginary;

    private PyComplex(double real, double imaginary) {
        super(PyType.COMPLEX);
        this.real = real;
        this.imaginary = imaginary;
    }

    public static PyComplex of(double real, double imaginary) {
        return new PyComplex(real, imaginary);
    }

    public double real() {
        return real;
    }

    public double imaginary() {
        return imaginary;
    }

    PyFloat realPart() {
        return PyFloat.of(real);
    }

    boolean imaginaryIsZero() {
        return imaginary == 0.0;
    }

    // --- numeric slots ---------------------------------------------------------------------

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

    /**
     * A complex is false only when both parts are zero.
     *
     * Ordering is not here because it is not a slot of {@code complex} in CPython either: the base
     * class declines an ordering test, and {@link Slots} turns two declinations into the error that
     * names both types.
     */
    @Override
    public PyObject truth() {
        return PyBool.of(real != 0.0 || imaginary != 0.0);
    }

    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "real" -> realPart();
            case "imag" -> PyFloat.of(imaginary);
            default -> Members.find(this, name);
        };
    }

    private static final Members METHODS = Members.of(PyType.COMPLEX)
            .addNoArgument("conjugate", self -> {
                PyComplex c = (PyComplex) self;
                return PyComplex.of(c.real(), -c.imaginary());
            });

    @Override
    public long hash() {
        return PyHash.complexHash(real, imaginary);
    }

    /**
     * A complex is the most general of the numeric types, so it answers comparisons with float and
     * int itself rather than passing them back — {@code float} and {@code int} both defer here, and
     * a complex that deferred too would recurse.
     */
    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyComplex c) {
            return real == c.real && imaginary == c.imaginary;
        }
        if (other instanceof PyFloat f) {
            return imaginary == 0.0 && real == f.getValue();
        }
        if (other instanceof PyInt || other instanceof PyBool) {
            return imaginary == 0.0 && realPart().eq(other);
        }
        return false;
    }

    /**
     * CPython's {@code complex_repr}: a positive real zero hides the real part and the parentheses
     * with it, every part is a float repr without the {@code .0}, and the imaginary part always
     * carries a sign.
     */
    @Override
    public String repr() {
        if (real == 0.0 && Double.compare(real, -0.0) != 0) {
            return PyFloat.format(imaginary, false) + "j";
        }
        return "(" + PyFloat.format(real, false) + signedImaginary() + "j)";
    }

    private String signedImaginary() {
        String part = PyFloat.format(imaginary, false);
        return part.startsWith("-") ? part : "+" + part;
    }
}
