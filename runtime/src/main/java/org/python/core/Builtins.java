package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code builtins}: the namespace every module frame falls back to when a name is not its own.
 *
 * The entries are the ones M1's done-criterion needs — the type constructors, the exception names a
 * script can raise or catch, and the functions that arithmetic, branching, looping and calling a
 * function are written with. What is absent is deliberate and short: {@code exec}, {@code eval} and
 * {@code compile} are the code-evaluation path, {@code super} is the class system's other half,
 * {@code open} plus the rest of the file API is M4, and {@code property}, {@code classmethod} and
 * {@code staticmethod} are here as the descriptor builtins they are. A name that is not here raises
 * {@code NameError}, which points at the missing feature; a half-implemented one would point at nothing.
 *
 * Every message is as CPython 3.14 words it, including the count family a builtin belongs to —
 * {@code len() takes exactly one argument (2 given)} against {@code min expected at least 1 argument,
 * got 0} — because those strings are asserted directly by the same tests this milestone is measured
 * with.
 */
final class Builtins {

    private Builtins() {
    }

    /** Build the namespace. Called once, by {@link PyRuntime#startup()}. */
    static PyDict namespace() {
        PyDict out = PyDict.empty();
        addConstants(out);
        addTypes(out);
        addExceptions(out);
        addFunctions(out);
        return out;
    }

    private static void put(PyDict out, String name, PyObject value) {
        out.put(PyStr.interned(name), value);
    }

    private static void addConstants(PyDict out) {
        put(out, "None", PyNone.INSTANCE);
        put(out, "True", PyBool.TRUE);
        put(out, "False", PyBool.FALSE);
        put(out, "NotImplemented", PyNotImplemented.INSTANCE);
        put(out, "Ellipsis", PyEllipsis.INSTANCE);
    }

    /** The type objects, which Python reaches for by name both to construct with and to test against. */
    private static void addTypes(PyDict out) {
        put(out, "object", PyType.OBJECT);
        put(out, "type", PyType.TYPE);
        put(out, "bool", PyType.BOOL);
        put(out, "int", PyType.INT);
        put(out, "float", PyType.FLOAT);
        put(out, "complex", PyType.COMPLEX);
        put(out, "str", PyType.STR);
        put(out, "bytes", PyType.BYTES);
        put(out, "bytearray", PyType.BYTEARRAY);
        put(out, "tuple", PyType.TUPLE);
        put(out, "list", PyType.LIST);
        put(out, "dict", PyType.DICT);
        put(out, "set", PyType.SET);
        put(out, "frozenset", PyType.FROZENSET);
        put(out, "range", PyType.RANGE);
        put(out, "slice", PyType.SLICE);
        put(out, "function", PyType.FUNCTION);
        put(out, "method", PyType.METHOD);
        put(out, "module", PyType.MODULE);
        put(out, "property", PyType.PROPERTY);
        put(out, "classmethod", PyType.CLASS_METHOD);
        put(out, "staticmethod", PyType.STATIC_METHOD);
        put(out, "super", PyType.SUPER);
    }

    private static void addExceptions(PyDict out) {
        put(out, "BaseException", ExceptionTypes.BASE_EXCEPTION);
        put(out, "BaseExceptionGroup", ExceptionTypes.BASE_EXCEPTION_GROUP);
        put(out, "Exception", ExceptionTypes.EXCEPTION);
        put(out, "ExceptionGroup", ExceptionTypes.EXCEPTION_GROUP);
        put(out, "ArithmeticError", ExceptionTypes.ARITHMETIC_ERROR);
        put(out, "AssertionError", ExceptionTypes.ASSERTION_ERROR);
        put(out, "AttributeError", ExceptionTypes.ATTRIBUTE_ERROR);
        put(out, "BlockingIOError", ExceptionTypes.BLOCKING_IO_ERROR);
        put(out, "BrokenPipeError", ExceptionTypes.BROKEN_PIPE_ERROR);
        put(out, "BufferError", ExceptionTypes.BUFFER_ERROR);
        put(out, "BytesWarning", ExceptionTypes.BYTES_WARNING);
        put(out, "ChildProcessError", ExceptionTypes.CHILD_PROCESS_ERROR);
        put(out, "ConnectionAbortedError", ExceptionTypes.CONNECTION_ABORTED_ERROR);
        put(out, "ConnectionError", ExceptionTypes.CONNECTION_ERROR);
        put(out, "ConnectionRefusedError", ExceptionTypes.CONNECTION_REFUSED_ERROR);
        put(out, "ConnectionResetError", ExceptionTypes.CONNECTION_RESET_ERROR);
        put(out, "DeprecationWarning", ExceptionTypes.DEPRECATION_WARNING);
        put(out, "EOFError", ExceptionTypes.EOF_ERROR);
        put(out, "EncodingWarning", ExceptionTypes.ENCODING_WARNING);
        put(out, "FileExistsError", ExceptionTypes.FILE_EXISTS_ERROR);
        put(out, "FileNotFoundError", ExceptionTypes.FILE_NOT_FOUND_ERROR);
        put(out, "FloatingPointError", ExceptionTypes.FLOATING_POINT_ERROR);
        put(out, "FutureWarning", ExceptionTypes.FUTURE_WARNING);
        put(out, "GeneratorExit", ExceptionTypes.GENERATOR_EXIT);
        put(out, "ImportError", ExceptionTypes.IMPORT_ERROR);
        put(out, "ImportWarning", ExceptionTypes.IMPORT_WARNING);
        put(out, "IndentationError", ExceptionTypes.INDENTATION_ERROR);
        put(out, "IndexError", ExceptionTypes.INDEX_ERROR);
        put(out, "InterruptedError", ExceptionTypes.INTERRUPTED_ERROR);
        put(out, "IsADirectoryError", ExceptionTypes.IS_A_DIRECTORY_ERROR);
        put(out, "KeyError", ExceptionTypes.KEY_ERROR);
        put(out, "KeyboardInterrupt", ExceptionTypes.KEYBOARD_INTERRUPT);
        put(out, "LookupError", ExceptionTypes.LOOKUP_ERROR);
        put(out, "MemoryError", ExceptionTypes.MEMORY_ERROR);
        put(out, "ModuleNotFoundError", ExceptionTypes.MODULE_NOT_FOUND_ERROR);
        put(out, "NameError", ExceptionTypes.NAME_ERROR);
        put(out, "NotADirectoryError", ExceptionTypes.NOT_A_DIRECTORY_ERROR);
        put(out, "NotImplementedError", ExceptionTypes.NOT_IMPLEMENTED_ERROR);
        put(out, "OSError", ExceptionTypes.OS_ERROR);
        put(out, "OverflowError", ExceptionTypes.OVERFLOW_ERROR);
        put(out, "PendingDeprecationWarning", ExceptionTypes.PENDING_DEPRECATION_WARNING);
        put(out, "PermissionError", ExceptionTypes.PERMISSION_ERROR);
        put(out, "ProcessLookupError", ExceptionTypes.PROCESS_LOOKUP_ERROR);
        put(out, "PythonFinalizationError", ExceptionTypes.PYTHON_FINALIZATION_ERROR);
        put(out, "RecursionError", ExceptionTypes.RECURSION_ERROR);
        put(out, "ReferenceError", ExceptionTypes.REFERENCE_ERROR);
        put(out, "ResourceWarning", ExceptionTypes.RESOURCE_WARNING);
        put(out, "RuntimeError", ExceptionTypes.RUNTIME_ERROR);
        put(out, "RuntimeWarning", ExceptionTypes.RUNTIME_WARNING);
        put(out, "StopAsyncIteration", ExceptionTypes.STOP_ASYNC_ITERATION);
        put(out, "StopIteration", ExceptionTypes.STOP_ITERATION);
        put(out, "SyntaxError", ExceptionTypes.SYNTAX_ERROR);
        put(out, "SyntaxWarning", ExceptionTypes.SYNTAX_WARNING);
        put(out, "SystemError", ExceptionTypes.SYSTEM_ERROR);
        put(out, "SystemExit", ExceptionTypes.SYSTEM_EXIT);
        put(out, "TabError", ExceptionTypes.TAB_ERROR);
        put(out, "TimeoutError", ExceptionTypes.TIMEOUT_ERROR);
        put(out, "TypeError", ExceptionTypes.TYPE_ERROR);
        put(out, "UnboundLocalError", ExceptionTypes.UNBOUND_LOCAL_ERROR);
        put(out, "UnicodeDecodeError", ExceptionTypes.UNICODE_DECODE_ERROR);
        put(out, "UnicodeEncodeError", ExceptionTypes.UNICODE_ENCODE_ERROR);
        put(out, "UnicodeError", ExceptionTypes.UNICODE_ERROR);
        put(out, "UnicodeTranslateError", ExceptionTypes.UNICODE_TRANSLATE_ERROR);
        put(out, "UnicodeWarning", ExceptionTypes.UNICODE_WARNING);
        put(out, "UserWarning", ExceptionTypes.USER_WARNING);
        put(out, "ValueError", ExceptionTypes.VALUE_ERROR);
        put(out, "Warning", ExceptionTypes.WARNING);
        put(out, "ZeroDivisionError", ExceptionTypes.ZERO_DIVISION_ERROR);
        put(out, "_IncompleteInputError", ExceptionTypes.INCOMPLETE_INPUT_ERROR);
        // Two names the same class answers to, which _PyBuiltins_AddExceptions files as aliases of
        // OSError rather than as classes: they are the same object, so `IOError is OSError` holds and a
        // raise of one is caught by the other. WindowsError is the third of these and is not filed here,
        // because CPython files it only in a Windows build.
        put(out, "EnvironmentError", ExceptionTypes.OS_ERROR);
        put(out, "IOError", ExceptionTypes.OS_ERROR);
    }

    /**
     * The two functions {@code LOAD_COMMON_CONSTANT} names at 3 and 4, as the namespace filed them.
     *
     * <p>{@code all(x for x in y)} is not a call: the compiler puts an identity check on these two objects
     * in front of an inlined loop, and CPython keeps the objects in {@code interp->common_consts}, written
     * once at startup. Reading them out of the live namespace instead would let a program that rebinds
     * {@code builtins.all} keep the inlined loop, which CPython would not.
     */
    static PyObject commonAll;

    static PyObject commonAny;

    /**
     * The forty rows {@code bltinmodule.c}'s method table files, each with the {@code ml_doc} its table
     * row carries.
     *
     * <p>Twenty-seven of these texts are the clinic's, and so read as two halves: {@code find_signature} and
     * {@code skip_signature} cut the signature out of the front of the sentence and hand it to
     * {@code __text_signature__}, leaving the rest to {@code __doc__}. Eight were written by hand —
     * {@code dir}, {@code getattr}, {@code iter}, {@code max}, {@code min}, {@code next}, {@code vars} and
     * {@code __build_class__} — and their first line carries no {@code --} rule, so the whole text stays in
     * {@code __doc__} and {@code __text_signature__} reads {@code None}. The five this runtime files as
     * functions while CPython files them as types ({@code enumerate}, {@code filter}, {@code map},
     * {@code reversed} and {@code zip}) carry the text CPython reads as that type's {@code tp_doc}, which
     * is why their signature names the row itself — {@code (function, iterable, /)} — rather than opening
     * with {@code $module} like the clinic's module-level rows do.
     */
    private static void addFunctions(PyDict out) {
        put(out, "print", new PyNativeFunction("print", Builtins::print)
                .documented(
                        "print($module, /, *args, sep=' ', end='\\n', file=None, flush=False)\n"
                                + "--\n"
                                + "\n"
                                + "Prints the values to a stream, or to sys.stdout by default.\n"
                                + "\n"
                                + "  sep\n"
                                + "    string inserted between values, default a space.\n"
                                + "  end\n"
                                + "    string appended after the last value, default a newline.\n"
                                + "  file\n"
                                + "    a file-like object (stream); defaults to the current sys.stdout.\n"
                                + "  flush\n"
                                + "    whether to forcibly flush the stream."));
        put(out, "input", new PyNativeFunction("input", Builtins::input)
                .documented(
                        "input($module, prompt='', /)\n"
                                + "--\n"
                                + "\n"
                                + "Read a string from standard input.  The trailing newline is"
                                + " stripped.\n"
                                + "\n"
                                + "The prompt string, if given, is printed to standard output without"
                                + " a\n"
                                + "trailing newline before reading input.\n"
                                + "\n"
                                + "If the user hits EOF (*nix: Ctrl-D, Windows: Ctrl-Z+Return), raise\n"
                                + "EOFError.\n"
                                + "On *nix systems, readline is used if available."));
        put(out, "len", unary("len", value -> Slots.length(value))
                .documented(
                        "len($module, obj, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the number of items in a container."));
        put(out, "repr", unary("repr", value -> PyStr.of(value.repr()))
                .documented(
                        "repr($module, obj, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the canonical string representation of the object.\n"
                                + "\n"
                                + "For many object types, including most builtins, eval(repr(obj)) == obj."));
        put(out, "abs", unary("abs", value -> Slots.absolute(value))
                .documented(
                        "abs($module, x, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the absolute value of the argument."));
        put(out, "hash", unary("hash", value -> PyInt.of(value.hash()))
                .documented(
                        "hash($module, obj, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the integer hash value for the given object.\n"
                                + "\n"
                                + "Two objects that compare equal must also have the same hash value, but\n"
                                + "the reverse is not necessarily true.  Hash values may differ between\n"
                                + "Python processes.  Not all objects are hashable; calling hash() on an\n"
                                + "unhashable object raises TypeError."));
        put(out, "id", unary("id", value -> PyInt.of(System.identityHashCode(value)))
                .documented(
                        "id($module, obj, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the identity of an object.\n"
                                + "\n"
                                + "This is guaranteed to be unique among simultaneously existing objects.\n"
                                + "(CPython uses the object's memory address.)"));
        put(out, "callable", unary("callable", value -> PyBool.of(Slots.isCallable(value)))
                .documented(
                        "callable($module, obj, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return whether the object is callable (i.e., some kind of function).\n"
                                + "\n"
                                + "Note that classes are callable, as are instances of classes with a\n"
                                + "__call__() method."));
        commonAll =
                unary("all", Builtins::all)
                        .documented(
                                "all($module, iterable, /)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Return True if bool(x) is True for all values x in the"
                                        + " iterable.\n"
                                        + "\n"
                                        + "If the iterable is empty, return True.");
        put(out, "all", commonAll);
        commonAny =
                unary("any", Builtins::any)
                        .documented(
                                "any($module, iterable, /)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Return True if bool(x) is True for any x in the"
                                        + " iterable.\n"
                                        + "\n"
                                        + "If the iterable is empty, return False.");
        put(out, "any", commonAny);
        put(out, "ord", unary("ord", Builtins::ordinal)
                .documented(
                        "ord($module, character, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the ordinal value of a character.\n"
                                + "\n"
                                + "If the argument is a one-character string, return the Unicode code\n"
                                + "point of that character.\n"
                                + "\n"
                                + "If the argument is a bytes or bytearray object of length 1, return its\n"
                                + "single byte value."));
        put(out, "chr", unary("chr", Builtins::character)
                .documented(
                        "chr($module, i, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return a Unicode string of one character with ordinal i; 0 <= i <= 0x10ffff."));
        put(out, "ascii", unary("ascii", Builtins::asciiText)
                // The book spells each marker with two backslashes, as the clinic block writes it.
                .documented(
                        "ascii($module, obj, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return an ASCII-only representation of an object.\n"
                                + "\n"
                                + "As repr(), return a string containing a printable representation of an\n"
                                + "object, but escape the non-ASCII characters in the string returned by\n"
                                + "repr() using \\\\x, \\\\u or \\\\U escapes. This generates a string similar\n"
                                + "to that returned by repr() in Python 2."));
        put(out, "bin", unary("bin", value -> PyStr.of(radix(value, 2, "0b")))
                .documented(
                        "bin($module, number, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the binary representation of an integer.\n"
                                + "\n"
                                + "   >>> bin(2796202)\n"
                                + "   '0b1010101010101010101010'"));
        put(out, "hex", unary("hex", value -> PyStr.of(radix(value, 16, "0x")))
                .documented(
                        "hex($module, number, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the hexadecimal representation of an integer.\n"
                                + "\n"
                                + "   >>> hex(12648430)\n"
                                + "   '0xc0ffee'"));
        put(out, "oct", unary("oct", value -> PyStr.of(radix(value, 8, "0o")))
                .documented(
                        "oct($module, number, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the octal representation of an integer.\n"
                                + "\n"
                                + "   >>> oct(342391)\n"
                                + "   '0o1234567'"));
        put(out, "format", new PyNativeFunction("format", Builtins::format)
                .documented(
                        "format($module, value, format_spec='', /)\n"
                                + "--\n"
                                + "\n"
                                + "Return type(value).__format__(value, format_spec)\n"
                                + "\n"
                                + "Many built-in types implement format_spec according to the\n"
                                + "Format Specification Mini-language. See help('FORMATTING').\n"
                                + "\n"
                                + "If type(value) does not supply a method named __format__\n"
                                + "and format_spec is empty, then str(value) is returned.\n"
                                + "See also help('SPECIALMETHODS')."));
        put(out, "min", new PyNativeFunction("min", (args, kw) -> extreme(args, kw, false))
                .documented(
                        "min(iterable, *[, default=obj, key=func]) -> value\n"
                                + "min(arg1, arg2, *args, *[, key=func]) -> value\n"
                                + "\n"
                                + "With a single iterable argument, return its smallest item. The\n"
                                + "default keyword-only argument specifies an object to return if\n"
                                + "the provided iterable is empty.\n"
                                + "With two or more positional arguments, return the smallest argument."));
        put(out, "max", new PyNativeFunction("max", (args, kw) -> extreme(args, kw, true))
                .documented(
                        "max(iterable, *[, default=obj, key=func]) -> value\n"
                                + "max(arg1, arg2, *args, *[, key=func]) -> value\n"
                                + "\n"
                                + "With a single iterable argument, return its biggest item. The\n"
                                + "default keyword-only argument specifies an object to return if\n"
                                + "the provided iterable is empty.\n"
                                + "With two or more positional arguments, return the largest argument."));
        put(out, "sum", new PyNativeFunction("sum", Builtins::sum)
                .documented(
                        "sum($module, iterable, /, start=0)\n"
                                + "--\n"
                                + "\n"
                                + "Return the sum of a 'start' value (default: 0) plus an iterable of numbers\n"
                                + "\n"
                                + "When the iterable is empty, return the start value.\n"
                                + "This function is intended specifically for use with numeric values and\n"
                                + "may reject non-numeric types."));
        put(out, "sorted", new PyNativeFunction("sorted", Builtins::sorted)
                .documented(
                        "sorted($module, iterable, /, *, key=None, reverse=False)\n"
                                + "--\n"
                                + "\n"
                                + "Return a new list containing all items from the iterable in ascending order.\n"
                                + "\n"
                                + "A custom key function can be supplied to customize the sort order, and the\n"
                                + "reverse flag can be set to request the result in descending order."));
        // The five names that hand out a cursor and are types rather than functions: each is the
        // `PyTypeObject` the call makes, so `reversed(x)` is the type's own `tp_vectorcall` and
        // `reversed.__new__(reversed, x)` is its `tp_new` — two spellings of one constructor, and a book of
        // names behind the value a program can reach. `iter` is the sixth of these and the only one left as
        // a `builtin_function_or_method`, because there is no one iterator type for it to be.
        put(out, "reversed", PyType.REVERSED);
        put(out, "enumerate", PyType.ENUMERATE);
        put(out, "zip", PyType.ZIP);
        put(out, "map", PyType.MAP);
        put(out, "filter", PyType.FILTER);
        put(out, "iter", new PyNativeFunction("iter", Builtins::iter)
                .documented(
                        "iter(iterable) -> iterator\n"
                                + "iter(callable, sentinel) -> iterator\n"
                                + "\n"
                                + "Get an iterator from an object.  In the first form, the argument must\n"
                                + "supply its own iterator, or be a sequence.\n"
                                + "In the second form, the callable is called until it returns the\n"
                                + "sentinel."));
        put(out, "next", new PyNativeFunction("next", Builtins::next)
                .documented(
                        "next(iterator[, default])\n"
                                + "\n"
                                + "Return the next item from the iterator.  If default is given and the\n"
                                + "iterator is exhausted, it is returned instead of raising StopIteration."));
        put(out, "isinstance", twoArgument("isinstance", Builtins::isInstanceOf)
                .documented(
                        "isinstance($module, obj, class_or_tuple, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return whether an object is an instance of a class or of a subclass thereof.\n"
                                + "\n"
                                + "A tuple, as in ``isinstance(x, (A, B, ...))``, may be given as the\n"
                                + "target to check against.  This is equivalent to ``isinstance(x, A) or\n"
                                + "isinstance(x, B) or ...`` etc."));
        put(out, "issubclass", twoArgument("issubclass", Builtins::isSubclass)
                .documented(
                        "issubclass($module, cls, class_or_tuple, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return whether 'cls' is derived from another class or is the same class.\n"
                                + "\n"
                                + "A tuple, as in ``issubclass(x, (A, B, ...))``, may be given as the\n"
                                + "target to check against.  This is equivalent to ``issubclass(x, A) or\n"
                                + "issubclass(x, B) or ...``."));
        put(out, "divmod", twoArgument("divmod", (a, b) -> PyTuple.of(
                Slots.binary(BinaryOp.FLOORDIV, a, b), Slots.binary(BinaryOp.MOD, a, b)))
                .documented(
                        "divmod($module, x, y, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the tuple (x//y, x%y).  Invariant: div*y + mod == x."));
        put(out, "pow", new PyNativeFunction("pow", Builtins::power)
                .documented(
                        "pow($module, /, base, exp, mod=None)\n"
                                + "--\n"
                                + "\n"
                                + "Equivalent to base**exp with 2 arguments or base**exp % mod with 3 arguments\n"
                                + "\n"
                                + "Some types, such as ints, are able to use a more efficient algorithm\n"
                                + "when invoked using the three argument form."));
        put(out, "round", new PyNativeFunction("round", Builtins::round)
                .documented(
                        "round($module, /, number, ndigits=None)\n"
                                + "--\n"
                                + "\n"
                                + "Round a number to a given precision in decimal digits.\n"
                                + "\n"
                                + "The return value is an integer if ndigits is omitted or None.\n"
                                + "Otherwise the return value has the same type as the number.  ndigits\n"
                                + "may be negative."));
        put(out, "getattr", new PyNativeFunction("getattr", Builtins::getAttribute)
                .documented(
                        "getattr(object, name[, default]) -> value\n"
                                + "\n"
                                + "Get a named attribute from an object.\n"
                                + "\n"
                                + "getattr(x, 'y') is equivalent to x.y.\n"
                                + "When a default argument is given, it is returned when the attribute\n"
                                + "doesn't exist; without it, an exception is raised in that case."));
        put(out, "hasattr", new PyNativeFunction("hasattr", Builtins::hasAttribute)
                .documented(
                        "hasattr($module, obj, name, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return whether the object has an attribute with the given name.\n"
                                + "\n"
                                + "This is done by calling getattr(obj, name) and catching AttributeError."));
        put(out, "setattr", new PyNativeFunction("setattr", Builtins::setAttribute)
                .documented(
                        "setattr($module, obj, name, value, /)\n"
                                + "--\n"
                                + "\n"
                                + "Sets the named attribute on the given object to the specified value.\n"
                                + "\n"
                                + "setattr(x, 'y', v) is equivalent to ``x.y = v``"));
        put(out, "delattr", new PyNativeFunction("delattr", Builtins::deleteAttribute)
                .documented(
                        "delattr($module, obj, name, /)\n"
                                + "--\n"
                                + "\n"
                                + "Deletes the named attribute from the given object.\n"
                                + "\n"
                                + "delattr(x, 'y') is equivalent to ``del x.y``"));
        put(out, "locals", new PyNativeFunction("locals", Builtins::localNames)
                .documented(
                        "locals($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return a dictionary containing the current scope's local variables.\n"
                                + "\n"
                                + "NOTE: Whether or not updates to this dictionary will affect name\n"
                                + "lookups in the local scope and vice-versa is *implementation\n"
                                + "dependent* and not covered by any backwards compatibility\n"
                                + "guarantees."));
        put(out, "globals", new PyNativeFunction("globals", Builtins::globalNames)
                .documented(
                        "globals($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the dictionary containing the current scope's global variables.\n"
                                + "\n"
                                + "NOTE: Updates to this dictionary *will* affect name lookups in the\n"
                                + "current global scope and vice-versa."));
        put(out, "vars", new PyNativeFunction("vars", Builtins::variables)
                .documented(
                        "vars([object]) -> dictionary\n"
                                + "\n"
                                + "Without arguments, equivalent to locals().\n"
                                + "With an argument, equivalent to object.__dict__."));
        put(out, "dir", new PyNativeFunction("dir", Builtins::directory)
                .documented(
                        "dir([object]) -> list of strings\n"
                                + "\n"
                                + "If called without an argument, return the names in the current scope.\n"
                                + "Else, return an alphabetized list of names comprising (some of) the\n"
                                + "attributes of the given object, and of attributes reachable from it.\n"
                                + "If the object supplies a method named __dir__, it will be used;\n"
                                + "otherwise the default dir() logic is used and returns:\n"
                                + "  for a module object: the module's attributes.\n"
                                + "  for a class object:  its attributes, and recursively the attributes\n"
                                + "    of its bases.\n"
                                + "  for any other object: its attributes, its class's attributes, and\n"
                                + "    recursively the attributes of its class's base classes."));
        put(out, "__build_class__", new PyNativeFunction("__build_class__", Classes::buildClass)
                .documented(
                        "__build_class__(func, name, /, *bases, [metaclass], **kwds) -> class\n"
                                + "\n"
                                + "Internal helper function used by the class statement."));
    }

    // --- one argument ----------------------------------------------------------------------

    /** A builtin of exactly one positional argument, in {@code len()}'s counting family. */
    private static PyNativeFunction unary(
            String name, java.util.function.Function<PyObject, PyObject> body) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.exactlyOne(name + "()", args, kwNames);
            return body.apply(args[0]);
        });
    }

    /** A builtin of exactly two positional arguments, in {@code isinstance}'s legacy family. */
    private static PyNativeFunction twoArgument(
            String name, java.util.function.BiFunction<PyObject, PyObject, PyObject> body) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.noKeywords(name + "()", kwNames);
            Arguments.exactly(name, args, kwNames, 2);
            return body.apply(args[0], args[1]);
        });
    }

    private static PyObject ordinal(PyObject value) {
        if (!(value instanceof PyStr s)) {
            throw Slots.typeError(
                    "ord() expected string of length 1, but %s found", value.getType().name());
        }
        if (s.codePointLength() != 1) {
            throw Slots.typeError(
                    "ord() expected a character, but string of length %d found",
                    s.codePointLength());
        }
        return PyInt.of(s.codePointAt(0));
    }

    private static PyObject character(PyObject value) {
        BigInteger code = integerOrRefusal(value);
        if (code.signum() < 0 || code.compareTo(MAX_CODE_POINT) > 0) {
            throw Slots.valueError("chr() arg not in range(0x110000)");
        }
        return PyStr.of(new String(Character.toChars(code.intValue())));
    }

    private static final BigInteger MAX_CODE_POINT = BigInteger.valueOf(0x10FFFF);

    /** {@code bin}, {@code hex}, {@code oct}: an {@code __index__} integer with the prefix inside the sign. */
    private static String radix(PyObject value, int base, String prefix) {
        BigInteger number = integerOrRefusal(value);
        return (number.signum() < 0 ? "-" : "") + prefix + number.abs().toString(base);
    }

    /**
     * The number a builtin reads out of its argument with {@code _PyNumber_Index}: {@code chr}, and the
     * three radix spellings, which are {@code PyNumber_ToBase} over the same conversion.
     */
    private static BigInteger integerOrRefusal(PyObject value) {
        return Numbers.asIndex(value);
    }

    /**
     * {@code PyObject_ASCII} (Objects/object.c:840): the repr of the value, then that same text as the
     * encoder's {@code backslashreplace} handler writes it — every character above {@code 0x7f} replaced by
     * the escape its width asks for. Everything below that is handed on as it stands, control characters
     * included, which is why a raw newline written by a program's own {@code __repr__} is still a newline in
     * the answer, while {@code repr} of that answer escapes it.
     *
     * <p>The walk is {@link Encoder}'s rather than a loop of this function's own, so the escapes {@code
     * ascii()} writes and the escapes {@code "é".encode("ascii", "backslashreplace")} writes are the same
     * escapes; a lone surrogate in a hand-written {@code __repr__} earns the same six characters either way.
     */
    private static PyObject asciiText(PyObject value) {
        return PyStr.of(Encoder.asciiEscaped(value.repr()));
    }

    /**
     * {@code format(value, format_spec='', /)}.
     *
     * Both parameters are positional-only, which is why a keyword gives the counting family's own
     * refusal rather than a named complaint.
     */
    private static PyObject format(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("format()", kwNames);
        Arguments.between("format", args, kwNames, 1, 2);
        String spec = args.length == 1 ? "" : asSpec(args[1]);
        return PyStr.of(Formats.apply(args[0], spec));
    }

    private static String asSpec(PyObject value) {
        if (value instanceof PyStr s) {
            return s.getValue();
        }
        throw Slots.typeError(
                "format() argument 2 must be str, not %s", Slots.argumentName(value));
    }

    // --- print -----------------------------------------------------------------------------

    private static final String[] PRINT_NAMES = {"sep", "end", "file", "flush"};

    /**
     * {@code print(*args, sep=' ', end='\n', file=None, flush=False)}.
     *
     * The four parameters are keyword-only, so every positional argument is something to print — which
     * is why {@code print(1, "a")} prints {@code 1 a} rather than setting a separator. Giving {@code
     * None} for {@code sep} or {@code end} is not "print nothing" but "print the default", which is
     * what the two look like when they are simply absent.
     *
     * <p>{@code builtin_print_impl} (Python/bltinmodule.c:2231) settles the stream before it looks at the
     * separator: a call that names no {@code file} reads {@code sys.stdout} there and then, so a name that
     * has been deleted is refused as {@code lost sys.stdout} before anyone notices that {@code sep} was an
     * integer, and a name that is {@code None} answers without writing anything — the one state where
     * {@code print} is quieter than {@code sys.displayhook}, which refuses it. Naming a {@code file} skips
     * the read entirely, and {@code file=None} is the same call as naming nothing. What follows is one
     * {@code write} per argument and one per separator — an empty separator is written, not skipped — then
     * one for the ending, then {@code flush} once those are done. The value of an argument is turned into
     * text as it is reached, which is why an argument that refuses mid-call leaves the ones before it, and
     * the separator that was written ahead of it, already on the stream.
     */
    private static PyObject print(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        PyObject separator = PyNone.INSTANCE;
        PyObject ending = PyNone.INSTANCE;
        PyObject target = PyNone.INSTANCE;
        boolean flush = false;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject value = args[positional + i];
                switch (kwNames[i]) {
                    case "sep" -> separator = value;
                    case "end" -> ending = value;
                    case "file" -> target = value;
                    case "flush" -> flush = Slots.truth(value);
                    default -> throw Slots.typeError(
                            "print() got an unexpected keyword argument '%s'", kwNames[i]);
                }
            }
        }
        if (target == PyNone.INSTANCE) {
            target = PyRuntime.sysAttribute("stdout");
            if (target == null) {
                throw Slots.runtimeError("lost sys.stdout");
            }
            if (target == PyNone.INSTANCE) {
                return PyNone.INSTANCE;
            }
        }
        PyObject between = printed("sep", separator, DEFAULT_SEPARATOR);
        PyObject after = printed("end", ending, DEFAULT_ENDING);
        for (int i = 0; i < positional; i++) {
            if (i > 0) {
                write(target, between);
            }
            write(target, args[i]);
        }
        write(target, after);
        if (flush) {
            Slots.call(Slots.getAttr(target, "flush"), new PyObject[0], null);
        }
        return PyNone.INSTANCE;
    }

    /** The two defaults, which {@code sep=None} and {@code end=None} also answer with. */
    private static final PyStr DEFAULT_SEPARATOR = PyStr.of(" ");

    private static final PyStr DEFAULT_ENDING = PyStr.of("\n");

    /**
     * One {@code PyFile_WriteObject} with {@code Py_PRINT_RAW}: the value's own text, handed to the
     * stream's {@code write} as its own call. The stream is reached through its attributes rather than
     * its class, which is what both {@code file=} with any object that can write and {@code print(x,
     * file=1)}'s {@code AttributeError} depend on.
     */
    private static void write(PyObject stream, PyObject value) {
        Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of(value.str())}, null);
    }

    /**
     * {@code sep} and {@code end}, where {@code None} means the parameter's own default.
     *
     * <p>The value is kept as it was given rather than read out as a Java string, because the reference
     * writes it through {@code PyObject_Str}: a {@code str} subclass that has written its own {@code
     * __str__} is printed as that answers, not as the characters it holds.
     */
    private static PyObject printed(String name, PyObject value, PyObject fallback) {
        if (value == PyNone.INSTANCE) {
            return fallback;
        }
        if (value instanceof PyStr) {
            return value;
        }
        throw Slots.typeError(
                "%s must be None or a string, not %s", name, value.getType().name());
    }

    // --- input -----------------------------------------------------------------------------

    /**
     * {@code input(prompt='')}.
     *
     * <p>{@code builtin_input_impl} (Python/bltinmodule.c:2335) settles the three names before it asks
     * anything of them, and the order is the one a program can see: {@code stdin}, then {@code stdout},
     * then {@code stderr}, each missing or {@code None} refused as {@code lost sys.<name>} with nothing
     * yet called. What follows is one flush of {@code stderr}, whose refusal is dropped, then
     * {@code stdin.fileno()} — this runtime never reads a terminal through the {@code readline} library,
     * so the answer decides nothing, but it is still read, and the two refusals of reading it are kept:
     * a value no integer can be made of, and one too wide for a C long. Then the prompt, then a flush of
     * {@code stdout} that is also dropped, then the line.
     *
     * <p>Both flushes come before the read and after the write, and the prompt is written as
     * {@code print} writes — its {@code str}, through the stream's own {@code write} — which is why a
     * stream with no {@code write} is refused before the prompt has been looked at, and why
     * {@code input()} with no prompt writes nothing yet still flushes.
     */
    private static PyObject input(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("input()", kwNames);
        Arguments.atMost("input", args, kwNames, 1);
        PyObject prompt = Arguments.positional(args, kwNames) == 0 ? null : args[0];
        PyObject fin = streamNamed("stdin");
        PyObject fout = streamNamed("stdout");
        PyObject ferr = streamNamed("stderr");
        flushed(ferr);
        ttyTest(fin);
        if (prompt != null) {
            write(fout, prompt);
        }
        flushed(fout);
        return readLine(fin);
    }

    /** {@code _PySys_GetRequiredAttr}: the name is read from {@code sys}, and both absences are one error. */
    private static PyObject streamNamed(String name) {
        PyObject stream = PyRuntime.sysAttribute(name);
        if (stream == null || stream == PyNone.INSTANCE) {
            throw Slots.runtimeError("lost sys." + name);
        }
        return stream;
    }

    /** {@code _PyFile_Flush}, whose failure the caller clears: a stream may refuse to flush quietly. */
    private static void flushed(PyObject stream) {
        try {
            Slots.call(Slots.getAttr(stream, "flush"), new PyObject[0], null);
        } catch (PyRaise refusal) {
            // dropped, as it is in the reference — the read goes ahead whatever the flush answered.
        }
    }

    /** {@code tty = fd == fileno(stdin) && isatty(fd)}: the answer is read and then discarded. */
    private static void ttyTest(PyObject stream) {
        PyObject answer;
        try {
            answer = Slots.call(Slots.getAttr(stream, "fileno"), new PyObject[0], null);
        } catch (PyRaise refusal) {
            // A stream with no fileno, or one that refuses the question, is cleared and left non-tty.
            return;
        }
        // The conversion is outside the clearing, because these refusals are the ones kept: a float
        // answers `PyLong_AsLong` with what it cannot be read as, and a number wider than a C long
        // overflows before the comparison is ever made.
        BigInteger descriptor = Numbers.asIndex(answer);
        if (descriptor.bitLength() >= Long.SIZE) {
            throw Slots.overflowError("Python int too large to convert to C long");
        }
    }

    /**
     * {@code PyFile_GetLine(fin, -1)}: one call of {@code readline} with no arguments, and the newline
     * it ended the line with is the only character taken off.
     *
     * <p>An answer with no newline in it is handed back as the very object that answered, and an answer
     * that has one is cut down to a fresh string or octet run — which is observable for a subclass,
     * whose {@code type} the reference drops as it cuts and keeps as it does not. An empty answer is the
     * end of the descriptor and is refused, and anything that is neither text nor octets is refused by
     * the unpacking rather than by the caller.
     */
    private static PyObject readLine(PyObject stream) {
        PyObject answer = Slots.call(Slots.getAttr(stream, "readline"), new PyObject[0], null);
        if (answer instanceof PyStr text) {
            String value = text.getValue();
            if (value.isEmpty()) {
                throw atEof();
            }
            return value.charAt(value.length() - 1) == '\n'
                    ? PyStr.of(value.substring(0, value.length() - 1))
                    : answer;
        }
        if (answer instanceof PyBytes octets) {
            byte[] value = octets.getValue();
            if (value.length == 0) {
                throw atEof();
            }
            return value[value.length - 1] == '\n'
                    ? PyBytes.of(java.util.Arrays.copyOf(value, value.length - 1))
                    : answer;
        }
        throw Slots.typeError("object.readline() returned non-string");
    }

    /** The one message {@code EOFError} is raised with here, by {@code input} and by nothing else. */
    private static PyRaise atEof() {
        return PyRaise.raise(ExceptionTypes.EOF_ERROR, "EOF when reading a line");
    }

    // --- iteration and sequences -----------------------------------------------------------

    private static PyObject iter(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("iter()", kwNames);
        Arguments.between("iter", args, kwNames, 1, 2);
        if (args.length == 1) {
            return Slots.iterator(args[0]);
        }
        if (!Slots.isCallable(args[0])) {
            throw Slots.typeError("iter(v, w): v must be callable");
        }
        return new Repeated(Slots.call(args[0], new PyObject[0], null), args[1]);
    }

    /** {@code iter(callable, sentinel)}, the two-argument form. */
    private static final class Repeated extends PyIterator {

        private final PyObject callable;
        private final PyObject sentinel;

        Repeated(PyObject callable, PyObject sentinel) {
            this.callable = callable;
            this.sentinel = sentinel;
        }

        @Override
        public PyObject nextOrNull() {
            PyObject value = Slots.call(callable, new PyObject[0], null);
            return Slots.compareTrue(CompareOp.EQ, value, sentinel) ? null : value;
        }
    }

    private static PyObject next(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("next()", kwNames);
        Arguments.between("next", args, kwNames, 1, 2);
        // Not only the built-in iterators: a class that declares __next__ is driven the same way, which
        // is what PyIter_Check means for a value of a Python class.
        PyIterator iterator = Slots.asIterator(args[0]);
        if (iterator == null) {
            throw Slots.typeError(
                    "'%s' object is not an iterator", args[0].getType().name());
        }
        PyObject value = iterator.nextOrNull();
        if (value != null) {
            return value;
        }
        // The three outcomes of `PyIter_Next` returning NULL, which CPython tells apart by whether an
        // exception was left set. A generator that ended with a value was; one that simply ran out, or one
        // whose frame had already finished when this step was made, was not — and the difference is what
        // decides whether the default is taken or the real StopIteration, with its value, travels on.
        PyBaseException stopped = iterator.exhaustion();
        if (stopped != null) {
            if (args.length > 1) {
                return args[1];
            }
            // Re-raised as it was made: `_PyErr_SetRaisedException`, so no context is taken from whatever
            // the caller happens to be handling.
            throw PyRaise.reraise(stopped);
        }
        if (args.length > 1) {
            return args[1];
        }
        // `PyErr_SetNone`, which is the route that does chain: this invented exception gets the caller's
        // handled exception as its context, exactly as a bare `raise StopIteration` inside a handler would.
        throw PyRaise.of(new PyBaseException(ExceptionTypes.STOP_ITERATION, new PyObject[0]));
    }

    // --- aggregates ------------------------------------------------------------------------

    /**
     * {@code min}/{@code max}, either over an iterable or over several arguments, with {@code key} and
     * {@code default}.
     *
     * The two forms share a name and a family of messages but not a rule: {@code default} only means
     * anything to the one-argument form, an argument given by keyword is not one of the arguments the
     * count is taken over, and the empty-iterable message names itself qualified.
     */
    private static PyObject extreme(PyObject[] args, String[] kwNames, boolean maximum) {
        String label = maximum ? "max" : "min";
        Arguments.atLeast(label, args, kwNames, 1);
        PyObject key = null;
        PyObject fallback = null;
        boolean hasFallback = false;
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject value = args[positional + i];
                switch (kwNames[i]) {
                    case "key" -> key = value;
                    case "default" -> {
                        fallback = value;
                        hasFallback = true;
                    }
                    default -> throw Slots.typeError(
                            "%s() got an unexpected keyword argument '%s'", label, kwNames[i]);
                }
            }
        }
        List<PyObject> items = new ArrayList<>();
        if (positional == 1) {
            // One argument is the iterable to look over, so `min(1)` is `iter(1)`'s complaint.
            items.addAll(Constructors.materialise(args[0]));
        } else {
            for (int i = 0; i < positional; i++) {
                items.add(args[i]);
            }
        }
        if (items.isEmpty()) {
            if (hasFallback) {
                return fallback;
            }
            throw Slots.valueError("%s() iterable argument is empty", label);
        }
        PyObject best = items.get(0);
        PyObject bestKey = keyed(key, best);
        for (int i = 1; i < items.size(); i++) {
            PyObject candidate = items.get(i);
            PyObject candidateKey = keyed(key, candidate);
            boolean better = Slots.compareTrue(
                    maximum ? CompareOp.GT : CompareOp.LT, candidateKey, bestKey);
            // CPython keeps the first of the equal ones for both min and max, so an improvement has
            // to be strictly better rather than merely not-worse.
            if (better) {
                best = candidate;
                bestKey = candidateKey;
            }
        }
        return best;
    }

    private static PyObject keyed(PyObject key, PyObject value) {
        return key == null || key == PyNone.INSTANCE
                ? value
                : Slots.call(key, new PyObject[] {value}, null);
    }

    /**
     * {@code all(iterable)}: the walk that answers false at the first item that is not true.
     *
     * <p>The iterator is driven here rather than materialised first, because {@code builtin_all} breaks out
     * of its {@code for} as soon as {@code PyObject_IsTrue} has answered false — and an item whose truth
     * refuses is a refusal of the whole call, not one the walk recovers from.
     */
    private static PyObject all(PyObject iterable) {
        PyIterator iterator = Slots.iterator(iterable);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            if (!Slots.truth(item)) {
                return PyBool.FALSE;
            }
        }
        return PyBool.TRUE;
    }

    /** {@code any(iterable)}: the same walk, answering true at the first item that is. */
    private static PyObject any(PyObject iterable) {
        PyIterator iterator = Slots.iterator(iterable);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            if (Slots.truth(item)) {
                return PyBool.TRUE;
            }
        }
        return PyBool.FALSE;
    }

    private static PyObject sum(PyObject[] args, String[] kwNames) {
        Arguments.positionalAtLeast("sum", args, kwNames, 1);
        Arguments.atMostQualified("sum", args, kwNames, 2);
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (String kwName : kwNames) {
                if (!"start".equals(kwName)) {
                    throw Slots.typeError(
                            "sum() got an unexpected keyword argument '%s'", kwName);
                }
            }
        }
        PyObject total = positional > 1
                ? args[1]
                : keywordValue(kwNames, args, positional, "start", PyInt.ZERO);
        for (PyObject item : Constructors.materialise(args[0])) {
            total = Slots.binary(BinaryOp.ADD, total, item);
        }
        return total;
    }

    /**
     * {@code sorted(iterable, /, *, key=None, reverse=False)}.
     *
     * The keyword complaints name {@code sort}, not {@code sorted} — CPython's two share the argument
     * parser and it prints the list method's name — and every key is computed before the first
     * comparison, so a {@code key} that is not callable fails here rather than partway through the
     * sort, and one with side effects runs once per item.
     */
    private static PyObject sorted(PyObject[] args, String[] kwNames) {
        Arguments.exactly("sorted", args, kwNames, 1);
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (String kwName : kwNames) {
                if (!"key".equals(kwName) && !"reverse".equals(kwName)) {
                    throw Slots.typeError(
                            "sort() got an unexpected keyword argument '%s'", kwName);
                }
            }
        }
        PyObject key = keywordValue(kwNames, args, positional, "key", null);
        boolean reverse = Slots.truth(
                keywordValue(kwNames, args, positional, "reverse", PyBool.FALSE));
        List<PyObject> items = Constructors.materialise(args[0]);
        PyObject[] keys = new PyObject[items.size()];
        for (int i = 0; i < items.size(); i++) {
            keys[i] = keyed(key, items.get(i));
        }
        List<Integer> order = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        // Equal keys keep their original order in both directions, which a plain
        // comparator.reversed() would flip along with the comparison.
        Comparator<Integer> byKey = (left, right) -> {
            int step = reverse ? -1 : 1;
            if (Slots.compareTrue(CompareOp.LT, keys[left], keys[right])) {
                return -step;
            }
            if (Slots.compareTrue(CompareOp.LT, keys[right], keys[left])) {
                return step;
            }
            return Integer.compare(left, right);
        };
        order.sort(byKey);
        List<PyObject> out = new ArrayList<>(items.size());
        for (int index : order) {
            out.add(items.get(index));
        }
        return PyList.of(out.toArray(new PyObject[0]));
    }

    private static PyObject keywordValue(
            String[] kwNames, PyObject[] args, int positional, String name, PyObject absent) {
        if (kwNames == null) {
            return absent;
        }
        for (int i = 0; i < kwNames.length; i++) {
            if (kwNames[i].equals(name)) {
                return args[positional + i];
            }
        }
        return absent;
    }

    // --- types and attributes --------------------------------------------------------------

    /**
     * {@code isinstance(x, C)}: {@link Classes#instanceOf}, which is {@code object_recursive_isinstance}.
     *
     * <p>The builtin answers nothing of its own. That is what lets a metaclass take over the test, and
     * it is why the exact-class shortcut and the {@code __instancecheck__} hook behave the same whether
     * the question came from {@code isinstance}, from {@code type.__instancecheck__} or from a metaclass
     * that calls {@code super()} to get the ordinary answer.
     */
    private static PyObject isInstanceOf(PyObject value, PyObject classes) {
        return PyBool.of(Classes.instanceOf(value, classes));
    }

    /** {@code issubclass(A, B)}: {@link Classes#subclassOf}, which is {@code object_issubclass}. */
    private static PyObject isSubclass(PyObject child, PyObject classes) {
        return PyBool.of(Classes.subclassOf(child, classes));
    }

    private static PyObject getAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("getattr()", kwNames);
        Arguments.between("getattr", args, kwNames, 2, 3);
        String name = attributeName(args[1]);
        try {
            return Slots.getAttr(args[0], name);
        } catch (PyRaise failure) {
            if (args.length == 2
                    || !failure.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                throw failure;
            }
            return args[2];
        }
    }

    private static PyObject hasAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("hasattr()", kwNames);
        Arguments.exactly("hasattr", args, kwNames, 2);
        String name = attributeName(args[1]);
        try {
            Slots.getAttr(args[0], name);
            return PyBool.TRUE;
        } catch (PyRaise failure) {
            // hasattr reports a missing attribute as false and lets anything else through, which is
            // why a property that raises does not turn hasattr into false.
            if (failure.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                return PyBool.FALSE;
            }
            throw failure;
        }
    }

    /**
     * {@code setattr(x, name, value)}: the same write the {@code STORE_ATTR} instruction makes.
     *
     * <p>Routing it through {@link Slots} rather than calling the value is what lets {@code
     * setattr(3, '__class__', int)} be refused as a class assignment instead of as a name {@code int} does
     * not keep, and lets a class that declares {@code __setattr__} see the call from either spelling.
     */
    private static PyObject setAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("setattr()", kwNames);
        Arguments.exactly("setattr", args, kwNames, 3);
        Slots.setAttr(args[0], attributeName(args[1]), args[2]);
        return PyNone.INSTANCE;
    }

    private static PyObject deleteAttribute(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("delattr()", kwNames);
        Arguments.exactly("delattr", args, kwNames, 2);
        Slots.delAttr(args[0], attributeName(args[1]));
        return PyNone.INSTANCE;
    }

    private static String attributeName(PyObject value) {
        if (value instanceof PyStr s) {
            return s.getValue();
        }
        throw Slots.typeError(
                "attribute name must be string, not '%s'", value.getType().name());
    }

    // --- namespaces ------------------------------------------------------------------------

    /**
     * {@code locals()}: the names of the scope Python is running, as 3.14's builtin answers them.
     *
     * <p>A module or a class body is answered with the very dictionary its names are written into, so
     * {@code locals() is globals()} holds at module level and {@code vars() is locals()} holds in a class
     * body — and a name added to either is a name the scope has. A function frame has no dictionary: 3.14
     * walks its slots, follows each cell to what it holds, leaves a name out while the slot is empty, and
     * hands back a plain dict made for the call, so writing into the answer writes into nothing.
     * {@code frame.f_locals} is the other shape again, CPython's write-back proxy, which this runtime does
     * not have.
     */
    private static PyObject localNames(PyObject[] args, String[] kwNames) {
        Arguments.none("locals()", args, kwNames);
        return currentLocals();
    }

    /** {@code globals()}: the module dictionary the running frame reads its names from, the live one. */
    private static PyObject globalNames(PyObject[] args, String[] kwNames) {
        Arguments.none("globals()", args, kwNames);
        Frame frame = Interpreter.currentFrame();
        return frame == null ? PyRuntime.mainModule().dictionary() : frame.globals();
    }

    /**
     * {@code vars([object])}: {@code locals()} without an argument, {@code object.__dict__} with one.
     *
     * <p>With an argument it is the attribute read and nothing more, so a value whose {@code __dict__} is
     * a property that answers a dictionary is given that dictionary, and only a value with no such member
     * is refused. A class answers its own namespace rather than CPython's read-only mappingproxy view of
     * it.
     */
    private static PyObject variables(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("vars()", kwNames);
        Arguments.atMost("vars", args, kwNames, 1);
        if (args.length == 0) {
            return currentLocals();
        }
        PyObject held = Slots.findAttr(args[0], "__dict__");
        if (held == null) {
            throw Slots.typeError("vars() argument must have __dict__ attribute");
        }
        return held;
    }

    /**
     * {@code dir([object])}: the names of the running scope, or the names one value answers with.
     *
     * <p>Both halves sort, and the argument half goes through {@link Dirs} for what the value's {@code
     * __dir__} returns — items in the order they arrive, duplicates kept, a refusal allowed to escape — or
     * for the default walk of the dictionaries when nothing declares one.
     */
    private static PyObject directory(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("dir()", kwNames);
        Arguments.atMost("dir", args, kwNames, 1);
        if (args.length == 0) {
            return sorted(new PyObject[] {currentLocals()}, null);
        }
        PyObject[] items = Dirs.itemsOf(args[0]).toArray(new PyObject[0]);
        return sorted(new PyObject[] {PyList.of(items)}, null);
    }

    /**
     * Whose names {@code locals()} is asked for.
     *
     * <p>Java code that reached a builtin from outside any frame is the case the frame cannot answer, and
     * CPython sends it to the globals of the interpreter's running {@code __main__} — which is the same
     * dictionary this runtime hands back, and the reason {@code globals()} is never {@code None} here.
     */
    private static PyObject currentLocals() {
        Frame frame = Interpreter.currentFrame();
        return frame == null ? PyRuntime.mainModule().dictionary() : frame.localValues();
    }

    // --- numeric builtins ------------------------------------------------------------------

    private static final String[] POW_NAMES = {"base", "exp", "mod"};
    private static final PyObject[] POW_DEFAULTS = {PyNone.INSTANCE};
    private static final String[] ROUND_NAMES = {"number", "ndigits"};
    private static final PyObject[] ROUND_DEFAULTS = {PyNone.INSTANCE};

    /**
     * {@code pow(base, exp, mod=None)}.
     *
     * All three are positional-or-keyword, so {@code pow(2, exp=3)} is 8. The two-argument form is the
     * {@code **} operator's but names both spellings in its message; the three-argument form is
     * integers only, and a negative exponent asks for a modular inverse rather than a fraction, which
     * is what makes {@code pow(2, -1, 5)} 3. A negative modulus keeps Python's floor-remainder sign,
     * which {@code BigInteger.mod} alone cannot give.
     */
    private static PyObject power(PyObject[] args, String[] kwNames) {
        Arguments.atMostQualified("pow", args, kwNames, 3);
        PyObject[] given = Arguments.fill("pow", args, kwNames, POW_NAMES, POW_DEFAULTS);
        Arguments.required("pow", POW_NAMES, given, 0);
        Arguments.required("pow", POW_NAMES, given, 1);
        PyObject base = given[0];
        PyObject exponent = given[1];
        PyObject modulus = given[2];
        if (modulus == null || modulus == PyNone.INSTANCE) {
            if (!isNumber(base) || !isNumber(exponent)) {
                throw Slots.typeError(
                        "unsupported operand type(s) for ** or pow(): '%s' and '%s'",
                        base.getType().name(), exponent.getType().name());
            }
            return Slots.binary(BinaryOp.POW, base, exponent);
        }
        BigInteger b = Numbers.asInteger(base);
        BigInteger e = Numbers.asInteger(exponent);
        BigInteger m = Numbers.asInteger(modulus);
        if (b == null || e == null || m == null) {
            if (isNumber(base) && isNumber(exponent) && isNumber(modulus)) {
                throw Slots.typeError(
                        "pow() 3rd argument not allowed unless all arguments are integers");
            }
            throw Slots.typeError(
                    "unsupported operand type(s) for ** or pow(): '%s', '%s', '%s'",
                    base.getType().name(), exponent.getType().name(), modulus.getType().name());
        }
        return modularPower(b, e, m);
    }

    /**
     * {@code long_pow}'s modulus half, which the builtin and the slot share.
     *
     * <p>The three refusals are CPython's in its order: a modulus of zero is complained about before the
     * exponent is looked at, and a base with no inverse is only discovered once the exponent is known to
     * be negative. A negative modulus is not a refusal but a rule about the answer — the remainder keeps
     * the divisor's sign, which is why {@code pow(7, 2, -5)} is {@code -1} and not {@code 4}.
     */
    static PyObject modularPower(BigInteger b, BigInteger e, BigInteger m) {
        if (m.signum() == 0) {
            throw Slots.valueError("pow() 3rd argument cannot be 0");
        }
        boolean floorRemainder = m.signum() < 0;
        m = m.abs();
        BigInteger answer;
        if (e.signum() < 0) {
            if (!b.gcd(m).equals(BigInteger.ONE)) {
                throw Slots.valueError("base is not invertible for the given modulus");
            }
            answer = b.mod(m).modInverse(m).modPow(e.negate(), m);
        } else {
            answer = b.mod(m).modPow(e, m);
        }
        return PyInt.of(floorRemainder && answer.signum() != 0 ? answer.subtract(m) : answer);
    }

    /**
     * {@code long_pow} as {@code int} files it, with the declines the operator never shows.
     *
     * <p>{@code CHECK_BINOP} runs over the pair first and then {@code PyLong_Check} over the modulus, which
     * is allowed to be {@code None} — {@code int.__pow__(2, 3, None)} is eight, and {@code int.__pow__(2,
     * 3, 4.0)} declines although {@code pow(2, 3, 4.0)} reaches the float's own three-argument power. A
     * negative exponent with no modulus hands the pair to the float slot, which is how {@code int.__pow__(2,
     * -1)} is a half and {@code int.__pow__(0, -1)} is the error about zero.
     */
    static PyObject integerPower(PyObject base, PyObject exponent, PyObject modulus) {
        BigInteger b = Numbers.asInteger(base);
        BigInteger e = Numbers.asInteger(exponent);
        if (b == null || e == null) {
            return PyNotImplemented.INSTANCE;
        }
        boolean modulo = modulus != null && modulus != PyNone.INSTANCE;
        BigInteger m = Numbers.asInteger(modulus);
        if (modulo && m == null) {
            return PyNotImplemented.INSTANCE;
        }
        if (!modulo) {
            return Numeric.apply(BinaryOp.POW, PyInt.of(b), PyInt.of(e));
        }
        return modularPower(b, e, m);
    }

    private static boolean isNumber(PyObject value) {
        return value instanceof PyInt
                || value instanceof PyBool
                || value instanceof PyFloat
                || value instanceof PyComplex;
    }

    /**
     * {@code round(number, ndigits=None)}.
     *
     * <p>One question, asked the way {@code builtin_round_impl} asks it: {@code __round__} on the value's
     * <em>own type</em>, and then the place as the only argument when one was named and no argument at all
     * when it was {@code None}. The runtime does not know or care which built-in type the value has —
     * {@code round(1.5)} reaches the same {@code float.__round__} that {@code dir(float)} files, and a class
     * Python writes is asked through the very same door, so the three answers a {@code float} gives are the
     * three its method table gives.
     *
     * <p>Two shapes fall out of that one lookup. It walks the type only, never the instance dictionary, so
     * a value that files a {@code __round__} of its own under the name is still rounded by its class. And a
     * name that is present but useless is not a missing name: {@code __round__ = None} is <em>called</em>,
     * and the complaint is that {@code 'NoneType' object is not callable} — the sentence about a type that
     * "doesn't define {@code __round__}" is reserved for a lookup that found nothing at all, and names the
     * type as its {@code tp_name} spells it, cut at a hundred characters because that is what the
     * formatter is told to do with it.
     *
     * <p>The receiver is asked before the place is read, so {@code round('x', 'y')} complains about the
     * string having no {@code __round__} and never gets as far as the place it was handed.
     */
    private static PyObject round(PyObject[] args, String[] kwNames) {
        Arguments.atMostQualified("round", args, kwNames, 2);
        PyObject[] given = Arguments.fill("round", args, kwNames, ROUND_NAMES, ROUND_DEFAULTS);
        Arguments.required("round", ROUND_NAMES, given, 0);
        PyObject value = given[0];
        PyObject place = given[1];
        PyType owner = value.getType();
        PyObject declared = Specials.member(owner, "__round__");
        if (declared == null) {
            throw Slots.typeError("type %.100s doesn't define __round__ method", owner.name());
        }
        PyObject method = Descriptors.get(declared, value, owner);
        return place == null || place == PyNone.INSTANCE
                ? Slots.call(method, new PyObject[0], null)
                : Slots.call(method, new PyObject[] {place}, null);
    }
}
