package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code BaseException.add_note} as the runtime decides it, measured against CPython 3.14.8.
 *
 * <p>The corpus script reads a note the way a program does: it adds one and asks the value what it now
 * carries. This is the machinery under that, which no script can point at — that the name arrives with
 * the first note rather than living on the value, that the argument table judges the note before anything
 * looks at the list, which of the two names an arity complaint wears, and what a class that files a list
 * under {@code __notes__} is answered by. Every value here is made by {@link Classes#newType} and every
 * method Java wrote, so each assertion is about the protocol rather than about the compiler that would
 * normally supply it.
 *
 * <p>The two names are the part a script reaches only by accident. Reading a name and calling what comes
 * back hands the descriptor the receiver in the argument list, so the refusal is written under the class
 * that declares the method; binding first makes a copy that names the class of the value it was made
 * from. {@link PyObject#methodForm()} is the hook that keeps those two apart, and it is what the
 * interpreter's method form of {@code LOAD_ATTR} pushes.
 */
class AddNoteTest {

    /** A call that gave {@code note} as its one keyword, which is the spelling this method refuses. */
    private static final PyTuple note = PyBoundMethod.keywordTuple(new String[] {"note"});

    /** {@code type(name, bases, {members})}, the three-argument creator asked to make a class. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType) Classes.newType(
                PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(bases), dict}, null);
    }

    /** {@code class Name(Exception)} with whatever the body wrote. */
    private static PyType exception(String name, Object... members) {
        return type(name, new PyType[] {ExceptionTypes.EXCEPTION}, members);
    }

    /** {@code Name(*arguments)}: the call a program makes, put through {@code type_call}. */
    private static PyObject make(PyType kind, PyObject... arguments) {
        return kind.call(arguments, null);
    }

    /** {@code BaseException.add_note(value, note)}, the descriptor called with the receiver in hand. */
    private static PyObject add(PyObject held, String text) {
        return Slots.call(TypeDict.ADD_NOTE, new PyObject[] {held, PyStr.of(text)}, null);
    }

    /** The same note put to the copy that reading the name off the value hands out. */
    private static PyObject addThrough(PyObject method, String text) {
        return Slots.call(method, new PyObject[] {PyStr.of(text)}, null);
    }

    /** The value's own dictionary, which is where a note is filed. */
    private static PyDict dictOf(PyObject held) {
        return (PyDict) Slots.getAttr(held, "__dict__");
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
     * Nothing is missing from the value until the first note.
     *
     * <p>{@code __notes__} is not a field and not a member of the family: it is an ordinary attribute
     * written by the first call, so the read refuses until then and answers with an ordinary list after.
     * The list is kept rather than copied, so the next note arrives on the one object the value was
     * already showing — and the method's own answer is {@code None}, which is why a program cannot chain
     * it.
     */
    @Test
    void theNameArrivesWithTheFirstNoteAndTheListKeepsArriving() {
        PyType written = exception("Written");
        PyObject held = make(written, PyStr.of("first"));

        assertThat(Slots.findAttr(held, "__notes__")).isNull();
        refuses(
                "AttributeError: 'Written' object has no attribute '__notes__'",
                () -> Slots.getAttr(held, "__notes__"));
        assertThat(dictOf(held)).hasToString("{}");

        assertThat(add(held, "a note")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(held, "__notes__")).hasToString("['a note']");
        assertThat(dictOf(held).get(PyStr.interned("__notes__")))
                .isSameAs(Slots.getAttr(held, "__notes__"));

        add(held, "another");
        PyObject notes = Slots.getAttr(held, "__notes__");
        assertThat(notes).hasToString("['a note', 'another']");
        add(held, "a third");
        assertThat(Slots.getAttr(held, "__notes__")).isSameAs(notes);
        assertThat(notes).hasToString("['a note', 'another', 'a third']");

        // A note is not an argument: the display of the value is what the call made it.
        assertThat(Slots.getAttr(held, "args")).hasToString("('first',)");
        assertThat(held.str()).isEqualTo("first");
        assertThat(held.repr()).isEqualTo("Written('first')");
    }

    /**
     * The note is judged before the list is looked at, and by the class of what arrived.
     *
     * <p>Cling to the order: {@code None} is spelled as the value rather than as {@code NoneType}, every
     * other non-text is spelled by its class, and a {@code __notes__} that is already there and is not a
     * list has to wait — so a call that is wrong in both ways says nothing about the list.
     */
    @Test
    void theArgumentTableJudgesTheNoteBeforeTheListIsRead() {
        PyType written = exception("Written");
        PyType unrelated = type("Unrelated", new PyType[] {PyType.OBJECT});
        PyObject held = make(written, PyStr.of("x"));

        refuses(
                "TypeError: add_note() argument must be str, not int",
                () -> Slots.call(
                        TypeDict.ADD_NOTE, new PyObject[] {held, PyInt.of(3)}, null));
        refuses(
                "TypeError: add_note() argument must be str, not None",
                () -> Slots.call(
                        TypeDict.ADD_NOTE, new PyObject[] {held, PyNone.INSTANCE}, null));
        refuses(
                "TypeError: add_note() argument must be str, not bytes",
                () -> Slots.call(
                        TypeDict.ADD_NOTE, new PyObject[] {held, PyBytes.of(new byte[] {1})}, null));
        refuses(
                "TypeError: add_note() argument must be str, not Unrelated",
                () -> Slots.call(
                        TypeDict.ADD_NOTE,
                        new PyObject[] {held, make(unrelated)},
                        null));
        refuses(
                "TypeError: add_note() argument must be str, not list",
                () -> Slots.call(
                        TypeDict.ADD_NOTE,
                        new PyObject[] {held, PyList.of(PyStr.of("not text"))},
                        null));

        // Nothing was written while the table refused, and a list that is there is not read at all.
        assertThat(Slots.findAttr(held, "__notes__")).isNull();
        held.setAttr("__notes__", PyTuple.of(PyStr.of("not"), PyStr.of("a"), PyStr.of("list")));
        refuses(
                "TypeError: add_note() argument must be str, not int",
                () -> Slots.call(
                        TypeDict.ADD_NOTE, new PyObject[] {held, PyInt.of(3)}, null));
        assertThat(Slots.getAttr(held, "__notes__")).hasToString("('not', 'a', 'list')");
    }

    /**
     * One method, two names, and which one a refusal wears is decided by the read.
     *
     * <p>Calling the descriptor with the receiver in the argument list is what reading a name and calling
     * what comes back amounts to, so the complaint names {@code BaseException}; a copy bound to a value
     * names that value's class instead, even for a class that inherited the method rather than wrote it.
     * {@link PyObject#methodForm()} is the transient unbound copy the interpreter's method form pushes,
     * and it is the only reason both spellings can be right at once.
     */
    @Test
    void theArityRefusalIsWrittenUnderTheReadItCameFrom() {
        PyType written = exception("Written");
        PyObject held = make(written, PyStr.of("x"));
        PyObject bound = Slots.getAttr(held, "add_note");

        refuses(
                "TypeError: Written.add_note() takes exactly one argument (0 given)",
                () -> Slots.call(bound, new PyObject[0], null));
        refuses(
                "TypeError: BaseException.add_note() takes exactly one argument (0 given)",
                () -> Slots.call(bound.methodForm(), new PyObject[0], null));
        refuses(
                "TypeError: BaseException.add_note() takes exactly one argument (2 given)",
                () -> Slots.call(
                        TypeDict.ADD_NOTE,
                        new PyObject[] {held, PyStr.of("a"), PyStr.of("b")},
                        null));
        refuses(
                "TypeError: Written.add_note() takes exactly one argument (3 given)",
                () -> Slots.call(
                        bound,
                        new PyObject[] {PyStr.of("a"), PyStr.of("b"), PyStr.of("c")},
                        null));

        // The method form is the only difference: the same copy answers with the same list.
        assertThat(addThrough(bound.methodForm(), "through the method form"))
                .isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(held, "__notes__")).hasToString("['through the method form']");
        assertThat(bound.methodForm()).isNotSameAs(bound);
    }

    /**
     * Neither half of this family has a keyword table, and an empty one is not a keyword either.
     *
     * <p>A named argument is refused for giving one; the count is refused for giving none. The two are
     * told apart by the list of names, which unpacking an empty mapping leaves absent rather than empty —
     * so {@code f(**{})} is refused by count, exactly as the call that named nothing at all is.
     */
    @Test
    void aKeywordIsRefusedByCountWhenTheNamesWereUnpackedFromNothing() {
        PyType written = exception("Written");
        PyObject held = make(written, PyStr.of("x"));
        PyObject bound = Slots.getAttr(held, "add_note");

        refuses(
                "TypeError: Written.add_note() takes no keyword arguments",
                () -> Slots.call(bound, new PyObject[] {PyStr.of("a")}, note));
        refuses(
                "TypeError: BaseException.add_note() takes no keyword arguments",
                () -> Slots.call(
                        TypeDict.ADD_NOTE, new PyObject[] {held, PyStr.of("a")}, note));
        refuses(
                "TypeError: Written.add_note() takes exactly one argument (0 given)",
                () -> Slots.call(bound, new PyObject[0], PyTuple.of()));
        assertThat(addThrough(bound, "unpacked")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(held, "__notes__")).hasToString("['unpacked']");
    }

    /**
     * The two refusals of a {@code method_descriptor} receiver, which the arity check never reaches.
     *
     * <p>Nothing at all is the unbound complaint, naming the declaring class and the method; a value from
     * outside the family is the descriptor complaint, which counts the receiver among the arguments and so
     * says {@code (0 given)} for a call that handed one. A value inside it is called with nothing else and
     * arrives as a note.
     */
    @Test
    void theReceiverOfAMethodDescriptorIsJudgedBeforeTheArgumentsAre() {
        refuses(
                "TypeError: unbound method BaseException.add_note() needs an argument",
                () -> Slots.call(TypeDict.ADD_NOTE, new PyObject[0], null));
        refuses(
                "TypeError: descriptor 'add_note' for 'BaseException' objects doesn't apply to a 'int' object",
                () -> Slots.call(TypeDict.ADD_NOTE, new PyObject[] {PyInt.of(1), PyStr.of("a note")}, null));
        refuses(
                "TypeError: descriptor 'add_note' for 'BaseException' objects doesn't apply to a 'str' object",
                () -> Slots.call(TypeDict.ADD_NOTE, new PyObject[] {PyStr.of("x"), PyStr.of("a note")}, null));

        PyType derived = exception("Derived");
        PyObject held = make(derived, PyStr.of("x"));
        assertThat(Slots.call(
                        TypeDict.ADD_NOTE, new PyObject[] {held, PyStr.of("a note")}, null))
                .isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(held, "__notes__")).hasToString("['a note']");
    }

    /**
     * A list that is already there is used, and anything else under the name is refused.
     *
     * <p>The list is found as an attribute, so the check is only whether it is a list: {@code PyList_Check}
     * and not the sequence protocol, which is why a tuple of texts — which would append nothing anywhere —
     * and a string, an absent {@code None} and a value of one's own class are all refused in the same
     * words, with the note left off and the value unchanged.
     */
    @Test
    void aListThatIsAlreadyThereIsAppendedToAndAnythingElseIsRefused() {
        PyType written = exception("Written");

        PyObject hand = make(written, PyStr.of("hand"));
        PyList filed = PyList.of(PyStr.of("written by hand"));
        hand.setAttr("__notes__", filed);
        assertThat(add(hand, "added")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(hand, "__notes__")).isSameAs(filed);
        assertThat(hand.repr()).isEqualTo("Written('hand')");
        assertThat(filed).hasToString("['written by hand', 'added']");

        PyObject tupled = make(written, PyStr.of("tuple"));
        tupled.setAttr("__notes__", PyTuple.of(PyStr.of("not"), PyStr.of("a"), PyStr.of("list")));
        refuses("TypeError: Cannot add note: __notes__ is not a list", () -> add(tupled, "refused"));
        assertThat(Slots.getAttr(tupled, "__notes__")).hasToString("('not', 'a', 'list')");

        PyObject string = make(written, PyStr.of("string"));
        string.setAttr("__notes__", PyStr.of("a plain string"));
        refuses("TypeError: Cannot add note: __notes__ is not a list", () -> add(string, "refused"));

        PyObject none = make(written, PyStr.of("none"));
        none.setAttr("__notes__", PyNone.INSTANCE);
        refuses("TypeError: Cannot add note: __notes__ is not a list", () -> add(none, "refused"));

        PyType unrelated = type("Unrelated", new PyType[] {PyType.OBJECT});
        PyObject claimed = make(written, PyStr.of("object"));
        claimed.setAttr("__notes__", make(unrelated));
        refuses("TypeError: Cannot add note: __notes__ is not a list", () -> add(claimed, "refused"));
        assertThat(((PyObject) Slots.getAttr(claimed, "__notes__")).getType().name())
                .isEqualTo("Unrelated");
    }

    /**
     * A class that files a list under the name is answered by <em>that</em> list.
     *
     * <p>The note goes where the read goes, and the read walks the class's order before the value's own
     * dictionary — so every instance of the class appends to the one list the class holds, and nothing is
     * ever written into the instance. A value that files a list of its own is the same rule from the other
     * side: the read stops at the dictionary, and the note follows it there rather than to the class.
     * A {@code __setattr__} that throws the write away loses the note the same way, which is what the
     * corpus reads and a Java-written member cannot reach — the hook is asked only for a function.
     */
    @Test
    void aClassThatFilesTheListIsAnsweredFromItAndSoIsAValueThatFilesItsOwn() {
        PyList shared = PyList.of(PyStr.of("filed by the class"));
        PyType filing = exception("Filing", "__notes__", shared);

        PyObject one = make(filing, PyStr.of("one"));
        PyObject two = make(filing, PyStr.of("two"));
        assertThat(Slots.getAttr(one, "__notes__")).isSameAs(shared);
        assertThat(Slots.getAttr(two, "__notes__")).isSameAs(shared);

        add(one, "added by one");
        assertThat(Slots.getAttr(two, "__notes__"))
                .hasToString("['filed by the class', 'added by one']");
        assertThat(dictOf(one).get(PyStr.interned("__notes__"))).isNull();
        assertThat(shared).isSameAs(Slots.getAttr(filing, "__notes__"));

        PyList own = PyList.of(PyStr.of("written by the value"));
        one.setAttr("__notes__", own);
        add(one, "added after");
        assertThat(own).hasToString("['written by the value', 'added after']");
        assertThat(Slots.getAttr(two, "__notes__"))
                .hasToString("['filed by the class', 'added by one']");
    }

    /**
     * One member for the whole family, and the names it is read with.
     *
     * <p>The descriptor is filed on {@code BaseException} alone, so reading it off any class of the family
     * hands out the same object and a bound copy is equal without being identical. A subclass reached
     * through the class is the unbound member again, which is what lets {@code Written.add_note} name the
     * declaring class while {@code e.add_note} names the value's own.
     */
    @Test
    void theMemberIsFiledOnceAndReadAsTheSameObjectEverywhere() {
        PyType written = exception("Written");
        PyObject held = make(written, PyStr.of("x"));

        assertThat(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, "add_note")).isSameAs(TypeDict.ADD_NOTE);
        assertThat(Slots.getAttr(written, "add_note")).isSameAs(TypeDict.ADD_NOTE);
        assertThat(Slots.getAttr(ExceptionTypes.VALUE_ERROR, "add_note")).isSameAs(TypeDict.ADD_NOTE);
        assertThat(Slots.getAttr(ExceptionTypes.TYPE_ERROR, "add_note"))
                .isSameAs(Slots.getAttr(ExceptionTypes.KEY_ERROR, "add_note"));
        assertThat(Slots.findAttr(written, "__notes__")).isNull();

        assertThat(Slots.getAttr(TypeDict.ADD_NOTE, "__name__")).hasToString("'add_note'");
        assertThat(Slots.getAttr(TypeDict.ADD_NOTE, "__qualname__"))
                .hasToString("'BaseException.add_note'");
        assertThat(Slots.getAttr(TypeDict.ADD_NOTE, "__doc__"))
                .hasToString("'Add a note to the exception'");
        assertThat(Slots.repr(TypeDict.ADD_NOTE))
                .isEqualTo("<method 'add_note' of 'BaseException' objects>");
        assertThat(Slots.getAttr(held, "add_note").repr())
                .isEqualTo("<built-in method add_note of Written object>");

        PyObject again = Slots.getAttr(held, "add_note");
        assertThat(again.eq(Slots.getAttr(held, "add_note"))).isTrue();
        assertThat(again).isNotSameAs(Slots.getAttr(held, "add_note"));
        assertThat(Slots.getAttr(again, "__qualname__")).hasToString("'Written.add_note'");
    }

    /**
     * The same split runs under the group's three, which are filed the same way.
     *
     * <p>A group is an exception, so its notes are the ones above; and each of {@code derive}, {@code
     * split} and {@code subgroup} is a {@code method_descriptor} declared once on {@code
     * BaseExceptionGroup}, which is the name their refusals wear when the receiver came from reading the
     * name off a class or from the unbound form of a lookup.
     */
    @Test
    void aGroupCarriesNotesTooAndItsThreeMethodsSplitTheSameWay() {
        PyObject group =
                Slots.call(
                        ExceptionTypes.EXCEPTION_GROUP,
                        new PyObject[] {
                            PyStr.of("children"),
                            PyList.of(make(ExceptionTypes.VALUE_ERROR, PyStr.of("one")))
                        },
                        null);

        assertThat(add(group, "on a group")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(group, "__notes__")).hasToString("['on a group']");

        PyObject split = Slots.getAttr(group, "split");
        assertThat(Slots.getAttr(ExceptionTypes.EXCEPTION_GROUP, "split"))
                .isSameAs(TypeDict.GROUP_SPLIT);
        assertThat(Slots.getAttr(TypeDict.GROUP_SPLIT, "__qualname__"))
                .hasToString("'BaseExceptionGroup.split'");
        assertThat(Slots.getAttr(split, "__qualname__")).hasToString("'ExceptionGroup.split'");

        refuses(
                "TypeError: BaseExceptionGroup.split() takes exactly one argument (0 given)",
                () -> Slots.call(TypeDict.GROUP_SPLIT, new PyObject[] {group}, null));
        refuses(
                "TypeError: ExceptionGroup.split() takes exactly one argument (0 given)",
                () -> Slots.call(split, new PyObject[0], null));
        refuses(
                "TypeError: BaseExceptionGroup.split() takes exactly one argument (0 given)",
                () -> Slots.call(split.methodForm(), new PyObject[0], null));

        // With a class in hand the method answers the pair it was always going to answer.
        assertThat(Slots.call(split, new PyObject[] {ExceptionTypes.VALUE_ERROR}, null))
                .hasToString(
                        "(ExceptionGroup('children', [ValueError('one')]), None)");
    }
}
