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
 * The twenty-four rows {@code code} files, measured against CPython 3.14.10.
 *
 * <p>Corpus case 123 reads every one of these rows from Python and prints the refusal each of them makes
 * in the words the reference makes it. What is pinned here is the store underneath: the order the rows are
 * filed in, the kind each is filed as, the fact that the row a walk reaches off the class and the row in
 * the book are one object, and which of them outranks a dictionary a code object does not have.
 *
 * <p>The reference files thirty-seven rows in this book, and since {@code 127_code_new.py} this runtime files
 * all thirty-seven: the constructor at the head of it, the fourteen storage names, the six computed tables,
 * the three cursors, the eight rows copied out of the slots and the five method rows beside them. {@code
 * len(vars(code))} therefore counts one more row than it did, while {@code dir(code)} still counts fifty
 * names, because the constructor came in from {@code object} and so joins the name list without joining it
 * for the first time. What is pinned below is the store underneath those rows: the order they are filed in,
 * the kind each is filed as, and which of them outranks a dictionary a code object does not have.
 *
 * <p>Two kinds of refusal survive here that a single sentence would lose: a {@code PyMemberDef} marked
 * read-only answers four syllables and names nothing, and a getset whose {@code gd_set} is {@code NULL}
 * names the attribute and the type. The split is the tables CPython filed them in, so it is the tables in
 * {@link TypeDict} that decide it, and the tests below are the two halves of that decision.
 *
 * <p>The tests live in this module rather than the runtime's own for the reason {@link
 * GeneratorFrameTest} gives: a code object is only worth reading once something compiled it, and decision
 * D2 keeps the compiler out of {@code org.python.core}.
 */
class CodeStorageHalfTest {

    @TempDir
    Path work;

    /** The rows the book files, in the order the reference files its thirty-seven. */
    private static final List<String> BOOK =
            List.of(
                    "__new__",
                    "__repr__",
                    "__hash__",
                    "__lt__",
                    "__le__",
                    "__eq__",
                    "__ne__",
                    "__gt__",
                    "__ge__",
                    "__sizeof__",
                    "co_lines",
                    "co_branches",
                    "co_positions",
                    "replace",
                    "_varname_from_oparg",
                    "__replace__",
                    "co_argcount",
                    "co_posonlyargcount",
                    "co_kwonlyargcount",
                    "co_stacksize",
                    "co_flags",
                    "co_nlocals",
                    "co_consts",
                    "co_names",
                    "co_filename",
                    "co_name",
                    "co_qualname",
                    "co_firstlineno",
                    "co_linetable",
                    "co_exceptiontable",
                    "co_lnotab",
                    "_co_code_adaptive",
                    "co_varnames",
                    "co_cellvars",
                    "co_freevars",
                    "co_code",
                    "__doc__");

    /** The fourteen {@code PyMemberDef}s of {@code code_struct}. */
    private static final List<String> MEMBERS =
            List.of(
                    "co_argcount",
                    "co_posonlyargcount",
                    "co_kwonlyargcount",
                    "co_stacksize",
                    "co_flags",
                    "co_nlocals",
                    "co_consts",
                    "co_names",
                    "co_filename",
                    "co_name",
                    "co_qualname",
                    "co_firstlineno",
                    "co_linetable",
                    "co_exceptiontable");

    /** The six getsets, in the order {@code code_getseters} keeps them rather than in name order. */
    private static final List<String> GETSETS =
            List.of(
                    "co_lnotab",
                    "_co_code_adaptive",
                    "co_varnames",
                    "co_cellvars",
                    "co_freevars",
                    "co_code");

    private static final List<String> METHODS =
            List.of("co_lines", "co_branches", "co_positions");

    /** The twenty-four rows this case is about: the storage half, the three cursors and the sentence. */
    private static final List<String> STORAGE =
            List.of(
                    "co_lines",
                    "co_branches",
                    "co_positions",
                    "co_argcount",
                    "co_posonlyargcount",
                    "co_kwonlyargcount",
                    "co_stacksize",
                    "co_flags",
                    "co_nlocals",
                    "co_consts",
                    "co_names",
                    "co_filename",
                    "co_name",
                    "co_qualname",
                    "co_firstlineno",
                    "co_linetable",
                    "co_exceptiontable",
                    "co_lnotab",
                    "_co_code_adaptive",
                    "co_varnames",
                    "co_cellvars",
                    "co_freevars",
                    "co_code",
                    "__doc__");

    private static final String SENTENCE = "Create a code object.  Not for the faint of heart.";

    private static final String TABLE =
            "(argcount, posonlyargcount, kwonlyargcount, nlocals, stacksize,\n"
                    + "     flags, codestring, constants, names, varnames, filename, name,\n"
                    + "     qualname, firstlineno, linetable, exceptiontable, freevars=(),\n"
                    + "     cellvars=(), /)";

    private static final PyObject ONE = PyInt.of(1);

    /** A function with one parameter of every kind, and a closure over a local its child reads. */
    private static final String PROGRAM =
            """
            def fn(a, b=1, *args, c, **kw):
                return a

            def outer():
                total = 0

                def inner():
                    return total

                return inner
            """;

    /** The five names {@code fn} keeps in its slots, in the order the array holds them. */
    private static final PyObject VARNAMES =
            PyTuple.of(
                    PyStr.of("a"),
                    PyStr.of("b"),
                    PyStr.of("c"),
                    PyStr.of("args"),
                    PyStr.of("kw"));

    /** The namespace the program was run into, made once per test. */
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

    /** {@code fn.__code__} for the named function of the program, the way a value reaches it. */
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

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    @Test
    void theBookFilesThirtySevenRowsInTheReferenceOrder() {
        assertThat(filed()).isEqualTo(BOOK);
        assertThat(filed()).hasSize(37);
        assertThat(entry("__new__")).isNotNull();
        assertThat(PyType.CODE.bases()).containsExactly(PyType.OBJECT);
    }

    @Test
    void eachFieldIsAMemberRowAndEachTableAGetsetRow() {
        for (String member : MEMBERS) {
            assertThat(entry(member).getType().name()).isEqualTo("member_descriptor");
            assertThat(entry(member).repr()).isEqualTo("<member '" + member + "' of 'code' objects>");
            assertThat(Slots.getAttr(entry(member), "__objclass__")).isSameAs(PyType.CODE);
            assertThat(Slots.getAttr(entry(member), "__name__")).isEqualTo(PyStr.of(member));
            assertThat(Slots.getAttr(entry(member), "__qualname__"))
                    .isEqualTo(PyStr.of("code." + member));
            assertThat(Slots.getAttr(entry(member), "__doc__")).isSameAs(PyNone.INSTANCE);
            refuses(
                    "AttributeError: 'member_descriptor' object has no attribute '__text_signature__'",
                    () -> Slots.getAttr(entry(member), "__text_signature__"));
        }
        for (String getset : GETSETS) {
            assertThat(entry(getset).getType().name()).isEqualTo("getset_descriptor");
            assertThat(entry(getset).repr()).isEqualTo("<attribute '" + getset + "' of 'code' objects>");
            assertThat(Slots.getAttr(entry(getset), "__objclass__")).isSameAs(PyType.CODE);
            assertThat(Slots.getAttr(entry(getset), "__qualname__"))
                    .isEqualTo(PyStr.of("code." + getset));
            assertThat(Slots.getAttr(entry(getset), "__doc__")).isSameAs(PyNone.INSTANCE);
            refuses(
                    "AttributeError: 'getset_descriptor' object has no attribute '__text_signature__'",
                    () -> Slots.getAttr(entry(getset), "__text_signature__"));
        }
    }

    @Test
    void theThreeCursorRowsAreMethodsAndEachNamesItsOwnTable() {
        for (String method : METHODS) {
            assertThat(entry(method).getType().name()).isEqualTo("method_descriptor");
            assertThat(entry(method).repr()).isEqualTo("<method '" + method + "' of 'code' objects>");
            assertThat(Slots.getAttr(entry(method), "__objclass__")).isSameAs(PyType.CODE);
            assertThat(Slots.getAttr(entry(method), "__qualname__"))
                    .isEqualTo(PyStr.of("code." + method));
            assertThat(Slots.getAttr(entry(method), "__doc__")).isSameAs(PyNone.INSTANCE);
            assertThat(Slots.getAttr(entry(method), "__text_signature__"))
                    .isEqualTo(PyStr.of("($self, /)"));
        }
    }

    /**
     * The twenty storage rows are data descriptors, so {@link GenericAttributes} runs them before it looks
     * for a dictionary; the three cursor rows and the sentence are not, and only outrank one because nothing
     * below them answers. The ten slot rows of {@code 124_code_slot_rows.py} are not either, and are the
     * next list below.
     */
    @Test
    void theFieldsAndTheTablesOutrankAndTheMethodsDoNot() {
        for (String row : STORAGE) {
            boolean data = MEMBERS.contains(row) || GETSETS.contains(row);
            assertThat(Descriptors.writes(entry(row))).as(row).isEqualTo(data);
        }
        for (String row : List.of("__repr__", "__hash__", "__eq__", "__sizeof__", "_varname_from_oparg")) {
            assertThat(Descriptors.writes(entry(row))).as(row).isFalse();
        }
    }

    @Test
    void theDocRowIsTheSentenceBelowTheRuleAndBothKnivesCutIt() {
        assertThat(entry("__doc__").getType().name()).isEqualTo("str");
        assertThat(entry("__doc__")).isEqualTo(PyStr.of(SENTENCE));
        assertThat(Slots.getAttr(PyType.CODE, "__doc__")).isEqualTo(PyStr.of(SENTENCE));
        assertThat(Slots.getAttr(PyType.CODE, "__text_signature__")).isEqualTo(PyStr.of(TABLE));
        // The text has no name above its bracket, which `find_signature` tolerates, so the table is cut
        // away and the ten words below the rule are all that is left of it.
        assertThat(PyType.CODE.docText()).isEqualTo(TABLE + "\n--\n\n" + SENTENCE);
    }

    /** {@code code.co_argcount} is the row in the book; only a value gets the number underneath. */
    @Test
    void theRowReadOffTheClassIsTheRowInTheBook() {
        for (String row : STORAGE) {
            if (row.equals("__doc__")) {
                assertThat(Slots.getAttr(PyType.CODE, row)).isEqualTo(entry(row));
            } else {
                assertThat(Slots.getAttr(PyType.CODE, row)).isSameAs(entry(row));
            }
        }
    }

    @Test
    void aRowAskedForTheWrongReceiverIsRefusedBeforeItsWriter() {
        for (String row : List.of("co_argcount", "co_code")) {
            PyObject getter = Slots.getAttr(entry(row), "__get__");
            refuses(
                    "TypeError: descriptor '"
                            + row
                            + "' for 'code' objects doesn't apply to a 'int' object",
                    () -> Slots.call(getter, new PyObject[] {ONE}, null));
        }
        // A method row has no writing half at all, so the question itself has no answer.
        refuses(
                "AttributeError: 'method_descriptor' object has no attribute '__set__'",
                () -> Slots.getAttr(entry("co_lines"), "__set__"));
    }

    @Test
    void aValueAnswersEveryStorageRowFromWhatItCarries() throws IOException {
        PyObject fn = codeOf("fn");

        assertThat(Slots.getAttr(fn, "co_argcount")).isEqualTo(PyInt.of(2));
        assertThat(Slots.getAttr(fn, "co_posonlyargcount")).isEqualTo(PyInt.of(0));
        assertThat(Slots.getAttr(fn, "co_kwonlyargcount")).isEqualTo(PyInt.of(1));
        assertThat(Slots.getAttr(fn, "co_nlocals")).isEqualTo(PyInt.of(5));
        assertThat(Slots.getAttr(fn, "co_stacksize")).isEqualTo(PyInt.of(1));
        // CO_OPTIMIZED, CO_NEWLOCALS, CO_VARARGS and CO_VARKEYWORDS.
        assertThat(Slots.getAttr(fn, "co_flags")).isEqualTo(PyInt.of(15));
        assertThat(Slots.getAttr(fn, "co_name")).isEqualTo(PyStr.of("fn"));
        assertThat(Slots.getAttr(fn, "co_qualname")).isEqualTo(PyStr.of("fn"));
        assertThat(Slots.getAttr(fn, "co_firstlineno")).isEqualTo(PyInt.of(1));
        assertThat(Slots.getAttr(fn, "co_consts")).isEqualTo(PyTuple.of(PyNone.INSTANCE));
        assertThat(Slots.getAttr(fn, "co_names")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(fn, "co_varnames")).isEqualTo(VARNAMES);
        assertThat(Slots.getAttr(fn, "co_cellvars")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(fn, "co_freevars")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(fn, "__doc__")).isEqualTo(PyStr.of(SENTENCE));

        // A parameter shared downward is a cell in the scope that owns it and a free variable below.
        PyObject outer = codeOf("outer");
        PyObject inner = codeOf("outer()");
        assertThat(Slots.getAttr(outer, "co_cellvars")).isEqualTo(PyTuple.of(PyStr.of("total")));
        assertThat(Slots.getAttr(outer, "co_freevars")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(outer, "co_qualname")).isEqualTo(PyStr.of("outer"));
        assertThat(Slots.getAttr(inner, "co_qualname")).isEqualTo(PyStr.of("outer.<locals>.inner"));
        assertThat(Slots.getAttr(inner, "co_cellvars")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(inner, "co_freevars")).isEqualTo(PyTuple.of(PyStr.of("total")));
        assertThat(Slots.getAttr(inner, "co_varnames")).isEqualTo(PyTuple.of());
    }

    @Test
    void aWriteToAFieldIsRefusedInFourSyllablesEitherWay() throws IOException {
        PyObject fn = codeOf("fn");

        for (String member : MEMBERS) {
            refuses("AttributeError: readonly attribute", () -> Slots.setAttr(fn, member, ONE));
            refuses("AttributeError: readonly attribute", () -> Slots.delAttr(fn, member));
        }
    }

    @Test
    void aWriteToATableNamesTheFieldAndTheType() throws IOException {
        PyObject fn = codeOf("fn");

        for (String getset : GETSETS) {
            refuses(
                    "AttributeError: attribute '" + getset + "' of 'code' objects is not writable",
                    () -> Slots.setAttr(fn, getset, ONE));
            refuses(
                    "AttributeError: attribute '" + getset + "' of 'code' objects is not writable",
                    () -> Slots.delAttr(fn, getset));
        }
    }

    /**
     * A method row cannot be replaced because a {@code method_descriptor} has no writing half, and the read
     * that finds it is the one that says so: the row outranks the dictionary a code object does not have, so
     * the refusal names the attribute rather than the missing one.
     */
    @Test
    void aMethodIsRefusedAsReadByTheRowThatIsFiledForIt() throws IOException {
        PyObject fn = codeOf("fn");

        for (String method : METHODS) {
            refuses(
                    "AttributeError: 'code' object attribute '" + method + "' is read-only",
                    () -> Slots.setAttr(fn, method, ONE));
            refuses(
                    "AttributeError: 'code' object attribute '" + method + "' is read-only",
                    () -> Slots.delAttr(fn, method));
        }
    }

    @Test
    void aNameNobodyFilesIsRefusedAsTheMissingNameItIs() throws IOException {
        PyObject fn = codeOf("fn");

        refuses(
                "AttributeError: 'code' object has no attribute 'nonsense'"
                        + " and no __dict__ for setting new attributes",
                () -> Slots.setAttr(fn, "nonsense", ONE));
        refuses(
                "AttributeError: 'code' object has no attribute 'nonsense'"
                        + " and no __dict__ for setting new attributes",
                () -> Slots.delAttr(fn, "nonsense"));
        refuses(
                "AttributeError: 'code' object has no attribute 'nonsense'",
                () -> Slots.getAttr(fn, "nonsense"));
    }

    @Test
    void theClassRefusesItsOwnRows() {
        for (String row : List.of("co_argcount", "co_code", "co_lines", "co_branches", "__doc__")) {
            refuses(
                    "TypeError: cannot set '" + row + "' attribute of immutable type 'code'",
                    () -> Slots.setAttr(PyType.CODE, row, ONE));
            refuses(
                    "TypeError: cannot set '" + row + "' attribute of immutable type 'code'",
                    () -> Slots.delAttr(PyType.CODE, row));
        }
    }
}
