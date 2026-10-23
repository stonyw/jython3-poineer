package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code sys.version_info} as the runtime decides it, measured against CPython 3.14.8.
 *
 * <p>The corpus scripts read a version the way a program does — a field, a repr, a refused write. This is
 * the machinery under those, which no script can point at: that the type answers before the interpreter has
 * made a value of it, that a field is the item at its declaration index rather than a name attached to a
 * number, which of two refusals a write reaches first, and which name an arity complaint wears depending on
 * whether the wrapper counted or the method did.
 *
 * <p>Two names of this type are the same word in different mouths. {@code tp_name} is
 * {@code sys.version_info} and quotes itself in every message about the type; {@code __name__} is
 * {@code version_info} and is what a qualified name is built from. Each assertion below that reads a
 * sentence checks which of the two it wore.
 */
class VersionInfoTest {

    /** The one value the interpreter files, and the copy every other value of this type is made from. */
    private static final PyVersionInfo version = PyVersionInfo.current();

    private static final PyType kind = PyType.VERSION_INFO;

    /** The five names the type files as storage, in item order. */
    private static final String[] FIELDS = {
        "major", "minor", "micro", "releaselevel", "serial"
    };

    /** {@code TypeError} and {@code AttributeError} in CPython's own words, which the runtime repeats. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** {@code kind.name}: the class's own read, before the descriptor protocol runs. */
    private static PyObject filed(String name) {
        return kind.getAttr(name);
    }

    /** {@code version.__replace__(**changes)}: the copy made through the method a value hands out. */
    private static PyObject replaced(PyObject[] values, String... names) {
        return Slots.call(
                Slots.getAttr(version, "__replace__"),
                values,
                names.length == 0 ? null : PyBoundMethod.keywordTuple(names));
    }

    /** {@code version_info.__get__(...)}: the wrapper CPython installs for {@code tp_descr_get}. */
    private static PyObject getOf(String field, PyObject[] values) {
        return Slots.call(filed(field).getAttr("__get__"), values, null);
    }

    /**
     * The type is answerable on its own, and both halves of its name are used.
     *
     * <p>A table is a Java static, so a script that only ever asks {@code version_info.major} has never
     * made a version — {@link Members#tableOf} has to initialize the value class on that ask. The dotted
     * name is what a repr, an {@code __module__} and every message about the type read; the half after the
     * last dot is what {@code __name__} is, and a name with two dots in it splits on the last one.
     */
    @Test
    void theTypeAnswersBeforeAnyValueOfItHasBeenMade() {
        assertThat(Members.tableOf(kind)).isNotNull();
        assertThat(filed("major")).isInstanceOf(PyMemberDescriptor.class);
        assertThat(kind.repr()).isEqualTo("<class 'sys.version_info'>");
        assertThat(kind.getAttr("__name__").str()).isEqualTo("version_info");
        assertThat(kind.getAttr("__qualname__").str()).isEqualTo("version_info");
        assertThat(kind.getAttr("__module__").str()).isEqualTo("sys");
        assertThat(PyType.INT.getAttr("__module__").str()).isEqualTo("builtins");
        assertThat(PyType.MEMBER_DESCRIPTOR.getAttr("__module__").str()).isEqualTo("builtins");
        assertThat(kind.mro()).containsExactly(PyType.VERSION_INFO, PyType.TUPLE, PyType.OBJECT);
    }

    /**
     * A field is the item at its own position, read out of whichever value carries it.
     *
     * <p>The index comes from declaration order and not from the item: a copy whose fields have been
     * swapped for markers reads each marker back under its own name, and the five descriptors stay where
     * they were while everything under them moves.
     */
    @Test
    void eachFieldReadsTheItemAtItsOwnPosition() {
        PyObject[] markers = new PyObject[FIELDS.length];
        for (int index = 0; index < FIELDS.length; index++) {
            PyObject one = filed(FIELDS[index]);
            assertThat(Slots.getAttr(version, FIELDS[index]))
                    .isSameAs(((PyTuple) version).at(index));
            assertThat(Descriptors.get(one, version, kind)).isSameAs(((PyTuple) version).at(index));
            assertThat(one.getAttr("__name__").str()).isEqualTo(FIELDS[index]);
            // A qualified name is built from __qualname__, so the module comes off it.
            assertThat(one.getAttr("__qualname__").str()).isEqualTo("version_info." + FIELDS[index]);
            assertThat(one.repr())
                    .isEqualTo("<member '" + FIELDS[index] + "' of 'sys.version_info' objects>");
            markers[index] = PyStr.of("field" + index);
        }

        PyObject swapped = replaced(markers, FIELDS);
        for (int index = 0; index < FIELDS.length; index++) {
            assertThat(Slots.getAttr(swapped, FIELDS[index]).str()).isEqualTo("field" + index);
        }
        assertThat(swapped.repr()).isEqualTo(
                "sys.version_info(major='field0', minor='field1', micro='field2',"
                        + " releaselevel='field3', serial='field4')");
    }

    /**
     * A read of the class stops at the descriptor; a read of the value goes through it.
     *
     * <p>{@code __get__} is the same door from both sides, and the third answer is the one a program never
     * reaches: handed neither value nor class it refuses, because a member has nothing to read. The owner
     * it is handed is ignored — {@code tuple} as owner still finds the item, since only the receiver is
     * looked at.
     */
    @Test
    void theDescriptorIsItselfThroughTheClassAndTheItemThroughAValue() {
        PyObject major = filed("major");
        assertThat(Slots.getAttr(version, "major")).isNotSameAs(major);
        assertThat(Descriptors.get(major, null, kind)).isSameAs(major);
        assertThat(filed("major")).isSameAs(major);
        assertThat(Descriptors.get(major, version, PyType.TUPLE)).isSameAs(((PyTuple) version).at(0));

        assertThat(getOf("major", new PyObject[] {version})).isSameAs(((PyTuple) version).at(0));
        assertThat(getOf("major", new PyObject[] {version, PyType.TUPLE}))
                .isSameAs(((PyTuple) version).at(0));
        assertThat(getOf("major", new PyObject[] {PyNone.INSTANCE, kind})).isSameAs(major);
        refuses(
                "TypeError: __get__(None, None) is invalid",
                () -> getOf("major", new PyObject[] {PyNone.INSTANCE, PyNone.INSTANCE}));
        refuses(
                "TypeError: __get__ expected at least 1 argument, got 0",
                () -> getOf("major", new PyObject[0]));
        refuses(
                "TypeError: __get__ expected at most 2 arguments, got 3",
                () -> getOf("major", new PyObject[] {version, kind, kind}));
    }

    /**
     * The three refusals of a write, and the one gate they are all reached through.
     *
     * <p>CPython's {@code setattr} and {@code delattr} share the walk of the type, so a name filed as a
     * member is refused by its descriptor with a sentence about the field alone, a name filed as anything
     * else — a count, a {@code tuple} method — is refused by the generic setter with a sentence about the
     * type and the name, and a name nobody files is refused for the name and for the dictionary this value
     * has not got. Deleting changes none of the three, which is what a runtime that reads the wording per
     * half gets wrong.
     */
    @Test
    void writingAndDeletingReachTheSameGate() {
        refuses("AttributeError: readonly attribute", () -> Slots.setAttr(version, "major", PyInt.of(4)));
        refuses("AttributeError: readonly attribute", () -> Slots.delAttr(version, "major"));
        for (String field : FIELDS) {
            refuses("AttributeError: readonly attribute", () -> Slots.setAttr(version, field, PyInt.of(1)));
        }

        // A filed name that is not a descriptor: the dictionary's own entry, and a method tuple declares.
        refuses(
                "AttributeError: 'sys.version_info' object attribute 'n_fields' is read-only",
                () -> Slots.setAttr(version, "n_fields", PyInt.of(2)));
        refuses(
                "AttributeError: 'sys.version_info' object attribute '__doc__' is read-only",
                () -> Slots.delAttr(version, "__doc__"));
        refuses(
                "AttributeError: 'sys.version_info' object attribute 'count' is read-only",
                () -> Slots.setAttr(version, "count", PyInt.of(1)));

        refuses(
                "AttributeError: 'sys.version_info' object has no attribute 'majorr'"
                        + " and no __dict__ for setting new attributes",
                () -> Slots.setAttr(version, "majorr", PyInt.of(1)));
        refuses(
                "AttributeError: 'sys.version_info' object has no attribute 'majorr'"
                        + " and no __dict__ for setting new attributes",
                () -> Slots.delAttr(version, "majorr"));
        refuses(
                "AttributeError: 'sys.version_info' object has no attribute 'nope'",
                () -> Slots.getAttr(version, "nope"));

        // The type itself takes every write as a refusal, in the immutable-type words.
        refuses(
                "TypeError: cannot set 'major' attribute of immutable type 'sys.version_info'",
                () -> kind.setAttr("major", PyInt.of(1)));
        refuses(
                "TypeError: cannot set '__doc__' attribute of immutable type 'sys.version_info'",
                () -> kind.delAttr("__doc__"));
    }

    /**
     * What the wrappers count, and in what order they count it.
     *
     * <p>Keywords are judged in the wrapper before the count is taken from the argument list, so
     * {@code __delete__(v, x=1)} complains about the keyword rather than about arriving with two arguments.
     * The receiver is checked last of all, which is why {@code __set__(4)} says nothing about the int while
     * {@code __set__(4, 1)} does. And the two halves of {@code tp_descr_set} wear different names:
     * {@code __set__} counts in its own words, {@code __delete__} counts in nobody's.
     */
    @Test
    void aWrapperJudgesKeywordsBeforeCountingAndCountingBeforeTheReceiver() {
        PyObject set = filed("major").getAttr("__set__");
        PyObject delete = filed("major").getAttr("__delete__");
        PyTuple x = PyBoundMethod.keywordTuple(new String[] {"x"});

        refuses(
                "TypeError: wrapper __set__() takes no keyword arguments",
                () -> Slots.call(set, new PyObject[] {version, PyInt.of(1), PyInt.of(2)}, x));
        refuses(
                "TypeError: wrapper __delete__() takes no keyword arguments",
                () -> Slots.call(delete, new PyObject[] {version, PyInt.of(1)}, x));

        refuses(
                "TypeError: __set__ expected 2 arguments, got 1",
                () -> Slots.call(set, new PyObject[] {version}, null));
        refuses("TypeError: expected 1 argument, got 0", () -> Slots.call(delete, new PyObject[0], null));
        refuses(
                "TypeError: expected 1 argument, got 2",
                () -> Slots.call(delete, new PyObject[] {version, PyInt.of(1)}, null));

        // Two arguments arrived, so the receiver is asked about at last.
        refuses(
                "TypeError: descriptor 'major' for 'sys.version_info' objects doesn't apply to a 'int' object",
                () -> Slots.call(set, new PyObject[] {PyInt.of(4), PyInt.of(1)}, null));
        refuses(
                "TypeError: descriptor 'major' for 'sys.version_info' objects doesn't apply to a 'int' object",
                () -> Slots.call(delete, new PyObject[] {PyInt.of(4)}, null));
        refuses(
                "AttributeError: readonly attribute",
                () -> Slots.call(set, new PyObject[] {version, PyInt.of(4)}, null));
    }

    /**
     * Calling the type is refused before its arguments are looked at.
     *
     * <p>An empty {@code tp_new} is a flag on the type, and {@code type_call} reads it on the way in: one
     * sentence, whatever arrived, and no complaint about count or keyword anywhere near it.
     */
    @Test
    void theTypeRefusesToMakeInstancesWhateverItIsHanded() {
        refuses(
                "TypeError: cannot create 'sys.version_info' instances",
                () -> kind.call(new PyObject[0], null));
        refuses(
                "TypeError: cannot create 'sys.version_info' instances",
                () -> kind.call(new PyObject[] {PyInt.of(1)}, null));
        refuses(
                "TypeError: cannot create 'sys.version_info' instances",
                () -> kind.call(new PyObject[0], PyBoundMethod.keywordTuple(new String[] {"major"})));
    }

    /**
     * The display names every item, and the type is named in full.
     *
     * <p>{@code str}, {@code repr} and an empty format all read the same text; a format that asks for a
     * type is refused by the generic {@code object.__format__}, whose complaint quotes {@code tp_name}
     * rather than the half {@code __name__} answers with — the rule every message in {@link Slots} follows.
     */
    @Test
    void theReprNamesEveryItemAndTheFormatThatAsksForATypeIsRefused() {
        assertThat(version.repr()).isEqualTo(
                "sys.version_info(major=" + version.at(0).repr()
                        + ", minor=" + version.at(1).repr()
                        + ", micro=" + version.at(2).repr()
                        + ", releaselevel=" + version.at(3).repr()
                        + ", serial=" + version.at(4).repr() + ")");
        assertThat(version.str()).isEqualTo(version.repr());
        assertThat(Slots.str(version)).isEqualTo(version.repr());
        assertThat(Formats.apply(version, "")).isEqualTo(version.repr());
        refuses(
                "TypeError: unsupported format string passed to sys.version_info.__format__",
                () -> Formats.apply(version, "x"));
    }

    /**
     * {@code __replace__} is the only route to a second value, and it judges names and nothing else.
     *
     * <p>No positional argument, no type check, and the names it does not file listed in the order the call
     * wrote them. The sentence it carries is the table's, so the descriptor and the method bound to a value
     * answer {@code __doc__} with the same text — the half of CPython's {@code ml_doc} this type declares;
     * {@code __reduce__} has none and answers {@code None}, and the unbound call that gives only a keyword
     * complains about the receiver in the qualified-name words.
     */
    @Test
    void replaceCopiesByNameAndCarriesItsOwnSentence() {
        PyObject swapped = replaced(new PyObject[] {PyList.of(new PyObject[0])}, "major");
        assertThat(Slots.getAttr(swapped, "major")).isInstanceOf(PyList.class);
        assertThat(swapped.repr()).startsWith("sys.version_info(major=[]");
        assertThat(swapped.getType()).isSameAs(kind);

        refuses(
                "TypeError: __replace__() takes no positional arguments",
                () -> replaced(new PyObject[] {PyInt.of(4)}));
        refuses(
                "TypeError: __replace__() takes no positional arguments",
                () -> replaced(new PyObject[] {version, version}, "micro"));
        refuses(
                "TypeError: Got unexpected field name(s): ['a', 'b']",
                () -> replaced(new PyObject[] {PyInt.of(1), PyInt.of(2)}, "a", "b"));

        // The same call made through the class: a keyword is not a receiver, and the unbound method says so.
        refuses(
                "TypeError: unbound method version_info.__replace__() needs an argument",
                () -> Slots.call(
                        filed("__replace__"),
                        new PyObject[] {PyInt.of(1)},
                        PyBoundMethod.keywordTuple(new String[] {"micro"})));

        String sentence =
                "Return a copy of the structure with new values for the specified fields.";
        assertThat(filed("__replace__").getAttr("__doc__").str()).isEqualTo(sentence);
        assertThat(Slots.getAttr(version, "__replace__").getAttr("__doc__").str())
                .isEqualTo(sentence);
        assertThat(filed("__reduce__").getAttr("__doc__")).isSameAs(PyNone.INSTANCE);
    }

    /**
     * The tuple underneath keeps working, and stays a tuple.
     *
     * <p>A named sequence is a {@code tuple} subclass and nothing more: a slice is a plain tuple, the
     * length is the tuple's, and the three counts and the match arguments are the dictionary's own values
     * rather than fields — which is why reading one needs no receiver.
     */
    @Test
    void theTupleUnderItKeepsItsOwnAnswers() {
        assertThat(Slots.length(version).str()).isEqualTo("5");
        assertThat(Slots.call(
                Slots.getAttr(version, "index"),
                new PyObject[] {PyStr.of(version.at(3).str())},
                null).str()).isEqualTo("3");
        assertThat(Slots.getItem(version, PyInt.of(-1))).isSameAs(version.at(4));
        assertThat(filed("n_fields").str()).isEqualTo("5");
        assertThat(filed("n_sequence_fields").str()).isEqualTo("5");
        assertThat(filed("n_unnamed_fields").str()).isEqualTo("0");
        assertThat(Slots.repr(filed("__match_args__")))
                .isEqualTo("('major', 'minor', 'micro', 'releaselevel', 'serial')");
        assertThat(Slots.getAttr(version, "__match_args__")).isSameAs(filed("__match_args__"));
        assertThat(Slots.getAttr(version, "__doc__").str()).isEqualTo(kind.getAttr("__doc__").str());
    }

    /** The five items the interpreter supplies, in the order the fields read them. */
    @Test
    void theRuntimeSuppliesTheItemsInTheFieldOrder() {
        PyObject[] items = PyRuntime.versionItems();
        assertThat(items).hasSize(FIELDS.length);
        for (int index = 0; index < FIELDS.length; index++) {
            assertThat(Slots.getAttr(version, FIELDS[index]).str()).isEqualTo(items[index].str());
        }
        assertThat(Slots.repr(version)).startsWith("sys.version_info(major=3, minor=14, micro=");
        assertThat(PyVersionInfo.current()).isSameAs(version);
    }
}
