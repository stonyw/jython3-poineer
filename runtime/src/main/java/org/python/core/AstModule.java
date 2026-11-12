package org.python.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code _ast}: the node classes {@code ast.py} imports with {@code from _ast import *}.
 *
 * <p>CPython builds this module in C from a table in {@code Python-ast.c}; the table is the same shape
 * here, but written as Python source — {@code ast-bootstrap.py}, carried beside this class — because a
 * class statement gives the node types the instance dictionaries, inheritance and attribute lookup
 * that {@code ast.dump}, {@code ast.literal_eval} and {@code ast.unparse} all read. The source is run
 * through the same compiler every imported module goes through, so a heap type made here is a heap type
 * made anywhere.
 *
 * <p>It is built on first import rather than at startup: building it needs the injected compiler, and
 * the compiler is handed in after startup. That is also why {@code _ast} is in the built-in name
 * table but not in the module table until someone imports it.
 */
final class AstModule {

    /** The Python source, a resource so the node table stays readable as a table. */
    private static final String BOOTSTRAP = "/org/python/core/ast-bootstrap.py";

    private static PyModule module;

    private AstModule() {
    }

    /**
     * The module, built on first use. Synchronized because two frames may import it in one process.
     *
     * <p>A runtime with no compiler cannot build the node types and says so by name: the alternative
     * would be a module whose classes exist but whose constructor bodies cannot run.
     */
    static synchronized PyModule install() {
        if (module != null) {
            return module;
        }
        if (!PyRuntime.hasSourceCompiler()) {
            throw Slots.notImplemented("_ast needs a compiler (setSourceCompiler)");
        }
        PyModule built = PyRuntime.namespace(new PyModule("_ast"));
        PyRuntime.markBuiltIn(built, "_ast");
        PyDict globals = built.dictionary();
        PyRuntime.runIn(PyRuntime.compileSource(source(), "<ast>"), globals, globals);
        PyRuntime.registerBuiltInModule("_ast", built);
        module = built;
        return built;
    }

    private static String source() {
        try (InputStream stream = AstModule.class.getResourceAsStream(BOOTSTRAP)) {
            if (stream == null) {
                throw new IllegalStateException("the runtime is missing " + BOOTSTRAP);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + BOOTSTRAP, e);
        }
    }
}
