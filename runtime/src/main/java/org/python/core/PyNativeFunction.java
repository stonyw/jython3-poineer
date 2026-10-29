package org.python.core;

/**
 * A function implemented in Java, seen from Python: what {@code print}, {@code len} and the methods of
 * the built-in types are made of.
 *
 * They share one shape so that a call site cannot tell them apart — all of them take CPython's
 * vectorcall arguments and answer with a value or a Python exception, which is why {@code CALL} has a
 * single implementation of argument count and keyword handling rather than one per kind of callee.
 */
public final class PyNativeFunction extends PyObject implements Descriptors.Protocol, FunctionFields {

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
     * CPython's {@code ml_doc}, kept exactly as the C source writes it, or null where the built-in says
     * nothing and {@code __doc__} reads {@code None}.
     *
     * <p>That includes the argument table {@code __text_signature__} reads itself out of: {@code all}'s
     * first line is {@code all($module, iterable, /)} and its {@code __doc__} is everything below the
     * {@code --} rule, because both names are cut from this one text by the same two helpers CPython
     * uses — {@code find_signature} and {@code skip_signature}. A handful of the builtins carry a
     * sentence of their own — {@code sys.excepthook} among them — and a person reading it off the
     * function is reading the same text the docs print.
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
    private final PyType declaredBy;

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

    /**
     * The module whose dictionary holds this function, or {@code null} for a member of a type.
     *
     * <p>CPython's {@code PyModule_AddFunctions} makes the module {@code m_self}, and a {@code METH_O}
     * body is handed that argument only to ignore it, so it shows through in three reads and nowhere in
     * the call: {@code __self__} answers the module, {@code __qualname__} stays the bare name a module's
     * function has, and {@code __reduce__} answers the name alone instead of a {@code getattr}. It is not
     * the {@link #receiver}, which the call puts in front of its arguments.
     */
    private PyObject home;

    /**
     * CPython's {@code m_module}: the one of this function's names that is storage rather than a read,
     * because {@code methodobject.c} files it as a {@code PyMemberDef} over the field.
     */
    private PyObject module;

    public PyNativeFunction(String name, Body body) {
        this(name, body, null, null, null, false, null);
    }

    private PyNativeFunction(
            String name,
            Body body,
            PyObject receiver,
            String doc,
            PyType declaredBy,
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
    static PyNativeFunction declaredBy(PyType owner, String name, Body body) {
        return new PyNativeFunction(name, body, null, null, owner, false, null);
    }

    /** This method read through {@code owner}, with the receiver already attached to it. */
    PyNativeFunction withReceiver(PyObject owner) {
        return keeping(new PyNativeFunction(name, body, owner, doc, declaredBy, onType, declaring));
    }

    /** This function answering {@code __doc__} with {@code text} rather than with {@code None}. */
    PyNativeFunction documented(String text) {
        return keeping(new PyNativeFunction(name, body, receiver, text, declaredBy, onType, declaring));
    }

    /**
     * The two fields a module's table writes, moved onto a copy of the function they were written on.
     *
     * <p>{@link #withReceiver} and {@link #documented} each change one thing about a function, and the
     * module it belongs to is not that thing — so a copy made after {@link #belongingTo} has run still
     * reads the same {@code __self__} and the same {@code __module__} as the entry the dictionary holds.
     */
    private PyNativeFunction keeping(PyNativeFunction copy) {
        copy.home = home;
        copy.module = module;
        return copy;
    }

    /**
     * {@code PyModule_AddFunctions}: the module a function is entered in becomes its {@code m_self} and
     * its name becomes its {@code m_module}.
     *
     * <p>Written here rather than in the constructor because this runtime builds the built-in namespace
     * first and the module that owns it afterwards, which is the same order CPython's module table is
     * filled in — {@code m_methods} is read once the {@code PyModuleDef} has a dictionary to put it in.
     */
    void belongingTo(PyModule owner) {
        home = owner;
        module = PyStr.of(owner.name());
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
        return declaredBy == null ? name : declaredBy.name() + "." + name;
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
            // Both of these are cut out of `ml_doc` by the same two walks `typeobject.c` uses for every
            // built-in's sentence, so the table's `__doc__` and its `__text_signature__` agree whatever
            // sentence was transcribed and whichever half of it a person asks for.
            case "__doc__" -> docOf(name, doc);
            case "__text_signature__" -> signatureOf(name, doc);
            case "__self__" -> receiver != null
                    ? receiver
                    : declaredBy != null
                            ? PyNone.INSTANCE
                            : home != null ? home : PyNotImplemented.INSTANCE;
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
    public PyObject functionSelf() {
        // `m_self`, in the three shapes `methodobject.c` lets a module's table and a type's take.
        return receiver != null ? receiver : declaredBy != null ? declaredBy : home;
    }

    @Override
    public Object functionDefinition() {
        return body;
    }

    @Override
    public PyObject moduleValue() {
        return module;
    }

    @Override
    public void writeModule(PyObject value) {
        module = value;
    }

    @Override
    public boolean eq(PyObject other) {
        // `meth_richcompare`: the receiver and the `PyMethodDef`, both by identity. Two reads of one
        // method of one class are therefore equal, `bytes.fromhex == bytes.fromhex`, while two objects
        // over two classes are not — which is what the identity below would get wrong for the copies a
        // read makes rather than the entries a dictionary keeps.
        return other instanceof PyNativeFunction one
                && one.body == body
                && one.functionSelf() == functionSelf();
    }

    @Override
    public long hash() {
        // `meth_hash`: the generic hash of the receiver, crossed with the address of the method.
        PyObject self = functionSelf();
        long crossed = (self == null ? 0 : System.identityHashCode(self))
                ^ System.identityHashCode(body);
        return PyHash.avoidMinusOne(crossed);
    }

    /**
     * {@code _PyType_GetDocFromInternalDoc}: the sentence with its argument table cut off, or {@code
     * None} where there was nothing to say.
     *
     * <p>The cut only happens when the text both starts with the function's own name and carries the
     * {@code )\n--\n\n} rule after its table — {@code find_signature} and {@code skip_signature} in
     * {@code typeobject.c}. A sentence written without a table is kept whole, which is the case for every
     * built-in here whose text was transcribed rather than generated.
     */
    static PyObject docOf(String name, String internal) {
        int rule = table(name, internal);
        String text = rule < 0 ? internal : internal.substring(rule + SIGNATURE_END.length());
        return text == null || text.isEmpty() ? PyNone.INSTANCE : PyStr.of(text);
    }

    /**
     * {@code _PyType_GetTextSignatureFromInternalDoc} for a row whose text is the only place a table can
     * come from.
     *
     * <p>CPython has a second way of answering this — {@link #signatureFromFlags} makes a table out of the
     * {@code METH_NOARGS} or {@code METH_O} a row was registered with — and a row whose body takes an
     * argument list and keyword names is registered with neither, so the text is all it has. The rows the
     * method tables file by hand, without a clinic text at all, carry the flag and read the other form.
     */
    static PyObject signatureOf(String name, String internal) {
        return signatureOf(name, internal, 0);
    }

    /**
     * The same read with the row's own {@code PyMethodDef} flags beside it, which CPython tries second.
     *
     * <p>The order is the C function's: a text that carries a table wins, and the flag is only reached for
     * the rows whose {@code ml_doc} is a sentence with no table above it or nothing at all — {@code
     * bytes.__getnewargs__}, {@code int.conjugate}, the five {@code mappingproxy} views, {@code
     * object}'s two hooks and the seven generic subscripts. Those answer {@code ($self, /)} or {@code
     * ($type, object, /)} in the reference interpreter because a flag says so, not because anyone wrote it.
     */
    static PyObject signatureOf(String name, String internal, int flags) {
        int rule = table(name, internal);
        if (rule < 0) {
            String invented = signatureFromFlags(flags);
            return invented == null ? PyNone.INSTANCE : PyStr.of(invented);
        }
        return PyStr.of(internal.substring(signatureStart(name, internal), rule + 1));
    }

    /** The rule {@code skip_signature} looks for: a bracket, then a {@code --}, then a blank line. */
    private static final String SIGNATURE_END = ")\n--\n\n";

    /** {@code methodobject.h}: the three flags {@link #signatureFromFlags} can answer a table for. */
    static final int METH_NOARGS = 0x0004;

    static final int METH_O = 0x0008;
    static final int METH_CLASS = 0x0010;
    static final int METH_STATIC = 0x0020;

    /** A row filed twice over two names, which is not part of the calling convention read here. */
    private static final int METH_COEXIST = 0x0040;

    /**
     * {@code signature_from_flags} (Objects/typeobject.c:888): the table CPython cuts out of a row's flags
     * when its text carries none, or {@code null} for every other calling convention.
     *
     * <p>Four of the six cases name a receiver, and they are the four this runtime reaches: a row that takes
     * no argument says whether the thing it was handed is the value or the type, and a row that takes one
     * says the same, so {@code ($self, /)} and {@code ($type, /)} sit under {@code METH_NOARGS} and {@code
     * ($self, object, /)} and {@code ($type, object, /)} under {@code METH_O}. The static pair is what a
     * {@code staticmethod} registered like those reads; no row here is, because every static clinic files
     * comes with a text of its own, which the read above answers from first.
     */
    private static String signatureFromFlags(int flags) {
        return switch (flags & ~METH_COEXIST) {
            case METH_NOARGS -> "($self, /)";
            case METH_NOARGS | METH_CLASS -> "($type, /)";
            case METH_NOARGS | METH_STATIC -> "()";
            case METH_O -> "($self, object, /)";
            case METH_O | METH_CLASS -> "($type, object, /)";
            case METH_O | METH_STATIC -> "(object, /)";
            default -> null;
        };
    }

    /**
     * Where a text's table ends, or -1 where it has none: {@code find_signature} and {@code
     * skip_signature} run in CPython's order, and the answer is the index of the bracket that closes it.
     */
    private static int table(String name, String internal) {
        int start = signatureStart(name, internal);
        if (start < 0) {
            return -1;
        }
        int rule = internal.indexOf(SIGNATURE_END, start);
        if (rule < 0) {
            return -1;
        }
        int blank = blankLine(internal, start);
        return blank >= 0 && blank < rule ? -1 : rule;
    }

    /**
     * {@code find_signature}: where the table begins, counted from the name, or -1 for a text that is not
     * one.
     *
     * <p>Only the last component of a dotted name counts — a doc written for {@code bytes.upper} is looked
     * for as {@code upper} — and the name must be followed straight by the opening bracket.
     */
    private static int signatureStart(String name, String internal) {
        if (internal == null) {
            return -1;
        }
        int dot = name.lastIndexOf('.');
        String last = dot < 0 ? name : name.substring(dot + 1);
        return internal.startsWith(last) && internal.length() > last.length()
                        && internal.charAt(last.length()) == '('
                ? last.length()
                : -1;
    }

    /**
     * The first blank line at or after {@code start}, which is where {@code skip_signature} gives up: a
     * table that has not reached its rule by then was never a table.
     */
    private static int blankLine(String internal, int start) {
        for (int at = start; at + 1 < internal.length(); at++) {
            if (internal.charAt(at) == '\n' && internal.charAt(at + 1) == '\n') {
                return at;
            }
        }
        return -1;
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
