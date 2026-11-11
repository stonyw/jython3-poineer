package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PEP 649's module half: {@code module.__annotations__} is computed from {@code module.__annotate__}
 * the first time it is read and kept, and the two names keep the invariants CPython's module object
 * does.
 *
 * <p>A script cannot reach its own module object in this runtime — {@code sys.modules} has no
 * {@code __main__} entry and {@code types.ModuleType} does not exist — so the rules are asked of a
 * module made here. The corpus measures the annotate functions a body leaves behind; this measures
 * what reading them through a module does.
 */
class ModuleAnnotationsTest {

    /** An annotate function that answers {@code answer} and records every format it was asked for. */
    private static PyNativeFunction annotate(PyObject answer, List<PyObject> asked) {
        return new PyNativeFunction("__annotate__", (args, kwNames) -> {
            asked.add(args[0]);
            return answer;
        });
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    @Test
    void readingAnnotationsCallsTheAnnotateFunctionOnceAndKeepsItsAnswer() {
        PyDict answer = PyDict.empty();
        answer.put(PyStr.interned("x"), PyType.INT);
        List<PyObject> asked = new ArrayList<>();
        PyModule module = new PyModule("m");
        module.set("__annotate__", annotate(answer, asked));

        assertThat(Slots.getAttr(module, "__annotations__")).isSameAs(answer);
        assertThat(asked).containsExactly(PyFormat.VALUE);
        assertThat(Slots.getAttr(module, "__annotations__")).isSameAs(answer);
        assertThat(asked).hasSize(1);
        assertThat(Slots.getAttr(module, "__annotate__")).isNotSameAs(PyNone.INSTANCE);
    }

    @Test
    void aModuleWithNoAnnotateFunctionAnswersAnEmptyDictionaryAndKeepsIt() {
        PyModule module = new PyModule("n");
        PyObject first = Slots.getAttr(module, "__annotations__");

        assertThat(first).isInstanceOf(PyDict.class);
        assertThat(((PyDict) first).size()).isZero();
        assertThat(Slots.getAttr(module, "__annotations__")).isSameAs(first);
        assertThat(module.get("__annotations__")).isSameAs(first);
    }

    @Test
    void readingAMissingAnnotateFunctionFilesNone() {
        PyModule module = new PyModule("n");

        assertThat(Slots.getAttr(module, "__annotate__")).isSameAs(PyNone.INSTANCE);
        assertThat(module.get("__annotate__")).isSameAs(PyNone.INSTANCE);
    }

    @Test
    void writingAnnotationsForgetsTheAnnotateFunction() {
        List<PyObject> asked = new ArrayList<>();
        PyModule module = new PyModule("m");
        module.set("__annotate__", annotate(PyDict.empty(), asked));
        PyDict written = PyDict.empty();

        Slots.setAttr(module, "__annotations__", written);

        assertThat(Slots.getAttr(module, "__annotations__")).isSameAs(written);
        assertThat(Slots.getAttr(module, "__annotate__")).isSameAs(PyNone.INSTANCE);
        assertThat(asked).isEmpty();
    }

    @Test
    void annotateMustBeCallableOrNone() {
        PyModule module = new PyModule("m");

        refuses("TypeError: __annotate__ must be callable or None",
                () -> Slots.setAttr(module, "__annotate__", PyInt.of(3)));
        Slots.setAttr(module, "__annotate__", PyNone.INSTANCE);
        assertThat(Slots.getAttr(module, "__annotate__")).isSameAs(PyNone.INSTANCE);
    }

    @Test
    void aNonDictAnswerIsRefused() {
        PyModule module = new PyModule("m");
        module.set("__annotate__", annotate(PyNone.INSTANCE, new ArrayList<>()));

        refuses("TypeError: __annotate__ returned non-dict of type 'NoneType'",
                () -> Slots.getAttr(module, "__annotations__"));
    }

    @Test
    void deletingAnnotationsDropsBothNamesAndThenRefuses() {
        PyModule module = new PyModule("m");
        module.set("__annotate__", annotate(PyDict.empty(), new ArrayList<>()));
        Slots.getAttr(module, "__annotations__");

        Slots.delAttr(module, "__annotations__");

        assertThat(module.get("__annotations__")).isNull();
        assertThat(module.get("__annotate__")).isNull();
        refuses("AttributeError: __annotations__", () -> Slots.delAttr(module, "__annotations__"));
    }

    @Test
    void deletingAnnotateIsRefused() {
        PyModule module = new PyModule("m");

        refuses("TypeError: cannot delete __annotate__ attribute",
                () -> Slots.delAttr(module, "__annotate__"));
    }
}
