package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The exception family as the runtime decides it, measured against CPython 3.14.8.
 *
 * <p>The corpus script reads a written exception class the way a program does: it raises one, catches
 * it, and asks the value what it kept. This is the machinery under that, which no script can point at —
 * which of the three dictionaries a member is inherited from, the order the three refusals of the
 * creator come in, what a keyword argument is worth to a method whose table names only positionals, and
 * which of two candidate reads wins for a field the value itself owns. Every class here is made by
 * {@link Classes#newType} and every member Java wrote, so each assertion is about the protocol rather
 * than about the compiler that would normally supply it.
 */
class ExceptionClassTest {

    /** A call that gave {@code flag} as a keyword, which is the one spelling these methods refuse. */
    private static final PyTuple flag = PyBoundMethod.keywordTuple(new String[] {"flag"});

    /** {@code type(name, bases, {members})}, the three-argument creator asked to make a class. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType) Classes.newType(PyType.TYPE, new PyObject[] {
            PyStr.of(name), PyTuple.of(bases), dict
        }, null);
    }

    /** {@code class Name(base)} for a base Java already has, with nothing else in the body. */
    private static PyType exception(String name, PyType base, Object... members) {
        return type(name, new PyType[] {base}, members);
    }

    /** {@code Name(*arguments)}: the call a program makes, put through {@code type_call}. */
    private static PyObject make(PyType kind, PyObject... arguments) {
        return kind.call(arguments, null);
    }

    /** {@code Name(flag=1)}, and {@code Name(positionals..., flag=1)} behind that. */
    private static PyObject makeWithKeyword(PyType kind, PyObject... arguments) {
        return kind.call(withOne(arguments), flag);
    }

    /** The arguments, with {@code 1} appended as the value of the one keyword. */
    private static PyObject[] withOne(PyObject[] arguments) {
        PyObject[] all = new PyObject[arguments.length + 1];
        System.arraycopy(arguments, 0, all, 0, arguments.length);
        all[arguments.length] = PyInt.ONE;
        return all;
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
     * {@code class Bare(Exception): pass}.
     *
     * <p>The creator is inherited rather than declared, which is the whole of what makes the class
     * raisable: {@code object.__new__} would have refused the argument, as a class with no initialiser of
     * its own is refused, and CPython's {@code object_new} only asks the init slot because
     * {@code BaseException} has already replaced it.
     */
    @Test
    void aClassThatWritesNothingIsStillCalledWithArguments() {
        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        PyObject value = make(bare, PyStr.of("boom"));

        assertThat(value).isInstanceOf(PyBaseException.class);
        assertThat(((PyBaseException) value).exceptionType()).isSameAs(bare);
        assertThat(value.getType()).isSameAs(bare);
        assertThat(((PyBaseException) value).args()).hasToString("('boom',)");
        assertThat(value.repr()).isEqualTo("Bare('boom')");
        assertThat(value.str()).isEqualTo("boom");
        assertThat(bare.matches(ExceptionTypes.BASE_EXCEPTION)).isTrue();

        assertThat(make(bare).repr()).isEqualTo("Bare()");
        assertThat(make(bare).str()).isEmpty();
        assertThat(make(bare, PyStr.of("a"), PyInt.of(3)).repr()).isEqualTo("Bare('a', 3)");
        assertThat(make(bare, PyStr.of("a"), PyInt.of(3)).str()).isEqualTo("('a', 3)");
    }

    /** The arguments of the call, before any {@code __init__} has said anything about them. */
    @Test
    void theArgumentsAreTheOnesTheCreatorWasHanded() {
        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        PyObject empty = make(bare);
        assertThat(Slots.getAttr(empty, "args")).hasToString("()");

        // `BaseException.__init__` takes the arguments over, so a value re-initialised reads the new
        // ones — which is what `super().__init__("boom")` inside a class does to the value the creator
        // already made.
        Slots.call(TypeDict.EXCEPTION_INIT, new PyObject[] {empty, PyStr.of("boom")}, null);
        assertThat(Slots.getAttr(empty, "args")).hasToString("('boom',)");
        assertThat(empty.repr()).isEqualTo("Bare('boom')");

        Slots.call(TypeDict.EXCEPTION_INIT, new PyObject[] {empty}, null);
        assertThat(Slots.getAttr(empty, "args")).hasToString("()");
    }

    /** {@code Bare(flag=1)}: the keyword is refused in the class's own words, before anything is made. */
    @Test
    void aKeywordIsRefusedByTheClassItWasGivenTo() {
        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        refuses("TypeError: Bare() takes no keyword arguments", () -> makeWithKeyword(bare));
        refuses(
                "TypeError: ValueError() takes no keyword arguments",
                () -> makeWithKeyword(ExceptionTypes.VALUE_ERROR));
        refuses(
                "TypeError: Bare() takes no keyword arguments", () -> makeWithKeyword(bare, PyStr.of("a")));

        // The same refusal reaches the initialiser through a value, and names the class of the value
        // rather than the class that declares the method.
        PyObject held = make(bare, PyStr.of("kept"));
        refuses(
                "TypeError: Bare() takes no keyword arguments",
                () -> Slots.call(TypeDict.EXCEPTION_INIT, withOne(new PyObject[] {held}), flag));
        assertThat(Slots.getAttr(held, "args")).hasToString("('kept',)");
    }

    /**
     * {@code BaseException.__new__}: the three refusals, in CPython's order.
     *
     * <p>An argument list with nothing in it is not even a metatype, the first argument has to be one,
     * and the class being built has to be in the family whose dictionary holds the method — so a call
     * that is wrong in two ways is complained about in the first of them.
     */
    @Test
    void theCreatorChecksTheClassItIsAskedFor() {
        refuses(
                "TypeError: BaseException.__new__(): not enough arguments",
                () -> Slots.call(TypeDict.EXCEPTION_NEW, new PyObject[0], null));
        refuses(
                "TypeError: BaseException.__new__(X): X is not a type object (int)",
                () -> Slots.call(TypeDict.EXCEPTION_NEW, new PyObject[] {PyInt.ONE}, null));
        PyType plain = type("Quiet", new PyType[] {PyType.OBJECT});
        refuses(
                "TypeError: BaseException.__new__(Quiet): Quiet is not a subtype of BaseException",
                () -> Slots.call(TypeDict.EXCEPTION_NEW, new PyObject[] {plain}, null));

        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        PyObject made = Slots.call(TypeDict.EXCEPTION_NEW, new PyObject[] {bare}, null);
        assertThat(made).isInstanceOf(PyBaseException.class);
        assertThat(made.getType()).isSameAs(bare);
    }

    /**
     * What a keyword is worth to a method whose table names only positionals: nothing at all.
     *
     * <p>{@code BaseException.__new__} keeps the arguments that arrived positionally and reads the rest
     * no further, so {@code Bare.__new__(Bare, "a", flag=1)} makes a value of one argument. The
     * initialiser is the other half of the pair and refuses the same call, which is why a keyword ever
     * reaches the creator only of a class that never got as far as its own {@code __init__}.
     */
    @Test
    void theCreatorKeepsOnlyWhatArrivedPositionally() {
        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        PyObject made =
                Slots.call(
                        TypeDict.EXCEPTION_NEW, new PyObject[] {bare, PyStr.of("a"), PyInt.ONE}, flag);

        assertThat(made.repr()).isEqualTo("Bare('a')");
        assertThat(((PyBaseException) made).args()).hasToString("('a',)");

        PyObject nothing = Slots.call(TypeDict.EXCEPTION_NEW, new PyObject[] {bare, PyInt.ONE}, flag);
        assertThat(nothing.repr()).isEqualTo("Bare()");
        assertThat(((PyBaseException) nothing).args()).hasToString("()");
    }

    /**
     * Which dictionary holds the three names, and how far down the family they reach.
     *
     * <p>{@code BaseException} owns them and no other class repeats them, so {@code ValueError}'s own
     * dictionary is empty of {@code __new__} while its lookup order finds the one it inherits — which is
     * what lets {@link Specials}, {@link PySuper} and a direct read from Python all reach one object.
     * {@code type} and {@code object} keep their own, and a class Python wrote keeps nothing until its
     * body writes it.
     */
    @Test
    void oneDictionaryInTheFamilyHoldsThemAndTheRestInherit() {
        assertThat(Specials.declared(ExceptionTypes.BASE_EXCEPTION, "__new__"))
                .isSameAs(TypeDict.EXCEPTION_NEW);
        assertThat(Specials.declared(ExceptionTypes.EXCEPTION, "__new__")).isNull();
        assertThat(Specials.declared(ExceptionTypes.VALUE_ERROR, "with_traceback")).isNull();
        assertThat(Specials.declared(PyType.TYPE, "__new__")).isSameAs(TypeDict.NEW);
        assertThat(Specials.declared(PyType.OBJECT, "__init_subclass__"))
                .isSameAs(TypeDict.INIT_SUBCLASS);

        assertThat(Specials.member(ExceptionTypes.VALUE_ERROR, "__new__"))
                .isSameAs(TypeDict.EXCEPTION_NEW);
        assertThat(Specials.member(ExceptionTypes.KEY_ERROR, "__init__"))
                .isSameAs(TypeDict.EXCEPTION_INIT);

        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        assertThat(Specials.member(bare, "with_traceback")).isSameAs(TypeDict.WITH_TRACEBACK);
        // The two names a program reads are not in the order `Specials.lookUp` walks, which asks the
        // dictionaries of classes Python wrote — and is why the class system reaches these by `member`.
        assertThat(Specials.lookUp(bare, "__new__")).isNull();
        assertThat(Specials.lookUp(bare, "__init__")).isNull();

        // Read through a value the wrapper binds to it; the raw static method binds nothing.
        PyObject held = make(bare, PyStr.of("x"));
        assertThat(Descriptors.get(TypeDict.EXCEPTION_INIT, held, bare))
                .isNotSameAs(TypeDict.EXCEPTION_INIT);
        assertThat(Descriptors.get(TypeDict.EXCEPTION_NEW, held, bare))
                .isSameAs(TypeDict.EXCEPTION_NEW);
    }

    /** The words the two families of receiver refusal use for the exception half of {@code type}. */
    @Test
    void theTwoHalvesComplainAboutTheirReceiverDifferently() {
        refuses(
                "TypeError: descriptor '__init__' of 'BaseException' object needs an argument",
                () -> Slots.call(TypeDict.EXCEPTION_INIT, new PyObject[0], null));
        refuses(
                "TypeError: descriptor '__init__' requires a 'BaseException' object but received a 'str'",
                () -> Slots.call(TypeDict.EXCEPTION_INIT, new PyObject[] {PyStr.of("x")}, null));

        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        refuses(
                "TypeError: unbound method BaseException.with_traceback() needs an argument",
                () -> Slots.call(TypeDict.WITH_TRACEBACK, new PyObject[0], null));
        refuses(
                "TypeError: descriptor 'with_traceback' for 'BaseException' objects doesn't apply to a 'str' object",
                () -> Slots.call(TypeDict.WITH_TRACEBACK, new PyObject[] {PyStr.of("x")}, null));
        assertThat(Slots.call(
                        TypeDict.WITH_TRACEBACK, new PyObject[] {make(bare), PyNone.INSTANCE}, null))
                .isInstanceOf(PyBaseException.class);
    }

    /** {@code e.with_traceback(tb)}: one argument, checked as the field is, and the value answers itself. */
    @Test
    void withTracebackChecksItsOneArgumentAndAnswersTheValue() {
        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        PyObject held = make(bare, PyStr.of("x"));

        assertThat(Slots.call(TypeDict.WITH_TRACEBACK, new PyObject[] {held, PyNone.INSTANCE}, null))
                .isSameAs(held);
        refuses(
                "TypeError: BaseException.with_traceback() takes exactly one argument (0 given)",
                () -> Slots.call(TypeDict.WITH_TRACEBACK, new PyObject[] {held}, null));
        refuses(
                "TypeError: BaseException.with_traceback() takes exactly one argument (2 given)",
                () ->
                        Slots.call(
                                TypeDict.WITH_TRACEBACK,
                                new PyObject[] {held, PyNone.INSTANCE, PyNone.INSTANCE},
                                null));
        refuses(
                "TypeError: __traceback__ must be a traceback or None",
                () -> Slots.call(TypeDict.WITH_TRACEBACK, new PyObject[] {held, PyInt.ONE}, null));

        // A chain is hung on the value as the error leaves each frame it travels out of, so a value that
        // was made rather than raised has none, and the field reads the absence of one.
        assertThat(Slots.getAttr(held, "__traceback__")).isSameAs(PyNone.INSTANCE);
        refuses(
                "TypeError: __traceback__ must be a traceback or None", () -> held.setAttr("__traceback__", PyInt.ONE));
        held.setAttr("__traceback__", PyNone.INSTANCE);
        assertThat(Slots.getAttr(held, "__traceback__")).isSameAs(PyNone.INSTANCE);
    }

    /** The three fields that take only an exception, and the one that takes any iterable. */
    @Test
    void theChainFieldsCheckWhatTheyAreGiven() {
        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        PyObject held = make(bare);
        PyObject other = make(bare, PyStr.of("other"));

        held.setAttr("__cause__", other);
        assertThat(Slots.getAttr(held, "__cause__")).isSameAs(other);
        held.setAttr("__context__", other);
        assertThat(Slots.getAttr(held, "__context__")).isSameAs(other);
        held.setAttr("__cause__", PyNone.INSTANCE);
        assertThat(Slots.getAttr(held, "__cause__")).isSameAs(PyNone.INSTANCE);

        refuses(
                "TypeError: exception cause must be None or derive from BaseException",
                () -> held.setAttr("__cause__", PyInt.ONE));
        refuses(
                "TypeError: exception context must be None or derive from BaseException",
                () -> held.setAttr("__context__", PyStr.of("s")));

        held.setAttr("args", PyStr.of("xy"));
        assertThat(Slots.getAttr(held, "args")).hasToString("('x', 'y')");
        held.setAttr("args", PyTuple.of(PyInt.ONE, PyStr.of("k")));
        assertThat(Slots.getAttr(held, "args")).hasToString("(1, 'k')");
        refuses("TypeError: 'int' object is not iterable", () -> held.setAttr("args", PyInt.ONE));
        refuses("TypeError: args may not be deleted", () -> held.delAttr("args"));
    }

    /** {@code e.args} outranks a name the class wrote in its own dictionary, as a data descriptor does. */
    @Test
    void theFieldsTheValueOwnsOutrankTheClass() {
        PyType shadow =
                exception(
                        "Shadow",
                        ExceptionTypes.EXCEPTION,
                        "args",
                        PyInt.of(5),
                        "note",
                        PyStr.of("class"));
        PyObject held = make(shadow, PyStr.of("kept"));

        assertThat(Slots.getAttr(held, "args")).hasToString("('kept',)");
        assertThat(Slots.getAttr(shadow, "args")).hasToString("5");
        assertThat(Slots.getAttr(held, "note")).hasToString("'class'");

        // A name the value writes into its own dictionary is what the next read finds, and a name the
        // class holds that cannot write is what it cannot hide.
        held.setAttr("note", PyStr.of("value"));
        assertThat(Slots.getAttr(held, "note")).hasToString("'value'");
        assertThat(Slots.getAttr(shadow, "note")).hasToString("'class'");
        refuses(
                "AttributeError: 'Shadow' object has no attribute 'missing'", () -> Slots.getAttr(held, "missing"));
    }

    /** Identity, which is what an exception is hashed and matched by until its class writes otherwise. */
    @Test
    void anExceptionIsItselfAndNoOther() {
        PyType bare = exception("Bare", ExceptionTypes.EXCEPTION);
        PyObject held = make(bare, PyStr.of("x"));
        PyObject same = make(bare, PyStr.of("x"));

        assertThat(held.eq(held)).isTrue();
        assertThat(held.eq(same)).isFalse();
        assertThat(held.hash()).isEqualTo(System.identityHashCode(held));
        assertThat(same.hash()).isEqualTo(System.identityHashCode(same));

        // A class that writes equality and no hash is given `__hash__ = None` as it is built, which is
        // the only way an exception class loses the identity hash it inherits.
        PyType compared =
                exception(
                        "Compared",
                        ExceptionTypes.EXCEPTION,
                        "__eq__",
                        new PyNativeFunction("__eq__", (args, kwNames) -> PyBool.TRUE));
        assertThat(Specials.lookUp(compared, "__hash__")).isSameAs(PyNone.INSTANCE);
        refuses("TypeError: unhashable type: 'Compared'", () -> make(compared, PyStr.of("x")).hash());
    }

    /**
     * The two names a value is read with, and the qualified one that does not appear in either.
     *
     * <p>{@code BaseException_repr} prints {@code tp_name}, which for a class Python wrote is its plain
     * {@code __name__} however deeply it is nested; {@code __qualname__} is what a method of the class
     * reports about itself and never reaches the display.
     */
    @Test
    void theDisplayNamesTheClassPlainly() {
        PyDict dict = PyDict.empty();
        dict.put(PyStr.interned("__qualname__"), PyStr.of("Outer.Nested"));
        PyType nested =
                (PyType)
                        Classes.newType(
                                PyType.TYPE,
                                new PyObject[] {
                                    PyStr.of("Nested"),
                                    PyTuple.of(new PyType[] {ExceptionTypes.EXCEPTION}),
                                    dict
                                },
                                null);
        PyObject held = make(nested, PyStr.of("x"));

        assertThat(held.repr()).isEqualTo("Nested('x')");
        assertThat(Slots.getAttr(nested, "__qualname__")).hasToString("'Outer.Nested'");
        assertThat(Slots.getAttr(nested, "__name__")).hasToString("'Nested'");

        // KeyError reports its one argument with repr, because the argument is a key and a missing empty
        // key has to read as `''` rather than as nothing.
        assertThat(make(ExceptionTypes.KEY_ERROR, PyStr.of("")).str()).isEqualTo("''");
        assertThat(make(ExceptionTypes.VALUE_ERROR, PyStr.of("")).str()).isEmpty();
        assertThat(make(ExceptionTypes.KEY_ERROR, PyStr.of("k")).repr()).isEqualTo("KeyError('k')");
    }
}
