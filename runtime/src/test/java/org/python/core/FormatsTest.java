package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The default alignment of the format mini-language, measured against CPython 3.14.8.
 *
 * <p>{@code [[fill]align][sign][#][0][width][grouping][.precision][type]} never says what a width with no
 * alignment letter means, and the answer is not in the text the value produced: CPython asks the value's
 * own type. A number right-justifies and a text left-justifies even when that text is made of digits — and
 * 3.14's compiler rewrites {@code "%20s"} into a {@code FORMAT_WITH_SPEC} carrying the bare spec
 * {@code '20'}, so a {@code %}-formatted table reaches this code as a {@code str} of digits and a width,
 * which is exactly the case a guess about the body gets wrong.
 */
class FormatsTest {

    /** {@code format(value, spec)} — the half the interpreter and {@code %} both delegate to. */
    private static String format(PyObject value, String spec) {
        return Formats.apply(value, spec);
    }

    /** A width alone pads by the type of the value, and an alignment letter written out beats it. */
    @Test
    void aWidthWithNoAlignmentIsAnsweredByTheTypeOfTheValue() {
        assertThat(format(PyStr.of("12"), "6")).isEqualTo("12    ");
        assertThat(format(PyInt.of(12), "6")).isEqualTo("    12");
        assertThat(format(PyBool.TRUE, "6")).isEqualTo("     1");
        assertThat(format(PyFloat.of(1.5), "6")).isEqualTo("   1.5");

        assertThat(format(PyStr.of("12"), "<6")).isEqualTo("12    ");
        assertThat(format(PyInt.of(12), "<6")).isEqualTo("12    ");
        assertThat(format(PyInt.of(12), ">10")).isEqualTo("        12");
        assertThat(format(PyStr.of("x"), "^5")).isEqualTo("  x  ");
        assertThat(format(PyFloat.of(1.5), "^8")).isEqualTo("  1.5   ");
    }

    /**
     * The {@code 0} flag, which is not a fill character where it is written.
     *
     * <p>For a number it is the {@code '='} alignment and so pads after the sign, which is why
     * {@code format(-42, '05')} is {@code '-0042'} and not {@code '0-42'}; for a text it stays an ordinary
     * fill under the left alignment that text got from the rule above.
     */
    @Test
    void aZeroBeforeAWidthPadsAfterASignAndBeforeNothingElse() {
        assertThat(format(PyInt.of(-42), "05")).isEqualTo("-0042");
        assertThat(format(PyInt.of(42), "05")).isEqualTo("00042");
        assertThat(format(PyInt.of(12), "06")).isEqualTo("000012");
        assertThat(format(PyStr.of("12"), "06")).isEqualTo("120000");
        assertThat(format(PyInt.of(12), "5")).isEqualTo("   12");
    }
}
