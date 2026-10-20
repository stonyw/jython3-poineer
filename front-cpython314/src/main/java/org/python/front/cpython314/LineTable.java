package org.python.front.cpython314;

/**
 * CPython 3.14's {@code co_linetable}: which source line each code unit belongs to.
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
 * Columns are absolute and are the part a traceback display reads, so they are stepped over here;
 * the line is all {@code tb_lineno} and {@code f_lineno} ask for.
 */
final class LineTable {

    /** The line of every code unit, and -1 for a unit that carries no location. */
    private final int[] lineByUnit;

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
            if (code <= 9) {
                // A short form: the columns are the only thing the following byte carries, and the
                // line stays where it was.
                next = readColumns(table, next, 1);
            } else if (code <= 12) {
                line += code - 10;
                next = readColumns(table, next, 2);
            } else if (code == 13) {
                int[] delta = readSignedVarint(table, next);
                line += delta[0];
                next = delta[1];
            } else if (code == 14) {
                int[] delta = readSignedVarint(table, next);
                line += delta[0];
                next = skipVarint(table, delta[1]); // the end line's delta from this one
                next = skipVarint(table, next); // the start column, less one
                next = skipVarint(table, next); // the end column, less one
            } else {
                // Code 15 keeps the running line where it is and says this unit has no location.
                fill(unit, width, -1);
                unit += width;
                at = next;
                continue;
            }
            fill(unit, width, line);
            unit += width;
            at = next;
        }
    }

    /** The line of the instruction that begins at {@code unit}, or -1 beyond the last entry. */
    int lineAtUnit(int unit) {
        return unit >= 0 && unit < lineByUnit.length ? lineByUnit[unit] : -1;
    }

    /** One entry per code unit, for the expectation file to compare against CPython's own reading. */
    int[] lineByUnit() {
        return lineByUnit;
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

    /** Skip {@code count} single-byte columns, which are never a start-of-entry byte. */
    private static int readColumns(byte[] table, int at, int count) {
        int next = at + count;
        if (next > table.length) {
            throw MarshalFormatException.badData("line table ends inside an entry");
        }
        return next;
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

    /** Step over one varint whose value belongs to the part of the entry a traceback never reads. */
    private static int skipVarint(byte[] table, int at) {
        return readVarint(table, at)[1];
    }
}
