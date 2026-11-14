package org.python.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.python.core.ExceptionTypes;
import org.python.core.PyBaseException;
import org.python.core.PyBool;
import org.python.core.PyCode;
import org.python.core.PyInt;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PyRaise;
import org.python.core.PyRuntime;
import org.python.core.PyStr;
import org.python.core.Slots;
import org.python.core.TracebackReport;
import org.python.javaclass.JavaInterop;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * Jython3 launcher: the {@code jy3} command.
 *
 * <p>M1 status: a script, a {@code -c} command and stdin all run end to end — Stage A compiles, the
 * interpreter executes, {@code sys.argv} is what the script sees. M2 put the JVM on the other side of
 * that same script, so {@code import java.util} is an ordinary import now, and changed nothing about the
 * options below. The option surface is therefore small, and every omission is reported rather than
 * quietly ignored: {@code -m} needs {@code runpy} on top of the import system, {@code -O} needs
 * {@code __debug__} to mean something to the code object, {@code -b} needs a {@code BytesWarning} at
 * the comparison site, and the interactive prompt needs a terminal, a line editor and a read-eval-print
 * loop, which the native frontend milestone owns. Those exit 3, which the harness records as
 * {@code NOT_IMPLEMENTED}.
 *
 * <p>Two options carry a payload. {@code -W} is stored verbatim in {@code sys.warnoptions}, because the
 * argument's syntax — {@code action[:category[:message[:module[:lineno]]]]}, with the abbreviations
 * {@code -Wall} and {@code -Wd} that the reference unfolds from prefixes — belongs to {@code warnings},
 * which replays the list at import time. {@code -X} is matched against the keys this runtime can honour
 * and refused by name otherwise; an unknown key is never filed away and forgotten. Of the rest, the
 * ones accepted are ones whose promise already holds — {@code -B}, because no {@code .pyc} is ever
 * written, {@code -S} and {@code -s}, because no {@code site} or user directory is imported,
 * {@code -q}, because there is no banner to silence, {@code -u}, because both streams are already the
 * JVM's own, and {@code -E}/{@code -I}, which are accepted but not applied — {@code PYTHONPATH} is
 * still honoured under them, and {@code sys.flags} reports that rather than the switch.
 *
 * <p>Harness protocol, shared with {@code org.jython3.tools.harness.Jython3Backend}:
 *
 * <pre>
 *   0 passed   1 failed   2 skipped   3 not implemented   other crash
 * </pre>
 *
 * <p>Exit codes a script can reach deliberately follow CPython instead, because that is the answer a
 * differential test compares: 1 for an uncaught exception, 2 for a usage error or an unreadable
 * script, and whatever {@code sys.exit(n)} named. Only {@code --harness} speaks the protocol, and it
 * is matched before any other option.
 */
public final class jy3 {

    /** Reserved by the golden test harness: run one regrtest target and report by exit code. */
    private static final String HARNESS_FLAG = "--harness";

    private static final int PASSED = 0;
    private static final int FAILED = 1;

    /** CPython's code for both a bad option and a script that is not there. */
    private static final int USAGE_ERROR = 2;

    private static final int NOT_IMPLEMENTED = 3;

    /**
     * An uncaught {@code KeyboardInterrupt}, which leaves by {@code kill(getpid(), SIGINT)} so that the
     * caller learns the user pressed {@code ^C}: {@code 128 + SIGINT}, as CPython's {@code exit_sigint}
     * counts it.
     */
    private static final int INTERRUPTED = 130;

    /** Anything outside the protocol's four codes, which the harness must read as a crash. */
    private static final int INTERNAL_CRASH = 70;

    private static final String PROGRAM = "jy3";
    private static final String PROGRAM_VERSION = "0.1.0-M4";

    private static final String USAGE =
            "usage: " + PROGRAM + " [option] ... [-c cmd | -m mod | file | -] [arg] ...\n"
                    + "Try `" + PROGRAM + " -h' for more information.\n";

    private static final String HELP =
            PROGRAM + " [option] ... [-c cmd | -m mod | file | -] [arg] ...\n"
                    + "Options and arguments:\n"
                    + "-c cmd : program read from string\n"
                    + "-h     : print this help message and exit\n"
                    + "-V     : print the version and exit\n"
                    + "-W arg : warning control; the argument goes to `warnings` verbatim\n"
                    + "-X opt : set an implementation-specific option (`utf8`, `dev`, `faulthandler`)\n"
                    + "-      : program read from stdin\n"
                    + "\n"
                    + "Accepted: -B -E -I -S -s -q -u -W arg -X utf8 -X dev -X faulthandler\n"
                    + "Not implemented yet: -b and -bb (BytesWarning); -m mod (runpy); -O and -OO"
                    + " (`__debug__` in the code object); -X keys other than the three above;"
                    + " the interactive prompt (M5)\n";

    public static void main(String[] args) {
        int status;
        try {
            status = run(args);
        } catch (PyRaise raised) {
            status = reportUncaught(raised);
        } catch (Cpython314Compiler.CompilationRefused refusal) {
            // The reference compiler has already laid this out the way a Python user reads it:
            // file, line, the offending source, a caret, the reason. Quoting it beats imitating it.
            String report = refusal.report();
            write(report.endsWith("\n") ? report : report + "\n");
            status = FAILED;
        } catch (Cpython314Compiler.CompilerUnavailable unavailable) {
            write(PROGRAM + ": " + unavailable.advice() + "\n");
            status = INTERNAL_CRASH;
        } catch (RuntimeException | StackOverflowError bug) {
            write(PROGRAM + ": internal error: " + bug.getClass().getName() + ": " + bug.getMessage() + "\n");
            bug.printStackTrace();
            status = INTERNAL_CRASH;
        }
        // A non-daemon Python thread is one the reference joins before `atexit` runs; doing it in
        // that order is what lets a thread that logs through an `atexit` handler finish first.
        PyRuntime.awaitThreads();
        // The reference runs `atexit` callbacks on the way out of a normal shutdown, before the
        // streams are drained; `logging`'s handler flush is the one this runtime already relies on.
        PyRuntime.runExitFunctions();
        // Before exiting, not after: stdout is block-buffered whenever it is a pipe, and
        // System.exit makes no promise to drain a stream the JVM itself opened.
        PyRuntime.flushStreams();
        System.exit(status);
    }

    /** Parse the command line, compile whatever it names, run it. Returns the exit status. */
    private static int run(String[] args) {
        String command = null;
        boolean stdin = false;
        int at = 0;
        List<String> warnOptions = new ArrayList<>();
        // `PYTHONWARNINGS` is the same list as `-W`, read before the switches: the reference appends
        // the command line's entries after the environment's, and empty fields are dropped from both.
        String fromEnvironment = System.getenv("PYTHONWARNINGS");
        if (fromEnvironment != null) {
            for (String entry : fromEnvironment.split(",")) {
                if (!entry.isEmpty()) {
                    warnOptions.add(entry);
                }
            }
        }
        List<String> xOptions = new ArrayList<>();
        boolean devMode = false;
        boolean quiet = false;
        boolean dontWriteBytecode = false;
        boolean noSite = false;
        int optimize = 0;
        boolean noUserSite = false;

        options:
        while (at < args.length) {
            String option = args[at];
            if (option.equals(HARNESS_FLAG)) {
                return harness(args, at);
            }
            if (option.equals("-")) {
                stdin = true;
                at++;
                break;
            }
            if (!option.startsWith("-")) {
                break;
            }
            // `-W` and `-X` take their argument either as the next word or glued on, and the standard
            // library's own harness writes the glued form (`-Wd`, `-Xutf8`). Split it here so the switch
            // below sees one spelling of each option.
            String glued = null;
            if (option.length() > 2 && (option.startsWith("-W") || option.startsWith("-X"))) {
                glued = option.substring(2);
                option = option.substring(0, 2);
            }
            switch (option) {
                case "-c" -> {
                    if (!argumentExpected(option, args, at)) {
                        return USAGE_ERROR;
                    }
                    command = args[at + 1];
                    at += 2;
                    break options;
                }
                case "-h", "--help" -> {
                    write(HELP);
                    return PASSED;
                }
                case "-V", "--version" -> {
                    write(version() + "\n");
                    return PASSED;
                }
                case "-m" -> {
                    if (!argumentExpected(option, args, at)) {
                        return USAGE_ERROR;
                    }
                    return notImplemented(
                            "-m module", "running a module needs runpy, which comes after the finder");
                }
                case "-W" -> {
                    // The string is kept exactly as it was written, because `warnings` parses it: the
                    // action, category, message, module and line number are its syntax to check, and an
                    // invalid one is reported by `_processoptions` the way the reference reports it.
                    if (glued != null) {
                        warnOptions.add(glued);
                        at++;
                    } else {
                        if (!argumentExpected(option, args, at)) {
                            return USAGE_ERROR;
                        }
                        warnOptions.add(args[at + 1]);
                        at += 2;
                    }
                }
                case "-X" -> {
                    String configuration;
                    int consumed;
                    if (glued != null) {
                        configuration = glued;
                        consumed = 1;
                    } else {
                        if (!argumentExpected(option, args, at)) {
                            return USAGE_ERROR;
                        }
                        configuration = args[at + 1];
                        consumed = 2;
                    }
                    switch (configuration) {
                        case "faulthandler" -> {
                            // What the flag asks for is a stack trace when the process dies; the JVM
                            // already prints one, and the standard library's harness always passes it.
                            xOptions.add(configuration);
                        }
                        case "utf8" -> {
                            // Every byte this runtime reads and writes is already UTF-8, so the mode
                            // the flag selects is the one in force. `sys.flags.utf8_mode` says so.
                            xOptions.add(configuration);
                        }
                        case "dev" -> {
                            // Development mode is `-W default` plus `-X faulthandler`; the rest of it
                            // (malloc debugging, checked builds) belongs to a C runtime this one is not.
                            xOptions.add(configuration);
                            devMode = true;
                        }
                        case "tracemalloc" -> {
                            return notImplemented(
                                    "-X tracemalloc", "this runtime has no tracemalloc module");
                        }
                        case "importtime" -> {
                            return notImplemented(
                                    "-X importtime",
                                    "timing every import on stderr is not implemented");
                        }
                        default -> {
                            if (configuration.startsWith("int_max_str_digits")) {
                                return notImplemented(
                                        "-X " + configuration,
                                        "this runtime enforces no digit limit on int-to-text"
                                                + " conversion");
                            }
                            return notImplemented(
                                    "-X " + configuration,
                                    "-X utf8, -X dev and -X faulthandler are the keys this runtime"
                                            + " implements");
                        }
                    }
                    at += consumed;
                }
                case "-O", "-OO" -> {
                    // `-OO` is `-O` twice in the reference: the second one strips docstrings too.
                    optimize = option.equals("-OO") ? 2 : 1;
                    at++;
                }
                case "-b", "-bb" -> {
                    return notImplemented(
                            option,
                            "BytesWarning on a bytes/str mixed comparison is not raised by this"
                                    + " runtime");
                }
                case "-q" -> {
                    // It silences the interactive banner, and this runtime prints none: the promise
                    // already holds, and `sys.flags.quiet` reports it.
                    quiet = true;
                    at++;
                }
                case "-B" -> {
                    // No `.pyc` is ever written, so the flag asks for what already holds.
                    dontWriteBytecode = true;
                    at++;
                }
                case "-S" -> {
                    // There is no `site` import to suppress.
                    noSite = true;
                    at++;
                }
                case "-s" -> {
                    // No user site directory is ever added to `sys.path`.
                    noUserSite = true;
                    at++;
                }
                // `-E`, `-I` and `-u` are accepted and not applied: `PYTHONPATH` is still honoured
                // under them and both streams are already the JVM's own. `sys.flags` reports that
                // honestly, and the milestone notes name the gap.
                case "-E", "-I", "-u" -> at++;
                default -> {
                    write("Unknown option: " + option + "\n" + USAGE);
                    return USAGE_ERROR;
                }
            }
        }

        if (devMode) {
            // The reference adds development mode's filter before any `-W` the user wrote, whichever
            // order the two switches came in.
            warnOptions.add(0, "default");
        }
        PyRuntime.setCommandLine(
                new PyRuntime.CommandLine(
                        List.copyOf(warnOptions),
                        List.copyOf(xOptions),
                        devMode,
                        quiet,
                        dontWriteBytecode,
                        noSite,
                        noUserSite,
                        optimize));

        List<String> operands = new ArrayList<>();
        for (int i = at; i < args.length; i++) {
            operands.add(args[i]);
        }

        if (command != null) {
            start(importPath(""));
            PyRuntime.setArgv("-c", operands);
            PyCode compiled = Cpython314Compiler.compileUnit(command, "<string>", "exec", 0, optimize, -1);
            // The command is the only copy of this source there is: a report of a failure in it has
            // to quote it, because no file named <string> exists to read. CPython registers the same
            // text in linecache, for the same reason.
            TracebackReport.registerSource(compiled, command);
            return execute(compiled, null);
        }
        if (stdin) {
            start(importPath(""));
            PyRuntime.setArgv("-", operands);
            return execute(Cpython314Compiler.compileUnit(
                    readStdin(), "<stdin>", "exec", 0, optimize, -1), "<stdin>");
        }
        if (operands.isEmpty()) {
            return notImplemented(
                    "the interactive prompt",
                    "a terminal, a line editor and a read-eval-print loop belong to the native"
                            + " frontend milestone. Pass a file, -c cmd, or - for stdin");
        }

        Path script;
        try {
            script = Path.of(operands.get(0));
        } catch (InvalidPathException unreadable) {
            write(PROGRAM + ": can't open file '" + operands.get(0)
                    + "': [Errno 2] No such file or directory\n");
            return USAGE_ERROR;
        }
        if (Files.isDirectory(script)) {
            return notImplemented(
                    "running the directory '" + script + "'",
                    "a directory is entered through its __main__.py, which needs runpy on top of"
                            + " the import system");
        }
        if (!Files.isReadable(script)) {
            // CPython resolves the name before complaining, so the path it prints is the working
            // directory's own spelling of itself rather than whatever was typed.
            write(PROGRAM + ": can't open file '" + Cpython314Compiler.absolutePath(script)
                    + "': [Errno 2] No such file or directory\n");
            return USAGE_ERROR;
        }
        PyRuntime.setArgv(operands.get(0), operands.subList(1, operands.size()));
        start(importPath(scriptDirectory(script)));
        Cpython314Compiler.Compiled compiled = Cpython314Compiler.compileFile(script, optimize);
        return execute(compiled.code(), compiled.fileName());
    }

    /** Bring the runtime up with this process's import path, and install the compiler it needs. */
    private static void start(List<String> directories) {
        PyRuntime.setSysPath(directories);
        PyRuntime.startup();
        // After startup, because the finders it installs go into the module table startup builds.
        JavaInterop.install();
        PyRuntime.setSourceCompiler(Cpython314Compiler.COMPILER);
        // `-W` and `PYTHONWARNINGS` are replayed by `warnings` itself when it is imported, so the
        // reference imports it during startup whenever it has options; doing the same is what puts
        // the module in `sys.modules` before the script runs and what reports a malformed option.
        if (!PyRuntime.warnOptions().isEmpty()) {
            try {
                PyRuntime.importModule("warnings");
            } catch (PyRaise missing) {
                if (!missing.value().matches(ExceptionTypes.MODULE_NOT_FOUND_ERROR)) {
                    throw missing;
                }
            }
        }
    }

    /**
     * The first {@code sys.path} entry, then {@code PYTHONPATH}.
     *
     * <p>The reference puts the script's directory first, resolved through symlinks, and the empty
     * string — the working directory — first for {@code -c} and stdin. {@code PYTHONPATH} follows in
     * both cases, split on the platform's path separator. {@code -E} and {@code -I} are accepted and
     * ignored, so {@code PYTHONPATH} is honoured even under them; that omission is named in the
     * milestone notes.
     */
    private static List<String> importPath(String first) {
        List<String> directories = new ArrayList<>();
        directories.add(first);
        String fromEnvironment = System.getenv("PYTHONPATH");
        if (fromEnvironment != null) {
            for (String entry : fromEnvironment.split(
                    java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!entry.isEmpty()) {
                    directories.add(entry);
                }
            }
        }
        return directories;
    }

    /** The directory the script lives in, as the reference spells it in {@code sys.path[0]}. */
    private static String scriptDirectory(Path script) {
        try {
            Path directory = script.toRealPath().getParent();
            return directory == null ? "" : directory.toString();
        } catch (IOException unreadable) {
            Path directory = script.toAbsolutePath().getParent();
            return directory == null ? "" : directory.toString();
        }
    }

    /** CPython's own complaint about an option that lost its argument. */
    private static boolean argumentExpected(String option, String[] args, int at) {
        if (at + 1 < args.length) {
            return true;
        }
        write("Argument expected for the " + option + " option\n" + USAGE);
        return false;
    }

    /**
     * Run the program in {@code __main__}, the same namespace an embedder reaches through
     * {@code PythonInterpreter.interp()}.
     *
     * <p>The file name is what tells the three forms apart inside the runtime: a script's path, the
     * word {@code <stdin>}, or null for {@code -c}, which is a program with no file and therefore no
     * {@code __file__} at all.
     */
    private static int execute(PyCode code, String fileName) {
        PyRuntime.execMain(code, fileName);
        return PASSED;
    }

    /**
     * Run one regrtest target: {@code test/<target>.py} as the main program.
     *
     * <p>The harness starts this launcher with the reference library as the working directory, so the
     * relative {@code test/} name is the module the target means. The file is run rather than imported,
     * because a suite written for regrtest ends in {@code unittest.main()} and that finds its tests on the
     * module that is running. The library root, not the test directory, goes on {@code sys.path}: a suite
     * imports {@code test.support} and that needs the root there, the same empty entry {@code -c} uses.
     *
     * <p>The suites need the standard library M4 assembled and the constructs M5 and M6-a still owe;
     * today the first one raises {@code ModuleNotFoundError} or {@code NotImplementedError} and the exit
     * status is 1, which the harness records as a failure. A target that is not in the library is
     * reported as unimplemented rather than as skipped: exit code 2 means "skipped" to the harness and
     * would hide the fact that nothing was run.
     */
    private static int harness(String[] args, int at) {
        if (at + 1 >= args.length) {
            write("Argument expected for the " + HARNESS_FLAG + " option\n" + USAGE);
            return NOT_IMPLEMENTED;
        }
        String target = args[at + 1];
        Path test = Path.of("test", target + ".py");
        if (!Files.isReadable(test)) {
            return notImplemented(
                    "the regrtest target " + target,
                    "no test/" + target + ".py under the working directory");
        }
        start(importPath(""));
        PyRuntime.setArgv(test.toString(), List.of());
        Cpython314Compiler.Compiled compiled =
                Cpython314Compiler.compileFile(test, PyRuntime.optimizeLevel());
        return execute(compiled.code(), compiled.fileName());
    }

    private static int notImplemented(String what, String why) {
        write(PROGRAM + ": " + what + " is not implemented yet (" + why + ").\n"
                + "See PLAN.md for the milestone that owns it.\n");
        return NOT_IMPLEMENTED;
    }

    private static String version() {
        return PROGRAM + " " + PROGRAM_VERSION + " (Python " + PyRuntime.pythonVersion()
                + ") on Java " + System.getProperty("java.version", "unknown");
    }

    /**
     * Hand an exception that outlived every {@code except} to {@code sys.excepthook}.
     *
     * <p>CPython's {@code _PyErr_PrintEx} is the order this follows, and the order is most of what the
     * slice is about. {@code handle_system_exit} runs first, on the value while it is still the raised
     * one, and it looks at {@code KeyboardInterrupt} before it looks at {@code SystemExit}: an exit
     * leaves immediately, with the status the argument named and no word printed, but an interrupt is
     * only noted and goes on to the hook like any other error. The four {@code sys.last_} names are
     * filed before the hook is even read, so a hook that looks at them finds the exception it was called
     * for. The read is optional, and a program that deleted the name gets {@code sys.excepthook is
     * missing} above the report rather than a complaint about it.
     *
     * <p>Only an error from the hook itself is wrapped. The hook is called with the value's class, the
     * value and the traceback it carries — the class because CPython passes {@code Py_TYPE(exc)} and
     * ignores it, the traceback because that is what {@code sys.excepthook} documents — and what it
     * raises is shown under {@code Error in sys.excepthook:} with the original beneath {@code Original
     * exception was:}. A {@code SystemExit} raised from inside a hook still wins over all of that text,
     * because the second {@code handle_system_exit} call exits the process where it stands.
     *
     * <p>The note an interrupt leaves behind is what the process exits with: 130, which is CPython
     * raising {@code SIGINT} at itself so that a shell learns the user pressed {@code ^C}. It is 130
     * whether the interrupt was the escaped exception or only what the hook raised, and a hook that
     * swallows the error does not change it — the note was made before the hook ran.
     */
    private static int reportUncaught(PyRaise raised) {
        PyBaseException value = raised.value();
        boolean interrupted = value.exceptionType().matches(ExceptionTypes.KEYBOARD_INTERRUPT);
        if (!interrupted && value.exceptionType().matches(ExceptionTypes.SYSTEM_EXIT)) {
            return exitStatus(value);
        }

        PyObject traceback = value.getAttr("__traceback__");
        PyRuntime.setSysAttribute("last_exc", value);
        PyRuntime.setSysAttribute("last_type", value.exceptionType());
        PyRuntime.setSysAttribute("last_value", value);
        PyRuntime.setSysAttribute("last_traceback", traceback);

        PyObject hook = PyRuntime.sysAttribute("excepthook");
        if (hook == null) {
            write("sys.excepthook is missing\n");
            TracebackReport.print(value);
            return interrupted ? INTERRUPTED : FAILED;
        }
        try {
            Slots.call(hook, new PyObject[] {value.exceptionType(), value, traceback}, null);
        } catch (PyRaise refused) {
            PyBaseException fromHook = refused.value();
            if (fromHook.exceptionType().matches(ExceptionTypes.SYSTEM_EXIT)) {
                return exitStatus(fromHook);
            }
            interrupted |=
                    fromHook.exceptionType().matches(ExceptionTypes.KEYBOARD_INTERRUPT);
            // CPython flushes C's stdout here, which is not the buffer print() writes into: what a
            // hook printed still arrives only when the process ends. This runtime has one buffer, so
            // the matching behaviour is to leave it alone.
            write("Error in sys.excepthook:\n");
            TracebackReport.print(fromHook);
            write("\nOriginal exception was:\n");
            TracebackReport.print(value);
        }
        return interrupted ? INTERRUPTED : FAILED;
    }

    /** {@code sys.exit(n)}: no argument is 0, an int is itself, anything else is printed and 1. */
    private static int exitStatus(PyBaseException value) {
        int given = value.args().size();
        if (given == 0) {
            return PASSED;
        }
        PyObject argument = value.args().at(0);
        if (given == 1) {
            if (argument instanceof PyInt integer) {
                return integer.getValue().intValue();
            }
            if (argument instanceof PyBool flag) {
                return flag.getValue() ? FAILED : PASSED;
            }
            if (argument instanceof PyNone) {
                return PASSED;
            }
        }
        // A string argument, a tuple from two arguments, anything else: CPython prints the value
        // on stderr and reports failure.
        write(argument instanceof PyStr text ? text.getValue() : value.args().repr());
        write("\n");
        return FAILED;
    }

    private static String readStdin() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read stdin", e);
        }
    }

    private static void write(String text) {
        PyRuntime.stderr().writeText(text);
    }

    private jy3() {}
}
