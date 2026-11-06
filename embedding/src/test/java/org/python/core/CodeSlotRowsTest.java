package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * The ten rows {@code code} copies out of its own slots, measured against CPython 3.14.10.
 *
 * <p>Corpus case 124 reads each of them from Python and prints the answer the reference answers with. Pinned
 * here is what a script cannot reach: that the six comparison names are one slot and so one Java method,
 * that equality walks the decoded tables rather than the object, and that {@code __sizeof__} counts the
 * units a front-end read rather than the instructions a driver runs. The three cursor rows of the same
 * table are {@code 123_code_storage_half.py}'s and {@code 126_code_branches.py}'s, and the row still
 * unfiled — the constructor — is the last of the type's own.
 *
 * <p>Two of those answers are measured rather than copied. {@code code_richcompare} compares the raw {@code
 * co_linetable} and {@code co_exceptiontable} bytes and keys constants with {@code _PyCode_ConstantKey};
 * this runtime has the exception octets, which it compares as the C does, but no line octets and so compares
 * the decoded runs instead, and asks {@code ==} of the constants themselves. Corpus case 125 can now hand
 * either table to a copy and asks an equality about what it gets back; every answer it prints is one the two
 * routes give alike, and {@link CodeReplaceTest} files the cases they do not.
 *
 * <p>The tests live in this module for the reason {@link CodeStorageHalfTest} gives: a code object is only
 * worth reading once something compiled it, and decision D2 keeps the compiler out of {@code org.python.
 * core}.
 */
class CodeSlotRowsTest {

    @TempDir
    Path work;

    /** The eight rows {@code add_operators} copies out of {@code tp_repr}, {@code tp_hash} and the compare. */
    private static final List<String> WRAPPERS =
            List.of("__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__");

    /** The two rows {@code code_methods} files with no sentence beyond their own. */
    private static final List<String> METHODS = List.of("__sizeof__", "_varname_from_oparg");

    /** The six names that are one slot, in the order the book files them. */
    private static final List<String> COMPARISONS =
            List.of("__lt__", "__le__", "__eq__", "__ne__", "__gt__", "__ge__");

    /** The row CPython files and this runtime still refuses as missing. */
    private static final List<String> NOT_FILED = List.of("__new__");

    private static final PyObject THREE = PyInt.of(3);

    /** The two functions whose unit counts the reference measures, and a closure over two locals. */
    private static final String PROGRAM =
            """
            def fn(a, b=1, *args, c, **kw):
                return a

            def wide(a, b, c, d, e):
                total = 0
                for each in range(10):
                    total += each
                while total > 1:
                    total -= 1
                return total

            def outer(x):
                y = x + 1

                def inner():
                    return x + y

                return inner
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

    private static PyObject called(PyObject callable, PyObject... args) {
        return Slots.call(callable, args, null);
    }

    /** Pin the sentence the reference prints for a refusal, in the words it prints it in. */
    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** The first body underneath a tuple of constants, which is where a compiled function's code sits. */
    private static PyObject firstCode(PyObject constants) {
        PyTuple items = (PyTuple) constants;
        for (int i = 0; i < items.size(); i++) {
            if (items.at(i) instanceof PyCode) {
                return items.at(i);
            }
        }
        throw new AssertionError("no code object among the constants");
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

    /** The module code of the program, compiled twice from one file so the two bodies agree everywhere. */
    private PyCode[] sameTableTwice() throws IOException {
        Path script = Files.writeString(work.resolve("twice.py"), PROGRAM);
        return new PyCode[] {
            Cpython314Compiler.compileFile(script).code(), Cpython314Compiler.compileFile(script).code()
        };
    }

    @Test
    void theTenSlotsComeFirstAndTheTwoRowsLeftAreStillMissing() {
        List<String> book = filed();

        assertThat(book.subList(0, 8)).containsExactlyElementsOf(WRAPPERS);
        assertThat(book.subList(8, 15))
                .containsExactly(
                        "__sizeof__", "co_lines", "co_branches", "co_positions", "replace",
                        "_varname_from_oparg", "__replace__");
        assertThat(book).hasSize(36);
        for (String row : NOT_FILED) {
            assertThat(entry(row)).as(row).isNull();
        }
    }

    @Test
    void eachWrapperRowIsTheSlotUnderneathAndNamesItsOwnType() throws IOException {
        PyObject code = codeOf("fn");

        for (String name : WRAPPERS) {
            PyObject row = entry(name);
            assertThat(row.getType().name()).as(name).isEqualTo("wrapper_descriptor");
            assertThat(row.repr()).isEqualTo("<slot wrapper '" + name + "' of 'code' objects>");
            assertThat(Slots.getAttr(row, "__objclass__")).isSameAs(PyType.CODE);
            assertThat(Slots.getAttr(row, "__name__")).isEqualTo(PyStr.of(name));
            assertThat(Slots.getAttr(row, "__qualname__")).isEqualTo(PyStr.of("code." + name));
            assertThat(Descriptors.writes(row)).as(name).isFalse();
            // The row read off the class is the row in the book; only a value gets the slot wearing it.
            assertThat(Slots.getAttr(PyType.CODE, name)).isSameAs(row);
            assertThat(rowOf(code, name).getType().name()).isEqualTo("method-wrapper");
        }
        assertThat(Slots.getAttr(entry("__repr__"), "__doc__")).isEqualTo(PyStr.of("Return repr(self)."));
        assertThat(Slots.getAttr(entry("__eq__"), "__doc__"))
                .isEqualTo(PyStr.of("Return self==value."));
        assertThat(Slots.getAttr(entry("__lt__"), "__doc__"))
                .isEqualTo(PyStr.of("Return self<value."));
        assertThat(Slots.getAttr(entry("__ge__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, value, /)"));
        assertThat(Slots.getAttr(entry("__hash__"), "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));
    }

    @Test
    void theTwoMethodRowsAreTheKindThatCarriesNoReceiverAndOneIsSilent() throws IOException {
        PyObject code = codeOf("fn");

        for (String name : METHODS) {
            PyObject row = entry(name);
            assertThat(row.getType().name()).as(name).isEqualTo("method_descriptor");
            assertThat(row.repr()).isEqualTo("<method '" + name + "' of 'code' objects>");
            assertThat(Slots.getAttr(row, "__objclass__")).isSameAs(PyType.CODE);
            assertThat(Descriptors.writes(row)).as(name).isFalse();
            assertThat(rowOf(code, name).getType().name()).isEqualTo("builtin_function_or_method");
        }
        // `code_methods` writes no `ml_doc` beside `__sizeof__`, so the row and the bound method are both silent.
        assertThat(Slots.getAttr(entry("__sizeof__"), "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(rowOf(code, "__sizeof__"), "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(entry("__sizeof__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(entry("_varname_from_oparg"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, /, oparg)"));
        assertThat(Slots.getAttr(entry("_varname_from_oparg"), "__doc__"))
                .isEqualTo(
                        PyStr.of(
                                "(internal-only) Return the local variable name for the given oparg."
                                        + "\n\nWARNING: this method is for internal use only and may change"
                                        + " or go\naway."));
        assertThat(Slots.getAttr(rowOf(code, "__sizeof__"), "__self__")).isSameAs(code);
        assertThat(Slots.getAttr(rowOf(code, "__sizeof__"), "__qualname__"))
                .isEqualTo(PyStr.of("code.__sizeof__"));
        // Every read makes the wrapper afresh, and the two reads answer the same.
        PyObject once = rowOf(code, "__eq__");
        PyObject twice = rowOf(code, "__eq__");
        assertThat(once).isNotSameAs(twice);
        assertThat(Slots.compareTrue(CompareOp.EQ, called(once, code), called(twice, code))).isTrue();
    }

    /** The six names are one C function, so read as rows they answer the two equalities and decline the rest. */
    @Test
    void theSixComparisonNamesAreOneSlotAndAnswerOnlyTwoEqualities() throws IOException {
        PyObject code = codeOf("fn");

        assertThat(Slots.compareTrue(CompareOp.EQ, code, code)).isTrue();
        assertThat(Slots.compareTrue(CompareOp.NE, code, code)).isFalse();
        assertThat(CodeMembers.compare(CompareOp.EQ, code, THREE)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(CodeMembers.compare(CompareOp.NE, code, THREE)).isSameAs(PyNotImplemented.INSTANCE);
        // The four orderings decline between two code objects, which is what names both types in the refusal.
        assertThat(CodeMembers.compare(CompareOp.LT, code, code)).isSameAs(PyNotImplemented.INSTANCE);
        for (String name : List.of("__lt__", "__le__", "__gt__", "__ge__")) {
            assertThat(called(rowOf(code, name), code)).as(name).isSameAs(PyNotImplemented.INSTANCE);
        }
        assertThat(Slots.call(entry("__eq__"), new PyObject[] {code, code}, null)).isSameAs(PyBool.TRUE);
        assertThat(Slots.call(entry("__ne__"), new PyObject[] {code, code}, null)).isSameAs(PyBool.FALSE);
        assertThat(Slots.call(entry("__lt__"), new PyObject[] {code, code}, null))
                .isSameAs(PyNotImplemented.INSTANCE);
        refuses("TypeError: expected 1 argument, got 0", () -> called(entry("__eq__"), code));
        refuses(
                "TypeError: '<' not supported between instances of 'code' and 'code'",
                () -> Slots.compare(CompareOp.LT, code, code));
        refuses(
                "TypeError: '>=' not supported between instances of 'code' and 'code'",
                () -> Slots.compare(CompareOp.GE, code, code));
    }

    /**
     * Equality is about the table, so two objects read from one body agree while remaining two objects, and
     * a body that differs in one count does not.
     */
    @Test
    void equalityReadsTheTableAndNotTheObject() throws IOException {
        PyCode[] twice = sameTableTwice();

        assertThat(twice[0]).isNotSameAs(twice[1]);
        assertThat(CodeMembers.compare(CompareOp.EQ, (PyObject) twice[0], (PyObject) twice[1]))
                .isSameAs(PyBool.TRUE);
        assertThat(CodeMembers.compare(CompareOp.NE, (PyObject) twice[0], (PyObject) twice[1]))
                .isSameAs(PyBool.FALSE);
        // The constants underneath are compared the same way, so a function body two compiles deep agrees too.
        PyObject one = firstCode(twice[0].constants());
        PyObject other = firstCode(twice[1].constants());
        assertThat(one).isNotSameAs(other);
        assertThat(Slots.compareTrue(CompareOp.EQ, one, other)).isTrue();

        PyObject fn = codeOf("fn");
        PyObject wide = codeOf("wide");
        assertThat(Slots.compareTrue(CompareOp.EQ, fn, wide)).isFalse();
        assertThat(Slots.compareTrue(CompareOp.NE, fn, wide)).isTrue();
        // Two reads of one body are one answer: the value keeps the object it was built with.
        assertThat(Slots.compareTrue(CompareOp.EQ, fn, codeOf("fn"))).isTrue();
    }

    @Test
    void sizeOfCountsTheUnitsTheFrontEndReadNotTheInstructionsRun() throws IOException {
        PyObject fn = codeOf("fn");
        PyObject wide = codeOf("wide");

        assertThat(((PyCode) fn).unitCount()).isEqualTo(3);
        assertThat(((PyCode) wide).unitCount()).isEqualTo(49);
        // The two numbers differ wherever an opcode leaves a cache behind it, which is what `wide` does.
        assertThat(((PyCode) wide).instructions().length()).isLessThan(49);
        // 208 octets of header plus two for each unit, rounded up to a word: what `Py_SIZE` measures.
        assertThat(called(rowOf(fn, "__sizeof__"))).isEqualTo(PyInt.of(216));
        assertThat(called(rowOf(wide, "__sizeof__"))).isEqualTo(PyInt.of(312));
        assertThat(called(rowOf(fn, "__sizeof__")).getType()).isSameAs(PyType.INT);
        refuses(
                "TypeError: code.__sizeof__() takes no arguments (1 given)",
                () -> called(rowOf(fn, "__sizeof__"), THREE));
        refuses(
                "TypeError: code.__sizeof__() takes no keyword arguments",
                () -> Slots.call(rowOf(fn, "__sizeof__"), new PyObject[0], PyTuple.of(PyStr.of("x"))));
    }

    /**
     * {@code _varname_from_oparg} indexes the localsplus array, which keeps a function's parameters before
     * its cells and its own locals, so an outer body answers for names {@code co_varnames} does not hold.
     */
    @Test
    void varnameFromOpargWalksTheSlotsRatherThanTheVarnames() throws IOException {
        PyObject outer = codeOf("outer");
        PyObject inner = codeOf("outer(1)");

        assertThat(Slots.getAttr(outer, "co_varnames"))
                .isEqualTo(PyTuple.of(PyStr.of("x"), PyStr.of("inner")));
        assertThat(Slots.getAttr(outer, "co_cellvars"))
                .isEqualTo(PyTuple.of(PyStr.of("x"), PyStr.of("y")));
        assertThat(called(rowOf(outer, "_varname_from_oparg"), PyInt.of(0))).isEqualTo(PyStr.of("x"));
        assertThat(called(rowOf(outer, "_varname_from_oparg"), PyInt.of(1))).isEqualTo(PyStr.of("inner"));
        // The third slot is a cell, which no read of `co_varnames` can name.
        assertThat(called(rowOf(outer, "_varname_from_oparg"), PyInt.of(2))).isEqualTo(PyStr.of("y"));
        refuses(
                "IndexError: tuple index out of range",
                () -> called(rowOf(outer, "_varname_from_oparg"), PyInt.of(3)));
        refuses(
                "IndexError: tuple index out of range",
                () -> called(rowOf(outer, "_varname_from_oparg"), PyInt.of(-1)));

        // A body that only captures answers for the names it captures, and has no locals of its own.
        assertThat(Slots.getAttr(inner, "co_varnames")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(inner, "co_freevars"))
                .isEqualTo(PyTuple.of(PyStr.of("x"), PyStr.of("y")));
        assertThat(called(rowOf(inner, "_varname_from_oparg"), PyInt.of(0))).isEqualTo(PyStr.of("x"));
        assertThat(called(rowOf(inner, "_varname_from_oparg"), PyInt.of(1))).isEqualTo(PyStr.of("y"));
        refuses(
                "IndexError: tuple index out of range",
                () -> called(rowOf(inner, "_varname_from_oparg"), PyInt.of(2)));
    }

    /** The clinic takes a C {@code int}, so a count is asked of the argument before the array is read. */
    @Test
    void theArgumentIsACountAndASlotInTheArray() throws IOException {
        PyObject fn = codeOf("fn");

        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> called(rowOf(fn, "_varname_from_oparg"), PyStr.of("0")));
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> called(rowOf(fn, "_varname_from_oparg"), PyFloat.of(1.0)));
        refuses(
                "OverflowError: Python int too large to convert to C int",
                () ->
                        called(
                                rowOf(fn, "_varname_from_oparg"),
                                PyInt.of(new java.math.BigInteger("123456789012345678901234567890"))));
        // A bool is an int, and an `__index__` is asked for one.
        assertThat(called(rowOf(fn, "_varname_from_oparg"), PyBool.TRUE)).isEqualTo(PyStr.of("b"));
        refuses(
                "TypeError: _varname_from_oparg() missing required argument 'oparg' (pos 1)",
                () -> called(rowOf(fn, "_varname_from_oparg")));
        refuses(
                "TypeError: _varname_from_oparg() takes at most 1 argument (2 given)",
                () -> called(rowOf(fn, "_varname_from_oparg"), PyInt.of(0), PyInt.of(1)));
        // The clinic names the one argument, so a call that names it answers and one that does not is missing.
        assertThat(
                        Slots.call(
                                rowOf(fn, "_varname_from_oparg"),
                                new PyObject[] {PyInt.of(2)},
                                PyTuple.of(PyStr.of("oparg"))))
                .isEqualTo(PyStr.of("c"));
        refuses(
                "TypeError: _varname_from_oparg() missing required argument 'oparg' (pos 1)",
                () ->
                        Slots.call(
                                rowOf(fn, "_varname_from_oparg"),
                                new PyObject[] {PyInt.of(0)},
                                PyTuple.of(PyStr.of("index"))));
    }

    @Test
    void theReceiverIsAskedBeforeAnyOfTheTenLooksAtItsArguments() {
        for (String name : List.of("__eq__", "__repr__", "__sizeof__", "_varname_from_oparg")) {
            refuses(
                    "TypeError: descriptor '"
                            + name
                            + "' for 'code' objects doesn't apply to a 'int' object",
                    () -> Slots.call(Slots.getAttr(entry(name), "__get__"), new PyObject[] {THREE}, null));
        }
        for (String name : WRAPPERS) {
            assertThat(Slots.getAttr(entry(name), "__get__")).isNotNull();
            refuses(
                    "AttributeError: 'wrapper_descriptor' object has no attribute '__set__'",
                    () -> Slots.getAttr(entry(name), "__set__"));
            refuses(
                    "AttributeError: 'wrapper_descriptor' object has no attribute '__delete__'",
                    () -> Slots.getAttr(entry(name), "__delete__"));
        }
        for (String name : METHODS) {
            refuses(
                    "AttributeError: 'method_descriptor' object has no attribute '__set__'",
                    () -> Slots.getAttr(entry(name), "__set__"));
        }
    }

    @Test
    void noneOfTheTenIsWritableFromAValueOrFromTheClass() throws IOException {
        PyObject fn = codeOf("fn");

        for (String name : WRAPPERS) {
            refuses(
                    "AttributeError: 'code' object attribute '" + name + "' is read-only",
                    () -> Slots.setAttr(fn, name, THREE));
            refuses(
                    "AttributeError: 'code' object attribute '" + name + "' is read-only",
                    () -> Slots.delAttr(fn, name));
        }
        for (String name : METHODS) {
            refuses(
                    "AttributeError: 'code' object attribute '" + name + "' is read-only",
                    () -> Slots.setAttr(fn, name, THREE));
        }
        for (String name : List.of("__eq__", "__sizeof__", "_varname_from_oparg")) {
            refuses(
                    "TypeError: cannot set '" + name + "' attribute of immutable type 'code'",
                    () -> Slots.setAttr(PyType.CODE, name, THREE));
            refuses(
                    "TypeError: cannot set '" + name + "' attribute of immutable type 'code'",
                    () -> Slots.delAttr(PyType.CODE, name));
        }
    }
}
