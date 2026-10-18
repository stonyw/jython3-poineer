package org.python.core;

/**
 * Root of the Jython3 object model.
 *
 * The type is held in a field instead of being inferred from the Java class, because a
 * Python-level subclass (M3) has to report its own type without a Java subclass existing.
 */
public abstract class PyObject {

    /** Set by every constructor; only {@link PyType}'s registry rewrites it, to close the
     *  "type is an instance of type" cycle. */
    protected PyType type;

    protected PyObject() {
    }

    protected PyObject(PyType type) {
        this.type = type;
    }

    public final PyType getType() {
        return type;
    }

    /**
     * Python {@code hash()}. CPython's Py_hash_t is 64-bit and -1 is reserved for "error", so
     * {@code hash(-1) == -2}; returning a long keeps that reachable. Values are deliberately
     * deterministic rather than seed-randomised, which is a documented divergence from CPython
     * for {@code str} and {@code bytes}.
     */
    public long hash() {
        throw new UnsupportedOperationException("unhashable type: '" + type.name() + "'");
    }

    /** Python {@code ==}, for the cases where the answer is definite. */
    public boolean eq(PyObject other) {
        return this == other;
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof PyObject o && eq(o);
    }

    /** The int form of {@link #hash()}, for use as a Java key. */
    @Override
    public final int hashCode() {
        long h = hash();
        return (int) (h ^ (h >>> 32));
    }

    /** Python {@code str()}: what {@code print} shows. */
    public String str() {
        return repr();
    }

    /** Python {@code repr()}. */
    public String repr() {
        return "<" + type.name() + " object>";
    }

    @Override
    public final String toString() {
        return repr();
    }
}
