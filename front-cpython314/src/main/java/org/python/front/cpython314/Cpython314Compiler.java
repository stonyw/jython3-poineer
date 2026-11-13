package org.python.front.cpython314;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.python.core.PyCode;
import org.python.core.SourceCompiler;
import org.python.core.SourceRefused;

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
     * <p>Argv is {@code payload-kind payload fileName code-kind}: the first {@code kind} says whether
     * the payload is a path ({@code f}) or the source itself ({@code s}), the second which compile
     * mode to ask for ({@code exec} for a module's top level, {@code eval} for one expression) or,
     * when it starts with {@code ast-}, which parse mode to hand {@code ast.parse}. An AST answer is
     * the tree written the way {@code ast.dump} writes it, not a code object: the runtime owns the
     * node classes, so the only thing that crosses is their constructor expression.
     * {@code dont_inherit=True} is not decoration — without it, a compile flag inherited from the
     * parent ({@code -O}, or {@code __debug__} off) would change the bytecode the launcher never
     * asked for. {@code -O} has to be an explicit decision of ours, at the point where
     * {@code sys.flags} can report it.
     */
    private static final String DRIVER =
            """
            import base64
            import marshal
            import os
            import sys
            import traceback

            if sys.version_info[:2] != (3, 14):
                sys.stderr.write(
                    "jy3: Stage A needs CPython 3.14, but %s is %d.%d\\n"
                    % (sys.executable, *sys.version_info[:2]))
                raise SystemExit(64)

            payload_kind, payload, file_name, code_kind = (
                sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
            flags, optimize, feature_version = map(int, sys.argv[5:8])
            if payload_kind == "f":
                with open(payload, "rb") as stream:
                    source = stream.read()
                file_name = os.path.abspath(file_name)
            elif payload_kind == "b":
                source = base64.b64decode(payload)
            else:
                source = payload.encode("utf-8")
            try:
                if code_kind.startswith("ast-"):
                    import ast

                    # A constructor expression for the tree, carrying every field the parser set --
                    # including the optional ones it left as None.  `ast.dump` drops those None fields,
                    # and a rebuilt _ast node never puts a dropped field back, so the round trip needs
                    # its own writer rather than dump's readable form.  Positions come last, in the
                    # order `_attributes` names them.
                    def write(node):
                        if isinstance(node, ast.AST):
                            parts = []
                            for name in node._fields:
                                try:
                                    value = getattr(node, name)
                                except AttributeError:
                                    continue
                                parts.append("%s=%s" % (name, write(value)))
                            for name in node._attributes:
                                try:
                                    value = getattr(node, name)
                                except AttributeError:
                                    continue
                                parts.append("%s=%s" % (name, write(value)))
                            return "%s(%s)" % (type(node).__name__, ", ".join(parts))
                        if isinstance(node, list):
                            return "[%s]" % ", ".join(write(item) for item in node)
                        return repr(node)

                    tree = ast.parse(source, file_name, code_kind[4:])
                    sys.stdout.buffer.write(write(tree).encode("utf-8"))
                else:
                    options = {"dont_inherit": True}
                    if flags:
                        options["flags"] = flags
                    if optimize != -1:
                        options["optimize"] = optimize
                    if feature_version != -1:
                        options["_feature_version"] = feature_version
                    code = compile(source, file_name, code_kind, **options)
                    sys.stdout.buffer.write(marshal.dumps(code))
            except (SyntaxError, ValueError) as error:
                # The report the interpreter would have printed for the script, and nothing of the
                # driver: a traceback through this string would be ours to explain, not the user's.
                # ValueError is what an expression compile mode gives for a statement source.
                #
                # The `JY3-COMPILE-ERROR` block ahead of the report is for the runtime, which rebuilds
                # this exception and raises it in Python; the text after `JY3-REPORT` is for the
                # launcher, which quotes it verbatim at top level.  A field is `!` for None, a plain
                # integer, or base64 of the UTF-8 text -- the encoding is line-oriented because the
                # only thing that crosses is one error, never a stream.
                def encode(value):
                    if value is None:
                        return "!"
                    if isinstance(value, int):
                        return str(value)
                    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")

                fields = [("type", type(error).__name__)]
                if isinstance(error, SyntaxError):
                    fields += [
                        ("msg", error.msg),
                        ("filename", error.filename),
                        ("lineno", error.lineno),
                        ("offset", error.offset),
                        ("text", error.text),
                        ("end_lineno", error.end_lineno),
                        ("end_offset", error.end_offset),
                    ]
                else:
                    fields.append(("msg", str(error)))
                sys.stderr.write("JY3-COMPILE-ERROR\\n")
                for name, value in fields:
                    sys.stderr.write("%s:%s\\n" % (name, encode(value)))
                sys.stderr.write("JY3-REPORT\\n")
                sys.stderr.write("".join(traceback.format_exception_only(error)))
                raise SystemExit(1)
            """;

    private Cpython314Compiler() {
    }

    /**
     * The compiler as the runtime's seam: {@code import} asks for a module, {@code eval()} for an
     * expression, and the reference compiler answers both.
     */
    public static final SourceCompiler COMPILER =
            new SourceCompiler() {
                @Override
                public PyCode compile(String source, String fileName) {
                    return compileSource(source, fileName);
                }

                @Override
                public PyCode compileExpression(String source, String fileName) {
                    return Cpython314Compiler.compileExpression(source, fileName);
                }

                @Override
                public PyCode compile(
                        String source,
                        String fileName,
                        String mode,
                        int flags,
                        int optimize,
                        int featureVersion) {
                    return Cpython314Compiler.compileUnit(
                            source, fileName, mode, flags, optimize, featureVersion);
                }

                @Override
                public PyCode compileBytes(
                        byte[] source,
                        String fileName,
                        String mode,
                        int flags,
                        int optimize,
                        int featureVersion) {
                    return Cpython314Compiler.compileBytes(
                            source, fileName, mode, flags, optimize, featureVersion);
                }

                @Override
                public String parseAst(String source, String fileName, String mode) {
                    return Cpython314Compiler.parseAst(source, fileName, mode);
                }
            };

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
    public static Compiled compileFile(Path script, int optimize) {
        String resolved = absolutePath(script);
        return new Compiled(compile("f", script.toString(), resolved, "exec", 0, optimize, -1), resolved);
    }

    /** Compile a file at the level the launcher was given, for callers with no {@code -O} of their own. */
    public static Compiled compileFile(Path script) {
        return compileFile(script, -1);
    }

    /**
     * Compile source that has no file: the {@code -c} argument, or a script read from stdin.
     *
     * @param fileName what {@code co_filename} reports; CPython uses {@code <string>} and
     *     {@code <stdin>} for these two
     */
    public static PyCode compileSource(String source, String fileName) {
        return compile("s", source, fileName, "exec", 0, -1, -1);
    }

    /**
     * Compile one unit with the options {@code compile()} takes, which is the only path that can ask
     * for {@code single}, a compiler flag, or an optimization level other than the interpreter's own.
     */
    public static PyCode compileUnit(
            String source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion) {
        return compile("s", source, fileName, mode, flags, optimize, featureVersion);
    }

    /** Compile bytes, leaving the encoding declaration in them to the reference decoder. */
    public static PyCode compileBytes(
            byte[] source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion) {
        String payload = Base64.getEncoder().encodeToString(source);
        return compile("b", payload, fileName, mode, flags, optimize, featureVersion);
    }

    /**
     * Compile one expression, the way {@code eval()} does rather than {@code exec()}.
     *
     * <p>The difference is not a wrapper around {@code exec}: an {@code eval}-mode code object leaves
     * the value of the expression as its return value, while a module's top level returns {@code None}
     * and communicates only through its namespace. A statement source is refused the way CPython
     * refuses it, as {@link CompilationRefused}.
     */
    public static PyCode compileExpression(String source, String fileName) {
        return compile("s", source, fileName, "eval", 0, -1, -1);
    }

    /**
     * Parse {@code source} and answer the tree as the constructor text {@code _ast} rebuilds.
     *
     * <p>The reference parser is the authority on the shape of a tree; the runtime is the authority on
     * the node classes. What crosses the seam is therefore neither object nor class, but the text
     * {@code ast.dump} writes — one expression naming classes the runtime defines.
     *
     * @param mode the {@code ast.parse} mode: {@code exec}, {@code eval}, {@code single} or
     *     {@code func_type}
     */
    public static String parseAst(String source, String fileName, String mode) {
        return new String(run("s", source, fileName, "ast-" + mode, 0, -1, -1), StandardCharsets.UTF_8);
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

    private static PyCode compile(
            String payloadKind,
            String payload,
            String fileName,
            String codeKind,
            int flags,
            int optimize,
            int featureVersion) {
        return MarshalReader.readCode(
                run(payloadKind, payload, fileName, codeKind, flags, optimize, featureVersion), 0);
    }

    /**
     * Run the driver and answer the bytes it wrote on stdout — marshalled code, or an AST dump.
     *
     * <p>Every failure is turned into one of the two exceptions here, because both callers explain a
     * refusal the same way: CPython's own report is the user's, anything else is ours.
     */
    private static byte[] run(
            String payloadKind,
            String payload,
            String fileName,
            String codeKind,
            int flags,
            int optimize,
            int featureVersion) {
        File diagnostics = temporaryFile();
        try {
            Process process = invoke(
                    payloadKind, payload, fileName, codeKind, flags, optimize, featureVersion,
                    diagnostics);
            byte[] out;
            try (InputStream stream = process.getInputStream()) {
                out = stream.readAllBytes();
            }
            int status = waitFor(process);
            if (status == 0) {
                return out;
            }
            String report = read(diagnostics);
            if (status == WRONG_VERSION) {
                throw new CompilerUnavailable(report.strip());
            }
            if (out.length != 0) {
                throw new CompilerUnavailable(
                        "the reference compiler exited " + status + " after writing output: "
                                + report.strip());
            }
            throw refused(report);
        } catch (IOException e) {
            throw new CompilerUnavailable(
                    "cannot run '" + executable() + "' as the Stage A compiler: " + e.getMessage());
        } finally {
            diagnostics.delete();
        }
    }

    private static Process invoke(
            String payloadKind,
            String payload,
            String fileName,
            String codeKind,
            int flags,
            int optimize,
            int featureVersion,
            File diagnostics)
            throws IOException {
        ProcessBuilder builder =
                new ProcessBuilder(
                        executable(), "-I", "-S", "-c", DRIVER,
                        payloadKind, payload, fileName, codeKind,
                        Integer.toString(flags),
                        Integer.toString(optimize),
                        Integer.toString(featureVersion))
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

    /** The markers the driver writes ahead of the report when the source does not compile. */
    private static final String ERROR_MARKER = "JY3-COMPILE-ERROR\n";
    private static final String REPORT_MARKER = "JY3-REPORT\n";

    /**
     * Read the driver's refusal: the structured fields it wrote, and the report a person reads.
     *
     * <p>Anything that is not the structured block is an older or unexpected explanation, which is
     * still a refusal — the report alone is enough for the launcher, and no field is invented for it.
     */
    private static CompilationRefused refused(String diagnostics) {
        int start = diagnostics.indexOf(ERROR_MARKER);
        int end = diagnostics.indexOf(REPORT_MARKER, start);
        if (start < 0 || end < 0) {
            return new CompilationRefused(diagnostics);
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : diagnostics.substring(start + ERROR_MARKER.length(), end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                fields.put(line.substring(0, colon), line.substring(colon + 1));
            }
        }
        return new CompilationRefused(
                diagnostics.substring(end + REPORT_MARKER.length()),
                text(fields.get("type")),
                text(fields.get("msg")),
                text(fields.get("filename")),
                number(fields.get("lineno")),
                number(fields.get("offset")),
                text(fields.get("text")),
                number(fields.get("end_lineno")),
                number(fields.get("end_offset")));
    }

    private static String text(String field) {
        return field == null || field.equals("!")
                ? null
                : new String(Base64.getDecoder().decode(field), StandardCharsets.UTF_8);
    }

    private static long number(String field) {
        return field == null || field.equals("!")
                ? SourceRefused.ABSENT
                : Long.parseLong(field);
    }

    /**
     * The source did not compile.
     *
     * <p>{@link #report()} is CPython's own message, already in the form a Python user expects; the
     * launcher prints it and exits 1, which is what {@code python3.14} does. The fields behind it are
     * what {@link SourceRefused#python()} rebuilds the caught {@code SyntaxError} from.
     */
    public static final class CompilationRefused extends SourceRefused {

        CompilationRefused(String report) {
            this(report, null, null, null, ABSENT, ABSENT, null, ABSENT, ABSENT);
        }

        CompilationRefused(
                String report,
                String errorType,
                String message,
                String fileName,
                long lineno,
                long offset,
                String text,
                long endLineno,
                long endOffset) {
            super(report, errorType, message, fileName, lineno, offset, text, endLineno, endOffset);
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
