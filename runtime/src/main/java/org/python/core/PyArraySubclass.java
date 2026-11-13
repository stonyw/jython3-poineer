package org.python.core;

/**
 * A value of a class Python wrote under {@code array.array}: the buffer {@link PyArray} already holds,
 * plus the attributes a class statement promises.
 *
 * <p>CPython embeds an {@code arrayobject} in the larger object a subclass needs, so every read, every
 * write and every method the base answers keeps working on the same run of octets. What the class adds
 * is a dictionary — {@code self.tag = "x"} is refused by a plain {@code array.array} and kept here —
 * and the right of its own methods to win.
 *
 * <p>{@code array_new} does all the work and {@code array.array.__init__} is {@code object.__init__},
 * which is why the arguments are consumed by {@code __new__} and the class's own {@code __init__}, if
 * it writes one, is still handed the same arguments by the call machinery.
 */
final class PyArraySubclass extends PyArray {

    private PyDict dictionary = PyDict.empty();

    PyArraySubclass(PyType kind, char typecode) {
        super(kind, typecode);
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
    public String repr() {
        return SubclassValue.repr(this, super::repr);
    }

    // --- the sequence protocol the buffer already answers --------------------------------

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
    public PyObject setItem(PyObject key, PyObject value) {
        PyObject answer = Specials.invoke(this, "__setitem__", key, value);
        return Slots.declined(answer) ? super.setItem(key, value) : answer;
    }

    @Override
    public PyObject delItem(PyObject key) {
        PyObject answer = Specials.invoke(this, "__delitem__", key);
        return Slots.declined(answer) ? super.delItem(key) : answer;
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

    @Override
    public PyObject truth() {
        PyObject answer = Specials.invoke(this, "__bool__");
        return Slots.declined(answer) ? super.truth() : answer;
    }
}
