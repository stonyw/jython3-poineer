package org.python.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code _sre} module: the part of the engine {@code re} talks to.
 *
 * <p>CPython's {@code _sre} is a C module; here the engine is {@link java.util.regex} and the work
 * that is left is the boundary — turning the six arguments of {@code compile} into a
 * {@link PySrePattern}, and answering the four case helpers the parser's character-set optimiser
 * asks for. Everything a user sees as a compile error is raised by the pure-Python parser before it
 * gets here, so this file's complaint is only for syntax Python accepts and the translation does not.
 */
final class SreModule {

    /** The engine's serialisation code size, which the parser multiplies by eight. */
    static final int CODESIZE = 4;

    /** The largest repetition the parser accepts. */
    static final long MAXREPEAT = 4294967295L;

    /** The largest group number the parser accepts. */
    static final long MAXGROUPS = 1073741823L;

    /** The value {@code re._constants.MAGIC} asserts against. */
    static final int MAGIC = 20230612;

    private static final String COPYRIGHT =
            "Copyright (c) 1997-2001 by Secret Labs AB.";

    private SreModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_sre");
        module.set("__doc__", PyStr.of("Internal support module for sre"));
        module.set("MAGIC", PyInt.of(MAGIC));
        module.set("CODESIZE", PyInt.of(CODESIZE));
        module.set("MAXREPEAT", PyInt.of(MAXREPEAT));
        module.set("MAXGROUPS", PyInt.of(MAXGROUPS));
        module.set("copyright", PyStr.of(COPYRIGHT));
        module.set("getcodesize", new PyNativeFunction("getcodesize", (args, kwNames) -> {
            Arguments.none("getcodesize", args, kwNames);
            return PyInt.of(CODESIZE);
        }));
        module.set("compile", new PyNativeFunction("compile", SreModule::compile));
        module.set("template", new PyNativeFunction("template", (args, kwNames) -> {
            Arguments.exactly("template", args, kwNames, 2);
            return args[1];
        }));
        module.set("unicode_iscased", new PyNativeFunction("unicode_iscased",
                (args, kwNames) -> PyBool.of(iscased(codePoint("unicode_iscased", args, kwNames)))));
        module.set("unicode_tolower", new PyNativeFunction("unicode_tolower",
                (args, kwNames) -> PyInt.of(
                        UnicodeCase.simpleLower(codePoint("unicode_tolower", args, kwNames)))));
        module.set("ascii_iscased", new PyNativeFunction("ascii_iscased",
                (args, kwNames) -> PyBool.of(asciiCased(codePoint("ascii_iscased", args, kwNames)))));
        module.set("ascii_tolower", new PyNativeFunction("ascii_tolower",
                (args, kwNames) -> PyInt.of(
                        asciiLower(codePoint("ascii_tolower", args, kwNames)))));
        return module;
    }

    private static int codePoint(String plain, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne(plain, args, kwNames);
        return Numbers.asIndex(args[0]).intValue();
    }

    /**
     * Whether a code point is cased, in the sense {@code sre.c} uses.
     *
     * <p>This is not Unicode's {@code Cased} property and not Java's
     * {@code isLowerCase}/{@code isUpperCase} pair: the reference asks whether the simple lowercase
     * or the simple uppercase mapping differs from the character itself, so {@code 'ª'} (whose only
     * case relation is Other_Lowercase) is not cased while {@code 'ß'} is.
     */
    private static boolean iscased(int codePoint) {
        return UnicodeCase.sreCased(codePoint);
    }

    private static boolean asciiCased(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z');
    }

    private static int asciiLower(int codePoint) {
        return codePoint >= 'A' && codePoint <= 'Z' ? codePoint + ('a' - 'A') : codePoint;
    }

    /**
     * {@code _sre.compile(pattern, flags, code, groups, groupindex, indexgroup)}.
     *
     * <p>Only the first, second, fourth and fifth arguments are read: the code list is the reference
     * engine's own program and the index-group tuple repeats the numbering the parser already put in
     * {@code groupindex}.
     */
    private static PyObject compile(PyObject[] args, String[] kwNames) {
        Arguments.between("compile", args, kwNames, 6, 6);
        String source;
        PyObject sourceObject = args[0];
        boolean bytes;
        if (sourceObject instanceof PyStr text) {
            source = text.getValue();
            bytes = false;
        } else if (sourceObject instanceof PyBytes raw) {
            // One character per octet, which is what makes a Java `Pattern` agree with a bytes pattern:
            // every escape the parser left behind stands for a byte, and `.` spans one octet.
            source = new String(raw.getValue(), StandardCharsets.ISO_8859_1);
            bytes = true;
        } else {
            throw Slots.typeError("first argument must be string or compiled pattern");
        }
        int flags = Numbers.asIndex(args[1]).intValue();
        int groups = Numbers.asIndex(args[3]).intValue();
        Map<String, Integer> names = new LinkedHashMap<>();
        if (args[4] instanceof PyDict groupindex) {
            for (PyObject key : groupindex.keys()) {
                names.put(Numbers.asText(key), Numbers.asIndex(groupindex.get(key)).intValue());
            }
        }
        try {
            return new PySrePattern(sourceObject, source, bytes, flags, groups, names);
        } catch (PatternSyntaxException e) {
            throw PyRaise.of(error(e.getDescription()));
        } catch (IllegalArgumentException e) {
            throw PyRaise.of(error(e.getMessage()));
        }
    }

    /** The complaint {@code re} raises for a bad pattern, which is {@code re.error}'s class. */
    private static PyBaseException error(String message) {
        PyObject constants = PyRuntime.importModule("re._constants");
        PyObject type = constants instanceof PyModule module ? module.get("PatternError") : null;
        if (type instanceof PyType kind) {
            PyObject built = Slots.call(kind, new PyObject[] {PyStr.of(message)}, null);
            if (built instanceof PyBaseException exception) {
                return exception;
            }
        }
        return new PyBaseException(ExceptionTypes.VALUE_ERROR, new PyObject[] {PyStr.of(message)});
    }
}
