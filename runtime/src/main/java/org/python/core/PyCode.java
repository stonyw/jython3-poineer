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

    /** {@code CO_NEWLOCALS}: the frame gets its own locals dict, which is what a function body does. */
    int CO_NEWLOCALS = 0x2;

    /** A trailing {@code *args} parameter, which collects what the caller gave beyond the rest. */
    int CO_VARARGS = 0x4;

    /** A trailing {@code **kwargs} parameter, which collects the leftover keywords. */
    int CO_VARKEYWORDS = 0x8;

    /**
     * {@code CO_GENERATOR}: calling this code object answers a generator rather than running the body,
     * which is what {@code RETURN_GENERATOR} reads. A body compiled with it begins with that opcode, so
     * the driver never needs to consult this flag to know a frame came from a generator — only
     * {@code PyFunction} asks, and it asks the same way CPython's {@code _Py_MakeCoro} does.
     */
    int CO_GENERATOR = 0x20;

    /** {@code CO_COROUTINE}: the body of an {@code async def}, which is awaited rather than iterated. */
    int CO_COROUTINE = 0x80;

    /** {@code CO_ASYNC_GENERATOR}: an {@code async def} body with a yield in it. */
    int CO_ASYNC_GENERATOR = 0x200;

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

    /**
     * {@code co_lines()}: the source line of every run of instructions.
     *
     * <p>Each item is CPython's triple — the instruction offset the run starts at, the one it ends at,
     * and the line the run belongs to or {@code None} where it carries none. Offsets are the same ones a
     * traceback records, so an entry here is a range a {@code tb_lasti} falls inside.
     *
     * <p>Runs of one line are one item even when the front-end's own table writes them as several, which
     * is what makes this a different question from {@link CodeInstructions#lineAtLasti}: that names the
     * line an offset falls on, this names the runs.
     */
    PyTuple lineRanges();

    /**
     * {@code co_positions()}: the whole source range of every instruction, columns and all.
     *
     * <p>Each item is CPython's quadruple of start line, end line, start column and end column, with
     * {@code None} wherever the table names nothing — the unit of an inline cache, or one the compiler left
     * without a source range. The answer is one item per unit the table reaches rather than one per line, so
     * the caches behind an opcode repeat the range of the instruction they belong to, and a unit past the
     * last entry is not in it at all: CPython's iterator counts the units its table writes, never the length
     * of {@code co_code}.
     *
     * <p>These are the numbers a PEP 657 caret is drawn from. The report this runtime prints names only a
     * line, because a caret needs the source text beside the column, and that is Stage B's half of the
     * display.
     */
    PyTuple positions();

    /**
     * The handler that covers the instruction at {@code index}, or null where nothing guards it.
     *
     * <p>An exception is answered here by a table rather than by a block stack: when a frame has one to
     * deal with, the instruction it was executing names a handler, and that handler says where to resume,
     * how much of the value stack to discard, and whether to leave the offset of the raising instruction
     * on the stack under the exception so a {@code RERAISE} can put it back. {@code index} is that
     * instruction, which after a failed call is the call itself — so a handler written in the caller
     * covers what the callee raised.
     *
     * <p>How a front-end records its own ranges is its business (D2); what it has to answer is the
     * question the interpreter asks while unwinding.
     */
    Handler handlerFor(int index);

    /**
     * What an unwinding frame does about one exception.
     *
     * @param targetIndex the instruction the handler begins at
     * @param stackLevel how many values the frame keeps, counted from the bottom of its own stack
     * @param pushLasti whether to push the raising instruction's offset below the exception
     */
    record Handler(int targetIndex, int stackLevel, boolean pushLasti) {}
}
