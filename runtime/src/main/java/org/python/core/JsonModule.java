package org.python.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * {@code _json}: the accelerators {@code json} takes when the C extension is present.
 *
 * <p>The pieces {@code json.decoder} and {@code json.encoder} reach for by name are here: the scanner
 * that reads one JSON document, the two escapers that write one string, {@code scanstring}, and the
 * encoder {@code json.dumps} keeps as its {@code _iterencode}.
 *
 * <p>The bodies are transliterations of {@code Modules/_json.c}: {@code scanstring_unicode},
 * {@code scanner_new}, {@code scanner_call}, {@code scan_once_unicode}, the object, array, number and
 * constant readers, {@code escape_unicode} and {@code ascii_escape_unicode}. Positions are counted in
 * code points the way {@code PyUnicode_GET_LENGTH} counts them, so a string holding an astral character
 * or a lone surrogate reports the index the reference would, and the text a reader gets back keeps
 * those lone surrogates rather than replacing them. On the writing side, {@code encoder_call} and the
 * {@code encoder_listencode_*} family write into one buffer, with the same indent cache the reference
 * builds: two strings per level, reused as a walk goes deeper.
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

    /** {@code _json.encode_basestring_ascii}, kept so the encoder can recognise it as its fast path. */
    private static final PyNativeFunction ENCODE_BASESTRING_ASCII =
            new PyNativeFunction("encode_basestring_ascii", JsonModule::encodeBasestringAscii)
                    .documented(ENCODE_BASESTRING_ASCII_DOC)
                    .filedWith(PyNativeFunction.METH_O);

    /** {@code _json.encode_basestring}, the other function the encoder short-circuits. */
    private static final PyNativeFunction ENCODE_BASESTRING =
            new PyNativeFunction("encode_basestring", JsonModule::encodeBasestring)
                    .documented(ENCODE_BASESTRING_DOC)
                    .filedWith(PyNativeFunction.METH_O);

    static PyModule newModule() {
        PyModule module = new PyModule("_json");
        module.set("__doc__", PyStr.of(MODULE_DOC));
        PyRuntime.markBuiltIn(module, "_json");
        // The reference's dictionary order: the two escapers first, `scanstring` after them.
        module.set("encode_basestring_ascii", ENCODE_BASESTRING_ASCII);
        module.set("encode_basestring", ENCODE_BASESTRING);
        module.set("scanstring",
                new PyNativeFunction("scanstring", JsonModule::scanstring).documented(SCANSTRING_DOC));
        // `_json_exec` adds the two types after the three method rows, the scanner first.
        module.set("make_scanner", PyType.JSON_SCANNER);
        module.set("make_encoder", PyType.JSON_ENCODER);
        return module;
    }

    // --- the scanner -------------------------------------------------------------------------

    /** The one parameter {@code make_scanner} takes, in the reference's {@code kwlist}. */
    private static final String[] MAKE_SCANNER_PARAMETERS = {"context"};

    /** The two parameters {@code scan_once} takes, in the reference's {@code kwlist}. */
    private static final String[] SCAN_ONCE_PARAMETERS = {"string", "idx"};

    /** The nine parameters {@code make_encoder} takes, in the reference's {@code kwlist}. */
    private static final String[] MAKE_ENCODER_PARAMETERS = {
        "markers", "default", "encoder", "indent",
        "key_separator", "item_separator",
        "sort_keys", "skipkeys", "allow_nan"
    };

    /** The two parameters the encoder's {@code _iterencode} takes. */
    private static final String[] ITERENCODE_PARAMETERS = {"obj", "_current_indent_level"};

    /**
     * {@code scanner_new}: read the six settings off the decoder's context and keep them.
     *
     * <p>Every read is a plain {@code PyObject_GetAttrString}, so a context missing one of the names
     * fails with that attribute's own {@code AttributeError} and nothing is built.
     */
    static PyObject makeScanner(PyObject[] args, String[] kwNames) {
        PyObject context =
                parseArguments("make_scanner", args, kwNames, MAKE_SCANNER_PARAMETERS, "O")[0];
        boolean strict = Slots.truth(Slots.getAttr(context, "strict"));
        return new Scanner(
                strict,
                Slots.getAttr(context, "object_hook"),
                Slots.getAttr(context, "object_pairs_hook"),
                Slots.getAttr(context, "parse_float"),
                Slots.getAttr(context, "parse_int"),
                Slots.getAttr(context, "parse_constant"));
    }

    /**
     * {@code encoder_new}: the nine settings {@code json.encoder} hands over, parsed and kept.
     *
     * <p>{@code markers} is the cycle-detection dictionary or {@code None}; the reference tests it with
     * {@code PyDict_Check} <em>after</em> the whole argument list has parsed, so a bad fifth argument is
     * reported before a bad first one. A dict subclass is a dict here for the same reason, and what is
     * kept is the dictionary it embeds, which is where the markers are written.
     */
    static PyObject makeEncoder(PyObject[] args, String[] kwNames) {
        PyObject[] values = parseArguments(
                "make_encoder", args, kwNames, MAKE_ENCODER_PARAMETERS, "OOOOUUppp");
        PyObject markers = values[0];
        if (markers != PyNone.INSTANCE) {
            if (markers instanceof PyDictInstance instance) {
                markers = instance.entries();
            } else if (!(markers instanceof PyDict)) {
                throw Slots.typeError(
                        "make_encoder() argument 1 must be dict or None, not %.200s",
                        markers.getType().filedName());
            }
        }
        return new Encoder(
                markers,
                values[1],
                values[2],
                values[3],
                values[4],
                values[5],
                Slots.truth(values[6]),
                Slots.truth(values[7]),
                Slots.truth(values[8]));
    }

    /**
     * CPython's {@code _PyErr_FormatNote}: one more line of context on an exception already on its way
     * out, recorded as {@code add_note} would record it.
     *
     * <p>The reference formats the line and calls {@code add_note}, dropping the note if that call fails;
     * a note that cannot be recorded must not replace the error it was describing, so failures here are
     * swallowed too.
     */
    private static void addNote(PyRaise raised, String note) {
        try {
            PyBaseException value = raised.value();
            PyObject found = Slots.findAttr(value, "__notes__");
            PyList notes;
            if (found == null) {
                notes = PyList.empty();
                Slots.setAttr(value, "__notes__", notes);
            } else if (found instanceof PyList list) {
                notes = list;
            } else {
                return;
            }
            notes.add(PyStr.of(note));
        } catch (RuntimeException ignored) {
            // the original error is the one worth reporting
        }
    }

    /**
     * {@code PyArg_ParseTupleAndKeywords} for a row whose parameters are all required.
     *
     * <p>The reference's old parser checks the total first, then fills its parameters in order and
     * reports the first one nothing arrived for, and only then looks at what is left over. That order is
     * visible: {@code scan_once(x=1)} complains that {@code string} is missing rather than that
     * {@code x} is unexpected.
     */
    private static PyObject[] parseArguments(
            String function, PyObject[] args, String[] kwNames, String[] names, String kinds) {
        int most = names.length;
        if (args.length > most) {
            throw Slots.typeError(
                    "%s() takes at most %d argument%s (%d given)",
                    function, most, most == 1 ? "" : "s", args.length);
        }
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        PyObject[] values = new PyObject[most];
        boolean[] used = new boolean[keywords];
        for (int i = 0; i < most; i++) {
            if (i < positional) {
                values[i] = args[i];
            } else {
                for (int k = 0; k < keywords; k++) {
                    if (names[i].equals(kwNames[k])) {
                        values[i] = args[positional + k];
                        used[k] = true;
                        break;
                    }
                }
            }
            if (values[i] == null) {
                throw Slots.typeError(
                        "%s() missing required argument '%s' (pos %d)", function, names[i], i + 1);
            }
            values[i] = convertArgument(function, values[i], kinds.charAt(i), i + 1);
        }
        for (int k = 0; k < keywords; k++) {
            if (!used[k]) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", function, kwNames[k]);
            }
        }
        return values;
    }

    /** The three format units {@code make_encoder} uses: anything, a string, and a truth test. */
    private static PyObject convertArgument(
            String function, PyObject value, char kind, int position) {
        return switch (kind) {
            case 'U' -> {
                if (!(value instanceof PyStr)) {
                    throw Slots.typeError(
                            "%s() argument %d must be str, not %.200s",
                            function, position, Slots.argumentName(value));
                }
                yield value;
            }
            case 'p' -> PyBool.of(Slots.truth(value));
            // `n` is `_PyNumber_Index` and `PyLong_AsSsize_t`, and it converts where it sits so that
            // its refusal comes before anything the parser would say about a later parameter.
            case 'n' -> PyInt.of(Numbers.size(value));
            default -> value;
        };
    }

    /** {@code _json.Scanner}: one decoder's settings and the {@code scan_once} body over them. */
    static final class Scanner extends PyObject {

        final boolean strict;
        final PyObject objectHook;
        final PyObject objectPairsHook;
        final PyObject parseFloat;
        final PyObject parseInt;
        final PyObject parseConstant;

        Scanner(
                boolean strict,
                PyObject objectHook,
                PyObject objectPairsHook,
                PyObject parseFloat,
                PyObject parseInt,
                PyObject parseConstant) {
            super(PyType.JSON_SCANNER);
            this.strict = strict;
            this.objectHook = objectHook;
            this.objectPairsHook = objectPairsHook;
            this.parseFloat = parseFloat;
            this.parseInt = parseInt;
            this.parseConstant = parseConstant;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public boolean isCallable() {
            return true;
        }

        /** {@code scanner_call}: {@code scan_once(string, idx)}, which answers {@code (value, next)}. */
        @Override
        public PyObject call(PyObject[] args, PyTuple kwNames) {
            PyObject[] values = parseArguments(
                    "scan_once", args, PyBoundMethod.keywordNames(kwNames),
                    SCAN_ONCE_PARAMETERS, "On");
            long start = Numbers.size(values[1]);
            if (!(values[0] instanceof PyStr string)) {
                throw Slots.typeError(
                        "first argument must be a string, not %.80s",
                        values[0].getType().filedName());
            }
            int[] text = string.getValue().codePoints().toArray();
            if (start < 0) {
                throw Slots.valueError("idx cannot be negative");
            }
            if (start >= text.length) {
                throw stopIteration(start);
            }
            Cursor next = new Cursor();
            PyObject value = scanOnce(PyDict.empty(), text, (int) start, next);
            return PyTuple.of(value, PyInt.of(next.value));
        }

        /** The reference's default object repr, which quotes {@code tp_name} rather than the bare name. */
        @Override
        public String repr() {
            return "<" + getType().filedName() + " object at 0x"
                    + Long.toHexString(System.identityHashCode(this)) + ">";
        }

        /** {@code scan_once_unicode}. */
        private PyObject scanOnce(PyDict memo, int[] text, int idx, Cursor next) {
            int length = text.length;
            if (idx < 0) {
                throw Slots.valueError("idx cannot be negative");
            }
            if (idx >= length) {
                throw stopIteration(idx);
            }
            switch (text[idx]) {
                case '"': {
                    StringScan scan = scanString(text, idx + 1, strict);
                    next.value = scan.next();
                    return scan.value();
                }
                case '{':
                    return parseObject(memo, text, idx + 1, next);
                case '[':
                    return parseArray(memo, text, idx + 1, next);
                case 'n':
                    if (idx + 3 < length
                            && text[idx + 1] == 'u' && text[idx + 2] == 'l' && text[idx + 3] == 'l') {
                        next.value = idx + 4;
                        return PyNone.INSTANCE;
                    }
                    break;
                case 't':
                    if (idx + 3 < length
                            && text[idx + 1] == 'r' && text[idx + 2] == 'u' && text[idx + 3] == 'e') {
                        next.value = idx + 4;
                        return PyBool.TRUE;
                    }
                    break;
                case 'f':
                    if (idx + 4 < length
                            && text[idx + 1] == 'a' && text[idx + 2] == 'l'
                            && text[idx + 3] == 's' && text[idx + 4] == 'e') {
                        next.value = idx + 5;
                        return PyBool.FALSE;
                    }
                    break;
                case 'N':
                    if (idx + 2 < length && text[idx + 1] == 'a' && text[idx + 2] == 'N') {
                        return parseConstant("NaN", idx, next);
                    }
                    break;
                case 'I':
                    if (idx + 7 < length
                            && text[idx + 1] == 'n' && text[idx + 2] == 'f' && text[idx + 3] == 'i'
                            && text[idx + 4] == 'n' && text[idx + 5] == 'i'
                            && text[idx + 6] == 't' && text[idx + 7] == 'y') {
                        return parseConstant("Infinity", idx, next);
                    }
                    break;
                case '-':
                    if (idx + 8 < length
                            && text[idx + 1] == 'I' && text[idx + 2] == 'n' && text[idx + 3] == 'f'
                            && text[idx + 4] == 'i' && text[idx + 5] == 'n'
                            && text[idx + 6] == 'i' && text[idx + 7] == 't'
                            && text[idx + 8] == 'y') {
                        return parseConstant("-Infinity", idx, next);
                    }
                    break;
                default:
                    break;
            }
            return matchNumber(text, idx, next);
        }

        /** {@code _parse_object_unicode}: a dict, or the list a pairs hook takes, or the hook's answer. */
        private PyObject parseObject(PyDict memo, int[] text, int idx, Cursor next) {
            int end = text.length - 1;
            boolean pairs = objectPairsHook != PyNone.INSTANCE;
            PyObject result = pairs ? PyList.of() : PyDict.empty();
            idx = whitespace(text, idx, end);
            if (idx > end || text[idx] != '}') {
                while (true) {
                    if (idx > end || text[idx] != '"') {
                        throw decodeError(
                                "Expecting property name enclosed in double quotes", text, idx);
                    }
                    StringScan scan = scanString(text, idx + 1, strict);
                    PyObject key = scan.value();
                    PyObject known = memo.get(key);
                    if (known == null) {
                        memo.put(key, key);
                    } else {
                        key = known;
                    }
                    idx = scan.next();
                    idx = whitespace(text, idx, end);
                    if (idx > end || text[idx] != ':') {
                        throw decodeError("Expecting ':' delimiter", text, idx);
                    }
                    idx = whitespace(text, idx + 1, end);
                    Cursor after = new Cursor();
                    PyObject value = scanOnce(memo, text, idx, after);
                    if (pairs) {
                        ((PyList) result).add(PyTuple.of(key, value));
                    } else {
                        ((PyDict) result).put(key, value);
                    }
                    idx = after.value;
                    idx = whitespace(text, idx, end);
                    if (idx <= end && text[idx] == '}') {
                        break;
                    }
                    if (idx > end || text[idx] != ',') {
                        throw decodeError("Expecting ',' delimiter", text, idx);
                    }
                    int comma = idx;
                    idx = whitespace(text, idx + 1, end);
                    if (idx <= end && text[idx] == '}') {
                        throw decodeError("Illegal trailing comma before end of object", text, comma);
                    }
                }
            }
            next.value = idx + 1;
            if (pairs) {
                return Slots.call(objectPairsHook, new PyObject[] {result}, null);
            }
            if (objectHook != PyNone.INSTANCE) {
                return Slots.call(objectHook, new PyObject[] {result}, null);
            }
            return result;
        }

        /** {@code _parse_array_unicode}: always a list, which no hook can change. */
        private PyObject parseArray(PyDict memo, int[] text, int idx, Cursor next) {
            int end = text.length - 1;
            PyList result = PyList.of();
            idx = whitespace(text, idx, end);
            if (idx > end || text[idx] != ']') {
                while (true) {
                    Cursor after = new Cursor();
                    PyObject value = scanOnce(memo, text, idx, after);
                    result.add(value);
                    idx = after.value;
                    idx = whitespace(text, idx, end);
                    if (idx <= end && text[idx] == ']') {
                        break;
                    }
                    if (idx > end || text[idx] != ',') {
                        throw decodeError("Expecting ',' delimiter", text, idx);
                    }
                    int comma = idx;
                    idx = whitespace(text, idx + 1, end);
                    if (idx <= end && text[idx] == ']') {
                        throw decodeError("Illegal trailing comma before end of array", text, comma);
                    }
                }
            }
            if (idx > end || text[idx] != ']') {
                throw decodeError("Expecting value", text, end);
            }
            next.value = idx + 1;
            return result;
        }

        /** {@code _parse_constant}: the hook's answer, and the index just past the constant. */
        private PyObject parseConstant(String constant, int idx, Cursor next) {
            PyObject value = Slots.call(parseConstant, new PyObject[] {PyStr.of(constant)}, null);
            next.value = idx + constant.length();
            return value;
        }

        /** {@code _match_number_unicode}: an int, a float, or whatever the custom hook answers. */
        private PyObject matchNumber(int[] text, int start, Cursor next) {
            int end = text.length - 1;
            int idx = start;
            boolean isFloat = false;
            if (text[idx] == '-') {
                idx++;
                if (idx > end) {
                    throw stopIteration(start);
                }
            }
            if (text[idx] >= '1' && text[idx] <= '9') {
                idx++;
                while (idx <= end && isDigit(text[idx])) {
                    idx++;
                }
            } else if (text[idx] == '0') {
                // A leading zero is the whole integer part: `01` is `0` and then extra data.
                idx++;
            } else {
                throw stopIteration(start);
            }
            if (idx < end && text[idx] == '.' && isDigit(text[idx + 1])) {
                isFloat = true;
                idx += 2;
                while (idx <= end && isDigit(text[idx])) {
                    idx++;
                }
            }
            if (idx < end && (text[idx] == 'e' || text[idx] == 'E')) {
                int exponentStart = idx;
                idx++;
                if (idx < end && (text[idx] == '-' || text[idx] == '+')) {
                    idx++;
                }
                while (idx <= end && isDigit(text[idx])) {
                    idx++;
                }
                if (isDigit(text[idx - 1])) {
                    isFloat = true;
                } else {
                    // The `e` and its sign are not part of the number after all.
                    idx = exponentStart;
                }
            }
            PyObject custom = isFloat
                    ? (parseFloat != PyType.FLOAT ? parseFloat : null)
                    : (parseInt != PyType.INT ? parseInt : null);
            String source = new String(text, start, idx - start);
            PyObject value;
            if (custom != null) {
                value = Slots.call(custom, new PyObject[] {PyStr.of(source)}, null);
            } else if (isFloat) {
                value = PyFloat.of(Double.parseDouble(source));
            } else {
                value = PyInt.of(new java.math.BigInteger(source));
            }
            next.value = idx;
            return value;
        }

        /** {@code IS_WHITESPACE}: space, tab, newline and carriage return, and nothing else. */
        private static int whitespace(int[] text, int idx, int end) {
            while (idx <= end) {
                int c = text[idx];
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    break;
                }
                idx++;
            }
            return idx;
        }

        private static boolean isDigit(int c) {
            return c >= '0' && c <= '9';
        }

        /** {@code raise_stop_iteration}: the index itself is the exception's value. */
        private static PyRaise stopIteration(long idx) {
            // The index has to arrive through the class's initialiser, which is what fills
            // `StopIteration.value`; `json.decoder` reads it back out of that field.
            return PyRaise.of((PyBaseException) Slots.call(
                    ExceptionTypes.STOP_ITERATION, new PyObject[] {PyInt.of(idx)}, null));
        }
    }

    /** The index just past the value a reader stopped at. */
    private static final class Cursor {
        int value;
    }

    /** {@code _json.Encoder}: the settings {@code json.encoder} keeps and the writer over them. */
    static final class Encoder extends PyObject {

        final PyObject markers;
        final PyObject defaultFn;
        final PyObject encoder;
        final PyObject indent;
        final PyObject keySeparator;
        final PyObject itemSeparator;
        final boolean sortKeys;
        final boolean skipKeys;
        final boolean allowNan;

        Encoder(
                PyObject markers,
                PyObject defaultFn,
                PyObject encoder,
                PyObject indent,
                PyObject keySeparator,
                PyObject itemSeparator,
                boolean sortKeys,
                boolean skipKeys,
                boolean allowNan) {
            super(PyType.JSON_ENCODER);
            this.markers = markers;
            this.defaultFn = defaultFn;
            this.encoder = encoder;
            this.indent = indent;
            this.keySeparator = keySeparator;
            this.itemSeparator = itemSeparator;
            this.sortKeys = sortKeys;
            this.skipKeys = skipKeys;
            this.allowNan = allowNan;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public boolean isCallable() {
            return true;
        }

        @Override
        public String repr() {
            return "<" + getType().filedName() + " object at 0x"
                    + Long.toHexString(System.identityHashCode(this)) + ">";
        }

        /**
         * {@code encoder_call}: {@code _iterencode(obj, _current_indent_level)} answers a 1-tuple.
         *
         * <p>The starting level only sizes the indent cache; the walk itself always begins at 0. That is
         * why {@code _iterencode(x, 3)} is the same text as {@code _iterencode(x, 0)} whenever the
         * top-level value is not a container.
         */
        @Override
        public PyObject call(PyObject[] args, PyTuple kwNames) {
            PyObject[] values = parseArguments(
                    "_iterencode", args, PyBoundMethod.keywordNames(kwNames),
                    ITERENCODE_PARAMETERS, "On");
            int initial = (int) Numbers.size(values[1]);
            ArrayList<String> cache = indent == PyNone.INSTANCE ? null : createIndentCache(initial);
            StringBuilder out = new StringBuilder();
            listencodeObj(out, values[0], 0, cache);
            return PyTuple.of(PyStr.of(out.toString()));
        }

        /** {@code encoder_encode_string}: the fast pair when it is one of them, else the callable. */
        private String encodeString(PyStr obj) {
            if (encoder == ENCODE_BASESTRING_ASCII) {
                return asciiEscape(obj.getValue());
            }
            if (encoder == ENCODE_BASESTRING) {
                return escape(obj.getValue());
            }
            PyObject encoded = Slots.call(encoder, new PyObject[] {obj}, null);
            if (!(encoded instanceof PyStr string)) {
                throw Slots.typeError(
                        "encoder() must return a string, not %.80s",
                        encoded.getType().filedName());
            }
            return string.getValue();
        }

        /** {@code encoder_encode_float}: the three spellings the format allows, or the repr. */
        private String encodeFloat(PyFloat obj) {
            double value = obj.getValue();
            if (!Double.isFinite(value)) {
                if (!allowNan) {
                    throw Slots.valueError(
                            "Out of range float values are not JSON compliant: " + obj.repr());
                }
                return value > 0 ? "Infinity" : value < 0 ? "-Infinity" : "NaN";
            }
            return PyFloat.format(value);
        }

        /** {@code _encoded_const}: the three constants JSON has a literal for. */
        private static String encodedConst(PyObject obj) {
            if (obj == PyNone.INSTANCE) {
                return "null";
            }
            if (obj == PyBool.TRUE) {
                return "true";
            }
            if (obj == PyBool.FALSE) {
                return "false";
            }
            throw Slots.valueError("not a const");
        }

        /** {@code encoder_listencode_obj}: one value, in the order the reference dispatches. */
        private void listencodeObj(
                StringBuilder out, PyObject obj, int level, ArrayList<String> cache) {
            if (obj == PyNone.INSTANCE) {
                out.append("null");
            } else if (obj == PyBool.TRUE) {
                out.append("true");
            } else if (obj == PyBool.FALSE) {
                out.append("false");
            } else if (obj instanceof PyStr string) {
                out.append(encodeString(string));
            } else if (obj instanceof PyInt integer) {
                // `PyLong_Type.tp_repr(obj)` in the reference: the int slot, not the class's
                // `__repr__`, so an `IntEnum` member is written as its value.
                out.append(integer.getValue());
            } else if (obj instanceof PyFloat floating) {
                out.append(encodeFloat(floating));
            } else if (obj instanceof PyList list) {
                listencodeList(out, obj, list::size, list::at, level, cache);
            } else if (obj instanceof PyListInstance list) {
                PyList items = list.entries();
                listencodeList(out, obj, items::size, items::at, level, cache);
            } else if (obj instanceof PyTuple tuple) {
                listencodeList(out, obj, tuple::size, tuple::at, level, cache);
            } else if (obj instanceof PyDict dict) {
                listencodeDict(out, obj, dict, true, level, cache);
            } else if (obj instanceof PyDictInstance dict) {
                listencodeDict(out, obj, dict.entries(), false, level, cache);
            } else {
                encodeDefault(out, obj, level, cache);
            }
        }

        /** The tail of {@code encoder_listencode_obj}: remember the object, ask {@code default}. */
        private void encodeDefault(
                StringBuilder out, PyObject obj, int level, ArrayList<String> cache) {
            PyDict memo = markers == PyNone.INSTANCE ? null : (PyDict) markers;
            PyInt ident = memo == null ? null : PyInt.of(System.identityHashCode(obj));
            if (ident != null) {
                if (memo.get(ident) != null) {
                    throw Slots.valueError("Circular reference detected");
                }
                memo.put(ident, obj);
            }
            PyObject replacement = Slots.call(defaultFn, new PyObject[] {obj}, null);
            try {
                listencodeObj(out, replacement, level, cache);
            } catch (PyRaise raised) {
                addNote(raised, "when serializing " + obj.getType().filedName() + " object");
                throw raised;
            }
            if (ident != null) {
                memo.remove(ident);
            }
        }

        /** {@code encoder_listencode_list}. */
        private void listencodeList(
                StringBuilder out,
                PyObject seq,
                IntSupplier size,
                IntFunction<PyObject> at,
                int level,
                ArrayList<String> cache) {
            if (size.getAsInt() == 0) {
                out.append("[]");
                return;
            }
            PyDict memo = markers == PyNone.INSTANCE ? null : (PyDict) markers;
            PyInt ident = null;
            if (memo != null) {
                ident = PyInt.of(System.identityHashCode(seq));
                if (memo.get(ident) != null) {
                    throw Slots.valueError("Circular reference detected");
                }
                memo.put(ident, seq);
            }
            out.append('[');
            String separator = separatorText();
            if (indent != PyNone.INSTANCE) {
                level++;
                separator = getItemSeparator(level, cache);
                writeNewlineIndent(out, level, cache);
            }
            for (int i = 0; i < size.getAsInt(); i++) {
                if (i > 0) {
                    out.append(separator);
                }
                try {
                    listencodeObj(out, at.apply(i), level, cache);
                } catch (PyRaise raised) {
                    addNote(
                            raised,
                            "when serializing "
                                    + seq.getType().filedName()
                                    + " item "
                                    + i);
                    throw raised;
                }
            }
            if (ident != null) {
                memo.remove(ident);
            }
            if (indent != PyNone.INSTANCE) {
                level--;
                writeNewlineIndent(out, level, cache);
            }
            out.append(']');
        }

        /** {@code encoder_listencode_dict}. */
        private void listencodeDict(
                StringBuilder out,
                PyObject dct,
                PyDict entries,
                boolean exact,
                int level,
                ArrayList<String> cache) {
            if (entries.size() == 0) {
                out.append("{}");
                return;
            }
            PyDict memo = markers == PyNone.INSTANCE ? null : (PyDict) markers;
            PyInt ident = null;
            if (memo != null) {
                ident = PyInt.of(System.identityHashCode(dct));
                if (memo.get(ident) != null) {
                    throw Slots.valueError("Circular reference detected");
                }
                memo.put(ident, dct);
            }
            out.append('{');
            String separator = separatorText();
            if (indent != PyNone.INSTANCE) {
                level++;
                separator = getItemSeparator(level, cache);
            }
            boolean[] first = {true};
            if (sortKeys || !exact) {
                // `PyMapping_Items` takes the snapshot whenever the order or the object demands it,
                // and a snapshot is also what makes `sort_keys` work on the pairs rather than on keys.
                PyList items = mappingItems(dct, entries);
                if (sortKeys) {
                    Slots.call(Slots.getAttr(items, "sort"), new PyObject[0], null);
                }
                for (int i = 0; i < items.size(); i++) {
                    PyObject item = items.at(i);
                    if (!(item instanceof PyTuple pair) || pair.size() != 2) {
                        throw Slots.valueError("items must return 2-tuples");
                    }
                    encodeKeyValue(
                            out, first, dct, pair.at(0), pair.at(1), level, cache, separator);
                }
            } else {
                // The reference iterates the live dict; a snapshot keeps a `default` that writes to it
                // from tripping the map's own modification check.
                for (Map.Entry<PyObject, PyObject> entry
                        : new ArrayList<>(entries.getItems().entrySet())) {
                    encodeKeyValue(
                            out, first, dct, entry.getKey(), entry.getValue(), level, cache, separator);
                }
            }
            if (ident != null) {
                memo.remove(ident);
            }
            if (indent != PyNone.INSTANCE && !first[0]) {
                level--;
                writeNewlineIndent(out, level, cache);
            }
            out.append('}');
        }

        /** {@code PyMapping_Items}: an exact dict's pairs, or whatever {@code items()} answers. */
        private PyList mappingItems(PyObject dct, PyDict entries) {
            if (dct instanceof PyDict) {
                PyList items = PyList.of();
                for (Map.Entry<PyObject, PyObject> entry : entries.getItems().entrySet()) {
                    items.add(PyTuple.of(entry.getKey(), entry.getValue()));
                }
                return items;
            }
            PyObject produced = Slots.call(Slots.getAttr(dct, "items"), new PyObject[0], null);
            if (produced instanceof PyList list) {
                return list;
            }
            return (PyList) Constructors.instantiate(PyType.LIST, new PyObject[] {produced}, null);
        }

        /** {@code encoder_encode_key_value}: one pair, refused or skipped by {@code skipkeys}. */
        private void encodeKeyValue(
                StringBuilder out,
                boolean[] first,
                PyObject dct,
                PyObject key,
                PyObject value,
                int level,
                ArrayList<String> cache,
                String separator) {
            PyStr keystr;
            if (key instanceof PyStr string) {
                keystr = string;
            } else if (key instanceof PyFloat floating) {
                keystr = PyStr.of(encodeFloat(floating));
            } else if (key == PyBool.TRUE || key == PyBool.FALSE || key == PyNone.INSTANCE) {
                // Before the int branch: True and False are 1 and 0 as keys, but they spell differently.
                keystr = PyStr.of(encodedConst(key));
            } else if (key instanceof PyInt integer) {
                keystr = PyStr.of(integer.getValue().toString());
            } else if (skipKeys) {
                return;
            } else {
                throw Slots.typeError(
                        "keys must be str, int, float, bool or None, not %.100s",
                        key.getType().filedName());
            }
            if (first[0]) {
                first[0] = false;
                if (indent != PyNone.INSTANCE) {
                    writeNewlineIndent(out, level, cache);
                }
            } else {
                out.append(separator);
            }
            out.append(encodeString(keystr));
            out.append(keySeparatorText());
            try {
                listencodeObj(out, value, level, cache);
            } catch (PyRaise raised) {
                addNote(
                        raised,
                        "when serializing "
                                + dct.getType().filedName()
                                + " item "
                                + key.repr());
                throw raised;
            }
        }

        /** {@code create_indent_cache}: one entry, the newline for the level the caller started at. */
        private ArrayList<String> createIndentCache(int initial) {
            ArrayList<String> cache = new ArrayList<>();
            cache.add("\n" + indentFor(initial));
            return cache;
        }

        /**
         * {@code update_indent_cache}: add the two strings for one level.
         *
         * <p>Even positions are the newline and indent written just inside a bracket, odd positions the
         * item separator followed by that same newline; the cache is extended a level at a time, which is
         * all a walk deeper by one step ever needs.
         */
        private void updateIndentCache(int level, ArrayList<String> cache) {
            String newline = cache.get((level - 1) * 2) + indentText();
            cache.add(separatorText() + newline);
            cache.add(newline);
        }

        /** {@code get_item_separator}. */
        private String getItemSeparator(int level, ArrayList<String> cache) {
            if (level * 2 > cache.size()) {
                updateIndentCache(level, cache);
            }
            return cache.get(level * 2 - 1);
        }

        /** {@code write_newline_indent}. */
        private void writeNewlineIndent(StringBuilder out, int level, ArrayList<String> cache) {
            out.append(cache.get(level * 2));
        }

        /** The indent repeated {@code level} times; a negative level repeats it no times. */
        private String indentFor(int level) {
            return level <= 0 ? "" : indentText().repeat(level);
        }

        /** {@code s->indent} as text: {@code json.encoder} normalises it, so it is a string or None. */
        private String indentText() {
            if (indent instanceof PyStr string) {
                return string.getValue();
            }
            throw Slots.typeError(
                    "can only concatenate str (not \"%s\") to str", indent.getType().filedName());
        }

        private String separatorText() {
            return ((PyStr) itemSeparator).getValue();
        }

        private String keySeparatorText() {
            return ((PyStr) keySeparator).getValue();
        }
    }

    static {
        Members encoder = Members.of(PyType.JSON_ENCODER);
        encoder.addField("markers", "markers", self -> ((Encoder) self).markers);
        encoder.addField("default", "default", self -> ((Encoder) self).defaultFn);
        encoder.addField("encoder", "encoder", self -> ((Encoder) self).encoder);
        encoder.addField("indent", "indent", self -> ((Encoder) self).indent);
        encoder.addField("key_separator", "key_separator", self -> ((Encoder) self).keySeparator);
        encoder.addField("item_separator", "item_separator", self -> ((Encoder) self).itemSeparator);
        encoder.addField("sort_keys", "sort_keys", self -> PyBool.of(((Encoder) self).sortKeys));
        encoder.addField("skipkeys", "skipkeys", self -> PyBool.of(((Encoder) self).skipKeys));
    }

    /** A decoded JSON string and the index just past its closing quote. */
    private record StringScan(PyStr value, int next) {
    }

    static {
        Members scanner = Members.of(PyType.JSON_SCANNER);
        scanner.addField("strict", "strict", self -> PyBool.of(((Scanner) self).strict));
        scanner.addField("object_hook", "object_hook", self -> ((Scanner) self).objectHook);
        scanner.addField("object_pairs_hook", null, self -> ((Scanner) self).objectPairsHook);
        scanner.addField("parse_float", "parse_float", self -> ((Scanner) self).parseFloat);
        scanner.addField("parse_int", "parse_int", self -> ((Scanner) self).parseInt);
        scanner.addField("parse_constant", "parse_constant", self -> ((Scanner) self).parseConstant);
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
        StringScan scan = scanString(text, end, strict);
        return PyTuple.of(scan.value(), PyInt.of(scan.next()));
    }

    /** The string behind a closing quote, and the index just past it. */
    private static StringScan scanString(int[] text, int end, boolean strict) {
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
                return new StringScan(PyStr.of(new String(text, end, next - end)), next + 1);
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
        return new StringScan(PyStr.of(out.text()), end);
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
