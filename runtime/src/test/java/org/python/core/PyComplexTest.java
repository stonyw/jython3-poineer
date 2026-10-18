package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code repr(complex)}, measured against CPython 3.14.8. */
class PyComplexTest {

    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double NAN = Double.NaN;

    @ParameterizedTest
    @MethodSource("cases")
    void reprsAsCpythonDoes(double real, double imaginary, String expected) {
        assertThat(PyComplex.of(real, imaginary).repr()).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(0.0, 0.0, "0j"),
                Arguments.of(0.0, -0.0, "-0j"),
                Arguments.of(-0.0, 0.0, "(-0+0j)"),
                Arguments.of(-0.0, -0.0, "(-0-0j)"),
                Arguments.of(0.0, 1.0, "1j"),
                Arguments.of(-0.0, -1.0, "(-0-1j)"),
                Arguments.of(1.0, 2.0, "(1+2j)"),
                Arguments.of(-1.0, 0.0, "(-1+0j)"),
                Arguments.of(1.5, -2.5, "(1.5-2.5j)"),
                Arguments.of(INF, 1.0, "(inf+1j)"),
                Arguments.of(-INF, 1.0, "(-inf+1j)"),
                Arguments.of(NAN, 1.0, "(nan+1j)"),
                Arguments.of(1.0, INF, "(1+infj)"),
                Arguments.of(1.0, -INF, "(1-infj)"),
                Arguments.of(1.0, NAN, "(1+nanj)"),
                Arguments.of(1e16, 1e15, "(1e+16+1000000000000000j)"),
                Arguments.of(0.5, 1e-5, "(0.5+1e-05j)"),
                Arguments.of(Math.pow(2, 53), 1.0, "(9007199254740992+1j)"),
                Arguments.of(1e308, -5e-324, "(1e+308-5e-324j)"),
                Arguments.of(1.0, -0.0, "(1-0j)"),
                Arguments.of(-1.0, -2.0, "(-1-2j)"));
    }

    /**
     * A real zero is hidden — but a <em>negative</em> real zero is not, because suppressing it would
     * lose the sign that {@code -0j} and {@code (-0-0j)} are the only way to spell apart.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            0.0  | 5.0 | 5j
            -0.0 | 5.0 | (-0+5j)
            """)
    void hidesOnlyAPositiveRealZero(double real, double imaginary, String expected) {
        assertThat(PyComplex.of(real, imaginary).repr()).isEqualTo(expected);
    }

    /** The imaginary part always carries a sign, which is why a positive one gets a {@code +}. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1.0 |  2.0 | (1+2j)
            1.0 | -2.0 | (1-2j)
            1.0 | -0.0 | (1-0j)
            """)
    void imaginaryPartKeepsItsSign(double real, double imaginary, String expected) {
        assertThat(PyComplex.of(real, imaginary).repr()).isEqualTo(expected);
    }

    /**
     * The parts are not float reprs: {@code repr(1.0)} is {@code 1.0} but the same value inside a
     * complex is {@code 1}.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1.0  | 1.0  | (1+1j)
            1e16 | 0.5  | (1e+16+0.5j)
            0.0  | 1e16 | 1e+16j
            """)
    void partsDropTheTrailingZero(double real, double imaginary, String expected) {
        assertThat(PyComplex.of(real, imaginary).repr()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1.0 | 2.0
            0.0 | 0.0
            1.5 | -2.5
            """)
    void comparesByParts(double real, double imaginary) {
        PyComplex a = PyComplex.of(real, imaginary);
        PyComplex b = PyComplex.of(real, imaginary);
        assertThat(a).isEqualTo(b);
        assertThat(a.eq(PyFloat.of(real))).isEqualTo(imaginary == 0.0);
    }

    /** {@code 1 == 1.0 == (1+0j)} has to hold in a dict, so the three hashes must agree. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1.0   | 1
            2.0   | 2
            -5.0  | -5
            1e20  | 100000000000000000000
            """)
    void hashesLikeTheEqualInt(double value, String asInt) {
        long expected = PyInt.of(new java.math.BigInteger(asInt)).hash();
        assertThat(PyFloat.of(value).hash()).isEqualTo(expected);
        assertThat(PyComplex.of(value, 0.0).hash()).isEqualTo(expected);
    }
}
