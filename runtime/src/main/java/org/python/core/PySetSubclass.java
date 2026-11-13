package org.python.core;

import java.util.Set;

/**
 * A value of a class Python wrote under {@code set}: the hash table {@link PySet} already holds, plus
 * the attributes a class statement promises.
 *
 * <p>CPython embeds a {@code PySetObject} in the larger object a subclass needs; this runtime spells
 * that as a subclass of {@link PySet}, so {@code in}, {@code len}, iteration and every method the base
 * answers keep working on the same table. What the class adds is storage and the right of its own
 * methods to win.
 *
 * <p>The operators that make a new value answer a plain {@code set}, even when the left side is this
 * subclass — {@code S([1]) | {2}} is a {@code set} — because {@link PySet#emptyLike()} builds the base
 * type. The in-place four are the exception: they change this value and hand it back, so {@code s |= t}
 * keeps the subclass, exactly as CPython's {@code set_ior} does.
 */
final class PySetSubclass extends PySet {

    private PyDict dictionary = PyDict.empty();

    PySetSubclass(Set<PyObject> items, PyType kind) {
        super(items, kind);
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

    /** {@code X({1, 2})}, {@code X()} when empty: a class-defined {@code __repr__} wins first. */
    @Override
    public String repr() {
        return SubclassValue.repr(this, this::named);
    }

    /** {@code str(self)} follows {@code repr} unless the class wrote a {@code __str__}. */
    @Override
    public String str() {
        return SubclassValue.str(this, this::named);
    }

    private String named() {
        return items.isEmpty() ? getType().name() + "()" : getType().name() + "({" + contents() + "})";
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

    /** {@code s |= other}: the class's own method, else the base's in-place slot, which keeps this value. */
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

    /** {@code self < other} and the rest: a method the class wrote, or the contents' own comparison. */
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

    /** {@code bool(self)}: a class-declared {@code __bool__} wins over the table being empty. */
    @Override
    public PyObject truth() {
        PyObject answer = Specials.invoke(this, "__bool__");
        return Slots.declined(answer) ? super.truth() : answer;
    }
}
