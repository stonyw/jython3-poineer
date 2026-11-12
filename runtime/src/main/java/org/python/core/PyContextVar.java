package org.python.core;

/**
 * {@code _contextvars.ContextVar}: a name a value can be looked up by in the context of the current
 * call.
 *
 * <p>The variable holds no value of its own. What it holds is a name and an optional default, and
 * every value lives in a {@link PyContext}; {@link #get} reads the context the call is running in,
 * which is why the same variable answers differently inside {@code ctx.run()} and outside it.
 *
 * <p>{@link #set} hands back a {@link PyContextToken}. The token is the whole of the undo: it records
 * the context and the previous value, and {@link #reset} refuses a token from another variable, one
 * from another context, and one already used.
 */
final class PyContextVar extends PyObject {

    private static final String GET_DOC = """
            Return a value for the context variable for the current context.

            If there is no value for the variable in the current context, the
            method will:
             * return the value of the default argument of the method, if
               provided; or
             * return the default value for the context variable, if it was
               created with one; or
             * raise a LookupError.""";
    private static final String SET_DOC = """
            Call to set a new value for the context variable in the current context.

            The required value argument is the new value for the context
            variable.

            Returns a Token object that can be used to restore the variable to
            its previous value via the `ContextVar.reset()` method.""";
    private static final String RESET_DOC = """
            Reset the context variable.

            The variable is reset to the value it had before the
            `ContextVar.set()` that created the token was used.""";
    private static final Members METHODS = Members.of(PyType.CONTEXT_VAR)
            .addField("name", null, self -> PyStr.interned(((PyContextVar) self).name))
            .add("get", GET_DOC, (self, args, kwNames) -> ((PyContextVar) self).get(args, kwNames))
            .add("set", SET_DOC, (self, args, kwNames) -> ((PyContextVar) self).set(args, kwNames))
            .add("reset", RESET_DOC,
                    (self, args, kwNames) -> ((PyContextVar) self).reset(args, kwNames));

    private final String name;

    /** The default a read falls back to, or null where the variable was created without one. */
    private final PyObject defaultValue;

    private PyContextVar(String name, PyObject defaultValue) {
        super(PyType.CONTEXT_VAR);
        this.name = name;
        this.defaultValue = defaultValue;
    }

    /**
     * {@code ContextVar(name, *, default=<unset>)}.
     *
     * <p>The count checks come in CPython's order: too many arguments in all, then too many
     * positional, then too few positional, then an unknown keyword, and only then the type of the
     * name. That is why {@code ContextVar(1, other=2)} complains about {@code other} rather than
     * about the integer.
     */
    static PyContextVar construct(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (args.length > 2) {
            throw Slots.typeError("ContextVar() takes at most 2 arguments (%d given)", args.length);
        }
        if (positional > 1) {
            throw Slots.typeError(
                    "ContextVar() takes at most 1 positional argument (%d given)", positional);
        }
        if (positional < 1) {
            throw Slots.typeError(
                    "ContextVar() takes exactly 1 positional argument (%d given)", positional);
        }
        PyObject defaultValue = null;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!"default".equals(kwNames[i])) {
                    throw Slots.typeError(
                            "ContextVar() got an unexpected keyword argument '%s'", kwNames[i]);
                }
                defaultValue = args[positional + i];
            }
        }
        if (!(args[0] instanceof PyStr text)) {
            throw Slots.typeError("context variable name must be a str");
        }
        return new PyContextVar(text.getValue(), defaultValue);
    }

    /** One context's value, its default argument, the variable's own default, then {@code LookupError}. */
    private PyObject get(PyObject[] args, String[] kwNames) {
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError("ContextVar.get() takes no keyword arguments");
        }
        Arguments.between("get", args, kwNames, 0, 1);
        PyObject found = PyContext.current().values.get(this);
        if (found != null) {
            return found;
        }
        if (args.length == 1) {
            return args[0];
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        throw PyRaise.raise(ExceptionTypes.LOOKUP_ERROR, this);
    }

    private PyContextToken set(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("ContextVar.set()", args, kwNames);
        PyContext context = PyContext.current();
        PyObject previous = context.values.put(this, args[0]);
        return new PyContextToken(this, previous, context);
    }

    private PyObject reset(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("ContextVar.reset()", args, kwNames);
        if (!(args[0] instanceof PyContextToken token)) {
            throw Slots.typeError("expected an instance of Token, got " + args[0].repr());
        }
        if (token.used) {
            throw Slots.runtimeError(token.repr() + " has already been used once");
        }
        if (token.var != this) {
            throw Slots.valueError(token.repr() + " was created by a different ContextVar");
        }
        if (token.context != PyContext.current()) {
            throw Slots.valueError(token.repr() + " was created in a different Context");
        }
        PyContext current = PyContext.current();
        if (token.oldValue == null) {
            current.values.remove(this);
        } else {
            current.values.put(this, token.oldValue);
        }
        token.used = true;
        return PyNone.INSTANCE;
    }

    /**
     * CPython's {@code ContextVar} is hashable as the object it is, and that identity hash is what
     * lets a context key its values by one. {@link PyObject#hashCode()} is final and reads this, so
     * the map the context keeps can use the variable itself as a key.
     */
    @Override
    public long hash() {
        // Masked so the Python-facing value is positive, as a pointer hash is in the reference.
        return System.identityHashCode(this) & 0xFFFFFFFFL;
    }

    @Override
    public String repr() {
        return "<ContextVar name=" + PyStr.of(name).repr()
                + (defaultValue == null ? "" : " default=" + defaultValue.repr())
                + " at 0x" + Integer.toHexString(System.identityHashCode(this)) + ">";
    }
}
