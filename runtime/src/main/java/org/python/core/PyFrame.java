package org.python.core;

/**
 * One activation of one code object, as Python sees it.
 *
 * <p>This is not the interpreter's {@link Frame} — it is the object CPython materialises the first time
 * anything asks to look at a running activation, and keeps for the rest of that activation. The {@link
 * Frame} is the live thing: the slots, the value stack and the instruction pointer. Nothing is copied
 * here, because the whole point of the eleven {@code f_} names is that they answer for the activation as
 * it stands, and a copy would report a frame that stopped moving.
 *
 * <p>Two of those names are therefore read straight through to the live frame rather than stored: {@code
 * f_lineno} and {@code f_lasti} name whatever instruction the frame is on now, which is why the same
 * frame read from two places can disagree with the traceback entry that holds it — an entry froze its
 * offset when the error passed through, and a dead frame froze it by stopping.
 *
 * <p>CPython's frame object is a debugging surface, and the part of it that only a debugger drives is not
 * here: there is no trace function until {@code sys.settrace}, so {@code f_lineno} refuses every write.
 * That is what the name answers in the runtime CPython runs today with no tracer attached.
 */
public final class PyFrame extends PyObject {

    /** {@code frame.clear()}: CPython's own, including the refusal for a frame still running. */
    private static final PyNativeFunction CLEAR =
            PyNativeFunction.positional(
                    "clear",
                    args -> {
                        ((PyFrame) args[0]).clear();
                        return PyNone.INSTANCE;
                    });

    private final Frame frame;

    /** {@code f_trace}: any value at all, and the only name on a frame that accepts one. */
    private PyObject trace = PyNone.INSTANCE;

    private boolean traceLines = true;
    private boolean traceOpcodes;

    /** Made by {@link Frame#frameObject()}, never directly: one per activation is the whole design. */
    PyFrame(Frame frame) {
        super(PyType.FRAME);
        this.frame = frame;
    }

    /** The activation this is a view of, for the interpreter and the traceback report. */
    public Frame frame() {
        return frame;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "f_back" -> frame.back() == null ? PyNone.INSTANCE : frame.back().frameObject();
            // A code object is a Python value in CPython, and every front-end's is a PyObject here;
            // the interface is what the interpreter stops knowing at.
            case "f_code" -> (PyObject) frame.code();
            case "f_globals" -> frame.globals();
            case "f_builtins" -> PyRuntime.builtins();
            case "f_locals" -> frame.localValues();
            case "f_lineno" -> frame.line() < 0 ? PyNone.INSTANCE : PyInt.of(frame.line());
            case "f_lasti" -> PyInt.of(frame.lasti());
            case "f_generator" ->
                    frame.generator == null ? PyNone.INSTANCE : frame.generator;
            case "f_trace" -> trace;
            case "f_trace_lines" -> PyBool.of(traceLines);
            case "f_trace_opcodes" -> PyBool.of(traceOpcodes);
            case "clear" -> CLEAR.withReceiver(this);
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * {@code fr.name = value}: three names a Python program may write, and the rest CPython refuses.
     *
     * <p>The wording splits the way CPython's type tables split. The seven getsets with no setter answer
     * the generic complaint about an attribute that is not writable; {@code f_trace_lines} is a member of
     * {@code bool} type, so it complains about the value rather than the name; {@code f_lineno} is settable
     * in principle and only from inside a trace function, which this runtime cannot be in, and it checks
     * the value's type before it says so. What is left is {@link GenericAttributes}' to answer, and it
     * reads the type's dictionary: {@code __doc__} is filed there and so is read-only, {@code __class__}
     * reaches {@code object}'s getset, and a name nothing files has nowhere to go, because a frame keeps no
     * instance dictionary.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        switch (attribute) {
            case "f_trace":
                trace = value;
                return PyNone.INSTANCE;
            case "f_trace_lines":
                traceLines = asBool(value);
                return PyNone.INSTANCE;
            case "f_trace_opcodes":
                traceOpcodes = asBool(value);
                return PyNone.INSTANCE;
            case "f_lineno":
                setLine(value);
                return PyNone.INSTANCE;
            default:
                break;
        }
        refused(attribute);
        GenericAttributes.write(this, attribute, value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code del fr.name}, which every name on a frame refuses bar one.
     *
     * <p>{@code f_trace} is the exception because it has a deleter that means "no trace function". The
     * rest answer as they do to a write, except the two getsets that have a setter but no deleter, which
     * say the attribute cannot be deleted at all, and {@code f_trace_lines}, which as a member is deleted
     * with CPython's complaint about deleting a value that is not a reference.
     */
    @Override
    public PyObject delAttr(String attribute) {
        if (attribute.equals("f_trace")) {
            trace = PyNone.INSTANCE;
            return PyNone.INSTANCE;
        }
        if (attribute.equals("f_lineno") || attribute.equals("f_trace_opcodes")) {
            throw Slots.attributeError(
                    "attribute '" + attribute + "' of 'frame' objects cannot be deleted");
        }
        if (attribute.equals("f_trace_lines")) {
            throw Slots.typeError("can't delete numeric/char attribute");
        }
        refused(attribute);
        GenericAttributes.delete(this, attribute);
        return PyNone.INSTANCE;
    }

    /**
     * {@code frame.clear()}: let go of the names, if the frame has finished.
     *
     * <p>Who owns the frame decides what "finished" means. A generator-owned frame is asked of the
     * generator, because a generator keeps a frame after the run that parked it and a frame parked at a
     * yield is neither running nor over: {@code EXECUTING} and {@code SUSPENDED} each get their own
     * complaint, and a frame the generator has done with is cleared by closing the generator, which is
     * what drops the names and marks the end. A frame an ordinary call owns has only the one test —
     * whether the loop that ran it has left — and it is the only thing {@code frame.clear()} can point at
     * to tell a dead activation from a live one.
     */
    private void clear() {
        PyGenerator owner = frame.generator;
        if (owner != null) {
            switch (owner.state()) {
                case EXECUTING:
                    throw Slots.runtimeError("cannot clear an executing frame");
                case SUSPENDED:
                    throw Slots.runtimeError("cannot clear a suspended frame");
                default:
                    // _PyGen_Finalize: the generator is already over, so closing it drops the names.
                    // CPython turns anything it raises into an unraisable report; here it travels out to
                    // the caller, which is the one difference this route makes.
                    owner.finalizeFromClear();
                    return;
            }
        }
        if (!frame.isFinished()) {
            throw Slots.runtimeError("cannot clear an executing frame");
        }
        frame.clearLocals();
    }

    /** The value {@code f_trace_lines} and {@code f_trace_opcodes} take: {@code True}, {@code False}. */
    private static boolean asBool(PyObject value) {
        if (value instanceof PyBool bool) {
            return bool.getValue();
        }
        throw Slots.typeError("attribute value type must be bool");
    }

    /**
     * {@code fr.f_lineno = n}, which is only how a debugger moves execution from one line to another.
     *
     * <p>CPython checks the value before it checks whether a trace function is running, so a write of a
     * string complains about the string even here, where no write can be allowed. The integer check is
     * exact-type, as {@code PyLong_CheckExact} is: a {@code bool} is refused.
     */
    private static void setLine(PyObject value) {
        if (!(value instanceof PyInt)) {
            throw Slots.valueError("lineno must be an integer");
        }
        throw Slots.valueError("f_lineno can only be set in a trace function");
    }

    /** The names CPython's {@code frame_getsetlist} holds with no setter, and so never accepts a write to. */
    private static boolean writable(String attribute) {
        return switch (attribute) {
            case "f_back", "f_builtins", "f_code", "f_generator", "f_globals", "f_lasti", "f_locals" ->
                    true;
            default -> false;
        };
    }

    /**
     * The complaint for a name {@code frame} keeps in a C table rather than in the dictionary its type
     * publishes, which is the one {@link GenericAttributes} would otherwise read: the seven getsets with no
     * setter, and {@code clear}, the method this runtime answers from a field of its own.
     */
    private void refused(String attribute) {
        if (writable(attribute)) {
            throw Slots.attributeError(
                    "attribute '" + attribute + "' of 'frame' objects is not writable");
        }
        if (attribute.equals("clear")) {
            throw Slots.attributeError("'frame' object attribute 'clear' is read-only");
        }
    }

    /**
     * {@code hash(fr)}: identity, inherited from {@code object} exactly as CPython's is.
     *
     * <p>A frame has no dictionary and no way to compare equal to another frame, so its address is its
     * whole identity — which is also what lets a set of frames be a set.
     */
    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    @Override
    public String repr() {
        // CPython's shape, with this object's address where a memory pointer would be. The number cannot
        // match the oracle's and is not meant to; the file, line and name are what a report reads.
        int line = frame.line();
        return "<frame at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ", file '"
                + frame.code().fileName()
                + "', line "
                + (line < 0 ? frame.code().firstLine() : line)
                + ", code "
                + frame.code().name()
                + ">";
    }
}
