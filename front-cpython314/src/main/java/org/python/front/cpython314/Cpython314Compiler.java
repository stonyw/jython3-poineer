package org.python.front.cpython314;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.python.core.PyCode;

/**
 * Stage A of decision D1: CPython 3.14 is the compiler, and this class is the whole seam.
 *
 * <p>It runs the reference interpreter in a subprocess, asks it to {@code compile()} one unit of
 * source and {@code marshal.dumps} the result, then reads that stream with {@link MarshalReader}.
 * Nothing else in the build may know that a subprocess exists: above this class a {@link PyCode} is
 * just a {@link PyCode}, which is what lets Stage B's native parser replace the body of
 * {@link #compile} without touching the interpreter.
 *
 * <p>The marshalled bytes go to stdout and the child's diagnostics go to a temporary file rather than
 * to a second pipe. That is deliberate: two pipes must be pumped concurrently or a child that writes
 * much to both blocks once one buffer fills, and {@code compile()} of a large module can write a
 * deprecation warning while its marshal output is still in flight.
 *
 * <p>Two failures are possible here and they belong to different people. A {@link CompilationRefused}
 * is the user's: the source is bad, and CPython's own report — caret, offset, the lot — is passed
 * through verbatim, because a hand-written Java imitation of that layout would be a worse
 * reproduction of CPython than simply quoting it. Anything else is ours: no interpreter on the
 * {@code PATH}, the wrong version, a crash inside the driver.
 */
public final class Cpython314Compiler {

    /** The reference interpreter, resolved the way the build resolves it: property, then env, then PATH. */
    private static final String EXECUTABLE_PROPERTY = "jython3.refPython";
    private static final String EXECUTABLE_ENVIRONMENT = "JY3_REF_PYTHON";
    private static final String DEFAULT_EXECUTABLE = "python3.14";

    /** Exit code the driver uses to say "I am not 3.14", which is our bug, not the user's. */
    private static final int WRONG_VERSION = 64;

    /**
     * The driver, executed by the reference interpreter with {@code -c}.
     *
     * <p>Argv is {@code mode payload fileName}: {@code f} reads the payload as a path, {@code s} uses
     * it as the source itself. {@code dont_inherit=True} is not decoration — without it, a compile
     * flag inherited from the parent ({@code -O}, or {@code __debug__} off) would change the bytecode
     * the launcher never asked for. {@code -O} has to be an explicit decision of ours, at the point
     * where {@code sys.flags} can report it.
     */
    private static final String DRIVER =
            """
            import marshal
            import os
            import sys
            import traceback

            if sys.version_info[:2] != (3, 14):
                sys.stderr.write(
                    "jy3: Stage A needs CPython 3.14, but %s is %d.%d\\n"
                    % (sys.executable, *sys.version_info[:2]))
                raise SystemExit(64)

            mode, payload, file_name = sys.argv[1], sys.argv[2], sys.argv[3]
            if mode == "f":
                with open(payload, "rb") as stream:
                    source = stream.read()
                file_name = os.path.abspath(file_name)
            else:
                source = payload.encode("utf-8")
            try:
                code = compile(source, file_name, "exec", dont_inherit=True)
            except SyntaxError as error:
                # The report the interpreter would have printed for the script, and nothing of the
                # driver: a traceback through this string would be ours to explain, not the user's.
                sys.stderr.write("".join(traceback.format_exception_only(error)))
                raise SystemExit(1)
            sys.stdout.buffer.write(marshal.dumps(code))
            """;

    private Cpython314Compiler() {
    }

    /** The compiled unit plus the name CPython gave its code object, which is also {@code __file__}. */
    public record Compiled(PyCode code, String fileName) {}

    /**
     * Compile {@code script} as a module, the way {@code python3.14 script.py} does.
     *
     * <p>The reported file name is an absolute path made the way {@code os.path.abspath} makes one.
     * The launcher needs that exact string: the reference interpreter puts it in both {@code __file__}
     * and the {@code File "..."} line of a syntax error, and a path resolved by Java instead of by the
     * child differs whenever the working directory is reached through a symlink.
     */
    public static Compiled compileFile(Path script) {
        String resolved = absolutePath(script);
        return new Compiled(compile("f", script.toString(), resolved), resolved);
    }

    /**
     * Compile source that has no file: the {@code -c} argument, or a script read from stdin.
     *
     * @param fileName what {@code co_filename} reports; CPython uses {@code <string>} and
     *     {@code <stdin>} for these two
     */
    public static PyCode compileSource(String source, String fileName) {
        return compile("s", source, fileName);
    }

    /** The reference interpreter this build will invoke, for reporting and for tests. */
    public static String executable() {
        String configured = System.getProperty(EXECUTABLE_PROPERTY);
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv(EXECUTABLE_ENVIRONMENT);
        }
        return configured == null || configured.isEmpty() ? DEFAULT_EXECUTABLE : configured;
    }

    /** {@code os.path.abspath} of a path that exists, without a subprocess. */
    public static String absolutePath(Path script) {
        // normalize() rather than toRealPath(): abspath resolves the working directory, not the
        // file, so a symlinked script keeps the name it was invoked by.
        return Path.of("").toAbsolutePath().resolve(script).normalize().toString();
    }

    private static PyCode compile(String mode, String payload, String fileName) {
        File diagnostics = temporaryFile();
        try {
            Process process = invoke(mode, payload, fileName, diagnostics);
            byte[] marshal;
            try (InputStream out = process.getInputStream()) {
                marshal = out.readAllBytes();
            }
            int status = waitFor(process);
            if (status == 0) {
                return MarshalReader.readCode(marshal, 0);
            }
            String report = read(diagnostics);
            if (status == WRONG_VERSION) {
                throw new CompilerUnavailable(report.strip());
            }
            if (marshal.length != 0) {
                throw new CompilerUnavailable(
                        "the reference compiler exited " + status + " after writing bytecode: "
                                + report.strip());
            }
            throw new CompilationRefused(report);
        } catch (IOException e) {
            throw new CompilerUnavailable(
                    "cannot run '" + executable() + "' as the Stage A compiler: " + e.getMessage());
        } finally {
            diagnostics.delete();
        }
    }

    private static Process invoke(String mode, String payload, String fileName, File diagnostics)
            throws IOException {
        ProcessBuilder builder =
                new ProcessBuilder(
                        executable(), "-I", "-S", "-c", DRIVER, mode, payload, fileName)
                        .redirectError(diagnostics);
        return builder.start();
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CompilerUnavailable("interrupted while compiling with the reference interpreter");
        }
    }

    private static File temporaryFile() {
        try {
            return File.createTempFile("jy3-stage-a-", ".txt");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create a file for the compiler's stderr", e);
        }
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "the reference compiler gave no explanation (exit status unread: " + e.getMessage() + ")";
        }
    }

    /**
     * The source did not compile.
     *
     * <p>{@link #report()} is CPython's own message, already in the form a Python user expects; the
     * launcher prints it and exits 1, which is what {@code python3.14} does.
     */
    public static final class CompilationRefused extends RuntimeException {

        private final String report;

        CompilationRefused(String report) {
            super(report.strip(), null, false, false);
            this.report = report;
        }

        public String report() {
            return report;
        }
    }

    /** Stage A could not be run at all: no reference interpreter, or the wrong one. */
    public static final class CompilerUnavailable extends RuntimeException {

        CompilerUnavailable(String message) {
            super(message, null, false, false);
        }

        /** What to tell the user, including how to point at a different interpreter. */
        public String advice() {
            return getMessage()
                    + "\nInstall CPython 3.14, or set "
                    + EXECUTABLE_ENVIRONMENT
                    + " (or -P"
                    + EXECUTABLE_PROPERTY
                    + "=<bin>) to its path.";
        }
    }
}
