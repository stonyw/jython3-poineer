package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dictionaries {@code bytes} and {@code str} file in their own names, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 59 and 60 read both namespaces from Python, one line per name, and case 61 asks what the
 * names they file answer when called. What is asserted here is the half a script cannot point at: the two
 * tables as the complete ordered maps they are, since a script
 * can only read the names of a type this runtime fills and cannot say which of the runtime's own lists put
 * a name where it landed; the identity between a dictionary entry and the object the same type's {@link
 * Members} table hands out, which is the one place a name written in one of the two files and not the
 * other is caught before a program finds it; {@code wrapperdescr_call}'s gates now that every type is
 * checked against its own values rather than only {@code type} against classes; the call slot, which a
 * program can only ask about through {@code callable()} and which the dictionary's own entries turned out
 * never to have had; and the two ways a {@code staticmethod} comes into being, only one of which is ever
 * handed a function to keep the name of.
 */
class ValueDictionaryTest {

    /** The sixty-five names {@code bytes} files, in the order its dictionary answers them. */
    private static final List<String> BYTES_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__str__", "__lt__", "__le__", "__eq__", "__ne__",
                    "__gt__", "__ge__", "__iter__", "__buffer__", "__mod__", "__rmod__", "__len__",
                    "__getitem__", "__add__", "__mul__", "__rmul__", "__contains__", "__getnewargs__",
                    "__bytes__", "capitalize", "center", "count", "decode", "endswith", "expandtabs",
                    "find", "fromhex", "hex", "index", "isalnum", "isalpha", "isascii", "isdigit",
                    "islower", "isspace", "istitle", "isupper", "join", "ljust", "lower", "lstrip",
                    "maketrans", "partition", "replace", "removeprefix", "removesuffix", "rfind", "rindex",
                    "rjust", "rpartition", "rsplit", "rstrip", "split", "splitlines", "startswith",
                    "strip", "swapcase", "title", "translate", "upper", "zfill", "__doc__");

    /**
     * The seventy names {@code str} files: nineteen slots less one, the forty-six of its method table, the
     * four names CPython files after that table, and {@code __new__} and {@code __doc__} beside them.
     */
    private static final List<String> STR_ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__str__", "__lt__", "__le__", "__eq__", "__ne__",
                    "__gt__", "__ge__", "__iter__", "__mod__", "__rmod__", "__len__", "__getitem__",
                    "__add__", "__mul__", "__rmul__", "__contains__", "encode", "replace", "split",
                    "rsplit", "join", "capitalize", "casefold", "title", "center", "count", "expandtabs",
                    "find", "partition", "index", "ljust", "lower", "lstrip", "rfind", "rindex", "rjust",
                    "rstrip", "rpartition", "splitlines", "strip", "swapcase", "translate", "upper",
                    "startswith", "endswith", "removeprefix", "removesuffix", "isascii", "islower",
                    "isupper", "istitle", "isspace", "isdecimal", "isdigit", "isnumeric", "isalpha",
                    "isalnum", "isidentifier", "isprintable", "zfill", "format", "format_map",
                    "__format__", "maketrans", "__sizeof__", "__getnewargs__", "__doc__");

    /** The nineteen names of {@code bytes} that are a copied slot rather than a declared method. */
    private static final List<String> BYTES_SLOTS =
            List.of(
                    "__repr__", "__hash__", "__str__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__iter__", "__buffer__", "__mod__", "__rmod__", "__len__", "__getitem__",
                    "__add__", "__mul__", "__rmul__", "__contains__");

    /** {@code type(name, bases, {members})}, the three-argument creator asked to make a plain class. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < members.length; at += 2) {
            dict.put(PyStr.interned((String) members[at]), (PyObject) members[at + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(bases), dict}, null);
    }

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

    private static PyObject octets(String text) {
        return PyBytes.of(text.getBytes(StandardCharsets.UTF_8));
    }

    private static PyObject call(PyObject callable, PyObject... arguments) {
        return Slots.call(callable, arguments, null);
    }

    /** The text a value answers for a name, which is how a written {@code __qualname__} is read back. */
    private static String text(PyObject value, String name) {
        return ((PyStr) Slots.getAttr(value, name)).getValue();
    }

    /** {@code TypeError}, {@code AttributeError} and {@code ValueError} in CPython's own words. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * Each type's own dictionary is complete, in the reference interpreter's order, and holds the objects
     * its method table hands out.
     *
     * <p>The order is a fact about CPython's source rather than about this runtime's: {@code bytes} files
     * the two names pickle asks before its method table, {@code str} files its four tail names after, and
     * {@code str}'s forty-six methods come in the order {@code unicode_methods} declares them — the order a
     * person reads the docs in, {@code encode} first, and not an alphabetical one. The method names are then
     * asserted against {@link Members} because the two lists are written by hand in two files, the
     * dictionary spelling out its own order and the table its own shape, and only a name in both can be
     * read at all.
     */
    @Test
    void theTwoValueTypesFileEveryNameTheyAnswer() {
        assertThat(filed(PyType.BYTES)).isEqualTo(BYTES_ORDER);
        assertThat(filed(PyType.STR)).isEqualTo(STR_ORDER);
        assertThat(TypeDict.declared(PyType.BYTES).keys()).hasSize(65);
        assertThat(TypeDict.declared(PyType.STR).keys()).hasSize(70);

        List<String> methods =
                BYTES_ORDER.subList(BYTES_ORDER.indexOf("__getnewargs__"), BYTES_ORDER.size() - 1);
        assertThat(Arrays.asList(Members.tableOf(PyType.BYTES).names()))
                .containsExactlyInAnyOrderElementsOf(methods);
        List<String> textMethods = STR_ORDER.subList(STR_ORDER.indexOf("encode"), STR_ORDER.size() - 1);
        assertThat(Arrays.asList(Members.tableOf(PyType.STR).names()))
                .containsExactlyInAnyOrderElementsOf(textMethods);

        // The entry and the read are one object, so `bytes.upper is bytes.__dict__["upper"]` stays true
        // however many times either side is asked.
        assertThat(entry(PyType.BYTES, "upper"))
                .isSameAs(Slots.getAttr(PyType.BYTES, "upper"))
                .isSameAs(Members.tableOf(PyType.BYTES).describe("upper"));
        assertThat(entry(PyType.BYTES, "__getnewargs__")).isSameAs(Slots.getAttr(PyType.BYTES, "__getnewargs__"));
    }

    /**
     * Which of the kinds a name was filed from, including the two a value type files exactly once.
     *
     * <p>The nineteen wrappers of {@code bytes} are its {@code tp_richcompare}, {@code tp_as_number} and
     * {@code tp_as_sequence} copies, and {@code str} files eighteen of the same nineteen: {@code __buffer__}
     * is the name a text has not, because a string is its own characters and offers no memory to expose.
     * Everything else on either side is a {@code method_descriptor} of a value except the three names that
     * take no value — {@code __new__}, filed as a plain builtin, {@code fromhex}, filed as a {@code
     * classmethod_descriptor}, and {@code maketrans}, filed as the wrapper rather than the function.
     */
    @Test
    void theSlotsAreFiledAsWrappersAndTheMethodsAsDescriptors() {
        for (String name : BYTES_SLOTS) {
            assertThat(kind(entry(PyType.BYTES, name)))
                    .as("bytes.__dict__['%s']", name)
                    .isEqualTo("wrapper_descriptor");
        }
        assertThat(filed(PyType.BYTES)).contains("__buffer__");
        assertThat(filed(PyType.STR)).doesNotContain("__buffer__");

        assertThat(kind(entry(PyType.BYTES, "upper"))).isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.BYTES, "__bytes__"))).isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.BYTES, "fromhex"))).isEqualTo("classmethod_descriptor");
        assertThat(kind(entry(PyType.BYTES, "maketrans"))).isEqualTo("staticmethod");
        assertThat(kind(entry(PyType.BYTES, "__new__"))).isEqualTo("builtin_function_or_method");
        assertThat(kind(entry(PyType.STR, "casefold"))).isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.STR, "__format__"))).isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.STR, "__sizeof__"))).isEqualTo("method_descriptor");

        // The sentences CPython's `bytes_doc` and `unicode_doc` write, filed as values rather than as
        // descriptors, which is what puts them in front of the `__doc__` inherited from `object`.
        assertThat(kind(entry(PyType.BYTES, "__doc__"))).isEqualTo("str");
        assertThat(text(PyType.BYTES, "__doc__")).startsWith("bytes(iterable_of_ints) -> bytes\n");
        assertThat(text(PyType.STR, "__doc__")).startsWith("str(object='') -> str\n");

        // The read is the dictionary's text in a fresh string, which is what CPython's
        // `type_getdoc_helper` answers for a static type — `bytes.__doc__ is bytes.__dict__["__doc__"]` is
        // false there, and false here, unlike the read of a docstring a class body wrote.
        assertThat(Slots.getAttr(PyType.BYTES, "__doc__")).isEqualTo(entry(PyType.BYTES, "__doc__"));
        assertThat(Slots.getAttr(PyType.BYTES, "__doc__")).isNotSameAs(entry(PyType.BYTES, "__doc__"));
    }

    /**
     * {@code wrapperdescr_call}'s four gates, and the receiver half of them now that every type checks its
     * own values.
     *
     * <p>Before this slice only {@code type}'s metaclass slots checked a receiver at all, so a wrapper filed
     * by a value type was handed anything and left to complain about the slot it wrapped. Now the sentence is
     * the wrapper's own and names the declaring type, and the refusals run in CPython's order — receiver,
     * then keyword, then count — so a call wrong in three ways names only the first thing it found.
     */
    @Test
    void anUnboundWrapperRunsItsGatesInOrder() {
        PyObject length = entry(PyType.BYTES, "__len__");
        refuses(
                "TypeError: descriptor '__len__' of 'bytes' object needs an argument",
                () -> call(length));
        refuses(
                "TypeError: descriptor '__len__' requires a 'bytes' object but received a 'int'",
                () -> call(length, PyInt.of(5)));
        refuses(
                "TypeError: wrapper __len__() takes no keyword arguments",
                () -> Slots.call(length, new PyObject[] {octets("ab"), PyInt.of(5)}, oneKeyword()));
        refuses(
                "TypeError: expected 0 arguments, got 1",
                () -> call(length, octets("ab"), PyInt.of(1)));

        // Receiver first, so the three-way wrong call says only which object arrived.
        refuses(
                "TypeError: descriptor '__len__' requires a 'bytes' object but received a 'int'",
                () -> Slots.call(length, new PyObject[] {PyInt.of(5), PyInt.of(6)}, oneKeyword()));
        // and a call with nothing but a keyword has no receiver to complain about yet.
        refuses(
                "TypeError: descriptor '__len__' of 'bytes' object needs an argument",
                () -> Slots.call(length, new PyObject[] {PyInt.of(5)}, oneKeyword()));

        assertThat(call(length, octets("abcd"))).isEqualTo(PyInt.of(4));

        // Two wrappers name themselves in the count refusal, because the C function behind them hands
        // `PyArg_UnpackTuple` a name to say: `wrap_buffer` here, `wrap_setattr` on the metatype side.
        refuses(
                "TypeError: __buffer__ expected 1 argument, got 2",
                () -> call(entry(PyType.BYTES, "__buffer__"), octets("ab"), PyInt.ZERO, PyInt.ZERO));
        refuses(
                "TypeError: descriptor '__setattr__' of 'type' object needs an argument",
                () -> call(entry(PyType.TYPE, "__setattr__")));
        refuses(
                "TypeError: descriptor '__setattr__' requires a 'type' object but received a 'int'",
                () -> call(entry(PyType.TYPE, "__setattr__"), PyInt.of(3)));
    }

    /**
     * A wrapper reports what its slot answered, which is not always what the operator answers.
     *
     * <p>{@code tp_richcompare} declines, and a wrapper hands the decline over without judging it:
     * {@code bytes.__dict__["__eq__"](b"ab", 1)} is {@code NotImplemented} while {@code b"ab" == 1} is
     * false, the second answer belonging to the driver that tries the reflected slot next.
     * {@code sq_concat} is the opposite case — {@code bytes_concat} does not decline, it reads a buffer and
     * says which of the two it could not join — and {@code sq_repeat} takes its count as an index, so the
     * slot's complaint is not the operator's either.
     */
    @Test
    void aWrapperReportsTheSlotRatherThanAnOperator() {
        assertThat(call(entry(PyType.BYTES, "__eq__"), octets("ab"), PyInt.of(1)))
                .isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.compare(CompareOp.EQ, octets("ab"), PyInt.of(1))).isSameAs(PyBool.FALSE);
        assertThat(call(entry(PyType.BYTES, "__lt__"), octets("ab"), PyStr.of("b")))
                .isSameAs(PyNotImplemented.INSTANCE);

        refuses(
                "TypeError: can't concat list to bytes",
                () -> call(entry(PyType.BYTES, "__add__"), octets("ab"), PyList.empty()));
        assertThat(call(entry(PyType.BYTES, "__add__"), octets("a"), octets("b"))).isEqualTo(octets("ab"));
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> call(entry(PyType.BYTES, "__mul__"), octets("ab"), PyFloat.of(1.5)));
        assertThat(call(entry(PyType.BYTES, "__mul__"), octets("ab"), PyInt.of(2))).isEqualTo(octets("abab"));

        // `%` is the slot whose result a caller came for, and `__rmod__` is RBINSLOT's swap: the second
        // operand does the formatting, and a second operand that cannot be asked leaves the decline.
        assertThat(call(entry(PyType.STR, "__mod__"), PyStr.of("%s!"), PyStr.of("ab")))
                .isEqualTo(PyStr.of("ab!"));
        assertThat(call(entry(PyType.STR, "__rmod__"), PyStr.of("ab"), PyStr.of("%s!")))
                .isEqualTo(PyStr.of("ab!"));
        assertThat(call(entry(PyType.STR, "__rmod__"), PyStr.of("ab"), PyInt.of(1)))
                .isSameAs(PyNotImplemented.INSTANCE);
    }

    /**
     * A declined comparison is an answer, and {@code callable()} is a different question.
     *
     * <p>{@link PyObject#call} used to hand back {@link PyNotImplemented} to mean "I cannot be called",
     * which made the two meanings collide: the method-wrapper of a slot that had just declined was reported
     * to its caller as not callable. The refusal is now thrown by the value that has no call slot, so a
     * decline stays a value, {@code callable()} reads the slot rather than a list of classes, and the
     * dictionary's own entries — which a script can call and always could — are callable too. A
     * {@code property} and a {@code classmethod} object are the two kinds that answer {@code false} while
     * still holding a function, which is CPython's rule and not this runtime's invention.
     */
    @Test
    void aCallSlotIsTheWholeOfTheCallableQuestion() {
        PyObject wrapper = Slots.getAttr(octets("ab"), "__eq__");
        assertThat(wrapper.getType()).isSameAs(PyType.METHOD_WRAPPER);
        assertThat(Slots.isCallable(wrapper)).isTrue();
        assertThat(call(wrapper, octets("ab"))).isSameAs(PyBool.TRUE);

        for (String name : new String[] {"__len__", "upper", "fromhex", "maketrans", "__new__"}) {
            assertThat(Slots.isCallable(entry(PyType.BYTES, name)))
                    .as("callable(bytes.__dict__['%s'])", name)
                    .isTrue();
        }
        assertThat(Slots.isCallable(octets("ab"))).isFalse();
        refuses(
                "TypeError: 'bytes' object is not callable",
                () -> Slots.call(octets("ab"), new PyObject[0], null));

        // The wrapper is callable, so its complaint about an empty call is about the arguments.
        refuses(
                "TypeError: expected 1 argument, got 0",
                () -> Slots.call(wrapper, new PyObject[0], null));

        PyType plain = type("Plain", new PyType[0]);
        PyObject marker = Slots.call(plain, new PyObject[0], null);
        assertThat(Slots.isCallable(marker)).isFalse();
        refuses(
                "TypeError: 'Plain' object is not callable",
                () -> Slots.call(marker, new PyObject[0], null));

        PyType called =
                type(
                        "Called",
                        new PyType[0],
                        "__call__",
                        new PyNativeFunction("__call__", (args, kwNames) -> PyStr.of("called")));
        assertThat(Slots.isCallable(Slots.call(called, new PyObject[0], null))).isTrue();
        assertThat(call(Slots.call(called, new PyObject[0], null))).isEqualTo(PyStr.of("called"));

        // A classmethod object holds a function and is not callable; a staticmethod object is, since 3.11.
        assertThat(Slots.isCallable(new PyClassMethod(makeTrans()))).isFalse();
        assertThat(Slots.isCallable(new PyStaticMethod(makeTrans()))).isTrue();
        assertThat(entry(PyType.OBJECT, "__init_subclass__").getType().name())
                .isEqualTo("classmethod_descriptor");
    }

    /**
     * The name is what makes a class callable; what stands under it decides what the call says.
     *
     * <p>{@code slot_tp_call} is {@code call_method(self, "__call__", args, kwds)} and the three branches of
     * {@code lookup_method_ex} are the whole rule: a value whose class files an unbound built-in method takes
     * the value as its own first argument, a descriptor has {@code __get__} run and the answer is called as it
     * stands, and a value that is neither is called as it stands too — which is why an {@code int} filed under
     * the name refuses as {@code 'int' object is not callable} and not as the class that filed it. None of
     * that reaches {@code callable()}, which is answered by the filing alone: {@code __call__ = None} is a
     * callable class whose calls fail, and the corpus case measures the same split from Python.
     */
    @Test
    void theFiledCallDecidesTheRefusalAndTheNameDecidesCallability() {
        PyType number = type("Numbered", new PyType[0], "__call__", PyInt.of(5));
        PyObject numbered = Slots.call(number, new PyObject[0], null);
        assertThat(Slots.isCallable(numbered)).isTrue();
        refuses("TypeError: 'int' object is not callable", () -> call(numbered));

        PyType nothing = type("Nilled", new PyType[0], "__call__", PyNone.INSTANCE);
        PyObject nilled = Slots.call(nothing, new PyObject[0], null);
        assertThat(Slots.isCallable(nilled)).isTrue();
        refuses("TypeError: 'NoneType' object is not callable", () -> call(nilled));

        // The method descriptor is not bound first: the value arrives as the argument it must accept.
        PyType uppered = type("Uppered", new PyType[0], "__call__", entry(PyType.STR, "upper"));
        PyObject receiver = Slots.call(uppered, new PyObject[0], null);
        assertThat(Slots.isCallable(receiver)).isTrue();
        refuses(
                "TypeError: descriptor 'upper' for 'str' objects doesn't apply to a 'Uppered' object",
                () -> call(receiver));

        // A class method binds to the type, so the value never reaches the function it wraps.
        PyObject[] seen = new PyObject[1];
        PyClassMethod asCall =
                new PyClassMethod(
                        new PyNativeFunction(
                                "__call__",
                                (args, kwNames) -> {
                                    seen[0] = args[0];
                                    return PyInt.of(args.length);
                                }));
        PyType classed = type("Classed", new PyType[0], "__call__", asCall);
        PyObject value = Slots.call(classed, new PyObject[0], null);
        assertThat(call(value)).isEqualTo(PyInt.of(1));
        assertThat(seen[0]).isSameAs(classed);

        // A name no class filed is refused by the value's own type, and that refusal has no gate to run.
        PyType plain = type("Plain", new PyType[0]);
        PyObject declined = Slots.call(plain, new PyObject[0], null);
        assertThat(Specials.member(declined.getType(), "__call__")).isNull();
        assertThat(Slots.isCallable(declined)).isFalse();
        refuses("TypeError: 'Plain' object is not callable", () -> call(declined));
    }

    /**
     * The class method in the dictionary is a descriptor; the read of the same name is already a method.
     *
     * <p>{@code bytes.__dict__["fromhex"]} is a {@code classmethod_descriptor} that names its type in the
     * repr, in {@code __objclass__} and as the qualifier of {@code __qualname__}, and calling it takes the
     * class as its first argument. Once a class is attached it is a plain {@code
     * builtin_function_or_method} again and says none of the three. Both halves check before they bind, and
     * the two refusals name the type that declares the method rather than the one handed over.
     */
    @Test
    void theClassMethodEntryBindsAndTheReadIsAlreadyBound() {
        PyObject fromHex = entry(PyType.BYTES, "fromhex");
        assertThat(fromHex.getType().name()).isEqualTo("classmethod_descriptor");
        assertThat(Slots.getAttr(fromHex, "__objclass__")).isSameAs(PyType.BYTES);
        assertThat(fromHex.repr()).isEqualTo("<method 'fromhex' of 'bytes' objects>");
        assertThat(text(fromHex, "__qualname__")).isEqualTo("bytes.fromhex");

        assertThat(call(fromHex, PyType.BYTES, PyStr.of("61 62"))).isEqualTo(octets("ab"));
        refuses(
                "TypeError: descriptor 'fromhex' of 'bytes' object needs an argument",
                () -> call(fromHex));
        refuses(
                "TypeError: descriptor 'fromhex' for type 'bytes' needs a type, not a 'bytes' as arg 2",
                () -> call(fromHex, octets("ab"), PyStr.of("61")));
        refuses(
                "TypeError: descriptor 'fromhex' requires a subtype of 'bytes' but received 'str'",
                () -> call(fromHex, PyType.STR, PyStr.of("61")));

        PyObject bound = Slots.getAttr(PyType.BYTES, "fromhex");
        assertThat(bound.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(bound.getAttr("__objclass__")).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(PyType.BYTES);
        assertThat(call(bound, PyStr.of("61"))).isEqualTo(octets("a"));

        // `__get__` runs the same two checks as the call, and a fresh method each time it answers: a
        // class method is made by the read, which is why `bytes.fromhex is bytes.fromhex` is false on both
        // sides of the same dictionary entry.
        assertThat(Descriptors.get(fromHex, PyNone.INSTANCE, PyType.BYTES).getType())
                .isSameAs(bound.getType());
        refuses(
                "TypeError: descriptor 'fromhex' requires a subtype of 'bytes' but received 'str'",
                () -> Descriptors.get(fromHex, PyNone.INSTANCE, PyType.STR));
        assertThat(Descriptors.get(fromHex, octets("ab"), PyType.BYTES)).isNotSameAs(bound);
    }

    /**
     * The two ways a {@code staticmethod} is made, and what each keeps of the function under it.
     *
     * <p>Python's {@code staticmethod(f)} runs {@code functools_wraps}, so the wrapper answers
     * {@code f}'s {@code __name__}, {@code __qualname__} and {@code __doc__} as its own. A {@code
     * METH_STATIC} entry of a built-in method table is made before any of that, and so {@code
     * bytes.__dict__["maketrans"]} has no name to answer with at all — the one entry in either table that
     * is silent about itself, whose {@code __doc__} is read up the MRO off the {@code staticmethod} type.
     * Both wrappers unwrap to the same function, which is what keeps {@code bytes.maketrans is
     * b''.maketrans} true.
     */
    @Test
    void aFiledStaticmethodKeepsNothingOfTheFunctionItWraps() {
        PyObject filed = entry(PyType.BYTES, "maketrans");
        PyNativeFunction function = (PyNativeFunction) Slots.getAttr(PyType.BYTES, "maketrans");

        assertThat(kind(filed)).isEqualTo("staticmethod");
        assertThat(Slots.getAttr(filed, "__func__")).isSameAs(function);
        assertThat(Slots.getAttr(filed, "__wrapped__")).isSameAs(function);
        assertThat(Slots.getAttr(filed, "__isabstractmethod__")).isSameAs(PyBool.FALSE);
        assertThat(filed.getAttr("__name__")).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(filed.getAttr("__qualname__")).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(filed.getAttr("__module__")).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.getAttr(filed, "__doc__"))
                .isEqualTo(Slots.getAttr(PyType.STATIC_METHOD, "__doc__"));

        // The way Python makes one: the wrapper takes the function's identity as its own.
        PyObject weight = new PyNativeFunction("weight", (args, kwNames) -> PyInt.of(1));
        PyObject wrapped = new PyStaticMethod(weight);
        assertThat(text(wrapped, "__name__")).isEqualTo("weight");
        assertThat(text(wrapped, "__qualname__")).isEqualTo("weight");
        assertThat(Slots.getAttr(wrapped, "__doc__")).isSameAs(Slots.getAttr(weight, "__doc__"));

        // The read and the entry are two objects because there are two answers; the function underneath
        // is one, for the type and for every value of it.
        assertThat(Descriptors.get(filed, octets("ab"), PyType.BYTES)).isSameAs(function);
        assertThat(Slots.getAttr(octets("ab"), "maketrans")).isSameAs(function);
        assertThat(call(filed, octets("ab"), octets("cd")))
                .isEqualTo(call(function, octets("ab"), octets("cd")));
    }

    /**
     * {@code maketrans} builds the table {@code translate} is fed, in CPython's order of refusals.
     *
     * <p>The argument table converts the second and third arguments before the body looks at the first, so
     * {@code maketrans(3, 4)} complains about argument 2 while {@code maketrans(3)} complains about a dict
     * that never arrived. A dictionary is re-keyed and never re-valued: a one-character key becomes its
     * ordinal, an integer key is stored as it stands, and a value is kept whatever it holds, which is why
     * {@code maketrans({"a": 3})} is accepted although {@code translate} will not be. CPython's two
     * messages about keys are mis-spelled in its own source — {@code translatetable} and {@code mustbe} —
     * and are transcribed rather than tidied.
     */
    @Test
    void theTranslationTableIsKeyedByOrdinals() {
        PyObject pairs = call(makeTrans(), PyStr.of("ab"), PyStr.of("cd"));
        assertThat(pairs.repr()).isEqualTo("{97: 99, 98: 100}");
        assertThat(
                        call(makeTrans(), PyStr.of("ab"), PyStr.of("cd"), PyStr.of("e"))
                                .repr())
                .isEqualTo("{97: 99, 98: 100, 101: None}");

        PyDict characterKey = PyDict.empty();
        characterKey.put(PyStr.interned("a"), PyInt.of(3));
        assertThat(call(makeTrans(), characterKey).repr()).isEqualTo("{97: 3}");
        PyDict ordinalKey = PyDict.empty();
        ordinalKey.put(PyInt.of(65), PyStr.of("x"));
        assertThat(call(makeTrans(), ordinalKey).repr()).isEqualTo("{65: 'x'}");

        refuses(
                "TypeError: maketrans() argument 2 must be str, not int",
                () -> call(makeTrans(), PyInt.of(3), PyInt.of(4)));
        refuses(
                "TypeError: if you give only one argument to maketrans it must be a dict",
                () -> call(makeTrans(), PyInt.of(3)));
        refuses(
                "TypeError: first maketrans argument must be a string if there is a second argument",
                () -> call(makeTrans(), PyInt.of(3), PyStr.of("ab")));
        refuses(
                "ValueError: the first two maketrans arguments must have equal length",
                () -> call(makeTrans(), PyStr.of("ab"), PyStr.of("cde")));
        PyDict tooLong = PyDict.empty();
        tooLong.put(PyStr.interned("ab"), PyInt.of(1));
        refuses(
                "ValueError: string keys in translatetable must be of length 1",
                () -> call(makeTrans(), tooLong));
        PyDict wrongKind = PyDict.empty();
        wrongKind.put(PyFloat.of(1.5), PyInt.of(1));
        refuses(
                "TypeError: keys in translate table mustbe strings or integers",
                () -> call(makeTrans(), wrongKind));
    }

    /** {@code str.maketrans}, reached the way a program reaches it: through the type. */
    private static PyObject makeTrans() {
        return Slots.getAttr(PyType.STR, "maketrans");
    }
}
