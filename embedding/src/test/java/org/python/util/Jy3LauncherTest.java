package org.python.util;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>One case compares less than the whole stream on purpose: a usage error is matched on its exit status
 * and on the tail of its message, because the program's own name and the working directory are both in
 * the sentence. Everything else is compared byte for byte, the report an uncaught exception leaves on
 * stderr included — that report is M3 slice 9's, and it is matched against the reference asked to leave
 * its anchors out.
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
     * M3's language surface, one file per area, under {@code src/test/resources/m3/corpus}.
     *
     * <p>The same rule as M1's corpus: no expectation is written down, the reference interpreter that
     * compiled the script also answers it. Each script here ends on its own two feet; the ones that end on
     * an escaped exception live beside them in {@code m3/uncaught}, where the report is the thing being
     * compared. Nothing here prints a value whose {@code repr} carries an address, which is the one thing
     * the two interpreters can never agree on.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("m3Corpus")
    void anM3CorpusScriptAnswersExactlyAsCpythonAnswers(String scriptName) throws Exception {
        answersExactlyAsCpython("m3/corpus", scriptName);
    }

    private static List<String> m3Corpus() {
        return List.of(
                "01_handlers.py",
                "02_finally_and_reraise.py",
                "03_chaining.py",
                "04_exc_info.py",
                "05_classes.py",
                "06_inheritance.py",
                "07_dunders.py",
                "08_inherited_members.py",
                "09_descriptors.py",
                "10_super.py",
                "11_super_attribute_form.py",
                "12_metaclass.py",
                "13_class_hooks.py",
                "14_with.py",
                "15_exceptions.py",
                "16_traceback.py");
    }

    private void answersExactlyAsCpython(String folder, String scriptName) throws Exception {
        answersExactlyAsCpython(folder, scriptName, cpythonCommand());
    }

    private void answersExactlyAsCpython(String folder, String scriptName, List<String> command)
            throws Exception {
        Path script = write(scriptName, resource(folder + "/" + scriptName));

        Result reference = Jy3Process.run(work, command, List.of(script.toString()), null);
        Result jy3 = jy3(script);

        assertThat(jy3.exit()).as("%s exit", scriptName).isEqualTo(reference.exit());
        assertThat(jy3.out()).as("%s stdout", scriptName).isEqualTo(reference.out());
        assertThat(jy3.err()).as("%s stderr", scriptName).isEqualTo(reference.err());
    }

    /**
     * M3 slice 9's report: an exception nobody caught, printed the way CPython prints it.
     *
     * <p>The reference runs with {@code -X no_debug_ranges}, and that is not a convenience. CPython's
     * default report puts an anchor line under each frame — the {@code ~~~^~~~} of PEP 657, built from
     * the column ranges this runtime does not carry yet — and switching the ranges off makes the
     * interpreter answer with the report its own C printer writes, which is the one {@code jy3}
     * transcribes. The missing anchor is the named difference of this slice and is recorded in PLAN.md.
     *
     * <p>Unlike the M3 corpus, every script here ends on an uncaught exception: the report is what is
     * being compared. Stdout is compared alongside it because a script catches and prints before it
     * fails, and the absolute path each frame names is the temp directory's own spelling of itself, so
     * these cases cover that too.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("uncaughtReports")
    void anUncaughtExceptionIsReportedAsCpythonReportsIt(String scriptName) throws Exception {
        answersExactlyAsCpython("m3/uncaught", scriptName, cpythonWithoutAnchorsCommand());
    }

    private static List<String> uncaughtReports() {
        return List.of(
                "01_frames.py",
                "02_cause.py",
                "03_handled_then_raise.py",
                "04_context.py",
                "05_operation_in_handler.py",
                "06_from_none.py",
                "07_limit.py",
                "08_repeated_line.py",
                "09_notes.py",
                "10_notes_tuple.py",
                "11_notes_string.py",
                "12_empty_message.py",
                "13_str_fails.py",
                "14_type_names.py",
                "15_deep_chain.py",
                "16_context_of_cause.py",
                "17_zero_limit.py",
                "18_cleared_traceback.py",
                "19_bare_raise_without_an_exception.py",
                "20_exit_status.py",
                "21_limit_that_is_not_a_number.py",
                "22_limit_of_true_is_one_frame.py",
                "23_context_cycle.py");
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
     * The whole report is compared, not the complaint alone.
     *
     * <p>The frame lines above the message are what M3 slice 9 added, and they are why this test asks the
     * reference for its anchor-free report: see {@link #anUncaughtExceptionIsReportedAsCpythonReportsIt}.
     */
    @Test
    void anUncaughtExceptionNamesItsTypeAndFails() throws Exception {
        Path script = write("raises.py", "raise ValueError('bad value')\n");

        Result jy3 = jy3(script);
        Result reference = cpythonWithoutAnchors(List.of(script.toString()));

        assertThat(jy3.exit()).isEqualTo(1);
        assertThat(jy3.err()).isEqualTo(reference.err());
        assertThat(jy3.err())
                .isEqualTo(
                        "Traceback (most recent call last):\n"
                                + "  File \""
                                + script.toAbsolutePath()
                                + "\", line 1, in <module>\n"
                                + "    raise ValueError('bad value')\n"
                                + "ValueError: bad value\n");
    }

    /**
     * The refusals a call meets before any value exists, each in its own run, worded as the reference
     * words it.
     *
     * <p>A corpus script cannot carry these: one uncaught exception ends the program, so every
     * wording needs a process of its own. The whole report is compared, which makes these the cases that
     * cover a {@code <string>} frame: a command has no file to read a source line from, so the command
     * itself has to be what the report quotes, the way CPython hands the text to {@code linecache}. The
     * passing lines are here for the same reason as the refusals — each one used to answer wrongly rather
     * than loudly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("signatureCases")
    void aSignatureIsEnforcedAsCpythonEnforcesIt(String program) throws Exception {
        List<String> arguments = commandOptions(List.of(program), List.of());

        Result jy3 = jy3(arguments);
        Result reference = cpythonWithoutAnchors(arguments);

        assertThat(jy3.exit()).as(program).isEqualTo(reference.exit());
        assertThat(jy3.out()).as(program).isEqualTo(reference.out());
        assertThat(jy3.err()).as(program).isEqualTo(reference.err());
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

    /**
     * The reference asked for the report without anchors: {@code -X no_debug_ranges} leaves the PEP 657
     * ranges unrecorded, and the report CPython then writes is the one its own C printer writes.
     */
    private Result cpythonWithoutAnchors(List<String> arguments) throws Exception {
        return Jy3Process.run(work, cpythonWithoutAnchorsCommand(), arguments, null);
    }

    private static List<String> cpythonWithoutAnchorsCommand() {
        return cpythonCommand(List.of("-X", "no_debug_ranges"));
    }

    private static List<String> cpythonCommand() {
        return cpythonCommand(List.of());
    }

    /** The reference interpreter, with any interpreter options ahead of the script. */
    private static List<String> cpythonCommand(List<String> options) {
        String configured = System.getProperty("jython3.refPython");
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv("JY3_REF_PYTHON");
        }
        List<String> command =
                new ArrayList<>(
                        List.of(configured == null || configured.isEmpty() ? "python3.14" : configured));
        command.addAll(options);
        return command;
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
