package org.python.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
        // Java's `UNICODE_CHARACTER_CLASS` is not Python's Unicode mode: its `\w` adds every
        // combining mark and connector punctuation, and its `\s` lacks U+001C..U+001F. Those four
        // escapes are therefore written out here instead of being handed to Java; every other
        // escape keeps Java's default meaning, which is the ASCII one Python uses under `re.ASCII`.
        // `^` and `$` are written out for the same reason: Python's MULTILINE knows only '\n', while
        // Java's also breaks at '\r', U+0085, U+2028 and U+2029, so Java's MULTILINE bit stays off.
        String translated = new Translator(source, names, ascii,
                (flags & DOTALL) != 0, (flags & MULTILINE) != 0,
                (flags & VERBOSE) != 0).translate();
        return Pattern.compile(translated, javaFlags);
    }

    /**
     * Python's Unicode word character, which is {@code str.isalnum()} plus underscore.
     *
     * <p>That set is exactly the letters plus every number category, so it is expressible as a Java
     * class. Java's own {@code \w} under {@code UNICODE_CHARACTER_CLASS} is wider: it also admits
     * {@code Mn}/{@code Me}/{@code Mc} and {@code Pc}, which is why a combining acute accent matches
     * Java's {@code \w} but not Python's.
     */
    private static final String WORD_CHARS = "\\p{L}\\p{N}_";

    /** Python's {@code \s}: Unicode White_Space plus the four C0 separators Python counts. */
    private static final String SPACE_CHARS = "\\p{IsWhite_Space}\\x{1c}-\\x{1f}";

    /**
     * Python's {@code \b}.
     *
     * <p>Java has a word-boundary assertion of its own, but it is defined against Java's word set,
     * so the boundary is spelled as two lookarounds over {@link #WORD_CHARS}: one for a word
     * character on the left and a non-word one on the right, one for the mirror image.
     */
    private static final String WORD_BOUNDARY =
            "(?:(?<=[" + WORD_CHARS + "])(?![" + WORD_CHARS + "])"
                    + "|(?<![" + WORD_CHARS + "])(?=[" + WORD_CHARS + "]))";

    /** Python's {@code \B}: everywhere {@link #WORD_BOUNDARY} does not hold. */
    private static final String NOT_WORD_BOUNDARY =
            "(?:(?<=[" + WORD_CHARS + "])(?=[" + WORD_CHARS + "])"
                    + "|(?<![" + WORD_CHARS + "])(?![" + WORD_CHARS + "]))";

    /**
     * The Java name this class gives Python's group {@code number}.
     *
     * <p>Every Python capturing group is emitted under a name rather than left to Java's own
     * numbering, because a conditional group ({@code (?(id)yes|no)}) needs a second, empty group
     * inside group {@code id} to ask whether it took part — and that marker would shift Java's
     * numbers. The match object therefore reads its groups by name, and {@code \1} is re-spelled
     * as {@code \k<g1>}.
     */
    static String groupName(int number) {
        return "g" + number;
    }

    /** The re-spelling itself: one pass, with the two scoped flags kept on a stack. */
    private static final class Translator {

        private final String source;
        private final Map<String, Integer> names;
        private final StringBuilder out = new StringBuilder();
        private final Deque<boolean[]> enclosing = new ArrayDeque<>();
        /** The Python number of each open group, {@code 0} for a non-capturing one. */
        private final Deque<Integer> openGroups = new ArrayDeque<>();
        /** Groups a conditional asks about after they closed: each needs a participation marker. */
        private final Set<Integer> conditionalGroups = new HashSet<>();
        /**
         * The Java number of each participation marker.
         *
         * <p>A conditional may name a group that only closes later in the pattern ("group used
         * before defined"), and Java refuses a <em>named</em> backreference to a group it has not
         * seen yet. Java does accept a forward <em>numeric</em> one, so the references are written
         * as placeholders and turned into numbers once the whole pattern has been translated.
         */
        private final Map<Integer, Integer> markerNumbers = new HashMap<>();
        /** How many capturing groups Java has seen so far; markers are numbered from here. */
        private int javaGroups;
        private int groupCount;
        private int at;
        private boolean dotAll;
        private boolean verbose;
        private boolean multiline;
        /** Whether the escapes being translated right now use Python's ASCII tables. */
        private boolean ascii;

        Translator(String source, Map<String, Integer> names, boolean ascii,
                boolean dotAll, boolean multiline, boolean verbose) {
            this.source = source;
            this.names = names;
            this.ascii = ascii;
            this.dotAll = dotAll;
            this.multiline = multiline;
            this.verbose = verbose;
            scanConditionals();
        }

        /**
         * Note which groups a conditional asks about before the single pass starts.
         *
         * <p>A conditional usually names a group that has already closed, so the request for a
         * participation marker arrives after the point where that marker has to be written. This
         * scan only collects the numbers; whether the condition is live is decided during the
         * translation, where the stack of open groups is at hand.
         */
        private void scanConditionals() {
            int cursor = 0;
            while (cursor < source.length()) {
                char c = source.charAt(cursor);
                if (c == '\\') {
                    cursor += 2;
                } else if (c == '[') {
                    cursor = skipClass(cursor + 1);
                } else if (source.startsWith("(?#", cursor)) {
                    int close = source.indexOf(')', cursor);
                    cursor = close < 0 ? source.length() : close + 1;
                } else if (source.startsWith("(?(", cursor)) {
                    int close = source.indexOf(')', cursor);
                    if (close < 0) {
                        return;
                    }
                    conditionalGroups.add(conditionNumber(source.substring(cursor + 3, close)));
                    cursor = close + 1;
                } else {
                    cursor++;
                }
            }
        }

        private int skipClass(int cursor) {
            if (cursor < source.length() && source.charAt(cursor) == '^') {
                cursor++;
            }
            if (cursor < source.length() && source.charAt(cursor) == ']') {
                cursor++;
            }
            while (cursor < source.length()) {
                char c = source.charAt(cursor);
                if (c == '\\') {
                    cursor += 2;
                } else if (c == ']') {
                    return cursor + 1;
                } else {
                    cursor++;
                }
            }
            return cursor;
        }

        String translate() {
            body(-1);
            String translated = out.toString();
            for (Map.Entry<Integer, Integer> marker : markerNumbers.entrySet()) {
                translated = translated.replace(
                        "\\k<mk" + marker.getKey() + ">", "\\" + marker.getValue());
            }
            return translated;
        }

        /**
         * Translate until the end of the pattern, or — when {@code depth} is not negative — until a
         * {@code |} or {@code )} that belongs to the group open at that depth. A conditional's two
         * branches are the one caller that needs the early stop.
         */
        private void body(int depth) {
            while (at < source.length()) {
                if (depth >= 0 && openGroups.size() == depth) {
                    char stop = source.charAt(at);
                    if (stop == '|' || stop == ')') {
                        return;
                    }
                }
                step();
            }
        }

        private void step() {
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
            } else if (c == '^') {
                out.append(multiline ? "(?:\\A|(?<=\\n))" : "\\A");
                at++;
            } else if (c == '$') {
                out.append(multiline ? "(?:\\z|(?=\\n))" : "(?:\\z|(?=\\n\\z))");
                at++;
            } else if (c == '{') {
                repetition();
            } else if (c == '}') {
                out.append("\\}");
                at++;
            } else if (verbose && isVerboseWhitespace(c)) {
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
                    // ways, so the escape is written out. Outside a class Java's boundary is Java's
                    // word set, so the Unicode one is spelled here instead.
                    out.append(inClass ? "\\x08" : (ascii ? "\\b" : WORD_BOUNDARY));
                    at++;
                    return;
                case 'B':
                    if (inClass) {
                        // `re._parser` refuses `[\B]`; nothing valid reaches this point.
                        throw new IllegalArgumentException("bad escape \\B at position " + start);
                    }
                    out.append(ascii ? "\\B" : NOT_WORD_BOUNDARY);
                    at++;
                    return;
                case 'd':
                    out.append(ascii ? "\\d" : "\\p{Nd}");
                    at++;
                    return;
                case 'D':
                    out.append(ascii ? "\\D" : "\\P{Nd}");
                    at++;
                    return;
                case 's':
                    out.append(ascii
                            ? "\\s"
                            : (inClass ? SPACE_CHARS : "[" + SPACE_CHARS + "]"));
                    at++;
                    return;
                case 'S':
                    out.append(ascii ? "\\S" : "[^" + SPACE_CHARS + "]");
                    at++;
                    return;
                case 'w':
                    out.append(ascii
                            ? "\\w"
                            : (inClass ? WORD_CHARS : "[" + WORD_CHARS + "]"));
                    at++;
                    return;
                case 'W':
                    out.append(ascii ? "\\W" : "[^" + WORD_CHARS + "]");
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
            // Inside a class every numeric escape is an octal character, up to three digits, and
            // `\41` is `'!'` rather than a group reference. Java would read a one- or two-digit
            // form as a backreference and refuse it there, so the character is written out.
            if (inClass && c >= '0' && c <= '7') {
                int value = c - '0';
                int digits = 1;
                at++;
                while (digits < 3 && at < source.length() && isOctal(source.charAt(at))) {
                    value = value * 8 + (source.charAt(at) - '0');
                    at++;
                    digits++;
                }
                codePoint(value);
                return;
            }
            // Outside a class, three octal digits are a character: `\277` is U+00BF even when the
            // pattern also has 277 groups, while `\41` stays a group reference.
            if (c >= '1' && c <= '7'
                    && at + 2 < source.length()
                    && isOctal(source.charAt(at + 1))
                    && isOctal(source.charAt(at + 2))) {
                int value = (c - '0') * 64
                        + (source.charAt(at + 1) - '0') * 8
                        + (source.charAt(at + 2) - '0');
                at += 3;
                codePoint(value);
                return;
            }
            if (c >= '1' && c <= '9') {
                // A group reference goes by name, not by Java's number: a participation marker is an
                // extra Java group and would shift every number after it. It is at most two digits —
                // `_parser.py` reads only one more digit here, so `\119` is group 11 and a literal 9.
                int value = c - '0';
                at++;
                if (at < source.length() && isDigit(source.charAt(at))) {
                    value = value * 10 + (source.charAt(at) - '0');
                    at++;
                }
                out.append("\\k<").append(groupName(value)).append('>');
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
                int number = ++groupCount;
                enter(number);
                out.append("(?<").append(groupName(number)).append('>');
                javaGroups++;
                at++;
                return;
            }
            char kind = source.charAt(at + 2);
            switch (kind) {
                case ':':
                    enter(0);
                    out.append("(?:");
                    at += 3;
                    return;
                case '=':
                    enter(0);
                    out.append("(?=");
                    at += 3;
                    return;
                case '!':
                    enter(0);
                    out.append("(?!");
                    at += 3;
                    return;
                case '>':
                    enter(0);
                    out.append("(?>");
                    at += 3;
                    return;
                case '(':
                    conditional(start);
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
                        enter(0);
                        out.append("(?<=");
                        at += 4;
                        return;
                    }
                    if (at + 3 < source.length() && source.charAt(at + 3) == '!') {
                        enter(0);
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
                groupCount = number;
                enter(number);
                out.append("(?<").append(groupName(number)).append('>');
                javaGroups++;
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
                ascii = outer[2];
                multiline = outer[3];
            }
            int number = openGroups.isEmpty() ? 0 : openGroups.pop();
            if (number > 0 && conditionalGroups.contains(number)) {
                // An empty group at the very end of group `number`: it takes part exactly when that
                // group did, and being zero-width it leaves the group's text alone.
                participationMarker(number);
            }
            out.append(')');
            at++;
        }

        private void participationMarker(int number) {
            markerNumbers.put(number, ++javaGroups);
            out.append("(?<mk").append(number).append(">)");
        }

        /**
         * {@code (?(id)yes|no)}, which Java has no construct for.
         *
         * <p>The condition is spelled as a lookahead over an empty group placed inside the group
         * being asked about (see {@link #participationMarker}): Java fails a backreference to a
         * group that never took part, and succeeds on one that matched the empty string, so
         * {@code (?=\k<mkN>)} is exactly "group N took part". A group that is still open reads as
         * "did not take part" — CPython records a group's end mark only when it closes — so that
         * case takes the {@code no} branch outright rather than needing a marker it cannot have.
         */
        private void conditional(int start) {
            at += 3;
            int from = at;
            while (at < source.length() && source.charAt(at) != ')') {
                at++;
            }
            if (at >= source.length()) {
                throw new IllegalArgumentException(
                        "missing ), unterminated subpattern at position " + start);
            }
            String id = source.substring(from, at);
            at++;
            int number = conditionNumber(id);
            String test = "\\k<mk" + number + ">";
            boolean taken = !openGroups.contains(number);
            if (taken) {
                conditionalGroups.add(number);
            }
            int depth = openGroups.size();
            out.append("(?:");
            if (taken) {
                out.append("(?=").append(test).append(')');
            } else {
                // `(?!)` never matches, so the `yes` branch is skipped while its groups still get
                // their Python numbers.
                out.append("(?!)");
            }
            body(depth);
            out.append('|');
            if (taken) {
                out.append("(?!").append(test).append(')');
            }
            if (at < source.length() && source.charAt(at) == '|') {
                at++;
                body(depth);
            }
            at++;
            out.append(')');
        }

        private int conditionNumber(String id) {
            boolean digits = !id.isEmpty();
            for (int i = 0; i < id.length() && digits; i++) {
                digits = isDigit(id.charAt(i));
            }
            if (digits) {
                return Integer.parseInt(id);
            }
            Integer number = names.get(id);
            if (number == null) {
                throw new IllegalArgumentException("unknown group name '" + id + "'");
            }
            return number;
        }

        private void enter(int number) {
            enclosing.push(new boolean[] {dotAll, verbose, ascii, multiline});
            openGroups.push(number);
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
            boolean newMultiline = multiline;
            boolean newAscii = ascii;
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
                        // The escape tables follow the scoped flag; the `uU` pair is what Java uses
                        // for the same distinction when it is the one reading the escapes.
                        newAscii = adding;
                        if (adding) {
                            remove.append("uU");
                        } else {
                            add.append("uU");
                        }
                        break;
                    case 'u':
                        newAscii = !adding;
                        if (adding) {
                            add.append("uU");
                        } else {
                            remove.append("uU");
                        }
                        break;
                    case 'i':
                        if (adding) {
                            add.append(flag);
                        } else {
                            remove.append(flag);
                        }
                        break;
                    case 'm':
                        // Kept for the Translator, which spells `^` and `$` itself; Java's own
                        // MULTILINE would also break at the three terminators Python ignores.
                        newMultiline = adding;
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
            enter(0);
            dotAll = newDotAll;
            verbose = newVerbose;
            multiline = newMultiline;
            ascii = newAscii;
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

        /**
         * The six characters {@code re.VERBOSE} drops, which are CPython's {@code WHITESPACE} set
         * rather than Java's: {@code Character.isWhitespace} also covers U+001C..U+001F, and those
         * four are ordinary literals to a Python pattern even under the flag.
         */
        private static boolean isVerboseWhitespace(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000b' || c == '\f';
        }

        private static boolean isOctal(char c) {
            return c >= '0' && c <= '7';
        }
    }
}
