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
import java.util.function.IntPredicate;

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
     * The position a codec error reports is a code-point index while a Java string is a UTF-16 array, so
     * the {@code ascii} and {@code latin-1} tests walk code points and count them. The substitution
     * handlers are M4: an encoding this runtime has but a mode it does not is refused rather than
     * silently made strict.
     */
    public static PyBytes encodeText(String text, String encoding, String errors) {
        String name = codec(encoding);
        requireStrict(errors, "str.encode");
        return switch (name) {
            case "utf8" -> PyBytes.wrap(PyHash.encodeUtf8(text));
            case "utf8sig" -> PyBytes.wrap(concat(
                    new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, PyHash.encodeUtf8(text)));
            case "ascii" -> {
                int[] run = unencodable(text, 0x7f);
                if (run != null) {
                    throw Slots.unicodeEncode(
                            "ascii", text, run[0], run[1], "ordinal not in range(128)");
                }
                yield PyBytes.of(text.getBytes(StandardCharsets.US_ASCII));
            }
            case "latin1" -> {
                int[] run = unencodable(text, 0xff);
                if (run != null) {
                    throw Slots.unicodeEncode(
                            "latin-1", text, run[0], run[1], "ordinal not in range(256)");
                }
                byte[] out = new byte[text.length()];
                for (int i = 0; i < out.length; i++) {
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
                                "ascii", value, i, i + 1, "ordinal not in range(128)");
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

    /**
     * {@code charmap_encode}'s run: {@code [start, end)} over the code points a codec cannot write, counted
     * in code points because that is what the exception carries and what its sentence prints.
     *
     * <p>The run closes at the first character the codec can write, which is why {@code 'aéé'} is one error
     * over {@code 1-3} rather than two over {@code 1} and {@code 2}, while {@code 'éab'} is {@code 0-1} and
     * never reaches the character two places later.
     */
    private static int[] unencodable(String text, int limit) {
        int start = -1;
        int end = 0;
        int at = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint > limit) {
                if (start < 0) {
                    start = at;
                }
                end = at + 1;
            } else if (start >= 0) {
                break;
            }
            at++;
            i += Character.charCount(codePoint);
        }
        return start < 0 ? null : new int[] {start, end};
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
        if (op == BinaryOp.ADD) {
            // `bytes_concat` does not decline: it tries to read the right operand as a buffer and, when
            // that fails, says which of the two it could not concat — `[] + b'ab'` is the list's own
            // complaint about a bytes, while `b'ab' + []` is this one about a list.
            if (!(other instanceof PyBytes b)) {
                throw Slots.typeError(
                        "can't concat %s to %s", other.getType().name(), getType().name());
            }
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

    /**
     * {@code bytes_richcompare}: an octet run compares against another one and declines everything else.
     *
     * <p>The decline is what a program sees when it asks for the slot rather than running the operator —
     * {@code b'ab'.__eq__(3)} is {@code NotImplemented}, not {@code False} — and the operator still ends up
     * with {@code False}, because {@link Slots} falls back to identity once both sides have declined.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyBytes b
                ? PyObject.ordered(cmp, Arrays.compare(value, b.value))
                : PyNotImplemented.INSTANCE;
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

    /** {@code expandtabs}'s one parameter, which a caller may name but the paddings' fill byte may not. */
    private static final String[] TAB_SIZE = {"tabsize"};
    private static final PyObject[] TAB_DEFAULTS = {PyInt.of(8)};

    /** What the three paddings fill with when the call does not say. */
    private static final byte SPACE = 0x20;

    private static final BigInteger OCTET_MAX = BigInteger.valueOf(255);

    /** {@code hex}'s two parameters, which a caller may name; the separator has no default value. */
    private static final String[] HEX = {"sep", "bytes_per_sep"};

    private static final PyObject[] HEX_DEFAULTS = {PyInt.of(1)};

    /** {@code translate}'s two parameters, both of which CPython leaves NULL until a call fills them. */
    private static final String[] TABLE_AND_DELETE = {"table", "delete"};

    private static final PyObject[] TRANSLATE_DEFAULTS = {};

    /** {@code Py_hexdigits}: the two digits of one octet, lower case always. */
    private static final String HEX_DIGITS = "0123456789abcdef";

    private static final Members METHODS = Members.of(PyType.BYTES)
            .addNoArgument("capitalize", self -> PyBytes.wrap(capitalized(octets(self))))
            .add("center", (self, args, kwNames) -> padded(self, args, kwNames, "center", true, true))
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
            .add("expandtabs", (self, args, kwNames) -> PyBytes.wrap(expanded(
                    octets(self),
                    Numbers.cInteger(Arguments.parameters(
                            "expandtabs", args, kwNames, TAB_SIZE, TAB_DEFAULTS, 0)[0]))))
            .add("find", (self, args, kwNames) -> search(self, args, kwNames, "find", false, false))
            .addClass("fromhex", (args, kwNames) -> fromHexadecimal(args, kwNames))
            .add("hex", (self, args, kwNames) -> hexadecimal(
                    octets(self),
                    Arguments.parameters("hex", args, kwNames, HEX, HEX_DEFAULTS, 0)))
            .add("index", (self, args, kwNames) -> search(self, args, kwNames, "index", false, true))
            .addNoArgument("isalnum", self -> PyBool.of(allOf(octets(self), PyBytes::isAlnum)))
            .addNoArgument("isalpha", self -> PyBool.of(allOf(octets(self), PyBytes::isAlpha)))
            .addNoArgument("isascii", self -> PyBool.of(isAscii(octets(self))))
            .addNoArgument("isdigit", self -> PyBool.of(allOf(octets(self), PyBytes::isDigit)))
            .addNoArgument("islower", self -> PyBool.of(caseTest(octets(self), true)))
            .addNoArgument("isspace", self -> PyBool.of(allOf(octets(self), PyBytes::isSpace)))
            .addNoArgument("istitle", self -> PyBool.of(isTitled(octets(self))))
            .addNoArgument("isupper", self -> PyBool.of(caseTest(octets(self), false)))
            .addOne("join", (self, parts) -> joined(octets(self), parts))
            .add("ljust", (self, args, kwNames) ->
                    padded(self, args, kwNames, "ljust", false, true))
            .addNoArgument("lower", self -> PyBytes.wrap(cased(octets(self), false)))
            .add("lstrip", (self, args, kwNames) -> trim(self, args, kwNames, "lstrip", true, false))
            .addStatic("maketrans", (args, kwNames) -> translationTable(args, kwNames))
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
            .add("rjust", (self, args, kwNames) ->
                    padded(self, args, kwNames, "rjust", true, false))
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
            .addNoArgument("swapcase", self -> PyBytes.wrap(swapped(octets(self))))
            .addNoArgument("title", self -> PyBytes.wrap(titleCased(octets(self))))
            .add("translate", (self, args, kwNames) -> translated(
                    octets(self),
                    Arguments.parameters(
                            "translate", args, kwNames, TABLE_AND_DELETE, TRANSLATE_DEFAULTS, 1),
                    self))
            .addNoArgument("upper", self -> PyBytes.wrap(cased(octets(self), true)))
            .addOne("zfill", (self, width) -> zeroFilled(self, octets(self), Numbers.size(width)))
            // The two names pickle and the buffer protocol read off a value rather than call: the argument
            // list a rebuild is made from is the octet run itself, and `__bytes__` answers the same object
            // it was asked on, because a bytes is already the exact type it converts to.
            .add("__getnewargs__", (self, args, kwNames) -> {
                Arguments.none("bytes.__getnewargs__()", args, kwNames);
                return PyTuple.of(self);
            })
            .add("__bytes__", (self, args, kwNames) -> {
                Arguments.none("bytes.__bytes__()", args, kwNames);
                return self;
            })
            // The whole internal text clinic writes beside each of the forty-three rows `bytes_methods`
            // files with an `ml_doc`; the forty-fourth row, `__getnewargs__`, is filed with none, so it has
            // no text to transcribe. Two names are read out of that one text, and the cutting is the same
            // `find_signature`/`skip_signature` pair a built-in function's rows go through.
            .document(
                    "__bytes__",
                    "__bytes__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Convert this value to exact type bytes.")
            .document(
                    "capitalize",
                    "capitalize($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.capitalize() -> copy of B\n"
                            + "\n"
                            + "Return a copy of B with only its first character capitalized (ASCII)\n"
                            + "and the rest lower-cased.")
            .document(
                    "center",
                    "center($self, width, fillchar=b' ', /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a centered string of length width.\n"
                            + "\n"
                            + "Padding is done using the specified fill character.")
            .document(
                    "count",
                    "count($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the number of non-overlapping occurrences of subsection 'sub' in bytes B[start:end].\n"
                            + "\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the bytes.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the bytes.")
            .document(
                    "decode",
                    "decode($self, /, encoding='utf-8', errors='strict')\n"
                            + "--\n"
                            + "\n"
                            + "Decode the bytes using the codec registered for encoding.\n"
                            + "\n"
                            + "  encoding\n"
                            + "    The encoding with which to decode the bytes.\n"
                            + "  errors\n"
                            + "    The error handling scheme to use for the handling of decoding\n"
                            + "    errors.  The default is 'strict' meaning that decoding errors\n"
                            + "    raise a UnicodeDecodeError.  Other possible values are 'ignore'\n"
                            + "    and 'replace' as well as any other name registered with\n"
                            + "    codecs.register_error that can handle UnicodeDecodeErrors.")
            .document(
                    "endswith",
                    "endswith($self, suffix[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the bytes ends with the specified suffix, False otherwise.\n"
                            + "\n"
                            + "  suffix\n"
                            + "    A bytes or a tuple of bytes to try.\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the bytes.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the bytes.")
            .document(
                    "expandtabs",
                    "expandtabs($self, /, tabsize=8)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy where all tab characters are expanded using spaces.\n"
                            + "\n"
                            + "If tabsize is not given, a tab size of 8 characters is assumed.")
            .document(
                    "find",
                    "find($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the lowest index in B where subsection 'sub' is found, such that 'sub' is contained within B[start,end].\n"
                            + "\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the bytes.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the bytes.\n"
                            + "\n"
                            + "Return -1 on failure.")
            .document(
                    "fromhex",
                    "fromhex($type, string, /)\n"
                            + "--\n"
                            + "\n"
                            + "Create a bytes object from a string of hexadecimal numbers.\n"
                            + "\n"
                            + "Spaces between two numbers are accepted.\n"
                            + "Example: bytes.fromhex('B9 01EF') -> b'\\\\xb9\\\\x01\\\\xef'.")
            .document(
                    "hex",
                    "hex($self, /, sep=<unrepresentable>, bytes_per_sep=1)\n"
                            + "--\n"
                            + "\n"
                            + "Create a string of hexadecimal numbers from a bytes object.\n"
                            + "\n"
                            + "  sep\n"
                            + "    An optional single character or byte to separate hex bytes.\n"
                            + "  bytes_per_sep\n"
                            + "    How many bytes between separators.  Positive values count from\n"
                            + "    the right, negative values count from the left.\n"
                            + "\n"
                            + "Example:\n"
                            + ">>> value = b'\\xb9\\x01\\xef'\n"
                            + ">>> value.hex()\n"
                            + "'b901ef'\n"
                            + ">>> value.hex(':')\n"
                            + "'b9:01:ef'\n"
                            + ">>> value.hex(':', 2)\n"
                            + "'b9:01ef'\n"
                            + ">>> value.hex(':', -2)\n"
                            + "'b901:ef'")
            .document(
                    "index",
                    "index($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the lowest index in B where subsection 'sub' is found, such that 'sub' is contained within B[start,end].\n"
                            + "\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the bytes.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the bytes.\n"
                            + "\n"
                            + "Raise ValueError if the subsection is not found.")
            .document(
                    "isalnum",
                    "isalnum($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.isalnum() -> bool\n"
                            + "\n"
                            + "Return True if all characters in B are alphanumeric\n"
                            + "and there is at least one character in B, False otherwise.")
            .document(
                    "isalpha",
                    "isalpha($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.isalpha() -> bool\n"
                            + "\n"
                            + "Return True if all characters in B are alphabetic\n"
                            + "and there is at least one character in B, False otherwise.")
            .document(
                    "isascii",
                    "isascii($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.isascii() -> bool\n"
                            + "\n"
                            + "Return True if B is empty or all characters in B are ASCII,\n"
                            + "False otherwise.")
            .document(
                    "isdigit",
                    "isdigit($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.isdigit() -> bool\n"
                            + "\n"
                            + "Return True if all characters in B are digits\n"
                            + "and there is at least one character in B, False otherwise.")
            .document(
                    "islower",
                    "islower($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.islower() -> bool\n"
                            + "\n"
                            + "Return True if all cased characters in B are lowercase and there is\n"
                            + "at least one cased character in B, False otherwise.")
            .document(
                    "isspace",
                    "isspace($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.isspace() -> bool\n"
                            + "\n"
                            + "Return True if all characters in B are whitespace\n"
                            + "and there is at least one character in B, False otherwise.")
            .document(
                    "istitle",
                    "istitle($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.istitle() -> bool\n"
                            + "\n"
                            + "Return True if B is a titlecased string and there is at least one\n"
                            + "character in B, i.e. uppercase characters may only follow uncased\n"
                            + "characters and lowercase characters only cased ones. Return False\n"
                            + "otherwise.")
            .document(
                    "isupper",
                    "isupper($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.isupper() -> bool\n"
                            + "\n"
                            + "Return True if all cased characters in B are uppercase and there is\n"
                            + "at least one cased character in B, False otherwise.")
            .document(
                    "join",
                    "join($self, iterable_of_bytes, /)\n"
                            + "--\n"
                            + "\n"
                            + "Concatenate any number of bytes objects.\n"
                            + "\n"
                            + "The bytes whose method is called is inserted in between each pair.\n"
                            + "\n"
                            + "The result is returned as a new bytes object.\n"
                            + "\n"
                            + "Example: b'.'.join([b'ab', b'pq', b'rs']) -> b'ab.pq.rs'.")
            .document(
                    "ljust",
                    "ljust($self, width, fillchar=b' ', /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a left-justified string of length width.\n"
                            + "\n"
                            + "Padding is done using the specified fill character.")
            .document(
                    "lower",
                    "lower($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.lower() -> copy of B\n"
                            + "\n"
                            + "Return a copy of B with all ASCII characters converted to lowercase.")
            .document(
                    "lstrip",
                    "lstrip($self, bytes=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Strip leading bytes contained in the argument.\n"
                            + "\n"
                            + "If the argument is omitted or None, strip leading  ASCII whitespace.")
            .document(
                    "maketrans",
                    "maketrans(frm, to, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a translation table usable for the bytes or bytearray translate method.\n"
                            + "\n"
                            + "The returned table will be one where each byte in frm is mapped to\n"
                            + "the byte at the same position in to.\n"
                            + "\n"
                            + "The bytes objects frm and to must be of the same length.")
            .document(
                    "partition",
                    "partition($self, sep, /)\n"
                            + "--\n"
                            + "\n"
                            + "Partition the bytes into three parts using the given separator.\n"
                            + "\n"
                            + "This will search for the separator sep in the bytes.  If the\n"
                            + "separator is found, returns a 3-tuple containing the part before the\n"
                            + "separator, the separator itself, and the part after it.\n"
                            + "\n"
                            + "If the separator is not found, returns a 3-tuple containing the\n"
                            + "original bytes object and two empty bytes objects.")
            .document(
                    "removeprefix",
                    "removeprefix($self, prefix, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a bytes object with the given prefix string removed if present.\n"
                            + "\n"
                            + "If the bytes starts with the prefix string, return\n"
                            + "bytes[len(prefix):].  Otherwise, return a copy of the original\n"
                            + "bytes.")
            .document(
                    "removesuffix",
                    "removesuffix($self, suffix, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a bytes object with the given suffix string removed if present.\n"
                            + "\n"
                            + "If the bytes ends with the suffix string and that suffix is not\n"
                            + "empty, return bytes[:-len(prefix)].  Otherwise, return a copy of the\n"
                            + "original bytes.")
            .document(
                    "replace",
                    "replace($self, old, new, count=-1, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy with all occurrences of substring old replaced by new.\n"
                            + "\n"
                            + "  count\n"
                            + "    Maximum number of occurrences to replace.\n"
                            + "    -1 (the default value) means replace all occurrences.\n"
                            + "\n"
                            + "If the optional argument count is given, only the first count occurrences are\n"
                            + "replaced.")
            .document(
                    "rfind",
                    "rfind($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the highest index in B where subsection 'sub' is found, such that 'sub' is contained within B[start,end].\n"
                            + "\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the bytes.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the bytes.\n"
                            + "\n"
                            + "Return -1 on failure.")
            .document(
                    "rindex",
                    "rindex($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the highest index in B where subsection 'sub' is found, such that 'sub' is contained within B[start,end].\n"
                            + "\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the bytes.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the bytes.\n"
                            + "\n"
                            + "Raise ValueError if the subsection is not found.")
            .document(
                    "rjust",
                    "rjust($self, width, fillchar=b' ', /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a right-justified string of length width.\n"
                            + "\n"
                            + "Padding is done using the specified fill character.")
            .document(
                    "rpartition",
                    "rpartition($self, sep, /)\n"
                            + "--\n"
                            + "\n"
                            + "Partition the bytes into three parts using the given separator.\n"
                            + "\n"
                            + "This will search for the separator sep in the bytes, starting at the\n"
                            + "end.  If the separator is found, returns a 3-tuple containing the\n"
                            + "part before the separator, the separator itself, and the part after\n"
                            + "it.\n"
                            + "\n"
                            + "If the separator is not found, returns a 3-tuple containing two\n"
                            + "empty bytes objects and the original bytes object.")
            .document(
                    "rsplit",
                    "rsplit($self, /, sep=None, maxsplit=-1)\n"
                            + "--\n"
                            + "\n"
                            + "Return a list of the sections in the bytes, using sep as the delimiter.\n"
                            + "\n"
                            + "  sep\n"
                            + "    The delimiter according which to split the bytes.\n"
                            + "    None (the default value) means split on ASCII whitespace\n"
                            + "    characters (space, tab, return, newline, formfeed, vertical tab).\n"
                            + "  maxsplit\n"
                            + "    Maximum number of splits to do.\n"
                            + "    -1 (the default value) means no limit.\n"
                            + "\n"
                            + "Splitting is done starting at the end of the bytes and working to\n"
                            + "the front.")
            .document(
                    "rstrip",
                    "rstrip($self, bytes=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Strip trailing bytes contained in the argument.\n"
                            + "\n"
                            + "If the argument is omitted or None, strip trailing ASCII whitespace.")
            .document(
                    "split",
                    "split($self, /, sep=None, maxsplit=-1)\n"
                            + "--\n"
                            + "\n"
                            + "Return a list of the sections in the bytes, using sep as the delimiter.\n"
                            + "\n"
                            + "  sep\n"
                            + "    The delimiter according which to split the bytes.\n"
                            + "    None (the default value) means split on ASCII whitespace\n"
                            + "    characters (space, tab, return, newline, formfeed, vertical tab).\n"
                            + "  maxsplit\n"
                            + "    Maximum number of splits to do.\n"
                            + "    -1 (the default value) means no limit.")
            .document(
                    "splitlines",
                    "splitlines($self, /, keepends=False)\n"
                            + "--\n"
                            + "\n"
                            + "Return a list of the lines in the bytes, breaking at line boundaries.\n"
                            + "\n"
                            + "Line breaks are not included in the resulting list unless keepends\n"
                            + "is given and true.")
            .document(
                    "startswith",
                    "startswith($self, prefix[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the bytes starts with the specified prefix, False otherwise.\n"
                            + "\n"
                            + "  prefix\n"
                            + "    A bytes or a tuple of bytes to try.\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the bytes.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the bytes.")
            .document(
                    "strip",
                    "strip($self, bytes=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Strip leading and trailing bytes contained in the argument.\n"
                            + "\n"
                            + "If the argument is omitted or None, strip leading and trailing ASCII\n"
                            + "whitespace.")
            .document(
                    "swapcase",
                    "swapcase($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.swapcase() -> copy of B\n"
                            + "\n"
                            + "Return a copy of B with uppercase ASCII characters converted\n"
                            + "to lowercase ASCII and vice versa.")
            .document(
                    "title",
                    "title($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.title() -> copy of B\n"
                            + "\n"
                            + "Return a titlecased version of B, i.e. ASCII words start with\n"
                            + "uppercase characters, all remaining cased characters have lowercase.")
            .document(
                    "translate",
                    "translate($self, table, /, delete=b'')\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy with each character mapped by the given translation table.\n"
                            + "\n"
                            + "  table\n"
                            + "    Translation table, which must be a bytes object of length 256.\n"
                            + "\n"
                            + "All characters occurring in the optional argument delete are\n"
                            + "removed.  The remaining characters are mapped through the given\n"
                            + "translation table.")
            .document(
                    "upper",
                    "upper($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.upper() -> copy of B\n"
                            + "\n"
                            + "Return a copy of B with all ASCII characters converted to uppercase.")
            .document(
                    "zfill",
                    "zfill($self, width, /)\n"
                            + "--\n"
                            + "\n"
                            + "Pad a numeric string with zeros on the left, to fill a field of the given width.\n"
                            + "\n"
                            + "The original string is never truncated.");


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

    /**
     * {@code center}, {@code ljust} and {@code rjust}: the same margin, put on one side or on both.
     *
     * <p>The width is read before the fill byte, and both are read even when the value is already as
     * wide as it asked, so {@code b'ab'.center(1, 'x')} is refused as a fill byte rather than answered
     * as {@code b'ab'}. An odd margin is not split by rounding: the extra octet goes left only when the
     * width is odd too, which is CPython's {@code marg / 2 + (marg & width & 1)} and is visible as
     * {@code b'ab'.center(4)} being {@code b' ab '} while {@code center(5)} is {@code b'  ab '}.
     */
    private static PyObject padded(PyObject self, PyObject[] args, String[] kwNames, String name,
            boolean left, boolean right) {
        legacy(name, args, kwNames, 1, 2);
        byte[] text = octets(self);
        long width = Numbers.size(args[0]);
        byte fill = args.length > 1 ? fillByte(args[1], name) : SPACE;
        if (text.length >= width) {
            return self;
        }
        long margin = width - text.length;
        long before = !left ? 0 : right ? margin / 2 + (margin & width & 1) : margin;
        return PyBytes.wrap(pad(text, before, margin - before, fill));
    }

    /**
     * The one octet a padding fills with.
     *
     * <p>An integer is refused, however small, where {@code index} and {@code count} read one as an
     * octet: this argument is converted by the {@code char} converter, whose two refusals differ by a
     * colon. A type that is not a run of octets is refused by {@code _PyArg_BadArgument}, which names
     * the method without one and says {@code None} for {@code None}; a run of the wrong length is
     * refused by the converter's own {@code PyErr_Format}, which adds the colon and counts the octets.
     */
    private static byte fillByte(PyObject value, String name) {
        if (value instanceof PyBytes one) {
            if (one.size() != 1) {
                throw Slots.typeError(
                        "%s(): argument 2 must be a byte string of length 1, "
                                + "not a bytes object of length %d",
                        name, one.size());
            }
            return one.value[0];
        }
        throw Slots.typeError(
                "%s() argument 2 must be a byte string of length 1, not %s",
                name, Slots.argumentName(value));
    }

    /**
     * {@code transmogrify.h}'s {@code pad()}: the value, with the fill byte counted out on each side.
     *
     * <p>The margins sum to the width the caller asked for, so they cannot overflow a word, and the
     * refusal of a width too wide to allocate comes from the same place CPython's comes from: the size
     * is checked against what a bytes object can carry before anything is asked of memory.
     */
    private static byte[] pad(byte[] text, long before, long after, byte fill) {
        long total = before + text.length + after;
        Numbers.byteSize(total);
        int size = Numbers.total(total);
        byte[] out = new byte[size];
        Arrays.fill(out, 0, (int) before, fill);
        System.arraycopy(text, 0, out, (int) before, text.length);
        Arrays.fill(out, (int) before + text.length, size, fill);
        return out;
    }

    /**
     * {@code zfill}: zeros in front of a number, and a sign stays in front of the zeros.
     *
     * <p>Only the first octet of the value can be a sign, and it moves rather than being re-read, so
     * {@code b'--1'.zfill(6)} is {@code b'-000-1'} — the second minus is part of what got padded.
     */
    private static PyObject zeroFilled(PyObject self, byte[] text, long width) {
        if (text.length >= width) {
            return self;
        }
        long margin = width - text.length;
        byte[] out = pad(text, margin, 0, (byte) '0');
        // The octet CPython looks at is the first of the value, which sits just after the zeros it
        // padded in front of it — and for an empty value it is the terminator, so nothing moves.
        if (text.length > 0 && (text[0] == '+' || text[0] == '-')) {
            out[0] = text[0];
            out[(int) margin] = '0';
        }
        return PyBytes.wrap(out);
    }

    /**
     * {@code expandtabs}: every tab reaches the next multiple of the tab size, counted in octets.
     *
     * <p>The tab size is a C int, so {@code 2 ** 31} overflows here while the widths of the paddings
     * take it; zero or less deletes a tab instead of expanding it. Only {@code \n} and {@code \r} start
     * a new line, which is fewer than the breaks {@code splitlines()} reads and fewer than the spaces
     * {@code strip()} removes: {@code b'a\x0bb\x9ct'.expandtabs()} keeps counting columns through the
     * vertical tab. Unlike the paddings, a value with nothing to expand is still copied.
     */
    private static byte[] expanded(byte[] text, int tabsize) {
        // CPython's first pass, which counts the octets before any of them are written: the answer is
        // one number, so the width is refused where the paddings refuse theirs instead of being asked
        // of memory a tab at a time. {@code result too long} — the overflow the same pass jumps to when
        // the count would leave a machine word — would take four billion tabs to reach, which is more
        // octets than a bytes can hold on this runtime anyway, so only memory is ever refused here.
        long total = 0;
        long column = 0;
        for (byte octet : text) {
            int value = octet & 0xff;
            if (value == '\t') {
                long spaces = tabsize > 0 ? tabsize - column % tabsize : 0;
                total += spaces;
                column += spaces;
            } else {
                total++;
                column = value == '\n' || value == '\r' ? 0 : column + 1;
            }
        }
        byte[] out = new byte[Numbers.total(total)];
        int at = 0;
        column = 0;
        for (byte octet : text) {
            int value = octet & 0xff;
            if (value == '\t') {
                if (tabsize > 0) {
                    int spaces = (int) (tabsize - column % tabsize);
                    Arrays.fill(out, at, at + spaces, SPACE);
                    at += spaces;
                    column += spaces;
                }
            } else {
                out[at++] = octet;
                column = value == '\n' || value == '\r' ? 0 : column + 1;
            }
        }
        return out;
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
            out[i] = (byte) (upper ? upper(octet) : lower(octet));
        }
        return out;
    }

    private static int upper(int octet) {
        return isLower(octet) ? octet - ('a' - 'A') : octet;
    }

    private static int lower(int octet) {
        return isUpper(octet) ? octet + ('a' - 'A') : octet;
    }

    private static boolean isUpper(int octet) {
        return octet >= 'A' && octet <= 'Z';
    }

    private static boolean isLower(int octet) {
        return octet >= 'a' && octet <= 'z';
    }

    private static boolean isAlpha(int octet) {
        return isUpper(octet) || isLower(octet);
    }

    private static boolean isAlnum(int octet) {
        return isAlpha(octet) || isDigit(octet);
    }

    private static boolean isDigit(int octet) {
        return octet >= '0' && octet <= '9';
    }

    /**
     * {@code capitalize}: the first octet upper, the rest lower.
     *
     * <p>The head is uppercased on its own rather than by folding the whole value first, which is what
     * makes {@code b'a\xff'.capitalize()} keep the {@code \xff} and {@code b'AB'.capitalize()} be
     * {@code b'Ab'}.
     */
    private static byte[] capitalized(byte[] text) {
        byte[] out = cased(text, false);
        if (out.length > 0) {
            out[0] = (byte) upper(text[0] & 0xff);
        }
        return out;
    }

    /** {@code title}: an octet goes upper where the one before it was not cased at all. */
    private static byte[] titleCased(byte[] text) {
        byte[] out = new byte[text.length];
        boolean previous = false;
        for (int i = 0; i < text.length; i++) {
            int octet = text[i] & 0xff;
            if (isLower(octet)) {
                out[i] = (byte) (previous ? octet : upper(octet));
                previous = true;
            } else if (isUpper(octet)) {
                out[i] = (byte) (previous ? lower(octet) : octet);
                previous = true;
            } else {
                out[i] = (byte) octet;
                previous = false;
            }
        }
        return out;
    }

    /** {@code swapcase}: the one fold that leaves an octet which is cased in neither direction alone. */
    private static byte[] swapped(byte[] text) {
        byte[] out = new byte[text.length];
        for (int i = 0; i < text.length; i++) {
            int octet = text[i] & 0xff;
            out[i] = (byte) (isLower(octet) ? upper(octet) : lower(octet));
        }
        return out;
    }

    /**
     * {@code islower} and {@code isupper}: one direction of case, and at least one octet that has any.
     *
     * <p>Uncased octets are neither a help nor a hindrance, so {@code b'1a'.islower()} is true while
     * {@code b'1'.islower()} is false, and an octet above {@code 0x7f} is one of the uncased ones.
     */
    private static boolean caseTest(byte[] text, boolean lower) {
        boolean cased = false;
        for (byte octet : text) {
            int value = octet & 0xff;
            if (lower ? isUpper(value) : isLower(value)) {
                return false;
            }
            cased = cased || (lower ? isLower(value) : isUpper(value));
        }
        return cased;
    }

    /**
     * {@code istitle}: an upper may only follow an uncased octet, and a lower only a cased one.
     *
     * <p>Both halves are needed, so {@code b'A a'.istitle()} is false while {@code b'A1'} is true — a
     * digit uncases the run without lowering it. An empty bytes answers false, unlike {@code isascii}.
     */
    private static boolean isTitled(byte[] text) {
        boolean cased = false;
        boolean previous = false;
        for (byte octet : text) {
            int value = octet & 0xff;
            if (isUpper(value)) {
                if (previous) {
                    return false;
                }
                cased = true;
            } else if (isLower(value)) {
                if (!previous) {
                    return false;
                }
                cased = true;
            } else {
                previous = false;
                continue;
            }
            previous = true;
        }
        return cased;
    }

    /** The predicates that ask one category of every octet, and answer false of an empty bytes. */
    private static boolean allOf(byte[] text, IntPredicate category) {
        if (text.length == 0) {
            return false;
        }
        for (byte octet : text) {
            if (!category.test(octet & 0xff)) {
                return false;
            }
        }
        return true;
    }

    /** {@code isascii}: every octet below {@code 0x80}, which an empty bytes has never broken. */
    private static boolean isAscii(byte[] text) {
        for (byte octet : text) {
            if ((octet & 0xff) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code b'ab'.hex(sep, bytes_per_sep)}: two digits per octet, optionally grouped.
     *
     * <p>The step is converted while the arguments are being read, before the separator is looked at,
     * which is why {@code b'ab'.hex(None, 2.0)} complains about a float rather than about a separator with
     * no length. Both numbers are a C {@code int}, so a step wider than one is an {@code OverflowError}
     * even when its sign would have made it a group of nothing.
     */
    private static PyObject hexadecimal(byte[] text, PyObject[] given) {
        int step = Numbers.cInteger(given[1]);
        if (given[0] == null) {
            return PyStr.of(hexadecimal(text));
        }
        char between = (char) separator(given[0]);
        // CPython takes the absolute value into an unsigned word, so the most negative int is a group of
        // 2**31 — always wider than any bytes it could be applied to, and so a plain run of digits.
        long group = Math.abs((long) step);
        if (step == 0 || group >= text.length) {
            return PyStr.of(hexadecimal(text));
        }
        // The count of separators is the number of whole groups that fit in all but the last octet, and
        // where they land is the only difference between the two signs: positive counts them from the
        // right, negative from the left.
        int groups = (int) ((text.length - 1) / group);
        int next = step > 0 ? text.length - groups * (int) group : (int) group;
        StringBuilder out = new StringBuilder(text.length * 2 + groups);
        for (int at = 0; at < text.length; at++) {
            if (at == next) {
                out.append(between);
                next += (int) group;
            }
            appendDigits(out, text[at]);
        }
        return PyStr.of(out.toString());
    }

    /**
     * The one character {@code hex} separates its groups with, asked in CPython's order.
     *
     * <p>The length comes first and of anything, which is why a number is refused for having no
     * {@code len()} while a one-element list is refused for not being a string. Only then is the type
     * read, and then the value: a separator is two digits beside one octet in a string this method
     * returns, so nothing above {@code 0x7f} can be written into it — whether because it needed two or
     * four bytes to store, or because one was enough for a byte no ASCII letter is.
     */
    private static int separator(PyObject sep) {
        long length = Numbers.size(Slots.length(sep));
        if (length != 1) {
            throw Slots.valueError("sep must be length 1.");
        }
        int value;
        if (sep instanceof PyStr text) {
            value = text.getValue().codePointAt(0);
        } else if (sep instanceof PyBytes one) {
            value = one.value[0] & 0xff;
        } else {
            throw Slots.typeError("sep must be str or bytes.");
        }
        if (value > 127) {
            throw Slots.valueError("sep must be ASCII.");
        }
        return value;
    }

    /** {@code bytes.fromhex(string)}: two digits per octet, with a space allowed between the pair. */
    private static PyObject fromHexadecimal(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("bytes.fromhex()", args, kwNames);
        return PyBytes.wrap(hexadecimalDigits(args[0]));
    }

    /**
     * The octets behind the digits {@code fromhex} reads.
     *
     * <p>A string is tested for anything above ASCII before a single digit is read, so its position is a
     * count of code points and is the only thing a mixed string ever reports; a bytes is read as digits
     * from its first octet, and an octet that is not one reports the same sentence at the same count.
     * Anything that is neither — a buffer this runtime has no type for included — is refused by name.
     */
    private static byte[] hexadecimalDigits(PyObject source) {
        if (source instanceof PyStr text) {
            String value = text.getValue();
            int index = 0;
            for (int at = 0; at < value.length(); ) {
                int code = value.codePointAt(at);
                at += Character.charCount(code);
                if (code >= 128) {
                    throw Slots.valueError(
                            "non-hexadecimal number found in fromhex() arg at position %d", index);
                }
                index++;
            }
            return hexadecimalDigits(value.getBytes(StandardCharsets.ISO_8859_1));
        }
        if (source instanceof PyBytes one) {
            return hexadecimalDigits(one.value);
        }
        throw Slots.typeError(
                "fromhex() argument must be str or bytes-like, not %s", source.getType().name());
    }

    /** The walk itself, which reads a space only where a pair of digits would otherwise begin. */
    private static byte[] hexadecimalDigits(byte[] digits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(digits.length / 2);
        int at = 0;
        while (at < digits.length) {
            if (isHexSpace(digits[at] & 0xff)) {
                do {
                    at++;
                    if (at >= digits.length) {
                        return out.toByteArray();
                    }
                } while (isHexSpace(digits[at] & 0xff));
            }
            int top = hexDigit(digits[at] & 0xff);
            if (top >= 16) {
                throw Slots.valueError(
                        "non-hexadecimal number found in fromhex() arg at position %d", at);
            }
            at++;
            if (at >= digits.length) {
                throw Slots.valueError(
                        "fromhex() arg must contain an even number of hexadecimal digits");
            }
            int low = hexDigit(digits[at] & 0xff);
            if (low >= 16) {
                throw Slots.valueError(
                        "non-hexadecimal number found in fromhex() arg at position %d", at);
            }
            at++;
            out.write((top << 4) + low);
        }
        return out.toByteArray();
    }

    /** The octets {@code fromhex} skips between two numbers: CPython's {@code Py_ISSPACE}. */
    private static boolean isHexSpace(int code) {
        // Only the six that a C locale calls a space — a vertical tab is one and a non-breaking space is
        // not, and a string that reached this method never got as far as a space above ASCII anyway.
        return code == 0x20 || (code >= 0x09 && code <= 0x0d);
    }

    /** {@code _PyLong_DigitValue}: the sixteen digits, and anything else a number that is not one. */
    private static int hexDigit(int code) {
        if (code >= '0' && code <= '9') {
            return code - '0';
        }
        if (code >= 'a' && code <= 'f') {
            return code - 'a' + 10;
        }
        if (code >= 'A' && code <= 'F') {
            return code - 'A' + 10;
        }
        return 255;
    }

    /** {@code bytes.maketrans(frm, to)}: the identity table with each octet of one pointed at the other. */
    private static PyObject translationTable(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("bytes.maketrans()", kwNames);
        Arguments.exactly("maketrans", args, kwNames, 2);
        byte[] from = run(args[0]);
        byte[] to = run(args[1]);
        if (from.length != to.length) {
            throw Slots.valueError("maketrans arguments must have same length");
        }
        byte[] table = new byte[256];
        for (int at = 0; at < 256; at++) {
            table[at] = (byte) at;
        }
        for (int at = 0; at < from.length; at++) {
            // Written in order onto one table, so the last of two claims on an octet is the answer.
            table[from[at] & 0xff] = to[at];
        }
        return PyBytes.wrap(table);
    }

    /**
     * {@code b'abc'.translate(table, delete)}: one octet in, one out, or none.
     *
     * <p>The table is read before the deletions are, so a table of the wrong length is refused even by a
     * call whose second argument could not be a table either, and {@code None} is the only argument that
     * is not a run of octets — it means the identity, and so is how a call deletes without mapping.
     *
     * <p>An answer that is octet for octet the receiver is the receiver itself, which is what makes
     * {@code b'ab'.translate(bytes.maketrans(b'', b'')) is b'ab'} true. A deletion that never happens and
     * a mapping that maps nothing both count as no change.
     */
    private static PyObject translated(byte[] text, PyObject[] given, PyObject self) {
        byte[] table = given[0] instanceof PyNone ? null : run(given[0]);
        if (table != null && table.length != 256) {
            throw Slots.valueError("translation table must be 256 characters long");
        }
        byte[] delete = given[1] == null ? null : run(given[1]);
        if (delete == null || delete.length == 0) {
            if (table != null) {
                // CPython's fast path, the only one that reads the table as octets rather than as ints.
                byte[] out = new byte[text.length];
                boolean changed = false;
                for (int at = 0; at < text.length; at++) {
                    byte mapped = table[text[at] & 0xff];
                    out[at] = mapped;
                    changed |= mapped != text[at];
                }
                return changed ? PyBytes.wrap(out) : self;
            }
        }
        int[] mapped = new int[256];
        for (int at = 0; at < 256; at++) {
            mapped[at] = table == null ? at : table[at] & 0xff;
        }
        if (delete != null) {
            for (byte doomed : delete) {
                mapped[doomed & 0xff] = -1;
            }
        }
        byte[] out = new byte[text.length];
        int written = 0;
        boolean changed = false;
        for (byte octet : text) {
            int value = mapped[octet & 0xff];
            if (value < 0) {
                changed = true;
            } else if ((out[written++] = (byte) value) != octet) {
                changed = true;
            }
        }
        return changed ? PyBytes.wrap(Arrays.copyOf(out, written)) : self;
    }

    private static String hexadecimal(byte[] text) {
        StringBuilder out = new StringBuilder(text.length * 2);
        for (byte octet : text) {
            appendDigits(out, octet);
        }
        return out.toString();
    }

    /** The two {@code Py_hexdigits} of one octet. */
    private static void appendDigits(StringBuilder out, byte octet) {
        int value = octet & 0xff;
        out.append(HEX_DIGITS.charAt(value >> 4)).append(HEX_DIGITS.charAt(value & 0xf));
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
