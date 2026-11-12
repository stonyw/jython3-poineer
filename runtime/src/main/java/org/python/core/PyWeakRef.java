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
 */
final class PyWeakRef extends PyObject implements Weakref.Holder {

    static final Members METHODS = Members.of(PyType.WEAK_REFERENCE)
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
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.size();
        if (args.length != 0 || keywords != 0) {
            throw Slots.typeError(
                    "weakref expected 0 arguments, got %d", args.length + keywords);
        }
        PyObject live = referent.get();
        return live == null ? PyNone.INSTANCE : live;
    }

    private static PyObject callRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("weakref", kwNames);
        return ((PyWeakRef) self).call(args, null);
    }

    @Override
    public long hash() {
        PyObject live = referent.get();
        if (live == null) {
            throw Slots.typeError("weak object has gone away");
        }
        return live.hash();
    }

    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyWeakRef otherRef) {
            PyObject mine = referent.get();
            PyObject theirs = otherRef.referent.get();
            if (mine == null || theirs == null) {
                return this == other;
            }
            return mine == theirs;
        }
        return false;
    }

    @Override
    public PyObject getAttr(String attribute) {
        if (attribute.equals("__callback__")) {
            return callback == null ? PyNone.INSTANCE : callback;
        }
        return super.getAttr(attribute);
    }

    @Override
    public String repr() {
        String at = "0x" + Integer.toHexString(System.identityHashCode(this));
        PyObject live = referent.get();
        if (live == null) {
            return "<weakref at " + at + "; dead>";
        }
        return "<weakref at "
                + at
                + "; to '"
                + live.getType().name()
                + "' at 0x"
                + Integer.toHexString(System.identityHashCode(live))
                + ">";
    }
}
