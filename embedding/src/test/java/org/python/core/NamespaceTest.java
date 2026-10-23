package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * The four builtins that answer a scope, read from the side a script cannot stand on.
 *
 * <p>The corpus scripts run every shape Python can write against the reference interpreter, so what is left
 * for Java is the two questions a running frame cannot ask about itself. The first is which dictionary
 * answers when there is <em>no</em> frame: 3.14 sends {@code locals()}, {@code globals()} and {@code vars()}
 * to the globals of the interpreter's running {@code __main__}, and only a Java caller can be in that state,
 * because a script is always inside the very frame it is asking about. The second is which object came back
 * — a repr shows the same text for a live dictionary and for a copy of it, and the difference between
 * {@code locals()} and {@code frame.f_locals}, or between {@code vars(class)} and 3.14's read-only {@code
 * mappingproxy}, is exactly that difference.
 *
 * <p>Every expectation here is what CPython 3.14.8 does. {@code builtin_vars} is the {@code __dict__} read
 * and nothing more ({@code bltinmodule.c}); {@code _PyEval_GetFrameLocals} hands a module or a class body
 * the dictionary its names are written into, and hands a function frame a fresh dict filled from the slots
 * it holds ({@code ceval.c}); and {@code dir()} with no argument is {@code sorted(locals())} of whichever
 * of those it just got. The tests live here rather than in the runtime's own tree for the reason {@link
 * GeneratorFrameTest} gives: a frame is only worth inspecting once something has compiled it, and decision
 * D2 keeps the compiler out of {@code org.python.core}.
 */
class NamespaceTest {

    @TempDir
    Path work;

    // --- asked with no frame running -------------------------------------------------------------

    /**
     * With nothing executing, all three builtins answer the running {@code __main__}'s own dictionary —
     * the same object rather than a copy of it, which is what makes a name written through one of them a
     * name the next line the same caller executes can read.
     */
    @Test
    void withNoFrameTheThreeAnswerTheRunningMain() {
        PyDict held = PyRuntime.mainModule().dictionary();

        assertThat(answer("locals")).isSameAs(held);
        assertThat(answer("globals")).isSameAs(held);
        assertThat(answer("vars")).isSameAs(held);
    }

    /** {@code dir()} with no argument is that same dictionary sorted, so it names exactly what it holds. */
    @Test
    void withNoFrameDirSortsWhatTheRunningMainHolds() {
        List<String> held = new ArrayList<>();
        for (PyObject key : PyRuntime.mainModule().dictionary().keys()) {
            held.add(((PyStr) key).getValue());
        }
        Collections.sort(held);

        assertThat(names(answer("dir"))).isEqualTo(held);
        // The names the runtime seeds a module with are why a scope's list is not only the names a script
        // wrote — `__builtins__` included, which is in 3.14's too.
        assertThat(held).contains("__name__", "__doc__", "__builtins__");
    }

    // --- asked inside a frame a Java caller entered ------------------------------------------------

    /**
     * A function's frame is a frame even when Java called it, so the three answer that frame and not the
     * {@code __main__} below it — with a dictionary made for the call, and with the star tuple and the
     * keyword dictionary among the names in it.
     *
     * <p>The short call is the one that used to fall over: {@code make(1)} gives fewer positionals than the
     * function declares parameters, and cutting {@code *rest} out of that range asked Java for a negative
     * length before the frame ever ran.
     */
    @Test
    void aFrameJavaEnteredAnswersItsOwnSnapshot() throws Exception {
        PyModule module =
                run(
                        "snapshot.py",
                        """
                        def make(a, b=2, *rest, **keywords):
                            return locals()
                        """);
        PyObject make = module.get("make");

        PyDict shortCall = (PyDict) Slots.call(make, new PyObject[] {PyInt.of(1)}, null);
        assertThat((PyObject) shortCall).isNotSameAs(PyRuntime.mainModule().dictionary());
        assertThat(number(shortCall.get(PyStr.interned("a")))).isEqualTo(1);
        assertThat(number(shortCall.get(PyStr.interned("b")))).isEqualTo(2);
        assertThat(shortCall.get(PyStr.interned("rest")))
                .isInstanceOfSatisfying(PyTuple.class, star -> assertThat(star.size()).isZero());
        assertThat(shortCall.get(PyStr.interned("keywords")))
                .isInstanceOfSatisfying(PyDict.class, none -> assertThat(none.keys()).isEmpty());

        // `make(1, 2, 3, extra=4)`, in the convention the interpreter calls with: a keyword's value is one
        // of the trailing entries of `args`, so three of the four values arrived positionally.
        PyDict filled =
                (PyDict)
                        Slots.call(
                                make,
                                new PyObject[] {PyInt.of(1), PyInt.of(2), PyInt.of(3), PyInt.of(4)},
                                PyTuple.of(PyStr.of("extra")));
        assertThat(number(filled.get(PyStr.interned("a")))).isEqualTo(1);
        assertThat(number(filled.get(PyStr.interned("b")))).isEqualTo(2);
        assertThat(filled.get(PyStr.interned("rest")))
                .isInstanceOfSatisfying(
                        PyTuple.class, star -> assertThat(number(star.at(0))).isEqualTo(3));
        assertThat(filled.get(PyStr.interned("keywords")))
                .isInstanceOfSatisfying(
                        PyDict.class,
                        given ->
                                assertThat(number(given.get(PyStr.interned("extra")))).isEqualTo(4));

        // The snapshot lists the gathered names as well as the declared ones, and two calls are two
        // dictionaries: nothing is kept between them.
        List<String> listed = new ArrayList<>();
        for (PyObject key : filled.keys()) {
            listed.add(((PyStr) key).getValue());
        }
        Collections.sort(listed);
        assertThat(listed).containsExactly("a", "b", "keywords", "rest");
        assertThat((PyObject) Slots.call(make, new PyObject[] {PyInt.of(1)}, null))
                .isNotSameAs((PyObject) Slots.call(make, new PyObject[] {PyInt.of(1)}, null));
    }

    // --- the answers that are somebody else's dictionary -------------------------------------------

    /**
     * {@code vars(value)} is the attribute read and nothing more, so the answer is the value's own
     * dictionary and a name written into it is a name the value then has.
     */
    @Test
    void varsOfAValueIsTheDictionaryThatValueKeeps() throws Exception {
        PyModule module =
                run(
                        "kept.py",
                        """
                        class Held:
                            pass

                        kept = Held()
                        """);
        PyObject kept = module.get("kept");

        assertThat(answer("vars", kept)).isSameAs(Slots.getAttr(kept, "__dict__"));

        PyDict held = (PyDict) answer("vars", kept);
        held.put(PyStr.interned("written"), PyInt.of(7));
        assertThat(Slots.getAttr(kept, "written")).isSameAs(held.get(PyStr.interned("written")));
        assertThat(number(Slots.getAttr(kept, "written"))).isEqualTo(7);
    }

    /**
     * A class answers the namespace it was built with, because this runtime has no {@code mappingproxy}:
     * 3.14 hands out a read-only view of that same dictionary, so the question asked in Python gets a false
     * there and a true here, while at the Java level it is one object either way — the shape {@link
     * ClassBodyCellTest} gives the same reasoning for.
     */
    @Test
    void varsOfAClassIsTheNamespaceTheClassWasBuiltWith() throws Exception {
        PyModule module =
                run(
                        "aclass.py",
                        """
                        class Held:
                            member = 1
                        """);
        PyClass held = (PyClass) module.get("Held");

        assertThat(answer("vars", held)).isSameAs(held.namespace());
        assertThat(number(held.namespace().get(PyStr.interned("member")))).isEqualTo(1);
    }

    // --- running and reading -----------------------------------------------------------------------

    /** A builtin of the running runtime, asked the way Python would ask it. */
    private static PyObject builtin(String name) {
        return PyRuntime.builtins().get(PyStr.interned(name));
    }

    /** The same builtin called with no argument at all, from Java, with no frame above the call. */
    private static PyObject answer(String name) {
        return Slots.call(builtin(name), new PyObject[0], null);
    }

    /** The same builtin called with one positional argument. */
    private static PyObject answer(String name, PyObject argument) {
        return Slots.call(builtin(name), new PyObject[] {argument}, null);
    }

    /** The text of a {@code dir()}-shaped answer. */
    private static List<String> names(PyObject answered) {
        List<String> texts = new ArrayList<>();
        for (PyObject item : ((PyList) answered).getItems()) {
            texts.add(((PyStr) item).getValue());
        }
        return texts;
    }

    /** The value an {@code int} carries. */
    private static long number(PyObject value) {
        return ((PyInt) value).getValue().longValue();
    }

    private PyModule run(String name, String program) throws IOException {
        Cpython314Compiler.Compiled compiled =
                Cpython314Compiler.compileFile(Files.writeString(work.resolve(name), program));
        PyModule module = new PyModule("workshop");
        PyRuntime.exec(compiled.code(), module, compiled.fileName());
        return module;
    }
}
