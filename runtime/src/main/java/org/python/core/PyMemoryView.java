package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The window a {@code memoryview} holds over an octet string.
 *
 * <p>CPython's {@code memoryview} is a header over a {@code Py_buffer} the exporter filled in: the
 * buffer carries a pointer, a length, a format, a shape and strides, and the view never copies the
 * octets. Here the exporters are {@code bytes} and {@code bytearray}, so this class keeps the same
 * facts over the only run those two can produce — a reference to the exporting value, an offset into
 * it, the struct-style {@code format}, the width of one element, and the shape and strides that say
 * where each element starts. A view made from another view shares the run rather than nesting one view
 * inside another, which is why {@code memoryview(memoryview(b"abc")).obj is b"abc"} holds and why a
 * released source is refused before anything is shared.
 *
 * <p>What a view is <em>of</em> is the object it reports as {@code .obj}: the exporter for a view
 * {@code memoryview(x)} made, and the parent view for one {@code __buffer__} exported. The run itself
 * is always a {@code bytes} or a {@code bytearray}, so a write through a view reaches the run the
 * program still holds. A basic view is one-dimensional with format {@code B}; {@code cast} is the only
 * operation that changes the format, the element width or the shape, and {@code toreadonly} the only
 * one that pins the read-only bit without changing anything else.
 *
 * <p>A slice is another window over the same run: the start folds into the offset, the selected
 * count replaces the first extent, and the step multiplies the stride, so {@code mv[::2].strides} is
 * the stride of the elements the slice selects rather than of the octets underneath. Nothing is
 * copied. The stride is a machine word because CPython keeps it in a {@code Py_ssize_t}: a step wider
 * than the word is clamped to its edges, and a stride that overflows on a nested slice wraps exactly as
 * the C multiplication does.
 *
 * <p>Release is a state of the view, not of the run: it drops the reference (and in CPython asks the
 * exporter to unpin its buffer), and every read afterwards raises the same {@link Slots#valueError}
 * sentence. Four operations still answer, because CPython keeps them outside the check: {@code repr}
 * prints the released window rather than refusing it, {@code release} is idempotent, {@code __exit__} is
 * a release and so repeats it, and the two equalities compare by identity alone. A view that exported
 * buffers refuses to be released while any of them is still alive, and {@code __release_buffer__} is
 * how the exporter — a view or the {@code bytearray} itself — takes one back.
 */
public final class PyMemoryView extends PyObject {

    /** {@code PyBUF_WRITABLE}: the caller wants a run it can write to. */
    private static final int WRITABLE = 1;

    /** {@code PyBUF_FORMAT}: the caller wants the element format rather than plain unsigned bytes. */
    private static final int FORMAT = 4;

    /** {@code PyBUF_ND}: the caller wants the shape as well as the flat run. */
    private static final int ND = 8;

    /** {@code PyBUF_STRIDES}: the caller can cope with a view whose elements are not adjacent. */
    private static final int STRIDES = 24;

    /** The value the window looks into: always a {@code bytes} or a {@code bytearray}. */
    private final PyObject exporter;

    /** What {@code memoryview.obj} reports: the exporter, or the view this one was exported from. */
    private final PyObject obj;

    /** The first octet of the window inside the exporter. */
    private final int offset;

    /** The struct-style element code, possibly prefixed with {@code '@'} as {@code cast} keeps it. */
    private final String format;

    /** The width of one element, in octets. */
    private final int itemsize;

    /** One extent per dimension; a basic view has exactly one. */
    private final long[] shape;

    /** One stride per dimension, in octets. */
    private final long[] strides;

    /** Whether the run refuses writes; views of {@code bytes} are read-only. */
    private final boolean readonly;

    /** Set by {@code release}; every read but {@code repr} and {@code release} then refuses. */
    private boolean released;

    /** The views {@code __buffer__} exported from this one and that are still alive. */
    private final List<PyMemoryView> exports = new ArrayList<>();

    private PyMemoryView(
            PyObject exporter,
            PyObject obj,
            int offset,
            String format,
            int itemsize,
            long[] shape,
            long[] strides,
            boolean readonly) {
        super(PyType.MEMORYVIEW);
        this.exporter = exporter;
        this.obj = obj;
        this.offset = offset;
        this.format = format;
        this.itemsize = itemsize;
        this.shape = shape;
        this.strides = strides;
        this.readonly = readonly;
    }

    /**
     * {@code memoryview(object)}: acquire the buffer {@code value} exports.
     *
     * <p>Only the two octet types export one here. A view is copied whole — its format, shape and
     * strides included — which is what makes {@code memoryview(mv)} an independent header over the same
     * run, and what keeps {@code .obj} pointing at whatever {@code mv.obj} pointed at. A released source
     * is refused with the same sentence every released read uses. Anything else answers the TypeError
     * {@code PyObject_GetBuffer} raises, naming the value's type in the style {@code filedName()} prints.
     */
    static PyMemoryView of(PyObject value) {
        if (value instanceof PyMemoryView view) {
            view.check();
            return view.copied();
        }
        if (value instanceof PyByteArray run) {
            return basic(run, run.size(), false);
        }
        if (value instanceof PyBytes octets) {
            return basic(octets, octets.size(), true);
        }
        throw Slots.typeError(
                "memoryview: a bytes-like object is required, not '%s'",
                value.getType().filedName());
    }

    /**
     * {@code memoryview._from_flags(object, flags)}: the constructor the buffer protocol reaches.
     *
     * <p>A view already made is copied as it stands and the flags are ignored altogether, which is why
     * asking for a writable one over a read-only view answers instead of refusing. A {@code bytes}
     * exports a read-only basic view and refuses only the writable bit; a {@code bytearray} exports a
     * writable one whatever the flags say, because there is nothing else to say about a basic run.
     */
    static PyMemoryView fromFlags(PyObject value, int flags) {
        if (value instanceof PyMemoryView view) {
            view.check();
            return view.copied();
        }
        if (value instanceof PyBytes octets) {
            if ((flags & WRITABLE) != 0) {
                throw Slots.bufferError("Object is not writable.");
            }
            return basic(octets, octets.size(), true);
        }
        if (value instanceof PyByteArray run) {
            return basic(run, run.size(), false);
        }
        throw Slots.typeError(
                "memoryview: a bytes-like object is required, not '%s'",
                value.getType().filedName());
    }

    private static PyMemoryView basic(PyObject exporter, int size, boolean readonly) {
        return new PyMemoryView(
                exporter, exporter, 0, "B", 1, new long[] {size}, new long[] {1}, readonly);
    }

    /** An independent header over the same run, with the same format and shape. */
    private PyMemoryView copied() {
        return new PyMemoryView(
                exporter,
                obj,
                offset,
                format,
                itemsize,
                shape.clone(),
                strides.clone(),
                readonly);
    }

    /**
     * {@code view.cast(format, shape=None)}: another header over the same run.
     *
     * <p>The reference refuses a view it cannot walk consecutively first, then reads the shape, then
     * the format; the order matters because each answer names only its own mistake. A destination
     * wider than one octet may only be cast from a one-octet source, and a shape may only be supplied
     * when one side of the cast is one-dimensional, because the reference restricts the operation to
     * {@code 1D -> ND} and {@code ND -> 1D}. With no shape the run is read as a flat row of the
     * destination's elements.
     */
    PyMemoryView cast(String destination, PyObject requestedShape) {
        check();
        if (!cContiguous()) {
            throw Slots.typeError("memoryview: casts are restricted to C-contiguous views");
        }
        long[] requested = null;
        if (requestedShape != null) {
            requested = castShape(requestedShape);
        }
        int width = formatSize(destination);
        if (width < 0) {
            throw Slots.valueError(
                    "memoryview: destination format must be a native single character format"
                            + " prefixed with an optional '@'");
        }
        if (width != 1 && itemsize != 1) {
            throw Slots.typeError("memoryview: cannot cast between two non-byte formats");
        }
        if (requested != null && shape.length != 1 && requested.length != 1) {
            throw Slots.typeError("memoryview: cast must be 1D -> ND or ND -> 1D");
        }
        if (requested != null) {
            for (long extent : requested) {
                if (extent <= 0) {
                    throw Slots.valueError("memoryview.cast(): elements of shape must be integers > 0");
                }
            }
        }
        int total = nbytes();
        if (requested != null && total == 0) {
            throw Slots.typeError("memoryview: cannot cast view with zeros in shape or strides");
        }
        long[] next;
        if (requested != null) {
            next = requested;
        } else {
            if (total % width != 0) {
                throw Slots.typeError("memoryview: length is not a multiple of itemsize");
            }
            next = new long[] {total / width};
        }
        long product = 1;
        for (long extent : next) {
            product *= extent;
        }
        if (product * width != total) {
            throw Slots.typeError("memoryview: product(shape) * itemsize != buffer size");
        }
        return new PyMemoryView(
                exporter, obj, offset, destination, width, next, cStrides(next, width), readonly);
    }

    /** The shape argument of {@code cast}, read element by element through the index protocol. */
    private static long[] castShape(PyObject value) {
        int size;
        if (value instanceof PyList list) {
            size = list.size();
        } else if (value instanceof PyTuple tuple) {
            size = tuple.size();
        } else {
            throw Slots.typeError("shape must be a list or a tuple");
        }
        long[] extents = new long[size];
        for (int i = 0; i < size; i++) {
            PyObject element = value instanceof PyList list ? list.at(i) : ((PyTuple) value).at(i);
            if (!Numbers.isIndex(element)) {
                throw Slots.typeError("memoryview.cast(): elements of shape must be integers");
            }
            BigInteger extent = Numbers.asIndex(element);
            if (extent.bitLength() > 63) {
                throw Slots.overflowError("Python int too large to convert to C ssize_t");
            }
            extents[i] = extent.longValue();
        }
        return extents;
    }

    /** The width of a native single-character format, or {@code -1} when the spelling is not one. */
    private static int formatSize(String format) {
        String body = format.startsWith("@") ? format.substring(1) : format;
        if (body.length() != 1) {
            return -1;
        }
        switch (body.charAt(0)) {
            case 'b':
            case 'B':
            case 'c':
            case '?':
                return 1;
            case 'h':
            case 'H':
            case 'e':
                return 2;
            case 'i':
            case 'I':
            case 'f':
                return 4;
            case 'l':
            case 'L':
            case 'q':
            case 'Q':
            case 'd':
            case 'n':
            case 'N':
            case 'P':
                return 8;
            default:
                return -1;
        }
    }

    /** C-order strides for a shape: the last extent is adjacent, the others multiply outwards. */
    private static long[] cStrides(long[] shape, int itemsize) {
        long[] out = new long[shape.length];
        long step = itemsize;
        for (int d = shape.length - 1; d >= 0; d--) {
            out[d] = step;
            step *= shape[d];
        }
        return out;
    }

    /**
     * {@code view.toreadonly()}: a second header over the same run with the read-only bit set.
     *
     * <p>It always makes a new view, even when the source is already read-only, and it changes nothing
     * but that bit: the offset, format, shape and strides are the source's, and {@code .obj} is the
     * object the source reported.
     */
    PyMemoryView toReadonly() {
        return new PyMemoryView(
                exporter,
                obj,
                offset,
                format,
                itemsize,
                shape.clone(),
                strides.clone(),
                true);
    }

    /**
     * {@code view.__buffer__(flags)}: a second header this view exports to the caller.
     *
     * <p>Four refusals arrive before the header is built. A released view cannot export, {@code flags}
     * outside the range of a C {@code int} is refused by the caller, a writable window over a read-only
     * run is refused, a format without a shape is refused because the caller would not know how to walk
     * the elements, and a caller that cannot take strides is refused a non-consecutive window. What the
     * flags then decide is only how much the header says: with {@code ND} it keeps the shape, and with
     * {@code FORMAT} the element code, while without {@code ND} it flattens to a one-dimensional row of
     * the same element width. The exported header reports this view as its {@code .obj}, which is what
     * {@code __release_buffer__} checks before taking it back.
     */
    PyMemoryView exposeBuffer(int flags) {
        check();
        if ((flags & WRITABLE) != 0 && readonly) {
            throw Slots.bufferError("memoryview: underlying buffer is not writable");
        }
        if ((flags & FORMAT) != 0 && (flags & ND) == 0) {
            throw Slots.bufferError(
                    "memoryview: cannot cast to unsigned bytes if the format flag is present");
        }
        if (!cContiguous() && (flags & STRIDES) != STRIDES) {
            throw Slots.bufferError("memoryview: underlying buffer is not C-contiguous");
        }
        long[] nextShape;
        long[] nextStrides;
        String nextFormat;
        if ((flags & ND) != 0) {
            nextShape = shape.clone();
            nextStrides = strides.clone();
            nextFormat = (flags & FORMAT) != 0 ? format : "B";
        } else {
            nextShape = new long[] {nbytes() / itemsize};
            nextStrides = new long[] {itemsize};
            nextFormat = "B";
        }
        PyMemoryView exported =
                new PyMemoryView(
                        exporter, this, offset, nextFormat, itemsize, nextShape, nextStrides, readonly);
        exports.add(exported);
        return exported;
    }

    /**
     * {@code view.__release_buffer__(buffer)}: take back a header this view exported.
     *
     * <p>The argument has to be a view, and the one this view exported: the reference tests the
     * reported {@code .obj} rather than any identity of its own, which is why exporting twice makes two
     * distinct arguments both acceptable. Releasing one twice is refused rather than repeated.
     */
    PyObject releaseBuffer(PyObject other) {
        if (!(other instanceof PyMemoryView view)) {
            throw Slots.typeError("expected a memoryview object");
        }
        if (view.obj != this) {
            throw Slots.valueError("memoryview's buffer is not this object");
        }
        if (view.released) {
            throw Slots.valueError("memoryview's buffer has already been released");
        }
        view.released = true;
        return PyNone.INSTANCE;
    }

    /**
     * {@code view.release()}: idempotent once no buffer is still out, and one of the four operations a
     * released view allows.
     *
     * <p>A view that exported a header cannot drop the run while the caller still holds it, so the
     * release is refused with the count of live exports. A header that was released already no longer
     * counts, which is what lets the exporter release once the last borrower is done. The second call
     * on a released view finds no exports and answers like the first, which is what makes {@code release}
     * safe to repeat where the other reads are not.
     */
    void release() {
        int live = 0;
        for (PyMemoryView exported : exports) {
            if (!exported.released) {
                live++;
            }
        }
        if (live > 0) {
            throw Slots.bufferError(
                    "memoryview has %d exported buffer%s", live, live == 1 ? "" : "s");
        }
        released = true;
    }

    /** Whether {@code release} has run; read without the released check by the two buffer rows. */
    boolean isReleased() {
        return released;
    }

    /** What {@code .obj} reports, read without the released check by {@code bytearray}'s row. */
    PyObject reports() {
        return obj;
    }

    /** Marks the view released from outside, which is what an exporter's row does. */
    void markReleased() {
        released = true;
    }

    /**
     * {@code memoryview.__enter__()}: the {@code with} statement's entry, which answers the view itself.
     *
     * <p>Entering copies and pins nothing here, so the body is the released check every read repeats: a
     * view that was released cannot be entered, while leaving one is a release and stays allowed.
     */
    PyMemoryView enter() {
        check();
        return this;
    }

    /**
     * {@code memoryview.__exit__()}: leaving a {@code with} releases the window, however the body ended.
     *
     * <p>The three exception arguments are read and dropped — the reference releases in every case and
     * answers {@code None}, which lets an exception out unchanged. Leaving twice is releasing twice,
     * which is why a second {@code __exit__} on a released view still answers {@code None}.
     */
    PyObject exit() {
        release();
        return PyNone.INSTANCE;
    }

    /**
     * {@code view.tobytes(order='C')}: the elements the window selects, copied into a {@code bytes}.
     *
     * <p>The copy walks the window through its shape and strides, so a strided slice answers the
     * elements it selects rather than the run underneath. {@code F} reads the elements in column-major
     * order; {@code C} and {@code A} read them in row-major order, which is the physical order for every
     * window this runtime can make.
     *
     * <p>{@code order} arrives already converted — {@code null} for {@code None}, otherwise the string —
     * and its spelling is checked here rather than in the argument parser, because the reference refuses
     * a spent view before it looks at which of the three letters was asked for.
     */
    PyBytes toBytes(String order) {
        check();
        if (order != null && !order.equals("C") && !order.equals("F") && !order.equals("A")) {
            throw Slots.valueError("order must be 'C', 'F' or 'A'");
        }
        boolean fortran = "F".equals(order);
        int elements = elements();
        byte[] out = new byte[nbytes()];
        for (int i = 0; i < elements; i++) {
            int at = fortran ? fortranPosition(i) : position(i);
            for (int j = 0; j < itemsize; j++) {
                out[i * itemsize + j] = (byte) octetAt(at + j);
            }
        }
        return PyBytes.wrap(out);
    }

    /** {@code view.tolist()}: the same elements, nested one list per dimension. */
    PyList toList() {
        check();
        long[] flat = new long[] {0};
        return (PyList) nested(0, flat);
    }

    private PyObject nested(int dimension, long[] flat) {
        int extent = (int) shape[dimension];
        PyObject[] elements = new PyObject[extent];
        if (dimension == shape.length - 1) {
            for (int i = 0; i < extent; i++) {
                elements[i] = elementAt(position((int) flat[0]++));
            }
        } else {
            for (int i = 0; i < extent; i++) {
                elements[i] = nested(dimension + 1, flat);
            }
        }
        return PyList.of(elements);
    }

    /**
     * {@code view.hex(sep, bytes_per_sep)}: the window's octets as a string of hexadecimal digits.
     *
     * <p>The copy is taken in the order the window presents its octets, exactly as {@code tobytes}
     * takes it, so a strided or reversed view spells what it shows rather than what its parent holds.
     * The grouping, the separator and every refusal are {@code bytes.hex}'s, because the text is the
     * same text: what differs between the two methods is only which octets are handed to it.
     */
    PyStr hex(PyObject[] given) {
        check();
        return (PyStr) PyBytes.hexadecimal(octets(), given);
    }

    /**
     * {@code view.count(value)}: how many elements equal {@code value}.
     *
     * <p>The comparison is {@code ==} between the element the format reads and the value, so
     * {@code memoryview(b"a").count(97.0)} is one and a value that compares equal to nothing counts
     * nothing. A multi-dimensional view is refused with the iterator's sentence rather than walked,
     * because there are no sub-views here to compare against.
     */
    PyInt count(PyObject value) {
        check();
        if (shape.length > 1) {
            throw unimplemented("multi-dimensional sub-views are not implemented");
        }
        long found = 0;
        int total = elements();
        for (int i = 0; i < total; i++) {
            if (Slots.compareTrue(CompareOp.EQ, elementAt(position(i)), value)) {
                found++;
            }
        }
        return PyInt.of(found);
    }

    /**
     * {@code view.index(value, start, stop)}: where the first element equal to {@code value} sits.
     *
     * <p>The bounds are slice bounds — a negative one counts back from the end and an overshooting one
     * is clamped — but {@code None} is not one of them, which is why the refusal is the search's
     * wording and not the slice's. A multi-dimensional view is refused with the lookup's own sentence,
     * and a walk that finds nothing raises {@code ValueError} rather than answering {@code -1}.
     */
    PyInt index(PyObject value, PyObject start, PyObject stop) {
        check();
        if (shape.length > 1) {
            throw unimplemented("multi-dimensional lookup is not implemented");
        }
        int total = elements();
        int from = bound(start, total, 0);
        int to = bound(stop, total, total);
        for (int i = from; i < to; i++) {
            if (Slots.compareTrue(CompareOp.EQ, elementAt(position(i)), value)) {
                return PyInt.of(i);
            }
        }
        throw Slots.valueError("memoryview.index(x): x not found");
    }

    /** One resolved bound of {@code index}, with a missing argument standing for its default. */
    private static int bound(PyObject given, int length, int absent) {
        if (given == null) {
            return absent;
        }
        BigInteger value = Numbers.searchIndex(given);
        if (value.signum() < 0) {
            value = value.add(BigInteger.valueOf(length));
            if (value.signum() < 0) {
                return 0;
            }
        }
        return value.compareTo(BigInteger.valueOf(length)) > 0 ? length : value.intValue();
    }

    /** Refuses every read of a released view with CPython's one sentence for the state. */
    private void check() {
        if (released) {
            throw Slots.valueError("operation forbidden on released memoryview object");
        }
    }

    /** {@code memoryview.obj}: the value the reference reports, not necessarily the exporter. */
    PyObject viewed() {
        check();
        return obj;
    }

    /** {@code memoryview.nbytes}: the octets the window covers, every element counted once. */
    int bytes() {
        check();
        return nbytes();
    }

    /** {@code memoryview.readonly}: whether writes would be refused. */
    boolean readonly() {
        check();
        return readonly;
    }

    /** {@code memoryview.itemsize}: the width of one element of the current format. */
    int itemsize() {
        check();
        return itemsize;
    }

    /** {@code memoryview.format}: the struct-style code, which starts as {@code B}. */
    String format() {
        check();
        return format;
    }

    /** {@code memoryview.ndim}: how many extents the shape carries. */
    int ndim() {
        check();
        return shape.length;
    }

    /** {@code memoryview.shape}: one extent per dimension. */
    PyObject shape() {
        check();
        PyObject[] extents = new PyObject[shape.length];
        for (int d = 0; d < shape.length; d++) {
            extents[d] = PyInt.of(shape[d]);
        }
        return PyTuple.of(extents);
    }

    /** {@code memoryview.strides}: one stride per dimension. */
    PyObject strides() {
        check();
        PyObject[] steps = new PyObject[strides.length];
        for (int d = 0; d < strides.length; d++) {
            steps[d] = PyInt.of(strides[d]);
        }
        return PyTuple.of(steps);
    }

    /** {@code memoryview.suboffsets}: empty, because a window here is not PIL-style. */
    PyObject suboffsets() {
        check();
        return PyTuple.of();
    }

    /**
     * The three contiguity flags: whether the elements are adjacent in C order, in Fortran order, or
     * either.
     *
     * <p>CPython computes {@code c_contiguous} and {@code f_contiguous} from the strides and then sets
     * {@code contiguous} when either holds; an extent of zero or one has no neighbouring elements to be
     * non-adjacent, so its stride never matters.
     */
    boolean cContiguous() {
        long expected = itemsize;
        for (int d = shape.length - 1; d >= 0; d--) {
            if (shape[d] != 1 && strides[d] != expected) {
                return false;
            }
            expected *= shape[d];
        }
        return true;
    }

    boolean fContiguous() {
        long expected = itemsize;
        for (int d = 0; d < shape.length; d++) {
            if (shape[d] != 1 && strides[d] != expected) {
                return false;
            }
            expected *= shape[d];
        }
        return true;
    }

    boolean contiguous() {
        check();
        return cContiguous() || fContiguous();
    }

    /**
     * {@code view == other} and {@code view != other}: the elements the window selects, compared with
     * the elements another buffer selects.
     *
     * <p>CPython spells this one {@code memory_richcompare}, and it answers only for equality: the four
     * orderings raise {@code TypeError} at the point the slot declines, which is the
     * {@link PyNotImplemented#INSTANCE} this returns. Two views are equal when their shapes agree
     * extent for extent, their elements are the same width, their formats are the same family — {@code B}
     * and {@code b} are one family, as are the eight-octet integers, while {@code c}, {@code ?} and the
     * floats each stand alone — and their selected octets agree in C order. A {@code bytes} or a
     * {@code bytearray} stands in only for a one-dimensional view of the {@code B} family.
     *
     * <p>A released view is CPython's exception to every rule above: the comparison answers identity and
     * nothing else, which is why {@code released == released} is true only for the very same object and
     * why a released view is unequal to the contents it used to cover. Another value that exports no
     * buffer — a string, a list, {@code None} — is not compared at all; the reference asks for a buffer,
     * clears the failure and declines, and Python's fallback then answers false for {@code ==} and true
     * for {@code !=}.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (cmp != CompareOp.EQ && cmp != CompareOp.NE) {
            return PyNotImplemented.INSTANCE;
        }
        boolean equal;
        if (released) {
            equal = this == other;
        } else if (other instanceof PyMemoryView view) {
            equal = view.released ? this == other : sameElements(view);
        } else if (other instanceof PyBytes || other instanceof PyByteArray) {
            equal = matchesOctets(PyBytes.octets(other));
        } else {
            return PyNotImplemented.INSTANCE;
        }
        return PyBool.of(cmp == CompareOp.EQ ? equal : !equal);
    }

    /**
     * The Java-level equality behind {@link PyObject#equals}, which is {@code ==} without the declined
     * case: the same elements, or the same object when either side has been released.
     */
    @Override
    public boolean eq(PyObject other) {
        if (other == this) {
            return true;
        }
        if (other instanceof PyMemoryView view) {
            return !released && !view.released && sameElements(view);
        }
        return !released
                && (other instanceof PyBytes || other instanceof PyByteArray)
                && matchesOctets(PyBytes.octets(other));
    }

    /**
     * {@code hash(view)}: the hash of the octets the window covers, refused for a writable view.
     *
     * <p>{@code memory_hash} hashes the run only for the one-octet formats, because a hash has to agree
     * with the value a dictionary key was inserted with and only those formats spell a plain run; any
     * other format is refused by name. A writable view is refused earlier still because a run that can
     * change under the hash would break every dictionary it was put in, and a released view is refused
     * before either, with the same sentence every released read uses.
     *
     * <p>A strided view hashes its elements in the order the view presents them, since the reference
     * copies a non-contiguous window into a scratch buffer before hashing it.
     */
    @Override
    public long hash() {
        check();
        if (!readonly) {
            throw Slots.valueError("cannot hash writable memoryview object");
        }
        char code = code();
        if (code != 'B' && code != 'b' && code != 'c') {
            throw Slots.valueError("memoryview: hashing is restricted to formats 'B', 'b' or 'c'");
        }
        return PyHash.bytesHash(octets());
    }

    /**
     * {@code iter(view)}: a cursor over the first dimension, in the order the window presents it.
     *
     * <p>The cursor captures the extent now — a view cannot make its run grow or shrink, so the number
     * is stable — and reads each element when the step is asked for, which is what makes a walk of a
     * writable view see a write that lands after the cursor was made. A multi-dimensional view has no
     * sub-views here, so the first step refuses it.
     */
    @Override
    public PyObject iterator() {
        check();
        return new MemoryIterator(this);
    }

    /** How many elements the window covers, every dimension multiplied out. */
    private int elements() {
        long total = 1;
        for (long extent : shape) {
            total *= extent;
        }
        return (int) total;
    }

    /** The octets the window covers, one element after another in C order. */
    private int nbytes() {
        return (int) (elements() * (long) itemsize);
    }

    /** The last letter of the format, which is the code the element readers switch on. */
    private char code() {
        return format.charAt(format.length() - 1);
    }

    /** The selected octets, copied out in the order the window presents them. */
    private byte[] octets() {
        int total = nbytes();
        byte[] out = new byte[total];
        int count = elements();
        for (int i = 0; i < count; i++) {
            int at = position(i);
            for (int j = 0; j < itemsize; j++) {
                out[i * itemsize + j] = (byte) octetAt(at + j);
            }
        }
        return out;
    }

    /** Whether two live windows select the same elements, in the same shapes and formats. */
    private boolean sameElements(PyMemoryView other) {
        if (shape.length != other.shape.length) {
            return false;
        }
        for (int d = 0; d < shape.length; d++) {
            if (shape[d] != other.shape[d]) {
                return false;
            }
        }
        if (itemsize != other.itemsize || !formatFamily().equals(other.formatFamily())) {
            return false;
        }
        return Arrays.equals(octets(), other.octets());
    }

    /** Whether a plain run stands in for this view: one dimension of the {@code B} family. */
    private boolean matchesOctets(byte[] run) {
        return shape.length == 1
                && shape[0] == run.length
                && itemsize == 1
                && formatFamily().equals("B")
                && Arrays.equals(octets(), run);
    }

    /** The family a format belongs to: formats that spell the same values compare as one. */
    private String formatFamily() {
        switch (code()) {
            case 'b':
            case 'B':
                return "B";
            case 'c':
                return "c";
            case '?':
                return "?";
            case 'h':
            case 'H':
                return "h";
            case 'i':
            case 'I':
                return "i";
            case 'l':
            case 'L':
            case 'q':
            case 'Q':
            case 'n':
            case 'N':
            case 'P':
                return "q";
            case 'f':
                return "f";
            case 'd':
                return "d";
            default:
                return "e";
        }
    }

    /** {@code view[i]}, {@code view[a:b:c]} and {@code view[i,j]}: the element or a sub-window. */
    @Override
    public PyObject getItem(PyObject key) {
        check();
        if (key instanceof PyTuple tuple) {
            return tupleItem(tuple);
        }
        if (Numbers.isIndex(key)) {
            if (shape.length > 1) {
                throw unimplemented("multi-dimensional sub-views are not implemented");
            }
            return elementAt(position(indexOf(key)));
        }
        if (Slices.isSlice(key)) {
            return sliced((PySlice) key);
        }
        throw Slots.typeError("memoryview: invalid slice key");
    }

    /** {@code view[key] = value}: one element, or a run through a slice, or the elements of a tuple. */
    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        check();
        if (readonly) {
            throw Slots.typeError("cannot modify read-only memory");
        }
        if (Numbers.isIndex(key)) {
            if (shape.length > 1) {
                throw unimplemented("multi-dimensional sub-views are not implemented");
            }
            writeElement(position(indexOf(key)), value);
        } else if (Slices.isSlice(key)) {
            assignSlice((PySlice) key, value);
        } else if (key instanceof PyTuple tuple) {
            assignTuple(tuple, value);
        } else {
            throw Slots.typeError("memoryview: invalid slice key");
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code del view[key]}: never allowed.
     *
     * <p>A memoryview cannot change the size of what it looks into, so CPython refuses every deletion
     * with one sentence, in front of any reading of the key — which is why {@code del view[()]} and
     * {@code del view[0:1]} say the same thing, and why a read-only view complains about that first.
     */
    @Override
    public PyObject delItem(PyObject key) {
        check();
        if (readonly) {
            throw Slots.typeError("cannot modify read-only memory");
        }
        throw Slots.typeError("cannot delete memory");
    }

    /** One element of a one-dimensional view, folded into the window's own bounds. */
    private int indexOf(PyObject key) {
        return Numbers.wrapIndex(
                key, Numbers.asIndex(key), (int) shape[0], "index out of bounds on dimension 1");
    }

    /** The octet a C-order element index starts at, walked through the shape. */
    private int position(int flat) {
        long at = offset;
        long left = flat;
        for (int d = shape.length - 1; d >= 0; d--) {
            long extent = shape[d];
            long index = extent == 0 ? 0 : left % extent;
            if (extent != 0) {
                left /= extent;
            }
            at += index * strides[d];
        }
        return (int) at;
    }

    /** The octet a Fortran-order element index starts at, walked from the first dimension. */
    private int fortranPosition(int flat) {
        long at = offset;
        long left = flat;
        for (int d = 0; d < shape.length; d++) {
            long extent = shape[d];
            long index = extent == 0 ? 0 : left % extent;
            if (extent != 0) {
                left /= extent;
            }
            at += index * strides[d];
        }
        return (int) at;
    }

    /** One octet of the run, read through the offset; a released view never reaches here. */
    private int octetAt(long at) {
        int index = (int) at;
        return exporter instanceof PyByteArray run
                ? run.byteAt(index) & 0xff
                : ((PyBytes) exporter).byteAt(index) & 0xff;
    }

    /** One octet of the run, written in place; only a writable view reaches here. */
    private void writeOctet(long at, int value) {
        ((PyByteArray) exporter).raw()[(int) at] = (byte) value;
    }

    /** {@code itemsize} octets read as a little-endian integer, the native order on this host. */
    private long readLE(int at, int width) {
        long value = 0;
        for (int i = width - 1; i >= 0; i--) {
            value = (value << 8) | octetAt(at + i);
        }
        return value;
    }

    private void writeLE(int at, int width, long value) {
        for (int i = 0; i < width; i++) {
            writeOctet(at + i, (int) (value >>> (8 * i)) & 0xff);
        }
    }

    /** One element, read as the value its format spells. */
    private PyObject elementAt(int at) {
        switch (code()) {
            case 'b':
                return PyInt.of((byte) octetAt(at));
            case 'B':
                return PyInt.of(octetAt(at));
            case 'c':
                return PyBytes.wrap(new byte[] {(byte) octetAt(at)});
            case '?':
                return PyBool.of(octetAt(at) != 0);
            case 'h':
                return PyInt.of((short) readLE(at, 2));
            case 'H':
                return PyInt.of(readLE(at, 2));
            case 'i':
                return PyInt.of((int) readLE(at, 4));
            case 'I':
                return PyInt.of(readLE(at, 4));
            case 'l':
            case 'q':
            case 'n':
                return PyInt.of(readLE(at, 8));
            case 'L':
            case 'Q':
            case 'N':
            case 'P':
                return PyInt.of(unsigned(readLE(at, 8)));
            case 'f':
                return PyFloat.of(Float.intBitsToFloat((int) readLE(at, 4)));
            case 'd':
                return PyFloat.of(Double.longBitsToDouble(readLE(at, 8)));
            default:
                return PyFloat.of(halfToDouble((short) readLE(at, 2)));
        }
    }

    /** A word read back as an unsigned 64-bit value, which may need more than a Java {@code long}. */
    private static BigInteger unsigned(long value) {
        return value >= 0
                ? BigInteger.valueOf(value)
                : BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));
    }

    /** One element, written in the encoding its format spells. */
    private void writeElement(int at, PyObject value) {
        char code = code();
        switch (code) {
            case 'c':
                if (!(value instanceof PyBytes run)) {
                    throw Slots.typeError("memoryview: invalid type for format 'c'");
                }
                byte[] octets = run.getValue();
                if (octets.length != 1) {
                    throw Slots.valueError("memoryview: invalid value for format 'c'");
                }
                writeOctet(at, octets[0] & 0xff);
                return;
            case '?':
                writeOctet(at, Slots.truth(value) ? 1 : 0);
                return;
            case 'b':
                writeInteger(at, value, code, BigInteger.valueOf(-128), BigInteger.valueOf(127));
                return;
            case 'B':
                writeInteger(at, value, code, BigInteger.ZERO, BigInteger.valueOf(255));
                return;
            case 'h':
                writeInteger(
                        at, value, code, BigInteger.valueOf(-32768), BigInteger.valueOf(32767));
                return;
            case 'H':
                writeInteger(at, value, code, BigInteger.ZERO, BigInteger.valueOf(65535));
                return;
            case 'i':
                writeInteger(
                        at,
                        value,
                        code,
                        BigInteger.valueOf(Integer.MIN_VALUE),
                        BigInteger.valueOf(Integer.MAX_VALUE));
                return;
            case 'I':
                writeInteger(
                        at, value, code, BigInteger.ZERO, BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE));
                return;
            case 'l':
            case 'q':
            case 'n':
                writeInteger(
                        at,
                        value,
                        code,
                        BigInteger.valueOf(Long.MIN_VALUE),
                        BigInteger.valueOf(Long.MAX_VALUE));
                return;
            case 'L':
            case 'Q':
            case 'N':
            case 'P':
                writeInteger(
                        at, value, code, BigInteger.ZERO, BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE));
                return;
            default:
                writeFloat(at, value, code);
        }
    }

    /** An integer element, read through the index protocol and checked against its format's range. */
    private void writeInteger(
            int at, PyObject value, char code, BigInteger minimum, BigInteger maximum) {
        if (!Numbers.isIndex(value)) {
            throw Slots.typeError("memoryview: invalid type for format '%c'", code);
        }
        BigInteger number = Numbers.asIndex(value);
        if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) {
            throw Slots.valueError("memoryview: invalid value for format '%c'", code);
        }
        writeLE(at, itemsize, number.longValue());
    }

    /** A floating-point element, which also accepts an integer the way the reference does. */
    private void writeFloat(int at, PyObject value, char code) {
        double number;
        if (value instanceof PyFloat real) {
            number = real.getValue();
        } else if (Numbers.isIndex(value)) {
            number = Numbers.asIndex(value).doubleValue();
        } else {
            throw Slots.typeError("memoryview: invalid type for format '%c'", code);
        }
        switch (code) {
            case 'f':
                writeLE(at, 4, Float.floatToIntBits((float) number) & 0xffffffffL);
                return;
            case 'd':
                writeLE(at, 8, Double.doubleToLongBits(number));
                return;
            default:
                writeLE(at, 2, doubleToHalf(number) & 0xffffL);
        }
    }

    /** IEEE 754 half-precision to double, including the subnormal and special values. */
    private static double halfToDouble(short bits) {
        int half = bits & 0xffff;
        int sign = half >>> 15;
        int exponent = (half >>> 10) & 0x1f;
        int mantissa = half & 0x3ff;
        double value;
        if (exponent == 0) {
            value = mantissa * Math.pow(2, -24);
        } else if (exponent == 31) {
            value = mantissa == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        } else {
            value = (1024 + mantissa) * Math.pow(2, exponent - 25);
        }
        return sign == 1 ? -value : value;
    }

    /** Double to IEEE 754 half-precision, rounding to nearest with ties to even. */
    private static short doubleToHalf(double number) {
        int single = Float.floatToIntBits((float) number);
        int sign = (single >>> 16) & 0x8000;
        int exponent = (single >>> 23) & 0xff;
        int mantissa = single & 0x7fffff;
        if (exponent == 0xff) {
            return (short) (sign | 0x7c00 | (mantissa != 0 ? 0x200 : 0));
        }
        int biased = exponent - 127 + 15;
        if (biased >= 0x1f) {
            return (short) (sign | 0x7c00);
        }
        if (biased <= 0) {
            if (biased < -10) {
                return (short) sign;
            }
            mantissa |= 0x800000;
            int shift = 14 - biased;
            int half = mantissa >>> shift;
            int rest = mantissa & ((1 << shift) - 1);
            int middle = 1 << (shift - 1);
            if (rest > middle || (rest == middle && (half & 1) == 1)) {
                half++;
            }
            return (short) (sign | half);
        }
        int half = (biased << 10) | (mantissa >>> 13);
        int rest = mantissa & 0x1fff;
        if (rest > 0x1000 || (rest == 0x1000 && (half & 1) == 1)) {
            half++;
        }
        return (short) (half | sign);
    }

    /**
     * A key of the form {@code (i,)}, {@code (a:b:c,)} or, for a multi-dimensional view, one index per
     * dimension.
     *
     * <p>A one-dimensional view has no second dimension, so a tuple is only a way to spell the same
     * single key back — and the tuple itself is refused in three ways before that: an empty one would
     * be a sub-view, a longer one indexes dimensions this view does not have, and a slice inside it is
     * multi-dimensional slicing. A multi-dimensional view reads one element from a full tuple of
     * indices, refuses a shorter tuple as a sub-view, and refuses a longer one by size; slices make it
     * either mixed (an invalid key) or wholly multi-dimensional (not implemented).
     */
    private PyObject tupleItem(PyTuple tuple) {
        int size = tuple.size();
        if (shape.length == 1) {
            if (size == 0) {
                throw unimplemented("sub-views are not implemented");
            }
            if (size > 1) {
                throw Slots.typeError("cannot index 1-dimension view with %d-element tuple", size);
            }
            PyObject element = tuple.at(0);
            if (Slices.isSlice(element)) {
                throw unimplemented("multi-dimensional slicing is not implemented");
            }
            if (Numbers.isIndex(element)) {
                return elementAt(position(indexOf(element)));
            }
            throw Slots.typeError("memoryview: invalid slice key");
        }
        if (size == 0 || size < shape.length) {
            throw unimplemented("sub-views are not implemented");
        }
        if (size > shape.length) {
            throw Slots.typeError(
                    "cannot index %d-dimension view with %d-element tuple", shape.length, size);
        }
        boolean sliced = false;
        boolean indexed = false;
        for (int d = 0; d < size; d++) {
            if (Slices.isSlice(tuple.at(d))) {
                sliced = true;
            } else if (Numbers.isIndex(tuple.at(d))) {
                indexed = true;
            } else {
                throw Slots.typeError("memoryview: invalid slice key");
            }
        }
        if (sliced) {
            if (indexed) {
                throw Slots.typeError("memoryview: invalid slice key");
            }
            throw unimplemented("multi-dimensional slicing is not implemented");
        }
        long at = offset;
        for (int d = 0; d < size; d++) {
            at += dimensionIndex(tuple.at(d), d) * strides[d];
        }
        return elementAt((int) at);
    }

    /** One index of a multi-dimensional key, folded into that dimension's extent. */
    private int dimensionIndex(PyObject key, int dimension) {
        return Numbers.wrapIndex(
                key,
                Numbers.asIndex(key),
                (int) shape[dimension],
                "index out of bounds on dimension " + (dimension + 1));
    }

    /** The tuple form of {@code setItem}, with the same refusals and one more for a slice. */
    private void assignTuple(PyTuple tuple, PyObject value) {
        int size = tuple.size();
        if (shape.length == 1) {
            if (size == 0) {
                throw unimplemented("sub-views are not implemented");
            }
            if (size > 1) {
                throw Slots.typeError("cannot index 1-dimension view with %d-element tuple", size);
            }
            PyObject element = tuple.at(0);
            if (Slices.isSlice(element)) {
                throw unimplemented(
                        "memoryview slice assignments are currently restricted to ndim = 1");
            }
            if (Numbers.isIndex(element)) {
                writeElement(position(indexOf(element)), value);
                return;
            }
            throw Slots.typeError("memoryview: invalid slice key");
        }
        if (size == 0 || size < shape.length) {
            throw unimplemented("sub-views are not implemented");
        }
        if (size > shape.length) {
            throw Slots.typeError(
                    "cannot index %d-dimension view with %d-element tuple", shape.length, size);
        }
        long at = offset;
        for (int d = 0; d < size; d++) {
            PyObject key = tuple.at(d);
            if (Slices.isSlice(key)) {
                throw unimplemented(
                        "memoryview slice assignments are currently restricted to ndim = 1");
            }
            if (!Numbers.isIndex(key)) {
                throw Slots.typeError("memoryview: invalid slice key");
            }
            at += dimensionIndex(key, d) * strides[d];
        }
        writeElement((int) at, value);
    }

    /**
     * {@code view[a:b:c] = source}: copy a snapshot of {@code source} element by element.
     *
     * <p>The source is read into an array before anything is written, which is what makes an
     * overlapping assignment behave as CPython's does: {@code view[1:4] = view[0:3]} moves the run
     * rather than smearing the first octet rightwards. A source that is not a buffer is refused in the
     * exporter's words, without the {@code memoryview:} prefix the element errors carry.
     */
    private void assignSlice(PySlice key, PyObject value) {
        byte[] source = bufferOctets(value);
        Window window = windowOf(key);
        if (source.length != (long) window.count() * itemsize) {
            throw Slots.valueError(
                    "memoryview assignment: lvalue and rvalue have different structures");
        }
        for (int i = 0; i < window.count(); i++) {
            int at = (int) (offset + window.at(i) * strides[0]);
            for (int j = 0; j < itemsize; j++) {
                writeOctet(at + j, source[i * itemsize + j] & 0xff);
            }
        }
    }

    /** A copy of the octets a buffer argument exports, in the order the argument presents them. */
    private static byte[] bufferOctets(PyObject value) {
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return PyBytes.octets(value).clone();
        }
        if (value instanceof PyMemoryView view) {
            view.check();
            return view.octets();
        }
        throw notBytesLike(value);
    }

    /** The buffer protocol's refusal, in the word-for-word form {@code PyObject_GetBuffer} uses. */
    private static PyRaise notBytesLike(PyObject value) {
        return Slots.typeError(
                "a bytes-like object is required, not '%s'", value.getType().filedName());
    }

    private static PyRaise unimplemented(String message) {
        return PyRaise.raise(ExceptionTypes.NOT_IMPLEMENTED_ERROR, message);
    }

    /**
     * A slice resolved against this window: the first index, the step, and how many elements it selects.
     *
     * <p>The step is a machine word because it becomes a stride; the count is an {@code int} because it
     * is never wider than the first extent it was resolved against.
     */
    private record Window(long start, long step, int count) {

        /** The {@code i}-th index the window selects. */
        long at(int i) {
            return start + (long) i * step;
        }
    }

    /**
     * A slice of this window over the same run: nothing is copied.
     *
     * <p>The selected start folds into the offset, the selected count replaces the first extent, and the
     * step multiplies the first stride — which is why {@code m[::2].strides} is {@code (2,)} and
     * {@code m[::-1]} is a window walked backwards from its far end. A multi-dimensional view may only be
     * sliced along its first dimension; the reference refuses an all-slice tuple, but a plain slice folds
     * into the leading extent exactly as it does for a one-dimensional view.
     */
    private PyMemoryView sliced(PySlice key) {
        Window window = windowOf(key);
        long[] nextShape = shape.clone();
        long[] nextStrides = strides.clone();
        nextShape[0] = window.count();
        nextStrides[0] = strides[0] * window.step();
        int nextOffset = (int) (offset + window.start() * strides[0]);
        return new PyMemoryView(
                exporter, obj, nextOffset, format, itemsize, nextShape, nextStrides, readonly);
    }

    private Window windowOf(PySlice key) {
        BigInteger[] bounds = Slices.longIndices(key, BigInteger.valueOf(shape[0]));
        long step = clampStep(bounds[2]);
        long start = bounds[0].longValue();
        long stop = bounds[1].longValue();
        long count =
                step > 0
                        ? (start < stop ? (stop - start - 1) / step + 1 : 0)
                        : (stop < start ? (start - stop - 1) / -step + 1 : 0);
        return new Window(start, step, (int) count);
    }

    /** A step wider than a machine word saturates to its edge, which is how CPython reports a stride. */
    private static long clampStep(BigInteger step) {
        if (step.bitLength() > 63) {
            return step.signum() < 0 ? -Long.MAX_VALUE : Long.MAX_VALUE;
        }
        return step.longValue();
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

    /** {@code __len__}: the first extent, which for a one-dimensional view is the element count. */
    @Override
    public PyObject length() {
        check();
        return PyInt.of(shape[0]);
    }

    /**
     * The cursor {@code iter(view)} answers: the view, the first extent, and the index reached.
     *
     * <p>{@code memoryiter_next} asks the view for its element on every step rather than copying the run
     * first, which is why a write through a writable view between two {@code next} calls is seen, and why
     * a released view refuses the step that comes after the release. Once the cursor has run out it drops
     * the view and can only keep reporting the end, so releasing afterwards changes nothing. A
     * multi-dimensional view has no sub-views here, so its first step refuses the walk.
     *
     * <p>CPython gives this cursor no docstring of its own, and the default {@code object} repr is what it
     * prints — {@code <memory_iterator object at 0x...>} — rather than the {@code <iterator object>} the
     * abstract cursor here would print.
     */
    private static final class MemoryIterator extends PyIterator {

        /** The view being walked; dropped once the end has been reported. */
        private PyMemoryView source;

        /** The first extent the walk started with, which a view cannot change. */
        private final int length;

        /** How many elements have been handed out. */
        private int at;

        MemoryIterator(PyMemoryView source) {
            super(PyType.MEMORY_ITERATOR);
            this.source = source;
            this.length = (int) source.shape[0];
        }

        @Override
        public PyObject nextOrNull() {
            if (source == null) {
                return null;
            }
            if (source.shape.length > 1) {
                throw unimplemented("multi-dimensional sub-views are not implemented");
            }
            if (at >= length) {
                source = null;
                return null;
            }
            source.check();
            return source.elementAt(source.position(at++));
        }

        @Override
        public String repr() {
            return "<" + getType().name() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }
    }
}
