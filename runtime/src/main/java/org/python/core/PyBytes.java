package org.python.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Python's {@code bytes}: an immutable array of octets.
 *
 * Its elements are ints rather than one-byte strings, which is what makes {@code b'a'[0]} an
 * {@code int}, {@code iter(b'ab')} a pair of {@code 97} and {@code 98}, and a slice another bytes.
 *
 * Codecs are a short, deliberate list. Java's own charsets cover UTF-8, UTF-16 and latin-1, which is
 * everything the marshal reader and ordinary scripts need; a refused encoding prints CPython's
 * {@code LookupError} rather than guessing at one this runtime does not have.
 */
public final class PyBytes extends PyObject {

    public static final PyBytes EMPTY = new PyBytes(new byte[0]);

    /** The codecs this slice answers, under their hyphen- and case-free names. */
    private static final Set<String> CODECS =
            Set.of("utf8", "utf8sig", "ascii", "latin1", "utf16", "utf16le", "utf16be");

    private final byte[] value;

    private PyBytes(byte[] value) {
        super(PyType.BYTES);
        this.value = value;
    }

    public static PyBytes of(byte[] value) {
        return value.length == 0 ? EMPTY : new PyBytes(value.clone());
    }

    /** Without copying: for literals read straight out of a marshal stream. */
    public static PyBytes wrap(byte[] value) {
        return value.length == 0 ? EMPTY : new PyBytes(value);
    }

    public byte[] getValue() {
        return value.clone();
    }

    public int size() {
        return value.length;
    }

    @Override
    public PyObject length() {
        return PyInt.of(value.length);
    }

    // --- codecs ------------------------------------------------------------------------

    /**
     * {@code s.encode(encoding, errors)}.
     *
     * The position CPython reports is a code-point index while a Java string is a UTF-16 array, so the
     * {@code ascii} test walks code points and counts them. The substitution handlers are M4: an
     * encoding this runtime has but a mode it does not is refused rather than silently made strict.
     */
    public static PyBytes encodeText(String text, String encoding, String errors) {
        String name = codec(encoding);
        requireStrict(errors, "str.encode");
        return switch (name) {
            case "utf8" -> PyBytes.wrap(PyHash.encodeUtf8(text));
            case "utf8sig" -> PyBytes.wrap(concat(
                    new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, PyHash.encodeUtf8(text)));
            case "ascii" -> {
                int at = firstNonAscii(text);
                if (at >= 0) {
                    throw Slots.unicodeEncode(
                            "'ascii' codec can't encode character "
                                    + describe(text.codePointAt(at))
                                    + " in position " + text.codePointCount(0, at)
                                    + ": ordinal not in range(128)");
                }
                yield PyBytes.of(text.getBytes(StandardCharsets.US_ASCII));
            }
            case "latin1" -> {
                byte[] out = new byte[text.length()];
                for (int i = 0; i < out.length; i++) {
                    if (text.charAt(i) > 0xff) {
                        throw Slots.unicodeEncode(
                                "'latin-1' codec can't encode character "
                                        + describe(text.charAt(i))
                                        + " in position " + i + ": ordinal not in range(256)");
                    }
                    out[i] = (byte) text.charAt(i);
                }
                yield PyBytes.wrap(out);
            }
            case "utf16" -> PyBytes.wrap(concat(
                    new byte[] {(byte) 0xff, (byte) 0xfe},
                    text.getBytes(StandardCharsets.UTF_16LE)));
            case "utf16be" -> PyBytes.of(text.getBytes(StandardCharsets.UTF_16BE));
            default -> PyBytes.of(text.getBytes(StandardCharsets.UTF_16LE));
        };
    }

    /** {@code b.decode(encoding, errors)}. */
    public String decodeText(String encoding, String errors) {
        String name = codec(encoding);
        requireStrict(errors, "bytes.decode");
        return switch (name) {
            case "utf8" -> new String(value, StandardCharsets.UTF_8);
            case "utf8sig" -> new String(
                    value.length >= 3 ? Arrays.copyOfRange(value, 3, value.length) : value,
                    StandardCharsets.UTF_8);
            case "ascii" -> {
                for (int i = 0; i < value.length; i++) {
                    if ((value[i] & 0xff) > 0x7f) {
                        throw Slots.unicodeDecode(
                                "'ascii' codec can't decode byte 0x"
                                        + String.format("%02x", value[i] & 0xff)
                                        + " in position " + i + ": ordinal not in range(128)");
                    }
                }
                yield new String(value, StandardCharsets.US_ASCII);
            }
            case "latin1" -> {
                StringBuilder out = new StringBuilder(value.length);
                for (byte b : value) {
                    out.append((char) (b & 0xff));
                }
                yield out.toString();
            }
            case "utf16be" -> new String(value, StandardCharsets.UTF_16BE);
            default -> new String(value, StandardCharsets.UTF_16);
        };
    }

    private static String codec(String encoding) {
        String name = encoding.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        if (!CODECS.contains(name)) {
            throw Slots.lookupError("unknown encoding: " + encoding);
        }
        return name;
    }

    private static void requireStrict(String errors, String what) {
        if (!"strict".equals(errors)) {
            throw Slots.notImplemented(what + " error handler " + errors);
        }
    }

    private static int firstNonAscii(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp > 0x7f) {
                return i;
            }
            i += Character.charCount(cp);
        }
        return -1;
    }

    /** How CPython names a character in a codec error: {@code '\xe9'} in the BMP, {@code U+1f600} above. */
    private static String describe(int codePoint) {
        if (codePoint < 0x100) {
            return String.format("'\\x%02x'", codePoint);
        }
        return codePoint <= 0xffff
                ? String.format("'\\u%04x'", codePoint)
                : String.format("U+%04x", codePoint);
    }

    private static byte[] concat(byte[] head, byte[] body) {
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    // --- sequence protocol -------------------------------------------------------------

    /** An index selects one octet and so answers with an int; a slice answers with a bytes. */
    @Override
    public PyObject getItem(PyObject key) {
        if (Slices.isSlice(key)) {
            Slices.Range range = Slices.of((PySlice) key, value.length);
            byte[] out = new byte[range.count()];
            for (int i = 0; i < out.length; i++) {
                out[i] = value[range.at(i)];
            }
            return PyBytes.wrap(out);
        }
        return PyInt.of(
                value[Slices.index(key, value.length, "byte", "index out of range")] & 0xff);
    }

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD && other instanceof PyBytes b) {
            return PyBytes.wrap(concat(value, b.value));
        }
        if (op == BinaryOp.MUL) {
            return PyBytes.wrap(repeated(other));
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.MUL ? binaryOp(op, other) : PyNotImplemented.INSTANCE;
    }

    /** {@code b * n} repeats octets, not the ints its elements read as. */
    private byte[] repeated(PyObject times) {
        BigInteger count = Numbers.asInteger(times);
        if (count == null) {
            throw Slots.typeError(
                    "can't multiply sequence by non-int of type '%s'", times.getType().name());
        }
        if (count.signum() < 0) {
            return new byte[0];
        }
        if (!Numbers.fitsInt(count)) {
            throw Slots.overflowError("cannot fit 'int' into an index-sized integer");
        }
        byte[] out = new byte[value.length * count.intValue()];
        for (int at = 0; at < out.length; ) {
            for (byte b : value) {
                out[at++] = b;
            }
        }
        return out;
    }

    /** {@code x in b}, where {@code x} is either one octet or a run of them. */
    @Override
    public PyObject contains(PyObject item) {
        if (item instanceof PyBytes sub) {
            return PyBool.of(indexOf(sub.value) >= 0);
        }
        BigInteger wanted = Numbers.asInteger(item);
        if (wanted == null) {
            throw Slots.typeError(
                    "a bytes-like object is required, not '%s'", item.getType().name());
        }
        int at = wanted.intValue();
        return PyBool.of(at >= 0 && at <= 0xff && indexOf(new byte[] {(byte) at}) >= 0);
    }

    private int indexOf(byte[] part) {
        outer:
        for (int i = 0; i + part.length <= value.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (value[i + j] != part[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Override
    public PyObject iterator() {
        PyObject[] elements = new PyObject[value.length];
        for (int i = 0; i < elements.length; i++) {
            elements[i] = PyInt.of(value[i] & 0xff);
        }
        return PyIterator.of(elements);
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyBytes b
                ? PyObject.ordered(cmp, Arrays.compare(value, b.value))
                : super.richCompare(cmp, other);
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    // --- methods -----------------------------------------------------------------------

    private static final String[] CODE_NAMES = {"encoding", "errors"};
    private static final PyObject[] CODE_DEFAULTS = {PyStr.of("utf-8"), PyStr.of("strict")};

    private static final Members METHODS = Members.of(PyType.BYTES)
            .add("decode", (self, args, kwNames) -> {
                Arguments.between("decode", args, kwNames, 0, 2);
                PyObject[] parameters =
                        Arguments.fill("bytes.decode", args, kwNames, CODE_NAMES, CODE_DEFAULTS);
                return PyStr.of(((PyBytes) self).decodeText(
                        Slots.str(parameters[0]), Slots.str(parameters[1])));
            })
            .addNoArgument("hex", self -> {
                StringBuilder out = new StringBuilder(((PyBytes) self).value.length * 2);
                for (byte b : ((PyBytes) self).value) {
                    out.append(String.format("%02x", b & 0xff));
                }
                return PyStr.of(out.toString());
            })
            .addNoArgument("upper", self -> fold(self, true))
            .addNoArgument("lower", self -> fold(self, false))
            .addOne("count", (self, sub) ->
                    PyInt.of(((PyBytes) self).occurrences(octets(sub, "count"))))
            .add("startswith", (self, args, kwNames) -> edge(self, args, kwNames, "startswith", true))
            .add("endswith", (self, args, kwNames) -> edge(self, args, kwNames, "endswith", false))
            .addOne("join", (self, parts) -> {
                PyBytes separator = (PyBytes) self;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                PyIterator iterator = Slots.iterator(parts);
                PyObject item;
                boolean first = true;
                int at = 0;
                while ((item = iterator.nextOrNull()) != null) {
                    if (!(item instanceof PyBytes element)) {
                        throw Slots.typeError(
                                "sequence item %d: expected bytes, %.200s found",
                                at, item.getType().name());
                    }
                    if (!first) {
                        out.writeBytes(separator.value);
                    }
                    out.writeBytes(element.value);
                    first = false;
                    at++;
                }
                return PyBytes.wrap(out.toByteArray());
            });

    /** ASCII case folding, which is all of what {@code bytes.upper()} means. */
    private static PyBytes fold(PyObject self, boolean upper) {
        byte[] in = ((PyBytes) self).value;
        byte[] out = new byte[in.length];
        for (int i = 0; i < out.length; i++) {
            int b = in[i] & 0xff;
            out[i] = (byte) (upper ? Character.toUpperCase(b) : Character.toLowerCase(b));
        }
        return PyBytes.wrap(out);
    }

    /** An empty needle is found once in every gap, so {@code b.count(b'')} is {@code len + 1}. */
    private int occurrences(byte[] sub) {
        if (sub.length == 0) {
            return value.length + 1;
        }
        int found = 0;
        for (int i = 0; i <= value.length - sub.length; ) {
            if (matchesAt(i, sub)) {
                found++;
                i += sub.length;
            } else {
                i++;
            }
        }
        return found;
    }

    private boolean matchesAt(int from, byte[] part) {
        for (int j = 0; j < part.length; j++) {
            if (value[from + j] != part[j]) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code startswith} and {@code endswith}: one bytes or a tuple of them, with an optional
     * {@code start} and {@code stop}.
     *
     * CPython type-checks the whole tuple before testing any of it, so
     * {@code b'ab'.startswith((b'a', 'x'))} is a {@code TypeError} even though the first element
     * matches.
     */
    private static PyObject edge(PyObject self, PyObject[] args, String[] kwNames, String method,
            boolean prefix) {
        Arguments.between(method, args, kwNames, 1, 3);
        byte[] text = ((PyBytes) self).value;
        int start = args.length > 1 ? Sequences.searchStart(args[1], text.length) : 0;
        int stop = args.length > 2 ? Sequences.searchStop(args[2], text.length) : text.length;
        byte[] window = Arrays.copyOfRange(text, start, Math.max(start, stop));
        if (args[0] instanceof PyTuple candidates) {
            byte[][] parts = new byte[candidates.size()][];
            for (int i = 0; i < parts.length; i++) {
                parts[i] = octets(candidates.at(i), method);
            }
            for (byte[] part : parts) {
                if (matches(window, part, prefix)) {
                    return PyBool.TRUE;
                }
            }
            return PyBool.FALSE;
        }
        return PyBool.of(matches(window, octets(args[0], method), prefix));
    }

    private static boolean matches(byte[] text, byte[] part, boolean prefix) {
        if (part.length > text.length) {
            return false;
        }
        for (int i = 0; i < part.length; i++) {
            if (text[prefix ? i : text.length - part.length + i] != part[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] octets(PyObject value, String method) {
        if (value instanceof PyBytes b) {
            return b.value;
        }
        throw Slots.typeError(
                "%s first arg must be bytes or a tuple of bytes, not %s",
                method, value.getType().name());
    }

    @Override
    public long hash() {
        return PyHash.bytesHash(value);
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyBytes b && Arrays.equals(value, b.value);
    }

    @Override
    public String repr() {
        return PyRepr.quoteBytes(value);
    }
}
