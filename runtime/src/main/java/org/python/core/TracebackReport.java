package org.python.core;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The report a person reads when nothing catches an exception.
 *
 * <p>CPython builds this text twice over, and the two are not the same document. {@code
 * Python/traceback.c} prints the header, one {@code File "...", line N, in NAME} entry per traceback
 * link, and the source line read off disk; {@code Lib/traceback.py} does the same and adds, under the
 * failing expression, the {@code ^} and {@code ~} line PEP 657 calls an anchor. Since 3.13 the default
 * report goes through the Python one — {@code _PyErr_Display} imports {@code traceback} and calls its
 * {@code _print_exception_bltin}, and only falls back to the C printer when that fails. This class
 * transcribes the Python text, which for a code object that carries no column information — as every one
 * of them does under {@code -X no_debug_ranges} — is byte for byte what the C printer writes. The two
 * things that exist only in the Python printer are the exception group and the {@code Did you mean: 'x'?}
 * that {@link Suggestions} hangs on the end of a complaint, and both are here — the group because the C
 * fallback a person reaches by making {@code traceback} unimportable prints it as a plain complaint line
 * and nothing else, the sentence because it joins {@code _str} before the type name goes in front of it and
 * so belongs to the complaint line rather than standing under it.
 *
 * <p>The anchors are here too, read from the position table rather than from a syntax tree:
 * {@link TracebackSource} takes the {@code co_positions()} entry of the failing instruction, cuts the
 * source segment it names, and decides which characters get a {@code ~} by scanning that segment for a
 * binary operator or for the last call or subscript of a chain. CPython makes the same decision by
 * parsing the segment with {@code ast}, which this runtime does not have; a segment the scan cannot read
 * is shown without anchors, which is also what CPython does when {@code ast} refuses the segment.
 * Everything else the report says is here: the chain of causes and contexts, the collapse of a repeated
 * line, {@code sys.tracebacklimit}, the notes, the type name the complaint is filed under, and the tree
 * of a group down to the margin CPython puts in front of every line inside one.
 *
 * <p>Three readings follow CPython's code rather than a plausible shortcut. The limit keeps the
 * <em>last</em> N entries, because {@code BUILTIN_EXCEPTION_LIMIT} slices the walked traceback from the
 * end; a {@code sys.tracebacklimit} that is not a number is ignored, where CPython's comparison
 * raises inside the display and drops it to the C printer — which, unlike the Python one, cannot quote a
 * {@code -c} command at all; and the whole tree of a report is captured before a line of it is written,
 * because {@code _seen} marks an exception as shown when its node is <em>made</em> — so the second child
 * of a group whose cause is the first of them has no cause to report, which printing the walk as it goes
 * would have printed.
 */
public final class TracebackReport {

    /** {@code _RECURSIVE_CUTOFF}: how many identical entries each still get their own lines. */
    private static final int RECURSIVE_CUTOFF = 3;

    private static final String HEADER = "Traceback (most recent call last):\n";

    private static final String CAUSE_MESSAGE =
            "\nThe above exception was the direct cause of the following exception:\n\n";

    private static final String CONTEXT_MESSAGE =
            "\nDuring handling of the above exception, another exception occurred:\n\n";

    /** {@code _TRACEBACK_SOURCE_LINE_INDENT}, which is also what {@code textwrap.indent} is given. */
    private static final String SOURCE_INDENT = "    ";

    /** {@code max_group_width}: how many children of a group get a frame of their own before a count. */
    private static final int MAX_GROUP_WIDTH = 15;

    /** {@code max_group_depth}: how deep into a tree of groups the report goes before it gives up. */
    private static final int MAX_GROUP_DEPTH = 10;

    private static final String GROUP_HEADER = "Exception Group Traceback (most recent call last):\n";

    /** {@code '+-------'} and {@code '-------'}: the frame around a numbered child, and the one that ends all. */
    private static final String CHILD_FRAME = "+---------------- ";

    private static final String CHILD_END = " ----------------\n";

    private static final String CLOSER = "+------------------------------------\n";

    /**
     * Source compiled from a string rather than from a file, keyed as {@code linecache._register_code}
     * keys it: by the three names only the code object can repeat.
     *
     * <p>CPython needs this because a {@code -c} command's frames name {@code <string>} and no such file
     * exists; the launcher has the same problem and the same advantage, being the one that read it.
     */
    private static final Map<String, List<String>> inlineSources = new ConcurrentHashMap<>();

    private final StringBuilder out = new StringBuilder();

    /** Each file's lines, read once per report because a deep traceback names one file many times. */
    private final Map<String, List<String>> files = new ConcurrentHashMap<>();

    private final int limit;

    /**
     * Whether the report draws the PEP 657 anchors.
     *
     * <p>It does not when {@code sys.tracebacklimit} is set to something that is not a number: the Python
     * printer compares that value with zero, the comparison raises, and {@code _PyErr_Display} drops the
     * whole report to the C printer, which quotes one line per entry and draws nothing under it.
     */
    private final boolean anchors;

    private TracebackReport() {
        PyObject configured = PyRuntime.sysAttribute("tracebacklimit");
        limit = entryLimit(configured);
        anchors = configured == null || configured == PyNone.INSTANCE || limitValue(configured) != null;
    }

    /** The whole report of {@code error}, ending in a newline like every line of it does. */
    public static String of(PyBaseException error) {
        TracebackReport report = new TracebackReport();
        report.chain(error);
        return report.out.toString();
    }

    /** {@code of} written to {@code sys.stderr}, which is how an uncaught exception reaches a person. */
    public static void print(PyBaseException error) {
        String text = of(error);
        PyObject stream = PyRuntime.sysAttribute("stderr");
        if (stream == null || stream instanceof PyNone) {
            PyRuntime.stderr().writeText(text);
            return;
        }
        try {
            Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of(text)}, null);
        } catch (PyRaise ignored) {
            // `_PyErr_Display` asks `sys.stderr` and, where writing to it fails, falls back to the stream
            // the interpreter was started with rather than losing the report.
            PyRuntime.stderr().writeText(text);
        }
    }

    /**
     * Remember the text a code object was compiled from, so that its frames can quote it.
     *
     * <p>Nested code objects are registered too, as {@code _register_code} walks {@code co_consts},
     * because the frame that fails is usually a function inside the command rather than the command.
     */
    public static void registerSource(PyCode code, String source) {
        registerEach(code, List.of(source.split("\n", -1)));
    }

    private static void registerEach(PyCode code, List<String> lines) {
        inlineSources.put(keyOf(code), lines);
        PyTuple constants = code.constants();
        for (int i = 0; i < constants.size(); i++) {
            if (constants.at(i) instanceof PyCode nested) {
                registerEach(nested, lines);
            }
        }
    }

    /** {@code linecache._make_key}: the same file name can carry two commands with different first lines. */
    private static String keyOf(PyCode code) {
        return code.fileName() + "\u0000" + code.qualName() + "\u0000" + code.firstLine();
    }

    // --- the chain -----------------------------------------------------------------------

    /** {@code _ExceptionPrintContext}: how far into a tree of groups the report currently stands. */
    private static final class Context {

        /** {@code exception_group_depth}: one for each group the report is inside, zero above them all. */
        private int depth;

        /** Whether the block that is about to end still owes the reader its closing frame. */
        private boolean needClose;

        /** {@code indent()}: two spaces per group, which is nothing at all outside one. */
        private String indent() {
            return " ".repeat(2 * depth);
        }
    }

    /**
     * One exception the report shows, with the links the capture gave it.
     *
     * <p>The three fields are what {@code TracebackException} is asked for as it is printed, and they are
     * decided by the capture below rather than by the print, because {@code _seen} marks an id as shown
     * when its node is <em>made</em>: a group's children are all made before any of them is looked at, so
     * one whose cause is a sibling of it has no cause to report.
     */
    private static final class Node {

        private final PyBaseException value;
        private Node cause;
        private Node context;

        /** The children of a group, or null for anything else — the first question {@code format} asks. */
        private List<Node> children;

        Node(PyBaseException value) {
            this.value = value;
        }
    }

    /** A captured node and the exception it was made from, which is the pair the capture queue carries. */
    private record Link(Node node, PyBaseException value) {}

    /** The whole report of {@code error}: its tree captured once, then written oldest block first. */
    private void chain(PyBaseException error) {
        format(capture(error), new Context());
    }

    /**
     * {@code TracebackException.__init__}'s capture queue, which is a stack: {@code queue.pop()} takes the
     * last entry, so a group's children are looked at in reverse and every node settles its own links
     * before any of them is opened.
     *
     * <p>Causes and contexts are only captured when the exception has not been seen, which is what stops
     * {@code a.__cause__ = b; b.__cause__ = a} from never finishing. Children are captured unconditionally:
     * the same exception twice in one group is shown twice, because {@code BaseExceptionGroup} puts it in
     * {@code exceptions} and the capture does not ask whether the reader has met it.
     */
    private Node capture(PyBaseException raised) {
        Set<PyBaseException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Node top = taken(raised, seen);
        List<Link> queue = new ArrayList<>();
        queue.add(new Link(top, raised));
        while (!queue.isEmpty()) {
            Link item = queue.remove(queue.size() - 1);
            Node node = item.node();
            PyBaseException value = item.value();
            PyBaseException cause = chained(value, "__cause__");
            node.cause = cause != null && !seen.contains(cause) ? taken(cause, seen) : null;
            // compact=True: a context is only followed when there was no cause to print.
            PyBaseException context =
                    node.cause == null && !Slots.truth(value.getAttr("__suppress_context__"))
                            ? chained(value, "__context__")
                            : null;
            node.context =
                    context != null && !seen.contains(context) ? taken(context, seen) : null;
            if (value instanceof PyBaseExceptionGroup group) {
                PyObject[] children = group.children().getArray();
                node.children = new ArrayList<>(children.length);
                for (PyObject child : children) {
                    node.children.add(taken((PyBaseException) child, seen));
                }
            }
            if (node.cause != null) {
                queue.add(new Link(node.cause, cause));
            }
            if (node.context != null) {
                queue.add(new Link(node.context, context));
            }
            if (node.children != null) {
                PyObject[] children = ((PyBaseExceptionGroup) value).children().getArray();
                for (int i = 0; i < node.children.size(); i++) {
                    queue.add(new Link(node.children.get(i), (PyBaseException) children[i]));
                }
            }
        }
        return top;
    }

    /** A node for {@code value}, marking it seen as {@code _seen.add(id(exc_value))} marks the one it makes. */
    private static Node taken(PyBaseException value, Set<PyBaseException> seen) {
        seen.add(value);
        return new Node(value);
    }

    /** {@code e.__cause__} or {@code e.__context__} as an exception, or null where CPython has {@code None}. */
    private static PyBaseException chained(PyBaseException value, String name) {
        return value.getAttr(name) instanceof PyBaseException other ? other : null;
    }

    /**
     * {@code TracebackException.format}: the chain of one node, oldest block first, each with the sentence
     * that introduces it.
     *
     * <p>The sentence belongs to the block it comes before, not to the link it describes: {@code output}
     * collects {@code (message, exc)} pairs walking outward and prints them reversed, so what the reader
     * sees is the cause, then "during handling of the above exception", then the error it was handling.
     */
    private void format(Node node, Context ctx) {
        List<Node> values = new ArrayList<>();
        List<String> introductions = new ArrayList<>();
        Node at = node;
        while (at != null) {
            boolean follows = at.cause != null || at.context != null;
            introductions.add(follows ? at.cause != null ? CAUSE_MESSAGE : CONTEXT_MESSAGE : null);
            values.add(at);
            at = follows ? (at.cause != null ? at.cause : at.context) : null;
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            if (introductions.get(i) != null) {
                emit(introductions.get(i), ctx);
            }
            render(values.get(i), ctx);
        }
    }

    /**
     * One node's own block: its frames, its complaint, and then, for a group, one frame per child.
     *
     * <p>A group is what the depth limit is counted in, and the count is of the groups <em>above</em> this
     * one — {@code _ctx.exception_group_depth > max_group_depth} — so the eleventh is the one that reads
     * {@code ... (max_group_depth is 10)} instead of its own contents.
     *
     * <p>The only difference between a group's header and any other's is the character in the margin: the
     * outermost gets {@code +} and everything inside it, including a nested group's own header, gets {@code
     * |}. The {@code needClose} flag is why a group whose last child is itself a group is not given a
     * closing frame — the child took it, and the parent has none left to take.
     */
    private void render(Node node, Context ctx) {
        PyBaseException value = node.value;
        List<Entry> entries = entriesOf(value);
        // The suggestion is worked out from the frame the error ended in, which is the last link of the
        // whole traceback rather than of the sliced one: sys.tracebacklimit cuts what the reader is shown,
        // not what the chooser is given.
        Frame innermost = innermostFrameOf(value);
        if (node.children == null) {
            if (!entries.isEmpty()) {
                emit(HEADER, ctx);
                emit(frames(entries), ctx);
            }
            emit(only(value, innermost), ctx);
            return;
        }
        if (ctx.depth > MAX_GROUP_DEPTH) {
            emit("... (max_group_depth is " + MAX_GROUP_DEPTH + ")\n", ctx);
            return;
        }
        boolean toplevel = ctx.depth == 0;
        if (toplevel) {
            ctx.depth = 1;
        }
        if (!entries.isEmpty()) {
            emit(GROUP_HEADER, ctx, toplevel ? '+' : '|');
            emit(frames(entries), ctx);
        }
        emit(only(value, innermost), ctx);

        int total = node.children.size();
        int shown = total <= MAX_GROUP_WIDTH ? total : MAX_GROUP_WIDTH + 1;
        ctx.needClose = false;
        for (int i = 0; i < shown; i++) {
            boolean last = i == shown - 1;
            if (last) {
                ctx.needClose = true;
            }
            boolean truncated = i >= MAX_GROUP_WIDTH;
            out.append(ctx.indent())
                    .append(i == 0 ? "+-" : "  ")
                    .append(CHILD_FRAME)
                    .append(truncated ? "..." : Integer.toString(i + 1))
                    .append(CHILD_END);
            ctx.depth++;
            if (truncated) {
                int remaining = total - MAX_GROUP_WIDTH;
                emit(
                        "and " + remaining + " more exception" + (remaining > 1 ? "s" : "") + "\n",
                        ctx);
            } else {
                format(node.children.get(i), ctx);
            }
            if (last && ctx.needClose) {
                out.append(ctx.indent()).append(CLOSER);
                ctx.needClose = false;
            }
            ctx.depth--;
        }
        if (toplevel) {
            ctx.depth = 0;
        }
    }

    // --- the entries ---------------------------------------------------------------------

    /**
     * One traceback link as the report reads it: a file, the source range the frame stopped in, a
     * function, and the code to quote.
     *
     * <p>The four numbers are CPython's {@code (lineno, end_lineno, colno, end_colno)} for the failing
     * instruction, with -1 wherever the code object's position table names nothing. The report only needs
     * them to place the PEP 657 anchor under the right characters; the header line falls back to the line
     * table when the position table has no start line.
     */
    private record Entry(
            String fileName, int line, int endLine, int col, int endCol, String name, PyCode code) {}

    /**
     * The links of {@code value.__traceback__}, outermost first, cut to {@code sys.tracebacklimit}.
     *
     * <p>A line the code object's own ranges do not cover has no number, and the report says {@code
     * None} for it rather than inventing one — the same {@code tb_lineno} that reads {@code None}.
     */
    private List<Entry> entriesOf(PyBaseException value) {
        List<Entry> all = new ArrayList<>();
        PyObject carried = value.getAttr("__traceback__");
        while (carried instanceof PyTraceback traceback) {
            Frame frame = traceback.frame();
            PyCode code = frame.code();
            int[] position = positionOf(code, traceback.lasti());
            int line =
                    position[0] >= 0
                            ? position[0]
                            : frame.instructions().lineAtLasti(traceback.lasti());
            all.add(
                    new Entry(
                            code.fileName(),
                            line,
                            position[1] >= 0 ? position[1] : line,
                            position[2],
                            position[3],
                            code.name(),
                            code));
            carried = traceback.next() == null ? PyNone.INSTANCE : traceback.next();
        }
        return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
    }

    /**
     * {@code _get_code_position}: the position table's entry for the failing instruction.
     *
     * <p>The table has one entry per two-byte unit, and {@code tb_lasti} counts bytes, so the index is
     * the offset halved. An offset the table does not reach, or a value it does not name, answers four
     * -1s and lets the caller fall back to the line table, exactly as {@code _walk_tb_with_full_positions}
     * does with a quadruple whose first item is {@code None}.
     */
    private static int[] positionOf(PyCode code, int lasti) {
        int[] none = {-1, -1, -1, -1};
        if (lasti < 0) {
            return none;
        }
        PyTuple positions = code.positions();
        int index = lasti / 2;
        if (index >= positions.size() || !(positions.at(index) instanceof PyTuple item)) {
            return none;
        }
        int[] position = new int[4];
        for (int at = 0; at < 4 && at < item.size(); at++) {
            position[at] = item.at(at) instanceof PyInt value ? number(value) : -1;
        }
        return position;
    }

    private static int number(PyInt value) {
        return value.getValue().intValue();
    }

    /**
     * The frame the error ended in, or null where the exception carries no traceback at all.
     *
     * <p>This is the {@code tb} that {@code _compute_suggestion_error} walks to its last link, and it is
     * walked from the whole chain rather than from {@link #entriesOf}: a report cut short by {@code
     * sys.tracebacklimit} still names the frame that failed, because the limit is applied to what the reader
     * is shown after the sentence has been chosen.
     */
    private static Frame innermostFrameOf(PyBaseException value) {
        Frame innermost = null;
        PyObject carried = value.getAttr("__traceback__");
        while (carried instanceof PyTraceback traceback) {
            innermost = traceback.frame();
            carried = traceback.next() == null ? PyNone.INSTANCE : traceback.next();
        }
        return innermost;
    }

    /**
     * The entry lines, with a run of identical ones collapsed into a count.
     *
     * <p>Two entries are the same run when file, line and function agree — which is what a recursion
     * produces, and why a thousand frames of it print as four lines.
     *
     * @return {@code StackSummary.format}'s output as one text, every line of it ending in a newline
     */
    private String frames(List<Entry> entries) {
        StringBuilder block = new StringBuilder();
        Entry previous = null;
        int count = 0;
        for (Entry entry : entries) {
            boolean sameRun =
                    previous != null
                            && previous.fileName().equals(entry.fileName())
                            && previous.line() == entry.line()
                            && previous.name().equals(entry.name());
            if (!sameRun) {
                if (count > RECURSIVE_CUTOFF) {
                    block.append(repeated(count));
                }
                previous = entry;
                count = 0;
            }
            count++;
            if (count <= RECURSIVE_CUTOFF) {
                block.append(entry(entry));
            }
        }
        if (count > RECURSIVE_CUTOFF) {
            block.append(repeated(count));
        }
        return block.toString();
    }

    private static String repeated(int count) {
        int hidden = count - RECURSIVE_CUTOFF;
        return "  [Previous line repeated " + hidden + " more "
                + (hidden > 1 ? "times" : "time") + "]\n";
    }

    private String entry(Entry entry) {
        StringBuilder line =
                new StringBuilder("  File \"")
                        .append(entry.fileName())
                        .append("\", line ")
                        // -1 is the line no range covers, which the exception itself reports as None.
                        .append(entry.line() < 0 ? "None" : Integer.toString(entry.line()))
                        .append(", in ")
                        .append(entry.name())
                        .append('\n');
        String source =
                TracebackSource.block(
                        linesOf(entry),
                        entry.line(),
                        entry.endLine(),
                        // A report that fell back to the C printer quotes one line and draws nothing:
                        // a column the entry does not carry is the same request.
                        anchors ? entry.col() : -1,
                        anchors ? entry.endCol() : -1);
        if (source != null) {
            line.append(source);
        }
        return line.toString();
    }

    /**
     * The lines an entry quotes, read once per file because a deep traceback names one file many times.
     *
     * <p>A source compiled from a string has no file to read, so the launcher registers its text instead,
     * keyed the way {@code linecache._register_code} keys it. A name nothing registered answers an empty
     * list, which is the same thing as a line the file does not have.
     */
    private List<String> linesOf(Entry entry) {
        String fileName = entry.fileName();
        List<String> lines =
                fileName.startsWith("<") && fileName.endsWith(">")
                        ? inlineSources.get(keyOf(entry.code()))
                        : files.computeIfAbsent(fileName, TracebackReport::readLines);
        return lines == null ? List.of() : lines;
    }

    /**
     * The lines {@code linecache} would answer for a file, read through {@code tokenize.open}: the encoding
     * is the one the file declares in a PEP 263 cookie, or UTF-8, and a UTF-8 BOM is not part of the first
     * line.
     *
     * <p>Reading as plain UTF-8 was visible twice. A file that says {@code # coding: latin1} and then writes
     * a latin-1 letter had no line quoted under its frame at all, because the bytes would not decode; a file
     * that starts with a BOM had the BOM quoted and every caret one column to the right, because the BOM had
     * survived as text. The reference opens these files the way the tokenizer does, so this reads them the
     * same way.
     */
    private static List<String> readLines(String fileName) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(fileName));
        } catch (IOException | InvalidPathException unreadable) {
            // A source that has gone, or that cannot be decoded, is the same answer as a line the
            // file does not have: the entry is still reported, without anything under it.
            return List.of();
        }
        String text;
        try {
            text = Decoder.decode(bytes, declaredEncoding(bytes), "replace");
        } catch (PyRaise unknown) {
            text = Decoder.decode(bytes, "utf_8", "replace");
        }
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    /** The encoding a PEP 263 cookie names, or {@code utf_8_sig} for a BOM, or {@code utf_8}. */
    private static String declaredEncoding(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) {
            return "utf_8_sig";
        }
        int at = 0;
        for (int line = 0; line < 2 && at < bytes.length; line++) {
            int end = at;
            while (end < bytes.length && bytes[end] != '\n') {
                end++;
            }
            String candidate = new String(bytes, at, end - at, StandardCharsets.ISO_8859_1);
            Matcher cookie = COOKIE.matcher(candidate);
            if (cookie.find()) {
                return cookie.group(1).toLowerCase(java.util.Locale.ROOT).replace('-', '_');
            }
            if (line == 0 && !candidate.strip().isEmpty() && !candidate.strip().startsWith("#")) {
                break;
            }
            at = end + 1;
        }
        return "utf_8";
    }

    /** {@code tokenize.cookie_re}, anchored the way the tokenizer anchors it. */
    private static final Pattern COOKIE =
            Pattern.compile("^[ \\t\\f]*#.*?coding[:=][ \\t]*([-_.a-zA-Z0-9]+)");

    // --- the complaint -------------------------------------------------------------------

    /**
     * {@code ValueError: bad value}, or the bare type name where there is nothing to say.
     *
     * <p>An exception whose {@code __str__} fails is reported as CPython reports it — {@code <exception
     * str() failed>} — because the frames above it are the news the reader came for, and a second
     * failure is not allowed to hide them.
     *
     * <p>The suggestion is part of the same line rather than a line of its own, and it is hung on the text
     * of the exception rather than beside it: {@code _str} is what {@code format_exception_only} puts behind
     * the colon, so a complaint that had nothing to say about a name that was missed reads {@code NameError:
     * . Did you mean: 'x'?} with the period the suggestion always begins with.
     */
    private static String complaint(PyBaseException value, Frame innermost) {
        String type = typeName(value.exceptionType());
        String text = safe("exception", "str", value::str);
        String sentence = suggestion(value, innermost);
        if (sentence != null) {
            text += sentence;
        }
        return type + (text.isEmpty() ? "" : ": " + text) + "\n";
    }

    /**
     * {@code TracebackException._format_syntax_error}: a syntax error's block, which quotes the source the
     * parser refused and underlines the span it refused.
     *
     * <p>This is the one block whose text does not come from a code object. A {@code SyntaxError} carries a
     * file name, a line number, the source line and a one-based column span in its own members, and those
     * are what the reference prints — a value made by hand with {@code SyntaxError('bad', ('bad.py', 1, 2,
     * 'abcdefg', 1, 7))} is shown exactly so. The members are read as what they are, not as what they
     * should be: {@code lineno} prints as {@code True} when a caller filed a bool, and carets are drawn only
     * where {@code offset} answers {@code isinstance(x, int)}, which a bool does and a string does not.
     *
     * <p>The span rules are the reference's, and each has a reason: an {@code end_offset} of zero or a
     * different {@code end_lineno} means "no named end", a span past the end of the text is clipped to one
     * character past it, and a reversed or negative end is replaced by one caret. The leading whitespace the
     * parser reports as part of {@code offset} is removed from the quoted line and kept in the caret line,
     * except that tabs stay as tabs so the carets land under the right characters.
     */
    private static String syntax(PyBaseException value) {
        StringBuilder out = new StringBuilder();
        PyObject filename = value.memberField("filename");
        PyObject lineno = value.memberField("lineno");
        PyObject offset = value.memberField("offset");
        PyObject endLineno = value.memberField("end_lineno");
        PyObject endOffset = value.memberField("end_offset");
        PyObject source = value.memberField("text");
        PyObject message = value.memberField("msg");

        String suffix = "";
        if (!absent(lineno)) {
            out.append("  File \"")
                    .append(!absent(filename) && Slots.truth(filename) ? stated(filename) : "<string>")
                    .append("\", line ")
                    .append(stated(lineno))
                    .append('\n');
        } else if (!absent(filename)) {
            suffix = " (" + stated(filename) + ")";
        }

        if (source instanceof PyStr quoted) {
            String full = quoted.getValue();
            String raw = trailingNewlinesCut(full);
            String line = leadingBlanksCut(raw);
            int spaces = raw.length() - line.length();
            Long start = asInt(offset);
            if (absent(offset)) {
                out.append("    ").append(line).append('\n');
            } else if (start != null) {
                long end;
                if (sameValue(lineno, endLineno)) {
                    Long named = asInt(endOffset);
                    end = named != null && named != 0 ? named : start;
                } else {
                    end = raw.length() + 1L;
                }
                if (start > full.length()) {
                    start = (long) raw.length() + 1;
                }
                if (end > full.length()) {
                    end = raw.length() + 1L;
                }
                if (start >= end || end < 0) {
                    end = start + 1;
                }
                long column = start - 1 - spaces;
                long endColumn = end - 1 - spaces;
                if (column >= 0) {
                    int[] codePoints = line.codePoints().toArray();
                    StringBuilder caretSpace = new StringBuilder();
                    for (int at = 0; at < column && at < codePoints.length; at++) {
                        caretSpace.append(
                                Character.isWhitespace(codePoints[at])
                                                || Character.isSpaceChar(codePoints[at])
                                        ? (char) codePoints[at]
                                        : ' ');
                    }
                    out.append("    ").append(line).append('\n');
                    out.append("    ").append(caretSpace);
                    for (long at = column; at < endColumn; at++) {
                        out.append('^');
                    }
                    out.append('\n');
                } else {
                    out.append("    ").append(line).append('\n');
                }
            } else if (absent(offset)) {
                out.append("    ").append(line).append('\n');
            }
        }

        String detail =
                absent(message) || !Slots.truth(message) ? "<no detail available>" : stated(message);
        return out.append(typeName(value.exceptionType()))
                .append(": ")
                .append(detail)
                .append(suffix)
                .append('\n')
                .toString();
    }

    /** Whether a member holds nothing: never filled, deleted, or {@code None}. */
    private static boolean absent(PyObject value) {
        return value == null || value instanceof PyNone;
    }

    /** What CPython's {@code format} prints for a value: {@code str} for everything but nothing at all. */
    private static String stated(PyObject value) {
        return safe("value", "str", value::str);
    }

    /** {@code isinstance(value, int)}, which a bool answers and a float does not. */
    private static Long asInt(PyObject value) {
        if (value instanceof PyInt number) {
            return number.getValue().longValue();
        }
        if (value instanceof PyBool flag) {
            return flag.getValue() ? 1L : 0L;
        }
        return null;
    }

    /** {@code a == b} for two members, false where the comparison itself fails. */
    private static boolean sameValue(PyObject left, PyObject right) {
        try {
            return Slots.truth(Slots.compare(CompareOp.EQ, left, right));
        } catch (RuntimeException failed) {
            return false;
        }
    }

    private static String trailingNewlinesCut(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    private static String leadingBlanksCut(String text) {
        int start = 0;
        while (start < text.length()) {
            char c = text.charAt(start);
            if (c != ' ' && c != '\n' && c != '\f') {
                break;
            }
            start++;
        }
        return text.substring(start);
    }

    /**
     * The sentence the chooser suggests, or none at all.
     *
     * <p>Anything that fails inside it is swallowed here rather than reported: CPython lets such a failure
     * escape {@code TracebackException} and falls back to the printer written in C, and this runtime has no
     * second printer to fall back to — one that had it could not quote the line of a {@code -c} command at
     * all.
     */
    private static String suggestion(PyBaseException value, Frame innermost) {
        try {
            return Suggestions.sentence(value, innermost);
        } catch (RuntimeException failed) {
            return null;
        }
    }

    /**
     * How the type is named: {@code __qualname__}, with its module in front of it unless the module is
     * one the reader already knows.
     *
     * <p>{@code exc_type_str} drops {@code builtins} and {@code __main__}, so a class a script wrote
     * reports as {@code Loud} and not as {@code __main__.Loud}, while anything else keeps its path — a
     * Java failure among them, which {@link PyType#qualifiedName} already spells in full.
     */
    private static String typeName(PyType type) {
        PyObject module = type.getAttr("__module__");
        if (module instanceof PyStr named
                && (named.getValue().equals("builtins") || named.getValue().equals("__main__"))
                && type.getAttr("__qualname__") instanceof PyStr qualName) {
            return qualName.getValue();
        }
        return type.qualifiedName();
    }

    /**
     * {@code format_exception_only}: the complaint and then the notes, as one text of lines.
     *
     * <p>CPython yields the two as separate strings and indents each of them on the way out, which is the
     * same text as indenting the pair once they are joined — so the block is built here and {@link #emit}
     * puts the margin on every line of it.
     *
     * <p>{@code __notes__} is read as the report reads it rather than as a promise: a list or tuple is
     * walked and each note printed as its own lines, a bare string or anything else is printed as its
     * {@code repr}, which is the difference CPython draws with {@code isinstance(x, Sequence)}.
     */
    private static String only(PyBaseException value, Frame innermost) {
        StringBuilder text = new StringBuilder(
                Classes.instanceOf(value, ExceptionTypes.SYNTAX_ERROR)
                        ? syntax(value)
                        : complaint(value, innermost));
        PyObject notes = value.getAttr("__notes__");
        if (notes == null || notes instanceof PyNone || notes == Slots.DECLINED) {
            return text.toString();
        }
        if (notes instanceof PyList list) {
            for (int i = 0; i < list.size(); i++) {
                appendNote(text, safe("note", "str", list.at(i)::str));
            }
            return text.toString();
        }
        if (notes instanceof PyTuple tuple) {
            for (int i = 0; i < tuple.size(); i++) {
                appendNote(text, safe("note", "str", tuple.at(i)::str));
            }
            return text.toString();
        }
        return text.append(safe("__notes__", "repr", notes::repr)).append('\n').toString();
    }

    /**
     * One note's text, split on the newline and nothing else, each piece given a line of its own.
     *
     * <p>The trailing empty piece of {@code "text\n".split('\n')} is kept, which is why a note that ends in
     * a newline is reported with a blank line after it — CPython's list comprehension makes one, and the
     * margin then makes that blank line visible inside a group.
     */
    private static void appendNote(StringBuilder text, String note) {
        for (String line : note.split("\n", -1)) {
            text.append(line).append('\n');
        }
    }

    /** {@code _ExceptionPrintContext.emit}: the margin a whole block of the report gets, with {@code |}. */
    private void emit(String text, Context ctx) {
        emit(text, ctx, '|');
    }

    /**
     * Every line of {@code text} with the group margin in front of it, which is {@code textwrap.indent}
     * handed a predicate that accepts blank lines.
     *
     * <p>Two details make this more than a prefix. An empty line inside a group is not left empty — it gets
     * the margin and its trailing space, which is what makes the blank around a chain introduction read as
     * part of the block it introduces. And the margin is only put on at all once the report is inside a
     * group: at the top of one the depth is still zero, and CPython's {@code if
     * self.exception_group_depth:} is what keeps the plain report free of a leading space.
     */
    private void emit(String text, Context ctx, char margin) {
        String prefix = ctx.depth == 0 ? "" : ctx.indent() + margin + " ";
        for (String line : splitLines(text)) {
            out.append(prefix).append(line);
        }
    }

    /** {@code text.splitlines(True)}: every line with the terminator that ended it, and the last one bare. */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int end = lineEnd(text, from);
            if (end < 0) {
                lines.add(text.substring(from));
                return lines;
            }
            lines.add(text.substring(from, end));
            from = end;
        }
        return lines;
    }

    /**
     * Where the line starting at {@code from} ends, past its terminator, or -1 where no line does.
     *
     * <p>{@code str.splitlines} breaks on more than a newline — a vertical tab, a form feed, the four
     * file separators, the Unicode line and paragraph separators, and a lone carriage return — and the
     * report inherits that list whether it wanted it or not, because {@code textwrap.indent} is what does
     * the splitting.
     */
    private static int lineEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? i + 2 : i + 1;
            }
            if (c == '\n' || c == '\u000B' || c == '\f' || c == 0x85 || c == 0x2028 || c == 0x2029
                    || (c >= 0x1c && c <= 0x1e)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** {@code _safe_string}: the text of a value, or the complaint about the call that could not make it. */
    private static String safe(String what, String through, Supplier<String> read) {
        try {
            return read.get();
        } catch (RuntimeException failed) {
            // CPython's bare except: whatever a value's own code raises while the report is being
            // written, the reader still gets the frames the error travelled through.
            return "<" + what + " " + through + "() failed>";
        }
    }

    /**
     * {@code sys.tracebacklimit}: how many entries the report may show, counted from the innermost.
     *
     * <p>A bool counts because CPython asks {@code PyLong_Check}, which a {@code bool} passes as the
     * integer it is. Anything else — a string, a float — is not a limit at all: the Python printer
     * refuses to compare it, the report falls back to the printer that never reads it, and every frame
     * is shown.
     */
    private static int entryLimit(PyObject configured) {
        BigInteger value = limitValue(configured);
        if (value == null) {
            return Integer.MAX_VALUE;
        }
        if (value.signum() <= 0) {
            return 0;
        }
        return value.bitLength() < 32 ? value.intValue() : Integer.MAX_VALUE;
    }

    /** The integer a limit stands for, or null for a value that is not a limit. */
    private static BigInteger limitValue(PyObject value) {
        if (value instanceof PyInt number) {
            return number.getValue();
        }
        if (value instanceof PyBool flag) {
            return flag.getValue() ? BigInteger.ONE : BigInteger.ZERO;
        }
        return null;
    }
}
