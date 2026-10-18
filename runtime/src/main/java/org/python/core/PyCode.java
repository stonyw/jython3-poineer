package org.python.core;

/**
 * A compiled code object, as the interpreter sees it.
 *
 * This is an interface on purpose (decision D2 in PLAN.md). Stage A builds implementations of it by
 * reading CPython 3.14 {@code marshal} streams; Stage B will build them from a native parser. No
 * wordcode, line-table or exception-table encoding may appear here, because those are properties of
 * one front-end's output format, not of the VM. The two stages do share an opcode set — that is the
 * interpreter's instruction set, not a format detail.
 *
 * Fields derived from a specific format's variable layout are here only where the instruction set
 * needs them: {@code co_varnames} and friends come from the CPython localsplus array, and the array
 * is what {@code LOAD_FAST} and {@code LOAD_DEREF} index, so a {@code PyCode} has to answer for both.
 * The derivation itself is the front-end's business and is tested there.
 */
public interface PyCode {

    /** {@code CO_OPTIMIZED}: the frame has its own locals rather than sharing a module's. */
    int CO_OPTIMIZED = 0x1;

    /** A trailing {@code *args} parameter, which collects what the caller gave beyond the rest. */
    int CO_VARARGS = 0x4;

    /** A trailing {@code **kwargs} parameter, which collects the leftover keywords. */
    int CO_VARKEYWORDS = 0x8;

    String fileName();

    String name();

    String qualName();

    int argCount();

    int posOnlyArgCount();

    int kwOnlyArgCount();

    int stackSize();

    /** The {@code co_flags} bit field: generator, coroutine, nested scope and so on. */
    int flags();

    int firstLine();

    /** {@code co_consts}: a tuple, indexed by {@code LOAD_CONST}. */
    PyTuple constants();

    /** {@code co_names}: a tuple of interned strings, indexed by name-using opcodes. */
    PyTuple names();

    /**
     * {@code co_varnames}: the names that are plain locals, in slot order, parameters first. This is
     * the parameter list Python calls see, and it is not the same list as {@link #localNames()} —
     * a captured parameter belongs to both, and to a cell as well.
     */
    PyTuple varNames();

    /** {@code co_cellvars}, the locals this scope shares downward. */
    PyTuple cellVars();

    /** {@code co_freevars}, the locals this scope shares upward. */
    PyTuple freeVars();

    /**
     * The names of the frame's variable slots, in the order {@code LOAD_FAST} and {@code LOAD_DEREF}
     * index them: locals, cells and free variables each appear exactly once, in the front-end's own
     * order.
     */
    PyTuple localNames();

    /** How many slots {@link #localNames()} has, which is how many a frame must reserve. */
    int localCount();

    CodeInstructions instructions();
}
