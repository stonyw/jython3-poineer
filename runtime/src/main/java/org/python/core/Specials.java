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
     * {@code _PyType_Lookup}: one class's own dictionary, which every type here keeps — a class Python
     * wrote holds the namespace its statement collected, and a built-in type the table {@link TypeDict}
     * builds for it from the members its value class compiles in.
     *
     * @return the member, or null when this class holds nothing under the name
     */
    static PyObject declared(PyType owner, String name) {
        return owner.namespace().get(PyStr.interned(name));
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
     * <p>A class that declares nothing under the name declines with {@link Slots#DECLINED}, the same currency
     * as a built-in type whose Java slot is empty, so a caller that runs a protocol can pass the answer
     * straight back to {@link Slots}. What the method <em>returns</em> is not a decline, whatever it is: the
     * value {@code NotImplemented} from a {@code __getitem__} is the value, and only the comparison-running
     * protocols read it as a refusal.
     *
     * @return the result, or {@link Slots#DECLINED} when no class in the order declares the name as a
     *     function
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
     *
     * <p>A class may file something other than a function of its own under a dunder. A wrapper
     * descriptor taken from a built-in — {@code __neg__ = dict.copy}, {@code __eq__ = str.__eq__} — and a
     * classmethod are both members CPython reaches through {@code tp_descr_get} and then calls, so the
     * member is bound to this value first; a descriptor of the wrong kind of value refuses here with the
     * sentence its built-in wrote. A member that only binds is declined, which keeps
     * {@code __hash__ = None} saying "not callable" rather than "absent".
     */
    static PyObject invoke(PyObject self, String name, PyObject[] arguments, PyTuple kwNames) {
        PyObject declared = lookUp(self.getType(), name);
        if (declared instanceof PyFunction method) {
            PyObject[] all = new PyObject[arguments.length + 1];
            all[0] = self;
            System.arraycopy(arguments, 0, all, 1, arguments.length);
            return method.call(all, kwNames);
        }
        if (declared != null && Descriptors.binds(declared)) {
            return Slots.call(
                    Descriptors.get(declared, self, self.getType()), arguments, kwNames);
        }
        return Slots.DECLINED;
    }

    /**
     * {@code lookup_maybe_method} followed by the call, which is what {@code slot_tp_richcompare} does.
     *
     * <p>The difference from {@link #invoke} is one clause of {@code lookup_method_ex}: with {@code
     * raise_attribute_error} clear, a descriptor that refuses to bind with {@code AttributeError} is
     * treated as though the class had never filed the name, and the refusal is cleared rather than
     * propagated. A class that files {@code __eq__ = property(booh)} therefore compares by identity and
     * never raises, while an {@code AttributeError} raised by the method <em>body</em> is still its own.
     */
    static PyObject invokeMaybe(PyObject self, String name, PyObject... arguments) {
        PyObject declared = lookUp(self.getType(), name);
        if (declared instanceof PyFunction method) {
            PyObject[] all = new PyObject[arguments.length + 1];
            all[0] = self;
            System.arraycopy(arguments, 0, all, 1, arguments.length);
            return method.call(all, null);
        }
        if (declared != null && Descriptors.binds(declared)) {
            PyObject bound;
            try {
                bound = Descriptors.get(declared, self, self.getType());
            } catch (PyRaise refused) {
                if (refused.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                    return Slots.DECLINED;
                }
                throw refused;
            }
            return Slots.call(bound, arguments, null);
        }
        return Slots.DECLINED;
    }
}
