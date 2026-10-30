package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fifteen names {@code descrobject.c} files for {@code property}, measured against CPython 3.14.8.
 *
 * <p>The corpus script reads the dictionary from Python and calls every row of it. What is pinned here is the
 * shape of the table and the machinery the rows run through. The shape comes out of four C lists walked in one
 * order: {@code add_tp_new_wrapper} files {@code __new__} before {@code type_ready_fill_dict} runs at all,
 * {@code add_operators} puts the four slots {@code slotdefs} lists — {@code tp_descr_get},
 * {@code tp_descr_set}, {@code tp_descr_set} again, {@code tp_init} — in that order, and only then come
 * {@code property_methods}, {@code property_members} and {@code property_getsetlist}, with
 * {@code type_dict_set_doc} last of all and skipping the name the members table already took. So one slot is
 * filed twice over, which is the whole of why a property outranks an instance dictionary even when the second
 * of its two rows can only refuse.
 *
 * <p>The four method rows are two families in one shape. {@code getter}, {@code setter} and {@code deleter} are
 * {@code METH_O} with a plain sentence beside them and no clinic table, so their {@code __text_signature__} is
 * cut out of the flag; {@code __set_name__} is {@code METH_VARARGS} whose {@code ml_doc} was written by hand in
 * the clinic shape, so its sentence and its table both come out of the text — and it is the only row of the
 * fifteen that counts in its own words rather than through a parser. The four members are one
 * {@code _Py_T_OBJECT} offset each, three of them {@code Py_READONLY}, and the fourth — {@code __doc__}, filed
 * with flag {@code 0} — is the one name of the fifteen a program can write, delete, and then read back as
 * {@code None}.
 *
 * <p>Two differences from the value types of the earlier slices are worth naming. {@code PyProperty_Type} fills
 * no {@code tp_repr} and no {@code tp_dictoffset}, so {@code repr(p)} is {@code object}'s and a property has no
 * store to keep a name in; and its {@code tp_doc} is {@code property_init}'s clinic text with the signature
 * block thrown away, which {@code type}'s own {@code __doc__} getset writes again as a fresh string on every
 * read — the class answers a 750-character {@code str} while its dictionary holds a
 * {@code member_descriptor} under the same name.
 */
class PropertyDictionaryTest {

    /** {@code property}'s dictionary: the new row, four slots, four methods, four members, two getsets. */
    private static final List<String> ORDER =
            List.of(
                    "__new__",
                    "__get__",
                    "__set__",
                    "__delete__",
                    "__init__",
                    "getter",
                    "setter",
                    "deleter",
                    "__set_name__",
                    "fget",
                    "fset",
                    "fdel",
                    "__doc__",
                    "__name__",
                    "__isabstractmethod__");

    private static final PyObject NUMBER = PyInt.of(5);
    private static final PyObject TEXT = PyStr.of("text");

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading the name. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** The kind one filed entry reads as: {@code wrapper_descriptor}, {@code member_descriptor}, … */
    private static String kind(PyObject member) {
        return member.getType().name();
    }

    /** The names a kind's own dictionary keeps, in the order it keeps them. */
    private static List<String> filed(PyType owner) {
        List<String> names = new ArrayList<>();
        for (PyObject key : TypeDict.declared(owner).getItems().keySet()) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** The sentence a value answers for a name, or {@code null} where it answers {@code None}. */
    private static String text(PyObject value, String name) {
        PyObject read = Slots.getAttr(value, name);
        return read == PyNone.INSTANCE ? null : ((PyStr) read).getValue();
    }

    /** {@code type(name, (object,), {members})}, which is where {@code __set_name__} is called. */
    private static PyType type(String name, Object... members) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < members.length; at += 2) {
            dict.put(PyStr.interned((String) members[at]), (PyObject) members[at + 1]);
        }
        return (PyType)
                PyType.TYPE.call(new PyObject[] {PyStr.of(name), PyTuple.of(), dict}, null);
    }

    /** {@code kind.__dict__[name](...args)}: a row of the dictionary, called with what the test names. */
    private static PyObject row(PyType owner, String name, PyObject... args) {
        return Slots.call(entry(owner, name), args, null);
    }

    /** {@code getattr(value, name)(...args)}: a row read through a value, which binds it first. */
    private static PyObject through(PyObject value, String name, PyObject... args) {
        return Slots.call(Slots.getAttr(value, name), args, null);
    }

    /** {@code property(fget, fset, fdel, doc)} as Python writes it, absent halves left as {@code None}. */
    private static PyObject property(PyObject getter, PyObject setter, PyObject deleter, PyObject doc) {
        return Slots.call(
                PyType.PROPERTY,
                new PyObject[] {orNone(getter), orNone(setter), orNone(deleter), orNone(doc)},
                null);
    }

    private static PyObject orNone(PyObject value) {
        return value == null ? PyNone.INSTANCE : value;
    }

    /** A getter that answers {@code 7}, and, when told, says what it is. */
    private static PyNativeFunction getter(String name, String sentence) {
        return new PyNativeFunction(name, (args, kwNames) -> PyInt.of(7)).documented(sentence);
    }

    private static PyNativeFunction half(String name) {
        return new PyNativeFunction(name, (args, kwNames) -> PyNone.INSTANCE);
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

    /**
     * The four C lists in the order {@code type_ready_fill_dict} walks them, and the two rows that are one slot.
     *
     * <p>Fifteen names, in five families: the {@code METH_STATIC} entry {@code tp_new_wrapper} files ahead of
     * all of them, four {@code wrapper_descriptor}s out of {@code add_operators}, four {@code
     * method_descriptor}s out of {@code property_methods}, four {@code member_descriptor}s out of {@code
     * property_members}, and the two {@code getset_descriptor}s of {@code property_getsetlist}. {@code __set__}
     * and {@code __delete__} are the same pointer filed twice, the second with nothing handed over, so a write
     * and a delete of a getter-only property differ only in their last word. No {@code tp_repr} and no {@code
     * tp_call} means no row under either name — and so no {@code __dict__} row either, since a property keeps
     * no store.
     */
    @Test
    void fourListsInOneOrderWithTheSameSlotFiledTwiceOver() {
        assertThat(filed(PyType.PROPERTY)).isEqualTo(ORDER);
        assertThat(ORDER).hasSize(15);

        assertThat(kind(entry(PyType.PROPERTY, "__new__"))).isEqualTo("builtin_function_or_method");
        for (String name : List.of("__get__", "__set__", "__delete__", "__init__")) {
            assertThat(kind(entry(PyType.PROPERTY, name))).as(name).isEqualTo("wrapper_descriptor");
        }
        for (String name : List.of("getter", "setter", "deleter", "__set_name__")) {
            assertThat(kind(entry(PyType.PROPERTY, name))).as(name).isEqualTo("method_descriptor");
        }
        for (String name : List.of("fget", "fset", "fdel", "__doc__")) {
            assertThat(kind(entry(PyType.PROPERTY, name))).as(name).isEqualTo("member_descriptor");
        }
        for (String name : List.of("__name__", "__isabstractmethod__")) {
            assertThat(kind(entry(PyType.PROPERTY, name))).as(name).isEqualTo("getset_descriptor");
        }
        for (String name : List.of("__repr__", "__call__", "__dict__")) {
            assertThat(entry(PyType.PROPERTY, name)).as(name).isNull();
        }

        // The four `repr`s CPython words out of the shape each family was registered with.
        assertThat(entry(PyType.PROPERTY, "__get__").repr())
                .isEqualTo("<slot wrapper '__get__' of 'property' objects>");
        assertThat(entry(PyType.PROPERTY, "__delete__").repr())
                .isEqualTo("<slot wrapper '__delete__' of 'property' objects>");
        assertThat(entry(PyType.PROPERTY, "getter").repr())
                .isEqualTo("<method 'getter' of 'property' objects>");
        assertThat(entry(PyType.PROPERTY, "fdel").repr())
                .isEqualTo("<member 'fdel' of 'property' objects>");
        assertThat(entry(PyType.PROPERTY, "__isabstractmethod__").repr())
                .isEqualTo("<attribute '__isabstractmethod__' of 'property' objects>");

        // The two rows of one slot, refused in the same words but counted differently: `descr_delete` hands
        // `check_num_args` no name to quote, so its two refusals are the barest in the dictionary.
        PyObject both = property(getter("get", null), half("setx"), null, null);
        PyObject holds = type("Holds", "span", both);
        PyObject value = Slots.call(holds, new PyObject[0], null);
        refuses(
                "TypeError: __set__ expected 2 arguments, got 1",
                () -> through(both, "__set__", value));
        refuses("TypeError: expected 1 argument, got 0", () -> through(both, "__delete__"));
        refuses(
                "TypeError: expected 1 argument, got 2",
                () -> through(both, "__delete__", value, NUMBER));
        refuses(
                "TypeError: wrapper __delete__() takes no keyword arguments",
                () ->
                        Slots.call(
                                Slots.getAttr(both, "__delete__"),
                                new PyObject[] {value},
                                PyTuple.of(PyStr.interned("instance"))));
        // The delete row is the write slot with nothing handed over, so it refuses by name of the property.
        refuses(
                "AttributeError: property 'span' of 'Holds' object has no deleter",
                () -> row(PyType.PROPERTY, "__delete__", both, value));
        assertThat(row(PyType.PROPERTY, "__set__", both, value, NUMBER)).isSameAs(PyNone.INSTANCE);
    }

    /**
     * The five rows that come off a slot or a clinic text, and the signatures each answers with.
     *
     * <p>Only {@code __init__} of the four {@code wrapper_descriptor}s takes keywords, because that is the one
     * slot {@code add_operators} files with {@code PyWrapperFlag_KEYWORDS} set — and its table is the generic
     * {@code ($self, /, *args, *kwargs)} that a slot with no clinic of its own is given. The other three name
     * their arguments, and {@code __get__}'s third field says {@code owner=None}, which is what a two-field
     * {@code slotdefs} entry reads as. {@code __new__} is filed by {@code tp_new_wrapper} as a plain {@code
     * METH_STATIC} entry: it is the only row of the fifteen that is not a descriptor, so it answers no {@code
     * __objclass__} at all.
     */
    @Test
    void fiveSlotRowsTheirSentencesAndTheSignatureEachTableGives() {
        assertThat(text(PyType.PROPERTY, "__qualname__")).isEqualTo("property");

        PyObject created = entry(PyType.PROPERTY, "__new__");
        assertThat(text(created, "__doc__"))
                .isEqualTo("Create and return a new object.  See help(type) for accurate signature.");
        assertThat(text(created, "__text_signature__")).isEqualTo("($type, *args, **kwargs)");
        assertThat(text(created, "__name__")).isEqualTo("__new__");
        assertThat(text(created, "__qualname__")).isEqualTo("property.__new__");
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__objclass__'",
                () -> Slots.getAttr(created, "__objclass__"));

        // The three sentences `slotdefs` writes for the descriptor slots, and the one for `tp_init`.
        assertThat(text(entry(PyType.PROPERTY, "__get__"), "__doc__"))
                .isEqualTo("Return an attribute of instance, which is of type owner.");
        assertThat(text(entry(PyType.PROPERTY, "__set__"), "__doc__"))
                .isEqualTo("Set an attribute of instance to value.");
        assertThat(text(entry(PyType.PROPERTY, "__delete__"), "__doc__"))
                .isEqualTo("Delete an attribute of instance.");
        assertThat(text(entry(PyType.PROPERTY, "__init__"), "__doc__"))
                .isEqualTo("Initialize self.  See help(type(self)) for accurate signature.");
        assertThat(text(entry(PyType.PROPERTY, "__get__"), "__text_signature__"))
                .isEqualTo("($self, instance, owner=None, /)");
        assertThat(text(entry(PyType.PROPERTY, "__set__"), "__text_signature__"))
                .isEqualTo("($self, instance, value, /)");
        assertThat(text(entry(PyType.PROPERTY, "__delete__"), "__text_signature__"))
                .isEqualTo("($self, instance, /)");
        assertThat(text(entry(PyType.PROPERTY, "__init__"), "__text_signature__"))
                .isEqualTo("($self, /, *args, **kwargs)");

        // A wrapper row judges its receiver before it counts, so the bad one is named by the slot.
        for (String name : List.of("__get__", "__set__", "__delete__", "__init__")) {
            refuses(
                    "TypeError: descriptor '" + name + "' of 'property' object needs an argument",
                    () -> row(PyType.PROPERTY, name));
            refuses(
                    "TypeError: descriptor '" + name + "' requires a 'property' object"
                            + " but received a 'int'",
                    () -> row(PyType.PROPERTY, name, NUMBER, NUMBER, NUMBER));
        }
    }

    /**
     * The four method rows, and the two ways a sentence and a table arrive.
     *
     * <p>{@code getter}, {@code setter} and {@code deleter} are {@code METH_O} with one plain line beside them,
     * so the row's {@code __doc__} is that line and its {@code __text_signature__} is what {@code
     * signatureFromFlags} makes of the flag. {@code __set_name__} is {@code METH_VARARGS} whose {@code ml_doc}
     * was written by hand in the clinic shape, so the read splits the same text the way {@code _PyMethodDoc}
     * does: the table for the signature, the last paragraph for the sentence. All four carry {@code __objclass__}
     * and a qualified name, and all four bind a property into a {@code builtin_function_or_method} when read
     * through one.
     */
    @Test
    void threeSentencesFromTheFlagAndOneWrittenByHandInTheClinicShape() {
        assertThat(text(entry(PyType.PROPERTY, "getter"), "__doc__"))
                .isEqualTo("Descriptor to obtain a copy of the property with a different getter.");
        assertThat(text(entry(PyType.PROPERTY, "setter"), "__doc__"))
                .isEqualTo("Descriptor to obtain a copy of the property with a different setter.");
        assertThat(text(entry(PyType.PROPERTY, "deleter"), "__doc__"))
                .isEqualTo("Descriptor to obtain a copy of the property with a different deleter.");
        // The hand-written text: everything under the `--` rule is the sentence, and the rule is what is cut.
        assertThat(text(entry(PyType.PROPERTY, "__set_name__"), "__doc__"))
                .isEqualTo("Method to set name of a property.");

        for (String name : List.of("getter", "setter", "deleter")) {
            assertThat(text(entry(PyType.PROPERTY, name), "__text_signature__"))
                    .as(name)
                    .isEqualTo("($self, object, /)");
        }
        assertThat(text(entry(PyType.PROPERTY, "__set_name__"), "__text_signature__"))
                .isEqualTo("($self, owner, name, /)");
        for (String name : List.of("getter", "setter", "deleter", "__set_name__")) {
            PyObject rowEntry = entry(PyType.PROPERTY, name);
            assertThat(text(rowEntry, "__name__")).as(name).isEqualTo(name);
            assertThat(text(rowEntry, "__qualname__"))
                    .as(name)
                    .isEqualTo("property." + name);
            assertThat(Slots.getAttr(rowEntry, "__objclass__")).as(name).isSameAs(PyType.PROPERTY);
            // Bound through a value, a method row is a built-in method with nothing to say about its owner.
            assertThat(kind(Slots.getAttr(property(null, null, null, null), name)))
                    .as(name)
                    .isEqualTo("builtin_function_or_method");
        }

        PyObject held = property(getter("get", null), null, null, null);
        // Called bare with a property in front, the three copies make a copy; `__set_name__` needs two.
        assertThat(row(PyType.PROPERTY, "getter", held, half("other")).getType())
                .isSameAs(PyType.PROPERTY);
        refuses(
                "TypeError: __set_name__() takes 2 positional arguments but 1 were given",
                () -> row(PyType.PROPERTY, "__set_name__", held, NUMBER));
        refuses(
                "TypeError: __set_name__() takes 2 positional arguments but 3 were given",
                () -> row(PyType.PROPERTY, "__set_name__", held, NUMBER, NUMBER, NUMBER));
        // Unbound, the four name the qualified row they belong to, and a receiver of the wrong kind is
        // refused by the descriptor rather than by the body behind it.
        for (String name : List.of("getter", "setter", "deleter", "__set_name__")) {
            refuses(
                    "TypeError: unbound method property." + name + "() needs an argument",
                    () -> row(PyType.PROPERTY, name));
            refuses(
                    "TypeError: descriptor '" + name + "' for 'property' objects"
                            + " doesn't apply to a 'str' object",
                    () -> row(PyType.PROPERTY, name, TEXT, NUMBER));
        }
    }

    /**
     * The three {@code Py_READONLY} members over the fields, and the fourth member that writes.
     *
     * <p>All four are one {@code _Py_T_OBJECT} offset, so a null field is read as {@code None} rather than
     * complained about — which is what {@code __new__} leaves behind and what {@code del p.__doc__} leaves. The
     * first three are filed {@code Py_READONLY} and answer one sentence for both a write and a delete; the
     * fourth, {@code __doc__}, is filed with flag {@code 0} and so is the only name of the fifteen a program
     * owns. None of the four has a sentence beside it, so each row's own {@code __doc__} is {@code None}, and a
     * member is not callable: reading the row out of the dictionary and calling it is a complaint about the row.
     */
    @Test
    void threeMembersReadOneFieldEachAndTheFourthIsTheNameAProgramOwns() {
        for (String name : List.of("fget", "fset", "fdel", "__doc__")) {
            PyObject member = entry(PyType.PROPERTY, name);
            assertThat(Slots.getAttr(member, "__doc__")).as(name).isSameAs(PyNone.INSTANCE);
            assertThat(text(member, "__name__")).as(name).isEqualTo(name);
            assertThat(text(member, "__qualname__")).as(name).isEqualTo("property." + name);
            assertThat(Slots.getAttr(member, "__objclass__")).as(name).isSameAs(PyType.PROPERTY);
            refuses(
                    "TypeError: 'member_descriptor' object is not callable",
                    () ->
                            Slots.call(
                                    member,
                                    new PyObject[] {property(null, null, null, null)},
                                    null));
        }
        assertThat(entry(PyType.PROPERTY, "fget").repr())
                .isEqualTo("<member 'fget' of 'property' objects>");

        PyNativeFunction get = getter("get", "the getter's own sentence");
        PyObject value = property(get, half("setx"), null, null);
        assertThat(Slots.getAttr(value, "fget")).isSameAs(get);
        assertThat(Slots.getAttr(value, "fset").getType().name())
                .isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(value, "fdel")).isSameAs(PyNone.INSTANCE);

        for (String name : List.of("fget", "fset", "fdel")) {
            refuses("AttributeError: readonly attribute", () -> Slots.setAttr(value, name, TEXT));
            refuses("AttributeError: readonly attribute", () -> Slots.delAttr(value, name));
        }
        assertThat(Slots.getAttr(value, "fdel"))
                .as("the refusal deleted nothing")
                .isSameAs(PyNone.INSTANCE);

        // The writable one: a write lands, a delete empties the field, and an empty field reads as None.
        Slots.setAttr(value, "__doc__", PyStr.of("written"));
        assertThat(Slots.getAttr(value, "__doc__")).isEqualTo(PyStr.of("written"));
        Slots.delAttr(value, "__doc__");
        assertThat(Slots.getAttr(value, "__doc__")).isSameAs(PyNone.INSTANCE);
        Slots.delAttr(value, "__doc__");
        assertThat(Slots.getAttr(value, "__doc__")).isSameAs(PyNone.INSTANCE);
        // The getter's sentence is taken at the init and not given back by a delete of the field.
        assertThat(Slots.getAttr(property(get, null, null, null), "__doc__"))
                .isEqualTo(PyStr.of("the getter's own sentence"));
    }

    /**
     * {@code property_name} and {@code property_get___isabstractmethod__} — the two getsets.
     *
     * <p>Both file no sentence, and both are read through the descriptor the way every other getset is. {@code
     * __name__} has a setter, so a write files the name and a delete uncovers the fallback under it rather than
     * removing it: what is left is the getter's own {@code __name__}, and where there is no name and no getter
     * the read is a refusal rather than {@code None}. {@code __isabstractmethod__} files none, so its write and
     * its delete say the same sentence of a getset that cannot be written — and the flag itself asks the three
     * functions in order, which is why a property with only a setter says it is abstract.
     */
    @Test
    void theNameIsGivenOrBorrowedAndTheFlagAsksTheThreeFunctionsInOrder() {
        PyNativeFunction get = getter("get", null);
        PyObject value = property(get, null, null, null);
        assertThat(Slots.getAttr(value, "__name__")).isEqualTo(PyStr.of("get"));
        Slots.setAttr(value, "__name__", PyStr.of("given"));
        assertThat(Slots.getAttr(value, "__name__")).isEqualTo(PyStr.of("given"));
        Slots.delAttr(value, "__name__");
        assertThat(Slots.getAttr(value, "__name__")).as("the fallback").isEqualTo(PyStr.of("get"));

        // `property_name` reads the getter's name and nothing else: a setter's name is no fallback.
        refuses(
                "AttributeError: 'property' object has no attribute '__name__'",
                () -> Slots.getAttr(property(null, half("setx"), null, null), "__name__"));
        refuses(
                "AttributeError: 'property' object has no attribute '__name__'",
                () -> Slots.getAttr(property(null, null, null, null), "__name__"));

        refuses(
                "AttributeError: attribute '__isabstractmethod__' of 'property' objects is not writable",
                () -> Slots.setAttr(value, "__isabstractmethod__", PyBool.TRUE));
        refuses(
                "AttributeError: attribute '__isabstractmethod__' of 'property' objects is not writable",
                () -> Slots.delAttr(value, "__isabstractmethod__"));
        assertThat(Slots.getAttr(value, "__isabstractmethod__")).isSameAs(PyBool.FALSE);

        // `property_get___isabstractmethod__` asks the three functions in order, and the flag is read off
        // whichever one answers, so a property with only a setter or only a deleter is still an abstract one.
        PyDict says = PyDict.empty();
        says.put(PyStr.interned("__isabstractmethod__"), PyBool.TRUE);
        PyType saysIt =
                (PyType)
                        PyType.TYPE.call(
                                new PyObject[] {PyStr.of("Says"), PyTuple.of(), says}, null);
        assertThat(Slots.getAttr(property(saysIt, null, null, null), "__isabstractmethod__"))
                .as("the getter")
                .isSameAs(PyBool.TRUE);
        assertThat(Slots.getAttr(property(null, saysIt, null, null), "__isabstractmethod__"))
                .as("the setter alone")
                .isSameAs(PyBool.TRUE);
        assertThat(Slots.getAttr(property(null, null, saysIt, null), "__isabstractmethod__"))
                .as("the deleter alone")
                .isSameAs(PyBool.TRUE);
        assertThat(Slots.getAttr(property(get, null, null, null), "__isabstractmethod__"))
                .as("a getter that says nothing")
                .isSameAs(PyBool.FALSE);

        // The two rows read as descriptors, and a receiver of the wrong kind is refused by the descriptor.
        for (String name : List.of("__name__", "__isabstractmethod__")) {
            PyObject member = entry(PyType.PROPERTY, name);
            assertThat(Slots.getAttr(member, "__doc__")).as(name).isSameAs(PyNone.INSTANCE);
            assertThat(text(member, "__qualname__")).as(name).isEqualTo("property." + name);
            assertThat(Slots.getAttr(member, "__objclass__")).as(name).isSameAs(PyType.PROPERTY);
            // A getset is not a function: calling the row itself is a complaint about the row, and the read
            // goes through its `__get__` — which is the same refusal a member answers.
            refuses(
                    "TypeError: 'getset_descriptor' object is not callable",
                    () -> Slots.call(member, new PyObject[] {value}, null));
            PyObject read = Slots.getAttr(member, "__get__");
            assertThat(Slots.call(read, new PyObject[] {value}, null)).as(name).isNotNull();
            refuses(
                    "TypeError: descriptor '" + name + "' for 'property' objects"
                            + " doesn't apply to a 'str' object",
                    () -> Slots.call(read, new PyObject[] {TEXT}, null));
        }
    }

    /**
     * {@code PyType_GenericNew} behind the {@code __new__} row, and {@code property_init} behind its own.
     *
     * <p>The {@code __new__} row is {@code tp_new_wrapper} over {@code PyType_GenericNew}: it allocates, files
     * nothing, and reads no argument beyond the first, so the value it hands back has four {@code None}s, a
     * missing name, no dictionary, and its getter's sentence nowhere — and a keyword beside the type is
     * ignored. Its three gates come in CPython's order, the count of what arrived first, then the type, then the
     * subtype; the count sentence and the type sentence are the wrapper's own words and name the qualified row.
     * {@code __init__} is the only row that takes keywords, because {@code tp_init} is filed with {@code
     * PyWrapperFlag_KEYWORDS}, and its arguments are read by {@code property_init} through the same parser the
     * builtin uses — which is why both routes say {@code property() takes at most 4 arguments}.
     *
     * <p>{@code property_init_impl} empties the docstring and the name before it fills them again, so {@code
     * p.__init__(f)} is a refill of the same object: the docstring it files is the {@code doc} argument or, when
     * that says nothing, the getter's, and the flag that records which decides what a later copy does.
     */
    @Test
    void theConstructorFilesNothingAndTheInitialiserEmptiesBeforeItFills() {
        PyObject made = row(PyType.PROPERTY, "__new__", PyType.PROPERTY);
        assertThat(made.getType()).isSameAs(PyType.PROPERTY);
        for (String name : List.of("fget", "fset", "fdel", "__doc__")) {
            assertThat(Slots.getAttr(made, name)).as(name).isSameAs(PyNone.INSTANCE);
        }
        assertThat(Slots.getAttr(made, "__isabstractmethod__")).isSameAs(PyBool.FALSE);
        refuses(
                "AttributeError: 'property' object has no attribute '__name__'",
                () -> Slots.getAttr(made, "__name__"));
        refuses(
                "AttributeError: 'property' object has no attribute '__dict__'",
                () -> Slots.getAttr(made, "__dict__"));
        // The class is read back as the property itself: `property_descr_get` asks None for not-given.
        assertThat(row(PyType.PROPERTY, "__get__", made, PyNone.INSTANCE, PyType.INT))
                .isSameAs(made);

        // Two of the three extras are ignored whatever they are, because `tp_new_wrapper` hands tp_new the type.
        assertThat(
                        Slots.call(
                                entry(PyType.PROPERTY, "__new__"),
                                new PyObject[] {PyType.PROPERTY, NUMBER},
                                PyTuple.of(PyStr.interned("x"))))
                .isInstanceOf(PyProperty.class);
        refuses(
                "TypeError: property.__new__(): not enough arguments",
                () -> row(PyType.PROPERTY, "__new__"));
        refuses(
                "TypeError: property.__new__(X): X is not a type object (int)",
                () -> row(PyType.PROPERTY, "__new__", NUMBER));
        refuses(
                "TypeError: property.__new__(int): int is not a subtype of property",
                () -> row(PyType.PROPERTY, "__new__", PyType.INT));

        // `property_init` through the row it is filed under, over a value that had everything.
        PyNativeFunction get = getter("get", "the getter's own sentence");
        PyObject filled = property(get, half("setx"), half("drop"), PyStr.of("written"));
        PyObject init = entry(PyType.PROPERTY, "__init__");
        PyNativeFunction other = getter("other", null);
        Slots.call(
                init,
                new PyObject[] {filled, other, other, other, PyStr.of("again")},
                PyTuple.of(PyStr.interned("doc")));
        // The row refills the object it was handed rather than making a new one.
        assertThat(Slots.getAttr(filled, "fget")).as("refilled in place").isSameAs(other);
        assertThat(Slots.getAttr(filled, "fset")).isSameAs(other);
        assertThat(Slots.getAttr(filled, "__doc__")).isEqualTo(PyStr.of("again"));
        // The name went with the docstring, so the fallback is what the new getter answers.
        assertThat(Slots.getAttr(filled, "__name__")).isEqualTo(PyStr.of("other"));
        Slots.call(init, new PyObject[] {filled}, null);
        assertThat(Slots.getAttr(filled, "fget"))
                .as("every half emptied")
                .isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(filled, "__doc__")).isSameAs(PyNone.INSTANCE);
        // `doc=None` does not mean "no docstring" but "take the getter's", and nothing raises the flag.
        Slots.call(init, new PyObject[] {filled, get, PyNone.INSTANCE}, null);
        assertThat(Slots.getAttr(filled, "__doc__"))
                .isEqualTo(PyStr.of("the getter's own sentence"));

        // The count and the keyword, both in the builtin's own words: `property` is the qualified name.
        refuses(
                "TypeError: property() takes at most 4 arguments (5 given)",
                () -> Slots.call(init, new PyObject[] {made, get, get, get, get, get}, null));
        refuses(
                "TypeError: property() got an unexpected keyword argument 'nothing'",
                () ->
                        Slots.call(
                                init,
                                new PyObject[] {made, get},
                                PyTuple.of(PyStr.interned("nothing"))));
        refuses(
                "TypeError: descriptor '__init__' of 'property' object needs an argument",
                () -> row(PyType.PROPERTY, "__init__"));
    }

    /**
     * {@code property_copy}: the three functions with one of them replaced, put through a fresh init.
     *
     * <p>A copy keeps whichever halves the call did not name, keeps the name the class gave it, and asks the
     * docstring question twice: when the sentence was borrowed off the getter the copy hands {@code None} as
     * {@code doc} so the init goes and asks the getter it now has, and when a person wrote one it is kept
     * whichever half is swapped. Both refusals of a copy are {@code METH_O}'s, and say the qualified row — the
     * count because {@code exactly_one_arg} fires first, the keyword because the check for a keyword is made
     * before the count of a {@code METH_O} call is.
     */
    @Test
    void aCopyKeepsTheHalvesItWasNotGivenTheNameItWasGivenAndTheSentenceOnlyIfItWroteIt() {
        PyNativeFunction get = getter("get", "the getter's own sentence");
        PyNativeFunction other = getter("other", "another sentence");
        PyObject named = property(null, null, null, null);
        PyType holder = type("Holds", "span", property(get, null, half("drop"), null));
        PyObject given = Slots.getAttr(holder, "span");

        PyObject borrowed =
                Slots.call(Slots.getAttr(given, "getter"), new PyObject[] {other}, null);
        assertThat(Slots.getAttr(borrowed, "__doc__")).isEqualTo(PyStr.of("another sentence"));
        assertThat(Slots.getAttr(borrowed, "fget")).isSameAs(other);
        assertThat(Slots.getAttr(borrowed, "fdel"))
                .as("the half the call did not name")
                .isEqualTo(Slots.getAttr(given, "fdel"));
        assertThat(Slots.getAttr(borrowed, "__name__"))
                .as("the name carried over")
                .isEqualTo(PyStr.of("span"));
        assertThat(borrowed).isNotSameAs(given);

        PyObject written = property(get, null, null, PyStr.of("explicit"));
        PyObject sideways = Slots.call(Slots.getAttr(written, "setter"), new PyObject[] {other}, null);
        assertThat(Slots.getAttr(sideways, "__doc__"))
                .as("a sentence a person wrote is kept")
                .isEqualTo(PyStr.of("explicit"));
        // With nothing to keep, a copy of a property whose getter had no sentence has none either.
        PyObject silent = property(half("docless"), null, null, null);
        PyObject retaken = Slots.call(Slots.getAttr(silent, "getter"), new PyObject[] {get}, null);
        assertThat(Slots.getAttr(retaken, "__doc__"))
                .as("the copy goes back to the getter it now has")
                .isEqualTo(PyStr.of("the getter's own sentence"));
        PyObject widened = Slots.call(Slots.getAttr(silent, "setter"), new PyObject[] {other}, null);
        assertThat(Slots.getAttr(widened, "__doc__"))
                .as("the same getter, still with nothing to say")
                .isSameAs(PyNone.INSTANCE);

        for (String name : List.of("getter", "setter", "deleter")) {
            PyObject rowEntry = entry(PyType.PROPERTY, name);
            refuses(
                    "TypeError: property." + name + "() takes exactly one argument (2 given)",
                    () -> Slots.call(rowEntry, new PyObject[] {named, other, other}, null));
            refuses(
                    "TypeError: property." + name + "() takes no keyword arguments",
                    () ->
                            Slots.call(
                                    rowEntry,
                                    new PyObject[] {named, other},
                                    PyTuple.of(PyStr.interned("object"))));
        }
    }
}
