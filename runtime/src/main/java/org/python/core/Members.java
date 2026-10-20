package org.python.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The methods a type's values share, as CPython's method definition tables are.
 *
 * One table per type, built once in a static field, and a lookup that hands back a
 * {@link PyBoundMethod} carrying the receiver — which is what makes {@code "a".upper} print as
 * {@code <built-in method upper of str object>} and compare correctly against itself.
 *
 * <p>A table also remembers which type it belongs to, because the same methods are reachable through
 * the type: {@code str.upper("a")} takes the value as its argument, and CPython answers that with a
 * {@link PyMethodDescriptor} rather than refusing. {@link #tableOf} is how a type finds its table, and
 * installs the one its value class declares when no value of that type has been built yet.
 *
 * <p>Both lookups here walk the type's {@link PyType#mro mro()}, so a type has the methods it inherits
 * and not only the ones it declares: {@code True.bit_length()} reaches the table {@code int} declares,
 * and {@code bool.bit_length} is the same descriptor {@code int.bit_length} gives.
 *
 * Names here are the Python-visible ones only; a dunder is a slot on {@link PyObject} and is answered
 * there, because the interpreter calls slots directly rather than looking them up as attributes.
 */
final class Members {

    /** A method taking one argument, checked for the caller. */
    interface One {
        PyObject apply(PyObject self, PyObject argument);
    }

    /** Each table by the type it was built for, which is what a lookup on the class consults. */
    private static final Map<PyType, Members> REGISTRY = new ConcurrentHashMap<>();

    /**
     * The class that declares each built-in type's table.
     *
     * <p>A table is a static field, so Java initializes it only when the class is first used — and a
     * script that only ever asks {@code set.add} has never made a set. {@link #tableOf} initializes the
     * class named here, which is what makes a type's members answerable before any value of it exists.
     */
    private static final Map<PyType, Class<?>> VALUE_CLASSES = Map.ofEntries(
            Map.entry(PyType.INT, PyInt.class),
            Map.entry(PyType.FLOAT, PyFloat.class),
            Map.entry(PyType.COMPLEX, PyComplex.class),
            Map.entry(PyType.STR, PyStr.class),
            Map.entry(PyType.BYTES, PyBytes.class),
            Map.entry(PyType.TUPLE, PyTuple.class),
            Map.entry(PyType.LIST, PyList.class),
            Map.entry(PyType.DICT, PyDict.class),
            Map.entry(PyType.SET, PySet.class),
            Map.entry(PyType.FROZENSET, PySet.class),
            Map.entry(PyType.GENERATOR, PyGenerator.class));

    private final PyType owner;

    /** Built during the value class's own initialization, then only read. */
    private final Map<String, PyBoundMethod.Body> entries = new LinkedHashMap<>();

    /** Made on the first lookup through the type, which is why this one is concurrent. */
    private final Map<String, PyObject> descriptors = new ConcurrentHashMap<>();

    private Members(PyType owner) {
        this.owner = owner;
    }

    static Members of(PyType type) {
        Members methods = new Members(type);
        REGISTRY.put(type, methods);
        return methods;
    }

    /**
     * The table {@code type} declares, installing it if this is the first time the type has been asked.
     *
     * @return the table, or null when the type declares no methods of its own — a class built by
     *     {@link Classes} holds its members in a namespace, and {@code object} has none
     */
    static Members tableOf(PyType type) {
        Members methods = REGISTRY.get(type);
        if (methods != null) {
            return methods;
        }
        Class<?> values = VALUE_CLASSES.get(type);
        if (values == null) {
            return null;
        }
        try {
            Class.forName(values.getName(), true, Members.class.getClassLoader());
        } catch (ClassNotFoundException declaredInThisRuntime) {
            throw Slots.runtimeError(
                    "the value class " + values.getName() + " is not on the class path");
        }
        return REGISTRY.get(type);
    }

    /**
     * The method named {@code name} that {@code self}'s type inherits, bound to {@code self}.
     *
     * <p>The walk is the type's lookup order, so a bool is answered by the methods int declares. The
     * bound method keeps the receiver Python asked for — {@code True.bit_length.__self__} is the bool,
     * and {@code LOAD_ATTR} recognises it as already bound — while the Java body is given the value its
     * own type can read, since it casts to {@link PyInt} rather than to {@link PyBool}. See
     * {@link PyObject#adaptedFor}.
     *
     * @return the bound method, or {@link PyNotImplemented} when no type in the order declares it
     */
    static PyObject find(PyObject self, String name) {
        for (PyType owner : self.getType().mro()) {
            Members methods = tableOf(owner);
            if (methods == null) {
                continue;
            }
            PyObject bound = methods.bind(self, name);
            if (bound != PyNotImplemented.INSTANCE) {
                return bound;
            }
        }
        return PyNotImplemented.INSTANCE;
    }

    /** The full form, for a method that checks its own arguments or accepts keywords. */
    Members add(String name, PyBoundMethod.Body body) {
        entries.put(name, body);
        return this;
    }

    /** {@code upper()}, {@code bit_length()}: no arguments, and the message says so in CPython's words. */
    Members addNoArgument(String name, Function<PyObject, PyObject> body) {
        String qualified = owner.name() + "." + name + "()";
        return add(name, (self, args, kwNames) -> {
            Arguments.none(qualified, args, kwNames);
            return body.apply(self);
        });
    }

    /** {@code append(x)}, {@code join(x)}: exactly one argument. */
    Members addOne(String name, One body) {
        String qualified = owner.name() + "." + name + "()";
        return add(name, (self, args, kwNames) -> {
            Arguments.exactlyOne(qualified, args, kwNames);
            return body.apply(self, args[0]);
        });
    }

    /**
     * The method named {@code name}, bound to {@code self}, or a refusal if this type has no such method.
     *
     * <p>{@code self} is kept as the receiver and only the body sees the adapted one, so that a method a
     * bool inherits from int still reports itself as {@code bool.bit_length}.
     */
    PyObject bind(PyObject self, String name) {
        PyBoundMethod.Body body = entries.get(name);
        if (body == null) {
            return PyNotImplemented.INSTANCE;
        }
        PyObject receiver = self.adaptedFor(owner);
        if (receiver == self) {
            return new PyBoundMethod(self, name, body);
        }
        return new PyBoundMethod(self, name, (ignored, args, kwNames) ->
                body.apply(receiver, args, kwNames));
    }

    /**
     * The method named {@code name} as the type's own, unbound.
     *
     * <p>CPython hands out the same descriptor from the type's dictionary every time it is asked, so
     * {@code list.append is list.append} is true; the table keeps one per name for the same reason.
     */
    PyObject describe(String name) {
        if (!defines(name)) {
            return PyNotImplemented.INSTANCE;
        }
        return descriptors.computeIfAbsent(name, key -> new PyMethodDescriptor(owner, key));
    }

    /** Whether this type defines that method at all, which {@code hasattr} and {@code in} both ask. */
    boolean defines(String name) {
        return entries.containsKey(name);
    }

    /** The names Python sees, in declaration order, for {@code dir()}. */
    String[] names() {
        return entries.keySet().toArray(new String[0]);
    }
}
