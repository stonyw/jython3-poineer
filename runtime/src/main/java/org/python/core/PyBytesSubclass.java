package org.python.core;

/**
 * A value of a class Python wrote under {@code bytes}: the octets {@link PyBytes} already holds, plus
 * the attributes a class statement promises.
 *
 * <p>CPython embeds a {@code PyBytesObject} in the larger object a subclass needs; this runtime spells
 * that as a subclass of {@link PyBytes}, so every read and every method the base answers keeps working
 * on the same run of octets. What the class adds is storage — {@code self.tag = "x"} is refused by a
 * plain {@code bytes} and kept here — and the right of its own methods to win.
 *
 * <p>The value is fixed when {@code __new__} answers. {@code __init__} is still called afterwards, as
 * every class call is, and it may write attributes but cannot change the octets: the constructor that
 * could have is the one that already ran. Slicing, joining and repeating stay the base's own answers,
 * so they come back as plain {@code bytes}, exactly as they do in CPython.
 */
final class PyBytesSubclass extends PyBytes {

    private PyDict dictionary = PyDict.empty();

    PyBytesSubclass(byte[] value, PyType kind) {
        super(kind, value);
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

    @Override
    public String str() {
        return SubclassValue.str(this, super::str);
    }

    @Override
    public String repr() {
        return SubclassValue.repr(this, super::repr);
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
