package org.python.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A value of a class Python wrote under {@code frozenset}: the table {@link PyFrozenSet} already holds,
 * plus the attributes a class statement promises.
 *
 * <p>Everything that is true of a {@link PySetSubclass} is true here as well — the operators answer a
 * plain {@code frozenset}, iteration and {@code in} read the same table, a class-defined method wins —
 * with two differences that come from the value being immutable. The hash is the contents' hash, which
 * is what CPython's {@code frozenset_hash} computes for any type; and {@code copy()} answers a plain
 * {@code frozenset} rather than the receiver, because CPython's {@code frozenset_copy} only hands back
 * the receiver when its type is exactly {@code frozenset}.
 */
final class PyFrozenSetSubclass extends PyFrozenSet {

    private PyDict dictionary = PyDict.empty();

    PyFrozenSetSubclass(Set<PyObject> items, PyType kind) {
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

    @Override
    public String repr() {
        return SubclassValue.repr(this, this::guarded);
    }

    /**
     * The base repr's own text under CPython's {@code Py_ReprEnter}, as in {@link PySetSubclass}: an
     * element that reaches this value again sees {@code X(...)} instead of recursing.
     */
    private String guarded() {
        if (enteringRepr(this)) {
            return getType().name() + "(...)";
        }
        try {
            return named();
        } finally {
            leavingRepr(this);
        }
    }

    @Override
    public String str() {
        return SubclassValue.str(this, this::named);
    }

    private String named() {
        return items.isEmpty()
                ? getType().name() + "()"
                : getType().name() + "({" + contents() + "})";
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

    /** {@code copy()} of an immutable value of a subclass is still a plain {@code frozenset}. */
    @Override
    PySet copy() {
        return new PyFrozenSet(new LinkedHashSet<>(items));
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
