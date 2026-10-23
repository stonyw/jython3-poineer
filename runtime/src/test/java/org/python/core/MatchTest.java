package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The flags a pattern match reads and the decisions behind its instructions, measured against
 * CPython 3.14.8.
 *
 * <p>The three corpus scripts run the statement: which subjects a mapping or sequence pattern accepts, in
 * what order the names are bound, and which sentence a refused pattern says. This is the surface a script
 * cannot reach. The compiler picks the instruction from the shape written in the source, so a script cannot
 * ask {@code MATCH_KEYS} for no keys at all — {@code case {}} is compiled to the flag question alone. It
 * cannot show that what a missing key leaves behind is one object reused for every key rather than a value
 * that merely compares equal, and it cannot assert identity, which is the whole of what {@code MATCH_SELF}
 * is about. The flags themselves it can only name one type at a time, through the pattern that type happens
 * to accept.
 *
 * <p>The classes here are made by {@link Classes#newType} and their members are the Java-written kinds
 * {@link DescriptorsTest} uses, so every assertion is about the match rather than about the compiler that
 * would normally supply it.
 */
class MatchTest {

    /** {@code class name: ...} — {@code type(name, (object,), {members})}. */
    private static PyType type(String name, Object... members) {
        return made(name, PyTuple.of(PyType.OBJECT), members);
    }

    /** {@code class name(base): ...} */
    private static PyType derived(String name, PyType base, Object... members) {
        return made(name, PyTuple.of(base), members);
    }

    private static PyType made(String name, PyTuple bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), bases, dict}, null);
    }

    /** {@code C()} — a value of a class Python wrote. */
    private static PyObject value(PyType kind) {
        return kind.call(new PyObject[0], null);
    }

    /** The {@code LOAD_CONST} operand of a {@code MATCH_CLASS}: the keyword names, in the order given. */
    private static PyTuple names(String... spelled) {
        PyObject[] asValues = new PyObject[spelled.length];
        for (int i = 0; i < spelled.length; i++) {
            asValues[i] = PyStr.of(spelled[i]);
        }
        return PyTuple.of(asValues);
    }

    /** The same operand of a {@code MATCH_KEYS}: the keys the pattern spells, in that order. */
    private static PyTuple keys(String... spelled) {
        return names(spelled);
    }

    /** {@code @property def name(self)} that records the read and answers with the name it was read as. */
    private static PyProperty recording(String label, List<String> read) {
        return new PyProperty(
                new PyNativeFunction(
                        label,
                        (args, kwNames) -> {
                            read.add(label);
                            return PyStr.of(label);
                        }),
                PyNone.INSTANCE,
                PyNone.INSTANCE,
                PyNone.INSTANCE);
    }

    /** {@code TypeError} and {@code ValueError} in CPython's own words, which the runtime repeats. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The two flags a subject is asked about before any key or element is read, for every type here.
     *
     * <p>Two of them are worth reading twice. {@code range} carries the sequence bit and no
     * {@code MATCH_SELF}, which is why the corpus can unpack one as {@code [first, second]} while
     * {@code case range(x)} is refused. {@code str} carries {@code MATCH_SELF} and not the sequence bit,
     * which is why {@code case str(text)} answers and {@code case [a, b]} over text does not even ask how
     * long it is. A class Python wrote carries neither, however well it answers {@code __getitem__} and
     * {@code __len__} — the corpus script shows a subject that refuses both patterns without ever being
     * asked either question.
     */
    @Test
    void onlyTheTypesThatSaySoAreASequenceOrAMapping() {
        assertThat(PyType.LIST.hasFeature(PyType.SEQUENCE)).isTrue();
        assertThat(PyType.TUPLE.hasFeature(PyType.SEQUENCE)).isTrue();
        assertThat(PyType.RANGE.hasFeature(PyType.SEQUENCE)).isTrue();
        assertThat(PyType.STR.hasFeature(PyType.SEQUENCE)).isFalse();
        assertThat(PyType.BYTES.hasFeature(PyType.SEQUENCE)).isFalse();
        assertThat(PyType.SET.hasFeature(PyType.SEQUENCE)).isFalse();
        assertThat(PyType.DICT.hasFeature(PyType.SEQUENCE)).isFalse();
        assertThat(PyType.OBJECT.hasFeature(PyType.SEQUENCE)).isFalse();

        assertThat(PyType.DICT.hasFeature(PyType.MAPPING)).isTrue();
        assertThat(PyType.FRAME.hasFeature(PyType.MAPPING)).isTrue();
        assertThat(PyType.LIST.hasFeature(PyType.MAPPING)).isFalse();
        assertThat(PyType.SET.hasFeature(PyType.MAPPING)).isFalse();

        PyType written = type("Plain");
        assertThat(written.hasFeature(PyType.MAPPING)).isFalse();
        assertThat(written.hasFeature(PyType.SEQUENCE)).isFalse();
        assertThat(derived("Sub", written).hasFeature(PyType.SEQUENCE)).isFalse();
    }

    /**
     * The bit that answers a class pattern's one positional by handing the subject back.
     *
     * <p>The list is CPython's, read off which static types declare the flag, and the inheritance is the
     * part a script can only show one type at a time: {@code bool} declares nothing and carries the bit
     * because {@code int} does, which is the same walk a member takes. A written class has no way to ask
     * for it — the one route CPython gives it is {@code collections.abc} registration, which needs the
     * standard library this runtime does not import yet.
     */
    @Test
    void theBuiltInsWithNoMatchArgsOfTheirOwnHandTheSubjectBack() {
        for (PyType self : new PyType[] {
            PyType.LIST, PyType.TUPLE, PyType.STR, PyType.BYTES, PyType.SET,
            PyType.FROZENSET, PyType.DICT, PyType.INT, PyType.FLOAT, PyType.BOOL
        }) {
            assertThat(self.hasFeature(PyType.MATCH_SELF)).as(self.name()).isTrue();
        }
        for (PyType notSelf : new PyType[] {
            PyType.RANGE, PyType.COMPLEX, PyType.SLICE, PyType.FRAME, PyType.TYPE, PyType.OBJECT
        }) {
            assertThat(notSelf.hasFeature(PyType.MATCH_SELF)).as(notSelf.name()).isFalse();
        }
        assertThat(type("Plain").hasFeature(PyType.MATCH_SELF)).isFalse();
    }

    /** {@code MATCH_KEYS} for no keys, which the compiler never emits because {@code case {}} asks the flag. */
    @Test
    void aPatternNamingNoKeysDoesNotEvenLookTheGetterUp() {
        // A subject with no `get` anywhere: were the instruction to read the name before counting the keys,
        // this would be an AttributeError rather than an answer.
        PyObject held = value(type("NoGetter"));

        assertThat(Interpreter.matchedKeys(held, PyTuple.EMPTY)).isSameAs(PyTuple.EMPTY);
    }

    /** Every key is asked of the subject's own {@code get}, with one default handed to all of them. */
    @Test
    void eachKeyIsHandedToGetWithTheSameDefaultEveryTime() {
        Map<String, PyObject> there = new HashMap<>();
        there.put("first", PyStr.of("one"));
        there.put("second", PyStr.of("two"));
        List<PyObject> handed = new ArrayList<>();
        PyObject held = value(type("Answers"));
        held.setAttr(
                "get",
                new PyNativeFunction(
                        "get",
                        (args, kwNames) -> {
                            handed.add(args[1]);
                            return there.getOrDefault(((PyStr) args[0]).getValue(), args[1]);
                        }));

        PyTuple values = (PyTuple) Interpreter.matchedKeys(held, keys("first", "second"));

        assertThat(values.size()).isEqualTo(2);
        assertThat(values.at(0)).isSameAs(there.get("first"));
        assertThat(values.at(1)).isSameAs(there.get("second"));
        assertThat(handed).hasSize(2);
        assertThat(handed.get(1)).isSameAs(handed.get(0));
    }

    /**
     * What marks a key as missing is identity with the default the getter was handed, not the value.
     *
     * <p>So a getter that answers with its second argument every time — which is what a Python-written one
     * does for a key it does not hold — fails the match even for the keys that are there. This is why
     * CPython insists on the two-argument call rather than a lookup and a {@code KeyError}: a mapping that
     * creates a key on a miss never gets the chance to.
     */
    @Test
    void aGetterThatAnswersWithTheDefaultItWasGivenIsAbsentNotAValue() {
        PyObject held = value(type("AlwaysMissing"));
        held.setAttr("get", new PyNativeFunction("get", (args, kwNames) -> args[1]));

        assertThat(Interpreter.matchedKeys(held, keys("present"))).isSameAs(PyNone.INSTANCE);

        PyDict dict = PyDict.empty();
        PyObject one = PyStr.of("one");
        dict.put(PyStr.of("present"), one);
        assertThat(((PyTuple) Interpreter.matchedKeys(dict, keys("present"))).at(0))
                .as("a dict's own getter answers for a key it holds")
                .isSameAs(one);
        assertThat(Interpreter.matchedKeys(dict, keys("present", "absent")))
                .as("and the default it was handed for one it does not")
                .isSameAs(PyNone.INSTANCE);
    }

    /**
     * The matcher is asked before the subject, whatever the pattern names inside it.
     *
     * <p>{@code case not_a_class} on its own is a capture, not a class pattern, so the refusal can only
     * arrive through parentheses — and it arrives for {@code case not_a_class()} that names nothing as
     * surely as for a pattern full of them, because the check comes before the count and before the
     * instance question. A value that is not a class is an error; a subject that is not an instance of one
     * is a failed match.
     */
    @Test
    void aMatcherThatIsNotAClassIsRefusedBeforeTheSubjectIsAsked() {
        PyObject subject = PyStr.of("a subject");

        refuses(
                "TypeError: called match pattern must be a class",
                () -> Interpreter.matchedAttributes(subject, PyStr.of("NotAClass"), PyTuple.EMPTY, 0));
        refuses(
                "TypeError: called match pattern must be a class",
                () -> Interpreter.matchedAttributes(subject, value(type("NotAClass")), names("any"), 1));
        refuses(
                "TypeError: called match pattern must be a class",
                () -> Interpreter.matchedAttributes(subject, value(type("NotAClass")), PyTuple.EMPTY, 0));
    }

    /** The instance question fails the pattern instead of raising, and reads nothing on the way. */
    @Test
    void aSubjectOfTheWrongClassEndsTheMatchWithoutAskingAnything() {
        List<String> read = new ArrayList<>();
        PyType base = type("Base", "first", recording("first", read));
        PyType other = type("Other");
        PyType child = derived("Child", base);

        PyTuple got = (PyTuple) Interpreter.matchedAttributes(value(child), base, names("first"), 0);
        assertThat(got.size()).isEqualTo(1);
        assertThat(read).containsExactly("first");

        assertThat(Interpreter.matchedAttributes(value(other), base, names("first"), 0))
                .as("an unrelated class fails the pattern, it is not an error")
                .isSameAs(PyNone.INSTANCE);
        assertThat(Interpreter.matchedAttributes(value(base), child, names("first"), 0))
                .as("a parent is not an instance of its child")
                .isSameAs(PyNone.INSTANCE);
        assertThat(read).as("and neither of the two asked the attributes anything")
                .containsExactly("first");
    }

    /**
     * A type with the bit answers one positional with the subject itself, and refuses a second.
     *
     * <p>The value in the tuple is the subject by identity, which is what lets the corpus bind the very
     * object the statement switched on rather than a copy of it. The count the bit allows is one, so the
     * refusal says it in the singular — and the same sentence in the plural for a class that allows none,
     * which is what a written class with no {@code __match_args__} is.
     */
    @Test
    void aTypeThatHandsItselfBackTakesExactlyOnePositionalSubPattern() {
        PyObject subject = PyStr.of("the subject itself");

        PyTuple got = (PyTuple) Interpreter.matchedAttributes(subject, PyType.STR, PyTuple.EMPTY, 1);
        assertThat(got.size()).isEqualTo(1);
        assertThat(got.at(0)).isSameAs(subject);
        assertThat(((PyTuple) Interpreter.matchedAttributes(PyBool.TRUE, PyType.BOOL, PyTuple.EMPTY, 1)).at(0))
                .as("and a bool through the bit it inherits from int")
                .isSameAs(PyBool.TRUE);

        refuses(
                "TypeError: str() accepts 1 positional sub-pattern (2 given)",
                () -> Interpreter.matchedAttributes(subject, PyType.STR, PyTuple.EMPTY, 2));
        refuses(
                "TypeError: bool() accepts 1 positional sub-pattern (2 given)",
                () -> Interpreter.matchedAttributes(PyBool.TRUE, PyType.BOOL, names("any"), 2));

        PyType none = type("Empty");
        refuses(
                "TypeError: Empty() accepts 0 positional sub-patterns (1 given)",
                () -> Interpreter.matchedAttributes(value(none), none, PyTuple.EMPTY, 1));
        refuses(
                "TypeError: Empty() accepts 0 positional sub-patterns (2 given)",
                () -> Interpreter.matchedAttributes(value(none), none, names("any"), 2));
    }

    /** What {@code __match_args__} has to be, and what is said when it is not. */
    @Test
    void theDeclaredOrderMustBeATupleOfNames() {
        PyType asList = type("AsList", "__match_args__", PyList.of(PyStr.of("first")));
        PyType asText = type("AsText", "__match_args__", PyStr.of("first"));
        PyType asDict = type("AsDict", "__match_args__", PyDict.empty());
        PyType number = type("Number", "__match_args__", PyTuple.of(PyInt.of(1)));

        refuses(
                "TypeError: AsList.__match_args__ must be a tuple (got list)",
                () -> Interpreter.matchedAttributes(value(asList), asList, PyTuple.EMPTY, 1));
        refuses(
                "TypeError: AsText.__match_args__ must be a tuple (got str)",
                () -> Interpreter.matchedAttributes(value(asText), asText, PyTuple.EMPTY, 1));
        refuses(
                "TypeError: AsDict.__match_args__ must be a tuple (got dict)",
                () -> Interpreter.matchedAttributes(value(asDict), asDict, PyTuple.EMPTY, 1));
        refuses(
                "TypeError: __match_args__ elements must be strings (got int)",
                () -> Interpreter.matchedAttributes(value(number), number, PyTuple.EMPTY, 1));
    }

    /**
     * The positionals named through {@code __match_args__} are read before the keywords.
     *
     * <p>Both halves go through the same attribute read, and the order they are read in is the order the
     * values come back in, which is what the {@code UNPACK_SEQUENCE} that follows the instruction counts. A
     * name the subject does not have ends the match where it is reached: the second class below fails on
     * its positional and is never asked for the keyword written next to it.
     */
    @Test
    void thePositionalsAreReadFirstAndTheKeywordsAfterInPatternOrder() {
        List<String> read = new ArrayList<>();
        PyType ordered =
                type(
                        "Ordered",
                        "__match_args__",
                        PyTuple.of(PyStr.of("first")),
                        "first",
                        recording("first", read),
                        "second",
                        recording("second", read),
                        "third",
                        recording("third", read));

        PyTuple got =
                (PyTuple)
                        Interpreter.matchedAttributes(
                                value(ordered), ordered, names("third", "second"), 1);

        assertThat(read).containsExactly("first", "third", "second");
        assertThat(got.size()).isEqualTo(3);
        assertThat(((PyStr) got.at(0)).getValue()).isEqualTo("first");
        assertThat(((PyStr) got.at(2)).getValue()).isEqualTo("second");

        List<String> half = new ArrayList<>();
        PyType missing =
                type(
                        "Missing",
                        "__match_args__",
                        PyTuple.of(PyStr.of("absent")),
                        "present",
                        recording("present", half));
        assertThat(Interpreter.matchedAttributes(value(missing), missing, names("present"), 1))
                .isSameAs(PyNone.INSTANCE);
        assertThat(half).isEmpty();
    }

    /** Two sub-patterns for one attribute is refused in CPython's words, with the name shown as a repr. */
    @Test
    void theSameAttributeTwiceEndsTheCall() {
        PyType repeated =
                type(
                        "Args",
                        "__match_args__",
                        PyTuple.of(PyStr.of("first")),
                        "first",
                        PyStr.of("a value"));

        refuses(
                "TypeError: Args() got multiple sub-patterns for attribute 'first'",
                () -> Interpreter.matchedAttributes(value(repeated), repeated, names("first"), 1));
    }

    /** A name the subject has but cannot answer is an error the match does not swallow. */
    @Test
    void aReadThatRaisesEscapesTheMatch() {
        List<String> read = new ArrayList<>();
        PyType raising =
                type(
                        "Guarded",
                        "__match_args__",
                        PyTuple.of(PyStr.of("first")),
                        "first",
                        new PyProperty(
                                new PyNativeFunction(
                                        "first",
                                        (args, kwNames) -> {
                                            throw Slots.valueError("the attribute refused");
                                        }),
                                PyNone.INSTANCE,
                                PyNone.INSTANCE,
                                PyNone.INSTANCE),
                        "second",
                        recording("second", read));

        refuses(
                "ValueError: the attribute refused",
                () -> Interpreter.matchedAttributes(value(raising), raising, names("second"), 1));
        assertThat(read).isEmpty();
    }
}
