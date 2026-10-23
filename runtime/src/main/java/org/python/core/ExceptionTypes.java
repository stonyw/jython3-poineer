package org.python.core;

/**
 * The exception classes this runtime raises, as Python-visible objects.
 *
 * They are ordinary {@link PyType}s with one parent each, because {@code except} matching is a class
 * test and {@code raise ValueError(...)} is a call: both need a real hierarchy rather than a Java
 * class per exception. Multiple inheritance, subclass {@code __init__} and {@code __module__} arrive
 * with the class system at M3.
 */
public final class ExceptionTypes {

    public static final PyType BASE_EXCEPTION = PyType.declare("BaseException", PyType.OBJECT);
    public static final PyType EXCEPTION = child("Exception", BASE_EXCEPTION);
    public static final PyType ARITHMETIC_ERROR = child("ArithmeticError", EXCEPTION);
    public static final PyType LOOKUP_ERROR = child("LookupError", EXCEPTION);
    public static final PyType OVERFLOW_ERROR = child("OverflowError", ARITHMETIC_ERROR);
    public static final PyType ZERO_DIVISION_ERROR = child("ZeroDivisionError", ARITHMETIC_ERROR);

    /** {@code FloatingPointError}, the one arithmetic leaf no arithmetic here raises: it needs a FPU. */
    public static final PyType FLOATING_POINT_ERROR = child("FloatingPointError", ARITHMETIC_ERROR);
    public static final PyType VALUE_ERROR = child("ValueError", EXCEPTION);
    public static final PyType UNICODE_ERROR = child("UnicodeError", VALUE_ERROR);
    public static final PyType UNICODE_ENCODE_ERROR = child("UnicodeEncodeError", UNICODE_ERROR);
    public static final PyType UNICODE_DECODE_ERROR = child("UnicodeDecodeError", UNICODE_ERROR);
    public static final PyType UNICODE_TRANSLATE_ERROR =
            child("UnicodeTranslateError", UNICODE_ERROR);
    public static final PyType TYPE_ERROR = child("TypeError", EXCEPTION);
    public static final PyType NAME_ERROR = child("NameError", EXCEPTION);
    public static final PyType UNBOUND_LOCAL_ERROR = child("UnboundLocalError", NAME_ERROR);
    public static final PyType ATTRIBUTE_ERROR = child("AttributeError", EXCEPTION);
    public static final PyType INDEX_ERROR = child("IndexError", LOOKUP_ERROR);
    public static final PyType KEY_ERROR = child("KeyError", LOOKUP_ERROR);
    public static final PyType STOP_ITERATION = child("StopIteration", EXCEPTION);

    /**
     * {@code StopAsyncIteration}: what {@code anext()} and {@code async for} are left holding, which is
     * why it is a sibling of {@code StopIteration} and not a subclass — one loop's end must not read as
     * the other's.
     */
    public static final PyType STOP_ASYNC_ITERATION = child("StopAsyncIteration", EXCEPTION);
    public static final PyType RUNTIME_ERROR = child("RuntimeError", EXCEPTION);
    public static final PyType RECURSION_ERROR = child("RecursionError", RUNTIME_ERROR);
    public static final PyType NOT_IMPLEMENTED_ERROR = child("NotImplementedError", RUNTIME_ERROR);

    /** {@code PythonFinalizationError}: the one {@code RuntimeError} an interpreter at exit answers with. */
    public static final PyType PYTHON_FINALIZATION_ERROR =
            child("PythonFinalizationError", RUNTIME_ERROR);

    /** {@code ReferenceError}, which {@code weakref} raises for a dead proxy and nothing else does. */
    public static final PyType REFERENCE_ERROR = child("ReferenceError", EXCEPTION);

    public static final PyType IMPORT_ERROR = child("ImportError", EXCEPTION);
    public static final PyType MODULE_NOT_FOUND_ERROR = child("ModuleNotFoundError", IMPORT_ERROR);
    public static final PyType SYNTAX_ERROR = child("SyntaxError", EXCEPTION);

    /**
     * The two classes that say <em>where</em> the text was wrong rather than that it is, and the private
     * one the incremental parser answers an unfinished statement with. All three keep {@code
     * SyntaxError}'s arguments and therefore its display, which is why they are declared beside it.
     */
    public static final PyType INDENTATION_ERROR = child("IndentationError", SYNTAX_ERROR);

    public static final PyType TAB_ERROR = child("TabError", INDENTATION_ERROR);
    public static final PyType INCOMPLETE_INPUT_ERROR = child("_IncompleteInputError", SYNTAX_ERROR);
    public static final PyType MEMORY_ERROR = child("MemoryError", EXCEPTION);
    public static final PyType ASSERTION_ERROR = child("AssertionError", EXCEPTION);

    /** {@code EOFError}: input ended where more was expected, which {@code input()} answers with. */
    public static final PyType EOF_ERROR = child("EOFError", EXCEPTION);

    /** {@code BufferError}: an export a buffer still holds cannot be released. */
    public static final PyType BUFFER_ERROR = child("BufferError", EXCEPTION);
    public static final PyType SYSTEM_ERROR = child("SystemError", EXCEPTION);
    /**
     * {@code OSError} and the fifteen classes under it.
     *
     * <p>The base is the one exception whose value carries fields of its own — see {@link PyOSError} — and
     * the leaves carry nothing but their place in the tree, which is what makes the mapping of an error
     * number onto one of them a table on the base rather than a rule each leaf knows. {@code
     * ConnectionError} is the only intermediate: four of the leaves are under it, and {@code TimeoutError}
     * is not, since 3.10 folded the socket's own timeout into the {@code OSError} family.
     */
    public static final PyType OS_ERROR = child("OSError", EXCEPTION);

    public static final PyType BLOCKING_IO_ERROR = child("BlockingIOError", OS_ERROR);
    public static final PyType CHILD_PROCESS_ERROR = child("ChildProcessError", OS_ERROR);
    public static final PyType CONNECTION_ERROR = child("ConnectionError", OS_ERROR);
    public static final PyType FILE_EXISTS_ERROR = child("FileExistsError", OS_ERROR);
    public static final PyType FILE_NOT_FOUND_ERROR = child("FileNotFoundError", OS_ERROR);
    public static final PyType INTERRUPTED_ERROR = child("InterruptedError", OS_ERROR);
    public static final PyType IS_A_DIRECTORY_ERROR = child("IsADirectoryError", OS_ERROR);
    public static final PyType NOT_A_DIRECTORY_ERROR = child("NotADirectoryError", OS_ERROR);
    public static final PyType PERMISSION_ERROR = child("PermissionError", OS_ERROR);
    public static final PyType PROCESS_LOOKUP_ERROR = child("ProcessLookupError", OS_ERROR);
    public static final PyType TIMEOUT_ERROR = child("TimeoutError", OS_ERROR);

    public static final PyType BROKEN_PIPE_ERROR = child("BrokenPipeError", CONNECTION_ERROR);
    public static final PyType CONNECTION_ABORTED_ERROR =
            child("ConnectionAbortedError", CONNECTION_ERROR);
    public static final PyType CONNECTION_REFUSED_ERROR =
            child("ConnectionRefusedError", CONNECTION_ERROR);
    public static final PyType CONNECTION_RESET_ERROR =
            child("ConnectionResetError", CONNECTION_ERROR);

    /**
     * {@code Warning} and the eleven categories {@code warnings.warn} sorts a message by.
     *
     * <p>In 3.14 these are ordinary classes: the keyword form the categories used to take
     * ({@code Warning(message=…, category=…, stacklevel=…)}) is gone, so a category is asked for its
     * arguments like any other exception and the reading of them belongs to the warnings machinery, not to
     * the class. {@code ResourceWarning} and {@code EncodingWarning} are the two youngest leaves and are
     * siblings of {@code BytesWarning} rather than of each other, which is only visible in the tree.
     */
    public static final PyType WARNING = child("Warning", EXCEPTION);

    public static final PyType BYTES_WARNING = child("BytesWarning", WARNING);
    public static final PyType DEPRECATION_WARNING = child("DeprecationWarning", WARNING);
    public static final PyType ENCODING_WARNING = child("EncodingWarning", WARNING);
    public static final PyType FUTURE_WARNING = child("FutureWarning", WARNING);
    public static final PyType IMPORT_WARNING = child("ImportWarning", WARNING);
    public static final PyType PENDING_DEPRECATION_WARNING =
            child("PendingDeprecationWarning", WARNING);
    public static final PyType RESOURCE_WARNING = child("ResourceWarning", WARNING);
    public static final PyType RUNTIME_WARNING = child("RuntimeWarning", WARNING);
    public static final PyType SYNTAX_WARNING = child("SyntaxWarning", WARNING);
    public static final PyType UNICODE_WARNING = child("UnicodeWarning", WARNING);
    public static final PyType USER_WARNING = child("UserWarning", WARNING);
    public static final PyType SYSTEM_EXIT = child("SystemExit", BASE_EXCEPTION);
    public static final PyType KEYBOARD_INTERRUPT = child("KeyboardInterrupt", BASE_EXCEPTION);
    /**
     * What {@code gen.close()} throws into the frame, and the one exception a generator is not allowed
     * to answer with: an error that reaches the caller from a {@code close} is either this, which the
     * call swallows, or whatever the body raised while handling it, which escapes.
     */
    public static final PyType GENERATOR_EXIT = child("GeneratorExit", BASE_EXCEPTION);
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
