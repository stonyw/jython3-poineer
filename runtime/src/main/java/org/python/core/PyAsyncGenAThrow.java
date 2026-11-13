package org.python.core;

/**
 * What {@code agen.aclose()} and {@code agen.athrow(...)} answer: one awaitable class for both, as the
 * reference files attribution ({@code async_generator_athrow} is the type name of each).
 *
 * <p>{@code aclose} asks the body to exit and expects nothing back; a body that yields instead of
 * exiting is the reference's {@code async generator ignored GeneratorExit}. {@code athrow} throws its
 * arguments in at the parked yield and reports what the body answers — a yielded value as the
 * {@code StopIteration} an awaitable uses, the end of the body as {@code StopAsyncIteration}.
 */
final class PyAsyncGenAThrow extends PyIterator {

    private static final Members MEMBERS = Members.of(PyType.ASYNC_GENERATOR_ATHROW)
            .addOne("send", (self, argument) -> ((PyAsyncGenAThrow) self).send(argument))
            .add(
                    "throw",
                    (self, args, kwNames) -> {
                        Arguments.noKeywords("async_generator_athrow.throw()", kwNames);
                        Arguments.between("throw", args, kwNames, 1, 3);
                        return ((PyAsyncGenAThrow) self).throwAt(args);
                    })
            .addNoArgument("close", self -> ((PyAsyncGenAThrow) self).close())
            .addNoArgument("__await__", self -> self);

    private final PyAsyncGenerator generator;

    /** The three arguments of {@code athrow}, or null for the {@code aclose} this type also serves. */
    private final PyObject[] arguments;

    private boolean closed;

    /** The {@code StopIteration} the last step left set, kept for {@code next()}'s exhaustion. */
    private PyBaseException stopped;

    PyAsyncGenAThrow(PyAsyncGenerator generator, PyObject[] arguments) {
        super(PyType.ASYNC_GENERATOR_ATHROW);
        this.generator = generator;
        this.arguments = arguments;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return Members.find(this, attribute);
    }

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

    /** {@code async_gen_athrow_send}: the body's answer, with the reference's two refusals ahead of it. */
    PyObject send(PyObject argument) {
        if (generator.isExecuting()) {
            closed = true;
            throw Slots.runtimeError(
                    (arguments == null ? "aclose()" : "athrow()")
                            + ": asynchronous generator is already running");
        }
        if (closed) {
            throw Slots.runtimeError("cannot reuse already awaited aclose()/athrow()");
        }
        return arguments == null ? closeStep() : throwStep();
    }

    /** {@code async_gen_athrow_throw}: a throw into the awaitable is a throw into the body. */
    PyObject throwAt(PyObject[] args) {
        if (closed) {
            throw Slots.runtimeError("cannot reuse already awaited aclose()/athrow()");
        }
        PyObject type = args[0];
        PyObject value = args.length >= 2 ? args[1] : null;
        PyObject trace = args.length == 3 ? args[2] : null;
        try {
            return report(generator.throwForAwait(type, value, trace));
        } catch (PyRaise escaped) {
            return failed(escaped);
        }
    }

    /** {@code async_gen_athrow_close}: closing the awaitable asks the body to exit. */
    PyObject close() {
        closed = true;
        try {
            generator.close();
        } catch (PyRaise escaped) {
            if (escaped.value().matches(ExceptionTypes.GENERATOR_EXIT)
                    || escaped.value().matches(ExceptionTypes.STOP_ITERATION)
                    || escaped.value().matches(ExceptionTypes.STOP_ASYNC_ITERATION)) {
                return PyNone.INSTANCE;
            }
            throw escaped;
        }
        return PyNone.INSTANCE;
    }

    /** The {@code aclose} route: the body exits, and the await reports None rather than a value. */
    private PyObject closeStep() {
        try {
            generator.close();
        } catch (PyRaise escaped) {
            if (escaped.value().matches(ExceptionTypes.GENERATOR_EXIT)
                    || escaped.value().matches(ExceptionTypes.STOP_ITERATION)
                    || escaped.value().matches(ExceptionTypes.STOP_ASYNC_ITERATION)) {
                closed = true;
                throw PyRaise.raise(ExceptionTypes.STOP_ITERATION);
            }
            closed = true;
            throw escaped;
        }
        closed = true;
        throw PyRaise.raise(ExceptionTypes.STOP_ITERATION);
    }

    /** The {@code athrow} route: what the body answered, in awaitable form. */
    private PyObject throwStep() {
        PyObject type = arguments[0];
        PyObject value = arguments.length >= 2 ? arguments[1] : null;
        PyObject trace = arguments.length == 3 ? arguments[2] : null;
        try {
            return report(generator.throwForAwait(type, value, trace));
        } catch (PyRaise escaped) {
            return failed(escaped);
        }
    }

    private PyObject report(PyObject yielded) {
        if (yielded == null) {
            closed = true;
            throw PyRaise.raise(ExceptionTypes.STOP_ASYNC_ITERATION);
        }
        if (!(yielded instanceof PyAsyncGenWrapped wrapped)) {
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
