package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code builtins}: the namespace every module frame falls back to when a name is not its own.
 *
 * The entries are the ones M1's done-criterion needs — the type constructors, the exception names a
 * script can raise or catch, and the functions that arithmetic, branching, looping and calling a
 * function are written with. What is absent is deliberate and short: {@code exec}, {@code eval} and
 * {@code compile} are the code-evaluation path, {@code super} is the class system's other half,
 * {@code open} plus the rest of the file API is M4, and {@code property}, {@code classmethod} and
 * {@code staticmethod} are here as the descriptor builtins they are. A name that is not here raises
 * {@code NameError}, which points at the missing feature; a half-implemented one would point at nothing.
 *
 * Every message is as CPython 3.14 words it, including the count family a builtin belongs to —
 * {@code len() takes exactly one argument (2 given)} against {@code min expected at least 1 argument,
 * got 0} — because those strings are asserted directly by the same tests this milestone is measured
 * with.
 */
final class Builtins {

    private Builtins() {
    }

    /** Build the namespace. Called once, by {@link PyRuntime#startup()}. */
    static PyDict namespace() {
        PyDict out = PyDict.empty();
        addConstants(out);
        addTypes(out);
        addExceptions(out);
        addFunctions(out);
        return out;
    }

    private static void put(PyDict out, String name, PyObject value) {
        out.put(PyStr.interned(name), value);
    }

    private static void addConstants(PyDict out) {
        put(out, "None", PyNone.INSTANCE);
        put(out, "True", PyBool.TRUE);
        put(out, "False", PyBool.FALSE);
        put(out, "NotImplemented", PyNotImplemented.INSTANCE);
        put(out, "Ellipsis", PyEllipsis.INSTANCE);
    }

    /** The type objects, which Python reaches for by name both to construct with and to test against. */
    private static void addTypes(PyDict out) {
        put(out, "object", PyType.OBJECT);
        put(out, "type", PyType.TYPE);
        put(out, "bool", PyType.BOOL);
        put(out, "int", PyType.INT);
        put(out, "float", PyType.FLOAT);
        put(out, "complex", PyType.COMPLEX);
        put(out, "str", PyType.STR);
        put(out, "bytes", PyType.BYTES);
        put(out, "tuple", PyType.TUPLE);
        put(out, "list", PyType.LIST);
        put(out, "dict", PyType.DICT);
        put(out, "set", PyType.SET);
        put(out, "frozenset", PyType.FROZENSET);
        put(out, "range", PyType.RANGE);
        put(out, "slice", PyType.SLICE);
        put(out, "function", PyType.FUNCTION);
        put(out, "method", PyType.METHOD);
        put(out, "module", PyType.MODULE);
        put(out, "property", PyType.PROPERTY);
        put(out, "classmethod", PyType.CLASS_METHOD);
        put(out, "staticmethod", PyType.STATIC_METHOD);
        put(out, "super", PyType.SUPER);
    }

    private static void addExceptions(PyDict out) {
        put(out, "BaseException", ExceptionTypes.BASE_EXCEPTION);
        put(out, "BaseExceptionGroup", ExceptionTypes.BASE_EXCEPTION_GROUP);
        put(out, "Exception", ExceptionTypes.EXCEPTION);
        put(out, "ExceptionGroup", ExceptionTypes.EXCEPTION_GROUP);
        put(out, "ArithmeticError", ExceptionTypes.ARITHMETIC_ERROR);
        put(out, "AssertionError", ExceptionTypes.ASSERTION_ERROR);
        put(out, "AttributeError", ExceptionTypes.ATTRIBUTE_ERROR);
        put(out, "FileNotFoundError", ExceptionTypes.FILE_NOT_FOUND_ERROR);
        put(out, "GeneratorExit", ExceptionTypes.GENERATOR_EXIT);
        put(out, "ImportError", ExceptionTypes.IMPORT_ERROR);
        put(out, "IndexError", ExceptionTypes.INDEX_ERROR);
        put(out, "KeyError", ExceptionTypes.KEY_ERROR);
        put(out, "KeyboardInterrupt", ExceptionTypes.KEYBOARD_INTERRUPT);
        put(out, "LookupError", ExceptionTypes.LOOKUP_ERROR);
        put(out, "MemoryError", ExceptionTypes.MEMORY_ERROR);
        put(out, "ModuleNotFoundError", ExceptionTypes.MODULE_NOT_FOUND_ERROR);
        put(out, "NameError", ExceptionTypes.NAME_ERROR);
        put(out, "NotImplementedError", ExceptionTypes.NOT_IMPLEMENTED_ERROR);
        put(out, "OSError", ExceptionTypes.OS_ERROR);
        put(out, "OverflowError", ExceptionTypes.OVERFLOW_ERROR);
        put(out, "RecursionError", ExceptionTypes.RECURSION_ERROR);
        put(out, "RuntimeError", ExceptionTypes.RUNTIME_ERROR);
        put(out, "StopIteration", ExceptionTypes.STOP_ITERATION);
        put(out, "SyntaxError", ExceptionTypes.SYNTAX_ERROR);
        put(out, "SystemError", ExceptionTypes.SYSTEM_ERROR);
        put(out, "SystemExit", ExceptionTypes.SYSTEM_EXIT);
        put(out, "TypeError", ExceptionTypes.TYPE_ERROR);
        put(out, "UnboundLocalError", ExceptionTypes.UNBOUND_LOCAL_ERROR);
        put(out, "UnicodeDecodeError", ExceptionTypes.UNICODE_DECODE_ERROR);
        put(out, "UnicodeEncodeError", ExceptionTypes.UNICODE_ENCODE_ERROR);
        put(out, "UnicodeError", ExceptionTypes.UNICODE_ERROR);
        put(out, "UnicodeTranslateError", ExceptionTypes.UNICODE_TRANSLATE_ERROR);
        put(out, "ValueError", ExceptionTypes.VALUE_ERROR);
        put(out, "ZeroDivisionError", ExceptionTypes.ZERO_DIVISION_ERROR);
    }

    private static void addFunctions(PyDict out) {
        put(out, "print", new PyNativeFunction("print", Builtins::print));
        put(out, "len", unary("len", value -> Slots.length(value)));
        put(out, "repr", unary("repr", value -> PyStr.of(value.repr())));
        put(out, "abs", unary("abs", value -> Slots.absolute(value)));
        put(out, "hash", unary("hash", value -> PyInt.of(value.hash())));
        put(out, "id", unary("id", value -> PyInt.of(System.identityHashCode(value))));
        put(out, "callable", unary("callable", value -> PyBool.of(Slots.isCallable(value))));
        put(out, "ord", unary("ord", Builtins::ordinal));
        put(out, "chr", unary("chr", Builtins::character));
        put(out, "bin", unary("bin", value -> PyStr.of(radix(value, 2, "0b"))));
        put(out, "hex", unary("hex", value -> PyStr.of(radix(value, 16, "0x"))));
        put(out, "oct", unary("oct", value -> PyStr.of(radix(value, 8, "0o"))));
        put(out, "format", new PyNativeFunction("format", Builtins::format));
        put(out, "min", new PyNativeFunction("min", (args, kw) -> extreme(args, kw, false)));
        put(out, "max", new PyNativeFunction("max", (args, kw) -> extreme(args, kw, true)));
        put(out, "sum", new PyNativeFunction("sum", Builtins::sum));
        put(out, "sorted", new PyNativeFunction("sorted", Builtins::sorted));
        put(out, "reversed", unary("reversed", Builtins::reversed));
        put(out, "enumerate", new PyNativeFunction("enumerate", Builtins::enumerate));
        put(out, "zip", new PyNativeFunction("zip", Builtins::zip));
        put(out, "map", new PyNativeFunction("map", Builtins::map));
        put(out, "filter", new PyNativeFunction("filter", Builtins::filter));
        put(out, "iter", new PyNativeFunction("iter", Builtins::iter));
        put(out, "next", new PyNativeFunction("next", Builtins::next));
        put(out, "isinstance", twoArgument("isinstance", Builtins::isInstanceOf));
        put(out, "issubclass", twoArgument("issubclass", Builtins::isSubclass));
        put(out, "divmod", twoArgument("divmod", (a, b) -> PyTuple.of(
                Slots.binary(BinaryOp.FLOORDIV, a, b), Slots.binary(BinaryOp.MOD, a, b))));
        put(out, "pow", new PyNativeFunction("pow", Builtins::power));
        put(out, "round", new PyNativeFunction("round", Builtins::round));
        put(out, "getattr", new PyNativeFunction("getattr", Builtins::getAttribute));
        put(out, "hasattr", new PyNativeFunction("hasattr", Builtins::hasAttribute));
        put(out, "setattr", new PyNativeFunction("setattr", Builtins::setAttribute));
        put(out, "delattr", new PyNativeFunction("delattr", Builtins::deleteAttribute));
        put(out, "locals", new PyNativeFunction("locals", Builtins::localNames));
        put(out, "globals", new PyNativeFunction("globals", Builtins::globalNames));
        put(out, "vars", new PyNativeFunction("vars", Builtins::variables));
        put(out, "dir", new PyNativeFunction("dir", Builtins::directory));
        put(out, "__build_class__", new PyNativeFunction("__build_class__", Classes::buildClass));
    }

    // --- one argument ----------------------------------------------------------------------

    /** A builtin of exactly one positional argument, in {@code len()}'s counting family. */
    private static PyNativeFunction unary(
            String name, java.util.function.Function<PyObject, PyObject> body) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.exactlyOne(name + "()", args, kwNames);
            return body.apply(args[0]);
        });
    }

    /** A builtin of exactly two positional arguments, in {@code isinstance}'s legacy family. */
    private static PyNativeFunction twoArgument(
            String name, java.util.function.BiFunction<PyObject, PyObject, PyObject> body) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.noKeywords(name + "()", kwNames);
            Arguments.exactly(name, args, kwNames, 2);
            return body.apply(args[0], args[1]);
        });
    }

    private static PyObject ordinal(PyObject value) {
        if (!(value instanceof PyStr s)) {
            throw Slots.typeError(
                    "ord() expected string of length 1, but %s found", value.getType().name());
        }
        if (s.codePointLength() != 1) {
            throw Slots.typeError(
                    "ord() expected a character, but string of length %d found",
                    s.codePointLength());
        }
        return PyInt.of(s.codePointAt(0));
    }

    private static PyObject character(PyObject value) {
        BigInteger code = integerOrRefusal(value);
        if (code.signum() < 0 || code.compareTo(MAX_CODE_POINT) > 0) {
            throw Slots.valueError("chr() arg not in range(0x110000)");
        }
        return PyStr.of(new String(Character.toChars(code.intValue())));
    }

    private static final BigInteger MAX_CODE_POINT = BigInteger.valueOf(0x10FFFF);

    /** {@code bin}, {@code hex}, {@code oct}: an {@code __index__} integer with the prefix inside the sign. */
    private static String radix(PyObject value, int base, String prefix) {
        BigInteger number = integerOrRefusal(value);
        return (number.signum() < 0 ? "-" : "") + prefix + number.abs().toString(base);
    }

    private static BigInteger integerOrRefusal(PyObject value) {
        BigInteger exact = Numbers.asInteger(value);
        if (exact == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().name());
        }
        return exact;
    }

    /**
     * {@code format(value, format_spec='', /)}.
     *
     * Both parameters are positional-only, which is why a keyword gives the counting family's own
     * refusal rather than a named complaint.
     */
    private static PyObject format(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("format()", kwNames);
        Arguments.between("format", args, kwNames, 1, 2);
        String spec = args.length == 1 ? "" : asSpec(args[1]);
        return PyStr.of(Formats.apply(args[0], spec));
    }

    private static String asSpec(PyObject value) {
        if (value instanceof PyStr s) {
            return s.getValue();
        }
        throw Slots.typeError(
                "format() argument 2 must be str, not %s", namedType(value));
    }

    /**
     * How a type's name is printed in a message that names the type rather than quoting it.
     *
     * {@code None} is the one value whose type is not called by its type's name: CPython says
     * {@code 'NoneType' found} in {@code int()}'s message and {@code must be str, not None} in
     * {@code format()}'s, because the second is written against the argument, not the type.
     */
    private static String namedType(PyObject value) {
        return value == PyNone.INSTANCE ? "None" : value.getType().name();
    }

    // --- print -----------------------------------------------------------------------------

    private static final String[] PRINT_NAMES = {"sep", "end", "file", "flush"};

    /**
     * {@code print(*args, sep=' ', end='\n', file=None, flush=False)}.
     *
     * The four parameters are keyword-only, so every positional argument is something to print — which
     * is why {@code print(1, "a")} prints {@code 1 a} rather than setting a separator. Giving {@code
     * None} for {@code sep} or {@code end} is not "print nothing" but "print the default", which is
     * what the two look like when they are simply absent.
     */
    private static PyObject print(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        String separator = " ";
        String ending = "\n";
        PyObject target = PyNone.INSTANCE;
        boolean flush = false;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject value = args[positional + i];
                switch (kwNames[i]) {
                    case "sep" -> separator = printed("sep", value, " ");
                    case "end" -> ending = printed("end", value, "\n");
                    case "file" -> target = value;
                    case "flush" -> flush = Slots.truth(value);
                    default -> throw Slots.typeError(
                            "print() got an unexpected keyword argument '%s'", kwNames[i]);
                }
            }
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < positional; i++) {
            if (i > 0) {
                line.append(separator);
            }
            line.append(args[i].str());
        }
        line.append(ending);
        PyObject stream = target == PyNone.INSTANCE ? PyRuntime.stdout() : target;
        // The stream is used through its attributes, not its class, which is what both `file=` with
        // any object that can write and `print(x, file=1)`'s AttributeError depend on.
        Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of(line.toString())}, null);
        if (flush) {
            Slots.call(Slots.getAttr(stream, "flush"), new PyObject[0], null);
        }
        return PyNone.INSTANCE;
    }

    /** {@code sep} and {@code end}, where {@code None} means the parameter's own default. */
    private static String printed(String name, PyObject value, String fallback) {
        if (value == PyNone.INSTANCE) {
            return fallback;
        }
        if (value instanceof PyStr s) {
            return s.getValue();
        }
        throw Slots.typeError(
                "%s must be None or a string, not %s", name, value.getType().name());
    }

    // --- iteration and sequences -----------------------------------------------------------

    /**
     * {@code reversed(x)} for the types that have an end.
     *
     * Reversal is not iteration: an iterator and a set are both iterable but neither has a last
     * element, so the refusal is by type and comes before anything is read.
     */
    private static PyObject reversed(PyObject value) {
        if (!(value instanceof PyList
                || value instanceof PyTuple
                || value instanceof PyStr
                || value instanceof PyBytes
                || value instanceof PyRange
                || value instanceof PyDict)) {
            throw Slots.typeError("'%s' object is not reversible", value.getType().name());
        }
        List<PyObject> items = Constructors.materialise(value);
        java.util.Collections.reverse(items);
        return PyIterator.of(items);
    }

    private static final String[] ENUMERATE_NAMES = {"start"};

    private static PyObject enumerate(PyObject[] args, String[] kwNames) {
        Arguments.rejectKeywords("enumerate", kwNames, ENUMERATE_NAMES);
        Arguments.atMostQualified("enumerate", args, kwNames, 2);
        int positional = Arguments.positional(args, kwNames);
        if (positional < 1) {
            throw Slots.typeError("enumerate() missing required argument 'iterable'");
        }
        long at = 0;
        PyObject start = positional > 1
                ? args[1]
                : keywordValue(kwNames, args, positional, "start", null);
        if (start != null) {
            BigInteger from = integerOrRefusal(start);
            if (!Numbers.fitsInt(from)) {
                throw Slots.overflowError("Python int too large to convert to C ssize_t");
            }
            at = from.longValue();
        }
        return new Counter(Slots.iterator(args[0]), at);
    }

    /** {@code enumerate}: the pair-counting iterator, which is its own type in CPython. */
    private static final class Counter extends PyIterator {

        private final PyIterator source;
        private long at;

        Counter(PyIterator source, long at) {
            this.source = source;
            this.at = at;
        }

        @Override
        public PyObject nextOrNull() {
            PyObject value = source.nextOrNull();
            return value == null ? null : PyTuple.of(PyInt.of(at++), value);
        }
    }

    private static PyObject zip(PyObject[] args, String[] kwNames) {
        boolean strict = false;
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!"strict".equals(kwNames[i])) {
                    throw Slots.typeError(
                            "zip() got an unexpected keyword argument '%s'", kwNames[i]);
                }
                strict = Slots.truth(args[positional + i]);
            }
        }
        List<PyIterator> sources = new ArrayList<>();
        for (int i = 0; i < positional; i++) {
            sources.add(Slots.iterator(args[i]));
        }
        return new Zipped(sources, strict);
    }

    /**
     * {@code zip}: one tuple per round of the iterators, ending at the shortest.
     *
     * CPython's type is called {@code zip} and its values print their own name; both are reported
     * here as {@code iterator}, which is the divergence {@link PyIterator} documents.
     */
    private static final class Zipped extends PyIterator {

        private final List<PyIterator> sources;
        private final boolean strict;

        Zipped(List<PyIterator> sources, boolean strict) {
            this.sources = sources;
            this.strict = strict;
        }

        @Override
        public PyObject nextOrNull() {
            PyObject[] pulled = new PyObject[sources.size()];
            int exhausted = 0;
            for (int i = 0; i < sources.size(); i++) {
                pulled[i] = sources.get(i).nextOrNull();
                if (pulled[i] == null) {
                    exhausted++;
                }
            }
            if (exhausted == 0) {
                return PyTuple.of(pulled);
            }
            if (strict && exhausted < sources.size()) {
                throw Slots.valueError(shortfall(pulled));
            }
            return null;
        }

        /**
         * The strict complaint, naming the side that ran on and the run of sources that stopped.
         *
         * Which of the two forms it takes is decided by whether the first source is among those that
         * stopped: a leading run of short sources makes the rest "longer than argument 1", while a
         * short source somewhere later makes it "shorter than arguments 1-2".
         */
        private static String shortfall(PyObject[] pulled) {
            int first = 0;
            while (pulled[first] != null) {
                first++;
            }
            if (first == 0) {
                int run = 0;
                while (run < pulled.length && pulled[run] == null) {
                    run++;
                }
                for (int j = run; j < pulled.length; j++) {
                    if (pulled[j] != null) {
                        return "zip() argument " + (j + 1) + " is longer than " + range(run);
                    }
                }
            }
            return "zip() argument " + (first + 1) + " is shorter than " + range(first);
        }

        /** The sources {@code 1..count}, named as one argument or as a range. */
        private static String range(int count) {
            return count == 1 ? "argument 1" : "arguments 1-" + count;
        }
    }

    /**
     * {@code map(function, iterable, ...)}.
     *
     * Its too-few-arguments message is the one CPython writes by hand rather than a counting template,
     * and it takes no keywords at all.
     */
    private static PyObject map(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError(
                    "map() got an unexpected keyword argument '%s'", kwNames[0]);
        }
        if (args.length < 2) {
            throw Slots.typeError("map() must have at least two arguments.");
        }
        PyObject function = args[0];
        List<PyIterator> sources = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            sources.add(Slots.iterator(args[i]));
        }
        return new Mapped(function, sources);
    }

    private static final class Mapped extends PyIterator {

        private final PyObject function;
        private final List<PyIterator> sources;

        Mapped(PyObject function, List<PyIterator> sources) {
            this.function = function;
            this.sources = sources;
        }

        @Override
        public PyObject nextOrNull() {
            List<PyObject> pulled = new ArrayList<>(sources.size());
            for (PyIterator source : sources) {
                PyObject value = source.nextOrNull();
                if (value == null) {
                    return null;
                }
                pulled.add(value);
            }
            return Slots.call(
                    function, pulled.toArray(new PyObject[0]), null);
        }
    }

    private static PyObject filter(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("filter()", kwNames);
        Arguments.exactly("filter", args, kwNames, 2);
        return new Filtered(args[0], Slots.iterator(args[1]));
    }

    /** {@code filter}: {@code None} as the predicate means the value's own truth, as CPython does. */
    private static final class Filtered extends PyIterator {

        private final PyObject predicate;
        private final PyIterator source;

        Filtered(PyObject predicate, PyIterator source) {
            this.predicate = predicate;
            this.source = source;
        }

        @Override
        public PyObject nextOrNull() {
            while (true) {
                PyObject value = source.nextOrNull();
                if (value == null) {
                    return null;
                }
                boolean keep = predicate == PyNone.INSTANCE
                        ? Slots.truth(value)
                        : Slots.truth(Slots.call(predicate, new PyObject[] {value}, null));
                if (keep) {
                    return value;
                }
            }
        }
    }

    private static PyObject iter(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("iter()", kwNames);
        Arguments.between("iter", args, kwNames, 1, 2);
        if (args.length == 1) {
            return Slots.iterator(args[0]);
        }
        if (!Slots.isCallable(args[0])) {
            throw Slots.typeError("iter(v, w): v must be callable");
        }
        return new Repeated(Slots.call(args[0], new PyObject[0], null), args[1]);
    }

    /** {@code iter(callable, sentinel)}, the two-argument form. */
    private static final class Repeated extends PyIterator {

        private final PyObject callable;
        private final PyObject sentinel;

        Repeated(PyObject callable, PyObject sentinel) {
            this.callable = callable;
            this.sentinel = sentinel;
        }

        @Override
        public PyObject nextOrNull() {
            PyObject value = Slots.call(callable, new PyObject[0], null);
            return Slots.compareTrue(CompareOp.EQ, value, sentinel) ? null : value;
        }
    }

    private static PyObject next(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("next()", kwNames);
        Arguments.between("next", args, kwNames, 1, 2);
        // Not only the built-in iterators: a class that declares __next__ is driven the same way, which
        // is what PyIter_Check means for a value of a Python class.
        PyIterator iterator = Slots.asIterator(args[0]);
        if (iterator == null) {
            throw Slots.typeError(
                    "'%s' object is not an iterator", args[0].getType().name());
        }
        PyObject value = iterator.nextOrNull();
        if (value != null) {
            return value;
        }
        // The three outcomes of `PyIter_Next` returning NULL, which CPython tells apart by whether an
        // exception was left set. A generator that ended with a value was; one that simply ran out, or one
        // whose frame had already finished when this step was made, was not — and the difference is what
        // decides whether the default is taken or the real StopIteration, with its value, travels on.
        PyBaseException stopped = iterator.exhaustion();
        if (stopped != null) {
            if (args.length > 1) {
                return args[1];
            }
            // Re-raised as it was made: `_PyErr_SetRaisedException`, so no context is taken from whatever
            // the caller happens to be handling.
            throw PyRaise.reraise(stopped);
        }
        if (args.length > 1) {
            return args[1];
        }
        // `PyErr_SetNone`, which is the route that does chain: this invented exception gets the caller's
        // handled exception as its context, exactly as a bare `raise StopIteration` inside a handler would.
        throw PyRaise.of(new PyBaseException(ExceptionTypes.STOP_ITERATION, new PyObject[0]));
    }

    // --- aggregates ------------------------------------------------------------------------

    /**
     * {@code min}/{@code max}, either over an iterable or over several arguments, with {@code key} and
     * {@code default}.
     *
     * The two forms share a name and a family of messages but not a rule: {@code default} only means
     * anything to the one-argument form, an argument given by keyword is not one of the arguments the
     * count is taken over, and the empty-iterable message names itself qualified.
     */
    private static PyObject extreme(PyObject[] args, String[] kwNames, boolean maximum) {
        String label = maximum ? "max" : "min";
        Arguments.atLeast(label, args, kwNames, 1);
        PyObject key = null;
        PyObject fallback = null;
        boolean hasFallback = false;
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject value = args[positional + i];
                switch (kwNames[i]) {
                    case "key" -> key = value;
                    case "default" -> {
                        fallback = value;
                        hasFallback = true;
                    }
                    default -> throw Slots.typeError(
                            "%s() got an unexpected keyword argument '%s'", label, kwNames[i]);
                }
            }
        }
        List<PyObject> items = new ArrayList<>();
        if (positional == 1) {
            // One argument is the iterable to look over, so `min(1)` is `iter(1)`'s complaint.
            items.addAll(Constructors.materialise(args[0]));
        } else {
            for (int i = 0; i < positional; i++) {
                items.add(args[i]);
            }
        }
        if (items.isEmpty()) {
            if (hasFallback) {
                return fallback;
            }
            throw Slots.valueError("%s() iterable argument is empty", label);
        }
        PyObject best = items.get(0);
        PyObject bestKey = keyed(key, best);
        for (int i = 1; i < items.size(); i++) {
            PyObject candidate = items.get(i);
            PyObject candidateKey = keyed(key, candidate);
            boolean better = Slots.compareTrue(
                    maximum ? CompareOp.GT : CompareOp.LT, candidateKey, bestKey);
            // CPython keeps the first of the equal ones for both min and max, so an improvement has
            // to be strictly better rather than merely not-worse.
            if (better) {
                best = candidate;
                bestKey = candidateKey;
            }
        }
        return best;
    }

    private static PyObject keyed(PyObject key, PyObject value) {
        return key == null || key == PyNone.INSTANCE
                ? value
                : Slots.call(key, new PyObject[] {value}, null);
    }

    private static PyObject sum(PyObject[] args, String[] kwNames) {
        Arguments.positionalAtLeast("sum", args, kwNames, 1);
        Arguments.atMostQualified("sum", args, kwNames, 2);
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (String kwName : kwNames) {
                if (!"start".equals(kwName)) {
                    throw Slots.typeError(
                            "sum() got an unexpected keyword argument '%s'", kwName);
                }
            }
        }
        PyObject total = positional > 1
                ? args[1]
                : keywordValue(kwNames, args, positional, "start", PyInt.ZERO);
        for (PyObject item : Constructors.materialise(args[0])) {
            total = Slots.binary(BinaryOp.ADD, total, item);
        }
        return total;
    }

    /**
     * {@code sorted(iterable, /, *, key=None, reverse=False)}.
     *
     * The keyword complaints name {@code sort}, not {@code sorted} — CPython's two share the argument
     * parser and it prints the list method's name — and every key is computed before the first
     * comparison, so a {@code key} that is not callable fails here rather than partway through the
     * sort, and one with side effects runs once per item.
     */
    private static PyObject sorted(PyObject[] args, String[] kwNames) {
        Arguments.exactly("sorted", args, kwNames, 1);
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (String kwName : kwNames) {
                if (!"key".equals(kwName) && !"reverse".equals(kwName)) {
                    throw Slots.typeError(
                            "sort() got an unexpected keyword argument '%s'", kwName);
                }
            }
        }
        PyObject key = keywordValue(kwNames, args, positional, "key", null);
        boolean reverse = Slots.truth(
                keywordValue(kwNames, args, positional, "reverse", PyBool.FALSE));
        List<PyObject> items = Constructors.materialise(args[0]);
        PyObject[] keys = new PyObject[items.size()];
        for (int i = 0; i < items.size(); i++) {
            keys[i] = keyed(key, items.get(i));
        }
        List<Integer> order = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        // Equal keys keep their original order in both directions, which a plain
        // comparator.reversed() would flip along with the comparison.
        Comparator<Integer> byKey = (left, right) -> {
            int step = reverse ? -1 : 1;
            if (Slots.compareTrue(CompareOp.LT, keys[left], keys[right])) {
                return -step;
            }
            if (Slots.compareTrue(CompareOp.LT, keys[right], keys[left])) {
                return step;
            }
            return Integer.compare(left, right);
        };
        order.sort(byKey);
        List<PyObject> out = new ArrayList<>(items.size());
        for (int index : order) {
            out.add(items.get(index));
        }
        return PyList.of(out.toArray(new PyObject[0]));
    }

    private static PyObject keywordValue(
            String[] kwNames, PyObject[] args, int positional, String name, PyObject absent) {
        if (kwNames == null) {
            return absent;
        }
        for (int i = 0; i < kwNames.length; i++) {
            if (kwNames[i].equals(name)) {
                return args[positional + i];
            }
        }
        return absent;
    }

    // --- types and attributes --------------------------------------------------------------

    /**
     * {@code isinstance(x, C)}: {@link Classes#instanceOf}, which is {@code object_recursive_isinstance}.
     *
     * <p>The builtin answers nothing of its own. That is what lets a metaclass take over the test, and
     * it is why the exact-class shortcut and the {@code __instancecheck__} hook behave the same whether
     * the question came from {@code isinstance}, from {@code type.__instancecheck__} or from a metaclass
     * that calls {@code super()} to get the ordinary answer.
     */
    private static PyObject isInstanceOf(PyObject value, PyObject classes) {
        return PyBool.of(Classes.instanceOf(value, classes));
    }

    /** {@code issubclass(A, B)}: {@link Classes#subclassOf}, which is {@code object_issubclass}. */
    private static PyObject isSubclass(PyObject child, PyObject classes) {
        return PyBool.of(Classes.subclassOf(child, classes));
    }

    private static PyObject getAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("getattr()", kwNames);
        Arguments.between("getattr", args, kwNames, 2, 3);
        String name = attributeName(args[1]);
        try {
            return Slots.getAttr(args[0], name);
        } catch (PyRaise failure) {
            if (args.length == 2
                    || !failure.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                throw failure;
            }
            return args[2];
        }
    }

    private static PyObject hasAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("hasattr()", kwNames);
        Arguments.exactly("hasattr", args, kwNames, 2);
        String name = attributeName(args[1]);
        try {
            Slots.getAttr(args[0], name);
            return PyBool.TRUE;
        } catch (PyRaise failure) {
            // hasattr reports a missing attribute as false and lets anything else through, which is
            // why a property that raises does not turn hasattr into false.
            if (failure.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                return PyBool.FALSE;
            }
            throw failure;
        }
    }

    private static PyObject setAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("setattr()", kwNames);
        Arguments.exactly("setattr", args, kwNames, 3);
        String name = attributeName(args[1]);
        PyObject answer = args[0].setAttr(name, args[2]);
        if (answer != PyNotImplemented.INSTANCE) {
            return PyNone.INSTANCE;
        }
        throw Slots.failedRead(
                args[0],
                name,
                "'%s' object has no attribute '%s' and no __dict__ for setting new attributes",
                args[0].getType().name(),
                name);
    }

    private static PyObject deleteAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("delattr()", kwNames);
        Arguments.exactly("delattr", args, kwNames, 2);
        String name = attributeName(args[1]);
        PyObject answer = args[0].delAttr(name);
        if (answer != PyNotImplemented.INSTANCE) {
            return PyNone.INSTANCE;
        }
        // The refusal of a delete from an object that has no dictionary is the same refusal a write makes,
        // and CPython names the object and the member on it too — unlike the delete of a name an instance's
        // own dictionary does not hold, which is complained about without.
        throw Slots.failedRead(
                args[0],
                name,
                "'%s' object has no attribute '%s' and no __dict__ for setting new attributes",
                args[0].getType().name(),
                name);
    }

    private static String attributeName(PyObject value) {
        if (value instanceof PyStr s) {
            return s.getValue();
        }
        throw Slots.typeError(
                "attribute name must be string, not '%s'", value.getType().name());
    }

    // --- namespaces ------------------------------------------------------------------------

    /**
     * {@code locals()}: the names of the scope Python is running, as 3.14's builtin answers them.
     *
     * <p>A module or a class body is answered with the very dictionary its names are written into, so
     * {@code locals() is globals()} holds at module level and {@code vars() is locals()} holds in a class
     * body — and a name added to either is a name the scope has. A function frame has no dictionary: 3.14
     * walks its slots, follows each cell to what it holds, leaves a name out while the slot is empty, and
     * hands back a plain dict made for the call, so writing into the answer writes into nothing.
     * {@code frame.f_locals} is the other shape again, CPython's write-back proxy, which this runtime does
     * not have.
     */
    private static PyObject localNames(PyObject[] args, String[] kwNames) {
        Arguments.none("locals()", args, kwNames);
        return currentLocals();
    }

    /** {@code globals()}: the module dictionary the running frame reads its names from, the live one. */
    private static PyObject globalNames(PyObject[] args, String[] kwNames) {
        Arguments.none("globals()", args, kwNames);
        Frame frame = Interpreter.currentFrame();
        return frame == null ? PyRuntime.mainModule().dictionary() : frame.globals();
    }

    /**
     * {@code vars([object])}: {@code locals()} without an argument, {@code object.__dict__} with one.
     *
     * <p>With an argument it is the attribute read and nothing more, so a value whose {@code __dict__} is
     * a property that answers a dictionary is given that dictionary, and only a value with no such member
     * is refused. A class answers its own namespace rather than CPython's read-only mappingproxy view of
     * it.
     */
    private static PyObject variables(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("vars()", kwNames);
        Arguments.atMost("vars", args, kwNames, 1);
        if (args.length == 0) {
            return currentLocals();
        }
        PyObject held = Slots.findAttr(args[0], "__dict__");
        if (held == null) {
            throw Slots.typeError("vars() argument must have __dict__ attribute");
        }
        return held;
    }

    /**
     * {@code dir([object])}: the names of the running scope, or the names one value answers with.
     *
     * <p>Both halves sort, and the argument half goes through {@link Dirs} for what the value's {@code
     * __dir__} returns — items in the order they arrive, duplicates kept, a refusal allowed to escape — or
     * for the default walk of the dictionaries when nothing declares one.
     */
    private static PyObject directory(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("dir()", kwNames);
        Arguments.atMost("dir", args, kwNames, 1);
        if (args.length == 0) {
            return sorted(new PyObject[] {currentLocals()}, null);
        }
        PyObject[] items = Dirs.itemsOf(args[0]).toArray(new PyObject[0]);
        return sorted(new PyObject[] {PyList.of(items)}, null);
    }

    /**
     * Whose names {@code locals()} is asked for.
     *
     * <p>Java code that reached a builtin from outside any frame is the case the frame cannot answer, and
     * CPython sends it to the globals of the interpreter's running {@code __main__} — which is the same
     * dictionary this runtime hands back, and the reason {@code globals()} is never {@code None} here.
     */
    private static PyObject currentLocals() {
        Frame frame = Interpreter.currentFrame();
        return frame == null ? PyRuntime.mainModule().dictionary() : frame.localValues();
    }

    // --- numeric builtins ------------------------------------------------------------------

    private static final String[] POW_NAMES = {"base", "exp", "mod"};
    private static final PyObject[] POW_DEFAULTS = {PyNone.INSTANCE};
    private static final String[] ROUND_NAMES = {"number", "ndigits"};
    private static final PyObject[] ROUND_DEFAULTS = {PyNone.INSTANCE};

    /**
     * {@code pow(base, exp, mod=None)}.
     *
     * All three are positional-or-keyword, so {@code pow(2, exp=3)} is 8. The two-argument form is the
     * {@code **} operator's but names both spellings in its message; the three-argument form is
     * integers only, and a negative exponent asks for a modular inverse rather than a fraction, which
     * is what makes {@code pow(2, -1, 5)} 3. A negative modulus keeps Python's floor-remainder sign,
     * which {@code BigInteger.mod} alone cannot give.
     */
    private static PyObject power(PyObject[] args, String[] kwNames) {
        Arguments.atMostQualified("pow", args, kwNames, 3);
        PyObject[] given = Arguments.fill("pow", args, kwNames, POW_NAMES, POW_DEFAULTS);
        Arguments.required("pow", POW_NAMES, given, 0);
        Arguments.required("pow", POW_NAMES, given, 1);
        PyObject base = given[0];
        PyObject exponent = given[1];
        PyObject modulus = given[2];
        if (modulus == null || modulus == PyNone.INSTANCE) {
            if (!isNumber(base) || !isNumber(exponent)) {
                throw Slots.typeError(
                        "unsupported operand type(s) for ** or pow(): '%s' and '%s'",
                        base.getType().name(), exponent.getType().name());
            }
            return Slots.binary(BinaryOp.POW, base, exponent);
        }
        BigInteger b = Numbers.asInteger(base);
        BigInteger e = Numbers.asInteger(exponent);
        BigInteger m = Numbers.asInteger(modulus);
        if (b == null || e == null || m == null) {
            if (isNumber(base) && isNumber(exponent) && isNumber(modulus)) {
                throw Slots.typeError(
                        "pow() 3rd argument not allowed unless all arguments are integers");
            }
            throw Slots.typeError(
                    "unsupported operand type(s) for ** or pow(): '%s', '%s', '%s'",
                    base.getType().name(), exponent.getType().name(), modulus.getType().name());
        }
        if (m.signum() == 0) {
            throw Slots.valueError("pow() 3rd argument cannot be 0");
        }
        boolean floorRemainder = m.signum() < 0;
        m = m.abs();
        BigInteger answer;
        if (e.signum() < 0) {
            if (!b.gcd(m).equals(BigInteger.ONE)) {
                throw Slots.valueError("base is not invertible for the given modulus");
            }
            answer = b.mod(m).modInverse(m).modPow(e.negate(), m);
        } else {
            answer = b.mod(m).modPow(e, m);
        }
        return PyInt.of(floorRemainder && answer.signum() != 0
                ? answer.subtract(m)
                : answer);
    }

    private static boolean isNumber(PyObject value) {
        return value instanceof PyInt
                || value instanceof PyBool
                || value instanceof PyFloat
                || value instanceof PyComplex;
    }

    /**
     * {@code round(number, ndigits=None)}.
     *
     * Halfway values go to the even neighbour, which is IEEE's default and CPython's rule, and is why
     * {@code round(2.5)} is 2 while {@code round(3.5)} is 4. The rounding is done on the double's exact
     * binary value rather than on its shortest decimal spelling, because that is the difference between
     * {@code round(2.675, 2)} being 2.67 as Python says and 2.68 as a naive decimal would give.
     *
     * <p>Without {@code ndigits} the answer is an {@code int}, with it a float — even at
     * {@code ndigits=0}, where {@code round(1.5, 0)} is 2.0. An {@code ndigits} too large for the value
     * to have that many places is clipped, which is what makes {@code round(1, 2**70)} answer 1 instead
     * of building a power of ten no machine has.
     */
    private static PyObject round(PyObject[] args, String[] kwNames) {
        Arguments.atMostQualified("round", args, kwNames, 2);
        PyObject[] given = Arguments.fill("round", args, kwNames, ROUND_NAMES, ROUND_DEFAULTS);
        Arguments.required("round", ROUND_NAMES, given, 0);
        PyObject value = given[0];
        boolean place = given[1] != null && given[1] != PyNone.INSTANCE;
        int at = place ? places(integerOrRefusal(given[1])) : 0;
        if (value instanceof PyInt i) {
            return place && at < 0 ? PyInt.of(roundInteger(i.getValue(), at)) : i;
        }
        if (value instanceof PyBool b) {
            return b.getValue() ? PyInt.ONE : PyInt.ZERO;
        }
        if (value instanceof PyFloat f) {
            double number = f.getValue();
            if (!Double.isFinite(number)) {
                if (place) {
                    return f;
                }
                if (Double.isNaN(number)) {
                    throw Slots.valueError("cannot convert float NaN to integer");
                }
                throw Slots.overflowError("cannot convert float infinity to integer");
            }
            java.math.BigDecimal exact = new java.math.BigDecimal(number);
            if (place) {
                return PyFloat.of(
                        exact.setScale(at, java.math.RoundingMode.HALF_EVEN).doubleValue());
            }
            return PyInt.of(exact.setScale(0, java.math.RoundingMode.HALF_EVEN).toBigInteger());
        }
        throw Slots.typeError(
                "type %s doesn't define __round__ method", value.getType().name());
    }

    private static final int PLACE_CLIP = 10000;

    /** {@code ndigits} as an {@code int}, with the answers that cannot differ folded together. */
    private static int places(BigInteger ndigits) {
        if (ndigits.abs().compareTo(BigInteger.valueOf(PLACE_CLIP)) <= 0) {
            return ndigits.intValue();
        }
        return ndigits.signum() < 0 ? -PLACE_CLIP : PLACE_CLIP;
    }

    /** {@code round(int, -n)}, which is CPython's integer rounding to a power-of-ten place. */
    private static BigInteger roundInteger(BigInteger value, int places) {
        if (places >= 0) {
            return value;
        }
        BigInteger unit = BigInteger.TEN.pow(-places);
        BigInteger[] parts = value.divideAndRemainder(unit);
        BigInteger twice = parts[1].abs().shiftLeft(1);
        int compare = twice.compareTo(unit);
        if (compare < 0 || (compare == 0 && !parts[0].testBit(0))) {
            return parts[0].multiply(unit);
        }
        // Away from zero, in whichever direction the value points: -1350 goes to -1400, not to -1200.
        BigInteger neighbour = value.signum() < 0
                ? parts[0].subtract(BigInteger.ONE)
                : parts[0].add(BigInteger.ONE);
        return neighbour.multiply(unit);
    }
}
