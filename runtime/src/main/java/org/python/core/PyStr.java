package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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
public class PyStr extends PyObject {

    private final String value;
    private final boolean interned;
    private final int codePoints;

    private PyStr(String value, boolean interned) {
        super(PyType.STR);
        this.value = value;
        this.interned = interned;
        this.codePoints = value.codePointCount(0, value.length());
    }

    /**
     * The constructor a class Python wrote under {@code str} uses: the value is already known, and the
     * type is the class whose name the value answers with. {@link PyStrSubclass} adds the storage.
     */
    PyStr(String value, PyType type) {
        super(type);
        this.value = value;
        this.interned = false;
        this.codePoints = value.codePointCount(0, value.length());
    }

    public static PyStr of(String value) {
        return value.isEmpty() ? EMPTY : new PyStr(value, false);
    }

    /**
     * The one empty string this interpreter has, the way CPython has one empty {@code str} and one
     * empty {@code bytes}.
     *
     * <p>It is observable: {@code ''.lower()} and {@code ''.join(())} both answer with nothing to copy,
     * so they answer with this object, and {@code '' is ''.lower()} is true. A str of one character has
     * no such shared instance, so the same question about {@code 'a'} is false.
     */
    private static final PyStr EMPTY = new PyStr("", false);

    /**
     * Interning is what lets the interpreter compare method and variable names by pointer. Java's
     * own string table serves that role, so an interned {@code PyStr} always wraps an interned
     * {@code String}.
     */
    public static PyStr interned(String value) {
        if (value.isEmpty()) {
            return EMPTY;
        }
        String key = value.intern();
        PyStr known = INTERNED.get(key);
        if (known != null) {
            return known;
        }
        PyStr fresh = new PyStr(key, true);
        PyStr raced = INTERNED.putIfAbsent(key, fresh);
        return raced == null ? fresh : raced;
    }

    /**
     * The one {@code PyStr} each interned {@code String} answers with.
     *
     * <p>{@code String.intern()} alone only makes the storage shared, not the wrapper, and
     * {@code 'a' is 'a'} is answered by this runtime with object identity. Caching the wrapper is
     * what makes an interned name compare identically across events: {@code pyexpat} hands the same
     * element name out once per event, and the reference interpreter's own interned cache is why
     * those names satisfy {@code is}.
     */
    private static final ConcurrentHashMap<String, PyStr> INTERNED = new ConcurrentHashMap<>();

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
        return textItem(this, key);
    }

    /**
     * The {@code str} slot itself.
     *
     * <p>{@code str.__getitem__} is a wrapper descriptor whose {@code d_wrapped} is the C function
     * {@code str} installed, so calling it on a subclass runs the string layout rather than asking the
     * subclass's own {@code __getitem__} again — which is what keeps {@code str.__getitem__(self, key)}
     * inside an override from recursing. {@link #getItem} is the virtual half and reaches this one
     * through the subtype's decline.
     */
    static PyObject textItem(PyStr text, PyObject key) {
        if (Slices.isSlice(key)) {
            return PyStr.of(text.select(Slices.of((PySlice) key, text.codePoints)));
        }
        return text.characterAt(text.index(key));
    }

    /** {@code needle in haystack}, which is a substring test and not an element test. */
    @Override
    public PyObject contains(PyObject needle) {
        if (!(needle instanceof PyStr text)) {
            throw Slots.typeError(
                    "'in <string>' requires string as left operand, not %s",
                    needle.getType().name());
        }
        return PyBool.of(text.value.isEmpty()
                || FastSearch.find(value, 0, value.length(), text.value, false) >= 0);
    }

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            // CPython raises here instead of declining, so `str + bytes` names only the right operand.
            if (!(other instanceof PyStr text)) {
                throw Slots.typeError(
                        "can only concatenate str (not \"%s\") to str", other.getType().filedName());
            }
            return PyStr.of(value + text.value);
        }
        if (op == BinaryOp.MUL) {
            return repeated(other);
        }
        if (op == BinaryOp.MOD) {
            return Percent.format(this, other);
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        // Only `__rmul__`: a str has no `__radd__`, which is why `1 + "a"` reports the int's failure.
        return op == BinaryOp.MUL ? repeated(other) : PyNotImplemented.INSTANCE;
    }

    /**
     * Code-point order against another string, and the decline of anything else.
     *
     * <p>{@code unicode_richcompare} compares only what it can read as text, so a program that asks for
     * the slot instead of running the operator — {@code 'a'.__eq__(3)} — is handed {@code NotImplemented}
     * rather than a {@code False} it did not compute.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyStr text ? ordered(cmp, compareTo(text)) : PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject iterator() {
        return SequenceIterators.of(this);
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
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
        if (!Numbers.isIndex(key)) {
            throw Slots.typeError("string indices must be integers, not '%s'", key.getType().filedName());
        }
        return Numbers.wrapIndex(key, Numbers.asIndex(key), codePoints, "string index out of range");
    }

    /**
     * {@code self * n}: {@link Sequences} reads the multiplier, and the width of the result is
     * {@code str}'s own to refuse — {@code unicode_repeat} is the one sequence repeat that answers an
     * {@code OverflowError} with a sentence about the string rather than a {@code MemoryError}.
     */
    private PyObject repeated(PyObject times) {
        long count = Sequences.multiplier(times);
        // `unicode_repeat` returns the same string for a count of one, for an exact string only; a
        // subclass is repeated into a plain string and never returns itself.
        if (count == 1 && getClass() == PyStr.class) {
            return this;
        }
        int total =
                Sequences.scaled(codePoints, count, "repeated string is too long");
        if (total == 0) {
            return PyStr.of("");
        }
        StringBuilder out = new StringBuilder(total);
        for (long at = 0; at < count; at++) {
            out.append(value);
        }
        return PyStr.of(out.toString());
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

    /**
     * {@code PyUnicode_IS_COMPACT_ASCII}: whether every code point is under 128, which is what {@code
     * unicode_iter} asks before it chooses between the two string cursors.
     *
     * <p>The test is on the UTF-16 units, which is the same question: a code point at or above 128 is
     * written either as one unit of its own or as a surrogate pair, and both are units over 127. The empty
     * string passes, and so answers with the ASCII cursor.
     */
    boolean isAscii() {
        return value.chars().allMatch(unit -> unit < 128);
    }

    /** The one-code-point string at a code-point index, which is what {@code s[i]} and a walk hand out. */
    PyObject characterAt(int at) {
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

    /**
     * {@code startswith}/{@code endswith} against one candidate, without copying the window the way
     * {@link #between} would: the comparison is the candidate's length, not the range's.
     */
    private boolean edgeMatches(String edge, int fromUnit, int width, boolean prefix) {
        if (edge.length() > width) {
            return false;
        }
        return value.startsWith(edge, prefix ? fromUnit : fromUnit + width - edge.length());
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
     * {@code maketrans} is the one entry that takes no receiver, and so is declared with
     * {@link Members#addStatic}: the same object answers for the type and for every value of it, which is
     * what makes {@code str.maketrans is ''.maketrans} true. {@code translate} is present as a
     * refusal, because an unimplemented method reports better than an {@code AttributeError} for a
     * name every Python programmer knows.
     */
    private static final Members METHODS = Members.of(PyType.STR)
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
                        args.length > 1 ? Sequences.searchFrom(args[1], text.codePoints) : 0,
                        args.length > 2
                                ? Sequences.searchStop(args[2], text.codePoints)
                                : text.codePoints));
            })
            .add("encode", (self, args, kwNames) -> {
                PyObject[] parameters =
                        Arguments.parameters(
                                "encode", args, kwNames, ENCODE_NAMES, ENCODE_DEFAULTS, 0);
                return PyBytes.encodeText(
                        text(self),
                        named(parameters[0], "encode", "encoding"),
                        named(parameters[1], "encode", "errors"));
            })
            .add("endswith", (self, args, kwNames) -> edges(self, args, kwNames, "endswith", false))
            .add("expandtabs", (self, args, kwNames) -> {
                String text = text(self);
                int tabsize = Numbers.cInteger(Arguments.parameters(
                        "expandtabs", args, kwNames, TAB_SIZE, TAB_DEFAULTS, 0)[0]);
                // CPython's first pass notes whether there is a tab at all and hands the same string
                // back when there is none — a copy of it, for a subclass, so `S(a).expandtabs()` is
                // a plain str.
                return text.indexOf('\t') < 0
                        ? unchanged((PyStr) self)
                        : PyStr.of(expandTabs(text, tabsize));
            })
            .add("find", (self, args, kwNames) -> search(self, args, kwNames, "find", false, false))
            .add("format", (self, args, kwNames) ->
                    PyStr.of(new Format(text(self), args, kwNames).render()))
            .add("format_map", (self, args, kwNames) -> {
                Arguments.exactlyOne("str.format_map()", args, kwNames);
                return PyStr.of(new Format(text(self), args[0]).render());
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
            .addOne("join", (self, iterable) -> join(text(self), iterable))
            .add("ljust", (self, args, kwNames) -> align(self, args, kwNames, "ljust", Side.LEFT))
            .addNoArgument("lower", self -> PyStr.of(lowerText(text(self))))
            .add("lstrip", (self, args, kwNames) -> trim(self, args, kwNames, "lstrip", true, false))
            .addStatic("maketrans", PyStr::makeTrans)
            .addOne("partition", (self, separator) ->
                    triples(((PyStr) self).divide(divided(separator), true)))
            .addOne("removeprefix", (self, prefix) -> {
                String text = text(self);
                String cut = edge(prefix, "removeprefix");
                return PyStr.of(text.startsWith(cut) ? text.substring(cut.length()) : text);
            })
            .addOne("removesuffix", (self, suffix) -> {
                String text = text(self);
                String cut = edge(suffix, "removesuffix");
                return PyStr.of(
                        !cut.isEmpty() && text.endsWith(cut)
                                ? text.substring(0, text.length() - cut.length())
                                : text);
            })
            .add("replace", (self, args, kwNames) -> {
                PyObject[] parameters =
                        Arguments.parameters(
                                "replace", args, kwNames, OLD_NEW_COUNT, COUNT_DEFAULTS, 2);
                PyStr text = (PyStr) self;
                String result = text.replaced(
                        pattern(parameters[0], "replace"),
                        pattern(parameters[1], "replace", 2),
                        Numbers.size(parameters[2]));
                // A result that spells the receiver again is the receiver: CPython's `count == 0`,
                // `old == new` and "not found" paths all end in `unicode_result_unchanged`.
                return result.equals(text.value) ? unchanged(text) : PyStr.of(result);
            })
            .add("rfind", (self, args, kwNames) -> search(self, args, kwNames, "rfind", true, false))
            .add("rindex", (self, args, kwNames) -> search(self, args, kwNames, "rindex", true, true))
            .add("rjust", (self, args, kwNames) -> align(self, args, kwNames, "rjust", Side.RIGHT))
            .addOne("rpartition", (self, separator) ->
                    triples(((PyStr) self).divide(divided(separator), false)))
            .add("rsplit", (self, args, kwNames) -> split(self, args, kwNames, "rsplit", true))
            .add("rstrip", (self, args, kwNames) -> trim(self, args, kwNames, "rstrip", false, true))
            .add("split", (self, args, kwNames) -> split(self, args, kwNames, "split", false))
            .add("splitlines", (self, args, kwNames) -> strings(((PyStr) self).lines(
                    Slots.truth(Arguments.parameters(
                            "splitlines", args, kwNames, KEEP_ENDS, KEEP_ENDS_DEFAULTS, 0)[0]))))
            .add("startswith", (self, args, kwNames) ->
                    edges(self, args, kwNames, "startswith", true))
            .add("strip", (self, args, kwNames) -> trim(self, args, kwNames, "strip", true, true))
            .addNoArgument("swapcase", self -> PyStr.of(swapCase(text(self))))
            .add("translate", (self, args, kwNames) -> translated(self, args, kwNames))
            .addNoArgument("title", self -> PyStr.of(capitalize(text(self), true)))
            .addNoArgument("upper", self -> PyStr.of(text(self).toUpperCase(Locale.ROOT)))
            .addOne("zfill", (self, width) -> zeroFilled(self, text(self), Numbers.size(width)))
            // The three names CPython files after the methods a person calls, in the dictionary rather than
            // among them. `__format__` is what a `format()` call reaches, and answers through the same
            // {@link Formats} the builtin does, so that the two spellings of a spec cannot drift; the empty
            // spec hands back the string itself. `__sizeof__` counts a C struct this runtime does not have,
            // and so says nought. `__getnewargs__` hands pickle the one argument a string is rebuilt from.
            .add(
                    "__format__",
                    (self, args, kwNames) -> {
                        Arguments.exactlyOne("str.__format__()", args, kwNames);
                        if (!(args[0] instanceof PyStr spec)) {
                            throw Slots.typeError(
                                    "__format__() argument must be str, not %s",
                                    Slots.argumentName(args[0]));
                        }
                        return spec.value.isEmpty()
                                ? self
                                : PyStr.of(Formats.apply(self, spec.value));
                    })
            .add(
                    "__sizeof__",
                    (self, args, kwNames) -> {
                        Arguments.none("str.__sizeof__()", args, kwNames);
                        return PyInt.of(0);
                    })
            .addNoArgument("__getnewargs__", self -> PyTuple.of(PyStr.of(text(self))))
            // The sentences clinic files beside `unicode_methods`, transcribed from
            // `Objects/clinic/unicodeobject.c.h` and, for the two hand-written ones, from
            // `Objects/unicodeobject.c`. Each is the whole `ml_doc`: the row's own name, its argument
            // table, the `--` knife, and the prose. `__getnewargs__` is the one row of the nine files
            // without a sentence of its own, so CPython builds its table from the METH_NOARGS flag — the
            // shape this registration names, and the second source `__text_signature__` reads.
            .document(
                    "__format__",
                    "__format__($self, format_spec, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a formatted version of the string as described by format_spec.")
            .document(
                    "__sizeof__",
                    "__sizeof__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the size of the string in memory, in bytes.")
            .document(
                    "capitalize",
                    "capitalize($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a capitalized version of the string.\n"
                            + "\n"
                            + "More specifically, make the first character have upper case and the\n"
                            + "rest lower case.")
            .document(
                    "casefold",
                    "casefold($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a version of the string suitable for caseless comparisons.")
            .document(
                    "center",
                    "center($self, width, fillchar=' ', /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a centered string of length width.\n"
                            + "\n"
                            + "Padding is done using the specified fill character (default is\n"
                            + "a space).")
            .document(
                    "count",
                    "count($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the number of non-overlapping occurrences of substring sub in string S[start:end].\n"
                            + "\n"
                            + "Optional arguments start and end are interpreted as in slice\n"
                            + "notation.")
            .document(
                    "encode",
                    "encode($self, /, encoding='utf-8', errors='strict')\n"
                            + "--\n"
                            + "\n"
                            + "Encode the string using the codec registered for encoding.\n"
                            + "\n"
                            + "  encoding\n"
                            + "    The encoding in which to encode the string.\n"
                            + "  errors\n"
                            + "    The error handling scheme to use for encoding errors.\n"
                            + "    The default is 'strict' meaning that encoding errors raise a\n"
                            + "    UnicodeEncodeError.  Other possible values are 'ignore', 'replace'\n"
                            + "    and 'xmlcharrefreplace' as well as any other name registered with\n"
                            + "    codecs.register_error that can handle UnicodeEncodeErrors.")
            .document(
                    "endswith",
                    "endswith($self, suffix[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string ends with the specified suffix, False otherwise.\n"
                            + "\n"
                            + "  suffix\n"
                            + "    A string or a tuple of strings to try.\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the string.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the string.")
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
                            + "Return the lowest index in S where substring sub is found, such that sub is contained within S[start:end].\n"
                            + "\n"
                            + "Optional arguments start and end are interpreted as in slice\n"
                            + "notation.  Return -1 on failure.")
            .document(
                    "format",
                    "format($self, /, *args, **kwargs)\n"
                            + "--\n"
                            + "\n"
                            + "Return a formatted version of the string, using substitutions from args and kwargs.\n"
                            + "The substitutions are identified by braces ('{' and '}').")
            .document(
                    "format_map",
                    "format_map($self, mapping, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a formatted version of the string, using substitutions from mapping.\n"
                            + "The substitutions are identified by braces ('{' and '}').")
            .document(
                    "index",
                    "index($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the lowest index in S where substring sub is found, such that sub is contained within S[start:end].\n"
                            + "\n"
                            + "Optional arguments start and end are interpreted as in slice\n"
                            + "notation.  Raises ValueError when the substring is not found.")
            .document(
                    "isalnum",
                    "isalnum($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is an alpha-numeric string, False otherwise.\n"
                            + "\n"
                            + "A string is alpha-numeric if all characters in the string are\n"
                            + "alpha-numeric and there is at least one character in the string.")
            .document(
                    "isalpha",
                    "isalpha($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is an alphabetic string, False otherwise.\n"
                            + "\n"
                            + "A string is alphabetic if all characters in the string are\n"
                            + "alphabetic and there is at least one character in the string.")
            .document(
                    "isascii",
                    "isascii($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if all characters in the string are ASCII, False otherwise.\n"
                            + "\n"
                            + "ASCII characters have code points in the range U+0000-U+007F.\n"
                            + "Empty string is ASCII too.")
            .document(
                    "isdecimal",
                    "isdecimal($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is a decimal string, False otherwise.\n"
                            + "\n"
                            + "A string is a decimal string if all characters in the string are\n"
                            + "decimal and there is at least one character in the string.")
            .document(
                    "isdigit",
                    "isdigit($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is a digit string, False otherwise.\n"
                            + "\n"
                            + "A string is a digit string if all characters in the string are\n"
                            + "digits and there is at least one character in the string.")
            .document(
                    "isidentifier",
                    "isidentifier($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is a valid Python identifier, False otherwise.\n"
                            + "\n"
                            + "Call keyword.iskeyword(s) to test whether string s is a reserved\n"
                            + "identifier, such as \"def\" or \"class\".")
            .document(
                    "islower",
                    "islower($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is a lowercase string, False otherwise.\n"
                            + "\n"
                            + "A string is lowercase if all cased characters in the string are\n"
                            + "lowercase and there is at least one cased character in the string.")
            .document(
                    "isnumeric",
                    "isnumeric($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is a numeric string, False otherwise.\n"
                            + "\n"
                            + "A string is numeric if all characters in the string are numeric and\n"
                            + "there is at least one character in the string.")
            .document(
                    "isprintable",
                    "isprintable($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if all characters in the string are printable, False otherwise.\n"
                            + "\n"
                            + "A character is printable if repr() may use it in its output.")
            .document(
                    "isspace",
                    "isspace($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is a whitespace string, False otherwise.\n"
                            + "\n"
                            + "A string is whitespace if all characters in the string are\n"
                            + "whitespace and there is at least one character in the string.")
            .document(
                    "istitle",
                    "istitle($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is a title-cased string, False otherwise.\n"
                            + "\n"
                            + "In a title-cased string, upper- and title-case characters may only\n"
                            + "follow uncased characters and lowercase characters only cased ones.")
            .document(
                    "isupper",
                    "isupper($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string is an uppercase string, False otherwise.\n"
                            + "\n"
                            + "A string is uppercase if all cased characters in the string are\n"
                            + "uppercase and there is at least one cased character in the string.")
            .document(
                    "join",
                    "join($self, iterable, /)\n"
                            + "--\n"
                            + "\n"
                            + "Concatenate any number of strings.\n"
                            + "\n"
                            + "The string whose method is called is inserted in between each given\n"
                            + "string.  The result is returned as a new string.\n"
                            + "\n"
                            + "Example: '.'.join(['ab', 'pq', 'rs']) -> 'ab.pq.rs'")
            .document(
                    "ljust",
                    "ljust($self, width, fillchar=' ', /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a left-justified string of length width.\n"
                            + "\n"
                            + "Padding is done using the specified fill character (default is\n"
                            + "a space).")
            .document(
                    "lower",
                    "lower($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy of the string converted to lowercase.")
            .document(
                    "lstrip",
                    "lstrip($self, chars=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy of the string with leading whitespace removed.\n"
                            + "\n"
                            + "If chars is given and not None, remove characters in chars instead.")
            .document(
                    "partition",
                    "partition($self, sep, /)\n"
                            + "--\n"
                            + "\n"
                            + "Partition the string into three parts using the given separator.\n"
                            + "\n"
                            + "This will search for the separator in the string.  If the separator\n"
                            + "is found, returns a 3-tuple containing the part before the\n"
                            + "separator, the separator itself, and the part after it.\n"
                            + "\n"
                            + "If the separator is not found, returns a 3-tuple containing\n"
                            + "the original string and two empty strings.")
            .document(
                    "removeprefix",
                    "removeprefix($self, prefix, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a str with the given prefix string removed if present.\n"
                            + "\n"
                            + "If the string starts with the prefix string, return\n"
                            + "string[len(prefix):].  Otherwise, return a copy of the original\n"
                            + "string.")
            .document(
                    "removesuffix",
                    "removesuffix($self, suffix, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a str with the given suffix string removed if present.\n"
                            + "\n"
                            + "If the string ends with the suffix string and that suffix is not\n"
                            + "empty, return string[:-len(suffix)].  Otherwise, return a copy of\n"
                            + "the original string.")
            .document(
                    "replace",
                    "replace($self, old, new, /, count=-1)\n"
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
                            + "Return the highest index in S where substring sub is found, such that sub is contained within S[start:end].\n"
                            + "\n"
                            + "Optional arguments start and end are interpreted as in slice\n"
                            + "notation.  Return -1 on failure.")
            .document(
                    "rindex",
                    "rindex($self, sub[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the highest index in S where substring sub is found, such that sub is contained within S[start:end].\n"
                            + "\n"
                            + "Optional arguments start and end are interpreted as in slice\n"
                            + "notation.  Raises ValueError when the substring is not found.")
            .document(
                    "rjust",
                    "rjust($self, width, fillchar=' ', /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a right-justified string of length width.\n"
                            + "\n"
                            + "Padding is done using the specified fill character (default is\n"
                            + "a space).")
            .document(
                    "rpartition",
                    "rpartition($self, sep, /)\n"
                            + "--\n"
                            + "\n"
                            + "Partition the string into three parts using the given separator.\n"
                            + "\n"
                            + "This will search for the separator in the string, starting at the\n"
                            + "end.  If the separator is found, returns a 3-tuple containing the\n"
                            + "part before the separator, the separator itself, and the part after\n"
                            + "it.\n"
                            + "\n"
                            + "If the separator is not found, returns a 3-tuple containing two\n"
                            + "empty strings and the original string.")
            .document(
                    "rsplit",
                    "rsplit($self, /, sep=None, maxsplit=-1)\n"
                            + "--\n"
                            + "\n"
                            + "Return a list of the substrings in the string, using sep as the separator string.\n"
                            + "\n"
                            + "  sep\n"
                            + "    The separator used to split the string.\n"
                            + "\n"
                            + "    When set to None (the default value), will split on any\n"
                            + "    whitespace character (including \\n \\r \\t \\f and spaces) and\n"
                            + "    will discard empty strings from the result.\n"
                            + "  maxsplit\n"
                            + "    Maximum number of splits.\n"
                            + "    -1 (the default value) means no limit.\n"
                            + "\n"
                            + "Splitting starts at the end of the string and works to the front.")
            .document(
                    "rstrip",
                    "rstrip($self, chars=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy of the string with trailing whitespace removed.\n"
                            + "\n"
                            + "If chars is given and not None, remove characters in chars instead.")
            .document(
                    "split",
                    "split($self, /, sep=None, maxsplit=-1)\n"
                            + "--\n"
                            + "\n"
                            + "Return a list of the substrings in the string, using sep as the separator string.\n"
                            + "\n"
                            + "  sep\n"
                            + "    The separator used to split the string.\n"
                            + "\n"
                            + "    When set to None (the default value), will split on any\n"
                            + "    whitespace character (including \\n \\r \\t \\f and spaces) and\n"
                            + "    will discard empty strings from the result.\n"
                            + "  maxsplit\n"
                            + "    Maximum number of splits.\n"
                            + "    -1 (the default value) means no limit.\n"
                            + "\n"
                            + "Splitting starts at the front of the string and works to the end.\n"
                            + "\n"
                            + "Note, str.split() is mainly useful for data that has been\n"
                            + "intentionally delimited.  With natural text that includes\n"
                            + "punctuation, consider using the regular expression module.")
            .document(
                    "splitlines",
                    "splitlines($self, /, keepends=False)\n"
                            + "--\n"
                            + "\n"
                            + "Return a list of the lines in the string, breaking at line boundaries.\n"
                            + "\n"
                            + "Line breaks are not included in the resulting list unless keepends\n"
                            + "is given and true.")
            .document(
                    "startswith",
                    "startswith($self, prefix[, start[, end]], /)\n"
                            + "--\n"
                            + "\n"
                            + "Return True if the string starts with the specified prefix, False otherwise.\n"
                            + "\n"
                            + "  prefix\n"
                            + "    A string or a tuple of strings to try.\n"
                            + "  start\n"
                            + "    Optional start position. Default: start of the string.\n"
                            + "  end\n"
                            + "    Optional stop position. Default: end of the string.")
            .document(
                    "strip",
                    "strip($self, chars=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy of the string with leading and trailing whitespace removed.\n"
                            + "\n"
                            + "If chars is given and not None, remove characters in chars instead.")
            .document(
                    "swapcase",
                    "swapcase($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Convert uppercase characters to lowercase and lowercase characters to uppercase.")
            .document(
                    "title",
                    "title($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a version of the string where each word is titlecased.\n"
                            + "\n"
                            + "More specifically, words start with uppercased characters and all\n"
                            + "remaining cased characters have lower case.")
            .document(
                    "translate",
                    "translate($self, table, /)\n"
                            + "--\n"
                            + "\n"
                            + "Replace each character in the string using the given translation table.\n"
                            + "\n"
                            + "  table\n"
                            + "    Translation table, which must be a mapping of Unicode ordinals\n"
                            + "    to Unicode ordinals, strings, or None.\n"
                            + "\n"
                            + "The table must implement lookup/indexing via __getitem__, for\n"
                            + "instance a dictionary or list.  If this operation raises\n"
                            + "LookupError, the character is left untouched.  Characters mapped to\n"
                            + "None are deleted.")
            .document(
                    "upper",
                    "upper($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy of the string converted to uppercase.")
            .document(
                    "zfill",
                    "zfill($self, width, /)\n"
                            + "--\n"
                            + "\n"
                            + "Pad a numeric string with zeros on the left, to fill a field of the given width.\n"
                            + "\n"
                            + "The string is never truncated.");

    private static String text(PyObject self) {
        return ((PyStr) self).value;
    }

    /**
     * {@code str.maketrans(x[, y[, z]])}: the dictionary {@code translate} is fed.
     *
     * <p>Two shapes and one order of refusals. The second and third arguments are converted by the
     * argument table before the body sees anything, so {@code maketrans(3, 4)} complains about argument 2
     * rather than about the first argument, and only then does the one-argument shape ask for a dict. A
     * dict is re-keyed but never re-valued: a character key becomes its ordinal, an integer key stays as
     * it is, and a value is stored whatever it holds — {@code maketrans({'a': 3})} is accepted even though
     * {@code translate} will not be. CPython's two messages about keys are mis-spelled in its own source
     * ("translatetable", "mustbe"), and are transcribed here rather than tidied.
     */
    private static PyObject makeTrans(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("str.maketrans()", kwNames);
        Arguments.between("maketrans", args, kwNames, 1, 3);
        PyDict built = PyDict.empty();
        if (args.length == 1) {
            if (!(args[0] instanceof PyDict table)) {
                throw Slots.typeError(
                        "if you give only one argument to maketrans it must be a dict");
            }
            for (PyObject pair : table.pairs()) {
                PyObject key = ((PyTuple) pair).at(0);
                PyObject value = ((PyTuple) pair).at(1);
                if (key instanceof PyStr character) {
                    if (character.codePoints != 1) {
                        throw Slots.valueError("string keys in translatetable must be of length 1");
                    }
                    built.put(PyInt.of(character.value.codePointAt(0)), value);
                } else if (key instanceof PyInt ordinal) {
                    built.put(ordinal, value);
                } else {
                    throw Slots.typeError("keys in translate table mustbe strings or integers");
                }
            }
            return built;
        }
        if (!(args[1] instanceof PyStr to)) {
            throw Slots.typeError(
                    "maketrans() argument 2 must be str, not %s", args[1].getType().name());
        }
        if (args.length > 2 && !(args[2] instanceof PyStr)) {
            throw Slots.typeError(
                    "maketrans() argument 3 must be str, not %s", args[2].getType().name());
        }
        if (!(args[0] instanceof PyStr from)) {
            throw Slots.typeError(
                    "first maketrans argument must be a string if there is a second argument");
        }
        int[] source = points(from.value);
        int[] target = points(to.value);
        if (source.length != target.length) {
            throw Slots.valueError("the first two maketrans arguments must have equal length");
        }
        for (int at = 0; at < source.length; at++) {
            built.put(PyInt.of(source[at]), PyInt.of(target[at]));
        }
        if (args.length > 2) {
            // The deletions are named by the text they read as, and every one of them answers None.
            for (int doomed : points(((PyStr) args[2]).value)) {
                built.put(PyInt.of(doomed), PyNone.INSTANCE);
            }
        }
        return built;
    }

    /**
     * {@code str.translate(table)}: every code point is looked up in the table, and what it answers
     * decides the character — no answer at all keeps it, {@code None} drops it, and an {@code int} or
     * a {@code str} replaces it.
     *
     * <p>The lookup goes through the ordinary subscript protocol, which is why a {@code None} table is
     * refused as {@code 'NoneType' object is not subscriptable} rather than silently ignored, and why
     * a {@code KeyError} or {@code IndexError} from a mapping, a list or a class is the "no answer"
     * case. Any other exception is the table's and is left to travel.
     */
    private static PyObject translated(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("str.translate()", args, kwNames);
        String source = text(self);
        StringBuilder out = new StringBuilder(source.length());
        for (int at = 0; at < source.length(); ) {
            int point = source.codePointAt(at);
            at += Character.charCount(point);
            PyObject mapped;
            try {
                mapped = Slots.getItem(args[0], PyInt.of(point));
            } catch (PyRaise missing) {
                if (missing.value().exceptionType().matches(ExceptionTypes.LOOKUP_ERROR)) {
                    out.appendCodePoint(point);
                    continue;
                }
                throw missing;
            }
            if (mapped == PyNone.INSTANCE) {
                continue;
            }
            if (mapped instanceof PyStr replacement) {
                out.append(replacement.value);
                continue;
            }
            BigInteger ordinal = Numbers.asInteger(mapped);
            if (ordinal == null) {
                throw Slots.typeError("character mapping must return integer, None or str");
            }
            if (ordinal.signum() < 0 || ordinal.compareTo(BigInteger.valueOf(0x110000)) >= 0) {
                throw Slots.valueError("character mapping must be in range(0x110000)");
            }
            out.appendCodePoint(ordinal.intValue());
        }
        return PyStr.of(out.toString());
    }

    /** The code points of a text, which is what {@code maketrans} pairs up and counts by. */
    private static int[] points(String text) {
        int[] out = new int[text.codePointCount(0, text.length())];
        for (int at = 0, read = 0; at < out.length; at++) {
            int point = text.codePointAt(read);
            out[at] = point;
            read += Character.charCount(point);
        }
        return out;
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
     * A string argument, with the message that names the method it was passed to.
     *
     * <p>CPython's {@code U} converter refuses through {@code _PyArg_BadArgument}, which leaves the type
     * unquoted and calls {@code None} by what it is rather than by its type's name.
     */
    private static String pattern(PyObject value, String name) {
        return pattern(value, name, 1);
    }

    /** The same test for an argument that is not the first, which CPython numbers. */
    private static String pattern(PyObject value, String name, int position) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError(
                    "%s() argument %d must be str, not %s",
                    name, position, Slots.argumentName(value));
        }
        return text;
    }

    /**
     * {@code partition}'s separator, refused by a converter that names no method at all.
     *
     * <p>{@code ensure_unicode} is the {@code O&} converter behind {@code str.partition}, and it writes
     * its own bare sentence: {@code must be str, not NoneType}. Unlike {@link #pattern} it calls
     * {@code None} by its type's name, and it is the reason a {@code partition} refusal looks nothing
     * like the {@code find} refusal beside it.
     */
    private static String divided(PyObject value) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError("must be str, not %s", value.getType().filedName());
        }
        return text;
    }

    /**
     * {@code removeprefix}'s prefix, which a {@code METH_O} wrapper refuses without a position.
     *
     * <p>{@code _PyArg_BadArgument} is handed the word {@code argument} rather than {@code argument 1},
     * because a one-argument wrapper has no count to be precise about: {@code 'ab'.removeprefix(None)}
     * says {@code removeprefix() argument must be str, not None}.
     */
    private static String edge(PyObject value, String name) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError(
                    "%s() argument must be str, not %s", name, Slots.argumentName(value));
        }
        return text;
    }

    /**
     * A named parameter of its own method, which CPython names in the refusal.
     *
     * <p>The wording is {@code _PyArg_BadArgument} (Python/getargs.c:622), and that function makes {@code
     * None} the one value whose type is not named: {@code encode("ascii", None)} says {@code not None}, where
     * {@code encode("ascii", 7)} says {@code not int}. Both parameters of {@code encode} and of {@code
     * bytes.decode} are Clinic {@code str} converters, so a name with a null character in it is refused as a
     * value error before the codec is looked for; {@code decode} asks the same two questions with its own name
     * in the sentence.
     */
    static String named(PyObject value, String method, String parameter) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError(
                    "%s() argument '%s' must be str, not %s",
                    method, parameter, Slots.argumentName(value));
        }
        if (text.indexOf('\0') >= 0) {
            throw Slots.valueError("embedded null character");
        }
        return text;
    }

    /**
     * {@code unicode_result_unchanged}: a method that would hand its receiver back at once answers an
     * exact copy instead when the receiver is a subclass, which is why {@code S('a').upper()} and
     * {@code S('a').ljust(1)} are plain {@code str}.
     */
    private static PyObject unchanged(PyStr self) {
        return self.getType() == PyType.STR ? self : PyStr.of(self.value);
    }

    /** The fill character of {@code center}, {@code ljust} and {@code rjust}. */
    private static String fill(PyObject value) {
        String text = Numbers.asText(value);
        if (text == null) {
            throw Slots.typeError(
                    "The fill character must be a unicode character, not %s", value.getType().name());
        }
        if (text.codePointCount(0, text.length()) != 1) {
            throw Slots.typeError("The fill character must be exactly one character long");
        }
        return text;
    }

    // --- method bodies ---------------------------------------------------------------------

    /** {@code a.center(b)} and friends, whose only difference is which side gets the padding. */
    private static PyObject align(
            PyObject self, PyObject[] args, String[] kwNames, String name, Side side) {
        positional(name, args, kwNames, 1, 2);
        PyStr text = (PyStr) self;
        long width = Numbers.size(args[0]);
        String fill = args.length > 1 ? fill(args[1]) : " ";
        long margin = width - text.codePoints;
        if (margin <= 0) {
            return unchanged(text);
        }
        // An odd margin is not split by rounding: the extra character goes left only when the width is
        // odd as well, which is CPython's `marg / 2 + (marg & width & 1)` and reads as
        // `'ab'.center(4)` being `' ab '` while `center(5)` is `'  ab '`.
        long before = switch (side) {
            case CENTER -> margin / 2 + (margin & width & 1);
            case LEFT -> 0;
            case RIGHT -> margin;
        };
        // A margin too wide to number is refused before it is asked for, the way CPython's allocator
        // refuses it: the answer is an empty MemoryError, not a Java failure.
        Numbers.total(text.value.length() + margin);
        return PyStr.of(
                fill.repeat((int) before) + text.value + fill.repeat((int) (margin - before)));
    }

    private static PyObject search(
            PyObject self, PyObject[] args, String[] kwNames, String name, boolean right, boolean raises) {
        positional(name, args, kwNames, 1, 3);
        PyStr text = (PyStr) self;
        int found = text.first(
                pattern(args[0], name),
                args.length > 1 ? Sequences.searchFrom(args[1], text.codePoints) : 0,
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
        int from = args.length > 1 ? Sequences.searchFrom(args[1], text.codePoints) : 0;
        int to = args.length > 2 ? Sequences.searchStop(args[2], text.codePoints) : text.codePoints;
        if (from > to) {
            // Not even the empty prefix matches a range that is not one — and a start past the end
            // makes the range not one rather than pulling it back to an edge, which is what
            // distinguishes this from {@code list.index}'s reading of the same bound.
            return PyBool.FALSE;
        }
        int fromUnit = text.charIndex(from);
        int toUnit = to >= text.codePoints ? text.value.length() : text.charIndex(to);
        int width = toUnit - fromUnit;
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
                if (text.edgeMatches(edge, fromUnit, width, prefix)) {
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
        return PyBool.of(text.edgeMatches(single, fromUnit, width, prefix));
    }

    private static PyObject split(
            PyObject self, PyObject[] args, String[] kwNames, String name, boolean right) {
        PyObject[] parameters =
                Arguments.parameters(
                        name, args, kwNames, SEPARATOR, SEPARATOR_DEFAULTS, 0);
        return strings(((PyStr) self).pieces(
                separator(parameters[0], name), Numbers.size(parameters[1]), right));
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
    private static PyObject join(String separator, PyObject iterable) {
        if (iterable.iterator() == Slots.DECLINED) {
            throw Slots.typeError("can only join an iterable");
        }
        StringBuilder out = new StringBuilder();
        PyObject only = null;
        int count = 0;
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
            } else {
                only = part;
            }
            out.append(text.value);
            count++;
        }
        // A single exact str: CPython answers that item itself. A subclass is rebuilt below, which
        // is what makes `type(S('a').join(...))` plain `str`.
        if (count == 1 && only.getType() == PyType.STR) {
            return only;
        }
        return PyStr.of(out.toString());
    }

    /**
     * {@code zfill}: zeros in front of a number, and a sign stays in front of the zeros.
     *
     * <p>Only the first character can be a sign, and it moves rather than being re-read, so
     * {@code '--1'.zfill(6)} is {@code '-000-1'}. A value already as wide as it was asked to be comes
     * back unchanged — as a copy, for a subclass, the same rule the three padding methods follow.
     */
    private static PyObject zeroFilled(PyObject self, String text, long width) {
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints >= width) {
            return unchanged((PyStr) self);
        }
        long margin = width - codePoints;
        Numbers.total(text.length() + margin);
        String zeros = "0".repeat((int) margin);
        if (!text.isEmpty() && "+-".indexOf(text.charAt(0)) >= 0) {
            return PyStr.of(text.charAt(0) + zeros + text.substring(1));
        }
        return PyStr.of(zeros + text);
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
                    + lowerText(text, Character.charCount(first), text.length());
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
                    ? lowerText(text, at, end)
                    : titleHead(code) + lowerText(text, next, end));
            previousAlphabetic = true;
            at = end;
        }
        return out.toString();
    }

    /** The title form of one code point: a full mapping, then its own tail lower-cased. */
    private static String titleHead(int code) {
        // Georgian mkhedruli letters carry an uppercase mapping but no titlecase mapping, and a title
        // moves only on the titlecase mapping: `'\u10D0'.title()` is itself while `.upper()` is
        // `'\u1C90'`. Java reports no title mapping for them either; only the uppercase fallback
        // below would move them, so they keep their own form here.
        if (code >= 0x10D0 && code <= 0x10FA || code >= 0x10FD && code <= 0x10FF) {
            return String.valueOf(Character.toChars(code));
        }
        // A letter already in its title form keeps it: Java answers toTitleCase with the same code
        // point, and the fallback below would then read its uppercase form (ǅ -> Ǆ).
        if (Character.isTitleCase(code)) {
            return String.valueOf(Character.toChars(code));
        }
        int title = Character.toTitleCase(code);
        String one = String.valueOf(Character.toChars(code));
        String special = specialTitle(code);
        if (special != null) {
            return special;
        }
        String mapped = title != code
                ? String.valueOf(Character.toChars(title))
                : one.toUpperCase(Locale.ROOT);
        int head = mapped.codePointAt(0);
        int after = Character.charCount(head);
        return mapped.substring(0, after) + mapped.substring(after).toLowerCase(Locale.ROOT);
    }

    /**
     * The title mappings that are neither a simple title mapping nor the uppercase expansion with a
     * lowercased tail.
     *
     * <p>These ten are the SpecialCasing title entries Java's uppercase cannot be bent into: the
     * ligature apostrophe keeps its capital N, and the Greek letters with a ypogegrammeni keep the
     * combining iota where uppercase spells the spacing one.
     */
    private static String specialTitle(int code) {
        return switch (code) {
            case 0x0149 -> "\u02BCN";
            case 0x1FB2 -> "\u1FBA\u0345";
            case 0x1FB4 -> "\u0386\u0345";
            case 0x1FB7 -> "\u0391\u0342\u0345";
            case 0x1FC2 -> "\u1FCA\u0345";
            case 0x1FC4 -> "\u0389\u0345";
            case 0x1FC7 -> "\u0397\u0342\u0345";
            case 0x1FF2 -> "\u1FFA\u0345";
            case 0x1FF4 -> "\u038F\u0345";
            case 0x1FF7 -> "\u03A9\u0342\u0345";
            default -> null;
        };
    }

    /** {@code swapcase}, which like {@code capitalize} uses the full one-way mappings. */
    private static String swapCase(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            int next = at + Character.charCount(code);
            String one = String.valueOf(Character.toChars(code));
            // A titlecase letter is neither of the two cases CPython switches between, even though
            // Java's one-way mappings do move it; the two property tables are what it tests.
            out.append(Character.isTitleCase(code) ? one
                    : Character.toLowerCase(code) != code
                    ? lowerOne(text, at, code)
                    : one.toUpperCase(Locale.ROOT));
            at = next;
        }
        return out.toString();
    }

    private static String expandTabs(String text, int tabsize) {
        // CPython counts the columns in a first pass and only then makes the string, so a tabsize wide
        // enough to need more characters than a Java string can hold is refused where the paddings refuse
        // theirs. {@code new string is too long} — the overflow of that same count — would need a string
        // of four billion tabs to reach, which is longer than this runtime can build anything anyway.
        long total = 0;
        long column = 0;
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            if (code == '\t') {
                long spaces = tabsize > 0 ? tabsize - column % tabsize : 0;
                total += spaces;
                column += spaces;
            } else {
                total++;
                column = code == '\n' || code == '\r' ? 0 : column + 1;
            }
        }
        Numbers.total(total);
        StringBuilder out = new StringBuilder(text.length());
        column = 0;
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            if (code == '\t') {
                // A tabsize of zero or less deletes the tab instead of expanding it.
                if (tabsize > 0) {
                    int spaces = (int) (tabsize - column % tabsize);
                    out.append(" ".repeat(spaces));
                    column += spaces;
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
            // A test that only asks what the code points of the string are is vacuously true of a
            // string that has none, and `isascii` is the only one of the eight that asks no more than
            // that: `"".isascii()` is true while `"".isalpha()` and its neighbours are false, because
            // those want to have found something to classify.
            return kind == Category.ASCII;
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
     * The {@code Numeric_Type=Digit} code points beyond Nd: twenty ranges, 128 code points, as the
     * reference 3.14.10 spells them in {@code _PyUnicode_IsDigit}.
     *
     * <p>Java exposes no such property, only {@code getNumericValue}, which cannot separate
     * {@code '①'} (a digit) from {@code '\U00011065'} (a number): both answer a positive integer.
     * The table is the difference, so {@code isdigit} asks it directly instead of guessing at the
     * number's shape. Superscripts, subscripts, circled and parenthesized digits, and the few
     * non-decimal scripts that give their numbers digit status are what it holds.
     */
    private static final int[] NON_DECIMAL_DIGIT_RANGES = {
        0x00B2, 0x00B3, 0x00B9, 0x00B9, 0x1369, 0x1371, 0x19DA, 0x19DA,
        0x2070, 0x2070, 0x2074, 0x2079, 0x2080, 0x2089, 0x2460, 0x2468,
        0x2474, 0x247C, 0x2488, 0x2490, 0x24EA, 0x24EA, 0x24F5, 0x24FD,
        0x24FF, 0x24FF, 0x2776, 0x277E, 0x2780, 0x2788, 0x278A, 0x2792,
        0x10A40, 0x10A43, 0x10E60, 0x10E68, 0x11052, 0x1105A,
        0x1F100, 0x1F10A,
    };

    private static boolean isNonDecimalDigit(int code) {
        for (int i = 0; i < NON_DECIMAL_DIGIT_RANGES.length; i += 2) {
            if (code >= NON_DECIMAL_DIGIT_RANGES[i] && code <= NON_DECIMAL_DIGIT_RANGES[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one code point satisfies a category test. {@code isdecimal} is Nd and
     * {@code isnumeric} is Nd, Nl or No, exactly as Python defines them; {@code isdigit} is Nd plus
     * the {@code Numeric_Type=Digit} characters of other general categories, which is the table
     * {@link #NON_DECIMAL_DIGIT_RANGES} records because Java has no property for it.
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
            case DIGIT -> Character.isDigit(code) || isNonDecimalDigit(code);
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

    /**
     * {@code isupper} and {@code islower}: at least one cased character, and none of the other kind.
     *
     * <p>A character counts as cased when Unicode gives it a case of its own, which is not the same
     * question as whether Java's one-to-one case mapping moves it: {@code 'ß'} has no single-character
     * uppercase in Java, so comparing it with {@code toUpperCase} leaves it looking uncased, while
     * {@code 'ß'.islower()} is true because its general category is a lowercase letter.
     */
    private static boolean caseTest(String text, boolean lower) {
        boolean seen = false;
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            boolean cased = Character.isLowerCase(code)
                    || Character.isUpperCase(code)
                    || Character.isTitleCase(code);
            if (!cased) {
                continue;
            }
            seen = true;
            if (lower ? !Character.isLowerCase(code) : !Character.isUpperCase(code)) {
                return false;
            }
        }
        return seen;
    }

    /**
     * {@code casefold}: Unicode's full case folding.
     *
     * <p>Lowercasing already answers the folding for most characters; the keys below are the ones
     * where it does not, which is three groups. The Greek letters with a ypogegrammeni fold into a
     * base vowel plus iota (and {@code ς} folds to {@code σ}), the Cherokee pairs fold toward the
     * uppercase code points rather than toward their lowercase, and the ligatures, letterlike
     * symbols and a few Latin letters expand or move sideways. The table was read from the reference
     * interpreter's {@code str.casefold} over every code point, so it is exactly this Unicode version.
     */
    private static String caseFold(String text) {
        String lowered = lowerText(text);
        StringBuilder out = null;
        for (int at = 0; at < lowered.length(); ) {
            int code = lowered.codePointAt(at);
            int next = at + Character.charCount(code);
            int found = Arrays.binarySearch(CASE_FOLD_KEYS, code);
            if (found < 0) {
                if (out != null) {
                    out.appendCodePoint(code);
                }
                at = next;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(lowered.length() + 2).append(lowered, 0, at);
            }
            out.append(CASE_FOLD_VALUES[found]);
            at = next;
        }
        return out == null ? lowered : out.toString();
    }

    /**
     * The pair this table is looked up by: one code point and the full case folding it gets.
     */
    private static final int[] CASE_FOLD_KEYS = {
        0x00B5, 0x00DF, 0x0149, 0x017F, 0x01F0, 0x0345, 0x0390, 0x03B0, 0x03C2, 0x03D0, 0x03D1,
        0x03D5, 0x03D6, 0x03F0, 0x03F1, 0x03F5, 0x0587, 0x13F8, 0x13F9, 0x13FA, 0x13FB, 0x13FC,
        0x13FD, 0x1C80, 0x1C81, 0x1C82, 0x1C83, 0x1C84, 0x1C85, 0x1C86, 0x1C87, 0x1C88, 0x1E96,
        0x1E97, 0x1E98, 0x1E99, 0x1E9A, 0x1E9B, 0x1E9E, 0x1F50, 0x1F52, 0x1F54, 0x1F56, 0x1F80,
        0x1F81, 0x1F82, 0x1F83, 0x1F84, 0x1F85, 0x1F86, 0x1F87, 0x1F88, 0x1F89, 0x1F8A, 0x1F8B,
        0x1F8C, 0x1F8D, 0x1F8E, 0x1F8F, 0x1F90, 0x1F91, 0x1F92, 0x1F93, 0x1F94, 0x1F95, 0x1F96,
        0x1F97, 0x1F98, 0x1F99, 0x1F9A, 0x1F9B, 0x1F9C, 0x1F9D, 0x1F9E, 0x1F9F, 0x1FA0, 0x1FA1,
        0x1FA2, 0x1FA3, 0x1FA4, 0x1FA5, 0x1FA6, 0x1FA7, 0x1FA8, 0x1FA9, 0x1FAA, 0x1FAB, 0x1FAC,
        0x1FAD, 0x1FAE, 0x1FAF, 0x1FB2, 0x1FB3, 0x1FB4, 0x1FB6, 0x1FB7, 0x1FBC, 0x1FBE, 0x1FC2,
        0x1FC3, 0x1FC4, 0x1FC6, 0x1FC7, 0x1FCC, 0x1FD2, 0x1FD3, 0x1FD6, 0x1FD7, 0x1FE2, 0x1FE3,
        0x1FE4, 0x1FE6, 0x1FE7, 0x1FF2, 0x1FF3, 0x1FF4, 0x1FF6, 0x1FF7, 0x1FFC, 0xAB70, 0xAB71,
        0xAB72, 0xAB73, 0xAB74, 0xAB75, 0xAB76, 0xAB77, 0xAB78, 0xAB79, 0xAB7A, 0xAB7B, 0xAB7C,
        0xAB7D, 0xAB7E, 0xAB7F, 0xAB80, 0xAB81, 0xAB82, 0xAB83, 0xAB84, 0xAB85, 0xAB86, 0xAB87,
        0xAB88, 0xAB89, 0xAB8A, 0xAB8B, 0xAB8C, 0xAB8D, 0xAB8E, 0xAB8F, 0xAB90, 0xAB91, 0xAB92,
        0xAB93, 0xAB94, 0xAB95, 0xAB96, 0xAB97, 0xAB98, 0xAB99, 0xAB9A, 0xAB9B, 0xAB9C, 0xAB9D,
        0xAB9E, 0xAB9F, 0xABA0, 0xABA1, 0xABA2, 0xABA3, 0xABA4, 0xABA5, 0xABA6, 0xABA7, 0xABA8,
        0xABA9, 0xABAA, 0xABAB, 0xABAC, 0xABAD, 0xABAE, 0xABAF, 0xABB0, 0xABB1, 0xABB2, 0xABB3,
        0xABB4, 0xABB5, 0xABB6, 0xABB7, 0xABB8, 0xABB9, 0xABBA, 0xABBB, 0xABBC, 0xABBD, 0xABBE,
        0xABBF, 0xFB00, 0xFB01, 0xFB02, 0xFB03, 0xFB04, 0xFB05, 0xFB06, 0xFB13, 0xFB14, 0xFB15,
        0xFB16, 0xFB17,
    };

    private static final String[] CASE_FOLD_VALUES = {
        "\u03BC", "\u0073\u0073", "\u02BC\u006E", "\u0073", "\u006A\u030C", "\u03B9",
        "\u03B9\u0308\u0301", "\u03C5\u0308\u0301", "\u03C3", "\u03B2", "\u03B8", "\u03C6",
        "\u03C0", "\u03BA", "\u03C1", "\u03B5", "\u0565\u0582", "\u13F0", "\u13F1", "\u13F2",
        "\u13F3", "\u13F4", "\u13F5", "\u0432", "\u0434", "\u043E", "\u0441", "\u0442", "\u0442",
        "\u044A", "\u0463", "\uA64B", "\u0068\u0331", "\u0074\u0308", "\u0077\u030A",
        "\u0079\u030A", "\u0061\u02BE", "\u1E61", "\u0073\u0073", "\u03C5\u0313",
        "\u03C5\u0313\u0300", "\u03C5\u0313\u0301", "\u03C5\u0313\u0342", "\u1F00\u03B9",
        "\u1F01\u03B9", "\u1F02\u03B9", "\u1F03\u03B9", "\u1F04\u03B9", "\u1F05\u03B9",
        "\u1F06\u03B9", "\u1F07\u03B9", "\u1F00\u03B9", "\u1F01\u03B9", "\u1F02\u03B9",
        "\u1F03\u03B9", "\u1F04\u03B9", "\u1F05\u03B9", "\u1F06\u03B9", "\u1F07\u03B9",
        "\u1F20\u03B9", "\u1F21\u03B9", "\u1F22\u03B9", "\u1F23\u03B9", "\u1F24\u03B9",
        "\u1F25\u03B9", "\u1F26\u03B9", "\u1F27\u03B9", "\u1F20\u03B9", "\u1F21\u03B9",
        "\u1F22\u03B9", "\u1F23\u03B9", "\u1F24\u03B9", "\u1F25\u03B9", "\u1F26\u03B9",
        "\u1F27\u03B9", "\u1F60\u03B9", "\u1F61\u03B9", "\u1F62\u03B9", "\u1F63\u03B9",
        "\u1F64\u03B9", "\u1F65\u03B9", "\u1F66\u03B9", "\u1F67\u03B9", "\u1F60\u03B9",
        "\u1F61\u03B9", "\u1F62\u03B9", "\u1F63\u03B9", "\u1F64\u03B9", "\u1F65\u03B9",
        "\u1F66\u03B9", "\u1F67\u03B9", "\u1F70\u03B9", "\u03B1\u03B9", "\u03AC\u03B9",
        "\u03B1\u0342", "\u03B1\u0342\u03B9", "\u03B1\u03B9", "\u03B9", "\u1F74\u03B9",
        "\u03B7\u03B9", "\u03AE\u03B9", "\u03B7\u0342", "\u03B7\u0342\u03B9", "\u03B7\u03B9",
        "\u03B9\u0308\u0300", "\u03B9\u0308\u0301", "\u03B9\u0342", "\u03B9\u0308\u0342",
        "\u03C5\u0308\u0300", "\u03C5\u0308\u0301", "\u03C1\u0313", "\u03C5\u0342",
        "\u03C5\u0308\u0342", "\u1F7C\u03B9", "\u03C9\u03B9", "\u03CE\u03B9", "\u03C9\u0342",
        "\u03C9\u0342\u03B9", "\u03C9\u03B9", "\u13A0", "\u13A1", "\u13A2", "\u13A3", "\u13A4",
        "\u13A5", "\u13A6", "\u13A7", "\u13A8", "\u13A9", "\u13AA", "\u13AB", "\u13AC", "\u13AD",
        "\u13AE", "\u13AF", "\u13B0", "\u13B1", "\u13B2", "\u13B3", "\u13B4", "\u13B5", "\u13B6",
        "\u13B7", "\u13B8", "\u13B9", "\u13BA", "\u13BB", "\u13BC", "\u13BD", "\u13BE", "\u13BF",
        "\u13C0", "\u13C1", "\u13C2", "\u13C3", "\u13C4", "\u13C5", "\u13C6", "\u13C7", "\u13C8",
        "\u13C9", "\u13CA", "\u13CB", "\u13CC", "\u13CD", "\u13CE", "\u13CF", "\u13D0", "\u13D1",
        "\u13D2", "\u13D3", "\u13D4", "\u13D5", "\u13D6", "\u13D7", "\u13D8", "\u13D9", "\u13DA",
        "\u13DB", "\u13DC", "\u13DD", "\u13DE", "\u13DF", "\u13E0", "\u13E1", "\u13E2", "\u13E3",
        "\u13E4", "\u13E5", "\u13E6", "\u13E7", "\u13E8", "\u13E9", "\u13EA", "\u13EB", "\u13EC",
        "\u13ED", "\u13EE", "\u13EF", "\u0066\u0066", "\u0066\u0069", "\u0066\u006C",
        "\u0066\u0066\u0069", "\u0066\u0066\u006C", "\u0073\u0074", "\u0073\u0074", "\u0574\u0576",
        "\u0574\u0565", "\u0574\u056B", "\u057E\u0576", "\u0574\u056D",
    };

    /** {@code lower()}: each code point's lowercase mapping, with the final-sigma rule. */
    private static String lowerText(String text) {
        return lowerText(text, 0, text.length());
    }

    /**
     * The lowercase mapping of {@code text[from, to)}, with CPython's final-sigma rule.
     *
     * <p>Σ becomes ς only when a cased letter precedes it and none follows, both scans skipping
     * case-ignorable characters. The rule reads the whole string rather than the slice, which is why
     * {@code capitalize} and {@code title} hand their tails here instead of lowering a substring:
     * {@code 'A\u0345\u03a3'.capitalize()} must end in ς even though the tail alone has no cased
     * letter before the sigma.
     */
    private static String lowerText(String text, int from, int to) {
        StringBuilder out = new StringBuilder(to - from);
        for (int at = from; at < to; ) {
            int code = text.codePointAt(at);
            out.append(lowerOne(text, at, code));
            at += Character.charCount(code);
        }
        return out.toString();
    }

    /** One code point's lowercase mapping, where Σ asks the surrounding text first. */
    private static String lowerOne(String text, int at, int code) {
        if (code == '\u03A3' && isFinalSigma(text, at)) {
            return "\u03C2";
        }
        return String.valueOf(Character.toChars(code)).toLowerCase(Locale.ROOT);
    }

    /**
     * CPython's {@code is_final_sigma}: a cased letter before the sigma, no cased letter after it,
     * with case-ignorable characters stepped over in both directions.
     */
    private static boolean isFinalSigma(String text, int at) {
        boolean casedBefore = false;
        for (int i = at; i > 0; ) {
            int code = text.codePointBefore(i);
            i -= Character.charCount(code);
            if (isCaseIgnorable(code)) {
                continue;
            }
            casedBefore = isCased(code);
            break;
        }
        if (!casedBefore) {
            return false;
        }
        for (int i = at + Character.charCount('\u03A3'); i < text.length(); ) {
            int code = text.codePointAt(i);
            i += Character.charCount(code);
            if (isCaseIgnorable(code)) {
                continue;
            }
            return !isCased(code);
        }
        return true;
    }

    /** Unicode's {@code Cased} property: the three cased categories and the other-cased letters. */
    private static boolean isCased(int code) {
        return Character.isLowerCase(code)
                || Character.isUpperCase(code)
                || Character.isTitleCase(code);
    }

    /**
     * Unicode's {@code Case_Ignorable} property: marks, format characters, modifier letters and
     * modifier symbols, plus the punctuation UAX #29 files under {@code MidLetter},
     * {@code MidNumLet} and {@code Single_Quote}.
     */
    private static boolean isCaseIgnorable(int code) {
        int type = Character.getType(code);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT
                || type == Character.MODIFIER_LETTER
                || type == Character.MODIFIER_SYMBOL) {
            return true;
        }
        return switch (code) {
            case 0x0027, 0x002E, 0x003A, 0x00B7, 0x0387, 0x05F4, 0x2018, 0x2019,
                 0x2024, 0x2027, 0xFE13, 0xFE52, 0xFE55, 0xFF07, 0xFF0E, 0xFF1A -> true;
            default -> false;
        };
    }

    /**
     * {@code istitle}: an upper or a title may only follow an uncased character, a lower only a cased
     * one — CPython's {@code unicode_istitle_impl} with its two flags, and not a reading of what
     * {@code title()} would have made of the same run.
     */
    private static boolean isTitle(String text) {
        boolean seen = false;
        boolean previousCased = false;
        for (int at = 0; at < text.length(); ) {
            int code = text.codePointAt(at);
            at += Character.charCount(code);
            if (Character.isUpperCase(code) || Character.isTitleCase(code)) {
                if (previousCased) {
                    return false;
                }
                previousCased = true;
                seen = true;
            } else if (Character.isLowerCase(code)) {
                if (!previousCased) {
                    return false;
                }
                previousCased = true;
                seen = true;
            } else {
                previousCased = false;
            }
        }
        return seen;
    }

    /**
     * {@code isidentifier}: the Unicode category rules Java keeps, which agree with Python's
     * {@code XID_Start} and {@code XID_Continue} for the scripts that matter here and drift for a
     * handful of code points that are letters in one table and numbers in the other.
     *
     * <p>Java also counts every ISO control character as an ignorable part of an identifier, which
     * Python does not: {@code 'a\0b'.isidentifier()} is false there, and {@code __slots__ = ["a\0b"]}
     * is the case that reads the answer.
     */
    static boolean isIdentifier(String text) {
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
            if (code != '_'
                    && (Character.isISOControl(code) || !Character.isUnicodeIdentifierPart(code))) {
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
        if (from >= to) {
            return 0;
        }
        int fromUnit = charIndex(from);
        int toUnit = to >= codePoints ? value.length() : charIndex(to);
        int found = FastSearch.count(value, fromUnit, toUnit - fromUnit, sub, Long.MAX_VALUE);
        return Math.max(found, 0);
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
        if (from >= to) {
            return -1;
        }
        int fromUnit = charIndex(from);
        int toUnit = to >= codePoints ? value.length() : charIndex(to);
        int at = FastSearch.find(value, fromUnit, toUnit - fromUnit, sub, right);
        return at < 0 ? -1 : from + value.codePointCount(fromUnit, at);
    }

    /** {@code partition} and {@code rpartition}: before, separator, after. */
    private List<String> divide(String separator, boolean left) {
        if (separator.isEmpty()) {
            throw Slots.valueError("empty separator");
        }
        int at = FastSearch.find(value, 0, value.length(), separator, !left);
        if (at < 0) {
            // A miss puts the whole value on the side the method reads from: `partition` before the
            // separator it could not find, `rpartition` after it.
            return left ? List.of(value, "", "") : List.of("", "", value);
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

    private String replaced(String old, String replacement, long count) {
        if (count == 0) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        if (old.isEmpty()) {
            // The empty pattern matches at every boundary, the last one included, and the first
            // replacement is not one the loop could have made.
            long remaining = count < 0 ? Long.MAX_VALUE : count;
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
        long remaining = count < 0 ? Long.MAX_VALUE : count;
        int at = 0;
        while (remaining > 0) {
            int found = FastSearch.find(value, at, value.length() - at, old, false);
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

    private List<String> pieces(String separator, long maxsplit, boolean right) {
        if (separator != null && separator.isEmpty()) {
            throw Slots.valueError("empty separator");
        }
        if (separator == null) {
            return whitespacePieces(maxsplit, right);
        }
        long cuts = maxsplit < 0 ? Long.MAX_VALUE : maxsplit;
        List<String> out = new ArrayList<>();
        if (right) {
            int end = value.length();
            while (cuts > 0) {
                int at = FastSearch.find(value, 0, end, separator, true);
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
            int found = FastSearch.find(value, at, value.length() - at, separator, false);
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
    private List<String> whitespacePieces(long maxsplit, boolean right) {
        long cuts = maxsplit < 0 ? Long.MAX_VALUE : maxsplit;
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

        /** One parsed field: its name text, its conversion, and its specification pieces. */
        private static final class Field {
            String name;
            char conversion;
            /** Literal runs and nested {@link Field}s, or null when the field has no {@code :}. */
            List<Object> spec;
        }

        private final String text;
        private final PyObject[] positional;
        private final String[] names;
        private final PyObject[] values;
        /** The mapping {@code format_map} resolves names through, or null for {@code format}. */
        private final PyObject mapping;
        private int cursor;
        private int automatic;
        private boolean manual;
        private boolean auto;

        Format(String text, PyObject[] args, String[] kwNames) {
            this.text = text;
            this.mapping = null;
            int keywords = kwNames == null ? 0 : kwNames.length;
            this.positional = new PyObject[args.length - keywords];
            System.arraycopy(args, 0, positional, 0, positional.length);
            this.names = kwNames == null ? new String[0] : kwNames;
            this.values = new PyObject[keywords];
            System.arraycopy(args, args.length - keywords, values, 0, keywords);
        }

        /**
         * {@code format_map}'s form: there are no positional arguments at all, and every name is a key
         * of the one mapping. A positional field is refused outright rather than looked up.
         */
        Format(String text, PyObject mapping) {
            this.text = text;
            this.mapping = mapping;
            this.positional = new PyObject[0];
            this.names = new String[0];
            this.values = new PyObject[0];
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
            if (cursor >= text.length()) {
                throw Slots.valueError("Single '{' encountered in format string");
            }
            out.append(renderField(parseField(0, false)));
        }

        /**
         * Read one field without looking anything up: its name text, conversion and specification.
         *
         * <p>CPython parses the whole field before it resolves the name, which is why {@code '{a'}
         * complains about the missing brace rather than about the missing keyword, and why a
         * malformed specification is reported before the value it would format.
         */
        private Field parseField(int depth, boolean inSpec) {
            Field field = new Field();
            field.name = fieldName(inSpec);
            while (cursor < text.length()) {
                char code = text.charAt(cursor);
                if (code == '!') {
                    field.conversion = conversion();
                    continue;
                }
                if (code == ':') {
                    field.spec = specification(depth);
                    continue;
                }
                if (code == '}') {
                    cursor++;
                    return field;
                }
                throw Slots.valueError("unexpected '{' in field name");
            }
            throw Slots.valueError(inSpec
                    ? "unmatched '{' in format spec"
                    : "expected '}' before end of string");
        }

        /**
         * The name text, up to the first {@code !}, {@code :} or {@code }} outside brackets.
         *
         * <p>A {@code ]} outside brackets is an ordinary name character — {@code '{a]}'} asks for
         * the key {@code 'a]'} — and a bracket that never closes leaves the field unterminated.
         */
        private String fieldName(boolean inSpec) {
            int start = cursor;
            int brackets = 0;
            while (cursor < text.length()) {
                char code = text.charAt(cursor);
                if (code == '[') {
                    brackets++;
                } else if (code == ']') {
                    if (brackets > 0) {
                        brackets--;
                    }
                } else if (brackets == 0) {
                    if (code == '!' || code == ':' || code == '}') {
                        break;
                    }
                    if (code == '{') {
                        throw Slots.valueError("unexpected '{' in field name");
                    }
                }
                cursor++;
            }
            if (cursor >= text.length() || brackets > 0) {
                throw Slots.valueError(inSpec
                        ? "unmatched '{' in format spec"
                        : "expected '}' before end of string");
            }
            return text.substring(start, cursor);
        }

        /** A parsed field's value, converted and formatted. */
        private String renderField(Field field) {
            PyObject value = resolve(field.name);
            // The conversion character is judged only after the name has been resolved, so a missing
            // keyword is reported as missing rather than as a bad conversion.
            if (field.conversion != 0
                    && field.conversion != 's' && field.conversion != 'r' && field.conversion != 'a') {
                throw Slots.valueError("Unknown conversion specifier %s", spelled(field.conversion));
            }
            // The conversion happens first, so a specification is applied to the text it produced, and
            // the type's own __format__ is what applies it, exactly as format() does.
            PyObject shown = field.conversion == 0
                    ? value
                    : PyStr.of(present(value, field.conversion));
            return ((PyStr) Formats.formatValue(shown, specText(field.spec))).getValue();
        }

        /**
         * How CPython prints the rejected conversion character: the character itself when it is
         * printable ASCII, and a bare {@code \x} escape otherwise, so a space is {@code \x20}.
         */
        private static String spelled(char code) {
            if (code > 0x20 && code <= 0x7E) {
                return String.valueOf(code);
            }
            return "\\x" + Integer.toHexString(code);
        }

        /** The specification with any fields inside it filled in first. */
        private String specText(List<Object> pieces) {
            if (pieces == null) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            for (Object piece : pieces) {
                if (piece instanceof String literal) {
                    out.append(literal);
                } else {
                    out.append(renderField((Field) piece));
                }
            }
            return out.toString();
        }

        /**
         * The field's value: its base name resolved to an argument, then every {@code .attr} and
         * {@code [key]} step applied in order.
         */
        private PyObject resolve(String name) {
            int at = 0;
            while (at < name.length() && name.charAt(at) != '.' && name.charAt(at) != '[') {
                at++;
            }
            PyObject value = base(name.substring(0, at));
            while (at < name.length()) {
                char marker = name.charAt(at++);
                int start = at;
                if (marker == '.') {
                    // `]` is an ordinary character in an attribute name; only the grammar's own
                    // markers end it.
                    while (at < name.length()
                            && name.charAt(at) != '.' && name.charAt(at) != '[') {
                        at++;
                    }
                    String step = name.substring(start, at);
                    if (step.isEmpty()) {
                        throw Slots.valueError("Empty attribute in format string");
                    }
                    value = Slots.getAttr(value, step);
                    continue;
                }
                while (at < name.length() && name.charAt(at) != ']') {
                    at++;
                }
                String step = name.substring(start, at);
                if (step.isEmpty()) {
                    throw Slots.valueError("Empty attribute in format string");
                }
                if (at < name.length()) {
                    at++;
                }
                value = Slots.getItem(value, indexKey(step));
                // The step is applied before the text after it is judged, so an int base reports its
                // own failure first: `'{a[0]x}'` with `a=2` is the subscript that fails.
                if (at < name.length() && name.charAt(at) != '.' && name.charAt(at) != '[') {
                    throw Slots.valueError(
                            "Only '.' or '[' may follow ']' in format field specifier");
                }
            }
            return value;
        }

        /** The name before any step: a positional index, a keyword, or nothing, which means the next one. */
        private PyObject base(String name) {
            if (mapping != null) {
                if (name.isEmpty() || digits(name) != null) {
                    throw Slots.valueError("Format string contains positional fields");
                }
                return Slots.getItem(mapping, PyStr.of(name));
            }
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

        /**
         * The conversion after {@code !}: one character, followed by {@code :} or {@code }}.
         *
         * <p>Only the shape is checked here; whether the character names a conversion is decided
         * after the name has been resolved, as CPython does. Running out of text right after the
         * {@code !} is the only case that says so; running out after the conversion character leaves
         * the field's specification unterminated.
         */
        private char conversion() {
            cursor++;
            if (cursor >= text.length()) {
                throw Slots.valueError("end of string while looking for conversion specifier");
            }
            char code = text.charAt(cursor++);
            if (cursor >= text.length()) {
                throw Slots.valueError("unmatched '{' in format spec");
            }
            char next = text.charAt(cursor);
            if (next != ':' && next != '}') {
                throw Slots.valueError("expected ':' after conversion specifier");
            }
            return code;
        }

        /**
         * The specification after {@code :}, as a list of literal runs and nested fields — CPython
         * parses the whole specification, and only then fills the fields in.
         *
         * <p>The closing brace is left in place, so the caller's own loop consumes it; that is what
         * makes the second {@code }} of {@code "{a:{b}}"} belong to the outer field rather than the
         * inner one. One level of nesting is all the grammar allows.
         */
        private List<Object> specification(int depth) {
            if (depth >= 1) {
                throw Slots.valueError("Max string recursion exceeded");
            }
            cursor++;
            List<Object> pieces = new ArrayList<>();
            StringBuilder literal = new StringBuilder();
            while (true) {
                if (cursor >= text.length()) {
                    throw Slots.valueError("unmatched '{' in format spec");
                }
                char code = text.charAt(cursor);
                if (code == '}') {
                    break;
                }
                if (code == '{') {
                    cursor++;
                    if (cursor >= text.length()) {
                        throw Slots.valueError("unmatched '{' in format spec");
                    }
                    if (literal.length() > 0) {
                        pieces.add(literal.toString());
                        literal.setLength(0);
                    }
                    pieces.add(parseField(depth + 1, true));
                    continue;
                }
                literal.append(code);
                cursor++;
            }
            if (literal.length() > 0) {
                pieces.add(literal.toString());
            }
            return pieces;
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
