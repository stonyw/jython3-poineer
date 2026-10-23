package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * What a class body reads a name from, and what a frame says where the place it holds nothing.
 *
 * <p>The corpus scripts run a nested class body against the reference interpreter, so they already say which
 * of the two places answers a {@code LOAD_FROM_DICT_OR_DEREF} and what each refusal is worded like. What a
 * script cannot say is which <em>object</em> came back: a repr shows the same text for the value a
 * {@code __prepare__} handed in and for a copy of it, and the two refusals reach Python only inside a
 * traceback or a {@code type(error).__name__} string. The identity of the dictionary a method reads through
 * {@code __classdict__} is out of reach for a different reason — the name is a hidden cell, taken out of the
 * class dictionary as the class is built, so Python has no way to ask what it is the same object as.
 *
 * <p>Every expectation here is what CPython 3.14.8 does: {@code LOAD_LOCALS} increfs the frame's own
 * {@code f_locals} rather than copying it, {@code LOAD_FROM_DICT_OR_DEREF} searches that dictionary before
 * the cell (both in {@code bytecodes.c}), and the two refusals are the two halves of
 * {@code _PyEval_FormatExcUnbound} ({@code ceval.c}). The tests live in this module rather than the
 * runtime's own for the reason {@link GeneratorFrameTest} gives: a class body is only worth inspecting once
 * something compiled it, and decision D2 keeps the compiler out of {@code org.python.core}.
 */
class ClassBodyCellTest {

    @TempDir
    Path work;

    // --- the two places a nested body reads a name from ----------------------------------------

    @Test
    void theDictionaryTheBodyWasGivenAnswersBeforeTheCell() throws Exception {
        PyModule module =
                run(
                        "seeded.py",
                        """
                        marker = []

                        class Seeded(type):
                            @staticmethod
                            def __prepare__(name, bases, **kwds):
                                return {'carried': marker}

                        def build():
                            carried = ['the enclosing local']

                            class Body(metaclass=Seeded):
                                read = carried

                            return Body

                        made = build()
                        """);
        PyClass built = (PyClass) module.get("made");
        PyObject marker = module.get("marker");

        // The cell held a list that reprs identically, so the corpus can only show that the two are not
        // equal; this says the body read the very object the metaclass put in the namespace.
        assertThat(built.namespace().get(PyStr.interned("read"))).isSameAs(marker);
        // And the name the metaclass wrote is not taken out of the dictionary on the way to the class.
        assertThat(built.namespace().get(PyStr.interned("carried"))).isSameAs(marker);
    }

    @Test
    void theCellAnswersForANameNobodyHasWritten() throws Exception {
        PyModule module =
                run(
                        "cell.py",
                        """
                        stored = ['the enclosing local']

                        def build():
                            shared = stored

                            class Body:
                                read = shared

                            return Body

                        made = build()
                        """);
        PyClass built = (PyClass) module.get("made");

        // Nothing was seeded, so the cell answered, and it answered with the object the enclosing frame
        // holds. `stored` is only how the test gets a handle on that object from outside the frame.
        assertThat(built.namespace().get(PyStr.interned("read"))).isSameAs(module.get("stored"));
    }

    @Test
    void aNameTheBodyTookOverIsNotReadFromTheCellAroundIt() throws Exception {
        PyModule module =
                run(
                        "taken_over.py",
                        """
                        stored = 'the enclosing local'

                        def build():
                            shared = stored

                            class Body:
                                shared = 'the class own'
                                after = shared

                            return Body

                        made = build()
                        """);
        PyClass built = (PyClass) module.get("made");

        // The compiler never asks this instruction for a name the body assigned: it reads through
        // `LOAD_NAME`, which is why the class's own value wins over a cell of the same spelling.
        assertThat(built.namespace().get(PyStr.interned("after")))
                .isSameAs(built.namespace().get(PyStr.interned("shared")))
                .isNotSameAs(module.get("stored"));
        assertThat(((PyStr) built.namespace().get(PyStr.interned("after"))).getValue())
                .isEqualTo("the class own");
    }

    // --- the dictionary a body hands out ---------------------------------------------------------

    @Test
    void theBodyHandsOutItsOwnDictionaryNotAMomentOfIt() throws Exception {
        PyModule module =
                run(
                        "classdict.py",
                        """
                        def build():
                            class Body:
                                def reads(self):
                                    return __classdict__

                                where = reads(None)
                                seen = sorted(k for k in where if not k.startswith("__"))
                                later = 1

                            return Body

                        made = build()
                        seen_after = made().reads()
                        """);
        PyClass built = (PyClass) module.get("made");

        // The names written before the call are in what the method read and the one written after is not,
        // which is what the frame hands out: the dictionary the body is still writing into, not a copy of
        // the prologue. A snapshot taken as the cell was filled would answer with no names at all.
        assertThat(text(built.namespace().get(PyStr.interned("seen"))))
                .isEqualTo("['reads', 'where']");

        // Once the class is built the same name answers with the dictionary the class keeps its members
        // in — the cell is moved, rather than the dictionary being copied twice. CPython hands that
        // dictionary to Python through a `mappingproxy`, which this runtime does not have, so asking the
        // question in Python gets a false there and a true here; at the Java level it is one object.
        assertThat(module.get("seen_after")).isSameAs(built.namespace());
    }

    @Test
    void aNameSetOnTheClassAfterwardsIsInTheDictionaryItHandsOut() throws Exception {
        PyModule module =
                run(
                        "written_later.py",
                        """
                        def build():
                            class Body:
                                def reads(self):
                                    return __classdict__

                            return Body

                        made = build()
                        made.named_after = 'set on the class'
                        seen = made().reads()
                        """);
        PyClass built = (PyClass) module.get("made");
        PyDict seen = (PyDict) module.get("seen");

        // Not a snapshot taken as the class was built: the name written afterwards is in the dictionary
        // the method reads, because it is the class's own.
        assertThat(seen.get(PyStr.interned("named_after")))
                .isSameAs(built.namespace().get(PyStr.interned("named_after")));
        assertThat(((PyStr) seen.get(PyStr.interned("named_after"))).getValue())
                .isEqualTo("set on the class");
    }

    // --- the two refusals ------------------------------------------------------------------------

    @Test
    void anEmptyCellRefusesAboutTheEnclosingScope() {
        assertThatThrownBy(
                        () ->
                                run(
                                        "empty_cell.py",
                                        """
                                        def build():
                                            class Body:
                                                read = carried

                                            carried = 'filled after the body ran'
                                            return Body

                                        build()
                                        """))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> {
                            // A free variable is somebody else's local, so the refusal is a `NameError`
                            // and it names the scope that owns the name.
                            assertThat(raised.value().exceptionType())
                                    .isSameAs(ExceptionTypes.NAME_ERROR);
                            assertThat(raised.getMessage())
                                    .isEqualTo(
                                            "NameError: cannot access free variable 'carried' "
                                                    + "where it is not associated with a value"
                                                    + " in enclosing scope");
                        });
    }

    @Test
    void anEmptyLocalRefusesAboutThisScope() {
        assertThatThrownBy(
                        () ->
                                run(
                                        "empty_local.py",
                                        """
                                        def reads(kept):
                                            if kept:
                                                carried = 'here'
                                            return carried

                                        reads(False)
                                        """))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> {
                            // The other half of the same decision, and a different class: this frame owns
                            // the name, so nothing about an enclosing scope is said.
                            assertThat(raised.value().exceptionType())
                                    .isSameAs(ExceptionTypes.UNBOUND_LOCAL_ERROR);
                            assertThat(raised.getMessage())
                                    .isEqualTo(
                                            "UnboundLocalError: cannot access local variable"
                                                    + " 'carried' where it is not associated"
                                                    + " with a value");
                        });
    }

    // --- running a program -------------------------------------------------------------------------

    private PyModule run(String name, String program) throws IOException {
        Cpython314Compiler.Compiled compiled =
                Cpython314Compiler.compileFile(Files.writeString(work.resolve(name), program));
        PyModule module = new PyModule("workshop");
        PyRuntime.exec(compiled.code(), module, compiled.fileName());
        return module;
    }

    /** What the script itself would have printed for this value. */
    private static String text(PyObject value) {
        return value instanceof PyStr string ? string.getValue() : Slots.repr(value);
    }
}
