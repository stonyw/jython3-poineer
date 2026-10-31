package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * The two conversion slots, measured against CPython 3.14.10.
 *
 * <p>Corpus case 109 walks them from Python — {@code int()}, {@code float()}, {@code complex()} and the
 * six siblings of {@code %d} over a class that files each slot alone, both, badly, or not at all. What is
 * pinned here is what a script cannot point at from outside.
 *
 * <p>First the order. {@code PyNumber_Long} reads {@code __int__} and only falls through to
 * {@code __index__} when there is no {@code __int__} to read, while {@code PyNumber_Float} never reads
 * {@code __int__} at all — so a class filing {@code __int__} and {@code __index__} is the first number to
 * {@code int()} and the second to {@code float()}, and a class filing only {@code __int__} is refused by
 * {@code float()} as though it filed nothing.
 *
 * <p>Then what a reader does with a refusal. {@code mainformatlong} carries a {@code wrongtype} label that
 * rewrites a {@code TypeError} into the format's own complaint — so an {@code __int__} that answers with a
 * str, an {@code __int__} that is not callable, a class that files nothing and a class that files only
 * {@code __float__} all say the same sentence, while an {@code __int__} that raises a {@code ValueError}
 * is heard as itself. {@code formatfloat} has no such label: {@code %f} is {@code PyFloat_AsDouble} and
 * passes its errors on, including its own.
 *
 * <p>And the two-step shape of {@code complex()}, which a call either settles as one value or parses as
 * two named parts. The half it settles has one refusal for a non-number and parses a string; the half it
 * does not gates each part in the part's own words, gates both before converting either, and reads a
 * complex part as the vector it is rather than the number it looks like.
 */
class ConversionSlotTest {

    private static final BigInteger BIG = BigInteger.valueOf(2).pow(70);
    private static final PyObject THREE = PyInt.of(3);

    /** {@code type(name, bases, {members})}, with the bases handed over as Java has them. */
    private static PyType type(String name, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(PyType.OBJECT), dict}, null);
    }

    /** A {@code def __name__} that answers a fixed value, standing in for the Python-written one. */
    private static PyNativeFunction answers(String name, PyObject value) {
        return new PyNativeFunction(name, (args, kwNames) -> value);
    }

    /** A {@code def __name__} whose body raises, which is the only way to watch a caller swallow it. */
    private static PyNativeFunction raises(String name, String message) {
        return new PyNativeFunction(
                name,
                (args, kwNames) -> {
                    throw Slots.valueError("%s", message);
                });
    }

    /** A value of a class of its own, filed with the given members. */
    private static PyObject of(String kind, Object... members) {
        return type(kind, members).call(new PyObject[0], null);
    }

    /** The exception a call dies with, in CPython's own words and as its own class. */
    private static void refused(Runnable call, PyType kind, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> {
                            assertThat(raised.value().exceptionType()).isSameAs(kind);
                            assertThat(raised.value().str()).isEqualTo(message);
                        });
    }

    /** {@code template % value}, which is {@code str.__mod__} and nothing else. */
    private static String interpolated(String template, PyObject value) {
        return ((PyStr) Percent.format(PyStr.of(template), value)).getValue();
    }

    /** The two numbers of a {@code complex}, since neither half of the tower compares by value. */
    private static String parts(PyObject value) {
        PyComplex taken = (PyComplex) value;
        return taken.real() + "+" + taken.imaginary() + "j";
    }

    /** A class whose {@code __int__} answers {@code number} and whose {@code __index__} answers 3. */
    private static PyObject intAndIndex(BigInteger number) {
        return of(
                "IntIndex",
                "__int__",
                answers("__int__", PyInt.of(number)),
                "__index__",
                answers("__index__", THREE));
    }

    @Test
    void intAsksItsOwnConversionBeforeItsIndex() {
        assertThat(Numbers.longOf(THREE)).isEqualTo(BigInteger.valueOf(3));
        // PyLong_CheckExact reads an int without asking it, which is what keeps int(x) is x.
        PyObject wide = PyInt.of(BIG);
        assertThat(Constructors.parsedInteger(new PyObject[] {wide}, null)).isSameAs(wide);
        // A bool is not that exact int: CPython asks its nb_int, and the row answers with the number.
        PyObject truth = Constructors.parsedInteger(new PyObject[] {PyBool.TRUE}, null);
        assertThat(truth.getType()).isSameAs(PyType.INT);
        assertThat(Numbers.longOf(truth)).isEqualTo(BigInteger.ONE);
        // __int__ is read first, and only a class with no __int__ at all falls through to __index__.
        assertThat(Numbers.longOf(intAndIndex(BigInteger.valueOf(7)))).isEqualTo(BigInteger.valueOf(7));
        assertThat(Numbers.longOf(of("Idx", "__index__", answers("__index__", THREE))))
                .isEqualTo(BigInteger.valueOf(3));
        // A float truncates through the row it files, which is where int(2.7) and int(-2.7) both come from.
        assertThat(Numbers.longOf(PyFloat.of(2.7))).isEqualTo(BigInteger.TWO);
        assertThat(Numbers.longOf(PyFloat.of(-2.7))).isEqualTo(BigInteger.valueOf(-2));
        assertThat(Numbers.longOf(PyStr.of("12"))).isNull();
        assertThat(Numbers.longOf(of("Flt", "__float__", answers("__float__", PyFloat.of(2.5))))).isNull();
    }

    @Test
    void floatNeverBorrowsTheIntegerConversion() {
        PyObject flt = of("Flt", "__float__", answers("__float__", PyFloat.of(2.5)));
        assertThat(Numbers.floatOf(flt)).isEqualTo(2.5);
        // int() reads this class and float() refuses it: __int__ is not one of float's two slots.
        PyObject onlyInt = of("Int", "__int__", answers("__int__", THREE));
        assertThat(Numbers.longOf(onlyInt)).isEqualTo(BigInteger.valueOf(3));
        assertThat(Numbers.floatOf(onlyInt)).isNull();
        // The second of float's slots is the index, asked in full precision and then widened.
        assertThat(Numbers.floatOf(of("Idx", "__index__", answers("__index__", THREE)))).isEqualTo(3.0);
        assertThat(Numbers.floatOf(intAndIndex(BigInteger.valueOf(7)))).isEqualTo(3.0);
        assertThat(Numbers.floatOf(THREE)).isEqualTo(3.0);
        // A float is the one argument read without being asked, which is what keeps float(x) is x. A class
        // that files __float__ is asked, and what float() hands back is the answer rather than the value.
        PyFloat held = PyFloat.of(2.5);
        assertThat(Constructors.floating(new PyObject[] {held}, null)).isSameAs(held);
        assertThat(((PyFloat) Constructors.floating(new PyObject[] {flt}, null)).getValue()).isEqualTo(2.5);
        // The widening is the one refusal a conversion by index can raise.
        refused(
                () -> Numbers.floatOf(PyInt.of(BigInteger.valueOf(2).pow(10000))),
                ExceptionTypes.OVERFLOW_ERROR,
                "int too large to convert to float");
    }

    @Test
    void aSlotAnsweringTheWrongKindIsRefusedForItsAnswer() {
        PyObject bad = of("IntBad", "__int__", answers("__int__", PyStr.of("seven")));
        refused(
                () -> Numbers.longOf(bad),
                ExceptionTypes.TYPE_ERROR,
                "__int__ returned non-int (type str)");
        // The complaint about a bad __float__ names both sides, and the one about a bad __int__ only one.
        PyObject worse = of("FltBad", "__float__", answers("__float__", THREE));
        refused(
                () -> Numbers.floatOf(worse),
                ExceptionTypes.TYPE_ERROR,
                "FltBad.__float__ returned non-float (type int)");
        // A row that is not a method at all is still a row, so the refusal is about calling it.
        refused(
                () -> Numbers.longOf(of("IntNone", "__int__", PyNone.INSTANCE)),
                ExceptionTypes.TYPE_ERROR,
                "'NoneType' object is not callable");
        // What the body itself raises is not a refusal of the conversion, and no reader rewords it.
        PyObject raised = of("IntRaised", "__int__", raises("__int__", "boom"));
        refused(() -> Numbers.longOf(raised), ExceptionTypes.VALUE_ERROR, "boom");
        refused(
                () -> Numbers.floatOf(of("FltRaised", "__float__", raises("__float__", "boom"))),
                ExceptionTypes.VALUE_ERROR,
                "boom");
    }

    @Test
    void theNumberGateIsTheThreeSlotsAndTheNameComplex() {
        assertThat(Numbers.isNumber(THREE)).isTrue();
        assertThat(Numbers.isNumber(PyBool.FALSE)).isTrue();
        assertThat(Numbers.isNumber(PyFloat.of(1.5))).isTrue();
        // complex is a number by name: its tp_as_number carries neither nb_int, nb_float nor nb_index.
        assertThat(Numbers.isNumber(PyComplex.of(1, 2))).isTrue();
        assertThat(Numbers.isNumber(of("Int", "__int__", answers("__int__", THREE)))).isTrue();
        // Text, containers and the plain object file none of the three, however convertible they look:
        // a __str__ and a __len__ are not conversions, and PyNumber_Check reads tp_as_number's three.
        for (String name : new String[] {"__str__", "__len__"}) {
            assertThat(Numbers.isNumber(of("One" + name, name, PyInt.ONE))).as(name).isFalse();
        }
        assertThat(Numbers.isNumber(PyStr.of("12"))).isFalse();
        assertThat(Numbers.isNumber(PyBytes.of(new byte[] {1}))).isFalse();
        assertThat(Numbers.isNumber(PyNone.INSTANCE)).isFalse();
        assertThat(Numbers.isNumber(PyType.OBJECT)).isFalse();
        // The two slots that do open the gate are the ones the readers borrow.
        assertThat(Numbers.isNumber(of("Flt", "__float__", answers("__float__", PyFloat.of(2.5)))))
                .isTrue();
        assertThat(Numbers.isNumber(of("Idx", "__index__", answers("__index__", THREE)))).isTrue();
        // complex's gate is the same two slots minus __int__, which is why int() reads what it refuses.
        assertThat(Numbers.hasRealForm(THREE)).isTrue();
        assertThat(Numbers.hasRealForm(PyComplex.of(1, 2))).isTrue();
        assertThat(Numbers.hasRealForm(of("Int", "__int__", answers("__int__", THREE)))).isFalse();
        assertThat(Numbers.hasRealForm(PyStr.of("1"))).isFalse();
    }

    @Test
    void integerFieldsRewordTypeErrorsAndHearEverythingElse() {
        PyObject good = of("Int", "__int__", answers("__int__", PyInt.of(7)));
        assertThat(interpolated("%d", good)).isEqualTo("7");
        assertThat(interpolated("%d", PyFloat.of(2.7))).isEqualTo("2");
        assertThat(interpolated("%d", PyBool.TRUE)).isEqualTo("1");
        // The four refusals CPython's wrongtype label puts the same words on, each of them a TypeError the
        // conversion raised and the format answered for.
        refused(
                () -> interpolated("%d", of("IntBad", "__int__", answers("__int__", PyStr.of("x")))),
                ExceptionTypes.TYPE_ERROR,
                "%d format: a real number is required, not IntBad");
        refused(
                () -> interpolated("%d", of("IntNone", "__int__", PyNone.INSTANCE)),
                ExceptionTypes.TYPE_ERROR,
                "%d format: a real number is required, not IntNone");
        refused(
                () -> interpolated("%d", of("Flt", "__float__", answers("__float__", PyFloat.of(2.5)))),
                ExceptionTypes.TYPE_ERROR,
                "%d format: a real number is required, not Flt");
        refused(
                () -> interpolated("%d", PyStr.of("12")),
                ExceptionTypes.TYPE_ERROR,
                "%d format: a real number is required, not str");
        // An error the body raises is not a refusal of the conversion, and is passed on as it came.
        refused(
                () -> interpolated("%d", of("IntRaised", "__int__", raises("__int__", "boom"))),
                ExceptionTypes.VALUE_ERROR,
                "boom");
        // A float's own NaN and infinity reach the same reader through the row it files.
        refused(
                () -> interpolated("%d", PyFloat.of(Double.NaN)),
                ExceptionTypes.VALUE_ERROR,
                "cannot convert float NaN to integer");
        refused(
                () -> interpolated("%d", PyFloat.of(Double.POSITIVE_INFINITY)),
                ExceptionTypes.OVERFLOW_ERROR,
                "cannot convert float infinity to integer");
    }

    @Test
    void hexadecimalFieldsWantAnIndexAndNothingElse() {
        PyObject intOnly = of("Int", "__int__", answers("__int__", PyInt.of(7)));
        // __int__ is not read on this path at all, so the class is refused before its body runs.
        refused(
                () -> interpolated("%x", intOnly),
                ExceptionTypes.TYPE_ERROR,
                "%x format: an integer is required, not Int");
        refused(
                () -> interpolated("%x", of("IntRaised", "__int__", raises("__int__", "boom"))),
                ExceptionTypes.TYPE_ERROR,
                "%x format: an integer is required, not IntRaised");
        assertThat(interpolated("%x", of("Idx", "__index__", answers("__index__", THREE)))).isEqualTo("3");
        assertThat(interpolated("%x", PyInt.of(255))).isEqualTo("ff");
        // A float is a number for %d and not one for %x, which is the whole difference between the two.
        refused(
                () -> interpolated("%x", PyFloat.of(2.7)),
                ExceptionTypes.TYPE_ERROR,
                "%x format: an integer is required, not float");
        refused(
                () -> interpolated("%x", of("Bad", "__index__", answers("__index__", PyFloat.of(1.5)))),
                ExceptionTypes.TYPE_ERROR,
                "%x format: an integer is required, not Bad");
        // The three decimal siblings share %d's converter, each naming the character it was written with.
        assertThat(interpolated("%i", intOnly)).isEqualTo("7");
        assertThat(interpolated("%u", intOnly)).isEqualTo("7");
        assertThat(interpolated("%o", PyInt.of(8))).isEqualTo("10");
    }

    @Test
    void floatFieldsPassTheirErrorsThroughUnreworded() {
        assertThat(interpolated("%f", of("Flt", "__float__", answers("__float__", PyFloat.of(2.5)))))
                .isEqualTo("2.500000");
        assertThat(interpolated("%f", THREE)).isEqualTo("3.000000");
        assertThat(interpolated("%f", of("Idx", "__index__", answers("__index__", THREE))))
                .isEqualTo("3.000000");
        // The refusal is the converter's own short sentence, which has no format character in it.
        refused(
                () -> interpolated("%f", of("Int", "__int__", answers("__int__", THREE))),
                ExceptionTypes.TYPE_ERROR,
                "must be real number, not Int");
        refused(
                () -> interpolated("%f", PyComplex.of(1, 2)),
                ExceptionTypes.TYPE_ERROR,
                "must be real number, not complex");
        // And nothing on this path is reworded, so a bad answer is heard as the converter names it.
        refused(
                () -> interpolated("%f", of("FltBad", "__float__", answers("__float__", THREE))),
                ExceptionTypes.TYPE_ERROR,
                "FltBad.__float__ returned non-float (type int)");
        refused(
                () -> interpolated("%f", of("Bad", "__index__", answers("__index__", PyFloat.of(1.5)))),
                ExceptionTypes.TYPE_ERROR,
                "__index__ returned non-int (type float)");
        refused(
                () -> interpolated("%f", of("FltNone", "__float__", PyNone.INSTANCE)),
                ExceptionTypes.TYPE_ERROR,
                "'NoneType' object is not callable");
    }

    @Test
    void complexSettlesOneValueAndGatesTwoParts() {
        PyObject lone = PyComplex.of(1, 2);
        assertThat(Constructors.complexing(new PyObject[] {lone}, null)).isSameAs(lone);
        assertThat(parts(Constructors.complexing(new PyObject[] {PyStr.of("1+2j")}, null)))
                .isEqualTo("1.0+2.0j");
        // A name sends the call to the clinic half, where a string is only the wrong kind of number.
        refused(
                () -> Constructors.complexing(new PyObject[] {PyStr.of("1+2j")}, new String[] {"real"}),
                ExceptionTypes.TYPE_ERROR,
                "complex() argument 'real' must be a real number, not str");
        // Two values still reach the clinic half, where the first is named in the refusal.
        refused(
                () -> Constructors.complexing(
                        new PyObject[] {PyStr.of("1"), THREE}, new String[] {"imag"}),
                ExceptionTypes.TYPE_ERROR,
                "complex() argument 'real' must be a real number, not str");
        // The single-value refusal is the one without a parameter name in it.
        refused(
                () -> Constructors.complexing(
                        new PyObject[] {of("Int", "__int__", answers("__int__", THREE))}, null),
                ExceptionTypes.TYPE_ERROR,
                "complex() argument must be a string or a number, not Int");
        // Both gates are read before either part is converted, in the order the parameters are named.
        refused(
                () -> Constructors.complexing(
                        new PyObject[] {
                            of("FltRaised", "__float__", raises("__float__", "boom")),
                            of("Nothing")
                        },
                        null),
                ExceptionTypes.TYPE_ERROR,
                "complex() argument 'imag' must be a real number, not Nothing");
        assertThat(parts(Constructors.complexing(
                        new PyObject[] {
                            of("Flt", "__float__", answers("__float__", PyFloat.of(2.5))), THREE
                        },
                        null)))
                .isEqualTo("2.5+3.0j");
    }

    @Test
    void aComplexPartCrossesOverInsteadOfBeingTakenAtFaceValue() {
        // complex(1, 2j) is 1 + 2j * i, which is -1: the second argument's own imaginary part is what
        // lands in the first direction, and its real part is dropped.
        assertThat(parts(Constructors.complexing(
                        new PyObject[] {PyInt.of(1), PyComplex.of(0, 2)}, null)))
                .isEqualTo("-1.0+0.0j");
        assertThat(parts(Constructors.complexing(
                        new PyObject[] {PyComplex.of(0, 1), PyInt.of(2)}, null)))
                .isEqualTo("0.0+3.0j");
        assertThat(
                        parts(
                                Constructors.complexing(
                                        new PyObject[] {PyComplex.of(0, 1), PyComplex.of(0, 2)}, null)))
                .isEqualTo("-2.0+1.0j");
        // With no second value given, the first one's own parts are simply kept.
        assertThat(parts(Constructors.complexing(new PyObject[] {PyComplex.of(3, 4)}, null)))
                .isEqualTo("3.0+4.0j");
    }
}
