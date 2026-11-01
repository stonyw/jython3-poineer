package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The six names each of the six cursors the sequence types hands out files, measured against CPython
 * 3.14.10.
 *
 * <p>Corpus case 114 reads all six dictionaries from Python, one line per answer. What is pinned here is
 * the half a script cannot point at. The first of it is that six dictionaries are one: {@code listobject.c}
 * files {@code listiter_methods} ({@code :3961}) and {@code listreviter_methods} ({@code :4115}), the other
 * three sequence files one apiece, all of them the same three {@code PyMethodDef} rows with the
 * same three sentences, and {@code TypeDict} writes the six books out of one {@link SequenceIterators}
 * table each. The second is what no read reaches: that only the two list cursors look at their source
 * again, that a spent cursor is marked by a different word in the two families, and that the builtin a
 * payload names is read out of the builtins dictionary at the moment of the call.
 *
 * <p>Then the three windows {@code __setstate__} clips into — {@code [-1, len]} for a cursor walking
 * forwards, {@code [-1, len - 1]} for one walking back, {@code [0, len]} for the four that cannot change —
 * and the one converter all six share, which is {@code PyLong_AsSsize_t} and so asks an {@code int} rather
 * than an index. Then {@code descr_check} and {@code wrapperdescr_call} against a cursor of a neighbouring
 * type, which is the six-way version of the check slice 63 measured on two. And last the empty value each
 * family reports for a source it has dropped, of which {@code bytes} is the one whose answer is not a value
 * of the type it was made from.
 */
class SequenceIteratorTableTest {

    /** The six names each book files, in the order the two slot entries and one C method list wrote them. */
    private static final List<String> ORDER =
            List.of("__iter__", "__next__", "__length_hint__", "__reduce__", "__setstate__", "__doc__");

    /** The two rows {@code tp_iter} and {@code tp_iternext} are copied out as. */
    private static final List<String> SLOTS = List.of("__iter__", "__next__");

    /** The three rows each of the six C method lists files, in the order it writes them. */
    private static final List<String> METHODS = List.of("__length_hint__", "__reduce__", "__setstate__");

    /** Two of the three, the ones filed as {@code METH_NOARGS}. */
    private static final List<String> BARE = List.of("__length_hint__", "__reduce__");

    /** The four names {@code dir()} of a cursor gains over {@code dir()} of {@code object}. */
    private static final List<String> OWN =
            List.of("__iter__", "__length_hint__", "__next__", "__setstate__");

    /** The six types, in the order the four C files write them. */
    private static final List<PyType> SIX =
            List.of(
                    PyType.LIST_ITERATOR,
                    PyType.LIST_REVERSE_ITERATOR,
                    PyType.TUPLE_ITERATOR,
                    PyType.BYTES_ITERATOR,
                    PyType.STR_ITERATOR,
                    PyType.STR_ASCII_ITERATOR);

    private static final String LENGTH_HINT_DOC = "Private method returning an estimate of len(list(it)).";
    private static final String REDUCE_DOC = "Return state information for pickling.";
    private static final String SETSTATE_DOC = "Set state information for unpickling.";

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final PyObject ONE = PyInt.of(1);
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject ZERO = PyInt.ZERO;
    private static final PyObject MINUS_ONE = PyInt.of(-1);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject TRUE = PyBool.TRUE;
    private static final PyObject TEXT = PyStr.of("2");
    private static final PyObject REAL = PyFloat.of(2.5);

    /** {@code 2 ** 63} and {@code 2 ** 70}: either is one bit past the word {@code Py_ssize_t} is. */
    private static final PyObject WIDE = PyInt.of(two(63));

    private static final PyObject WIDER = PyInt.of(two(70));

    private static final BigInteger ZERO_BI = BigInteger.ZERO;
    private static final BigInteger ONE_BI = BigInteger.ONE;
    private static final BigInteger TWO_BI = BigInteger.TWO;
    private static final BigInteger THREE_BI = BigInteger.valueOf(3);

    /** {@code [1, 2, 3]}: the three values every forward walk here is counted in. */
    private static final PyObject THREE_ITEMS = PyList.of(ONE, TWO, THREE);

    /** One keyword name, for the calls that argue about how they were written. */
    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    private static final PyTuple SELF = PyTuple.of(PyStr.interned("self"));

    private static BigInteger two(int exponent) {
        return BigInteger.valueOf(2).pow(exponent);
    }

    /** A fresh cursor of {@code owner}'s own kind, walked from the same three values where it can be. */
    private static PyObject cursor(PyType owner) {
        if (owner == PyType.LIST_ITERATOR) {
            return SequenceIterators.list(PyList.of(ONE, TWO, THREE));
        }
        if (owner == PyType.LIST_REVERSE_ITERATOR) {
            return SequenceIterators.listBackwards(PyList.of(ONE, TWO, THREE));
        }
        if (owner == PyType.TUPLE_ITERATOR) {
            return SequenceIterators.of(PyTuple.of(ONE, TWO, THREE));
        }
        if (owner == PyType.BYTES_ITERATOR) {
            return SequenceIterators.of(PyBytes.of(new byte[] {97, 98, 99}));
        }
        return SequenceIterators.of(PyStr.of(owner == PyType.STR_ITERATOR ? "a\u00e9c" : "abc"));
    }

    /** A cursor of some other one of the six, which every receiver check on this type's rows refuses. */
    private static PyObject cursorOtherThan(PyType owner) {
        for (PyType other : SIX) {
            if (other != owner) {
                return cursor(other);
            }
        }
        throw new AssertionError(owner);
    }

    /** The entry one of the six types files under {@code name}. */
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

    /** {@code sorted(set(dir(T)) - set(dir(object)))}, which is four names for all six. */
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

    /** {@code it.__length_hint__()}: the values still in this cursor. */
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

    /** {@code it.__setstate__(state)}, which is the only way to move a cursor without walking it. */
    private static PyObject setState(PyObject iterator, PyObject state) {
        return Slots.getAttr(iterator, "__setstate__").call(new PyObject[] {state}, null);
    }

    private static BigInteger setStateOf(PyObject iterator, PyObject state) {
        setState(iterator, state);
        return hint(iterator);
    }

    /** {@code it.__reduce__()}: the pickle payload, still holding the callable it named. */
    private static PyTuple reduce(PyObject iterator) {
        return (PyTuple) Slots.getAttr(iterator, "__reduce__").call(new PyObject[0], null);
    }

    /** The sequence a payload carries, which is the one item of its argument tuple. */
    private static PyObject sourceOf(PyObject iterator) {
        return ((PyTuple) reduce(iterator).at(1)).at(0);
    }

    private static List<String> walked(PyObject source) {
        List<String> read = new ArrayList<>();
        for (PyObject value : Constructors.materialise(source)) {
            read.add(value.repr());
        }
        return read;
    }

    /** {@code list(it)} over a text cursor, read as the characters rather than as their reprs. */
    private static List<String> characters(PyObject source) {
        List<String> read = new ArrayList<>();
        for (PyObject value : Constructors.materialise(source)) {
            read.add(((PyStr) value).getValue());
        }
        return read;
    }

    /** A list of three, so a growth or a cut is written where the walk can see it. */
    private static PyList listed() {
        return PyList.of(ONE, TWO, THREE);
    }

    /** {@code vars(T)[name](*args)}: a filed row, called without an attribute read. */
    private static PyObject call(PyType owner, String name, PyObject... args) {
        return Slots.call(entry(owner, name), args, null);
    }

    /** {@code vars(T)[name](*args, **kwargs)}. */
    private static PyObject calledWith(PyType owner, String name, PyTuple kwNames, PyObject... args) {
        return Slots.call(entry(owner, name), args, kwNames);
    }

    /** The value of a class that writes {@code __index__}, which none of the six converters asks. */
    private static PyObject indexAnswering(PyObject answer) {
        return withIndex(dict -> dict.put(
                PyStr.interned("__index__"), new PyNativeFunction("as_index", (args, kwNames) -> answer)));
    }

    /** The value of a class whose {@code __index__} refuses, which none of the six ever reaches. */
    private static PyObject indexRaising() {
        return withIndex(
                dict ->
                        dict.put(
                                PyStr.interned("__index__"),
                                new PyNativeFunction(
                                        "as_index",
                                        (args, kwNames) -> {
                                            throw Slots.valueError("boom");
                                        })));
    }

    private static PyObject withIndex(Consumer<PyDict> fill) {
        PyDict dict = PyDict.empty();
        fill.accept(dict);
        return Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of("Indexes"), PyTuple.of(PyType.OBJECT), dict},
                        null)
                .call(new PyObject[0], null);
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
     * Six dictionaries, six of them one shape.
     *
     * <p>The two slot rows and the three {@code PyMethodDef} rows are filed in the same order by all six
     * types, and the tail is the value {@code None} in all six because {@code tp_doc} is 0 in all six — the
     * same reason neither {@code T.__doc__} nor the dictionary entry is a sentence. {@code __iter__} and
     * {@code __next__} are slot wrappers, so no {@link Members} table files them; the other three are the
     * entries their own table describes, which is why the six tables hold twelve names between them and no
     * two of the six share a row object. {@code __new__} is filed by none of the six, because {@code tp_new}
     * is 0 in all of them, and a read of the name therefore walks to {@code object}.
     */
    @Test
    void sixDictionariesAreOneShapeFiledSixTimes() {
        assertThat(ORDER).hasSize(6).startsWith("__iter__").endsWith("__doc__");

        for (PyType owner : SIX) {
            String kind = owner.name();
            assertThat(filed(owner)).as(kind).isEqualTo(ORDER);
            assertThat(TypeDict.declared(owner).keys()).as(kind).hasSize(6);
            assertThat(Dirs.classNames(owner)).as(kind).hasSize(28);
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
            }

            // No constructor row, and so no way to name one from here.
            assertThat(entry(owner, "__new__")).as(kind).isNull();
            assertThat(ORDER).doesNotContain("__new__");
            assertThat(Slots.getAttr(owner, "__new__")).isSameAs(Slots.getAttr(PyType.OBJECT, "__new__"));
        }

        // One book, six copies: the same sentences, the same tables, six pairs of objects.
        PyType first = PyType.LIST_ITERATOR;
        for (String name : List.of("__iter__", "__next__", "__length_hint__", "__reduce__",
                                  "__setstate__")) {
            for (PyType owner : SIX) {
                assertThat(Slots.getAttr(entry(owner, name), "__doc__"))
                        .as(owner.name() + "." + name)
                        .isEqualTo(Slots.getAttr(entry(first, name), "__doc__"));
                assertThat(Slots.getAttr(entry(owner, name), "__text_signature__"))
                        .as(owner.name() + "." + name)
                        .isEqualTo(Slots.getAttr(entry(first, name), "__text_signature__"));
            }
            List<PyObject> copies = new ArrayList<>();
            for (PyType owner : SIX) {
                copies.add(entry(owner, name));
            }
            for (int a = 0; a < SIX.size(); a++) {
                for (int b = a + 1; b < SIX.size(); b++) {
                    assertThat(copies.get(b))
                            .as(name + " of " + SIX.get(a).name() + " and " + SIX.get(b).name())
                            .isNotSameAs(copies.get(a));
                }
            }
        }
        assertThat(Slots.getAttr(entry(first, "__setstate__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, object, /)"));
        for (String name : List.of("__iter__", "__next__", "__length_hint__", "__reduce__")) {
            assertThat(Slots.getAttr(entry(first, name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, /)"));
        }
        assertThat(Slots.getAttr(entry(first, "__length_hint__"), "__doc__")).isEqualTo(PyStr.of(LENGTH_HINT_DOC));
        assertThat(Slots.getAttr(entry(first, "__reduce__"), "__doc__")).isEqualTo(PyStr.of(REDUCE_DOC));
        assertThat(Slots.getAttr(entry(first, "__setstate__"), "__doc__")).isEqualTo(PyStr.of(SETSTATE_DOC));

        // Absent rather than filed: a cursor is not a sequence, not reversible and not pickled by itself.
        for (String name : List.of("__getnewargs__", "__reduce_ex__", "__len__", "__reversed__", "__dict__")) {
            assertThat(entry(first, name)).as(name).isNull();
            assertThat(ORDER).doesNotContain(name);
        }
    }

    /**
     * Six type names, chosen by the value walked and by nothing else.
     *
     * <p>{@code unicode_iter} asks the string which of its two cursors it wants, and the answer is the
     * storage: {@code PyUnicode_IS_COMPACT_ASCII} is what {@link PyStr#isAscii()} reads, so {@code ''} —
     * which has no units at all — takes the {@code str_ascii_iterator}, {@code '\u007f'} is the last
     * character that does and {@code '\u0080'} the first that does not. The other five types have one cursor
     * each, and {@code list_reverseiterator} is reached only through the list's own {@code __reversed__},
     * which is the hook the builtin {@code reversed} goes through rather than a snapshot the builtin makes.
     * None of the six can be built: {@code tp_new} is 0 in all of them, so the four roads that lead to a
     * constructor all say the type's own {@code tp_name}.
     */
    @Test
    void eachValueNamesTheCursorItWalksWith() {
        assertThat(THREE_ITEMS.iterator().getType()).isSameAs(PyType.LIST_ITERATOR);
        assertThat(PyList.empty().iterator().getType()).isSameAs(PyType.LIST_ITERATOR);
        assertThat(SequenceIterators.listBackwards(listed()).getType())
                .isSameAs(PyType.LIST_REVERSE_ITERATOR);
        assertThat(PyTuple.of(ONE, TWO).iterator().getType()).isSameAs(PyType.TUPLE_ITERATOR);
        assertThat(PyBytes.of(new byte[] {(byte) 0xff}).iterator().getType())
                .isSameAs(PyType.BYTES_ITERATOR);
        assertThat(PyStr.of("").isAscii()).isTrue();
        assertThat(PyStr.of("").iterator().getType()).isSameAs(PyType.STR_ASCII_ITERATOR);
        assertThat(PyStr.of("\u007f").isAscii()).isTrue();
        assertThat(PyStr.of("\u007f").iterator().getType()).isSameAs(PyType.STR_ASCII_ITERATOR);
        assertThat(PyStr.of("abc").iterator().getType()).isSameAs(PyType.STR_ASCII_ITERATOR);
        assertThat(PyStr.of("\u0080").isAscii()).isFalse();
        assertThat(PyStr.of("\u0080").iterator().getType()).isSameAs(PyType.STR_ITERATOR);
        assertThat(PyStr.of("\u00e9").iterator().getType()).isSameAs(PyType.STR_ITERATOR);
        assertThat(PyStr.of("a\uD83D\uDE00").iterator().getType()).isSameAs(PyType.STR_ITERATOR);

        // The builtin asks the list for its own cursor rather than making one out of the values.
        PyObject reversed = PyRuntime.builtins().get(PyStr.interned("reversed"));
        assertThat(Slots.call(reversed, new PyObject[] {listed()}, null).getType())
                .isSameAs(PyType.LIST_REVERSE_ITERATOR);

        for (PyType owner : SIX) {
            String kind = owner.name();
            refuses("TypeError: cannot create '" + kind + "' instances", () -> owner.call(new PyObject[0], null));
            refuses("TypeError: cannot create '" + kind + "' instances", () -> owner.call(new PyObject[] {ONE}, null));
            refuses("TypeError: cannot create '" + kind + "' instances", () -> owner.call(new PyObject[] {ONE}, X));
            refuses(
                    "TypeError: cannot create '" + kind + "' instances",
                    () -> Slots.call(Slots.getAttr(PyType.OBJECT, "__new__"), new PyObject[] {owner}, null));
            assertThat(TypeDict.declared(owner).get(PyStr.interned("__init__"))).as(kind).isNull();
        }

        // Six types, and no one of them a value of another.
        for (PyType owner : SIX) {
            PyObject own = cursor(owner);
            assertThat(PyType.OBJECT.instanceOf(own)).as(owner.name()).isTrue();
            assertThat(owner.instanceOf(own)).as(owner.name()).isTrue();
            assertThat(owner.instanceOf(cursorOtherThan(owner))).as(owner.name()).isFalse();
            assertThat(own.getType()).as(owner.name()).isSameAs(owner);
        }
    }

    /**
     * The two list cursors read the list again at every step, and the other four cannot.
     *
     * <p>{@code listiter_next} compares its index against {@code PyList_GET_SIZE} and so walks items
     * appended after the cursor was made, and stops short when the list is cut under it; there is no
     * complaint and no "changed size" refusal anywhere in the file. The four that hold a tuple, bytes or
     * text read a size that cannot move. What all six have in common is the end: the step that finds
     * nothing left drops the pointer, and after that the source is gone rather than merely passed — a list
     * grown back to length is not walked again.
     */
    @Test
    void onlyTheListCursorsLookAtTheListAgain() {
        PyList growing = listed();
        PyObject walkedForward = SequenceIterators.list(growing);
        assertThat(nextOf(walkedForward).repr()).isEqualTo("1");
        growing.add(FOUR);
        assertThat(walked(walkedForward)).isEqualTo(List.of("2", "3", "4"));
        assertThat(hint(walkedForward)).isEqualTo(ZERO_BI);

        PyList shrinking = PyList.of(ONE, TWO, THREE, FOUR);
        PyObject cut = SequenceIterators.list(shrinking);
        assertThat(nextOf(cut).repr()).isEqualTo("1");
        shrinking.getItems().subList(2, 4).clear();
        assertThat(hint(cut)).isEqualTo(ONE_BI);
        assertThat(walked(cut)).isEqualTo(List.of("2"));
        assertThat(reduce(cut).size()).isEqualTo(2);

        // The reversal starts below the last item, so an appended value is one it has already walked past.
        PyList appended = listed();
        PyObject backwards = SequenceIterators.listBackwards(appended);
        assertThat(nextOf(backwards).repr()).isEqualTo("3");
        appended.add(FOUR);
        assertThat(nextOf(backwards).repr()).isEqualTo("2");
        assertThat(hint(backwards)).isEqualTo(ONE_BI);

        // Cut below the index it is standing at, and the reversal spends itself the way the C does.
        PyList cutBack = PyList.of(ONE, TWO, THREE);
        PyObject shortBack = SequenceIterators.listBackwards(cutBack);
        nextOf(shortBack);
        cutBack.getItems().subList(1, 3).clear();
        assertThat(hint(shortBack)).isEqualTo(ZERO_BI);
        refuses("StopIteration: ", () -> nextOf(shortBack));
        assertThat(reduce(shortBack).size()).isEqualTo(2);

        // A spent list stays spent: the pointer, not the index, is what the walk gives up.
        PyList after = listed();
        PyObject spent = SequenceIterators.list(after);
        walked(spent);
        after.add(FOUR);
        assertThat(walked(spent)).isEmpty();
        assertThat(hint(spent)).isEqualTo(ZERO_BI);

        // A live cursor's payload names the source itself and the index it stands at; a spent one names a
        // fresh empty value, made for the answer and not the list the walk left behind.
        PyObject holding = SequenceIterators.list(after);
        nextOf(holding);
        assertThat(sourceOf(holding)).isSameAs(after);
        assertThat(((PyInt) reduce(holding).at(2)).getValue()).isEqualTo(ONE_BI);
        assertThat(sourceOf(spent)).isNotSameAs(after);
        assertThat(sourceOf(spent).getType().name()).isEqualTo("list");
        assertThat(((PyList) sourceOf(spent)).size()).isZero();
        assertThat(sourceOf(spent)).isNotSameAs(sourceOf(spent));
    }

    /**
     * What each family reports once it has dropped its source, which is where {@code bytes} differs.
     *
     * <p>{@code listiter_reduce_general} builds {@code "N(N)"} over a {@code PyList_New(0)} — an empty list
     * made for the answer, not the list the cursor held. {@code tupleiter_reduce} and {@code striter_reduce}
     * build {@code "N(())"}, an empty tuple, and the second of those is the octet cursor's own body: an
     * exhausted {@code bytes_iterator} pickles as {@code iter(())}, so it comes back as a walk over nothing
     * rather than over {@code b''}. The strings' own {@code unicodeiter_reduce} builds {@code "N(N)"} over
     * {@code unicode_get_empty()}, and of the six answers only {@code bytes}' is not a value of the type it
     * was made from.
     */
    @Test
    void aSpentCursorReportsAnEmptyValueOfItsOwnChoosing() {
        PyObject octets = SequenceIterators.of(PyBytes.of(new byte[] {97}));
        walked(octets);
        assertThat(octets.getType().name()).isEqualTo("bytes_iterator");
        assertThat(sourceOf(octets).getType().name()).isEqualTo("tuple");
        assertThat(((PyTuple) sourceOf(octets)).size()).isZero();

        PyObject tuple = SequenceIterators.of(PyTuple.of(ONE));
        walked(tuple);
        assertThat(((PyTuple) sourceOf(tuple)).size()).isZero();

        PyObject text = SequenceIterators.of(PyStr.of("a\u00e9"));
        walked(text);
        assertThat(text.getType().name()).isEqualTo("str_iterator");
        assertThat(((PyStr) sourceOf(text)).getValue()).isEmpty();

        PyObject ascii = SequenceIterators.of(PyStr.of("a"));
        walked(ascii);
        assertThat(ascii.getType().name()).isEqualTo("str_ascii_iterator");
        assertThat(((PyStr) sourceOf(ascii)).getValue()).isEmpty();

        PyObject list = SequenceIterators.list(PyList.of(ONE));
        walked(list);
        assertThat(((PyList) sourceOf(list)).size()).isZero();

        PyObject backwards = SequenceIterators.listBackwards(PyList.of(ONE));
        walked(backwards);
        assertThat(((PyList) sourceOf(backwards)).size()).isZero();
        assertThat(reduce(backwards).at(0))
                .isSameAs(PyRuntime.builtins().get(PyStr.interned("reversed")));

        // A cursor of nothing is spent in one family and not in the other: the reversal of an empty list
        // starts at -1, which is the marker, while the other five start at 0 and still hold their source.
        assertThat(reduce(SequenceIterators.list(PyList.empty())).size()).isEqualTo(3);
        assertThat(reduce(SequenceIterators.listBackwards(PyList.empty())).size()).isEqualTo(2);
        assertThat(reduce(SequenceIterators.of(PyTuple.EMPTY)).size()).isEqualTo(3);
        assertThat(reduce(SequenceIterators.of(PyStr.of(""))).size()).isEqualTo(3);
        assertThat(reduce(SequenceIterators.of(PyBytes.of(new byte[0]))).size()).isEqualTo(3);
        assertThat(hint(SequenceIterators.listBackwards(PyList.empty()))).isEqualTo(ZERO_BI);
        assertThat(((PyInt) reduce(SequenceIterators.list(PyList.empty())).at(2)).getValue())
                .isEqualTo(ZERO_BI);

        // And the number that spends a list cursor by clipping leaves the pointer in place, so a later
        // number puts the walk back: only a step that finds nothing left ends it for good.
        PyObject clipped = cursor(PyType.LIST_ITERATOR);
        setState(clipped, MINUS_ONE);
        assertThat(reduce(clipped).size()).isEqualTo(2);
        setState(clipped, ONE);
        assertThat(reduce(clipped).size()).isEqualTo(3);
        assertThat(walked(clipped)).isEqualTo(List.of("2", "3"));
        PyObject clippedBack = cursor(PyType.LIST_REVERSE_ITERATOR);
        setState(clippedBack, MINUS_ONE);
        setState(clippedBack, ONE);
        assertThat(walked(clippedBack)).isEqualTo(List.of("2", "1"));

        // Live, and the payload is three items: the callable, the one-item argument tuple, the index.
        PyTuple live = reduce(THREE_ITEMS.iterator());
        assertThat(live.size()).isEqualTo(3);
        assertThat(live.at(1).getType().name()).isEqualTo("tuple");
        assertThat(((PyTuple) live.at(1)).size()).isEqualTo(1);
        assertThat(((PyInt) live.at(2)).getValue()).isEqualTo(ZERO_BI);
        assertThat(live.at(0)).isSameAs(PyRuntime.builtins().get(PyStr.interned("iter")));
    }

    /**
     * The hint counts what is left, and none of the six files a length.
     *
     * <p>Five of the six read a count of values still in front of them and the sixth — the reversal —
     * reads its own index plus one, which is the same answer until the list is cut shorter than that. Both
     * say nought once the pointer is gone, and neither is ever a {@code __len__}: no row is filed for it, so
     * {@code len()} of a cursor is refused in the sentence that names the type rather than in a count.
     */
    @Test
    void theHintCountsWhatIsLeftAndNoTypeFilesALength() {
        List<BigInteger> three = List.of(THREE_BI, TWO_BI, ONE_BI, ZERO_BI, ZERO_BI);
        for (PyType owner : SIX) {
            String kind = owner.name();
            assertThat(ladder(cursor(owner))).as(kind).isEqualTo(three);
            assertThat(entry(owner, "__len__")).as(kind).isNull();
            assertThat(ORDER).doesNotContain("__len__");
            refuses("TypeError: object of type '" + kind + "' has no len()", () -> Slots.length(cursor(owner)));
        }

        assertThat(hint(cursor(PyType.LIST_ITERATOR))).isEqualTo(THREE_BI);
        assertThat(hint(SequenceIterators.of(PyStr.of("")))).isEqualTo(ZERO_BI);
        assertThat(hint(SequenceIterators.of(PyStr.of("ab")))).isEqualTo(TWO_BI);
        assertThat(Slots.getAttr(listed().iterator(), "__length_hint__")
                .call(new PyObject[0], null).getType())
                .isSameAs(PyType.INT);

        // A walk that never was still counts: the reversal of an empty list starts at -1.
        assertThat(ladder(SequenceIterators.list(PyList.empty())))
                .isEqualTo(List.of(ZERO_BI, ZERO_BI, ZERO_BI, ZERO_BI, ZERO_BI));
        assertThat(walked(cursor(PyType.LIST_REVERSE_ITERATOR))).isEqualTo(List.of("3", "2", "1"));
        assertThat(walked(cursor(PyType.BYTES_ITERATOR))).isEqualTo(List.of("97", "98", "99"));
        assertThat(characters(cursor(PyType.STR_ITERATOR))).isEqualTo(List.of("a", "\u00e9", "c"));
        assertThat(characters(cursor(PyType.STR_ASCII_ITERATOR))).isEqualTo(List.of("a", "b", "c"));

        // The reversal counts its own index plus one, so a cut shorter than that index answers nought
        // while the cursor itself has not yet found the end.
        PyList cut = listed();
        PyObject backwards = SequenceIterators.listBackwards(cut);
        nextOf(backwards);
        cut.getItems().subList(2, 3).clear();
        assertThat(hint(backwards)).isEqualTo(TWO_BI);
        cut.getItems().clear();
        assertThat(hint(backwards)).isEqualTo(ZERO_BI);
    }

    /**
     * {@code __setstate__} converts first, guards second, and clips instead of complaining.
     *
     * <p>All six read their index through {@code PyLong_AsSsize_t}, which is the narrow converter: it asks
     * an {@code int} and so takes a bool and a subclass but refuses anything with an {@code __index__} —
     * {@code an integer is required}, and a value whose {@code __index__} raises never gets asked. It is the
     * same converter slice 22 measured for a count, and it overflows at sixty-four bits. Then the guard on
     * {@code it_seq} decides whether the number is used at all, which is why a spent cursor still refuses a
     * float and then ignores an {@code int}. And no index is out of range: the three windows are {@code
     * [-1, len]} for a forward cursor, {@code [-1, len - 1]} for a reversal, and {@code [0, len]} for the
     * four that hold something immutable.
     */
    @Test
    void setstateConvertsThenGuardsAndThenClips() {
        for (PyType owner : SIX) {
            String kind = owner.name();
            assertThat(setState(cursor(owner), ZERO)).as(kind).isSameAs(NONE);
            for (PyObject refused : List.of(REAL, TEXT, NONE, PyList.of(ONE), indexAnswering(ONE),
                                            indexRaising())) {
                refuses("TypeError: an integer is required", () -> setState(cursor(owner), refused));
            }
            for (PyObject tooWide : List.of(WIDE, WIDER, PyInt.of(two(63).negate().subtract(ONE_BI)))) {
                refuses("OverflowError: Python int too large to convert to C ssize_t",
                        () -> setState(cursor(owner), tooWide));
            }
            // The widest number that does fit, on both sides of nought.
            assertThat(setState(cursor(owner), PyInt.of(BigInteger.valueOf(Long.MIN_VALUE))))
                    .as(kind)
                    .isSameAs(NONE);

            // A bool is an int to this converter, and the cursor moves by one.
            assertThat(setStateOf(cursor(owner), TRUE)).as(kind).isEqualTo(TWO_BI);
        }

        // The forward window reaches one past the end, which is a cursor with nothing left but a source.
        PyObject forward = cursor(PyType.LIST_ITERATOR);
        assertThat(setStateOf(forward, FOUR)).isEqualTo(ZERO_BI);
        assertThat(reduce(forward).size()).isEqualTo(3);
        assertThat(((PyInt) reduce(forward).at(2)).getValue()).isEqualTo(THREE_BI);
        assertThat(sourceOf(forward)).isEqualTo(THREE_ITEMS);

        // Below the window the two list cursors spend themselves and the four others restart.
        PyObject clipped = cursor(PyType.LIST_ITERATOR);
        assertThat(setStateOf(clipped, MINUS_ONE)).isEqualTo(ZERO_BI);
        assertThat(reduce(clipped).size()).isEqualTo(2);
        assertThat(setStateOf(clipped, ONE)).isEqualTo(TWO_BI);
        assertThat(nextOf(clipped).repr()).isEqualTo("2");

        PyObject backwards = cursor(PyType.LIST_REVERSE_ITERATOR);
        assertThat(setStateOf(backwards, MINUS_ONE)).isEqualTo(ZERO_BI);
        assertThat(setStateOf(backwards, FOUR)).isEqualTo(THREE_BI);
        assertThat(setStateOf(backwards, PyInt.of(-4))).isEqualTo(ZERO_BI);
        assertThat(nextOf(setstateAndReturn(cursor(PyType.LIST_REVERSE_ITERATOR), ONE))).isEqualTo(TWO);

        // The four immutable cursors clip at 0, so a negative index restarts them rather than spending them.
        for (PyType owner : List.of(PyType.TUPLE_ITERATOR, PyType.BYTES_ITERATOR,
                                    PyType.STR_ITERATOR, PyType.STR_ASCII_ITERATOR)) {
            String kind = owner.name();
            PyObject fixed = cursor(owner);
            assertThat(setStateOf(fixed, MINUS_ONE)).as(kind).isEqualTo(THREE_BI);
            assertThat(setStateOf(cursor(owner), PyInt.of(-70))).as(kind).isEqualTo(THREE_BI);
            PyObject ended = cursor(owner);
            assertThat(setStateOf(ended, FOUR)).as(kind).isEqualTo(ZERO_BI);
            assertThat(reduce(ended).size()).as(kind).isEqualTo(3);
            assertThat(((PyInt) reduce(ended).at(2)).getValue()).as(kind).isEqualTo(THREE_BI);
        }

        // The conversion comes before the guard, so a spent cursor still refuses and still ignores.
        PyObject spent = cursor(PyType.TUPLE_ITERATOR);
        walked(spent);
        refuses("TypeError: an integer is required", () -> setState(spent, REAL));
        refuses("OverflowError: Python int too large to convert to C ssize_t", () -> setState(spent, WIDE));
        assertThat(setState(spent, ONE)).isSameAs(NONE);
        assertThat(hint(spent)).isEqualTo(ZERO_BI);
        assertThat(reduce(spent).size()).isEqualTo(2);

        PyObject spentList = cursor(PyType.LIST_ITERATOR);
        walked(spentList);
        refuses("TypeError: an integer is required", () -> setState(spentList, REAL));
        assertThat(setState(spentList, ZERO)).isSameAs(NONE);
        assertThat(hint(spentList)).isEqualTo(ZERO_BI);
        assertThat(((PyList) sourceOf(spentList)).size()).isZero();

        // Arity, in the row's own words: one argument, no keyword, and the row itself is not a bound method.
        for (PyType owner : SIX) {
            String kind = owner.name();
            refuses(
                    "TypeError: " + kind + ".__setstate__() takes exactly one argument (0 given)",
                    () -> call(owner, "__setstate__", cursor(owner)));
            refuses(
                    "TypeError: " + kind + ".__setstate__() takes exactly one argument (2 given)",
                    () -> call(owner, "__setstate__", cursor(owner), ONE, TWO));
            refuses(
                    "TypeError: " + kind + ".__setstate__() takes no keyword arguments",
                    () -> calledWith(owner, "__setstate__", X, cursor(owner), ONE));
            refuses(
                    "TypeError: " + kind + ".__length_hint__() takes no arguments (1 given)",
                    () -> call(owner, "__length_hint__", cursor(owner), ONE));
            refuses(
                    "TypeError: " + kind + ".__reduce__() takes no keyword arguments",
                    () -> calledWith(owner, "__reduce__", X, cursor(owner), ONE));
        }
    }

    /** {@code it.__setstate__(state)} and then the cursor, whose kind is what the next read depends on. */
    private static PyObject setstateAndReturn(PyObject iterator, PyObject state) {
        setState(iterator, state);
        return iterator;
    }

    /**
     * A row of one cursor refuses a cursor of another, in two different sentences.
     *
     * <p>{@code wrapperdescr_call} counts its receiver and says {@code requires a 'X' object but received a
     * 'Y'}, while {@code descr_check} — the gate slice 63 measured on the two range cursors — says
     * {@code descriptor 'X' for 'Y' objects doesn't apply to a 'Z' object}, and both test the value's own
     * type rather than an {@code __instancecheck__}. Six types instead of two is the whole difference here:
     * a list cursor is refused by the tuple rows and a tuple cursor by the list rows, in the same words with
     * the neighbouring name in them. The receiver still comes before the count, so {@code
     * vars(T)['__next__']('x', 1)} complains about the str and not about the two arguments.
     */
    @Test
    void eachRowRefusesTheOtherFiveCursors() {
        for (PyType owner : SIX) {
            String kind = owner.name();
            PyObject own = cursor(owner);
            PyObject other = cursorOtherThan(owner);

            for (String name : SLOTS) {
                refuses(
                        "TypeError: descriptor '" + name + "' of '" + kind + "' object needs an argument",
                        () -> call(owner, name));
                refuses(
                        "TypeError: descriptor '" + name + "' of '" + kind + "' object needs an argument",
                        () -> calledWith(owner, name, SELF, own));
                refuses(
                        "TypeError: descriptor '" + name + "' requires a '" + kind + "' object"
                                + " but received a 'int'",
                        () -> call(owner, name, ZERO));
                refuses(
                        "TypeError: descriptor '" + name + "' requires a '" + kind + "' object"
                                + " but received a '" + other.getType().name() + "'",
                        () -> call(owner, name, other));
                refuses("TypeError: expected 0 arguments, got 1", () -> call(owner, name, own, ONE));
                refuses(
                        "TypeError: wrapper " + name + "() takes no keyword arguments",
                        () -> calledWith(owner, name, X, own, ONE));
                if (name.equals("__iter__")) {
                    assertThat(call(owner, name, own)).as(kind + "." + name).isSameAs(own);
                } else {
                    // The one row that moves a cursor moves it the same way unbound as bound, and hands
                    // back the value rather than the cursor it stepped.
                    assertThat(call(owner, name, cursor(owner)).repr())
                            .as(kind + "." + name)
                            .isEqualTo(nextOf(cursor(owner)).repr());
                }
            }
            for (String name : BARE) {
                refuses(
                        "TypeError: unbound method " + kind + "." + name + "() needs an argument",
                        () -> call(owner, name));
                refuses(
                        "TypeError: descriptor '" + name + "' for '" + kind + "' objects"
                                + " doesn't apply to a 'int' object",
                        () -> call(owner, name, ZERO));
                refuses(
                        "TypeError: descriptor '" + name + "' for '" + kind + "' objects"
                                + " doesn't apply to a '" + other.getType().name() + "' object",
                        () -> call(owner, name, other));
                refuses(
                        "TypeError: " + kind + "." + name + "() takes no arguments (1 given)",
                        () -> call(owner, name, own, ONE));
                refuses(
                        "TypeError: " + kind + "." + name + "() takes no keyword arguments",
                        () -> calledWith(owner, name, X, own, ONE));
            }
            assertThat(((PyInt) call(owner, "__length_hint__", own)).getValue()).isEqualTo(THREE_BI);
            assertThat(call(owner, "__setstate__", own, ONE)).isSameAs(NONE);
            assertThat(Slots.call(entry(owner, "__reduce__"), new PyObject[] {cursor(owner)}, null).getType())
                    .as(kind)
                    .isSameAs(PyType.TUPLE);

            // Both families are descriptors, and a read with no value hands the row back unchanged.
            for (String name : SLOTS) {
                assertThat(Slots.call(Slots.getAttr(entry(owner, name), "__get__"),
                        new PyObject[] {NONE, owner}, null)).as(kind + "." + name).isSameAs(entry(owner, name));
            }
        }

        // __iter__ is the slot that makes a cursor its own iterator, six times over.
        for (PyType owner : SIX) {
            PyObject own = cursor(owner);
            assertThat(own.iterator()).as(owner.name()).isSameAs(own);
        }
    }

    /**
     * A read off a value binds, and a read off the type does not.
     *
     * <p>The two slot rows bind to {@code method-wrapper}s and the three table rows to {@code
     * builtin_function_or_method}s, each made afresh by the read, and the bound method's {@code __qualname__}
     * is the cursor type's name with the row's after it — {@code bytes_iterator.__reduce__}, {@code
     * str_ascii_iterator.__setstate__}. Every one of the six types is a value's own, so what a bound read
     * names is the type the value was made from and nothing else.
     */
    @Test
    void aBoundReadNamesTheTypeTheValueWasMadeFrom() {
        for (PyType owner : SIX) {
            String kind = owner.name();
            PyObject value = cursor(owner);
            for (String name : SLOTS) {
                PyObject bound = Slots.getAttr(value, name);
                assertThat(bound.getType().name()).as(kind + "." + name).isEqualTo("method-wrapper");
                assertThat(Slots.getAttr(bound, "__self__")).as(kind + "." + name).isSameAs(value);
                assertThat(Slots.getAttr(bound, "__name__")).as(kind + "." + name).isEqualTo(PyStr.of(name));
                assertThat(Slots.getAttr(value, name)).as(kind + "." + name)
                        .isNotSameAs(Slots.getAttr(value, name));
                assertThat(bound).isNotSameAs(entry(owner, name));
            }
            for (String name : METHODS) {
                PyObject bound = Slots.getAttr(value, name);
                assertThat(bound.getType().name()).as(kind + "." + name)
                        .isEqualTo("builtin_function_or_method");
                assertThat(Slots.getAttr(bound, "__self__")).as(kind + "." + name).isSameAs(value);
                assertThat(Slots.getAttr(bound, "__qualname__")).as(kind + "." + name)
                        .isEqualTo(PyStr.of(kind + "." + name));
                assertThat(Slots.getAttr(bound, "__doc__"))
                        .as(kind + "." + name)
                        .isEqualTo(Slots.getAttr(entry(owner, name), "__doc__"));
                assertThat(Slots.getAttr(owner, name)).as(kind + "." + name).isSameAs(entry(owner, name));
            }
            refuses(
                    "AttributeError: '" + kind + "' object has no attribute 'send'",
                    () -> Slots.getAttr(value, "send"));
            refuses(
                    "AttributeError: '" + kind + "' object has no attribute '__dict__'",
                    () -> Slots.getAttr(value, "__dict__"));
            refuses(
                    "TypeError: vars() argument must have __dict__ attribute",
                    () -> Slots.call(
                            PyRuntime.builtins().get(PyStr.interned("vars")), new PyObject[] {value}, null));
            refuses(
                    "AttributeError: '" + kind + "' object has no attribute 'extra'"
                            + " and no __dict__ for setting new attributes",
                    () -> Slots.setAttr(value, "extra", ONE));
        }
    }

    /**
     * The name in a payload is looked up when the payload is made.
     *
     * <p>{@code _PyEval_GetBuiltin} is called inside {@code Xiter_reduce}, after nothing and before the
     * pointers are read — issue {@code #101765} is why the call comes first in {@code
     * listiter_reduce_general}, since the name may run arbitrary code on the way. So rebinding {@code iter}
     * in {@code builtins} changes what five of the six payloads carry, rebinding {@code reversed} changes the
     * sixth, and deleting either leaves the reduce with nothing to name.
     */
    @Test
    void thePayloadAsksTheBuiltinsDictionaryAtTheCall() {
        PyDict namespace = PyRuntime.builtins();
        PyObject realIter = namespace.get(PyStr.interned("iter"));
        PyObject realReversed = namespace.get(PyStr.interned("reversed"));
        PyObject replacement = new PyNativeFunction("replacement", (args, kwNames) -> NONE);
        try {
            namespace.put(PyStr.interned("iter"), replacement);
            for (PyType owner : List.of(PyType.LIST_ITERATOR, PyType.TUPLE_ITERATOR, PyType.BYTES_ITERATOR,
                                         PyType.STR_ITERATOR, PyType.STR_ASCII_ITERATOR)) {
                assertThat(reduce(cursor(owner)).at(0)).as(owner.name()).isSameAs(replacement);
            }
            assertThat(reduce(cursor(PyType.LIST_REVERSE_ITERATOR)).at(0))
                    .isSameAs(realReversed);

            PyObject other = new PyNativeFunction("other", (args, kwNames) -> NONE);
            namespace.put(PyStr.interned("reversed"), other);
            assertThat(reduce(cursor(PyType.LIST_REVERSE_ITERATOR)).at(0)).isSameAs(other);
            assertThat(reduce(cursor(PyType.LIST_ITERATOR)).at(0)).isSameAs(replacement);

            namespace.put(PyStr.interned("iter"), realIter);
            namespace.put(PyStr.interned("reversed"), realReversed);
            namespace.delItem(PyStr.interned("iter"));
            refuses(
                    "AttributeError: iter",
                    () -> reduce(SequenceIterators.list(listed())));
            refuses(
                    "AttributeError: iter",
                    () -> reduce(SequenceIterators.of(PyTuple.of(ONE))));
        } finally {
            namespace.put(PyStr.interned("iter"), realIter);
            namespace.put(PyStr.interned("reversed"), realReversed);
        }
        assertThat(reduce(cursor(PyType.LIST_ITERATOR)).at(0)).isSameAs(realIter);
        assertThat(reduce(cursor(PyType.LIST_REVERSE_ITERATOR)).at(0)).isSameAs(realReversed);
        assertThat(reduce(cursor(PyType.LIST_REVERSE_ITERATOR)).size()).isEqualTo(3);
    }
}
