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

/**
 * The report a person reads when nothing catches an exception.
 *
 * <p>CPython builds this text twice over, and the two are not the same document. {@code
 * Python/traceback.c} prints the header, one {@code File "...", line N, in NAME} entry per traceback
 * link, and the source line read off disk; {@code Lib/traceback.py} does the same and adds, under the
 * failing expression, the {@code ^} and {@code ~} line PEP 657 calls an anchor. Since 3.13 the default
 * report goes through the Python one — {@code _PyErr_Display} imports {@code traceback} and calls its
 * {@code _print_exception_bltin}, and only falls back to the C printer when that fails. This class
 * transcribes the C printer's text, which is byte for byte what the Python printer writes when the code
 * objects carry no column information, as they do under {@code -X no_debug_ranges}.
 *
 * <p>The anchors are therefore the one named omission, and it is not a matter of reading wider ranges
 * out of the line table: {@code _extract_caret_anchors_from_line_segment} decides which characters get a
 * {@code ~} by parsing the source segment with {@code ast} and {@code tokenize}, and this runtime has
 * neither module — it reads a code object, not a syntax tree. Everything else the report says is here:
 * the chain of causes and contexts, the collapse of a repeated line, {@code sys.tracebacklimit}, the
 * notes, and the type name the complaint is filed under.
 *
 * <p>Two readings follow CPython's code rather than a plausible shortcut. The limit keeps the
 * <em>last</em> N entries, because {@code BUILTIN_EXCEPTION_LIMIT} slices the walked traceback from the
 * end; and a {@code sys.tracebacklimit} that is not a number is ignored, where CPython's comparison
 * raises inside the display and drops it to the C printer — which, unlike the Python one, cannot quote a
 * {@code -c} command at all.
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

    private TracebackReport() {
        limit = entryLimit();
    }

    /** The whole report of {@code error}, ending in a newline like every line of it does. */
    public static String of(PyBaseException error) {
        TracebackReport report = new TracebackReport();
        report.chain(error);
        return report.out.toString();
    }

    /** {@code of} written to {@code sys.stderr}, which is how an uncaught exception reaches a person. */
    public static void print(PyBaseException error) {
        PyRuntime.stderr().writeText(of(error));
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

    /**
     * Every exception the report has to show, oldest first, each with the sentence that introduces it.
     *
     * <p>{@code TracebackException.format} walks {@code __cause__} and then {@code __context__} from the
     * raised value outwards, collects the chain, and prints it in reverse — which is why a cause appears
     * above the exception it caused. The walk stops at the first exception it has already shown, as the
     * {@code _seen} set of ids stops CPython's, so {@code a.__cause__ = b; b.__cause__ = a} reports two
     * blocks rather than never finishing.
     *
     * <p>An introduction belongs to a link the walk actually takes. Where {@code StackSummary.__init__}
     * refuses to build a chained object for an id already in {@code _seen}, the report has no sentence to
     * print either — a cycle closes on the oldest block it reached, not on a claim that something was
     * handled before it.
     */
    private void chain(PyBaseException error) {
        List<PyBaseException> values = new ArrayList<>();
        List<String> introductions = new ArrayList<>();
        Set<PyBaseException> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        PyBaseException at = error;
        while (at != null && seen.add(at)) {
            PyBaseException cause = chained(at, "__cause__");
            // compact=True: a context is only followed when there was no cause to print.
            PyBaseException context =
                    cause == null && !Slots.truth(at.getAttr("__suppress_context__"))
                            ? chained(at, "__context__")
                            : null;
            PyBaseException next = cause != null ? cause : context;
            boolean follows = next != null && !seen.contains(next);
            introductions.add(follows ? cause != null ? CAUSE_MESSAGE : CONTEXT_MESSAGE : null);
            values.add(at);
            at = follows ? next : null;
        }

        for (int i = values.size() - 1; i >= 0; i--) {
            if (introductions.get(i) != null) {
                out.append(introductions.get(i));
            }
            block(values.get(i));
        }
    }

    /** {@code e.__cause__} or {@code e.__context__} as an exception, or null where CPython has {@code None}. */
    private static PyBaseException chained(PyBaseException value, String name) {
        return value.getAttr(name) instanceof PyBaseException other ? other : null;
    }

    /** One exception's own report: its frames, then its complaint, then its notes. */
    private void block(PyBaseException value) {
        List<Entry> entries = entriesOf(value);
        if (!entries.isEmpty()) {
            out.append(HEADER);
            frames(entries);
        }
        out.append(complaint(value));
        notes(value);
    }

    // --- the entries ---------------------------------------------------------------------

    /** One traceback link as the report reads it: a file, a line, a function, and the code to quote. */
    private record Entry(String fileName, int line, String name, PyCode code) {}

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
            all.add(
                    new Entry(
                            frame.code().fileName(),
                            frame.instructions().lineAtLasti(traceback.lasti()),
                            frame.code().name(),
                            frame.code()));
            carried = traceback.next() == null ? PyNone.INSTANCE : traceback.next();
        }
        return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
    }

    /**
     * The entry lines, with a run of identical ones collapsed into a count.
     *
     * <p>Two entries are the same run when file, line and function agree — which is what a recursion
     * produces, and why a thousand frames of it print as four lines.
     */
    private void frames(List<Entry> entries) {
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
                    out.append(repeated(count));
                }
                previous = entry;
                count = 0;
            }
            count++;
            if (count <= RECURSIVE_CUTOFF) {
                out.append(entry(entry));
            }
        }
        if (count > RECURSIVE_CUTOFF) {
            out.append(repeated(count));
        }
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
        String source = sourceLine(entry);
        if (source != null && !source.isEmpty()) {
            line.append(SOURCE_INDENT).append(source).append('\n');
        }
        return line.toString();
    }

    /** The quoted line of an entry, or null where CPython prints no line at all. */
    private String sourceLine(Entry entry) {
        if (entry.line() < 1) {
            return null;
        }
        String fileName = entry.fileName();
        List<String> lines =
                fileName.startsWith("<") && fileName.endsWith(">")
                        ? inlineSources.get(keyOf(entry.code()))
                        : files.computeIfAbsent(fileName, TracebackReport::readLines);
        if (lines == null || entry.line() > lines.size()) {
            return null;
        }
        // linecache rstrips each line and FrameSummary strips the one it returns; the report only ever
        // shows a line that still has something on it.
        return lines.get(entry.line() - 1).strip();
    }

    private static List<String> readLines(String fileName) {
        try {
            return Files.readAllLines(Path.of(fileName), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException unreadable) {
            // A source that has gone, or that cannot be decoded, is the same answer as a line the
            // file does not have: the entry is still reported, without anything under it.
            return List.of();
        }
    }

    // --- the complaint -------------------------------------------------------------------

    /**
     * {@code ValueError: bad value}, or the bare type name where there is nothing to say.
     *
     * <p>An exception whose {@code __str__} fails is reported as CPython reports it — {@code <exception
     * str() failed>} — because the frames above it are the news the reader came for, and a second
     * failure is not allowed to hide them.
     */
    private static String complaint(PyBaseException value) {
        String type = typeName(value.exceptionType());
        String text = safe("exception", "str", value::str);
        return type + (text.isEmpty() ? "" : ": " + text) + "\n";
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
     * The notes, one block of lines each, after the complaint.
     *
     * <p>{@code __notes__} is read as the report reads it rather than as a promise: a list or tuple is
     * walked and each note printed as its own lines, a bare string or anything else is printed as its
     * {@code repr}, which is the difference CPython draws with {@code isinstance(x, Sequence)}.
     */
    private void notes(PyBaseException value) {
        PyObject notes = value.getAttr("__notes__");
        if (notes == null || notes instanceof PyNone || notes instanceof PyNotImplemented) {
            return;
        }
        if (notes instanceof PyList || notes instanceof PyTuple) {
            boolean list = notes instanceof PyList;
            int size = list ? ((PyList) notes).size() : ((PyTuple) notes).size();
            for (int i = 0; i < size; i++) {
                PyObject note = list ? ((PyList) notes).at(i) : ((PyTuple) notes).at(i);
                emitLines(safe("note", "str", note::str));
            }
            return;
        }
        emitLine(safe("__notes__", "repr", notes::repr));
    }

    /** A note's text, with its own newlines kept as separate lines of the report — CPython's {@code split}. */
    private void emitLines(String text) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        for (String line : lines) {
            emitLine(line);
        }
    }

    private void emitLine(String text) {
        out.append(text).append('\n');
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
    private static int entryLimit() {
        BigInteger configured = limitValue(PyRuntime.sysAttribute("tracebacklimit"));
        if (configured == null) {
            return Integer.MAX_VALUE;
        }
        if (configured.signum() <= 0) {
            return 0;
        }
        return configured.bitLength() < 32 ? configured.intValue() : Integer.MAX_VALUE;
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
