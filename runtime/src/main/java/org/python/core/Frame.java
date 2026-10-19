package org.python.core;

/**
 * One activation of one code object: its variable slots, its value stack and its instruction pointer.
 *
 * The variable slots are CPython's {@code localsplus} array, indexed by the numbers {@code LOAD_FAST},
 * {@code STORE_DEREF} and friends carry. Cells live <em>in</em> that array rather than in a side
 * table, which is why {@code LOAD_FAST} on a captured variable hands over the cell itself and why
 * {@code MAKE_CELL} rewrites a slot in place.
 *
 * The value stack holds Java {@code null} where CPython holds a NULL stack reference — the one the
 * call opcodes leave under a callable, and the one {@code CALL_FUNCTION_EX} uses to say "no keyword
 * arguments". Nothing on the stack may be null by accident, so a slot that expects a value checks.
 */
public final class Frame {

    private final PyCode code;
    private final PyDict globals;
    /** {@code f_locals}: the module's dictionary at module level, null in optimized code. */
    private final PyDict locals;
    private final PyObject[] slots;

    private PyObject[] stack;
    private int sp;
    private int ip;

    /**
     * The instruction this frame is executing, which is CPython's {@code next_instr - 1}.
     *
     * <p>An exception is looked up by this: the row that covers the instruction that just failed names
     * the handler, and after a failed call the failing instruction is the call itself, not whatever the
     * callee was doing.
     */
    private int executing;

    /**
     * The instruction a report would name, which is CPython's {@code instr_ptr} and whose offset is
     * what {@code f_lasti} gives back.
     *
     * <p>The two are the same instruction until a handler re-raises: {@code RERAISE} moves this one
     * back to wherever the exception first failed, so a traceback and a re-entered handler speak of the
     * raise rather than of the instruction that repeated it. The lookup keeps using {@link #executing},
     * because a re-raise is answered by the rows around the re-raise.
     */
    private int raisedAt;

    /** The function whose closure fills this frame's free variables; null for a module frame. */
    PyFunction function;

    Frame(PyCode code, PyDict globals, PyObject[] slots) {
        this(code, globals, (code.flags() & PyCode.CO_OPTIMIZED) == 0 ? globals : null, slots);
    }

    /**
     * A frame whose names go into a dictionary the caller chose.
     *
     * <p>A class body is the case that needs it: its code object is unoptimized, so the rule above would
     * make its names land in the module, while CPython runs the body against the namespace the metaclass
     * prepared. The two dictionaries stay separate — {@code LOAD_NAME} reads the class dictionary first
     * and the module's only for a name the body has not defined.
     */
    Frame(PyCode code, PyDict globals, PyDict locals, PyObject[] slots) {
        this.code = code;
        this.globals = globals;
        this.locals = locals;
        this.slots = slots;
        // co_stacksize bounds the frame's own needs; the extra room is for the values a call opcode
        // has not yet moved into the callee.
        this.stack = new PyObject[Math.max(8, code.stackSize() + 8)];
    }

    public PyCode code() {
        return code;
    }

    public PyDict globals() {
        return globals;
    }

    /** The dictionary {@code LOAD_NAME} and {@code STORE_NAME} address, or null when there is none. */
    public PyDict locals() {
        return locals;
    }

    // --- instruction pointer -------------------------------------------------------------

    int ip() {
        return ip;
    }

    void setIp(int ip) {
        this.ip = ip;
    }

    /** The instruction an exception is looked up by: {@link #executing}, not the reported site. */
    int executing() {
        return executing;
    }

    /** Step onto instruction {@code index}; the reported site starts out the same instruction. */
    void setExecuting(int index) {
        this.executing = index;
        this.raisedAt = index;
    }

    /**
     * Put the reported site back where the exception first failed, as {@code RERAISE} does.
     *
     * @param unit the front-end's own offset of that instruction, which is the form a handler leaves it
     *     in below the exception
     */
    void restoreRaisedAt(int unit) {
        int index = instructions().indexOfUnitAt(unit);
        if (index < 0) {
            throw Slots.runtimeError("a handler left code unit " + unit + " as the reported site, "
                    + "which no instruction starts at (a bug in the interpreter)");
        }
        this.raisedAt = index;
    }

    /** The reported site, in the units a handler's lasti is pushed in. */
    int unitRaisedAt() {
        return instructions().unitOffsetAt(raisedAt);
    }

    CodeInstructions instructions() {
        return code.instructions();
    }

    /** Where the jump at {@code index} lands, in instruction indices. */
    int jumpTarget(int index) {
        return code.instructions().jumpTargetAt(index);
    }

    // --- value stack ---------------------------------------------------------------------

    void push(PyObject value) {
        if (sp == stack.length) {
            PyObject[] grown = new PyObject[stack.length * 2];
            System.arraycopy(stack, 0, grown, 0, stack.length);
            stack = grown;
        }
        stack[sp++] = value;
    }

    PyObject pop() {
        if (sp == 0) {
            throw Slots.runtimeError("underflow in the value stack (a bug in the interpreter)");
        }
        PyObject value = stack[--sp];
        stack[sp] = null;
        return value;
    }

    /** The value {@code depth} places below the top; {@code peek(0)} is the top. */
    PyObject peek(int depth) {
        if (depth < 0 || depth >= sp) {
            throw Slots.runtimeError("read past the bottom of the value stack (a bug in the interpreter)");
        }
        return stack[sp - 1 - depth];
    }

    /** Overwrite the value at {@code depth}, which is how {@code SWAP} is implemented. */
    void replace(int depth, PyObject value) {
        stack[sp - 1 - depth] = value;
    }

    int stackDepth() {
        return sp;
    }

    /**
     * Discard everything above {@code level}, which is how a handler gets the stack it was compiled
     * against.
     *
     * <p>The level is the handler's own stack depth counted from the bottom of this frame's stack, so
     * it is comparable with {@link #stackDepth()} without knowing anything about the code object.
     */
    void popTo(int level) {
        if (level > sp) {
            throw Slots.runtimeError("an exception handler for " + code.qualName() + " expects "
                    + level + " values on the value stack, which holds " + sp
                    + " (a bug in the interpreter)");
        }
        while (sp > level) {
            pop();
        }
    }

    /** The {@code n} values on top, in the order they were pushed, leaving them popped. */
    PyObject[] take(int n) {
        PyObject[] args = new PyObject[n];
        for (int i = 0; i < n; i++) {
            args[n - 1 - i] = pop();
        }
        return args;
    }

    // --- variable slots ------------------------------------------------------------------

    PyObject slot(int index) {
        return slots[index];
    }

    void setSlot(int index, PyObject value) {
        slots[index] = value;
    }

    PyObject[] slots() {
        return slots;
    }

    /** The name a slot holds, for the messages CPython prints when it is not bound. */
    String slotName(int index) {
        PyTuple names = code.localNames();
        return index < names.size() ? ((PyStr) names.at(index)).getValue() : "?";
    }

    /**
     * The error CPython raises for a read of slot {@code index} while its cell is empty: a free
     * variable is someone else's local, so it is a {@code NameError} about an enclosing scope, and
     * anything else is an {@code UnboundLocalError} about this one.
     */
    PyRaise unbound(int index) {
        String name = slotName(index);
        int free = code.freeVars().size();
        if (free > 0 && index >= code.localCount() - free) {
            return PyRaise.raise(
                    ExceptionTypes.NAME_ERROR,
                    "free variable '" + name + "' referenced before assignment in enclosing scope");
        }
        return PyRaise.raise(
                ExceptionTypes.UNBOUND_LOCAL_ERROR,
                "cannot access local variable '" + name + "' where it is not associated with a value");
    }

    // --- constant and name pools ---------------------------------------------------------

    PyObject constant(int index) {
        return code.constants().at(index);
    }

    String name(int index) {
        return ((PyStr) code.names().at(index)).getValue();
    }

    /**
     * The cell at {@code index}, which {@code LOAD_DEREF}, {@code STORE_DEREF} and {@code DELETE_DEREF}
     * all require: the compiler only emits them for slots it has made into cells.
     */
    PyCell cell(int index) {
        if (slots[index] instanceof PyCell cell) {
            return cell;
        }
        throw Slots.runtimeError(
                "variable '" + slotName(index) + "' is not a cell (a bug in the interpreter)");
    }
}
