package org.python.core;

/**
 * A Python exception travelling up the Java call stack.
 *
 * Java has no way to express "this slot declined, and also this {@code for} loop body raised", so
 * the value crosses Java frames in an unchecked exception and {@link Interpreter} converts it back
 * to a stack value at the point Python semantics expect — which is the same trick CPython plays with
 * {@code PyErr_Occurred}, only harder to forget. Nothing here inspects the Java stack trace: a
 * Python traceback is rebuilt from the frame chain, not from Java frames, because Java frames
 * include {@link Slots} and every other internal caller.
 */
public final class PyRaise extends RuntimeException {

    private final PyBaseException value;

    private PyRaise(PyBaseException value) {
        super(null, null, false, false);
        this.value = value;
    }

    public static PyRaise of(PyBaseException value) {
        return new PyRaise(value);
    }

    /** Raise {@code type(args...)}; the message is built from arguments already converted. */
    public static PyRaise raise(PyType type, PyObject... args) {
        return of(new PyBaseException(type, args.clone()));
    }

    /** Raise {@code type("message")} for the many cases where CPython formats a single string. */
    public static PyRaise raise(PyType type, String message) {
        return raise(type, PyStr.of(message));
    }

    public PyBaseException value() {
        return value;
    }

    @Override
    public String getMessage() {
        return value.exceptionType().name() + ": " + value.str();
    }
}
