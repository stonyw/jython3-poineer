package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.List;

/**
 * The {@code math} module: the C library's floating-point functions over Python numbers.
 *
 * <p>Every function here reads its arguments exactly as CPython's {@code math_1} and {@code math_2}
 * converters do: a value with no {@code __float__} is refused with {@code must be real number, not X},
 * and the C library's two errno answers become the two Python exceptions — {@code ValueError: math domain
 * error} for a value outside the function's domain and {@code OverflowError: math range error} for a
 * result too large to represent. The integer functions ({@code factorial}, {@code isqrt}, {@code comb},
 * {@code perm}, {@code gcd}, {@code lcm}) do their arithmetic in {@link BigInteger} and never round.
 *
 * <p>{@code erf} is absent. Java's standard library has no implementation of it, and nothing this runtime
 * can import asks for one; {@code erfc} is here because {@code statistics} imports it at module level.
 * {@code gamma}, {@code lgamma} and {@code erfc} are not libm calls but approximations — Lanczos for the
 * first two and the regularized incomplete gamma for the third — so a value may differ from the reference
 * in the last few ulp, the deviation {@code PLAN.md} names.
 */
final class MathModule {

    private static final String DOC =
            "This module provides access to the mathematical functions\n"
            + "defined by the C standard.";

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    private MathModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("math");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "math");
        module.set("pi", PyFloat.of(Math.PI));
        module.set("e", PyFloat.of(Math.E));
        module.set("tau", PyFloat.of(Math.PI * 2));
        module.set("inf", PyFloat.of(Double.POSITIVE_INFINITY));
        module.set("nan", PyFloat.of(Double.NaN));

        module.set("sqrt", unary("sqrt", x -> {
            if (x < 0) {
                throw Slots.valueError("expected a nonnegative input, got %s",
                        PyFloat.of(x).repr());
            }
            return Math.sqrt(x);
        }));
        module.set("exp", unary("exp", Math::exp));
        module.set("exp2", unary("exp2", x -> Math.pow(2, x)));
        module.set("expm1", unary("expm1", Math::expm1));
        module.set("log", logarithmlike());
        module.set("log2", unary("log2", x -> positiveLog(x, Math.log(x) / Math.log(2))));
        module.set("log10", log10());
        module.set("log1p", unary("log1p", x -> {
            if (x <= -1) {
                throw Slots.valueError("expected argument value > -1, got %s",
                        PyFloat.of(x).repr());
            }
            return Math.log1p(x);
        }));
        module.set("sin", unary("sin", Math::sin));
        module.set("cos", unary("cos", Math::cos));
        module.set("tan", unary("tan", Math::tan));
        module.set("asin", unary("asin", x -> boundedUnit(x, Math.asin(x))));
        module.set("acos", unary("acos", x -> boundedUnit(x, Math.acos(x))));
        module.set("atan", unary("atan", Math::atan));
        module.set("sinh", unary("sinh", Math::sinh));
        module.set("cosh", unary("cosh", Math::cosh));
        module.set("tanh", unary("tanh", Math::tanh));
        module.set("asinh", unary("asinh", x -> Math.log(x + Math.sqrt(x * x + 1))));
        module.set("acosh", unary("acosh", x -> {
            if (x < 1) {
                throw Slots.valueError("expected argument value not less than 1, got %s",
                        PyFloat.of(x).repr());
            }
            return Math.log(x + Math.sqrt(x * x - 1));
        }));
        module.set("atanh", unary("atanh", x -> {
            if (x <= -1 || x >= 1) {
                throw Slots.valueError("expected a number between -1 and 1, got %s",
                        PyFloat.of(x).repr());
            }
            return 0.5 * Math.log((1 + x) / (1 - x));
        }));
        module.set("degrees", unary("degrees", Math::toDegrees));
        module.set("radians", unary("radians", Math::toRadians));
        module.set("fabs", unary("fabs", Math::abs));
        module.set("cbrt", unary("cbrt", Math::cbrt));
        module.set("floor", integral("floor", Math::floor));
        module.set("ceil", integral("ceil", Math::ceil));
        module.set("trunc", integral("trunc", x -> x < 0 ? Math.ceil(x) : Math.floor(x)));

        module.set("isinf", predicate("isinf", Double::isInfinite));
        module.set("isnan", predicate("isnan", Double::isNaN));
        module.set("isfinite", predicate("isfinite", Double::isFinite));

        module.set("atan2", binary("atan2", Math::atan2));
        module.set("copysign", binary("copysign", Math::copySign));
        module.set("fmod", binary("fmod", (x, y) -> {
            if (y == 0 || (Double.isInfinite(x) && Double.isFinite(y))) {
                throw domain();
            }
            double answer = x % y;
            if (Double.isNaN(answer) && !Double.isNaN(x) && !Double.isNaN(y)) {
                throw domain();
            }
            return answer;
        }));
        module.set("remainder", binary("remainder", (x, y) -> {
            if (y == 0 || (Double.isInfinite(x) && Double.isFinite(y))) {
                throw domain();
            }
            double answer = Math.IEEEremainder(x, y);
            if (Double.isNaN(answer) && !Double.isNaN(x) && !Double.isNaN(y)) {
                throw domain();
            }
            return answer;
        }));
        module.set("pow", binary("pow", MathModule::power));
        module.set("nextafter", taking("nextafter", 2, (x, y) -> Math.nextAfter(x, y)));
        module.set("fma", new PyNativeFunction("fma", (args, kwNames) -> {
            Arguments.between("fma", args, kwNames, 3, 3);
            return PyFloat.of(Math.fma(real(args[0]), real(args[1]), real(args[2])));
        }));
        module.set("ldexp", new PyNativeFunction("ldexp", (args, kwNames) -> {
            Arguments.between("ldexp", args, kwNames, 2, 2);
            double x = real(args[0]);
            int exponent = Numbers.asIndex(args[1]).intValueExact();
            double answer = Math.scalb(x, exponent);
            if (Double.isInfinite(answer) && Double.isFinite(x)) {
                throw Slots.overflowError("math range error");
            }
            return PyFloat.of(answer);
        }));
        module.set("hypot", new PyNativeFunction("hypot", (args, kwNames) -> {
            refuseKeywords("hypot", kwNames);
            double sum = 0;
            for (PyObject arg : args) {
                double value = real(arg);
                sum = Math.hypot(sum, value);
            }
            return PyFloat.of(sum);
        }));
        module.set("dist", new PyNativeFunction("dist", (args, kwNames) -> {
            Arguments.between("dist", args, kwNames, 2, 2);
            List<PyObject> p = drain(args[0]);
            List<PyObject> q = drain(args[1]);
            if (p.size() != q.size()) {
                throw Slots.valueError("both points must have the same number of dimensions");
            }
            double sum = 0;
            for (int i = 0; i < p.size(); i++) {
                double difference = real(p.get(i)) - real(q.get(i));
                sum = Math.hypot(sum, difference);
            }
            return PyFloat.of(sum);
        }));

        module.set("frexp", unaryPair("frexp", MathModule::frexp));
        module.set("modf", unaryPair("modf", MathModule::modf));
        module.set("isclose", isclose());
        module.set("ulp", unary("ulp", MathModule::ulp));
        module.set("gamma", unary("gamma", MathModule::gamma));
        module.set("lgamma", unary("lgamma", MathModule::logGamma));
        module.set("erfc", unary("erfc", MathModule::complementaryError));

        module.set("factorial", new PyNativeFunction("factorial", (args, kwNames) -> {
            Arguments.exactlyOne("math.factorial()", args, kwNames);
            BigInteger n = Numbers.asIndex(args[0]);
            if (n.signum() < 0) {
                throw Slots.valueError("factorial() not defined for negative values");
            }
            BigInteger answer = ONE;
            for (BigInteger i = BigInteger.valueOf(2); i.compareTo(n) <= 0; i = i.add(ONE)) {
                answer = answer.multiply(i);
            }
            return PyInt.of(answer);
        }));
        module.set("isqrt", new PyNativeFunction("isqrt", (args, kwNames) -> {
            Arguments.exactlyOne("math.isqrt()", args, kwNames);
            BigInteger n = Numbers.asIndex(args[0]);
            if (n.signum() < 0) {
                throw Slots.valueError("isqrt() argument must be nonnegative");
            }
            return PyInt.of(n.sqrt());
        }));
        module.set("comb", new PyNativeFunction("comb", (args, kwNames) -> {
            Arguments.between("comb", args, kwNames, 2, 2);
            BigInteger n = Numbers.asIndex(args[0]);
            BigInteger k = Numbers.asIndex(args[1]);
            nonNegative("n", n);
            nonNegative("k", k);
            return PyInt.of(combinations(n, k));
        }));
        module.set("perm", new PyNativeFunction("perm", (args, kwNames) -> {
            Arguments.between("perm", args, kwNames, 1, 2);
            BigInteger n = Numbers.asIndex(args[0]);
            BigInteger k = args.length > 1 ? Numbers.asIndex(args[1]) : n;
            nonNegative("n", n);
            nonNegative("k", k);
            return PyInt.of(permutations(n, k));
        }));
        module.set("gcd", new PyNativeFunction("gcd", (args, kwNames) -> {
            refuseKeywords("gcd", kwNames);
            BigInteger answer = ZERO;
            for (PyObject arg : args) {
                answer = answer.gcd(Numbers.asIndex(arg));
            }
            return PyInt.of(answer);
        }));
        module.set("lcm", new PyNativeFunction("lcm", (args, kwNames) -> {
            refuseKeywords("lcm", kwNames);
            BigInteger answer = ONE;
            for (PyObject arg : args) {
                BigInteger value = Numbers.asIndex(arg).abs();
                if (value.signum() == 0) {
                    return PyInt.of(0);
                }
                answer = answer.divide(answer.gcd(value)).multiply(value);
            }
            return PyInt.of(answer);
        }));
        module.set("prod", prod());
        module.set("fsum", fsum());
        module.set("sumprod", sumprod());
        return module;
    }

    // --- the Lanczos approximation -----------------------------------------------------------------

    /** {@code log(sqrt(pi))}, the exact {@code lgamma(0.5)} the {@code erfc} expansions divide by. */
    private static final double LOG_SQRT_PI = 0.5723649429247001;

    /** The nine coefficients of the g=7, n=9 Lanczos sum. */
    private static final double[] LANCZOS = {
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
    };

    /** {@code gamma(x)}, through the Lanczos sum because libm's {@code tgamma} is not reachable. */
    private static double gamma(double x) {
        if (Double.isNaN(x)) {
            return x;
        }
        if (x == 0 || (x < 0 && x == Math.floor(x))) {
            throw Slots.valueError("expected a noninteger or positive integer, got %s",
                    PyFloat.of(x).repr());
        }
        if (Double.isInfinite(x)) {
            return x;
        }
        if (x < 0.5) {
            return Math.PI / (Math.sin(Math.PI * x) * gamma(1 - x));
        }
        double answer = Math.exp(logGamma(x));
        if (Double.isInfinite(answer)) {
            throw Slots.overflowError("math range error");
        }
        return answer;
    }

    /** {@code lgamma(x)}: the log of the absolute value, finite where gamma is not. */
    private static double logGamma(double x) {
        if (Double.isNaN(x)) {
            return x;
        }
        if (Double.isInfinite(x)) {
            return Double.POSITIVE_INFINITY;
        }
        if (x == 0 || (x < 0 && x == Math.floor(x))) {
            throw Slots.valueError("expected a noninteger or positive integer, got %s",
                    PyFloat.of(x).repr());
        }
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
        }
        x -= 1;
        double sum = LANCZOS[0];
        double t = x + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) {
            sum += LANCZOS[i] / (x + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(sum);
    }

    /**
     * {@code erfc(x)}: the tail of the error function, through the regularized incomplete gamma.
     *
     * <p>{@code erf(x)} is {@code sign(x) * P(1/2, x²)} and {@code erfc} is its complement, so the tail
     * {@code Q(1/2, x²)} is computed directly and the sign folded in — subtracting {@code P} from one
     * would lose every digit of a small {@code erfc}. The two expansions switch at {@code x² = 1.5}, where
     * the series below and the continued fraction above both converge in a few dozen terms.
     */
    private static double complementaryError(double x) {
        if (Double.isNaN(x)) {
            return x;
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? 0.0 : 2.0;
        }
        double z = x * x;
        double tail = z < 1.5 ? 1.0 - gammaSeries(0.5, z) : gammaContinuedFraction(0.5, z);
        return x < 0 ? 2.0 - tail : tail;
    }

    /** {@code P(a, x)} by its power series, for {@code x} below {@code a + 1}. */
    private static double gammaSeries(double a, double x) {
        if (x <= 0) {
            return 0.0;
        }
        double ap = a;
        double del = 1.0 / a;
        double sum = del;
        for (int n = 0; n < 500; n++) {
            ap += 1;
            del *= x / ap;
            sum += del;
            if (Math.abs(del) < Math.abs(sum) * 1e-16) {
                break;
            }
        }
        return sum * prefactor(a, x);
    }

    /** {@code Q(a, x)} by Lentz's continued fraction, for {@code x} at or above {@code a + 1}. */
    private static double gammaContinuedFraction(double a, double x) {
        final double tiny = 1e-300;
        double b = x + 1 - a;
        double c = 1 / tiny;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < 500; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = b + an / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-15) {
                break;
            }
        }
        return prefactor(a, x) * h;
    }

    /**
     * {@code x**a * e**-x / Gamma(a)}, the factor both incomplete-gamma expansions share.
     *
     * <p>{@code erfc} only ever asks for {@code a = 1/2}, where {@code Gamma(1/2)} is exactly
     * {@code sqrt(pi)} — so the {@code log(sqrt(pi))} constant is written down rather than reached through
     * the Lanczos sum, whose one-ulp error in {@code lgamma(0.5)} would otherwise show up in the last
     * digit of every answer.
     */
    private static double prefactor(double a, double x) {
        return Math.exp(-x + a * Math.log(x) - LOG_SQRT_PI);
    }

    // --- conversion and the two errno answers ------------------------------------------------------

    /**
     * {@code PyFloat_AsDouble}: a real number, or the refusal CPython's converter makes.
     *
     * <p>{@link Numbers#floatOf} is that converter, {@code __float__} included, which is how
     * {@code math.sqrt(Decimal("2"))} and a {@code Fraction} are read. {@link Numeric#doubleOf} would
     * accept only the built-in numbers and refuse both.
     */
    private static double real(PyObject value) {
        Double number = Numbers.floatOf(value);
        if (number == null) {
            throw Slots.typeError("must be real number, not %s", value.getType().name());
        }
        return number;
    }

    private static PyRaise domain() {
        return Slots.valueError("math domain error");
    }

    /** The C library's results after the fact: a NaN is a domain error, an infinity an overflow. */
    private static double errno(String name, double argument, double answer) {
        if (Double.isNaN(answer) && !Double.isNaN(argument)) {
            throw domain();
        }
        if (Double.isInfinite(answer) && Double.isFinite(argument)) {
            throw Slots.overflowError("math range error");
        }
        return answer;
    }

    private static double positiveLog(double x, double answer) {
        if (x <= 0) {
            throw Slots.valueError("expected a positive input");
        }
        return answer;
    }

    /**
     * {@code math.log10}: an integer is measured without becoming a float first.
     *
     * <p>{@code math.log10(10**1000)} is 1000.0 in the reference, while converting that integer to a
     * double overflows long before the logarithm is asked for. CPython's {@code loghelper} takes the
     * integer apart with {@code _PyLong_Frexp} instead — {@code n = m * 2**e} with {@code 0.5 <= m < 1}
     * — and {@code log10(m) + e*log10(2)} carries the answer without the detour.
     */
    private static PyNativeFunction log10() {
        return new PyNativeFunction("log10", (args, kwNames) -> {
            Arguments.exactlyOne("math.log10()", args, kwNames);
            if (args[0] instanceof PyInt integer) {
                BigInteger n = integer.getValue();
                if (n.signum() <= 0) {
                    throw Slots.valueError("expected a positive input");
                }
                if (n.bitLength() <= 1023) {
                    return PyFloat.of(Math.log10(n.doubleValue()));
                }
                int bits = n.bitLength();
                double mantissa =
                        new BigDecimal(n.shiftRight(bits - 53)).doubleValue() / Math.pow(2.0, 53);
                return PyFloat.of(Math.log10(mantissa) + bits * Math.log10(2.0));
            }
            double x = real(args[0]);
            return PyFloat.of(errno("log10", x, positiveLog(x, Math.log10(x))));
        });
    }

    private static double boundedUnit(double x, double answer) {
        if (x < -1 || x > 1) {
            throw Slots.valueError("expected a number in range from -1 up to 1, got %s",
                    PyFloat.of(x).repr());
        }
        return answer;
    }

    private static void refuseKeywords(String name, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s() takes no keyword arguments", name);
        }
    }

    // --- shapes ------------------------------------------------------------------------------------

    private static PyNativeFunction unary(
            String name, java.util.function.DoubleUnaryOperator operation) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.exactlyOne("math." + name + "()", args, kwNames);
            double x = real(args[0]);
            return PyFloat.of(errno(name, x, operation.applyAsDouble(x)));
        });
    }

    /**
     * {@code math.floor}/{@code ceil}/{@code trunc}: the same three names CPython's wrappers ask of a
     * value that is not a float.
     *
     * <p>CPython calls {@code __floor__} and its two siblings before it reaches for a double, which is
     * how {@code math.floor(Decimal('1.7'))} answers an {@code int} without rounding through a float.
     * The built-in numbers declare none of these names to {@link Specials#invoke}, so they still take
     * the conversion path below; an integer is its own floor and truncation, as in the reference.
     */
    private static PyNativeFunction integral(
            String name, java.util.function.DoubleUnaryOperator operation) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.exactlyOne("math." + name + "()", args, kwNames);
            PyObject value = args[0];
            if (value instanceof PyInt) {
                return value;
            }
            if (!(value instanceof PyFloat) && !(value instanceof PyBool)) {
                PyObject answer = Specials.invoke(value, "__" + name + "__");
                if (!Slots.declined(answer)) {
                    return answer;
                }
            }
            double x = real(value);
            if (Double.isNaN(x)) {
                throw Slots.valueError("cannot convert float NaN to integer");
            }
            if (Double.isInfinite(x)) {
                throw Slots.overflowError("cannot convert float infinity to integer");
            }
            return PyInt.of(BigInteger.valueOf((long) operation.applyAsDouble(x)));
        });
    }

    private static PyNativeFunction predicate(
            String name, java.util.function.DoublePredicate test) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.exactlyOne("math." + name + "()", args, kwNames);
            return PyBool.of(test.test(real(args[0])));
        });
    }

    private static PyNativeFunction binary(
            String name, java.util.function.DoubleBinaryOperator operation) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.between(name, args, kwNames, 2, 2);
            double x = real(args[0]);
            double y = real(args[1]);
            return PyFloat.of(errno(name, x, operation.applyAsDouble(x, y)));
        });
    }

    /** A one-argument function that answers with a pair instead of a float. */
    private static PyNativeFunction unaryPair(
            String name, java.util.function.DoubleFunction<PyObject> pair) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.exactlyOne("math." + name + "()", args, kwNames);
            return pair.apply(real(args[0]));
        });
    }

    private static PyNativeFunction taking(
            String name, int count, java.util.function.DoubleBinaryOperator operation) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.onlyPositional(name, args, kwNames, count);
            return PyFloat.of(errno(name, real(args[0]), operation.applyAsDouble(
                    real(args[0]), real(args[1]))));
        });
    }

    /** {@code math.pow}: the domain cases the C function reports as errno rather than as a result. */
    private static double power(double x, double y) {
        if (x == 0 && y < 0) {
            throw domain();
        }
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.NaN;
        }
        double answer = Math.pow(x, y);
        if (Double.isNaN(answer)) {
            throw domain();
        }
        if (Double.isInfinite(answer) && Double.isFinite(x) && Double.isFinite(y)) {
            throw Slots.overflowError("math range error");
        }
        return answer;
    }

    /** {@code math.log(x[, base])}. */
    private static PyNativeFunction logarithmlike() {
        return new PyNativeFunction("log", (args, kwNames) -> {
            Arguments.atMost("log", args, kwNames, 2);
            double x = real(args[0]);
            if (x <= 0) {
                throw Slots.valueError("expected a positive input");
            }
            if (args.length == 1) {
                return PyFloat.of(Math.log(x));
            }
            double base = real(args[1]);
            if (base <= 0) {
                throw Slots.valueError("expected a positive input");
            }
            if (base == 1) {
                throw Slots.zeroDivision("division by zero");
            }
            return PyFloat.of(Math.log(x) / Math.log(base));
        });
    }

    /** {@code math.frexp(x) -> (m, e)}. */
    private static PyObject frexp(double x) {
        if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) {
            return PyTuple.of(PyFloat.of(x), PyInt.of(0));
        }
        int exponent = Math.getExponent(x);
        if (exponent == Double.MIN_EXPONENT - 1) {
            // A subnormal: normalize it by scaling up first.
            double scaled = Math.scalb(x, 54);
            exponent = Math.getExponent(scaled) - 54;
            x = scaled;
        }
        int e = exponent + 1;
        return PyTuple.of(PyFloat.of(Math.scalb(x, -e)), PyInt.of(e));
    }

    /** {@code math.modf(x) -> (fraction, integer)}. */
    private static PyObject modf(double x) {
        if (Double.isNaN(x)) {
            return PyTuple.of(PyFloat.of(Double.NaN), PyFloat.of(Double.NaN));
        }
        if (Double.isInfinite(x)) {
            return PyTuple.of(PyFloat.of(0.0 * x), PyFloat.of(x));
        }
        double whole = Math.copySign(Math.floor(Math.abs(x)), x);
        return PyTuple.of(PyFloat.of(x - whole), PyFloat.of(whole));
    }

    private static PyNativeFunction isclose() {
        return new PyNativeFunction("isclose", (args, kwNames) -> {
            int positional = Arguments.positional(args, kwNames);
            if (positional > 2) {
                throw Slots.typeError(
                        "isclose() takes 2 positional arguments but %d were given", positional);
            }
            PyObject[] values = new PyObject[4];
            values[0] = positional > 0 ? args[0] : null;
            values[1] = positional > 1 ? args[1] : null;
            values[2] = PyFloat.of(1e-09);
            values[3] = PyFloat.of(0.0);
            if (kwNames != null) {
                for (int i = 0; i < kwNames.length; i++) {
                    int at = indexOf(new String[] {"a", "b", "rel_tol", "abs_tol"}, kwNames[i]);
                    if (at < 0) {
                        throw Slots.typeError(
                                "isclose() got an unexpected keyword argument '%s'", kwNames[i]);
                    }
                    if (at < 2) {
                        if (values[at] != null) {
                            throw Slots.typeError(
                                    "isclose() got multiple values for argument '%s'", kwNames[i]);
                        }
                    }
                    values[at] = args[positional + i];
                }
            }
            if (values[0] == null) {
                throw Slots.typeError("isclose() missing required argument 'a' (pos 1)");
            }
            if (values[1] == null) {
                throw Slots.typeError("isclose() missing required argument 'b' (pos 2)");
            }
            double a = real(values[0]);
            double b = real(values[1]);
            double relative = real(values[2]);
            double absolute = real(values[3]);
            if (relative < 0 || absolute < 0) {
                throw Slots.valueError("tolerances must be non-negative");
            }
            if (a == b) {
                return PyBool.TRUE;
            }
            if (Double.isInfinite(a) || Double.isInfinite(b)) {
                return PyBool.FALSE;
            }
            double difference = Math.abs(a - b);
            return PyBool.of(difference <= Math.max(relative * Math.max(Math.abs(a), Math.abs(b)),
                    absolute));
        });
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code math.ulp(x)}: the distance to the next representable float. */
    private static double ulp(double x) {
        if (Double.isNaN(x)) {
            return Double.NaN;
        }
        if (Double.isInfinite(x)) {
            return Double.POSITIVE_INFINITY;
        }
        double magnitude = Math.abs(x);
        return magnitude == 0 ? Double.MIN_VALUE : Math.nextUp(magnitude) - magnitude;
    }

    private static PyNativeFunction prod() {
        return new PyNativeFunction("prod", (args, kwNames) -> {
            Arguments.onlyPositional("prod", args, kwNames, 1);
            PyObject start = PyInt.of(1);
            if (kwNames != null) {
                for (int i = 0; i < kwNames.length; i++) {
                    if (!kwNames[i].equals("start")) {
                        throw Slots.typeError(
                                "prod() got an unexpected keyword argument '%s'", kwNames[i]);
                    }
                    start = args[args.length - 1];
                }
            }
            PyObject answer = start;
            PyIterator iterator = Slots.iterator(args[0]);
            for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
                answer = multiply(answer, item);
            }
            return answer;
        });
    }

    /**
     * {@code math.prod}'s multiplication: the number protocol, not a conversion to double.
     *
     * <p>{@code prod} and {@code sumprod} run {@code PyNumber_Multiply} and {@code PyNumber_Add} on whatever
     * the iterable yields, so a {@code Decimal} stays a {@code Decimal} and a {@code Fraction} stays
     * exact. Converting through {@link #real} first — which is what this did — made
     * {@code math.prod(map(Decimal, ...))} answer a float.
     */
    private static PyObject multiply(PyObject left, PyObject right) {
        if (left instanceof PyInt a && right instanceof PyInt b) {
            return PyInt.of(a.getValue().multiply(b.getValue()));
        }
        return Slots.binary(BinaryOp.MUL, left, right);
    }

    private static PyObject add(PyObject left, PyObject right) {
        if (left instanceof PyInt a && right instanceof PyInt b) {
            return PyInt.of(a.getValue().add(b.getValue()));
        }
        return Slots.binary(BinaryOp.ADD, left, right);
    }

    private static PyNativeFunction fsum() {
        return new PyNativeFunction("fsum", (args, kwNames) -> {
            Arguments.onlyPositional("fsum", args, kwNames, 1);
            BigDecimal sum = BigDecimal.ZERO;
            boolean sawNaN = false;
            boolean sawPositive = false;
            boolean sawNegative = false;
            PyIterator iterator = Slots.iterator(args[0]);
            for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
                double value = real(item);
                if (Double.isNaN(value)) {
                    sawNaN = true;
                } else if (value == Double.POSITIVE_INFINITY) {
                    sawPositive = true;
                } else if (value == Double.NEGATIVE_INFINITY) {
                    sawNegative = true;
                } else {
                    sum = sum.add(new BigDecimal(value));
                }
            }
            if (sawPositive && sawNegative) {
                throw Slots.valueError("-inf + inf in fsum");
            }
            if (sawNaN) {
                return PyFloat.of(Double.NaN);
            }
            if (sawPositive) {
                return PyFloat.of(Double.POSITIVE_INFINITY);
            }
            if (sawNegative) {
                return PyFloat.of(Double.NEGATIVE_INFINITY);
            }
            return PyFloat.of(sum.round(new MathContext(17)).doubleValue());
        });
    }

    /**
     * {@code math.sumprod(p, q)}: the sum of the pairwise products, exact for floats.
     *
     * <p>For a pair of floats the answer is the correctly rounded exact sum, which plain
     * {@code sum(a * b)} is not — {@code sumprod((x, h), (y, -h))} is what {@code statistics._sqrtprod}
     * adds its correction from, and it agrees with a 200-digit reference where the naive sum does not.
     * Each product is split into its high and lost-low halves with {@link Math#fma}, and the four
     * terms are accumulated with Neumaier's compensation. A term that is not a float (a {@code Decimal},
     * a {@code Fraction}) flushes the pending double total and continues in the number protocol, so the
     * type of the sum is conserved as it is for {@code math.prod}.
     */
    private static PyNativeFunction sumprod() {
        return new PyNativeFunction("sumprod", (args, kwNames) -> {
            Arguments.between("sumprod", args, kwNames, 2, 2);
            PyIterator p = Slots.iterator(args[0]);
            PyIterator q = Slots.iterator(args[1]);
            PyObject sum = PyInt.of(0);
            // acc[0] is the running total and acc[1] the Neumaier correction it has dropped.
            double[] acc = new double[2];
            boolean pending = false;
            while (true) {
                PyObject left = p.nextOrNull();
                PyObject right = q.nextOrNull();
                if (left == null && right == null) {
                    return pending
                            ? add(sum, PyFloat.of(acc[0] + acc[1]))
                            : sum;
                }
                if (left == null || right == null) {
                    throw Slots.valueError("Inputs are not the same length");
                }
                PyObject term = multiply(left, right);
                if (term instanceof PyFloat exact) {
                    double product = exact.getValue();
                    // The product itself is a rounding: fma recovers the part it lost.
                    double lost = Math.fma(real(left), real(right), -product);
                    accumulate(acc, product);
                    accumulate(acc, lost);
                    pending = true;
                } else {
                    if (pending) {
                        sum = add(sum, PyFloat.of(acc[0] + acc[1]));
                        acc[0] = 0;
                        acc[1] = 0;
                        pending = false;
                    }
                    sum = add(sum, term);
                }
            }
        });
    }

    /** Neumaier's compensated addition of one more term into a running {@code total, correction} pair. */
    private static void accumulate(double[] acc, double value) {
        double total = acc[0];
        double next = total + value;
        if (Math.abs(total) >= Math.abs(value)) {
            acc[1] += (total - next) + value;
        } else {
            acc[1] += (value - next) + total;
        }
        acc[0] = next;
    }

    private static List<PyObject> drain(PyObject iterable) {
        java.util.ArrayList<PyObject> items = new java.util.ArrayList<>();
        PyIterator iterator = Slots.iterator(iterable);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            items.add(item);
        }
        return items;
    }

    private static void nonNegative(String name, BigInteger value) {
        if (value.signum() < 0) {
            throw Slots.valueError("%s must be a non-negative integer", name);
        }
    }

    private static BigInteger combinations(BigInteger n, BigInteger k) {
        if (k.compareTo(n) > 0) {
            return ZERO;
        }
        if (k.compareTo(n.subtract(k)) > 0) {
            k = n.subtract(k);
        }
        BigInteger answer = ONE;
        for (BigInteger i = ONE; i.compareTo(k) <= 0; i = i.add(ONE)) {
            answer = answer.multiply(n.subtract(k).add(i)).divide(i);
        }
        return answer;
    }

    private static BigInteger permutations(BigInteger n, BigInteger k) {
        if (k.compareTo(n) > 0) {
            return ZERO;
        }
        BigInteger answer = ONE;
        for (BigInteger i = ZERO; i.compareTo(k) < 0; i = i.add(ONE)) {
            answer = answer.multiply(n.subtract(i));
        }
        return answer;
    }
}
