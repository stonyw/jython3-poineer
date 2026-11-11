package org.python.core;

/**
 * The cursor {@code iter(template)} answers with: the template's parts in source order.
 *
 * <p>It alternates a string, an interpolation, a string, and so on, starting and ending with a
 * string. The empty strings between two adjacent interpolations are skipped, because CPython's
 * {@code template_iternext} skips them — the iteration is of the source's parts rather than of the
 * {@code strings} tuple, which always has one entry more than there are interpolations.
 */
final class PyTemplateIterator extends PyIterator {

    private final PyTemplate template;
    private int at;

    PyTemplateIterator(PyTemplate template) {
        super(PyType.TEMPLATE_ITERATOR);
        this.template = template;
    }

    @Override
    public PyObject nextOrNull() {
        PyTuple strings = template.strings();
        PyTuple interpolations = template.interpolations();
        int parts = strings.size() + interpolations.size();
        while (at < parts) {
            int index = at++;
            if ((index & 1) == 0) {
                PyStr text = (PyStr) strings.at(index / 2);
                if (text.getValue().isEmpty()) {
                    continue;
                }
                return text;
            }
            return interpolations.at(index / 2);
        }
        return null;
    }

    @Override
    public String repr() {
        return "<" + getType().filedName() + " object at 0x"
                + Integer.toHexString(System.identityHashCode(this)) + ">";
    }
}
