package org.python.interop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.python.util.Jy3Process.jy3;
import static org.python.util.Jy3Process.lastLine;
import static org.python.util.Jy3Process.write;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.util.Jy3Process.Result;

/**
 * MVP② and MVP③: Java types, reached from Python, answering the way Java answers.
 *
 * <p>The reference for these is {@link WidgetOracleTest}, which makes the same calls in Java. Where a
 * spelling is Python's rather than Java's — {@code len(x)} for {@code size()}, {@code x[k]} for
 * {@code get(k)}, {@code x.name = v} for {@code setName(v)} — the mapping is the one Jython 2.7
 * established, and each test says which.
 *
 * <p>Two of these are deliberately about failure, because an interop layer that cannot say "no such
 * method" is worse than one that can: {@code aMissingMemberIsAnAttributeError} and
 * {@code noMatchingOverloadNamesTheCandidates}.
 */
class JavaFromPythonTest {

    @TempDir
    Path work;

    @Test
    void everyJavaImportSpellingBindsWhatPythonExpects() throws Exception {
        String out = run("""
                import java.util
                import java.util as util
                from java.util import ArrayList
                from java.lang import Integer, Math

                names = java.util.ArrayList()
                names.add("first")
                print(names.size(), names.get(0))
                print(util.ArrayList is ArrayList, isinstance(util, type(util)))
                print(type(java).__name__, type(util).__name__)
                print(Integer.parseInt("42"), Integer.MAX_VALUE)
                print(Math.max(3, 7), Math.PI)
                """);

        // `import java.util` binds the head of the chain and `import ... as` binds the leaf, which is
        // CPython's rule and the one worth testing: only the head spelling makes `java.util.ArrayList`
        // an attribute walk.
        assertThat(out).isEqualTo("""
                1 first
                True True
                module module
                42 2147483647
                7 3.141592653589793
                """);
    }

    @Test
    void constructorsFieldsAndToStringMatchTheJavaCalls() throws Exception {
        String out = run("""
                from org.python.interop.fixtures import Widget

                empty = Widget()
                sized = Widget(7)
                named = Widget("iron", 3)
                print(empty, sized, named)
                print(empty.size, sized.area(), named.area())
                print(empty.name)
                """);

        assertThat(out).isEqualTo("""
                Widget[unnamed,0] Widget[sized,7] Widget[iron,3]
                0 49 9
                unnamed
                """);
    }

    @Test
    void staticMethodsAreChosenByArgumentTypeTheWayJavaChoosesThem() throws Exception {
        String out = run("""
                from org.python.interop.fixtures import Widget

                print(Widget.describe(5))
                print(Widget.describe("iron"))
                print(Widget.describe(2, 9))
                print(Widget.MAX_SIZE, Widget.LABEL)
                print(Widget.widest(3, 4.5), Widget.widest(9, 4.5))
                """);

        assertThat(out).isEqualTo("""
                widget:5
                widget/iron
                widget:2@9
                9 widget
                4 9
                """);
    }

    @Test
    void aPythonValueFitsAJavaParameterThatIsWiderOrBoxed() throws Exception {
        String out = run("""
                from org.python.interop.fixtures import Widget
                from java.math import BigInteger

                print(Widget.count(["a", "b", "c"]))
                print(Widget.widest(BigInteger.valueOf(4), 2.5))
                print(Widget.widest(2, 2.5))
                """);

        assertThat(out).isEqualTo("""
                3
                4
                2
                """);
    }

    @Test
    void beanPropertiesAreAttributesAndTheirNamesAreHidden() throws Exception {
        String out = run("""
                from org.python.interop.fixtures import Widget

                widget = Widget()
                print(widget.name, widget.polished)
                widget.name = "copper"
                widget.polished = True
                print(widget, widget.polished)
                print(widget.getName(), widget.setName is not None)
                """);

        assertThat(out).isEqualTo("""
                unnamed False
                Widget[copper,0] True
                copper True
                """);
    }

    /** Jython's mapping of Python's container protocol onto the collections classes. */
    @Test
    void javaCollectionsAnswerLenIndexIterationAndMembership() throws Exception {
        String out = run("""
                from java.util import ArrayList, HashMap

                items = ArrayList()
                items.add("a")
                items.add("b")
                items.add("c")
                print(len(items), items[0], items[-1])
                print("a" in items, "missing" in items)
                items[0] = "z"
                print(items, list(items))
                for item in items:
                    print(item, end=";")
                print()

                sizes = HashMap()
                sizes["a"] = 1
                sizes["b"] = 2
                print(len(sizes), sizes["a"], sorted(sizes.keySet()))
                print("a" in sizes, sizes.get("b"))
                """);

        // Membership is asked before the write that replaces "a", because `items[0] = "z"` removes it:
        // the Java list is the same object, so a Python assignment is a Java `set` and changes what a
        // later `in` answers. The order here is the order the oracle checks it in.
        assertThat(out).isEqualTo("""
                3 a c
                True False
                [z, b, c] ['z', 'b', 'c']
                z;b;c;
                2 1 ['a', 'b']
                True 2
                """);
    }

    @Test
    void aNestedClassResolvesThroughItsOuterName() throws Exception {
        String out = run("""
                from org.python.interop.fixtures import Widget

                built = Widget.Builder().size(4).build()
                print(built)
                print(Widget.Builder)
                """);

        assertThat(out).isEqualTo("""
                Widget[sized,4]
                <class 'org.python.interop.fixtures.Widget$Builder'>
                """);
    }

    @Test
    void typeNamesAndIsinstanceFollowTheJavaHierarchy() throws Exception {
        String out = run("""
                from org.python.interop.fixtures import Widget
                from java.util import ArrayList
                from java.lang import Object, CharSequence

                widget = Widget()
                print(type(widget).__name__, type(widget).__module__)
                print(isinstance(widget, Widget), isinstance(widget, Object))
                items = ArrayList()
                print(isinstance(items, Object), isinstance(items, Widget))
                print(isinstance("text", CharSequence))
                """);

        assertThat(out).isEqualTo("""
                Widget org.python.interop.fixtures
                True True
                True False
                False
                """);
    }

    @Test
    void aMissingMemberIsAnAttributeError() throws Exception {
        Result missing = run1("""
                from org.python.interop.fixtures import Widget
                Widget().noSuchThing
                """);
        assertThat(missing.exit()).isEqualTo(1);
        assertThat(lastLine(missing.err()))
                .isEqualTo("AttributeError: 'Widget' object has no attribute 'noSuchThing'");

        Result unimportableClass = run1("""
                from java.util import NoSuch
                """);
        assertThat(unimportableClass.exit()).isEqualTo(1);
        assertThat(lastLine(unimportableClass.err()))
                .isEqualTo("ImportError: cannot import name 'NoSuch' from 'java.util'");

        Result unimportablePackage = run1("""
                import nosuchpackage
                """);
        assertThat(unimportablePackage.exit()).isEqualTo(1);
        assertThat(lastLine(unimportablePackage.err()))
                .isEqualTo("ModuleNotFoundError: No module named 'nosuchpackage'");
    }

    @Test
    void noMatchingOverloadNamesTheCandidates() throws Exception {
        Result arity = run1("""
                from org.python.interop.fixtures import Widget
                Widget.describe(1, 2, 3)
                """);
        assertThat(arity.exit()).isEqualTo(1);
        assertThat(lastLine(arity.err()))
                .isEqualTo(
                        "TypeError: no matching overloads of describe() available,"
                                + " found int, int, int;"
                                + " expected describe(int), describe(str), describe(int, int)");

        Result conversion = run1("""
                from org.python.interop.fixtures import Widget
                Widget.describe([])
                """);
        assertThat(conversion.exit()).isEqualTo(1);
        assertThat(lastLine(conversion.err()))
                .isEqualTo(
                        "TypeError: no matching overloads of describe() available, found list;"
                                + " expected describe(int), describe(str), describe(int, int)");
    }

    /** A Java exception is a Python exception whose name is the Java class's own. */
    @Test
    void aJavaExceptionCrossesTheBoundaryWithItsNameAndMessage() throws Exception {
        Result result = run1("""
                from org.python.interop.fixtures import Widget
                Widget().boom()
                """);

        assertThat(result.exit()).isEqualTo(1);
        assertThat(lastLine(result.err()))
                .isEqualTo("java.lang.IllegalStateException: widget is broken");
    }

    // --- running -------------------------------------------------------------------------

    /** Run a script that must succeed, and give back exactly what it printed. */
    private String run(String source) throws Exception {
        Result result = run1(source);
        assertThat(result.err()).isEmpty();
        assertThat(result.exit()).as("exit code, stderr was: " + result.err()).isEqualTo(0);
        return result.out();
    }

    private Result run1(String source) throws Exception {
        return jy3(work, write(work, "m2.py", source).toString());
    }
}
