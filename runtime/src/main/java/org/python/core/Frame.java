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

    /**
     * The generator this activation belongs to, or null while an ordinary call owns it.
     *
     * <p>Owning is more than keeping: {@code f_generator} answers the owner, and {@code frame.clear()}
     * asks it what state it is in rather than deciding from the loop, because a generator keeps a frame
     * after the run that parked it. Ownership also explains why this frame is never linked under the code
     * that resumed it — CPython's {@code _PyFrame_Copy} leaves {@code previous} NULL and {@code
     * YIELD_VALUE} unlinks the frame on the way out — so {@code f_back} of a generator's frame answers
     * {@code None} however it is reached.
     */
    PyGenerator generator;

    /**
     * The frame that called this one, which is what makes a Python stack exist.
     *
     * <p>CPython keeps the same link as {@code frame->previous} for the interpreter and {@code f_back}
     * for Python, and needs it for two things: a traceback is one entry per frame an error travelled
     * out of, so the entries have to be read back down the chain, and {@code tb_frame.f_back} is a name
     * Python asks. Null only for the outermost frame a run starts, which is where {@code f_back} reads
     * {@code None}.
     */
    private Frame back;

    /**
     * This activation as Python sees it, made the first time anything asks.
     *
     * <p>One per frame, not one per ask, because CPython materialises the {@code PyFrameObject} once and
     * every traceback entry for this activation holds that same object: {@code a.tb_frame is b.tb_frame}
     * is true for two entries of one call, and {@code fr.f_back is} the frame a caller's entry names is
     * true as well.
     */
    private PyFrame frameObject;

    /** Whether this frame has run its last instruction, which is what {@code frame.clear()} asks. */
    private boolean finished;

    /** Whether {@code clear()} has already dropped this frame's names, which {@code f_locals} reports. */
    private boolean cleared;

    /** Whether this frame is between two runs, which is the state a generator leaves it in. */
    private boolean parked;

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

    /** The frame whose call this one is an activation of, or null for the outermost one. */
    Frame back() {
        return back;
    }

    /**
     * Link this frame under the one that called it, as CPython does when it pushes a frame.
     *
     * <p>Set by {@link Interpreter#run}, which is where the caller is known: the Java recursion that runs
     * a callee keeps the caller in a thread-local, and the link a traceback walks is that same one.
     */
    void setBack(Frame caller) {
        this.back = caller;
    }

    /**
     * {@code f_locals}, as the frame's own view of its names.
     *
     * <p>A module or class-body frame answers with the dictionary it writes its names into, which is the
     * live one — {@code fr.f_locals is fr.f_globals} is true at module level because CPython's frame
     * holds the same pointer twice. A function frame has no dictionary, and CPython builds a proxy over
     * its slots on request; this reads the slots into a new dict in the same order, following a cell to
     * what it holds and leaving a name out while its slot is empty.
     */
    PyDict localValues() {
        if (cleared) {
            return PyDict.empty();
        }
        if (locals != null) {
            return locals;
        }
        PyDict names = PyDict.empty();
        PyTuple localsPlus = code.localNames();
        for (int i = 0; i < localsPlus.size() && i < slots.length; i++) {
            PyObject value = slots[i] instanceof PyCell cell ? cell.value() : slots[i];
            if (value != null) {
                names.put((PyStr) localsPlus.at(i), value);
            }
        }
        return names;
    }

    // --- frame object ----------------------------------------------------------------------

    /**
     * This activation as a Python value, made once and kept.
     *
     * <p>Every traceback entry for this frame hands out the same object, and so does {@code f_back} from
     * the entry above it, which is the identity CPython's one {@code frame_obj} per activation gives.
     */
    PyFrame frameObject() {
        if (frameObject == null) {
            frameObject = new PyFrame(this);
        }
        return frameObject;
    }

    /** Called once this frame's loop is over, so that {@code frame.clear()} can tell it from a live one. */
    void finish() {
        finished = true;
    }

    /** Whether Python is still running this activation. */
    boolean isFinished() {
        return finished;
    }

    /**
     * Drop every name this frame holds, which is all {@code frame.clear()} does to a finished frame.
     *
     * <p>The slots go to null — including the cells, so a closure made here loses its reference the way
     * CPython's {@code frame_tp_clear} does — and the dictionary a module or class-body frame shared
     * stops being the frame's own answer. It is not emptied: CPython clears the frame's <em>pointer</em>
     * to it, so {@code f_locals} reads back an empty dict while {@code f_globals} still holds every name
     * the module has.
     */
    void clearLocals() {
        java.util.Arrays.fill(slots, null);
        cleared = true;
    }

    // --- instruction pointer -------------------------------------------------------------

    int ip() {
        return ip;
    }

    void setIp(int ip) {
        this.ip = ip;
    }

    /**
     * Leave this frame at {@code index}, with nothing of the run that parked it left behind.
     *
     * <p>The three pointers land on the same instruction on purpose, because CPython's {@code instr_ptr}
     * does: both {@code RETURN_GENERATOR} and {@code YIELD_VALUE} step past themselves before they hand the
     * frame over, so what a parked frame waits on is the {@code POP_TOP} after the one that made it, or the
     * {@code RESUME} after the yield that stopped it. That instruction is what the next entry runs, what
     * {@code f_lasti} gives back and what a traceback entry names — and the line a report prints for it is
     * the yield's, because the compiler leaves the resume on the line the yield was written on.
     *
     * <p>Unlinking from the caller is the other half. A yield is an exit from the interpreter's stack, so
     * nothing above this frame is there any more; the generator that owns it is what keeps it alive.
     */
    void park(int index) {
        parked = true;
        setIp(index);
        setExecuting(index);
        back = null;
    }

    /**
     * Move the lookup site back onto the instruction that parked this frame, as an entry with an exception
     * already in flight does.
     *
     * <p>{@code _PyEval_EvalFrame} with the throw flag set skips the resume and goes straight to the
     * handler table, and the table is searched at {@code next_instr - 1}: the yield, not the resume the
     * frame waits at. That is what runs a {@code finally} written around a yield when the exception comes
     * in from outside, and what leaves a throw into a generator that never started with nowhere to be
     * caught. The reported site stays on the resume, because a traceback entry keeps the offset the frame
     * carries rather than the one the table was read at.
     *
     * <p>Both park sites are instructions that carry no cache entries of their own, so the unit before one
     * is always the start of the instruction before it.
     */
    void lookUpFromPreviousUnit() {
        executing = instructions().indexOfUnitAt(instructions().unitOffsetAt(executing) - 1);
    }

    /** Whether this frame is waiting to be entered again, rather than running or finished. */
    boolean isParked() {
        return parked;
    }

    /** Take this frame back off the park, which is the first thing an entry does. */
    void unpark() {
        parked = false;
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

    /**
     * The reported site as a traceback and {@code f_lasti} keep it: in bytes.
     *
     * <p>CPython's one field answers both — {@code instr_ptr} minus the start of the code, times the
     * width of a unit — and it keeps answering it after the frame has run its last instruction, which is
     * why a dead frame reached through {@code tb_frame} still names where it stopped.
     */
    int lasti() {
        return instructions().lastiAt(raisedAt);
    }

    /**
     * {@code f_lineno}: the line the reported site was compiled from, or -1 where it has no line.
     *
     * <p>Live rather than remembered, exactly as CPython reads it: a frame that caught an exception and
     * went on executing reports the line it is on now, not the one the traceback entry below it names.
     */
    int line() {
        return instructions().lineAtLasti(lasti());
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
     * The cell {@code co_freevars} names {@code __class__}, or null when this frame's code has no such
     * free variable.
     *
     * <p>A no-argument {@code super()} reads it: the class body made the cell, the compiler handed it
     * down to every function written inside that body, and {@code COPY_FREE_VARS} placed it in the
     * trailing slot the moment this frame started. The cell can be there and still empty, which is a
     * different complaint, so the cell itself comes back rather than its contents.
     */
    PyCell classVar() {
        PyTuple free = code.freeVars();
        int first = code.localCount() - free.size();
        for (int i = 0; i < free.size(); i++) {
            if ("__class__".equals(((PyStr) free.at(i)).getValue())) {
                return slots[first + i] instanceof PyCell cell ? cell : null;
            }
        }
        return null;
    }

    /**
     * The value a method calls {@code self} and a classmethod calls {@code cls}, or null when the frame
     * has deleted it.
     *
     * <p>Slot 0, because that is where CPython's {@code super()} reads it out of {@code f_localsplus} —
     * and a cell when the parameter is also a captured one, which is how a method that stores {@code
     * self} into a variable used by a nested function still hands it over. An emptied cell reads back
     * null like a deleted slot, which is why the caller asks rather than assuming a frame with a
     * parameter has a value in it.
     */
    PyObject firstArgument() {
        PyObject value = slots[0];
        return value instanceof PyCell cell ? cell.value() : value;
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
