package org.python.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python's {@code dict}: a mapping whose insertion order is part of the language.
 *
 * The map is a {@link LinkedHashMap} because that order is what iteration, {@code repr},
 * {@code list(d)} and {@code popitem} all show, and CPython's compact dict guarantees it.
 * {@code popitem} is the one that reads it backwards: it removes the last key inserted, not the
 * first.
 *
 * Keys are {@link PyObject}s, so a lookup goes through Python's {@code hash} and {@code eq} — which
 * is what makes {@code {1: 'a'}} findable by {@code True}, and what makes a list an unusable key.
 * CPython reports that second fact in the dict's own words, naming both the key it was handed and
 * the type inside it that refused, and {@link #unhashable} is what keeps that wording.
 */
public final class PyDict extends PyObject {

    private final Map<PyObject, PyObject> items;

    private PyDict(Map<PyObject, PyObject> items) {
        super(PyType.DICT);
        this.items = items;
    }

    public static PyDict empty() {
        return new PyDict(new LinkedHashMap<>());
    }

    /** The entry count for Java; {@link #length()} is the Python slot. */
    public int size() {
        return items.size();
    }

    public PyObject get(PyObject key) {
        try {
            return items.get(key);
        } catch (PyRaise refusal) {
            throw unhashable(key, refusal);
        }
    }

    public void put(PyObject key, PyObject value) {
        try {
            items.put(key, value);
        } catch (PyRaise refusal) {
            throw unhashable(key, refusal);
        }
    }

    /**
     * {@code del d[key]} and {@code dict.pop} at Java level: the value the key held, or null when
     * there is none. It does not raise, because the caller owns the message — {@link Slots#keyError}
     * for a deletion, a default for a pop.
     */
    public PyObject remove(PyObject key) {
        try {
            return items.remove(key);
        } catch (PyRaise refusal) {
            throw unhashable(key, refusal);
        }
    }

    public Map<PyObject, PyObject> getItems() {
        return items;
    }

    // --- slots -------------------------------------------------------------------------

    @Override
    public PyObject length() {
        return PyInt.of(items.size());
    }

    /** {@code d[key]}: a missing key is a {@code KeyError} carrying the key itself. */
    @Override
    public PyObject getItem(PyObject key) {
        PyObject value = get(key);
        if (value == null) {
            throw Slots.keyError(key);
        }
        return value;
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        put(key, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delItem(PyObject key) {
        if (remove(key) == null) {
            throw Slots.keyError(key);
        }
        return PyNone.INSTANCE;
    }

    /** {@code k in d} tests keys, as CPython's {@code sq_contains} does. */
    @Override
    public PyObject contains(PyObject key) {
        return PyBool.of(hasKey(key));
    }

    /** {@code k in d} at Java level, with the same refusal for a key that will not hash. */
    boolean hasKey(PyObject key) {
        try {
            return items.containsKey(key);
        } catch (PyRaise refusal) {
            throw unhashable(key, refusal);
        }
    }

    @Override
    public PyObject iterator() {
        return keyIterator();
    }

    /** {@code a | b}: a new dict, with {@code b}'s entry losing where both have the key. */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.OR) {
            if (!(other instanceof PyDict dict)) {
                // Declining is what lets `{1: 2} | 1` be an operand error rather than an iterable one.
                return PyNotImplemented.INSTANCE;
            }
            PyDict merged = copy();
            merged.putAll(dict);
            return merged;
        }
        return PyNotImplemented.INSTANCE;
    }

    /** {@code a |= b}: an in-place {@code update}, which accepts a mapping or a sequence of pairs. */
    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.OR) {
            merge(other);
            return this;
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject getAttr(String name) {
        return METHODS.bind(this, name);
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyDict d && items.equals(d.items);
    }

    /** Mutable, therefore unhashable; {@link #hashCode()} inherits the same refusal. */
    @Override
    public String repr() {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<PyObject, PyObject> entry : items.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(entry.getKey().repr()).append(": ").append(entry.getValue().repr());
        }
        return out.append('}').toString();
    }

    // --- iteration -----------------------------------------------------------------------

    /**
     * The steps of an iteration, as an array taken when the iterator is made. CPython reads the live
     * table by index and notices only a size change, and that is what {@link Walker} does too:
     * replacing a value is not something an iterator is allowed to complain about.
     */
    PyIterator keyIterator() {
        return new Walker(keys());
    }

    PyIterator valueIterator() {
        return new Walker(values());
    }

    /** Iterating a dict yields keys; {@code items()} yields the pairs, each as a fresh tuple. */
    PyIterator itemIterator() {
        return new Walker(pairs());
    }

    /** The keys, in insertion order. */
    PyObject[] keys() {
        return items.keySet().toArray(new PyObject[0]);
    }

    /** The values, in the order their keys were inserted. */
    PyObject[] values() {
        return items.values().toArray(new PyObject[0]);
    }

    /** Every pair as a two-element tuple, in insertion order. */
    PyObject[] pairs() {
        List<PyObject> out = new ArrayList<>(items.size());
        for (Map.Entry<PyObject, PyObject> entry : items.entrySet()) {
            out.add(PyTuple.of(entry.getKey(), entry.getValue()));
        }
        return out.toArray(new PyObject[0]);
    }

    private final class Walker extends PyIterator {

        private final PyObject[] itinerary;
        private final int expectedSize;
        private int at;

        Walker(PyObject[] itinerary) {
            this.itinerary = itinerary;
            this.expectedSize = items.size();
        }

        @Override
        public PyObject nextOrNull() {
            if (items.size() != expectedSize) {
                throw Slots.runtimeError("dictionary changed size during iteration");
            }
            return at >= itinerary.length ? null : itinerary[at++];
        }
    }

    // --- key checks ----------------------------------------------------------------------

    /**
     * CPython's wrapper around a key that will not hash. Both names in it are observable:
     * {@code {(1, [2]): 0}} says the key is a tuple and the unhashable value a list, because the
     * refusal came from inside the tuple.
     */
    private static PyRaise unhashable(PyObject key, PyRaise refusal) {
        if (!refusal.value().exceptionType().matches(ExceptionTypes.TYPE_ERROR)) {
            return refusal;
        }
        return Slots.typeError(
                "cannot use '%s' as a dict key (%s)", key.getType().name(), refusal.value().str());
    }

    /**
     * {@code dict.update} and {@code |=}: a mapping merges as it is, and anything else is read as a
     * sequence of pairs. Only a {@code dict} counts as a mapping here, because that is all
     * {@link Slots#isMapping} can tell before the class system gives a type its MAPPING flag.
     */
    void merge(PyObject source) {
        if (source instanceof PyDict dict) {
            putAll(dict);
            return;
        }
        PyIterator iterator = Slots.iterator(source);
        PyObject element;
        for (int position = 0; (element = iterator.nextOrNull()) != null; position++) {
            PyObject[] pair = pair(element, position);
            put(pair[0], pair[1]);
        }
    }

    /** One {@code (key, value)} element of an update sequence, with CPython's two refusals. */
    private static PyObject[] pair(PyObject element, int position) {
        if (!Slots.isIterable(element)) {
            throw Slots.typeError("object is not iterable");
        }
        PyIterator iterator = Slots.iterator(element);
        List<PyObject> parts = new ArrayList<>(2);
        PyObject part;
        while ((part = iterator.nextOrNull()) != null) {
            parts.add(part);
        }
        if (parts.size() != 2) {
            throw Slots.valueError(
                    "dictionary update sequence element #%d has length %d; 2 is required",
                    position, parts.size());
        }
        return parts.toArray(new PyObject[0]);
    }

    private void putAll(PyDict source) {
        for (Map.Entry<PyObject, PyObject> entry : source.items.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    private PyDict copy() {
        PyDict out = new PyDict(new LinkedHashMap<>());
        out.putAll(this);
        return out;
    }

    // --- methods -------------------------------------------------------------------------

    /** A legacy method's keyword refusal, which comes before any counting. */
    private static void noKeywords(String qualified, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s takes no keyword arguments", qualified);
        }
    }

    /** The method table, in CPython's order, with {@code fromkeys} alongside the rest. */
    private static final Members METHODS = Members.of(PyType.DICT)
            .add("fromkeys", (self, args, kwNames) -> {
                noKeywords("dict.fromkeys()", kwNames);
                Arguments.between("fromkeys", args, kwNames, 1, 2);
                // One value shared by every key, which CPython also does: fromkeys([1,2], []) keeps
                // two names for the same list.
                PyObject value = args.length > 1 ? args[1] : PyNone.INSTANCE;
                PyDict dict = empty();
                PyIterator keys = Slots.iterator(args[0]);
                PyObject key;
                while ((key = keys.nextOrNull()) != null) {
                    dict.put(key, value);
                }
                return dict;
            })
            .addNoArgument("copy", self -> ((PyDict) self).copy())
            .addNoArgument("clear", self -> {
                ((PyDict) self).items.clear();
                return PyNone.INSTANCE;
            })
            .add("get", (self, args, kwNames) -> {
                noKeywords("dict.get()", kwNames);
                Arguments.between("get", args, kwNames, 1, 2);
                PyObject found = ((PyDict) self).get(args[0]);
                return found != null
                        ? found
                        : args.length > 1 ? args[1] : PyNone.INSTANCE;
            })
            .addNoArgument("items", self -> DictViews.items((PyDict) self))
            .addNoArgument("keys", self -> DictViews.keys((PyDict) self))
            .add("pop", (self, args, kwNames) -> {
                noKeywords("dict.pop()", kwNames);
                Arguments.between("pop", args, kwNames, 1, 2);
                PyObject found = ((PyDict) self).remove(args[0]);
                if (found != null) {
                    return found;
                }
                if (args.length > 1) {
                    return args[1];
                }
                throw Slots.keyError(args[0]);
            })
            .addNoArgument("popitem", self -> {
                PyDict dict = (PyDict) self;
                if (dict.items.isEmpty()) {
                    throw Slots.keyError(PyStr.of("popitem(): dictionary is empty"));
                }
                PyObject[] keys = dict.keys();
                PyObject key = keys[keys.length - 1];
                return PyTuple.of(key, dict.remove(key));
            })
            .add("setdefault", (self, args, kwNames) -> {
                noKeywords("dict.setdefault()", kwNames);
                Arguments.between("setdefault", args, kwNames, 1, 2);
                PyDict dict = (PyDict) self;
                PyObject found = dict.get(args[0]);
                if (found != null) {
                    return found;
                }
                PyObject inserted = args.length > 1 ? args[1] : PyNone.INSTANCE;
                dict.put(args[0], inserted);
                return inserted;
            })
            .add("update", (self, args, kwNames) -> {
                PyDict dict = (PyDict) self;
                int keywords = kwNames == null ? 0 : kwNames.length;
                // Only the positional arguments count here: `update(d, a=1)` is one argument.
                int positional = args.length - keywords;
                if (positional > 1) {
                    throw Slots.typeError("update expected at most 1 argument, got %d", positional);
                }
                if (positional == 1) {
                    dict.merge(args[0]);
                }
                for (int i = 0; i < keywords; i++) {
                    dict.put(PyStr.interned(kwNames[i]), args[positional + i]);
                }
                return PyNone.INSTANCE;
            })
            .addNoArgument("values", self -> DictViews.values((PyDict) self));
}
