package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code repr(str)}, {@code repr(bytes)} and UTF-8 encoding, measured against CPython 3.14.8.
 *
 * The strings below are written as backslash-u escapes rather than as literals, because half of
 * what is being tested — lone surrogates, separators, the byte 0x80 — does not survive being pasted
 * into source, and an escape is the only spelling that says exactly which code unit is meant.
 */
class PyReprTest {

    @ParameterizedTest
    @MethodSource("strings")
    void reprsStringsAsCpythonDoes(String input, String expected) {
        assertThat(PyStr.of(input).repr()).isEqualTo(expected);
    }

    static Stream<Arguments> strings() {
        return Stream.of(
                Arguments.of("", "''"),
                Arguments.of("abc", "'abc'"),
                Arguments.of("it's", "\"it's\""),
                Arguments.of("say \"hi\"", "'say \"hi\"'"),
                Arguments.of("both ' and \"", "'both \\' and \"'"),
                Arguments.of("back\\slash", "'back\\\\slash'"),
                Arguments.of("tab\ttab", "'tab\\ttab'"),
                Arguments.of("nl\nnl", "'nl\\nnl'"),
                Arguments.of("cr\rcr", "'cr\\rcr'"),
                Arguments.of("\u0000\u0001\u001f", "'\\x00\\x01\\x1f'"),
                Arguments.of("\u007f\u0080\u009f", "'\\x7f\\x80\\x9f'"),
                Arguments.of("caf\u00e9", "'caf\u00e9'"),
                Arguments.of("\u4e2d\u6587", "'\u4e2d\u6587'"),
                Arguments.of("\ud83d\ude00 emoji", "'\ud83d\ude00 emoji'"),
                Arguments.of("\ud800 lone", "'\\ud800 lone'"),
                Arguments.of("\udcff end", "'\\udcff end'"),
                Arguments.of("\u2028 sep", "'\\u2028 sep'"),
                Arguments.of("\u00a0 nbsp", "'\\xa0 nbsp'"),
                Arguments.of("\u200b zwsp", "'\\u200b zwsp'"),
                Arguments.of("\u0301 combining", "'\u0301 combining'"),
                Arguments.of("\ufeff bom", "'\\ufeff bom'"),
                Arguments.of("\uffff last", "'\\uffff last'"),
                Arguments.of("mixed \u0007 \u00e9 \udbea\udfcd", "'mixed \\x07 \u00e9 \\U0010abcd'"),
                Arguments.of("'", "\"'\""),
                Arguments.of("\"", "'\"'"),
                Arguments.of("'\"", "'\\'\"'"),
                Arguments.of("\"\"", "'\"\"'"),
                Arguments.of("''", "\"''\""),
                Arguments.of("\ud835\udd50 math", "'\ud835\udd50 math'"),
                Arguments.of("\ud800 \ud83d\ude00 mixed", "'\\ud800 \ud83d\ude00 mixed'"),
                Arguments.of("\u000b\u000c esc", "'\\x0b\\x0c esc'"));
    }

    @ParameterizedTest
    @MethodSource("byteStrings")
    void reprsBytesAsCpythonDoes(byte[] input, String expected) {
        assertThat(PyBytes.wrap(input).repr()).isEqualTo(expected);
    }

    static Stream<Arguments> byteStrings() {
        return Stream.of(
                Arguments.of(new byte[0], "b''"),
                Arguments.of(bytes("abc"), "b'abc'"),
                Arguments.of(bytes("it's"), "b\"it's\""),
                Arguments.of(bytes("say \"hi\""), "b'say \"hi\"'"),
                Arguments.of(bytes("both ' and \""), "b'both \\' and \"'"),
                Arguments.of(bytes("back\\slash"), "b'back\\\\slash'"),
                Arguments.of(new byte[] {0, (byte) 0xff, 0x1b}, "b'\\x00\\xff\\x1b'"),
                Arguments.of(range(0, 32), "b'\\x00\\x01\\x02\\x03\\x04\\x05\\x06\\x07\\x08\\t"
                        + "\\n\\x0b\\x0c\\r\\x0e\\x0f\\x10\\x11\\x12\\x13\\x14\\x15\\x16\\x17"
                        + "\\x18\\x19\\x1a\\x1b\\x1c\\x1d\\x1e\\x1f'"),
                Arguments.of(range(0x78, 0x82), "b'xyz{|}~\\x7f\\x80\\x81'"),
                Arguments.of("caf\u00e9".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "b'caf\\xc3\\xa9'"),
                Arguments.of(new byte[] {(byte) 0xc0, (byte) 0x80}, "b'\\xc0\\x80'"),
                Arguments.of(new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                        "b'\\xed\\xa0\\x80'"),
                Arguments.of(new byte[] {'\n', '\t', '\r', '\'', '"', '\\'},
                        "b'\\n\\t\\r\\'\"\\\\'"));
    }

    /** Latin-1 is the only charset where each of these source characters is the one byte meant. */
    private static byte[] bytes(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }

    private static byte[] range(int from, int to) {
        byte[] out = new byte[to - from];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (from + i);
        }
        return out;
    }

    /**
     * str reprs quote with {@code '} unless the text holds a {@code '} and no {@code "}; that one
     * branch is what makes the pairs of quote rows above worth measuring.
     */
    @Test
    void encodesTheWaySurrogatepassDoes() {
        assertThat(toHex(PyStr.of("abc").toUtf8())).isEqualTo("616263");
        assertThat(toHex(PyStr.of("caf\u00e9").toUtf8())).isEqualTo("636166c3a9");
        assertThat(toHex(PyStr.of("\u4e2d\u6587").toUtf8())).isEqualTo("e4b8ade69687");
    }

    /**
     * Encoding must accept a lone surrogate, where {@code String.getBytes(UTF_8)} silently writes a
     * {@code ?}. This is the encoder {@code marshal} uses, so getting it wrong corrupts a constant.
     */
    @Test
    void encodesLoneSurrogatesAsThreeBytes() {
        assertThat(toHex(PyStr.of("\ud800").toUtf8())).isEqualTo("eda080");
        assertThat(toHex(PyStr.of("\udcff").toUtf8())).isEqualTo("edb3bf");
        assertThat(toHex(PyStr.of("\ud800 lone").toUtf8())).isEqualTo("eda080206c6f6e65");
    }

    @Test
    void encodesAstralCharactersAsFourBytes() {
        assertThat(toHex(PyStr.of("\ud83d\ude00").toUtf8())).isEqualTo("f09f9880");
        assertThat(toHex(PyStr.of("\ud835\udd50").toUtf8())).isEqualTo("f09d9590");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
