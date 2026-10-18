package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A Python function: a code object, the globals it was defined in, and what the compiler attached to
 * it.
 *
 * Calling it is entirely the argument-binding step — check the shape of the call, then lay the values
 * into a new frame's variable slots — so {@link #bind} is where Python's calling conventions actually
 * live, and where every one of their errors is spelled the way CPython spells them.
 *
 * The slots are indexed by the code's own variable array, not by {@code co_varnames}: a parameter
 * captured by an inner function is a local <em>and</em> a cell, and appears once in that array, which
 * is the one the bytecodes address.
 */
public final class PyFunction extends PyObject {

    /**
     * The {@code SET_FUNCTION_ATTRIBUTE} argument values, read off {@code dis} of 3.14.8.
     *
     * There is no docstring flag: a docstring is the first entry of {@code co_consts}, which is why
     * {@code def f(): 'doc'} disassembles to a bare {@code MAKE_FUNCTION}.
     */
    static final int DEFAULTS = 0x1;
    static final int KW_DEFAULTS = 0x2;
    static final int ANNOTATIONS = 0x4;
    static final int CLOSURE = 0x8;
    static final int ANNOTATE = 0x10;

    private final PyCode code;
    private final PyDict globals;
    private PyObject name;
    private PyObject qualName;
    private PyObject doc = PyNone.INSTANCE;
    private PyObject module;
    private PyObject[] defaults = new PyObject[0];
    private PyDict kwDefaults;
    private PyCell[] closure = new PyCell[0];
    private PyDict attributes;

    PyFunction(PyCode code, PyDict globals) {
        super(PyType.FUNCTION);
        this.code = code;
        this.globals = globals;
        this.name = PyStr.of(code.name());
        this.qualName = code.qualName() == null ? name : PyStr.of(code.qualName());
        this.module = globals.get(PyStr.interned("__name__"));
        this.doc = docstringOf(code.constants());
    }

    /** A leading string in {@code co_consts} is the docstring; anything else means there is none. */
    private static PyObject docstringOf(PyTuple constants) {
        if (constants.size() == 0) {
            return PyNone.INSTANCE;
        }
        return constants.at(0) instanceof PyStr ? constants.at(0) : PyNone.INSTANCE;
    }

    public PyCode code() {
        return code;
    }

    public PyDict globals() {
        return globals;
    }

    /**
     * One {@code SET_FUNCTION_ATTRIBUTE}: take the value the compiler left below the function and
     * attach it.
     *
     * The compiler pushes attributes in ascending flag order beneath the code object, so they arrive
     * here in descending order — a closure is attached before the defaults that sit under it.
     */
    void setAttribute(int flag, PyObject value) {
        switch (flag) {
            case DEFAULTS -> defaults = tupleItems(value, "defaults");
            case KW_DEFAULTS -> kwDefaults = mapping(value, "kwdefaults");
            case CLOSURE -> closure = cells(value);
            case ANNOTATE -> {
                // Kept as a live attribute is what PEP 649 wants; deferred annotations are M3, so the
                // annotate function is accepted and dropped rather than silently misapplied.
            }
            case ANNOTATIONS -> throw Slots.notImplemented(
                    "the old-style __annotations__ attribute");
            default -> throw Slots.notImplemented("function attribute flag " + flag);
        }
    }

    private static PyObject[] tupleItems(PyObject value, String what) {
        if (value instanceof PyTuple tuple) {
            PyObject[] items = new PyObject[tuple.size()];
            for (int i = 0; i < items.length; i++) {
                items[i] = tuple.at(i);
            }
            return items;
        }
        throw Slots.typeError("%s must be a tuple, not '%s'", what, value.getType().name());
    }

    private static PyDict mapping(PyObject value, String what) {
        if (value instanceof PyDict dict) {
            return dict;
        }
        throw Slots.typeError("%s must be a dict, not '%s'", what, value.getType().name());
    }

    private static PyCell[] cells(PyObject value) {
        PyObject[] items = tupleItems(value, "closure");
        PyCell[] cells = new PyCell[items.length];
        for (int i = 0; i < items.length; i++) {
            if (!(items[i] instanceof PyCell cell)) {
                throw Slots.typeError(
                        "closure item %d is not a cell but '%s'", i, items[i].getType().name());
            }
            cells[i] = cell;
        }
        return cells;
    }

    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        int positional = kwNames == null ? args.length : args.length - kwNames.size();
        Frame frame = new Frame(code, globals, bind(args, positional, kwNames));
        frame.function = this;
        return Interpreter.run(frame);
    }

    /** The cells an inner function of this one will receive, in {@code co_freevars} order. */
    PyCell[] closureCells() {
        return closure;
    }

    /**
     * Check a call against this function's signature and lay its values into slot order.
     *
     * @param args positional arguments first, then one value per entry of {@code kwNames}
     * @param positional how many of {@code args} arrived without a keyword
     * @param kwNames the keyword names, in the order the caller gave them, or null
     * @throws TypeError in CPython's own words, which are worth matching: they are asserted by
     *     CPython's own test suite
     */
    PyObject[] bind(PyObject[] args, int positional, PyTuple kwNames) {
        int parameters = code.argCount();
        int keywordOnly = code.kwOnlyArgCount();
        boolean varArgs = (code.flags() & PyCode.CO_VARARGS) != 0;
        boolean varKeywords = (code.flags() & PyCode.CO_VARKEYWORDS) != 0;
        PyObject[] slots = new PyObject[code.localCount()];

        if (positional > parameters && !varArgs) {
            throw Slots.typeError(
                    "%s() takes %d positional argument%s but %d were given",
                    code.name(), parameters, parameters == 1 ? "" : "s", positional);
        }
        for (int i = 0; i < parameters; i++) {
            slots[i] = i < positional ? args[i] : null;
        }
        int varArgsAt = parameters + keywordOnly;
        if (varArgs) {
            // The star tuple is only what the caller gave beyond the declared parameters; a short
            // call fills the rest from defaults and keywords, so positional can be below parameters.
            slots[varArgsAt] =
                    PyTuple.of(
                            java.util.Arrays.copyOfRange(args, parameters, Math.max(parameters, positional)));
        }

        int varKeywordsAt = varArgsAt + (varArgs ? 1 : 0);
        PyDict collected = varKeywords ? PyDict.empty() : null;
        int keywords = kwNames == null ? 0 : kwNames.size();
        // A parameter before the '/' is invisible to keyword matching, so naming it is a mistake —
        // unless the function gathers keywords, where such a name is simply one of them. CPython
        // reports every offending name at once, inside a single pair of quotes.
        if (!varKeywords) {
            List<String> shadowed = new ArrayList<>();
            for (int i = 0; i < keywords; i++) {
                String name = ((PyStr) kwNames.at(i)).getValue();
                if (parameterSlot(name, 0, code.posOnlyArgCount()) >= 0) {
                    shadowed.add(name);
                }
            }
            if (!shadowed.isEmpty()) {
                throw Slots.typeError(
                        "%s() got some positional-only arguments passed as keyword arguments: '%s'",
                        code.name(), String.join(", ", shadowed));
            }
        }
        for (int i = 0; i < keywords; i++) {
            PyStr key = (PyStr) kwNames.at(i);
            PyObject value = args[positional + i];
            int at = parameterSlot(key.getValue(), code.posOnlyArgCount(), parameters + keywordOnly);
            if (at < 0) {
                if (collected == null) {
                    throw Slots.typeError(
                            "%s() got an unexpected keyword argument '%s'",
                            code.name(), key.getValue());
                }
                collected.put(key, value);
                continue;
            }
            if (at < parameters && at < positional) {
                // Rejected even when the positional came from *args: CPython lifted that exemption in
                // 3.11, and a call like f(*[1], a=2) is a bug worth naming rather than a choice.
                throw Slots.typeError(
                        "%s() got multiple values for argument '%s'", code.name(), key.getValue());
            }
            slots[at] = value;
        }
        if (varKeywords) {
            slots[varKeywordsAt] = collected;
        }

        applyDefaults(slots, parameters, keywordOnly);
        return slots;
    }

    /**
     * Fill what the caller left out, from the defaults the compiler attached.
     *
     * Positional defaults are right-aligned against the parameter list, which is why a parameter
     * after a defaulted one can only be keyword-only; keyword defaults are looked up by name.
     */
    private void applyDefaults(PyObject[] slots, int parameters, int keywordOnly) {
        int firstDefault = parameters - defaults.length;
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < parameters; i++) {
            if (slots[i] != null) {
                continue;
            }
            int at = i - firstDefault;
            if (at >= 0 && at < defaults.length) {
                slots[i] = defaults[at];
            } else {
                missing.add(parameterName(i));
            }
        }
        if (!missing.isEmpty()) {
            throw Slots.typeError(
                    "%s() missing %d required positional argument%s: %s",
                    code.name(), missing.size(), missing.size() == 1 ? "" : "s", listed(missing));
        }
        List<String> missingKeyword = new ArrayList<>();
        for (int i = 0; i < keywordOnly; i++) {
            int at = parameters + i;
            if (slots[at] != null) {
                continue;
            }
            PyObject fallback = kwDefaults == null
                    ? null
                    : kwDefaults.get(code.varNames().at(at));
            if (fallback == null) {
                missingKeyword.add(parameterName(at));
            } else {
                slots[at] = fallback;
            }
        }
        if (!missingKeyword.isEmpty()) {
            throw Slots.typeError(
                    "%s() missing %d required keyword-only argument%s: %s",
                    code.name(),
                    missingKeyword.size(),
                    missingKeyword.size() == 1 ? "" : "s",
                    listed(missingKeyword));
        }
    }

    /** {@code 'a', 'b', and 'c'} — CPython's Oxford-comma list, one message at a time. */
    private static String listed(List<String> names) {
        List<String> quoted = new ArrayList<>(names.size());
        for (String name : names) {
            quoted.add("'" + name + "'");
        }
        if (quoted.size() == 1) {
            return quoted.get(0);
        }
        String last = quoted.remove(quoted.size() - 1);
        return String.join(", ", quoted) + ", and " + last;
    }

    private String parameterName(int index) {
        return ((PyStr) code.varNames().at(index)).getValue();
    }

    /** The slot a keyword names, among the declared parameters, or -1 when it names nothing. */
    private int parameterSlot(String key, int from, int declared) {
        PyTuple names = code.varNames();
        for (int i = from; i < declared; i++) {
            if (((PyStr) names.at(i)).getValue().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> name;
            case "__qualname__" -> qualName;
            case "__doc__" -> doc;
            case "__module__" -> module == null ? PyNone.INSTANCE : module;
            case "__code__" -> (PyObject) code;
            case "__defaults__" -> PyTuple.of(defaults);
            case "__kwdefaults__" -> kwDefaults == null ? PyNone.INSTANCE : kwDefaults;
            case "__closure__" -> closure.length == 0
                    ? PyNone.INSTANCE
                    : PyTuple.of(closure.clone());
            case "__dict__" -> attributes();
            case "__globals__" -> globals;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        switch (attribute) {
            case "__name__" -> name = value;
            case "__qualname__" -> qualName = value;
            case "__doc__" -> doc = value;
            case "__module__" -> module = value;
            default -> attributes().put(PyStr.of(attribute), value);
        }
        return PyNone.INSTANCE;
    }

    private PyDict attributes() {
        if (attributes == null) {
            attributes = PyDict.empty();
        }
        return attributes;
    }

    @Override
    public String repr() {
        return "<function " + qualName.str() + ">";
    }
}
