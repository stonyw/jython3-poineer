package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fifteen names {@code slice} files, measured against CPython 3.14.8.
 *
 * <p>Corpus case 110 reads the whole table from Python, one line per answer. What is pinned here is the half a
 * script cannot point at. First the table as the complete ordered map it is: the {@code tp_new_wrapper} row,
 * then the eight slot rows behind {@code PySlice_Type}'s own {@code tp_repr}, {@code tp_hash} and {@code
 * tp_richcompare}, then the two of {@code slice_methods} (Objects/sliceobject.c:571), the three of {@code
 * slice_members} ({@code :367}) and the type's own sentence — and eleven of those fifteen are names {@code
 * object} files too, which is why {@code len(vars(slice))} is fifteen while {@code dir(slice)} is only
 * twenty-eight. A reordered or duplicated row is only caught by comparing the dictionary, the {@link Members}
 * table and an ordinary read of the same name.
 *
 * <p>Then the two invented argument tables: {@code slice_indices_doc} opens with {@code S.indices(len)}, which
 * is not the name the row is filed under, so {@code find_signature} declines it and {@code
 * signature_from_flags} writes {@code ($self, object, /)} out of the {@code METH_O}. Then the three fields,
 * which are {@code _Py_T_OBJECT} offsets and so answer the very object the slice was built with — {@code
 * slice(True).start} is the {@code True} — where {@code complex}'s two {@code Py_T_DOUBLE} fields answer a
 * fresh number. Then {@link Slices#indices}, which is {@code _PySlice_GetLongIndices}: arbitrary precision,
 * the whole step, and four refusals in an order that decides which of its three sentences a call gets. Then
 * {@link Slices#compare}, whose three stages — decline, identity, three fields as a tuple — leave a mark no
 * {@code repr} shows. And last the constructor's legacy unpack, whose count message names {@code slice}
 * whichever class was handed over.
 */
class SliceTableTest {

    /** The fifteen names {@code slice} files, in the order its dictionary answers them. */
    private static final List<String> ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "indices", "__reduce__", "start", "stop", "step", "__doc__");

    /** The eight rows {@code tp_repr}, {@code tp_hash} and {@code tp_richcompare} file as wrappers. */
    private static final List<String> WRAPPERS = ORDER.subList(1, 9);

    /** The two rows {@code slice_methods} files, in the order it writes them. */
    private static final List<String> METHODS = List.of("indices", "__reduce__");

    /** The three rows {@code slice_members} files, each one {@code _Py_T_OBJECT} marked {@code Py_READONLY}. */
    private static final List<String> FIELDS = List.of("start", "stop", "step");

    /** The eleven rows of the fifteen that {@code object} files as well, so {@code dir()} does not grow. */
    private static final List<String> SHARED =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__reduce__", "__doc__");

    /** The four rows that are this type's own, and the only four a program gains by asking {@code dir()}. */
    private static final List<String> OWN = List.of("indices", "start", "stop", "step");

    /** The sentence {@code slice_indices_doc} carries, kept whole because it opens as neither of its names. */
    private static final String INDICES_DOC =
            "S.indices(len) -> (start, stop, stride)\n"
                    + "\n"
                    + "Assuming a sequence of length len, calculate the start and stop\n"
                    + "indices, and the stride length of the extended slice described by\n"
                    + "S. Out of bounds indices are clipped in a manner consistent with the\n"
                    + "handling of normal slices.";

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject FIVE = PyInt.of(5);
    private static final PyObject SEVEN = PyInt.of(7);
    private static final PyObject TEN = PyInt.of(10);
    private static final PyObject TWENTY = PyInt.of(20);
    private static final PyObject HUNDRED = PyInt.of(100);
    private static final PyObject MINUS_ONE = PyInt.of(-1);
    private static final PyObject MINUS_TEN = PyInt.of(-10);
    private static final PyObject MINUS_THREE = PyInt.of(-3);
    private static final PyObject MINUS_HUNDRED = PyInt.of(-100);
    private static final PyObject WIDE = PyInt.of(BigInteger.valueOf(2).pow(70));
    private static final PyObject HUGE = PyInt.of(BigInteger.valueOf(2).pow(100));
    private static final PyObject HUGE_BACK = PyInt.of(BigInteger.valueOf(2).pow(100).negate());

    /** {@code -(2 ** 70) - 5}, the step that is too wide for a machine word and still comes back whole. */
    private static final PyObject WIDE_BACK =
            PyInt.of(BigInteger.valueOf(2).pow(70).negate().subtract(BigInteger.valueOf(5)));

    private static final PyObject VALUE = PySlice.of(ONE, TWO, THREE);
    private static final PyObject SAME = PySlice.of(ONE, TWO, THREE);
    private static final PyObject TEXT = PyStr.of("two");

    /** {@code slice(1, 2)}, {@code slice(1, 2, 4)}: the pair whose three fields agree until one runs out. */
    private static final PyObject SHORT = PySlice.of(ONE, TWO, NONE);

    private static final PyObject LONG = PySlice.of(ONE, TWO, FOUR);

    /** {@code slice(1, 3, 2)}, which is the greater of the two second fields. */
    private static final PyObject STEPPED = PySlice.of(ONE, THREE, TWO);

    /** {@code slice(1, 'two')}, whose second field cannot be ordered against an {@code int} at all. */
    private static final PyObject TEXTY = PySlice.of(ONE, TEXT, NONE);

    /** The entry {@code slice} files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.SLICE).get(PyStr.interned(name));
    }

    /** {@code slice(start, stop, step)} with {@code null} written for the {@code None} a shape leaves out. */
    private static PyObject sliceOf(PyObject start, PyObject stop, PyObject step) {
        return PySlice.of(start == null ? NONE : start, stop == null ? NONE : stop, step == null ? NONE : step);
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

    /** {@code slice(*args)}, the way the interpreter calls a type. */
    private static PyObject slicing(PyObject... args) {
        return PyType.SLICE.call(args, null);
    }

    /** {@code slice.__dict__[name](*args)}: a filed row, called without an attribute read. */
    private static PyObject call(String name, PyObject... args) {
        return Slots.call(entry(name), args, null);
    }

    /** {@code slice.__dict__[name](*args, **kwargs)}, which is what sends a row to its argument parser. */
    private static PyObject calledWith(String name, PyTuple kwNames, PyObject... args) {
        return Slots.call(entry(name), args, kwNames);
    }

    /** {@code slice.__dict__[field].__get__(*args)}: the descriptor protocol, called as Python calls it. */
    private static PyObject getRow(String field, PyObject... args) {
        return Slots.call(Slots.getAttr(entry(field), "__get__"), args, null);
    }

    /** {@code S.indices(len)} as the three numbers it answers, so a folded bound can be read at any width. */
    private static List<String> folded(PyObject answer) {
        PyTuple parts = (PyTuple) answer;
        List<String> digits = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            digits.add(((PyInt) parts.at(i)).getValue().toString());
        }
        return digits;
    }

    /** The same three numbers as {@link Slices#of} folds them — the machine-word half of the arithmetic. */
    private static List<String> clipped(PyObject slice, int length) {
        Slices.Range range = Slices.of((PySlice) slice, length);
        return List.of(
                String.valueOf(range.start()), String.valueOf(range.stop()), String.valueOf(range.step()));
    }

    /** A value of a class that writes {@code __index__} itself, which is the only way to reach its failures. */
    private static PyObject indexAnswering(PyObject answer) {
        PyDict dict = PyDict.empty();
        dict.put(
                PyStr.interned("__index__"), new PyNativeFunction("__index__", (args, kwNames) -> answer));
        return Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of("AnswersIndex"), PyTuple.of(PyType.OBJECT), dict},
                        null)
                .call(new PyObject[0], null);
    }

    /** A value whose {@code __index__} raises, so a reader's own refusal can be outranked by the body. */
    private static PyObject indexRaising() {
        PyDict dict = PyDict.empty();
        dict.put(
                PyStr.interned("__index__"),
                new PyNativeFunction(
                        "__index__",
                        (args, kwNames) -> {
                            throw Slots.valueError("inside __index__");
                        }));
        return Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of("RaisesIndex"), PyTuple.of(PyType.OBJECT), dict},
                        null)
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
     * The fifteen rows in the order the three C lists wrote them.
     *
     * <p>The dictionary is filled in filing order, so the eight wrappers land between the constructor and the
     * two methods and the three fields land last but one. Eleven of the fifteen are names {@code object} files
     * as well, and the four it does not — {@code indices} and the three bounds — are the whole of what a
     * program gains by asking {@code dir()}. The rows read off the class are the rows themselves: dictionary,
     * {@link Members} table and ordinary answer one object, because none of the kinds this type files binds on
     * the way out. {@code PySlice_Type} writes no number struct and no sequence half, so where {@code complex}
     * leaves eight arithmetic rows out, this one leaves out {@code __bool__} and {@code __len__} as well.
     */
    @Test
    void fifteenRowsFileTheOrderTheThreeCListsWrote() {
        assertThat(filed(PyType.SLICE)).isEqualTo(ORDER);
        assertThat(ORDER).hasSize(15);
        assertThat(TypeDict.declared(PyType.SLICE).keys()).hasSize(15);
        assertThat(Arrays.asList(Members.tableOf(PyType.SLICE).names()))
                .containsExactlyInAnyOrderElementsOf(METHODS);

        assertThat(ORDER).startsWith("__new__").endsWith("__doc__");
        assertThat(WRAPPERS).hasSize(8).startsWith("__repr__").endsWith("__ge__");
        assertThat(ORDER.indexOf("__hash__") - ORDER.indexOf("__repr__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__ge__") - ORDER.indexOf("__lt__")).isEqualTo(5);
        assertThat(ORDER.indexOf("indices") - ORDER.indexOf("__ge__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__reduce__") - ORDER.indexOf("indices")).isEqualTo(1);
        assertThat(ORDER.subList(11, 14)).isEqualTo(FIELDS);
        assertThat(ORDER).hasSize(SHARED.size() + OWN.size());

        assertThat(entry("__new__").getType().name()).isEqualTo("builtin_function_or_method");
        for (String name : WRAPPERS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("wrapper_descriptor");
        }
        for (String name : METHODS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("method_descriptor");
            assertThat(entry(name)).isSameAs(Slots.getAttr(PyType.SLICE, name));
            assertThat(entry(name)).isSameAs(Members.tableOf(PyType.SLICE).describe(name));
        }
        for (String name : FIELDS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("member_descriptor");
            assertThat(entry(name)).isSameAs(Slots.getAttr(PyType.SLICE, name));
        }
        assertThat(entry("__doc__").getType()).isSameAs(PyType.STR);

        // Neither the constructor row nor a slot row binds, so a read off the class answers the entry itself.
        assertThat(Slots.getAttr(PyType.SLICE, "__new__")).isSameAs(entry("__new__"));
        assertThat(Slots.getAttr(PyType.SLICE, "__eq__")).isSameAs(entry("__eq__"));

        for (String name : SHARED) {
            assertThat(TypeDict.declared(PyType.OBJECT).get(PyStr.interned(name))).as(name).isNotNull();
        }
        for (String name : OWN) {
            assertThat(TypeDict.declared(PyType.OBJECT).get(PyStr.interned(name))).as(name).isNull();
        }

        // Absent rather than filed and declining: this type writes no truth, length or conversion slot.
        for (String name : List.of("__bool__", "__len__", "__str__", "__init__", "__format__")) {
            assertThat(TypeDict.declared(PyType.SLICE).get(PyStr.interned(name))).as(name).isNull();
        }
        assertThat(ORDER).doesNotContain("__getnewargs__", "__reduce_ex__", "__subclasshook__", "__int__");
    }

    /**
     * The two sentences {@code slice_methods} carries, and the tables its flags invent for them.
     *
     * <p>Neither row's text opens with the name it is filed under — {@code S.indices(len)} for one, and the
     * other is filed with no table at all — so {@code signature_from_flags} writes both out of the {@code
     * METH_O} and the {@code METH_NOARGS}: {@code ($self, object, /)}, naming the one argument {@code object}
     * because a flag has no name to offer, and {@code ($self, /)}. The three fields are the only rows here
     * with no argument table whatever, since a {@code member_descriptor} keeps a sentence, a name and an
     * {@code __objclass__} and answers nothing else.
     */
    @Test
    void theTwoMethodRowsSpeakTheTablesTheirFlagsInvent() {
        assertThat(Slots.getAttr(entry("indices"), "__doc__")).isEqualTo(PyStr.of(INDICES_DOC));
        assertThat(Slots.getAttr(entry("__reduce__"), "__doc__"))
                .isEqualTo(PyStr.of("Return state information for pickling."));
        assertThat(Slots.getAttr(entry("__new__"), "__doc__"))
                .isEqualTo(
                        PyStr.of("Create and return a new object.  See help(type) for accurate signature."));

        assertThat(Slots.getAttr(entry("indices"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, object, /)"));
        assertThat(Slots.getAttr(entry("__reduce__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(entry("__new__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($type, *args, **kwargs)"));
        assertThat(Slots.getAttr(entry("__repr__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(entry("__hash__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /)"));
        for (String name : List.of("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")) {
            assertThat(Slots.getAttr(entry(name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, value, /)"));
        }
        for (String name : FIELDS) {
            refuses(
                    "AttributeError: 'member_descriptor' object has no attribute '__text_signature__'",
                    () -> Slots.getAttr(entry(name), "__text_signature__"));
        }

        assertThat(Slots.getAttr(entry("__repr__"), "__doc__")).isEqualTo(PyStr.of("Return repr(self)."));
        assertThat(Slots.getAttr(entry("__hash__"), "__doc__")).isEqualTo(PyStr.of("Return hash(self)."));
        assertThat(Slots.getAttr(entry("__lt__"), "__doc__")).isEqualTo(PyStr.of("Return self<value."));
        assertThat(Slots.getAttr(entry("__le__"), "__doc__")).isEqualTo(PyStr.of("Return self<=value."));
        assertThat(Slots.getAttr(entry("__eq__"), "__doc__")).isEqualTo(PyStr.of("Return self==value."));
        assertThat(Slots.getAttr(entry("__ne__"), "__doc__")).isEqualTo(PyStr.of("Return self!=value."));
        assertThat(Slots.getAttr(entry("__gt__"), "__doc__")).isEqualTo(PyStr.of("Return self>value."));
        assertThat(Slots.getAttr(entry("__ge__"), "__doc__")).isEqualTo(PyStr.of("Return self>=value."));

        for (String name : ORDER) {
            if (name.equals("__doc__")) {
                continue;
            }
            assertThat(Slots.getAttr(entry(name), "__qualname__"))
                    .as(name)
                    .isEqualTo(PyStr.of("slice." + name));
        }
        for (String name : FIELDS) {
            assertThat(Slots.getAttr(entry(name), "__objclass__")).isSameAs(PyType.SLICE);
            assertThat(Slots.getAttr(entry(name), "__name__")).isEqualTo(PyStr.of(name));
            // A `PyMemberDef` written without a docstring, which is what makes the row answer `None`.
            assertThat(Slots.getAttr(entry(name), "__doc__")).isSameAs(NONE);
            assertThat(entry(name).repr()).isEqualTo("<member '" + name + "' of 'slice' objects>");
        }
        assertThat(entry("indices").repr()).isEqualTo("<method 'indices' of 'slice' objects>");
        assertThat(entry("__reduce__").repr()).isEqualTo("<method '__reduce__' of 'slice' objects>");
        assertThat(entry("__eq__").repr()).isEqualTo("<slot wrapper '__eq__' of 'slice' objects>");
    }

    /**
     * The type's sentence is {@code PySlice_Type.tp_doc} whole, because it holds no table to cut out.
     *
     * <p>Where {@code complex} files clinic's own text and so loses its first lines into {@code
     * __text_signature__}, {@code slice}'s was written by hand: it opens with the two shapes the constructor
     * takes, carries no {@code --} rule, and so leaves {@code cut_out_signature} nothing to do. That is why
     * {@code slice.__text_signature__} is {@code None} rather than a string, and why the row is a plain {@code
     * str} in the dictionary instead of a descriptor. Both reads make the sentence again, so neither answer is
     * the entry, and a value reaches it through the type as anything else does.
     */
    @Test
    void theTypeSentenceIsTpDocWholeBecauseItCarriesNoArgumentTable() {
        PyObject throughClass = Slots.getAttr(PyType.SLICE, "__doc__");
        PyObject throughValue = Slots.getAttr(VALUE, "__doc__");

        assertThat(throughClass).isEqualTo(entry("__doc__"));
        assertThat(throughClass).isNotSameAs(entry("__doc__"));
        assertThat(throughValue).isEqualTo(throughClass);
        assertThat(throughValue).isNotSameAs(throughClass);
        assertThat(throughClass.getType()).isSameAs(PyType.STR);
        assertThat(Slots.getAttr(PyType.SLICE, "__text_signature__")).isEqualTo(NONE);

        List<String> lines = Arrays.asList(((PyStr) throughClass).getValue().split("\n"));
        assertThat(lines).hasSize(6);
        assertThat(lines.get(0)).isEqualTo("slice(stop)");
        assertThat(lines.get(1)).isEqualTo("slice(start, stop[, step])");
        assertThat(lines.get(3)).isEqualTo("Create a slice object.");
        assertThat(lines.get(5)).isEqualTo("This is used for extended slicing (e.g. a[0:10:2]).");
        assertThat(((PyStr) throughClass).getValue()).doesNotContain("--");
        assertThat(((PyStr) entry("__doc__")).getValue()).startsWith("slice(stop)");
    }

    /**
     * The three fields are object offsets, so they answer the object stored and refuse every way of writing.
     *
     * <p>{@code slice_members} files all three as {@code _Py_T_OBJECT} with {@code Py_READONLY}, which makes
     * each one a {@code member_descriptor} handing back the pointer the constructor was given: {@code
     * slice(True).start} is the {@code True} rather than a fresh int — where {@code complex}'s two {@code
     * Py_T_DOUBLE} fields answer a number of their own — and a bound left out reads as the one {@code None}.
     * All four refusals come from {@code PyMember_SetOne} before it looks at the value, so {@code del}, {@code
     * __set__} and {@code __delete__} say the same two words an assignment does. The class-level write is
     * refused one level up by {@code type_setattro}, whose message says {@code cannot set} whichever way the
     * write went, and the receiver check quotes {@code tp_name} and the field rather than the slot.
     */
    @Test
    void theThreeFieldsHandBackTheVeryObjectTheSliceWasBuiltWith() {
        assertThat(Slots.getAttr(VALUE, "start")).isSameAs(ONE);
        assertThat(Slots.getAttr(VALUE, "stop")).isSameAs(TWO);
        assertThat(Slots.getAttr(VALUE, "step")).isSameAs(THREE);
        assertThat(Slots.getAttr(PySlice.of(PyBool.TRUE, TEXT, PyBool.TRUE), "start")).isSameAs(PyBool.TRUE);
        assertThat(Slots.getAttr(PySlice.of(PyBool.TRUE, TEXT, PyBool.TRUE), "stop")).isSameAs(TEXT);
        assertThat(Slots.getAttr(slicing(ONE), "stop")).isSameAs(ONE);
        assertThat(Slots.getAttr(slicing(ONE), "start")).isSameAs(NONE);
        assertThat(Slots.getAttr(slicing(NONE), "start"))
                .isSameAs(Slots.getAttr(slicing(NONE, NONE, NONE), "step"));

        for (String name : FIELDS) {
            // A `PyMemberDesc_Type` keeps a `tp_descr_set` even read-only, so the field is a data
            // descriptor — and a slice value has no instance dictionary for it to outrank.
            assertThat(((Descriptors.Protocol) entry(name)).writes()).as(name).isTrue();
        }
        refuses(
                "AttributeError: 'slice' object has no attribute '__dict__'",
                () -> Slots.getAttr(VALUE, "__dict__"));

        String readonly = "AttributeError: readonly attribute";
        refuses(readonly, () -> Slots.setAttr(VALUE, "start", FOUR));
        refuses(readonly, () -> Slots.delAttr(VALUE, "step"));
        refuses(
                readonly,
                () ->
                        Slots.call(
                                Slots.getAttr(entry("start"), "__set__"),
                                new PyObject[] {VALUE, FOUR},
                                null));
        refuses(
                readonly,
                () ->
                        Slots.call(
                                Slots.getAttr(entry("step"), "__delete__"), new PyObject[] {VALUE}, null));
        refuses(readonly, () -> Slots.setAttr(VALUE, "start", NONE));
        assertThat(Slots.getAttr(VALUE, "start")).isSameAs(ONE);

        refuses(
                "TypeError: cannot set 'start' attribute of immutable type 'slice'",
                () -> Slots.setAttr(PyType.SLICE, "start", FOUR));
        refuses(
                "TypeError: cannot set 'step' attribute of immutable type 'slice'",
                () -> Slots.delAttr(PyType.SLICE, "step"));

        refuses(
                "TypeError: descriptor 'start' for 'slice' objects doesn't apply to a 'int' object",
                () -> getRow("start", ONE));
        refuses(
                "TypeError: descriptor 'step' for 'slice' objects doesn't apply to a 'str' object",
                () -> getRow("step", TEXT));
        refuses("TypeError: __get__ expected at least 1 argument, got 0", () -> getRow("start"));
        refuses("TypeError: __get__ expected at most 2 arguments, got 3", () -> getRow("start", VALUE, ONE, TWO));
        assertThat(getRow("start", NONE, PyType.SLICE)).isSameAs(entry("start"));
        assertThat(getRow("step", NONE, PyType.INT)).isSameAs(entry("step"));
        assertThat(getRow("stop", VALUE)).isSameAs(TWO);
        assertThat(getRow("stop", VALUE, PyType.SLICE)).isSameAs(TWO);
    }

    /**
     * The class gates, then {@code PyArg_UnpackTuple} counts, then the one argument moves.
     *
     * <p>{@code slice_new} is the legacy shape: keywords are refused by {@code _PyArg_NoKeywords} and the
     * count by {@code PyArg_UnpackTuple}, both naming {@code slice} rather than the class handed over — which
     * is what makes {@code slice.__new__(slice, 1, 2, 3, 4)} say {@code expected at most 3} in the plain-name
     * words and not {@code slice() takes at most 3 arguments}. The swap that turns one bound into {@code
     * slice(None, x, None)} belongs to the constructor, so it runs under {@code __new__} too, and the two
     * spellings answer equal slices rather than one. {@code tp_init} is {@code object}'s, which answers {@code
     * None} and changes nothing.
     */
    @Test
    void theConstructorCountsInItsOwnWordsAfterTheClassHasGated() {
        assertThat(slicing(ONE).repr()).isEqualTo("slice(None, 1, None)");
        assertThat(slicing(ONE, TWO).repr()).isEqualTo("slice(1, 2, None)");
        assertThat(slicing(ONE, TWO, THREE)).isEqualTo(VALUE);
        assertThat(slicing(ONE, TWO, THREE)).isNotSameAs(VALUE);
        assertThat(slicing(ONE, TWO, THREE).getType()).isSameAs(PyType.SLICE);
        assertThat(Slots.getAttr(slicing(ONE), "stop")).isSameAs(ONE);
        assertThat(Slots.call(Slots.getAttr(VALUE, "__init__"), new PyObject[] {ONE, TWO}, null))
                .isSameAs(NONE);

        assertThat(call("__new__", PyType.SLICE, ONE)).isEqualTo(slicing(ONE));
        assertThat(call("__new__", PyType.SLICE, ONE)).isNotSameAs(slicing(ONE));
        assertThat(call("__new__", PyType.SLICE, ONE, TWO, THREE)).isEqualTo(VALUE);
        assertThat(Slots.getAttr(call("__new__", PyType.SLICE, ONE, TWO, THREE), "start")).isSameAs(ONE);

        refuses("TypeError: slice expected at least 1 argument, got 0", () -> slicing());
        refuses("TypeError: slice expected at least 1 argument, got 0", () -> call("__new__", PyType.SLICE));
        refuses("TypeError: slice expected at most 3 arguments, got 4", () -> slicing(ONE, TWO, THREE, FOUR));
        refuses(
                "TypeError: slice expected at most 3 arguments, got 4",
                () -> call("__new__", PyType.SLICE, ONE, TWO, THREE, FOUR));
        refuses(
                "TypeError: slice() takes no keyword arguments",
                () -> PyType.SLICE.call(new PyObject[] {ONE}, PyTuple.of(PyStr.interned("stop"))));
        refuses(
                "TypeError: slice() takes no keyword arguments",
                () -> calledWith("__new__", PyTuple.of(PyStr.interned("stop")), PyType.SLICE, ONE));

        refuses(
                "TypeError: slice.__new__(int): int is not a subtype of slice",
                () -> call("__new__", PyType.INT));
        refuses(
                "TypeError: slice.__new__(str): str is not a subtype of slice",
                () -> call("__new__", PyType.STR));
        refuses(
                "TypeError: slice.__new__(X): X is not a type object (int)",
                () -> call("__new__", ONE));
        refuses("TypeError: slice.__new__(): not enough arguments", () -> call("__new__"));
    }

    /**
     * {@code S.indices(len)} is {@code _PySlice_GetLongIndices}, the wide half of the slice arithmetic.
     *
     * <p>Three things at the Java end are not visible as a {@code repr}. The fold runs on {@code BigInteger}s
     * and answers {@code PyInt}s of whatever width that takes, so a step of {@code -(2 ** 70) - 5} comes back
     * whole rather than folded into a machine word, and a bound of {@code 2 ** 100} is clipped to the length
     * rather than saturated; {@link Slices#of}, the half a subscript uses, agrees wherever a word can hold it.
     * The length is read by {@link Numbers#asIndex} and the three bounds by {@link Numbers#sliceIndex}, which
     * is why one bad value speaks the index protocol's sentence and the other the slice path's. And the
     * refusals are in the source's order, which decides who complains first: the length before the step, a
     * zero step before either bound, and {@code start} before {@code stop}.
     */
    @Test
    void indicesFoldsOnBigIntegerAndRefusesInTheOrderTheSourceReads() {
        assertThat(folded(call("indices", sliceOf(ONE, FIVE, TWO), TEN))).isEqualTo(List.of("1", "5", "2"));
        assertThat(folded(call("indices", VALUE, FOUR))).isEqualTo(List.of("1", "2", "3"));
        assertThat(folded(call("indices", sliceOf(ONE, FIVE, TWO), PyInt.ZERO)))
                .isEqualTo(List.of("0", "0", "2"));
        assertThat(folded(call("indices", sliceOf(ONE, FIVE, TWO), ONE))).isEqualTo(List.of("1", "1", "2"));
        assertThat(folded(call("indices", sliceOf(null, null, MINUS_ONE), THREE)))
                .isEqualTo(List.of("2", "-1", "-1"));
        assertThat(folded(call("indices", sliceOf(TWO, null, MINUS_ONE), THREE)))
                .isEqualTo(List.of("2", "-1", "-1"));
        assertThat(folded(call("indices", sliceOf(MINUS_THREE, null, TWO), TEN)))
                .isEqualTo(List.of("7", "10", "2"));
        assertThat(folded(call("indices", sliceOf(TEN, TWENTY, null), FIVE))).isEqualTo(List.of("5", "5", "1"));
        assertThat(folded(call("indices", sliceOf(MINUS_HUNDRED, HUNDRED, SEVEN), TEN)))
                .isEqualTo(List.of("0", "10", "7"));
        assertThat(folded(call("indices", sliceOf(THREE, THREE, null), PyInt.ZERO)))
                .isEqualTo(List.of("0", "0", "1"));
        assertThat(folded(call("indices", sliceOf(PyInt.ZERO, PyInt.ZERO, THREE), ONE)))
                .isEqualTo(List.of("0", "0", "3"));
        assertThat(folded(call("indices", sliceOf(null, THREE, null), TEN))).isEqualTo(List.of("0", "3", "1"));

        // A bool bound is an index like any other, so the two truths fold to the two numbers they stand for.
        assertThat(folded(call("indices", sliceOf(PyBool.TRUE, PyBool.FALSE, null), THREE)))
                .isEqualTo(List.of("1", "0", "1"));

        // Nothing here is pressed down to a word: the bounds clip, and the step is reported as it was written.
        assertThat(folded(call("indices", sliceOf(WIDE, WIDE, null), TEN))).isEqualTo(List.of("10", "10", "1"));
        assertThat(folded(call("indices", sliceOf(TWO, HUGE, null), FIVE))).isEqualTo(List.of("2", "5", "1"));
        assertThat(folded(call("indices", sliceOf(HUGE_BACK, null, null), FIVE)))
                .isEqualTo(List.of("0", "5", "1"));
        assertThat(folded(call("indices", sliceOf(null, null, WIDE_BACK), TEN)))
                .isEqualTo(List.of("9", "-1", "-1180591620717411303429"));
        assertThat(folded(call("indices", sliceOf(null, null, WIDE), TEN)))
                .isEqualTo(List.of("0", "10", "1180591620717411303424"));

        // The answer is three fresh integers, and none of them the object the slice was built with.
        PyObject answer = call("indices", sliceOf(null, null, WIDE), TEN);
        assertThat(answer.getType()).isSameAs(PyType.TUPLE);
        assertThat(((PyTuple) answer).at(0).getType()).isSameAs(PyType.INT);
        assertThat(((PyTuple) answer).at(2)).isEqualTo(WIDE);
        assertThat(((PyTuple) answer).at(2)).isNotSameAs(WIDE);

        // Where a machine word can hold the shape, the two halves of the arithmetic fold the same three ways.
        assertThat(folded(call("indices", sliceOf(ONE, FIVE, TWO), TEN)))
                .isEqualTo(clipped(sliceOf(ONE, FIVE, TWO), 10));
        assertThat(folded(call("indices", sliceOf(MINUS_HUNDRED, HUNDRED, SEVEN), TEN)))
                .isEqualTo(clipped(sliceOf(MINUS_HUNDRED, HUNDRED, SEVEN), 10));
        assertThat(folded(call("indices", sliceOf(MINUS_TEN, null, MINUS_ONE), FIVE)))
                .isEqualTo(clipped(sliceOf(MINUS_TEN, null, MINUS_ONE), 5));

        // The length is `PyNumber_Index`, so it speaks the index protocol's own two sentences.
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> call("indices", VALUE, PyFloat.of(4.5)));
        refuses("TypeError: 'NoneType' object cannot be interpreted as an integer", () -> call("indices", VALUE, NONE));
        refuses("TypeError: 'str' object cannot be interpreted as an integer", () -> call("indices", VALUE, TEXT));
        refuses(
                "TypeError: __index__ returned non-int (type float)",
                () -> call("indices", VALUE, indexAnswering(PyFloat.of(0.5))));
        refuses(
                "ValueError: inside __index__",
                () -> call("indices", VALUE, indexRaising()));

        // The three bounds are the slice path's, in its sentence, and one of them outranks the next.
        String bounds = "TypeError: slice indices must be integers or None or have an __index__ method";
        refuses(bounds, () -> call("indices", sliceOf(PyFloat.of(1.5), TWO, THREE), FOUR));
        refuses(bounds, () -> call("indices", sliceOf(ONE, PyFloat.of(2.5), THREE), FOUR));
        refuses(bounds, () -> call("indices", sliceOf(ONE, TWO, PyFloat.of(3.5)), FOUR));
        refuses(bounds, () -> call("indices", sliceOf(TEXT, TWO, null), FOUR));
        refuses(bounds, () -> call("indices", sliceOf(ONE, TEXT, null), FOUR));
        refuses(
                "TypeError: __index__ returned non-int (type float)",
                () -> call("indices", sliceOf(indexAnswering(PyFloat.of(0.5)), TWO, THREE), FOUR));

        // The order, which decides which refusal a call that is wrong in three ways gets.
        refuses(
                "ValueError: length should not be negative",
                () -> call("indices", sliceOf(ONE, TWO, PyInt.ZERO), PyInt.of(-1)));
        refuses(
                "ValueError: length should not be negative",
                () -> call("indices", sliceOf(PyFloat.of(1.5), TWO, PyInt.ZERO), PyInt.of(-1)));
        refuses("ValueError: slice step cannot be zero", () -> call("indices", sliceOf(ONE, TWO, PyInt.ZERO), FOUR));
        refuses(
                "ValueError: slice step cannot be zero",
                () -> call("indices", sliceOf(PyFloat.of(1.5), PyFloat.of(2.5), PyInt.ZERO), FOUR));
        refuses(
                bounds,
                () -> call("indices", sliceOf(PyFloat.of(1.5), PyFloat.of(2.5), TWO), FOUR));
    }

    /**
     * The three stages of {@code slice_richcompare}: decline, identity, three fields as a tuple.
     *
     * <p>Both sides have to be slices, and that check runs before a field is read — so a slice is declined
     * against the very tuple its three bounds would make, {@code ==} answers false on the strength of that
     * declination, and {@code <} becomes the {@code TypeError} naming both types. The identity shortcut is the
     * part the source itself questions, in a comment that asks whether it is needed at all, and it is
     * unobservable: it is reached only for a value compared with itself, where the tuple path answers the same
     * six booleans. The third stage is a {@code PyTuple} comparison of the three bounds, and both sides have
     * exactly three of them, so the length rule that tuple carries is never reached — instead the {@code step}
     * is compared like any other field, which is what makes {@code slice(1, 2) < slice(1, 2, 4)} the {@code
     * NoneType} and {@code int} refusal rather than an answer about which slice is shorter.
     */
    @Test
    void theSixOrderingsDeclineBeforeTheyReadTheThreeFields() {
        for (CompareOp cmp : List.of(CompareOp.LE, CompareOp.EQ, CompareOp.GE)) {
            assertThat(Slices.compare(cmp, VALUE, VALUE)).as(cmp.symbol()).isSameAs(PyBool.TRUE);
            assertThat(Slices.compare(cmp, VALUE, SAME)).as(cmp.symbol()).isSameAs(PyBool.TRUE);
        }
        for (CompareOp cmp : List.of(CompareOp.LT, CompareOp.NE, CompareOp.GT)) {
            assertThat(Slices.compare(cmp, VALUE, VALUE)).as(cmp.symbol()).isSameAs(PyBool.FALSE);
            assertThat(Slices.compare(cmp, VALUE, SAME)).as(cmp.symbol()).isSameAs(PyBool.FALSE);
        }

        assertThat(Slices.compare(CompareOp.EQ, VALUE, ONE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slices.compare(CompareOp.LT, TEXT, VALUE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slices.compare(CompareOp.EQ, VALUE, PyTuple.of(ONE, TWO, THREE)))
                .isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slices.compare(CompareOp.LT, VALUE, NONE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call("__eq__", VALUE, ONE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call("__lt__", VALUE, ONE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call("__eq__", VALUE, PyTuple.of(ONE, TWO, THREE))).isSameAs(PyNotImplemented.INSTANCE);

        assertThat(Slots.compare(CompareOp.EQ, VALUE, ONE)).isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.NE, VALUE, ONE)).isSameAs(PyBool.TRUE);
        assertThat(Slots.compare(CompareOp.EQ, VALUE, PyTuple.of(ONE, TWO, THREE))).isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.EQ, VALUE, slicing(ONE, TWO, THREE))).isSameAs(PyBool.TRUE);
        refuses(
                "TypeError: '<' not supported between instances of 'slice' and 'int'",
                () -> Slots.compare(CompareOp.LT, VALUE, ONE));

        // The fields are compared in order, and the step is one of them: both sides have exactly three
        // fields, so the length rule `Sequences.compare` carries can never be reached here — the third
        // pair decides instead, and a `None` beside a number decides it in the refusal's own words.
        assertThat(Slices.compare(CompareOp.LT, VALUE, STEPPED)).isSameAs(PyBool.TRUE);
        assertThat(Slices.compare(CompareOp.GT, VALUE, STEPPED)).isSameAs(PyBool.FALSE);
        assertThat(Slices.compare(CompareOp.EQ, VALUE, PySlice.of(ONE, TWO, PyInt.ZERO)))
                .isSameAs(PyBool.FALSE);
        assertThat(Slices.compare(CompareOp.EQ, SHORT, LONG)).isSameAs(PyBool.FALSE);
        assertThat(Slices.compare(CompareOp.NE, SHORT, LONG)).isSameAs(PyBool.TRUE);
        refuses(
                "TypeError: '<' not supported between instances of 'NoneType' and 'int'",
                () -> Slices.compare(CompareOp.LT, SHORT, LONG));
        refuses(
                "TypeError: '>' not supported between instances of 'int' and 'NoneType'",
                () -> Slots.compare(CompareOp.GT, LONG, SHORT));

        // A field that cannot order decides the call in its own words, naming the pair rather than the slices.
        refuses(
                "TypeError: '<' not supported between instances of 'str' and 'int'",
                () -> Slices.compare(CompareOp.LT, TEXTY, SHORT));
        refuses(
                "TypeError: '<' not supported between instances of 'int' and 'str'",
                () -> call("__lt__", SHORT, TEXTY));
        refuses(
                "TypeError: '<=' not supported between instances of 'str' and 'int'",
                () -> Slots.compare(CompareOp.LE, TEXTY, SHORT));
        assertThat(Slices.compare(CompareOp.EQ, TEXTY, SHORT)).isSameAs(PyBool.FALSE);
        assertThat(call("__eq__", TEXTY, SHORT)).isSameAs(PyBool.FALSE);

        // `tp_richcompare` is what the operator reaches, so the row, the value and `eq` all answer alike.
        assertThat(VALUE.richCompare(CompareOp.EQ, SAME)).isSameAs(Slices.compare(CompareOp.EQ, VALUE, SAME));
        assertThat(VALUE.eq(SAME)).isTrue();
        assertThat(SAME.eq(VALUE)).isTrue();
        assertThat(VALUE.eq(ONE)).isFalse();
        assertThat(VALUE.eq(slicing(ONE, TWO, THREE))).isTrue();

        // No `__bool__` and no `__len__` row, so the truth of a slice is the only answer `object` can give.
        assertThat(Slots.truth(VALUE)).isTrue();
        assertThat(Slots.truth(slicing(NONE))).isTrue();
    }

    /**
     * The two method rows bind the value, the three fields do not, and the gates are the flags' words.
     *
     * <p>{@code method_get} makes a {@code builtin_function_or_method} over the receiver for a row of {@code
     * slice_methods} — slice 54's rule, reached from a table this slice files — while a {@code
     * member_descriptor} has no receiver to bind and answers the object under the offset instead. The {@code
     * METH_O} and {@code METH_NOARGS} refusals are {@link Arguments}' two sentences and both name the
     * qualified {@code slice.indices()}; with no receiver at all the row speaks the unbound words, and with a
     * foreign one the descriptor's, which quote {@code tp_name} and the method rather than the arguments.
     * {@code slice_reduce} files the class and the three bounds it would be built from again, holding the very
     * objects the fields hold.
     */
    @Test
    void theTwoMethodRowsBindTheValueWhileTheThreeFieldsAnswerIt() {
        PyObject bound = Slots.getAttr(VALUE, "indices");
        assertThat(bound.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(VALUE);
        assertThat(Slots.getAttr(bound, "__name__")).isEqualTo(PyStr.of("indices"));
        assertThat(Slots.getAttr(bound, "__qualname__")).isEqualTo(PyStr.of("slice.indices"));
        assertThat(Slots.getAttr(bound, "__doc__")).isEqualTo(Slots.getAttr(entry("indices"), "__doc__"));
        assertThat(Slots.getAttr(bound, "__text_signature__")).isEqualTo(PyStr.of("($self, object, /)"));
        assertThat(folded(Slots.call(bound, new PyObject[] {FOUR}, null))).isEqualTo(List.of("1", "2", "3"));
        assertThat(Slots.getAttr(VALUE, "start")).isSameAs(ONE);
        assertThat(Slots.getAttr(VALUE, "__reduce__").getType()).isSameAs(PyType.BUILTIN_FUNCTION);

        // The rows are filed before any value exists, because `Members` loads the value class for the type.
        assertThat(Members.tableOf(PyType.SLICE).describe("indices")).isNotSameAs(Slots.DECLINED);
        assertThat(Members.find(VALUE, "indices")).isNotSameAs(Slots.DECLINED);
        assertThat(Members.find(VALUE, "junk")).isSameAs(Slots.DECLINED);
        assertThat(Slots.getAttr(VALUE, "indices")).isNotSameAs(entry("indices"));

        refuses(
                "TypeError: slice.indices() takes exactly one argument (0 given)", () -> call("indices", VALUE));
        refuses(
                "TypeError: slice.indices() takes exactly one argument (2 given)",
                () -> call("indices", VALUE, FOUR, ONE));
        refuses(
                "TypeError: slice.indices() takes no keyword arguments",
                () -> calledWith("indices", PyTuple.of(PyStr.interned("length")), VALUE, FOUR));
        refuses("TypeError: unbound method slice.indices() needs an argument", () -> call("indices"));
        refuses(
                "TypeError: unbound method slice.indices() needs an argument",
                () -> calledWith("indices", PyTuple.of(PyStr.interned("self")), FOUR));
        refuses(
                "TypeError: descriptor 'indices' for 'slice' objects doesn't apply to a 'int' object",
                () -> call("indices", ONE, FOUR));

        PyObject reduced = call("__reduce__", VALUE);
        assertThat(reduced).isEqualTo(PyTuple.of(PyType.SLICE, PyTuple.of(ONE, TWO, THREE)));
        assertThat(((PyTuple) reduced).size()).isEqualTo(2);
        assertThat(((PyTuple) reduced).at(0)).isSameAs(PyType.SLICE);
        PyObject bounds = ((PyTuple) reduced).at(1);
        assertThat(((PyTuple) bounds).at(0)).isSameAs(ONE);
        assertThat(((PyTuple) bounds).at(1)).isSameAs(TWO);
        assertThat(((PyTuple) bounds).at(2)).isSameAs(THREE);
        assertThat(((PyTuple) call("__reduce__", slicing(ONE))).at(1)).isEqualTo(PyTuple.of(NONE, ONE, NONE));
        refuses(
                "TypeError: slice.__reduce__() takes no arguments (1 given)", () -> call("__reduce__", VALUE, ONE));
        // A keyword value trails the positionals in the array a call is handed over with, so the two
        // spellings of a name beside a receiver are two different counts: `slice.__reduce__(s, junk=1)` has
        // the receiver and refuses the name, while `slice.__reduce__(junk=1)` arrives with no receiver at
        // all and speaks the unbound words instead.
        refuses(
                "TypeError: slice.__reduce__() takes no keyword arguments",
                () -> calledWith("__reduce__", PyTuple.of(PyStr.interned("junk")), VALUE, ONE));
        refuses(
                "TypeError: unbound method slice.__reduce__() needs an argument",
                () -> calledWith("__reduce__", PyTuple.of(PyStr.interned("junk")), ONE));
        refuses(
                "TypeError: unbound method slice.__reduce__() needs an argument", () -> call("__reduce__"));

        // The eight wrappers run `wrapperdescr_call`'s three gates, in the order the wrapper runs them.
        assertThat(call("__repr__", VALUE)).isEqualTo(PyStr.of("slice(1, 2, 3)"));
        assertThat(call("__hash__", VALUE)).isEqualTo(PyInt.of(VALUE.hash()));
        assertThat(call("__hash__", VALUE)).isEqualTo(call("__hash__", SAME));
        refuses(
                "TypeError: descriptor '__repr__' requires a 'slice' object but received a 'int'",
                () -> call("__repr__", ONE));
        refuses(
                "TypeError: descriptor '__hash__' requires a 'slice' object but received a 'str'",
                () -> call("__hash__", TEXT));
        refuses(
                "TypeError: wrapper __eq__() takes no keyword arguments",
                () -> calledWith("__eq__", PyTuple.of(PyStr.interned("other")), VALUE, SAME));
        refuses("TypeError: expected 1 argument, got 2", () -> call("__eq__", VALUE, SAME, ONE));
        refuses("TypeError: expected 0 arguments, got 1", () -> call("__repr__", VALUE, ONE));
        refuses(
                "TypeError: descriptor '__eq__' of 'slice' object needs an argument", () -> call("__eq__"));
    }
}
