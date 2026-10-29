package org.python.core;

/**
 * The descriptor protocol: an attribute that answers for itself.
 *
 * <p>CPython keeps this as two slots on a type — {@code tp_descr_get} and {@code tp_descr_set} — filled
 * from the Python names {@code __get__}, {@code __set__} and {@code __delete__} when a class declares
 * them. Here the same question is asked of a value rather than of a type, and in one place, because the
 * answers come from three different kinds of member: a function Java wrote, a function Python wrote,
 * and one of the built-in descriptor objects {@code property}, {@code classmethod} and
 * {@code staticmethod} are. A value of either of the first two families says so by implementing
 * {@link Protocol}; a class Python wrote says so by declaring the name.
 *
 * <p>The two orders the protocol is defined by are both here. A descriptor that can write
 * ({@link #writes}) is asked before the instance's own dictionary is consulted; one that can only read
 * is asked only when that dictionary has nothing, which is what lets {@code a.quiet = "mine"} shadow an
 * attribute that only reads. {@code property} counts as a writer even with no setter, because CPython
 * gives it a {@code tp_descr_set} whatever it holds — and that is why assigning to a read-only property
 * complains instead of quietly succeeding.
 */
final class Descriptors {

    /**
     * A member written in Java that answers for itself when Python reads or writes it.
     *
     * <p>{@code obj} is null for an attribute read off the class rather than off one of its values,
     * which is the difference between {@code Point.as_text} and {@code p.as_text}. {@code owner} is the
     * class the attribute was reached through, or whatever {@code __get__}'s second argument said —
     * CPython passes that pointer through without looking at it, and only {@code classmethod} uses it
     * for anything beyond telling a class access from a value one.
     */
    interface Protocol {
        PyObject asAttribute(PyObject obj, PyObject owner);

        /** Whether writing the name reaches this object rather than the instance dictionary. */
        default boolean writes() {
            return false;
        }

        /**
         * Whether this object is a descriptor at all, which is what {@link #binds} asks and what
         * CPython answers from the type's {@code tp_descr_get} slot.
         *
         * <p>Every one of them answers true, {@code staticmethod} included: its getter hands back the
         * plain function and so takes the receiver away just as surely as a binding one does.
         */
        default boolean binds() {
            return true;
        }

        /**
         * {@code obj.name = value}, or {@code del obj.name} when {@code value} is null.
         *
         * <p>Only asked when {@link #writes()} said the descriptor owns the name. A Java-written
         * descriptor that owns a name but has no writing half is refused exactly as CPython refuses a
         * Python class that declares only {@code __delete__}: {@code AttributeError: __set__}.
         */
        default void write(PyObject obj, PyObject value) {
            throw Slots.attributeError(value == null ? "__delete__" : "__set__");
        }
    }

    private Descriptors() {
    }

    /** Whether this attribute outranks the instance dictionary, which is what makes it a data one. */
    static boolean writes(PyObject value) {
        if (value instanceof Protocol protocol) {
            return protocol.writes();
        }
        return declared(value, "__set__") != null || declared(value, "__delete__") != null;
    }

    /** Whether reading this member as an attribute asks it to answer for itself: {@code tp_descr_get}. */
    static boolean binds(PyObject value) {
        if (value instanceof Protocol protocol) {
            return protocol.binds();
        }
        return declared(value, "__get__") != null;
    }

    /** {@code value.__get__(obj, owner)}, or the value itself when it is not a descriptor at all. */
    static PyObject get(PyObject value, PyObject obj, PyObject owner) {
        PyObject written = owner == null && obj != null ? obj.getType() : owner;
        if (value instanceof Protocol protocol) {
            return protocol.asAttribute(obj, written);
        }
        PyFunction method = declared(value, "__get__");
        return method == null
                ? value
                : method.call(
                        new PyObject[] {
                            value, obj == null ? PyNone.INSTANCE : obj, written
                        },
                        null);
    }

    /**
     * {@code value.__set__(obj, item)}, for a descriptor that owns the name.
     *
     * <p>A descriptor with only a deleting half still outranks the instance dictionary — that is what
     * {@link #writes} asks — and CPython's complaint about writing through it names the missing slot and
     * nothing else: {@code AttributeError: __set__}.
     */
    static void set(PyObject value, PyObject obj, PyObject item) {
        if (value instanceof Protocol protocol) {
            protocol.write(obj, item);
            return;
        }
        PyFunction method = declared(value, "__set__");
        if (method == null) {
            throw Slots.attributeError("__set__");
        }
        method.call(new PyObject[] {value, obj, item}, null);
    }

    /**
     * {@code value.__delete__(obj)}, which CPython reaches through the same {@code tp_descr_set} as a
     * write and so refuses the same way when the descriptor has no deleting half at all.
     */
    static void delete(PyObject value, PyObject obj) {
        if (value instanceof Protocol protocol) {
            protocol.write(obj, null);
            return;
        }
        PyFunction method = declared(value, "__delete__");
        if (method == null) {
            throw Slots.attributeError("__delete__");
        }
        method.call(new PyObject[] {value, obj}, null);
    }

    /**
     * {@code __set_name__}, asked of the class system as a member is placed in a class.
     *
     * <p>CPython looks the name up on the value's type rather than on the value, so an instance
     * dictionary that holds one does not get it called, and it hands over the bound form: the
     * descriptor is the first argument, exactly as a {@code self} would be.
     *
     * @return whether the value has anything to be told
     */
    static boolean setName(PyObject value, PyType owner, String name) {
        if (value instanceof PyProperty property) {
            property.setName(PyStr.of(name));
            return true;
        }
        PyFunction method = declared(value, "__set_name__");
        if (method == null) {
            return false;
        }
        method.call(new PyObject[] {value, owner, PyStr.of(name)}, null);
        return true;
    }

    /**
     * The {@code __get__} Python sees on one of the Java-written descriptors: {@code wrap_descr_get},
     * which unpacks one or two arguments, reads {@code None} as "not given" both ways round, and
     * refuses a call that names neither the value nor its class.
     *
     * <p>The target is asked the same question {@link #get} asks, so a descriptor written in Java answers
     * through {@link Protocol#asAttribute} and one written in Python through its own {@code __get__} —
     * which is what lets the slot wrapper a descriptor kind files for {@code __get__} hand the call to
     * whatever kind of descriptor it was read from.
     */
    static PyObject called(PyObject target, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("wrapper __get__() takes no keyword arguments");
        }
        Arguments.atLeast("__get__", args, kwNames, 1);
        Arguments.atMost("__get__", args, kwNames, 2);
        PyObject obj = args[0] == PyNone.INSTANCE ? null : args[0];
        PyObject owner = args.length > 1 && args[1] != PyNone.INSTANCE ? args[1] : null;
        if (obj == null && owner == null) {
            throw Slots.typeError("__get__(None, None) is invalid");
        }
        if (owner == null) {
            // CPython's substitution for a missing second argument: a class names itself, a value
            // names its type, and a descriptor that reads its owner is never handed a null class.
            owner = obj instanceof PyType ? obj : obj.getType();
        }
        return get(target, obj, owner);
    }

    /** The function {@code value}'s own type declares under {@code name}, which CPython reads as a slot. */
    private static PyFunction declared(PyObject value, String name) {
        return Specials.function(value.getType(), name);
    }
}
