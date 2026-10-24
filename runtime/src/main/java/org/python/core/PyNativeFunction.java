package org.python.core;

/**
 * A function implemented in Java, seen from Python: what {@code print}, {@code len} and the methods of
 * the built-in types are made of.
 *
 * They share one shape so that a call site cannot tell them apart — all of them take CPython's
 * vectorcall arguments and answer with a value or a Python exception, which is why {@code CALL} has a
 * single implementation of argument count and keyword handling rather than one per kind of callee.
 */
public final class PyNativeFunction extends PyObject implements Descriptors.Protocol {

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
     * The owner this function was reached through, or null when it is a plain function.
     *
     * {@code int.mro} is one of these: a builtin method that needs no receiver of its own keeps its body
     * and gains the type it was read from, which is what makes {@code int.mro()} answer for {@code int}
     * while {@code str.upper} — a method that does take a receiver — stays a
     * {@link PyMethodDescriptor}. CPython hands out a fresh object each time, so {@code int.mro is int.mro}
     * is false, and this is a fresh object for the same reason.
     */
    private final PyObject receiver;

    /**
     * CPython's {@code ml_doc}, or null where the built-in has none and {@code __doc__} reads {@code None}.
     *
     * <p>A handful of the module-level builtins carry a sentence of their own — {@code sys.excepthook}
     * among them — and a person reading it off the function is reading the same text the docs print.
     */
    private final String doc;

    /**
     * The type a static method is named after, or null for every other builtin.
     *
     * <p>{@code bytes.maketrans} belongs to a type but takes no receiver, so CPython qualifies its name
     * with the type ({@code __qualname__} is {@code bytes.maketrans}) while its {@code __self__} reads
     * {@code None}. The name alone is not enough to print that, and the receiver is not the answer,
     * because a receiver would also be prepended to the arguments.
     */
    private final String declaredBy;

    /**
     * Whether the first argument of this body is the class it was reached through: {@code METH_CLASS}.
     *
     * <p>CPython keeps such a builtin in the type's dictionary as a {@code classmethod_descriptor}, whose
     * getter ignores the object it was handed and binds to the <em>class the read ran on</em> — which is
     * what makes {@code bool.from_bytes(b'\xff')} a {@code bool} and {@code int.from_bytes} the same call
     * over {@code int}. A function that is not one of these is not a descriptor at all: reading
     * {@code bytes.maketrans} hands out the very object the dictionary holds.
     */
    private final boolean onType;

    /**
     * The type whose dictionary holds this class method, or {@code null} for every other builtin.
     *
     * <p>A {@code classmethod_descriptor} names its declaring type in three places: as {@code
     * __objclass__}, as the type in its {@code method_repr} — {@code <method 'fromhex' of 'bytes'
     * objects>} — and as the qualifier of the {@code __qualname__} the dictionary entry answers with. Once
     * a class is bound to the method it is an ordinary {@code builtin_function_or_method} again and says
     * none of that: {@code bytes.fromhex} has no {@code __objclass__} at all.
     */
    private final PyType declaring;

    public PyNativeFunction(String name, Body body) {
        this(name, body, null, null, null, false, null);
    }

    private PyNativeFunction(
            String name,
            Body body,
            PyObject receiver,
            String doc,
            String declaredBy,
            boolean onType,
            PyType declaring) {
        super(
                onType && receiver == null
                        ? PyType.CLASS_METHOD_DESCRIPTOR
                        : PyType.BUILTIN_FUNCTION);
        this.name = name;
        this.body = body;
        this.receiver = receiver;
        this.doc = doc;
        this.declaredBy = declaredBy;
        this.onType = onType;
        this.declaring = declaring;
    }

    /**
     * A class method of {@code owner} as its dictionary holds it: with no receiver attached yet.
     *
     * <p>The receiver is what the read makes, not what the table keeps, so {@code int.from_bytes} and
     * {@code bool.from_bytes} are two objects naming two different classes over one body.
     */
    static PyNativeFunction onType(PyType owner, String name, Body body) {
        return new PyNativeFunction(name, body, null, null, null, true, owner);
    }

    /**
     * A method of {@code owner} that takes no receiver: {@code bytes.maketrans}.
     *
     * <p>One object serves the type and every value of it, which is what makes {@code bytes.maketrans is
     * b''.maketrans} true, and it answers {@code __self__} with {@code None} rather than with the type
     * that names it.
     */
    static PyNativeFunction declaredBy(String owner, String name, Body body) {
        return new PyNativeFunction(name, body, null, null, owner, false, null);
    }

    /** This method read through {@code owner}, with the receiver already attached to it. */
    PyNativeFunction withReceiver(PyObject owner) {
        return new PyNativeFunction(name, body, owner, doc, declaredBy, onType, declaring);
    }

    /** This function answering {@code __doc__} with {@code text} rather than with {@code None}. */
    PyNativeFunction documented(String text) {
        return new PyNativeFunction(name, body, receiver, text, declaredBy, onType, declaring);
    }

    /**
     * Reading this member as an attribute of a class, which is the whole of a class method's descriptor
     * half: the type the read ran on becomes the receiver, and the object it ran on — if there was one —
     * is dropped, exactly as {@code classmethod_get} drops it.
     *
     * <p>Anything already bound answers with itself, so a second read of a member that a table hands out
     * bound cannot bind the receiver again.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        return onType && receiver == null ? boundTo(owner) : this;
    }

    /**
     * {@code classmethod_get}: the class a read runs on, checked before it is attached.
     *
     * <p>Two refusals, both naming the type that declares the method rather than the one being bound. The
     * second is what stops {@code int.__dict__["from_bytes"].__get__(None, str)} from handing out a method
     * over a class that could never hold the value it builds.
     */
    private PyNativeFunction boundTo(PyObject owner) {
        if (!(owner instanceof PyType type)) {
            throw Slots.typeError(
                    "descriptor '%s' for type '%s' needs a type, not a '%s' as arg 2",
                    name, declaring.filedName(), owner.getType().filedName());
        }
        if (!type.matches(declaring)) {
            throw Slots.typeError(
                    "descriptor '%s' requires a subtype of '%s' but received '%s'",
                    name, declaring.filedName(), type.filedName());
        }
        return withReceiver(owner);
    }

    /** Only a class method is a descriptor; a static method or a plain builtin is not one. */
    @Override
    public boolean binds() {
        return onType;
    }

    /** The name CPython qualifies a receiver-bound method with: {@code int.mro}. */
    String qualifiedName() {
        if (receiver instanceof PyType owner) {
            return owner.name() + "." + name;
        }
        if (declaring != null) {
            return declaring.name() + "." + name;
        }
        return declaredBy == null ? name : declaredBy + "." + name;
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
            if (onType) {
                return classMethodCall(args, kwNames);
            }
            return body.apply(args, PyBoundMethod.keywordNames(kwNames));
        }
        PyObject[] withSelf = new PyObject[args.length + 1];
        withSelf[0] = receiver;
        System.arraycopy(args, 0, withSelf, 1, args.length);
        return body.apply(withSelf, PyBoundMethod.keywordNames(kwNames));
    }

    @Override
    public boolean isCallable() {
        return true;
    }

    /**
     * {@code classmethoddescr_call}: calling the dictionary's own entry, which takes its class as the
     * first argument and then behaves as the bound method it makes itself into.
     *
     * <p>{@code bytes.__dict__["fromhex"](bytes, "61")} is {@code bytes.fromhex("61")}, and the argument
     * that names no class at all is refused before the body is reached — with the sentence a descriptor
     * tells rather than the one the method's own parameter list would.
     */
    private PyObject classMethodCall(PyObject[] args, PyTuple kwNames) {
        String[] names = PyBoundMethod.keywordNames(kwNames);
        if (Arguments.positional(args, names) < 1) {
            throw Slots.typeError(
                    "descriptor '%s' of '%s' object needs an argument", name, declaring.filedName());
        }
        return boundTo(args[0])
                .call(java.util.Arrays.copyOfRange(args, 1, args.length), kwNames);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> PyStr.of(name);
            case "__qualname__" -> PyStr.of(qualifiedName());
            case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
            case "__self__" -> receiver != null
                    ? receiver
                    : declaredBy == null ? PyNotImplemented.INSTANCE : PyNone.INSTANCE;
            // Only the dictionary's own entry has an owner to name; the method a read makes has none.
            case "__objclass__" -> declaring != null && receiver == null
                    ? declaring
                    : PyNotImplemented.INSTANCE;
            // A classmethod_descriptor is a descriptor; the built-in method a read makes is not one, and
            // neither is a plain function like `len`, which CPython says has no `__get__` at all.
            case "__get__" -> onType && receiver == null
                    ? new PyNativeFunction(
                            "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames))
                    : PyNotImplemented.INSTANCE;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public String repr() {
        if (receiver != null) {
            return "<built-in method " + name + " of " + receiver.getType().name() + " object>";
        }
        if (declaring != null) {
            return "<method '" + name + "' of '" + declaring.name() + "' objects>";
        }
        // CPython prints the type object a static method was read from, which it keeps behind
        // `__self__` even though that reads None; the address is the rest of its sentence.
        return declaredBy == null
                ? "<built-in function " + name + ">"
                : "<built-in method " + name + " of type object>";
    }
}
