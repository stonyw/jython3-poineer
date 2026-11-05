package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The three books of the singletons — eleven names, five and four — measured against CPython 3.14.10.
 *
 * <p>Corpus case 120 reads each row of each book from Python and prints the complaint every refusal makes.
 * What is pinned here is the store behind those reads. The first of it is the welded text: {@code tp_doc} of
 * these three types is a table of an empty argument list, the rule under it and one sentence, so
 * {@code __text_signature__} is {@code ()} for all three and {@code __doc__} is the sentence, and the row the
 * dictionary keeps under {@code __doc__} is that sentence rather than the weld the two knives cut it from.
 *
 * <p>The second is the split between the two names one type carries. {@code PyEllipsis_Type} is filed under
 * the {@code tp_name} {@code ellipsis}, which is what its table says and what {@code __qualname__} of every
 * row repeats, while the constructor's refusal is written beside the C struct and says {@code EllipsisType
 * takes no arguments}. The same split runs through the {@code __new__} row: the gate above the count names
 * the type, so {@code ellipsis.__new__(int)} answers {@code ellipsis.__new__(int): int is not a subtype of
 * ellipsis} and {@code ellipsis.__new__(ellipsis, 1)} answers {@code EllipsisType takes no arguments}.
 *
 * <p>Then the order of the rows, which is the order of the slots rather than the order a person would write:
 * {@code tp_repr}, {@code tp_hash}, the six of {@code tp_richcompare}, {@code nb_bool}, then the sentence.
 * {@code NoneType} reaches {@code dir()} of twenty-five with eleven rows because ten of them are names
 * {@code object} files too; {@code NotImplementedType} reaches the same twenty-five with five; {@code
 * ellipsis} files no truth row at all and so stays at {@code object}'s twenty-four.
 *
 * <p>One read of these books does not agree with the reference, and it is not a read of the singletons.
 * {@code object.__ne__} and {@code NoneType.__ne__} answer the negation of the pointer here, where CPython
 * negates the left value's own {@code __eq__} and declines with it when that declines: {@code
 * object.__ne__(None, 1)} is {@code True} here and {@code NotImplemented} there. The other five rows agree,
 * which is why the equality rows are pinned at the pointer and the truth rows at their own answers.
 */
class SingletonBookTest {

    /** The three types whose only value is the singleton the language names. */
    private static final List<PyType> SINGLETONS =
            List.of(PyType.NONE, PyType.NOTIMP, PyType.ELLIPSIS);

    /** {@code NoneType}'s eleven names, in the order its dictionary keeps them. */
    private static final List<String> NONE_BOOK =
            List.of(
                    "__new__",
                    "__repr__",
                    "__hash__",
                    "__lt__",
                    "__le__",
                    "__eq__",
                    "__ne__",
                    "__gt__",
                    "__ge__",
                    "__bool__",
                    "__doc__");

    /** {@code notimplemented_methods} ({@code Objects/object.c}) beside the three slot copies. */
    private static final List<String> NOTIMP_BOOK =
            List.of("__new__", "__repr__", "__bool__", "__reduce__", "__doc__");

    /** The shortest book in the language: the constructor, its two rows and the sentence. */
    private static final List<String> ELLIPSIS_BOOK =
            List.of("__new__", "__repr__", "__reduce__", "__doc__");

    /** The six rows {@code tp_richcompare} is copied out as, in the order the reference files them. */
    private static final List<String> COMPARISONS =
            List.of("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__");

    /** The one name each of the first two books adds to the twenty-four {@code object} already keeps. */
    private static final List<String> TRUTH_ROWS = List.of("__bool__");

    /** The sentence each type welds under its own {@code ()} table. */
    private static final String NONE_SENTENCE = "The type of the None singleton.";

    private static final String NOTIMP_SENTENCE = "The type of the NotImplemented singleton.";

    private static final String ELLIPSIS_SENTENCE = "The type of the Ellipsis singleton.";

    /** The text of {@code object_new}'s clinic block, which every {@code __new__} row of a static type keeps. */
    private static final String NEW_DOC =
            "Create and return a new object.  See help(type) for accurate signature.";

    private static final PyObject NONE = PyNone.INSTANCE;

    private static final PyObject NOTIMP = PyNotImplemented.INSTANCE;

    private static final PyObject ELLIPSIS = PyEllipsis.INSTANCE;

    private static final PyObject ONE = PyInt.of(1);

    private static final PyObject THREE = PyInt.of(3);

    private static final PyObject FOUR = PyInt.of(4);

    /** The one keyword name, for the refusals that count a keyword argument as an argument. */
    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    /** {@code kind.__dict__[name]}: the row the book keeps, or null where it keeps no such name. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** The names the book keeps, in the order it kept them. */
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

    /** {@code sorted(set(dir(kind) - set(dir(object)))}. */
    private static List<String> gained(PyType kind) {
        List<String> names = new ArrayList<>(Dirs.classNames(kind));
        names.removeAll(Dirs.classNames(PyType.OBJECT));
        Collections.sort(names);
        return names;
    }

    private static String rowKind(PyType kind, String name) {
        return entry(kind, name).getType().name();
    }

    private static String qualified(PyType kind, String name) {
        return ((PyStr) Slots.getAttr(entry(kind, name), "__qualname__")).getValue();
    }

    private static String signature(PyType kind, String name) {
        return ((PyStr) Slots.getAttr(entry(kind, name), "__text_signature__")).getValue();
    }

    /** {@code kind(*args, **kwargs)}: the type itself as a call, the way {@code type(None)()} reaches it. */
    private static PyObject create(PyType kind, PyTuple kwNames, PyObject... args) {
        return Slots.call(kind, args, kwNames);
    }

    private static PyObject createNew(PyType kind, PyTuple kwNames, PyObject... args) {
        return Slots.call(entry(kind, "__new__"), args, kwNames);
    }

    private static PyObject callRow(PyType kind, String name, PyObject... args) {
        return Slots.call(entry(kind, name), args, null);
    }

    /** {@code row.__get__(*receivers)}: the row asked to bind, which is where the truth row refuses. */
    private static PyObject bind(PyObject row, PyObject... receivers) {
        return Slots.call(Slots.getAttr(row, "__get__"), receivers, null);
    }

    /**
     * The name the creator's refusal is written under: the C struct, which for the third book is not the name
     * the type is filed under.
     */
    private static String structOf(PyType kind) {
        if (kind == PyType.NONE) {
            return "NoneType";
        }
        return kind == PyType.NOTIMP ? "NotImplementedType" : "EllipsisType";
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
    void theBuiltinNameOfEachValueIsItsOwnType() {
        assertThat(NONE.getType()).isSameAs(PyType.NONE);
        assertThat(NOTIMP.getType()).isSameAs(PyType.NOTIMP);
        assertThat(ELLIPSIS.getType()).isSameAs(PyType.ELLIPSIS);
        assertThat(PyRuntime.builtins().get(PyStr.interned("None"))).isSameAs(NONE);
        assertThat(PyRuntime.builtins().get(PyStr.interned("NotImplemented"))).isSameAs(NOTIMP);
        assertThat(PyRuntime.builtins().get(PyStr.interned("Ellipsis"))).isSameAs(ELLIPSIS);
        assertThat(create(PyType.NONE, null)).isSameAs(NONE);
        assertThat(create(PyType.NOTIMP, null)).isSameAs(NOTIMP);
        assertThat(create(PyType.ELLIPSIS, null)).isSameAs(ELLIPSIS);
    }

    @Test
    void eachBookKeepsItsRowsInTheOrderOfTheSlots() {
        assertThat(filed(PyType.NONE)).isEqualTo(NONE_BOOK);
        assertThat(filed(PyType.NOTIMP)).isEqualTo(NOTIMP_BOOK);
        assertThat(filed(PyType.ELLIPSIS)).isEqualTo(ELLIPSIS_BOOK);
        assertThat(NONE_BOOK).hasSize(11).endsWith("__bool__", "__doc__");
        assertThat(NONE_BOOK.subList(3, 9)).isEqualTo(COMPARISONS);
    }

    @Test
    void onlyTheTruthRowStandsAboveObject() {
        assertThat(Dirs.classNames(PyType.NONE)).hasSize(25);
        assertThat(Dirs.classNames(PyType.NOTIMP)).hasSize(25);
        assertThat(Dirs.classNames(PyType.ELLIPSIS)).hasSize(24);
        assertThat(gained(PyType.NONE)).isEqualTo(TRUTH_ROWS);
        assertThat(gained(PyType.NOTIMP)).isEqualTo(TRUTH_ROWS);
        assertThat(gained(PyType.ELLIPSIS)).isEmpty();
        assertThat(entry(PyType.ELLIPSIS, "__bool__")).isNull();
    }

    @Test
    void oneWeldedTextCarriesAnEmptyTableAndASentence() {
        List<String> sentences =
                List.of(NONE_SENTENCE, NOTIMP_SENTENCE, ELLIPSIS_SENTENCE);
        for (int at = 0; at < SINGLETONS.size(); at++) {
            PyType kind = SINGLETONS.get(at);
            String stored = kind.docText();
            assertThat(stored)
                    .as("%s opens with its own name and an empty list", kind.name())
                    .isEqualTo(kind.name() + "()\n--\n\n" + sentences.get(at));
            assertThat(Slots.getAttr(kind, "__text_signature__")).isEqualTo(PyStr.of("()"));
            assertThat(Slots.getAttr(kind, "__doc__")).isEqualTo(PyStr.of(sentences.get(at)));
            assertThat(entry(kind, "__doc__")).isEqualTo(PyStr.of(sentences.get(at)));
        }
    }

    @Test
    void theCreatorRefusesEverythingInTheWordsOfTheStruct() {
        for (PyType kind : SINGLETONS) {
            String refusal = "TypeError: " + structOf(kind) + " takes no arguments";
            refuses(refusal, () -> create(kind, null, ONE));
            refuses(refusal, () -> create(kind, null, ONE, THREE));
            refuses(refusal, () -> create(kind, X, ONE));
        }
        assertThat(create(PyType.NONE, null)).isSameAs(NONE);
    }

    @Test
    void theCreatorRowGatesTheClassBeforeItCounts() {
        for (PyType kind : SINGLETONS) {
            String named = kind.name();
            refuses(
                    "TypeError: " + named + ".__new__(): not enough arguments",
                    () -> createNew(kind, null));
            refuses(
                    "TypeError: " + named + ".__new__(int): int is not a subtype of " + named,
                    () -> createNew(kind, null, PyType.INT));
            assertThat(createNew(kind, null, kind)).isSameAs(theValue(kind));
            // The gate has passed and the count has not, and it is the count that speaks the struct's name.
            String refusal = "TypeError: " + structOf(kind) + " takes no arguments";
            refuses(refusal, () -> createNew(kind, null, kind, ONE));
            refuses(refusal, () -> createNew(kind, X, kind, ONE));
        }
    }

    @Test
    void theNewRowIsNamedForTheTypeAndNotForTheStruct() {
        for (PyType kind : SINGLETONS) {
            assertThat(rowKind(kind, "__new__")).isEqualTo("builtin_function_or_method");
            assertThat(qualified(kind, "__new__")).isEqualTo(kind.name() + ".__new__");
            assertThat(signature(kind, "__new__")).isEqualTo("($type, *args, **kwargs)");
            assertThat(Slots.getAttr(entry(kind, "__new__"), "__doc__")).isEqualTo(PyStr.of(NEW_DOC));
            // A builtin function files no owner at all, so the question has no answer rather than an empty one.
            refuses(
                    "AttributeError: 'builtin_function_or_method' object has no attribute '__objclass__'",
                    () -> Slots.getAttr(entry(kind, "__new__"), "__objclass__"));
        }
    }

    @Test
    void theTruthRowsAnswerAndRefuseAsTheValuesDo() {
        assertThat(rowKind(PyType.NONE, "__bool__")).isEqualTo("wrapper_descriptor");
        assertThat(signature(PyType.NONE, "__bool__")).isEqualTo("($self, /)");
        assertThat(Slots.getAttr(entry(PyType.NONE, "__bool__"), "__doc__"))
                .isEqualTo(PyStr.of("True if self else False"));
        assertThat(callRow(PyType.NONE, "__bool__", NONE)).isSameAs(PyBool.FALSE);
        assertThat(Slots.truth(NONE)).isFalse();
        assertThat(Slots.truth(ELLIPSIS)).isTrue();
        refuses(
                "TypeError: NotImplemented should not be used in a boolean context",
                () -> callRow(PyType.NOTIMP, "__bool__", NOTIMP));
        refuses(
                "TypeError: NotImplemented should not be used in a boolean context",
                () -> Slots.truth(NOTIMP));
    }

    @Test
    void thePickleRowsCarryATableAndNoSentence() {
        for (PyType kind : List.of(PyType.NOTIMP, PyType.ELLIPSIS)) {
            assertThat(rowKind(kind, "__reduce__")).isEqualTo("method_descriptor");
            assertThat(Slots.getAttr(entry(kind, "__reduce__"), "__doc__")).isSameAs(PyNone.INSTANCE);
            assertThat(signature(kind, "__reduce__")).isEqualTo("($self, /)");
            assertThat(qualified(kind, "__reduce__")).isEqualTo(kind.name() + ".__reduce__");
            assertThat(Slots.getAttr(entry(kind, "__reduce__"), "__objclass__")).isSameAs(kind);
        }
        assertThat(callRow(PyType.NOTIMP, "__reduce__", NOTIMP)).isEqualTo(PyStr.of("NotImplemented"));
        assertThat(callRow(PyType.ELLIPSIS, "__reduce__", ELLIPSIS)).isEqualTo(PyStr.of("Ellipsis"));
        assertThat(entry(PyType.NONE, "__reduce__")).isNull();
    }

    @Test
    void theComparisonsAnswerEqualityOutOfThePointerAndDeclineTheRest() {
        for (String row : List.of("__lt__", "__le__", "__gt__", "__ge__")) {
            assertThat(rowKind(PyType.NONE, row)).isEqualTo("wrapper_descriptor");
            assertThat(signature(PyType.NONE, row)).isEqualTo("($self, value, /)");
            assertThat(callRow(PyType.NONE, row, NONE, ONE)).isSameAs(NOTIMP);
            assertThat(callRow(PyType.NONE, row, NONE, NONE)).isSameAs(NOTIMP);
        }
        assertThat(callRow(PyType.NONE, "__eq__", NONE, NONE)).isSameAs(PyBool.TRUE);
        assertThat(callRow(PyType.NONE, "__eq__", NONE, ONE)).isSameAs(NOTIMP);
        assertThat(callRow(PyType.OBJECT, "__eq__", THREE, FOUR)).isSameAs(NOTIMP);
        assertThat(callRow(PyType.OBJECT, "__eq__", THREE, THREE)).isSameAs(PyBool.TRUE);
        assertThat(callRow(PyType.OBJECT, "__lt__", THREE, FOUR)).isSameAs(NOTIMP);
    }

    @Test
    void aRowAskedForAReceiverOfAnotherKindRefusesInItsOwnWords() {
        refuses(
                "TypeError: descriptor '__repr__' requires a 'NoneType' object but received a 'int'",
                () -> callRow(PyType.NONE, "__repr__", ONE));
        refuses(
                "TypeError: descriptor '__bool__' requires a 'NotImplementedType' object"
                        + " but received a 'int'",
                () -> callRow(PyType.NOTIMP, "__bool__", ONE));
        refuses(
                "TypeError: descriptor '__repr__' requires a 'ellipsis' object"
                        + " but received a 'NotImplementedType'",
                () -> callRow(PyType.ELLIPSIS, "__repr__", NOTIMP));
        refuses(
                "TypeError: descriptor '__reduce__' for 'NotImplementedType' objects doesn't apply to a"
                        + " 'int' object",
                () -> callRow(PyType.NOTIMP, "__reduce__", ONE));
        refuses(
                "TypeError: descriptor '__reduce__' for 'ellipsis' objects doesn't apply to a 'NoneType'"
                        + " object",
                () -> callRow(PyType.ELLIPSIS, "__reduce__", NONE));
    }

    @Test
    void bindingTheTruthRowToNothingIsBindingItToNoReceiver() {
        PyObject row = entry(PyType.NONE, "__bool__");
        refuses("TypeError: __get__(None, None) is invalid", () -> bind(row, NONE));
        assertThat(Slots.getAttr(row, "__name__")).isEqualTo(PyStr.of("__bool__"));
        assertThat(Slots.getAttr(row, "__objclass__")).isSameAs(PyType.NONE);
        // Bound to the only receiver it has, the row is still a wrapper, and still wants its argument.
        PyObject bound = bind(row, NONE, PyType.NONE);
        assertThat(bound.getType().name()).isEqualTo("wrapper_descriptor");
        refuses(
                "TypeError: descriptor '__bool__' of 'NoneType' object needs an argument",
                () -> Slots.call(bound, new PyObject[0], null));

        PyObject truth = bind(entry(PyType.NOTIMP, "__bool__"), NOTIMP, PyType.NOTIMP);
        assertThat(truth.getType().name()).isEqualTo("method-wrapper");
        refuses(
                "TypeError: NotImplemented should not be used in a boolean context",
                () -> Slots.call(truth, new PyObject[0], null));
    }

    /** The one value of the singleton type the gate was handed. */
    private static PyObject theValue(PyType kind) {
        if (kind == PyType.NONE) {
            return NONE;
        }
        return kind == PyType.NOTIMP ? NOTIMP : ELLIPSIS;
    }
}
