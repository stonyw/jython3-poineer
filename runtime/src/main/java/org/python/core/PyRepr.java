package org.python.core;

/**
 * The {@code repr()} rules shared by {@code str} and {@code bytes}.
 *
 * Printability follows Unicode general categories, so this tracks the JDK's bundled Unicode data
 * rather than the version CPython 3.14 pins; that drift is in the plan's risk register.
 */
final class PyRepr {

    private PyRepr() {
    }

    static String quote(String s, String prefix) {
        boolean hasSingle = s.indexOf('\'') >= 0;
        boolean hasDouble = s.indexOf('"') >= 0;
        char quote = hasSingle && !hasDouble ? '"' : '\'';
        StringBuilder out = new StringBuilder(s.length() + 4);
        out.append(prefix).append(quote);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (cp == quote) {
                        out.append('\\').append((char) cp);
                    } else if (isPrintable(cp)) {
                        out.appendCodePoint(cp);
                    } else if (cp < 0x100) {
                        out.append("\\x").append(hex(cp, 2));
                    } else if (cp < 0x10000) {
                        out.append("\\u").append(hex(cp, 4));
                    } else {
                        out.append("\\U").append(hex(cp, 8));
                    }
                }
            }
        }
        return out.append(quote).toString();
    }

    /**
     * {@code bytes} repr. Bytes at or above 0x80 are always escaped, so this cannot reuse the
     * string path by mapping a byte to a Latin-1 code point — 0xC0 would render as a printable.
     */
    static String quoteBytes(byte[] bytes) {
        boolean hasSingle = false;
        boolean hasDouble = false;
        for (byte b : bytes) {
            if (b == '\'') {
                hasSingle = true;
            } else if (b == '"') {
                hasDouble = true;
            }
        }
        char quote = hasSingle && !hasDouble ? '"' : '\'';
        StringBuilder out = new StringBuilder(bytes.length + 4);
        out.append('b').append(quote);
        for (byte b : bytes) {
            int v = b & 0xff;
            if (v == '\\') {
                out.append("\\\\");
            } else if (v == '\n') {
                out.append("\\n");
            } else if (v == '\r') {
                out.append("\\r");
            } else if (v == '\t') {
                out.append("\\t");
            } else if (v == quote) {
                out.append('\\').append((char) v);
            } else if (v >= 0x20 && v < 0x7f) {
                out.append((char) v);
            } else {
                out.append("\\x").append(hex(v, 2));
            }
        }
        return out.append(quote).toString();
    }

    /** CPython's {@code str.isprintable()}: everything except the C*, Z* (but space) and Cs ranges. */
    private static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL,
                 Character.FORMAT,
                 Character.SURROGATE,
                 Character.PRIVATE_USE,
                 Character.UNASSIGNED,
                 Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    private static String hex(int cp, int digits) {
        return String.format("%0" + digits + "x", cp);
    }
}
