package org.python.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The bridge from CPython's regular-expression syntax to {@link java.util.regex}.
 *
 * <p>{@code re} in 3.14 is the pure-Python parser and compiler, and the only thing it asks of the
 * runtime is {@code _sre.compile}. So the pattern string arrives here already validated: a broken
 * bracket, a bad group reference or an unknown escape has been refused by {@code re._parser} and
 * never reaches this class. What is left is syntax Java spells differently, and the translation below
 * is deliberately a re-spelling rather than a second parser — group numbers are preserved by keeping
 * one Java {@code (} for every Python capturing group, so {@code \1} and {@code \k<gN>} stay right.
 *
 * <p>The two flags whose meaning Java would give a different scope — {@code DOTALL} and
 * {@code VERBOSE} — are handled here instead of being handed to Java: {@code .} is written out as the
 * class Python means, and whitespace and {@code #} comments are dropped outside character classes.
 */
final class SreRegex {

    /** The {@code SRE_FLAG_*} bits {@code re._constants} defines, repeated here for the runtime side. */
    static final int IGNORECASE = 2;
    static final int LOCALE = 4;
    static final int MULTILINE = 8;
    static final int DOTALL = 16;
    static final int UNICODE = 32;
    static final int VERBOSE = 64;
    static final int DEBUG = 128;
    static final int ASCII = 256;

    private SreRegex() {
    }

    /**
     * Compile {@code source} for a {@code str} pattern.
     *
     * @param names the Python group names and their numbers, as {@code re._parser} recorded them
     * @throws IllegalArgumentException for a construct Java cannot express, with a sentence fit for
     *     {@code re.error}
     * @throws java.util.regex.PatternSyntaxException for anything else Java refuses
     */
    static Pattern compile(String source, int flags, boolean bytes, Map<String, Integer> names) {
        // A bytes pattern has no Unicode flag and never will: every character class and every case
        // fold is the ASCII one, exactly as for a str pattern written with `re.ASCII`.
        boolean ascii = bytes || (flags & (ASCII | LOCALE)) != 0;
        int javaFlags = 0;
        if ((flags & IGNORECASE) != 0) {
            javaFlags |= Pattern.CASE_INSENSITIVE;
            if (!ascii) {
                // Java folds ASCII case only unless asked otherwise, and Python's str patterns fold
                // the Unicode table by default.
                javaFlags |= Pattern.UNICODE_CASE;
            }
        }
        if ((flags & MULTILINE) != 0) {
            javaFlags |= Pattern.MULTILINE;
        }
        if (!ascii) {
            // `\w`, `\d`, `\s` and `\b` are Unicode-aware for a Python str pattern.
            javaFlags |= Pattern.UNICODE_CHARACTER_CLASS;
        }
        String translated = new Translator(
                source, names, (flags & DOTALL) != 0, (flags & VERBOSE) != 0).translate();
        return Pattern.compile(translated, javaFlags);
    }

    /** The re-spelling itself: one pass, with the two scoped flags kept on a stack. */
    private static final class Translator {

        private final String source;
        private final Map<String, Integer> names;
        private final StringBuilder out = new StringBuilder();
        private final Deque<boolean[]> enclosing = new ArrayDeque<>();
        private int at;
        private boolean dotAll;
        private boolean verbose;

        Translator(String source, Map<String, Integer> names,
                boolean dotAll, boolean verbose) {
            this.source = source;
            this.names = names;
            this.dotAll = dotAll;
            this.verbose = verbose;
        }

        String translate() {
            while (at < source.length()) {
                char c = source.charAt(at);
                if (c == '\\') {
                    backslash(false);
                } else if (c == '[') {
                    characterClass();
                } else if (c == '(') {
                    group();
                } else if (c == ')') {
                    closeGroup();
                } else if (c == '.' && !dotAll) {
                    out.append("[^\\n]");
                    at++;
                } else if (c == '.') {
                    out.append("[\\s\\S]");
                    at++;
                } else if (c == '{') {
                    repetition();
                } else if (c == '}') {
                    out.append("\\}");
                    at++;
                } else if (verbose && Character.isWhitespace(c)) {
                    at++;
                } else if (verbose && c == '#') {
                    while (at < source.length() && source.charAt(at) != '\n') {
                        at++;
                    }
                } else {
                    out.append(c);
                    at++;
                }
            }
            return out.toString();
        }

        /** One escape or backreference, copied or re-spelled as Java needs it. */
        private void backslash(boolean inClass) {
            int start = at;
            at++;
            if (at >= source.length()) {
                out.append("\\\\");
                return;
            }
            char c = source.charAt(at);
            switch (c) {
                case 'A':
                    out.append("\\A");
                    at++;
                    return;
                case 'Z':
                case 'z':
                    // Python's two spellings both mean the absolute end; Java's \Z is not absolute.
                    out.append("\\z");
                    at++;
                    return;
                case 'b':
                    // `[\b]` is the backspace character in Python, while Java reads `\b` as a word
                    // boundary and refuses it inside a class; the character is the one that works both
                    // ways, so the escape is written out. Outside a class Java's meaning is Python's.
                    out.append(inClass ? "\\x08" : "\\b");
                    at++;
                    return;
                case 'B':
                case 'd':
                case 'D':
                case 's':
                case 'S':
                case 'w':
                case 'W':
                    out.append('\\').append(c);
                    at++;
                    return;
                case 'x':
                    // `re._parser` has already demanded exactly two hex digits.
                    out.append('\\').append('x').append(source, at + 1, at + 3);
                    at += 3;
                    return;
                case 'u': {
                    int value = hex(at + 1, 4);
                    at += 5;
                    codePoint(value);
                    return;
                }
                case 'U': {
                    int value = hex(at + 1, 8);
                    at += 9;
                    codePoint(value);
                    return;
                }
                case 'N': {
                    int close = source.indexOf('}', at);
                    if (close < 0) {
                        throw new IllegalArgumentException(
                                "missing }, unterminated name at position " + start);
                    }
                    String name = source.substring(at + 2, close);
                    int value = UnicodeNames.codePointOf(name);
                    at = close + 1;
                    codePoint(value);
                    return;
                }
                default:
                    break;
            }
            if (c >= '1' && c <= '9') {
                out.append('\\');
                while (at < source.length() && isDigit(source.charAt(at))) {
                    out.append(source.charAt(at));
                    at++;
                }
                return;
            }
            if (c == '0') {
                int value = 0;
                int digits = 0;
                at++;
                while (digits < 2 && at < source.length() && isOctal(source.charAt(at))) {
                    value = value * 8 + (source.charAt(at) - '0');
                    at++;
                    digits++;
                }
                codePoint(value);
                return;
            }
            // Everything else Java spells the same way: `\.`, `\\`, `\n`, `\t`, `\ `, and so on.
            out.append('\\').append(c);
            at++;
        }

        private void codePoint(int value) {
            out.append("\\x{").append(Integer.toHexString(value)).append('}');
        }

        private int hex(int from, int count) {
            if (from + count > source.length()) {
                throw new IllegalArgumentException("incomplete escape");
            }
            return Integer.parseUnsignedInt(source.substring(from, from + count), 16);
        }

        /** A character class: copied, with the characters Java would give a meaning escaped. */
        private void characterClass() {
            out.append('[');
            at++;
            if (at < source.length() && source.charAt(at) == '^') {
                out.append('^');
                at++;
            }
            boolean first = true;
            while (at < source.length()) {
                char c = source.charAt(at);
                if (c == ']' && !first) {
                    out.append(']');
                    at++;
                    return;
                }
                if (c == '\\') {
                    backslash(true);
                } else if (c == '&' || c == '~') {
                    // Java reads `&&` as intersection and `~~` as symmetric difference; Python has
                    // neither, so these are three literal characters.
                    out.append('\\').append(c);
                    at++;
                } else if (c == '[') {
                    out.append("\\[");
                    at++;
                } else {
                    out.append(c);
                    at++;
                }
                first = false;
            }
            throw new IllegalArgumentException("unterminated character set");
        }

        /** A group opener, or one of the constructs that only look like one. */
        private void group() {
            int start = at;
            if (at + 1 >= source.length() || source.charAt(at + 1) != '?') {
                enter();
                out.append('(');
                at++;
                return;
            }
            char kind = source.charAt(at + 2);
            switch (kind) {
                case ':':
                    enter();
                    out.append("(?:");
                    at += 3;
                    return;
                case '=':
                    enter();
                    out.append("(?=");
                    at += 3;
                    return;
                case '!':
                    enter();
                    out.append("(?!");
                    at += 3;
                    return;
                case '>':
                    enter();
                    out.append("(?>");
                    at += 3;
                    return;
                case '#':
                    // A comment group: nothing to emit, and its text is not scanned.
                    while (at < source.length() && source.charAt(at) != ')') {
                        at++;
                    }
                    if (at >= source.length()) {
                        throw new IllegalArgumentException(
                                "missing ), unterminated comment at position " + start);
                    }
                    at++;
                    return;
                case '<':
                    if (at + 3 < source.length() && source.charAt(at + 3) == '=') {
                        enter();
                        out.append("(?<=");
                        at += 4;
                        return;
                    }
                    if (at + 3 < source.length() && source.charAt(at + 3) == '!') {
                        enter();
                        out.append("(?<!");
                        at += 4;
                        return;
                    }
                    // `(?<name>` is not Python syntax and `re._parser` refuses it.
                    throw new IllegalArgumentException(
                            "unknown extension ?< at position " + start);
                case 'P':
                    namedGroup(start);
                    return;
                default:
                    if (isFlagLetter(kind) || kind == '-') {
                        inlineFlags(start);
                        return;
                    }
                    throw new IllegalArgumentException(
                            "unknown extension ?" + kind + " at position " + start);
            }
        }

        /** {@code (?P<name>} and {@code (?P=name)}, both re-spelled with a Java group name. */
        private void namedGroup(int start) {
            if (source.startsWith("(?P<", at)) {
                int close = source.indexOf('>', at);
                if (close < 0) {
                    throw new IllegalArgumentException(
                            "missing >, unterminated name at position " + start);
                }
                String name = source.substring(at + 4, close);
                Integer number = names.get(name);
                if (number == null) {
                    throw new IllegalArgumentException("unknown group name '" + name + "'");
                }
                enter();
                out.append("(?<g").append(number).append('>');
                at = close + 1;
                return;
            }
            if (source.startsWith("(?P=", at)) {
                int close = source.indexOf(')', at);
                if (close < 0) {
                    throw new IllegalArgumentException(
                            "missing ), unterminated name at position " + start);
                }
                String name = source.substring(at + 4, close);
                Integer number = names.get(name);
                if (number == null) {
                    throw new IllegalArgumentException("unknown group name '" + name + "'");
                }
                out.append("\\k<g").append(number).append('>');
                at = close + 1;
                return;
            }
            throw new IllegalArgumentException("unknown extension ?P at position " + start);
        }

        private void closeGroup() {
            if (!enclosing.isEmpty()) {
                boolean[] outer = enclosing.pop();
                dotAll = outer[0];
                verbose = outer[1];
            }
            out.append(')');
            at++;
        }

        private void enter() {
            enclosing.push(new boolean[] {dotAll, verbose});
        }

        /**
         * {@code (?aiLmsux)} or {@code (?aiLmsux-imsx:...)}.
         *
         * <p>The unscoped form has already been folded into the flags by the Python parser, so it is
         * dropped. The scoped form becomes a Java non-capturing group carrying only the flags Java
         * still has to honour: {@code i} and {@code m}, plus {@code u}/{@code U} for the two that
         * choose the case and character tables. {@code s} and {@code x} are taken here, because Java
         * would give them the wrong scope.
         */
        private void inlineFlags(int start) {
            int from = at + 2;
            int cursor = from;
            boolean adding = true;
            StringBuilder add = new StringBuilder();
            StringBuilder remove = new StringBuilder();
            boolean scoped = false;
            boolean newDotAll = dotAll;
            boolean newVerbose = verbose;
            while (cursor < source.length()) {
                char flag = source.charAt(cursor);
                if (flag == '-') {
                    adding = false;
                    cursor++;
                    continue;
                }
                if (flag == ':') {
                    scoped = true;
                    cursor++;
                    break;
                }
                if (flag == ')') {
                    cursor++;
                    break;
                }
                switch (flag) {
                    case 'a':
                        if (adding) {
                            remove.append("uU");
                        } else {
                            add.append("uU");
                        }
                        break;
                    case 'u':
                        if (adding) {
                            add.append("uU");
                        } else {
                            remove.append("uU");
                        }
                        break;
                    case 'i':
                    case 'm':
                        if (adding) {
                            add.append(flag);
                        } else {
                            remove.append(flag);
                        }
                        break;
                    case 's':
                        newDotAll = adding;
                        break;
                    case 'x':
                        newVerbose = adding;
                        break;
                    case 'L':
                        // A locale is a bytes-pattern notion and cannot change a str pattern.
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "unknown flag at position " + (cursor - 1));
                }
                cursor++;
            }
            if (!scoped) {
                at = cursor;
                return;
            }
            enter();
            dotAll = newDotAll;
            verbose = newVerbose;
            out.append("(?");
            if (add.length() > 0) {
                out.append(add);
            }
            if (remove.length() > 0) {
                out.append('-').append(remove);
            }
            out.append(':');
            at = cursor;
        }

        /** A counted repetition, with Python's {@code {,n}} spelled the way Java requires. */
        private void repetition() {
            int cursor = at + 1;
            boolean digits = false;
            while (cursor < source.length() && isDigit(source.charAt(cursor))) {
                cursor++;
                digits = true;
            }
            if (cursor < source.length() && source.charAt(cursor) == ',') {
                cursor++;
                while (cursor < source.length() && isDigit(source.charAt(cursor))) {
                    cursor++;
                    digits = true;
                }
            }
            if (!digits || cursor >= source.length() || source.charAt(cursor) != '}') {
                out.append("\\{");
                at++;
                return;
            }
            cursor = at + 1;
            if (source.charAt(cursor) == ',') {
                // `{,n}` means `{0,n}`, which Java does not accept as written.
                out.append("{0");
            } else {
                out.append('{');
            }
            while (source.charAt(cursor) != '}') {
                out.append(source.charAt(cursor));
                cursor++;
            }
            out.append('}');
            at = cursor + 1;
        }

        private static boolean isFlagLetter(char c) {
            return c == 'a' || c == 'i' || c == 'L' || c == 'm'
                    || c == 's' || c == 'u' || c == 'x';
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isOctal(char c) {
            return c >= '0' && c <= '7';
        }
    }
}
