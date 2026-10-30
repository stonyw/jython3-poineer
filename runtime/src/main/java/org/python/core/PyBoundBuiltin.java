package org.python.core;

/**
 * A method of a built-in type as the value whose table it came from holds it: {@code "text".join}, {@code
 * (5).bit_length}, {@code sys.stdout.readline}.
 *
 * <p>{@code method_get} (Objects/descrobject.c:137) makes one of these per read with {@code
 * PyCFunction_NewEx(ml, obj, NULL)} — the same {@code PyCFunctionObject} the builtin {@code len} is, with
 * {@code m_self} filled in. So this is not a {@link PyBoundMethod}, which is what a <em>Python</em>
 * function's {@code __get__} makes and what {@code PyMethod_Type} names {@code method}: a built-in row is
 * read, not boxed, and the sixteen-name dictionary of {@link PyType#BUILTIN_FUNCTION} is what answers the
 * eleven names a program asks of it, {@code dir()} thirty, and every refusal.
 *
 * <p>The three fields of that record are what this keeps: the receiver, the row it was read out of, and
 * {@code m_module}. Only the last is writable, and it starts empty here — the {@code NULL} {@code method_get}
 * hands {@code PyCFunction_NewEx} — which is why {@code "text".join.__module__} reads {@code None} while the
 * {@code __module__} of {@code len} reads {@code 'builtins'}. The receiver is not what the body is given:
 * {@code True.bit_length} keeps the bool as {@code __self__} and hands {@code int}'s body the integer it
 * stands for, since the two are separate Java classes and the walk that found the row crossed that gap.
 *
 * <p>Five names are read out of the row, and CPython reads all five through the same functions a plain
 * builtin uses: the name itself, that name qualified with the class of whatever the read ran on — {@code
 * Text("t").upper.__qualname__} is {@code 'Text.upper'} because {@code meth_get__qualname__} asks the
 * receiver's own type — and the sentence and the argument table cut out of {@code ml_doc} by {@link
 * PyNativeFunction}'s two knives, so a bound row answers exactly what its descriptor answers. {@code
 * __objclass__}, {@code __func__} and {@code __get__} are the three names a read has no record to answer.
 */
final class PyBoundBuiltin extends PyObject implements FunctionFields {

    private final String name;

    /** The whole internal text the row carries, or {@code null} where it carries none. */
    private final String doc;

    /** The {@code PyMethodDef} flag of the row, for the ten whose text names no argument table. */
    private final int flags;

    /** The row's body, kept for the identity {@code meth_richcompare} reads beside the receiver. */
    private final PyBoundMethod.Body definition;

    /** {@code m_self}: the value the read ran on. */
    private final PyObject receiver;

    /** {@code m_self} as the body reads it — see {@link PyObject#adaptedFor}. */
    private final PyObject argument;

    /** {@code m_module}, empty until a program writes it. */
    private PyObject module;

    PyBoundBuiltin(
            String name,
            String doc,
            int flags,
            PyBoundMethod.Body definition,
            PyObject receiver,
            PyObject argument) {
        super(PyType.BUILTIN_FUNCTION);
        this.name = name;
        this.doc = doc;
        this.flags = flags;
        this.definition = definition;
        this.receiver = receiver;
        this.argument = argument;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return definition.apply(argument, args, PyBoundMethod.keywordNames(kwNames));
    }

    @Override
    public boolean isCallable() {
        return true;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> PyStr.of(name);
            // The class of the receiver, not the one that declares the row: `True.bit_length` says
            // `bool.bit_length` because `meth_get__qualname__` asks `Py_TYPE(m_self)`.
            case "__qualname__" -> PyStr.of(receiver.getType().dottedName() + "." + name);
            case "__self__" -> receiver;
            case "__doc__" -> PyNativeFunction.docOf(name, doc);
            case "__text_signature__" -> PyNativeFunction.signatureOf(name, doc, flags);
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public PyObject functionSelf() {
        return receiver;
    }

    @Override
    public Object functionDefinition() {
        return definition;
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
        // `meth_richcompare`: the receiver by pointer, then the C function pointer of the row. Two reads of
        // one method of one value are therefore equal, and never one object, since each read is a fresh
        // `PyCFunction_NewEx` — and a method of the same name over a second value is not equal at all.
        return other instanceof PyBoundBuiltin one
                && one.receiver == receiver
                && one.definition == definition;
    }

    @Override
    public long hash() {
        // `meth_hash`: the identity of the receiver crossed with the pointer of the row's function.
        long crossed = System.identityHashCode(receiver) ^ System.identityHashCode(definition);
        return PyHash.avoidMinusOne(crossed);
    }

    @Override
    public String repr() {
        // CPython words this `<built-in method join of str object at 0x…>`, the address being the
        // receiver's; see the plan's standing note on addresses.
        return "<built-in method " + name + " of " + receiver.getType().name() + " object>";
    }
}
