package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Argument binding shared by the two objects the import system builds from Python.
 *
 * <p>Only the two constructors use it — {@code ModuleSpec(...)} and {@code SourceFileLoader(...)} — and
 * both are plain positional-or-keyword signatures. The too-many-positional sentence is left to the
 * caller because CPython counts the receiver in it, and only the caller knows whether it has one.
 */
final class ImportArguments {

    private ImportArguments() {
    }

    /**
     * Bind {@code args} against {@code names}, refusing an unknown or repeated keyword and naming every
     * required argument that is missing.
     */
    static PyObject[] bind(
            String qualified, PyObject[] args, String[] kwNames, String[] names, int required) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        PyObject[] values = new PyObject[names.length];
        System.arraycopy(args, 0, values, 0, positional);
        for (int i = 0; i < keywords; i++) {
            int slot = indexOf(names, kwNames[i]);
            if (slot < 0) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", qualified, kwNames[i]);
            }
            if (values[slot] != null) {
                throw Slots.typeError(
                        "%s() got multiple values for argument '%s'", qualified, kwNames[i]);
            }
            values[slot] = args[positional + i];
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < required; i++) {
            if (values[i] == null) {
                missing.add("'" + names[i] + "'");
            }
        }
        if (missing.size() == 1) {
            throw Slots.typeError(
                    "%s() missing 1 required positional argument: %s", qualified, missing.get(0));
        }
        if (missing.size() > 1) {
            throw Slots.typeError(
                    "%s() missing %d required positional arguments: %s",
                    qualified, missing.size(), enumerate(missing));
        }
        return values;
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code 'a' and 'b'}, or {@code 'a', 'b', and 'c'} — how CPython joins the missing names. */
    private static String enumerate(List<String> names) {
        if (names.size() == 2) {
            return names.get(0) + " and " + names.get(1);
        }
        return String.join(", ", names.subList(0, names.size() - 1))
                + ", and " + names.get(names.size() - 1);
    }
}
