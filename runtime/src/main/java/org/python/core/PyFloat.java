package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteOrder;

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

    /**
     * {@code float}'s members, read the way every other value type reads them.
     *
     * <p>{@code real} and {@code imag} are not here: they are {@code getset_descriptor}s filed in the
     * type's own dictionary, which the ordinary read reaches through {@link TypeDict} — and a read of
     * {@code real} answers with the value it was asked for, so {@code x.real is x} holds while {@code
     * x.imag is x} never does, because the imaginary part is a fresh zero.
     */
    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    private static final Members METHODS = Members.of(PyType.FLOAT)
            .addClassNamed("from_number", PyFloat::fromNumber)
            .addNoArgument("conjugate", Numeric::floatFloat)
            .addNoArgument("__trunc__", self -> Numeric.longFromDouble(((PyFloat) self).value))
            .addNoArgument(
                    "__floor__",
                    self -> Numeric.longFromDouble(Math.floor(((PyFloat) self).value)))
            .addNoArgument(
                    "__ceil__",
                    self -> Numeric.longFromDouble(Math.ceil(((PyFloat) self).value)))
            .add("__round__", PyFloat::roundTo)
            .addNoArgument("as_integer_ratio", PyFloat::integerRatio)
            .addClassNamed("fromhex", PyFloat::fromHex)
            .addNoArgument("hex", self -> PyStr.of(hexOf(((PyFloat) self).value)))
            .addNoArgument("is_integer", self -> PyBool.of(isIntegral(((PyFloat) self).value)))
            .addNoArgument(
                    "__getnewargs__", self -> PyTuple.of(PyFloat.of(((PyFloat) self).value)))
            .addClassNamed("__getformat__", PyFloat::getFormat)
            .addOne("__format__", PyFloat::formatted)
            // The thirteen sentences `float_methods` files (Objects/floatobject.c:1780), every one of them
            // read out of `Objects/clinic/floatobject.c.h` through the `*_METHODDEF` macro the table names —
            // float writes no row by hand. All thirteen therefore carry a table, which is why thirteen rows
            // here close thirteen tables where int's fourteen closed only ten. `__getnewargs__` is the
            // silence of a table with nothing written under it, `from_number`, `fromhex` and `__getformat__`
            // are the three `$type` rows whose `METH_CLASS` flag makes `float.__dict__` file a
            // `classmethod_descriptor` while the other ten are plain `method_descriptor`s, `__round__` is
            // the one default, and the three texts that quote arrive from C literals written as `\'`.
            .document(
                    "from_number",
                    "from_number($type, number, /)\n"
                            + "--\n"
                            + "\n"
                            + "Convert real number to a floating-point number.")
            .document(
                    "conjugate",
                    "conjugate($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return self, the complex conjugate of any float.")
            .document(
                    "__trunc__",
                    "__trunc__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the Integral closest to x between 0 and x.")
            .document(
                    "__floor__",
                    "__floor__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the floor as an Integral.")
            .document(
                    "__ceil__",
                    "__ceil__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the ceiling as an Integral.")
            .document(
                    "__round__",
                    "__round__($self, ndigits=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the Integral closest to x, rounding half toward even.\n"
                            + "\n"
                            + "When an argument is passed, work like built-in round(x, ndigits).")
            .document(
                    "as_integer_ratio",
                    "as_integer_ratio($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a pair of integers, whose ratio is exactly equal to the original float.\n"
                            + "\n"
                            + "The ratio is in lowest terms and has a positive denominator.  Raise\n"
                            + "OverflowError on infinities and a ValueError on NaNs.\n"
                            + "\n"
                            + ">>> (10.0).as_integer_ratio()\n"
                            + "(10, 1)\n"
                            + ">>> (0.0).as_integer_ratio()\n"
                            + "(0, 1)\n"
                            + ">>> (-.25).as_integer_ratio()\n"
                            + "(-1, 4)")
            .document(
                    "fromhex",
                    "fromhex($type, string, /)\n"
                            + "--\n"
                            + "\n"
                            + "Create a floating-point number from a hexadecimal string.\n"
                            + "\n"
                            + ">>> float.fromhex('0x1.ffffp10')\n"
                            + "2047.984375\n"
                            + ">>> float.fromhex('-0x1p-1074')\n"
                            + "-5e-324")
            .document(
                    "hex",
                    "hex($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a hexadecimal representation of a floating-point number.\n"
                            + "\n"
                            + ">>> (-0.1).hex()\n"
                            + "'-0x1.999999999999ap-4'\n"
                            + ">>> 3.14159.hex()\n"
                            + "'0x1.921f9f01b866ep+1'")
            .document(
                    "is_integer",
                    "is_integer($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the float is an integer.")
            .document(
                    "__getnewargs__",
                    "__getnewargs__($self, /)\n"
                            + "--\n"
                            + "\n")
            .document(
                    "__getformat__",
                    "__getformat__($type, typestr, /)\n"
                            + "--\n"
                            + "\n"
                            + "You probably don't want to use this function.\n"
                            + "\n"
                            + "  typestr\n"
                            + "    Must be 'double' or 'float'.\n"
                            + "\n"
                            + "It exists mainly to be used in Python's test suite.\n"
                            + "\n"
                            + "This function returns whichever of 'unknown', 'IEEE, big-endian' or\n"
                            + "'IEEE, little-endian' best describes the format of floating-point\n"
                            + "numbers used by the C type named by typestr.")
            .document(
                    "__format__",
                    "__format__($self, format_spec, /)\n"
                            + "--\n"
                            + "\n"
                            + "Formats the float according to format_spec.");

    /**
     * Whether the value is an integer, which {@code float.is_integer()} answers and the arithmetic
     * below needs too: a negative base raised to a non-integer leaves the reals.
     */
    private static boolean isIntegral(double v) {
        return !Double.isInfinite(v) && !Double.isNaN(v) && v == Math.floor(v);
    }

    // --- the members -------------------------------------------------------------------------------

    /**
     * {@code float_from_number}: the double a real number carries, named as a float.
     *
     * <p>An exact float answers with itself — this is the fifth name for {@link Numeric#floatFloat} — and
     * everything else goes through {@code PyFloat_AsDouble}, which is <em>stricter</em> than {@code
     * float()} about a literal: {@code float.from_number('1.5')} is refused although {@code float('1.5')}
     * is three and a half, because reading text is not one of the conversions a real number offers.
     */
    private static PyObject fromNumber(PyObject type, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("float.from_number()", args, kwNames);
        if (args[0] instanceof PyFloat value) {
            return value;
        }
        Double real = Numeric.doubleOf(args[0]);
        if (real == null) {
            throw Slots.typeError(
                    "must be real number, not %s", args[0].getType().filedName());
        }
        return PyFloat.of(real);
    }

    /**
     * {@code float___round___impl}: the nearest integer, or the value rounded to a decimal place.
     *
     * <p>The two forms share nothing but the receiver. With no place named the answer is an {@code int},
     * and the rounding is C's {@code round} — half away from zero — corrected to half-even by asking again
     * for half the value; with a place named the answer is a float and the rounding is decimal, so
     * {@code round(2.675, 2)} is {@code 2.67} because the value is not two thousand six hundred seventy
     * five thousandths but a hair less. The place is read as a machine integer and a wider number is
     * clipped rather than refused, which is what lets {@code round(x, 10**400)} be {@code x}: past
     * {@value #NDIGITS_MAX} every value rounds to itself and below {@value #NDIGITS_MIN} every value
     * rounds to a zero that keeps the sign it had.
     */
    private static PyObject roundTo(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("float.__round__()", kwNames);
        Arguments.between("__round__", args, kwNames, 0, 1);
        double x = ((PyFloat) self).value;
        if (args.length == 0 || args[0] == PyNone.INSTANCE) {
            return Numeric.longFromDouble(nearestInteger(x));
        }
        long ndigits = clippedToMachineInteger(args[0]);
        if (!Double.isFinite(x)) {
            return PyFloat.of(x);
        }
        if (ndigits > NDIGITS_MAX) {
            return PyFloat.of(x);
        }
        if (ndigits < NDIGITS_MIN) {
            return PyFloat.of(0.0 * x);
        }
        double rounded =
                new BigDecimal(x).setScale((int) ndigits, RoundingMode.HALF_EVEN).doubleValue();
        if (Double.isInfinite(rounded)) {
            throw Slots.overflowError("rounded value too large to represent");
        }
        // A rounding that leaves nothing still leaves the sign, which the decimal form has no way to say.
        return PyFloat.of(rounded == 0.0 ? Math.copySign(0.0, x) : rounded);
    }

    /** {@code C}'s {@code DBL_MIN_EXP}, {@code DBL_MANT_DIG} and {@code DBL_MAX_EXP} for a double. */
    private static final int DBL_MIN_EXP = -1021;

    private static final int DBL_MANT_DIG = 53;

    private static final int DBL_MAX_EXP = 1024;

    /** {@code LONG_MIN/2} and {@code LONG_MAX/2}, the two bounds the parser sets on its own exponent. */
    private static final long LONG_MIN_HALF = Long.MIN_VALUE / 2;

    private static final long LONG_MAX_HALF = Long.MAX_VALUE / 2;

    private static final BigInteger LONG_MIN_VALUE = BigInteger.valueOf(Long.MIN_VALUE);

    private static final BigInteger LONG_MAX_VALUE = BigInteger.valueOf(Long.MAX_VALUE);

    /** The widest coefficient {@code float_fromhex} will read at all, which no literal comes near. */
    private static final long MAX_HEX_DIGITS =
            Math.min(DBL_MIN_EXP - DBL_MANT_DIG - LONG_MIN_HALF, LONG_MAX_HALF + 1 - DBL_MAX_EXP) / 4;

    /** {@code NDIGITS_MAX} and {@code NDIGITS_MIN}: past them a place asks for the value or for nothing. */
    private static final int NDIGITS_MAX = (int) ((DBL_MANT_DIG - DBL_MIN_EXP) * 0.30103);

    private static final int NDIGITS_MIN = -(int) ((DBL_MAX_EXP + 1) * 0.30103);

    /**
     * {@code float_fromhex}: the one argument, read as CPython's hex float grammar.
     *
     * <p>A {@code classmethod} of {@code METH_CLASS | METH_O}, so a name given by keyword is refused before
     * the argument is looked at and a value that is not text is refused without a word about what it was
     * asked to convert.
     */
    private static PyObject fromHex(PyObject type, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("float.fromhex()", args, kwNames);
        if (!(args[0] instanceof PyStr text)) {
            // The parser asks `PyUnicode_AsUTF8AndSize` for a buffer, and the refusal that comes back names
            // no hexadecimal anything: it is the one CPython keeps for a built-in handed the wrong kind.
            throw Slots.typeError("bad argument type for built-in operation");
        }
        return PyFloat.of(parseHexFloat(text.getValue()));
    }

    /**
     * {@code float___getformat___impl}: the sentence this platform's reals answer, for either of two names.
     *
     * <p>The argument picks which of the two C types the question is about and is not part of the answer:
     * both {@code 'double'} and {@code 'float'} name the same layout here, and the layout is decided once,
     * at startup, by the machine. It is the only member of {@code float} whose answer says something about
     * the hardware rather than about the value, which is why it exists at all — {@code floatobject.c} says
     * plainly that you probably don't want to use it, and that it is there for the test suite.
     *
     * <p>A value that is not text at all is a different refusal from a word that is not one of the two, and
     * the two are worded differently: one complains about the kind, the other about the position.
     */
    private static PyObject getFormat(PyObject type, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("float.__getformat__()", args, kwNames);
        if (args[0] instanceof PyStr text) {
            String name = text.getValue();
            if (name.equals("double") || name.equals("float")) {
                return PyStr.of(PLATFORM_FORMAT);
            }
            throw Slots.valueError("__getformat__() argument 1 must be 'double' or 'float'");
        }
        throw Slots.typeError(
                "__getformat__() argument must be str, not %s", Slots.argumentName(args[0]));
    }

    /**
     * {@code double_format} and {@code float_format}, which CPython works out at startup and this runtime
     * reads off the same byte order: a Java {@code double} is IEEE 754 binary64 by definition, so the only
     * question left is the order the eight bytes are written in.
     */
    private static final String PLATFORM_FORMAT =
            ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                    ? "IEEE, little-endian"
                    : "IEEE, big-endian";

    /** {@code float.__format__(x, spec)}: the spec arrives as text and nothing else. */
    private static PyObject formatted(PyObject self, PyObject spec) {
        if (!(spec instanceof PyStr text)) {
            throw Slots.typeError(
                    "__format__() argument must be str, not %s", Slots.argumentName(spec));
        }
        return PyStr.of(Formats.apply(self, text.getValue()));
    }

    /** {@code round(x)}, then the half-even correction CPython applies when the value is exactly halfway. */
    private static double nearestInteger(double x) {
        double rounded = halfAwayFromZero(x);
        if (Math.abs(x - rounded) == 0.5) {
            // Halfway between two integers, so the even one — and half the value is not itself halfway,
            // which is why asking C's round again is the whole of the correction rather than a recursion.
            return 2.0 * halfAwayFromZero(x / 2.0);
        }
        return rounded;
    }

    /** C's {@code round}: the nearest integer, with a halfway value sent away from zero. */
    private static double halfAwayFromZero(double x) {
        double magnitude = Math.abs(x);
        double below = Math.floor(magnitude);
        return Math.copySign(magnitude - below >= 0.5 ? below + 1.0 : below, x);
    }

    /** {@code PyNumber_AsSsize_t} with no exception to raise, which is to say the clipping form. */
    private static long clippedToMachineInteger(PyObject value) {
        BigInteger exact = Numbers.asInteger(value);
        if (exact == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().name());
        }
        if (exact.compareTo(LONG_MAX_VALUE) > 0) {
            return Long.MAX_VALUE;
        }
        return exact.compareTo(LONG_MIN_VALUE) < 0 ? Long.MIN_VALUE : exact.longValue();
    }

    /** {@code float_as_integer_ratio}: the exact pair of integers the value is, in lowest terms. */
    private static PyObject integerRatio(PyObject self) {
        double v = ((PyFloat) self).value;
        if (Double.isInfinite(v)) {
            throw Slots.overflowError("cannot convert Infinity to integer ratio");
        }
        if (Double.isNaN(v)) {
            throw Slots.valueError("cannot convert NaN to integer ratio");
        }
        if (v == 0.0) {
            return PyTuple.of(PyInt.ZERO, PyInt.ONE);
        }
        long bits = Double.doubleToRawLongBits(v);
        long rawExponent = (bits >>> 52) & 0x7FFL;
        long mantissa = bits & ((1L << 52) - 1);
        // The value is the significand times a power of two, and the significand of a normal number
        // carries the hidden bit that the word itself leaves out.
        BigInteger significand =
                BigInteger.valueOf(rawExponent == 0 ? mantissa : (1L << 52) | mantissa);
        int exponent = rawExponent == 0 ? -1074 : (int) rawExponent - 1023 - 52;
        int trailing = significand.getLowestSetBit();
        significand = significand.shiftRight(trailing);
        exponent += trailing;
        BigInteger numerator = v < 0.0 ? significand.negate() : significand;
        return exponent > 0
                ? PyTuple.of(PyInt.of(numerator.shiftLeft(exponent)), PyInt.ONE)
                : PyTuple.of(
                        PyInt.of(numerator), PyInt.of(BigInteger.ONE.shiftLeft(-exponent)));
    }

    /** {@code float_hex}: the value's own bits, spelled as a sign, a hex digit, thirteen more and a two. */
    private static String hexOf(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return format(v);
        }
        if (v == 0.0) {
            return (Double.compare(v, -0.0) == 0 ? "-" : "") + "0x0.0p+0";
        }
        long bits = Double.doubleToRawLongBits(v);
        long rawExponent = (bits >>> 52) & 0x7FFL;
        long mantissa = bits & ((1L << 52) - 1);
        long significand = rawExponent == 0 ? mantissa : (1L << 52) | mantissa;
        int exponent = rawExponent == 0 ? -1022 : (int) rawExponent - 1023;
        String fraction = Long.toHexString(significand & ((1L << 52) - 1));
        return (v < 0.0 ? "-0x" : "0x")
                + Long.toHexString(significand >>> 52)
                + "."
                + "0".repeat(13 - fraction.length())
                + fraction
                + "p"
                + (exponent < 0 ? "-" : "+")
                + Math.abs(exponent);
    }

    /**
     * {@code float_fromhex}: the value a hexadecimal literal names, rounded the way the literal's own
     * bits would be.
     *
     * <p>The grammar is CPython's and not C's: leading and trailing ASCII space only, an optional {@code
     * 0x} that may be left out, a coefficient with or without a point, and a binary exponent behind a
     * {@code p} that may also be left out — {@code '1.5'} is one and thirty-two sixty-fourths, not one
     * and a half. The digits are read from the least significant end so that the ones a double cannot
     * hold are the ones it rounds, and a coefficient of more than four digits per bit of the machine's
     * longest run is refused as too long rather than silently dropped.
     */
    private static double parseHexFloat(String text) {
        int length = text.length();
        int at = 0;
        while (isAsciiSpace(characterAt(text, at))) {
            at++;
        }
        int[] past = new int[1];
        double value = parseNamedDouble(text, at, past);
        boolean negate = false;
        if (past[0] != at) {
            // An infinity or a NaN carries its own sign, which is why the outer `negate` stays where it is.
            at = past[0];
        } else {
            if (characterAt(text, at) == '-') {
                at++;
                negate = true;
            } else if (characterAt(text, at) == '+') {
                at++;
            }
            int start = at;
            if (characterAt(text, at) == '0') {
                at++;
                if (characterAt(text, at) != 'x' && characterAt(text, at) != 'X') {
                    at = start;
                } else {
                    at++;
                }
            }
            int coefficientStart = at;
            while (hexDigit(characterAt(text, at)) >= 0) {
                at++;
            }
            int point = at;
            int coefficientEnd;
            if (characterAt(text, at) == '.') {
                at++;
                while (hexDigit(characterAt(text, at)) >= 0) {
                    at++;
                }
                coefficientEnd = at - 1;
            } else {
                coefficientEnd = at;
            }
            long ndigits = coefficientEnd - coefficientStart;
            long fdigits = coefficientEnd - point;
            if (ndigits == 0) {
                throw parseError();
            }
            if (ndigits > MAX_HEX_DIGITS) {
                throw Slots.valueError("hexadecimal string too long to convert");
            }
            long exponent = 0;
            if (characterAt(text, at) == 'p' || characterAt(text, at) == 'P') {
                at++;
                int exponentStart = at;
                if (characterAt(text, at) == '-' || characterAt(text, at) == '+') {
                    at++;
                }
                if (!isDecimal(characterAt(text, at))) {
                    throw parseError();
                }
                at++;
                while (isDecimal(characterAt(text, at))) {
                    at++;
                }
                exponent = decimalExponent(text.substring(exponentStart, at));
            }
            value = roundedCoefficient(
                    text, coefficientEnd, fdigits, ndigits, exponent);
        }
        while (isAsciiSpace(characterAt(text, at))) {
            at++;
        }
        if (at != length) {
            throw parseError();
        }
        return negate ? -value : value;
    }

    /**
     * {@code float_fromhex}'s arithmetic half: the coefficient, placed and rounded to sixty-four bits.
     *
     * <p>{@code exp} is the power of two the least significant hex digit sits on before the point is
     * accounted for, and {@code ndigits} has already lost the zeros in front of the number. Every refusal
     * and every zero here is the C function's own: a coefficient of nothing, an exponent so far down that
     * the value is zero, and an exponent so far up that no double reaches it.
     */
    private static double roundedCoefficient(
            String text, int coefficientEnd, long fdigits, long ndigits, long exp) {
        while (ndigits > 0 && hexDigitAt(text, coefficientEnd, fdigits, ndigits - 1) == 0) {
            ndigits--;
        }
        if (ndigits == 0 || exp < LONG_MIN_HALF) {
            return 0.0;
        }
        if (exp > LONG_MAX_HALF) {
            throw tooLarge();
        }
        exp -= 4 * fdigits;
        long topExp = exp + 4 * (ndigits - 1);
        for (int digit = hexDigitAt(text, coefficientEnd, fdigits, ndigits - 1);
                digit != 0;
                digit /= 2) {
            topExp++;
        }
        if (topExp < DBL_MIN_EXP - DBL_MANT_DIG) {
            return 0.0;
        }
        if (topExp > DBL_MAX_EXP) {
            throw tooLarge();
        }
        long lsb = Math.max(topExp, DBL_MIN_EXP) - DBL_MANT_DIG;
        double x = 0.0;
        if (exp >= lsb) {
            for (long i = ndigits - 1; i >= 0; i--) {
                x = 16.0 * x + hexDigitAt(text, coefficientEnd, fdigits, i);
            }
            return Math.scalb(x, (int) exp);
        }
        int halfEps = 1 << (int) ((lsb - exp - 1) % 4);
        long keyDigit = (lsb - exp - 1) / 4;
        for (long i = ndigits - 1; i > keyDigit; i--) {
            x = 16.0 * x + hexDigitAt(text, coefficientEnd, fdigits, i);
        }
        int digit = hexDigitAt(text, coefficientEnd, fdigits, keyDigit);
        x = 16.0 * x + (digit & (16 - 2 * halfEps));
        if ((digit & halfEps) != 0) {
            boolean roundUp =
                    (digit & (3 * halfEps - 1)) != 0
                            || (halfEps == 8
                                    && keyDigit + 1 < ndigits
                                    && (hexDigitAt(text, coefficientEnd, fdigits, keyDigit + 1) & 1)
                                            != 0);
            for (long i = keyDigit - 1; !roundUp && i >= 0; i--) {
                roundUp = hexDigitAt(text, coefficientEnd, fdigits, i) != 0;
            }
            if (roundUp) {
                x += 2 * halfEps;
                if (topExp == DBL_MAX_EXP && x == Math.scalb((double) (2 * halfEps), DBL_MANT_DIG)) {
                    throw tooLarge();
                }
            }
        }
        return Math.scalb(x, (int) (exp + 4 * keyDigit));
    }

    /** {@code _Py_parse_inf_or_nan}: the word, and how much of the string the word ran to. */
    private static double parseNamedDouble(String text, int at, int[] pastOut) {
        int position = at;
        boolean negate = false;
        if (characterAt(text, position) == '-') {
            negate = true;
            position++;
        } else if (characterAt(text, position) == '+') {
            position++;
        }
        double value;
        if (matchesIgnoreCase(text, position, "inf")) {
            position += 3;
            if (matchesIgnoreCase(text, position, "inity")) {
                position += 5;
            }
            value = negate ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        } else if (matchesIgnoreCase(text, position, "nan")) {
            position += 3;
            value = negate ? -Double.NaN : Double.NaN;
        } else {
            position = at;
            value = -1.0;
        }
        pastOut[0] = position;
        return value;
    }

    /** {@code HEX_DIGIT}: the digit {@code j} places from the least significant end of the coefficient. */
    private static int hexDigitAt(String text, int coefficientEnd, long fdigits, long j) {
        int position = (int) (j < fdigits ? coefficientEnd - j : coefficientEnd - 1 - j);
        return hexDigit(text.charAt(position));
    }

    /** {@code strtol}: the digits, read as a decimal number and saturated rather than wrapped. */
    private static long decimalExponent(String digits) {
        int at = 0;
        boolean negative = false;
        if (digits.charAt(at) == '-' || digits.charAt(at) == '+') {
            negative = digits.charAt(at) == '-';
            at++;
        }
        BigInteger exact = new BigInteger(digits.substring(at));
        if (negative) {
            exact = exact.negate();
        }
        return exact.compareTo(LONG_MAX_VALUE) > 0
                ? Long.MAX_VALUE
                : exact.compareTo(LONG_MIN_VALUE) < 0 ? Long.MIN_VALUE : exact.longValue();
    }

    /** The character {@code C}'s {@code *s} hands back one past the end of the buffer: its terminator. */
    private static char characterAt(String text, int index) {
        return index < text.length() ? text.charAt(index) : '\0';
    }

    private static boolean matchesIgnoreCase(String text, int at, String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = characterAt(text, at + i);
            if (Character.toLowerCase(c) != word.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int hexDigit(char c) {
        if ('0' <= c && c <= '9') {
            return c - '0';
        }
        if ('a' <= c && c <= 'f') {
            return c - 'a' + 10;
        }
        if ('A' <= c && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean isDecimal(char c) {
        return '0' <= c && c <= '9';
    }

    /** {@code Py_ISSPACE} over a byte, which is the whole of the whitespace rule here. */
    private static boolean isAsciiSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    /** The refusal {@code float_fromhex} makes of a string its grammar does not read. */
    private static PyRaise parseError() {
        return Slots.valueError("invalid hexadecimal floating-point string");
    }

    /** The refusal {@code float_fromhex} makes of an exponent no double can hold. */
    private static PyRaise tooLarge() {
        return Slots.overflowError("hexadecimal value too large to represent as a float");
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
     *
     * That is dtoa's mode zero, which is also what {@code Formats} asks for an omitted type code.
     */
    static String shortestDigits(double abs, int[] pointOut) {
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
