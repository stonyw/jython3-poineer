package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The seven names of the one cursor a builtin call makes for itself, measured against CPython 3.14.10.
 *
 * <p>Corpus case 116 reads the book and the walk from Python, one line per answer. What is pinned here is
 * the half a script cannot point at. The first of it is the two-halves-of-one-text business: {@code tp_doc}
 * is the constructor's clinic text, {@code type_get_doc} cuts the sentence out of it for {@code __doc__} and
 * {@code type_get_text_signature} cuts the table out of the very same text for {@code __text_signature__},
 * so one stored string answers two reads and neither answer is the whole. The second is that the builtin
 * and the type are one object: {@code builtins['reversed']} <em>is</em> {@code PyType.REVERSED}, whose
 * vectorcall is the constructor, so {@code reversed(x)} and {@code reversed.__new__(reversed, x)} are the
 * same four refusals in the same order.
 *
 * <p>Then the three questions the constructor asks and the order it asks them in, which a script can only
 * infer from which refusal comes back: the {@code __reversed__} of the <em>type</em> — so a {@code None}
 * written there refuses even on a class that both counts and subscripts, which is not the same thing as the
 * special being absent; then {@code PySequence_Check}, the protocol rather than a try at it; then the count.
 * Then the walk, which holds the sequence instead of a copy of it and so is ended by the source's own read,
 * and the two payload shapes: three items naming the sequence and the index, two naming nothing to walk.
 * And the restart, whose window is {@code [-1, n - 1]} with the bottom of it the marker at the top, so an
 * empty source is unrestartable for as long as it lives.
 */
class ReversedCursorTableTest {

    /** The seven names the book files, in the order the constructor row and the two tables wrote them. */
    private static final List<String> ORDER =
            List.of(
                    "__new__",
                    "__iter__",
                    "__next__",
                    "__length_hint__",
                    "__reduce__",
                    "__setstate__",
                    "__doc__");

    /** The row {@code tp_new} is copied out as, above the two slot copies. */
    private static final String CREATOR = "__new__";

    /** The two rows {@code tp_iter} and {@code tp_iternext} are copied out as. */
    private static final List<String> SLOTS = List.of("__iter__", "__next__");

    /** The three rows of {@code reversediter_methods} ({@code Objects/enumobject.c:538}). */
    private static final List<String> METHODS = List.of("__length_hint__", "__reduce__", "__setstate__");

    /** The two of those three filed as {@code METH_NOARGS}. */
    private static final List<String> BARE = List.of("__length_hint__", "__reduce__");

    /** The four names {@code dir()} of the cursor gains over {@code dir()} of {@code object}. */
    private static final List<String> OWN =
            List.of("__iter__", "__length_hint__", "__next__", "__setstate__");

    /** {@code reversed_new__doc__}, the one {@code tp_doc} of the sixteen cursors that carries a table. */
    private static final String CLINIC =
            "reversed(sequence, /)\n--\n\nReturn a reverse iterator over the values of the given sequence.";

    private static final String SENTENCE = "Return a reverse iterator over the values of the given sequence.";
    private static final String TABLE = "(sequence, /)";
    private static final String NEW_DOC =
            "Create and return a new object.  See help(type) for accurate signature.";
    private static final String ITER_DOC = "Implement iter(self).";
    private static final String NEXT_DOC = "Implement next(self).";
    private static final String LENGTH_HINT_DOC = "Private method returning an estimate of len(list(it)).";
    private static final String REDUCE_DOC = "Return state information for pickling.";
    private static final String SETSTATE_DOC = "Set state information for unpickling.";

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final PyObject ZERO = PyInt.ZERO;
    private static final PyObject ONE = PyInt.of(1);
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject TRUE = PyBool.TRUE;
    private static final PyObject MINUS_ONE = PyInt.of(-1);
    private static final PyObject MINUS_FIVE = PyInt.of(-5);
    private static final PyObject NINE = PyInt.of(9);
    private static final PyObject TEXT = PyStr.of("x");
    private static final PyObject REAL = PyFloat.of(2.5);

    /** {@code 2 ** 63} and {@code 2 ** 70}: either is one bit past the word {@code Py_ssize_t} is. */
    private static final PyObject WIDE = PyInt.of(two(63));

    private static final PyObject WIDER = PyInt.of(two(70));

    private static final BigInteger ZERO_BI = BigInteger.ZERO;
    private static final BigInteger ONE_BI = BigInteger.ONE;
    private static final BigInteger TWO_BI = BigInteger.TWO;
    private static final BigInteger THREE_BI = BigInteger.valueOf(3);
    private static final BigInteger FOUR_BI = BigInteger.valueOf(4);

    /** {@code (10, 20, 30, 40)}: the four values every walk here is counted in. */
    private static final PyObject SOURCE =
            PyTuple.of(PyInt.of(10), PyInt.of(20), PyInt.of(30), PyInt.of(40));

    /** One keyword name, for the calls that argue about how they were written. */
    private static final PyTuple X = PyTuple.of(PyStr.interned("x"));

    private static final PyTuple SEQUENCE = PyTuple.of(PyStr.interned("sequence"));

    private static BigInteger two(int exponent) {
        return BigInteger.valueOf(2).pow(exponent);
    }

    /** The entry the cursor's own dictionary keeps under {@code name}, or null where it keeps none. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.REVERSED).get(PyStr.interned(name));
    }

    /** The names the type's dictionary keeps, in the order it kept them. */
    private static List<String> filed() {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(PyType.REVERSED, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** {@code sorted(set(dir(reversed) - set(dir(object)))}, which is four names. */
    private static List<String> gained() {
        List<String> names = new ArrayList<>(Dirs.classNames(PyType.REVERSED));
        names.removeAll(Dirs.classNames(PyType.OBJECT));
        Collections.sort(names);
        return names;
    }

    /** {@code reversed(source)}: the builtin, reached the way a call does. */
    private static PyObject builtin(PyObject source) {
        PyObject callable = PyRuntime.builtins().get(PyStr.interned("reversed"));
        return Slots.call(callable, new PyObject[] {source}, null);
    }

    /** The cursor over the four values, fresh each time it is asked for. */
    private static PyObject cursor() {
        return Reversed.over(SOURCE);
    }

    /** {@code it.__next__()}: one step, read as an attribute and then called. */
    private static PyObject nextOf(PyObject iterator) {
        return Slots.getAttr(iterator, "__next__").call(new PyObject[0], null);
    }

    /** {@code it.__length_hint__()}: the values still in front of this cursor. */
    private static BigInteger hint(PyObject iterator) {
        return ((PyInt) Slots.getAttr(iterator, "__length_hint__").call(new PyObject[0], null)).getValue();
    }

    /** {@code it.__setstate__(state)}: the only way to move a cursor without walking it. */
    private static PyObject setState(PyObject iterator, PyObject state) {
        return Slots.getAttr(iterator, "__setstate__").call(new PyObject[] {state}, null);
    }

    /** The hint and then the rest of the walk, which is the pair every jump here is measured by. */
    private static List<Object> jumped(PyObject iterator, PyObject state) {
        setState(iterator, state);
        return List.of(hint(iterator), reprs(walked(iterator)));
    }

    /** {@code it.__reduce__()}: the pickle payload, still holding the callable it named. */
    private static PyTuple reduce(PyObject iterator) {
        return (PyTuple) Slots.getAttr(iterator, "__reduce__").call(new PyObject[0], null);
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

    /** {@code vars(reversed)[name](*args)}: a filed row, called without an attribute read. */
    private static PyObject call(String name, PyObject... args) {
        return Slots.call(entry(name), args, null);
    }

    /** {@code vars(reversed)[name](*args, **kwargs)}. */
    private static PyObject calledWith(PyTuple kwNames, String name, PyObject... args) {
        return Slots.call(entry(name), args, kwNames);
    }

    /** A class body, and a value of it: the walk's sources are all written from Java here. */
    private static PyObject valueOf(String name, Consumer<PyDict> fill) {
        PyDict dict = PyDict.empty();
        fill.accept(dict);
        return Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of(name), PyTuple.of(PyType.OBJECT), dict},
                        null)
                .call(new PyObject[0], null);
    }

    private static void writes(PyDict dict, String name, String doc, PyNativeFunction.Body body) {
        dict.put(PyStr.interned(name), new PyNativeFunction(name, body));
    }

    /** The row named {@code name}, answering whatever the body hands back and ignoring its arguments. */
    private static void answers(PyDict dict, String name, PyObject answer) {
        writes(dict, name, null, (args, kwNames) -> answer);
    }

    /**
     * {@code name} filed as a plain value rather than a function. The constructor calls whatever the special
     * answers with, so this is the one way to make it complain about the {@code __reversed__} of a class that
     * wrote something uncallable there.
     */
    private static void files(PyDict dict, String name, PyObject value) {
        dict.put(PyStr.interned(name), value);
    }

    /**
     * The Java stand-in for a class body that wrote the two sequence methods.
     *
     * <p>{@link Specials#invoke} binds the {@code PyFunction} a {@code def} makes, and a function written
     * into a namespace from Java is not one, so the two slots a Python class fills by declaring {@code
     * __len__} and {@code __getitem__} are filled here in Java. The <em>class</em> is one {@link
     * Classes#newType} really built, which is where the name every refusal is worded with comes from, where
     * {@code PySequence_Check} asks whether {@code __getitem__} is filed at all, and where the {@code
     * __reversed__} question is put — all three of which a script can write and this runtime answers from
     * the names rather than from the bodies.
     */
    private static class Sequence extends PyObject {

        private final Supplier<PyObject> count;
        private final Function<PyObject, PyObject> read;

        Sequence(PyType kind, Supplier<PyObject> count, Function<PyObject, PyObject> read) {
            super(kind);
            this.count = count;
            this.read = read;
        }

        @Override
        public PyObject length() {
            return count == null ? super.length() : count.get();
        }

        @Override
        public PyObject getItem(PyObject key) {
            return read == null ? super.getItem(key) : read.apply(key);
        }
    }

    /** The class named {@code kind}, filing only the two sequence names it is asked to answer with. */
    private static PyType written(String kind, boolean counts, boolean subscripts) {
        return written(kind, counts, subscripts, false);
    }

    /** The same class, and {@code __reversed__ = None} written on it where the fourth answer says so. */
    private static PyType written(String kind, boolean counts, boolean subscripts, boolean refuses) {
        PyDict dict = PyDict.empty();
        if (counts) {
            dict.put(PyStr.interned("__len__"), new PyNativeFunction("as_len", (args, kwNames) -> ZERO));
        }
        if (subscripts) {
            dict.put(
                    PyStr.interned("__getitem__"),
                    new PyNativeFunction("as_get", (args, kwNames) -> ZERO));
        }
        if (refuses) {
            dict.put(PyStr.interned("__reversed__"), NONE);
        }
        return (PyType) Classes.newType(
                PyType.TYPE, new PyObject[] {PyStr.of(kind), PyTuple.of(PyType.OBJECT), dict}, null);
    }

    /** A source of three that a test can cut down while a walk is holding it. */
    private static final class Backed {

        private final List<PyObject> items = new ArrayList<>(List.of(ONE, TWO, THREE));

        private final PyObject value =
                new Sequence(
                        written("Backed", true, true),
                        () -> PyInt.of(items.size()),
                        key -> {
                            long at = Numbers.asInteger(key).longValue();
                            if (at < 0 || at >= items.size()) {
                                throw Slots.indexError("out of range");
                            }
                            return items.get((int) at);
                        });

        private void cut(int from) {
            items.subList(from, items.size()).clear();
        }
    }

    /** A value whose {@code __reversed__} is the {@code None} that the constructor reads as a refusal. */
    private static PyObject refusesSpecial(boolean sequence) {
        return sequence
                ? new Sequence(written("Refuses", true, true, true), () -> THREE, key -> key)
                : new Sequence(written("Refuses", false, false, true), null, null);
    }

    /** A value that counts but never subscripts, which is the protocol question rather than the count. */
    private static PyObject countedOnly() {
        return new Sequence(written("Counted", true, false), () -> THREE, null);
    }

    /** A value that subscripts but never counts: the one road to the refusal that names no cursor. */
    private static PyObject indexedOnly() {
        return new Sequence(written("Indexed", false, true), null, key -> key);
    }

    /** A value whose {@code __len__} complains, which the constructor hears before it makes anything. */
    private static PyObject lengthRefuses() {
        return new Sequence(
                written("Refused", true, true),
                () -> {
                    throw Slots.valueError("no count");
                },
                key -> key);
    }

    /**
     * A value that answers the count {@code free} times and complains afterwards. The constructor takes the
     * first ask, so {@code free} of one leaves a live walk over a source that no longer counts — and the
     * estimate and the restart are the two reads that then go and ask it again.
     */
    private static PyObject countedThenRefuses(int free) {
        int[] asked = new int[1];
        return new Sequence(
                written("CountedThen", true, true),
                () -> {
                    if (asked[0]++ >= free) {
                        throw Slots.valueError("no count");
                    }
                    return THREE;
                },
                key -> key);
    }

    /** A value whose {@code __getitem__} refuses from the {@code stops}-th position onward. */
    private static PyObject steps(int stops, PyRaise refusal) {
        return new Sequence(
                written("Steps", true, true),
                () -> THREE,
                key -> {
                    if (Numbers.asInteger(key).longValue() >= stops) {
                        throw refusal;
                    }
                    return key;
                });
    }

    /** The value of a class that writes {@code __index__}, which the narrow converter never asks. */
    private static PyObject indexAnswering(PyObject answer) {
        return valueOf(
                "Index", dict -> answers(dict, "__index__", answer));
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
     * The name in {@code builtins} is a type, and the type is the cursor it hands out.
     *
     * <p>{@code map}, {@code zip}, {@code filter} and {@code enumerate} are functions with a cursor type
     * behind them that no program can name; here the builtin <em>is</em> {@code PyReversed_Type}, so
     * {@code type(reversed)} is {@code type}, {@code isinstance(it, reversed)} is true and the dictionary has
     * the seven names of a type rather than the dozen of a function. Seven against the six a sequence cursor
     * files and the five a hash cursor does, and {@code dir()} twenty-eight with the same four names a
     * sequence cursor gains — {@code __reduce__} and {@code __doc__} being names {@code object} files
     * already. Twenty-eight is also what a sequence cursor answers, and for the same reason: the one name
     * more than a hash cursor is {@code __setstate__} and the one more than that is {@code __new__}, which
     * {@code dir()} does not count twice.
     */
    @Test
    void theBuiltinIsTheTypeItHandsOut() {
        assertThat(PyRuntime.builtins().get(PyStr.interned("reversed"))).isSameAs(PyType.REVERSED);
        assertThat(PyType.REVERSED.getType()).isSameAs(PyType.TYPE);
        assertThat(PyType.REVERSED.name()).isEqualTo("reversed");
        assertThat(PyType.REVERSED.filedName()).isEqualTo("reversed");
        assertThat(Slots.getAttr(PyType.REVERSED, "__module__")).isEqualTo(PyStr.of("builtins"));
        assertThat(Slots.getAttr(PyType.REVERSED, "__base__")).isSameAs(PyType.OBJECT);
        assertThat(PyType.REVERSED.mro()).containsExactly(PyType.REVERSED, PyType.OBJECT);

        assertThat(filed()).isEqualTo(ORDER);
        assertThat(TypeDict.declared(PyType.REVERSED).keys()).hasSize(7);
        assertThat(Dirs.classNames(PyType.REVERSED)).hasSize(28);
        assertThat(gained()).isEqualTo(OWN);

        // The cursor is what the call makes, and it is an instance of the name it was called through.
        PyObject value = builtin(SOURCE);
        assertThat(value.getType()).isSameAs(PyType.REVERSED);
        assertThat(PyType.REVERSED.instanceOf(value)).isTrue();
        assertThat(PyType.OBJECT.instanceOf(value)).isTrue();
        assertThat(value.iterator()).isSameAs(value);
        assertThat(Sequences.isSequence(value)).isFalse();
        assertThat(Slots.getAttr(value, "__doc__")).isEqualTo(PyStr.of(SENTENCE));

        // Four of the sixteen: the one this call makes, and the three it hands to the sources that answer.
        assertThat(builtin(PyList.of(ONE, TWO)).getType()).isSameAs(PyType.LIST_REVERSE_ITERATOR);
        assertThat(builtin(PyRange.of(BigInteger.ZERO, THREE_BI, ONE_BI)).getType())
                .isSameAs(PyType.RANGE_ITERATOR);
        assertThat(builtin(table()).getType()).isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(builtin(classDict()).getType()).isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
    }

    /** {@code {'a': 1, 'b': 2, 'c': 3}}, for the rows of the family that answer the special. */
    private static PyDict table() {
        PyDict filled = PyDict.empty();
        filled.put(PyStr.of("a"), ONE);
        filled.put(PyStr.of("b"), TWO);
        filled.put(PyStr.of("c"), THREE);
        return filled;
    }

    /** {@code int.__dict__}: the read-only view a class keeps, which answers as the table under it does. */
    private static PyObject classDict() {
        return Slots.getAttr(PyType.INT, "__dict__");
    }

    /**
     * One text, two reads: the sentence for {@code __doc__} and the table for {@code __text_signature__}.
     *
     * <p>{@code add_doc} cuts the sentence at {@code PyType_Ready} time and files it as the dictionary's tail
     * entry, and {@code type_get_text_signature} cuts the table out of the same {@code tp_doc} at the read —
     * so one stored text answers two reads here, in two objects rather than one: the entry is kept and the
     * read of {@code __doc__} is built from {@code tp_doc} every time it is asked, which is also why
     * {@code __text_signature__} is a name the book does not file at all. A cursor holds no dictionary of its
     * own ({@code tp_dictoffset} is zero), so {@code vars()} of a value is refused and {@code __doc__} is
     * read through the type: a value's text is the stored entry, and the kind's own read is a copy beside it.
     */
    @Test
    void oneTextAnswersTwoReads() {
        assertThat(PyType.REVERSED.docText()).isEqualTo(CLINIC);
        assertThat(Slots.getAttr(PyType.REVERSED, "__doc__")).isEqualTo(PyStr.of(SENTENCE));
        assertThat(entry("__doc__")).isEqualTo(PyStr.of(SENTENCE));

        // Two reads of one text, and only one of them is stored: add_doc files the cut sentence as the
        // dictionary's tail entry, where type_get_doc builds a fresh string from tp_doc at every ask.
        assertThat(entry("__doc__")).isSameAs(entry("__doc__"));
        assertThat(entry("__doc__")).isNotSameAs(Slots.getAttr(PyType.REVERSED, "__doc__"));
        assertThat(Slots.getAttr(PyType.REVERSED, "__doc__"))
                .isNotSameAs(Slots.getAttr(PyType.REVERSED, "__doc__"));
        assertThat(entry("__doc__").getType()).isSameAs(PyType.STR);
        assertThat(Slots.getAttr(PyType.REVERSED, "__text_signature__")).isEqualTo(PyStr.of(TABLE));
        assertThat(entry("__text_signature__")).isNull();

        // A value asks its own text through its type's dictionary rather than through the metatype's read,
        // so it is the stored entry that comes back — and the kind's read is a second object of one text.
        assertThat(Slots.getAttr(cursor(), "__doc__")).isSameAs(entry("__doc__"));
        assertThat(Slots.getAttr(cursor(), "__doc__"))
                .isNotSameAs(Slots.getAttr(PyType.REVERSED, "__doc__"));
        assertThat(Slots.getAttr(cursor().getType(), "__text_signature__")).isEqualTo(PyStr.of(TABLE));

        // The tail is a value rather than a descriptor, so it carries neither name nor table of its own.
        refuses(
                "AttributeError: 'str' object has no attribute '__qualname__'",
                () -> Slots.getAttr(entry("__doc__"), "__qualname__"));
        refuses(
                "AttributeError: 'str' object has no attribute '__objclass__'",
                () -> Slots.getAttr(entry("__doc__"), "__objclass__"));

        // No dictionary of its own, and nothing to write into one.
        refuses(
                "TypeError: vars() argument must have __dict__ attribute",
                () -> Slots.call(
                        PyRuntime.builtins().get(PyStr.interned("vars")), new PyObject[] {cursor()}, null));
        refuses(
                "AttributeError: 'reversed' object has no attribute '__dict__'",
                () -> Slots.getAttr(cursor(), "__dict__"));
        assertThat(entry("__dict__")).isNull();

        // A cursor of another kind, whose type says nothing at all, still answers the two sentences.
        assertThat(Slots.getAttr(PyType.LIST_REVERSE_ITERATOR, "__doc__")).isSameAs(NONE);
        assertThat(Slots.getAttr(PyType.LIST_REVERSE_ITERATOR, "__text_signature__")).isSameAs(NONE);
        assertThat(PyType.LIST_REVERSE_ITERATOR.docText()).isNull();
    }

    /**
     * Five descriptors and one value, in one order, out of two tables and one constructor.
     *
     * <p>{@code __new__} is the odd row: it is not in {@code reversediter_methods} but is the type's {@code
     * tp_new} copied out by {@code type_new}, so it is a {@code builtin_function_or_method} whose {@code
     * __self__} is the type rather than one of the {@link Members} rows. The two beside it are {@code
     * PyObject_SelfIter} and {@code reversed_next} copied out of the slot pointers, and only three names come
     * from the C method list — which is why {@code Members.tableOf} answers three where the dictionary holds
     * seven. The two slot copies are one kind with one difference of name, and the three table rows carry the
     * three sentences the file writes beside them, the same sentences the two other cursor families file.
     */
    @Test
    void fiveDescriptorsAndOneValue() {
        assertThat(Arrays.asList(Members.tableOf(PyType.REVERSED).names()))
                .containsExactlyElementsOf(METHODS);

        for (String name : METHODS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("method_descriptor");
            assertThat(entry(name)).as(name).isSameAs(Members.tableOf(PyType.REVERSED).describe(name));
            assertThat(Slots.getAttr(entry(name), "__objclass__")).as(name).isSameAs(PyType.REVERSED);
            assertThat(Slots.getAttr(entry(name), "__qualname__"))
                    .as(name)
                    .isEqualTo(PyStr.of("reversed." + name));
            assertThat(Slots.getAttr(entry(name), "__name__")).as(name).isEqualTo(PyStr.of(name));
        }
        assertThat(Slots.getAttr(entry("__length_hint__"), "__doc__")).isEqualTo(PyStr.of(LENGTH_HINT_DOC));
        assertThat(Slots.getAttr(entry("__reduce__"), "__doc__")).isEqualTo(PyStr.of(REDUCE_DOC));
        assertThat(Slots.getAttr(entry("__setstate__"), "__doc__")).isEqualTo(PyStr.of(SETSTATE_DOC));
        assertThat(Slots.getAttr(entry("__length_hint__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(entry("__reduce__"), "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(entry("__setstate__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, object, /)"));

        for (String name : SLOTS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("wrapper_descriptor");
            assertThat(Slots.getAttr(entry(name), "__objclass__")).as(name).isSameAs(PyType.REVERSED);
            assertThat(Slots.getAttr(entry(name), "__qualname__"))
                    .as(name)
                    .isEqualTo(PyStr.of("reversed." + name));
            assertThat(Slots.getAttr(entry(name), "__text_signature__"))
                    .as(name)
                    .isEqualTo(PyStr.of("($self, /)"));
            assertThat(Slots.getAttr(entry(name), "__doc__"))
                    .as(name)
                    .isEqualTo(PyStr.of(name.equals("__iter__") ? ITER_DOC : NEXT_DOC));
        }
        assertThat(entry("__iter__").getType()).isSameAs(entry("__next__").getType());

        // The constructor row, which no method list files and no value binds.
        assertThat(entry(CREATOR).getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(entry(CREATOR), "__doc__")).isEqualTo(PyStr.of(NEW_DOC));
        assertThat(Slots.getAttr(entry(CREATOR), "__qualname__")).isEqualTo(PyStr.of("reversed.__new__"));
        assertThat(Slots.getAttr(entry(CREATOR), "__text_signature__"))
                .isEqualTo(PyStr.of("($type, *args, **kwargs)"));
        assertThat(Slots.getAttr(PyType.REVERSED, CREATOR)).isSameAs(entry(CREATOR));
        assertThat(Slots.getAttr(Slots.getAttr(PyType.REVERSED, CREATOR), "__self__"))
                .isSameAs(PyType.REVERSED);
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__objclass__'",
                () -> Slots.getAttr(entry(CREATOR), "__objclass__"));

        // Names the family does not file: no length, no comparison, no second pickle.
        for (String name : List.of("__len__", "__reversed__", "__eq__", "__getnewargs__", "__reduce_ex__")) {
            assertThat(entry(name)).as(name).isNull();
        }
    }

    /**
     * Two spellings of one constructor, arguing in one set of words.
     *
     * <p>{@code reversed_vectorcall} asks {@code _PyArg_NoKwnames} <em>before</em> {@code
     * _PyArg_CheckPositional}, so a keyword is refused by name whatever the count says of it, and the count
     * is exact — one argument, never zero and never two. Through {@code __new__} the same two refusals come
     * back after the four gates of {@code tp_new_wrapper}, which is why {@code reversed.__new__(list, …)}
     * complains about the class it was handed before it ever counts. {@code object.__new__(reversed)} is the
     * one spelling that refuses without reaching the constructor at all: the type has a {@code tp_new} of its
     * own, and that is what makes it one of the two roads to the sentence {@code … is not safe}.
     */
    @Test
    void twoSpellingsOfOneConstructor() {
        assertThat(builtin(SOURCE).repr())
                .startsWith("<reversed object at 0x")
                .endsWith(">");
        assertThat(((PyTuple) reduce(cursor()).at(1)).at(0)).isSameAs(SOURCE);

        // The count, in the same words from both roads.
        for (String spelling : List.of("call", "new")) {
            String count = "TypeError: reversed expected 1 argument, got %s";
            if (spelling.equals("call")) {
                refuses(String.format(count, 0), () -> Slots.call(PyType.REVERSED, new PyObject[0], null));
                refuses(
                        String.format(count, 2),
                        () -> Slots.call(PyType.REVERSED, new PyObject[] {SOURCE, SOURCE}, null));
            } else {
                refuses(
                        String.format(count, 0),
                        () -> call(CREATOR, PyType.REVERSED));
                refuses(
                        String.format(count, 2),
                        () -> call(CREATOR, PyType.REVERSED, SOURCE, SOURCE));
            }
            // The keyword first, because the parameter is positional-only.
            refuses(
                    "TypeError: reversed() takes no keyword arguments",
                    () -> Slots.call(PyType.REVERSED, new PyObject[] {SOURCE}, SEQUENCE));
            refuses(
                    "TypeError: reversed() takes no keyword arguments",
                    () -> calledWith(SEQUENCE, CREATOR, PyType.REVERSED, SOURCE));
        }

        // The four gates of tp_new_wrapper, none of which the plain call passes through.
        refuses(
                "TypeError: reversed.__new__(): not enough arguments",
                () -> call(CREATOR));
        refuses(
                "TypeError: reversed.__new__(X): X is not a type object (str)",
                () -> call(CREATOR, TEXT));
        refuses(
                "TypeError: reversed.__new__(list): list is not a subtype of reversed",
                () -> call(CREATOR, PyType.LIST, SOURCE));
        refuses(
                "TypeError: object.__new__(reversed) is not safe, use reversed.__new__()",
                () -> Slots.call(
                        Slots.getAttr(PyType.OBJECT, CREATOR), new PyObject[] {PyType.REVERSED}, null));

        // A row read off a value still names the class it was asked through, and answers a cursor.
        PyObject value = cursor();
        assertThat(
                        ((PyObject) Slots.getAttr(value, CREATOR))
                                .call(new PyObject[] {PyType.REVERSED, SOURCE}, null)
                                .getType())
                .isSameAs(PyType.REVERSED);

        // No second spelling reaches a cursor a program cannot name: this one has a tp_new.
        assertThat(entry(CREATOR)).isNotNull();
        for (PyType other : List.of(
                PyType.LIST_ITERATOR, PyType.RANGE_ITERATOR, PyType.DICT_KEY_ITERATOR, PyType.SET_ITERATOR)) {
            assertThat(TypeDict.declared(other).get(PyStr.interned(CREATOR))).as(other.name()).isNull();
        }
    }

    /**
     * The three questions, in the order the constructor asks them.
     *
     * <p>The special is looked up on the type, so an instance dictionary writing {@code __reversed__} changes
     * nothing; a {@code None} in that position is read as a refusal rather than as an absence, which is why a
     * class that both counts and subscripts is still not reversible when it writes {@code __reversed__ =
     * None} — a thing an absence would not do to it. What the special hands back is returned without being
     * looked at, so the one builtin call of this family can hand back a value of a type it did not make.
     * {@code PySequence_Check} is the protocol and so refuses a class that counts, and only then does {@code
     * PySequence_Size} get asked, whose refusal is the one sentence in this case that names no cursor.
     */
    @Test
    void theSpecialIsAskedBeforeTheProtocolAndTheCount() {
        // The sources that answer for themselves, and the type each of them answers with.
        assertThat(builtin(PyList.of(ONE)).getType()).isSameAs(PyType.LIST_REVERSE_ITERATOR);
        assertThat(builtin(PyStr.of("ab")).getType()).isSameAs(PyType.REVERSED);
        assertThat(builtin(PyBytes.of(new byte[] {97})).getType()).isSameAs(PyType.REVERSED);
        assertThat(builtin(PyTuple.of(ONE)).getType()).isSameAs(PyType.REVERSED);
        assertThat(builtin(DictViews.keys(table())).getType()).isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(builtin(table()).getType()).isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);
        assertThat(builtin(classDict()).getType()).isSameAs(PyType.DICT_REVERSE_KEY_ITERATOR);

        // Handed back untouched: the answer is a list, and the call keeps it.
        PyObject handed = PyList.of(PyStr.of("not"), PyStr.of("a"), PyStr.of("cursor"));
        PyObject answered =
                valueOf("Answers", dict -> writes(dict, "__reversed__", null, (args, kwNames) -> handed));
        assertThat(builtin(answered)).isSameAs(handed);
        assertThat(builtin(answered).getType()).isSameAs(PyType.LIST);

        // A value written in the special's place is called anyway, and its uncallability is the refusal.
        refuses(
                "TypeError: 'list' object is not callable",
                () -> Reversed.over(valueOf("AsList", dict -> files(dict, "__reversed__", handed))));

        // A None written on a class that counts and subscripts is still a refusal, not an absence.
        assertThat(Sequences.isSequence(refusesSpecial(true))).isTrue();
        refuses("TypeError: 'Refuses' object is not reversible", () -> Reversed.over(refusesSpecial(true)));
        refuses("TypeError: 'Refuses' object is not reversible", () -> Reversed.over(refusesSpecial(false)));

        // The protocol before the count: a class that counts is not a sequence at all.
        refuses("TypeError: 'Counted' object is not reversible", () -> Reversed.over(countedOnly()));
        refuses(
                "TypeError: object of type 'Indexed' has no len()", () -> Reversed.over(indexedOnly()));
        refuses("ValueError: no count", () -> Reversed.over(lengthRefuses()));

        // The values that answer nothing and are nothing: type, not content, is what is asked.
        refuses("TypeError: 'int' object is not reversible", () -> Reversed.over(ONE));
        refuses("TypeError: 'NoneType' object is not reversible", () -> Reversed.over(NONE));
        refuses("TypeError: 'set' object is not reversible", () -> Reversed.over(PySet.of(ONE)));
        refuses(
                "TypeError: 'list_iterator' object is not reversible",
                () -> Reversed.over(PyList.of(ONE).iterator()));
        refuses("TypeError: 'reversed' object is not reversible", () -> Reversed.over(cursor()));

        // A special that complains is heard, and the refusal is its own rather than the call's.
        PyObject fails =
                valueOf(
                        "Fails",
                        dict ->
                                writes(
                                        dict,
                                        "__reversed__",
                                        null,
                                        (args, kwNames) -> {
                                            throw Slots.valueError("not now");
                                        }));
        refuses("ValueError: not now", () -> Reversed.over(fails));

        // The instance dictionary is never asked: a value that writes the special on itself is still
        // refused for the class it was made from, which files neither the special nor a subscript. Had
        // the write been read, the refusal would have been that the list is not callable.
        PyObject typed = valueOf("Written", dict -> {});
        Slots.setAttr(typed, "__reversed__", handed);
        refuses("TypeError: 'Written' object is not reversible", () -> Reversed.over(typed));
    }

    /**
     * The walk reads the source again at every step, which is the whole difference from a hash cursor.
     *
     * <p>A hash cursor copies the table's entries and so never asks the table again; this one keeps a
     * reference and subscripts it, so a source cut down between two steps is ended by the read rather than by
     * a count and the complaint it brings back is the source's own. Two of those complaints are the end of
     * the walk instead of an error — {@code IndexError} and {@code StopIteration} are cleared and the marker
     * put in their place — and anything else travels out unchanged, which is why a mapping that subscripts by
     * name carries a {@code KeyError} all the way through {@code list(reversed(x))}. The estimate is asked of
     * the live count too, so the same {@code __len__} that refuses the constructor refuses a step of nothing.
     */
    @Test
    void theWalkReadsTheSourceAtEveryStep() {
        PyObject ladder = cursor();
        assertThat(List.of(hint(ladder), nextOf(ladder).repr(), hint(ladder), nextOf(ladder).repr()))
                .isEqualTo(List.of(FOUR_BI, "40", THREE_BI, "30"));

        PyObject spent = cursor();
        drain(spent);
        assertThat(hint(spent)).isEqualTo(ZERO_BI);
        assertThat(reprs(walked(spent))).isEmpty();

        // Cut the source under the walk, and it is the read that complains rather than a count.
        Backed backed = new Backed();
        PyObject cut = Reversed.over(backed.value);
        assertThat(nextOf(cut).repr()).isEqualTo("3");
        backed.cut(1);
        assertThat(hint(cut)).isEqualTo(ZERO_BI);
        assertThat(reprs(walked(cut))).isEmpty();

        Backed shorted = new Backed();
        PyObject shortCut = Reversed.over(shorted.value);
        nextOf(shortCut);
        shorted.cut(2);
        assertThat(hint(shortCut)).isEqualTo(TWO_BI);
        assertThat(reprs(walked(shortCut))).isEqualTo(List.of("2", "1"));

        // The two refusals that end the walk, and the one that travels out of it.
        assertThat(reprs(walked(Reversed.over(steps(1, Slots.indexError("stop")))))).isEmpty();
        assertThat(reprs(walked(Reversed.over(steps(1, PyRaise.raise(ExceptionTypes.STOP_ITERATION))))))
                .isEmpty();
        refuses(
                "KeyError: 'nope'",
                () -> walked(Reversed.over(steps(1, Slots.keyError(PyStr.of("nope"))))));

        // The estimate and the restart each ask the count again, so a source that stops answering is heard
        // by them rather than by the walk — and neither ask is the end of the cursor.
        PyObject hinting = Reversed.over(countedThenRefuses(1));
        refuses("ValueError: no count", () -> hint(hinting));
        refuses("ValueError: no count", () -> setState(hinting, ONE));
        assertThat(nextOf(hinting).repr()).isEqualTo("2");
        assertThat(hint(Reversed.over(countedThenRefuses(2)))).isEqualTo(THREE_BI);

        // A source of nought is born with the marker a walk out leaves behind.
        PyObject empty = Reversed.over(PyTuple.EMPTY);
        assertThat(hint(empty)).isEqualTo(ZERO_BI);
        assertThat(reprs(walked(empty))).isEmpty();
        assertThat(reduce(empty).size()).isEqualTo(2);

        // Bytes read as numbers and text as characters, since the read is the source's own.
        assertThat(reprs(walked(Reversed.over(PyBytes.of(new byte[] {97, 98})))))
                .isEqualTo(List.of("98", "97"));
        assertThat(reprs(walked(Reversed.over(PyStr.of("abc")))))
                .isEqualTo(List.of("'c'", "'b'", "'a'"));

        // __iter__ answers self, and __next__ is the only way the walk moves.
        PyObject moving = cursor();
        assertThat(Slots.getAttr(moving, "__iter__")).isNotSameAs(entry("__iter__"));
        assertThat(moving.iterator()).isSameAs(moving);
        assertThat(nextOf(moving).repr()).isEqualTo("40");
        assertThat(((PyInt) call("__length_hint__", moving)).getValue()).isEqualTo(THREE_BI);
    }

    /**
     * Two payload shapes, and the type the first item of each names.
     *
     * <p>{@code "O(O)n"} while the source is held and {@code "O(())"} once a step has dropped it — and the
     * second is not a payload of two items but of two where the argument list itself holds the empty tuple,
     * so what being found again makes is a cursor over nothing with no state to jump into. The callable a
     * live payload names is the cursor's own type rather than {@code iter}, which fourteen of the other
     * fifteen kinds do: their payloads carry the part of the source still to come, while this one is
     * restarted by the number the state carries. The list's own reverse cursor is the fifteenth kind that
     * names a type, and the type it names is this one — beside an empty list rather than an empty tuple,
     * since it is the sequence it walked that comes back whatever its kind.
     */
    @Test
    void thePickleHasTwoShapesAndNamesItsOwnType() {
        PyTuple live = reduce(cursor());
        assertThat(live.size()).isEqualTo(3);
        assertThat(live.at(0)).isSameAs(PyType.REVERSED);
        assertThat(live.at(0)).isSameAs(PyRuntime.builtins().get(PyStr.interned("reversed")));
        assertThat(live.at(1).getType()).isSameAs(PyType.TUPLE);
        assertThat(((PyTuple) live.at(1)).size()).isEqualTo(1);
        assertThat(((PyTuple) live.at(1)).at(0)).isSameAs(SOURCE);
        assertThat(((PyInt) live.at(2)).getValue()).isEqualTo(THREE_BI);

        PyObject oneStep = cursor();
        nextOf(oneStep);
        assertThat(((PyInt) reduce(oneStep).at(2)).getValue()).isEqualTo(TWO_BI);

        // Both halves of the family that has nothing left to walk: the marker, and the empty source.
        for (PyObject dead : List.of(spent(), Reversed.over(PyTuple.EMPTY))) {
            PyTuple payload = reduce(dead);
            assertThat(payload.size()).isEqualTo(2);
            assertThat(payload.at(0)).isSameAs(PyType.REVERSED);
            assertThat(((PyTuple) payload.at(1)).size()).isEqualTo(1);
            assertThat(((PyTuple) payload.at(1)).at(0).getType()).isSameAs(PyType.TUPLE);
            assertThat(((PyTuple) ((PyTuple) payload.at(1)).at(0)).size()).isZero();
        }

        // The cursor that answers the special keeps the same shape with a different inside: it names the
        // same callable, because it is the same builtin, and hands back the empty list it walked.
        PyObject ofList = builtin(PyList.empty());
        assertThat(ofList.getType()).isSameAs(PyType.LIST_REVERSE_ITERATOR);
        assertThat(reduce(ofList).size()).isEqualTo(2);
        assertThat(reduce(ofList).at(0)).isSameAs(PyType.REVERSED);
        assertThat(((PyTuple) reduce(ofList).at(1)).at(0).getType()).isSameAs(PyType.LIST);

        // The sequence itself, not a copy of it, whatever it is.
        Backed backed = new Backed();
        PyObject ofBacked = Reversed.over(backed.value);
        nextOf(ofBacked);
        assertThat(((PyTuple) reduce(ofBacked).at(1)).at(0)).isSameAs(backed.value);
        assertThat(((PyInt) reduce(ofBacked).at(2)).getValue()).isEqualTo(ONE_BI);

        // The two halves put back together, which is a call of the type and then a state.
        PyTuple found = reduce(cursor());
        PyObject rebuilt = Slots.call(PyType.REVERSED, new PyObject[] {((PyTuple) found.at(1)).at(0)}, null);
        setState(rebuilt, found.at(2));
        assertThat(reprs(walked(rebuilt))).isEqualTo(List.of("40", "30", "20", "10"));
        PyObject mid = cursor();
        nextOf(mid);
        PyTuple halfway = reduce(mid);
        PyObject again =
                Slots.call(PyType.REVERSED, new PyObject[] {((PyTuple) halfway.at(1)).at(0)}, null);
        setState(again, halfway.at(2));
        assertThat(((PyInt) reduce(again).at(2)).getValue()).isEqualTo(TWO_BI);

        // A dead payload rebuilt is a cursor over nothing, and has no state to be given.
        PyObject dead = Slots.call(PyType.REVERSED, new PyObject[] {PyTuple.EMPTY}, null);
        assertThat(hint(dead)).isEqualTo(ZERO_BI);
        assertThat(reprs(walked(dead))).isEmpty();
    }

    /** The four-values cursor walked out to its marker, for the two payload shapes. */
    private static PyObject spent() {
        PyObject iterator = cursor();
        drain(iterator);
        return iterator;
    }

    /** Four steps take the whole of {@code SOURCE} and the fifth is the marker: walk it out here. */
    private static void drain(PyObject iterator) {
        for (int step = 0; step < 4; step++) {
            nextOf(iterator);
        }
        refuses("StopIteration: ", () -> nextOf(iterator));
    }

    /**
     * The restart converts first, guards second, and clips instead of complaining.
     *
     * <p>{@code PyLong_AsSsize_t} is the narrow converter: it takes an {@code int} and a bool, refuses an
     * {@code __index__} answerer in {@code an integer is required}, and overflows at sixty-four bits — and it
     * runs before the guard, which is why a spent cursor still refuses a float and then ignores an {@code
     * int}. The window is {@code [-1, n - 1]} with {@code n - 1} at the top of it the marker at the bottom, so
     * a number over the end restarts the walk whole and a number below it ends the cursor for good. An empty
     * source is born at {@code -1}, which no number put back.
     */
    @Test
    void theRestartConvertsThenGuardsThenClips() {
        assertThat(setState(cursor(), ZERO)).isSameAs(NONE);
        assertThat(jumped(cursor(), ONE)).isEqualTo(List.of(TWO_BI, List.of("20", "10")));
        assertThat(jumped(cursor(), THREE)).isEqualTo(List.of(FOUR_BI, List.of("40", "30", "20", "10")));
        assertThat(jumped(cursor(), NINE)).isEqualTo(List.of(FOUR_BI, List.of("40", "30", "20", "10")));
        assertThat(jumped(cursor(), TRUE)).isEqualTo(List.of(TWO_BI, List.of("20", "10")));
        assertThat(jumped(cursor(), MINUS_ONE)).isEqualTo(List.of(ZERO_BI, List.of()));
        assertThat(jumped(cursor(), MINUS_FIVE)).isEqualTo(List.of(ZERO_BI, List.of()));
        assertThat(jumped(cursor(), PyInt.of(BigInteger.valueOf(Long.MIN_VALUE))))
                .isEqualTo(List.of(ZERO_BI, List.of()));

        // The conversion is the narrow one, so the number a class answers with is never asked.
        for (PyObject refused : List.of(REAL, TEXT, NONE, PyList.of(ONE), indexAnswering(ONE))) {
            refuses("TypeError: an integer is required", () -> setState(cursor(), refused));
        }
        for (PyObject tooWide :
                List.of(WIDE, WIDER, PyInt.of(two(63).negate().subtract(ONE_BI)))) {
            refuses(
                    "OverflowError: Python int too large to convert to C ssize_t",
                    () -> setState(cursor(), tooWide));
        }

        // A guard reads the marker before it measures, so a spent cursor never asks its source again.
        PyObject spent = spent();
        refuses("TypeError: an integer is required", () -> setState(spent, REAL));
        refuses("OverflowError: Python int too large to convert to C ssize_t", () -> setState(spent, WIDE));
        assertThat(List.of(setState(spent, TWO), hint(spent), reprs(walked(spent))))
                .isEqualTo(List.of(NONE, ZERO_BI, List.of()));
        assertThat(reduce(spent).size()).isEqualTo(2);

        // A source of nought is born at the marker, so no number puts it back.
        PyObject empty = Reversed.over(PyTuple.EMPTY);
        assertThat(jumped(empty, ZERO)).isEqualTo(List.of(ZERO_BI, List.of()));

        // A source cut to nothing under a live cursor is measured at the guard, not at the walk.
        Backed backed = new Backed();
        PyObject live = Reversed.over(backed.value);
        nextOf(live);
        backed.cut(0);
        assertThat(jumped(live, TWO)).isEqualTo(List.of(ZERO_BI, List.of()));

        // Arity, in the row's own words: one argument, no keyword, and the row is not a bound method.
        refuses(
                "TypeError: reversed.__setstate__() takes exactly one argument (0 given)",
                () -> call("__setstate__", cursor()));
        refuses(
                "TypeError: reversed.__setstate__() takes exactly one argument (2 given)",
                () -> call("__setstate__", cursor(), ONE, TWO));
        refuses(
                "TypeError: reversed.__setstate__() takes no keyword arguments",
                () -> calledWith(X, "__setstate__", cursor(), ONE));
        for (String name : BARE) {
            refuses(
                    "TypeError: unbound method reversed." + name + "() needs an argument",
                    () -> call(name));
            refuses(
                    "TypeError: reversed." + name + "() takes no arguments (1 given)",
                    () -> call(name, cursor(), ONE));
            refuses(
                    "TypeError: reversed." + name + "() takes no keyword arguments",
                    () -> calledWith(X, name, cursor(), ONE));
        }
    }

    /**
     * A row of this cursor refuses a cursor of another kind, in two different sentences.
     *
     * <p>{@code wrapperdescr_call} counts its receiver and says {@code requires a 'reversed' object but
     * received a 'list_iterator'}, while {@code descr_check} says {@code descriptor 'X' for 'reversed'
     * objects doesn't apply to a 'Y' object} — and both test the value's own type rather than an {@code
     * __instancecheck__}. The receiver comes before the count on the two slot rows, so {@code
     * vars(reversed)['__next__'](iter([]), 1)} complains about the receiver and not about the two arguments;
     * on the three table rows the receiver is checked first too, and a good receiver with an extra argument
     * is the arity complaint instead.
     */
    @Test
    void eachRowRefusesACursorOfAnotherKind() {
        PyObject own = cursor();
        PyObject other = PyList.of(ONE).iterator();
        PyObject foreign = PyType.LIST_REVERSE_ITERATOR;

        for (String name : SLOTS) {
            String sentence =
                    "TypeError: descriptor '" + name + "' of 'reversed' object needs an argument";
            refuses(sentence, () -> call(name));
            refuses(
                    "TypeError: descriptor '" + name + "' requires a 'reversed' object but received a 'int'",
                    () -> call(name, ZERO));
            refuses(
                    "TypeError: descriptor '" + name + "' requires a 'reversed' object but received a"
                            + " 'list_iterator'",
                    () -> call(name, other));
            refuses("TypeError: expected 0 arguments, got 1", () -> call(name, own, ONE));
            refuses(
                    "TypeError: wrapper " + name + "() takes no keyword arguments",
                    () -> calledWith(X, name, own, ONE));
        }
        assertThat(call("__iter__", own)).isSameAs(own);
        assertThat(call("__next__", own).repr()).isEqualTo(nextOf(cursor()).repr());

        for (String name : BARE) {
            refuses(
                    "TypeError: descriptor '" + name + "' for 'reversed' objects"
                            + " doesn't apply to a 'int' object",
                    () -> call(name, ZERO));
            refuses(
                    "TypeError: descriptor '" + name + "' for 'reversed' objects"
                            + " doesn't apply to a 'list_iterator' object",
                    () -> call(name, other));
        }
        refuses(
                "TypeError: descriptor '__setstate__' for 'reversed' objects"
                        + " doesn't apply to a 'tuple' object",
                () -> call("__setstate__", SOURCE, ONE));

        // The kind itself is no receiver: a row asked for a type complains about the type.
        refuses(
                "TypeError: descriptor '__next__' requires a 'reversed' object but received a 'type'",
                () -> call("__next__", foreign));
        refuses(
                "TypeError: descriptor '__reduce__' for 'reversed' objects"
                        + " doesn't apply to a 'type' object",
                () -> call("__reduce__", foreign));

        // A bound read is of two kinds, and both move the same cursor the unbound row would.
        PyObject bound = cursor();
        assertThat(Slots.getAttr(bound, "__next__").getType().name()).isEqualTo("method-wrapper");
        assertThat(Slots.getAttr(bound, "__length_hint__").getType().name())
                .isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(bound, "__length_hint__")).isNotSameAs(Slots.getAttr(bound, "__length_hint__"));
        assertThat(Slots.getAttr(bound, "__reduce__").call(new PyObject[0], null).getType())
                .isSameAs(PyType.TUPLE);
        assertThat(Slots.getAttr(PyType.REVERSED, "__reduce__")).isSameAs(entry("__reduce__"));
        for (String name : List.of("__iter__", "__next__")) {
            assertThat(Slots.call(
                            Slots.getAttr(entry(name), "__get__"),
                            new PyObject[] {NONE, PyType.REVERSED},
                            null))
                    .as(name)
                    .isSameAs(entry(name));
        }
    }
}
