package org.python.core;

/**
 * The exception classes this runtime raises, as Python-visible objects.
 *
 * They are ordinary {@link PyType}s with one parent each, because {@code except} matching is a class
 * test and {@code raise ValueError(...)} is a call: both need a real hierarchy rather than a Java class
 * per exception. Multiple inheritance, subclass {@code __init__} and {@code __module__} arrive with the
 * class system at M3.
 *
 * <p>Every sentence this file declares is the {@code tp_doc} the exception's own C file writes down,
 * copied whole. None of these types has an argument table, so the two knives read one text and disagree
 * about nothing: {@code __text_signature__} answers {@code None} and {@code __doc__} answers what is
 * written here, and the dictionary row is the same string a third time. The two classes left without a
 * {@code documented} call are {@code ExceptionGroup} and {@code UnsupportedOperation}, whose {@code
 * tp_doc} CPython leaves {@code NULL} — the only exception types here that answer {@code None}.
 */
public final class ExceptionTypes {

    public static final PyType BASE_EXCEPTION =
            PyType.declare("BaseException", PyType.OBJECT)
                    .documented("Common base class for all exceptions");

    public static final PyType EXCEPTION =
            child("Exception", BASE_EXCEPTION)
                    .documented("Common base class for all non-exit exceptions.");

    public static final PyType ARITHMETIC_ERROR =
            child("ArithmeticError", EXCEPTION).documented("Base class for arithmetic errors.");

    public static final PyType LOOKUP_ERROR =
            child("LookupError", EXCEPTION).documented("Base class for lookup errors.");

    public static final PyType OVERFLOW_ERROR =
            child("OverflowError", ARITHMETIC_ERROR)
                    .documented("Result too large to be represented.");

    public static final PyType ZERO_DIVISION_ERROR =
            child("ZeroDivisionError", ARITHMETIC_ERROR)
                    .documented("Second argument to a division or modulo operation was zero.");

    /** {@code FloatingPointError}, the one arithmetic leaf no arithmetic here raises: it needs a FPU. */
    public static final PyType FLOATING_POINT_ERROR =
            child("FloatingPointError", ARITHMETIC_ERROR)
                    .documented("Floating-point operation failed.");

    public static final PyType VALUE_ERROR =
            child("ValueError", EXCEPTION)
                    .documented("Inappropriate argument value (of correct type).");

    // The four classes the three Unicode leaves and the three indentation errors are made of: the base
    // declares nothing but its sentence, so its dictionary is a `__new__` and a `__doc__`, and each leaf
    // files the five offsets its `PyMemberDef` table names beside its own initialiser and display.
    public static final PyType UNICODE_ERROR =
            child("UnicodeError", VALUE_ERROR).documented("Unicode related error.");

    public static final PyType UNICODE_ENCODE_ERROR =
            child("UnicodeEncodeError", UNICODE_ERROR).documented("Unicode encoding error.");

    public static final PyType UNICODE_DECODE_ERROR =
            child("UnicodeDecodeError", UNICODE_ERROR).documented("Unicode decoding error.");

    public static final PyType UNICODE_TRANSLATE_ERROR =
            child("UnicodeTranslateError", UNICODE_ERROR).documented("Unicode translation error.");

    public static final PyType TYPE_ERROR =
            child("TypeError", EXCEPTION).documented("Inappropriate argument type.");

    // The sentences the five dictionaries below this one file as their own `__doc__` row, and the two the
    // classes under them file for themselves while inheriting every other row. They are the only part of
    // `tp_doc` that reaches a dictionary: a class with nothing to say files a row that reads `None`.
    public static final PyType NAME_ERROR =
            child("NameError", EXCEPTION).documented("Name not found globally.");

    public static final PyType UNBOUND_LOCAL_ERROR =
            child("UnboundLocalError", NAME_ERROR)
                    .documented("Local name referenced but not bound to a value.");

    public static final PyType ATTRIBUTE_ERROR =
            child("AttributeError", EXCEPTION).documented("Attribute not found.");

    public static final PyType INDEX_ERROR =
            child("IndexError", LOOKUP_ERROR).documented("Sequence index out of range.");

    public static final PyType KEY_ERROR =
            child("KeyError", LOOKUP_ERROR).documented("Mapping key not found.");

    public static final PyType STOP_ITERATION =
            child("StopIteration", EXCEPTION).documented("Signal the end from iterator.__next__().");

    /**
     * {@code StopAsyncIteration}: what {@code anext()} and {@code async for} are left holding, which is
     * why it is a sibling of {@code StopIteration} and not a subclass — one loop's end must not read as
     * the other's. Its sentence names the other dunder, which is the only place in this file where the
     * name a class answers and the name its sentence holds differ.
     */
    public static final PyType STOP_ASYNC_ITERATION =
            child("StopAsyncIteration", EXCEPTION)
                    .documented("Signal the end from iterator.__anext__().");

    public static final PyType RUNTIME_ERROR =
            child("RuntimeError", EXCEPTION).documented("Unspecified run-time error.");

    public static final PyType RECURSION_ERROR =
            child("RecursionError", RUNTIME_ERROR).documented("Recursion limit exceeded.");

    public static final PyType NOT_IMPLEMENTED_ERROR =
            child("NotImplementedError", RUNTIME_ERROR)
                    .documented("Method or function hasn't been implemented yet.");

    /** {@code PythonFinalizationError}: the one {@code RuntimeError} an interpreter at exit answers with. */
    public static final PyType PYTHON_FINALIZATION_ERROR =
            child("PythonFinalizationError", RUNTIME_ERROR)
                    .documented("Operation blocked during Python finalization.");

    /** {@code ReferenceError}, which {@code weakref} raises for a dead proxy and nothing else does. */
    public static final PyType REFERENCE_ERROR =
            child("ReferenceError", EXCEPTION)
                    .documented("Weak ref proxy used after referent went away.");

    public static final PyType IMPORT_ERROR =
            child("ImportError", EXCEPTION)
                    .documented("Import can't find module, or can't find name in module.");

    public static final PyType MODULE_NOT_FOUND_ERROR =
            child("ModuleNotFoundError", IMPORT_ERROR).documented("Module not found.");

    // The nine offsets {@code SyntaxError_members} files, and the display that reads two of them. The class
    // is the only one of the family whose {@code tp_new} is left to {@code BaseException} — it declares a
    // {@code tp_init} and so files no {@code __new__} row of its own.
    public static final PyType SYNTAX_ERROR =
            child("SyntaxError", EXCEPTION).documented("Invalid syntax.");

    /**
     * The two classes that say <em>where</em> the text was wrong rather than that it is, and the private
     * one the incremental parser answers an unfinished statement with. All three keep {@code
     * SyntaxError}'s arguments, its initialiser and therefore its display, which is why they are declared
     * beside it and file nothing themselves but the sentence.
     */
    public static final PyType INDENTATION_ERROR =
            child("IndentationError", SYNTAX_ERROR).documented("Improper indentation.");

    public static final PyType TAB_ERROR =
            child("TabError", INDENTATION_ERROR)
                    .documented("Improper mixture of spaces and tabs.");

    public static final PyType INCOMPLETE_INPUT_ERROR =
            child("_IncompleteInputError", SYNTAX_ERROR).documented("incomplete input.");

    public static final PyType MEMORY_ERROR =
            child("MemoryError", EXCEPTION).documented("Out of memory.");

    public static final PyType ASSERTION_ERROR =
            child("AssertionError", EXCEPTION).documented("Assertion failed.");

    /** {@code EOFError}: input ended where more was expected, which {@code input()} answers with. */
    public static final PyType EOF_ERROR =
            child("EOFError", EXCEPTION).documented("Read beyond end of file.");

    /** {@code BufferError}: an export a buffer still holds cannot be released. */
    public static final PyType BUFFER_ERROR =
            child("BufferError", EXCEPTION).documented("Buffer error.");

    /**
     * {@code SystemError}, the one sentence in this file that breaks its own paragraph. The blank line in
     * the middle is what {@code skip_signature} would read as the end of a signature that never started:
     * {@code find_signature} wants the name and a {@code (} at the front, and this text opens with its
     * sentence, so the cut gives up and all four lines are the answer.
     */
    public static final PyType SYSTEM_ERROR =
            child("SystemError", EXCEPTION)
                    .documented(
                            "Internal error in the Python interpreter.\n"
                                    + "\n"
                                    + "Please report this to the Python maintainer, along with the traceback,\n"
                                    + "the Python version, and the hardware/OS platform and version.");

    /**
     * {@code OSError} and the fifteen classes under it.
     *
     * <p>The base is the one exception whose value carries fields of its own — see {@link PyOSError} — and
     * the leaves carry nothing but their place in the tree, which is what makes the mapping of an error
     * number onto one of them a table on the base rather than a rule each leaf knows. {@code
     * ConnectionError} is the only intermediate: four of the leaves are under it, and {@code TimeoutError}
     * is not, since 3.10 folded the socket's own timeout into the {@code OSError} family.
     */
    public static final PyType OS_ERROR =
            child("OSError", EXCEPTION).documented("Base class for I/O related errors.");

    public static final PyType BLOCKING_IO_ERROR =
            child("BlockingIOError", OS_ERROR).documented("I/O operation would block.");

    public static final PyType CHILD_PROCESS_ERROR =
            child("ChildProcessError", OS_ERROR).documented("Child process error.");

    public static final PyType CONNECTION_ERROR =
            child("ConnectionError", OS_ERROR).documented("Connection error.");

    public static final PyType FILE_EXISTS_ERROR =
            child("FileExistsError", OS_ERROR).documented("File already exists.");

    public static final PyType FILE_NOT_FOUND_ERROR =
            child("FileNotFoundError", OS_ERROR).documented("File not found.");

    public static final PyType INTERRUPTED_ERROR =
            child("InterruptedError", OS_ERROR).documented("Interrupted by signal.");

    public static final PyType IS_A_DIRECTORY_ERROR =
            child("IsADirectoryError", OS_ERROR)
                    .documented("Operation doesn't work on directories.");

    public static final PyType NOT_A_DIRECTORY_ERROR =
            child("NotADirectoryError", OS_ERROR)
                    .documented("Operation only works on directories.");

    public static final PyType PERMISSION_ERROR =
            child("PermissionError", OS_ERROR).documented("Not enough permissions.");

    public static final PyType PROCESS_LOOKUP_ERROR =
            child("ProcessLookupError", OS_ERROR).documented("Process not found.");

    public static final PyType TIMEOUT_ERROR =
            child("TimeoutError", OS_ERROR).documented("Timeout expired.");

    public static final PyType BROKEN_PIPE_ERROR =
            child("BrokenPipeError", CONNECTION_ERROR).documented("Broken pipe.");

    public static final PyType CONNECTION_ABORTED_ERROR =
            child("ConnectionAbortedError", CONNECTION_ERROR).documented("Connection aborted.");

    public static final PyType CONNECTION_REFUSED_ERROR =
            child("ConnectionRefusedError", CONNECTION_ERROR).documented("Connection refused.");

    public static final PyType CONNECTION_RESET_ERROR =
            child("ConnectionResetError", CONNECTION_ERROR).documented("Connection reset.");

    /**
     * What a stream says when the question is legitimate but this stream cannot answer it.
     *
     * <p>An exception type CPython files outside {@code builtins}: under {@code io}, its {@code tp_doc} is
     * a bare {@code NULL} — so {@code UnsupportedOperation.__doc__} answers {@code None} — and it inherits
     * from both {@code OSError} and {@code ValueError} so {@code except} clauses written against either
     * catch it. A sentence raised this way prints with no errno prefix, the same as any {@code OSError}
     * built from one argument.
     */
    public static final PyType UNSUPPORTED_OPERATION =
            child("UnsupportedOperation", OS_ERROR, VALUE_ERROR).filedUnder("io.UnsupportedOperation");

    /**
     * {@code Warning} and the eleven categories {@code warnings.warn} sorts a message by.
     *
     * <p>In 3.14 these are ordinary classes: the keyword form the categories used to take
     * ({@code Warning(message=…, category=…, stacklevel=…)}) is gone, so a category is asked for its
     * arguments like any other exception and the reading of them belongs to the warnings machinery, not to
     * the class. {@code ResourceWarning} and {@code EncodingWarning} are the two youngest leaves and are
     * siblings of {@code BytesWarning} rather than of each other, which is only visible in the tree.
     *
     * <p>Four of the twelve sentences break their line where the source string breaks it and a fifth —
     * {@code ImportWarning}'s — ends without the full stop the other eleven have. Both are what the read
     * answers, so both are copied.
     */
    public static final PyType WARNING =
            child("Warning", EXCEPTION).documented("Base class for warning categories.");

    public static final PyType BYTES_WARNING =
            child("BytesWarning", WARNING)
                    .documented(
                            "Base class for warnings about bytes and buffer related problems, mostly\n"
                                    + "related to conversion from str or comparing to str.");

    public static final PyType DEPRECATION_WARNING =
            child("DeprecationWarning", WARNING)
                    .documented("Base class for warnings about deprecated features.");

    public static final PyType ENCODING_WARNING =
            child("EncodingWarning", WARNING)
                    .documented("Base class for warnings about encodings.");

    public static final PyType FUTURE_WARNING =
            child("FutureWarning", WARNING)
                    .documented(
                            "Base class for warnings about constructs that will change semantically\n"
                                    + "in the future.");

    public static final PyType IMPORT_WARNING =
            child("ImportWarning", WARNING)
                    .documented("Base class for warnings about probable mistakes in module imports");

    public static final PyType PENDING_DEPRECATION_WARNING =
            child("PendingDeprecationWarning", WARNING)
                    .documented(
                            "Base class for warnings about features which will be deprecated\n"
                                    + "in the future.");

    public static final PyType RESOURCE_WARNING =
            child("ResourceWarning", WARNING)
                    .documented("Base class for warnings about resource usage.");

    public static final PyType RUNTIME_WARNING =
            child("RuntimeWarning", WARNING)
                    .documented("Base class for warnings about dubious runtime behavior.");

    public static final PyType SYNTAX_WARNING =
            child("SyntaxWarning", WARNING)
                    .documented("Base class for warnings about dubious syntax.");

    public static final PyType UNICODE_WARNING =
            child("UnicodeWarning", WARNING)
                    .documented(
                            "Base class for warnings about Unicode related problems, mostly\n"
                                    + "related to conversion problems.");

    public static final PyType USER_WARNING =
            child("UserWarning", WARNING)
                    .documented("Base class for warnings generated by user code.");

    public static final PyType SYSTEM_EXIT =
            child("SystemExit", BASE_EXCEPTION)
                    .documented("Request to exit from the interpreter.");

    public static final PyType KEYBOARD_INTERRUPT =
            child("KeyboardInterrupt", BASE_EXCEPTION)
                    .documented("Program interrupted by user.");

    /**
     * What {@code gen.close()} throws into the frame, and the one exception a generator is not allowed
     * to answer with: an error that reaches the caller from a {@code close} is either this, which the
     * call swallows, or whatever the body raised while handling it, which escapes.
     */
    public static final PyType GENERATOR_EXIT =
            child("GeneratorExit", BASE_EXCEPTION).documented("Request that a generator exit.");

    /**
     * The two classes {@code except*} works over. {@code BaseExceptionGroup} takes one base as any other
     * exception does; {@code ExceptionGroup} is the one class in this table with two, because CPython
     * creates it over {@code (BaseExceptionGroup, Exception)} — which is why {@code isinstance(eg,
     * Exception)} is true while {@code isinstance(eg, Exception)} for a group of {@code
     * KeyboardInterrupt} alone is false: the second class only ever holds what {@code Exception} allows.
     */
    public static final PyType BASE_EXCEPTION_GROUP =
            child("BaseExceptionGroup", BASE_EXCEPTION)
                    .documented("A combination of multiple unrelated exceptions.");

    public static final PyType EXCEPTION_GROUP =
            child("ExceptionGroup", BASE_EXCEPTION_GROUP, EXCEPTION);

    private static PyType child(String name, PyType... bases) {
        return PyType.declare(name, bases);
    }

    private ExceptionTypes() {
    }
}
