package org.python.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * {@code bytes.__mod__} and {@code bytearray.__mod__}: PEP 461 formatting.
 *
 * <p>This is CPython's {@code bytes_format} rather than {@code PyUnicode_Format}, and the two differ
 * in ways a reader can see. The conversions are octets first — {@code %s} and {@code %b} want a
 * bytes-like object or a {@code __bytes__}, {@code %a} and {@code %r} write the ASCII form of a
 * repr, and {@code %c} takes a byte rather than a code point — while the numeric conversions share
 * the string formatter's implementation and so share its answers down to the messages.
 *
 * <p>The argument model is the source's. A tuple is a list of positional values that all have to be
 * used. Anything else is a single value, used by at most one conversion, unless the conversion is
 * keyed: a keyed conversion looks its key up in the mapping and re-arms that one slot, which is why
 * {@code b'%(k)s %(k)s' % {b'k': b'v'}} works while {@code b'%(k)s %s' % {b'k': b'v'}} runs out of
 * arguments. Whether the argument can be looked up at all is decided from its type once, and a
 * tuple, {@code bytes}, {@code bytearray} or {@code str} is refused with "format requires a
 * mapping" rather than asked for a subscript.
 *
 * <p>The order of the refusals is the source's too, and it is observable: a key is looked up as soon
 * as it is read, before any flag is parsed, so a missing key outranks a bad conversion; the argument
 * for a conversion is consumed before the conversion character is looked at, so {@code b'%5%' % ()}
 * complains of missing arguments rather than of an unsupported {@code %}; and a format that ends
 * before its conversion character is "incomplete format" before any argument is touched.
 */
final class OctetFormat {

    private final byte[] template;
    private final PyObject argument;
    private final PyObject[] positional;
    private final boolean tuple;
    private final boolean mapping;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private PyObject armed;
    private boolean armedValue;
    private int next;
    private int at;

    private OctetFormat(byte[] template, PyObject argument) {
        this.template = template;
        this.argument = argument;
        if (argument instanceof PyTuple values) {
            this.positional = values.getArray();
            this.tuple = true;
            this.mapping = false;
        } else {
            this.positional = null;
            this.tuple = false;
            this.mapping = isMapping(argument);
            this.armed = argument;
            this.armedValue = true;
        }
    }

    /** The formatted octets, or the first refusal the template earns. */
    static byte[] format(byte[] template, PyObject argument) {
        return new OctetFormat(template, argument).run();
    }

    /**
     * Whether the argument has a subscript to look a key up with: CPython's
     * {@code tp_as_mapping && mp_subscript}, with the four types it refuses even though they have one.
     */
    private static boolean isMapping(PyObject value) {
        if (value instanceof PyBytes
                || value instanceof PyByteArray
                || value instanceof PyStr) {
            return false;
        }
        return value instanceof PyDict
                || value instanceof PyList
                || Specials.member(value.getType(), "__getitem__") != null;
    }

    private byte[] run() {
        while (at < template.length) {
            int octet = template[at++] & 0xff;
            if (octet != '%') {
                out.write(octet);
                continue;
            }
            if (at < template.length && template[at] == '%') {
                at++;
                out.write('%');
                continue;
            }
            conversion();
        }
        if (tuple && next < positional.length) {
            throw Slots.typeError("not all arguments converted during bytes formatting");
        }
        if (!tuple && !mapping && armedValue) {
            throw Slots.typeError("not all arguments converted during bytes formatting");
        }
        return out.toByteArray();
    }

    /** One conversion specifier, with the repeating literal decreed by {@link #run} already spent. */
    private void conversion() {
        PyObject keyed = null;
        if (at < template.length && template[at] == '(') {
            if (!mapping) {
                throw Slots.typeError("format requires a mapping");
            }
            keyed = keyed();
        }
        boolean left = false;
        boolean zero = false;
        boolean plus = false;
        boolean space = false;
        boolean alternate = false;
        while (at < template.length) {
            int octet = template[at];
            if (octet == '-') {
                left = true;
            } else if (octet == '0') {
                zero = true;
            } else if (octet == '+') {
                plus = true;
            } else if (octet == ' ') {
                space = true;
            } else if (octet == '#') {
                alternate = true;
            } else {
                break;
            }
            at++;
        }
        int width = -1;
        if (at < template.length && template[at] == '*') {
            at++;
            long asked = Numbers.asSsizeT(starred());
            if (asked < 0) {
                left = true;
                if (asked == Long.MIN_VALUE) {
                    throw Slots.overflowError("width too big");
                }
                asked = -asked;
            }
            width = (int) Math.min(asked, Integer.MAX_VALUE);
        } else {
            width = (int) Math.min(digits("width too big", -1, Long.MAX_VALUE),
                    Integer.MAX_VALUE);
        }
        int precision = -1;
        if (at < template.length && template[at] == '.') {
            at++;
            if (at < template.length && template[at] == '*') {
                at++;
                precision = Numbers.cInteger(starred());
                if (precision < 0) {
                    precision = 0;
                }
            } else {
                precision = (int) digits("prec too big", 0, Integer.MAX_VALUE);
            }
        }
        // CPython accepts one length modifier and ignores it, so the character after it is the
        // conversion — which is why b'%lld' complains of 'l' rather than of 'd'.
        if (at < template.length
                && (template[at] == 'h' || template[at] == 'l' || template[at] == 'L')) {
            at++;
        }
        if (at >= template.length) {
            throw Slots.valueError("incomplete format");
        }
        if (keyed != null) {
            armed = keyed;
            armedValue = true;
        }
        PyObject value = take();
        char code = (char) (template[at++] & 0xff);
        switch (code) {
            case 's', 'b' -> emit(padded(octets(value), width, precision, left));
            case 'a', 'r' ->
                    emit(padded(
                            Percent.ascii(Slots.repr(value)).getBytes(StandardCharsets.US_ASCII),
                            width, precision, left));
            case 'c' -> emit(padded(character(value), width, -1, left));
            case 'd', 'i', 'u', 'o', 'x', 'X', 'e', 'E', 'f', 'F', 'g', 'G' ->
                    emit(numeric(code, value, width, precision, left, zero, plus, space,
                            alternate));
            default -> unsupported(code);
        }
    }

    /**
     * {@code %(name)}: the key is every octet between the parentheses, nesting counted, and the
     * lookup happens now — CPython reads the key before the flags and calls
     * {@code PyObject_GetItem} as soon as the closing parenthesis is seen.
     */
    private PyObject keyed() {
        int start = ++at;
        int depth = 1;
        while (at < template.length) {
            int octet = template[at++];
            if (octet == '(') {
                depth++;
            } else if (octet == ')' && --depth == 0) {
                return argument.getItem(
                        PyBytes.wrap(Arrays.copyOfRange(template, start, at - 1)));
            }
        }
        throw Slots.valueError("incomplete format key");
    }

    /** The value a {@code *} reads, which has to be an int or a bool and nothing else. */
    private PyObject starred() {
        PyObject value = take();
        if (Numbers.asInteger(value) == null) {
            throw Slots.typeError("* wants int");
        }
        return value;
    }

    /** One positional value: the tuple's next, or the single argument the mapping mode re-arms. */
    private PyObject take() {
        if (tuple) {
            if (next >= positional.length) {
                throw Slots.typeError("not enough arguments for format string");
            }
            return positional[next++];
        }
        if (!armedValue) {
            throw Slots.typeError("not enough arguments for format string");
        }
        armedValue = false;
        return armed;
    }

    /**
     * A width or precision written in the template, or {@code absent} when no digit is present.
     *
     * <p>CPython reads the width into a {@code Py_ssize_t} and the precision into a C {@code int}, and
     * either parser complains about the field rather than about the number once the digits would not
     * fit — a precision therefore tops out at {@code 2147483647} while a width goes on to 2**63 - 1.
     * A precision written as a bare {@code .} is zero rather than absent.
     */
    private long digits(String tooBig, long absent, long limit) {
        int start = at;
        while (at < template.length && template[at] >= '0' && template[at] <= '9') {
            at++;
        }
        if (at == start) {
            return absent;
        }
        long number = 0;
        for (int i = start; i < at; i++) {
            int digit = template[i] - '0';
            if (number > (limit - digit) / 10) {
                throw Slots.valueError(tooBig);
            }
            number = number * 10 + digit;
        }
        return number;
    }

    /** {@code format_obj}: the octets of a bytes-like value, or of what {@code __bytes__} answers. */
    private static byte[] octets(PyObject value) {
        if (value instanceof PyBytes bytes) {
            return bytes.getValue();
        }
        if (value instanceof PyByteArray bytes) {
            return Arrays.copyOf(bytes.raw(), bytes.size());
        }
        PyObject answer = Specials.invoke(value, "__bytes__");
        if (answer != Slots.DECLINED) {
            if (answer instanceof PyBytes bytes) {
                return bytes.getValue();
            }
            throw Slots.typeError(
                    "__bytes__ returned non-bytes (type %s)", answer.getType().filedName());
        }
        throw Slots.typeError(
                "%%b requires a bytes-like object, or an object that implements __bytes__, not '%s'",
                value.getType().filedName());
    }

    /**
     * {@code byte_converter}: one octet, from a one-octet sequence or from an index in range.
     *
     * <p>The range is checked after the index is read and is the refusal for every number outside
     * it, however wide — CPython reaches it with a saturated conversion, so {@code 2 ** 70} is the
     * same complaint as {@code 256}.
     */
    private static byte[] character(PyObject value) {
        if (value instanceof PyBytes bytes) {
            if (bytes.size() != 1) {
                throw Slots.typeError(
                        "%%c requires an integer in range(256) or a single byte, not a bytes object"
                                + " of length %d",
                        bytes.size());
            }
            return new byte[] {(byte) bytes.byteAt(0)};
        }
        if (value instanceof PyByteArray bytes) {
            if (bytes.size() != 1) {
                throw Slots.typeError(
                        "%%c requires an integer in range(256) or a single byte, not a bytearray"
                                + " object of length %d",
                        bytes.size());
            }
            return new byte[] {(byte) bytes.byteAt(0)};
        }
        if (!Numbers.isIndex(value)) {
            throw Slots.typeError(
                    "%%c requires an integer in range(256) or a single byte, not %s",
                    value.getType().filedName());
        }
        BigInteger number = Numbers.asIndex(value);
        if (number.signum() < 0 || number.compareTo(BigInteger.valueOf(256)) >= 0) {
            throw Slots.overflowError("%%c arg not in range(256)");
        }
        return new byte[] {(byte) number.intValue()};
    }

    /**
     * A numeric conversion, handed to the string formatter with the flags and the digits as written.
     *
     * <p>CPython's bytes formatter and its string formatter share {@code formatlong} and
     * {@code formatfloat}, so the padding, the alternate form and the truncation {@code %d} gives a
     * float are the same; what differs is only the surface, an octet string in place of a str. The
     * float side is converted first so that its complaint is the bytes one — {@code float argument
     * required, not str} — rather than {@code %}'s "must be real number".
     */
    private static byte[] numeric(
            char code,
            PyObject value,
            int width,
            int precision,
            boolean left,
            boolean zero,
            boolean plus,
            boolean space,
            boolean alternate) {
        PyObject asked = value;
        if ("eEfFgG".indexOf(code) >= 0) {
            Double real = Numbers.floatOf(value);
            if (real == null) {
                throw Slots.typeError(
                        "float argument required, not %s", value.getType().filedName());
            }
            asked = PyFloat.of(real);
        }
        StringBuilder spec = new StringBuilder("%");
        if (left) {
            spec.append('-');
        }
        if (zero) {
            spec.append('0');
        }
        if (plus) {
            spec.append('+');
        }
        if (space) {
            spec.append(' ');
        }
        if (alternate) {
            spec.append('#');
        }
        if (width >= 0) {
            spec.append(width);
        }
        if (precision >= 0) {
            spec.append('.').append(precision);
        }
        spec.append(code);
        PyObject formatted = Percent.format(PyStr.of(spec.toString()), asked);
        return ((PyStr) formatted).getValue().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The field a converted body occupies: the precision cuts it to that many octets, and the width
     * pads it on the side the {@code -} flag names, with spaces — the {@code 0} flag never reaches
     * a string conversion.
     */
    private static byte[] padded(byte[] body, int width, int precision, boolean left) {
        if (precision >= 0 && body.length > precision) {
            body = Arrays.copyOf(body, precision);
        }
        if (width <= body.length) {
            return body;
        }
        byte[] field = new byte[width];
        Arrays.fill(field, (byte) ' ');
        if (left) {
            System.arraycopy(body, 0, field, 0, body.length);
        } else {
            System.arraycopy(body, 0, field, width - body.length, body.length);
        }
        return field;
    }

    private void emit(byte[] body) {
        out.write(body, 0, body.length);
    }

    /**
     * The conversion character CPython's default case names: one whose signed value is negative is
     * reported as unprintable, the rest as itself and its index in the template.
     */
    private void unsupported(char code) {
        if (code > 0x7f) {
            throw Slots.overflowError("character argument not in range(0x110000)");
        }
        throw Slots.valueError(
                "unsupported format character '%c' (0x%x) at index %d",
                code, (int) code, at - 1);
    }
}
