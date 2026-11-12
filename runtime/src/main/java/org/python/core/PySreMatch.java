package org.python.core;

import java.math.BigInteger;
import java.util.regex.Matcher;

/**
 * {@code re.Match}: one successful match, as {@code _sre.compile}'s pattern reports it.
 *
 * <p>The engine underneath is {@link java.util.regex}, so a match is a copied snapshot of the
 * {@link Matcher}'s group table rather than a live cursor: the offsets are converted to code-point
 * indices once, and every read afterwards is a substring of {@link #string}. CPython holds the same
 * shape — a subject, the two bounds, and an array of start/end pairs — which is why the attributes
 * below are read from fields instead of being recomputed.
 */
final class PySreMatch extends PyObject {

    private static final Members METHODS = Members.of(PyType.SRE_MATCH)
            .addField("re", "The pattern object whose match this is.",
                    self -> ((PySreMatch) self).pattern)
            .addField("string", "The string the match was found in.",
                    self -> PyStr.of(((PySreMatch) self).string))
            .addField("pos", "The position the search started at.",
                    self -> PyInt.of(((PySreMatch) self).pos))
            .addField("endpos", "The position the search ended at.",
                    self -> PyInt.of(((PySreMatch) self).endpos))
            .addField("regs", "The start and end of every group, as a tuple.",
                    self -> ((PySreMatch) self).regsTuple())
            .addField("lastindex", "The number of the last matched group, or None.",
                    self -> ((PySreMatch) self).lastindex)
            .addField("lastgroup", "The name of the last matched group, or None.",
                    self -> ((PySreMatch) self).lastgroup)
            .add("group", "group([group1, ...]) -> str, tuple or None",
                    (self, args, kwNames) -> ((PySreMatch) self).group(args, kwNames))
            .add("groups", "groups(default=None) -> tuple",
                    (self, args, kwNames) -> ((PySreMatch) self).groups(args, kwNames))
            .add("groupdict", "groupdict(default=None) -> dict",
                    (self, args, kwNames) -> ((PySreMatch) self).groupdict(args, kwNames))
            .add("start", "start([group]) -> int",
                    (self, args, kwNames) -> ((PySreMatch) self).start(args, kwNames))
            .add("end", "end([group]) -> int",
                    (self, args, kwNames) -> ((PySreMatch) self).end(args, kwNames))
            .add("span", "span([group]) -> tuple",
                    (self, args, kwNames) -> ((PySreMatch) self).span(args, kwNames))
            .add("expand", "expand(template) -> str",
                    (self, args, kwNames) -> ((PySreMatch) self).expand(args, kwNames))
            .addNoArgument("__copy__", self -> self)
            .addOne("__deepcopy__", (self, memo) -> self);

    /** The pattern this match belongs to. */
    private final PySrePattern pattern;

    /** The whole subject, which every group's text is cut out of. */
    private final String string;

    /** The clamped search window, reported as {@code pos} and {@code endpos}. */
    private final int pos;

    private final int endpos;

    /** Two code-point offsets per group, {@code -1} for one that did not take part. */
    private final int[] regs;

    /** The highest group that matched, or {@code None}. */
    private final PyObject lastindex;

    /** The name of that group when it has one, or {@code None}. */
    private final PyObject lastgroup;

    private PySreMatch(PySrePattern pattern, String string, int pos, int endpos, int[] regs) {
        super(PyType.SRE_MATCH);
        this.pattern = pattern;
        this.string = string;
        this.pos = pos;
        this.endpos = endpos;
        this.regs = regs;
        int last = 0;
        for (int i = 1; i <= pattern.groups(); i++) {
            if (regs[2 * i] >= 0) {
                last = i;
            }
        }
        this.lastindex = last == 0 ? PyNone.INSTANCE : PyInt.of(last);
        this.lastgroup = last == 0
                ? PyNone.INSTANCE
                : pattern.groupName(last);
    }

    /**
     * The match {@code matcher} currently holds.
     *
     * <p>The matcher's input is the subject cut at {@code endpos}, so its UTF-16 offsets are also
     * offsets into the whole string; they are converted to code points because that is what Python
     * indexes by.
     */
    static PySreMatch of(PySrePattern pattern, String string, int pos, int endpos, Matcher matcher) {
        int groups = pattern.groups();
        int[] regs = new int[2 * (groups + 1)];
        int available = matcher.groupCount();
        for (int i = 0; i <= groups; i++) {
            if (i > available) {
                regs[2 * i] = -1;
                regs[2 * i + 1] = -1;
                continue;
            }
            int start = matcher.start(i);
            int end = matcher.end(i);
            regs[2 * i] = start < 0 ? -1 : string.codePointCount(0, start);
            regs[2 * i + 1] = end < 0 ? -1 : string.codePointCount(0, end);
        }
        return new PySreMatch(pattern, string, pos, endpos, regs);
    }

    /** {@code group()} at one index, or {@code None} for a group that did not take part. */
    PyObject groupAt(int index) {
        int start = regs[2 * index];
        int end = regs[2 * index + 1];
        if (start < 0) {
            return PyNone.INSTANCE;
        }
        return PyStr.of(string.substring(offset(start), offset(end)));
    }

    private int offset(int codePoints) {
        return string.offsetByCodePoints(0, codePoints);
    }

    /** The group index a Python argument names, with the reference's refusal for one it does not. */
    private int resolve(PyObject group) {
        if (group instanceof PyStr name) {
            PyObject number = pattern.groupNumber(name.getValue());
            if (number == null) {
                throw Slots.indexError("no such group");
            }
            return Numbers.asIndex(number).intValue();
        }
        BigInteger index = Numbers.asIndex(group);
        if (index.signum() < 0 || index.compareTo(BigInteger.valueOf(pattern.groups())) > 0) {
            throw Slots.indexError("no such group");
        }
        return index.intValue();
    }

    private PyObject group(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("match.group()", kwNames);
        if (args.length == 0) {
            return groupAt(0);
        }
        if (args.length == 1) {
            return groupAt(resolve(args[0]));
        }
        PyObject[] values = new PyObject[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = groupAt(resolve(args[i]));
        }
        return PyTuple.of(values);
    }

    private PyObject groups(PyObject[] args, String[] kwNames) {
        Arguments.atMost("groups", args, kwNames, 1);
        PyObject fallback = args.length == 0 ? PyNone.INSTANCE : args[0];
        PyObject[] values = new PyObject[pattern.groups()];
        for (int i = 1; i <= pattern.groups(); i++) {
            int start = regs[2 * i];
            values[i - 1] = start < 0 ? fallback : groupAt(i);
        }
        return PyTuple.of(values);
    }

    private PyObject groupdict(PyObject[] args, String[] kwNames) {
        Arguments.atMost("groupdict", args, kwNames, 1);
        PyObject fallback = args.length == 0 ? PyNone.INSTANCE : args[0];
        PyDict out = PyDict.empty();
        for (String name : pattern.groupNames()) {
            PyObject number = pattern.groupNumber(name);
            int index = Numbers.asIndex(number).intValue();
            int start = regs[2 * index];
            out.put(PyStr.interned(name), start < 0 ? fallback : groupAt(index));
        }
        return out;
    }

    private int indexArgument(String name, PyObject[] args, String[] kwNames) {
        Arguments.atMost(name, args, kwNames, 1);
        return args.length == 0 ? 0 : resolve(args[0]);
    }

    private PyObject start(PyObject[] args, String[] kwNames) {
        return PyInt.of(regs[2 * indexArgument("start", args, kwNames)]);
    }

    private PyObject end(PyObject[] args, String[] kwNames) {
        return PyInt.of(regs[2 * indexArgument("end", args, kwNames) + 1]);
    }

    private PyObject span(PyObject[] args, String[] kwNames) {
        int index = indexArgument("span", args, kwNames);
        return PyTuple.of(PyInt.of(regs[2 * index]), PyInt.of(regs[2 * index + 1]));
    }

    private PyObject expand(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("expand", args, kwNames);
        if (!(args[0] instanceof PyStr template)) {
            throw Slots.typeError("expected str instance, %s found",
                    Slots.argumentName(args[0]));
        }
        PyObject parsed = PySrePattern.template(template, pattern);
        StringBuilder out = new StringBuilder();
        PySrePattern.expandInto(out, parsed, this);
        return PyStr.of(out.toString());
    }

    private PyTuple regsTuple() {
        PyObject[] values = new PyObject[regs.length];
        for (int i = 0; i < regs.length; i++) {
            values[i] = PyInt.of(regs[i]);
        }
        return PyTuple.of(values);
    }

    @Override
    public PyObject getItem(PyObject key) {
        return groupAt(resolve(key));
    }

    @Override
    public PyObject truth() {
        return PyBool.TRUE;
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    /** {@code <re.Match object; span=(0, 1), match='a'>}. */
    @Override
    public String repr() {
        String text = string.substring(offset(regs[0]), offset(regs[1]));
        String head = String.format("<%s object; span=(%d, %d), match=",
                getType().filedName(), regs[0], regs[1]);
        if (text.codePointCount(0, text.length()) >= PySrePattern.REPR_LIMIT) {
            // The reference drops the closing quote with the tail it cuts off, so the `>` closes both.
            return head + "'"
                    + text.substring(0, text.offsetByCodePoints(0, PySrePattern.REPR_LIMIT - 1))
                    + ">";
        }
        return head + PyStr.of(text).repr() + ">";
    }
}
