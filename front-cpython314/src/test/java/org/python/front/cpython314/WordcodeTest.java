package org.python.front.cpython314;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The parts of walking a wordcode array that a marshal stream cannot exercise, because the fixtures
 * only ever hand over bytes CPython itself compiled.
 *
 * What a real code object decodes to is measured against {@code dis} by {@link MarshalFixturesTest}.
 * This file covers the rest: cache bytes that happen to look like instructions, an EXTENDED_ARG chain
 * long enough to overflow, and the refusals. The expectations come from {@code dis._unpack_opargs}
 * run over the same bytes.
 */
class WordcodeTest {

    /** Opcode numbers from CPython 3.14's {@code dis.opmap}. */
    private static final int EXTENDED_ARG = InlineCaches.EXTENDED_ARG;
    private static final int BINARY_OP = 44;
    private static final int LOAD_GLOBAL = 92;
    private static final int RETURN_VALUE = 35;

    /** One code unit: the opcode byte, then its argument byte. */
    private static int unit(int opcode, int oparg) {
        return (opcode << 8) | oparg;
    }

    private static byte[] code(int... units) {
        byte[] out = new byte[units.length * 2];
        for (int i = 0; i < units.length; i++) {
            out[2 * i] = (byte) ((units[i] >> 8) & 0xff);
            out[2 * i + 1] = (byte) (units[i] & 0xff);
        }
        return out;
    }

    /**
     * A cache unit is a data word and the only way to know that is the table, so the five bytes
     * behind {@code BINARY_OP} are deliberately filled with a valid instruction. {@code dis} reads
     * the three that follow as separate instructions; the five in between are never seen.
     */
    @Test
    void skipsTheCacheUnitsBehindAnOpcode() {
        int caches = InlineCaches.of(BINARY_OP);
        assertThat(caches).as("BINARY_OP carries caches in 3.14").isEqualTo(5);
        int[] units = new int[1 + caches + 3];
        units[0] = unit(BINARY_OP, 0);
        Arrays.fill(units, 1, units.length, unit(RETURN_VALUE, 0));

        Wordcode decoded = Wordcode.decode(code(units));

        assertThat(decoded.length()).isEqualTo(4);
        assertThat(decoded.opcodeAt(0)).isEqualTo(BINARY_OP);
        assertThat(decoded.unitOffsetAt(1)).isEqualTo(1 + caches);
        assertThat(decoded.unitOffsetAt(3)).isEqualTo(units.length - 1);
        // dis reports byte offsets, which are these units times two: 12, 14, 16.
        assertThat(decoded.unitOffsetAt(1) * 2).isEqualTo(12);
    }

    /**
     * EXTENDED_ARG is both a prefix and, as far as {@code dis} is concerned, an instruction: each
     * prefix unit is reported with the value it contributed before shifting, and only the
     * instruction at the end of the chain carries the whole folded argument.
     */
    @Test
    void foldsAChainOfExtendedArgs() {
        Wordcode decoded = Wordcode.decode(code(
                unit(EXTENDED_ARG, 1),
                unit(EXTENDED_ARG, 2),
                unit(LOAD_GLOBAL, 3)));

        assertThat(decoded.length()).isEqualTo(3);
        assertThat(decoded.argAt(0)).isEqualTo(1);
        assertThat(decoded.argAt(1)).isEqualTo(258);
        assertThat(decoded.argAt(2)).isEqualTo(66051);
        assertThat(decoded.unitOffsetAt(2)).isEqualTo(2);
    }

    /**
     * Past 32 bits the argument wraps negative — CPython's {@code dis} emulates a signed C int there
     * and Java's {@code int} simply does it, which is why these two chains agree on {@code -256},
     * {@code -129} and {@code -33024} rather than on the unsigned values the bytes spell.
     */
    @Test
    void foldsAChainThatOverflowsASignedInt() {
        Wordcode decoded = Wordcode.decode(code(
                unit(EXTENDED_ARG, 0xff),
                unit(EXTENDED_ARG, 0xff),
                unit(EXTENDED_ARG, 0xff),
                unit(EXTENDED_ARG, 0x7f),
                unit(LOAD_GLOBAL, 0)));

        assertThat(decoded.argAt(0)).isEqualTo(255);
        assertThat(decoded.argAt(1)).isEqualTo(65535);
        assertThat(decoded.argAt(2)).isEqualTo(16777215);
        assertThat(decoded.argAt(3)).isEqualTo(-129);
        assertThat(decoded.argAt(4)).isEqualTo(-33024);
    }

    /** A jump argument is a byte offset, so the walk has to be reversible from unit to instruction. */
    @Test
    void mapsAUnitOffsetBackToItsInstruction() {
        int caches = InlineCaches.of(BINARY_OP);
        int[] units = new int[1 + caches + 1];
        units[0] = unit(BINARY_OP, 0);
        Arrays.fill(units, 1, 1 + caches, unit(RETURN_VALUE, 0));
        units[units.length - 1] = unit(RETURN_VALUE, 0);
        Wordcode decoded = Wordcode.decode(code(units));

        assertThat(decoded.indexOfUnitAt(0)).isZero();
        assertThat(decoded.indexOfUnitAt(1)).as("a cache unit starts no instruction").isEqualTo(-1);
        assertThat(decoded.indexOfUnitAt(units.length - 1)).isEqualTo(1);
        assertThat(decoded.indexOfUnitAt(-1)).isEqualTo(-1);
        assertThat(decoded.indexOfUnitAt(units.length)).as("one past the end").isEqualTo(-1);
        assertThat(decoded.indexOfUnitAt(1000)).isEqualTo(-1);
    }

    /** Wordcode is two bytes a unit, with no partial unit at the end. */
    @Test
    void refusesACodeArrayOfOddLength() {
        assertThatThrownBy(() -> Wordcode.decode(new byte[] {RETURN_VALUE & 0xff}))
                .isInstanceOf(MarshalFormatException.class)
                .hasMessageContaining("odd length");
    }

    @Test
    void decodesAnEmptyCodeArray() {
        Wordcode decoded = Wordcode.decode(new byte[0]);
        assertThat(decoded.length()).isZero();
        assertThat(decoded.indexOfUnitAt(0)).isEqualTo(-1);
    }

    /** A no-argument opcode still carries an argument byte on the wire, and CPython always writes 0. */
    @Test
    void readsAnAbsentArgumentAsZero() {
        Wordcode decoded = Wordcode.decode(code(unit(RETURN_VALUE, 0)));
        assertThat(decoded.argAt(0)).isZero();
    }
}
