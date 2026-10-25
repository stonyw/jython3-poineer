package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dictionaries {@code list} and {@code tuple} file in their own names, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 62 and 63 read both namespaces from Python, one line per name, and ask what each entry
 * answers when called. What is asserted here is the half a script cannot point at: the two tables as the
 * complete ordered maps they are, since a script can only read the names of a type this runtime fills and
 * cannot say which of the runtime's own lists put a name where it landed; the identity between a dictionary
 * entry and the object the same type's {@link Members} table hands out, which is how the three names {@code
 * list} files from its value class rather than copying from a slot are caught if the two hand-written lists
 * ever disagree; {@code classmethoddescr_call}'s third gate, which only a call made straight into the
 * dictionary can reach and which no Python program can point at because {@code list[int]} always supplies
 * its own class; the shape of the arguments a class method is handed once {@code __get__} has run, which is
 * the difference between two refusals that read almost alike; and the two halves of {@code ga_hash}, which a
 * program can compare only with itself and never with the reference's own numbers.
 */
class SequenceDictionaryTest {

    /** The thirty-six names {@code list} files, in the order its dictionary answers them. */
    private static final List<String> LIST_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__iter__", "__init__", "__len__", "__getitem__", "__setitem__", "__delitem__",
                    "__add__", "__mul__", "__rmul__", "__contains__", "__iadd__", "__imul__", "__reversed__",
                    "__sizeof__", "clear", "copy", "append", "insert", "extend", "pop", "remove", "index",
                    "count", "reverse", "sort", "__class_getitem__", "__doc__");

    /** The twenty-one names {@code tuple} files: fifteen slots, pickle's one name, two methods, two more. */
    private static final List<String> TUPLE_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__iter__", "__len__", "__getitem__", "__add__", "__mul__", "__rmul__",
                    "__contains__", "__getnewargs__", "index", "count", "__class_getitem__", "__doc__");

    /** The entry a type files under {@code name}, out of its own dictionary rather than by reading it. */
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

    private static PyObject items(PyObject... elements) {
        return PyList.of(elements);
    }

    private static PyObject pair() {
        return PyTuple.of(PyInt.of(1), PyInt.of(2));
    }

    private static PyObject call(PyObject callable, PyObject... arguments) {
        return Slots.call(callable, arguments, null);
    }

    /** The text a value answers for a name, which is how a filed {@code __doc__} is read back. */
    private static String text(PyObject value, String name) {
        return ((PyStr) Slots.getAttr(value, name)).getValue();
    }

    /** {@code TypeError} and {@code RuntimeError} in CPython's own words. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * Each sequence's own dictionary is complete, in the reference interpreter's order, and holds the objects
     * its method table hands out.
     *
     * <p>The order is a fact about CPython's source rather than about this runtime's: {@code list} files the
     * two in-place slots between {@code __contains__} and the eleven methods of {@code list_methods}, and
     * {@code tuple} files {@code __getnewargs__} before the two of {@code tuple_methods} — so the fifteen
     * slots of one type are the eighteen of the other minus {@code __init__}, {@code __setitem__}, {@code
     * __delitem__}, {@code __iadd__} and {@code __imul__}, plus the three names {@code list} takes from its
     * value class instead of copying a slot. Those three, {@code __getitem__}, {@code __reversed__} and
     * {@code __sizeof__}, are asserted against {@link Members} by identity: the dictionary and a read of the
     * type hold one {@code PyMethodDescriptor}, and a name the value class does not keep is a runtime error
     * the table raises as it fills.
     */
    @Test
    void theTwoSequenceTypesFileEveryNameTheyAnswer() {
        assertThat(filed(PyType.LIST)).isEqualTo(LIST_ORDER);
        assertThat(filed(PyType.TUPLE)).isEqualTo(TUPLE_ORDER);
        assertThat(TypeDict.declared(PyType.LIST).keys()).hasSize(36);
        assertThat(TypeDict.declared(PyType.TUPLE).keys()).hasSize(21);

        assertThat(Arrays.asList(Members.tableOf(PyType.LIST).names()))
                .containsExactlyInAnyOrder(
                        "__getitem__", "__reversed__", "__sizeof__", "clear", "copy", "append", "insert",
                        "extend", "pop", "remove", "index", "count", "reverse", "sort");
        assertThat(Arrays.asList(Members.tableOf(PyType.TUPLE).names()))
                .containsExactlyInAnyOrder("__getnewargs__", "index", "count");

        // One object in both places, so `list.__getitem__ is list.__dict__["__getitem__"]` survives every
        // read, and the same for the name that carries no sentence at all.
        assertThat(entry(PyType.LIST, "__getitem__"))
                .isSameAs(Slots.getAttr(PyType.LIST, "__getitem__"))
                .isSameAs(Members.tableOf(PyType.LIST).describe("__getitem__"));
        assertThat(entry(PyType.LIST, "sort")).isSameAs(Slots.getAttr(PyType.LIST, "sort"));
        assertThat(entry(PyType.TUPLE, "__getnewargs__"))
                .isSameAs(Members.tableOf(PyType.TUPLE).describe("__getnewargs__"));

        // The one entry of either table whose `ml_doc` is NULL: the name says nothing about itself, which is
        // the reference's own answer rather than a sentence this runtime left out.
        assertThat(Slots.getAttr(entry(PyType.TUPLE, "__getnewargs__"), "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(text(entry(PyType.LIST, "__getitem__"), "__doc__")).isEqualTo("Return self[index].");
    }

    /**
     * Which of the kinds a name was filed from, including the two a sequence files only for itself.
     *
     * <p>{@code tuple} copies every slot it files straight out of {@code tp_as_sequence} and {@code
     * tp_richcompare}, so all fifteen of its wrappers are the {@code sq_*} and {@code wrap_*} C functions;
     * {@code list} files seventeen the same way and then two names the sequence protocol has no shape for —
     * {@code __getitem__}, because {@code list_subscript} answers a slice as well as an index, and {@code
     * __init__} which it does copy, because {@code list_init} is {@code list_extend} over the receiver. The
     * two that differ between the tables by kind and not by name are {@code __hash__} and {@code
     * __getitem__}.
     */
    @Test
    void theSlotsAreFiledAsWrappersAndTheMethodsAsDescriptors() {
        for (String name : List.of("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__", "__iter__",
                "__len__", "__add__", "__mul__", "__rmul__", "__contains__", "__repr__")) {
            assertThat(kind(entry(PyType.LIST, name))).as("list.%s", name).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(PyType.TUPLE, name))).as("tuple.%s", name).isEqualTo("wrapper_descriptor");
        }

        assertThat(kind(entry(PyType.LIST, "__getitem__"))).isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.TUPLE, "__getitem__"))).isEqualTo("wrapper_descriptor");
        assertThat(kind(entry(PyType.LIST, "__reversed__"))).isEqualTo("method_descriptor");
        assertThat(filed(PyType.TUPLE)).doesNotContain("__reversed__", "__sizeof__", "__init__");
        assertThat(kind(entry(PyType.LIST, "__class_getitem__")))
                .isEqualTo("classmethod_descriptor");
        assertThat(kind(entry(PyType.TUPLE, "__class_getitem__"))).isEqualTo("classmethod_descriptor");
        assertThat(kind(entry(PyType.LIST, "__new__"))).isEqualTo("builtin_function_or_method");

        // The two sentences CPython's `list_doc` and `tuple_doc` write, filed as values.
        assertThat(kind(entry(PyType.LIST, "__doc__"))).isEqualTo("str");
        assertThat(text(PyType.LIST, "__doc__")).startsWith("Built-in mutable sequence.");
        assertThat(text(PyType.TUPLE, "__doc__")).startsWith("Built-in immutable sequence.");
    }

    /**
     * A mutable sequence files {@code None} where its immutable cousin files a slot.
     *
     * <p>{@code list.__hash__ = None} is the whole of why {@code hash([])} complains about unhashability
     * rather than about a missing name: the value in the dictionary pushes {@code object.__hash__} out of
     * reach, and it is the very same {@link PyNone} the runtime hands out everywhere rather than a fresh
     * object that happens to compare equal. {@code tuple} files its {@code tp_hash} copy beside it, and the
     * two halves of the difference are what {@code {{(): 0}} and {@code {[]: 0}} answer.
     */
    @Test
    void aMutableSequenceFilesNoneWhereItsCousinFilesASlot() {
        assertThat(entry(PyType.LIST, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(kind(entry(PyType.LIST, "__hash__"))).isEqualTo("NoneType");
        assertThat(Slots.getAttr(PyType.LIST, "__hash__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(items(), "__hash__")).isSameAs(PyNone.INSTANCE);
        refuses("TypeError: unhashable type: 'list'", () -> items().hash());

        PyObject hash = entry(PyType.TUPLE, "__hash__");
        assertThat(kind(hash)).isEqualTo("wrapper_descriptor");
        PyObject value = pair();
        assertThat(call(hash, value)).isEqualTo(PyInt.of(value.hash()));
        assertThat(items().eq(items())).isTrue();
        assertThat(PyList.empty().getType()).isSameAs(items().getType());

        // The read of the name a list has pushed away is `None`, so calling it is a complaint about `None`
        // and not the `unhashable` sentence — which is what the corpus measures for `[].__hash__([])`.
        refuses("TypeError: 'NoneType' object is not callable", () -> call(Slots.getAttr(PyType.LIST, "__hash__")));
    }

    /**
     * The third gate of {@code classmethoddescr_call}, which only a call made into the dictionary reaches.
     *
     * <p>CPython takes the class out of the argument list and checks it three times before the wrapped
     * {@code METH_O} counts anything: that one argument arrived, that it is a type, that it is a type the
     * method could build a value of. {@code list[int]} can never show the third refusal, because the read
     * supplies a class that is a subtype of its own by construction; a program has to reach into the
     * dictionary, and the runtime has to run the check the way {@code boundTo} runs it for
     * {@code bytes.fromhex} — naming the declaring type, not the one handed over.
     */
    @Test
    void theClassMethodTakesItsClassOutOfTheArgumentsBeforeCounting() {
        PyObject listEntry = entry(PyType.LIST, "__class_getitem__");
        refuses(
                "TypeError: descriptor '__class_getitem__' of 'list' object needs an argument",
                () -> call(listEntry));
        refuses(
                "TypeError: descriptor '__class_getitem__' for type 'list' needs a type, not a 'int' as arg 2",
                () -> call(listEntry, PyInt.of(3), PyType.INT));
        refuses(
                "TypeError: descriptor '__class_getitem__' requires a subtype of 'list' but received 'str'",
                () -> call(listEntry, PyType.STR, PyType.INT));

        // The subtype check runs before the count, so a foreign class with two arguments says nothing about
        // either of them.
        refuses(
                "TypeError: descriptor '__class_getitem__' requires a subtype of 'tuple' but received 'list'",
                () -> call(entry(PyType.TUPLE, "__class_getitem__"), PyType.LIST, PyType.INT, PyType.STR));

        // Past the three gates the wrapped method counts its own arguments, and the class it keeps is the one
        // that arrived — which is why `list.__dict__[...](list, int)` and `list[int]` answer the same alias.
        refuses(
                "TypeError: list.__class_getitem__() takes exactly one argument (2 given)",
                () -> call(listEntry, PyType.LIST, PyType.INT, PyType.STR));
        assertThat(((PyGenericAlias) call(listEntry, PyType.LIST, PyType.INT)).getAttr("__origin__"))
                .isSameAs(PyType.LIST);
        assertThat(call(listEntry, PyType.LIST, PyType.INT).repr()).isEqualTo("list[int]");

        // A read through the type supplies the class itself, so its refusals are the METH_O's from the first.
        PyObject bound = Slots.getAttr(PyType.LIST, "__class_getitem__");
        assertThat(kind(bound)).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(PyType.LIST);
        refuses(
                "TypeError: list.__class_getitem__() takes exactly one argument (0 given)",
                () -> call(bound));
        refuses(
                "TypeError: list.__class_getitem__() takes exactly one argument (2 given)",
                () -> call(bound, PyType.INT, PyType.STR));
        refuses("TypeError: list.__class_getitem__() takes no keyword arguments",
                () -> Slots.call(bound, new PyObject[] {PyType.INT, PyInt.ONE}, oneKeyword()));
    }

    /**
     * The two gates a mutable sequence's own {@code __setitem__} runs, and the count that names itself.
     *
     * <p>{@code wrap_ass_subscript} is one of the three C functions that hands {@code PyArg_UnpackTuple} a
     * name to say, beside {@code wrap_setattr} and {@code wrap_buffer} — so the count of a short or long
     * {@code __setitem__} reads {@code __setitem__ expected 2 arguments, got 1} where every other wrapper of
     * either table answers bare, and the keyword refusal keeps the {@code wrapper} half that the named
     * counts do not. {@code __delitem__} is {@code wrap_del_item}, which passes no name at all.
     */
    @Test
    void aSetItemWrapperNamesItselfAndADelItemWrapperDoesNot() {
        PyObject setItem = entry(PyType.LIST, "__setitem__");
        PyObject list = items(PyInt.of(1), PyInt.of(2));
        refuses(
                "TypeError: descriptor '__setitem__' of 'list' object needs an argument",
                () -> call(setItem));
        refuses(
                "TypeError: descriptor '__setitem__' requires a 'list' object but received a 'int'",
                () -> call(setItem, PyInt.of(3), PyInt.ZERO, PyInt.ZERO));
        refuses(
                "TypeError: wrapper __setitem__() takes no keyword arguments",
                () -> Slots.call(setItem, new PyObject[] {list, PyInt.ZERO, PyInt.ONE}, oneKeyword()));
        refuses("TypeError: __setitem__ expected 2 arguments, got 1", () -> call(setItem, list, PyInt.ZERO));
        refuses(
                "TypeError: __setitem__ expected 2 arguments, got 3",
                () -> call(setItem, list, PyInt.ZERO, PyInt.ONE, PyInt.of(2)));

        // The bare count, and the metatype's own named wrapper, both reached through the same gate.
        refuses(
                "TypeError: expected 1 argument, got 0",
                () -> call(entry(PyType.LIST, "__delitem__"), list));
        refuses(
                "TypeError: __setattr__ expected 2 arguments, got 1",
                () -> call(entry(PyType.TYPE, "__setattr__"), PyType.LIST, PyStr.of("x")));

        call(setItem, list, PyInt.ZERO, PyInt.of(7));
        assertThat(list.repr()).isEqualTo("[7, 2]");
    }

    /**
     * What an alias keeps of the key it was read with, and the two hashes {@code ga_hash} XORs.
     *
     * <p>{@code list[()]} and {@code list[int, str]} are one call with a tuple key, whose elements become the
     * arguments — so {@code list[()]} keeps none and prints the empty parentheses CPython prints, while
     * {@code list[(int, str)]} keeps two. A slice key is not a tuple and so stays one argument of its own.
     * CPython's hash is the origin's XOR the arguments-as-one-tuple's, a relationship a program can check
     * against itself; the runtime folds the same two numbers and nothing else.
     */
    @Test
    void anAliasKeepsTheOriginAndEveryArgumentTheKeyHeld() {
        PyObject one = Slots.getItem(PyType.LIST, PyType.INT);
        PyObject spread = Slots.getItem(PyType.LIST, PyTuple.of(PyType.INT, PyType.STR));
        PyObject none = Slots.getItem(PyType.LIST, PyTuple.EMPTY);
        PyObject slice =
                Slots.getItem(PyType.LIST, PySlice.of(PyInt.of(1), PyInt.of(2), PyNone.INSTANCE));

        assertThat(Slots.getAttr(one, "__args__")).isEqualTo(PyTuple.of(PyType.INT));
        assertThat(Slots.getAttr(spread, "__args__")).isEqualTo(PyTuple.of(PyType.INT, PyType.STR));
        assertThat(Slots.getAttr(none, "__args__")).isEqualTo(PyTuple.EMPTY);
        assertThat(Slots.getAttr(one, "__origin__")).isSameAs(PyType.LIST);
        assertThat(Slots.getAttr(one, "__parameters__")).isEqualTo(PyTuple.EMPTY);

        assertThat(one.repr()).isEqualTo("list[int]");
        assertThat(spread.repr()).isEqualTo("list[int, str]");
        assertThat(none.repr()).isEqualTo("list[()]");
        assertThat(slice.repr()).isEqualTo("list[slice(1, 2, None)]");

        // `ga_hash`: two readings of one alias are one key, and the number is the XOR of the origin and the
        // arguments seen as a tuple — the same two numbers a program would XOR.
        assertThat(one.hash()).isEqualTo(PyType.LIST.hash() ^ PyTuple.of(PyType.INT).hash());
        assertThat(none.hash()).isEqualTo(PyType.LIST.hash() ^ PyTuple.EMPTY.hash());
        assertThat(Slots.getItem(PyType.LIST, PyType.INT)).isEqualTo(one).isNotSameAs(one);
        assertThat(spread).isNotEqualTo(Slots.getItem(PyType.LIST, PyTuple.of(PyType.STR, PyType.INT)));

        // Every alias of every type is a value of one class, and it is the one the `types` module names.
        assertThat(one.getType()).isSameAs(PyType.GENERIC_ALIAS);
        assertThat(Slots.getItem(PyType.TUPLE, PyType.INT).getType()).isSameAs(PyType.GENERIC_ALIAS);
        assertThat(text(PyType.GENERIC_ALIAS, "__module__")).isEqualTo("types");

        // An alias is a subscription and not a class, and the complaint quotes its own repr.
        refuses("TypeError: list[int] is not a generic class", () -> Slots.getItem(one, PyInt.ZERO));
    }

    /**
     * A sequence concatenation refuses; a sequence comparison declines, and only the driver knows both did.
     *
     * <p>{@code tuple_concat} does not check its second operand and decline, as the number slots do — it reads
     * a tuple and says which of the two it could not join, and {@code list_concat} is written the same way. So
     * neither side ever reaches the {@code __radd__} that neither type files, and {@code (1,) + [1]} and {@code
     * [1] + (1,)} are each the left operand's own sentence. {@code tuple_tp_richcompare} is the opposite
     * shape: it declines anything that is not a tuple, which leaves {@code (1, 2) == [1, 2]} to {@link
     * Slots#compare}'s identity fallback rather than to a list-by-list walk.
     */
    @Test
    void aSequenceConcatenationRefusesAndASequenceComparisonDeclines() {
        PyObject tuple = pair();
        PyObject list = items(PyInt.of(1), PyInt.of(2));

        refuses(
                "TypeError: can only concatenate tuple (not \"list\") to tuple",
                () -> tuple.binaryOp(BinaryOp.ADD, list));
        refuses(
                "TypeError: can only concatenate list (not \"tuple\") to list",
                () -> Slots.binary(BinaryOp.ADD, list, tuple));
        assertThat(tuple.binaryOp(BinaryOp.ADD, PyTuple.of(PyInt.of(3))))
                .isEqualTo(PyTuple.of(PyInt.of(1), PyInt.of(2), PyInt.of(3)));

        // A slot that declines is the case the operator's own message is written for.
        refuses(
                "TypeError: unsupported operand type(s) for +: 'int' and 'list'",
                () -> Slots.binary(BinaryOp.ADD, PyInt.ONE, items()));

        assertThat(tuple.richCompare(CompareOp.EQ, list)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(list.richCompare(CompareOp.LT, tuple)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.compare(CompareOp.EQ, tuple, list)).isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.EQ, tuple, tuple)).isSameAs(PyBool.TRUE);
        refuses(
                "TypeError: '<' not supported between instances of 'tuple' and 'int'",
                () -> Slots.compare(CompareOp.LT, tuple, PyInt.of(1)));

        // `sq_repeat` is neither: the wrapper filed under `__mul__` reads its count as an index, where the
        // `nb_multiply` slot that the `*` operator runs says which kind it could not multiply by.
        refuses(
                "TypeError: 'list' object cannot be interpreted as an integer",
                () -> call(entry(PyType.TUPLE, "__mul__"), tuple, list));
        refuses(
                "TypeError: can't multiply sequence by non-int of type 'list'",
                () -> Slots.binary(BinaryOp.MUL, tuple, list));
    }

    /**
     * The one half of the constructor that builds nothing, and the one that hands back what it was given.
     *
     * <p>{@code tuple(t) is t} is {@code tuple_new}'s shortcut for an immutable it can reuse, and it is a
     * decision a script can only see from outside: {@code Constructors.instantiate} returns the very same
     * object, not a copy that compares equal. {@code list.__new__} is the other half — it reads the class to
     * work out how much to allocate and throws the rest of the call away, so {@code list.__new__(list, 'ab')}
     * is empty where {@code list('ab')} is two characters, because only the constructor runs {@code
     * __init__}.
     */
    @Test
    void theConstructorHalvesBuildDifferentThingsFromTheSameArguments() {
        PyObject tuple = pair();
        assertThat(Constructors.instantiate(PyType.TUPLE, new PyObject[] {tuple}, null)).isSameAs(tuple);
        assertThat(Constructors.instantiate(PyType.TUPLE, new PyObject[] {items(PyInt.ONE)}, null))
                .isEqualTo(PyTuple.of(PyInt.ONE));
        assertThat(Constructors.instantiate(PyType.LIST, new PyObject[] {PyStr.of("ab")}, null))
                .isEqualTo(items(PyStr.of("a"), PyStr.of("b")));

        PyObject fresh = call(entry(PyType.LIST, "__new__"), PyType.LIST, PyStr.of("ab"));
        assertThat(fresh).isEqualTo(PyList.empty());
        assertThat(((PyList) fresh).length()).isEqualTo(PyInt.ZERO);

        refuses(
                "TypeError: list expected at most 1 argument, got 2",
                () -> call(Slots.getAttr(PyType.LIST, "__init__"), items(), PyInt.ONE, PyInt.of(2)));
        refuses(
                "TypeError: tuple() takes no keyword arguments",
                () -> Slots.call(
                        entry(PyType.TUPLE, "__new__"),
                        new PyObject[] {PyType.TUPLE, PyInt.ONE},
                        oneKeyword()));
    }
}
