package org.python.front.cpython314;

import java.util.ArrayList;
import java.util.List;
import org.python.core.CodeInstructions;
import org.python.core.CodeMembers;
import org.python.core.PyBytes;
import org.python.core.PyCode;
import org.python.core.PyInt;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PyTuple;
import org.python.core.PyType;

/**
 * A code object as read from a CPython 3.14 marshal stream.
 *
 * Everything 3.14-specific is confined to this class and its package: the field order of the wire
 * format, the wordcode packing, the localsplus array, and the two byte tables the interpreter does
 * not decode itself.
 */
public final class Cpython314Code extends PyObject implements PyCode {

    /** Bit flags in a localsplus kind byte, from CPython's pycore_code.h. */
    private static final int CO_FAST_LOCAL = 0x20;
    private static final int CO_FAST_CELL = 0x40;
    private static final int CO_FAST_FREE = 0x80;

    private final int argCount;
    private final int posOnlyArgCount;
    private final int kwOnlyArgCount;
    private final int stackSize;
    private final int flags;
    private final int firstLine;
    private final String fileName;
    private final String name;
    private final String qualName;
    private final PyTuple constants;
    private final PyTuple names;
    private final PyTuple localsPlusNames;
    private final byte[] localsPlusKinds;
    private final byte[] codeArray;
    private final byte[] lineTable;
    private final byte[] exceptionTable;
    private final Wordcode instructions;
    private final ExceptionTable handlers;

    Cpython314Code(
            int argCount,
            int posOnlyArgCount,
            int kwOnlyArgCount,
            int stackSize,
            int flags,
            PyBytes code,
            PyTuple constants,
            PyTuple names,
            PyTuple localsPlusNames,
            PyBytes localsPlusKinds,
            String fileName,
            String name,
            String qualName,
            int firstLine,
            PyBytes lineTable,
            PyBytes exceptionTable) {
        super(PyType.CODE);
        if (localsPlusNames.size() != localsPlusKinds.getValue().length) {
            throw MarshalFormatException.badData(
                    "localsplus names and kinds disagree in length");
        }
        this.argCount = argCount;
        this.posOnlyArgCount = posOnlyArgCount;
        this.kwOnlyArgCount = kwOnlyArgCount;
        this.stackSize = stackSize;
        this.flags = flags;
        this.firstLine = firstLine;
        this.fileName = fileName;
        this.name = name;
        this.qualName = qualName;
        this.constants = constants;
        this.names = names;
        this.localsPlusNames = localsPlusNames;
        this.localsPlusKinds = localsPlusKinds.getValue();
        this.codeArray = code.getValue();
        this.lineTable = lineTable.getValue();
        this.exceptionTable = exceptionTable.getValue();
        this.instructions = Wordcode.decode(codeArray, this.lineTable, firstLine);
        this.handlers = ExceptionTable.parse(this.exceptionTable);
    }

    @Override
    public String fileName() {
        return fileName;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String qualName() {
        return qualName;
    }

    @Override
    public int argCount() {
        return argCount;
    }

    @Override
    public int posOnlyArgCount() {
        return posOnlyArgCount;
    }

    @Override
    public int kwOnlyArgCount() {
        return kwOnlyArgCount;
    }

    @Override
    public int stackSize() {
        return stackSize;
    }

    @Override
    public int flags() {
        return flags;
    }

    @Override
    public int firstLine() {
        return firstLine;
    }

    @Override
    public PyTuple constants() {
        return constants;
    }

    @Override
    public PyTuple names() {
        return names;
    }

    @Override
    public CodeInstructions instructions() {
        return instructions;
    }

    /**
     * {@inheritDoc}
     *
     * <p>CPython looks the row up by the last code unit of the instruction that was executing
     * ({@code INSTR_OFFSET() - 1}, its own byte arithmetic); the first unit of that instruction is in the
     * same row, because a row is emitted per run of whole instructions and so never ends inside an opcode
     * and the caches that follow it.
     */
    @Override
    public Handler handlerFor(int index) {
        ExceptionTable.Entry entry = handlers.handlerFor(instructions.unitOffsetAt(index));
        if (entry == null) {
            return null;
        }
        int target = instructions.indexOfUnitAt(entry.target());
        if (target < 0) {
            throw MarshalFormatException.badData(
                    "exception handler targets code unit " + entry.target()
                            + ", which no instruction starts at");
        }
        return new Handler(target, entry.depth(), entry.pushLasti());
    }

    /**
     * {@code co_varnames} and friends, derived the way CPython derives them from the localsplus
     * array: a name belongs to whichever views its kind byte selects. Note that a captured
     * parameter is both a local and a cell, and so legitimately appears in two of these.
     */
    @Override
    public PyTuple varNames() {
        return namesWithKind(CO_FAST_LOCAL);
    }

    @Override
    public PyTuple cellVars() {
        return namesWithKind(CO_FAST_CELL);
    }

    @Override
    public PyTuple freeVars() {
        return namesWithKind(CO_FAST_FREE);
    }

    /**
     * {@code co_varnames + co_freevars + co_cellvars} in the order the code object actually lays them
     * out, which is the localsplus array itself. CPython's four names are three lists and a derived
     * fourth; this keeps the one that is stored and answers the others from it.
     */
    @Override
    public PyTuple localNames() {
        return localsPlusNames;
    }

    @Override
    public int localCount() {
        return localsPlusNames.size();
    }

    int nLocals() {
        return varNames().size();
    }

    byte[] codeArray() {
        return codeArray;
    }

    byte[] lineTable() {
        return lineTable;
    }

    /** The same table as {@link #lineTable()}, read: one line number per code unit, -1 for no location. */
    int[] lineByUnit() {
        return instructions.lineByUnit();
    }

    byte[] exceptionTable() {
        return exceptionTable;
    }

    private PyTuple namesWithKind(int mask) {
        List<PyObject> selected = new ArrayList<>();
        for (int i = 0; i < localsPlusKinds.length; i++) {
            if ((localsPlusKinds[i] & mask) != 0) {
                selected.add(localsPlusNames.at(i));
            }
        }
        return PyTuple.of(selected.toArray(new PyObject[0]));
    }

    /**
     * {@inheritDoc}
     *
     * <p>CPython walks its table entry by entry and keeps the ones that name the same line together, so the
     * answer is the runs a reader sees rather than the widths the table happens to be written in. A line of
     * -1 is the marker both use for "no line here" and merges with itself, which is why a run of caches with
     * no location is one {@code None} rather than one per cache. The walk ends where the table ends and not
     * where the code does ({@code at_end} compares {@code lo_next} to {@code limit}, both of them table
     * bytes), so nothing here measures the runs against {@code co_code}.
     */
    @Override
    public PyTuple lineRanges() {
        List<PyObject> runs = new ArrayList<>();
        int start = -1;
        int end = 0;
        int line = 0;
        for (LineTable.Entry entry : instructions.entries()) {
            if (start >= 0 && entry.line() == line) {
                end = entry.startUnit() + entry.units();
                continue;
            }
            if (start >= 0) {
                runs.add(range(start, end, line));
            }
            start = entry.startUnit();
            line = entry.line();
            end = entry.startUnit() + entry.units();
        }
        if (start >= 0) {
            runs.add(range(start, end, line));
        }
        return PyTuple.of(runs.toArray(new PyObject[0]));
    }

    /**
     * {@inheritDoc}
     *
     * <p>CPython's iterator holds the entry it is reading and steps two bytes at a time until the entry's
     * own end, so the answer is one tuple per unit {@em of the table} — a unit no entry reaches is not in it
     * at all, which is why this too walks entries rather than the code array.
     */
    @Override
    public PyTuple positions() {
        List<PyObject> items = new ArrayList<>();
        for (LineTable.Entry entry : instructions.entries()) {
            for (int unit = 0; unit < entry.units(); unit++) {
                // CPython's reader builds a tuple per unit rather than reusing one, and two units of
                // one entry are therefore two objects that happen to compare equal.
                items.add(
                        PyTuple.of(
                                offset(entry.line()),
                                offset(entry.endLine()),
                                offset(entry.column()),
                                offset(entry.endColumn())));
            }
        }
        return PyTuple.of(items.toArray(new PyObject[0]));
    }

    /** One {@code co_lines()} item: the two instruction offsets CPython counts in bytes, and the line. */
    private static PyTuple range(int startUnit, int endUnit, int line) {
        return PyTuple.of(
                PyInt.of(2 * startUnit), PyInt.of(2 * endUnit), offset(line));
    }

    /** CPython's {@code _source_offset_converter}: the marker for a range that names nothing is {@code None}. */
    private static PyObject offset(int value) {
        return value < 0 ? PyNone.INSTANCE : PyInt.of(value);
    }

    /**
     * {@code co.name}: the two byte tables this format writes, and the rest from the runtime.
     *
     * <p>The split is the one decision D2 draws. {@code co_linetable} and {@code co_exceptiontable} are
     * the bytes that came off the wire and mean something only to the reader that decoded them, and
     * {@code co_code} is the instruction stream in the same front-end's packing; every other name is a
     * number, a tuple, or a string the {@link PyCode} interface already answers, and {@link CodeMembers}
     * holds those so that a native parser can publish the same surface without copying this class.
     */
    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "co_code" -> PyBytes.wrap(codeArray);
            case "co_linetable" -> PyBytes.wrap(lineTable);
            case "co_exceptiontable" -> PyBytes.wrap(exceptionTable);
            default -> CodeMembers.getAttr(this, attribute);
        };
    }

    /**
     * A code object is immutable, and CPython says so one name at a time.
     *
     * <p>The wording depends on which table the name is in, which is why the refusal lives with the
     * tables in {@link CodeMembers} rather than here.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        CodeMembers.refuse(this, attribute, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        CodeMembers.refuse(this, attribute, null);
        return PyNone.INSTANCE;
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    /**
     * {@code repr(co)}: CPython's shape, with this object's address where a pointer would be.
     *
     * <p>A code object appears in the {@code co_consts} of the code object that encloses it, so this is
     * what a script reads when it prints a function's constants. The address cannot match the oracle's
     * and is not meant to; the name, file and first line are the parts a report is about.
     */
    @Override
    public String repr() {
        return "<code object "
                + name
                + " at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ", file \""
                + fileName
                + "\", line "
                + firstLine
                + ">";
    }
}
