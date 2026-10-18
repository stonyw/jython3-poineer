package org.python.core;

public final class PySlice extends PyObject {

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

    @Override
    public long hash() {
        return PyHash.sliceHash(start.hash(), stop.hash(), step.hash());
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PySlice s
                && start.eq(s.start) && stop.eq(s.stop) && step.eq(s.step);
    }

    @Override
    public String repr() {
        return "slice(" + start.repr() + ", " + stop.repr() + ", " + step.repr() + ")";
    }
}
