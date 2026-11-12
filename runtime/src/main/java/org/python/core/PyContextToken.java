package org.python.core;

/**
 * {@code _contextvars.Token}: what {@link PyContextVar#set} returns, and the undo of that set.
 *
 * <p>A token is single-use. {@code reset} marks it used and refuses a second call, which is CPython's
 * guard against restoring a value over a newer one; the token also carries the variable and the
 * context so that a reset in the wrong place is refused rather than silently applied.
 *
 * <p>{@code Token.MISSING} is the sentinel {@code old_value} answers when the variable had no value
 * before the {@code set}. It is a singleton of its own type, filed as {@code Token.MISSING} — which is
 * exactly how CPython writes that type's {@code tp_name}, so {@code type(token.MISSING)} prints
 * {@code <class 'Token.MISSING'>} and its {@code __module__} reads {@code Token}.
 */
final class PyContextToken extends PyObject {

    private static final Members METHODS = Members.of(PyType.CONTEXT_TOKEN)
            .addField("var", null, self -> ((PyContextToken) self).var)
            .addField("old_value", null, self -> {
                PyObject previous = ((PyContextToken) self).oldValue;
                return previous == null ? Missing.INSTANCE : previous;
            })
            .addValue("MISSING", Missing.INSTANCE);

    final PyContextVar var;

    /** The value the variable held in {@link #context} before the set, or null where it held none. */
    final PyObject oldValue;

    /** The context the set happened in; a reset anywhere else is refused. */
    final PyContext context;

    /** Set by the first reset, which is what a second one reports. */
    boolean used;

    PyContextToken(PyContextVar var, PyObject oldValue, PyContext context) {
        super(PyType.CONTEXT_TOKEN);
        this.var = var;
        this.oldValue = oldValue;
        this.context = context;
    }

    @Override
    public String repr() {
        return "<Token" + (used ? " used" : "") + " var=" + var.repr()
                + " at 0x" + Integer.toHexString(System.identityHashCode(this)) + ">";
    }

    /** The one object {@code Token.MISSING} is: a sentinel, not a token, and there is only one. */
    static final class Missing extends PyObject {

        static final Missing INSTANCE = new Missing();

        private Missing() {
            super(PyType.CONTEXT_TOKEN_MISSING);
        }

        @Override
        public String repr() {
            return "<Token.MISSING>";
        }
    }
}
