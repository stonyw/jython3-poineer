package org.python.core;

/**
 * A value of a class Python wrote under {@code list}: the attributes its class gives it, and a list of
 * its own for the items {@code l[i]} reads.
 *
 * <p>The same split {@link PyDictInstance} makes, for the same reason: a plain {@code list} <em>is</em>
 * its storage, while an instance of a subclass also carries a {@code __dict__} and whatever a class body
 * filed on it, so CPython embeds the {@code PyListObject} in a larger object and this runtime spells that
 * as a {@link PyInstance} beside a {@link #items} list. {@code self.append} reaches the list's methods
 * through {@link #adaptedFor}, and the slots below answer with the items themselves once the class has
 * declined to.
 *
 * <p>{@code traceback.StackSummary} is the first thing the standard library subclasses {@code list} with,
 * which is why this exists at all: it appends frames to itself and is then iterated and formatted.
 */
final class PyListInstance extends PyInstance {

    /** The items {@code self[i]} reads and writes, kept apart from {@code self.__dict__}. */
    private final PyList items = PyList.empty();

    PyListInstance(PyType kind) {
        super(kind);
    }

    /** The list this value contributes where a {@code PyList} is expected. */
    PyList entries() {
        return items;
    }

    @Override
    PyObject adaptedFor(PyType declaring) {
        return declaring == PyType.LIST ? items : this;
    }

    /**
     * The names inherited from {@code list}'s own table, which {@link PyInstance}'s walk of the class
     * namespace cannot see — {@code self.append}, {@code self.sort} and the slot wrappers.
     */
    @Override
    PyObject inherited(String name) {
        PyObject method = Members.find(this, name);
        return method != Slots.DECLINED ? method : TypeDict.read(this, name);
    }

    // --- container protocol ---------------------------------------------------------------

    @Override
    public PyObject length() {
        if (Specials.lookUp(type, "__len__") == PyNone.INSTANCE) {
            return Slots.call(PyNone.INSTANCE, new PyObject[0], null);
        }
        PyObject answer = Specials.invoke(this, "__len__");
        return Slots.declined(answer) ? items.length() : answer;
    }

    @Override
    public PyObject getItem(PyObject key) {
        PyObject answer = Specials.invoke(this, "__getitem__", key);
        return Slots.declined(answer) ? items.getItem(key) : answer;
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        PyObject answer = Specials.invoke(this, "__setitem__", key, value);
        return Slots.declined(answer) ? items.setItem(key, value) : answer;
    }

    @Override
    public PyObject delItem(PyObject key) {
        PyObject answer = Specials.invoke(this, "__delitem__", key);
        return Slots.declined(answer) ? items.delItem(key) : answer;
    }

    @Override
    public PyObject contains(PyObject item) {
        PyObject answer = Specials.invoke(this, "__contains__", item);
        return Slots.declined(answer) ? items.contains(item) : answer;
    }

    @Override
    public PyObject iterator() {
        PyObject answer = Specials.invoke(this, "__iter__");
        return Slots.declined(answer) ? items.iterator() : answer;
    }

    // --- operators ------------------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.dunder(), other);
        if (!Slots.declined(answer)) {
            return answer;
        }
        PyList body = PyList.bodyOf(other);
        return items.binaryOp(op, body == null ? other : body);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.reflectedDunder(), other);
        if (!Slots.declined(answer)) {
            return answer;
        }
        PyList body = PyList.bodyOf(other);
        return items.reflectedOp(op, body == null ? other : body);
    }

    /**
     * {@code l += other}: the class's own method, or the inherited extend — which leaves this value the
     * receiver, where the list the extend was made into is only its storage.
     */
    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.inPlaceDunder(), other);
        if (!Slots.declined(answer)) {
            return answer;
        }
        return items.inplaceOp(op, other) == items ? this : PyNotImplemented.INSTANCE;
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
        PyList body = PyList.bodyOf(other);
        return items.richCompare(cmp, body == null ? other : body);
    }

    @Override
    public boolean eq(PyObject other) {
        if (Specials.lookUp(type, "__eq__") != null) {
            return super.eq(other);
        }
        PyList body = PyList.bodyOf(other);
        return body != null && items.eq(body);
    }

    /** {@code hash(self)}: a subclass that declares no {@code __hash__} inherits {@code list}'s {@code None}. */
    @Override
    public long hash() {
        if (Specials.lookUp(type, "__hash__") == null) {
            throw Slots.typeError("unhashable type: '%s'", type.name());
        }
        return super.hash();
    }

    /** {@code repr(self)} without a Python-level {@code __repr__} is the list's own text. */
    @Override
    String defaultDisplay() {
        return items.repr();
    }
}
