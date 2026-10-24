package org.python.core;

/**
 * {@code PyObject_GenericSetAttr} and {@code PyObject_GenericDelAttr}: the write a value gets when nobody
 * else takes it.
 *
 * <p>This is the algorithm every type inherits and the one {@code object.__setattr__} answers with, so it
 * is the only place the four questions an assignment can raise are asked: does the class file the name, can
 * what it files write, does a value of this class keep a dictionary of its own, and — for a delete — is the
 * name actually in it. {@link PyInstance} reaches it after a {@code __setattr__} the class declared has
 * declined the name, and {@link Slots} reaches it for the built-in values that declare no attribute writing
 * at all, which is what makes {@code int.__doc__}, {@code e.args} and {@code t.__weakref__} refuse a write
 * in the words of the descriptor filed for them rather than in the words of a missing attribute.
 *
 * <p>The order matters twice over. A member found on the class is asked <em>before</em> the dictionary, and
 * asked outright when it can write, because {@code tp_descr_set} is read off the member's own type — which
 * is why a {@code getset_descriptor} with no setter still takes the name away from the dictionary and
 * complains, and why a plain class variable lets the write land beside it. And the dictionary is only
 * written when the class really stores one: CPython checks {@code tp_dictoffset}, and the answer here is
 * {@link PyType#hasValueDict()}, so a value of a class with {@code __slots__} and no {@code __dict__} among
 * them is refused by name rather than quietly gaining an attribute.
 */
final class GenericAttributes {

    private GenericAttributes() {
    }

    /** {@code obj.name = value}. */
    static void write(PyObject obj, String name, PyObject value) {
        apply(obj, name, value);
    }

    /**
     * {@code del obj.name}, which CPython runs through the same function with {@code NULL} handed over
     * rather than through a slot of its own.
     */
    static void delete(PyObject obj, String name) {
        apply(obj, name, null);
    }

    private static void apply(PyObject obj, String name, PyObject value) {
        PyType kind = obj.getType();
        boolean deleting = value == null;
        PyObject member = Specials.member(kind, name);
        if (member != null && Descriptors.writes(member)) {
            if (deleting) {
                Descriptors.delete(member, obj);
            } else {
                Descriptors.set(member, obj, value);
            }
            return;
        }
        if (kind.hasValueDict()) {
            PyDict store = obj.attributeStore();
            if (store != null) {
                PyStr key = PyStr.interned(name);
                if (deleting) {
                    if (store.get(key) == null) {
                        throw Slots.attributeError(
                                "'%s' object has no attribute '%s'", kind.filedName(), name);
                    }
                    store.remove(key);
                } else {
                    store.put(key, value);
                }
                return;
            }
        }
        // Two refusals, and which one the name gets depends on the class rather than on the value: a
        // member the lookup reached but that cannot write is called read-only, and a name nothing on the
        // class owns is the one that has nowhere to go. Only the second carries the name and the value on
        // the error, because CPython reaches `_PyObject_SetAttributeErrorContext` from inside its own
        // `descr == NULL` branch and leaves the read-only one without.
        if (member != null) {
            throw Slots.attributeError(
                    "'%s' object attribute '%s' is read-only", kind.filedName(), name);
        }
        // And the second branch chooses its sentence by asking whether the write came through `tp_setattro`
        // at all: `_PyObject_GenericSetAttrWithDict` compares that slot with itself, so a class that
        // declares `__setattr__` or `__delattr__` — whose slot is `slot_tp_setattro` — names the missing
        // attribute plainly, and only a class whose write *is* the generic one adds that there is no
        // dictionary to put it in.
        if (declaresAttributeWrite(kind)) {
            throw Slots.failedRead(
                    obj, name, "'%s' object has no attribute '%s'", kind.filedName(), name);
        }
        throw Slots.failedRead(
                obj,
                name,
                "'%s' object has no attribute '%s' and no __dict__ for setting new attributes",
                kind.filedName(),
                name);
    }

    /** Whether {@code kind} has a {@code tp_setattro} of its own, which is to say a declared write. */
    private static boolean declaresAttributeWrite(PyType kind) {
        return Specials.lookUp(kind, "__setattr__") != null
                || Specials.lookUp(kind, "__delattr__") != null;
    }
}
