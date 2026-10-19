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

    /**
     * The Java wildcard, which only one Python spelling can express.
     *
     * <p>{@code import java.util.*} is a SyntaxError to CPython 3.14's grammar — Python has never had a
     * wildcard in an {@code import} statement — so Jython 2.7's most familiar line reaches us as
     * {@code from java.util import *}. That is the mechanism Python already has, with the list of names a
     * package publishes as its {@code __all__}: every class in the package and every package beneath it.
     * No nested class is in that list, because {@code Widget$Builder} is not a Python name; it stays
     * reachable as {@code Widget.Builder}, which is how Java reaches it too.
     */
    @Test
    void fromAJavaPackageStarBindsItsClassesAndSubpackages() throws Exception {
        String out = run("""
                from java.util import *

                names = ArrayList()
                names.add("first")
                print(names.size(), names.get(0))
                print(ArrayList)

                sizes = HashMap()
                sizes["a"] = 1
                print(len(sizes), sizes["a"])
                print(concurrent.atomic.AtomicInteger(3).incrementAndGet())

                from org.python.interop.fixtures import *
                print(Widget("iron", 3).area(), Widget.Builder)
                """);

        assertThat(out).isEqualTo("""
                1 first
                <class 'java.util.ArrayList'>
                1 1
                4
                9 <class 'org.python.interop.fixtures.Widget$Builder'>
                """);

        String published = run("""
                import java.util
                import org.python.interop.fixtures as fx

                print(fx.__all__)
                print("ArrayList" in java.util.__all__, "concurrent" in java.util.__all__)
                print("$" in "".join(java.util.__all__))
                """);

        // `__all__` is a sorted list of the names themselves, so a star import loads exactly these.
        assertThat(published).isEqualTo("""
                ['Panel', 'Widget']
                True True
                False
                """);

        Result nested = run1("""
                from org.python.interop.fixtures import *
                Builder
                """);
        assertThat(nested.exit()).isEqualTo(1);
        assertThat(lastLine(nested.err()))
                .isEqualTo("NameError: name 'Builder' is not defined");
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

    /**
     * Where the module system's boundary actually falls.
     *
     * <p>A package a module does not export is still importable here — {@code Module.getPackages()} names
     * it, so Python can read its {@code __all__} and hold its classes — and the refusal comes at the call,
     * as {@link java.lang.IllegalAccessException} from the JVM's own reflection check. That is the same
     * order Java enforces it in, and it keeps {@code --add-exports} the only switch a caller needs.
     */
    @Test
    void aNonExportedPackageIsRefusedAtTheCallWithJavasmOwnName() throws Exception {
        String out = run("""
                from jdk.internal.misc import Unsafe
                print(Unsafe, type(Unsafe).__name__)
                """);
        assertThat(out).isEqualTo("<class 'jdk.internal.misc.Unsafe'> type\n");

        Result refused = run1("""
                from jdk.internal.misc import Unsafe
                Unsafe.getUnsafe()
                """);
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(lastLine(refused.err()))
                .startsWith("java.lang.IllegalAccessException:")
                .contains("does not export jdk.internal.misc");
    }

    /**
     * The methods of a class Java never shows you.
     *
     * <p>{@code List.of(...)} answers with an {@code ImmutableCollections.ListN} and {@code stream()} with
     * a {@code ReferencePipeline}: both package-private, so their {@code get(int)} and {@code map} are
     * public members of a class no outside caller may name. Reflection checks the class a method was
     * declared in, not the one it is called on, so binding what the runtime class declares turns a call
     * every Java program makes into an {@code IllegalAccessException}. Java's own answer is to dispatch
     * through {@code List} or {@code Stream}, which is what binding the same signature on the first public
     * supertype does.
     */
    @Test
    void aPackagePrivateImplementationAnswersItsPublicMethods() throws Exception {
        String out = run("""
                from java.util import ArrayList, List, Optional

                rows = List.of("a", "b", "c")
                print(len(rows), rows.get(1), rows.indexOf("c"), rows.contains("b"))

                items = ArrayList()
                for name in ("c", "a", "b"):
                    items.add(name)
                print(items.stream().sorted().count())
                print(items.stream().map(lambda one: one + "!").toList())
                print(Optional.of(3).map(lambda n: n * 2).orElse(-1))
                """);

        assertThat(out).isEqualTo("""
                3 b 2 True
                3
                [c!, a!, b!]
                6
                """);
    }

    /**
     * MVP② taken literally: Java's own reflection API, used from Python.
     *
     * <p>A Java class is also an instance of {@code java.lang.Class}, and {@code getName()},
     * {@code isInterface()} and {@code getDeclaredFields()} are the calls an introspecting reader came
     * for. They answer only after the class's own nested classes, methods and constants have had their
     * turn, so a class that declares {@code getName()} itself keeps its version.
     */
    @Test
    void aJavaClassAnswersJavasmOwnReflectionMethods() throws Exception {
        String out = run("""
                from java.lang import Integer
                from java.util import ArrayList, List
                from org.python.interop.fixtures import Widget

                print(ArrayList().getClass().getName())
                print(Integer.TYPE.isPrimitive(), List.isInterface(), Integer.isInstance(3))
                print(Integer.forName("java.util.List") is List)
                print("name" in [field.getName() for field in Widget.getDeclaredFields()])
                """);

        assertThat(out).isEqualTo("""
                java.util.ArrayList
                True True True
                True
                True
                """);
    }

    /**
     * The acceptance spelling, in a package {@code java.base} does not even contain.
     *
     * <p>{@code java.awt} is in the {@code java.desktop} module, so this is the first time the package
     * index has to read a module that is not the one the runtime starts in — and the first time a class
     * named from Python has both static and instance methods under one name. Java resolves
     * {@code Point2D.distance} by the argument count it is given; a class attribute that answered only
     * the unbound form would make {@code Point2D.distance(0, 0, 3, 4)}, which every Java caller writes,
     * a complaint about a missing instance.
     */
    @Test
    void aPointFromJavaAwtAnswersTheWayJavaAnswers() throws Exception {
        String out = run("""
                from java.awt import Color, Dimension, Point
                from java.awt.geom import Point2D

                point = Point(1, 2)
                print(point.x, point.y, point)
                point.move(4, 5)
                print(point, point.distance(1, 1), point.getX())

                origin = Point()
                print(origin.x, origin.y, origin.equals(point), origin.getLocation())

                corner = Point2D.Double(1.5, 2.5)
                print(Point2D.distance(0, 0, 3, 4))
                print(Point2D.distance(origin, 3, 4))
                print(Point2D.distance(origin, corner))
                print(Point2D.distanceSq(0, 0, 3, 4), corner.distance(origin))

                print(type(corner).__name__, corner.getX(), corner.getY(), corner.distance(0, 0))
                print(Dimension(3, 4).getWidth(), Color.RED.getRGB(), isinstance(corner, Point2D))
                """);

        // Every number here is java.awt's own answer, checked against the same calls in JDK 21.
        assertThat(out).isEqualTo("""
                1 2 java.awt.Point[x=1,y=2]
                java.awt.Point[x=4,y=5] 5.0 4.0
                0 0 False java.awt.Point[x=0,y=0]
                5.0
                5.0
                2.9154759474226504
                25.0 2.9154759474226504
                Double 1.5 2.5 2.9154759474226504
                3.0 -65536 True
                """);
    }

    /**
     * A Java array, which is a sequence to Python and an array to Java.
     *
     * <p>There is no array literal in Python and no array type in Java's own names, so the way in is
     * {@code java.lang.reflect.Array} — and that only works once a class object passed as an argument
     * becomes the {@code Class} Java asked for rather than a Python value Java has no name for. What
     * comes back is the array itself: {@code len()}, {@code a[i]}, assignment, {@code for} and {@code in}
     * are the same {@code Array.getLength}/{@code Array.get}/{@code Array.set} a Java caller would use,
     * and no copy is made.
     *
     * <p>Two of the lines are about what Java's own answer looks like rather than what a Python reader
     * expects: {@code toString()} on a Java array is the identity {@code [I@6bdf28bb} and there is no
     * element view of it in Java either, so an array is printed by its class name and read by
     * {@code list()} or indexing; and a single trailing argument that can already be the array a varargs
     * parameter wants — a Java array, or a Python list, which the conversion table reads as an array —
     * <em>is</em> that array, which is JLS 15.12.2.4's rule and the one {@code Arrays.asList} depends on.
     * A caller who wants a list to stay one element has Java's own way to say it: {@code List.of} declares
     * a one-parameter overload, an earlier phase beats variable arity, and so {@code List.of([[1, 2]])}
     * is a list of one.
     */
    @Test
    void aJavaArrayIsASequenceFromPythonAndAnArrayFromJava() throws Exception {
        String out = run("""
                from java.lang import Integer, String
                from java.lang.reflect import Array
                from java.util import Arrays, List

                row = Array.newInstance(Integer.TYPE, 3)
                row[0] = 7
                Array.set(row, 1, 8)
                print(len(row), row[0], row[1], row[-1], Array.getLength(row))
                print(list(row), 8 in row, row[2])
                for one in row:
                    print(one, end="/")
                print()

                names = Array.newInstance(String, 2)
                names[0] = "a"
                names[1] = "b"
                print(names[0] + names[1], Arrays.asList(names), len(Arrays.asList(names)))
                print(Array.getInt(row, 1), Array.get(names, 1))
                print(row.getClass().getName(), names.getClass().getName(), Array.get(row, 2))

                print(Arrays.asList(*["a", "b"]), Arrays.asList(["a", "b"]))
                print(List.of(names[0], names[1]), List.of(*names))
                print(len(List.of([[1, 2]])), List.of([[1, 2]]))
                print(sum([Array.getInt(row, i) for i in range(3)]))
                """);

        assertThat(out).isEqualTo("""
                3 7 8 0 3
                [7, 8, 0] True 0
                7/8/0/
                ab [a, b] 2
                8 b
                [I [Ljava.lang.String; 0
                [a, b] [a, b]
                [a, b] [a, b]
                1 [[[1, 2]]]
                15
                """);
    }

    /**
     * Any Python callable, not only a function written with {@code def} or {@code lambda}.
     *
     * <p>Java wants a {@code Function}, and the value Python hands it is very often not a function object:
     * {@code map(int)} is a type, {@code map(str.upper)} is a method reached through its type rather than
     * through one string, and {@code map(len)} is a builtin. {@code callable()} in the runtime already
     * knows that list, so the interop layer is not allowed to keep a narrower one — the caller who sees
     * {@code callable(x) is True} and then a {@code TypeError} has been told a lie.
     */
    @Test
    void anyPythonCallableSatisfiesAJavaFunctionInterface() throws Exception {
        String out = run("""
                from java.util import ArrayList, Optional

                print(callable(int), callable(str.lower), callable(ArrayList))
                print(Optional.of("42").map(int).orElse(-1) + 1)
                print(Optional.of(7).map(lambda n: n * 2).orElse(-1))
                print(Optional.of("abc").map(str.upper).orElse(""))
                print(Optional.of(3).map(str).orElse(-1))
                print(Optional.of(["a", "bb"]).map(len).orElse(-1))
                """);

        assertThat(out).isEqualTo("""
                True True True
                43
                14
                ABC
                3
                2
                """);
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
