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
    static final int COMMENT = 65;
    static final int NL = 66;
    static final int ERRORTOKEN = 67;

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
                throw eofError();
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
                    throw eofError();
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
                    return emit(NL, startPos, cur);
                }
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
        lineStart = buf.length();
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
     * <p>The prefix loop is the reference's, condition for condition, because the order is what
     * refuses {@code bu''} while accepting {@code br''}, {@code rb''} and {@code u''}. A quote
     * reached with {@code f} or {@code t} set is an f-string or template string in 3.14; both are
     * read as plain strings here, which is the named deviation this slice carries until the
     * f-string segment lands.
     */
    private int identifier(int c) {
        boolean sawB = false;
        boolean sawU = false;
        boolean sawR = false;
        boolean sawF = false;
        boolean sawT = false;
        for (;;) {
            if (!(sawB || sawU || sawF) && (c == 'b' || c == 'B')) {
                sawB = true;
            } else if (!(sawB || sawU || sawR || sawF) && (c == 'u' || c == 'U')) {
                sawU = true;
            } else if (!(sawR || sawU) && (c == 'r' || c == 'R')) {
                sawR = true;
            } else if (!(sawF || sawB || sawU) && (c == 'f' || c == 'F')) {
                sawF = true;
            } else if (!(sawT || sawB || sawU) && (c == 't' || c == 'T')) {
                sawT = true;
            } else {
                break;
            }
            c = nextc();
            if (c == '\'' || c == '"') {
                return letterQuote(c);
            }
        }
        while (isIdentifierPart(c)) {
            c = nextc();
        }
        backup(c);
        return emit(NAME, start, cur);
    }

    private int letterQuote(int quote) {
        multiLineStart = lineStart;
        firstLineno = lineno;
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
        String message =
                triple
                        ? "unterminated triple-quoted string literal (detected at line "
                                + firstLineno
                                + ")"
                        : "unterminated string literal (detected at line " + firstLineno + ")";
        throw stringError(message);
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

    private PyRaise stringError(String message) {
        String line = currentLine();
        int offset = start - lineStart + 1;
        return syntaxError(message, ExceptionTypes.SYNTAX_ERROR, firstLineno, offset,
                line, lineno, offset);
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

    /** EOF inside brackets: the one error whose {@code text} is {@code None}. */
    private PyRaise eofError() {
        return syntaxError(
                "unexpected EOF in multi-line statement",
                ExceptionTypes.SYNTAX_ERROR,
                lineno,
                0,
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
