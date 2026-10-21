package org.python.core;

import java.util.Arrays;

/**
 * A method reached through its type rather than through one of its values: {@code str.upper},
 * {@code list.append}, {@code int.bit_length}.
 *
 * <p>CPython keeps one of these per method in the type's dictionary, and the difference from a
 * {@link PyBoundMethod} is only where the receiver comes from — a bound method has it already, a
 * descriptor takes it as its first argument. That is what makes {@code str.upper("abc")} and
 * {@code sorted(words, key=str.lower)} work, and it is the same shape a Java class's unbound method
 * has, which is why the interop layer hands out nothing else.
 *
 * <p>The call is handed to the receiver's own attribute lookup rather than to the table body, so that
 * the two spellings of the same method can never disagree: {@code int.bit_length(True)} answers exactly
 * what {@code True.bit_length()} does, including when the value's type reports that it has no such
 * method. That check against the receiver happens first, because a {@link ClassCastException} from
 * inside a {@link Members} body would be a runtime bug dressed up as a Python error.
 */
public final class PyMethodDescriptor extends PyObject implements Descriptors.Protocol {

    private final PyType owner;
    private final String name;

    PyMethodDescriptor(PyType owner, String name) {
        super(PyType.METHOD_DESCRIPTOR);
        this.owner = owner;
        this.name = name;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        if (args.length == 0) {
            throw Slots.typeError("unbound method %s.%s() needs an argument", owner.name(), name);
        }
        PyObject self = checked(args[0]);
        return Slots.call(
                Slots.getAttr(self, name),
                Arrays.copyOfRange(args, 1, args.length),
                kwNames);
    }

    /**
     * The method as an attribute: itself through the class, bound through a value.
     *
     * <p>{@code int.__dict__["bit_length"].__get__(3)} and {@code (3).bit_length} are the same method
     * object by CPython's account, and this is the one place both are answered.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject unused) {
        return obj == null ? this : Slots.getAttr(checked(obj), name);
    }

    /** The receiver, checked against the type whose method this is. */
    private PyObject checked(PyObject self) {
        if (!owner.instanceOf(self)) {
            throw Slots.typeError(
                    "descriptor '%s' for '%s' objects doesn't apply to a '%s' object",
                    name, owner.name(), self.getType().name());
        }
        return self;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> PyStr.of(name);
            case "__qualname__" -> PyStr.of(owner.name() + "." + name);
            // A method table registers a body and no sentence, so the name the descriptor answers with is
            // the one CPython leaves NULL: no table entry carries the docstring written beside the method.
            case "__doc__" -> PyNone.INSTANCE;
            case "__get__" -> new PyNativeFunction(
                    "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames));
            default -> PyNotImplemented.INSTANCE;
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
        return "<method '" + name + "' of '" + owner.name() + "' objects>";
    }
}
