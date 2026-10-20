package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The three descriptor builtins as objects, measured against CPython 3.14.8.
 *
 * <p>The corpus script measures the protocol they take part in: which half of a lookup runs, in what
 * order, and against what. This is the other surface — the arguments each builtin accepts, the names it
 * hands out for itself, and the refusals it words when a call gives none of that. The two divide the way
 * CPython does: a script reads {@code property} through a class and its values, and only a Java caller
 * asks the wrapper directly, the way {@code sorted(words, key=str.lower)} asks a method.
 */
class DescriptorsTest {

    /** {@code type(name, (), dict)}: members placed by the same code that calls {@code __set_name__}. */
    private static PyType type(String name, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType) PyType.TYPE.call(new PyObject[] {PyStr.of(name), PyTuple.of(), dict}, null);
    }

    /** {@code class Loud(Scores)}, which is how a classmethod's owner is shown to come from the read. */
    private static PyType subtype(String name, PyType base) {
        return (PyType)
                PyType.TYPE.call(
                        new PyObject[] {PyStr.of(name), PyTuple.of(base), PyDict.empty()}, null);
    }

    /** {@code property(fget, fset, fdel, doc)}, with the absent arguments left as {@code None}. */
    private static PyObject property(PyObject getter, PyObject setter, PyObject deleter, PyObject doc) {
        return PyType.PROPERTY.call(
                new PyObject[] {orNone(getter), orNone(setter), orNone(deleter), orNone(doc)}, null);
    }

    private static PyObject orNone(PyObject value) {
        return value == null ? PyNone.INSTANCE : value;
    }

    /** A function Java wrote, standing in for the one Python would have named in the class body. */
    private static PyNativeFunction function(String name, PyObject answer) {
        return new PyNativeFunction(name, (args, kwNames) -> answer);
    }

    /** {@code TypeError} and {@code AttributeError} in CPython's own words, which the runtime repeats. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** {@code property(f)}, {@code classmethod(f)} and {@code staticmethod(f)} each take exactly one. */
    @Test
    void theThreeBuiltinsCountTheirArgumentsTheirOwnWays() {
        PyObject body = function("f", PyNone.INSTANCE);
        PyTuple named = PyTuple.of(PyStr.of("method"));

        refuses(
                "TypeError: classmethod expected 1 argument, got 0",
                () -> PyType.CLASS_METHOD.call(new PyObject[0], null));
        refuses(
                "TypeError: classmethod expected 1 argument, got 2",
                () -> PyType.CLASS_METHOD.call(new PyObject[] {body, body}, null));
        refuses(
                "TypeError: staticmethod expected 1 argument, got 0",
                () -> PyType.STATIC_METHOD.call(new PyObject[0], null));
        refuses(
                "TypeError: classmethod() takes no keyword arguments",
                () -> PyType.CLASS_METHOD.call(new PyObject[] {body}, named));
        refuses(
                "TypeError: staticmethod() takes no keyword arguments",
                () -> PyType.STATIC_METHOD.call(new PyObject[] {body}, named));

        // property is of the vectorcall family rather than the legacy one, and both its messages say so.
        refuses(
                "TypeError: property() takes at most 4 arguments (5 given)",
                () ->
                        PyType.PROPERTY.call(
                                new PyObject[] {body, body, body, PyNone.INSTANCE, body}, null));
        refuses(
                "TypeError: property() got an unexpected keyword argument 'x'",
                () ->
                        PyType.PROPERTY.call(
                                new PyObject[] {PyInt.ONE}, PyTuple.of(PyStr.of("x"))));
    }

    /**
     * A read-only property owns its name, and the complaint names the class it was filed in.
     *
     * <p>{@code 'span'} is in that message because {@code __set_name__} was called as the class was
     * built; reading the same member off the class gives the property back rather than asking it for a
     * value it has no instance to compute. Neither writing half exists on this one, and the instance
     * dictionary is left alone either way — which is what makes a property a data descriptor rather than
     * a suggestion.
     */
    @Test
    void aGetterOnlyPropertyRefusesItsNameByItsName() {
        PyObject span = property(function("span", PyStr.of("measured")), null, null, null);
        PyType kind = type("Measured", "span", span);
        PyObject value = Slots.call(kind, new PyObject[0], null);

        assertThat(Slots.getAttr(value, "span")).isEqualTo(PyStr.of("measured"));
        assertThat(Slots.getAttr(kind, "span")).isSameAs(span);
        assertThat(Slots.getAttr(span, "__name__")).isEqualTo(PyStr.of("span"));
        assertThat(Descriptors.writes(span)).isTrue();
        assertThat(Slots.getAttr(span, "fget")).isNotEqualTo(PyNone.INSTANCE);
        assertThat(Slots.getAttr(span, "fset")).isEqualTo(PyNone.INSTANCE);

        refuses(
                "AttributeError: property 'span' of 'Measured' object has no setter",
                () -> Slots.setAttr(value, "span", PyInt.ONE));
        refuses(
                "AttributeError: property 'span' of 'Measured' object has no deleter",
                () -> Slots.delAttr(value, "span"));
        assertThat(((PyDict) Slots.getAttr(value, "__dict__")).get(PyStr.interned("span"))).isNull();
    }

    /** {@code @span.setter}: a copy with one function replaced, its docstring and its halves kept. */
    @Test
    void aPropertyCopiesItselfForItsDecoratorsAndKeepsWhatItWasTold() {
        PyObject getter = function("span", PyStr.of("read"));
        PyObject setter = function("write", PyNone.INSTANCE);
        PyObject first = property(getter, null, null, PyStr.of("the docstring"));

        PyObject withSetter =
                Slots.call(Slots.getAttr(first, "setter"), new PyObject[] {setter}, null);
        assertThat(withSetter).isInstanceOf(PyProperty.class).isNotSameAs(first);
        assertThat(Slots.getAttr(withSetter, "fset")).isSameAs(setter);
        assertThat(Slots.getAttr(withSetter, "fget")).isSameAs(getter);
        assertThat(Slots.getAttr(withSetter, "fdel")).isEqualTo(PyNone.INSTANCE);
        // CPython's property_copy keeps the docstring instead of re-deriving it from what was replaced.
        assertThat(Slots.getAttr(withSetter, "__doc__")).isEqualTo(PyStr.of("the docstring"));

        refuses(
                "TypeError: property.getter() takes exactly one argument (0 given)",
                () -> Slots.getAttr(first, "getter").call(new PyObject[0], null));

        // A property never placed in a class falls back to a function's own name, and with no function
        // behind it and no class to name it there is no name at all — a refusal rather than None.
        assertThat(Slots.getAttr(first, "__name__")).isEqualTo(PyStr.of("span"));
        refuses(
                "AttributeError: 'property' object has no attribute '__name__'",
                () -> Slots.getAttr(property(null, null, null, null), "__name__"));
    }

    /**
     * The wrapper half of the protocol, asked of the objects themselves.
     *
     * <p>Each message belongs to a different unpacking macro in CPython, and the differences are the
     * point: {@code __get__} takes one or two arguments and names itself bare, {@code __set__} does the
     * same for a write, {@code __delete__} names nothing at all because {@code descr_delete} passes no
     * name to its unpacking, and {@code __set_name__} is a plain method counting positionals.
     */
    @Test
    void theDescriptorSlotsRefuseTheCallsThatNameNoValue() {
        PyObject span = property(function("span", PyStr.of("measured")), null, null, null);
        PyObject getValue = Slots.getAttr(span, "__get__");
        PyObject setValue = Slots.getAttr(span, "__set__");
        PyObject deleteValue = Slots.getAttr(span, "__delete__");
        PyObject setName = Slots.getAttr(span, "__set_name__");

        refuses(
                "TypeError: __get__ expected at least 1 argument, got 0",
                () -> getValue.call(new PyObject[0], null));
        refuses(
                "TypeError: __get__(None, None) is invalid",
                () -> getValue.call(new PyObject[] {PyNone.INSTANCE, PyNone.INSTANCE}, null));
        refuses(
                "TypeError: wrapper __get__() takes no keyword arguments",
                () -> getValue.call(new PyObject[] {PyInt.ONE}, PyTuple.of(PyStr.of("obj"))));
        refuses(
                "TypeError: __set__ expected 2 arguments, got 1",
                () -> setValue.call(new PyObject[] {PyInt.ONE}, null));
        refuses(
                "TypeError: __set__ expected 2 arguments, got 3",
                () -> setValue.call(new PyObject[] {PyInt.ONE, PyInt.ONE, PyInt.ONE}, null));
        refuses(
                "TypeError: wrapper __set__() takes no keyword arguments",
                () ->
                        setValue.call(
                                new PyObject[] {PyInt.ONE, PyInt.ONE, PyInt.ONE},
                                PyTuple.of(PyStr.of("value"))));
        refuses(
                "TypeError: expected 1 argument, got 0",
                () -> deleteValue.call(new PyObject[0], null));
        refuses(
                "TypeError: expected 1 argument, got 2",
                () -> deleteValue.call(new PyObject[] {PyInt.ONE, PyInt.ONE}, null));
        refuses(
                "TypeError: __set_name__() takes 2 positional arguments but 1 were given",
                () -> setName.call(new PyObject[] {PyInt.ONE}, null));
        refuses(
                "TypeError: property.__set_name__() takes no keyword arguments",
                () -> setName.call(new PyObject[] {PyInt.ONE}, PyTuple.of(PyStr.of("owner"))));

        // One argument is enough for __get__: the class it is missing is filled in from the value.
        PyType kind = type("Filled", "span", span);
        assertThat(getValue.call(new PyObject[] {Slots.call(kind, new PyObject[0], null)}, null))
                .isEqualTo(PyStr.of("measured"));
    }

    /** {@code callable()} is true of exactly one of the three, and only that one can be called. */
    @Test
    void onlyAStaticmethodIsCallable() {
        PyObject body = function("weight", PyStr.of("called"));
        PyObject plain = new PyStaticMethod(body);
        PyObject wrapped = new PyClassMethod(body);
        PyObject view = property(body, null, null, null);

        assertThat(Slots.isCallable(plain)).isTrue();
        assertThat(Slots.isCallable(wrapped)).isFalse();
        assertThat(Slots.isCallable(view)).isFalse();
        assertThat(Slots.call(plain, new PyObject[0], null)).isEqualTo(PyStr.of("called"));

        refuses(
                "TypeError: 'classmethod' object is not callable",
                () -> Slots.call(wrapped, new PyObject[0], null));
        refuses(
                "TypeError: 'property' object is not callable",
                () -> Slots.call(view, new PyObject[0], null));
    }

    /**
     * A classmethod binds its class whichever side it is reached from; a staticmethod binds nothing.
     *
     * <p>{@code Scores.named} and {@code Scores().named} are methods of the same class, reached twice,
     * and reading through a subclass hands over the subclass instead — the whole of what the wrapper
     * adds, and why the answer's type is an ordinary {@code method}. A staticmethod's answer is the
     * function itself, object for object, so a call through a value receives nothing beside it: the
     * function below counts its one argument and says so.
     */
    @Test
    void aClassmethodBindsItsClassAndAStaticmethodBindsNothing() {
        PyNativeFunction named =
                new PyNativeFunction(
                        "named",
                        (args, kwNames) ->
                                PyStr.of(((PyType) args[0]).name() + " " + args.length));
        PyNativeFunction weight =
                new PyNativeFunction("weight", (args, kwNames) -> PyInt.of(args.length));

        PyType kind =
                type(
                        "Scores",
                        "named",
                        new PyClassMethod(named),
                        "weight",
                        new PyStaticMethod(weight));
        PyObject value = Slots.call(kind, new PyObject[0], null);
        PyType subclass = subtype("Loud", kind);

        assertThat(Slots.getAttr(kind, "named")).isInstanceOf(PyBoundMethod.class);
        assertThat(Slots.getAttr(value, "named")).isInstanceOf(PyBoundMethod.class);
        assertThat(Slots.getAttr(kind, "named").getType().name()).isEqualTo("method");
        assertThat(Slots.getAttr(kind, "named")).isNotSameAs(Slots.getAttr(value, "named"));
        assertThat(Slots.getAttr(subclass, "named").getAttr("__self__")).isSameAs(subclass);

        assertThat(Slots.call(Slots.getAttr(value, "named"), new PyObject[0], null))
                .isEqualTo(PyStr.of("Scores 1"));
        assertThat(Slots.call(Slots.getAttr(subclass, "named"), new PyObject[0], null))
                .isEqualTo(PyStr.of("Loud 1"));

        assertThat(Slots.getAttr(value, "weight")).isSameAs(weight);
        assertThat(Slots.getAttr(kind, "weight")).isSameAs(weight);
        assertThat(Slots.call(Slots.getAttr(value, "weight"), new PyObject[] {PyInt.ONE}, null))
                .isEqualTo(PyInt.ONE);
    }
}
