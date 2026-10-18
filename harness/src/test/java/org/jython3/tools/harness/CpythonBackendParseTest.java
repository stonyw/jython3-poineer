package org.jython3.tools.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** regrtest output parsing: if this is wrong, results are wrong. */
class CpythonBackendParseTest {

    private static Backend.Proc proc(int exit, String output) {
        return new Backend.Proc(exit, output, false);
    }

    @Test
    void readsPassedLine() {
        String out =
                """
                0:00:00 load avg: 1.34 [1/1] test_grammar passed
                == Tests result: SUCCESS ==
                """;
        assertThat(CpythonBackend.classify(proc(0, out))).isEqualTo(Grading.Status.PASS);
    }

    @Test
    void readsFailedLine() {
        String out =
                """
                0:00:01 load avg: 1.10 [1/1] test_bool failed (1 failure)
                == Tests result: FAILURE ==
                """;
        assertThat(CpythonBackend.classify(proc(1, out))).isEqualTo(Grading.Status.FAIL);
    }

    @Test
    void readsSkippedLine() {
        String out = "0:00:00 load avg: 1.00 [1/1] test_faulthandler skipped (reason: require subinterpreters)\n";
        assertThat(CpythonBackend.classify(proc(0, out))).isEqualTo(Grading.Status.SKIP);
    }

    @Test
    void crashWordIsNotAFailure() {
        // A segfault is a runtime defect, not an assertion: it must not fold into the FAIL bucket.
        assertThat(CpythonBackend.classify(proc(-6, "0:00:00 [1/1] test_math core dumped\n")))
                .isEqualTo(Grading.Status.CRASH);
        assertThat(CpythonBackend.classify(proc(1, "no per-test line here at all")))
                .isEqualTo(Grading.Status.CRASH);
    }

    @Test
    void lastResultLineWins() {
        String out =
                """
                0:00:00 [1/2] test_a failed (1 failure)
                0:00:00 [2/2] test_a passed
                """;
        assertThat(CpythonBackend.classify(proc(0, out))).isEqualTo(Grading.Status.PASS);
    }

    @Test
    void missingTestTreeFailsFastWithGuidance() {
        CpythonBackend b =
                new CpythonBackend("python3.14", java.nio.file.Path.of("/nonexistent/lib-python"), Duration.ofSeconds(5));
        Grading.Result res = b.run("test_bool");
        assertThat(res.status()).isEqualTo(Grading.Status.CRASH);
        assertThat(res.detail()).contains("syncCpythonTests");
    }
}
