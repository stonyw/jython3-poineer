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
     * The raw member {@code name} in this type's lookup order, descriptor or not.
     *
     * <p>CPython's {@code _PyType_Lookup} is what {@link PyClass}'s four-step attribute read and {@link
     * PySuper} both need, and it answers from three places for each class in the order: the namespace a
     * Python class wrote, the class-system members {@link TypeDict} keeps for {@code type} and {@code
     * object}, and the {@link Members} table a built-in type compiles in — whose unbound method
     * descriptor is the same object that class's dictionary would have held.
     *
     * @return the member, or null when no type in the order names it
     */
    static PyObject member(PyType type, String name) {
        for (PyType owner : type.mro()) {
            PyObject declared = declared(owner, name);
            if (declared != null) {
                return declared;
            }
        }
        return null;
    }

    /**
     * The raw member {@code owner} holds under {@code name}, with no walk past it.
     *
     * <p>This is CPython's {@code lookup_tp_dict} step of {@code _super_lookup_descr} and of
     * {@code _PyType_Lookup}: one class's own dictionary, which for a built-in type is the {@link Members}
     * table and the {@link TypeDict} entry it is named in.
     *
     * @return the member, or null when this class holds nothing under the name
     */
    static PyObject declared(PyType owner, String name) {
        PyObject fromDict = TypeDict.member(owner, name);
        if (fromDict != null) {
            return fromDict;
        }
        if (owner instanceof PyClass namespace) {
            PyObject value = namespace.namespace().get(PyStr.interned(name));
            if (value != null) {
                return value;
            }
        }
        Members methods = Members.tableOf(owner);
        if (methods == null) {
            return null;
        }
        PyObject descriptor = methods.describe(name);
        return descriptor == PyNotImplemented.INSTANCE ? null : descriptor;
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
