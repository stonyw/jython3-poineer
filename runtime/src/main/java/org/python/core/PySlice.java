package org.python.core;

public final class PySlice extends PyObject {

    /**
     * The two rows {@code slice_methods} files (Objects/sliceobject.c:571).
     *
     * <p>Neither sentence is clinic output: {@code slice_indices_doc} opens with {@code S.indices(len)},
     * which is not the name the row is filed under, so the signature knife finds no table above either text
     * and both read theirs out of the flags instead — {@code ($self, object, /)} for the {@code METH_O} and
     * {@code ($self, /)} for the {@code METH_NOARGS}. The three fields beside them are not in this table;
     * they are a {@code PyMemberDef} array, and are filed by the dictionary as its own offsets.
     */
    private static final Members METHODS = Members.of(PyType.SLICE)
            .addOne(
                    "indices",
                    "S.indices(len) -> (start, stop, stride)\n"
                            + "\n"
                            + "Assuming a sequence of length len, calculate the start and stop\n"
                            + "indices, and the stride length of the extended slice described by\n"
                            + "S. Out of bounds indices are clipped in a manner consistent with the\n"
                            + "handling of normal slices.",
                    (self, length) -> Slices.indices((PySlice) self, length))
            .addNoArgument(
                    "__reduce__",
                    "Return state information for pickling.",
                    self -> ((PySlice) self).reduced());

    private final PyObject start;
    private final PyObject stop;
    private final PyObject step;

    private PySlice(PyObject start, PyObject stop, PyObject step) {
        super(PyType.SLICE);
        this.start = start;
        this.stop = stop;
        this.step = step;
    }

    public static PySlice of(PyObject start, PyObject stop, PyObject step) {
        return new PySlice(start, stop, step);
    }

    public PyObject start() {
        return start;
    }

    public PyObject stop() {
        return stop;
    }

    public PyObject step() {
        return step;
    }

    /** The three fields in the order {@code slice_richcompare} and {@code slice_reduce} read them. */
    PyObject[] parts() {
        return new PyObject[] {start, stop, step};
    }

    /** {@code slice_reduce}: the class, and the three bounds it would be built from again. */
    PyObject reduced() {
        return PyTuple.of(PyType.SLICE, PyTuple.of(start, stop, step));
    }

    @Override
    public long hash() {
        return PyHash.sliceHash(start.hash(), stop.hash(), step.hash());
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PySlice s
                && start.eq(s.start) && stop.eq(s.stop) && step.eq(s.step);
    }

    /**
     * {@code slice_richcompare}: the three fields as a tuple, with the identity shortcut in front.
     *
     * <p>The operator asks this rather than {@link #eq}, so the two orderings a slice has no answer for
     * still reach {@link Slices} and become the {@code TypeError} naming both types.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return Slices.compare(cmp, this, other);
    }

    @Override
    public String repr() {
        return "slice(" + start.repr() + ", " + stop.repr() + ", " + step.repr() + ")";
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }
}
