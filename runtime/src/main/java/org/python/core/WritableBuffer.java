package org.python.core;

/**
 * A caller's writable buffer seen as octets: what {@code readinto} fills.
 *
 * <p>The reference takes the buffer protocol's writable view, so the three shapes a caller can hand
 * over are the run of a {@code bytearray}, a {@code memoryview} that is not read-only, and the backing
 * octets of an {@code array.array}. A read-only view and anything that exports no buffer are refused by
 * the caller's own sentence, which is why {@link #of} answers null rather than raising.
 */
final class WritableBuffer {

    private final PyByteArray run;
    private final PyMemoryView window;
    private final PyArray elements;

    /** How many octets the target spans: for an array, its element count times the item size. */
    private final int length;

    private WritableBuffer(PyByteArray run, PyMemoryView window, PyArray elements, int length) {
        this.run = run;
        this.window = window;
        this.elements = elements;
        this.length = length;
    }

    /** The target as octets, or null when it exports no writable buffer. */
    static WritableBuffer of(PyObject value) {
        if (value instanceof PyByteArray bytes) {
            return new WritableBuffer(bytes, null, null, bytes.size());
        }
        if (value instanceof PyMemoryView view && !view.readonly()) {
            return new WritableBuffer(null, view, null, view.byteLength());
        }
        if (value instanceof PyArray array) {
            return new WritableBuffer(null, null, array, array.size() * array.itemSizeValue());
        }
        return null;
    }

    /** The number of octets a fill may write, which is what {@code readinto} asks its layer for. */
    int length() {
        return length;
    }

    /** Copy the first {@code count} octets of {@code source} into the target at {@code offset}. */
    void write(int offset, byte[] source, int count) {
        if (run != null) {
            System.arraycopy(source, 0, run.raw(), offset, count);
            return;
        }
        if (window != null) {
            window.writeWindow(offset, source, 0, count);
            return;
        }
        for (int i = 0; i < count; i++) {
            elements.setByteAt(offset + i, source[i]);
        }
    }
}
