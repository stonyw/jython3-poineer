package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * A generator as the runtime holds it: a frame that is not on the stack, and the state that says so.
 *
 * <p>The corpus scripts read a generator through the names Python gives it — {@code gi_frame},
 * {@code gi_suspended}, the answers {@code next} and {@code send} hand back. What no script can reach is
 * the frame itself between those answers: where its instruction pointer is parked, whether it counts as
 * finished, what its slots hold while nothing runs it, and whether the caller that made it is still
 * reachable from it. Those are the whole of what a generator is, and every other difference between this
 * and a plain call follows from them.
 *
 * <p>Two contracts with the driver are pinned here as well, because both are invisible to Python and both
 * are easy to get wrong from memory. A yield is a {@code return} from the dispatch loop that must not
 * finish the frame it parks — CPython's {@code YIELD_VALUE} is careful never to raise for exactly that
 * reason — and a returned value ends iteration silently, becoming a {@code StopIteration} only for the
 * one caller that asks for an exception. A {@code for} loop takes the first answer, and so discards the
 * value.
 *
 * <p>Every expectation is what CPython 3.14.8 does, read from {@code genobject.c}, {@code bytecodes.c}
 * and {@code frame.c}, and confirmed against the reference interpreter. The tests live in this module
 * rather than the runtime's own for the reason {@link TracebackReportTest} gives: a frame is only worth
 * inspecting once something compiled it, and decision D2 keeps the compiler out of
 * {@code org.python.core}.
 */
class GeneratorFrameTest {

    @TempDir
    Path work;

    @AfterEach
    void leaveNoHandlerBehind() {
        // A handler left installed by one test answers for the next one's sys.exc_info().
        PyRuntime.setHandledException(PyNone.INSTANCE);
    }

    // --- making one ------------------------------------------------------------------------

    @Test
    void theCallCopiesTheStartingFrameAndDropsTheCaller() throws Exception {
        PyModule module =
                run(
                        "closure.py",
                        """
                        def make(shared):
                            def body(other):
                                yield shared
                                yield other

                            return body

                        factory = make('the cell')
                        first = factory('the argument')
                        """);
        PyGenerator generator = (PyGenerator) module.get("first");
        Frame frame = generator.frame();

        assertThat(generator.getType()).isSameAs(PyType.GENERATOR);
        // Not a name Python has either: `generator` is not in builtins, as `frame` and `code` are not.
        assertThat(PyRuntime.builtins().get(PyStr.interned("generator"))).isNull();
        assertThat(frame.code().flags() & PyCode.CO_GENERATOR).isNotZero();

        assertThat(generator.state()).isEqualTo(PyGenerator.State.CREATED);
        // The frame the call made was popped and this copy taken from it: nothing of the call is left.
        assertThat(frame.back()).isNull();
        assertThat(frame.stackDepth()).isZero();
        assertThat(frame.isFinished()).isFalse();
        assertThat(frame.globals()).isSameAs(module.dictionary());
        assertThat(frame.function).isSameAs(module.get("factory"));

        // Parked on the POP_TOP that follows RETURN_GENERATOR, which is where the first entry lands.
        assertThat(frame.instructions().opcodeAt(frame.ip())).isEqualTo(Opcodes.POP_TOP);

        assertThat(text(frame.slot(0))).isEqualTo("the argument");
        // A closure is shared, not copied: the copy holds the very cell the enclosing frame made.
        assertThat(frame.slot(frame.code().localCount() - 1))
                .isSameAs(reach(module, "factory.__closure__[0]"));
    }

    @Test
    void aJustStartedGeneratorRefusesAValueWithoutEnteringItsFrame() throws Exception {
        PyModule module =
                run(
                        "echo.py",
                        """
                        def echo(start):
                            yield start
                            yield 'second'
                        """);
        PyGenerator generator = (PyGenerator) reach(module, "echo(1)");
        Frame frame = generator.frame();
        int parkedAt = frame.ip();

        refuses(
                "TypeError: can't send non-None value to a just-started generator",
                () -> send(generator, PyInt.of(7)));

        // The refusal is before the entry: CPython pushes the argument only for a call that runs.
        assertThat(generator.state()).isEqualTo(PyGenerator.State.CREATED);
        assertThat(frame.ip()).isEqualTo(parkedAt);
        assertThat(frame.stackDepth()).isZero();

        assertThat(step(generator)).isSameAs(PyInt.of(1));
        assertThat(generator.state()).isEqualTo(PyGenerator.State.SUSPENDED);
        assertThat(text(step(generator))).isEqualTo("second");
    }

    // --- driving one -----------------------------------------------------------------------

    @Test
    void aYieldParksTheFrameAtTheResumeThatFollowsIt() throws Exception {
        PyModule module =
                run(
                        "tick.py",
                        """
                        def tick():
                            yield 1
                            yield 2
                        """);
        PyGenerator generator = (PyGenerator) reach(module, "tick()");
        Frame frame = generator.frame();

        assertThat(step(generator)).isSameAs(PyInt.of(1));
        // The one thing a yield may not do: finish the frame it is parking.
        assertThat(frame.isFinished()).isFalse();
        assertThat(generator.state()).isEqualTo(PyGenerator.State.SUSPENDED);
        assertThat(frame.instructions().opcodeAt(frame.ip())).isEqualTo(Opcodes.RESUME);
        assertThat(frame.stackDepth()).isZero();
        assertThat(generator.getAttr("gi_frame")).isInstanceOf(PyFrame.class);

        assertThat(step(generator)).isSameAs(PyInt.of(2));
        assertThat(frame.isFinished()).isFalse();

        assertThat(step(generator)).isNull();
        assertThat(generator.state()).isEqualTo(PyGenerator.State.COMPLETED);
        assertThat(frame.isFinished()).isTrue();
        // A generator that has run out has no frame left for Python to reach, however it ran out.
        assertThat(generator.getAttr("gi_frame")).isSameAs(PyNone.INSTANCE);
    }

    @Test
    void resumptionRunsOnTheValueTheCallerHandedIn() throws Exception {
        PyModule module =
                run(
                        "gather.py",
                        """
                        collected = []

                        def gather(start):
                            value = start
                            while True:
                                collected.append(value)
                                value = yield value
                        """);
        PyGenerator generator = (PyGenerator) reach(module, "gather('first')");
        Frame frame = generator.frame();

        assertThat(text(step(generator))).isEqualTo("first");
        // The first entry pushed None, and the POP_TOP a generator starts on discarded it.
        assertThat(text(slot(frame, "value"))).isEqualTo("first");

        assertThat(text(send(generator, PyStr.of("second")))).isEqualTo("second");
        // What the yield expression is worth is what the frame holds once the assignment has run.
        assertThat(text(slot(frame, "value"))).isEqualTo("second");
        assertThat(frame.stackDepth()).isZero();
        assertThat(Slots.repr(reach(module, "collected")))
                .isEqualTo("['first', 'second']");
    }

    @Test
    void aReturnEndsIterationAndHandsItsValueToWhoeverAsksFirst() throws Exception {
        PyModule module =
                run(
                        "back.py",
                        """
                        def back():
                            yield 1
                            return 42

                        looped = back()
                        next(looped)
                        for entry in looped:
                            pass
                        try:
                            next(looped)
                        except StopIteration as stop:
                            discarded = stop.args
                        """);

        PyGenerator generator = (PyGenerator) reach(module, "back()");
        assertThat(step(generator)).isSameAs(PyInt.of(1));
        // Iteration ended with no exception at all: this is the answer a `for` reads and stops on.
        assertThat(step(generator)).isNull();

        PyBaseException stopped = generator.exhaustion();
        assertThat(stopped).isNotNull();
        assertThat(stopped.exceptionType()).isSameAs(ExceptionTypes.STOP_ITERATION);
        assertThat(stopped.getAttr("value")).isSameAs(PyInt.of(42));
        // Handed over once, because CPython builds the exception from the frame and discards the record.
        assertThat(generator.exhaustion()).isNull();

        // The loop above was the one that asked, so nothing was left for the caller after it.
        assertThat(reach(module, "len(discarded)")).isSameAs(PyInt.of(0));
    }

    // --- what comes out of a body ----------------------------------------------------------

    @Test
    void onlyAStopIterationEscapingABodyIsRebuilt() throws Exception {
        PyModule module =
                run(
                        "converts.py",
                        """
                        made = None

                        def converts():
                            raise StopIteration('the original')
                            yield

                        def ordinary():
                            global made
                            made = IndexError('plain')
                            raise made
                            yield
                        """);

        assertThatThrownBy(() -> step((PyGenerator) reach(module, "converts()")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> {
                            assertThat(raised.getMessage())
                                    .isEqualTo("RuntimeError: generator raised StopIteration");
                            // Both halves of the chain, and the very same exception in each of them.
                            PyBaseException original =
                                    (PyBaseException) raised.value().getAttr("__cause__");
                            assertThat(original).isSameAs(raised.value().getAttr("__context__"));
                            assertThat(original.exceptionType())
                                    .isSameAs(ExceptionTypes.STOP_ITERATION);
                            assertThat(text(original.getAttr("args")))
                                    .isEqualTo("('the original',)");
                        });

        assertThatThrownBy(() -> step((PyGenerator) reach(module, "ordinary()")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> {
                            // The intrinsic answered with the exception it was given, untouched.
                            assertThat(raised.value()).isSameAs(module.get("made"));
                            assertThat(raised.value().getAttr("__cause__"))
                                    .isSameAs(PyNone.INSTANCE);
                            assertThat(raised.value().getAttr("__context__"))
                                    .isSameAs(PyNone.INSTANCE);
                        });
    }

    // --- the ways in, and the ones that are refused ----------------------------------------

    @Test
    void aRefusedThrowLeavesTheFrameExactlyWhereItWas() throws Exception {
        PyModule module =
                run(
                        "silent.py",
                        """
                        def silent():
                            yield 1
                            yield 2
                        """);
        PyGenerator generator = (PyGenerator) reach(module, "silent()");
        Frame frame = generator.frame();
        step(generator);
        int parkedAt = frame.ip();

        refuses(
                "TypeError: exceptions must be classes or instances"
                        + " deriving from BaseException, not str",
                () -> throwInto(generator, PyStr.of("text")));
        refuses(
                "TypeError: generator.throw() takes no keyword arguments",
                () ->
                        Slots.call(
                                member(generator, "throw"),
                                new PyObject[] {ExceptionTypes.VALUE_ERROR, PyNone.INSTANCE},
                                PyTuple.of(PyStr.of("x"))));
        refuses(
                "TypeError: throw expected at least 1 argument, got 0",
                () -> throwWith(generator, new PyObject[0]));
        refuses(
                "TypeError: throw expected at most 3 arguments, got 4",
                () ->
                        throwWith(
                                generator,
                                new PyObject[] {
                                    ExceptionTypes.VALUE_ERROR,
                                    PyNone.INSTANCE,
                                    PyNone.INSTANCE,
                                    PyNone.INSTANCE
                                }));

        assertThat(generator.state()).isEqualTo(PyGenerator.State.SUSPENDED);
        assertThat(frame.ip()).isEqualTo(parkedAt);
        assertThat(frame.stackDepth()).isZero();
        assertThat(step(generator)).isSameAs(PyInt.of(2));
    }

    @Test
    void aFrameThatNeverRanIsFinishedByCloseOrByClearAndAnswersNothingAfter() throws Exception {
        PyModule module =
                run(
                        "counted.py",
                        """
                        made = []

                        def counted():
                            made.append('ran')
                            yield 1

                        closed = counted()
                        cleared = counted()
                        parked = counted()
                        """);

        PyGenerator closed = (PyGenerator) module.get("closed");
        assertThat(call(closed, "close")).isSameAs(PyNone.INSTANCE);
        // A created generator is simply done: the body never entered, so not even a finally runs.
        assertThat(reach(module, "made")).isInstanceOfSatisfying(
                PyList.class, seen -> assertThat(seen.getItems()).isEmpty());
        assertThat(closed.state()).isEqualTo(PyGenerator.State.COMPLETED);
        // Nothing ran, so the frame is still parked where the copy left it.
        assertThat(closed.frame().instructions().opcodeAt(closed.frame().ip()))
                .isEqualTo(Opcodes.POP_TOP);
        assertThat(closed.getAttr("gi_frame")).isSameAs(PyNone.INSTANCE);
        assertThat(closed.frame().isFinished()).isTrue();

        // Clearing the frame by hand reaches the same end, for the same reason: there is nothing left
        // to run, so the generator is finished and its frame is not worth a name.
        PyGenerator cleared = (PyGenerator) module.get("cleared");
        assertThat(cleared.getAttr("gi_frame")).isInstanceOf(PyFrame.class);
        assertThat(reach(module, "cleared.gi_frame.clear()")).isSameAs(PyNone.INSTANCE);
        assertThat(cleared.state()).isEqualTo(PyGenerator.State.COMPLETED);
        assertThat(cleared.getAttr("gi_frame")).isSameAs(PyNone.INSTANCE);
        assertThat(step(cleared)).isNull();

        PyGenerator parked = (PyGenerator) module.get("parked");
        assertThat(step(parked)).isSameAs(PyInt.of(1));
        refuses(
                "RuntimeError: cannot clear a suspended frame",
                () -> reach(module, "parked.gi_frame.clear()"));
        assertThat(parked.state()).isEqualTo(PyGenerator.State.SUSPENDED);
        assertThat(step(parked)).isNull();
    }

    // --- the exception a generator is handling ---------------------------------------------

    @Test
    void aParkedGeneratorKeepsItsOwnHandlerStateAndLeavesTheCallersAlone() throws Exception {
        PyModule module =
                run(
                        "guarded.py",
                        """
                        def guarded():
                            try:
                                yield 1
                            except TypeError:
                                yield 2

                        def plain():
                            yield 1
                            yield 2
                        """);
        PyGenerator generator = (PyGenerator) reach(module, "guarded()");

        assertThat(step(generator)).isSameAs(PyInt.of(1));
        assertThat(PyRuntime.handledException()).isSameAs(PyNone.INSTANCE);

        assertThat(throwInto(generator, newException(ExceptionTypes.TYPE_ERROR, "thrown")))
                .isSameAs(PyInt.of(2));
        // Parked inside its own handler, and none of that shows in the slot the caller reads.
        assertThat(PyRuntime.handledException()).isSameAs(PyNone.INSTANCE);
        assertThat(step(generator)).isNull();
        assertThat(PyRuntime.handledException()).isSameAs(PyNone.INSTANCE);

        // A caller's handler outlives every step of a generator that has none of its own: the empty
        // record falls through to it rather than replacing it, and is not left behind on the way out.
        PyBaseException callers = newException(ExceptionTypes.INDEX_ERROR, "the caller");
        PyRuntime.setHandledException(callers);
        PyGenerator other = (PyGenerator) reach(module, "plain()");
        assertThat(step(other)).isSameAs(PyInt.of(1));
        assertThat(PyRuntime.handledException()).isSameAs(callers);
        assertThat(step(other)).isSameAs(PyInt.of(2));
        assertThat(PyRuntime.handledException()).isSameAs(callers);
        assertThat(step(other)).isNull();
        assertThat(PyRuntime.handledException()).isSameAs(callers);
    }

    // --- running -------------------------------------------------------------------------

    /** A program compiled by the reference and run into a namespace of its own. */
    private PyModule run(String name, String program) throws IOException {
        Cpython314Compiler.Compiled compiled =
                Cpython314Compiler.compileFile(Files.writeString(work.resolve(name), program));
        PyModule module = new PyModule("workshop");
        PyRuntime.exec(compiled.code(), module, compiled.fileName());
        return module;
    }

    /** One expression evaluated in that namespace: how a test reaches what the script made. */
    private static PyObject reach(PyModule module, String expression) {
        return PyRuntime.eval(
                Cpython314Compiler.compileExpression(expression, module.name()), module);
    }

    /** {@code next(gen)} read at the frame: the value, or null when the generator is done. */
    private static PyObject step(PyGenerator generator) {
        return generator.nextOrNull();
    }

    /** {@code gen.send(value)} as Python calls it, through the table the type declares. */
    private static PyObject send(PyGenerator generator, PyObject value) {
        return Slots.call(member(generator, "send"), new PyObject[] {value}, null);
    }

    /** {@code gen.throw(one_argument)} as Python calls it. */
    private static PyObject throwInto(PyGenerator generator, PyObject argument) {
        return Slots.call(member(generator, "throw"), new PyObject[] {argument}, null);
    }

    private static PyObject throwWith(PyGenerator generator, PyObject[] arguments) {
        return Slots.call(member(generator, "throw"), arguments, null);
    }

    private static PyObject call(PyGenerator generator, String name) {
        return Slots.call(member(generator, name), new PyObject[0], null);
    }

    /** The method Python reads off the type, which is the one that words its own refusals. */
    private static PyObject member(PyGenerator generator, String name) {
        return Members.find(generator, name);
    }

    /** The slot that name holds, found the way {@code f_locals} finds it. */
    private static PyObject slot(Frame frame, String name) {
        PyTuple names = frame.code().localNames();
        for (int i = 0; i < names.size(); i++) {
            if (name.equals(((PyStr) names.at(i)).getValue())) {
                return frame.slot(i);
            }
        }
        throw new AssertionError("no slot named " + name + " in " + frame.code().qualName());
    }

    /** What Python reads as text, or as the {@code repr} of whatever the value turned out to be. */
    private static String text(PyObject value) {
        return value instanceof PyStr string ? string.getValue() : Slots.repr(value);
    }

    private static PyBaseException newException(PyType type, String message) {
        return (PyBaseException) Slots.call(type, new PyObject[] {PyStr.of(message)}, null);
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }
}
