package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code _csv}: the C half {@code csv.py} is written against.
 *
 * <p>The state machine, the field and record buffers, and every refusal here are the reference's
 * {@code Modules/_csv.c}, sentence for sentence. Three things are worth naming because 3.14 changed
 * them and an older implementation would get them wrong:
 *
 * <ul>
 *   <li>{@code QUOTE_STRINGS} and {@code QUOTE_NOTNULL} are read and written differently from the
 *       other four styles: on reading, an unquoted empty field is {@code None} (and an unquoted
 *       non-empty one is a float under {@code QUOTE_STRINGS}); on writing, the style decides per
 *       field whether quotes are added.</li>
 *   <li>Each line object is treated as ending with a virtual end-of-line character, so an iterable
 *       of lines without terminators still yields one record per line.</li>
 *   <li>A NUL is ordinary field data; the reference no longer reports {@code line contains NUL}.</li>
 * </ul>
 *
 * <p>Dialects are registered in one dictionary owned by the module, exactly as the reference's
 * module state holds them, and a dialect object answers its eight settings read-only.
 */
final class CsvModule {

    private CsvModule() {}

    static final int QUOTE_MINIMAL = 0;
    static final int QUOTE_ALL = 1;
    static final int QUOTE_NONNUMERIC = 2;
    static final int QUOTE_NONE = 3;
    static final int QUOTE_STRINGS = 4;
    static final int QUOTE_NOTNULL = 5;

    /** No character was given: CPython's {@code NOT_SET}, which no code point can collide with. */
    private static final int NOT_SET = -1;

    /**
     * The virtual end of a line object.
     *
     * <p>The reference feeds one of these after the last real character of every line, which is what
     * lets the same state machine end a record whether the line carries a terminator or not.
     */
    private static final int EOL = 0x110000;

    private static final int START_RECORD = 0;
    private static final int START_FIELD = 1;
    private static final int ESCAPED_CHAR = 2;
    private static final int IN_FIELD = 3;
    private static final int IN_QUOTED_FIELD = 4;
    private static final int ESCAPE_IN_QUOTED_FIELD = 5;
    private static final int QUOTE_IN_QUOTED_FIELD = 6;
    private static final int EAT_CRNL = 7;
    private static final int AFTER_ESCAPED_CRNL = 8;

    private static final String[] DIALECT_KEYWORDS =
            {"dialect", "delimiter", "doublequote", "escapechar", "lineterminator", "quotechar",
                "quoting", "skipinitialspace", "strict"};

    /** What {@code field_size_limit} reads and replaces; the reference keeps one per module too. */
    private static long fieldLimit = 128 * 1024;

    /** The named dialects, in registration order. */
    private static final PyDict dialects = PyDict.empty();

    static PyModule newModule() {
        PyModule module = new PyModule("_csv");
        module.set("__doc__", PyStr.of("CSV parsing and writing.\n"));
        PyRuntime.markBuiltIn(module, "_csv");
        module.set("Dialect", PyType.CSV_DIALECT);
        module.set("Reader", PyType.CSV_READER);
        module.set("Writer", PyType.CSV_WRITER);
        module.set("Error", ExceptionTypes.CSV_ERROR);
        module.set("_dialects", dialects);
        module.set("QUOTE_MINIMAL", PyInt.of(QUOTE_MINIMAL));
        module.set("QUOTE_ALL", PyInt.of(QUOTE_ALL));
        module.set("QUOTE_NONNUMERIC", PyInt.of(QUOTE_NONNUMERIC));
        module.set("QUOTE_NONE", PyInt.of(QUOTE_NONE));
        module.set("QUOTE_STRINGS", PyInt.of(QUOTE_STRINGS));
        module.set("QUOTE_NOTNULL", PyInt.of(QUOTE_NOTNULL));
        module.set("reader", new PyNativeFunction("reader", CsvModule::reader));
        module.set("writer", new PyNativeFunction("writer", CsvModule::writer));
        module.set("register_dialect",
                new PyNativeFunction("register_dialect", CsvModule::registerDialect));
        module.set("unregister_dialect",
                new PyNativeFunction("unregister_dialect", CsvModule::unregisterDialect));
        module.set("get_dialect", new PyNativeFunction("get_dialect", CsvModule::getDialect));
        module.set("list_dialects",
                new PyNativeFunction("list_dialects", CsvModule::listDialects));
        module.set("field_size_limit",
                new PyNativeFunction("field_size_limit", CsvModule::fieldSizeLimit));
        return module;
    }

    /** {@code _csv.Error}, in the module's own words. */
    private static PyRaise error(String message) {
        return PyRaise.raise(ExceptionTypes.CSV_ERROR, message);
    }

    private static PyRaise error(String format, Object... arguments) {
        return PyRaise.raise(ExceptionTypes.CSV_ERROR, String.format(format, arguments));
    }

    private static String typeName(PyObject value) {
        return value == PyNone.INSTANCE ? "NoneType" : value.getType().name();
    }

    private static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    /** How many arguments arrived positionally, the keyword tail excluded. */
    private static int positional(PyObject[] args, String[] kwNames) {
        return kwNames == null ? args.length : args.length - kwNames.length;
    }

    // --- module functions ----------------------------------------------------------------

    /**
     * {@code reader(iterable[, dialect='excel'][, **fmtparams])}.
     *
     * <p>The iterable is read at construction time, as {@code PyObject_GetIter} is in the reference:
     * an object that cannot be iterated refuses here, before the dialect is even looked at.
     */
    static PyObject reader(PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        unpackTuple("reader", positional, 1, 2);
        PyObject input = args[0];
        PyObject dialectArgument = positional > 1 ? args[1] : null;
        PyIterator inputIterator = Slots.iterator(input);
        DialectResult dialect = dialect(dialectArgument, kwNames, args, positional);
        return new CsvReader(inputIterator, dialect);
    }

    /** {@code writer(fileobj[, dialect='excel'][, **fmtparams])}. */
    static PyObject writer(PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        unpackTuple("writer", positional, 1, 2);
        PyObject output = args[0];
        PyObject dialectArgument = positional > 1 ? args[1] : null;
        PyObject write = output.getAttr("write");
        if (write == Slots.DECLINED || !Slots.isCallable(write)) {
            throw Slots.typeError("argument 1 must have a \"write\" method");
        }
        DialectResult dialect = dialect(dialectArgument, kwNames, args, positional);
        return new CsvWriter(write, dialect);
    }

    /** {@code register_dialect(name[, dialect[, **fmtparams]])}. */
    static PyObject registerDialect(PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        unpackTuple("register_dialect", positional, 1, 2);
        PyObject name = args[0];
        if (!(name instanceof PyStr)) {
            throw Slots.typeError("dialect name must be a string");
        }
        PyObject dialectArgument = positional > 1 ? args[1] : null;
        DialectResult dialect = dialect(dialectArgument, kwNames, args, positional);
        dialects.put(name, dialect.exposed);
        return PyNone.INSTANCE;
    }

    /** {@code unregister_dialect(name)}. */
    static PyObject unregisterDialect(PyObject[] args, String[] kwNames) {
        PyObject name = oneNamedArgument("unregister_dialect", args, kwNames);
        if (dialects.remove(name) == null) {
            throw error("unknown dialect");
        }
        return PyNone.INSTANCE;
    }

    /** {@code get_dialect(name)}. */
    static PyObject getDialect(PyObject[] args, String[] kwNames) {
        return fromRegistry(oneNamedArgument("get_dialect", args, kwNames));
    }

    /** {@code list_dialects()}: the registered names, in insertion order. */
    static PyObject listDialects(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_csv.list_dialects() takes no keyword arguments");
        }
        if (args.length > 0) {
            throw Slots.typeError("_csv.list_dialects() takes no arguments (%d given)", args.length);
        }
        List<PyObject> names = new ArrayList<>(dialects.getItems().keySet());
        return PyList.of(names.toArray(new PyObject[0]));
    }

    /** {@code field_size_limit([new_limit])}: the previous limit, after setting the new one. */
    static PyObject fieldSizeLimit(PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        if (positional > 1) {
            throw Slots.typeError(
                    "field_size_limit() takes at most 1 argument (%d given)", positional);
        }
        PyObject newLimit = positional == 1 ? args[0] : null;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!"new_limit".equals(kwNames[i])) {
                    throw Slots.typeError(
                            "field_size_limit() got an unexpected keyword argument '%s'",
                            kwNames[i]);
                }
                if (newLimit != null) {
                    throw Slots.typeError(
                            "argument for function given by name ('new_limit') and position (1)");
                }
                newLimit = args[positional + i];
            }
        }
        long old = fieldLimit;
        if (newLimit != null) {
            if (newLimit.getType() != PyType.INT) {
                throw Slots.typeError("limit must be an integer");
            }
            if (!((PyInt) newLimit).fitsLong()) {
                throw PyRaise.raise(
                        ExceptionTypes.OVERFLOW_ERROR,
                        "Python int too large to convert to C ssize_t");
            }
            fieldLimit = ((PyInt) newLimit).getValue().longValue();
        }
        return PyInt.of(old);
    }

    /** One argument, which {@code get_dialect} and {@code unregister_dialect} call {@code name}. */
    private static PyObject oneNamedArgument(
            String function, PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        if (positional > 1) {
            throw Slots.typeError(
                    "%s() takes at most 1 argument (%d given)", function, positional);
        }
        PyObject value = positional == 1 ? args[0] : null;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!"name".equals(kwNames[i])) {
                    throw Slots.typeError(
                            "%s() got an unexpected keyword argument '%s'", function, kwNames[i]);
                }
                if (value != null) {
                    throw Slots.typeError(
                            "argument for function given by name ('name') and position (1)");
                }
                value = args[positional + i];
            }
        }
        if (value == null) {
            throw Slots.typeError("%s() missing required argument 'name' (pos 1)", function);
        }
        return value;
    }

    private static PyObject fromRegistry(PyObject name) {
        PyObject found = dialects.get(name);
        if (found == null) {
            throw error("unknown dialect");
        }
        return found;
    }

    /**
     * {@code PyArg_UnpackTuple}: the sentence CPython writes when a positional count misses.
     */
    private static void unpackTuple(String name, int given, int least, int most) {
        if (given < least) {
            throw Slots.typeError(
                    "%s expected at least %d argument%s, got %d",
                    name, least, least == 1 ? "" : "s", given);
        }
        if (given > most) {
            throw Slots.typeError(
                    "%s expected at most %d argument%s, got %d",
                    name, most, most == 1 ? "" : "s", given);
        }
    }

    // --- dialects ------------------------------------------------------------------------

    /** A dialect's eight settings, as the C struct holds them. */
    private static final class Settings {
        int delimiter = ',';
        boolean doublequote = true;
        int escapechar = NOT_SET;
        String lineterminator = "\r\n";
        int quotechar = '"';
        int quoting = QUOTE_MINIMAL;
        boolean skipinitialspace;
        boolean strict;
    }

    /** The pair a reader or writer keeps: what it exposes as {@code .dialect} and what it runs on. */
    private record DialectResult(PyObject exposed, Settings settings) {}

    /** {@code _csv.Dialect}'s constructor, reached through {@code Constructors.instantiate}. */
    static PyObject dialectCalled(PyObject[] args, String[] kwNames) {
        int positional = positional(args, kwNames);
        if (positional > DIALECT_KEYWORDS.length) {
            throw Slots.typeError(
                    "function takes at most %d arguments (%d given)",
                    DIALECT_KEYWORDS.length, positional);
        }
        PyObject[] values = new PyObject[DIALECT_KEYWORDS.length];
        System.arraycopy(args, 0, values, 0, positional);
        bindKeywords(values, args, kwNames, positional);
        return create(values).exposed;
    }

    /**
     * The dialect a reader, writer or registration was handed.
     *
     * <p>{@code dialectArgument} is the positional dialect, and {@code args}/{@code kwNames} carry the
     * same call's keyword arguments from {@code positional} onwards. The reference merges the two in
     * one {@code Dialect(dialect, **kwargs)} call, so this does the same — including the refusal when
     * the same parameter is named twice.
     */
    private static DialectResult dialect(
            PyObject dialectArgument, String[] kwNames, PyObject[] args, int positional) {
        PyObject[] values = new PyObject[DIALECT_KEYWORDS.length];
        values[0] = dialectArgument;
        bindKeywords(values, args, kwNames, positional);
        return create(values);
    }

    private static void bindKeywords(
            PyObject[] values, PyObject[] args, String[] kwNames, int positional) {
        if (kwNames == null) {
            return;
        }
        for (int i = 0; i < kwNames.length; i++) {
            int at = indexOfKeyword(kwNames[i]);
            if (at < 0) {
                throw Slots.typeError(
                        "this function got an unexpected keyword argument '%s'", kwNames[i]);
            }
            if (values[at] != null) {
                throw Slots.typeError(
                        "argument for function given by name ('%s') and position (%d)",
                        kwNames[i], at + 1);
            }
            values[at] = args[positional + i];
        }
    }

    private static int indexOfKeyword(String name) {
        for (int i = 0; i < DIALECT_KEYWORDS.length; i++) {
            if (DIALECT_KEYWORDS[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code dialect_new}: resolve the dialect object, read what it does not override, convert and
     * validate every setting in the reference's order, and answer the object to expose.
     */
    private static DialectResult create(PyObject[] values) {
        PyObject dialect = values[0];
        if (dialect instanceof PyStr name) {
            dialect = fromRegistry(name);
        }
        if (dialect != null && isDialectInstance(dialect) && noneOverridden(values)) {
            // The reference reuses a dialect instance handed back untouched, without validating it.
            Settings settings =
                    dialect instanceof CsvDialect own ? own.settings : readSettings(dialect, values);
            return new DialectResult(dialect, settings);
        }
        Settings settings = readSettings(dialect, values);
        return new DialectResult(new CsvDialect(settings), settings);
    }

    private static boolean noneOverridden(PyObject[] values) {
        for (int i = 1; i < values.length; i++) {
            if (values[i] != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDialectInstance(PyObject value) {
        for (PyType type : value.getType().mro()) {
            if (type == PyType.CSV_DIALECT) {
                return true;
            }
        }
        return false;
    }

    private static Settings readSettings(PyObject dialect, PyObject[] values) {
        PyObject[] read = values.clone();
        if (dialect != null) {
            for (int i = 1; i < read.length; i++) {
                if (read[i] == null) {
                    try {
                        PyObject found = Slots.getAttr(dialect, DIALECT_KEYWORDS[i]);
                        if (found != Slots.DECLINED) {
                            read[i] = found;
                        }
                    } catch (PyRaise ignored) {
                        // The reference clears this error and falls back on the default.
                    }
                }
            }
        }
        Settings settings = new Settings();
        settings.delimiter = setChar("delimiter", read[1], ',');
        settings.doublequote = setBool("doublequote", read[2], true);
        settings.escapechar = setCharOrNone("escapechar", read[3], NOT_SET);
        settings.lineterminator = setStr("lineterminator", read[4], "\r\n");
        settings.quotechar = setCharOrNone("quotechar", read[5], '"');
        settings.quoting = setInt("quoting", read[6], QUOTE_MINIMAL);
        settings.skipinitialspace = setBool("skipinitialspace", read[7], false);
        settings.strict = setBool("strict", read[8], false);

        checkQuoting(settings.quoting);
        if (read[5] == PyNone.INSTANCE && read[6] == null) {
            settings.quoting = QUOTE_NONE;
        }
        if (settings.quoting != QUOTE_NONE && settings.quotechar == NOT_SET) {
            throw Slots.typeError("quotechar must be set if quoting enabled");
        }
        checkChar("delimiter", settings.delimiter, settings, true);
        checkChar("escapechar", settings.escapechar, settings, !settings.skipinitialspace);
        checkChar("quotechar", settings.quotechar, settings, !settings.skipinitialspace);
        checkChars("delimiter", "escapechar", settings.delimiter, settings.escapechar);
        checkChars("delimiter", "quotechar", settings.delimiter, settings.quotechar);
        checkChars("escapechar", "quotechar", settings.escapechar, settings.quotechar);
        return settings;
    }

    private static int setChar(String name, PyObject source, int fallback) {
        if (source == null) {
            return fallback;
        }
        if (!(source instanceof PyStr text)) {
            throw Slots.typeError(
                    "\"%s\" must be a unicode character, not %s", name, typeName(source));
        }
        String value = text.getValue();
        int length = codePointLength(value);
        if (length != 1) {
            throw Slots.typeError(
                    "\"%s\" must be a unicode character, not a string of length %d", name, length);
        }
        return value.codePointAt(0);
    }

    private static int setCharOrNone(String name, PyObject source, int fallback) {
        if (source == null) {
            return fallback;
        }
        if (source == PyNone.INSTANCE) {
            return NOT_SET;
        }
        if (!(source instanceof PyStr text)) {
            throw Slots.typeError(
                    "\"%s\" must be a unicode character or None, not %s", name, typeName(source));
        }
        String value = text.getValue();
        int length = codePointLength(value);
        if (length != 1) {
            throw Slots.typeError(
                    "\"%s\" must be a unicode character or None, not a string of length %d",
                    name, length);
        }
        return value.codePointAt(0);
    }

    private static String setStr(String name, PyObject source, String fallback) {
        if (source == null) {
            return fallback;
        }
        if (!(source instanceof PyStr text)) {
            throw Slots.typeError("\"%s\" must be a string, not %s", name, typeName(source));
        }
        return text.getValue();
    }

    private static int setInt(String name, PyObject source, int fallback) {
        if (source == null) {
            return fallback;
        }
        if (source.getType() != PyType.INT) {
            throw Slots.typeError("\"%s\" must be an integer, not %s", name, typeName(source));
        }
        BigInteger value = ((PyInt) source).getValue();
        if (value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw PyRaise.raise(
                    ExceptionTypes.OVERFLOW_ERROR, "Python int too large to convert to C int");
        }
        return value.intValue();
    }

    private static boolean setBool(String name, PyObject source, boolean fallback) {
        return source == null ? fallback : Slots.truth(source);
    }

    private static void checkQuoting(int quoting) {
        if (quoting < QUOTE_MINIMAL || quoting > QUOTE_NOTNULL) {
            throw Slots.typeError("bad \"quoting\" value");
        }
    }

    private static void checkChar(String name, int character, Settings settings, boolean allowSpace) {
        if (character == NOT_SET) {
            return;
        }
        if (character == '\r' || character == '\n' || (character == ' ' && !allowSpace)) {
            throw Slots.valueError("bad %s value", name);
        }
        if (containsCodePoint(settings.lineterminator, character)) {
            throw Slots.valueError("bad %s or lineterminator value", name);
        }
    }

    private static boolean containsCodePoint(String text, int character) {
        if (character == NOT_SET) {
            return false;
        }
        return text.indexOf(new String(Character.toChars(character))) >= 0;
    }

    private static void checkChars(String firstName, String secondName, int first, int second) {
        if (first == second && first != NOT_SET) {
            throw Slots.valueError("bad %s or %s value", firstName, secondName);
        }
    }

    // --- values --------------------------------------------------------------------------

    /** {@code _csv.Dialect}: eight read-only settings and nothing else. */
    static final class CsvDialect extends PyObject {

        final Settings settings;

        CsvDialect(Settings settings) {
            super(PyType.CSV_DIALECT);
            this.settings = settings;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }
    }

    /** {@code _csv.reader}: the parser's state and its one exposed counter. */
    static final class CsvReader extends PyIterator {

        private final PyIterator input;
        private final PyObject dialectObject;
        private final Settings settings;

        private long lineNum;
        private int state = START_RECORD;
        private boolean unquotedField;
        /** Whether a read is running: a re-entrant {@code next()} is refused rather than nested. */
        private boolean iterating;
        private final StringBuilder field = new StringBuilder();
        private List<PyObject> fields = new ArrayList<>();

        CsvReader(PyIterator input, DialectResult dialect) {
            super(PyType.CSV_READER);
            this.input = input;
            this.dialectObject = dialect.exposed();
            this.settings = dialect.settings();
        }

        @Override
        public PyObject nextOrNull() {
            if (iterating) {
                throw error("iterator has already advanced the reader");
            }
            iterating = true;
            try {
                return readRecord();
            } finally {
                iterating = false;
            }
        }

        private PyObject readRecord() {
            fields = new ArrayList<>();
            field.setLength(0);
            state = START_RECORD;
            unquotedField = false;

            while (true) {
                PyObject lineObject = input.nextOrNull();
                if (lineObject == null) {
                    if (field.length() != 0 || state == IN_QUOTED_FIELD) {
                        if (settings.strict) {
                            throw error("unexpected end of data");
                        }
                        saveField();
                        return PyList.of(fields.toArray(new PyObject[0]));
                    }
                    return null;
                }
                if (!(lineObject instanceof PyStr line)) {
                    throw error(
                            "iterator should return strings, not %s "
                                    + "(the file should be opened in text mode)",
                            typeName(lineObject));
                }
                lineNum++;
                String text = line.getValue();
                for (int at = 0; at < text.length(); ) {
                    int character = text.codePointAt(at);
                    at += Character.charCount(character);
                    process(character);
                }
                process(EOL);
                if (state == START_RECORD) {
                    return PyList.of(fields.toArray(new PyObject[0]));
                }
            }
        }

        private void process(int c) {
            switch (state) {
                case START_RECORD -> {
                    if (c == EOL) {
                        break;
                    }
                    if (c == '\n' || c == '\r') {
                        state = EAT_CRNL;
                        break;
                    }
                    state = START_FIELD;
                    processStartField(c);
                }
                case START_FIELD -> processStartField(c);
                case ESCAPED_CHAR -> {
                    if (c == '\n' || c == '\r') {
                        addChar(c);
                        state = AFTER_ESCAPED_CRNL;
                        break;
                    }
                    if (c == EOL) {
                        c = '\n';
                    }
                    addChar(c);
                    state = IN_FIELD;
                }
                case AFTER_ESCAPED_CRNL -> {
                    if (c == EOL) {
                        break;
                    }
                    processInField(c);
                }
                case IN_FIELD -> processInField(c);
                case IN_QUOTED_FIELD -> {
                    if (c == EOL) {
                        break;
                    }
                    if (c == settings.escapechar) {
                        state = ESCAPE_IN_QUOTED_FIELD;
                    } else if (c == settings.quotechar && settings.quoting != QUOTE_NONE) {
                        state = settings.doublequote ? QUOTE_IN_QUOTED_FIELD : IN_FIELD;
                    } else {
                        addChar(c);
                    }
                }
                case ESCAPE_IN_QUOTED_FIELD -> {
                    if (c == EOL) {
                        c = '\n';
                    }
                    addChar(c);
                    state = IN_QUOTED_FIELD;
                }
                case QUOTE_IN_QUOTED_FIELD -> {
                    if (settings.quoting != QUOTE_NONE && c == settings.quotechar) {
                        addChar(c);
                        state = IN_QUOTED_FIELD;
                    } else if (c == settings.delimiter) {
                        saveField();
                        state = START_FIELD;
                    } else if (c == '\n' || c == '\r' || c == EOL) {
                        saveField();
                        state = c == EOL ? START_RECORD : EAT_CRNL;
                    } else if (!settings.strict) {
                        addChar(c);
                        state = IN_FIELD;
                    } else {
                        throw error(
                                "'%s' expected after '%s'",
                                new String(Character.toChars(settings.delimiter)),
                                new String(Character.toChars(settings.quotechar)));
                    }
                }
                case EAT_CRNL -> {
                    if (c == '\n' || c == '\r') {
                        break;
                    }
                    if (c == EOL) {
                        state = START_RECORD;
                        break;
                    }
                    throw error(
                            "new-line character seen in unquoted field - "
                                    + "do you need to open the file with newline=''?");
                }
                default -> throw new IllegalStateException("csv state " + state);
            }
        }

        private void processStartField(int c) {
            unquotedField = true;
            if (c == '\n' || c == '\r' || c == EOL) {
                saveField();
                state = c == EOL ? START_RECORD : EAT_CRNL;
            } else if (c == settings.quotechar && settings.quoting != QUOTE_NONE) {
                unquotedField = false;
                state = IN_QUOTED_FIELD;
            } else if (c == settings.escapechar) {
                state = ESCAPED_CHAR;
            } else if (c == ' ' && settings.skipinitialspace) {
                // Ignored at the start of a field.
            } else if (c == settings.delimiter) {
                saveField();
            } else {
                addChar(c);
                state = IN_FIELD;
            }
        }

        private void processInField(int c) {
            if (c == '\n' || c == '\r' || c == EOL) {
                saveField();
                state = c == EOL ? START_RECORD : EAT_CRNL;
            } else if (c == settings.escapechar) {
                state = ESCAPED_CHAR;
            } else if (c == settings.delimiter) {
                saveField();
                state = START_FIELD;
            } else {
                addChar(c);
            }
        }

        private void addChar(int c) {
            if (field.length() >= fieldLimit) {
                throw error("field larger than field limit (%d)", fieldLimit);
            }
            field.appendCodePoint(c);
        }

        private void saveField() {
            PyObject value;
            if (unquotedField
                    && field.length() == 0
                    && (settings.quoting == QUOTE_NOTNULL || settings.quoting == QUOTE_STRINGS)) {
                value = PyNone.INSTANCE;
            } else {
                String text = field.toString();
                if (unquotedField
                        && field.length() != 0
                        && (settings.quoting == QUOTE_NONNUMERIC
                                || settings.quoting == QUOTE_STRINGS)) {
                    value = Slots.call(PyType.FLOAT, new PyObject[] {PyStr.of(text)}, PyTuple.EMPTY);
                } else {
                    value = PyStr.of(text);
                }
            }
            field.setLength(0);
            fields.add(value);
        }
    }

    /** {@code _csv.writer}: the record buffer and the {@code write} callable it feeds. */
    static final class CsvWriter extends PyObject {

        private final PyObject write;
        private final PyObject dialectObject;
        private final Settings settings;

        private final StringBuilder record = new StringBuilder();
        private int numFields;

        CsvWriter(PyObject write, DialectResult dialect) {
            super(PyType.CSV_WRITER);
            this.write = write;
            this.dialectObject = dialect.exposed();
            this.settings = dialect.settings();
        }

        /** {@code writerow(iterable)}: the result of the underlying {@code write}, as the reference. */
        PyObject writerow(PyObject sequence) {
            PyIterator iterator;
            try {
                iterator = Slots.iterator(sequence);
            } catch (PyRaise refusal) {
                if (refusal.value().exceptionType().matches(ExceptionTypes.TYPE_ERROR)) {
                    throw error("iterable expected, not %s", typeName(sequence));
                }
                throw refusal;
            }

            record.setLength(0);
            numFields = 0;
            boolean nullField = false;
            PyObject field;
            while ((field = iterator.nextOrNull()) != null) {
                boolean quoted =
                        switch (settings.quoting) {
                            case QUOTE_NONNUMERIC -> !Numbers.isNumber(field);
                            case QUOTE_ALL -> true;
                            case QUOTE_STRINGS -> field instanceof PyStr;
                            case QUOTE_NOTNULL -> field != PyNone.INSTANCE;
                            default -> false;
                        };
                nullField = field == PyNone.INSTANCE;
                if (field instanceof PyStr text) {
                    joinAppend(text.getValue(), quoted);
                } else if (nullField) {
                    joinAppend(null, quoted);
                } else {
                    joinAppend(Slots.str(field), quoted);
                }
            }

            if (numFields > 0 && record.length() == 0) {
                if (settings.quoting == QUOTE_NONE
                        || (nullField
                                && (settings.quoting == QUOTE_STRINGS
                                        || settings.quoting == QUOTE_NOTNULL))) {
                    throw error("single empty field record must be quoted");
                }
                numFields--;
                joinAppend(null, true);
            }

            record.append(settings.lineterminator);
            return Slots.call(write, new PyObject[] {PyStr.of(record.toString())}, PyTuple.EMPTY);
        }

        PyObject writerows(PyObject sequence) {
            PyIterator rows = Slots.iterator(sequence);
            PyObject row;
            while ((row = rows.nextOrNull()) != null) {
                writerow(row);
            }
            return PyNone.INSTANCE;
        }

        /** {@code join_append}: one field, quotes decided and the escape rule applied. */
        private void joinAppend(String field, boolean quoted) {
            int length = field == null ? 0 : codePointLength(field);
            if (length == 0 && settings.delimiter == ' ' && settings.skipinitialspace) {
                if (settings.quoting == QUOTE_NONE
                        || (field == null
                                && (settings.quoting == QUOTE_STRINGS
                                        || settings.quoting == QUOTE_NOTNULL))) {
                    throw error(
                            "empty field must be quoted if delimiter is a space "
                                    + "and skipinitialspace is true");
                }
                quoted = true;
            }
            quoted = decideQuoted(field, quoted);
            appendData(field, quoted);
            numFields++;
        }

        /**
         * The reference's length-counting pass: it decides whether the field needs quotes and refuses
         * an escape it cannot spell, before a single character is written.
         */
        private boolean decideQuoted(String field, boolean quoted) {
            if (field == null) {
                return quoted;
            }
            for (int at = 0; at < field.length(); ) {
                int c = field.codePointAt(at);
                at += Character.charCount(c);
                if (!isSpecial(c)) {
                    continue;
                }
                boolean wantEscape = false;
                if (settings.quoting == QUOTE_NONE) {
                    wantEscape = true;
                } else if (c == settings.quotechar) {
                    if (settings.doublequote) {
                        quoted = true;
                    } else {
                        wantEscape = true;
                    }
                } else if (c == settings.escapechar) {
                    wantEscape = true;
                } else {
                    quoted = true;
                }
                if (wantEscape && settings.escapechar == NOT_SET) {
                    throw error("need to escape, but no escapechar set");
                }
            }
            return quoted;
        }

        private void appendData(String field, boolean quoted) {
            if (numFields > 0) {
                record.appendCodePoint(settings.delimiter);
            }
            if (quoted) {
                record.appendCodePoint(settings.quotechar);
            }
            if (field != null) {
                for (int at = 0; at < field.length(); ) {
                    int c = field.codePointAt(at);
                    at += Character.charCount(c);
                    if (isSpecial(c)) {
                        boolean wantEscape = false;
                        if (settings.quoting == QUOTE_NONE) {
                            wantEscape = true;
                        } else if (c == settings.quotechar) {
                            if (settings.doublequote) {
                                record.appendCodePoint(settings.quotechar);
                            } else {
                                wantEscape = true;
                            }
                        } else if (c == settings.escapechar) {
                            wantEscape = true;
                        }
                        if (wantEscape) {
                            record.appendCodePoint(settings.escapechar);
                        }
                    }
                    record.appendCodePoint(c);
                }
            }
            if (quoted) {
                record.appendCodePoint(settings.quotechar);
            }
        }

        private boolean isSpecial(int c) {
            return c == settings.delimiter
                    || c == settings.escapechar
                    || c == settings.quotechar
                    || c == '\n'
                    || c == '\r'
                    || containsCodePoint(settings.lineterminator, c);
        }
    }

    static {
        Members dialect = Members.of(PyType.CSV_DIALECT);
        dialect.addField("delimiter", null, self -> character(((CsvDialect) self).settings.delimiter));
        dialect.addField("doublequote", null,
                self -> PyBool.of(((CsvDialect) self).settings.doublequote));
        dialect.addField("escapechar", null, self -> character(((CsvDialect) self).settings.escapechar));
        dialect.addField("lineterminator", null,
                self -> PyStr.of(((CsvDialect) self).settings.lineterminator));
        dialect.addField("quotechar", null, self -> character(((CsvDialect) self).settings.quotechar));
        dialect.addField("quoting", null, self -> PyInt.of(((CsvDialect) self).settings.quoting));
        dialect.addField("skipinitialspace", null,
                self -> PyBool.of(((CsvDialect) self).settings.skipinitialspace));
        dialect.addField("strict", null, self -> PyBool.of(((CsvDialect) self).settings.strict));
        // `Dialect_reduce`: a dialect carries no state a copy could re-create, so both protocol hooks
        // refuse in the reference's one sentence, and `copy.copy` reaches it through `__reduce_ex__`.
        PyBoundMethod.Body refusePickle = (self, args, kwNames) -> {
            throw Slots.typeError(
                    "cannot pickle '%s' instances", self.getType().name());
        };
        dialect.add("__reduce__", refusePickle);
        dialect.add("__reduce_ex__", refusePickle);

        Members reader = Members.of(PyType.CSV_READER);
        reader.addField("dialect", null, self -> ((CsvReader) self).dialectObject);
        reader.addField("line_num", null, self -> PyInt.of(((CsvReader) self).lineNum));

        Members writer = Members.of(PyType.CSV_WRITER);
        writer.addField("dialect", null, self -> ((CsvWriter) self).dialectObject);
        writer.add("writerow", (self, args, kwNames) -> {
            Arguments.exactlyOne("writer.writerow()", args, kwNames);
            return ((CsvWriter) self).writerow(args[0]);
        });
        writer.add("writerows", (self, args, kwNames) -> {
            Arguments.exactlyOne("writer.writerows()", args, kwNames);
            return ((CsvWriter) self).writerows(args[0]);
        });
    }

    private static PyObject character(int c) {
        return c == NOT_SET ? PyNone.INSTANCE : PyStr.of(new String(Character.toChars(c)));
    }
}
