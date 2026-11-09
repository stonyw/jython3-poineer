package org.python.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * {@code str.encode}, and the error handlers it walks when a character does not fit the codec.
 *
 * <p>Three shapes of complaint, and they are not the same. The one-byte family ({@code unicode_encode_ucs1},
 * Objects/unicodeobject.c:7378) collects <em>every</em> character at or above its limit into one range and
 * answers for the whole run; UTF-8 does the same but its only offender is a surrogate; UTF-16 (6441) never
 * collects at all — it stops at the first bad character and hands a range of one, which is why {@code
 * "a\ud800\udc00b"} is one refusal while the same pair above {@code 0x80} is two.
 *
 * <p>Of the eight names the registry files ({@code _PyCodec_InitRegistry}, Python/codecs.c:1577) this
 * runtime answers seven. Six of them are inside the two families above and are called without looking
 * anything up, and the registry is asked only when a run actually fails: {@code "x".encode("ascii","nope")}
 * is {@code b'x'} and the name is never mentioned, while {@code "é".encode("ascii","nope")} is refused in
 * the registry's own words. The seventh, {@code namereplace}, needs the Unicode name database, which is not
 * in this runtime — measured against this JDK 25 and CPython's unicodedata 16.0.0, the reference names
 * 148,853 characters, {@code Character.getName} names 294,579 of which 145,726 the reference leaves
 * unnamed, and the two disagree on the spelling of 108,840 of the rest (it writes CJK ideographs by block)
 * — so it is refused rather than answered with a table that would be wrong.
 *
 * <p>Two handlers write what they replace through the codec, and one writes raw octets. {@code replace}
 * answers {@code '?'} per character and {@code backslashreplace} an escape per character, both as text, so
 * UTF-16 turns them into two bytes each; {@code surrogateescape} is the one whose answer is a bytes, and the
 * one-byte family and UTF-8 take it as a fast path that writes the octet {@code ch - 0xdc00} for the
 * characters in {@code 0xdc80..0xdcff} and re-raises the <em>rest</em> of the run — the range it complains
 * about starts where the escaping stopped, so a leading {@code '\udc80'} moves the position of the
 * {@code '\xe9'} behind it. Everywhere else that answer is a one-octet reply to a two-byte codec, and
 * UTF-16 refuses it outright ({@code _PyUnicode_EncodeUTF16}:6558 raises for an odd-length bytes).
 */
final class Encoder {

    /**
     * The seven codec modules this runtime has, under the names {@code encodings} imports.
     *
     * <p>A name reaches one of them exactly as CPython's {@code encodings.search_function} decides it: the
     * {@code _Py_normalize_encoding} of Objects/unicodeobject.c:3605 first, then one step through the alias
     * table of {@code Lib/encodings/aliases.py} as {@code Lib/encodings/__init__.py:87} reads it. Only the
     * aliases of these seven are copied, and they are copied verbatim, because an invented alias would answer
     * for a name the reference refuses — the way {@code u-t-f8} normalizes to {@code u_t_f8} and is no codec
     * at all, while {@code utf 8} and {@code UTF-8} and {@code utf-8} all normalize to {@code utf_8} and are.
     */
    private static final Set<String> CODECS = Set.of(
            "ascii", "latin_1", "utf_8", "utf_8_sig", "utf_16", "utf_16_le", "utf_16_be");

    /** The lines of {@code aliases.py} whose target is one of the seven. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("646", "ascii"),
            Map.entry("ansi_x3.4_1968", "ascii"),
            Map.entry("ansi_x3_4_1968", "ascii"),
            Map.entry("ansi_x3.4_1986", "ascii"),
            Map.entry("cp367", "ascii"),
            Map.entry("csascii", "ascii"),
            Map.entry("ibm367", "ascii"),
            Map.entry("iso646_us", "ascii"),
            Map.entry("iso_646.irv_1991", "ascii"),
            Map.entry("iso_ir_6", "ascii"),
            Map.entry("us", "ascii"),
            Map.entry("us_ascii", "ascii"),
            Map.entry("8859", "latin_1"),
            Map.entry("cp819", "latin_1"),
            Map.entry("csisolatin1", "latin_1"),
            Map.entry("ibm819", "latin_1"),
            Map.entry("iso8859", "latin_1"),
            Map.entry("iso8859_1", "latin_1"),
            Map.entry("iso_8859_1", "latin_1"),
            Map.entry("iso_8859_1_1987", "latin_1"),
            Map.entry("iso_ir_100", "latin_1"),
            Map.entry("l1", "latin_1"),
            Map.entry("latin", "latin_1"),
            Map.entry("latin1", "latin_1"),
            Map.entry("u16", "utf_16"),
            Map.entry("utf16", "utf_16"),
            Map.entry("unicodebigunmarked", "utf_16_be"),
            Map.entry("utf_16be", "utf_16_be"),
            Map.entry("unicodelittleunmarked", "utf_16_le"),
            Map.entry("utf_16le", "utf_16_le"),
            Map.entry("u8", "utf_8"),
            Map.entry("utf", "utf_8"),
            Map.entry("utf8", "utf_8"),
            Map.entry("utf8_ucs2", "utf_8"),
            Map.entry("utf8_ucs4", "utf_8"),
            Map.entry("cp65001", "utf_8"));

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** The byte order mark UTF-16 writes ahead of everything, an empty string included. */
    private static final byte[] UTF16_BOM = {(byte) 0xFF, (byte) 0xFE};

    /** Seven of the eight names the registry files, in the order its table walks them. */
    private enum Handler {
        STRICT,
        IGNORE,
        REPLACE,
        XMLCHARREFREPLACE,
        BACKSLASHREPLACE,
        SURROGATEESCAPE,
        SURROGATEPASS
    }

    /**
     * One codec: the name its own errors carry, the sentence they end with, the limit under which a
     * character is fine, and whether a refusal collects a run.
     */
    private record Codec(String reported, String reason, int limit, boolean collects, byte[] prefix) {

        private static final Codec ASCII = new Codec("ascii", "ordinal not in range(128)", 0x80, true, null);
        private static final Codec LATIN1 =
                new Codec("latin-1", "ordinal not in range(256)", 0x100, true, null);
        private static final Codec UTF8 = new Codec("utf-8", "surrogates not allowed", 0, true, null);
        private static final Codec UTF8_SIG =
                new Codec("utf-8", "surrogates not allowed", 0, true, UTF8_BOM);
        private static final Codec UTF16 =
                new Codec("utf-16", "surrogates not allowed", 0, false, UTF16_BOM);
        private static final Codec UTF16_LE =
                new Codec("utf-16-le", "surrogates not allowed", 0, false, null);
        private static final Codec UTF16_BE =
                new Codec("utf-16-be", "surrogates not allowed", 0, false, null);

        /** Whether this is a character the codec refuses to write at all. */
        boolean refuses(int codePoint) {
            return limit == 0 ? isSurrogate(codePoint) : codePoint >= limit;
        }

        /** The octets this codec writes for one character it does accept. */
        void write(ByteArrayOutputStream out, int codePoint) {
            if (limit != 0) {
                out.write(codePoint);
                return;
            }
            if (reported.startsWith("utf-16")) {
                if (codePoint < 0x10000) {
                    units(out, codePoint, -1);
                    return;
                }
                int pair = codePoint - 0x10000;
                units(out, 0xD800 | (pair >> 10), 0xDC00 | (pair & 0x3FF));
                return;
            }
            utf8(out, codePoint);
        }

        /** The two code units of UTF-16, in the order this codec's name says. */
        private void units(ByteArrayOutputStream out, int first, int second) {
            if (reported.endsWith("be")) {
                big(out, first);
                if (second >= 0) {
                    big(out, second);
                }
                return;
            }
            little(out, first);
            if (second >= 0) {
                little(out, second);
            }
        }

        private static void big(ByteArrayOutputStream out, int unit) {
            out.write(unit >> 8);
            out.write(unit & 0xff);
        }

        private static void little(ByteArrayOutputStream out, int unit) {
            out.write(unit & 0xff);
            out.write(unit >> 8);
        }

        /** The UTF-8 sequence of one character, written by hand because a surrogate passes through it. */
        private static void utf8(ByteArrayOutputStream out, int codePoint) {
            if (codePoint < 0x80) {
                out.write(codePoint);
            } else if (codePoint < 0x800) {
                out.write(0xC0 | (codePoint >> 6));
                out.write(0x80 | (codePoint & 0x3F));
            } else if (codePoint < 0x10000) {
                out.write(0xE0 | (codePoint >> 12));
                out.write(0x80 | ((codePoint >> 6) & 0x3F));
                out.write(0x80 | (codePoint & 0x3F));
            } else {
                out.write(0xF0 | (codePoint >> 18));
                out.write(0x80 | ((codePoint >> 12) & 0x3F));
                out.write(0x80 | ((codePoint >> 6) & 0x3F));
                out.write(0x80 | (codePoint & 0x3F));
            }
        }
    }

    private Encoder() {}

    /** {@code s.encode(encoding, errors)} and {@code bytes(s, encoding, errors)}: the same call. */
    static PyBytes encode(String text, String encoding, String errors) {
        return PyBytes.wrap(writing(codecOf(encoding), text, errors));
    }

    /** {@code PyObject_ASCII}: the repr as the encoder's {@code backslashreplace} writes it, read back. */
    static String asciiEscaped(String text) {
        return new String(writing(Codec.ASCII, text, "backslashreplace"), StandardCharsets.US_ASCII);
    }

    /**
     * The walk: each accepted character goes out through the codec, and each run of refused ones through
     * the handler that answers for it.
     *
     * <p>The handler's name is settled at the first failure and not before, which is what makes a name
     * nobody knows harmless until something actually goes wrong.
     */
    private static byte[] writing(Codec codec, String text, String errors) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (codec.prefix != null) {
            out.writeBytes(codec.prefix);
        }
        int at = 0;
        int unit = 0;
        while (unit < text.length()) {
            int codePoint = text.codePointAt(unit);
            if (!codec.refuses(codePoint)) {
                codec.write(out, codePoint);
                at++;
                unit += Character.charCount(codePoint);
                continue;
            }
            Handler handler = named(errors);
            int stop = unit;
            int end = at;
            do {
                stop += Character.charCount(text.codePointAt(stop));
                end++;
            } while (codec.collects && stop < text.length() && codec.refuses(text.codePointAt(stop)));
            handled(codec, handler, text, out, unit, stop, at, end);
            unit = stop;
            at = end;
        }
        return out.toByteArray();
    }

    /**
     * What one handler answers for the run of characters at {@code [from, to)} of the text and
     * {@code [start, stop)} of the code points.
     */
    private static void handled(
            Codec codec,
            Handler handler,
            String text,
            ByteArrayOutputStream out,
            int from,
            int to,
            long start,
            long stop) {
        switch (handler) {
            case STRICT -> throw Slots.unicodeEncode(codec.reported, text, start, stop, codec.reason);
            case IGNORE -> {}
            case REPLACE -> {
                for (int unit = from; unit < to; ) {
                    unit += Character.charCount(text.codePointAt(unit));
                    codec.write(out, '?');
                }
            }
            case BACKSLASHREPLACE -> write(codec, out, text, from, to, true, false);
            case XMLCHARREFREPLACE -> write(codec, out, text, from, to, false, true);
            case SURROGATEESCAPE -> escape(codec, out, text, from, to, start, stop);
            case SURROGATEPASS -> {
                if (codec.limit != 0) {
                    // surrogatepass asks the encoding whether it is one of the four that carry a
                    // surrogate through, and a one-byte codec is not one of them (Python/codecs.c:1237).
                    throw Slots.unicodeEncode(codec.reported, text, start, stop, codec.reason);
                }
                for (int unit = from; unit < to; ) {
                    int codePoint = text.codePointAt(unit);
                    unit += Character.charCount(codePoint);
                    codec.write(out, codePoint);
                }
            }
        }
    }

    /** The escapes or the character references of a run, written as text and then encoded. */
    private static void write(
            Codec codec,
            ByteArrayOutputStream out,
            String text,
            int from,
            int to,
            boolean hex,
            boolean decimal) {
        StringBuilder replacement = new StringBuilder();
        for (int unit = from; unit < to; ) {
            int codePoint = text.codePointAt(unit);
            unit += Character.charCount(codePoint);
            if (hex) {
                replacement.append(escapeOf(codePoint));
            } else {
                replacement.append("&#").append(codePoint).append(';');
            }
        }
        for (int at = 0; at < replacement.length(); ) {
            int codePoint = replacement.codePointAt(at);
            at += Character.charCount(codePoint);
            codec.write(out, codePoint);
        }
    }

    /** {@code codec_handler_write_unicode_hex} (Python/codecs.c:719): the width the character is worth. */
    private static String escapeOf(int codePoint) {
        if (codePoint >= 0x10000) {
            return String.format("\\U%08x", codePoint);
        }
        return codePoint >= 0x100
                ? String.format("\\u%04x", codePoint)
                : String.format("\\x%02x", codePoint);
    }

    /**
     * {@code surrogateescape}: the octet each {@code 0xdc80..0xdcff} stands for, and the rest of the run
     * refused from where the escaping stopped.
     */
    private static void escape(
            Codec codec,
            ByteArrayOutputStream out,
            String text,
            int from,
            int to,
            long start,
            long stop) {
        if (!codec.collects) {
            // One octet is not an answer a two-byte codec can use, so the run is refused as it arrived.
            throw Slots.unicodeEncode(codec.reported, text, start, stop, codec.reason);
        }
        int unit = from;
        long at = start;
        while (unit < to) {
            int codePoint = text.codePointAt(unit);
            if (codePoint < 0xDC80 || codePoint > 0xDCFF) {
                break;
            }
            out.write(codePoint - 0xDC00);
            unit += Character.charCount(codePoint);
            at++;
        }
        if (unit == to) {
            return;
        }
        throw Slots.unicodeEncode(codec.reported, text, at, stop, codec.reason);
    }

    /** {@code Py_UNICODE_IS_SURROGATE}: the two ranges no character of a well-formed text is in. */
    private static boolean isSurrogate(int codePoint) {
        return codePoint >= 0xD800 && codePoint <= 0xDFFF;
    }

    /** The registry's own answer to a name: the seven it files, and the one this runtime has no table for. */
    private static Handler named(String errors) {
        return switch (errors) {
            case "strict" -> Handler.STRICT;
            case "ignore" -> Handler.IGNORE;
            case "replace" -> Handler.REPLACE;
            case "xmlcharrefreplace" -> Handler.XMLCHARREFREPLACE;
            case "backslashreplace" -> Handler.BACKSLASHREPLACE;
            case "surrogateescape" -> Handler.SURROGATEESCAPE;
            case "surrogatepass" -> Handler.SURROGATEPASS;
            case "namereplace" -> throw Slots.notImplemented(
                    "str.encode error handler namereplace");
            default -> throw Slots.lookupError("unknown error handler name '" + errors + "'");
        };
    }

    /**
     * {@code _Py_normalize_encoding} (Objects/unicodeobject.c:3605) and one step through the alias table: the
     * name as {@code codecs.lookup} reads it, or a refusal of the name as it was written.
     */
    static String codec(String encoding) {
        String name = normalize(encoding);
        String alias = ALIASES.get(name);
        if (alias == null) {
            alias = ALIASES.get(name.replace('.', '_'));
        }
        String module = alias == null ? name : alias;
        if (CODECS.contains(module)) {
            return module;
        }
        throw Slots.lookupError("unknown encoding: " + encoding);
    }

    /**
     * Everything that is not a letter, a digit or the dot of a package name is punctuation, and a run of it
     * is one separator — never a leading one. Letters are folded to lowercase as they are copied, and a
     * character that is not ASCII is punctuation rather than a letter, which is what makes {@code é} worth
     * nothing in an encoding name.
     */
    private static String normalize(String encoding) {
        StringBuilder name = new StringBuilder(encoding.length());
        boolean punct = false;
        for (int at = 0; at < encoding.length(); at++) {
            char c = encoding.charAt(at);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.') {
                if (punct && !name.isEmpty()) {
                    name.append('_');
                }
                name.append(Character.toLowerCase(c));
                punct = false;
            } else {
                punct = true;
            }
        }
        return name.toString();
    }

    private static Codec codecOf(String encoding) {
        return switch (codec(encoding)) {
            case "ascii" -> Codec.ASCII;
            case "latin_1" -> Codec.LATIN1;
            case "utf_8" -> Codec.UTF8;
            case "utf_8_sig" -> Codec.UTF8_SIG;
            case "utf_16" -> Codec.UTF16;
            case "utf_16_le" -> Codec.UTF16_LE;
            default -> Codec.UTF16_BE;
        };
    }
}
