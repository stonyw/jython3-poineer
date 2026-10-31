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
 * The six names each of the two range cursors files, measured against CPython 3.14.10.
 *
 * <p>Corpus case 112 reads both dictionaries from Python, one line per answer. What is pinned here is the half a
 * script cannot point at, and the first of it is that one dictionary is two. {@code range_iterator} and {@code
 * longrange_iterator} file the same six names in the same order — {@code tp_iter} and {@code tp_iternext} copied
 * out as slot wrappers, then the three rows of {@code rangeiter_methods} (Objects/rangeobject.c:910) or of {@code
 * longrangeiter_methods} ({@code :1087}), which are the same list written twice — and the only difference a read
 * can see is which type each {@code __objclass__} names. Neither files {@code __new__}, because neither has a
 * {@code tp_new}, and neither files a sentence, because {@code tp_doc} is zero in both, which is what makes the
 * {@code __doc__} row the value {@code None} rather than a string.
 *
 * <p>Then the five gates {@code range_iter} runs before it builds the fast cursor, and the two more {@code
 * range_reverse} runs before it builds a fast one walking back: the third of the five is the step the cursor takes
 * after its last value, and it is a guard on a walk the reversal does not make, so the same range is wide one way
 * and narrow the other. Then the two halves' arithmetic agreeing — every cursor's {@code __reduce__} hands back the
 * range of the values still in it, computed in C longs by one and in Python integers by the other. Then the two
 * {@code __setstate__}s, which are the one place the bodies disagree in words: the fast half converts its index and
 * so takes a bool and an {@code __index__} and overflows at sixty-four bits, and the wide half compares its type and
 * so refuses both and clips a number of any width. Then the three gates of {@code wrapperdescr_call} against the
 * three of {@code method_check_args}, whose second and third answer in different sentences depending on whether the
 * row is a slot or a method — and which refuse a cursor of the other half, so the two dictionaries check their
 * receiver against the type each {@code __objclass__} names. And last the cursor's own end, which is a bare {@code
 * StopIteration} with nothing carried by it, and the nowhere a cursor has to keep an attribute.
 */
class RangeIteratorTableTest {

    /** The six names each cursor files, in the order its dictionary answers them. */
    private static final List<String> ORDER =
            List.of("__iter__", "__next__", "__length_hint__", "__reduce__", "__setstate__", "__doc__");

    /** The two rows {@code tp_iter} and {@code tp_iternext} are copied out as. */
    private static final List<String> SLOTS = List.of("__iter__", "__next__");

    /** The three rows each of the two C method lists files, in the order it writes them. */
    private static final List<String> METHODS = List.of("__length_hint__", "__reduce__", "__setstate__");

    /** The two rows {@code rangeiter_methods} files as {@code METH_NOARGS}. */
    private static final List<String> BARE = List.of("__length_hint__", "__reduce__");

    /** The four names {@code dir()} gains over {@code object}, out of the six. */
    private static final List<String> OWN =
            List.of("__iter__", "__length_hint__", "__next__", "__setstate__");

    /** The two cursors, as {@code range_iter} and {@code longrange_iter} name them. */
    private static final List<PyType> BOTH = List.of(PyType.RANGE_ITERATOR, PyType.LONG_RANGE_ITERATOR);

    private static final String FAST_NAME = "range_iterator";
    private static final String WIDE_NAME = "longrange_iterator";

    /** What the fifth read of a four-value cursor says, which is the whole of a bare {@code StopIteration}. */
    private static final String END = "StopIteration: ";

    private static final String LENGTH_HINT_DOC = "Private method returning an estimate of len(list(it)).";

    private static final String REDUCE_DOC = "Return state information for pickling.";

    private static final String SETSTATE_DOC = "Set state information for unpickling.";

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final BigInteger ONE_BI = BigInteger.ONE;
    private static final BigInteger TWO_BI = BigInteger.TWO;
    private static final BigInteger ZERO_BI = BigInteger.ZERO;
    private static final BigInteger THREE_BI = BigInteger.valueOf(3);
    private static final BigInteger TEN_BI = BigInteger.TEN;

    private static final PyObject ZERO = PyInt.ZERO;
    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject TEN = PyInt.of(10);
    private static final PyObject MINUS_ONE = PyInt.of(-1);

    /** {@code 2 ** 70}: the width a C {@code long} cannot hold, which both a bound and an index can. */
    private static final PyObject WIDE = PyInt.of(two(70));

    private static final PyObject TRUE = PyBool.TRUE;

    private static final PyObject TEXT = PyStr.of("2");

    private static final PyObject REAL = PyFloat.of(2.0);

    /** {@code 2 ** 63 - 1} and {@code -2 ** 63}: the two ends of the word the fast cursor counts in. */
    private static final BigInteger LAST = BigInteger.valueOf(Long.MAX_VALUE);

    private static final BigInteger FIRST = BigInteger.valueOf(Long.MIN_VALUE);

    /** {@code range(1, 9, 2)}: the four values every walk here is counted in. */
    private static final PyRange ODD = range(1, 9, 2);

    /** {@code range(0, 2 ** 70, 2 ** 69)}: two values, and a bound no word holds. */
    private static final PyRange FAR = range(ZERO_BI, two(70), two(69));

    /** {@code range(0)}: the empty range, whose cursor is the fast one and whose walk is nothing. */
    private static final PyRange EMPTY = range(0, 0, 1);

    /** The two cursors, read the way Python reads them. */
    private static final PyObject FAST = ODD.iterator();

    private static final PyObject WIDE_CURSOR = FAR.iterator();

    /** One keyword name, for the calls that argue about how they were written rather than about length. */
    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    private static final PyTuple SELF = PyTuple.of(PyStr.interned("self"));

    /** {@code 2 ** exponent}, the width every wide bound here is written at. */
    private static BigInteger two(int exponent) {
        return BigInteger.valueOf(2).pow(exponent);
    }

    /** {@code range(start, stop, step)} as three machine words. */
    private static PyRange range(long start, long stop, long step) {
        return PyRange.of(BigInteger.valueOf(start), BigInteger.valueOf(stop), BigInteger.valueOf(step));
    }

    /** {@code range(start, stop, step)} at any width. */
    private static PyRange range(BigInteger start, BigInteger stop, BigInteger step) {
        return PyRange.of(start, stop, step);
    }

    /** The cursor {@code range(start, stop, step)} hands out. */
    private static PyObject cursor(long start, long stop, long step) {
        return range(start, stop, step).iterator();
    }

    private static PyObject cursor(BigInteger start, BigInteger stop, BigInteger step) {
        return range(start, stop, step).iterator();
    }

    /** {@code reversed(range(start, stop, step))}, which is {@code range_reverse} over the same three bounds. */
    private static PyObject reversed(BigInteger start, BigInteger stop, BigInteger step) {
        return RangeIterators.reversed(range(start, stop, step));
    }

    /** The name of the cursor a range answers, which is the whole of what the two widths look like. */
    private static String widthOf(PyObject source) {
        return source.getType().name();
    }

    /** A cursor of {@code owner}'s own kind, for the rows that want one as their receiver. */
    private static PyObject cursorOf(PyType owner) {
        return owner == PyType.RANGE_ITERATOR ? ODD.iterator() : FAR.iterator();
    }

    /** A cursor of the other kind, which every receiver check on this type's rows refuses. */
    private static PyObject cursorOtherThan(PyType owner) {
        return owner == PyType.RANGE_ITERATOR ? FAR.iterator() : ODD.iterator();
    }

    /** The entry one of the two cursors' types files under {@code name}. */
    private static PyObject entry(PyType owner, String name) {
        return TypeDict.declared(owner).get(PyStr.interned(name));
    }

    /** The names a kind's own dictionary keeps, in the order the dictionary kept them. */
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

    /** {@code sorted(set(dir(T)) - set(dir(object)))}: the four names a cursor is not inherited for. */
    private static List<String> gained(PyType owner) {
        List<String> names = new ArrayList<>(Dirs.classNames(owner));
        names.removeAll(Dirs.classNames(PyType.OBJECT));
        Collections.sort(names);
        return names;
    }

    /** {@code it.__next__()}: one step of a cursor, read as an attribute and then called. */
    private static PyObject nextOf(PyObject iterator) {
        return Slots.getAttr(iterator, "__next__").call(new PyObject[0], null);
    }

    /** {@code it.__length_hint__()}: the values still in this cursor. */
    private static BigInteger hint(PyObject iterator) {
        return ((PyInt) Slots.getAttr(iterator, "__length_hint__").call(new PyObject[0], null)).getValue();
    }

    /** {@code it.__setstate__(state)}, which is the only way to move a cursor without walking it. */
    private static PyObject setState(PyObject iterator, PyObject state) {
        return Slots.getAttr(iterator, "__setstate__").call(new PyObject[] {state}, null);
    }

    /** The range a cursor's {@code __reduce__} says its remaining values are. */
    private static PyRange restOf(PyObject iterator) {
        PyTuple state = (PyTuple) Slots.getAttr(iterator, "__reduce__").call(new PyObject[0], null);
        return (PyRange) ((PyTuple) state.at(1)).at(0);
    }

    /** The three bounds of a range, as the numbers a reduction has to reproduce. */
    private static List<BigInteger> bounds(PyRange source) {
        return List.of(source.start().getValue(), source.stop().getValue(), source.step().getValue());
    }

    /** {@code vars(T)[name](*args)}: a filed row, called without an attribute read. */
    private static PyObject call(PyType owner, String name, PyObject... args) {
        return Slots.call(entry(owner, name), args, null);
    }

    /** {@code vars(T)[name](*args, **kwargs)}, which is what sends a row to its argument parser. */
    private static PyObject calledWith(PyType owner, String name, PyTuple kwNames, PyObject... args) {
        return Slots.call(entry(owner, name), args, kwNames);
    }

    /** The value of a class that writes {@code __index__} itself, which one half of the pair then asks. */
    private static PyObject indexAnswering(String name, PyObject answer) {
        return withIndex(name, dict -> dict.put(
                PyStr.interned("__index__"), new PyNativeFunction(name, (args, kwNames) -> answer)));
    }

    /** The value of a class whose {@code __index__} refuses, which one half asks and the other never does. */
    private static PyObject indexRaising(String name) {
        return withIndex(
                name,
                dict ->
                        dict.put(
                                PyStr.interned("__index__"),
                                new PyNativeFunction(
                                        name,
                                        (args, kwNames) -> {
                                            throw Slots.valueError("boom");
                                        })));
    }

    /** A class over {@code object} with only the {@code __index__} a cursor half asks, then one of each. */
    private static PyObject withIndex(String name, Consumer<PyDict> fill) {
        PyDict dict = PyDict.empty();
        fill.accept(dict);
        return Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(PyType.OBJECT), dict}, null)
                .call(new PyObject[0], null);
    }

    /** {@code list(it)} over a cursor's own walk, as the decimal strings the answers can be compared by. */
    private static List<String> walked(PyObject source) {
        List<String> digits = new ArrayList<>();
        for (PyObject value : Constructors.materialise(source)) {
            digits.add(((PyInt) value).getValue().toString());
        }
        return digits;
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
     * Six rows, filed twice over, in the order the two slot entries and one C method list wrote them.
     *
     * <p>The two dictionaries are two objects and one shape: the same six names in the same order, twenty-eight
     * names in the same {@code dir()}, the same four sentences, the same invented tables, and the four method rows
     * of each the very objects {@link Members} describes — so {@code range_iterator.__reduce__} and {@code
     * longrange_iterator.__reduce__} differ in their {@code __objclass__} and in nothing else a read can reach. Both
     * types are one deep, named for the C {@code tp_name} that is the whole of their name, and both answer {@code
     * None} for a sentence because {@code tp_doc} is zero in either. Two of the six rows are wrappers and four are
     * methods, and only the four methods are {@link Members} table entries: the two wrappers are slots, which no
     * {@code PyMethodDef} files.
     */
    @Test
    void theSixRowsAreTwoSlotEntriesAndOneMethodListWrittenTwice() {
        assertThat(ORDER).hasSize(6).startsWith("__iter__").endsWith("__doc__");
        assertThat(ORDER.indexOf("__next__") - ORDER.indexOf("__iter__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__length_hint__") - ORDER.indexOf("__next__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__setstate__") - ORDER.indexOf("__reduce__")).isEqualTo(1);

        for (PyType owner : BOTH) {
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
                    .containsExactlyInAnyOrderElementsOf(METHODS);

            for (String name : SLOTS) {
                assertThat(entry(owner, name).getType().name())
                        .as(kind + "." + name)
                        .isEqualTo("wrapper_descriptor");
                assertThat(entry(owner, name)).as(kind + "." + name).isSameAs(Slots.getAttr(owner, name));
            }
            assertThat(Slots.getAttr(entry(owner, "__iter__"), "__doc__"))
                    .as(kind)
                    .isEqualTo(PyStr.of("Implement iter(self)."));
            assertThat(Slots.getAttr(entry(owner, "__next__"), "__doc__"))
                    .as(kind)
                    .isEqualTo(PyStr.of("Implement next(self)."));
            for (String name : METHODS) {
                assertThat(entry(owner, name).getType().name())
                        .as(kind + "." + name)
                        .isEqualTo("method_descriptor");
                assertThat(entry(owner, name)).as(kind + "." + name).isSameAs(Slots.getAttr(owner, name));
                assertThat(entry(owner, name)).as(kind + "." + name)
                        .isSameAs(Members.tableOf(owner).describe(name));
            }
            // Neither constructor row nor a slot row is filed, so a read of __new__ walks to object's.
            assertThat(entry(owner, "__new__")).as(kind).isNull();
            assertThat(ORDER).doesNotContain("__new__");
            assertThat(Slots.getAttr(owner, "__new__")).isSameAs(Slots.getAttr(PyType.OBJECT, "__new__"));
            assertThat(Slots.getAttr(owner, "__new__").getType().name())
                    .as(kind)
                    .isEqualTo("builtin_function_or_method");
        }

        // The two tables are one shape: same names, same sentences, same tables, one type apart.
        for (String name : List.of("__length_hint__", "__reduce__", "__setstate__", "__iter__", "__next__")) {
            assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, name), "__doc__"))
                    .as(name)
                    .isEqualTo(Slots.getAttr(entry(PyType.LONG_RANGE_ITERATOR, name), "__doc__"));
            assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(Slots.getAttr(entry(PyType.LONG_RANGE_ITERATOR, name), "__text_signature__"));
            assertThat(entry(PyType.RANGE_ITERATOR, name)).isNotSameAs(entry(PyType.LONG_RANGE_ITERATOR, name));
        }
        assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, "__reduce__"), "__objclass__"))
                .isSameAs(PyType.RANGE_ITERATOR);
        assertThat(Slots.getAttr(entry(PyType.LONG_RANGE_ITERATOR, "__reduce__"), "__objclass__"))
                .isSameAs(PyType.LONG_RANGE_ITERATOR);
        assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, "__setstate__"), "__doc__"))
                .isEqualTo(PyStr.of(SETSTATE_DOC));
        assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, "__reduce__"), "__doc__"))
                .isEqualTo(PyStr.of(REDUCE_DOC));
        assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, "__length_hint__"), "__doc__"))
                .isEqualTo(PyStr.of(LENGTH_HINT_DOC));
        assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, "__setstate__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, object, /)"));
        for (String name : List.of("__iter__", "__next__", "__length_hint__", "__reduce__")) {
            assertThat(Slots.getAttr(entry(PyType.RANGE_ITERATOR, name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, /)"));
        }

        // Absent rather than filed: a cursor is not a sequence, not reversible and not picklable by itself.
        for (String name : List.of("__getnewargs__", "__reduce_ex__", "__len__", "__reversed__", "__dict__")) {
            assertThat(entry(PyType.RANGE_ITERATOR, name)).as(name).isNull();
            assertThat(ORDER).doesNotContain(name);
        }
    }

    /**
     * Neither cursor is callable, and the refusal is the slot's rather than the argument list's.
     *
     * <p>{@code tp_new} is null in both types, so {@code type_call} reads the slot before it counts anything and
     * {@code PyType_NotUsable} says the sentence for the class it was handed rather than for the caller: {@code
     * range_iterator()}, {@code range_iterator(1)}, {@code range_iterator(x=1)} and {@code
     * object.__new__(range_iterator)} all say {@code cannot create 'range_iterator' instances} in the cursor's own
     * {@code tp_name}. A value of one kind is a value of {@code object} and of nothing between, which is the whole
     * of what {@code isinstance} can be asked about a cursor — and the two kinds are not values of each other,
     * which is why each type's rows refuse a cursor of the other half.
     */
    @Test
    void bothCursorsRefuseToBeBuilt() {
        for (PyType owner : BOTH) {
            String kind = owner.name();
            refuses(
                    "TypeError: cannot create '" + kind + "' instances",
                    () -> owner.call(new PyObject[0], null));
            refuses(
                    "TypeError: cannot create '" + kind + "' instances",
                    () -> owner.call(new PyObject[] {ONE}, null));
            refuses(
                    "TypeError: cannot create '" + kind + "' instances",
                    () -> owner.call(new PyObject[] {ONE}, X));
            refuses(
                    "TypeError: cannot create '" + kind + "' instances",
                    () -> Slots.call(Slots.getAttr(PyType.OBJECT, "__new__"), new PyObject[] {owner}, null));
            assertThat(TypeDict.declared(owner).get(PyStr.interned("__init__"))).as(kind).isNull();
            assertThat(TypeDict.declared(owner).get(PyStr.interned("__qualname__"))).as(kind).isNull();
        }
        assertThat(FAST.getType()).isSameAs(PyType.RANGE_ITERATOR);
        assertThat(WIDE_CURSOR.getType()).isSameAs(PyType.LONG_RANGE_ITERATOR);
        assertThat(PyType.OBJECT.instanceOf(FAST)).isTrue();
        assertThat(PyType.OBJECT.instanceOf(WIDE_CURSOR)).isTrue();
        assertThat(PyType.RANGE.instanceOf(FAST)).isFalse();
        assertThat(PyType.RANGE_ITERATOR.instanceOf(FAST)).isTrue();
        assertThat(PyType.RANGE_ITERATOR.instanceOf(WIDE_CURSOR)).isFalse();
        assertThat(PyType.LONG_RANGE_ITERATOR.instanceOf(WIDE_CURSOR)).isTrue();
        assertThat(PyType.LONG_RANGE_ITERATOR.instanceOf(FAST)).isFalse();
    }

    /**
     * The five gates {@code range_iter} runs, of which only three are about the bounds.
     *
     * <p>The three conversions come first, then the count, and then the one step the cursor takes past the last
     * value — {@code lstart + ulen * lstep}, which CPython guards without computing it, on {@code stop} rather than
     * on the product. That last gate is the reason {@code range(0, 2 ** 63 - 1, 2)} gets a wide cursor while {@code
     * range(0, 2 ** 63 - 2, 2)} gets a fast one: the two differ by one value, and only the first walk ends on a
     * number a word cannot hold. The negative side is the same guard written the other way round, and a range of no
     * values at all passes it whatever its step is.
     */
    @Test
    void rangeIterCountsFiveNumbersAndNotThree() {
        assertThat(widthOf(cursor(0, 10, 1))).isEqualTo(FAST_NAME);
        assertThat(widthOf(FAST)).isEqualTo(FAST_NAME);
        assertThat(widthOf(cursor(0, 1, 1))).isEqualTo(FAST_NAME);
        assertThat(widthOf(EMPTY.iterator())).isEqualTo(FAST_NAME);
        assertThat(widthOf(range(1, 10, 1).iterator())).isEqualTo(FAST_NAME);

        // A bound too wide for a word, whatever the count says.
        assertThat(widthOf(cursor(LAST, LAST.add(ONE_BI), ONE_BI))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(cursor(ZERO_BI, two(64), TWO_BI))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(WIDE_CURSOR)).isEqualTo(WIDE_NAME);
        assertThat(widthOf(range(ZERO_BI, two(100), THREE_BI).iterator())).isEqualTo(WIDE_NAME);

        // Every bound converts and the count still does not: 2 ** 64 values between the two ends of a word.
        assertThat(widthOf(cursor(FIRST, LAST, ONE_BI))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(range(FIRST, ZERO_BI, ONE_BI).iterator())).isEqualTo(WIDE_NAME);

        // The fourth and fifth gates: the step that is added once after the last value.
        assertThat(widthOf(cursor(ZERO_BI, LAST, TWO_BI))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(cursor(ZERO_BI, LAST.subtract(ONE_BI), TWO_BI))).isEqualTo(FAST_NAME);
        assertThat(widthOf(cursor(LAST, FIRST, TWO_BI.negate()))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(cursor(LAST, FIRST.add(ONE_BI), TWO_BI.negate()))).isEqualTo(FAST_NAME);

        // An empty range passes the last gate whatever its step is, because there is no last value to leave.
        assertThat(widthOf(cursor(0, 10, Long.MIN_VALUE))).isEqualTo(FAST_NAME);
        assertThat(widthOf(cursor(0, 0, -1))).isEqualTo(FAST_NAME);
        assertThat(widthOf(EMPTY.iterator())).isEqualTo(FAST_NAME);
    }

    /**
     * The two numbers more {@code range_reverse} checks, which are the two the reversal has to write in.
     *
     * <p>The C function converts the three bounds, refuses a step of {@code LONG_MIN} because it is about to negate
     * it, and then checks {@code start - step} in unsigned arithmetic in both directions — and only then the count.
     * So the same range can be wide one way and narrow the other, twice over in opposite directions: {@code
     * range(0, 2 ** 63 - 1, 2)} has a walk that ends past a word and a reversal that starts inside one, and {@code
     * range(2 ** 63 - 1, 0, -1)} has a last value its own first bound cannot step below. Neither half of a reversal
     * produces a different value than the other.
     */
    @Test
    void rangeReverseChecksStartMinusStepAsWellAsTheEnd() {
        // A step whose negation is itself: forward in words, back in Python integers.
        assertThat(widthOf(reversed(ZERO_BI, TEN_BI, FIRST))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(reversed(ONE_BI, TWO_BI, FIRST))).isEqualTo(WIDE_NAME);

        // The bound the reversed cursor walks to: {@code start - step}, off the end of a word in both directions.
        assertThat(widthOf(reversed(LAST, ZERO_BI, BigInteger.ONE.negate()))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(reversed(FIRST, ZERO_BI, ONE_BI))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(cursor(LAST, ZERO_BI, BigInteger.ONE.negate()))).isEqualTo(FAST_NAME);
        assertThat(widthOf(cursor(FIRST, ZERO_BI, ONE_BI))).isEqualTo(WIDE_NAME);

        // Wide forwards and narrow backwards: the guard on the last step is a guard on the walk it never takes.
        assertThat(widthOf(cursor(ZERO_BI, LAST, TWO_BI))).isEqualTo(WIDE_NAME);
        assertThat(widthOf(reversed(ZERO_BI, LAST, TWO_BI))).isEqualTo(FAST_NAME);
        assertThat(widthOf(reversed(ONE_BI, TWO_BI, FIRST))).isEqualTo(WIDE_NAME);

        // Both halves of a reversal agree on the values, whatever width either was built at.
        assertThat(walked(reversed(ZERO_BI, TEN_BI, TWO_BI))).isEqualTo(List.of("8", "6", "4", "2", "0"));
        assertThat(walked(RangeIterators.reversed(ODD))).isEqualTo(List.of("7", "5", "3", "1"));
        assertThat(walked(RangeIterators.reversed(FAR)))
                .isEqualTo(List.of("590295810358705651712", "0"));
        assertThat(walked(reversed(two(70), ZERO_BI, two(69).negate())))
                .isEqualTo(List.of("590295810358705651712", "1180591620717411303424"));
        assertThat(walked(reversed(ZERO_BI, TEN_BI, FIRST))).isEmpty();
        assertThat(restOf(RangeIterators.reversed(ODD)).repr()).isEqualTo("range(7, -1, -2)");

        // A reversal starts at its own last value, and the hint counts what is left after it is taken.
        PyObject back = RangeIterators.reversed(range(0, 10, 1));
        assertThat(nextOf(back).repr()).isEqualTo("9");
        assertThat(hint(back)).isEqualTo(BigInteger.valueOf(9));

        // A reversal jumps with the same index the forward cursor takes.
        PyObject again = RangeIterators.reversed(range(0, 10, 1));
        assertThat(setState(again, THREE)).isSameAs(NONE);
        assertThat(nextOf(again).repr()).isEqualTo("6");
    }

    /**
     * Every cursor's state is the range of the values still in it, folded at its own width.
     *
     * <p>{@code rangeiter_reduce} multiplies in C longs and {@code longrangeiter_reduce} in Python integers, and the
     * two answers are the same range — which is what the two guards at construction buy. The reduce is a three-item
     * state whose first item is the builtin {@code iter} reached for at the call, so it is the {@code iter} of this
     * runtime's own builtins dictionary rather than a copy of it, and whose last item is the {@code None} that says
     * there is no state to set afterwards. An exhausted cursor reduces to the empty range at the end of its walk,
     * not to nothing at all.
     */
    @Test
    void bothHalvesReduceToTheRangeOfTheValuesLeft() {
        PyTuple state = (PyTuple) Slots.getAttr(FAST, "__reduce__").call(new PyObject[0], null);
        assertThat(state.size()).isEqualTo(3);
        assertThat(state.at(0)).isSameAs(PyRuntime.builtins().get(PyStr.interned("iter")));
        assertThat(state.at(0).getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(state.at(1).getType().name()).isEqualTo("tuple");
        assertThat(state.at(2)).isSameAs(NONE);
        assertThat(((PyTuple) state.at(1)).size()).isEqualTo(1);
        assertThat(((PyRange) ((PyTuple) state.at(1)).at(0)).repr()).isEqualTo("range(1, 9, 2)");

        PyObject walkedOnce = ODD.iterator();
        nextOf(walkedOnce);
        assertThat(restOf(walkedOnce).repr()).isEqualTo("range(3, 9, 2)");
        nextOf(walkedOnce);
        assertThat(restOf(walkedOnce).repr()).isEqualTo("range(5, 9, 2)");
        nextOf(walkedOnce);
        nextOf(walkedOnce);
        assertThat(restOf(walkedOnce).repr()).isEqualTo("range(9, 9, 2)");

        // The wide half computes the same stop in whatever width it needs.
        PyObject broad = FAR.iterator();
        assertThat(restOf(broad).repr())
                .isEqualTo("range(0, 1180591620717411303424, 590295810358705651712)");
        nextOf(broad);
        assertThat(restOf(broad).repr())
                .isEqualTo("range(590295810358705651712, 1180591620717411303424, 590295810358705651712)");
        nextOf(broad);
        assertThat(restOf(broad).repr())
                .isEqualTo("range(1180591620717411303424, 1180591620717411303424, 590295810358705651712)");
        assertThat(hint(broad)).isEqualTo(ZERO_BI);

        // The state is the values left, so a jump is a shorter range and a clip is the whole of it. A step of one
        // is what the repr of the state leaves out, the same as the repr of the range it was made from.
        PyObject jumped = range(0, 10, 1).iterator();
        setState(jumped, FOUR);
        assertThat(restOf(jumped).repr()).isEqualTo("range(4, 10)");
        setState(jumped, TEN);
        assertThat(restOf(jumped).repr()).isEqualTo("range(10, 10)");
        assertThat(restOf(range(0, 10, 2).iterator()).repr()).isEqualTo("range(0, 10, 2)");

        assertThat(bounds(restOf(FAST))).isEqualTo(List.of(ONE_BI, BigInteger.valueOf(9), TWO_BI));
    }

    /**
     * The hint is this cursor's own count, and it is not a length.
     *
     * <p>Neither type files {@code __len__}, so {@code len(it)} is refused while {@code it.__length_hint__()} answers
     * — and answers the values left rather than the values the range holds, which is the whole of what the method
     * exists for. The fast half reads its word; the wide half reads the Python integer it was built with, so the
     * same number a {@code len()} of the range cannot reach is handed out without a complaint, and still as an
     * {@code int}.
     */
    @Test
    void theHintCountsWhatIsLeftAndNeitherTypeFilesALength() {
        assertThat(hint(FAST)).isEqualTo(BigInteger.valueOf(4));
        PyObject walking = ODD.iterator();
        List<BigInteger> seen = new ArrayList<>();
        for (int step = 0; step < 6; step++) {
            seen.add(hint(walking));
            try {
                nextOf(walking);
            } catch (PyRaise stopped) {
                assertThat(stopped.getMessage()).isEqualTo(END);
            }
        }
        assertThat(seen)
                .containsExactly(
                        BigInteger.valueOf(4), BigInteger.valueOf(3), BigInteger.valueOf(2),
                        BigInteger.valueOf(1), ZERO_BI, ZERO_BI);
        assertThat(hint(EMPTY.iterator())).isEqualTo(ZERO_BI);
        assertThat(hint(cursor(0, 1, 1))).isEqualTo(ONE_BI);
        assertThat(hint(range(0, 5, 1).iterator())).isEqualTo(BigInteger.valueOf(5));
        assertThat(walked(ODD.iterator())).isEqualTo(List.of("1", "3", "5", "7"));
        assertThat(walked(FAR.iterator()))
                .isEqualTo(List.of("0", "590295810358705651712"));

        // A range too long for a {@code Py_ssize_t}, and a cursor that counts it in Python integers anyway.
        PyObject unwalkable = range(ZERO_BI, two(70), ONE_BI).iterator();
        assertThat(unwalkable.getType().name()).isEqualTo(WIDE_NAME);
        assertThat(hint(unwalkable)).isEqualTo(two(70));
        assertThat(Slots.getAttr(unwalkable, "__length_hint__").call(new PyObject[0], null).getType())
                .isSameAs(PyType.INT);
        refuses(
                "OverflowError: Python int too large to convert to C ssize_t",
                () -> range(ZERO_BI, two(70), ONE_BI).length());

        for (PyType owner : BOTH) {
            String kind = owner.name();
            assertThat(entry(owner, "__len__")).as(kind).isNull();
            assertThat(ORDER).doesNotContain("__len__");
            refuses(
                    "TypeError: object of type '" + kind + "' has no len()",
                    () -> Slots.length(cursorOf(owner)));
        }
    }

    /**
     * A jump is a count of values to skip, and neither half refuses a count it can hold.
     *
     * <p>{@code rangeiter_setstate} converts and then clips, so {@code -1} becomes {@code 0} and a number past the
     * end becomes the end; the clip is the method's whole answer to a bad index, and there is no {@code IndexError}
     * in either body. What a conversion still refuses is width: {@code 2 ** 63} is an {@code OverflowError} about a
     * C long, and it leaves the cursor exactly where it was, because the C function has not written anything by the
     * time it fails. The wide half needs no conversion at all, so its two extremes are clipped as calmly as its
     * middle.
     */
    @Test
    void setstateClipsRatherThanRefusing() {
        PyObject jumping = range(0, 10, 1).iterator();
        assertThat(setState(jumping, MINUS_ONE)).isSameAs(NONE);
        assertThat(hint(jumping)).isEqualTo(BigInteger.TEN);
        assertThat(nextOf(jumping).repr()).isEqualTo("0");
        assertThat(setState(jumping, PyInt.of(400))).isSameAs(NONE);
        assertThat(hint(jumping)).isEqualTo(ZERO_BI);
        assertThat(widthOf(jumping)).isEqualTo(FAST_NAME);

        // The line the conversion is made at: 2 ** 63 - 1 still converts, 2 ** 63 does not, and a failure moves
        // nothing.
        assertThat(setStateOf(range(0, 10, 1).iterator(), PyInt.of(LAST))).isEqualTo(ZERO_BI);
        PyObject untouched = range(0, 10, 1).iterator();
        nextOf(untouched);
        refuses(
                "OverflowError: Python int too large to convert to C long",
                () -> setState(untouched, PyInt.of(LAST.add(ONE_BI))));
        assertThat(hint(untouched)).isEqualTo(BigInteger.valueOf(9));
        assertThat(nextOf(untouched).repr()).isEqualTo("1");

        // The index is asked for as an index, so a value's own __index__ is called and its complaint travels.
        assertThat(setStateOf(range(0, 10, 1).iterator(), TRUE)).isEqualTo(BigInteger.valueOf(9));
        assertThat(setStateOf(range(0, 10, 1).iterator(), indexAnswering("AnswersIndex", TWO)))
                .isEqualTo(BigInteger.valueOf(8));
        refuses("ValueError: boom", () -> setState(range(0, 10, 1).iterator(), indexRaising("RaisesInIndex")));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> setState(range(0, 10, 1).iterator(), TEXT));
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> setState(range(0, 10, 1).iterator(), REAL));

        // The wide half clips whatever the width, because it converts nothing.
        PyObject broad = FAR.iterator();
        assertThat(setState(broad, WIDE)).isSameAs(NONE);
        assertThat(hint(broad)).isEqualTo(ZERO_BI);
        assertThat(setStateOf(FAR.iterator(), PyInt.of(two(200)))).isEqualTo(ZERO_BI);
        assertThat(setStateOf(FAR.iterator(), MINUS_ONE)).isEqualTo(TWO_BI);
        assertThat(widthOf(setStateAndReturn(FAR.iterator(), PyInt.of(FIRST)))).isEqualTo(WIDE_NAME);
    }

    /** {@code it.__setstate__(state)} and then the hint, for the one answer a jump leaves behind. */
    private static BigInteger setStateOf(PyObject iterator, PyObject state) {
        setState(iterator, state);
        return hint(iterator);
    }

    /** {@code it.__setstate__(state)} and then the cursor itself, which is the value whose kind matters. */
    private static PyObject setStateAndReturn(PyObject iterator, PyObject state) {
        setState(iterator, state);
        return iterator;
    }

    /**
     * One index, asked two ways: the fast half converts it and the wide half compares it.
     *
     * <p>{@code PyLong_AsLong} takes a bool, a subclass and anything with an {@code __index__}, so the fast half
     * accepts all three and asks the last of them; {@code longrangeiter_setstate} starts from {@code
     * PyLong_CheckExact} and so refuses all three in the same sentence, naming the value's own type and never
     * reaching an {@code __index__} that would have raised. Both halves refuse a str, a float and {@code None}, in
     * two different sentences because one is a conversion failure and the other is a type test.
     */
    @Test
    void theFastHalfConvertsAndTheWideHalfComparesTypes() {
        for (PyObject accepted : List.of(ONE, TRUE, ZERO, indexAnswering("AnswersIndex", TWO))) {
            assertThat(setState(range(0, 10, 1).iterator(), accepted))
                    .as(accepted.getType().name())
                    .isSameAs(NONE);
        }
        for (PyObject refused : List.of(TRUE, indexAnswering("AnswersIndex", TWO), REAL, TEXT, NONE)) {
            refuses(
                    "TypeError: state must be an int, not " + refused.getType().filedName(),
                    () -> setState(FAR.iterator(), refused));
        }
        // The wide half never asks an __index__, so the value that raises one is refused for its type instead.
        refuses(
                "TypeError: state must be an int, not RaisesInIndex",
                () -> setState(FAR.iterator(), indexRaising("RaisesInIndex")));
        // An exact int of any width is what the type test wants, including zero and a negative.
        for (PyObject accepted : List.of(ZERO, ONE, TWO, MINUS_ONE, PyInt.of(FIRST), PyInt.of(two(200)))) {
            assertThat(setState(FAR.iterator(), accepted))
                    .as(accepted.repr())
                    .isSameAs(NONE);
        }
        assertThat(setStateOf(FAR.iterator(), ONE)).isEqualTo(ONE_BI);

        // A negative jump and a jump past the end are both accepted by both halves, and both clip.
        PyObject narrow = range(0, 10, 1).iterator();
        PyObject broad = FAR.iterator();
        for (PyObject state : List.of(MINUS_ONE, TEN, PyInt.of(LAST))) {
            assertThat(setState(narrow, state)).isSameAs(NONE);
            assertThat(setState(broad, state)).isSameAs(NONE);
        }
        assertThat(hint(narrow)).isEqualTo(ZERO_BI);
        assertThat(hint(broad)).isEqualTo(ZERO_BI);

        // Only the fast half converts, so only it can overflow: the same width the wide half clips is an
        // OverflowError for the half that reads the state through __index__ and stores a Py_ssize_t.
        refuses(
                "OverflowError: Python int too large to convert to C long",
                () -> setState(narrow, PyInt.of(two(70))));
        assertThat(setState(broad, PyInt.of(two(70)))).isSameAs(NONE);
    }

    /**
     * A wrapper and a method_descriptor both check their receiver, and say it in two different sentences.
     *
     * <p>{@code wrapperdescr_call} counts before it calls and {@code method_check_args} counts before it calls, and
     * each has three gates in the same order — an empty list, a receiver of the wrong kind, a count or a keyword —
     * with different words at all three. The receiver comes before the count, so {@code vars(T)['__next__']('x', 1)}
     * complains about the str rather than about the argument; the keyword only reaches a wrapper that has a name to
     * write in its sentence, and a method that has a {@code PyMethodDef} to read its count out of. A count of
     * keywords is no count of arguments at all: {@code vars(T)['__iter__'](self=it)} says the argument is missing.
     * And every receiver check is against its own type, so a fast cursor is refused by the wide rows and the other
     * way round, in the short sentence with the other half's name in it.
     */
    @Test
    void theRowsAskTheReceiverBeforeTheyCountAnything() {
        for (PyType owner : BOTH) {
            String kind = owner.name();
            PyObject own = cursorOf(owner);
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
                                + " but received a 'str'",
                        () -> call(owner, name, TEXT, ONE));
                refuses(
                        "TypeError: descriptor '" + name + "' requires a '" + kind + "' object"
                                + " but received a '" + other.getType().name() + "'",
                        () -> call(owner, name, other));
                refuses("TypeError: expected 0 arguments, got 1", () -> call(owner, name, own, ONE));
                refuses(
                        "TypeError: wrapper " + name + "() takes no keyword arguments",
                        () -> calledWith(owner, name, X, own, ONE));
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
                                + " doesn't apply to a 'str' object",
                        () -> call(owner, name, TEXT, ONE));
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

            // __setstate__ is the METH_O of the three, so its own body counts what the row was handed.
            refuses(
                    "TypeError: unbound method " + kind + ".__setstate__() needs an argument",
                    () -> call(owner, "__setstate__"));
            refuses(
                    "TypeError: descriptor '__setstate__' for '" + kind + "' objects"
                            + " doesn't apply to a 'int' object",
                    () -> call(owner, "__setstate__", ZERO, ONE));
            assertThat(call(owner, "__setstate__", own, ONE)).isSameAs(NONE);
            refuses(
                    "TypeError: " + kind + ".__setstate__() takes exactly one argument (0 given)",
                    () -> call(owner, "__setstate__", own));
            refuses(
                    "TypeError: " + kind + ".__setstate__() takes exactly one argument (2 given)",
                    () -> call(owner, "__setstate__", own, ONE, TWO));
            refuses(
                    "TypeError: " + kind + ".__setstate__() takes no keyword arguments",
                    () -> calledWith(owner, "__setstate__", X, own, ONE));

            // Both are descriptors, and a read with no value hands back the row itself.
            assertThat(Slots.call(
                            Slots.getAttr(entry(owner, "__next__"), "__get__"),
                            new PyObject[] {NONE, owner},
                            null))
                    .isSameAs(entry(owner, "__next__"));
            // own has already been sent forward by the __setstate__ above, so these three read fresh cursors.
            PyObject fresh = cursorOf(owner);
            assertThat(call(owner, "__iter__", fresh)).isSameAs(fresh);
            assertThat(((PyInt) call(owner, "__next__", cursorOf(owner))).repr())
                    .isEqualTo(owner == PyType.RANGE_ITERATOR ? "1" : "0");
            assertThat(((PyInt) call(owner, "__length_hint__", fresh)).getValue())
                    .isEqualTo(owner == PyType.RANGE_ITERATOR ? BigInteger.valueOf(4) : TWO_BI);
        }
    }

    /**
     * A read off a value binds and a read off the type does not, which is the four rows in two kinds.
     *
     * <p>{@code tp_iter} and {@code tp_iternext} are copied out as wrappers, so {@code it.__next__} is a {@code
     * method-wrapper} made afresh by the read and holding the cursor as its {@code __self__}; {@code
     * rangeiter_methods} and {@code longrangeiter_methods} are {@code PyMethodDef} lists, so {@code
     * it.__length_hint__} is a {@code builtin_function_or_method} whose {@code __qualname__} names the cursor's type
     * and whose {@code __doc__} is the row's sentence. Two reads of the same wrapper or the same method are two
     * objects, and neither is ever equal to the entry — while {@code T.__length_hint__} is the entry, and is it
     * twice.
     */
    @Test
    void aReadOffAValueBindsAndOffATypeDoesNot() {
        for (String name : SLOTS) {
            PyObject bound = Slots.getAttr(FAST, name);
            assertThat(bound.getType().name()).as(name).isEqualTo("method-wrapper");
            assertThat(bound).isNotSameAs(entry(PyType.RANGE_ITERATOR, name));
            assertThat(Slots.getAttr(bound, "__self__")).as(name).isSameAs(FAST);
            assertThat(Slots.getAttr(FAST, name)).as(name).isNotSameAs(Slots.getAttr(FAST, name));
            assertThat(Slots.getAttr(bound, "__name__")).as(name).isEqualTo(PyStr.of(name));
        }
        for (String name : METHODS) {
            PyObject bound = Slots.getAttr(FAST, name);
            assertThat(bound.getType().name()).as(name).isEqualTo("builtin_function_or_method");
            assertThat(Slots.getAttr(bound, "__self__")).as(name).isSameAs(FAST);
            assertThat(Slots.getAttr(bound, "__name__")).as(name).isEqualTo(PyStr.of(name));
            assertThat(Slots.getAttr(bound, "__qualname__"))
                    .as(name)
                    .isEqualTo(PyStr.of(FAST_NAME + "." + name));
            assertThat(Slots.getAttr(bound, "__doc__"))
                    .as(name)
                    .isEqualTo(
                            PyStr.of(
                                    name.equals("__length_hint__")
                                            ? LENGTH_HINT_DOC
                                            : name.equals("__reduce__") ? REDUCE_DOC : SETSTATE_DOC));
            assertThat(Slots.getAttr(FAST, name)).as(name).isNotSameAs(Slots.getAttr(FAST, name));
            assertThat(Slots.getAttr(PyType.RANGE_ITERATOR, name))
                    .as(name)
                    .isSameAs(entry(PyType.RANGE_ITERATOR, name));
            assertThat(Slots.getAttr(PyType.LONG_RANGE_ITERATOR, name))
                    .as(name)
                    .isSameAs(entry(PyType.LONG_RANGE_ITERATOR, name));
        }
        // The bound row still takes its receiver from the binding rather than from the call.
        PyObject reading = ODD.iterator();
        assertThat(Slots.getAttr(reading, "__length_hint__").call(new PyObject[0], null))
                .isEqualTo(PyInt.of(4));
        assertThat(hint(FAR.iterator())).isEqualTo(TWO_BI);
    }

    /**
     * The end of a cursor is a bare {@code StopIteration}, and a cursor has nowhere to keep anything.
     *
     * <p>{@code rangeiter_next} returns null and {@code slot_tp_iternext} turns that into the exception, which is
     * the difference between a cursor and a generator: nothing is carried by it, so {@code StopIteration.value} is
     * absent and a second read gives the same bare one. {@code tp_getattro} is {@code PyObject_GenericGetAttr} over
     * a type that files six names on a value with no dictionary, so a name off that list is refused by the type, a
     * write has nowhere to go, and {@code vars()} of a cursor is refused by the builtin that asks the same question.
     */
    @Test
    void aCursorEndsBarelyAndHoldsNothing() {
        PyObject running = ODD.iterator();
        for (int step = 0; step < 4; step++) {
            nextOf(running);
        }
        refuses(END, () -> nextOf(running));
        refuses(END, () -> nextOf(running));
        assertThat(((PyIterator) running).exhaustion()).isNull();
        assertThat(PyType.OBJECT.instanceOf(running)).isTrue();

        // An iterator is its own, through the slot and through the row both.
        assertThat(running.iterator()).isSameAs(running);
        assertThat(Slots.getAttr(running, "__iter__").call(new PyObject[0], null)).isSameAs(running);
        assertThat(Slots.asIterator(running)).isSameAs(running);
        assertThat(((PyIterator) EMPTY.iterator()).nextOrNull()).isNull();

        for (PyType owner : BOTH) {
            String kind = owner.name();
            PyObject value = cursorOf(owner);
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
                    "AttributeError: '" + kind + "' object has no attribute 'start'"
                            + " and no __dict__ for setting new attributes",
                    () -> Slots.setAttr(value, "start", ONE));
            refuses(
                    "AttributeError: '" + kind + "' object has no attribute 'start'"
                            + " and no __dict__ for setting new attributes",
                    () -> Slots.delAttr(value, "start"));
            refuses(
                    "AttributeError: type object '" + kind + "' has no attribute '__slots__'",
                    () -> Slots.getAttr(owner, "__slots__"));
            assertThat(Slots.findAttr(value, "__weakref__")).as(kind).isNull();

            // The window the type hands out keeps the six names and takes no new one.
            PyObject window = Slots.getAttr(owner, "__dict__");
            assertThat(window.getType().name()).isEqualTo("mappingproxy");
            refuses(
                    "AttributeError: 'mappingproxy' object has no attribute '__setitem__'",
                    () -> Slots.getAttr(window, "__setitem__"));
        }
    }
}
