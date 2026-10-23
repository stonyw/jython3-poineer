package org.python.front.cpython314;

import java.util.ArrayList;
import java.util.List;

/**
 * CPython 3.14's {@code co_linetable}: which source range each code unit belongs to.
 *
 * <p>An entry is a first byte with the top bit set, followed by however many bytes the entry's own
 * form needs, and the low three bits of that first byte say how many <em>code units</em> the entry
 * covers — one to eight. Longer runs are emitted as several entries with the same location rather
 * than one wide entry, which is why consecutive units can repeat a line and why a reader must not
 * coalesce them. The seven forms are the codes 0 to 15 in CPython's {@code Objects/codeobject.c},
 * {@code advance_with_locations}: the short forms 0 to 9 fold a column pair into one following byte,
 * {@code ONE_LINE0} to {@code ONE_LINE2} (10 to 12) carry two unsigned columns and move the line by
 * 0, 1 or 2, {@code NO_COLUMNS} (13) carries a signed line delta and nothing else, {@code LONG} (14)
 * carries a signed line delta plus an end-line delta and two columns, and 15 names a unit with no
 * location at all — an inline cache, or the run past the last instruction.
 *
 * <p>Line deltas accumulate: the running line starts at {@code co_firstlineno} and only the forms
 * that name a delta move it, so a short entry repeats whatever line the previous one reached.
 *
 * <p>Two readings of the same entries are kept. {@code co_lines()} walks entries and merges the ones
 * that name the same line, and answers a line and nothing beside it, while {@code co_positions()} walks
 * <em>units</em> and repeats the covering entry's whole range — end line and both columns included — for
 * each of them. So the answers kept here are the entries themselves and the line per unit, and the widths
 * are what the two readings count on separately: a reader that merged entries here would lose the widths
 * the following entries are measured from. A {@code -1} in any field is CPython's own marker for a
 * location the entry does not name, which both Python methods print as {@code None}.
 */
final class LineTable {

    /**
     * One entry: the code unit it starts at, how many it reaches over, and the source range it names.
     *
     * <p>Measured in code units, not bytes — the width CPython's own {@code advance_with_locations}
     * reads from the first byte — because that is what the instructions are counted in here.
     */
    record Entry(int startUnit, int units, int line, int endLine, int column, int endColumn) {}

    /** The line of every code unit, and -1 for a unit that carries no location. */
    private final int[] lineByUnit;

    /** The entries themselves, in the order the table writes them. */
    private final List<Entry> entries = new ArrayList<>();

    LineTable(byte[] table, int firstLine, int totalUnits) {
        this.lineByUnit = new int[Math.max(totalUnits, 0)];
        java.util.Arrays.fill(this.lineByUnit, -1);
        int line = firstLine;
        int at = 0;
        int unit = 0;
        while (at < table.length) {
            int first = table[at] & 0xff;
            if ((first & 128) == 0) {
                throw MarshalFormatException.badData(
                        "line table byte " + at + " continues an entry that has already ended");
            }
            int code = (first >> 3) & 15;
            int width = (first & 7) + 1;
            int next = at + 1;
            int endLine;
            int column;
            int endColumn;
            if (code <= 9) {
                // A short form: the columns are the only thing the following byte carries, and the
                // line stays where it was. The code number names the top of the start column.
                int pair = readColumn(table, next);
                next++;
                column = code << 3 | (pair >> 4);
                endColumn = column + (pair & 15);
                endLine = line;
            } else if (code <= 12) {
                line += code - 10;
                column = readColumn(table, next);
                endColumn = readColumn(table, next + 1);
                next += 2;
                endLine = line;
            } else if (code == 13) {
                int[] delta = readSignedVarint(table, next);
                line += delta[0];
                next = delta[1];
                endLine = line;
                column = -1;
                endColumn = -1;
            } else if (code == 14) {
                int[] delta = readSignedVarint(table, next);
                line += delta[0];
                next = delta[1];
                int[] endDelta = readVarint(table, next);
                next = endDelta[1];
                // The two columns are written one greater than the numbers they mean.
                int[] start = readVarint(table, next);
                next = start[1];
                int[] end = readVarint(table, next);
                next = end[1];
                endLine = line + endDelta[0];
                column = start[0] - 1;
                endColumn = end[0] - 1;
            } else {
                // Code 15 keeps the running line where it is and says these units have no location.
                entries.add(new Entry(unit, width, -1, -1, -1, -1));
                fill(unit, width, -1);
                unit += width;
                at = next;
                continue;
            }
            entries.add(new Entry(unit, width, line, endLine, column, endColumn));
            fill(unit, width, line);
            unit += width;
            at = next;
        }
    }

    /** The line of the instruction that begins at {@code unit}, or -1 beyond the last entry. */
    int lineAtUnit(int unit) {
        return unit >= 0 && unit < lineByUnit.length ? lineByUnit[unit] : -1;
    }

    /** One entry per code unit, for the expectation file to compare against CPython's reading. */
    int[] lineByUnit() {
        return lineByUnit;
    }

    /**
     * The table's entries, as written.
     *
     * <p>Adjacent entries repeating a line are kept apart, which is what the table says; only
     * {@code co_lines()} merges them, and {@code co_positions()} never needs to.
     */
    List<Entry> entries() {
        return entries;
    }

    /**
     * Write the same line over the units one entry covers.
     *
     * <p>A table is not validated against the code array by CPython, and one hand-built code object's
     * table declares more units than it has, so the run is clamped rather than trusted.
     */
    private void fill(int unit, int width, int line) {
        for (int i = 0; i < width && unit + i < lineByUnit.length; i++) {
            lineByUnit[unit + i] = line;
        }
    }

    /** Read one of the single-byte columns, which are never a start-of-entry byte. */
    private static int readColumn(byte[] table, int at) {
        if (at >= table.length) {
            throw MarshalFormatException.badData("line table ends inside an entry");
        }
        return table[at] & 0xff;
    }

    /** CPython's {@code read_varint}: six bits per byte, continuation bit 0x40, least significant first. */
    private static int[] readVarint(byte[] table, int at) {
        int value = 0;
        int shift = 0;
        int next = at;
        while (true) {
            if (next >= table.length) {
                throw MarshalFormatException.badData("line table ends inside a varint");
            }
            int read = table[next++] & 0xff;
            value |= (read & 63) << shift;
            if ((read & 64) == 0) {
                return new int[] {value, next};
            }
            shift += 6;
        }
    }

    /** CPython's {@code read_signed_varint}: the low bit says the sign, and the rest is the magnitude. */
    private static int[] readSignedVarint(byte[] table, int at) {
        int[] read = readVarint(table, at);
        int unsigned = read[0];
        return new int[] {
            (unsigned & 1) == 1 ? -(unsigned >>> 1) : unsigned >>> 1, read[1]
        };
    }
}
