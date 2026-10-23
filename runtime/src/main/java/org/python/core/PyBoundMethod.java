package org.python.core;

/**
 * A bound method: {@code "a".upper} is one of these, and calling it passes {@code "a"} as the
 * receiver.
 *
 * The receiver is kept here rather than curried into the body so that {@code __self__} and
 * {@code __name__} are answerable, both of which CPython's own tests read.
 *
 * <p>There are two kinds, and they are the same thing seen from the two halves of the runtime: a
 * method of a built-in type has a {@link Body} written in Java, and a method of a class Python wrote is
 * a {@link PyFunction} whose first parameter is the receiver. Both come back from an attribute lookup on
 * a value, both are of type {@code method}, and both are unboxed by {@code LOAD_ATTR}'s method form so
 * that the receiver is not also passed as an argument.
 */
public final class PyBoundMethod extends PyObject {

    /** A native method body, with the receiver separated from the arguments. */
    public interface Body {
        PyObject apply(PyObject self, PyObject[] args, String[] kwNames);
    }

    private final PyObject target;
    private final String name;
    private final Body body;

    /** The sentence the built-in method's table carries, or null where it carries none. */
    private final String doc;

    /** The Python method this value wraps, or null when the body is Java's. */
    private final PyFunction function;

    public PyBoundMethod(PyObject target, String name, Body body) {
        this(target, name, body, null);
    }

    /** A Java-written method with the {@code ml_doc} its own type files beside the body. */
    PyBoundMethod(PyObject target, String name, Body body, String doc) {
        super(PyType.METHOD);
        this.target = target;
        this.name = name;
        this.body = body;
        this.doc = doc;
        this.function = null;
    }

    /** {@code p.as_text}: a function found on the instance's class, with the instance attached to it. */
    PyBoundMethod(PyObject target, PyFunction function) {
        super(PyType.METHOD);
        this.target = target;
        this.name = function.code().name();
        this.body = null;
        this.doc = null;
        this.function = function;
    }

    /**
     * The keywords of a vectorcall argument list, as Java strings.
     *
     * <p>An empty name list is no keywords at all, which is how CPython hands the case over: the
     * unpacking of {@code **} leaves {@code kwnames} null rather than building an empty tuple, and every
     * argument table reads the two spellings the same way. A method with no keyword table is therefore
     * refused by count — {@code takes exactly one argument (0 given)} — when a call unpacked an empty
     * mapping, not by the complaint about keywords.
     */
    static String[] keywordNames(PyTuple kwNames) {
        if (kwNames == null || kwNames.size() == 0) {
            return null;
        }
        String[] names = new String[kwNames.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ((PyStr) kwNames.at(i)).getValue();
        }
        return names;
    }

    /** The other direction, for a body that was given names as Java strings and has to call again. */
    static PyTuple keywordTuple(String[] kwNames) {
        if (kwNames == null) {
            return null;
        }
        PyObject[] names = new PyObject[kwNames.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = PyStr.interned(kwNames[i]);
        }
        return PyTuple.of(names);
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        if (function == null) {
            return body.apply(target, args, keywordNames(kwNames));
        }
        // The receiver is the function's first parameter, which is the whole of how self arrives.
        PyObject[] withSelf = new PyObject[args.length + 1];
        withSelf[0] = target;
        System.arraycopy(args, 0, withSelf, 1, args.length);
        return function.call(withSelf, kwNames);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__self__" -> target;
            case "__func__" -> function == null ? PyNotImplemented.INSTANCE : function;
            case "__name__" -> PyStr.of(name);
            case "__qualname__" -> function != null
                    ? function.getAttr("__qualname__")
                    : PyStr.of(target.getType().name() + "." + name);
            case "__doc__" -> function != null
                    ? function.getAttr("__doc__")
                    : doc == null ? PyNone.INSTANCE : PyStr.of(doc);
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public boolean eq(PyObject other) {
        // Bound methods compare on receiver and name, so a list's own append is never the append of a
        // second list, but is the same method object asked twice of one list.
        return other instanceof PyBoundMethod m
                && m.name.equals(name)
                && m.target.eq(target);
    }

    @Override
    public long hash() {
        return target.hash() * 31 + PyHash.textHash(name);
    }

    @Override
    public String repr() {
        if (function == null) {
            return "<built-in method " + name + " of " + target.getType().name() + " object>";
        }
        return "<bound method "
                + function.getAttr("__qualname__").str()
                + " of "
                + target.repr()
                + ">";
    }
}
