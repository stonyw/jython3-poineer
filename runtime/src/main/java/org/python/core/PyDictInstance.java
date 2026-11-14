package org.python.core;

/**
 * A value of a class Python wrote under {@code dict}: the attributes its class gives it, and a
 * dictionary of its own for the entries {@code d[key]} reads.
 *
 * <p>A plain {@code dict} <em>is</em> its storage. An instance of a subclass is not, because it also
 * holds a {@code __dict__} and whatever a class body files on it; CPython embeds the {@code
 * PyDictObject} in a larger object and this runtime spells that as a {@link PyInstance} beside a
 * {@link #entries} dictionary. The two stores never mix: {@code self.x = 1} writes the instance
 * dictionary {@link PyInstance} owns, and {@code self[key] = 1} writes the entries here. That is why
 * {@code vars(d)} can be empty while {@code len(d)} is not.
 *
 * <p>{@link #adaptedFor} is what keeps the inherited methods working. {@code d.get}, {@code
 * d.update} and the rest are written against a {@code PyDict}, so when {@link Members} finds one of
 * them on {@code dict} it hands over {@link #entries} while the bound method still reports {@code d}
 * as its {@code __self__}. The slots below take the same two steps in the other order: a method the
 * class declared in Python wins, and only when it declared none does {@link #entries} answer. That
 * is CPython's {@code slot_tp_*} wrapper, which is installed on the class only when the subclass
 * overrode the slot, and the inherited {@code dict} slot otherwise.
 */
final class PyDictInstance extends PyInstance {

    /** The mapping {@code self[key]} reads and writes, kept apart from {@code self.__dict__}. */
    private final PyDict entries = PyDict.empty();

    PyDictInstance(PyType kind) {
        super(kind);
    }

    /** The dictionary this value contributes where a {@code PyDict} is expected. */
    PyDict entries() {
        return entries;
    }

    @Override
    PyObject adaptedFor(PyType declaring) {
        return declaring == PyType.DICT ? entries : this;
    }

    /**
     * The names inherited from {@code dict}'s own table, which {@link PyInstance}'s walk of the class
     * namespace cannot see: {@code d.get}, {@code d.update}, {@code d.keys} and the slot wrappers
     * {@code dict} declares under double-underscore names.
     *
     * <p>{@link Members#find} answers the first kind; the rest are read out of the type dictionaries
     * the way {@link Slots#getAttr} reads a built-in value's names. A name the class filed itself is
     * answered before this hook is reached, so the Python-level declaration still wins.
     */
    @Override
    PyObject inherited(String name) {
        PyObject method = Members.find(this, name);
        return method != Slots.DECLINED ? method : TypeDict.read(this, name);
    }

    // --- container protocol ---------------------------------------------------------------

    /**
     * {@code len(self)}: {@code __len__} when the class declares one, otherwise the entries.
     *
     * <p>The {@code None} case is checked here for the reason {@link PyObject#length} checks it: a
     * class that files {@code None} under the name is refusing the question, and the call of
     * {@code None} is what says so.
     */
    @Override
    public PyObject length() {
        if (Specials.lookUp(type, "__len__") == PyNone.INSTANCE) {
            return Slots.call(PyNone.INSTANCE, new PyObject[0], null);
        }
        PyObject answer = Specials.invoke(this, "__len__");
        return Slots.declined(answer) ? entries.length() : answer;
    }

    /**
     * {@code self[key]}: {@code __getitem__} when the class declares one, then the entries, then
     * {@code __missing__}.
     *
     * <p>{@code dict_subscript} asks {@code __missing__} only when the key is absent and only for a
     * subclass — an exact dict has no class to look the name up in — and its answer replaces the
     * KeyError rather than following it. {@code get} does not take this road: a missing key there is
     * the default, which is why the two spellings disagree in CPython.
     */
    @Override
    public PyObject getItem(PyObject key) {
        // `dict.__getitem__` inherited unchanged is not a declaration of this class: what it does is
        // the entries lookup below, and its KeyError would leave `__missing__` unasked — the one
        // protocol a `defaultdict` has. Only a class that files something else under the name gets
        // the first say, and then its answer (or its own KeyError) is the whole answer.
        if (Specials.lookUp(type, "__getitem__") != Specials.lookUp(PyType.DICT, "__getitem__")) {
            PyObject answer = Specials.invoke(this, "__getitem__", key);
            if (!Slots.declined(answer)) {
                return answer;
            }
        }
        try {
            return entries.getItem(key);
        } catch (PyRaise missing) {
            if (!missing.value().exceptionType().matches(ExceptionTypes.KEY_ERROR)
                    || Specials.lookUp(type, "__missing__") == null) {
                throw missing;
            }
            PyObject fallback = Specials.invoke(this, "__missing__", key);
            if (Slots.declined(fallback)) {
                throw missing;
            }
            return fallback;
        }
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        PyObject answer = Specials.invoke(this, "__setitem__", key, value);
        return Slots.declined(answer) ? entries.setItem(key, value) : answer;
    }

    @Override
    public PyObject delItem(PyObject key) {
        PyObject answer = Specials.invoke(this, "__delitem__", key);
        return Slots.declined(answer) ? entries.delItem(key) : answer;
    }

    @Override
    public PyObject contains(PyObject item) {
        PyObject answer = Specials.invoke(this, "__contains__", item);
        return Slots.declined(answer) ? entries.contains(item) : answer;
    }

    @Override
    public PyObject iterator() {
        PyObject answer = Specials.invoke(this, "__iter__");
        return Slots.declined(answer) ? entries.iterator() : answer;
    }

    // --- operators ------------------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.dunder(), other);
        return Slots.declined(answer) ? entries.binaryOp(op, other) : answer;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.reflectedDunder(), other);
        return Slots.declined(answer) ? entries.reflectedOp(op, other) : answer;
    }

    /**
     * {@code d |= e}: the class's own method, or {@code dict}'s merge, which leaves this value the
     * receiver — {@code dict_ior} returns the subclass instance rather than the plain dictionary the
     * merge was made into.
     */
    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        PyObject answer = Specials.invoke(this, op.inPlaceDunder(), other);
        if (!Slots.declined(answer)) {
            return answer;
        }
        if (op != BinaryOp.OR) {
            return PyNotImplemented.INSTANCE;
        }
        entries.merge(other);
        return this;
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
        return entries.richCompare(cmp, other);
    }

    @Override
    public boolean eq(PyObject other) {
        return Specials.lookUp(type, "__eq__") == null ? entries.eq(other) : super.eq(other);
    }

    /**
     * {@code hash(self)}: a subclass that declares no {@code __hash__} inherits {@code dict}'s
     * {@code None} and is unhashable, where {@link PyInstance} would fall back to identity.
     */
    @Override
    public long hash() {
        if (Specials.lookUp(type, "__hash__") == null) {
            throw Slots.typeError("unhashable type: '%s'", type.name());
        }
        return super.hash();
    }

    /** {@code repr(self)} without a Python-level {@code __repr__} is the dictionary's own text. */
    @Override
    String defaultDisplay() {
        return entries.repr();
    }
}
