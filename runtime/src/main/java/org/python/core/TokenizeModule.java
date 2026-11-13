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
 * <p>Prefixed literals are the scanner's too: an f-string or t-string arrives as
 * {@code FSTRING_START}, its text as {@code FSTRING_MIDDLE} (a {@code \N{...}} name ending the run it
 * appears in, as the reference's does), the field expression through the ordinary token loop, and
 * {@code FSTRING_END}, with the nested, debug ({@code =}), conversion and format-specifier boundaries
 * where the reference puts them.
 *
 * <p>The one named deviation is the {@code extra_tokens=False} mode: only a direct
 * {@link PyTokenizerIter} caller can ask for it, never the compiler front end, and there the
 * reference also suppresses comments and NL, strips the newline from {@code NEWLINE.string}, and
 * reports INDENT/DEDENT/ENDMARKER at column -1. This scanner always produces the
 * {@code tokenize.py} shape, which is the mode the reference's own wrapper asks for.
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
