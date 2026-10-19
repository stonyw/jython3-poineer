package org.python.javaclass;

import java.lang.reflect.Method;
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
        return bound(owner, name, candidates, receiver, false);
    }

    /** {@code Widget.describe(5)}: a member of the class, so the call has no receiver. */
    static PyObject aClass(PyObject owner, String name, List<Method> candidates) {
        return bound(owner, name, candidates, null, false);
    }

    /**
     * {@code Widget.getName(widget)}: Java's own unbound form, where the receiver is the first argument.
     *
     * <p>2.7 keeps this spelling, and dropping it would make a class attribute that names an instance
     * method quietly mean something other than what it does in Java.
     */
    static PyObject unbound(PyObject owner, String name, List<Method> candidates) {
        return bound(owner, name, candidates, null, true);
    }

    private static PyObject bound(
            PyObject owner, String name, List<Method> candidates, Object receiver, boolean takesReceiver) {
        return new PyBoundMethod(
                owner,
                name,
                (self, args, kwNames) -> {
                    if (!takesReceiver) {
                        return Overloads.call(name, candidates, args, kwNames, receiver);
                    }
                    if (args.length == 0) {
                        throw Slots.typeError(
                                "%s() is an unbound Java method: pass an instance as the first argument",
                                name);
                    }
                    return Overloads.call(
                            name,
                            candidates,
                            Arrays.copyOfRange(args, 1, args.length),
                            kwNames,
                            receiverFor(name, args[0]));
                });
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
