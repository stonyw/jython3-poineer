package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * The two rows {@code code_replace} files twice, measured against CPython 3.14.10.
 *
 * <p>Corpus case 125 walks the eighteen names a copy takes and prints what the reference prints for each of
 * them: the converters in the order the clinic table declares them, the seven counts' refusal of a negative,
 * the eleven sentences naming the parameter that was handed the wrong kind of value, the arity refusals that
 * come before any converter has looked, and what the builder makes of the eighteen once they are in hand.
 * Every one of those answers is an agreement, so what is pinned here is the rest: the three answers this
 * runtime gives to a line table no compiler wrote, the {@code SystemError} whose sentence the reference opens
 * with the C file it came from, the one row the book still does not have, and the equality that used to ask
 * an interpreter's question in the middle of a comparison.
 *
 * <p>Two differences are not reachable from a script in either direction. {@code co_filename}, {@code
 * co_name} and {@code co_qualname} are {@code unicode} converters that keep the object they are handed, while
 * the {@link PyCode} interface answers the three names as Java strings, so a {@code str} subclass would be
 * flattened here and kept there; {@code co_names} with one in it is the C's {@code SystemError} for a name
 * that is not exactly a string, and the same wall keeps that out of reach. Neither can be built here, because
 * {@code class X(str)} is not implemented. The audit hook the C rings before it builds is the third gap, and
 * this runtime has no audit machinery to ring.
 *
 * <p>The tests live in this module for the reason {@link CodeStorageHalfTest} gives: a copy is only worth
 * reading once something compiled the body it copies, and decision D2 keeps the compiler out of {@code
 * org.python.core}.
 */
class CodeReplaceTest {

    @TempDir
    Path work;

    /** The eighteen names, in the order the clinic table converts them. */
    private static final List<String> FIELDS =
            List.of(
                    "co_argcount", "co_posonlyargcount", "co_kwonlyargcount", "co_nlocals",
                    "co_stacksize", "co_flags", "co_firstlineno", "co_code", "co_consts", "co_names",
                    "co_varnames", "co_freevars", "co_cellvars", "co_filename", "co_name", "co_qualname",
                    "co_linetable", "co_exceptiontable");

    /** The seven of the eighteen the C reads into an {@code int}, and so refuses a negative for. */
    private static final List<String> COUNTS = FIELDS.subList(0, 7);

    /** The two rows, each with the sentence {@code code_methods} hands it. */
    private static final List<String> ROWS = List.of("replace", "__replace__");

    private static final Map<String, String> DOCS =
            Map.of(
                    "replace",
                    "Return a copy of the code object with new values for the specified fields.",
                    "__replace__",
                    "The same as replace().");

    private static final PyObject THREE = PyInt.of(3);

    private static final String PROGRAM =
            """
            def fn(a, b=1, *args, c, **kw):
                return a

            def caught(a):
                try:
                    return a + 1
                except ValueError:
                    return 0

            def plain(a, b):
                return a + b
            """;

    private PyModule module;

    /** {@code code.__dict__[name]}: the row the book files, or null where it files no such name. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.CODE).get(PyStr.interned(name));
    }

    private static List<String> filed() {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(PyType.CODE, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** The row read off a value, which is the row in the book wearing that value. */
    private static PyObject rowOf(PyObject code, String name) {
        return Slots.getAttr(code, name);
    }

    /** {@code code.replace(name = value)}, which is a keyword argument and nothing beside it. */
    private static PyObject changed(PyObject code, String name, PyObject value) {
        return Slots.call(
                rowOf(code, "replace"), new PyObject[] {value}, PyTuple.of(PyStr.of(name)));
    }

    /** {@code code.replace()}, with every one of the eighteen left to its own default. */
    private static PyObject copied(PyObject code) {
        return Slots.call(rowOf(code, "replace"), new PyObject[0], null);
    }

    private static PyBytes octets(int... values) {
        byte[] table = new byte[values.length];
        for (int at = 0; at < values.length; at++) {
            table[at] = (byte) values[at];
        }
        return PyBytes.wrap(table);
    }

    private static List<PyObject> items(PyTuple tuple) {
        List<PyObject> all = new ArrayList<>();
        for (int at = 0; at < tuple.size(); at++) {
            all.add(tuple.at(at));
        }
        return all;
    }

    /** Pin the sentence the reference prints for a refusal, in the words it prints it in. */
    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    /**
     * That two bodies are one body. A code object has no Java {@code equals}, so this is the only question
     * the reference's own {@code tp_richcompare} answers between two of them.
     */
    private static void alike(PyObject left, PyObject right) {
        assertThat(Slots.compareTrue(CompareOp.EQ, left, right)).isTrue();
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** {@code fn.__code__} for the named expression of the program, the way a value reaches it. */
    private PyObject codeOf(String expression) throws IOException {
        if (module == null) {
            Cpython314Compiler.Compiled compiled =
                    Cpython314Compiler.compileFile(
                            Files.writeString(work.resolve("shapes.py"), PROGRAM));
            module = new PyModule("workshop");
            PyRuntime.exec(compiled.code(), module, compiled.fileName());
        }
        PyObject function =
                PyRuntime.eval(
                        Cpython314Compiler.compileExpression(expression, module.name()), module);
        return Slots.getAttr(function, "__code__");
    }

    /** The two rows are one C function, so everything either of them answers, the other answers alike. */
    @Test
    void theTwoRowsAreOneFunctionFiledTwice() throws IOException {
        PyObject code = codeOf("fn");

        for (String name : ROWS) {
            PyObject entry = entry(name);
            assertThat(entry.getType().name()).as(name).isEqualTo("method_descriptor");
            assertThat(entry.repr()).isEqualTo("<method '" + name + "' of 'code' objects>");
            assertThat(Slots.getAttr(entry, "__objclass__")).isSameAs(PyType.CODE);
            assertThat(Slots.getAttr(entry, "__qualname__")).isEqualTo(PyStr.of("code." + name));
            assertThat(Slots.getAttr(entry, "__doc__")).isEqualTo(PyStr.of(DOCS.get(name)));
            assertThat(Slots.getAttr(entry, "__text_signature__"))
                    .isEqualTo(PyStr.of("($self, /, **changes)"));
            assertThat(Descriptors.writes(entry)).as(name).isFalse();
            // The row read off the class is the row in the book; only a value gets the method wearing it.
            assertThat(Slots.getAttr(PyType.CODE, name)).isSameAs(entry);
            PyObject bound = rowOf(code, name);
            assertThat(bound.getType().name()).as(name).isEqualTo("builtin_function_or_method");
            assertThat(Slots.getAttr(bound, "__self__")).isSameAs(code);
            // The unbound row takes its receiver as its first argument, and reaches the same copy.
            PyTuple named = PyTuple.of(PyStr.of("co_name"));
            alike(
                    Slots.call(entry, new PyObject[] {code, PyStr.of("z")}, named),
                    changed(code, "co_name", PyStr.of("z")));
            // An empty call to either row reaches the copy the public row answers, because it is one function.
            alike(Slots.call(bound, new PyObject[0], null), copied(code));
            refuses(
                    "TypeError: unbound method code." + name + "() needs an argument",
                    () -> Slots.call(entry, new PyObject[] {PyStr.of("x")}, named));
            refuses(
                    "TypeError: descriptor '" + name
                            + "' for 'code' objects doesn't apply to a 'int' object",
                    () -> Slots.call(entry, new PyObject[] {THREE, PyStr.of("x")}, named));
            refuses(
                    "AttributeError: 'method_descriptor' object has no attribute '__set__'",
                    () -> Slots.getAttr(entry, "__set__"));
            refuses(
                    "TypeError: cannot set '" + name + "' attribute of immutable type 'code'",
                    () -> Slots.setAttr(PyType.CODE, name, THREE));
            refuses(
                    "AttributeError: 'code' object attribute '" + name + "' is read-only",
                    () -> Slots.setAttr(code, name, THREE));
        }
    }

    /** The eighteen defaults are read as attributes, so a copy left alone keeps what the source answers. */
    @Test
    void aCopyLeftToItsDefaultsIsANewObjectCarryingEveryOneOfTheEighteen() throws IOException {
        PyObject code = codeOf("fn");
        PyObject copy = copied(code);

        assertThat(copy).isNotSameAs(code);
        assertThat(Slots.compareTrue(CompareOp.EQ, copy, code)).isTrue();
        for (String field : FIELDS) {
            assertThat(Slots.compareTrue(
                            CompareOp.EQ, Slots.getAttr(copy, field), Slots.getAttr(code, field)))
                    .as(field)
                    .isTrue();
        }
        // A value handed over is the value read back, rather than a copy of it made on the way in.
        PyObject constants = Slots.getAttr(code, "co_consts");
        assertThat(Slots.getAttr(changed(code, "co_consts", constants), "co_consts")).isSameAs(constants);
        alike(copied(copy), copy);
    }

    /** Every one of the seven counts is converted before any of them is checked, in the order declared. */
    @Test
    void everyConverterRunsBeforeAnyCountIsChecked() throws IOException {
        PyObject code = codeOf("plain");

        for (String count : COUNTS) {
            refuses(
                    "ValueError: " + count + " must be a positive integer",
                    () -> changed(code, count, PyInt.of(-1)));
            refuses(
                    "TypeError: 'str' object cannot be interpreted as an integer",
                    () -> changed(code, count, PyStr.of("x")));
            refuses(
                    "TypeError: 'float' object cannot be interpreted as an integer",
                    () -> changed(code, count, PyFloat.of(1.0)));
            refuses(
                    "OverflowError: Python int too large to convert to C int",
                    () -> changed(code, count, PyInt.of(new java.math.BigInteger("70368744177664"))));
        }
        // A bool is an int, so it reaches the builder; the builder is the one that rounds nought up.
        assertThat(((PyCode) changed(code, "co_argcount", PyBool.TRUE)).argCount()).isEqualTo(1);
        assertThat(((PyCode) changed(code, "co_stacksize", PyInt.of(0))).stackSize()).isEqualTo(1);
    }

    /**
     * The one refusal whose sentence this runtime cannot copy: the reference opens it with the C file and
     * line the assertion named in {@code code_replace} came from.
     */
    @Test
    void thePositionOnlyCountWiderThanTheArgumentsIsNamedForItsFileByTheReference() throws IOException {
        PyObject code = codeOf("fn");
        PyObject wide = changed(code, "co_argcount", PyInt.of(1));

        // The reference answers `SystemError: Objects/codeobject.c:465: bad argument to internal function`.
        refuses(
                "SystemError: bad argument to internal function",
                () -> changed(wide, "co_posonlyargcount", THREE));
        assertThatThrownBy(() -> changed(wide, "co_posonlyargcount", THREE))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage()).doesNotContain("codeobject.c"));
    }

    /**
     * A line table no compiler wrote is the one thing the two readers do not answer alike. The reference
     * keeps whatever octets it is handed and decodes them per read, tolerating anything; this reader decodes
     * once, eagerly, and its complaint is what the call hears.
     */
    @Test
    void aHandWrittenLineTableIsHeardAsTheReaderRefusesIt() throws IOException {
        PyObject code = codeOf("plain");

        refuses(
                "ValueError: bad marshal data (line table byte 0 continues an entry that has already ended)",
                () -> changed(code, "co_linetable", octets(0, 0)));
        refuses(
                "ValueError: bad marshal data (line table ends inside an entry)",
                () -> changed(code, "co_linetable", octets(0x80)));
        refuses(
                "ValueError: bad marshal data (line table ends inside a varint)",
                () -> changed(code, "co_linetable", octets(0xf0)));
        // The tables that mean something to both readers answer what the reference answers from them.
        PyObject emptied = changed(code, "co_linetable", octets());
        assertThat(Slots.getAttr(emptied, "co_linetable")).isEqualTo(octets());
        assertThat(items(((PyCode) emptied).lineRanges())).isEmpty();
        assertThat(items(((PyCode) emptied).positions())).isEmpty();
        // Code 15 names units with no location at all, and both readings follow the table rather than the
        // body: sixteen units are declared here, whatever the instruction stream underneath happens to hold.
        PyObject nowhere = changed(code, "co_linetable", octets(0xff, 0xff));
        PyTuple none = PyTuple.of(PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE);
        assertThat(items(((PyCode) nowhere).positions())).hasSize(16).containsOnly(none);
        assertThat(items(((PyCode) nowhere).lineRanges()))
                .containsExactly(PyTuple.of(PyInt.of(0), PyInt.of(32), PyNone.INSTANCE));
    }

    /**
     * {@code code_richcompare} weighs the exception octets, and nothing in that walk decodes them.
     *
     * <p>An earlier walk asked what the two tables meant to an interpreter — one handler per instruction —
     * and so heard the front-end's complaint about a row naming a unit this body has no instruction at, in
     * the middle of an equality. The reference never asks, and neither does this: the copy below is unequal
     * because its octets are, and every other read of it answers.
     */
    @Test
    void equalityWeighsTheExceptionOctetsAndNeverWhatTheyMean() throws IOException {
        PyObject plain = codeOf("plain");
        PyObject handled = codeOf("caught");
        PyObject foreign = Slots.getAttr(handled, "co_exceptiontable");
        assertThat(Slots.compareTrue(
                CompareOp.NE, foreign, Slots.getAttr(plain, "co_exceptiontable")))
                .as("the two bodies need different tables")
                .isTrue();

        PyObject swapped = changed(plain, "co_exceptiontable", foreign);

        assertThat(Slots.getAttr(swapped, "co_exceptiontable")).isEqualTo(foreign);
        assertThat(CodeMembers.compare(CompareOp.EQ, swapped, plain)).isSameAs(PyBool.FALSE);
        assertThat(CodeMembers.compare(CompareOp.NE, swapped, plain)).isSameAs(PyBool.TRUE);
        assertThatCode(() -> Slots.compare(CompareOp.EQ, swapped, plain)).doesNotThrowAnyException();
        assertThat(items(((PyCode) swapped).positions()))
                .hasSameSizeAs(items(((PyCode) plain).positions()));
        alike(changed(swapped, "co_exceptiontable", octets()), plain);
    }

    /** The book has the two rows now, and still lacks the constructor beside the branch report it filed. */
    @Test
    void theBookFilesThirtySixRowsWhereTheReferenceFilesThirtySeven() throws IOException {
        PyObject code = codeOf("fn");

        assertThat(filed()).hasSize(36);
        assertThat(entry("replace")).isNotNull();
        assertThat(entry("__replace__")).isNotNull();
        assertThat(entry("co_branches")).isNotNull();
        assertThat(entry("__new__")).isNull();
        // The names a script sees are already the reference's fifty: __new__ comes in from object.
        List<String> published = new ArrayList<>();
        for (PyObject name : Dirs.itemsOf(code)) {
            published.add(((PyStr) name).getValue());
        }
        assertThat(published).hasSize(50);
        assertThat(published).contains("replace", "__replace__", "co_branches", "__new__");
    }
}
