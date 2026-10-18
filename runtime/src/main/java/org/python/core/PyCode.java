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
 * Fields derived from a specific format's variable layout (the CPython localsplus array, and so
 * {@code co_varnames}, {@code co_cellvars}, {@code co_freevars}) are added when an opcode needs
 * them, so that the derivation is written against a test rather than guessed.
 */
public interface PyCode {

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

    CodeInstructions instructions();
}
