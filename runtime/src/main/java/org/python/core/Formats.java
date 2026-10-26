package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

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
        if (spec.isEmpty()) {
            // Every advanced writer answers an absent specification with str(value), and reads
            // nothing else of it.
            return Slots.str(value);
        }
        int presented = presentation(value);
        if (presented < 0) {
            // object.__format__ never parses: a specification it has no presentation for is the error.
            throw Slots.typeError(
                    "unsupported format string passed to %s.__format__", value.getType().filedName());
        }
        Spec parsed = Spec.parse(value.getType().filedName(), (char) presented, spec);
        String body = convert(value, parsed);
        return parsed.align(body, counted(value));
    }

    /**
     * The presentation type a family falls back to when the specification names none, which is the
     * character its writer validates a grouping option against. A value with no family of its own
     * answers -1.
     */
    private static int presentation(PyObject value) {
        if (value instanceof PyStr) {
            return 's';
        }
        if (value instanceof PyInt || value instanceof PyBool) {
            return 'd';
        }
        // float's default is the character CPython spells '\0': no type at all, which is repr.
        return value instanceof PyFloat ? '\0' : -1;
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
            requireTextType(spec.type, spec.typeName);
            requireTextSpec(spec);
            if (spec.precision < 0 || spec.precision >= s.codePointLength()) {
                return s.getValue();
            }
            return s.getValue()
                    .substring(0, s.getValue().offsetByCodePoints(0, (int) spec.precision));
        }
        if (value instanceof PyInt || value instanceof PyBool) {
            return integer(value, spec);
        }
        return floating(((PyFloat) value).getValue(), spec);
    }

    private static void requireTextType(String type, String typeName) {
        if (!type.isEmpty() && !"s".equals(type)) {
            throw Slots.valueError(
                    "Unknown format code '%s' for object of type '%s'", named(type.charAt(0)), typeName);
        }
    }

    /** The four things {@code format_string_internal} refuses a text value, in the order it refuses them. */
    private static void requireTextSpec(Spec spec) {
        if (!spec.sign.isEmpty()) {
            throw Slots.valueError(
                    " ".equals(spec.sign)
                            ? "Space not allowed in string format specifier"
                            : "Sign not allowed in string format specifier");
        }
        if (spec.noNegZero) {
            throw Slots.valueError(
                    "Negative zero coercion (z) not allowed in string format specifier");
        }
        if (!spec.alt.isEmpty()) {
            throw Slots.valueError("Alternate form (#) not allowed in string format specifier");
        }
        if (spec.align == '=') {
            throw Slots.valueError("'=' alignment not allowed in string format specifier");
        }
    }

    /**
     * The two things {@code format_long_internal} refuses a whole number, once its presentation type has
     * already been accepted. A code that presents the number as a float never reaches either: the int
     * writer hands that value to the float one, precision and all.
     */
    private static void requireIntegerSpec(Spec spec) {
        if (spec.precision >= 0) {
            throw Slots.valueError("Precision not allowed in integer format specifier");
        }
        if (spec.noNegZero) {
            throw Slots.valueError(
                    "Negative zero coercion (z) not allowed in integer format specifier");
        }
    }

    /** How CPython names a presentation type too wide to print as a character. */
    private static String named(char type) {
        return type < 127 ? String.valueOf(type) : String.format("\\x%x", (int) type);
    }

    private static String integer(PyObject value, Spec spec) {
        BigInteger n = value instanceof PyInt i
                ? i.getValue()
                : BigInteger.valueOf(((PyBool) value).getValue() ? 1 : 0);
        String type = spec.type;
        if (!type.isEmpty() && "bcdoxnX".indexOf(type.charAt(0)) < 0) {
            if ("eEfFgG%".indexOf(type.charAt(0)) >= 0) {
                // `PyNumber_Float` of the value, which is the one place an integer's own size matters.
                return floating(Numeric.asDouble(PyInt.of(n)), spec);
            }
            throw Slots.valueError(
                    "Unknown format code '%s' for object of type '%s'",
                    named(type.charAt(0)), spec.typeName);
        }
        requireIntegerSpec(spec);
        if ("c".equals(type)) {
            if (!spec.sign.isEmpty()) {
                throw Slots.valueError("Sign not allowed with integer format specifier 'c'");
            }
            if (!spec.alt.isEmpty()) {
                throw Slots.valueError(
                        "Alternate form (#) not allowed with integer format specifier 'c'");
            }
        }
        String raw;
        if (type.isEmpty() || "d".equals(type) || "n".equals(type)) {
            raw = n.abs().toString();
        } else if ("b".equals(type)) {
            raw = n.abs().toString(2);
        } else if ("o".equals(type)) {
            raw = n.abs().toString(8);
        } else if ("x".equals(type) || "X".equals(type)) {
            raw = n.abs().toString(16);
            if ("X".equals(type)) {
                raw = raw.toUpperCase();
            }
        } else {
            raw = new String(Character.toChars(ordinal(n)));
        }
        // Hex and octal digits are letters as often as not, so the whole run is grouped as written.
        String digits = spec.grouping.isEmpty()
                ? raw
                : group(raw, spec.grouping.charAt(0), !type.isEmpty() && "boxX".indexOf(type) >= 0 ? 4 : 3,
                        true);
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

    /** {@code PyLong_AsLong} of the value, and the {@code %c} range check that follows the call. */
    private static int ordinal(BigInteger n) {
        if (n.bitLength() >= Long.SIZE) {
            throw Slots.overflowError("Python int too large to convert to C long");
        }
        long wide = n.longValue();
        if (wide < 0 || wide > 0x10ffff) {
            // The sentence is CPython's own, and its `%c` names the presentation type, not a slot.
            throw Slots.overflowError("%%c arg not in range(0x110000)");
        }
        return (int) wide;
    }

    /**
     * {@code format_float_internal}: {@code PyOS_double_to_string} and then the padding.
     *
     * <p>The omitted code is not {@code 'g'}: it is repr, and repr reads the fewest digits that still
     * come back as the same double. The three other families ask the exact binary value for a number of
     * significant digits or of places and round to nearest with ties to the even digit, which is what
     * Java's own {@code %f} does not do — {@code format(2.5, '.0f')} is {@code '2'} here and {@code '3'}
     * there, and {@code format(1e100, 'f')} is the 104 digits the value really holds.
     */
    private static String floating(double value, Spec spec) {
        boolean omitted = spec.type.isEmpty();
        char type = omitted ? 'r' : spec.type.charAt(0);
        if (!omitted && "eEfFgGn%".indexOf(type) < 0) {
            throw Slots.valueError(
                    "Unknown format code '%s' for object of type '%s'",
                    named(type), spec.typeName);
        }
        if (spec.precision > Integer.MAX_VALUE - 1024) {
            // `DOUBLE_TO_STRING_PRECISION_MAX`, which is how far dtoa is willing to be asked for digits.
            throw Slots.valueError("precision too big");
        }
        boolean upper = type == 'E' || type == 'F' || type == 'G';
        type = Character.toLowerCase(type);
        boolean addDotZero = omitted;
        boolean percent = type == '%';
        if (type == 'n') {
            // The locale form is the general one, and this runtime has only the C locale to group with.
            type = 'g';
        }
        if (percent) {
            // The hundred is multiplied into the double itself, so `format(1e308, '%')` is an
            // infinity and not a number with two more digits than the value has room for.
            type = 'f';
            value = value * 100;
        }
        int precision = (int) spec.precision;
        if (precision < 0) {
            precision = omitted ? 0 : 6;
        } else if (type == 'r') {
            // A precision is a general one asked of the omitted code, and it keeps repr's dot.
            type = 'g';
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            String name = Double.isNaN(value) ? (upper ? "NAN" : "nan") : (upper ? "INF" : "inf");
            // A nan is the one value whose sign dtoa throws away before it writes the name.
            return leading(!Double.isNaN(value) && negative(value), spec) + name + suffix(percent);
        }
        int mode = type == 'r' ? 0 : type == 'f' ? 3 : 2;
        if (type == 'e') {
            precision = precision + 1;
        } else if (type == 'g' && precision == 0) {
            precision = 1;
        }
        Digits digits = dtoa(value, mode, precision);
        if (spec.noNegZero && isNothing(digits.digits())) {
            // The `z` flag asks a value that rounds to nothing not to wear a minus for it.
            digits = new Digits(false, digits.digits(), digits.point());
        }
        String body =
                layOut(digits, type, precision, addDotZero, "#".equals(spec.alt), upper ? 'E' : 'e')
                        + suffix(percent);
        return leading(digits.negative(), spec) + grouped(body, spec);
    }

    /** dtoa's digit run for a value that had no significant digit to give: one zero, or none. */
    private static boolean isNothing(String digits) {
        return digits.isEmpty() || digits.equals("0");
    }

    private static String suffix(boolean percent) {
        return percent ? "%" : "";
    }

    /** The sign of a double as its bits tell it, which is the only way {@code -0.0} says so. */
    private static boolean negative(double value) {
        return Double.doubleToRawLongBits(value) < 0;
    }

    /** {@code _Py_dg_dtoa} in the three modes the presentation types ask for. */
    private static Digits dtoa(double value, int mode, int precision) {
        boolean negative = negative(value);
        BigDecimal exact = new BigDecimal(Math.abs(value));
        if (mode == 0) {
            int[] point = new int[1];
            return new Digits(negative, PyFloat.shortestDigits(Math.abs(value), point), point[0]);
        }
        BigDecimal rounded = mode == 2
                ? exact.round(new MathContext(precision, RoundingMode.HALF_EVEN))
                : exact.setScale(precision, RoundingMode.HALF_EVEN);
        String digits = rounded.unscaledValue().toString();
        if (mode == 2) {
            // Gay hands the general form its digits without the zeros it did not ask for; the
            // exponential one pads them back out by the width of the field, not by these digits.
            String stripped = digits.replaceAll("0+$", "");
            digits = stripped.isEmpty() ? "0" : stripped;
        }
        return new Digits(negative, digits, rounded.precision() - rounded.scale());
    }

    /**
     * {@code format_float_short}'s slice of the infinite run of zeros the digit string implies:
     * {@code [<sign>]<zeros><digits><zeros>[<exponent>]}, with the decimal point somewhere in it.
     */
    private static String layOut(
            Digits digits, char type, int precision, boolean addDotZero, boolean alt, char exponentChar) {
        String run = digits.digits();
        int point = digits.point();
        boolean useExponent = false;
        int end = run.length();
        switch (type) {
            case 'e' -> {
                useExponent = true;
                end = precision;
            }
            case 'f' -> end = point + precision;
            case 'g' -> {
                useExponent = point <= -4 || point > (addDotZero ? precision - 1 : precision);
                if (alt) {
                    end = precision;
                }
            }
            default -> {
                // 'r' leaves the exponential form to the two thresholds repr has always used.
                useExponent = point <= -4 || point > 16;
            }
        }
        int exponent = 0;
        if (useExponent) {
            exponent = point - 1;
            point = 1;
        }
        int start = point <= 0 ? point - 1 : 0;
        if (!useExponent && addDotZero) {
            end = Math.max(end, point + 1);
        } else {
            end = Math.max(end, point);
        }
        StringBuilder out = new StringBuilder();
        if (point <= 0) {
            out.append(zeros(point - start)).append('.').append(zeros(-point));
        } else {
            out.append(zeros(-start));
        }
        if (point > 0 && point <= run.length()) {
            out.append(run, 0, point).append('.').append(run.substring(point));
        } else {
            out.append(run);
        }
        if (run.length() < point) {
            out.append(zeros(point - run.length())).append('.').append(zeros(end - point));
        } else {
            out.append(zeros(end - run.length()));
        }
        if (!alt && out.length() > 0 && out.charAt(out.length() - 1) == '.') {
            out.setLength(out.length() - 1);
        }
        if (useExponent) {
            int magnitude = Math.abs(exponent);
            out.append(exponentChar)
                    .append(exponent < 0 ? '-' : '+')
                    .append(magnitude < 10 ? "0" + magnitude : String.valueOf(magnitude));
        }
        return out.toString();
    }

    private static String zeros(int count) {
        return count <= 0 ? "" : "0".repeat(count);
    }

    /** The digits, where the decimal point falls among them, and whether the value was negative. */
    private record Digits(boolean negative, String digits, int point) {}

    /**
     * The {@code _} or {@code ,} grouping option, put into the digits on each side of the point and
     * nowhere else: {@code format(1e100, ',.2f')} groups the 104 digits it writes and leaves the {@code
     * .2} alone, and an exponent or a {@code %} is not part of what gets grouped either. Only a
     * separator written after the point — {@code '.4,f'} — groups the digits that follow it, and it
     * counts those from the point outward, so their short run is the last one.
     */
    private static String grouped(String body, Spec spec) {
        int head = 0;
        while (head < body.length() && Character.isDigit(body.charAt(head))) {
            head++;
        }
        String out = spec.grouping.isEmpty()
                ? body.substring(0, head)
                : group(body.substring(0, head), spec.grouping.charAt(0), 3, true);
        if (head < body.length() && body.charAt(head) == '.' && !spec.fracGrouping.isEmpty()) {
            int tail = head + 1;
            while (tail < body.length() && Character.isDigit(body.charAt(tail))) {
                tail++;
            }
            return out + "."
                    + group(body.substring(head + 1, tail), spec.fracGrouping.charAt(0), 3, false)
                    + body.substring(tail);
        }
        return out + body.substring(head);
    }

    /**
     * The character in front of the digits: the minus the value carries, or the one the sign flag
     * asks for. {@code '-'} is that flag's third answer and asks for nothing on a value that is not
     * negative, which is why {@code format(1.5, '-08.1e')} is {@code '01.5e+00'} and not a number
     * with a sign it never had.
     */
    private static String leading(boolean negative, Spec spec) {
        return negative ? "-" : "-".equals(spec.sign) ? "" : spec.sign;
    }

    private static String sign(BigInteger value, Spec spec) {
        return leading(value.signum() < 0, spec);
    }

    /**
     * The separator, stood between every {@code every} digits. Reading a whole number from its point
     * outward leaves a short run at the front — {@code 1,234,567} — while reading a fraction from the
     * point leaves the short run at the end, which is why {@code '.4,f'} answers {@code 1234.567,9}.
     */
    private static String group(String digits, char separator, int every, boolean shortRunFirst) {
        StringBuilder out = new StringBuilder();
        int remainder = digits.length() % every;
        int leading = !shortRunFirst || remainder == 0 ? every : remainder;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - leading) % every == 0) {
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
        long width = -1;
        long precision = -1;
        String type = "";
        String grouping = "";
        String fracGrouping = "";
        boolean zeroPad;
        boolean noNegZero;
        /** {@code Py_TYPE(obj)->tp_name}, which three of the sentences below print. */
        String typeName = "";

        /**
         * {@code parse_internal_render_format_spec}, character by character, with the object's default
         * presentation type in place of the one the grammar leaves out.
         */
        static Spec parse(String typeName, char defaultType, String spec) {
            Spec out = new Spec();
            out.typeName = typeName;
            int at = 0;
            boolean fillGiven = false;
            boolean alignGiven = false;
            // Only the character that stands in front of an alignment is a fill. A space that opens
            // the spec is instead the sign that asks a positive number to wear one.
            if (spec.length() - at >= 2 && "<^>=".indexOf(spec.charAt(at + 1)) >= 0) {
                out.fill = String.valueOf(spec.charAt(at));
                out.align = spec.charAt(at + 1);
                fillGiven = alignGiven = true;
                at += 2;
            } else if (at < spec.length() && "<^>=".indexOf(spec.charAt(at)) >= 0) {
                out.align = spec.charAt(at);
                alignGiven = true;
                at++;
            }
            if (at < spec.length() && "+- ".indexOf(spec.charAt(at)) >= 0) {
                out.sign = String.valueOf(spec.charAt(at));
                at++;
            }
            if (at < spec.length() && spec.charAt(at) == 'z') {
                out.noNegZero = true;
                at++;
            }
            if (at < spec.length() && spec.charAt(at) == '#') {
                out.alt = "#";
                at++;
            }
            if (!fillGiven && at < spec.length() && spec.charAt(at) == '0') {
                // The digit is the fill for every type; the alignment it stands for is only a
                // number's, and only where the spec did not already choose one.
                out.fill = "0";
                out.zeroPad = !alignGiven;
                at++;
            }
            long[] held = new long[1];
            int consumed = digits(spec, at, held);
            at += consumed;
            if (consumed > 0) {
                out.width = held[0];
            }
            if (at < spec.length() && spec.charAt(at) == ',') {
                out.grouping = ",";
                at++;
            }
            if (at < spec.length() && spec.charAt(at) == '_') {
                if (!out.grouping.isEmpty()) {
                    throw Slots.valueError("Cannot specify both ',' and '_'.");
                }
                out.grouping = "_";
                at++;
            }
            if (at < spec.length() && spec.charAt(at) == ',' && out.grouping.equals("_")) {
                throw Slots.valueError("Cannot specify both ',' and '_'.");
            }
            if (at < spec.length() && spec.charAt(at) == '.') {
                at++;
                consumed = digits(spec, at, held);
                at += consumed;
                out.precision = consumed > 0 ? held[0] : 0;
                if (at < spec.length() && spec.charAt(at) == ',') {
                    out.precision = consumed == 0 ? -1 : out.precision;
                    out.fracGrouping = ",";
                    at++;
                    consumed++;
                }
                if (at < spec.length() && spec.charAt(at) == '_') {
                    if (!out.fracGrouping.isEmpty()) {
                        throw Slots.valueError("Cannot specify both ',' and '_'.");
                    }
                    out.precision = consumed == 0 ? -1 : out.precision;
                    out.fracGrouping = "_";
                    at++;
                    consumed++;
                }
                if (at < spec.length() && spec.charAt(at) == ',' && out.fracGrouping.equals("_")) {
                    throw Slots.valueError("Cannot specify both ',' and '_'.");
                }
                if (consumed == 0) {
                    throw Slots.valueError("Format specifier missing precision");
                }
            }
            if (spec.length() - at > 1) {
                throw Slots.valueError(
                        "Invalid format specifier '%s' for object of type '%s'", spec, typeName);
            }
            if (spec.length() - at == 1) {
                out.type = spec.substring(at);
            }
            requireGrouping(out, defaultType);
            return out;
        }

        /**
         * {@code get_integer}: how many decimal digits open {@code spec} at {@code from}, and what they
         * add up to. A run too long for a {@code Py_ssize_t} is the sentence CPython raises for it,
         * which Java would otherwise answer with a {@code NumberFormatException} of its own.
         */
        private static int digits(String spec, int from, long[] out) {
            long accumulator = 0;
            int count = 0;
            while (from + count < spec.length() && Character.isDigit(spec.charAt(from + count))) {
                int value = Character.digit(spec.charAt(from + count), 10);
                if (accumulator > (Long.MAX_VALUE - value) / 10) {
                    throw Slots.valueError("Too many decimal digits in format string");
                }
                accumulator = accumulator * 10 + value;
                count++;
            }
            out[0] = accumulator;
            return count;
        }

        /**
         * The grouping option's own last look, which is the parser's, not the writer's: only a type
         * with digits to group may ask for one, and bin, oct and hex take underscores alone — PEP 515
         * groups those every four, because four of them is one nibble of nothing.
         */
        private static void requireGrouping(Spec out, char defaultType) {
            char type = out.type.isEmpty() ? defaultType : out.type.charAt(0);
            if (!out.grouping.isEmpty()) {
                if ("deEfFgG%\0".indexOf(type) >= 0) {
                    return;
                }
                if ("boxX".indexOf(type) >= 0 && out.grouping.equals("_")) {
                    return;
                }
                throw Slots.valueError(
                        "Cannot specify '%s' with '%s'.", out.grouping, named(type));
            }
            if (type == 'n' && !out.fracGrouping.isEmpty()) {
                throw Slots.valueError("Cannot specify '%s' with 'n'.", out.fracGrouping);
            }
        }

        /** Pad to the width CPython was asked for, on the side the alignment selects. */
        String align(String body, boolean numeric) {
            if (width < 0 || body.length() >= width) {
                return body;
            }
            char pad = fill.length() == 1 ? fill.charAt(0) : ' ';
            int missing = Numbers.total(width - body.length());
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
