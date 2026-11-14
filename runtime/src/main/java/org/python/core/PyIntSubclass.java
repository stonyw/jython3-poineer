package org.python.core;

import java.math.BigInteger;

/**
 * A value of a class Python wrote under {@code int}: the number {@link PyInt} already knows how to be,
 * plus the attributes a class statement promises.
 *
 * <p>CPython embeds a {@code PyLongObject} in the larger object a subclass needs; this runtime spells
 * that as a subclass of {@link PyInt}, so every arithmetic and comparison slot the base already answers
 * keeps working on the same value. What the class adds is storage — {@code self.x = 1} is refused by a
 * plain {@code int} and kept here — and the right of its own methods to win: each slot below asks the
 * class first and only then the number, which is what an {@code IntEnum} relies on when it replaces
 * {@code __str__}.
 *
 * <p>The value is fixed when {@code __new__} answers. {@code __init__} is still called afterwards, as
 * every class call is, and it may write attributes but cannot change the number: the constructor that
 * could have is the one that already ran.
 */
final class PyIntSubclass extends PyInt {

    private PyDict dictionary = PyDict.empty();

    PyIntSubclass(BigInteger value, PyType kind) {
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
        if (Slots.declined(answer)) {
            answer = super.unaryOp(op);
        }
        // `long___pos__`, `long___neg__` and `long___abs__` all build the answer with `_PyLong_Copy`,
        // so a subclass gets a plain int back rather than itself — `+a` is not `a` for `class I(int)`.
        if (answer == this
                && (op == UnaryOp.POSITIVE || op == UnaryOp.NEGATIVE || op == UnaryOp.ABSOLUTE)) {
            return PyInt.of(getValue());
        }
        return answer;
    }

    /**
     * {@code self < other} and the rest: a method the class wrote, or the number's own comparison.
     *
     * <p>{@code !=} is the one that is not simply a name: a class that writes {@code __eq__} and no
     * {@code __ne__} gets the negation, as {@code object.__ne__} would give it, rather than the value's
     * comparison that knows nothing about the override.
     */
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

    /** {@code bool(self)}: a class-declared {@code __bool__} wins over the number being zero. */
    @Override
    public PyObject truth() {
        PyObject answer = Specials.invoke(this, "__bool__");
        return Slots.declined(answer) ? super.truth() : answer;
    }
}
