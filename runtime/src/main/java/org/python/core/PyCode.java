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
     * How many code units the body occupies, inline caches and all, which is CPython's {@code Py_SIZE(co)}.
     *
     * <p>Two names of {@code code} answer it and nothing else can: {@code __sizeof__} multiplies it by the
     * width of a unit to measure a struct this runtime does not have, and {@code code_richcompare} compares
     * it before it compares the units themselves. {@link #instructions()} counts the instructions a driver
     * runs, which is a smaller number wherever an opcode has a cache behind it.
     */
    int unitCount();

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

    /**
     * The eighteen values a code object is built from, in the order and with the types CPython's own builder
     * takes them: six counts, the instruction stream, the two tables of values and of names, the three runs
     * of locals, the file and the two names, the line the body opens on, and the two byte tables.
     *
     * <p>The three byte runs are here as the octets a format writes rather than as anything read from them,
     * which is the one place {@link PyCode} carries a format's own bytes: a builder has to be handed what a
     * reader produced, and {@code co_code}, {@code co_linetable} and {@code co_exceptiontable} are what
     * {@link PyCode#instructions()}, {@code co_lines()} and {@link PyCode#handlerFor} were decoded from.
     * {@link #newCode} is the only reader of these three, and the one route to it is {@code replace()}, so
     * the two ends of that trade are a front-end's and nothing in the interpreter has to look at a byte.
     *
     * @param argCount {@code co_argcount}, the parameters a call binds by position
     * @param posOnlyArgCount {@code co_posonlyargcount}, of those, the ones with no name
     * @param kwOnlyArgCount {@code co_kwonlyargcount}, the parameters after {@code *}
     * @param nLocals {@code co_nlocals}, the count the three name runs are checked against
     * @param stackSize {@code co_stacksize}, the deepest the body pushes
     * @param flags {@code co_flags}, the bits that say what kind of body this is
     * @param code {@code co_code}, the instruction stream in the format's own packing
     * @param constants {@code co_consts}
     * @param names {@code co_names}
     * @param varNames {@code co_varnames}, the names that are plain locals
     * @param freeVars {@code co_freevars}, the cells this scope reads from outside
     * @param cellVars {@code co_cellvars}, the cells this scope makes for others
     * @param fileName {@code co_filename}
     * @param name {@code co_name}
     * @param qualName {@code co_qualname}
     * @param firstLine {@code co_firstlineno}
     * @param lineTable {@code co_linetable}, the positions as one format packs them
     * @param exceptionTable {@code co_exceptiontable}, the handlers as one format packs them
     */
    record Fields(
            int argCount,
            int posOnlyArgCount,
            int kwOnlyArgCount,
            int nLocals,
            int stackSize,
            int flags,
            PyBytes code,
            PyTuple constants,
            PyTuple names,
            PyTuple varNames,
            PyTuple freeVars,
            PyTuple cellVars,
            String fileName,
            String name,
            String qualName,
            int firstLine,
            PyBytes lineTable,
            PyBytes exceptionTable) {}

    /**
     * A code object in this one's format, built from {@code fields}.
     *
     * <p>CPython's builder is a C function that every front-end reaches the same way; a {@link PyCode} has
     * to answer for it because the values that make a table are the values of whichever table this object
     * was read from, and the runtime that copies one — {@code code.replace()} — cannot name a class in a
     * module it may never load. The receiver is therefore only the route to the format, and everything it
     * holds is asked for again through {@code fields}.
     *
     * <p>What the C works out from these values is the localsplus array, and what it refuses is whatever
     * the values cannot make: the refusals are its own, in its own order, because a program that builds a
     * table by hand hears the reference's sentence rather than a Java one.
     */
    PyCode newCode(Fields fields);
}
