package org.python.core;

/**
 * A value of a class Python wrote under {@code bytearray}: the octets {@link PyByteArray} already holds,
 * plus the attributes a class statement promises.
 *
 * <p>CPython embeds a {@code PyByteArrayObject} in the larger object a subclass needs; this runtime
 * spells that as a subclass of {@link PyByteArray}, so every read, every write and every method the base
 * answers keeps working on the same run of octets. What the class adds is storage — {@code self.tag =
 * "x"} is refused by a plain {@code bytearray} and kept here — and the right of its own methods to win.
 *
 * <p>Unlike {@link PyBytesSubclass}, the octets are <em>not</em> fixed when {@code __new__} answers:
 * {@code bytearray_new} allocates an empty run and {@code bytearray_init} fills it, so a second
 * {@code __init__} replaces the value the first one wrote. Slicing, joining and repeating stay the
 * base's own answers and come back as plain {@code bytearray}s, exactly as they do in CPython, while
 * {@code +=} and {@code *=} change this value in place and keep its class.
 */
final class PyByteArraySubclass extends PyByteArray {

    private PyDict dictionary = PyDict.empty();

    PyByteArraySubclass(PyType kind) {
        super(kind, new byte[0], 0);
    }

    // --- attributes ----------------------------------------------------------------------

    @Override
    public PyObject getAttr(String name) {
        return SubclassValue.getAttr(this, dictionary, name);
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        return SubclassValue.setAttr(this, name, value);
    }

    @Override
    public PyObject delAttr(String name) {
        return SubclassValue.delAttr(this, name);
    }

    @Override
    PyDict attributeStore() {
        return dictionary;
    }

    @Override
    void replaceAttributeStore(PyDict store) {
        dictionary = store;
    }

    @Override
    boolean filedByItsClass(String name) {
        return SubclassValue.filedByItsClass(getType(), name);
    }

    // --- calls ---------------------------------------------------------------------------

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return SubclassValue.call(this, args, kwNames);
    }

    @Override
    public boolean isCallable() {
        return SubclassValue.isCallable(this);
    }

    // --- display -------------------------------------------------------------------------

    /**
     * {@code str(self)}: the class's {@code __str__}, else the named form of the octets.
     *
     * <p>Not {@link SubclassValue#str}: that asks {@code __repr__} next, because a class under
     * {@code int} may write only {@code __repr__} and expect {@code str} to show it. A bytearray is the
     * other way round — {@code bytearray_str} is its own slot, so a class that writes {@code __repr__}
     * only still has {@code str(x)} show {@code X(b'...')} — and asking the base's slot directly is what
     * keeps the two apart.
     */
    @Override
    public String str() {
        PyObject answer = Specials.invoke(this, "__str__");
        if (Slots.declined(answer)) {
            return named();
        }
        if (answer instanceof PyStr text) {
            return text.getValue();
        }
        throw Slots.typeError("__str__ returned non-string (type %s)", answer.getType().name());
    }

    @Override
    public String repr() {
        return SubclassValue.repr(this, this::named);
    }

    /** {@code X(b'...')}: the base's text with this class's name where {@code bytearray} stood. */
    private String named() {
        return getType().name() + "(" + PyRepr.quoteByteArray(raw()) + ")";
    }

    // --- equality ------------------------------------------------------------------------

    @Override
    public boolean eq(PyObject other) {
        return SubclassValue.eq(this, other, () -> super.eq(other));
    }

    @Override
    public long hash() {
        return SubclassValue.hash(this, super::hash);
    }

    // --- operators -----------------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.dunder(), other);
        return Slots.declined(answer) ? super.binaryOp(op, other) : answer;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.reflectedDunder(), other);
        return Slots.declined(answer) ? super.reflectedOp(op, other) : answer;
    }

    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.inPlaceDunder(), other);
        return Slots.declined(answer) ? super.inplaceOp(op, other) : answer;
    }

    @Override
    public PyObject unaryOp(UnaryOp op) {
        PyObject answer = Specials.invoke(this, op.dunder());
        return Slots.declined(answer) ? super.unaryOp(op) : answer;
    }

    /** {@code self < other} and the rest: a method the class wrote, or the octets' own comparison. */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (cmp == CompareOp.NE) {
            PyObject custom = Specials.invoke(this, "__ne__", other);
            if (!Slots.declined(custom)) {
                return custom;
            }
            PyObject equal = Specials.invoke(this, "__eq__", other);
            if (!Slots.declined(equal)) {
                return PyBool.of(!Slots.truth(equal));
            }
        } else {
            PyObject answer = Specials.invoke(this, cmp.dunder(), other);
            if (!Slots.declined(answer)) {
                return answer;
            }
        }
        return super.richCompare(cmp, other);
    }

    /** {@code bool(self)}: a class-declared {@code __bool__} wins over the octets being empty. */
    @Override
    public PyObject truth() {
        PyObject answer = Specials.invoke(this, "__bool__");
        return Slots.declined(answer) ? super.truth() : answer;
    }
}
