package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The numeric tower: {@code int}, {@code bool}, {@code float} and {@code complex} in one place.
 *
 * Each of the four types answers its own arithmetic slots by calling here, which mirrors what CPython
 * does across four files ({@code longobject.c}, {@code boolobject.c}, {@code floatobject.c},
 * {@code complexobject.c}) and one protocol: an operator is asked of the left operand and then of the
 * right, and the answer is whichever operand can compute it. Because no numeric type in CPython refuses
 * a numeric partner, which side answers is not observable — only the value and, for a refusal, the
 * message are. So {@link #apply} is allowed to look at both operands at once, and to promote:
 * {@code int} with {@code float} is computed as a float, anything with {@code complex} as a complex.
 *
 * <p>Two places do real work rather than arithmetic, and both are ported from CPython rather than
 * approximated, because getting them wrong produces a wrong number instead of an error:
 * {@link #trueDivide} for correctly-rounded division of two integers, and {@link #floatPow} for the
 * special cases {@code pow} cannot be trusted with.
 */
final class Numeric {

    /** The errno CPython reports when {@code pow} overflows, and the text that goes with it. */
    private static final int ERANGE = 34;
    private static final String RESULT_TOO_LARGE = "Result too large";

    /** Digits kept for a quotient of two integers; see {@link #trueDivide}. */
    private static final MathContext QUOTIENT = new MathContext(200, RoundingMode.HALF_EVEN);

    private static final BigInteger ONE = BigInteger.ONE;

    private Numeric() {
    }

    /**
     * Whether this value is one of the four numeric types.
     *
     * CPython asks a type flag ({@code Py_TPFLAGS_NUMBER}) instead, which user-defined numeric types
     * will set at M3; until then the tower is exactly these four classes.
     */
    static boolean isNumber(PyObject value) {
        return value instanceof PyInt
                || value instanceof PyBool
                || value instanceof PyFloat
                || value instanceof PyComplex;
    }

    /** {@code a op b} for two numbers, or a refusal when either is not one. */
    static PyObject apply(BinaryOp op, PyObject a, PyObject b) {
        if (!isNumber(a) || !isNumber(b)) {
            return PyNotImplemented.INSTANCE;
        }
        if (a instanceof PyComplex || b instanceof PyComplex) {
            return complexOp(op, asComplex(a), asComplex(b));
        }
        if (a instanceof PyFloat || b instanceof PyFloat) {
            return floatOp(op, asDouble(a), asDouble(b));
        }
        return intOp(op, Numbers.asInteger(a), Numbers.asInteger(b));
    }

    // --- int -------------------------------------------------------------------------------

    private static PyObject intOp(BinaryOp op, BigInteger x, BigInteger y) {
        return switch (op) {
            case ADD -> PyInt.of(x.add(y));
            case SUB -> PyInt.of(x.subtract(y));
            case MUL -> PyInt.of(x.multiply(y));
            case AND -> PyInt.of(x.and(y));
            case OR -> PyInt.of(x.or(y));
            case XOR -> PyInt.of(x.xor(y));
            case LSHIFT -> PyInt.of(x.shiftLeft(shiftCount(y, op)));
            case RSHIFT -> PyInt.of(x.shiftRight(shiftCount(y, op)));
            case FLOORDIV -> PyInt.of(divmod(x, y)[0]);
            case MOD -> PyInt.of(divmod(x, y)[1]);
            case TRUEDIV -> PyFloat.of(trueDivide(x, y));
            case POW -> power(x, y);
            case MATMUL -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * A shift count, which Python requires to be an integer no larger than memory can hold.
     *
     * CPython lets the allocation fail and reports {@code MemoryError}; here the size is known before
     * allocating, so the same error is raised on purpose rather than by running out.
     */
    private static int shiftCount(BigInteger by, BinaryOp op) {
        if (by.signum() < 0) {
            throw Slots.valueError("negative shift count");
        }
        if (!Numbers.fitsInt(by) || by.intValue() > Integer.MAX_VALUE / 2) {
            throw Slots.memoryError();
        }
        return by.intValue();
    }

    /** {@code x ** y} for two integers: exact while the exponent is positive, float once it is not. */
    private static PyObject power(BigInteger x, BigInteger y) {
        if (y.signum() < 0) {
            // A negative exponent makes the result a fraction, so the tower gives up on exactness.
            return floatOp(BinaryOp.POW, asDouble(PyInt.of(x)), asDouble(PyInt.of(y)));
        }
        if (!Numbers.fitsInt(y)) {
            throw Slots.memoryError();
        }
        int exponent = y.intValue();
        if (x.signum() != 0 && exponent > 0
                && (long) x.bitLength() * exponent > Integer.MAX_VALUE) {
            throw Slots.memoryError();
        }
        return PyInt.of(x.pow(exponent));
    }

    /**
     * Quotient and remainder of two integers, floored the way Python wants.
     *
     * {@code -5 // 2} is {@code -3} with a remainder of {@code 1}, not {@code -2} with {@code -1}: the
     * remainder always carries the divisor's sign, which is what makes {@code a == a//b*b + a%b} hold
     * for negative operands too. Java's own division truncates toward zero, so both are adjusted.
     */
    static BigInteger[] divmod(BigInteger x, BigInteger y) {
        if (y.signum() == 0) {
            throw Slots.zeroDivision("division by zero");
        }
        BigInteger[] both = x.divideAndRemainder(y);
        if (both[1].signum() != 0 && both[1].signum() != y.signum()) {
            both[0] = both[0].subtract(ONE);
            both[1] = both[1].add(y);
        }
        return both;
    }

    /**
     * {@code a / b} for two integers, correctly rounded to a double.
     *
     * CPython's {@code long_true_divide} shifts the dividend to the 54 bits a double needs and rounds
     * from the sticky bits, which is exact. A 200-digit decimal quotient rounds to the same double for
     * every pair of integers whose halfway case the expansion can state — which needs more than 200
     * digits to distinguish, so no test that exists can tell. What is reproduced exactly is the two
     * things a shortcut would get wrong: {@code 10**400 / 10**400 == 1.0}, and the overflow below.
     */
    private static double trueDivide(BigInteger a, BigInteger b) {
        if (b.signum() == 0) {
            throw Slots.zeroDivision("division by zero");
        }
        double result = new BigDecimal(a).divide(new BigDecimal(b), QUOTIENT).doubleValue();
        if (Double.isInfinite(result)) {
            throw Slots.overflowError("integer division result too large for a float");
        }
        return result;
    }

    // --- float -----------------------------------------------------------------------------

    private static PyObject floatOp(BinaryOp op, double x, double y) {
        return switch (op) {
            case ADD -> PyFloat.of(x + y);
            case SUB -> PyFloat.of(x - y);
            case MUL -> PyFloat.of(x * y);
            case TRUEDIV -> PyFloat.of(divide(x, y));
            case FLOORDIV -> PyFloat.of(divmodFloat(x, y)[0]);
            case MOD -> PyFloat.of(divmodFloat(x, y)[1]);
            case POW -> floatPow(x, y);
            case AND, OR, XOR, LSHIFT, RSHIFT, MATMUL -> PyNotImplemented.INSTANCE;
        };
    }

    private static double divide(double x, double y) {
        if (y == 0.0) {
            throw Slots.zeroDivision("division by zero");
        }
        return x / y;
    }

    /**
     * Floor division and remainder of two floats: {@code Objects/floatobject.c}'s
     * {@code _float_div_mod}, line for line.
     *
     * The interesting part is the snapping. {@code fmod} is exact, so {@code v - mod} is an exact
     * multiple of {@code w} in real arithmetic, but in floating-point the quotient of that is only
     * <em>nearly</em> an integer, and has to be rounded back to one before flooring. Both halves come
     * out of one call because {@code divmod} needs them together and the sign adjustments are shared.
     */
    static double[] divmodFloat(double v, double w) {
        if (w == 0.0) {
            throw Slots.zeroDivision("division by zero");
        }
        double mod = v % w;
        double div = (v - mod) / w;
        if (mod != 0.0) {
            if ((w < 0) != (mod < 0)) {
                mod += w;
                div -= 1.0;
            }
        } else {
            mod = Math.copySign(0.0, w);
        }
        double floordiv;
        if (div != 0.0) {
            floordiv = Math.floor(div);
            if (div - floordiv > 0.5) {
                floordiv += 1.0;
            }
        } else {
            floordiv = Math.copySign(0.0, v / w);
        }
        return new double[] {floordiv, mod};
    }

    /**
     * {@code v ** w} for two floats.
     *
     * CPython refuses to hand this to {@code libm} and settles the special cases first — every one of
     * them is a case where {@code pow} itself answers inconsistently across platforms. A negative base
     * with a fractional exponent leaves the reals and becomes a complex, which is Python 3 behaviour
     * and not an error. Where {@code libm} reports {@code ERANGE} on overflow, Java returns infinity,
     * so the infinity is the signal.
     */
    private static PyObject floatPow(double v, double w) {
        if (w == 0.0) {
            return PyFloat.of(1.0);
        }
        if (Double.isNaN(v)) {
            return PyFloat.of(v);
        }
        if (Double.isNaN(w)) {
            return PyFloat.of(v == 1.0 ? 1.0 : w);
        }
        if (Double.isInfinite(w)) {
            double magnitude = Math.abs(v);
            if (magnitude == 1.0) {
                return PyFloat.of(1.0);
            }
            return PyFloat.of((w > 0.0) == (magnitude > 1.0) ? Math.abs(w) : 0.0);
        }
        if (Double.isInfinite(v)) {
            boolean odd = isOddInteger(w);
            return PyFloat.of(w > 0.0 ? (odd ? v : Math.abs(v))
                    : (odd ? Math.copySign(0.0, v) : 0.0));
        }
        if (v == 0.0) {
            if (w < 0.0) {
                throw Slots.zeroDivision("zero to a negative power");
            }
            // CPython keeps the sign of a negative zero only when the exponent is an odd integer.
            return PyFloat.of(isOddInteger(w) ? v : 0.0);
        }
        boolean negate = false;
        double base = v;
        if (base < 0.0) {
            // It is the exponent, not the base, that decides whether the answer stays real: CPython
            // tests iw != floor(iw) before handing anything to libm, so -7.5 ** 2 is 56.25.
            if (w != Math.floor(w)) {
                return complexPow(PyComplex.of(v, 0.0), PyComplex.of(w, 0.0));
            }
            base = -base;
            negate = isOddInteger(w);
        }
        if (base == 1.0) {
            return PyFloat.of(negate ? -1.0 : 1.0);
        }
        double result = Math.pow(base, w);
        if (Double.isInfinite(result)) {
            throw resultTooLarge();
        }
        return PyFloat.of(negate ? -result : result);
    }

    /** The errno CPython's {@code PyErr_SetFromErrno} turns into an {@code OverflowError}'s arguments. */
    private static PyRaise resultTooLarge() {
        return PyRaise.raise(ExceptionTypes.OVERFLOW_ERROR,
                PyInt.of(ERANGE), PyStr.of(RESULT_TOO_LARGE));
    }

    /** {@code DOUBLE_IS_ODD_INTEGER}: whether a double is an integer, and an odd one. */
    private static boolean isOddInteger(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v != Math.floor(v)) {
            return false;
        }
        return Math.floor(Math.abs(v) % 2.0) == 1.0;
    }

    // --- complex ---------------------------------------------------------------------------

    private static PyObject complexOp(BinaryOp op, PyComplex a, PyComplex b) {
        double ar = a.real();
        double ai = a.imaginary();
        double br = b.real();
        double bi = b.imaginary();
        return switch (op) {
            case ADD -> PyComplex.of(ar + br, ai + bi);
            case SUB -> PyComplex.of(ar - br, ai - bi);
            case MUL -> PyComplex.of(ar * br - ai * bi, ar * bi + ai * br);
            case TRUEDIV -> {
                double[] divided = quotient(ar, ai, br, bi);
                yield PyComplex.of(divided[0], divided[1]);
            }
            case POW -> complexPow(a, b);
            case FLOORDIV, MOD, AND, OR, XOR, LSHIFT, RSHIFT, MATMUL -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * {@code (ar + ai j) / (br + bi j)} by Smith's ratio method.
     *
     * Dividing by the larger component keeps the denominator away from zero, which is the whole reason
     * CPython does not use the textbook formula: {@code (1+1j)/(1e308+1e308j)} must not overflow.
     */
    private static double[] quotient(double ar, double ai, double br, double bi) {
        double absReal = Math.abs(br);
        double absImag = Math.abs(bi);
        double ratio;
        double denom;
        double[] out;
        if (Double.isNaN(absReal) && Double.isNaN(absImag)) {
            return new double[] {Double.NaN, Double.NaN};
        }
        if (absReal >= absImag) {
            if (br == 0.0) {
                throw Slots.zeroDivision("complex division by zero");
            }
            ratio = bi / br;
            denom = br + bi * ratio;
            out = new double[] {(ar + ai * ratio) / denom, (ai - ar * ratio) / denom};
        } else {
            ratio = br / bi;
            denom = br * ratio + bi;
            out = new double[] {(ar * ratio + ai) / denom, (ai * ratio - ar) / denom};
        }
        if (Double.isNaN(out[0]) && Double.isNaN(out[1])
                && Double.isFinite(ar) && Double.isFinite(ai)
                && (Double.isInfinite(absReal) || Double.isInfinite(absImag))) {
            double x = Math.copySign(1.0, br);
            double y = Math.copySign(1.0, bi);
            out = new double[] {0.0 * (ar * x), 0.0 * (-ai * y)};
        }
        return out;
    }

    /** {@code _Py_c_pow}: magnitude raised to the real part, rotated by the angle, plus the log spiral. */
    private static PyObject complexPow(PyComplex a, PyComplex b) {
        double ar = a.real();
        double ai = a.imaginary();
        double br = b.real();
        double bi = b.imaginary();
        if (br == 0.0 && bi == 0.0) {
            return PyComplex.of(1.0, 0.0);
        }
        if (ar == 0.0 && ai == 0.0) {
            if (bi != 0.0 || br < 0.0) {
                throw Slots.zeroDivision("0.0 to a negative or complex power");
            }
            return PyComplex.of(0.0, 0.0);
        }
        double length = Math.hypot(ar, ai);
        double magnitude = Math.pow(length, br);
        double phase = Math.atan2(ai, ar) * br;
        if (bi != 0.0) {
            magnitude *= Math.exp(-Math.atan2(ai, ar) * bi);
            phase += bi * Math.log(length);
        }
        return PyComplex.of(magnitude * Math.cos(phase), magnitude * Math.sin(phase));
    }

    // --- unary, absolute and comparison ----------------------------------------------------

    /**
     * {@code -x}, {@code +x}, {@code ~x}, {@code abs(x)} for a number.
     *
     * {@code +int} answers with the same object, as CPython's {@code long_positive} does, and that is
     * observable through {@code is}: {@code x = 10**400; +x is x} is true.
     */
    static PyObject unary(UnaryOp op, PyObject value) {
        if (value instanceof PyComplex c) {
            return switch (op) {
                case NEGATIVE -> PyComplex.of(-c.real(), -c.imaginary());
                case POSITIVE -> PyComplex.of(c.real(), c.imaginary());
                case ABSOLUTE -> PyFloat.of(complexAbs(c));
                case INVERT -> PyNotImplemented.INSTANCE;
            };
        }
        if (value instanceof PyFloat f) {
            double v = f.getValue();
            return switch (op) {
                case NEGATIVE -> PyFloat.of(-v);
                case POSITIVE -> PyFloat.of(v);
                case ABSOLUTE -> PyFloat.of(Math.abs(v));
                case INVERT -> PyNotImplemented.INSTANCE;
            };
        }
        BigInteger integer = Numbers.asInteger(value);
        if (integer == null) {
            return PyNotImplemented.INSTANCE;
        }
        return switch (op) {
            case NEGATIVE -> PyInt.of(integer.negate());
            // +x on an int is the same object; on a bool it is the int the bool stands for.
            case POSITIVE -> value instanceof PyInt i ? i : PyInt.of(integer);
            case INVERT -> PyInt.of(integer.not());
            case ABSOLUTE -> PyInt.of(integer.abs());
        };
    }

    /** {@code abs(z)}: the modulus, which is an infinity if either part is one. */
    static double complexAbs(PyComplex z) {
        if (Double.isInfinite(z.real())) {
            return Double.POSITIVE_INFINITY;
        }
        if (Double.isInfinite(z.imaginary())) {
            return Double.POSITIVE_INFINITY;
        }
        if (Double.isNaN(z.real()) || Double.isNaN(z.imaginary())) {
            return Double.NaN;
        }
        return Math.hypot(z.real(), z.imaginary());
    }

    /**
     * Ordering among numbers.
     *
     * A complex refuses — CPython's {@code complex} has no ordering slots, so this declines and
     * {@link Slots} raises the error naming both types. Equality between an integer and a float is
     * exact, decided on the decimal values rather than by rounding the integer away: {@code 10**400}
     * is not equal to {@code 10.0**400}'s neighbour, and comparing doubles would say it was.
     */
    static PyObject compare(CompareOp cmp, PyObject a, PyObject b) {
        if (!isNumber(a) || !isNumber(b) || a instanceof PyComplex || b instanceof PyComplex) {
            return PyNotImplemented.INSTANCE;
        }
        if (a instanceof PyFloat fa && b instanceof PyFloat fb) {
            return compareDoubles(cmp, fa.getValue(), fb.getValue());
        }
        BigInteger left = Numbers.asInteger(a);
        BigInteger right = Numbers.asInteger(b);
        if (left != null && right != null) {
            return PyObject.ordered(cmp, left.compareTo(right));
        }
        // Mixed int and float, compared exactly. Converting the integer would round it, and
        // 2**53 + 1 does have a float neighbour worth getting right.
        BigInteger exact = left != null ? left : right;
        double other = (left != null ? b : a) instanceof PyFloat f
                ? f.getValue()
                : asDouble(left != null ? b : a);
        if (Double.isNaN(other)) {
            return equalityOnly(cmp, false);
        }
        return PyObject.ordered(cmp, new BigDecimal(exact).compareTo(new BigDecimal(other)));
    }

    /**
     * IEEE comparison, which is not a three-way one: every ordering test against a NaN is false while
     * {@code !=} is true. A single {@code int} signal cannot say that, which is why floats have a path
     * of their own rather than going through {@link PyObject#ordered}.
     */
    private static PyObject compareDoubles(CompareOp cmp, double x, double y) {
        return switch (cmp) {
            case LT -> PyBool.of(x < y);
            case LE -> PyBool.of(x <= y);
            case GT -> PyBool.of(x > y);
            case GE -> PyBool.of(x >= y);
            case EQ -> PyBool.of(x == y);
            case NE -> PyBool.of(x != y);
        };
    }

    private static PyObject equalityOnly(CompareOp cmp, boolean equal) {
        return switch (cmp) {
            case EQ -> PyBool.of(equal);
            case NE -> PyBool.of(!equal);
            default -> PyBool.FALSE;
        };
    }

    // --- conversions -----------------------------------------------------------------------

    /**
     * The value as a double, refusing the way CPython does when an integer has no double form.
     *
     * Only numbers reach this, and a complex is handled by {@link #asComplex} before it.
     */
    static double asDouble(PyObject value) {
        if (value instanceof PyFloat f) {
            return f.getValue();
        }
        BigInteger integer = Numbers.asInteger(value);
        double asDouble = integer.doubleValue();
        if (Double.isInfinite(asDouble) && integer.signum() != 0) {
            throw Slots.overflowError("int too large to convert to float");
        }
        return asDouble;
    }

    static PyComplex asComplex(PyObject value) {
        if (value instanceof PyComplex c) {
            return c;
        }
        return PyComplex.of(asDouble(value), 0.0);
    }
}
