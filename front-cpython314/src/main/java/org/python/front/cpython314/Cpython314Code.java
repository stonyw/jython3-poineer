package org.python.front.cpython314;

import java.util.ArrayList;
import java.util.List;
import org.python.core.CodeInstructions;
import org.python.core.PyBytes;
import org.python.core.PyCode;
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
        if (localsPlusNames.length() != localsPlusKinds.length()) {
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
        this.instructions = Wordcode.decode(codeArray);
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
     * {@code co_varnames} and friends, derived the way CPython derives them from the localsplus
     * array: a name belongs to whichever views its kind byte selects. Note that a captured
     * parameter is both a local and a cell, and so legitimately appears in two of these.
     */
    PyTuple varNames() {
        return namesWithKind(CO_FAST_LOCAL);
    }

    PyTuple cellVars() {
        return namesWithKind(CO_FAST_CELL);
    }

    PyTuple freeVars() {
        return namesWithKind(CO_FAST_FREE);
    }

    int nLocals() {
        return varNames().length();
    }

    byte[] codeArray() {
        return codeArray;
    }

    byte[] lineTable() {
        return lineTable;
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
}
