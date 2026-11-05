package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The write half of a {@code PyGetSetDef}, and the two gates that stand before the writer — measured against
 * CPython 3.14.10.
 *
 * <p>Corpus case 122 reads and writes these rows from Python and prints every refusal in the words the
 * reference makes it. What is pinned here is the store those reads pass through: which rows {@code type}'s
 * book files with nothing on its writing side, that such a row is still a <em>data</em> descriptor so a write
 * reaches a refusal rather than a dictionary, and that the receiver is asked before the missing writer is
 * ever noticed.
 *
 * <p>Three of the twelve getset rows of {@code type} are of that kind — {@code __mro__}, {@code __dict__} and
 * {@code __text_signature__} — and the book of a class Python built adds two more of its own. A fourth row,
 * {@code __abstractmethods__}, is the only writer in the book that asks nothing of the type it was handed, so
 * {@code int} takes the filing and its book grows by one row; filed on {@code type} it lands under the very
 * key the row itself was filed at, which is why this test puts that row back.
 *
 * <p>Two paths to one name make two sentences. {@code type_setattro} refuses a non-heap type before it looks
 * the name up anywhere, so {@code int.__mro__ = 1} says {@code cannot set} while the same row reached as a
 * descriptor says {@code is not writable} — and a {@code member_descriptor} with no writer is a third wording
 * again, {@code readonly attribute}, which is why all three sit in one test below.
 *
 * <p>{@code int}'s and {@code type}'s books are shared by every test in this JVM, so each filing here is
 * undone in a {@code finally}: a {@code __abstractmethods__} left in {@code type}'s book would answer for
 * every class in it.
 */
class GetSetWriteHalfTest {

    /** The three rows of {@code type}'s book that CPython files with {@code gd->set == NULL}. */
    private static final List<String> NO_WRITER = List.of("__mro__", "__dict__", "__text_signature__");

    /** The twelve getset rows of {@code type}'s book, in the order the book keeps them. */
    private static final List<String> TYPE_GETSETS =
            List.of(
                    "__name__",
                    "__qualname__",
                    "__bases__",
                    "__mro__",
                    "__module__",
                    "__abstractmethods__",
                    "__dict__",
                    "__doc__",
                    "__text_signature__",
                    "__annotations__",
                    "__annotate__",
                    "__type_params__");

    /** The four rows {@code int} files with no writer, which every {@code bool} reads as its own. */
    private static final List<String> INT_GETSETS = List.of("real", "imag", "numerator", "denominator");

    private static final String ABSTRACT = "__abstractmethods__";

    private static final PyObject THREE = PyInt.of(3);

    private static final PyObject ONE = PyInt.of(1);

    private static final PyObject TEXT = PyStr.of("text");

    /** The dictionary a type declares, which is where {@link Specials} reads before it walks anywhere. */
    private static PyDict book(PyType kind) {
        return kind.namespace();
    }

    /** {@code type.__dict__[name]}: the row the book keeps, or null where it keeps no such name. */
    private static PyObject row(String name) {
        return book(PyType.TYPE).get(PyStr.interned(name));
    }

    /** The getset rows of a book, in the order the book keeps them. */
    private static List<String> getsetRows(PyType kind) {
        PyDict declared = book(kind);
        PyIterator keys = (PyIterator) ((PyMappingProxy) Slots.getAttr(kind, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            PyObject value = declared.get((PyStr) key);
            if (value != null && "getset_descriptor".equals(value.getType().name())) {
                names.add(((PyStr) key).getValue());
            }
        }
        return names;
    }

    /** {@code type.__dict__[name].__set__(obj, value)}: the writing half asked as a row. */
    private static void writeRow(String name, PyObject receiver, PyObject value) {
        Descriptors.set(row(name), receiver, value);
    }

    /** {@code type.__dict__[name].__delete__(obj)}: the same pointer with nothing handed over. */
    private static void deleteRow(String name, PyObject receiver) {
        Descriptors.delete(row(name), receiver);
    }

    /** The same two asks, of a row taken out of the book before anything could be filed over it. */
    private static void writeEntry(PyObject entry, PyObject receiver, PyObject value) {
        Descriptors.set(entry, receiver, value);
    }

    /** {@code type(name, (), {})}: a class Python built, which passes the modifiable gate. */
    private static PyType classOf(String name) {
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(), PyDict.empty()}, null);
    }

    /** {@code AttributeError: attribute 'NAME' of 'type' objects is not writable}. */
    private static String notWritable(String name) {
        return "AttributeError: attribute '" + name + "' of 'type' objects is not writable";
    }

    /** {@code TypeError: cannot set 'NAME' attribute of immutable type 'KIND'}. */
    private static String immutable(String name, String kind) {
        return "TypeError: cannot set '" + name + "' attribute of immutable type '" + kind + "'";
    }

    /** {@code TypeError: descriptor 'NAME' for 'type' objects doesn't apply to a 'KIND' object}. */
    private static String wrongReceiver(String name, String kind) {
        return "TypeError: descriptor '"
                + name
                + "' for 'type' objects doesn't apply to a '"
                + kind
                + "' object";
    }

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    @Test
    void twelveGetsetsStandInTypesBookAndThreeOfThemHaveNoWriter() {
        assertThat(getsetRows(PyType.TYPE)).isEqualTo(TYPE_GETSETS);
        for (String name : NO_WRITER) {
            PyObject entry = row(name);
            assertThat(entry.getType().name()).isEqualTo("getset_descriptor");
            // `PyDescr_IsData` asks the descriptor's own type for a `tp_descr_set`, and that type always has
            // one, so a row with nothing in its writing half still outranks a dictionary of its own.
            assertThat(Descriptors.writes(entry)).isTrue();
            assertThat(Slots.getAttr(entry, "__name__")).isEqualTo(PyStr.of(name));
            assertThat(Slots.getAttr(entry, "__objclass__")).isSameAs(PyType.TYPE);
            assertThat(Slots.getAttr(entry, "__doc__")).isSameAs(PyNone.INSTANCE);
        }
        assertThat(row(ABSTRACT)).isNotSameAs(row("__mro__"));
    }

    @Test
    void aRowWithNoWriterRefusesBothOfItsHalvesInItsOwnWords() {
        PyType kind = classOf("WriteHalf");
        for (String name : NO_WRITER) {
            refuses(notWritable(name), () -> writeRow(name, kind, ONE));
            refuses(notWritable(name), () -> deleteRow(name, kind));
            // The name asked through the class reaches the same row, so the two paths agree on a heap type.
            refuses(notWritable(name), () -> Slots.setAttr(kind, name, ONE));
            refuses(notWritable(name), () -> Slots.delAttr(kind, name));
        }
        assertThat(getsetRows(PyType.TYPE)).isEqualTo(TYPE_GETSETS);
    }

    @Test
    void everyWriteRefusalLeavesTheReadHalfAnswering() {
        PyType kind = classOf("ReadHalf");
        for (String name : NO_WRITER) {
            refuses(notWritable(name), () -> writeRow(name, PyType.INT, ONE));
        }
        assertThat(Descriptors.get(row("__mro__"), PyType.INT, PyType.TYPE)).isInstanceOf(PyTuple.class);
        assertThat(Descriptors.get(row("__text_signature__"), PyType.INT, PyType.TYPE))
                .isSameAs(PyNone.INSTANCE);
        assertThat(Descriptors.get(row("__dict__"), kind, PyType.TYPE)).isInstanceOf(PyMappingProxy.class);
        assertThat(Slots.getAttr(kind, "__mro__")).isInstanceOf(PyTuple.class);
        assertThat(Slots.getAttr(kind, "__text_signature__")).isSameAs(PyNone.INSTANCE);
    }

    @Test
    void theReceiverIsAskedBeforeTheWriterIsMissing() {
        List<PyObject> receivers = List.of(THREE, PyNone.INSTANCE, TEXT);
        List<String> kinds = List.of("int", "NoneType", "str");
        for (String name : NO_WRITER) {
            for (int i = 0; i < receivers.size(); i++) {
                PyObject receiver = receivers.get(i);
                String sentence = wrongReceiver(name, kinds.get(i));
                refuses(sentence, () -> writeRow(name, receiver, ONE));
                refuses(sentence, () -> deleteRow(name, receiver));
            }
        }
        // A row of `type` asked of a number never reaches the immutable gate a class would have met, because
        // the gate that refuses it stands in front of both halves of the descriptor.
        assertThat(Descriptors.writes(row("__mro__"))).isTrue();
    }

    @Test
    void aBuiltInTypeRefusesTheNameByPathAndAnswersTheWriterByDescriptor() {
        for (String name : NO_WRITER) {
            refuses(immutable(name, "int"), () -> Slots.setAttr(PyType.INT, name, ONE));
            refuses(immutable(name, "int"), () -> Slots.delAttr(PyType.INT, name));
            refuses(immutable(name, "type"), () -> Slots.setAttr(PyType.TYPE, name, ONE));
            refuses(notWritable(name), () -> writeRow(name, PyType.INT, ONE));
            refuses(notWritable(name), () -> deleteRow(name, PyType.INT));
            assertThat(getsetRows(PyType.INT)).doesNotContain(name);
        }
        assertThat(getsetRows(PyType.INT)).isEqualTo(INT_GETSETS);
    }

    @Test
    void abstractmethodsFilesOntoABuiltInTypeAndTheBareNameAnswersWhenItIsEmpty() {
        int before = book(PyType.INT).size();
        try {
            writeRow(ABSTRACT, PyType.INT, ONE);
            assertThat(Slots.getAttr(PyType.INT, ABSTRACT)).isSameAs(ONE);
            assertThat(book(PyType.INT).size()).isEqualTo(before + 1);
            deleteRow(ABSTRACT, PyType.INT);
            assertThat(book(PyType.INT).size()).isEqualTo(before);
            // A delete of a filing that was never there is the attribute's own name, not a sentence about it.
            refuses("AttributeError: " + ABSTRACT, () -> Slots.getAttr(PyType.INT, ABSTRACT));
            refuses("AttributeError: " + ABSTRACT, () -> deleteRow(ABSTRACT, PyType.INT));
        } finally {
            book(PyType.INT).remove(PyStr.interned(ABSTRACT));
        }
        assertThat(book(PyType.INT).size()).isEqualTo(before);
        assertThat(getsetRows(PyType.INT)).isEqualTo(INT_GETSETS);
    }

    @Test
    void abstractmethodsFiledOnTheMetatypeShadowsItsOwnRowAndReadsBelow() {
        // The row is taken out of the book first: the filing lands under the same key and covers it over.
        PyObject entry = row(ABSTRACT);
        PyType kind = classOf("Below");
        try {
            writeEntry(entry, PyType.TYPE, ONE);
            assertThat(book(PyType.TYPE).get(PyStr.interned(ABSTRACT))).isSameAs(ONE);
            assertThat(getsetRows(PyType.TYPE)).hasSize(TYPE_GETSETS.size() - 1).doesNotContain(ABSTRACT);
            assertThat(Slots.getAttr(PyType.TYPE, ABSTRACT)).isSameAs(ONE);
            // Every class below reads the value out of the metatype's book while its own keeps none.
            assertThat(Slots.getAttr(kind, ABSTRACT)).isSameAs(ONE);
            assertThat(Slots.getAttr(PyType.INT, ABSTRACT)).isSameAs(ONE);
            assertThat(book(PyType.INT).get(PyStr.interned(ABSTRACT))).isNull();
            // The name is gone from the book, so the read of the row that was filed under it asks the row.
            assertThat(Slots.getAttr(entry, "__name__")).isEqualTo(PyStr.of(ABSTRACT));
        } finally {
            book(PyType.TYPE).put(PyStr.interned(ABSTRACT), entry);
        }
        assertThat(getsetRows(PyType.TYPE)).isEqualTo(TYPE_GETSETS);
        refuses("AttributeError: " + ABSTRACT, () -> Slots.getAttr(kind, ABSTRACT));
    }

    @Test
    void annotationsAsksTheGateThatAbstractmethodsDoesNot() {
        refuses(immutable("__annotations__", "int"), () -> writeRow("__annotations__", PyType.INT, ONE));
        refuses(immutable("__annotations__", "int"), () -> deleteRow("__annotations__", PyType.INT));
        // `type_set_annotations` begins with `type_check_modifiable` and `type_set_abstractmethods` does not.
        PyObject entry = row(ABSTRACT);
        try {
            writeEntry(entry, PyType.INT, ONE);
            assertThat(Slots.getAttr(PyType.INT, ABSTRACT)).isSameAs(ONE);
        } finally {
            book(PyType.INT).remove(PyStr.interned(ABSTRACT));
        }
    }

    @Test
    void boolFilesNoGetsetAndTheWalkAnswersItsFourNamesFromInt() {
        assertThat(getsetRows(PyType.BOOL)).isEmpty();
        for (String name : INT_GETSETS) {
            assertThat(book(PyType.BOOL).get(PyStr.interned(name))).isNull();
            PyObject entry = book(PyType.INT).get(PyStr.interned(name));
            assertThat(entry).isNotNull();
            String sentence = "AttributeError: attribute '" + name + "' of 'int' objects is not writable";
            refuses(sentence, () -> Descriptors.set(entry, PyBool.TRUE, ONE));
            refuses(sentence, () -> Descriptors.delete(entry, PyBool.TRUE));
            // A value of `bool` is a value of `int`, so the attribute syntax reaches the same row.
            refuses(sentence, () -> Slots.setAttr(PyBool.TRUE, name, ONE));
        }
    }

    @Test
    void aGetsetSpeaksInTheFieldsWordsAndAMemberInItsOwn() {
        PyObject floatRow = book(PyType.FLOAT).get(PyStr.interned("real"));
        assertThat(floatRow.getType().name()).isEqualTo("getset_descriptor");
        refuses(
                "AttributeError: attribute 'real' of 'float' objects is not writable",
                () -> Descriptors.set(floatRow, PyFloat.of(1.5), ONE));
        refuses(
                "AttributeError: attribute 'real' of 'float' objects is not writable",
                () -> Descriptors.delete(floatRow, PyFloat.of(1.5)));

        PyObject member = book(PyType.SUPER).get(PyStr.interned("__self__"));
        assertThat(member.getType().name()).isEqualTo("member_descriptor");
        PySuper proxy = PySuper.of(PyType.INT, THREE);
        refuses("AttributeError: readonly attribute", () -> Descriptors.set(member, proxy, ONE));
        refuses("AttributeError: readonly attribute", () -> Descriptors.delete(member, proxy));
    }

    @Test
    void theFiveRowsWithAWriterKeepTheirOwnGatesBehindIt() {
        PyType kind = classOf("Gates");
        refuses(
                "TypeError: can only assign string to Gates.__name__, not 'int'",
                () -> writeRow("__name__", kind, ONE));
        refuses(
                "TypeError: can only assign tuple to Gates.__bases__, not int",
                () -> writeRow("__bases__", kind, ONE));
        refuses(immutable("__name__", "int"), () -> writeRow("__name__", PyType.INT, ONE));
        refuses(immutable("__bases__", "int"), () -> writeRow("__bases__", PyType.INT, ONE));
        // A write that passes takes any value at all, and the delete of a class's own name does not.
        writeRow("__module__", kind, TEXT);
        assertThat(Slots.getAttr(kind, "__module__")).isEqualTo(TEXT);
        refuses(
                "TypeError: cannot delete '__module__' attribute of immutable type 'Gates'",
                () -> deleteRow("__module__", kind));
        assertThat(Descriptors.get(row("__doc__"), kind, PyType.TYPE)).isSameAs(PyNone.INSTANCE);
    }

    @Test
    void dictIsFiledInAClassBookAndInNoBuiltInOne() {
        PyType kind = classOf("Booked");
        assertThat(book(kind).get(PyStr.interned("__dict__"))).isNotNull();
        assertThat(book(PyType.INT).get(PyStr.interned("__dict__"))).isNull();
        assertThat(Slots.getAttr(kind, "__dict__")).isInstanceOf(PyMappingProxy.class);
        // The row that hands out the book is one of the two getsets a class Python built files of its own.
        assertThat(getsetRows(kind)).isEqualTo(List.of("__dict__", "__weakref__"));
    }
}
