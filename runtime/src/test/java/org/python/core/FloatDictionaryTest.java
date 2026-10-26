package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The forty-seven names {@code float} files, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 69 to 71 read the table, its thirty slots and its thirteen members from Python, one line per
 * answer. What is asserted here is the half a script cannot point at: the table as the complete ordered map it
 * is, and the one object the dictionary, the {@link Members} table and an ordinary read hand out for the same
 * name — a reordered or duplicated member row is only caught by comparing those three. Then the seven Java
 * identities behind {@code float_float}, which a printed {@code repr} cannot show, and the two ways a {@code
 * float} and an {@code int} are compared without either side being rounded: {@code 9007199254740992.0 < 2 ** 53
 * + 1} is true, and as doubles the two are the same number, so this is the one rule a conversion would get
 * wrong in both directions. Then the sentences, because the wrappers refuse in {@code wrapper_descriptor}'s
 * words and the members in clinic's, and the two differ on where the receiver is named. Then the one rounding
 * rule that {@code round()} reaches by asking {@code float.__round__} itself, on both sides of zero and at both
 * ends of the place, and the one lookup under it, which a class Python wrote is asked through exactly as a
 * {@code float} is. Then {@code float_fromhex}'s grammar — the three words it reads, the four
 * it refuses half-written — and {@code float_as_integer_ratio}'s exact pairs. Then the platform sentence
 * {@code __getformat__} answers and the fifteen spellings {@code __format__} answers. And last the two
 * properties, which are the only rows a {@code float} reads through a descriptor rather than a slot, and the
 * only ones whose answer about {@code real} is the receiver itself.
 */
class FloatDictionaryTest {

    /** The forty-seven names {@code float} files, in the order its dictionary answers them. */
    private static final List<String> FLOAT_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__add__", "__radd__", "__sub__", "__rsub__", "__mul__", "__rmul__", "__mod__",
                    "__rmod__", "__divmod__", "__rdivmod__", "__pow__", "__rpow__", "__neg__", "__pos__",
                    "__abs__", "__bool__", "__int__", "__float__", "__floordiv__", "__rfloordiv__",
                    "__truediv__", "__rtruediv__", "from_number", "conjugate", "__trunc__", "__floor__",
                    "__ceil__", "__round__", "as_integer_ratio", "fromhex", "hex", "is_integer",
                    "__getnewargs__", "__getformat__", "__format__", "real", "imag", "__doc__");

    /** The thirty rows {@code float_as_number}, {@code tp_repr} and {@code tp_hash} file as wrappers. */
    private static final List<String> FLOAT_WRAPPERS =
            List.of(
                    "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__",
                    "__add__", "__radd__", "__sub__", "__rsub__", "__mul__", "__rmul__", "__mod__", "__rmod__",
                    "__divmod__", "__rdivmod__", "__pow__", "__rpow__", "__neg__", "__pos__", "__abs__",
                    "__bool__", "__int__", "__float__", "__floordiv__", "__rfloordiv__", "__truediv__",
                    "__rtruediv__");

    /** The thirteen rows {@code float_methods} files, in the order it writes them. */
    private static final List<String> FLOAT_METHODS =
            List.of(
                    "from_number", "conjugate", "__trunc__", "__floor__", "__ceil__", "__round__",
                    "as_integer_ratio", "fromhex", "hex", "is_integer", "__getnewargs__", "__getformat__",
                    "__format__");

    /** The three {@code METH_CLASS} rows of the thirteen, which are reached through a type. */
    private static final List<String> FLOAT_CLASS_ROWS = List.of("from_number", "fromhex", "__getformat__");

    /** The two rows {@code float_getset} files, each with a null setter. */
    private static final List<String> FLOAT_PROPERTIES = List.of("real", "imag");

    /** A real outside the reach of any cache, which is how the six hand-backs show themselves. */
    private static final PyObject VALUE = PyFloat.of(1234.5);

    private static final PyObject HALF = PyFloat.of(1.5);
    private static final PyObject TWO_HALVES = PyFloat.of(2.5);
    private static final PyObject FIVE_AND_A_HALF = PyFloat.of(5.5);
    private static final PyObject ZERO = PyFloat.of(0.0);
    private static final PyObject NEG_ZERO = PyFloat.of(-0.0);
    private static final PyObject INF = PyFloat.of(Double.POSITIVE_INFINITY);
    private static final PyObject NEG_INF = PyFloat.of(Double.NEGATIVE_INFINITY);
    private static final PyObject NAN = PyFloat.of(Double.NaN);
    private static final PyObject TINY = PyFloat.of(Double.MIN_VALUE);
    private static final BigInteger HUGE_VALUE = BigInteger.TEN.pow(400);
    private static final PyObject HUGE = PyInt.of(HUGE_VALUE);
    private static final PyObject BIG = PyInt.of(BigInteger.valueOf(2).pow(70));
    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject TEXT = PyStr.of("a");
    private static final PyObject COMPLEX = PyComplex.of(0.0, 1.0);
    private static final PyObject QUARTER = PyFloat.of(0.25);
    private static final PyObject LIST = PyList.of(ONE);

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    private static String kind(PyObject member) {
        return member.getType().name();
    }

    /** The names the window over a type's dictionary answers, in the order the dictionary kept them. */
    private static List<String> filed(PyType kind) {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(kind, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** One name of a type's dictionary, called the way a program calls it: the receiver comes first. */
    private static PyObject call(PyType kind, String name, PyObject... arguments) {
        return Slots.call(entry(kind, name), arguments, null);
    }

    /** The same name called with keywords beside it, which is what sends a call to the parser. */
    private static PyObject called(PyType kind, String name, PyTuple kwNames, PyObject... arguments) {
        return Slots.call(entry(kind, name), arguments, kwNames);
    }

    /** One method of a value, read through its type and then called. */
    private static PyObject run(PyObject receiver, String name, PyObject... arguments) {
        return Slots.call(Slots.getAttr(receiver, name), arguments, null);
    }

    /** {@code descriptor.__get__(receiver)}, which is how the two properties are read at the table. */
    private static PyObject getOf(String name, PyObject receiver) {
        return Slots.call(
                Slots.getAttr(entry(PyType.FLOAT, name), "__get__"), new PyObject[] {receiver}, null);
    }

    /** The sentence one filed member answers {@code __doc__} with; {@code null} where it answers none. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == PyNone.INSTANCE ? null : ((PyStr) text).getValue();
    }

    /** The {@code round} builtin, which is the one caller of the rounding rule below. */
    private static PyObject roundOf(PyObject... arguments) {
        return Slots.call(Builtins.namespace().get(PyStr.interned("round")), arguments, null);
    }

    /** {@code type(name, bases, {members})}, the three-argument creator asked for a plain class. */
    private static PyType classOf(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(bases), dict}, null);
    }

    /** A member that answers with the arguments it was handed, which is how a call's shape shows. */
    private static PyNativeFunction echoes(String name) {
        return new PyNativeFunction(name, (args, kwNames) -> PyTuple.of(args));
    }

    /** One value of a class, as {@code type()} hands it out. */
    private static PyObject one(PyType kind) {
        return Slots.call(kind, new PyObject[0], null);
    }

    /** {@code float.as_integer_ratio()} as the table answers it, split into the pair it files. */
    private static BigInteger[] ratioOf(double value) {
        PyTuple pair = (PyTuple) call(PyType.FLOAT, "as_integer_ratio", PyFloat.of(value));
        return new BigInteger[] {
            ((PyInt) pair.at(0)).getValue(), ((PyInt) pair.at(1)).getValue()
        };
    }

    private static PyTuple oneKeyword() {
        return PyTuple.of(PyStr.interned("x"));
    }

    /** {@code f(name=value)}: the one keyword's name, beside the value the calling convention appended. */
    private static PyTuple named(String name) {
        return PyTuple.of(PyStr.interned(name));
    }

    /** {@code TypeError}, {@code ValueError} and {@code AttributeError} in CPython's own words. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The table complete, in the order {@code floatobject.c} wrote it, and the one object its names file.
     *
     * <p>The forty-seven rows come from three places and land in one order: the constructor clinic writes, then
     * the thirty slots {@code add_operators} files in struct order — with the two coercions {@code __int__} and
     * {@code __float__} between {@code __abs__}, {@code __bool__} and the floor-and-true divide pair because
     * that is where {@code PyNumberMethods} keeps them — then the thirteen of {@code float_methods}, the two of
     * {@code float_getset} and the type's own sentence. {@code len} is forty-seven and {@code dir} sixty: the
     * thirteen names only the second has are {@code object}'s, which a {@code float} borrows without writing a
     * line for, and the last assertion is that a read of one of those reaches the very row {@code object} filed.
     */
    @Test
    void fortySevenRowsFileTheOrderTheCFileWrote() {
        assertThat(filed(PyType.FLOAT)).isEqualTo(FLOAT_ORDER);
        assertThat(TypeDict.declared(PyType.FLOAT).keys()).hasSize(47);
        // The member table answers its thirteen by kind, while the order {@code float_methods} wrote is the
        // dictionary's — which is what the row below reads out of the table.
        assertThat(Arrays.asList(Members.tableOf(PyType.FLOAT).names()))
                .containsExactlyInAnyOrderElementsOf(FLOAT_METHODS);

        assertThat(FLOAT_ORDER.indexOf("__hash__") - FLOAT_ORDER.indexOf("__new__")).isEqualTo(2);
        assertThat(FLOAT_ORDER.indexOf("__ge__") - FLOAT_ORDER.indexOf("__lt__")).isEqualTo(5);
        assertThat(FLOAT_ORDER.indexOf("__add__") - FLOAT_ORDER.indexOf("__ge__")).isEqualTo(1);
        assertThat(FLOAT_ORDER.indexOf("__rtruediv__") - FLOAT_ORDER.indexOf("__add__")).isEqualTo(21);
        assertThat(FLOAT_ORDER.indexOf("from_number") - FLOAT_ORDER.indexOf("__rtruediv__")).isEqualTo(1);
        assertThat(FLOAT_ORDER.indexOf("__format__") - FLOAT_ORDER.indexOf("from_number")).isEqualTo(12);
        assertThat(FLOAT_ORDER.indexOf("imag") - FLOAT_ORDER.indexOf("real")).isEqualTo(1);
        assertThat(FLOAT_ORDER.indexOf("__doc__") - FLOAT_ORDER.indexOf("imag")).isEqualTo(1);
        assertThat(FLOAT_ORDER).startsWith("__new__").endsWith("__doc__");
        assertThat(FLOAT_ORDER.subList(31, 44)).isEqualTo(FLOAT_METHODS);

        // A method_descriptor read off the class is the row it was read from; a classmethod_descriptor
        // binds the type on the way out, so it is a fresh object each read.
        for (String name : FLOAT_METHODS) {
            if (FLOAT_CLASS_ROWS.contains(name)) {
                continue;
            }
            assertThat(entry(PyType.FLOAT, name))
                    .isSameAs(Slots.getAttr(PyType.FLOAT, name))
                    .isSameAs(Members.tableOf(PyType.FLOAT).describe(name));
        }
        for (String name : FLOAT_CLASS_ROWS) {
            assertThat(Members.tableOf(PyType.FLOAT).describe(name)).isSameAs(entry(PyType.FLOAT, name));
            assertThat(Slots.getAttr(PyType.FLOAT, name)).isNotSameAs(entry(PyType.FLOAT, name));
        }
        assertThat(entry(PyType.FLOAT, "__add__")).isSameAs(Slots.getAttr(PyType.FLOAT, "__add__"));
        assertThat(entry(PyType.FLOAT, "real")).isSameAs(Slots.getAttr(PyType.FLOAT, "real"));

        assertThat(PyType.FLOAT.parent()).isSameAs(PyType.OBJECT);
        assertThat(FLOAT_ORDER)
                .doesNotContain(
                        "__str__", "__init__", "__dir__", "__getattribute__", "__sizeof__", "__setformat__",
                        "__slots__");
        assertThat(TypeDict.declared(PyType.FLOAT).get(PyStr.interned("__setformat__"))).isNull();
        for (String borrowed : List.of("__str__", "__init__", "__dir__", "__sizeof__", "__getattribute__")) {
            assertThat(Slots.getAttr(PyType.FLOAT, borrowed))
                    .isSameAs(Slots.getAttr(PyType.OBJECT, borrowed));
        }

        // `float_as_number` fills no bitwise slot and `float` declares no `nb_index`: the operators decline.
        assertThat(FLOAT_ORDER)
                .doesNotContain(
                        "__and__", "__or__", "__xor__", "__lshift__", "__rshift__", "__invert__", "__index__",
                        "__matmul__");
        assertThat(Numeric.apply(BinaryOp.AND, HALF, TWO)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Numeric.apply(BinaryOp.LSHIFT, HALF, TWO)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Numeric.apply(BinaryOp.MATMUL, HALF, TWO)).isSameAs(PyNotImplemented.INSTANCE);
    }

    /**
     * The four kinds the forty-seven are filed with, and which of them carries the type that filed it.
     *
     * <p>Thirty rows are slot wrappers and ten of the thirteen members are plain methods; {@code from_number},
     * {@code fromhex} and {@code __getformat__} are the three {@code METH_CLASS} rows, and the distinction is
     * not cosmetic — a wrapper is called with the receiver in position and a class row with a type. {@code real}
     * and {@code imag} are a third shape and {@code __doc__} a fourth: the one row that is a string, and so the
     * one with no {@code __qualname__} beside it.
     */
    @Test
    void theFortySevenAreFiledInFourKinds() {
        assertThat(kind(entry(PyType.FLOAT, "__new__"))).isEqualTo("builtin_function_or_method");
        for (String name : FLOAT_WRAPPERS) {
            assertThat(kind(entry(PyType.FLOAT, name))).isEqualTo("wrapper_descriptor");
        }
        for (String name : FLOAT_METHODS) {
            assertThat(kind(entry(PyType.FLOAT, name)))
                    .isEqualTo(FLOAT_CLASS_ROWS.contains(name) ? "classmethod_descriptor" : "method_descriptor");
        }
        for (String name : FLOAT_PROPERTIES) {
            assertThat(kind(entry(PyType.FLOAT, name))).isEqualTo("getset_descriptor");
        }
        assertThat(kind(entry(PyType.FLOAT, "__doc__"))).isEqualTo("str");

        assertThat(rowsWithForeignQualnames()).isEqualTo(List.of());
        assertThat(rowsWithForeignObjclasses()).isEqualTo(List.of());
        assertThat(rowsWithoutQualname()).isEqualTo(List.of("__doc__"));
        assertThat(Slots.getAttr(entry(PyType.FLOAT, "hex"), "__qualname__")).isEqualTo(PyStr.of("float.hex"));
        assertThat(Slots.getAttr(entry(PyType.FLOAT, "from_number"), "__qualname__"))
                .isEqualTo(PyStr.of("float.from_number"));
        assertThat(Slots.getAttr(entry(PyType.FLOAT, "from_number"), "__objclass__")).isSameAs(PyType.FLOAT);

        // The one row that is not a descriptor has no qualname, and the one member with no sentence.
        assertThat(entry(PyType.FLOAT, "__doc__")).isInstanceOf(PyStr.class);
        assertThat(refusedDocs()).isEqualTo(List.of("__getnewargs__"));
        assertThat(((PyStr) entry(PyType.FLOAT, "__doc__")).getValue())
                .startsWith("Convert a string or number to a floating-point number");
    }

    /**
     * The sentences the table carries, which is how a help screen knows a {@code float}.
     *
     * <p>The thirty wrappers answer {@code add_operators}' and {@code float_as_number}'s own short sentences,
     * the thirteen members the docstrings {@code float_methods} files, and the two properties the pair
     * {@code float_getset} shares with {@code int}'s four — a {@code float} is the other value type that
     * calls itself a complex number with no imaginary part.
     */
    @Test
    void eachRowCarriesTheSentenceTheCFileWrites() {
        assertThat(carried(PyType.FLOAT, "__eq__")).isEqualTo("Return self==value.");
        assertThat(carried(PyType.FLOAT, "__add__")).isEqualTo("Return self+value.");
        assertThat(carried(PyType.FLOAT, "__rmod__")).isEqualTo("Return value%self.");
        assertThat(carried(PyType.FLOAT, "__rtruediv__")).isEqualTo("Return value/self.");
        assertThat(carried(PyType.FLOAT, "__divmod__")).isEqualTo("Return divmod(self, value).");
        assertThat(carried(PyType.FLOAT, "__pow__")).isEqualTo("Return pow(self, value, mod).");
        assertThat(carried(PyType.FLOAT, "__neg__")).isEqualTo("-self");
        assertThat(carried(PyType.FLOAT, "__abs__")).isEqualTo("abs(self)");
        assertThat(carried(PyType.FLOAT, "__bool__")).isEqualTo("True if self else False");
        assertThat(carried(PyType.FLOAT, "__int__")).isEqualTo("int(self)");
        assertThat(carried(PyType.FLOAT, "__float__")).isEqualTo("float(self)");
        assertThat(carried(PyType.FLOAT, "__hash__")).isEqualTo("Return hash(self).");
        assertThat(carried(PyType.FLOAT, "conjugate"))
                .isEqualTo("Return self, the complex conjugate of any float.");
        assertThat(carried(PyType.FLOAT, "__trunc__"))
                .isEqualTo("Return the Integral closest to x between 0 and x.");
        assertThat(carried(PyType.FLOAT, "__floor__")).isEqualTo("Return the floor as an Integral.");
        assertThat(carried(PyType.FLOAT, "__ceil__")).isEqualTo("Return the ceiling as an Integral.");
        assertThat(carried(PyType.FLOAT, "is_integer")).isEqualTo("Return True if the float is an integer.");
        assertThat(carried(PyType.FLOAT, "__format__"))
                .isEqualTo("Formats the float according to format_spec.");
        assertThat(carried(PyType.FLOAT, "real")).isEqualTo("the real part of a complex number");
        assertThat(carried(PyType.FLOAT, "imag")).isEqualTo("the imaginary part of a complex number");
        assertThat(carried(PyType.FLOAT, "__new__"))
                .isEqualTo("Create and return a new object.  See help(type) for accurate signature.");
        assertThat(carried(PyType.FLOAT, "__round__"))
                .startsWith("Return the Integral closest to x, rounding half toward even.");
        assertThat(carried(PyType.FLOAT, "from_number"))
                .isEqualTo("Convert real number to a floating-point number.");
        assertThat(carried(PyType.FLOAT, "hex")).startsWith("Return a hexadecimal representation");
        assertThat(carried(PyType.FLOAT, "fromhex")).startsWith("Create a floating-point number from a hex");
        assertThat(carried(PyType.FLOAT, "__getformat__")).startsWith("You probably don't want to use this");
        assertThat(carried(PyType.FLOAT, "as_integer_ratio"))
                .startsWith("Return a pair of integers, whose ratio is exactly equal");
    }

    /**
     * The six names that are one C function, and the seventh that cannot be.
     *
     * <p>{@code float_float} copies nothing, so {@code +x}, {@code float(x)}, {@code x.__float__()}, {@code
     * conjugate()}, the {@code real} read, {@code float.from_number(x)} and {@code float.__new__(float, x)} all
     * answer the very object they were handed — seven places in the runtime that could rebuild the number and
     * lose it, and one Java identity each. {@code imag} is filed by the same table and answered by a different
     * function: a {@code float}'s imaginary part is a zero that belongs to no one, so two reads are two objects
     * even though both answer {@code 0.0}.
     */
    @Test
    void sixNamesReachOneFunctionAndHandBackTheReceiver() {
        assertThat(call(PyType.FLOAT, "__pos__", VALUE)).isSameAs(VALUE);
        assertThat(call(PyType.FLOAT, "__float__", VALUE)).isSameAs(VALUE);
        assertThat(call(PyType.FLOAT, "conjugate", VALUE)).isSameAs(VALUE);
        assertThat(call(PyType.FLOAT, "from_number", PyType.FLOAT, VALUE)).isSameAs(VALUE);
        assertThat(call(PyType.FLOAT, "__new__", PyType.FLOAT, VALUE)).isSameAs(VALUE);
        assertThat(getOf("real", VALUE)).isSameAs(VALUE);
        assertThat(run(VALUE, "__float__")).isSameAs(VALUE);
        assertThat(run(VALUE, "conjugate")).isSameAs(VALUE);
        assertThat(Slots.getAttr(VALUE, "real")).isSameAs(VALUE);
        assertThat(PyType.FLOAT.call(new PyObject[] {VALUE}, null)).isSameAs(VALUE);
        assertThat(Numeric.floatFloat(VALUE)).isSameAs(VALUE);
        assertThat(Constructors.floating(new PyObject[] {VALUE}, null)).isSameAs(VALUE);

        assertThat(getOf("imag", VALUE)).isEqualTo(ZERO).isNotSameAs(VALUE);
        assertThat(getOf("imag", PyFloat.of(1.0))).isNotSameAs(getOf("imag", PyFloat.of(2.0)));
        assertThat(kind(getOf("imag", VALUE))).isEqualTo("float");
        assertThat(((PyFloat) getOf("imag", NEG_ZERO)).repr()).isEqualTo("0.0");

        // A `bool` is not one of the six: the wrapper's gate refuses it outright, and the number it stands
        // for is only what the operator path and the two conversions build.
        refuses(
                "TypeError: descriptor '__pos__' requires a 'float' object but received a 'bool'",
                () -> call(PyType.FLOAT, "__pos__", PyBool.TRUE));
        assertThat(Numeric.unary(UnaryOp.POSITIVE, PyBool.TRUE)).isEqualTo(PyFloat.of(1.0));
        assertThat(PyType.FLOAT.call(new PyObject[] {PyBool.TRUE}, null)).isEqualTo(PyFloat.of(1.0));
        assertThat(call(PyType.FLOAT, "from_number", PyType.FLOAT, PyBool.TRUE)).isEqualTo(PyFloat.of(1.0));
        assertThat(kind(PyType.FLOAT.call(new PyObject[] {PyBool.TRUE}, null))).isEqualTo("float");
    }

    /**
     * The four gates of the constructor row, in the order {@code tp_new_wrapper} walks them.
     *
     * <p>{@code float.__dict__["__new__"]} is clinic's static method, so the class is a plain argument and the
     * row binds {@code float} rather than the subtype it is handed: {@code float.__new__(int, 1)} is about the
     * class and not about the count or the keyword, because the gate that names a class comes first. The refusals
     * beside it are the four different sentences of the four gates, and the {@code object.__new__(float)} that
     * {@code staticbase} walks to rather than compares.
     */
    @Test
    void theConstructorGatesTheClassBeforeItCountsOrReadsAKeyword() {
        PyObject creator = entry(PyType.FLOAT, "__new__");
        assertThat(Slots.getAttr(creator, "__self__")).isSameAs(PyType.FLOAT);
        assertThat(Slots.getAttr(creator, "__qualname__")).isEqualTo(PyStr.of("float.__new__"));
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__objclass__'",
                () -> Slots.getAttr(creator, "__objclass__"));

        refuses(
                "TypeError: float.__new__(): not enough arguments", () -> Slots.call(creator, new PyObject[0], null));
        refuses(
                "TypeError: float.__new__(): not enough arguments",
                () -> Slots.call(creator, new PyObject[0], oneKeyword()));
        refuses(
                "TypeError: float.__new__(X): X is not a type object (str)",
                () -> Slots.call(creator, new PyObject[] {TEXT}, null));
        refuses(
                "TypeError: float.__new__(int): int is not a subtype of float",
                () -> call(PyType.FLOAT, "__new__", PyType.INT, ONE));
        refuses(
                "TypeError: float.__new__(int): int is not a subtype of float",
                () -> called(PyType.FLOAT, "__new__", oneKeyword(), PyType.INT, ONE));
        refuses(
                "TypeError: float.__new__(str): str is not a subtype of float",
                () -> call(PyType.FLOAT, "__new__", PyType.STR, TEXT));
        refuses(
                "TypeError: float.__new__(object): object is not a subtype of float",
                () -> call(PyType.FLOAT, "__new__", PyType.OBJECT));
        refuses(
                "TypeError: object.__new__(float) is not safe, use float.__new__()",
                () -> call(PyType.OBJECT, "__new__", PyType.FLOAT));

        // Past the gate the tail is the constructor's, which reads a keyword as a refusal and a second as a count.
        refuses(
                "TypeError: float() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__new__", oneKeyword(), PyType.FLOAT, HALF));
        refuses(
                "TypeError: float expected at most 1 argument, got 2",
                () -> call(PyType.FLOAT, "__new__", PyType.FLOAT, ONE, TWO));
        assertThat(call(PyType.FLOAT, "__new__", PyType.FLOAT)).isEqualTo(ZERO);
        assertThat(call(PyType.FLOAT, "__new__", PyType.FLOAT, PyStr.of(" 1.5\n"))).isEqualTo(HALF);
        assertThat(call(PyType.FLOAT, "__new__", PyType.FLOAT, PyBytes.of(new byte[] {'1', '.', '5'})))
                .isEqualTo(HALF);

        assertThat(PyType.FLOAT.call(new PyObject[0], null)).isEqualTo(ZERO);
        assertThat(PyType.FLOAT.call(new PyObject[] {PyInt.of(3)}, null)).isEqualTo(PyFloat.of(3.0));
        assertThat(PyType.FLOAT.call(new PyObject[] {PyStr.of("1_0.5")}, null)).isEqualTo(PyFloat.of(10.5));
        assertThat(PyType.FLOAT.call(new PyObject[] {PyStr.of("+Inf")}, null)).isEqualTo(INF);
        assertThat(kind(PyType.FLOAT.call(new PyObject[] {PyStr.of("nan")}, null))).isEqualTo("float");
        refuses(
                "OverflowError: int too large to convert to float",
                () -> PyType.FLOAT.call(new PyObject[] {HUGE}, null));
        refuses(
                "ValueError: could not convert string to float: '1.2.3'",
                () -> PyType.FLOAT.call(new PyObject[] {PyStr.of("1.2.3")}, null));
        // `float()` does not read the hexadecimal grammar that `fromhex` does.
        refuses(
                "ValueError: could not convert string to float: '0x1.8p1'",
                () -> PyType.FLOAT.call(new PyObject[] {PyStr.of("0x1.8p1")}, null));
        for (PyObject refused : List.of(PyNone.INSTANCE, LIST, COMPLEX, PyType.INT)) {
            refuses(
                    "TypeError: float() argument must be a string or a real number, not '"
                            + refused.getType().name()
                            + "'",
                    () -> PyType.FLOAT.call(new PyObject[] {refused}, null));
        }
        refuses(
                "TypeError: float expected at most 1 argument, got 2",
                () -> PyType.FLOAT.call(new PyObject[] {ONE, TWO}, null));
        refuses(
                "TypeError: float() takes no keyword arguments",
                () -> PyType.FLOAT.call(new PyObject[] {ONE}, oneKeyword()));
    }

    /**
     * The four tests a real answers a real with, and the ladder a real and an integer walk instead.
     *
     * <p>The six orderings are {@code float_richcompare}, whose first gate is {@code CHECK_BINOP}: a {@code str}
     * or a {@code complex} is declined rather than refused, which is why {@code float.__lt__(1.5, 'a')} answers
     * {@code NotImplemented} while {@code 1.5 < 'a'} is the sentence the interpreter builds out of two declines.
     * A pair of reals is four comparisons of doubles and nothing else, so {@code -0.0 == 0.0} is true where
     * {@code -0.0 < 0.0} is false, and a NaN fails all four. The mixed half is the rule that cannot be written
     * as a conversion: {@code 9007199254740992.0 == 2 ** 53} is true and {@code 9007199254740992.0 < 2 ** 53 + 1}
     * is true too, and rounding either side makes one of the two wrong. An infinity never reaches the integer —
     * it is measured against zero — and a {@code bool} is an integer to the whole ladder.
     */
    @Test
    void aRealAndAnIntegerCompareExactlyThroughOneLadder() {
        assertThat(call(PyType.FLOAT, "__lt__", HALF, TWO_HALVES)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__le__", HALF, TWO_HALVES)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__eq__", HALF, TWO_HALVES)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__ne__", HALF, TWO_HALVES)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__gt__", HALF, TWO_HALVES)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__ge__", HALF, TWO_HALVES)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__lt__", HALF, HALF)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__le__", HALF, HALF)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__eq__", NEG_ZERO, ZERO)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__lt__", NEG_ZERO, ZERO)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__ne__", NEG_ZERO, ZERO)).isSameAs(PyBool.FALSE);

        for (String name : List.of("__lt__", "__le__", "__gt__", "__ge__")) {
            assertThat(call(PyType.FLOAT, name, NAN, NAN)).isSameAs(PyBool.FALSE);
        }
        assertThat(call(PyType.FLOAT, "__eq__", NAN, NAN)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__ne__", NAN, NAN)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__eq__", NAN, HALF)).isSameAs(PyBool.FALSE);

        // The two that a conversion rounds the wrong way, and the two it rounds the other way.
        PyObject twoToFiftyThree = PyInt.of(BigInteger.ONE.shiftLeft(53));
        PyObject oneMore = PyInt.of(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE));
        PyObject exact = PyFloat.of(9007199254740992.0);
        assertThat(call(PyType.FLOAT, "__lt__", exact, oneMore)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__eq__", exact, twoToFiftyThree)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__lt__", exact, exact)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__eq__", PyFloat.of(Math.pow(2, 49)), PyInt.of(BigInteger.ONE.shiftLeft(49))))
                .isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__lt__", PyFloat.of(Math.pow(2, 49)), PyInt.of(BigInteger.ONE.shiftLeft(49))))
                .isSameAs(PyBool.FALSE);

        assertThat(call(PyType.FLOAT, "__lt__", HALF, TWO)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__gt__", HALF, TWO)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__eq__", PyFloat.of(1.0), ONE)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__eq__", HALF, ONE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__lt__", PyFloat.of(1e308), HUGE)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__gt__", PyFloat.of(1e308), HUGE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__lt__", HALF, PyInt.of(HUGE_VALUE.negate()))).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__gt__", HALF, PyInt.of(HUGE_VALUE.negate()))).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__lt__", INF, HUGE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__gt__", NEG_INF, HUGE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__lt__", NEG_INF, HUGE)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__eq__", INF, HUGE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__lt__", PyFloat.of(1.0), PyBool.TRUE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__eq__", PyFloat.of(1.0), PyBool.TRUE)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__gt__", HALF, PyBool.FALSE)).isSameAs(PyBool.TRUE);

        assertThat(call(PyType.FLOAT, "__lt__", HALF, TEXT)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.FLOAT, "__eq__", HALF, TEXT)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.FLOAT, "__eq__", PyFloat.of(1.0), COMPLEX)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.FLOAT, "__lt__", PyFloat.of(1.0), COMPLEX)).isSameAs(PyNotImplemented.INSTANCE);

        // The same ladder read as an operator, which is where the reflected half has to answer.
        assertThat(Slots.compareTrue(CompareOp.LT, HALF, TWO)).isTrue();
        assertThat(Slots.compareTrue(CompareOp.GT, HALF, TWO)).isFalse();
        assertThat(Slots.compareTrue(CompareOp.LT, TWO, HALF)).isFalse();
        assertThat(Slots.compareTrue(CompareOp.GT, TWO, HALF)).isTrue();
        assertThat(Slots.compareTrue(CompareOp.LE, HALF, TWO)).isTrue();
        assertThat(Slots.compareTrue(CompareOp.GE, TWO, HALF)).isTrue();
        assertThat(Slots.compareTrue(CompareOp.EQ, PyFloat.of(0.1).binaryOp(BinaryOp.ADD, PyFloat.of(0.2)), PyFloat.of(0.3)))
                .isFalse();
        assertThat(Slots.compareTrue(CompareOp.EQ, PyFloat.of(1.0), ONE)).isTrue();
        assertThat(Slots.compare(CompareOp.LT, PyFloat.of(9007199254740992.0), oneMore))
                .isSameAs(PyBool.TRUE);
        assertThat(Slots.compare(CompareOp.GT, oneMore, PyFloat.of(9007199254740992.0)))
                .isSameAs(PyBool.TRUE);
        assertThat(Slots.compare(CompareOp.EQ, COMPLEX, PyFloat.of(1.0))).isSameAs(PyBool.FALSE);
        refuses(
                "TypeError: '<' not supported between instances of 'float' and 'str'",
                () -> Slots.compare(CompareOp.LT, HALF, TEXT));
        refuses(
                "TypeError: '<' not supported between instances of 'str' and 'float'",
                () -> Slots.compare(CompareOp.LT, TEXT, HALF));

        // A whole float hashes as the integer it is, and the two zeroes alike.
        assertThat(PyFloat.of(42.0).hash()).isEqualTo(PyInt.of(42).hash());
        assertThat(PyFloat.of(-0.0).hash()).isEqualTo(PyFloat.of(0.0).hash());
        assertThat(call(PyType.FLOAT, "__hash__", PyFloat.of(42.0))).isEqualTo(PyInt.of(42));
    }

    /**
     * The eight pairs, each of them {@code CONVERT_TO_DOUBLE} twice.
     *
     * <p>A real, an integer that fits and a {@code bool} are read; a {@code str} or a {@code complex} declines
     * and an integer with no double form is an {@code OverflowError}, which is the difference between {@code
     * float.__add__(5.5, 'x')} and {@code float.__add__(5.5, 10 ** 400)}. Each reflected row is the very same C
     * function with the operands swapped, so {@code float.__rsub__(5.5, 2)} is the subtraction of {@code 2 - 5.5}
     * — and the swap is what a script cannot show, because an operator never reaches a reflected row whose left
     * operand is not a float. The floor divide and remainder pair comes out of one call, which is why {@code
     * math.fmod}'s sign rule survives: {@code -5.5 % 2.0} is {@code 0.5} and {@code 5.5 % -2.0} is {@code -0.5}.
     */
    @Test
    void theEightPairsReadBothOperandsAndDeclineInOnePlace() {
        assertThat(call(PyType.FLOAT, "__add__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(PyFloat.of(8.0));
        assertThat(call(PyType.FLOAT, "__radd__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(PyFloat.of(8.0));
        assertThat(call(PyType.FLOAT, "__sub__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(PyFloat.of(3.0));
        assertThat(call(PyType.FLOAT, "__rsub__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(PyFloat.of(-3.0));
        assertThat(call(PyType.FLOAT, "__mul__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(PyFloat.of(13.75));
        assertThat(call(PyType.FLOAT, "__mod__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(PyFloat.of(0.5));
        assertThat(call(PyType.FLOAT, "__rmod__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(TWO_HALVES);
        assertThat(call(PyType.FLOAT, "__add__", FIVE_AND_A_HALF, TWO)).isEqualTo(PyFloat.of(7.5));
        assertThat(call(PyType.FLOAT, "__add__", FIVE_AND_A_HALF, PyBool.TRUE)).isEqualTo(PyFloat.of(6.5));
        assertThat(call(PyType.FLOAT, "__radd__", FIVE_AND_A_HALF, TWO)).isEqualTo(PyFloat.of(7.5));
        assertThat(call(PyType.FLOAT, "__rsub__", FIVE_AND_A_HALF, TWO)).isEqualTo(PyFloat.of(-3.5));
        assertThat(call(PyType.FLOAT, "__rtruediv__", PyFloat.of(4.0), TWO)).isEqualTo(PyFloat.of(0.5));
        assertThat(call(PyType.FLOAT, "__rfloordiv__", FIVE_AND_A_HALF, TWO)).isEqualTo(ZERO);
        assertThat(call(PyType.FLOAT, "__rdivmod__", FIVE_AND_A_HALF, TWO))
                .isEqualTo(PyTuple.of(ZERO, TWO));

        assertThat(call(PyType.FLOAT, "__mod__", PyFloat.of(-5.5), PyFloat.of(2.0))).isEqualTo(PyFloat.of(0.5));
        assertThat(call(PyType.FLOAT, "__mod__", FIVE_AND_A_HALF, PyFloat.of(-2.0))).isEqualTo(PyFloat.of(-0.5));
        assertThat(call(PyType.FLOAT, "__mod__", PyFloat.of(-5.5), PyFloat.of(-2.0))).isEqualTo(PyFloat.of(-1.5));
        assertThat(call(PyType.FLOAT, "__mod__", PyFloat.of(5.0), TWO_HALVES)).isEqualTo(ZERO);
        assertThat(call(PyType.FLOAT, "__divmod__", PyFloat.of(7.5), PyFloat.of(2.0)))
                .isEqualTo(PyTuple.of(PyFloat.of(3.0), HALF));
        assertThat(call(PyType.FLOAT, "__divmod__", PyFloat.of(7.5), TWO)).isEqualTo(PyTuple.of(PyFloat.of(3.0), HALF));
        assertThat(((PyTuple) call(PyType.FLOAT, "__divmod__", PyFloat.of(7.5), PyFloat.of(2.0))).at(0))
                .isInstanceOf(PyFloat.class);
        assertThat(call(PyType.FLOAT, "__floordiv__", FIVE_AND_A_HALF, PyFloat.of(2.0))).isEqualTo(PyFloat.of(2.0));
        assertThat(call(PyType.FLOAT, "__floordiv__", PyFloat.of(-5.5), PyFloat.of(2.0))).isEqualTo(PyFloat.of(-3.0));
        assertThat(call(PyType.FLOAT, "__floordiv__", FIVE_AND_A_HALF, PyFloat.of(-2.0))).isEqualTo(PyFloat.of(-3.0));
        assertThat(call(PyType.FLOAT, "__truediv__", FIVE_AND_A_HALF, TWO_HALVES)).isEqualTo(PyFloat.of(2.2));
        assertThat(call(PyType.FLOAT, "__truediv__", FIVE_AND_A_HALF, TWO)).isEqualTo(PyFloat.of(2.75));

        for (String name : List.of("__add__", "__sub__", "__mul__", "__mod__")) {
            assertThat(call(PyType.FLOAT, name, FIVE_AND_A_HALF, TEXT)).isSameAs(PyNotImplemented.INSTANCE);
            assertThat(call(PyType.FLOAT, name, FIVE_AND_A_HALF, COMPLEX)).isSameAs(PyNotImplemented.INSTANCE);
            refuses(
                    "OverflowError: int too large to convert to float",
                    () -> call(PyType.FLOAT, name, FIVE_AND_A_HALF, HUGE));
            refuses(
                    "TypeError: descriptor '" + name + "' requires a 'float' object but received a 'str'",
                    () -> call(PyType.FLOAT, name, TEXT, FIVE_AND_A_HALF));
        }

        // The operators reach the same slots, and the declines are where the reflected half answers instead.
        assertThat(Slots.binary(BinaryOp.SUB, FIVE_AND_A_HALF, TWO)).isEqualTo(PyFloat.of(3.5));
        assertThat(Slots.binary(BinaryOp.SUB, TWO, FIVE_AND_A_HALF)).isEqualTo(PyFloat.of(-3.5));
        assertThat(Slots.binary(BinaryOp.TRUEDIV, TWO, FIVE_AND_A_HALF))
                .isEqualTo(PyFloat.of(0.36363636363636365));
        assertThat(Slots.binary(BinaryOp.POW, TWO, PyFloat.of(3.0))).isEqualTo(PyFloat.of(8.0));
        assertThat(Slots.binary(BinaryOp.ADD, HALF, COMPLEX)).isEqualTo(PyComplex.of(1.5, 1.0));
        refuses(
                "TypeError: unsupported operand type(s) for +: 'float' and 'str'",
                () -> Slots.binary(BinaryOp.ADD, FIVE_AND_A_HALF, TEXT));
        // Two declines are one sentence, but the left operand writes it: a str brings its own words.
        refuses(
                "TypeError: can only concatenate str (not \"float\") to str",
                () -> Slots.binary(BinaryOp.ADD, TEXT, FIVE_AND_A_HALF));
    }

    /**
     * The zero refusals, which in 3.14.8 are one sentence, and the three powers that are not arithmetic.
     *
     * <p>{@code float_divmod} is one function for the floor divide, the remainder and {@code divmod}, and its
     * first line tests the divisor — so all four of {@code /}, {@code //}, {@code %} and {@code divmod} answer
     * {@code division by zero}, including for a zero that is negative, where IEEE would have handed back a NaN.
     * {@code float_pow} settles its own special cases before {@code libm}: {@code 0.0 ** -1} is {@code zero to a
     * negative power}, an overflow is the errno sentence {@code (34, 'Result too large')}, a negative base with a
     * fractional exponent leaves the reals, and {@code -0.0} keeps its sign only for an odd exponent. The third
     * argument is refused outright, because {@code float_pow} is not the three-argument {@code long_pow}.
     */
    @Test
    void theDivisionsShareOneZeroSentenceAndThePowerRefusesItsModulus() {
        PyObject unit = PyFloat.of(1.0);
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__truediv__", unit, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__truediv__", ZERO, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__truediv__", PyFloat.of(-1.0), ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__truediv__", unit, NEG_ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__truediv__", unit, PyInt.ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__floordiv__", unit, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__mod__", unit, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__mod__", ZERO, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> call(PyType.FLOAT, "__divmod__", unit, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> Numeric.apply(BinaryOp.TRUEDIV, ONE, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> Numeric.apply(BinaryOp.FLOORDIV, ONE, ZERO));
        refuses("ZeroDivisionError: division by zero", () -> Numeric.apply(BinaryOp.MOD, ONE, ZERO));

        // The reflected half divides the other by the receiver, so it has a zero to give but never one to ask of.
        assertThat(call(PyType.FLOAT, "__rdivmod__", unit, ZERO)).isEqualTo(PyTuple.of(ZERO, ZERO));
        assertThat(call(PyType.FLOAT, "__rtruediv__", unit, PyInt.ZERO)).isEqualTo(ZERO);
        assertThat(call(PyType.FLOAT, "__rfloordiv__", unit, PyInt.ZERO)).isEqualTo(ZERO);
        assertThat(call(PyType.FLOAT, "__rmod__", unit, PyInt.ZERO)).isEqualTo(ZERO);

        assertThat(call(PyType.FLOAT, "__pow__", PyFloat.of(2.0), PyFloat.of(0.5))).isEqualTo(PyFloat.of(Math.sqrt(2.0)));
        assertThat(call(PyType.FLOAT, "__pow__", PyFloat.of(2.0), THREE)).isEqualTo(PyFloat.of(8.0));
        assertThat(call(PyType.FLOAT, "__rpow__", PyFloat.of(2.0), THREE)).isEqualTo(PyFloat.of(9.0));
        assertThat(call(PyType.FLOAT, "__pow__", PyFloat.of(4.0), PyFloat.of(0.5))).isEqualTo(PyFloat.of(2.0));
        assertThat(call(PyType.FLOAT, "__pow__", PyFloat.of(2.0), THREE, PyNone.INSTANCE)).isEqualTo(PyFloat.of(8.0));
        assertThat(call(PyType.FLOAT, "__pow__", ZERO, ZERO)).isEqualTo(ONE);
        assertThat(((PyFloat) call(PyType.FLOAT, "__pow__", NEG_ZERO, PyInt.of(3))).repr()).isEqualTo("-0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__pow__", NEG_ZERO, TWO)).repr()).isEqualTo("0.0");
        assertThat(call(PyType.FLOAT, "__pow__", PyFloat.of(-2.0), THREE)).isEqualTo(PyFloat.of(-8.0));
        assertThat(Numeric.apply(BinaryOp.POW, PyFloat.of(-2.0), PyFloat.of(0.5)))
                .isInstanceOf(PyComplex.class);

        refuses(
                "ZeroDivisionError: zero to a negative power",
                () -> call(PyType.FLOAT, "__pow__", ZERO, PyInt.of(-1)));
        refuses(
                "ZeroDivisionError: zero to a negative power",
                () -> call(PyType.FLOAT, "__pow__", ZERO, PyFloat.of(-1.0)));
        refuses(
                "OverflowError: (34, 'Result too large')",
                () -> call(PyType.FLOAT, "__pow__", PyFloat.of(1e308), TWO));
        refuses(
                "TypeError: pow() 3rd argument not allowed unless all arguments are integers",
                () -> call(PyType.FLOAT, "__pow__", PyFloat.of(2.0), THREE, FOUR()));
        refuses(
                "TypeError: pow() 3rd argument not allowed unless all arguments are integers",
                () -> call(PyType.FLOAT, "__pow__", PyFloat.of(2.0), THREE, PyFloat.of(4.0)));
        refuses(
                "TypeError: pow() 3rd argument not allowed unless all arguments are integers",
                () -> call(PyType.FLOAT, "__rpow__", PyFloat.of(2.0), THREE, FOUR()));
    }

    private static PyObject FOUR() {
        return PyInt.of(4);
    }

    /**
     * The unary six, and the three readings every wrapper makes of its own argument list.
     *
     * <p>{@code float_neg} and {@code float_abs} are the two that keep a zero's sign, {@code float_bool} tests
     * the value against zero rather than for a length so a NaN is true, and {@code float_int} is {@code
     * PyLong_FromDouble}: truncation toward zero, the two specials refused, and a whole this wide answered as the
     * exact integer the double names. The gate beside all of it is {@code check_value}'s, which names the
     * descriptor and the receiver it wanted, and the arity gate, which counts in {@code wrapper_descriptor}'s
     * words rather than in the slot's — the two-argument {@code __pow__} is the one row that says {@code 1 or 2}.
     */
    @Test
    void theUnarySixKeepSignsAndEveryWrapperGatesTheSameThreeWays() {
        assertThat(call(PyType.FLOAT, "__neg__", HALF)).isEqualTo(PyFloat.of(-1.5));
        assertThat(((PyFloat) call(PyType.FLOAT, "__neg__", ZERO)).repr()).isEqualTo("-0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__neg__", NEG_ZERO)).repr()).isEqualTo("0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__neg__", INF)).repr()).isEqualTo("-inf");
        assertThat(((PyFloat) call(PyType.FLOAT, "__neg__", NAN)).repr()).isEqualTo("nan");
        assertThat(((PyFloat) call(PyType.FLOAT, "__abs__", NAN)).repr()).isEqualTo("nan");
        assertThat(((PyFloat) call(PyType.FLOAT, "__abs__", NEG_ZERO)).repr()).isEqualTo("0.0");
        assertThat(call(PyType.FLOAT, "__pos__", PyFloat.of(-1.5))).isEqualTo(PyFloat.of(-1.5));

        assertThat(call(PyType.FLOAT, "__bool__", ZERO)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__bool__", NEG_ZERO)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "__bool__", TINY)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__bool__", NAN)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "__bool__", INF)).isSameAs(PyBool.TRUE);

        assertThat(call(PyType.FLOAT, "__int__", PyFloat.of(1.7))).isEqualTo(ONE);
        assertThat(call(PyType.FLOAT, "__int__", PyFloat.of(-1.5))).isEqualTo(PyInt.of(-1));
        assertThat(call(PyType.FLOAT, "__int__", NEG_ZERO)).isEqualTo(PyInt.ZERO);
        assertThat(kind(call(PyType.FLOAT, "__int__", PyFloat.of(1.7)))).isEqualTo("int");
        assertThat(((PyInt) call(PyType.FLOAT, "__int__", PyFloat.of(Math.pow(2, 70)))).getValue())
                .isEqualTo(BigInteger.TWO.pow(70));
        refuses(
                "OverflowError: cannot convert float infinity to integer",
                () -> call(PyType.FLOAT, "__int__", INF));
        refuses(
                "ValueError: cannot convert float NaN to integer",
                () -> call(PyType.FLOAT, "__int__", NAN));

        assertThat(((PyStr) call(PyType.FLOAT, "__repr__", PyFloat.of(1.0))).getValue()).isEqualTo("1.0");
        assertThat(((PyStr) call(PyType.FLOAT, "__repr__", NEG_ZERO)).getValue()).isEqualTo("-0.0");
        assertThat(((PyStr) call(PyType.FLOAT, "__repr__", PyFloat.of(1e16))).getValue()).isEqualTo("1e+16");
        assertThat(((PyStr) call(PyType.FLOAT, "__repr__", PyFloat.of(1e-5))).getValue()).isEqualTo("1e-05");
        assertThat(((PyStr) call(PyType.FLOAT, "__repr__", PyFloat.of(1e-4))).getValue()).isEqualTo("0.0001");
        assertThat(((PyStr) call(PyType.FLOAT, "__repr__", PyFloat.of(Math.pow(2, 70)))).getValue())
                .isEqualTo("1.1805916207174113e+21");

        for (String name : List.of("__add__", "__lt__", "__eq__", "__mul__")) {
            refuses("TypeError: expected 1 argument, got 0", () -> call(PyType.FLOAT, name, HALF));
            refuses("TypeError: expected 1 argument, got 2", () -> call(PyType.FLOAT, name, HALF, TWO, THREE));
        }
        for (String name : List.of("__neg__", "__abs__", "__int__", "__float__", "__hash__", "__repr__", "__bool__")) {
            refuses("TypeError: expected 0 arguments, got 1", () -> call(PyType.FLOAT, name, HALF, TWO));
        }
        refuses("TypeError: expected 1 or 2 arguments, got 0", () -> call(PyType.FLOAT, "__pow__", PyFloat.of(2.0)));
        refuses(
                "TypeError: expected 1 or 2 arguments, got 3",
                () -> call(PyType.FLOAT, "__pow__", PyFloat.of(2.0), THREE, FOUR(), PyFloat.of(5.0)));
        refuses(
                "TypeError: wrapper __lt__() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__lt__", oneKeyword(), PyFloat.of(1.0), TWO, THREE));
        refuses(
                "TypeError: wrapper __add__() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__add__", oneKeyword(), PyFloat.of(1.0), TWO, THREE));
        refuses(
                "TypeError: wrapper __neg__() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__neg__", oneKeyword(), PyFloat.of(1.0), TWO));
        // The keyword gate is the wrapper's first, so it answers even for the row that takes a modulus.
        refuses(
                "TypeError: wrapper __pow__() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__pow__", named("modulus"), PyFloat.of(2.0), THREE, FOUR()));
        refuses(
                "TypeError: descriptor '__add__' requires a 'float' object but received a 'str'",
                () -> call(PyType.FLOAT, "__add__", TEXT, TEXT));
        refuses(
                "TypeError: descriptor '__bool__' requires a 'float' object but received a 'list'",
                () -> call(PyType.FLOAT, "__bool__", LIST));
        refuses(
                "TypeError: descriptor '__abs__' requires a 'float' object but received a 'int'",
                () -> call(PyType.FLOAT, "__abs__", PyInt.of(-3)));
        refuses(
                "TypeError: descriptor '__repr__' requires a 'float' object but received a 'int'",
                () -> call(PyType.FLOAT, "__repr__", ONE));
        refuses(
                "TypeError: descriptor '__hash__' requires a 'float' object but received a 'int'",
                () -> call(PyType.FLOAT, "__hash__", ONE));
        refuses(
                "TypeError: descriptor '__int__' requires a 'float' object but received a 'str'",
                () -> call(PyType.FLOAT, "__int__", TEXT));
        refuses(
                "TypeError: descriptor '__float__' requires a 'float' object but received a 'int'",
                () -> call(PyType.FLOAT, "__float__", ONE));

        // A wrapper read off a value binds it, and the binding is a `method-wrapper` rather than a new kind.
        assertThat(kind(Slots.getAttr(HALF, "__add__"))).isEqualTo("method-wrapper");
        assertThat(Slots.getAttr(Slots.getAttr(HALF, "__add__"), "__self__")).isSameAs(HALF);
        assertThat(run(HALF, "__add__", TWO_HALVES)).isEqualTo(PyFloat.of(4.0));
        assertThat(run(HALF, "__lt__", TWO_HALVES)).isSameAs(PyBool.TRUE);
        assertThat(run(HALF, "__neg__")).isEqualTo(PyFloat.of(-1.5));
    }

    /**
     * What the thirteen members refuse, in clinic's words rather than the wrapper's.
     *
     * <p>A member is read off a type and called with the receiver in position, so the wrong kind is told that the
     * descriptor {@code doesn't apply to} it — the sentence a {@code method_descriptor} keeps, beside the
     * {@code requires a 'float' object} a wrapper keeps. The counts are clinic's too, and they differ by the
     * calling convention each row was filed with: {@code METH_O} says {@code takes no arguments (1 given)}, {@code
     * METH_VARARGS} with one slot says {@code takes exactly one argument}, and {@code __getnewargs__}, which is
     * read before any instance arrives, says {@code unbound method ... needs an argument}.
     */
    @Test
    void theThirteenMembersRefuseInClinicsOwnWords() {
        refuses(
                "TypeError: descriptor 'hex' for 'float' objects doesn't apply to a 'int' object",
                () -> call(PyType.FLOAT, "hex", ONE));
        refuses(
                "TypeError: descriptor 'conjugate' for 'float' objects doesn't apply to a 'int' object",
                () -> call(PyType.FLOAT, "conjugate", ONE));
        refuses(
                "TypeError: descriptor '__trunc__' for 'float' objects doesn't apply to a 'int' object",
                () -> call(PyType.FLOAT, "__trunc__", ONE));
        refuses(
                "TypeError: descriptor 'as_integer_ratio' for 'float' objects doesn't apply to a 'int' object",
                () -> call(PyType.FLOAT, "as_integer_ratio", ONE));
        refuses(
                "TypeError: descriptor 'is_integer' for 'float' objects doesn't apply to a 'int' object",
                () -> call(PyType.FLOAT, "is_integer", ONE));
        refuses(
                "TypeError: descriptor '__format__' for 'float' objects doesn't apply to a 'int' object",
                () -> call(PyType.FLOAT, "__format__", ONE, PyStr.of(".1f")));

        refuses(
                "TypeError: float.hex() takes no arguments (1 given)",
                () -> call(PyType.FLOAT, "hex", HALF, TWO));
        refuses(
                "TypeError: float.conjugate() takes no arguments (1 given)",
                () -> call(PyType.FLOAT, "conjugate", HALF, TWO));
        refuses(
                "TypeError: float.__trunc__() takes no arguments (1 given)",
                () -> call(PyType.FLOAT, "__trunc__", HALF, TWO));
        refuses(
                "TypeError: float.as_integer_ratio() takes no arguments (1 given)",
                () -> call(PyType.FLOAT, "as_integer_ratio", HALF, TWO));
        refuses(
                "TypeError: float.is_integer() takes no arguments (1 given)",
                () -> call(PyType.FLOAT, "is_integer", HALF, TWO));
        refuses(
                "TypeError: float.__getnewargs__() takes no arguments (1 given)",
                () -> call(PyType.FLOAT, "__getnewargs__", HALF, TWO));
        refuses(
                "TypeError: float.hex() takes no keyword arguments",
                () -> called(PyType.FLOAT, "hex", oneKeyword(), HALF, TWO));
        refuses(
                "TypeError: float.__trunc__() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__trunc__", oneKeyword(), HALF, TWO));

        refuses(
                "TypeError: unbound method float.__getnewargs__() needs an argument",
                () -> Slots.call(entry(PyType.FLOAT, "__getnewargs__"), new PyObject[0], null));
        refuses(
                "TypeError: float.from_number() takes exactly one argument (0 given)",
                () -> call(PyType.FLOAT, "from_number", PyType.FLOAT));
        refuses(
                "TypeError: float.from_number() takes exactly one argument (2 given)",
                () -> call(PyType.FLOAT, "from_number", PyType.FLOAT, ONE, TWO));
        refuses(
                "TypeError: float.from_number() takes no keyword arguments",
                () -> called(PyType.FLOAT, "from_number", named("number"), PyType.FLOAT, ONE));
        refuses(
                "TypeError: float.fromhex() takes exactly one argument (0 given)",
                () -> call(PyType.FLOAT, "fromhex", PyType.FLOAT));
        refuses(
                "TypeError: float.fromhex() takes exactly one argument (2 given)",
                () -> call(PyType.FLOAT, "fromhex", PyType.FLOAT, PyStr.of("0x1"), PyStr.of("0x2")));
        refuses(
                "TypeError: float.fromhex() takes no keyword arguments",
                () -> called(PyType.FLOAT, "fromhex", named("string"), PyType.FLOAT, PyStr.of("0x1")));
        refuses(
                "TypeError: float.__getformat__() takes exactly one argument (0 given)",
                () -> call(PyType.FLOAT, "__getformat__", PyType.FLOAT));
        refuses(
                "TypeError: float.__getformat__() takes exactly one argument (2 given)",
                () -> call(PyType.FLOAT, "__getformat__", PyType.FLOAT, PyStr.of("double"), PyStr.of("float")));
        refuses(
                "TypeError: float.__getformat__() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__getformat__", named("typestr"), PyType.FLOAT, PyStr.of("double")));
        refuses(
                "TypeError: float.__format__() takes exactly one argument (0 given)",
                () -> call(PyType.FLOAT, "__format__", HALF));
        refuses(
                "TypeError: float.__format__() takes exactly one argument (2 given)",
                () -> call(PyType.FLOAT, "__format__", HALF, PyStr.of(""), PyStr.of("")));
        refuses(
                "TypeError: __round__ expected at most 1 argument, got 2",
                () -> call(PyType.FLOAT, "__round__", HALF, ONE, TWO));
        refuses(
                "TypeError: float.__round__() takes no keyword arguments",
                () -> called(PyType.FLOAT, "__round__", named("ndigits"), HALF, ONE));

        // The three class rows check the type they were handed, and name the argument position it arrived in.
        refuses(
                "TypeError: descriptor 'from_number' for type 'float' needs a type, not a 'str' as arg 2",
                () -> call(PyType.FLOAT, "from_number", TEXT, ONE));
        refuses(
                "TypeError: descriptor '__getformat__' for type 'float' needs a type, not a 'str' as arg 2",
                () -> call(PyType.FLOAT, "__getformat__", TEXT, PyStr.of("double")));
        refuses(
                "TypeError: descriptor 'from_number' requires a subtype of 'float' but received 'int'",
                () -> call(PyType.FLOAT, "from_number", PyType.INT, ONE));

        // `from_number` is stricter than the constructor: reading text is not one of the conversions a real offers.
        for (String name : List.of("str", "bytes", "NoneType", "complex")) {
            PyObject given = switch (name) {
                case "str" -> TEXT;
                case "bytes" -> PyBytes.of(new byte[] {'1'});
                case "NoneType" -> PyNone.INSTANCE;
                default -> COMPLEX;
            };
            refuses(
                    "TypeError: must be real number, not " + name,
                    () -> call(PyType.FLOAT, "from_number", PyType.FLOAT, given));
        }
        refuses(
                "OverflowError: int too large to convert to float",
                () -> call(PyType.FLOAT, "from_number", PyType.FLOAT, HUGE));
        assertThat(call(PyType.FLOAT, "from_number", PyType.FLOAT, BIG)).isEqualTo(PyFloat.of(Math.pow(2, 70)));
        assertThat(call(PyType.FLOAT, "from_number", PyType.FLOAT, PyInt.of(3))).isEqualTo(PyFloat.of(3.0));

        // The two names `int` neither files nor inherits, read out of the two places a lookup reaches.
        assertThat(TypeDict.declared(PyType.INT).get(PyStr.interned("from_number"))).isNull();
        assertThat(Members.tableOf(PyType.INT).describe("from_number")).isSameAs(PyNotImplemented.INSTANCE);
    }

    /**
     * The three that answer an integer between, below or above the value.
     *
     * <p>{@code __trunc__} is {@code PyLong_FromDouble} of the value, {@code __floor__} and {@code __ceil__} the
     * same conversion of a double that has already been rounded down or up — so {@code -0.0} answers the integer
     * zero in all three, a whole answers itself, and the two specials are refused before any integer is built.
     * {@code conjugate} is the one that answers a float rather than an int, and it answers the receiver.
     */
    @Test
    void theThreeIntegerAnswersAndTheConjugateThatIsTheReceiver() {
        assertThat(call(PyType.FLOAT, "__trunc__", PyFloat.of(1.7))).isEqualTo(ONE);
        assertThat(call(PyType.FLOAT, "__trunc__", PyFloat.of(-1.7))).isEqualTo(PyInt.of(-1));
        assertThat(call(PyType.FLOAT, "__trunc__", HALF)).isEqualTo(ONE);
        assertThat(call(PyType.FLOAT, "__trunc__", PyFloat.of(-0.5))).isEqualTo(PyInt.ZERO);
        assertThat(call(PyType.FLOAT, "__trunc__", PyFloat.of(2.0))).isEqualTo(TWO);
        assertThat(kind(call(PyType.FLOAT, "__trunc__", HALF))).isEqualTo("int");
        assertThat(((PyInt) call(PyType.FLOAT, "__trunc__", PyFloat.of(-0.0))).getValue())
                .isEqualTo(BigInteger.ZERO);

        assertThat(call(PyType.FLOAT, "__floor__", PyFloat.of(1.7))).isEqualTo(ONE);
        assertThat(call(PyType.FLOAT, "__floor__", PyFloat.of(-1.7))).isEqualTo(PyInt.of(-2));
        assertThat(call(PyType.FLOAT, "__floor__", HALF)).isEqualTo(ONE);
        assertThat(call(PyType.FLOAT, "__floor__", PyFloat.of(-0.5))).isEqualTo(PyInt.of(-1));
        assertThat(call(PyType.FLOAT, "__ceil__", PyFloat.of(1.7))).isEqualTo(TWO);
        assertThat(call(PyType.FLOAT, "__ceil__", PyFloat.of(-1.7))).isEqualTo(PyInt.of(-1));
        assertThat(call(PyType.FLOAT, "__ceil__", PyFloat.of(-0.5))).isEqualTo(PyInt.ZERO);
        assertThat(call(PyType.FLOAT, "__ceil__", PyFloat.of(-2.0))).isEqualTo(PyInt.of(-2));
        assertThat(call(PyType.FLOAT, "__trunc__", PyFloat.of(Math.pow(2, 70))))
                .isEqualTo(call(PyType.FLOAT, "__floor__", PyFloat.of(Math.pow(2, 70))));

        refuses(
                "OverflowError: cannot convert float infinity to integer",
                () -> call(PyType.FLOAT, "__trunc__", INF));
        refuses(
                "OverflowError: cannot convert float infinity to integer",
                () -> call(PyType.FLOAT, "__floor__", INF));
        refuses(
                "ValueError: cannot convert float NaN to integer",
                () -> call(PyType.FLOAT, "__floor__", NAN));

        assertThat(call(PyType.FLOAT, "conjugate", VALUE)).isSameAs(VALUE);
        assertThat(call(PyType.FLOAT, "conjugate", PyFloat.of(-1.5))).isEqualTo(PyFloat.of(-1.5));
        assertThat(call(PyType.FLOAT, "conjugate", ZERO)).isEqualTo(ZERO);
        assertThat(((PyFloat) call(PyType.FLOAT, "conjugate", NEG_ZERO)).repr()).isEqualTo("-0.0");
        assertThat(call(PyType.FLOAT, "conjugate", INF)).isSameAs(INF);

        assertThat(call(PyType.FLOAT, "is_integer", PyFloat.of(2.0))).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "is_integer", HALF)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "is_integer", ZERO)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "is_integer", NEG_ZERO)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "is_integer", PyFloat.of(-3.0))).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "is_integer", PyFloat.of(1e308))).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.FLOAT, "is_integer", INF)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.FLOAT, "is_integer", TINY)).isSameAs(PyBool.FALSE);
        assertThat(kind(call(PyType.FLOAT, "is_integer", PyFloat.of(2.0)))).isEqualTo("bool");

        PyTuple args = (PyTuple) call(PyType.FLOAT, "__getnewargs__", VALUE);
        assertThat(args.size()).isEqualTo(1);
        assertThat(args.at(0)).isEqualTo(VALUE);
        assertThat(((PyTuple) call(PyType.FLOAT, "__getnewargs__", ZERO)).at(0)).isEqualTo(ZERO);
    }

    /**
     * The one rounding rule, asked by the member and by {@code round()} and answered the same both times.
     *
     * <p>With no place {@code float___round___impl} answers an {@code int} and the ties go even — {@code 0.5} to
     * {@code 0}, {@code 2.5} to {@code 2}, {@code -1.5} to {@code -2} — which is C's {@code round} corrected by
     * asking again for half the value. With a place it answers a float even at place zero, the place is read as a
     * machine integer so a {@code str} is refused and a number too wide is clipped rather than refused, and the
     * clipping is what makes {@code round(x, 2 ** 70)} be {@code x} and {@code round(x, -(2 ** 70))} be a signed
     * zero. The built-in is one lookup that lands on this member, so {@code roundOf} and the member are asserted
     * against each other as well as against the reference — which is how a second branch creeping back into the
     * built-in would show.
     */
    @Test
    void roundingGoesToTheEvenNeighbourAndTheBuiltinAsksTheSameCode() {
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(0.5))).isEqualTo(PyInt.ZERO);
        assertThat(call(PyType.FLOAT, "__round__", HALF)).isEqualTo(TWO);
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(2.5))).isEqualTo(TWO);
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(3.5))).isEqualTo(PyInt.of(4));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(-0.5))).isEqualTo(PyInt.ZERO);
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(-1.5))).isEqualTo(PyInt.of(-2));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(-2.5))).isEqualTo(PyInt.of(-2));
        assertThat(kind(call(PyType.FLOAT, "__round__", HALF))).isEqualTo("int");
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1.49999))).isEqualTo(ONE);
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1.50001))).isEqualTo(TWO);
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1234.0))).isEqualTo(PyInt.of(1234));
        assertThat(((PyInt) call(PyType.FLOAT, "__round__", PyFloat.of(-0.0))).getValue())
                .isEqualTo(BigInteger.ZERO);
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(Math.pow(2, 70))))
                .isEqualTo(PyInt.of(BigInteger.TWO.pow(70)));
        refuses(
                "OverflowError: cannot convert float infinity to integer",
                () -> call(PyType.FLOAT, "__round__", INF));
        refuses(
                "ValueError: cannot convert float NaN to integer",
                () -> call(PyType.FLOAT, "__round__", NAN));
        // `None` is not a place: it is the call that named none, and it answers the same int.
        assertThat(call(PyType.FLOAT, "__round__", HALF, PyNone.INSTANCE)).isEqualTo(TWO);

        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", HALF, PyInt.ZERO)).repr()).isEqualTo("2.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", PyFloat.of(2.5), PyInt.ZERO)).repr()).isEqualTo("2.0");
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1.25), ONE)).isEqualTo(PyFloat.of(1.2));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1.49999), ONE)).isEqualTo(PyFloat.of(1.5));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(0.15), ONE)).isEqualTo(PyFloat.of(0.1));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(0.25), ONE)).isEqualTo(PyFloat.of(0.2));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(2.675), TWO)).isEqualTo(PyFloat.of(2.67));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(0.125), TWO)).isEqualTo(PyFloat.of(0.12));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(0.375), TWO)).isEqualTo(PyFloat.of(0.38));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1.005), TWO)).isEqualTo(PyFloat.of(1.0));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1234.0), PyInt.of(-2))).isEqualTo(PyFloat.of(1200.0));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(123.456), PyInt.of(-2))).isEqualTo(PyFloat.of(100.0));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(543.21), PyInt.of(-3))).isEqualTo(PyFloat.of(1000.0));
        assertThat(call(PyType.FLOAT, "__round__", PyFloat.of(1.25), PyBool.TRUE)).isEqualTo(PyFloat.of(1.2));
        assertThat(call(PyType.FLOAT, "__round__", HALF, PyInt.of(20))).isEqualTo(HALF);
        assertThat(call(PyType.FLOAT, "__round__", HALF, PyInt.of(323))).isEqualTo(HALF);
        assertThat(call(PyType.FLOAT, "__round__", HALF, PyInt.of(324))).isEqualTo(HALF);
        assertThat(call(PyType.FLOAT, "__round__", HALF, BIG)).isEqualTo(HALF);
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", HALF, PyInt.of(-309))).repr()).isEqualTo("0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", HALF, PyInt.of(-1000))).repr()).isEqualTo("0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", HALF, PyInt.of(BigInteger.TWO.pow(70).negate())))
                        .repr())
                .isEqualTo("0.0");

        // A value the place empties still has a sign, which is the one thing the decimal form cannot say.
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", NEG_ZERO, ONE)).repr()).isEqualTo("-0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", PyFloat.of(-1e-320), TWO)).repr()).isEqualTo("-0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", PyFloat.of(1e-320), TWO)).repr()).isEqualTo("0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", PyFloat.of(-0.04), ONE)).repr()).isEqualTo("-0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", ZERO, PyInt.of(-3))).repr()).isEqualTo("0.0");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", NEG_ZERO, PyInt.of(-3))).repr()).isEqualTo("-0.0");

        // Past the last double the rounding cannot reach, the refusal is the rounding's own.
        refuses(
                "OverflowError: rounded value too large to represent",
                () -> call(PyType.FLOAT, "__round__", PyFloat.of(1.7976931348623157e308), PyInt.of(-308)));
        // One less than the largest double is not past it, so the same place answers rather than refuses.
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", PyFloat.of(1e308), PyInt.of(-308))).repr())
                .isEqualTo("1e+308");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", PyFloat.of(-1e308), PyInt.of(-308))).repr())
                .isEqualTo("-1e+308");
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", HALF, PyInt.of(-308))).repr()).isEqualTo("0.0");
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> call(PyType.FLOAT, "__round__", HALF, TEXT));
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> call(PyType.FLOAT, "__round__", HALF, PyFloat.of(1.0)));
        refuses(
                "TypeError: 'bytes' object cannot be interpreted as an integer",
                () -> call(PyType.FLOAT, "__round__", HALF, PyBytes.of(new byte[] {'1'})));

        // The specials keep themselves however the place is written, and a subnormal is not a special.
        assertThat(call(PyType.FLOAT, "__round__", INF, ONE)).isEqualTo(INF);
        assertThat(call(PyType.FLOAT, "__round__", NAN, ONE)).isNotSameAs(NAN);
        assertThat(((PyFloat) call(PyType.FLOAT, "__round__", NAN, ONE)).repr()).isEqualTo("nan");
        assertThat(call(PyType.FLOAT, "__round__", TINY, TWO)).isEqualTo(ZERO);

        for (double value : new double[] {0.5, 1.5, 2.5, 3.5, -1.5, 123.456, 1234.5, 2.675}) {
            for (int place : new int[] {-3, -2, 0, 1, 2}) {
                PyObject digits = PyInt.of(place);
                PyObject self = PyFloat.of(value);
                assertThat(roundOf(self, digits)).isEqualTo(call(PyType.FLOAT, "__round__", self, digits));
            }
        }
        assertThat(roundOf(HALF)).isEqualTo(call(PyType.FLOAT, "__round__", HALF));
        assertThat(roundOf(PyFloat.of(2.5))).isEqualTo(TWO);
        assertThat(roundOf(PyFloat.of(1.5), ONE)).isEqualTo(PyFloat.of(1.5));
        assertThat(roundOf(HALF, PyNone.INSTANCE)).isEqualTo(TWO);
        assertThat(kind(roundOf(HALF))).isEqualTo("int");
        assertThat(kind(roundOf(HALF, ONE))).isEqualTo("float");
        assertThat(kind(roundOf(BIG, ONE))).isEqualTo("int");
        refuses(
                "TypeError: type str doesn't define __round__ method",
                () -> roundOf(TEXT, TEXT));
        refuses("TypeError: type str doesn't define __round__ method", () -> roundOf(TEXT));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> roundOf(HALF, TEXT));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> roundOf(PyInt.of(5), TEXT));
        refuses(
                "TypeError: round() takes at most 2 arguments (3 given)",
                () -> roundOf(HALF, TWO, THREE));
        // The built-in reads a place named `ndigits`, which the member underneath it will not.
        PyObject round = Builtins.namespace().get(PyStr.interned("round"));
        assertThat(Slots.call(round, new PyObject[] {HALF, ONE}, named("ndigits"))).isEqualTo(PyFloat.of(1.5));
    }

    /**
     * The built-in's one lookup: {@code __round__} on the value's own type, and nothing else.
     *
     * <p>{@code builtin_round_impl} asks {@code _PyType_Lookup} and calls what it answers, so every rule below
     * is a consequence of that being the whole of it. The place is the only argument the member is handed when
     * one was named, and there is no argument at all when it was {@code None} — which is why a member that is
     * not a method descriptor, and so never has a receiver bound into it, sees {@code ()} where a function
     * written in Python would see the value. A descriptor is read through its own {@code __get__} first, so a
     * class method is handed its class and a static method is handed nothing. A name that is present but
     * useless is not missing: {@code __round__ = None} is called, and {@code 'NoneType' object is not callable}
     * is the sentence, while the complaint about a type that "doesn't define {@code __round__}" is reserved for
     * an empty lookup and cuts the name at a hundred characters. The walk is over the type alone, so an instance
     * dictionary that keeps a {@code __round__} of its own is not asked — and the walk is over the whole lookup
     * order, so a {@code bool} is rounded by {@code int} and reads its place as {@code int} would.
     */
    @Test
    void theBuiltinRoundAsksTheValuesOwnTypeAndNowhereElse() {
        PyType answers = classOf("Answers", new PyType[0], "__round__", echoes("Answers.__round__"));
        PyObject value = one(answers);
        assertThat(roundOf(value).repr()).isEqualTo("()");
        assertThat(roundOf(value, TWO).repr()).isEqualTo("(2,)");
        assertThat(roundOf(value, PyNone.INSTANCE).repr()).isEqualTo("()");

        // A subclass is asked the same question, which is the MRO walk rather than a dictionary read.
        assertThat(roundOf(one(classOf("Inherits", new PyType[] {answers}))).repr()).isEqualTo("()");

        // Whatever the name holds is read through its own getter before it is called.
        PyType stated = classOf("AsStatic", new PyType[0], "__round__", new PyStaticMethod(echoes("s")));
        assertThat(roundOf(one(stated)).repr()).isEqualTo("()");
        PyType classled =
                classOf("AsClassMethod", new PyType[0], "__round__", new PyClassMethod(echoes("c")));
        PyTuple classRow = (PyTuple) roundOf(one(classled));
        assertThat(classRow.size()).isEqualTo(1);
        assertThat(Slots.getAttr(classRow.at(0), "__name__")).isEqualTo(PyStr.of("AsClassMethod"));

        // A name that is present but useless is called, not refused for being the wrong kind of thing.
        refuses(
                "TypeError: 'NoneType' object is not callable",
                () -> roundOf(one(classOf("Silent", new PyType[0], "__round__", PyNone.INSTANCE))));
        refuses(
                "TypeError: 'NoneType' object is not callable",
                () -> roundOf(one(classOf("Silent", new PyType[0], "__round__", PyNone.INSTANCE)), TWO));
        refuses(
                "TypeError: 'int' object is not callable",
                () -> roundOf(one(classOf("Five", new PyType[0], "__round__", ONE))));

        // The receiver check belongs to the descriptor the way it is filed, wherever its name is written down.
        PyType borrowed =
                classOf("FloatsRound", new PyType[0], "__round__", entry(PyType.FLOAT, "__round__"));
        refuses(
                "TypeError: descriptor '__round__' for 'float' objects doesn't apply to a 'FloatsRound' object",
                () -> roundOf(one(borrowed)));
        refuses(
                "TypeError: descriptor '__round__' for 'float' objects doesn't apply to a 'FloatsRound' object",
                () -> roundOf(one(borrowed), ONE));

        // The instance dictionary is never asked, although an ordinary attribute read is.
        PyType silent = classOf("Nothing", new PyType[0]);
        PyObject shadowed = one(silent);
        Slots.setAttr(shadowed, "__round__", echoes("dictionary"));
        assertThat(run(shadowed, "__round__").repr()).isEqualTo("()");
        refuses("TypeError: type Nothing doesn't define __round__ method", () -> roundOf(shadowed));
        refuses(
                "TypeError: type Nothing doesn't define __round__ method", () -> roundOf(one(silent), TWO));

        // The name the refusal answers with is the type's own, cut at the hundred characters it is told to show.
        String farTooLong = "Rounding".repeat(16);
        assertThat(farTooLong).hasSize(128);
        refuses(
                "TypeError: type " + farTooLong.substring(0, 100) + " doesn't define __round__ method",
                () -> roundOf(one(classOf(farTooLong, new PyType[0]))));

        // A built-in type is asked through the row its dictionary files, so `bool` is rounded by `int`.
        assertThat(roundOf(PyBool.TRUE).repr()).isEqualTo("1");
        assertThat(kind(roundOf(PyBool.TRUE))).isEqualTo("int");
        assertThat(roundOf(PyBool.TRUE, TWO).repr()).isEqualTo("1");
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> roundOf(PyBool.TRUE, TEXT));
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> roundOf(PyBool.TRUE, PyFloat.of(1.0)));
        assertThat(roundOf(HALF)).isEqualTo(call(PyType.FLOAT, "__round__", HALF));
        assertThat(roundOf(BIG)).isEqualTo(call(PyType.INT, "__round__", BIG));
    }

    /**
     * The exact pair a double is, and the hexadecimal string that says it.
     *
     * <p>{@code float_as_integer_ratio} answers the significand and a power of two, in lowest terms and with a
     * positive denominator, so {@code 0.1} is not a tenth but {@code 3602879701896397} over {@code 2 ** 55} and
     * the smallest double is {@code 1} over {@code 2 ** 1074}. The two specials are refused rather than rounded:
     * an infinity has no ratio and neither has a NaN. {@code float_hex} is the same bits spelled, which is why
     * the two are exact inverses of each other on every value below — and why a negative zero writes a minus sign
     * in front of a zero.
     */
    @Test
    void theRatioIsExactAndTheHexadecimalFormReadsItBack() {
        assertThat(ratioOf(10.0)).containsExactly(BigInteger.TEN, BigInteger.ONE);
        assertThat(ratioOf(0.25)).containsExactly(BigInteger.ONE, BigInteger.valueOf(4));
        assertThat(ratioOf(0.0)).containsExactly(BigInteger.ZERO, BigInteger.ONE);
        assertThat(ratioOf(-0.0)).containsExactly(BigInteger.ZERO, BigInteger.ONE);
        assertThat(ratioOf(-0.75)).containsExactly(BigInteger.valueOf(-3), BigInteger.valueOf(4));
        assertThat(ratioOf(0.1))
                .containsExactly(
                        BigInteger.valueOf(3602879701896397L), BigInteger.valueOf(36028797018963968L));
        assertThat(ratioOf(1.0 / 3.0))
                .containsExactly(
                        BigInteger.valueOf(6004799503160661L), BigInteger.valueOf(18014398509481984L));
        assertThat(ratioOf(Double.MIN_VALUE))
                .containsExactly(BigInteger.ONE, BigInteger.ONE.shiftLeft(1074));
        assertThat(ratioOf(Math.pow(2, 1023)))
                .containsExactly(BigInteger.ONE.shiftLeft(1023), BigInteger.ONE);
        assertThat(ratioOf(1e308)[1]).isEqualTo(BigInteger.ONE);
        assertThat(((PyTuple) call(PyType.FLOAT, "as_integer_ratio", QUARTER)).at(0)).isInstanceOf(PyInt.class);

        refuses(
                "OverflowError: cannot convert Infinity to integer ratio",
                () -> call(PyType.FLOAT, "as_integer_ratio", INF));
        refuses(
                "OverflowError: cannot convert Infinity to integer ratio",
                () -> call(PyType.FLOAT, "as_integer_ratio", NEG_INF));
        refuses(
                "ValueError: cannot convert NaN to integer ratio",
                () -> call(PyType.FLOAT, "as_integer_ratio", NAN));

        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(0.1))).getValue())
                .isEqualTo("0x1.999999999999ap-4");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(-0.1))).getValue())
                .isEqualTo("-0x1.999999999999ap-4");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", ZERO)).getValue()).isEqualTo("0x0.0p+0");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", NEG_ZERO)).getValue()).isEqualTo("-0x0.0p+0");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(1.0))).getValue()).isEqualTo("0x1.0000000000000p+0");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(65536.0))).getValue())
                .isEqualTo("0x1.0000000000000p+16");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(1e16))).getValue())
                .isEqualTo("0x1.1c37937e08000p+53");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", TINY)).getValue())
                .isEqualTo("0x0.0000000000001p-1022");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(1 / 3.0))).getValue())
                .isEqualTo("0x1.5555555555555p-2");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(0.75))).getValue())
                .isEqualTo("0x1.8000000000000p-1");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(-0.75))).getValue())
                .isEqualTo("-0x1.8000000000000p-1");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(Math.pow(2, 70)))).getValue())
                .isEqualTo("0x1.0000000000000p+70");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(3.14159))).getValue())
                .isEqualTo("0x1.921f9f01b866ep+1");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", INF)).getValue()).isEqualTo("inf");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", NEG_INF)).getValue()).isEqualTo("-inf");
        assertThat(((PyStr) call(PyType.FLOAT, "hex", NAN)).getValue()).isEqualTo("nan");

        assertThat(hexRead("0x1.999999999999ap-4")).isEqualTo(PyFloat.of(0.1));
        assertThat(hexRead("-0x1.999999999999ap-4")).isEqualTo(PyFloat.of(-0.1));
        assertThat(hexRead(" 0x1.8p+1 ")).isEqualTo(PyFloat.of(3.0));
        assertThat(hexRead("\t0x1\n")).isEqualTo(ONE);
        assertThat(hexRead("0x1")).isEqualTo(ONE);
        assertThat(hexRead("0X1P4")).isEqualTo(PyFloat.of(16.0));
        assertThat(hexRead("0x.8p0")).isEqualTo(PyFloat.of(0.5));
        assertThat(hexRead("0x1.p+0")).isEqualTo(ONE);
        assertThat(hexRead("0x000.1p+4")).isEqualTo(ONE);
        assertThat(hexRead("+0x1")).isEqualTo(ONE);
        assertThat(hexRead("-0x1")).isEqualTo(PyFloat.of(-1.0));
        assertThat(hexRead("0x123")).isEqualTo(PyFloat.of(291.0));
        assertThat(hexRead("0x123p4")).isEqualTo(PyFloat.of(4656.0));
        // The prefix is optional, so a bare hexadecimal coefficient still reads as one: 1 + 5/16.
        assertThat(hexRead("1.5")).isEqualTo(PyFloat.of(1.3125));
        assertThat(hexRead(" -0x1p-1074 ")).isEqualTo(PyFloat.of(-Double.MIN_VALUE));
        assertThat(hexRead("0x1.23456789abcdef012345p+1")).isEqualTo(PyFloat.of(2.2755555555555556));

        // The three words the grammar reads beside the digits, and the four it refuses half-written.
        assertThat(hexRead("inf")).isEqualTo(INF);
        assertThat(hexRead("Infinity")).isEqualTo(INF);
        assertThat(hexRead("+Infinity")).isEqualTo(INF);
        assertThat(((PyFloat) hexRead("-infinity")).getValue()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(hexRead("INF")).isEqualTo(INF);
        assertThat(((PyFloat) hexRead("nan")).getValue()).isNaN();
        assertThat(((PyFloat) hexRead("NaN")).getValue()).isNaN();
        assertThat(((PyFloat) hexRead("-nan")).getValue()).isNaN();
        for (String refused :
                List.of("", "0x", "1.8p", "0x1z", "0x 1", "0x1_0", "in", "n", "infinit", "infinityx", "nanx",
                        "0x1p", "0x1p+", "0x.")) {
            refuses(
                    "ValueError: invalid hexadecimal floating-point string",
                    () -> hexRead(refused));
        }
        for (String refused : List.of("0x1p100000", "0x1p1024", "0x1.fffffffffffff8p+1023")) {
            refuses(
                    "OverflowError: hexadecimal value too large to represent as a float",
                    () -> hexRead(refused));
        }
        assertThat(((PyFloat) hexRead("0x1p-100000")).getValue()).isEqualTo(0.0);
        assertThat(((PyFloat) hexRead("0x1.fffffffffffffp+1023")).getValue())
                .isEqualTo(Double.MAX_VALUE);
        assertThat(((PyStr) call(PyType.FLOAT, "hex", hexRead("0x1.fffffffffffffp+1023"))).getValue())
                .isEqualTo("0x1.fffffffffffffp+1023");

        for (double value : new double[] {0.1, 1e308, Double.MIN_VALUE, -0.0, 1.0 / 3.0, -1e-310}) {
            assertThat(hexRead(((PyStr) call(PyType.FLOAT, "hex", PyFloat.of(value))).getValue()))
                    .isEqualTo(PyFloat.of(value));
        }

        refuses(
                "TypeError: bad argument type for built-in operation",
                () -> call(PyType.FLOAT, "fromhex", PyType.FLOAT, PyBytes.of(new byte[] {'0', 'x', '1'})));
        refuses(
                "TypeError: bad argument type for built-in operation",
                () -> call(PyType.FLOAT, "fromhex", PyType.FLOAT, ONE));
        refuses(
                "TypeError: bad argument type for built-in operation",
                () -> call(PyType.FLOAT, "fromhex", PyType.FLOAT, HALF));
    }

    private static PyObject hexRead(String text) {
        return call(PyType.FLOAT, "fromhex", PyType.FLOAT, PyStr.of(text));
    }

    /**
     * The sentence about the machine, and the fifteen about the value.
     *
     * <p>{@code float___getformat___impl} is the only member whose answer is not about the receiver: both names ask the
     * same question and get the platform's layout, and a third word is refused for its position rather than its
     * kind. {@code float.__format__} is the other half of the story — the value spelled, with the whole
     * presentation grammar behind it, and a spec that is only a fill character spelling the value unchanged.
     */
    @Test
    void getformatAnswersTheMachineAndFormatAnswersTheValueSpelled() {
        PyObject doubleSays = call(PyType.FLOAT, "__getformat__", PyType.FLOAT, PyStr.of("double"));
        PyObject floatSays = call(PyType.FLOAT, "__getformat__", PyType.FLOAT, PyStr.of("float"));
        assertThat(((PyStr) doubleSays).getValue()).startsWith("IEEE");
        assertThat(floatSays).isEqualTo(doubleSays);
        // Read through the class a class row binds it, which is the asymmetry a method_descriptor has not:
        // `float.hex is float.__dict__["hex"]` is true and `float.__getformat__ is ...` is false.
        PyObject boundToTheType = Slots.getAttr(PyType.FLOAT, "__getformat__");
        assertThat(boundToTheType).isNotSameAs(entry(PyType.FLOAT, "__getformat__"));
        assertThat(kind(boundToTheType)).isEqualTo("builtin_function_or_method");
        assertThat(Slots.call(boundToTheType, new PyObject[] {PyStr.of("double")}, null)).isEqualTo(doubleSays);

        for (String word : List.of("long", "DOUBLE", "")) {
            refuses(
                    "ValueError: __getformat__() argument 1 must be 'double' or 'float'",
                    () -> call(PyType.FLOAT, "__getformat__", PyType.FLOAT, PyStr.of(word)));
        }
        refuses(
                "TypeError: __getformat__() argument must be str, not bytes",
                () -> call(PyType.FLOAT, "__getformat__", PyType.FLOAT, PyBytes.of(new byte[] {'d'})));
        refuses(
                "TypeError: __getformat__() argument must be str, not int",
                () -> call(PyType.FLOAT, "__getformat__", PyType.FLOAT, ONE));
        refuses(
                "TypeError: __getformat__() argument must be str, not None",
                () -> call(PyType.FLOAT, "__getformat__", PyType.FLOAT, PyNone.INSTANCE));

        List<String> specs =
                List.of(
                        "", ".2f", ".2e", ">8.1f", "+.3%", "n", "010.3f", ".4g", "<12", "^11.2f", "=", "e", "E", "f",
                        "%");
        List<String> spelled =
                List.of(
                        "1234.5", "1234.50", "1.23e+03", "  1234.5", "+123450.000%", "1234.5", "001234.500", "1234",
                        "1234.5      ", "  1234.50  ", "1234.5", "1.234500e+03", "1.234500E+03", "1234.500000",
                        "123450.000000%");
        List<String> answers = new ArrayList<>();
        for (String spec : specs) {
            answers.add(((PyStr) call(PyType.FLOAT, "__format__", VALUE, PyStr.of(spec))).getValue());
        }
        assertThat(answers).isEqualTo(spelled);
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", VALUE, PyStr.of("z"))).getValue())
                .isEqualTo("1234.5");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", VALUE, PyStr.of(">^"))).getValue())
                .isEqualTo("1234.5");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", VALUE, PyStr.of(",.2f"))).getValue())
                .isEqualTo("1,234.50");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", PyFloat.of(1.5), PyStr.of(".2f"))).getValue())
                .isEqualTo("1.50");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", INF, PyStr.of(""))).getValue()).isEqualTo("inf");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", NEG_INF, PyStr.of(".2e"))).getValue()).isEqualTo("-inf");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", NAN, PyStr.of("e"))).getValue()).isEqualTo("nan");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", PyFloat.of(0.1), PyStr.of(".0f"))).getValue())
                .isEqualTo("0");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", ZERO, PyStr.of(".0f"))).getValue()).isEqualTo("0");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", TINY, PyStr.of(".0e"))).getValue())
                .isEqualTo("5e-324");
        assertThat(((PyStr) call(PyType.FLOAT, "__format__", PyFloat.of(1e308), PyStr.of("g"))).getValue())
                .isEqualTo("1e+308");

        for (PyObject spec : List.of(PyBool.TRUE, ONE, PyBytes.of(new byte[0]), PyNone.INSTANCE)) {
            refuses(
                    "TypeError: __format__() argument must be str, not " + Slots.argumentName(spec),
                    () -> call(PyType.FLOAT, "__format__", VALUE, spec));
        }
    }

    /**
     * The two properties, which are the only rows a {@code float} reads through a descriptor.
     *
     * <p>{@code float_getset} files a getter and a null setter, so both answer in the same sentence when
     * written, whether the write comes through {@code __set__}, through {@code __delete__} or through an
     * attribute — and the sentence names the property it was asked for, since each descriptor carries its own
     * name into {@code PyGetSetDescriptor}'s refusal. A read with no instance answers the descriptor itself, which
     * is what {@code float.real} is: the attribute, not a number.
     */
    @Test
    void theTwoPropertiesAreFiledOnceAndRefuseEveryWrite() {
        for (String name : FLOAT_PROPERTIES) {
            assertThat(Slots.getAttr(entry(PyType.FLOAT, name), "__objclass__")).isSameAs(PyType.FLOAT);
            assertThat(Slots.getAttr(entry(PyType.FLOAT, name), "__qualname__"))
                    .isEqualTo(PyStr.of("float." + name));
            assertThat(Slots.getAttr(entry(PyType.FLOAT, name), "__doc__")).isInstanceOf(PyStr.class);
        }

        assertThat(getOf("real", VALUE)).isSameAs(VALUE);
        assertThat(getOf("real", PyFloat.of(-1234.5))).isEqualTo(PyFloat.of(-1234.5));
        assertThat(getOf("imag", VALUE)).isEqualTo(ZERO);
        assertThat(Slots.getAttr(VALUE, "real")).isSameAs(VALUE);
        assertThat(Slots.getAttr(VALUE, "imag")).isEqualTo(ZERO);

        PyObject unbound =
                Slots.call(
                        Slots.getAttr(entry(PyType.FLOAT, "real"), "__get__"),
                        new PyObject[] {PyNone.INSTANCE, PyType.FLOAT},
                        null);
        assertThat(unbound).isSameAs(entry(PyType.FLOAT, "real"));
        assertThat(unbound.repr()).isEqualTo("<attribute 'real' of 'float' objects>");

        for (String name : FLOAT_PROPERTIES) {
            refuses(
                    "AttributeError: attribute '" + name + "' of 'float' objects is not writable",
                    () -> Slots.call(
                            Slots.getAttr(entry(PyType.FLOAT, name), "__set__"),
                            new PyObject[] {HALF, ONE},
                            null));
            refuses(
                    "AttributeError: attribute '" + name + "' of 'float' objects is not writable",
                    () -> Slots.call(
                            Slots.getAttr(entry(PyType.FLOAT, name), "__delete__"),
                            new PyObject[] {HALF},
                            null));
            refuses(
                    "AttributeError: attribute '" + name + "' of 'float' objects is not writable",
                    () -> Slots.setAttr(VALUE, name, ONE));
        }
        refuses(
                "TypeError: descriptor 'real' for 'float' objects doesn't apply to a 'int' object",
                () -> getOf("real", ONE));
        refuses(
                "TypeError: descriptor 'real' for 'float' objects doesn't apply to a 'str' object",
                () -> getOf("real", TEXT));
        refuses(
                "TypeError: descriptor 'imag' for 'float' objects doesn't apply to a 'complex' object",
                () -> getOf("imag", COMPLEX));
    }

    /** The rows of {@code float}'s table whose {@code __doc__} is {@code None}. */
    private static List<String> refusedDocs() {
        List<String> nameless = new ArrayList<>();
        for (String name : FLOAT_ORDER) {
            if (carried(PyType.FLOAT, name) == null) {
                nameless.add(name);
            }
        }
        return nameless;
    }

    /** The filed rows, but for the string, whose {@code __qualname__} does not read {@code float.<name>}. */
    private static List<String> rowsWithForeignQualnames() {
        List<String> wrong = new ArrayList<>();
        for (String name : FLOAT_ORDER) {
            if (name.equals("__doc__")) {
                continue;
            }
            if (!((PyStr) Slots.getAttr(entry(PyType.FLOAT, name), "__qualname__"))
                    .getValue()
                    .equals("float." + name)) {
                wrong.add(name);
            }
        }
        return wrong;
    }

    /** The filed rows that answer no {@code __qualname__} at all, which is what {@code hasattr} asks. */
    private static List<String> rowsWithoutQualname() {
        List<String> nameless = new ArrayList<>();
        for (String name : FLOAT_ORDER) {
            try {
                Slots.getAttr(entry(PyType.FLOAT, name), "__qualname__");
            } catch (PyRaise missing) {
                nameless.add(name);
            }
        }
        return nameless;
    }

    /** The filed rows whose {@code __objclass__} is not the type that files them. */
    private static List<String> rowsWithForeignObjclasses() {
        List<String> wrong = new ArrayList<>();
        for (String name : FLOAT_ORDER) {
            if (name.equals("__new__") || name.equals("__doc__")) {
                continue;
            }
            if (Slots.getAttr(entry(PyType.FLOAT, name), "__objclass__") != PyType.FLOAT) {
                wrong.add(name);
            }
        }
        return wrong;
    }
}
