package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two dictionaries {@code funcobject.c} files for the wrapper objects, measured against CPython 3.14.8.
 *
 * <p>The corpus script reads both tables from Python and calls every row of them. What is pinned here is the
 * machinery those reads run through. The first piece is the order: {@code type_ready_fill_dict} walks
 * {@code add_operators} before the three lists of the type's own, so the five slot rows — {@code tp_new},
 * {@code tp_repr}, {@code tp_call}, {@code tp_descr_get}, {@code tp_init} — are filed first, and
 * {@code __class_getitem__}, the two {@code Py_READONLY} members, the four getsets and the sentence follow in
 * that order. The second is that one of those slots is filled twice over and once not at all: {@code tp_call}
 * is {@code staticmethod}'s thirteenth name and {@code classmethod} fills no {@code tp_call}, which is the only
 * difference between thirteen and twelve.
 *
 * <p>The third piece is {@code functools_wraps}: {@code sm_init} sets the field and then copies four names off
 * the callable, each with {@code PyObject_GetOptionalAttr}, so a callable that answers nothing under three of
 * them leaves three names out of the dictionary — and each copy is made with {@code PyObject_SetAttr}, so the
 * four are the wrapper's own from then on. {@code PyStaticMethod_New}, the third way a wrapper is made and the
 * one a {@code METH_STATIC} row of a built-in's table uses, copies nothing.
 *
 * <p>The fourth is the three reading rules that meet in one dictionary: a member that writes outranks the
 * store, the store outranks a member that only reads, and a name the type files as a plain value is shadowed by
 * a copy of the same name. {@code descriptor_get_wrapped_attribute} adds a fourth rule of its own for
 * {@code __annotations__} and {@code __annotate__}: the store first, then the callable, and the answer cached
 * into the store. {@code PyObject_GenericGetDict} is the reason the store is not there until it is asked for,
 * and {@code _PyObject_IsAbstract} reads the name off the callable like any other attribute rather than looking
 * the callable's type up.
 */
class WrapperDictionaryTest {

    /** {@code staticmethod}: five slot rows, the alias, the two members, the four getsets and the sentence. */
    private static final List<String> STATIC_ORDER =
            List.of(
                    "__new__",
                    "__repr__",
                    "__call__",
                    "__get__",
                    "__init__",
                    "__class_getitem__",
                    "__func__",
                    "__wrapped__",
                    "__isabstractmethod__",
                    "__dict__",
                    "__annotations__",
                    "__annotate__",
                    "__doc__");

    /** {@code classmethod}: the same twelve of those thirteen that a missing {@code tp_call} leaves. */
    private static final List<String> CLASS_ORDER =
            List.of(
                    "__new__",
                    "__repr__",
                    "__get__",
                    "__init__",
                    "__class_getitem__",
                    "__func__",
                    "__wrapped__",
                    "__isabstractmethod__",
                    "__dict__",
                    "__annotations__",
                    "__annotate__",
                    "__doc__");

    /** {@code functools_wraps}' list, in the order {@code sm_init} runs it. */
    private static final List<String> COPIED =
            List.of("__module__", "__name__", "__qualname__", "__doc__");

    private static final PyObject NUMBER = PyInt.of(5);
    private static final PyObject TEXT = PyStr.of("text");

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading the name. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** The kind one filed entry reads as: {@code wrapper_descriptor}, {@code member_descriptor}, … */
    private static String kind(PyObject member) {
        return member.getType().name();
    }

    /** The names a kind's own dictionary keeps, in the order it keeps them. */
    private static List<String> filed(PyType owner) {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(owner, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** The names one value's own store keeps, in the order they were put there. */
    private static List<String> kept(PyObject value) {
        PyDict store = (PyDict) Slots.getAttr(value, "__dict__");
        List<String> names = new ArrayList<>();
        for (PyObject key : store.getItems().keySet()) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** The sentence a value answers for a name, or {@code null} where it answers {@code None}. */
    private static String text(PyObject value, String name) {
        PyObject read = Slots.getAttr(value, name);
        return read == PyNone.INSTANCE ? null : ((PyStr) read).getValue();
    }

    /** {@code type(name, (base,), {members})}: a class Python wrote, with two members at a step. */
    private static PyType type(String name, PyType base, Object... members) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < members.length; at += 2) {
            dict.put(PyStr.interned((String) members[at]), (PyObject) members[at + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(base), dict}, null);
    }

    /** A callable with all four of the names {@code functools_wraps} copies, and one with only a sentence. */
    private static PyType saying() {
        return type(
                "Saying",
                PyType.OBJECT,
                "__module__",
                PyStr.of("written"),
                "__doc__",
                PyStr.of("A sentence."));
    }

    /** {@code kind.__dict__[name](...args)}: a row of the dictionary, called with what the test names. */
    private static PyObject row(PyType owner, String name, PyObject... args) {
        return Slots.call(entry(owner, name), args, null);
    }

    /** {@code getattr(value, name)(...args)}: a row read through a value, which binds it first. */
    private static PyObject through(PyObject value, String name, PyObject... args) {
        return Slots.call(Slots.getAttr(value, name), args, null);
    }

    /** The wrapper of a function that hands back its first argument. */
    private static PyObject echoStatic() {
        return new PyStaticMethod(new PyNativeFunction("echo", (args, kwNames) -> args[0]));
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
     * The two tables, in the order the four C lists were walked.
     *
     * <p>Thirteen and twelve, and the one row between them is {@code tp_call}: {@code PyClassMethod_Type}
     * fills no {@code tp_call}, so {@code classmethod} files twelve rows where its neighbour files five slots.
     * Every one of the six rows that a slot leaves behind — {@code tp_new} as a plain {@code METH_STATIC}
     * entry, {@code tp_repr}, {@code tp_call}, {@code tp_descr_get} and {@code tp_init} as {@code
     * wrapper_descriptor}s, and {@code tp_methods}' one {@code METH_CLASS} row — is filed before the two {@code
     * PyMemberDef} entries and the four {@code PyGetSetDef} ones, and the sentence last of all, because {@code
     * type_dict_set_doc} runs after the three lists.
     */
    @Test
    void fiveSlotRowsOneAliasTwoMembersFourGetsetsAndTheSentence() {
        assertThat(filed(PyType.STATIC_METHOD)).isEqualTo(STATIC_ORDER);
        assertThat(filed(PyType.CLASS_METHOD)).isEqualTo(CLASS_ORDER);
        assertThat(filed(PyType.STATIC_METHOD)).hasSize(13);
        assertThat(filed(PyType.CLASS_METHOD)).hasSize(12);

        for (PyType owner : List.of(PyType.STATIC_METHOD, PyType.CLASS_METHOD)) {
            assertThat(kind(entry(owner, "__new__"))).isEqualTo("builtin_function_or_method");
            assertThat(kind(entry(owner, "__repr__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__get__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__init__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__class_getitem__")))
                    .isEqualTo("classmethod_descriptor");
            for (String name : List.of("__func__", "__wrapped__")) {
                assertThat(kind(entry(owner, name))).isEqualTo("member_descriptor");
            }
            for (String name :
                    List.of("__isabstractmethod__", "__dict__", "__annotations__", "__annotate__")) {
                assertThat(kind(entry(owner, name))).isEqualTo("getset_descriptor");
            }
            // A {@code NULL} {@code ml_doc} and an empty {@code PyGetSetDef} sentence both read as nothing.
            for (String name :
                    List.of("__class_getitem__", "__func__", "__wrapped__", "__dict__")) {
                assertThat(Slots.getAttr(entry(owner, name), "__doc__"))
                        .as(name)
                        .isSameAs(PyNone.INSTANCE);
            }
            // The row under the last name is not a descriptor at all: a static type's sentence is a string.
            assertThat(kind(entry(owner, "__doc__"))).isEqualTo("str");
            assertThat(text(owner, "__qualname__")).isEqualTo(owner.name());
        }
        assertThat(kind(entry(PyType.STATIC_METHOD, "__call__"))).isEqualTo("wrapper_descriptor");
        assertThat(entry(PyType.CLASS_METHOD, "__call__")).isNull();
        // Reading the name a kind does not file is a complaint even though `object` runs a `tp_call`.
        refuses(
                "AttributeError: 'classmethod' object has no attribute '__call__'",
                () -> Slots.getAttr(new PyClassMethod(), "__call__"));
    }

    /**
     * {@code sm_call} and the absence of {@code cm_call}.
     *
     * <p>The {@code __call__} row is {@code tp_call}, so a wrapper called as a function hands its arguments to
     * the callable with nothing prepended — and the same row read out of the dictionary and handed the wrapper
     * as its receiver is the same call, because {@code wrap_pyobjfunc} takes the receiver out first. {@code
     * classmethod} fills no such slot: its values are only ever reached through a class, where {@code
     * cm_descr_get} has already put the class in front.
     */
    @Test
    void oneKindCallsThroughTheFieldAndTheOtherIsNotCallableAtAll() {
        PyObject echo = new PyNativeFunction("echo", (args, kwNames) -> args[0]);
        PyObject asStatic = new PyStaticMethod(echo);
        PyObject asClass = new PyClassMethod(echo);

        assertThat(Slots.call(asStatic, new PyObject[] {NUMBER}, null)).isSameAs(NUMBER);
        assertThat(row(PyType.STATIC_METHOD, "__call__", asStatic, TEXT)).isSameAs(TEXT);
        // The keywords come through with the tail, which is what `PyBoundMethod.keywordTuple` is for.
        PyObject named = new PyNativeFunction("named", (args, kwNames) -> PyStr.of(kwNames[0]));
        assertThat(
                        Slots.call(
                                new PyStaticMethod(named),
                                new PyObject[0],
                                PyTuple.of(PyStr.interned("x"))))
                .isEqualTo(PyStr.of("x"));

        refuses(
                "TypeError: 'classmethod' object is not callable",
                () -> Slots.call(asClass, new PyObject[] {NUMBER}, null));
        // The wrapper around nothing complains about the field rather than about itself.
        refuses(
                "TypeError: 'NoneType' object is not callable",
                () -> Slots.call(new PyStaticMethod(), new PyObject[0], null));
    }

    /**
     * {@code functools_wraps} over the four names, and the two ways to make a wrapper without it.
     *
     * <p>Each copy is {@code PyObject_GetOptionalAttr} and {@code PyObject_SetAttr}, so the four are the
     * wrapper's own — renaming what was wrapped leaves the copy where it was — and so a callable that answers
     * only {@code __doc__} puts only that one name in the store. {@code 5} is such a callable: a value answers
     * the sentence its class files and none of the three names a function has. {@code PyStaticMethod_New} is
     * the third way and copies nothing, which is what a {@code METH_STATIC} row of a built-in's method table is
     * built with.
     */
    @Test
    void fourNamesCopiedAtTheInitAndNotBorrowedFromTheCallable() {
        PyType saying = saying();
        PyObject aroundClass = new PyClassMethod(saying);
        assertThat(kept(aroundClass)).isEqualTo(COPIED);
        assertThat(text(aroundClass, "__module__")).isEqualTo("written");
        assertThat(text(aroundClass, "__name__")).isEqualTo("Saying");
        assertThat(text(aroundClass, "__qualname__")).isEqualTo("Saying");
        assertThat(text(aroundClass, "__doc__")).isEqualTo("A sentence.");
        Slots.setAttr(saying, "__name__", PyStr.of("Renamed"));
        assertThat(text(saying, "__name__")).isEqualTo("Renamed");
        assertThat(text(aroundClass, "__name__")).isEqualTo("Saying");
        // A name the kind files as a plain value is shadowed by the copy: the wrapper reads what it kept
        // rather than the 637 characters of its own kind's sentence.
        assertThat(Slots.getAttr(aroundClass, "__doc__")).isEqualTo(PyStr.of("A sentence."));

        PyObject aroundInt = new PyStaticMethod(NUMBER);
        assertThat(kept(aroundInt)).containsExactly("__doc__");
        assertThat(Slots.getAttr(aroundInt, "__doc__"))
                .isEqualTo(Slots.getAttr(PyType.INT, "__doc__"));
        refuses(
                "AttributeError: 'staticmethod' object has no attribute '__name__'",
                () -> Slots.getAttr(aroundInt, "__name__"));

        PyObject fromTable = PyStaticMethod.filed(saying);
        assertThat(kept(fromTable)).isEmpty();
        assertThat(Slots.getAttr(fromTable, "__func__")).isSameAs(saying);
        assertThat(((PyStr) row(PyType.STATIC_METHOD, "__repr__", fromTable)).getValue())
                .isEqualTo("<staticmethod(" + saying.repr() + ")>");
        assertThat(new PyClassMethod(NUMBER).repr()).isEqualTo("<classmethod(5)>");
        assertThat(new PyStaticMethod().repr()).isEqualTo("<staticmethod(None)>");
    }

    /**
     * The two {@code _Py_T_OBJECT Py_READONLY} members over the one {@code wm_callable} field.
     *
     * <p>Both rows read the same field, and {@code PyMember_GetOne} hands out the same object each time; the
     * rows themselves are two objects that do not compare equal, because {@code member_descriptor} inherits
     * {@code object}'s identity comparison. A member is a data descriptor even when its writer only refuses, so
     * the two names are read from the field even when the store holds something under them — which is the half
     * of {@code PyObject_GenericGetAttr} that a copied {@code __doc__} does not answer.
     */
    @Test
    void oneFieldReadByTwoRowsThatAreNotTheSameRow() {
        PyObject echo = new PyNativeFunction("echo", (args, kwNames) -> args[0]);
        PyObject value = new PyStaticMethod(echo);
        PyObject func = entry(PyType.STATIC_METHOD, "__func__");
        PyObject wrapped = entry(PyType.STATIC_METHOD, "__wrapped__");

        assertThat(func).isNotSameAs(wrapped);
        assertThat(func.eq(wrapped)).isFalse();
        assertThat(func.repr()).isEqualTo("<member '__func__' of 'staticmethod' objects>");
        assertThat(entry(PyType.CLASS_METHOD, "__wrapped__").repr())
                .isEqualTo("<member '__wrapped__' of 'classmethod' objects>");
        assertThat(Slots.getAttr(value, "__func__")).isSameAs(echo);
        assertThat(Slots.getAttr(value, "__func__")).isSameAs(Slots.getAttr(value, "__wrapped__"));

        // {@code Py_READONLY}: one sentence for a write and one for a delete, naming neither field nor kind.
        for (String name : List.of("__func__", "__wrapped__")) {
            refuses("AttributeError: readonly attribute", () -> Slots.setAttr(value, name, TEXT));
            refuses("AttributeError: readonly attribute", () -> Slots.delAttr(value, name));
        }
        // The store cannot take either name away from the field, because a member writes however little it
        // writes with — and the dictionary is where the four copied names live, so `__doc__` reads the copy.
        PyDict shadow = PyDict.empty();
        shadow.put(PyStr.interned("__func__"), TEXT);
        shadow.put(PyStr.interned("__doc__"), TEXT);
        Slots.setAttr(value, "__dict__", shadow);
        assertThat(Slots.getAttr(value, "__func__")).isSameAs(echo);
        assertThat(Slots.getAttr(value, "__doc__")).isSameAs(TEXT);
    }

    /**
     * {@code PyObject_GenericGetDict} and {@code PyObject_GenericSetDict}, and the delete that is refused.
     *
     * <p>These two kinds carry a {@code tp_dictoffset}, so the store is made on the first read that needs one
     * and every later read reaches the same dictionary — which is why {@code vars()} of a wrapper that was only
     * looked at answers a real, empty dictionary rather than complaining. A write puts a new dictionary in its
     * place and copies nothing; a delete is refused in the words {@code PyObject_GenericSetDict} has for {@code
     * NULL}, and anything that is not a dictionary is refused by name of what it was.
     */
    @Test
    void theStoreIsMadeByTheFirstReadAndReplacedByAWriteButNeverDeleted() {
        PyObject fresh = new PyStaticMethod();
        assertThat(((PyDict) Slots.getAttr(fresh, "__dict__")).size()).isZero();
        // Made once: the second read is the object the first made.
        assertThat(Slots.getAttr(fresh, "__dict__")).isSameAs(Slots.getAttr(fresh, "__dict__"));

        PyDict replacement = PyDict.empty();
        replacement.put(PyStr.interned("a"), NUMBER);
        Slots.setAttr(fresh, "__dict__", replacement);
        assertThat(kept(fresh)).containsExactly("a");
        assertThat(Slots.getAttr(fresh, "__dict__")).isSameAs(replacement);

        refuses("TypeError: cannot delete __dict__", () -> Slots.delAttr(fresh, "__dict__"));
        refuses(
                "TypeError: __dict__ must be set to a dictionary, not a 'int'",
                () -> Slots.setAttr(fresh, "__dict__", NUMBER));
        // The name is a getset with a setter, so the refusal of a bad value comes from the setter and not
        // from a missing descriptor.
        refuses(
                "TypeError: __dict__ must be set to a dictionary, not a 'int'",
                () -> Slots.setAttr(new PyClassMethod(NUMBER), "__dict__", NUMBER));
    }

    /**
     * {@code descriptor_get_wrapped_attribute} and {@code descriptor_set_wrapped_attribute}.
     *
     * <p>The read looks in the store first and then asks the callable, and it <em>files what it found</em> in
     * the store, so a later change to the callable is not seen: the {@code {}} a bare class answers is the
     * dictionary the wrapper keeps. The write only ever touches the store, and a delete of a name that was
     * never cached is {@code PyDict_DelItem}'s missing key turned into an attribute complaint in the wrapper's
     * own words. A callable that answers nothing under the name leaves the store untouched and complains about
     * itself, because the read that fills the cache is not an optional one.
     */
    @Test
    void twoNamesReadOffTheCallableAreKeptAndTheDeleteOfAnUnwrittenOneIsTheWrappersOwn() {
        PyType bare = type("Bare", PyType.OBJECT);
        PyObject value = new PyStaticMethod(bare);
        assertThat(kept(value)).doesNotContain("__annotations__", "__annotate__");

        PyObject read = Slots.getAttr(value, "__annotations__");
        assertThat(((PyDict) read).size()).isZero();
        assertThat(kept(value)).contains("__annotations__");
        // The cache is the callable's own answer, and the store now keeps the object the class answered.
        assertThat(Slots.getAttr(value, "__annotations__")).isSameAs(read);
        Slots.setAttr(bare, "__annotations__", TEXT);
        assertThat(Slots.getAttr(value, "__annotations__")).isSameAs(read);

        Slots.setAttr(value, "__annotations__", NUMBER);
        assertThat(Slots.getAttr(value, "__annotations__")).isSameAs(NUMBER);
        Slots.delAttr(value, "__annotations__");
        // Uncached again: the read reaches the callable, which now says what was written on it.
        assertThat(Slots.getAttr(value, "__annotations__")).isSameAs(TEXT);
        refuses(
                "AttributeError: 'classmethod' object has no attribute '__annotations__'",
                () -> Slots.delAttr(new PyClassMethod(bare), "__annotations__"));

        // `__annotate__` is the same pair of rules, and a class that annotated nothing answers `None`.
        PyObject second = new PyClassMethod(bare);
        assertThat(Slots.getAttr(second, "__annotate__")).isSameAs(PyNone.INSTANCE);
        assertThat(kept(second)).contains("__annotate__");

        // A callable that answers nothing under the name leaves the store as it was.
        PyObject aroundInt = new PyStaticMethod(NUMBER);
        refuses(
                "AttributeError: 'int' object has no attribute '__annotations__'",
                () -> Slots.getAttr(aroundInt, "__annotations__"));
        refuses(
                "AttributeError: 'int' object has no attribute '__annotate__'",
                () -> Slots.getAttr(aroundInt, "__annotate__"));
        refuses(
                "AttributeError: 'staticmethod' object has no attribute '__annotate__'",
                () -> Slots.delAttr(aroundInt, "__annotate__"));
        assertThat(kept(aroundInt)).containsExactly("__doc__");
    }

    /**
     * {@code _PyObject_IsAbstract} of the field, and the getset that files no setter.
     *
     * <p>The flag is a plain attribute read of the name off the callable, not a lookup on its type, so a class
     * that holds {@code True} under the name in its own dictionary answers true — and a class that holds {@code
     * None} answers false, as does a callable that answers nothing at all. {@code None} in the field, the state
     * {@code __new__} leaves, is false too where {@code __name__} is a complaint: nothing under the name is
     * missing here, only nothing true.
     */
    @Test
    void theAbstractFlagIsReadOffTheCallableAndTheRowThatReportsItWritesNothing() {
        PyType holds = type("Holds", PyType.OBJECT, "__isabstractmethod__", PyBool.TRUE);
        PyType saysNo = type("SaysNo", PyType.OBJECT, "__isabstractmethod__", PyNone.INSTANCE);
        PyType silent = type("Silent", PyType.OBJECT);

        for (PyObject callable : List.of(holds, saysNo, silent, NUMBER)) {
            assertThat(Slots.getAttr(new PyStaticMethod(callable), "__isabstractmethod__"))
                    .as(callable.repr())
                    .isEqualTo(Slots.getAttr(new PyClassMethod(callable), "__isabstractmethod__"));
        }
        assertThat(Slots.getAttr(new PyStaticMethod(holds), "__isabstractmethod__"))
                .isSameAs(PyBool.TRUE);
        assertThat(Slots.getAttr(new PyStaticMethod(saysNo), "__isabstractmethod__"))
                .isSameAs(PyBool.FALSE);
        assertThat(Slots.getAttr(new PyStaticMethod(silent), "__isabstractmethod__"))
                .isSameAs(PyBool.FALSE);
        assertThat(Slots.getAttr(new PyStaticMethod(NUMBER), "__isabstractmethod__"))
                .isSameAs(PyBool.FALSE);
        assertThat(Slots.getAttr(new PyStaticMethod(), "__isabstractmethod__"))
                .isSameAs(PyBool.FALSE);

        // A getter with no setter: the refusal names the attribute, the kind it was read through, and the
        // plural CPython keeps for every getset — and a delete answers the same sentence as a write.
        refuses(
                "AttributeError: attribute '__isabstractmethod__' of 'staticmethod' objects"
                        + " is not writable",
                () -> Slots.setAttr(new PyStaticMethod(silent), "__isabstractmethod__", PyBool.TRUE));
        refuses(
                "AttributeError: attribute '__isabstractmethod__' of 'staticmethod' objects"
                        + " is not writable",
                () -> Slots.delAttr(new PyStaticMethod(silent), "__isabstractmethod__"));
        refuses(
                "AttributeError: attribute '__isabstractmethod__' of 'classmethod' objects"
                        + " is not writable",
                () -> Slots.setAttr(new PyClassMethod(silent), "__isabstractmethod__", PyBool.TRUE));
        refuses(
                "AttributeError: attribute '__isabstractmethod__' of 'classmethod' objects"
                        + " is not writable",
                () -> Slots.delAttr(new PyClassMethod(silent), "__isabstractmethod__"));
    }

    /**
     * {@code sm_new} / {@code cm_new} behind {@code tp_new_wrapper}, and {@code sm_init} behind its two gates.
     *
     * <p>The constructor creates nothing from its arguments: it allocates and files {@code None}, so a value
     * made that way has an empty store, no name to read, and a {@code __doc__} that is its kind's sentence
     * rather than a copy of anything — and it reads no argument beyond the first, so two more are ignored. Its
     * own three gates come in CPython's order — the receiver checked against {@code type}, then the count, then
     * the subtype — and the count sentence is {@code tp_new_wrapper}'s while the other two name the kind.
     */
    @Test
    void theConstructorFilesNothingAndTheInitialiserFillsTheFieldAndCopiesTheNames() {
        PyObject created =
                Slots.call(
                        entry(PyType.STATIC_METHOD, "__new__"),
                        new PyObject[] {PyType.STATIC_METHOD},
                        null);
        assertThat(created.getType()).isSameAs(PyType.STATIC_METHOD);
        assertThat(((PyDict) Slots.getAttr(created, "__dict__")).size()).isZero();
        assertThat(Slots.getAttr(created, "__func__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(created, "__wrapped__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(created, "__isabstractmethod__")).isSameAs(PyBool.FALSE);
        assertThat(Slots.getAttr(created, "__doc__"))
                .isEqualTo(Slots.getAttr(PyType.STATIC_METHOD, "__doc__"));
        refuses(
                "AttributeError: 'staticmethod' object has no attribute '__name__'",
                () -> Slots.getAttr(created, "__name__"));
        // The rest of the argument list is never read, because `tp_new_wrapper` only hands `tp_new` the type.
        PyObject extras =
                Slots.call(
                        entry(PyType.STATIC_METHOD, "__new__"),
                        new PyObject[] {PyType.STATIC_METHOD, NUMBER, NUMBER},
                        null);
        assertThat(extras.getType()).isSameAs(PyType.STATIC_METHOD);
        assertThat(Slots.getAttr(extras, "__func__")).isSameAs(PyNone.INSTANCE);

        refuses(
                "TypeError: staticmethod.__new__(): not enough arguments",
                () -> Slots.call(entry(PyType.STATIC_METHOD, "__new__"), new PyObject[0], null));
        refuses(
                "TypeError: staticmethod.__new__(X): X is not a type object (int)",
                () -> Slots.call(entry(PyType.STATIC_METHOD, "__new__"), new PyObject[] {NUMBER}, null));
        refuses(
                "TypeError: classmethod.__new__(X): X is not a type object (int)",
                () -> Slots.call(entry(PyType.CLASS_METHOD, "__new__"), new PyObject[] {NUMBER}, null));
        refuses(
                "TypeError: staticmethod.__new__(int): int is not a subtype of staticmethod",
                () -> Slots.call(
                        entry(PyType.STATIC_METHOD, "__new__"), new PyObject[] {PyType.INT}, null));

        PyType saying = saying();
        PyObject init = entry(PyType.STATIC_METHOD, "__init__");
        refuses(
                "TypeError: staticmethod expected 1 argument, got 0",
                () -> Slots.call(init, new PyObject[] {created}, null));
        refuses(
                "TypeError: staticmethod expected 1 argument, got 3",
                () -> Slots.call(init, new PyObject[] {created, saying, saying, saying}, null));
        refuses(
                "TypeError: staticmethod() takes no keyword arguments",
                () ->
                        Slots.call(
                                init,
                                new PyObject[] {created, saying},
                                PyTuple.of(PyStr.interned("x"))));
        // The wrapper row judges its receiver before it counts, so a bad one is named by the slot.
        refuses(
                "TypeError: descriptor '__init__' requires a 'staticmethod' object but received a 'int'",
                () -> Slots.call(init, new PyObject[] {NUMBER, saying}, null));

        Slots.call(init, new PyObject[] {created, saying}, null);
        assertThat(Slots.getAttr(created, "__func__")).isSameAs(saying);
        assertThat(kept(created)).isEqualTo(COPIED);
        assertThat(((PyStr) row(PyType.STATIC_METHOD, "__repr__", created)).getValue())
                .isEqualTo("<staticmethod(" + saying.repr() + ")>");
    }

    /**
     * The {@code METH_CLASS} alias row and the sentence the kind carries.
     *
     * <p>{@code Py_GenericAlias} is filed with {@code METH_O|METH_CLASS} and a {@code NULL} beside it, so the
     * row is reached bound on the class, answers no sentence, and takes exactly one argument — and the argument
     * has to be the kind or a subclass of it, which is the third of {@code classmethoddescr_call}'s three
     * questions. The count is of what the read supplies itself, which is why two arguments given to the bound
     * row complain of two. The sentence the two kinds do have is written again on every read: {@code tp_doc} is
     * parsed by {@code make_docstring}, so the string the class answers is never the object its dictionary
     * holds.
     */
    @Test
    void theAliasRowBindsTheClassAndTheSentenceIsWrittenAnewOnEveryRead() {
        PyObject alias =
                Slots.call(
                        Slots.getAttr(PyType.STATIC_METHOD, "__class_getitem__"),
                        new PyObject[] {PyType.INT},
                        null);
        assertThat(alias.getType().name()).isEqualTo("GenericAlias");
        assertThat(Slots.getAttr(alias, "__origin__")).isSameAs(PyType.STATIC_METHOD);
        PyTuple arguments = (PyTuple) Slots.getAttr(alias, "__args__");
        assertThat(arguments.size()).isEqualTo(1);
        assertThat(arguments.at(0)).isSameAs(PyType.INT);

        PyObject bound = Slots.getAttr(PyType.CLASS_METHOD, "__class_getitem__");
        assertThat(kind(bound)).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(PyType.CLASS_METHOD);
        assertThat(text(bound, "__name__")).isEqualTo("__class_getitem__");
        assertThat(text(bound, "__qualname__")).isEqualTo("classmethod.__class_getitem__");
        assertThat(Slots.getAttr(bound, "__doc__")).isSameAs(PyNone.INSTANCE);

        refuses(
                "TypeError: descriptor '__class_getitem__' of 'staticmethod' object needs an argument",
                () ->
                        Slots.call(
                                entry(PyType.STATIC_METHOD, "__class_getitem__"),
                                new PyObject[0],
                                null));
        refuses(
                "TypeError: descriptor '__class_getitem__' for type 'staticmethod' needs a type,"
                        + " not a 'int' as arg 2",
                () ->
                        Slots.call(
                                entry(PyType.STATIC_METHOD, "__class_getitem__"),
                                new PyObject[] {NUMBER},
                                null));
        refuses(
                "TypeError: classmethod.__class_getitem__() takes exactly one argument (2 given)",
                () -> Slots.call(bound, new PyObject[] {PyType.CLASS_METHOD, NUMBER}, null));

        // The two sentences, each 563 or 637 characters, and each a new string on every read.
        for (PyType owner : List.of(PyType.STATIC_METHOD, PyType.CLASS_METHOD)) {
            int length = owner == PyType.STATIC_METHOD ? 563 : 637;
            PyObject sentence = Slots.getAttr(owner, "__doc__");
            assertThat(((PyStr) sentence).getValue()).hasSize(length);
            assertThat(sentence).isNotSameAs(entry(owner, "__doc__"));
            assertThat(Slots.getAttr(owner, "__doc__")).isNotSameAs(sentence);
            assertThat(Slots.getAttr(owner, "__doc__")).isEqualTo(sentence);
        }
        assertThat(((PyStr) Slots.getAttr(PyType.STATIC_METHOD, "__doc__")).getValue())
                .startsWith("Convert a function to be a static method.");
        assertThat(((PyStr) Slots.getAttr(PyType.CLASS_METHOD, "__doc__")).getValue())
                .startsWith("Convert a function to be a class method.");
    }

    /**
     * {@code sm_descr_get} hands back the field; {@code cm_descr_get} binds the class it was given.
     *
     * <p>The two {@code tp_descr_get} pointers are the reason a class reads its two members differently: the
     * static one answers whatever the field holds, with no receiver and no owner, and the class one makes a
     * method of the field and the owner — which is why binding a wrapper around {@code None} still answers a
     * {@code method} rather than a complaint. Both rows are read through the same {@code wrapper_descriptor},
     * and that row turns each {@code None} it was handed into not-given before it counts, so the pair of them
     * is refused by name.
     */
    @Test
    void theDescriptorHalfOfEachKindIsTheOnlyHalfThatDiffers() {
        PyObject echo = new PyNativeFunction("echo", (args, kwNames) -> args[0]);
        PyType holder = type("Holder", PyType.OBJECT);
        PyObject asStatic = new PyStaticMethod(echo);
        PyObject asClass = new PyClassMethod(echo);

        assertThat(through(asStatic, "__get__", PyNone.INSTANCE, holder)).isSameAs(echo);
        // The value is whatever it is: `sm_descr_get` reads neither of the two arguments.
        assertThat(through(asStatic, "__get__", NUMBER)).isSameAs(echo);
        assertThat(through(asStatic, "__get__", TEXT)).isSameAs(echo);

        PyObject bound = through(asClass, "__get__", PyNone.INSTANCE, holder);
        assertThat(bound.getType().name()).isEqualTo("method");
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(holder);
        // A classmethod around a built-in still binds, and the class arrives in front of the arguments.
        assertThat(Slots.call(bound, new PyObject[] {NUMBER}, null)).isSameAs(holder);
        // A wrapper around nothing binds too: the field is put in front of the class, whatever it is.
        assertThat(through(new PyClassMethod(), "__get__", PyNone.INSTANCE, holder).getType().name())
                .isEqualTo("method");

        refuses(
                "TypeError: __get__(None, None) is invalid",
                () -> through(asStatic, "__get__", PyNone.INSTANCE, PyNone.INSTANCE));
        refuses(
                "TypeError: __get__ expected at least 1 argument, got 0",
                () -> through(asStatic, "__get__"));
        refuses(
                "TypeError: __get__ expected at most 2 arguments, got 3",
                () -> through(asClass, "__get__", holder, holder, holder));
        refuses(
                "TypeError: wrapper __get__() takes no keyword arguments",
                () ->
                        Slots.call(
                                Slots.getAttr(asStatic, "__get__"),
                                new PyObject[] {asStatic},
                                PyTuple.of(PyStr.interned("x"))));
    }
}
