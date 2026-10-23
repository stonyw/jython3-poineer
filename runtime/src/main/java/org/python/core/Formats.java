package org.python.core;

import java.math.BigInteger;

/**
 * The format mini-language, as {@code FORMAT_WITH_SPEC} and {@code str.format} use it.
 *
 * The grammar is CPython's — {@code [[fill]align][sign][#][0][width][grouping_option][.precision][type]}
 * — and this implements the parts of it that a value with no presentation type of its own can ask
 * for: alignment and width for every type, and the number and string conversions. An unknown type code
 * is the {@code ValueError} CPython raises, with its wording, because tests assert the message.
 */
final class Formats {

    private Formats() {
    }

    /** {@code format(value, spec)}. */
    static String apply(PyObject value, String spec) {
        Spec parsed = Spec.parse(spec);
        String body = convert(value, parsed);
        return parsed.align(body, counted(value));
    }

    /**
     * Whether the value's own default alignment is the numeric one, which is the whole of CPython's rule
     * for a spec that names no alignment: {@code >} for a number and {@code <} for a string.
     *
     * <p>It is asked of the value and not of the text it presents as, because the two disagree for every
     * string that ends with a digit: {@code format('12', '6')} is {@code '12    '} while {@code
     * format(12, '6')} is {@code '    12'}, and CPython's rewrite of {@code '%-6s' % ('12',)} into {@code
     * FORMAT_WITH_SPEC} hands the same text to both.
     */
    private static boolean counted(PyObject value) {
        return value instanceof PyInt || value instanceof PyBool || value instanceof PyFloat;
    }

    /** {@code value} presented according to the type-specific half of its spec, before padding. */
    private static String convert(PyObject value, Spec spec) {
        if (value instanceof PyStr s) {
            requireTextType(spec.type);
            return spec.precision < 0
                    ? s.getValue()
                    : s.getValue().substring(0, s.getValue().offsetByCodePoints(
                            0, Math.min(spec.precision, s.codePointLength())));
        }
        if (value instanceof PyInt || value instanceof PyBool) {
            return integer(value, spec);
        }
        if (value instanceof PyFloat f) {
            return floating(f.getValue(), spec);
        }
        // object.__format__ accepts an empty specification and nothing else.
        if (spec.isEmpty()) {
            return Slots.str(value);
        }
        throw Slots.typeError(
                "unsupported format string passed to %s.__format__", value.getType().filedName());
    }

    private static void requireTextType(String type) {
        if (!type.isEmpty() && !"s".equals(type)) {
            throw Slots.valueError("Unknown format code '%s' for object of type 'str'", type);
        }
    }

    private static String integer(PyObject value, Spec spec) {
        BigInteger n = value instanceof PyInt i
                ? i.getValue()
                : BigInteger.valueOf(((PyBool) value).getValue() ? 1 : 0);
        String type = spec.type;
        String digits;
        if (type.isEmpty() || "d".equals(type) || "n".equals(type)) {
            digits = group(n.abs().toString(), spec);
        } else if ("b".equals(type)) {
            digits = n.abs().toString(2);
        } else if ("o".equals(type)) {
            digits = n.abs().toString(8);
        } else if ("x".equals(type) || "X".equals(type)) {
            digits = n.abs().toString(16);
            if ("X".equals(type)) {
                digits = digits.toUpperCase();
            }
        } else if ("%".equals(type)) {
            digits = group(n.abs().multiply(BigInteger.valueOf(100)).toString(), spec) + "%";
        } else if ("c".equals(type)) {
            int cp = n.intValueExact();
            digits = String.valueOf((char) cp);
        } else if ("eEfFgG".indexOf(type.charAt(0)) >= 0 && type.length() == 1) {
            return floating(n.doubleValue(), spec);
        } else {
            throw Slots.valueError(
                    "Unknown format code '%s' for object of type 'int'", type);
        }
        if ("#".equals(spec.alt) && !type.isEmpty() && !"d".equals(type)) {
            digits = switch (type) {
                case "b" -> "0b" + digits;
                case "o" -> "0o" + digits;
                case "x", "X" -> "0" + type + digits;
                default -> digits;
            };
        }
        return sign(n, spec) + digits;
    }

    private static String floating(double value, Spec spec) {
        String type = spec.type.isEmpty() ? "g" : spec.type;
        int precision = spec.precision < 0 ? 6 : spec.precision;
        if ("s".equals(type)) {
            throw Slots.valueError("Unknown format code 's' for object of type 'float'");
        }
        String digits;
        switch (type) {
            case "f", "F" -> digits = fixed(value, precision, type);
            case "e", "E" -> digits = exponent(value, precision, type);
            case "g", "G" -> digits = general(value, precision, type);
            case "n" -> digits = general(value, precision == 6 ? 12 : precision, type);
            case "%" -> digits = fixed(value * 100, precision, "f") + "%";
            default -> throw Slots.valueError(
                    "Unknown format code '%s' for object of type 'float'", type);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            // A nan or an inf is still signed and still aligned, but never padded or grouped.
            String name = Double.isNaN(value) ? "nan" : "inf";
            return (value < 0 ? "-" : spec.sign) + name;
        }
        return sign(value, spec) + group(digits, spec);
    }

    private static String fixed(double value, int precision, String type) {
        String out = String.format(
                java.util.Locale.ROOT, "%." + precision + "f", Math.abs(value));
        return "F".equals(type) ? out.replace("e", "E") : out;
    }

    private static String exponent(double value, int precision, String type) {
        String out = String.format(java.util.Locale.ROOT, "%." + precision + (type), Math.abs(value));
        // Java emits at least three exponent digits; Python uses two.
        int at = out.indexOf('e');
        if (at >= 0 && out.length() - at > 3) {
            out = out.substring(0, at + 2) + out.substring(at + 3);
        }
        return "E".equals(type) ? out.toUpperCase() : out;
    }

    private static String general(double value, int precision, String type) {
        String out = String.format(
                java.util.Locale.ROOT, "%." + Math.max(precision, 1) + ("G".equals(type) ? "G" : "g"),
                Math.abs(value));
        if (out.startsWith("0.")) {
            return trimZeros(out);
        }
        return out.contains("e") || out.contains("E") ? out : trimZeros(out);
    }

    /** {@code %g} drops the trailing zeros it was not asked to keep; Java keeps them. */
    private static String trimZeros(String digits) {
        int dot = digits.indexOf('.');
        if (dot < 0 || digits.matches(".*[eE].*")) {
            return digits;
        }
        String out = digits;
        while (out.endsWith("0")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.endsWith(".") ? out.substring(0, out.length() - 1) : out;
    }

    private static String sign(BigInteger value, Spec spec) {
        return value.signum() < 0 ? "-" : spec.sign;
    }

    private static String sign(double value, Spec spec) {
        return value < 0 ? "-" : spec.sign;
    }

    /** The {@code _} or {@code ,} grouping option, applied to the digits of a decimal number. */
    private static String group(String digits, Spec spec) {
        if (spec.grouping.isEmpty()) {
            return digits;
        }
        char separator = spec.grouping.charAt(0);
        StringBuilder out = new StringBuilder();
        int leading = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - leading) % 3 == 0) {
                out.append(separator);
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    /** The presentation half of a format specification. */
    private static final class Spec {

        String fill = " ";
        char align = 0;
        String sign = "";
        String alt = "";
        int width = -1;
        int precision = -1;
        String type = "";
        String grouping = "";
        boolean zeroPad;

        boolean isEmpty() {
            return fill.equals(" ") && align == 0 && sign.isEmpty() && alt.isEmpty()
                    && width < 0 && precision < 0 && type.isEmpty() && grouping.isEmpty()
                    && !zeroPad;
        }

        static Spec parse(String spec) {
            Spec out = new Spec();
            int at = 0;
            if (spec.length() >= 3 && "<^>= ".indexOf(spec.charAt(2)) >= 0) {
                out.fill = spec.substring(0, 1);
                out.align = spec.charAt(2);
                at = 3;
            } else if (!spec.isEmpty() && "<^>= ".indexOf(spec.charAt(0)) >= 0) {
                out.align = spec.charAt(0);
                at = 1;
            }
            if (at < spec.length() && "+- ".indexOf(spec.charAt(at)) >= 0) {
                out.sign = String.valueOf(spec.charAt(at));
                at++;
            }
            if (at < spec.length() && spec.charAt(at) == '#') {
                out.alt = "#";
                at++;
            }
            if (at < spec.length() && spec.charAt(at) == '0') {
                out.zeroPad = true;
                if (out.align == 0) {
                    // The fill is the digit for every type; only the alignment it stands for is the
                    // number's, which is why format('12', '06') left-justifies inside zeroes.
                    out.fill = "0";
                }
                at++;
            }
            int digitsStart = at;
            while (at < spec.length() && Character.isDigit(spec.charAt(at))) {
                at++;
            }
            if (at > digitsStart) {
                out.width = Integer.parseInt(spec.substring(digitsStart, at));
            }
            if (at < spec.length() && (spec.charAt(at) == ',' || spec.charAt(at) == '_')) {
                out.grouping = String.valueOf(spec.charAt(at));
                at++;
            }
            if (at < spec.length() && spec.charAt(at) == '.') {
                at++;
                int start = at;
                while (at < spec.length() && Character.isDigit(spec.charAt(at))) {
                    at++;
                }
                out.precision = at > start ? Integer.parseInt(spec.substring(start, at)) : 0;
            }
            if (at < spec.length()) {
                out.type = spec.substring(at);
                at = spec.length();
            }
            if (at != spec.length()) {
                throw Slots.valueError("Invalid format specifier: '%s'", spec);
            }
            return out;
        }

        /** Pad to the width CPython was asked for, on the side the alignment selects. */
        String align(String body, boolean numeric) {
            if (width < 0 || body.length() >= width) {
                return body;
            }
            char pad = fill.length() == 1 ? fill.charAt(0) : ' ';
            int missing = width - body.length();
            char alignment =
                    align != 0
                            ? align
                            : numeric
                                    ? (zeroPad ? '=' : '>')
                                    : '<';
            switch (alignment) {
                case '<':
                    return body + repeat(pad, missing);
                case '>':
                    return repeat(pad, missing) + body;
                case '^':
                    int left = missing / 2;
                    return repeat(pad, left) + body + repeat(pad, missing - left);
                default:
                    // '=' keeps a sign glued to the number, which is what a zero-padded field wants.
                    int signLength = body.length() > 0 && "+- ".indexOf(body.charAt(0)) >= 0 ? 1 : 0;
                    return body.substring(0, signLength)
                            + repeat(pad, missing)
                            + body.substring(signLength);
            }
        }

        private static String repeat(char c, int times) {
            return times <= 0 ? "" : String.valueOf(c).repeat(times);
        }
    }
}
