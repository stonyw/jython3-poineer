package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.python.front.cpython314.Cpython314Compiler;

/**
 * {@code code.__new__}, measured against CPython 3.14.10.
 *
 * <p>Corpus case 127 walks the eighteen fields from Python and prints what the reference prints: the two
 * refusals that come before any value is looked at, the converters in the order the generated C sets them, the
 * seven counts read into a C {@code int}, the four negatives that are the constructor's own, the four name runs
 * and what the builder makes of the numbers. Every one of those is an agreement, so what is pinned here is the
 * rest — the answers a script cannot print without disagreeing and the one seam no script can fall into.
 *
 * <p>The seam is the builder. Decision D2 keeps a code format in the front-end that read one and a {@link
 * PyCode} is where that format lives, so a route with no receiver has to borrow one, and this one asks the
 * frame that made the call — the same trade a no-argument {@code super()} and a class body's {@code __module__}
 * already make. Python always has such a frame, which is why the corpus case never sees the sentence every Java
 * call below does; it is also why the builder's own refusals — the malformed {@code co_code}, the count that
 * does not match the runs, the {@code SystemError} at {@code Objects/codeobject.c:465} — are measured only from
 * Python.
 *
 * <p>The rest is identity, and it splits by field rather than by route. This runtime stores {@code co_consts},
 * {@code co_names} and the exception table, so a copy answers the object its body answers and the reference
 * agrees on those three; it derives {@code co_code}, {@code co_linetable}, {@code co_varnames} and the three
 * strings {@code co_filename}, {@code co_name} and {@code co_qualname} from the body it holds, so those come
 * back as fresh objects on every read where the reference hands back the one it was given. Both directions are
 * pinned below, since a differential script can only print the fields that agree.
 */
class CodeConstructorTest {

    @TempDir
    Path work;

    /** The eighteen fields, in the order the generated C converts them rather than the order {@code replace} names. */
    private static final List<String> FIELDS =
            List.of(
                    "co_argcount",
                    "co_posonlyargcount",
                    "co_kwonlyargcount",
                    "co_nlocals",
                    "co_stacksize",
                    "co_flags",
                    "co_code",
                    "co_consts",
                    "co_names",
                    "co_varnames",
                    "co_filename",
                    "co_name",
                    "co_qualname",
                    "co_firstlineno",
                    "co_linetable",
                    "co_exceptiontable",
                    "co_freevars",
                    "co_cellvars");

    /** The seven of the eighteen the C reads into a C {@code int}, as indexes into {@link #FIELDS}. */
    private static final int[] COUNTS = {0, 1, 2, 3, 4, 5, 13};

    /** The six fields this runtime answers afresh from the body and the reference answers with the object kept. */
    private static final List<String> DERIVED =
            List.of(
                    "co_code", "co_linetable", "co_varnames", "co_filename", "co_name", "co_qualname");

    /** The three objects this runtime files the way it was handed them, as the reference also does. */
    private static final List<String> KEPT = List.of("co_consts", "co_names", "co_exceptiontable");

    /** The sentence the format seam answers with, where no frame can give it a code object to build through. */
    private static final String NO_FORMAT =
            "SystemError: code.__new__() outside a running frame has no code format to build";

    private static final PyObject THREE = PyInt.of(3);

    private static final String PROGRAM =
            """
            def fn(a, b):
                return a + b

            def shared(a):
                b = a + 1

                def inner():
                    return b + a

                return inner

            NAMES = ("co_argcount", "co_posonlyargcount", "co_kwonlyargcount", "co_nlocals",
                     "co_stacksize", "co_flags", "co_code", "co_consts", "co_names", "co_varnames",
                     "co_filename", "co_name", "co_qualname", "co_firstlineno", "co_linetable",
                     "co_exceptiontable", "co_freevars", "co_cellvars")


            def fields_of(code):
                return [getattr(code, name) for name in NAMES]


            body = fn.__code__
            copy = type(body)(*fields_of(body))
            closure = shared(1).__code__
            copied_closure = type(closure)(*fields_of(closure))
            sixteen = type(closure)(*fields_of(closure)[:16])
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

    /** The eighteen values a body carries, read as attributes so each is the object Python would hand. */
    private static PyObject[] fieldsOf(PyObject code) {
        PyObject[] fields = new PyObject[FIELDS.size()];
        for (int at = 0; at < FIELDS.size(); at++) {
            fields[at] = Slots.getAttr(code, FIELDS.get(at));
        }
        return fields;
    }

    /** The eighteen fields of a body, with one of them replaced. */
    private static PyObject[] changed(PyObject code, int at, PyObject value) {
        PyObject[] fields = fieldsOf(code);
        fields[at] = value;
        return fields;
    }

    /** {@code code(*fields)}: the constructor reached straight from Java, with no frame running. */
    private static PyObject built(PyObject[] fields) {
        return CodeMembers.construct(fields, null);
    }

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** The script run once, so that a test can read a copy the interpreter itself built inside a frame. */
    private PyModule run() throws IOException {
        if (module == null) {
            Cpython314Compiler.Compiled compiled =
                    Cpython314Compiler.compileFile(
                            Files.writeString(work.resolve("shapes.py"), PROGRAM));
            module = new PyModule("workshop");
            PyRuntime.exec(compiled.code(), module, compiled.fileName());
        }
        return module;
    }

    /** {@code fn.__code__}, read through a running frame so the builder has a format to borrow. */
    private PyObject code(String expression) throws IOException {
        PyModule work = run();
        PyObject function =
                PyRuntime.eval(Cpython314Compiler.compileExpression(expression, work.name()), work);
        return Slots.getAttr(function, "__code__");
    }

    @Test
    void theRowIsTheFirstOfThirtySeven() {
        PyObject row = entry("__new__");

        assertThat(filed().indexOf("__new__")).isZero();
        assertThat(filed()).hasSize(37);
        assertThat(row.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(Slots.getAttr(row, "__name__")).isEqualTo(PyStr.of("__new__"));
        assertThat(Slots.getAttr(row, "__qualname__")).isEqualTo(PyStr.of("code.__new__"));
        assertThat(Slots.getAttr(row, "__module__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(row, "__self__")).isSameAs(PyType.CODE);
        assertThat(Slots.getAttr(row, "__doc__"))
                .isEqualTo(
                        PyStr.of(
                                "Create and return a new object.  See help(type) for accurate signature."));
        assertThat(Slots.getAttr(row, "__text_signature__")).isEqualTo(PyStr.of("($type, *args, **kwargs)"));
        // The name came in from `object`, so the book gains a row where the name list gains nothing.
        assertThat(Dirs.itemsOf(PyType.CODE)).hasSize(50);
    }

    @Test
    void theTwoRefusalsBeforeTheFieldsNameTheClassNotTheRow() throws IOException {
        PyObject[] fields = fieldsOf(code("fn"));

        refuses(
                "TypeError: code() takes no keyword arguments",
                () -> CodeMembers.construct(fields, new String[] {"co_argcount"}));
        refuses("TypeError: code expected at least 16 arguments, got 0", () -> built(new PyObject[0]));
        refuses(
                "TypeError: code expected at least 16 arguments, got 15",
                () -> built(Arrays.copyOf(fields, 15)));
        refuses(
                "TypeError: code expected at most 18 arguments, got 19",
                () -> built(Arrays.copyOf(fields, 19)));
        // Both arity answers come before any converter has looked, so a bad field cannot change them.
        refuses(
                "TypeError: code expected at least 16 arguments, got 2",
                () -> built(new PyObject[] {PyStr.of("x"), PyStr.of("y")}));
    }

    @Test
    void theConvertersRunInTableOrderAndNameTheirPosition() throws IOException {
        PyObject code = code("fn");

        for (int at : COUNTS) {
            refuses(
                    "TypeError: 'str' object cannot be interpreted as an integer",
                    () -> built(changed(code, at, PyStr.of("x"))));
        }
        refuses(
                "TypeError: code() argument 7 must be bytes, not str",
                () -> built(changed(code, 6, PyStr.of("x"))));
        refuses(
                "TypeError: code() argument 8 must be tuple, not list",
                () -> built(changed(code, 7, PyList.of(THREE))));
        refuses(
                "TypeError: code() argument 11 must be str, not int",
                () -> built(changed(code, 10, THREE)));
        refuses(
                "TypeError: code() argument 12 must be str, not bytes",
                () -> built(changed(code, 11, PyBytes.of(new byte[] {1}))));
        refuses(
                "TypeError: code() argument 18 must be tuple, not str",
                () -> built(changed(code, 17, PyStr.of("ab"))));
        // A pair is refused in the order the table set it, whichever way the call wrote it.
        refuses(
                "TypeError: code() argument 11 must be str, not int",
                () -> {
                    PyObject[] fields = changed(code, 16, THREE);
                    fields[10] = THREE;
                    built(fields);
                });
        // Sixteen fields reach the builder, and so the seam, before the two runs the call left out.
        refuses(NO_FORMAT, () -> built(Arrays.copyOf(fieldsOf(code), 16)));
    }

    @Test
    void theSevenCountsAreReadAsIntegersAndOneOfThemOverflows() throws IOException {
        PyObject code = code("fn");

        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> built(changed(code, 0, PyFloat.of(2.0))));
        refuses(
                "TypeError: 'NoneType' object cannot be interpreted as an integer",
                () -> built(changed(code, 13, PyNone.INSTANCE)));
        refuses(
                "OverflowError: Python int too large to convert to C int",
                () -> built(changed(code, 0, PyInt.of(BigInteger.valueOf(2).pow(70)))));
        refuses(
                "OverflowError: Python int too large to convert to C int",
                () ->
                        built(
                                changed(
                                        code,
                                        5,
                                        PyInt.of(
                                                BigInteger.valueOf(-2).pow(31).subtract(BigInteger.ONE)))));
        // A bool is an int to the converter, and the count it carries is the one the seam then reports on.
        refuses(NO_FORMAT, () -> built(changed(code, 4, PyBool.FALSE)));
    }

    @Test
    void theConstructorRefusesFourNegativesAfterEveryConverterHasRun() throws IOException {
        PyObject code = code("fn");

        refuses(
                "ValueError: code: argcount must not be negative",
                () -> built(changed(code, 0, PyInt.of(-1))));
        refuses(
                "ValueError: code: posonlyargcount must not be negative",
                () -> built(changed(code, 1, PyInt.of(-1))));
        refuses(
                "ValueError: code: kwonlyargcount must not be negative",
                () -> built(changed(code, 2, PyInt.of(-1))));
        refuses(
                "ValueError: code: nlocals must not be negative",
                () -> built(changed(code, 3, PyInt.of(-1))));
        // Two of them at once answer in the order the constructor reads, which puts the first in front.
        refuses(
                "ValueError: code: argcount must not be negative",
                () -> {
                    PyObject[] fields = changed(code, 1, PyInt.of(-1));
                    fields[0] = PyInt.of(-1);
                    built(fields);
                });
        // A converter outranks every one of them, and a negative outranks the name runs.
        refuses(
                "TypeError: code() argument 7 must be bytes, not int",
                () -> {
                    PyObject[] fields = changed(code, 0, PyInt.of(-1));
                    fields[6] = THREE;
                    built(fields);
                });
        refuses(
                "ValueError: code: argcount must not be negative",
                () -> {
                    PyObject[] fields = changed(code, 0, PyInt.of(-1));
                    fields[8] = PyTuple.of(THREE);
                    built(fields);
                });
        // The two counts left are not refused here: they reach the builder, and so the seam, first.
        refuses(NO_FORMAT, () -> built(changed(code, 4, PyInt.of(-1))));
        refuses(NO_FORMAT, () -> built(changed(code, 5, PyInt.of(-1))));
    }

    @Test
    void theFourNameRunsRefuseAnythingButStringsInTheirOwnWords() throws IOException {
        PyObject code = code("fn");
        PyTuple oneNumber = PyTuple.of(THREE);

        for (int at : new int[] {8, 9, 16, 17}) {
            refuses(
                    "TypeError: name tuples must contain only strings, not 'int'",
                    () -> built(changed(code, at, oneNumber)));
        }
        refuses(
                "TypeError: name tuples must contain only strings, not 'float'",
                () -> built(changed(code, 8, PyTuple.of(PyStr.of("a"), PyFloat.of(1.5)))));
        // A run of strings passes here and is answered by the builder, which is where the seam sits.
        refuses(NO_FORMAT, () -> built(changed(code, 9, PyTuple.of(PyStr.of("a"), PyStr.of("b")))));
    }

    @Test
    void callingTheTypeReachesTheConstructorRatherThanRefusingIt() throws IOException {
        PyObject[] fields = fieldsOf(code("fn"));

        // Slice 75 left this call answering "calling 'code' is not implemented"; the row now answers it.
        refuses(NO_FORMAT, () -> Constructors.instantiate(PyType.CODE, fields, null));
        refuses(
                "TypeError: code expected at least 16 arguments, got 15",
                () -> Constructors.instantiate(PyType.CODE, Arrays.copyOf(fields, 15), null));
        refuses(
                "TypeError: code() takes no keyword arguments",
                () ->
                        Constructors.instantiate(
                                PyType.CODE,
                                Arrays.copyOf(fields, 17),
                                PyTuple.of(PyStr.of("co_freevars"))));
    }

    @Test
    void aFrameBuiltTheSameBodyTheCompilerWrote() throws IOException {
        PyModule work = run();
        PyObject body = work.get("body");
        PyObject copy = work.get("copy");

        assertThat(copy).isInstanceOf(PyCode.class);
        assertThat(Slots.compare(CompareOp.EQ, copy, body)).isSameAs(PyBool.TRUE);
        for (String name : FIELDS) {
            assertThat(Slots.getAttr(copy, name)).as(name).isEqualTo(Slots.getAttr(body, name));
        }
        assertThat(((PyCode) copy).instructions().length()).isEqualTo(((PyCode) body).instructions().length());
    }

    @Test
    void aCopyKeepsThreeOfTheObjectsItWasGivenAndDerivesTheOtherSix() throws IOException {
        PyModule work = run();
        PyObject body = work.get("body");
        PyObject copy = work.get("copy");

        for (String name : KEPT) {
            assertThat(Slots.getAttr(copy, name)).as(name).isSameAs(Slots.getAttr(body, name));
        }
        // The derived six answer afresh on every read, where the reference answers the object it was given.
        for (String name : DERIVED) {
            PyObject read = Slots.getAttr(body, name);
            assertThat(Slots.getAttr(copy, name)).as(name).isNotSameAs(read);
            assertThat(Slots.getAttr(body, name)).as(name).isNotSameAs(read);
        }
    }

    @Test
    void theHashOfACopyIsItsOwnIdentityNotItsBody() throws IOException {
        PyModule work = run();
        PyObject body = work.get("body");
        PyObject copy = work.get("copy");

        // `code_hash` weighs the tables and the runs there; here it is the object, as `id` is.
        assertThat(copy.hash()).isEqualTo(System.identityHashCode(copy));
        assertThat(body.hash()).isEqualTo(System.identityHashCode(body));
        assertThat(Slots.compare(CompareOp.EQ, copy, body)).isSameAs(PyBool.TRUE);
    }

    @Test
    void sixteenFieldsLeaveTheTwoRunsWithoutNames() throws IOException {
        PyModule work = run();
        PyObject closure = work.get("closure");
        PyObject sixteen = work.get("sixteen");
        PyObject copiedClosure = work.get("copied_closure");

        assertThat(Slots.getAttr(sixteen, "co_freevars")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(sixteen, "co_cellvars")).isEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(closure, "co_freevars")).isNotEqualTo(PyTuple.of());
        assertThat(Slots.getAttr(copiedClosure, "co_freevars"))
                .isEqualTo(Slots.getAttr(closure, "co_freevars"));
        assertThat(Slots.getAttr(copiedClosure, "co_cellvars"))
                .isEqualTo(Slots.getAttr(closure, "co_cellvars"));
        // The two runs the eighteen default are the two the sixteen do not reach at all.
        assertThat(((PyCode) sixteen).freeVars()).isSameAs(PyTuple.EMPTY);
    }
}
