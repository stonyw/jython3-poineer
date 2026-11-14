package org.python.core;

import java.lang.ref.WeakReference;

/**
 * {@code _weakref.ReferenceType}: a reference to an object that does not keep it alive.
 *
 * <p>Java's own {@link WeakReference} is the whole mechanism. There is no clearing callback here —
 * CPython runs a program's callback at the moment the referent dies, which needs a death hook this
 * runtime does not have — so a reference whose referent has been collected simply answers
 * {@code None}, and the callback, if any, is remembered and never called. That is the one deviation
 * the corpus in this slice does not exercise.
 *
 * <p>A class Python writes over this one is a {@link PyWeakRefSubclass}, which carries the same
 * referent and callback in a value that also has a class's dictionary; the five bodies below are
 * written against {@link Weakref.Holder} rather than this class so that both kinds share them.
 */
final class PyWeakRef extends PyObject implements Weakref.Holder {

    static final Members METHODS = Members.of(PyType.WEAK_REFERENCE)
            .add("__repr__", (self, args, kwNames) -> PyStr.of(Weakref.describe((Weakref.Holder) self)))
            .add("__hash__", (self, args, kwNames) -> PyInt.of(Weakref.hashOf((Weakref.Holder) self)))
            .add("__eq__", PyWeakRef::eqRow)
            .add("__ne__", PyWeakRef::neRow)
            .add("__call__", PyWeakRef::callRow);

    private final WeakReference<PyObject> referent;

    private final PyObject callback;

    PyWeakRef(PyObject referent, PyObject callback) {
        super(PyType.WEAK_REFERENCE);
        this.referent = new WeakReference<>(referent);
        this.callback = callback;
    }

    /** The live referent, or null once it has been collected. */
    @Override
    public PyObject target() {
        return referent.get();
    }

    @Override
    public PyObject callback() {
        return callback;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return Weakref.call(this, args, kwNames);
    }

    private static PyObject callRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("weakref", kwNames);
        return Weakref.call((Weakref.Holder) self, args, null);
    }

    private static PyObject eqRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.exactly("__eq__", args, kwNames, 1);
        return PyBool.of(Weakref.equal((Weakref.Holder) self, args[0]));
    }

    private static PyObject neRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.exactly("__ne__", args, kwNames, 1);
        return PyBool.of(!Weakref.equal((Weakref.Holder) self, args[0]));
    }

    @Override
    public long hash() {
        return Weakref.hashOf(this);
    }

    /** Whether {@link #frozenHash} has been computed, and the value it froze. */
    private boolean hashed;

    private int frozenHash;

    /**
     * Java's hash: the answer {@link #hash()} gave the first time it was asked, kept after the
     * referent dies.
     *
     * <p>A container that holds references — {@code weakref.WeakSet}'s {@code data} — hashes each
     * one when it is put in, and hashes them again when the container is copied. CPython never
     * copies a reference whose subject is already gone, because the reference's callback removes it
     * at that moment; this runtime has no such callback (see the class comment on {@link PyWeakRef}),
     * so a dead reference stays in the container and the copy would raise. Freezing the first
     * answer keeps the copy working while {@code hash(ref)} itself still refuses once gone, which is
     * the answer a program sees.
     */
    @Override
    public int hashCode() {
        if (!hashed) {
            long h = Weakref.hashOf(this);
            frozenHash = (int) (h ^ (h >>> 32));
            hashed = true;
        }
        return frozenHash;
    }

    @Override
    public boolean eq(PyObject other) {
        return Weakref.equal(this, other);
    }

    @Override
    public PyObject getAttr(String attribute) {
        if (attribute.equals("__callback__")) {
            return Weakref.callbackOf(this);
        }
        return super.getAttr(attribute);
    }

    @Override
    public String repr() {
        return Weakref.describe(this);
    }
}
