package org.python.front.cpython314;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.python.core.PyCode;
import org.python.core.PyRuntime;
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
            import warnings

            if sys.version_info[:2] != (3, 14):
                sys.stderr.write(
                    "jy3: Stage A needs CPython 3.14, but %s is %d.%d\\n"
                    % (sys.executable, *sys.version_info[:2]))
                raise SystemExit(64)

            import ast

            # A constructor expression for the tree, carrying every field the parser set --
            # including the optional ones it left as None.  `ast.dump` drops those None fields,
            # and a rebuilt _ast node never puts a dropped field back, so the round trip needs
            # its own writer rather than dump's readable form.  Positions come last, in the
            # order `_attributes` names them.
            #
            # `inf` and `nan` have no Python literal, so those two doubles get an arithmetic
            # stand-in; every other float is `repr`-safe.
            def write_float(value):
                if value != value:
                    return "(1e999-1e999)"
                if value == float("inf"):
                    return "1e999"
                if value == float("-inf"):
                    return "-1e999"
                return repr(value)

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
                if isinstance(node, float):
                    return write_float(node)
                return repr(node)

            # The runtime writes a tree it holds with these five helpers.  An unknown node class
            # keeps its name and the nearest base the reference knows, so a report about it names
            # the class the user wrote; a value that is not an AST node at all becomes an object
            # whose class carries the same name, and compile() then refuses it in its own words.
            def _jy3_node(name, base, fields):
                node = type(name, (base,), {})()
                for key, value in fields.items():
                    setattr(node, key, value)
                return node

            def _jy3_opaque(name):
                return type(name, (), {})()

            def _jy3_bytes(text):
                return base64.b64decode(text)

            def _jy3_bytearray(text):
                return bytearray(base64.b64decode(text))

            def _jy3_complex(real, imag):
                return complex(float.fromhex(real), float.fromhex(imag))

            payload_kind, payload, file_name, code_kind = (
                sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
            flags, optimize, feature_version = map(int, sys.argv[5:8])
            try:
                if payload_kind == "f":
                    with open(payload, "rb") as stream:
                        source = stream.read()
                    file_name = os.path.abspath(file_name)
                elif payload_kind == "a":
                    with open(payload, "rb") as stream:
                        text = stream.read().decode("utf-8", "surrogatepass")
                    namespace = dict(vars(ast))
                    namespace.update({
                        "_jy3_node": _jy3_node,
                        "_jy3_opaque": _jy3_opaque,
                        "_jy3_bytes": _jy3_bytes,
                        "_jy3_bytearray": _jy3_bytearray,
                        "_jy3_complex": _jy3_complex,
                    })
                    source = eval(text, namespace)
                elif payload_kind == "b":
                    source = base64.b64decode(payload)
                else:
                    source = payload.encode("utf-8")
                options = {"dont_inherit": True}
                if optimize != -1:
                    options["optimize"] = optimize
                if feature_version != -1:
                    options["_feature_version"] = feature_version
                if code_kind.startswith("ast-"):
                    options["flags"] = flags | ast.PyCF_ONLY_AST
                    inside = code_kind[4:]
                else:
                    if flags:
                        options["flags"] = flags
                    inside = code_kind
                # The parser raises `SyntaxWarning` itself, so a successful compile can carry warnings
                # the same way it carries bytes.  `catch_warnings(record=True)` keeps every filter the
                # process was started with -- an ignored warning stays ignored -- and collects what
                # would have been printed, which is what the runtime replays where compile() was
                # called.
                with warnings.catch_warnings(record=True) as raised:
                    result = compile(source, file_name, inside, **options)
                if raised:
                    sys.stderr.write("JY3-WARNINGS\\n")
                    for warning in raised:
                        sys.stderr.write("%s\\t%s\\t%s\\t%s\\n" % (
                            base64.b64encode(
                                str(warning.filename).encode("utf-8", "surrogatepass")
                            ).decode("ascii"),
                            warning.lineno,
                            base64.b64encode(
                                warning.category.__name__.encode("ascii")
                            ).decode("ascii"),
                            base64.b64encode(
                                str(warning.message).encode("utf-8", "surrogatepass")
                            ).decode("ascii")))
                    sys.stderr.write("JY3-WARNINGS-END\\n")
                if isinstance(result, ast.AST):
                    sys.stdout.buffer.write(write(result).encode("utf-8", "surrogatepass"))
                else:
                    sys.stdout.buffer.write(marshal.dumps(result))
            except (SyntaxError, ValueError, TypeError) as error:
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
                public String parseAst(
                        String source,
                        String fileName,
                        String mode,
                        int flags,
                        int optimize,
                        int featureVersion) {
                    return Cpython314Compiler.parseAst(
                            source, fileName, mode, flags, optimize, featureVersion);
                }

                @Override
                public String parseAstBytes(
                        byte[] source,
                        String fileName,
                        String mode,
                        int flags,
                        int optimize,
                        int featureVersion) {
                    return Cpython314Compiler.parseAstBytes(
                            source, fileName, mode, flags, optimize, featureVersion);
                }

                @Override
                public PyCode compileAst(
                        String tree,
                        String fileName,
                        String mode,
                        int flags,
                        int optimize,
                        int featureVersion) {
                    return Cpython314Compiler.compileAst(
                            tree, fileName, mode, flags, optimize, featureVersion);
                }

                @Override
                public String rebuildAst(
                        String tree,
                        String fileName,
                        String mode,
                        int flags,
                        int optimize,
                        int featureVersion) {
                    return Cpython314Compiler.rebuildAst(
                            tree, fileName, mode, flags, optimize, featureVersion);
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
     * @param flags {@code PyCF_TYPE_COMMENTS} and {@code PyCF_ALLOW_INCOMPLETE_INPUT}, which change
     *     what the parser keeps
     * @param optimize {@code 0}..{@code 2} asks for the tree {@code __debug__} is already folded out
     *     of, as {@code ast.parse(optimize=...)} does
     */
    public static String parseAst(
            String source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion) {
        return new String(
                run("s", source, fileName, "ast-" + mode, flags, optimize, featureVersion),
                StandardCharsets.UTF_8);
    }

    /** Parse bytes the way {@link #parseAst} parses text, leaving the encoding declaration to the decoder. */
    public static String parseAstBytes(
            byte[] source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion) {
        String payload = Base64.getEncoder().encodeToString(source);
        return new String(
                run("b", payload, fileName, "ast-" + mode, flags, optimize, featureVersion),
                StandardCharsets.UTF_8);
    }

    /**
     * Compile a tree the caller already holds, written as the constructor text
     * {@link #parseAst} answers.
     *
     * <p>The tree is written to a temporary file rather than to argv: a module of a few thousand
     * statements is well past the size an argument list may carry, and the parser path passes files
     * for the same reason.
     */
    public static PyCode compileAst(
            String tree,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion) {
        return MarshalReader.readCode(
                runTree(tree, fileName, mode, flags, optimize, featureVersion), 0);
    }

    /** Hand a tree back as the constructor text of the normalized tree, for an AST answer. */
    public static String rebuildAst(
            String tree,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion) {
        return new String(
                runTree(tree, fileName, mode, flags, optimize, featureVersion),
                StandardCharsets.UTF_8);
    }

    private static byte[] runTree(
            String tree,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion) {
        Path file = null;
        try {
            file = Files.createTempFile("jy3-stage-a-tree-", ".txt");
            Files.writeString(file, tree, StandardCharsets.UTF_8);
            return run("a", file.toString(), fileName, mode, flags, optimize, featureVersion);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot hand the tree to the reference compiler", e);
        } finally {
            if (file != null) {
                file.toFile().delete();
            }
        }
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
     * The answers the driver has already given, oldest evicted first so a long run keeps a bounded memory.
     *
     * <p>Every call is a fresh process, so compiling or parsing the same source twice costs the same as
     * the first time. `traceback` parses each frame's line — twice — and formats one failure several
     * times, and a test that fails once per case pays for the same lines again and again. A source
     * longer than {@link #CACHEABLE_PAYLOAD} is left out: a script is compiled once anyway, and the
     * key would hold a second copy of it.
     */
    private static final Map<String, Answer> ANSWERED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Answer> eldest) {
            return size() > 512;
        }
    };

    /** A code answer and the warnings its compile raised, which are replayed on every cache hit. */
    private record Answer(byte[] bytes, List<CompiledWarning> warnings) {
    }

    /** One warning the reference parser raised, at the position it reported. */
    private record CompiledWarning(String filename, int lineno, String category, String message) {
    }

    private static final int CACHEABLE_PAYLOAD = 8192;

    /**
     * Whether the payload <em>is</em> the text the answer belongs to.
     *
     * <p>Only two kinds qualify: a source string and the base-64 of a byte string. The other two hand the
     * driver something that merely names the text — a file path, or the path of a freshly written tree —
     * and a path can be written again with different contents between two calls, so remembering an answer
     * under it would hand the old text's code back for the new text.
     */
    private static boolean cacheable(String payloadKind, String payload) {
        return ("s".equals(payloadKind) || "b".equals(payloadKind))
                && payload.length() <= CACHEABLE_PAYLOAD;
    }

    /** The refusals seen so far: a source that does not compile is refused the same way every time. */
    private static final Map<String, String> REFUSED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 512;
        }
    };

    private static String answerKey(
            String payloadKind,
            String payload,
            String fileName,
            String codeKind,
            int flags,
            int optimize,
            int featureVersion) {
        return payloadKind + '\u0000' + codeKind + '\u0000' + flags + '\u0000' + optimize
                + '\u0000' + featureVersion + '\u0000' + fileName + '\u0000' + payload;
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
            String key = cacheable(payloadKind, payload)
                    ? answerKey(payloadKind, payload, fileName, codeKind, flags, optimize, featureVersion)
                    : null;
            if (key != null) {
                Answer remembered = ANSWERED.get(key);
                if (remembered != null) {
                    replay(remembered.warnings());
                    return remembered.bytes();
                }
                String refusal = REFUSED.get(key);
                if (refusal != null) {
                    throw refused(refusal);
                }
            }
            Process process = invoke(
                    payloadKind, payload, fileName, codeKind, flags, optimize, featureVersion,
                    diagnostics);
            byte[] out;
            try (InputStream stream = process.getInputStream()) {
                out = stream.readAllBytes();
            }
            int status = waitFor(process);
            if (status == 0) {
                // A warning is part of the answer: the reference raises it while compiling, at the
                // position the parser reported, so this is where it is replayed.
                List<CompiledWarning> warnings = warningsIn(read(diagnostics));
                replay(warnings);
                if (key != null) {
                    ANSWERED.put(key, new Answer(out, warnings));
                }
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
            // A source that does not compile is refused the same way every time, and `traceback` asks
            // about the same fragments once per frame of every failure it formats.
            if (key != null) {
                REFUSED.put(key, report);
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

    /** The markers the driver writes around the warnings a successful compile raised. */
    private static final String WARNINGS_MARKER = "JY3-WARNINGS\n";
    private static final String WARNINGS_END = "JY3-WARNINGS-END\n";

    /**
     * Read the warnings block the driver wrote beside a successful compile.
     *
     * <p>Each line is base64(filename), lineno, base64(category name), base64(message); the base64 is
     * the reference's own {@code surrogatepass} bytes, so a warning about odd source text survives the
     * trip. A line that does not parse is dropped rather than fabricating a warning.
     */
    private static List<CompiledWarning> warningsIn(String diagnostics) {
        int start = diagnostics.indexOf(WARNINGS_MARKER);
        if (start < 0) {
            return List.of();
        }
        int end = diagnostics.indexOf(WARNINGS_END, start + WARNINGS_MARKER.length());
        String body = diagnostics.substring(
                start + WARNINGS_MARKER.length(),
                end < 0 ? diagnostics.length() : end);
        List<CompiledWarning> warnings = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) {
                continue;
            }
            try {
                warnings.add(new CompiledWarning(
                        decoded(fields[0]),
                        Integer.parseInt(fields[1]),
                        decoded(fields[2]),
                        decoded(fields[3])));
            } catch (RuntimeException malformed) {
                // Not a warning line; the code answer is unaffected.
            }
        }
        return warnings;
    }

    private static String decoded(String field) {
        return new String(Base64.getDecoder().decode(field), StandardCharsets.UTF_8);
    }

    /** Hand each warning to the runtime, which issues it where {@code compile()} was called. */
    private static void replay(List<CompiledWarning> warnings) {
        for (CompiledWarning warning : warnings) {
            PyRuntime.issueCompileWarning(
                    warning.filename(), warning.lineno(), warning.category(), warning.message());
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
