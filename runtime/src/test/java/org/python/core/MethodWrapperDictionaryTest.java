package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sixteen names {@code descrobject.c} files for {@code method-wrapper}, and the seven kinds of the class
 * system that file no creator, measured against CPython 3.14.8.
 *
 * <p>The corpus script reads the table from Python and calls every row of it over a wrapper of {@code
 * int.__add__}. What is pinned here is the machinery those reads run through. The first piece is the order:
 * {@code type_ready_fill_dict} walks {@code add_operators} before the three lists of the type's own, so the nine
 * slot rows — {@code tp_repr}, {@code tp_hash}, {@code tp_call} and the six halves of {@code tp_richcompare} —
 * come first, and {@code wrapper_methods}, {@code wrapper_members} and {@code wrapper_getsets} follow with one
 * name each, five names and no {@code tp_doc} at all. The second is that all nine of those rows run over the
 * value the wrapper holds: {@code wrapper_call} hands {@code wp->self} to {@code wrapperdescr_raw_call}, so the
 * arity sentences a row refuses with are the slot's own and name nothing, while {@code wrapper_repr} and {@code
 * wrapper_hash} answer about the wrapper and mix the descriptor's pointer into what they say.
 *
 * <p>The third piece is {@code wrapper_richcompare}, which ranks nothing: it answers only {@code ==} and {@code
 * !=}, only between two wrappers, and out of the descriptor and the value together — so two reads of the same
 * slot on the same value are equal although neither is the other, and the four orderings hand the question back
 * for the operator to complain about. The fourth is the one {@code PyMemberDef} and the five {@code
 * PyGetSetDef}s: the member is {@code Py_READONLY} and refuses in two words, the getsets file no setter and
 * refuse in a sentence of their own, the nine slot rows are refused by {@code object} rather than by a
 * descriptor, and a name outside the sixteen is refused for the write and the delete alike, because {@code
 * object} hands the delete to the same setter with nothing as the value — four ways a program's attempt to
 * change one of these names comes back.
 *
 * <p>The fifth is the absence. {@code _PyMethodWrapper_Type} files no {@code tp_new} over a base of {@code
 * object}, so {@code type_ready_set_new} marks it {@code Py_TPFLAGS_DISALLOW_INSTANTIATION}, {@code
 * add_tp_new_wrapper} is never reached, and the type has no constructor row to read — six other static builtins
 * of the class system are in the same position, and all seven refuse every route to being built in the same
 * words, with the slot read before any argument is.
 */
class MethodWrapperDictionaryTest {

    /** {@code method-wrapper}: nine slots, one method, one member, five getsets, no constructor. */
    private static final List<String> ORDER =
            List.of(
                    "__repr__",
                    "__hash__",
                    "__call__",
                    "__lt__",
                    "__le__",
                    "__eq__",
                    "__ne__",
                    "__gt__",
                    "__ge__",
                    "__reduce__",
                    "__self__",
                    "__objclass__",
                    "__name__",
                    "__qualname__",
                    "__doc__",
                    "__text_signature__");

    /** The seven kinds whose {@code tp_new} is a null pointer over a base of {@code object}. */
    private static final List<PyType> NO_CREATOR =
            List.of(
                    PyType.BUILTIN_FUNCTION,
                    PyType.METHOD_WRAPPER,
                    PyType.METHOD_DESCRIPTOR,
                    PyType.WRAPPER_DESCRIPTOR,
                    PyType.CLASS_METHOD_DESCRIPTOR,
                    PyType.GETSET_DESCRIPTOR,
                    PyType.MEMBER_DESCRIPTOR);

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

    /** {@code (3).__add__}: the wrapper this test runs every row of the table over. */
    private static PyObject wrapper() {
        return Slots.getAttr(PyInt.of(3), "__add__");
    }

    /** A second read of the same slot on the same value, which is a fresh wrapper of its own. */
    private static PyObject twice() {
        return Slots.getAttr(PyInt.of(3), "__add__");
    }

    /** {@code kind.__dict__[name](...args)}: a row of the dictionary, called with what the test names. */
    private static PyObject row(PyType owner, String name, PyObject... args) {
        return Slots.call(entry(owner, name), args, null);
    }

    /** {@code getattr(value, name)(...args)}: a row read through a value, which binds it first. */
    private static PyObject through(PyObject value, String name, PyObject... args) {
        return Slots.call(Slots.getAttr(value, name), args, null);
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
     * The four C lists in the order {@code type_ready_fill_dict} walks them, and the four names it never files.
     *
     * <p>Nine {@code wrapper_descriptor}s out of {@code add_operators} over the four slots the record fills, then
     * the one {@code method_descriptor} of {@code wrapper_methods}, the one {@code member_descriptor} of {@code
     * wrapper_members} and the five {@code getset_descriptor}s of {@code wrapper_getsets}. No {@code tp_new}
     * means no {@code __new__} row, and no {@code tp_dictoffset} means no {@code __dict__} row — and with no
     * {@code tp_doc} either, {@code type_dict_set_doc} files nothing, so the sixteenth name is the getset the
     * type reads its own {@code __doc__} through. {@code tp_descr_get} and {@code tp_descr_set} are the last two
     * zeros of the record: a wrapper is the record of one slot on one value, not something to read a name out of.
     */
    @Test
    void nineSlotsOneMethodOneMemberFiveGetsetsAndNoConstructor() {
        assertThat(filed(PyType.METHOD_WRAPPER)).isEqualTo(ORDER);
        assertThat(ORDER).hasSize(16);

        for (String name :
                List.of(
                        "__repr__", "__hash__", "__call__",
                        "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")) {
            assertThat(kind(entry(PyType.METHOD_WRAPPER, name)))
                    .as(name)
                    .isEqualTo("wrapper_descriptor");
        }
        assertThat(kind(entry(PyType.METHOD_WRAPPER, "__reduce__")))
                .isEqualTo("method_descriptor");
        assertThat(kind(entry(PyType.METHOD_WRAPPER, "__self__"))).isEqualTo("member_descriptor");
        for (String name :
                List.of(
                        "__objclass__", "__name__", "__qualname__", "__doc__", "__text_signature__")) {
            assertThat(kind(entry(PyType.METHOD_WRAPPER, name)))
                    .as(name)
                    .isEqualTo("getset_descriptor");
        }
        for (String name : List.of("__new__", "__dict__", "__get__", "__set__")) {
            assertThat(entry(PyType.METHOD_WRAPPER, name)).as(name).isNull();
        }

        // The four families, each with its own words for what it is — and none of them an address.
        assertThat(entry(PyType.METHOD_WRAPPER, "__call__").repr())
                .isEqualTo("<slot wrapper '__call__' of 'method-wrapper' objects>");
        assertThat(entry(PyType.METHOD_WRAPPER, "__reduce__").repr())
                .isEqualTo("<method '__reduce__' of 'method-wrapper' objects>");
        assertThat(entry(PyType.METHOD_WRAPPER, "__self__").repr())
                .isEqualTo("<member '__self__' of 'method-wrapper' objects>");
        assertThat(entry(PyType.METHOD_WRAPPER, "__name__").repr())
                .isEqualTo("<attribute '__name__' of 'method-wrapper' objects>");

        // The nine slot rows carry the sentences `slotdefs` writes, and every one of them names the value.
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__repr__"), "__doc__"))
                .isEqualTo("Return repr(self).");
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__hash__"), "__doc__"))
                .isEqualTo("Return hash(self).");
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__call__"), "__doc__"))
                .isEqualTo("Call self as a function.");
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__lt__"), "__doc__"))
                .isEqualTo("Return self<value.");
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__ge__"), "__doc__"))
                .isEqualTo("Return self>=value.");
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__repr__"), "__text_signature__"))
                .isEqualTo("($self, /)");
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__call__"), "__text_signature__"))
                .isEqualTo("($self, /, *args, **kwargs)");
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__eq__"), "__text_signature__"))
                .isEqualTo("($self, value, /)");

        // The method, member and getset rows are registered with no sentence at all, and a member has no
        // table to be cut out of the flag either.
        for (String name :
                List.of(
                        "__reduce__", "__self__", "__objclass__", "__name__", "__qualname__", "__doc__",
                        "__text_signature__")) {
            PyObject member = entry(PyType.METHOD_WRAPPER, name);
            assertThat(Slots.getAttr(member, "__doc__")).as(name).isSameAs(PyNone.INSTANCE);
            assertThat(text(member, "__qualname__")).as(name).isEqualTo("method-wrapper." + name);
            assertThat(Slots.getAttr(member, "__objclass__")).as(name).isSameAs(PyType.METHOD_WRAPPER);
        }
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__reduce__"), "__text_signature__"))
                .isEqualTo("($self, /)");
        refuses(
                "AttributeError: 'member_descriptor' object has no attribute '__text_signature__'",
                () -> Slots.getAttr(entry(PyType.METHOD_WRAPPER, "__self__"), "__text_signature__"));
    }

    /**
     * The class answers its own {@code __doc__} row, because it has no {@code tp_doc} to answer instead.
     *
     * <p>{@code type_get_doc} takes the {@code tp_doc} of a static type only when there is one; with none, it
     * reads the name out of the dictionary and, since a getset bound to nothing returns itself, hands back the
     * very {@code getset_descriptor} the dictionary holds — the same object on every read. {@code
     * type_get_text_signature} has no such fallback: it words a table out of the {@code tp_doc} alone, so a type
     * that fills none answers {@code None}.
     */
    @Test
    void theClassReadsItsOwnDocRowAndAnswersNoSignature() {
        assertThat(Slots.getAttr(PyType.METHOD_WRAPPER, "__doc__"))
                .isSameAs(entry(PyType.METHOD_WRAPPER, "__doc__"));
        assertThat(Slots.getAttr(PyType.METHOD_WRAPPER, "__doc__"))
                .isSameAs(Slots.getAttr(PyType.METHOD_WRAPPER, "__doc__"));
        assertThat(Slots.getAttr(PyType.METHOD_WRAPPER, "__text_signature__"))
                .isSameAs(PyNone.INSTANCE);
        assertThat(PyType.METHOD_WRAPPER.bases().length).isEqualTo(1);
        assertThat(PyType.METHOD_WRAPPER.bases()[0]).isSameAs(PyType.OBJECT);
        assertThat(PyType.METHOD_WRAPPER.filedName()).isEqualTo("method-wrapper");

        // The five getsets and the one member of the wrapper's own table, run over the value they belong to.
        PyObject added = wrapper();
        assertThat(Slots.getAttr(added, "__name__")).isEqualTo(PyStr.of("__add__"));
        assertThat(Slots.getAttr(added, "__qualname__")).isEqualTo(PyStr.of("int.__add__"));
        assertThat(Slots.getAttr(added, "__objclass__")).isSameAs(PyType.INT);
        assertThat(Slots.getAttr(added, "__self__")).isEqualTo(PyInt.of(3));
        assertThat(Slots.getAttr(added, "__doc__")).isEqualTo(PyStr.of("Return self+value."));
        assertThat(Slots.getAttr(added, "__text_signature__"))
                .isEqualTo(PyStr.of("($self, value, /)"));

        // A wrapper holds no store and is not a descriptor, so neither name is anywhere on its way.
        for (String name : List.of("__dict__", "__get__")) {
            refuses(
                    "AttributeError: 'method-wrapper' object has no attribute '" + name + "'",
                    () -> Slots.getAttr(added, name));
        }
    }

    /**
     * The nine slot rows over the value the wrapper holds, and the arity sentences the slot answers with.
     *
     * <p>{@code wrapperdescr_call} runs the slot out of the descriptor over {@code wp->self} and hands it the
     * arguments that came beside the wrapper, so a row called bare complains in the slot's own words — {@code
     * expected 1 argument, got 0} with no name to say whose — while the receiver it was called through is judged
     * by {@code wrapperdescr_check} first, in the longer words of the descriptor. {@code __call__} is the row
     * with no count of its own: it forwards whatever arrived, so the slot underneath is what runs out of count.
     */
    @Test
    void theNineSlotRowsRunOverTheValueAndAnswerInTheSlotOwnWords() {
        PyObject added = wrapper();
        assertThat(row(PyType.METHOD_WRAPPER, "__call__", added, PyInt.of(4))).isEqualTo(PyInt.of(7));
        // The same row over a different slot of a different value, and the wrapper the row is read through.
        PyObject minus = Slots.getAttr(PyInt.of(3), "__sub__");
        assertThat(row(PyType.METHOD_WRAPPER, "__call__", minus, PyInt.of(4))).isEqualTo(PyInt.of(-1));
        assertThat(through(added, "__call__", PyInt.of(4))).isEqualTo(PyInt.of(7));

        assertThat(row(PyType.METHOD_WRAPPER, "__hash__", added)).isEqualTo(PyInt.of(added.hash()));
        assertThat(row(PyType.METHOD_WRAPPER, "__hash__", twice())).isEqualTo(PyInt.of(added.hash()));
        assertThat(((PyStr) row(PyType.METHOD_WRAPPER, "__repr__", wrapper())).getValue())
                .startsWith("<method-wrapper '__add__' of int object");

        // What the slot was handed is counted in the slot's own words: the tail of a binary slot, of a nullary
        // one, and the row that forwards whatever arrived runs out of count the same way.
        for (String name : List.of("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")) {
            refuses(
                    "TypeError: expected 1 argument, got 2",
                    () -> row(PyType.METHOD_WRAPPER, name, added, NUMBER, NUMBER));
        }
        refuses(
                "TypeError: expected 1 argument, got 0",
                () -> row(PyType.METHOD_WRAPPER, "__call__", added));
        refuses(
                "TypeError: expected 1 argument, got 2",
                () -> row(PyType.METHOD_WRAPPER, "__call__", added, NUMBER, NUMBER));
        refuses(
                "TypeError: expected 0 arguments, got 2",
                () -> row(PyType.METHOD_WRAPPER, "__repr__", added, NUMBER, NUMBER));
        refuses(
                "TypeError: expected 0 arguments, got 1",
                () -> row(PyType.METHOD_WRAPPER, "__hash__", added, NUMBER));

        // A keyword is refused by the row, which names the slot it wrapped — and only then counts.
        refuses(
                "TypeError: wrapper __add__() takes no keyword arguments",
                () ->
                        Slots.call(
                                entry(PyType.METHOD_WRAPPER, "__call__"),
                                new PyObject[] {added, NUMBER},
                                PyTuple.of(PyStr.interned("x"))));
        refuses(
                "TypeError: wrapper __repr__() takes no keyword arguments",
                () ->
                        Slots.call(
                                entry(PyType.METHOD_WRAPPER, "__repr__"),
                                new PyObject[] {added, NUMBER},
                                PyTuple.of(PyStr.interned("x"))));

        // The receiver is judged before the tail is counted, and the bare call says the row is unbound.
        for (String name : List.of("__repr__", "__hash__", "__call__", "__eq__")) {
            refuses(
                    "TypeError: descriptor '" + name + "' of 'method-wrapper' object needs an argument",
                    () -> row(PyType.METHOD_WRAPPER, name));
            refuses(
                    "TypeError: descriptor '" + name + "' requires a 'method-wrapper' object"
                            + " but received a 'int'",
                    () -> row(PyType.METHOD_WRAPPER, name, NUMBER, NUMBER, NUMBER));
        }
    }

    /**
     * {@code wrapper_richcompare}: two answers out of the descriptor and the value, and no ranking at all.
     *
     * <p>The function begins by refusing anything that is not {@code ==} or {@code !=} and anything that is not
     * a second wrapper, so the four ordering rows hand back {@code NotImplemented} even between two of this very
     * type — and the operator that called them is what complains. Equality is the pair the wrapper is made of,
     * which is the only thing a program has to compare, since {@code PyWrapper_New} makes a fresh wrapper for
     * every read of a slot.
     */
    @Test
    void orderingRanksNothingAndEqualityIsTheDescriptorAndTheValueTogether() {
        PyObject added = wrapper();
        PyObject again = twice();
        PyObject minus = Slots.getAttr(PyInt.of(3), "__sub__");

        assertThat(added).isNotSameAs(again);
        for (CompareOp cmp : List.of(CompareOp.LT, CompareOp.LE, CompareOp.GT, CompareOp.GE)) {
            assertThat(row(PyType.METHOD_WRAPPER, cmp.dunder(), added, again))
                    .as(cmp.symbol())
                    .isSameAs(PyNotImplemented.INSTANCE);
        }
        assertThat(row(PyType.METHOD_WRAPPER, "__eq__", added, again)).isSameAs(PyBool.TRUE);
        assertThat(row(PyType.METHOD_WRAPPER, "__ne__", added, again)).isSameAs(PyBool.FALSE);
        assertThat(row(PyType.METHOD_WRAPPER, "__eq__", added, minus)).isSameAs(PyBool.FALSE);
        // An operand that is not a wrapper at all is declined, whatever the question was.
        for (String name : List.of("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__")) {
            assertThat(row(PyType.METHOD_WRAPPER, name, added, NUMBER))
                    .as(name)
                    .isSameAs(PyNotImplemented.INSTANCE);
        }

        // The operators run past the decline: `<` has nothing to fall back on, `==` falls back to identity.
        refuses(
                "TypeError: '<' not supported between instances of 'method-wrapper'"
                        + " and 'method-wrapper'",
                () -> Slots.compare(CompareOp.LT, added, again));
        assertThat(Slots.compare(CompareOp.EQ, added, again)).isSameAs(PyBool.TRUE);
        assertThat(Slots.compare(CompareOp.EQ, added, NUMBER)).isSameAs(PyBool.FALSE);
        assertThat(added.richCompare(CompareOp.LT, again)).isSameAs(PyNotImplemented.INSTANCE);

        // The hash is the identity of the value mixed with the pointer of the descriptor, so two reads of one
        // slot on one value hash alike and neither is a copy of the other.
        assertThat(added.hash()).isEqualTo(again.hash());
    }

    /**
     * The one {@code Py_READONLY} member, the five getsets with no setter, and the four sentences a write
     * answers with.
     *
     * <p>The four families refuse in four different words, and which of them answers is decided by the row the
     * name is filed under: {@code member_set} says {@code readonly attribute} for the member, {@code descr_set}
     * says the longer sentence of a getset that filled no setter, and a name filed as a slot is not a descriptor
     * at all, so {@code object} refuses it as read-only before any descriptor is reached. A name the type files
     * nowhere is refused by the same {@code object} for a different reason: a wrapper has no store to put it in.
     * A delete of any of the sixteen says the write's sentence too, which is the whole of how a delete is made:
     * {@code object}'s own {@code __delattr__} hands the name to the setter with {@code NULL} as the value, so
     * the refusal of a setter that was never filled is also the refusal of a field that was never there.
     */
    @Test
    void oneReadonlyMemberFiveGetsetsWithNoSetterAndFourWordsForAWrite() {
        PyObject added = wrapper();

        refuses("AttributeError: readonly attribute", () -> Slots.setAttr(added, "__self__", TEXT));
        refuses("AttributeError: readonly attribute", () -> Slots.delAttr(added, "__self__"));
        assertThat(Slots.getAttr(added, "__self__")).isEqualTo(PyInt.of(3));

        for (String name :
                List.of(
                        "__objclass__", "__name__", "__qualname__", "__doc__", "__text_signature__")) {
            String message =
                    "AttributeError: attribute '" + name + "' of 'method-wrapper' objects is not writable";
            refuses(message, () -> Slots.setAttr(added, name, TEXT));
            refuses(message, () -> Slots.delAttr(added, name));
        }
        // The getset that refuses the write is the one that answers the read, and it answers what it always did.
        assertThat(Slots.getAttr(added, "__name__")).isEqualTo(PyStr.of("__add__"));

        for (String name : List.of("__repr__", "__hash__", "__call__", "__eq__")) {
            String message =
                    "AttributeError: 'method-wrapper' object attribute '" + name + "' is read-only";
            refuses(message, () -> Slots.setAttr(added, name, TEXT));
            refuses(message, () -> Slots.delAttr(added, name));
        }
        // A name the type files nowhere is refused for the reason the wrapper holds: it has no store.
        final String nowhere =
                "AttributeError: 'method-wrapper' object has no attribute '__brand__'"
                        + " and no __dict__ for setting new attributes";
        refuses(nowhere, () -> Slots.setAttr(added, "__brand__", TEXT));
        refuses(nowhere, () -> Slots.delAttr(added, "__brand__"));

        // On the class, none of it is about the row: a static type is immutable and says so by name.
        for (String name : List.of("__repr__", "__self__", "__doc__", "__brand__")) {
            refuses(
                    "TypeError: cannot set '" + name + "' attribute of immutable type"
                            + " 'method-wrapper'",
                    () -> Slots.setAttr(PyType.METHOD_WRAPPER, name, TEXT));
            refuses(
                    "TypeError: cannot set '" + name + "' attribute of immutable type"
                            + " 'method-wrapper'",
                    () -> Slots.delAttr(PyType.METHOD_WRAPPER, name));
        }
    }

    /**
     * {@code wrapper_reduce}: the one {@code PyMethodDef} of the record, and the {@code getattr} it names.
     *
     * <p>{@code descr_reduce} — the row of the same list that the three unbound kinds file — names the type the
     * descriptor belongs to and its name. This one names the value the wrapper holds and the slot, which is the
     * half that makes the restored method the same one rather than a copy of nothing. The row is {@code
     * METH_NOARGS} and its own {@code ml_doc} is null, so it answers no sentence and a table cut out of the flag;
     * and because {@code methoddescr_call} looks for its receiver before the count is made, a call that brought
     * junk is refused by the descriptor rather than by the arity.
     */
    @Test
    void theReduceRowNamesTheValueAndTheSlotForGetattrToFindAgain() {
        PyObject added = wrapper();
        PyObject through = Slots.getAttr(added, "__reduce__");
        assertThat(kind(through)).isEqualTo("builtin_function_or_method");

        PyTuple pair = (PyTuple) Slots.call(through, new PyObject[0], null);
        assertThat(pair.at(0)).isSameAs(PyRuntime.builtins().get(PyStr.interned("getattr")));
        PyTuple found = (PyTuple) pair.at(1);
        assertThat(found.at(0)).isEqualTo(PyInt.of(3));
        assertThat(found.at(1)).isEqualTo(PyStr.of("__add__"));
        PyObject restored = Slots.call(pair.at(0), new PyObject[] {found.at(0), found.at(1)}, null);
        assertThat(restored.getType()).isSameAs(PyType.METHOD_WRAPPER);
        assertThat(Slots.call(restored, new PyObject[] {PyInt.of(4)}, null)).isEqualTo(PyInt.of(7));

        assertThat(kind(entry(PyType.METHOD_WRAPPER, "__reduce__")))
                .isEqualTo("method_descriptor");
        assertThat(Slots.getAttr(entry(PyType.METHOD_WRAPPER, "__reduce__"), "__doc__"))
                .isSameAs(PyNone.INSTANCE);
        assertThat(text(entry(PyType.METHOD_WRAPPER, "__reduce__"), "__text_signature__"))
                .isEqualTo("($self, /)");

        // The three refusals of the row, in the order the call makes them: receiver, then count, then keywords.
        refuses(
                "TypeError: unbound method method-wrapper.__reduce__() needs an argument",
                () -> row(PyType.METHOD_WRAPPER, "__reduce__"));
        refuses(
                "TypeError: descriptor '__reduce__' for 'method-wrapper' objects"
                        + " doesn't apply to a 'int' object",
                () -> row(PyType.METHOD_WRAPPER, "__reduce__", NUMBER));
        refuses(
                "TypeError: method-wrapper.__reduce__() takes no arguments (1 given)",
                () -> row(PyType.METHOD_WRAPPER, "__reduce__", added, NUMBER));
        refuses(
                "TypeError: method-wrapper.__reduce__() takes no keyword arguments",
                () ->
                        Slots.call(
                                entry(PyType.METHOD_WRAPPER, "__reduce__"),
                                new PyObject[] {added, NUMBER},
                                PyTuple.of(PyStr.interned("x"))));
    }

    /**
     * The seven kinds that file no {@code tp_new}, and the five routes that reach the same refusal.
     *
     * <p>{@code type_ready_set_new} looks for a null {@code tp_new} over a base of {@code object} on a type that
     * was not built at runtime, marks what it finds {@code Py_TPFLAGS_DISALLOW_INSTANTIATION} and clears the slot
     * again — so {@code add_tp_new_wrapper} never files a {@code __new__} row and there is nothing to read off
     * the dictionary but the absence. {@code type_call} looks at the slot before it looks at the arguments, and
     * {@code object_new} and {@code tp_new_wrapper} look at it for the class they were handed, which is how a
     * call, a call with junk, a call with a keyword, {@code object.__new__(kind)} and {@code kind.__new__(kind)}
     * all say the same sentence. The kinds that do fill a creator of their own — {@code property}, {@code
     * staticmethod}, {@code classmethod}, {@code mappingproxy} and {@code str} are the five this test reads — are
     * refused for someone else's instead, and keep the {@code is not safe} complaint.
     */
    @Test
    void sevenKindsWithNoCreatorRefuseEveryRouteInTheSameWords() {
        for (PyType kindOf : NO_CREATOR) {
            String name = kindOf.filedName();
            assertThat(kindOf.declaresNoInstances()).as(name).isTrue();
            assertThat(entry(kindOf, "__new__")).as(name).isNull();
            final String refusal = "TypeError: cannot create '" + name + "' instances";
            refuses(refusal, () -> kindOf.call(new PyObject[0], null));
            refuses(refusal, () -> kindOf.call(new PyObject[] {NUMBER, TEXT}, null));
            refuses(
                    refusal,
                    () ->
                            Slots.call(
                                    kindOf,
                                    new PyObject[] {NUMBER},
                                    PyTuple.of(PyStr.interned("x"))));
            refuses(refusal, () -> row(PyType.OBJECT, "__new__", kindOf));
            // `kind.__new__` is not the kind's own row: it is `object`'s, reached through the base.
            refuses(
                    refusal,
                    () ->
                            Slots.call(
                                    Slots.getAttr(kindOf, "__new__"),
                                    new PyObject[] {kindOf},
                                    null));
            // The class still answers its own `__doc__` row, and no signature at all.
            assertThat(Slots.getAttr(kindOf, "__doc__"))
                    .as(name)
                    .isSameAs(entry(kindOf, "__doc__"));
            assertThat(Slots.getAttr(kindOf, "__text_signature__"))
                    .as(name)
                    .isSameAs(PyNone.INSTANCE);
        }

        // `dict_keys` is an eighth, and is not one of the class-system kinds this slice names.
        PyType views = DictViews.keys(PyDict.empty()).getType();
        assertThat(views.declaresNoInstances()).isTrue();
        refuses(
                "TypeError: cannot create 'dict_keys' instances",
                () -> row(PyType.OBJECT, "__new__", views));

        // The five that fill a creator of their own are refused by `object.__new__` for someone else's.
        for (PyType keeper :
                List.of(
                        PyType.PROPERTY,
                        PyType.STATIC_METHOD,
                        PyType.CLASS_METHOD,
                        PyType.MAPPING_PROXY,
                        PyType.STR)) {
            assertThat(keeper.declaresNoInstances()).as(keeper.name()).isFalse();
            refuses(
                    "TypeError: object.__new__(" + keeper.name() + ") is not safe,"
                            + " use " + keeper.name() + ".__new__()",
                    () -> row(PyType.OBJECT, "__new__", keeper));
            assertThat(entry(keeper, "__new__")).as(keeper.name()).isNotNull();
        }
    }
}
