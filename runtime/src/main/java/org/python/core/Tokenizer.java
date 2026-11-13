package org.python.core;

/**
 * The scanner {@code _tokenize.TokenizerIter} drives: a port of the reference tokenizer's
 * {@code tok_get_normal_mode}, kept at the character level.
 *
 * <p>The reference has two implementations of this scanner — the C one the compiler uses and the
 * pure-Python one {@code tokenize.py} carried until 3.13 — and they agree token for token. This is
 * the second translated into Java: the same indentation stack with two columns (spaces and tabs) so
 * inconsistent mixing can be refused, the same {@code pendin} count so a dedent to several levels
 * arrives one token at a time, and the same line-at-a-time buffering through {@code readline}.
 *
 * <p>Columns are characters here rather than bytes, which is what {@code TokenInfo.start} reports
 * anyway; the reference computes the same number by re-decoding its byte offsets. The buffer keeps
 * every line it has read, so a multi-line string's {@code line} is still the whole span the
 * reference reports.
 *
 * <p>{@link #nextToken} leaves the token in the fields below rather than building a tuple: the
 * iterator that wraps this scanner packs them, and a five-tuple is exactly {@code TokenInfo}'s five
 * fields.
 */
final class Tokenizer {

    /* token.py's numbering, which the reference's own token.h mirrors. */
    static final int ENDMARKER = 0;
    static final int NAME = 1;
    static final int NUMBER = 2;
    static final int STRING = 3;
    static final int NEWLINE = 4;
    static final int INDENT = 5;
    static final int DEDENT = 6;
    static final int LPAR = 7;
    static final int RPAR = 8;
    static final int LSQB = 9;
    static final int RSQB = 10;
    static final int COLON = 11;
    static final int COMMA = 12;
    static final int SEMI = 13;
    static final int PLUS = 14;
    static final int MINUS = 15;
    static final int STAR = 16;
    static final int SLASH = 17;
    static final int VBAR = 18;
    static final int AMPER = 19;
    static final int LESS = 20;
    static final int GREATER = 21;
    static final int EQUAL = 22;
    static final int DOT = 23;
    static final int PERCENT = 24;
    static final int LBRACE = 25;
    static final int RBRACE = 26;
    static final int EQEQUAL = 27;
    static final int NOTEQUAL = 28;
    static final int LESSEQUAL = 29;
    static final int GREATEREQUAL = 30;
    static final int TILDE = 31;
    static final int CIRCUMFLEX = 32;
    static final int LEFTSHIFT = 33;
    static final int RIGHTSHIFT = 34;
    static final int DOUBLESTAR = 35;
    static final int PLUSEQUAL = 36;
    static final int MINEQUAL = 37;
    static final int STAREQUAL = 38;
    static final int SLASHEQUAL = 39;
    static final int PERCENTEQUAL = 40;
    static final int AMPEREQUAL = 41;
    static final int VBAREQUAL = 42;
    static final int CIRCUMFLEXEQUAL = 43;
    static final int LEFTSHIFTEQUAL = 44;
    static final int RIGHTSHIFTEQUAL = 45;
    static final int DOUBLESTAREQUAL = 46;
    static final int DOUBLESLASH = 47;
    static final int DOUBLESLASHEQUAL = 48;
    static final int AT = 49;
    static final int ATEQUAL = 50;
    static final int RARROW = 51;
    static final int ELLIPSIS = 52;
    static final int COLONEQUAL = 53;
    static final int EXCLAMATION = 54;
    static final int OP = 55;
    static final int FSTRING_START = 59;
    static final int FSTRING_MIDDLE = 60;
    static final int FSTRING_END = 61;
    static final int TSTRING_START = 62;
    static final int TSTRING_MIDDLE = 63;
    static final int TSTRING_END = 64;
    static final int COMMENT = 65;
    static final int NL = 66;
    static final int ERRORTOKEN = 67;

    /*
     * What the top of an f-string frame is reading. CONTENT is the literal text between fields;
     * EXPR is the expression after a `{` (which is the ordinary scanner again, so its tokens come
     * from the normal path); SPEC is the format specifier after a `:` at the field's own depth.
     */
    private static final int CONTENT = 0;
    private static final int EXPR = 1;
    private static final int SPEC = 2;

    /** How many fields one f-string may nest before the reference calls it too deep. */
    private static final int MAXFIELDS = 3;

    /* errcode.h. */
    private static final int E_OK = 10;
    private static final int E_EOF = 11;
    private static final int E_TABSPACE = 18;
    private static final int E_TOODEEP = 20;
    private static final int E_DEDENT = 21;
    private static final int E_LINECONT = 25;

    private static final int MAXINDENT = 100;
    private static final int MAXLEVEL = 200;
    private static final int TABSIZE = 8;
    private static final int ALTTABSIZE = 1;

    private static final int EOF = -1;
    private static final String FILENAME = "<string>";

    private final PyObject readLine;

    /** Null when {@code readline} answers {@code str}; otherwise the codec its bytes are read with. */
    private final String encoding;

    /** Whether the caller wants comments and blank lines, which is what {@code tokenize.py} wants. */
    private final boolean extraTokens;

    private final StringBuilder buf = new StringBuilder();
    private int cur;
    private int inp;
    private int lineStart;
    private int multiLineStart = -1;

    /**
     * Where the current logical line began.
     *
     * <p>A newline at bracket depth zero ends a logical line, and the reference resets the buffer it
     * counts EOF offsets from at that point, so this is what the multi-line-statement error's offset
     * is measured against.
     */
    private int logicalStart;

    /** Whether the literal being read reuses the enclosing f-string's own quote and width. */
    private boolean fstringQuote;
    private int start = -1;
    private int done = E_OK;
    private int lineno;
    private int firstLineno;

    private int level;
    private int indent;
    private int pendin;
    private final int[] indstack = new int[MAXINDENT];
    private final int[] altindstack = new int[MAXINDENT];
    private final int[] parenlinenostack = new int[MAXLEVEL];
    private final int[] parencolstack = new int[MAXLEVEL];
    private final char[] parenstack = new char[MAXLEVEL];

    private boolean implicitNewline;
    private boolean atbol = true;
    private boolean blankline;

    /** The buffer offset each line starts at, so a token can name the line it began on. */
    private int[] lineStarts = new int[64];
    private int lineCount;

    /**
     * One f-string or t-string being read.
     *
     * <p>{@code modes} is a small stack whose bottom is always {@link #CONTENT}: every {@code {}
     * pushes {@link #EXPR}, a {@code :} at the field's own depth turns the top into {@link #SPEC},
     * and a {@code {} inside a specifier pushes another {@link #EXPR}. {@code bases} remembers the
     * bracket depth at which each entry was pushed, which is what tells a field's closing brace
     * from a dict's.
     */
    private static final class FString {
        char quote;
        int quoteSize;
        String kind;
        int startType;
        int middleType;
        int endType;
        int literalStart;
        int literalLine;
        int textStart;
        boolean raw;
        final int[] modes = new int[MAXFIELDS + 2];
        final int[] bases = new int[MAXFIELDS + 2];
        int modeCount;
    }

    private FString[] fstrings = new FString[4];
    private int fstringCount;

    /*
     * Tokens already decided but not yet handed out. A doubled brace or a closing `}` can finish a
     * run of text and open the next token in the same step, and the reference emits them in that
     * order; a queue keeps the order without re-reading.
     */
    private static final int PENDINGCAPACITY = 8;
    private final int[] pendingTypes = new int[PENDINGCAPACITY];
    private final int[] pendingStarts = new int[PENDINGCAPACITY];
    private final int[] pendingEnds = new int[PENDINGCAPACITY];
    private int pendingCount;
    private PyRaise pendingError;

    /* The token last read. */
    int type;
    String text;
    int startLine;
    int startCol;
    int endLine;
    int endCol;
    String lineText;

    Tokenizer(PyObject readLine, String encoding, boolean extraTokens) {
        this.readLine = readLine;
        this.encoding = encoding;
        this.extraTokens = extraTokens;
    }

    /**
     * Reads one token into the fields above and answers its type.
     *
     * <p>{@link #ENDMARKER} is the last token; asking again is the iterator's business, because the
     * reference leaves that decision to {@code TokenizerIter} too.
     */
    int nextToken() {
        if (pendingCount > 0) {
            return nextPendingToken();
        }
        if (pendingError != null) {
            PyRaise failure = pendingError;
            pendingError = null;
            throw failure;
        }
        if (fstringCount > 0) {
            FString top = fstringTop();
            int mode = top.modes[top.modeCount - 1];
            if (mode != EXPR) {
                // Inside the literal itself: the text, the braces and the specifier are the
                // scanner's here, not the ordinary token loop's.
                return fstringLiteral(top, mode);
            }
        }
        if (atbol) {
            atbol = false;
            indentation();
        }
        if (pendin > 0) {
            pendin--;
            return emit(INDENT, lineStart, cur);
        }
        if (pendin < 0) {
            pendin++;
            return emit(DEDENT, cur, cur);
        }
        if (done == E_EOF) {
            if (level > 0) {
                throw eofError(fstringCount > 0 ? cur - logicalStart : 0);
            }
            return emit(ENDMARKER, -1, -1);
        }
        for (;;) {
            int c = nextc();
            start = cur - 1;
            if (c == ' ' || c == '\t' || c == '\f') {
                continue;
            }
            if (c == '#') {
                while ((c = nextc()) != '\n' && c != '\r' && c != EOF) {
                    // A comment runs to the end of the line.
                }
                if (c != EOF) {
                    backup(c);
                }
                return emit(COMMENT, start, cur);
            }
            if (c == '\\') {
                int c2 = nextc();
                if (c2 == '\n' || c2 == '\r') {
                    if (c2 == '\r') {
                        int c3 = nextc();
                        if (c3 != '\n') {
                            backup(c3);
                        }
                    }
                    continue;
                }
                if (c2 != EOF) {
                    backup(c2);
                }
                throw lineContinuationError();
            }
            if (c == EOF) {
                if (level > 0) {
                    throw eofError(fstringCount > 0 ? cur - logicalStart : 0);
                }
                done = E_EOF;
                return emit(ENDMARKER, -1, -1);
            }
            if (c == '\n' || c == '\r') {
                int startPos = start;
                if (c == '\r') {
                    int c2 = nextc();
                    if (c2 != '\n') {
                        backup(c2);
                    }
                }
                atbol = true;
                if (level > 0 || blankline) {
                    blankline = false;
                    if (level == 0) {
                        logicalStart = cur;
                    }
                    return emit(NL, startPos, cur);
                }
                logicalStart = cur;
                return emit(NEWLINE, startPos, cur);
            }
            if (isDigit(c)) {
                return number(c);
            }
            if (isIdentifierStart(c)) {
                return identifier(c);
            }
            return punctuation(c);
        }
    }

    /* ---------------------------------------------------------------- reading */

    private int nextc() {
        if (cur < inp) {
            return buf.charAt(cur++);
        }
        if (done != E_OK) {
            return EOF;
        }
        if (!underflow()) {
            cur = inp;
            return EOF;
        }
        return buf.charAt(cur++);
    }

    /** {@code n} characters back, which is how every two-character operator is undone. */
    private void backup(int c) {
        if (c != EOF) {
            cur--;
        }
    }

    /**
     * One more line from {@code readline}, appended to the buffer.
     *
     * <p>A line that does not end in a newline gets one, and the tokenizer remembers that it wrote
     * it: the token stream reports that final newline as an empty {@code NEWLINE}, and the line text
     * of the tokens before it stops short of it.
     */
    private boolean underflow() {
        PyObject lineObject;
        try {
            lineObject = readLine.call(new PyObject[0], null);
        } catch (PyRaise ended) {
            if (!ended.value().exceptionType().matches(ExceptionTypes.STOP_ITERATION)) {
                throw ended;
            }
            done = E_EOF;
            return false;
        }
        String read;
        if (encoding == null) {
            if (!(lineObject instanceof PyStr string)) {
                throw Slots.typeError("readline() returned a non-string object");
            }
            read = string.getValue();
        } else {
            if (!(lineObject instanceof PyBytes bytes)) {
                throw Slots.typeError("readline() returned a non-bytes object");
            }
            read = Decoder.decode(bytes.getValue(), encoding, "replace");
        }
        if (read.isEmpty()) {
            done = E_EOF;
            return false;
        }
        int nul = read.indexOf('\0');
        if (nul >= 0) {
            // The reference refuses a source with an embedded null before it tokenizes any of it,
            // and points at the line the byte is on with the text that precedes it.
            int sourceLine = lineno + 1;
            String before = read.substring(0, nul);
            throw syntaxError(
                    "source code cannot contain null bytes",
                    ExceptionTypes.SYNTAX_ERROR,
                    sourceLine,
                    0,
                    before,
                    sourceLine,
                    0);
        }
        lineStart = buf.length();
        if (lineCount == lineStarts.length) {
            lineStarts = java.util.Arrays.copyOf(lineStarts, lineStarts.length * 2);
        }
        lineStarts[lineCount++] = lineStart;
        buf.append(read);
        if (read.charAt(read.length() - 1) != '\n') {
            buf.append('\n');
            implicitNewline = true;
        }
        inp = buf.length();
        lineno++;
        return true;
    }

    /* ------------------------------------------------------------ indentation */

    /**
     * The indentation of the line just started, and the INDENT or DEDENT it owes.
     *
     * <p>Two columns are measured: one with hard tabs at eight, one with them at one. The second
     * exists only so that a block indented with tabs can be told from one indented with spaces when
     * the first column says the levels match — that is the {@code TabError} the reference raises.
     * Blank lines and comment-only lines compare nothing: they belong to neither level.
     */
    private void indentation() {
        int col = 0;
        int altcol = 0;
        int c;
        for (;;) {
            c = nextc();
            if (c == ' ') {
                col++;
                altcol++;
            } else if (c == '\t') {
                col = (col / TABSIZE + 1) * TABSIZE;
                altcol = (altcol / ALTTABSIZE + 1) * ALTTABSIZE;
            } else if (c == '\f') {
                col = 0;
                altcol = 0;
            } else if (c == '\\') {
                int c2 = nextc();
                if (c2 == '\n' || c2 == '\r') {
                    if (c2 == '\r') {
                        int c3 = nextc();
                        if (c3 != '\n') {
                            backup(c3);
                        }
                    }
                    continue;
                }
                if (c2 != EOF) {
                    backup(c2);
                }
                throw lineContinuationError();
            } else {
                break;
            }
        }
        backup(c);
        blankline = c == '#' || c == '\n' || c == '\r';
        if (c == EOF) {
            done = E_EOF;
            col = 0;
            altcol = 0;
        }
        if (level > 0 || blankline) {
            return;
        }
        if (col == indstack[indent]) {
            // No change in the visual column, but a tab in the line can still have moved the
            // alternating suffix. The reference checks that case too, and it is what turns a
            // tab-indented block followed by eight spaces into a TabError rather than silence.
            if (altcol != altindstack[indent]) {
                throw generalError(
                        "inconsistent use of tabs and spaces in indentation", E_TABSPACE);
            }
            return;
        }
        if (col > indstack[indent]) {
            if (indent + 1 >= MAXINDENT) {
                throw generalError("too many levels of indentation", E_TOODEEP);
            }
            pendin++;
            indstack[++indent] = col;
            altindstack[indent] = altcol;
            return;
        }
        while (indent > 0 && col < indstack[indent]) {
            pendin--;
            indent--;
        }
        if (col != indstack[indent]) {
            throw generalError(
                    "unindent does not match any outer indentation level", E_DEDENT);
        }
        if (altcol != altindstack[indent]) {
            throw generalError(
                    "inconsistent use of tabs and spaces in indentation", E_TABSPACE);
        }
    }

    /* --------------------------------------------------------------- literals */

    /**
     * A name, or a string with its prefix.
     *
     * <p>A prefix letter may appear once and only once: a repeated letter ends the prefix scan and
     * the whole run becomes a name, which is why {@code bb'x'} is a name followed by a string while
     * {@code br'x'} is one literal. Letters left over at the quote are checked against the pairs
     * the reference refuses, so {@code bf'x'} is an error rather than a name and a string. A quote
     * reached with {@code f} or {@code t} set starts an f-string or a template string.
     */
    private int identifier(int c) {
        boolean sawB = false;
        boolean sawU = false;
        boolean sawR = false;
        boolean sawF = false;
        boolean sawT = false;
        for (;;) {
            if (c == 'b' || c == 'B') {
                if (sawB) {
                    break;
                }
                sawB = true;
            } else if (c == 'u' || c == 'U') {
                if (sawU) {
                    break;
                }
                sawU = true;
            } else if (c == 'r' || c == 'R') {
                if (sawR) {
                    break;
                }
                sawR = true;
            } else if (c == 'f' || c == 'F') {
                if (sawF) {
                    break;
                }
                sawF = true;
            } else if (c == 't' || c == 'T') {
                if (sawT) {
                    break;
                }
                sawT = true;
            } else {
                break;
            }
            c = nextc();
            if (c == '\'' || c == '"') {
                String pair = prefixConflict(sawB, sawU, sawR, sawF, sawT);
                if (pair != null) {
                    throw prefixConflictError(pair);
                }
                if (sawF || sawT) {
                    return fstringStart(c, sawT, sawR);
                }
                return letterQuote(c);
            }
        }
        while (isIdentifierPart(c)) {
            c = nextc();
        }
        backup(c);
        return emit(NAME, start, cur);
    }

    /**
     * The pair of prefixes the reference names first when they cannot go together, in the order it
     * names them; {@code null} when the set is legal.
     */
    private static String prefixConflict(
            boolean b, boolean u, boolean r, boolean f, boolean t) {
        if (u && b) {
            return "'u' and 'b'";
        }
        if (u && r) {
            return "'u' and 'r'";
        }
        if (u && f) {
            return "'u' and 'f'";
        }
        if (u && t) {
            return "'u' and 't'";
        }
        if (b && f) {
            return "'b' and 'f'";
        }
        if (b && t) {
            return "'b' and 't'";
        }
        if (f && t) {
            return "'f' and 't'";
        }
        return null;
    }

    private PyRaise prefixConflictError(String pair) {
        int end = cur - lineStart;
        return syntaxError(
                pair + " prefixes are incompatible",
                ExceptionTypes.SYNTAX_ERROR,
                lineno,
                start - lineStart + 1,
                currentLine(),
                lineno,
                end);
    }

    private int letterQuote(int quote) {
        multiLineStart = lineStart;
        firstLineno = lineno;
        fstringQuote = false;
        int quoteSize = 1;
        int c = nextc();
        if (c == quote) {
            quoteSize = 2;
            int c2 = nextc();
            if (c2 == quote) {
                quoteSize = 3;
            } else {
                backup(c2);
            }
        } else {
            backup(c);
        }
        if (quoteSize == 2) {
            return emit(STRING, start, cur);
        }
        fstringQuote =
                fstringCount > 0
                        && inFStringField()
                        && quote == fstringTop().quote
                        && quoteSize == fstringTop().quoteSize;
        if (quoteSize == 1) {
            for (;;) {
                c = nextc();
                if (c == EOF) {
                    return unterminatedString(false);
                }
                if (c == quote) {
                    return emit(STRING, start, cur);
                }
                if (c == '\\') {
                    if (skipEscape() == EOF) {
                        return unterminatedString(false);
                    }
                } else if (c == '\n' || c == '\r') {
                    return unterminatedString(false);
                }
            }
        }
        for (;;) {
            c = nextc();
            if (c == EOF) {
                return unterminatedString(true);
            }
            if (c == quote) {
                int c2 = nextc();
                if (c2 == quote) {
                    int c3 = nextc();
                    if (c3 == quote) {
                        return emit(STRING, start, cur);
                    }
                    backup(c3);
                } else {
                    backup(c2);
                }
                continue;
            }
            if (c == '\\' && skipEscape() == EOF) {
                return unterminatedString(true);
            }
        }
    }

    /** The character after a backslash, with {@code \r\n} taken as one. */
    private int skipEscape() {
        int c = nextc();
        if (c == '\r') {
            int c2 = nextc();
            if (c2 != '\n') {
                backup(c2);
            }
        }
        return c;
    }

    private int unterminatedString(boolean triple) {
        if (fstringQuote) {
            // The reference reads a quote that reuses the f-string's own quote as an attempt to
            // close the field, so an unterminated one is reported that way.
            throw stringError("f-string: expecting '}'");
        }
        String message =
                triple
                        ? "unterminated triple-quoted string literal (detected at line "
                                + firstLineno
                                + ")"
                        : "unterminated string literal (detected at line " + firstLineno + ")";
        throw stringError(message);
    }

    /* -------------------------------------------------------------- f-strings */

    /**
     * The quote that opens an f-string or template string, and the frame that reads it.
     *
     * <p>{@code start} is the first prefix character, so the START token's text is the whole prefix
     * and quote. Two quotes are one quote and the closing one rather than the start of a triple,
     * which is why the third character decides.
     */
    private int fstringStart(int quote, boolean template, boolean raw) {
        int literalStart = start;
        int quoteStart = cur - 1;
        int quoteSize = 1;
        int c = nextc();
        if (c == quote) {
            int c2 = nextc();
            if (c2 == quote) {
                quoteSize = 3;
            } else {
                cur = quoteStart + 1;
            }
        } else {
            backup(c);
        }
        FString f = pushFrame();
        f.quote = (char) quote;
        f.quoteSize = quoteSize;
        f.raw = raw;
        f.kind = template ? "t-string" : "f-string";
        f.startType = template ? TSTRING_START : FSTRING_START;
        f.middleType = template ? TSTRING_MIDDLE : FSTRING_MIDDLE;
        f.endType = template ? TSTRING_END : FSTRING_END;
        f.literalStart = literalStart;
        f.literalLine = lineno;
        f.textStart = cur;
        f.modeCount = 1;
        f.modes[0] = CONTENT;
        f.bases[0] = level;
        return emitFStringToken(f.startType, literalStart, cur);
    }

    private FString pushFrame() {
        if (fstringCount == fstrings.length) {
            fstrings = java.util.Arrays.copyOf(fstrings, fstrings.length * 2);
        }
        FString f = fstrings[fstringCount];
        if (f == null) {
            f = new FString();
            fstrings[fstringCount] = f;
        }
        fstringCount++;
        return f;
    }

    private FString fstringTop() {
        return fstrings[fstringCount - 1];
    }

    /** Whether the ordinary token loop is inside a field, at the field's own bracket depth. */
    private boolean inFStringField() {
        if (fstringCount == 0) {
            return false;
        }
        FString f = fstringTop();
        return f.modes[f.modeCount - 1] == EXPR && level == f.bases[f.modeCount - 1];
    }

    /**
     * One token of the literal itself: text, a brace, or the closing quote.
     *
     * <p>The braces are read here rather than by the ordinary loop because their meaning depends on
     * where they are: doubled they are one character of text, alone in the content they open a
     * field, a lone {@code }} in the content is an error, and inside a specifier neither brace needs
     * doubling to begin or end a nested field. Each step that decides more than one token — a
     * doubled brace finishing the text and the next text run beginning, or a closing quote owing
     * both a last piece of text and the END — queues the rest.
     */
    private int fstringLiteral(FString f, int mode) {
        for (;;) {
            int c = nextc();
            int pos = cur - 1;
            if (c == EOF) {
                throw unterminatedFString(f);
            }
            if (c == '\\' && !f.raw) {
                int c2 = nextc();
                if (c2 == EOF) {
                    throw unterminatedFString(f);
                }
                if (c2 == '{') {
                    // `\{` does not escape the brace: the backslash is text and the brace still
                    // opens a field. `\N{...}` is the one escape that spans one.
                    backup(c2);
                } else if (c2 == 'N') {
                    int c3 = nextc();
                    if (c3 == '{') {
                        // A `\N{...}` name ends the text run where its closing brace is, because
                        // the reference hands the name to the parser as a token of its own. A
                        // quote reached first means the literal ends there, and the name is
                        // left as plain text; a newline in a single-quoted literal or EOF is
                        // the ordinary unterminated case.
                        boolean closed = false;
                        for (;;) {
                            int c4 = nextc();
                            if (c4 == EOF) {
                                throw unterminatedFString(f);
                            }
                            if (c4 == '}') {
                                closed = true;
                                break;
                            }
                            if (c4 == f.quote) {
                                if (f.quoteSize == 1) {
                                    backup(c4);
                                    break;
                                }
                                int q = cur - 1;
                                int c5 = nextc();
                                if (c5 == f.quote) {
                                    int c6 = nextc();
                                    if (c6 == f.quote) {
                                        cur = q;
                                        break;
                                    }
                                }
                                continue;
                            }
                            if ((c4 == '\n' || c4 == '\r') && f.quoteSize == 1) {
                                throw unterminatedFString(f);
                            }
                        }
                        if (closed) {
                            pushPending(f.middleType, f.textStart, cur);
                            f.textStart = cur;
                            return nextPendingToken();
                        }
                    } else {
                        backup(c3);
                    }
                }
                continue;
            }
            if (c == f.quote) {
                if (f.quoteSize == 1) {
                    return endLiteral(f, pos);
                }
                int c2 = nextc();
                if (c2 != f.quote) {
                    backup(c2);
                    continue;
                }
                int c3 = nextc();
                if (c3 == f.quote) {
                    return endLiteral(f, pos);
                }
                backup(c3);
                continue;
            }
            if (c == '\n' || c == '\r') {
                if (f.quoteSize == 3) {
                    if (c == '\r') {
                        int c2 = nextc();
                        if (c2 != '\n') {
                            backup(c2);
                        }
                    }
                    continue;
                }
                if (mode == SPEC) {
                    throw fstringError(
                            "f-string: newlines are not allowed in format specifiers for single"
                                    + " quoted f-strings",
                            cur - lineStart);
                }
                throw unterminatedFString(f);
            }
            if (c == '{') {
                if (mode == CONTENT) {
                    int c2 = nextc();
                    if (c2 == '{') {
                        pushPending(f.middleType, f.textStart, pos + 1);
                        f.textStart = cur;
                        return nextPendingToken();
                    }
                    backup(c2);
                    if (pos > f.textStart) {
                        pushPending(f.middleType, f.textStart, pos);
                    }
                } else {
                    int c2 = nextc();
                    boolean doubled = c2 == '{';
                    backup(c2);
                    // A specifier's brace always opens a field; the text before it is reported only
                    // when there is some, or when the doubled brace shows the author meant text.
                    if (pos > f.textStart || doubled) {
                        pushPending(f.middleType, f.textStart, pos);
                    }
                }
                openField(f, pos);
                pushPending(LBRACE, pos, cur);
                return nextPendingToken();
            }
            if (c == '}') {
                if (mode == CONTENT) {
                    int c2 = nextc();
                    if (c2 == '}') {
                        pushPending(f.middleType, f.textStart, pos + 1);
                        f.textStart = cur;
                        return nextPendingToken();
                    }
                    backup(c2);
                    pushPending(f.middleType, f.textStart, pos);
                    pendingError =
                            fstringError(
                                    "f-string: single '}' is not allowed", cur - lineStart);
                    return nextPendingToken();
                }
                pushPending(f.middleType, f.textStart, pos);
                level--;
                f.modeCount--;
                f.textStart = cur;
                pushPending(RBRACE, pos, cur);
                return nextPendingToken();
            }
        }
    }

    /** The closing quote: the text run before it, if any, then END, then the frame is gone. */
    private int endLiteral(FString f, int quotePos) {
        if (quotePos > f.textStart) {
            pushPending(f.middleType, f.textStart, quotePos);
        }
        fstringCount--;
        pushPending(f.endType, quotePos, cur);
        return nextPendingToken();
    }

    private void openField(FString f, int bracePos) {
        if (f.modeCount > MAXFIELDS) {
            throw fstringError("f-string: expressions nested too deeply", bracePos - lineStart);
        }
        level++;
        f.modes[f.modeCount] = EXPR;
        f.bases[f.modeCount] = level;
        f.modeCount++;
    }

    /** The {@code :} that leaves the expression for the format specifier. */
    private int beginSpec() {
        FString f = fstringTop();
        f.modes[f.modeCount - 1] = SPEC;
        f.textStart = cur;
        return emit(COLON, start, cur);
    }

    /** The {@code }} that ends the field the expression was in. */
    private int closeField() {
        FString f = fstringTop();
        level--;
        f.modeCount--;
        f.textStart = cur;
        return emit(RBRACE, start, cur);
    }

    private void pushPending(int tokenType, int startPos, int endPos) {
        pendingTypes[pendingCount] = tokenType;
        pendingStarts[pendingCount] = startPos;
        pendingEnds[pendingCount] = endPos;
        pendingCount++;
    }

    private int nextPendingToken() {
        int tokenType = pendingTypes[0];
        int startPos = pendingStarts[0];
        int endPos = pendingEnds[0];
        System.arraycopy(pendingTypes, 1, pendingTypes, 0, pendingCount - 1);
        System.arraycopy(pendingStarts, 1, pendingStarts, 0, pendingCount - 1);
        System.arraycopy(pendingEnds, 1, pendingEnds, 0, pendingCount - 1);
        pendingCount--;
        return emitFStringToken(tokenType, startPos, endPos);
    }

    /**
     * A token the literal decided, located by buffer offsets.
     *
     * <p>These spans cross lines, so the line and column cannot come from {@code lineStart} and
     * {@code lineno}; the recorded line starts answer both. The reported line text is the start
     * line's, from its beginning to everything read so far, which is how the reference reports the
     * line of a token that a later readline has already extended past.
     */
    private int emitFStringToken(int tokenType, int startPos, int endPos) {
        text = buf.substring(startPos, endPos);
        startLine = lineOf(startPos);
        startCol = startPos - lineStarts[startLine - 1];
        endLine = endPos > startPos ? lineOf(endPos) : startLine;
        endCol = endPos - lineStarts[endLine - 1];
        int base = lineStarts[startLine - 1];
        int end = inp - (implicitNewline ? 1 : 0);
        lineText = buf.substring(base, Math.max(base, end));
        type = extraTokens && tokenType > DEDENT && tokenType < OP ? OP : tokenType;
        return type;
    }

    private int lineOf(int position) {
        int lo = 0;
        int hi = lineCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= position) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    /** A line's text with its newline removed and its carriage return kept. */
    private String lineTextAt(int lineNumber) {
        if (lineNumber < 1 || lineNumber > lineCount) {
            return "";
        }
        int from = lineStarts[lineNumber - 1];
        int to = lineNumber < lineCount ? lineStarts[lineNumber] : buf.length();
        if (to > from && buf.charAt(to - 1) == '\n') {
            to--;
        }
        return buf.substring(from, Math.max(from, to));
    }

    private PyRaise fstringError(String message, int offset) {
        return syntaxError(
                message,
                ExceptionTypes.SYNTAX_ERROR,
                lineno,
                offset,
                currentLine(),
                lineno,
                offset);
    }

    /** A bare quote where the field's closing brace belongs. */
    private PyRaise fstringExpectedClose() {
        return fstringError("f-string: expecting '}'", start - lineStart + 1);
    }

    private PyRaise unterminatedFString(FString f) {
        String message =
                (f.quoteSize == 3 ? "unterminated triple-quoted " : "unterminated ")
                        + f.kind
                        + " literal (detected at line "
                        + lineno
                        + ")";
        int offset = f.literalStart - lineStarts[f.literalLine - 1] + 1;
        return syntaxError(
                message,
                ExceptionTypes.SYNTAX_ERROR,
                f.literalLine,
                offset,
                lineTextAt(f.literalLine),
                f.literalLine,
                offset);
    }

    /* ---------------------------------------------------------------- numbers */

    private int number(int c) {
        if (c == '0') {
            int c2 = nextc();
            if (c2 == 'x' || c2 == 'X') {
                radix(16);
                return emit(NUMBER, start, cur);
            }
            if (c2 == 'o' || c2 == 'O') {
                radix(8);
                return emit(NUMBER, start, cur);
            }
            if (c2 == 'b' || c2 == 'B') {
                radix(2);
                return emit(NUMBER, start, cur);
            }
            backup(c2);
        }
        while (isDigit(c) || c == '_') {
            c = nextc();
        }
        if (c == '.') {
            c = nextc();
            while (isDigit(c) || c == '_') {
                c = nextc();
            }
        }
        if (c == 'e' || c == 'E') {
            int marker = c;
            int c2 = nextc();
            if (c2 == '+' || c2 == '-') {
                c2 = nextc();
            }
            if (isDigit(c2)) {
                c = c2;
                while (isDigit(c) || c == '_') {
                    c = nextc();
                }
            } else {
                backup(c2);
                backup(marker);
                c = marker;
            }
        }
        if (c == 'j' || c == 'J') {
            c = nextc();
        }
        backup(c);
        return emit(NUMBER, start, cur);
    }

    /** The digits after {@code 0x}, {@code 0o} or {@code 0b}, underscores included. */
    private void radix(int base) {
        int c = nextc();
        for (;;) {
            boolean isRadixDigit =
                    base == 16
                            ? isHexDigit(c)
                            : base == 8 ? c >= '0' && c <= '7' : c == '0' || c == '1';
            if (!isRadixDigit && c != '_') {
                break;
            }
            c = nextc();
        }
        backup(c);
    }

    /* ----------------------------------------------------------- punctuation */

    private int punctuation(int c) {
        if (c == '}' && inFStringField()) {
            return closeField();
        }
        if (c == ':' && inFStringField()) {
            return beginSpec();
        }
        switch (c) {
            case '.':
                return dot();
            case '\'':
            case '"':
                return letterQuote(c);
            case '(':
            case '[':
            case '{':
                return opening(c);
            case ')':
            case ']':
            case '}':
                return closing(c);
            case ':':
                if (peekAfter('=')) {
                    return emit(COLONEQUAL, start, cur);
                }
                return emit(COLON, start, cur);
            case ';':
                return emit(SEMI, start, cur);
            case ',':
                return emit(COMMA, start, cur);
            case '+':
                if (peekAfter('=')) {
                    return emit(PLUSEQUAL, start, cur);
                }
                return emit(PLUS, start, cur);
            case '-':
                {
                    int c2 = nextc();
                    if (c2 == '=') {
                        return emit(MINEQUAL, start, cur);
                    }
                    if (c2 == '>') {
                        return emit(RARROW, start, cur);
                    }
                    backup(c2);
                    return emit(MINUS, start, cur);
                }
            case '*':
                {
                    int c2 = nextc();
                    if (c2 == '=') {
                        return emit(STAREQUAL, start, cur);
                    }
                    if (c2 == '*') {
                        int c3 = nextc();
                        if (c3 == '=') {
                            return emit(DOUBLESTAREQUAL, start, cur);
                        }
                        backup(c3);
                        return emit(DOUBLESTAR, start, cur);
                    }
                    backup(c2);
                    return emit(STAR, start, cur);
                }
            case '/':
                {
                    int c2 = nextc();
                    if (c2 == '=') {
                        return emit(SLASHEQUAL, start, cur);
                    }
                    if (c2 == '/') {
                        int c3 = nextc();
                        if (c3 == '=') {
                            return emit(DOUBLESLASHEQUAL, start, cur);
                        }
                        backup(c3);
                        return emit(DOUBLESLASH, start, cur);
                    }
                    backup(c2);
                    return emit(SLASH, start, cur);
                }
            case '|':
                if (peekAfter('=')) {
                    return emit(VBAREQUAL, start, cur);
                }
                return emit(VBAR, start, cur);
            case '&':
                if (peekAfter('=')) {
                    return emit(AMPEREQUAL, start, cur);
                }
                return emit(AMPER, start, cur);
            case '^':
                if (peekAfter('=')) {
                    return emit(CIRCUMFLEXEQUAL, start, cur);
                }
                return emit(CIRCUMFLEX, start, cur);
            case '%':
                if (peekAfter('=')) {
                    return emit(PERCENTEQUAL, start, cur);
                }
                return emit(PERCENT, start, cur);
            case '@':
                if (peekAfter('=')) {
                    return emit(ATEQUAL, start, cur);
                }
                return emit(AT, start, cur);
            case '=':
                if (peekAfter('=')) {
                    return emit(EQEQUAL, start, cur);
                }
                return emit(EQUAL, start, cur);
            case '!':
                if (peekAfter('=')) {
                    return emit(NOTEQUAL, start, cur);
                }
                return emit(EXCLAMATION, start, cur);
            case '<':
                {
                    int c2 = nextc();
                    if (c2 == '=') {
                        return emit(LESSEQUAL, start, cur);
                    }
                    if (c2 == '<') {
                        int c3 = nextc();
                        if (c3 == '=') {
                            return emit(LEFTSHIFTEQUAL, start, cur);
                        }
                        backup(c3);
                        return emit(LEFTSHIFT, start, cur);
                    }
                    backup(c2);
                    return emit(LESS, start, cur);
                }
            case '>':
                {
                    int c2 = nextc();
                    if (c2 == '=') {
                        return emit(GREATEREQUAL, start, cur);
                    }
                    if (c2 == '>') {
                        int c3 = nextc();
                        if (c3 == '=') {
                            return emit(RIGHTSHIFTEQUAL, start, cur);
                        }
                        backup(c3);
                        return emit(RIGHTSHIFT, start, cur);
                    }
                    backup(c2);
                    return emit(GREATER, start, cur);
                }
            case '~':
                return emit(TILDE, start, cur);
            default:
                // A graphic character that begins no token is still a token: the reference's
                // `tokenize` hands `$` and `?` back as OP so that callers such as
                // `inspect._signature_strip_non_python_syntax` can see them.
                return emit(OP, start, cur);
        }
    }

    /** Consumes the next character if it is {@code expected}, and otherwise leaves it unread. */
    private boolean peekAfter(int expected) {
        int c = nextc();
        if (c == expected) {
            return true;
        }
        backup(c);
        return false;
    }

    private int dot() {
        int c = nextc();
        if (isDigit(c)) {
            return number(c);
        }
        if (c == '.') {
            int c2 = nextc();
            if (c2 == '.') {
                return emit(ELLIPSIS, start, cur);
            }
            backup(c2);
            backup(c);
            return emit(DOT, start, cur);
        }
        backup(c);
        return emit(DOT, start, cur);
    }

    private int opening(int c) {
        if (level >= MAXLEVEL) {
            throw generalError("too many nested parentheses", E_TOODEEP);
        }
        parenstack[level] = (char) c;
        parenlinenostack[level] = lineno;
        parencolstack[level] = start - lineStart;
        level++;
        return emit(c == '(' ? LPAR : c == '[' ? LSQB : LBRACE, start, cur);
    }

    /**
     * A closing bracket, which pops the stack when there is one.
     *
     * <p>The reference hands a closer that does not match the opener, and one with nothing open,
     * back as a plain OP; refusing it is the parser's job, and {@code tokenize} never sees the
     * refusal. That is why {@code x = [1)} tokenises without complaint.
     */
    private int closing(int c) {
        if (level > 0) {
            level--;
        }
        return emit(c == ')' ? RPAR : c == ']' ? RSQB : RBRACE, start, cur);
    }

    /* ----------------------------------------------------------------- tokens */

    /**
     * Records the token spanning {@code [startPos, endPos)} and answers the type Python sees.
     *
     * <p>Extra-token mode is {@code tokenize.py}'s, and it makes two changes that the reference
     * makes in {@code tokenizeriter_next} rather than in the scanner: every operator token becomes
     * {@code OP} (so {@code exact_type} has something to say), and the trailing
     * {@code ENDMARKER}/{@code DEDENT} of a file carries no line text and sits one line past the
     * last.
     */
    private int emit(int tokenType, int startPos, int endPos) {
        boolean trailing =
                extraTokens
                        && (tokenType == ENDMARKER
                                || (tokenType == DEDENT && done == E_EOF));
        if (tokenType == ENDMARKER || trailing) {
            text = "";
            lineText = "";
            startLine = endLine = lineno + 1;
            startCol = endCol = 0;
        } else {
            int base = tokenType == STRING ? multiLineStart : lineStart;
            int baseLine = tokenType == STRING ? firstLineno : lineno;
            text = startPos < 0 ? "" : buf.substring(startPos, endPos);
            if (implicitNewline
                    && (tokenType == NEWLINE || tokenType == NL)
                    && endPos == inp) {
                text = "";
            }
            startLine = baseLine;
            startCol = startPos - base;
            endLine = lineno;
            endCol = endPos - lineStart;
            int end = inp - (implicitNewline ? 1 : 0);
            lineText = buf.substring(base, Math.max(base, end));
        }
        type = extraTokens && tokenType > DEDENT && tokenType < OP ? OP : tokenType;
        return type;
    }

    /* ---------------------------------------------------------------- errors */

    private String currentLine() {
        int end = inp;
        if (end > lineStart && buf.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > lineStart && buf.charAt(end - 1) == '\r') {
            end--;
        }
        return buf.substring(lineStart, Math.max(lineStart, end));
    }

    /**
     * An unterminated literal, which is reported where the literal began — not where the scan gave
     * up — with that line's text.
     */
    private PyRaise stringError(String message) {
        int offset = start - lineStarts[firstLineno - 1] + 1;
        return syntaxError(message, ExceptionTypes.SYNTAX_ERROR, firstLineno, offset,
                lineTextAt(firstLineno), firstLineno, offset);
    }

    /**
     * The reference's {@code _tokenizer_error} sentence for the indentation family.
     *
     * <p>Its position is the whole line rather than a character in it: the offset is the line's
     * length, and the text is the line without its newline.
     */
    private PyRaise generalError(String message, int errcode) {
        String line = currentLine();
        int offset = inp - lineStart;
        PyType kind = ExceptionTypes.SYNTAX_ERROR;
        if (errcode == E_DEDENT || errcode == E_TOODEEP) {
            kind = ExceptionTypes.INDENTATION_ERROR;
        } else if (errcode == E_TABSPACE) {
            kind = ExceptionTypes.TAB_ERROR;
        }
        return syntaxError(message, kind, lineno, offset, line, -1, -1);
    }

    private PyRaise lineContinuationError() {
        return generalError(
                "unexpected character after line continuation character", E_LINECONT);
    }

    /**
     * EOF inside brackets: the one error whose {@code text} is {@code None}.
     *
     * <p>The offset is zero unless the EOF was reached inside an f-string, where the reference
     * points at the last character it read.
     */
    private PyRaise eofError(int offset) {
        return syntaxError(
                "unexpected EOF in multi-line statement",
                ExceptionTypes.SYNTAX_ERROR,
                lineno,
                offset,
                null,
                lineno,
                -1);
    }

    private PyRaise syntaxError(
            String message,
            PyType kind,
            int lineNumber,
            int offset,
            String line,
            int endLineNumber,
            int endOffset) {
        PyObject[] info = {
            PyStr.of(FILENAME),
            PyInt.of(lineNumber),
            PyInt.of(offset),
            line == null ? PyNone.INSTANCE : PyStr.of(line),
            endLineNumber < 0 ? PyNone.INSTANCE : PyInt.of(endLineNumber),
            endOffset < 0 ? PyNone.INSTANCE : PyInt.of(endOffset),
        };
        PyBaseException failure =
                new PyBaseException(kind, new PyObject[] {PyStr.of(message), PyTuple.of(info)});
        failure.writeMemberField("msg", PyStr.of(message));
        failure.writeMemberField("filename", info[0]);
        failure.writeMemberField("lineno", info[1]);
        failure.writeMemberField("offset", info[2]);
        failure.writeMemberField("text", info[3]);
        failure.writeMemberField("end_lineno", info[4]);
        failure.writeMemberField("end_offset", info[5]);
        return PyRaise.of(failure);
    }

    /* ------------------------------------------------------------- characters */

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(int c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentifierStart(int c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 128;
    }

    private static boolean isIdentifierPart(int c) {
        return isIdentifierStart(c) || isDigit(c);
    }
}
