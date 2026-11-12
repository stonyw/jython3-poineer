package org.python.core;

import java.lang.ref.WeakReference;
import java.math.BigInteger;

/**
 * A value of a class Python wrote over {@code weakref.ref}: a reference that also has a class's
 * dictionary and slots.
 *
 * <p>CPython's reference type is a static type, so a class written over it is an ordinary heap type
 * whose {@code tp_weaklistoffset} and {@code tp_dictoffset} come from the subclass; the referent
 * lives in the base's fields. Here the base value is a plain Java object with no storage for a
 * dictionary, so a subclass is a {@link PyInstance} that carries the same referent and callback
 * beside the dictionary. Both answer the same five operations because those are written against
 * {@link Weakref.Holder}, and the Python-level declarations win whenever the class made any:
 * {@code WeakMethod} writes {@code __new__}, {@code __call__}, {@code __eq__} and {@code __hash__},
 * and every one of them has to be reachable exactly where CPython puts it.
 *
 * <p>The operation slots are the only place the inherited weak-reference behaviour has to be
 * restated. {@link PyInstance}'s versions consult {@link Specials}, which sees a Python namespace and
 * not the built-in base's table, so a subclass that declares no {@code __eq__} would compare by
 * identity and one that declares no {@code __hash__} would hash by identity. The overrides below
 * fall back to the reference's own answers in that case and defer to {@link PyInstance} when the
 * class wrote something.
 */
final class PyWeakRefSubclass extends PyInstance implements Weakref.Holder {

    private final WeakReference<PyObject> referent;

    private final PyObject callback;

    PyWeakRefSubclass(PyType kind, PyObject referent, PyObject callback) {
        super(kind);
        this.referent = new WeakReference<>(referent);
        this.callback = callback;
    }

    @Override
    public PyObject target() {
        return referent.get();
    }

    @Override
    public PyObject callback() {
        return callback;
    }

    /**
     * The names inherited from the reference type's own table, which {@link PyInstance}'s walk of
     * the class namespace cannot see: {@code __call__}, {@code __callback__} and the slot wrappers
     * {@code weakref.ref} files under double-underscore names.
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

    /**
     * {@code ==}: the class's {@code __eq__} when it declares one, and the reference's answer
     * otherwise — the two references point at the same live object, or both are dead.
     */
    @Override
    public boolean eq(PyObject other) {
        return Specials.lookUp(type, "__eq__") == null
                ? Weakref.equal(this, other)
                : super.eq(other);
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (cmp == CompareOp.EQ && Specials.lookUp(type, "__eq__") == null) {
            return PyBool.of(Weakref.equal(this, other));
        }
        if (cmp == CompareOp.NE
                && Specials.lookUp(type, "__eq__") == null
                && Specials.lookUp(type, "__ne__") == null) {
            return PyBool.of(!Weakref.equal(this, other));
        }
        return super.richCompare(cmp, other);
    }

    /**
     * {@code hash(self)}: the referent's hash unless the class writes its own method.
     *
     * <p>A class that declares {@code __hash__ = ref.__hash__} — which is what {@code WeakMethod}
     * does — files the reference type's unbound method under the name, so the answer comes from
     * calling it with this value as the receiver rather than from a Python function. A class that
     * declares {@code __eq__} and no {@code __hash__} is given {@code None} and is unhashable, as
     * {@link PyInstance#hash} already says.
     */
    @Override
    public long hash() {
        PyObject declared = Specials.lookUp(type, "__hash__");
        if (declared == null) {
            return Weakref.hashOf(this);
        }
        if (declared == PyNone.INSTANCE) {
            throw Slots.typeError("unhashable type: '%s'", type.name());
        }
        PyObject answer =
                Slots.call(Descriptors.get(declared, this, type), new PyObject[0], null);
        BigInteger value = Numbers.asInteger(answer);
        if (value == null) {
            throw Slots.typeError("__hash__ method should return an integer");
        }
        return value.longValue();
    }

    /** {@code repr(self)} without a Python-level {@code __repr__} is the reference's own text. */
    @Override
    String defaultDisplay() {
        return Weakref.describe(this);
    }
}
