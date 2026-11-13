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
     * Compile one unit the way {@code compile()} does, options and all.
     *
     * @param mode {@code exec}, {@code eval} or {@code single}
     * @param flags the {@code PyCF_*} bits the caller passed, already checked against the reference's
     *     set
     * @param optimize {@code 0}..{@code 2}, or {@code -1} for "whatever the interpreter was started
     *     with"
     * @param featureVersion {@code _feature_version}, or {@code -1} for the running version
     */
    PyCode compile(
            String source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion);

    /**
     * Compile bytes exactly as {@code compile()} does, so the encoding declaration in the source is
     * the one that decodes it.
     */
    PyCode compileBytes(
            byte[] source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion);

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
     * <p>{@code flags} holds the parser's switches — {@code PyCF_TYPE_COMMENTS} and
     * {@code PyCF_ALLOW_INCOMPLETE_INPUT} — and {@code optimize} decides whether the tree is handed
     * back with {@code __debug__} already folded, which is what {@code ast.parse(optimize=...)} asks
     * for.
     *
     * @param mode {@code exec}, {@code eval}, {@code single} or {@code func_type}, as {@code ast.parse}
     *     passes it
     */
    String parseAst(
            String source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion);

    /** Parse bytes the way {@link #parseAst} parses text, encoding declaration and all. */
    String parseAstBytes(
            byte[] source,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion);

    /**
     * Compile a tree the caller holds, written as the same constructor text {@link #parseAst} answers.
     *
     * <p>The tree goes back to the reference, which rebuilds its own nodes and runs the checks it
     * applies to any tree — mode against root kind, required fields, field types, positions,
     * {@code type_ignores}. This side never validates a tree it did not define.
     */
    PyCode compileAst(
            String tree,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion);

    /**
     * Hand a tree back as a tree, for {@code PyCF_ONLY_AST} and {@code PyCF_OPTIMIZED_AST}.
     *
     * <p>The reference validates the tree and writes the normalized one — defaults filled in as
     * {@code None}, the optimized form when asked — and the answer is again the constructor text that
     * rebuilds it in this runtime.
     */
    String rebuildAst(
            String tree,
            String fileName,
            String mode,
            int flags,
            int optimize,
            int featureVersion);
}
