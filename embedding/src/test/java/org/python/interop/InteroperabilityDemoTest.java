package org.python.interop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.python.util.Jy3Process.jy3;
import static org.python.util.Jy3Process.resource;
import static org.python.util.Jy3Process.write;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.core.PyRaise;
import org.python.interop.fixtures.Panel;
import org.python.util.Jy3Process.Result;
import org.python.util.PythonInterpreter;

/**
 * M2's acceptance line, run the way a person runs a demo.
 *
 * <p>One script per capability, under {@code src/test/resources/m2}, named by the number it answers:
 * {@code 02} is Java's own types read from Python, {@code 03} is the Java class library, {@code 04} is
 * the half an embedded Java program calls. MVP① — Python source running on the JVM at all — is
 * {@code m1/hello.py}, which {@link org.python.util.Jy3LauncherTest} already measures against CPython,
 * so it is not measured twice here. The first two run through the launcher as its own process, because
 * that is what a demo is; the third runs in this JVM, because an embedding API only exists inside one.
 *
 * <p>CPython cannot be the reference for these. {@code from java.util import ArrayList} is a line it has
 * no way to run, so each block below is the answer the JDK gives, and every one of those answers is the
 * same call made in Java somewhere else in this suite — {@link WidgetOracleTest} for the fixture
 * classes, {@link JavaFromPythonTest} for the library ones. A demo whose output nobody re-measures is
 * documentation rather than a test; these are re-measured, by the process below as much as by the
 * assertions.
 */
class InteroperabilityDemoTest {

    @TempDir
    Path work;

    /** MVP②: the JDK's own classes, read by reflection, answering in their own names. */
    @Test
    void aDemoReadsJavaBuiltinsFromPython() throws Exception {
        assertThat(runDemo("02_introspection.py"))
                .isEqualTo(
                        """
                        2147483647 -2147483648 32 int
                        9223372036854775807 3.141592653589793 7 1.4142135623730951
                        42 255 ff
                        8 int
                        ArrayList java.util java.util.ArrayList
                        True True True
                        42-two str 2
                        True True
                        java.lang.Integer True True
                        True
                        """);
    }

    /** MVP③: collections, streams, time, geometry and an array, with Java's answers. */
    @Test
    void aDemoUsesTheJavaClassLibraryFromPython() throws Exception {
        assertThat(runDemo("03_libraries.py"))
                .isEqualTo(
                        """
                        3 delta charlie
                        ['delta', 'alpha', 'charlie'] True
                        ['bravo', 'alpha', 'charlie']
                        2 ['bravo', 'alpha']
                        2 1 None
                        ['a', 'bb'] True
                        ['bb']
                        3 1 [3, 1]
                        [0, 1, 2, 3]
                        [3!, 1!, 2!]
                        6
                        HERE
                        absent
                        2026-10-19 MONDAY 19 False
                        2026-12-03 9
                        5400 PT25H
                        java.awt.Point[x=1,y=2] 1 2
                        java.awt.Point[x=4,y=5] 5.0
                        5.0 5.0
                        2 delta alpha [delta, alpha]
                        """);
    }

    /**
     * MVP④: a Java program runs the script and calls what it defines.
     *
     * <p>The acceptance sentence is the first assertion — a Python function called from Java gives back a
     * {@code java.util.List} — and the rest are the other three crossings an embedder needs on the same
     * file: a value going in as Java's own type, a namespace wearing a Java interface, and a Python
     * failure reaching a {@code catch} with its type and message intact.
     */
    @Test
    void aDemoIsRunByJavaCallingIntoIt() {
        PythonInterpreter py = new PythonInterpreter();
        py.exec(resource("m2/04_embedding.py"));

        Object squares = py.invoke("build", 4);
        assertThat(squares).isInstanceOf(List.class);
        assertThat(squares).isEqualTo(List.of(0, 1, 4, 9));

        assertThat(py.invoke("shout", "hi", 2)).isEqualTo("hi hi");
        assertThat(py.invoke("counts")).isEqualTo(Map.of("one", 1, "two", 2));

        Panel panel = py.export(Panel.class);
        panel.onOpen("home");
        assertThat(py.get("log")).isEqualTo(List.of("home"));
        assertThat(panel.onClose(2, "bye")).isEqualTo(203);

        assertThatThrownBy(() -> py.invoke("boom"))
                .isInstanceOf(PyRaise.class)
                .hasMessage("ValueError: bad value");
    }

    /** Run one demo script the way a user runs it, and give back everything it printed. */
    private String runDemo(String name) throws Exception {
        Path script = write(work, name, resource("m2/" + name));

        Result result = jy3(work, script.toString());
        assertThat(result.err()).as("stderr of %s", name).isEmpty();
        assertThat(result.exit()).as("exit code of %s, stderr was %s", name, result.err()).isZero();
        return result.out();
    }
}
