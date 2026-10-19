package org.python.javaclass;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.python.core.PyBoundMethod;
import org.python.core.PyObject;
import org.python.core.Slots;

/**
 * A Java member, presented as the callable attribute Python expects.
 *
 * <p>It hands back a {@link PyBoundMethod} whose receiver is the object the attribute was looked up on,
 * and that choice is not cosmetic. {@code LOAD_ATTR} in its method form pushes a receiver slot next to
 * the callable, and the runtime clears that slot only when the attribute it found is already bound to
 * that same object — which is how {@code "a".upper()} avoids passing {@code "a"} twice. A Java method
 * that answered with a callable of its own would therefore receive its receiver as a first argument.
 * Binding here is what keeps {@code org.python.core} free of any knowledge of Java.
 *
 * <p>A static method reached through an instance is still bound to it, and reflection ignores the
 * receiver of a static call, so {@code widget.describe(5)} and {@code Widget.describe(5)} both work —
 * as they do in Java, where the second form is only a style error.
 */
final class JavaMethods {

    private JavaMethods() {
    }

    /** Every method of {@code type} with this name, bridges excluded. */
    static List<Method> named(Class<?> type, String name) {
        List<Method> out = new ArrayList<>();
        for (Method method : Overloads.visibleMethods(type)) {
            if (method.getName().equals(name)) {
                out.add(method);
            }
        }
        return out;
    }

    /** {@code widget.area()}: the receiver was found with the member. */
    static PyObject instance(PyObject owner, String name, List<Method> candidates, Object receiver) {
        return new PyBoundMethod(
                owner,
                name,
                (self, args, kwNames) -> Overloads.call(name, candidates, args, kwNames, receiver));
    }

    /**
     * A method named on its own class, where Java has two spellings under one name.
     *
     * <p>{@code Point2D.distance(0, 0, 3, 4)} is the static method Java declares, and
     * {@code Point2D.distance(point, 3, 4)} is Java's unbound form, which Python is allowed to write and
     * Java is not. Both live under {@code distance} on the same class, so which one a call means is a
     * question about the arguments and can only be answered at the call: the static candidates are asked
     * first — Java's own order, since a name in a class context resolves to a static member — and the
     * receiver becomes the first argument only when no static candidate takes what was passed. A class
     * whose methods are all static, or all instance, resolves exactly as it did when the two spellings
     * were separate.
     */
    static PyObject onClass(PyObject owner, String name, List<Method> candidates) {
        List<Method> statics = candidates.stream().filter(JavaMethods::isStatic).toList();
        List<Method> instances =
                candidates.stream().filter(one -> !isStatic(one)).toList();
        return new PyBoundMethod(
                owner,
                name,
                (self, args, kwNames) -> {
                    if (statics.isEmpty()) {
                        return asUnbound(name, instances, args, kwNames);
                    }
                    if (instances.isEmpty()
                            || args.length == 0
                            || Overloads.takes(name, statics, args, kwNames)) {
                        return Overloads.call(name, statics, args, kwNames, null);
                    }
                    return asUnbound(name, instances, args, kwNames);
                });
    }

    private static boolean isStatic(Method method) {
        return Modifier.isStatic(method.getModifiers());
    }

    private static PyObject asUnbound(
            String name, List<Method> instances, PyObject[] args, String[] kwNames) {
        if (args.length == 0) {
            throw Slots.typeError(
                    "%s() is an unbound Java method: pass an instance as the first argument", name);
        }
        return Overloads.call(
                name,
                instances,
                Arrays.copyOfRange(args, 1, args.length),
                kwNames,
                receiverFor(name, args[0]));
    }

    private static Object receiverFor(String name, PyObject candidate) {
        if (candidate instanceof PyJavaObject java) {
            return java.target();
        }
        throw Slots.typeError(
                "%s() expects a Java instance as its first argument, got %s",
                name, candidate.getType().name());
    }
}
