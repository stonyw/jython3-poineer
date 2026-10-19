package org.python.core;

/**
 * Root of the Jython3 object model, and the place every Python slot is declared.
 *
 * The type is held in a field instead of being inferred from the Java class, because a Python-level
 * subclass (M3) has to report its own type without a Java subclass existing.
 *
 * A slot that this type does not implement answers {@link PyNotImplemented} rather than throwing:
 * that refusal is the whole content of CPython's reflected-operator protocol, and only
 * {@link Slots}, which runs the protocol, turns a refusal into an error. {@code hash} and {@code eq}
 * keep throwing/returning definite answers because they sit under {@code PyDict} and {@code PySet},
 * where a refusal would have nowhere to go.
 */
public abstract class PyObject {

    /** Set by every constructor; only {@link PyType}'s registry rewrites it, to close the
     *  "type is an instance of type" cycle. */
    protected PyType type;

    protected PyObject() {
    }

    protected PyObject(PyType type) {
        this.type = type;
    }

    public final PyType getType() {
        return type;
    }

    /**
     * This value as the type that declares an inherited method expects its receiver to look like.
     *
     * <p>{@link Members} walks a value's type in lookup order, so a {@code bool} is answered by the
     * methods {@code int} declares — and those bodies read an integer's storage. In CPython the question
     * does not arise, because a bool <em>is</em> an int object with two more display rules; here the two
     * are separate Java classes, so crossing that gap in an inheritance walk means handing over the
     * value the body was written for. A value that has no such gap, which is every value but {@code bool},
     * answers for itself.
     *
     * @param declaring the type whose table the method was found in
     */
    PyObject adaptedFor(PyType declaring) {
        return this;
    }

    // --- slots: arithmetic -------------------------------------------------------------

    /**
     * {@code self op other}: the operand's own slot.
     *
     * @return the result, or {@link PyNotImplemented} to decline; {@link Slots} then asks the other
     *     operand's reflected slot and raises {@code TypeError} if that declines too
     */
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code other op self}: the reflected half of the same operator, {@code other}'s
     * {@code __radd__} and so on. Declining by default is what lets {@code 1 + 2.0} be a float and
     * {@code "a" + 1} be an error.
     */
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return PyNotImplemented.INSTANCE;
    }

    /**
     * The in-place slot ({@code __iadd__}), which Python consults before falling back to
     * {@link #binaryOp}. A type whose values are immutable — every type here until a user-defined
     * class can be mutable — inherits that fallback.
     */
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        return PyNotImplemented.INSTANCE;
    }

    /** {@code -self}, {@code ~self}, {@code not self}: see {@link UnaryOp}. */
    public PyObject unaryOp(UnaryOp op) {
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code self cmp other}, with {@code cmp} one of CPython's six rich-comparison slots.
     *
     * Equality is answered from {@link #eq}, which every value already has to define for hashing's
     * sake; ordering defaults to a refusal, so {@code "a" < 1} reaches {@code Slots} and becomes a
     * {@code TypeError} naming both types.
     */
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return switch (cmp) {
            case EQ -> PyBool.of(eq(other));
            case NE -> PyBool.of(!eq(other));
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * {@code self cmp other} for a type that can put its values in a total order.
     *
     * @param signal negative, zero or positive, as {@code self} sorts before, with or after {@code other}
     */
    protected static PyObject ordered(CompareOp cmp, int signal) {
        return switch (cmp) {
            case LT -> PyBool.of(signal < 0);
            case LE -> PyBool.of(signal <= 0);
            case GT -> PyBool.of(signal > 0);
            case GE -> PyBool.of(signal >= 0);
            case EQ -> PyBool.of(signal == 0);
            case NE -> PyBool.of(signal != 0);
        };
    }

    // --- slots: container protocol -----------------------------------------------------

    /** {@code len(self)}; a refusal means the value has no length. */
    public PyObject length() {
        return PyNotImplemented.INSTANCE;
    }

    /** {@code bool(self)}: a refusal lets {@link Slots} fall back to {@link #length()}. */
    public PyObject truth() {
        return PyNotImplemented.INSTANCE;
    }

    /** {@code self[key]}, including slices. */
    public PyObject getItem(PyObject key) {
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code self[key] = value}. The result is discarded by Python; the {@code null} that a
     * mutation returns here becomes {@code None} in {@link Slots}.
     */
    public PyObject setItem(PyObject key, PyObject value) {
        return PyNotImplemented.INSTANCE;
    }

    public PyObject delItem(PyObject key) {
        return PyNotImplemented.INSTANCE;
    }

    /** {@code item in self}. */
    public PyObject contains(PyObject item) {
        return PyNotImplemented.INSTANCE;
    }

    /** {@code iter(self)}: an iterator over this value, or a refusal. */
    public PyObject iterator() {
        return PyNotImplemented.INSTANCE;
    }

    // --- slots: attributes and calls ---------------------------------------------------

    /** {@code self.name}, looked up on the value itself for the builtin types. */
    public PyObject getAttr(String name) {
        return PyNotImplemented.INSTANCE;
    }

    /** {@code self.name = value}. */
    public PyObject setAttr(String name, PyObject value) {
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code del self.name}, which is a slot of its own rather than {@code setAttr(name, null)}:
     * CPython distinguishes an attribute that can be set to {@code None} from one that can be
     * removed, and a type that supports only the first must not answer the second.
     */
    public PyObject delAttr(String name) {
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code self(*args, **kwNames)}: the calling convention of CPython's vectorcall, where the
     * keyword values are the {@code kwNames.length} entries at the end of {@code args}.
     *
     * @param kwNames interned keyword names, or null when every argument is positional
     */
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return PyNotImplemented.INSTANCE;
    }

    /** Python {@code hash()}. */
    public long hash() {
        throw PyRaise.raise(ExceptionTypes.TYPE_ERROR,
                "unhashable type: '" + type.name() + "'");
    }

    /** Python {@code ==}, for the cases where the answer is definite. */
    public boolean eq(PyObject other) {
        return this == other;
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof PyObject o && eq(o);
    }

    /** The int form of {@link #hash()}, for use as a Java key. */
    @Override
    public final int hashCode() {
        long h = hash();
        return (int) (h ^ (h >>> 32));
    }

    /** Python {@code str()}: what {@code print} shows. */
    public String str() {
        return repr();
    }

    /** Python {@code repr()}. */
    public String repr() {
        return "<" + type.name() + " object at " + System.identityHashCode(this) + ">";
    }

    @Override
    public final String toString() {
        return repr();
    }
}
