package org.python.core;

/**
 * What {@code coroutine.__await__()} answers: an iterator over a coroutine.
 *
 * <p>CPython wraps the coroutine rather than letting {@code __await__} answer itself, and the wrapper
 * is what makes the coroutine usable in a {@code SEND} loop without making it iterable: its
 * {@code __next__} is a {@code send(None)}, and its {@code send}, {@code throw} and {@code close}
 * forward to the coroutine so that the three names a delegation is driven with all reach the body.
 *
 * <p>{@code await} does not build one of these. {@code GET_AWAITABLE} hands the exact coroutine to
 * {@code SEND}, which is what CPython does too; the wrapper exists only for a program that asks for
 * {@code __await__} itself, and for {@code type(coro.__await__())}.
 */
final class PyCoroutineWrapper extends PyIterator {

    private static final Members MEMBERS = Members.of(PyType.COROUTINE_WRAPPER)
            .addOne("send", (self, argument) -> ((PyCoroutineWrapper) self).engine.send(argument))
            .add(
                    "throw",
                    (self, args, kwNames) -> {
                        Arguments.noKeywords("coroutine_wrapper.throw()", kwNames);
                        Arguments.between("throw", args, kwNames, 1, 3);
                        return ((PyCoroutineWrapper) self).engine.throwIn(args);
                    })
            .addNoArgument("close", self -> ((PyCoroutineWrapper) self).engine.close());

    private final PyGenerator engine;

    /**
     * The {@code StopIteration} the step that ended the iteration was made of.
     *
     * <p>A bare {@code next(wrapper)} on a coroutine that returns 42 has to raise {@code
     * StopIteration(42)}, and the value is known only to the step that raised it.
     */
    private PyBaseException stopped;

    PyCoroutineWrapper(PyGenerator engine) {
        super(PyType.COROUTINE_WRAPPER);
        this.engine = engine;
    }

    @Override
    public PyObject nextOrNull() {
        try {
            return engine.send(PyNone.INSTANCE);
        } catch (PyRaise ended) {
            if (ended.value().matches(ExceptionTypes.STOP_ITERATION)) {
                stopped = ended.value();
                return null;
            }
            throw ended;
        }
    }

    @Override
    public PyBaseException exhaustion() {
        return stopped;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return Members.find(this, attribute);
    }

    @Override
    public String repr() {
        return "<coroutine_wrapper object at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ">";
    }
}
