package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two group dictionaries, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 85 and 86 read both tables from Python. What is asserted here is the machinery a script
 * reaches only by accident. The first is why one class of the family files eleven names and the other
 * three: {@code ComplexExtendsException} writes {@code tp_repr} beside {@code tp_str} for {@code
 * BaseExceptionGroup} and for no other class of the family, so {@code add_operators} files two {@code
 * wrapper_descriptor}s where its siblings file one; while {@code ExceptionGroup} is made by {@code
 * PyErr_NewException} rather than declared as a static struct, so its dictionary is what {@code type()}
 * leaves behind — a {@code __module__}, the weak-reference list a heap class gives itself, and no sentence,
 * because nothing handed {@code PyErr_NewExceptionWithDoc} one to write. The second is that the two fields
 * are read-only <em>and</em> data descriptors: {@code tp_descr_set} is written for every {@code
 * PyMemberDef}, so a write and a delete both say {@code readonly attribute}, neither reaches the instance
 * dictionary, and {@code __reduce__} — which tests the two pointers {@code self->args && self->dict} — keeps
 * answering two items until the dictionary is looked at. The third is the four refusals of {@code
 * Py_GenericAlias} called unbound out of the dictionary. The fourth is that a {@code wrapper_descriptor}
 * hands a class the very same row while a {@code classmethod_descriptor} builds a fresh binding on every
 * read.
 */
class ExceptionGroupDictionaryTest {

    /** {@code BaseExceptionGroup.__dict__}: the creator, the two sentences, the eight names, the doc. */
    private static final List<String> GROUP_ORDER =
            List.of(
                    "__new__",
                    "__repr__",
                    "__str__",
                    "__init__",
                    "__class_getitem__",
                    "derive",
                    "split",
                    "subgroup",
                    "message",
                    "exceptions",
                    "__doc__");

    /** The same eleven names without the sentence at the end, which is the one row a subclass files too. */
    private static final List<String> GROUP_ROWS =
            List.of(
                    "__new__",
                    "__repr__",
                    "__str__",
                    "__init__",
                    "__class_getitem__",
                    "derive",
                    "split",
                    "subgroup",
                    "message",
                    "exceptions");

    /** {@code ExceptionGroup.__dict__}: the three names every class made by {@code type()} keeps. */
    private static final List<String> NARROW_ORDER =
            List.of("__module__", "__weakref__", "__doc__");

    /** The two offsets of {@code BaseExceptionGroup_members}, the family's only read-only pointers. */
    private static final List<String> FIELDS = List.of("message", "exceptions");

    private static final String GETITEM_DOC =
            "Exception groups are generic over the type of their contained exceptions";

    private static final PyObject TEXT = PyStr.of("many");
    private static final PyObject RAW = PyStr.of("raw");

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** The kind one filed entry reads as: {@code wrapper_descriptor}, {@code member_descriptor}, … */
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

    /** {@code dir(type)}: every name a read of the class answers, filed or borrowed. */
    private static List<String> visible(PyType kind) {
        PyList names =
                (PyList)
                        Slots.call(
                                Builtins.namespace().get(PyStr.interned("dir")),
                                new PyObject[] {kind},
                                null);
        List<String> seen = new ArrayList<>();
        for (int at = 0; at < names.size(); at++) {
            seen.add(((PyStr) names.at(at)).getValue());
        }
        return seen;
    }

    /** {@code Kind("many", [children...])}, which is the only way either class is made. */
    private static PyObject group(PyType kind, PyObject... children) {
        return kind.call(new PyObject[] {TEXT, PyList.of(children)}, null);
    }

    /** A group that stays a {@code BaseExceptionGroup}, which needs one child that is not an exception. */
    private static PyObject rawGroup() {
        return ExceptionTypes.BASE_EXCEPTION_GROUP.call(
                new PyObject[] {
                    RAW, PyList.of(ExceptionTypes.KEYBOARD_INTERRUPT.call(new PyObject[0], null))
                },
                null);
    }

    /** {@code ValueError("message")}: a child plain enough for either class to hold. */
    private static PyObject child(String message) {
        return ExceptionTypes.VALUE_ERROR.call(new PyObject[] {PyStr.of(message)}, null);
    }

    /** {@code descriptor.__get__(receiver)}: the read half of a member row of the table. */
    private static PyObject getOf(PyType kind, String name, PyObject receiver) {
        return Slots.call(
                Slots.getAttr(entry(kind, name), "__get__"), new PyObject[] {receiver}, null);
    }

    /** {@code descriptor.__set__(receiver, value)}: the write half, reached without a read first. */
    private static void putOn(PyType kind, String name, PyObject receiver, PyObject value) {
        Slots.call(
                Slots.getAttr(entry(kind, name), "__set__"), new PyObject[] {receiver, value}, null);
    }

    /** {@code descriptor.__delete__(receiver)}: the third half, which a read-only pointer also refuses. */
    private static void takeOff(PyType kind, String name, PyObject receiver) {
        Slots.call(
                Slots.getAttr(entry(kind, name), "__delete__"), new PyObject[] {receiver}, null);
    }

    /** One filed member's own {@code __qualname__}, which is the row's name and not the value's. */
    private static String named(PyType kind, String name) {
        return ((PyStr) Slots.getAttr(entry(kind, name), "__qualname__")).getValue();
    }

    /** The sentence one filed member answers {@code __doc__} with; {@code null} where it answers none. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == PyNone.INSTANCE ? null : ((PyStr) text).getValue();
    }

    /** One of the two offsets, read as the row reads it rather than as a dictionary entry. */
    private static PyObject field(PyObject value, String name) {
        return Slots.getAttr(value, name);
    }

    /** {@code str(e)}: the text the class's own {@code tp_str} writes, or the family's. */
    private static String said(PyObject value) {
        return ((PyBaseException) value).str();
    }

    /** {@code repr(e)}: the text the class's own {@code tp_repr} writes, or the family's. */
    private static String shown(PyObject value) {
        return ((PyBaseException) value).repr();
    }

    /** {@code e.__dict__}, printed — which is also the read that gives the value a dictionary. */
    private static String dictOf(PyObject value) {
        return ((PyBaseException) value).held().repr();
    }

    /** {@code len(e.__reduce__())}: two while nothing has ever been written and the dictionary unasked. */
    private static int reduced(PyObject value) {
        return ((PyTuple)
                        Slots.call(Slots.getAttr(value, "__reduce__"), new PyObject[0], null))
                .size();
    }

    /** {@code type(name, (base,), {})}, the three-argument creator asked for a class. */
    private static PyType classOf(String name, PyType... bases) {
        return (PyType)
                Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of(name), PyTuple.of(bases), PyDict.empty()},
                        null);
    }

    /** {@code TypeError} and {@code AttributeError} in CPython's own words, which the runtime repeats. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The two tables, in the order {@code type_ready_fill_dict} built them.
     *
     * <p>Eleven names and thirty-nine for the class that owns the group, three and forty-one for the one
     * {@code PyErr_NewException} made: the narrower class files none of the eleven, so every one of them is
     * read out of the table above it — and {@code __module__}, which the base has no row for and still
     * answers, is the only name of the three that a static struct never gets.
     */
    @Test
    void oneClassFilesElevenNamesAndTheOtherOnlyWhatTypeLeavesBehind() {
        PyType base = ExceptionTypes.BASE_EXCEPTION_GROUP;
        PyType narrower = ExceptionTypes.EXCEPTION_GROUP;
        assertThat(filed(base)).isEqualTo(GROUP_ORDER);
        assertThat(TypeDict.declared(base).keys()).hasSize(11);
        assertThat(visible(base)).hasSize(39);
        assertThat(filed(narrower)).isEqualTo(NARROW_ORDER);
        assertThat(visible(narrower)).hasSize(41);

        assertThat(entry(narrower, "__module__")).isInstanceOf(PyStr.class);
        assertThat(((PyStr) entry(narrower, "__module__")).getValue()).isEqualTo("builtins");
        assertThat(entry(base, "__module__")).isNull();
        assertThat(((PyStr) Slots.getAttr(base, "__module__")).getValue()).isEqualTo("builtins");
        // A value reads the row itself; the class read answers the half of `tp_name` before the last dot,
        // because `type_module` consults the dictionary only for a heap type and this runtime declares the
        // class that CPython makes with `PyErr_NewException` as a static one.
        assertThat(Slots.getAttr(narrower, "__module__"))
                .isEqualTo(entry(narrower, "__module__"));
        assertThat(Slots.getAttr(group(narrower, child("a")), "__module__"))
                .isSameAs(entry(narrower, "__module__"));
        for (String name : List.of("__qualname__", "__bases__", "__name__", "__mro__")) {
            assertThat(entry(narrower, name)).as(name).isNull();
        }

        // A class written under either repeats none of the ten rows and borrows every one of them.
        PyType underBase = classOf("UnderBase", base);
        PyType underGroup = classOf("UnderGroup", narrower);
        for (PyType below : List.of(underBase, underGroup)) {
            assertThat(filed(below)).doesNotContainAnyElementsOf(GROUP_ROWS);
            assertThat(Slots.getAttr(below, "__str__")).isSameAs(entry(base, "__str__"));
            assertThat(Slots.getAttr(below, "message")).isSameAs(entry(base, "message"));
            assertThat(Slots.getAttr(below, "__new__")).isSameAs(entry(base, "__new__"));
        }
        // What either files is what `type()` hands a class of its own, less the row no caller from Java can
        // name for it: the `__module__` a class statement writes from the frame is missing, so the class
        // under the static base has no module row anywhere in its MRO and counts forty visible names.
        assertThat(filed(underBase)).isEqualTo(List.of("__weakref__", "__doc__"));
        assertThat(visible(underBase)).hasSize(40);
        // Under the class that files the three, both rows come from above instead — the weak-reference list
        // because `ExceptionGroup` already keeps one, `__module__` because it keeps that too — and the count
        // is back to the forty-one a Python class statement writes under either parent.
        assertThat(filed(underGroup)).isEqualTo(List.of("__doc__"));
        assertThat(visible(underGroup)).hasSize(41);
        // The parent's row shows in `dir()` and answers nothing else: `type_module` reads a heap class's
        // module from its own dictionary alone — no walk of the MRO — and a class built from Java files no
        // row because no frame named one, so the read re-derives the word instead of handing back the row.
        assertThat(Slots.getAttr(underGroup, "__module__")).isEqualTo(entry(narrower, "__module__"));
        assertThat(Slots.getAttr(underGroup, "__module__"))
                .isNotSameAs(entry(narrower, "__module__"));
    }

    /**
     * What the eleven names are filed as, and the two display rows the rest of the family does not have.
     *
     * <p>The creator is {@code BaseExceptionGroup_new} copied into the dictionary as a static method, so it
     * is a {@code builtin_function_or_method} already bound to its class and files no {@code __get__} at
     * all; {@code __repr__}, {@code __str__} and {@code __init__} are the three slot wrappers; the four
     * names of {@code BaseExceptionGroup_methods} divide into one {@code classmethod_descriptor} and three
     * {@code method_descriptor}s; the two fields are {@code member_descriptor}s. Nine of the eleven answer
     * {@code __objclass__} — the nine that are descriptors — and only {@code derive}, {@code split} and
     * {@code subgroup} answer no sentence at all.
     */
    @Test
    void twoDisplayRowsWhereTheFamilyFilesOneAndACreatorThatIsNotADescriptor() {
        PyType base = ExceptionTypes.BASE_EXCEPTION_GROUP;
        assertThat(kind(entry(base, "__new__"))).isEqualTo("builtin_function_or_method");
        assertThat(entry(base, "__new__")).isSameAs(TypeDict.GROUP_NEW);
        assertThat(Slots.getAttr(entry(base, "__new__"), "__self__")).isSameAs(base);
        assertThat(named(base, "__new__")).isEqualTo("BaseExceptionGroup.__new__");
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__get__'",
                () -> Slots.getAttr(entry(base, "__new__"), "__get__"));

        for (String name : List.of("__repr__", "__str__")) {
            assertThat(kind(entry(base, name))).as(name).isEqualTo("wrapper_descriptor");
            assertThat(entry(base, name).repr())
                    .as(name)
                    .isEqualTo("<slot wrapper '" + name + "' of 'BaseExceptionGroup' objects>");
            assertThat(carried(base, name))
                    .as(name)
                    .isEqualTo(name.equals("__repr__") ? "Return repr(self)." : "Return str(self).");
            assertThat(named(base, name)).as(name).isEqualTo("BaseExceptionGroup." + name);
            assertThat(Slots.getAttr(entry(base, name), "__objclass__"))
                    .as(name)
                    .isSameAs(base);
            assertThat(entry(base, name)).as(name)
                    .isNotSameAs(entry(ExceptionTypes.BASE_EXCEPTION, name));
        }
        assertThat(kind(entry(base, "__init__"))).isEqualTo("wrapper_descriptor");
        assertThat(carried(base, "__init__"))
                .isEqualTo("Initialize self.  See help(type(self)) for accurate signature.");
        for (String name : List.of("derive", "split", "subgroup")) {
            assertThat(kind(entry(base, name))).as(name).isEqualTo("method_descriptor");
            assertThat(carried(base, name)).as(name).isNull();
        }
        assertThat(entry(base, "__doc__")).isInstanceOf(PyStr.class);
        for (String name : List.of("__new__", "__doc__")) {
            assertThat(Slots.findAttr(entry(base, name), "__objclass__"))
                    .as(name)
                    .isNull();
        }

        // The narrower class writes neither display slot, so its read hands back the base's row whole.
        assertThat(entry(ExceptionTypes.EXCEPTION_GROUP, "__str__")).isNull();
        assertThat(Slots.getAttr(ExceptionTypes.EXCEPTION_GROUP, "__str__"))
                .isSameAs(entry(base, "__str__"));
        assertThat(Slots.getAttr(ExceptionTypes.EXCEPTION_GROUP, "__repr__"))
                .isSameAs(entry(base, "__repr__"));

        // Two rows, two texts, and the class of the value in only one of them: the sentence counts the
        // children and the representation asks each of them for itself.
        PyObject value = group(base, child("a"), child("b"));
        assertThat(said(value)).isEqualTo("many (2 sub-exceptions)");
        assertThat(shown(value))
                .isEqualTo("ExceptionGroup('many', [ValueError('a'), ValueError('b')])");
        assertThat(((PyStr) Slots.call(entry(base, "__str__"), new PyObject[] {value}, null))
                        .getValue())
                .isEqualTo("many (2 sub-exceptions)");
        assertThat(((PyStr) Slots.call(entry(base, "__repr__"), new PyObject[] {value}, null))
                        .getValue())
                .isEqualTo("ExceptionGroup('many', [ValueError('a'), ValueError('b')])");
        assertThat(said(group(base, child("a")))).isEqualTo("many (1 sub-exception)");
        assertThat(shown(group(base, child("a"))))
                .isEqualTo("ExceptionGroup('many', [ValueError('a')])");

        // The one value that stays a `BaseExceptionGroup` says the same sentence and shows its own name.
        PyObject held = rawGroup();
        assertThat(said(held)).isEqualTo("raw (1 sub-exception)");
        assertThat(shown(held)).isEqualTo("BaseExceptionGroup('raw', [KeyboardInterrupt()])");
    }

    /**
     * The two offsets, which refuse every write and every delete and still outrank the dictionary.
     *
     * <p>{@code BaseExceptionGroup_members} marks both {@code Py_READONLY}, and CPython still gives both a
     * {@code tp_descr_set}: the setter is the thing that says {@code readonly attribute}, and a delete is
     * that same call with nothing handed over. So both are data descriptors — a name put into the instance
     * dictionary by hand is not the name a read answers — and because the refusals never reach that
     * dictionary, {@code __reduce__} keeps answering two items until {@code __dict__} is asked for, which is
     * the one read that allocates it.
     */
    @Test
    void theTwoFieldsAreReadOnlyPointersThatAreStillDataDescriptors() {
        PyType base = ExceptionTypes.BASE_EXCEPTION_GROUP;
        for (String name : FIELDS) {
            assertThat(kind(entry(base, name))).as(name).isEqualTo("member_descriptor");
            assertThat(entry(base, name).repr())
                    .as(name)
                    .isEqualTo("<member '" + name + "' of 'BaseExceptionGroup' objects>");
            assertThat(Slots.getAttr(entry(base, name), "__set__")).as(name).isNotNull();
            assertThat(((PyMemberDescriptor) entry(base, name)).writes()).as(name).isTrue();
            assertThat(named(base, name)).as(name).isEqualTo("BaseExceptionGroup." + name);
            assertThat(Slots.getAttr(entry(base, name), "__objclass__"))
                    .as(name)
                    .isSameAs(base);
        }
        assertThat(carried(base, "message")).isEqualTo("exception message");
        assertThat(carried(base, "exceptions")).isEqualTo("nested exceptions");

        PyObject value = group(base, child("a"), child("b"));
        PyObject second = field(value, "exceptions");
        assertThat(field(value, "message")).isSameAs(TEXT);
        assertThat(second.repr()).isEqualTo("(ValueError('a'), ValueError('b'))");
        assertThat(field(value, "args").repr())
                .isEqualTo("('many', [ValueError('a'), ValueError('b')])");
        assertThat(getOf(base, "message", value)).isSameAs(TEXT);
        assertThat(getOf(base, "exceptions", value)).isSameAs(second);
        assertThat(((PyBaseExceptionGroup) value).groupMessage()).isSameAs(TEXT);
        assertThat(((PyBaseExceptionGroup) value).children()).isSameAs(second);

        for (String name : FIELDS) {
            refuses("AttributeError: readonly attribute", () -> Slots.setAttr(value, name, RAW));
            refuses("AttributeError: readonly attribute", () -> Slots.delAttr(value, name));
            refuses("AttributeError: readonly attribute", () -> putOn(base, name, value, RAW));
            refuses("AttributeError: readonly attribute", () -> takeOff(base, name, value));
        }
        assertThat(field(value, "message")).isSameAs(TEXT);
        assertThat(field(value, "exceptions")).isSameAs(second);
        assertThat(reduced(value)).isEqualTo(2);

        // The dictionary the refusals never wrote is still uncreated — and asking for it is what makes the
        // reduction report a third item.
        assertThat(dictOf(value)).isEqualTo("{}");
        assertThat(reduced(value)).isEqualTo(3);

        // A data descriptor outranks the dictionary it outranks.
        PyObject shadowed = group(base, child("a"));
        ((PyBaseException) shadowed).held().put(PyStr.interned("message"), RAW);
        assertThat(field(shadowed, "message")).isSameAs(TEXT);

        // The receiver is checked against the class that filed the row, in the words of that row.
        refuses(
                "TypeError: descriptor 'message' for 'BaseExceptionGroup' objects doesn't apply to a "
                        + "'ValueError' object",
                () -> getOf(base, "message", child("nope")));
        refuses(
                "TypeError: descriptor 'message' for 'BaseExceptionGroup' objects doesn't apply to a "
                        + "'BaseException' object",
                () ->
                        getOf(
                                base,
                                "message",
                                ExceptionTypes.BASE_EXCEPTION.call(
                                        new PyObject[] {TEXT}, null)));
        refuses(
                "TypeError: descriptor 'message' for 'BaseExceptionGroup' objects doesn't apply to a "
                        + "'type' object",
                () -> getOf(base, "message", base));

        // The class the row belongs to cannot be written either, in the words of the immutable type.
        refuses(
                "TypeError: cannot set 'message' attribute of immutable type 'BaseExceptionGroup'",
                () -> Slots.setAttr(base, "message", RAW));

        // A name that is not one of the two reaches the dictionary, which is what the class was always for.
        PyObject extra = group(base, child("a"));
        Slots.setAttr(extra, "extra", PyInt.of(1));
        assertThat(field(extra, "extra")).isEqualTo(PyInt.of(1));

        // A subclass reads the one row and files none of its own.
        PyType below = classOf("Named", base);
        assertThat(entry(below, "message")).isNull();
        assertThat(Specials.member(below, "message")).isSameAs(entry(base, "message"));
        assertThat(Specials.declared(below, "message")).isNull();
        assertThat(field(group(below, child("a")), "message")).isSameAs(TEXT);
    }

    /**
     * The subscript row, the only one of the eleven that binds the class instead of a value.
     *
     * <p>{@code Py_GenericAlias} is filed {@code METH_CLASS | METH_O}, so called out of the dictionary the
     * row wants its class in the first position: the three refusals that name the descriptor rather than the
     * group are {@code classmethoddescr_call} taking that class back, and the fourth is the wrapped {@code
     * METH_O} counting what is left. A read through the type supplies that class itself and builds a new
     * binding on every read, which is the opposite of what the three slot wrappers do. Nothing of the key is
     * checked, so {@code [1]} and {@code ["text"]} are aliases, a tuple key spreads and {@code [()]} keeps
     * none — and a list key is the one argument {@code ga_repr} writes item by item.
     */
    @Test
    void theSubscriptRowIsCalledUnboundWithAClassFirstAndChecksNothingAfter() {
        PyType base = ExceptionTypes.BASE_EXCEPTION_GROUP;
        PyObject row = entry(base, "__class_getitem__");
        assertThat(kind(row)).isEqualTo("classmethod_descriptor");
        assertThat(row.repr())
                .isEqualTo("<method '__class_getitem__' of 'BaseExceptionGroup' objects>");
        assertThat(carried(base, "__class_getitem__")).isEqualTo(GETITEM_DOC);
        assertThat(named(base, "__class_getitem__"))
                .isEqualTo("BaseExceptionGroup.__class_getitem__");
        assertThat(Slots.getAttr(row, "__objclass__")).isSameAs(base);
        refuses(
                "AttributeError: 'classmethod_descriptor' object has no attribute '__set__'",
                () -> Slots.getAttr(row, "__set__"));

        refuses(
                "TypeError: descriptor '__class_getitem__' of 'BaseExceptionGroup' object needs an "
                        + "argument",
                () -> Slots.call(row, new PyObject[0], null));
        refuses(
                "TypeError: descriptor '__class_getitem__' for type 'BaseExceptionGroup' needs a type, "
                        + "not a 'int' as arg 2",
                () -> Slots.call(row, new PyObject[] {PyInt.of(3)}, null));
        refuses(
                "TypeError: descriptor '__class_getitem__' requires a subtype of 'BaseExceptionGroup' "
                        + "but received 'ValueError'",
                () -> Slots.call(row, new PyObject[] {ExceptionTypes.VALUE_ERROR}, null));
        refuses(
                "TypeError: BaseExceptionGroup.__class_getitem__() takes exactly one argument (0 given)",
                () -> Slots.call(row, new PyObject[] {base}, null));
        assertThat(Slots.call(row, new PyObject[] {base, PyType.INT}, null).repr())
                .isEqualTo("BaseExceptionGroup[int]");

        PyObject bound = Slots.getAttr(base, "__class_getitem__");
        assertThat(kind(bound)).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(base, "__class_getitem__")).isNotSameAs(bound);
        assertThat(Slots.getAttr(ExceptionTypes.EXCEPTION_GROUP, "__class_getitem__"))
                .isNotSameAs(bound);
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(base);
        assertThat(Slots.getAttr(bound, "__qualname__"))
                .hasToString("'BaseExceptionGroup.__class_getitem__'");
        refuses(
                "TypeError: BaseExceptionGroup.__class_getitem__() takes exactly one argument (0 given)",
                () -> Slots.call(bound, new PyObject[0], null));
        refuses(
                "TypeError: BaseExceptionGroup.__class_getitem__() takes exactly one argument (2 given)",
                () -> Slots.call(bound, new PyObject[] {PyType.INT, PyType.STR}, null));
        refuses(
                "TypeError: BaseExceptionGroup.__class_getitem__() takes no keyword arguments",
                () ->
                        Slots.call(
                                bound,
                                new PyObject[] {PyType.INT},
                                PyBoundMethod.keywordTuple(new String[] {"item"})));

        assertThat(base.getItem(PyType.INT).repr()).isEqualTo("BaseExceptionGroup[int]");
        assertThat(base.getItem(PyTuple.of(PyType.INT, PyType.STR)).repr())
                .isEqualTo("BaseExceptionGroup[int, str]");
        assertThat(base.getItem(PyTuple.EMPTY).repr()).isEqualTo("BaseExceptionGroup[()]");
        assertThat(base.getItem(PyInt.of(1)).repr()).isEqualTo("BaseExceptionGroup[1]");
        assertThat(base.getItem(PyStr.of("text")).repr()).isEqualTo("BaseExceptionGroup['text']");
        assertThat(base.getItem(PyList.of(PyType.INT)).repr())
                .isEqualTo("BaseExceptionGroup[[int]]");
        assertThat(base.getItem(base.getItem(PyType.INT)).repr())
                .isEqualTo("BaseExceptionGroup[BaseExceptionGroup[int]]");

        PyObject alias = base.getItem(PyType.INT);
        assertThat(((PyGenericAlias) alias).getAttr("__origin__")).isSameAs(base);
        assertThat(base.getItem(PyTuple.of(PyType.INT, PyType.STR)).getAttr("__args__").repr())
                .isEqualTo("(<class 'int'>, <class 'str'>)");
        assertThat(alias.getAttr("__parameters__")).isSameAs(PyTuple.EMPTY);
        assertThat(alias.str()).isEqualTo("BaseExceptionGroup[int]");
        assertThat(base.getItem(PyType.INT)).isEqualTo(base.getItem(PyType.INT));
        assertThat(base.getItem(PyType.INT)).isNotEqualTo(base.getItem(PyType.STR));

        // The origin is the class the read ran on, so a subclass keeps the base's row and names itself.
        PyType below = classOf("Narrow", ExceptionTypes.EXCEPTION_GROUP);
        PyObject part = below.getItem(PyType.INT);
        assertThat(((PyGenericAlias) part).getAttr("__origin__")).isSameAs(below);
        assertThat(part.repr()).contains("Narrow[int]");
        assertThat(ExceptionTypes.EXCEPTION_GROUP.getItem(PyType.INT).repr())
                .isEqualTo("ExceptionGroup[int]");

        // A value of either class is not subscriptable, and the row itself cannot be replaced.
        refuses(
                "TypeError: 'ExceptionGroup' object is not subscriptable",
                () ->
                        Slots.getItem(
                                group(ExceptionTypes.EXCEPTION_GROUP, child("a")), PyType.INT));
        refuses(
                "TypeError: 'BaseExceptionGroup' object is not subscriptable",
                () -> Slots.getItem(rawGroup(), PyType.INT));
        refuses(
                "TypeError: cannot set '__class_getitem__' attribute of immutable type "
                        + "'BaseExceptionGroup'",
                () -> Slots.setAttr(base, "__class_getitem__", PyInt.of(3)));
    }

    /**
     * The three names {@code ExceptionGroup} files, which are the three {@code type()} leaves behind.
     *
     * <p>{@code PyErr_NewException} takes the half of the dotted name before the last dot, writes it into
     * the dictionary as {@code __module__}, and calls {@code type} with the two bases it was given — which
     * is why this class and no other of the family has a {@code __module__} row, and why the weak-reference
     * list is filed here rather than on the base that owns everything else. {@code
     * PyErr_NewExceptionWithDoc} writes the sentence only when handed one, so the row is {@code None}
     * outright, and the difference reaches the values: a group reads its module and a base-value has no such
     * attribute, because nothing ever filed one for its class.
     */
    @Test
    void theNarrowerClassFilesTheModuleAndTheListButNoSentence() {
        PyType base = ExceptionTypes.BASE_EXCEPTION_GROUP;
        PyType narrower = ExceptionTypes.EXCEPTION_GROUP;
        assertThat(((PyStr) entry(base, "__doc__")).getValue())
                .isEqualTo("A combination of multiple unrelated exceptions.");
        assertThat(entry(narrower, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(kind(entry(narrower, "__doc__"))).isEqualTo("NoneType");
        assertThat(entry(narrower, "__module__").getType()).isSameAs(PyType.STR);
        assertThat(Slots.getAttr(base, "__doc__"))
                .hasToString("'A combination of multiple unrelated exceptions.'");
        assertThat(Slots.getAttr(narrower, "__doc__")).isSameAs(PyNone.INSTANCE);

        assertThat(kind(entry(narrower, "__weakref__"))).isEqualTo("getset_descriptor");
        assertThat(entry(narrower, "__weakref__").repr())
                .isEqualTo("<attribute '__weakref__' of 'ExceptionGroup' objects>");
        assertThat(carried(narrower, "__weakref__"))
                .isEqualTo("list of weak references to the object");
        assertThat(entry(base, "__weakref__")).isNull();
        assertThat(Slots.getAttr(group(narrower, child("a")), "__weakref__"))
                .isSameAs(PyNone.INSTANCE);

        assertThat(((PyStr) Slots.getAttr(group(narrower, child("a")), "__module__")).getValue())
                .isEqualTo("builtins");
        refuses(
                "AttributeError: 'BaseExceptionGroup' object has no attribute '__module__'",
                () -> Slots.getAttr(rawGroup(), "__module__"));

        assertThat(Slots.getAttr(narrower, "__bases__").repr())
                .isEqualTo("(<class 'BaseExceptionGroup'>, <class 'Exception'>)");
        assertThat(Slots.getAttr(narrower, "__mro__").repr())
                .isEqualTo("(<class 'ExceptionGroup'>, <class 'BaseExceptionGroup'>, <class 'Exception'>, "
                        + "<class 'BaseException'>, <class 'object'>)");

        // The three rows are the whole of the dictionary, so everything else the family runs comes from the
        // class above — the same objects, not copies of them, except the one row that binds a class.
        for (String name : List.of("message", "exceptions", "derive", "split")) {
            assertThat(entry(narrower, name)).as(name).isNull();
            assertThat(Slots.getAttr(narrower, name)).as(name).isSameAs(entry(base, name));
        }
        assertThat(Slots.getAttr(narrower, "__class_getitem__")).isNotSameAs(entry(base, "__class_getitem__"));
        assertThat(Specials.member(narrower, "__init__")).isSameAs(TypeDict.GROUP_INIT);
        assertThat(Specials.declared(narrower, "__init__")).isNull();
    }
}
