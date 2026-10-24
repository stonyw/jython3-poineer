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
 *
 * <p>Two ways make one, and they differ in what the wrapper keeps of the function. Python's
 * {@code staticmethod(f)} — {@code sm_init} — runs {@code functools_wraps}, which files {@code f}'s
 * {@code __name__}, {@code __qualname__}, {@code __module__} and {@code __doc__} as the wrapper's own,
 * skipping any the function does not have. A {@code METH_STATIC} entry of a built-in type's method table
 * is made before any of that, so {@code str.__dict__['maketrans']} has none of the four: its
 * {@code __doc__} is the sentence the {@code staticmethod} type itself declares, read up the MRO, and it
 * has no name to answer with. {@link #filed(PyNativeFunction)} is that second way.
 */
public final class PyStaticMethod extends PyObject implements Descriptors.Protocol {

    private final PyObject function;

    /** Whether {@code f}'s own identity the wrapper holds, which only Python's call to wrap does. */
    private final boolean wrapped;

    PyStaticMethod(PyObject function) {
        this(function, true);
    }

    private PyStaticMethod(PyObject function, boolean wrapped) {
        super(PyType.STATIC_METHOD);
        this.function = function;
        this.wrapped = wrapped;
    }

    /** The wrapper a built-in type's method table files, which was never given a function to wrap. */
    static PyStaticMethod filed(PyObject function) {
        return new PyStaticMethod(function, false);
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

    /** Callable since 3.11, whatever the function underneath it is. */
    @Override
    public boolean isCallable() {
        return true;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__func__" -> function;
            case "__get__" -> new PyNativeFunction(
                    "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames));
            case "__isabstractmethod__" -> PyBool.FALSE;
            case "__wrapped__" -> function;
            case "__name__", "__qualname__", "__module__", "__doc__" ->
                    wrapped ? function.getAttr(attribute) : PyNotImplemented.INSTANCE;
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
