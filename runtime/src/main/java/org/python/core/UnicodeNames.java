package org.python.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The Unicode name database, read in both directions: the name a character has, and the character a
 * name stands for.
 *
 * <p>The table is not written here. {@code unicode-names.txt.gz} is exported from the reference's own
 * data — the primary names are what {@code unicodedata.name} answers in CPython 3.14.10 (Unicode
 * 16.0.0), and the aliases are the formal ones of {@code NameAliases.txt} for that version — by
 * {@code runtime/tools/gen_unicode_names.py}. That matters for {@code namereplace}, whose answer is
 * the primary name, and for {@code unicode_escape}'s {@code \N{...}}, which also accepts an alias. A
 * character the reference leaves unnamed (a surrogate, a private-use value, an unassigned one) has no
 * name here either, so the handler falls back to the hex escape exactly where the reference does.
 *
 * <p>Four families spell their name as a prefix and the code point in uppercase hex — the CJK unified
 * ideographs, the Khitan characters, the Nushu characters and the Tangut ideographs — so the file
 * stores their ranges instead of a line each. Tangut is the one family the decoder can read and the
 * encoder cannot write: {@code unicodedata.name} leaves those code points unnamed and
 * {@code unicodedata.lookup} still knows the spelling, which is why the file marks those ranges
 * {@code decode}.
 *
 * <p>Names are matched the way {@code _PyUnicode_FromName} matches them: folded to uppercase and
 * otherwise exact. {@code LATIN_SMALL_LETTER_A} and {@code SNOW MAN} are unknown names, and the two
 * separators programs guess at are the two the reference does not accept. Named sequences are a
 * {@code unicodedata.lookup} feature only; {@code \N{...}} refuses them, and so does this table.
 */
final class UnicodeNames {

    private UnicodeNames() {}

    /** The name of a character, or {@code null} where the reference has none. */
    static String nameOf(int codePoint) {
        String name = Table.NAMES.get(codePoint);
        if (name != null) {
            return name;
        }
        for (Range range : Table.RANGES) {
            if (range.written && codePoint >= range.first && codePoint <= range.last) {
                return range.prefix + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** The character a name stands for, or {@code -1} where the reference knows no such name. */
    static int codePointOf(String name) {
        String folded = fold(name);
        Integer known = Table.CODES.get(folded);
        if (known != null) {
            return known;
        }
        for (Range range : Table.RANGES) {
            if (!folded.startsWith(range.prefix)) {
                continue;
            }
            int codePoint = hex(folded, range.prefix.length());
            if (codePoint >= range.first && codePoint <= range.last) {
                return codePoint;
            }
        }
        return -1;
    }

    /** Uppercases one character at a time, the way the reference folds a name before matching it. */
    private static String fold(String name) {
        char[] folded = name.toCharArray();
        for (int at = 0; at < folded.length; at++) {
            folded[at] = Character.toUpperCase(folded[at]);
        }
        return new String(folded);
    }

    /** The value of the hex digits of {@code name} from {@code from}, or {@code -1} where they are not. */
    private static int hex(String name, int from) {
        // A family spells its hex without padding, and the reference compares the spelling rather than
        // the number: CJK UNIFIED IDEOGRAPH-04E00 is not a name.
        if (name.length() == from || name.length() - from > 6 || name.charAt(from) == '0') {
            return -1;
        }
        int value = 0;
        for (int at = from; at < name.length(); at++) {
            int digit = Character.digit(name.charAt(at), 16);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /** One algorithmic family: {@code prefix + hex} names a run of characters. */
    private record Range(int first, int last, String prefix, boolean written) {}

    /** Loaded on first use, so a program that never names a character pays nothing for the table. */
    private static final class Table {
        private static final Map<Integer, String> NAMES = new HashMap<>(1 << 16);
        private static final Map<String, Integer> CODES = new HashMap<>(1 << 16);
        private static final List<Range> RANGES = new ArrayList<>();

        static {
            try (InputStream raw = UnicodeNames.class.getResourceAsStream("unicode-names.txt.gz")) {
                if (raw == null) {
                    throw new AssertionError("unicode-names.txt.gz is not on the classpath");
                }
                try (BufferedReader lines =
                        new BufferedReader(
                                new InputStreamReader(
                                        new GZIPInputStream(raw), StandardCharsets.US_ASCII))) {
                    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                        if (!line.isEmpty() && line.charAt(0) != '#') {
                            read(line);
                        }
                    }
                }
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static void read(String line) {
            int space = line.indexOf(' ');
            int semicolon = line.indexOf(';', space);
            String kind = line.substring(0, space);
            String left = line.substring(space + 1, semicolon);
            String right = line.substring(semicolon + 1);
            switch (kind) {
                case "name" -> {
                    int codePoint = Integer.parseInt(left, 16);
                    NAMES.put(codePoint, right);
                    CODES.put(right, codePoint);
                }
                case "alias" -> CODES.putIfAbsent(right, Integer.parseInt(left, 16));
                case "range" -> RANGES.add(range(left, right, true));
                case "decode" -> RANGES.add(range(left, right, false));
                default -> throw new AssertionError(line);
            }
        }

        private static Range range(String span, String prefix, boolean written) {
            int dash = span.indexOf('-');
            return new Range(
                    Integer.parseInt(span.substring(0, dash), 16),
                    Integer.parseInt(span.substring(dash + 1), 16),
                    prefix,
                    written);
        }
    }
}
