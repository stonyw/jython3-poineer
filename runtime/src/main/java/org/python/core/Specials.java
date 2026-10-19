package org.python.core;

/**
 * The double-underscore names a class declares, which are how a user-defined type answers the
 * operations the built-in types answer with a Java slot.
 *
 * <p>{@link #lookUp} is CPython's {@code _PyType_Lookup}: walk the lookup order and take the first
 * dictionary that holds the name. That is the whole of the class system's attribute machinery, and
 * everything else — a method reached through an instance, a dunder reached through an operation — is
 * built on it.
 *
 * <p>A dunder is invoked here rather than through {@link Slots#getAttr} because the interpreter calls
 * slots directly and must not see a bound method, and because a class is allowed to hold anything at
 * all under these names: {@code __hash__ = None} is a value with a meaning of its own, which is why
 * {@link #function} only accepts a function and callers that must tell "absent" from "present but not
 * a function" use {@link #lookUp} themselves.
 */
final class Specials {

    private Specials() {
    }

    /**
     * The value the lookup order holds for {@code name}.
     *
     * <p>Only classes built by {@link Classes} have a dictionary to search. A built-in type in the same
     * order — {@code object}, or {@code int} once subclassing one is allowed — declares its members in
     * a {@link Members} table instead, which {@link PyType#getAttr} walks separately.
     *
     * @return the value, or null when no type in the order names it
     */
    static PyObject lookUp(PyType type, String name) {
        PyStr key = PyStr.interned(name);
        for (PyType owner : type.mro()) {
            if (owner instanceof PyClass declaring) {
                PyObject value = declaring.namespace().get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** Whether this type inherits a declaration of {@code name} at all, whatever it holds. */
    static boolean defines(PyType type, String name) {
        return lookUp(type, name) != null;
    }

    /**
     * The function declared under {@code name}, or null.
     *
     * <p>A class that holds {@code None} under the name — which is what a class that declares
     * {@code __eq__} but no {@code __hash__} is given — answers null here, and the caller that has to
     * distinguish the two asks {@link #lookUp} first.
     */
    static PyFunction function(PyType type, String name) {
        return lookUp(type, name) instanceof PyFunction declared ? declared : null;
    }

    /**
     * {@code self.name(...)}: what the method hands back.
     *
     * <p>A class that declares nothing under the name declines in the same currency as a built-in type
     * whose Java slot is empty, so a caller that runs a protocol can pass this straight back to
     * {@link Slots} and get the protocol's own refusal.
     *
     * @return the result, or {@link PyNotImplemented} when no class in the order declares the name as a
     *     function — which is also what {@code __hash__ = None} means, and is meant by it
     */
    static PyObject invoke(PyObject self, String name, PyObject... arguments) {
        return invoke(self, name, arguments, null);
    }

    /**
     * {@code self.name(*arguments, **kwNames)}, for the slots a call site can pass keywords to.
     *
     * <p>{@code __call__} is the one that matters: {@code doubling(21)} has to reach
     * {@code __call__(self, value)} with whatever spelling the call used, and a keyword argument that
     * vanished here would be a silently wrong call rather than a refused one.
     */
    static PyObject invoke(PyObject self, String name, PyObject[] arguments, PyTuple kwNames) {
        PyObject declared = lookUp(self.getType(), name);
        if (!(declared instanceof PyFunction method)) {
            return PyNotImplemented.INSTANCE;
        }
        PyObject[] all = new PyObject[arguments.length + 1];
        all[0] = self;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return method.call(all, kwNames);
    }
}
