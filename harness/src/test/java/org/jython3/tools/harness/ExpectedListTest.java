package org.jython3.tools.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpectedListTest {

    private static Path write(@TempDir Path dir, String body) throws IOException {
        Path f = dir.resolve("cpython-ref.txt");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void parsesEntriesAndComments(@TempDir Path dir) throws IOException {
        ExpectedList e =
                ExpectedList.read(
                        write(
                                dir,
                                """
                                # known bad on the JVM
                                test_math fail
                                test_socket skip   # needs network

                                test_os fail
                                """),
                        "linux");
        assertThat(e.byTest()).hasSize(3);
        assertThat(e.expectationFor("test_math")).isEqualTo(Grading.Expectation.FAIL);
        assertThat(e.expectationFor("test_socket")).isEqualTo(Grading.Expectation.SKIP);
        assertThat(e.expectationFor("test_absent")).isEqualTo(Grading.Expectation.NONE);
    }

    @Test
    void platformScopedLinesOnlyApplyOnTheirPlatform(@TempDir Path dir) throws IOException {
        String body =
                """
                [macosx] test_subprocess fail
                [linux] test_fcntl skip
                test_grammar fail
                """;
        ExpectedList mac = ExpectedList.read(write(dir, body), "macosx");
        assertThat(mac.byTest()).containsOnlyKeys("test_subprocess", "test_grammar");
        assertThat(ExpectedList.read(write(dir, body), "linux").byTest())
                .containsOnlyKeys("test_fcntl", "test_grammar");
    }

    @Test
    void rejectsAmbiguousLines(@TempDir Path dir) {
        assertThatThrownBy(
                        () -> ExpectedList.read(write(dir, "test_a fail extra\n"), "linux"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expected '<test> <fail|skip>'");
    }

    @Test
    void rejectsDuplicatesAndBadStatus(@TempDir Path dir) {
        assertThatThrownBy(() -> ExpectedList.read(write(dir, "test_a fail\ntest_a fail\n"), "linux"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> ExpectedList.read(write(dir, "test_a maybe\n"), "linux"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unknown expectation");
    }

    @Test
    void absentFileMeansNoExpectations(@TempDir Path dir) throws IOException {
        assertThat(ExpectedList.read(dir.resolve("nope.txt"), "linux").byTest()).isEmpty();
    }

    @Test
    void renderRoundTrips(@TempDir Path dir) throws IOException {
        List<Grading.Graded> graded =
                List.of(
                        g("test_math", Grading.Status.FAIL, Grading.Expectation.FAIL),
                        g("test_socket", Grading.Status.SKIP, Grading.Expectation.SKIP),
                        g("test_grammar", Grading.Status.PASS, Grading.Expectation.NONE));
        Path f = write(dir, ExpectedList.render(graded));
        ExpectedList back = ExpectedList.read(f, "linux");
        // Only recorded deviations survive a round trip; passing tests need no entry.
        assertThat(back.byTest())
                .hasSize(2)
                .containsEntry("test_math", Grading.Expectation.FAIL)
                .containsEntry("test_socket", Grading.Expectation.SKIP)
                .doesNotContainKey("test_grammar");
    }

    private static Grading.Graded g(String test, Grading.Status s, Grading.Expectation e) {
        Grading.Result r = new Grading.Result(test, s, 1, 5L, "detail");
        return new Grading.Graded(r, e, Grading.grade(r, e).verdict());
    }
}
