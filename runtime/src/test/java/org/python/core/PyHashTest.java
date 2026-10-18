package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Numeric hashing, measured against CPython 3.14.8.
 *
 * Every expected value below is the output of {@code hash()} in that interpreter, not a
 * calculation from this file. Text and bytes are absent on purpose: CPython seeds SipHash per
 * process, so no committed number for them could ever be right, and this runtime hashes them
 * deterministically instead.
 */
class PyHashTest {

    private static final BigInteger TWO_61 = BigInteger.ONE.shiftLeft(61);
    private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger TWO_70 = BigInteger.ONE.shiftLeft(70);

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1                 | 1
            0                 | 0
            -5                | -5
            -1                | -2
            -2                | -2
            256               | 256
            257               | 257
            """)
    void hashesIntsThatFitALong(String value, long expected) {
        assertThat(PyInt.of(Long.parseLong(value)).hash()).isEqualTo(expected);
    }

    @Test
    void hashesIntsBeyondALong() {
        assertThat(PyInt.of(new BigInteger("100000000000000000000")).hash())
                .isEqualTo(848750603811160107L);
        assertThat(PyInt.of(TWO_61.subtract(BigInteger.ONE)).hash()).isZero();
        assertThat(PyInt.of(TWO_61).hash()).isEqualTo(1);
        assertThat(PyInt.of(TWO_61.negate()).hash()).isEqualTo(-2);
        assertThat(PyInt.of(TWO_63.negate()).hash()).isEqualTo(-4);
        assertThat(PyInt.of(TWO_64.add(BigInteger.valueOf(7))).hash()).isEqualTo(15);
        assertThat(PyInt.of(new BigInteger("123456789012345678901234567890")).hash())
                .isEqualTo(248789772095949448L);
        assertThat(PyInt.of(BigInteger.TEN.pow(20).negate()).hash())
                .isEqualTo(-848750603811160107L);
    }

    /**
     * The two clauses of CPython's {@code int_hash} have to agree: it short-circuits values that fit
     * one 30-bit digit, and the general path is what the tests above measure.
     */
    @Test
    void smallAndLargeFormsOfTheSameNumberHashAlike() {
        for (long v : new long[] {0, 1, -1, 5, -5, 256, 257, 1L << 30, (1L << 30) - 1}) {
            assertThat(PyInt.of(BigInteger.valueOf(v)).hash()).isEqualTo(PyInt.of(v).hash());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            0.0        | 0
            -0.0       | 0
            1.0        | 1
            0.5        | 1152921504606846976
            1e20       | 848750603811160107
            1e308      | 156575653125701
            5E-324     | 16777216
            Infinity   | 314159
            -Infinity  | -314159
            3.14159    | 326484311674566659
            -2.5       | -1152921504606846978
            1e-300     | 482449582752280463
            0.1        | 230584300921369408
            -1e20      | -848750603811160107
            """)
    void hashesFloats(String value, long expected) {
        assertThat(PyFloat.of(Double.parseDouble(value)).hash()).isEqualTo(expected);
    }

    /** {@code hash(x) == hash(int(x))} whenever the float is integral — the whole point of the loop. */
    @Test
    void integralFloatsHashAsIntegers() {
        assertThat(PyFloat.of(Math.pow(10, 20)).hash())
                .isEqualTo(PyInt.of(BigInteger.TEN.pow(20)).hash());
        assertThat(PyFloat.of(Math.pow(2, 61)).hash()).isEqualTo(PyInt.of(TWO_61).hash());
        assertThat(PyFloat.of(1.0).hash()).isEqualTo(PyInt.of(1).hash());
        assertThat(PyFloat.of(-0.0).hash()).isEqualTo(PyInt.of(0).hash());
    }

    /** CPython hashes NaN by object address, so any answer that is not -1 is acceptable here. */
    @Test
    void hashesNaNWithoutRaising() {
        assertThat(PyFloat.of(Double.NaN).hash()).isNotEqualTo(-1);
    }

    /**
     * {@code 10.0**20} and {@code 1e20} are the same double; a dict written with either spelling has
     * to find the other's key.
     */
    @Test
    void hashOfAFloatEqualsThatOfAnEqualInt() {
        assertThat(PyFloat.of(2.0).hash()).isEqualTo(PyInt.of(2).hash());
        assertThat(PyFloat.of(Math.pow(2, 61)).hash()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("complexParts")
    void hashesComplexes(double real, double imaginary, long expected) {
        assertThat(PyComplex.of(real, imaginary).hash()).isEqualTo(expected);
    }

    static List<Arguments> complexParts() {
        double inf = Double.POSITIVE_INFINITY;
        return List.of(
                Arguments.of(1.0, 2.0, 2000007L),
                Arguments.of(0.0, 0.0, 0L),
                Arguments.of(1.5, -2.5, -2305843009215693957L),
                Arguments.of(1e16, 1e15, 3888820019684212736L),
                Arguments.of(0.1, 0.2, 1614090106475185856L),
                Arguments.of(inf, 3.0, 3314168L),
                Arguments.of(0.0, -0.0, 0L),
                Arguments.of(-1.0, 0.0, -2L),
                Arguments.of(1e308, 1e308, 9002326838637089876L));
    }

    /**
     * The imaginary part is scaled and <em>added</em>, not mixed in. {@code complex(1, 2)} happens to
     * give the same answer either way, which is why an XOR here can survive for a while.
     */
    @Test
    void complexHashCombinesThePartsAdditively() {
        assertThat(PyComplex.of(1.0, 2.0).hash())
                .isEqualTo(PyHash.avoidMinusOne(1 + PyHash.IMAG * 2));
        assertThat(PyComplex.of(1e16, 1e15).hash())
                .isNotEqualTo(PyHash.avoidMinusOne(
                        PyHash.floatHash(1e16) ^ (PyHash.IMAG * PyHash.floatHash(1e15))));
    }

    @Test
    void hashesTuples() {
        assertThat(PyTuple.of().hash()).isEqualTo(5740354900026072187L);
        assertThat(PyTuple.of(PyInt.of(1)).hash()).isEqualTo(-6644214454873602895L);
        assertThat(PyTuple.of(PyInt.of(1), PyInt.of(2), PyInt.of(3)).hash())
                .isEqualTo(529344067295497451L);
        assertThat(PyTuple.of(PyInt.of(1), PyFloat.of(2.0), PyBool.TRUE).hash())
                .isEqualTo(3313134723150563520L);
        assertThat(PyTuple.of(PyInt.of(TWO_70), PyInt.of(5)).hash())
                .isEqualTo(1705602459818329402L);
        assertThat(PyTuple.of(PyInt.of(1), PyTuple.of()).hash())
                .isEqualTo(2500886146856312502L);
        assertThat(PyTuple.of(PyInt.of(1), PyInt.of(2), PyInt.of(3), PyInt.of(4), PyInt.of(5),
                PyInt.of(6), PyInt.of(7), PyInt.of(8), PyInt.of(9), PyInt.of(10)).hash())
                .isEqualTo(9109727825473806502L);
    }

    /** A tuple's hash is 64-bit, so the -1 that means "error" has to be steered around. */
    @Test
    void neverReturnsMinusOne() {
        assertThat(PyHash.intHash(BigInteger.valueOf(-1))).isEqualTo(-2);
        assertThat(PyHash.avoidMinusOne(-1)).isEqualTo(-2);
        assertThat(PyHash.avoidMinusOne(0)).isZero();
    }

    /**
     * A slice folds its three parts the way a tuple does but leaves out the length term, so
     * {@code hash(slice(1, 2, 3))} and {@code hash((1, 2, 3))} are different numbers.
     */
    @Test
    void hashesSlices() {
        assertThat(slice(1, 2, 3).hash()).isEqualTo(-2340833382717974474L);
        assertThat(slice(0, 0, 0).hash()).isEqualTo(140260061923537301L);
        assertThat(slice(-1, -2, -3).hash()).isEqualTo(-4110487523571942366L);
        assertThat(slice(1, 2, 3).hash()).isNotEqualTo(PyTuple.of(
                PyInt.of(1), PyInt.of(2), PyInt.of(3)).hash());
    }

    private static PySlice slice(int start, int stop, int step) {
        return PySlice.of(PyInt.of(start), PyInt.of(stop), PyInt.of(step));
    }

    /** {@code hash(None)} is an address in CPython, but a slice holding one still has to hash. */
    @Test
    void singletonsAreHashable() {
        assertThat(PyNone.INSTANCE.hash()).isNotEqualTo(-1);
        assertThat(PyEllipsis.INSTANCE.hash()).isNotEqualTo(-1);
        assertThat(PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE).hash())
                .isNotEqualTo(-1);
    }

    /** A tuple is only hashable if every element is; an unhashable dict has to be refused. */
    @Test
    void refusesToHashAnUnhashableElement() {
        assertThatThrownBy(() -> PyTuple.of(PyDict.empty()).hash())
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refusal -> assertThat(refusal.value().exceptionType())
                                .isEqualTo(ExceptionTypes.TYPE_ERROR))
                .hasMessageContaining("unhashable type: 'dict'");
    }

    /** A slice is the only value in the numeric-free corner of the fixture set, so check its bits. */
    @Test
    void equalSlicesHashAlike() {
        assertThat(slice(5, 6, 7).hash()).isEqualTo(slice(5, 6, 7).hash());
        assertThat(slice(5, 6, 7)).isEqualTo(slice(5, 6, 7));
    }
}
