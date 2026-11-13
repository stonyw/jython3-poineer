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
 * <p>What the window holds is the mapping it was handed, not its entries: a subclass of {@code dict} keeps
 * its own {@code __getitem__}, {@code __missing__}, {@code __contains__}, {@code __iter__}, {@code __len__}
 * and {@code get}, and every read of the window is a call to one of those. That is why {@code
 * mappingproxy(custom)['key']} answers {@code 'getitem=value'} even though the entries underneath are
 * ordinary, and why a {@code ChainMap} can be wrapped at all. The one shortcut is for a plain dictionary,
 * where the entries are the mapping.
 *
 * <p>The half that is not a window is the arithmetic. Every binary and ordering operation hands the
 * <em>mapping</em> to CPython instead of itself, which is why {@code p | {'x': 1}} answers a {@code dict}
 * and {@code p < p} complains about two dicts with {@code '>'} — the reflected leg of the comparison runs
 * on the other window's mapping with the operator swapped, and it is that inner refusal whose wording
 * escapes. A key that will not hash is refused in the dict's own words for the same reason.
 */
final class PyMappingProxy extends PyObject {

    /** The mapping the window stands for, exactly as it was handed over. */
    private final PyObject held;

    /** The same value, when the mapping is a plain dictionary: the paths that read entries directly. */
    private final PyDict dictionary;

    private PyMappingProxy(PyObject held) {
        super(PyType.MAPPING_PROXY);
        this.held = held;
        this.dictionary = held instanceof PyDict dict ? dict : null;
    }

    /** {@code X.__dict__} for the dictionary {@code X} keeps. */
    static PyMappingProxy of(PyDict held) {
        return new PyMappingProxy(held);
    }

    /** {@code mappingproxy(mapping)} for any mapping the constructor accepted. */
    static PyMappingProxy ofMapping(PyObject held) {
        return new PyMappingProxy(held);
    }

    /** The mapping underneath, which is what every slot and every comparison is really about. */
    PyObject held() {
        return held;
    }

    /**
     * The dictionary the window stands for, when the mapping is one; {@code null} otherwise.
     *
     * <p>This is the typed read the tests over {@code X.__dict__} take — a type's dictionary is always a
     * plain {@code dict} — while the slots read {@link #held()} so that a {@code ChainMap} or a subclass
     * is not mistaken for its entries.
     */
    PyDict mapping() {
        return dictionary;
    }

    /** The entries, for a window whose mapping is a plain dictionary; {@code null} otherwise. */
    PyDict dictionary() {
        return dictionary;
    }

    // --- the mapping protocol ------------------------------------------------------------

    @Override
    public PyObject length() {
        return dictionary != null ? dictionary.length() : Slots.length(held);
    }

    @Override
    public PyObject getItem(PyObject key) {
        return dictionary != null ? dictionary.getItem(key) : Slots.getItem(held, key);
    }

    @Override
    public PyObject contains(PyObject key) {
        return dictionary != null
                ? dictionary.contains(key)
                : PyBool.of(Slots.contains(held, key));
    }

    @Override
    public PyObject iterator() {
        return dictionary != null ? dictionary.iterator() : Slots.iterator(held);
    }

    /** The keys walk, for a caller that wants the iterator itself; the same one {@code __iter__} gives. */
    PyIterator keysIterator() {
        return (PyIterator) iterator();
    }

    /** {@code reversed(p)}: the mapping's own {@code __reversed__}, called as CPython calls it. */
    PyIterator reversedKeysIterator() {
        return (PyIterator) Slots.call(Slots.getAttr(held, "__reversed__"), new PyObject[0], null);
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
        return dictionary.copy();
    }

    // --- arithmetic and comparison -------------------------------------------------------

    /**
     * {@code p | q}: the mapping's own bar, with the window's operand left as it arrived.
     *
     * <p>{@code mappingproxy_or} is {@code PyNumber_Or(mapping, other)}, so a string window would complain
     * about {@code 'str' and 'int'} and a window over a dict answers a dict. Leaving the other window
     * wrapped is deliberate: its {@code __ror__} is what makes {@code p | q} a dict-to-dict merge.
     */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.OR ? Slots.binary(BinaryOp.OR, held, other) : PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.OR ? Slots.binary(BinaryOp.OR, other, held) : PyNotImplemented.INSTANCE;
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

    /**
     * {@code p == q}, {@code p != q} and the four orderings.
     *
     * <p>{@code mappingproxy_richcompare} hands the <em>mapping</em> to CPython and leaves the other
     * operand as it arrived, so a window compared with a window is declined by the first mapping and
     * answered by the second leg — the other window's mapping against this one's, with the operator
     * swapped for the orderings. Two windows are therefore equal when their mappings are equal, not when
     * they are windows over the one dictionary; it is that swap whose wording escapes: {@code p < p}
     * complains about {@code '>'} and two dicts.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
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
        return Slots.compareTrue(CompareOp.EQ, held, other);
    }

    /** {@code hash(p)}: refused in the mapping's words, because the mapping is what was hashed. */
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
