package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

public final class PyFloat extends PyObject {

    /** The most digits a double can need; 17 always round-trips, so the search always terminates. */
    private static final int MAX_SIGNIFICANT_DIGITS = 17;

    private final double value;

    private PyFloat(double value) {
        super(PyType.FLOAT);
        this.value = value;
    }

    public static PyFloat of(double value) {
        return new PyFloat(value);
    }

    public double getValue() {
        return value;
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

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return Numeric.compare(cmp, this, other);
    }

    /** Every float is true except zero, and NaN is true — the value is never tested for its length. */
    @Override
    public PyObject truth() {
        return PyBool.of(value != 0.0);
    }

    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "real" -> this;
            case "imag" -> PyFloat.of(0.0);
            default -> METHODS.bind(this, name);
        };
    }

    private static final Members METHODS = Members.of(PyType.FLOAT)
            .addNoArgument("is_integer", self -> PyBool.of(
                    isIntegral(((PyFloat) self).value)))
            .addNoArgument("conjugate", self -> self);

    /**
     * Whether the value is an integer, which {@code float.is_integer()} answers and the arithmetic
     * below needs too: a negative base raised to a non-integer leaves the reals.
     */
    private static boolean isIntegral(double v) {
        return !Double.isInfinite(v) && !Double.isNaN(v) && v == Math.floor(v);
    }

    @Override
    public long hash() {
        return PyHash.floatHash(value);
    }

    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyFloat f) {
            return Double.compare(value, f.value) == 0;
        }
        if (other instanceof PyInt || other instanceof PyBool || other instanceof PyComplex) {
            return other.eq(this);
        }
        return false;
    }

    @Override
    public String repr() {
        return format(value);
    }

    @Override
    public String str() {
        return repr();
    }

    static String format(double v) {
        return format(v, true);
    }

    /**
     * CPython's {@code format_float_short} in its {@code 'r'} mode. {@code addDotZero} is that
     * function's {@code Py_DTSF_ADD_DOT_0} flag: a float's own repr sets it, the parts of a
     * complex's repr do not, which is why {@code repr(1.0)} is {@code 1.0} while
     * {@code repr(1+0j)} is {@code (1+0j)}.
     */
    static String format(double v, boolean addDotZero) {
        if (Double.isNaN(v)) {
            // The sign of a NaN is not printed, by dtoa or by Java.
            return "nan";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        if (v == 0.0) {
            String zero = addDotZero ? "0.0" : "0";
            return Double.compare(v, -0.0) == 0 ? "-" + zero : zero;
        }
        int[] point = new int[1];
        String digits = shortestDigits(Math.abs(v), point);
        String body = layOut(digits, point[0]);
        if (addDotZero && body.indexOf('.') < 0 && body.indexOf('e') < 0) {
            body += ".0";
        }
        return (v < 0 ? "-" : "") + body;
    }

    /**
     * The fewest significant digits that still read back as {@code abs}, and where the decimal
     * point sits relative to them.
     *
     * {@code Double.toString} also produces a round-tripping form, but it does not always produce
     * the shortest one, and which digits CPython prints is observable: it rounds the exact binary
     * value to {@code n} digits and takes the first {@code n} that reads back unchanged, so
     * {@code repr(5e-324)} is {@code 5e-324} where Java says {@code 4.9E-324}.
     */
    private static String shortestDigits(double abs, int[] pointOut) {
        BigDecimal exact = new BigDecimal(abs);
        for (int significant = 1; significant <= MAX_SIGNIFICANT_DIGITS; significant++) {
            BigDecimal candidate = exact.round(new MathContext(significant, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() == abs) {
                BigInteger unscaled = candidate.unscaledValue();
                // The first length that round-trips never ends in a zero: dropping a trailing zero
                // gives the same value, and that shorter form would have matched earlier.
                pointOut[0] = candidate.precision() - candidate.scale();
                return unscaled.toString();
            }
        }
        throw new AssertionError("no decimal representation of " + abs + " round-trips");
    }

    /**
     * CPython's layout rule: exponential form once the decimal point would fall at or below
     * {@code -4} or above {@code 16} in the digit string, and a two-digit-or-wider exponent.
     */
    private static String layOut(String digits, int point) {
        int length = digits.length();
        if (point <= -4 || point > 16) {
            String head = length > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
            int exponent = point - 1;
            int magnitude = Math.abs(exponent);
            String padded = magnitude < 10 ? "0" + magnitude : Integer.toString(magnitude);
            return head + (exponent < 0 ? "e-" : "e+") + padded;
        }
        if (point <= 0) {
            return "0." + "0".repeat(-point) + digits;
        }
        if (point >= length) {
            return digits + "0".repeat(point - length);
        }
        return digits.substring(0, point) + "." + digits.substring(point);
    }
}
