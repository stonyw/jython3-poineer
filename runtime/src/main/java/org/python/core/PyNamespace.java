package org.python.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code types.SimpleNamespace}: a value whose whole state is a dictionary.
 *
 * <p>CPython's namespace type is an object with {@code tp_dictoffset} and nothing else in it —
 * {@code _PyNamespace_Type} — so every read, write and delete goes straight to that dictionary, and the
 * type answers it through {@code __dict__}. This class is that, because it is everything a program can
 * see: {@code vars(ns)} and {@code ns.__dict__} are the same object, and the pairs come back in the order
 * they were written, which is what {@code repr} prints between {@code namespace(} and {@code )}.
 *
 * <p>Three of CPython's answers are visible and copied here. The dictionary itself cannot be replaced —
 * {@code ns.__dict__ = {...}} is {@code AttributeError: readonly attribute} — values compare equal when
 * their types are the same and their dictionaries are, which makes two namespaces built the same way
 * compare the same, and hashing is refused by {@link PyObject}'s default, which is the
 * {@code unhashable type} the type declares by filing {@code __hash__ = None}.
 *
 * <p>The constructor is CPython's two halves: {@link #empty} is {@code namespace_new}, which only makes a
 * value of the class it is handed, and {@link #fill} is {@code namespace_init}, which reads one positional
 * argument the way {@code dict()} reads it and then the keywords. Keeping them apart is visible through
 * {@code SimpleNamespace.__new__(SimpleNamespace, x=1)}, which is empty: the slot alone is the first half.
 * A class derived from {@code SimpleNamespace} gets a {@link PyInstance} holding the dictionary rather
 * than a {@code PyNamespace} of its own — CPython has one C layout for the base type and gives a subclass
 * the managed dictionary it inherits.
 */
public final class PyNamespace extends PyObject {

    /** The names being printed on this thread, so that a namespace holding itself prints {@code ...}. */
    private static final ThreadLocal<Set<PyObject>> PRINTING =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    /** The dictionary that is the whole value. */
    private final PyDict contents;

    PyNamespace() {
        this(PyDict.empty());
    }

    private PyNamespace(PyDict contents) {
        super(PyType.NAMESPACE);
        this.contents = contents;
    }

    /**
     * {@code namespace_new}: a value of the class, with an empty dictionary and nothing read.
     *
     * <p>The base type is a {@code PyNamespace} of its own; a class derived from it is the
     * {@link PyInstance} that carries the managed dictionary it inherits.
     */
    static PyObject empty(PyType kind) {
        return kind == PyType.NAMESPACE ? new PyNamespace() : new PyInstance(kind);
    }

    /** Both halves of the constructor, which is what {@code SimpleNamespace(...)} runs. */
    static PyObject create(PyType kind, PyObject[] args, String[] names) {
        PyObject made = empty(kind);
        fill(made, args, names);
        return made;
    }

    /**
     * {@code namespace_init}: {@code SimpleNamespace(mapping_or_iterable=(), /, **kwargs)}.
     *
     * <p>The one positional argument goes through the dictionary merger, so a mapping copies and a
     * sequence of pairs is walked with {@code dict()}'s own complaints, and every key it produced has to
     * be a string — the check CPython applies to the whole of that dictionary before any of it lands. The
     * keywords come second, and an existing key is overwritten where it already sits, which is what keeps
     * {@code SimpleNamespace(d, x=1)} in the order of {@code d}.
     */
    static void fill(PyObject self, PyObject[] args, String[] names) {
        int keywords = names == null ? 0 : names.length;
        int positional = args.length - keywords;
        if (positional > 1) {
            // `PyArg_UnpackTuple` is handed `tp_name`, so the complaint names the class the call was
            // written with: `Spam(1, 2)` says `Spam`, while the base type says `SimpleNamespace`.
            throw Slots.typeError(
                    "%s expected at most 1 argument, got %d", self.getType().name(), positional);
        }
        if (positional == 1) {
            PyDict given = PyDict.empty();
            given.merge(args[0]);
            for (PyObject key : given.getItems().keySet()) {
                if (!(key instanceof PyStr)) {
                    throw Slots.typeError("keywords must be strings");
                }
            }
            contentsOf(self).putAll(given);
        }
        PyDict contents = contentsOf(self);
        for (int i = 0; i < keywords; i++) {
            contents.put(PyStr.interned(names[i]), args[positional + i]);
        }
    }

    /**
     * The dictionary either half of the namespace family keeps: this class's own for the base type, the
     * instance dictionary for a subclass. {@code __reduce__} and {@code __replace__} are shared by both.
     */
    static PyDict contentsOf(PyObject value) {
        return value instanceof PyNamespace namespace
                ? namespace.contents
                : ((PyInstance) value).ensureInstanceDict();
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
     * Equality as CPython's namespace type defines it: the same pairs, whatever class either side was
     * made from.
     *
     * <p>{@code namespace_richcompare} asks whether both sides are namespace instances, and a subclass is
     * one: {@code Spam(a=1) == SimpleNamespace(a=1)} is true, and so is two different subclasses with the
     * same pairs. A class that writes its own {@code __eq__} answers first through the ordinary slot; this
     * is the fallback the base type's slot supplies.
     */
    @Override
    public boolean eq(PyObject other) {
        return isNamespace(other) && sameContents(this, other);
    }

    /** Whether a value is the base type or a class written over it. */
    static boolean isNamespace(PyObject value) {
        return value instanceof PyNamespace || value.getType().matches(PyType.NAMESPACE);
    }

    /** Whether two namespace instances hold the same keys and values. */
    static boolean sameContents(PyObject left, PyObject right) {
        return contentsOf(left).getItems().equals(contentsOf(right).getItems());
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

    /**
     * {@code namespace(a=1, b='x')}, the pairs in the order they were written.
     *
     * <p>A class derived from the type says its own name instead — {@code Spam(a=1)} — because
     * {@code namespace_repr} reads {@code tp_name} for anything that is not the base type exactly.
     *
     * <p>{@code Py_ReprEnter} is the recursion guard CPython keeps: a namespace that holds itself, directly
     * or around a cycle, prints {@code namespace(...)} at the point it comes round again, which is why the
     * outer text ends {@code spam=namespace(...))}. A pair whose key is not a string, or is the empty
     * string, is skipped — the dictionary can be written through {@code __dict__} by hand, and CPython's
     * printer only prints the keys it could have been given as an argument.
     */
    @Override
    public String repr() {
        return display("namespace", contents, this);
    }

    /**
     * The printer both halves of the family use, since a subclass's pairs are in a
     * {@link PyInstance}'s dictionary rather than in a {@code PyNamespace}'s own.
     *
     * @param name what the value calls itself: {@code namespace} for the base type, the class's name for
     *             a subclass
     * @param self the value printed, whose identity the recursion guard remembers
     */
    static String display(String name, PyDict contents, PyObject self) {
        Set<PyObject> printing = PRINTING.get();
        if (!printing.add(self)) {
            return name + "(...)";
        }
        try {
            StringBuilder out = new StringBuilder(name).append('(');
            boolean first = true;
            for (Map.Entry<PyObject, PyObject> pair : contents.getItems().entrySet()) {
                if (!(pair.getKey() instanceof PyStr key) || key.getValue().isEmpty()) {
                    continue;
                }
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(key.getValue()).append('=').append(pair.getValue().repr());
            }
            return out.append(')').toString();
        } finally {
            printing.remove(self);
            if (printing.isEmpty()) {
                PRINTING.remove();
            }
        }
    }
}
