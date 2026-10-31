package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The twenty-two names {@code range} files, measured against CPython 3.14.8.
 *
 * <p>Corpus case 111 reads the whole table from Python, one line per answer. What is pinned here is the half a
 * script cannot point at. First the table as the complete ordered map it is: the {@code tp_new_wrapper} row, the
 * thirteen wrappers behind {@code PyRange_Type}'s own {@code tp_repr}, {@code tp_hash}, {@code tp_richcompare},
 * {@code tp_iter} and {@code nb_bool} and its two protocol tables, the four of {@code range_methods}
 * (Objects/rangeobject.c:766), the three of {@code range_members} ({@code :774}) and the type's own sentence —
 * and eleven of those twenty-two are names {@code object} files too, which is why {@code len(vars(range))} is
 * twenty-two while {@code dir(range)} is only thirty-five. A reordered or duplicated row is only caught by
 * comparing the dictionary, the {@link Members} table and an ordinary read of the same name.
 *
 * <p>Then the two invented argument tables: {@code rangeobject.count(value)} names the method by the type it is
 * reached through rather than by itself, so {@code find_signature} declines it and {@code signature_from_flags}
 * writes {@code ($self, object, /)} out of the {@code METH_O} — and {@code range_reduce} is filed with no {@code
 * ml_doc} at all, so one row of the four answers {@code None} for its sentence while still speaking the {@code
 * ($self, /)} a {@code METH_NOARGS} answers. Then the three fields, {@code Py_T_OBJECT_EX} offsets that hand back
 * the very parameter the range was built with. Then the two rows that disagree about width: {@code range_length}
 * is a {@code Py_ssize_t} and {@code range_subscript} an index of any size, so the same bound overflows in one
 * and is merely refused in the other. Then {@code compute_slice}, the arbitrary-precision half, whose answer is a
 * range of the same kind and whose step is the product of the two. Then {@code range_contains}, where an integer
 * is asked of the arithmetic and anything else is walked for with {@code ==} — which is why {@code 2 ** 68} is
 * found in a range of {@code 2 ** 69} values without a single step, while a value equal to everything is found in
 * every one of four. Then {@code range_equals} with its two short circuits and {@code range_hash} with its three
 * lanes. And last {@code range_reverse}, which is arithmetic too.
 */
class RangeTableTest {

    /** The twenty-two names {@code range} files, in the order its dictionary answers them. */
    private static final List<String> ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__iter__", "__bool__", "__len__", "__getitem__", "__contains__",
                    "__reversed__", "__reduce__", "count", "index", "start", "stop", "step", "__doc__");

    /** The thirteen rows that {@code PyRange_Type}'s own slot functions file as wrappers. */
    private static final List<String> WRAPPERS = ORDER.subList(1, 14);

    /** The four rows {@code range_methods} files, in the order it writes them. */
    private static final List<String> METHODS =
            List.of("__reversed__", "__reduce__", "count", "index");

    /** The three rows {@code range_members} files, each one {@code Py_T_OBJECT_EX} marked {@code Py_READONLY}. */
    private static final List<String> FIELDS = List.of("start", "stop", "step");

    /** The eleven rows of the twenty-two that {@code object} files as well, so {@code dir()} does not grow. */
    private static final List<String> SHARED =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__reduce__", "__doc__");

    /** The eleven rows that are this type's own, and the only eleven a program gains by asking {@code dir()}. */
    private static final List<String> OWN =
            List.of(
                    "__bool__", "__contains__", "__getitem__", "__iter__", "__len__", "__reversed__", "count",
                    "index", "start", "step", "stop");

    private static final String COUNT_DOC =
            "rangeobject.count(value) -> integer -- return number of occurrences of value";

    private static final String INDEX_DOC =
            "rangeobject.index(value) -> integer -- return index of value.\n"
                    + "Raise ValueError if the value is not present.";

    /** The eight lines of {@code range_doc}, kept whole because none of them is an argument table. */
    private static final List<String> TYPE_DOC =
            List.of(
                    "range(stop) -> range object",
                    "range(start, stop[, step]) -> range object",
                    "",
                    "Return an object that produces a sequence of integers from start (inclusive)",
                    "to stop (exclusive) by step.  range(i, j) produces i, i+1, i+2, ..., j-1.",
                    "start defaults to 0, and stop is omitted!  range(4) produces 0, 1, 2, 3.",
                    "These are exactly the valid indices for a list of 4 elements.",
                    "When step is given, it specifies the increment (or decrement).");

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final BigInteger ONE_BI = BigInteger.ONE;
    private static final BigInteger TWO_BI = BigInteger.TWO;
    private static final BigInteger ZERO_BI = BigInteger.ZERO;

    private static final PyObject ZERO = PyInt.ZERO;
    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject FIVE = PyInt.of(5);
    private static final PyObject SEVEN = PyInt.of(7);
    private static final PyObject EIGHT = PyInt.of(8);
    private static final PyObject NINE = PyInt.of(9);
    private static final PyObject TEN = PyInt.of(10);
    private static final PyObject HUNDRED = PyInt.of(100);
    private static final PyObject MINUS_ONE = PyInt.of(-1);
    private static final PyObject MINUS_TWO = PyInt.of(-2);
    private static final PyObject MINUS_THREE = PyInt.of(-3);

    /** {@code 2 ** 70}: the width a C {@code long} cannot hold and a range bound can. */
    private static final PyObject WIDE = PyInt.of(two(70));

    private static final PyObject WIDE_BACK = PyInt.of(two(70).negate());

    /** {@code range(1, 9, 2)}: the four odd values every membership question here is asked of. */
    private static final PyRange ODD = range(1, 9, 2);

    /** {@code range(0, 1)}: the one-value range whose step both the equality and the hash ignore. */
    private static final PyRange SINGLE = range(0, 1, 1);

    /** {@code range(0)}: the empty range, which equals and hashes like every other empty one. */
    private static final PyRange EMPTY = range(0, 0, 1);

    /** {@code range(0, 20, 3)}: the stepped range a slice of is folded against and its step multiplied by. */
    private static final PyRange STEPPED = range(0, 20, 3);

    private static final PyRange BACKWARD = range(0, 20, -3);

    /** {@code range(0, 3, 2 ** 70)}: one value, and a step no machine word holds. */
    private static final PyRange WIDE_RANGE = range(ZERO_BI, BigInteger.valueOf(3), two(70));

    /** {@code range(0, 2 ** 70, 2 ** 69)}: two values, a word apart and reversed without a walk. */
    private static final PyRange WIDE_PAIR = range(ZERO_BI, two(70), two(69));

    /** {@code range(0, 2 ** 80, 2 ** 70)}: a thousand values, which nothing here may walk. */
    private static final PyRange MANY_WIDE = range(ZERO_BI, two(80), two(70));

    /** {@code range(0, 2 ** 200, 2)}: a range of {@code 2 ** 199} values, which no walk can finish. */
    private static final PyRange UNWALKABLE = range(ZERO_BI, two(200), TWO_BI);

    /** {@code range(0, 2 ** 100, 2 ** 90)}: bounds wide enough that only arithmetic can fold them. */
    private static final PyRange HUGE_RANGE = range(ZERO_BI, two(100), two(90));

    /** {@code range(0, 2 ** 100)}: a range no word can count, whose middle element arithmetic reaches. */
    private static final PyRange HUGE_STOP = range(ZERO_BI, two(100), ONE_BI);

    private static final PyObject TEXT = PyStr.of("3");

    /** {@code 2 ** exponent}, the width every wide bound here is written at. */
    private static BigInteger two(int exponent) {
        return BigInteger.valueOf(2).pow(exponent);
    }

    /** {@code range(start, stop, step)} as three machine words. */
    private static PyRange range(long start, long stop, long step) {
        return PyRange.of(BigInteger.valueOf(start), BigInteger.valueOf(stop), BigInteger.valueOf(step));
    }

    /** {@code range(start, stop, step)} at any width, the three bounds as the numbers CPython holds. */
    private static PyRange range(BigInteger start, BigInteger stop, BigInteger step) {
        return PyRange.of(start, stop, step);
    }

    /** The entry {@code range} files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.RANGE).get(PyStr.interned(name));
    }

    /** The names a kind's own dictionary keeps, in the order the dictionary kept them. */
    private static List<String> filed(PyType owner) {
        PyIterator keys = (PyIterator) ((PyMappingProxy) Slots.getAttr(owner, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** {@code range(*args)}, the way the interpreter calls a type. */
    private static PyObject ranging(PyObject... args) {
        return PyType.RANGE.call(args, null);
    }

    /** {@code range.__dict__[name](*args)}: a filed row, called without an attribute read. */
    private static PyObject call(String name, PyObject... args) {
        return Slots.call(entry(name), args, null);
    }

    /** {@code range.__dict__[name](*args, **kwargs)}, which is what sends a row to its argument parser. */
    private static PyObject calledWith(String name, PyTuple kwNames, PyObject... args) {
        return Slots.call(entry(name), args, kwNames);
    }

    /** {@code range.__dict__[field].__get__(*args)}: the descriptor protocol, called as Python calls it. */
    private static PyObject getRow(String field, PyObject... args) {
        return Slots.call(Slots.getAttr(entry(field), "__get__"), args, null);
    }

    /** {@code slice(start, stop, step)} with {@code null} written for the {@code None} a shape leaves out. */
    private static PyObject sliceOf(PyObject start, PyObject stop, PyObject step) {
        return PySlice.of(start == null ? NONE : start, stop == null ? NONE : stop, step == null ? NONE : step);
    }

    /** {@code seq[a:b]} and {@code seq[a:b:c]} as the subscript the type's own row then reads. */
    private static PyObject sliced(PyObject sequence, PyObject start, PyObject stop) {
        return sequence.getItem(sliceOf(start, stop, null));
    }

    private static PyObject sliced(PyObject sequence, PyObject start, PyObject stop, PyObject step) {
        return sequence.getItem(sliceOf(start, stop, step));
    }

    /** {@code list(x)} over a range's own walk, as the decimal strings the answers can be compared by. */
    private static List<String> walked(PyObject source) {
        List<String> digits = new ArrayList<>();
        for (PyObject value : Constructors.materialise(source)) {
            digits.add(((PyInt) value).getValue().toString());
        }
        return digits;
    }

    /** The value of a class that writes {@code __index__} itself, which the index protocol then asks. */
    private static PyObject indexAnswering(PyObject answer) {
        PyDict dict = PyDict.empty();
        dict.put(
                PyStr.interned("__index__"), new PyNativeFunction("__index__", (args, kwNames) -> answer));
        return instantiated("AnswersIndex", dict);
    }

    /** The value of a class that writes nothing at all, which neither the arithmetic nor a slice can be. */
    private static PyObject nothing() {
        return instantiated("Nothing", PyDict.empty());
    }

    /** The value of a class equal to everything, which only a walk that asks its own {@code __eq__} finds. */
    private static PyObject equalsEverything() {
        return new Everything();
    }

    /** A value whose {@code __eq__} answers the integers a range walks, which {@code int} alone cannot say. */
    private static final class Everything extends PyObject {

        Everything() {
            super(PyType.OBJECT);
        }

        @Override
        public PyObject richCompare(CompareOp cmp, PyObject other) {
            if (cmp == CompareOp.EQ) {
                return PyBool.TRUE;
            }
            return cmp == CompareOp.NE ? PyBool.FALSE : Slots.DECLINED;
        }

        @Override
        public long hash() {
            return 0;
        }
    }

    /** {@code C()} for a class {@code C} the test writes itself, over {@code object} and nothing else. */
    private static PyObject instantiated(String name, PyDict dict) {
        return Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(PyType.OBJECT), dict}, null)
                .call(new PyObject[0], null);
    }

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The twenty-two rows in the order the three C lists wrote them.
     *
     * <p>The dictionary is filled in filing order, so the thirteen wrappers land between the constructor and the
     * four methods, and the three fields land last but one. Eleven of the twenty-two are names {@code object}
     * files as well, and the eleven it does not — the five protocol rows, {@code __reversed__} and {@code count}
     * and {@code index} and the three bounds — are the whole of what a program gains by asking {@code dir()}. The
     * rows read off the class are the rows themselves: dictionary, {@link Members} table and ordinary read answer
     * one object, because neither a {@code method_descriptor} nor a {@code member_descriptor} binds on the way
     * out. {@code PyRange_Type} writes both a sequence half and a number half, so where {@code slice} files
     * fifteen rows this one files twenty-two — and still writes no {@code tp_str} and no {@code tp_init} of its
     * own, which is why {@code str()} on a range is the repr row's answer.
     */
    @Test
    void twentyTwoRowsFileTheOrderTheThreeCListsWrote() {
        assertThat(filed(PyType.RANGE)).isEqualTo(ORDER);
        assertThat(ORDER).hasSize(22);
        assertThat(TypeDict.declared(PyType.RANGE).keys()).hasSize(22);
        assertThat(Arrays.asList(Members.tableOf(PyType.RANGE).names()))
                .containsExactlyInAnyOrderElementsOf(METHODS);

        assertThat(ORDER).startsWith("__new__").endsWith("__doc__");
        assertThat(WRAPPERS).hasSize(13).startsWith("__repr__").endsWith("__contains__");
        assertThat(ORDER.subList(14, 18)).isEqualTo(METHODS);
        assertThat(ORDER.subList(18, 21)).isEqualTo(FIELDS);
        assertThat(ORDER).hasSize(SHARED.size() + OWN.size());
        assertThat(SHARED).doesNotContainAnyElementsOf(OWN);
        assertThat(ORDER.indexOf("__iter__") - ORDER.indexOf("__ge__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__len__") - ORDER.indexOf("__bool__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__getitem__") - ORDER.indexOf("__len__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__reversed__") - ORDER.indexOf("__contains__")).isEqualTo(1);

        assertThat(entry("__new__").getType().name()).isEqualTo("builtin_function_or_method");
        for (String name : WRAPPERS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("wrapper_descriptor");
            assertThat(entry(name)).as(name).isSameAs(Slots.getAttr(PyType.RANGE, name));
        }
        for (String name : METHODS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("method_descriptor");
            assertThat(entry(name)).isSameAs(Slots.getAttr(PyType.RANGE, name));
            assertThat(entry(name)).isSameAs(Members.tableOf(PyType.RANGE).describe(name));
        }
        for (String name : FIELDS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("member_descriptor");
            assertThat(entry(name)).isSameAs(Slots.getAttr(PyType.RANGE, name));
        }
        assertThat(entry("__doc__").getType()).isSameAs(PyType.STR);

        // Neither the constructor row nor a slot row binds, so a read off the class answers the entry itself.
        assertThat(Slots.getAttr(PyType.RANGE, "__new__")).isSameAs(entry("__new__"));
        assertThat(Slots.getAttr(PyType.RANGE, "__eq__")).isSameAs(entry("__eq__"));
        assertThat(Slots.getAttr(PyType.RANGE, "__len__")).isSameAs(entry("__len__"));

        for (String name : SHARED) {
            assertThat(TypeDict.declared(PyType.OBJECT).get(PyStr.interned(name))).as(name).isNotNull();
        }
        for (String name : OWN) {
            assertThat(TypeDict.declared(PyType.OBJECT).get(PyStr.interned(name))).as(name).isNull();
        }

        // Absent rather than filed and declining: a range writes no str, no init and no getnewargs of its own.
        for (String name : List.of(
                "__str__", "__init__", "__format__", "__getnewargs__", "__subclasshook__", "__setitem__")) {
            assertThat(TypeDict.declared(PyType.RANGE).get(PyStr.interned(name))).as(name).isNull();
        }
        assertThat(ORDER).doesNotContain("__reduce_ex__", "__class_getitem__", "__call__", "__int__");
    }

    /**
     * The four sentences {@code range_methods} carries, and the tables its flags invent for them.
     *
     * <p>{@code rangeobject.count(value)} and {@code rangeobject.index(value)} name the method by the type it is
     * reached through rather than by itself, so {@code find_signature} finds no table above either text and both
     * {@code METH_O} rows read {@code ($self, object, /)} out of the flag — the same invented table {@code
     * slice.indices} answers with. {@code __reduce__} is the one row of the four filed with no {@code ml_doc}
     * beside it at all: its sentence is {@code None} while its signature is still the {@code ($self, /)} a {@code
     * METH_NOARGS} answers. The thirteen wrappers carry CPython's own slot sentences, and the three fields no
     * table whatever, since a {@code member_descriptor} keeps a name and an {@code __objclass__} and answers
     * nothing else.
     */
    @Test
    void theFourMethodRowsSpeakTheTablesTheirFlagsInvent() {
        assertThat(Slots.getAttr(entry("count"), "__doc__")).isEqualTo(PyStr.of(COUNT_DOC));
        assertThat(Slots.getAttr(entry("index"), "__doc__")).isEqualTo(PyStr.of(INDEX_DOC));
        assertThat(Slots.getAttr(entry("__reversed__"), "__doc__"))
                .isEqualTo(PyStr.of("Return a reverse iterator."));
        assertThat(Slots.getAttr(entry("__reduce__"), "__doc__")).isSameAs(NONE);
        assertThat(Slots.getAttr(entry("__new__"), "__doc__"))
                .isEqualTo(
                        PyStr.of("Create and return a new object.  See help(type) for accurate signature."));

        for (String name : List.of("count", "index")) {
            assertThat(Slots.getAttr(entry(name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, object, /)"));
        }
        assertThat(Slots.getAttr(entry("__new__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($type, *args, **kwargs)"));

        assertThat(Slots.getAttr(entry("__repr__"), "__doc__")).isEqualTo(PyStr.of("Return repr(self)."));
        assertThat(Slots.getAttr(entry("__hash__"), "__doc__")).isEqualTo(PyStr.of("Return hash(self)."));
        assertThat(Slots.getAttr(entry("__lt__"), "__doc__")).isEqualTo(PyStr.of("Return self<value."));
        assertThat(Slots.getAttr(entry("__le__"), "__doc__")).isEqualTo(PyStr.of("Return self<=value."));
        assertThat(Slots.getAttr(entry("__eq__"), "__doc__")).isEqualTo(PyStr.of("Return self==value."));
        assertThat(Slots.getAttr(entry("__ne__"), "__doc__")).isEqualTo(PyStr.of("Return self!=value."));
        assertThat(Slots.getAttr(entry("__gt__"), "__doc__")).isEqualTo(PyStr.of("Return self>value."));
        assertThat(Slots.getAttr(entry("__ge__"), "__doc__")).isEqualTo(PyStr.of("Return self>=value."));
        assertThat(Slots.getAttr(entry("__iter__"), "__doc__")).isEqualTo(PyStr.of("Implement iter(self)."));
        assertThat(Slots.getAttr(entry("__bool__"), "__doc__")).isEqualTo(PyStr.of("True if self else False"));
        assertThat(Slots.getAttr(entry("__len__"), "__doc__")).isEqualTo(PyStr.of("Return len(self)."));
        assertThat(Slots.getAttr(entry("__getitem__"), "__doc__")).isEqualTo(PyStr.of("Return self[key]."));
        assertThat(Slots.getAttr(entry("__contains__"), "__doc__"))
                .isEqualTo(PyStr.of("Return bool(key in self)."));

        for (String name : List.of("__repr__", "__hash__", "__iter__", "__bool__", "__len__", "__reversed__")) {
            assertThat(Slots.getAttr(entry(name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, /)"));
        }
        for (String name : List.of("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")) {
            assertThat(Slots.getAttr(entry(name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, value, /)"));
        }
        for (String name : List.of("__getitem__", "__contains__")) {
            assertThat(Slots.getAttr(entry(name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, key, /)"));
        }
        for (String name : FIELDS) {
            refuses(
                    "AttributeError: 'member_descriptor' object has no attribute '__text_signature__'",
                    () -> Slots.getAttr(entry(name), "__text_signature__"));
        }

        for (String name : ORDER) {
            if (name.equals("__doc__")) {
                continue;
            }
            assertThat(Slots.getAttr(entry(name), "__qualname__"))
                    .as(name)
                    .isEqualTo(PyStr.of("range." + name));
        }
        for (String name : METHODS) {
            assertThat(Slots.getAttr(entry(name), "__objclass__")).as(name).isSameAs(PyType.RANGE);
            assertThat(Slots.getAttr(entry(name), "__name__")).as(name).isEqualTo(PyStr.of(name));
        }
        for (String name : WRAPPERS) {
            assertThat(Slots.getAttr(entry(name), "__objclass__")).as(name).isSameAs(PyType.RANGE);
        }
        for (String name : FIELDS) {
            assertThat(Slots.getAttr(entry(name), "__objclass__")).isSameAs(PyType.RANGE);
            assertThat(Slots.getAttr(entry(name), "__name__")).isEqualTo(PyStr.of(name));
            // A `PyMemberDef` written without a docstring, which is what makes the row answer `None`.
            assertThat(Slots.getAttr(entry(name), "__doc__")).isSameAs(NONE);
            assertThat(entry(name).repr()).isEqualTo("<member '" + name + "' of 'range' objects>");
        }
        assertThat(entry("count").repr()).isEqualTo("<method 'count' of 'range' objects>");
        assertThat(entry("__reduce__").repr()).isEqualTo("<method '__reduce__' of 'range' objects>");
        assertThat(entry("__eq__").repr()).isEqualTo("<slot wrapper '__eq__' of 'range' objects>");
        assertThat(entry("__bool__").repr()).isEqualTo("<slot wrapper '__bool__' of 'range' objects>");
    }

    /**
     * The type's sentence is {@code PyRange_Type.tp_doc} whole, because it holds no table to cut out.
     *
     * <p>{@code range_doc} opens with the two shapes the constructor takes and then says what a range produces, in
     * eight lines the signature knife reads as a table only to give up on: no {@code --} anywhere in the text, and
     * no closing bracket for it to follow. So {@code range.__text_signature__} is {@code None} and the row is the
     * whole thing, filed as a plain {@code str} in the dictionary rather than as a descriptor. Both reads make the
     * sentence again, so neither answer is the entry, and a value reaches it through {@code object}'s own {@code
     * __doc__} getset exactly as a slice does.
     */
    @Test
    void theTypeSentenceIsTpDocWholeBecauseItCarriesNoArgumentTable() {
        PyObject throughClass = Slots.getAttr(PyType.RANGE, "__doc__");
        PyObject throughValue = Slots.getAttr(ODD, "__doc__");

        assertThat(throughClass).isEqualTo(entry("__doc__"));
        assertThat(throughClass).isNotSameAs(entry("__doc__"));
        assertThat(throughValue).isEqualTo(throughClass);
        assertThat(throughValue).isNotSameAs(throughClass);
        assertThat(throughClass.getType()).isSameAs(PyType.STR);
        assertThat(Slots.getAttr(PyType.RANGE, "__text_signature__")).isEqualTo(NONE);

        List<String> lines = Arrays.asList(((PyStr) throughClass).getValue().split("\n"));
        assertThat(lines).isEqualTo(TYPE_DOC);
        assertThat(lines).hasSize(8);
        assertThat(lines.get(2)).isEmpty();
        assertThat(((PyStr) throughClass).getValue()).doesNotContain("--");
        assertThat(((PyStr) entry("__doc__")).getValue()).startsWith("range(stop) -> range object");
    }

    /**
     * The three fields are object offsets, so they answer the object stored and refuse every way of writing.
     *
     * <p>{@code range_members} files all three as {@code Py_T_OBJECT_EX} with {@code Py_READONLY}, which makes
     * each one a {@code member_descriptor} handing back the integer the range already holds: the same object twice
     * for the same range, at any width — {@code range(0, 3, 2 ** 70).step} is the wide number, not a copy of it
     * and not a saturated one. All four refusals come from {@code PyMember_SetOne} before it looks at the value,
     * so {@code del}, {@code __set__} and {@code __delete__} say the same two words an assignment does, and the
     * class-level write is refused one level up by {@code type_setattro}, whose message says {@code cannot set}
     * whichever way the write went. The receiver check quotes {@code tp_name} and the field rather than the slot.
     */
    @Test
    void theThreeFieldsHandBackTheVeryParameterTheRangeWasBuiltWith() {
        assertThat(Slots.getAttr(ODD, "start")).isSameAs(Slots.getAttr(ODD, "start"));
        assertThat(Slots.getAttr(ODD, "start")).isEqualTo(ONE);
        assertThat(Slots.getAttr(ODD, "stop")).isEqualTo(NINE);
        assertThat(Slots.getAttr(ODD, "step")).isEqualTo(TWO);
        assertThat(Slots.getAttr(WIDE_RANGE, "step")).isSameAs(WIDE_RANGE.step());
        assertThat(Slots.getAttr(WIDE_RANGE, "step")).isEqualTo(WIDE);
        assertThat(Slots.getAttr(WIDE_RANGE, "stop")).isSameAs(WIDE_RANGE.stop());
        assertThat(Slots.getAttr(WIDE_RANGE, "start")).isSameAs(WIDE_RANGE.start());
        assertThat(((PyRange) ranging(WIDE)).start()).isSameAs(((PyRange) ranging(WIDE)).start());
        assertThat(Slots.getAttr(ranging(FOUR), "start")).isEqualTo(ZERO);
        assertThat(Slots.getAttr(ranging(FOUR), "stop")).isEqualTo(FOUR);
        assertThat(Slots.getAttr(ranging(FOUR), "step")).isEqualTo(ONE);

        for (String name : FIELDS) {
            // A `PyMemberDesc_Type` keeps a `tp_descr_set` even read-only, so the field is a data
            // descriptor — and a range value has no instance dictionary for it to outrank.
            assertThat(((Descriptors.Protocol) entry(name)).writes()).as(name).isTrue();
        }
        refuses(
                "AttributeError: 'range' object has no attribute '__dict__'",
                () -> Slots.getAttr(ODD, "__dict__"));

        String readonly = "AttributeError: readonly attribute";
        refuses(readonly, () -> Slots.setAttr(ODD, "start", ZERO));
        refuses(readonly, () -> Slots.delAttr(ODD, "step"));
        refuses(
                readonly,
                () -> Slots.call(Slots.getAttr(entry("start"), "__set__"), new PyObject[] {ODD, ZERO}, null));
        refuses(
                readonly,
                () -> Slots.call(Slots.getAttr(entry("step"), "__delete__"), new PyObject[] {ODD}, null));
        refuses(readonly, () -> Slots.setAttr(ODD, "stop", NONE));
        assertThat(Slots.getAttr(ODD, "start")).isEqualTo(ONE);

        refuses(
                "TypeError: cannot set 'start' attribute of immutable type 'range'",
                () -> Slots.setAttr(PyType.RANGE, "start", FOUR));
        refuses(
                "TypeError: cannot set 'step' attribute of immutable type 'range'",
                () -> Slots.delAttr(PyType.RANGE, "step"));

        refuses(
                "TypeError: descriptor 'start' for 'range' objects doesn't apply to a 'int' object",
                () -> getRow("start", THREE));
        refuses(
                "TypeError: descriptor 'step' for 'range' objects doesn't apply to a 'str' object",
                () -> getRow("step", TEXT));
        refuses("TypeError: __get__ expected at least 1 argument, got 0", () -> getRow("start"));
        refuses("TypeError: __get__ expected at most 2 arguments, got 3", () -> getRow("start", ODD, ONE, TWO));
        assertThat(getRow("start", NONE, PyType.RANGE)).isSameAs(entry("start"));
        assertThat(getRow("step", NONE, PyType.INT)).isSameAs(entry("step"));
        assertThat(getRow("stop", ODD)).isEqualTo(NINE);
        assertThat(getRow("stop", ODD, PyType.RANGE)).isEqualTo(NINE);
    }

    /**
     * The class gates, then the constructor counts, then the three bounds move.
     *
     * <p>{@code range_new} is the legacy shape again: {@code range()} is refused in the C function's own words —
     * {@code range expected at least 1 argument, got 0} — whether it was reached as the call or as {@code
     * range.__new__(range)}, and both the count and the keyword refusals name {@code range} rather than the
     * subtype handed over. The three bounds go through {@code PyNumber_Index}, so a {@code float} is refused for
     * what it is while a value with {@code __index__} and a {@code bool} both move, and a zero step is refused by
     * the length computation with the sentence no other range refusal speaks. The shift that turns one argument
     * into {@code range(0, x)} belongs to the constructor and so runs under {@code __new__} too, and a bound
     * wider than a word is kept whole rather than truncated.
     */
    @Test
    void theConstructorCountsAndGatesBeforeTheThreeBoundsMove() {
        assertThat(ranging(FOUR).repr()).isEqualTo("range(0, 4)");
        assertThat(ranging(ONE, NINE, TWO).repr()).isEqualTo("range(1, 9, 2)");
        assertThat(ranging(ONE, NINE, TWO)).isEqualTo(ODD);
        assertThat(ranging(ONE, NINE, TWO)).isNotSameAs(ODD);
        assertThat(ranging(ONE, NINE, TWO).getType()).isSameAs(PyType.RANGE);
        assertThat(ranging(MINUS_THREE).repr()).isEqualTo("range(0, -3)");
        assertThat(ranging(NINE, ZERO).repr()).isEqualTo("range(9, 0)");
        assertThat(range(-1, -5, -2).repr()).isEqualTo("range(-1, -5, -2)");
        assertThat(WIDE_RANGE.repr()).isEqualTo("range(0, 3, 1180591620717411303424)");
        assertThat(range(two(100), two(101), two(99)).repr())
                .isEqualTo(
                        "range(1267650600228229401496703205376, 2535301200456458802993406410752, "
                                + "633825300114114700748351602688)");
        assertThat(Slots.getAttr(ranging(ONE), "stop")).isEqualTo(ONE);
        assertThat(Slots.call(Slots.getAttr(ODD, "__init__"), new PyObject[] {ONE, TWO}, null)).isSameAs(NONE);

        assertThat(call("__new__", PyType.RANGE, ONE, NINE, TWO)).isEqualTo(ODD);
        assertThat(call("__new__", PyType.RANGE, ONE, NINE, TWO)).isNotSameAs(ODD);
        assertThat(Slots.getAttr(call("__new__", PyType.RANGE, FOUR), "stop")).isEqualTo(FOUR);
        assertThat(call("__new__", PyType.RANGE, WIDE)).isEqualTo(range(ZERO_BI, two(70), ONE_BI));

        refuses("TypeError: range expected at least 1 argument, got 0", () -> ranging());
        refuses("TypeError: range expected at least 1 argument, got 0", () -> call("__new__", PyType.RANGE));
        refuses(
                "TypeError: range expected at most 3 arguments, got 4",
                () -> ranging(ONE, TWO, THREE, FOUR));
        refuses(
                "TypeError: range expected at most 3 arguments, got 4",
                () -> call("__new__", PyType.RANGE, ONE, TWO, THREE, FOUR));
        refuses(
                "TypeError: range() takes no keyword arguments",
                () -> PyType.RANGE.call(new PyObject[] {ONE}, PyTuple.of(PyStr.interned("stop"))));
        refuses(
                "TypeError: range() takes no keyword arguments",
                () -> calledWith("__new__", PyTuple.of(PyStr.interned("stop")), PyType.RANGE, ONE));

        refuses("ValueError: range() arg 3 must not be zero", () -> ranging(ZERO, FIVE, ZERO));
        refuses(
                "ValueError: range() arg 3 must not be zero",
                () -> call("__new__", PyType.RANGE, ZERO, FIVE, ZERO));
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer", () -> ranging(PyFloat.of(1.5)));
        refuses("TypeError: 'str' object cannot be interpreted as an integer", () -> ranging(TEXT));
        refuses("TypeError: 'NoneType' object cannot be interpreted as an integer", () -> ranging(NONE));
        refuses(
                "TypeError: __index__ returned non-int (type float)",
                () -> ranging(indexAnswering(PyFloat.of(0.5))));

        // A `bool` and an `__index__` are both integers to `PyNumber_Index`, so both move.
        assertThat(ranging(PyBool.TRUE).repr()).isEqualTo("range(0, 1)");
        assertThat(ranging(indexAnswering(TWO)).repr()).isEqualTo("range(0, 2)");

        refuses(
                "TypeError: range.__new__(list): list is not a subtype of range",
                () -> call("__new__", PyType.LIST, FOUR));
        refuses(
                "TypeError: range.__new__(str): str is not a subtype of range",
                () -> call("__new__", PyType.STR, FOUR));
        refuses("TypeError: range.__new__(X): X is not a type object (int)", () -> call("__new__", THREE));
        refuses("TypeError: range.__new__(): not enough arguments", () -> call("__new__"));
    }

    /**
     * The length is a machine word and the subscript is an index of any size.
     *
     * <p>{@code range_length} hands its own count to {@code PyLong_AsSsize_t}, so a range of {@code 2 ** 63}
     * values answers {@code len()} with an {@code OverflowError} about a C {@code ssize_t} where {@code 2 ** 63 -
     * 1} still fits, and {@code len(range(2 ** 70, 0, -1))} overflows as happily going backwards. {@code
     * range_subscript} reads its index with {@code PyNumber_Index} and compares it in arbitrary precision, so
     * {@code range(10)[2 ** 70]} is only out of range and {@code range(0, 2 ** 100)[2 ** 99]} is the middle value
     * of a range no word could count. The two refusals are different classes for the same reason: one is a count
     * that cannot be made, the other a position that is not there. The thirteen wrappers still run {@code
     * wrapperdescr_call}'s three gates in the order they do for a slice.
     */
    @Test
    void theLengthIsAWordAndASubscriptIsNot() {
        assertThat(EMPTY.length()).isEqualTo(ZERO);
        assertThat(ranging(TEN).length()).isEqualTo(PyInt.of(10));
        assertThat(ODD.length()).isEqualTo(PyInt.of(4));
        assertThat(range(0, 9, 2).length()).isEqualTo(PyInt.of(5));
        assertThat(SINGLE.length()).isEqualTo(ONE);
        assertThat(range(ZERO_BI, two(63).subtract(ONE_BI), ONE_BI).length())
                .isEqualTo(PyInt.of(two(63).subtract(ONE_BI)));
        assertThat(range(ZERO_BI, two(63), TWO_BI).length()).isEqualTo(PyInt.of(two(62)));
        assertThat(range(ZERO_BI, two(63).add(BigInteger.valueOf(4)), TWO_BI).length())
                .isEqualTo(PyInt.of(new BigInteger("4611686018427387906")));

        refuses(
                "OverflowError: Python int too large to convert to C ssize_t",
                () -> range(ZERO_BI, two(63), ONE_BI).length());
        refuses("OverflowError: Python int too large to convert to C ssize_t", () -> ranging(WIDE).length());
        refuses(
                "OverflowError: Python int too large to convert to C ssize_t",
                () -> range(two(70), ZERO_BI, ONE_BI.negate()).length());
        refuses(
                "OverflowError: Python int too large to convert to C ssize_t", () -> UNWALKABLE.length());

        // The subscript is the wide half: refused for not being there, never for not fitting.
        assertThat(HUGE_STOP.getItem(PyInt.of(two(99)))).isEqualTo(PyInt.of(two(99)));
        assertThat(
                        range(ZERO_BI, two(63).subtract(ONE_BI), ONE_BI)
                                .getItem(PyInt.of(two(63).subtract(TWO_BI))))
                .isEqualTo(PyInt.of(two(63).subtract(TWO_BI)));
        refuses("IndexError: range object index out of range", () -> ranging(TEN).getItem(PyInt.of(two(70))));
        refuses(
                "IndexError: range object index out of range",
                () -> ranging(TEN).getItem(PyInt.of(two(70).negate())));
        refuses("IndexError: range object index out of range", () -> ODD.getItem(NINE));
        refuses("IndexError: range object index out of range", () -> ODD.getItem(PyInt.of(-5)));
        refuses("IndexError: range object index out of range", () -> EMPTY.getItem(ZERO));
        assertThat(SINGLE.getItem(ZERO)).isEqualTo(ZERO);
        assertThat(ODD.getItem(MINUS_ONE)).isEqualTo(SEVEN);

        // The index protocol is what a key goes through, and the refusal quotes the key's own type.
        assertThat(ODD.getItem(indexAnswering(TWO))).isEqualTo(FIVE);
        refuses(
                "TypeError: range indices must be integers or slices, not float",
                () -> ranging(TEN).getItem(PyFloat.of(1.0)));
        refuses(
                "TypeError: range indices must be integers or slices, not str",
                () -> ranging(TEN).getItem(TEXT));
        refuses(
                "TypeError: range indices must be integers or slices, not NoneType",
                () -> ranging(TEN).getItem(NONE));
        refuses(
                "TypeError: range indices must be integers or slices, not Nothing",
                () -> ranging(TEN).getItem(nothing()));

        assertThat(EMPTY.truth()).isSameAs(PyBool.FALSE);
        assertThat(SINGLE.truth()).isSameAs(PyBool.TRUE);
        assertThat(ranging(TEN).truth()).isSameAs(PyBool.TRUE);
        assertThat(range(0, -1, 1).truth()).isSameAs(PyBool.FALSE);
        assertThat(UNWALKABLE.truth()).isSameAs(PyBool.TRUE);
        assertThat(walked(ODD)).isEqualTo(List.of("1", "3", "5", "7"));
        assertThat(walked(range(5, 0, -2))).isEqualTo(List.of("5", "3", "1"));

        // `wrapperdescr_call`'s three gates, on the rows whose answers differ only in width.
        refuses(
                "TypeError: descriptor '__len__' requires a 'range' object but received a 'int'",
                () -> call("__len__", THREE));
        refuses(
                "TypeError: descriptor '__len__' of 'range' object needs an argument", () -> call("__len__"));
        refuses("TypeError: expected 0 arguments, got 1", () -> call("__len__", ODD, ONE));
        refuses(
                "TypeError: wrapper __len__() takes no keyword arguments",
                () -> calledWith("__len__", PyTuple.of(PyStr.interned("x")), ODD, ONE));
        refuses(
                "TypeError: descriptor '__getitem__' requires a 'range' object but received a 'int'",
                () -> call("__getitem__", THREE, ZERO));
        refuses("TypeError: expected 1 argument, got 0", () -> call("__getitem__", ODD));
        refuses("TypeError: expected 1 argument, got 2", () -> call("__getitem__", ODD, ONE, TWO));
        refuses(
                "TypeError: wrapper __getitem__() takes no keyword arguments",
                () -> calledWith("__getitem__", PyTuple.of(PyStr.interned("x")), ODD, ONE, TWO));
        refuses(
                "TypeError: descriptor '__bool__' requires a 'range' object but received a 'int'",
                () -> call("__bool__", THREE));
        refuses(
                "TypeError: descriptor '__iter__' requires a 'range' object but received a 'int'",
                () -> call("__iter__", THREE));
        refuses(
                "TypeError: descriptor '__contains__' requires a 'range' object but received a 'int'",
                () -> call("__contains__", THREE, ZERO));
        assertThat(call("__len__", ODD)).isEqualTo(PyInt.of(4));
        assertThat(call("__getitem__", ODD, TWO)).isEqualTo(FIVE);
        assertThat(call("__contains__", ODD, THREE)).isSameAs(PyBool.TRUE);
        assertThat(call("__bool__", ODD)).isSameAs(PyBool.TRUE);
    }

    /**
     * A slice of a range is folded in arbitrary precision and answers another range.
     *
     * <p>{@code compute_slice} is {@code _PySlice_GetLongIndices} over the length the range computed for itself,
     * then {@code compute_item} on the two bounds and the product of the two steps — so {@code range(0, 20,
     * 3)[::2]} runs at six and {@code range(0, 2 ** 100, 2 ** 90)[::2]} runs at {@code 2 ** 91}, while {@code
     * range(10)[2 ** 70:]} is the empty {@code range(10, 10)} rather than a refusal. A backwards slice keeps the
     * bound that means "run to and including index 0", which is what makes {@code range(10)[::-1]} end at {@code
     * 0} and {@code range(0, 20, 3)[::-2]} end at {@code -3}. The answer is a range of the same kind the slice was
     * read from, since {@code make_range_object} is handed {@code Py_TYPE(r)} — and slicing a sliced range folds
     * again, at the product of the three steps.
     */
    @Test
    void aSliceOfARangeFoldsInArbitraryPrecisionAndAnswersARange() {
        assertThat(sliced(ranging(TEN), TWO, FIVE).repr()).isEqualTo("range(2, 5)");
        assertThat(sliced(ranging(TEN), null, null, THREE).repr()).isEqualTo("range(0, 10, 3)");
        assertThat(sliced(ranging(TEN), null, null, MINUS_ONE).repr()).isEqualTo("range(9, -1, -1)");
        assertThat(sliced(ranging(TEN), FIVE, FIVE).repr()).isEqualTo("range(5, 5)");
        assertThat(sliced(ranging(TEN), FIVE, TWO, MINUS_ONE).repr()).isEqualTo("range(5, 2, -1)");
        assertThat(sliced(ranging(TEN), THREE, HUNDRED).repr()).isEqualTo("range(3, 10)");
        assertThat(sliced(ranging(TEN), MINUS_THREE, null).repr()).isEqualTo("range(7, 10)");
        assertThat(sliced(ranging(TEN), ONE, null, THREE).repr()).isEqualTo("range(1, 10, 3)");
        assertThat(sliced(ranging(TEN), THREE, FOUR).repr()).isEqualTo("range(3, 4)");
        assertThat(sliced(STEPPED, null, null, TWO).repr()).isEqualTo("range(0, 21, 6)");
        assertThat(sliced(STEPPED, null, null, MINUS_TWO).repr()).isEqualTo("range(18, -3, -6)");
        assertThat(sliced(STEPPED, ONE, null).repr()).isEqualTo("range(3, 21, 3)");
        assertThat(sliced(BACKWARD, null, null, TWO).repr()).isEqualTo("range(0, 0, -6)");
        assertThat(walked(ranging(TEN).getItem(sliceOf(TWO, EIGHT, TWO)))).isEqualTo(List.of("2", "4", "6"));

        // Nothing here is pressed down to a word: the bounds clip and the step is kept as it was written.
        assertThat(sliced(ranging(TEN), null, null, TWO).repr()).isEqualTo("range(0, 10, 2)");
        assertThat(sliced(ranging(TEN), WIDE, null).repr()).isEqualTo("range(10, 10)");
        assertThat(sliced(ranging(TEN), null, WIDE).repr()).isEqualTo("range(0, 10)");
        assertThat(sliced(ranging(TEN), WIDE_BACK, null).repr()).isEqualTo("range(0, 10)");
        assertThat(sliced(ranging(TEN), null, null, WIDE).repr())
                .isEqualTo("range(0, 10, 1180591620717411303424)");
        assertThat(sliced(ranging(TEN), null, null, PyInt.of(two(63))))
                .isEqualTo(range(ZERO_BI, BigInteger.TEN, two(63)));
        assertThat(sliced(ranging(TEN), WIDE, PyInt.of(two(80))).repr()).isEqualTo("range(10, 10)");
        assertThat(HUGE_RANGE.getItem(sliceOf(null, null, TWO)).repr())
                .isEqualTo("range(0, 1267650600228229401496703205376, 2475880078570760549798248448)");

        assertThat(ODD.getItem(sliceOf(TWO, FIVE, null)).getType()).isSameAs(PyType.RANGE);
        assertThat(ranging(TEN).getItem(sliceOf(ONE, NINE, TWO))).isEqualTo(ODD);
        assertThat(((PyRange) sliced(ranging(TEN), TWO, FIVE)).step()).isSameAs(PyInt.ONE);
        assertThat(sliced(sliced(ranging(TEN), ONE, NINE, TWO), null, null, TWO).repr())
                .isEqualTo("range(1, 9, 4)");

        refuses(
                "ValueError: slice step cannot be zero",
                () -> ranging(TEN).getItem(sliceOf(null, null, ZERO)));
        refuses(
                "TypeError: slice indices must be integers or None or have an __index__ method",
                () -> ranging(TEN).getItem(sliceOf(PyFloat.of(1.5), null, null)));
        refuses(
                "TypeError: slice indices must be integers or None or have an __index__ method",
                () -> ranging(TEN).getItem(sliceOf(TEXT, null, null)));

        // An empty slice of a range is empty, but it is not equal to a range of another length.
        assertThat(sliced(ranging(TEN), FIVE, FIVE)).isEqualTo(range(5, 5, 1));
        assertThat(sliced(ranging(TEN), FIVE, FIVE)).isNotEqualTo(ranging(THREE));
        assertThat(((PyRange) sliced(ranging(TEN), FIVE, FIVE)).length()).isEqualTo(ZERO);
    }

    /**
     * An integer is asked of the arithmetic and anything else is walked for with {@code ==}.
     *
     * <p>{@code range_contains}, {@code range_count} and {@code range_index} all split the same way: an exact
     * {@code int} or {@code bool} is answered between the bounds and a whole number of steps from {@code start},
     * so {@code 2 ** 199 in range(0, 2 ** 200, 2)} is a modulus rather than a walk over {@code 2 ** 199} values,
     * and {@code range(0, 2 ** 70, 2).index(2 ** 68)} answers {@code 2 ** 67} — a number no walk could reach.
     * Anything else, including a {@code float}, a {@code complex} and a value with {@code __index__}, goes to
     * {@code _PySequence_IterSearch}, which is why {@code 3.0} is in {@code range(1, 9, 2)} and {@code 1.5} is
     * not, why {@code 3 + 0j} is found, and why a value whose own {@code __eq__} accepts anything is found in all
     * four odd values even though the integers answer it with a decline. The two refusals name their own code:
     * the arithmetic one speaks {@code range.index(x)}, the search {@code sequence.index(x)} — so {@code
     * range(0).index("x")} names no range at all.
     */
    @Test
    void anIntegerIsAskedOfTheArithmeticAndAnythingElseIsWalkedFor() {
        assertThat(ODD.contains(THREE)).isSameAs(PyBool.TRUE);
        assertThat(ODD.contains(FIVE)).isSameAs(PyBool.TRUE);
        assertThat(ODD.contains(SEVEN)).isSameAs(PyBool.TRUE);
        assertThat(ODD.contains(ONE)).isSameAs(PyBool.TRUE);
        assertThat(ODD.contains(ZERO)).isSameAs(PyBool.FALSE);
        assertThat(ODD.contains(NINE)).isSameAs(PyBool.FALSE);
        assertThat(ODD.contains(PyBool.TRUE)).isSameAs(PyBool.TRUE);
        assertThat(ODD.contains(WIDE)).isSameAs(PyBool.FALSE);

        // The arithmetic answers a bound this wide without a single step of the walk.
        assertThat(MANY_WIDE.contains(WIDE)).isSameAs(PyBool.TRUE);
        assertThat(MANY_WIDE.contains(PyInt.of(two(69)))).isSameAs(PyBool.FALSE);
        assertThat(UNWALKABLE.contains(PyInt.of(two(199)))).isSameAs(PyBool.TRUE);
        assertThat(UNWALKABLE.contains(PyInt.of(two(199).add(ONE_BI)))).isSameAs(PyBool.FALSE);

        // Everything that is not an exact integer is walked for, element by element, with `==`.
        assertThat(ODD.contains(PyFloat.of(3.0))).isSameAs(PyBool.TRUE);
        assertThat(ODD.contains(PyFloat.of(1.5))).isSameAs(PyBool.FALSE);
        assertThat(ODD.contains(PyComplex.of(3, 0))).isSameAs(PyBool.TRUE);
        assertThat(ODD.contains(TEXT)).isSameAs(PyBool.FALSE);
        assertThat(ODD.contains(PyTuple.of(THREE))).isSameAs(PyBool.FALSE);
        assertThat(ODD.contains(indexAnswering(TWO))).isSameAs(PyBool.FALSE);
        assertThat(ODD.contains(equalsEverything())).isSameAs(PyBool.TRUE);
        assertThat(EMPTY.contains(equalsEverything())).isSameAs(PyBool.FALSE);

        assertThat(call("count", ODD, THREE)).isEqualTo(ONE);
        assertThat(call("count", ODD, PyFloat.of(3.0))).isEqualTo(ONE);
        assertThat(call("count", ODD, PyBool.TRUE)).isEqualTo(ONE);
        assertThat(call("count", ODD, TEXT)).isEqualTo(ZERO);
        assertThat(call("count", ODD, PyFloat.of(1.5))).isEqualTo(ZERO);
        assertThat(call("count", ODD, equalsEverything())).isEqualTo(PyInt.of(4));
        assertThat(call("count", ODD, WIDE)).isEqualTo(ZERO);
        assertThat(call("count", EMPTY, ZERO)).isEqualTo(ZERO);
        assertThat(call("count", range(ZERO_BI, two(70), TWO_BI), PyInt.of(two(68)))).isEqualTo(ONE);

        assertThat(call("index", ODD, FIVE)).isEqualTo(TWO);
        assertThat(call("index", ODD, ONE)).isEqualTo(ZERO);
        assertThat(call("index", ODD, PyBool.TRUE)).isEqualTo(ZERO);
        assertThat(call("index", ODD, PyFloat.of(3.0))).isEqualTo(ONE);
        assertThat(call("index", SINGLE, ZERO)).isEqualTo(ZERO);
        assertThat(call("index", range(10, 0, -3), FOUR)).isEqualTo(PyInt.of(2));
        assertThat(call("index", ODD, equalsEverything())).isEqualTo(ZERO);
        assertThat(call("index", range(ZERO_BI, two(70), TWO_BI), PyInt.of(two(68))))
                .isEqualTo(PyInt.of(new BigInteger("147573952589676412928")));
        assertThat(call("index", range(ZERO_BI, two(100), two(99)), PyInt.of(two(99)))).isEqualTo(ONE);

        // The two refusals, named by the half of the code that reaches them.
        refuses("ValueError: range.index(x): x not in range", () -> call("index", ODD, NINE));
        refuses("ValueError: range.index(x): x not in range", () -> call("index", ODD, WIDE));
        refuses("ValueError: range.index(x): x not in range", () -> call("index", EMPTY, ZERO));
        refuses(
                "ValueError: sequence.index(x): x not in sequence", () -> call("index", ODD, PyFloat.of(2.0)));
        refuses("ValueError: sequence.index(x): x not in sequence", () -> call("index", ODD, TEXT));
        refuses(
                "ValueError: sequence.index(x): x not in sequence",
                () -> call("index", EMPTY, equalsEverything()));

        // `METH_O` gates: the qualified name, the unbound words, and the descriptor's own receiver check.
        refuses(
                "TypeError: range.count() takes exactly one argument (0 given)",
                () -> Slots.call(Slots.getAttr(ODD, "count"), new PyObject[0], null));
        refuses(
                "TypeError: range.count() takes exactly one argument (2 given)",
                () -> Slots.call(Slots.getAttr(ODD, "count"), new PyObject[] {ONE, TWO}, null));
        refuses(
                "TypeError: range.count() takes no keyword arguments",
                () -> calledWith("count", PyTuple.of(PyStr.interned("value")), ODD, ONE));
        refuses(
                "TypeError: range.index() takes no keyword arguments",
                () -> calledWith("index", PyTuple.of(PyStr.interned("value")), ODD, ONE));
        refuses("TypeError: unbound method range.count() needs an argument", () -> call("count"));
        refuses("TypeError: unbound method range.index() needs an argument", () -> call("index"));
        refuses(
                "TypeError: descriptor 'count' for 'range' objects doesn't apply to a 'list' object",
                () -> call("count", PyList.of(ONE), TWO));
        refuses(
                "TypeError: descriptor 'index' for 'range' objects doesn't apply to a 'list' object",
                () -> call("index", PyList.of(ONE), TWO));

        PyObject bound = Slots.getAttr(ODD, "count");
        assertThat(bound.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(ODD);
        assertThat(Slots.getAttr(bound, "__name__")).isEqualTo(PyStr.of("count"));
        assertThat(Slots.getAttr(bound, "__qualname__")).isEqualTo(PyStr.of("range.count"));
        assertThat(Slots.getAttr(bound, "__doc__")).isEqualTo(Slots.getAttr(entry("count"), "__doc__"));
        assertThat(Slots.getAttr(bound, "__text_signature__")).isEqualTo(PyStr.of("($self, object, /)"));
        assertThat(Slots.getAttr(ODD, "index")).isNotSameAs(entry("index"));
        assertThat(Members.find(ODD, "count")).isNotSameAs(Slots.DECLINED);
        assertThat(Members.find(ODD, "junk")).isSameAs(Slots.DECLINED);
    }

    /**
     * The two equalities answer, the four orderings decline, and the hash is the tuple CPython builds.
     *
     * <p>{@code PyRange_Check} is exact, so a value that is not a range is declined by both sides and {@code
     * range(3) == 3} falls back on identity; the four orderings decline even between two ranges, which is what
     * makes {@code range(3) < range(4)} the {@code TypeError} naming both types. {@code range_equals} is the
     * short-circuiting one: identity, length, empty, start, one value, and only then the step — so every empty
     * range equals every other and {@code range(1) == range(0, 1, 2 ** 70)} is true while {@code range(1) ==
     * range(1, 2, 5)} is not. {@code range_hash} hashes the three lanes {@code (len, start, step)} with a bound
     * left out where the range cannot reach it, so a range of one value shares a hash with another of any step
     * exactly as it shares that answer, and a range of more than one hashes bounds that are integers and so
     * comes out the same number as the reference.
     */
    @Test
    void theTwoEqualitiesAnswerAndTheHashIsTheTupleTheRangeBuilds() {
        assertThat(PyRange.compare(CompareOp.EQ, ODD, ranging(ONE, NINE, TWO))).isSameAs(PyBool.TRUE);
        assertThat(PyRange.compare(CompareOp.NE, ODD, ranging(ONE, NINE, TWO))).isSameAs(PyBool.FALSE);
        assertThat(PyRange.compare(CompareOp.EQ, ODD, ODD)).isSameAs(PyBool.TRUE);

        // `range_equals`, in the order its five short circuits run.
        assertThat(EMPTY.eq(range(5, 0, 1))).isTrue();
        assertThat(EMPTY.eq(range(3, 3, 7))).isTrue();
        assertThat(SINGLE.eq(range(ZERO_BI, ONE_BI, two(70)))).isTrue();
        assertThat(SINGLE.eq(range(1, 2, 5))).isFalse();
        assertThat(range(0, 6, 2).eq(range(0, 6, 3))).isFalse();
        assertThat(ranging(THREE).eq(ranging(FOUR))).isFalse();
        // The two short circuits at a width no machine word reaches, where only arithmetic can count.
        assertThat(range(ZERO_BI, two(70), two(70)).eq(range(ZERO_BI, two(70), two(70).add(ONE_BI))))
                .isTrue();
        assertThat(range(ZERO_BI, two(70), two(70)).eq(range(ONE_BI, two(70).add(ONE_BI), two(70))))
                .isFalse();
        assertThat(range(ZERO_BI, two(70), ONE_BI).eq(range(ZERO_BI, two(70), TWO_BI))).isFalse();
        assertThat(range(ZERO_BI, two(70), TWO_BI).eq(range(ZERO_BI, two(70), BigInteger.valueOf(4))))
                .isFalse();
        assertThat(range(ZERO_BI, two(70), ONE_BI.negate()).eq(range(5, 0, 1))).isTrue();
        assertThat(ODD.eq(PyTuple.of(ONE, THREE, FIVE, SEVEN))).isFalse();
        assertThat(ODD.eq(PyList.of(ONE, THREE, FIVE, SEVEN))).isFalse();

        assertThat(PyRange.compare(CompareOp.EQ, ranging(THREE), THREE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(PyRange.compare(CompareOp.EQ, THREE, ranging(THREE))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(PyRange.compare(CompareOp.EQ, ranging(THREE), PyList.of(ZERO, ONE, TWO)))
                .isSameAs(PyNotImplemented.INSTANCE);
        for (CompareOp cmp : List.of(CompareOp.LT, CompareOp.LE, CompareOp.GT, CompareOp.GE)) {
            assertThat(PyRange.compare(cmp, ranging(THREE), ranging(FOUR)))
                    .as(cmp.symbol())
                    .isSameAs(PyNotImplemented.INSTANCE);
            assertThat(PyRange.compare(cmp, ODD, THREE)).as(cmp.symbol())
                    .isSameAs(PyNotImplemented.INSTANCE);
        }

        assertThat(Slots.compare(CompareOp.EQ, ranging(THREE), THREE)).isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.NE, ranging(THREE), THREE)).isSameAs(PyBool.TRUE);
        assertThat(Slots.compare(CompareOp.EQ, ranging(THREE), PyList.of(ZERO, ONE, TWO)))
                .isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.EQ, ranging(THREE), PyStr.of("abc"))).isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.EQ, PyTuple.of(ranging(THREE)), PyTuple.of(ranging(THREE))))
                .isSameAs(PyBool.TRUE);
        refuses(
                "TypeError: '<' not supported between instances of 'range' and 'range'",
                () -> Slots.compare(CompareOp.LT, ranging(THREE), ranging(FOUR)));
        refuses(
                "TypeError: '<' not supported between instances of 'range' and 'list'",
                () -> Slots.compare(CompareOp.LT, ranging(THREE), PyList.of(ZERO, ONE, TWO)));
        assertThat(ODD.richCompare(CompareOp.EQ, ranging(ONE, NINE, TWO)))
                .isSameAs(PyRange.compare(CompareOp.EQ, ODD, ranging(ONE, NINE, TWO)));

        // The wrapper answers what the slot answers, and refuses a foreign receiver before it reads either.
        assertThat(call("__eq__", ODD, ranging(ONE, NINE, TWO))).isSameAs(PyBool.TRUE);
        assertThat(call("__eq__", ODD, PyTuple.of(ONE, NINE, TWO))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call("__ne__", ODD, ODD)).isSameAs(PyBool.FALSE);
        refuses(
                "TypeError: descriptor '__eq__' requires a 'range' object but received a 'int'",
                () -> call("__eq__", THREE, ODD));
        refuses("TypeError: expected 1 argument, got 0", () -> call("__eq__", ODD));

        // `range_hash`: three lanes, and a bound left out where the range cannot reach it.
        assertThat(ranging(FIVE).hash()).isEqualTo(PyTuple.of(PyInt.of(5), ZERO, ONE).hash());
        assertThat(ranging(FIVE).hash()).isEqualTo(5795932985296280846L);
        assertThat(range(0, 5, 2).hash()).isEqualTo(-1830836740296686765L);
        assertThat(range(0, 5, 2).hash()).isEqualTo(PyTuple.of(PyInt.of(3), ZERO, TWO).hash());
        assertThat(EMPTY.hash()).isEqualTo(PyTuple.of(ZERO, NONE, NONE).hash());
        assertThat(SINGLE.hash()).isEqualTo(PyTuple.of(ONE, ZERO, NONE).hash());
        assertThat(SINGLE.hash()).isEqualTo(range(0, 1, 5).hash());
        assertThat(SINGLE.eq(range(0, 1, 5))).isTrue();
        assertThat(EMPTY.hash()).isEqualTo(range(5, 0, 1).hash());
        assertThat(ranging(FIVE).hash()).isEqualTo(range(0, 5, 1).hash());
        assertThat(ranging(FIVE).hash()).isNotEqualTo(ranging(PyInt.of(6)).hash());
        // A one-value range keeps the third lane as `None` however wide its step, so its hash is the tuple's.
        assertThat(range(ZERO_BI, BigInteger.TEN, two(63)).hash())
                .isEqualTo(PyTuple.of(ONE, ZERO, NONE).hash());
        assertThat(range(ZERO_BI, two(70), two(69)).hash()).isEqualTo(1704115574185051363L);
        assertThat(range(ZERO_BI, two(100), two(99)).hash())
                .isEqualTo(PyTuple.of(PyInt.of(2), ZERO, PyInt.of(two(99))).hash());
        assertThat(call("__hash__", ODD)).isEqualTo(PyInt.of(ODD.hash()));
        refuses(
                "TypeError: descriptor '__hash__' requires a 'range' object but received a 'int'",
                () -> call("__hash__", THREE));
    }

    /**
     * {@code range_reverse} is arithmetic, and {@code range_reduce} hands back the three parameters it holds.
     *
     * <p>{@code range_reverse} builds {@code range(start + (n - 1) * step, start - step, -step)} and returns
     * <em>that</em> range's iterator, so reversing a range of {@code 2 ** 100} values answers its last value
     * without counting any of them, and an empty range folds to one that is empty the other way. {@code
     * range_reduce} is the fourth row of {@code range_methods} and the only one filed with no sentence: it hands
     * back the class and the three bounds it would be built from again, holding the very objects the three fields
     * hold — which is why {@code range(0, 3, 2 ** 70).__reduce__()} still carries a step no word could.
     */
    @Test
    void reversedIsArithmeticAndReduceHandsBackTheThreeParameters() {
        assertThat(walked(call("__reversed__", ODD))).isEqualTo(List.of("7", "5", "3", "1"));
        assertThat(walked(Slots.getAttr(ODD, "__reversed__").call(new PyObject[0], null)))
                .isEqualTo(List.of("7", "5", "3", "1"));
        assertThat(walked(call("__reversed__", range(3, 8, 1)))).isEqualTo(List.of("7", "6", "5", "4", "3"));
        assertThat(walked(call("__reversed__", EMPTY))).isEqualTo(List.of());
        assertThat(walked(call("__reversed__", range(5, 0, -2)))).isEqualTo(List.of("1", "3", "5"));
        assertThat(walked(call("__reversed__", WIDE_PAIR)))
                .isEqualTo(List.of("590295810358705651712", "0"));

        // The first value of a reversed range no walk could finish, reached as arithmetic.
        PyObject reversedHuge = call("__reversed__", range(ZERO_BI, two(100), ONE_BI));
        assertThat(((PyInt) Slots.iterator(reversedHuge).nextOrNull()).getValue())
                .isEqualTo(two(100).subtract(ONE_BI));

        assertThat(call("__reduce__", ODD)).isEqualTo(PyTuple.of(PyType.RANGE, PyTuple.of(ONE, NINE, TWO)));
        PyObject reduced = call("__reduce__", ODD);
        assertThat(((PyTuple) reduced).size()).isEqualTo(2);
        assertThat(((PyTuple) reduced).at(0)).isSameAs(PyType.RANGE);
        PyObject parameters = ((PyTuple) reduced).at(1);
        assertThat(((PyTuple) parameters).at(0)).isSameAs(Slots.getAttr(ODD, "start"));
        assertThat(((PyTuple) parameters).at(1)).isSameAs(Slots.getAttr(ODD, "stop"));
        assertThat(((PyTuple) parameters).at(2)).isSameAs(Slots.getAttr(ODD, "step"));
        assertThat(((PyTuple) call("__reduce__", EMPTY)).at(1)).isEqualTo(PyTuple.of(ZERO, ZERO, ONE));
        assertThat(((PyTuple) ((PyTuple) call("__reduce__", WIDE_RANGE)).at(1)).at(2))
                .isSameAs(WIDE_RANGE.step());

        refuses(
                "TypeError: unbound method range.__reversed__() needs an argument", () -> call("__reversed__"));
        refuses(
                "TypeError: range.__reversed__() takes no arguments (1 given)",
                () -> call("__reversed__", ODD, ONE));
        refuses(
                "TypeError: range.__reversed__() takes no keyword arguments",
                () -> calledWith("__reversed__", PyTuple.of(PyStr.interned("x")), ODD, ONE));
        refuses(
                "TypeError: descriptor '__reversed__' for 'range' objects doesn't apply to a 'list' object",
                () -> call("__reversed__", PyList.of(ONE)));
        refuses("TypeError: unbound method range.__reduce__() needs an argument", () -> call("__reduce__"));
        refuses(
                "TypeError: range.__reduce__() takes no arguments (1 given)", () -> call("__reduce__", ODD, ONE));
        refuses(
                "TypeError: range.__reduce__() takes no keyword arguments",
                () -> calledWith("__reduce__", PyTuple.of(PyStr.interned("x")), ODD, ONE));
        refuses(
                "TypeError: descriptor '__reduce__' for 'range' objects doesn't apply to a 'list' object",
                () -> call("__reduce__", PyList.of(ONE)));

        // `tp_repr` and `tp_iter` answer through the rows the same way the value does.
        assertThat(call("__repr__", ODD)).isEqualTo(PyStr.of("range(1, 9, 2)"));
        assertThat(ODD.repr()).isEqualTo("range(1, 9, 2)");
        assertThat(Slots.str(ODD)).isEqualTo("range(1, 9, 2)");
        assertThat(Constructors.materialise(ODD.iterator())).hasSize(4);
        assertThat(walked(call("__iter__", ODD))).isEqualTo(List.of("1", "3", "5", "7"));
    }
}
