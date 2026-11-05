package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fifty sentences that carry no argument table, measured against CPython 3.14.10.
 *
 * <p>Forty-nine of the names in {@code builtins} are exception or warning classes whose C file writes a
 * {@code tp_doc} beside the {@code PyTypeObject}, and {@code super} is the fiftieth. None of the fifty is
 * welded to a table, so the two reads of a type's text agree on every one of them: {@code find_signature}
 * never reaches a rule, and what {@code __doc__} answers is the stored text whole. {@code super} is the
 * interesting failure — its first line is the class's own name followed by a bracket, so the name test
 * {@code type_get_text_signature} runs does hit, and the reason it still answers {@code None} is the arrow
 * after that bracket and the missing rule: the same guard that keeps the eight usage recipes of {@link
 * BuiltinTextSignatureTableTest} uncut.
 *
 * <p>Corpus case 119 reads all of this from Python. Pinned here instead is the store each declaration keeps,
 * the row each type files beside it, the one class of the family that keeps nothing, and the sentence a write
 * onto a static type is answered with.
 */
class ExceptionSentenceTest {

    /** A type and the {@code tp_doc} its own C file writes down. */
    private record Said(PyType kind, String text) {}

    /** {@code Objects/typeobject.c} keeps {@code super}'s usage as fourteen lines and a final newline. */
    private static final String SUPER_TEXT =
            "super() -> same as super(__class__, <first argument>)\n"
                    + "super(type) -> unbound super object\n"
                    + "super(type, obj) -> bound super object; requires isinstance(obj, type)\n"
                    + "super(type, type2) -> bound super object; requires\n"
                    + "    issubclass(type2, type)\n"
                    + "Typical use to call a cooperative superclass method:\n"
                    + "class C(B):\n"
                    + "    def meth(self, arg):\n"
                    + "        super().meth(arg)\n"
                    + "This works for class methods too:\n"
                    + "class C(B):\n"
                    + "    @classmethod\n"
                    + "    def cmeth(cls, arg):\n"
                    + "        super().cmeth(arg)\n";

    /** The forty-nine exception and warning classes, and {@code super}, with the text each keeps. */
    private static final List<Said> FIFTY =
            List.of(
                    new Said(ExceptionTypes.ARITHMETIC_ERROR, "Base class for arithmetic errors."),
                    new Said(ExceptionTypes.ASSERTION_ERROR, "Assertion failed."),
                    new Said(ExceptionTypes.BLOCKING_IO_ERROR, "I/O operation would block."),
                    new Said(ExceptionTypes.BROKEN_PIPE_ERROR, "Broken pipe."),
                    new Said(ExceptionTypes.BUFFER_ERROR, "Buffer error."),
                    new Said(
                            ExceptionTypes.BYTES_WARNING,
                            "Base class for warnings about bytes and buffer related problems, mostly"
                                    + "\nrelated to conversion from str or comparing to str."),
                    new Said(ExceptionTypes.CHILD_PROCESS_ERROR, "Child process error."),
                    new Said(ExceptionTypes.CONNECTION_ABORTED_ERROR, "Connection aborted."),
                    new Said(ExceptionTypes.CONNECTION_ERROR, "Connection error."),
                    new Said(ExceptionTypes.CONNECTION_REFUSED_ERROR, "Connection refused."),
                    new Said(ExceptionTypes.CONNECTION_RESET_ERROR, "Connection reset."),
                    new Said(
                            ExceptionTypes.DEPRECATION_WARNING,
                            "Base class for warnings about deprecated features."),
                    new Said(ExceptionTypes.EOF_ERROR, "Read beyond end of file."),
                    new Said(
                            ExceptionTypes.ENCODING_WARNING,
                            "Base class for warnings about encodings."),
                    new Said(
                            ExceptionTypes.EXCEPTION,
                            "Common base class for all non-exit exceptions."),
                    new Said(ExceptionTypes.FILE_EXISTS_ERROR, "File already exists."),
                    new Said(ExceptionTypes.FILE_NOT_FOUND_ERROR, "File not found."),
                    new Said(
                            ExceptionTypes.FLOATING_POINT_ERROR,
                            "Floating-point operation failed."),
                    new Said(
                            ExceptionTypes.FUTURE_WARNING,
                            "Base class for warnings about constructs that will change semantically"
                                    + "\nin the future."),
                    new Said(ExceptionTypes.GENERATOR_EXIT, "Request that a generator exit."),
                    new Said(
                            ExceptionTypes.IMPORT_WARNING,
                            "Base class for warnings about probable mistakes in module imports"),
                    new Said(ExceptionTypes.INDEX_ERROR, "Sequence index out of range."),
                    new Said(ExceptionTypes.INTERRUPTED_ERROR, "Interrupted by signal."),
                    new Said(
                            ExceptionTypes.IS_A_DIRECTORY_ERROR,
                            "Operation doesn't work on directories."),
                    new Said(ExceptionTypes.KEYBOARD_INTERRUPT, "Program interrupted by user."),
                    new Said(ExceptionTypes.LOOKUP_ERROR, "Base class for lookup errors."),
                    new Said(ExceptionTypes.MEMORY_ERROR, "Out of memory."),
                    new Said(
                            ExceptionTypes.NOT_A_DIRECTORY_ERROR,
                            "Operation only works on directories."),
                    new Said(
                            ExceptionTypes.NOT_IMPLEMENTED_ERROR,
                            "Method or function hasn't been implemented yet."),
                    new Said(
                            ExceptionTypes.OVERFLOW_ERROR,
                            "Result too large to be represented."),
                    new Said(
                            ExceptionTypes.PENDING_DEPRECATION_WARNING,
                            "Base class for warnings about features which will be deprecated"
                                    + "\nin the future."),
                    new Said(ExceptionTypes.PERMISSION_ERROR, "Not enough permissions."),
                    new Said(ExceptionTypes.PROCESS_LOOKUP_ERROR, "Process not found."),
                    new Said(
                            ExceptionTypes.PYTHON_FINALIZATION_ERROR,
                            "Operation blocked during Python finalization."),
                    new Said(ExceptionTypes.RECURSION_ERROR, "Recursion limit exceeded."),
                    new Said(
                            ExceptionTypes.REFERENCE_ERROR,
                            "Weak ref proxy used after referent went away."),
                    new Said(
                            ExceptionTypes.RESOURCE_WARNING,
                            "Base class for warnings about resource usage."),
                    new Said(ExceptionTypes.RUNTIME_ERROR, "Unspecified run-time error."),
                    new Said(
                            ExceptionTypes.RUNTIME_WARNING,
                            "Base class for warnings about dubious runtime behavior."),
                    new Said(
                            ExceptionTypes.STOP_ASYNC_ITERATION,
                            "Signal the end from iterator.__anext__()."),
                    new Said(
                            ExceptionTypes.SYNTAX_WARNING,
                            "Base class for warnings about dubious syntax."),
                    new Said(
                            ExceptionTypes.SYSTEM_ERROR,
                            "Internal error in the Python interpreter.\n"
                                    + "\nPlease report this to the Python maintainer, along with the"
                                    + " traceback,\nthe Python version, and the hardware/OS platform"
                                    + " and version."),
                    new Said(ExceptionTypes.TIMEOUT_ERROR, "Timeout expired."),
                    new Said(ExceptionTypes.TYPE_ERROR, "Inappropriate argument type."),
                    new Said(
                            ExceptionTypes.UNICODE_WARNING,
                            "Base class for warnings about Unicode related problems, mostly"
                                    + "\nrelated to conversion problems."),
                    new Said(
                            ExceptionTypes.USER_WARNING,
                            "Base class for warnings generated by user code."),
                    new Said(
                            ExceptionTypes.VALUE_ERROR,
                            "Inappropriate argument value (of correct type)."),
                    new Said(ExceptionTypes.WARNING, "Base class for warning categories."),
                    new Said(
                            ExceptionTypes.ZERO_DIVISION_ERROR,
                            "Second argument to a division or modulo operation was zero."),
                    new Said(PyType.SUPER, SUPER_TEXT));

    /** The five sentences that wrap and {@code super}'s recipe: the six texts that are not one line. */
    private static final List<PyType> WRAPPING =
            List.of(
                    ExceptionTypes.BYTES_WARNING,
                    ExceptionTypes.FUTURE_WARNING,
                    ExceptionTypes.PENDING_DEPRECATION_WARNING,
                    ExceptionTypes.UNICODE_WARNING,
                    ExceptionTypes.SYSTEM_ERROR,
                    PyType.SUPER);

    /** {@code type(name, bases, {members})}, the three-argument creator asked to write a class. */
    private static PyType declared(String name, PyType base, Object... members) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < members.length; at += 2) {
            dict.put(PyStr.interned((String) members[at]), (PyObject) members[at + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of(name), PyTuple.of(base), dict},
                        null);
    }

    /** What a class's own dictionary files under a name, which no read of the type cuts. */
    private static PyObject filed(PyType kind, String name) {
        return kind.namespace().get(PyStr.interned(name));
    }

    /** A write a static type answers with the sentence {@code type_setattro} keeps for it. */
    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    @Test
    void eachClassKeepsTheTextItsOwnFileWritesDown() {
        for (Said said : FIFTY) {
            assertThat(said.kind().docText())
                    .as("%s stores the sentence of its C file", said.kind().name())
                    .isEqualTo(said.text());
        }
    }

    @Test
    void noneOfTheFiftyCarriesATableSoBothReadsAnswerOneText() {
        for (Said said : FIFTY) {
            assertThat(PyNativeFunction.signatureOf(said.kind().name(), said.text()))
                    .as("%s has no rule for the cutter to stop at", said.kind().name())
                    .isSameAs(PyNone.INSTANCE);
            assertThat(PyNativeFunction.docOf(said.kind().name(), said.text()))
                    .as("%s keeps its whole sentence", said.kind().name())
                    .isEqualTo(PyStr.of(said.text()));
            assertThat(Slots.getAttr(said.kind(), "__text_signature__"))
                    .as("%s reports no table to a program", said.kind().name())
                    .isSameAs(PyNone.INSTANCE);
            assertThat(Slots.getAttr(said.kind(), "__doc__"))
                    .as("%s reports its sentence to a program", said.kind().name())
                    .isEqualTo(PyStr.of(said.text()));
        }
    }

    @Test
    void superOpensLikeATableAndIsRefusedForTheRule() {
        assertThat(SUPER_TEXT).as("the name test does hit").startsWith("super(");
        String welded = "super()\n--\n\nThe same first line, with a rule under it.\n";
        assertThat(PyNativeFunction.signatureOf("super", welded)).isEqualTo(PyStr.of("()"));
        assertThat(PyNativeFunction.signatureOf("super", SUPER_TEXT)).isSameAs(PyNone.INSTANCE);
        assertThat(PyNativeFunction.docOf("super", SUPER_TEXT)).isEqualTo(PyStr.of(SUPER_TEXT));
    }

    @Test
    void theSixTextsThatAreMoreThanOneLineAndTheFortyFourThatAreNot() {
        assertThat(ExceptionTypes.BYTES_WARNING.docText().split("\n")).hasSize(2);
        assertThat(ExceptionTypes.FUTURE_WARNING.docText().split("\n")).hasSize(2);
        assertThat(ExceptionTypes.PENDING_DEPRECATION_WARNING.docText().split("\n")).hasSize(2);
        assertThat(ExceptionTypes.UNICODE_WARNING.docText().split("\n")).hasSize(2);
        assertThat(ExceptionTypes.SYSTEM_ERROR.docText().split("\n")).hasSize(4);
        assertThat(SUPER_TEXT.chars().filter(c -> c == '\n')).as("fourteen newlines").hasSize(14);

        for (Said said : FIFTY) {
            boolean wraps = WRAPPING.contains(said.kind());
            assertThat(said.kind().docText().split("\n").length > 1)
                    .as("%s is more than one line only if it is one of the six", said.kind().name())
                    .isEqualTo(wraps);
            if (said.kind() != PyType.SUPER) {
                assertThat(said.text())
                        .as("%s is a sentence with nothing written after it", said.kind().name())
                        .doesNotEndWith("\n");
            }
            assertThat(said.text()).as("%s files no tab", said.kind().name()).doesNotContain("\t");
        }
        assertThat(SUPER_TEXT).endsWith("\n");
    }

    @Test
    void aDocumentedTypeFilesItsTextAsItsOwnRow() {
        for (Said said : FIFTY) {
            PyObject row = filed(said.kind(), "__doc__");
            assertThat(row)
                    .as("%s files a plain string, not a descriptor", said.kind().name())
                    .isInstanceOf(PyStr.class);
            assertThat(row)
                    .as("%s files the whole text", said.kind().name())
                    .isEqualTo(PyStr.of(said.text()));
        }
    }

    @Test
    void aValueReadsTheSentenceOfItsClassAndASubclassReadsNothing() {
        PyObject value = ExceptionTypes.VALUE_ERROR.call(new PyObject[] {PyStr.of("nope")}, null);
        assertThat(Slots.getAttr(value, "__doc__"))
                .as("an instance falls through to the class")
                .isEqualTo(PyStr.of("Inappropriate argument value (of correct type)."));

        PyType quiet = declared("Quiet", ExceptionTypes.VALUE_ERROR);
        assertThat(quiet.docText()).as("nothing was copied").isNull();
        assertThat(Slots.getAttr(quiet, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(quiet, "__text_signature__")).isSameAs(PyNone.INSTANCE);

        String stated = "Loud(a)\n--\n\nA subclass with a table of its own.\n";
        PyType loud = declared("Loud", ExceptionTypes.RUNTIME_ERROR, "__doc__", PyStr.of(stated));
        assertThat(loud.docText()).isEqualTo(stated);
        assertThat(Slots.getAttr(loud, "__doc__")).isEqualTo(PyStr.of(stated));
        assertThat(Slots.getAttr(loud, "__text_signature__")).isEqualTo(PyStr.of("(a)"));
    }

    @Test
    void exceptionGroupIsTheOneClassOfTheFamilyWithNothingToSay() {
        assertThat(ExceptionTypes.EXCEPTION_GROUP.docText()).isNull();
        assertThat(Slots.getAttr(ExceptionTypes.EXCEPTION_GROUP, "__doc__"))
                .isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION_GROUP, "__doc__"))
                .isEqualTo(PyStr.of("A combination of multiple unrelated exceptions."));
    }

    @Test
    void aStaticTypeRefusesTheWriteThatWouldMoveTheRead() {
        refuses(
                "TypeError: cannot set '__doc__' attribute of immutable type 'ValueError'",
                () -> Slots.setAttr(ExceptionTypes.VALUE_ERROR, "__doc__", PyStr.of("Rewritten")));
        refuses(
                "TypeError: cannot set '__doc__' attribute of immutable type 'super'",
                () -> Slots.setAttr(PyType.SUPER, "__doc__", PyStr.of("Rewritten")));
        refuses(
                "TypeError: cannot set '__doc__' attribute of immutable type 'SystemError'",
                () -> Slots.setAttr(ExceptionTypes.SYSTEM_ERROR, "__doc__", PyStr.of("Rewritten")));
        assertThat(Slots.getAttr(ExceptionTypes.VALUE_ERROR, "__doc__"))
                .isEqualTo(PyStr.of("Inappropriate argument value (of correct type)."));
    }
}
