package org.python.core;

/**
 * {@code X.__dict__} — the read-only window a type's dictionary opens onto itself.
 *
 * <p>CPython makes one of these every time the name is read and never keeps it: {@code int.__dict__ is
 * int.__dict__} is false, and the two answers compare equal because {@code mappingproxy_richcompare} hands
 * the <em>mapping</em> to the compare rather than the window — two windows over the one dictionary are two
 * ways of reading the same contents, and so, less obviously, are two windows over two dictionaries that
 * happen to hold the same names. That single decision explains most of what is surprising about the type.
 * It is why the window is live — {@code C.x = 1} puts the name in the one dictionary every window shows —
 * and why a window is not a dict: {@code isinstance(p, dict)} is false, {@code p} cannot be a key, and the
 * two mutators a dict has are answered for in the container's own words because {@code mappingproxy} has
 * no {@code mp_ass_subscript} at all.
 *
 * <p>The half that is not a window is the arithmetic. Every binary and ordering operation hands the
 * <em>mapping</em> to CPython instead of itself, which is why {@code p | {'x': 1}} answers a {@code dict}
 * and {@code p < p} complains about two dicts with {@code '>'} — the reflected leg of the comparison runs
 * on the other window's mapping with the operator swapped, and it is that inner refusal whose wording
 * escapes. A key that will not hash is refused in the dict's own words for the same reason.
 *
 * <p>Two of CPython's answers are not modelled. The two iterators a window offers are {@code
 * dict_keyiterator} and {@code dict_reversekeyiterator} there and an ordinary {@code iterator} here, which
 * is what {@code type(iter(p)).__name__} shows; and {@code p[int]} declines to answer a generic alias
 * because this runtime has no {@code types.GenericAlias}.
 */
final class PyMappingProxy extends PyObject {

    /** The one dictionary every window over this type's members shares. */
    private final PyDict held;

    private PyMappingProxy(PyDict held) {
        super(PyType.MAPPING_PROXY);
        this.held = held;
    }

    /** {@code X.__dict__} for the dictionary {@code X} keeps. */
    static PyMappingProxy of(PyDict held) {
        return new PyMappingProxy(held);
    }

    /** The mapping underneath, which is what a comparison of two windows is really about. */
    PyDict mapping() {
        return held;
    }

    /** The entry under {@code key}, for {@code X.__dict__[name]} at Java level. */
    PyObject at(PyObject key) {
        return held.getItem(key);
    }

    // --- the mapping protocol ------------------------------------------------------------

    @Override
    public PyObject length() {
        return held.length();
    }

    @Override
    public PyObject getItem(PyObject key) {
        return held.getItem(key);
    }

    @Override
    public PyObject contains(PyObject key) {
        return held.contains(key);
    }

    @Override
    public PyObject iterator() {
        return held.iterator();
    }

    /**
     * {@code iter(p)} and {@code reversed(p)} at Java level, in the order the dictionary was written.
     *
     * <p>Both follow the mapping's insertion order rather than the sorted order {@code dir()} takes, which
     * is what makes {@code list(int.__dict__)[:3]} the three names CPython's {@code tp_dict} happened to
     * fill first.
     */
    PyIterator keysIterator() {
        return held.keyIterator();
    }

    /** {@code p.__reversed__()}: the keys from the last one written back to the first. */
    PyIterator reversedKeysIterator() {
        PyObject[] keys = held.keys();
        PyObject[] backwards = new PyObject[keys.length];
        for (int at = 0; at < keys.length; at++) {
            backwards[at] = keys[keys.length - 1 - at];
        }
        return PyIterator.of(backwards);
    }

    /**
     * {@code dict(p)}, {@code {**p}} and {@code p.copy()}: the mapping, as a dict of its own.
     *
     * <p>{@code p[key] = value} and {@code del p[key]} are answered nowhere in this class: {@code
     * mappingproxy} has no {@code mp_ass_subscript} at all, so CPython's {@code PyObject_SetItem} falls
     * through to the slot's absence and complains in the container's words — {@code 'mappingproxy' object
     * does not support item assignment}, the same sentence a plain class without {@code __setitem__} gets.
     * {@code p.clear()} is a different refusal, an {@code AttributeError} for a name the dictionary never
     * filed.
     */
    PyDict copied() {
        return held.copy();
    }

    // --- arithmetic and comparison -------------------------------------------------------

    /**
     * {@code p | q} and {@code q | p}, both of which answer a plain dict.
     *
     * <p>The window is not the operand CPython uses: {@code dictproxy_or} hands the mapping over, so the
     * result is a {@code dict} and a refusal names {@code 'dict'} where a person reading the traceback
     * would expect {@code 'mappingproxy'}.
     */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op != BinaryOp.OR) {
            return PyNotImplemented.INSTANCE;
        }
        PyObject right = unwrap(other);
        if (!(right instanceof PyDict merged)) {
            throw Slots.typeError(
                    "unsupported operand type(s) for |: 'dict' and '%s'",
                    other.getType().filedName());
        }
        return joined(held, merged);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        if (op != BinaryOp.OR) {
            return PyNotImplemented.INSTANCE;
        }
        // `{'x': 1} | p`: the left operand's own `__or__` declined the window, so the merge starts there
        // and the mapping underneath this one overrules it, key for key.
        PyObject left = unwrap(other);
        if (!(left instanceof PyDict)) {
            throw Slots.typeError(
                    "unsupported operand type(s) for |: '%s' and 'dict'",
                    other.getType().filedName());
        }
        return joined(left, held);
    }

    /**
     * {@code p |= q}: the one in-place operator CPython names in its own refusal.
     *
     * <p>{@code nb_inplace_or} is a real slot that only refuses, so the augmented assignment reaches it as
     * the same {@code OR} the plain one does and {@code p | q} still answers a dict.
     */
    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.OR) {
            throw Slots.typeError("'|=' is not supported by mappingproxy; use '|' instead");
        }
        return PyNotImplemented.INSTANCE;
    }

    /** The mapping a window stands for, and the window's caller's own operand untouched. */
    private static PyObject unwrap(PyObject value) {
        return value instanceof PyMappingProxy window ? window.held : value;
    }

    private static PyObject joined(PyObject left, PyObject right) {
        PyDict merged = ((PyDict) left).copy();
        merged.putAll((PyDict) right);
        return merged;
    }

    /**
     * {@code p == q}, {@code p != q} and the four orderings.
     *
     * <p>{@code mappingproxy_richcompare} hands the <em>mapping</em> to CPython and leaves the other
     * operand as it arrived, so a window compared with a window is declined by the first dictionary and
     * answered by the second leg — the other window's mapping against this one's, which is a compare of
     * two dictionaries. Two windows are therefore equal when their contents agree, not when they are
     * windows over the one dictionary; the ordering legs keep the operator swap that same walk makes, and
     * it is that swap whose wording escapes: {@code p < p} complains about {@code '>'} and two dicts.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (other instanceof PyMappingProxy window) {
            if (cmp == CompareOp.EQ || cmp == CompareOp.NE) {
                boolean equal = held.eq(window.held);
                return PyBool.of(cmp == CompareOp.EQ ? equal : !equal);
            }
            // The leg CPython's reflected call runs: the other window's mapping, with the operator
            // swapped, over this one's.
            return Slots.compare(cmp.reflected(), held, window.held);
        }
        return Slots.compare(cmp, held, other);
    }

    /**
     * Python-level equality, which a dict or set consults.
     *
     * <p>A window is never a key — {@link #hash} refuses — but it can be a <em>value</em>, and {@code {x:
     * p} == {x: p}} reaches {@code PyObject_RichCompareBool} on the two values, which unwraps to the
     * mappings underneath and compares them as dictionaries do.
     */
    @Override
    public boolean eq(PyObject other) {
        if (other == this) {
            return true;
        }
        return other instanceof PyMappingProxy window
                ? held.eq(window.held)
                : other instanceof PyDict dict && held.eq(dict);
    }

    /** {@code hash(p)}: refused in the dict's words, because the mapping is what was hashed. */
    @Override
    public long hash() {
        return held.hash();
    }

    // --- display and members ---------------------------------------------------------------

    @Override
    public String repr() {
        return "mappingproxy(" + held.repr() + ")";
    }

    /** {@code str(p)}: the mapping's own repr, which is why it does not agree with {@link #repr()}. */
    @Override
    public String str() {
        return held.repr();
    }

    /**
     * {@code p.get}, {@code p.keys}, {@code p.update}.
     *
     * <p>Read through the type's dictionary, which is the only place these names exist: a window has no
     * {@code __dict__} of its own, and a name the dictionary does not hold is refused with {@code
     * mappingproxy} named as the owner — the same sentence CPython uses for the mutators the type lacks.
     */
    @Override
    public PyObject getAttr(String attribute) {
        return TypeDict.read(this, attribute);
    }
}
