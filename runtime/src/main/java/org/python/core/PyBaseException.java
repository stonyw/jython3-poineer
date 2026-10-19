package org.python.core;

/**
 * A raised Python exception, as a value.
 *
 * The type is held here rather than inferred from the Java class because the exception hierarchy is
 * open at Python level (M3): {@code class E(ValueError)} has to produce a value that is still a
 * {@code PyBaseException} in Java but reports {@code E} as its class. {@code exceptionType} is that
 * answer, and {@code getType()} is set to the same object so {@code type(e)} agrees with
 * {@code e.__class__}.
 *
 * No traceback yet. CPython 3.14 keeps exception state in the frame and reconstructs the traceback
 * from its exception table, which is a Stage-A byte-table concern (M3); until then a raise carries
 * only what the user wrote.
 */
public class PyBaseException extends PyObject {

    private final PyType exceptionType;
    private final PyObject[] args;

    /** The exception {@code raise X from Y} names, kept so that {@code __cause__} is readable. */
    private PyObject cause = PyNone.INSTANCE;

    /** The exception this one replaced, which CPython chains implicitly as {@code __context__}. */
    private PyObject context = PyNone.INSTANCE;

    /**
     * An exception raised as {@code exceptionType} with {@code args}.
     *
     * <p>Protected rather than package-private because the class system is not the only subclass this
     * needs: a Java failure crossing the boundary arrives as an exception whose attributes are read off
     * the {@code Throwable}, which is a subclass of this one and lives in the interop module.
     */
    protected PyBaseException(PyType exceptionType, PyObject[] args) {
        super(exceptionType);
        this.exceptionType = exceptionType;
        this.args = args;
    }

    /** The class the exception was raised as — {@code ValueError}, not always {@code BaseException}. */
    public PyType exceptionType() {
        return exceptionType;
    }

    public PyTuple args() {
        return PyTuple.of(args);
    }

    /**
     * {@code str(e)}: the single argument on its own, every argument as a tuple, nothing at all for an
     * exception raised without arguments. This is CPython's rule, and it is observable —
     * {@code str(ValueError(1, 2))} is {@code "(1, 2)"} while {@code str(ValueError())} is {@code ""}.
     */
    @Override
    public String str() {
        if (args.length == 0) {
            return "";
        }
        if (args.length == 1) {
            return args[0].str();
        }
        return PyTuple.of(args).repr();
    }

    @Override
    public String repr() {
        String arguments = PyTuple.of(args).repr();
        // A one-element tuple reprs with its trailing comma, which the exception form does not use.
        if (args.length == 1) {
            arguments = "(" + args[0].repr() + ",)";
        }
        return exceptionType.name() + arguments;
    }

    void setCause(PyBaseException other) {
        cause = other;
    }

    /**
     * The attributes an exception instance carries.
     *
     * {@code __traceback__} is always {@code None} here: a traceback is rebuilt from the frame chain
     * and the code object's exception table, both of which are M3 work, and reporting no traceback is
     * honest where inventing a Java one would not be.
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "args" -> PyTuple.of(args);
            case "__cause__" -> cause;
            case "__context__" -> context;
            case "__traceback__", "__module__" -> PyNone.INSTANCE;
            case "__class__" -> exceptionType;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        switch (name) {
            case "__cause__" -> cause = value;
            case "__context__" -> context = value;
            default -> {
            }
        }
        return PyNone.INSTANCE;
    }

    /** Whether this is the exception {@code clause} catches, by the class alone (M3 adds instances). */
    public boolean matches(PyType clause) {
        return exceptionType.matches(clause);
    }
}
