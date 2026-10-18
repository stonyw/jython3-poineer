package org.python.core;

/**
 * Argument-count checking for native methods, in CPython's words.
 *
 * The two families of message are not interchangeable and both appear in CPython's own tests. A method
 * declared with the modern vectorcall signature says {@code str.upper() takes no arguments (1 given)},
 * naming itself qualified, and counts a keyword among its arguments: {@code pow(2, 3, 5, mod=7)} is
 * "takes at most 3 arguments (4 given)". A method still using the legacy
 * {@code _PyArg_UnpackStack} says {@code getattr expected at least 2 arguments, got 1}, naming itself
 * bare and counting only what arrived positionally — {@code min(default=0, key=None)} has no argument
 * at all in that sense. Which family a method belongs to is a property of CPython's source, so each
 * helper here takes the name in the form its family prints and reads the count its family uses.
 *
 * Argument lists follow CPython's vectorcall layout, which {@link PyFunction} also relies on: the
 * keyword values are the trailing entries of {@code args}, so a call gave {@code args.length}
 * arguments altogether and only the prefix {@link #positional} gave them positionally.
 */
final class Arguments {

    private Arguments() {
    }

    /** A method that takes nothing: {@code upper()}, {@code bit_length()}, {@code is_integer()}. */
    static void none(String qualified, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s takes no keyword arguments", qualified);
        }
        if (args.length != 0) {
            throw Slots.typeError("%s takes no arguments (%d given)", qualified, args.length);
        }
    }

    /** A METH_O method: exactly one argument, and the message counts what arrived. */
    static void exactlyOne(String qualified, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s takes no keyword arguments", qualified);
        }
        if (args.length != 1) {
            throw Slots.typeError("%s takes exactly one argument (%d given)", qualified, args.length);
        }
    }

    /** The bare-named legacy form, which counts only what arrived positionally. */
    static void atLeast(String plain, PyObject[] args, String[] kwNames, int wanted) {
        unpack(plain, args, kwNames, wanted, Integer.MAX_VALUE);
    }

    static void atMost(String plain, PyObject[] args, String[] kwNames, int limit) {
        unpack(plain, args, kwNames, 0, limit);
    }

    /** Both bounds at once, which is what most of the legacy methods actually declare. */
    static void between(String plain, PyObject[] args, String[] kwNames, int wanted, int limit) {
        unpack(plain, args, kwNames, wanted, limit);
    }

    /** A legacy method taking exactly {@code wanted} arguments: {@code insert expected 2 arguments, got 1}. */
    static void exactly(String plain, PyObject[] args, String[] kwNames, int wanted) {
        unpack(plain, args, kwNames, wanted, wanted);
    }

    /**
     * {@code _PyArg_UnpackStack}'s three messages, chosen by how the method declares itself.
     *
     * A method with one bound says "at most" or "at least"; a method whose minimum is its maximum
     * drops the qualifier and names that one number whichever way the call went wrong — which is why
     * {@code hasattr(1)} and {@code hasattr(1, 2, 3)} both say {@code expected 2 arguments}.
     */
    private static void unpack(
            String plain, PyObject[] args, String[] kwNames, int wanted, int limit) {
        int given = positional(args, kwNames);
        if (given >= wanted && given <= limit) {
            return;
        }
        if (wanted == limit) {
            throw Slots.typeError(
                    "%s expected %d argument%s, got %d", plain, wanted, plural(wanted), given);
        }
        if (given > limit) {
            throw Slots.typeError(
                    "%s expected at most %d argument%s, got %d", plain, limit, plural(limit), given);
        }
        throw Slots.typeError(
                "%s expected at least %d argument%s, got %d", plain, wanted, plural(wanted), given);
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }

    /**
     * Parameters that may also be given by keyword, laid out in positional order.
     *
     * @param defaults the trailing defaults, right-aligned against {@code names} as Python's are
     */
    static PyObject[] fill(
            String qualified,
            PyObject[] args,
            String[] kwNames,
            String[] names,
            PyObject[] defaults) {
        PyObject[] values = new PyObject[names.length];
        for (int i = 0; i < values.length; i++) {
            int fromEnd = values.length - 1 - i;
            values[i] = fromEnd < defaults.length ? defaults[defaults.length - 1 - fromEnd] : null;
        }
        int positional = positional(args, kwNames);
        for (int i = 0; i < positional; i++) {
            values[i] = args[i];
        }
        if (kwNames == null) {
            return values;
        }
        for (int i = 0; i < kwNames.length; i++) {
            int at = position(names, kwNames[i]);
            if (at < 0) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", qualified, kwNames[i]);
            }
            if (at < positional) {
                throw Slots.typeError(
                        "%s() got multiple values for argument '%s'", qualified, kwNames[i]);
            }
            values[at] = args[positional + i];
        }
        return values;
    }

    /** A positional-only signature: {@code getattr() takes no keyword arguments}. */
    static void noKeywords(String qualified, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("%s takes no keyword arguments", qualified);
        }
    }

    /** {@code pow() takes at most 3 arguments (4 given)}, which counts keywords as arguments. */
    static void atMostQualified(String qualified, PyObject[] args, String[] kwNames, int limit) {
        if (count(args, kwNames) > limit) {
            throw Slots.typeError(
                    "%s() takes at most %d arguments (%d given)",
                    qualified, limit, count(args, kwNames));
        }
    }

    /** {@code sum() takes at least 1 positional argument (0 given)}, which does not. */
    static void positionalAtLeast(String qualified, PyObject[] args, String[] kwNames, int wanted) {
        int given = positional(args, kwNames);
        if (given < wanted) {
            throw Slots.typeError(
                    "%s() takes at least %d positional argument%s (%d given)",
                    qualified, wanted, wanted == 1 ? "" : "s", given);
        }
    }

    /** {@code pow() missing required argument 'exp' (pos 2)}, for a named parameter with no default. */
    static void required(String qualified, String[] names, PyObject[] values, int index) {
        if (values[index] == null) {
            throw Slots.typeError(
                    "%s() missing required argument '%s' (pos %d)",
                    qualified, names[index], index + 1);
        }
    }

    /**
     * {@code 'x' is an invalid keyword argument for enumerate()}, which is a third family again.
     *
     * Only the few builtins written against {@code PyArg_ParseTupleAndKeywords} say it this way; the
     * rest use {@link #fill}'s complaint.
     */
    static void rejectKeywords(String plain, String[] kwNames, String[] names) {
        if (kwNames == null) {
            return;
        }
        for (String keyword : kwNames) {
            if (position(names, keyword) < 0) {
                throw Slots.typeError(
                        "'%s' is an invalid keyword argument for %s()", keyword, plain);
            }
        }
    }

    private static int position(String[] names, String keyword) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(keyword)) {
                return i;
            }
        }
        return -1;
    }

    /** How many arguments the call site pushed, keyword values included. */
    static int count(PyObject[] args, String[] kwNames) {
        return args.length;
    }

    /** How many of them arrived positionally, which is the prefix {@link #fill} reads first. */
    static int positional(PyObject[] args, String[] kwNames) {
        return args.length - (kwNames == null ? 0 : kwNames.length);
    }
}
