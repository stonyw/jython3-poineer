package org.python.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * {@code unicodedata}: the Unicode Character Database, as far as this runtime can answer it.
 *
 * <p>The reference's module is generated from the UCD tables and exposes a dozen readers over them
 * ({@code Modules/unicodedata.c}). What is written here is the part the standard library actually
 * imports it for: the four normal forms, which the platform's own Unicode tables can answer for any
 * character, and {@code is_normalized}, which is the same question asked without building the string.
 * A reader that is not here — {@code category}, {@code name}, {@code east_asian_width} and the rest —
 * is absent rather than approximated, so {@code hasattr} keeps telling the truth.
 *
 * <p>{@code test.support.os_helper} calls {@code normalize('NFD', ...)} on a file name when
 * {@code sys.platform} is {@code 'darwin'}, and every test target imports that module at module
 * scope; a missing {@code unicodedata} therefore stops all seven before a single test is collected.
 * The normal forms themselves are the platform's: a decomposed code point sequence is a property of
 * the character, not of the implementation, so both sides agree on the result.
 *
 * <p>{@code ucd_3_2_0} is a second database, not this one under an older label: IDNA and
 * {@code stringprep} (RFC 3454) froze their rules at Unicode 3.2, and the two versions disagree on
 * 60,027 categories, 607 combining classes, 770 decompositions and thousands of numeric and width
 * properties. {@link Database320} reads the table {@code runtime/tools/gen_unicode_320.py} exported
 * from the reference's own 3.2.0 reader. Its normalization is computed here from that table — the
 * canonical ordering and the composition pairs are 3.2's, not the platform's.
 */
final class UnicodeDataModule {

    /** The version CPython 3.14.10 ships, which is what {@code unicodedata.unidata_version} reads. */
    private static final String UNIDATA_VERSION = "16.0.0";

    /** The version {@code unicodedata.ucd_3_2_0.unidata_version} reads. */
    private static final String LEGACY_VERSION = "3.2.0";

    private static final String DOC =
            "This module provides access to the Unicode Character Database which\n"
                    + "defines character properties for all Unicode characters. The data in\n"
                    + "this database is based on the UnicodeData.txt file version\n"
                    + UNIDATA_VERSION
                    + " which is publicly available from ftp://ftp.unicode.org/.\n"
                    + "\n"
                    + "The module uses the same names and symbols as defined by the\n"
                    + "UnicodeData File Format "
                    + UNIDATA_VERSION
                    + ".";

    private static final String NORMALIZE_DOC =
            "normalize($module, form, unistr, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return the normal form 'form' for the Unicode string unistr.\n"
                    + "\n"
                    + "Valid values for form are 'NFC', 'NFKC', 'NFD', and 'NFKD'.";

    private static final String IS_NORMALIZED_DOC =
            "is_normalized($module, form, unistr, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return whether the Unicode string unistr is in the normal form 'form'.\n"
                    + "\n"
                    + "Valid values for form are 'NFC', 'NFKC', 'NFD', and 'NFKD'.";

    static {
        Members legacy = Members.of(PyType.UCD);
        legacy.addOne(
                "category",
                "Returns the general category assigned to the character chr as string.",
                (self, chr) -> PyStr.of(Database320.category(character("category", chr))));
        legacy.addOne(
                "combining",
                "Returns the canonical combining class assigned to the character chr as integer.\n"
                        + "\n"
                        + "Returns 0 if no combining class is defined.",
                (self, chr) -> PyInt.of(Database320.combining(character("combining", chr))));
        legacy.addOne(
                "bidirectional",
                "Returns the bidirectional class assigned to the character chr as string.\n"
                        + "\n"
                        + "If no such value is defined, an empty string is returned.",
                (self, chr) -> PyStr.of(Database320.bidirectional(character("bidirectional", chr))));
        legacy.addOne(
                "decomposition",
                "Returns the character decomposition mapping assigned to the character chr as string.\n"
                        + "\n"
                        + "An empty string is returned in case no such mapping is defined.",
                (self, chr) -> PyStr.of(Database320.decomposition(character("decomposition", chr))));
        legacy.addOne(
                "mirrored",
                "Returns the mirrored property assigned to the character chr as integer.\n"
                        + "\n"
                        + "Returns 1 if the character has been identified as a \"mirrored\"\n"
                        + "character in bidirectional text, 0 otherwise.",
                (self, chr) -> PyInt.of(Database320.mirrored(character("mirrored", chr))));
        legacy.addOne(
                "east_asian_width",
                "Returns the east asian width assigned to the character chr as string.",
                (self, chr) -> PyStr.of(Database320.eastAsianWidth(character("east_asian_width", chr))));
        legacy.add("decimal", "Converts a Unicode character into its equivalent decimal value.\n"
                        + "\n"
                        + "Returns the decimal value assigned to the character chr as integer.\n"
                        + "If no such value is defined, default is returned, or, if not given,\n"
                        + "ValueError is raised.",
                (self, args, kwNames) -> {
                    PyObject[] pair = optional("decimal", args, kwNames);
                    int value = Database320.decimal(character("decimal", pair[0]));
                    if (value >= 0) {
                        return PyInt.of(value);
                    }
                    if (pair[1] != null) {
                        return pair[1];
                    }
                    throw Slots.valueError("not a decimal digit");
                });
        legacy.add("digit", "Converts a Unicode character into its equivalent digit value.\n"
                        + "\n"
                        + "Returns the digit value assigned to the character chr as integer.\n"
                        + "If no such value is defined, default is returned, or, if not given,\n"
                        + "ValueError is raised.",
                (self, args, kwNames) -> {
                    PyObject[] pair = optional("digit", args, kwNames);
                    int value = Database320.digit(character("digit", pair[0]));
                    if (value >= 0) {
                        return PyInt.of(value);
                    }
                    if (pair[1] != null) {
                        return pair[1];
                    }
                    throw Slots.valueError("not a digit");
                });
        legacy.add("numeric", "Converts a Unicode character into its equivalent numeric value.\n"
                        + "\n"
                        + "Returns the numeric value assigned to the character chr as float.\n"
                        + "If no such value is defined, default is returned, or, if not given,\n"
                        + "ValueError is raised.",
                (self, args, kwNames) -> {
                    PyObject[] pair = optional("numeric", args, kwNames);
                    double value = Database320.numeric(character("numeric", pair[0]));
                    if (!Double.isNaN(value)) {
                        return PyFloat.of(value);
                    }
                    if (pair[1] != null) {
                        return pair[1];
                    }
                    throw Slots.valueError("not a numeric character");
                });
        legacy.add("name", "Returns the name assigned to the character chr as a string.\n"
                        + "\n"
                        + "If no name is defined, default is returned, or, if not given,\n"
                        + "ValueError is raised.",
                (self, args, kwNames) -> {
                    PyObject[] pair = optional("name", args, kwNames);
                    String name = Database320.name(character("name", pair[0]));
                    if (name != null) {
                        return PyStr.of(name);
                    }
                    if (pair[1] != null) {
                        return pair[1];
                    }
                    throw Slots.valueError("no such name");
                });
        legacy.addOne(
                "lookup",
                "Look up character by name.\n"
                        + "\n"
                        + "If a character with the given name is found, return the\n"
                        + "corresponding character.  If not found, KeyError is raised.",
                (self, name) -> {
                    String text = lookupText(name);
                    int codePoint = UnicodeNames.codePointOf(text);
                    if (codePoint < 0) {
                        throw Slots.keyError(
                                PyStr.of("undefined character name '%s'".formatted(text)));
                    }
                    return PyStr.of(new String(Character.toChars(codePoint)));
                });
        legacy.add("is_normalized", "Return whether the Unicode string unistr is in the normal form 'form'.\n"
                        + "\n"
                        + "Valid values for form are 'NFC', 'NFKC', 'NFD', and 'NFKD'.",
                (self, args, kwNames) -> {
                    String[] pair = exactly("is_normalized", args, kwNames);
                    String input = pair[1];
                    return PyBool.of(
                            input.isEmpty()
                                    || Database320.normalize(input, normalForm(pair[0])).equals(input));
                });
        legacy.add("normalize", "Return the normal form 'form' for the Unicode string unistr.\n"
                        + "\n"
                        + "Valid values for form are 'NFC', 'NFKC', 'NFD', and 'NFKD'.",
                (self, args, kwNames) -> {
                    String[] pair = exactly("normalize", args, kwNames);
                    String input = pair[1];
                    if (input.isEmpty()) {
                        return PyStr.of(input);
                    }
                    return PyStr.of(Database320.normalize(input, normalForm(pair[0])));
                });
        legacy.addField("unidata_version", null, self -> PyStr.of(LEGACY_VERSION));
    }

    private UnicodeDataModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("unicodedata");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "unicodedata");
        module.set("unidata_version", PyStr.of(UNIDATA_VERSION));
        module.set("UCD", PyType.UCD);
        module.set("ucd_3_2_0", new Ucd());
        module.set(
                "normalize",
                PyNativeFunction.positional(
                                "normalize",
                                args -> {
                                    String[] pair = two("normalize", args);
                                    Normalizer.Form form = form(pair[0]);
                                    String input = pair[1];
                                    if (input.isEmpty()) {
                                        return PyStr.of(input);
                                    }
                                    return PyStr.of(Normalizer.normalize(input, form));
                                })
                        .documented(NORMALIZE_DOC));
        module.set(
                "is_normalized",
                PyNativeFunction.positional(
                                "is_normalized",
                                args -> {
                                    String[] pair = two("is_normalized", args);
                                    Normalizer.Form form = form(pair[0]);
                                    String input = pair[1];
                                    if (input.isEmpty()) {
                                        return PyBool.TRUE;
                                    }
                                    return PyBool.of(
                                            Normalizer.normalize(input, form).equals(input));
                                })
                        .documented(IS_NORMALIZED_DOC));
        return module;
    }

    /** {@code unicodedata.UCD}: the reader IDNA and {@code stringprep} ask their questions of. */
    static final class Ucd extends PyObject {

        Ucd() {
            super(PyType.UCD);
        }

        @Override
        public String repr() {
            return "<unicodedata.UCD object at 0x%x>".formatted(System.identityHashCode(this));
        }
    }

    // --- argument reading ----------------------------------------------------------------------

    /** The code point a single-character {@code chr} argument names. */
    private static int character(String method, PyObject argument) {
        if (!(argument instanceof PyStr text)) {
            throw Slots.typeError(
                    "%s() argument must be a unicode character, not %s",
                    method, Slots.argumentName(argument));
        }
        String value = text.getValue();
        int length = value.codePointCount(0, value.length());
        if (length != 1) {
            throw Slots.typeError(
                    "%s(): argument must be a unicode character, not a string of length %d",
                    method, length);
        }
        return value.codePointAt(0);
    }

    /** The one or two arguments of {@code decimal}/{@code digit}/{@code numeric}/{@code name}. */
    private static PyObject[] optional(String method, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("UCD.%s() takes no keyword arguments", method);
        }
        if (args.length < 1) {
            throw Slots.typeError("%s expected at least 1 argument, got 0", method);
        }
        if (args.length > 2) {
            throw Slots.typeError("%s expected at most 2 arguments, got %d", method, args.length);
        }
        return new PyObject[] {args[0], args.length == 2 ? args[1] : null};
    }

    /** The two positional arguments of {@code normalize}/{@code is_normalized}. */
    private static String[] exactly(String method, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("UCD.%s() takes no keyword arguments", method);
        }
        if (args.length != 2) {
            throw Slots.typeError("%s expected 2 arguments, got %d", method, args.length);
        }
        if (!(args[0] instanceof PyStr form)) {
            throw Slots.typeError(
                    "%s() argument 1 must be str, not %s",
                    method, Slots.argumentName(args[0]));
        }
        if (!(args[1] instanceof PyStr unistr)) {
            throw Slots.typeError(
                    "%s() argument 2 must be str, not %s",
                    method, Slots.argumentName(args[1]));
        }
        return new String[] {form.getValue(), unistr.getValue()};
    }

    /**
     * The name {@code lookup} is given: a string, or bytes decoded the way the reference decodes them
     * (UTF-8 with replacement). A bytearray is refused by name, as it is there.
     */
    private static String lookupText(PyObject argument) {
        if (argument instanceof PyStr text) {
            return text.getValue();
        }
        if (argument instanceof PyByteArray) {
            throw Slots.typeError(
                    "lookup() argument must be read-only bytes-like object, not bytearray");
        }
        if (argument instanceof PyBytes bytes) {
            return new String(PyBytes.octets(bytes), StandardCharsets.UTF_8);
        }
        throw Slots.typeError(
                "a bytes-like object is required, not '%s'", Slots.argumentName(argument));
    }

    private enum LegacyForm {
        NFC(false),
        NFKC(true),
        NFD(false),
        NFKD(true);

        final boolean compat;

        LegacyForm(boolean compat) {
            this.compat = compat;
        }
    }

    private static LegacyForm normalForm(String name) {
        return switch (name) {
            case "NFC" -> LegacyForm.NFC;
            case "NFKC" -> LegacyForm.NFKC;
            case "NFD" -> LegacyForm.NFD;
            case "NFKD" -> LegacyForm.NFKD;
            default -> throw Slots.valueError("invalid normalization form");
        };
    }

    /**
     * The two arguments, read the way Clinic reads an unspelled pair: the count first, then each
     * argument's own type error naming its position.
     */
    private static String[] two(String name, PyObject[] args) {
        if (args.length != 2) {
            throw Slots.typeError("%s expected 2 arguments, got %d", name, args.length);
        }
        String form = text(name, 1, args[0]);
        String input = text(name, 2, args[1]);
        return new String[] {form, input};
    }

    private static String text(String function, int at, PyObject value) {
        if (!(value instanceof PyStr text)) {
            throw Slots.typeError(
                    "%s() argument %d must be str, not %s",
                    function, at, Slots.argumentName(value));
        }
        return text.getValue();
    }

    private static Normalizer.Form form(String name) {
        return switch (name) {
            case "NFC" -> Normalizer.Form.NFC;
            case "NFKC" -> Normalizer.Form.NFKC;
            case "NFD" -> Normalizer.Form.NFD;
            case "NFKD" -> Normalizer.Form.NFKD;
            default -> throw Slots.valueError("invalid normalization form");
        };
    }

    // --- Unicode 3.2.0 --------------------------------------------------------------------------

    /**
     * The 3.2.0 properties, as exported by {@code runtime/tools/gen_unicode_320.py}.
     *
     * <p>Properties are stored as maximal runs over code points, so the whole range answers from a
     * binary search. Decompositions are kept twice: the canonical mapping and the compatibility one
     * (which also contains every canonical mapping), both already split into code points. Composition
     * pairs carry the same exclusions the 3.2.0 normalizer applies, because the generator took them
     * from the reference's own round trip rather than from a transcribed list. Names are not here:
     * 3.2.0 spells all of its 95,156 names exactly as 16.0.0 does, so {@link UnicodeNames} answers
     * them and this table only records which code points have one.
     */
    private static final class Database320 {

        private static final String RESOURCE = "/org/python/core/unicode-320.txt.gz";

        private static final int[] STARTS;
        private static final int[] ENDS;
        private static final String[] CATEGORY;
        private static final int[] COMBINING;
        private static final String[] BIDIRECTIONAL;
        private static final boolean[] MIRRORED;
        private static final String[] EAST_ASIAN_WIDTH;
        private static final double[] NUMERIC;
        private static final int[] DECIMAL;
        private static final int[] DIGIT;
        private static final int[] NAMED_STARTS;
        private static final int[] NAMED_ENDS;
        private static final Map<Integer, String> DECOMPOSITION;
        private static final Map<Integer, int[]> CANONICAL;
        private static final Map<Integer, int[]> COMPAT;
        private static final Map<Long, Integer> COMPOSE;

        static {
            List<int[]> spans = new ArrayList<>();
            List<String> categories = new ArrayList<>();
            List<int[]> combining = new ArrayList<>();
            List<String> directional = new ArrayList<>();
            List<Boolean> mirrored = new ArrayList<>();
            List<String> widths = new ArrayList<>();
            List<double[]> numeric = new ArrayList<>();
            List<int[]> decimal = new ArrayList<>();
            List<int[]> digit = new ArrayList<>();
            List<int[]> named = new ArrayList<>();
            Map<Integer, String> raw = new HashMap<>();
            Map<Integer, int[]> canonical = new HashMap<>();
            Map<Integer, int[]> compat = new HashMap<>();
            Map<Long, Integer> compose = new HashMap<>();
            try (BufferedReader reader = open()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    int space = line.indexOf(' ');
                    String kind = line.substring(0, space);
                    String body = line.substring(space + 1);
                    switch (kind) {
                        case "range" -> {
                            String[] fields = body.split(";", -1);
                            spans.add(span(fields[0]));
                            categories.add(fields[1]);
                            combining.add(new int[] {Integer.parseInt(fields[2])});
                            directional.add(fields[3]);
                            mirrored.add(fields[4].equals("1"));
                            widths.add(fields[5]);
                            numeric.add(
                                    new double[] {
                                        fields[6].equals("-")
                                                ? Double.NaN
                                                : Double.parseDouble(fields[6])
                                    });
                            decimal.add(new int[] {fields[7].equals("-") ? -1 : Integer.parseInt(fields[7])});
                            digit.add(new int[] {fields[8].equals("-") ? -1 : Integer.parseInt(fields[8])});
                        }
                        case "decomp" -> {
                            int semicolon = body.indexOf(';');
                            int codePoint = Integer.parseInt(body.substring(0, semicolon), 16);
                            String text = body.substring(semicolon + 1);
                            boolean compatible = text.charAt(0) == '<';
                            int[] parts = split(compatible ? text.substring(text.indexOf('>') + 2) : text);
                            raw.put(codePoint, text);
                            compat.put(codePoint, parts);
                            if (!compatible) {
                                canonical.put(codePoint, parts);
                            }
                        }
                        case "named" -> named.add(span(body));
                        case "compose" -> {
                            int semicolon = body.indexOf(';');
                            String pair = body.substring(0, semicolon);
                            int plus = pair.indexOf('+');
                            int first = Integer.parseInt(pair.substring(0, plus), 16);
                            int second = Integer.parseInt(pair.substring(plus + 1), 16);
                            compose.put(
                                    ((long) first << 32) | (second & 0xFFFFFFFFL),
                                    Integer.parseInt(body.substring(semicolon + 1), 16));
                        }
                        default -> throw new IllegalStateException("unicode-320: " + kind);
                    }
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("unicode-320.txt.gz", failure);
            }
            STARTS = new int[spans.size()];
            ENDS = new int[spans.size()];
            CATEGORY = categories.toArray(new String[0]);
            COMBINING = new int[spans.size()];
            BIDIRECTIONAL = directional.toArray(new String[0]);
            MIRRORED = new boolean[spans.size()];
            EAST_ASIAN_WIDTH = widths.toArray(new String[0]);
            NUMERIC = new double[spans.size()];
            DECIMAL = new int[spans.size()];
            DIGIT = new int[spans.size()];
            for (int i = 0; i < spans.size(); i++) {
                STARTS[i] = spans.get(i)[0];
                ENDS[i] = spans.get(i)[1];
                COMBINING[i] = combining.get(i)[0];
                MIRRORED[i] = mirrored.get(i);
                NUMERIC[i] = numeric.get(i)[0];
                DECIMAL[i] = decimal.get(i)[0];
                DIGIT[i] = digit.get(i)[0];
            }
            NAMED_STARTS = new int[named.size()];
            NAMED_ENDS = new int[named.size()];
            for (int i = 0; i < named.size(); i++) {
                NAMED_STARTS[i] = named.get(i)[0];
                NAMED_ENDS[i] = named.get(i)[1];
            }
            CANONICAL = Map.copyOf(canonical);
            COMPAT = Map.copyOf(compat);
            DECOMPOSITION = Map.copyOf(raw);
            COMPOSE = Map.copyOf(compose);
        }

        private static BufferedReader open() throws IOException {
            InputStream raw = Database320.class.getResourceAsStream(RESOURCE);
            if (raw == null) {
                throw new IOException("missing " + RESOURCE);
            }
            return new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.US_ASCII));
        }

        private static int[] span(String text) {
            int dash = text.indexOf('-');
            return new int[] {
                Integer.parseInt(text.substring(0, dash), 16),
                Integer.parseInt(text.substring(dash + 1), 16)
            };
        }

        private static int[] split(String text) {
            String[] pieces = text.split(" ");
            int[] parts = new int[pieces.length];
            for (int i = 0; i < pieces.length; i++) {
                parts[i] = Integer.parseInt(pieces[i], 16);
            }
            return parts;
        }

        private static int indexOf(int codePoint) {
            int low = 0;
            int high = STARTS.length - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (codePoint < STARTS[middle]) {
                    high = middle - 1;
                } else if (codePoint > ENDS[middle]) {
                    low = middle + 1;
                } else {
                    return middle;
                }
            }
            throw new IllegalStateException("unicode-320: no range for U+%04X".formatted(codePoint));
        }

        private static boolean named(int codePoint) {
            int low = 0;
            int high = NAMED_STARTS.length - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (codePoint < NAMED_STARTS[middle]) {
                    high = middle - 1;
                } else if (codePoint > NAMED_ENDS[middle]) {
                    low = middle + 1;
                } else {
                    return true;
                }
            }
            return false;
        }

        static String category(int codePoint) {
            return CATEGORY[indexOf(codePoint)];
        }

        static int combining(int codePoint) {
            return COMBINING[indexOf(codePoint)];
        }

        static String bidirectional(int codePoint) {
            return BIDIRECTIONAL[indexOf(codePoint)];
        }

        static int mirrored(int codePoint) {
            return MIRRORED[indexOf(codePoint)] ? 1 : 0;
        }

        static String eastAsianWidth(int codePoint) {
            return EAST_ASIAN_WIDTH[indexOf(codePoint)];
        }

        static double numeric(int codePoint) {
            return NUMERIC[indexOf(codePoint)];
        }

        static int decimal(int codePoint) {
            return DECIMAL[indexOf(codePoint)];
        }

        static int digit(int codePoint) {
            return DIGIT[indexOf(codePoint)];
        }

        static String decomposition(int codePoint) {
            return DECOMPOSITION.getOrDefault(codePoint, "");
        }

        static String name(int codePoint) {
            return named(codePoint) ? UnicodeNames.nameOf(codePoint) : null;
        }

        /** The four normal forms, computed over this version's decomposition and composition. */
        static String normalize(String input, LegacyForm form) {
            boolean compat = form.compat;
            boolean compose = form == LegacyForm.NFC || form == LegacyForm.NFKC;
            CodePoints points = new CodePoints(input.length() + 8);
            for (int i = 0; i < input.length(); ) {
                int codePoint = input.codePointAt(i);
                i += Character.charCount(codePoint);
                decompose(codePoint, compat, points);
            }
            points.order();
            if (compose) {
                points.compose();
            }
            return points.text();
        }

        private static void decompose(int codePoint, boolean compat, CodePoints out) {
            if (codePoint >= 0xAC00 && codePoint <= 0xD7A3) {
                int index = codePoint - 0xAC00;
                out.add(0x1100 + index / 588);
                out.add(0x1161 + (index % 588) / 28);
                if (index % 28 != 0) {
                    out.add(0x11A7 + index % 28);
                }
                return;
            }
            int[] parts = (compat ? COMPAT : CANONICAL).get(codePoint);
            if (parts != null) {
                for (int part : parts) {
                    decompose(part, compat, out);
                }
                return;
            }
            out.add(codePoint);
        }

        private static int pair(int first, int second) {
            if (first >= 0x1100 && first <= 0x1112 && second >= 0x1161 && second <= 0x1175) {
                return 0xAC00 + ((first - 0x1100) * 21 + (second - 0x1161)) * 28;
            }
            if (first >= 0xAC00
                    && first <= 0xD7A3
                    && (first - 0xAC00) % 28 == 0
                    && second >= 0x11A8
                    && second <= 0x11C2) {
                return first + second - 0x11A7;
            }
            Integer composite = COMPOSE.get(((long) first << 32) | (second & 0xFFFFFFFFL));
            return composite == null ? -1 : composite;
        }

        /** A growable code point sequence that can be reordered and composed in place. */
        private static final class CodePoints {
            private int[] values;
            private int size;

            CodePoints(int capacity) {
                values = new int[Math.max(capacity, 4)];
            }

            void add(int codePoint) {
                if (size == values.length) {
                    int[] bigger = new int[values.length * 2];
                    System.arraycopy(values, 0, bigger, 0, size);
                    values = bigger;
                }
                values[size++] = codePoint;
            }

            /** Canonical ordering: each non-starter moves back over the higher classes before it. */
            void order() {
                for (int i = 1; i < size; i++) {
                    int combining = combining(values[i]);
                    if (combining == 0) {
                        continue;
                    }
                    int at = i;
                    while (at > 0) {
                        int before = combining(values[at - 1]);
                        if (before <= combining) {
                            break;
                        }
                        int swap = values[at];
                        values[at] = values[at - 1];
                        values[at - 1] = swap;
                        at--;
                    }
                }
            }

            /** Canonical composition, the algorithm of UAX #15 run over the ordered sequence. */
            void compose() {
                int starterAt = 0;
                int starter = values[0];
                int written = 1;
                int lastClass = combining(starter);
                if (lastClass != 0) {
                    lastClass = 256;
                }
                for (int i = 1; i < size; i++) {
                    int codePoint = values[i];
                    int codeClass = combining(codePoint);
                    int composite = pair(starter, codePoint);
                    if (composite != -1 && (lastClass < codeClass || lastClass == 0)) {
                        values[starterAt] = composite;
                        starter = composite;
                    } else {
                        if (codeClass == 0) {
                            starterAt = written;
                            starter = codePoint;
                        }
                        lastClass = codeClass;
                        values[written++] = codePoint;
                    }
                }
                size = written;
            }

            String text() {
                StringBuilder text = new StringBuilder(size + 8);
                for (int i = 0; i < size; i++) {
                    text.appendCodePoint(values[i]);
                }
                return text.toString();
            }
        }
    }
}
