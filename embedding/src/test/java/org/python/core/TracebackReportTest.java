package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * The report an uncaught exception leaves behind, built in the JVM that holds the frames.
 *
 * <p>{@link org.python.util.Jy3LauncherTest} compares this text against the reference interpreter
 * stream for stream. What is taken apart here is the one decision a report makes per frame — where the
 * line it quotes comes from — because a script cannot reach it three ways: a file that is there, a file
 * that has gone, and a command whose text exists nowhere else. {@code Cpython314Compiler} is why these
 * run in the embedding module rather than the runtime's own: a frame is only worth reporting once
 * something compiled it, and decision D2 keeps the compiler out of {@code org.python.core}.
 *
 * <p>Every literal is what CPython 3.14.8 prints for the same shape, read off the reference asked for
 * its anchor-free report. The unregistered {@code <string>} is its own reading: CPython only hands the
 * text to {@code linecache} on the entry points it compiles itself — {@code -c} and the REPL — so an
 * embedded snippet, which is what {@code PyRun_SimpleString} and {@code exec()} both are, quotes nothing.
 */
class TracebackReportTest {

    @TempDir
    Path work;

    @AfterEach
    void forgetTheLimit() {
        // A limit left over from one test would quietly cut another's report.
        assign("sys.tracebacklimit = None");
    }

    @Test
    void aFrameQuotesTheLineItFailedOn() throws Exception {
        Path script = Files.writeString(work.resolve("quotes.py"), """
                def inner(x):
                    return 1 / x

                inner(0)
                """);
        Cpython314Compiler.Compiled compiled = Cpython314Compiler.compileFile(script);

        String where = compiled.fileName();
        PyBaseException error = uncaught(compiled.code(), PyRuntime.mainModule(), where);

        assertThat(TracebackReport.of(error))
                .isEqualTo(
                        """
                        Traceback (most recent call last):
                          File "%1$s", line 4, in <module>
                            inner(0)
                          File "%1$s", line 2, in inner
                            return 1 / x
                        ZeroDivisionError: division by zero
                        """
                                .formatted(where));
    }

    @Test
    void aFrameWithNoFileToReadNamesItselfAndQuotesNothing() {
        // The shape CPython answers for a compiled name that is not on disk, read off exec(compile(...)).
        PyBaseException error =
                uncaught(
                        source(
                                "def f():\n    raise IndexError('ghost')\n\nf()\n", "ghost.py"),
                        PyRuntime.mainModule(),
                        null);

        assertThat(TracebackReport.of(error))
                .isEqualTo(
                        """
                        Traceback (most recent call last):
                          File "ghost.py", line 4, in <module>
                          File "ghost.py", line 2, in f
                        IndexError: ghost
                        """);
    }

    @Test
    void aCommandIsQuotedFromTheTextItWasGiven() {
        String command = "def boom():\n    raise KeyError('cmd')\n\nboom()\n";
        PyCode code = source(command, "<string>");

        // Without the text, a <string> frame has nothing to read: this is PyRun_SimpleString's report.
        assertThat(TracebackReport.of(uncaught(code, PyRuntime.mainModule(), null)))
                .isEqualTo(
                        """
                        Traceback (most recent call last):
                          File "<string>", line 4, in <module>
                          File "<string>", line 2, in boom
                        KeyError: 'cmd'
                        """);

        // `-c` is the same code object with the same name, and the one difference is that the launcher
        // kept the text, as pymain_run_command does.
        TracebackReport.registerSource(code, command);

        assertThat(TracebackReport.of(uncaught(code, PyRuntime.mainModule(), null)))
                .isEqualTo(
                        """
                        Traceback (most recent call last):
                          File "<string>", line 4, in <module>
                            boom()
                          File "<string>", line 2, in boom
                            raise KeyError('cmd')
                        KeyError: 'cmd'
                        """);
    }

    /**
     * The limit as the reference reads it: counted from the failing frame, refused by anything that is
     * not a number, and worth zero frames at or below zero — where the header goes too.
     */
    @Test
    void theLimitCountsFromTheFailingFrame() throws Exception {
        Cpython314Compiler.Compiled script =
                script(
                        "limit.py",
                        "def a():\n    raise ValueError('deep')\n"
                                + "\ndef b():\n    return a()\n\nb()\n");
        PyBaseException error =
                uncaught(script.code(), PyRuntime.mainModule(), script.fileName());
        String where = script.fileName();
        String innermost =
                "  File \"%1$s\", line 2, in a\n    raise ValueError('deep')\n".formatted(where);
        String wholeReport =
                ("Traceback (most recent call last):\n"
                                + "  File \"%1$s\", line 7, in <module>\n    b()\n"
                                + "  File \"%1$s\", line 5, in b\n    return a()\n")
                        .formatted(where)
                        + innermost
                        + "ValueError: deep\n";

        assign("sys.tracebacklimit = 2");
        assertThat(TracebackReport.of(error))
                .isEqualTo(
                        "Traceback (most recent call last):\n"
                                + "  File \"%1$s\", line 5, in b\n    return a()\n".formatted(where)
                                + innermost
                                + "ValueError: deep\n");

        assign("sys.tracebacklimit = True");
        assertThat(TracebackReport.of(error))
                .isEqualTo("Traceback (most recent call last):\n" + innermost + "ValueError: deep\n");

        for (String nothing : new String[] {"False", "-3"}) {
            assign("sys.tracebacklimit = " + nothing);
            assertThat(TracebackReport.of(error)).isEqualTo("ValueError: deep\n");
        }

        // A number too wide for a count is every frame, and so is anything the comparison refuses.
        assign("sys.tracebacklimit = 10 ** 40");
        assertThat(TracebackReport.of(error)).isEqualTo(wholeReport);
        assign("sys.tracebacklimit = 'not a number'");
        assertThat(TracebackReport.of(error)).isEqualTo(wholeReport);
    }

    @Test
    void aTypeFiledUnderItsOwnModuleKeepsItsModuleName() throws Exception {
        // Measured off json: a broken document answers as json.decoder.JSONDecodeError, not as the
        // bare class. A module that is neither __main__ nor builtins is named in full.
        Cpython314Compiler.Compiled script =
                script("mine.py", "class Mine(Exception):\n    pass\n\nraise Mine('workshop')\n");

        PyBaseException error = uncaught(script.code(), new PyModule("workshop"), script.fileName());

        assertThat(TracebackReport.of(error))
                .isEqualTo(
                        """
                        Traceback (most recent call last):
                          File "%1$s", line 4, in <module>
                            raise Mine('workshop')
                        workshop.Mine: workshop
                        """
                                .formatted(script.fileName()));
    }

    // --- running -------------------------------------------------------------------------

    /** A program written where the report can read the line each frame quotes. */
    private Cpython314Compiler.Compiled script(String name, String program) throws IOException {
        return Cpython314Compiler.compileFile(Files.writeString(work.resolve(name), program));
    }

    /** The exception a module's top level could not catch. */
    private static PyBaseException uncaught(PyCode code, PyModule where, String fileName) {
        try {
            PyRuntime.exec(code, where, fileName);
        } catch (PyRaise raised) {
            return raised.value();
        }
        throw new AssertionError("expected an escaped exception from " + code.fileName());
    }

    private static PyCode source(String program, String fileName) {
        return Cpython314Compiler.compileSource(program, fileName);
    }

    /** One statement run against the runtime's own {@code sys}, which is how a script reaches it. */
    private static void assign(String statement) {
        PyRuntime.exec(
                source("import sys\n" + statement + "\n", "<assign>"), PyRuntime.mainModule(), null);
    }
}
