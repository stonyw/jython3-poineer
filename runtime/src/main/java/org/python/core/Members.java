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
 * {@link PyMethodDescriptor} rather than refusing. {@link #registered} is how {@link PyType} finds the
 * table, and it is only ever populated as the value class is loaded — which any program that has made
 * one of its values has already done.
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

    /** The table built for {@code type}, or null when no value of that type has been made yet. */
    static Members registered(PyType type) {
        return REGISTRY.get(type);
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

    /** The method named {@code name}, bound to {@code self}, or a refusal if this type has no such method. */
    PyObject bind(PyObject self, String name) {
        PyBoundMethod.Body body = entries.get(name);
        return body == null ? PyNotImplemented.INSTANCE : new PyBoundMethod(self, name, body);
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
