package org.python.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Python's {@code bytes}: an immutable array of octets.
 *
 * Its elements are ints rather than one-byte strings, which is what makes {@code b'a'[0]} an
 * {@code int}, {@code iter(b'ab')} a pair of {@code 97} and {@code 98}, and a slice another bytes.
 *
 * The methods read octets, not characters: their whitespace is the six ASCII spaces, their line
 * breaks are {@code \n}, {@code \r} and the pair between them, and their case folds a to z — which
 * is why {@code b'\xff'.upper()} is still {@code b'\xff'} where {@code chr(255).upper()} is not.
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

    /**
     * {@code x in b}, where {@code x} is either one octet or a run of them.
     *
     * The two readings have different refusals, and both are measured: an octet out of range is a
     * {@code ValueError} about its value, while anything that is neither an integer nor a run of
     * octets is refused as the missing buffer — so {@code 300 in b''} complains about the range and
     * {@code 2.0 in b''} about the float.
     */
    @Override
    public PyObject contains(PyObject item) {
        BigInteger wanted = Numbers.asInteger(item);
        byte[] needle = wanted == null ? run(item) : octet(wanted);
        return PyBool.of(first(value, needle, 0, value.length, false) >= 0);
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

    /** {@code split}'s two parameters, which CPython lets a caller name but never refuses by name. */
    private static final String[] SEPARATOR = {"sep", "maxsplit"};
    private static final PyObject[] SEPARATOR_DEFAULTS = {PyNone.INSTANCE, PyInt.of(-1)};

    private static final String[] KEEP_ENDS = {"keepends"};
    private static final PyObject[] KEEP_ENDS_DEFAULTS = {PyBool.FALSE};

    private static final BigInteger OCTET_MAX = BigInteger.valueOf(255);

    private static final Members METHODS = Members.of(PyType.BYTES)
            .add("count", (self, args, kwNames) -> {
                legacy("count", args, kwNames, 1, 3);
                byte[] text = octets(self);
                int from = args.length > 1 ? Sequences.searchFrom(args[1], text.length) : 0;
                int to = args.length > 2 ? Sequences.searchStop(args[2], text.length) : text.length;
                return PyInt.of(occurrences(text, octetOrRun(args[0]), from, to));
            })
            .add("decode", (self, args, kwNames) -> {
                Arguments.between("decode", args, kwNames, 0, 2);
                PyObject[] parameters =
                        Arguments.fill("bytes.decode", args, kwNames, CODE_NAMES, CODE_DEFAULTS);
                return PyStr.of(((PyBytes) self).decodeText(
                        Slots.str(parameters[0]), Slots.str(parameters[1])));
            })
            .add("endswith", (self, args, kwNames) -> edge(self, args, kwNames, "endswith", false))
            .add("find", (self, args, kwNames) -> search(self, args, kwNames, "find", false, false))
            .addNoArgument("hex", self -> PyStr.of(hexadecimal(octets(self))))
            .add("index", (self, args, kwNames) -> search(self, args, kwNames, "index", false, true))
            .addOne("join", (self, parts) -> joined(octets(self), parts))
            .add("lstrip", (self, args, kwNames) -> trim(self, args, kwNames, "lstrip", true, false))
            .addNoArgument("lower", self -> PyBytes.wrap(cased(octets(self), false)))
            .addOne("partition", (self, argument) -> divided(self, octets(self), argument, true))
            .addOne("removeprefix", (self, argument) -> shortened(self, argument, true))
            .addOne("removesuffix", (self, argument) -> shortened(self, argument, false))
            .add("replace", (self, args, kwNames) -> {
                legacy("replace", args, kwNames, 2, 3);
                byte[] text = octets(self);
                byte[] old = run(args[0]);
                byte[] replacement = run(args[1]);
                long count = args.length > 2 ? Numbers.size(args[2]) : -1;
                return count == 0 || first(text, old, 0, text.length, false) < 0
                        ? self
                        : PyBytes.wrap(exchanged(text, old, replacement, count));
            })
            .add("rfind", (self, args, kwNames) -> search(self, args, kwNames, "rfind", true, false))
            .add("rindex", (self, args, kwNames) -> search(self, args, kwNames, "rindex", true, true))
            .addOne("rpartition", (self, argument) -> divided(self, octets(self), argument, false))
            .add("rsplit", (self, args, kwNames) -> split(self, args, kwNames, "rsplit", true))
            .add("rstrip", (self, args, kwNames) -> trim(self, args, kwNames, "rstrip", false, true))
            .add("split", (self, args, kwNames) -> split(self, args, kwNames, "split", false))
            .add("splitlines", (self, args, kwNames) -> lined(
                    octets(self),
                    Slots.truth(Arguments.parameters(
                            "splitlines", args, kwNames, KEEP_ENDS, KEEP_ENDS_DEFAULTS, 0)[0])))
            .add("startswith", (self, args, kwNames) -> edge(self, args, kwNames, "startswith", true))
            .add("strip", (self, args, kwNames) -> trim(self, args, kwNames, "strip", true, true))
            .addNoArgument("upper", self -> PyBytes.wrap(cased(octets(self), true)));

    // --- argument helpers ------------------------------------------------------------------

    /**
     * The methods that refuse a keyword before anything else: the same two families {@code str} is
     * measured with, one naming itself {@code bytes.find()} at the call site and the other {@code find}
     * bare inside the body. See {@link Arguments} for why the counts differ between them.
     */
    private static void legacy(
            String name, PyObject[] args, String[] kwNames, int atLeast, int atMost) {
        Arguments.noKeywords("bytes." + name + "()", kwNames);
        Arguments.between(name, args, kwNames, atLeast, atMost);
    }

    /**
     * A run of octets, which is what {@code split}, {@code strip}, {@code replace} and the rest of the
     * cut-and-search family are pointed at.
     *
     * <p>The refusal is the buffer protocol's own, with the type quoted. The methods that also read a
     * bare octet refuse in other words — see {@link #octetOrRun} — and {@code startswith} refusing its
     * first argument names itself instead of the protocol, so no one converter fits all three.
     */
    private static byte[] run(PyObject value) {
        if (value instanceof PyBytes b) {
            return b.value;
        }
        throw Slots.typeError("a bytes-like object is required, not '%s'", value.getType().name());
    }

    /** The needle of {@code find}, {@code index}, {@code count} and their mirrors: an octet or a run. */
    private static byte[] octetOrRun(PyObject value) {
        if (value instanceof PyBytes b) {
            return b.value;
        }
        BigInteger wanted = Numbers.asInteger(value);
        if (wanted == null) {
            throw Slots.typeError(
                    "argument should be integer or bytes-like object, not '%s'",
                    value.getType().name());
        }
        return octet(wanted);
    }

    private static byte[] octet(BigInteger wanted) {
        if (wanted.signum() < 0 || wanted.compareTo(OCTET_MAX) > 0) {
            // Read as an octet and refused as one, however far the number reaches.
            throw Slots.valueError("byte must be in range(0, 256)");
        }
        return new byte[] {wanted.byteValue()};
    }

    private static byte[] octets(PyObject self) {
        return ((PyBytes) self).value;
    }

    // --- method bodies ---------------------------------------------------------------------

    /**
     * {@code find}, {@code index} and their right-facing mirrors.
     *
     * <p>The bounds are read before the needle, which is why {@code b'ab'.find('x', 'y')} complains
     * about slice indices while {@code b'ab'.find('x', 1)} complains about the needle. A miss is -1
     * for {@code find} and {@code ValueError: subsection not found} for its loud twin — including when
     * the needle was a bare octet, which is reported the same way.
     */
    private static PyObject search(PyObject self, PyObject[] args, String[] kwNames, String name,
            boolean right, boolean raises) {
        legacy(name, args, kwNames, 1, 3);
        byte[] text = octets(self);
        int from = args.length > 1 ? Sequences.searchFrom(args[1], text.length) : 0;
        int to = args.length > 2 ? Sequences.searchStop(args[2], text.length) : text.length;
        int found = first(text, octetOrRun(args[0]), from, to, right);
        if (found < 0 && raises) {
            throw Slots.valueError("subsection not found");
        }
        return PyInt.of(found);
    }

    /**
     * {@code startswith} and {@code endswith}: one run or a tuple of them, over a range.
     *
     * <p>The bounds come first, then the needle. A tuple is walked until something matches, so
     * {@code b'ab'.startswith((b'a', 'x'))} is {@code True} while {@code ('x', b'a')} is refused — and
     * every element is converted on the way to the one answer a range that is not one gives, so
     * {@code b'ab'.startswith(('x', 'y'), 2, 1)} still names the str.
     */
    private static PyObject edge(PyObject self, PyObject[] args, String[] kwNames, String name,
            boolean prefix) {
        legacy(name, args, kwNames, 1, 3);
        byte[] text = octets(self);
        int from = args.length > 1 ? Sequences.searchFrom(args[1], text.length) : 0;
        int to = args.length > 2 ? Sequences.searchStop(args[2], text.length) : text.length;
        // Not even the empty prefix matches a range that is not one.
        boolean none = from > to;
        if (args[0] instanceof PyTuple candidates) {
            for (PyObject candidate : candidates.elements()) {
                byte[] part = run(candidate);
                if (!none && matches(text, from, to, part, prefix)) {
                    return PyBool.TRUE;
                }
            }
            return PyBool.FALSE;
        }
        byte[] single = firstArgument(args[0], name);
        return PyBool.of(!none && matches(text, from, to, single, prefix));
    }

    /**
     * {@code startswith}'s first argument when it is not a tuple, refused in the method's own words:
     * the tuple's elements are refused by the buffer protocol, this one names the method and leaves
     * the type unquoted.
     */
    private static byte[] firstArgument(PyObject value, String name) {
        if (value instanceof PyBytes b) {
            return b.value;
        }
        throw Slots.typeError(
                "%s first arg must be bytes or a tuple of bytes, not %s",
                name, value.getType().name());
    }

    /**
     * {@code split} and {@code rsplit}: a separator or none at all, and a count.
     *
     * <p>The count is read before the separator — {@code b'ab'.split('x', 'y')} is refused as an
     * integer, not as a run of octets — and it is a size rather than an index, so a count past every
     * cut there is does not overflow until it passes a machine word.
     */
    private static PyObject split(PyObject self, PyObject[] args, String[] kwNames, String name,
            boolean right) {
        PyObject[] parameters =
                Arguments.parameters(name, args, kwNames, SEPARATOR, SEPARATOR_DEFAULTS, 0);
        long maxsplit = Numbers.size(parameters[1]);
        byte[] separator = parameters[0] == PyNone.INSTANCE ? null : run(parameters[0]);
        return pieces(octets(self), separator, maxsplit, right);
    }

    /** {@code strip}, {@code lstrip} and {@code rstrip}: an alphabet of octets, or none. */
    private static PyObject trim(
            PyObject self, PyObject[] args, String[] kwNames, String name, boolean left, boolean right) {
        legacy(name, args, kwNames, 0, 1);
        byte[] characters = args.length > 0 && args[0] != PyNone.INSTANCE ? run(args[0]) : null;
        byte[] text = octets(self);
        int[] edges = trimmed(text, characters, left, right);
        return edges[0] == 0 && edges[1] == text.length
                ? self
                : PyBytes.wrap(Arrays.copyOfRange(text, edges[0], edges[1]));
    }

    /** {@code separator.join(iterable)}: a run for every item, and nothing else. */
    private static PyObject joined(byte[] separator, PyObject parts) {
        if (parts.iterator() == PyNotImplemented.INSTANCE) {
            throw Slots.typeError("can only join an iterable");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PyIterator iterator = Slots.iterator(parts);
        for (int at = 0; ; at++) {
            PyObject item = iterator.nextOrNull();
            if (item == null) {
                break;
            }
            if (at > 0) {
                out.writeBytes(separator);
            }
            out.writeBytes(segment(item, at));
        }
        return PyBytes.wrap(out.toByteArray());
    }

    /** An item of {@code join}'s iterable, refused with the position it was reached at. */
    private static byte[] segment(PyObject item, int at) {
        if (item instanceof PyBytes b) {
            return b.value;
        }
        throw Slots.typeError(
                "sequence item %d: expected a bytes-like object, %s found",
                at, item.getType().name());
    }

    /**
     * {@code partition} and {@code rpartition}: before, separator, after.
     *
     * <p>A miss is not an error but the whole value in one of the three places, on the side the
     * method reads from. An empty separator is an error, because it would be found at a place that is
     * not between two pieces.
     */
    private static PyTuple divided(PyObject self, byte[] text, PyObject argument, boolean left) {
        byte[] separator = run(argument);
        if (separator.length == 0) {
            throw Slots.valueError("empty separator");
        }
        int at = first(text, separator, 0, text.length, !left);
        if (at < 0) {
            return left
                    ? PyTuple.of(self, PyBytes.EMPTY, PyBytes.EMPTY)
                    : PyTuple.of(PyBytes.EMPTY, PyBytes.EMPTY, self);
        }
        return PyTuple.of(
                PyBytes.wrap(Arrays.copyOfRange(text, 0, at)),
                PyBytes.wrap(Arrays.copyOfRange(text, at, at + separator.length)),
                PyBytes.wrap(Arrays.copyOfRange(text, at + separator.length, text.length)));
    }

    /** {@code removeprefix} and {@code removesuffix}: the edge run cut off, if the value starts there. */
    private static PyObject shortened(PyObject self, PyObject argument, boolean prefix) {
        byte[] edge = run(argument);
        byte[] text = octets(self);
        if (edge.length == 0 || !matches(text, 0, text.length, edge, prefix)) {
            return self;
        }
        return PyBytes.wrap(
                prefix
                        ? Arrays.copyOfRange(text, edge.length, text.length)
                        : Arrays.copyOfRange(text, 0, text.length - edge.length));
    }

    /** The two edges of the run left once {@code strip}'s alphabet has been taken off them. */
    private static int[] trimmed(byte[] text, byte[] characters, boolean left, boolean right) {
        int from = 0;
        int to = text.length;
        while (left && from < to && removed(text[from], characters)) {
            from++;
        }
        while (right && to > from && removed(text[to - 1], characters)) {
            to--;
        }
        return new int[] {from, to};
    }

    private static boolean removed(byte octet, byte[] characters) {
        if (characters == null) {
            return isSpace(octet & 0xff);
        }
        for (byte character : characters) {
            if (character == octet) {
                return true;
            }
        }
        return false;
    }

    /** The six ASCII spaces Python strips, and nothing else: {@code \x85} is a space to no bytes. */
    private static boolean isSpace(int octet) {
        return octet == 0x20 || (octet >= 0x09 && octet <= 0x0d);
    }

    /**
     * Where {@code needle} is in {@code text[from, to)} as an absolute index, or -1.
     *
     * <p>An empty needle is not searched for but read off the range it was pointed at, which is what
     * makes {@code b'abc'.find(b'', 4)} -1 while {@code find(b'', 3)} is 3.
     */
    private static int first(byte[] text, byte[] needle, int from, int to, boolean right) {
        if (to < from || from > text.length) {
            return -1;
        }
        if (needle.length == 0) {
            return right ? to : from;
        }
        for (int at = right ? to - needle.length : from;
                right ? at >= from : at + needle.length <= to;
                at += right ? -1 : 1) {
            if (matchesAt(text, at, needle)) {
                return at;
            }
        }
        return -1;
    }

    /** How many non-overlapping copies of {@code needle} the range holds; an empty one fills every gap. */
    private static int occurrences(byte[] text, byte[] needle, int from, int to) {
        if (to < from || from > text.length) {
            return 0;
        }
        if (needle.length == 0) {
            return to - from + 1;
        }
        int found = 0;
        for (int at = first(text, needle, from, to, false);
                at >= 0;
                at = first(text, needle, at + needle.length, to, false)) {
            found++;
        }
        return found;
    }

    /** Whether the whole of {@code needle} sits at {@code at}, which the range must still have room for. */
    private static boolean matches(byte[] text, int from, int to, byte[] needle, boolean prefix) {
        if (needle.length > to - from) {
            return false;
        }
        return matchesAt(text, prefix ? from : to - needle.length, needle);
    }

    private static boolean matchesAt(byte[] text, int at, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (text[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    /** {@code b.replace(old, new, count)}, counting cuts rather than copies. */
    private static byte[] exchanged(byte[] text, byte[] old, byte[] replacement, long count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long remaining = count < 0 ? Long.MAX_VALUE : count;
        int at = 0;
        if (old.length == 0) {
            // The empty pattern matches at every boundary, the last one included, and the first
            // replacement is not one the loop could have made.
            out.writeBytes(replacement);
            while (at < text.length && --remaining > 0) {
                out.write(text[at]);
                out.writeBytes(replacement);
                at++;
            }
        } else {
            while (remaining > 0) {
                int found = first(text, old, at, text.length, false);
                if (found < 0) {
                    break;
                }
                out.write(text, at, found - at);
                out.writeBytes(replacement);
                at = found + old.length;
                remaining--;
            }
        }
        out.write(text, at, text.length - at);
        return out.toByteArray();
    }

    /** The pieces {@code split} and {@code rsplit} report, by separator or by whitespace. */
    private static PyList pieces(byte[] text, byte[] separator, long maxsplit, boolean right) {
        if (separator != null && separator.length == 0) {
            throw Slots.valueError("empty separator");
        }
        long cuts = maxsplit < 0 ? Long.MAX_VALUE : maxsplit;
        List<byte[]> out = new ArrayList<>();
        if (separator == null) {
            return whitespacePieces(text, cuts, right);
        }
        if (right) {
            int end = text.length;
            while (cuts > 0) {
                int at = first(text, separator, 0, end, true);
                if (at < 0) {
                    break;
                }
                out.add(Arrays.copyOfRange(text, at + separator.length, end));
                end = at;
                cuts--;
            }
            out.add(Arrays.copyOfRange(text, 0, end));
            Collections.reverse(out);
            return bytes(out);
        }
        int at = 0;
        while (cuts > 0) {
            int found = first(text, separator, at, text.length, false);
            if (found < 0) {
                break;
            }
            out.add(Arrays.copyOfRange(text, at, found));
            at = found + separator.length;
            cuts--;
        }
        out.add(Arrays.copyOfRange(text, at, text.length));
        return bytes(out);
    }

    /**
     * {@code split()} with no separator: runs of whitespace divide, and no piece is empty. The last
     * piece of a truncated split keeps the whitespace it was reached through, which is why
     * {@code b'  a  b  '.split(None, 1)} is {@code [b'a', b'b  ']} and not a list of one.
     */
    private static PyList whitespacePieces(byte[] text, long cuts, boolean right) {
        List<byte[]> out = new ArrayList<>();
        if (right) {
            int end = previousNonSpace(text, text.length);
            while (end > 0 && cuts > 0) {
                int start = end;
                while (start > 0 && !isSpace(text[start - 1] & 0xff)) {
                    start--;
                }
                out.add(Arrays.copyOfRange(text, start, end));
                end = previousNonSpace(text, start);
                cuts--;
            }
            if (end > 0) {
                out.add(Arrays.copyOfRange(text, 0, end));
            }
            Collections.reverse(out);
            return bytes(out);
        }
        int at = nextNonSpace(text, 0);
        while (at < text.length) {
            if (cuts == 0) {
                out.add(Arrays.copyOfRange(text, at, text.length));
                break;
            }
            int end = at;
            while (end < text.length && !isSpace(text[end] & 0xff)) {
                end++;
            }
            out.add(Arrays.copyOfRange(text, at, end));
            at = nextNonSpace(text, end);
            cuts--;
        }
        return bytes(out);
    }

    private static int nextNonSpace(byte[] text, int at) {
        while (at < text.length && isSpace(text[at] & 0xff)) {
            at++;
        }
        return at;
    }

    /** The offset just before the run of whitespace ending at {@code to}. */
    private static int previousNonSpace(byte[] text, int to) {
        while (to > 0 && isSpace(text[to - 1] & 0xff)) {
            to--;
        }
        return to;
    }

    /**
     * {@code splitlines}: the runs the line breaks end, with or without the break that ended them.
     *
     * <p>A bytes reads three breaks — {@code \n}, {@code \r} and the pair — where a {@code str} reads
     * the Unicode family. {@code b'a\x0bb'.splitlines()} is one line, and a {@code \x85} is two octets
     * of some other character's UTF-8 rather than the next line.
     */
    private static PyList lined(byte[] text, boolean keepends) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        int at = 0;
        while (at < text.length) {
            int octet = text[at] & 0xff;
            if (octet == '\r' || octet == '\n') {
                int next = at + 1;
                if (octet == '\r' && next < text.length && text[next] == '\n') {
                    next++;
                }
                out.add(Arrays.copyOfRange(text, start, keepends ? next : at));
                start = at = next;
            } else {
                at++;
            }
        }
        // A trailing break closes the last line rather than opening an empty one.
        if (start < text.length) {
            out.add(Arrays.copyOfRange(text, start, text.length));
        }
        return bytes(out);
    }

    /** ASCII case folding, which is all of what {@code bytes.upper()} means: {@code \xff} stays. */
    private static byte[] cased(byte[] text, boolean upper) {
        byte[] out = new byte[text.length];
        for (int i = 0; i < out.length; i++) {
            int octet = text[i] & 0xff;
            if (upper && octet >= 'a' && octet <= 'z') {
                octet -= 'a' - 'A';
            } else if (!upper && octet >= 'A' && octet <= 'Z') {
                octet += 'a' - 'A';
            }
            out[i] = (byte) octet;
        }
        return out;
    }

    private static String hexadecimal(byte[] text) {
        StringBuilder out = new StringBuilder(text.length * 2);
        for (byte octet : text) {
            out.append(String.format("%02x", octet & 0xff));
        }
        return out.toString();
    }

    private static PyList bytes(List<byte[]> parts) {
        PyObject[] out = new PyObject[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = PyBytes.wrap(parts.get(i));
        }
        return PyList.of(out);
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
