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
    public static final PyType RUNTIME_ERROR = child("RuntimeError", EXCEPTION);
    public static final PyType RECURSION_ERROR = child("RecursionError", RUNTIME_ERROR);
    public static final PyType NOT_IMPLEMENTED_ERROR = child("NotImplementedError", RUNTIME_ERROR);
    public static final PyType IMPORT_ERROR = child("ImportError", EXCEPTION);
    public static final PyType MODULE_NOT_FOUND_ERROR = child("ModuleNotFoundError", IMPORT_ERROR);
    public static final PyType SYNTAX_ERROR = child("SyntaxError", EXCEPTION);
    public static final PyType MEMORY_ERROR = child("MemoryError", EXCEPTION);
    public static final PyType ASSERTION_ERROR = child("AssertionError", EXCEPTION);
    public static final PyType SYSTEM_ERROR = child("SystemError", EXCEPTION);
    public static final PyType OS_ERROR = child("OSError", EXCEPTION);
    public static final PyType FILE_NOT_FOUND_ERROR = child("FileNotFoundError", OS_ERROR);
    public static final PyType SYSTEM_EXIT = child("SystemExit", BASE_EXCEPTION);
    public static final PyType KEYBOARD_INTERRUPT = child("KeyboardInterrupt", BASE_EXCEPTION);

    private static PyType child(String name, PyType parent) {
        return PyType.declare(name, parent);
    }

    private ExceptionTypes() {
    }
}
