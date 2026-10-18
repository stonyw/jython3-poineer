package org.jython3.tools.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteTest {

    private static Path write(@TempDir Path dir, String body) throws IOException {
        Path f = dir.resolve("smoke.txt");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void readsNamesAndIgnoresCommentsAndDuplicates(@TempDir Path dir) throws IOException {
        Suite s =
                Suite.read(
                        write(
                                dir,
                                """
                                # M0 smoke
                                test_grammar
                                    test_bool
                                test_grammar

                                # scoped entries are not allowed in suites
                                """));
        assertThat(s.name()).isEqualTo("smoke");
        assertThat(s.tests()).containsExactly("test_grammar", "test_bool");
    }

    @Test
    void rejectsMultipleTokensOnOneLine(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> Suite.read(write(dir, "test_a fail\n")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("single test name");
    }

    @Test
    void rejectsEmptySuite(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> Suite.read(write(dir, "# nothing here\n")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("suite is empty");
    }
}
