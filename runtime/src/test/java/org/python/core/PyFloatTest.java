package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code repr(float)}, measured against CPython 3.14.8.
 *
 * The interesting rows are the ones Java's own {@code Double.toString} would get wrong: it prints
 * {@code 4.9E-324} where the shortest round-tripping form is {@code 5e-324}, and it never uses the
 * {@code 1e+16} cut-off that Python's does.
 */
class PyFloatTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            0.0                             | 0.0
            -0.0                            | -0.0
            1.0                             | 1.0
            0.5                             | 0.5
            100.0                           | 100.0
            1e15                            | 1000000000000000.0
            1e16                            | 1e+16
            9999999999999999.0              | 1e+16
            1e-4                            | 0.0001
            0.0001                          | 0.0001
            1e-5                            | 1e-05
            0.00001                         | 1e-05
            -1e-5                           | -1e-05
            5E-324                          | 5e-324
            1e308                           | 1e+308
            1.7976931348623157E308          | 1.7976931348623157e+308
            1234567890123456789.0           | 1.2345678901234568e+18
            123456789012345678.0            | 1.2345678901234568e+17
            2.5e155                         | 2.5e+155
            1.5e-5                          | 1.5e-05
            0.3333333333333333              | 0.3333333333333333
            1e20                            | 1e+20
            1.2345678901234568e29           | 1.2345678901234568e+29
            2.000000000000001e16            | 2.000000000000001e+16
            """)
    void reprsEveryMeasuredDouble(String value, String expected) {
        assertThat(PyFloat.of(Double.parseDouble(value)).repr()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            NaN       | nan
            Infinity  | inf
            -Infinity | -inf
            """)
    void reprsTheSpecialValues(String value, String expected) {
        assertThat(PyFloat.of(Double.parseDouble(value)).repr()).isEqualTo(expected);
    }

    /**
     * {@code str(float)} is {@code repr(float)} in Python 3, and both differ from
     * {@code Double.toString} in layout, not just case.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1.0     | 1.0
            1e16    | 1e+16
            -0.0    | -0.0
            0.5     | 0.5
            """)
    void strEqualsRepr(String value, String expected) {
        PyFloat f = PyFloat.of(Double.parseDouble(value));
        assertThat(f.str()).isEqualTo(expected).isEqualTo(f.repr());
    }

    /**
     * The {@code ADD_DOT_0} flag belongs to the caller. A float's own repr sets it; the two parts of
     * a complex number do not, which is what makes {@code repr(1.0)} and the real part of
     * {@code repr(1+0j)} different strings.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1.0     | 1.0     | 1
            0.0     | 0.0     | 0
            -0.0    | -0.0    | -0
            1e16    | 1e+16   | 1e+16
            100.0   | 100.0   | 100
            0.5     | 0.5     | 0.5
            """)
    void dotZeroIsOptional(String value, String withDot, String without) {
        double v = Double.parseDouble(value);
        assertThat(PyFloat.format(v, true)).isEqualTo(withDot);
        assertThat(PyFloat.format(v, false)).isEqualTo(without);
    }

    /** Every repr has to read back as the same double, or the shortest-digit search is broken. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            0.1
            1e-300
            5E-324
            1.7976931348623157E308
            1234567890123456789.0
            0.3333333333333333
            2.5e155
            """)
    void reprsRoundTrip(String value) {
        double v = Double.parseDouble(value);
        assertThat(Double.parseDouble(PyFloat.of(v).repr())).isEqualTo(v);
    }
}
