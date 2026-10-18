package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * CPython's {@code %} formatting for {@code str}, the operator whose right operand can be a tuple,
 * a mapping or a single value.
 *
 * Three rules decide what a program sees, and all three are measured rather than guessed. The
 * argument is taken when the format string is parsed rather than when it is used, so
 * {@code "%y" % ()} is "not enough arguments" and {@code "%(a)x" % {}} is a {@code KeyError} about
 * {@code a} rather than a complaint about {@code x}. Only a tuple is taken apart positionally: a
 * list, a dict or an {@code int} is one argument however iterable it looks. And a key conversion
 * spends that argument, which is why {@code "%(a)s %s" % d} has nothing left for the {@code %s}
 * while {@code "%s %(a)s" % d} works.
 *
 * Width, precision and the index in "unsupported format character ... at index N" count code points,
 * as they do everywhere else in {@code str}; the presentation half of a numeric conversion is
 * {@link Formats}'s, since CPython's {@code %f} and {@code format(x, 'f')} are the same function.
 * A field wider than a Java {@code String} can hold is the one place the two runtimes can still be
 * told apart: CPython tries to allocate and fails with {@code MemoryError}, and so does this.
 */
final class Percent {

    /** The largest width CPython can hold in a {@code Py_ssize_t}. */
    private static final BigInteger SSIZE = BigInteger.valueOf(Long.MAX_VALUE);

    /** The largest precision CPython can hold in a C {@code int}. */
    private static final BigInteger INT = BigInteger.valueOf(Integer.MAX_VALUE);

    /**
     * One conversion: the flags between the {@code %} and its type character, the value it takes,
     * and the type character itself, which a message names when it cannot be served.
     */
    private static final class Field {

        PyObject value;
        boolean keyed;
        int width = -1;
        int precision = -1;
        boolean left;
        boolean zero;
        boolean plus;
        boolean space;
        boolean alt;
        char type;
    }

    private final String text;
    private final PyObject argument;
    private final PyObject[] positional;
    private final PyDict mapping;
    private final StringBuilder out;

    /** How many positional arguments have been taken. */
    private int at;

    /** Where the parse is, in UTF-16 units, and the code-point index that goes with it. */
    private int cursor;

    private int column;

    private Percent(String text, PyObject argument) {
        this.text = text;
        this.argument = argument;
        this.positional = argument instanceof PyTuple tuple
                ? tuple.elements()
                : new PyObject[] {argument};
        this.mapping = argument instanceof PyDict dict ? dict : null;
        this.out = new StringBuilder(text.length() + 16);
    }

    /** {@code template % argument}. */
    static PyObject format(PyStr template, PyObject argument) {
        return new Percent(template.getValue(), argument).render();
    }

    private PyObject render() {
        int literal = 0;
        while (cursor < text.length()) {
            int code = text.codePointAt(cursor);
            if (code != '%') {
                cursor += Character.charCount(code);
                column++;
                continue;
            }
            out.append(text, literal, cursor);
            cursor++;
            column++;
            conversion();
            literal = cursor;
        }
        out.append(text, literal, text.length());
        if (at < positional.length && !subscriptable(argument)) {
            throw Slots.typeError("not all arguments converted during string formatting");
        }
        return PyStr.of(out.toString());
    }

    /**
     * Whether an argument left over at the end is forgiven. CPython keeps a subscriptable right
     * operand — a dict, a list, {@code bytes} or a range — in an iterator of its own and never
     * counts it, while a {@code str} is carved out of that rule so that {@code "abc" % "x"} still
     * reports the unused argument.
     */
    private static boolean subscriptable(PyObject value) {
        return value instanceof PyDict
                || value instanceof PyList
                || value instanceof PyBytes
                || value instanceof PyRange;
    }

    private void conversion() {
        if (take('%')) {
            out.append('%');
            return;
        }
        Field field = new Field();
        flags(field);
        int width = digits("width");
        if (width >= 0) {
            field.width = width;
        }
        if (take('.')) {
            int precision = take('*') ? star(true) : digits("precision");
            // A missing precision is zero, and a negative one from "*" is zero too.
            field.precision = precision < 0 ? 0 : precision;
        }
        if (!field.keyed) {
            field.value = next();
        }
        int code = peek();
        if (code < 0) {
            throw Slots.valueError("incomplete format");
        }
        skip();
        field.type = (char) code;
        out.append(pad(convert(field), field, numeric(field.type)));
    }

    /** The flags, the mapping key and any {@code *} width, in the order they appear. */
    private void flags(Field field) {
        while (true) {
            int code = peek();
            if (code == '(') {
                skip();
                key(field);
                continue;
            }
            if (code == '*') {
                skip();
                int width = star(false);
                // A negative width from "*" is the left-align flag, which is how CPython spells it.
                field.left |= width < 0;
                field.width = Math.abs(width);
                continue;
            }
            boolean known = true;
            switch (code) {
                case '-' -> field.left = true;
                case '+' -> field.plus = true;
                case ' ' -> field.space = true;
                case '#' -> field.alt = true;
                case '0' -> field.zero = true;
                // CPython accepts the printf length modifiers and ignores them.
                case 'h', 'l', 'L' -> { }
                default -> known = false;
            }
            if (!known) {
                return;
            }
            skip();
        }
    }

    /**
     * The {@code %(name)} form. The key is the literal text up to the closing parenthesis — a
     * {@code %%} inside it stays two percent signs — and it is looked up here, before the rest of the
     * conversion has been read.
     */
    private void key(Field field) {
        if (mapping == null) {
            throw Slots.typeError("format requires a mapping");
        }
        int close = text.indexOf(')', cursor);
        if (close < 0) {
            throw Slots.valueError("incomplete format key");
        }
        String name = text.substring(cursor, close);
        cursor = close + 1;
        column += name.codePointCount(0, name.length()) + 1;
        PyStr key = PyStr.of(name);
        PyObject value = mapping.get(key);
        if (value == null) {
            throw Slots.keyError(key);
        }
        field.value = value;
        field.keyed = true;
        // A mapping conversion leaves nothing for a positional one, which is CPython's order.
        at = positional.length;
    }

    /**
     * A {@code *} width or precision, which CPython reads from the argument list before the value
     * itself. Each half refuses a number it cannot store in its own C type, and only a width is
     * allowed to be negative.
     */
    private int star(boolean precision) {
        BigInteger value = Numbers.asInteger(next());
        if (value == null) {
            throw Slots.typeError("* wants int");
        }
        BigInteger size = value.abs();
        if (precision ? size.compareTo(INT) > 0 : size.compareTo(SSIZE) > 0) {
            throw Slots.overflowError(precision
                    ? "Python int too large to convert to C int"
                    : "Python int too large to convert to C ssize_t");
        }
        if (size.compareTo(INT) > 0) {
            // A field wider than a Java String: CPython asks for the memory and is refused.
            throw PyRaise.raise(ExceptionTypes.MEMORY_ERROR, "");
        }
        return value.signum() < 0 ? -size.intValue() : size.intValue();
    }

    /** The value this conversion takes, or the one a {@code %(name)} key already fetched. */
    private PyObject next() {
        if (at >= positional.length) {
            throw Slots.typeError("not enough arguments for format string");
        }
        return positional[at++];
    }

    private String convert(Field field) {
        PyObject value = field.value;
        return switch (field.type) {
            case 's' -> truncate(Slots.str(value), field.precision);
            case 'r' -> truncate(Slots.repr(value), field.precision);
            case 'a' -> truncate(ascii(Slots.repr(value)), field.precision);
            case 'c' -> truncate(character(value), field.precision);
            case 'd', 'i', 'u', 'o', 'x', 'X', 'e', 'E', 'f', 'F', 'g', 'G' -> number(field, value);
            default -> throw Slots.valueError(
                    "unsupported format character '%s' (0x%x) at index %d",
                    new String(Character.toChars(field.type)),
                    (int) field.type,
                    column - 1);
        };
    }

    /** Whether the {@code 0} flag pads this conversion's field, which only a number accepts. */
    private static boolean numeric(char type) {
        return "diuoxXfFeEgGG".indexOf(type) >= 0;
    }

    /**
     * A numeric conversion, asked of {@link Formats} with the presentation flags and nothing else:
     * the width belongs to {@link #pad}, which counts code points where {@code Formats} counts
     * UTF-16 units.
     *
     * {@code %d} truncates a float toward zero, which {@code %x} refuses; both name the type
     * character they were written with.
     */
    private String number(Field field, PyObject value) {
        char type = field.type;
        PyObject converted = value;
        if ("diuoxX".indexOf(type) >= 0) {
            BigInteger count = Numbers.asInteger(value);
            if (count == null) {
                if ("xXo".indexOf(type) >= 0) {
                    throw Slots.typeError(
                            "%%%s format: an integer is required, not %s",
                            type, value.getType().name());
                }
                converted = PyInt.of(integerFromReal(type, value));
            }
        } else {
            Double real = Numbers.asReal(value);
            if (real == null) {
                throw Slots.typeError("must be real number, not %s", value.getType().name());
            }
            converted = PyFloat.of(real);
        }
        String spec = (field.plus ? "+" : "")
                + (field.alt ? "#" : "")
                + (field.precision >= 0 ? "." + field.precision : "")
                + (type == 'i' || type == 'u' ? "d" : String.valueOf(type));
        String body = Formats.apply(converted, spec);
        if (field.alt && "fFeEgG".indexOf(type) >= 0) {
            body = forcedPoint(body);
        }
        // The space flag is not the mini-language's, which reads a leading space as an alignment.
        if (field.space && !field.plus && !body.startsWith("-")) {
            body = " " + body;
        }
        return body;
    }

    /** {@code %d} and {@code %i} of a float, which CPython truncates and refuses to lie about. */
    private static BigInteger integerFromReal(char type, PyObject value) {
        Double real = Numbers.asReal(value);
        if (real == null) {
            throw Slots.typeError(
                    "%%%s format: a real number is required, not %s", type, value.getType().name());
        }
        if (real.isNaN()) {
            throw Slots.valueError("cannot convert float NaN to integer");
        }
        if (real.isInfinite()) {
            throw Slots.overflowError("cannot convert float infinity to integer");
        }
        return BigDecimal.valueOf(real).toBigInteger();
    }

    /**
     * CPython's alternate form for a float: a decimal separator that survives when the digits have
     * none, {@code "%#.1g"} being {@code '1.'} where Java's own alternate flag is not accepted.
     */
    private static String forcedPoint(String body) {
        if (body.indexOf('.') >= 0) {
            return body;
        }
        int exponent = body.indexOf('e');
        if (exponent < 0) {
            exponent = body.indexOf('E');
        }
        return exponent < 0
                ? body + "."
                : body.substring(0, exponent) + "." + body.substring(exponent);
    }

    /** {@code %c}: a code point given as an integer, or as a one-character string. */
    private static String character(PyObject value) {
        if (value instanceof PyStr text) {
            if (text.codePointLength() != 1) {
                throw Slots.typeError(
                        "%%c requires an int or a unicode character, not a string of length %d",
                        text.codePointLength());
            }
            return text.getValue();
        }
        BigInteger code = Numbers.asInteger(value);
        if (code == null) {
            throw Slots.typeError(
                    "%%c requires an int or a unicode character, not %s", value.getType().name());
        }
        if (!Numbers.fitsInt(code) || code.intValue() < 0 || code.intValue() >= 0x110000) {
            throw Slots.overflowError("%%c arg not in range(0x110000)");
        }
        return new String(Character.toChars(code.intValue()));
    }

    /**
     * {@code ascii()}: what {@code %a} and {@code !a} print — a {@code repr} with the printable
     * non-ASCII code points escaped as well. A surrogate is already escaped by {@code repr} and is
     * left alone, because Python writes it as a backslash-u-d-XXX escape, not as a {@code \U} one.
     */
    static String ascii(String repr) {
        StringBuilder out = new StringBuilder(repr.length());
        for (int i = 0; i < repr.length(); ) {
            int code = repr.codePointAt(i);
            int next = i + Character.charCount(code);
            if (code > 0x7e && !Character.isSurrogate((char) code)) {
                out.append(code < 0x100 ? "\\x" : code < 0x10000 ? "\\u" : "\\U")
                        .append(hex(code, code < 0x100 ? 2 : code < 0x10000 ? 4 : 8));
            } else {
                out.append(repr, i, next);
            }
            i = next;
        }
        return out.toString();
    }

    private static String hex(int code, int digits) {
        String value = Integer.toHexString(code);
        return value.length() >= digits ? value : "0".repeat(digits - value.length()) + value;
    }

    /** A field narrower than the precision it was given, cut at a code-point boundary. */
    private static String truncate(String body, int precision) {
        if (precision < 0) {
            return body;
        }
        int keep = Math.min(precision, body.codePointCount(0, body.length()));
        return body.substring(0, body.offsetByCodePoints(0, keep));
    }

    /**
     * Padding to the field width. Only a numeric conversion takes the {@code 0} flag, and it pads
     * after a leading sign — which is why {@code "%05d" % -42} is {@code '-0042'} and
     * {@code "%05s" % 'a'} is four spaces.
     */
    private static String pad(String body, Field field, boolean numeric) {
        if (field.width < 0) {
            return body;
        }
        int missing = field.width - body.codePointCount(0, body.length());
        if (missing <= 0) {
            return body;
        }
        if (field.left) {
            return body + " ".repeat(missing);
        }
        if (field.zero && numeric) {
            int sign = body.isEmpty() || "+- ".indexOf(body.charAt(0)) < 0 ? 0 : 1;
            return body.substring(0, sign) + "0".repeat(missing) + body.substring(sign);
        }
        return " ".repeat(missing) + body;
    }

    private int peek() {
        return cursor < text.length() ? text.codePointAt(cursor) : -1;
    }

    private boolean take(char expected) {
        if (cursor < text.length() && text.charAt(cursor) == expected) {
            skip();
            return true;
        }
        return false;
    }

    private void skip() {
        cursor += Character.charCount(text.codePointAt(cursor));
        column++;
    }

    /**
     * A run of decimal digits, or -1 when there is none. {@code what} names the thing being counted,
     * because CPython says {@code width too big} and {@code precision too big} rather than one
     * generic overflow for a number of digits no {@code Py_ssize_t} can hold.
     */
    private int digits(String what) {
        long value = 0;
        int start = cursor;
        boolean tooBig = false;
        while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) {
            int digit = text.charAt(cursor) - '0';
            tooBig |= value > (Long.MAX_VALUE - digit) / 10;
            value = value * 10 + digit;
            cursor++;
        }
        if (cursor == start) {
            return -1;
        }
        // Digits are ASCII, so the UTF-16 and code-point distances are the same.
        column += cursor - start;
        if (tooBig) {
            throw Slots.valueError("%s too big", what);
        }
        if (value > Integer.MAX_VALUE) {
            throw PyRaise.raise(ExceptionTypes.MEMORY_ERROR, "");
        }
        return (int) value;
    }
}
