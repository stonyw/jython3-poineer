package org.python.core;

/**
 * {@code NotImplemented}: the answer a slot gives when it declines to handle the other operand.
 *
 * <p>Declining is what makes CPython's reflected-operator protocol work, so it has to be representable
 * inside the JVM rather than flattened into a Java exception — and it has to be representable as an
 * <em>ordinary value</em> too, because a program can put it in a list, use it as a dict key and hand it
 * back out of a subscript. Those two roles were one object until {@link Slots#DECLINED} took the second:
 * this one is now only the value, and it behaves like the singleton CPython files it under.
 *
 * <p>Two of {@code NotImplementedType}'s own slots are what it is famous for. {@code tp_as_number} holds
 * exactly one entry, {@code notimplemented_bool} ({@code Objects/object.c:2365}), so the value refuses
 * every boolean question rather than answering true the way a one-field object would; and {@code tp_hash}
 * is empty, which leaves the identity hash {@code object} gives and makes it usable as a set element.
 */
public final class PyNotImplemented extends PyObject {

    public static final PyNotImplemented INSTANCE = new PyNotImplemented();

    private PyNotImplemented() {
        super(PyType.NOTIMP);
    }

    /** {@code notimplemented_bool} — the only entry of {@code notimplemented_as_number}. */
    @Override
    public PyObject truth() {
        throw Slots.typeError("NotImplemented should not be used in a boolean context");
    }

    /** CPython hashes {@code NotImplemented} by address, as it does every value with no {@code tp_hash}. */
    @Override
    public long hash() {
        return PyHash.textHash("NotImplemented");
    }

    @Override
    public String str() {
        return "NotImplemented";
    }

    @Override
    public String repr() {
        return "NotImplemented";
    }
}
