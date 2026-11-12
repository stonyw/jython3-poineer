package org.python.core;

/**
 * A value of a class Python wrote under {@code str}: the text {@link PyStr} already knows how to be,
 * plus the attributes a class statement promises.
 *
 * <p>The same shape as {@link PyIntSubclass}, for the other immutable built-in: the value is stored
 * once, {@code __new__} fixes it, {@code __init__} runs afterwards and may keep attributes but cannot
 * change the text, and every inherited slot asks the class before it asks the value, so a class that
 * writes {@code __str__} or {@code __eq__} is the one that answers.
 */
final class PyStrSubclass extends PyStr {

    private PyDict dictionary = PyDict.empty();

    PyStrSubclass(String value, PyType kind) {
        super(value, kind);
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
        return SubclassValue.filedByItsClass(type, name);
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

    // --- the sequence protocol the text already answers ----------------------------------

    @Override
    public PyObject length() {
        PyObject answer = Specials.invoke(this, "__len__");
        return Slots.declined(answer) ? super.length() : answer;
    }

    @Override
    public PyObject getItem(PyObject key) {
        PyObject answer = Specials.invoke(this, "__getitem__", key);
        return Slots.declined(answer) ? super.getItem(key) : answer;
    }

    @Override
    public PyObject contains(PyObject item) {
        PyObject answer = Specials.invoke(this, "__contains__", item);
        return Slots.declined(answer) ? super.contains(item) : answer;
    }

    @Override
    public PyObject iterator() {
        PyObject answer = Specials.invoke(this, "__iter__");
        return Slots.declined(answer) ? super.iterator() : answer;
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
}
