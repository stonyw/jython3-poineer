package org.python.core;

/**
 * What the import system asks for when a {@code .py} file has to become a code object.
 *
 * <p>The runtime owns the protocol and the front end owns the compiler, and design D2 keeps the
 * dependency pointing that way: {@code runtime} may not reach into {@code front-cpython314}. A build
 * that embeds the runtime hands a compiler over with {@link PyRuntime#setSourceCompiler}; the launcher
 * and the embedding API each do, because each already depends on the front end.
 */
public interface SourceCompiler {

    /** Compile {@code source} as a module's top level, reporting errors against {@code fileName}. */
    PyCode compile(String source, String fileName);

    /**
     * Compile {@code source} as one expression, the way {@code eval()} does rather than {@code exec()}.
     *
     * <p>The difference is in the code object: an expression's top level leaves its value as the return
     * value, while a module's returns {@code None} and speaks only through its namespace.
     */
    PyCode compileExpression(String source, String fileName);

    /**
     * Parse {@code source} and answer the tree as the text that rebuilds it.
     *
     * <p>The reference parser knows the tree and this runtime knows the node classes, so neither side
     * is handed the other's representation: the answer is a constructor expression in the syntax
     * {@code ast.dump} writes — {@code Name(id='x', ctx=Load(), lineno=1, ...)} — which runtime
     * compiles as an expression and evaluates in the {@code _ast} namespace. It carries every field the
     * parser set, including the optional ones left as {@code None}, because {@code ast.dump} omits those
     * and a rebuilt node would then lack them. Nothing in {@code runtime} needs to know how a tree was
     * parsed, only that the text names the node classes it owns.
     *
     * @param mode {@code exec}, {@code eval}, {@code single} or {@code func_type}, as {@code ast.parse}
     *     passes it
     */
    String parseAst(String source, String fileName, String mode);
}
