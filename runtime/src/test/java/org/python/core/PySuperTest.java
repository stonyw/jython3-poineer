package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The {@code super} proxy as an object, measured against CPython 3.14.8.
 *
 * <p>The corpus script reads super the way Python does — from inside a method, through the bytecode that
 * builds the proxy from the frame. This is the half a script cannot reach: what the constructor accepts
 * and refuses, which class a read starts at, what a descriptor is handed when it answers, and what the
 * proxy keeps of itself. The classes here are built by {@code type(name, bases, dict)} and their members
 * are the Java-written kinds of {@link DescriptorsTest}, so every assertion is about the proxy rather
 * than about the class system that supplies it.
 */
class PySuperTest {

    /** {@code type(name, bases, {members})}. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType)
                PyType.TYPE.call(
                        new PyObject[] {
                            PyStr.of(name), PyTuple.of(bases), dict
                        },
                        null);
    }

    /** {@code super(C)}, {@code super(C, x)} — the two forms a script reaches by calling the type. */
    private static PyObject proxy(PyObject... arguments) {
        return PyType.SUPER.call(arguments, null);
    }

    /**
     * A classmethod that answers with the class it was bound to.
     *
     * <p>The label is which class <em>declared</em> it, so an answer of {@code Loud:Quiet} says the read
     * started after the class the proxy was given and the descriptor ran for the value the proxy holds.
     */
    private static PyObject classMethod(String declared) {
        return new PyClassMethod(
                new PyNativeFunction(
                        "kind",
                        (args, kwNames) ->
                                PyStr.of(declared + ":" + ((PyType) args[0]).name())));
    }

    /** A property whose getter names the value it was asked about, and its type. */
    private static PyObject propertyNamed(String name) {
        return new PyProperty(
                new PyNativeFunction(
                        name,
                        (args, kwNames) ->
                                PyStr.of(
                                        name + " of "
                                                + (args[0] instanceof PyType owner
                                                        ? owner.name()
                                                        : args[0].getType().name()))),
                PyNone.INSTANCE,
                PyNone.INSTANCE,
                PyNone.INSTANCE);
    }

    private static String text(PyObject value) {
        return ((PyStr) value).getValue();
    }

    /** {@code TypeError}, {@code RuntimeError} and {@code AttributeError} in CPython's own words. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** A chain of three: the smallest hierarchy in which "start after this class" has an answer. */
    private static final class Hierarchy {
        private final PyType speak = type("Speak", new PyType[0], "kind", classMethod("Speak"));
        private final PyType loud = type("Loud", new PyType[] {speak}, "kind", classMethod("Loud"));
        private final PyType quiet = type("Quiet", new PyType[] {loud}, "kind", classMethod("Quiet"));
        private final PyObject leaf = Slots.call(quiet, new PyObject[0], null);
    }

    /**
     * The walk begins at the class after the one the proxy was given, whichever class that is.
     *
     * <p>Three reads of the same value's order, starting at three different points: {@code super(Quiet,
     * leaf)} cannot see {@code Quiet} at all and so lands on {@code Loud}, {@code super(Loud, leaf)}
     * passes both and finds {@code Speak}, and {@code super(Speak, leaf)} has nothing left to try —
     * only {@code object} follows, and a name it does not hold is refused the same way a name no class
     * holds is.
     */
    @Test
    void aReadStartsAfterTheClassTheProxyWasGiven() {
        Hierarchy hierarchy = new Hierarchy();

        assertThat(text(Slots.call(
                Slots.getAttr(proxy(hierarchy.quiet, hierarchy.leaf), "kind"),
                new PyObject[0],
                null))).isEqualTo("Loud:Quiet");
        assertThat(text(Slots.call(
                Slots.getAttr(proxy(hierarchy.loud, hierarchy.leaf), "kind"),
                new PyObject[0],
                null))).isEqualTo("Speak:Quiet");

        refuses(
                "AttributeError: 'super' object has no attribute 'kind'",
                () -> Slots.getAttr(proxy(hierarchy.speak, hierarchy.leaf), "kind"));
    }

    /**
     * The class form walks that class's order, and binds to that class.
     *
     * <p>{@code super(Loud, Quiet)} is the read a subclass's own classmethod makes: the second argument is
     * a class, so the order is the class's linearization and the class handed to {@code __get__} is the
     * class itself, not one of its values.
     */
    @Test
    void aClassAsTheSecondArgumentIsWalkedAsTheValueWouldBe() {
        Hierarchy hierarchy = new Hierarchy();

        assertThat(text(Slots.call(
                Slots.getAttr(proxy(hierarchy.loud, hierarchy.quiet), "kind"), new PyObject[0], null)))
                .isEqualTo("Speak:Quiet");
        assertThat(Slots.getAttr(proxy(hierarchy.loud, hierarchy.quiet), "__self__"))
                .isSameAs(hierarchy.quiet);
        assertThat(Slots.getAttr(proxy(hierarchy.loud, hierarchy.quiet), "__self_class__"))
                .isSameAs(hierarchy.quiet);
    }

    /** The protocol still runs at the far end of the walk, for the proxy's own value. */
    @Test
    void aMemberReachedThroughSuperRunsItsDescriptorForTheValue() {
        PyType speak =
                type(
                        "Speak",
                        new PyType[0],
                        "span",
                        propertyNamed("span"),
                        "plain",
                        new PyStaticMethod(new PyNativeFunction("plain", (a, k) -> PyStr.of("plain"))));
        PyType loud = type("Loud", new PyType[] {speak});
        PyObject leaf = Slots.call(loud, new PyObject[0], null);

        assertThat(text(Slots.getAttr(proxy(loud, leaf), "span"))).isEqualTo("span of Loud");
        // A staticmethod contributes no binding of its own, so the function is the member itself.
        assertThat(Slots.getAttr(proxy(loud, leaf), "plain").getType().name())
                .isEqualTo("builtin_function_or_method");
    }

    /**
     * What the constructor takes, and the three ways it refuses.
     *
     * <p>The count message is CPython's legacy one, naming itself bare and counting positionals; the
     * keyword refusal comes first because {@code super} has no keyword parameters at all. Both the value
     * and the class form are checked against the class, and each says which of the two it was handed —
     * {@code instance of Speak} or {@code type Speak} — which is why the two refusals are separate
     * assertions rather than one message with two arguments. The empty call is the odd one out: too many
     * arguments is a {@code TypeError}, but a call with no arguments and no frame to ask about them is a
     * {@code RuntimeError}, and here there is no frame at all.
     */
    @Test
    void theCallThatBuildsAProxyCountsItsArgumentsInCPythonsWords() {
        Hierarchy hierarchy = new Hierarchy();
        PyObject leaf = hierarchy.leaf;

        refuses(
                "RuntimeError: super(): no arguments",
                () -> proxy());
        refuses(
                "TypeError: super() expected at most 2 arguments, got 3",
                () -> proxy(hierarchy.loud, leaf, leaf));
        refuses(
                "TypeError: super() takes no keyword arguments",
                () -> PyType.SUPER.call(
                        new PyObject[] {hierarchy.speak}, PyTuple.of(PyStr.of("type"))));
        refuses(
                "TypeError: super() argument 1 must be a type, not int",
                () -> proxy(PyInt.of(1)));
        refuses(
                "TypeError: super() argument 1 must be a type, not int",
                () -> proxy(PyInt.of(1), leaf));
        refuses(
                "TypeError: super(type, obj): obj (instance of Speak) is not an instance or subtype of type (Quiet).",
                () -> proxy(hierarchy.quiet, Slots.call(hierarchy.speak, new PyObject[0], null)));
        refuses(
                "TypeError: super(type, obj): obj (type Speak) is not an instance or subtype of type (Quiet).",
                () -> proxy(hierarchy.quiet, hierarchy.speak));
    }

    /**
     * A built-in type is walked too, because its members answer the same question in a table.
     *
     * <p>{@code bool} is the one order in Python's own hierarchy that passes a built-in type on the way
     * to another: {@code super(bool, True).bit_length} is the walk leaving {@code bool} behind and
     * finding what {@code int} declares, bound to the bool it was given. Nothing follows {@code int} but
     * {@code object}, and a name {@code object} does not hold is refused here for the same reason
     * CPython refuses it.
     */
    @Test
    void aBuiltInMembersTableIsPartOfTheOrderLikeAnyClassDictionary() {
        PyObject ofBool = proxy(PyType.BOOL, PyBool.TRUE);

        assertThat(Slots.call(Slots.getAttr(ofBool, "bit_length"), new PyObject[0], null).str())
                .isEqualTo("1");
        assertThat(Slots.getAttr(ofBool, "bit_length").getAttr("__self__")).isSameAs(PyBool.TRUE);

        refuses(
                "AttributeError: 'super' object has no attribute 'bit_length'",
                () -> Slots.getAttr(proxy(PyType.INT, PyInt.of(3)), "bit_length"));
    }

    /**
     * A proxy writes nothing, and is only ever itself.
     *
     * <p>Both refusals are CPython's: the three names super owns are read-only getsets, so they are
     * refused by name — {@code readonly attribute} and nothing else — while any other name falls through
     * to the generic complaint about a value with no dictionary to put it in. Two proxies built from the
     * same class and value are not equal, because super has no comparison of its own and inherits
     * identity.
     */
    @Test
    void aProxyWritesNothingAndSharesNothing() {
        Hierarchy hierarchy = new Hierarchy();
        PyObject proxy = proxy(hierarchy.quiet, hierarchy.leaf);
        PyObject other = proxy(hierarchy.quiet, hierarchy.leaf);

        assertThat(Slots.compareTrue(CompareOp.EQ, proxy, proxy)).isTrue();
        assertThat(Slots.compareTrue(CompareOp.EQ, proxy, other)).isFalse();
        assertThat(Slots.truth(proxy)).isTrue();
        assertThat(proxy.repr()).isEqualTo("<super: <class 'Quiet'>, <Quiet object>>");
        assertThat(proxy.getType()).isSameAs(PyType.SUPER);

        refuses(
                "AttributeError: 'super' object has no attribute 'kind' and no __dict__ for setting new attributes",
                () -> Slots.setAttr(proxy, "kind", PyInt.of(1)));
        refuses(
                "AttributeError: 'super' object has no attribute 'kind' and no __dict__ for setting new attributes",
                () -> Slots.delAttr(proxy, "kind"));
        refuses(
                "AttributeError: readonly attribute", () -> Slots.setAttr(proxy, "__self__", PyInt.of(1)));
        refuses(
                "AttributeError: readonly attribute", () -> Slots.delAttr(proxy, "__thisclass__"));
        refuses(
                "AttributeError: 'super' object has no attribute 'added'",
                () -> Slots.getAttr(proxy, "added"));
    }

    /**
     * {@code super(C)} holds a class and no value, so it can walk no order at all.
     *
     * <p>That is the difference from the two-argument form even though both are built by the same call:
     * with nothing to measure the walk against, every member is refused, and the three names report an
     * absent value as {@code None} while the repr says {@code NULL}. Naming {@code None} as the value is
     * the third way to that proxy, not a refusal: CPython gives {@code super(C, None)} the same
     * initializer as {@code super(C)} instead of asking whether {@code NoneType} is a subtype.
     */
    @Test
    void aProxyWithNoValueReadsNoMember() {
        Hierarchy hierarchy = new Hierarchy();
        PyObject empty = proxy(hierarchy.loud);
        PyObject fromNone = proxy(hierarchy.loud, PyNone.INSTANCE);

        assertThat(Slots.getAttr(empty, "__thisclass__")).isSameAs(hierarchy.loud);
        assertThat(Slots.getAttr(empty, "__self__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(empty, "__self_class__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(empty, "__class__")).isSameAs(PyType.SUPER);
        assertThat(empty.repr()).isEqualTo("<super: <class 'Loud'>, NULL>");
        assertThat(fromNone.repr()).isEqualTo(empty.repr());
        assertThat(Slots.getAttr(fromNone, "__self_class__")).isSameAs(PyNone.INSTANCE);

        refuses(
                "AttributeError: 'super' object has no attribute 'kind'",
                () -> Slots.getAttr(empty, "kind"));
        refuses("TypeError: 'super' object is not callable", () -> Slots.call(empty, new PyObject[0], null));
    }
}
