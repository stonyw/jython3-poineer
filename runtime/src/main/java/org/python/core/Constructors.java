package org.python.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calling a type, which is how every value in the language is built: {@code int(x)},
 * {@code list(iterable)}, {@code ValueError("boom")}.
 *
 * Dispatch is by identity against {@link PyType}'s constants — the same thing CPython does with a
 * {@code tp_new} slot per type, at the same cost. Exception classes are the one group matched by
 * relation rather than by enumeration: any class under {@code BaseException} builds a
 * {@link PyBaseException}, so {@code class MyError(ValueError)} needs nothing new here once the class
 * system lands at M3.
 *
 * Every refusal is worded as CPython 3.14 words it, and that is not decoration. The builtins belong
 * to three different argument-signature families and each says so differently: {@code int} and
 * {@code bytes} name their parameters ({@code int() got an unexpected keyword argument 'x'}),
 * {@code list}, {@code range} and {@code slice} refuse keywords outright
 * ({@code range() takes no keyword arguments}), and the legacy count family names itself bare
 * ({@code bool expected at most 1 argument, got 2}). CPython's own tests read these strings.
 */
final class Constructors {

    private Constructors() {
    }

    /**
     * {@code type(args, **kw)} for a type the runtime builds in Java.
     *
     * <p>{@link Classes#typeCall} reaches here once {@code type} itself and every class Python wrote have
     * been answered, so this is the one place that knows each built-in's constructor by identity rather
     * than guessing per class.
     */
    static PyObject instantiate(PyType type, PyObject[] args, PyTuple kwNames) {
        String[] keywordNames = PyBoundMethod.keywordNames(kwNames);
        if (type.matches(ExceptionTypes.BASE_EXCEPTION)) {
            return exception(type, args, kwNames);
        }
        if (type instanceof PyClass kind) {
            return Classes.construct(kind, args, kwNames);
        }
        if (type == PyType.BOOL) {
            return booleanValue(args, keywordNames);
        }
        if (type == PyType.INT) {
            return integer(args, keywordNames);
        }
        if (type == PyType.FLOAT) {
            return floating(args, keywordNames);
        }
        if (type == PyType.COMPLEX) {
            return complexValue(args, keywordNames);
        }
        if (type == PyType.STR) {
            return text(args, keywordNames);
        }
        if (type == PyType.BYTES) {
            return bytes(args, keywordNames);
        }
        if (type == PyType.TUPLE) {
            List<PyObject> source = iterable(args, keywordNames, "tuple");
            // `tuple(t)` of a tuple is the one object rather than a copy of it: `PySequence_Tuple` keeps what
            // it is handed for a `PyTuple_CheckExact`, which is what makes `tuple(pair) is pair` hold while
            // `list(values) is values` never does. A subclass is not exact, so `tuple(version_info)` builds a
            // plain tuple and the value it came from is not what comes back.
            if (args.length == 1 && args[0].getType() == PyType.TUPLE) {
                return (PyTuple) args[0];
            }
            return PyTuple.of(source.toArray(new PyObject[0]));
        }
        if (type == PyType.LIST) {
            return PyList.of(iterable(args, keywordNames, "list").toArray(new PyObject[0]));
        }
        if (type == PyType.SET) {
            return PySet.collected(single(args, keywordNames, "set"));
        }
        if (type == PyType.FROZENSET) {
            return PyFrozenSet.collected(single(args, keywordNames, "frozenset"));
        }
        if (type == PyType.DICT) {
            return mapping(args, keywordNames);
        }
        if (type == PyType.RANGE) {
            return range(args, keywordNames);
        }
        if (type == PyType.SLICE) {
            return slice(args, keywordNames);
        }
        if (type == PyType.PROPERTY) {
            return property(args, keywordNames);
        }
        if (type == PyType.CLASS_METHOD) {
            return classMethod(args, keywordNames);
        }
        if (type == PyType.STATIC_METHOD) {
            return staticMethod(args, keywordNames);
        }
        if (type == PyType.SUPER) {
            return superProxy(args, keywordNames);
        }
        throw Slots.notImplemented("calling '" + type.name() + "'");
    }

    // --- the descriptor builtins ---------------------------------------------------------------

    /**
     * {@code property(fget, fset, fdel, doc)}.
     *
     * <p>The four arguments may all be given by name, and the ones a call leaves out are read as the ones
     * that are not there — including {@code doc}, whose absence does not mean "no docstring" but "go and ask
     * the getter". The parser is the same one the {@code __init__} row runs, which is what puts {@code
     * property} in the qualified family rather than the legacy one {@code classmethod} belongs to and makes a
     * fifth argument refused in the same words whichever of the two ways the property was made.
     */
    private static PyObject property(PyObject[] args, String[] kwNames) {
        PyObject[] given =
                Arguments.unpackKeywords(
                        "property",
                        args,
                        kwNames,
                        PyProperty.PARAMETERS,
                        0,
                        PyProperty.PARAMETERS.length);
        return new PyProperty(given[0], given[1], given[2], given[3]);
    }

    /**
     * {@code classmethod(func)} and {@code staticmethod(func)}: one argument, never by name.
     *
     * Both are still written against {@code _PyArg_UnpackStack}, so the refusal says {@code classmethod
     * expected 1 argument, got 2} with nothing qualified about it, and naming the argument is refused
     * before the count is even looked at.
     */
    private static PyObject classMethod(PyObject[] args, String[] kwNames) {
        oneArgument("classmethod", args, kwNames);
        return new PyClassMethod(args[0]);
    }

    private static PyObject staticMethod(PyObject[] args, String[] kwNames) {
        oneArgument("staticmethod", args, kwNames);
        return new PyStaticMethod(args[0]);
    }

    private static void oneArgument(String name, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords(name + "()", kwNames);
        Arguments.exactly(name, args, kwNames, 1);
    }

    /**
     * {@code super()}, {@code super(C)}, {@code super(C, x)}.
     *
     * <p>Three counts, three meanings, and only the middle one looks at nothing: {@code super(C)} holds
     * the class with no value, which is a proxy that can read {@code __thisclass__} and no member, even
     * when a frame would have something to say. The one-argument form is why {@code repr(super(Right))}
     * prints {@code NULL} inside a method — only the empty call asks the frame, in the legacy family's
     * own words about its arity.
     */
    private static PyObject superProxy(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("super()", kwNames);
        Arguments.atMost("super()", args, kwNames, 2);
        return switch (args.length) {
            case 0 -> PySuper.fromFrame();
            case 1 -> PySuper.withoutValue(args[0]);
            default -> PySuper.of(args[0], args[1]);
        };
    }

    /**
     * {@code ValueError("x")} and every subclass of it.
     *
     * An exception keeps its arguments as given because {@code str(e)} and {@code e.args} are both
     * derived from them, and neither is cooked here.
     *
     * <p>A call of a class of the family is two steps and not one, because CPython's {@code type_call}
     * runs {@code tp_new} and then {@code tp_init}: the creator takes the positionals and no notice of the
     * names, and the initialiser is the method that fills a field from a name, keeps a value made by the
     * creator alone, or refuses. {@code Constructors} is where a built-in class takes both steps; a class
     * Python wrote takes them in {@link Classes#construct}, which reaches the same two rows through the
     * method resolution order.
     *
     * <p>A group is the one exception family whose creator reads only two of the arguments and then
     * refuses the rest itself, so the call is handed to {@link PyBaseExceptionGroup} whole: the
     * positional prefix because that is what its {@code PyArg_ParseTuple} table sees, and the fact that
     * anything was named separately because the refusal for that is made afterwards, by {@code __init__},
     * under the class the value was made as.
     */
    private static PyObject exception(PyType type, PyObject[] args, PyTuple keywords) {
        String[] kwNames = PyBoundMethod.keywordNames(keywords);
        if (type.matches(ExceptionTypes.BASE_EXCEPTION_GROUP)) {
            return PyBaseExceptionGroup.create(
                    type,
                    Arguments.positionals(args, kwNames),
                    kwNames != null && kwNames.length > 0);
        }
        if (type.matches(ExceptionTypes.OS_ERROR)) {
            // The one family whose creator reads its arguments rather than keeping them, and whose value
            // may be of a narrower class than the one that was called. `OSError_new` has a keyword table
            // and is the only creator of the family that refuses from it, before a value is made.
            if (kwNames != null) {
                throw Slots.typeError("%s() takes no keyword arguments", type.name());
            }
            return PyOSError.create(type, args);
        }
        // CPython's two steps, in its order: `BaseException_new` keeps the positionals and throws the
        // names away without looking at them, and the `__init__` the order answers with is the method that
        // has something to say about a named argument. That is the whole of the difference between
        // `NameError('m', name='n')`, which fills a field, and `ValueError('m', name='n')`, which refuses
        // in `BaseException_init` under the class the value was made as.
        PyBaseException made = new PyBaseException(type, Arguments.positionals(args, kwNames));
        PyObject declared = Specials.member(type, "__init__");
        if (declared == null) {
            return made;
        }
        PyObject[] withReceiver = new PyObject[args.length + 1];
        withReceiver[0] = made;
        System.arraycopy(args, 0, withReceiver, 1, args.length);
        Slots.call(declared, withReceiver, keywords);
        return made;
    }

    // --- scalars ---------------------------------------------------------------------------

    private static PyObject booleanValue(PyObject[] args, String[] kwNames) {
        refuseKeywords("bool", kwNames);
        Arguments.atMost("bool", args, kwNames, 1);
        return booleanOf(args.length == 0 ? null : args[0]);
    }

    /**
     * {@code bool(x)}, and {@code False} when the call gave no x at all.
     *
     * <p>The two spellings of the creator share this: {@code bool([1])} runs it over the list, and
     * {@code bool.__new__(bool, [1])} runs it over what its own parser found.
     */
    static PyObject booleanOf(PyObject value) {
        return PyBool.of(Slots.truth(value == null ? PyNone.INSTANCE : value));
    }

    /**
     * The names {@code int.__new__}'s parser answers to, the first of them position-only.
     *
     * <p>The empty slot is what CPython's {@code new_kwtuple} keeps for {@code x}: {@code int(x=1)} is
     * therefore about a name the signature does not have rather than about the parameter behind it.
     */
    private static final String[] INT_NAMES = {"", "base"};

    /**
     * {@code int()}, {@code int(x)}, {@code int(x, base)}.
     *
     * <p>{@code long_vectorcall} answers a call that named nothing itself — zero, {@code PyNumber_Long}
     * over one argument, the two-argument implementation over two — and only hands a call with a keyword to
     * {@code _PyObject_MakeTpCall} and the clinic parser. That is why the same wrong count is complained
     * about twice over: {@code int(2, 3, 4)} says {@code int expected at most 2 arguments, got 3} and
     * {@code int(2, 3, x=4)} says {@code int() takes at most 2 arguments (3 given)}.
     *
     * <p>Whether the base was <em>given</em> matters more than what it holds: {@code int(2**70, 10)} is a
     * {@code TypeError} while {@code int(2**70)} is not, because naming a base commits the call to the
     * string form. That is also why {@code x} is read positionally and only {@code base} by name.
     */
    private static PyObject integer(PyObject[] args, String[] kwNames) {
        if (kwNames == null) {
            return switch (args.length) {
                case 0 -> PyInt.ZERO;
                case 1 -> integerOf(args[0], null);
                case 2 -> integerOf(args[0], args[1]);
                default ->
                        throw Slots.typeError("int expected at most 2 arguments, got %d", args.length);
            };
        }
        return parsedInteger(args, kwNames);
    }

    /**
     * {@code long_new}'s own argument table: the two slots {@code int}'s signature names, either absent.
     *
     * <p>This is the parser a call through {@code int.__new__} reaches rather than the one {@code
     * long_vectorcall} skips, so its refusals are the clinic ones — {@code int() takes at most 2 arguments
     * (3 given)} where the fast path says {@code int expected at most 2 arguments, got 3}.
     */
    static PyObject parsedInteger(PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords("int", args, kwNames, INT_NAMES, 0, 2);
        return integerOf(given[0], given[1]);
    }

    /**
     * {@code long_new} over the two arguments a parser found, either of which may be absent.
     *
     * <p>A null {@code value} is a call that gave nothing where x belongs, which is zero when no base was
     * named and the complaint about a missing string when one was: the base is what commits the call to the
     * literal form, so an x that never arrived is only missing in that case.
     *
     * <p>The base is read and range-checked <em>before</em> {@code x} is looked at, which is what puts the
     * number a base should be ahead of the value it has nothing to do with: {@code int(5, 1)} is about the
     * base being one, not about five not being a string, and {@code int('1', True)} complains about the
     * same base although the value is exactly the literal that base would read.
     */
    static PyObject integerOf(PyObject value, PyObject base) {
        if (value == null) {
            if (base == null) {
                return PyInt.ZERO;
            }
            throw Slots.typeError("int() missing string argument");
        }
        BigInteger radix = base == null ? null : readBase(base);
        String literal = textual(value);
        if (literal != null) {
            return parseInteger(literal, radix, value);
        }
        if (radix != null) {
            throw Slots.typeError("int() can't convert non-string with explicit base");
        }
        return integerFromNumber(value);
    }

    /**
     * {@code PyNumber_AsSsize_t} over the base and the range {@code long_new_impl} allows it.
     *
     * <p>Zero is legal although no literal is read in base zero, because zero asks for the prefix to say
     * the base; every other legal value is a radix, and both ends of the refusal are measured.
     */
    private static BigInteger readBase(PyObject base) {
        BigInteger radix = interpretedAsInteger(base);
        if (!radix.equals(BigInteger.ZERO)
                && (radix.compareTo(BigInteger.TWO) < 0 || radix.compareTo(THIRTY_SIX) > 0)) {
            throw Slots.valueError("int() base must be >= 2 and <= 36, or 0");
        }
        return radix;
    }

    /** {@code int(x)} where {@code x} is neither text nor absent. */
    private static PyObject integerFromNumber(PyObject value) {
        BigInteger exact = Numbers.asInteger(value);
        if (exact != null) {
            // PyNumber_Long hands the int it was given back rather than rebuilding it, which is
            // what makes `int(x) is x` hold for a value too big for the small-int cache. A bool is not
            // that int: it answers with the number it stands for.
            return value instanceof PyInt i ? i : PyInt.of(exact);
        }
        if (value instanceof PyFloat f) {
            double number = f.getValue();
            if (Double.isNaN(number)) {
                throw Slots.valueError("cannot convert float NaN to integer");
            }
            if (Double.isInfinite(number)) {
                throw Slots.overflowError("cannot convert float infinity to integer");
            }
            // BigDecimal.valueOf keeps the double's own decimal value, so this truncates rather
            // than rounds — which is what int(1.9) == 1 and int(-1.9) == -1 both need.
            return PyInt.of(BigDecimal.valueOf(number).toBigInteger());
        }
        throw Slots.typeError(
                "int() argument must be a string, a bytes-like object or a real number, not '%s'",
                value.getType().name());
    }

    private static final BigInteger THIRTY_SIX = BigInteger.valueOf(36);

    /**
     * {@code PyLong_FromUnicodeObject} over a literal whose base {@link #readBase} has already allowed.
     *
     * <p>A null base is the one case the call left out, which is ten and not zero: {@code int('0x12')} is
     * about the {@code x} that no base ten has a digit for.
     */
    private static PyObject parseInteger(String literal, BigInteger base, PyObject shown) {
        int radix = base == null ? 10 : base.intValue();
        BigInteger value = decodeInteger(digitNormalised(literal.strip()), radix);
        if (value == null) {
            throw Slots.valueError(
                    "invalid literal for int() with base %d: %s", radix, shown.repr());
        }
        return PyInt.of(value);
    }

    /**
     * CPython's integer grammar: an optional sign, a base prefix when the radix is 0 or matches, digits
     * in that radix, and underscores only ever between two digits.
     *
     * @param literal already stripped of surrounding whitespace, which CPython allows and this keeps out
     *     of the grammar
     * @return the value, or null for {@code invalid literal}
     */
    private static BigInteger decodeInteger(String literal, int radix) {
        int at = 0;
        int end = literal.length();
        boolean negative = at < end && literal.charAt(at) == '-';
        if (negative || (at < end && literal.charAt(at) == '+')) {
            at++;
        }
        int actual = radix;
        if (at + 1 < end && literal.charAt(at) == '0') {
            char prefix = Character.toLowerCase(literal.charAt(at + 1));
            if (radix == 0) {
                // The prefix selects the radix, and a plain "0" or "00" stays decimal.
                if (prefix == 'x') {
                    actual = 16;
                } else if (prefix == 'o') {
                    actual = 8;
                } else if (prefix == 'b') {
                    actual = 2;
                } else {
                    actual = 10;
                }
                if (actual != 10) {
                    at += 2;
                }
            } else if (prefix == matchedBy(radix)) {
                at += 2;
            }
        } else if (radix == 0) {
            actual = 10;
        }
        if (actual < 2 || actual > 36) {
            return null;
        }
        BigInteger out = BigInteger.ZERO;
        BigInteger baseValue = BigInteger.valueOf(actual);
        boolean any = false;
        boolean previousUnderscore = false;
        while (at < end) {
            char c = literal.charAt(at++);
            if (c == '_') {
                // A separator has to sit between two digits: not leading, not trailing, not doubled.
                if (!any || previousUnderscore || at >= end) {
                    return null;
                }
                previousUnderscore = true;
                continue;
            }
            int digit = Character.digit(c, actual);
            if (digit < 0) {
                return null;
            }
            out = out.multiply(baseValue).add(BigInteger.valueOf(digit));
            any = true;
            previousUnderscore = false;
        }
        if (!any) {
            return null;
        }
        return negative ? out.negate() : out;
    }

    /** The prefix letter an explicit radix accepts, or a letter no radix spells a prefix with. */
    private static char matchedBy(int radix) {
        return switch (radix) {
            case 16 -> 'x';
            case 8 -> 'o';
            case 2 -> 'b';
            default -> '\0';
        };
    }

    /**
     * An argument used as a number where CPython demands an integer, which is its own refusal because
     * {@code __index__} is a different thing from {@code __int__}.
     */
    private static BigInteger interpretedAsInteger(PyObject value) {
        BigInteger exact = Numbers.asInteger(value);
        if (exact == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().name());
        }
        return exact;
    }

    /**
     * {@code float_new}'s whole argument table, which the constructor and {@code float.__new__} share.
     *
     * <p>{@code float_vectorcall} and clinic's {@code float_new} parser are the same three lines — keywords,
     * then count, then the value — so both spellings of an over-long call give the same legacy sentence,
     * {@code float expected at most 1 argument, got 2}, and neither names a parameter. A float is
     * {@code PyNumber_Float}'s one argument that needs no conversion at all: it is the value that comes
     * back, because {@code nb_float} is {@code float_float} and hands the receiver out.
     */
    static PyObject floating(PyObject[] args, String[] kwNames) {
        refuseKeywords("float", kwNames);
        Arguments.atMost("float", args, kwNames, 1);
        if (args.length == 0) {
            return PyFloat.of(0.0);
        }
        String literal = textual(args[0]);
        if (literal != null) {
            Double parsed = decodeFloat(literal.strip());
            if (parsed == null) {
                throw Slots.valueError(
                        "could not convert string to float: %s", args[0].repr());
            }
            return PyFloat.of(parsed);
        }
        if (!Numbers.isReal(args[0])) {
            throw Slots.typeError(
                    "float() argument must be a string or a real number, not '%s'",
                    args[0].getType().name());
        }
        if (args[0] instanceof PyFloat number) {
            return number;
        }
        return PyFloat.of(Numeric.asDouble(args[0]));
    }

    /**
     * CPython's float grammar: {@code inf}, {@code infinity} and {@code nan} in either case with an
     * optional sign, digits with at most one point and one exponent, and underscores only between two
     * digits.
     *
     * Java's {@code Double.parseDouble} is close but not equal: it reads {@code 0x1p3} as a hex float,
     * accepts a trailing {@code d} or {@code f}, and knows only ASCII digits, while CPython takes any
     * digit Unicode marks as decimal ({@code float('١٢')} is 12.0). Digits are therefore normalised and
     * the two spellings Java would read are refused before it is asked. Overflow is not refused:
     * {@code float('1e400')} is positive infinity in Python too.
     */
    private static Double decodeFloat(String literal) {
        String text = digitNormalised(literal);
        if (text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '_'
                    && !(i > 0
                            && isDigit(text.charAt(i - 1))
                            && i + 1 < text.length()
                            && isDigit(text.charAt(i + 1)))) {
                return null;
            }
        }
        String withoutSeparators = text.replace("_", "");
        int at = 0;
        boolean negative = false;
        if (withoutSeparators.charAt(0) == '-' || withoutSeparators.charAt(0) == '+') {
            negative = withoutSeparators.charAt(0) == '-';
            at = 1;
        }
        String body = withoutSeparators.substring(at).toLowerCase(Locale.ROOT);
        if (body.equals("inf") || body.equals("infinity")) {
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (body.equals("nan")) {
            return Double.NaN;
        }
        if (body.indexOf('x') >= 0 || body.indexOf('d') >= 0 || body.indexOf('f') >= 0) {
            return null;
        }
        try {
            return Double.parseDouble(withoutSeparators);
        } catch (NumberFormatException notAFloat) {
            return null;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Every Unicode decimal digit rewritten as its ASCII equivalent, which is what lets {@code int} and
     * {@code float} read {@code '١٢'} and {@code '１２'} as 12.
     */
    private static String digitNormalised(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int decimal = Character.digit(c, 10);
            out.append(isDigit(c) || decimal < 0 ? c : (char) ('0' + decimal));
        }
        return out.toString();
    }

    private static PyObject complexValue(PyObject[] args, String[] kwNames) {
        acceptKeywordsOnly("complex", kwNames, "real", "imag");
        int given = Arguments.count(args, kwNames);
        if (given > 2) {
            throw Slots.typeError("complex() takes at most 2 arguments (%d given)", given);
        }
        int positional = Arguments.positional(args, kwNames);
        PyObject real = positional > 0
                ? args[0]
                : keywordOr(kwNames, args, positional, "real", PyInt.ZERO);
        boolean imagGiven = positional > 1 || names(kwNames, "imag");
        if (!imagGiven && real instanceof PyStr s) {
            // complex("1+2j") is a parse, and only when no imaginary part was also given — otherwise
            // the string is simply the wrong kind of first argument.
            PyComplex parsed = decodeComplex(s.getValue());
            if (parsed == null) {
                throw Slots.valueError("complex() arg is a malformed string");
            }
            return parsed;
        }
        if (!imagGiven && !Numbers.isReal(real)) {
            throw Slots.typeError(
                    "complex() argument must be a string or a number, not %s",
                    real.getType().name());
        }
        PyObject imag = positional > 1 ? args[1] : keywordOr(kwNames, args, positional, "imag", PyInt.ZERO);
        double realPart = realArgument("real", real);
        double imagPart = realArgument("imag", imag);
        if (imag instanceof PyComplex) {
            // complex(1, 2j) is 1 + 2j*i, which is -1: CPython multiplies the second argument by i
            // rather than taking it as the imaginary part when it is already complex.
            PyComplex second = (PyComplex) imag;
            return PyComplex.of(realPart - second.imaginary(), second.real());
        }
        return PyComplex.of(realPart, imagPart);
    }

    private static double realArgument(String name, PyObject value) {
        if (Numbers.isReal(value)) {
            return Numeric.asDouble(value);
        }
        throw Slots.typeError(
                "complex() argument '%s' must be a real number, not %s",
                name, value.getType().name());
    }

    /**
     * {@code complex(text)}: an optional real part, an optional sign, an imaginary part ending in
     * {@code j}, or either part alone.
     *
     * Whitespace inside the number is refused, so {@code "1 - 2j"} is malformed while
     * {@code " 1+2j "} is not — CPython strips the ends and then parses without skipping.
     */
    private static PyComplex decodeComplex(String text) {
        String literal = text.strip();
        if (literal.length() >= 2
                && literal.charAt(0) == '('
                && literal.charAt(literal.length() - 1) == ')') {
            literal = literal.substring(1, literal.length() - 1).strip();
        }
        if (literal.isEmpty() || containsInnerSpace(literal)) {
            return null;
        }
        int split = lastSign(literal);
        if (split < 0) {
            if (!literal.endsWith("j") && !literal.endsWith("J")) {
                Double alone = decodeFloat(literal);
                return alone == null ? null : PyComplex.of(alone, 0.0);
            }
            return imaginaryOnly(literal);
        }
        String head = literal.substring(0, split);
        String tail = literal.substring(split);
        if (!tail.endsWith("j") && !tail.endsWith("J")) {
            return null;
        }
        Double real = decodeFloat(head);
        Double imaginary = decodeFloat(tail.substring(0, tail.length() - 1));
        if (real == null || imaginary == null) {
            return null;
        }
        return PyComplex.of(real, imaginary);
    }

    /** A literal whose whole content is the imaginary part, where an empty or bare sign means one. */
    private static PyComplex imaginaryOnly(String literal) {
        String body = literal.substring(0, literal.length() - 1);
        double value = 1.0;
        boolean negative = false;
        if (!body.isEmpty() && (body.charAt(0) == '-' || body.charAt(0) == '+')) {
            negative = body.charAt(0) == '-';
            if (body.length() == 1) {
                return PyComplex.of(0.0, negative ? -1.0 : 1.0);
            }
            body = body.substring(1);
        }
        Double parsed = decodeFloat(body);
        if (parsed == null) {
            return null;
        }
        return PyComplex.of(0.0, negative ? -parsed : parsed);
    }

    /** Where the imaginary part starts: the last sign that is not an exponent's and not the first. */
    private static int lastSign(String literal) {
        for (int i = literal.length() - 1; i > 0; i--) {
            char c = literal.charAt(i);
            if ((c == '+' || c == '-') && !isExponent(literal.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isExponent(char before) {
        return before == 'e' || before == 'E';
    }

    private static boolean containsInnerSpace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // --- sequences and mappings ------------------------------------------------------------

    /** {@code list(x)}, {@code tuple(x)}, {@code set(x)}, {@code frozenset(x)} — all one iterable. */
    private static List<PyObject> iterable(PyObject[] args, String[] kwNames, String name) {
        refuseKeywords(name, kwNames);
        Arguments.atMost(name, args, kwNames, 1);
        if (args.length == 0) {
            return List.of();
        }
        return materialise(args[0]);
    }

    /**
     * The one argument a set constructor is given, handed over rather than walked out here.
     *
     * <p>{@code set_vectorcall} and {@code frozenset_vectorcall} make the same two refusals and then pass
     * the argument straight to {@code make_new_set}, which adds to the value it is building as it goes. The
     * difference shows on the way in — {@code set(x=1)} is refused before anything is counted — and leaves
     * nothing observable behind, since a set abandoned half-filled is never seen again.
     */
    private static PyObject single(PyObject[] args, String[] kwNames, String name) {
        refuseKeywords(name, kwNames);
        Arguments.atMost(name, args, kwNames, 1);
        return args.length == 0 ? null : args[0];
    }

    /**
     * The one argument of {@code list(iterable)} as {@code list_init} reads it: the same two refusals, and
     * the iterable itself rather than a copy of it. {@code list___init___impl} empties the receiver before it
     * extends from the argument, so the slot has to be handed the argument and not what it holds — the list
     * it reads is the one that was just thrown away.
     */
    static PyObject listed(PyObject[] args, String[] kwNames) {
        refuseKeywords("list", kwNames);
        Arguments.atMost("list", args, kwNames, 1);
        return args.length == 0 ? null : args[0];
    }

    /** Every value of an iterable, in order. Shared with the interpreter's own {@code [*x]} paths. */
    static List<PyObject> materialise(PyObject source) {
        List<PyObject> out = new ArrayList<>();
        PyIterator iterator = Slots.iterator(source);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            out.add(item);
        }
        return out;
    }

    /**
     * {@code dict()}, {@code dict(mapping)}, {@code dict(sequence_of_pairs)}, {@code dict(**kw)}.
     *
     * Every keyword becomes a key as it is, which is why {@code dict(self=1)} is a dict with the key
     * {@code "self"} — the name is not checked against anything.
     */
    private static PyObject mapping(PyObject[] args, String[] kwNames) {
        Arguments.atMost("dict", args, kwNames, 1);
        PyDict out = PyDict.empty();
        int positional = Arguments.positional(args, kwNames);
        if (positional > 0) {
            out.merge(args[0]);
        }
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                out.put(PyStr.of(kwNames[i]), args[positional + i]);
            }
        }
        return out;
    }

    /** {@code range(stop)} / {@code range(start, stop[, step])}, which is one to three integers. */
    private static PyObject range(PyObject[] args, String[] kwNames) {
        refuseKeywords("range", kwNames);
        Arguments.between("range", args, kwNames, 1, 3);
        BigInteger start = BigInteger.ZERO;
        BigInteger stop;
        BigInteger step = BigInteger.ONE;
        if (args.length == 1) {
            stop = interpretedAsInteger(args[0]);
        } else {
            start = interpretedAsInteger(args[0]);
            stop = interpretedAsInteger(args[1]);
            if (args.length == 3) {
                step = interpretedAsInteger(args[2]);
            }
        }
        return PyRange.of(start, stop, step);
    }

    /** {@code slice(stop)}, {@code slice(start, stop)}, {@code slice(start, stop, step)}. */
    private static PyObject slice(PyObject[] args, String[] kwNames) {
        refuseKeywords("slice", kwNames);
        Arguments.between("slice", args, kwNames, 1, 3);
        PyObject none = PyNone.INSTANCE;
        return switch (Arguments.count(args, kwNames)) {
            case 1 -> PySlice.of(none, args[0], none);
            case 2 -> PySlice.of(args[0], args[1], none);
            default -> PySlice.of(args[0], args[1], args[2]);
        };
    }

    // --- text ------------------------------------------------------------------------------

    private static final String[] STR_NAMES = {"object", "encoding", "errors"};
    private static final PyObject[] STR_DEFAULTS = {
        PyStr.of(""), PyStr.of("utf-8"), PyStr.of("strict"),
    };

    /**
     * {@code str(x)}, and the decoding form {@code str(b, encoding, errors)}.
     *
     * The two forms are told apart by whether a second argument arrived at all, not by what the first
     * one is: {@code str(1, "utf-8")} is a decoding error about the {@code int}. The extra arguments'
     * types are checked first, which is why {@code str(1, 2)} complains about {@code encoding} rather
     * than about the number, and four arguments is the legacy count message instead.
     */
    private static PyObject text(PyObject[] args, String[] kwNames) {
        Arguments.atMost("str", args, kwNames, 3);
        acceptKeywordsOnly("str", kwNames, STR_NAMES);
        PyObject[] given = Arguments.fill("str", args, kwNames, STR_NAMES, STR_DEFAULTS);
        PyObject value = given[0];
        if (Arguments.count(args, kwNames) < 2) {
            return value instanceof PyStr s ? s : PyStr.of(value.str());
        }
        String codec = given[1] == PyNone.INSTANCE
                ? "utf-8"
                : requiredText("encoding", given[1]);
        String mode = given[2] == PyNone.INSTANCE
                ? "strict"
                : requiredText("errors", given[2]);
        if (!(value instanceof PyBytes source)) {
            throw Slots.typeError(
                    "decoding to str: need a bytes-like object, %s found", value.getType().name());
        }
        return PyStr.of(source.decodeText(codec, mode));
    }

    /** An argument that CPython requires to be a {@code str}, checked positionally or by name alike. */
    private static String requiredText(String name, PyObject value) {
        if (value instanceof PyStr s) {
            return s.getValue();
        }
        throw Slots.typeError(
                "str() argument '%s' must be str, not %s", name, value.getType().name());
    }

    private static PyObject bytes(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError(
                    "bytes() got an unexpected keyword argument '%s'", kwNames[0]);
        }
        if (args.length > 3) {
            throw Slots.typeError("bytes() takes at most 3 arguments (%d given)", args.length);
        }
        if (args.length > 1) {
            codecArgument("encoding", args[1]);
            if (args.length > 2) {
                codecArgument("errors", args[2]);
            }
        }
        if (args.length == 0) {
            return PyBytes.EMPTY;
        }
        PyObject value = args[0];
        if (value instanceof PyStr s) {
            if (args.length == 1 || args[1] == PyNone.INSTANCE) {
                throw Slots.typeError("string argument without an encoding");
            }
            String mode = args.length > 2 ? ((PyStr) args[2]).getValue() : "strict";
            return PyBytes.encodeText(s.getValue(), ((PyStr) args[1]).getValue(), mode);
        }
        BigInteger count = Numbers.asInteger(value);
        if (count != null) {
            if (count.signum() < 0) {
                throw Slots.valueError("negative count");
            }
            if (!Numbers.fitsInt(count)) {
                throw Slots.memoryError();
            }
            return PyBytes.wrap(new byte[count.intValue()]);
        }
        if (value instanceof PyBytes source) {
            return PyBytes.wrap(source.getValue().clone());
        }
        if (Numbers.isReal(value)) {
            throw Slots.typeError(
                    "cannot convert '%s' object to bytes", value.getType().name());
        }
        List<PyObject> items = materialise(value);
        byte[] out = new byte[items.size()];
        for (int i = 0; i < out.length; i++) {
            BigInteger item = Numbers.asInteger(items.get(i));
            if (item == null) {
                throw Slots.typeError(
                        "'%s' object cannot be interpreted as an integer",
                        items.get(i).getType().name());
            }
            if (item.signum() < 0 || item.compareTo(BYTE_LIMIT) > 0) {
                throw Slots.valueError("bytes must be in range(0, 256)");
            }
            out[i] = item.byteValue();
        }
        return PyBytes.wrap(out);
    }

    private static final BigInteger BYTE_LIMIT = BigInteger.valueOf(255);

    private static void codecArgument(String name, PyObject value) {
        if (value instanceof PyStr || value == PyNone.INSTANCE) {
            return;
        }
        throw Slots.typeError(
                "bytes() argument '%s' must be str, not %s", name, value.getType().name());
    }

    /** The text of a {@code str} or {@code bytes} argument, or null when the value is neither. */
    private static String textual(PyObject value) {
        if (value instanceof PyStr s) {
            return s.getValue();
        }
        if (value instanceof PyBytes b) {
            // Bytes are read as ASCII: int(b'12') works and int(b'\xd9\xdb') does not, because the
            // digits an integer literal may use are ASCII digits whatever the encoding story says.
            return new String(b.getValue(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    // --- type itself -----------------------------------------------------------------------

    // --- keyword plumbing ------------------------------------------------------------------

    /** The family whose keywords are all positional-only: {@code bool()}, {@code list()}, {@code range()}. */
    private static void refuseKeywords(String name, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s() takes no keyword arguments", name);
        }
    }

    /** Whether any of these names was given by keyword, which {@code fill} cannot tell a caller. */
    private static boolean names(String[] kwNames, String name) {
        if (kwNames == null) {
            return false;
        }
        for (String keyword : kwNames) {
            if (keyword.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The value {@code name} was given, or null; keyword values trail the positional ones. */
    private static PyObject keyword(String[] kwNames, PyObject[] args, int positional, String name) {
        if (kwNames == null) {
            return null;
        }
        for (int i = 0; i < kwNames.length; i++) {
            if (kwNames[i].equals(name)) {
                return args[positional + i];
            }
        }
        return null;
    }

    private static PyObject keywordOr(
            String[] kwNames, PyObject[] args, int positional, String name, PyObject fallback) {
        PyObject given = keyword(kwNames, args, positional, name);
        return given == null ? fallback : given;
    }

    /** A name the signature does not have, refused in the form that names its parameters. */
    private static void acceptKeywordsOnly(String qualified, String[] kwNames, String... allowed) {
        if (kwNames == null) {
            return;
        }
        for (String keyword : kwNames) {
            boolean found = false;
            for (String name : allowed) {
                if (name.equals(keyword)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", qualified, keyword);
            }
        }
    }
}
