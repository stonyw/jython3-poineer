package org.python.core;

/**
 * A function filed in a class that stays a function: {@code staticmethod}.
 *
 * <p>The wrapper's whole effect is that binding never happens. {@code Scores.weight} and
 * {@code Scores().weight} are both the plain function, so {@code self.weight(2)} is a one-argument call
 * rather than CPython's usual two — which is the one place the runtime could otherwise get it wrong,
 * since a function found through a value is normally bound by the lookup that found it. Handing back the
 * function here is what keeps it unbound, and the method form of {@code LOAD_ATTR} then has no receiver
 * left to pass.
 *
 * <p>The object itself is callable, as CPython made it in 3.11: {@code Scores.__dict__["weight"](9)}
 * calls the function with no receiver at all. That makes it the only one of the three descriptor
 * builtins that {@code callable()} answers true for; a classmethod or property object is not callable.
 */
public final class PyStaticMethod extends PyObject implements Descriptors.Protocol {

    private final PyObject function;

    PyStaticMethod(PyObject function) {
        super(PyType.STATIC_METHOD);
        this.function = function;
    }

    /** The unwrapping: reached through a class or a value, the answer is the function either way. */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        return function;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return Slots.call(function, args, kwNames);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__func__" -> function;
            case "__get__" -> new PyNativeFunction(
                    "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames));
            case "__name__", "__qualname__", "__doc__", "__module__", "__wrapped__" ->
                    attribute.equals("__wrapped__") ? function : function.getAttr(attribute);
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    @Override
    public String repr() {
        return "<staticmethod(" + function.repr() + ")>";
    }
}
