package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dictionaries a type publishes, the five kinds filed in them, and the write a class answers, measured
 * against CPython 3.14.8.
 *
 * <p>The four corpus scripts read all of this through Python: a class statement, a metaclass, the name
 * {@code __dict__} again. This is the surface a script cannot point at. The three complete tables are
 * asserted here as the Java maps they are, because a script can only read the names of a type this runtime
 * fills entirely and cannot say which table filled them; the writing half of a descriptor is asked through
 * {@link Descriptors}, which is the question {@code type_setattro} puts before it looks at a dictionary and
 * is not a name any program can read; and a class made here is a class no source ever wrote, so its metatype
 * can file a member that was never a {@code property} in a body. The one sentence of the read that is not
 * here is the metaclass {@code __getattr__} that answers a name nobody files: that step asks for a function
 * a class statement compiled, and is left to corpus case 58.
 */
class TypeNamespaceTest {

    /** The twenty-four names {@code object} files, sorted as {@code dir()} reads them. */
    private static final List<String> OBJECT_NAMES =
            List.of(
                    "__class__", "__delattr__", "__dir__", "__doc__", "__eq__", "__format__", "__ge__",
                    "__getattribute__", "__getstate__", "__gt__", "__hash__", "__init__",
                    "__init_subclass__", "__le__", "__lt__", "__ne__", "__new__", "__reduce__",
                    "__reduce_ex__", "__repr__", "__setattr__", "__sizeof__", "__str__",
                    "__subclasshook__");

    /** The thirty-four names {@code type} files, in the same order. */
    private static final List<String> TYPE_NAMES =
            List.of(
                    "__abstractmethods__", "__annotate__", "__annotations__", "__base__", "__bases__",
                    "__basicsize__", "__call__", "__delattr__", "__dict__", "__dictoffset__", "__dir__",
                    "__doc__", "__flags__", "__getattribute__", "__init__", "__instancecheck__",
                    "__itemsize__", "__module__", "__mro__", "__name__", "__new__", "__or__", "__prepare__",
                    "__qualname__", "__repr__", "__ror__", "__setattr__", "__sizeof__", "__subclasscheck__",
                    "__subclasses__", "__text_signature__", "__type_params__", "__weakrefoffset__", "mro");

    /** The twenty-five names the window itself is written from. */
    private static final List<String> PROXY_NAMES =
            List.of(
                    "__class_getitem__", "__contains__", "__doc__", "__eq__", "__ge__", "__getitem__",
                    "__gt__", "__hash__", "__ior__", "__iter__", "__le__", "__len__", "__lt__", "__ne__",
                    "__new__", "__or__", "__repr__", "__reversed__", "__ror__", "__str__", "copy", "get",
                    "items", "keys", "values");

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

    /** {@code C.__dict__} unwrapped: the dictionary the names a class was built from are kept in. */
    private static PyDict dictOf(PyType kind) {
        return ((PyMappingProxy) Slots.getAttr(kind, "__dict__")).mapping();
    }

    /** The names an iterator answers, in the order it answers them. */
    private static List<String> drained(PyObject iterator) {
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = ((PyIterator) iterator).nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** Whether the comparison of two values answers true, at Python level rather than Java's. */
    private static boolean answers(PyObject left, CompareOp cmp, PyObject right) {
        return ((PyBool) Slots.compare(cmp, left, right)).getValue();
    }

    /** The text a value answers for a name, which is how a written {@code __name__} is read back. */
    private static String textOf(PyObject value, String name) {
        return ((PyStr) Slots.getAttr(value, name)).getValue();
    }

    /** The entry a type files under {@code name}, out of its own dictionary rather than by reading it. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    private static String kind(PyObject member) {
        return member.getType().name();
    }

    /** {@code property(fget, fset)}: the member a metatype files for a name its classes are given. */
    private static PyObject property(PyNativeFunction getter, PyNativeFunction setter) {
        return new PyProperty(
                getter,
                setter == null ? PyNone.INSTANCE : setter,
                PyNone.INSTANCE,
                PyNone.INSTANCE);
    }

    /** A function that records the receiver and the argument it was handed, and answers {@code None}. */
    private static PyNativeFunction recording(List<String> asked, String label) {
        return new PyNativeFunction(
                label,
                (args, kwNames) -> {
                    asked.add(
                            label
                                    + " "
                                    + (args[0] instanceof PyType owner
                                            ? owner.name()
                                            : args[0].getType().name())
                                    + (args.length > 1 ? " " + args[1].getType().name() : ""));
                    return PyNone.INSTANCE;
                });
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
     * The dictionary is one per type and the window is one per read, so equality is the dictionary's.
     *
     * <p>{@code mappingproxy_richcompare} hands the mapping to the compare and leaves the other operand as
     * it arrived, which is why two windows over two different dictionaries that hold the same names are
     * equal, and why a window is never a key: {@code hash} is refused in the dictionary's words, not the
     * window's.
     */
    @Test
    void theWindowIsFreshAndTheMappingUnderneathIsOne() {
        PyDict kept = TypeDict.declared(PyType.INT);
        assertThat(TypeDict.declared(PyType.INT)).isSameAs(kept);

        PyObject first = Slots.getAttr(PyType.INT, "__dict__");
        PyObject second = Slots.getAttr(PyType.INT, "__dict__");
        assertThat(first).isNotSameAs(second);
        assertThat(answers(first, CompareOp.EQ, second)).isTrue();

        // Two dictionaries, contents agreeing: CPython compares them and says yes, which is the answer a
        // window over each one has to give.
        PyDict left = PyDict.empty();
        PyDict right = PyDict.empty();
        for (String name : new String[] {"a", "b"}) {
            left.put(PyStr.interned(name), PyInt.of(1));
            right.put(PyStr.interned(name), PyInt.of(1));
        }
        assertThat(left).isNotSameAs(right);
        assertThat(PyMappingProxy.of(left).eq(PyMappingProxy.of(right))).isTrue();
        assertThat(PyMappingProxy.of(left).eq(PyMappingProxy.of(PyDict.empty()))).isFalse();
        assertThat(Slots.compare(CompareOp.NE, PyMappingProxy.of(left), PyMappingProxy.of(right)))
                .isSameAs(PyBool.FALSE);

        refuses("TypeError: unhashable type: 'dict'", () -> PyMappingProxy.of(left).hash());
        refuses(
                "TypeError: '|=' is not supported by mappingproxy; use '|' instead",
                () -> PyMappingProxy.of(left).inplaceOp(BinaryOp.OR, right));
        assertThat(PyMappingProxy.of(left).binaryOp(BinaryOp.OR, right).getType()).isSameAs(PyType.DICT);
        assertThat(PyMappingProxy.of(left).repr()).isEqualTo("mappingproxy({'a': 1, 'b': 1})");
        assertThat(PyMappingProxy.of(left).str()).isEqualTo("{'a': 1, 'b': 1}");
    }

    /**
     * The keys come in the order the dictionary was written, not the order {@code dir()} sorts them into.
     *
     * <p>That is the one place a window is more informative than {@code dir} about a namespace: the order the
     * members arrived in survives into the class's dictionary, and the names the creator files for itself —
     * the window and the two storage slots, then the sentence — land after them. The reverse iterator walks
     * back over the whole of it, so the last two names the body wrote are the first two it answers. A class
     * made from Java has no calling frame to read a module name from, which is CPython's {@code globals ==
     * NULL} branch of {@code type_new_set_module}: the dictionary says nothing about where it came from.
     */
    @Test
    void theWindowIteratesInTheOrderTheBodyWrote() {
        PyType kind =
                type(
                        "Ordered",
                        new PyType[0],
                        "__module__",
                        PyStr.of("body"),
                        "zeta",
                        PyInt.of(1),
                        "alpha",
                        PyInt.of(2));
        PyMappingProxy window = (PyMappingProxy) Slots.getAttr(kind, "__dict__");
        assertThat(drained(window.keysIterator()))
                .containsExactly("__module__", "zeta", "alpha", "__dict__", "__weakref__", "__doc__");
        assertThat(drained(window.reversedKeysIterator()))
                .containsExactly("__doc__", "__weakref__", "__dict__", "alpha", "zeta", "__module__");

        PyMappingProxy frameless =
                (PyMappingProxy)
                        Slots.getAttr(
                                type("NoFrame", new PyType[0], "zeta", PyInt.of(1)), "__dict__");
        assertThat(drained(frameless.keysIterator()))
                .containsExactly("zeta", "__dict__", "__weakref__", "__doc__");
    }

    /**
     * Three types fill their whole dictionary here, and every entry is the kind the interpreter files.
     *
     * <p>The names are asserted as a complete set because a script can only read a table this runtime
     * fills entirely — {@code int} files six of its sixty names — and the kinds are the point of the
     * slice: which of {@code PyMethodDef}, {@code PyGetSetDef}, {@code PyMemberDef} and the slot wrapper a
     * name was filed from.
     */
    @Test
    void theThreeTablesFileTheNamesTheInterpreterFiles() {
        assertThat(sorted(TypeDict.declared(PyType.OBJECT))).isEqualTo(OBJECT_NAMES);
        assertThat(sorted(TypeDict.declared(PyType.TYPE))).isEqualTo(TYPE_NAMES);
        assertThat(sorted(TypeDict.declared(PyType.MAPPING_PROXY))).isEqualTo(PROXY_NAMES);

        assertThat(kind(entry(PyType.OBJECT, "__dir__"))).isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.OBJECT, "__eq__"))).isEqualTo("wrapper_descriptor");
        assertThat(kind(entry(PyType.OBJECT, "__class__"))).isEqualTo("getset_descriptor");
        assertThat(kind(entry(PyType.OBJECT, "__init_subclass__")))
                .isEqualTo("classmethod_descriptor");
        assertThat(kind(entry(PyType.OBJECT, "__new__"))).isEqualTo("builtin_function_or_method");
        assertThat(kind(entry(PyType.OBJECT, "__doc__"))).isEqualTo("str");
        assertThat(kind(entry(PyType.TYPE, "__name__"))).isEqualTo("getset_descriptor");
        assertThat(kind(entry(PyType.TYPE, "__base__"))).isEqualTo("member_descriptor");
        assertThat(kind(entry(PyType.TYPE, "mro"))).isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.TYPE, "__prepare__"))).isEqualTo("classmethod_descriptor");

        // The descriptor object is the one the name means, so reading the table twice gives the same
        // member and `type.mro is type.__dict__['mro']` stays true.
        assertThat(entry(PyType.TYPE, "mro")).isSameAs(Slots.getAttr(PyType.TYPE, "mro"));
        assertThat(entry(PyType.OBJECT, "__dir__"))
                .isSameAs(Specials.member(PyType.OBJECT, "__dir__"));
    }

    private static List<String> sorted(PyDict dict) {
        List<String> names = new ArrayList<>();
        for (PyObject key : dict.keys()) {
            names.add(((PyStr) key).getValue());
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * A getset is a data descriptor whatever it holds, and the half that writes is what differs.
     *
     * <p>{@link Descriptors#writes} is {@code tp_descr_set != NULL}, and {@code getset_descriptor} always
     * has one — so {@code __mro__} outranks a class dictionary that holds the name and then refuses the
     * write in its own words. The receiver is checked before either half runs, in the {@code tp_name} of the
     * declaring type.
     */
    @Test
    void aGetsetIsADataDescriptorEvenWhenItCannotWrite() {
        PyType kind = type("Owned", new PyType[0]);
        PyObject mro = entry(PyType.TYPE, "__mro__");
        assertThat(Descriptors.writes(mro)).isTrue();
        refuses(
                "AttributeError: attribute '__mro__' of 'type' objects is not writable",
                () -> Descriptors.set(mro, kind, PyTuple.of()));
        refuses(
                "AttributeError: attribute '__mro__' of 'type' objects is not writable",
                () -> Descriptors.delete(mro, kind));

        PyObject name = entry(PyType.TYPE, "__name__");
        Descriptors.set(name, kind, PyStr.of("Other"));
        assertThat(kind.name()).isEqualTo("Other");
        assertThat(textOf(kind, "__name__")).isEqualTo("Other");

        refuses(
                "TypeError: descriptor '__name__' for 'type' objects doesn't apply to a 'int' object",
                () -> Descriptors.get(name, PyInt.of(1), PyType.TYPE));
        refuses(
                "TypeError: cannot set '__name__' attribute of immutable type 'int'",
                () -> Descriptors.set(name, PyType.INT, PyStr.of("x")));
        // A delete is refused whatever the class was built from, and the sentence names the class as it is
        // now called — the rename above is the reason this one says `Other`.
        refuses(
                "TypeError: cannot delete '__name__' attribute of immutable type 'Other'",
                () -> Descriptors.delete(name, kind));
        assertThat(Descriptors.writes(entry(PyType.OBJECT, "__class__"))).isTrue();
    }

    /**
     * A member is storage, so its writing half exists even for a field that is read-only — and its refusal
     * is the one sentence CPython has for every read-only field alike.
     *
     * <p>The receiver check is the same as a getset's, but the name it prints is the {@code tp_name} of the
     * type whose field this is, dotted for {@code sys.version_info}.
     */
    @Test
    void aMemberRefusesWhateverArrives() {
        PyObject base = entry(PyType.TYPE, "__base__");
        PyType kind = type("Leaf", new PyType[0]);
        assertThat(Descriptors.writes(base)).isTrue();
        refuses("AttributeError: readonly attribute", () -> Descriptors.set(base, kind, PyNone.INSTANCE));
        refuses("AttributeError: readonly attribute", () -> Descriptors.delete(base, kind));
        refuses(
                "TypeError: descriptor '__base__' for 'type' objects doesn't apply to a 'int' object",
                () -> Descriptors.set(base, PyInt.of(1), PyNone.INSTANCE));
        assertThat(Descriptors.get(base, kind, PyType.TYPE)).isSameAs(PyType.OBJECT);
        assertThat(kind(entry(PyType.TYPE, "__basicsize__"))).isEqualTo("member_descriptor");
    }

    /**
     * A method, wrapper or classmethod descriptor files no writing half, so the name is the class's to
     * overwrite.
     *
     * <p>{@code type_setattro} looks at the member the metatype files and asks only this question of it;
     * the answer is no for six of the eight names in {@code object}'s dictionary, which is the whole of why
     * {@code C.mro = 1} files a number where {@code C.__mro__ = ()} is refused.
     */
    @Test
    void aMethodDescriptorFilesNoWritingHalf() {
        assertThat(Descriptors.writes(entry(PyType.OBJECT, "__dir__"))).isFalse();
        assertThat(Descriptors.writes(entry(PyType.OBJECT, "__eq__"))).isFalse();
        assertThat(Descriptors.writes(entry(PyType.OBJECT, "__subclasshook__"))).isFalse();
        assertThat(Descriptors.writes(entry(PyType.TYPE, "mro"))).isFalse();
        assertThat(Descriptors.writes(entry(PyType.TYPE, "__prepare__"))).isFalse();
        assertThat(Descriptors.writes(entry(PyType.TYPE, "__new__"))).isFalse();
        assertThat(Descriptors.writes(entry(PyType.OBJECT, "__doc__"))).isFalse();

        PyType kind = type("Over", new PyType[0]);
        PyObject number = PyInt.of(1);
        Slots.setAttr(kind, "mro", number);
        assertThat(dictOf(kind).get(PyStr.interned("mro"))).isSameAs(number);
        PyObject other = PyInt.of(2);
        Slots.setAttr(kind, "__dir__", other);
        assertThat(dictOf(kind).get(PyStr.interned("__dir__"))).isSameAs(other);
    }

    /**
     * The write asks the metatype first: its hook, then its writable member, then the dictionary.
     *
     * <p>A metatype's property owns the name on every class built from it and nothing is filed under it,
     * and a metatype that files a plain method descriptor says nothing about the write at all — the name
     * then lands in the class's own dictionary, which is the one dictionary every window over it shows.
     */
    @Test
    void theMetatypeIsAskedBeforeTheClassDictionary() {
        List<String> asked = new ArrayList<>();
        PyType metatype =
                type(
                        "Meta",
                        new PyType[] {PyType.TYPE},
                        "shaped",
                        property(recording(asked, "get"), recording(asked, "set")));
        PyType owned = type(metatype, "Owned", new PyType[0]);

        Slots.setAttr(owned, "shaped", PyInt.of(7));
        assertThat(asked).containsExactly("set Owned int");
        assertThat(dictOf(owned).get(PyStr.interned("shaped"))).isNull();

        // The deleter half is the same pointer with nothing handed over, and a property that files none
        // refuses in its own words instead of removing the name the class never held.
        refuses(
                "AttributeError: property 'shaped' of 'Meta' object has no deleter",
                () -> Slots.delAttr(owned, "shaped"));
        assertThat(asked).hasSize(1);
        assertThat(dictOf(owned).get(PyStr.interned("shaped"))).isNull();

        // A member the metatype files that has no writing half is not an answer about the write.
        PyObject number = PyInt.of(3);
        Slots.setAttr(owned, "mro", number);
        assertThat(asked).hasSize(1);
        assertThat(dictOf(owned).get(PyStr.interned("mro"))).isSameAs(number);
    }

    /**
     * {@code type.__setattr__} files the name without asking the metatype again.
     *
     * <p>That is what stops a hook that ends in {@code super().__setattr__} from being asked forever: the
     * three-argument slot is the same {@code type_setattro}, but it reaches {@link PyClass#write} directly
     * rather than the {@code tp_setattro} it is itself.
     */
    @Test
    void typeSetAttributeWritesWithoutReAskingTheMetatype() {
        List<String> asked = new ArrayList<>();
        PyType metatype = type("Meta", new PyType[] {PyType.TYPE});
        PyType owned = type(metatype, "Owned", new PyType[0]);
        dictOf(metatype)
                .put(
                        PyStr.interned("counted"),
                        new PyGetSetDescriptor(
                                metatype,
                                "counted",
                                null,
                                target -> PyInt.of(asked.size()),
                                (target, value) -> asked.add("set " + ((PyType) target).name())));

        // The metatype's writable member still runs for the class, and files nothing itself.
        Slots.setAttr(owned, "counted", PyInt.of(1));
        assertThat(asked).containsExactly("set Owned");
        assertThat(dictOf(owned).get(PyStr.interned("counted"))).isNull();

        PyObject keep = PyInt.of(2);
        Slots.call(
                Slots.getAttr(PyType.TYPE, "__setattr__"),
                new PyObject[] {owned, PyStr.of("kept"), keep},
                null);
        assertThat(dictOf(owned).get(PyStr.interned("kept"))).isSameAs(keep);
        assertThat(asked).containsExactly("set Owned");

        Slots.call(
                Slots.getAttr(PyType.TYPE, "__delattr__"),
                new PyObject[] {owned, PyStr.of("kept")},
                null);
        assertThat(dictOf(owned).get(PyStr.interned("kept"))).isNull();

        // The same slot on a type no Python source built is refused before the name is read.
        refuses(
                "TypeError: cannot set 'x' attribute of immutable type 'int'",
                () ->
                        Slots.call(
                                Slots.getAttr(PyType.TYPE, "__setattr__"),
                                new PyObject[] {PyType.INT, PyStr.of("x"), PyInt.of(1)},
                                null));
        refuses(
                "TypeError: cannot set 3 attribute of immutable type 'int'",
                () ->
                        Slots.call(
                                Slots.getAttr(PyType.TYPE, "__setattr__"),
                                new PyObject[] {PyType.INT, PyInt.of(3), PyInt.of(1)},
                                null));
    }

    /**
     * The plain names of a class are entries in a dictionary, and their refusals name the class.
     *
     * <p>A delete of a name the class does not file is not an {@code AttributeError} for the missing
     * member but {@code type object 'X' has no attribute 'y'} — the dictionary is asked, and the name is
     * not in it. A write of any other name goes to the same dictionary the next window reads.
     */
    @Test
    void aClassNameWrittenAndDeletedIsAnEntryInTheDocument() {
        PyType kind = type("Boxed", new PyType[0]);
        PyObject one = PyInt.of(1);
        Slots.setAttr(kind, "tag", one);
        assertThat(Slots.getAttr(kind, "tag")).isSameAs(one);
        assertThat(dictOf(kind).get(PyStr.interned("tag"))).isSameAs(one);
        Slots.delAttr(kind, "tag");
        assertThat(dictOf(kind).get(PyStr.interned("tag"))).isNull();
        refuses(
                "AttributeError: type object 'Boxed' has no attribute 'tag'",
                () -> Slots.delAttr(kind, "tag"));
        refuses(
                "AttributeError: type object 'Boxed' has no attribute 'nothere'",
                () -> Slots.delAttr(kind, "nothere"));
        refuses(
                "AttributeError: type object 'Boxed' has no attribute '5'",
                () -> ((PyClass) kind).write("5", null));
    }

    /**
     * The four refusals of the special names, each in the sentence its own C setter has.
     *
     * <p>{@code type_set_name} and {@code type_set_qualname} check the class is one a program can rename at
     * all, then read the value and name the class as it is called right now — which is why the sentence says
     * {@code Named}, not {@code type}. The null character is refused by the same half that files a good name;
     * {@code __bases__} says what it wanted and what it got; and the delete of a special name reaches the
     * immutable-type sentence with the class's own name in it.
     */
    @Test
    void theSpecialNamesRefuseInTheirOwnWords() {
        PyType kind = type("Named", new PyType[0]);
        refuses(
                "TypeError: can only assign string to Named.__name__, not 'int'",
                () -> Descriptors.set(entry(PyType.TYPE, "__name__"), kind, PyInt.of(5)));
        refuses(
                "ValueError: type name must not contain null characters",
                () -> Descriptors.set(entry(PyType.TYPE, "__name__"), kind, PyStr.of("a\0b")));
        refuses(
                "TypeError: can only assign string to Named.__qualname__, not 'int'",
                () -> Descriptors.set(entry(PyType.TYPE, "__qualname__"), kind, PyInt.of(5)));
        refuses(
                "TypeError: can only assign tuple to Named.__bases__, not NoneType",
                () -> Descriptors.set(entry(PyType.TYPE, "__bases__"), kind, PyNone.INSTANCE));
        refuses(
                "TypeError: can only assign tuple to Named.__bases__, not list",
                () ->
                        Descriptors.set(
                                entry(PyType.TYPE, "__bases__"), kind, PyList.of(PyType.OBJECT)));

        refuses(
                "TypeError: cannot delete '__doc__' attribute of immutable type 'Named'",
                () -> Slots.delAttr(kind, "__doc__"));
        refuses(
                "AttributeError: __abstractmethods__",
                () -> Slots.delAttr(type("Bare", new PyType[0]), "__abstractmethods__"));
        refuses(
                "AttributeError: __annotations__",
                () -> Slots.delAttr(type("Bare", new PyType[0]), "__annotations__"));
        refuses(
                "TypeError: cannot delete __annotate__ attribute",
                () -> Slots.delAttr(type("Bare", new PyType[0]), "__annotate__"));
    }

    /**
     * {@code __module__} is the one name whose setter clears a second name on the way through.
     *
     * <p>{@code type_set_module} pops {@code __firstlineno__} before filing the value, so the line the
     * statement was on is gone as soon as a decorator moves the class — and any value at all is filed,
     * because the setter never reads it.
     */
    @Test
    void writingTheModuleDropsTheFirstLine() {
        PyType kind = type("Moved", new PyType[0]);
        dictOf(kind).put(PyStr.interned("__firstlineno__"), PyInt.of(12));
        dictOf(kind).put(PyStr.interned("__module__"), PyStr.of("somewhere"));

        PyObject any = PyInt.of(7);
        Descriptors.set(entry(PyType.TYPE, "__module__"), kind, any);
        assertThat(dictOf(kind).get(PyStr.interned("__firstlineno__"))).isNull();
        assertThat(dictOf(kind).get(PyStr.interned("__module__"))).isSameAs(any);
        assertThat(Slots.getAttr(kind, "__module__")).isSameAs(any);

        PyObject sentence = PyStr.of("sentence");
        Descriptors.set(entry(PyType.TYPE, "__doc__"), kind, sentence);
        assertThat(dictOf(kind).get(PyStr.interned("__doc__"))).isSameAs(sentence);
        assertThat(textOf(kind, "__doc__")).isEqualTo("sentence");
    }
}
