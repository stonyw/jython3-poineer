package org.python.core;

import java.text.Normalizer;

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
 */
final class UnicodeDataModule {

    /** The version CPython 3.14.10 ships, which is what {@code unicodedata.unidata_version} reads. */
    private static final String UNIDATA_VERSION = "16.0.0";

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

    private UnicodeDataModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("unicodedata");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "unicodedata");
        module.set("unidata_version", PyStr.of(UNIDATA_VERSION));
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
}
