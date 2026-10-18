package org.python.core;

/**
 * A cell: the box a variable shared between scopes lives in.
 *
 * A frame's variable slot holds either the value or, once {@code MAKE_CELL} has run on it, a cell
 * containing the value. Both {@code LOAD_FAST} and {@code LOAD_DEREF} accept either, and which one
 * they hand out differs: {@code LOAD_DEREF} reads through the box, {@code LOAD_FAST} hands the box
 * itself over — which is how a closure tuple of cells gets built by ordinary-looking code.
 */
public final class PyCell extends PyObject {

    private PyObject value;

    PyCell(PyObject value) {
        super(PyType.CELL);
        this.value = value;
    }

    /**
     * The box's content, or null when it is empty.
     *
     * An empty cell is reachable: read a shared variable before its scope has bound it. The name of
     * the variable is not known here — only the frame that indexes the cell does — so the reading
     * code raises, with {@link Frame} as the place that can spell the message out.
     */
    public PyObject value() {
        return value;
    }

    public boolean empty() {
        return value == null;
    }

    public void set(PyObject value) {
        this.value = value;
    }

    @Override
    public String repr() {
        return "<cell: " + (value == null ? "<empty>" : value.repr()) + ">";
    }
}
