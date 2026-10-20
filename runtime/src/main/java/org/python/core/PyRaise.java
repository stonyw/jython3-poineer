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
 *
 * <p>Two routes put a value on that stack, and CPython keeps them apart. {@link #of} is the route an
 * error is made on — {@code _PyErr_SetObject} — and it links the exception a handler is running for as
 * this one's {@code __context__}. {@link #reraise} is the route a value already in flight takes back
 * out — {@code _PyErr_SetRaisedException}, which is what {@code RERAISE} and a bare {@code raise} use —
 * and it links nothing, because the context was chosen when the value was made.
 */
public final class PyRaise extends RuntimeException {

    private final PyBaseException value;

    private PyRaise(PyBaseException value) {
        super(null, null, false, false);
        this.value = value;
    }

    /** Make {@code value} the error in flight, chaining it onto the exception being handled. */
    public static PyRaise of(PyBaseException value) {
        linkContext(value);
        return new PyRaise(value);
    }

    /** Send an exception that is already in flight back up: {@code RERAISE}, and a bare {@code raise}. */
    public static PyRaise reraise(PyBaseException value) {
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

    /**
     * The implicit chain: the exception a handler is running for becomes this one's context, unless it
     * is this one already.
     *
     * <p>CPython reads {@code _PyErr_GetTopmostException(tstate)->exc_value}, the exception a handler was
     * entered with, rather than the one in flight, which is why an error raised two frames inside a
     * handler still finds the handler's exception as its context.
     */
    private static void linkContext(PyBaseException value) {
        if (!(PyRuntime.handledException() instanceof PyBaseException handled) || handled == value) {
            return;
        }
        cutContextCycle(handled, value);
        value.setContext(handled);
    }

    /**
     * CPython's guard against a {@code __context__} cycle: walk the handled exception's own chain and
     * cut it where it would reach the one about to be raised.
     *
     * <p>Without the cut, {@code except E: e.__context__ = e; raise F} leaves a chain a report cannot
     * finish walking. The slow pointer is CPython's own answer to a cycle that already exists, which the
     * walk would otherwise spin on forever.
     */
    private static void cutContextCycle(PyBaseException handled, PyBaseException raised) {
        PyBaseException slow = handled;
        boolean moveSlow = false;
        for (PyBaseException at = handled; ; ) {
            PyBaseException next = at.context() instanceof PyBaseException following ? following : null;
            if (next == null) {
                return;
            }
            if (next == raised) {
                at.setContext(null);
                return;
            }
            at = next;
            if (moveSlow) {
                if (!(slow.context() instanceof PyBaseException following)) {
                    return;
                }
                slow = following;
                if (slow == at) {
                    return;
                }
            }
            moveSlow = !moveSlow;
        }
    }
}
