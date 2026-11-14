package org.python.core;

import java.util.Arrays;

/**
 * A method reached through its type rather than through one of its values: {@code str.upper},
 * {@code list.append}, {@code int.bit_length}.
 *
 * <p>CPython keeps one of these per method in the type's dictionary, and the difference from a {@link
 * PyBoundBuiltin} is only where the receiver comes from — a method read through a value has it already, a
 * descriptor takes it as its first argument. That is what makes {@code str.upper("abc")} and {@code
 * sorted(words, key=str.lower)} work, and it is the same shape a Java class's unbound method
 * has, which is why the interop layer hands out nothing else.
 *
 * <p>The call is handed to the body the descriptor names, bound to the receiver, rather than to a
 * name lookup on the receiver: {@code int.bit_length(True)} answers exactly what {@code
 * True.bit_length()} does, and a Python subclass that overrides the name does not intercept either
 * spelling. The check against the receiver happens first, because a {@link ClassCastException} from
 * inside a {@link Members} body would be a runtime bug dressed up as a Python error.
 */
public final class PyMethodDescriptor extends PyObject implements Descriptors.Protocol {

    private final PyType owner;
    private final String name;

    /** The whole internal text the method table carries, or {@code null} where it carries none. */
    private final String doc;

    /**
     * The flag the row was registered with, which is where a table comes from when the text carries none.
     *
     * <p>Only the two flags {@code signature_from_flags} reads are ever kept here — {@code METH_NOARGS} for
     * {@code int.conjugate} and {@code str.__getnewargs__}, {@code METH_O} for a one-argument row whose
     * clinic text names none — and every other row arrives with nought, which answers {@code None} for a
     * table the way CPython's default case does.
     */
    private final int flags;

    PyMethodDescriptor(PyType owner, String name, String doc, int flags) {
        super(PyType.METHOD_DESCRIPTOR);
        this.owner = owner;
        this.name = name;
        this.doc = doc;
        this.flags = flags;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        if (Arguments.positional(args, PyBoundMethod.keywordNames(kwNames)) == 0) {
            throw Slots.typeError("unbound method %s.%s() needs an argument", owner.name(), name);
        }
        PyObject self = checked(args[0]);
        return Slots.call(
                implementationOf(self),
                Arrays.copyOfRange(args, 1, args.length),
                kwNames);
    }

    @Override
    public boolean isCallable() {
        return true;
    }

    /**
     * The method as an attribute: itself through the class, bound through a value.
     *
     * <p>{@code int.__dict__["bit_length"].__get__(3)} and {@code (3).bit_length} are the same method
     * object by CPython's account, and this is the one place both are answered.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        if (obj == null) {
            return this;
        }
        PyObject receiver = checked(obj);
        // `method_get` reads the class a `METH_METHOD` row was handed before it binds: the row needs
        // one, so a read that names anything else is refused by name. Every other row ignores it.
        if ((flags & PyNativeFunction.METH_METHOD) != 0 && !(owner instanceof PyType)) {
            throw Slots.typeError(
                    "descriptor '%s' needs a type, not '%s', as arg 2",
                    name, owner.getType().filedName());
        }
        return implementationOf(receiver);
    }

    /**
     * The body this descriptor names, bound to {@code self}, rather than whatever the name answers.
     *
     * <p>{@code PyObject_GetAttr} would be the wrong lookup: a Python class may override a slot that a
     * built-in base declares, and {@code super().__getitem__(key)} has to reach the base's own C function
     * rather than the override it started in. {@link Members#find} asks the built-in tables along the
     * value's MRO and keeps {@code self} as the reported receiver while the body reads the adapted value,
     * which is exactly {@code method_get}'s answer. The attribute lookup is kept as the fallback for a
     * row no table declares, which is where a refusal already lives.
     */
    private PyObject implementationOf(PyObject self) {
        PyObject method = Members.find(self, name);
        return method != Slots.DECLINED ? method : Slots.getAttr(self, name);
    }

    /** The receiver, checked against the type whose method this is. */
    private PyObject checked(PyObject self) {
        if (!owner.instanceOf(self)) {
            throw Slots.typeError(
                    "descriptor '%s' for '%s' objects doesn't apply to a '%s' object",
                    name, owner.filedName(), self.getType().filedName());
        }
        return self;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> PyStr.of(name);
            case "__qualname__" -> PyStr.of(owner.name() + "." + name);
            // Every descriptor of CPython's `PyDescrObject` family answers this, and answers it with the
            // type whose method it is — the same type the repr names.
            case "__objclass__" -> owner;
            // The sentence is the table's, so a descriptor answers with it and a method bound from the same
            // entry answers with the same text; where the table declares none this is the None CPython
            // leaves NULL beside the function pointer. Both names are read out of the one text the same way
            // a builtin function reads them, because `descr_get_doc` and `descr_get_text_signature` call
            // the same two functions `methodobject.c` does — with the method's own name, not its qualified
            // one, as the prefix to look for. The table has a second source, though, that `__doc__` has no
            // part in: a row whose text names none is answered from the flag it was registered with.
            case "__doc__" -> PyNativeFunction.docOf(name, doc);
            case "__text_signature__" -> PyNativeFunction.signatureOf(name, doc, flags);
            // No `__get__` among these: `add_operators` files the kind's own `tp_descr_get` in
            // `method_descriptor`'s dictionary, so a read of the name walks there and answers a
            // method-wrapper rather than a function made here.
            default -> Slots.DECLINED;
        };
    }

    @Override
    public boolean eq(PyObject other) {
        // One descriptor per type and name, so `str.upper is str.upper` holds and a descriptor of a
        // different type's never compares equal to it.
        return other instanceof PyMethodDescriptor one && one.name.equals(name) && one.owner == owner;
    }

    @Override
    public long hash() {
        return PyHash.textHash(owner.name() + "." + name);
    }

    @Override
    public String repr() {
        return "<method '" + name + "' of '" + owner.filedName() + "' objects>";
    }
}
