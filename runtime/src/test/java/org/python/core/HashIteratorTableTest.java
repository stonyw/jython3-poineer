package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The five names each of the seven cursors over a hash table files, measured against CPython 3.14.10.
 *
 * <p>Corpus case 115 reads all seven dictionaries from Python, one line per answer. What is pinned here is
 * the half a script cannot point at. The first of it is that seven dictionaries are one shape with one row
 * missing: {@code dictiter_methods} ({@code Objects/dictobject.c:5211}) is filed by all six dict cursors and
 * {@code setiter_methods} ({@code Objects/setobject.c:895}) by the set's, both of them two {@code
 * PyMethodDef} rows — and neither has the {@code __setstate__} the sequence cursors file beside them, which
 * is what makes these dictionaries five names against those six and {@code dir()} twenty-seven against
 * twenty-eight. The second is the row that is not in either table: a payload always names {@code iter}, even
 * for the three cursors that walk the table from its last entry back, where the list's own reversal names
 * {@code reversed}.
 *
 * <p>Then the guard, which is the reason a cursor over a hash table can be made to complain at all. C
 * compares the count the table held when the cursor was made against the count it holds now — {@code
 * di_used} against {@code ma_used} — and nothing else: a value written over a key already in the table moves
 * no entry and is not noticed, and a table cut and rebuilt to the same count between two steps is not
 * noticed either. The refusal marks the cursor {@code -1}, and since no table has {@code -1} entries, that
 * outlives every change that follows it. The one sentence this model cannot reach is CPython's second one,
 * {@code dictionary keys changed during iteration} ({@code :5281}), which is what its scan says when it
 * finds an entry past the count it meant to hand out — a thing an array of the entries as they were written
 * does not do.
 */
class HashIteratorTableTest {

    /** The five names each book files, as the two slot entries and one C method list wrote them. */
    private static final List<String> ORDER =
            List.of("__iter__", "__next__", "__length_hint__", "__reduce__", "__doc__");

    /** The two rows {@code tp_iter} and {@code tp_iternext} are copied out as. */
    private static final List<String> SLOTS = List.of("__iter__", "__next__");

    /** The two rows each of the two C method lists files, in the order it writes them. */
    private static final List<String> METHODS = List.of("__length_hint__", "__reduce__");

    /** The three names {@code dir()} of a cursor gains over {@code dir()} of {@code object}. */
    private static final List<String> OWN = List.of("__iter__", "__length_hint__", "__next__");

    /** The seven types, in the order the two C files write them. */
    private static final List<PyType> SEVEN =
            List.of(
                    PyType.DICT_KEY_ITERATOR,
                    PyType.DICT_VALUE_ITERATOR,
                    PyType.DICT_ITEM_ITERATOR,
                    PyType.DICT_REVERSE_KEY_ITERATOR,
                    PyType.DICT_REVERSE_VALUE_ITERATOR,
                    PyType.DICT_REVERSE_ITEM_ITERATOR,
                    PyType.SET_ITERATOR);

    private static final String LENGTH_HINT_DOC = "Private method returning an estimate of len(list(it)).";
    private static final String REDUCE_DOC = "Return state information for pickling.";

    /** The two sentences the two files write, in the order a step reads them. */
    private static final String DICT_REFUSAL = "dictionary changed size during iteration";

    private static final String SET_REFUSAL = "Set changed size during iteration";
    private static final String RAISED_DICT_REFUSAL = "RuntimeError: " + DICT_REFUSAL;

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final PyObject ONE = PyInt.of(1);
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject ZERO = PyInt.ZERO;
    private static final PyObject TEXT = PyStr.of("a");
    private static final PyObject NINETY_NINE = PyInt.of(99);

    private static final BigInteger ZERO_BI = BigInteger.ZERO;
    private static final BigInteger ONE_BI = BigInteger.ONE;
    private static final BigInteger TWO_BI = BigInteger.TWO;
    private static final BigInteger THREE_BI = BigInteger.valueOf(3);

    /** One keyword name, for the calls that argue about how they were written. */
    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    /** {@code {'a': 1, 'b': 2, 'c': 3}}: the three entries every walk here is counted in. */
    private static PyDict table() {
        PyDict filled = PyDict.empty();
        filled.put(PyStr.of("a"), ONE);
        filled.put(PyStr.of("b"), TWO);
        filled.put(PyStr.of("c"), THREE);
        return filled;
    }

    /** A fresh cursor of {@code owner}'s own kind, over three entries of the shape that kind walks. */
    private static PyObject cursor(PyType owner) {
        if (owner == PyType.SET_ITERATOR) {
            return HashIterators.over(PySet.of(ONE, TWO, THREE));
        }
        PyDict source = table();
        if (owner == PyType.DICT_KEY_ITERATOR) {
            return HashIterators.keys(source);
        }
        if (owner == PyType.DICT_VALUE_ITERATOR) {
            return HashIterators.values(source);
        }
        if (owner == PyType.DICT_ITEM_ITERATOR) {
            return HashIterators.items(source);
        }
        if (owner == PyType.DICT_REVERSE_KEY_ITERATOR) {
            return HashIterators.backwardsKeys(source);
        }
        if (owner == PyType.DICT_REVERSE_VALUE_ITERATOR) {
            return HashIterators.backwardsValues(source);
        }
        return HashIterators.backwardsItems(source);
    }

    /** A cursor of some other one of the seven, which every receiver check on this type's rows refuses. */
    private static PyObject cursorOtherThan(PyType owner) {
        for (PyType other : SEVEN) {
            if (other != owner) {
                return cursor(other);
            }
        }
        throw new AssertionError(owner);
    }

    /** The entry one of the seven types files under {@code name}. */
    private static PyObject entry(PyType owner, String name) {
        return TypeDict.declared(owner).get(PyStr.interned(name));
    }

    /** The names one type's own dictionary keeps, in the order it kept them. */
    private static List<String> filed(PyType owner) {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(owner, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** {@code sorted(set(dir(T)) - set(dir(object)))}, which is three names for all seven. */
    private static List<String> gained(PyType owner) {
        List<String> names = new ArrayList<>(Dirs.classNames(owner));
        names.removeAll(Dirs.classNames(PyType.OBJECT));
        Collections.sort(names);
        return names;
    }

    /** {@code it.__next__()}: one step, read as an attribute and then called. */
    private static PyObject nextOf(PyObject iterator) {
        return Slots.getAttr(iterator, "__next__").call(new PyObject[0], null);
    }

    /** {@code it.__length_hint__()}: the entries still in this cursor. */
    private static BigInteger hint(PyObject iterator) {
        return ((PyInt) Slots.getAttr(iterator, "__length_hint__").call(new PyObject[0], null))
                .getValue();
    }

    /** The hint before the walk and after each of four steps, which is where a spent cursor says nought. */
    private static List<BigInteger> ladder(PyObject iterator) {
        List<BigInteger> seen = new ArrayList<>();
        seen.add(hint(iterator));
        for (int step = 0; step < 4; step++) {
            try {
                nextOf(iterator);
            } catch (PyRaise stopped) {
                assertThat(stopped.getMessage()).isEqualTo("StopIteration: ");
            }
            seen.add(hint(iterator));
        }
        return seen;
    }

    /** {@code it.__reduce__()}: the pickle payload, still holding the callable it named. */
    private static PyTuple reduce(PyObject iterator) {
        return (PyTuple) Slots.getAttr(iterator, "__reduce__").call(new PyObject[0], null);
    }

    /** The list a payload carries, which is the one item of its argument tuple. */
    private static PyList restOf(PyObject iterator) {
        return (PyList) ((PyTuple) reduce(iterator).at(1)).at(0);
    }

    private static List<String> walked(PyObject iterator) {
        List<String> read = new ArrayList<>();
        PyObject value;
        while ((value = iteratorOfRead(iterator)) != null) {
            read.add(value.repr());
        }
        return read;
    }

    /** One step of {@link #walked}, which a spent cursor ends with a null rather than a complaint. */
    private static PyObject iteratorOfRead(PyObject iterator) {
        return ((PyIterator) iterator).nextOrNull();
    }

    /** {@code vars(T)[name](*args)}: a filed row, called without an attribute read. */
    private static PyObject call(PyType owner, String name, PyObject... args) {
        return Slots.call(entry(owner, name), args, null);
    }

    /** {@code vars(T)[name](*args, **kwargs)}. */
    private static PyObject calledWith(PyType owner, String name, PyTuple kwNames, PyObject... args) {
        return Slots.call(entry(owner, name), args, kwNames);
    }

    /** {@code del source[key]}: the cut that parts a dict cursor from the count it was made with. */
    private static void cut(PyDict source, PyObject key) {
        source.delItem(key);
    }

    /** {@code held.discard(element)}: the same over a set. */
    private static void drop(PySet held, PyObject element) {
        Slots.getAttr(held, "discard").call(new PyObject[] {element}, null);
    }

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * Seven dictionaries, seven of them one shape with one row fewer than the sequence cursors'.
     *
     * <p>The two slot rows and the two {@code PyMethodDef} rows are filed in the same order by all seven,
     * and the tail is the value {@code None} in all seven because {@code tp_doc} is 0 in all seven. {@code
     * __setstate__} is the name none of them files: a position in a hash table is not a state to restart
     * from, since the table may have been rebuilt since, so the dictionary is one row shorter than a
     * sequence cursor's and a payload is the only way these seven answer the pickle at all. {@code __new__}
     * is filed by none of the seven either — {@code tp_new} is 0 — and a read of the name walks to {@code
     * object}.
     */
    @Test
    void sevenDictionariesAreOneShapeFiledSevenTimes() {
        assertThat(ORDER).hasSize(5).startsWith("__iter__").endsWith("__doc__");

        for (PyType owner : SEVEN) {
            String kind = owner.name();
            assertThat(filed(owner)).as(kind).isEqualTo(ORDER);
            assertThat(TypeDict.declared(owner).keys()).as(kind).hasSize(5);
            assertThat(Dirs.classNames(owner)).as(kind).hasSize(27);
            assertThat(gained(owner)).as(kind).isEqualTo(OWN);
            assertThat(owner.docText()).as(kind).isNull();
            assertThat(Slots.getAttr(owner, "__doc__")).as(kind).isSameAs(NONE);
            assertThat(entry(owner, "__doc__")).as(kind).isSameAs(NONE);
            assertThat(Slots.getAttr(owner, "__name__")).as(kind).isEqualTo(PyStr.of(kind));
            assertThat(Slots.getAttr(owner, "__qualname__")).as(kind).isEqualTo(PyStr.of(kind));
            assertThat(Slots.getAttr(owner, "__module__")).as(kind).isEqualTo(PyStr.of("builtins"));
            assertThat(Slots.getAttr(owner, "__base__")).as(kind).isSameAs(PyType.OBJECT);
            assertThat(owner.mro()).as(kind).containsExactly(owner, PyType.OBJECT);
            assertThat(Arrays.asList(Members.tableOf(owner).names()))
                    .as(kind)
                    .containsExactlyElementsOf(METHODS);

            for (String name : SLOTS) {
                assertThat(entry(owner, name).getType().name()).as(kind + "." + name)
                        .isEqualTo("wrapper_descriptor");
                assertThat(entry(owner, name)).as(kind + "." + name)
                        .isSameAs(Slots.getAttr(owner, name));
                assertThat(Slots.getAttr(entry(owner, name), "__doc__"))
                        .as(kind + "." + name)
                        .isEqualTo(PyStr.of(name.equals("__iter__")
                                ? "Implement iter(self)." : "Implement next(self)."));
            }
            for (String name : METHODS) {
                assertThat(entry(owner, name).getType().name()).as(kind + "." + name)
                        .isEqualTo("method_descriptor");
                assertThat(entry(owner, name)).as(kind + "." + name)
                        .isSameAs(Members.tableOf(owner).describe(name));
                assertThat(Slots.getAttr(entry(owner, name), "__objclass__"))
                        .as(kind + "." + name)
                        .isSameAs(owner);
                assertThat(Slots.getAttr(entry(owner, name), "__doc__"))
                        .as(kind + "." + name)
                        .isEqualTo(PyStr.of(name.equals("__length_hint__")
                                ? LENGTH_HINT_DOC : REDUCE_DOC));
                assertThat(Slots.getAttr(entry(owner, name), "__text_signature__"))
                        .as(kind + "." + name)
                        .isEqualTo(PyStr.of("($self, /)"));
            }

            // No constructor row and no restart row: the two names the sequence cursors file that these
            // seven do not, in either of their two tables.
            assertThat(entry(owner, "__new__")).as(kind).isNull();
            assertThat(entry(owner, "__setstate__")).as(kind).isNull();
            assertThat(Slots.getAttr(owner, "__new__")).isSameAs(Slots.getAttr(PyType.OBJECT, "__new__"));
        }

        // One book, seven copies: the same two sentences, and seven pairs of objects.
        for (String name : List.of("__iter__", "__next__", "__length_hint__", "__reduce__")) {
            List<PyObject> copies = new ArrayList<>();
            for (PyType owner : SEVEN) {
                copies.add(entry(owner, name));
                assertThat(Slots.getAttr(entry(owner, name), "__doc__"))
                        .as(owner.name() + "." + name)
                        .isEqualTo(Slots.getAttr(entry(PyType.DICT_KEY_ITERATOR, name), "__doc__"));
            }
            for (int a = 0; a < SEVEN.size(); a++) {
                for (int b = a + 1; b < SEVEN.size(); b++) {
                    assertThat(copies.get(b))
                            .as(name + " of " + SEVEN.get(a).name() + " and " + SEVEN.get(b).name())
                            .isNotSameAs(copies.get(a));
                }
            }
        }

        // Absent rather than filed: these seven restart nothing and are not sequences either.
        for (String name : List.of("__setstate__", "__getnewargs__", "__reduce_ex__", "__len__",
                                  "__reversed__", "__dict__")) {
            assertThat(entry(PyType.DICT_KEY_ITERATOR, name)).as(name).isNull();
            assertThat(ORDER).doesNotContain(name);
        }
    }

    /**
     * Seven type names, chosen by the call that made the cursor and by nothing else.
     *
     * <p>The six dict types come from one struct: the three forwards are {@code dict_iter}, {@code
     * dictvalues_iter} and {@code dictitems_iter}, the three back are the same struct started at the last
     * entry, which {@code dict.__reversed__} and the three views' own {@code __reversed__} each ask for.
     * The set's one type is handed out by {@code set} and {@code frozenset} alike, and a {@code mappingproxy}
     * makes neither of its two walks — it asks the dictionary underneath, so a window's cursor is the
     * dict's cursor and carries its name. None of the seven can be built: {@code tp_new} is 0 in all of
     * them, and the refusal is the type's own {@code tp_name} in every one.
     */
    @Test
    void eachCallNamesTheCursorItWalksWith() {
        PyDict source = table();
        assertThat(source.iterator().getType()).isSameAs(PyType.DICT_KEY_ITERATOR);
        assertThat(source.keyIterator().getType()).isSameAs(PyType.DICT_KEY_ITERATOR);
        assertThat(source.valueIterator().getType()).isSameAs(PyType.DICT_VALUE_ITERATOR);
        assertThat(source.itemIterator().getType()).isSameAs(PyType.DICT_ITEM_ITERATOR);
        assertThat(source.reverseKeyIterator().getType()).isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(source.reverseValueIterator().getType())
                .isSameAs(PyType.DICT_REVERSE_VALUE_ITERATOR);
        assertThat(source.reverseItemIterator().getType()).isSameAs(PyType.DICT_REVERSE_ITEM_ITERATOR);
        assertThat(PyDict.empty().iterator().getType()).isSameAs(PyType.DICT_KEY_ITERATOR);

        // The three views walk the dict's own cursors, forwards and back.
        assertThat(DictViews.keys(source).iterator().getType()).isSameAs(PyType.DICT_KEY_ITERATOR);
        assertThat(DictViews.values(source).iterator().getType()).isSameAs(PyType.DICT_VALUE_ITERATOR);
        assertThat(DictViews.items(source).iterator().getType()).isSameAs(PyType.DICT_ITEM_ITERATOR);
        assertThat(Slots.getAttr(DictViews.keys(source), "__reversed__")
                .call(new PyObject[0], null).getType())
                .isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(Slots.getAttr(DictViews.values(source), "__reversed__")
                .call(new PyObject[0], null).getType())
                .isSameAs(PyType.DICT_REVERSE_VALUE_ITERATOR);
        assertThat(Slots.getAttr(DictViews.items(source), "__reversed__")
                .call(new PyObject[0], null).getType())
                .isSameAs(PyType.DICT_REVERSE_ITEM_ITERATOR);

        // The builtin asks the dict for its own cursor, in both directions, and the set for none.
        PyObject reversed = PyRuntime.builtin("reversed");
        assertThat(Slots.call(reversed, new PyObject[] {source}, null).getType())
                .isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(Slots.getAttr(source, "__reversed__").call(new PyObject[0], null).getType())
                .isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(PySet.of(ONE).iterator().getType()).isSameAs(PyType.SET_ITERATOR);
        PyObject frozen = new PySet(java.util.Set.of(ONE), PyType.FROZENSET);
        assertThat(frozen.iterator().getType()).isSameAs(PyType.SET_ITERATOR);
        refuses(
                "TypeError: 'set' object is not reversible",
                () -> Slots.call(reversed, new PyObject[] {PySet.of(ONE)}, null));
        refuses(
                "TypeError: 'frozenset' object is not reversible",
                () -> Slots.call(reversed, new PyObject[] {frozen}, null));

        // A window makes no cursor of its own: both of its walks are the dictionary's.
        PyObject window = Slots.getAttr(PyType.INT, "__dict__");
        assertThat(((PyMappingProxy) window).iterator().getType()).isSameAs(PyType.DICT_KEY_ITERATOR);
        assertThat(((PyMappingProxy) window).keysIterator().getType())
                .isSameAs(PyType.DICT_KEY_ITERATOR);
        assertThat(((PyMappingProxy) window).reversedKeysIterator().getType())
                .isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(Slots.getAttr(window, "__reversed__").call(new PyObject[0], null).getType())
                .isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);

        for (PyType owner : SEVEN) {
            String kind = owner.name();
            refuses("TypeError: cannot create '" + kind + "' instances",
                    () -> owner.call(new PyObject[0], null));
            refuses("TypeError: cannot create '" + kind + "' instances",
                    () -> owner.call(new PyObject[] {ONE}, null));
            refuses("TypeError: cannot create '" + kind + "' instances",
                    () -> owner.call(new PyObject[] {ONE}, X));
            refuses(
                    "TypeError: cannot create '" + kind + "' instances",
                    () -> Slots.call(Slots.getAttr(PyType.OBJECT, "__new__"), new PyObject[] {owner}, null));
            assertThat(TypeDict.declared(owner).get(PyStr.interned("__init__"))).as(kind).isNull();
        }

        // Seven types, and no one of them a value of another.
        for (PyType owner : SEVEN) {
            assertThat(owner.instanceOf(cursor(owner))).as(owner.name()).isTrue();
            for (PyType other : SEVEN) {
                if (other != owner) {
                    assertThat(owner.instanceOf(cursor(other))).as(owner.name() + " of " + other.name())
                            .isFalse();
                }
            }
        }
    }

    /**
     * The estimate counts the walk, and goes quiet for good when the two counts part.
     *
     * <p>{@code dictiter_len} and {@code setiter_len} are the same four lines over the two structs: the
     * count still to hand out, or nought when the cursor holds no table at all, or when the count it was
     * made with has stopped matching the table's. A spent cursor therefore reads nought because its pointer
     * is gone, and a refused one because its marker is {@code -1} — which no later change can match, so the
     * estimate stays at nought after the table is put back the size it was.
     */
    @Test
    void theHintCountsTheWalkAndAPartedCountSilencesIt() {
        List<BigInteger> counted = List.of(THREE_BI, TWO_BI, ONE_BI, ZERO_BI, ZERO_BI);
        for (PyType owner : SEVEN) {
            assertThat(ladder(cursor(owner))).as(owner.name()).isEqualTo(counted);
        }
        assertThat(ladder(HashIterators.keys(PyDict.empty())))
                .isEqualTo(List.of(ZERO_BI, ZERO_BI, ZERO_BI, ZERO_BI, ZERO_BI));
        assertThat(hint(HashIterators.over(PySet.empty()))).isEqualTo(ZERO_BI);

        // A table that no longer matches the count answers nought before anything is refused.
        PyDict source = table();
        PyObject overDict = HashIterators.keys(source);
        source.put(PyStr.of("d"), FOUR);
        assertThat(hint(overDict)).isEqualTo(ZERO_BI);
        source.put(PyStr.of("a"), NINETY_NINE);
        assertThat(hint(overDict)).isEqualTo(ZERO_BI);
        refuses(RAISED_DICT_REFUSAL, () -> nextOf(overDict));

        // ... and the marker is the answer from here on, whatever the table does next.
        assertThat(hint(overDict)).isEqualTo(ZERO_BI);
        cut(source, PyStr.of("d"));
        assertThat(hint(overDict)).isEqualTo(ZERO_BI);
        assertThat(source.size()).isEqualTo(3);

        // A cut is the same parting, and the set writes its own capitalised sentence.
        PyDict trimmed = table();
        PyObject backwards = HashIterators.backwardsItems(trimmed);
        cut(trimmed, PyStr.of("b"));
        assertThat(hint(backwards)).isEqualTo(ZERO_BI);
        refuses(RAISED_DICT_REFUSAL, () -> nextOf(backwards));

        PySet held = PySet.of(ONE, TWO, THREE);
        PyObject overSet = HashIterators.over(held);
        held.add(FOUR);
        assertThat(hint(overSet)).isEqualTo(ZERO_BI);
        refuses("RuntimeError: " + SET_REFUSAL, () -> nextOf(overSet));
        drop(held, FOUR);
        assertThat(hint(overSet)).isEqualTo(ZERO_BI);
    }

    /**
     * The guard is a count and nothing else, which is what a program can walk into by mistake.
     *
     * <p>Writing a value over a key already in the table moves no entry, so a walk that finds the same count
     * where it left one keeps going — and a table cut and rebuilt to the count it started with is not
     * complained about either. That last case is where this runtime and the reference part company in the
     * other direction: CPython's step reads the live table by index, so a cut and a new key leave it either
     * one item short of the walk it promised, or facing an entry past its own count and saying {@code
     * dictionary keys changed during iteration} ({@code Objects/dictobject.c:5281}). A cursor here holds the
     * entries as the table had them when it was made, so it hands out what it took and never finds the
     * second sentence; that sentence is unreachable here and is named in the milestone's census.
     */
    @Test
    void onlyTheCountIsGuardedAndAChangePutBackIsNoChange() {
        PyDict source = table();
        PyObject walked = HashIterators.items(source);
        nextOf(walked);
        source.put(PyStr.of("a"), NINETY_NINE);
        assertThat(hint(walked)).isEqualTo(TWO_BI);
        assertThat(nextOf(walked).repr()).isEqualTo("('b', 2)");

        PyDict changed = table();
        PyObject keys = HashIterators.keys(changed);
        nextOf(keys);
        changed.put(PyStr.of("d"), FOUR);
        cut(changed, PyStr.of("b"));
        assertThat(changed.size()).isEqualTo(3);
        assertThat(hint(keys)).isEqualTo(TWO_BI);
        // CPython reads the live table by index and answers ['c', 'd'] to this walk; the snapshot answers
        // ['b', 'c'], which is what the cursor was handed when it was made.
        assertThat(walked(keys)).isEqualTo(List.of("'b'", "'c'"));

        // A refusal, once made, is not undone by putting the count back.
        PyDict refused = table();
        PyObject overRefused = HashIterators.keys(refused);
        refused.put(PyStr.of("d"), FOUR);
        refuses(RAISED_DICT_REFUSAL, () -> nextOf(overRefused));
        cut(refused, PyStr.of("d"));
        refuses(RAISED_DICT_REFUSAL, () -> nextOf(overRefused));
        refuses(RAISED_DICT_REFUSAL, () -> reduce(overRefused));
        refuses(RAISED_DICT_REFUSAL, () -> walked(overRefused));
        assertThat(hint(overRefused)).isEqualTo(ZERO_BI);
    }

    /**
     * A cursor that ran out holds no table, and refuses nobody after it.
     *
     * <p>The step that finds nothing left clears the pointer — {@code di_dict = NULL}, {@code si_set = NULL}
     * — and from there the three rows stop asking: the walk is over without a complaint, the estimate is
     * nought, and the payload is a call of {@code iter} on an empty list even though the table it came from
     * has since been cut and grown. There is no marker to carry over either: a spent cursor and a refused
     * one both stop, but only the refused one says so.
     */
    @Test
    void aSpentCursorHoldsNoTableAndRefusesNobody() {
        PyDict source = table();
        PyObject spent = HashIterators.keys(source);
        assertThat(walked(spent)).isEqualTo(List.of("'a'", "'b'", "'c'"));
        assertThat(iteratorOfRead(spent)).isNull();

        source.put(PyStr.of("d"), FOUR);
        cut(source, PyStr.of("a"));
        assertThat(hint(spent)).isEqualTo(ZERO_BI);
        assertThat(iteratorOfRead(spent)).isNull();
        assertThat(repr(restOf(spent))).isEmpty();

        // The reverse cursors spend themselves on the step past the first entry, the same way.
        PyDict emptied = table();
        PyObject backwards = HashIterators.backwardsValues(emptied);
        assertThat(walked(backwards)).isEqualTo(List.of("3", "2", "1"));
        for (String key : List.of("a", "b", "c")) {
            cut(emptied, PyStr.of(key));
        }
        assertThat(hint(backwards)).isEqualTo(ZERO_BI);
        assertThat(repr(restOf(backwards))).isEmpty();

        PySet held = PySet.of(ONE);
        PyObject overSet = HashIterators.over(held);
        assertThat(walked(overSet)).isEqualTo(List.of("1"));
        held.add(TWO);
        assertThat(hint(overSet)).isEqualTo(ZERO_BI);
        assertThat(repr(restOf(overSet))).isEmpty();
        assertThat(iteratorOfRead(overSet)).isNull();
    }

    /**
     * What each of the seven reports as its state, and the one callable all seven name.
     *
     * <p>{@code dictiter_reduce} and {@code setiter_reduce} are the same six lines, and both end in {@code
     * Py_BuildValue("N(N)", iter, list)}: a payload of two items whose argument tuple holds one list of the
     * entries still to come. All seven name {@code iter}, the three that walk the table backwards included,
     * which is where they differ from the list's own reversal — that one names {@code reversed}, because its
     * state is a position a {@code reversed()} call can restart. These cannot: the restart is a {@code
     * list_iterator} over what the cursor had left, and the cursor's kind is not in the answer at all.
     */
    @Test
    void eachPickleIsACallOfIterOnTheRestOfTheWalk() {
        PyObject restart = PyRuntime.builtin("iter");
        for (PyType owner : SEVEN) {
            PyTuple payload = reduce(cursor(owner));
            String kind = owner.name();
            assertThat(payload.size()).as(kind).isEqualTo(2);
            assertThat(payload.at(0)).as(kind).isSameAs(restart);
            assertThat(payload.at(1)).as(kind).isInstanceOf(PyTuple.class);
            assertThat(((PyTuple) payload.at(1)).size()).as(kind).isEqualTo(1);
            assertThat(restOf(cursor(owner)).getType()).as(kind).isSameAs(PyType.LIST);
        }

        // Fresh, one step in, and spent: the list is what the walk still holds, in the order it holds it.
        assertThat(repr(restOf(HashIterators.keys(table()))))
                .isEqualTo(List.of("'a'", "'b'", "'c'"));
        assertThat(repr(restOf(HashIterators.values(table()))))
                .isEqualTo(List.of("1", "2", "3"));
        assertThat(repr(restOf(HashIterators.items(table()))))
                .isEqualTo(List.of("('a', 1)", "('b', 2)", "('c', 3)"));
        assertThat(repr(restOf(HashIterators.backwardsKeys(table()))))
                .isEqualTo(List.of("'c'", "'b'", "'a'"));
        assertThat(repr(restOf(HashIterators.backwardsValues(table()))))
                .isEqualTo(List.of("3", "2", "1"));
        PyObject backwardsItems = HashIterators.backwardsItems(table());
        assertThat(repr(restOf(backwardsItems))).isEqualTo(List.of("('c', 3)", "('b', 2)", "('a', 1)"));

        PyObject mid = HashIterators.items(table());
        nextOf(mid);
        assertThat(repr(restOf(mid))).isEqualTo(List.of("('b', 2)", "('c', 3)"));
        PyObject ended = HashIterators.backwardsValues(table());
        walked(ended);
        assertThat(repr(restOf(ended))).isEmpty();

        // A set's payload is a list too, and a one-element set is the only order it can promise.
        assertThat(repr(restOf(HashIterators.over(PySet.of(ONE))))).isEqualTo(List.of("1"));
        assertThat(repr(restOf(HashIterators.over(PySet.empty())))).isEmpty();

        // The question spends nothing: the same cursor asked twice gives the same list and the same estimate.
        PyObject asked = HashIterators.keys(table());
        assertThat(repr(restOf(asked))).isEqualTo(repr(restOf(asked)));
        assertThat(hint(asked)).isEqualTo(THREE_BI);

        // And the restart is a list cursor over the entries the walk had left.
        PyObject part = HashIterators.keys(table());
        nextOf(part);
        PyTuple payload = reduce(part);
        PyObject back = Slots.call(payload.at(0), new PyObject[] {((PyTuple) payload.at(1)).at(0)}, null);
        assertThat(back.getType()).isSameAs(PyType.LIST_ITERATOR);
        assertThat(walked(back)).isEqualTo(List.of("'b'", "'c'"));
        assertThat(hint(part)).isEqualTo(TWO_BI);
    }

    /**
     * A pickle asked of a changed table refuses, and marks the cursor nobody asked it to mark.
     *
     * <p>{@code dictiter_reduce} copies the struct and drives the copy through {@code PySequence_List}, so
     * the refusal the question brings back is the copy's. The cursor a program holds keeps the count it was
     * made with: put the table back the size it was and the walk goes on exactly where it was asked to
     * report. The list, once it is made, is the entries still to come — and only those, because the copy
     * starts where the original stands.
     */
    @Test
    void thePickleOfAChangedTableRefusesAndMarksNobody() {
        PyDict source = table();
        PyObject cursor = HashIterators.keys(source);
        nextOf(cursor);
        source.put(PyStr.of("d"), FOUR);
        refuses(RAISED_DICT_REFUSAL, () -> reduce(cursor));
        assertThat(hint(cursor)).isEqualTo(ZERO_BI);

        cut(source, PyStr.of("d"));
        assertThat(hint(cursor)).isEqualTo(TWO_BI);
        assertThat(repr(restOf(cursor))).isEqualTo(List.of("'b'", "'c'"));
        assertThat(walked(cursor)).isEqualTo(List.of("'b'", "'c'"));

        // The same over a set, whose sentence the copy writes in the set's own words.
        PySet held = PySet.of(ONE, TWO);
        PyObject overSet = HashIterators.over(held);
        held.add(THREE);
        refuses("RuntimeError: " + SET_REFUSAL, () -> reduce(overSet));
        drop(held, THREE);
        assertThat(repr(restOf(overSet))).containsExactlyInAnyOrder("1", "2");
    }

    /**
     * A row of one of the seven refuses a cursor of another, and a row of the type refuses no argument.
     *
     * <p>{@code descr_check} is the gate slice 63 measured on two cursors and slice 64 on six: it compares
     * the value's own type against the type that declares the row, so a {@code dict_keyiterator}'s row told
     * to walk an item cursor says {@code descriptor '__length_hint__' for 'dict_keyiterator' objects doesn't
     * apply to a 'dict_itemiterator' object}. Seven types instead of six makes forty-two of those sentences,
     * and the two refusals of the argument list — the empty one and the one that names a keyword — are
     * {@code method_check_args}'s own, in the declaring type's words. A read from a value binds the same row
     * and reports the kind the value was made from.
     */
    @Test
    void eachRowRefusesTheOtherSixCursors() {
        for (PyType owner : SEVEN) {
            String kind = owner.name();
            for (String name : METHODS) {
                refuses(
                        "TypeError: unbound method " + kind + "." + name + "() needs an argument",
                        () -> call(owner, name));
                // The keyword's value rides at the end of the argument array, so x=1 alone still names no
                // receiver — which is what makes this refusal the same sentence as the one above.
                refuses(
                        "TypeError: unbound method " + kind + "." + name + "() needs an argument",
                        () -> calledWith(owner, name, X, ONE));
                refuses(
                        "TypeError: descriptor '" + name + "' for '" + kind
                                + "' objects doesn't apply to a 'int' object",
                        () -> call(owner, name, ONE));
                refuses(
                        "TypeError: descriptor '" + name + "' for '" + kind
                                + "' objects doesn't apply to a 'str' object",
                        () -> call(owner, name, TEXT));
                refuses(
                        "TypeError: " + kind + "." + name + "() takes no arguments (1 given)",
                        () -> call(owner, name, cursor(owner), ONE));
                refuses(
                        "TypeError: " + kind + "." + name + "() takes no keyword arguments",
                        () -> calledWith(owner, name, X, cursor(owner), ONE));

                for (PyType other : SEVEN) {
                    if (other == owner) {
                        continue;
                    }
                    refuses(
                            "TypeError: descriptor '" + name + "' for '" + kind
                                    + "' objects doesn't apply to a '" + other.name() + "' object",
                            () -> call(owner, name, cursor(other)));
                }
            }

            // The two slot rows check their receiver the same way, in the wrapper's own words.
            for (String name : SLOTS) {
                refuses(
                        "TypeError: descriptor '" + name + "' of '" + kind + "' object needs an argument",
                        () -> call(owner, name));
                refuses(
                        "TypeError: descriptor '" + name + "' requires a '" + kind
                                + "' object but received a 'int'",
                        () -> call(owner, name, ONE));
            }

            // A row read from a value runs on that value and says its own type's name.
            PyObject value = cursor(owner);
            assertThat(Slots.getAttr(value, "__iter__").call(new PyObject[0], null))
                    .as(kind)
                    .isSameAs(value);
            assertThat(hint(value)).as(kind).isEqualTo(THREE_BI);
            assertThat(nextOf(value).repr()).as(kind).isEqualTo(nextOf(cursor(owner)).repr());
            assertThat(reduce(value).at(0)).as(kind).isSameAs(PyRuntime.builtin("iter"));
        }
    }

    /** The reprs of a list's items, which is how a payload's contents are read without its own repr. */
    private static List<String> repr(PyList list) {
        List<String> shown = new ArrayList<>();
        for (PyObject item : list.getItems()) {
            shown.add(item.repr());
        }
        return shown;
    }
}
