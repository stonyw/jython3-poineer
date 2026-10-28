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

    /**
     * {@code len(self)}; a refusal means the value has no length.
     *
     * <p>For a value whose class was written in Python this is {@code slot_sq_length} asking the namespace:
     * a class that files {@code None} under the name is not asking the question to go unanswered, because
     * {@code maybe_call_special_no_args} calls what it found and the call machinery refuses — so {@code
     * __len__ = None} says {@code 'NoneType' object is not callable} rather than {@code has no len()}.
     */
    public PyObject length() {
        if (Specials.lookUp(getType(), "__len__") == PyNone.INSTANCE) {
            return Slots.call(PyNone.INSTANCE, new PyObject[0], null);
        }
        return Specials.invoke(this, "__len__");
    }

    /**
     * {@code bool(self)}: a refusal lets {@link Slots} fall back to {@link #length()}.
     *
     * <p>{@code slot_nb_bool} asks the same namespace, but with somewhere to record that the name was found
     * and was {@code None}, and refuses by naming the class when it did. A filed {@code None} is therefore a
     * third answer besides the two a {@code __bool__} usually gives, and is not the same as declaring
     * nothing.
     */
    public PyObject truth() {
        if (Specials.lookUp(getType(), "__bool__") == PyNone.INSTANCE) {
            throw Slots.typeError(
                    "'%s' cannot be interpreted as a boolean", getType().filedName());
        }
        return Specials.invoke(this, "__bool__");
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

    /**
     * {@code self.name}, looked up on the value itself for the builtin types.
     *
     * <p>A refusal here is not yet a missing attribute: {@link Slots} answers {@code object}'s own names
     * after it, which is how every value reads {@code __class__} without declaring it. See
     * {@link ClassAttribute}.
     */
    public PyObject getAttr(String name) {
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code self.name = value}, for the values whose type declares no attribute writing of its own.
     *
     * <p>That is CPython's {@code tp_setattro == PyObject_GenericSetAttr}, and almost every built-in type is
     * one: the refusal a program sees for {@code (1,).__doc__ = 'x'} is {@link GenericAttributes}' answer
     * about the descriptor {@code tuple} files, not a complaint from this class about a name it does not
     * know. A type with storage to keep — an instance of a class Python wrote, an exception, a module —
     * overrides this and says where the name goes.
     */
    public PyObject setAttr(String name, PyObject value) {
        GenericAttributes.write(this, name, value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code self.name} for a field the value's class declared in {@code __slots__} — CPython's {@code
     * T_OBJECT_EX} offset, which this runtime keeps in a storage of its own beside the attributes a value
     * can list.
     *
     * <p>Reached only through the {@code member_descriptor} the class files, so the name is already known
     * to be one of those fields, and an empty one is the {@code AttributeError} the getter of that offset
     * answers with rather than a missing attribute the class dictionary would have to be asked about. Only
     * the two value shapes that keep attribute storage — an instance of a class Python wrote, and an
     * exception — override this; the class system builds no other class with slots to read.
     */
    PyObject slotValue(String name) {
        throw Slots.attributeError(
                "'%s' object has no attribute '%s'", getType().filedName(), name);
    }

    /** {@code self.name = value} for a slot, with null for the {@code del} that empties the field. */
    void writeSlot(String name, PyObject value) {
        throw Slots.attributeError(
                "'%s' object has no attribute '%s'", getType().filedName(), name);
    }

    /**
     * The dictionary a generic write puts a name into — {@code _PyObject_ComputedDictPointer} and its
     * managed-dict cousin, which CPython reaches as a pointer and so reads as absent for a value whose type
     * keeps no storage.
     *
     * <p>Asked only after {@link PyType#hasValueDict()} has said this value's class stores one, so the two
     * together are the answer to {@code dictptr == NULL} and the reason a class with {@code __slots__} is
     * refused by name instead of gaining an attribute. A value with storage of its own overrides this; the
     * built-in types that have none answer null and are refused.
     */
    PyDict attributeStore() {
        return null;
    }

    /**
     * Whether {@code name} is one this value's own class files, rather than one {@code object} answers.
     *
     * <p>The question is only asked about {@code __class__}, and only a class built at Python level can
     * answer it: such a class may file a member under that name, which CPython's lookup then treats as an
     * ordinary attribute and writes to the instance dictionary instead of swapping the type, or may own
     * every write outright through {@code __setattr__} or {@code __delattr__}. A built-in type files
     * nothing, so {@link Slots} runs {@link ClassAttribute}'s rule for its values.
     */
    boolean filedByItsClass(String name) {
        return false;
    }

    /**
     * This value as the method form of {@code LOAD_ATTR} hands it to {@code CALL}.
     *
     * <p>Only a member CPython leaves unbound in that form answers differently: the copy exists so that a
     * refusal about its argument list is written under the class declaring the method rather than under
     * the class the read was made from, which is the difference between {@code e.with_traceback()} and
     * {@code m = e.with_traceback; m()}. Everything else is already bound by the read itself, or is not a
     * method at all.
     */
    PyObject methodForm() {
        return this;
    }

    /**
     * This value's storage given the class {@code x.__class__ = Y} put on it.
     *
     * <p>Only an exception has anything beside the pointer to keep in step: it holds its class twice, once
     * for {@code type(e)} and once as the class whose {@code __init__} and name the display reads.
     */
    void reassignedTo(PyType kind) {
        type = kind;
    }

    /**
     * {@code del self.name}, which is a slot of its own in this runtime rather than {@code setAttr(name,
     * null)}: CPython distinguishes an attribute that can be set to {@code None} from one that can be
     * removed, and a type that supports only the first must not answer the second.
     *
     * <p>The default is the other half of {@link #setAttr}'s, and reaches the same descriptor the write does
     * — CPython hands it {@code NULL} through {@code tp_descr_set} rather than taking a different road,
     * which is why {@code del e.args} answers in the words of the field rather than as a missing attribute.
     */
    public PyObject delAttr(String name) {
        GenericAttributes.delete(this, name);
        return PyNone.INSTANCE;
    }

    /**
     * {@code self(*args, **kwNames)}: the calling convention of CPython's vectorcall, where the
     * keyword values are the {@code kwNames.length} entries at the end of {@code args}.
     *
     * <p>The refusal belongs to the value that cannot answer a call rather than to the call site, because
     * {@link PyNotImplemented} is a legal <em>answer</em>: {@code b'ab'.__eq__('ab')} hands one back, and a
     * caller that read it as "not callable" would report {@code 'method-wrapper' object is not callable}
     * about a method that was called. A type that overrides this is callable; everything else says so in
     * CPython's words here.
     *
     * @param kwNames interned keyword names, or null when every argument is positional
     */
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        throw Slots.typeError("'%s' object is not callable", getType().filedName());
    }

    /**
     * Whether the value has a call slot, which is the whole of what {@code callable()} asks.
     *
     * <p>CPython reads {@code tp_call}, a field its own type layout fills in only for a class that
     * implements the slot, so this is a question about the value's class rather than about a call it might
     * one day refuse. The default answer is no, and every class that overrides {@link #call} says yes
     * beside it; a {@link PyInstance} is the one value whose answer is not its class's, because a class
     * that declares no {@code __call__} has no slot to run.
     */
    public boolean isCallable() {
        return false;
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
