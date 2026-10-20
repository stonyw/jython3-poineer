package org.python.core;

/**
 * One entry of a traceback: a frame an error travelled out of, and the instruction it was at.
 *
 * <p>An entry is made by {@link Interpreter} at CPython's {@code error} label — that is, on every way a
 * Python frame fails, including the way into a handler of its own, which is why {@code e.__traceback__}
 * read inside an {@code except} clause names the line that called the function that raised. Two ways an
 * error leaves a frame make no entry, because CPython sends them to {@code exception_unwind} instead:
 * a bare {@code raise} and a {@code RERAISE}, both of which repeat an exception that already says where
 * it failed.
 *
 * <p>The list runs outermost first, because each entry is hung on the front of the chain the escaping
 * exception already carries. Only two C calls make one, and this is the first: {@code PyTraceBack_Here}
 * takes the exception out of the runtime, reads its traceback, prepends this frame, and puts it back.
 * The other is {@code _PyTraceback_Add}, which builds a frame out of a name and a line for an error that
 * has no Python frame to name; the runtime here has no reason to invent one.
 *
 * <p>The line is not stored. {@code tb_lasti} is the byte offset of the reported instruction and
 * {@code tb_lineno} is whatever the code object's line table says covers it, read again every time —
 * which is what lets a frame that has since moved on, or been cleared, still be reported where the error
 * said it was.
 *
 * <p>CPython's {@code TracebackType} constructor is the other way an entry exists, and it is not
 * reachable here: {@code types} cannot be imported until the import system at M4. A traceback therefore
 * has no way to be built with a line the code object does not give it, so it stores no line at all.
 */
public final class PyTraceback extends PyObject {

    /** The four names CPython's {@code tb_dir} answers, which is all of {@code dir(tb)}. */
    private static final PyNativeFunction DIR =
            PyNativeFunction.positional(
                    "__dir__",
                    args ->
                            PyList.of(
                                    PyStr.of("tb_frame"),
                                    PyStr.of("tb_next"),
                                    PyStr.of("tb_lasti"),
                                    PyStr.of("tb_lineno")));

    /** The entry below this one — the frame the error came from next — or null for the innermost. */
    private PyTraceback next;

    private final Frame frame;

    /** {@code tb_lasti}: the reported instruction's offset in bytes, frozen when this entry was made. */
    private final int lasti;

    /**
     * An entry above {@code next} for {@code frame} as it stands now.
     *
     * <p>The offset is taken from the frame's reported site rather than the instruction it is stepping
     * through, which is the difference CPython keeps between {@code instr_ptr} and {@code next_instr} and
     * is what makes a re-raised exception name the raise rather than the re-raise.
     */
    private PyTraceback(PyTraceback next, Frame frame) {
        super(PyType.TRACEBACK);
        this.next = next;
        this.frame = frame;
        this.lasti = frame.lasti();
    }

    /**
     * CPython's {@code PyTraceBack_Here}, at the eval loop's error label.
     *
     * <p>The chain the exception already carries becomes the tail: nothing is copied, and an exception
     * raised twice through the same frame gets one entry per pass, as {@code raise e} from a handler
     * does.
     */
    static void logFrame(Frame frame, PyBaseException error) {
        PyObject carried = error.traceback();
        error.setTraceback(
                new PyTraceback(
                        carried instanceof PyTraceback head ? head : null, frame));
    }

    /** {@code tb_next}, for the report: the entry this one was hung on, or null at the end. */
    public PyTraceback next() {
        return next;
    }

    /** The activation this entry names, for the report's file, function and line. */
    public Frame frame() {
        return frame;
    }

    /** The byte offset this entry reports, which is what the line was looked up by. */
    public int lasti() {
        return lasti;
    }

    /**
     * {@code tb_lineno}, read out of the code object every time it is asked for.
     *
     * <p>An offset no range covers has no line and reads {@code None}, which is CPython's answer for a
     * code object compiled without one rather than a complaint.
     */
    PyObject lineNumber() {
        int line = frame.instructions().lineAtLasti(lasti);
        return line < 0 ? PyNone.INSTANCE : PyInt.of(line);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "tb_frame" -> frame.frameObject();
            case "tb_next" -> next == null ? PyNone.INSTANCE : next;
            case "tb_lasti" -> PyInt.of(lasti);
            case "tb_lineno" -> lineNumber();
            case "__dir__" -> DIR;
            case "__class__" -> getType();
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * {@code tb.name = value}: one writable name, and two that are members rather than attributes.
     *
     * <p>The difference in wording is CPython's. {@code tb_frame} and {@code tb_lasti} are {@code
     * PyMemberDef}s marked {@code Py_READONLY}, so writing or deleting them is the generic complaint about
     * a read-only attribute; {@code tb_lineno} is a getset with no setter, and that one names the
     * attribute and the type. Everything else reaches the refusal for a value with no dictionary, because
     * a traceback has no instance dictionary to file a new name in.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        if (attribute.equals("tb_next")) {
            next = asNext(value, this);
            return PyNone.INSTANCE;
        }
        throw Slots.attributeError(
                member(attribute) ? "readonly attribute" : notWritable(attribute));
    }

    @Override
    public PyObject delAttr(String attribute) {
        if (attribute.equals("tb_next")) {
            throw Slots.typeError("can't delete tb_next attribute");
        }
        throw Slots.attributeError(
                member(attribute) ? "readonly attribute" : notWritable(attribute));
    }

    /**
     * {@code tb.tb_next = value}: nothing, this chain's tail, or a loop.
     *
     * <p>The loop test walks what is being hung on the front and refuses when it comes back to this
     * entry, which is the walk CPython does before it changes anything — so a refused write leaves the
     * chain exactly as it was.
     */
    private static PyTraceback asNext(PyObject value, PyTraceback self) {
        if (value == PyNone.INSTANCE) {
            return null;
        }
        if (!(value instanceof PyTraceback candidate)) {
            throw Slots.typeError(
                    "expected traceback object, got '%s'", value.getType().name());
        }
        for (PyTraceback at = candidate; at != null; at = at.next) {
            if (at == self) {
                throw Slots.valueError("traceback loop detected");
            }
        }
        return candidate;
    }

    /** The two names {@code traceback}'s {@code PyMemberDef} table holds, and so refuses by address. */
    private static boolean member(String attribute) {
        return attribute.equals("tb_frame") || attribute.equals("tb_lasti");
    }

    /**
     * The refusal for a name that is not a writable attribute of {@code traceback}.
     *
     * <p>{@code tb_lineno} is a getset with no setter, so it names the attribute and the type; {@code
     * __dir__} is a method the type publishes, and a method has no setter either, which is the third
     * wording CPython uses for the same refusal; anything else has nowhere to be filed, because a
     * traceback has no instance dictionary.
     */
    private static String notWritable(String attribute) {
        if (attribute.equals("tb_lineno")) {
            return "attribute 'tb_lineno' of 'traceback' objects is not writable";
        }
        if (attribute.equals("__dir__")) {
            return "'traceback' object attribute '__dir__' is read-only";
        }
        return "'traceback' object has no attribute '"
                + attribute
                + "' and no __dict__ for setting new attributes";
    }

    /**
     * {@code hash(tb)}: identity, which is what CPython leaves {@code traceback} inheriting from {@code
     * object}. The value has no dictionary and no way to compare equal to another, so its address is its
     * whole identity and that is enough for a set.
     */
    @Override
    public long hash() {
        return System.identityHashCode(this);
    }
}
