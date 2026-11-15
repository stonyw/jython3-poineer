package org.python.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code pyexpat} module: an expat-shaped reader built on this runtime's own scanner.
 *
 * <p>The reference {@code pyexpat} is a binding to expat, and expat is a C library; neither is here.
 * What is here is the callback surface the standard library actually leans on — {@code ParserCreate},
 * {@code Parse}/{@code ParseFile}, the twenty-two handlers, {@code ordered_attributes},
 * {@code buffer_text}, the four position readouts and the error table — with a scanner underneath
 * that is incremental in the same way expat's is: a token is handed to the handlers as soon as it is
 * complete, and an incomplete tail waits for the next {@code Parse}.
 *
 * <p>Three things expat alone answers are not modelled, and {@code version_info} says {@code (2, 5, 0)}
 * so that a caller can tell. Reparse deferral was added in 2.6, so a parser here never defers a token
 * it has already seen; the DTD declaration callbacks fire for {@code <!ENTITY>} only, not for element,
 * attribute-list or notation declarations; and the external-entity machinery stops at the handler,
 * without a second parser reading the referenced file.
 *
 * <p>The error text is the reference's own table, and the positions are this scanner's. They agree
 * with expat where the scanner can see what expat sees — the position of an unclosed token, of a
 * mismatched end tag, of an undefined entity — and differ where expat's wording depends on its own
 * internal buffering, which no token stream exposes.
 */
final class PyexpatModule {

    private static final String SOURCE = "/org/python/core/pyexpat.py";

    private static final AtomicLong NEXT = new AtomicLong(1);

    private static final Map<Long, State> STATES = new ConcurrentHashMap<>();

    private static PyModule module;

    private PyexpatModule() {
    }

    static synchronized PyModule install() {
        if (module != null) {
            return module;
        }
        if (!PyRuntime.hasSourceCompiler()) {
            throw Slots.notImplemented("pyexpat needs a compiler (setSourceCompiler)");
        }
        PyModule built = PyRuntime.namespace(new PyModule("pyexpat"));
        PyRuntime.markBuiltIn(built, "pyexpat");
        PyDict globals = built.dictionary();
        nativeFunction(globals, "_new", "ParserCreate", PyexpatModule::parserNew);
        nativeFunction(globals, "_parse", "xmlparser.Parse", PyexpatModule::parse);
        nativeFunction(globals, "_parse_file", "xmlparser.ParseFile", PyexpatModule::parseFile);
        nativeFunction(globals, "_info", "xmlparser.info", PyexpatModule::info);
        nativeFunction(globals, "_get_base", "xmlparser.GetBase", PyexpatModule::getBase);
        nativeFunction(globals, "_set_base", "xmlparser.SetBase", PyexpatModule::setBase);
        nativeFunction(globals, "_set_activation_threshold",
                "xmlparser.SetBillionLaughsAttackProtectionActivationThreshold",
                PyexpatModule::setActivationThreshold);
        nativeFunction(globals, "_set_max_amplification",
                "xmlparser.SetBillionLaughsAttackProtectionMaximumAmplification",
                PyexpatModule::setMaxAmplification);
        PyRuntime.runIn(PyRuntime.compileSource(source(), "<pyexpat>"), globals, globals);
        built.set("__name__", PyStr.interned("pyexpat"));
        built.set("ExpatError", ExceptionTypes.EXPAT_ERROR);
        built.set("error", ExceptionTypes.EXPAT_ERROR);
        built.set("errors", errorsModule());
        built.set("model", modelModule());
        built.set("ErrorString", PyNativeFunction.positional("ErrorString", PyexpatModule::errorString));
        PyRuntime.registerBuiltInModule("pyexpat", built);
        module = built;
        return built;
    }

    private static void nativeFunction(
            PyDict globals,
            String injected,
            String name,
            java.util.function.Function<PyObject[], PyObject> body) {
        globals.put(PyStr.interned("_jy3" + injected),
                PyNativeFunction.positional(name, body));
    }

    /**
     * expat's table in full, index for index.
     *
     * <p>{@code errors.messages} is this array with the null at zero left out, and
     * {@code errors.codes} is it backwards; a code outside the array answers {@code None} exactly as
     * the reference does, which is why {@code ErrorString(0)} and {@code ErrorString(99)} are both
     * {@code None} rather than an exception.
     */
    private static final String[] MESSAGES = {
            null, // 0
            "out of memory", // 1
            "syntax error", // 2
            "no element found", // 3
            "not well-formed (invalid token)", // 4
            "unclosed token", // 5
            "partial character", // 6
            "mismatched tag", // 7
            "duplicate attribute", // 8
            "junk after document element", // 9
            "illegal parameter entity reference", // 10
            "undefined entity", // 11
            "recursive entity reference", // 12
            "asynchronous entity", // 13
            "reference to invalid character number", // 14
            "reference to binary entity", // 15
            "reference to external entity in attribute", // 16
            "XML or text declaration not at start of entity", // 17
            "unknown encoding", // 18
            "encoding specified in XML declaration is incorrect", // 19
            "unclosed CDATA section", // 20
            "error in processing external entity reference", // 21
            "document is not standalone", // 22
            "unexpected parser state - please send a bug report", // 23
            "entity declared in parameter entity", // 24
            "requested feature requires XML_DTD support in Expat", // 25
            "cannot change setting once parsing has begun", // 26
            "unbound prefix", // 27
            "must not undeclare prefix", // 28
            "incomplete markup in parameter entity", // 29
            "XML declaration not well-formed", // 30
            "text declaration not well-formed", // 31
            "illegal character(s) in public id", // 32
            "parser suspended", // 33
            "parser not suspended", // 34
            "parsing aborted", // 35
            "parsing finished", // 36
            "cannot suspend in external parameter entity", // 37
            "reserved prefix (xml) must not be undeclared or bound to another namespace name", // 38
            "reserved prefix (xmlns) must not be declared or undeclared", // 39
            "prefix must not be bound to one of the reserved namespace names", // 40
            "invalid argument", // 41
            "a successful prior call to function XML_GetBuffer is required", // 42
            "limit on input amplification factor (from DTD and entities) breached", // 43
            "parser not started", // 44
    };

    /**
     * The constant each message is filed under in {@code pyexpat.errors}.
     *
     * <p>They are expat's own {@code XML_Error} enumerator names, which do not follow from the
     * message text ({@code "syntax error"} is {@code XML_ERROR_SYNTAX}, not
     * {@code XML_ERROR_SYNTAX_ERROR}), so they are written out beside {@link #MESSAGES}.
     */
    private static final String[] NAMES = {
            null, // 0
            "XML_ERROR_NO_MEMORY",
            "XML_ERROR_SYNTAX",
            "XML_ERROR_NO_ELEMENTS",
            "XML_ERROR_INVALID_TOKEN",
            "XML_ERROR_UNCLOSED_TOKEN",
            "XML_ERROR_PARTIAL_CHAR",
            "XML_ERROR_TAG_MISMATCH",
            "XML_ERROR_DUPLICATE_ATTRIBUTE",
            "XML_ERROR_JUNK_AFTER_DOC_ELEMENT",
            "XML_ERROR_PARAM_ENTITY_REF",
            "XML_ERROR_UNDEFINED_ENTITY",
            "XML_ERROR_RECURSIVE_ENTITY_REF",
            "XML_ERROR_ASYNC_ENTITY",
            "XML_ERROR_BAD_CHAR_REF",
            "XML_ERROR_BINARY_ENTITY_REF",
            "XML_ERROR_ATTRIBUTE_EXTERNAL_ENTITY_REF",
            "XML_ERROR_MISPLACED_XML_PI",
            "XML_ERROR_UNKNOWN_ENCODING",
            "XML_ERROR_INCORRECT_ENCODING",
            "XML_ERROR_UNCLOSED_CDATA_SECTION",
            "XML_ERROR_EXTERNAL_ENTITY_HANDLING",
            "XML_ERROR_NOT_STANDALONE",
            "XML_ERROR_UNEXPECTED_STATE",
            "XML_ERROR_ENTITY_DECLARED_IN_PE",
            "XML_ERROR_FEATURE_REQUIRES_XML_DTD",
            "XML_ERROR_CANT_CHANGE_FEATURE_ONCE_PARSING",
            "XML_ERROR_UNBOUND_PREFIX",
            "XML_ERROR_UNDECLARING_PREFIX",
            "XML_ERROR_INCOMPLETE_PE",
            "XML_ERROR_XML_DECL",
            "XML_ERROR_TEXT_DECL",
            "XML_ERROR_PUBLICID",
            "XML_ERROR_SUSPENDED",
            "XML_ERROR_NOT_SUSPENDED",
            "XML_ERROR_ABORTED",
            "XML_ERROR_FINISHED",
            "XML_ERROR_SUSPEND_PE",
            "XML_ERROR_RESERVED_PREFIX_XML",
            "XML_ERROR_RESERVED_PREFIX_XMLNS",
            "XML_ERROR_RESERVED_NAMESPACE_URI",
            "XML_ERROR_INVALID_ARGUMENT",
            "XML_ERROR_NO_BUFFER",
            "XML_ERROR_AMPLIFICATION_LIMIT_BREACH",
            "XML_ERROR_NOT_STARTED",
    };

    /** {@code errors}: the two tables and the message-name constants the standard library reads. */
    private static PyModule errorsModule() {
        PyModule errors = new PyModule("pyexpat.errors");
        PyDict codes = PyDict.empty();
        PyDict messages = PyDict.empty();
        for (int code = 1; code < MESSAGES.length; code++) {
            String message = MESSAGES[code];
            codes.put(PyStr.interned(message), PyInt.of(code));
            messages.put(PyInt.of(code), PyStr.interned(message));
            errors.set(NAMES[code], PyStr.interned(message));
        }
        errors.set("codes", codes);
        errors.set("messages", messages);
        return errors;
    }

    /** {@code model}: the content-model constants, which nothing on the ElementTree path reads. */
    private static PyModule modelModule() {
        PyModule model = new PyModule("pyexpat.model");
        model.set("XML_CTYPE_EMPTY", PyInt.of(1));
        model.set("XML_CTYPE_ANY", PyInt.of(2));
        model.set("XML_CTYPE_MIXED", PyInt.of(3));
        model.set("XML_CTYPE_NAME", PyInt.of(4));
        model.set("XML_CTYPE_CHOICE", PyInt.of(5));
        model.set("XML_CTYPE_SEQ", PyInt.of(6));
        model.set("XML_CQUANT_NONE", PyInt.of(0));
        model.set("XML_CQUANT_OPT", PyInt.of(1));
        model.set("XML_CQUANT_REP", PyInt.of(2));
        model.set("XML_CQUANT_PLUS", PyInt.of(3));
        return model;
    }

    private static PyObject errorString(PyObject[] args) {
        if (args.length < 1) {
            throw Slots.typeError("ErrorString() takes exactly one argument (0 given)");
        }
        int code = Numbers.asIndex(args[0]).intValue();
        return code > 0 && code < MESSAGES.length ? PyStr.interned(MESSAGES[code]) : PyNone.INSTANCE;
    }

    // --- the injected functions ---------------------------------------------------------------

    private static PyObject parserNew(PyObject[] args) {
        String encoding = args.length > 0 && args[0] != PyNone.INSTANCE ? text(args[0]) : null;
        String separator = args.length > 1 && args[1] != PyNone.INSTANCE ? text(args[1]) : null;
        boolean root = args.length < 3 || Slots.truth(args[2]);
        State state = new State(encoding, separator);
        state.root = root;
        state.external = !root;
        long id = NEXT.getAndIncrement();
        STATES.put(id, state);
        return PyInt.of(id);
    }

    private static PyObject parse(PyObject[] args) {
        if (args.length < 2) {
            throw Slots.typeError("Parse() takes at least 2 arguments (%d given)", args.length);
        }
        State state = stateOf(args[0]);
        boolean isfinal = args.length > 2 && Slots.truth(args[2]);
        // `Parse(str)` is documented to mean "these are characters, encode them as UTF-8", and the
        // forced encoding only applies to bytes: a parser made for UTF-16 still reads a str as text.
        String forced = state.forcedEncoding;
        boolean text = args[1] instanceof PyStr;
        if (text) {
            state.forcedEncoding = null;
        }
        state.textSource = text;
        try {
            run(state, args[0], octets(args[1]), isfinal, false);
        } finally {
            state.forcedEncoding = forced;
            state.textSource = false;
        }
        return PyInt.of(1);
    }

    private static PyObject parseFile(PyObject[] args) {
        if (args.length < 2) {
            throw Slots.typeError("ParseFile() takes exactly 2 arguments (%d given)", args.length);
        }
        State state = stateOf(args[0]);
        while (true) {
            PyObject chunk = Slots.call(Slots.getAttr(args[1], "read"), new PyObject[0], PyTuple.EMPTY);
            byte[] bytes = octets(chunk);
            if (bytes.length == 0) {
                break;
            }
            run(state, args[0], bytes, false, true);
        }
        run(state, args[0], new byte[0], true, true);
        return PyInt.of(1);
    }

    // --- the amplification switches -----------------------------------------------------------

    private static PyObject setActivationThreshold(PyObject[] args) {
        State state = stateOf(args[0]);
        oneArgument(args, "SetBillionLaughsAttackProtectionActivationThreshold");
        rootOnly(state);
        PyObject value = args[1];
        if (!(value instanceof PyInt) && !(value instanceof PyBool)) {
            throw Slots.typeError("'%s' object cannot be interpreted as an integer",
                    value.getType().filedName());
        }
        BigInteger threshold = Numbers.asIndex(value);
        if (threshold.signum() < 0) {
            throw Slots.valueError("Cannot convert negative int");
        }
        if (threshold.bitLength() > 64) {
            throw PyRaise.raise(ExceptionTypes.OVERFLOW_ERROR,
                    "Python int too large to convert to C unsigned long long");
        }
        state.activationThreshold = threshold;
        return PyNone.INSTANCE;
    }

    private static PyObject setMaxAmplification(PyObject[] args) {
        State state = stateOf(args[0]);
        oneArgument(args, "SetBillionLaughsAttackProtectionMaximumAmplification");
        rootOnly(state);
        double factor = real(args[1]);
        if (Double.isNaN(factor) || factor < 1.0) {
            throw expatError(41, 1, 0, "'max_factor' must be at least 1.0");
        }
        // Infinity is a factor expat accepts: it turns the check off without turning the parser off.
        state.maxAmplification = factor;
        return PyNone.INSTANCE;
    }

    /** {@code PyFloat_AsDouble}: the one conversion expat's {@code "d"} argument format performs. */
    private static double real(PyObject value) {
        if (value instanceof PyFloat number) {
            return number.getValue();
        }
        if (value instanceof PyInt number) {
            return number.getValue().doubleValue();
        }
        if (value instanceof PyBool truth) {
            return truth.getValue() ? 1.0 : 0.0;
        }
        throw Slots.typeError("must be real number, not %s", value.getType().filedName());
    }

    private static void oneArgument(PyObject[] args, String name) {
        if (args.length != 2) {
            throw Slots.typeError("%s() takes exactly one argument (%d given)",
                    name, args.length - 1);
        }
    }

    private static void rootOnly(State state) {
        if (!state.root) {
            throw expatError(41, 1, 0, "parser must be a root parser");
        }
    }

    private static PyObject info(PyObject[] args) {
        State state = stateOf(args[0]);
        String name = text(args[1]);
        return switch (name) {
            case "line" -> PyInt.of(state.eventLine);
            case "column" -> PyInt.of(state.eventColumn);
            case "byte" -> PyInt.of(state.eventByte);
            case "error_line" -> PyInt.of(state.errorLine);
            case "error_column" -> PyInt.of(state.errorColumn);
            case "error_byte" -> PyInt.of(state.errorByte);
            case "error_code" -> PyInt.of(state.errorCode);
            default -> PyNone.INSTANCE;
        };
    }

    private static PyObject getBase(PyObject[] args) {
        State state = stateOf(args[0]);
        return state.base == null ? PyNone.INSTANCE : PyStr.of(state.base);
    }

    private static PyObject setBase(PyObject[] args) {
        State state = stateOf(args[0]);
        state.base = args.length > 1 && args[1] != PyNone.INSTANCE ? text(args[1]) : null;
        return PyNone.INSTANCE;
    }

    private static State stateOf(PyObject parser) {
        PyObject id = Slots.getAttr(parser, "_id");
        State state = STATES.get(Numbers.asIndex(id).longValue());
        if (state == null) {
            throw Slots.valueError("parser has no state");
        }
        return state;
    }

    private static String text(PyObject value) {
        if (value instanceof PyStr str) {
            return str.getValue();
        }
        throw Slots.typeError("expected str, not '%s'", value.getType().filedName());
    }

    private static byte[] octets(PyObject value) {
        if (value instanceof PyStr str) {
            return str.getValue().getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return PyBytes.octets(value);
        }
        if (value instanceof PyMemoryView) {
            PyObject made = Slots.call(Slots.getAttr(value, "tobytes"), new PyObject[0], PyTuple.EMPTY);
            return ((PyBytes) made).getValue();
        }
        throw Slots.typeError("a bytes-like object is required, not '%s'",
                value.getType().filedName());
    }

    private static String source() {
        try (InputStream stream = PyexpatModule.class.getResourceAsStream(SOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the runtime is missing " + SOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }

    // --- one parser's state -------------------------------------------------------------------

    private static final class State {
        String forcedEncoding;
        /** A {@code Parse(str)} call: the characters are already text, so the declaration is ignored. */
        boolean textSource;
        final String separator;

        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        Charset charset;
        boolean charsetChosen;

        final StringBuilder buf = new StringBuilder();
        int pos;

        int line = 1;
        int column;
        long byteIndex;

        int eventLine = 1;
        int eventColumn;
        long eventByte = -1;

        int errorCode;
        int errorLine;
        int errorColumn;
        long errorByte = -1;

        boolean rootSeen;
        boolean rootClosed;
        boolean finished;
        boolean parsing;
        boolean sawContent;
        boolean sawXmlDecl;
        boolean unreadSubset;
        boolean root = true;
        /** An external entity parser reads an entity body, which need not hold an element. */
        boolean external;
        int standalone = -1;
        BigInteger activationThreshold = BigInteger.valueOf(8 * 1024 * 1024);
        double maxAmplification = 100.0;

        final Deque<Element> elements = new ArrayDeque<>();
        final Deque<Map<String, String>> scopes = new ArrayDeque<>();
        final Map<String, String> entities = new LinkedHashMap<>();
        final Map<String, String> externalEntities = new LinkedHashMap<>();
        final StringBuilder text = new StringBuilder();
        String base;

        State(String forcedEncoding, String separator) {
            this.forcedEncoding = forcedEncoding;
            this.separator = separator;
        }
    }

    private record Element(String rawName, String name, Map<String, String> declared) {
    }

    private record Pos(int line, int column, long byteIndex) {
    }

    // --- feeding and decoding -----------------------------------------------------------------

    private static void run(State state, PyObject parser, byte[] data, boolean isfinal, boolean viaFile) {
        if (state.finished) {
            fail(state, 36, new Pos(state.line, state.column, state.byteIndex));
        }
        if (state.parsing) {
            throw PyRaise.raise(ExceptionTypes.RUNTIME_ERROR,
                    viaFile ? "cannot call ParseFile() from within a handler"
                            : "cannot call Parse() from within a handler");
        }
        state.parsing = true;
        try {
            feed(state, data, isfinal);
            tokenize(state, parser, isfinal);
            flushText(state, parser);
            if (isfinal) {
                finish(state);
            }
        } finally {
            state.parsing = false;
        }
    }

    private static void feed(State state, byte[] data, boolean isfinal) {
        if (data.length > 0) {
            state.raw.write(data, 0, data.length);
        }
        if (!state.charsetChosen) {
            if (!chooseCharset(state, isfinal)) {
                return;
            }
            state.charsetChosen = true;
        }
        byte[] all = state.raw.toByteArray();
        state.raw.reset();
        if (all.length == 0) {
            return;
        }
        CharsetDecoder decoder = state.charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(all);
        StringBuilder decoded = new StringBuilder(all.length);
        CharBuffer out = CharBuffer.allocate(Math.max(16, all.length * 2 + 16));
        while (true) {
            CoderResult result = decoder.decode(in, out, isfinal);
            out.flip();
            decoded.append(out);
            out.clear();
            if (result.isOverflow()) {
                continue;
            }
            if (result.isError()) {
                fail(state, 6, new Pos(state.line, state.column, state.byteIndex));
            }
            break;
        }
        String text = decoded.toString();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        state.buf.append(text);
        if (in.hasRemaining()) {
            if (isfinal) {
                fail(state, 6, new Pos(state.line, state.column, state.byteIndex));
            }
            byte[] tail = new byte[in.remaining()];
            in.get(tail);
            state.raw.write(tail, 0, tail.length);
        }
    }

    private static boolean chooseCharset(State state, boolean isfinal) {
        if (state.forcedEncoding != null) {
            state.charset = charsetFor(state.forcedEncoding);
            if (state.charset == null) {
                fail(state, 18, new Pos(1, 0, 0));
            }
            if (state.charset == StandardCharsets.UTF_16) {
                // A bare `utf-16` is two encodings, and the BOM is what picks one. Reading the
                // stream without it would make a fresh decoder guess big-endian for every chunk it
                // is handed, because each `Parse` call starts a new decoder here.
                byte[] raw = state.raw.toByteArray();
                if (raw.length < 2 && !isfinal) {
                    return false;
                }
                state.charset = raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE
                        ? StandardCharsets.UTF_16LE
                        : StandardCharsets.UTF_16BE;
            }
            return true;
        }
        byte[] raw = state.raw.toByteArray();
        if (raw.length == 0) {
            if (!isfinal) {
                return false;
            }
            state.charset = StandardCharsets.UTF_8;
            return true;
        }
        int first = raw[0] & 0xFF;
        int second = raw.length > 1 ? raw[1] & 0xFF : -1;
        // The four byte orders expat sniffs for. A lone 0x00 is not one of them: the leading byte
        // only says UTF-16 when it is the high half of a `<`, which is how both endiannesses start.
        boolean maybeUtf16 = first == 0xFE || first == 0xFF
                || (first == 0x3C && (second == 0 || second == -1))
                || (first == 0x00 && (second == 0x3C || second == -1));
        if (raw.length < 2 && !isfinal && maybeUtf16) {
            return false;
        }
        if (first == 0xFE) {
            state.charset = StandardCharsets.UTF_16BE;
        } else if (first == 0xFF) {
            state.charset = StandardCharsets.UTF_16LE;
        } else if (first == 0x00 && second == 0x3C) {
            state.charset = StandardCharsets.UTF_16BE;
        } else if (first == 0x3C && second == 0x00) {
            state.charset = StandardCharsets.UTF_16LE;
        } else {
            state.charset = StandardCharsets.UTF_8;
        }
        if (first == 0x3C && !state.textSource) {
            String head = new String(raw, 0, Math.min(raw.length, 1024), StandardCharsets.ISO_8859_1);
            if (head.startsWith("<?xml")) {
                int end = head.indexOf("?>");
                if (end < 0 && !isfinal) {
                    return false;
                }
                if (end > 0) {
                    String declared = declaredEncoding(head.substring(0, end));
                    if (declared != null) {
                        Charset chosen = charsetFor(declared);
                        if (chosen == null) {
                            state.charset = StandardCharsets.UTF_8;
                            fail(state, 18, new Pos(1, 0, 0));
                        }
                        state.charset = chosen;
                    }
                }
            }
        }
        return true;
    }

    private static String declaredEncoding(String declaration) {
        int at = declaration.indexOf("encoding");
        if (at < 0) {
            return null;
        }
        int i = at + "encoding".length();
        while (i < declaration.length() && Character.isWhitespace(declaration.charAt(i))) {
            i++;
        }
        if (i >= declaration.length() || declaration.charAt(i) != '=') {
            return null;
        }
        i++;
        while (i < declaration.length() && Character.isWhitespace(declaration.charAt(i))) {
            i++;
        }
        if (i >= declaration.length() || (declaration.charAt(i) != '"' && declaration.charAt(i) != '\'')) {
            return null;
        }
        char quote = declaration.charAt(i++);
        int start = i;
        while (i < declaration.length() && declaration.charAt(i) != quote) {
            i++;
        }
        return i < declaration.length() ? declaration.substring(start, i) : null;
    }

    /** expat accepts ASCII-compatible names only; a Python codec that is not one is unknown here. */
    private static Charset charsetFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        switch (lower) {
            case "utf-8", "utf8", "u8", "ascii", "us-ascii":
                return StandardCharsets.UTF_8;
            case "latin-1", "latin1", "iso-8859-1", "iso8859-1", "8859", "iso8859", "iso-8859",
                    "l1", "iso-ir-100", "csisolatin1", "ibm819", "cp819":
                return StandardCharsets.ISO_8859_1;
            case "utf-16", "utf16":
                return StandardCharsets.UTF_16;
            case "utf-16-le", "utf-16le":
                return StandardCharsets.UTF_16LE;
            case "utf-16-be", "utf-16be":
                return StandardCharsets.UTF_16BE;
            case "cp1250":
            case "cp1251":
            case "cp1252":
            case "cp1253":
            case "cp1254":
            case "cp1255":
            case "cp1256":
            case "cp1257":
            case "cp1258":
                lower = "windows-" + lower.substring(2);
                break;
            default:
                break;
        }
        try {
            return Charset.forName(lower);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- positions ----------------------------------------------------------------------------

    private static Pos pos(State state) {
        return new Pos(state.line, state.column, state.byteIndex);
    }

    private static Pos posAt(State state, CharSequence token, int offset) {
        int line = state.line;
        int column = state.column;
        for (int i = 0; i < offset && i < token.length(); ) {
            char c = token.charAt(i);
            if (c == '\n') {
                line++;
                column = 0;
                i++;
            } else if (c == '\r') {
                line++;
                column = 0;
                if (i + 1 < offset && token.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
            } else {
                column++;
                i++;
            }
        }
        String consumed = token.subSequence(0, Math.min(offset, token.length())).toString();
        long bytes = consumed.getBytes(state.charset == null ? StandardCharsets.UTF_8 : state.charset).length;
        return new Pos(line, column, state.byteIndex + bytes);
    }

    private static void advance(State state, int chars) {
        int end = state.pos + chars;
        for (int i = state.pos; i < end; ) {
            char c = state.buf.charAt(i);
            if (c == '\n') {
                state.line++;
                state.column = 0;
                i++;
            } else if (c == '\r') {
                state.line++;
                state.column = 0;
                if (i + 1 < end && state.buf.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
            } else {
                state.column++;
                i++;
            }
        }
        String consumed = state.buf.substring(state.pos, end);
        state.byteIndex += consumed.getBytes(
                state.charset == null ? StandardCharsets.UTF_8 : state.charset).length;
        state.pos = end;
    }

    private static void event(State state, Pos at) {
        state.eventLine = at.line;
        state.eventColumn = at.column;
        state.eventByte = at.byteIndex;
    }

    private static void fail(State state, int code, Pos at) {
        state.errorCode = code;
        state.errorLine = at.line;
        state.errorColumn = at.column;
        state.errorByte = at.byteIndex;
        state.eventLine = at.line;
        state.eventColumn = at.column;
        state.eventByte = at.byteIndex;
        throw expatError(code, at.line, at.column);
    }

    private static PyRaise expatError(int code, int line, int column) {
        return expatError(code, line, column, MESSAGES[code]);
    }

    /**
     * The three fields expat files beside the message.
     *
     * <p>{@code code} is the {@code XML_Error}, {@code lineno} the 1-based line and {@code offset}
     * the 0-based column, all written into the instance dictionary rather than declared as slots,
     * which is why an {@code ExpatError("text")} made by hand has none of them.
     */
    private static PyRaise expatError(int code, int line, int column, String message) {
        String text = message + ": line " + line + ", column " + column;
        PyBaseException exception =
                new PyBaseException(ExceptionTypes.EXPAT_ERROR, new PyObject[] {PyStr.of(text)});
        exception.setAttr("code", PyInt.of(code));
        exception.setAttr("lineno", PyInt.of(line));
        exception.setAttr("offset", PyInt.of(column));
        return PyRaise.of(exception);
    }

    // --- the scanner --------------------------------------------------------------------------

    private static void tokenize(State state, PyObject parser, boolean isfinal) {
        while (state.pos < state.buf.length()) {
            char c = state.buf.charAt(state.pos);
            if (c != '<') {
                if (!textRun(state, parser, isfinal)) {
                    break;
                }
                continue;
            }
            if (state.buf.length() - state.pos < 2) {
                if (!isfinal) {
                    break;
                }
                fail(state, 5, pos(state));
            }
            char next = state.buf.charAt(state.pos + 1);
            boolean complete;
            if (next == '/') {
                complete = endTag(state, parser, isfinal);
            } else if (next == '!') {
                complete = bang(state, parser, isfinal);
            } else if (next == '?') {
                complete = processingInstruction(state, parser, isfinal, pos(state));
            } else {
                complete = startTag(state, parser, isfinal);
            }
            if (!complete) {
                break;
            }
        }
        if (state.pos > 0) {
            state.buf.delete(0, state.pos);
            state.pos = 0;
        }
    }

    /** {@code >} that closes the tag starting at {@link State#pos}, or {@code -1} when incomplete. */
    private static int scanToGt(State state) {
        char quote = 0;
        for (int i = state.pos + 1; i < state.buf.length(); i++) {
            char c = state.buf.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static boolean textRun(State state, PyObject parser, boolean isfinal) {
        int at = indexOf(state.buf, '<', state.pos);
        int end = at < 0 ? state.buf.length() : at;
        if (at < 0 && !isfinal) {
            int amp = lastUnpairedAmp(state.buf, state.pos, end);
            if (amp >= 0) {
                end = amp;
            }
        }
        if (end <= state.pos) {
            return end > state.pos;
        }
        String run = state.buf.substring(state.pos, end);
        checkChars(state, run);
        Pos start = pos(state);
        if (state.elements.isEmpty()) {
            int k = 0;
            while (k < run.length() && isSpace(run.charAt(k))) {
                k++;
            }
            if (k < run.length()) {
                char first = run.charAt(k);
                int code = first == '&' ? 4 : state.rootClosed ? 9 : 2;
                fail(state, code, posAt(state, run, k));
            }
            advance(state, run.length());
            return true;
        }
        StringBuilder expanded = expand(state, parser, run, start, false);
        if (expanded.length() > 0) {
            state.text.append(expanded);
        }
        advance(state, run.length());
        return true;
    }

    private static int lastUnpairedAmp(CharSequence chars, int from, int to) {
        int last = -1;
        for (int i = to - 1; i >= from; i--) {
            char c = chars.charAt(i);
            if (c == ';') {
                return -1;
            }
            if (c == '&') {
                last = i;
                break;
            }
        }
        return last;
    }

    private static String normalize(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                out.append('\n');
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean startTag(State state, PyObject parser, boolean isfinal) {
        Pos tokenPos = pos(state);
        int end = scanToGt(state);
        if (end < 0) {
            if (isfinal) {
                fail(state, 5, tokenPos);
            }
            return false;
        }
        String token = state.buf.substring(state.pos, end + 1);
        checkChars(state, token);
        if (state.rootClosed && !state.external) {
            fail(state, 9, tokenPos);
        }
        int limit = token.length() - 1;
        int i = 1;
        if (i >= limit || !isNameStart(token.charAt(i))) {
            fail(state, 4, posAt(state, token, i));
        }
        int nameStart = i;
        while (i < limit && isNameChar(token.charAt(i))) {
            i++;
        }
        String rawName = token.substring(nameStart, i);
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Integer> nameOffsets = new ArrayList<>();
        List<Integer> valueOffsets = new ArrayList<>();
        boolean empty = false;
        while (true) {
            while (i < limit && isSpace(token.charAt(i))) {
                i++;
            }
            if (i >= limit) {
                break;
            }
            if (token.charAt(i) == '/') {
                empty = true;
                i++;
                if (i != limit) {
                    fail(state, 4, posAt(state, token, i));
                }
                break;
            }
            int attributeStart = i;
            if (!isNameStart(token.charAt(i))) {
                fail(state, 4, posAt(state, token, i));
            }
            while (i < limit && isNameChar(token.charAt(i))) {
                i++;
            }
            String name = token.substring(attributeStart, i);
            while (i < limit && isSpace(token.charAt(i))) {
                i++;
            }
            if (i >= limit || token.charAt(i) != '=') {
                fail(state, 4, posAt(state, token, i));
            }
            i++;
            while (i < limit && isSpace(token.charAt(i))) {
                i++;
            }
            if (i >= limit || (token.charAt(i) != '"' && token.charAt(i) != '\'')) {
                fail(state, 4, posAt(state, token, i));
            }
            char quote = token.charAt(i++);
            int valueStart = i;
            while (i < limit && token.charAt(i) != quote) {
                if (token.charAt(i) == '<') {
                    fail(state, 4, posAt(state, token, i));
                }
                i++;
            }
            if (i >= limit) {
                if (isfinal) {
                    fail(state, 5, tokenPos);
                }
                return false;
            }
            String value = token.substring(valueStart, i);
            i++;
            for (String seen : names) {
                if (seen.equals(name)) {
                    fail(state, 8, posAt(state, token, attributeStart));
                }
            }
            names.add(name);
            values.add(value);
            nameOffsets.add(attributeStart);
            valueOffsets.add(valueStart);
        }
        event(state, tokenPos);
        Map<String, String> declared = new LinkedHashMap<>();
        // Namespace declarations apply to the element they are written on whatever their position
        // among the attributes, so every `xmlns` is read before the element's own prefix is expanded.
        if (state.separator != null) {
            for (int k = 0; k < names.size(); k++) {
                String attribute = names.get(k);
                if (!attribute.equals("xmlns") && !attribute.startsWith("xmlns:")) {
                    continue;
                }
                String value = expandAttribute(state, parser, values.get(k), token, valueOffsets.get(k));
                if (attribute.equals("xmlns")) {
                    declared.put("", value.isEmpty() ? null : value);
                    continue;
                }
                String prefix = attribute.substring(6);
                if (prefix.equals("xmlns")) {
                    fail(state, 39, tokenPos);
                }
                if (prefix.equals("xml")) {
                    if (!value.equals("http://www.w3.org/XML/1998/namespace")) {
                        fail(state, 38, tokenPos);
                    }
                    continue;
                }
                if (value.isEmpty()) {
                    fail(state, 28, tokenPos);
                }
                declared.put(prefix, value);
            }
        }
        String name = qualify(state, parser, rawName, tokenPos, token, nameStart, declared, false);
        List<String> qualified = new ArrayList<>();
        List<String> qualifiedValues = new ArrayList<>();
        for (int k = 0; k < names.size(); k++) {
            String attribute = names.get(k);
            if (state.separator != null
                    && (attribute.equals("xmlns") || attribute.startsWith("xmlns:"))) {
                continue;
            }
            String value = expandAttribute(state, parser, values.get(k), token, valueOffsets.get(k));
            qualified.add(qualify(state, parser, attribute, tokenPos, token, nameOffsets.get(k),
                    declared, true));
            qualifiedValues.add(value);
        }
        state.scopes.push(declared);
        dispatchNamespaceStart(state, parser, declared);
        Element element = new Element(rawName, name, declared);
        state.elements.push(element);
        state.rootSeen = true;
        state.sawContent = true;
        call(state, parser, "StartElementHandler", PyStr.interned(name),
                attributeValue(state, parser, qualified, qualifiedValues));
        if (empty) {
            Pos after = posAt(state, token, token.length());
            event(state, after);
            state.elements.pop();
            call(state, parser, "EndElementHandler", PyStr.interned(name));
            dispatchNamespaceEnd(state, parser, declared);
            state.scopes.pop();
            state.rootClosed = state.elements.isEmpty();
        }
        advance(state, token.length());
        return true;
    }

    private static PyObject attributeValue(
            State state, PyObject parser, List<String> names, List<String> values) {
        if (flag(parser, "ordered_attributes")) {
            PyList list = PyList.of();
            for (int i = 0; i < names.size(); i++) {
                list.add(PyStr.interned(names.get(i)));
                list.add(PyStr.of(values.get(i)));
            }
            return list;
        }
        PyDict dict = PyDict.empty();
        for (int i = 0; i < names.size(); i++) {
            dict.put(PyStr.interned(names.get(i)), PyStr.of(values.get(i)));
        }
        return dict;
    }

    /**
     * The name the handlers see: expat expands a prefix to {@code uri + separator + local}, and appends
     * the prefix a second time when {@code namespace_prefixes} is on — {@code uri!local!prefix}.
     */
    private static String qualify(
            State state,
            PyObject parser,
            String rawName,
            Pos tokenPos,
            String token,
            int offset,
            Map<String, String> declared,
            boolean attribute) {
        if (state.separator == null) {
            return rawName;
        }
        int colon = rawName.indexOf(':');
        if (colon < 0) {
            if (attribute) {
                return rawName;
            }
            String uri = lookup(state, declared, "");
            return uri == null ? rawName : uri + state.separator + rawName;
        }
        String prefix = rawName.substring(0, colon);
        String local = rawName.substring(colon + 1);
        if (prefix.isEmpty() || local.isEmpty()) {
            fail(state, 4, posAt(state, token, offset + colon));
        }
        String uri = lookup(state, declared, prefix);
        if (uri == null) {
            fail(state, 27, tokenPos);
        }
        if (flag(parser, "namespace_prefixes")) {
            return uri + state.separator + local + state.separator + prefix;
        }
        return uri + state.separator + local;
    }

    private static String lookup(State state, Map<String, String> declared, String prefix) {
        String found = declared.get(prefix);
        if (found != null || declared.containsKey(prefix)) {
            return found;
        }
        for (Map<String, String> scope : state.scopes) {
            if (scope.containsKey(prefix)) {
                return scope.get(prefix);
            }
        }
        // The two prefixes the XML recommendation binds before any declaration is read.
        if (prefix.equals("xml")) {
            return "http://www.w3.org/XML/1998/namespace";
        }
        if (prefix.equals("xmlns")) {
            return "http://www.w3.org/2000/xmlns/";
        }
        return null;
    }

    /** One of the four switches expat keeps beside the callbacks. */
    private static boolean flag(PyObject parser, String name) {
        PyObject value = Slots.findAttr(parser, name);
        return value != null && Slots.truth(value);
    }

    private static void dispatchNamespaceStart(
            State state, PyObject parser, Map<String, String> declared) {
        if (declared.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            PyObject prefix = entry.getKey().isEmpty() ? PyNone.INSTANCE : PyStr.interned(entry.getKey());
            PyObject uri = entry.getValue() == null ? PyNone.INSTANCE : PyStr.of(entry.getValue());
            call(state, parser, "StartNamespaceDeclHandler", prefix, uri);
        }
    }

    private static void dispatchNamespaceEnd(
            State state, PyObject parser, Map<String, String> declared) {
        if (declared.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>(declared.keySet());
        for (int i = names.size() - 1; i >= 0; i--) {
            PyObject prefix = names.get(i).isEmpty() ? PyNone.INSTANCE : PyStr.interned(names.get(i));
            call(state, parser, "EndNamespaceDeclHandler", prefix);
        }
    }

    private static boolean endTag(State state, PyObject parser, boolean isfinal) {
        Pos tokenPos = pos(state);
        int end = scanToGt(state);
        if (end < 0) {
            if (isfinal) {
                fail(state, 5, tokenPos);
            }
            return false;
        }
        String token = state.buf.substring(state.pos, end + 1);
        checkChars(state, token);
        int limit = token.length() - 1;
        int i = 2;
        int nameStart = i;
        if (i >= limit || !isNameStart(token.charAt(i))) {
            fail(state, 4, posAt(state, token, i));
        }
        while (i < limit && isNameChar(token.charAt(i))) {
            i++;
        }
        String rawName = token.substring(nameStart, i);
        while (i < limit && isSpace(token.charAt(i))) {
            i++;
        }
        if (i != limit) {
            fail(state, 4, posAt(state, token, i));
        }
        Element open = state.elements.peek();
        if (open == null) {
            fail(state, state.rootClosed ? 9 : 4, posAt(state, token, nameStart));
        }
        Map<String, String> declared = new java.util.LinkedHashMap<>();
        String name = qualify(state, parser, rawName, tokenPos, token, nameStart, declared, false);
        if (!name.equals(open.name())) {
            fail(state, 7, posAt(state, token, nameStart));
        }
        event(state, tokenPos);
        state.elements.pop();
        state.scopes.pop();
        state.rootClosed = state.elements.isEmpty();
        state.sawContent = true;
        call(state, parser, "EndElementHandler", PyStr.interned(name));
        dispatchNamespaceEnd(state, parser, open.declared());
        advance(state, token.length());
        return true;
    }

    private static boolean bang(State state, PyObject parser, boolean isfinal) {
        Pos tokenPos = pos(state);
        if (startsWith(state, "<!--")) {
            return comment(state, parser, isfinal, tokenPos);
        }
        if (startsWith(state, "<![CDATA[")) {
            return cdata(state, parser, isfinal, tokenPos);
        }
        if (startsWith(state, "<!DOCTYPE")) {
            return doctype(state, parser, isfinal, tokenPos);
        }
        // An external entity parser reads an entity body, where a bare markup declaration is the
        // content: `SubParser.Parse('<!ENTITY ext SYSTEM "entity.file">')` is how a caller feeds a
        // DTD piece into the reader itself.
        if (state.external && (startsWith(state, "<!ENTITY") || startsWith(state, "<!ELEMENT")
                || startsWith(state, "<!ATTLIST") || startsWith(state, "<!NOTATION"))) {
            int end = scanToGt(state);
            if (end < 0) {
                if (isfinal) {
                    fail(state, 5, tokenPos);
                }
                return false;
            }
            String declaration = state.buf.substring(state.pos, end + 1);
            readSubset(state, declaration);
            advance(state, declaration.length());
            return true;
        }
        // An incomplete `<!` waits for the next chunk; anything else is not well-formed.
        if (!isfinal && state.buf.length() - state.pos < 9) {
            return false;
        }
        fail(state, 4, tokenPos);
        return false;
    }

    private static boolean comment(State state, PyObject parser, boolean isfinal, Pos tokenPos) {
        int end = indexOf(state.buf, "-->", state.pos + 4);
        if (end < 0) {
            if (isfinal) {
                fail(state, 5, tokenPos);
            }
            return false;
        }
        String content = state.buf.substring(state.pos + 4, end);
        checkChars(state, state.buf.subSequence(state.pos, end));
        event(state, tokenPos);
        state.sawContent = true;
        call(state, parser, "CommentHandler", PyStr.of(content));
        advance(state, end + 3 - state.pos);
        return true;
    }

    private static boolean cdata(State state, PyObject parser, boolean isfinal, Pos tokenPos) {
        int start = state.pos + 9;
        int end = indexOf(state.buf, "]]>", start);
        if (end < 0) {
            if (isfinal) {
                fail(state, 20, pos(state));
            }
            return false;
        }
        String content = state.buf.substring(start, end);
        String token = state.buf.substring(state.pos, end + 3);
        checkChars(state, token);
        event(state, posAt(state, token, 9));
        state.sawContent = true;
        flushText(state, parser);
        call(state, parser, "StartCdataSectionHandler");
        call(state, parser, "CharacterDataHandler", PyStr.of(content));
        call(state, parser, "EndCdataSectionHandler");
        advance(state, end + 3 - state.pos);
        return true;
    }

    private static boolean processingInstruction(
            State state, PyObject parser, boolean isfinal, Pos tokenPos) {
        int end = indexOf(state.buf, "?>", state.pos + 2);
        if (end < 0) {
            if (isfinal) {
                fail(state, 5, tokenPos);
            }
            return false;
        }
        String token = state.buf.substring(state.pos, end + 2);
        checkChars(state, token);
        int i = 2;
        int nameStart = i;
        while (i < token.length() - 2 && isNameChar(token.charAt(i))) {
            i++;
        }
        String target = token.substring(nameStart, i);
        if (target.isEmpty() || !isNameStart(target.charAt(0))) {
            fail(state, 4, posAt(state, token, nameStart));
        }
        String data = token.substring(i, token.length() - 2);
        int space = 0;
        while (space < data.length() && isSpace(data.charAt(space))) {
            space++;
        }
        data = data.substring(space);
        if (target.equals("xml")) {
            return xmlDeclaration(state, parser, token, tokenPos, isfinal);
        }
        if (state.sawContent && target.equalsIgnoreCase("xml")) {
            fail(state, 17, tokenPos);
        }
        event(state, tokenPos);
        state.sawContent = true;
        flushText(state, parser);
        call(state, parser, "ProcessingInstructionHandler",
                PyStr.interned(target), PyStr.of(data));
        advance(state, token.length());
        return true;
    }

    private static boolean xmlDeclaration(
            State state, PyObject parser, String token, Pos tokenPos, boolean isfinal) {
        if (state.sawContent || state.sawXmlDecl) {
            fail(state, 17, tokenPos);
        }
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        int i = token.indexOf("xml") + 3;
        int limit = token.length() - 2;
        while (i < limit) {
            while (i < limit && isSpace(token.charAt(i))) {
                i++;
            }
            if (i >= limit) {
                break;
            }
            int nameStart = i;
            while (i < limit && isNameChar(token.charAt(i))) {
                i++;
            }
            String name = token.substring(nameStart, i);
            while (i < limit && isSpace(token.charAt(i))) {
                i++;
            }
            if (i >= limit || token.charAt(i) != '=') {
                fail(state, 30, posAt(state, token, nameStart));
            }
            i++;
            while (i < limit && isSpace(token.charAt(i))) {
                i++;
            }
            if (i >= limit || (token.charAt(i) != '"' && token.charAt(i) != '\'')) {
                fail(state, 30, posAt(state, token, i));
            }
            char quote = token.charAt(i++);
            int valueStart = i;
            while (i < limit && token.charAt(i) != quote) {
                i++;
            }
            if (i >= limit) {
                fail(state, 30, posAt(state, token, valueStart));
            }
            attributes.put(name, token.substring(valueStart, i));
            i++;
        }
        if (!attributes.containsKey("version")) {
            int at = token.indexOf(' ') + 1;
            fail(state, 30, posAt(state, token, at < 1 ? 5 : at));
        }
        String standalone = attributes.get("standalone");
        int flag = standalone == null ? -1 : standalone.equals("yes") ? 1 : standalone.equals("no") ? 0 : -2;
        if (flag == -2) {
            fail(state, 30, tokenPos);
        }
        state.sawXmlDecl = true;
        state.standalone = flag;
        event(state, tokenPos);
        PyObject encoding = attributes.containsKey("encoding")
                ? PyStr.of(attributes.get("encoding")) : PyNone.INSTANCE;
        call(state, parser, "XmlDeclHandler",
                PyStr.of(attributes.getOrDefault("version", "1.0")), encoding, PyInt.of(flag));
        advance(state, token.length());
        return true;
    }

    private static boolean doctype(State state, PyObject parser, boolean isfinal, Pos tokenPos) {
        int depth = 0;
        char quote = 0;
        int end = -1;
        for (int i = state.pos + 9; i < state.buf.length(); i++) {
            char c = state.buf.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth == 0) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            if (isfinal) {
                fail(state, 5, tokenPos);
            }
            return false;
        }
        String token = state.buf.substring(state.pos, end + 1);
        int i = 9;
        while (i < token.length() && isSpace(token.charAt(i))) {
            i++;
        }
        int nameStart = i;
        while (i < token.length() && isNameChar(token.charAt(i))) {
            i++;
        }
        String name = token.substring(nameStart, i);
        String systemId = null;
        String publicId = null;
        int bracket = token.indexOf('[');
        int stop = bracket < 0 ? token.length() - 1 : bracket;
        while (i < stop) {
            while (i < stop && isSpace(token.charAt(i))) {
                i++;
            }
            if (i >= stop) {
                break;
            }
            if (token.startsWith("SYSTEM", i)) {
                i += 6;
                systemId = quoted(token, i);
                i = skipQuoted(token, i);
            } else if (token.startsWith("PUBLIC", i)) {
                i += 6;
                publicId = quoted(token, i);
                i = skipQuoted(token, i);
                systemId = quoted(token, i);
                i = skipQuoted(token, i);
            } else {
                i++;
            }
        }
        boolean hasInternalSubset = bracket >= 0;
        if (hasInternalSubset) {
            int close = token.lastIndexOf(']');
            String subset = token.substring(bracket + 1, close < 0 ? token.length() : close);
            readSubset(state, subset);
        }
        if (systemId != null) {
            state.unreadSubset = true;
        }
        event(state, tokenPos);
        state.sawContent = true;
        flushText(state, parser);
        call(state, parser, "StartDoctypeDeclHandler",
                PyStr.interned(name),
                systemId == null ? PyNone.INSTANCE : PyStr.of(systemId),
                publicId == null ? PyNone.INSTANCE : PyStr.of(publicId),
                PyInt.of(hasInternalSubset ? 1 : 0));
        call(state, parser, "EndDoctypeDeclHandler");
        advance(state, token.length());
        return true;
    }

    /** The internal declarations this reader understands: general entities, internal and external. */
    private static void readSubset(State state, String subset) {
        int at = 0;
        while (true) {
            int start = subset.indexOf("<!ENTITY", at);
            if (start < 0) {
                break;
            }
            int close = subset.indexOf('>', start);
            if (close < 0) {
                break;
            }
            String declaration = subset.substring(start + 8, close).trim();
            at = close + 1;
            boolean parameter = declaration.startsWith("%");
            if (parameter) {
                declaration = declaration.substring(1).trim();
            }
            int space = 0;
            while (space < declaration.length() && !Character.isWhitespace(declaration.charAt(space))) {
                space++;
            }
            String name = declaration.substring(0, space);
            String rest = declaration.substring(space).trim();
            if (parameter) {
                if (rest.startsWith("SYSTEM") || rest.startsWith("PUBLIC")) {
                    state.unreadSubset = true;
                }
                continue;
            }
            if (rest.startsWith("SYSTEM")) {
                String system = quoted(rest, 6);
                state.externalEntities.put(name, system == null ? "" : system);
                continue;
            }
            if (rest.startsWith("PUBLIC")) {
                String system = quoted(rest, skipQuoted(rest, 6));
                state.externalEntities.put(name, system == null ? "" : system);
                continue;
            }
            if (rest.startsWith("\"") || rest.startsWith("'")) {
                char quote = rest.charAt(0);
                int endQuote = rest.indexOf(quote, 1);
                if (endQuote > 0) {
                    state.entities.put(name, rest.substring(1, endQuote));
                }
            }
        }
        int percent = 0;
        while (true) {
            int ref = subset.indexOf('%', percent);
            if (ref < 0) {
                break;
            }
            int semicolon = subset.indexOf(';', ref);
            if (semicolon < 0) {
                break;
            }
            percent = semicolon + 1;
            state.unreadSubset = true;
        }
    }

    private static String quoted(String token, int at) {
        int i = at;
        while (i < token.length() && isSpace(token.charAt(i))) {
            i++;
        }
        if (i >= token.length() || (token.charAt(i) != '"' && token.charAt(i) != '\'')) {
            return null;
        }
        char quote = token.charAt(i);
        int end = token.indexOf(quote, i + 1);
        return end < 0 ? null : token.substring(i + 1, end);
    }

    private static int skipQuoted(String token, int at) {
        int i = at;
        while (i < token.length() && isSpace(token.charAt(i))) {
            i++;
        }
        if (i >= token.length() || (token.charAt(i) != '"' && token.charAt(i) != '\'')) {
            return i;
        }
        char quote = token.charAt(i);
        int end = token.indexOf(quote, i + 1);
        return end < 0 ? token.length() : end + 1;
    }

    // --- entity expansion ---------------------------------------------------------------------

    private static StringBuilder expand(
            State state, PyObject parser, String run, Pos start, boolean attribute) {
        StringBuilder out = new StringBuilder(run.length());
        expandInto(state, parser, run, start, attribute, out, 0);
        return out;
    }

    private static void expandInto(
            State state,
            PyObject parser,
            String run,
            Pos start,
            boolean attribute,
            StringBuilder out,
            int depth) {
        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            if (c != '&') {
                if (attribute && c == '<') {
                    fail(state, 4, posAt(state, run, i));
                }
                out.append(c);
                continue;
            }
            int semicolon = run.indexOf(';', i + 1);
            if (semicolon < 0) {
                fail(state, 4, posAt(state, run, i));
            }
            String reference = run.substring(i + 1, semicolon);
            Pos at = posAt(state, run, i);
            i = semicolon;
            if (reference.startsWith("#")) {
                out.appendCodePoint(characterReference(state, reference, at));
                continue;
            }
            switch (reference) {
                case "lt" -> out.append('<');
                case "gt" -> out.append('>');
                case "amp" -> out.append('&');
                case "apos" -> out.append('\'');
                case "quot" -> out.append('"');
                default -> {
                    if (state.externalEntities.containsKey(reference)) {
                        if (attribute) {
                            fail(state, 16, at);
                        }
                        externalEntity(state, parser, reference, at);
                    } else if (state.entities.containsKey(reference)) {
                        if (depth > 8) {
                            fail(state, 12, at);
                        }
                        expandInto(state, parser, state.entities.get(reference), at, attribute, out,
                                depth + 1);
                    } else if (state.unreadSubset) {
                        skippedEntity(state, parser, reference, at);
                    } else {
                        fail(state, 11, at);
                    }
                }
            }
        }
    }

    private static int characterReference(State state, String reference, Pos at) {
        int value;
        try {
            if (reference.startsWith("#x") || reference.startsWith("#X")) {
                value = Integer.parseInt(reference.substring(2), 16);
            } else {
                value = Integer.parseInt(reference.substring(1), 10);
            }
        } catch (NumberFormatException e) {
            fail(state, 4, at);
            return 0;
        }
        if (value == 0 || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
            fail(state, 14, at);
        }
        return value;
    }

    private static void externalEntity(State state, PyObject parser, String name, Pos at) {
        String system = state.externalEntities.get(name);
        // expat's first argument to the handler is the entity name, not the parser context, and the
        // third is the system identifier the declaration named.
        PyObject handler = handler(parser, "ExternalEntityRefHandler");
        if (handler != PyNone.INSTANCE) {
            Slots.call(handler, new PyObject[] {
                    PyStr.interned(name),
                    state.base == null ? PyNone.INSTANCE : PyStr.of(state.base),
                    system == null ? PyNone.INSTANCE : PyStr.of(system),
                    PyNone.INSTANCE}, PyTuple.EMPTY);
            return;
        }
        // No handler means expat cannot read the entity, so the reference is skipped: the default
        // handler sees the raw `&name;` and the document continues, exactly as expat's
        // `XML_DefaultCurrent` does.  The position is recorded as an undefined-entity one because
        // that is what a caller reading `ErrorLineNumber` is asking about.
        state.errorCode = 11;
        state.errorLine = at.line();
        state.errorColumn = at.column();
        state.errorByte = at.byteIndex();
        event(state, at);
        PyObject fallback = handler(parser, "DefaultHandlerExpand");
        if (fallback == PyNone.INSTANCE) {
            fallback = handler(parser, "DefaultHandler");
        }
        if (fallback != PyNone.INSTANCE) {
            Slots.call(fallback, new PyObject[] {PyStr.of("&" + name + ";")}, PyTuple.EMPTY);
        }
    }

    private static void skippedEntity(State state, PyObject parser, String name, Pos at) {
        state.errorCode = 11;
        state.errorLine = at.line();
        state.errorColumn = at.column();
        state.errorByte = at.byteIndex();
        event(state, at);
        PyObject skipped = handler(parser, "SkippedEntityHandler");
        if (skipped != PyNone.INSTANCE) {
            Slots.call(skipped, new PyObject[] {PyStr.interned(name), PyInt.of(0)}, PyTuple.EMPTY);
            return;
        }
        PyObject fallback = handler(parser, "DefaultHandlerExpand");
        if (fallback == PyNone.INSTANCE) {
            fallback = handler(parser, "DefaultHandler");
        }
        if (fallback == PyNone.INSTANCE) {
            throw expatError(11, at.line(), at.column());
        }
        Slots.call(fallback, new PyObject[] {PyStr.of("&" + name + ";")}, PyTuple.EMPTY);
    }

    private static String expandAttribute(
            State state, PyObject parser, String value, String token, int valueOffset) {
        if (value.indexOf('&') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        String normalized = normalize(value);
        StringBuilder out = new StringBuilder(normalized.length());
        expandInto(state, parser, normalized, posAt(state, token, valueOffset), true, out, 0);
        return out.toString();
    }

    // --- text buffering ----------------------------------------------------------------------

    /**
     * Deliver the text accumulated since the last event.
     *
     * <p>With {@code buffer_text} on, the whole run goes to the handler at once. With it off, expat
     * splits the run where the lines end, and the newline is a chunk of its own — that is what
     * {@code "<a>x\r\ny</a>"} turning into {@code 'x', '\n', 'y'} means, and the split is in the
     * reader's contract, not in the text.
     */
    private static void flushText(State state, PyObject parser) {
        if (state.text.length() == 0) {
            return;
        }
        String data = state.text.toString();
        state.text.setLength(0);
        PyObject callback = handler(parser, "CharacterDataHandler");
        if (callback == PyNone.INSTANCE) {
            return;
        }
        if (flag(parser, "buffer_text")) {
            Slots.call(callback, new PyObject[] {PyStr.of(data)}, PyTuple.EMPTY);
            return;
        }
        int at = 0;
        while (at < data.length()) {
            int newline = data.indexOf('\n', at);
            if (newline < 0) {
                Slots.call(callback, new PyObject[] {PyStr.of(data.substring(at))}, PyTuple.EMPTY);
                return;
            }
            if (newline > at) {
                Slots.call(callback,
                        new PyObject[] {PyStr.of(data.substring(at, newline))}, PyTuple.EMPTY);
            }
            Slots.call(callback, new PyObject[] {PyStr.of("\n")}, PyTuple.EMPTY);
            at = newline + 1;
        }
    }

    // --- handlers ----------------------------------------------------------------------------

    private static PyObject handler(PyObject parser, String name) {
        PyObject value = Slots.findAttr(parser, name);
        return value == null ? PyNone.INSTANCE : value;
    }

    private static void call(State state, PyObject parser, String name, PyObject... args) {
        PyObject callback = handler(parser, name);
        if (callback == PyNone.INSTANCE) {
            return;
        }
        flushText(state, parser);
        Slots.call(callback, args, PyTuple.EMPTY);
    }

    // --- finishing ---------------------------------------------------------------------------

    private static void finish(State state) {
        if (state.external) {
            // An entity body is a run of content, not a document: it may hold text and no element
            // at all, which is why an empty external parser returns rather than refusing.
            return;
        }
        if (!state.rootSeen) {
            fail(state, 3, pos(state));
        }
        if (!state.elements.isEmpty()) {
            fail(state, 3, pos(state));
        }
    }

    // --- character classes -------------------------------------------------------------------

    private static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c == ':'
                || (c >= 0xC0 && c <= 0xD6) || (c >= 0xD8 && c <= 0xF6)
                || (c >= 0xF8 && c <= 0x2FF) || (c >= 0x370 && c <= 0x37D)
                || (c >= 0x37F && c <= 0x1FFF) || (c >= 0x200C && c <= 0x200D)
                || (c >= 0x2070 && c <= 0x218F) || (c >= 0x2C00 && c <= 0x2FEF)
                || (c >= 0x3001 && c <= 0xD7FF) || (c >= 0xF900 && c <= 0xFDCF)
                || (c >= 0xFDF0 && c <= 0xFFFD);
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9') || c == '-' || c == '.'
                || c == 0xB7 || (c >= 0x0300 && c <= 0x036F)
                || (c >= 0x203F && c <= 0x2040);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * Refuses the characters XML 1.0 excludes outside a reference.
     *
     * <p>{@code token} begins at the current position, so the reported column is the character's own.
     * Tab, line feed and carriage return survive; the rest of C0 and the two non-characters do not.
     */
    private static void checkChars(State state, CharSequence token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == 0x9 || c == 0xA || c == 0xD) {
                continue;
            }
            if (c < 0x20 || c == 0xFFFE || c == 0xFFFF) {
                fail(state, 4, posAt(state, token, i));
            }
        }
    }

    private static boolean startsWith(State state, String prefix) {
        int available = state.buf.length() - state.pos;
        if (available < prefix.length()) {
            return prefix.startsWith(state.buf.substring(state.pos));
        }
        return state.buf.substring(state.pos, state.pos + prefix.length()).equals(prefix);
    }

    private static int indexOf(CharSequence chars, char c, int from) {
        for (int i = from; i < chars.length(); i++) {
            if (chars.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(CharSequence chars, String needle, int from) {
        int limit = chars.length() - needle.length();
        for (int i = from; i <= limit; i++) {
            boolean match = true;
            for (int k = 0; k < needle.length(); k++) {
                if (chars.charAt(i + k) != needle.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
