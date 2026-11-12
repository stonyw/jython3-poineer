package org.python.core;

import java.lang.ref.WeakReference;

/**
 * {@code _weakref.ProxyType} and {@code _weakref.CallableProxyType}: a stand-in that forwards to an object
 * without keeping it alive.
 *
 * <p>CPython gives the two proxy flavours separate types so that {@code tp_call} can be filled in for one
 * and left out for the other — {@code callable(weakref.proxy(instance))} is false while a proxy over a
 * function is callable — and this class keeps that split by holding the flavour in a field. The reference
 * itself is Java's {@link WeakReference}; the forwarding is {@link Slots}, which is the same protocol the
 * interpreter would run on the referent directly and so produces the referent's own errors for free.
 *
 * <p>A dead proxy is not a silent absence: every operation but {@code repr} raises
 * {@code ReferenceError: weakly-referenced object no longer exists}, which is what makes a proxy safe to
 * keep around. It is also unhashable, like CPython's proxy, whose {@code tp_hash} is
 * {@code PyObject_HashNotImplemented}.
 */
final class PyWeakProxy extends PyObject implements Weakref.Holder {

    private final WeakReference<PyObject> referent;

    private final boolean callable;

    PyWeakProxy(PyObject referent) {
        super(referent.isCallable() ? PyType.WEAK_CALLABLE_PROXY : PyType.WEAK_PROXY);
        this.referent = new WeakReference<>(referent);
        this.callable = referent.isCallable();
    }

    /** The live referent, or null once it has been collected. */
    @Override
    public PyObject target() {
        return referent.get();
    }

    /** The referent, or the {@code ReferenceError} every operation on a dead proxy raises. */
    private PyObject live() {
        PyObject live = referent.get();
        if (live == null) {
            throw PyRaise.raise(
                    ExceptionTypes.REFERENCE_ERROR, "weakly-referenced object no longer exists");
        }
        return live;
    }

    @Override
    public boolean isCallable() {
        return callable;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        if (!callable) {
            // The non-callable flavour has no call slot at all, so it is the proxy that is named.
            throw Slots.typeError("'%s' object is not callable", getType().filedName());
        }
        return Slots.call(live(), args, kwNames);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return Slots.getAttr(live(), attribute);
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        Slots.setAttr(live(), attribute, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        Slots.delAttr(live(), attribute);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject length() {
        return Slots.length(live());
    }

    @Override
    public PyObject truth() {
        return PyBool.of(Slots.truth(live()));
    }

    @Override
    public PyObject getItem(PyObject key) {
        return Slots.getItem(live(), key);
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        Slots.setItem(live(), key, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delItem(PyObject key) {
        Slots.delItem(live(), key);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject contains(PyObject item) {
        return PyBool.of(Slots.contains(live(), item));
    }

    @Override
    public PyObject iterator() {
        return Slots.iterator(live());
    }

    /**
     * {@code p == q}: the two referents compared, as {@code proxy_richcompare} does.
     *
     * <p>A dead proxy refuses the comparison rather than answering identity, which is the one place a dead
     * proxy is not silently unequal.
     */
    @Override
    public boolean eq(PyObject other) {
        if (other == this) {
            if (referent.get() == null) {
                live();
            }
            return true;
        }
        PyObject mine = live();
        PyObject theirs = other instanceof PyWeakProxy proxy ? proxy.live() : other;
        return mine.eq(theirs);
    }

    /** Unhashable, in the module-qualified words CPython's {@code tp_hash} uses. */
    @Override
    public long hash() {
        throw Slots.typeError("unhashable type: '%s'", getType().filedName());
    }

    /** {@code str(p)}: the referent's own {@code str}, not the proxy's repr. */
    @Override
    public String str() {
        return live().str();
    }

    @Override
    public String repr() {
        String at = "0x" + Integer.toHexString(System.identityHashCode(this));
        PyObject live = referent.get();
        if (live == null) {
            return "<weakproxy at " + at + "; dead>";
        }
        return "<weakproxy at "
                + at
                + "; to '"
                + live.getType().name()
                + "' at 0x"
                + Integer.toHexString(System.identityHashCode(live))
                + ">";
    }
}
