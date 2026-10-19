package org.python.front.cpython314;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code co_exceptiontable} blob, decoded.
 *
 * <p>CPython 3.14 handles exceptions at zero cost: there is no {@code SETUP_FINALLY} opcode any more, and
 * the only record that a stretch of bytecode is guarded is one row of this table. It is consulted when
 * something has already gone wrong, which is why nothing else in the runtime has to know it exists — and
 * why the decoding lives here, behind {@link org.python.core.PyCode}, rather than in the interpreter (D2).
 *
 * <p>The format is four numbers per row, each a base-64 varint: the code unit where the guarded range
 * starts, its length in code units, the code unit its handler begins at, and the stack depth the handler
 * wants with {@code push-lasti} in its low bit. The high bit of a row's first byte marks the row's start,
 * which is what lets a reader binary-search a table of variable-width entries; a linear reader still
 * checks it, because a walk that slips by one byte produces a table that looks well-formed and says the
 * wrong thing.
 *
 * <p>Offsets stay in code units, which is what CPython's own lookup compares against
 * ({@code INSTR_OFFSET() - 1} counts units, not bytes). {@code dis} doubles them to print byte offsets,
 * and only the fixture renderer here cares about that.
 */
final class ExceptionTable {

    /** A row's first byte carries this as its start marker; it is not part of the value. */
    private static final int START_BIT = 0x80;
    /** Every byte of a value but the last carries this to say another byte follows. */
    private static final int CONTINUATION_BIT = 0x40;
    private static final int PAYLOAD = 0x3f;

    /**
     * One row: the half-open range of code units it covers, where its handler starts, what the value stack
     * holds when the handler runs, and whether the offset of the raising instruction is pushed below the
     * exception so that a {@code RERAISE} can put it back.
     */
    record Entry(int start, int end, int target, int depth, boolean pushLasti) {

        boolean covers(int unit) {
            return start <= unit && unit < end;
        }
    }

    private static final ExceptionTable EMPTY = new ExceptionTable(List.of());

    private final List<Entry> entries;

    private ExceptionTable(List<Entry> entries) {
        this.entries = entries;
    }

    static ExceptionTable parse(byte[] table) {
        if (table.length == 0) {
            return EMPTY;
        }
        Reader reader = new Reader(table);
        List<Entry> rows = new ArrayList<>();
        while (!reader.atEnd()) {
            int start = reader.value(true);
            int size = reader.value(false);
            int target = reader.value(false);
            int depthAndLasti = reader.value(false);
            rows.add(new Entry(
                    start, start + size, target, depthAndLasti >> 1, (depthAndLasti & 1) != 0));
        }
        return new ExceptionTable(List.copyOf(rows));
    }

    /**
     * The row covering {@code unit}, or null where the code is not guarded.
     *
     * <p>Linear, over rows in the order they were written, which is ascending by start. CPython
     * binary-searches a table once it holds forty-odd rows; a lookup only happens on the way out of a
     * failed call, and no script here has needed the difference yet.
     */
    Entry handlerFor(int unit) {
        for (Entry entry : entries) {
            if (entry.covers(unit)) {
                return entry;
            }
        }
        return null;
    }

    List<Entry> entries() {
        return entries;
    }

    /** Reads the base-64 varints of one table, a row at a time. */
    private static final class Reader {

        private final byte[] table;
        private int at;

        Reader(byte[] table) {
            this.table = table;
        }

        boolean atEnd() {
            return at >= table.length;
        }

        /**
         * One value. {@code startsRow} marks the first value of a row, the only place the start bit may
         * appear.
         */
        int value(boolean startsRow) {
            int first = table[at++] & 0xff;
            if (startsRow != ((first & START_BIT) != 0)) {
                throw MarshalFormatException.badData(
                        "exception table row at byte " + (at - 1) + " has no start marker");
            }
            int value = first & PAYLOAD;
            int b = first;
            while ((b & CONTINUATION_BIT) != 0) {
                if (at >= table.length) {
                    throw MarshalFormatException.badData("exception table ends inside a value");
                }
                b = table[at++] & 0xff;
                value = (value << 6) | (b & PAYLOAD);
            }
            return value;
        }
    }
}
