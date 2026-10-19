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

    /**
     * The owner this method was reached through, or null when it is a plain function.
     *
     * {@code int.mro} is one of these: a builtin method that needs no receiver of its own keeps its body
     * and gains the type it was read from, which is what makes {@code int.mro()} answer for {@code int}
     * while {@code str.upper} — a method that does take a receiver — stays a
     * {@link PyMethodDescriptor}. CPython hands out a fresh object each time, so {@code int.mro is int.mro}
     * is false, and this is a fresh object for the same reason.
     */
    private final PyObject receiver;

    public PyNativeFunction(String name, Body body) {
        this(name, body, null);
    }

    private PyNativeFunction(String name, Body body, PyObject receiver) {
        super(PyType.BUILTIN_FUNCTION);
        this.name = name;
        this.body = body;
        this.receiver = receiver;
    }

    /** This method read through {@code owner}, with the receiver already attached to it. */
    PyNativeFunction withReceiver(PyObject owner) {
        return new PyNativeFunction(name, body, owner);
    }

    /** The name CPython qualifies a receiver-bound method with: {@code int.mro}. */
    String qualifiedName() {
        if (receiver instanceof PyType owner) {
            return owner.name() + "." + name;
        }
        return name;
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
        if (receiver == null) {
            return body.apply(args, PyBoundMethod.keywordNames(kwNames));
        }
        PyObject[] withSelf = new PyObject[args.length + 1];
        withSelf[0] = receiver;
        System.arraycopy(args, 0, withSelf, 1, args.length);
        return body.apply(withSelf, PyBoundMethod.keywordNames(kwNames));
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> PyStr.of(name);
            case "__qualname__" -> PyStr.of(qualifiedName());
            case "__doc__" -> PyNone.INSTANCE;
            case "__self__" -> receiver == null ? PyNotImplemented.INSTANCE : receiver;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public String repr() {
        return receiver == null
                ? "<built-in function " + name + ">"
                : "<built-in method " + name + " of " + receiver.getType().name() + " object>";
    }
}
