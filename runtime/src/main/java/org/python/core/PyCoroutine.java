package org.python.core;

/**
 * A coroutine: a frame that is stepped by {@code send}, never by {@code next}.
 *
 * <p>The frame machine is {@link PyGenerator}'s, and this class owns one of those rather than
 * repeating it. What a coroutine adds is what makes it different from a generator in Python's eyes:
 * it is not iterable, so a step arrives only through {@code send}, {@code throw} or {@code close};
 * an exhausted one cannot be entered again at all, where a generator answers {@code next()} with a
 * bare {@code StopIteration}; and {@code __await__} hands out a wrapper whose three driving methods
 * forward to this object.
 *
 * <p>{@code await} itself does not go through {@code __await__}. {@code GET_AWAITABLE} recognises an
 * exact coroutine and hands it to {@code SEND} as it is, which is why the value of {@code cr_await}
 * in the awaiting frame is the coroutine rather than the wrapper CPython only makes when a program
 * asks for it.
 */
public final class PyCoroutine extends PyObject {

    /** The six fields {@code coroutine_getsetlist} gives no setter, and so never accepts a write to. */
    private static final String[] READ_ONLY = {
        "cr_await", "cr_frame", "cr_code", "cr_origin", "cr_running", "cr_suspended"
    };

    /** {@code coroutine_methods}: three driving methods and the await hook. */
    private static final Members MEMBERS = Members.of(PyType.COROUTINE)
            .addOne("send", (self, argument) -> ((PyCoroutine) self).send(argument))
            .add(
                    "throw",
                    (self, args, kwNames) -> {
                        Arguments.noKeywords("coroutine.throw()", kwNames);
                        Arguments.between("throw", args, kwNames, 1, 3);
                        return ((PyCoroutine) self).engine.throwIn(args);
                    })
            .addNoArgument("close", self -> ((PyCoroutine) self).engine.close())
            .addNoArgument("__await__", self -> ((PyCoroutine) self).awaitWrapper());

    /** The frame machine, shared with the generator it was generalised from. */
    private final PyGenerator engine;

    PyCoroutine(Frame frame) {
        super(PyType.COROUTINE);
        this.engine = new PyGenerator(frame, PyGenerator.Kind.COROUTINE);
        // The frame belongs to this coroutine, so `frame.f_generator` has to name this value and not
        // the engine inside it: the cr_* names live here.
        engine.ownedBy(this);
    }

    // --- driving -------------------------------------------------------------------------

    /** {@code coro.send(value)}: one step, with the value the suspended {@code send} expression sees. */
    PyObject send(PyObject argument) {
        return engine.send(argument);
    }

    /**
     * Whether this coroutine is standing on a delegation, which is CPython's test for a second
     * {@code await} of the same object.
     */
    boolean awaitingDelegate() {
        return engine.yieldFrom() != null;
    }

    PyObject awaitWrapper() {
        return new PyCoroutineWrapper(engine);
    }

    // --- what Python reads ---------------------------------------------------------------

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> engine.name;
            case "__qualname__" -> engine.qualName;
            // PyCoro_Type declares no tp_doc, so the name inherited from object has nothing to show.
            case "__doc__" -> PyNone.INSTANCE;
            case "cr_await" -> {
                PyObject delegate = engine.yieldFrom();
                yield delegate == null ? PyNone.INSTANCE : delegate;
            }
            case "cr_running" -> PyBool.of(engine.state() == PyGenerator.State.EXECUTING);
            case "cr_suspended" -> PyBool.of(engine.isSuspended());
            // A finished coroutine has no frame for Python to reach; the code object stays, because it
            // belongs to the coroutine and not to the frame.
            case "cr_frame" ->
                    engine.state() == PyGenerator.State.COMPLETED
                            ? PyNone.INSTANCE
                            : engine.frame().frameObject();
            case "cr_code" -> (PyObject) engine.frame().code();
            // With origin tracking off — the default, and the only mode this runtime has — the slot is
            // empty and the getter answers None rather than raising.
            case "cr_origin" -> PyNone.INSTANCE;
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
                        "attribute '" + attribute + "' of 'coroutine' objects is not writable");
            }
        }
        if (MEMBERS.defines(attribute) || attribute.equals("__doc__")) {
            return Slots.attributeError(
                    "'coroutine' object attribute '" + attribute + "' is read-only");
        }
        return Slots.attributeError(
                "'coroutine' object has no attribute '" + attribute
                        + "' and no __dict__ for setting new attributes");
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    @Override
    public String repr() {
        return "<coroutine object "
                + engine.displayName()
                + " at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ">";
    }
}
