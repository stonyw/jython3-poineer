package org.python.core;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * The window a {@code memoryview} holds over an octet string.
 *
 * <p>CPython's {@code memoryview} is a header over a {@code Py_buffer} the exporter filled in: the
 * buffer carries a pointer, a length, a format, a shape and strides, and the view never copies the
 * octets. Here the exporters are {@code bytes} and {@code bytearray}, so this class keeps the same four
 * facts in the only shapes those two can produce — a reference to the exporting value, an offset, an
 * octet count, the stride between neighbouring elements and the read-only bit that follows from which of
 * the two the reference holds. A view made from another view shares that reference rather than nesting
 * one view inside another, which is why {@code memoryview(memoryview(b"abc")).obj is b"abc"} holds and
 * why a released source is refused before anything is shared.
 *
 * <p>What a view is <em>of</em> stays the exporter, not the view: {@link #viewed()} answers the
 * {@code bytes} or {@code bytearray} itself, so a write through a view of a {@code bytearray} reaches the
 * run the program still holds. The remaining facts — {@code format}, {@code itemsize}, {@code ndim},
 * {@code shape}, {@code strides}, {@code suboffsets} — are the one-dimensional octet answer, because the
 * only way to make them anything else is {@code cast} and {@code toreadonly}, which the later slices
 * file.
 *
 * <p>A slice is another window over the same exporter: the start folds into the offset, the count
 * becomes the new length, and the step multiplies the stride, so {@code mv[::2].strides} is the stride of
 * the elements the slice selects rather than the stride of the octets underneath. Nothing is copied. The
 * stride is a machine word because CPython keeps it in a {@code Py_ssize_t}: a step wider than the word
 * is clamped to its edges, and a stride that overflows on a nested slice wraps exactly as the C
 * multiplication does.
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

    /**
     * The distance between two neighbouring elements, in octets.
     *
     * <p>A machine word, because CPython keeps a stride in a {@code Py_ssize_t} and a slice whose step is
     * wider than a Java {@code int} reports that width whole. A multiplication of two strides wraps here
     * the way the C one does, which is what makes a doubly-sliced huge step answer the same number.
     */
    private final long stride;

    /** Whether the exporter refuses writes; views of {@code bytes} are read-only. */
    private final boolean readonly;

    /** Set by {@code release}; every read but {@code repr} and {@code release} then refuses. */
    private boolean released;

    private PyMemoryView(
            PyObject exporter, int offset, int length, long stride, boolean readonly) {
        super(PyType.MEMORYVIEW);
        this.exporter = exporter;
        this.offset = offset;
        this.length = length;
        this.stride = stride;
        this.readonly = readonly;
    }

    /**
     * {@code memoryview(object)}: acquire the buffer {@code value} exports.
     *
     * <p>Only the two octet types export one here. A view is unwrapped to the value it looks into, which
     * is the identity {@code memoryview(memoryview(x)).obj is x} rests on — and the window keeps the
     * offset and stride of the view it came from, so re-viewing a slice preserves the slice. A released
     * source is refused with the same sentence every released read uses. Anything else answers the
     * TypeError {@code PyObject_GetBuffer} raises, naming the value's type in the style
     * {@code filedName()} prints.
     */
    static PyMemoryView of(PyObject value) {
        if (value instanceof PyMemoryView view) {
            view.check();
            return new PyMemoryView(
                    view.exporter, view.offset, view.length, view.stride, view.readonly);
        }
        if (value instanceof PyByteArray run) {
            return new PyMemoryView(run, 0, run.size(), 1, false);
        }
        if (value instanceof PyBytes octets) {
            return new PyMemoryView(octets, 0, octets.size(), 1, true);
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

    /** {@code memoryview.strides}: one stride, the distance between neighbouring elements. */
    PyObject strides() {
        check();
        return PyTuple.of(PyInt.of(stride));
    }

    /** {@code memoryview.suboffsets}: empty, because a basic view is not PIL-style. */
    PyObject suboffsets() {
        check();
        return PyTuple.of();
    }

    /**
     * The three contiguity flags, which for a one-dimensional view are one question.
     *
     * <p>CPython computes {@code c_contiguous} and {@code f_contiguous} from the strides and then sets
     * {@code contiguous} when either holds; with one dimension both are the same test, and an extent of
     * zero or one has no neighbouring elements to be non-adjacent.
     */
    boolean contiguous() {
        check();
        return length <= 1 || stride == 1;
    }

    /**
     * {@code view == other} and {@code view != other}: the octets the window selects, compared with the
     * octets another buffer selects.
     *
     * <p>CPython spells this one {@code memory_richcompare}, and it answers only for equality: the four
     * orderings raise {@code TypeError} at the point the slot declines, which is the
     * {@link PyNotImplemented#INSTANCE} this returns. A memoryview on the other side is compared through
     * the shapes first — the reference walks the two {@code shape} arrays and stops at an extent that is
     * zero — and then through the values the two formats describe; with both sides always one-dimensional
     * {@code B} views here, that is the length followed by the selected octets, so a strided slice equals
     * the contiguous run that spells the same elements.
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
            equal = view.released ? this == other : sameOctets(view);
        } else if (other instanceof PyBytes || other instanceof PyByteArray) {
            equal = Arrays.equals(octets(), PyBytes.octets(other));
        } else {
            return PyNotImplemented.INSTANCE;
        }
        return PyBool.of(cmp == CompareOp.EQ ? equal : !equal);
    }

    /**
     * The Java-level equality behind {@link PyObject#equals}, which is {@code ==} without the declined
     * case: the same octets, or the same object when either side has been released.
     */
    @Override
    public boolean eq(PyObject other) {
        if (other == this) {
            return true;
        }
        if (other instanceof PyMemoryView view) {
            return !released && !view.released && sameOctets(view);
        }
        return !released
                && (other instanceof PyBytes || other instanceof PyByteArray)
                && Arrays.equals(octets(), PyBytes.octets(other));
    }

    /**
     * {@code hash(view)}: the hash of the octets the window covers, refused for a writable view.
     *
     * <p>{@code memory_hash} hashes the exporter first and then hashes the buffer, so a view of a
     * {@code bytes} hashes exactly as those {@code bytes} do and a view of a {@code bytearray} would agree
     * with them too — if it had a hash at all. It does not: an exporter that can change under the hash
     * would break every dictionary it was put in, so CPython refuses a writable view with the sentence
     * below. A released view is refused earlier, with the same sentence every released read uses, because
     * the reference checks the window's state before the read-only bit.
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
        return PyHash.bytesHash(octets());
    }

    /**
     * {@code iter(view)}: a cursor over the elements, in the order the window presents them.
     *
     * <p>The cursor is {@link MemoryIterator}, the one type {@code Objects/memoryobject.c} declares for
     * this. It captures the length now — a view cannot make its exporter grow or shrink, so the number is
     * stable — and reads each element when the step is asked for, which is what makes a walk of a writable
     * view see a write that lands after the cursor was made.
     */
    @Override
    public PyObject iterator() {
        check();
        return new MemoryIterator(this);
    }

    /** The selected octets, copied out in the order the window presents them. */
    private byte[] octets() {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) byteAt(i);
        }
        return out;
    }

    /** Whether two live windows select the same octets, element for element. */
    private boolean sameOctets(PyMemoryView other) {
        if (length != other.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (byteAt(i) != other.byteAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** {@code view[i]}, {@code view[a:b:c]} and {@code view[i,]}: the element or the sub-window. */
    @Override
    public PyObject getItem(PyObject key) {
        check();
        if (Numbers.isIndex(key)) {
            return PyInt.of(byteAt(indexOf(key)));
        }
        if (Slices.isSlice(key)) {
            return sliced((PySlice) key);
        }
        if (key instanceof PyTuple tuple) {
            return tupleItem(tuple);
        }
        throw Slots.typeError("memoryview: invalid slice key");
    }

    /** {@code view[key] = value}: one octet, or a run through a slice, or the one element of a tuple. */
    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        check();
        if (readonly) {
            throw Slots.typeError("cannot modify read-only memory");
        }
        if (Numbers.isIndex(key)) {
            writeAt(indexOf(key), octetOf(value));
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

    /** One element, folded into the window's own bounds. */
    private int indexOf(PyObject key) {
        return Numbers.wrapIndex(
                key, Numbers.asIndex(key), length, "index out of bounds on dimension 1");
    }

    /** One element, read through the offset and stride; a released view never reaches here. */
    private int byteAt(long index) {
        int at = (int) (offset + index * stride);
        return exporter instanceof PyByteArray run ? run.byteAt(at) & 0xff : ((PyBytes) exporter).byteAt(at) & 0xff;
    }

    /** One element, written through the offset and stride; only a writable view reaches here. */
    private void writeAt(long index, int value) {
        ((PyByteArray) exporter).raw()[(int) (offset + index * stride)] = (byte) value;
    }

    /**
     * {@code view[a:b:c]}: a new window over the same exporter.
     *
     * <p>The bounds are unfolded by {@link Slices#longIndices}, which is the same arithmetic
     * {@code slice.indices} uses and so the same clipping: a bound past either edge lands on it rather
     * than raising, and only a zero step refuses. The step itself is clamped to the widest machine word
     * before it becomes a stride, because CPython reports the stride as a {@code Py_ssize_t} and a
     * wider step saturates there instead of wrapping.
     */
    private PyMemoryView sliced(PySlice key) {
        Window window = windowOf(key);
        return new PyMemoryView(
                exporter,
                (int) (offset + window.start() * stride),
                window.count(),
                stride * window.step(),
                readonly);
    }

    /**
     * A key of the form {@code (i,)} or {@code (a:b:c,)}.
     *
     * <p>A one-dimensional view has no second dimension, so a tuple is only a way to spell the same
     * single key back — and the tuple itself is refused in three ways before that: an empty one would be
     * a sub-view, a longer one indexes dimensions this view does not have, and a slice inside it is
     * multi-dimensional slicing. The first two are decided on the tuple's size alone, the third after.
     */
    private PyObject tupleItem(PyTuple tuple) {
        int size = tuple.size();
        if (size == 0) {
            throw PyRaise.raise(
                    ExceptionTypes.NOT_IMPLEMENTED_ERROR, "sub-views are not implemented");
        }
        if (size > 1) {
            throw Slots.typeError(
                    "cannot index 1-dimension view with %d-element tuple", size);
        }
        PyObject element = tuple.at(0);
        if (Slices.isSlice(element)) {
            throw Slots.notImplemented("multi-dimensional slicing");
        }
        if (Numbers.isIndex(element)) {
            return PyInt.of(byteAt(indexOf(element)));
        }
        throw Slots.typeError("memoryview: invalid slice key");
    }

    /** The tuple form of {@code setItem}, with the same three refusals and one more for a slice. */
    private void assignTuple(PyTuple tuple, PyObject value) {
        int size = tuple.size();
        if (size == 0) {
            throw PyRaise.raise(
                    ExceptionTypes.NOT_IMPLEMENTED_ERROR, "sub-views are not implemented");
        }
        if (size > 1) {
            throw Slots.typeError(
                    "cannot index 1-dimension view with %d-element tuple", size);
        }
        PyObject element = tuple.at(0);
        if (Slices.isSlice(element)) {
            throw PyRaise.raise(
                    ExceptionTypes.NOT_IMPLEMENTED_ERROR,
                    "memoryview slice assignments are currently restricted to ndim = 1");
        }
        if (Numbers.isIndex(element)) {
            writeAt(indexOf(element), octetOf(value));
            return;
        }
        throw Slots.typeError("memoryview: invalid slice key");
    }

    /**
     * {@code view[a:b:c] = source}: copy a snapshot of {@code source} element by element.
     *
     * <p>The source is read into an array before anything is written, which is what makes an overlapping
     * assignment behave as CPython's does: {@code view[1:4] = view[0:3]} moves the run rather than
     * smearing the first octet rightwards. A source that is not a buffer is refused in the exporter's
     * words, without the {@code memoryview:} prefix the element errors carry.
     */
    private void assignSlice(PySlice key, PyObject value) {
        byte[] source = bufferOctets(value);
        Window window = windowOf(key);
        if (source.length != window.count()) {
            throw Slots.valueError(
                    "memoryview assignment: lvalue and rvalue have different structures");
        }
        for (int i = 0; i < source.length; i++) {
            writeAt(window.at(i), source[i] & 0xff);
        }
    }

    /** One element's number, read as an index and checked against the format's range. */
    private static int octetOf(PyObject value) {
        if (!Numbers.isIndex(value)) {
            throw Slots.typeError("memoryview: invalid type for format 'B'");
        }
        BigInteger number = Numbers.asIndex(value);
        if (number.signum() < 0 || number.compareTo(BigInteger.valueOf(255)) > 0) {
            throw Slots.valueError("memoryview: invalid value for format 'B'");
        }
        return number.intValue();
    }

    /** A copy of the octets a buffer argument exports, in the order the argument presents them. */
    private static byte[] bufferOctets(PyObject value) {
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return PyBytes.octets(value).clone();
        }
        if (value instanceof PyMemoryView view) {
            view.check();
            byte[] out = new byte[view.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) view.byteAt(i);
            }
            return out;
        }
        throw notBytesLike(value);
    }

    /** The buffer protocol's refusal, in the word-for-word form {@code PyObject_GetBuffer} uses. */
    private static PyRaise notBytesLike(PyObject value) {
        return Slots.typeError(
                "a bytes-like object is required, not '%s'", value.getType().filedName());
    }

    /**
     * A slice resolved against this window: the first index, the step, and how many elements it selects.
     *
     * <p>The step is a machine word because it becomes a stride; the count is an {@code int} because it
     * is never wider than the length it was resolved against.
     */
    private record Window(long start, long step, int count) {

        /** The {@code i}-th index the window selects. */
        long at(int i) {
            return start + (long) i * step;
        }
    }

    private Window windowOf(PySlice key) {
        BigInteger[] bounds = Slices.longIndices(key, BigInteger.valueOf(length));
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

    /** {@code __len__}: the first extent, which for a one-dimensional view is the octet count. */
    @Override
    public PyObject length() {
        check();
        return PyInt.of(length);
    }

    /**
     * The cursor {@code iter(view)} answers: the view, the length it started with, and the index reached.
     *
     * <p>{@code memoryiter_next} asks the view for its element on every step rather than copying the run
     * first, which is why a write through a writable view between two {@code next} calls is seen, and why
     * a released view refuses the step that comes after the release. Once the cursor has run out it drops
     * the view and can only keep reporting the end, so releasing afterwards changes nothing.
     *
     * <p>CPython gives this cursor no docstring of its own, and the default {@code object} repr is what it
     * prints — {@code <memory_iterator object at 0x...>} — rather than the {@code <iterator object>} the
     * abstract cursor here would print.
     */
    private static final class MemoryIterator extends PyIterator {

        /** The view being walked; dropped once the end has been reported. */
        private PyMemoryView source;

        /** The element count the walk started with, which a view cannot change. */
        private final int length;

        /** How many elements have been handed out. */
        private int at;

        MemoryIterator(PyMemoryView source) {
            super(PyType.MEMORY_ITERATOR);
            this.source = source;
            this.length = source.length;
        }

        @Override
        public PyObject nextOrNull() {
            if (source == null) {
                return null;
            }
            if (at >= length) {
                source = null;
                return null;
            }
            source.check();
            return PyInt.of(source.byteAt(at++));
        }

        @Override
        public String repr() {
            return "<" + getType().name() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }
    }
}
