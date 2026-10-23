package org.python.front.cpython314;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.python.core.CodeInstructions;
import org.python.core.PyBool;
import org.python.core.PyBytes;
import org.python.core.PyCode;
import org.python.core.PyComplex;
import org.python.core.PyDict;
import org.python.core.PyEllipsis;
import org.python.core.PyFloat;
import org.python.core.PyFrozenSet;
import org.python.core.PyHash;
import org.python.core.PyInt;
import org.python.core.PyList;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PySet;
import org.python.core.PySlice;
import org.python.core.PyStr;
import org.python.core.PyTuple;
import org.python.core.PyType;

/**
 * Renders an unmarshalled value exactly the way {@code tools/gen_marshal_fixtures.py} renders the
 * object CPython read from the same bytes.
 *
 * The two renderings have to agree character for character, so this is a transcription of that
 * file's {@code render}, not a general-purpose repr: layout-free, values identified by their bits,
 * sets compared as multisets, and a {@code #n} marker wherever a mutable container is met a second
 * time. The marker is what makes the reference table observable — a reader that claims slots in
 * the wrong order produces different ordinals rather than a silent pass.
 *
 * Identity is tracked for list, dict and set only, matching the generator. A frozenset or a tuple
 * cannot reach that path, and CPython's reader is freer with singletons than a Java one can be:
 * equal short strings and one-byte {@code bytes} come out of its caches already shared.
 */
final class MarshalRenderer {

    private final Map<PyObject, Integer> ordinals = new IdentityHashMap<>();

    static String render(PyObject value) {
        return new MarshalRenderer().value(value);
    }

    /** A whole code object, for a {@code .pyc} payload — the only thing that stream holds. */
    static String render(PyCode value) {
        if (!(value instanceof Cpython314Code code)) {
            throw new IllegalArgumentException("not a CPython 3.14 code object: " + value);
        }
        return new MarshalRenderer().code(code);
    }

    private String value(PyObject o) {
        if (isTracked(o)) {
            Integer seen = ordinals.get(o);
            if (seen != null) {
                return "#" + seen;
            }
            ordinals.put(o, ordinals.size());
        }
        if (o instanceof PyNone) {
            return "None";
        }
        if (o instanceof PyBool bool) {
            return bool.getValue() ? "True" : "False";
        }
        if (o instanceof PyEllipsis) {
            return "Ellipsis";
        }
        if (o instanceof PyType type) {
            return "class:" + type.name();
        }
        if (o instanceof PyComplex c) {
            return "complex:" + bits(c.real()) + "," + bits(c.imaginary());
        }
        if (o instanceof PyFloat f) {
            return "float:" + bits(f.getValue());
        }
        if (o instanceof PyInt i) {
            return "int:" + i.getValue();
        }
        if (o instanceof PyBytes b) {
            return "bytes:" + hex(b.getValue());
        }
        if (o instanceof PyStr s) {
            return "str:" + hex(PyHash.encodeUtf8(s.getValue()));
        }
        if (o instanceof PyTuple t) {
            return join(t, "(", ")");
        }
        if (o instanceof PyList l) {
            return joinItems(l.getItems(), "[", "]");
        }
        if (o instanceof PyDict d) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<PyObject, PyObject> e : d.getItems().entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(value(e.getKey())).append('=').append(value(e.getValue()));
            }
            return out.append('}').toString();
        }
        if (o instanceof PySet s) {
            // A set's iteration order is a hash-table artefact on both sides, so compare the
            // multiset: same children, sorted as text.
            List<String> rendered = new ArrayList<>();
            for (PyObject item : s.getItems()) {
                rendered.add(value(item));
            }
            Collections.sort(rendered);
            return (o instanceof PyFrozenSet ? "frozenset(" : "set(")
                    + String.join(" ", rendered) + ")";
        }
        if (o instanceof PySlice slice) {
            return "slice(%s,%s,%s)".formatted(
                    value(slice.start()), value(slice.stop()), value(slice.step()));
        }
        if (o instanceof Cpython314Code code) {
            return code(code);
        }
        if (o instanceof PyCode code) {
            throw new IllegalArgumentException("not a CPython 3.14 code object: " + code);
        }
        throw new IllegalArgumentException("unrenderable " + o.getClass().getName());
    }

    private static boolean isTracked(PyObject o) {
        return o instanceof PyList || o instanceof PyDict || (o instanceof PySet && !(o instanceof PyFrozenSet));
    }

    /**
     * The code-object block of the spec. {@code decoded} is the point of it: opcode, byte offset
     * and folded argument per instruction, which is only reproducible if the cache table and the
     * EXTENDED_ARG folding both match CPython's {@code dis}.
     */
    private String code(Cpython314Code co) {
        CodeInstructions instructions = co.instructions();
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < instructions.length(); i++) {
            if (i > 0) {
                decoded.append(' ');
            }
            // dis reports byte offsets; a unit offset is in code units, which are two bytes.
            decoded.append(instructions.opcodeAt(i)).append(':')
                    .append(2 * instructions.unitOffsetAt(i)).append(':')
                    .append(instructions.argAt(i));
        }
        StringBuilder handles = new StringBuilder();
        for (ExceptionTable.Entry entry : ExceptionTable.parse(co.exceptionTable()).entries()) {
            if (!handles.isEmpty()) {
                handles.append(' ');
            }
            handles.append(2 * entry.start()).append(':').append(2 * entry.end()).append(':')
                    .append(2 * entry.target()).append(':').append(entry.depth()).append(':')
                    .append(entry.pushLasti() ? 1 : 0);
        }
        StringBuilder lines = new StringBuilder();
        for (int line : co.lineByUnit()) {
            if (!lines.isEmpty()) {
                lines.append(' ');
            }
            // The same `-` the expectation file uses for a unit that carries no location.
            lines.append(line < 0 ? "-" : line);
        }
        StringBuilder runs = new StringBuilder();
        PyTuple ranges = co.lineRanges();
        for (int i = 0; i < ranges.size(); i++) {
            PyTuple range = (PyTuple) ranges.at(i);
            if (!runs.isEmpty()) {
                runs.append(' ');
            }
            runs.append(number(range.at(0)))
                    .append(':')
                    .append(number(range.at(1)))
                    .append(':')
                    .append(number(range.at(2)));
        }
        StringBuilder places = new StringBuilder();
        PyTuple positions = co.positions();
        for (int i = 0; i < positions.size(); i++) {
            PyTuple position = (PyTuple) positions.at(i);
            if (!places.isEmpty()) {
                places.append(' ');
            }
            for (int field = 0; field < position.size(); field++) {
                if (field > 0) {
                    places.append(':');
                }
                places.append(number(position.at(field)));
            }
        }
        return ("code(argcount=%d,posonly=%d,kwonly=%d,stack=%d,flags=%d,first=%d)\n"
                + "  file=%s name=%s qualname=%s\n"
                + "  wordcode=%s\n"
                + "  linetable=%s\n"
                + "  unitlines=%s\n"
                + "  lines=%s\n"
                + "  positions=%s\n"
                + "  exceptiontable=%s\n"
                + "  exchandles=%s\n"
                + "  varnames=%s cellvars=%s freevars=%s nlocals=%d\n"
                + "  consts=%s\n"
                + "  names=%s\n"
                + "  decoded=%s").formatted(
                co.argCount(), co.posOnlyArgCount(), co.kwOnlyArgCount(), co.stackSize(),
                co.flags(), co.firstLine(),
                text(co.fileName()), text(co.name()), text(co.qualName()),
                hex(co.codeArray()), hex(co.lineTable()), lines, runs, places,
                hex(co.exceptionTable()), handles,
                value(co.varNames()), value(co.cellVars()), value(co.freeVars()), co.nLocals(),
                value(co.constants()), value(co.names()), decoded);
    }

    private String join(PyTuple t, String open, String close) {
        List<PyObject> items = new ArrayList<>(t.size());
        for (int i = 0; i < t.size(); i++) {
            items.add(t.at(i));
        }
        return joinItems(items, open, close);
    }

    private String joinItems(List<PyObject> items, String open, String close) {
        StringBuilder out = new StringBuilder(open);
        boolean first = true;
        for (PyObject item : items) {
            if (!first) {
                out.append(' ');
            }
            first = false;
            out.append(value(item));
        }
        return out.append(close).toString();
    }

    private static String text(String s) {
        return hex(PyHash.encodeUtf8(s));
    }

    private static String bits(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        return String.format("%016x", Double.doubleToRawLongBits(v));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    /**
     * One location field, as the two position reads answer it: the number, or the same {@code -} the
     * expectation file uses for a range the table does not name.
     */
    private static String number(PyObject value) {
        return value instanceof PyInt whole ? whole.getValue().toString() : "-";
    }
}
