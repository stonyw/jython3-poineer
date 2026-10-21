package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The group family as the runtime decides it, measured against CPython 3.14.8.
 *
 * <p>The corpus scripts read a group the way a program does: they make one, split it, ask an {@code
 * except*} clause which half it takes, and print whatever escapes. This is the machinery under that,
 * which no script can point at — which of the two classes a value ends up belonging to and how far that
 * decision comes ahead of the refusal of a named argument, which of the family's dictionaries holds the
 * five members, what a matcher is allowed to be before it is ever called and how many times it is called
 * once it is, which of the four writes a part of a split keeps its metadata through, and in what order a
 * clause refuses the types it was handed. Every class here is made by {@link Classes#newType} and every
 * member Java wrote, so each assertion is about the protocol rather than about the compiler that would
 * normally supply it.
 */
class ExceptionGroupTest {

    /** {@code BaseExceptionGroup}, which is the class the five members are written under. */
    private static final PyType BASE = ExceptionTypes.BASE_EXCEPTION_GROUP;

    /** {@code ExceptionGroup}, which is the class a group of nothing but exceptions is made as. */
    private static final PyType NARROWER = ExceptionTypes.EXCEPTION_GROUP;

    /** The one sentence every refusal of a matcher uses. */
    private static final String NO_MATCHER =
            "TypeError: expected an exception type, a tuple of exception types, "
                    + "or a callable (other than a class)";

    /** What a clause that names a group is told instead of being allowed to run. */
    private static final String NO_GROUP_CLAUSE =
            "TypeError: catching ExceptionGroup with except* is not allowed. Use except instead.";

    /** {@code Name(flag=1)}, the one spelling the two arguments leave no room for. */
    private static final PyTuple flag = PyBoundMethod.keywordTuple(new String[] {"flag"});

    /** {@code class Name(base)} for a base Java already has, with nothing else in the body. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType) Classes.newType(PyType.TYPE, new PyObject[] {
            PyStr.of(name), PyTuple.of(bases), dict
        }, null);
    }

    /** {@code Name(*arguments, flag=1)}, with the keyword's value appended behind the positional ones. */
    private static PyObject named(PyType kind, PyObject... arguments) {
        PyObject[] handed = new PyObject[arguments.length + 1];
        System.arraycopy(arguments, 0, handed, 0, arguments.length);
        handed[arguments.length] = PyInt.ONE;
        return kind.call(handed, flag);
    }

    /** {@code Name("m", [children...])}, which is the only way a group is made. */
    private static PyObject group(PyType kind, PyObject... children) {
        return kind.call(new PyObject[] {PyStr.of("m"), PyList.of(children)}, null);
    }

    /** {@code Name("message")}, for the children a group is made out of. */
    private static PyObject error(PyType kind, String message) {
        return kind.call(new PyObject[] {PyStr.of(message)}, null);
    }

    /** {@code g.split(matcher)}: the two halves, as the driver receives them. */
    private static PyTuple split(PyObject group, PyObject matcher) {
        return (PyTuple) Slots.call(Slots.getAttr(group, "split"), new PyObject[] {matcher}, null);
    }

    /** {@code g.subgroup(matcher)}: the matching half alone. */
    private static PyObject subgroup(PyObject group, PyObject matcher) {
        return Slots.call(Slots.getAttr(group, "subgroup"), new PyObject[] {matcher}, null);
    }

    /** The children a part holds, which is where the leaves of the original are still to be found. */
    private static PyObject[] childrenOf(PyObject group) {
        return ((PyBaseExceptionGroup) group).children().getArray();
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
     * {@code BaseExceptionGroup_new}'s last step: the children decide, but only the two built-in classes
     * are ever swapped for the narrower one.
     *
     * <p>The check asks each direct child whether it is an {@code Exception} and no further, so a group of
     * groups is promoted or refused by the classes of its children rather than by what is inside them. A
     * class a person wrote is never promoted, which is the only difference between it and {@code
     * BaseExceptionGroup}, and the refusal to hold a {@code BaseException} is worded for the two built-in
     * classes and for a subclass by its own name.
     */
    @Test
    void theChildrenDecideWhichClassAnswers() {
        PyObject value = error(ExceptionTypes.VALUE_ERROR, "v");
        PyObject keyboard = error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki");

        assertThat(group(BASE, value).getType()).isSameAs(NARROWER);
        assertThat(group(BASE, keyboard).getType()).isSameAs(BASE);
        assertThat(group(BASE, value, keyboard).getType()).isSameAs(BASE);
        assertThat(group(NARROWER, value).getType()).isSameAs(NARROWER);
        refuses(
                "TypeError: Cannot nest BaseExceptions in an ExceptionGroup",
                () -> group(NARROWER, keyboard));

        PyObject narrowChild = group(NARROWER, value);
        PyObject baseChild = group(BASE, keyboard);
        assertThat(group(BASE, narrowChild).getType()).isSameAs(NARROWER);
        refuses(
                "TypeError: Cannot nest BaseExceptions in an ExceptionGroup",
                () -> group(NARROWER, baseChild));

        PyType named = type("Named", new PyType[] {BASE});
        assertThat(group(named, value).getType()).isSameAs(named);
        assertThat(group(named, keyboard).getType()).isSameAs(named);

        PyType bundle = type("Bundle", new PyType[] {NARROWER});
        assertThat(group(bundle, value).getType()).isSameAs(bundle);
        refuses("TypeError: Cannot nest BaseExceptions in 'Bundle'", () -> group(bundle, keyboard));
    }

    /**
     * The keyword refusal, and the four checks that come ahead of it.
     *
     * <p>{@code _PyArg_NoKeywords} is read off {@code Py_TYPE(self)}, which the class decided above has
     * already replaced — so {@code BaseExceptionGroup("m", [ValueError()])} with a named argument is
     * refused in the name of {@code ExceptionGroup}. A call wrong in two ways is complained about in the
     * first of them, however, because every other check is made before the class is chosen.
     */
    @Test
    void aNamedArgumentIsRefusedInTheNameOfTheClassMade() {
        PyObject value = error(ExceptionTypes.VALUE_ERROR, "v");
        PyObject keyboard = error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki");
        PyType named = type("Named", new PyType[] {BASE});

        refuses(
                "TypeError: ExceptionGroup() takes no keyword arguments",
                () -> named(BASE, PyStr.of("m"), PyList.of(value)));
        refuses(
                "TypeError: BaseExceptionGroup() takes no keyword arguments",
                () -> named(BASE, PyStr.of("m"), PyList.of(keyboard)));
        refuses(
                "TypeError: Named() takes no keyword arguments",
                () -> named(named, PyStr.of("m"), PyList.of(value)));

        refuses(
                "TypeError: BaseExceptionGroup.__new__() argument 1 must be str, not int",
                () -> named(BASE, PyInt.of(3), PyList.of(value)));
        refuses(
                "TypeError: BaseExceptionGroup.__new__() takes exactly 2 arguments (3 given)",
                () -> named(BASE, PyStr.of("m"), PyList.of(value), PyInt.of(1)));
        refuses(
                "ValueError: second argument (exceptions) must be a non-empty sequence",
                () -> named(BASE, PyStr.of("m"), PyList.of()));
        refuses(
                "ValueError: Item 1 of second argument (exceptions) is not an exception",
                () -> named(BASE, PyStr.of("m"), PyList.of(value, PyStr.of("x"))));
    }

    /**
     * Which dictionary in the family holds the five names, and how far down they reach.
     *
     * <p>{@code BaseExceptionGroup} owns all five and {@code ExceptionGroup} repeats none of them, so a
     * subclass of either reads the same five objects off its order while its own namespace is empty of
     * them. The group family's creator and initialiser are not the exception family's, though a group is
     * an exception too and reaches {@code with_traceback} through the same order.
     */
    @Test
    void oneDictionaryInTheFamilyHoldsThemAndTheRestInherit() {
        assertThat(Specials.declared(BASE, "split")).isSameAs(TypeDict.GROUP_SPLIT);
        assertThat(Specials.declared(BASE, "subgroup")).isSameAs(TypeDict.GROUP_SUBGROUP);
        assertThat(Specials.declared(BASE, "derive")).isSameAs(TypeDict.GROUP_DERIVE);
        assertThat(Specials.declared(BASE, "__new__")).isSameAs(TypeDict.GROUP_NEW);
        assertThat(Specials.declared(BASE, "__init__")).isSameAs(TypeDict.GROUP_INIT);

        assertThat(Specials.declared(NARROWER, "split")).isNull();
        assertThat(Specials.member(NARROWER, "split")).isSameAs(TypeDict.GROUP_SPLIT);
        assertThat(Specials.member(NARROWER, "derive")).isSameAs(TypeDict.GROUP_DERIVE);
        assertThat(Specials.member(NARROWER, "__init__")).isSameAs(TypeDict.GROUP_INIT);

        assertThat(TypeDict.GROUP_NEW).isNotSameAs(TypeDict.EXCEPTION_NEW);
        assertThat(TypeDict.GROUP_INIT).isNotSameAs(TypeDict.EXCEPTION_INIT);
        assertThat(Specials.member(BASE, "with_traceback")).isSameAs(TypeDict.WITH_TRACEBACK);

        PyType named = type("Named", new PyType[] {BASE});
        assertThat(Specials.declared(named, "split")).isNull();
        assertThat(Specials.lookUp(named, "split")).isNull();
        assertThat(Specials.member(named, "split")).isSameAs(TypeDict.GROUP_SPLIT);

        // The creator is not a descriptor at all and binds nothing; the other four bind what they were
        // read from, which is the whole of how a group answers `g.split(matcher)`.
        PyObject held = group(BASE, error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki"));
        assertThat(Descriptors.get(TypeDict.GROUP_NEW, held, NARROWER)).isSameAs(TypeDict.GROUP_NEW);
        assertThat(Descriptors.get(TypeDict.GROUP_SPLIT, held, NARROWER))
                .isNotSameAs(TypeDict.GROUP_SPLIT);
        assertThat(Descriptors.get(TypeDict.GROUP_INIT, held, NARROWER))
                .isNotSameAs(TypeDict.GROUP_INIT);
    }

    /**
     * What the two classes and the five members say about themselves.
     *
     * <p>Two of the members are the two sentences CPython lets every class inherit and never rewrites, so
     * the read that comes from {@code ExceptionGroup} carries the same text as the one from {@code
     * BaseExceptionGroup} and only the name it reports changes. The three group methods are a method table
     * with no sentence beside them, which is what {@code ml_doc} keeps as NULL and {@code __doc__} reads
     * back as {@code None} — on the member and on the bound copy alike.
     */
    @Test
    void twoMembersSayWhatEveryClassSaysAndThreeSayNothing() {
        PyObject held = group(BASE, error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki"));

        assertThat(Slots.getAttr(BASE, "__doc__"))
                .hasToString("'A combination of multiple unrelated exceptions.'");
        assertThat(Slots.getAttr(NARROWER, "__doc__")).isSameAs(PyNone.INSTANCE);

        assertThat(Slots.getAttr(TypeDict.GROUP_NEW, "__doc__"))
                .hasToString("'Create and return a new object.  See help(type) for accurate signature.'");
        assertThat(Slots.getAttr(TypeDict.GROUP_INIT, "__doc__"))
                .hasToString("'Initialize self.  See help(type(self)) for accurate signature.'");
        assertThat(Slots.getAttr(TypeDict.GROUP_SPLIT, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(TypeDict.GROUP_SUBGROUP, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(TypeDict.GROUP_DERIVE, "__doc__")).isSameAs(PyNone.INSTANCE);

        assertThat(Slots.getAttr(held, "__init__").getAttr("__doc__"))
                .hasToString("'Initialize self.  See help(type(self)) for accurate signature.'");
        assertThat(Slots.getAttr(held, "split").getAttr("__doc__")).isSameAs(PyNone.INSTANCE);

        // The four spellings of the same name: a method descriptor bound to a value, the same member read
        // off a class that only inherits it, and the creator that is a plain function object either way.
        assertThat(Slots.getAttr(held, "split").getAttr("__qualname__"))
                .hasToString("'BaseExceptionGroup.split'");
        assertThat(Slots.getAttr(NARROWER, "split").getAttr("__qualname__"))
                .hasToString("'BaseExceptionGroup.split'");
        assertThat(Slots.getAttr(held, "__init__").getAttr("__qualname__"))
                .hasToString("'BaseExceptionGroup.__init__'");
        assertThat(Slots.getAttr(held, "__new__")).isSameAs(TypeDict.GROUP_NEW);
        assertThat(Slots.getAttr(TypeDict.GROUP_NEW, "__self__")).isSameAs(BASE);
        assertThat(Slots.getAttr(TypeDict.GROUP_SPLIT, "__objclass__")).isSameAs(BASE);
        assertThat(Slots.getAttr(held, "split").getAttr("__self__")).isSameAs(held);

        assertThat(Slots.getAttr(NARROWER, "split").getType()).isSameAs(PyType.METHOD_DESCRIPTOR);
        assertThat(Slots.getAttr(held, "split").getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(held, "__init__").getType()).isSameAs(PyType.METHOD_WRAPPER);
        assertThat(Slots.getAttr(NARROWER, "__new__").getType()).isSameAs(PyType.BUILTIN_FUNCTION);
    }

    /**
     * Two reads of one member: equal, and not one object.
     *
     * <p>{@code meth_compare} compares the method and the object it wrapped, both by identity, and each
     * read makes a fresh copy the way {@code PyCFunction_NewEx} does — so {@code g.split == g.split} holds
     * while {@code g.split is g.split} refuses, and the copy of a second group is unequal to the first.
     * The member itself stays one object per name across the whole family, which is why it is not equal to
     * any of the copies made from it.
     */
    @Test
    void twoReadsOfOneMemberAreEqualButNotOneValue() {
        PyObject keyboard = error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki");
        PyObject held = group(BASE, keyboard);
        PyObject other = group(BASE, error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki"));

        PyObject first = Slots.getAttr(held, "split");
        PyObject second = Slots.getAttr(held, "split");
        assertThat(first).isNotSameAs(second).isEqualTo(second);
        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(Slots.getAttr(other, "split")).isNotEqualTo(first);
        assertThat(Slots.getAttr(held, "subgroup")).isNotEqualTo(first);
        assertThat(Specials.member(BASE, "split")).isNotEqualTo(first);

        assertThat(Slots.getAttr(NARROWER, "split")).isSameAs(Slots.getAttr(BASE, "split"));
        assertThat(Slots.getAttr(NARROWER, "__new__")).isSameAs(Slots.getAttr(BASE, "__new__"));
    }

    /**
     * What a matcher is allowed to be, which is decided once, before any exception is asked.
     *
     * <p>A class is a type to match against rather than a predicate to call, and that distinction is the
     * only reason {@code split(ValueError)} takes one child of a group of three: called as a predicate it
     * would have made a value of each and matched all of them. An exception instance is the case that
     * shows the two halves of the judgement at once — not callable, and not a type either.
     */
    @Test
    void aClassIsMatchedAgainstAndOnlyANonClassIsCalled() {
        PyObject value = error(ExceptionTypes.VALUE_ERROR, "v");
        PyObject keyboard = error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki");
        PyObject mixed = group(BASE, value, keyboard);

        assertThat(Slots.isCallable(value)).isFalse();
        assertThat(Slots.isCallable(ExceptionTypes.VALUE_ERROR)).isTrue();
        refuses(NO_MATCHER, () -> split(mixed, value));
        refuses(NO_MATCHER, () -> split(mixed, PyInt.ONE));
        refuses(NO_MATCHER, () -> split(mixed, PyStr.of("ValueError")));
        refuses(NO_MATCHER, () -> split(mixed, PyType.INT));
        refuses(NO_MATCHER, () -> split(mixed, PyTuple.of(ExceptionTypes.VALUE_ERROR, PyInt.ONE)));

        // The class and a predicate that names the same thing answer with the same two halves, which is
        // the only reason the two spellings of a matcher are worth telling apart at all.
        String half =
                "(ExceptionGroup('m', [ValueError('v')]), "
                        + "BaseExceptionGroup('m', [KeyboardInterrupt('ki')]))";
        assertThat(split(mixed, ExceptionTypes.VALUE_ERROR)).hasToString(half);

        // A callable that is not a class is the other half, and an empty tuple is a tuple of exception
        // types that names none of them.
        PyNativeFunction judge = matcher(new ArrayList<>(), ExceptionTypes.VALUE_ERROR);
        assertThat(Slots.isCallable(judge)).isTrue();
        assertThat(split(mixed, judge)).hasToString(half);
        assertThat(split(mixed, PyTuple.EMPTY))
                .hasToString("(None, BaseExceptionGroup('m', [ValueError('v'), KeyboardInterrupt('ki')]))");
    }

    /** A matcher Java wrote, standing in for the {@code lambda} a script would have handed over. */
    private static PyNativeFunction matcher(List<String> asked, PyType takes) {
        return new PyNativeFunction(
                "matcher",
                (arguments, kwNames) -> {
                    asked.add(arguments[0].getType().name());
                    return PyBool.of(arguments[0].getType() == takes);
                });
    }

    /**
     * How many times a matcher is called, and what the two halves are made of.
     *
     * <p>The walk asks the group before it asks anything inside it, which is what makes a full match take
     * the whole value rather than rebuild it, and it asks every node of a tree once and in reading order.
     * A leaf that matches travels into its part unchanged: the part is new, its children are not.
     */
    @Test
    void everyNodeIsAskedOnceAndTheLeavesTravelOnUnchanged() {
        List<String> asked = new ArrayList<>();
        PyObject judge = matcher(asked, ExceptionTypes.VALUE_ERROR);

        PyObject value = error(ExceptionTypes.VALUE_ERROR, "v");
        PyObject typeError = error(ExceptionTypes.TYPE_ERROR, "t");
        PyObject key = error(ExceptionTypes.KEY_ERROR, "k");
        PyObject keyboard = error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki");
        PyObject inner = group(BASE, value, typeError);
        PyObject deep = group(BASE, keyboard);
        PyObject nested = group(BASE, inner, key, deep);

        split(nested, judge);
        assertThat(asked)
                .containsExactly(
                        "BaseExceptionGroup",
                        "ExceptionGroup",
                        "ValueError",
                        "TypeError",
                        "KeyError",
                        "BaseExceptionGroup",
                        "KeyboardInterrupt");

        PyObject mixed = group(BASE, value, keyboard);
        PyTuple all = split(mixed, ExceptionTypes.BASE_EXCEPTION);
        assertThat(all.at(0)).isSameAs(mixed);
        assertThat(all.at(1)).isSameAs(PyNone.INSTANCE);

        PyTuple parts = split(mixed, ExceptionTypes.VALUE_ERROR);
        assertThat(parts.at(0)).isNotSameAs(mixed);
        assertThat(childrenOf(parts.at(0))).containsExactly(value);
        assertThat(childrenOf(parts.at(1))).containsExactly(keyboard);
    }

    /**
     * What a part of a split keeps, and how each of the four is written.
     *
     * <p>The metadata is put on the part as the fields CPython's {@code exceptiongroup_subset} writes
     * rather than as assignments a class could intercept, and the cause is written through {@code
     * PyException_SetCause} — the one function that both names a cause and takes the context out of the
     * report. So a part of a group that never asked for its context to be hidden still says
     * {@code __suppress_context__} is true, and a {@code __notes__} that is not a sequence is left out
     * rather than reported from inside a split.
     */
    @Test
    void aPartIsBuiltThroughTheCreatorAndItsMetadataIsWrittenAsFields() {
        PyObject value = error(ExceptionTypes.VALUE_ERROR, "v");
        PyObject keyboard = error(ExceptionTypes.KEYBOARD_INTERRUPT, "ki");
        PyObject mixed = group(BASE, value, keyboard);
        PyObject notes = PyList.of(PyStr.of("one"));
        mixed.setAttr("__notes__", notes);

        PyObject part = subgroup(mixed, ExceptionTypes.VALUE_ERROR);
        assertThat(part.getType()).isSameAs(NARROWER);
        assertThat(Slots.getAttr(part, "message")).hasToString("'m'");
        assertThat(childrenOf(part)).containsExactly(value);
        assertThat(Slots.getAttr(part, "__notes__")).isEqualTo(notes).isNotSameAs(notes);

        assertThat(Slots.getAttr(mixed, "__suppress_context__")).isSameAs(PyBool.FALSE);
        assertThat(Slots.getAttr(part, "__suppress_context__")).isSameAs(PyBool.TRUE);
        assertThat(Slots.getAttr(part, "__cause__")).isSameAs(PyNone.INSTANCE);

        PyObject cause = error(ExceptionTypes.KEY_ERROR, "cause");
        mixed.setAttr("__cause__", cause);
        PyObject again = subgroup(mixed, ExceptionTypes.VALUE_ERROR);
        assertThat(Slots.getAttr(again, "__cause__")).isSameAs(cause);
        assertThat(Slots.getAttr(again, "__context__"))
                .isSameAs(Slots.getAttr(mixed, "__context__"));
        assertThat(Slots.getAttr(again, "__traceback__"))
                .isSameAs(Slots.getAttr(mixed, "__traceback__"));

        PyObject misnoted = group(BASE, value, keyboard);
        misnoted.setAttr("__notes__", PyInt.ONE);
        refuses(
                "AttributeError: 'ExceptionGroup' object has no attribute '__notes__'",
                () -> Slots.getAttr(subgroup(misnoted, ExceptionTypes.VALUE_ERROR), "__notes__"));

        // A part is always built through `BaseExceptionGroup`, so a subclass of the family never gets one
        // of itself — and a subset of nothing is `None` rather than an empty group.
        PyType named = type("Named", new PyType[] {BASE});
        PyObject held = group(named, value, keyboard);
        assertThat(PyBaseExceptionGroup.derive(held, PyList.of(value)).getType()).isSameAs(NARROWER);
        assertThat(subgroup(held, ExceptionTypes.INDEX_ERROR)).isSameAs(PyNone.INSTANCE);
    }

    /**
     * The two refusals an {@code except*} clause makes of its types, in CPython's order.
     *
     * <p>The order is the one a tuple shows: every member is scanned for the first that is not an
     * exception class, and only then for the first that is a group. So {@code except* (int,
     * ExceptionGroup)} complains about {@code int} and never reaches the group it would also have refused
     * — and a class a person wrote under either of the two is refused as a group, in CPython's fixed
     * sentence about {@code ExceptionGroup}, whatever it is named.
     */
    @Test
    void aClauseRefusesItsTypesInTwoScansOfTheTuple() {
        assertThatCode(() -> PyBaseExceptionGroup.checkClause(ExceptionTypes.VALUE_ERROR))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                PyBaseExceptionGroup.checkClause(
                                        PyTuple.of(
                                                ExceptionTypes.VALUE_ERROR,
                                                ExceptionTypes.KEY_ERROR)))
                .doesNotThrowAnyException();

        refuses(
                "TypeError: catching classes that do not inherit from BaseException is not allowed",
                () -> PyBaseExceptionGroup.checkClause(PyType.INT));
        refuses(NO_GROUP_CLAUSE, () -> PyBaseExceptionGroup.checkClause(NARROWER));
        refuses(NO_GROUP_CLAUSE, () -> PyBaseExceptionGroup.checkClause(BASE));
        refuses(
                NO_GROUP_CLAUSE,
                () -> PyBaseExceptionGroup.checkClause(type("Bundle", new PyType[] {NARROWER})));

        refuses(
                "TypeError: catching classes that do not inherit from BaseException is not allowed",
                () -> PyBaseExceptionGroup.checkClause(PyTuple.of(PyType.INT, NARROWER)));
        refuses(
                NO_GROUP_CLAUSE,
                () ->
                        PyBaseExceptionGroup.checkClause(
                                PyTuple.of(ExceptionTypes.VALUE_ERROR, NARROWER)));
    }

    /**
     * Which of the values a clause left behind came out of a handler rather than being raised by one.
     *
     * <p>CPython tells the two apart by metadata alone: an exception that still carries the traceback,
     * cause, context and notes of the group it was split out of is the same one travelling on, and is put
     * back with its position in the tree. Everything else is new, and one new exception leaves the
     * statement alone rather than being wrapped.
     */
    @Test
    void whatLeavesTheStatementOnceEveryClauseHasRun() {
        PyObject value = error(ExceptionTypes.VALUE_ERROR, "v");
        PyObject key = error(ExceptionTypes.KEY_ERROR, "k");
        PyObject group = group(NARROWER, value, key);

        assertThat(PyBaseExceptionGroup.prepareReraise(group, PyList.of(PyNone.INSTANCE, PyNone.INSTANCE)))
                .isSameAs(PyNone.INSTANCE);

        // A value whose metadata still matches the group it came from is put back where it was.
        assertThat(PyBaseExceptionGroup.prepareReraise(group, PyList.of(value)))
                .hasToString("ExceptionGroup('m', [ValueError('v')])");

        PyObject fresh = error(ExceptionTypes.RUNTIME_ERROR, "new");
        fresh.setAttr("__context__", group);
        assertThat(PyBaseExceptionGroup.prepareReraise(group, PyList.of(fresh))).isSameAs(fresh);

        PyObject second = error(ExceptionTypes.RUNTIME_ERROR, "second");
        second.setAttr("__context__", group);
        assertThat(PyBaseExceptionGroup.prepareReraise(group, PyList.of(fresh, second)))
                .hasToString("ExceptionGroup('', [RuntimeError('new'), RuntimeError('second')])");
        assertThat(PyBaseExceptionGroup.prepareReraise(group, PyList.of(fresh, value)))
                .hasToString(
                        "ExceptionGroup('', [RuntimeError('new'), "
                                + "ExceptionGroup('m', [ValueError('v')])])");

        // A value that was never a group at all can only have reached one clause.
        PyObject naked = error(ExceptionTypes.VALUE_ERROR, "naked");
        assertThat(PyBaseExceptionGroup.prepareReraise(naked, PyList.of(naked))).isSameAs(naked);
    }

    /**
     * The three methods called the long way, over a value that is not a group.
     *
     * <p>A {@code method_descriptor} checks its receiver against the class that declares it and complains
     * in that class's words, so {@code BaseExceptionGroup.split(ValueError())} is refused for the receiver
     * and never reaches the matcher. The arity complaint below it names the declarer too, whatever the
     * class of the value it was read from is.
     */
    @Test
    void aReceiverIsCheckedByTheClassThatDeclaresTheMethod() {
        PyObject value = error(ExceptionTypes.VALUE_ERROR, "v");

        refuses(
                "TypeError: unbound method BaseExceptionGroup.split() needs an argument",
                () -> Slots.call(TypeDict.GROUP_SPLIT, new PyObject[0], null));
        refuses(
                "TypeError: descriptor 'split' for 'BaseExceptionGroup' objects doesn't apply to a "
                        + "'ValueError' object",
                () -> Slots.call(TypeDict.GROUP_SPLIT, new PyObject[] {value}, null));
        refuses(
                "TypeError: descriptor 'derive' for 'BaseExceptionGroup' objects doesn't apply to a "
                        + "'str' object",
                () -> Slots.call(TypeDict.GROUP_DERIVE, new PyObject[] {PyStr.of("x")}, null));

        PyType named = type("Named", new PyType[] {BASE});
        PyObject held = group(named, value);
        refuses(
                "TypeError: BaseExceptionGroup.split() takes exactly one argument (0 given)",
                () -> Slots.call(Slots.getAttr(held, "split"), new PyObject[0], null));
        refuses(
                "TypeError: BaseExceptionGroup.subgroup() takes no keyword arguments",
                () ->
                        Slots.call(
                                Slots.getAttr(held, "subgroup"),
                                new PyObject[] {ExceptionTypes.VALUE_ERROR, PyInt.ONE},
                                flag));
        refuses(
                "TypeError: BaseExceptionGroup.derive() takes exactly one argument (2 given)",
                () ->
                        Slots.call(
                                Slots.getAttr(held, "derive"),
                                new PyObject[] {PyList.of(value), PyList.of(value)},
                                null));
    }
}
