package org.python.front.cpython314;

import org.python.core.CodeInstructions;
import org.python.core.Opcodes;

/**
 * CPython 3.14 wordcode: two bytes per code unit, {@code [opcode, oparg]}, with a variable number
 * of cache units behind some opcodes and a chain of EXTENDED_ARG units in front of large arguments.
 *
 * Decoding happens once, eagerly, into parallel arrays; {@link CodeInstructions} is then pure index
 * arithmetic, so the interpreter never sees a unit, a cache or an extension.
 *
 * <p>The line table arrives with the code array because the two are read together: every question about
 * where an instruction came from is answered here, in whichever of the two conventions — code units for
 * the exception table, bytes for a traceback — the answer is asked in.
 */
final class Wordcode implements CodeInstructions {

    private final int[] opcodes;
    private final int[] args;
    private final int[] units;
    /** Instruction index per code unit, or -1 for a unit that starts nothing. */
    private final int[] indexByUnit;
    private final LineTable lines;
    private final int firstLine;

    private Wordcode(
            int[] opcodes,
            int[] args,
            int[] units,
            int[] indexByUnit,
            LineTable lines,
            int firstLine) {
        this.opcodes = opcodes;
        this.args = args;
        this.units = units;
        this.indexByUnit = indexByUnit;
        this.lines = lines;
        this.firstLine = firstLine;
    }

    static Wordcode decode(byte[] code, byte[] lineTable, int firstLine) {
        if ((code.length & 1) != 0) {
            throw MarshalFormatException.badData("code array has an odd length");
        }
        int total = code.length / 2;
        int[] opcodes = new int[total];
        int[] args = new int[total];
        int[] units = new int[total];
        int n = 0;
        int extended = 0;
        for (int i = 0; i < total; ) {
            int opcode = code[2 * i] & 0xff;
            int arg = (code[2 * i + 1] & 0xff) | extended;
            extended = opcode == InlineCaches.EXTENDED_ARG ? arg << 8 : 0;
            // An EXTENDED_ARG unit stays in the list, as dis reports it, with the argument it
            // contributed before shifting. The instruction it extends already carries the folded
            // value, so the interpreter can step over it and do nothing.
            opcodes[n] = opcode;
            args[n] = arg;
            units[n] = i;
            n++;
            i += 1 + InlineCaches.of(opcode);
        }
        int[] indexByUnit = new int[total + 1];
        java.util.Arrays.fill(indexByUnit, -1);
        for (int i = 0; i < n; i++) {
            indexByUnit[units[i]] = i;
        }
        return new Wordcode(
                java.util.Arrays.copyOf(opcodes, n),
                java.util.Arrays.copyOf(args, n),
                java.util.Arrays.copyOf(units, n),
                indexByUnit,
                new LineTable(lineTable, firstLine, total),
                firstLine);
    }

    @Override
    public int length() {
        return opcodes.length;
    }

    @Override
    public int opcodeAt(int index) {
        return opcodes[index];
    }

    @Override
    public int argAt(int index) {
        return args[index];
    }

    @Override
    public int unitOffsetAt(int index) {
        return units[index];
    }

    @Override
    public int indexOfUnitAt(int unit) {
        return unit >= 0 && unit < indexByUnit.length ? indexByUnit[unit] : -1;
    }

    /** The one place the two conventions meet: a traceback counts bytes, this array counts units. */
    @Override
    public int lastiAt(int index) {
        return 2 * units[index];
    }

    @Override
    public int lineAtLasti(int lasti) {
        // An offset from before the first instruction names the line the code object starts on, which
        // is what CPython's `PyCode_Addr2Line` answers for a frame that never executed anything.
        return lasti < 0 ? firstLine : lines.lineAtUnit(lasti / 2);
    }

    /** One line number per code unit, for the expectation file to compare against CPython's reading. */
    int[] lineByUnit() {
        return lines.lineByUnit();
    }

    /** The line table's entries, in the order it writes them, for the two position reads. */
    java.util.List<LineTable.Entry> entries() {
        return lines.entries();
    }

    /**
     * {@inheritDoc}
     *
     * The offset is measured from the unit after this instruction <em>and its whole cache</em>,
     * which is why the cache width appears here. A jump therefore lands one unit further along than
     * a naive {@code unit + 1 + arg}, and {@code dis} reports the same target it does.
     *
     * FOR_ITER is not special-cased: its {@code dis} target is the END_FOR that follows the loop
     * body, and the interpreter steps past that itself.
     */
    @Override
    public int jumpTargetAt(int index) {
        int opcode = opcodes[index];
        int target = units[index] + 1 + InlineCaches.of(opcode)
                + (Opcodes.jumpsBackward(opcode) ? -args[index] : args[index]);
        return indexOfUnitAt(target);
    }
}
