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

    /**
     * The callable a {@code types.MethodType(callable, instance)} wrap carries, or null for the two
     * kinds above.
     *
     * <p>Such a value is a {@code method} whose {@code __func__} is not a function at all, so it keeps
     * the callable for {@code __func__}, {@code __name__} and the {@code repr} that has to read them.
     */
    private final PyObject wrapped;

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
        this.wrapped = null;
    }

    /** {@code p.as_text}: a function found on the instance's class, with the instance attached to it. */
    PyBoundMethod(PyObject target, PyFunction function) {
        super(PyType.METHOD);
        this.target = target;
        this.name = function.code().name();
        this.body = null;
        this.doc = null;
        this.function = function;
        this.wrapped = null;
    }

    /** {@code types.MethodType(callable, instance)}: a method whose function is any callable. */
    PyBoundMethod(PyObject target, PyObject wrapped, Body body) {
        super(PyType.METHOD);
        this.target = target;
        this.name = wrappedName(wrapped);
        this.body = body;
        this.doc = null;
        this.function = null;
        this.wrapped = wrapped;
    }

    /** {@code method_repr}'s name: {@code __qualname__}, then {@code __name__}, then {@code "?"}. */
    private static String wrappedName(PyObject callable) {
        for (String attribute : new String[] {"__qualname__", "__name__"}) {
            PyObject name = callable.getAttr(attribute);
            if (name instanceof PyStr text) {
                return text.getValue();
            }
        }
        return "?";
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
    public boolean isCallable() {
        return true;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__self__" -> target;
            case "__func__" -> function != null
                    ? function
                    : wrapped != null ? wrapped : Slots.DECLINED;
            case "__name__" -> function != null
                    ? PyStr.of(name)
                    : PyStr.of(wrapped != null ? wrappedName(wrapped) : name);
            case "__qualname__" -> function != null
                    ? function.getAttr("__qualname__")
                    : PyStr.of(wrapped != null
                            ? wrappedName(wrapped)
                            : target.getType().name() + "." + name);
            case "__doc__" -> function != null
                    ? function.getAttr("__doc__")
                    : PyNativeFunction.docOf(name, doc);
            // `method_get`: a bound method of a Python function is a descriptor, but since gh-113157
            // `a.meth.__get__(b, B)` does not rebind — it answers a fresh method still bound to `a`, so
            // the call is only the argument check and the one refusal, `__get__(None)`. A built-in method
            // has no `__get__` at all; a `types.MethodType` wrap does.
            case "__get__" -> function == null && wrapped == null
                    ? Slots.DECLINED
                    : new PyNativeFunction("__get__", (args, kwNames) -> rebind(args, kwNames));
            default -> Slots.DECLINED;
        };
    }

    /**
     * {@code method_get}: {@code ($self, instance, owner=None, /)}.
     *
     * <p>Since gh-113157 a method of a Python function does not rebind: the answer is a bound method
     * naming the receiver it was already bound to, whatever instance and owner were given. The whole
     * of the call is therefore the argument check and the one refusal — {@code __get__(None)} with no
     * owner names nothing to bind to. A built-in method never reaches here: it has no {@code __get__}.
     */
    private PyObject rebind(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("wrapper __get__() takes no keyword arguments");
        }
        if (args.length < 1) {
            throw Slots.typeError("__get__ expected at least 1 argument, got 0");
        }
        if (args.length > 2) {
            throw Slots.typeError("__get__ expected at most 2 arguments, got %d", args.length);
        }
        if (args[0] == PyNone.INSTANCE && (args.length < 2 || args[1] == PyNone.INSTANCE)) {
            throw Slots.typeError("__get__(None, None) is invalid");
        }
        return function != null
                ? new PyBoundMethod(target, function)
                : new PyBoundMethod(target, wrapped, body);
    }

    @Override
    public boolean eq(PyObject other) {
        // `method_richcompare` compares the two halves by identity — the receiver and the function —
        // and never runs the receiver's own `__eq__`: `a1.f == a2.f` is false although `a1 == a2` is
        // true. A built-in method has no function object here, so its name stands in for one.
        if (!(other instanceof PyBoundMethod m) || m.target != target) {
            return false;
        }
        if (function != null) {
            return m.function == function;
        }
        return wrapped != null ? m.wrapped == wrapped : m.name.equals(name);
    }

    @Override
    public long hash() {
        // `method_hash`: the receiver is hashed by pointer and the function by its own hash. The
        // receiver's `__hash__` is never asked, which is why a method of a class whose `__hash__`
        // raises is still hashable.
        long receiver = PyHash.avoidMinusOne(System.identityHashCode(target));
        long callable = function != null
                ? function.hash()
                : wrapped != null
                        ? wrapped.hash()
                        : PyHash.avoidMinusOne(PyHash.textHash(name));
        return PyHash.avoidMinusOne(receiver ^ callable);
    }

    @Override
    public String repr() {
        if (function == null && wrapped == null) {
            return "<built-in method " + name + " of " + target.getType().name() + " object>";
        }
        String qualname = function != null
                ? function.getAttr("__qualname__").str()
                : wrappedName(wrapped);
        return "<bound method " + qualname + " of " + target.repr() + ">";
    }
}
