package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dictionary {@code dict} files in its own name, measured against CPython 3.14.8.
 *
 * <p>Corpus case 64 reads the thirty-four names of a mapping from Python, one line per name, and asks what each
 * entry answers when called. What is asserted here is the half a script cannot point at: the table as the
 * complete ordered map it is, since a script can only read the names of a type this runtime fills and cannot say
 * which of the runtime's own lists put a name where it landed; the identity between a dictionary entry and the
 * object the same type's {@link Members} table hands out, which is how the fourteen descriptors written into
 * {@code mapp_methods} are caught if the two hand-written lists ever disagree — and how the one
 * {@code classmethod_descriptor} filed out of the dictionary is told apart from the one filed out of the table;
 * what {@code __ror__} keeps of its two operands, which a program can only infer from the order of a {@code
 * repr}; that a lookup asks the key to hash before it knows whether there is anything to find, a rule that
 * exists only because {@link java.util.LinkedHashMap} answers a miss from an empty map without asking at all; and
 * the one merge body read as two names, {@code dict_init}'s complaint about a count spelling the class where
 * {@code update}'s spells the method.
 */
class DictDictionaryTest {

    /** The thirty-four names {@code dict} files, in the order its dictionary answers them. */
    private static final List<String> DICT_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__iter__", "__init__", "__or__", "__ror__", "__ior__", "__len__", "__getitem__",
                    "__setitem__", "__delitem__", "__contains__", "__sizeof__", "get", "setdefault", "pop",
                    "popitem", "keys", "items", "values", "update", "fromkeys", "clear", "copy", "__reversed__",
                    "__class_getitem__", "__doc__");

    /** The fourteen names the method table files as {@code method_descriptor}s. */
    private static final List<String> DESCRIPTORS =
            List.of(
                    "__getitem__", "__contains__", "__sizeof__", "get", "setdefault", "pop", "popitem", "keys",
                    "items", "values", "update", "clear", "copy", "__reversed__");

    /** The fifteen slot wrappers a mapping is measured, compared and merged by. */
    private static final List<String> WRAPPERS =
            List.of(
                    "__repr__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__", "__iter__",
                    "__init__", "__or__", "__ror__", "__ior__", "__len__", "__setitem__", "__delitem__");

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    private static String kind(PyObject member) {
        return member.getType().name();
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

    /** {@code f(x=1)} as the calling convention gives it: the name beside the trailing keyword value. */
    private static PyTuple oneKeyword() {
        return PyTuple.of(PyStr.interned("x"));
    }

    /** A dictionary written out as key and value, in the order they are written. */
    private static PyDict mapping(PyObject... entries) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < entries.length; at += 2) {
            dict.put(entries[at], entries[at + 1]);
        }
        return dict;
    }

    private static PyObject key(String name) {
        return PyStr.of(name);
    }

    private static PyObject call(PyObject callable, PyObject... arguments) {
        return Slots.call(callable, arguments, null);
    }

    /** The sentence one filed member answers {@code __doc__} with. */
    private static String carried(String name) {
        return ((PyStr) Slots.getAttr(entry(PyType.DICT, name), "__doc__")).getValue();
    }

    /** The sentence a filed value holds, read out of the dictionary rather than off a type. */
    private static String sentence(PyType kind, String name) {
        return ((PyStr) entry(kind, name)).getValue();
    }

    /** {@code TypeError}, {@code KeyError} and {@code ValueError} in CPython's own words. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** A key that counts how often the dictionary asked it to hash. */
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

    /** A key whose own hash refuses with something that is not the unhashable {@code TypeError}. */
    private static final class Grumpy extends PyObject {

        Grumpy() {
            super(PyType.OBJECT);
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
     * Each of the three lists that make up a mapping's namespace, complete and in the reference's order.
     *
     * <p>The order is a fact about CPython's source rather than about this runtime's: {@code PyDict_Type} writes
     * {@code tp_dictoffset} and the slots in struct order, and {@code mapp_methods} then files its names with
     * {@code __getitem__} — a {@code METH_O} beside {@code mp_length}, not a copy of {@code mp_subscript} — and
     * with {@code fromkeys} in the middle rather than at either end. Fifteen names of the thirty-four come from
     * the method table and so are the very objects {@link Members#describe} hands out; {@code __class_getitem__}
     * is the one {@code classmethod_descriptor} that is not, which is why a {@link Members} table asked for it
     * says it files nothing. {@code __str__} is a third case: a mapping leaves {@code tp_str} null and reads
     * through {@code object}, so the name is inherited and never filed.
     */
    @Test
    void theMappingFilesEveryNameItAnswers() {
        assertThat(filed(PyType.DICT)).isEqualTo(DICT_ORDER);
        assertThat(TypeDict.declared(PyType.DICT).keys()).hasSize(34);
        assertThat(Arrays.asList(Members.tableOf(PyType.DICT).names()))
                .containsExactlyInAnyOrder(
                        "__getitem__", "__contains__", "__sizeof__", "__reversed__", "get", "items", "keys",
                        "pop", "popitem", "setdefault", "update", "values", "clear", "copy", "fromkeys");

        // One object in both places, so `dict.get is dict.__dict__["get"]` survives every read — and the table's
        // own declaration order, which is not the dictionary's, is the only evidence a script cannot gather.
        assertThat(entry(PyType.DICT, "__getitem__"))
                .isSameAs(Slots.getAttr(PyType.DICT, "__getitem__"))
                .isSameAs(Members.tableOf(PyType.DICT).describe("__getitem__"));
        assertThat(entry(PyType.DICT, "get"))
                .isSameAs(Slots.getAttr(PyType.DICT, "get"))
                .isSameAs(Members.tableOf(PyType.DICT).describe("get"));
        assertThat(entry(PyType.DICT, "fromkeys"))
                .isSameAs(Members.tableOf(PyType.DICT).describe("fromkeys"));
        assertThat(Members.tableOf(PyType.DICT).describe("__class_getitem__"))
                .isSameAs(PyNotImplemented.INSTANCE);

        assertThat(filed(PyType.DICT)).doesNotContain("__str__", "__format__", "__call__", "__reduce__");
        assertThat(Slots.getAttr(PyType.DICT, "__str__"))
                .isSameAs(Slots.getAttr(PyType.OBJECT, "__str__"));

        // `dict_doc`, filed as a value: nine lines, opening with the constructor that takes nothing and
        // closing with the example the last of the four spells out.
        assertThat(sentence(PyType.DICT, "__doc__"))
                .startsWith("dict() -> new empty dictionary")
                .endsWith("dict(one=1, two=2)");
        assertThat(sentence(PyType.DICT, "__doc__").split("\n")).hasSize(9);
    }

    /**
     * The sentence every filed name carries, which is text copied out of CPython's tables rather than written
     * here.
     *
     * <p>The fifteen wrappers speak their {@code slotdefs} row and the fourteen methods the tail of their
     * {@code mapp_methods} row — the head of a clinic row belongs to {@code __text_signature__}, a name this
     * runtime does not file, so what is left is the prose and its blank lines. The read that is not the
     * dictionary's — the method bound to a value, the class method bound to the class — answers with the same
     * text, because it is the table that holds it and not the object the call reaches.
     */
    @Test
    void everyFiledNameCarriesTheSentenceItsTableWrites() {
        for (String name : WRAPPERS) {
            assertThat(carried(name)).as("dict.%s", name).isNotEmpty();
        }
        for (String name : DESCRIPTORS) {
            assertThat(carried(name)).as("dict.%s", name).isNotEmpty();
        }
        assertThat(carried("__new__"))
                .isEqualTo("Create and return a new object.  See help(type) for accurate signature.");
        assertThat(carried("__ror__")).isEqualTo("Return value|self.");

        // The tail of two clinic rows, kept paragraph-for-paragraph: the one blank line is the separator the
        // signature was cut at, and the line breaks are CPython's source's, not a wrap made here.
        assertThat(carried("pop"))
                .isEqualTo(
                        "D.pop(k[,d]) -> v, remove specified key and return the corresponding value.\n"
                                + "\n"
                                + "If the key is not found, return the default if given; otherwise,\n"
                                + "raise a KeyError.");
        assertThat(carried("update"))
                .startsWith("D.update([E, ]**F) -> None.  Update D from mapping/iterable E and F.\n")
                .endsWith("In either case, this is followed by: for k in F:  D[k] = F[k]");

        assertThat(carried("fromkeys"))
                .isEqualTo("Create a new dictionary with keys from iterable and values set to value.");
        assertThat(carried("__class_getitem__"))
                .isEqualTo(
                        "dicts are generic over two types, signifying (respectively) the types of their keys"
                                + " and values");

        // The same sentence off a bound read, and the same sentence off the value's own read of a method.
        PyObject dict = mapping(key("a"), PyInt.ONE);
        assertThat(carried("get"))
                .isEqualTo(((PyStr) Slots.getAttr(Slots.getAttr(PyType.DICT, "get"), "__doc__")).getValue())
                .isEqualTo(((PyStr) Slots.getAttr(Slots.getAttr(dict, "get"), "__doc__")).getValue());
    }

    /**
     * Which kind each name was filed as, and the two places a {@code classmethod_descriptor} comes from.
     *
     * <p>{@code dict} is the first of these types to file a name that is neither a slot nor a method nor the
     * constructor: {@code __or__}, {@code __ror__} and {@code __ior__} are PEP 584's three, copied out of
     * {@code tp_as_number} beside the six comparisons that cannot answer. The two class methods read as one kind
     * in Python and are two objects here — {@code fromkeys} is the {@code METH_CLASS} entry of the method table,
     * {@code __class_getitem__} a member of the dictionary's own list — and the fourteen descriptors are the
     * only names whose argument counts are written by the method rather than by the slot that wraps it.
     */
    @Test
    void theSlotsAreFiledAsWrappersAndTheMethodsAsDescriptors() {
        for (String name : WRAPPERS) {
            assertThat(kind(entry(PyType.DICT, name))).as("dict.%s", name).isEqualTo("wrapper_descriptor");
        }
        for (String name : DESCRIPTORS) {
            assertThat(kind(entry(PyType.DICT, name))).as("dict.%s", name).isEqualTo("method_descriptor");
        }

        assertThat(kind(entry(PyType.DICT, "fromkeys"))).isEqualTo("classmethod_descriptor");
        assertThat(kind(entry(PyType.DICT, "__class_getitem__"))).isEqualTo("classmethod_descriptor");
        assertThat(entry(PyType.DICT, "fromkeys")).isInstanceOf(PyNativeFunction.class);
        assertThat(entry(PyType.DICT, "__class_getitem__")).isNotInstanceOf(PyNativeFunction.class);
        assertThat(kind(entry(PyType.DICT, "__new__"))).isEqualTo("builtin_function_or_method");
        assertThat(kind(entry(PyType.DICT, "__hash__"))).isEqualTo("NoneType");
        assertThat(kind(entry(PyType.DICT, "__doc__"))).isEqualTo("str");

        // The three names of a merge, filed for a mapping and for nothing else: `list` and `bytes` have no
        // `nb_or` to copy, and so no `__or__` in their dictionaries at all.
        assertThat(filed(PyType.LIST)).doesNotContain("__or__", "__ror__", "__ior__");
        assertThat(filed(PyType.BYTES)).doesNotContain("__or__", "__ror__", "__ior__");
        assertThat(filed(PyType.DICT).indexOf("__or__")).isEqualTo(11);
    }

    /**
     * A mutable mapping files {@code None} where an immutable one files a slot.
     *
     * <p>{@code dict.__hash__ = None} is the whole of why {@code hash({})} complains about unhashability rather
     * than about a missing name, and the value filed under it is the very same {@link PyNone} the runtime hands
     * out everywhere. It is also what makes a dictionary an unusable key, and that refusal is the dictionary's own
     * wording: {@code PyDict_SetItem} names the key it was handed and the type inside it that refused, so a tuple
     * holding a list is complained about as a tuple.
     */
    @Test
    void aMutableMappingFilesNoneWhereItsCousinFilesASlot() {
        assertThat(entry(PyType.DICT, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(PyType.DICT, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(mapping(), "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(entry(PyType.LIST, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(kind(entry(PyType.TUPLE, "__hash__"))).isEqualTo("wrapper_descriptor");

        refuses("TypeError: unhashable type: 'dict'", () -> mapping().hash());
        // The read of the name a mapping has pushed away is `None`, so calling it is a complaint about `None` and
        // not the unhashable sentence.
        refuses(
                "TypeError: 'NoneType' object is not callable",
                () -> call(Slots.getAttr(PyType.DICT, "__hash__"), mapping()));

        refuses(
                "TypeError: cannot use 'dict' as a dict key (unhashable type: 'dict')",
                () -> call(entry(PyType.DICT, "__setitem__"), PyDict.empty(), mapping(), PyInt.ONE));
        refuses(
                "TypeError: cannot use 'tuple' as a dict key (unhashable type: 'list')",
                () -> PyDict.empty().put(PyTuple.of(PyInt.ONE, PyList.of(PyInt.ONE)), PyInt.ONE));
    }

    /**
     * The six comparison wrappers, of which only two can answer, and the four that decline even between
     * dictionaries.
     *
     * <p>{@code dict_richcompare} checks <em>both</em> operands before it compares anything and returns {@code
     * Py_NotImplemented} for the four orderings whatever it is asked about — so a read of {@code __lt__} out of
     * the dictionary hands back {@code NotImplemented} for two dictionaries where the same call on two lists
     * hands back a bool. What a person reads is therefore {@link Slots#compare}'s sentence naming the two types,
     * which is a different fact from the one the corpus prints: the slot's own answer is only visible from inside
     * the runtime. {@code dict.__eq__} against a list declines too, and the {@code ==} falls back on identity
     * only after the list's slot declines as well.
     */
    @Test
    void theSixComparisonWrappersOfWhichOnlyTwoAnswer() {
        PyObject left = mapping(key("a"), PyInt.ONE);
        PyObject same = mapping(key("a"), PyInt.ONE);
        PyObject wider = mapping(key("a"), PyInt.ONE, key("b"), PyInt.of(2));
        PyObject otherValue = mapping(key("a"), PyInt.of(2));

        assertThat(call(entry(PyType.DICT, "__eq__"), left, same)).isSameAs(PyBool.TRUE);
        assertThat(call(entry(PyType.DICT, "__ne__"), left, same)).isSameAs(PyBool.FALSE);
        // Length is read before value, and it is the second check that tells `{a: 1}` from `{a: 2}`.
        assertThat(call(entry(PyType.DICT, "__eq__"), left, wider)).isSameAs(PyBool.FALSE);
        assertThat(call(entry(PyType.DICT, "__eq__"), left, otherValue)).isSameAs(PyBool.FALSE);

        for (String name : List.of("__lt__", "__le__", "__gt__", "__ge__")) {
            assertThat(call(entry(PyType.DICT, name), left, same)).as("dict.%s", name)
                    .isSameAs(PyNotImplemented.INSTANCE);
        }
        assertThat(left.richCompare(CompareOp.EQ, PyList.of(PyInt.ONE))).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(entry(PyType.LIST, "__lt__"), PyList.of(PyInt.ONE), PyList.of(PyInt.of(2))))
                .isSameAs(PyBool.TRUE);

        refuses(
                "TypeError: '<' not supported between instances of 'dict' and 'dict'",
                () -> Slots.compare(CompareOp.LT, left, same));
        refuses(
                "TypeError: '<' not supported between instances of 'dict' and 'int'",
                () -> Slots.compare(CompareOp.LT, left, PyInt.ONE));
        assertThat(Slots.compare(CompareOp.EQ, left, PyTuple.of(PyTuple.of(key("a"), PyInt.ONE))))
                .isSameAs(PyBool.FALSE);

        // `wrap_richcompare` counts bare, and counts the second operand as its one argument.
        refuses("TypeError: expected 1 argument, got 0", () -> call(entry(PyType.DICT, "__eq__"), left));
        refuses(
                "TypeError: expected 1 argument, got 2",
                () -> call(entry(PyType.DICT, "__eq__"), left, same, wider));

        // A window over a dictionary is a mapping to a mapping: `dict(p)` reads the dictionary underneath the
        // proxy rather than the keys the proxy iterates, which `Slots.isMapping` cannot tell.
        PyObject window = Slots.getAttr(PyType.DICT, "__dict__");
        assertThat(TypeDict.declared(PyType.DICT).copy().eq(window)).isTrue();
        PyDict copied = PyDict.empty();
        copied.merge(window);
        assertThat(copied.keys()).hasSize(34);
    }

    /**
     * The three halves of the merge operator, and the order the reflected one keeps.
     *
     * <p>{@code dict.__or__(d, e)} is {@code PyDict_Copy(d)} with {@code e} merged over it, so the keys keep the
     * order of the dictionary that was asked and the values come from the one that answered; {@code __ror__} is
     * the same C function with its two operands swapped, which a script can only see as the order of a
     * {@code repr} and cannot pin to the call that made it. Both decline a non-dictionary rather than complaining
     * about it, and only {@link Slots#binary} knows both sides did. {@code __ior__} is the only one of the three
     * that hands the receiver back as itself.
     */
    @Test
    void theReflectedMergeBuildsFromTheRightHandDictionary() {
        PyDict left = mapping(key("b"), PyInt.of(3));
        PyDict right = mapping(key("b"), PyInt.of(2), key("a"), PyInt.ONE);

        assertThat(((PyDict) call(entry(PyType.DICT, "__or__"), left, right)).repr()).isEqualTo("{'b': 2, 'a': 1}");
        assertThat(((PyDict) call(entry(PyType.DICT, "__ror__"), left, right)).repr())
                .isEqualTo("{'b': 3, 'a': 1}");
        assertThat((PyObject) call(entry(PyType.DICT, "__or__"), left, right)).isNotSameAs(left);
        assertThat(left.repr()).isEqualTo("{'b': 3}");

        PyObject items = PyList.of(PyInt.ONE);
        assertThat(call(entry(PyType.DICT, "__or__"), left, items)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(call(entry(PyType.DICT, "__ror__"), left, items)).isSameAs(PyNotImplemented.INSTANCE);
        refuses(
                "TypeError: unsupported operand type(s) for |: 'dict' and 'list'",
                () -> Slots.binary(BinaryOp.OR, left, items));
        refuses(
                "TypeError: unsupported operand type(s) for |: 'list' and 'dict'",
                () -> Slots.binary(BinaryOp.OR, items, left));

        PyObject merged = call(entry(PyType.DICT, "__ior__"), left, right);
        assertThat(merged).isSameAs(left);
        assertThat(left.repr()).isEqualTo("{'b': 2, 'a': 1}");

        // The in-place half merges what `update` merges: a sequence of pairs, with the two refusals read off the
        // element that is not one.
        call(entry(PyType.DICT, "__ior__"), left, PyTuple.of(PyTuple.of(key("k"), PyInt.ONE)));
        assertThat(left.repr()).isEqualTo("{'b': 2, 'a': 1, 'k': 1}");
        refuses(
                "TypeError: 'int' object is not iterable",
                () -> call(entry(PyType.DICT, "__ior__"), left, PyInt.of(5)));
        refuses(
                "TypeError: object is not iterable",
                () -> call(entry(PyType.DICT, "__ior__"), left, PyList.of(PyInt.ONE)));

        // `__ror__` of a non-mapping is the same decline the left half answers, so a list on the left never
        // reaches a sentence about dictionaries.
        assertThat(left.binaryOp(BinaryOp.OR, items)).isSameAs(PyNotImplemented.INSTANCE);
    }

    /**
     * A subscript counts itself, a set item counts its receiver, and the two readings disagree by one.
     *
     * <p>{@code mp_subscript} is the only half of {@code tp_as_mapping} that {@code mapp_methods} does not copy
     * out, so {@code __getitem__} and {@code __contains__} are {@code METH_O}s answering in vectorcall words while
     * {@code __len__}, {@code __setitem__} and {@code __delitem__} beside them are counted by {@code
     * wrapperdescr_call}: the bare number for the two that take one argument, and {@code __setitem__}'s own name
     * for the slot that takes two. A descriptor reached with no receiver at all is a third sentence again, and
     * one no Python program can write, because a read through the type supplies it.
     */
    @Test
    void aSubscriptCountsItselfAndASetItemCountsBare() {
        PyObject dict = mapping(key("a"), PyInt.ONE);

        refuses(
                "TypeError: dict.__getitem__() takes exactly one argument (0 given)",
                () -> call(entry(PyType.DICT, "__getitem__"), dict));
        refuses(
                "TypeError: dict.__contains__() takes exactly one argument (0 given)",
                () -> call(entry(PyType.DICT, "__contains__"), dict));
        refuses(
                "TypeError: descriptor '__getitem__' for 'dict' objects doesn't apply to a 'int' object",
                () -> call(entry(PyType.DICT, "__getitem__"), PyInt.of(42), key("a")));
        refuses(
                "TypeError: unbound method dict.get() needs an argument",
                () -> call(entry(PyType.DICT, "get")));

        // The two counts that name the method rather than the call.
        refuses("TypeError: dict.__reversed__() takes no arguments (1 given)",
                () -> call(entry(PyType.DICT, "__reversed__"), dict, PyInt.ONE));
        refuses("TypeError: dict.__sizeof__() takes no arguments (1 given)",
                () -> call(entry(PyType.DICT, "__sizeof__"), dict, PyInt.ONE));
        refuses("TypeError: dict.keys() takes no arguments (1 given)",
                () -> call(Slots.getAttr(dict, "keys"), PyInt.ONE));
        refuses(
                "TypeError: dict.popitem() takes no keyword arguments",
                () -> Slots.call(Slots.getAttr(dict, "popitem"), new PyObject[0], oneKeyword()));

        // The slot halves, counted bare — including `__or__`, whose one argument is the other dictionary.
        refuses("TypeError: expected 0 arguments, got 1", () -> call(entry(PyType.DICT, "__len__"), dict, PyInt.ONE));
        refuses(
                "TypeError: descriptor '__len__' of 'dict' object needs an argument",
                () -> call(entry(PyType.DICT, "__len__")));
        refuses(
                "TypeError: wrapper __len__() takes no keyword arguments",
                () -> Slots.call(
                        entry(PyType.DICT, "__len__"), new PyObject[] {dict, PyInt.ONE}, oneKeyword()));
        refuses(
                "TypeError: __setitem__ expected 2 arguments, got 1",
                () -> call(entry(PyType.DICT, "__setitem__"), dict, key("a")));
        refuses("TypeError: expected 1 argument, got 0", () -> call(entry(PyType.DICT, "__delitem__"), dict));
        refuses("TypeError: expected 1 argument, got 0", () -> call(entry(PyType.DICT, "__or__"), dict));

        // The receiver check comes before the count, and for `__init__` there is no count at all: `tp_init` is
        // handed the whole argument list, which is why the two gates of `wrapperdescr_call` run for it while
        // the keyword refusal and the count are the slot's own to make.
        refuses(
                "TypeError: descriptor '__init__' requires a 'dict' object but received a 'int'",
                () -> call(entry(PyType.DICT, "__init__"), PyInt.of(42)));
        refuses(
                "TypeError: descriptor '__init__' requires a 'dict' object but received a 'int'",
                () -> Slots.call(
                        entry(PyType.DICT, "__init__"),
                        new PyObject[] {PyInt.of(42), PyInt.ONE},
                        oneKeyword()));
        refuses(
                "TypeError: descriptor '__init__' of 'dict' object needs an argument",
                () -> call(entry(PyType.DICT, "__init__")));
        refuses(
                "TypeError: descriptor '__init__' of 'object' object needs an argument",
                () -> call(entry(PyType.OBJECT, "__init__")));
        assertThat(call(entry(PyType.DICT, "__init__"), PyDict.empty(), PyDict.empty()))
                .as("a second dictionary past the receiver is the slot's to count, and it counts none")
                .isSameAs(PyNone.INSTANCE);
        assertThat(call(entry(PyType.OBJECT, "__init__"), PyInt.of(42), PyInt.ONE))
                .as("`object.__init__(3, 4)` answers rather than complaining, keywords and all")
                .isSameAs(PyNone.INSTANCE);
        refuses(
                "TypeError: list() takes no keyword arguments",
                () -> Slots.call(
                        entry(PyType.LIST, "__init__"),
                        new PyObject[] {PyList.empty(), PyInt.ONE},
                        oneKeyword()));
        refuses(
                "TypeError: descriptor '__call__' of 'type' object needs an argument",
                () -> call(entry(PyType.TYPE, "__call__")));
        refuses(
                "TypeError: descriptor '__call__' requires a 'type' object but received a 'int'",
                () -> call(entry(PyType.TYPE, "__call__"), PyInt.of(42)));
        assertThat(call(entry(PyType.TYPE, "__call__"), PyType.DICT)).isEqualTo(PyDict.empty());
        assertThat(call(entry(PyType.DICT, "__new__"), PyType.DICT, PyInt.of(3))).isEqualTo(PyDict.empty());
    }

    /**
     * The hash a lookup takes before it knows whether there is anything to find.
     *
     * <p>{@code dict_find} asks the key to hash first, so an unhashable key read from a dictionary with nothing in
     * it is refused rather than reported as a miss — and a key whose {@code __hash__} raises is heard raising,
     * because only the {@code TypeError} of an unhashable value is rewritten into the dictionary's own words. An
     * empty {@link java.util.LinkedHashMap} answers both reads without touching the key, which is what
     * {@link PyDict}'s {@code hashKey} exists for; the guard that keeps it to an empty map is what keeps a
     * dictionary that has entries hashing exactly once, as it always did. {@code _PyDict_Pop} runs the other way
     * and reads the entry count first, so an empty pop hands back its default without asking anything at all.
     */
    @Test
    void aLookupHashesItsKeyBeforeItKnowsWhetherThereIsOne() {
        Counted missed = new Counted();
        assertThat(PyDict.empty().get(missed)).isNull();
        assertThat(missed.asks).as("an empty read asks where the map itself would not have").isEqualTo(1);

        PyDict kept = mapping(key("a"), PyInt.ONE);
        Counted stored = new Counted();
        kept.put(stored, PyInt.ONE);
        assertThat(stored.asks).as("the insert asks").isEqualTo(1);
        kept.get(stored);
        kept.hasKey(stored);
        assertThat(stored.asks).as("a dictionary with entries asks once per read, not twice").isEqualTo(3);

        // The same key on a dictionary that has nothing: refused by the dict's own wording, whichever way it was
        // asked.
        PyObject unusable = PyList.of(key("a"));
        refuses(
                "TypeError: cannot use 'list' as a dict key (unhashable type: 'list')",
                () -> Slots.getItem(PyDict.empty(), unusable));
        refuses(
                "TypeError: cannot use 'list' as a dict key (unhashable type: 'list')",
                () -> PyDict.empty().get(unusable));
        refuses(
                "TypeError: cannot use 'list' as a dict key (unhashable type: 'list')",
                () -> PyDict.empty().contains(unusable));
        refuses(
                "TypeError: cannot use 'list' as a dict key (unhashable type: 'list')",
                () -> call(Slots.getAttr(PyDict.empty(), "setdefault"), unusable));
        refuses(
                "TypeError: cannot use 'list' as a dict key (unhashable type: 'list')",
                () -> PyDict.empty().delItem(unusable));

        // An exception that is not the unhashable one is the key's own, and is not rewritten.
        refuses("ValueError: no hash for you", () -> PyDict.empty().get(new Grumpy()));
        refuses("ValueError: no hash for you", () -> kept.get(new Grumpy()));

        // `_PyDict_Pop`: the count first, so an empty dictionary never hears from the key at all.
        Counted unpopped = new Counted();
        assertThat(call(Slots.getAttr(PyDict.empty(), "pop"), unpopped, key("gone"))).isEqualTo(key("gone"));
        assertThat(unpopped.asks).as("a pop that has nothing to pop does not ask").isZero();
        refuses(
                "KeyError: ['a']",
                () -> call(Slots.getAttr(PyDict.empty(), "pop"), PyList.of(key("a"))));
        refuses(
                "TypeError: cannot use 'list' as a dict key (unhashable type: 'list')",
                () -> call(Slots.getAttr(kept, "pop"), unusable));
    }

    /**
     * The constructor merges and the sequence constructor clears, and one merge body answers to two names.
     *
     * <p>{@code dict_init} is {@code PyDict_Merge} over the receiver, so no argument changes nothing, {@code
     * d.__init__(d)} is a no-op rather than an accident, and a refused merge leaves the dictionary exactly as it
     * was. {@code list___init___impl} is the opposite order — parse, clear, extend — which is what makes {@code
     * l.__init__(l)} an empty list where {@code l.extend(l)} doubles one, and what makes a refused {@code
     * l.__init__(5)} leave nothing behind. The two counts of one {@code mergeFrom} are the last part a script
     * cannot point at: {@code dict_init} hands {@code _PyArg_CheckPositional} the class and {@code dict_update}
     * hands it the method, over the same body.
     */
    @Test
    void theConstructorMergesAndTheSequenceConstructorClears() {
        PyDict kept = mapping(key("a"), PyInt.ONE);
        call(entry(PyType.DICT, "__init__"), kept);
        assertThat(kept.repr()).isEqualTo("{'a': 1}");
        call(entry(PyType.DICT, "__init__"), kept, kept);
        assertThat(kept.repr()).isEqualTo("{'a': 1}");
        call(entry(PyType.DICT, "__init__"), kept, mapping(key("a"), PyInt.of(2), key("b"), PyInt.ONE));
        assertThat(kept.repr()).isEqualTo("{'a': 2, 'b': 1}");

        refuses(
                "TypeError: 'int' object is not iterable",
                () -> call(entry(PyType.DICT, "__init__"), kept, PyInt.of(5)));
        assertThat(kept.repr()).as("a refused merge changes nothing").isEqualTo("{'a': 2, 'b': 1}");

        // A list, cleared before the argument is read: itself emptied, and a refusal emptied too.
        PyList doubled = PyList.of(PyInt.ONE, PyInt.of(2));
        call(entry(PyType.LIST, "__init__"), doubled, doubled);
        assertThat(doubled.repr()).isEqualTo("[]");
        PyList extended = PyList.of(PyInt.ONE, PyInt.of(2));
        call(Slots.getAttr(extended, "extend"), extended);
        assertThat(extended.repr()).isEqualTo("[1, 2, 1, 2]");
        PyList emptied = PyList.of(PyInt.ONE);
        refuses(
                "TypeError: 'int' object is not iterable",
                () -> call(entry(PyType.LIST, "__init__"), emptied, PyInt.of(5)));
        assertThat(emptied.repr()).as("a refused list init has already cleared").isEqualTo("[]");

        // One body, two names written into its complaint, and only the positional arguments counted.
        refuses(
                "TypeError: dict expected at most 1 argument, got 2",
                () -> call(entry(PyType.DICT, "__init__"), kept, PyDict.empty(), PyDict.empty()));
        refuses(
                "TypeError: update expected at most 1 argument, got 2",
                () -> call(Slots.getAttr(kept, "update"), PyDict.empty(), PyDict.empty()));
        // Only the positional arguments are counted there, which is what leaves `update(d, x=1)` one argument.
        Slots.call(
                Slots.getAttr(kept, "update"), new PyObject[] {PyDict.empty(), PyInt.ONE}, oneKeyword());
        assertThat(kept.repr()).isEqualTo("{'a': 2, 'b': 1, 'x': 1}");

        // `dict_new` reads the class and throws the rest away; the constructor runs `__init__` over it.
        PyObject pairs = PyList.of(PyTuple.of(key("a"), PyInt.ONE));
        assertThat(((PyDict) call(entry(PyType.DICT, "__new__"), PyType.DICT, pairs)).repr()).isEqualTo("{}");
        assertThat(Constructors.instantiate(PyType.DICT, new PyObject[] {pairs}, null))
                .isEqualTo(mapping(key("a"), PyInt.ONE));
        refuses(
                "TypeError: 'int' object is not iterable",
                () -> Constructors.instantiate(PyType.DICT, new PyObject[] {PyInt.of(5)}, null));
    }

    /**
     * The three gates each of the two {@code classmethod_descriptor}s runs, before either body counts.
     *
     * <p>{@code classmethoddescr_call} takes the class out of the argument list and checks it three times — that
     * one argument arrived, that it is a type, that it is a type the method could build a value of — and only then
     * hands the rest to the body. A read through the type can never show the third refusal, because the read
     * supplies a class that is a subtype of its own by construction; and {@code dict} is the first type whose
     * dictionary holds two of these, filed from two different places, whose refusals are worded alike and whose
     * bodies are not: {@code fromkeys} counts with the legacy words and {@code __class_getitem__} with the {@code
     * METH_O}'s.
     */
    @Test
    void theTwoClassMethodsTakeTheirClassOutOfTheArgumentsBeforeCounting() {
        PyObject fromKeys = entry(PyType.DICT, "fromkeys");
        PyObject classGetItem = entry(PyType.DICT, "__class_getitem__");

        assertThat(Slots.getAttr(fromKeys, "__objclass__")).isSameAs(PyType.DICT);
        assertThat(Slots.getAttr(classGetItem, "__objclass__")).isSameAs(PyType.DICT);
        assertThat(Slots.getAttr(fromKeys, "__qualname__")).isEqualTo(PyStr.of("dict.fromkeys"));
        // The dictionary's entry has an owner to name and no receiver to name: `__self__` is only answered once
        // a class has been bound to it.
        refuses(
                "AttributeError: 'classmethod_descriptor' object has no attribute '__self__'",
                () -> Slots.getAttr(fromKeys, "__self__"));

        refuses(
                "TypeError: descriptor 'fromkeys' of 'dict' object needs an argument",
                () -> call(fromKeys));
        refuses(
                "TypeError: descriptor 'fromkeys' for type 'dict' needs a type, not a 'dict' as arg 2",
                () -> call(fromKeys, mapping(key("a"), PyInt.ONE), PyStr.of("ab")));
        refuses(
                "TypeError: descriptor 'fromkeys' requires a subtype of 'dict' but received 'list'",
                () -> call(fromKeys, PyType.LIST, PyStr.of("ab")));
        // A foreign class with the right number of arguments says nothing about either of them.
        refuses(
                "TypeError: descriptor '__class_getitem__' requires a subtype of 'dict' but received 'list'",
                () -> call(classGetItem, PyType.LIST, PyType.INT, PyType.STR));

        // Past the gates each body counts in its own family's words.
        refuses("TypeError: fromkeys expected at least 1 argument, got 0", () -> call(fromKeys, PyType.DICT));
        refuses(
                "TypeError: fromkeys expected at most 2 arguments, got 3",
                () -> call(fromKeys, PyType.DICT, PyStr.of("ab"), PyInt.ONE, PyInt.of(2)));
        refuses(
                "TypeError: dict.fromkeys() takes no keyword arguments",
                () -> Slots.call(fromKeys, new PyObject[] {PyType.DICT, PyStr.of("ab"), PyInt.ONE}, oneKeyword()));
        refuses(
                "TypeError: descriptor '__class_getitem__' for type 'dict' needs a type, not a 'str' as arg 2",
                () -> call(classGetItem, PyStr.of("ab"), PyType.INT));
        refuses(
                "TypeError: dict.__class_getitem__() takes exactly one argument (2 given)",
                () -> call(classGetItem, PyType.DICT, PyType.STR, PyType.INT));

        // The class the dictionary entry was given is the one the body keeps, and the value it builds is the
        // entry's own — one shared value, which is why two of the same key are one name.
        assertThat(((PyDict) call(fromKeys, PyType.DICT, PyStr.of("aba"))).repr())
                .isEqualTo("{'a': None, 'b': None}");
        assertThat(((PyDict) call(fromKeys, PyType.DICT, PyStr.of("ab"), PyList.empty())).repr())
                .isEqualTo("{'a': [], 'b': []}");
        assertThat(((PyObject) call(classGetItem, PyType.DICT, PyType.INT)).repr()).isEqualTo("dict[int]");

        // A read through the type supplies the class itself, so its refusals are the body's from the first.
        PyObject bound = Slots.getAttr(PyType.DICT, "fromkeys");
        assertThat(kind(bound)).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(PyType.DICT);
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__objclass__'",
                () -> Slots.getAttr(bound, "__objclass__"));
        refuses("TypeError: fromkeys expected at least 1 argument, got 0", () -> call(bound));
        // Read through a value, it binds to the type of that value — which is the same class.
        assertThat(Slots.getAttr(mapping(key("a"), PyInt.ONE), "fromkeys")).isInstanceOf(PyNativeFunction.class);
    }

    /**
     * What an alias of a mapping keeps of the key it was read with, and the two hashes it is made of.
     *
     * <p>{@code dict[str, int]} is one call with a two-element tuple key whose elements become the arguments, so
     * a mapping's alias is the one that keeps two of them and keeps them <em>in order</em>: {@code dict[str, int]}
     * and {@code dict[int, str]} are two values, where the same pair of readings of {@code list} differ only in
     * their {@code repr}. {@code dict[()]} keeps none. {@code ga_hash} XORs the origin's hash with the hash of the
     * arguments as one tuple — a relationship a program can check against itself, and the only hash this slice
     * writes down.
     */
    @Test
    void anAliasOfAMappingKeepsBothArgumentsInOrder() {
        PyObject pair = Slots.getItem(PyType.DICT, PyTuple.of(PyType.STR, PyType.INT));
        PyObject swapped = Slots.getItem(PyType.DICT, PyTuple.of(PyType.INT, PyType.STR));
        PyObject three = Slots.getItem(PyType.DICT, PyTuple.of(PyType.STR, PyType.INT, PyType.FLOAT));
        PyObject none = Slots.getItem(PyType.DICT, PyTuple.EMPTY);

        assertThat(Slots.getAttr(pair, "__origin__")).isSameAs(PyType.DICT);
        assertThat(Slots.getAttr(pair, "__args__")).isEqualTo(PyTuple.of(PyType.STR, PyType.INT));
        assertThat(Slots.getAttr(none, "__args__")).isEqualTo(PyTuple.EMPTY);
        assertThat(Slots.getAttr(pair, "__parameters__")).isEqualTo(PyTuple.EMPTY);

        assertThat(pair.repr()).isEqualTo("dict[str, int]");
        assertThat(swapped.repr()).isEqualTo("dict[int, str]");
        assertThat(three.repr()).isEqualTo("dict[str, int, float]");
        assertThat(none.repr()).isEqualTo("dict[()]");
        assertThat(Slots.getItem(PyType.DICT, PyInt.of(3)).repr()).isEqualTo("dict[3]");
        assertThat(Slots.getItem(PyType.DICT, PyType.INT).repr()).isEqualTo("dict[int]");

        // Two readings of one alias are equal and not one object; two orderings of two arguments are not equal.
        assertThat(Slots.getItem(PyType.DICT, PyTuple.of(PyType.STR, PyType.INT))).isEqualTo(pair).isNotSameAs(pair);
        assertThat(pair).isNotEqualTo(swapped);
        assertThat(pair.hash()).isEqualTo(PyType.DICT.hash() ^ PyTuple.of(PyType.STR, PyType.INT).hash());
        assertThat(none.hash()).isEqualTo(PyType.DICT.hash() ^ PyTuple.EMPTY.hash());

        assertThat(pair.getType()).isSameAs(PyType.GENERIC_ALIAS);
        refuses("TypeError: dict[str, int] is not a generic class", () -> Slots.getItem(pair, PyInt.ZERO));
    }

    /**
     * The three windows a dictionary hands out are values, not types.
     *
     * <p>A view's MRO is its own name and {@code object}, so nothing in the walk casts the value: a base of
     * {@code type} would put {@code type.__dict__} and {@code type.__dir__} in front of it, and both of those
     * getters read their receiver as a {@code PyType}. What a program reads here is therefore the missing name,
     * in the attribute lookup's own words.
     */
    @Test
    void theThreeWindowsAreValuesAndNotTypes() {
        PyObject sample = mapping(key("a"), PyInt.ONE);
        for (String name : Arrays.asList("keys", "values", "items")) {
            PyObject view = call(Slots.getAttr(sample, name));
            List<String> walk = new ArrayList<>();
            for (PyType owner : view.getType().mro()) {
                walk.add(owner.name());
            }

            assertThat(walk).containsExactly("dict_" + name, "object");
            assertThat(PyType.TYPE.instanceOf(view)).isFalse();
            refuses(
                    "AttributeError: 'dict_" + name + "' object has no attribute '__dict__'",
                    () -> Slots.getAttr(view, "__dict__"));
            refuses(
                    "AttributeError: 'dict_" + name + "' object has no attribute '__name__'",
                    () -> Slots.getAttr(view, "__name__"));
            assertThat(view.length()).isEqualTo(PyInt.ONE);
        }
    }
}
