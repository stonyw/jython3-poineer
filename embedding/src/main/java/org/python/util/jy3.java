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
 * quietly ignored: {@code -m} needs the import finder (M4), {@code -O} needs {@code __debug__} to mean
 * something to the code object (M3), and the interactive prompt needs a terminal, a line editor and a
 * read-eval-print loop (M4). Those exit 3, which the harness records as {@code NOT_IMPLEMENTED}. The
 * handful of options accepted and ignored are ones that change nothing a script can observe yet — {@code
 * -B}, because no {@code .pyc} is ever written, and {@code -u}, because both streams are already the
 * JVM's own.
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
    private static final String PROGRAM_VERSION = "0.1.0-M2";

    private static final String USAGE =
            "usage: " + PROGRAM + " [option] ... [-c cmd | -m mod | file | -] [arg] ...\n"
                    + "Try `" + PROGRAM + " -h' for more information.\n";

    private static final String HELP =
            PROGRAM + " [option] ... [-c cmd | -m mod | file | -] [arg] ...\n"
                    + "Options and arguments:\n"
                    + "-c cmd : program read from string\n"
                    + "-h     : print this help message and exit\n"
                    + "-V     : print the version and exit\n"
                    + "-      : program read from stdin\n"
                    + "\n"
                    + "Accepted and ignored: -B -E -I -S -s -u\n"
                    + "Not implemented yet: -m mod and -X opt (import system, M4);"
                    + " -O and -OO (M3); the interactive prompt (M4)\n";

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
                            "-m module", "the import finder that reads sys.path is M4");
                }
                case "-X" -> {
                    if (!argumentExpected(option, args, at)) {
                        return USAGE_ERROR;
                    }
                    return notImplemented("-X option", "the interpreter-config layer is M4");
                }
                case "-O", "-OO" -> {
                    return notImplemented(
                            option, "__debug__ and the optimize level have to reach the code object in M3");
                }
                case "-B", "-E", "-I", "-S", "-s", "-u" -> at++;
                default -> {
                    write("Unknown option: " + option + "\n" + USAGE);
                    return USAGE_ERROR;
                }
            }
        }

        List<String> operands = new ArrayList<>();
        for (int i = at; i < args.length; i++) {
            operands.add(args[i]);
        }
        PyRuntime.startup();
        // After startup, because the finder it registers goes into the module table startup builds.
        JavaInterop.install();

        if (command != null) {
            PyRuntime.setArgv("-c", operands);
            PyCode compiled = Cpython314Compiler.compileSource(command, "<string>");
            // The command is the only copy of this source there is: a report of a failure in it has
            // to quote it, because no file named <string> exists to read. CPython registers the same
            // text in linecache, for the same reason.
            TracebackReport.registerSource(compiled, command);
            return execute(compiled, null);
        }
        if (stdin) {
            PyRuntime.setArgv("-", operands);
            return execute(Cpython314Compiler.compileSource(readStdin(), "<stdin>"), null);
        }
        if (operands.isEmpty()) {
            return notImplemented(
                    "the interactive prompt",
                    "a terminal, a line editor and a read-eval-print loop are M4."
                            + " Pass a file, -c cmd, or - for stdin");
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
                    "a directory is entered through its __main__.py, which is the M4 import finder");
        }
        if (!Files.isReadable(script)) {
            // CPython resolves the name before complaining, so the path it prints is the working
            // directory's own spelling of itself rather than whatever was typed.
            write(PROGRAM + ": can't open file '" + Cpython314Compiler.absolutePath(script)
                    + "': [Errno 2] No such file or directory\n");
            return USAGE_ERROR;
        }
        PyRuntime.setArgv(operands.get(0), operands.subList(1, operands.size()));
        Cpython314Compiler.Compiled compiled = Cpython314Compiler.compileFile(script);
        return execute(compiled.code(), compiled.fileName());
    }

    /** CPython's own complaint about an option that lost its argument. */
    private static boolean argumentExpected(String option, String[] args, int at) {
        if (at + 1 < args.length) {
            return true;
        }
        write("Argument expected for the " + option + " option\n" + USAGE);
        return false;
    }

    private static int execute(PyCode code, String fileName) {
        // Into __main__, the same namespace an embedder reaches with PythonInterpreter.interp().
        PyRuntime.exec(code, PyRuntime.mainModule(), fileName);
        return PASSED;
    }

    private static int harness(String[] args, int at) {
        String target = at + 1 < args.length ? args[at + 1] : "no target";
        return notImplemented(
                "the regrtest target " + target,
                "a test module is an import, and its support code is the standard library: M4");
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
