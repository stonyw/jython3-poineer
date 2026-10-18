package org.jython3.tools.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The grading table is the contract of the whole harness: a wrong table makes every milestone lie. */
class GradingTest {

    private static void assertVerdict(
            Grading.Status actual, Grading.Expectation expected, Grading.Verdict v, boolean surprise) {
        Grading.Graded g =
                Grading.grade(new Grading.Result("test_x", actual, 0, 1L, ""), expected);
        assertThat(g.verdict()).isEqualTo(v);
        assertThat(g.isSurprise()).isEqualTo(surprise);
    }

    @ParameterizedTest
    @EnumSource(Grading.Status.class)
    @DisplayName("every status resolves against every expectation")
    void totalTable(Grading.Status s) {
        for (Grading.Expectation e : Grading.Expectation.values()) {
            assertThat(Grading.grade(new Grading.Result("t", s, 0, 1L, ""), e).verdict()).isNotNull();
        }
    }

    @Test
    void unexpectedFailureIsASurprise() {
        assertVerdict(Grading.Status.FAIL, Grading.Expectation.NONE, Grading.Verdict.FAILURE, true);
    }

    @Test
    void unexpectedPassIsASurpriseToo() {
        // This is the direction a naive runner gets wrong: a test that heals itself must be
        // re-recorded, or the known-bad list quietly grows stale and green.
        assertVerdict(Grading.Status.PASS, Grading.Expectation.FAIL, Grading.Verdict.UNEXPECTED_PASS, true);
        assertVerdict(Grading.Status.PASS, Grading.Expectation.SKIP, Grading.Verdict.UNEXPECTED_PASS, true);
    }

    @Test
    void knownBadOutcomesAreQuiet() {
        assertVerdict(Grading.Status.FAIL, Grading.Expectation.FAIL, Grading.Verdict.EXPECTED_FAIL, false);
        assertVerdict(Grading.Status.SKIP, Grading.Expectation.SKIP, Grading.Verdict.EXPECTED_SKIP, false);
        assertVerdict(Grading.Status.PASS, Grading.Expectation.NONE, Grading.Verdict.PASS, false);
    }

    @Test
    void silentSkipNeverLooksLikeSuccess() {
        // A skip against an unlisted test usually means a missing requirement, not a pass.
        assertVerdict(Grading.Status.SKIP, Grading.Expectation.NONE, Grading.Verdict.UNEXPECTED_SKIP, true);
        assertVerdict(Grading.Status.SKIP, Grading.Expectation.FAIL, Grading.Verdict.UNEXPECTED_SKIP, true);
        // ...and a failure where we predicted a mere skip is a real regression.
        assertVerdict(Grading.Status.FAIL, Grading.Expectation.SKIP, Grading.Verdict.FAILURE, true);
    }

    @Test
    void crashAndTimeoutAreAlwaysProblems() {
        for (Grading.Expectation e : Grading.Expectation.values()) {
            assertVerdict(Grading.Status.CRASH, e, Grading.Verdict.ERROR, true);
            assertVerdict(Grading.Status.TIMEOUT, e, Grading.Verdict.ERROR, true);
            assertVerdict(Grading.Status.NOT_IMPLEMENTED, e, Grading.Verdict.NOT_IMPLEMENTED, true);
        }
    }

    @Test
    void expectationParsing() {
        assertThat(Grading.Expectation.parse("fail")).isEqualTo(Grading.Expectation.FAIL);
        assertThat(Grading.Expectation.parse("SKIP")).isEqualTo(Grading.Expectation.SKIP);
        assertThat(Grading.Expectation.parse("")).isEqualTo(Grading.Expectation.NONE);
        assertThatThrownBy(() -> Grading.Expectation.parse("maybe")).isInstanceOf(IllegalArgumentException.class);
    }
}
