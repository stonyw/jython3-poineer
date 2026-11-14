package org.python.core;

/**
 * A value of a class Python wrote under {@code tuple}: the items {@link PyTuple} already holds, plus the
 * attributes a class statement promises.
 *
 * <p>The third immutable built-in, after {@code int} and {@code str}, and the one the standard library
 * derives from most: {@code codecs.CodecInfo} keeps the four codec functions this way, and {@code
 * datetime}'s {@code IsoCalendarDate} keeps a date's three numbers — both of them written as a plain
 * {@code class X(tuple)} with a {@code __new__} of their own. The items are fixed by that {@code __new__}
 * ({@code tuple.__new__} builds them), {@code __init__} runs afterwards and may keep attributes but
 * cannot change them, and every inherited slot asks the class before it asks the value, so a class that
 * writes {@code __repr__} or {@code __eq__} is the one that answers.
 *
 * <p>{@code collections.namedtuple} is the same shape with more machinery: its generated class derives
 * from {@code tuple}, declares {@code __slots__ = ()} and files one {@code _tuplegetter} descriptor per
 * field, so a read of {@code p.x} is a descriptor on the class rather than a name in the instance
 * dictionary — and {@code _fields}, {@code _make} and {@code _replace} are ordinary class attributes.
 */
final class PyTupleSubclass extends PyTuple {

    private PyDict dictionary = PyDict.empty();

    PyTupleSubclass(PyObject[] items, PyType kind) {
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

    // --- the sequence protocol the items already answer ----------------------------------

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

    /**
     * {@code tuple.__iter__(value)} called by name: the items, never the class's own {@code __iter__}.
     *
     * <p>The two reads are different in the reference. {@code iter(value)} goes through the type's
     * slot, which for a class that writes {@code __iter__} runs that method; calling
     * {@code tuple.__iter__} runs the inherited wrapper descriptor itself, which allocates a tuple
     * iterator without looking the name up again. Answering both from {@link #iterator()} made
     * {@code super().__iter__()} inside such a class call itself until the stack ran out — which is
     * exactly what {@code platform.uname_result.__iter__} does.
     */
    PyObject baseIterator() {
        return super.iterator();
    }

    /**
     * {@code tuple.__getitem__(value, key)} called by name: the items, never the class's own
     * {@code __getitem__}.
     *
     * <p>The same split as {@link #baseIterator}, for the same reason: the inherited wrapper runs the
     * {@code sq_item} body over the tuple layout without looking the name up again, so a class whose
     * {@code __getitem__} calls {@code tuple.__getitem__(self, x)} — which is what {@code test_slices}
     * writes — reads the items instead of calling itself until the stack runs out.
     */
    PyObject baseItem(PyObject key) {
        return super.getItem(key);
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
