package org.python.core;

/**
 * An async generator: the object an {@code async def} body with a {@code yield} answers with.
 *
 * <p>The body is the same frame machine a generator runs on — {@code RETURN_GENERATOR}, then a
 * {@code YIELD_VALUE} that parks and a {@code RESUME} that continues — so the engine is a
 * {@link PyGenerator}. What is different is how the body is entered. Nobody asks an async generator to
 * step; they await an object it hands out, and that object's {@code send} is what steps the body and
 * reports a yield through a {@code StopIteration} the way an awaitable reports its result. The two
 * awaitables are {@link PyAsyncGenASend} and {@link PyAsyncGenAThrow}, and the protocol methods here
 * are the ones that make them.
 *
 * <p>{@code ag_await} is None on purpose: this runtime has no event loop, so a body waiting on
 * something other than another coroutine is not suspended in anything a caller can name. The
 * placement of {@code yield} and {@code await} is still CPython's, and the values agree.
 */
final class PyAsyncGenerator extends PyObject {

    private static final String[] READ_ONLY = {
        "ag_await", "ag_frame", "ag_code", "ag_running", "ag_suspended"
    };

    /** {@code async_gen_methods}: three methods taking the shape CPython's {@code METH_*} flags take. */
    private static final Members MEMBERS = Members.of(PyType.ASYNC_GENERATOR)
            .addOne("asend", (self, value) -> ((PyAsyncGenerator) self).asend(value))
            .add(
                    "athrow",
                    (self, args, kwNames) -> {
                        Arguments.noKeywords("athrow", kwNames);
                        Arguments.between("athrow", args, kwNames, 1, 3);
                        return ((PyAsyncGenerator) self).athrow(args);
                    })
            .addNoArgument("aclose", self -> ((PyAsyncGenerator) self).aclose())
            // `am_aiter` is `PyObject_SelfIter` and `am_anext` makes the asend, so both names are
            // written here rather than in a subclass.
            .addNoArgument("__aiter__", self -> self)
            .addNoArgument(
                    "__anext__", self -> ((PyAsyncGenerator) self).asend(PyNone.INSTANCE));

    private final PyGenerator engine;

    PyAsyncGenerator(Frame frame) {
        super(PyType.ASYNC_GENERATOR);
        this.engine = new PyGenerator(frame, PyGenerator.Kind.ASYNC_GENERATOR);
    }

    /** {@code async_gen_asend}, which is also {@code async_gen_anext} with a None to send. */
    PyObject asend(PyObject value) {
        return new PyAsyncGenASend(this, value);
    }

    /** {@code async_gen_athrow}: the awaitable that throws the arguments in when it is stepped. */
    PyObject athrow(PyObject[] arguments) {
        return new PyAsyncGenAThrow(this, arguments);
    }

    /** {@code async_gen_aclose}: the awaitable that asks the body to exit. */
    PyObject aclose() {
        return new PyAsyncGenAThrow(this, null);
    }

    /**
     * One {@code gen_send} of the body, with the yield-or-return answer left raw: the awaitables decide
     * whether a yield is a {@code StopIteration} the awaiting frame reads as a result, or a value a
     * caller of {@code send} must be told about.
     */
    PyObject stepForAwait(PyObject argument) {
        return engine.stepForAwait(argument);
    }

    /** {@code _gen_throw} with the raw answer, for {@code athrow}'s and {@code aclose}'s bodies. */
    PyObject throwForAwait(PyObject type, PyObject value, PyObject trace) {
        return engine.throwForAwait(type, value, trace);
    }

    /** {@code _gen_close} on the body, which is what both awaitables' {@code close} asks for. */
    PyObject close() {
        return engine.close();
    }

    /** {@code ag_running_async}: a body being stepped cannot be entered by another awaitable. */
    boolean isExecuting() {
        return engine.state() == PyGenerator.State.EXECUTING;
    }

    /** {@code FRAME_STATE_FINISHED}: the body has nothing left to run. */
    boolean isFinished() {
        return engine.state() == PyGenerator.State.COMPLETED;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> engine.name;
            case "__qualname__" -> engine.qualName;
            case "__doc__" -> PyNone.INSTANCE;
            // An async generator parked between steps is standing on nothing: the only awaitables it
            // would be suspended in are the asend-driven ones this slice does not make.
            case "ag_await" -> PyNone.INSTANCE;
            case "ag_running" -> PyBool.of(engine.state() == PyGenerator.State.EXECUTING);
            case "ag_suspended" -> PyBool.of(engine.isSuspended());
            case "ag_frame" ->
                    engine.state() == PyGenerator.State.COMPLETED
                            ? PyNone.INSTANCE
                            : engine.frame().frameObject();
            case "ag_code" -> (PyObject) engine.frame().code();
            default -> Members.find(this, attribute);
        };
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        if (attribute.equals("__name__") || attribute.equals("__qualname__")) {
            setName(attribute, value);
            return PyNone.INSTANCE;
        }
        throw refusal(attribute);
    }

    @Override
    public PyObject delAttr(String attribute) {
        if (attribute.equals("__name__") || attribute.equals("__qualname__")) {
            setName(attribute, null);
            return PyNone.INSTANCE;
        }
        throw refusal(attribute);
    }

    private void setName(String attribute, PyObject value) {
        if (!(value instanceof PyStr text)) {
            throw Slots.typeError(attribute + " must be set to a string object");
        }
        if (attribute.equals("__name__")) {
            engine.name = text;
        } else {
            engine.qualName = text;
        }
    }

    private PyRaise refusal(String attribute) {
        for (String name : READ_ONLY) {
            if (name.equals(attribute)) {
                return Slots.attributeError(
                        "attribute '" + attribute + "' of 'async_generator' objects is not writable");
            }
        }
        if (attribute.equals("__doc__")) {
            return Slots.attributeError(
                    "'async_generator' object attribute '" + attribute + "' is read-only");
        }
        return Slots.attributeError(
                "'async_generator' object has no attribute '" + attribute
                        + "' and no __dict__ for setting new attributes");
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    @Override
    public String repr() {
        return "<async_generator object "
                + engine.displayName()
                + " at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ">";
    }
}
