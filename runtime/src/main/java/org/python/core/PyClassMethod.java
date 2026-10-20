package org.python.core;

/**
 * A method whose first argument is the class it was reached through: {@code classmethod}.
 *
 * <p>The object exists only to change where the receiver comes from. Reading {@code Scores.named} runs
 * {@code __get__} with no value and the class as its second argument, and CPython's
 * {@code cm_descr_get} ignores the value entirely — which is why {@code Scores().named("x")} and
 * {@code Scores.named("x")} both pass {@code Scores}, and why a subclass read hands it the subclass.
 * The result is an ordinary bound method, so {@code type(Scores.named)} is {@code method} and
 * {@code __func__} is the function underneath.
 *
 * <p>The names Python reads off the wrapper are the function's own: {@code __name__},
 * {@code __qualname__}, {@code __doc__}, {@code __module__} and {@code __wrapped__} all answer for what
 * was decorated, and the object itself is not callable — {@code C.__dict__["m"]()} is
 * {@code TypeError: 'classmethod' object is not callable}, unlike a {@link PyStaticMethod}, which
 * CPython made callable in 3.11.
 */
public final class PyClassMethod extends PyObject implements Descriptors.Protocol {

    private final PyObject function;

    PyClassMethod(PyObject function) {
        super(PyType.CLASS_METHOD);
        this.function = function;
    }

    /** The function this wrapper was built around. */
    PyObject function() {
        return function;
    }

    /**
     * Bound to {@code owner}, whichever way it was reached.
     *
     * <p>{@code owner} is the class the attribute was found on, and CPython always has one: a read
     * through a value passes its type, and a one-argument {@code __get__} call has its second argument
     * filled in the same way by {@link Descriptors#called}.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        PyObject klass = owner != null ? owner : obj.getType();
        if (function instanceof PyFunction python) {
            return new PyBoundMethod(klass, python);
        }
        // A classmethod around anything else — a built-in, a Java method — still has to arrive as a
        // method of the class, so the binding is done by handing the class over at the call.
        return new PyBoundMethod(
                klass,
                nameOf(),
                (self, args, kwNames) -> {
                    PyObject[] withClass = new PyObject[args.length + 1];
                    withClass[0] = self;
                    System.arraycopy(args, 0, withClass, 1, args.length);
                    return Slots.call(function, withClass, PyBoundMethod.keywordTuple(kwNames));
                });
    }

    private String nameOf() {
        return function.getAttr("__name__") instanceof PyStr text ? text.getValue() : "method";
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
        return "<classmethod(" + function.repr() + ")>";
    }
}
