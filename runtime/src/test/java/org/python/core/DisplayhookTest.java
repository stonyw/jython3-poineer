package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code sys.displayhook}, the showing half of the interactive loop, measured against CPython 3.14.10.
 *
 * <p>What a call prints is compared elsewhere: {@code m3/corpus/129_displayhook_and_sentences} runs the
 * hook against the reference, write for write. Taken apart here is the order inside one call, which a
 * script can only see by asking a stream to remember what {@code builtins._} held as each write arrived.
 * {@code sys_displayhook} (Python/sysmodule.c:751) reads the builtins module, bails out on {@code None},
 * empties the saved name, and only then requires {@code sys.stdout} — so a program that has deleted the
 * stream still loses the value it asked to see. The repr and the newline are two separate calls, which is
 * how a string arrives quoted and the line after it does not, and the value is saved last, which is why a
 * value whose own repr refuses is the one value the name never gets.
 *
 * <p>The refusals are worded by two different people. The calling convention settles a {@code METH_O}
 * row's count and refuses any name before the body is entered, and both sentences spell the function as
 * module and name; {@code exit} is refused by the convention for a keyword and counts its status itself,
 * in the legacy wording and under its bare name.
 */
class DisplayhookTest {

    /** The texts one call wrote, in the order they arrived. */
    private final List<String> written = new ArrayList<>();

    /** What {@code builtins._} held at each of those writes, which is the order made visible. */
    private final List<String> held = new ArrayList<>();

    private PyObject hook() {
        return PyRuntime.sysAttribute("displayhook");
    }

    /** A stream that writes nothing anywhere and remembers instead. */
    private PyModule sink(PyNativeFunction.Body body) {
        PyModule sink = new PyModule("sink");
        sink.set("write", new PyNativeFunction("write", body));
        return sink;
    }

    /** The stream that records both halves of a call: what arrived, and what the name held then. */
    private PyModule recordingSink() {
        return sink(
                (args, kwNames) -> {
                    written.add(text(args[0]));
                    held.add(held());
                    return PyNone.INSTANCE;
                });
    }

    private PyObject call(PyObject value) {
        return Slots.call(hook(), new PyObject[] {value}, null);
    }

    /** {@code builtins._} in the words the differential case uses for it. */
    private String held() {
        PyObject value = Slots.findAttr(PyRuntime.importModule("builtins"), "_");
        if (value == null) {
            return "missing";
        }
        return value == PyNone.INSTANCE ? "None" : value.repr();
    }

    private static void save(String text) {
        Slots.setAttr(PyRuntime.importModule("builtins"), "_", PyStr.of(text));
    }

    private static String text(PyObject value) {
        return ((PyStr) value).getValue();
    }

    @AfterEach
    void putTheHookTheStreamAndTheNameBack() {
        PyRuntime.setSysAttribute("displayhook", PyRuntime.sysAttribute("__displayhook__"));
        PyRuntime.setSysAttribute("stdout", PyRuntime.sysAttribute("__stdout__"));
        Slots.setAttr(PyRuntime.importModule("builtins"), "_", PyNone.INSTANCE);
    }

    /**
     * One object filed under two names, and the six reads its table row answers.
     *
     * <p>{@code COPY_SYS_ATTR} copies the entry rather than building another function, which is why
     * replacing the working name leaves the doubled one pointing at the interpreter's own printer. The
     * sentence and the argument table are cut out of the one text the row carries, and {@code __self__} is
     * the module the row was filed in — read there, not by name, because a built-in's {@code m_self} is the
     * module itself.
     */
    @Test
    void theTwoNamesOfTheHookAreTheOneEntry() {
        PyObject printer = hook();

        assertThat(printer).isSameAs(PyRuntime.sysAttribute("__displayhook__"));
        assertThat(printer).isInstanceOf(PyNativeFunction.class);
        assertThat(printer.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(printer.repr()).isEqualTo("<built-in function displayhook>");
        assertThat(text(Slots.getAttr(printer, "__name__"))).isEqualTo("displayhook");
        assertThat(text(Slots.getAttr(printer, "__qualname__"))).isEqualTo("displayhook");
        assertThat(text(Slots.getAttr(printer, "__module__"))).isEqualTo("sys");
        assertThat(Slots.getAttr(printer, "__self__").getType().name()).isEqualTo("module");
        assertThat(text(Slots.getAttr(printer, "__text_signature__")))
                .isEqualTo("($module, object, /)");
        assertThat(text(Slots.getAttr(printer, "__doc__")))
                .isEqualTo("Print an object to sys.stdout and also save it in builtins._");

        PyObject standIn = new PyNativeFunction("stand_in", (args, kwNames) -> PyNone.INSTANCE);
        PyRuntime.setSysAttribute("displayhook", standIn);

        assertThat(PyRuntime.sysAttribute("displayhook")).isSameAs(standIn);
        assertThat(PyRuntime.sysAttribute("__displayhook__")).isSameAs(printer);
    }

    /**
     * The three rows of the same book that had nothing to say until this slice.
     *
     * <p>{@code exit}'s table defaults its status, and that is the only place a person can read that
     * {@code sys.exit()} and {@code sys.exit(None)} are the same call; the two sentences below the {@code
     * --} rule of each row are what {@code __doc__} keeps, the table above it what {@code
     * __text_signature__} keeps.
     */
    @Test
    void theRowsOfTheSameBookCarryTheirTablesAndSentences() {
        says(
                "exc_info",
                "($module, /)",
                "Return current exception information: (type, value, traceback).\n"
                        + "\n"
                        + "Return information about the most recent exception caught by an except\n"
                        + "clause in the current stack frame or in an older stack frame.");
        says(
                "getdefaultencoding",
                "($module, /)",
                "Return the current default encoding used by the Unicode implementation.");
        says(
                "exit",
                "($module, status=None, /)",
                "Exit the interpreter by raising SystemExit(status).\n"
                        + "\n"
                        + "If the status is omitted or None, it defaults to zero (i.e., success).\n"
                        + "If the status is an integer, it will be used as the system exit status.\n"
                        + "If it is another kind of object, it will be printed and the system\n"
                        + "exit status will be one (i.e., failure).");
    }

    private static void says(String name, String table, String sentence) {
        PyObject row = PyRuntime.sysAttribute(name);

        assertThat(text(Slots.getAttr(row, "__text_signature__"))).isEqualTo(table);
        assertThat(text(Slots.getAttr(row, "__doc__"))).isEqualTo(sentence);
    }

    /**
     * The five steps of one call, in the order the C source writes them.
     *
     * <p>The name is emptied before anything reaches the stream, so both writes see {@code None} where the
     * program had left a value, and the repr arrives as its own call with the newline after it — quoted,
     * because what is written is the repr and the flags say raw.
     */
    @Test
    void oneCallEmptiesTheNameWritesTwiceAndSavesTheValueLast() {
        save("SENTINEL");
        PyRuntime.setSysAttribute("stdout", recordingSink());

        assertThat(call(PyStr.of("hi"))).isSameAs(PyNone.INSTANCE);

        assertThat(written).containsExactly("'hi'", "\n");
        assertThat(held).containsExactly("None", "None");
        assertThat(held()).isEqualTo("'hi'");
    }

    /**
     * {@code None} is not a result worth showing, and the bail-out is earlier than the stream is asked.
     *
     * <p>Nothing is written and the saved name keeps what the program left in it — and the stream is not
     * required either, so a call that shows nothing succeeds even with no stream to show it to.
     */
    @Test
    void noneLeavesTheStreamUntouchedAndIsNotEvenShownToIt() {
        save("SENTINEL");
        PyRuntime.setSysAttribute("stdout", recordingSink());

        assertThat(call(PyNone.INSTANCE)).isSameAs(PyNone.INSTANCE);

        assertThat(written).isEmpty();
        assertThat(held()).isEqualTo("'SENTINEL'");

        Slots.delAttr(PyRuntime.importModule("sys"), "stdout");
        assertThat(call(PyNone.INSTANCE)).isSameAs(PyNone.INSTANCE);
        assertThat(held()).isEqualTo("'SENTINEL'");
    }

    /**
     * What is saved is the value, not the text it was shown as.
     *
     * <p>The name holds the very object, which is what makes {@code _} usable for a result a person means
     * to work with rather than to read.
     */
    @Test
    void theValueSavedIsTheValueItself() {
        save("SENTINEL");
        PyRuntime.setSysAttribute("stdout", sink((args, kwNames) -> PyNone.INSTANCE));
        PyObject item = PyList.of(PyInt.of(1), PyInt.of(2));

        call(item);

        assertThat(Slots.findAttr(PyRuntime.importModule("builtins"), "_")).isSameAs(item);
    }

    /**
     * A repr that refuses is the one value the saved name never gets.
     *
     * <p>The emptying has already happened, so the name reads {@code None} rather than the value or what
     * the program left there — and nothing reached the stream, because the text was never made.
     */
    @Test
    void aReprThatRefusesLeavesTheNameEmptied() {
        save("SENTINEL");
        PyRuntime.setSysAttribute("stdout", recordingSink());
        PyObject refused =
                new PyObject(PyType.OBJECT) {
                    @Override
                    public String repr() {
                        throw Slots.valueError("nope");
                    }
                };

        refuses("ValueError: nope", () -> call(refused));

        assertThat(written).isEmpty();
        assertThat(held()).isEqualTo("None");
    }

    /**
     * The calling convention of a row that takes one value, and the two sentences it says.
     *
     * <p>The count is the convention's, not the body's, so it names the function as module and name and
     * counts a keyword among the arguments; an empty unpacking is no keyword at all and so is refused by
     * count, the way {@code _PyStack_UnpackDict} leaves {@code kwnames} null.
     */
    @Test
    void aRowThatTakesOneValueRefusesEveryOtherShapeOfCall() {
        for (int given : new int[] {0, 2, 3}) {
            PyObject[] arguments = new PyObject[given];
            for (int at = 0; at < given; at++) {
                arguments[at] = PyInt.of(at);
            }
            assertThatThrownBy(() -> Slots.call(hook(), arguments, null))
                    .as("%d given", given)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo(
                                                    "TypeError: sys.displayhook() takes exactly one"
                                                            + " argument ("
                                                            + given
                                                            + " given)"));
        }

        refuses(
                "TypeError: sys.displayhook() takes no keyword arguments",
                () ->
                        Slots.call(
                                hook(),
                                new PyObject[] {PyInt.ONE},
                                PyBoundMethod.keywordTuple(new String[] {"object"})));
        refuses(
                "TypeError: sys.displayhook() takes exactly one argument (0 given)",
                () -> Slots.call(hook(), new PyObject[0], PyTuple.of()));
    }

    /**
     * The two rows that take nothing, which refuse both kinds of argument the same way.
     *
     * <p>Both sentences name the function as module and name, because the convention writes them, and an
     * empty unpacking is not an argument at all: {@code sys.exc_info(**{})} answers the triple.
     */
    @Test
    void aRowThatTakesNothingRefusesBothArgumentsAndNames() {
        for (String name : List.of("exc_info", "getdefaultencoding")) {
            PyObject row = PyRuntime.sysAttribute(name);

            assertThatThrownBy(
                            () -> Slots.call(row, new PyObject[] {PyInt.ONE, PyInt.of(2)}, null))
                    .as("%s with two", name)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo(
                                                    "TypeError: sys."
                                                            + name
                                                            + "() takes no arguments (2 given)"));
            refuses(
                    "TypeError: sys." + name + "() takes no keyword arguments",
                    () ->
                            Slots.call(
                                    row,
                                    new PyObject[0],
                                    PyBoundMethod.keywordTuple(new String[] {"x"})));
        }

        // An empty unpacking names nothing, which the convention reads as no keyword at all.
        PyObject encoding =
                Slots.call(
                        PyRuntime.sysAttribute("getdefaultencoding"), new PyObject[0], PyTuple.of());
        assertThat(text(encoding)).isEqualTo("utf-8");
        PyObject triple =
                Slots.call(PyRuntime.sysAttribute("exc_info"), new PyObject[0], PyTuple.of());
        assertThat(triple).isInstanceOfSatisfying(
                PyTuple.class,
                answered -> {
                    assertThat(answered.size()).isEqualTo(3);
                    for (int at = 0; at < 3; at++) {
                        assertThat(answered.at(at)).isSameAs(PyNone.INSTANCE);
                    }
                });
    }

    /**
     * {@code exit} is refused for the keyword before it counts its status.
     *
     * <p>The convention settles the name and never enters {@code sys_exit_impl}, whose
     * {@code _PyArg_CheckPositional} would have said the legacy sentence under the bare name — which is
     * the sentence two or three positions still get.
     */
    @Test
    void exitRefusesAKeywordBeforeItCountsItsStatus() {
        refuses(
                "TypeError: sys.exit() takes no keyword arguments",
                () ->
                        Slots.call(
                                PyRuntime.sysAttribute("exit"),
                                new PyObject[0],
                                PyBoundMethod.keywordTuple(new String[] {"status"})));

        for (int given : new int[] {2, 3}) {
            PyObject[] arguments = new PyObject[given];
            for (int at = 0; at < given; at++) {
                arguments[at] = PyInt.of(at);
            }
            assertThatThrownBy(
                            () -> Slots.call(PyRuntime.sysAttribute("exit"), arguments, null))
                    .as("%d given", given)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo(
                                                    "TypeError: exit expected at most 1 argument, got "
                                                            + given));
        }
    }

    /**
     * {@code sys.stdout} is a name a program may leave in five unusable states, and each complains
     * differently about the same call.
     *
     * <p>Deleted and {@code None} are one refusal, the runtime's, made before any write is attempted; an
     * integer and a module that files no {@code write} are the read's own, worded by the type that was
     * asked — the module names itself, because {@code module_getattro} knows which namespace failed; and a
     * stream whose own write refuses is the only state whose complaint the stream wrote itself. In all five
     * the name has already been emptied.
     */
    @Test
    void theStreamRefusesInEveryStateAProgramCanLeaveItIn() {
        refuses("RuntimeError: lost sys.stdout", () -> shown(PyNone.INSTANCE, PyInt.of(7)));
        refuses("RuntimeError: lost sys.stdout", () -> shown(null, PyInt.of(7)));
        refuses(
                "AttributeError: 'int' object has no attribute 'write'",
                () -> shown(PyInt.of(1), PyInt.of(7)));
        refuses(
                "AttributeError: module 'sink' has no attribute 'write'",
                () -> shown(new PyModule("sink"), PyInt.of(7)));
        refuses(
                "OSError: disk full",
                () ->
                        shown(
                                sink(
                                        (args, kwNames) -> {
                                            throw PyRaise.raise(
                                                    ExceptionTypes.OS_ERROR, "disk full");
                                        }),
                                PyInt.of(7)));

        assertThat(held()).isEqualTo("None");

        // A value nobody asks to see never reaches the stream, so a stream that is gone is not a failure.
        assertThat(shown(PyNone.INSTANCE, PyNone.INSTANCE)).isSameAs(PyNone.INSTANCE);
        assertThat(held()).isEqualTo("'SENTINEL'");
    }

    /**
     * One call shown to the stream in one state, with the name left as the program put it.
     *
     * <p>The stream itself is not restored here: {@link #putTheHookTheStreamAndTheNameBack} puts the
     * interpreter's own back once the assertion has been made, which is the only moment a print is safe.
     */
    private PyObject shown(PyObject stream, PyObject value) {
        save("SENTINEL");
        if (stream == null) {
            Slots.delAttr(PyRuntime.importModule("sys"), "stdout");
        } else {
            PyRuntime.setSysAttribute("stdout", stream);
        }
        return call(value);
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }
}
