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
 * No traceback yet. CPython 3.14 keeps exception state in the frame and reconstructs the traceback from
 * its exception table: the table is read and used, but a traceback needs the frame chain above it too,
 * which is a later M3 slice. Until then a raise carries only what the user wrote.
 */
public class PyBaseException extends PyObject {

    private final PyType exceptionType;
    private final PyObject[] args;

    /** The exception {@code raise X from Y} names, kept so that {@code __cause__} is readable. */
    private PyObject cause = PyNone.INSTANCE;

    /** The exception this one replaced, which CPython chains implicitly as {@code __context__}. */
    private PyObject context = PyNone.INSTANCE;

    /** Whether to hide {@code __context__} from the report, which {@code raise X from Y} decides. */
    private PyObject suppressContext = PyBool.FALSE;

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
     *
     * <p>KeyError is the one exception with a rule of its own: its single argument is the missing key,
     * and CPython reports it with {@code repr} so that {@code {}['']} reads as {@code KeyError: ''}
     * rather than as a bare {@code KeyError}. The quotes are CPython's, not a bug to smooth over.
     */
    @Override
    public String str() {
        if (args.length == 1 && exceptionType.matches(ExceptionTypes.KEY_ERROR)) {
            return args[0].repr();
        }
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
        // CPython's two halves of one format string: `%s(%R)` when there is exactly one argument, so
        // `ValueError(1)` reads without a tuple's trailing comma, and `%s%R` of the argument tuple
        // otherwise — which is how no argument at all still gets its parentheses, as `ValueError()`.
        if (args.length == 1) {
            return exceptionType.name() + "(" + args[0].repr() + ")";
        }
        return exceptionType.name() + PyTuple.of(args).repr();
    }

    /**
     * {@code raise X from Y}: the cause, and with it the decision not to report a context.
     *
     * <p>CPython's {@code PyException_SetCause} sets both at once, so an explicit {@code from} always
     * suppresses the implicit chain — even when the two name the same exception, which is what
     * {@code except E as inner: raise F from inner} does.
     *
     * @param other the cause, or null for {@code from None}, which reports no cause at all
     */
    void setCause(PyBaseException other) {
        cause = other == null ? PyNone.INSTANCE : other;
        suppressContext = PyBool.TRUE;
    }

    /** The exception this one replaced, or {@code None} when nothing did. */
    PyObject context() {
        return context;
    }

    /** The implicit chain, built from the exception the enclosing handler was running for. */
    void setContext(PyBaseException other) {
        context = other == null ? PyNone.INSTANCE : other;
    }

    /**
     * The {@code __traceback__} field, which CPython reads as a field and not as an attribute — so a
     * class that files a property under the name is not asked what an escaped exception looks like.
     *
     * <p>A traceback is built from the frame chain above the raise, which this runtime does not keep
     * yet, and reporting none is honest where inventing a Java one would not be. The name of the
     * exception {@code with} hands to {@code __exit__} is read here, so that the day a chain is kept,
     * the third argument a context manager sees follows from it.
     */
    PyObject traceback() {
        return PyNone.INSTANCE;
    }

    /**
     * The attributes an exception instance carries.
     *
     * {@code __traceback__} is always {@code None} here: a traceback is built from the frame chain above
     * the raise, which this runtime does not keep yet, and reporting no traceback is honest where
     * inventing a Java one would not be.
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "args" -> PyTuple.of(args);
            case "__cause__" -> cause;
            case "__context__" -> context;
            case "__suppress_context__" -> suppressContext;
            case "__traceback__" -> traceback();
            case "__module__" -> PyNone.INSTANCE;
            case "__class__" -> exceptionType;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        switch (name) {
            case "__cause__" -> cause = value;
            case "__context__" -> context = value;
            case "__suppress_context__" -> suppressContext = value;
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
