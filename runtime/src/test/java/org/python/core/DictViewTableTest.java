package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dictionaries the three dict views file, measured against CPython 3.14.8.
 *
 * <p>Corpus case 106 reads all twenty-three names of {@code dict_keys}, all six of {@code dict_values} and all
 * twenty-three of {@code dict_items} from Python, one line per row. What is pinned here is the half a script
 * cannot point at. First the two orders side by side: {@code PyDictKeys_Type} and {@code PyDictItems_Type}
 * (Objects/dictobject.c:6534 and :6646) file the six rows of {@code tp_richcompare} and the eight of {@code
 * dictviews_as_number}, while {@code PyDictValues_Type} (:6734) fills neither pointer and so files neither
 * group — the same difference that makes its {@code __hash__} a real one and the other two a trailing {@code
 * None}. Second the two identities a script cannot see: a dictionary row and the {@link Members} table's row
 * are one and the same object, and the {@code mapping} row hands out a <em>different</em> {@code
 * mappingproxy} on every read, which is what {@code dictview_mapping} does and no {@code repr} of the two
 * would tell apart.
 *
 * <p>Then the walk each {@code __reversed__} row starts, since the three reverse iterators are three Java
 * bodies and only the keys one existed before this table was filed; the refusals of {@code mapping}, which is
 * a data descriptor with no setter and so outranks a value's own write and refuses {@code del} in the same
 * words as a write; and the shorter walk of {@code isdisjoint}, which CPython takes only when the other side
 * has a length and which swaps <em>which side is iterated</em> rather than what is asked of it. The question
 * stays "is any one value common", and reading it as "are all of them common" — which is what the subset rows
 * ask — calls a two-key view and a four-member set that share one key disjoint.
 */
class DictViewTableTest {

    /** The twenty-three names {@code dict_keys} and {@code dict_items} file, in the struct's order. */
    private static final List<String> SET_LIKE_ORDER =
            List.of(
                    "__repr__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__", "__iter__",
                    "__sub__", "__rsub__", "__and__", "__rand__", "__xor__", "__rxor__", "__or__", "__ror__",
                    "__len__", "__contains__", "isdisjoint", "__reversed__", "mapping", "__doc__",
                    "__hash__");

    /** The six names {@code dict_values} files: the shared slots and nothing of the two protocols. */
    private static final List<String> VALUES_ORDER =
            List.of("__repr__", "__iter__", "__len__", "__reversed__", "mapping", "__doc__");

    /**
     * The six rows of {@code tp_richcompare} and the eight of {@code dictviews_as_number} — the fourteen the
     * values view has no part in, which are not a contiguous run because {@code __iter__} sits between them.
     */
    private static final List<String> SET_LIKE_ROWS =
            List.of(
                    "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__",
                    "__sub__", "__rsub__", "__and__", "__rand__", "__xor__", "__rxor__", "__or__", "__ror__");

    private static final PyObject ONE = PyInt.of(1);
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject A = PyStr.of("a");
    private static final PyObject B = PyStr.of("b");
    private static final PyObject C = PyStr.of("c");
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

    /** Everything an iterator has left to hand out. */
    private static List<PyObject> walked(PyObject iterator) {
        List<PyObject> out = new ArrayList<>();
        PyIterator steps = (PyIterator) iterator;
        PyObject item;
        while ((item = steps.nextOrNull()) != null) {
            out.add(item);
        }
        return out;
    }

    /** The sentence one filed member answers {@code __doc__} with. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == PyNone.INSTANCE ? null : ((PyStr) text).getValue();
    }

    /** A dict of {@code a}, {@code b} and {@code c}, each valued so that a walk shows its order. */
    private static PyDict three() {
        PyDict dict = PyDict.empty();
        dict.put(A, ONE);
        dict.put(B, TWO);
        dict.put(C, THREE);
        return dict;
    }

    /** A dict of one key. */
    private static PyDict one() {
        PyDict dict = PyDict.empty();
        dict.put(A, ONE);
        return dict;
    }

    /** A dict of the first two of those keys, the shorter side of the two walks below. */
    private static PyDict two() {
        PyDict dict = PyDict.empty();
        dict.put(A, ONE);
        dict.put(B, TWO);
        return dict;
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * Each of the three tables, complete and in the struct's order.
     *
     * <p>The three types file the same five names out of one C function each — {@code dictview_repr}, one
     * {@code tp_iter}, {@code dictview_len}, the shared {@code dictview_getset}, and the {@code tp_doc} that
     * is {@code 0} in all three — and the two set-like ones file fourteen more between them. The trailing
     * {@code None} under {@code __hash__} is not a slot row: {@code tp_hash} is null for {@code dict_keys}
     * and {@code dict_items} and no {@code __hash__} is filed beside it, so {@code type_ready_set_hash}
     * appends the entry itself, which is why it lands <em>after</em> {@code __doc__} rather than between the
     * repr and the comparisons where {@code set} files its own.
     */
    @Test
    void theTwoSetLikeViewsFileFourteenRowsTheValuesViewHasNoneOf() {
        assertThat(filed(PyType.DICT_KEYS)).isEqualTo(SET_LIKE_ORDER);
        assertThat(filed(PyType.DICT_ITEMS)).isEqualTo(SET_LIKE_ORDER);
        assertThat(filed(PyType.DICT_VALUES)).isEqualTo(VALUES_ORDER);
        assertThat(TypeDict.declared(PyType.DICT_KEYS).keys()).hasSize(23);
        assertThat(TypeDict.declared(PyType.DICT_VALUES).keys()).hasSize(6);

        for (String row : SET_LIKE_ROWS) {
            assertThat(kind(entry(PyType.DICT_KEYS, row))).isEqualTo("wrapper_descriptor");
            assertThat(entry(PyType.DICT_VALUES, row)).isNull();
        }
        assertThat(kind(entry(PyType.DICT_KEYS, "__repr__"))).isEqualTo("wrapper_descriptor");
        assertThat(kind(entry(PyType.DICT_ITEMS, "isdisjoint"))).isEqualTo("method_descriptor");
        assertThat(Arrays.asList(Members.tableOf(PyType.DICT_KEYS).names()))
                .containsExactly("isdisjoint", "__reversed__");
        assertThat(Arrays.asList(Members.tableOf(PyType.DICT_VALUES).names()))
                .containsExactly("__reversed__");

        assertThat(entry(PyType.DICT_KEYS, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(entry(PyType.DICT_VALUES, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(entry(PyType.DICT_KEYS, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(entry(PyType.DICT_ITEMS, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(entry(PyType.DICT_VALUES, "__hash__")).isNull();
        assertThat(VALUES_ORDER).doesNotContain("__eq__", "__lt__", "__sub__", "__or__", "isdisjoint");
    }

    /**
     * The one row that is neither a slot nor a method, and the answers it gives.
     *
     * <p>{@code dictview_mapping} is a {@code PyGetSetDef} with a null setter, so the read makes a fresh
     * proxy every time it is asked — the view holds the dict, not the proxy — and a write is refused by the
     * descriptor rather than by the value, in the same words as {@code del}, which reaches the same pointer
     * with nothing handed over. The receiver is checked before the getter runs, which is what makes {@code
     * dict_keys.__dict__['mapping'].__get__(3, dict_keys)} a complaint about the descriptor.
     */
    @Test
    void theWindowIsMadeAnewOnEveryReadAndCannotBeWritten() {
        PyDict dict = one();
        PyObject values = DictViews.values(dict);
        PyObject keys = DictViews.keys(dict);

        assertThat(kind(entry(PyType.DICT_VALUES, "mapping"))).isEqualTo("getset_descriptor");
        PyObject window = Slots.getAttr(values, "mapping");
        assertThat(window).isInstanceOf(PyMappingProxy.class);
        assertThat(((PyMappingProxy) window).mapping()).isSameAs(dict);
        assertThat(Slots.getAttr(values, "mapping")).isNotSameAs(window);

        dict.put(B, TWO);
        assertThat(((PyMappingProxy) Slots.getAttr(values, "mapping")).length()).isEqualTo(PyInt.of(2));
        assertThat(walked(((PyMappingProxy) Slots.getAttr(keys, "mapping")).keysIterator()))
                .containsExactly(A, B);

        assertThat(Slots.getAttr(entry(PyType.DICT_VALUES, "mapping"), "__objclass__"))
                .isSameAs(PyType.DICT_VALUES);
        assertThat(Slots.getAttr(entry(PyType.DICT_KEYS, "mapping"), "__qualname__"))
                .isEqualTo(PyStr.of("dict_keys.mapping"));
        assertThat(carried(PyType.DICT_ITEMS, "mapping"))
                .isEqualTo("dictionary that this view refers to");
        assertThat(Slots.getAttr(entry(PyType.DICT_KEYS, "mapping"), "__set__"))
                .isInstanceOf(PyObject.class);

        refuses("AttributeError: attribute 'mapping' of 'dict_keys' objects is not writable",
                () -> Slots.setAttr(keys, "mapping", dict));
        refuses("AttributeError: attribute 'mapping' of 'dict_values' objects is not writable",
                () -> Slots.setAttr(values, "mapping", PyNone.INSTANCE));
        refuses("AttributeError: attribute 'mapping' of 'dict_items' objects is not writable",
                () -> Slots.delAttr(DictViews.items(dict), "mapping"));
        refuses("TypeError: descriptor 'mapping' for 'dict_keys' objects doesn't apply to a 'int' object",
                () -> Slots.call(Slots.getAttr(entry(PyType.DICT_KEYS, "mapping"), "__get__"),
                        new PyObject[] {PyInt.of(3), PyType.DICT_KEYS}, null));
    }

    /**
     * The three reverse walks, and the refusals of the row that starts them.
     *
     * <p>{@code dictkeys_methods} files {@code __reversed__} as a {@code METH_NOARGS} method, so the row is a
     * {@code method_descriptor} however much the slot it answers looks like a wrapper, and its sentence is one
     * of the three {@code PyDoc_STRVAR}s — each naming the kind of value that walk hands out. Called from the
     * row the receiver is the only argument; a second is counted in method words, a keyword refused by the
     * same flag the table was registered with, and no receiver at all answered by {@code method_get} rather
     * than by the body.
     */
    @Test
    void eachKindWalksTheDictBackwardsAndRefusesAnExtraInItsOwnWords() {
        PyDict dict = three();
        PyObject keys = DictViews.keys(dict);
        PyObject values = DictViews.values(dict);
        PyObject items = DictViews.items(dict);

        assertThat(kind(entry(PyType.DICT_KEYS, "__reversed__"))).isEqualTo("method_descriptor");
        assertThat(entry(PyType.DICT_KEYS, "__reversed__"))
                .isSameAs(Members.tableOf(PyType.DICT_KEYS).describe("__reversed__"))
                .isSameAs(Slots.getAttr(PyType.DICT_KEYS, "__reversed__"));

        assertThat(walked(run(keys, "__reversed__"))).containsExactly(C, B, A);
        assertThat(walked(run(values, "__reversed__"))).containsExactly(THREE, TWO, ONE);
        assertThat(walked(run(items, "__reversed__"))).containsExactly(
                PyTuple.of(C, THREE), PyTuple.of(B, TWO), PyTuple.of(A, ONE));
        assertThat(walked(call(PyType.DICT_VALUES, "__reversed__", values)))
                .isEqualTo(walked(run(values, "__reversed__")));

        assertThat(carried(PyType.DICT_KEYS, "__reversed__"))
                .isEqualTo("Return a reverse iterator over the dict keys.");
        assertThat(carried(PyType.DICT_ITEMS, "__reversed__"))
                .isEqualTo("Return a reverse iterator over the dict items.");
        assertThat(carried(PyType.DICT_VALUES, "__reversed__"))
                .isEqualTo("Return a reverse iterator over the dict values.");
        // The three sentences are plain `PyDoc_STRVAR`s rather than clinic ones, so `__doc__` carries no
        // `name($self, /)\n--\n\n` header of its own and the signature is the one the flag invents.
        assertThat(Slots.getAttr(entry(PyType.DICT_VALUES, "__reversed__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /)"));

        refuses("TypeError: dict_keys.__reversed__() takes no arguments (1 given)",
                () -> call(PyType.DICT_KEYS, "__reversed__", keys, ONE));
        refuses("TypeError: dict_items.__reversed__() takes no keyword arguments",
                () -> Slots.call(entry(PyType.DICT_ITEMS, "__reversed__"),
                        new PyObject[] {items, ONE}, PyTuple.of(PyStr.interned("x"))));
        refuses("TypeError: unbound method dict_values.__reversed__() needs an argument",
                () -> call(PyType.DICT_VALUES, "__reversed__"));
        refuses("TypeError: descriptor '__reversed__' for 'dict_keys' objects doesn't apply to a 'list' object",
                () -> call(PyType.DICT_KEYS, "__reversed__", PyList.of(ONE)));
    }

    /**
     * The shorter walk of {@code isdisjoint}, which is a swap of sides and not a swap of questions.
     *
     * <p>{@code dictviews_isdisjoint} iterates the shorter of the two only when the other side has a length,
     * and asks the same thing either way: whether any one value is common. {@code all_contained_in} is the
     * subset rows' question, and taking it for this one reports {@code dict_keys()} of two keys against a
     * four-member set that holds one of them as disjoint. Two views of the same dict are not the same object,
     * so {@code self == other} is a pointer test and {@code {}.keys().isdisjoint({}.keys())} is the true the
     * empty case answers rather than a shortcut through the length.
     */
    @Test
    void isdisjointAsksWhetherAnyValueIsCommonWhicheverSideItWalks() {
        PyObject shortKeys = DictViews.keys(two());
        PyObject longKeys = DictViews.keys(three());
        PyObject emptyItems = DictViews.items(PyDict.empty());

        assertThat(kind(entry(PyType.DICT_KEYS, "isdisjoint"))).isEqualTo("method_descriptor");
        assertThat(entry(PyType.DICT_VALUES, "isdisjoint")).isNull();

        // The other side has four members and this view shares one of them: the walk moves to the view,
        // and a question about every value of the view would answer true here.
        assertThat(run(shortKeys, "isdisjoint", PySet.of(A, C, ONE, TWO))).isSameAs(PyBool.FALSE);
        assertThat(run(shortKeys, "isdisjoint", PySet.of(C, ONE, TWO, PyStr.of("z"))))
                .isSameAs(PyBool.TRUE);
        // Equal lengths, so no swap: `other` is walked and asked of the view.
        assertThat(run(longKeys, "isdisjoint", PySet.of(A, B, C))).isSameAs(PyBool.FALSE);
        assertThat(run(longKeys, "isdisjoint", PySet.of(ONE, TWO, THREE))).isSameAs(PyBool.TRUE);
        // A list is not measured, so the view is never the side that is asked about.
        assertThat(run(shortKeys, "isdisjoint", PyList.of(PyStr.of("z")))).isSameAs(PyBool.TRUE);
        assertThat(run(shortKeys, "isdisjoint", PyList.of(B))).isSameAs(PyBool.FALSE);

        // Two views of one dict are two objects, so the identical case is a pointer test and nothing else.
        assertThat(run(emptyItems, "isdisjoint", emptyItems)).isSameAs(PyBool.TRUE);
        assertThat(run(longKeys, "isdisjoint", DictViews.keys(three()))).isSameAs(PyBool.FALSE);
        assertThat(run(DictViews.items(PyDict.empty()), "isdisjoint", DictViews.items(PyDict.empty())))
                .isSameAs(PyBool.TRUE);

        // A pair that is not a two-element tuple is not a member of an items view; a list is not a key.
        assertThat(run(DictViews.items(three()), "isdisjoint", PyList.of(PyList.of(ONE))))
                .isSameAs(PyBool.TRUE);
        refuses("TypeError: cannot use 'list' as a dict key (unhashable type: 'list')",
                () -> run(shortKeys, "isdisjoint", PyList.of(PyList.of(ONE))));
        refuses("TypeError: 'int' object is not iterable", () -> run(shortKeys, "isdisjoint", ONE));
        refuses("TypeError: dict_keys.isdisjoint() takes exactly one argument (0 given)",
                () -> call(PyType.DICT_KEYS, "isdisjoint", shortKeys));
    }

    /**
     * The hash difference the two structs make, and the bare words a slot wrapper refuses in.
     *
     * <p>A values view fills neither {@code tp_richcompare} nor {@code tp_hash}, and the inheritance rule
     * copies the pair only when both are null, so it keeps {@code object}'s identity hash and is the one dict
     * view a program can put in a set. The other two reach the same name by a different route — {@code
     * PyObject_HashNotImplemented}, which {@code add_operators} files as the value {@code None} — and the
     * refusal a program reads is that entry run as a slot, not a callable refusing its argument.
     */
    @Test
    void oneOfTheThreeIsHashableAndTheOtherTwoSaySoInSlotWords() {
        PyDict dict = one();
        PyObject keys = DictViews.keys(dict);
        PyObject values = DictViews.values(dict);

        assertThat(Slots.contains(PySet.of(values), values)).isTrue();
        PyDict keyed = PyDict.empty();
        keyed.put(values, ONE);
        assertThat(keyed.get(values)).isSameAs(ONE);
        assertThat(keys.richCompare(CompareOp.EQ, PyList.of(A))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(keys.richCompare(CompareOp.LT, PySet.of(A, B))).isSameAs(PyBool.TRUE);
        assertThat(values.richCompare(CompareOp.EQ, values)).isSameAs(PyBool.TRUE);
        assertThat(values.richCompare(CompareOp.LT, values)).isSameAs(PyNotImplemented.INSTANCE);
        refuses("TypeError: unhashable type: 'dict_keys'", () -> keys.hash());
        refuses("TypeError: unhashable type: 'dict_items'", () -> DictViews.items(dict).hash());

        refuses(
                "TypeError: descriptor '__len__' requires a 'dict_keys' object but received a 'list'",
                () -> call(PyType.DICT_KEYS, "__len__", PyList.of(ONE)));
        refuses("TypeError: descriptor '__len__' of 'dict_keys' object needs an argument",
                () -> call(PyType.DICT_KEYS, "__len__"));
        refuses("TypeError: expected 1 argument, got 0",
                () -> call(PyType.DICT_ITEMS, "__contains__", DictViews.items(dict)));
        refuses("TypeError: expected 1 argument, got 2",
                () -> call(PyType.DICT_KEYS, "__sub__", keys, keys, keys));
        refuses("TypeError: descriptor '__len__' requires a 'dict_keys' object but received a 'dict_values'",
                () -> call(PyType.DICT_KEYS, "__len__", values));
        assertThat(call(PyType.DICT_VALUES, "__len__", values)).isEqualTo(PyInt.of(1));
    }
}
