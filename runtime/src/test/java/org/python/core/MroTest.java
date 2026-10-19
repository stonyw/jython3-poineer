package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The linearization a class inherits by, measured against CPython 3.14.8.
 *
 * <p>Every order and every message below is what {@code type(name, bases, {})} answers in CPython for
 * the same shapes; {@link PyType#TYPE} taking three arguments is that call, so the runtime is asked the
 * very question the reference interpreter was. The orders are C3's, the conflicts are CPython's, and a
 * conflict names the bases that could not be merged.
 */
class MroTest {

    /** {@code type(name, bases, {})} — a class made the way {@code __build_class__} ends up making it. */
    private static PyType type(String name, PyType... bases) {
        PyObject made =
                PyType.TYPE.call(
                        new PyObject[] {PyStr.of(name), PyTuple.of(bases), PyDict.empty()}, null);
        return (PyType) made;
    }

    /** {@code [t.__name__ for t in cls.__mro__]}, as Java can assert on it. */
    private static List<String> names(PyType type) {
        PyTuple mro = (PyTuple) Slots.getAttr(type, "__mro__");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < mro.size(); i++) {
            names.add(((PyType) mro.at(i)).name());
        }
        return names;
    }

    @Test
    void aChainOfSingleBasesIsTheChainItselfWithObjectLast() {
        PyType a = type("A");
        PyType b = type("B", a);
        PyType c = type("C", b);

        assertThat(names(a)).containsExactly("A", "object");
        assertThat(names(c)).containsExactly("C", "B", "A", "object");
        assertThat(Slots.getAttr(c, "__base__")).isSameAs(b);
        assertThat(((PyTuple) Slots.getAttr(c, "__bases__")).size()).isEqualTo(1);
    }

    @Test
    void noBasesAtAllStillDescendsFromObject() {
        assertThat(names(type("N"))).containsExactly("N", "object");
    }

    @Test
    void aDiamondKeepsTheBaseThatBothSidesShareBehindBothOfThem() {
        PyType a = type("A");
        PyType b = type("B", a);
        PyType c = type("C", a);
        PyType d = type("D", b, c);

        assertThat(names(d)).containsExactly("D", "B", "C", "A", "object");
        assertThat(names(type("E", d)))
                .containsExactly("E", "D", "B", "C", "A", "object");
    }

    @Test
    void namingObjectAsABaseAlongsideAnotherIsAllowedAndSaysNothing() {
        PyType a = type("A");
        PyType b = type("B", a);

        assertThat(names(type("Y", b, PyType.OBJECT))).containsExactly("Y", "B", "A", "object");
    }

    @Test
    void unrelatedRootsMergeInTheOrderTheyWereGiven() {
        PyType a = type("A");
        PyType b = type("B", a);
        PyType c = type("C", a);
        PyType m1 = type("M1");
        PyType m2 = type("M2");

        assertThat(names(type("F", m1, m2, a))).containsExactly("F", "M1", "M2", "A", "object");
        assertThat(names(type("R", c, b, a))).containsExactly("R", "C", "B", "A", "object");
    }

    @Test
    void aBaseListedBeforeItsOwnSubclassHasNoConsistentOrder() {
        PyType a = type("A");
        PyType b = type("B", a);

        assertThatThrownBy(() -> type("G", a, b))
                .isInstanceOf(PyRaise.class)
                .hasMessage(
                        "TypeError: Cannot create a consistent method resolution"
                            + " order (MRO) for bases A, B");
    }

    @Test
    void twoSubclassesThatOrderTheirRootsDifferentlyCannotBeMerged() {
        PyType m1 = type("M1");
        PyType m2 = type("M2");
        PyType m3 = type("M3", m1, m2);
        PyType m4 = type("M4", m2, m1);

        assertThatThrownBy(() -> type("M5", m3, m4))
                .isInstanceOf(PyRaise.class)
                .hasMessage(
                        "TypeError: Cannot create a consistent method resolution"
                            + " order (MRO) for bases M1, M2");
    }

    @Test
    void theSameClassTwiceInBasesIsRefusedBeforeTheOrderIsEvenAsked() {
        PyType b = type("B");

        assertThatThrownBy(() -> type("Dup", b, b))
                .isInstanceOf(PyRaise.class)
                .hasMessage("TypeError: duplicate base class B");
    }

    @Test
    void aClassIsItsOwnSubclassAndEveryOneOfItsBasesIs() {
        PyType a = type("A");
        PyType b = type("B", a);
        PyType c = type("C", b);

        assertThat(c.matches(c)).isTrue();
        assertThat(c.matches(a)).isTrue();
        assertThat(a.matches(c)).isFalse();
        assertThat(c.matches(PyType.OBJECT)).isTrue();
        assertThat(a.matches(PyType.INT)).isFalse();
    }

    @Test
    void aBuiltInTypeAnswersItsOneParentAsItsWholeOrder() {
        assertThat(names(PyType.BOOL)).containsExactly("bool", "int", "object");
        assertThat(names(PyType.INT)).containsExactly("int", "object");
    }
}
