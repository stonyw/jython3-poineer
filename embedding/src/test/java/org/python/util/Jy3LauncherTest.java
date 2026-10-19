package org.python.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.python.util.Jy3Process.lastLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.python.util.Jy3Process.Result;

/**
 * M1's done criterion, measured rather than declared: the same source, run by CPython 3.14 and by
 * {@code jy3}, answers the same way.
 *
 * <p>Every comparison here is against the reference interpreter that also compiled the source, so a
 * divergence is either the runtime's or the reference's, never a stale expectation somebody wrote
 * down in 2026. That is the whole value of a differential test and the reason these run the launcher
 * as a process: {@code print} reaches the OS descriptor, which is what a user sees.
 *
 * <p>Two cases compare less than the whole stream on purpose. An uncaught exception is matched on its
 * last line only, because a traceback needs the frame chain above the raise and jy3 has only the
 * exception to print, and a usage error is matched on its exit status, because the program's own name is
 * in the message. Both gaps are named where they are taken rather than left for someone to discover.
 */
class Jy3LauncherTest {

    @TempDir
    Path work;

    @Test
    void helloWorldPrintsExactlyWhatCpythonPrints() throws Exception {
        Path script = write("hello.py", resource("m1/hello.py"));

        Result reference = cpython(script);
        Result jy3 = jy3(script);

        assertThat(jy3.out()).isEqualTo(reference.out());
        assertThat(jy3.out()).contains("hello world");
        assertThat(jy3.exit()).isEqualTo(reference.exit());
        assertThat(jy3.err()).isEmpty();
    }

    @Test
    void arithmeticAndControlFlowAgreeOverARangeOfValues() throws Exception {
        Path script = write("numbers.py", """
                for n in range(1, 40):
                    print(n, n * n, 2 ** n, n // 7, n % 7, -n, abs(-n))
                total = 0
                i = 0
                while i < 1000:
                    total += i
                    i += 1
                print("while:", total)
                print("big:", 2 ** 200 + 1, (10 ** 30) // 7, (10 ** 30) % 7)
                print("floats:", 1 / 3, 7 / 2, -7 / 2, 7 // 2, -7 // 2, 2.5 ** 0.5)
                print("bools:", True + True, False == 0, 1 < 2 <= 2 < 3, not 0)
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    @Test
    void functionCallsAgreeOnReturnValuesAndArguments() throws Exception {
        Path script = write("calls.py", """
                def varargs(a, *rest, **keywords):
                    return (a, rest, tuple(sorted(keywords)))

                def defaults(x, y=2, *, z=3):
                    return x - y * z

                def fib(n):
                    return n if n < 2 else fib(n - 1) + fib(n - 2)

                def make(n):
                    def add(other):
                        return other + n
                    return add

                print(varargs(1, 2, 3, key="v", other=9))
                print(defaults(100), defaults(100, 5), defaults(100, y=5, z=1))
                print(fib(15))
                print(make(3)(4), make(0)(0))
                packed = [1, 2]
                print(defaults(*packed), varargs(*[1], **{"k": 2}))
                print(list(map(abs, [-1, 2, -3])), list(filter(lambda v: v % 2, range(8))))
                print(sorted([defaults(i) for i in range(3)]))
                print("a", "b", sep="|", end="!\\n")
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    @Test
    void stringsAndContainersPrintAsCPythonPrintsThem() throws Exception {
        Path script = write("containers.py", """
                text = "Hello, World"
                print(text.upper(), text.split(", ")[1], "%-10s|" % text[:5], f"{len(text)=}")
                print("-".join(text.split()))
                listing = [1, [2, {"three": ("four", b"bytes")}]]
                print(listing, listing[1][0], listing[1][1], listing[1][2][::-1][:1])
                del listing[0]
                print(listing, len(listing), 1 in listing, {3, 1, 2}, {"a": 1} | {"b": 2})
                print(tuple(sorted("banana", key=str.lower)), list(enumerate("abc", 3)))
                print({x: x * x for x in range(4)}, sorted({x for x in "aabcc"}))
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    @Test
    void rangeArithmeticAgreesAtTheBoundaries() throws Exception {
        Path script = write("ranges.py", """
                for spec in [(0,), (1,), (5, 5), (3, 10), (10, 0, -2), (0, 10, 3), (1, 10, 3),
                             (-5, 5, 2), (10, 10, 2), (0, -3, -1), (2 ** 62, 2 ** 62 + 3),
                             (0, 2 ** 70, 2 ** 69)]:
                    r = range(*spec)
                    print(spec, len(r), r[0] if len(r) else "-", r[-1] if len(r) else "-",
                          list(r)[:4], 7 in r, -1 in r, r == range(*spec), str(r))
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    /**
     * The language surface M1 claims, one file per area, under {@code src/test/resources/m1/corpus}.
     *
     * <p>Each case is a script rather than a Java assertion so that growing M1 means adding a file,
     * not inventing an expectation. Nothing here needs a class body or a generator: {@code class} and a
     * bare {@code (x for x in y)} belong to later M3 slices, and so does {@code try}, whose catching is
     * measured by the corpus below. {@code int.to_bytes} and {@code sys.version_info.major} are further
     * slices still.
     */
    private static final List<String> CORPUS =
            List.of(
                    "01_int_arithmetic.py",
                    "02_float_arithmetic.py",
                    "03_comparison.py",
                    "04_control_flow.py",
                    "05_functions.py",
                    "06_closures.py",
                    "07_strings.py",
                    "08_containers.py",
                    "09_slices_and_range.py",
                    "10_builtins_and_sys.py");

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void aCorpusScriptAnswersExactlyAsCpythonAnswers(String scriptName) throws Exception {
        answersExactlyAsCpython("m1/corpus", scriptName);
    }

    private static List<String> corpus() {
        return CORPUS;
    }

    /**
     * M3's catching surface, one file per area, under {@code src/test/resources/m3/corpus}.
     *
     * <p>The same rule as M1's corpus: no expectation is written down, the reference interpreter that
     * compiled the script also answers it. Each script here ends on its own two feet, because an
     * escaped exception would be comparing traceback lines that a later slice has to build.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("catchingCorpus")
    void aCatchingCorpusScriptAnswersExactlyAsCpythonAnswers(String scriptName) throws Exception {
        answersExactlyAsCpython("m3/corpus", scriptName);
    }

    private static List<String> catchingCorpus() {
        return List.of(
                "01_handlers.py",
                "02_finally_and_reraise.py",
                "03_chaining.py",
                "04_exc_info.py");
    }

    private void answersExactlyAsCpython(String folder, String scriptName) throws Exception {
        Path script = write(scriptName, resource(folder + "/" + scriptName));

        Result reference = cpython(script);
        Result jy3 = jy3(script);

        assertThat(jy3.exit()).as("%s exit", scriptName).isEqualTo(reference.exit());
        assertThat(jy3.out()).as("%s stdout", scriptName).isEqualTo(reference.out());
        assertThat(jy3.err()).as("%s stderr", scriptName).isEqualTo(reference.err());
    }

    @Test
    void aCommandOnTheCommandLineRunsAndNamesItselfInArgv() throws Exception {
        List<String> program =
                List.of("import sys; print(sys.argv); print(sum([int(a) for a in sys.argv[1:]]))");
        List<String> arguments = List.of("3", "4");

        Result jy3 = jy3(commandOptions(program, arguments));
        assertThat(jy3.exit()).isZero();
        assertThat(jy3.out()).isEqualTo("['-c', '3', '4']\n7\n");
        assertThat(jy3.out()).isEqualTo(cpython(commandOptions(program, arguments)).out());
    }

    @Test
    void aProgramOnStdinRunsAndReportsStdinInArgv() throws Exception {
        String program = "import sys; print(sys.argv); print('from stdin')\n";
        List<String> arguments = List.of("-", "a", "b");

        Result jy3 = jy3(arguments, program);
        assertThat(jy3.out()).isEqualTo("['-', 'a', 'b']\nfrom stdin\n");
        assertThat(jy3.out()).isEqualTo(cpython(arguments, program).out());
    }

    @Test
    void aSyntaxErrorIsTheReferenceInterpretersOwnReport() throws Exception {
        Path script = write("broken.py", "def f(:\n");

        Result jy3 = jy3(script);
        Result reference = cpython(script);

        assertThat(jy3.exit()).isEqualTo(reference.exit());
        assertThat(jy3.err()).isEqualTo(reference.err());
    }

    /**
     * Only the last line of an uncaught exception is compared.
     *
     * <p>The frame lines above it are a later slice: a traceback is rebuilt from the frame chain above
     * the raise, which jy3 does not keep yet, so it has only the exception to print. Comparing the whole
     * stream would either fail the build or buy a fake traceback.
     */
    @Test
    void anUncaughtExceptionNamesItsTypeAndFails() throws Exception {
        Path script = write("raises.py", "raise ValueError('bad value')\n");

        Result jy3 = jy3(script);
        Result reference = cpython(script);

        assertThat(jy3.exit()).isEqualTo(1);
        assertThat(lastLine(jy3.err())).isEqualTo(lastLine(reference.err()));
        assertThat(lastLine(jy3.err())).isEqualTo("ValueError: bad value");
    }

    /**
     * The refusals a call meets before any value exists, each in its own run, worded as the reference
     * words it.
     *
     * <p>A corpus script cannot carry these: one uncaught exception ends the program, so every
     * wording needs a process of its own. Only the last line is compared, because the traceback above
     * it is M3. The passing lines are here for the same reason as the refusals — each one used to
     * answer wrongly rather than loudly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("signatureCases")
    void aSignatureIsEnforcedAsCpythonEnforcesIt(String program) throws Exception {
        List<String> arguments = commandOptions(List.of(program), List.of());

        Result jy3 = jy3(arguments);
        Result reference = cpython(arguments);

        assertThat(jy3.exit()).as(program).isEqualTo(reference.exit());
        assertThat(jy3.out()).as(program).isEqualTo(reference.out());
        assertThat(lastLine(jy3.err())).as(program).isEqualTo(lastLine(reference.err()));
    }

    private static List<String> signatureCases() {
        return List.of(
                "def f(a, /): pass\nf(a=1)",
                "def f(a, b=2, /, c=3): pass\nf(a=1, b=2)",
                "def f(a, b=2, /, c=3, **kw): pass\nf(a=1)",
                "def f(a, b=2, /, c=3, **kw): pass\nprint(f(1, a=2))",
                "def f(a): pass\nf(1, 2)",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass\nprint(f(1, 2, 3, e=7))",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass"
                        + "\nprint(f(*[1, 2, 3], **{'e': 7, 'g': 8}))",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass"
                        + "\nprint(f(1, 2, 3, 4, 5, 6, e=7, z=8))",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass\nf(1, 2, 3, e=7, g=8, k=9)");
    }

    /**
     * Behaviours whose whole point is a value, not a message: the slice defaults, the folding table,
     * the keyword-only method and the dict comprehension's pair.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("valueCases")
    void aValueIsComputedAsCpythonComputesIt(String program) throws Exception {
        List<String> arguments = commandOptions(List.of(program), List.of());

        Result jy3 = jy3(arguments);
        Result reference = cpython(arguments);

        assertThat(jy3.exit()).as(program).isZero();
        assertThat(jy3.out()).as(program).isEqualTo(reference.out());
    }

    private static List<String> valueCases() {
        return List.of(
                "print([1, 2, 3][::-1], 'abc'[1::-1], (1, 2, 3)[::-1], 'abc'[4::-1])",
                "print([x for x in range(6)][::-2], [1, 2, 3][-1:0], [1, 2, 3][0:-1])",
                "print('ß'.casefold(), 'ﬁ'.casefold(), 'ŉ'.casefold(), 'İ'.casefold())",
                "print('x'.isupper(), 'X'.isupper(), 'x'.islower(), 'A'.islower(), 'a1'.islower())",
                "print('{:>8}|{:<8}|{:^8}|{a:{w}}'.format('a', 'b', 'c', a='z', w=5))",
                "print({k: k * k for k in range(4)}, {k: 'v' for k in 'ab'})",
                "x = [3, 1, 2]\nx.sort(reverse=True)\nprint(x)\nx.sort(key=str, reverse=False)\nprint(x)",
                "print(-7.5 ** 2, (-7.5) ** 3, (-8) ** 0.5 == (-8) ** 0.5, 2 ** -3, (-2) ** -3)",
                "for i, ch in enumerate('abc'):\n    print(i, ch)",
                "a, *b, c = [1, 2, 3, 4]\nprint(a, b, c)\n(x, y), z = (1, 2), 3\nprint(x, y, z)");
    }

    @Test
    void sysExitDecidesTheStatus() throws Exception {
        for (String argument : List.of("()", "(0)", "(3)", "(1,)", "('bye')")) {
            List<String> program = List.of("import sys; sys.exit" + argument);

            Result jy3 = jy3(commandOptions(program, List.of()));
            Result reference = cpython(commandOptions(program, List.of()));

            assertThat(jy3.exit()).as("sys.exit" + argument).isEqualTo(reference.exit());
            assertThat(jy3.err()).as("sys.exit" + argument).isEqualTo(reference.err());
        }
    }

    @Test
    void aScriptThatIsNotThereIsAUsageError() throws Exception {
        Result jy3 = jy3(List.of("nosuch.py"));
        Result reference = cpython(List.of("nosuch.py"));

        // CPython resolves the name before complaining, so the path in the message is the working
        // directory's own spelling of itself; only the tail of the sentence is comparable.
        assertThat(jy3.exit()).isEqualTo(reference.exit());
        assertThat(jy3.exit()).isEqualTo(2);
        assertThat(complaint(jy3.err())).isEqualTo(complaint(reference.err()));
        assertThat(jy3.err()).startsWith("jy3: ");
    }

    @Test
    void aReservedHarnessTargetIsReportedAsNotImplemented() throws Exception {
        Result result = jy3(List.of("--harness", "test_bool"));

        // The protocol the golden harness reads: 0 pass, 1 fail, 2 skip, 3 not implemented.
        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.err()).contains("test_bool").contains("not implemented");
    }

    // --- running -------------------------------------------------------------------------
    //
    // The process plumbing is Jy3Process, shared with the interop tests: two copies of "start the
    // launcher, drain both streams, refuse to wait forever" drift, and the drift looks like a product
    // bug when it shows up in only one of them.

    private Result jy3(List<String> arguments) throws Exception {
        return Jy3Process.run(work, Jy3Process.jy3Command(), arguments, null);
    }

    private Result jy3(Path script) throws Exception {
        return jy3(List.of(script.toString()));
    }

    private Result jy3(List<String> arguments, String stdin) throws Exception {
        return Jy3Process.run(work, Jy3Process.jy3Command(), arguments, stdin);
    }

    private Result cpython(List<String> arguments) throws Exception {
        return Jy3Process.run(work, cpythonCommand(), arguments, null);
    }

    private Result cpython(Path script) throws Exception {
        return cpython(List.of(script.toString()));
    }

    private Result cpython(List<String> arguments, String stdin) throws Exception {
        return Jy3Process.run(work, cpythonCommand(), arguments, stdin);
    }

    private static List<String> cpythonCommand() {
        String configured = System.getProperty("jython3.refPython");
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv("JY3_REF_PYTHON");
        }
        return List.of(configured == null || configured.isEmpty() ? "python3.14" : configured);
    }

    private static List<String> commandOptions(List<String> program, List<String> arguments) {
        List<String> options = new ArrayList<>();
        options.add("-c");
        options.add(program.get(0));
        options.addAll(arguments);
        return options;
    }

    private Path write(String name, String source) throws IOException {
        return Jy3Process.write(work, name, source);
    }

    /** M1's acceptance script, kept in one place so the test and a human run the same file. */
    private static String resource(String name) {
        return Jy3Process.resource(name);
    }

    /** The part of a launcher complaint that names neither the program nor the working directory. */
    private static String complaint(String stderr) {
        int at = stderr.indexOf("can't open file");
        return at < 0 ? stderr : stderr.substring(at);
    }
}
