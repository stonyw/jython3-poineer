package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dictionaries {@code int} and {@code bool} file, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 67 and 68 read the sixty-two names of a number and the ten of a truth value from Python, one
 * line per name, and ask what each entry answers. What is asserted here is the half a script cannot point at:
 * the two tables as the complete ordered maps they are, and the fifty-two names a bool leaves to the number
 * under it — a script can see which names this runtime fills but not that the dictionary entry and the same
 * type's {@link Members} table hand out one and the same descriptor, which is how a reordered or duplicated
 * method row is caught. Then the identities: {@code int(+BIG) is BIG} is Java object identity, and four of the
 * coercion slots, {@code abs} of what is already non-negative, {@code int()} of an exact {@code int} and the
 * {@code real} of a value are each a separate place in the runtime that could rebuild the number and lose it.
 * Then one arithmetic rule that two callers share — the round-half-to-even neighbour that {@code round()} and
 * {@code int.__round__} both ask {@link Numeric#nearMultiple} for, on both sides of zero. Then {@code
 * bool_new}'s two refusals in their one order, which a plain call cannot show because {@code bool(1, 2)} never
 * reaches the keyword check, and the {@code Py_False} default that makes a constructor of nothing answer False.
 * Then the four rows {@code long_getseters} files with no setter at all, which a bool reads through {@code
 * int}'s dictionary rather than through a branch of its own. And last the {@code tp_new}s that are null
 * pointers, which {@link PyType#withoutNew} is how this runtime says so.
 */
class IntDictionaryTest {

    /** The sixty-two names {@code int} files, in the order its dictionary answers them. */
    private static final List<String> INT_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__add__", "__radd__", "__sub__", "__rsub__", "__mul__", "__rmul__", "__mod__",
                    "__rmod__", "__divmod__", "__rdivmod__", "__pow__", "__rpow__", "__neg__", "__pos__",
                    "__abs__", "__bool__", "__invert__", "__lshift__", "__rlshift__", "__rshift__",
                    "__rrshift__", "__and__", "__rand__", "__xor__", "__rxor__", "__or__", "__ror__",
                    "__int__", "__float__", "__floordiv__", "__rfloordiv__", "__truediv__", "__rtruediv__",
                    "__index__", "conjugate", "bit_length", "bit_count", "to_bytes", "from_bytes",
                    "as_integer_ratio", "__trunc__", "__floor__", "__ceil__", "__round__", "__getnewargs__",
                    "__format__", "__sizeof__", "is_integer", "real", "imag", "numerator", "denominator",
                    "__doc__");

    /** The forty-one names {@code PyNumberMethods} and the three compare slots file as wrappers. */
    private static final List<String> INT_WRAPPERS =
            List.of(
                    "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__",
                    "__add__", "__radd__", "__sub__", "__rsub__", "__mul__", "__rmul__", "__mod__", "__rmod__",
                    "__divmod__", "__rdivmod__", "__pow__", "__rpow__", "__neg__", "__pos__", "__abs__",
                    "__bool__", "__invert__", "__lshift__", "__rlshift__", "__rshift__", "__rrshift__",
                    "__and__", "__rand__", "__xor__", "__rxor__", "__or__", "__ror__", "__int__", "__float__",
                    "__floordiv__", "__rfloordiv__", "__truediv__", "__rtruediv__", "__index__");

    /** The thirteen names {@code long_methods} files as plain methods, in the order it writes them. */
    private static final List<String> INT_METHODS =
            List.of(
                    "conjugate", "bit_length", "bit_count", "to_bytes", "as_integer_ratio", "__trunc__",
                    "__floor__", "__ceil__", "__round__", "__getnewargs__", "__format__", "__sizeof__",
                    "is_integer");

    /** The four names {@code long_getseters} files, each with a null setter. */
    private static final List<String> INT_PROPERTIES = List.of("real", "imag", "numerator", "denominator");

    /** The ten names {@code bool} files, which is all of what a truth value writes down for itself. */
    private static final List<String> BOOL_ORDER =
            List.of(
                    "__new__", "__repr__", "__invert__", "__and__", "__rand__", "__xor__", "__rxor__",
                    "__or__", "__ror__", "__doc__");

    /** The six names of the three bitwise pairs that answer a bool with a bool. */
    private static final List<String> BOOL_BITS =
            List.of("__and__", "__rand__", "__xor__", "__rxor__", "__or__", "__ror__");

    private static final BigInteger BIG_VALUE = BigInteger.valueOf(2).pow(70);

    /** A number too big for the small-int cache, which is the only way an identity answer shows itself. */
    private static final PyObject BIG = PyInt.of(BIG_VALUE);

    private static final PyObject ZERO = PyInt.ZERO;
    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FIFTEEN = PyInt.of(15);
    private static final PyObject TWENTY_FIVE = PyInt.of(25);
    private static final PyObject NEG_FIFTEEN = PyInt.of(-15);
    private static final PyObject TEXT = PyStr.of("a");
    private static final PyObject SPACED = PyStr.of(" 12 ");
    private static final PyObject HALF = PyFloat.of(2.5);

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

    private static PyObject call(PyObject callable, PyObject... arguments) {
        return Slots.call(callable, arguments, null);
    }

    /** The same name called with keywords beside it, which is what sends a call to the parser. */
    private static PyObject called(PyType kind, String name, PyTuple kwNames, PyObject... arguments) {
        return Slots.call(entry(kind, name), arguments, kwNames);
    }

    /** One method of a value, read through its type and then called. */
    private static PyObject run(PyObject receiver, String name, PyObject... arguments) {
        return Slots.call(Slots.getAttr(receiver, name), arguments, null);
    }

    /** {@code descriptor.__get__(receiver)}, which is how the four properties are read at the table. */
    private static PyObject getOf(String name, PyObject receiver) {
        return Slots.call(
                Slots.getAttr(entry(PyType.INT, name), "__get__"), new PyObject[] {receiver}, null);
    }

    /** The sentence one filed member answers {@code __doc__} with; {@code null} where it answers none. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == PyNone.INSTANCE ? null : ((PyStr) text).getValue();
    }

    /** The {@code round} builtin, which is the other caller of the rounding rule below. */
    private static PyObject roundOf(PyObject... arguments) {
        return Slots.call(Builtins.namespace().get(PyStr.interned("round")), arguments, null);
    }

    private static PyTuple oneKeyword() {
        return PyTuple.of(PyStr.interned("x"));
    }

    /** {@code f(name=value)}: the one keyword's name, beside the value the calling convention appended. */
    private static PyTuple named(String name) {
        return PyTuple.of(PyStr.interned(name));
    }

    /** {@code TypeError} and {@code AttributeError} in CPython's own words, with the type that says them. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * Each of the two tables, complete and in the reference's order.
     *
     * <p>{@code int}'s rows come from three places and land in one order: the constructor clinic writes, then
     * the forty-one slots {@code add_operators} files in struct order — with {@code __int__} and {@code
     * __float__} between {@code __ror__} and {@code __floordiv__} because the two coercion slots precede the
     * rest of the number block — then {@code long_methods}, {@code long_getseters} and the type's own sentence.
     * {@code from_bytes} is the one {@code METH_CLASS} of the fourteen and so the one {@code
     * classmethod_descriptor}, and {@code __getnewargs__} the one name whose sentence is {@code None}.
     *
     * <p>Of the sixty-two, a bool files none: its ten are its own, and every other name a bool answers is
     * found by walking to {@code int} and reading the object filed there, which is what the three {@code
     * isSameAs} assertions below are for. The two exceptions are the six bitwise rows and {@code __invert__},
     * which {@code boolobject.c} fills because it answers something other than what the number slot would.
     */
    @Test
    void eachHalfFilesItsOwnTable() {
        assertThat(filed(PyType.INT)).isEqualTo(INT_ORDER);
        assertThat(filed(PyType.BOOL)).isEqualTo(BOOL_ORDER);
        assertThat(TypeDict.declared(PyType.INT).keys()).hasSize(62);
        assertThat(TypeDict.declared(PyType.BOOL).keys()).hasSize(10);
        assertThat(Arrays.asList(Members.tableOf(PyType.INT).names()))
                .isEqualTo(concat(INT_METHODS, "from_bytes"));

        assertThat(INT_ORDER.indexOf("__hash__") - INT_ORDER.indexOf("__new__")).isEqualTo(2);
        assertThat(INT_ORDER.indexOf("__add__") - INT_ORDER.indexOf("__ge__")).isEqualTo(1);
        assertThat(INT_ORDER.indexOf("__index__") - INT_ORDER.indexOf("__int__")).isEqualTo(6);
        assertThat(INT_ORDER.indexOf("conjugate") - INT_ORDER.indexOf("__index__")).isEqualTo(1);
        assertThat(INT_ORDER.indexOf("is_integer") - INT_ORDER.indexOf("conjugate")).isEqualTo(13);
        assertThat(INT_ORDER.indexOf("real") - INT_ORDER.indexOf("is_integer")).isEqualTo(1);
        assertThat(INT_ORDER).startsWith("__new__").endsWith("__doc__");

        assertThat(entry(PyType.INT, "bit_length"))
                .isSameAs(Slots.getAttr(PyType.INT, "bit_length"))
                .isSameAs(Members.tableOf(PyType.INT).describe("bit_length"));
        assertThat(entry(PyType.INT, "__format__"))
                .isSameAs(Members.tableOf(PyType.INT).describe("__format__"));
        assertThat(entry(PyType.INT, "from_bytes"))
                .isSameAs(Members.tableOf(PyType.INT).describe("from_bytes"));

        assertThat(PyType.BOOL.parent()).isSameAs(PyType.INT);
        assertThat(entry(PyType.BOOL, "__and__")).isNotSameAs(entry(PyType.INT, "__and__"));
        assertThat(entry(PyType.BOOL, "__invert__")).isNotSameAs(entry(PyType.INT, "__invert__"));
        assertThat(Slots.getAttr(PyType.BOOL, "__eq__")).isSameAs(entry(PyType.INT, "__eq__"));
        assertThat(Slots.getAttr(PyType.BOOL, "bit_length")).isSameAs(entry(PyType.INT, "bit_length"));
        assertThat(Slots.getAttr(PyType.BOOL, "real")).isSameAs(entry(PyType.INT, "real"));
        assertThat(BOOL_ORDER).doesNotContain("__eq__", "__hash__", "__add__", "__int__", "real", "to_bytes");

        // `int` leaves `tp_str` to `object` and declares no initializer at all.
        assertThat(INT_ORDER).doesNotContain("__str__", "__init__", "__dir__", "__sizeof_class__");
        assertThat(Slots.getAttr(PyType.INT, "__str__"))
                .isSameAs(Slots.getAttr(PyType.OBJECT, "__str__"));
        assertThat(Slots.getAttr(PyType.INT, "__init__"))
                .isSameAs(Slots.getAttr(PyType.OBJECT, "__init__"));
        // ...which is why `str(-1350)` is the number's own `tp_repr` read through `object`.
        assertThat(call(PyType.INT, "__repr__", PyInt.of(-1350))).isEqualTo(PyStr.of("-1350"));
        assertThat(Slots.call(Slots.getAttr(PyInt.of(-1350), "__str__"), new PyObject[0], null))
                .isEqualTo(PyStr.of("-1350"));
    }

    /**
     * The shape each name is filed with, which is what a read hands out.
     *
     * <p>Forty-two of the sixty-two are slot wrappers, and the thirteen methods beside {@code from_bytes} are
     * not: the distinction shows in what a wrong receiver is told — {@code int.__lt__("a", 5)} names the {@code
     * int} a {@code check_value} wanted, while {@code int.__round__("a", 1)} names the descriptor a {@code
     * method_descriptor} was read on. The four properties are a third shape again, and each of the three
     * carries the type that filed it in {@code __objclass__}.
     */
    @Test
    void theFourGroupsAreFiledInThreeShapes() {
        for (String name : INT_WRAPPERS) {
            assertThat(kind(entry(PyType.INT, name))).isEqualTo("wrapper_descriptor");
        }
        for (String name : INT_METHODS) {
            assertThat(kind(entry(PyType.INT, name))).isEqualTo("method_descriptor");
        }
        for (String name : INT_PROPERTIES) {
            assertThat(kind(entry(PyType.INT, name))).isEqualTo("getset_descriptor");
        }
        assertThat(kind(entry(PyType.INT, "from_bytes"))).isEqualTo("classmethod_descriptor");
        assertThat(kind(entry(PyType.INT, "__doc__"))).isEqualTo("str");

        assertThat(Slots.getAttr(entry(PyType.INT, "__lt__"), "__qualname__"))
                .isEqualTo(PyStr.of("int.__lt__"));
        assertThat(Slots.getAttr(entry(PyType.INT, "to_bytes"), "__qualname__"))
                .isEqualTo(PyStr.of("int.to_bytes"));
        assertThat(Slots.getAttr(entry(PyType.INT, "__and__"), "__objclass__")).isSameAs(PyType.INT);
        assertThat(Slots.getAttr(entry(PyType.BOOL, "__and__"), "__objclass__")).isSameAs(PyType.BOOL);

        for (String name : BOOL_ORDER.subList(1, 9)) {
            assertThat(kind(entry(PyType.BOOL, name))).isEqualTo("wrapper_descriptor");
        }
        assertThat(kind(entry(PyType.BOOL, "__doc__"))).isEqualTo("str");

        assertThat(refusedDocs()).isEqualTo(List.of("__getnewargs__"));
        assertThat(carried(PyType.INT, "__eq__")).isEqualTo("Return self==value.");
        assertThat(carried(PyType.INT, "__index__"))
                .isEqualTo(
                        "Return self converted to an integer, if self is suitable for use as an index"
                                + " into a list.");
        assertThat(carried(PyType.INT, "real")).isEqualTo("the real part of a complex number");
        assertThat(carried(PyType.INT, "__new__"))
                .isEqualTo(carried(PyType.BOOL, "__new__"))
                .isEqualTo("Create and return a new object.  See help(type) for accurate signature.");
        assertThat(((PyStr) entry(PyType.INT, "__doc__")).getValue())
                .startsWith("int([x]) -> integer");
        assertThat(((PyStr) entry(PyType.BOOL, "__doc__")).getValue())
                .startsWith("Returns True when the argument is true, False otherwise.");
        assertThat(entry(PyType.BOOL, "__doc__")).isNotEqualTo(entry(PyType.INT, "__doc__"));
    }

    /**
     * The one row of the two tables that is not a descriptor, and the four gates of the rows that are.
     *
     * <p>{@code int.__dict__["__new__"]} is a {@code builtin_function_or_method} with {@code __self__} set and
     * no {@code __objclass__} at all, because clinic files a static method rather than {@code tp_new_wrapper}
     * filing a descriptor over a slot — so reading it binds the declaring type and a call has to name the class
     * it wants built. The gates run in CPython's order and each names a different class: the first three name
     * the type whose dictionary holds the row, and the fourth the one {@code staticbase} walked to, which is why
     * {@code int.__new__(bool)} says {@code bool} twice and not at all about {@code int}.
     */
    @Test
    void theConstructorRowBindsItsTypeAndGatesTheClassItIsGiven() {
        PyObject creator = entry(PyType.INT, "__new__");
        assertThat(kind(creator)).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(creator, "__self__")).isSameAs(PyType.INT);
        assertThat(Slots.getAttr(entry(PyType.BOOL, "__new__"), "__self__")).isSameAs(PyType.BOOL);
        assertThat(Slots.getAttr(creator, "__qualname__")).isEqualTo(PyStr.of("int.__new__"));
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__objclass__'",
                () -> Slots.getAttr(creator, "__objclass__"));

        refuses(
                "TypeError: int.__new__(): not enough arguments",
                () -> Slots.call(creator, new PyObject[0], null));
        refuses(
                "TypeError: int.__new__(X): X is not a type object (str)",
                () -> call(creator, TEXT));
        refuses(
                "TypeError: int.__new__(str): str is not a subtype of int",
                () -> call(creator, PyType.STR));
        refuses(
                "TypeError: int.__new__(bool) is not safe, use bool.__new__()",
                () -> call(creator, PyType.BOOL, PyBool.TRUE));
        refuses(
                "TypeError: bool.__new__(int): int is not a subtype of bool",
                () -> call(PyType.BOOL, "__new__", PyType.INT, ONE));
        refuses(
                "TypeError: object.__new__(bool) is not safe, use bool.__new__()",
                () -> call(PyType.OBJECT, "__new__", PyType.BOOL));
        refuses(
                "TypeError: object.__new__(int) is not safe, use int.__new__()",
                () -> call(PyType.OBJECT, "__new__", PyType.INT));
        refuses(
                "TypeError: object.__new__(str) is not safe, use str.__new__()",
                () -> call(PyType.OBJECT, "__new__", PyType.STR, TEXT));

        assertThat(call(creator, PyType.INT, SPACED)).isEqualTo(PyInt.of(12));
        assertThat(call(creator, PyType.INT)).isEqualTo(ZERO);
        assertThat(call(PyType.INT, "__new__", PyType.INT, FIFTEEN)).isSameAs(FIFTEEN);
    }

    /**
     * What the slots decline, before they answer anything.
     *
     * <p>Every one of the forty-one is gated on {@code CHECK_BINOP} — or, for the orderings, on {@code
     * long_richcompare}'s copy of the same gate — so {@code int.__eq__(5, 5.0)} reports the decline rather than
     * the {@code True} the operator still answers through the float's reflected slot. The gate is about the two
     * operands and not about the answer, which is why a {@code bool} pair gets through and a {@code float} does
     * not, however the arithmetic would have worked out.
     *
     * <p>The refusals beside them are the wrapper's three readings of its own argument list: the receiver is
     * checked before the count, so a {@code str} receiver on {@code __lt__} is told what the descriptor wanted
     * rather than that it was short an argument, and a keyword is refused in the words {@code wrapperdescriptor}
     * uses rather than the words the slot's own body would have used.
     */
    @Test
    void everySlotGatesOnTwoIntegersAndSaysSoOtherwise() {
        assertThat(call(PyType.INT, "__eq__", FIFTEEN, HALF)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.INT, "__ne__", FIFTEEN, HALF)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.INT, "__lt__", FIFTEEN, PyFloat.of(5.5))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.INT, "__add__", FIFTEEN, HALF)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.INT, "__and__", FIFTEEN, PyFloat.of(2.0))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.INT, "__divmod__", FIFTEEN, HALF)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.INT, "__pow__", TWO, HALF)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(PyType.INT, "__mul__", FIFTEEN, TEXT)).isSameAs(PyNotImplemented.INSTANCE);

        // A bool is an integer to this gate, and the answer is the number the two stand for.
        assertThat(call(PyType.INT, "__add__", FIFTEEN, PyBool.TRUE)).isEqualTo(PyInt.of(16));
        assertThat(call(PyType.INT, "__ge__", FIFTEEN, PyBool.TRUE)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.INT, "__eq__", FIFTEEN, PyBool.TRUE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.INT, "__eq__", FIFTEEN, PyInt.of(6))).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.INT, "__lt__", BIG, FIFTEEN)).isSameAs(PyBool.FALSE);

        refuses(
                "TypeError: descriptor '__lt__' requires a 'int' object but received a 'str'",
                () -> call(PyType.INT, "__lt__", TEXT, FIFTEEN));
        refuses("TypeError: expected 1 argument, got 0", () -> call(PyType.INT, "__lt__", FIFTEEN));
        refuses(
                "TypeError: expected 1 argument, got 2",
                () -> call(PyType.INT, "__lt__", FIFTEEN, TWO, THREE));
        refuses(
                "TypeError: wrapper __lt__() takes no keyword arguments",
                () -> called(PyType.INT, "__lt__", oneKeyword(), FIFTEEN, TWO));
        refuses(
                "TypeError: descriptor '__and__' requires a 'bool' object but received a 'float'",
                () -> call(PyType.BOOL, "__and__", PyFloat.of(2.0), PyBool.TRUE));

        // The methods check their receiver the other way round, and count in clinic's words.
        refuses(
                "TypeError: descriptor 'bit_length' for 'int' objects doesn't apply to a 'str' object",
                () -> call(PyType.INT, "bit_length", TEXT));
        refuses(
                "TypeError: descriptor '__round__' for 'int' objects doesn't apply to a 'str' object",
                () -> call(PyType.INT, "__round__", TEXT, TWO));
        refuses(
                "TypeError: __round__ expected at most 1 argument, got 2",
                () -> call(PyType.INT, "__round__", FIFTEEN, TWO, THREE));
        refuses(
                "TypeError: int.__round__() takes no keyword arguments",
                () -> called(PyType.INT, "__round__", PyTuple.of(PyStr.interned("ndigits")), FIFTEEN, TWO));
        refuses(
                "TypeError: int.conjugate() takes no arguments (1 given)",
                () -> call(PyType.INT, "conjugate", FIFTEEN, ONE));
    }

    /**
     * The answers that are the very number they were handed.
     *
     * <p>{@code long_long} copies nothing, so {@code +x}, {@code int(x)}, {@code operator.index(x)}, {@code
     * __trunc__}, {@code __floor__}, {@code __ceil__} and {@code conjugate} all return the argument itself, and
     * {@code long_abs} copies, which for what is already non-negative is the same object. {@code PyNumber_Long}
     * increfs an exact {@code int} where a {@code bool} is asked for the number it stands for. A value this
     * large is outside the small-int cache, so each of these is one Java object rather than two that compare
     * equal — which is what a script printing a {@code repr} cannot show.
     */
    @Test
    void sevenSlotsAndTwoBuildersHandBackTheReceiverItWasGiven() {
        for (String name :
                List.of("__pos__", "__int__", "__index__", "__trunc__", "__floor__", "__ceil__", "conjugate")) {
            assertThat(call(PyType.INT, name, BIG)).isSameAs(BIG);
        }
        assertThat(call(PyType.INT, "__abs__", BIG)).isSameAs(BIG);
        assertThat(call(PyType.INT, "__abs__", PyInt.of(-1350))).isEqualTo(PyInt.of(1350));
        assertThat(Slots.call(entry(PyType.INT, "__neg__"), new PyObject[] {BIG}, null))
                .isEqualTo(PyInt.of(BIG_VALUE.negate()));
        assertThat(run(BIG, "__pos__")).isSameAs(BIG);
        assertThat(getOf("real", BIG)).isSameAs(BIG);
        assertThat(getOf("numerator", BIG)).isSameAs(BIG);
        assertThat(((PyTuple) call(PyType.INT, "as_integer_ratio", BIG)).at(0)).isSameAs(BIG);

        // A tuple rebuilt through `__getnewargs__` is a new number; a small one was always the same cached one.
        PyObject copied = ((PyTuple) call(PyType.INT, "__getnewargs__", BIG)).at(0);
        assertThat(copied).isEqualTo(BIG).isNotSameAs(BIG);
        assertThat(((PyTuple) call(PyType.INT, "__getnewargs__", FIFTEEN)).at(0)).isSameAs(FIFTEEN);

        assertThat(PyType.INT.call(new PyObject[] {BIG}, null)).isSameAs(BIG);
        assertThat(Constructors.parsedInteger(new PyObject[] {BIG}, null)).isSameAs(BIG);
        PyObject truth = Constructors.parsedInteger(new PyObject[] {PyBool.TRUE}, null);
        assertThat(truth).isEqualTo(ONE).isNotInstanceOf(PyBool.class);

        // A bool is not one of these: the number it stands for is what comes back, and it is an `int`.
        assertThat(kind(call(PyType.INT, "__pos__", PyBool.TRUE))).isEqualTo("int");
        assertThat(kind(call(PyType.INT, "__abs__", PyBool.TRUE))).isEqualTo("int");
        assertThat(call(PyType.INT, "__pos__", PyBool.TRUE)).isEqualTo(ONE);
        assertThat(kind(call(PyType.INT, "__bool__", BIG))).isEqualTo("bool");
    }

    /**
     * The one rounding rule, asked by two callers and measured on both sides of zero.
     *
     * <p>{@code int___round___impl} is {@code self - divmod_near(self, 10**-ndigits)[1]}: the nearest multiple
     * of the power of ten, with a tie going to the even quotient. That is the same rule {@code round()} uses for
     * an integer, so this runtime asks {@link Numeric#nearMultiple} from both places, and the four numbers below
     * are what catches the two ever disagreeing — {@code 15} and {@code 25} both going to {@code 20}, and {@code
     * -1350} at a hundreds place going out to {@code -1400} because {@code -13} is odd.
     *
     * <p>Java's division truncates toward zero, which is why the tie is decided by the parity of the quotient
     * rather than by which way the remainder points: the same helper has to send {@code -5} to {@code 0} and
     * {@code -15} to {@code -20}.
     */
    @Test
    void roundingTiesGoToTheEvenNeighbourThroughOneRule() {
        assertThat(Numeric.nearMultiple(BigInteger.valueOf(5), BigInteger.TEN)).isEqualTo(BigInteger.ZERO);
        assertThat(Numeric.nearMultiple(BigInteger.valueOf(15), BigInteger.TEN))
                .isEqualTo(BigInteger.valueOf(20));
        assertThat(Numeric.nearMultiple(BigInteger.valueOf(25), BigInteger.TEN))
                .isEqualTo(BigInteger.valueOf(20));
        assertThat(Numeric.nearMultiple(BigInteger.valueOf(-5), BigInteger.TEN)).isEqualTo(BigInteger.ZERO);
        assertThat(Numeric.nearMultiple(BigInteger.valueOf(-15), BigInteger.TEN))
                .isEqualTo(BigInteger.valueOf(-20));
        assertThat(Numeric.nearMultiple(BigInteger.valueOf(-1350), BigInteger.valueOf(100)))
                .isEqualTo(BigInteger.valueOf(-1400));
        assertThat(Numeric.nearMultiple(BigInteger.valueOf(9999), BigInteger.TEN.pow(4)))
                .isEqualTo(BigInteger.TEN.pow(4));

        assertThat(call(PyType.INT, "__round__", FIFTEEN, PyInt.of(-1))).isEqualTo(PyInt.of(20));
        assertThat(call(PyType.INT, "__round__", TWENTY_FIVE, PyInt.of(-1))).isEqualTo(PyInt.of(20));
        assertThat(call(PyType.INT, "__round__", PyInt.of(-1350), PyInt.of(-2))).isEqualTo(PyInt.of(-1400));
        assertThat(call(PyType.INT, "__round__", PyInt.of(9999), PyInt.of(-4))).isEqualTo(PyInt.of(10000));
        assertThat(call(PyType.INT, "__round__", PyInt.of(1234), PyInt.of(-5))).isEqualTo(ZERO);
        for (int value : new int[] {5, 15, 25, -5, -15, 1234, 9999}) {
            for (int place : new int[] {-1, -2, -3}) {
                PyObject digits = PyInt.of(place);
                assertThat(roundOf(PyInt.of(value), digits))
                        .isEqualTo(call(PyType.INT, "__round__", PyInt.of(value), digits));
            }
        }

        // A place at or after the point, and `None`, are the identity again rather than arithmetic.
        assertThat(call(PyType.INT, "__round__", FIFTEEN)).isSameAs(FIFTEEN);
        assertThat(call(PyType.INT, "__round__", FIFTEEN, TWO)).isSameAs(FIFTEEN);
        assertThat(call(PyType.INT, "__round__", BIG, PyNone.INSTANCE)).isSameAs(BIG);
        // A bool place is an integer to `_PyNumber_Index`, and a negative one asks for no digits at all.
        assertThat(call(PyType.INT, "__round__", PyInt.of(1234), PyBool.TRUE)).isEqualTo(PyInt.of(1234));
        assertThat(call(PyType.INT, "__round__", PyInt.of(1234), PyBool.FALSE)).isEqualTo(PyInt.of(1234));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> call(PyType.INT, "__round__", FIFTEEN, TEXT));
    }

    /**
     * The three bitwise pairs, decided by the types that came in rather than by what the answer means.
     *
     * <p>{@code bool_and}, {@code bool_or} and {@code bool_xor} are two lines each: both operands {@code bool}
     * → one of the two singletons, otherwise → {@code PyLong_Type}'s own slot. So {@code True & True} is the
     * {@code True} it always was while {@code True & 1} is the {@code int} one, and {@code True & 2**70} is the
     * {@code int} zero — an answer that would be {@code False} in the first half and is not here. The reflected
     * rows swap the operands before they ask, so {@code bool.__rand__(True, 12)} is the int twelve and {@code
     * bool.__rxor__(True, False)} is a bool.
     *
     * <p>{@code __invert__} has no such pair: {@code bool_invert} always answers a number, and in the reference
     * it warns first — see the record of this slice for why this runtime answers without.
     */
    @Test
    void theThreeBitwisePairsAnswerWithABoolOnlyForAPairOfBools() {
        assertThat(call(PyType.BOOL, "__and__", PyBool.TRUE, PyBool.TRUE)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.BOOL, "__and__", PyBool.TRUE, PyBool.FALSE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__and__", PyBool.FALSE, PyBool.TRUE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__and__", PyBool.FALSE, PyBool.FALSE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__or__", PyBool.FALSE, PyBool.FALSE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__xor__", PyBool.TRUE, PyBool.FALSE)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.BOOL, "__rxor__", PyBool.TRUE, PyBool.FALSE)).isSameAs(PyBool.TRUE);

        assertThat(call(PyType.BOOL, "__and__", PyBool.TRUE, ONE)).isEqualTo(ONE).isNotInstanceOf(PyBool.class);
        assertThat(call(PyType.BOOL, "__and__", PyBool.TRUE, ZERO)).isEqualTo(ZERO);
        assertThat(call(PyType.BOOL, "__and__", PyBool.TRUE, BIG)).isEqualTo(ZERO);
        assertThat(call(PyType.BOOL, "__or__", PyBool.TRUE, TWO)).isEqualTo(PyInt.of(3));
        assertThat(call(PyType.BOOL, "__xor__", PyBool.TRUE, ONE)).isEqualTo(ZERO);
        assertThat(call(PyType.BOOL, "__rand__", PyBool.TRUE, PyInt.of(12))).isEqualTo(ZERO);
        assertThat(call(PyType.BOOL, "__ror__", PyBool.FALSE, PyInt.of(12))).isEqualTo(PyInt.of(12));

        // The pairs the number half answers for a bool, and the hash and repr a bool never files for itself.
        assertThat(call(PyType.BOOL, "__repr__", PyBool.FALSE)).isEqualTo(PyStr.of("False"));
        assertThat(call(PyType.INT, "__repr__", PyBool.TRUE)).isEqualTo(PyStr.of("1"));
        assertThat(call(PyType.INT, "__hash__", PyBool.TRUE)).isEqualTo(ONE);
        assertThat(PyBool.TRUE.hash()).isEqualTo(ONE.hash());
        assertThat(call(PyType.BOOL, "__invert__", PyBool.TRUE)).isEqualTo(PyInt.of(-2));
        assertThat(call(PyType.INT, "__invert__", PyBool.TRUE)).isEqualTo(PyInt.of(-2));
        for (String name : BOOL_BITS) {
            assertThat(entry(PyType.INT, name)).isNotSameAs(entry(PyType.BOOL, name));
        }
    }

    /**
     * The four properties, which a bool reads out of {@code int}'s dictionary.
     *
     * <p>{@code long_getseters} files a getter and a null setter, so each of the four answers the number and
     * refuses a write in the same sentence. {@code PyBool} used to answer {@code real} and its three neighbours
     * itself; now that {@code int}'s table is complete the lookup reaches them, and the last four assertions
     * are what catches that a bool's own {@code getAttr} no longer carries the branch — every one of the four
     * answers an {@code int}, and {@code real} of a bool is the {@code int} one rather than the {@code True} it
     * was read on.
     */
    @Test
    void theFourPropertiesAreFiledOnceAndReadByBothHalves() {
        assertThat(getOf("real", BIG)).isSameAs(BIG);
        assertThat(getOf("imag", BIG)).isSameAs(ZERO);
        assertThat(getOf("numerator", BIG)).isSameAs(BIG);
        assertThat(getOf("denominator", BIG)).isSameAs(ONE);
        assertThat(getOf("denominator", PyInt.of(-1350))).isSameAs(ONE);
        assertThat(getOf("real", PyInt.of(-7))).isEqualTo(PyInt.of(-7));

        for (String name : INT_PROPERTIES) {
            assertThat(Slots.getAttr(entry(PyType.INT, name), "__objclass__")).isSameAs(PyType.INT);
            assertThat(Slots.getAttr(entry(PyType.INT, name), "__qualname__"))
                    .isEqualTo(PyStr.of("int." + name));
        }

        // The number's own values, and the same four read through a truth value.
        assertThat(Slots.getAttr(BIG, "real")).isSameAs(BIG);
        assertThat(Slots.getAttr(PyBool.TRUE, "real")).isEqualTo(ONE).isNotInstanceOf(PyBool.class);
        assertThat(Slots.getAttr(PyBool.TRUE, "imag")).isEqualTo(ZERO);
        assertThat(Slots.getAttr(PyBool.TRUE, "numerator")).isEqualTo(ONE);
        assertThat(Slots.getAttr(PyBool.TRUE, "denominator")).isEqualTo(ONE);
        assertThat(Slots.getAttr(PyBool.FALSE, "real")).isEqualTo(ZERO);

        refuses(
                "AttributeError: attribute 'real' of 'int' objects is not writable",
                () -> Slots.call(Slots.getAttr(entry(PyType.INT, "real"), "__set__"), new PyObject[] {FIFTEEN, ONE}, null));
        refuses(
                "AttributeError: attribute 'real' of 'int' objects is not writable",
                () -> Slots.call(Slots.getAttr(entry(PyType.INT, "real"), "__delete__"), new PyObject[] {FIFTEEN}, null));
        refuses(
                "AttributeError: attribute 'numerator' of 'int' objects is not writable",
                () -> Slots.setAttr(BIG, "numerator", ONE));
        refuses(
                "TypeError: descriptor 'real' for 'int' objects doesn't apply to a 'str' object",
                () -> getOf("real", TEXT));
        refuses(
                "TypeError: __get__(None, None) is invalid",
                () -> Slots.call(
                        Slots.getAttr(entry(PyType.INT, "real"), "__get__"),
                        new PyObject[] {PyNone.INSTANCE},
                        null));
    }

    /**
     * The two constructors, whose refusals are in a different order from each other.
     *
     * <p>{@code bool_new} asks {@code _PyArg_NoKeywords} before {@code PyArg_UnpackTuple}, so {@code bool(x=1)}
     * is about the keyword and {@code bool(1, 2)} about the count — and the count is stated in {@code bool}'s
     * words rather than in the name of the class handed over, because {@code bool_new} hard-codes the string.
     * {@code long_new} goes the other way about: its argument table reads {@code x} and {@code base} together,
     * range-checking the base before it looks at what {@code x} is, so {@code int(5, 1)} is about the base being
     * one and not about five; and naming a base commits the call to the literal form, which refuses what {@code
     * int(2**70)} accepts.
     *
     * <p>What a plain call of the type cannot reach is the vectorcall fast path beside the parser: {@code int(2,
     * 3, 4)} is counted by {@code long_vectorcall} and says {@code int expected at most 2 arguments, got 3},
     * while the same call with a keyword in it goes through {@code _PyObject_MakeTpCall} and clinic says
     * {@code int() takes at most 2 arguments (3 given)} — and {@code int.__new__(int, 2, 3, 4)}, which never
     * reaches {@code long_vectorcall}, always answers with the parser.
     *
     * <p>Neither of them is asked what to build until the gates are past, and {@code bool_new}'s default is
     * {@code Py_False}, so the constructor of nothing answers False rather than the truth of the class it was
     * handed.
     */
    @Test
    void theTwoConstructorsRefuseInTheirOwnOrder() {
        refuses(
                "TypeError: bool() takes no keyword arguments",
                () -> called(PyType.BOOL, "__new__", oneKeyword(), PyType.BOOL, ONE));
        refuses(
                "TypeError: bool expected at most 1 argument, got 2",
                () -> call(PyType.BOOL, "__new__", PyType.BOOL, ONE, TWO));
        // The keyword gate is in front, so a call that is wrong both ways is wrong about the keyword first.
        refuses(
                "TypeError: bool() takes no keyword arguments",
                () -> called(PyType.BOOL, "__new__", oneKeyword(), PyType.BOOL, ONE, TWO));
        refuses(
                "TypeError: bool expected at most 1 argument, got 2",
                () -> PyType.BOOL.call(new PyObject[] {ONE, TWO}, null));

        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL)).isSameAs(PyBool.FALSE);
        assertThat(PyType.BOOL.call(new PyObject[0], null)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL, PyList.empty())).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL, PyList.of(ONE))).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL, PyStr.of("0"))).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL, PyNone.INSTANCE)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL, BIG)).isSameAs(PyBool.TRUE);
        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL, ZERO)).isSameAs(PyBool.FALSE);
        assertThat(call(PyType.BOOL, "__new__", PyType.BOOL, NEG_FIFTEEN)).isSameAs(PyBool.TRUE);

        // The base is read and checked before the value, and naming it means the literal form.
        refuses(
                "ValueError: int() base must be >= 2 and <= 36, or 0",
                () -> PyType.INT.call(new PyObject[] {FIFTEEN, ONE}, null));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> PyType.INT.call(new PyObject[] {ONE, TEXT}, null));
        refuses(
                "TypeError: int() can't convert non-string with explicit base",
                () -> PyType.INT.call(new PyObject[] {BIG, PyInt.of(10)}, null));
        // `int(base=16)`: the keyword value is the only thing in the argument list, and it is not a string.
        refuses(
                "TypeError: int() missing string argument",
                () -> PyType.INT.call(new PyObject[] {PyInt.of(16)}, named("base")));
        refuses(
                "ValueError: invalid literal for int() with base 0: '0x1z'",
                () -> PyType.INT.call(new PyObject[] {PyStr.of("0x1z"), ZERO}, null));
        refuses(
                "TypeError: int() got an unexpected keyword argument 'x'",
                () -> PyType.INT.call(new PyObject[] {ONE}, oneKeyword()));

        assertThat(PyType.INT.call(new PyObject[] {PyStr.of("ff"), PyInt.of(16)}, null)).isEqualTo(PyInt.of(255));
        assertThat(PyType.INT.call(new PyObject[] {PyStr.of("0o12"), ZERO}, null)).isEqualTo(PyInt.of(10));
        assertThat(PyType.INT.call(new PyObject[] {PyStr.of(" 12 ")}, null)).isEqualTo(PyInt.of(12));
        assertThat(PyType.INT.call(new PyObject[] {PyFloat.of(-2.7)}, null)).isEqualTo(PyInt.of(-2));
        assertThat(PyType.INT.call(new PyObject[] {PyBytes.of(new byte[] {'1', '1'}), PyInt.of(2)}, null))
                .isEqualTo(THREE);
        assertThat(kind(PyType.INT.call(new PyObject[] {PyBool.TRUE}, null))).isEqualTo("int");

        // The two wordings of one wrong count, decided by whether the call named anything.
        refuses(
                "TypeError: int expected at most 2 arguments, got 3",
                () -> PyType.INT.call(new PyObject[] {TWO, THREE, PyInt.of(4)}, null));
        refuses(
                "TypeError: int() takes at most 2 arguments (3 given)",
                () -> PyType.INT.call(new PyObject[] {TWO, THREE, PyInt.of(4)}, oneKeyword()));
        refuses(
                "TypeError: int() takes at most 2 arguments (3 given)",
                () -> call(PyType.INT, "__new__", PyType.INT, TWO, THREE, PyInt.of(4)));
    }

    /**
     * The rows a type files for a {@code tp_new} that is a null pointer.
     *
     * <p>{@code Py_TPFLAGS_DISALLOW_INSTANTIATION} is what {@code dict_keys}, {@code dict_values}, {@code
     * dict_items} and {@code version_info} are made with, and both routes to the refusal answer the same
     * sentence: {@code type_call} looks at the slot before it looks at the arguments, and {@code tp_new_wrapper}
     * walks to the same class and stops there rather than comparing creators. So calling a view says {@code
     * cannot create 'dict_keys' instances} however many arguments it is given, and {@code object.__new__(dict_
     * keys)} says it too — where every other foreign class is refused with the {@code is not safe} sentence
     * about whose creator it found. What {@code add_operators} does <em>not</em> do for them is file a {@code
     * __new__} row: it walks the slots and skips the ones that are null, so a view's dictionary holds the
     * operators it declares and no constructor at all — which is the one name a program can read off
     * {@code dict_keys.__dict__} to learn that the class builds nothing.
     */
    @Test
    void aTypeWithNoCreatorRefusesBothRoutesInTheSameWords() {
        PyObject keys = DictViews.keys(PyDict.empty());
        PyType view = keys.getType();
        assertThat(view.name()).isEqualTo("dict_keys");
        assertThat(view.declaresNoInstances()).isTrue();
        assertThat(PyType.VERSION_INFO.declaresNoInstances()).isTrue();

        refuses(
                "TypeError: cannot create 'dict_keys' instances",
                () -> view.call(new PyObject[0], null));
        refuses(
                "TypeError: cannot create 'dict_keys' instances",
                () -> view.call(new PyObject[] {PyList.of(ONE), TWO}, null));
        refuses(
                "TypeError: cannot create 'dict_keys' instances",
                () -> call(PyType.OBJECT, "__new__", view));
        refuses(
                "TypeError: cannot create 'dict_keys' instances",
                () -> call(PyType.OBJECT, "__new__", view, PyList.of(ONE)));
        refuses(
                "TypeError: cannot create 'sys.version_info' instances",
                () -> PyType.VERSION_INFO.call(new PyObject[0], null));
        refuses(
                "TypeError: cannot create 'sys.version_info' instances",
                () -> call(PyType.OBJECT, "__new__", PyType.VERSION_INFO));

        // The refusal is not filed under a name, and the two types that answer it are named as they file.
        assertThat(TypeDict.declared(view).get(PyStr.interned("__new__"))).isNull();
        assertThat(view.filedName()).isEqualTo("dict_keys");
        assertThat(PyType.VERSION_INFO.filedName()).isEqualTo("sys.version_info");
        assertThat(PyType.BOOL.call(new PyObject[0], null)).isSameAs(PyBool.FALSE);
        assertThat(PyType.INT.call(new PyObject[0], null)).isEqualTo(ZERO);
    }

    /** The names of {@code int}'s table whose {@code __doc__} is {@code None}. */
    private static List<String> refusedDocs() {
        List<String> nameless = new ArrayList<>();
        for (String name : INT_ORDER) {
            if (carried(PyType.INT, name) == null) {
                nameless.add(name);
            }
        }
        return nameless;
    }

    private static List<String> concat(List<String> before, String last) {
        List<String> all = new ArrayList<>(before);
        all.add(last);
        return all;
    }
}
