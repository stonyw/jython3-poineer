package org.python.javaclass;

import org.python.core.PyBaseException;
import org.python.core.PyNotImplemented;
import org.python.core.PyObject;
import org.python.core.PyStr;

/**
 * A Java failure, raised as the Python exception of the same name.
 *
 * <p>It is a {@link PyBaseException} so that Python's {@code except} clauses, {@code str(e)} and the
 * launcher's report all work on it unchanged, and it is a Java {@code Throwable}'s wrapper at the same
 * time, so that the Java methods on it still answer: {@code e.getMessage()}, {@code e.getCause()},
 * {@code e.getStackTrace()}. A Python user who catches a Java exception is holding a Java object, and
 * the one thing they must not lose is the thing they would have called on it in Java.
 *
 * <p>Members are found by {@link PyJavaObject#memberOf}, the same lookup an ordinary wrapped Java
 * object uses, with this exception as the owner rather than a wrapper behind it: {@code e.getMessage()}
 * has to bind {@code getMessage} to the value Python is holding, or the interpreter hands it its
 * receiver twice. {@code args} and {@code __cause__} stay Python's own.
 *
 * <p>A Java stack trace is not a Python traceback, so {@code e.__traceback__} stays {@code None} as it
 * is for every exception in this runtime; {@code e.getStackTrace()} is the Java one, and it is honest
 * about which side of the boundary it came from.
 */
public final class PyJavaThrowable extends PyBaseException {

    private final Throwable failure;

    public PyJavaThrowable(Throwable failure) {
        super(PyJavaType.of(failure.getClass()), argumentsOf(failure));
        this.failure = failure;
    }

    /**
     * The object Java threw.
     *
     * <p>A Java caller that catches the {@code PyRaise} around a callback gets the original back, so a
     * failure that crossed into Python and out again can still be compared, rethrown or logged with its
     * own stack. That identity is the difference between reporting a failure and reporting its text.
     */
    public Throwable throwable() {
        return failure;
    }

    @Override
    public PyObject getAttr(String name) {
        PyObject answer = PyJavaObject.memberOf(failure, this, name);
        return answer != PyNotImplemented.INSTANCE ? answer : super.getAttr(name);
    }

    /** {@code e.args}, the way constructing the same exception in Java would have given it. */
    private static PyObject[] argumentsOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? new PyObject[0] : new PyObject[] {PyStr.of(message)};
    }
}
