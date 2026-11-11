package org.python.core;

/**
 * The window a {@code memoryview} holds over an octet string.
 *
 * <p>CPython's {@code memoryview} is a header over a {@code Py_buffer} the exporter filled in: the
 * buffer carries a pointer, a length, a format, a shape and strides, and the view never copies the
 * octets. Here the exporters are {@code bytes} and {@code bytearray}, so this class keeps the same four
 * facts in the only shapes those two can produce — a reference to the exporting value, an offset, an
 * octet count and the read-only bit that follows from which of the two the reference holds. A view made
 * from another view shares that reference rather than nesting one view inside another, which is why
 * {@code memoryview(memoryview(b"abc")).obj is b"abc"} holds and why a released source is refused before
 * anything is shared.
 *
 * <p>What a view is <em>of</em> stays the exporter, not the view: {@link #viewed()} answers the
 * {@code bytes} or {@code bytearray} itself, so a write through a view of a {@code bytearray} reaches the
 * run the program still holds. The remaining facts — {@code format}, {@code itemsize}, {@code ndim},
 * {@code shape}, {@code strides}, {@code suboffsets} — are the one-dimensional octet answer, because the
 * only way to make them anything else is {@code cast} and {@code toreadonly}, which this first slice does
 * not file yet.
 *
 * <p>Release is a state of the view, not of the exporter: it drops the reference (and in CPython asks the
 * exporter to unpin its buffer), and every read afterwards raises the same {@link Slots#valueError}
 * sentence. {@code repr} is the one operation that still answers, because CPython prints a released view
 * rather than refusing it — and {@code release} itself is idempotent.
 */
public final class PyMemoryView extends PyObject {

    /** The value the window looks into: always a {@code bytes} or a {@code bytearray}. */
    private final PyObject exporter;

    /** The first octet of the window inside the exporter. */
    private final int offset;

    /** How many octets the window covers. */
    private final int length;

    /** Whether the exporter refuses writes; views of {@code bytes} are read-only. */
    private final boolean readonly;

    /** Set by {@code release}; every read but {@code repr} and {@code release} then refuses. */
    private boolean released;

    private PyMemoryView(PyObject exporter, int offset, int length, boolean readonly) {
        super(PyType.MEMORYVIEW);
        this.exporter = exporter;
        this.offset = offset;
        this.length = length;
        this.readonly = readonly;
    }

    /**
     * {@code memoryview(object)}: acquire the buffer {@code value} exports.
     *
     * <p>Only the two octet types export one here. A view is unwrapped to the value it looks into, which
     * is the identity {@code memoryview(memoryview(x)).obj is x} rests on, and a released source is
     * refused with the same sentence every released read uses. Anything else answers the TypeError
     * {@code PyObject_GetBuffer} raises, naming the value's type in the style {@code filedName()} prints.
     */
    static PyMemoryView of(PyObject value) {
        if (value instanceof PyMemoryView view) {
            view.check();
            return new PyMemoryView(
                    view.exporter, view.offset, view.length, view.readonly);
        }
        if (value instanceof PyByteArray run) {
            return new PyMemoryView(run, 0, run.size(), false);
        }
        if (value instanceof PyBytes octets) {
            return new PyMemoryView(octets, 0, octets.size(), true);
        }
        throw Slots.typeError(
                "memoryview: a bytes-like object is required, not '%s'",
                value.getType().filedName());
    }

    /** {@code memoryview.release()}: idempotent, and the only read a released view still allows. */
    void release() {
        released = true;
    }

    /** Refuses every read of a released view with CPython's one sentence for the state. */
    private void check() {
        if (released) {
            throw Slots.valueError("operation forbidden on released memoryview object");
        }
    }

    /** {@code memoryview.obj}: the exporter itself, not the window. */
    PyObject viewed() {
        check();
        return exporter;
    }

    /** {@code memoryview.nbytes}: the octets the window covers, which is one per element for {@code B}. */
    int bytes() {
        check();
        return length;
    }

    /** {@code memoryview.readonly}: whether writes would be refused. */
    boolean readonly() {
        check();
        return readonly;
    }

    /** {@code memoryview.itemsize}: the format is {@code B}, so one octet per element. */
    int itemsize() {
        check();
        return 1;
    }

    /** {@code memoryview.format}: the struct-style code, which starts as {@code B}. */
    String format() {
        check();
        return "B";
    }

    /** {@code memoryview.ndim}: a basic view has one dimension. */
    int ndim() {
        check();
        return 1;
    }

    /** {@code memoryview.shape}: one extent, the octet count. */
    PyObject shape() {
        check();
        return PyTuple.of(PyInt.of(length));
    }

    /** {@code memoryview.strides}: one stride of one octet. */
    PyObject strides() {
        check();
        return PyTuple.of(PyInt.of(1));
    }

    /** {@code memoryview.suboffsets}: empty, because a basic view is not PIL-style. */
    PyObject suboffsets() {
        check();
        return PyTuple.of();
    }

    /**
     * {@code repr(view)}: the address in the angle brackets CPython uses, and the same line prefixed
     * {@code released} once the window is gone.
     *
     * <p>This is the one read that does not go through {@link #check()}: a released view is printed rather
     * than refused, so a failed {@code except} clause can still say what it was holding.
     */
    @Override
    public String repr() {
        String state = released ? "<released memory at 0x" : "<memory at 0x";
        return state + Integer.toHexString(System.identityHashCode(this)) + ">";
    }

    /** {@code __len__}: the first extent, which for a one-dimensional view is the octet count. */
    @Override
    public PyObject length() {
        check();
        return PyInt.of(length);
    }
}
