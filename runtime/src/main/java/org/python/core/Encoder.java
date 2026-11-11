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
 * answers for the whole run — and so does {@code cp1252}, whose limit is a set of holes rather than a
 * number; UTF-8 does the same but its only offender is a surrogate; UTF-16 (6441) and UTF-32 never collect at
 * all — they stop at the first bad character and hand a range of one, which is why {@code
 * "a\ud800\udc00b"} is one refusal while the same pair above {@code 0x80} is two. {@code unicode_escape}
 * refuses nothing at all, because every code point has an escape it can write.
 *
 * <p>Of the eight names the registry files ({@code _PyCodec_InitRegistry}, Python/codecs.c:1577) this
 * runtime answers all eight. Seven of them are inside the two families above and are called without looking
 * anything up, and the registry is asked only when a run actually fails: {@code "x".encode("ascii","nope")}
 * is {@code b'x'} and the name is never mentioned, while {@code "é".encode("ascii","nope")} is refused in
 * the registry's own words. The eighth, {@code namereplace}, writes the character's name as the text
 * {@code \N{...}} — run through the codec like the other text-shaped handlers — and falls back to the hex
 * escape for a character the reference leaves unnamed. The table behind it is exported from the reference's
 * own {@code unicodedata} (Unicode 16.0.0) rather than transcribed; {@link UnicodeNames} describes it.
 *
 * <p>Three handlers write a text they build through the codec, and one writes raw octets. {@code replace}
 * answers {@code '?'} per character, {@code backslashreplace} an escape per character and {@code namereplace}
 * a name or an escape per character, all as text, so UTF-16 turns them into two bytes each and UTF-32 into
 * four; {@code surrogateescape} is the one whose answer is a bytes, and the one-byte family, {@code cp1252}
 * and UTF-8 take it as a fast path that writes the octet {@code ch - 0xdc00} for the characters in
 * {@code 0xdc80..0xdcff} and re-raises the <em>rest</em> of the run — the range it complains about starts
 * where the escaping stopped, so a leading {@code '\udc80'} moves the position of the {@code '\xe9'} behind
 * it. Everywhere else that answer is a one-octet reply to a wider codec, and UTF-16 and UTF-32 refuse it
 * outright ({@code _PyUnicode_EncodeUTF16}:6558 raises for an odd-length bytes).
 */
final class Encoder {

    /**
     * The twelve codec modules this runtime has, under the names {@code encodings} imports.
     *
     * <p>A name reaches one of them exactly as CPython's {@code encodings.search_function} decides it: the
     * {@code _Py_normalize_encoding} of Objects/unicodeobject.c:3605 first, then one step through the alias
     * table of {@code Lib/encodings/aliases.py} as {@code Lib/encodings/__init__.py:87} reads it. Only the
     * aliases of these twelve are copied, and they are copied verbatim, because an invented alias would answer
     * for a name the reference refuses — the way {@code u-t-f8} normalizes to {@code u_t_f8} and is no codec
     * at all, while {@code utf 8} and {@code UTF-8} and {@code utf-8} all normalize to {@code utf_8} and are.
     * {@code unicode_escape} has no alias at all, which is why {@code unicodeescape} is refused while
     * {@code unicode_escape} is not.
     */
    private static final Set<String> CODECS = Set.of(
            "ascii", "latin_1", "utf_8", "utf_8_sig", "utf_16", "utf_16_le", "utf_16_be",
            "utf_32", "utf_32_le", "utf_32_be", "cp1252", "unicode_escape");

    /** The lines of {@code aliases.py} whose target is one of the twelve. */
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
            Map.entry("u32", "utf_32"),
            Map.entry("utf32", "utf_32"),
            Map.entry("utf_32be", "utf_32_be"),
            Map.entry("utf_32le", "utf_32_le"),
            Map.entry("1252", "cp1252"),
            Map.entry("windows_1252", "cp1252"),
            Map.entry("u8", "utf_8"),
            Map.entry("utf", "utf_8"),
            Map.entry("utf8", "utf_8"),
            Map.entry("utf8_ucs2", "utf_8"),
            Map.entry("utf8_ucs4", "utf_8"),
            Map.entry("cp65001", "utf_8"));

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** The byte order mark UTF-16 writes ahead of everything, an empty string included. */
    private static final byte[] UTF16_BOM = {(byte) 0xFF, (byte) 0xFE};

    /** The byte order mark UTF-32 writes ahead of everything, an empty string included. */
    private static final byte[] UTF32_BOM = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x00};

    /**
     * The 32 octets {@code cp1252} writes differently from {@code latin-1}, with {@code -1} where the table
     * has no character at all: {@code 0x81}, {@code 0x8d}, {@code 0x8f}, {@code 0x90} and {@code 0x9d} are the
     * five holes Microsoft left.
     */
    private static final int[] CP1252_HIGH = {
        0x20AC, -1, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, -1, 0x017D, -1,
        -1, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, -1, 0x017E, 0x0178
    };

    /** The character {@code cp1252} reads for one octet, or {@code -1} where the octet is undefined. */
    static int cp1252(int octet) {
        return octet >= 0x80 && octet <= 0x9F ? CP1252_HIGH[octet - 0x80] : octet;
    }

    /** The octet {@code cp1252} writes for one character, or {@code -1} where it has none. */
    static int octetOf(int codePoint) {
        if (codePoint < 0x80 || (codePoint >= 0xA0 && codePoint <= 0xFF)) {
            return codePoint;
        }
        if (codePoint <= 0x9F) {
            return -1;
        }
        for (int at = 0; at < CP1252_HIGH.length; at++) {
            if (CP1252_HIGH[at] == codePoint) {
                return 0x80 + at;
            }
        }
        return -1;
    }

    /** The eight names the registry files, in the order its table walks them. */
    private enum Handler {
        STRICT,
        IGNORE,
        REPLACE,
        XMLCHARREFREPLACE,
        BACKSLASHREPLACE,
        SURROGATEESCAPE,
        SURROGATEPASS,
        NAMEREPLACE
    }

    /** How a codec decides which characters it can write, and how it writes the ones it can. */
    private enum Kind {
        /** One octet per character below a limit: {@code ascii} and {@code latin-1}. */
        ONE_BYTE,
        /** The UTF-8 sequence of a character, surrogates included when a handler asks for them. */
        UTF8,
        /** One or two code units of two octets each. */
        UTF16,
        /** One code point of four octets. */
        UTF32,
        /** A table of 256 octets, five of which stand for no character. */
        CHARMAP,
        /** A backslash escape for every character, so nothing is ever refused. */
        ESCAPED
    }

    /**
     * One codec: the name its own errors carry, the sentence they end with, the limit under which a
     * character is fine, whether a refusal collects a run, and how it writes.
     *
     * <p>{@code limit} is read only by the one-byte family; the other kinds answer {@link #refuses} their own
     * way. {@code reason} is read only where a refusal actually happens, which is why {@code unicode-escape}
     * can leave it empty.
     */
    private record Codec(String reported, String reason, int limit, boolean collects, byte[] prefix,
            Kind kind) {

        private static final Codec ASCII =
                new Codec("ascii", "ordinal not in range(128)", 0x80, true, null, Kind.ONE_BYTE);
        private static final Codec LATIN1 =
                new Codec("latin-1", "ordinal not in range(256)", 0x100, true, null, Kind.ONE_BYTE);
        private static final Codec UTF8 =
                new Codec("utf-8", "surrogates not allowed", 0, true, null, Kind.UTF8);
        private static final Codec UTF8_SIG =
                new Codec("utf-8", "surrogates not allowed", 0, true, UTF8_BOM, Kind.UTF8);
        private static final Codec UTF16 =
                new Codec("utf-16", "surrogates not allowed", 0, false, UTF16_BOM, Kind.UTF16);
        private static final Codec UTF16_LE =
                new Codec("utf-16-le", "surrogates not allowed", 0, false, null, Kind.UTF16);
        private static final Codec UTF16_BE =
                new Codec("utf-16-be", "surrogates not allowed", 0, false, null, Kind.UTF16);
        private static final Codec UTF32 =
                new Codec("utf-32", "surrogates not allowed", 0, false, UTF32_BOM, Kind.UTF32);
        private static final Codec UTF32_LE =
                new Codec("utf-32-le", "surrogates not allowed", 0, false, null, Kind.UTF32);
        private static final Codec UTF32_BE =
                new Codec("utf-32-be", "surrogates not allowed", 0, false, null, Kind.UTF32);
        private static final Codec CP1252 =
                new Codec("charmap", "character maps to <undefined>", 0, true, null, Kind.CHARMAP);
        private static final Codec UNICODE_ESCAPE =
                new Codec("unicode-escape", "", 0, false, null, Kind.ESCAPED);

        /** Whether this is a character the codec refuses to write at all. */
        boolean refuses(int codePoint) {
            return switch (kind) {
                case ONE_BYTE -> codePoint >= limit;
                case UTF8, UTF16, UTF32 -> isSurrogate(codePoint);
                case CHARMAP -> octetOf(codePoint) < 0;
                case ESCAPED -> false;
            };
        }

        /** Whether {@code surrogatepass} may hand this codec a surrogate to write. */
        boolean carriesSurrogates() {
            return kind == Kind.UTF8 || kind == Kind.UTF16 || kind == Kind.UTF32;
        }

        /** The octets this codec writes for one character it does accept. */
        void write(ByteArrayOutputStream out, int codePoint) {
            switch (kind) {
                case ONE_BYTE -> out.write(codePoint);
                case UTF8 -> utf8(out, codePoint);
                case UTF16 -> units(out, codePoint);
                case UTF32 -> quads(out, codePoint);
                case CHARMAP -> out.write(octetOf(codePoint));
                case ESCAPED -> escaped(out, codePoint);
            }
        }

        /** The code units of UTF-16, in the order this codec's name says. */
        private void units(ByteArrayOutputStream out, int codePoint) {
            if (codePoint < 0x10000) {
                units(out, codePoint, -1);
                return;
            }
            int pair = codePoint - 0x10000;
            units(out, 0xD800 | (pair >> 10), 0xDC00 | (pair & 0x3FF));
        }

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

        /** The four octets of UTF-32, in the order this codec's name says. */
        private void quads(ByteArrayOutputStream out, int codePoint) {
            if (reported.endsWith("be")) {
                out.write(codePoint >> 24);
                out.write((codePoint >> 16) & 0xff);
                out.write((codePoint >> 8) & 0xff);
                out.write(codePoint & 0xff);
                return;
            }
            out.write(codePoint & 0xff);
            out.write((codePoint >> 8) & 0xff);
            out.write((codePoint >> 16) & 0xff);
            out.write(codePoint >> 24);
        }

        /** The backslash escape of one character: {@code unicode-escape} writes nothing else. */
        private static void escaped(ByteArrayOutputStream out, int codePoint) {
            switch (codePoint) {
                case '\\' -> {
                    out.write('\\');
                    out.write('\\');
                }
                case '\n' -> {
                    out.write('\\');
                    out.write('n');
                }
                case '\r' -> {
                    out.write('\\');
                    out.write('r');
                }
                case '\t' -> {
                    out.write('\\');
                    out.write('t');
                }
                default -> {
                    if (codePoint >= 0x20 && codePoint <= 0x7E) {
                        out.write(codePoint);
                        return;
                    }
                    String escape = escapeOf(codePoint);
                    for (int at = 0; at < escape.length(); at++) {
                        out.write(escape.charAt(at));
                    }
                }
            }
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
            case NAMEREPLACE -> writeNames(codec, out, text, from, to);
            case SURROGATEESCAPE -> escape(codec, out, text, from, to, start, stop);
            case SURROGATEPASS -> {
                if (!codec.carriesSurrogates()) {
                    // surrogatepass asks the encoding whether it is one of the utf family that carries a
                    // surrogate through, and a one-byte or table codec is not (Python/codecs.c:1237).
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
        emit(codec, out, replacement);
    }

    /**
     * {@code namereplace}: {@code \N{NAME}} for a character the reference names, and the hex escape
     * {@code backslashreplace} writes for the few it leaves unnamed.
     */
    private static void writeNames(
            Codec codec, ByteArrayOutputStream out, String text, int from, int to) {
        StringBuilder replacement = new StringBuilder();
        for (int unit = from; unit < to; ) {
            int codePoint = text.codePointAt(unit);
            unit += Character.charCount(codePoint);
            String name = UnicodeNames.nameOf(codePoint);
            if (name == null) {
                replacement.append(escapeOf(codePoint));
            } else {
                replacement.append("\\N{").append(name).append('}');
            }
        }
        emit(codec, out, replacement);
    }

    /** Writes a replacement text through the codec, which is what every text-shaped handler does. */
    private static void emit(Codec codec, ByteArrayOutputStream out, CharSequence replacement) {
        for (int at = 0; at < replacement.length(); ) {
            int codePoint = Character.codePointAt(replacement, at);
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
            // One octet is not an answer a wider codec can use, so the run is refused as it arrived.
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

    /**
     * The registry's own answer to a name: the seven it files, and the one this runtime has no table for.
     *
     * <p>The refusal clips the name the way {@code "%.400s"} does, so a handler named with 405 characters is
     * complained about with the first 400 of them; {@code unknown encoding} below carries no such clip.
     */
    private static Handler named(String errors) {
        return switch (errors) {
            case "strict" -> Handler.STRICT;
            case "ignore" -> Handler.IGNORE;
            case "replace" -> Handler.REPLACE;
            case "xmlcharrefreplace" -> Handler.XMLCHARREFREPLACE;
            case "backslashreplace" -> Handler.BACKSLASHREPLACE;
            case "surrogateescape" -> Handler.SURROGATEESCAPE;
            case "surrogatepass" -> Handler.SURROGATEPASS;
            case "namereplace" -> Handler.NAMEREPLACE;
            default -> throw Slots.lookupError("unknown error handler name '" + clipped(errors) + "'");
        };
    }

    /** {@code %.400s}: how much of a handler's name the registry's complaint carries. */
    static String clipped(String name) {
        return name.length() <= 400 ? name : name.substring(0, 400);
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
            case "utf_16_be" -> Codec.UTF16_BE;
            case "utf_32" -> Codec.UTF32;
            case "utf_32_le" -> Codec.UTF32_LE;
            case "utf_32_be" -> Codec.UTF32_BE;
            case "cp1252" -> Codec.CP1252;
            case "unicode_escape" -> Codec.UNICODE_ESCAPE;
            default -> throw new AssertionError(encoding);
        };
    }
}
