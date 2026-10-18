package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Python's {@code str}, backed by a Java {@code String}.
 *
 * Two consequences are tracked deliberately. Length and indexing are code-point based, since
 * {@code len('𝕐') == 1} is observable Python behaviour and UTF-16 units would say 2; storage stays
 * UTF-16 because Java cannot avoid it. And a str may hold a lone surrogate, which is representable
 * in both a Java String and a Python str.
 *
 * Every slot and method keeps that view: an index is turned into a UTF-16 offset by
 * {@link String#offsetByCodePoints}, a slice walks code points, and a width counts characters, which
 * is why the helpers at the bottom of this file are about code points rather than about {@code char}s.
 *
 * Method argument counts fall in CPython's two message families and the split shows in the table:
 * {@code split} and {@code replace} are the newer style, naming themselves and counting what arrived
 * ({@code split() takes at most 2 arguments (3 given)}), while {@code find}, {@code strip} and
 * {@code center} are the legacy style, name themselves bare ({@code find expected at most 3
 * arguments, got 4}). The legacy ones are also the ones that take no keywords at all, and that
 * refusal comes from the call site before the body has counted anything, which is why
 * {@code find("a", 0, 3, start=1)} complains about the keyword and not about the four arguments.
 */
public final class PyStr extends PyObject {

    private final String value;
    private final boolean interned;
    private final int codePoints;

    private PyStr(String value, boolean interned) {
        super(PyType.STR);
        this.value = value;
        this.interned = interned;
        this.codePoints = value.codePointCount(0, value.length());
    }

    public static PyStr of(String value) {
        return new PyStr(value, false);
    }

    /**
     * Interning is what lets the interpreter compare method and variable names by pointer. Java's
     * own string table serves that role, so an interned {@code PyStr} always wraps an interned
     * {@code String}.
     */
    public static PyStr interned(String value) {
        return new PyStr(value.intern(), true);
    }

    public String getValue() {
        return value;
    }

    public boolean isInterned() {
        return interned;
    }

    /** {@code len()}, in code points. */
    public int codePointLength() {
        return codePoints;
    }

    /**
     * The bytes a {@code str} turns into when encoded as UTF-8. Java's own encoder substitutes a
     * {@code ?} for a lone surrogate; Python raises there only under {@code 'strict'}, and
     * {@code marshal} never uses that mode.
     */
    public byte[] toUtf8() {
        return PyHash.encodeUtf8(value);
    }

    // --- slots -------------------------------------------------------------------------

    @Override
    public PyObject length() {
        return PyInt.of(codePoints);
    }

    /** {@code s[i]} for a code-point index, and {@code s[a:b:c]} for a slice. */
    @Override
    public PyObject getItem(PyObject key) {
        if (Slices.isSlice(key)) {
            return PyStr.of(select(Slices.of((PySlice) key, codePoints)));
        }
        return characterAt(index(key));
    }

    /** {@code needle in haystack}, which is a substring test and not an element test. */
    @Override
    public PyObject contains(PyObject needle) {
        if (!(needle instanceof PyStr text)) {
            throw Slots.typeError(
                    "'in <string>' requires string as left operand, not %s",
                    needle.getType().name());
        }
        return PyBool.of(value.contains(text.value));
    }

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            // CPython raises here instead of declining, so `str + bytes` names only the right operand.
            if (!(other instanceof PyStr text)) {
                throw Slots.typeError(
                        "can only concatenate str (not \"%s\") to str", other.getType().name());
            }
            return PyStr.of(value + text.value);
        }
        if (op == BinaryOp.MUL) {
            return PyStr.of(repeated(other));
        }
        if (op == BinaryOp.MOD) {
            return Percent.format(this, other);
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        // Only `__rmul__`: a str has no `__radd__`, which is why `1 + "a"` reports the int's failure.
        return op == BinaryOp.MUL ? PyStr.of(repeated(other)) : PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyStr text
                ? ordered(cmp, compareTo(text))
                : super.richCompare(cmp, other);
    }

    @Override
    public PyObject iterator() {
        return PyIterator.of(characters());
    }

    @Override
    public PyObject getAttr(String name) {
        return METHODS.bind(this, name);
    }

    @Override
    public long hash() {
        return PyHash.textHash(value);
    }

    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyStr s) {
            return interned && s.interned ? value == s.value : value.equals(s.value);
        }
        return false;
    }

    @Override
    public String str() {
        return value;
    }

    @Override
    public String repr() {
        return PyRepr.quote(value, "");
    }

    // --- slot helpers --------------------------------------------------------------------

    /**
     * An index with {@code str}'s own wording. {@link Numbers} writes the list and tuple form,
     * {@code tuple indices must be integers or slices}, while a str says
     * {@code string indices must be integers, not 'float'}; the arithmetic is still {@link Numbers}'s.
     */
    private int index(PyObject key) {
        BigInteger position = Numbers.asInteger(key);
        if (position == null) {
            throw Slots.typeError("string indices must be integers, not '%s'", key.getType().name());
        }
        if (!Numbers.fitsInt(position)) {
            throw Slots.indexError("string index out of range");
        }
        int at = position.intValue();
        if (at < 0) {
            at += codePoints;
        }
        if (at < 0 || at >= codePoints) {
            throw Slots.indexError("string index out of range");
        }
        return at;
    }

    /** {@code self * n}: {@link Sequences} counts the repeats and works out what may be refused. */
    private String repeated(PyObject times) {
        PyObject[] pieces = Sequences.multiplied(new PyObject[] {this}, times);
        StringBuilder out = new StringBuilder();
        for (PyObject piece : pieces) {
            out.append(((PyStr) piece).value);
        }
        return out.toString();
    }

    /** Code-point order, which is not {@link String#compareTo}: {@code '\U00010000' > '\uffff'}. */
    private int compareTo(PyStr other) {
        int here = 0;
        int there = 0;
        while (here < value.length() && there < other.value.length()) {
            int left = value.codePointAt(here);
            int right = other.value.codePointAt(there);
            if (left != right) {
                return Integer.compare(left, right);
            }
            here += Character.charCount(left);
            there += Character.charCount(right);
        }
        return Integer.compare(codePoints, other.codePoints);
    }

    /** The one-character strings this value iterates over. */
    private PyObject[] characters() {
        PyObject[] out = new PyObject[codePoints];
        for (int i = 0; i < out.length; i++) {
            out[i] = characterAt(i);
        }
        return out;
    }

    /** The one-character string holding the {@code at}-th code point. */
    private PyObject characterAt(int at) {
        int from = charIndex(at);
        return PyStr.of(value.substring(from, from + Character.charCount(value.codePointAt(from))));
    }

    /** The UTF-16 offset of the {@code at}-th code point. */
    private int charIndex(int at) {
        return value.offsetByCodePoints(0, at);
    }

    /** The code point at Python index {@code at}. */
    int codePointAt(int at) {
        return value.codePointAt(charIndex(at));
    }

    /** The text of the code points {@code [from, to)}, which is empty when the range is not one. */
    private String between(int from, int to) {
        if (from >= to) {
            return "";
        }
        return value.substring(charIndex(from), to >= codePoints ? value.length() : charIndex(to));
    }

    /** The code points a slice selects, in the order the slice walks them. */
    private String select(Slices.Range range) {
        if (range.count() == 0) {
            return "";
        }
        if (range.step() == 1) {
            return between(range.start(), range.start() + range.count());
        }
        StringBuilder out = new StringBuilder(range.count());
        for (int i = 0; i < range.count(); i++) {
            out.appendCodePoint(codePointAt(range.at(i)));
        }
        return out.toString();
    }

    // --- methods -------------------------------------------------------------------------

    private static final String[] SEPARATOR = {"sep", "maxsplit"};

    private static final PyObject[] SEPARATOR_DEFAULTS = {PyNone.INSTANCE, PyInt.of(-1)};

    private static final String[] OLD_NEW_COUNT = {"old", "new", "count"};

    private static final PyObject[] COUNT_DEFAULTS = {PyInt.of(-1)};

    private static final String[] ENCODE_NAMES = {"encoding", "errors"};

    private static final PyObject[] ENCODE_DEFAULTS = {PyStr.of("utf-8"), PyStr.of("strict")};

    private static final String[] TAB_SIZE = {"tabsize"};

    private static final PyObject[] TAB_DEFAULTS = {PyInt.of(8)};

    private static final String[] KEEP_ENDS = {"keepends"};

    private static final PyObject[] KEEP_ENDS_DEFAULTS = {PyBool.FALSE};

    /** Which of the {@code is*} predicates a string is being asked about. */
    private enum Category {
        ALNUM,
        ALPHA,
        ASCII,
        DECIMAL,
        DIGIT,
        NUMERIC,
        SPACE
    }

    /** Which side of the field an alignment pads; {@code center} takes both. */
    private enum Side {
        CENTER,
        LEFT,
        RIGHT
    }

    /**
     * The method table, in the order CPython lists it. Each entry checks its own arguments through
     * {@link Arguments} or one of the helpers below, because the wording of the failure is part of
     * what a program can see.
     *
     * {@code maketrans} is not here at all: it is a static method, which a table of instance methods
     * cannot express. {@code translate} and {@code format_map} are present as refusals, because an
     * unimplemented method reports better than an {@code AttributeError} for a name every Python
     * programmer knows.
     */
    private static final Members METHODS = Members.of("str")
            .addNoArgument("capitalize", self -> PyStr.of(capitalize(text(self), false)))
            // Python's casefold is Unicode's full case folding: lower everything, then expand the
            // characters whose lowercase form is more than one letter. Java stops at the first half.
            .addNoArgument("casefold", self -> PyStr.of(caseFold(text(self))))
            .add("center", (self, args, kwNames) -> align(self, args, kwNames, "center", Side.CENTER))
            .add("count", (self, args, kwNames) -> {
                positional("count", args, kwNames, 1, 3);
                PyStr text = (PyStr) self;
                return PyInt.of(text.occurrences(
                        pattern(args[0], "count"),
                        args.length > 1 ? searchFrom(args[1], text.codePoints) : 0,
                        args.length > 2
                                ? Sequences.searchStop(args[2], text.codePoints)
                                : text.codePoints));
            })
            .add("encode", (self, args, kwNames) -> {
                PyObject[] parameters =
                        parameters("encode", args, kwNames, ENCODE_NAMES, ENCODE_DEFAULTS, 0);
                return PyBytes.encodeText(
                        text(self),
                        named(parameters[0], "encode", "encoding"),
                        named(parameters[1], "encode", "errors"));
            })
            .add("endswith", (self, args, kwNames) -> edges(self, args, kwNames, "endswith", false))
            .add("expandtabs", (self, args, kwNames) -> PyStr.of(expandTabs(
                    text(self),
                    integer(parameters(
                            "expandtabs", args, kwNames, TAB_SIZE, TAB_DEFAULTS, 0)[0]))))
            .add("find", (self, args, kwNames) -> search(self, args, kwNames, "find", false, false))
            .add("format", (self, args, kwNames) ->
                    PyStr.of(new Format(text(self), args, kwNames).render()))
            .add("format_map", (self, args, kwNames) -> {
                Arguments.exactlyOne("str.format_map()", args, kwNames);
                throw Slots.notImplemented("str.format_map");
            })
            .add("index", (self, args, kwNames) -> search(self, args, kwNames, "index", false, true))
            .addNoArgument("isalnum", self -> PyBool.of(test(text(self), Category.ALNUM)))
            .addNoArgument("isalpha", self -> PyBool.of(test(text(self), Category.ALPHA)))
            .addNoArgument("isascii", self -> PyBool.of(test(text(self), Category.ASCII)))
            .addNoArgument("isdecimal", self -> PyBool.of(test(text(self), Category.DECIMAL)))
            .addNoArgument("isdigit", self -> PyBool.of(test(text(self), Category.DIGIT)))
            .addNoArgument("isidentifier", self -> PyBool.of(isIdentifier(text(self))))
            .addNoArgument("islower", self -> PyBool.of(caseTest(text(self), true)))
            .addNoArgument("isnumeric", self -> PyBool.of(test(text(self), Category.NUMERIC)))
            .addNoArgument("isprintable", self -> PyBool.of(printable(text(self))))
            .addNoArgument("isspace", self -> PyBool.of(test(text(self), Category.SPACE)))
            .addNoArgument("istitle", self -> PyBool.of(isTitle(text(self))))
            .addNoArgument("isupper", self -> PyBool.of(caseTest(text(self), false)))
            .addOne("join", (self, iterable) -> PyStr.of(join(text(self), iterable)))
            .add("ljust", (self, args, kwNames) -> align(self, args, kwNames, "ljust", Side.LEFT))
            .addNoArgument("lower", self -> PyStr.of(text(self).toLowerCase(Locale.ROOT)))
            .add("lstrip", (self, args, kwNames) -> trim(self, args, kwNames, "lstrip", true, false))
            .addOne("partition", (self, separator) ->
                    triples(((PyStr) self).divide(pattern(separator, "partition"), true)))
            .addOne("removeprefix", (self, prefix) -> {
                String text = text(self);
                String cut = pattern(prefix, "removeprefix");
                return PyStr.of(text.startsWith(cut) ? text.substring(cut.length()) : text);
            })
            .addOne("removesuffix", (self, suffix) -> {
                String text = text(self);
                String cut = pattern(suffix, "removesuffix");
                return PyStr.of(
                        !cut.isEmpty() && text.endsWith(cut)
                                ? text.substring(0, text.length() - cut.length())
                                : text);
            })
            .add("replace", (self, args, kwNames) -> {
                PyObject[] parameters =
                        parameters("replace", args, kwNames, OLD_NEW_COUNT, COUNT_DEFAULTS, 2);
                return PyStr.of(((PyStr) self).replaced(
                        pattern(parameters[0], "replace"),
                        pattern(parameters[1], "replace", 2),
                        integer(parameters[2])));
            })
            .add("rfind", (self, args, kwNames) -> search(self, args, kwNames, "rfind", true, false))
            .add("rindex", (self, args, kwNames) -> search(self, args, kwNames, "rindex", true, true))
            .add("rjust", (self, args, kwNames) -> align(self, args, kwNames, "rjust", Side.RIGHT))
            .addOne("rpartition", (self, separator) ->
                    triples(((PyStr) self).divide(pattern(separator, "rpartition"), false)))
            .add("rsplit", (self, args, kwNames) -> split(self, args, kwNames, "rsplit", true))
            .add("rstrip", (self, args, kwNames) -> trim(self, args, kwNames, "rstrip", false, true))
            .add("split", (self, args, kwNames) -> split(self, args, kwNames, "split", false))
            .add("splitlines", (self, args, kwNames) -> strings(((PyStr) self).lines(
                    Slots.truth(parameters(
                            "splitlines", args, kwNames, KEEP_ENDS, KEEP_ENDS_DEFAULTS, 0)[0]))))
            .add("startswith", (self, args, kwNames) ->
                    edges(self, args, kwNames, "startswith", true))
            .add("strip", (self, args, kwNames) -> trim(self, args, kwNames, "strip", true, true))
            .addNoArgument("swapcase", self -> PyStr.of(swapCase(text(self))))
            .add("translate", (self, args, kwNames) -> {
                Arguments.exactlyOne("str.translate()", args, kwNames);
                throw Slots.notImplemented("str.translate");
            })
            .addNoArgument("title", self -> PyStr.of(capitalize(text(self), true)))
            .addNoArgument("upper", self -> PyStr.of(text(self).toUpperCase(Locale.ROOT)))
            .addOne("zfill", (self, width) -> PyStr.of(zfill(text(self), integer(width))));

    private static String text(PyObject self) {
        return ((PyStr) self).value;
    }

    // --- argument helpers ------------------------------------------------------------------

    /**
     * Arguments of the legacy methods, which take no keywords at all: CPython refuses a keyword at
     * the call site, before the method body has counted anything, so
     * {@code str.find() takes no keyword arguments} wins over {@code find expected at most 3
     * arguments, got 4}. The bare name then counts arrivals, keywords included.
     */
    private static void positional(
            String name, PyObject[] args, String[] kwNames, int atLeast, int atMost) {
        if (kwNames != null) {
            throw Slots.typeError("str.%s() takes no keyword arguments", name);
        }
        Arguments.between(name, args, kwNames, atLeast, atMost);
    }

    /**
     * The keyword-taking methods laid out in parameter order, with CPython's four refusals in its
     * order: too few positional, too many of anything, a name used twice, and a name it does not
     * have. {@code args} carries the keyword values after the positional ones, which is why the
     * positional count is the total less the number of names.
     *
     * {@link Arguments#fill} cannot be used for this: its keyword values sit at the end of the same
     * array it indexes from the front, and it says {@code got multiple values} where 3.14 says
     * {@code given by name ('sep') and position (1)}.
     */
    private static PyObject[] parameters(
            String name,
            PyObject[] args,
            String[] kwNames,
            String[] names,
            PyObject[] defaults,
            int required) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        if (positional < required) {
            throw Slots.typeError(
                    "%s() takes at least %d positional arguments (%d given)",
                    name, required, positional);
        }
        if (args.length > names.length) {
            throw Slots.typeError(
                    "%s() takes at most %d %sargument%s (%d given)",
                    name,
                    names.length,
                    keywords > 0 && positional == 0 ? "keyword " : "",
                    names.length == 1 ? "" : "s",
                    args.length);
        }
        PyObject[] values = new PyObject[names.length];
        for (int i = 0; i < values.length; i++) {
            int fromEnd = values.length - 1 - i;
            values[i] = fromEnd < defaults.length ? defaults[defaults.length - 1 - fromEnd] : null;
        }
        System.arraycopy(args, 0, values, 0, positional);
        for (int i = 0; i < keywords; i++) {
            int at = position(names, kwNames[i]);
            if (at < 0) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", name, kwNames[i]);
            }
            if (at < positional) {
                // CPython counts the receiver as position zero, so `split(" ", sep=...)` is position 1.
                throw Slots.typeError(
                        "argument for %s() given by name ('%s') and position (%d)",
                        name, kwNames[i], at + 1);
            }
            values[at] = args[positional + i];
        }
        return values;
    }

    private static int position(String[] names, String keyword) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(keyword)) {
                return i;
            }
        }
        return -1;
    }

    /** A string argument, with the message that names the method it was passed to. */
    private static String pattern(PyObject value, String name) {
        return pattern(value, name, 1);
    }

    /** The same test for an argument that is not the first, which CPython numbers. */
    private static String pattern(PyObject value, String name, int position) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError(
                    "%s() argument %d must be str, not %s", name, position, value.getType().name());
        }
        return text;
    }

    /** A named parameter of its own method, which CPython names in the refusal. */
    private static String named(PyObject value, String method, String parameter) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError(
                    "%s() argument '%s' must be str, not %s",
                    method, parameter, value.getType().name());
        }
        return text;
    }

    /** A width or a count: an integer, or the refusal CPython words in its own voice. */
    private static int integer(PyObject value) {
        BigInteger number = Numbers.asInteger(value);
        if (number == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().name());
        }
        if (!Numbers.fitsInt(number)) {
            throw Slots.overflowError("cannot fit 'int' into an index-sized integer");
        }
        return number.intValue();
    }

    /** The fill character of {@code center}, {@code ljust} and {@code rjust}. */
    private static String fill(PyObject value) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError(
                    "The fill character must be a unicode character, not %s", value.getType().name());
        }
        if (text.codePointCount(0, text.length()) > 1) {
            throw Slots.typeError("The fill character must be exactly one character long");
        }
        return text;
    }

    /**
     * The {@code start} of a search, which differs from {@link Sequences#searchStart} in the one case
     * an empty substring can see: a start past the end is left there rather than pulled back, because
     * {@code "abc".find("", 4)} is {@code -1} and not {@code 3}.
     */
    private static int searchFrom(PyObject bound, int length) {
        if (bound == null || bound == PyNone.INSTANCE) {
            return 0;
        }
        BigInteger value = Numbers.sliceIndex(bound);
        if (value.signum() < 0) {
            return value.compareTo(BigInteger.valueOf(-length)) < 0 ? 0 : length + value.intValue();
        }
        return value.intValue();
    }

    // --- method bodies ---------------------------------------------------------------------

    /** {@code a.center(b)} and friends, whose only difference is which side gets the padding. */
    private static PyObject align(
            PyObject self, PyObject[] args, String[] kwNames, String name, Side side) {
        positional(name, args, kwNames, 1, 2);
        PyStr text = (PyStr) self;
        int width = integer(args[0]);
        String fill = args.length > 1 ? fill(args[1]) : " ";
        int missing = width - text.codePoints;
        if (missing <= 0) {
            return self;
        }
        StringBuilder out = new StringBuilder(Math.max(width, text.value.length()));
        switch (side) {
            case CENTER -> {
                int left = missing / 2;
                out.append(fill.repeat(left))
                        .append(text.value)
                        .append(fill.repeat(missing - left));
            }
            case LEFT -> out.append(text.value).append(fill.repeat(missing));
            case RIGHT -> out.append(fill.repeat(missing)).append(text.value);
        }
        return PyStr.of(out.toString());
    }

    private static PyObject search(
            PyObject self, PyObject[] args, String[] kwNames, String name, boolean right, boolean raises) {
        positional(name, args, kwNames, 1, 3);
        PyStr text = (PyStr) self;
        int found = text.first(
                pattern(args[0], name),
                args.length > 1 ? searchFrom(args[1], text.codePoints) : 0,
                args.length > 2 ? Sequences.searchStop(args[2], text.codePoints) : text.codePoints,
                right);
        if (found < 0 && raises) {
            throw Slots.valueError("substring not found");
        }
        return PyInt.of(found);
    }

    private static PyObject edges(
            PyObject self, PyObject[] args, String[] kwNames, String name, boolean prefix) {
        positional(name, args, kwNames, 1, 3);
        PyStr text = (PyStr) self;
        int from = args.length > 1 ? Sequences.searchStart(args[1], text.codePoints) : 0;
        int to = args.length > 2 ? Sequences.searchStop(args[2], text.codePoints) : text.codePoints;
        if (from > to) {
            // Not even the empty prefix matches a range that is not one.
            return PyBool.FALSE;
        }
        String window = text.between(from, to);
        if (args[0] instanceof PyTuple candidates) {
            // Each element is tested in turn and a match ends the test, so an element after the one
            // that matched is never looked at and may be anything at all.
            for (PyObject candidate : candidates.elements()) {
                String edge = Numbers.asText(candidate);
                if (edge == null) {
                    throw Slots.typeError(
                            "tuple for %s must only contain str, not %s",
                            name, candidate.getType().name());
                }
                if (prefix ? window.startsWith(edge) : window.endsWith(edge)) {
                    return PyBool.TRUE;
                }
            }
            return PyBool.FALSE;
        }
        String single = Numbers.asText(args[0]);
        if (single == null) {
            throw Slots.typeError(
                    "%s first arg must be str or a tuple of str, not %s",
                    name, args[0].getType().name());
        }
        return PyBool.of(prefix ? window.startsWith(single) : window.endsWith(single));
    }

    private static PyObject split(
            PyObject self, PyObject[] args, String[] kwNames, String name, boolean right) {
        PyObject[] parameters =
                parameters(name, args, kwNames, SEPARATOR, SEPARATOR_DEFAULTS, 0);
        return strings(((PyStr) self).pieces(
                separator(parameters[0], name), integer(parameters[1]), right));
    }

    /** {@code split}'s separator, which may be {@code None} and says so when it is neither. */
    private static String separator(PyObject value, String name) {
        if (value == PyNone.INSTANCE) {
            return null;
        }
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError("must be str or None, not %s", value.getType().name());
        }
        return text;
    }

    private static PyObject trim(
            PyObject self, PyObject[] args, String[] kwNames, String name, boolean left, boolean right) {
        positional(name, args, kwNames, 0, 1);
        String characters = null;
        if (args.length > 0 && args[0] != PyNone.INSTANCE) {
            characters = Numbers.asText(args[0]);
            if (characters == null) {
                throw Slots.typeError("%s arg must be None or str", name);
            }
        }
        return PyStr.of(((PyStr) self).stripped(characters, left, right));
    }

    /** A list of Java strings, as {@code split} and {@code splitlines} hand them back. */
    private static PyList strings(List<String> pieces) {
        PyObject[] out = new PyObject[pieces.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = PyStr.of(pieces.get(i));
        }
        return PyList.of(out);
    }

    /** The three parts {@code partition} reports: before, separator, after. */
    private static PyTuple triples(List<String> parts) {
        PyObject[] out = new PyObject[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = PyStr.of(parts.get(i));
        }
        return PyTuple.of(out);
    }

    /** {@code separator.join(iterable)}: an iterable of strings, and nothing else. */
    private static String join(String separator, PyObject iterable) {
        if (iterable.iterator() == PyNotImplemented.INSTANCE) {
            throw Slots.typeError("can only join an iterable");
        }
        StringBuilder out = new StringBuilder();
        PyIterator parts = Slots.iterator(iterable);
        for (int i = 0; ; i++) {
            PyObject part = parts.nextOrNull();
            if (part == null) {
                break;
            }
            if (!(part instanceof PyStr text)) {
                throw Slots.typeError(
                        "sequence item %d: expected str instance, %s found",
                        i, part.getType().name());
            }
            if (i > 0) {
                out.append(separator);
            }
            out.append(text.value);
        }
        return out.toString();
    }

    private static String zfill(String text, int width) {
        int missing = width - text.codePointCount(0, text.length());
        if (missing <= 0) {
            return text;
        }
        // The zeros go after a sign, which is the whole point of zfill.
        if (!text.isEmpty() && "+-".indexOf(text.charAt(0)) >= 0) {
            return text.charAt(0) + "0".repeat(missing) + text.substring(1);
        }
        return "0".repeat(missing) + text;
    }

    /**
     * {@code capitalize} and {@code title}, which differ only in how many word starts there are.
     * A start goes through its full upper or title mapping, so {@code 'ß'} becomes {@code Ss}, and
     * the rest of its word is lowered as a substring so that a multi-character lower mapping still
     * applies. A code point whose title form is itself a multi-character sequence — the digraphs —
     * keeps its single-character form, which is the one place this is not CPython.
     */
    private static String capitalize(String text, boolean everyWord) {
        if (text.isEmpty()) {
            return text;
        }
        if (!everyWord) {
            int first = text.codePointAt(0);
            return titleHead(first)
                    + text.substring(Character.charCount(first)).toLowerCase(Locale.ROOT);
        }
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        boolean previousAlphabetic = false;
        while (at < text.length()) {
            int code = text.codePointAt(at);
            int next = at + Character.charCount(code);
            if (!Character.isAlphabetic(code)) {
                out.appendCodePoint(code);
                previousAlphabetic = false;
                at = next;
                continue;
            }
            int end = next;
            while (end < text.length() && Character.isAlphabetic(text.codePointAt(end))) {
                end += Character.charCount(text.codePointAt(end));
            }
            out.append(previousAlphabetic
                    ? text.substring(at, end).toLowerCase(Locale.ROOT)
                    : titleHead(code) + text.substring(next, end).toLowerCase(Locale.ROOT));
            previousAlphabetic = true;
            at = end;
        }
        return out.toString();
    }

    /** The title form of one code point: a full mapping, then its own tail lower-cased. */
    private static String titleHead(int code) {
        int title = Character.toTitleCase(code);
        String one = String.valueOf(Character.toChars(code));
        String mapped = title != code
                ? String.valueOf(Character.toChars(title))
                : one.toUpperCase(Locale.ROOT);
        int head = mapped.codePointAt(0);
        int after = Character.charCount(head);
        return mapped.substring(0, after) + mapped.substring(after).toLowerCase(Locale.ROOT);
    }

    /** {@code swapcase}, which like {@code capitalize} uses the full one-way mappings. */
    private static String swapCase(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            String one = String.valueOf(Character.toChars(code));
            out.append(Character.toLowerCase(code) != code
                    ? one.toLowerCase(Locale.ROOT)
                    : one.toUpperCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String expandTabs(String text, int tabsize) {
        StringBuilder out = new StringBuilder(text.length());
        int column = 0;
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            if (code == '\t') {
                // A tabsize of zero or less deletes the tab instead of expanding it.
                if (tabsize > 0) {
                    int stop = column + tabsize - column % tabsize;
                    out.append(" ".repeat(stop - column));
                    column = stop;
                }
            } else {
                out.appendCodePoint(code);
                column = code == '\n' || code == '\r' ? 0 : column + 1;
            }
        }
        return out.toString();
    }

    /** One of the category predicates, all of which are false for an empty string. */
    private static boolean test(String text, Category kind) {
        if (text.isEmpty()) {
            return false;
        }
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            if (!category(code, kind)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one code point satisfies a category test. {@code isdecimal} is Nd and
     * {@code isnumeric} is Nd, Nl or No, exactly as Python defines them; {@code isdigit} is the one
     * that drifts, because Java has no digit property to consult and so counts every No — the Roman
     * numerals among them — as a digit.
     */
    private static boolean category(int code, Category kind) {
        int type = Character.getType(code);
        return switch (kind) {
            case ALPHA -> Character.isLetter(code);
            case ALNUM -> Character.isLetterOrDigit(code)
                    || type == Character.LETTER_NUMBER
                    || type == Character.OTHER_NUMBER;
            case ASCII -> code < 0x80;
            case DECIMAL -> type == Character.DECIMAL_DIGIT_NUMBER;
            case DIGIT -> Character.isDigit(code) || type == Character.OTHER_NUMBER;
            case NUMERIC -> type == Character.DECIMAL_DIGIT_NUMBER
                    || type == Character.LETTER_NUMBER
                    || type == Character.OTHER_NUMBER;
            case SPACE -> isWhitespace(code);
        };
    }

    /** Python's whitespace: the {@code Zs} category plus the control characters {@code str.split} cuts on. */
    private static boolean isWhitespace(int code) {
        return code >= 0x1c && code <= 0x20
                || code >= 0x09 && code <= 0x0d
                || code == 0x85
                || code == 0xa0
                || code == 0x1680
                || code >= 0x2000 && code <= 0x200a
                || code == 0x2028
                || code == 0x2029
                || code == 0x202f
                || code == 0x205f
                || code == 0x3000;
    }

    /** The line breaks {@code splitlines} knows, including the three information separators. */
    private static boolean isLineBreak(char code) {
        return code == '\n'
                || code == '\r'
                || code == '\u000b'
                || code == '\u000c'
                || code == '\u001c'
                || code == '\u001d'
                || code == '\u001e'
                || code == '\u0085'
                || code == '\u2028'
                || code == '\u2029';
    }

    /**
     * {@code isprintable}: everything but the control, separator, surrogate, private-use and
     * unassigned code points, with the ASCII space the one separator that prints. An empty string is
     * printable, which is not the rule the other predicates follow.
     */
    private static boolean printable(String text) {
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            if (code != ' ' && unprintable(code)) {
                return false;
            }
        }
        return true;
    }

    private static boolean unprintable(int code) {
        return switch (Character.getType(code)) {
            case Character.CONTROL,
                 Character.FORMAT,
                 Character.SURROGATE,
                 Character.PRIVATE_USE,
                 Character.UNASSIGNED,
                 Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> true;
            default -> false;
        };
    }

    /** {@code isupper} and {@code islower}: at least one cased character, and none of the other kind. */
    private static boolean caseTest(String text, boolean lower) {
        boolean seen = false;
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            int up = Character.toUpperCase(code);
            int down = Character.toLowerCase(code);
            if (up == code && down == code) {
                continue;
            }
            seen = true;
            if (lower ? down != code : up != code) {
                return false;
            }
        }
        return seen;
    }

    /**
     * {@code casefold}: the lowercase form plus the expansions Unicode's full case folding gives
     * several letters to.
     *
     * Java's {@code toLowerCase} applies only the one-to-one mapping, so ß, ŉ and the Latin ligatures
     * come back unchanged. The Greek and Coptic entries of the same table are still missing; they are
     * rare enough that the gap belongs with {@code unicodedata}, not with this method.
     */
    private static String caseFold(String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        StringBuilder out = null;
        for (int at = 0; at < lowered.length(); at++) {
            char code = lowered.charAt(at);
            String folded =
                    switch (code) {
                        case '\u00DF' -> "ss";
                        case '\u0149' -> "\u02BCn";
                        case '\uFB00' -> "ff";
                        case '\uFB01' -> "fi";
                        case '\uFB02' -> "fl";
                        case '\uFB03' -> "ffi";
                        case '\uFB04' -> "ffl";
                        case '\uFB05' -> "st";
                        case '\uFB06' -> "ft";
                        default -> null;
                    };
            if (folded == null) {
                if (out != null) {
                    out.append(code);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(lowered.length() + 2).append(lowered, 0, at);
            }
            out.append(folded);
        }
        return out == null ? lowered : out.toString();
    }

    private static boolean isTitle(String text) {
        boolean seen = false;
        boolean previousAlphabetic = false;
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            boolean cased = Character.toUpperCase(code) != code || Character.toLowerCase(code) != code;
            if (cased) {
                seen = true;
                boolean expected = previousAlphabetic
                        ? Character.toLowerCase(code) == code && Character.toUpperCase(code) != code
                        : Character.toTitleCase(code) == code && Character.toLowerCase(code) != code;
                if (!expected) {
                    return false;
                }
            }
            previousAlphabetic = Character.isAlphabetic(code);
        }
        return seen;
    }

    /**
     * {@code isidentifier}: the Unicode category rules Java keeps, which agree with Python's
     * {@code XID_Start} and {@code XID_Continue} for the scripts that matter here and drift for a
     * handful of code points that are letters in one table and numbers in the other.
     */
    private static boolean isIdentifier(String text) {
        if (text.isEmpty()) {
            return false;
        }
        int first = text.codePointAt(0);
        if (first != '_' && !Character.isUnicodeIdentifierStart(first)) {
            return false;
        }
        for (int at = Character.charCount(first); at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            if (code != '_' && !Character.isUnicodeIdentifierPart(code)) {
                return false;
            }
        }
        return true;
    }

    // --- instance bodies -------------------------------------------------------------------

    /** How many non-overlapping copies of {@code sub} the code points {@code [from, to)} contain. */
    private int occurrences(String sub, int from, int to) {
        if (sub.isEmpty()) {
            // Every boundary is a match, and a start past the end is not a range at all.
            return to < from || from > codePoints ? 0 : to - from + 1;
        }
        String window = between(from, to);
        int found = 0;
        for (int at = window.indexOf(sub); at >= 0; at = window.indexOf(sub, at + sub.length())) {
            found++;
        }
        return found;
    }

    /**
     * Where {@code sub} is, or -1, counted in code points. The search runs on UTF-16 offsets and the
     * answer is converted back, because an index a caller can use is a Python index. An empty
     * substring matches every boundary, and the answer CPython gives for it is an edge of the range
     * the search was pointed at rather than a position found inside it.
     */
    private int first(String sub, int from, int to, boolean right) {
        if (sub.isEmpty()) {
            return to < from || from > codePoints ? -1 : right ? to : from;
        }
        String window = between(from, to);
        int at = right ? window.lastIndexOf(sub) : window.indexOf(sub);
        return at < 0 ? -1 : from + window.codePointCount(0, at);
    }

    /** {@code partition} and {@code rpartition}: before, separator, after. */
    private List<String> divide(String separator, boolean left) {
        if (separator.isEmpty()) {
            throw Slots.valueError("empty separator");
        }
        int at = left ? value.indexOf(separator) : value.lastIndexOf(separator);
        if (at < 0) {
            return List.of(value, "", "");
        }
        int here = value.codePointCount(0, at);
        int there = here + separator.codePointCount(0, separator.length());
        return List.of(between(0, here), between(here, there), between(there, codePoints));
    }

    private String stripped(String characters, boolean left, boolean right) {
        int from = 0;
        int to = value.length();
        if (characters == null) {
            // Python's whitespace is all BMP, so a UTF-16 unit is enough to test one.
            while (left && from < to && isWhitespace(value.charAt(from))) {
                from++;
            }
            while (right && to > from && isWhitespace(value.charAt(to - 1))) {
                to--;
            }
            return value.substring(from, to);
        }
        if (left) {
            while (from < to) {
                int code = value.codePointAt(from);
                if (characters.indexOf(code) < 0) {
                    break;
                }
                from += Character.charCount(code);
            }
        }
        if (right) {
            while (to > from) {
                int previous = previousCodePoint(to);
                if (characters.indexOf(value.codePointAt(previous)) < 0) {
                    break;
                }
                to = previous;
            }
        }
        return value.substring(from, to);
    }

    /** The UTF-16 offset of the code point ending at {@code to}. */
    private int previousCodePoint(int to) {
        char unit = value.charAt(to - 1);
        return Character.isLowSurrogate(unit) && to - 2 >= 0 && Character.isHighSurrogate(value.charAt(to - 2))
                ? to - 2
                : to - 1;
    }

    private String replaced(String old, String replacement, int count) {
        if (count == 0) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        if (old.isEmpty()) {
            // The empty pattern matches at every boundary, the last one included, and the first
            // replacement is not one the loop could have made.
            int remaining = count < 0 ? Integer.MAX_VALUE : count;
            out.append(replacement);
            int at = 0;
            while (at < value.length() && --remaining > 0) {
                int code = value.codePointAt(at);
                int next = at + Character.charCount(code);
                out.append(value, at, next).append(replacement);
                at = next;
            }
            return out.append(value.substring(at)).toString();
        }
        int remaining = count < 0 ? Integer.MAX_VALUE : count;
        int at = 0;
        while (remaining > 0) {
            int found = value.indexOf(old, at);
            if (found < 0) {
                break;
            }
            out.append(value, at, found).append(replacement);
            at = found + old.length();
            remaining--;
        }
        return out.append(value.substring(at)).toString();
    }

    private List<String> lines(boolean keepends) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int at = 0;
        while (at < value.length()) {
            char code = value.charAt(at);
            if (isLineBreak(code)) {
                int next = at + 1;
                if (code == '\r' && next < value.length() && value.charAt(next) == '\n') {
                    next++;
                }
                out.add(keepends ? value.substring(start, next) : value.substring(start, at));
                start = at = next;
            } else {
                at++;
            }
        }
        // A trailing break closes the last line rather than opening an empty one.
        if (start < value.length()) {
            out.add(value.substring(start));
        }
        return out;
    }

    private List<String> pieces(String separator, int maxsplit, boolean right) {
        if (separator != null && separator.isEmpty()) {
            throw Slots.valueError("empty separator");
        }
        if (separator == null) {
            return whitespacePieces(maxsplit, right);
        }
        int cuts = maxsplit < 0 ? Integer.MAX_VALUE : maxsplit;
        List<String> out = new ArrayList<>();
        if (right) {
            int end = value.length();
            while (cuts > 0) {
                int at = value.lastIndexOf(separator, end - separator.length());
                if (at < 0) {
                    break;
                }
                out.add(0, value.substring(at + separator.length(), end));
                end = at;
                cuts--;
            }
            out.add(0, value.substring(0, end));
            return out;
        }
        int at = 0;
        while (cuts > 0) {
            int found = value.indexOf(separator, at);
            if (found < 0) {
                break;
            }
            out.add(value.substring(at, found));
            at = found + separator.length();
            cuts--;
        }
        out.add(value.substring(at));
        return out;
    }

    /**
     * {@code split()} with no separator: runs of whitespace divide, and no piece is empty. The last
     * piece of a truncated split keeps the whitespace it was reached through, which is the one thing
     * that makes {@code "  1  2  ".split(None, 1)} a two-element list.
     */
    private List<String> whitespacePieces(int maxsplit, boolean right) {
        int cuts = maxsplit < 0 ? Integer.MAX_VALUE : maxsplit;
        List<String> out = new ArrayList<>();
        if (right) {
            int end = previousNonWhitespace(value.length());
            while (end > 0 && cuts > 0) {
                int start = end;
                while (start > 0 && !isWhitespace(value.charAt(start - 1))) {
                    start--;
                }
                out.add(value.substring(start, end));
                end = previousNonWhitespace(start);
                cuts--;
            }
            if (end > 0) {
                out.add(value.substring(0, end));
            }
            Collections.reverse(out);
            return out;
        }
        int at = nextNonWhitespace(0);
        while (at < value.length()) {
            if (cuts == 0) {
                out.add(value.substring(at));
                break;
            }
            int end = at;
            while (end < value.length() && !isWhitespace(value.charAt(end))) {
                end++;
            }
            out.add(value.substring(at, end));
            at = nextNonWhitespace(end);
            cuts--;
        }
        return out;
    }

    private int nextNonWhitespace(int at) {
        while (at < value.length() && isWhitespace(value.charAt(at))) {
            at++;
        }
        return at;
    }

    /** The offset just before the run of whitespace ending at {@code to}. */
    private int previousNonWhitespace(int to) {
        while (to > 0 && isWhitespace(value.charAt(to - 1))) {
            to--;
        }
        return to;
    }

    // --- str.format ------------------------------------------------------------------------

    /**
     * The {@code str.format} replacement-field grammar, on top of {@link Formats}.
     *
     * A field is {@code {name[:spec][!conversion]}}, where the name is a number or a keyword followed
     * by any number of {@code .attr} and {@code [index]} steps. CPython reads the whole name and only
     * then decides what it is, so a name that starts with a digit and does not end with one —
     * {@code "0 "} — is a keyword and produces a {@code KeyError} about its text rather than an index
     * error. {@code {{}} and {@code {}}} are escapes and never reach here.
     */
    private static final class Format {

        /** The characters that end an attribute name, which is every piece of punctuation the grammar has. */
        private static final String DELIMITERS = ".[]:!}";

        private final String text;
        private final PyObject[] positional;
        private final String[] names;
        private final PyObject[] values;
        private int cursor;
        private int automatic;
        private boolean manual;
        private boolean auto;

        Format(String text, PyObject[] args, String[] kwNames) {
            this.text = text;
            int keywords = kwNames == null ? 0 : kwNames.length;
            this.positional = new PyObject[args.length - keywords];
            System.arraycopy(args, 0, positional, 0, positional.length);
            this.names = kwNames == null ? new String[0] : kwNames;
            this.values = new PyObject[keywords];
            System.arraycopy(args, args.length - keywords, values, 0, keywords);
        }

        String render() {
            StringBuilder out = new StringBuilder();
            int literal = 0;
            while (cursor < text.length()) {
                char code = text.charAt(cursor);
                if (code != '{' && code != '}') {
                    cursor++;
                    continue;
                }
                if (cursor + 1 < text.length() && text.charAt(cursor + 1) == code) {
                    out.append(text, literal, cursor).append(code);
                    cursor += 2;
                    literal = cursor;
                    continue;
                }
                if (code == '}') {
                    throw Slots.valueError("Single '}' encountered in format string");
                }
                out.append(text, literal, cursor);
                cursor++;
                field(out);
                literal = cursor;
            }
            return out.append(text, literal, text.length()).toString();
        }

        /** One field, with the cursor left past its closing brace. */
        private void field(StringBuilder out) {
            int start = cursor;
            PyObject value = argument();
            char conversion = 0;
            String spec = null;
            while (true) {
                if (cursor >= text.length()) {
                    throw Slots.valueError("expected '}' before end of string");
                }
                char code = text.charAt(cursor);
                if (code == '!') {
                    if (conversion != 0) {
                        throw Slots.valueError("Duplicate conversion specifier");
                    }
                    conversion = conversionAt();
                    continue;
                }
                if (code == ':') {
                    spec = specification();
                    continue;
                }
                if (code == '}') {
                    cursor++;
                    break;
                }
                if (code == '{') {
                    throw Slots.valueError("unexpected '{' in field name");
                }
                throw Slots.keyError(PyStr.of(text.substring(start, cursor)));
            }
            // The conversion happens first, so a specification is applied to the text it produced.
            PyObject shown = conversion == 0
                    ? value
                    : PyStr.of(present(value, conversion));
            out.append(Formats.apply(shown, spec == null ? "" : spec));
        }

        /**
         * The field's value: its name resolved to an argument, then every {@code .attr} and
         * {@code [key]} step applied in order.
         */
        private PyObject argument() {
            PyObject value = named();
            while (cursor < text.length() && (text.charAt(cursor) == '.' || text.charAt(cursor) == '[')) {
                char marker = text.charAt(cursor++);
                int start = cursor;
                if (marker == '.') {
                    while (cursor < text.length() && DELIMITERS.indexOf(text.charAt(cursor)) < 0) {
                        cursor++;
                    }
                } else {
                    while (cursor < text.length() && text.charAt(cursor) != ']') {
                        cursor++;
                    }
                }
                String step = text.substring(start, cursor);
                if (step.isEmpty()) {
                    throw Slots.valueError("Empty attribute in format string");
                }
                if (marker == '.') {
                    value = Slots.getAttr(value, step);
                    continue;
                }
                if (cursor >= text.length()) {
                    throw Slots.valueError("expected '}' before end of string");
                }
                cursor++;
                value = Slots.getItem(value, indexKey(step));
                if (cursor < text.length()
                        && text.charAt(cursor) != '.' && text.charAt(cursor) != '['
                        && text.charAt(cursor) != '!' && text.charAt(cursor) != ':'
                        && text.charAt(cursor) != '}') {
                    throw Slots.valueError(
                            "Only '.' or '[' may follow ']' in format field specifier");
                }
            }
            return value;
        }

        /** The name before any step: a positional index, a keyword, or nothing, which means the next one. */
        private PyObject named() {
            int start = cursor;
            while (cursor < text.length() && DELIMITERS.indexOf(text.charAt(cursor)) < 0) {
                cursor++;
            }
            String name = text.substring(start, cursor);
            if (name.isEmpty()) {
                if (manual) {
                    throw Slots.valueError(
                            "cannot switch from manual field specification to automatic field numbering");
                }
                auto = true;
                return position(automatic++);
            }
            BigInteger index = digits(name);
            if (index == null) {
                return keyword(name);
            }
            if (auto) {
                throw Slots.valueError(
                        "cannot switch from automatic field numbering to manual field specification");
            }
            manual = true;
            if (index.compareTo(SSIZE) > 0) {
                throw Slots.valueError("Too many decimal digits in format string");
            }
            return position(index.intValue());
        }

        private PyObject position(int index) {
            if (index >= positional.length) {
                throw Slots.indexError(
                        "Replacement index %d out of range for positional args tuple", index);
            }
            return positional[index];
        }

        private PyObject keyword(String name) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    return values[i];
                }
            }
            throw Slots.keyError(PyStr.of(name));
        }

        /** A step's key is an index when it is all digits and a string otherwise. */
        private static PyObject indexKey(String key) {
            BigInteger index = digits(key);
            if (index == null) {
                return PyStr.of(key);
            }
            if (!Numbers.fitsInt(index)) {
                throw Slots.overflowError("cannot fit 'int' into an index-sized integer");
            }
            return PyInt.of(index);
        }

        /** The whole text as a non-negative integer, or null when it is not one. */
        private static BigInteger digits(String name) {
            if (name.isEmpty()) {
                return null;
            }
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < name.length(); i++) {
                char code = name.charAt(i);
                if (code < '0' || code > '9') {
                    return null;
                }
                value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(code - '0'));
            }
            return value;
        }

        private static final BigInteger SSIZE = BigInteger.valueOf(Long.MAX_VALUE);

        /** The conversion character after a {@code !}, with CPython's three complaints about it. */
        private char conversionAt() {
            cursor++;
            if (cursor >= text.length()) {
                throw Slots.valueError("unmatched '{' in format spec");
            }
            char code = text.charAt(cursor);
            if (code == '}' && cursor + 1 == text.length()) {
                throw Slots.valueError("unmatched '{' in format spec");
            }
            if (code == ':' || code == '}') {
                throw Slots.valueError("expected ':' after conversion specifier");
            }
            if (code != 's' && code != 'r' && code != 'a') {
                throw Slots.valueError("Unknown conversion specifier %s", code);
            }
            cursor++;
            if (cursor < text.length() && text.charAt(cursor) != ':' && text.charAt(cursor) != '}') {
                throw Slots.valueError("expected ':' after conversion specifier");
            }
            return code;
        }

        /**
         * The specification after {@code :}, with any field inside it filled first — CPython turns
         * {@code "{0:{1}}"} into a plain spec before it reads it.
         */
        private String specification() {
            cursor++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (cursor >= text.length()) {
                    throw Slots.valueError("expected '}' before end of string");
                }
                char code = text.charAt(cursor);
                if (code == '}') {
                    // Left in place: the caller's own loop consumes it, which is what makes the
                    // second '}' of "{a:{b}}" belong to the outer field rather than the inner one.
                    break;
                }
                if (code == '{') {
                    cursor++;
                    field(out);
                    continue;
                }
                out.append(code);
                cursor++;
            }
            return out.toString();
        }

        private static String present(PyObject value, char conversion) {
            return switch (conversion) {
                case 's' -> Slots.str(value);
                case 'r' -> Slots.repr(value);
                default -> Percent.ascii(Slots.repr(value));
            };
        }
    }
}
