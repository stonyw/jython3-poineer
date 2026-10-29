package org.python.core;

/**
 * A method whose first argument is the class it was reached through: {@code classmethod}.
 *
 * <p>The object exists only to change where the receiver comes from. Reading {@code Scores.named} runs
 * {@code __get__} with no value and the class as its second argument, and {@code cm_descr_get} ignores the
 * value entirely — which is why {@code Scores().named("x")} and {@code Scores.named("x")} both pass
 * {@code Scores}, and why a subclass read hands it the subclass. The result is an ordinary bound method,
 * so {@code type(Scores.named)} is {@code method} and {@code __func__} is the function underneath.
 *
 * <p>The wrapper is not callable: {@code C.__dict__["m"]()} is {@code TypeError: 'classmethod' object is
 * not callable}, because {@code PyClassMethod_Type} files no {@code tp_call} and so no {@code __call__}
 * row either — the one name by which its thirteen-row neighbour differs from its twelve.
 *
 * <p>{@link PyCallableWrapper}'s {@code __init__} copies the wrapped function's four names, and since
 * {@code cm_descr_get} binds a {@code method} rather than returning the field, a classmethod is the only
 * one of the two that answers {@code type(get)} as {@code 'method'} even when the field is {@code None}.
 */
public final class PyClassMethod extends PyCallableWrapper {

    /** {@code classmethod(f)}: the field, and the four names copied beside it. */
    PyClassMethod(PyObject function) {
        this();
        initialise(function);
    }

    /** {@code classmethod.__new__(classmethod)}: a wrapper around {@code None}, with no names. */
    PyClassMethod() {
        super(PyType.CLASS_METHOD);
    }

    /**
     * Bound to {@code owner}, whichever way it was reached.
     *
     * <p>{@code owner} is the class the attribute was found on, and CPython always has one: a read
     * through a value passes its type, and a one-argument {@code __get__} call has its second argument
     * filled in the same way by {@link Descriptors#called}. The value is never the receiver, whatever it
     * is — that is the whole of what this kind changes.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        PyObject klass = owner != null ? owner : obj.getType();
        if (wrapped() instanceof PyFunction python) {
            return new PyBoundMethod(klass, python);
        }
        // A classmethod around anything else — a built-in, a Java method, the None `__new__` leaves —
        // still arrives as a method of the class, so the binding is done by handing the class over at
        // the call. That last case never gets that far: `PyMethod_New(None, C)` is a method whose call
        // is the None's own complaint.
        return new PyBoundMethod(
                klass,
                nameOf(),
                (self, args, kwNames) -> {
                    PyObject[] withClass = new PyObject[args.length + 1];
                    withClass[0] = self;
                    System.arraycopy(args, 0, withClass, 1, args.length);
                    return Slots.call(wrapped(), withClass, PyBoundMethod.keywordTuple(kwNames));
                });
    }

    private String nameOf() {
        return wrapped().getAttr("__name__") instanceof PyStr text
                ? text.getValue()
                : "method";
    }
}
