package org.python.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Python's {@code set}: the mutable collection of unique, hashable values.
 *
 * A set is a hash table, and that is what decides its rules. An element has to hash, so a list is
 * refused in the set's own words — {@code cannot use 'list' as a set element} — and so is a value
 * tested with {@code in}, which is looked up by hash. The exception CPython keeps is
 * {@link #intersection(PyObject)}: there an element is hashed only to be looked up, so the plain
 * {@code unhashable type} message survives, and the walk stops as soon as every element has been
 * matched, which is why {@code {1}.intersection([1, [2]])} answers instead of complaining.
 *
 * Iteration order is insertion order here, where CPython's is an artefact of a table whose seed is
 * random; it is not reproducible, so a set is compared by content and never by order, and
 * {@code pop} takes the first element where CPython takes one at an arbitrary position.
 */
public class PySet extends PyObject {

    final Set<PyObject> items;

    PySet(Set<PyObject> items, PyType type) {
        super(type);
        this.items = items;
    }

    public static PySet empty() {
        return new PySet(new LinkedHashSet<>(), PyType.SET);
    }

    public static PySet of(PyObject... items) {
        PySet set = empty();
        for (PyObject item : items) {
            set.add(item);
        }
        return set;
    }

    /** {@code set(iterable)} at Java level, which is how a view's reflected operator builds its result. */
    static PySet collected(PyObject iterable) {
        PySet set = empty();
        return iterable == null ? set : set.update(iterable);
    }

    /** The element count for Java; {@link #length()} is the Python slot. */
    public int size() {
        return items.size();
    }

    public void add(PyObject element) {
        insert(items, element);
    }

    public Set<PyObject> getItems() {
        return items;
    }

    // --- slots -------------------------------------------------------------------------

    @Override
    public PyObject length() {
        return PyInt.of(items.size());
    }

    /** {@code x in s}: a hash lookup, so a value that will not hash is refused, not missed. */
    @Override
    public PyObject contains(PyObject element) {
        return PyBool.of(lookedUp(items, element));
    }

    @Override
    public PyObject iterator() {
        return new Walker();
    }

    /**
     * Subset ordering, with {@code <} a <em>proper</em> subset, so {@code {1} < {1}} is false rather
     * than an error.
     *
     * <p>Every ordering declines a value that is not a set, {@code ==} and {@code !=} included, which is
     * what {@code set.__dict__['__eq__']} hands back for a list where {@code object}'s slot would have
     * answered the identity question. The operator then tries the reflected side, finds nothing there
     * either, and falls back on identity — so {@code {1} == [1]} is false while the slot reports that it
     * took no part in the decision.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (!(other instanceof PySet set)) {
            return PyNotImplemented.INSTANCE;
        }
        return switch (cmp) {
            case EQ -> PyBool.of(eq(set));
            case NE -> PyBool.of(!eq(set));
            case LE -> PyBool.of(subsetOf(set));
            case GE -> PyBool.of(set.subsetOf(this));
            case LT -> PyBool.of(items.size() < set.items.size() && subsetOf(set));
            case GT -> PyBool.of(items.size() > set.items.size() && set.subsetOf(this));
        };
    }

    /** {@code |}, {@code &}, {@code -}, {@code ^}: two sets only, and the left one's kind of set. */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (!(other instanceof PySet)) {
            return PyNotImplemented.INSTANCE;
        }
        return switch (op) {
            case OR -> detached().update(other);
            case SUB -> detached().differenceUpdate(other);
            case AND -> detached().intersectionUpdate(other);
            case XOR -> detached().symmetricDifferenceUpdate(other);
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * {@code s |= other} and the other three, which CPython's set slots also gate on the right
     * operand being a set — so {@code s |= [2]} is an operand error, not an iterable one — and which
     * return this same set.
     */
    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        if (!mutable() || !(other instanceof PySet)) {
            return PyNotImplemented.INSTANCE;
        }
        return switch (op) {
            case OR -> update(other);
            case SUB -> differenceUpdate(other);
            case AND -> intersectionUpdate(other);
            case XOR -> symmetricDifferenceUpdate(other);
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    /** Equality is content, both ways round a {@code set} and a {@code frozenset}. */
    @Override
    public boolean eq(PyObject other) {
        return other instanceof PySet set && items.equals(set.items);
    }

    /** Mutable, therefore unhashable; {@link #hashCode()} inherits the same refusal. */
    @Override
    public String repr() {
        return items.isEmpty() ? "set()" : "{" + contents() + "}";
    }

    /** Whether values of this kind change in place; a frozenset's are the only ones that do not. */
    protected boolean mutable() {
        return true;
    }

    /** An empty value of this same kind, because a {@code frozenset}'s operators give frozensets. */
    protected PySet emptyLike() {
        return new PySet(new LinkedHashSet<>(), PyType.SET);
    }

    // --- the operations the slots and the methods share -----------------------------------

    /** Every value of an iterable, added one by one: {@code s.update(other)}. */
    PySet update(PyObject other) {
        if (other == this) {
            // CPython's fast path for itself, which is also the only way this is safe to write.
            return this;
        }
        PyIterator iterator = Slots.iterator(other);
        PyObject element;
        while ((element = iterator.nextOrNull()) != null) {
            add(element);
        }
        return this;
    }

    /**
     * {@code set_init} over a value that already has contents: emptied, then filled from the argument.
     *
     * <p>The clear comes first and the argument is read after it, which is what makes {@code s.__init__(s)}
     * an empty set where {@code s.union(s)} holds everything it started with — and what makes a refused
     * {@code s.__init__(5)} leave an empty set behind rather than the one it was called on. Only a set
     * that had anything in it is emptied, because {@code self->fill} is the test; a fresh value keeps its
     * hash slot unset instead of being cleared, and nothing a program can see tells the two apart.
     */
    void initialized(PyObject iterable) {
        if (!items.isEmpty()) {
            items.clear();
        }
        if (iterable != null) {
            update(iterable);
        }
    }

    /** {@code s.difference_update(other)}: everything the other side holds, thrown away. */
    PySet differenceUpdate(PyObject other) {
        if (other == this) {
            items.clear();
            return this;
        }
        // The other side is filed into a set first, which is where the set's own message comes from
        // even for a value that only ever gets thrown away.
        for (PyObject element : collected(other).items) {
            items.remove(element);
        }
        return this;
    }

    /** {@code s.intersection_update(other)}: only what the other side holds is kept. */
    PySet intersectionUpdate(PyObject other) {
        PySet kept = intersection(other);
        items.clear();
        items.addAll(kept.items);
        return this;
    }

    /** {@code s.symmetric_difference_update(other)}: keep what differs, on either side. */
    PySet symmetricDifferenceUpdate(PyObject other) {
        if (other == this) {
            items.clear();
            return this;
        }
        // Materialised as a set first, so a repeated value cancels once rather than twice.
        for (PyObject element : collected(other).items) {
            if (!items.remove(element)) {
                items.add(element);
            }
        }
        return this;
    }

    /** The values this set and {@code other} share, as a new set of the same kind. */
    private PySet intersection(PyObject other) {
        PySet out = emptyLike();
        if (other == this) {
            out.items.addAll(items);
            return out;
        }
        if (other instanceof PySet set) {
            for (PyObject element : items) {
                if (set.items.contains(element)) {
                    out.items.add(element);
                }
            }
            return out;
        }
        PyIterator iterator = Slots.iterator(other);
        PyObject element;
        while ((element = iterator.nextOrNull()) != null && out.items.size() < items.size()) {
            // Looked up, not filed away: CPython hashes with its plain refusal here.
            if (items.contains(element)) {
                out.add(element);
            }
        }
        return out;
    }

    /** {@code s <= other}, the ordering test with CPython's size check first. */
    private boolean subsetOf(PySet other) {
        if (items.size() > other.items.size()) {
            return false;
        }
        for (PyObject element : items) {
            if (!other.items.contains(element)) {
                return false;
            }
        }
        return true;
    }

    /** {@code s.issubset(other)} for anything iterable, which is measured rather than tested. */
    private PyObject isSubset(PyObject other) {
        if (other instanceof PySet set) {
            return PyBool.of(subsetOf(set));
        }
        // CPython compares the sizes of the intersection and of the set, so an unusable value on the
        // other side is refused in the plain voice here.
        return PyBool.of(intersection(other).items.size() == items.size());
    }

    /** {@code s.issuperset(other)}: every value of the other side has to be in this set. */
    private PyObject isSuperset(PyObject other) {
        if (other instanceof PySet set) {
            return PyBool.of(set.subsetOf(this));
        }
        PyIterator iterator = Slots.iterator(other);
        PyObject element;
        while ((element = iterator.nextOrNull()) != null) {
            if (!holds(items, element)) {
                return PyBool.FALSE;
            }
        }
        return PyBool.TRUE;
    }

    /** {@code s.isdisjoint(other)}: CPython walks the shorter side when the other is a set. */
    private PyObject isDisjoint(PyObject other) {
        if (other == this) {
            return PyBool.of(items.isEmpty());
        }
        if (other instanceof PySet set) {
            return PyBool.of(!overlaps(set));
        }
        PyIterator iterator = Slots.iterator(other);
        PyObject element;
        while ((element = iterator.nextOrNull()) != null) {
            if (holds(items, element)) {
                return PyBool.FALSE;
            }
        }
        return PyBool.TRUE;
    }

    /**
     * Whether the two have anything in common, asked by walking whichever is shorter — the same
     * choice CPython makes, and one that changes nothing but the cost.
     */
    private boolean overlaps(PySet other) {
        PySet shorter = items.size() <= other.items.size() ? this : other;
        PySet longer = shorter == this ? other : this;
        for (PyObject element : shorter.items) {
            if (longer.items.contains(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code s.copy()}: the same values in a value of the same kind, which a mutable set has to be given.
     *
     * <p>A frozenset's version answers with the receiver itself, because nothing can be added to it and so
     * {@code f.copy() is f} — the one method of this family whose answer is not a fresh value.
     */
    PySet copy() {
        return detached();
    }

    /** The receiver's values, in a fresh value of the same kind: what every operator answers with. */
    private PySet detached() {
        PySet out = emptyLike();
        out.items.addAll(items);
        return out;
    }

    /** {@code s.discard(x)}: remove it, and say nothing when it was not there. */
    private void discard(PyObject element) {
        drop(items, element);
    }

    /** {@code s.remove(x)}: remove it, or raise {@code KeyError} carrying the value. */
    private void removed(PyObject element) {
        if (!drop(items, element)) {
            throw Slots.keyError(element);
        }
    }

    /** {@code s.pop()}: the first element, where CPython's table hands out an arbitrary one. */
    private PyObject popped() {
        if (items.isEmpty()) {
            throw Slots.keyError(PyStr.of("pop from an empty set"));
        }
        PyObject element = items.iterator().next();
        items.remove(element);
        return element;
    }

    /** {@code s.__reduce__()}: the type, the values as a list, and no state to restore. */
    private PyObject reduced() {
        return PyTuple.of(getType(), PyTuple.of(PyList.of(elements())), PyNone.INSTANCE);
    }

    /** The elements as an array, for a walk that must not see a mutation. */
    private PyObject[] elements() {
        return items.toArray(new PyObject[0]);
    }

    /** The joined elements, for a {@code repr} of either kind of set. */
    String contents() {
        List<String> shown = new ArrayList<>(items.size());
        for (PyObject element : items) {
            shown.add(element.repr());
        }
        return String.join(", ", shown);
    }

    // --- hashing's two voices --------------------------------------------------------------

    /**
     * CPython's refusal for a value a set cannot hold, wrapping the plain message and naming both the
     * value handed to the set and the type that refused — which is why {@code set([(1, [2])])} says
     * the key is a tuple and the offender a list.
     */
    private static PyRaise unhashable(PyObject element, PyRaise refusal) {
        if (!refusal.value().exceptionType().matches(ExceptionTypes.TYPE_ERROR)) {
            return refusal;
        }
        return Slots.typeError(
                "cannot use '%s' as a set element (%s)",
                element.getType().name(), refusal.value().str());
    }

    /** Filing a value away, which hashes it and so may refuse. */
    private static void insert(Set<PyObject> target, PyObject element) {
        try {
            target.add(element);
        } catch (PyRaise refusal) {
            throw unhashable(element, refusal);
        }
    }

    /**
     * A lookup by hash, with the set's own wording, as {@code isdisjoint} and {@code issuperset} ask it.
     *
     * <p>Those two walk the other side with {@code set_contains_key}, which wraps a refusal but asks the
     * question once — so a set handed to {@code isdisjoint} is refused even where {@code in} would have
     * found it, and {@code {frozenset({1})}.isdisjoint([{1}])} is an error rather than a {@code False}.
     */
    private static boolean holds(Set<PyObject> target, PyObject element) {
        asked(target, element, false);
        try {
            return target.contains(element);
        } catch (PyRaise refusal) {
            throw unhashable(element, refusal);
        }
    }

    /**
     * The same lookup as {@code set_contains_lock_held} makes, which is the one place a set is asked
     * twice.
     */
    private static boolean lookedUp(Set<PyObject> target, PyObject element) {
        asked(target, element, true);
        try {
            return target.contains(element);
        } catch (PyRaise refusal) {
            if (!hashesAsFrozenSet(element, refusal)) {
                throw unhashable(element, refusal);
            }
            return shares(target, element);
        }
    }

    /**
     * The hash a lookup takes before it knows whether there is anything to find.
     *
     * <p>{@code set_contains_key} asks the element to hash first, so an unhashable value tested against a
     * set with nothing in it is refused in the set's own words rather than reported as missing — and a
     * {@code __hash__} that raises is heard raising. A {@link LinkedHashSet} with no entries answers
     * {@code contains} without touching its key, so this asks when there is no map left to ask it, and
     * {@code retry} says whether the answer may be the second look a set gets: {@code isdisjoint} and
     * {@code issuperset} use the plain {@code set_contains_key}, which has none, while {@code in} wraps the
     * refusal and asks again as a frozenset. A removal needs neither: {@code remove} hashes its key even
     * with nothing to remove from.
     */
    private static void asked(Set<PyObject> target, PyObject element, boolean retry) {
        if (!target.isEmpty()) {
            return;
        }
        try {
            element.hash();
        } catch (PyRaise refusal) {
            if (!retry || !hashesAsFrozenSet(element, refusal)) {
                throw unhashable(element, refusal);
            }
        }
    }

    /** Removal by hash, refused in the same words as a lookup. */
    private static boolean drop(Set<PyObject> target, PyObject element) {
        try {
            return target.remove(element);
        } catch (PyRaise refusal) {
            if (!hashesAsFrozenSet(element, refusal)) {
                throw unhashable(element, refusal);
            }
            return throwOut(target, element);
        }
    }

    /**
     * The second look CPython takes at a set handed to a set: {@code {1} in {frozenset({1})}} is true, and
     * so is the same question asked of a {@code frozenset} made from a set.
     *
     * <p>A mutable set has no hash of its own, but its contents hash exactly as the frozenset of those
     * contents does, and that is the value an element of that shape was filed under. The retry is limited
     * on both sides: only a <em>set</em> is looked at this way — a list or a dictionary is refused outright —
     * and only when the refusal was a {@code TypeError}. {@code add} and {@code update} have no second look
     * at all, which is why the same set that answers {@code in} declines to be filed in one.
     */
    private static boolean hashesAsFrozenSet(PyObject element, PyRaise refusal) {
        return element instanceof PySet set
                && set.mutable()
                && refusal.value().exceptionType().matches(ExceptionTypes.TYPE_ERROR);
    }

    /** What the second look finds, which equality decides rather than the hash that was tried. */
    private static boolean shares(Set<PyObject> target, PyObject element) {
        for (PyObject held : target) {
            if (element.equals(held)) {
                return true;
            }
        }
        return false;
    }

    /** The second look's removal, which cannot ask the key for a hash any more than the look can. */
    private static boolean throwOut(Set<PyObject> target, PyObject element) {
        Iterator<PyObject> walk = target.iterator();
        while (walk.hasNext()) {
            if (element.equals(walk.next())) {
                walk.remove();
                return true;
            }
        }
        return false;
    }

    // --- iteration -----------------------------------------------------------------------

    /**
     * The steps over a set, which notices only that the set changed size, as CPython's iterator does,
     * and in its own capitalised message.
     */
    private final class Walker extends PyIterator {

        private final PyObject[] itinerary;
        private final int expectedSize;
        private int at;

        Walker() {
            this.itinerary = elements();
            this.expectedSize = items.size();
        }

        @Override
        public PyObject nextOrNull() {
            if (items.size() != expectedSize) {
                throw Slots.runtimeError("Set changed size during iteration");
            }
            return at >= itinerary.length ? null : itinerary[at++];
        }
    }

    // --- methods -------------------------------------------------------------------------

    /** A keyword refusal, in the same words for every method CPython declares with {@code METH_VARARGS}. */
    private static void noKeywords(String qualified, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s takes no keyword arguments", qualified);
        }
    }

    /** The body of a method taking any number of iterables. */
    private interface Many {
        PyObject apply(PySet self, PyObject[] others);
    }

    /**
     * A {@code METH_VARARGS} method: keywords refused, and nothing else counted.
     *
     * <p>{@code *others: array} in CPython's clinic is a method that never complains about a count, so
     * {@code s.union()} is a copy, {@code s.update()} changes nothing, and the only refusal a keyword
     * brings is the one written before the argument tuple is handed over.
     */
    private static PyBoundMethod.Body varargs(String qualified, Many body) {
        return (self, args, kwNames) -> {
            noKeywords(qualified, kwNames);
            return body.apply((PySet) self, args);
        };
    }

    /**
     * The two method tables, in the order {@code set_methods} and {@code frozenset_methods} write them.
     *
     * <p>{@code frozenset_methods} is that list with the eight names that change a value taken out of it,
     * and the two rows it declares for itself — {@code __contains__} and {@code copy} — say the same things
     * in the same words as the set's: CPython's clinic blocks for {@code frozenset.copy} and {@code set.copy}
     * carry one sentence, so a frozenset's {@code copy().__doc__} calls the copy a set. Every other shared
     * name is the same {@code SET_*_METHODDEF} in both lists, which is why the two types' refusals differ
     * only in the type they name.
     *
     * <p>Three of the rows are slots that CPython declares as methods. {@code __contains__} is marked
     * {@code @coexist}, so it keeps the {@code sq_contains} place in the dictionary that the sequence
     * protocol would otherwise have while reading as a {@code method_descriptor} and counting its argument
     * as a method does; {@code __reduce__} and {@code __sizeof__} are in no protocol at all. The four
     * operators are not here: a set's {@code &}, {@code |}, {@code -} and {@code ^} are filed as slot
     * wrappers by {@link TypeDict}, which is where the difference between {@code s.union(t)} and {@code s |
     * t} — one takes any iterable, the other only a set — is written down.
     */
    private static Members table(PyType type, boolean mutable) {
        String who = type.name();
        Members methods = Members.of(type);
        if (mutable) {
            methods
                    .addOne(
                            "add",
                            (self, element) -> {
                                ((PySet) self).add(element);
                                return PyNone.INSTANCE;
                            })
                    .addNoArgument(
                            "clear",
                            self -> {
                                ((PySet) self).items.clear();
                                return PyNone.INSTANCE;
                            });
        }
        methods
                .addOne("__contains__", (self, element) -> self.contains(element))
                .addNoArgument("copy", self -> ((PySet) self).copy());
        if (mutable) {
            methods.addOne(
                    "discard",
                    (self, element) -> {
                        ((PySet) self).discard(element);
                        return PyNone.INSTANCE;
                    });
        }
        methods
                .add(
                        "difference",
                        varargs(
                                who + ".difference()",
                                (self, others) -> {
                                    PySet out = self.detached();
                                    for (PyObject other : others) {
                                        out.differenceUpdate(other);
                                    }
                                    return out;
                                }))
                .add(
                        "intersection",
                        varargs(
                                who + ".intersection()",
                                (self, others) -> {
                                    PySet out = self.detached();
                                    for (PyObject other : others) {
                                        out.intersectionUpdate(other);
                                    }
                                    return out;
                                }))
                .addOne("isdisjoint", (self, other) -> ((PySet) self).isDisjoint(other))
                .addOne("issubset", (self, other) -> ((PySet) self).isSubset(other))
                .addOne("issuperset", (self, other) -> ((PySet) self).isSuperset(other));
        methods.addNoArgument("__reduce__", self -> ((PySet) self).reduced());
        if (mutable) {
            methods
                    .addNoArgument("pop", self -> ((PySet) self).popped())
                    .addOne(
                            "remove",
                            (self, element) -> {
                                ((PySet) self).removed(element);
                                return PyNone.INSTANCE;
                            });
        }
        methods.addNoArgument("__sizeof__", self -> PyInt.of(0));
        if (mutable) {
            methods.addOne(
                    "symmetric_difference_update",
                    (self, other) -> {
                        ((PySet) self).symmetricDifferenceUpdate(other);
                        return PyNone.INSTANCE;
                    });
        }
        methods
                .addOne(
                        "symmetric_difference",
                        (self, other) ->
                                ((PySet) self).detached().symmetricDifferenceUpdate(other))
                .add(
                        "union",
                        varargs(
                                who + ".union()",
                                (self, others) -> {
                                    PySet out = self.detached();
                                    for (PyObject other : others) {
                                        out.update(other);
                                    }
                                    return out;
                                }));
        if (mutable) {
            methods
                    .add(
                            "update",
                            varargs(
                                    who + ".update()",
                                    (self, others) -> {
                                        for (PyObject other : others) {
                                            self.update(other);
                                        }
                                        return PyNone.INSTANCE;
                                    }))
                    .add(
                            "difference_update",
                            varargs(
                                    who + ".difference_update()",
                                    (self, others) -> {
                                        for (PyObject other : others) {
                                            self.differenceUpdate(other);
                                        }
                                        return PyNone.INSTANCE;
                                    }))
                    .add(
                            "intersection_update",
                            varargs(
                                    who + ".intersection_update()",
                                    (self, others) -> {
                                        for (PyObject other : others) {
                                            self.intersectionUpdate(other);
                                        }
                                        return PyNone.INSTANCE;
                                    }));
        }
        // The twenty sentences clinic files beside the two tables, each the whole `ml_doc`: the row's own
        // name, its argument table, the `--` knife, and the prose. Eleven of them belong to a row that both
        // books file — the same `SET_*_METHODDEF` macro stands in `set_methods` and in `frozenset_methods` —
        // and the nine others to a row only `set` files. Two names are written twice over in C, once as
        // `set___contains__` and once as `frozenset___contains__`, and the two texts are one sentence:
        // clinic copied `copy`'s docstring rather than the type's, so a frozenset's `copy` still says "a
        // set". `__class_getitem__` is the twenty-first row of each table and the one clinic never wrote:
        // CPython files it with a sentence but no table, and reads its `($type, object, /)` out of the
        // METH_O|METH_CLASS it registers the row with, which is the flag `Table.genericSubscript` keeps.
        methods
                .document(
                        "__contains__",
                        "__contains__($self, object, /)\n"
                                + "--\n"
                                + "\n"
                                + "x.__contains__(y) <==> y in x.")
                .document(
                        "copy",
                        "copy($self, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return a shallow copy of a set.")
                .document(
                        "difference",
                        "difference($self, /, *others)\n"
                                + "--\n"
                                + "\n"
                                + "Return a new set with elements in the set that are not in the others.")
                .document(
                        "intersection",
                        "intersection($self, /, *others)\n"
                                + "--\n"
                                + "\n"
                                + "Return a new set with elements common to the set and all others.")
                .document(
                        "isdisjoint",
                        "isdisjoint($self, other, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return True if two sets have a null intersection.")
                .document(
                        "issubset",
                        "issubset($self, other, /)\n"
                                + "--\n"
                                + "\n"
                                + "Report whether another set contains this set.")
                .document(
                        "issuperset",
                        "issuperset($self, other, /)\n"
                                + "--\n"
                                + "\n"
                                + "Report whether this set contains another set.")
                .document(
                        "__reduce__",
                        "__reduce__($self, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return state information for pickling.")
                .document(
                        "__sizeof__",
                        "__sizeof__($self, /)\n"
                                + "--\n"
                                + "\n"
                                + "S.__sizeof__() -> size of S in memory, in bytes.")
                .document(
                        "symmetric_difference",
                        "symmetric_difference($self, other, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return a new set with elements in either the set or other but not both.")
                .document(
                        "union",
                        "union($self, /, *others)\n"
                                + "--\n"
                                + "\n"
                                + "Return a new set with elements from the set and all others.");
        if (mutable) {
            methods
                    .document(
                            "add",
                            "add($self, object, /)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Add an element to a set.\n"
                                    + "\n"
                                    + "This has no effect if the element is already present.")
                    .document(
                            "clear",
                            "clear($self, /)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Remove all elements from this set.")
                    .document(
                            "discard",
                            "discard($self, object, /)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Remove an element from a set if it is a member.\n"
                                    + "\n"
                                    + "Unlike set.remove(), the discard() method does not raise\n"
                                    + "an exception when an element is missing from the set.")
                    .document(
                            "difference_update",
                            "difference_update($self, /, *others)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Update the set, removing elements found in others.")
                    .document(
                            "intersection_update",
                            "intersection_update($self, /, *others)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Update the set, keeping only elements found in it and all others.")
                    .document(
                            "pop",
                            "pop($self, /)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Remove and return an arbitrary set element.\n"
                                    + "\n"
                                    + "Raises KeyError if the set is empty.")
                    .document(
                            "remove",
                            "remove($self, object, /)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Remove an element from a set; it must be a member.\n"
                                    + "\n"
                                    + "If the element is not a member, raise a KeyError.")
                    .document(
                            "symmetric_difference_update",
                            "symmetric_difference_update($self, other, /)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Update the set, keeping only elements found in either set, but not in both.")
                    .document(
                            "update",
                            "update($self, /, *others)\n"
                                    + "--\n"
                                    + "\n"
                                    + "Update the set, adding elements from all others.");
        }
        return methods;
    }

    private static final Members METHODS = table(PyType.SET, true);

    private static final Members FROZEN_METHODS = table(PyType.FROZENSET, false);
}
