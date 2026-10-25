package org.python.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Python's {@code frozenset}: the immutable, hashable half of {@link PySet}.
 *
 * Only the Java class differs, and three things follow from it. The method table has no mutating
 * method, so {@code fs.add} is an {@code AttributeError} rather than a refusal. The in-place
 * operators decline and Python falls back to the ordinary ones, so {@code fs |= other} names a new
 * frozenset. And a value can be filed in a dict, which needs {@link #hash()} to be independent of the
 * order its elements happen to be stored in — CPython's own mixing is an XOR of shuffled element
 * hashes for exactly that reason, and is reproduced here.
 */
public final class PyFrozenSet extends PySet {

    /** The one value this runtime keeps for an empty frozen set, which no Python program ever reads. */
    public static final PyFrozenSet EMPTY = new PyFrozenSet(new LinkedHashSet<>());

    PyFrozenSet(Set<PyObject> items) {
        super(items, PyType.FROZENSET);
    }

    public static PyFrozenSet of(PyObject... items) {
        if (items.length == 0) {
            return EMPTY;
        }
        PyFrozenSet set = new PyFrozenSet(new LinkedHashSet<>());
        for (PyObject item : items) {
            set.add(item);
        }
        return set;
    }

    /**
     * {@code frozenset(iterable)} at Java level, which is what makes the constructor idempotent.
     *
     * <p>A frozenset handed to its own type comes back as the same value rather than a copy of it, because
     * there is nothing a copy could hold that the original does not — {@code frozenset(f) is f} is true. An
     * empty call is the exception to the rule that no fresh value is made: {@code frozenset() is
     * frozenset()} is false, because the answer is a new value of zero size rather than a shared one.
     */
    static PyFrozenSet collected(PyObject iterable) {
        if (iterable != null && iterable.getType() == PyType.FROZENSET) {
            return (PyFrozenSet) iterable;
        }
        PyFrozenSet set = new PyFrozenSet(new LinkedHashSet<>());
        return iterable == null ? set : (PyFrozenSet) set.update(iterable);
    }

    @Override
    protected PySet emptyLike() {
        return new PyFrozenSet(new LinkedHashSet<>());
    }

    /** Nothing changes in place, so {@code __ior__} and its three siblings are not here. */
    @Override
    protected boolean mutable() {
        return false;
    }

    /** {@code frozenset.copy()}: the receiver, since a copy of an immutable value is the value. */
    @Override
    PySet copy() {
        return this;
    }

    /** {@code frozenset({1, 2})}, and {@code frozenset()} with nothing in it. */
    @Override
    public String repr() {
        return items.isEmpty() ? "frozenset()" : "frozenset({" + contents() + "})";
    }

    /**
     * CPython's {@code frozenset_hash_impl}: every element hash shuffled and XORed together, the
     * length folded in, and the result dispersed twice so that nested frozensets do not land on the
     * same value. Order-independent by construction, as equality is.
     */
    @Override
    public long hash() {
        return contentHash(items);
    }

    /** The same mix over any collection of values, which is how a set with no hash of its own is found. */
    static long contentHash(Iterable<PyObject> elements) {
        long hash = 0;
        int size = 0;
        for (PyObject element : elements) {
            hash ^= shuffle(element.hash());
            size++;
        }
        hash ^= (size + 1L) * 1927868237L;
        hash ^= (hash >>> 11) ^ (hash >>> 25);
        hash = hash * 69069L + 907133923L;
        // -1 means "no hash" to CPython, so it is replaced rather than returned.
        return hash == -1L ? 590923713L : hash;
    }

    /** The one mixing step, with CPython's two constants. */
    private static long shuffle(long h) {
        return ((h ^ 89869747L) ^ (h << 16)) * 3644798167L;
    }
}
