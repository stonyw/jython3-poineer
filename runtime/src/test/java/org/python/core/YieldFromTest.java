package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The judgements {@code yield from} is built out of, measured against CPython 3.14.8.
 *
 * <p>The corpus scripts run the statement: which values the delegate gives back, what its return becomes,
 * and which sentence a delegate that cannot be stepped is refused with. This is the machinery under that,
 * which no script can point at — a program has no way to ask the difference between {@code getattr} and
 * {@code GetOptionalAttr}, and the field an ending reads is only ever consulted on the branch that ends
 * the delegation, so the same question asked of any other exception is a question the statement never
 * asks. Every class here is made by {@link Classes#newType} and every member Java wrote, so each assertion
 * is about the judgement rather than about the compiler that would normally supply it.
 */
class YieldFromTest {

    /** {@code type(name, bases, {members})}, with the bases handed over as Java has them. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType) Classes.newType(PyType.TYPE, new PyObject[] {
            PyStr.of(name), PyTuple.of(bases), dict
        }, null);
    }

    /** {@code class Name:} with only the members named here in its body. */
    private static PyType written(String name, Object... members) {
        return type(name, new PyType[] {PyType.OBJECT}, members);
    }

    /** A method Java wrote, standing in for the one Python would have named in the class body. */
    private static PyNativeFunction function(String name, PyObject answer) {
        return new PyNativeFunction(name, (args, kwNames) -> answer);
    }

    private static PyObject value(PyType kind) {
        return kind.call(new PyObject[0], null);
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

    /** {@code GET_YIELD_FROM_ITER}: the iterator is the answer, and an iterable is only the question. */
    @Test
    void anIterableBecomesAnIteratorAndAnIteratorIsAlreadyOne() {
        PyList items = PyList.of(PyStr.of("first"), PyStr.of("second"));

        PyObject first = Slots.getIter(items);
        assertThat(first).isNotSameAs(items);
        assertThat(Slots.asIterator(first)).isSameAs(first);

        // The second question asks the same list and gets a different iterator: what a delegation stands
        // on is decided once, by the instruction, and the loop the compiler wrote never re-asks `__iter__`.
        assertThat(Slots.getIter(items)).isNotSameAs(first);

        // Asked of an iterator, `iter()` answers with the iterator itself, so the delegation the
        // instruction starts stands on the very object it was handed — which is what lets a caller that
        // holds the same iterator see the steps the delegation took.
        assertThat(Slots.getIter(first)).isSameAs(first);
    }

    /** The refusal {@code PyObject_GetIter} makes when there is no iteration slot to read. */
    @Test
    void aDelegateThatIsNeitherAnIteratorNorAnIterableIsRefusedByItsOwnName() {
        refuses("TypeError: 'int' object is not iterable", () -> Slots.getIter(PyInt.ZERO));
        refuses(
                "TypeError: 'Plain' object is not iterable",
                () -> Slots.getIter(value(written("Plain"))));

        // The other refusal — `__iter__` kept and the answer not an iterator — belongs to a class Python
        // wrote, and the corpus pins it there: Specials drives only a Python-level body, so a member Java
        // wrote cannot be the one a value's iteration slot answers with.
    }

    /** {@code GetOptionalAttr}, the read {@code close} and {@code throw} make of a delegate. */
    @Test
    void anOptionalMemberIsAnsweredWithNullRatherThanWithARefusal() {
        PyType onlyThrow = written("OnlyThrow", "throw", function("throw", PyStr.of("thrown")));
        PyObject holder = value(onlyThrow);

        assertThat(Slots.findAttr(holder, "throw")).isNotNull();
        assertThat(Slots.findAttr(holder, "close")).isNull();

        // The finding is the difference, not the answer: the same lookup that refuses for a reader that
        // asked for the name says nothing here, which is what lets a delegate with no {@code close} be
        // closed by the frame instead of refusing the close.
        refuses(
                "AttributeError: 'OnlyThrow' object has no attribute 'close'",
                () -> Slots.getAttr(holder, "close"));

        // Inherited members count, because this is the ordinary lookup with the refusal taken away.
        PyType derived = type("DerivedThrow", new PyType[] {onlyThrow});
        assertThat(Slots.findAttr(value(derived), "throw")).isNotNull();
    }

    /** The value the delegation ends with, which is the one field {@code StopIteration} carries. */
    @Test
    void theValueIsTakenAtTheConstructionAndNotAtTheReading() {
        assertThat(make(ExceptionTypes.STOP_ITERATION, "one").value()).isEqualTo(PyStr.of("one"));

        // A tuple given to the creator stays a tuple. This is why the value is read from the arguments
        // rather than off an exception raised from a type and a value: {@code StopIteration(("a", "b"))}
        // hands the delegation the pair, not its first item.
        PyObject pair = PyTuple.of(PyStr.of("a"), PyStr.of("b"));
        assertThat(make(ExceptionTypes.STOP_ITERATION, pair).value()).isSameAs(pair);

        assertThat(make(ExceptionTypes.STOP_ITERATION).value()).isSameAs(PyNone.INSTANCE);

        // A subclass has the member because it derives from the class that does, not because of the name
        // it was given.
        PyType derived = type("End", new PyType[] {ExceptionTypes.STOP_ITERATION});
        assertThat(make(derived, "carried").value()).isEqualTo(PyStr.of("carried"));
    }

    /** {@code args} and {@code value} are two fields, and the second one is written only by the creator. */
    @Test
    void rewritingTheArgumentsLeavesTheValueWhereItWas() {
        PyBaseException exception = make(ExceptionTypes.STOP_ITERATION, "first");
        exception.setArguments(new PyObject[] {PyStr.of("second"), PyStr.of("third")});

        assertThat(exception.args()).isEqualTo(PyTuple.of(PyStr.of("second"), PyStr.of("third")));
        // The display changes and the delegation does not: {@code value} is StopIteration's own member,
        // and the args setter is BaseException's, which does not know the field exists.
        assertThat(exception.value()).isEqualTo(PyStr.of("first"));
    }

    /** A refusal to end is not an ending: only {@code StopIteration} has a value to hand back. */
    @Test
    void anExceptionThatIsNotASignOfTheEndHasNoValue() {
        PyBaseException failed = make(ExceptionTypes.VALUE_ERROR, "nothing");

        // CLEANUP_THROW asks the question of whatever was thrown in, so an exception that merely has an
        // argument is answered with None — and the branch that reads it only runs for a StopIteration,
        // which is why a script can never see the difference.
        assertThat(failed.value()).isSameAs(PyNone.INSTANCE);
        assertThat(failed.args()).isEqualTo(PyTuple.of(PyStr.of("nothing")));

        // Asking for the member the way a program does is refused, even though the field answers here:
        // the member belongs to StopIteration and nothing else has it.
        refuses(
                "AttributeError: 'ValueError' object has no attribute 'value'",
                () -> Slots.getAttr(failed, "value"));
    }

    /** {@code Name(*arguments)} put through the creator, as a program writes it. */
    private static PyBaseException make(PyType kind, PyObject... arguments) {
        return (PyBaseException) kind.call(arguments, null);
    }

    /** {@code Name(text)} for an argument spelled as Java has it. */
    private static PyBaseException make(PyType kind, String argument) {
        return make(kind, PyStr.of(argument));
    }
}
