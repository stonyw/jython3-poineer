package org.python.core;

import java.util.Map;

/**
 * The five numbers {@code type} reports about a class's own layout: {@code __flags__},
 * {@code __basicsize__}, {@code __itemsize__}, {@code __dictoffset__} and
 * {@code __weakrefoffset__}.
 *
 * <p>CPython reads them straight out of the {@code PyTypeObject} a class was built from, so they say
 * what a class is as much as what it has: {@code Py_TPFLAGS_HEAPTYPE} separates a class written in
 * Python from one the binary shipped, {@code Py_TPFLAGS_BASETYPE} says whether a subclass may name it,
 * {@code Py_TPFLAGS_IS_ABSTRACT} is the one bit a setter can still flip, and the managed-dictionary
 * bits describe where a value's attributes live. {@link #heapFlags} composes those from the class this
 * runtime actually made; it does not invent a second set of numbers.
 *
 * <p>A compiled-in type keeps the flags it was declared with. Only the types the corpora and the
 * standard library ask about are spelled out in {@link #BUILTIN_FLAGS}; every other one is composed
 * from the same rules, which is exact for the bits this runtime models and names the rest of the
 * deviation where the table stops.
 *
 * <p>The sizes and offsets are the reference interpreter's 64-bit ABI, measured with the attributes
 * themselves on CPython 3.14.10. Nothing here allocates storage — {@code __basicsize__} is what
 * {@code __bases__} assignment compares, and {@link PyClass} already tracks that number for the classes
 * it makes. The two offsets name where a value's dictionary and weak references live: a managed slot
 * CPython reports as a negative sentinel, a real field it reports as the byte offset, and no storage at
 * all as nought.
 */
final class TypeLayout {

    private TypeLayout() {}

    // Objects/object.h, CPython 3.14.
    private static final long STATIC_BUILTIN = 1L << 1;
    private static final long INLINE_VALUES = 1L << 2;
    private static final long MANAGED_WEAKREF = 1L << 3;
    private static final long MANAGED_DICT = 1L << 4;
    private static final long SEQUENCE = 1L << 5;
    private static final long MAPPING = 1L << 6;
    private static final long IMMUTABLETYPE = 1L << 8;
    private static final long HEAPTYPE = 1L << 9;
    private static final long BASETYPE = 1L << 10;
    static final long READY = 1L << 12;
    static final long HAVE_GC = 1L << 14;
    static final long IS_ABSTRACT = 1L << 20;
    static final long MATCH_SELF = 1L << 22;
    private static final long ITEMS_AT_END = 1L << 23;
    private static final long LONG_SUBCLASS = 1L << 24;
    private static final long LIST_SUBCLASS = 1L << 25;
    private static final long TUPLE_SUBCLASS = 1L << 26;
    private static final long BYTES_SUBCLASS = 1L << 27;
    private static final long UNICODE_SUBCLASS = 1L << 28;
    private static final long DICT_SUBCLASS = 1L << 29;
    private static final long BASE_EXC_SUBCLASS = 1L << 30;
    private static final long TYPE_SUBCLASS = 1L << 31;
    private static final long HAVE_VECTORCALL = 1L << 11;

    /** A compiled-in type's whole {@code tp_flags}, as the reference reports it. */
    private static final Map<String, Long> BUILTIN_FLAGS =
            Map.ofEntries(
                    Map.entry("object", 5378L),
                    Map.entry("type", 2155896066L),
                    Map.entry("int", 20976898L),
                    Map.entry("bool", 20975874L),
                    Map.entry("float", 4199682L),
                    Map.entry("complex", 5378L),
                    Map.entry("str", 272635138L),
                    Map.entry("bytes", 138417410L),
                    Map.entry("bytearray", 4199682L),
                    Map.entry("list", 37770530L),
                    Map.entry("tuple", 71324962L),
                    Map.entry("dict", 541087042L),
                    Map.entry("set", 4216066L),
                    Map.entry("frozenset", 4216066L),
                    Map.entry("range", 4386L),
                    Map.entry("slice", 20738L),
                    Map.entry("property", 21762L),
                    Map.entry("staticmethod", 21762L),
                    Map.entry("classmethod", 21762L),
                    Map.entry("super", 21762L),
                    Map.entry("ExceptionGroup", 1073763848L),
                    Map.entry("_local", 22272L));

    /**
     * {@code tp_basicsize} for the compiled-in types a class may be built over.
     *
     * <p>The exception family is measured by its C struct, so the sizes differ even though the flags
     * do not: {@code OSError} carries five extra fields, {@code SyntaxError} five more, and the
     * groups one sequence each.
     */
    private static final Map<String, Integer> BUILTIN_SIZES =
            Map.ofEntries(
                    Map.entry("object", 16),
                    Map.entry("type", 936),
                    Map.entry("int", 24),
                    Map.entry("bool", 24),
                    Map.entry("float", 24),
                    Map.entry("complex", 32),
                    Map.entry("str", 64),
                    Map.entry("bytes", 33),
                    Map.entry("bytearray", 56),
                    Map.entry("list", 40),
                    Map.entry("tuple", 32),
                    Map.entry("dict", 48),
                    Map.entry("module", 56),
                    Map.entry("_local", 56),
                    Map.entry("set", 200),
                    Map.entry("frozenset", 200),
                    Map.entry("range", 48),
                    Map.entry("slice", 40),
                    Map.entry("property", 64),
                    Map.entry("staticmethod", 32),
                    Map.entry("classmethod", 32),
                    Map.entry("super", 40),
                    Map.entry("BaseException", 72),
                    Map.entry("Exception", 72),
                    Map.entry("ArithmeticError", 72),
                    Map.entry("AssertionError", 72),
                    Map.entry("AttributeError", 88),
                    Map.entry("BaseExceptionGroup", 96),
                    Map.entry("ExceptionGroup", 96),
                    Map.entry("BlockingIOError", 112),
                    Map.entry("BrokenPipeError", 112),
                    Map.entry("BufferError", 72),
                    Map.entry("BytesWarning", 72),
                    Map.entry("ChildProcessError", 112),
                    Map.entry("ConnectionAbortedError", 112),
                    Map.entry("ConnectionError", 112),
                    Map.entry("ConnectionRefusedError", 112),
                    Map.entry("ConnectionResetError", 112),
                    Map.entry("DeprecationWarning", 72),
                    Map.entry("EOFError", 72),
                    Map.entry("EncodingWarning", 72),
                    Map.entry("FileExistsError", 112),
                    Map.entry("FileNotFoundError", 112),
                    Map.entry("FloatingPointError", 72),
                    Map.entry("FutureWarning", 72),
                    Map.entry("GeneratorExit", 72),
                    Map.entry("ImportError", 104),
                    Map.entry("ImportWarning", 72),
                    Map.entry("IndentationError", 144),
                    Map.entry("IndexError", 72),
                    Map.entry("InterruptedError", 112),
                    Map.entry("IsADirectoryError", 112),
                    Map.entry("KeyError", 72),
                    Map.entry("KeyboardInterrupt", 72),
                    Map.entry("LookupError", 72),
                    Map.entry("MemoryError", 72),
                    Map.entry("ModuleNotFoundError", 104),
                    Map.entry("NameError", 80),
                    Map.entry("NotADirectoryError", 112),
                    Map.entry("NotImplementedError", 72),
                    Map.entry("OSError", 112),
                    Map.entry("OverflowError", 72),
                    Map.entry("PendingDeprecationWarning", 72),
                    Map.entry("PermissionError", 112),
                    Map.entry("ProcessLookupError", 112),
                    Map.entry("PythonFinalizationError", 72),
                    Map.entry("RecursionError", 72),
                    Map.entry("ReferenceError", 72),
                    Map.entry("ResourceWarning", 72),
                    Map.entry("RuntimeError", 72),
                    Map.entry("RuntimeWarning", 72),
                    Map.entry("StopAsyncIteration", 72),
                    Map.entry("StopIteration", 80),
                    Map.entry("SyntaxError", 144),
                    Map.entry("SyntaxWarning", 72),
                    Map.entry("SystemError", 72),
                    Map.entry("SystemExit", 80),
                    Map.entry("TabError", 144),
                    Map.entry("TimeoutError", 112),
                    Map.entry("TypeError", 72),
                    Map.entry("UnboundLocalError", 80),
                    Map.entry("UnicodeDecodeError", 112),
                    Map.entry("UnicodeEncodeError", 112),
                    Map.entry("UnicodeError", 72),
                    Map.entry("UnicodeTranslateError", 112),
                    Map.entry("UnicodeWarning", 72),
                    Map.entry("UserWarning", 72),
                    Map.entry("ValueError", 72),
                    Map.entry("Warning", 72),
                    Map.entry("ZeroDivisionError", 72),
                    Map.entry("_IncompleteInputError", 144));

    /** {@code tp_itemsize}: the types whose values carry a variable-length payload at the end. */
    private static final Map<String, Integer> BUILTIN_ITEMSIZES =
            Map.ofEntries(
                    Map.entry("type", 40),
                    Map.entry("int", 4),
                    Map.entry("bool", 4),
                    Map.entry("bytes", 1),
                    Map.entry("tuple", 8));

    /** The managed dictionary's {@code __dictoffset__} sentinel. */
    private static final int MANAGED_DICT_OFFSET = -1;

    /** The managed weak references' {@code __weakrefoffset__} sentinel. */
    private static final int MANAGED_WEAKREF_OFFSET = -32;

    /** The real dictionary field {@code BaseException} was compiled with. */
    private static final int EXCEPTION_DICT_OFFSET = 16;

    /** The real dictionary field {@code type} was compiled with. */
    private static final int TYPE_DICT_OFFSET = 264;

    /** The real weak-reference field {@code type} was compiled with. */
    private static final int TYPE_WEAKREF_OFFSET = 368;

    /** The real weak-reference field {@code set} and {@code frozenset} were compiled with. */
    private static final int SET_WEAKREF_OFFSET = 192;

    /**
     * The real weak-reference field {@code _thread._local} was compiled with, which a subclass inherits
     * whether or not it declares {@code __slots__}: {@code localobject} carries the list itself rather
     * than reaching for a managed slot.
     */
    private static final int LOCAL_WEAKREF_OFFSET = 32;

    /** Whether a class statement wrote {@code type}, which is CPython's {@code Py_TPFLAGS_HEAPTYPE}. */
    static boolean heap(PyType type) {
        return (flags(type) & HEAPTYPE) != 0;
    }

    /**
     * Whether a value of this class keeps its attributes in CPython's inline values array, which is
     * {@code Py_TPFLAGS_INLINE_VALUES}. {@link PyInstance} reads it to know whether a value starts out
     * with such an array and therefore answers {@code _testinternalcapi.has_inline_values} with true.
     */
    static boolean inlineValues(PyType type) {
        return (flags(type) & INLINE_VALUES) != 0;
    }

    /** {@code type.__flags__}. */
    static long flags(PyType type) {
        if (type instanceof PyClass held) {
            return heapFlags(held);
        }
        Long known = BUILTIN_FLAGS.get(type.name());
        if (known != null) {
            return known;
        }
        return declaredFlags(type);
    }

    /**
     * The flags of a class Python wrote: its parent's bits, with the compiled-in and managed-storage
     * ones replaced by what this class actually has.
     *
     * <p>{@code type_new} sets {@code HEAPTYPE} and {@code HAVE_GC} on every class it makes; the
     * managed-dictionary bits come from {@code type_new_slots_bases}, which decides where a value's
     * attributes and weak references live, and they are the one part of the parent's flags that must
     * not simply be inherited. Everything else — the subclass family bits, {@code SEQUENCE},
     * {@code MAPPING}, {@code MATCH_SELF} — does survive, which is why a subclass of {@code int}
     * keeps {@code LONG_SUBCLASS} and a subclass of {@code tuple} keeps {@code SEQUENCE}.
     */
    private static long heapFlags(PyClass held) {
        PyType base = held.parent();
        long flags = flags(base);
        flags &=
                ~(STATIC_BUILTIN
                        | IMMUTABLETYPE
                        | MANAGED_DICT
                        | MANAGED_WEAKREF
                        | INLINE_VALUES
                        | IS_ABSTRACT);
        flags |= HEAPTYPE | HAVE_GC;
        flags |= storageBits(base, held.hasValueDict(), held.hasWeakRefList());
        if (held.isAbstract()) {
            flags |= IS_ABSTRACT;
        }
        return flags;
    }

    /**
     * The managed-storage bits a value of this class has.
     *
     * <p>A base Python wrote answers the same way, by walking to the compiled-in root of its
     * chain: {@code class D(Plain)} gets all three bits again even though {@code D} introduces no
     * storage, because the bits describe the class's own layout rather than what it added, and
     * {@code class D(Slotted)} still has none. A base over an exception or over {@code type} keeps
     * its family's answer. A compiled-in base is asked directly, because CPython's answer is a
     * property of the C struct: a subclass of {@code int}, {@code bytes} or {@code tuple} gets a
     * managed dictionary but no inline values and no weak references, a subclass of {@code set} or
     * {@code frozenset} gets inline values with its dictionary but no weak references, an exception
     * gets only the weak references (its dictionary is the ordinary {@code tp_dictoffset} field), a
     * subclass of {@code type} keeps its attributes in the type's own dictionary, and every other
     * base — {@code object}, {@code float}, {@code complex}, {@code str}, {@code list},
     * {@code dict}, {@code bytearray}, {@code property} — gets all three.
     */
    private static long storageBits(PyType base, boolean hasDict, boolean hasWeak) {
        PyType root = root(base);
        if (root.matches(PyType.TYPE)) {
            return 0;
        }
        if (root.matches(ExceptionTypes.BASE_EXCEPTION)) {
            return hasWeak ? MANAGED_WEAKREF : 0;
        }
        if (root == PyType.LOCAL) {
            // `localobject` keeps its weak reference list in a real field, so the managed bit never
            // appears; the dictionary, when the class stores one, is managed as usual.
            return hasDict ? MANAGED_DICT | INLINE_VALUES : 0;
        }
        long bits = 0;
        if (hasDict) {
            bits |= MANAGED_DICT;
            if (root != PyType.INT && root != PyType.BYTES && root != PyType.TUPLE) {
                bits |= INLINE_VALUES;
            }
        }
        if (hasWeak && !weakless(root)) {
            bits |= MANAGED_WEAKREF;
        }
        return bits;
    }

    /** The compiled-in type a chain of written classes bottoms out at. */
    private static PyType root(PyType type) {
        while (type instanceof PyClass held && held.parent() != null) {
            type = held.parent();
        }
        return type;
    }

    /** The compiled-in bases whose values cannot carry a weak reference, so a subclass has none. */
    private static boolean weakless(PyType base) {
        return base == PyType.INT
                || base == PyType.BYTES
                || base == PyType.TUPLE
                || base == PyType.SET
                || base == PyType.FROZENSET;
    }

    /**
     * The bits of a compiled-in type the table does not spell out.
     *
     * <p>Every such type ships ready, immutable and static; whether it may be a base is the one
     * property this runtime already knows (a type outside {@code object}, the exception family,
     * {@code type}, {@code int} and {@code str} cannot be named in a class statement) but CPython's
     * bit is not the same question — {@code bool}, {@code range} and {@code slice} are the ones the
     * corpus pins by name. The family and pattern bits are composed the way the type declares them.
     */
    private static long declaredFlags(PyType type) {
        long flags = READY | IMMUTABLETYPE | STATIC_BUILTIN | BASETYPE;
        if (type.matches(PyType.INT)) {
            flags |= LONG_SUBCLASS;
        } else if (type.matches(PyType.STR)) {
            flags |= UNICODE_SUBCLASS;
        } else if (type.matches(PyType.BYTES)) {
            flags |= BYTES_SUBCLASS;
        } else if (type.matches(PyType.TUPLE)) {
            flags |= TUPLE_SUBCLASS;
        } else if (type.matches(PyType.LIST)) {
            flags |= LIST_SUBCLASS;
        } else if (type.matches(PyType.DICT)) {
            flags |= DICT_SUBCLASS;
        } else if (type.matches(ExceptionTypes.BASE_EXCEPTION)) {
            flags |= BASE_EXC_SUBCLASS | HAVE_GC;
        } else if (type.matches(PyType.TYPE)) {
            flags |= TYPE_SUBCLASS | ITEMS_AT_END | HAVE_VECTORCALL | HAVE_GC;
        }
        if (type.hasFeature(PyType.SEQUENCE)) {
            flags |= SEQUENCE;
        }
        if (type.hasFeature(PyType.MAPPING)) {
            flags |= MAPPING;
        }
        if (type.hasFeature(PyType.MATCH_SELF)) {
            flags |= MATCH_SELF;
        }
        return flags;
    }

    /** {@code type.__basicsize__}. */
    static int basicsize(PyType type) {
        if (type instanceof PyClass held) {
            return held.instanceSize();
        }
        Integer known = BUILTIN_SIZES.get(type.name());
        return known == null ? 16 : known;
    }

    /**
     * {@code type.__dictoffset__}: where a value's instance dictionary lives.
     *
     * <p>A class Python wrote keeps its dictionary in a managed slot, which CPython reports as
     * {@code -1}; the two families that brought a real field — an exception at 16 and {@code type} at
     * 264 — keep that field when subclassed, and a class that introduced no dictionary at all answers
     * nought. The same field tests decide it as the managed-dictionary bit in {@link #flags}.
     */
    static int dictoffset(PyType type) {
        if (type instanceof PyClass held) {
            PyType root = root(held);
            if (root.matches(PyType.TYPE)) {
                return TYPE_DICT_OFFSET;
            }
            if (root.matches(ExceptionTypes.BASE_EXCEPTION)) {
                return EXCEPTION_DICT_OFFSET;
            }
            return held.hasValueDict() ? MANAGED_DICT_OFFSET : 0;
        }
        if (type.matches(PyType.TYPE)) {
            return TYPE_DICT_OFFSET;
        }
        if (type.matches(ExceptionTypes.BASE_EXCEPTION)) {
            return EXCEPTION_DICT_OFFSET;
        }
        return 0;
    }

    /**
     * {@code type.__weakrefoffset__}: where a value's weak reference list lives.
     *
     * <p>The managed slot is reported as {@code -32}, which is likewise a sentinel rather than a byte
     * offset. Three compiled-in layouts are inherited as real fields instead: {@code type} at 368,
     * {@code set} and {@code frozenset} at 192, and {@code ExceptionGroup} reports the managed sentinel
     * because CPython gives it managed weak references. A value that cannot be weakly referenced —
     * {@code int}, {@code bytes} and {@code tuple} below, or a class that declared its own
     * {@code __slots__} without {@code __weakref__} — answers nought.
     */
    static int weakrefoffset(PyType type) {
        if (type instanceof PyClass held) {
            PyType root = root(held);
            if (root.matches(PyType.TYPE)) {
                return TYPE_WEAKREF_OFFSET;
            }
            if (root == PyType.SET || root == PyType.FROZENSET) {
                return SET_WEAKREF_OFFSET;
            }
            if (root == PyType.LOCAL) {
                return LOCAL_WEAKREF_OFFSET;
            }
            if (!held.hasWeakRefList() || weakless(root)) {
                return 0;
            }
            return MANAGED_WEAKREF_OFFSET;
        }
        if (type.matches(PyType.TYPE)) {
            return TYPE_WEAKREF_OFFSET;
        }
        if (type == PyType.SET || type == PyType.FROZENSET) {
            return SET_WEAKREF_OFFSET;
        }
        if (type == PyType.LOCAL) {
            return LOCAL_WEAKREF_OFFSET;
        }
        if (type == ExceptionTypes.EXCEPTION_GROUP) {
            return MANAGED_WEAKREF_OFFSET;
        }
        return 0;
    }

    /**
     * {@code type.__itemsize__}: the size of one item in a variable-length value.
     *
     * <p>A class Python wrote keeps its family's item size: a subclass of {@code int} carries
     * {@code int}'s four-byte digits and a subclass of {@code tuple} its eight-byte pointers,
     * although neither stores anything in this runtime.
     */
    static int itemsize(PyType type) {
        if (type instanceof PyClass held) {
            return itemsize(held.parent());
        }
        Integer known = BUILTIN_ITEMSIZES.get(type.name());
        return known == null ? 0 : known;
    }
}
