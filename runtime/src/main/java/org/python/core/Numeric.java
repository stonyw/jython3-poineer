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

    /** The widest shift this runtime will try to allocate, which is half a machine address space. */
    private static final BigInteger MAX_SHIFT = BigInteger.valueOf(Integer.MAX_VALUE / 2L);

    /**
     * A shift count, which Python requires to be an integer no larger than memory can hold.
     *
     * <p>The two refusals are made at two different widths. A count too wide for a machine word is refused
     * by the read itself, in the {@code OverflowError} words {@code long_lshift} reserves for it; a count
     * that fits a word but not an allocation is the case CPython only discovers when {@code malloc} fails,
     * and here the size is known before anything is allocated, so the same {@code MemoryError} is raised on
     * purpose rather than by running out.
     */
    private static int shiftCount(BigInteger by, BinaryOp op) {
        if (by.signum() < 0) {
            throw Slots.valueError("negative shift count");
        }
        if (by.bitLength() > 63) {
            throw Slots.overflowError("too many digits in integer");
        }
        if (by.compareTo(MAX_SHIFT) > 0) {
            throw Slots.memoryError();
        }
        return by.intValue();
    }

    /**
     * {@code CHECK_BINOP} and then {@code long_<em>op</em>}, which is what an {@code int} files.
     *
     * <p>The gate is {@code PyLong_Check} on both operands and nothing looser: {@code int.__add__(5, 2.5)}
     * declines although {@code 5 + 2.5} answers, because the operator promotes inside {@code nb_add} while
     * the slot on its own reports only what it could not do. A {@code bool} passes the gate — {@code
     * int.__lshift__(5, True)} is {@code 10} — and a float, a complex or anything else does not.
     */
    static PyObject integerSlot(BinaryOp op, PyObject a, PyObject b) {
        BigInteger x = Numbers.asInteger(a);
        BigInteger y = Numbers.asInteger(b);
        if (x == null || y == null) {
            return PyNotImplemented.INSTANCE;
        }
        return intOp(op, x, y);
    }

    /** {@code long_divmod}: the pair {@code nb_divmod} answers with, which no {@link BinaryOp} computes. */
    static PyObject integerDivmod(PyObject a, PyObject b) {
        BigInteger x = Numbers.asInteger(a);
        BigInteger y = Numbers.asInteger(b);
        if (x == null || y == null) {
            return PyNotImplemented.INSTANCE;
        }
        BigInteger[] both = divmod(x, y);
        return PyTuple.of(PyInt.of(both[0]), PyInt.of(both[1]));
    }

    /**
     * {@code long_richcompare}: the same gate as the arithmetic slots, over integers only.
     *
     * <p>{@code int.__eq__(5, 5.0)} declines where {@code 5 == 5.0} is true, because the float's answer
     * comes from the comparison the operator reaches after swapping the operands, not from this slot.
     */
    static PyObject integerCompare(CompareOp cmp, PyObject a, PyObject b) {
        BigInteger x = Numbers.asInteger(a);
        BigInteger y = Numbers.asInteger(b);
        if (x == null || y == null) {
            return PyNotImplemented.INSTANCE;
        }
        return PyObject.ordered(cmp, x.compareTo(y));
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
     * {@code _PyLong_DivmodNear}'s other half: the multiple of {@code unit} nearest to {@code value}.
     *
     * <p>A tie goes to the even quotient. Java's division truncates toward zero, so rounding away from it
     * reaches the neighbour one step further out: {@code -1350} goes to {@code -1400}, and {@code 15} and
     * {@code 25} both go to {@code 20}.
     */
    static BigInteger nearMultiple(BigInteger value, BigInteger unit) {
        BigInteger[] parts = value.divideAndRemainder(unit);
        BigInteger twice = parts[1].abs().shiftLeft(1);
        int compare = twice.compareTo(unit);
        if (compare < 0 || (compare == 0 && !parts[0].testBit(0))) {
            return parts[0].multiply(unit);
        }
        BigInteger neighbour = value.signum() < 0 ? parts[0].subtract(ONE) : parts[0].add(ONE);
        return neighbour.multiply(unit);
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

    /** {@code float_methods}' four coercions and the whole of its number struct run on this. */
    private static final int DBL_MAX_EXP = 1024;

    /**
     * {@code CONVERT_TO_DOUBLE}: the double a float or an integer carries, and null for anything else.
     *
     * <p>A refusal and a failure are different things here. A complex, a string or a Java object makes every
     * slot of {@code floatobject.c} decline, which is what lets {@code 1.5 + 1j} be answered by the
     * complex's own slot; an integer with no double form is a number the slot can read and cannot name, so
     * the {@code OverflowError} comes out of the read itself — {@code float.__add__(1.5, 10**400)} refuses
     * where {@code float.__add__(1.5, 'x')} declines.
     */
    static Double doubleOf(PyObject value) {
        if (value instanceof PyFloat f) {
            return f.getValue();
        }
        BigInteger integer = Numbers.asInteger(value);
        return integer == null ? null : integerAsDouble(integer);
    }

    /** {@code PyLong_AsDouble}, whose one refusal is the number being too large for a double. */
    private static double integerAsDouble(BigInteger integer) {
        double asDouble = integer.doubleValue();
        if (Double.isInfinite(asDouble) && integer.signum() != 0) {
            throw Slots.overflowError("int too large to convert to float");
        }
        return asDouble;
    }

    /**
     * {@code float_add} and its eight siblings, over the two operands a call named.
     *
     * <p>Each is the same three lines — read both, decline if either is neither a float nor an integer, and
     * compute in doubles. The {@code __r…} half of every pair is the very same C function with the operands
     * swapped, so {@code float.__radd__(1.5, 2)} is the addition of {@code 2 + 1.5} rather than of anything
     * else, and it is the order in which the two refusals come out that the swap changes.
     */
    static PyObject floatSlot(BinaryOp op, PyObject left, PyObject right) {
        Double x = doubleOf(left);
        Double y = doubleOf(right);
        if (x == null || y == null) {
            return PyNotImplemented.INSTANCE;
        }
        return floatOp(op, x, y);
    }

    /** {@code float_divmod}: the pair, which no {@link BinaryOp} computes. */
    static PyObject floatDivmod(PyObject left, PyObject right) {
        Double x = doubleOf(left);
        Double y = doubleOf(right);
        if (x == null || y == null) {
            return PyNotImplemented.INSTANCE;
        }
        double[] both = divmodFloat(x, y);
        return PyTuple.of(PyFloat.of(both[0]), PyFloat.of(both[1]));
    }

    /**
     * {@code float_pow}: the ladder of {@link #floatPow}, behind the one rule its three-argument form adds.
     *
     * <p>The modulus is refused before either operand is read, so {@code float.__pow__(2.0, 3, 'x')}
     * complains about a third argument and not about the string, and {@code None} is the value that means
     * "no modulus given" — which is what lets the wrapper answer {@code __pow__(2, 3)} and {@code
     * __pow__(2, 3, None)} with the same eight.
     */
    static PyObject floatPower(PyObject base, PyObject exponent, PyObject modulus) {
        if (modulus != PyNone.INSTANCE) {
            throw Slots.typeError("pow() 3rd argument not allowed unless all arguments are integers");
        }
        return floatSlot(BinaryOp.POW, base, exponent);
    }

    /**
     * {@code float_float}: the coercion every float answers with itself.
     *
     * <p>Four names reach this one C function — {@code +x}, {@code float(x)}, {@code x.__float__()} and the
     * {@code real} read — and each hands back the very object it was given rather than a copy of it, which
     * is observable through {@code is}. The copy half of {@code float_float} is written for a value that is
     * a float <em>subclass</em>, and no class under this runtime builds one.
     */
    static PyObject floatFloat(PyObject value) {
        return value;
    }

    /**
     * {@code PyLong_FromDouble}: the part of a double before the point, as an integer.
     *
     * <p>{@code __trunc__}, {@code __floor__} and {@code __ceil__} all answer with this, and so does
     * {@code int(x)} for a float — the three that floor or ceil have already rounded the double before it
     * gets here. Truncation is toward zero, so {@code int(-1.5)} is {@code -1} and {@code -0.0} is the
     * integer zero rather than a negative one. The two refusals are the conversion's own and name no
     * caller: a number with no integer part at all is not a size.
     */
    static PyObject longFromDouble(double value) {
        if (Double.isNaN(value)) {
            throw Slots.valueError("cannot convert float NaN to integer");
        }
        if (Double.isInfinite(value)) {
            throw Slots.overflowError("cannot convert float infinity to integer");
        }
        return PyInt.of(new BigDecimal(value).toBigInteger());
    }

    /**
     * {@code float_richcompare}: an ordering between a float and one other value, exact in both directions.
     *
     * <p>Rounding the integer would be wrong ({@code 9007199254740992.0 == 2**53 + 1} is false, and as
     * doubles they are equal) and rounding the float would be wrong the other way, so the C function walks
     * four tests before it compares anything: unlike signs settle it on their own, an integer of more than
     * {@value #DBL_MAX_EXP} bits is larger than any finite float, an integer of 48 bits or fewer converts
     * exactly, and only a pair with the same number of bits before the point is compared as integers — at
     * which point a float with a fraction has its operator rewritten, because {@code 2.5 <= 3} and {@code
     * 2.5 < 3} are the same question.
     *
     * <p>An infinity and a NaN are the two exceptions: neither is compared with the integer at all, and both
     * are measured against zero instead, which is why {@code -inf < 10**400} is true and every ordering a
     * NaN is asked for is false while {@code !=} is true.
     */
    static PyObject floatCompare(CompareOp cmp, PyObject floatSide, PyObject other) {
        double i = ((PyFloat) floatSide).getValue();
        if (other instanceof PyFloat f) {
            return compareDoubles(cmp, i, f.getValue());
        }
        BigInteger w = Numbers.asInteger(other);
        if (w == null) {
            return PyNotImplemented.INSTANCE;
        }
        if (!Double.isFinite(i)) {
            return compareDoubles(cmp, i, 0.0);
        }
        int vsign = i == 0.0 ? 0 : i < 0.0 ? -1 : 1;
        int wsign = w.signum();
        if (vsign != wsign) {
            return compareDoubles(cmp, vsign, wsign);
        }
        int nbits = w.abs().bitLength();
        if (nbits > DBL_MAX_EXP) {
            return compareDoubles(cmp, vsign, 2 * wsign);
        }
        if (nbits <= 48) {
            return compareDoubles(cmp, i, integerAsDouble(w));
        }
        // The frexp exponent is the number of bits before the point; a subnormal's is over-stated here by
        // design, and can only be read as "smaller than any integer this branch has reached".
        int exponent = Math.getExponent(i) + 1;
        if (exponent < nbits) {
            return compareDoubles(cmp, 0.0, i);
        }
        if (exponent > nbits) {
            return compareDoubles(cmp, i, 0.0);
        }
        double intpart = Math.copySign(Math.floor(Math.abs(i)), i);
        if (i - intpart != 0.0) {
            // A float with a fraction is never the integer it sits inside, and the strict form of the
            // ordering says the same thing the rewritten one does.
            if (cmp == CompareOp.EQ) {
                return PyBool.FALSE;
            }
            if (cmp == CompareOp.NE) {
                return PyBool.TRUE;
            }
            cmp = switch (cmp) {
                case LT, LE -> vsign > 0 ? CompareOp.LT : CompareOp.LE;
                default -> vsign > 0 ? CompareOp.GE : CompareOp.GT;
            };
        }
        return PyObject.ordered(cmp, new BigDecimal(intpart).toBigInteger().compareTo(w));
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
                // `nb_positive` is `float_float`, which is the receiver rather than a copy of it — the same
                // object rule `float(x) is x` and `x.__float__() is x` answer by.
                case POSITIVE -> floatFloat(f);
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
            // long_abs copies, and a copy of what is already non-negative is the value it was handed;
            // the negative half is what long_neg builds anew. abs(True) is the int, not the bool.
            case ABSOLUTE ->
                    integer.signum() >= 0 && value instanceof PyInt i ? i : PyInt.of(integer.abs());
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
        // 2**53 + 1 does have a float neighbour worth getting right. The float's slot is what answers the
        // operator in either order, so an integer written on the left asks the reflected question —
        // `2 < 1.5` is `float.__gt__(1.5, 2)`, which is the same two tests the other way round.
        boolean integerOnLeft = left != null;
        return floatCompare(
                integerOnLeft ? cmp.reflected() : cmp,
                integerOnLeft ? b : a,
                integerOnLeft ? a : b);
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
        return integerAsDouble(Numbers.asInteger(value));
    }

    static PyComplex asComplex(PyObject value) {
        if (value instanceof PyComplex c) {
            return c;
        }
        return PyComplex.of(asDouble(value), 0.0);
    }
}
