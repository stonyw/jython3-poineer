package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The translation layer's measured answers, and the two differences that are still differences.
 *
 * <p>The corpus script {@code m6a/corpus/17_re_unicode_classes.py} pins the same answers through the Python
 * surface; this file pins them at the seam, where a change to the spelling of a character class is visible
 * without an interpreter in the way. The divergences are in {@code docs/re-java-regex-divergences.md}; the
 * two pinned here are the ones a fix would have to come back and rewrite.
 */
class SreRegexDivergenceTest {

    private static Pattern compile(String source) {
        return SreRegex.compile(source, 0, false, Map.of());
    }

    private static Pattern compile(String source, int flags) {
        return SreRegex.compile(source, flags, false, Map.of());
    }

    private static boolean matches(String source, String text) {
        return compile(source).matcher(text).find();
    }

    @Test
    void unicodeWordIsLettersAndNumbersOnly() {
        // Java's UNICODE_CHARACTER_CLASS `\w` would add the combining mark and the connector punctuation.
        assertThat(matches("\\w", "\u0301")).isFalse();
        assertThat(matches("\\w", "\u203f")).isFalse();
        assertThat(matches("\\w", "\u2160")).isTrue();
        assertThat(matches("\\w", "\u2460")).isTrue();
        assertThat(matches("\\W", "\u0301")).isTrue();
        assertThat(matches("[\\W]", "\u0301")).isTrue();
        assertThat(matches("[\\S]", "\t")).isFalse();
        assertThat(matches("[\\S]", "\u001c")).isFalse();
    }

    @Test
    void unicodeDigitIsNdBeyondFullwidth() {
        assertThat(matches("\\d", "\uff10")).isTrue();
        assertThat(matches("\\d", "\u0660")).isTrue();
        assertThat(matches("\\D", "\uff10")).isFalse();
    }

    @Test
    void unicodeSpaceIsWhiteSpacePlusTheFourC0Separators() {
        assertThat(matches("\\s", "\u001c")).isTrue();
        assertThat(matches("\\s", "\u0085")).isTrue();
        assertThat(matches("\\s", "\u2028")).isTrue();
        assertThat(matches("\\s", "\u180e")).isFalse();
    }

    @Test
    void wordBoundaryUsesThePythonWordSet() {
        assertThat(matches("a\\b", "a\u0301")).isTrue();
        assertThat(matches("\\b\u0301", "a\u0301")).isTrue();
        assertThat(matches("\\Ba", "a")).isFalse();
        assertThat(matches("a\\Bb", "ab")).isTrue();
    }

    @Test
    void asciiFlagAndScopedAsciiChangeTheTables() {
        assertThat(matches("\\d", "\uff10")).isTrue();
        assertThat(SreRegex.compile("\\d", SreRegex.ASCII, false, Map.of())
                .matcher("\uff10").find()).isFalse();
        assertThat(matches("\\w(?a:\\W)\\w", "\u00e0\u00e0\u00e0")).isTrue();
        assertThat(SreRegex.compile("\\W(?u:\\w)", SreRegex.ASCII, false, Map.of())
                .matcher("\u00e0\u00e0").find()).isTrue();
    }

    @Test
    void octalEscapesAreCharactersNotGroupReferences() {
        assertThat(matches("[\\41]", "!")).isTrue();
        assertThat(matches("[\\101-\\103]+", "ABC")).isTrue();
        assertThat(matches("\\277", "\u00bf")).isTrue();
        assertThat(matches("\\0", "\u0000")).isTrue();
    }

    @Test
    void multilineAnchorsKnowOnlyNewline() {
        assertThat(count("^", "a\rb", SreRegex.MULTILINE)).isEqualTo(1);
        assertThat(count("^", "a\nb", SreRegex.MULTILINE)).isEqualTo(2);
        assertThat(count("$", "a\rb", SreRegex.MULTILINE)).isEqualTo(1);
        assertThat(count("$", "a\nb", SreRegex.MULTILINE)).isEqualTo(2);
        assertThat(matches("a$", "a\n")).isTrue();
        assertThat(matches("a\\Z", "a\n")).isFalse();
        assertThat(matches("a\\z", "a")).isTrue();
    }

    @Test
    void verboseDropsOnlyCpythonWhitespace() {
        // U+001C is not Java's whitespace question: it is a literal even under `re.VERBOSE`.
        assertThat(SreRegex.compile("\u001c", SreRegex.VERBOSE, false, Map.of())
                .matcher("\u001c").matches()).isTrue();
        assertThat(SreRegex.compile("a b", SreRegex.VERBOSE, false, Map.of())
                .matcher("ab").matches()).isTrue();
    }

    @Test
    void conditionalGroupsAreStillRefused() {
        assertThatThrownBy(() -> compile("(a)?(?(1)b|c)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown extension ?(");
    }

    /**
     * {@code Matcher.find()} advances one character after an empty match, so the non-empty alternative that
     * Python 3.7+ retries at the same position is missed. This is divergence 6.1 in the document, pinned as
     * the current answer rather than as the wanted one.
     */
    @Test
    void zeroWidthAdvancementStillFollowsJavaFind() {
        List<String> spans = new java.util.ArrayList<>();
        Matcher matcher = compile("\\b|:+").matcher("a::bc");
        while (matcher.find()) {
            spans.add(matcher.start() + ":" + matcher.end());
        }
        assertThat(spans).containsExactly("0:0", "1:1", "2:3", "3:3", "5:5");
    }

    private static int count(String source, String text, int flags) {
        Matcher matcher = compile(source, flags).matcher(text);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }
}
