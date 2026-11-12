package org.python.core;

import java.util.Map;

/**
 * {@code types.SimpleNamespace}: a value whose whole state is a dictionary.
 *
 * <p>CPython's namespace type is an object with {@code tp_dictoffset} and nothing else in it —
 * {@code _PyNamespace_Type} — so every read, write and delete goes straight to that dictionary, and the
 * type answers it through {@code __dict__}. This class is that, because it is everything a program can
 * see: {@code vars(ns)} and {@code ns.__dict__} are the same object, and the pairs come back in the order
 * they were written, which is what {@code repr} prints between {@code namespace(} and {@code )}.
 *
 * <p>Two of CPython's answers are visible and copied here. The dictionary itself cannot be replaced —
 * {@code ns.__dict__ = {...}} is {@code AttributeError: readonly attribute} — and values compare equal
 * when their types are the same and their dictionaries are, which makes two namespaces built the same
 * way compare the same. Hashing is refused by {@link PyObject}'s default, which is the
 * {@code unhashable type} the type declares by filing {@code __hash__ = None}.
 *
 * <p>Only {@code sys.implementation} makes one of these so far. {@code types} is not assembled until M4,
 * so {@link PyType#NAMESPACE} is the only way to name the type, and nothing in a program can construct a
 * second namespace.
 */
public final class PyNamespace extends PyObject {

    /** The dictionary that is the whole value. */
    private final PyDict contents = PyDict.empty();

    PyNamespace() {
        super(PyType.NAMESPACE);
    }

    /** Files one pair, keeping the order of the writes for {@code repr} and for {@code vars}. */
    void set(String name, PyObject value) {
        contents.put(PyStr.interned(name), value);
    }

    /**
     * {@code ns.name}: the dictionary's answer, with {@code __dict__} naming the dictionary itself.
     *
     * <p>A missing name is handed back to {@link PyObject} so that the ordinary {@code AttributeError}
     * names the value's type, as CPython's generic lookup does.
     */
    @Override
    public PyObject getAttr(String name) {
        if (name.equals("__dict__")) {
            return contents;
        }
        PyObject value = contents.get(PyStr.interned(name));
        return value == null ? super.getAttr(name) : value;
    }

    /**
     * {@code ns.name = value}: a new pair in the dictionary, or the refusal about the dictionary itself.
     *
     * <p>{@code __dict__} is the name the type's getset answers and cannot be written, which is CPython's
     * {@code readonly attribute} rather than a missing one.
     */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        if (name.equals("__dict__")) {
            throw Slots.attributeError("readonly attribute");
        }
        contents.put(PyStr.interned(name), value);
        return PyNone.INSTANCE;
    }

    /** {@code del ns.name}: removes the pair, and names the type when there was none to remove. */
    @Override
    public PyObject delAttr(String name) {
        if (name.equals("__dict__")) {
            throw Slots.attributeError("readonly attribute");
        }
        if (contents.remove(PyStr.interned(name)) == null) {
            throw Slots.attributeError(
                    "'%s' object has no attribute '%s'", getType().filedName(), name);
        }
        return PyNone.INSTANCE;
    }

    /** The dictionary the generic attribute road would write through, for {@code vars} and friends. */
    @Override
    PyDict attributeStore() {
        return contents;
    }

    /**
     * Equality as CPython's namespace type defines it: same type, same dictionary.
     *
     * <p>The type check is part of it because {@code _PyNamespace_Type}'s {@code tp_richcompare} refuses
     * another type's dictionary rather than comparing as one.
     */
    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyNamespace that
                && getType() == that.getType()
                && contents.getItems().equals(that.contents.getItems());
    }

    /**
     * {@code hash(ns)}: the refusal the type declares by filing {@code __hash__ = None}.
     *
     * <p>Written out rather than left to {@link PyObject}'s default because that one names the type by
     * its short name, while CPython reads {@code tp_name} and so keeps the {@code types.} prefix.
     */
    @Override
    public long hash() {
        throw Slots.typeError("unhashable type: '%s'", getType().filedName());
    }

    /** {@code namespace(a=1, b='x')}, the pairs in the order they were written. */
    @Override
    public String repr() {
        StringBuilder out = new StringBuilder("namespace(");
        boolean first = true;
        for (Map.Entry<PyObject, PyObject> pair : contents.getItems().entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(((PyStr) pair.getKey()).getValue())
                    .append('=')
                    .append(pair.getValue().repr());
        }
        return out.append(')').toString();
    }
}
