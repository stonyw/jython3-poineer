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
        // `IGNORECASE` is not handed to Java. Java folds one literal at a time against its own
        // tables and its `UNICODE_CASE` closure is directional, so `re.fullmatch('ß', 'ẞ', re.I)`
        // comes out False where the reference says True. The translator below folds in Python's own
        // terms instead: a cased literal stands for every character whose simple lowercase is one
        // of that character's lowercase forms, with `re._casefix._EXTRA_CASES` folded in.
        // Java's `UNICODE_CHARACTER_CLASS` is not Python's Unicode mode: its `\w` adds every
        // combining mark and connector punctuation, and its `\s` lacks U+001C..U+001F. Those four
        // escapes are therefore written out here instead of being handed to Java; every other
        // escape keeps Java's default meaning, which is the ASCII one Python uses under `re.ASCII`.
        // `^` and `$` are written out for the same reason: Python's MULTILINE knows only '\n', while
        // Java's also breaks at '\r', U+0085, U+2028 and U+2029, so Java's MULTILINE bit stays off.
        String translated = new Translator(source, names, ascii,
                (flags & DOTALL) != 0, (flags & MULTILINE) != 0,
                (flags & VERBOSE) != 0, (flags & IGNORECASE) != 0).translate();
        return Pattern.compile(translated);
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
        /** Whether the tokens being translated right now fold case the way {@code re.IGNORECASE} does. */
        private boolean ignoreCase;

        Translator(String source, Map<String, Integer> names, boolean ascii,
                boolean dotAll, boolean multiline, boolean verbose, boolean ignoreCase) {
            this.source = source;
            this.names = names;
            this.ascii = ascii;
            this.dotAll = dotAll;
            this.multiline = multiline;
            this.verbose = verbose;
            this.ignoreCase = ignoreCase;
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
            } else if (c == '}' || c == '*' || c == '+' || c == '?' || c == '|') {
                // Operators both engines spell the same way; they are not literals and never fold.
                out.append(c == '}' ? "\\}" : String.valueOf(c));
                at++;
            } else if (verbose && isVerboseWhitespace(c)) {
                at++;
            } else if (verbose && c == '#') {
                while (at < source.length() && source.charAt(at) != '\n') {
                    at++;
                }
            } else if (ignoreCase) {
                int codePoint = source.codePointAt(at);
                at += Character.charCount(codePoint);
                literal(codePoint);
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
                case 'x': {
                    // `re._parser` has already demanded exactly two hex digits.
                    int value = hex(at + 1, 2);
                    at += 3;
                    emitEscapeLiteral(value, inClass);
                    return;
                }
                case 'u': {
                    int value = hex(at + 1, 4);
                    at += 5;
                    emitEscapeLiteral(value, inClass);
                    return;
                }
                case 'U': {
                    int value = hex(at + 1, 8);
                    at += 9;
                    emitEscapeLiteral(value, inClass);
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
                    emitEscapeLiteral(value, inClass);
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
                emitEscapeLiteral(value, inClass);
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
                groupReference(value);
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
                emitEscapeLiteral(value, inClass);
                return;
            }
            // Everything else Java spells the same way: `\.`, `\\`, `\n`, `\t`, `\ `, and so on.
            out.append('\\').append(c);
            at++;
        }

        private void codePoint(int value) {
            out.append("\\x{").append(Integer.toHexString(value)).append('}');
        }

        /** A character written as an escape, folded when {@code IGNORECASE} asks for it. */
        private void emitEscapeLiteral(int value, boolean inClass) {
            if (ignoreCase && !inClass) {
                literal(value);
            } else {
                codePoint(value);
            }
        }

        /**
         * One literal character under {@code IGNORECASE}.
         *
         * <p>The reference does not compare a literal against the text with anything like Java's
         * case-insensitive match. Its compiler lowers a cased literal once and its matcher lowers
         * the text, so a literal stands for every character whose simple lowercase is one of that
         * character's lowercase forms — for {@code 'ß'} that is the two characters {@code 'ß'} and
         * {@code 'ẞ'}, and for {@code 'i'} it is {@code 'I'}, {@code 'i'}, {@code 'İ'} and
         * {@code 'ı'} because {@code re._casefix._EXTRA_CASES} says those last two share an uppercase
         * form. Uncased literals stand for themselves only, which is why the table's {@code Cased}
         * notion (not Unicode's) decides.
         */
        private void literal(int codePoint) {
            if (!ignoreCase) {
                codePoint(codePoint);
                return;
            }
            if (ascii) {
                if ((codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= 'a' && codePoint <= 'z')) {
                    CodeSet both = new CodeSet();
                    both.add(asciiLower(codePoint));
                    both.add(asciiLower(codePoint) - ('a' - 'A'));
                    emitClass(both, "", false);
                } else {
                    codePoint(codePoint);
                }
                return;
            }
            if (!UnicodeCase.sreCased(codePoint)) {
                codePoint(codePoint);
                return;
            }
            CodeSet members = new CodeSet();
            addLiteralMembers(members, codePoint);
            emitClass(preimage(members, false), "", false);
        }

        /**
         * A backreference under {@code IGNORECASE}.
         *
         * <p>The reference compares the two texts character by character under the simple lowercase
         * map. Java cannot be told to do that for a backreference, so the reference is wrapped in a
         * scoped {@code (?i:...)} and Java's own comparison is used. The two agree on every case
         * that matters here — including {@code 'ß'}/{@code 'ẞ'} — but this is the one place the
         * translation borrows Java's folding instead of the table.
         */
        private void groupReference(int number) {
            if (ignoreCase) {
                out.append(ascii ? "(?i:" : "(?iu:");
            }
            out.append("\\k<").append(groupName(number)).append('>');
            if (ignoreCase) {
                out.append(')');
            }
        }

        private int hex(int from, int count) {
            if (from + count > source.length()) {
                throw new IllegalArgumentException("incomplete escape");
            }
            return Integer.parseUnsignedInt(source.substring(from, from + count), 16);
        }

        /**
         * A character class.
         *
         * <p>Without {@code IGNORECASE} the text is copied, with the characters Java would give a
         * meaning escaped. With it the class is rebuilt from its members: every literal and every
         * range is lowered the way the reference compiler lowers it, and the result is written as
         * the set of characters that lower into one of those values. That is the same rule the
         * reference matcher applies, but resolved at compile time so Java's own folding is never
         * consulted.
         */
        private void characterClass() {
            if (ignoreCase) {
                foldedCharacterClass();
                return;
            }
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

        /** A character class under {@code IGNORECASE}, rebuilt from its folded members. */
        private void foldedCharacterClass() {
            at++;
            boolean negated = false;
            if (at < source.length() && source.charAt(at) == '^') {
                negated = true;
                at++;
            }
            CodeSet members = new CodeSet();
            StringBuilder categories = new StringBuilder();
            boolean first = true;
            boolean closed = false;
            while (at < source.length()) {
                char c = source.charAt(at);
                if (c == ']' && !first) {
                    at++;
                    closed = true;
                    break;
                }
                int start = at;
                int[] literal = classElement(categories);
                first = false;
                if (literal == null) {
                    continue;
                }
                if (at < source.length() && source.charAt(at) == '-'
                        && at + 1 < source.length() && source.charAt(at + 1) != ']') {
                    at++;
                    int[] end = classElement(categories);
                    if (end == null) {
                        throw new IllegalArgumentException(
                                "bad character range at position " + start);
                    }
                    addRangeMembers(members, literal[0], end[0]);
                } else {
                    addLiteralMembers(members, literal[0]);
                }
            }
            if (!closed) {
                throw new IllegalArgumentException("unterminated character set");
            }
            emitClass(preimage(members, ascii), categories.toString(), negated);
        }

        /**
         * One element of a class: a literal code point, a two-element range to come, or {@code null}
         * when the element was a category, which is folded into {@code categories} as Java class
         * text. A category is invariant under the simple lowercase map — no digit, underscore or
         * whitespace character has a case relation — so it can be copied unchanged.
         */
        private int[] classElement(StringBuilder categories) {
            char c = source.charAt(at);
            if (c != '\\') {
                int codePoint = source.codePointAt(at);
                at += Character.charCount(codePoint);
                return new int[] {codePoint};
            }
            int start = at;
            at++;
            if (at >= source.length()) {
                throw new IllegalArgumentException("bad escape at position " + start);
            }
            char escape = source.charAt(at);
            switch (escape) {
                case 'b':
                    // `[\b]` is the backspace character, not a boundary.
                    at++;
                    return new int[] {0x08};
                case 'd':
                    categories.append(ascii ? "\\d" : "\\p{Nd}");
                    at++;
                    return null;
                case 'D':
                    categories.append(ascii ? "\\D" : "\\P{Nd}");
                    at++;
                    return null;
                case 's':
                    categories.append(ascii ? "\\s" : SPACE_CHARS);
                    at++;
                    return null;
                case 'S':
                    categories.append(ascii ? "\\S" : "[^" + SPACE_CHARS + "]");
                    at++;
                    return null;
                case 'w':
                    categories.append(ascii ? "\\w" : WORD_CHARS);
                    at++;
                    return null;
                case 'W':
                    categories.append(ascii ? "\\W" : "[^" + WORD_CHARS + "]");
                    at++;
                    return null;
                case 'x': {
                    int value = hex(at + 1, 2);
                    at += 3;
                    return new int[] {value};
                }
                case 'u': {
                    int value = hex(at + 1, 4);
                    at += 5;
                    return new int[] {value};
                }
                case 'U': {
                    int value = hex(at + 1, 8);
                    at += 9;
                    return new int[] {value};
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
                    return new int[] {value};
                }
                default:
                    break;
            }
            if (escape >= '0' && escape <= '7') {
                int value = escape - '0';
                int digits = 1;
                at++;
                while (digits < 3 && at < source.length() && isOctal(source.charAt(at))) {
                    value = value * 8 + (source.charAt(at) - '0');
                    at++;
                    digits++;
                }
                return new int[] {value};
            }
            at++;
            return new int[] {unescaped(escape)};
        }

        /** Add one literal's folded form to a class: its lowercase and the extras that share it. */
        private void addLiteralMembers(CodeSet members, int codePoint) {
            int lower = ascii ? asciiLower(codePoint) : UnicodeCase.simpleLower(codePoint);
            members.add(lower);
            if (!ascii) {
                int[] extras = UnicodeCase.extraCases(lower);
                if (extras != null) {
                    for (int extra : extras) {
                        members.add(extra);
                    }
                }
            }
        }

        /**
         * Add a range's folded form to a class.
         *
         * <p>The reference folds every code point of the range, and for the characters of
         * {@code _EXTRA_CASES} also their partners; the matching side lowers the text the same way.
         * So the class contains the image of the range under the lowercase map — computed from the
         * table rather than by walking the range — plus the extras of that image.
         */
        private void addRangeMembers(CodeSet members, int low, int high) {
            if (ascii) {
                members.addRange(low, high);
                int from = Math.max(low, 'A');
                int to = Math.min(high, 'Z');
                if (from <= to) {
                    members.addRange(from + ('a' - 'A'), to + ('a' - 'A'));
                }
                return;
            }
            CodeSet image = new CodeSet();
            image.addRange(low, high);
            image.subtractPoints(UnicodeCase.simpleLowerKeys());
            for (int i = 0; i < UnicodeCase.simpleLowerCount(); i++) {
                int key = UnicodeCase.simpleLowerKey(i);
                if (key >= low && key <= high) {
                    image.add(UnicodeCase.simpleLowerValue(i));
                }
            }
            members.addAll(image);
            for (int key : UnicodeCase.extraCaseKeys()) {
                if (image.contains(key)) {
                    for (int extra : UnicodeCase.extraCases(key)) {
                        members.add(extra);
                    }
                }
            }
        }

        /**
         * The characters whose folded form is one of {@code members}.
         *
         * <p>Every code point either folds to itself or has a table entry. The fixed points are the
         * members minus the table's keys, and a key answers when its image is a member — which is how
         * {@code 'I'}, {@code 'İ'} and {@code 'ı'} all answer to {@code 'i'}.
         */
        private static CodeSet preimage(CodeSet members, boolean ascii) {
            CodeSet result = new CodeSet();
            if (ascii) {
                result.addAll(members);
                for (int letter = 'a'; letter <= 'z'; letter++) {
                    if (members.contains(letter)) {
                        result.add(letter - ('a' - 'A'));
                    }
                }
                return result;
            }
            for (int i = 0; i < UnicodeCase.simpleLowerCount(); i++) {
                if (members.contains(UnicodeCase.simpleLowerValue(i))) {
                    result.add(UnicodeCase.simpleLowerKey(i));
                }
            }
            CodeSet fixed = members.copy();
            fixed.subtractPoints(UnicodeCase.simpleLowerKeys());
            result.addAll(fixed);
            return result;
        }

        /** Write a class of code points, with any copied category text appended. */
        private void emitClass(CodeSet set, String categories, boolean negated) {
            if (set.isEmpty() && categories.isEmpty()) {
                // `[]` is not a Java class; an empty set is either "no character" or "any character".
                out.append(negated ? "[\\s\\S]" : "(?!)");
                return;
            }
            out.append('[');
            if (negated) {
                out.append('^');
            }
            set.appendTo(out);
            out.append(categories);
            out.append(']');
        }

        private static int asciiLower(int codePoint) {
            return codePoint >= 'A' && codePoint <= 'Z'
                    ? codePoint + ('a' - 'A')
                    : codePoint;
        }

        /** The character an in-class escape stands for, after the categories are taken out. */
        private static int unescaped(char escape) {
            switch (escape) {
                case 'a':
                    return 0x07;
                case 'f':
                    return 0x0c;
                case 'n':
                    return 0x0a;
                case 'r':
                    return 0x0d;
                case 't':
                    return 0x09;
                case 'v':
                    return 0x0b;
                default:
                    return escape;
            }
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
                groupReference(number);
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
                ignoreCase = outer[4];
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
            enclosing.push(new boolean[] {dotAll, verbose, ascii, multiline, ignoreCase});
            openGroups.push(number);
        }

        /**
         * {@code (?aiLmsux)} or {@code (?aiLmsux-imsx:...)}.
         *
         * <p>The unscoped form has already been folded into the flags by the Python parser, so it is
         * dropped. The scoped form only changes this translator's own state: every flag is applied
         * here, so the group is emitted as a plain {@code (?:} and Java is never asked to reinterpret
         * a scope.
         */
        private void inlineFlags(int start) {
            int cursor = at + 2;
            boolean adding = true;
            boolean scoped = false;
            boolean newDotAll = dotAll;
            boolean newVerbose = verbose;
            boolean newMultiline = multiline;
            boolean newAscii = ascii;
            boolean newIgnoreCase = ignoreCase;
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
                        newAscii = adding;
                        break;
                    case 'u':
                        newAscii = !adding;
                        break;
                    case 'i':
                        newIgnoreCase = adding;
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
            ignoreCase = newIgnoreCase;
            out.append("(?:");
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

        /** A set of code points kept as sorted, disjoint, inclusive ranges. */
        private static final class CodeSet {

            private final java.util.TreeMap<Integer, Integer> ranges = new java.util.TreeMap<>();

            void add(int codePoint) {
                addRange(codePoint, codePoint);
            }

            void addRange(int low, int high) {
                if (low > high) {
                    return;
                }
                Integer start = ranges.floorKey(low);
                if (start != null && ranges.get(start) >= low - 1) {
                    low = start;
                    high = Math.max(high, ranges.remove(start));
                }
                while (true) {
                    Integer next = ranges.ceilingKey(low);
                    if (next == null || next > high + 1) {
                        break;
                    }
                    high = Math.max(high, ranges.remove(next));
                }
                ranges.put(low, high);
            }

            void addAll(CodeSet other) {
                for (Map.Entry<Integer, Integer> range : other.ranges.entrySet()) {
                    addRange(range.getKey(), range.getValue());
                }
            }

            CodeSet copy() {
                CodeSet copy = new CodeSet();
                copy.addAll(this);
                return copy;
            }

            boolean contains(int codePoint) {
                Integer start = ranges.floorKey(codePoint);
                return start != null && ranges.get(start) >= codePoint;
            }

            boolean isEmpty() {
                return ranges.isEmpty();
            }

            /** Remove each of {@code points}, which must be sorted ascending. */
            void subtractPoints(int[] points) {
                for (int point : points) {
                    Integer start = ranges.floorKey(point);
                    if (start == null || ranges.get(start) < point) {
                        continue;
                    }
                    int end = ranges.remove(start);
                    if (start < point) {
                        ranges.put(start, point - 1);
                    }
                    if (point < end) {
                        ranges.put(point + 1, end);
                    }
                }
            }

            void appendTo(StringBuilder out) {
                for (Map.Entry<Integer, Integer> range : ranges.entrySet()) {
                    int low = range.getKey();
                    int high = range.getValue();
                    out.append("\\x{").append(Integer.toHexString(low)).append('}');
                    if (high != low) {
                        out.append("-\\x{").append(Integer.toHexString(high)).append('}');
                    }
                }
            }
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
