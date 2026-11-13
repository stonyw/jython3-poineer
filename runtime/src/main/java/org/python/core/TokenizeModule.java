package org.python.core;

/**
 * {@code _tokenize}: the module {@code tokenize.py} imports since 3.14.
 *
 * <p>Only {@code TokenizerIter} is filed. Reading tokens is the reference compiler's job, and this
 * runtime has no tokenizer of its own; {@link PyType#TOKENIZER_ITER} refuses construction, which is
 * the named deviation this module carries. The module exists so that {@code traceback.py} — and
 * through it {@code unittest} — can import at all. The refusal is also what stops
 * {@code inspect._signature_strip_non_python_syntax} and, behind it, {@code unittest.mock}'s
 * module-level signature of a compiled-in method: the reference tokenises that text, and there is
 * nothing here to tokenise it with. Building a tokenizer is a front-end feature, not a name this
 * slice can file.
 */
final class TokenizeModule {

    private TokenizeModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_tokenize");
        PyRuntime.markBuiltIn(module, "_tokenize");
        module.set("TokenizerIter", PyType.TOKENIZER_ITER);
        return module;
    }
}
