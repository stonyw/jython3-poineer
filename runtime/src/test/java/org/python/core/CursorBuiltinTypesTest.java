package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The four books of the cursors whose builtin is the type the call makes, measured against CPython
 * 3.14.10.
 *
 * <p>Corpus case 117 reads each book and each walk from Python and prints the kind of every object a
 * pickle hands back. What is pinned here is the half a script has to guess at. The first of it is that the
 * builtin <em>is</em> the type: {@code builtins['zip']} <em>is</em> {@code PyType.ZIP}, so {@code
 * zip(sources)} is {@code tp_vectorcall} and {@code zip.__new__(zip, sources)} is {@code tp_new} — one
 * constructor reached two ways, and the disagreement between the two that only {@code enumerate} has,
 * because {@code enumerate_vectorcall} counts its two arguments by hand while its {@code __new__} runs the
 * clinic table over the same two names.
 *
 * <p>Then the four books, which are the same five rows and one more name apiece. Six entries for {@code
 * enumerate}, which is the one generic of the family; six for {@code zip} and {@code map}, which hold a flag
 * no argument list can carry and so file a restart row; five for {@code filter}, which holds nothing a pickle
 * cannot say. The two slot copies, the pickle, the constructor and its own sentence are in all four books,
 * down to the text each one answers {@code __doc__} with, and {@code object} files three of those five.
 *
 * <p>Then what each constructor asks and in which order — the count and the keywords first for all four,
 * then the {@code __index__} of the start before the iterability of the source for {@code enumerate}, then
 * the <em>truth</em> of {@code strict} for the other two. Then the walks, which end at the first source
 * that has nothing and leave the sources after it alone unless {@code strict} is on, and which hand a
 * refusal that is not an end straight out of the loop that was walking. Then the payloads, which name their
 * own type and hold the iterators entire — and, unlike {@code reversed}, never let go of them.
 */
class CursorBuiltinTypesTest {

    /** The four types {@code _PyBuiltin_Init} and {@code enum_new} leave in {@code builtins}. */
    private static final List<PyType> FAMILY =
            List.of(PyType.ENUMERATE, PyType.ZIP, PyType.MAP, PyType.FILTER);

    /** {@code enum_methods} ({@code Objects/enumobject.c:307}) beside the two slots and the sentence. */
    private static final List<String> ENUMERATE_BOOK =
            List.of(
                    "__new__",
                    "__iter__",
                    "__next__",
                    "__reduce__",
                    "__class_getitem__",
                    "__doc__");

    /** {@code zip_methods} ({@code Python/bltinmodule.c:3293}) and {@code map_methods} ({@code :1612}). */
    private static final List<String> FLAGGED_BOOK =
            List.of(
                    "__new__",
                    "__iter__",
                    "__next__",
                    "__reduce__",
                    "__setstate__",
                    "__doc__");

    /** {@code filter_methods} ({@code bltinmodule.c:629}): the shortest book of the four. */
    private static final List<String> FILTER_BOOK =
            List.of("__new__", "__iter__", "__next__", "__reduce__", "__doc__");

    /** The names {@code dir()} gains over {@code object}'s, sorted, one book apiece. */
    private static final List<String> GENERIC_GAINED =
            List.of("__class_getitem__", "__iter__", "__next__");

    private static final List<String> FLAG_GAINED = List.of("__iter__", "__next__", "__setstate__");

    private static final List<String> BARE_GAINED = List.of("__iter__", "__next__");

    /** The rows every book files, whatever else it carries. */
    private static final String CREATOR = "__new__";

    private static final String REDUCER = "__reduce__";

    private static final String RESTART = "__setstate__";

    private static final String GENERIC = "__class_getitem__";

    /** The two rows {@code tp_iter} and {@code tp_iternext} are copied out as, in all four books. */
    private static final List<String> SLOTS = List.of("__iter__", "__next__");

    private static final String NEW_DOC =
            "Create and return a new object.  See help(type) for accurate signature.";
    private static final String ITER_DOC = "Implement iter(self).";
    private static final String NEXT_DOC = "Implement next(self).";
    private static final String REDUCE_DOC = "Return state information for pickling.";
    private static final String SETSTATE_DOC = "Set state information for unpickling.";
    private static final String GENERIC_DOC =
            "'enumerate' objects are generic over the type of their values";

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final PyObject ZERO = PyInt.ZERO;
    private static final PyObject ONE = PyInt.of(1);
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject SIX = PyInt.of(6);
    private static final PyObject SEVEN = PyInt.of(7);
    private static final PyObject TRUE = PyBool.TRUE;
    private static final PyObject FALSEY = PyTuple.EMPTY;
    private static final PyObject TEXT = PyStr.of("x");
    private static final PyObject LETTERS = PyStr.of("ab");
    private static final PyObject REAL = PyFloat.of(3.0);

    /** {@code [1, 2]} and {@code [3, 4]}: the two sources every walk here is counted in. */
    private static final PyObject PAIRS = PyList.of(ONE, TWO);

    private static final PyObject TRIPLE = PyList.of(THREE, FOUR);

    private static final PyObject SINGLE = PyList.of(THREE);

    /** {@code 2 ** 63}, the edge the count of {@code enumerate} walks over rather than wrapping at. */
    private static final BigInteger WORD = BigInteger.valueOf(2).pow(63);

    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    private static final PyTuple START = PyTuple.of(PyStr.interned("start"));

    private static final PyTuple ITERABLE = PyTuple.of(PyStr.interned("iterable"));

    private static final PyTuple STRICT = PyTuple.of(PyStr.interned("strict"));

    /** {@code def twice(n): return n * 2}, and the refusal a second argument brings. */
    private static final PyObject TWICE =
            new PyNativeFunction(
                    "twice",
                    (args, kwNames) -> {
                        if (args.length != 1) {
                            throw arity("twice", 1, args.length);
                        }
                        return PyInt.of(Numbers.asInteger(args[0]).multiply(BigInteger.TWO));
                    });

    /** {@code def join(a, b): return a, b}, which is what a two-source {@code map} writes. */
    private static final PyObject JOIN =
            new PyNativeFunction(
                    "join",
                    (args, kwNames) -> {
                        if (args.length != 2) {
                            throw arity("join", 2, args.length);
                        }
                        return PyTuple.of(args);
                    });

    /**
     * {@code def spread(*a): return a}, which is what a {@code map} of three sources writes. The two
     * argumentless functions above refuse a third, and that refusal is a row of its own.
     */
    private static final PyObject SPREAD =
            new PyNativeFunction("spread", (args, kwNames) -> PyTuple.of(args));

    /** CPython's {@code too_many_positional}, with the plural the two counts decide. */
    private static PyRaise arity(String name, int wanted, int given) {
        throw Slots.typeError(
                "%s() takes %d positional argument%s but %d %s given",
                name, wanted, wanted == 1 ? "" : "s", given, given == 1 ? "was" : "were");
    }

    /** {@code kind.__dict__[name]}: the row the book keeps, or null where it keeps no such name. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** The names the book keeps, in the order it kept them. */
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

    /** {@code sorted(set(dir(kind) - set(dir(object)))}. */
    private static List<String> gained(PyType kind) {
        List<String> names = new ArrayList<>(Dirs.classNames(kind));
        names.removeAll(Dirs.classNames(PyType.OBJECT));
        Collections.sort(names);
        return names;
    }

    private static String rowKind(PyType kind, String name) {
        return entry(kind, name).getType().name();
    }

    private static String signature(PyType kind, String name) {
        return ((PyStr) Slots.getAttr(entry(kind, name), "__text_signature__")).getValue();
    }

    /** The sentence one filed row answers {@code __doc__} with. */
    private static String carried(PyType kind, String name) {
        return ((PyStr) Slots.getAttr(entry(kind, name), "__doc__")).getValue();
    }

    /** One filed row's own {@code __qualname__}, which is the row's name and not the value's. */
    private static String named(PyType kind, String name) {
        return ((PyStr) Slots.getAttr(entry(kind, name), "__qualname__")).getValue();
    }

    /** The first line of the type's own sentence, which is the line the corpus case reads. */
    private static String firstLine(PyType kind) {
        String doc = ((PyStr) Slots.getAttr(kind, "__doc__")).getValue();
        return doc.lines().findFirst().orElse("");
    }

    /** {@code name(*args)}: the builtin, read out of {@code builtins} the way a call does. */
    private static PyObject builtin(String name, PyObject... args) {
        return Slots.call(PyRuntime.builtins().get(PyStr.interned(name)), args, null);
    }

    /** {@code name(*args, **kwargs)}, where the keyword values are the trailing entries of {@code args}. */
    private static PyObject keyword(String name, PyTuple kwNames, PyObject... args) {
        return Slots.call(PyRuntime.builtins().get(PyStr.interned(name)), args, kwNames);
    }

    private static PyObject enumerated(PyObject... args) {
        return builtin("enumerate", args);
    }

    private static PyObject enumerated(PyTuple kwNames, PyObject... args) {
        return keyword("enumerate", kwNames, args);
    }

    private static PyObject zipped(PyObject... args) {
        return builtin("zip", args);
    }

    private static PyObject zipped(PyTuple kwNames, PyObject... args) {
        return keyword("zip", kwNames, args);
    }

    private static PyObject mapped(PyObject... args) {
        return builtin("map", args);
    }

    private static PyObject mapped(PyTuple kwNames, PyObject... args) {
        return keyword("map", kwNames, args);
    }

    private static PyObject filtered(PyObject... args) {
        return builtin("filter", args);
    }

    private static PyObject filtered(PyTuple kwNames, PyObject... args) {
        return keyword("filter", kwNames, args);
    }

    /** {@code kind.__new__(kind, *args, **kwargs)}: the same constructor, reached the long way. */
    private static PyObject viaNew(PyType kind, PyObject[] args, PyTuple kwNames) {
        PyObject[] withClass = new PyObject[args.length + 1];
        withClass[0] = kind;
        System.arraycopy(args, 0, withClass, 1, args.length);
        return Slots.call(entry(kind, CREATOR), withClass, kwNames);
    }

    /**
     * The same row with no class in front of it: {@code zip.__setstate__} rather than {@code
     * it.__setstate__}.
     */
    private static PyObject unbound(PyType kind, String name, PyObject[] args, PyTuple kwNames) {
        return Slots.call(entry(kind, name), args, kwNames);
    }

    /** {@code it.__next__()}: one step, read as an attribute and then called. */
    private static PyObject nextOf(PyObject iterator) {
        return Slots.getAttr(iterator, "__next__").call(new PyObject[0], null);
    }

    /** {@code it.__reduce__()}: the pickle payload, still holding what the cursor holds. */
    private static PyTuple reduce(PyObject iterator) {
        return (PyTuple) Slots.getAttr(iterator, REDUCER).call(new PyObject[0], null);
    }

    /** {@code it.__setstate__(state)}: the only way to reach the flag of a {@code zip} or {@code map}. */
    private static PyObject setState(PyObject iterator, PyObject state) {
        return Slots.getAttr(iterator, RESTART).call(new PyObject[] {state}, null);
    }

    /** {@code list(it)}: the whole walk, read to the end. */
    private static List<PyObject> walked(PyObject iterator) {
        return Constructors.materialise(iterator);
    }

    private static List<String> reprs(List<PyObject> values) {
        List<String> read = new ArrayList<>();
        for (PyObject value : values) {
            read.add(value.repr());
        }
        return read;
    }

    /**
     * {@code class Refusing: def __bool__(self): raise ValueError("refusing")} — the value the corpus
     * writes the same way.
     *
     * <p>The {@code p} format of {@code strict} asks the truth rather than the type, so this is what a
     * constructor hears before it asks anything of an iterable, and {@code filter(None, …)} asks the same
     * thing of each value it walks. Only the complaint stands in Java, as an override of the slot a
     * {@code __bool__} reaches; the class it is a value of is made by {@code type}, because two of the
     * sentences below name the class rather than {@code object}.
     */
    private static PyObject refusesTruth() {
        return new Refusing();
    }

    private static final PyType REFUSING =
            (PyType)
                    Classes.newType(
                            PyType.TYPE,
                            new PyObject[] {PyStr.of("Refusing"), PyTuple.of(PyType.OBJECT), PyDict.empty()},
                            null);

    private static final class Refusing extends PyObject {

        Refusing() {
            super(REFUSING);
        }

        @Override
        public PyObject truth() {
            throw Slots.valueError("refusing");
        }
    }

    /**
     * A source of the two values given and then a {@code KeyError} rather than an end, which is the one
     * thing none of the four walks is allowed to read as the walk being over.
     */
    private static final class Complaining extends PyIterator {

        private final PyObject[] items;
        private int at;

        Complaining(PyObject first, PyObject second) {
            items = new PyObject[] {first, second};
        }

        @Override
        public PyObject nextOrNull() {
            if (at >= items.length) {
                throw Slots.keyError(PyStr.of("not the end"));
            }
            return items[at++];
        }
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
     * The name in {@code builtins} is a type, and that type is the cursor the call makes.
     *
     * <p>All four are {@code PyTypeObject}s, and {@code _PyBuiltin_Init} ({@code bltinmodule.c:3434}) files
     * each of them with {@code SETBUILTIN} and the type, so {@code type(enumerate("ab")) is enumerate} holds
     * and the book behind the value is a type's book rather than the sixteen names of a {@code
     * builtin_function_or_method}.
     * The fifth cursor-handing builtin is the odd one out: {@code iter} stays a {@code
     * builtin_function_or_method} because which cursor it makes is a question about the value it was handed,
     * so there is no one type it could be.
     */
    @Test
    void theNameInBuiltinsIsTheTypeTheCallMakes() {
        assertThat(PyRuntime.builtins().get(PyStr.interned("enumerate")))
                .isSameAs(PyType.ENUMERATE);
        assertThat(PyRuntime.builtins().get(PyStr.interned("zip"))).isSameAs(PyType.ZIP);
        assertThat(PyRuntime.builtins().get(PyStr.interned("map"))).isSameAs(PyType.MAP);
        assertThat(PyRuntime.builtins().get(PyStr.interned("filter"))).isSameAs(PyType.FILTER);

        for (PyType kind : FAMILY) {
            assertThat(kind.getType()).as(kind.name()).isSameAs(PyType.TYPE);
            assertThat(kind.filedName()).isEqualTo(kind.name());
            assertThat(Slots.getAttr(kind, "__module__")).isEqualTo(PyStr.of("builtins"));
            assertThat(Slots.getAttr(kind, "__base__")).isSameAs(PyType.OBJECT);
            assertThat(kind.mro()).containsExactly(kind, PyType.OBJECT);
            assertThat(kind.isCallable()).as(kind.name()).isTrue();
        }

        // The value of each call is of the name it was called through, and is its own iterator.
        PyObject[] made = {
            enumerated(PAIRS, SEVEN),
            zipped(PAIRS, TRIPLE),
            mapped(TWICE, PAIRS),
            filtered(TWICE, PAIRS),
        };
        for (int i = 0; i < FAMILY.size(); i++) {
            PyType kind = FAMILY.get(i);
            assertThat(made[i].getType()).as(kind.name()).isSameAs(kind);
            assertThat(kind.instanceOf(made[i])).isTrue();
            assertThat(PyType.OBJECT.instanceOf(made[i])).isTrue();
            assertThat(made[i].iterator()).isSameAs(made[i]);
            assertThat(made[i].repr()).startsWith("<" + kind.name() + " object at 0x");
            assertThat(Slots.getAttr(made[i], "__doc__"))
                    .isEqualTo(Slots.getAttr(kind, "__doc__"));
        }

        // The one cursor-handing builtin that is not a type, and the emptiest cursor of the four.
        assertThat(PyRuntime.builtins().get(PyStr.interned("iter")).getType())
                .isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(zipped().getType()).isSameAs(PyType.ZIP);
        assertThat(Enumerate.over(PyIterator.of(new PyObject[0]), BigInteger.ZERO).getType())
                .isSameAs(PyType.ENUMERATE);
    }

    /**
     * Six, six, six and five names, and the one row each book has beside the four it shares.
     *
     * <p>The two slot copies, the pickle and the constructor's sentence are in all four books, in the order
     * the C file writes them, and the difference is the fifth row: {@code enumerate} is generic over what it
     * walks, so {@code enum_methods} gets a {@code __class_getitem__}, while {@code zip} and {@code map}
     * hold a flag that the argument list of {@code zip(*sources)} cannot carry, so their tables get a {@code
     * __setstate__}. {@code filter} has neither need, and its five names are the whole book. The counts under
     * {@code dir()} — twenty-seven, twenty-seven, twenty-seven, twenty-six against {@code object}'s
     * twenty-four — move with that fifth row and not with the book's length, because {@code __reduce__},
     * {@code __doc__} and {@code __new__} are three of the five rows {@code object} files too, each with its
     * own sentence behind the doc.
     */
    @Test
    void theFourBooksDifferOnlyInTheRowEachOneAloneCarries() {
        assertThat(filed(PyType.ENUMERATE)).isEqualTo(ENUMERATE_BOOK);
        assertThat(TypeDict.declared(PyType.ENUMERATE).keys()).hasSize(6);
        assertThat(Dirs.classNames(PyType.ENUMERATE)).hasSize(27);
        assertThat(gained(PyType.ENUMERATE)).isEqualTo(GENERIC_GAINED);

        assertThat(filed(PyType.ZIP)).isEqualTo(FLAGGED_BOOK);
        assertThat(TypeDict.declared(PyType.ZIP).keys()).hasSize(6);
        assertThat(Dirs.classNames(PyType.ZIP)).hasSize(27);
        assertThat(gained(PyType.ZIP)).isEqualTo(FLAG_GAINED);

        assertThat(filed(PyType.MAP)).isEqualTo(FLAGGED_BOOK);
        assertThat(TypeDict.declared(PyType.MAP).keys()).hasSize(6);
        assertThat(Dirs.classNames(PyType.MAP)).hasSize(27);
        assertThat(gained(PyType.MAP)).isEqualTo(FLAG_GAINED);

        assertThat(filed(PyType.FILTER)).isEqualTo(FILTER_BOOK);
        assertThat(TypeDict.declared(PyType.FILTER).keys()).hasSize(5);
        assertThat(Dirs.classNames(PyType.FILTER)).hasSize(26);
        assertThat(gained(PyType.FILTER)).isEqualTo(BARE_GAINED);

        // The two rows {@code reversed} files and these four do not, and each family's own absence.
        assertThat(entry(PyType.ENUMERATE, "__length_hint__")).isNull();
        assertThat(entry(PyType.ZIP, "__length_hint__")).isNull();
        assertThat(entry(PyType.ENUMERATE, RESTART)).isNull();
        assertThat(entry(PyType.FILTER, RESTART)).isNull();
        assertThat(entry(PyType.ZIP, GENERIC)).isNull();
        assertThat(entry(PyType.MAP, GENERIC)).isNull();
        assertThat(entry(PyType.FILTER, GENERIC)).isNull();

        // A cursor keeps none of this on itself: {@code vars()} of a value is still refused.
        refuses(
                "TypeError: vars() argument must have __dict__ attribute",
                () ->
                        Slots.call(
                                PyRuntime.builtins().get(PyStr.interned("vars")),
                                new PyObject[] {zipped(PAIRS)},
                                null));
    }

    /**
     * Every row is the kind its C table registers, and answers the text that table writes.
     *
     * <p>{@code tp_new} is copied out of the type as a {@code builtin_function_or_method} that takes the
     * class first, the two slots are {@code wrapper_descriptor}s of {@code ($self, /)}, and the
     * method-table rows are {@code method_descriptor}s — the pickle bare, the restart and the generic with
     * one argument each. The tail entry is a {@code str} rather than a row, so reading {@code __doc__} off
     * it gives {@code str}'s own sentence, which is why the corpus case reads the type there and not the
     * entry. Both halves of the type's text come from one stored string: the table for {@code
     * __text_signature__} and the sentence below the {@code --} rule for {@code __doc__}.
     */
    @Test
    void eachRowIsTheKindAndTheTextItsOwnCFileGivesIt() {
        for (PyType kind : FAMILY) {
            assertThat(rowKind(kind, CREATOR))
                    .as(kind.name())
                    .isEqualTo("builtin_function_or_method");
            assertThat(signature(kind, CREATOR)).isEqualTo("($type, *args, **kwargs)");
            assertThat(carried(kind, CREATOR)).isEqualTo(NEW_DOC);
            assertThat(named(kind, CREATOR)).isEqualTo(kind.name() + ".__new__");
            for (String slot : SLOTS) {
                assertThat(rowKind(kind, slot)).as(kind.name()).isEqualTo("wrapper_descriptor");
                assertThat(signature(kind, slot)).isEqualTo("($self, /)");
            }
            assertThat(carried(kind, "__iter__")).isEqualTo(ITER_DOC);
            assertThat(carried(kind, "__next__")).isEqualTo(NEXT_DOC);
            assertThat(rowKind(kind, REDUCER)).as(kind.name()).isEqualTo("method_descriptor");
            assertThat(signature(kind, REDUCER)).isEqualTo("($self, /)");
            assertThat(carried(kind, REDUCER)).isEqualTo(REDUCE_DOC);
            assertThat(named(kind, REDUCER)).isEqualTo(kind.name() + ".__reduce__");
            assertThat(entry(kind, "__doc__")).as(kind.name()).isInstanceOf(PyStr.class);
        }

        assertThat(rowKind(PyType.ENUMERATE, GENERIC)).isEqualTo("classmethod_descriptor");
        assertThat(signature(PyType.ENUMERATE, GENERIC)).isEqualTo("($type, object, /)");
        assertThat(carried(PyType.ENUMERATE, GENERIC)).isEqualTo(GENERIC_DOC);
        assertThat(named(PyType.ENUMERATE, GENERIC)).isEqualTo("enumerate.__class_getitem__");

        for (PyType flagged : List.of(PyType.ZIP, PyType.MAP)) {
            assertThat(rowKind(flagged, RESTART))
                    .as(flagged.name())
                    .isEqualTo("method_descriptor");
            assertThat(signature(flagged, RESTART)).isEqualTo("($self, object, /)");
            assertThat(carried(flagged, RESTART)).isEqualTo(SETSTATE_DOC);
            assertThat(named(flagged, RESTART)).isEqualTo(flagged.name() + ".__setstate__");
        }

        assertThat(Slots.getAttr(PyType.ENUMERATE, "__text_signature__"))
                .isEqualTo(PyStr.of("(iterable, start=0)"));
        assertThat(Slots.getAttr(PyType.ZIP, "__text_signature__"))
                .isEqualTo(PyStr.of("(*iterables, strict=False)"));
        assertThat(Slots.getAttr(PyType.MAP, "__text_signature__"))
                .isEqualTo(PyStr.of("(function, iterable, /, *iterables, strict=False)"));
        assertThat(Slots.getAttr(PyType.FILTER, "__text_signature__"))
                .isEqualTo(PyStr.of("(function, iterable, /)"));
        assertThat(firstLine(PyType.ENUMERATE)).isEqualTo("Return an enumerate object.");
        assertThat(firstLine(PyType.ZIP))
                .isEqualTo("The zip object yields n-length tuples, where n is the number of");
        assertThat(firstLine(PyType.MAP))
                .isEqualTo("Make an iterator that computes the function using arguments from");
        assertThat(firstLine(PyType.FILTER))
                .isEqualTo("Return an iterator yielding those items of iterable for which");
    }

    /**
     * One constructor, two spellings, and the one name where they disagree.
     *
     * <p>{@code enumerate_vectorcall} counts {@code iterable} and {@code start} by hand and says so in the
     * words no clinic table wrote, while {@code enumerate.__new__} runs {@code _PyArg_UnpackKeywords} over
     * the same two names — so a call with no iterable gets {@code (pos 1)} on the end through one door and
     * not through the other, and a call that names only {@code start} is refused as a bad keyword by one
     * and as a missing iterable by the other, because the table looks for what it needs before it looks at
     * what it was given. The other three have no such pair: {@code zip} has no vectorcall at all, {@code
     * map}'s hands a keyword call back through {@code _PyObject_MakeTpCall}, and {@code filter}'s two paths
     * refuse in the same words. What all four share is the gate in front of everything: {@code
     * tp_new_wrapper} reads the class it was handed, and complains about it before anything is counted.
     */
    @Test
    void oneCallTwoSpellingsAgreeExceptWhereEnumerateCountsByHand() {
        refuses(
                "TypeError: enumerate() missing required argument 'iterable'",
                () -> enumerated());
        refuses(
                "TypeError: enumerate() missing required argument 'iterable' (pos 1)",
                () -> viaNew(PyType.ENUMERATE, new PyObject[0], null));
        refuses(
                "TypeError: 'start' is an invalid keyword argument for enumerate()",
                () -> enumerated(START, ONE));
        refuses(
                "TypeError: enumerate() missing required argument 'iterable' (pos 1)",
                () -> viaNew(PyType.ENUMERATE, new PyObject[] {ONE}, START));
        refuses(
                "TypeError: 'x' is an invalid keyword argument for enumerate()",
                () -> enumerated(X, ONE));
        refuses(
                "TypeError: enumerate() missing required argument 'iterable' (pos 1)",
                () -> viaNew(PyType.ENUMERATE, new PyObject[] {ONE}, X));
        assertThat(enumerated(ITERABLE, PAIRS).getType()).isSameAs(PyType.ENUMERATE);
        assertThat(viaNew(PyType.ENUMERATE, new PyObject[] {PAIRS}, ITERABLE).getType())
                .isSameAs(PyType.ENUMERATE);

        // The refusals the hand-written count and the clinic table agree on, word for word.
        refuses(
                "TypeError: enumerate() takes at most 2 arguments (3 given)",
                () -> enumerated(PAIRS, TWO, THREE));
        refuses(
                "TypeError: enumerate() takes at most 2 arguments (3 given)",
                () -> viaNew(PyType.ENUMERATE, new PyObject[] {PAIRS, TWO, THREE}, null));

        // `map`: the count in the vectorcall's words, and the flag in the table's, from one path.
        refuses("TypeError: map() must have at least two arguments.", () -> mapped(TWICE));
        refuses(
                "TypeError: map() must have at least two arguments.",
                () -> viaNew(PyType.MAP, new PyObject[] {TWICE}, null));
        refuses(
                "TypeError: map() takes at most 1 keyword argument (2 given)",
                () -> mapped(names("function", "iterable"), TWICE, PAIRS));
        refuses(
                "TypeError: map() takes at most 1 keyword argument (2 given)",
                () -> viaNew(PyType.MAP, new PyObject[] {TWICE, PAIRS}, names("function", "iterable")));
        refuses(
                "TypeError: map() got an unexpected keyword argument 'x'",
                () -> mapped(X, TWICE, PAIRS, ONE));
        refuses(
                "TypeError: map() got an unexpected keyword argument 'x'",
                () -> viaNew(PyType.MAP, new PyObject[] {TWICE, PAIRS, ONE}, X));

        // `zip`: one path, and a flag with no source behind it is a legal empty cursor.
        refuses("TypeError: zip() got an unexpected keyword argument 'x'", () -> zipped(X, ONE));
        refuses(
                "TypeError: zip() got an unexpected keyword argument 'x'",
                () -> viaNew(PyType.ZIP, new PyObject[] {ONE}, X));
        assertThat(reprs(walked(zipped(STRICT, TRUE)))).isEmpty();
        assertThat(reprs(walked(viaNew(PyType.ZIP, new PyObject[] {TRUE}, STRICT)))).isEmpty();

        // `filter`: the legacy family's words, twice, and a count no clinic table wrote.
        refuses("TypeError: filter expected 2 arguments, got 1", () -> filtered(TWICE));
        refuses(
                "TypeError: filter expected 2 arguments, got 1",
                () -> viaNew(PyType.FILTER, new PyObject[] {TWICE}, null));
        refuses(
                "TypeError: filter() takes no keyword arguments",
                () -> filtered(X, TWICE, PAIRS, ONE));
        refuses(
                "TypeError: filter() takes no keyword arguments",
                () -> viaNew(PyType.FILTER, new PyObject[] {TWICE, PAIRS, ONE}, X));

        // The gate every one of the four puts in front of its own count, and its own words.
        refuses(
                "TypeError: enumerate.__new__(list): list is not a subtype of enumerate",
                () -> Slots.call(
                        entry(PyType.ENUMERATE, CREATOR), new PyObject[] {PyType.LIST, PAIRS}, null));
        refuses(
                "TypeError: filter.__new__(list): list is not a subtype of filter",
                () -> Slots.call(
                        entry(PyType.FILTER, CREATOR), new PyObject[] {PyType.LIST, TWICE, PAIRS}, null));
        refuses(
                "TypeError: zip.__new__(X): X is not a type object (str)",
                () -> Slots.call(entry(PyType.ZIP, CREATOR), new PyObject[] {TEXT}, null));
        refuses(
                "TypeError: object.__new__(map) is not safe, use map.__new__()",
                () -> Slots.call(
                        Slots.getAttr(PyType.OBJECT, CREATOR),
                        new PyObject[] {PyType.MAP, TWICE, PAIRS},
                        null));
    }

    /** The keyword names of a call, as the vectorcall convention hands them to a body. */
    private static PyTuple names(String... given) {
        PyObject[] interned = new PyObject[given.length];
        for (int i = 0; i < given.length; i++) {
            interned[i] = PyStr.interned(given[i]);
        }
        return PyTuple.of(interned);
    }

    /**
     * What each constructor asks, and the order it asks it in.
     *
     * <p>The count and the keywords come first for all four, because a parameter list is read before a
     * value is. After that they part: {@code enumerate} asks {@code PyNumber_Index} of the start and only
     * then {@code PyObject_GetIter} of the source, so {@code enumerate(1, start='x')} is about the string
     * and never asks the integer to be walked; {@code zip} and {@code map} ask the <em>truth</em> of {@code
     * strict} — the {@code p} format is a truth test and not a type check, so a {@code __bool__} that
     * complains is heard before any iterable is asked for an iterator, and a falsey answer turns the flag
     * off instead of refusing the value; {@code map} counts before it calls anything and {@code filter}
     * asks nothing at all about its predicate, so {@code filter(1, 1)} is about the {@code int} nobody asked
     * to walk rather than the one nobody asked to call.
     */
    @Test
    void theOrderTheConstructorAsksItsQuestionsIsWhereTheFourDiffer() {
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> enumerated(START, ONE, TEXT));
        refuses(
                "TypeError: 'Refusing' object cannot be interpreted as an integer",
                () -> enumerated(START, ONE, refusesTruth()));
        refuses(
                "TypeError: 'iterable' is an invalid keyword argument for enumerate()",
                () -> enumerated(ITERABLE, ONE, TWO));
        refuses(
                "TypeError: enumerate() takes at most 2 arguments (3 given)",
                () -> enumerated(START, ONE, TWO, THREE));
        refuses(
                "TypeError: enumerate() takes at most 2 arguments (3 given)",
                () -> enumerated(names("start", "iterable"), ONE, TWO, THREE));

        // The flag before the source, the flag before the count, and the flag with nothing at all.
        refuses("ValueError: refusing", () -> zipped(STRICT, ONE, refusesTruth()));
        refuses("ValueError: refusing", () -> mapped(STRICT, TWICE, refusesTruth()));
        refuses("ValueError: refusing", () -> zipped(STRICT, refusesTruth()));

        // `strict` is keyword-only, so a positional `True` is one more source to walk.
        refuses("TypeError: 'bool' object is not iterable", () -> zipped(PAIRS, TRUE));
        refuses(
                "TypeError: 'Refusing' object is not iterable",
                () -> mapped(TWICE, PAIRS, refusesTruth()));
        assertThat(viaNew(PyType.ZIP, new PyObject[] {PAIRS, ONE}, STRICT).getType())
                .isSameAs(PyType.ZIP);

        // The keywords before the count, and the source before the predicate.
        refuses("TypeError: filter() takes no keyword arguments", () -> filtered(X, TWICE, ONE, ONE));
        refuses("TypeError: 'int' object is not iterable", () -> filtered(TWICE, ONE));
        refuses("TypeError: 'int' object is not iterable", () -> mapped(TWICE, ONE));
        refuses("TypeError: 'int' object is not iterable", () -> mapped(TWICE, PAIRS, ONE));
        refuses("TypeError: 'int' object is not iterable", () -> zipped(PAIRS, ONE));

        // Nothing a cursor holds is called by the call that makes it; the walk is where the call is.
        assertThat(filtered(ZERO, PAIRS).getType()).isSameAs(PyType.FILTER);
        assertThat(mapped(ZERO, PAIRS).getType()).isSameAs(PyType.MAP);
        refuses("TypeError: 'int' object is not callable", () -> walked(filtered(ZERO, PAIRS)));
        refuses("TypeError: 'int' object is not callable", () -> walked(mapped(ZERO, PAIRS)));
    }

    /**
     * The count of {@code enumerate} is as wide as Python integers are, over and under a machine word.
     *
     * <p>{@code enum_new_impl} keeps it two ways — {@code PyLong_AsSsize_t} over the {@code __index__}
     * answer and, when that overflows, a {@code PyErr_Clear()} and a wide {@code en_longindex} that adds one
     * at a time — and {@code enum_next} leaves the narrow path the moment the count reaches {@code
     * PY_SSIZE_T_MAX}. So the edges do not wrap: {@code 2 ** 63 - 1} counts on into sixty-four-bit overflow
     * rather than coming back negative, and {@code -(2 ** 63) - 1} counts up through the bottom of the word.
     * A {@code bool} is an {@code __index__} answer, which is what makes {@code start=True} the same call as
     * {@code start=1}; a {@code float} and a {@code str} are not.
     */
    @Test
    void theCountWalksOverTheEdgeOfAMachineWordWithoutWrapping() {
        assertThat(reprs(walked(enumerated(PAIRS, PyInt.of(WORD.subtract(BigInteger.valueOf(3)))))))
                .containsExactly("(9223372036854775805, 1)", "(9223372036854775806, 2)");
        assertThat(reprs(walked(enumerated(PAIRS, PyInt.of(WORD.subtract(BigInteger.ONE))))))
                .containsExactly("(9223372036854775807, 1)", "(9223372036854775808, 2)");
        assertThat(reprs(walked(enumerated(PAIRS, PyInt.of(WORD)))))
                .containsExactly("(9223372036854775808, 1)", "(9223372036854775809, 2)");
        assertThat(reprs(walked(enumerated(PAIRS, PyInt.of(WORD.shiftLeft(1))))))
                .containsExactly("(18446744073709551616, 1)", "(18446744073709551617, 2)");
        assertThat(reprs(walked(enumerated(PAIRS, PyInt.of(-1)))))
                .containsExactly("(-1, 1)", "(0, 2)");
        assertThat(reprs(walked(enumerated(PAIRS, PyInt.of(WORD.negate().subtract(BigInteger.ONE))))))
                .containsExactly("(-9223372036854775809, 1)", "(-9223372036854775808, 2)");

        // A `bool` answers `__index__`, so the walk starts where the truth of the value says.
        assertThat(reprs(walked(enumerated(PAIRS, TRUE)))).containsExactly("(1, 1)", "(2, 2)");
        assertThat(reprs(walked(enumerated(LETTERS, TRUE))))
                .containsExactly("(1, 'a')", "(2, 'b')");
        assertThat(reprs(walked(enumerated(LETTERS)))).containsExactly("(0, 'a')", "(1, 'b')");

        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> enumerated(PAIRS, REAL));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> enumerated(PAIRS, TEXT));
        assertThat(reprs(walked(enumerated((PyObject) PyTuple.EMPTY, PyInt.of(BigInteger.ONE)))))
                .isEmpty();
    }

    /**
     * The walk ends at the first source that has nothing, and hands back what it did not take.
     *
     * <p>{@code zip_next} reads the number of sources before it reads any of them, which is why a {@code
     * zip()} of none ends at once rather than handing out the empty tuple for ever — and why a cursor over
     * one source steps once and then has nothing. The sources after the one that ran out are not asked at
     * all in a plain cursor, so a shared iterator keeps the item a {@code zip} never took, and {@code map}
     * leaves its later sources alone the same way; a one-source {@code map} over an empty iterable never
     * calls its function. A refusal that is not an end is not swallowed either: the {@code KeyError} of a
     * source travels out of all four walks unchanged, and out of a strict one too.
     */
    @Test
    void theWalkStopsAtTheFirstSourceAndLeavesTheRestOfThemAlone() {
        assertThat(reprs(walked(zipped()))).isEmpty();
        assertThat(reprs(walked(zipped(PAIRS, SINGLE)))).containsExactly("(1, 3)");
        assertThat(reprs(walked(zipped(PyList.of(ONE, TWO, THREE), PyList.of(FOUR)))))
                .containsExactly("(1, 4)");

        // One source of one item is one step and then nothing: a `zip` of one is not endless.
        PyObject oneSource = zipped(PyList.of(ONE));
        assertThat(nextOf(oneSource).repr()).isEqualTo("(1,)");
        assertThat(((PyIterator) oneSource).nextOrNull()).isNull();

        // A later source is not asked once an earlier one has ended, and keeps what it held.
        // The empty source is told from the keyword names, which are a tuple of their own overload.
        PyIterator untouched = PyIterator.of(new PyObject[] {ONE, TWO});
        assertThat(reprs(walked(zipped((PyObject) PyTuple.EMPTY, untouched)))).isEmpty();
        assertThat(untouched.nextOrNull()).isSameAs(ONE);
        PyIterator alsoUntouched = PyIterator.of(new PyObject[] {ONE, TWO});
        assertThat(reprs(walked(mapped(JOIN, PyTuple.EMPTY, alsoUntouched)))).isEmpty();
        assertThat(alsoUntouched.nextOrNull()).isSameAs(ONE);

        // The walk of a `map` is a call of its function, and the call is not made without a full argument.
        assertThat(reprs(walked(mapped(TWICE, PAIRS)))).containsExactly("2", "4");
        assertThat(reprs(walked(mapped(JOIN, PAIRS, TRIPLE))))
                .containsExactly("(1, 3)", "(2, 4)");
        assertThat(reprs(walked(mapped(TWICE, PyTuple.EMPTY)))).isEmpty();
        // A step of `map` is a call whatever the function is: `None` is not a special value here.
        refuses(
                "TypeError: 'NoneType' object is not callable",
                () -> walked(mapped(NONE, PyList.of(ONE))));
        refuses(
                "TypeError: twice() takes 1 positional argument but 2 were given",
                () -> walked(mapped(TWICE, PAIRS, TRIPLE)));

        // A source that refuses rather than ends travels out of every one of the four walks.
        refuses("KeyError: 'not the end'", () -> walked(enumerated(new Complaining(ONE, TWO))));
        refuses("KeyError: 'not the end'", () -> walked(mapped(TWICE, new Complaining(ONE, TWO))));
        refuses("KeyError: 'not the end'", () -> walked(filtered(TWICE, new Complaining(ONE, TWO))));
        refuses(
                "KeyError: 'not the end'",
                () -> walked(zipped(new Complaining(ONE, TWO), PAIRS)));

        // `filter` asks the value its own truth when the predicate is `None` or `bool`, and otherwise calls.
        assertThat(reprs(walked(filtered(NONE, PyList.of(ZERO, ONE, PyStr.of("a"), FALSEY)))))
                .containsExactly("1", "'a'");
        assertThat(reprs(walked(filtered(PyType.BOOL, PyList.of(ZERO, ONE, TWO)))))
                .containsExactly("1", "2");
        assertThat(reprs(walked(filtered(TWICE, PyList.of(ZERO, ONE))))).containsExactly("1");
        refuses("ValueError: refusing", () -> walked(filtered(NONE, PyList.of(refusesTruth()))));
        refuses(
                "ValueError: refusing",
                () -> walked(filtered(PyType.BOOL, PyList.of(refusesTruth()))));
        refuses(
                "TypeError: 'int' object is not callable",
                () -> walked(filtered(ZERO, PyList.of(refusesTruth()))));
    }

    /**
     * {@code strict} reads the rest of the sources, and complains in one of two sentences.
     *
     * <p>The step that finds nothing is where a strict cursor parts from a plain one: it goes on to ask the
     * sources after that step, and what comes back decides which sentence it writes. If the source that
     * ended was not the first, an earlier one had more, so the complaint is that this argument <em>is
     * shorter than</em> the run of arguments before it — {@code argument 1} singular, {@code arguments 1-2}
     * plural. If the very first source ended at once, any later one that still has something makes the
     * other sentence, <em>is longer than</em> the ones before it, and the walk ends without complaint only
     * when every source was empty too. {@code map} writes the same two sentences about its own arguments,
     * which is why one {@code check} block serves both, and a falsey flag read by the same {@code
     * PyObject_IsTrue} is a plain cursor again.
     */
    @Test
    void strictWalksTheRestOfTheSourcesAndNamesTheOneThatDiffers() {
        assertThat(reprs(walked(zipped(STRICT, PAIRS, TRIPLE, TRUE))))
                .containsExactly("(1, 3)", "(2, 4)");
        refuses(
                "ValueError: zip() argument 2 is shorter than argument 1",
                () -> walked(zipped(STRICT, PAIRS, SINGLE, TRUE)));
        refuses(
                "ValueError: zip() argument 2 is longer than argument 1",
                () -> walked(zipped(STRICT, PyList.of(ONE), TRIPLE, TRUE)));
        refuses(
                "ValueError: zip() argument 3 is shorter than arguments 1-2",
                () -> walked(zipped(STRICT, PAIRS, TRIPLE, SINGLE, TRUE)));
        refuses(
                "ValueError: zip() argument 3 is longer than arguments 1-2",
                () -> walked(zipped(STRICT, PAIRS, TRIPLE, PyList.of(THREE, FOUR, SIX), TRUE)));
        refuses(
                "ValueError: zip() argument 2 is longer than argument 1",
                () -> walked(zipped(STRICT, PyTuple.EMPTY, PyList.of(ONE), TRUE)));
        assertThat(reprs(walked(zipped(STRICT, PyTuple.EMPTY, FALSEY, TRUE)))).isEmpty();
        assertThat(reprs(walked(zipped(STRICT, PAIRS, SINGLE, FALSEY)))).containsExactly("(1, 3)");

        // One source and a flag is nothing to be unbalanced against, in either direction.
        assertThat(reprs(walked(zipped(STRICT, PAIRS, TRUE)))).containsExactly("(1,)", "(2,)");
        assertThat(reprs(walked(mapped(STRICT, TWICE, PAIRS, TRUE)))).containsExactly("2", "4");

        // A strict cursor that has already stepped complains about what is left, not about the whole.
        PyObject stepped = zipped(STRICT, PAIRS, SINGLE, TRUE);
        assertThat(nextOf(stepped).repr()).isEqualTo("(1, 3)");
        refuses(
                "ValueError: zip() argument 2 is shorter than argument 1", () -> walked(stepped));

        refuses(
                "KeyError: 'not the end'",
                () -> walked(zipped(STRICT, new Complaining(ONE, TWO), PAIRS, TRUE)));
        refuses(
                "KeyError: 'not the end'",
                () -> walked(mapped(STRICT, JOIN, new Complaining(ONE, TWO), PAIRS, TRUE)));

        refuses(
                "ValueError: map() argument 2 is shorter than argument 1",
                () -> walked(mapped(STRICT, JOIN, PAIRS, SINGLE, TRUE)));
        refuses(
                "ValueError: map() argument 2 is longer than argument 1",
                () -> walked(mapped(STRICT, JOIN, PyList.of(ONE), TRIPLE, TRUE)));
        refuses(
                "ValueError: map() argument 2 is longer than argument 1",
                () -> walked(mapped(STRICT, JOIN, PyTuple.EMPTY, PyList.of(ONE), TRUE)));
        refuses(
                "ValueError: map() argument 3 is shorter than arguments 1-2",
                () -> walked(mapped(STRICT, SPREAD, PAIRS, TRIPLE, SINGLE, TRUE)));
        assertThat(reprs(walked(mapped(STRICT, JOIN, PyTuple.EMPTY, FALSEY, TRUE)))).isEmpty();
        assertThat(reprs(walked(mapped(STRICT, JOIN, PAIRS, SINGLE, FALSEY))))
                .containsExactly("(1, 3)");
    }

    /**
     * The pickle names its own type and hands back what the cursor holds, entire.
     *
     * <p>Four payloads and two item counts: {@code enumerate} the iterator and the count it would resume
     * at, {@code filter} the predicate and the one iterator, {@code zip} the tuple of iterators, {@code
     * map} the function and the iterators flattened into the same hand that a call of {@code map} takes.
     * All four name the type they are of as the callable to rebuild with, and none of them lets go of a
     * source when it runs out — the payload of a spent cursor is the payload of the same cursor born, which
     * is the one place this family differs from {@code reversed}. Putting the halves together walks the
     * rest of each source, so a found cursor is not a copy of anything, and a {@code map} whose function
     * cannot take the arguments it will be given cannot be found again either.
     */
    @Test
    void thePickleNamesItsOwnTypeAndNeverLetsGoOfTheSource() {
        PyIterator source = PyIterator.of(new PyObject[] {ONE, TWO});
        PyObject cursor = enumerated(source, SEVEN);
        PyTuple born = reduce(cursor);
        assertThat(born.size()).isEqualTo(2);
        assertThat(born.at(0)).isSameAs(PyType.ENUMERATE);
        assertThat(((PyTuple) born.at(1)).at(0)).isSameAs(source);
        assertThat(((PyTuple) born.at(1)).at(1)).isEqualTo(PyInt.of(7));

        // The count is the one the next pair carries, and a spent cursor keeps both.
        assertThat(reprs(walked(cursor))).containsExactly("(7, 1)", "(8, 2)");
        PyTuple spent = reduce(cursor);
        assertThat(((PyTuple) spent.at(1)).at(0)).isSameAs(source);
        assertThat(((PyTuple) spent.at(1)).at(1)).isEqualTo(PyInt.of(9));

        // The two halves put back together resume at the count, over the same iterator.
        PyObject rebuilt = rebuilt(enumerated(PyIterator.of(new PyObject[] {ONE, TWO}), SEVEN));
        assertThat(reprs(walked(rebuilt))).containsExactly("(7, 1)", "(8, 2)");

        PyIterator first = PyIterator.of(new PyObject[] {ONE, TWO});
        PyIterator second = PyIterator.of(new PyObject[] {THREE});
        PyObject pair = zipped(first, second);
        PyTuple zippedPayload = reduce(pair);
        assertThat(zippedPayload.size()).isEqualTo(2);
        assertThat(zippedPayload.at(0)).isSameAs(PyType.ZIP);
        assertThat(((PyTuple) zippedPayload.at(1)).getArray()).containsExactly(first, second);
        walked(pair);
        assertThat(((PyTuple) reduce(pair).at(1)).at(1)).isSameAs(second);

        // `map` flattens the function in front of the iterators, and calls it on the way back.
        PyIterator overSource = PyIterator.of(new PyObject[] {ONE, TWO});
        PyObject over = mapped(TWICE, overSource);
        PyTuple mappedPayload = reduce(over);
        assertThat(mappedPayload.at(0)).isSameAs(PyType.MAP);
        assertThat(((PyTuple) mappedPayload.at(1)).getArray()).containsExactly(TWICE, overSource);
        assertThat(reprs(walked(rebuilt(over)))).containsExactly("2", "4");

        PyObject kept = filtered(TWICE, PyList.of(ZERO, ONE, TWO));
        PyTuple filterPayload = reduce(kept);
        assertThat(filterPayload.size()).isEqualTo(2);
        assertThat(filterPayload.at(0)).isSameAs(PyType.FILTER);
        assertThat(((PyTuple) filterPayload.at(1)).at(0)).isSameAs(TWICE);
        assertThat(reprs(walked(rebuilt(kept)))).containsExactly("1", "2");

        // A cursor that cannot step cannot be found again either, and says so in the function's own words.
        PyObject ofTwoSources = mapped(TWICE, PAIRS, TRIPLE);
        refuses(
                "TypeError: twice() takes 1 positional argument but 2 were given",
                () -> walked(rebuilt(ofTwoSources)));
    }

    /** {@code type(*args)}: the callable a payload names, run over the arguments it carries. */
    private static PyObject rebuilt(PyObject cursor) {
        PyTuple payload = reduce(cursor);
        return Slots.call(payload.at(0), ((PyTuple) payload.at(1)).getArray(), null);
    }

    /**
     * The state the arguments cannot carry, and the pickle that has to write it.
     *
     * <p>{@code zip_setstate} and {@code map_setstate} do one thing: read the truth of the state and keep
     * it. That is the only way to reach the flag on a cursor that was made without the keyword, so a found
     * strict cursor is a plain one until the state is written back, and the third item of {@code __reduce__}
     * appears only while the flag is on. Because the state is read by {@code PyObject_IsTrue} rather than
     * type-checked, a falsey value turns a strict cursor back into a plain one and a value whose {@code
     * __bool__} complains is heard where it stands. {@code enumerate} and {@code filter} have no such row,
     * because everything they hold the argument list can say.
     */
    @Test
    void setStateIsTheOnlyWayToReachTheFlagTheArgumentsCannotCarry() {
        for (String name : List.of("zip", "map")) {
            PyObject cursor = "zip".equals(name) ? zipped(PAIRS, SINGLE) : mapped(JOIN, PAIRS, SINGLE);
            assertThat(setState(cursor, TRUE)).isSameAs(NONE);
            assertThat(reduce(cursor).size()).as(name).isEqualTo(3);
            assertThat(reduce(cursor).at(2)).as(name).isSameAs(TRUE);
            refuses(
                    "ValueError: " + name + "() argument 2 is shorter than argument 1",
                    () -> walked(cursor));

            PyObject falsey = "zip".equals(name) ? zipped(PAIRS, SINGLE) : mapped(JOIN, PAIRS, SINGLE);
            setState(falsey, FALSEY);
            assertThat(reduce(falsey).size()).as(name).isEqualTo(2);
            assertThat(reprs(walked(falsey))).containsExactly("(1, 3)");

            PyObject fresh = "zip".equals(name) ? zipped(PAIRS, TRIPLE) : mapped(JOIN, PAIRS, TRIPLE);
            refuses("ValueError: refusing", () -> setState(fresh, refusesTruth()));
            assertThat(reduce(fresh).size()).as(name).isEqualTo(2);

            PyObject plain = rebuilt(fresh);
            assertThat(reduce(plain).size()).as(name).isEqualTo(2);
            PyObject found = rebuilt(fresh);
            setState(found, TRUE);
            assertThat(reprs(walked(found))).containsExactly("(1, 3)", "(2, 4)");
        }

        // The flag a call did carry is not carried by the two halves alone, only by the state beside them.
        PyObject strict = zipped(STRICT, PAIRS, SINGLE, TRUE);
        PyTuple payload = reduce(strict);
        assertThat(payload.size()).isEqualTo(3);
        assertThat(payload.at(2)).isSameAs(TRUE);
        PyObject back = rebuilt(strict);
        assertThat(reduce(back).size()).isEqualTo(2);
        setState(back, payload.at(2));
        refuses(
                "ValueError: zip() argument 2 is shorter than argument 1",
                () -> walked(back));

        assertThat(entry(PyType.ENUMERATE, RESTART)).isNull();
        assertThat(entry(PyType.FILTER, RESTART)).isNull();
    }

    /**
     * The rows beside the pickle, called on their own and on a stranger.
     *
     * <p>A dictionary entry is not a bound method: {@code zip.__setstate__} takes the receiver as its first
     * argument, so no argument at all is {@code unbound method zip.__setstate__() needs an argument}, a
     * receiver and nothing else is the {@code METH_O} count refusing, and a receiver of the wrong type is
     * the descriptor's own sentence — checked before the count, which is why {@code zip.__setstate__([1],
     * 1)} and {@code zip.__setstate__([1])} say the same thing. {@code filter.__reduce__} is {@code
     * METH_NOARGS}, so a second argument reaching it is complained about as a count rather than dropped,
     * and {@code enumerate.__class_getitem__} is the one {@code METH_CLASS} of the family: its unbound
     * entry takes the class in front of the object, so it counts what is left once the class is off, asks
     * that the class be its own before it counts, and asks that it be a type at all before that.
     */
    @Test
    void theRowsBesideThePickleRefuseWhenTheyAreCalledWithoutAReceiver() {
        PyObject own = zipped(PAIRS);
        refuses(
                "TypeError: unbound method zip.__setstate__() needs an argument",
                () -> unbound(PyType.ZIP, RESTART, new PyObject[0], null));
        refuses(
                "TypeError: zip.__setstate__() takes exactly one argument (0 given)",
                () -> unbound(PyType.ZIP, RESTART, new PyObject[] {own}, null));
        assertThat(unbound(PyType.ZIP, RESTART, new PyObject[] {own, ONE}, null)).isSameAs(NONE);
        refuses(
                "TypeError: zip.__setstate__() takes no keyword arguments",
                () -> unbound(PyType.ZIP, RESTART, new PyObject[] {own, ONE}, X));
        refuses(
                "TypeError: descriptor '__setstate__' for 'zip' objects doesn't apply to a "
                        + "'list' object",
                () -> unbound(PyType.ZIP, RESTART, new PyObject[] {PAIRS, ONE}, null));
        refuses(
                "TypeError: descriptor '__setstate__' for 'zip' objects doesn't apply to a "
                        + "'list' object",
                () -> unbound(PyType.ZIP, RESTART, new PyObject[] {PAIRS}, null));

        PyObject ownMap = mapped(TWICE, PAIRS);
        refuses(
                "TypeError: unbound method map.__setstate__() needs an argument",
                () -> unbound(PyType.MAP, RESTART, new PyObject[0], null));
        refuses(
                "TypeError: map.__setstate__() takes exactly one argument (0 given)",
                () -> unbound(PyType.MAP, RESTART, new PyObject[] {ownMap}, null));
        assertThat(unbound(PyType.MAP, RESTART, new PyObject[] {ownMap, ONE}, null)).isSameAs(NONE);
        refuses(
                "TypeError: descriptor '__setstate__' for 'map' objects doesn't apply to a "
                        + "'list' object",
                () -> unbound(PyType.MAP, RESTART, new PyObject[] {PAIRS}, null));

        PyObject ownFilter = filtered(TWICE, PAIRS);
        refuses(
                "TypeError: unbound method filter.__reduce__() needs an argument",
                () -> unbound(PyType.FILTER, REDUCER, new PyObject[0], null));
        assertThat(((PyTuple) unbound(PyType.FILTER, REDUCER, new PyObject[] {ownFilter}, null))
                        .at(0))
                .isSameAs(PyType.FILTER);
        refuses(
                "TypeError: filter.__reduce__() takes no arguments (1 given)",
                () -> unbound(PyType.FILTER, REDUCER, new PyObject[] {ownFilter, ONE}, null));
        refuses(
                "TypeError: filter.__reduce__() takes no keyword arguments",
                () -> unbound(PyType.FILTER, REDUCER, new PyObject[] {ownFilter, ONE}, X));
        refuses(
                "TypeError: descriptor '__reduce__' for 'filter' objects doesn't apply to a "
                        + "'list' object",
                () -> unbound(PyType.FILTER, REDUCER, new PyObject[] {PAIRS}, null));

        // The one METH_CLASS of the family: the class is its own first argument, and must be its own.
        refuses(
                "TypeError: descriptor '__class_getitem__' of 'enumerate' object needs an argument",
                () -> unbound(PyType.ENUMERATE, GENERIC, new PyObject[0], null));
        refuses(
                "TypeError: enumerate.__class_getitem__() takes exactly one argument (0 given)",
                () -> unbound(PyType.ENUMERATE, GENERIC, new PyObject[] {PyType.ENUMERATE}, null));
        assertThat(
                        unbound(
                                        PyType.ENUMERATE,
                                        GENERIC,
                                        new PyObject[] {PyType.ENUMERATE, PyType.INT},
                                        null)
                                .repr())
                .isEqualTo("enumerate[int]");
        refuses(
                "TypeError: enumerate.__class_getitem__() takes exactly one argument (2 given)",
                () -> unbound(
                        PyType.ENUMERATE,
                        GENERIC,
                        new PyObject[] {PyType.ENUMERATE, PyType.INT, PyType.STR},
                        null));
        refuses(
                "TypeError: enumerate.__class_getitem__() takes no keyword arguments",
                () -> unbound(
                        PyType.ENUMERATE, GENERIC, new PyObject[] {PyType.ENUMERATE, PyType.INT}, X));
        refuses(
                "TypeError: descriptor '__class_getitem__' requires a subtype of 'enumerate' "
                        + "but received 'int'",
                () -> unbound(PyType.ENUMERATE, GENERIC, new PyObject[] {PyType.INT}, null));
        refuses(
                "TypeError: descriptor '__class_getitem__' for type 'enumerate' needs a type, not "
                        + "a 'str' as arg 2",
                () -> unbound(PyType.ENUMERATE, GENERIC, new PyObject[] {TEXT, PyType.INT}, null));
    }

    /**
     * The one generic of the family, and the alias's other half.
     *
     * <p>{@code enum_methods} files {@code __class_getitem__} and the other three tables do not, so {@code
     * enumerate[int]} builds an alias and {@code zip[int]} refuses in the {@code type '<name>' is not
     * subscriptable} words — the same refusal {@code reversed} makes. A subscript of a <em>value</em> is
     * refused by the type rather than by the row, and a subscript of an alias is what an alias is not:
     * {@code enumerate[int] is not a generic class}. What an alias <em>is</em> is a callable over its
     * origin, because {@code ga_vectorcall} forwards: {@code enumerate[int]("ab")} is the ordinary cursor
     * with nothing of the alias kept, and {@code list[int]([1, 2])} is an ordinary list. The row read off a
     * value and the row read off the type answer the same call, because a class method binds the class the
     * read ran on.
     */
    @Test
    void onlyEnumerateIsGenericAndItsAliasCallsWhatItIsAnAliasOf() {
        PyObject alias = PyType.ENUMERATE.getItem(PyType.INT);
        assertThat(alias.repr()).isEqualTo("enumerate[int]");
        assertThat(((PyGenericAlias) alias).getAttr("__origin__")).isSameAs(PyType.ENUMERATE);
        assertThat(PyType.ENUMERATE.getItem(PyTuple.of(PyType.INT, PyType.STR)).repr())
                .isEqualTo("enumerate[int, str]");
        assertThat(PyType.ENUMERATE.getItem(PyTuple.EMPTY).repr()).isEqualTo("enumerate[()]");
        assertThat(alias.isCallable()).isTrue();

        PyObject walkedFromAlias = Slots.call(alias, new PyObject[] {LETTERS}, null);
        assertThat(walkedFromAlias.getType()).isSameAs(PyType.ENUMERATE);
        assertThat(reprs(walked(walkedFromAlias))).containsExactly("(0, 'a')", "(1, 'b')");

        // The origin is what a call of the alias reaches, refusals included.
        PyObject listOf = PyType.LIST.getItem(PyType.INT);
        assertThat(Slots.call(listOf, new PyObject[] {PAIRS}, null).repr()).isEqualTo("[1, 2]");
        refuses(
                "TypeError: list() takes no keyword arguments",
                () -> Slots.call(listOf, new PyObject[] {ONE}, X));
        refuses(
                "TypeError: enumerate[int] is not a generic class", () -> Slots.getItem(alias, PyType.STR));
        refuses(
                "TypeError: 'enumerate' object is not subscriptable",
                () -> Slots.getItem(enumerated(PAIRS), PyType.INT));

        for (PyType kind : List.of(PyType.ZIP, PyType.MAP, PyType.FILTER, PyType.REVERSED)) {
            refuses(
                    "TypeError: type '" + kind.name() + "' is not subscriptable",
                    () -> Slots.getItem(kind, PyType.INT));
        }

        assertThat(Slots.getAttr(PyType.ENUMERATE, GENERIC).call(new PyObject[] {PyType.INT}, null)
                        .repr())
                .isEqualTo("enumerate[int]");
        assertThat(Slots.getAttr(enumerated(PAIRS), GENERIC).call(new PyObject[] {PyType.INT}, null)
                        .repr())
                .isEqualTo("enumerate[int]");
        refuses(
                "TypeError: enumerate.__class_getitem__() takes exactly one argument (0 given)",
                () -> Slots.getAttr(PyType.ENUMERATE, GENERIC).call(new PyObject[0], null));
    }
}
