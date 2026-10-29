package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The five {@code PyDescrObject} dictionaries, measured against CPython 3.14.8.
 *
 * <p>The corpus scripts read these tables from Python: {@code vars(method_descriptor)} and every row's call.
 * What is pinned here is the machinery behind those reads. The first is the order the four C lists are walked
 * in — {@code type_ready_fill_dict} runs {@code add_operators} before the three lists of the descriptor's own,
 * so the filled {@code tp_repr}, {@code tp_call} and {@code tp_descr_get} / {@code tp_descr_set} pointers are
 * {@code wrapper_descriptor}s <em>first</em> and {@code descr_methods}, {@code descr_members} and the kind's own
 * {@code tp_getset} are copied after them — because a script reads the names and cannot say which of the four
 * lists a row came from. The second is {@code type_get_doc}'s second half: a kind
 * with no {@code tp_doc} answers {@code __doc__} by reading its own dictionary and running the entry through
 * its descriptor, which returns the entry, so the answer is the {@code getset_descriptor} the kind files rather
 * than a sentence — and it is the same step that lets a class hold a {@code property} under {@code __doc__} and
 * read the property back off the class while its values read what the property says. The third is the two gates
 * every slot row runs before it looks at what it was handed: {@code PyArg_UnpackTuple} over the tail after the
 * receiver, then keywords, then the receiver's own class — and only the three kinds whose getter checks the
 * class get to the third. The fourth is {@code descr_reduce}, which three of the five file and two do not, and
 * the refusal of a write to a name a descriptor carries but cannot write.
 */
class DescriptorDictionaryTest {

    /** The nine names of {@code PyMethodDescr_Type} and {@code PyWrapperDescr_Type}. */
    private static final List<String> METHOD_ORDER =
            List.of(
                    "__repr__",
                    "__call__",
                    "__get__",
                    "__reduce__",
                    "__objclass__",
                    "__name__",
                    "__doc__",
                    "__qualname__",
                    "__text_signature__");

    /** {@code PyClassMethodDescr_Type}: the nine without pickle's row, which is its only {@code tp_methods}. */
    private static final List<String> CLASS_METHOD_ORDER =
            List.of(
                    "__repr__",
                    "__call__",
                    "__get__",
                    "__objclass__",
                    "__name__",
                    "__doc__",
                    "__qualname__",
                    "__text_signature__");

    /** The eight of the two writing kinds: no {@code tp_call}, no {@code tp_methods}, three names fewer. */
    private static final List<String> WRITING_ORDER =
            List.of(
                    "__repr__",
                    "__get__",
                    "__set__",
                    "__delete__",
                    "__objclass__",
                    "__name__",
                    "__doc__",
                    "__qualname__");

    /** {@code PyMemberDescr_Type}: the same eight with {@code descr_methods}' row after {@code __delete__}. */
    private static final List<String> MEMBER_ORDER =
            List.of(
                    "__repr__",
                    "__get__",
                    "__set__",
                    "__delete__",
                    "__reduce__",
                    "__objclass__",
                    "__name__",
                    "__doc__",
                    "__qualname__");

    /** The 79 characters of {@code type_doc}: two constructor lines and no {@code --} rule to cut on. */
    private static final String TYPE_DOC =
            "type(object) -> the object's type\n"
                    + "type(name, bases, dict, **kwds) -> a new type";

    private static final PyObject NUMBER = PyStr.of("no");
    private static final PyObject TEXT = PyStr.of("text");

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading the name. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** The entry a class keeps under {@code name}: its own namespace, which no table built. */
    private static PyObject held(PyType owner, String name) {
        return owner.namespace().get(PyStr.interned(name));
    }

    /** The kind one filed entry reads as: {@code wrapper_descriptor}, {@code member_descriptor}, … */
    private static String kind(PyObject member) {
        return member.getType().name();
    }

    /** The names a kind's own dictionary keeps, in the order it keeps them. */
    private static List<String> filed(PyType owner) {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(owner, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** The sentence a value answers for a name, or {@code null} where it answers {@code None}. */
    private static String text(PyObject value, String name) {
        PyObject read = Slots.getAttr(value, name);
        return read == PyNone.INSTANCE ? null : ((PyStr) read).getValue();
    }

    /** {@code row.__get__(None, owner)}: the read a class makes, with no instance and its own type. */
    private static PyObject readThrough(PyObject row) {
        return Slots.call(
                Slots.getAttr(row, "__get__"),
                new PyObject[] {PyNone.INSTANCE, Slots.getAttr(row, "__objclass__")},
                null);
    }

    /** {@code getattr(row, slot)(...args)}: a slot row of the kind, called with what the test names. */
    private static Object slot(PyObject row, String slot, PyObject... args) {
        return Slots.call(Slots.getAttr(row, slot), args, null);
    }

    /** {@code getattr(row, slot)(row, x=1)}: the same call with a keyword the row never asked for. */
    private static Object slotKeyword(PyObject row, String slot) {
        return Slots.call(
                Slots.getAttr(row, slot), new PyObject[] {row}, PyTuple.of(PyStr.interned("x")));
    }

    /** {@code type(name, (base,), {members})}, with a member list two items long at a step. */
    private static PyType type(String name, PyType base, Object... members) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < members.length; at += 2) {
            dict.put(PyStr.interned((String) members[at]), (PyObject) members[at + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(base), dict}, null);
    }

    /** {@code property(lambda self: 'no')}: the member a class body can put under {@code __doc__}. */
    private static PyObject property() {
        return new PyProperty(
                new PyNativeFunction("said", (args, kwNames) -> NUMBER),
                PyNone.INSTANCE,
                PyNone.INSTANCE,
                PyNone.INSTANCE);
    }

    /** The words CPython finds for a row handed one of its own kind as the instance. */
    private static String doesNotApply(PyObject row) {
        return "TypeError: descriptor '"
                + text(row, "__name__")
                + "' for '"
                + ((PyType) Slots.getAttr(row, "__objclass__")).name()
                + "' objects doesn't apply to a '"
                + kind(row)
                + "' object";
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

    /** A method row, a slot row, a class-method row, a getset and a member field. */
    private static PyObject methodRow() {
        return entry(PyType.INT, "bit_length");
    }

    private static PyObject wrapperRow() {
        return entry(PyType.OBJECT, "__init__");
    }

    private static PyObject classMethodRow() {
        return entry(PyType.OBJECT, "__subclasshook__");
    }

    private static PyObject getsetRow() {
        return entry(ExceptionTypes.BASE_EXCEPTION, "args");
    }

    private static PyObject memberRow() {
        return entry(PyType.TYPE, "__basicsize__");
    }

    /**
     * The five tables, in the order the four C lists were walked.
     *
     * <p>Nine, nine, eight, eight, nine. The two counts of eight are the two kinds that file no {@code
     * tp_methods}, and {@code classmethod_descriptor} loses pickle's row while keeping {@code __call__} from
     * its {@code tp_call} — which is why its {@code __reduce__} is read from {@code object} and answers a
     * refusal instead of a pair. The two writing kinds file {@code __set__} and {@code __delete__} from the one
     * {@code tp_descr_set} pointer, so one filled slot is two names, and their {@code tp_getset} stops at
     * {@code __qualname__} where the other three carry on to the signature.
     */
    @Test
    void fourCListsFillFiveDictionariesInAnOrderAPythonReadCannotSee() {
        assertThat(filed(PyType.METHOD_DESCRIPTOR)).isEqualTo(METHOD_ORDER);
        assertThat(filed(PyType.WRAPPER_DESCRIPTOR)).isEqualTo(METHOD_ORDER);
        assertThat(filed(PyType.CLASS_METHOD_DESCRIPTOR)).isEqualTo(CLASS_METHOD_ORDER);
        assertThat(filed(PyType.GETSET_DESCRIPTOR)).isEqualTo(WRITING_ORDER);
        assertThat(filed(PyType.MEMBER_DESCRIPTOR)).isEqualTo(MEMBER_ORDER);

        for (PyType owner :
                List.of(
                        PyType.METHOD_DESCRIPTOR,
                        PyType.WRAPPER_DESCRIPTOR,
                        PyType.CLASS_METHOD_DESCRIPTOR)) {
            assertThat(kind(entry(owner, "__repr__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__call__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__get__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__objclass__"))).isEqualTo("member_descriptor");
            assertThat(kind(entry(owner, "__name__"))).isEqualTo("member_descriptor");
            assertThat(kind(entry(owner, "__doc__"))).isEqualTo("getset_descriptor");
            assertThat(kind(entry(owner, "__text_signature__"))).isEqualTo("getset_descriptor");
        }
        for (PyType owner : List.of(PyType.GETSET_DESCRIPTOR, PyType.MEMBER_DESCRIPTOR)) {
            assertThat(kind(entry(owner, "__set__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__delete__"))).isEqualTo("wrapper_descriptor");
            assertThat(kind(entry(owner, "__get__"))).isEqualTo("wrapper_descriptor");
            assertThat(entry(owner, "__call__")).as(owner.name()).isNull();
            // The two lists stop before the third name, so there is no row for the signature to read off —
            // and the class still answers the name, because `type` files it above the whole family.
            assertThat(entry(owner, "__text_signature__")).as(owner.name()).isNull();
            assertThat(Slots.getAttr(owner, "__text_signature__")).isSameAs(PyNone.INSTANCE);
        }
        // One row separates the two kinds that write.
        assertThat(kind(entry(PyType.MEMBER_DESCRIPTOR, "__reduce__")))
                .isEqualTo("method_descriptor");
        assertThat(entry(PyType.GETSET_DESCRIPTOR, "__reduce__")).isNull();
        assertThat(entry(PyType.CLASS_METHOD_DESCRIPTOR, "__reduce__")).isNull();
    }

    /**
     * {@code type_get_doc}, both halves.
     *
     * <p>A static type with a {@code tp_doc} answers the sentence parsed out of it; one without answers the
     * {@code __doc__} entry of its own dictionary run through its own descriptor, and a getset row with no
     * instance is the row. {@code type} is the neighbour with both — a sentence of its own and a row under the
     * same name — and the sentence wins, which is what the transcription of {@code type_doc} is for.
     */
    @Test
    void aKindWithNoSentenceOfItsOwnAnswersTheRowItsGetsetListsFile() {
        for (PyType owner :
                List.of(
                        PyType.METHOD_DESCRIPTOR,
                        PyType.WRAPPER_DESCRIPTOR,
                        PyType.CLASS_METHOD_DESCRIPTOR,
                        PyType.GETSET_DESCRIPTOR,
                        PyType.MEMBER_DESCRIPTOR)) {
            PyObject row = entry(owner, "__doc__");
            assertThat(row.getType().name()).isEqualTo("getset_descriptor");
            // One object, not two equal sentences: the class read is the row the dictionary holds.
            assertThat(Slots.getAttr(owner, "__doc__")).as(owner.name()).isSameAs(row);
            // The row a kind files about the name is a descriptor of that kind, named for the name.
            assertThat(Slots.getAttr(row, "__objclass__")).isSameAs(owner);
            assertThat(text(row, "__name__")).isEqualTo("__doc__");
        }

        // `type` has an internal doc, so the row it files under the same name is never reached; and the
        // sentence is written again on every read rather than handed out of the dictionary.
        assertThat(Slots.getAttr(PyType.TYPE, "__doc__")).isEqualTo(PyStr.of(TYPE_DOC));
        assertThat(Slots.getAttr(PyType.TYPE, "__doc__")).isNotSameAs(entry(PyType.TYPE, "__doc__"));
        assertThat(((PyStr) Slots.getAttr(PyType.TYPE, "__doc__")).getValue()).hasSize(79);
        assertThat(kind(entry(PyType.OBJECT, "__doc__"))).isEqualTo("str");
        assertThat(Slots.getAttr(PyType.OBJECT, "__doc__")).isNotSameAs(entry(PyType.OBJECT, "__doc__"));
        assertThat(text(PyType.OBJECT, "__doc__")).startsWith("The base class of the class hierarchy.");

        // The same two steps for a class Python wrote: its own entry, run through its own descriptor.
        PyType silent = type("Silent", PyType.OBJECT);
        PyType saying = type("Saying", PyType.OBJECT, "__doc__", PyStr.of("A sentence."));
        PyType holding = type("Holding", PyType.OBJECT, "__doc__", property());
        assertThat(held(silent, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(silent, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(saying, "__doc__")).isEqualTo(PyStr.of("A sentence."));
        // A `property` under the name is a descriptor, and a descriptor run with no instance returns itself:
        // the class reads the member, a value of the class reads what the member says.
        assertThat(Slots.getAttr(holding, "__doc__")).isSameAs(held(holding, "__doc__"));
        assertThat(Slots.getAttr(Slots.call(holding, new PyObject[0], null), "__doc__"))
                .isEqualTo(NUMBER);
        // Nothing walks a base for the name: a class that inherits a sentence says none.
        assertThat(Slots.getAttr(type("Child", saying), "__doc__")).isSameAs(PyNone.INSTANCE);
    }

    /**
     * {@code method_get}, {@code getset_get}, {@code member_get} and the two wrappers, with no instance.
     *
     * <p>Four of the five hand back the descriptor when there is no instance to read, which is the half of the
     * read a class makes: {@code int.bit_length} and {@code type.__basicsize__} are the rows rather than
     * bindings, and each is the one object the dictionary holds. The class-method row is the exception — {@code
     * classmethoddescr_get} binds the owner it was handed, so {@code dict.fromkeys} is a method of {@code
     * dict}. None of the five accepts both arguments as {@code None}: {@code wrap_descr_get} turns each into
     * {@code NULL} and refuses the pair before the getter is run, and the row's own count gate is what names
     * the slot in that sentence.
     */
    @Test
    void aReadWithNoInstanceIsTheRowAndTwoOfTheFiveAlsoJudgeTheInstance() {
        List<PyObject> rows = List.of(methodRow(), wrapperRow(), getsetRow(), memberRow());
        for (PyObject row : rows) {
            assertThat(readThrough(row)).as(kind(row)).isSameAs(row);
        }
        for (PyObject row : List.of(methodRow(), wrapperRow(), classMethodRow(), getsetRow(), memberRow())) {
            refuses(
                    "TypeError: __get__(None, None) is invalid",
                    () -> slot(row, "__get__", PyNone.INSTANCE, PyNone.INSTANCE));
        }
        // The row the class reads is the row the dictionary filed — one object, reached twice.
        assertThat(Slots.getAttr(PyType.INT, "bit_length")).isSameAs(methodRow());
        assertThat(Slots.getAttr(PyType.OBJECT, "__init__")).isSameAs(wrapperRow());
        // The class-method row is the one that binds instead, and so is never the object it was filed as.
        assertThat(readThrough(classMethodRow())).isNotSameAs(classMethodRow());
        // After the count, three of the five judge the class of what they were handed and two say nothing:
        // `descr_check` refuses a row of its own kind, where a slot wrapper binds it and waits for the call.
        for (PyObject row : List.of(methodRow(), getsetRow(), memberRow())) {
            refuses(doesNotApply(row), () -> slot(row, "__get__", row));
        }
        PyObject bound =
                Slots.call(Slots.getAttr(wrapperRow(), "__get__"), new PyObject[] {NUMBER}, null);
        assertThat(bound.getType().name()).isEqualTo("method-wrapper");
        assertThat(text(bound, "__name__")).isEqualTo("__init__");
        assertThat(text(methodRow(), "__qualname__")).isEqualTo("int.bit_length");
        assertThat(text(classMethodRow(), "__qualname__")).isEqualTo("object.__subclasshook__");
        assertThat(text(getsetRow(), "__qualname__")).isEqualTo("BaseException.args");
        assertThat(text(memberRow(), "__qualname__")).isEqualTo("type.__basicsize__");
        assertThat(text(memberRow(), "__doc__")).isNull();
        // The `__repr__` row each kind files is the same C function the kind's values are printed with, and
        // the three shapes name the field, the method and the slot in their own words.
        assertThat(methodRow().repr()).isEqualTo("<method 'bit_length' of 'int' objects>");
        assertThat(classMethodRow().repr())
                .isEqualTo("<method '__subclasshook__' of 'object' objects>");
        assertThat(wrapperRow().repr()).isEqualTo("<slot wrapper '__init__' of 'object' objects>");
        assertThat(getsetRow().repr()).isEqualTo("<attribute 'args' of 'BaseException' objects>");
        assertThat(memberRow().repr()).isEqualTo("<member '__basicsize__' of 'type' objects>");
    }

    /**
     * The two gates a slot row runs before it reaches what it was handed.
     *
     * <p>{@code PyArg_UnpackTuple} counts the arguments after the receiver — at least one and at most two for
     * {@code __get__}, exactly two for {@code __set__}, exactly one for {@code __delete__} — and the keyword
     * gate runs after the count, which is why the count's sentence names the slot and the keyword's names the
     * wrapper. A row with no such slot never gets to either: {@code __set__} is no name a {@code
     * method_descriptor} answers, so the read fails before any call is made.
     */
    @Test
    void theArgumentTableIsJudgedBeforeTheReceiverIsNamed() {
        for (PyObject row :
                List.of(methodRow(), wrapperRow(), classMethodRow(), getsetRow(), memberRow())) {
            refuses("TypeError: __get__ expected at least 1 argument, got 0", () -> slot(row, "__get__"));
            refuses(
                    "TypeError: __get__ expected at most 2 arguments, got 3",
                    () -> slot(row, "__get__", row, row, row));
            refuses(
                    "TypeError: wrapper __get__() takes no keyword arguments",
                    () -> slotKeyword(row, "__get__"));
            refuses("TypeError: expected 0 arguments, got 3", () -> slot(row, "__repr__", row, row, row));
            refuses(
                    "TypeError: wrapper __repr__() takes no keyword arguments",
                    () -> slotKeyword(row, "__repr__"));
        }
        for (PyObject row : List.of(getsetRow(), memberRow())) {
            refuses("TypeError: __set__ expected 2 arguments, got 0", () -> slot(row, "__set__"));
            refuses(
                    "TypeError: __set__ expected 2 arguments, got 3",
                    () -> slot(row, "__set__", row, row, row));
            refuses(
                    "TypeError: wrapper __set__() takes no keyword arguments",
                    () -> slotKeyword(row, "__set__"));
            refuses("TypeError: expected 1 argument, got 0", () -> slot(row, "__delete__"));
            refuses(
                    "TypeError: expected 1 argument, got 3",
                    () -> slot(row, "__delete__", row, row, row));
            refuses(
                    "TypeError: wrapper __delete__() takes no keyword arguments",
                    () -> slotKeyword(row, "__delete__"));
        }
        for (PyObject row : List.of(methodRow(), wrapperRow(), classMethodRow())) {
            refuses(
                    "AttributeError: '" + kind(row) + "' object has no attribute '__set__'",
                    () -> slot(row, "__set__"));
        }
        // Three kinds run `tp_call` and two do not; and of the three that do, the two whose first job is to
        // judge a class say so before anything else, while the method row is called with no argument at all.
        refuses(
                "TypeError: unbound method int.bit_length() needs an argument",
                () -> slot(methodRow(), "__call__"));
        refuses(
                "TypeError: descriptor '__init__' of 'object' object needs an argument",
                () -> slot(wrapperRow(), "__call__"));
        refuses(
                "TypeError: descriptor '__subclasshook__' of 'object' object needs an argument",
                () -> slot(classMethodRow(), "__call__"));
        for (PyObject row : List.of(getsetRow(), memberRow())) {
            refuses(
                    "AttributeError: '" + kind(row) + "' object has no attribute '__call__'",
                    () -> slot(row, "__call__"));
        }
    }

    /**
     * {@code descr_reduce} and the one class that has to reach past its own dictionary for a refusal.
     *
     * <p>Three of the five file pickle's row and all three answer the same pair — {@code getattr}, and the
     * owner and name that rebuild it. {@code getset_descriptor} and {@code classmethod_descriptor} file none,
     * so the read walks to {@code object}'s row, and {@code object_reduce} names the kind of the value it was
     * handed rather than the row it was reached through. Every one of the five refuses a call that brought
     * anything beside the receiver — {@code METH_NOARGS} counts the tail both for its own row and for the
     * {@code object} row below it — and the three that file their own refuse a receiver of the wrong kind
     * first, because {@code methoddescr_call} looks for the receiver before the table is counted.
     */
    @Test
    void threeKindsAnswerPicklesPairAndTwoRefuseItFromObject() {
        for (PyObject row : List.of(methodRow(), wrapperRow(), memberRow())) {
            PyTuple two =
                    (PyTuple) Slots.call(Slots.getAttr(row, "__reduce__"), new PyObject[0], null);
            assertThat(text(two.at(0), "__name__")).isEqualTo("getattr");
            assertThat(((PyTuple) two.at(1)).at(0))
                    .isSameAs(Slots.getAttr(row, "__objclass__"));
            assertThat(((PyTuple) two.at(1)).at(1))
                    .isEqualTo(Slots.getAttr(row, "__name__"));
        }
        for (PyObject row : List.of(classMethodRow(), getsetRow())) {
            refuses(
                    "TypeError: cannot pickle '" + kind(row) + "' object",
                    () -> Slots.call(Slots.getAttr(row, "__reduce__"), new PyObject[0], null));
        }
        // What both are reached through is a row of `object`, filed as a method of a kind that files it.
        assertThat(entry(PyType.CLASS_METHOD_DESCRIPTOR, "__reduce__")).isNull();
        assertThat(entry(PyType.GETSET_DESCRIPTOR, "__reduce__")).isNull();
        assertThat(kind(entry(PyType.OBJECT, "__reduce__"))).isEqualTo("method_descriptor");

        // Bound either way, the row is a `METH_NOARGS` one and says so under the kind it was read through.
        for (PyObject row :
                List.of(
                        methodRow(),
                        wrapperRow(),
                        classMethodRow(),
                        getsetRow(),
                        memberRow())) {
            String qualified = kind(row) + ".__reduce__()";
            refuses(
                    "TypeError: " + qualified + " takes no arguments (4 given)",
                    () ->
                            Slots.call(
                                    Slots.getAttr(row, "__reduce__"),
                                    new PyObject[] {NUMBER, NUMBER, NUMBER, NUMBER},
                                    null));
            refuses(
                    "TypeError: " + qualified + " takes no keyword arguments",
                    () ->
                            Slots.call(
                                    Slots.getAttr(row, "__reduce__"),
                                    new PyObject[] {NUMBER, NUMBER},
                                    PyTuple.of(PyStr.interned("x"))));
        }
        // Reached unbound, `methoddescr_call` finds its receiver among the arguments before the table is
        // counted, so the wrong kind is named whole and the extra argument beside it goes uncounted.
        for (PyObject row : List.of(methodRow(), wrapperRow(), memberRow())) {
            PyObject unbound = entry(row.getType(), "__reduce__");
            refuses(
                    "TypeError: " + kind(row) + ".__reduce__() takes no arguments (2 given)",
                    () -> Slots.call(unbound, new PyObject[] {row, NUMBER, NUMBER}, null));
            refuses(
                    "TypeError: descriptor '__reduce__' for '"
                            + kind(row)
                            + "' objects doesn't apply to a 'str' object",
                    () -> Slots.call(unbound, new PyObject[] {NUMBER, NUMBER}, null));
        }
    }

    /**
     * The three refusals a write to a descriptor's own name draws.
     *
     * <p>A name the kind files as a getset with no setter is refused by {@code PyGetSetDescr_Type}'s {@code
     * tp_descr_set}, which says the attribute is not writable and names the kind of the <em>value</em> being
     * written. A name filed as a {@code Py_READONLY} member is refused by {@code PyMember_SetOne} in the two
     * words it has for every field, delete included. A name nobody files is refused last, because the kind
     * keeps no dictionary to write into, and the sentence is {@code PyObject_GenericSetAttr}'s.
     */
    @Test
    void aWriteOfANameTheRowCarriesIsRefusedByTheRowThatHasNoSetter() {
        for (String name : List.of("__doc__", "__qualname__", "__text_signature__")) {
            refuses(
                    "AttributeError: attribute '"
                            + name
                            + "' of 'method_descriptor' objects is not writable",
                    () -> Slots.setAttr(methodRow(), name, TEXT));
            refuses(
                    "AttributeError: attribute '"
                            + name
                            + "' of 'method_descriptor' objects is not writable",
                    () -> Slots.delAttr(methodRow(), name));
        }
        for (String name : List.of("__doc__", "__qualname__")) {
            refuses(
                    "AttributeError: attribute '"
                            + name
                            + "' of 'getset_descriptor' objects is not writable",
                    () -> Slots.setAttr(getsetRow(), name, TEXT));
            refuses(
                    "AttributeError: attribute '"
                            + name
                            + "' of 'member_descriptor' objects is not writable",
                    () -> Slots.setAttr(memberRow(), name, TEXT));
        }
        // The two short getset lists stop at `__qualname__`, so the signature is a name nobody files and the
        // refusal comes from the missing dictionary rather than from a row with no setter.
        for (PyObject row : List.of(getsetRow(), memberRow())) {
            refuses(
                    "AttributeError: '"
                            + kind(row)
                            + "' object has no attribute '__text_signature__'"
                            + " and no __dict__ for setting new attributes",
                    () -> Slots.setAttr(row, "__text_signature__", TEXT));
        }
        // The two fields are members of a `Py_READONLY` table: a write and a delete both reach
        // `PyMember_SetOne`, which has one sentence for either.
        for (PyObject row :
                List.of(methodRow(), wrapperRow(), classMethodRow(), getsetRow(), memberRow())) {
            refuses("AttributeError: readonly attribute", () -> Slots.setAttr(row, "__name__", TEXT));
            refuses("AttributeError: readonly attribute", () -> Slots.delAttr(row, "__name__"));
        }
        for (String name : List.of("zzz", "__dict__")) {
            refuses(
                    "AttributeError: 'method_descriptor' object has no attribute '"
                            + name
                            + "' and no __dict__ for setting new attributes",
                    () -> Slots.setAttr(methodRow(), name, TEXT));
            refuses(
                    "AttributeError: 'method_descriptor' object has no attribute '"
                            + name
                            + "' and no __dict__ for setting new attributes",
                    () -> Slots.delAttr(methodRow(), name));
        }
    }
}
