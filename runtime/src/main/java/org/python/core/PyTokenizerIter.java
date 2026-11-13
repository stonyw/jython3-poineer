package org.python.core;

/**
 * {@code _tokenize.TokenizerIter}: the iterator {@code tokenize.py} drives.
 *
 * <p>Each step asks {@link Tokenizer} for one token and packs it into the five-tuple
 * {@code TokenInfo} is made of — {@code (type, string, start, end, line)}. The iteration ends the
 * way the reference's does: the {@code ENDMARKER} token is handed out, and the step after it raises
 * {@code StopIteration("EOF")}.
 *
 * <p>The constructor is the reference's clinic signature,
 * {@code TokenizerIter(readline, /, *, extra_tokens, encoding='utf-8')}, refused with the same
 * sentences. {@code encoding} being absent is not the same as it being {@code 'utf-8'}: absent means
 * {@code readline} answers {@code str}, while a named encoding means it answers {@code bytes} in
 * that codec, which is what {@code tokenize.tokenize} hands over.
 */
final class PyTokenizerIter extends PyIterator {

    /**
     * The clinic parameter table: {@code readline} is positional-only (the empty name is how the
     * parser marks one), and {@code extra_tokens} and {@code encoding} come after the {@code *}, so a
     * second positional is refused by the count of one rather than bound to {@code extra_tokens}.
     */
    private static final String[] PARAMETERS = {"", "extra_tokens", "encoding"};

    private final Tokenizer tokenizer;
    private boolean finished;

    private PyTokenizerIter(Tokenizer tokenizer) {
        super(PyType.TOKENIZER_ITER);
        this.tokenizer = tokenizer;
    }

    static PyObject called(PyObject[] args, String[] keywordNames) {
        PyObject[] values =
                Arguments.unpackKeywords("tokenizeriter", args, keywordNames, PARAMETERS, 1, 1);
        if (values[1] == null) {
            // A required keyword-only parameter, which the unpacker does not model: it only knows
            // about names a positional can reach.
            throw Slots.typeError(
                    "tokenizeriter() missing required argument 'extra_tokens' (pos 2)");
        }
        String encoding = null;
        if (values[2] != null) {
            if (!(values[2] instanceof PyStr codec)) {
                String kind =
                        values[2] == PyNone.INSTANCE ? "None" : values[2].getType().name();
                throw Slots.typeError(
                        "tokenizeriter() argument 'encoding' must be str, not " + kind);
            }
            encoding = codec.getValue();
        }
        boolean extraTokens = values[1] != null && Slots.truth(values[1]);
        return new PyTokenizerIter(new Tokenizer(values[0], encoding, extraTokens));
    }

    @Override
    public PyObject nextOrNull() {
        if (finished) {
            return null;
        }
        int type = tokenizer.nextToken();
        PyObject[] item = {
            PyInt.of(type),
            PyStr.of(tokenizer.text),
            PyTuple.of(PyInt.of(tokenizer.startLine), PyInt.of(tokenizer.startCol)),
            PyTuple.of(PyInt.of(tokenizer.endLine), PyInt.of(tokenizer.endCol)),
            PyStr.of(tokenizer.lineText),
        };
        if (type == Tokenizer.ENDMARKER) {
            finished = true;
        }
        return PyTuple.of(item);
    }

    @Override
    public PyBaseException exhaustion() {
        return new PyBaseException(
                ExceptionTypes.STOP_ITERATION, new PyObject[] {PyStr.of("EOF")});
    }
}
