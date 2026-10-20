package org.python.front.cpython314;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Each of the seven forms CPython 3.14 writes into {@code co_linetable}, read from a table built by
 * hand.
 *
 * <p>What a real code object's table says is measured against CPython's own reading by
 * {@link MarshalFixturesTest}, over the bytes {@code compile()} produced. That covers the common cases
 * and nothing else, so the forms a script in the fixture set never reach — a line running backwards, a
 * unit with no location at all, a table that outlives the code it belongs to — are made up here. The
 * encodings are CPython's {@code advance_with_locations} ({@code Objects/codeobject.c:1202-1255}) and
 * {@code write_location_info_entry} ({@code Python/assemble.c}); the first byte is
 * {@code 128 | (code << 3) | (units - 1)} and a varint carries six bits a byte with continuation bit
 * {@code 0x40}, least significant chunk first, signed by zig-zag.
 */
class LineTableTest {

    /** A short form with no line to move: it carries only a column pair, and the line stays put. */
    @Test
    void readsAShortFormWithoutMovingTheLine() {
        LineTable lines = new LineTable(table(0x80, 0x05, 0x80, 0x93), 7, 2);

        assertThat(lines.lineAtUnit(0)).isEqualTo(7);
        assertThat(lines.lineAtUnit(1)).isEqualTo(7);
        assertThat(lines.lineAtUnit(2)).as("past the last entry").isEqualTo(-1);
    }

    /** The ninth short form reaches the columns the first eight cannot, and still says no line delta. */
    @Test
    void readsTheWideColumnsOfTheNinthShortForm() {
        // code 9, one unit: 128 | 9<<3 is 0xc8, and its column byte reads as 9<<3 | 9 = 81.
        LineTable lines = new LineTable(table(0xc8, 0x93), 4, 1);

        assertThat(lines.lineAtUnit(0)).isEqualTo(4);
    }

    /**
     * The one-line forms are the only ones below {@code LONG} that move the line, and they move it by
     * 0, 1 or 2 — the code number less ten.
     */
    @Test
    void movesTheLineByTheCodeOfAOneLineForm() {
        LineTable lines =
                new LineTable(table(0xd0, 0x00, 0x08, 0xd9, 0x00, 0x04, 0xe1, 0x00, 0x04), 10, 5);

        // Three entries of one, two and two units: ONE_LINE0 holds the line, ONE_LINE1 with a
        // width-2 field moves it by one, ONE_LINE2 by two.
        assertThat(lines.lineAtUnit(0)).as("ONE_LINE0").isEqualTo(10);
        assertThat(lines.lineAtUnit(1)).as("ONE_LINE1").isEqualTo(11);
        assertThat(lines.lineAtUnit(2)).as("ONE_LINE1's second unit, which it covers itself")
                .isEqualTo(11);
        assertThat(lines.lineAtUnit(3)).as("ONE_LINE2").isEqualTo(13);
        assertThat(lines.lineAtUnit(4)).isEqualTo(13);
    }

    /** A line may run backwards, which is what the signed varint of these two forms is for. */
    @Test
    void readsALineDeltaThatRunsBackwards() {
        // NO_COLUMNS with -100 needs two varint bytes (0x49 0x03), and LONG with -1 one (0x03).
        LineTable lines = new LineTable(table(0xe8, 0x49, 0x03, 0xf0, 0x03, 0x01, 0x01, 0x01), 101, 2);

        assertThat(lines.lineAtUnit(0)).as("NO_COLUMNS, delta -100").isEqualTo(1);
        assertThat(lines.lineAtUnit(1)).as("LONG, delta -1").isEqualTo(0);
    }

    /** {@code LONG} spells four varints after its first byte, and only the first is a line. */
    @Test
    void readsTheThreeValuesALongFormCarriesBeyondItsLine() {
        // line delta +3 (0x06), end line +9 (0x09), column 40-1 (0x28), end column 120-1: 119 is
        // two varint bytes (0x77 carries the low six bits with the continuation set, 0x01 the rest).
        LineTable lines =
                new LineTable(table(0xf0, 0x06, 0x09, 0x28, 0x77, 0x01, 0x80, 0x00), 1, 2);

        assertThat(lines.lineAtUnit(0)).isEqualTo(4);
        assertThat(lines.lineAtUnit(1)).as("the entry after a long form starts where it said")
                .isEqualTo(4);
    }

    /** Code 15 marks a unit with no location and leaves the running line where the last entry left it. */
    @Test
    void marksAUnitThatCarriesNoLocation() {
        // ONE_LINE1 two units wide, then the no-location marker, then a short form.
        LineTable lines = new LineTable(table(0xd9, 0x00, 0x02, 0xf8, 0x80, 0x00), 20, 4);

        assertThat(lines.lineAtUnit(0)).isEqualTo(21);
        assertThat(lines.lineAtUnit(1)).as("the second unit of a wide entry").isEqualTo(21);
        assertThat(lines.lineAtUnit(2)).as("the marker's own unit").isEqualTo(-1);
        assertThat(lines.lineAtUnit(3)).as("and the line after it is the one it left behind")
                .isEqualTo(21);
    }

    /** One entry can name up to eight units — a run of inline caches, or a long instruction chain. */
    @Test
    void fillsEveryUnitAWideEntryCovers() {
        // ONE_LINE1 with the width field at its largest: 7 more units than the first.
        LineTable lines = new LineTable(table(0xdf, 0x00, 0x02), 3, 9);

        for (int unit = 0; unit < 8; unit++) {
            assertThat(lines.lineAtUnit(unit)).as("unit " + unit).isEqualTo(4);
        }
    }

    /**
     * A run of instructions on one line is written as several entries eight units wide, not as one
     * entry, so the same line repeats. CPython coalesces those repeats only in {@code co_lines()}, and
     * a reader that coalesced here would lose the widths the following entries are measured from.
     */
    @Test
    void keepsTheRepeatsThatASingleLineIsWrittenAs() {
        // Two short forms eight units apiece, then a one-line form two units wide.
        LineTable lines = new LineTable(table(0x87, 0x00, 0x87, 0x00, 0xd9, 0x00, 0x02), 5, 18);

        assertThat(lines.lineAtUnit(0)).isEqualTo(5);
        assertThat(lines.lineAtUnit(15)).as("the second repeat of the same line").isEqualTo(5);
        assertThat(lines.lineAtUnit(16)).as("the next entry starts where the last one ended")
                .isEqualTo(6);
    }

    /** A table is not checked against the code array, and a hand-built one can outrun it. */
    @Test
    void clampsATableThatOutrunsTheCodeItBelongsTo() {
        LineTable lines = new LineTable(table(0xdf, 0x00, 0x02), 3, 3);

        assertThat(lines.lineAtUnit(0)).isEqualTo(4);
        assertThat(lines.lineAtUnit(2)).isEqualTo(4);
        assertThat(lines.lineAtUnit(3)).as("declared, but there is no such unit").isEqualTo(-1);
    }

    /** The other direction: a table that stops short leaves the rest with no location. */
    @Test
    void leavesTheUnitsATableStopsBeforeWithoutALine() {
        LineTable lines = new LineTable(table(0x80, 0x00), 6, 5);

        assertThat(lines.lineAtUnit(0)).isEqualTo(6);
        assertThat(lines.lineAtUnit(1)).isEqualTo(-1);
    }

    /** An empty table is a code object with no locations at all, which is what a bare one reads as. */
    @Test
    void readsAnEmptyTableAsNoLocations() {
        LineTable lines = new LineTable(new byte[0], 1, 4);

        assertThat(lines.lineByUnit()).containsExactly(-1, -1, -1, -1);
    }

    /** Every entry starts with a byte whose top bit is set; one that does not is a broken stream. */
    @Test
    void refusesAByteThatContinuesAnEntryAlreadyEnded() {
        assertThatThrownBy(() -> new LineTable(table(0x80, 0x00, 0x02), 1, 4))
                .isInstanceOf(MarshalFormatException.class)
                .hasMessageContaining("continues an entry");
    }

    /** A varint that runs off the end of the table is refused rather than read as a line. */
    @Test
    void refusesATableThatEndsInsideAVarint() {
        assertThatThrownBy(() -> new LineTable(table(0xe8, 0x49), 1, 1))
                .isInstanceOf(MarshalFormatException.class)
                .hasMessageContaining("inside a varint");
    }

    /** A one-line form whose two column bytes are not both there is the same refusal. */
    @Test
    void refusesATableThatEndsInsideAnEntry() {
        assertThatThrownBy(() -> new LineTable(table(0xd0, 0x00), 1, 1))
                .isInstanceOf(MarshalFormatException.class)
                .hasMessageContaining("inside an entry");
    }

    private static byte[] table(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
