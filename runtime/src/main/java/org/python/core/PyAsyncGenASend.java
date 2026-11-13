package org.python.core;

/**
 * What {@code agen.__anext__()} and {@code agen.asend(value)} answer: the awaitable that, when stepped,
 * advances the async generator one yield.
 *
 * <p>An awaitable reports the value it produced through the {@code StopIteration} it raises, which is
 * the one place an async generator and a coroutine differ in shape: the body's yield is a value the
 * awaiting frame receives, so it is the generator's own {@code StopIteration} that carries it. The end
 * of the body is a {@code StopAsyncIteration}, which is what {@code async for}'s exception table is
 * waiting for.
 *
 * <p>The object is single-use in the awaiting sense and remembers that: {@code send} after the body
 * answered with a failure or an end raises the reference's {@code cannot reuse already awaited}
 * failure rather than stepping a generator that has nothing left.
 */
final class PyAsyncGenASend extends PyIterator {

    private static final Members MEMBERS = Members.of(PyType.ASYNC_GENERATOR_ASEND)
            .addOne("send", (self, argument) -> ((PyAsyncGenASend) self).send(argument))
            .add(
                    "throw",
                    (self, args, kwNames) -> {
                        Arguments.noKeywords("async_generator_asend.throw()", kwNames);
                        Arguments.between("throw", args, kwNames, 1, 3);
                        return ((PyAsyncGenASend) self).throwAt(args);
                    })
            .addNoArgument("close", self -> ((PyAsyncGenASend) self).close())
            // `am_await` is `PyObject_SelfIter`, so the object is its own iterator and its own
            // awaitable; the difference between `next(x)` and `await x` is only where the value goes.
            .addNoArgument("__await__", self -> self);

    private final PyAsyncGenerator generator;

    /** What the first step sends: {@code None} for {@code __anext__}, the argument for {@code asend}. */
    private final PyObject sendValue;

    private boolean started;

    private boolean closed;

    /** The {@code StopIteration} the last step left set, kept for {@code next()}'s exhaustion. */
    private PyBaseException stopped;

    PyAsyncGenASend(PyAsyncGenerator generator, PyObject sendValue) {
        super(PyType.ASYNC_GENERATOR_ASEND);
        this.generator = generator;
        this.sendValue = sendValue;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return Members.find(this, attribute);
    }

    /**
     * {@code async_gen_asend_iternext}: {@code send(None)}, with a step that yields turned into the
     * {@code StopIteration} that says the iteration is over.
     */
    @Override
    public PyObject nextOrNull() {
        try {
            return send(PyNone.INSTANCE);
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

    /** {@code async_gen_asend_send}: the step, with CPython's two refusals ahead of it. */
    PyObject send(PyObject argument) {
        if (closed) {
            throw Slots.runtimeError("cannot reuse already awaited __anext__()/asend()");
        }
        PyObject value = argument;
        if (!started) {
            if (generator.isExecuting()) {
                closed = true;
                throw Slots.runtimeError("anext(): asynchronous generator is already running");
            }
            if (value == null || value == PyNone.INSTANCE) {
                value = sendValue;
            }
            started = true;
        }
        return drive(value);
    }

    /** {@code async_gen_asend_throw}: the throw into the body, answered the same way as a step. */
    PyObject throwAt(PyObject[] arguments) {
        if (closed) {
            throw Slots.runtimeError("cannot reuse already awaited __anext__()/asend()");
        }
        if (!started) {
            if (generator.isExecuting()) {
                closed = true;
                throw Slots.runtimeError("anext(): asynchronous generator is already running");
            }
            started = true;
        }
        PyObject type = arguments[0];
        PyObject value = arguments.length >= 2 ? arguments[1] : null;
        PyObject trace = arguments.length == 3 ? arguments[2] : null;
        try {
            return report(generator.throwForAwait(type, value, trace));
        } catch (PyRaise escaped) {
            return failed(escaped);
        }
    }

    /**
     * {@code async_gen_asend_close}: there is nothing to end — the awaitable is already over — so this
     * only closes the async generator, and substitutes the reference's wording for a body that ignored
     * the exit.
     */
    PyObject close() {
        if (closed) {
            return PyNone.INSTANCE;
        }
        closed = true;
        try {
            generator.close();
        } catch (PyRaise escaped) {
            if (escaped.value().matches(ExceptionTypes.RUNTIME_ERROR)
                    && escaped.value().str().equals("async generator ignored GeneratorExit")) {
                throw Slots.runtimeError("coroutine ignored GeneratorExit");
            }
            if (escaped.value().matches(ExceptionTypes.STOP_ITERATION)
                    || escaped.value().matches(ExceptionTypes.STOP_ASYNC_ITERATION)
                    || escaped.value().matches(ExceptionTypes.GENERATOR_EXIT)) {
                return PyNone.INSTANCE;
            }
            throw escaped;
        }
        return PyNone.INSTANCE;
    }

    private PyObject drive(PyObject value) {
        PyObject yielded;
        try {
            yielded = generator.stepForAwait(value);
        } catch (PyRaise escaped) {
            return failed(escaped);
        }
        return report(yielded);
    }

    /**
     * The yield an awaitable reports: a {@code StopIteration} carrying the value, or the end of the body
     * as the {@code StopAsyncIteration} that closes an {@code async for}.
     */
    private PyObject report(PyObject yielded) {
        if (yielded == null) {
            closed = true;
            throw PyRaise.raise(ExceptionTypes.STOP_ASYNC_ITERATION);
        }
        if (!(yielded instanceof PyAsyncGenWrapped wrapped)) {
            // Not a yield the body made through the wrap intrinsic, so there is no value for the await
            // to report and the step's own answer is the result (CPython's unwrap passes it through).
            return yielded;
        }
        stopped =
                (PyBaseException)
                        Slots.call(
                                ExceptionTypes.STOP_ITERATION,
                                new PyObject[] {wrapped.value},
                                null);
        closed = true;
        throw PyRaise.reraise(stopped);
    }

    /**
     * A step that failed ends the awaitable. The body's own {@code StopIteration} is not a failure here
     * — the frame has already turned it into the error the language names — so the only one converted
     * is the completion the engine reports.
     */
    private PyObject failed(PyRaise escaped) {
        closed = true;
        stopped = escaped.value();
        if (escaped.value().matches(ExceptionTypes.STOP_ITERATION)) {
            throw PyRaise.raise(ExceptionTypes.STOP_ASYNC_ITERATION);
        }
        throw escaped;
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }
}
