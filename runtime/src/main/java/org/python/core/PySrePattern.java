package org.python.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * {@code re.Pattern}: one compiled expression, as {@code _sre.compile} builds it.
 *
 * <p>The engine is {@link java.util.regex}; {@link SreRegex} translates the Python syntax into the
 * Java one and maps the flags, and this class carries the Python-visible bookkeeping: the source text,
 * the flag word, the group count and the name-to-number table. A {@link Matcher} is made per call
 * rather than kept, because a pattern is immutable but a search is not.
 */
final class PySrePattern extends PyObject {

    /** The length at which {@code repr} cuts a match's text, in code points. */
    static final int REPR_LIMIT = 50;

    /** The flags {@code repr} names, in the order the reference writes them. */
    private static final int[] REPR_FLAGS = {
        SreRegex.IGNORECASE, SreRegex.LOCALE, SreRegex.MULTILINE,
        SreRegex.DOTALL, SreRegex.DEBUG, SreRegex.ASCII
    };

    private static final String[] REPR_NAMES = {
        "re.IGNORECASE", "re.LOCALE", "re.MULTILINE",
        "re.DOTALL", "re.DEBUG", "re.ASCII"
    };

    private static final Members METHODS = Members.of(PyType.SRE_PATTERN)
            .addField("pattern", "The pattern string from which the pattern object was compiled.",
                    self -> ((PySrePattern) self).sourceObject)
            .addField("flags", "The flags the pattern was compiled with.",
                    self -> PyInt.of(((PySrePattern) self).flags))
            .addField("groups", "The number of capturing groups in the pattern.",
                    self -> PyInt.of(((PySrePattern) self).groups))
            .addField("groupindex", "A dictionary mapping group names to group numbers.",
                    self -> ((PySrePattern) self).groupindexProxy)
            .add("match", "match(string[, pos[, endpos]]) -> Match or None",
                    (self, args, kwNames) -> ((PySrePattern) self).match(args, kwNames, 0))
            .add("fullmatch", "fullmatch(string[, pos[, endpos]]) -> Match or None",
                    (self, args, kwNames) -> ((PySrePattern) self).match(args, kwNames, 1))
            .add("search", "search(string[, pos[, endpos]]) -> Match or None",
                    (self, args, kwNames) -> ((PySrePattern) self).match(args, kwNames, 2))
            .add("findall", "findall(string[, pos[, endpos]]) -> list",
                    (self, args, kwNames) -> ((PySrePattern) self).findall(args, kwNames))
            .add("finditer", "finditer(string[, pos[, endpos]]) -> iterator",
                    (self, args, kwNames) -> ((PySrePattern) self).finditer(args, kwNames))
            .add("sub", "sub(repl, string, count=0) -> str",
                    (self, args, kwNames) -> ((PySrePattern) self).sub(args, kwNames, false))
            .add("subn", "subn(repl, string, count=0) -> tuple",
                    (self, args, kwNames) -> ((PySrePattern) self).sub(args, kwNames, true))
            .add("split", "split(string, maxsplit=0) -> list",
                    (self, args, kwNames) -> ((PySrePattern) self).split(args, kwNames))
            .addNoArgument("__copy__", self -> self)
            .addOne("__deepcopy__", (self, memo) -> self);

    /** The pattern as Python wrote it: a {@link PyStr}, or the {@link PyBytes} of a bytes pattern. */
    private final PyObject sourceObject;

    /** The pattern as the engine reads it, one character per byte for a bytes pattern. */
    private final String source;

    /** Whether the pattern is bytes, which decides what subjects and groups come back as. */
    private final boolean bytes;

    /** The flag word {@code _sre.compile} was given, UNICODE included for a str pattern. */
    private final int flags;

    /** The number of capturing groups. */
    private final int groups;

    /** Names in the order the pattern declares them. */
    private final LinkedHashMap<String, Integer> names;

    /** The same table as a read-only Python mapping. */
    private final PyObject groupindexProxy;

    /** The Java pattern the source was translated into. */
    private final java.util.regex.Pattern compiled;

    PySrePattern(PyObject sourceObject, String source, boolean bytes, int flags, int groups,
            Map<String, Integer> names) {
        super(PyType.SRE_PATTERN);
        this.sourceObject = sourceObject;
        this.source = source;
        this.bytes = bytes;
        this.flags = flags;
        this.groups = groups;
        this.names = new LinkedHashMap<>(names);
        PyDict index = PyDict.empty();
        for (Map.Entry<String, Integer> entry : this.names.entrySet()) {
            index.put(PyStr.interned(entry.getKey()), PyInt.of(entry.getValue()));
        }
        this.groupindexProxy = PyMappingProxy.of(index);
        this.compiled = SreRegex.compile(source, flags, bytes, this.names);
    }

    /** Whether this pattern was written as bytes. */
    boolean isBytes() {
        return bytes;
    }

    int groups() {
        return groups;
    }

    /** The number a group name stands for, or null when the pattern has no such name. */
    PyObject groupNumber(String name) {
        Integer number = names.get(name);
        return number == null ? null : PyInt.of(number);
    }

    /** The first name filed for a group number, or {@code None}. */
    PyObject groupName(int number) {
        for (Map.Entry<String, Integer> entry : names.entrySet()) {
            if (entry.getValue() == number) {
                return PyStr.interned(entry.getKey());
            }
        }
        return PyNone.INSTANCE;
    }

    Iterable<String> groupNames() {
        return names.keySet();
    }

    /**
     * A matcher whose input is the subject cut at {@code endpos} and whose region starts at
     * {@code pos}.
     *
     * <p>The cut is what makes {@code $} and {@code \Z} see {@code endpos} as the end of the string,
     * as CPython's engine does. The region alone would not: anchoring bounds are turned off so that
     * {@code ^} still means the real beginning or a line start, and transparent bounds are turned on
     * so that a lookbehind at {@code pos} can see the character before it.
     */
    private Matcher bound(String text, int pos, int endpos) {
        String subject = text.substring(0, text.offsetByCodePoints(0, endpos));
        Matcher matcher = compiled.matcher(subject);
        matcher.region(text.offsetByCodePoints(0, pos), subject.length());
        matcher.useAnchoringBounds(false);
        matcher.useTransparentBounds(true);
        return matcher;
    }

    private static int clamp(BigInteger value, int length) {
        if (value.signum() <= 0) {
            return 0;
        }
        if (value.compareTo(BigInteger.valueOf(length)) >= 0) {
            return length;
        }
        return value.intValue();
    }

    /**
     * The subject as a Java string, one character per octet for a bytes pattern.
     *
     * <p>{@code str} and bytes are not interchangeable here, and the reference says so: a str pattern
     * refuses a bytes subject and a bytes pattern refuses a str one.
     */
    private String subject(PyObject value) {
        if (bytes) {
            if (value instanceof PyStr) {
                throw Slots.typeError("cannot use a bytes pattern on a string-like object");
            }
            if (value instanceof PyBytes raw) {
                return new String(raw.getValue(), StandardCharsets.ISO_8859_1);
            }
            // A bytes pattern asks the subject for a buffer, so every exporter is a subject:
            // bytearray, array and memoryview included.
            if (value instanceof PyByteArray || value instanceof PyArray
                    || value instanceof PyMemoryView) {
                return new String(PyMemoryView.bufferOctets(value), StandardCharsets.ISO_8859_1);
            }
        } else if (value instanceof PyStr text) {
            return text.getValue();
        } else if (value instanceof PyBytes || value instanceof PyByteArray
                || value instanceof PyArray || value instanceof PyMemoryView) {
            throw Slots.typeError("cannot use a string pattern on a bytes-like object");
        }
        throw Slots.typeError("expected string or bytes-like object, got '%s'",
                value.getType().filedName());
    }

    /** One matched piece of text as the pattern's own type: bytes for a bytes pattern. */
    PyObject text(String value) {
        return bytes
                ? PyBytes.of(value.getBytes(StandardCharsets.ISO_8859_1))
                : PyStr.of(value);
    }

    /** The three anchored forms: {@code 0} is {@code match}, {@code 1} {@code fullmatch}, {@code 2} {@code search}. */
    private PyObject match(PyObject[] args, String[] kwNames, int kind) {
        String plain = kind == 0 ? "match" : kind == 1 ? "fullmatch" : "search";
        Arguments.between(plain, args, kwNames, 1, 3);
        String text = subject(args[0]);
        int length = text.codePointCount(0, text.length());
        int pos = args.length > 1 ? clamp(Numbers.asIndex(args[1]), length) : 0;
        int endpos = args.length > 2 ? clamp(Numbers.asIndex(args[2]), length) : length;
        if (endpos < pos) {
            return PyNone.INSTANCE;
        }
        Matcher matcher = bound(text, pos, endpos);
        boolean found = switch (kind) {
            case 0 -> matcher.lookingAt();
            case 1 -> matcher.matches();
            default -> matcher.find();
        };
        return found ? PySreMatch.of(this, text, pos, endpos, matcher) : PyNone.INSTANCE;
    }

    private PyObject findall(PyObject[] args, String[] kwNames) {
        Arguments.between("findall", args, kwNames, 1, 3);
        String text = subject(args[0]);
        int length = text.codePointCount(0, text.length());
        int pos = args.length > 1 ? clamp(Numbers.asIndex(args[1]), length) : 0;
        int endpos = args.length > 2 ? clamp(Numbers.asIndex(args[2]), length) : length;
        PyList out = PyList.empty();
        if (endpos < pos) {
            return out;
        }
        Matcher matcher = bound(text, pos, endpos);
        while (matcher.find()) {
            if (groups == 0) {
                out.add(text(text.substring(matcher.start(), matcher.end())));
            } else if (groups == 1) {
                out.add(findallText(text, matcher, 1));
            } else {
                PyObject[] row = new PyObject[groups];
                for (int i = 1; i <= groups; i++) {
                    row[i - 1] = findallText(text, matcher, i);
                }
                out.add(PyTuple.of(row));
            }
        }
        return out;
    }

    /**
     * One group's text for {@code findall}, which answers the empty string for a group that did not
     * take part — the one place a group reads as {@code ''} rather than {@code None}.
     */
    private PyObject findallText(String text, Matcher matcher, int group) {
        int start = matcher.start(SreRegex.groupName(group));
        if (start < 0) {
            return text("");
        }
        return text(text.substring(start, matcher.end(SreRegex.groupName(group))));
    }

    private PyObject groupText(String text, Matcher matcher, int group) {
        int start = matcher.start(SreRegex.groupName(group));
        if (start < 0) {
            return PyNone.INSTANCE;
        }
        return text(text.substring(start, matcher.end(SreRegex.groupName(group))));
    }

    private PyObject finditer(PyObject[] args, String[] kwNames) {
        Arguments.between("finditer", args, kwNames, 1, 3);
        String text = subject(args[0]);
        int length = text.codePointCount(0, text.length());
        int pos = args.length > 1 ? clamp(Numbers.asIndex(args[1]), length) : 0;
        int endpos = args.length > 2 ? clamp(Numbers.asIndex(args[2]), length) : length;
        List<PyObject> out = new ArrayList<>();
        if (endpos >= pos) {
            Matcher matcher = bound(text, pos, endpos);
            while (matcher.find()) {
                out.add(PySreMatch.of(this, text, pos, endpos, matcher));
            }
        }
        return PyIterator.of(out);
    }

    private PyObject sub(PyObject[] args, String[] kwNames, boolean wantCount) {
        String plain = wantCount ? "subn" : "sub";
        Arguments.between(plain, args, kwNames, 2, 3);
        PyObject replacement = args[0];
        String text = subject(args[1]);
        int limit = args.length > 2 ? Numbers.asIndex(args[2]).intValue() : 0;
        int length = text.codePointCount(0, text.length());
        PyObject parsed = null;
        if (replacement instanceof PyStr || replacement instanceof PyBytes) {
            parsed = template(replacement, this);
        }
        Matcher matcher = bound(text, 0, length);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        int done = 0;
        while ((limit <= 0 || done < limit) && matcher.find()) {
            out.append(text, last, matcher.start());
            if (parsed != null) {
                expandInto(out, parsed, PySreMatch.of(this, text, 0, length, matcher));
            } else {
                PySreMatch match = PySreMatch.of(this, text, 0, length, matcher);
                PyObject value = Slots.call(replacement, new PyObject[] {match}, null);
                if (bytes) {
                    if (!(value instanceof PyBytes part)) {
                        throw Slots.typeError(
                                "sequence item 0: expected a bytes-like object, %s found",
                                Slots.argumentName(value));
                    }
                    out.append(new String(part.getValue(), StandardCharsets.ISO_8859_1));
                } else {
                    if (!(value instanceof PyStr part)) {
                        throw Slots.typeError("sequence item 0: expected str instance, %s found",
                                Slots.argumentName(value));
                    }
                    out.append(part.getValue());
                }
            }
            last = matcher.end();
            done++;
        }
        out.append(text, last, text.length());
        PyObject result = text(out.toString());
        return wantCount ? PyTuple.of(result, PyInt.of(done)) : result;
    }

    private PyObject split(PyObject[] args, String[] kwNames) {
        Arguments.between("split", args, kwNames, 1, 2);
        String text = subject(args[0]);
        int maxsplit = args.length > 1 ? Numbers.asIndex(args[1]).intValue() : 0;
        int length = text.codePointCount(0, text.length());
        Matcher matcher = bound(text, 0, length);
        PyList out = PyList.empty();
        int last = 0;
        int done = 0;
        while ((maxsplit <= 0 || done < maxsplit) && matcher.find()) {
            out.add(text(text.substring(last, matcher.start())));
            for (int i = 1; i <= groups; i++) {
                out.add(groupText(text, matcher, i));
            }
            last = matcher.end();
            done++;
        }
        out.add(text(text.substring(last)));
        return out;
    }

    /**
     * {@code re._parser.parse_template}, the reference's own reader of a replacement string.
     *
     * <p>Reusing it is what gives {@code sub} and {@code expand} the same template grammar and the
     * same complaints, including the index of the offending backslash.
     */
    static PyObject template(PyObject template, PySrePattern pattern) {
        PyObject parser = PyRuntime.importModule("re._parser");
        PyObject reader = parser instanceof PyModule module ? module.get("parse_template") : null;
        if (reader == null) {
            throw Slots.runtimeError("re._parser.parse_template is not available");
        }
        return Slots.call(reader, new PyObject[] {template, pattern}, null);
    }

    /** Writes one already-parsed replacement for {@code match} into {@code out}. */
    static void expandInto(StringBuilder out, PyObject parsed, PySreMatch match) {
        if (!(parsed instanceof PyList parts)) {
            return;
        }
        for (int i = 0; i < parts.size(); i++) {
            PyObject part = parts.at(i);
            if (part instanceof PyStr text) {
                out.append(text.getValue());
            } else if (part instanceof PyBytes raw) {
                out.append(new String(raw.getValue(), StandardCharsets.ISO_8859_1));
            } else {
                PyObject value = match.groupAt(Numbers.asIndex(part).intValue());
                if (value instanceof PyStr text) {
                    out.append(text.getValue());
                } else if (value instanceof PyBytes raw) {
                    out.append(new String(raw.getValue(), StandardCharsets.ISO_8859_1));
                }
            }
        }
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    /** {@code re.compile('a')} or {@code re.compile('a', re.IGNORECASE|re.MULTILINE)}. */
    @Override
    public String repr() {
        StringBuilder out = new StringBuilder("re.compile(");
        out.append(sourceObject.repr());
        int shown = flags & ~SreRegex.UNICODE;
        List<String> named = new ArrayList<>();
        for (int i = 0; i < REPR_FLAGS.length; i++) {
            if ((shown & REPR_FLAGS[i]) != 0) {
                named.add(REPR_NAMES[i]);
            }
        }
        if (!named.isEmpty()) {
            out.append(", ").append(String.join("|", named));
        }
        return out.append(')').toString();
    }
}
