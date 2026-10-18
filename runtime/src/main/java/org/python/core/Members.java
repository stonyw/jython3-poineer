package org.python.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The methods a type's values share, as CPython's method definition tables are.
 *
 * One table per type, built once in a static field, and a lookup that hands back a
 * {@link PyBoundMethod} carrying the receiver — which is what makes {@code "a".upper} print as
 * {@code <built-in method upper of str object>} and compare correctly against itself.
 *
 * Names here are the Python-visible ones only; a dunder is a slot on {@link PyObject} and is answered
 * there, because the interpreter calls slots directly rather than looking them up as attributes.
 */
final class Members {

    /** A method taking one argument, checked for the caller. */
    interface One {
        PyObject apply(PyObject self, PyObject argument);
    }

    private final String typeName;
    private final Map<String, PyBoundMethod.Body> entries = new LinkedHashMap<>();

    private Members(String typeName) {
        this.typeName = typeName;
    }

    static Members of(String typeName) {
        return new Members(typeName);
    }

    /** The full form, for a method that checks its own arguments or accepts keywords. */
    Members add(String name, PyBoundMethod.Body body) {
        entries.put(name, body);
        return this;
    }

    /** {@code upper()}, {@code bit_length()}: no arguments, and the message says so in CPython's words. */
    Members addNoArgument(String name, Function<PyObject, PyObject> body) {
        String qualified = typeName + "." + name + "()";
        return add(name, (self, args, kwNames) -> {
            Arguments.none(qualified, args, kwNames);
            return body.apply(self);
        });
    }

    /** {@code append(x)}, {@code join(x)}: exactly one argument. */
    Members addOne(String name, One body) {
        String qualified = typeName + "." + name + "()";
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

    /** Whether this type defines that method at all, which {@code hasattr} and {@code in} both ask. */
    boolean defines(String name) {
        return entries.containsKey(name);
    }

    /** The names Python sees, in declaration order, for {@code dir()}. */
    String[] names() {
        return entries.keySet().toArray(new String[0]);
    }
}
