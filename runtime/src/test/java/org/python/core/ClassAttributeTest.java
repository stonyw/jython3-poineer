package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The one name {@code object} files for every value, measured against CPython 3.14.8.
 *
 * <p>The corpus script reads the name the way a program does — through {@code x.__class__}, with a
 * {@code try} around each write. What is tested here is the machinery underneath it: that the answer is
 * the same object a value's own class is, that it arrives only after the value's type has refused, that a
 * class which files the name takes the write away from {@code object} entirely, and the four questions
 * {@code object_set_class} asks in a fixed order. The swap itself is the part no script can be asked to
 * prove by reading, so each successful one is followed by the storage it has to keep working on.
 */
class ClassAttributeTest {

    /** {@code type(name, bases, {members})}, the three-argument creator asked to make a plain class. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        return (PyType) Classes.newType(PyType.TYPE, three(name, bases, members), null);
    }

    /** {@code Meta(name, bases, {members})}: the same call, put to a metatype. */
    private static PyType type(PyType metatype, String name, PyType[] bases, Object... members) {
        return (PyType) Classes.newType(metatype, three(name, bases, members), null);
    }

    private static PyObject[] three(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return new PyObject[] {PyStr.of(name), PyTuple.of(bases), dict};
    }

    /** An instance of a class built here, which is {@code Kind()} as a script would write it. */
    private static PyObject make(PyType kind) {
        return Slots.call(kind, new PyObject[0], null);
    }

    /** {@code target.__class__ = value}. */
    private static void assign(PyObject target, PyObject value) {
        Slots.setAttr(target, ClassAttribute.NAME, value);
    }

    /** {@code del target.__class__}. */
    private static void drop(PyObject target) {
        Slots.delAttr(target, ClassAttribute.NAME);
    }

    /** {@code TypeError} and {@code AttributeError} in CPython's own words. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * Every value that files nothing answers the class it reports.
     *
     * <p>The read runs after the value's own type refuses, so it reaches a number, a module, an exception
     * and a built-in type alike, and it answers with that same object rather than a copy of it — which is
     * what makes {@code x.__class__ is type(x)} hold for every line here. A type object is a value too, so
     * a built-in's own class is {@code type} and a class written at Python level reports the metatype it
     * was built with.
     */
    @Test
    void everyValueThatFilesNothingAnswersTheClassItReports() {
        assertThat(Slots.getAttr(PyInt.of(1), ClassAttribute.NAME)).isSameAs(PyType.INT);
        assertThat(Slots.getAttr(PyBool.TRUE, ClassAttribute.NAME)).isSameAs(PyType.BOOL);
        assertThat(Slots.getAttr(PyStr.of("text"), ClassAttribute.NAME)).isSameAs(PyType.STR);
        assertThat(Slots.getAttr(PyList.of(PyInt.of(1)), ClassAttribute.NAME)).isSameAs(PyType.LIST);
        assertThat(Slots.getAttr(PyDict.empty(), ClassAttribute.NAME)).isSameAs(PyType.DICT);
        assertThat(Slots.getAttr(PyTuple.of(), ClassAttribute.NAME)).isSameAs(PyType.TUPLE);
        assertThat(Slots.getAttr(PySet.of(PyInt.of(1)), ClassAttribute.NAME)).isSameAs(PyType.SET);
        assertThat(Slots.getAttr(PySlice.of(PyNone.INSTANCE, PyInt.of(2), PyNone.INSTANCE),
                        ClassAttribute.NAME))
                .isSameAs(PyType.SLICE);
        assertThat(Slots.getAttr(PyNone.INSTANCE, ClassAttribute.NAME)).isSameAs(PyType.NONE);
        assertThat(Slots.getAttr(new PyModule("tagged"), ClassAttribute.NAME)).isSameAs(PyType.MODULE);
        assertThat(Slots.getAttr(PyType.INT, ClassAttribute.NAME)).isSameAs(PyType.TYPE);

        PyType kind = type("Kind", new PyType[] {PyType.OBJECT});
        assertThat(Slots.getAttr(kind, ClassAttribute.NAME)).isSameAs(PyType.TYPE);
        assertThat(Slots.getAttr(make(kind), ClassAttribute.NAME)).isSameAs(kind);

        PyType meta = type("Meta", new PyType[] {PyType.TYPE});
        PyType withMeta = type(meta, "WithMeta", new PyType[0]);
        assertThat(Slots.getAttr(withMeta, ClassAttribute.NAME)).isSameAs(meta);

        PyObject held = Slots.call(ExceptionTypes.VALUE_ERROR, new PyObject[] {PyStr.of("spam")}, null);
        assertThat(Slots.getAttr(held, ClassAttribute.NAME)).isSameAs(ExceptionTypes.VALUE_ERROR);
        assertThat(Slots.findAttr(held, ClassAttribute.NAME)).isSameAs(ExceptionTypes.VALUE_ERROR);
    }

    /**
     * The four questions of a write, in the order only the first refusal survives.
     *
     * <p>A value that is not a class is complained about before the two classes are looked at, which is
     * why {@code (1).__class__ = 'text'} names {@code str} rather than refusing the assignment at all —
     * the same answer a mutable value would get. Then either class being immutable ends the question, and
     * only a swap both survive reaches the layout. Deleting is refused first of all, before any of that.
     *
     * <p>A write on a built-in <em>type</em> never reaches these: {@code type.__setattr__} refuses every
     * name it is given on a type that was compiled in, and says so with a sentence about setting even for
     * {@code del int.__class__}.
     */
    @Test
    void theWriteAsksFourQuestionsAndStopsAtTheFirstOneThatRefuses() {
        refuses("TypeError: can't delete __class__ attribute", () -> drop(PyInt.of(1)));
        refuses(
                "TypeError: __class__ must be set to a class, not 'str' object",
                () -> assign(PyInt.of(1), PyStr.of("text")));
        refuses(
                "TypeError: __class__ assignment only supported for mutable types"
                        + " or ModuleType subclasses",
                () -> assign(PyInt.of(1), PyType.STR));
        refuses(
                "TypeError: __class__ assignment only supported for mutable types"
                        + " or ModuleType subclasses",
                () -> assign(PyStr.of("text"), PyType.LIST));

        PyType base = type("Base", new PyType[] {PyType.OBJECT});
        PyType left = type("Left", new PyType[] {ExceptionTypes.EXCEPTION});
        refuses(
                "TypeError: __class__ assignment: 'Left' object layout differs from 'Base'",
                () -> assign(make(base), left));
        refuses(
                "TypeError: __class__ assignment: 'Base' object layout differs from 'Left'",
                () -> assign(make(left), base));

        refuses(
                "TypeError: cannot set '__class__' attribute of immutable type 'int'",
                () -> assign(PyType.INT, type("Meta", new PyType[] {PyType.TYPE})));
        refuses(
                "TypeError: cannot set '__class__' attribute of immutable type 'int'",
                () -> drop(PyType.INT));
        refuses(
                "TypeError: cannot set '__class__' attribute of immutable type 'type'",
                () -> assign(PyType.TYPE, PyType.OBJECT));
    }

    /**
     * A swap the layouts agree to is carried out, and the storage comes with it.
     *
     * <p>The layout this runtime compares is the built-in class each written class was made from, so two
     * plain classes are interchangeable and a plain one and an exception are not — which is CPython's
     * answer too, since the size and the instance dictionary are what it measures. What is swapped is the
     * one pointer a value keeps beside its arguments: the instance dictionary, and everything written into
     * it before the swap, is the same dictionary afterwards, and {@code isinstance} follows the new class
     * at once.
     */
    @Test
    void aSwapOfTheSameShapeIsCarriedOutOfTheValueItLandsOn() {
        PyType base = type("Base", new PyType[] {PyType.OBJECT});
        PyType derived = type("Derived", new PyType[] {base});
        PyType other = type("Other", new PyType[] {PyType.OBJECT});

        PyObject kept = make(base);
        PyObject tag = PyStr.of("carried");
        Slots.setAttr(kept, "tag", tag);

        assign(kept, derived);
        assertThat(kept.getType()).isSameAs(derived);
        assertThat(Slots.getAttr(kept, ClassAttribute.NAME)).isSameAs(derived);
        assertThat(Classes.instanceOf(kept, derived)).isTrue();
        assertThat(Classes.instanceOf(kept, base)).isTrue();
        assertThat(Slots.getAttr(kept, "tag")).isSameAs(tag);

        assign(kept, other);
        assertThat(kept.getType()).isSameAs(other);
        assertThat(Classes.instanceOf(kept, other)).isTrue();
        assertThat(Classes.instanceOf(kept, derived)).isFalse();
        assertThat(Classes.instanceOf(kept, base)).isFalse();

        assign(kept, base);
        assertThat(kept.getType()).isSameAs(base);
        assertThat(Classes.instanceOf(kept, derived)).isFalse();
    }

    /**
     * An exception swapped to another keeps what it was raised with and what it is read as.
     *
     * <p>{@code PyBaseException} holds its class twice — once as the pointer {@code type(e)} reads and
     * once as the class whose name the display and {@code __init__} use — so a swap that moved only the
     * first would leave {@code str(e)} and the traceback header naming the old class. Both move, the
     * {@code args} stay, and the value is caught by the new class and not by the old one.
     */
    @Test
    void anExceptionSwapMovesBothPlacesItsClassIsKept() {
        PyType left = type("Left", new PyType[] {ExceptionTypes.EXCEPTION});
        PyType right = type("Right", new PyType[] {ExceptionTypes.EXCEPTION});

        PyObject held =
                Slots.call(left, new PyObject[] {PyStr.of("spam"), PyInt.of(2)}, null);
        assertThat(Slots.getAttr(held, ClassAttribute.NAME)).isSameAs(left);

        assign(held, right);

        assertThat(held.getType()).isSameAs(right);
        assertThat(Slots.getAttr(held, ClassAttribute.NAME)).isSameAs(right);
        assertThat(Slots.getAttr(held, "args")).isEqualTo(PyTuple.of(PyStr.of("spam"), PyInt.of(2)));
        assertThat(Slots.repr(held)).isEqualTo("Right('spam', 2)");
        assertThat(Slots.str(held)).isEqualTo("('spam', 2)");
        assertThat(Classes.instanceOf(held, left)).isFalse();
        assertThat(Classes.instanceOf(held, right)).isTrue();
        assertThat(Classes.instanceOf(held, ExceptionTypes.EXCEPTION)).isTrue();
    }

    /**
     * A module is the one built-in whose own class a write may name, and only with another module.
     *
     * <p>{@code object_set_class} makes room for module subclasses because a module object is mutable and
     * so is never interned; the exemption needs both classes to be modules, which is why handing a module a
     * plain written class is refused in the same sentence an {@code int} is.
     */
    @Test
    void aModuleSwapsOnlyWithAModule() {
        PyObject module = new PyModule("tagged");

        assign(module, PyType.MODULE);
        assertThat(module.getType()).isSameAs(PyType.MODULE);

        refuses(
                "TypeError: __class__ assignment only supported for mutable types"
                        + " or ModuleType subclasses",
                () -> assign(module, type("Plain", new PyType[] {PyType.OBJECT})));
    }

    /**
     * A class written at Python level is the only value whose own type a write may replace.
     *
     * <p>For a class, {@code __class__} is not the class it is filed under — it is its <em>metatype</em>,
     * and swapping it is how a program gives a class a metaclass after the fact. That needs two heap
     * classes, so a plain class handed a metatype is refused: the class it reports today is {@code type},
     * which was compiled in and is immutable.
     */
    @Test
    void aWrittenClassCanLoseItsMetatypeAndAPlainOneCannot() {
        PyType meta = type("Meta", new PyType[] {PyType.TYPE});
        PyType sub = type("Sub", new PyType[] {meta});
        PyType plain = type("Plain", new PyType[] {PyType.OBJECT});

        PyType withMeta = type(meta, "WithMeta", new PyType[0]);
        Slots.setAttr(withMeta, "member", PyInt.of(7));

        assign(withMeta, sub);
        assertThat(withMeta.getType()).isSameAs(sub);
        assertThat(Slots.getAttr(withMeta, ClassAttribute.NAME)).isSameAs(sub);
        assertThat(Slots.getAttr(withMeta, "member")).isSameAs(PyInt.of(7));
        assertThat(Slots.getAttr(withMeta, "__bases__")).isEqualTo(PyTuple.of(PyType.OBJECT));

        refuses(
                "TypeError: __class__ assignment only supported for mutable types"
                        + " or ModuleType subclasses",
                () -> assign(plain, meta));
        assertThat(plain.getType()).isSameAs(PyType.TYPE);
    }

    /**
     * A class that files the name owns it, and its instances' writes are ordinary writes.
     *
     * <p>{@code object}'s answer is reached only after the value's own type refuses, and a member the class
     * filed is found before that — so {@code Shadow().__class__} is the {@code int} it names, while
     * {@code type(Shadow())} stays {@code Shadow} and {@code isinstance} of the real class still holds.
     * A write of such an instance then goes to the instance dictionary like any other name, and the name
     * is read from there until it is deleted; a second delete has nothing left in the dictionary to remove
     * and refuses the way a missing attribute is refused.
     *
     * <p>A class that owns every write through {@code __setattr__} is the same case in a different shape:
     * the hook is asked, and {@code object} never hears that the name it files was written at all.
     */
    @Test
    void aClassThatFilesTheNameIsTheOneAnsweredAboutIt() {
        PyType shadow = type("Shadow", new PyType[] {PyType.OBJECT}, ClassAttribute.NAME, PyType.INT);
        PyObject value = make(shadow);

        assertThat(Slots.getAttr(value, ClassAttribute.NAME)).isSameAs(PyType.INT);
        assertThat(value.getType()).isSameAs(shadow);
        assertThat(Classes.instanceOf(value, shadow)).isTrue();
        assertThat(Slots.getAttr(shadow, ClassAttribute.NAME)).isSameAs(PyType.TYPE);

        assign(value, PyType.STR);
        assertThat(((PyDict) Slots.getAttr(value, "__dict__"))
                        .get(PyStr.interned(ClassAttribute.NAME)))
                .isSameAs(PyType.STR);
        assertThat(Slots.getAttr(value, ClassAttribute.NAME)).isSameAs(PyType.STR);

        drop(value);
        assertThat(Slots.getAttr(value, ClassAttribute.NAME)).isSameAs(PyType.INT);
        assertThat(((PyDict) Slots.getAttr(value, "__dict__"))
                        .get(PyStr.interned(ClassAttribute.NAME)))
                .isNull();
        refuses(
                "AttributeError: 'Shadow' object has no attribute '__class__'",
                () -> drop(value));

        PyType hooked = type("Hooked", new PyType[] {PyType.OBJECT}, ClassAttribute.NAME, PyType.STR);
        PyObject claimer = make(hooked);
        assertThat(Slots.findAttr(claimer, ClassAttribute.NAME)).isSameAs(PyType.STR);
    }

    /**
     * The read reaches a value through the routes that are not a plain attribute.
     *
     * <p>{@code case Point(__class__=kind)} is {@code PyObject_GetOptionalAttr}, so a keyword pattern
     * reaches the names {@code object} answers for every value; a refusal there is the empty answer a
     * pattern needs rather than an error. {@code supercheck} reads the same name a second time, only after
     * the value's real class has refused to be a subtype of the starting one — which is how a value that
     * claims a class is taken at its word and given that class's order to walk, rather than the complaint
     * a value that claims nothing gets.
     */
    @Test
    void theOptionalReadAndTheSuperCheckBothReachTheName() {
        PyType point = type("Point", new PyType[] {PyType.OBJECT});
        PyObject value = make(point);

        assertThat(Slots.findAttr(value, ClassAttribute.NAME)).isSameAs(point);
        assertThat(Slots.findAttr(value, "missing")).isNull();

        PyType claims = type("Claims", new PyType[] {PyType.OBJECT}, ClassAttribute.NAME, PyType.INT);
        PyObject claimer = make(claims);
        assertThat(Slots.findAttr(claimer, ClassAttribute.NAME)).isSameAs(PyType.INT);

        PySuper proxy = PySuper.of(PyType.INT, claimer);
        assertThat(Slots.getAttr(proxy, "__thisclass__")).isSameAs(PyType.INT);
        assertThat(Slots.getAttr(proxy, "__self_class__")).isSameAs(PyType.INT);
        assertThat(Slots.getAttr(proxy, "__self__")).isSameAs(claimer);

        PyType other = type("Other", new PyType[] {PyType.OBJECT});
        refuses(
                "TypeError: super(type, obj): obj (instance of Other) is not an instance or subtype"
                        + " of type (int).",
                () -> PySuper.of(PyType.INT, make(other)));
    }
}
