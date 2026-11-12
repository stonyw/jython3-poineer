package org.python.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code _contextvars.Context}: a mapping from variables to the values they have while a callable
 * runs in it.
 *
 * <p>CPython keeps the mapping in a height-balanced tree so that copying one is cheap. Here a copy is
 * a copy of a {@link LinkedHashMap}, which is the same answer with a different cost, and the one
 * observable difference is iteration order: CPython's is hash order and is documented as unspecified,
 * so these lists answer insertion order instead.
 *
 * <p>The context a call is running in is per OS thread. Nothing in this runtime can start a second
 * Python thread yet, but {@code _thread} exists and {@code warnings} is written as though it might,
 * so the slot is a {@link ThreadLocal} rather than a field of {@link PyRuntime}.
 */
final class PyContext extends PyObject {

    private static final String GET_DOC = """
            Return the value for `key` if `key` has the value in the context object.

            If `key` does not exist, return `default`.  If `default` is not
            given, return None.""";
    private static final String ITEMS_DOC = """
            Return all variables and their values in the context object.

            The result is returned as a list of 2-tuples (variable, value).""";
    private static final String KEYS_DOC = "Return a list of all variables in the context object.";
    private static final String VALUES_DOC =
            "Return a list of all variables' values in the context object.";
    private static final String COPY_DOC = "Return a shallow copy of the context object.";

    private static final Members METHODS = Members.of(PyType.CONTEXT)
            .add("run", (self, args, kwNames) -> ((PyContext) self).run(args, kwNames))
            .addNoArgument("copy", COPY_DOC, self -> ((PyContext) self).copy())
            .add("get", GET_DOC, (self, args, kwNames) -> ((PyContext) self).get(args, kwNames))
            .addNoArgument("items", ITEMS_DOC, self -> ((PyContext) self).items())
            .addNoArgument("keys", KEYS_DOC, self -> ((PyContext) self).keys())
            .addNoArgument("values", VALUES_DOC, self -> ((PyContext) self).values());

    /** The context of the call running on this thread; a fresh one until something enters a context. */
    private static final ThreadLocal<PyContext> CURRENT = ThreadLocal.withInitial(PyContext::new);

    final LinkedHashMap<PyContextVar, PyObject> values = new LinkedHashMap<>();

    /** True while {@link #run} is executing, which is what refuses a second entry of the same context. */
    private boolean entered;

    PyContext() {
        super(PyType.CONTEXT);
    }

    /** {@code Context()}: no arguments and no keywords, and each says the same sentence. */
    static PyContext construct(PyObject[] args, String[] kwNames) {
        if (args.length > 0 || (kwNames != null && kwNames.length > 0)) {
            throw Slots.typeError("Context() does not accept any arguments");
        }
        return new PyContext();
    }

    static PyContext current() {
        return CURRENT.get();
    }

    PyContext copy() {
        PyContext copy = new PyContext();
        copy.values.putAll(values);
        return copy;
    }

    /**
     * {@code ctx.run(func, *args, **kwargs)}: enter this context, call, leave.
     *
     * <p>{@code func} is positional-only, so a call that only passes it by keyword is the missing
     * argument rather than a keyword refusal; everything after it is handed on untouched.
     */
    private PyObject run(PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError("run() missing 1 required positional argument");
        }
        if (entered) {
            throw Slots.runtimeError("cannot enter context: " + repr() + " is already entered");
        }
        PyObject function = args[0];
        PyObject[] forwarded = Arrays.copyOfRange(args, 1, args.length);
        PyTuple keywords = PyBoundMethod.keywordTuple(kwNames);
        PyContext previous = CURRENT.get();
        entered = true;
        CURRENT.set(this);
        try {
            return Slots.call(function, forwarded, keywords);
        } finally {
            CURRENT.set(previous);
            entered = false;
        }
    }

    private PyObject get(PyObject[] args, String[] kwNames) {
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError("Context.get() takes no keyword arguments");
        }
        Arguments.between("get", args, kwNames, 1, 2);
        PyObject found = values.get(contextVar(args[0]));
        if (found != null) {
            return found;
        }
        return args.length == 2 ? args[1] : PyNone.INSTANCE;
    }

    private PyList items() {
        List<PyObject> out = new ArrayList<>(values.size());
        for (Map.Entry<PyContextVar, PyObject> entry : values.entrySet()) {
            out.add(PyTuple.of(entry.getKey(), entry.getValue()));
        }
        return PyList.of(out.toArray(PyObject[]::new));
    }

    private PyList keys() {
        return PyList.of(values.keySet().toArray(PyObject[]::new));
    }

    private PyList values() {
        return PyList.of(values.values().toArray(PyObject[]::new));
    }

    /**
     * The one key type a context accepts, and the sentence CPython sentences everything else with.
     */
    private static PyContextVar contextVar(PyObject key) {
        if (key instanceof PyContextVar variable) {
            return variable;
        }
        throw Slots.typeError("a ContextVar key was expected, got " + key.repr());
    }

    @Override
    public PyObject getItem(PyObject key) {
        PyContextVar variable = contextVar(key);
        PyObject found = values.get(variable);
        if (found == null) {
            throw PyRaise.raise(ExceptionTypes.KEY_ERROR, variable);
        }
        return found;
    }

    @Override
    public PyObject contains(PyObject item) {
        return PyBool.of(values.containsKey(contextVar(item)));
    }

    @Override
    public PyObject length() {
        return PyInt.of(values.size());
    }

    @Override
    public String repr() {
        return "<" + getType().filedName() + " object at 0x"
                + Integer.toHexString(System.identityHashCode(this)) + ">";
    }
}
