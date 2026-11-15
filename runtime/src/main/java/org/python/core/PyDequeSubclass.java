package org.python.core;

/**
 * A value of a class Python wrote under {@code collections.deque}: the element list {@link PyDeque}
 * already holds, plus the attributes a class statement promises.
 *
 * <p>CPython embeds a {@code PyDequeObject} in the larger object a subclass needs; this runtime spells
 * that as a subclass of {@link PyDeque}, so iteration, indexing, the searches and every method the base
 * answers keep working on the same list. What the class adds is storage and the right of its own
 * methods to win.
 *
 * <p>{@code maxlen} is the base's field: reading it in a subclass goes through the getset descriptor
 * the type files, writing is refused in the base's own words, and a re-run of the inherited
 * {@code __init__} resets the bound and clears the contents exactly as it does for a plain deque.
 */
final class PyDequeSubclass extends PyDeque {

    private PyDict dictionary = PyDict.empty();

    PyDequeSubclass(PyType kind, long maxlen) {
        super(maxlen, kind);
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

    /** {@code X(['a', 'b'])}: a class-defined {@code __repr__} wins first. */
    @Override
    public String repr() {
        return SubclassValue.repr(this, this::guarded);
    }

    /**
     * The base repr's own text with CPython's {@code Py_ReprEnter} around it, so an element whose
     * {@code __repr__} reaches this deque again sees {@code [...]} rather than recursing. The guard
     * belongs here and not around the class's {@code __repr__}, which is the slot CPython replaces
     * and which therefore never enters the guard.
     */
    private String guarded() {
        if (enteringRepr(this)) {
            return "[...]";
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

    /** {@code d += other}: the class's own method, else the base's in-place slot, which keeps this value. */
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

    /** {@code self < other} and the rest: a method the class wrote, or the contents' comparison. */
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

    // --- container slots -----------------------------------------------------------------

    @Override
    public PyObject truth() {
        PyObject answer = Specials.invoke(this, "__bool__");
        return Slots.declined(answer) ? super.truth() : answer;
    }

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
    public PyIterator iterator() {
        PyObject answer = Specials.invoke(this, "__iter__");
        if (Slots.declined(answer)) {
            return super.iterator();
        }
        return Slots.asIterator(answer);
    }
}
