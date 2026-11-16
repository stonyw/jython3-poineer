package org.python.core;

/**
 * {@code _statistics}: the one accelerator {@code statistics.py} takes when it is present.
 *
 * <p>The module is small on purpose. {@code statistics.py}'s private {@code _sum} is written in Python
 * in both interpreters; the C extension only carries {@code _normal_dist_inv_cdf}, the inverse of the
 * standard normal CDF, which {@code statistics.py} tries to import at the very end and keeps as its own
 * pure-Python spelling when the import fails:
 *
 * <pre>
 * try:
 *     from _statistics import _normal_dist_inv_cdf
 * except ImportError:
 *     pass
 * </pre>
 *
 * <p>The body below is {@code _statistics__normal_dist_inv_cdf_impl} from
 * {@code Modules/_statisticsmodule.c}: Wichura's AS241 rational approximation, transcribed term for
 * term so the arithmetic runs in the same order and rounds the same way. The parameters are
 * positional-only, the conversion is {@code PyFloat_AsDouble}'s (so a {@code Fraction} or a
 * {@code Decimal} is read through its {@code __float__} and a string is refused), and every parameter
 * outside the open unit interval — with a denominator that vanishes — is the one sentence
 * {@code inv_cdf undefined for these parameters}.
 */
final class StatisticsModule {

    private static final String DOC = "Accelerators for the statistics module.\n";

    /** The clinic text: a table with no body, which is why {@code __doc__} reads {@code None}. */
    private static final String NORMAL_DIST_INV_CDF_DOC =
            "_normal_dist_inv_cdf($module, p, mu, sigma, /)\n--\n\n";

    private StatisticsModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_statistics");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_statistics");
        module.set("_normal_dist_inv_cdf",
                new PyNativeFunction("_normal_dist_inv_cdf", StatisticsModule::normalDistInvCdf)
                        .documented(NORMAL_DIST_INV_CDF_DOC));
        return module;
    }

    /**
     * {@code _statistics._normal_dist_inv_cdf(p, mu, sigma, /)}.
     *
     * <p>The count is checked with the generated wrapper's own sentence — no parentheses, because it
     * comes from {@code PyErr_Format} in the clinic output rather than from the call machinery — and the
     * keyword refusal is the call machinery's, which runs before the count.
     */
    private static PyObject normalDistInvCdf(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("_statistics._normal_dist_inv_cdf()", kwNames);
        if (args.length != 3) {
            throw Slots.typeError(
                    "_normal_dist_inv_cdf expected 3 arguments, got %d", args.length);
        }
        double p = real(args[0]);
        double mu = real(args[1]);
        double sigma = real(args[2]);
        return PyFloat.of(invCdf(p, mu, sigma));
    }

    /** {@code PyFloat_AsDouble}: the value through {@code __float__}, or the converter's refusal. */
    private static double real(PyObject value) {
        Double number = Numbers.floatOf(value);
        if (number == null) {
            throw Slots.typeError("must be real number, not %s", value.getType().filedName());
        }
        return number;
    }

    /** {@code _statistics__normal_dist_inv_cdf_impl}: Wichura's AS241. */
    private static double invCdf(double p, double mu, double sigma) {
        double q;
        double num;
        double den;
        double r;
        double x;
        if (p <= 0.0 || p >= 1.0) {
            throw undefined();
        }

        q = p - 0.5;
        if (Math.abs(q) <= 0.425) {
            // The reference binary is built with contraction on, so `0.180625 - q*q` is one fmsub
            // and every `poly*r + c` stage is one fmadd. Reproducing that here is what makes the
            // last bit agree with python3.14; plain multiply-then-add is a different function.
            r = Math.fma(-q, q, 0.180625);
            // Hash sum-55.8831928806149014439
            num = polyAt(r,
                    2.5090809287301226727e+3, 3.3430575583588128105e+4,
                    6.7265770927008700853e+4, 4.5921953931549871457e+4,
                    1.3731693765509461125e+4, 1.9715909503065514427e+3,
                    1.3314166789178437745e+2, 3.3871328727963666080e+0);
            den = polyAt(r,
                    5.2264952788528545610e+3, 2.8729085735721942674e+4,
                    3.9307895800092710610e+4, 2.1213794301586595867e+4,
                    5.3941960214247511077e+3, 6.8718700749205790830e+2,
                    4.2313330701600911252e+1, 1.0);
            if (den == 0.0) {
                throw undefined();
            }
            x = (q * num) / den;
            return Math.fma(x, sigma, mu);
        }
        r = (q <= 0.0) ? p : (1.0 - p);
        if (r <= 0.0 || r >= 1.0) {
            throw undefined();
        }
        r = Math.sqrt(-Math.log(r));
        if (r <= 5.0) {
            r = r - 1.6;
            // Hash sum-49.33206503301610289036
            num = polyAt(r,
                    7.74545014278341407640e-4, 2.27238449892691845833e-2,
                    2.41780725177450611770e-1, 1.27045825245236838258e+0,
                    3.64784832476320460504e+0, 5.76949722146069140550e+0,
                    4.63033784615654529590e+0, 1.42343711074968357734e+0);
            den = polyAt(r,
                    1.05075007164441684324e-9, 5.47593808499534494600e-4,
                    1.51986665636164571966e-2, 1.48103976427480074590e-1,
                    6.89767334985100004550e-1, 1.67638483018380384940e+0,
                    2.05319162663775882187e+0, 1.0);
        } else {
            r -= 5.0;
            // Hash sum-47.52583317549289671629
            num = polyAt(r,
                    2.01033439929228813265e-7, 2.71155556874348757815e-5,
                    1.24266094738807843860e-3, 2.65321895265761230930e-2,
                    2.96560571828504891230e-1, 1.78482653991729133580e+0,
                    5.46378491116411436990e+0, 6.65790464350110377720e+0);
            den = polyAt(r,
                    2.04426310338993978564e-15, 1.42151175831644588870e-7,
                    1.84631831751005468180e-5, 7.86869131145613259100e-4,
                    1.48753612908506148525e-2, 1.36929880922735805310e-1,
                    5.99832206555887937690e-1, 1.0);
        }
        if (den == 0.0) {
            throw undefined();
        }
        x = num / den;
        if (q < 0.0) {
            x = -x;
        }
        return Math.fma(x, sigma, mu);
    }

    /** {@code (((c0*r + c1)*r + c2)...)}, fused the way the reference compiler fuses it. */
    private static double polyAt(double r, double... coefficients) {
        double value = Math.fma(coefficients[0], r, coefficients[1]);
        for (int i = 2; i < coefficients.length; i++) {
            value = Math.fma(value, r, coefficients[i]);
        }
        return value;
    }

    private static PyRaise undefined() {
        return Slots.valueError("inv_cdf undefined for these parameters");
    }
}
