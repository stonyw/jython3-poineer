package org.python.core;

/**
 * A function implemented in Java, seen from Python: what {@code print}, {@code len} and the methods of
 * the built-in types are made of.
 *
 * They share one shape so that a call site cannot tell them apart — all of them take CPython's
 * vectorcall arguments and answer with a value or a Python exception, which is why {@code CALL} has a
 * single implementation of argument count and keyword handling rather than one per kind of callee.
 */
public final class PyNativeFunction extends PyObject {

    /**
     * A native body.
     *
     * @param args positional arguments, followed — when {@code kwNames} is not null — by one value
     *     per keyword, in the order {@code kwNames} gives
     * @param kwNames interned keyword names, or null when the call was purely positional
     */
    public interface Body {
        PyObject apply(PyObject[] args, String[] kwNames);
    }

    private final String name;
    private final Body body;

    public PyNativeFunction(String name, Body body) {
        super(PyType.BUILTIN_FUNCTION);
        this.name = name;
        this.body = body;
    }

    /** A body that takes positionals only; a keyword argument reaching it is an error, as in Python. */
    public static PyNativeFunction positional(String name, java.util.function.Function<PyObject[], PyObject> body) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            if (kwNames != null) {
                throw Slots.typeError("%s() takes no keyword arguments", name);
            }
            return body.apply(args);
        });
    }

    public String name() {
        return name;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return body.apply(args, PyBoundMethod.keywordNames(kwNames));
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__", "__qualname__" -> PyStr.of(name);
            case "__doc__" -> PyNone.INSTANCE;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public String repr() {
        return "<built-in function " + name + ">";
    }
}
