package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code string.templatelib.Template}: the value a t-string literal evaluates to (PEP 750).
 *
 * <p>The literal's text and its substitutions are kept apart: {@code strings} holds one more entry
 * than {@code interpolations}, because every interpolation is preceded by the text between the
 * previous one and itself. That invariant is what makes {@code ''.join(strings)} the literal with
 * every substitution removed, and it is why concatenating two templates has to join the last string
 * of the left with the first of the right rather than laying them side by side.
 *
 * <p>{@code Template(...)} takes the pieces in source order and merges them the way CPython's
 * {@code template_new} does: adjacent strings become one entry and an interpolation with no text
 * between it and its neighbour contributes an empty string. No arguments at all is the empty
 * template, whose single string is empty.
 */
public final class PyTemplate extends PyObject {

    private final PyTuple strings;
    private final PyTuple interpolations;

    PyTemplate(PyTuple strings, PyTuple interpolations) {
        super(PyType.TEMPLATE);
        this.strings = strings;
        this.interpolations = interpolations;
    }

    public PyTuple strings() {
        return strings;
    }

    public PyTuple interpolations() {
        return interpolations;
    }

    /** What {@code BUILD_TEMPLATE} hands over: the compiler already produced the two tuples. */
    static PyTemplate build(PyTuple strings, PyTuple interpolations) {
        return new PyTemplate(strings, interpolations);
    }

    /** The value of {@code Template(*args)}, merging adjacent pieces in source order. */
    static PyObject creating(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("Template.__new__ only accepts *args arguments");
        }
        return merged(args);
    }

    static PyTemplate merged(PyObject[] parts) {
        List<PyObject> strings = new ArrayList<>();
        List<PyObject> interpolations = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (PyObject part : parts) {
            if (part instanceof PyStr str) {
                text.append(str.getValue());
            } else if (part instanceof PyInterpolation interpolation) {
                strings.add(PyStr.of(text.toString()));
                text.setLength(0);
                interpolations.add(interpolation);
            } else {
                throw Slots.typeError(
                        "Template.__new__ *args need to be of type 'str' or 'Interpolation', got %s",
                        part.getType().filedName());
            }
        }
        strings.add(PyStr.of(text.toString()));
        return new PyTemplate(
                PyTuple.of(strings.toArray(new PyObject[0])),
                PyTuple.of(interpolations.toArray(new PyObject[0])));
    }

    /** The {@code values} property: the substituted values, in source order. */
    PyObject values() {
        PyObject[] values = new PyObject[interpolations.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((PyInterpolation) interpolations.at(i)).value();
        }
        return PyTuple.of(values);
    }

    @Override
    public PyObject iterator() {
        return new PyTemplateIterator(this);
    }

    /**
     * {@code left + right}: the texts met at the seam become one string, and the interpolations are
     * simply laid end to end.
     */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op != BinaryOp.ADD) {
            return Slots.DECLINED;
        }
        if (!(other instanceof PyTemplate right)) {
            throw Slots.typeError(
                    "can only concatenate string.templatelib.Template (not \"%s\")"
                            + " to string.templatelib.Template",
                    other.getType().filedName());
        }
        PyObject[] joinedStrings =
                new PyObject[strings.size() + right.strings.size() - 1];
        int at = 0;
        for (int i = 0; i < strings.size() - 1; i++) {
            joinedStrings[at++] = strings.at(i);
        }
        String seam = ((PyStr) strings.at(strings.size() - 1)).getValue()
                + ((PyStr) right.strings.at(0)).getValue();
        joinedStrings[at++] = PyStr.of(seam);
        for (int i = 1; i < right.strings.size(); i++) {
            joinedStrings[at++] = right.strings.at(i);
        }
        PyObject[] joinedInterpolations =
                new PyObject[interpolations.size() + right.interpolations.size()];
        int interpAt = 0;
        for (int i = 0; i < interpolations.size(); i++) {
            joinedInterpolations[interpAt++] = interpolations.at(i);
        }
        for (int i = 0; i < right.interpolations.size(); i++) {
            joinedInterpolations[interpAt++] = right.interpolations.at(i);
        }
        return new PyTemplate(
                PyTuple.of(joinedStrings), PyTuple.of(joinedInterpolations));
    }

    @Override
    public String repr() {
        return "Template(strings=" + strings.repr()
                + ", interpolations=" + interpolations.repr() + ")";
    }

    /** Templates hash and compare by identity, as every other value CPython writes with {@code object}'s pair does. */
    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    PyObject reduce() {
        PyObject unpickle = PyRuntime.importModule("string.templatelib").getAttr("_template_unpickle");
        return PyTuple.of(unpickle, PyTuple.of(strings, interpolations));
    }
}
