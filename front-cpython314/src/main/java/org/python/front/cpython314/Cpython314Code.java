package org.python.front.cpython314;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.python.core.CodeInstructions;
import org.python.core.CodeMembers;
import org.python.core.CompareOp;
import org.python.core.PyBytes;
import org.python.core.PyCode;
import org.python.core.PyInt;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PyStr;
import org.python.core.PyTuple;
import org.python.core.PyType;
import org.python.core.Slots;

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

    /** {@code sizeof(_Py_CODEUNIT)}: the width an instruction stream has to come in. */
    private static final int CODE_UNIT_WIDTH = 2;

    /** {@code PyErr_BadInternalCall}, which the reference spells out with the C line it was reached from. */
    private static final String BAD_INTERNAL_CALL = "bad argument to internal function";

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

    /**
     * {@code PyUnstable_Code_NewWithPosOnlyArgs} for this format: the localsplus array the three name runs
     * make, then what the C refuses before it allocates, then the constructor the reader of a {@code .pyc}
     * already uses.
     *
     * <p>The array is the one thing here that is worked out rather than copied. A cell whose name is also a
     * local takes no slot of its own — the local's kind byte gains the cell bit and the count the array
     * ends up with shrinks by one — which is why {@code co_varnames} and {@code co_cellvars} can name the
     * same thing and why {@code _varname_from_oparg} reaches further than {@code co_nlocals} counts. A free
     * variable never merges: it names nothing this scope declares, and its slot is always its own.
     *
     * <p>Three of the C's refusals are unrepresentable here rather than skipped: its {@code
     * PyErr_BadInternalCall} half that asks whether the three runs are tuples at all, since Java handed
     * them over as {@link PyTuple}s, and its {@code co_code larger than INT_MAX} half, since no Java array
     * is that long. What is skipped is {@code CO_FAST_HIDDEN}: the C walks the instruction stream for
     * {@code LOAD_FAST_AND_CLEAR} only in a body that is not optimized, and the bit it sets belongs to no
     * name and no count this runtime reads back.
     *
     * <p>One refusal is this route's own. The reference never looks at {@code co_linetable} or {@code
     * co_exceptiontable} while it builds — it keeps the bytes and decodes them per read, tolerating
     * whatever it finds — and this reader decodes them here, because the runs {@code co_lines()} and {@code
     * co_positions()} answer are what it made of those bytes. A copy that hands over a table no compiler
     * wrote therefore hears the reader's own complaint as a {@code ValueError} where the reference would
     * answer a line range; what the two then say about the same bytes is the difference of record, and the
     * reader's refusals are {@code LineTableTest}'s to pin.
     */
    @Override
    public PyCode newCode(PyCode.Fields fields) {
        PyTuple varNames = fields.varNames();
        PyTuple cellVars = fields.cellVars();
        PyTuple freeVars = fields.freeVars();
        PyObject[] plusNames = new PyObject[varNames.size() + cellVars.size() + freeVars.size()];
        byte[] plusKinds = new byte[plusNames.length];
        int slot = 0;
        for (PyObject name : varNames.getArray()) {
            plusNames[slot] = name;
            plusKinds[slot++] = CO_FAST_LOCAL;
        }
        for (PyObject name : cellVars.getArray()) {
            int merged = indexOf(varNames, name);
            if (merged >= 0) {
                plusKinds[merged] |= CO_FAST_CELL;
                continue;
            }
            plusNames[slot] = name;
            plusKinds[slot++] = CO_FAST_CELL;
        }
        for (PyObject name : freeVars.getArray()) {
            plusNames[slot] = name;
            plusKinds[slot++] = (byte) CO_FAST_FREE;
        }
        PyObject[] plus = Arrays.copyOf(plusNames, slot);
        int stackSize = fields.stackSize();
        int flags = fields.flags();

        if (fields.argCount() < fields.posOnlyArgCount() || stackSize < 0 || flags < 0) {
            throw Slots.systemError(BAD_INTERNAL_CALL);
        }
        byte[] code = fields.code().getValue();
        if (code.length % CODE_UNIT_WIDTH != 0) {
            throw Slots.valueError("code: co_code is malformed");
        }
        // The C's own arithmetic: the counts have to leave room for the parameters the flags name.
        int nplainlocals =
                countKinds(plusKinds, slot, CO_FAST_LOCAL)
                        - fields.argCount()
                        - fields.kwOnlyArgCount()
                        - ((flags & PyCode.CO_VARARGS) != 0 ? 1 : 0)
                        - ((flags & PyCode.CO_VARKEYWORDS) != 0 ? 1 : 0);
        if (nplainlocals < 0) {
            throw Slots.valueError("code: co_varnames is too small");
        }
        if (fields.nLocals() != varNames.size()) {
            throw Slots.valueError("code: co_nlocals != len(co_varnames)");
        }
        requireStrings(fields.names().getArray());
        requireStrings(plus);
        // init_code refuses to build a frame that cannot hold a value.
        if (stackSize == 0) {
            stackSize = 1;
        }
        try {
            return new Cpython314Code(
                    fields.argCount(),
                    fields.posOnlyArgCount(),
                    fields.kwOnlyArgCount(),
                    stackSize,
                    flags,
                    fields.code(),
                    fields.constants(),
                    fields.names(),
                    PyTuple.of(plus),
                    PyBytes.wrap(Arrays.copyOf(plusKinds, slot)),
                    fields.fileName(),
                    fields.name(),
                    fields.qualName(),
                    fields.firstLine(),
                    fields.lineTable(),
                    fields.exceptionTable());
        } catch (MarshalFormatException refused) {
            throw Slots.valueError(refused.getMessage());
        }
    }

    /** The first slot {@link PyTuple#at} holds that is this name, or -1 for a name the run does not have. */
    private static int indexOf(PyTuple names, PyObject name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.at(i).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** How many of the first {@code slots} kinds carry this bit, which is what {@code co_nlocals} counts. */
    private static int countKinds(byte[] kinds, int slots, int bit) {
        int count = 0;
        for (int i = 0; i < slots; i++) {
            if ((kinds[i] & bit) != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * {@code intern_strings}: the two runs a code object indexes by name have to hold nothing else.
     *
     * <p>The C asks this after it has worked out everything else, so a name run with a number in it is a
     * {@code SystemError} rather than a {@code TypeError}: nothing that reads a code object expects to have
     * to check, and a hand-built table is the only way to make one that does not hold. The C's own test is
     * {@code PyUnicode_CheckExact}, so a {@code str} subclass is refused there and would pass here — a
     * difference nothing reaches yet, since {@code class X(str)} is not implemented.
     */
    private static void requireStrings(PyObject[] names) {
        for (PyObject name : names) {
            if (!(name instanceof PyStr)) {
                throw Slots.systemError("non-string found in code slot");
            }
        }
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

    /** {@code Py_SIZE(co)}: the units the wire format wrote, which is {@code co_code} two octets at a time. */
    @Override
    public int unitCount() {
        return codeArray.length / CODE_UNIT_WIDTH;
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

    /**
     * {@inheritDoc}
     *
     * <p>CPython's iterator walks the code array a unit at a time, de-instrumenting each unit it lands on and
     * stepping over the caches behind it, and names three offsets for the six opcodes that have two ways —
     * which is the walk {@link Wordcode#branches()} does, on the units this reader already decoded rather than
     * over the array again. The doubling to bytes is the same one {@code co_lines()} and a traceback are
     * answered with, and nothing here is capped to the length of {@code co_code}: a target the compiler never
     * wrote is still the number CPython's arithmetic says, and this answers the same one.
     */
    @Override
    public PyTuple branches() {
        List<PyObject> rows = new ArrayList<>();
        for (Wordcode.Branch branch : instructions.branches()) {
            rows.add(
                    PyTuple.of(
                            PyInt.of(2 * branch.sourceUnit()),
                            PyInt.of(2 * branch.fallthroughUnit()),
                            PyInt.of(2 * branch.jumpUnit())));
        }
        return PyTuple.of(rows.toArray(new PyObject[0]));
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
     *
     * <p>{@code _co_code_adaptive} is a known deviation. CPython hands out the instruction array followed
     * by the inline caches that the specializing interpreter writes into it — a longer answer whose tail is
     * the memory of what each opcode has seen — and this runtime has no specializing interpreter to fill
     * that tail. It therefore answers the plain instruction array, which is what the same bytes would be in
     * an interpreter that had run nothing yet.
     */
    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "co_code", "_co_code_adaptive" -> PyBytes.wrap(codeArray);
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
     * {@code code_richcompare}, which is the six comparison names of {@code code}'s dictionary.
     *
     * <p>The walk is {@link CodeMembers}'s, and so is the deviation it has to make: the C compares the two
     * byte tables outright, and a code object read from this format answers what they say rather than how
     * they are packed.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return CodeMembers.compare(cmp, this, other);
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
