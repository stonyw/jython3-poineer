package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The nine rows {@code super} files, and the one of them that rebuilds the proxy it is handed — measured
 * against CPython 3.14.10.
 *
 * <p>Corpus case 121 reads the book from Python and prints every refusal in the words the reference makes it.
 * What is pinned here is the store behind those reads: the order of the nine rows, the kinds they are filed
 * as, the text both knives read without cutting, and the fact that {@code __init__} assigns into the proxy it
 * was given rather than returning another one.
 *
 * <p>The four slot copies come first — {@code tp_repr}, {@code tp_getattro}, {@code tp_get}, {@code
 * tp_init} — each a {@code wrapper_descriptor}, then the three {@code PyMemberDef}s of the fields and the
 * sentence. The constructor is the odd kind: {@code super.__new__} is a {@code builtin_function_or_method}
 * because it is filed as a static method of the type, so it names no owner and hands back the proxy with
 * neither half filled, which prints {@code NULL} twice.
 *
 * <p>The two counts of the same three arguments are the split this slice exists to record. {@code super_new}
 * is handed the whole tuple and says {@code super() expected at most 2 arguments, got 3}; {@code super_init}
 * unpacks what it was given and says {@code super() takes at most 2 arguments (3 given)}. Both then name the
 * first argument's kind in one wording and the second argument's relation in another, and both refuse
 * keywords before either of them, because {@code super} has no keyword parameters at all.
 *
 * <p>One read of the book does not agree with the reference, and it is not a read of {@code super}: a
 * {@code member_descriptor} files no {@code __text_signature__} there and answers {@code None} here, and the
 * case masks that by asking with a default. A {@code __get__} asked of a proxy with no value crashes the
 * reference outright, so nothing here and nothing there asks it.
 */
class SuperBookTest {

    /** {@code super_methods} then {@code super_members} then the sentence, in the order the book keeps them. */
    private static final List<String> BOOK =
            List.of(
                    "__new__",
                    "__repr__",
                    "__getattribute__",
                    "__get__",
                    "__init__",
                    "__thisclass__",
                    "__self__",
                    "__self_class__",
                    "__doc__");

    /** The four names {@code super} adds to the twenty-four {@code object} already keeps. */
    private static final List<String> GAINED =
            List.of("__get__", "__self__", "__self_class__", "__thisclass__");

    /** The three fields a proxy reports, with the sentence each of them carries. */
    private static final List<String> MEMBERS = List.of("__thisclass__", "__self__", "__self_class__");

    private static final String CLASS_SENTENCE = "the class invoking super()";

    private static final String SELF_SENTENCE = "the instance invoking super(); may be None";

    private static final String ORDER_SENTENCE = "the type of the instance invoking super(); may be None";

    /** {@code super} is the one type whose {@code tp_doc} no knife cuts, so the whole text is its doc. */
    private static final String WHOLE_TEXT =
            "super() -> same as super(__class__, <first argument>)\n"
                    + "super(type) -> unbound super object\n"
                    + "super(type, obj) -> bound super object; requires isinstance(obj, type)\n"
                    + "super(type, type2) -> bound super object; requires\n"
                    + "    issubclass(type2, type)\n"
                    + "Typical use to call a cooperative superclass method:\n"
                    + "class C(B):\n"
                    + "    def meth(self, arg):\n"
                    + "        super().meth(arg)\n"
                    + "This works for class methods too:\n"
                    + "class C(B):\n"
                    + "    @classmethod\n"
                    + "    def cmeth(cls, arg):\n"
                    + "        super().cmeth(arg)\n";

    private static final String NEW_DOC =
            "Create and return a new object.  See help(type) for accurate signature.";

    private static final PyObject THREE = PyInt.of(3);

    private static final PyObject ONE = PyInt.of(1);

    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    /** {@code super.__dict__[name]}: the row the book keeps, or null where it keeps no such name. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.SUPER).get(PyStr.interned(name));
    }

    private static List<String> filed() {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(PyType.SUPER, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    private static List<String> gained() {
        List<String> names = new ArrayList<>(Dirs.classNames(PyType.SUPER));
        names.removeAll(Dirs.classNames(PyType.OBJECT));
        Collections.sort(names);
        return names;
    }

    private static PyObject callRow(String name, PyObject... args) {
        return Slots.call(entry(name), args, null);
    }

    /** {@code row(*receivers)}: the slot wrapper asked to bind, which is where its gates live. */
    private static PyObject bindRow(String name, PyObject... receivers) {
        return Slots.call(Slots.getAttr(entry(name), "__get__"), receivers, null);
    }

    /** {@code super(int, 3)}: a proxy with every field filled. */
    private static PySuper filled() {
        return PySuper.of(PyType.INT, THREE);
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
    void theBookKeepsNineRowsAndAddsFourNamesToObject() {
        assertThat(filed()).isEqualTo(BOOK);
        assertThat(Dirs.classNames(PyType.SUPER)).hasSize(28);
        assertThat(gained()).isEqualTo(GAINED);
        assertThat(PyType.SUPER.bases()).containsExactly(PyType.OBJECT);
        assertThat(entry("__bool__")).isNull();
        assertThat(entry("__hash__")).isNull();
    }

    @Test
    void oneTextIsReadByBothKnivesAndCutByNeither() {
        assertThat(WHOLE_TEXT).hasSize(471);
        assertThat(PyType.SUPER.docText()).isEqualTo(WHOLE_TEXT);
        assertThat(Slots.getAttr(PyType.SUPER, "__doc__")).isEqualTo(PyStr.of(WHOLE_TEXT));
        // `find_signature` wants the tp_name straight followed by a bracket, and this text follows it with
        // a space, so neither half of the weld is ever cut away and there is no table to hand back.
        assertThat(Slots.getAttr(PyType.SUPER, "__text_signature__")).isSameAs(PyNone.INSTANCE);
        // The row is a fresh string built from the same text on every read, never the read before it.
        assertThat(entry("__doc__")).isEqualTo(Slots.getAttr(PyType.SUPER, "__doc__"));
        assertThat(entry("__doc__")).isNotSameAs(Slots.getAttr(PyType.SUPER, "__doc__"));
        assertThat(entry("__doc__").getType().name()).isEqualTo("str");
    }

    @Test
    void theFourSlotCopiesAreWrappersAndTheThreeFieldsAreMembers() {
        assertThat(entry("__new__").getType().name()).isEqualTo("builtin_function_or_method");
        for (String row : List.of("__repr__", "__getattribute__", "__get__", "__init__")) {
            assertThat(entry(row).getType().name()).isEqualTo("wrapper_descriptor");
            assertThat(Slots.getAttr(entry(row), "__objclass__")).isSameAs(PyType.SUPER);
            assertThat(Slots.getAttr(entry(row), "__qualname__"))
                    .isEqualTo(PyStr.of("super." + row));
        }
        for (String member : MEMBERS) {
            assertThat(entry(member).getType().name()).isEqualTo("member_descriptor");
            assertThat(Slots.getAttr(entry(member), "__objclass__")).isSameAs(PyType.SUPER);
            assertThat(Slots.getAttr(entry(member), "__qualname__")).isEqualTo(PyStr.of("super." + member));
        }
        assertThat(Slots.getAttr(entry("__new__"), "__doc__")).isEqualTo(PyStr.of(NEW_DOC));
        assertThat(Slots.getAttr(entry("__new__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($type, *args, **kwargs)"));
        // A builtin function files no owner at all, so the question has no answer rather than an empty one.
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__objclass__'",
                () -> Slots.getAttr(entry("__new__"), "__objclass__"));
    }

    @Test
    void eachRowCarriesTheSentenceItsSlotWrites() {
        assertThat(Slots.getAttr(entry("__repr__"), "__doc__"))
                .isEqualTo(PyStr.of("Return repr(self)."));
        assertThat(Slots.getAttr(entry("__getattribute__"), "__doc__"))
                .isEqualTo(PyStr.of("Return getattr(self, name)."));
        assertThat(Slots.getAttr(entry("__get__"), "__doc__"))
                .isEqualTo(PyStr.of("Return an attribute of instance, which is of type owner."));
        assertThat(Slots.getAttr(entry("__init__"), "__doc__"))
                .isEqualTo(PyStr.of("Initialize self.  See help(type(self)) for accurate signature."));
        assertThat(Slots.getAttr(entry("__repr__"), "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(entry("__getattribute__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, name, /)"));
        assertThat(Slots.getAttr(entry("__get__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, instance, owner=None, /)"));
        assertThat(Slots.getAttr(entry("__init__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /, *args, **kwargs)"));
        assertThat(Slots.getAttr(entry("__thisclass__"), "__doc__")).isEqualTo(PyStr.of(CLASS_SENTENCE));
        assertThat(Slots.getAttr(entry("__self__"), "__doc__")).isEqualTo(PyStr.of(SELF_SENTENCE));
        assertThat(Slots.getAttr(entry("__self_class__"), "__doc__")).isEqualTo(PyStr.of(ORDER_SENTENCE));
    }

    @Test
    void theMembersReportTheThreeFieldsOfTheProxy() {
        PySuper proxy = filled();

        assertThat(Slots.getAttr(proxy, "__self__")).isSameAs(THREE);
        assertThat(Slots.getAttr(proxy, "__self_class__")).isSameAs(PyType.INT);
        assertThat(Slots.getAttr(proxy, "__thisclass__")).isSameAs(PyType.INT);
        assertThat(proxy.repr()).isEqualTo("<super: <class 'int'>, <int object>>");
        // A member row reads a field of a receiver and answers no call of its own.
        for (String member : MEMBERS) {
            refuses(
                    "TypeError: 'member_descriptor' object is not callable",
                    () -> callRow(member, proxy));
        }
    }

    @Test
    void aMemberReadFromAProxyRefusesBothWaysItCanAsked() {
        PySuper proxy = filled();

        for (String member : MEMBERS) {
            refuses(
                    "AttributeError: readonly attribute",
                    () -> Slots.setAttr(proxy, member, THREE));
            refuses(
                    "AttributeError: readonly attribute",
                    () -> Slots.delAttr(proxy, member));
        }
        // The sentence is a getset of the type, not a member, so it names the attribute it refuses.
        refuses(
                "AttributeError: 'super' object attribute '__doc__' is read-only",
                () -> Slots.setAttr(proxy, "__doc__", PyStr.of("x")));
        refuses(
                "AttributeError: 'super' object has no attribute 'volume'"
                        + " and no __dict__ for setting new attributes",
                () -> Slots.setAttr(proxy, "volume", ONE));
        refuses(
                "AttributeError: 'super' object has no attribute 'volume'"
                        + " and no __dict__ for setting new attributes",
                () -> Slots.delAttr(proxy, "volume"));
    }

    @Test
    void aNameOfTheTypeIsRefusedByTheImmutableTypeSentence() {
        for (String member : MEMBERS) {
            refuses(
                    "TypeError: cannot set '" + member + "' attribute of immutable type 'super'",
                    () -> Slots.setAttr(PyType.SUPER, member, THREE));
            refuses(
                    "TypeError: cannot set '" + member + "' attribute of immutable type 'super'",
                    () -> Slots.delAttr(PyType.SUPER, member));
        }
        refuses(
                "TypeError: cannot set '__doc__' attribute of immutable type 'super'",
                () -> Slots.setAttr(PyType.SUPER, "__doc__", PyStr.of("x")));
        refuses(
                "TypeError: cannot set 'volume' attribute of immutable type 'super'",
                () -> Slots.setAttr(PyType.SUPER, "volume", ONE));
        refuses(
                "TypeError: 'mappingproxy' object does not support item assignment",
                () -> Slots.setItem(Slots.getAttr(PyType.SUPER, "__dict__"), PyStr.of("__doc__"), ONE));
    }

    /**
     * {@code super_init} assigns into the object it was handed and returns {@code None}, so the proxy a
     * person held is the one that changed and nothing new is ever built.
     */
    @Test
    void theInitRowFillsTheProxyItWasHanded() {
        PySuper holder = PySuper.withoutValue(PyType.INT);
        assertThat(holder.repr()).isEqualTo("<super: <class 'int'>, NULL>");

        assertThat(callRow("__init__", holder, PyType.INT, THREE)).isSameAs(PyNone.INSTANCE);
        assertThat(holder.repr()).isEqualTo("<super: <class 'int'>, <int object>>");
        assertThat(Slots.getAttr(holder, "__self__")).isSameAs(THREE);

        // One argument is the class form of the valueless proxy, and the value it was given is discarded.
        assertThat(callRow("__init__", holder, PyType.STR)).isSameAs(PyNone.INSTANCE);
        assertThat(holder.repr()).isEqualTo("<super: <class 'str'>, NULL>");
        assertThat(Slots.getAttr(holder, "__self__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(holder, "__thisclass__")).isSameAs(PyType.STR);
    }

    @Test
    void theInitRowRefusesAndLeavesEveryFieldWhereItWas() {
        PySuper holder = PySuper.withoutValue(PyType.STR);

        refuses(
                "TypeError: super() takes at most 2 arguments (3 given)",
                () -> callRow("__init__", holder, PyType.INT, THREE, ONE));
        refuses(
                "TypeError: super() takes no keyword arguments",
                () -> Slots.call(entry("__init__"), new PyObject[] {holder, PyType.INT}, X));
        refuses(
                "TypeError: super() argument 1 must be type, not int",
                () -> callRow("__init__", holder, ONE));
        refuses(
                "TypeError: super(type, obj): obj (instance of str) is not an instance or subtype"
                        + " of type (int).",
                () -> callRow("__init__", holder, PyType.INT, PyStr.of("x")));
        // The frame is the third way to a class, and a JUnit call has no Python frame to ask.
        refuses(
                "RuntimeError: super(): no arguments", () -> callRow("__init__", holder));

        assertThat(holder.repr()).isEqualTo("<super: <class 'str'>, NULL>");
        assertThat(Slots.getAttr(holder, "__thisclass__")).isSameAs(PyType.STR);
        assertThat(Slots.getAttr(holder, "__self__")).isSameAs(PyNone.INSTANCE);
    }

    @Test
    void theCreatorRowAnswersForNeitherHalf() {
        assertThat(Slots.call(entry("__new__"), new PyObject[] {PyType.SUPER}, null).repr())
                .isEqualTo("<super: <class 'NULL'>, NULL>");
        // The arguments it was not given a use for are never gate-checked, so the count cannot refuse them.
        assertThat(
                        Slots.call(
                                        entry("__new__"),
                                        new PyObject[] {PyType.SUPER, PyType.INT, THREE},
                                        null)
                                .repr())
                .isEqualTo("<super: <class 'NULL'>, NULL>");
        refuses(
                "TypeError: super.__new__(): not enough arguments",
                () -> Slots.call(entry("__new__"), new PyObject[0], null));
        refuses(
                "TypeError: super.__new__(int): int is not a subtype of super",
                () -> Slots.call(entry("__new__"), new PyObject[] {PyType.INT}, null));

        PySuper empty = PySuper.empty();
        assertThat(Slots.getAttr(empty, "__thisclass__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(empty, "__self__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(empty, "__self_class__")).isSameAs(PyNone.INSTANCE);
        assertThat(empty.repr()).isEqualTo("<super: <class 'NULL'>, NULL>");
    }

    @Test
    void theRowsWithoutAProxyRefuseInWhicheverOfTheTwoWaysTheyCan() {
        refuses(
                "TypeError: descriptor '__repr__' of 'super' object needs an argument",
                () -> callRow("__repr__"));
        refuses(
                "TypeError: descriptor '__get__' of 'super' object needs an argument",
                () -> callRow("__get__"));
        refuses(
                "TypeError: descriptor '__repr__' requires a 'super' object but received a 'type'",
                () -> callRow("__repr__", PyType.INT, THREE));
        refuses(
                "TypeError: descriptor '__getattribute__' requires a 'super' object but received a 'type'",
                () -> callRow("__getattribute__", PyType.INT));
        refuses(
                "TypeError: descriptor '__init__' requires a 'super' object but received a 'type'",
                () -> callRow("__init__", PyType.INT, THREE));
        // With a proxy as the receiver the same rows answer, and the field row answers for it.
        PySuper proxy = filled();
        assertThat(callRow("__repr__", proxy)).isEqualTo(PyStr.of("<super: <class 'int'>, <int object>>"));
        refuses(
                "AttributeError: 'super' object has no attribute 'real'",
                () -> callRow("__getattribute__", proxy, PyStr.of("real")));
    }

    @Test
    void theBindingRowRunsItsGatesInTheOrderTheReferenceWritesThem() {
        PySuper proxy = filled();

        refuses(
                "TypeError: __get__ expected at least 1 argument, got 0",
                () -> callRow("__get__", proxy));
        refuses(
                "TypeError: __get__ expected at most 2 arguments, got 3",
                () ->
                        Slots.call(
                                bindRow("__get__", proxy, PyType.SUPER),
                                new PyObject[] {ONE, THREE, PyType.INT},
                                null));
        refuses(
                "TypeError: __get__(None, None) is invalid",
                () -> callRow("__get__", proxy, PyNone.INSTANCE, PyNone.INSTANCE));
        refuses(
                "TypeError: wrapper __get__() takes no keyword arguments",
                () -> Slots.call(entry("__get__"), new PyObject[] {proxy, ONE}, X));
        // A binding with an owner but no instance hands back the proxy itself, and binding it names the proxy.
        assertThat(callRow("__get__", proxy, ONE)).isSameAs(proxy);
        assertThat(callRow("__get__", proxy, PyNone.INSTANCE, PyType.INT)).isSameAs(proxy);
        assertThat(bindRow("__get__", proxy, PyType.SUPER).getType().name()).isEqualTo("method-wrapper");
    }

    @Test
    void theBookSitsBehindTheWalkAndAnswersWhatTheWalkFindsNoNameFor() {
        PySuper proxy = filled();
        PySuper valueless = PySuper.withoutValue(PyType.INT);

        // The walk reaches `object` from `int`, so a proxy with a value reads `object`'s sentence.
        assertThat(Slots.getAttr(proxy, "__doc__")).isEqualTo(Slots.getAttr(PyType.OBJECT, "__doc__"));
        assertThat(((PyStr) Slots.getAttr(proxy, "__doc__")).getValue()).hasSize(173);
        assertThat(((PyStr) Slots.getAttr(valueless, "__doc__")).getValue()).hasSize(471);
        // The walk's row binds the value and the book's row binds the proxy, for the same name.
        assertThat(Slots.getAttr(proxy, "__reduce__").getAttr("__self__")).isSameAs(THREE);
        assertThat(bindRow("__repr__", proxy, PyType.SUPER).getAttr("__self__")).isSameAs(proxy);
        refuses(
                "AttributeError: 'super' object has no attribute 'real'",
                () -> Slots.getAttr(valueless, "real"));
        refuses(
                "AttributeError: 'super' object has no attribute 'kind'",
                () -> Slots.getAttr(proxy, "kind"));
    }

    /** {@code super}'s own rows are reachable as the type's attributes, unbound and by name. */
    @Test
    void theRowsReadOffTheTypeAreTheRowsTheBookKeeps() {
        for (String row : BOOK) {
            if (row.equals("__doc__")) {
                continue;
            }
            assertThat(Slots.getAttr(PyType.SUPER, row)).isSameAs(entry(row));
        }
        assertThat(Slots.getAttr(PyType.SUPER, "__doc__")).isEqualTo(entry("__doc__"));
    }
}
