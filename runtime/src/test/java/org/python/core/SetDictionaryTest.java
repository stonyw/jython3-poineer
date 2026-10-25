package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The dictionaries {@code set} and {@code frozenset} file, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 65 and 66 read the forty-six names of a set and the thirty-two of a frozen set from Python,
 * one line per name, and ask what each entry answers. What is asserted here is the half a script cannot point
 * at: the two tables as the complete ordered maps they are, and the twenty names the mutable one files that
 * the frozen one does not — a script can read which names this runtime fills but not which of its two
 * hand-written lists put {@code __contains__} in a slot's index with a method's object; that a dictionary entry
 * and the same type's {@link Members} table hand out one and the same descriptor, which is how a frozen set's
 * eleven methods are caught if the two ever disagree; what a lookup asks for <em>before</em> it knows whether
 * there is anything to find, a rule that exists only because {@link java.util.LinkedHashSet} answers a miss
 * from an empty set without asking at all, and that it asks exactly once either way; which of the places a set
 * is handed to a set take the second look and which decline it, and that the second look is gated on a refusal
 * of the right kind; and the two identities a frozen value keeps, which are Java object identity rather than
 * anything a {@code repr} could show.
 */
class SetDictionaryTest {

    /** The forty-six names {@code set} files, in the order its dictionary answers them. */
    private static final List<String> SET_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__iter__", "__init__", "__sub__", "__rsub__", "__and__", "__rand__", "__xor__",
                    "__rxor__", "__or__", "__ror__", "__isub__", "__iand__", "__ixor__", "__ior__", "__len__",
                    "__contains__", "add", "clear", "copy", "discard", "difference", "difference_update",
                    "intersection", "intersection_update", "isdisjoint", "issubset", "issuperset", "pop",
                    "__reduce__", "remove", "__sizeof__", "symmetric_difference", "symmetric_difference_update",
                    "union", "update", "__class_getitem__", "__doc__");

    /** The thirty-two names {@code frozenset} files: the same slots less {@code __init__} and the four merges. */
    private static final List<String> FROZEN_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__iter__", "__sub__", "__rsub__", "__and__", "__rand__", "__xor__", "__rxor__",
                    "__or__", "__ror__", "__len__", "__contains__", "copy", "difference", "intersection",
                    "isdisjoint", "issubset", "issuperset", "__reduce__", "__sizeof__", "symmetric_difference",
                    "union", "__class_getitem__", "__doc__");

    /** The twenty names the mutable table files, in the order {@code set_methods} writes them. */
    private static final List<String> SET_METHODS =
            List.of(
                    "add", "clear", "__contains__", "copy", "discard", "difference", "intersection",
                    "isdisjoint", "issubset", "issuperset", "__reduce__", "pop", "remove", "__sizeof__",
                    "symmetric_difference_update", "symmetric_difference", "union", "update",
                    "difference_update", "intersection_update");

    /** The eleven names the frozen table files, in the order {@code frozenset_methods} writes them. */
    private static final List<String> FROZEN_METHODS =
            List.of(
                    "__contains__", "copy", "difference", "intersection", "isdisjoint", "issubset",
                    "issuperset", "__reduce__", "__sizeof__", "symmetric_difference", "union");

    private static final PyObject ONE = PyInt.of(1);
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    private static String kind(PyObject member) {
        return member.getType().name();
    }

    /** One name of a type's dictionary, called as a program calls it: the receiver comes first. */
    private static PyObject call(PyType kind, String name, PyObject... arguments) {
        return Slots.call(entry(kind, name), arguments, null);
    }

    /** One method of a value, read through its type and then called. */
    private static PyObject run(PyObject receiver, String name, PyObject... arguments) {
        return Slots.call(Slots.getAttr(receiver, name), arguments, null);
    }

    /** The names the window over a type's dictionary answers, in the order the dictionary kept them. */
    private static List<String> filed(PyType kind) {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(kind, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** The sentence one filed member answers {@code __doc__} with. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == PyNone.INSTANCE ? null : ((PyStr) text).getValue();
    }

    /** An empty frozen value, filled the way a {@code frozenset} display fills one. */
    private static PyFrozenSet frozenOf(PyObject... items) {
        PyFrozenSet set = PyFrozenSet.collected(null);
        for (PyObject item : items) {
            set.add(item);
        }
        return set;
    }

    /** A frozen set of one value, which is what every frozen holder here is made of. */
    private static PyFrozenSet frozen(PyObject item) {
        return frozenOf(item);
    }

    private static PySet mutable(PyObject... items) {
        return PySet.of(items);
    }

    /** An iterable of the given values, because the methods that take any iterable take a list. */
    private static PyList list(PyObject... items) {
        return PyList.of(items);
    }

    /** {@code TypeError} and friends in CPython's own words, with the type that says them. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** An element that counts how often a lookup asked it to hash. */
    private static final class Counted extends PyObject {

        private int asks;

        Counted() {
            super(PyType.OBJECT);
        }

        @Override
        public long hash() {
            asks++;
            return 7;
        }

        @Override
        public String repr() {
            return "counted";
        }
    }

    /** A set whose own hash refuses with something that is not the unhashable {@code TypeError}. */
    private static final class Grumpy extends PySet {

        Grumpy() {
            super(new LinkedHashSet<>(), PyType.SET);
        }

        @Override
        public long hash() {
            throw Slots.valueError("no hash for you");
        }

        @Override
        public String repr() {
            return "grumpy";
        }
    }

    /**
     * Each of the two tables, complete and in the reference's order.
     *
     * <p>{@code add_operators} writes a set's slots in struct order, so the twelve {@code nb_*} functions land
     * between {@code tp_init} and {@code mp_length} — the four in-place ones with them, which a frozen table
     * has no reason to file at all. {@code __contains__} is the odd one: {@code set_methods} declares it
     * {@code METH_COEXIST}, so it keeps the {@code sq_contains} index right after {@code __len__} but is filed
     * with the method table's own object rather than with a copy of a slot wrapper, and is therefore counted in
     * the vectorcall words while its two neighbours are counted bare. {@code __hash__} is a second case:
     * {@code tp_hash} is {@code PyObject_HashNotImplemented} for a set, and {@code add_operators} files that
     * one pointer as the value {@code None} rather than as a wrapper — which is not what a slot left null does,
     * since {@code add_operators} skips a {@code NULL} slot without filing it at all.
     *
     * <p>Twenty names come from the method table and so are the very objects {@link Members#describe} hands
     * out; {@code __class_getitem__} is the one {@code classmethod_descriptor} that is not, and {@code __str__}
     * a third case: {@code tp_str} is null for both halves, so the name is inherited and never filed.
     */
    @Test
    void eachHalfFilesItsOwnTable() {
        assertThat(filed(PyType.SET)).isEqualTo(SET_ORDER);
        assertThat(filed(PyType.FROZENSET)).isEqualTo(FROZEN_ORDER);
        assertThat(TypeDict.declared(PyType.SET).keys()).hasSize(46);
        assertThat(TypeDict.declared(PyType.FROZENSET).keys()).hasSize(32);
        assertThat(Arrays.asList(Members.tableOf(PyType.SET).names())).isEqualTo(SET_METHODS);
        assertThat(Arrays.asList(Members.tableOf(PyType.FROZENSET).names())).isEqualTo(FROZEN_METHODS);

        assertThat(SET_ORDER).containsAll(FROZEN_ORDER);
        assertThat(FROZEN_ORDER).doesNotContain("__init__", "add", "pop", "update", "__ior__", "__isub__");
        assertThat(entry(PyType.SET, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(kind(entry(PyType.FROZENSET, "__hash__"))).isEqualTo("wrapper_descriptor");
        assertThat(kind(entry(PyType.SET, "__contains__"))).isEqualTo("method_descriptor");
        assertThat(SET_ORDER.indexOf("__contains__") - SET_ORDER.indexOf("__len__")).isEqualTo(1);
        assertThat(SET_ORDER.indexOf("__len__") - SET_ORDER.indexOf("__sub__")).isEqualTo(12);

        assertThat(entry(PyType.SET, "add"))
                .isSameAs(Slots.getAttr(PyType.SET, "add"))
                .isSameAs(Members.tableOf(PyType.SET).describe("add"));
        assertThat(entry(PyType.FROZENSET, "__contains__"))
                .isSameAs(Members.tableOf(PyType.FROZENSET).describe("__contains__"));
        assertThat(entry(PyType.FROZENSET, "union"))
                .isSameAs(Members.tableOf(PyType.FROZENSET).describe("union"));
        assertThat(Members.tableOf(PyType.FROZENSET).describe("__init__"))
                .isSameAs(PyNotImplemented.INSTANCE);

        assertThat(kind(entry(PyType.SET, "__class_getitem__"))).isEqualTo("classmethod_descriptor");
        assertThat(entry(PyType.SET, "__class_getitem__"))
                .isNotSameAs(Members.tableOf(PyType.SET).describe("__class_getitem__"));
        assertThat(carried(PyType.SET, "__class_getitem__"))
                .isEqualTo("sets are generic over the type of their elements");
        assertThat(carried(PyType.FROZENSET, "__class_getitem__"))
                .isEqualTo("frozensets are generic over the type of their elements");

        assertThat(filed(PyType.SET)).doesNotContain("__str__", "__bool__", "__reversed__", "__format__");
        assertThat(Slots.getAttr(PyType.FROZENSET, "__str__"))
                .isSameAs(Slots.getAttr(PyType.OBJECT, "__str__"));
        assertThat(Slots.getAttr(PyType.FROZENSET, "__init__"))
                .isSameAs(Slots.getAttr(PyType.OBJECT, "__init__"));
    }

    /**
     * Every name of both tables answers its own sentence, and the two types answer theirs.
     *
     * <p>CPython keeps each one in the {@code PyMethodDef} beside the function pointer, so the mutable half's
     * nine extra methods carry text a frozen value never shows — and the in-place slots say {@code Return
     * self-=value.} because they are {@code nb_inplace_*} functions, where a list's {@code __iadd__} says
     * {@code Implement} because it is an {@code sq_inplace_concat}.
     */
    @Test
    void everyFiledNameCarriesItsSentence() {
        for (String name : SET_METHODS) {
            assertThat(carried(PyType.SET, name)).as(name).isNotNull();
        }
        for (String name : FROZEN_METHODS) {
            assertThat(carried(PyType.FROZENSET, name)).as(name).isNotNull();
        }
        assertThat(carried(PyType.SET, "add"))
                .isEqualTo("Add an element to a set.\n\nThis has no effect if the element is already present.");
        assertThat(carried(PyType.SET, "__contains__")).isEqualTo("x.__contains__(y) <==> y in x.");
        assertThat(carried(PyType.SET, "__sizeof__"))
                .isEqualTo("S.__sizeof__() -> size of S in memory, in bytes.");
        assertThat(carried(PyType.SET, "__reduce__")).isEqualTo("Return state information for pickling.");
        assertThat(carried(PyType.SET, "__sub__")).isEqualTo("Return self-value.");
        assertThat(carried(PyType.SET, "__rsub__")).isEqualTo("Return value-self.");
        assertThat(carried(PyType.SET, "__isub__")).isEqualTo("Return self-=value.");
        assertThat(carried(PyType.SET, "__ior__")).isEqualTo("Return self|=value.");
        assertThat(carried(PyType.FROZENSET, "__or__")).isEqualTo("Return self|value.");
        assertThat(carried(PyType.FROZENSET, "__rand__")).isEqualTo("Return value&self.");
        assertThat(carried(PyType.FROZENSET, "union"))
                .isEqualTo("Return a new set with elements from the set and all others.");
        assertThat(entry(PyType.FROZENSET, "add")).isNull();

        assertThat(entry(PyType.SET, "__doc__")).isInstanceOf(PyStr.class);
        assertThat(((PyStr) entry(PyType.SET, "__doc__")).getValue())
                .isEqualTo("Build an unordered collection of unique elements.");
        assertThat(((PyStr) entry(PyType.FROZENSET, "__doc__")).getValue())
                .isEqualTo("Build an immutable unordered collection of unique elements.");
    }

    /**
     * A lookup takes the element's hash before it knows whether there is anything to find, and takes it once.
     *
     * <p>{@code set_contains_key} asks the key to hash first, so an unhashable value tested against an empty
     * set is refused in the set's own words rather than reported as missing. A {@link LinkedHashSet} with no
     * entries answers {@code contains} without touching its key at all — the line that asks the Java table
     * directly is what shows the gap {@link PySet} has to close — so the set asks on its behalf, and only
     * there: a removal hashes through the map even with nothing to remove, and asking twice would make a
     * counting element report two asks where CPython reports one.
     */
    @Test
    void aLookupHashesItsElementBeforeItLooks() {
        Counted element = new Counted();
        PySet filled = mutable(ONE, TWO);

        refuses(
                "TypeError: cannot use 'list' as a set element (unhashable type: 'list')",
                () -> PySet.empty().contains(list(ONE)));
        refuses(
                "TypeError: cannot use 'list' as a set element (unhashable type: 'list')",
                () -> filled.contains(list(ONE)));
        refuses(
                "TypeError: cannot use 'dict' as a set element (unhashable type: 'dict')",
                () -> PySet.empty().contains(PyDict.empty()));

        Set<PyObject> nothing = new LinkedHashSet<>();
        assertThat(nothing.contains(element)).isFalse();
        assertThat(element.asks).isZero();

        element.asks = 0;
        assertThat(filled.contains(element)).isSameAs(PyBool.FALSE);
        assertThat(element.asks).isEqualTo(1);

        element.asks = 0;
        assertThat(PySet.empty().contains(element)).isSameAs(PyBool.FALSE);
        assertThat(element.asks).isEqualTo(1);

        element.asks = 0;
        assertThat(filled.getItems().remove(element)).isFalse();
        assertThat(element.asks).isEqualTo(1);

        // The two walks that use the plain lookup ask once as well, and take no second look.
        element.asks = 0;
        assertThat(run(PySet.empty(), "isdisjoint", list(element))).isSameAs(PyBool.TRUE);
        assertThat(element.asks).isEqualTo(1);
        element.asks = 0;
        assertThat(run(PySet.empty(), "issuperset", list(element))).isSameAs(PyBool.FALSE);
        assertThat(element.asks).isEqualTo(1);
    }

    /**
     * Which of the places a set is handed to a set ask it a second time, as a frozenset.
     *
     * <p>{@code set_contains_lock_held}, {@code set_discard_lock_held} and the {@code remove} that reads
     * through it all wrap the refusal, hash the value as {@code frozenset_hash_impl} and look again — so
     * {@code {1} in {frozenset({1})}} is true and a {@code discard} of the same set takes it out, even though
     * a set has no hash of its own. {@code isdisjoint}, {@code issuperset} and {@code set_add_key} use the
     * plain lookup and have no second look, which is why the value that answers {@code in} is an error handed
     * to the other three. The retry is gated on both halves of the rule: only a <em>mutable</em> set is looked
     * at this way, and only when what came back was a {@code TypeError} — a set whose hash raises anything else
     * is heard raising, in its own words rather than the set's.
     */
    @Test
    void onlySomeLooksTakeTheSecondOne() {
        PySet asElement = mutable(ONE);
        PyFrozenSet holdsOne = frozenOf(frozen(ONE));

        assertThat(holdsOne.contains(asElement)).isSameAs(PyBool.TRUE);
        assertThat(holdsOne.contains(frozen(ONE))).isSameAs(PyBool.TRUE);
        assertThat(holdsOne.contains(mutable(TWO))).isSameAs(PyBool.FALSE);

        PySet throwsOut = mutable(frozen(ONE));
        run(throwsOut, "discard", asElement);
        assertThat(throwsOut.getItems()).isEmpty();

        refuses(
                "TypeError: cannot use 'set' as a set element (unhashable type: 'set')",
                () -> run(mutable(frozen(ONE)), "isdisjoint", list(asElement)));
        refuses(
                "TypeError: cannot use 'set' as a set element (unhashable type: 'set')",
                () -> run(mutable(frozen(ONE)), "issuperset", list(asElement)));
        refuses(
                "TypeError: cannot use 'set' as a set element (unhashable type: 'set')",
                () -> mutable(ONE).add(asElement));
        refuses(
                "TypeError: cannot use 'set' as a set element (unhashable type: 'set')",
                () -> mutable(ONE).update(list(asElement)));
        refuses(
                "TypeError: cannot use 'set' as a set element (unhashable type: 'set')",
                () -> PySet.of(asElement));
        refuses(
                "TypeError: cannot use 'set' as a set element (unhashable type: 'set')",
                () -> frozenOf(asElement));

        Grumpy grumpy = new Grumpy();
        assertThatThrownBy(() -> frozen(ONE).contains(grumpy))
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo("ValueError: no hash for you"));
        assertThatThrownBy(() -> PySet.empty().contains(grumpy))
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo("ValueError: no hash for you"));
    }

    /**
     * {@code set_init} empties the receiver before it reads its argument; a frozen value has no such slot.
     *
     * <p>The clear is gated on {@code self->fill}, so a value that had contents loses them whether or not the
     * argument turns out to be usable — which is what separates {@code s.__init__(5)} from {@code d.__init__(5)}
     * and makes {@code s.__init__(s)} an empty set rather than itself, because the iterable it then reads is the
     * set it just threw away. A frozen value inherits {@code object}'s, which checks the receiver and swallows
     * whatever else arrived, so the same calls answer {@code None} and change nothing.
     */
    @Test
    void theConstructorClearsBeforeItFills() {
        PySet clears = mutable(ONE, TWO);
        clears.initialized(list(THREE));
        assertThat(clears.getItems()).containsExactly(THREE);

        PySet ofItself = mutable(ONE, TWO);
        ofItself.initialized(ofItself);
        assertThat(ofItself.getItems()).isEmpty();

        PySet refused = mutable(ONE, TWO);
        refuses("TypeError: 'int' object is not iterable", () -> refused.initialized(ONE));
        assertThat(refused.getItems()).isEmpty();

        PySet alreadyEmpty = PySet.empty();
        refuses("TypeError: 'int' object is not iterable", () -> alreadyEmpty.initialized(ONE));
        assertThat(alreadyEmpty.getItems()).isEmpty();

        PyFrozenSet swallows = frozen(ONE);
        assertThat(call(PyType.OBJECT, "__init__", swallows, list(TWO))).isSameAs(PyNone.INSTANCE);
        assertThat(call(PyType.OBJECT, "__init__", swallows, ONE)).isSameAs(PyNone.INSTANCE);
        assertThat(swallows.getItems()).containsExactly(ONE);
        refuses(
                "TypeError: object.__init__() takes exactly one argument (the instance to initialize)",
                () -> call(PyType.OBJECT, "__init__", new Counted(), list(TWO)));
        assertThat(call(PyType.OBJECT, "__init__", new Counted())).isSameAs(PyNone.INSTANCE);
        // An int fills its own tp_new, so the same excess it hands over is thrown away.
        assertThat(call(PyType.OBJECT, "__init__", ONE, list(TWO))).isSameAs(PyNone.INSTANCE);
    }

    /**
     * The four in-place slots either hand the receiver back or are not filed at all.
     *
     * <p>A set's answer is the object it was called on, mutated; a frozen value declines every one of them,
     * which is what lets {@code f |= x} fall back on {@code __or__} and name a new value. The ordinary eight
     * build through a detached table rather than {@code copy()}, because a frozen set's {@code copy} is itself
     * and the two halves would otherwise write into the value they were handed.
     */
    @Test
    void anInPlaceMergeKeepsTheReceiverAndAFrozenOneDeclines() {
        PySet left = mutable(ONE, TWO);
        assertThat(left.inplaceOp(BinaryOp.OR, mutable(THREE))).isSameAs(left);
        assertThat(left.inplaceOp(BinaryOp.SUB, mutable(ONE))).isSameAs(left);
        assertThat(left.inplaceOp(BinaryOp.AND, mutable(ONE))).isSameAs(left);
        assertThat(left.inplaceOp(BinaryOp.XOR, mutable(ONE))).isSameAs(left);

        PyFrozenSet frozenLeft = frozenOf(ONE, TWO);
        assertThat(frozenLeft.inplaceOp(BinaryOp.OR, frozen(THREE))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(frozenLeft.inplaceOp(BinaryOp.SUB, ONE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(left.inplaceOp(BinaryOp.OR, list(THREE))).isSameAs(PyNotImplemented.INSTANCE);

        assertThat(frozenLeft.binaryOp(BinaryOp.OR, frozen(THREE)))
                .isNotSameAs(frozenLeft)
                .isInstanceOf(PyFrozenSet.class);
        assertThat(left.binaryOp(BinaryOp.OR, ONE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(left.richCompare(CompareOp.EQ, list(ONE))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(left.richCompare(CompareOp.LT, frozen(THREE))).isSameAs(PyBool.FALSE);
        assertThat(left.richCompare(CompareOp.LE, frozen(THREE))).isSameAs(PyBool.FALSE);
        assertThat(left.richCompare(CompareOp.GT, frozen(ONE))).isSameAs(PyBool.FALSE);
        assertThat(mutable(ONE).richCompare(CompareOp.LT, frozenOf(ONE, TWO)))
                .isSameAs(PyBool.TRUE);
    }

    /**
     * The two identities a frozen value keeps, which are Java object identity.
     *
     * <p>{@code frozenset(f)} and {@code f.copy()} both hand back the very value they were given, because a
     * frozen set has nothing to copy; {@code frozenset()} does not, because CPython makes a fresh object each
     * time. {@link PyFrozenSet#EMPTY} is the one value this runtime keeps for an empty frozen set, and it never
     * reaches a Python program — which is what {@code collected} of nothing is asserted against here.
     */
    @Test
    void afrozenValueIsItselfTwice() {
        PyFrozenSet one = frozen(ONE);
        assertThat(PyFrozenSet.collected(one)).isSameAs(one);
        assertThat(one.copy()).isSameAs(one);
        assertThat(PyFrozenSet.collected(null)).isNotSameAs(PyFrozenSet.EMPTY);
        assertThat(PyFrozenSet.collected(mutable(ONE)).getItems()).containsExactly(ONE);
        assertThat(PySet.collected(one).getType()).isSameAs(PyType.SET);
        assertThat(one.repr()).isEqualTo("frozenset({1})");
        assertThat(PyFrozenSet.collected(null).repr()).isEqualTo("frozenset()");

        PySet fresh = PySet.collected(null);
        assertThat(fresh.getItems()).isEmpty();
        assertThat(fresh.repr()).isEqualTo("set()");
        assertThat(fresh.copy()).isNotSameAs(fresh);
        assertThat(PyFrozenSet.collected(null).equals(fresh)).isTrue();
        assertThat(one.equals(fresh)).isFalse();
    }

    /**
     * The hash a frozen value answers with, which is independent of the order its elements were filed in.
     *
     * <p>{@code frozenset_hash_impl} XORs a shuffled element hash for every entry, folds in the length and
     * disperses the result twice — the last step what keeps {@code frozenset({frozenset({1})})} from landing on
     * the same number as its own element — and replaces {@code -1} because that value means "no hash" to
     * CPython. Two values that compare equal have to hash alike however they were built, which is what lets a
     * frozen set be a dictionary key; a mutable one refuses the question in the same words {@code add} uses,
     * and the Java {@code hashCode} that a hash table takes inherits the refusal with it.
     */
    @Test
    void afrozenHashSetsWithoutAskingTheOrder() {
        assertThat(frozenOf(ONE, TWO).hash()).isEqualTo(frozenOf(TWO, ONE).hash());
        assertThat(frozenOf(ONE, TWO, THREE).hash()).isEqualTo(frozenOf(THREE, ONE, TWO).hash());
        assertThat(frozen(frozen(ONE)).hash()).isNotEqualTo(frozen(ONE).hash());
        assertThat(frozenOf(frozen(ONE), frozen(TWO)).hash())
                .isEqualTo(frozenOf(frozen(TWO), frozen(ONE)).hash());
        assertThat(PyFrozenSet.collected(null).hash()).isNotEqualTo(-1L);
        assertThat(frozen(ONE).hash()).isEqualTo(frozen(ONE).hash());
        assertThat(frozen(ONE).equals(mutable(ONE))).isTrue();
        refuses("TypeError: unhashable type: 'set'", () -> mutable(ONE).hash());
        assertThatThrownBy(() -> new LinkedHashSet<>().add(mutable(ONE))).isInstanceOf(PyRaise.class);

        PyDict keyed = PyDict.empty();
        PyObject marker = PyStr.of("x");
        keyed.put(frozenOf(ONE, TWO), marker);
        assertThat(keyed.get(frozenOf(TWO, ONE))).isSameAs(marker);
        assertThat(keyed.get(frozen(ONE))).isNull();
    }

    /**
     * The twelve number slots each build what CPython builds, in the left one's kind.
     *
     * <p>The eight ordinary ones answer the left operand's type — a frozen value combined with a set stays
     * frozen — and {@code __rsub__} and its three partners are the very same function with the two operands
     * swapped, which is what the filed wrapper has to get right on its own rather than by inheriting the
     * forward one. {@code __reduce__} is the ninth answer: a set files its values as a list, because only a
     * list can hold an unhashable value that a set could never keep.
     */
    @Test
    void theNumberSlotsAnswerInBothDirections() {
        PySet left = mutable(ONE, TWO);
        PyFrozenSet right = frozenOf(TWO, THREE);

        assertThat(left.binaryOp(BinaryOp.SUB, right)).isEqualTo(mutable(ONE));
        assertThat(left.binaryOp(BinaryOp.AND, right)).isEqualTo(mutable(TWO));
        assertThat(left.binaryOp(BinaryOp.XOR, right)).isEqualTo(mutable(ONE, THREE));
        assertThat(left.binaryOp(BinaryOp.OR, right)).isEqualTo(mutable(ONE, TWO, THREE));
        assertThat(right.binaryOp(BinaryOp.SUB, left)).isEqualTo(frozen(THREE));
        assertThat(right.binaryOp(BinaryOp.OR, left)).isInstanceOf(PyFrozenSet.class);

        assertThat(call(PyType.SET, "__rsub__", left, right)).isEqualTo(right.binaryOp(BinaryOp.SUB, left));
        assertThat(call(PyType.SET, "__rand__", left, right)).isEqualTo(left.binaryOp(BinaryOp.AND, right));
        assertThat(call(PyType.FROZENSET, "__ror__", right, left)).isEqualTo(right.binaryOp(BinaryOp.OR, left));
        assertThat(call(PyType.SET, "__sub__", left, right)).isEqualTo(mutable(ONE));
        refuses(
                "TypeError: unsupported operand type(s) for -: 'set' and 'list'",
                () -> Slots.binary(BinaryOp.SUB, left, list(ONE)));

        PyObject reduced = run(left, "__reduce__");
        assertThat(((PyTuple) reduced).at(0)).isSameAs(PyType.SET);
        assertThat(((PyTuple) reduced).at(2)).isSameAs(PyNone.INSTANCE);
        assertThat(((PyList) ((PyTuple) ((PyTuple) reduced).at(1)).at(0)).size()).isEqualTo(2);
        assertThat(run(frozen(ONE), "__reduce__").getType()).isSameAs(PyType.TUPLE);

        assertThat(left.richCompare(CompareOp.GE, right)).isSameAs(PyBool.FALSE);
        assertThat(left.richCompare(CompareOp.LE, right)).isSameAs(PyBool.FALSE);
        assertThat(mutable(ONE).richCompare(CompareOp.LT, mutable(ONE))).isSameAs(PyBool.FALSE);
        assertThat(mutable(ONE).richCompare(CompareOp.LE, mutable(ONE))).isSameAs(PyBool.TRUE);
        assertThat(mutable(ONE).richCompare(CompareOp.EQ, frozen(ONE))).isSameAs(PyBool.TRUE);
    }
}
