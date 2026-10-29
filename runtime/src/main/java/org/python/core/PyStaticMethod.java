package org.python.core;

/**
 * A function filed in a class that stays a function: {@code staticmethod}.
 *
 * <p>The wrapper's whole effect is that binding never happens. {@code Scores.weight} and
 * {@code Scores().weight} are both the plain function, because {@code sm_descr_get} hands back the field
 * and takes nothing off it — which is the one place the runtime could otherwise get it wrong, since a
 * function found through a value is normally bound by the lookup that found it. The method form of
 * {@code LOAD_ATTR} then has no receiver left to pass.
 *
 * <p>The object itself is callable, as CPython made it in 3.11: {@code Scores.__dict__["weight"](9)}
 * calls the function with no receiver at all, through {@code sm_call}. That makes it the only one of the
 * three descriptor builtins with a {@code tp_call}, and so the only one whose dictionary files a {@code
 * __call__} row and the only one {@code callable()} answers true for.
 *
 * <p>What the wrapper keeps of the function is {@link PyCallableWrapper}'s business: Python's
 * {@code staticmethod(f)} copies four names off it, {@code sm_new} files nothing but {@code None} in the
 * field, and the wrapper a {@code METH_STATIC} row of a built-in's method table holds — {@link #filed} —
 * has none of the four, which is what makes {@code str.__dict__['maketrans']} silent about its own name.
 */
public final class PyStaticMethod extends PyCallableWrapper {

    /** {@code staticmethod(f)}: the field, and the four names copied beside it. */
    PyStaticMethod(PyObject function) {
        this();
        initialise(function);
    }

    /** {@code staticmethod.__new__(staticmethod)}: a wrapper around {@code None}, with no names. */
    PyStaticMethod() {
        super(PyType.STATIC_METHOD);
    }

    /** The wrapper a built-in type's method table files, which was never given a function to wrap. */
    static PyStaticMethod filed(PyObject function) {
        PyStaticMethod wrapper = new PyStaticMethod();
        wrapper.attach(function);
        return wrapper;
    }

    /** The unwrapping: reached through a class or a value, the answer is the function either way. */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        return wrapped();
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return Slots.call(wrapped(), args, kwNames);
    }

    /** Callable since 3.11, whatever the function underneath it is. */
    @Override
    public boolean isCallable() {
        return true;
    }
}
