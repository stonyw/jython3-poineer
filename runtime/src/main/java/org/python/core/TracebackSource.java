package org.python.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The source lines under one traceback entry, with the PEP 657 anchors CPython draws beneath them.
 *
 * <p>CPython builds this text from two things: the code object's {@code co_positions()} entry for the
 * instruction the frame stopped on, and the source line that entry covers. It dedents the lines, cuts
 * the segment the instruction names, and reads that segment with {@code ast} to find the operator or
 * the bracket PEP 657 calls an anchor. This runtime has neither {@code ast} nor {@code tokenize}, so the
 * reading happens here with a scanner over the segment: a binary operator chosen by precedence, or the
 * last call or subscript of a primary chain. A segment the scanner cannot read is shown without anchors,
 * which is what CPython does for a segment {@code ast} refuses to parse.
 *
 * <p>Everything else is transcribed rather than approximated: the dedent, the conversion of a byte
 * column into a character column, the display width that makes a CJK character count for two columns,
 * the window of three lines around each anchor, and the {@code ...<N lines>...} that replaces a longer
 * gap.
 */
final class TracebackSource {

    private static final String SOURCE_INDENT = "    ";

    /** Operators Python spells with two characters, longest match first. */
    private static final Set<String> TWO_CHARACTER_OPERATORS =
            Set.of(
                    "**", "//", "<<", ">>", ":=", "->", "==", "!=", "<=", ">=",
                    "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@=");

    /** The operators that build a {@code BinOp} node, which is one of the two anchor shapes. */
    private static final Set<String> BINARY_OPERATORS =
            Set.of("+", "-", "*", "/", "//", "%", "@", "**", "|", "&", "^", "<<", ">>");

    /**
     * Names and operators that make the segment something other than a single expression of the two
     * anchor shapes: a comparison, a conditional, a comprehension, a statement, a tuple.
     */
    private static final Set<String> NON_EXPRESSION_KEYWORDS =
            Set.of(
                    "and", "or", "not", "in", "is", "if", "else", "lambda", "for", "async", "await",
                    "yield", "assert", "del", "from", "import", "pass", "while", "with", "try",
                    "raise", "return", "class", "def", "global", "nonlocal", "break", "continue",
                    "as", "except", "finally", "elif");

    private static final Set<String> NON_EXPRESSION_OPERATORS =
            Set.of("=", "==", "!=", "<", ">", "<=", ">=", ":=", ",", ";", ":", "?", "->");

    /** The East Asian Width classes {@code W} and {@code F}, exported from CPython's own table. */
    private static final int[] WIDE_RANGES = {
        0x1100, 0x115F,
        0x231A, 0x231B,
        0x2329, 0x232A,
        0x23E9, 0x23EC,
        0x23F0, 0x23F0,
        0x23F3, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2630, 0x2637,
        0x2648, 0x2653,
        0x267F, 0x267F,
        0x268A, 0x268F,
        0x2693, 0x2693,
        0x26A1, 0x26A1,
        0x26AA, 0x26AB,
        0x26BD, 0x26BE,
        0x26C4, 0x26C5,
        0x26CE, 0x26CE,
        0x26D4, 0x26D4,
        0x26EA, 0x26EA,
        0x26F2, 0x26F3,
        0x26F5, 0x26F5,
        0x26FA, 0x26FA,
        0x26FD, 0x26FD,
        0x2705, 0x2705,
        0x270A, 0x270B,
        0x2728, 0x2728,
        0x274C, 0x274C,
        0x274E, 0x274E,
        0x2753, 0x2755,
        0x2757, 0x2757,
        0x2795, 0x2797,
        0x27B0, 0x27B0,
        0x27BF, 0x27BF,
        0x2B1B, 0x2B1C,
        0x2B50, 0x2B50,
        0x2B55, 0x2B55,
        0x2E80, 0x2E99,
        0x2E9B, 0x2EF3,
        0x2F00, 0x2FD5,
        0x2FF0, 0x303E,
        0x3041, 0x3096,
        0x3099, 0x30FF,
        0x3105, 0x312F,
        0x3131, 0x318E,
        0x3190, 0x31E5,
        0x31EF, 0x321E,
        0x3220, 0x3247,
        0x3250, 0xA48C,
        0xA490, 0xA4C6,
        0xA960, 0xA97C,
        0xAC00, 0xD7A3,
        0xF900, 0xFAFF,
        0xFE10, 0xFE19,
        0xFE30, 0xFE52,
        0xFE54, 0xFE66,
        0xFE68, 0xFE6B,
        0xFF01, 0xFF60,
        0xFFE0, 0xFFE6,
        0x16FE0, 0x16FE4,
        0x16FF0, 0x16FF1,
        0x17000, 0x187F7,
        0x18800, 0x18CD5,
        0x18CFF, 0x18D08,
        0x1AFF0, 0x1AFF3,
        0x1AFF5, 0x1AFFB,
        0x1AFFD, 0x1AFFE,
        0x1B000, 0x1B122,
        0x1B132, 0x1B132,
        0x1B150, 0x1B152,
        0x1B155, 0x1B155,
        0x1B164, 0x1B167,
        0x1B170, 0x1B2FB,
        0x1D300, 0x1D356,
        0x1D360, 0x1D376,
        0x1F004, 0x1F004,
        0x1F0CF, 0x1F0CF,
        0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A,
        0x1F200, 0x1F202,
        0x1F210, 0x1F23B,
        0x1F240, 0x1F248,
        0x1F250, 0x1F251,
        0x1F260, 0x1F265,
        0x1F300, 0x1F320,
        0x1F32D, 0x1F335,
        0x1F337, 0x1F37C,
        0x1F37E, 0x1F393,
        0x1F3A0, 0x1F3CA,
        0x1F3CF, 0x1F3D3,
        0x1F3E0, 0x1F3F0,
        0x1F3F4, 0x1F3F4,
        0x1F3F8, 0x1F43E,
        0x1F440, 0x1F440,
        0x1F442, 0x1F4FC,
        0x1F4FF, 0x1F53D,
        0x1F54B, 0x1F54E,
        0x1F550, 0x1F567,
        0x1F57A, 0x1F57A,
        0x1F595, 0x1F596,
        0x1F5A4, 0x1F5A4,
        0x1F5FB, 0x1F64F,
        0x1F680, 0x1F6C5,
        0x1F6CC, 0x1F6CC,
        0x1F6D0, 0x1F6D2,
        0x1F6D5, 0x1F6D7,
        0x1F6DC, 0x1F6DF,
        0x1F6EB, 0x1F6EC,
        0x1F6F4, 0x1F6FC,
        0x1F7E0, 0x1F7EB,
        0x1F7F0, 0x1F7F0,
        0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945,
        0x1F947, 0x1F9FF,
        0x1FA70, 0x1FA7C,
        0x1FA80, 0x1FA89,
        0x1FA8F, 0x1FAC6,
        0x1FACE, 0x1FADC,
        0x1FADF, 0x1FAE9,
        0x1FAF0, 0x1FAF8,
        0x20000, 0x2FFFD,
        0x30000, 0x3FFFD,
    };

    private TracebackSource() {}

    /**
     * The block of source lines CPython prints under one entry, or null where it prints none.
     *
     * @param lines the file's lines, as read
     * @param line the line the instruction starts on, one-based
     * @param endLine the line it ends on, one-based, or {@code line} where the table names nothing
     * @param col the start column in UTF-8 bytes, or -1 where the table names nothing
     * @param endCol the end column in UTF-8 bytes, or -1 where the table names nothing
     * @return the lines, each already indented by the report's four spaces, ending in a newline; or null
     */
    static String block(List<String> lines, int line, int endLine, int col, int endCol) {
        if (line < 1 || line > lines.size()) {
            return null;
        }
        int last = Math.max(line, Math.min(endLine, lines.size()));
        List<String> original = new ArrayList<>(last - line + 1);
        for (int number = line; number <= last; number++) {
            original.add(rstrip(lines.get(number - 1)));
        }
        String joined = String.join("\n", original);
        if (joined.isBlank()) {
            return null;
        }
        if (col < 0 || endCol < 0) {
            // A code object compiled without range information, or a unit the table names nothing for:
            // CPython shows only the first line, stripped.
            String first = original.get(0).strip();
            return (first.isEmpty() ? "" : SOURCE_INDENT + first) + "\n";
        }
        List<String> all = new ArrayList<>(Arrays.asList(dedent(joined).split("\n", -1)));
        String firstLine = original.get(0);
        String lastLine = original.get(last - line);
        int startOffset = characterOffset(firstLine, col);
        int endOffset = characterOffset(lastLine, endCol);
        int dedentCharacters = firstLine.length() - all.get(0).length();
        startOffset = Math.max(0, startOffset - dedentCharacters);
        endOffset = Math.max(0, endOffset - dedentCharacters);

        int displayStart = displayWidth(all.get(0), startOffset);
        int displayEnd = displayWidth(all.get(all.size() - 1), endOffset);

        String source = String.join("\n", all);
        int segmentEnd = source.length() - (all.get(all.size() - 1).length() - endOffset);
        int from = Math.min(startOffset, source.length());
        int to = Math.min(Math.max(from, segmentEnd), source.length());
        String segment = source.substring(from, to);

        Anchors anchors = anchors(segment);
        boolean showCarets = shouldShowCarets(all, startOffset, endOffset, anchors);

        TreeSet<Integer> significant = new TreeSet<>();
        significant.add(0);
        significant.add(all.size() - 1);
        int leftLine = 0;
        int rightLine = 0;
        int leftOffset = 0;
        int rightOffset = 0;
        char primary = '^';
        char secondary = '^';
        if (anchors != null) {
            leftLine = anchors.leftLine();
            rightLine = anchors.rightLine();
            leftOffset = anchors.leftCol() + (leftLine == 0 ? startOffset : 0);
            rightOffset = anchors.rightCol() + (rightLine == 0 ? startOffset : 0);
            leftOffset = displayWidth(all.get(leftLine), leftOffset);
            rightOffset = displayWidth(all.get(rightLine), rightOffset);
            primary = '~';
            secondary = '^';
            for (int at = leftLine - 1; at <= leftLine + 1; at++) {
                significant.add(at);
            }
            for (int at = rightLine - 1; at <= rightLine + 1; at++) {
                significant.add(at);
            }
        }
        significant.removeIf(at -> at < 0 || at >= all.size());

        StringBuilder result = new StringBuilder();
        Integer previous = null;
        for (int at : significant) {
            if (previous != null) {
                int gap = at - previous;
                if (gap == 2) {
                    appendLine(result, all, at - 1, showCarets, displayStart, displayEnd,
                            anchors, leftLine, leftOffset, rightLine, rightOffset, primary, secondary);
                } else if (gap > 2) {
                    result.append("...<").append(gap - 1).append(" lines>...\n");
                }
            }
            appendLine(result, all, at, showCarets, displayStart, displayEnd,
                    anchors, leftLine, leftOffset, rightLine, rightOffset, primary, secondary);
            previous = at;
        }
        return indent(dedent(result.toString()));
    }

    private static void appendLine(
            StringBuilder result,
            List<String> all,
            int at,
            boolean showCarets,
            int displayStart,
            int displayEnd,
            Anchors anchors,
            int leftLine,
            int leftOffset,
            int rightLine,
            int rightOffset,
            char primary,
            char secondary) {
        String text = all.get(at);
        result.append(text).append('\n');
        if (!showCarets) {
            return;
        }
        int spaces = 0;
        while (spaces < text.length() && isSpace(text.charAt(spaces))) {
            spaces++;
        }
        int carets = at == all.size() - 1 ? displayEnd : displayWidth(text, text.length());
        for (int col = 0; col < carets; col++) {
            if (col < spaces || (at == 0 && col < displayStart)) {
                result.append(' ');
            } else if (anchors != null
                    && (at > leftLine || (at == leftLine && col >= leftOffset))
                    && (at < rightLine || (at == rightLine && col < rightOffset))) {
                result.append(secondary);
            } else {
                result.append(primary);
            }
        }
        result.append('\n');
    }

    // --- anchors -------------------------------------------------------------------------

    /** The two ends PEP 657 marks: everything between them is the instruction itself. */
    private record Anchors(int leftLine, int leftCol, int rightLine, int rightCol) {}

    private enum Kind {
        NAME,
        NUMBER,
        STRING,
        OPERATOR
    }

    /** One token, with the character columns it starts and ends at within the segment's lines. */
    private record Token(Kind kind, String text, int line, int col, int endLine, int endCol) {}

    /**
     * CPython's {@code _extract_caret_anchors_from_line_segment}, read without {@code ast}.
     *
     * <p>A binary expression anchors the operator: the left end is its first character, the right end
     * one or two characters later, depending on whether the second character belongs to the operator or
     * already to the right operand. A call anchors its opening parenthesis and a subscript its opening
     * bracket; both run to the end of the expression.
     */
    private static Anchors anchors(String segment) {
        List<String> lines = List.of(segment.split("\n", -1));
        List<Token> tokens = lex(segment);
        if (tokens.isEmpty()) {
            return null;
        }
        tokens = stripParens(tokens);
        int[] match = matching(tokens);
        int[] depth = depth(tokens);
        for (int at = 0; at < tokens.size(); at++) {
            if (depth[at] != 0) {
                continue;
            }
            Token token = tokens.get(at);
            if (token.kind() == Kind.NAME && NON_EXPRESSION_KEYWORDS.contains(token.text())) {
                return null;
            }
            if (token.kind() == Kind.OPERATOR && NON_EXPRESSION_OPERATORS.contains(token.text())) {
                return null;
            }
        }

        int chosen = -1;
        int best = Integer.MAX_VALUE;
        for (int at = 0; at < tokens.size(); at++) {
            if (depth[at] != 0) {
                continue;
            }
            Token token = tokens.get(at);
            if (token.kind() != Kind.OPERATOR || !BINARY_OPERATORS.contains(token.text())) {
                continue;
            }
            if (at == 0 || !endsExpression(tokens.get(at - 1))) {
                continue;
            }
            int rank = rank(token.text());
            if (rank < best) {
                best = rank;
                chosen = at;
            } else if (rank == best && !tokens.get(chosen).text().equals("**")) {
                // Same precedence: the left-associative operator nearest the end is the root, and the
                // right-associative ** keeps the first one.
                chosen = at;
            }
        }
        if (chosen >= 0) {
            if (chosen + 1 >= tokens.size()) {
                return null;
            }
            Token operator = tokens.get(chosen);
            Token right = tokens.get(chosen + 1);
            String line = lines.get(operator.line());
            int rightCol = operator.col() + 1;
            if (rightCol < line.length()
                    && (right.line() > operator.line() || rightCol < right.col())
                    && !isSpace(line.charAt(rightCol))
                    && line.charAt(rightCol) != '\\'
                    && line.charAt(rightCol) != '#') {
                rightCol++;
            }
            return new Anchors(operator.line(), operator.col(), operator.line(), rightCol);
        }
        return chain(tokens, match);
    }

    /** The last call or subscript of a primary chain, which is the other anchor shape. */
    private static Anchors chain(List<Token> tokens, int[] match) {
        int at = 0;
        Token first = tokens.get(0);
        if (first.kind() == Kind.OPERATOR) {
            if (!first.text().equals("(") && !first.text().equals("[") && !first.text().equals("{")) {
                return null;
            }
            if (match[0] < 0) {
                return null;
            }
            at = match[0] + 1;
        } else if (first.kind() == Kind.NAME
                || first.kind() == Kind.NUMBER
                || first.kind() == Kind.STRING) {
            at = 1;
        } else {
            return null;
        }
        int open = -1;
        boolean call = false;
        while (at < tokens.size()) {
            Token token = tokens.get(at);
            if (token.kind() == Kind.OPERATOR && token.text().equals(".")) {
                if (at + 1 >= tokens.size() || tokens.get(at + 1).kind() != Kind.NAME) {
                    return null;
                }
                at += 2;
                open = -1;
                call = false;
            } else if (token.kind() == Kind.OPERATOR
                    && (token.text().equals("(") || token.text().equals("["))) {
                if (match[at] < 0) {
                    return null;
                }
                open = at;
                call = token.text().equals("(");
                at = match[at] + 1;
            } else {
                return null;
            }
        }
        if (open < 0) {
            return null;
        }
        Token opener = tokens.get(open);
        Token closer = tokens.get(match[open]);
        return new Anchors(opener.line(), opener.col(), closer.endLine(), closer.endCol());
    }

    /**
     * CPython's {@code _should_show_carets}: whether a caret line belongs under the source at all.
     *
     * <p>A frame that stopped on a call whose result is the whole of a {@code return} or a single-name
     * assignment gets no caret, because the interesting part is the callee and not the call; everything
     * else that has an anchor, or that leaves anything visible before or after the instruction, does.
     */
    private static boolean shouldShowCarets(
            List<String> all, int startOffset, int endOffset, Anchors anchors) {
        if (spawnsFullLine(all, startOffset, endOffset)) {
            return false;
        }
        if (anchors != null) {
            return true;
        }
        String prefix = startOffset <= all.get(0).length() ? all.get(0).substring(0, startOffset) : "";
        String suffix =
                endOffset <= all.get(all.size() - 1).length()
                        ? all.get(all.size() - 1).substring(endOffset)
                        : "";
        return !prefix.strip().isEmpty() || !suffix.strip().isEmpty();
    }

    /** Whether the statement is {@code return <call>} or {@code <name> = <call>} with a plain name. */
    private static boolean spawnsFullLine(List<String> all, int startOffset, int endOffset) {
        List<Token> tokens = lex(String.join("\n", all));
        int from;
        if (tokens.size() >= 2
                && tokens.get(0).kind() == Kind.NAME
                && tokens.get(0).text().equals("return")) {
            from = 1;
        } else if (tokens.size() >= 3
                && tokens.get(0).kind() == Kind.NAME
                && tokens.get(1).text().equals("=")) {
            from = 2;
        } else {
            return false;
        }
        List<Token> value = new ArrayList<>(tokens.subList(from, tokens.size()));
        if (value.isEmpty()) {
            return false;
        }
        Token first = value.get(0);
        Token last = value.get(value.size() - 1);
        if (first.line() != 0
                || first.col() != startOffset
                || last.endLine() != all.size() - 1
                || last.endCol() != endOffset) {
            return false;
        }
        value = stripParens(value);
        return callOfName(value);
    }

    /** Whether these tokens are exactly a call whose function is one name. */
    private static boolean callOfName(List<Token> tokens) {
        int[] match = matching(tokens);
        int at = 0;
        Token first = tokens.get(0);
        if (first.kind() == Kind.OPERATOR && first.text().equals("(") && match[0] == tokens.size() - 1) {
            return callOfName(new ArrayList<>(tokens.subList(1, tokens.size() - 1)));
        }
        if (first.kind() != Kind.NAME || NON_EXPRESSION_KEYWORDS.contains(first.text())) {
            return false;
        }
        at = 1;
        boolean call = false;
        while (at < tokens.size()) {
            Token token = tokens.get(at);
            if (token.kind() == Kind.OPERATOR && token.text().equals(".")
                    && at + 1 < tokens.size() && tokens.get(at + 1).kind() == Kind.NAME) {
                at += 2;
                call = false;
            } else if (token.kind() == Kind.OPERATOR
                    && (token.text().equals("(") || token.text().equals("["))
                    && match[at] >= 0) {
                call = token.text().equals("(");
                at = match[at] + 1;
            } else {
                return false;
            }
        }
        return call;
    }

    private static List<Token> stripParens(List<Token> tokens) {
        List<Token> expr = new ArrayList<>(tokens);
        while (expr.size() >= 2 && expr.get(0).text().equals("(")) {
            int[] match = matching(expr);
            if (match[0] != expr.size() - 1) {
                break;
            }
            expr = new ArrayList<>(expr.subList(1, expr.size() - 1));
        }
        return expr;
    }

    private static boolean endsExpression(Token token) {
        return token.kind() == Kind.NAME
                || token.kind() == Kind.NUMBER
                || token.kind() == Kind.STRING
                || token.text().equals(")")
                || token.text().equals("]")
                || token.text().equals("}");
    }

    private static int rank(String operator) {
        return switch (operator) {
            case "|" -> 1;
            case "^" -> 2;
            case "&" -> 3;
            case "<<", ">>" -> 4;
            case "+", "-" -> 5;
            case "*", "/", "//", "%", "@" -> 6;
            case "**" -> 7;
            default -> Integer.MAX_VALUE;
        };
    }

    // --- scanning ------------------------------------------------------------------------

    private static List<Token> lex(String source) {
        List<String> lines = List.of(source.split("\n", -1));
        List<Token> tokens = new ArrayList<>();
        int line = 0;
        int col = 0;
        while (line < lines.size()) {
            String text = lines.get(line);
            if (col >= text.length()) {
                line++;
                col = 0;
                continue;
            }
            char ch = text.charAt(col);
            if (isSpace(ch)) {
                col++;
                continue;
            }
            if (ch == '#') {
                line++;
                col = 0;
                continue;
            }
            if (ch == '\\' && col == text.length() - 1) {
                line++;
                col = 0;
                continue;
            }
            int startLine = line;
            int startCol = col;
            if (ch == '\'' || ch == '"') {
                int quotes = triple(lines, line, col) ? 3 : 1;
                int[] end = scanString(lines, line, col, ch, quotes);
                line = end[0];
                col = end[1];
                tokens.add(new Token(Kind.STRING, "\"", startLine, startCol, line, col));
            } else if (isDigit(ch)
                    || (ch == '.'
                            && col + 1 < text.length()
                            && isDigit(text.charAt(col + 1)))) {
                while (col < text.length() && isNumberPart(text.charAt(col), text, col)) {
                    col++;
                }
                tokens.add(
                        new Token(
                                Kind.NUMBER,
                                text.substring(startCol, col),
                                startLine,
                                startCol,
                                line,
                                col));
            } else if (isNameStart(ch)) {
                while (col < text.length() && isNamePart(text.charAt(col))) {
                    col++;
                }
                String word = text.substring(startCol, col);
                if (col < text.length()
                        && (text.charAt(col) == '\'' || text.charAt(col) == '"')
                        && isStringPrefix(word)) {
                    char quote = text.charAt(col);
                    int quotes = triple(lines, line, col) ? 3 : 1;
                    int[] end = scanString(lines, line, col, quote, quotes);
                    line = end[0];
                    col = end[1];
                    tokens.add(new Token(Kind.STRING, "\"", startLine, startCol, line, col));
                } else {
                    tokens.add(
                            new Token(Kind.NAME, word, startLine, startCol, line, col));
                }
            } else {
                String operator = String.valueOf(ch);
                if (col + 1 < text.length()) {
                    String pair = text.substring(col, col + 2);
                    if (TWO_CHARACTER_OPERATORS.contains(pair)) {
                        operator = pair;
                    }
                }
                col += operator.length();
                tokens.add(
                        new Token(
                                Kind.OPERATOR, operator, startLine, startCol, line, col));
            }
        }
        return tokens;
    }

    private static int[] scanString(List<String> lines, int line, int col, char quote, int quotes) {
        int at = col + quotes;
        while (true) {
            String text = lines.get(line);
            if (at >= text.length()) {
                if (quotes == 1) {
                    return new int[] {line, text.length()};
                }
                line++;
                if (line >= lines.size()) {
                    return new int[] {lines.size() - 1, lines.get(lines.size() - 1).length()};
                }
                at = 0;
                continue;
            }
            char ch = text.charAt(at);
            if (ch == '\\') {
                if (at == text.length() - 1) {
                    line++;
                    if (line >= lines.size()) {
                        return new int[] {lines.size() - 1, text.length()};
                    }
                    at = 0;
                } else {
                    at += 2;
                }
                continue;
            }
            if (ch == quote) {
                if (quotes == 1) {
                    return new int[] {line, at + 1};
                }
                if (at + 2 < text.length()
                        && text.charAt(at + 1) == quote
                        && text.charAt(at + 2) == quote) {
                    return new int[] {line, at + 3};
                }
            }
            at++;
        }
    }

    private static boolean triple(List<String> lines, int line, int col) {
        String text = lines.get(line);
        char quote = text.charAt(col);
        return col + 2 < text.length() && text.charAt(col + 1) == quote && text.charAt(col + 2) == quote;
    }

    private static boolean isNumberPart(char ch, String text, int col) {
        if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') {
            return true;
        }
        return (ch == '+' || ch == '-') && col > 0 && (text.charAt(col - 1) == 'e' || text.charAt(col - 1) == 'E');
    }

    private static boolean isStringPrefix(String word) {
        return switch (word.toLowerCase()) {
            case "r", "b", "u", "f", "t", "br", "rb", "fr", "rf", "tr", "rt" -> true;
            default -> false;
        };
    }

    private static boolean isNameStart(char ch) {
        return ch == '_' || Character.isLetter(ch);
    }

    private static boolean isNamePart(char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
    }

    private static boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    private static int[] matching(List<Token> tokens) {
        int[] match = new int[tokens.size()];
        Arrays.fill(match, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for (int at = 0; at < tokens.size(); at++) {
            String text = tokens.get(at).text();
            if (text.equals("(") || text.equals("[") || text.equals("{")) {
                stack.push(at);
            } else if (text.equals(")") || text.equals("]") || text.equals("}")) {
                if (!stack.isEmpty()) {
                    int open = stack.pop();
                    match[open] = at;
                    match[at] = open;
                }
            }
        }
        return match;
    }

    private static int[] depth(List<Token> tokens) {
        int[] depth = new int[tokens.size()];
        int current = 0;
        for (int at = 0; at < tokens.size(); at++) {
            String text = tokens.get(at).text();
            if (text.equals(")") || text.equals("]") || text.equals("}")) {
                current = Math.max(0, current - 1);
            }
            depth[at] = current;
            if (text.equals("(") || text.equals("[") || text.equals("{")) {
                current++;
            }
        }
        return depth;
    }

    // --- text ----------------------------------------------------------------------------

    /** {@code _byte_offset_to_character_offset}: a UTF-8 byte column turned into a character one. */
    private static int characterOffset(String line, int offset) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        int prefix = Math.max(0, Math.min(offset, bytes.length));
        return new String(bytes, 0, prefix, StandardCharsets.UTF_8).length();
    }

    /** {@code _display_width}: the columns a prefix of {@code line} takes on a fixed-width terminal. */
    private static int displayWidth(String line, int offset) {
        int end = Math.max(0, Math.min(offset, line.length()));
        int width = 0;
        for (int at = 0; at < end; ) {
            int codePoint = line.codePointAt(at);
            width += wide(codePoint) ? 2 : 1;
            at += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean wide(int codePoint) {
        int low = 0;
        int high = WIDE_RANGES.length / 2 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (codePoint < WIDE_RANGES[2 * middle]) {
                high = middle - 1;
            } else if (codePoint > WIDE_RANGES[2 * middle + 1]) {
                low = middle + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** {@code textwrap.dedent}, as 3.14 writes it. */
    private static String dedent(String text) {
        String[] lines = text.split("\n", -1);
        int margin = -1;
        for (String line : lines) {
            if (line.isEmpty() || line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length()
                    && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            margin = margin < 0 ? indent : Math.min(margin, indent);
        }
        if (margin <= 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < lines.length; at++) {
            if (at > 0) {
                out.append('\n');
            }
            String line = lines[at];
            out.append(line.isBlank() ? "" : line.substring(Math.min(margin, line.length())));
        }
        return out.toString();
    }

    /** {@code textwrap.indent} with a predicate that takes every line, empty ones included. */
    private static String indent(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        int start = 0;
        for (int at = 0; at < text.length(); at++) {
            if (text.charAt(at) == '\n') {
                out.append(SOURCE_INDENT).append(text, start, at + 1);
                start = at + 1;
            }
        }
        if (start < text.length()) {
            out.append(SOURCE_INDENT).append(text, start, text.length());
        }
        return out.toString();
    }

    private static String rstrip(String line) {
        int end = line.length();
        while (end > 0 && isSpace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static boolean isSpace(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch);
    }
}
