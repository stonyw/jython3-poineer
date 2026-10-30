package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The two currencies a slot refusal is paid in, measured against CPython 3.14.10.
 *
 * <p>CPython has no marker object. {@code PyObject_GetItem} hands back whatever {@code mp_subscript}
 * answered and {@code PyObject_GetAttr} hands back whatever {@code slot_tp_getattro} answered; only the
 * two-way protocols — {@code nb_*} and {@code sq_compare} with their reflected twins — read the value
 * {@code NotImplemented} as a decline, and even there a slot that the type leaves null is not a value at
 * all. This runtime needs both, because a Java slot with no body behind it has to say so without
 * inventing a Python-visible object: {@link Slots#DECLINED} is that marker, and this class reduces
 * {@link PyNotImplemented} to being only a value.
 *
 * <p>The corpus script shows a program the answers; it cannot show why they differ, because Python has no
 * spelling for the marker. Every pair below is the same question put to {@link Answered} — a value whose
 * every slot hands back the value, which is what a class writing {@code return NotImplemented} does — and
 * to {@link #bare}, a value whose type fills none of them, which is what CPython's {@code object()} is.
 */
class NotImplementedValueTest {

    /** The one key every subscript in these assertions uses. */
    private static final PyStr KEY = PyStr.of("k");

    /** A value whose slots answer with the Python value, as a Python method would. */
    private static class Answered extends PyObject {

        Answered() {
            super(PyType.OBJECT);
        }

        @Override
        public PyObject getItem(PyObject key) {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject setItem(PyObject key, PyObject value) {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject delItem(PyObject key) {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject length() {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject iterator() {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject contains(PyObject item) {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject getAttr(String name) {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject truth() {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject binaryOp(BinaryOp op, PyObject other) {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject reflectedOp(BinaryOp op, PyObject other) {
            return PyNotImplemented.INSTANCE;
        }

        @Override
        public PyObject richCompare(CompareOp cmp, PyObject other) {
            return PyNotImplemented.INSTANCE;
        }
    }

    /** A value whose slots answer with the answer a type that declares nothing gives. */
    private static class Bare extends PyObject {

        Bare() {
            super(PyType.OBJECT);
        }
    }

    /** {@code object()} — the same refusal, read from the built-in type rather than from a subclass. */
    private static PyObject bare() {
        return new Bare();
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
     * The marker is not the value, and {@link Slots#declined} is the only place both count.
     *
     * <p>That method is called by the reflected-operator negotiation and by nothing that reports an answer
     * to a program, which is the whole of the split.
     */
    @Test
    void theMarkerIsNotTheValue() {
        assertThat(Slots.DECLINED).isNotSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.DECLINED.getType()).isNotSameAs(PyType.NOTIMP);
        assertThat(Slots.DECLINED.repr()).doesNotContain("NotImplemented");
        assertThat(new Answered().getItem(KEY)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(bare().getItem(KEY)).isSameAs(Slots.DECLINED);

        assertThat(Slots.declined(PyNotImplemented.INSTANCE)).isTrue();
        assertThat(Slots.declined(Slots.DECLINED)).isTrue();
        assertThat(Slots.declined(PyNone.INSTANCE)).isFalse();
        assertThat(Slots.declined(PyBool.FALSE)).isFalse();
    }

    /**
     * {@code x[k]}: the answer travels, the absence does not.
     *
     * <p>{@code NotImplemented} survives a round trip through a dictionary, a list, a tuple and a class
     * that hands it back from {@code __getitem__} — sliced key included, because {@code PyObject_GetItem}
     * does not look at what it is passed either. Only a type with no {@code mp_subscript} behind it is the
     * complaint, and it names the type of the value that was asked.
     */
    @Test
    void aSubscriptAnswerTravelsAndAnAbsentOneIsTheComplaint() {
        PyDict kept = PyDict.empty();
        kept.put(KEY, PyNotImplemented.INSTANCE);
        assertThat(Slots.getItem(kept, KEY)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.getItem(PyList.of(PyNotImplemented.INSTANCE), PyInt.of(0)))
                .isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.getItem(PyTuple.of(PyNotImplemented.INSTANCE), PyInt.of(0)))
                .isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.getItem(new Answered(), KEY)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.getItem(new Answered(), PySlice.of(PyInt.of(0), PyInt.of(1), null)))
                .isSameAs(PyNotImplemented.INSTANCE);

        refuses("TypeError: 'object' object is not subscriptable", () -> Slots.getItem(bare(), KEY));
        refuses("TypeError: 'int' object is not subscriptable", () -> Slots.getItem(PyInt.of(1), KEY));
        // A subscript nobody declares declines in the other currency, and so reaches the same complaint.
        assertThat(Specials.invoke(bare(), "__getitem__", KEY)).isSameAs(Slots.DECLINED);
    }

    /**
     * The writing halves have no answer to report, so the marker is the only way to refuse.
     *
     * <p>{@code mp_ass_subscript} returns a status, not a value: a {@code __setitem__} that hands back
     * {@code NotImplemented} has still written, and the assignment statement keeps its own
     * {@code None}. A type with no slot is the complaint, in the words of the operation rather than of the
     * value.
     */
    @Test
    void aWritingSlotDropsItsAnswerAndKeepsItsRefusal() {
        Slots.setItem(new Answered(), KEY, PyInt.of(1));
        Slots.delItem(new Answered(), KEY);

        refuses(
                "TypeError: 'object' object does not support item assignment",
                () -> Slots.setItem(bare(), KEY, PyInt.of(1)));
        refuses(
                "TypeError: 'str' object does not support item assignment",
                () -> Slots.setItem(PyStr.of("ab"), PyInt.of(0), PyInt.of(1)));
        refuses(
                "TypeError: 'object' object does not support item deletion",
                () -> Slots.delItem(bare(), KEY));
    }

    /**
     * {@code NotImplementedType} answers exactly one number question, and it is a refusal.
     *
     * <p>{@code notimplemented_bool} ({@code Objects/object.c:2365}) is the only entry the type writes in
     * {@code tp_as_number}, so the value is not true, not false, and not a fallthrough to a length. A
     * {@code __bool__} that hands the value back is a different sentence: {@code slot_tp_bool} checks the
     * answer for being a bool, and the type it names is the answer's.
     */
    @Test
    void theValueRefusesEveryBooleanQuestion() {
        refuses(
                "TypeError: NotImplemented should not be used in a boolean context",
                () -> Slots.truth(PyNotImplemented.INSTANCE));
        refuses(
                "TypeError: __bool__ should return bool, returned NotImplementedType",
                () -> Slots.truth(new Answered()));

        // The value is not the marker: nothing that reads a truth answer treats it as a decline.
        assertThat(Slots.truth(PyList.of(PyNotImplemented.INSTANCE))).isTrue();
        assertThat(Slots.truth(PyDict.empty())).isFalse();
    }

    /**
     * {@code in} truth-tests what {@code __contains__} handed back, and asks nothing of a type that has
     * neither that slot nor an iteration protocol.
     *
     * <p>{@code slot_sq_contains} passes the answer to {@code PyObject_IsTrue}, so a containment question
     * arrives at the boolean-context refusal through a class that never mentions {@code __bool__}. The
     * refusal for a value that cannot be searched at all is containment's own wording, not {@code iter()}'s.
     */
    @Test
    void containmentTruthTestsTheAnswerAndRefusesAValueThatHasNeitherProtocol() {
        refuses(
                "TypeError: NotImplemented should not be used in a boolean context",
                () -> Slots.contains(new Answered(), PyInt.of(1)));
        refuses(
                "TypeError: argument of type 'object' is not a container or iterable",
                () -> Slots.contains(bare(), PyInt.of(1)));
        refuses(
                "TypeError: argument of type 'int' is not a container or iterable",
                () -> Slots.contains(PyInt.of(2), PyInt.of(1)));

        // The value found in a container is found by equality alone, which asks no boolean question.
        assertThat(Slots.contains(PyList.of(PyNotImplemented.INSTANCE), PyNotImplemented.INSTANCE)).isTrue();
        assertThat(Slots.contains(PySet.of(PyNotImplemented.INSTANCE), PyNotImplemented.INSTANCE)).isTrue();
    }

    /**
     * A length that is the value is a complaint about interpretation; no length at all is a different one.
     *
     * <p>{@code slot_sq_length} runs the same interpretation {@code len()} does on a Python answer, and
     * {@code PyObject_Size} writes the other sentence — so the two currencies again read as two messages,
     * and only one of them mentions {@code NotImplementedType}. The fallthrough a bare value takes is the
     * proof that a decline is not a zero.
     */
    @Test
    void aLengthAnswerIsInterpretedAndAnAbsentOneIsMissing() {
        assertThat(new Answered().length()).isSameAs(PyNotImplemented.INSTANCE);
        refuses(
                "TypeError: 'NotImplementedType' object cannot be interpreted as an integer",
                () -> Slots.length(new Answered()));
        refuses("TypeError: object of type 'object' has no len()", () -> Slots.length(bare()));
        assertThat(Slots.length(PyList.of(PyNotImplemented.INSTANCE))).isSameAs(PyInt.of(1));

        // bool() of a value with neither __bool__ nor __len__ is true, which is the isEmpty fallthrough.
        assertThat(Slots.truth(bare())).isTrue();
    }

    /**
     * {@code iter(x)} checks what it is handed; a type with no {@code tp_iter} is simply not iterable.
     *
     * <p>The check is on the answer, so the value arrives in it by name — and a decline is the other
     * message, the one that names the value that was asked instead.
     */
    @Test
    void anIterationAnswerIsCheckedAndAnAbsentOneIsMissing() {
        assertThat(new Answered().iterator()).isSameAs(PyNotImplemented.INSTANCE);
        refuses(
                "TypeError: iter() returned non-iterator of type 'NotImplementedType'",
                () -> Slots.getIter(new Answered()));
        refuses("TypeError: 'object' object is not iterable", () -> Slots.getIter(bare()));
        refuses("TypeError: 'int' object is not iterable", () -> Slots.iterator(PyInt.of(1)));
    }

    /**
     * {@code x.name} hands back the value a hook answered, and refuses only at the end of its own walk.
     *
     * <p>{@code slot_tp_getattro} returns whatever the Python-level hook returned, which is why
     * {@code hasattr} is true for a class whose {@code __getattr__} hands back {@code NotImplemented} —
     * and why {@link Slots#findAttr}, the probe that CPython's {@code PyObject_GetOptionalAttr} is, sees
     * the value too rather than nothing at all.
     */
    @Test
    void anAttributeAnswerReachesTheProgramAndAnAbsentOneIsMissing() {
        assertThat(Slots.getAttr(new Answered(), "anything")).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.findAttr(new Answered(), "anything")).isSameAs(PyNotImplemented.INSTANCE);

        assertThat(Slots.findAttr(bare(), "anything")).isNull();
        refuses(
                "AttributeError: 'object' object has no attribute 'anything'",
                () -> Slots.getAttr(bare(), "anything"));
        refuses(
                "AttributeError: 'int' object has no attribute 'anything'",
                () -> Slots.getAttr(PyInt.of(1), "anything"));
    }

    /**
     * The value is an ordinary member of every container, because {@code tp_hash} is empty.
     *
     * <p>A static type that writes {@code 0} there inherits {@code object}'s hash, so the value is a set
     * element and a dictionary key; a container's display walks it like anything else, because
     * {@code repr} of the value is text and no boolean question is asked on the way.
     */
    @Test
    void theValueIsAnOrdinaryKeyAndElement() {
        assertThat(PyNotImplemented.INSTANCE.str()).isEqualTo("NotImplemented");
        assertThat(PyNotImplemented.INSTANCE.repr()).isEqualTo("NotImplemented");
        assertThat(PyNotImplemented.INSTANCE.getType().filedName()).isEqualTo("NotImplementedType");
        assertThat(Slots.str(PyList.of(PyNotImplemented.INSTANCE))).isEqualTo("[NotImplemented]");
        assertThat(Slots.length(PySet.of(PyNotImplemented.INSTANCE))).isSameAs(PyInt.of(1));

        PyObject held = PyStr.of("v");
        PyDict keyed = PyDict.empty();
        keyed.put(PyNotImplemented.INSTANCE, held);
        assertThat(Slots.getItem(keyed, PyNotImplemented.INSTANCE)).isSameAs(held);
        assertThat(keyed.repr()).isEqualTo("{NotImplemented: 'v'}");
    }

    /**
     * The two-way protocols read both currencies as a decline, and so keep the reflected half working.
     *
     * <p>Where {@code nb_add} hands back the value, {@code int}'s {@code nb_add} still gets its turn and
     * its answer is the answer to the program; where neither side answers, the complaint is CPython's
     * rather than a leaked value; and a comparison that both sides declines falls back to identity, which
     * is what makes {@code x == x} true for a class whose {@code __eq__} returns nothing useful.
     */
    @Test
    void theTwoWayProtocolsReadBothCurrenciesAsADecline() {
        refuses(
                "TypeError: unsupported operand type(s) for +: 'object' and 'int'",
                () -> Slots.binary(BinaryOp.ADD, new Answered(), PyInt.of(1)));
        refuses(
                "TypeError: unsupported operand type(s) for +: 'object' and 'int'",
                () -> Slots.binary(BinaryOp.ADD, bare(), PyInt.of(1)));
        refuses(
                "TypeError: unsupported operand type(s) for +: 'int' and 'object'",
                () -> Slots.binary(BinaryOp.ADD, PyInt.of(1), new Answered()));

        // The survivor is the other operand's answer: the value declined, so int's turn was still asked
        // for, and what came back from the reflected half is what the program reads.
        PyObject reflects = new Answered() {
            @Override
            public PyObject reflectedOp(BinaryOp op, PyObject other) {
                return PyStr.of("reflected");
            }
        };
        assertThat(Slots.binary(BinaryOp.ADD, PyInt.of(1), reflects)).isEqualTo(PyStr.of("reflected"));

        assertThat(Slots.compare(CompareOp.EQ, PyNotImplemented.INSTANCE, PyNotImplemented.INSTANCE))
                .isSameAs(PyBool.TRUE);
        assertThat(Slots.compare(CompareOp.EQ, new Answered(), new Answered())).isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.NE, new Answered(), new Answered())).isSameAs(PyBool.TRUE);
        refuses(
                "TypeError: '<' not supported between instances of 'object' and 'int'",
                () -> Slots.compare(CompareOp.LT, new Answered(), PyInt.of(1)));
        refuses(
                "TypeError: '<' not supported between instances of 'object' and 'int'",
                () -> Slots.compare(CompareOp.LT, bare(), PyInt.of(1)));
    }

    /**
     * The one place a decline is an error rather than an answer is the protocol that has nobody else to ask.
     *
     * <p>{@code abs()} and {@code -x} run {@code nb_*} too, so a value that declines them is refused with
     * the unary wording rather than handed to {@code NotImplementedType} as a result — and the same holds
     * whether the refusal came as the value or as the marker.
     */
    @Test
    void aUnaryRefusalIsRefusedOnceAndNotPassedOn() {
        Answered refused = new Answered() {
            @Override
            public PyObject unaryOp(UnaryOp op) {
                return PyNotImplemented.INSTANCE;
            }
        };
        refuses("TypeError: bad operand type for abs(): 'object'", () -> Slots.absolute(refused));
        refuses(
                "TypeError: bad operand type for abs(): 'object'",
                () -> Slots.absolute(new Bare() {
                    @Override
                    public PyObject unaryOp(UnaryOp op) {
                        return Slots.DECLINED;
                    }
                }));
    }
}
