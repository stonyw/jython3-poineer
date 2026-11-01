package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The question a built-in dictionary asks of the value one of its rows is read through, measured against
 * CPython 3.14.8.
 *
 * <p>{@code descr_check} (Objects/descrobject.c:80) is one function reached from four getters — {@code
 * method_get} ({@code :138}), {@code member_get} ({@code :163}), {@code getset_get} ({@code :184}) and
 * {@code wrapperdescr_get} ({@code :204}) — and it looks at exactly one of the two arguments those getters
 * were handed. So the class a read is addressed to never enters it, a class method that binds to a class
 * never asks it, and a read through the class itself is answered before it. Three different Java objects
 * own the answer here, because a row of a built-in type is a {@link TypeDict.Member}, a row of a method
 * table is a {@link PyMethodDescriptor} and a field is a {@link PyMemberDescriptor}; the sentence they
 * each have to produce names the row by its table name and both classes by the name a message quotes.
 */
class DescriptorReceiverTest {

    private static final PyObject TEXT = PyStr.of("text");
    private static final PyObject BYTES = PyBytes.of(new byte[] {'a', 'b'});

    /** A slot copied into {@code bytes} and a method it files for itself, refused the same way. */
    @Test
    void aRowRefusesAValueOfAnotherClass() {
        refuses(
                "TypeError: descriptor '__len__' for 'bytes' objects doesn't apply to a 'str' object",
                () -> read(PyType.BYTES, "__len__", TEXT));
        refuses(
                "TypeError: descriptor '__iter__' for 'bytes' objects doesn't apply to a 'str' object",
                () -> read(PyType.BYTES, "__iter__", TEXT));
        refuses(
                "TypeError: descriptor '__eq__' for 'bytes' objects doesn't apply to a 'int' object",
                () -> read(PyType.BYTES, "__eq__", PyInt.of(3)));
        refuses(
                "TypeError: descriptor 'count' for 'bytes' objects doesn't apply to a 'str' object",
                () -> read(PyType.BYTES, "count", TEXT));
    }

    /** The rows the two range cursors file, which are dictionaries like any other. */
    @Test
    void theRowsACursorFilesForItselfRefuseTheSameWay() {
        refuses(
                "TypeError: descriptor '__next__' for 'range_iterator' objects doesn't apply to a 'str'"
                        + " object",
                () -> read(PyType.RANGE_ITERATOR, "__next__", TEXT));
        refuses(
                "TypeError: descriptor '__iter__' for 'longrange_iterator' objects doesn't apply to a"
                        + " 'int' object",
                () -> read(PyType.LONG_RANGE_ITERATOR, "__iter__", PyInt.of(3)));
        refuses(
                "TypeError: descriptor '__length_hint__' for 'range_iterator' objects doesn't apply to a"
                        + " 'str' object",
                () -> read(PyType.RANGE_ITERATOR, "__length_hint__", TEXT));
    }

    /**
     * A class is not an instance of itself except for {@code type}, which is.
     *
     * <p>{@code bytes.__len__.__get__(bytes)} is refused and the receiver's type named as {@code type},
     * while {@code type.__subclasses__.__get__(type)} binds — {@code PyObject_TypeCheck} walks the value's
     * own MRO, and the type of a class is {@code type}.
     */
    @Test
    void aClassHandedAsItsOwnReceiverIsRefusedForBeingAType() {
        refuses(
                "TypeError: descriptor '__len__' for 'bytes' objects doesn't apply to a 'type' object",
                () -> read(PyType.BYTES, "__len__", PyType.BYTES));
        PyObject bound = read(PyType.TYPE, "__subclasses__", PyType.TYPE);
        assertThat(bound.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(PyType.TYPE);
    }

    /**
     * The second argument of {@code __get__} is the one {@code descr_check} never looks at.
     *
     * <p>An owner that matches the row does not rescue a foreign value, and an owner that contradicts it
     * does not spoil a value of the right class: the binding is the same method either way, and it points
     * at the value rather than at the class the read named.
     */
    @Test
    void theClassTheReadWasAddressedToIsNotTheOneChecked() {
        refuses(
                "TypeError: descriptor '__len__' for 'bytes' objects doesn't apply to a 'str' object",
                () -> read(PyType.BYTES, "__len__", TEXT, PyType.BYTES));
        PyObject throughAWrongOwner = read(PyType.BYTES, "__len__", BYTES, PyType.INT);
        assertThat(Slots.getAttr(throughAWrongOwner, "__self__")).isSameAs(BYTES);
        assertThat(throughAWrongOwner.getType().name()).isEqualTo("method-wrapper");
    }

    /** A row of {@code object} is an instance test every value passes, a class included. */
    @Test
    void aRowOfTheClassEverythingIsAnInstanceOfBindsToAnything() {
        for (PyObject value :
                List.of(TEXT, PyInt.of(3), PyNone.INSTANCE, PyType.BYTES, PyType.OBJECT)) {
            for (String name : List.of("__format__", "__sizeof__", "__dir__", "__reduce__")) {
                PyObject bound = read(PyType.OBJECT, name, value);
                assertThat(bound.getType().name()).isEqualTo("builtin_function_or_method");
                assertThat(Slots.getAttr(bound, "__self__")).isSameAs(value);
            }
        }
    }

    /** A read through the class hands back the row, which is the answer {@code descr_check} never sees. */
    @Test
    void aReadThroughTheClassIsTheRowItself() {
        PyObject row = row(PyType.BYTES, "__len__");

        assertThat(Descriptors.get(row, null, PyType.BYTES)).isSameAs(row);
        assertThat(Slots.getAttr(PyType.BYTES, "__len__")).isSameAs(row);
        assertThat(Slots.getAttr(PyType.MAPPING_PROXY, "get"))
                .isSameAs(row(PyType.MAPPING_PROXY, "get"));
    }

    /**
     * A subtype is an instance of the class that files the row, and the row it binds is the base's.
     *
     * <p>{@code bool} declares nothing of its own here, so {@code vars(int)['__and__']} binds to {@code
     * True} and still reports {@code int} as the class it was filed by.
     */
    @Test
    void aSubclassPassesTheTestItsBaseFilesTheRowAgainst() {
        PyObject row = row(PyType.INT, "__and__");
        PyObject bound = Descriptors.get(row, PyBool.TRUE, PyType.BOOL);

        assertThat(bound.getType().name()).isEqualTo("method-wrapper");
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(PyBool.TRUE);
        assertThat(Slots.getAttr(row, "__objclass__")).isSameAs(PyType.INT);
    }

    /**
     * The two shapes that bind to something other than the value never run {@code descr_check} on it.
     *
     * <p>{@code classmethod_get} ({@code :95}) throws the value away and keeps a class: with no second
     * argument it substitutes the value's own type, so {@code dict.fromkeys.__get__('text')} is refused for
     * the type it filled in rather than for the value it ignored, and a {@code str} handed with {@code dict}
     * as the class binds. {@code __new__} is a plain function with no {@code __get__} anywhere on its way,
     * so the read of it is the function itself.
     */
    @Test
    void aClassMethodAndNewBindWithoutTestingTheValue() {
        refuses(
                "TypeError: descriptor 'fromkeys' requires a subtype of 'dict' but received 'str'",
                () -> read(PyType.DICT, "fromkeys", TEXT));
        PyObject bound = Descriptors.get(row(PyType.DICT, "fromkeys"), TEXT, PyType.DICT);

        assertThat(bound.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(PyType.DICT);
        assertThat(Descriptors.get(row(PyType.INT, "__new__"), TEXT, PyType.INT))
                .isSameAs(row(PyType.INT, "__new__"));
    }

    /**
     * The argument list is settled first, and its refusals name nothing but the count.
     *
     * <p>All four come out of {@code wrap_descr_get} before {@code descr_check} is reached, so a foreign
     * value handed with three arguments is refused for the third one and not for being a {@code str}, and
     * no receiver at all is the invalid pair rather than a type test on {@code None}.
     */
    @Test
    void theArgumentRulesComeBeforeTheReceiverIsAsked() {
        PyObject getter = Slots.getAttr(row(PyType.BYTES, "__len__"), "__get__");

        refuses(
                "TypeError: __get__ expected at least 1 argument, got 0",
                () -> Slots.call(getter, new PyObject[0], null));
        refuses(
                "TypeError: __get__(None, None) is invalid",
                () -> Slots.call(getter, new PyObject[] {PyNone.INSTANCE}, null));
        refuses(
                "TypeError: __get__ expected at most 2 arguments, got 3",
                () ->
                        Slots.call(
                                getter,
                                new PyObject[] {TEXT, PyType.BYTES, PyInt.of(1)},
                                null));
        refuses(
                "TypeError: wrapper __get__() takes no keyword arguments",
                () ->
                        Slots.call(
                                getter,
                                new PyObject[] {TEXT},
                                PyTuple.of(PyStr.interned("obj"))));
    }

    /**
     * The call side asks the same question of its first argument, after it has counted.
     *
     * <p>{@code methoddescr_call} ({@code :280}) reaches the one {@code descr_check} after refusing an
     * empty argument list, which is the order the reference keeps for the row read unbound: {@code
     * object.__dir__()} has no receiver to test and says so, and {@code mappingproxy.get(3, 'x')} does.
     */
    @Test
    void anUnboundCallAsksTheSameQuestionOfTheArgumentItTakesAsItsReceiver() {
        refuses(
                "TypeError: unbound method object.__dir__() needs an argument",
                () -> Slots.call(row(PyType.OBJECT, "__dir__"), new PyObject[0], null));
        refuses(
                "TypeError: descriptor 'get' for 'mappingproxy' objects doesn't apply to a 'int' object",
                () ->
                        Slots.call(
                                row(PyType.MAPPING_PROXY, "get"),
                                new PyObject[] {PyInt.of(3), TEXT},
                                null));
        assertThat(
                        Slots.call(
                                row(PyType.MAPPING_PROXY, "get"),
                                new PyObject[] {Slots.getAttr(PyType.OBJECT, "__dict__"), TEXT},
                                null))
                .isSameAs(PyNone.INSTANCE);
    }

    /**
     * The four kinds of row answer with one sentence.
     *
     * <p>A slot copied into a dictionary, a method a table files, a field of a fixed layout and a getset
     * pair are four different Java objects, and each names its own class the way a message quotes it: {@code
     * sys.version_info} for the named sequence rather than the tail {@code __name__} answers with.
     */
    @Test
    void everyFamilyOfRowSaysTheSameSentence() {
        refuses(
                "TypeError: descriptor '__len__' for 'bytes' objects doesn't apply to a 'str' object",
                () -> read(PyType.BYTES, "__len__", TEXT));
        refuses(
                "TypeError: descriptor 'upper' for 'str' objects doesn't apply to a 'int' object",
                () -> read(PyType.STR, "upper", PyInt.of(5)));
        refuses(
                "TypeError: descriptor 'major' for 'sys.version_info' objects doesn't apply to a 'str'"
                        + " object",
                () -> read(PyType.VERSION_INFO, "major", TEXT));
        refuses(
                "TypeError: descriptor 'args' for 'BaseException' objects doesn't apply to a 'str'"
                        + " object",
                () -> read(ExceptionTypes.BASE_EXCEPTION, "args", TEXT));
    }

    /** {@code vars(T)[name]} — the row as the dictionary holds it, before anything binds it. */
    private static PyObject row(PyType owner, String name) {
        return TypeDict.declared(owner).get(PyStr.interned(name));
    }

    /** {@code vars(T)[name].__get__(value)}, with no class named. */
    private static PyObject read(PyType owner, String name, PyObject value) {
        return Descriptors.get(row(owner, name), value, null);
    }

    /** {@code vars(T)[name].__get__(value, owner)}. */
    private static PyObject read(PyType owner, String name, PyObject value, PyObject ownerNamed) {
        return Descriptors.get(row(owner, name), value, ownerNamed);
    }

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }
}
