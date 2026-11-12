package org.python.core;

/**
 * An async generator: the object an {@code async def} body with a {@code yield} answers with.
 *
 * <p>Only its creation, its type and the five {@code ag_*} fields are here. {@code asend},
 * {@code athrow}, {@code aclose}, {@code __aiter__} and {@code __anext__} are the missing half: they
 * answer with awaitables of their own, and with no event loop to run one the protocol has nothing to
 * drive it yet. {@code _collections_abc} and {@code types} ask only for the type, which is what makes
 * this object worth making now.
 */
final class PyAsyncGenerator extends PyObject {

    private static final String[] READ_ONLY = {
        "ag_await", "ag_frame", "ag_code", "ag_running", "ag_suspended"
    };

    private final PyGenerator engine;

    PyAsyncGenerator(Frame frame) {
        super(PyType.ASYNC_GENERATOR);
        this.engine = new PyGenerator(frame, PyGenerator.Kind.ASYNC_GENERATOR);
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
