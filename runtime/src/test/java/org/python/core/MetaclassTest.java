package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The metaclass protocol as the runtime decides it, measured against CPython 3.14.8.
 *
 * <p>The two corpus scripts read the protocol the way a program does — through a class statement, from
 * inside a method, in the order the compiler asks. This is the surface a script cannot reach: the
 * arguments each of the three {@code type} entry points counts and in which order it refuses them, which
 * of two candidate members a lookup consults first, and what a class that no Python source ever built
 * answers when it is asked to be one. The classes here are made by {@link Classes#newType} — {@code
 * type_new} itself — and their members are the Java-written kinds {@link DescriptorsTest} uses, so every
 * assertion is about the protocol rather than about the compiler that would normally supply it.
 */
class MetaclassTest {

    /** {@code type(name, bases, {members})}, the three-argument creator asked to make a plain class. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        return (PyType) Classes.newType(PyType.TYPE, three(name, bases, members), null);
    }

    /** {@code Meta(name, bases, {members})}: the same call, put to a metatype. */
    private static PyType type(PyType metatype, String name, PyType[] bases, Object... members) {
        return (PyType) Classes.newType(metatype, three(name, bases, members), null);
    }

    private static PyObject[] three(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return new PyObject[] {PyStr.of(name), PyTuple.of(bases), dict};
    }

    /** {@code name(*bases)} as a call from Python, which is what routes through {@link Classes#typeCall}. */
    private static PyObject callType(PyObject... arguments) {
        return PyType.TYPE.call(arguments, null);
    }

    /** The same call with one keyword spelled out: {@code name(..., tag=value)}. */
    private static PyObject callType(PyTuple keywordNames, PyObject... arguments) {
        return PyType.TYPE.call(arguments, keywordNames);
    }

    /**
     * A method that records the call it received and answers with {@code answer}.
     *
     * <p>The record names the receiver it was bound to and then every argument after it, so a test can
     * tell {@code Checked.__instancecheck__(Holder, value)} from a call that never reached the hook at
     * all — which is the whole of what the {@code isinstance} questions turn on.
     */
    private static PyNativeFunction recording(List<String> asked, String label, PyObject answer) {
        return new PyNativeFunction(
                label,
                (args, kwNames) -> {
                    List<String> seen = new ArrayList<>();
                    seen.add(args[0] instanceof PyType owner ? owner.name() : args[0].getType().name());
                    for (int i = 1; i < args.length; i++) {
                        seen.add(args[i].getType().name());
                    }
                    if (kwNames != null) {
                        seen.addAll(Arrays.asList(kwNames));
                    }
                    asked.add(label + " " + String.join(" ", seen));
                    return answer;
                });
    }

    /** {@code classmethod(f)} — the binding a metaclass method gets when Python writes one. */
    private static PyObject classMethod(PyNativeFunction method) {
        return new PyClassMethod(method);
    }

    /** {@code property(fget)} — the member a value uses to claim a class it was not built from. */
    private static PyObject property(PyNativeFunction getter) {
        return new PyProperty(getter, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE);
    }

    /** {@code C.__dict__} unwrapped: the dictionary the names a class was built from are kept in. */
    private static PyDict dictOf(PyType kind) {
        return ((PyMappingProxy) Slots.getAttr(kind, "__dict__")).mapping();
    }

    /** {@code TypeError}, {@code NotImplementedError} and {@code RuntimeError} in CPython's own words. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * One argument answers with the value's class; two, four and any keywords do not.
     *
     * <p>The count is checked before the arguments are looked at, which is why {@code type('a', ())} and
     * {@code type('a', (), {}, 1)} complain in exactly the same words, and why a keyword next to one
     * positional is refused by {@code type} itself while a keyword next to three reaches the class being
     * made and is refused by {@code object}'s {@code __init_subclass__} — the only place a {@code type()}
     * call can put a keyword argument.
     */
    @Test
    void theOneArgumentFormAnswersWithTheClassAndEveryOtherCountRefuses() {
        assertThat(callType(PyStr.of("x"))).isSameAs(PyType.STR);
        assertThat(callType(PyInt.of(3))).isSameAs(PyType.INT);

        refuses("TypeError: type() takes 1 or 3 arguments", () -> callType(PyStr.of("a"), PyTuple.of()));
        refuses(
                "TypeError: type() takes 1 or 3 arguments",
                () -> callType(PyStr.of("a"), PyTuple.of(), PyDict.empty(), PyInt.of(1)));
        refuses(
                "TypeError: type() takes no keyword arguments",
                () -> callType(PyTuple.of(PyStr.of("tag")), PyStr.of("x"), PyInt.of(1)));
        refuses(
                "TypeError: Wrong.__init_subclass__() takes no keyword arguments",
                () ->
                        callType(
                                PyTuple.of(PyStr.of("extra")),
                                PyStr.of("Wrong"),
                                PyTuple.of(),
                                PyDict.empty(),
                                PyInt.of(1)));
    }

    /**
     * What {@code type_new} refuses, in the order it refuses it.
     *
     * <p>The count comes first, and with the old spelling — {@code takes exactly 3 arguments}, naming the
     * method rather than {@code type()} and counting only what the call gave. Then each position is
     * checked in turn, so a call that gets the first argument wrong never learns that the second is also
     * wrong. These are the refusals of {@code type.__new__}, which is why they say
     * {@code type.__new__()} where {@link Classes#typeCall} says {@code type()}.
     */
    @Test
    void theThreeArgumentCreatorRefusesEachPositionItCannotUse() {
        refuses(
                "TypeError: type.__new__() takes exactly 3 arguments (1 given)",
                () -> Classes.newType(new PyObject[] {PyStr.of("a")}, null));
        refuses(
                "TypeError: type.__new__() argument 1 must be str, not int",
                () -> Classes.newType(new PyObject[] {PyInt.of(1), PyTuple.of(), PyDict.empty()}, null));
        refuses(
                "TypeError: type.__new__() argument 2 must be tuple, not dict",
                () ->
                        Classes.newType(
                                new PyObject[] {PyStr.of("a"), PyDict.empty(), PyDict.empty()}, null));
        refuses(
                "TypeError: type.__new__() argument 3 must be dict, not int",
                () -> Classes.newType(new PyObject[] {PyStr.of("a"), PyTuple.of(), PyInt.of(3)}, null));
    }

    /**
     * {@code type_init}: the two things a class made by a metatype call is checked for afterwards.
     *
     * <p>A keyword beside one positional is refused by name, a fourth argument is refused by count, and
     * three arguments with keywords are accepted — {@code type.__init__} parses keywords, it just has one
     * parameter that no caller can supply. The call that is accepted here is the one {@code Meta('C', (),
     * {})} makes after its own {@code __new__} returned.
     */
    @Test
    void theInitialiserIsCheckedAfterTheClassIsMade() {
        Classes.typeInit(new PyObject[] {PyStr.of("a")}, null);
        Classes.typeInit(
                new PyObject[] {PyStr.of("a"), PyTuple.of(), PyDict.empty(), PyStr.of("tag")},
                new String[] {"extra"});
        Classes.typeInit(
                new PyObject[] {PyStr.of("a"), PyTuple.of(), PyDict.empty(), PyStr.of("k")},
                new String[] {"k"});

        refuses(
                "TypeError: type.__init__() takes no keyword arguments",
                () -> Classes.typeInit(new PyObject[] {PyStr.of("a"), PyInt.of(1)}, new String[] {"k"}));
        refuses(
                "TypeError: type.__init__() takes 1 or 3 arguments",
                () -> Classes.typeInit(new PyObject[0], null));
        refuses(
                "TypeError: type.__init__() takes 1 or 3 arguments",
                () -> Classes.typeInit(
                        new PyObject[] {
                            PyStr.of("a"), PyTuple.of(), PyDict.empty(), PyDict.empty(), PyDict.empty()
                        },
                        null));
    }

    /**
     * The metatype of the most derived base wins, and two that cannot be ranked are a conflict.
     *
     * <p>No keyword is needed to get a class a metaclass: the winner is read off the bases, and the
     * winner's own winner is what a derived class inherits. The conflict is CPython's, is worded as one
     * long sentence with no names in it, and is raised <em>before</em> the bases are checked for being
     * classes at all — which is why {@code type('a', (1,), {})} complains about a metaclass rather than
     * about an {@code int}.
     */
    @Test
    void theMostDerivedMetaclassWinsAndTwoUnrelatedOnesDoNot() {
        PyType meta = type("Meta", new PyType[] {PyType.TYPE});
        PyType holder = type(meta, "Holder", new PyType[0]);
        assertThat(holder.getType()).isSameAs(meta);

        PyType derived = type("Derived", new PyType[] {holder});
        assertThat(derived.getType()).isSameAs(meta);
        assertThat(Slots.getAttr(derived, "__class__")).isSameAs(meta);

        PyType other = type("Other", new PyType[] {PyType.TYPE});
        PyType second = type(other, "Second", new PyType[0]);
        refuses(
                "TypeError: metaclass conflict: the metaclass of a derived class must be a (non-strict)"
                        + " subclass of the metaclasses of all its bases",
                () -> type("Clash", new PyType[] {holder, second}));
        refuses(
                "TypeError: metaclass conflict: the metaclass of a derived class must be a (non-strict)"
                        + " subclass of the metaclasses of all its bases",
                () -> Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of("Number"), PyTuple.of(PyInt.of(1)), PyDict.empty()},
                        null));
    }

    /**
     * A built-in type cannot be a base, and says so by name.
     *
     * <p>CPython derives {@code class C(str)} by copying the layout into a new type; here every built-in
     * is a Java class with slots no dictionary can stand in for, so the refusal is this runtime's own and
     * is named as a deviation. It is a {@code NotImplementedError} rather than a {@code TypeError}
     * because nothing about the program is wrong — only about what this runtime has built so far.
     */
    @Test
    void aBuiltInBaseIsRefusedByItsOwnName() {
        refuses(
                "NotImplementedError: subclassing 'str' is not implemented",
                () -> type("MyStr", new PyType[] {PyType.STR}));
        refuses(
                "NotImplementedError: subclassing 'int' is not implemented",
                () -> type("MyInt", new PyType[] {PyType.INT}));

        assertThat(type("Exceptionish", new PyType[] {ExceptionTypes.TYPE_ERROR})
                        .getType())
                .isSameAs(PyType.TYPE);
    }

    /**
     * The qualified name is read out of the namespace and filed on the class instead.
     *
     * <p>{@code __qualname__} is identity, not a member: CPython removes it from the dictionary it was
     * handed, so {@code 'A.B' in C.__dict__} is false afterwards while {@code C.__qualname__} reads the
     * value back. A name that is not a string is refused by the same message CPython uses, which names
     * the type rather than the class being built.
     */
    @Test
    void theQualifiedNameLeavesTheNamespaceAndStaysOnTheClass() {
        PyType nested = type("Outer", new PyType[0], "__qualname__", PyStr.of("A.B"));

        assertThat(((PyStr) Slots.getAttr(nested, "__qualname__")).getValue()).isEqualTo("A.B");
        assertThat(((PyStr) Slots.getAttr(nested, "__name__")).getValue()).isEqualTo("Outer");
        assertThat(dictOf(nested).get(PyStr.interned("__qualname__"))).isNull();

        refuses(
                "TypeError: type __qualname__ must be a str, not int",
                () -> type("Bad", new PyType[0], "__qualname__", PyInt.of(3)));
    }

    /**
     * A cell left behind by a body is filled, and anything else under that name is refused.
     *
     * <p>{@code __classcell__} is taken out of the namespace once it has been answered, so it is not a
     * member of the finished class. A value that is not a cell is complained about with the <em>type</em>
     * of the value printed as a class, which is CPython's spelling of {@code %.200s} applied to
     * {@code Py_TYPE(...)}.
     */
    @Test
    void aClassCellIsFilledAndEverythingElseUnderThatNameIsRefused() {
        PyCell cell = new PyCell(PyNone.INSTANCE);
        PyType uses = type("Uses", new PyType[0], "__classcell__", cell);

        assertThat(cell.value()).isSameAs(uses);
        assertThat(dictOf(uses).get(PyStr.interned("__classcell__"))).isNull();

        refuses(
                "TypeError: __classcell__ must be a nonlocal cell, not <class 'int'>",
                () -> type("Bad", new PyType[0], "__classcell__", PyInt.of(3)));
    }

    /**
     * {@code isinstance} asks the metatype, and never the class it is asking about.
     *
     * <p>Both halves are the same lookup run twice: the hook is found on {@code type(Holder)}, so a
     * metaclass that declares {@code __instancecheck__} answers for every class it built, and a class
     * that declares the name in its own body answers for nothing — the plain walk runs instead and the
     * method is never called. That is why the hook list stays empty for the second class.
     *
     * <p>The receiver here is the metaclass, not the class being asked about, because the member is a
     * {@link PyClassMethod} and {@code cm_descr_get} binds a classmethod to its second argument. A
     * {@code __instancecheck__} written as a plain function is the one a program normally writes, and
     * that one is handed the class — which is the difference the corpus reads.
     */
    @Test
    void theInstanceHookIsAskedOfTheMetatypeOnly() {
        List<String> asked = new ArrayList<>();
        PyType checked =
                type(
                        "Checked",
                        new PyType[] {PyType.TYPE},
                        "__instancecheck__",
                        classMethod(recording(asked, "instancecheck", PyBool.TRUE)));
        PyType holder = type(checked, "Holder", new PyType[0]);

        assertThat(Classes.instanceOf(PyStr.of("x"), holder)).isTrue();
        assertThat(asked).containsExactly("instancecheck Checked str");

        PyType body =
                type(
                        "Body",
                        new PyType[0],
                        "__instancecheck__",
                        classMethod(recording(asked, "body hook", PyBool.TRUE)));

        assertThat(Classes.instanceOf(PyInt.of(1), body)).isFalse();
        assertThat(asked).hasSize(1);
    }

    /**
     * A tuple of candidates is scanned in order and the first that answers wins.
     *
     * <p>Which is why {@code isinstance(1, (int, 3))} is true without a word about the {@code 3}: the
     * scan never reaches it. An unusable item earlier in the tuple is a different matter — each item is
     * the whole question, so {@code (3, int)} complains about argument 2 before the {@code int} is ever
     * tried, and so does a bare non-type.
     */
    @Test
    void aTupleOfCandidatesIsScannedUntilOneAnswers() {
        assertThat(Classes.instanceOf(PyInt.of(1), PyTuple.of(PyType.INT, PyInt.of(3)))).isTrue();
        assertThat(Classes.instanceOf(PyInt.of(1), PyTuple.of(PyType.STR, PyType.INT))).isTrue();

        refuses(
                "TypeError: isinstance() arg 2 must be a type, a tuple of types, or a union",
                () -> Classes.instanceOf(PyInt.of(1), PyTuple.of(PyInt.of(3), PyType.INT)));
        refuses(
                "TypeError: isinstance() arg 2 must be a type, a tuple of types, or a union",
                () -> Classes.instanceOf(PyStr.of("x"), PyTuple.of(PyType.INT, PyInt.of(3))));
        refuses(
                "TypeError: isinstance() arg 2 must be a type, a tuple of types, or a union",
                () -> Classes.instanceOf(PyInt.of(1), PyStr.of("not a type")));
    }

    /**
     * A value that answers {@code __class__} with another class is taken at its word.
     *
     * <p>The fallback runs only after the value's real class has refused: {@code isinstance(p, int)} is
     * true for a {@code Proxy} that claims to be one, and {@code isinstance(p, Proxy)} stays true for the
     * plain walk that runs first. A class that files another class under the name outright — the
     * no-descriptor form — is the same answer, because both are read through the same attribute.
     */
    @Test
    void aValueThatClaimsAnotherClassPassesForIt() {
        PyType proxy =
                type(
                        "Proxy",
                        new PyType[0],
                        "__class__",
                        property(recording(new ArrayList<>(), "__class__", PyType.INT)));
        PyObject value = Slots.call(proxy, new PyObject[0], null);

        assertThat(value.getType()).isSameAs(proxy);
        assertThat(Classes.instanceOf(value, PyType.INT)).isTrue();
        assertThat(Classes.instanceOf(value, proxy)).isTrue();
        assertThat(Classes.instanceOf(value, PyType.OBJECT)).isTrue();
        assertThat(Classes.instanceOf(value, PyType.STR)).isFalse();

        PyType claims = type("Claims", new PyType[0], "__class__", PyType.INT);
        assertThat(Classes.instanceOf(Slots.call(claims, new PyObject[0], null), PyType.INT)).isTrue();
    }

    /**
     * {@code issubclass} answers from its hook too, and the hook outranks the argument check.
     *
     * <p>{@code issubclass('not a class', Kind)} is not a {@code TypeError}: the metatype's
     * {@code __subclasscheck__} is asked first and a hook that returns an answer ends the question.
     * Without a hook, both refusals name the argument they found unusable, and a call whose second
     * argument is a class while the first is not gets the argument-1 complaint even when argument 2 would
     * also have been refused. As in {@link #theInstanceHookIsAskedOfTheMetatypeOnly}, the member written
     * here is a classmethod, so the hook is bound to the metatype and the record says {@code SubChecked}.
     */
    @Test
    void theSubclassHookIsAskedBeforeTheArgumentsAreJudged() {
        List<String> asked = new ArrayList<>();
        PyType subChecked =
                type(
                        "SubChecked",
                        new PyType[] {PyType.TYPE},
                        "__subclasscheck__",
                        classMethod(recording(asked, "subclasscheck", PyBool.TRUE)));
        PyType kind = type(subChecked, "Kind", new PyType[0]);

        assertThat(Classes.subclassOf(PyStr.of("not a class"), kind)).isTrue();
        assertThat(asked).containsExactly("subclasscheck SubChecked str");

        refuses(
                "TypeError: issubclass() arg 1 must be a class",
                () -> Classes.subclassOf(PyStr.of("s"), PyType.INT));
        refuses(
                "TypeError: issubclass() arg 2 must be a class, a tuple of classes, or a union",
                () -> Classes.subclassOf(PyType.INT, PyStr.of("not a class")));
        refuses(
                "TypeError: issubclass() arg 2 must be a class, a tuple of classes, or a union",
                () -> Classes.subclassOf(PyType.INT, PyTuple.of(PyType.STR, PyStr.of("no"))));
        assertThat(Classes.subclassOf(PyType.INT, PyTuple.of(PyType.INT, PyStr.of("no")))).isTrue();
    }

    /**
     * A class is subscripted by its metatype's {@code __getitem__} or by its own
     * {@code __class_getitem__}, and by nothing else.
     *
     * <p>Both are asked before the value is touched, which is what makes {@code Numbers[int]} a call
     * rather than an index. A type with neither is refused in CPython's words, which name the type and do
     * not quote them — the one spelling {@link Slots#getItem} keeps apart from its complaint about a
     * value, which quotes the type name in single quotes.
     *
     * <p>{@code type[int]} is refused here although CPython answers with
     * {@code types.GenericAlias(int, [int])}: an alias is a type of its own, and modelling it is a later
     * slice.
     */
    @Test
    void aClassIsSubscriptedByNameAndRefusedByItsOwnName() {
        List<String> asked = new ArrayList<>();
        PyType numbers =
                type(
                        "Numbers",
                        new PyType[0],
                        "__class_getitem__",
                        classMethod(recording(asked, "getitem", PyStr.of("generic"))));

        assertThat(((PyStr) Slots.getItem(numbers, PyType.INT)).getValue()).isEqualTo("generic");
        assertThat(asked).containsExactly("getitem Numbers type");

        refuses(
                "TypeError: type 'int' is not subscriptable",
                () -> Slots.getItem(PyType.INT, PyInt.of(3)));
        refuses(
                "TypeError: type 'Numbers' is not subscriptable",
                () -> Slots.getItem(type("Numbers", new PyType[0]), PyInt.of(3)));
        refuses(
                "TypeError: 'int' object is not subscriptable",
                () -> Slots.getItem(PyInt.of(3), PyInt.of(0)));
    }

    /**
     * The class statement's leftover keywords reach {@code __init_subclass__} and nothing else.
     *
     * <p>The hook is read through a proxy built from the new class on both sides, so a class that
     * declares it is not told about its own declaration — only about the class below it — and the
     * keyword arguments arrive in the vectorcall layout the call used. The refusal of an unexpected
     * keyword comes from {@code object}'s own hook and names the class being made; a hook written in
     * Python with a signature that does not fit names its own class instead, which is the difference the
     * corpus reads.
     */
    @Test
    void theLeftoverKeywordsGoToTheSubclassHookOfTheBase() {
        List<String> asked = new ArrayList<>();
        PyType watched =
                type(
                        "Watched",
                        new PyType[0],
                        "__init_subclass__",
                        classMethod(recording(asked, "hook", PyNone.INSTANCE)));

        PyType sub =
                (PyType)
                        Classes.newType(
                                PyType.TYPE,
                                new PyObject[] {
                                    PyStr.of("Sub"), PyTuple.of(watched), PyDict.empty(), PyInt.of(1)
                                },
                                new String[] {"tag"});

        assertThat(asked).containsExactly("hook Sub int tag");
        assertThat(sub.getType()).isSameAs(PyType.TYPE);

        refuses(
                "TypeError: Orphan.__init_subclass__() takes no keyword arguments",
                () ->
                        Classes.newType(
                                PyType.TYPE,
                                new PyObject[] {
                                    PyStr.of("Orphan"), PyTuple.of(), PyDict.empty(), PyInt.of(1)
                                },
                                new String[] {"unexpected"}));
    }

    /**
     * A metaclass call hands the whole namespace to {@code type_new} and keeps what comes back.
     *
     * <p>The class a metatype builds is a {@link PyClass} whose own class is that metatype, and the
     * dictionary it was handed is copied rather than adopted: writing to the namespace afterwards changes
     * nothing about the class, which is the difference CPython makes between {@code C.__dict__} and the
     * local names of the body.
     */
    @Test
    void aClassBuiltByAMetatypeIsCopiedOutOfTheNamespaceItWasGiven() {
        PyDict namespace = PyDict.empty();
        namespace.put(PyStr.interned("kept"), PyInt.of(1));
        PyType made =
                (PyType)
                        Classes.newType(
                                type("Meta", new PyType[] {PyType.TYPE}),
                                new PyObject[] {PyStr.of("Made"), PyTuple.of(), namespace},
                                null);

        namespace.put(PyStr.interned("added"), PyInt.of(2));

        assertThat(Slots.getAttr(made, "kept")).isSameAs(PyInt.of(1));
        refuses(
                "AttributeError: type object 'Made' has no attribute 'added'",
                () -> Slots.getAttr(made, "added"));
        assertThat(made.getType().name()).isEqualTo("Meta");
    }
}
