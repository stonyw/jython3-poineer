package org.python.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.python.core.PyInt;
import org.python.core.PyModule;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PyRaise;
import org.python.core.PyRuntime;
import org.python.core.PyStr;
import org.python.core.PyTuple;
import org.python.interop.fixtures.Panel;
import org.python.interop.fixtures.Widget;
import org.python.javaclass.PyJavaThrowable;

/**
 * MVP④: Java drives Python, in the same process, through the same runtime the launcher uses.
 *
 * <p>These run in process rather than as a child JVM, which is the opposite of
 * {@link Jy3LauncherTest} and correct for the same reason: what is being measured is the Java API, and
 * an API only exists on the inside of a JVM. The reference for each literal is the Java call it stands
 * for — the list a caller can assert on, not a number copied out of a Python session.
 *
 * <p>Three crossings are tested, because each fails alone: a Java value going in, a Python value
 * coming back, and a Python callable wearing a Java interface. Failures are the fourth. A Python
 * exception has to arrive in Java as something a {@code catch} can act on, and a Java exception caught
 * in Python has to keep the Java methods its own code would have called.
 */
class PythonInterpreterTest {

    // --- snippets ------------------------------------------------------------------------

    @Test
    void execRunsStatementsAndEvalAnswersWithTheValue() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("""
                total = 0
                for n in range(5):
                    total += n
                """);

        assertThat(py.eval("total")).isEqualTo(10);
        assertThat(py.getPyObject("total")).isInstanceOf(PyInt.class);
        assertThat(py.eval("1 + 1")).isEqualTo(2);
        assertThat(py.eval("'jy'.upper()")).isEqualTo("JY");
        assertThat(py.eval("None")).isNull();
    }

    @Test
    void evalWithAClassAnswersInThatClass() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("text = '42'\ncount = 7\nrows = [1, 2]\nlookup = {'a': 1}\n");

        assertThat(py.eval("text", String.class)).isEqualTo("42");
        assertThat(py.eval("count", Long.class)).isEqualTo(7L);
        assertThat(py.eval("count * count", Integer.class)).isEqualTo(49);
        assertThat(py.eval("rows", List.class)).containsExactly(1, 2);
        assertThat(py.eval("lookup", Map.class)).containsEntry("a", 1);
    }

    @Test
    void oneInterpreterKeepsItsNamespaceAcrossCalls() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("def square(n):\n    return n * n\n");
        py.exec("squares = [square(n) for n in range(4)]\n");

        assertThat(py.get("squares", List.class)).containsExactly(0, 1, 4, 9);
    }

    @Test
    void twoInterpretersDoNotSeeEachOtherButInterpSharesTheMainNamespace() {
        PythonInterpreter first = new PythonInterpreter();
        PythonInterpreter second = new PythonInterpreter();

        first.exec("only_here = 1\n");

        assertThat(first.get("only_here")).isEqualTo(1);
        assertThat(second.get("only_here")).isNull();

        // interp() is the namespace a script runs in, so it is the one an embedder shares with one.
        PythonInterpreter main = PythonInterpreter.interp();
        main.exec("shared = 6 * 7\n");
        assertThat(PythonInterpreter.interp().get("shared")).isEqualTo(42);
        assertThat(new PythonInterpreter().get("shared")).isNull();
    }

    @Test
    void everyInterpreterReadsAsAModuleTopLevel() {
        assertThat(new PythonInterpreter().eval("__name__")).isEqualTo("__main__");
        assertThat(PythonInterpreter.interp().eval("__name__")).isEqualTo("__main__");
    }

    // --- importing -----------------------------------------------------------------------

    /**
     * A star import takes the names a module publishes, in the order it publishes them.
     *
     * <p>This is the half of {@code from java.util import *} that belongs to Python, so it is tested on a
     * module built here rather than on a {@code .py} file — a file on a path is the M4 finder's business.
     * The two names left out are the point: {@code beetroot} is public but not listed, and {@code __all__}
     * says the module's exports are not "everything without an underscore" any more.
     */
    @Test
    void aStarImportTakesExactlyThePublishedNames() {
        PyModule veg = publishedModule("veg");
        veg.set("__all__", PyTuple.of(PyStr.of("celeriac"), PyStr.of("carrot")));

        PythonInterpreter py = new PythonInterpreter();
        py.exec("from veg import *");

        assertThat(py.get("celeriac")).isEqualTo(3);
        assertThat(py.get("carrot")).isEqualTo(1);
        assertThat(py.get("beetroot")).isNull();
        assertThat(py.get("_cellar")).isNull();
    }

    /** Without {@code __all__}, a star import takes every name that does not begin with an underscore. */
    @Test
    void aStarImportWithoutAPublishedListTakesThePublicNames() {
        publishedModule("fruit");

        PythonInterpreter py = new PythonInterpreter();
        py.exec("from fruit import *");

        assertThat(py.get("carrot")).isEqualTo(1);
        assertThat(py.get("beetroot")).isEqualTo(2);
        assertThat(py.get("_cellar")).isNull();
    }

    /** A module in the import system, with three names on it and one of them private. */
    private static PyModule publishedModule(String name) {
        PyModule module = new PyModule(name);
        module.set("carrot", PyInt.of(1));
        module.set("beetroot", PyInt.of(2));
        module.set("celeriac", PyInt.of(3));
        module.set("_cellar", PyInt.of(4));
        PyRuntime.addModuleFinder(dotted -> dotted.equals(name) ? module : null);
        return module;
    }

    // --- going in ------------------------------------------------------------------------

    @Test
    void javaValuesSetOnTheInterpreterAreThePythonValuesTheyStandFor() {
        PythonInterpreter py = new PythonInterpreter();
        py.exec("from org.python.interop.fixtures import Widget\n");

        py.set("count", 7);
        py.set("text", "hi");
        py.set("ratio", 1.5);
        py.set("flag", true);
        py.set("nothing", null);
        py.set("items", List.of("a", "b"));
        py.set("widget", new Widget("iron", 3));

        assertThat(py.eval("count * 2")).isEqualTo(14);
        assertThat(py.eval("text.upper()")).isEqualTo("HI");
        assertThat(py.eval("ratio + 0.5")).isEqualTo(2.0);
        assertThat(py.eval("flag")).isEqualTo(true);
        assertThat(py.getPyObject("nothing")).isSameAs(PyNone.INSTANCE);
        // A Java collection stays the Java collection: Python sees its size, not a copy of it.
        assertThat(py.eval("len(items)")).isEqualTo(2);
        assertThat(py.eval("items[0] + items[1]")).isEqualTo("ab");
        assertThat(py.eval("widget.size")).isEqualTo(3);
        assertThat(py.eval("isinstance(widget, Widget)")).isEqualTo(true);
    }

    @Test
    void aPythonFunctionCalledFromJavaTakesItsJavaArguments() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("""
                def shout(word, times):
                    return ' '.join([word] * times)

                def size(xs):
                    return len(xs)
                """);

        assertThat(py.invoke("shout", "hi", 2)).isEqualTo("hi hi");
        assertThat(py.invoke("size", List.of("a", "b", "c"))).isEqualTo(3);
        assertThat(py.invoke("size", "abcd")).isEqualTo(4);
    }

    @Test
    void aMissingNameIsANamedFailureRatherThanANull() {
        PythonInterpreter py = new PythonInterpreter();

        assertThatThrownBy(() -> py.invoke("noSuchFunction"))
                .isInstanceOf(PyRaise.class)
                .hasMessage("NameError: name 'noSuchFunction' is not defined");
    }

    // --- coming back ---------------------------------------------------------------------

    /** The acceptance sentence for MVP④, in its plainest form. */
    @Test
    void javaCallsAPythonFunctionAndGetBackAJavaList() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("""
                def build(n):
                    return [i * i for i in range(n)]
                """);

        Object back = py.invoke("build", 4);

        assertThat(back).isInstanceOf(List.class);
        assertThat(back).isEqualTo(List.of(0, 1, 4, 9));
        assertThat(py.invoke("build", 4).toString()).isEqualTo("[0, 1, 4, 9]");
    }

    @Test
    void containersComeBackAsTheJavaCollectionsTheyLookLike() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("""
                words = {'one': 1, 'two': 2}
                pair = ('a', 1)
                nothing = None

                def noop():
                    pass
                """);

        assertThat(py.get("words", Map.class)).containsEntry("one", 1).containsEntry("two", 2);
        assertThat(py.get("pair", List.class)).containsExactly("a", 1);
        assertThat(py.get("nothing")).isNull();
        // A function has no Java counterpart, so it comes back as the Python object it is.
        assertThat(py.get("noop")).isInstanceOf(PyObject.class);
    }

    // --- wearing a Java interface --------------------------------------------------------

    @Test
    void aSingleMethodInterfaceIsAnsweredByTheCallableItself() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("""
                log = []

                def note():
                    log.append('ran')

                square = lambda n: n * n
                """);

        Runnable callback = py.get("note", Runnable.class);
        callback.run();

        assertThat(py.get("log", List.class)).containsExactly("ran");

        Function<Object, Object> square = py.get("square", Function.class);
        assertThat(square.apply(5)).isEqualTo(25);
    }

    @Test
    void anInterfaceWithManyMethodsIsAnsweredByANamespace() {
        PythonInterpreter py = new PythonInterpreter();

        py.exec("""
                opened = []

                def onOpen(title):
                    opened.append(title)

                def onClose(code, reason):
                    return code * 100 + len(reason)
                """);

        Panel panel = py.export(Panel.class);
        panel.onOpen("home");

        assertThat(py.get("opened", List.class)).containsExactly("home");
        assertThat(panel.onClose(2, "bye")).isEqualTo(203);
    }

    @Test
    void anInterfaceMethodNoNamespaceProvidesFailsAsJavaRatherThanAsPython() {
        PythonInterpreter py = new PythonInterpreter();
        py.exec("def onOpen(title):\n    pass\n");

        Panel panel = py.export(Panel.class);

        // A missing member is a Java failure, because it is Java that asked for a method it does not
        // have. Turning it into a Python TypeError would make the caller chase a stack they are not on.
        assertThatThrownBy(() -> panel.onClose(1, "x"))
                .isInstanceOf(AbstractMethodError.class)
                .hasMessageContaining("onClose");
    }

    // --- failures across the boundary ----------------------------------------------------

    @Test
    void aPythonExceptionReachesTheJavaCallerAsACatchableRuntimeException() {
        PythonInterpreter py = new PythonInterpreter();
        py.exec("def boom():\n    raise ValueError('bad value')\n");

        assertThatThrownBy(() -> py.invoke("boom"))
                .isInstanceOf(PyRaise.class)
                .hasMessage("ValueError: bad value")
                .extracting(throwable -> ((PyRaise) throwable).value().str())
                .isEqualTo("bad value");
    }

    @Test
    void aPythonFailureInsideAnInterfaceMethodCrossesBackIntoJavaUnchanged() {
        PythonInterpreter py = new PythonInterpreter();
        py.exec("def bad(n):\n    raise ValueError('not a number')\n");

        Function<Object, Object> function = py.get("bad", Function.class);

        // The failure leaves Python, crosses a Java method boundary and must not come back as a
        // different exception, which is what a reflection call is free to do with it.
        assertThatThrownBy(() -> function.apply(1))
                .isInstanceOf(PyRaise.class)
                .hasMessage("ValueError: not a number");
    }

    /**
     * A Java failure, handed to Python, keeps the Java methods on it.
     *
     * <p>The exception reaches Python by being set rather than by an {@code except} clause, because
     * {@code try} needs the code object's exception table, which is M3. What is being pinned here is
     * the object, not the handler: whatever route a Java {@code Throwable} takes into Python, it arrives
     * as a Python exception that still answers {@code getMessage()}.
     */
    @Test
    void aJavaExceptionThatReachesPythonAnswersItsJavaMethods() {
        PythonInterpreter py = new PythonInterpreter();
        py.exec("""
                from org.python.interop.fixtures import Widget

                def relay():
                    Widget().boom()
                """);

        PyRaise raised = assertThrows(
                PyRaise.class, () -> py.invoke("relay"));

        py.set("failure", raised.value());

        assertThat(py.eval("type(failure).__name__")).isEqualTo("IllegalStateException");
        assertThat(py.eval("failure.getMessage()")).isEqualTo("widget is broken");
        assertThat(py.eval("str(failure)")).isEqualTo("widget is broken");
        assertThat(py.eval("failure.args")).isEqualTo(List.of("widget is broken"));
        // The Java hierarchy is joined to Python's at Throwable, so an ordinary handler will do.
        assertThat(py.eval("isinstance(failure, Exception)")).isEqualTo(true);
        assertThat(((PyJavaThrowable) raised.value()).throwable())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aJavaFailureThroughPythonIsStillTheJavaFailureItStartedAs() {
        PythonInterpreter py = new PythonInterpreter();
        py.exec("""
                from org.python.interop.fixtures import Widget

                def relay():
                    Widget().boom()
                """);

        Runnable callback = py.get("relay", Runnable.class);

        // Java asked for a Runnable; Python called Java; Java threw. The Throwable has to be the one
        // that was thrown, not a reconstruction of it, or a caller's catch clause cannot compare it.
        assertThatThrownBy(callback::run)
                .isInstanceOf(PyRaise.class)
                .extracting(throwable -> ((PyJavaThrowable) ((PyRaise) throwable).value()).throwable())
                .satisfies(original -> assertThat(original)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("widget is broken"));
    }
}
