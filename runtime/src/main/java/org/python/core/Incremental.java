package org.python.core;

import java.util.Arrays;

/**
 * The incremental half of the seventeen built-in codecs: the classes {@code codecs.lookup} files as
 * {@code incrementalencoder} and {@code incrementaldecoder}.
 *
 * <p>The whole-input walk in {@link Encoder} and {@link Decoder} is the same walk these answer with, once
 * the bytes that are not yet a whole sequence are held back. That is the whole difference, and it is the
 * reason this file does not know a second table of codecs: a decoder prepends what it carried to the chunk
 * it was handed, decides how much of the result is a whole number of sequences, runs {@link Decoder} over
 * that prefix, and keeps the rest for the next call. The prefix is the <em>longest</em> one the codec can
 * read, which is what makes a UTF-8 character split over two chunks decode, and what makes {@code final}
 * turn a leftover into the same {@code unexpected end of data} the whole-input walk raises.
 *
 * <p>Positions in an error are counted from the start of the carried-plus-new buffer, not from the new
 * chunk, because that is the array CPython's {@code BufferedIncrementalDecoder.decode} hands its
 * {@code _buffer_decode} ({@code Lib/codecs.py:324}). The carry itself survives a failed call untouched —
 * CPython assigns {@code self.buffer} only after the walk returns — which is why a chunk that raised can be
 * replaced with a well-formed one and continue.
 *
 * <p>The encoder side has no carry: a code point is encoded by the chunk it sits in. What it does have is
 * the twice-written byte order mark. {@code utf-8-sig} writes its three octets before the first chunk,
 * empty or not, and never again; {@code utf-16} and {@code utf-32} do the same with their wider mark and
 * then continue in the machine's own order, which on this JVM is little-endian. Their {@code getstate}
 * answers the one bit that is left — whether the mark has been written — in the shape
 * {@code Lib/encodings/utf_8_sig.py}, {@code utf_16.py} and {@code utf_32.py} answer it.
 */
final class Incremental {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final byte[] UTF16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};

    private static final byte[] UTF16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    private static final byte[] UTF32_LE_BOM = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x00};

    private static final byte[] UTF32_BE_BOM = {0x00, 0x00, (byte) 0xFE, (byte) 0xFF};

    /** The order a stateful UTF-16 or UTF-32 stream is read in: not yet decided, little, or big. */
    private static final int UNKNOWN = 2;

    private Incremental() {}

    // --- the factories a CodecInfo files ---------------------------------------------------------

    /** The class a {@code CodecInfo} files as {@code incrementalencoder}. */
    static PyObject encoder(String canonical) {
        return new PyNativeFunction("IncrementalEncoder", (args, kwNames) -> {
            PyObject[] values = Arguments.parameters(
                    "IncrementalEncoder", args, kwNames, new String[] {"errors"},
                    new PyObject[] {PyStr.of("strict")}, 0);
            return encoder(canonical, Numbers.asText(values[0]));
        });
    }

    /** The class a {@code CodecInfo} files as {@code incrementaldecoder}. */
    static PyObject decoder(String canonical) {
        return new PyNativeFunction("IncrementalDecoder", (args, kwNames) -> {
            PyObject[] values = Arguments.parameters(
                    "IncrementalDecoder", args, kwNames, new String[] {"errors"},
                    new PyObject[] {PyStr.of("strict")}, 0);
            return decoder(canonical, Numbers.asText(values[0]));
        });
    }

    // --- encoder ----------------------------------------------------------------------------------

    /** What an incremental encoder remembers: the twice-written mark, and nothing else. */
    private static final class EncoderState {
        final String canonical;
        final String errors;

        /** {@code utf-8-sig}'s {@code first}: whether the byte order mark is still to be written. */
        boolean first = true;

        /** {@code utf-16}/{@code utf-32}'s {@code encoder is None}: whether the order is still open. */
        boolean ordered;

        EncoderState(String canonical, String errors) {
            this.canonical = canonical;
            this.errors = errors;
        }
    }

    private static PyObject encoder(String canonical, String errors) {
        EncoderState state = new EncoderState(canonical, errors);
        PyNamespace object = new PyNamespace();
        object.setAttr("errors", PyStr.of(errors));
        object.setAttr("encode", new PyNativeFunction("encode", (args, kwNames) -> {
            PyObject[] values = Arguments.parameters(
                    "encode", args, kwNames, new String[] {"input", "final"},
                    new PyObject[] {PyNone.INSTANCE}, 1);
            String text = Numbers.asText(values[0]);
            return encode(state, text);
        }));
        object.setAttr("reset", new PyNativeFunction("reset", (args, kwNames) -> {
            Arguments.exactly("reset", args, kwNames, 0);
            state.first = true;
            state.ordered = false;
            return PyNone.INSTANCE;
        }));
        object.setAttr("getstate", new PyNativeFunction("getstate", (args, kwNames) -> {
            Arguments.exactly("getstate", args, kwNames, 0);
            return PyInt.of(encoderState(state));
        }));
        object.setAttr("setstate", new PyNativeFunction("setstate", (args, kwNames) -> {
            PyObject[] values = Arguments.parameters(
                    "setstate", args, kwNames, new String[] {"state"}, new PyObject[0], 1);
            setEncoderState(state, values[0]);
            return PyNone.INSTANCE;
        }));
        return object;
    }

    private static PyBytes encode(EncoderState state, String text) {
        switch (state.canonical) {
            case "utf_8_sig":
                if (state.first) {
                    state.first = false;
                    return Encoder.encode(text, "utf_8_sig", state.errors);
                }
                return Encoder.encode(text, "utf_8", state.errors);
            case "utf_16":
                if (!state.ordered) {
                    state.ordered = true;
                    return Encoder.encode(text, "utf_16", state.errors);
                }
                return Encoder.encode(text, "utf_16_le", state.errors);
            case "utf_32":
                if (!state.ordered) {
                    state.ordered = true;
                    return Encoder.encode(text, "utf_32", state.errors);
                }
                return Encoder.encode(text, "utf_32_le", state.errors);
            default:
                return Encoder.encode(text, state.canonical, state.errors);
        }
    }

    private static long encoderState(EncoderState state) {
        switch (state.canonical) {
            case "utf_8_sig":
                return state.first ? 1 : 0;
            case "utf_16":
            case "utf_32":
                return state.ordered ? 0 : UNKNOWN;
            default:
                return 0;
        }
    }

    private static void setEncoderState(EncoderState state, PyObject value) {
        switch (state.canonical) {
            case "utf_8_sig":
                state.first = Slots.truth(value);
                break;
            case "utf_16":
            case "utf_32":
                state.ordered = Numbers.asIndex(value).intValue() != UNKNOWN;
                break;
            default:
                break;
        }
    }

    // --- decoder ----------------------------------------------------------------------------------

    /** What an incremental decoder remembers: the bytes that are not yet a whole sequence. */
    private static final class DecoderState {
        final String canonical;
        final String errors;

        byte[] pending = new byte[0];

        /** {@code utf-8-sig}'s {@code first}: whether the mark is still to be looked for. */
        boolean first = true;

        /** {@code utf-16}/{@code utf-32}'s order, {@link #UNKNOWN} until the mark says. */
        int order = UNKNOWN;

        DecoderState(String canonical, String errors) {
            this.canonical = canonical;
            this.errors = errors;
        }
    }

    private static PyObject decoder(String canonical, String errors) {
        DecoderState state = new DecoderState(canonical, errors);
        PyNamespace object = new PyNamespace();
        object.setAttr("errors", PyStr.of(errors));
        object.setAttr("decode", new PyNativeFunction("decode", (args, kwNames) -> {
            PyObject[] values = Arguments.parameters(
                    "decode", args, kwNames, new String[] {"input", "final"},
                    new PyObject[] {PyNone.INSTANCE}, 1);
            byte[] input = octets(values[0]);
            boolean last = Slots.truth(values[1]);
            return PyStr.of(decode(state, input, last));
        }));
        object.setAttr("reset", new PyNativeFunction("reset", (args, kwNames) -> {
            Arguments.exactly("reset", args, kwNames, 0);
            state.pending = new byte[0];
            state.first = true;
            state.order = UNKNOWN;
            return PyNone.INSTANCE;
        }));
        object.setAttr("getstate", new PyNativeFunction("getstate", (args, kwNames) -> {
            Arguments.exactly("getstate", args, kwNames, 0);
            return PyTuple.of(PyBytes.of(state.pending), PyInt.of(decoderState(state)));
        }));
        object.setAttr("setstate", new PyNativeFunction("setstate", (args, kwNames) -> {
            PyObject[] values = Arguments.parameters(
                    "setstate", args, kwNames, new String[] {"state"}, new PyObject[0], 1);
            setDecoderState(state, values[0]);
            return PyNone.INSTANCE;
        }));
        return object;
    }

    /**
     * Decode one chunk, carrying whatever is not yet a whole sequence.
     *
     * <p>{@code state.pending} is written only after the walk returns, so a chunk that raised leaves the
     * carry exactly where it was, which is what lets the next chunk pick up the same partial sequence.
     */
    private static String decode(DecoderState state, byte[] input, boolean last) {
        byte[] data = join(state.pending, input);
        switch (state.canonical) {
            case "utf_8_sig":
                return sigDecode(state, data, last);
            case "utf_8":
                return tail(state, data, last, "utf_8");
            case "utf_16":
                return utf16(state, data, last);
            case "utf_16_le":
                return unit(state, data, last, 2, "utf_16_le");
            case "utf_16_be":
                return unit(state, data, last, 2, "utf_16_be");
            case "utf_32":
                return utf32(state, data, last);
            case "utf_32_le":
                return unit(state, data, last, 4, "utf_32_le");
            case "utf_32_be":
                return unit(state, data, last, 4, "utf_32_be");
            case "utf_7":
                return utf7(state, data, last);
            case "unicode_escape":
                return escaped(state, data, last, false);
            case "raw_unicode_escape":
                return escaped(state, data, last, true);
            default:
                // The one-octet family: ascii, latin_1, cp1252, cp1251, cp437, iso8859_7. Every octet is a
                // whole sequence, so nothing is ever carried.
                return Decoder.decode(data, state.canonical, state.errors);
        }
    }

    /** {@code utf-8} split at the longest prefix whose last character is whole. */
    private static String tail(DecoderState state, byte[] data, boolean last, String canonical) {
        int complete = last ? data.length : utf8Complete(data);
        String text = complete == 0
                ? ""
                : Decoder.decode(Arrays.copyOf(data, complete), canonical, state.errors);
        state.pending = Arrays.copyOfRange(data, complete, data.length);
        return text;
    }

    /**
     * {@code utf-8-sig}: the mark is looked for on the first call only, and a prefix of it — anything that
     * could still grow into the three octets — is carried rather than given a verdict
     * ({@code Lib/encodings/utf_8_sig.py:57}).
     */
    private static String sigDecode(DecoderState state, byte[] data, boolean last) {
        int from = 0;
        if (state.first) {
            if (data.length < 3) {
                if (prefixOf(data, UTF8_BOM)) {
                    state.pending = data;
                    return "";
                }
                state.first = false;
            } else if (startsWith(data, UTF8_BOM)) {
                state.first = false;
                from = 3;
            } else {
                state.first = false;
            }
        }
        byte[] body = Arrays.copyOfRange(data, from, data.length);
        return tail(state, body, last, "utf_8");
    }

    /** {@code utf-16} without an explicit order: the first two octets decide, and the name to blame. */
    private static String utf16(DecoderState state, byte[] data, boolean last) {
        if (state.order == UNKNOWN) {
            if (data.length == 0) {
                return "";
            }
            if (data.length < 2) {
                if (!last) {
                    state.pending = data;
                    return "";
                }
                // Not even a whole mark: the whole-input walk names the leftover exactly as CPython does.
                String text = Decoder.decode(data, "utf_16_le", state.errors);
                state.pending = new byte[0];
                return text;
            }
            if (startsWith(data, UTF16_LE_BOM)) {
                state.order = 0;
                data = Arrays.copyOfRange(data, 2, data.length);
            } else if (startsWith(data, UTF16_BE_BOM)) {
                state.order = 1;
                data = Arrays.copyOfRange(data, 2, data.length);
            } else {
                // CPython reads the first units before it complains about the missing mark: a run it cannot
                // read raises its own error, and a lone high surrogate is simply carried
                // (`Lib/encodings/utf_16.py:31`).
                if (!last) {
                    String text = unit(state, data, last, 2, "utf_16_le");
                    if (state.pending.length == data.length) {
                        return text;
                    }
                } else {
                    Decoder.decode(data, "utf_16_le", state.errors);
                }
                throw Slots.unicodeDecode("utf-16", data, 0, 2, "Stream does not start with BOM");
            }
        }
        return unit(state, data, last, 2, state.order == 0 ? "utf_16_le" : "utf_16_be");
    }

    /** {@code utf-32} without an explicit order, the same shape with a four-octet mark. */
    private static String utf32(DecoderState state, byte[] data, boolean last) {
        if (state.order == UNKNOWN) {
            if (data.length == 0) {
                return "";
            }
            if (data.length < 4) {
                if (!last) {
                    state.pending = data;
                    return "";
                }
                String text = Decoder.decode(data, "utf_32_le", state.errors);
                state.pending = new byte[0];
                return text;
            }
            if (startsWith(data, UTF32_LE_BOM)) {
                state.order = 0;
                data = Arrays.copyOfRange(data, 4, data.length);
            } else if (startsWith(data, UTF32_BE_BOM)) {
                state.order = 1;
                data = Arrays.copyOfRange(data, 4, data.length);
            } else {
                // Same shape as utf-16: the default order reads the run first, and only a run that reads
                // makes the missing mark the complaint (`Lib/encodings/utf_32.py:31`).
                if (!last) {
                    String text = unit(state, data, last, 4, "utf_32_le");
                    if (state.pending.length == data.length) {
                        return text;
                    }
                } else {
                    Decoder.decode(data, "utf_32_le", state.errors);
                }
                throw Slots.unicodeDecode("utf-32", data, 0, 4, "Stream does not start with BOM");
            }
        }
        return unit(state, data, last, 4, state.order == 0 ? "utf_32_le" : "utf_32_be");
    }

    /** A fixed-width walk: every {@code width} octets are one sequence, and a shorter tail is carried. */
    private static String unit(
            DecoderState state, byte[] data, boolean last, int width, String canonical) {
        int complete = last ? data.length : data.length - data.length % width;
        // A UTF-16 sequence may still need the low half of a pair: CPython carries the high surrogate
        // rather than calling the run truncated (Objects/unicodeobject.c, unicode_decode_utf16).
        if (!last && width == 2 && complete >= 2) {
            int lastUnit = canonical.endsWith("_be")
                    ? ((data[complete - 2] & 0xff) << 8) | (data[complete - 1] & 0xff)
                    : (data[complete - 2] & 0xff) | ((data[complete - 1] & 0xff) << 8);
            if (lastUnit >= 0xD800 && lastUnit <= 0xDBFF) {
                complete -= 2;
            }
        }
        String text = complete == 0
                ? ""
                : Decoder.decode(Arrays.copyOf(data, complete), canonical, state.errors);
        state.pending = Arrays.copyOfRange(data, complete, data.length);
        return text;
    }

    /** {@code utf-7}: an unterminated shift sequence is the only thing that can be carried. */
    private static String utf7(DecoderState state, byte[] data, boolean last) {
        int complete = last ? data.length : unterminatedShift(data);
        String text = complete == 0
                ? ""
                : Decoder.decode(Arrays.copyOf(data, complete), "utf_7", state.errors);
        state.pending = Arrays.copyOfRange(data, complete, data.length);
        return text;
    }

    /**
     * The escapes: both carry a backslash that may still become an escape — a trailing one included, since
     * {@code final=False} cannot tell a literal backslash from the start of {@code \}{@code uXXXX}
     * ({@code Objects/unicodeobject.c}, {@code _PyUnicode_DecodeRawUnicodeEscape}). {@code
     * raw-unicode-escape} knows only {@code \}{@code u} and {@code \}{@code U}; on the final chunk the walk
     * above reads a lone backslash as the character it is.
     */
    private static String escaped(DecoderState state, byte[] data, boolean last, boolean raw) {
        int complete = last ? data.length : escapePrefix(data, raw);
        String text = complete == 0
                ? ""
                : Decoder.decode(Arrays.copyOf(data, complete), state.canonical, state.errors);
        state.pending = Arrays.copyOfRange(data, complete, data.length);
        return text;
    }

    private static long decoderState(DecoderState state) {
        switch (state.canonical) {
            case "utf_8_sig":
                return state.first ? 1 : 0;
            case "utf_16":
            case "utf_32":
                return state.order;
            default:
                return 0;
        }
    }

    private static void setDecoderState(DecoderState state, PyObject value) {
        if (!(value instanceof PyTuple tuple) || tuple.size() < 1) {
            throw Slots.typeError("'%s' object is not subscriptable", value.getType().filedName());
        }
        state.pending = octets(tuple.at(0));
        int extra = tuple.size() > 1 ? Numbers.asIndex(tuple.at(1)).intValue() : 0;
        switch (state.canonical) {
            case "utf_8_sig":
                state.first = extra != 0;
                break;
            case "utf_16":
            case "utf_32":
                state.order = extra;
                break;
            default:
                break;
        }
    }

    // --- what is a whole sequence -----------------------------------------------------------------

    /**
     * The longest prefix of {@code data} that ends on a whole sequence, which is what a decoder that is not
     * final may read without a verdict on the octets it leaves behind. The {@code _codecs.*_decode}
     * primitives answer their {@code consumed} with exactly this count.
     */
    static int complete(String canonical, byte[] data) {
        switch (canonical) {
            case "utf_8":
                return utf8Complete(data);
            case "utf_8_sig":
                if (startsWith(data, UTF8_BOM)) {
                    return 3 + utf8Complete(Arrays.copyOfRange(data, 3, data.length));
                }
                return prefixOf(data, UTF8_BOM) ? 0 : utf8Complete(data);
            case "utf_16":
                if (startsWith(data, UTF16_LE_BOM) || startsWith(data, UTF16_BE_BOM)) {
                    return 2 + whole(data.length - 2, 2);
                }
                return whole(data.length, 2);
            case "utf_16_le":
            case "utf_16_be":
                return whole(data.length, 2);
            case "utf_32":
                if (startsWith(data, UTF32_LE_BOM) || startsWith(data, UTF32_BE_BOM)) {
                    return 4 + whole(data.length - 4, 4);
                }
                return whole(data.length, 4);
            case "utf_32_le":
            case "utf_32_be":
                return whole(data.length, 4);
            case "utf_7":
                return unterminatedShift(data);
            case "unicode_escape":
                return escapePrefix(data, false);
            case "raw_unicode_escape":
                return escapePrefix(data, true);
            default:
                return data.length;
        }
    }

    /** The largest multiple of {@code width} at most {@code length}. */
    private static int whole(int length, int width) {
        return length - length % width;
    }

    /**
     * The longest prefix of a UTF-8 run that ends on a character boundary.
     *
     * <p>A lead that promises more octets than arrived is carried only while the octets that did arrive
     * are what that lead allows; a lead followed by an octet outside its range is a refusal the whole-input
     * walk has to report, so it stays in the prefix. This is the same second-octet narrowing
     * {@code Decoder.utf8} applies, written once so the two cannot drift.
     */
    private static int utf8Complete(byte[] data) {
        int at = 0;
        while (at < data.length) {
            int lead = data[at] & 0xff;
            if (lead < 0x80) {
                at++;
                continue;
            }
            int need;
            if (lead >= 0xC2 && lead <= 0xDF) {
                need = 1;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                need = 2;
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                need = 3;
            } else {
                at++;
                continue;
            }
            if (at + need < data.length) {
                at += need + 1;
                continue;
            }
            if (plausible(data, at, need, lead)) {
                return at;
            }
            at++;
        }
        return data.length;
    }

    /** Whether the octets of a truncated sequence are still what its lead allows. */
    private static boolean plausible(byte[] data, int at, int need, int lead) {
        for (int k = 1; k <= need && at + k < data.length; k++) {
            int octet = data[at + k] & 0xff;
            if ((octet & 0xC0) != 0x80) {
                return false;
            }
            if (k == 1) {
                if (lead == 0xE0 && octet < 0xA0) {
                    return false;
                }
                if (lead == 0xED && octet > 0x9F) {
                    return false;
                }
                if (lead == 0xF0 && octet < 0x90) {
                    return false;
                }
                if (lead == 0xF4 && octet > 0x8F) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The start of the last {@code +} that no {@code -} has closed, or the length when every shift ends. */
    private static int unterminatedShift(byte[] data) {
        int at = 0;
        while (at < data.length) {
            if (data[at] != '+') {
                at++;
                continue;
            }
            int close = at + 1;
            while (close < data.length && data[close] != '-') {
                close++;
            }
            if (close == data.length) {
                return at;
            }
            at = close + 1;
        }
        return data.length;
    }

    /** The longest prefix of an escaped run that ends on a whole escape. */
    private static int escapePrefix(byte[] data, boolean raw) {
        int at = 0;
        while (at < data.length) {
            if (data[at] != '\\') {
                at++;
                continue;
            }
            if (at + 1 >= data.length) {
                return at;
            }
            int kind = data[at + 1];
            if (kind == 'u' || kind == 'U') {
                int digits = kind == 'u' ? 4 : 8;
                if (!digitsArrived(data, at + 2, digits)) {
                    return at;
                }
                at += 2 + digits;
            } else if (!raw && kind == 'x') {
                if (!digitsArrived(data, at + 2, 2)) {
                    return at;
                }
                at += 4;
            } else if (!raw && kind == 'N') {
                if (at + 2 >= data.length) {
                    return at;
                }
                if (data[at + 2] != '{') {
                    at += 2;
                    continue;
                }
                int close = at + 3;
                while (close < data.length && data[close] != '}') {
                    close++;
                }
                if (close == data.length) {
                    return at;
                }
                at = close + 1;
            } else {
                at += 2;
            }
        }
        return data.length;
    }

    /** Whether {@code count} hex digits have arrived; a non-hex one makes the escape a refusal, not a carry. */
    private static boolean digitsArrived(byte[] data, int from, int count) {
        for (int k = 0; k < count; k++) {
            if (from + k >= data.length) {
                return false;
            }
            if (Character.digit(data[from + k] & 0xff, 16) < 0) {
                return true;
            }
        }
        return true;
    }

    // --- octets -----------------------------------------------------------------------------------

    private static byte[] join(byte[] first, byte[] second) {
        if (first.length == 0) {
            return second;
        }
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int at = 0; at < prefix.length; at++) {
            if (data[at] != prefix[at]) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code data} is a prefix of {@code whole}, an empty run included. */
    private static boolean prefixOf(byte[] data, byte[] whole) {
        if (data.length > whole.length) {
            return false;
        }
        for (int at = 0; at < data.length; at++) {
            if (data[at] != whole[at]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] octets(PyObject value) {
        return PyMemoryView.bufferOctets(value);
    }
}
