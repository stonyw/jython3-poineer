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
     * The seventeen codec modules this runtime has, under the names {@code encodings} imports.
     *
     * <p>A name reaches one of them exactly as CPython's {@code encodings.search_function} decides it: the
     * {@code _Py_normalize_encoding} of Objects/unicodeobject.c:3605 first, then one step through the alias
     * table of {@code Lib/encodings/aliases.py} as {@code Lib/encodings/__init__.py:87} reads it. Only the
     * aliases of these seventeen are copied, and they are copied verbatim, because an invented alias would answer
     * for a name the reference refuses — the way {@code u-t-f8} normalizes to {@code u_t_f8} and is no codec
     * at all, while {@code utf 8} and {@code UTF-8} and {@code utf-8} all normalize to {@code utf_8} and are.
     * {@code unicode_escape} has no alias at all, which is why {@code unicodeescape} is refused while
     * {@code unicode_escape} is not.
     */
    private static final Set<String> CODECS = Set.of(
            "ascii", "latin_1", "utf_8", "utf_8_sig", "utf_16", "utf_16_le", "utf_16_be",
            "utf_32", "utf_32_le", "utf_32_be", "utf_7", "cp1252", "cp1251", "cp437",
            "iso8859_7", "unicode_escape", "raw_unicode_escape");

    /** The lines of {@code aliases.py} whose target is one of the seventeen. */
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
            Map.entry("u7", "utf_7"),
            Map.entry("utf7", "utf_7"),
            Map.entry("unicode_1_1_utf_7", "utf_7"),
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
            Map.entry("1251", "cp1251"),
            Map.entry("windows_1251", "cp1251"),
            Map.entry("437", "cp437"),
            Map.entry("cspc8codepage437", "cp437"),
            Map.entry("ibm437", "cp437"),
            Map.entry("csisolatingreek", "iso8859_7"),
            Map.entry("ecma_118", "iso8859_7"),
            Map.entry("elot_928", "iso8859_7"),
            Map.entry("greek", "iso8859_7"),
            Map.entry("greek8", "iso8859_7"),
            Map.entry("iso_8859_7", "iso8859_7"),
            Map.entry("iso_8859_7_1987", "iso8859_7"),
            Map.entry("iso_ir_126", "iso8859_7"),
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

    /** A character below {@code 0x80} stands for itself in all four charmap codecs, so only the high half is kept. */
    static final int[] CP1251_HIGH = high(
            "\u0402\u0403\u201a\u0453\u201e\u2026\u2020\u2021\u20ac\u2030\u0409\u2039\u040a\u040c\u040b\u040f"
            + "\u0452\u2018\u2019\u201c\u201d\u2022\u2013\u2014\u0000\u2122\u0459\u203a\u045a\u045c\u045b\u045f"
            + "\u00a0\u040e\u045e\u0408\u00a4\u0490\u00a6\u00a7\u0401\u00a9\u0404\u00ab\u00ac\u00ad\u00ae\u0407"
            + "\u00b0\u00b1\u0406\u0456\u0491\u00b5\u00b6\u00b7\u0451\u2116\u0454\u00bb\u0458\u0405\u0455\u0457"
            + "\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041a\u041b\u041c\u041d\u041e\u041f"
            + "\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042a\u042b\u042c\u042d\u042e\u042f"
            + "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043a\u043b\u043c\u043d\u043e\u043f"
            + "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044a\u044b\u044c\u044d\u044e\u044f");

    static final int[] CP437_HIGH = high(
            "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5"
            + "\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00a2\u00a3\u00a5\u20a7\u0192"
            + "\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u2310\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb"
            + "\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510"
            + "\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567"
            + "\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580"
            + "\u03b1\u00df\u0393\u03c0\u03a3\u03c3\u00b5\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u03c6\u03b5\u2229"
            + "\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u00a0");

    /** {@code iso8859-7} keeps the C1 range as its own characters, so its table starts one block higher. */
    static final int[] ISO8859_7_HIGH = high(
            "\u0080\u0081\u0082\u0083\u0084\u0085\u0086\u0087\u0088\u0089\u008a\u008b\u008c\u008d\u008e\u008f"
            + "\u0090\u0091\u0092\u0093\u0094\u0095\u0096\u0097\u0098\u0099\u009a\u009b\u009c\u009d\u009e\u009f"
            + "\u00a0\u2018\u2019\u00a3\u20ac\u20af\u00a6\u00a7\u00a8\u00a9\u037a\u00ab\u00ac\u00ad\u0000\u2015"
            + "\u00b0\u00b1\u00b2\u00b3\u0384\u0385\u0386\u00b7\u0388\u0389\u038a\u00bb\u038c\u00bd\u038e\u038f"
            + "\u0390\u0391\u0392\u0393\u0394\u0395\u0396\u0397\u0398\u0399\u039a\u039b\u039c\u039d\u039e\u039f"
            + "\u03a0\u03a1\u0000\u03a3\u03a4\u03a5\u03a6\u03a7\u03a8\u03a9\u03aa\u03ab\u03ac\u03ad\u03ae\u03af"
            + "\u03b0\u03b1\u03b2\u03b3\u03b4\u03b5\u03b6\u03b7\u03b8\u03b9\u03ba\u03bb\u03bc\u03bd\u03be\u03bf"
            + "\u03c0\u03c1\u03c2\u03c3\u03c4\u03c5\u03c6\u03c7\u03c8\u03c9\u03ca\u03cb\u03cc\u03cd\u03ce\u0000");

    /**
     * The 128 characters above {@code 0x7f}, with {@code -1} where the table has no character: one control
     * character per octet is what the reference's tables read, and the holes are the ones CPython's
     * {@code charmap_build} leaves.
     */
    static int[] high(String table) {
        int[] codePoints = new int[128];
        for (int at = 0; at < 128; at++) {
            char c = table.charAt(at);
            codePoints[at] = c == '\u0000' ? -1 : c;
        }
        return codePoints;
    }

    /** The octet one of the tabulated codecs writes for a character, or {@code -1} where it has none. */
    static int octetIn(int[] high, int codePoint) {
        if (codePoint < 0x80) {
            return codePoint;
        }
        for (int at = 0; at < 128; at++) {
            if (high[at] == codePoint) {
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
        /** A table of 128 characters above {@code 0x7f}, holes included: {@code cp1251}, {@code cp437}, {@code iso8859-7}. */
        TABLED,
        /** A backslash escape for every character, so nothing is ever refused. */
        ESCAPED,
        /** A backslash escape only where the character does not fit one octet, so nothing is refused. */
        RAW_ESCAPED
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
            Kind kind, int[] high) {

        private static final Codec ASCII =
                new Codec("ascii", "ordinal not in range(128)", 0x80, true, null, Kind.ONE_BYTE, null);
        private static final Codec LATIN1 =
                new Codec("latin-1", "ordinal not in range(256)", 0x100, true, null, Kind.ONE_BYTE, null);
        private static final Codec UTF8 =
                new Codec("utf-8", "surrogates not allowed", 0, true, null, Kind.UTF8, null);
        private static final Codec UTF8_SIG =
                new Codec("utf-8", "surrogates not allowed", 0, true, UTF8_BOM, Kind.UTF8, null);
        private static final Codec UTF16 =
                new Codec("utf-16", "surrogates not allowed", 0, false, UTF16_BOM, Kind.UTF16, null);
        private static final Codec UTF16_LE =
                new Codec("utf-16-le", "surrogates not allowed", 0, false, null, Kind.UTF16, null);
        private static final Codec UTF16_BE =
                new Codec("utf-16-be", "surrogates not allowed", 0, false, null, Kind.UTF16, null);
        private static final Codec UTF32 =
                new Codec("utf-32", "surrogates not allowed", 0, false, UTF32_BOM, Kind.UTF32, null);
        private static final Codec UTF32_LE =
                new Codec("utf-32-le", "surrogates not allowed", 0, false, null, Kind.UTF32, null);
        private static final Codec UTF32_BE =
                new Codec("utf-32-be", "surrogates not allowed", 0, false, null, Kind.UTF32, null);
        private static final Codec CP1252 =
                new Codec("charmap", "character maps to <undefined>", 0, true, null, Kind.CHARMAP, null);
        private static final Codec CP1251 =
                new Codec("charmap", "character maps to <undefined>", 0, true, null, Kind.TABLED,
                        CP1251_HIGH);
        private static final Codec CP437 =
                new Codec("charmap", "character maps to <undefined>", 0, true, null, Kind.TABLED,
                        CP437_HIGH);
        private static final Codec ISO8859_7 =
                new Codec("charmap", "character maps to <undefined>", 0, true, null, Kind.TABLED,
                        ISO8859_7_HIGH);
        private static final Codec UNICODE_ESCAPE =
                new Codec("unicode-escape", "", 0, false, null, Kind.ESCAPED, null);
        private static final Codec RAW_UNICODE_ESCAPE =
                new Codec("rawunicodeescape", "", 0, false, null, Kind.RAW_ESCAPED, null);

        /** Whether this is a character the codec refuses to write at all. */
        boolean refuses(int codePoint) {
            return switch (kind) {
                case ONE_BYTE -> codePoint >= limit;
                case UTF8, UTF16, UTF32 -> isSurrogate(codePoint);
                case CHARMAP -> octetOf(codePoint) < 0;
                case TABLED -> octetIn(high, codePoint) < 0;
                case ESCAPED, RAW_ESCAPED -> false;
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
                case TABLED -> out.write(octetIn(high, codePoint));
                case ESCAPED -> escaped(out, codePoint);
                case RAW_ESCAPED -> rawEscaped(out, codePoint);
            }
        }

        /**
         * The escape {@code raw-unicode-escape} writes for one character: the octet itself below
         * {@code 0x100}, and otherwise the hex escape, so {@code '\u00e9'} is one octet while
         * {@code '\u0100'} is six.
         */
        private static void rawEscaped(ByteArrayOutputStream out, int codePoint) {
            if (codePoint < 0x100) {
                out.write(codePoint);
                return;
            }
            String escape = escapeOf(codePoint);
            for (int at = 0; at < escape.length(); at++) {
                out.write(escape.charAt(at));
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
        if (!knows(encoding)) {
            return registered(text, encoding, errors);
        }
        if (codec(encoding).equals("utf_7")) {
            // UTF-7 is the one codec here with a mode that outlives a character: a run of characters it
            // cannot write directly is one shift sequence, so the per-character walk cannot express it.
            return PyBytes.wrap(utf7(text));
        }
        return PyBytes.wrap(writing(codecOf(encoding), text, errors));
    }

    /**
     * {@code utf7_encode} (Objects/unicodeobject.c:{@code _PyUnicode_EncodeUTF7}): the direct sets and the
     * modified-Base64 shift.
     *
     * <p>Three groups of characters are written as themselves: the alphanumerics and {@code '(),-./:?}, the
     * optional set {@code !"#$%&*;<=>@[]^_`{|}}, and the four ASCII whitespace characters. {@code '+'} is
     * never one of them outside a shift, where it is the two octets {@code +-}; inside a shift it is data
     * like any other character. Every other character — {@code ~} and the other controls included — is
     * written as its UTF-16BE code units in modified Base64, which is why {@code 'é+é'} is one shift
     * ({@code +AOkAKwDp-}) rather than {@code +AOk-} followed by {@code +-}.
     *
     * <p>A shift ends at the first direct character. If that character is one the Base64 alphabet also
     * holds, or is the {@code -} that would otherwise be read as the shift's own terminator, an explicit
     * {@code -} is written first; otherwise the direct character itself ends the shift, which is why
     * {@code 'é!'} is {@code +AOk!} and {@code 'éa'} is {@code +AOk-a}. A shift still open at the end of
     * the text is closed with {@code -}.
     */
    private static byte[] utf7(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean shift = false;
        int buffer = 0;
        int bits = 0;
        for (int unit = 0; unit < text.length(); ) {
            int codePoint = text.codePointAt(unit);
            unit += Character.charCount(codePoint);
            if (shift && utf7Direct(codePoint)) {
                // The shift has to hand over its padded last group before the direct character ends it.
                if (bits > 0) {
                    out.write(BASE64.charAt((buffer << (6 - bits)) & 0x3F));
                    bits = 0;
                }
                if (utf7Base64(codePoint) || codePoint == '-') {
                    out.write('-');
                }
                out.write(codePoint);
                shift = false;
                continue;
            }
            if (!shift) {
                if (codePoint == '+') {
                    out.write('+');
                    out.write('-');
                    continue;
                }
                if (utf7Direct(codePoint)) {
                    out.write(codePoint);
                    continue;
                }
                out.write('+');
                shift = true;
                buffer = 0;
                bits = 0;
            }
            if (codePoint >= 0x10000) {
                int pair = codePoint - 0x10000;
                buffer = (buffer << 16) | (0xD800 | (pair >> 10));
                bits += 16;
                while (bits >= 6) {
                    bits -= 6;
                    out.write(BASE64.charAt((buffer >>> bits) & 0x3F));
                }
                codePoint = 0xDC00 | (pair & 0x3FF);
            }
            buffer = (buffer << 16) | codePoint;
            bits += 16;
            while (bits >= 6) {
                bits -= 6;
                out.write(BASE64.charAt((buffer >>> bits) & 0x3F));
            }
        }
        if (shift) {
            if (bits > 0) {
                out.write(BASE64.charAt((buffer << (6 - bits)) & 0x3F));
            }
            out.write('-');
        }
        return out.toByteArray();
    }

    /** The modified-Base64 alphabet of RFC 2152, which is the standard one without its padding. */
    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** Whether UTF-7 writes a character as itself rather than inside a shift sequence. */
    private static boolean utf7Direct(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9')
                || "'(),-./:?".indexOf(codePoint) >= 0
                || "!\"#$%&*;<=>@[]^_`{|}".indexOf(codePoint) >= 0
                || codePoint == ' '
                || codePoint == '\t'
                || codePoint == '\n'
                || codePoint == '\r';
    }

    /** Whether a direct character would otherwise be swallowed by the shift sequence it follows. */
    private static boolean utf7Base64(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '+'
                || codePoint == '/';
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
    static String escapeOf(int codePoint) {
        if (codePoint >= 0x10000) {
            return String.format("\\U%08x", codePoint);
        }
        return codePoint >= 0x100
                ? String.format("\\u%04x", codePoint)
                : String.format("\\x%02x", codePoint);
    }

    /**
     * {@code surrogatepass} from the exception side, encoding: the code units a run of characters needs, or
     * null where the codec the exception names cannot carry a surrogate at all.
     *
     * <p>The name is read the way a call reads one, and a name this runtime has no codec for is a refusal
     * like any other rather than a lookup error: the handler has an exception in hand, and its answer to "I
     * cannot spell this" is to re-raise it. {@code utf-8-sig} is refused along with the one-byte family
     * because its caller would have to place a byte order mark, which a run of one character has no room
     * for; {@code utf-16} does write one, which is why the code units here carry none.
     */
    static PyBytes surrogateUnits(String text, String encoding) {
        String name;
        Codec codec;
        try {
            name = codec(encoding);
            codec = codecOf(encoding);
        } catch (PyRaise unknown) {
            return null;
        }
        if (name.equals("utf_8_sig") || !codec.carriesSurrogates()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int codePoint : text.codePoints().toArray()) {
            codec.write(out, codePoint);
        }
        return PyBytes.wrap(out.toByteArray());
    }

    /**
     * {@code surrogatepass} from the exception side, decoding: the one character exactly one code unit of
     * the codec spells, or null where the run is not one unit wide or the unit is no surrogate.
     *
     * <p>UTF-8, UTF-16 and UTF-32 are the only codecs here; the two UTF families read the octets in the
     * order their name asks for, and the unqualified {@code utf-16} and {@code utf-32} are little-endian
     * the way their decoders are.
     */
    static PyStr surrogateCharacter(byte[] data, String encoding, long start, long end) {
        String name;
        try {
            name = codec(encoding);
        } catch (PyRaise unknown) {
            return null;
        }
        int width = switch (name) {
            case "utf_8" -> 3;
            case "utf_16", "utf_16_le", "utf_16_be" -> 2;
            case "utf_32", "utf_32_le", "utf_32_be" -> 4;
            default -> 0;
        };
        if (width == 0 || end - start != width) {
            return null;
        }
        int at = (int) start;
        int unit;
        if (width == 3) {
            int lead = data[at] & 0xff;
            int middle = data[at + 1] & 0xff;
            int last = data[at + 2] & 0xff;
            if ((lead & 0xf0) != 0xe0 || (middle & 0xc0) != 0x80 || (last & 0xc0) != 0x80) {
                return null;
            }
            unit = ((lead & 0x0f) << 12) | ((middle & 0x3f) << 6) | (last & 0x3f);
        } else if (width == 2) {
            int first = data[at] & 0xff;
            int second = data[at + 1] & 0xff;
            unit = name.endsWith("_be") ? (first << 8) | second : (second << 8) | first;
        } else {
            int first = data[at] & 0xff;
            int second = data[at + 1] & 0xff;
            int third = data[at + 2] & 0xff;
            int fourth = data[at + 3] & 0xff;
            unit = name.endsWith("_be")
                    ? (first << 24) | (second << 16) | (third << 8) | fourth
                    : (fourth << 24) | (third << 16) | (second << 8) | first;
        }
        if (unit < 0xD800 || unit > 0xDFFF) {
            return null;
        }
        return PyStr.of(String.valueOf((char) unit));
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

    /** Whether one of the seventeen answers to this name; no exception either way. */
    static boolean knows(String encoding) {
        try {
            codec(encoding);
            return true;
        } catch (PyRaise unknown) {
            return false;
        }
    }

    /**
     * A codec from the registry rather than from the seventeen: {@code codecs.lookup(name)}'s own answer,
     * which is how {@code idna} and the other pure-Python codecs reach {@code str.encode}. The encoder
     * answers bytes or {@code (bytes, length)}, and both are accepted as the reference accepts them.
     */
    private static PyBytes registered(String text, String encoding, String errors) {
        PyObject info = CodecsModule.lookup(encoding);
        PyObject answer = info.getAttr("encode")
                .call(new PyObject[] {PyStr.of(text), PyStr.of(errors)}, null);
        PyObject bytes = answer instanceof PyTuple
                ? Slots.getItem(answer, PyInt.of(0))
                : answer;
        if (!(bytes instanceof PyBytes encoded)) {
            throw Slots.typeError(
                    "encoder returned %s instead of bytes", Slots.argumentName(answer));
        }
        return encoded;
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
            case "cp1251" -> Codec.CP1251;
            case "cp437" -> Codec.CP437;
            case "iso8859_7" -> Codec.ISO8859_7;
            case "unicode_escape" -> Codec.UNICODE_ESCAPE;
            case "raw_unicode_escape" -> Codec.RAW_UNICODE_ESCAPE;
            default -> throw new AssertionError(encoding);
        };
    }
}
