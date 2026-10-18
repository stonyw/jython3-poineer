package org.python.core;

/**
 * Python's {@code str}, backed by a Java {@code String}.
 *
 * Two consequences are tracked deliberately. Length and indexing are code-point based, since
 * {@code len('𝕐') == 1} is observable Python behaviour and UTF-16 units would say 2; storage stays
 * UTF-16 because Java cannot avoid it. And a str may hold a lone surrogate, which is representable
 * in both a Java String and a Python str.
 */
public final class PyStr extends PyObject {

    private final String value;
    private final boolean interned;
    private final int codePoints;

    private PyStr(String value, boolean interned) {
        super(PyType.STR);
        this.value = value;
        this.interned = interned;
        this.codePoints = value.codePointCount(0, value.length());
    }

    public static PyStr of(String value) {
        return new PyStr(value, false);
    }

    /**
     * Interning is what lets the interpreter compare method and variable names by pointer. Java's
     * own string table serves that role, so an interned {@code PyStr} always wraps an interned
     * {@code String}.
     */
    public static PyStr interned(String value) {
        return new PyStr(value.intern(), true);
    }

    public String getValue() {
        return value;
    }

    public boolean isInterned() {
        return interned;
    }

    /** {@code len()}, in code points. */
    public int codePointLength() {
        return codePoints;
    }

    /**
     * The bytes a {@code str} turns into when encoded as UTF-8. Java's own encoder substitutes a
     * {@code ?} for a lone surrogate; Python raises there only under {@code 'strict'}, and
     * {@code marshal} never uses that mode.
     */
    public byte[] toUtf8() {
        return PyHash.encodeUtf8(value);
    }

    @Override
    public long hash() {
        return PyHash.textHash(value);
    }

    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyStr s) {
            return interned && s.interned ? value == s.value : value.equals(s.value);
        }
        return false;
    }

    @Override
    public String str() {
        return value;
    }

    @Override
    public String repr() {
        return PyRepr.quote(value, "");
    }
}
