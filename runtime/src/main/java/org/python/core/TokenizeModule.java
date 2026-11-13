package org.python.core;

/**
 * {@code _tokenize}: the module {@code tokenize.py} imports since 3.14.
 *
 * <p>Only {@code TokenizerIter} is filed, and it is implemented here rather than borrowed from the
 * compiler: {@link PyTokenizerIter} answers one five-tuple per step and {@link Tokenizer} is the
 * scanner underneath it. {@code tokenize.generate_tokens}, and through it
 * {@code inspect._signature_strip_non_python_syntax} and {@code unittest.mock}'s handling of
 * compiled-in methods, therefore read tokens from the same state machine the reference uses.
 *
 * <p>What is not yet here is f-strings: a prefixed literal tokenises as one {@code STRING} where the
 * reference emits {@code FSTRING_START}/{@code FSTRING_MIDDLE}/{@code FSTRING_END}. That is the
 * named deviation this slice carries, recorded in PLAN.md.
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
