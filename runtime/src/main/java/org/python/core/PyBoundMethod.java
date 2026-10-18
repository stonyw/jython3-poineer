package org.python.core;

/**
 * A bound method: {@code "a".upper} is one of these, and calling it passes {@code "a"} as the
 * receiver.
 *
 * The receiver is kept here rather than curried into the body so that {@code __self__} and
 * {@code __name__} are answerable, both of which CPython's own tests read.
 */
public final class PyBoundMethod extends PyObject {

    /** A native method body, with the receiver separated from the arguments. */
    public interface Body {
        PyObject apply(PyObject self, PyObject[] args, String[] kwNames);
    }

    private final PyObject target;
    private final String name;
    private final Body body;

    public PyBoundMethod(PyObject target, String name, Body body) {
        super(PyType.METHOD);
        this.target = target;
        this.name = name;
        this.body = body;
    }

    /** The keywords of a vectorcall argument list, as Java strings. */
    static String[] keywordNames(PyTuple kwNames) {
        if (kwNames == null) {
            return null;
        }
        String[] names = new String[kwNames.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ((PyStr) kwNames.at(i)).getValue();
        }
        return names;
    }

    /**
     * Whether this method already carries {@code owner} as its receiver.
     *
     * {@code LOAD_ATTR}'s method form hands {@code CALL} a receiver slot, and CPython fills it with
     * NULL when the attribute it found is a method bound to the very object it looked up — an
     * unboxing that is why {@code "a".upper()} does not pass {@code "a"} twice.
     */
    boolean receives(PyObject owner) {
        return target == owner;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return body.apply(target, args, keywordNames(kwNames));
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__self__" -> target;
            case "__name__" -> PyStr.of(name);
            case "__doc__" -> PyNone.INSTANCE;
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
        return "<built-in method " + name + " of " + target.getType().name() + " object>";
    }
}
