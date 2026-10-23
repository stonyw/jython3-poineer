package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code sys.excepthook} and the namespace it is filed in, measured against CPython 3.14.8.
 *
 * <p>What the hook <em>prints</em> is compared elsewhere: {@code m3/corpus/40_default_hook_answers} asks
 * it directly and {@code m3/uncaught} hands it the exceptions a program cannot catch, stream for stream
 * against the reference. Taken apart here is the machinery a script cannot point at — that the two names
 * hold one object, that the keyword check is the call machinery's and so runs before the body counts,
 * which of the two names each refusal wears, and what a module does when one of its names is taken away.
 *
 * <p>The two names each say something different about the function. The count is written under the bare
 * {@code m_name}, because {@code _PyArg_CheckPositional} is given that and the table is inside the
 * implementation; the keyword refusal is written by {@code cfunction_check_kwargs}, which formats the
 * function as {@code _PyObject_FunctionStr} does — the module and the qualified name — so the same
 * function is {@code excepthook} in one sentence and {@code sys.excepthook} in the other.
 */
class ExcepthookTest {

    /** The name the hook is read under, and the one CPython keeps beside it as the original. */
    private static PyObject hook() {
        return PyRuntime.sysAttribute("excepthook");
    }

    @AfterEach
    void putTheHookBack() {
        PyRuntime.setSysAttribute("excepthook", PyRuntime.sysAttribute("__excepthook__"));
    }

    /**
     * One object, filed twice, and the names it answers with.
     *
     * <p>{@code COPY_SYS_ATTR} gives {@code __excepthook__} the same object rather than a copy, which is
     * why replacing the hook leaves the second name pointing at the interpreter's own printer. The doc is
     * the one CPython writes for this function; a built-in that has no doc answers {@code None}, and that
     * is what a plain one still does here.
     */
    @Test
    void theHookIsOneObjectFiledUnderTwoNames() {
        PyObject printer = hook();

        assertThat(printer).isSameAs(PyRuntime.sysAttribute("__excepthook__"));
        assertThat(printer).isInstanceOf(PyNativeFunction.class);
        assertThat(printer.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(printer.repr()).isEqualTo("<built-in function excepthook>");
        assertThat(Slots.getAttr(printer, "__name__")).hasToString("'excepthook'");
        assertThat(Slots.getAttr(printer, "__qualname__")).hasToString("'excepthook'");
        assertThat(Slots.getAttr(printer, "__doc__"))
                .hasToString(
                        "'Handle an exception by displaying it with a traceback on sys.stderr.'");
        assertThat(Slots.findAttr(printer, "__dict__")).isNull();

        PyObject standIn = new PyNativeFunction("stand_in", (args, kwNames) -> PyNone.INSTANCE);
        PyRuntime.setSysAttribute("excepthook", standIn);

        assertThat(standIn.repr()).isEqualTo("<built-in function stand_in>");
        assertThat(Slots.getAttr(standIn, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(PyRuntime.sysAttribute("excepthook")).isSameAs(standIn);
        assertThat(PyRuntime.sysAttribute("__excepthook__")).isSameAs(printer);
    }

    /**
     * The two families of refusal, and which of them runs first.
     *
     * <p>A keyword is settled by the call machinery before the implementation is entered, so a call that
     * gave the wrong number <em>and</em> a name is refused for the name. Counting is the implementation's
     * own and says {@code expected 3 arguments, got N} — the legacy wording, bare, with keywords counted
     * as arguments only in the sense that they never reach it.
     */
    @Test
    void theKeywordRefusalComesBeforeTheCountAndNamesTheModule() {
        PyTuple kind = PyBoundMethod.keywordTuple(new String[] {"kind"});

        assertThatThrownBy(() -> Slots.call(hook(), new PyObject[] {PyInt.ONE}, kind))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "TypeError: sys.excepthook() takes no keyword"
                                                        + " arguments"));

        for (int given = 0; given <= 4; given++) {
            if (given == 3) {
                continue;
            }
            PyObject[] arguments = new PyObject[given];
            for (int at = 0; at < given; at++) {
                arguments[at] = PyInt.of(at);
            }
            String expected = "TypeError: excepthook expected 3 arguments, got " + given;
            assertThatThrownBy(() -> Slots.call(hook(), arguments, null))
                    .as("%d given", given)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage()).isEqualTo(expected));
        }
    }

    /**
     * No names at all is the same call as no names unpacked.
     *
     * <p>{@code f(**{})} hands over an empty name list, which {@link PyBoundMethod#keywordNames} reads as
     * none — the way {@code _PyStack_UnpackDict} leaves {@code kwnames} null — so the hook is refused by
     * count rather than for a keyword. Give it one name and the keyword sentence comes back.
     */
    @Test
    void anEmptyUnpackingIsRefusedByCountAndOneNameByTheKeywordCheck() {
        assertThatThrownBy(() -> Slots.call(hook(), new PyObject[] {PyInt.ONE}, PyTuple.of()))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("TypeError: excepthook expected 3 arguments, got 1"));
        assertThatThrownBy(
                        () ->
                                Slots.call(
                                        hook(),
                                        new PyObject[0],
                                        PyBoundMethod.keywordTuple(new String[] {"a"})))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "TypeError: sys.excepthook() takes no keyword"
                                                        + " arguments"));
    }

    /**
     * A module's name is its dictionary, so taking an attribute away really removes it.
     *
     * <p>That is how {@code del sys.excepthook} reaches the launcher as a name that is not there rather
     * than as nothing, and the two sentences differ by design: the read names the module, because
     * {@code module_getattro} knows which namespace was asked, while the delete goes through the generic
     * refusal every type shares. {@code __dict__} belongs to the type rather than to the dictionary, and
     * CPython lets it be read but not taken away.
     */
    @Test
    void aModuleNamesItselfInARefusalAndLosesAnAttributeOnADelete() {
        PyModule module = new PyModule("workshop");
        module.set("tool", PyInt.of(3));

        assertThat(Slots.getAttr(module, "tool")).hasToString("3");
        Slots.delAttr(module, "tool");
        assertThat(Slots.findAttr(module, "tool")).isNull();

        refuses(
                "AttributeError: module 'workshop' has no attribute 'tool'",
                () -> Slots.getAttr(module, "tool"));
        refuses(
                "AttributeError: 'module' object has no attribute 'tool'",
                () -> Slots.delAttr(module, "tool"));
        refuses("AttributeError: readonly attribute", () -> Slots.delAttr(module, "__dict__"));

        // __main__ is a module too, and reads its own namespace the same way.
        assertThat(Slots.findAttr(PyRuntime.mainModule(), "never_stored")).isNull();
        refuses(
                "AttributeError: module '__main__' has no attribute 'never_stored'",
                () -> Slots.getAttr(PyRuntime.mainModule(), "never_stored"));
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }
}
