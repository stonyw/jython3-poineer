package org.python.core;

import java.util.Arrays;

/**
 * {@code _json}: the three string-level accelerators {@code json} takes when the C extension is present.
 *
 * <p>Only the pieces {@code json.decoder} and {@code json.encoder} reach for by name are here: the
 * scanner that reads one JSON string, and the two escapers that write one. {@code make_scanner},
 * {@code make_encoder} and their two types are not built yet, so {@code json} keeps using its Python
 * scanner and encoder while the two escapers and {@code scanstring} come from this module.
 *
 * <p>The bodies are transliterations of {@code Modules/_json.c}: {@code scanstring_unicode},
 * {@code escape_unicode} and {@code ascii_escape_unicode}. Positions are counted in code points the way
 * {@code PyUnicode_GET_LENGTH} counts them, so a string holding an astral character or a lone surrogate
 * reports the index the reference would, and the text a reader gets back keeps those lone surrogates
 * rather than replacing them.
 */
final class JsonModule {

    private JsonModule() {
    }

    private static final String MODULE_DOC = "json speedups\n";

    private static final String SCANSTRING_DOC = """
            scanstring(string, end, strict=True) -> (string, end)

            Scan the string s for a JSON string. End is the index of the
            character in s after the quote that started the JSON string.
            Unescapes all valid JSON string escape sequences and raises ValueError
            on attempt to decode an invalid string. If strict is False then literal
            control characters are allowed in the string.

            Returns a tuple of the decoded string and the index of the character in s
            after the end quote.\
            """;

    private static final String ENCODE_BASESTRING_ASCII_DOC = """
            encode_basestring_ascii(string) -> string

            Return an ASCII-only JSON representation of a Python string\
            """;

    private static final String ENCODE_BASESTRING_DOC = """
            encode_basestring(string) -> string

            Return a JSON representation of a Python string\
            """;

    /** {@code Py_hexdigits}, which is lowercase in the reference. */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static PyModule newModule() {
        PyModule module = new PyModule("_json");
        module.set("__doc__", PyStr.of(MODULE_DOC));
        PyRuntime.markBuiltIn(module, "_json");
        // The reference's dictionary order: the two escapers first, `scanstring` after them.
        module.set("encode_basestring_ascii",
                new PyNativeFunction("encode_basestring_ascii", JsonModule::encodeBasestringAscii)
                        .documented(ENCODE_BASESTRING_ASCII_DOC)
                        .filedWith(PyNativeFunction.METH_O));
        module.set("encode_basestring",
                new PyNativeFunction("encode_basestring", JsonModule::encodeBasestring)
                        .documented(ENCODE_BASESTRING_DOC)
                        .filedWith(PyNativeFunction.METH_O));
        module.set("scanstring",
                new PyNativeFunction("scanstring", JsonModule::scanstring).documented(SCANSTRING_DOC));
        return module;
    }

    /**
     * {@code py_scanstring}: {@code PyArg_ParseTuple(args, "On|p:scanstring")} and then
     * {@code scanstring_unicode}.
     */
    private static PyObject scanstring(PyObject[] args, String[] kwNames) {
        // A legacy `PyArg_ParseTuple` body is a METH_VARARGS row, so the call machinery never hands it a
        // keyword — but a call that gives one still has to be refused in the vectorcall's words.
        Arguments.noKeywords("scanstring()", kwNames);
        if (args.length < 2) {
            throw Slots.typeError("scanstring() takes at least 2 arguments (%d given)", args.length);
        }
        if (args.length > 3) {
            throw Slots.typeError("scanstring() takes at most 3 arguments (%d given)", args.length);
        }
        long end = Numbers.size(args[1]);
        if (!(args[0] instanceof PyStr string)) {
            throw Slots.typeError(
                    "first argument must be a string, not %.80s", args[0].getType().filedName());
        }
        boolean strict = args.length < 3 || Slots.truth(args[2]);
        int[] text = string.getValue().codePoints().toArray();
        if (end < 0 || text.length < end) {
            throw Slots.valueError("end is out of bounds");
        }
        return scan(text, (int) end, strict);
    }

    /** {@code scanstring_unicode}, over the code points of one {@code str}. */
    private static PyObject scan(int[] text, int end, boolean strict) {
        int begin = end - 1;
        CodePoints out = new CodePoints();
        while (true) {
            // Find the end of the string or the next escape. `c` is the character the scan stopped on,
            // or 0 when it ran off the end, which is how the reference distinguishes the two.
            int next = end;
            int c = 0;
            for (; next < text.length; next++) {
                c = text[next];
                if (c == '"' || c == '\\') {
                    break;
                }
                if (c <= 0x1f && strict) {
                    throw decodeError("Invalid control character at", text, next);
                }
            }

            if (c == '"' && out.isEmpty()) {
                // Fast path for the simple case: no escape has been seen, so the answer is the slice.
                return PyTuple.of(PyStr.of(new String(text, end, next - end)), PyInt.of(next + 1));
            }
            if (c != '"' && c != '\\') {
                throw decodeError("Unterminated string starting at", text, begin);
            }

            // Pick up this chunk if it is not zero length.
            if (next != end) {
                out.addAll(text, end, next);
            }
            next++;
            if (c == '"') {
                end = next;
                break;
            }
            if (next == text.length) {
                throw decodeError("Unterminated string starting at", text, begin);
            }
            c = text[next];
            if (c != 'u') {
                // Non-unicode backslash escapes.
                end = next + 1;
                switch (c) {
                    case '"', '\\', '/' -> {
                    }
                    case 'b' -> c = '\b';
                    case 'f' -> c = '\f';
                    case 'n' -> c = '\n';
                    case 'r' -> c = '\r';
                    case 't' -> c = '\t';
                    default -> c = 0;
                }
                if (c == 0) {
                    throw decodeError("Invalid \\escape", text, end - 2);
                }
            } else {
                c = 0;
                next++;
                end = next + 4;
                if (end > text.length) {
                    throw decodeError("Invalid \\uXXXX escape", text, next - 1);
                }
                // Decode the four hex digits.
                for (; next < end; next++) {
                    int digit = text[next];
                    c <<= 4;
                    if (digit >= '0' && digit <= '9') {
                        c |= digit - '0';
                    } else if (digit >= 'a' && digit <= 'f') {
                        c |= digit - 'a' + 10;
                    } else if (digit >= 'A' && digit <= 'F') {
                        c |= digit - 'A' + 10;
                    } else {
                        throw decodeError("Invalid \\uXXXX escape", text, end - 5);
                    }
                }
                // A high surrogate followed by a second unicode escape is joined only when that escape
                // is a low surrogate; otherwise the six characters it looked at are read as text.
                if (isHighSurrogate(c) && end + 6 < text.length
                        && text[next++] == '\\' && text[next++] == 'u') {
                    int c2 = 0;
                    end += 6;
                    for (; next < end; next++) {
                        int digit = text[next];
                        c2 <<= 4;
                        if (digit >= '0' && digit <= '9') {
                            c2 |= digit - '0';
                        } else if (digit >= 'a' && digit <= 'f') {
                            c2 |= digit - 'a' + 10;
                        } else if (digit >= 'A' && digit <= 'F') {
                            c2 |= digit - 'A' + 10;
                        } else {
                            throw decodeError("Invalid \\uXXXX escape", text, end - 5);
                        }
                    }
                    if (isLowSurrogate(c2)) {
                        c = joinSurrogates(c, c2);
                    } else {
                        end -= 6;
                    }
                }
            }
            out.add(c);
        }
        return PyTuple.of(PyStr.of(out.text()), PyInt.of(end));
    }

    /** {@code py_encode_basestring}: {@code escape_unicode}, a METH_O row. */
    private static PyObject encodeBasestring(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("_json.encode_basestring()", args, kwNames);
        return PyStr.of(escape(text(args[0])));
    }

    /** {@code py_encode_basestring_ascii}: {@code ascii_escape_unicode}, a METH_O row. */
    private static PyObject encodeBasestringAscii(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("_json.encode_basestring_ascii()", args, kwNames);
        return PyStr.of(asciiEscape(text(args[0])));
    }

    /** {@code escape_unicode}: a JSON string that escapes only the seven short forms and the C0 range. */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int c : value.codePoints().toArray()) {
            switch (c) {
                case '\\', '"' -> out.append('\\').append((char) c);
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c <= 0x1f) {
                        out.append("\\u00").append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else {
                        // Every other code point, an astral one and a lone surrogate included, is
                        // copied through: `maxchar` is the input's own widest character.
                        out.appendCodePoint(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** {@code ascii_escape_unicode}: a JSON string whose text is ASCII only. */
    private static String asciiEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int c : value.codePoints().toArray()) {
            if (c >= ' ' && c <= '~' && c != '\\' && c != '"') {
                out.append((char) c);
            } else {
                escapeUnichar(c, out);
            }
        }
        return out.append('"').toString();
    }

    /** {@code ascii_escape_unichar}: the two- or six- or twelve-character form of one code point. */
    private static void escapeUnichar(int c, StringBuilder out) {
        out.append('\\');
        switch (c) {
            case '\\', '"' -> out.append((char) c);
            case '\b' -> out.append('b');
            case '\f' -> out.append('f');
            case '\n' -> out.append('n');
            case '\r' -> out.append('r');
            case '\t' -> out.append('t');
            default -> {
                if (c >= 0x10000) {
                    // UTF-16 surrogate pair, written as two escapes of six characters each.
                    int high = 0xD800 + ((c - 0x10000) >> 10);
                    out.append('u').append(hex4(high)).append('\\');
                    c = 0xDC00 + ((c - 0x10000) & 0x3ff);
                }
                out.append('u').append(hex4(c));
            }
        }
    }

    private static String hex4(int c) {
        char[] digits = {HEX[(c >> 12) & 0xf], HEX[(c >> 8) & 0xf], HEX[(c >> 4) & 0xf], HEX[c & 0xf]};
        return new String(digits);
    }

    /** The `O` half of the two escapers: a `str`, or the reference's one-sentence refusal. */
    private static String text(PyObject value) {
        if (!(value instanceof PyStr string)) {
            throw Slots.typeError(
                    "first argument must be a string, not %.80s", value.getType().filedName());
        }
        return string.getValue();
    }

    /**
     * {@code raise_errmsg}: the {@code JSONDecodeError} {@code json.decoder} defines, called with the
     * message, the document and the character offset.
     *
     * <p>The class computes its own line, column and {@code str()}, so the error is the class's call
     * rather than a {@code ValueError} built here — which is why the import is done at this point and,
     * as in the reference, a failure to import is the error a caller sees.
     */
    private static PyRaise decodeError(String message, int[] text, int position) {
        PyObject decoder = PyRuntime.importModule("json.decoder");
        PyObject errorType = Slots.getAttr(decoder, "JSONDecodeError");
        PyObject document = PyStr.of(new String(text, 0, text.length));
        PyObject raised = Slots.call(errorType,
                new PyObject[] {PyStr.of(message), document, PyInt.of(position)}, null);
        return PyRaise.of((PyBaseException) raised);
    }

    private static boolean isHighSurrogate(int c) {
        return c >= 0xD800 && c <= 0xDBFF;
    }

    private static boolean isLowSurrogate(int c) {
        return c >= 0xDC00 && c <= 0xDFFF;
    }

    private static int joinSurrogates(int high, int low) {
        return 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
    }

    /**
     * The code points the scan has written so far.
     *
     * <p>An {@code int[]} rather than a {@code StringBuilder} because the reference's indices are code
     * points: an astral character is one of them, and a lone surrogate is one of them too, and both
     * have to come back out as the same text they came in as.
     */
    private static final class CodePoints {

        private int[] data = new int[16];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void add(int codePoint) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = codePoint;
        }

        void addAll(int[] source, int from, int to) {
            int count = to - from;
            if (size + count > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, size + count));
            }
            System.arraycopy(source, from, data, size, count);
            size += count;
        }

        String text() {
            return new String(data, 0, size);
        }
    }
}
