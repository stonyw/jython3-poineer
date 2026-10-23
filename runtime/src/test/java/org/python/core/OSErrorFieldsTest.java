package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The exception family as {@code _PyExc_InitState} files it, and the fields an {@code OSError} keeps
 * beside its arguments, measured against CPython 3.14.8.
 *
 * <p>The corpus script raises these classes and reads them the way a program does. This is the machinery a
 * script cannot point at: which names builtins holds and which two of them are one class, where each of the
 * fifteen {@code OSError}s hangs, whether the error number was read as a number before it was read as an
 * argument, and which half of {@code oserror_parse_args} a call of three, five or six arguments takes. The
 * classes here are the built-in ones Java declares and the arguments are Java's own objects, so each
 * assertion is about the table rather than about a class body that happens to match it.
 */
class OSErrorFieldsTest {

    /** The seventy-one names 3.14.8 files on this platform, in the order {@code dir(builtins)} gives them. */
    private static final List<String> EXCEPTION_NAMES =
            List.of(
                    "ArithmeticError", "AssertionError", "AttributeError", "BaseException",
                    "BaseExceptionGroup", "BlockingIOError", "BrokenPipeError", "BufferError",
                    "BytesWarning", "ChildProcessError", "ConnectionAbortedError", "ConnectionError",
                    "ConnectionRefusedError", "ConnectionResetError", "DeprecationWarning", "EOFError",
                    "EncodingWarning", "EnvironmentError", "Exception", "ExceptionGroup",
                    "FileExistsError", "FileNotFoundError", "FloatingPointError", "FutureWarning",
                    "GeneratorExit", "IOError", "ImportError", "ImportWarning", "IndentationError",
                    "IndexError", "InterruptedError", "IsADirectoryError", "KeyError",
                    "KeyboardInterrupt", "LookupError", "MemoryError", "ModuleNotFoundError", "NameError",
                    "NotADirectoryError", "NotImplementedError", "OSError", "OverflowError",
                    "PendingDeprecationWarning", "PermissionError", "ProcessLookupError",
                    "PythonFinalizationError", "RecursionError", "ReferenceError", "ResourceWarning",
                    "RuntimeError", "RuntimeWarning", "StopAsyncIteration", "StopIteration", "SyntaxError",
                    "SyntaxWarning", "SystemError", "SystemExit", "TabError", "TimeoutError", "TypeError",
                    "UnboundLocalError", "UnicodeDecodeError", "UnicodeEncodeError", "UnicodeError",
                    "UnicodeTranslateError", "UnicodeWarning", "UserWarning", "ValueError", "Warning",
                    "ZeroDivisionError", "_IncompleteInputError");

    /** The eleven classes {@code warnings.warn} sorts a message by, and the ten leaves beside them. */
    private static final List<PyType> WARNINGS =
            List.of(
                    ExceptionTypes.BYTES_WARNING,
                    ExceptionTypes.DEPRECATION_WARNING,
                    ExceptionTypes.ENCODING_WARNING,
                    ExceptionTypes.FUTURE_WARNING,
                    ExceptionTypes.IMPORT_WARNING,
                    ExceptionTypes.PENDING_DEPRECATION_WARNING,
                    ExceptionTypes.RESOURCE_WARNING,
                    ExceptionTypes.RUNTIME_WARNING,
                    ExceptionTypes.SYNTAX_WARNING,
                    ExceptionTypes.UNICODE_WARNING,
                    ExceptionTypes.USER_WARNING);

    /** The eleven direct children of {@code OSError}, with {@code ConnectionError} among them. */
    private static final List<PyType> OS_CHILDREN =
            List.of(
                    ExceptionTypes.BLOCKING_IO_ERROR,
                    ExceptionTypes.CHILD_PROCESS_ERROR,
                    ExceptionTypes.CONNECTION_ERROR,
                    ExceptionTypes.FILE_EXISTS_ERROR,
                    ExceptionTypes.FILE_NOT_FOUND_ERROR,
                    ExceptionTypes.INTERRUPTED_ERROR,
                    ExceptionTypes.IS_A_DIRECTORY_ERROR,
                    ExceptionTypes.NOT_A_DIRECTORY_ERROR,
                    ExceptionTypes.PERMISSION_ERROR,
                    ExceptionTypes.PROCESS_LOOKUP_ERROR,
                    ExceptionTypes.TIMEOUT_ERROR);

    private static final List<PyType> CONNECTION_LEAVES =
            List.of(
                    ExceptionTypes.BROKEN_PIPE_ERROR,
                    ExceptionTypes.CONNECTION_ABORTED_ERROR,
                    ExceptionTypes.CONNECTION_REFUSED_ERROR,
                    ExceptionTypes.CONNECTION_RESET_ERROR);

    /** {@code class Name(OSError): <members>}, made the way a class body makes one. */
    private static PyType filed(String name, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType)
                PyType.TYPE.call(
                        new PyObject[] {
                            PyStr.of(name),
                            PyTuple.of(ExceptionTypes.OS_ERROR),
                            dict
                        },
                        null);
    }

    /** {@code Name(*arguments)}: the call a program makes, put through {@code type_call}. */
    private static PyObject make(PyType kind, PyObject... arguments) {
        return kind.call(arguments, null);
    }

    /** A field as the value answers it, or the refusal it answers with. */
    private static String answer(PyObject value, String name) {
        try {
            return Slots.getAttr(value, name).repr();
        } catch (PyRaise refused) {
            return refused.getMessage();
        }
    }

    /** {@code repr}, arguments, the five fields and the text — one line, so an assertion can name a shape. */
    private static String shape(PyObject value) {
        return "%s | %s | %s | %s | %s | %s | %s | %s"
                .formatted(
                        value.repr(),
                        ((PyBaseException) value).args().repr(),
                        answer(value, "errno"),
                        answer(value, "strerror"),
                        answer(value, "filename"),
                        answer(value, "filename2"),
                        answer(value, "characters_written"),
                        value.str());
    }

    /** {@code property(fget)}, the one half a read-only member has. */
    private static PyObject property(PyObject getter) {
        return PyType.PROPERTY.call(
                new PyObject[] {getter, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE}, null);
    }

    /** A function Java wrote, standing in for the lambda a class body would have named. */
    private static PyNativeFunction getter(String name, PyObject answer) {
        return new PyNativeFunction(name, (args, kwNames) -> answer);
    }

    /** {@code TypeError} and friends in CPython's own words, which the runtime repeats. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run();
    }

    /**
     * {@code _PyExc_InitState}'s list, in one namespace, with the two aliases it files as {@code
     * INIT_ALIAS} pointing at the same class.
     *
     * <p>The count matters as much as the names: a thirteenth {@code OSError} or a missing warning category
     * is a class a program cannot catch. {@code WindowsError} is the third alias and is absent because
     * CPython files it only in a Windows build, which is also why this platform's list has seventy-one
     * names rather than seventy-two.
     */
    @Test
    void builtinsFilesEveryExceptionNameOfTheInitState() {
        PyDict namespace = PyRuntime.builtins();
        for (String name : EXCEPTION_NAMES) {
            assertThat(namespace.get(PyStr.interned(name)))
                    .as(name)
                    .isInstanceOfSatisfying(
                            PyType.class,
                            kind ->
                                    assertThat(kind.matches(ExceptionTypes.BASE_EXCEPTION))
                                            .as(name)
                                            .isTrue());
        }
        assertThat(
                namespace.getItems().values().stream()
                        .filter(
                                value ->
                                        value instanceof PyType kind
                                                && kind.matches(ExceptionTypes.BASE_EXCEPTION))
                        .count())
                .isEqualTo(EXCEPTION_NAMES.size());

        assertThat(namespace.get(PyStr.interned("IOError"))).isSameAs(ExceptionTypes.OS_ERROR);
        assertThat(namespace.get(PyStr.interned("EnvironmentError"))).isSameAs(ExceptionTypes.OS_ERROR);
        assertThat(ExceptionTypes.OS_ERROR.name()).isEqualTo("OSError");
        assertThat(namespace.get(PyStr.interned("WindowsError"))).isNull();
        assertThat(namespace.get(PyStr.interned("_IncompleteInputError")))
                .isSameAs(ExceptionTypes.INCOMPLETE_INPUT_ERROR);
    }

    /**
     * The tree the error number is a table on.
     *
     * <p>{@code ConnectionError} is the only intermediate of the fifteen, and {@code TimeoutError} is not
     * under it: 3.10 folded the socket's own timeout into the {@code OSError} family beside the four
     * connection leaves, so {@code except ConnectionError} must not catch a timeout.
     */
    @Test
    void theFifteenClassesUnderOSErrorHangWhereCpythonHangsThem() {
        assertThat(ExceptionTypes.OS_ERROR.parent()).isSameAs(ExceptionTypes.EXCEPTION);
        for (PyType child : OS_CHILDREN) {
            assertThat(child.parent()).as(child.name()).isSameAs(ExceptionTypes.OS_ERROR);
        }
        for (PyType leaf : CONNECTION_LEAVES) {
            assertThat(leaf.parent()).as(leaf.name()).isSameAs(ExceptionTypes.CONNECTION_ERROR);
        }
        assertThat(ExceptionTypes.CONNECTION_RESET_ERROR.matches(ExceptionTypes.OS_ERROR)).isTrue();
        assertThat(ExceptionTypes.TIMEOUT_ERROR.matches(ExceptionTypes.CONNECTION_ERROR)).isFalse();
        assertThat(ExceptionTypes.BROKEN_PIPE_ERROR.matches(ExceptionTypes.TIMEOUT_ERROR)).isFalse();
    }

    /**
     * The warning categories and the leaves that arrived after the first slices needed any.
     *
     * <p>In 3.14 these are ordinary exceptions: {@code Warning(message=…, category=…, stacklevel=…)} is
     * gone, so a category is called with arguments like any other class and refuses keywords in the same
     * sentence. {@code StopAsyncIteration} sits beside {@code StopIteration} rather than under it, because
     * one loop's end must not read as the other's.
     */
    @Test
    void theWarningFamilyIsElevenOrdinaryClassesAndTheLateLeavesSitWhere314PutThem() {
        assertThat(ExceptionTypes.WARNING.parent()).isSameAs(ExceptionTypes.EXCEPTION);
        for (PyType warning : WARNINGS) {
            assertThat(warning.parent()).as(warning.name()).isSameAs(ExceptionTypes.WARNING);
        }

        assertThat(make(ExceptionTypes.WARNING, PyStr.of("m")).repr()).isEqualTo("Warning('m')");
        assertThat(make(ExceptionTypes.WARNING, PyStr.of("m")).str()).isEqualTo("m");
        refuses(
                "TypeError: Warning() takes no keyword arguments",
                () ->
                        ExceptionTypes.WARNING.call(
                                new PyObject[0], PyBoundMethod.keywordTuple(new String[] {"category"})));

        assertThat(ExceptionTypes.STOP_ASYNC_ITERATION.parent())
                .isSameAs(ExceptionTypes.EXCEPTION)
                .isNotSameAs(ExceptionTypes.STOP_ITERATION);
        assertThat(ExceptionTypes.FLOATING_POINT_ERROR.parent())
                .isSameAs(ExceptionTypes.ARITHMETIC_ERROR);
        assertThat(ExceptionTypes.INDENTATION_ERROR.parent()).isSameAs(ExceptionTypes.SYNTAX_ERROR);
        assertThat(ExceptionTypes.TAB_ERROR.parent()).isSameAs(ExceptionTypes.INDENTATION_ERROR);
        assertThat(ExceptionTypes.INCOMPLETE_INPUT_ERROR.parent())
                .isSameAs(ExceptionTypes.SYNTAX_ERROR);
        assertThat(ExceptionTypes.PYTHON_FINALIZATION_ERROR.parent())
                .isSameAs(ExceptionTypes.RUNTIME_ERROR);
        for (PyType leaf :
                List.of(
                        ExceptionTypes.REFERENCE_ERROR,
                        ExceptionTypes.EOF_ERROR,
                        ExceptionTypes.BUFFER_ERROR)) {
            assertThat(leaf.parent()).as(leaf.name()).isSameAs(ExceptionTypes.EXCEPTION);
        }
    }

    /**
     * {@code OSError_new}'s promotion, which is a lookup on the base and nothing else.
     *
     * <p>Three gates, in this order: the class called has to be {@code OSError} exactly, so a subclass is
     * never narrowed; the first argument has to answer {@code PyLong_Check}, so {@code "1"} stays a text
     * filed in the number field; and the number has to be in the table, which is this platform's — 11 is
     * {@code EAGAIN} where {@code EINPROGRESS} is not 36, and 107 is {@code ENOTCAPABLE} only here. A bool
     * passes {@code PyLong_Check} and so reads as the dictionary's key {@code 1}.
     */
    @Test
    void theErrorNumberPicksTheClassOnlyWhenTheBaseWasTheOneCalled() {
        assertThat(make(ExceptionTypes.OS_ERROR, PyInt.of(2), PyStr.of("m")).getType())
                .isSameAs(ExceptionTypes.FILE_NOT_FOUND_ERROR);
        assertThat(make(ExceptionTypes.OS_ERROR, PyInt.of(35), PyStr.of("m")).getType())
                .isSameAs(ExceptionTypes.BLOCKING_IO_ERROR);
        assertThat(make(ExceptionTypes.OS_ERROR, PyBool.TRUE, PyStr.of("m")).getType())
                .isSameAs(ExceptionTypes.PERMISSION_ERROR);
        assertThat(make(ExceptionTypes.OS_ERROR, PyStr.of("1"), PyStr.of("m")).getType())
                .isSameAs(ExceptionTypes.OS_ERROR);
        assertThat(make(ExceptionTypes.OS_ERROR, PyStr.of("1"), PyStr.of("m")).getAttr("errno"))
                .isEqualTo(PyStr.of("1"));

        // Unmapped here, and no promotion for a class that was named rather than inferred.
        assertThat(make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("m")).getType())
                .isSameAs(ExceptionTypes.OS_ERROR);
        assertThat(make(ExceptionTypes.OS_ERROR, PyInt.of(9999), PyStr.of("m")).getType())
                .isSameAs(ExceptionTypes.OS_ERROR);
        assertThat(
                        make(
                                        ExceptionTypes.OS_ERROR,
                                        PyInt.of(new BigInteger("12345678901234567890")),
                                        PyStr.of("m"))
                                .getType())
                .isSameAs(ExceptionTypes.OS_ERROR);
        assertThat(
                        make(
                                        ExceptionTypes.FILE_NOT_FOUND_ERROR,
                                        PyInt.of(17),
                                        PyStr.of("m"))
                                .getType())
                .isSameAs(ExceptionTypes.FILE_NOT_FOUND_ERROR);
        assertThat(make(filed("Narrow"), PyInt.of(2), PyStr.of("m")).getType().name())
                .isEqualTo("Narrow");
    }

    /**
     * {@code oserror_parse_args} reads a signature of two to five arguments and no other length.
     *
     * <p>A one-argument call is not the signature, so its argument is an argument and not an error number;
     * a sixth is the same refusal in the other direction, and the whole run stays in {@code args}. A third
     * argument that is there — and {@code 0} and {@code ""} are there — is filed as a file name and cut out
     * of the arguments, while {@code None} keeps the arguments as they were and files nothing.
     */
    @Test
    void onlyACallOfTwoToFiveArgumentsIsReadAsThisSignature() {
        PyObject eleven = PyInt.of(11);
        PyObject text = PyStr.of("a");

        assertThat(shape(make(ExceptionTypes.OS_ERROR)))
                .isEqualTo("OSError() | () | None | None | None | None | "
                        + "AttributeError: characters_written | ");
        assertThat(shape(make(ExceptionTypes.OS_ERROR, eleven)))
                .isEqualTo("OSError(11) | (11,) | None | None | None | None | "
                        + "AttributeError: characters_written | 11");
        assertThat(shape(make(ExceptionTypes.OS_ERROR, eleven, text)))
                .isEqualTo("OSError(11, 'a') | (11, 'a') | 11 | 'a' | None | None | "
                        + "AttributeError: characters_written | [Errno 11] a");
        assertThat(shape(make(ExceptionTypes.OS_ERROR, eleven, text, PyStr.of("f"))))
                .isEqualTo("OSError(11, 'a') | (11, 'a') | 11 | 'a' | 'f' | None | "
                        + "AttributeError: characters_written | [Errno 11] a: 'f'");
        assertThat(
                        shape(
                                make(
                                        ExceptionTypes.OS_ERROR,
                                        eleven,
                                        text,
                                        PyStr.of("f"),
                                        PyStr.of("w"),
                                        PyStr.of("g"),
                                        PyStr.of("h"))))
                .isEqualTo("OSError(11, 'a', 'f', 'w', 'g', 'h') | "
                        + "(11, 'a', 'f', 'w', 'g', 'h') | None | None | None | None | "
                        + "AttributeError: characters_written | (11, 'a', 'f', 'w', 'g', 'h')");
        assertThat(shape(make(ExceptionTypes.OS_ERROR, eleven, text, PyNone.INSTANCE)))
                .isEqualTo("OSError(11, 'a', None) | (11, 'a', None) | 11 | 'a' | None | None | "
                        + "AttributeError: characters_written | [Errno 11] a");
        assertThat(shape(make(ExceptionTypes.OS_ERROR, eleven, text, PyInt.ZERO, PyNone.INSTANCE)))
                .isEqualTo("OSError(11, 'a') | (11, 'a') | 11 | 'a' | 0 | None | "
                        + "AttributeError: characters_written | [Errno 11] a: 0");
        assertThat(
                        shape(
                                make(
                                        ExceptionTypes.OS_ERROR,
                                        eleven,
                                        text,
                                        PyStr.of("f"),
                                        PyNone.INSTANCE,
                                        PyStr.of("g"))))
                .isEqualTo("OSError(11, 'a') | (11, 'a') | 11 | 'a' | 'f' | 'g' | "
                        + "AttributeError: characters_written | [Errno 11] a: 'f' -> 'g'");
    }

    /**
     * {@code BlockingIOError} reads its third argument as a count when it is a number.
     *
     * <p>Then it cuts nothing: the arguments stay the three the call gave, and no file is filed, which is
     * what makes {@code .filename} {@code None} beside an {@code args} of its own. A number is the four
     * builtins that answer {@code PyNumber_Check} here, and a float among them is refused by name rather
     * than truncated — {@code 3.5} never becomes a count.
     */
    @Test
    void blockingIoErrorCountsItsThirdArgumentInsteadOfFilingIt() {
        assertThat(
                        shape(
                                make(
                                        ExceptionTypes.BLOCKING_IO_ERROR,
                                        PyInt.of(11),
                                        PyStr.of("m"),
                                        PyInt.of(3))))
                .isEqualTo("BlockingIOError(11, 'm', 3) | (11, 'm', 3) | 11 | 'm' | None | None | 3 | "
                        + "[Errno 11] m");
        assertThat(
                        shape(
                                make(
                                        ExceptionTypes.BLOCKING_IO_ERROR,
                                        PyInt.of(11),
                                        PyStr.of("m"),
                                        PyBool.TRUE)))
                .isEqualTo("BlockingIOError(11, 'm', True) | (11, 'm', True) | 11 | 'm' | None | None | 1 | "
                        + "[Errno 11] m");
        assertThat(
                        shape(
                                make(
                                        ExceptionTypes.BLOCKING_IO_ERROR,
                                        PyInt.of(11),
                                        PyStr.of("m"),
                                        PyNone.INSTANCE)))
                .isEqualTo("BlockingIOError(11, 'm', None) | (11, 'm', None) | 11 | 'm' | None | None | "
                        + "AttributeError: characters_written | [Errno 11] m");
        assertThat(
                        shape(
                                make(
                                        ExceptionTypes.BLOCKING_IO_ERROR,
                                        PyInt.of(11),
                                        PyStr.of("m"),
                                        PyInt.of(3),
                                        PyStr.of("f"))))
                .isEqualTo("BlockingIOError(11, 'm', 3, 'f') | (11, 'm', 3, 'f') | 11 | 'm' | None | None | 3 | "
                        + "[Errno 11] m");
        assertThat(
                        shape(
                                make(
                                        ExceptionTypes.BLOCKING_IO_ERROR,
                                        PyInt.of(11),
                                        PyStr.of("m"),
                                        PyStr.of("f"),
                                        PyStr.of("w"))))
                .isEqualTo("BlockingIOError(11, 'm') | (11, 'm') | 11 | 'm' | 'f' | None | "
                        + "AttributeError: characters_written | [Errno 11] m: 'f'");
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () ->
                        make(
                                ExceptionTypes.BLOCKING_IO_ERROR,
                                PyInt.of(11),
                                PyStr.of("m"),
                                PyFloat.of(3.5)));
    }

    /**
     * A field that holds {@code None} and a field that was taken away read the same and display
     * differently.
     *
     * <p>The four {@code _Py_T_OBJECT} members answer {@code OR_NONE} for a null field, so {@code
     * e.filename2 = None} and {@code del e.filename2} both read {@code None} — but {@code OSError_str}
     * tests the field for being there, so only the assignment puts {@code -> None} into the text. This is
     * the one place where deleting and writing the object the language calls nothing are two different
     * values.
     */
    @Test
    void aFieldHoldingNoneAndAFieldTakenAwayReadAlikeAndDisplayDifferently() {
        PyObject written = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"), PyStr.of("f"),
                PyStr.of("w"), PyStr.of("g"));
        Slots.setAttr(written, "filename2", PyNone.INSTANCE);
        assertThat(answer(written, "filename2")).isEqualTo("None");
        assertThat(written.str()).isEqualTo("[Errno 11] a: 'f' -> None");

        PyObject deleted = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"), PyStr.of("f"),
                PyStr.of("w"), PyStr.of("g"));
        Slots.delAttr(deleted, "filename2");
        assertThat(answer(deleted, "filename2")).isEqualTo("None");
        assertThat(deleted.str()).isEqualTo("[Errno 11] a: 'f'");
        Slots.delAttr(deleted, "filename2");
        assertThat(answer(deleted, "filename2")).isEqualTo("None");

        // A number deleted and a number set to None read alike, and only the second still has an Errno in
        // a two-argument text: OSError_str asks the pair for being there before it prints them OR_NONE,
        // and the branch that a file name takes over asks no such question.
        PyObject gone = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"), PyStr.of("f"),
                PyStr.of("w"), PyStr.of("g"));
        Slots.delAttr(gone, "errno");
        assertThat(answer(gone, "errno")).isEqualTo("None");
        assertThat(gone.str()).isEqualTo("[Errno None] a: 'f' -> 'g'");
        PyObject plain = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"));
        Slots.delAttr(plain, "errno");
        assertThat(plain.str()).isEqualTo("(11, 'a')");
        PyObject cleared = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"));
        Slots.setAttr(cleared, "errno", PyNone.INSTANCE);
        assertThat(answer(cleared, "errno")).isEqualTo("None");
        assertThat(cleared.str()).isEqualTo("[Errno None] a");
        PyObject text = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"));
        Slots.delAttr(text, "strerror");
        assertThat(text.str()).isEqualTo("(11, 'a')");
        Slots.setAttr(text, "strerror", PyNone.INSTANCE);
        assertThat(text.str()).isEqualTo("[Errno 11] None");
    }

    /**
     * The getset has no answer for a field that was never filled, and reads a count through {@code
     * PyNumber_AsSsize_t}.
     *
     * <p>{@code -1} in the C word means nothing was written, so {@code .characters_written} refuses by
     * name — the only bare attribute message among the five fields, and the only one with no object named.
     * The overflow is the difference between this and {@code to_bytes}: {@code PyNumber_AsSsize_t} was
     * given {@code ValueError} as its second argument, so too large a count is a bad value rather than an
     * unrepresentable one.
     */
    @Test
    void theCountRefusesByItsNameUntilSomethingFillsIt() {
        PyObject value = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"));
        assertThat(answer(value, "characters_written")).isEqualTo("AttributeError: characters_written");

        Slots.setAttr(value, "characters_written", PyInt.of(3));
        assertThat(answer(value, "characters_written")).isEqualTo("3");

        refuses(
                "ValueError: cannot fit 'int' into an index-sized integer",
                () ->
                        Slots.setAttr(
                                value,
                                "characters_written",
                                PyInt.of(BigInteger.ONE.shiftLeft(70))));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> Slots.setAttr(value, "characters_written", PyStr.of("three")));

        // Writing None is the object, not the null pointer a delete gives, and so is converted.
        PyObject again = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"));
        refuses(
                "TypeError: 'NoneType' object cannot be interpreted as an integer",
                () -> Slots.setAttr(again, "characters_written", PyNone.INSTANCE));
    }

    /**
     * The five fields are reached through the lookup order, so whatever a class files nearer owns all of
     * the three questions.
     *
     * <p>{@code _PyType_Lookup} walks the order once and only asks what it lands on: a plain number filed
     * by a subclass shadows the member for the read, takes the write into the instance dictionary and
     * leaves the C field holding the number the call gave — so {@code str} still says {@code [Errno 1]}. A
     * property found nearer owns the name too, and its two refusals are the property's own words, not the
     * field's.
     */
    @Test
    void aClassThatFilesOneOfTheFiveNamesOwnsTheReadTheWriteAndTheDelete() {
        PyType kind = filed("Filed", "errno", PyInt.of(42));
        PyObject value = make(kind, PyInt.of(1), PyStr.of("m"));

        assertThat(value.getAttr("errno")).isEqualTo(PyInt.of(42));
        assertThat(value.str()).isEqualTo("[Errno 1] m");
        assertThat(kind.getAttr("errno")).isEqualTo(PyInt.of(42));

        // The write lands in the dictionary, and the field behind the name keeps the number it was given.
        Slots.setAttr(value, "errno", PyInt.of(5));
        assertThat(value.getAttr("errno")).isEqualTo(PyInt.of(5));
        assertThat(value.getAttr("__dict__").repr()).isEqualTo("{'errno': 5}");
        assertThat(value.str()).isEqualTo("[Errno 1] m");
        assertThat(kind.getAttr("errno")).isEqualTo(PyInt.of(42));

        // The delete takes the dictionary's name away, leaves the class value under it, and has
        // nothing left to take the second time.
        Slots.delAttr(value, "errno");
        assertThat(value.getAttr("errno")).isEqualTo(PyInt.of(42));
        refuses(
                "AttributeError: 'Filed' object has no attribute 'errno'",
                () -> Slots.delAttr(value, "errno"));

        PyType guarded = filed("Guarded", "errno", property(getter("read", PyStr.of("prop"))));
        PyObject kept = make(guarded, PyInt.of(1), PyStr.of("m"));
        assertThat(kept.getAttr("errno")).isEqualTo(PyStr.of("prop"));
        assertThat(kept.str()).isEqualTo("[Errno 1] m");
        refuses(
                "AttributeError: property 'errno' of 'Guarded' object has no setter",
                () -> Slots.setAttr(kept, "errno", PyInt.ONE));
        refuses(
                "AttributeError: property 'errno' of 'Guarded' object has no deleter",
                () -> Slots.delAttr(kept, "errno"));

        // A subclass two deep inherits the shadow rather than the field.
        PyType deeper =
                (PyType)
                        PyType.TYPE.call(
                                new PyObject[] {
                                    PyStr.of("Deeper"), PyTuple.of(kind), PyDict.empty()
                                },
                                null);
        assertThat(make(deeper, PyInt.of(1), PyStr.of("m")).getAttr("errno")).isEqualTo(PyInt.of(42));
    }

    /**
     * The seven names a value will not let go of, each refused before the instance dictionary is read.
     *
     * <p>{@code args} and the three chain fields are getsets whose deleting half is CPython's {@code
     * ex_del}, {@code __dict__} is a member and {@code __suppress_context__} a C {@code char}, neither of
     * which can be taken away, and {@code __class__} belongs to the family that keeps a value's type. Each
     * says so in its own words, and every one of them is a {@code TypeError} where the plain refusal for an
     * absent name is an {@code AttributeError} — which is why a delete of one of them is not the same
     * sentence as a delete of the field next to it.
     */
    @Test
    void sevenNamesRefuseBeforeTheInstanceDictionaryIsRead() {
        PyObject value = make(ExceptionTypes.OS_ERROR, PyInt.of(11), PyStr.of("a"), PyStr.of("f"));
        for (String name :
                List.of(
                        "args",
                        "__dict__",
                        "__class__",
                        "__traceback__",
                        "__cause__",
                        "__context__",
                        "__suppress_context__")) {
            refuses(
                    switch (name) {
                        case "args" -> "TypeError: args may not be deleted";
                        case "__dict__" -> "TypeError: cannot delete __dict__";
                        case "__class__" -> "TypeError: can't delete __class__ attribute";
                        case "__suppress_context__" ->
                                "TypeError: can't delete numeric/char attribute";
                        default -> "TypeError: " + name + " may not be deleted";
                    },
                    () -> Slots.delAttr(value, name));
        }

        // A member takes its value away and says nothing when there was nothing left to take.
        Slots.delAttr(value, "filename");
        assertThat(value.getAttr("filename")).isSameAs(PyNone.INSTANCE);
        assertThat(value.str()).isEqualTo("[Errno 11] a");
        Slots.delAttr(value, "filename");
        refuses(
                "AttributeError: 'OSError' object has no attribute 'nope'",
                () -> Slots.delAttr(value, "nope"));
    }
}
