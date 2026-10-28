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
 * the type inside it that refused, and {@link #unhashable} is what keeps that wording. An empty
 * {@link LinkedHashMap} does not ask its key to hash at all, so {@link #hashKey} asks on its behalf, and
 * an unhashable key read from an empty dictionary is a refusal in CPython rather than a miss.
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
        hashKey(key);
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
        hashKey(key);
        try {
            return items.containsKey(key);
        } catch (PyRaise refusal) {
            throw unhashable(key, refusal);
        }
    }

    /**
     * The hash a lookup on an <em>empty</em> dictionary would have taken.
     *
     * <p>{@code dict_find} asks the key to hash before it knows whether there is anything to find, so an
     * unhashable key read from a dictionary with nothing in it is refused in the dict's own words rather
     * than reported as missing — and a key whose {@code __hash__} raises is heard raising. A
     * {@link LinkedHashMap} with no entries answers {@code get} and {@code containsKey} without touching its
     * key, so this asks once, and only here: a dictionary that has entries hashes through the map itself,
     * exactly once, as it always did.
     */
    private void hashKey(PyObject key) {
        if (!items.isEmpty()) {
            return;
        }
        try {
            key.hashCode();
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
        return Members.find(this, name);
    }

    /**
     * {@code dict_richcompare}: two dictionaries, and nothing else.
     *
     * <p>The first line of that function is a check on <em>both</em> operands, so a dictionary asked about a
     * list declines rather than answering false — {@code {1: 2}.__eq__([1])} is {@code NotImplemented}, and
     * the {@code ==} the interpreter runs falls back on identity only after {@code list.__eq__} declines as
     * well. The four orderings are declined too, even between two dictionaries: the refusal a person reads is
     * {@link Slots} naming the two types, not a sentence written here.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (!(other instanceof PyDict dict) || cmp != CompareOp.EQ && cmp != CompareOp.NE) {
            return PyNotImplemented.INSTANCE;
        }
        boolean equal = eq(dict);
        return PyBool.of(cmp == CompareOp.EQ ? equal : !equal);
    }

    @Override
    public boolean eq(PyObject other) {
        // A window over a dict compares as the dict underneath it, which is what makes `dict(p) == p` and
        // `{x: p} == {x: p}` answer rather than fall back to identity.
        PyObject compared = other instanceof PyMappingProxy window ? window.mapping() : other;
        return compared instanceof PyDict d && items.equals(d.items);
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

    /**
     * The keys from the last one written back to the first, which is what {@code reversed(d)} and {@code
     * d.__reversed__()} hand out.
     *
     * <p>The same snapshot and the same size check as the forward walk, read backwards: a dictionary that
     * changes while it is being walked backwards complains exactly as it does walking forwards.
     */
    PyIterator reverseKeyIterator() {
        PyObject[] keys = keys();
        PyObject[] backwards = new PyObject[keys.length];
        for (int i = 0; i < keys.length; i++) {
            backwards[i] = keys[keys.length - 1 - i];
        }
        return new Walker(backwards);
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
     * sequence of pairs. A {@code dict} and a {@link PyMappingProxy} count as mappings here, which is
     * CPython's {@code Py_TPFLAGS_MAPPING} answer for the two types that set it beside {@code dict} itself;
     * {@link Slots#isMapping} still cannot tell a class that declares {@code keys} from one that does not.
     */
    void merge(PyObject source) {
        if (source instanceof PyDict dict) {
            putAll(dict);
            return;
        }
        if (source instanceof PyMappingProxy window) {
            // `dict(X.__dict__)` and `{**X.__dict__}`: the window is not the mapping, and CPython reads
            // the mapping it stands for rather than the keys the window iterates.
            putAll(window.mapping());
            return;
        }
        PyIterator iterator = Slots.iterator(source);
        PyObject element;
        for (int position = 0; (element = iterator.nextOrNull()) != null; position++) {
            PyObject[] pair = pair(element, position);
            put(pair[0], pair[1]);
        }
    }

    /**
     * {@code dict_update_common}: the one argument {@code update} and {@code __init__} take, then the
     * keywords that follow it.
     *
     * <p>The same body serves both, and CPython's {@code dict_update} and {@code dict_init} are the same
     * function with a different name written into the complaint — {@code d.__init__({}, {})} says {@code
     * dict expected at most 1 argument, got 2} where {@code d.update({}, {})} says {@code update}. Only the
     * positional arguments count there: {@code update(d, a=1)} is one argument.
     */
    void mergeFrom(PyObject[] args, String[] kwNames, String who) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        if (positional > 1) {
            throw Slots.typeError("%s expected at most 1 argument, got %d", who, positional);
        }
        if (positional == 1) {
            merge(args[0]);
        }
        for (int i = 0; i < keywords; i++) {
            put(PyStr.interned(kwNames[i]), args[positional + i]);
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

    void putAll(PyDict source) {
        for (Map.Entry<PyObject, PyObject> entry : source.items.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    /** {@code dict.copy()}. */
    PyDict copy() {
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

    /**
     * The method table, in the order {@code mapp_methods} writes it.
     *
     * <p>The four names before the ones a person calls are the slots CPython declares as methods rather
     * than copying out of a protocol: {@code __getitem__} because {@code dict_subscript} answers a missing
     * key as well as a hit, {@code __contains__} because {@code sq_contains} is {@code PyDict_Contains} and
     * the mapping's own test wants the refusal wording, and the two a {@code reversed()} call and a
     * {@code sizeof} call ask for. {@code fromkeys} is the one {@code METH_CLASS} name here besides
     * {@code __class_getitem__}, so its dictionary entry is a {@code classmethod_descriptor} and its three
     * refusals are the descriptor's rather than the method's.
     */
    private static final Members METHODS = Members.of(PyType.DICT)
            .add(
                    "__getitem__",
                    (self, args, kwNames) -> {
                        Arguments.exactlyOne("dict.__getitem__()", args, kwNames);
                        return self.getItem(args[0]);
                    })
            .add(
                    "__contains__",
                    (self, args, kwNames) -> {
                        Arguments.exactlyOne("dict.__contains__()", args, kwNames);
                        return self.contains(args[0]);
                    })
            .add(
                    "__sizeof__",
                    (self, args, kwNames) -> {
                        Arguments.none("dict.__sizeof__()", args, kwNames);
                        return PyInt.of(0);
                    })
            .add(
                    "__reversed__",
                    (self, args, kwNames) -> {
                        Arguments.none("dict.__reversed__()", args, kwNames);
                        return ((PyDict) self).reverseKeyIterator();
                    })
            .add(
                    "get",
                    (self, args, kwNames) -> {
                        noKeywords("dict.get()", kwNames);
                        Arguments.between("get", args, kwNames, 1, 2);
                        PyObject found = ((PyDict) self).get(args[0]);
                        return found != null
                                ? found
                                : args.length > 1 ? args[1] : PyNone.INSTANCE;
                    })
            .addNoArgument("items", self -> DictViews.items((PyDict) self))
            .addNoArgument("keys", self -> DictViews.keys((PyDict) self))
            .add(
                    "pop",
                    (self, args, kwNames) -> {
                        noKeywords("dict.pop()", kwNames);
                        Arguments.between("pop", args, kwNames, 1, 2);
                        PyDict dict = (PyDict) self;
                        // `_PyDict_Pop` reads the entry count before it looks at the key, so an empty
                        // dictionary hands back the default — or a KeyError carrying the key — without
                        // ever hashing it.
                        if (dict.items.isEmpty()) {
                            if (args.length > 1) {
                                return args[1];
                            }
                            throw Slots.keyError(args[0]);
                        }
                        PyObject found = dict.remove(args[0]);
                        if (found != null) {
                            return found;
                        }
                        if (args.length > 1) {
                            return args[1];
                        }
                        throw Slots.keyError(args[0]);
                    })
            .addNoArgument(
                    "popitem",
                    self -> {
                        PyDict dict = (PyDict) self;
                        if (dict.items.isEmpty()) {
                            throw Slots.keyError(PyStr.of("popitem(): dictionary is empty"));
                        }
                        PyObject[] keys = dict.keys();
                        PyObject key = keys[keys.length - 1];
                        return PyTuple.of(key, dict.remove(key));
                    })
            .add(
                    "setdefault",
                    (self, args, kwNames) -> {
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
            .add(
                    "update",
                    (self, args, kwNames) -> {
                        ((PyDict) self).mergeFrom(args, kwNames, "update");
                        return PyNone.INSTANCE;
                    })
            .addNoArgument("values", self -> DictViews.values((PyDict) self))
            .addClassNamed(
                    "fromkeys",
                    (kind, args, kwNames) -> {
                        noKeywords("dict.fromkeys()", kwNames);
                        Arguments.between("fromkeys", args, kwNames, 1, 2);
                        // One value shared by every key, which CPython also does: fromkeys([1,2], [])
                        // keeps two names for the same list.
                        PyObject value = args.length > 1 ? args[1] : PyNone.INSTANCE;
                        PyDict dict = empty();
                        PyIterator keys = Slots.iterator(args[0]);
                        PyObject key;
                        while ((key = keys.nextOrNull()) != null) {
                            dict.put(key, value);
                        }
                        return dict;
                    })
            .addNoArgument(
                    "clear",
                    self -> {
                        ((PyDict) self).items.clear();
                        return PyNone.INSTANCE;
                    })
            .addNoArgument("copy", self -> ((PyDict) self).copy())
            // The fifteen sentences `mapp_methods` files (Objects/dictobject.c:4812), each the whole
            // `ml_doc`: thirteen read from `Objects/clinic/dictobject.c.h` through the `*_METHODDEF` macro
            // the table names, and two written by hand under their own `PyDoc_STRVAR` — `getitem__doc__`
            // with a table like clinic's, `update__doc__` without one. `update` is therefore the row this
            // runtime and the reference agree on for a different reason than any other: its text carries no
            // table, `find_signature` never starts, so `__doc__` keeps the whole sentence and
            // `__text_signature__` reads `None`. `fromkeys` is the table's one `$type` row, and `pop`'s
            // default is the second `<unrepresentable>` this family has had to transcribe.
            .document(
                    "__contains__",
                    "__contains__($self, key, /)\n"
                            + "--\n"
                            + "\n"
                            + "True if the dictionary has the specified key, else False.")
            .document(
                    "__getitem__",
                    "__getitem__($self, key, /)\n--\n\nReturn self[key].")
            .document(
                    "__sizeof__",
                    "__sizeof__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the size of the dict in memory, in bytes.")
            .document(
                    "get",
                    "get($self, key, default=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the value for key if key is in the dictionary, else default.")
            .document(
                    "setdefault",
                    "setdefault($self, key, default=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Insert key with a value of default if key is not in the dictionary.\n"
                            + "\n"
                            + "Return the value for key if key is in the dictionary, else default.")
            .document(
                    "pop",
                    "pop($self, key, default=<unrepresentable>, /)\n"
                            + "--\n"
                            + "\n"
                            + "D.pop(k[,d]) -> v, remove specified key and return the corresponding value.\n"
                            + "\n"
                            + "If the key is not found, return the default if given; otherwise,\n"
                            + "raise a KeyError.")
            .document(
                    "popitem",
                    "popitem($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove and return a (key, value) pair as a 2-tuple.\n"
                            + "\n"
                            + "Pairs are returned in LIFO (last-in, first-out) order.\n"
                            + "Raises KeyError if the dict is empty.")
            .document(
                    "keys",
                    "keys($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a set-like object providing a view on the dict's keys.")
            .document(
                    "items",
                    "items($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a set-like object providing a view on the dict's items.")
            .document(
                    "values",
                    "values($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return an object providing a view on the dict's values.")
            .document(
                    "update",
                    "D.update([E, ]**F) -> None.  Update D from mapping/iterable E and F.\n"
                            + "If E is present and has a .keys() method, then does:  for k in E.keys(): D[k] = E[k]\n"
                            + "If E is present and lacks a .keys() method, then does:  for k, v in E: D[k] = v\n"
                            + "In either case, this is followed by: for k in F:  D[k] = F[k]")
            .document(
                    "fromkeys",
                    "fromkeys($type, iterable, value=None, /)\n"
                            + "--\n"
                            + "\n"
                            + "Create a new dictionary with keys from iterable and values set to value.")
            .document(
                    "clear",
                    "clear($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove all items from the dict.")
            .document(
                    "copy",
                    "copy($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a shallow copy of the dict.")
            .document(
                    "__reversed__",
                    "__reversed__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a reverse iterator over the dict keys.");
}
