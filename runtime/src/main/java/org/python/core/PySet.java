package org.python.core;

import java.util.ArrayList;
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
        return empty().update(iterable);
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
        return PyBool.of(holds(items, element));
    }

    @Override
    public PyObject iterator() {
        return new Walker();
    }

    /**
     * Subset ordering, with {@code <} a <em>proper</em> subset, so {@code {1} < {1}} is false rather
     * than an error. Anything that is not a set is refused by both sides, which {@code Slots} reports
     * in its own two-type words.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (!(other instanceof PySet set)) {
            return super.richCompare(cmp, other);
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
            case OR -> copy().update(other);
            case SUB -> copy().differenceUpdate(other);
            case AND -> copy().intersectionUpdate(other);
            case XOR -> copy().symmetricDifferenceUpdate(other);
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

    /** {@code s.copy()}, which CPython makes a set of the same type as the original. */
    PySet copy() {
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

    /** A lookup by hash, with the set's own wording, as {@code in} and {@code discard} use it. */
    private static boolean holds(Set<PyObject> target, PyObject element) {
        try {
            return target.contains(element);
        } catch (PyRaise refusal) {
            throw unhashable(element, refusal);
        }
    }

    /** Removal by hash, refused in the same words as a lookup. */
    private static boolean drop(Set<PyObject> target, PyObject element) {
        try {
            return target.remove(element);
        } catch (PyRaise refusal) {
            throw unhashable(element, refusal);
        }
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

    /** A {@code METH_VARARGS} method: keywords refused first, then nothing about the count. */
    private static PyBoundMethod.Body varargs(String qualified, Many body) {
        return (self, args, kwNames) -> {
            noKeywords(qualified, kwNames);
            return body.apply((PySet) self, args);
        };
    }

    /** The set's own table, with the frozen one its same methods minus the ones that change anything. */
    private static Members table(PyType type, boolean mutable) {
        Members methods = Members.of(type);
        if (mutable) {
            methods
                    .addOne("add", (self, element) -> {
                        ((PySet) self).add(element);
                        return PyNone.INSTANCE;
                    })
                    .addOne("discard", (self, element) -> {
                        ((PySet) self).discard(element);
                        return PyNone.INSTANCE;
                    })
                    .addOne("remove", (self, element) -> {
                        ((PySet) self).removed(element);
                        return PyNone.INSTANCE;
                    })
                    .addNoArgument("pop", self -> ((PySet) self).popped())
                    .addNoArgument("clear", self -> {
                        ((PySet) self).items.clear();
                        return PyNone.INSTANCE;
                    });
        }
        methods
                .addNoArgument("copy", self -> ((PySet) self).copy())
                .add("union", varargs(type.name() + ".union()",
                        (self, others) -> {
                            PySet out = self.copy();
                            for (PyObject other : others) {
                                out.update(other);
                            }
                            return out;
                        }))
                .add("intersection", varargs(type.name() + ".intersection()",
                        (self, others) -> {
                            PySet out = self.copy();
                            for (PyObject other : others) {
                                out.intersectionUpdate(other);
                            }
                            return out;
                        }))
                .add("difference", varargs(type.name() + ".difference()",
                        (self, others) -> {
                            PySet out = self.copy();
                            for (PyObject other : others) {
                                out.differenceUpdate(other);
                            }
                            return out;
                        }))
                .addOne("issubset", (self, other) -> ((PySet) self).isSubset(other))
                .addOne("issuperset", (self, other) -> ((PySet) self).isSuperset(other));
        if (mutable) {
            methods
                    .add("update", varargs(type.name() + ".update()",
                            (self, others) -> {
                                for (PyObject other : others) {
                                    self.update(other);
                                }
                                return PyNone.INSTANCE;
                            }))
                    .add("intersection_update", varargs(type.name() + ".intersection_update()",
                            (self, others) -> {
                                for (PyObject other : others) {
                                    self.intersectionUpdate(other);
                                }
                                return PyNone.INSTANCE;
                            }))
                    .add("difference_update", varargs(type.name() + ".difference_update()",
                            (self, others) -> {
                                for (PyObject other : others) {
                                    self.differenceUpdate(other);
                                }
                                return PyNone.INSTANCE;
                            }));
        }
        methods.addOne("symmetric_difference",
                (self, other) -> ((PySet) self).copy().symmetricDifferenceUpdate(other));
        if (mutable) {
            methods.addOne("symmetric_difference_update", (self, other) -> {
                ((PySet) self).symmetricDifferenceUpdate(other);
                return PyNone.INSTANCE;
            });
        }
        return methods.addOne("isdisjoint", (self, other) -> ((PySet) self).isDisjoint(other));
    }

    private static final Members METHODS = table(PyType.SET, true);

    private static final Members FROZEN_METHODS = table(PyType.FROZENSET, false);
}
