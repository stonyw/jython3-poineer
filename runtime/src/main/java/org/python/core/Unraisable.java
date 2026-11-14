package org.python.core;

/**
 * {@code sys.unraisablehook}: where an exception with no frame left to catch it is reported.
 *
 * <p>CPython has one entry point for this — {@code PyErr_WriteUnraisable} — and it calls whatever
 * {@code sys.unraisablehook} names, handing it an {@code UnraisableHookArgs} record. The default hook
 * is {@code sys.__unraisablehook__}, which writes the message and the traceback to {@code
 * sys.stderr}. The record's type is not reachable from Python ({@code builtins} does not export it, as
 * the reference does not), so the only way to hold one is to have the hook called with it.
 *
 * <p>The two shapes the default hook formats are the reference's: an {@code err_msg} on its own names
 * the caller — {@code Exception ignored in atexit callback <function ...>} — and is followed by
 * {@code ":\n"}; an object with no message is named as {@code Exception ignored in: <repr>}. The
 * traceback is written after either.
 *
 * <p>Which callers this runtime actually has is a fact about its finalization rather than about this
 * class. There is no deterministic finalizer here: a {@code __del__} is not called when the last
 * reference goes, no weakref callback runs, and a suspended generator is not closed at shutdown, so
 * the three sites the reference reports from — the deallocator, the weakref callback, and the
 * generator being closed — are not reachable. The one site that is real is {@code atexit}: a callback
 * that raises goes through {@link #report}, so it prints the same text the reference prints and a
 * program that replaces the hook receives the record. The milestone notes record the rest as absent
 * rather than pretend a finalizer ran.
 */
final class Unraisable {

    /**
     * The record's type, which stands where {@code builtins.UnraisableHookArgs} stands in the
     * reference. It is not filed in {@code builtins}, because the reference does not expose it either.
     */
    private static final PyType ARGS = PyType.builtin("UnraisableHookArgs");

    private Unraisable() {
    }

    /** The record one unraisable exception is reported as. */
    static final class Record extends PyObject {

        private final PyObject excType;

        private final PyObject excValue;

        private final PyObject excTraceback;

        private final PyObject errMsg;

        private final PyObject object;

        Record(PyObject excType, PyObject excValue, PyObject excTraceback, PyObject errMsg,
                PyObject object) {
            super(ARGS);
            this.excType = excType;
            this.excValue = excValue;
            this.excTraceback = excTraceback;
            this.errMsg = errMsg;
            this.object = object;
        }

        /** The five read-only fields, spelled as CPython's {@code UnraisableHookArgs} spells them. */
        @Override
        public PyObject getAttr(String name) {
            switch (name) {
                case "exc_type":
                    return excType;
                case "exc_value":
                    return excValue;
                case "exc_traceback":
                    return excTraceback;
                case "err_msg":
                    return errMsg;
                case "object":
                    return object;
                default:
                    return super.getAttr(name);
            }
        }
    }

    /** {@code sys.__unraisablehook__}: the text the reference writes, with nothing else to it. */
    static PyNativeFunction defaultHook() {
        return new PyNativeFunction("__unraisablehook__", (args, kwNames) -> {
            if (kwNames != null) {
                throw Slots.typeError("sys.unraisablehook takes no keyword arguments");
            }
            if (args.length != 1) {
                throw Slots.typeError(
                        "sys.unraisablehook() takes exactly one argument (%d given)", args.length);
            }
            if (!(args[0] instanceof Record record)) {
                throw Slots.typeError(
                        "sys.unraisablehook argument type must be UnraisableHookArgs");
            }
            write(record);
            return PyNone.INSTANCE;
        }).documented(
                "__unraisablehook__($module, unraisable, /)\n"
                        + "--\n"
                        + "\n"
                        + "Handle an unraisable exception.\n"
                        + "\n"
                        + "The unraisable argument has the following attributes:\n"
                        + "\n"
                        + "* exc_type: Exception type.\n"
                        + "* exc_value: Exception value.\n"
                        + "* exc_traceback: Exception traceback.\n"
                        + "* err_msg: Error message.\n"
                        + "* object: Object causing the exception.");
    }

    /**
     * Reports {@code value} the way {@code PyErr_WriteUnraisableMsg} does: through whatever hook
     * {@code sys.unraisablehook} currently names.
     *
     * <p>A hook that itself fails has nowhere else to go, so the reference writes to the stream it
     * started with rather than recursing; so does this.
     */
    static void report(PyObject object, String message, PyBaseException value) {
        PyObject hook = PyRuntime.sysAttribute("unraisablehook");
        if (hook == null) {
            return;
        }
        PyObject traceback = value.traceback();
        Record record = new Record(
                value.getType(),
                value,
                traceback == null ? PyNone.INSTANCE : traceback,
                message == null ? PyNone.INSTANCE : PyStr.of(message),
                object == null ? PyNone.INSTANCE : object);
        try {
            Slots.call(hook, new PyObject[] {record}, null);
        } catch (PyRaise ignored) {
            write(record);
        }
    }

    /** The default hook's text, split out so a failing custom hook can fall back to it. */
    private static void write(Record record) {
        StringBuilder text = new StringBuilder();
        if (!(record.errMsg instanceof PyNone)) {
            text.append(Slots.str(record.errMsg)).append(":\n");
        } else if (!(record.object instanceof PyNone)) {
            text.append("Exception ignored in: ").append(Slots.repr(record.object)).append('\n');
        } else {
            text.append("Exception ignored in: None\n");
        }
        if (record.excValue instanceof PyBaseException error) {
            text.append(TracebackReport.of(error));
        }
        // The reference writes through `sys.stderr`, which is what lets a program capture the report by
        // replacing that stream; only a failure to write falls back to the stream the process started
        // with.
        PyObject stream = PyRuntime.sysAttribute("stderr");
        if (stream == null || stream instanceof PyNone) {
            PyRuntime.stderr().writeText(text.toString());
            return;
        }
        try {
            Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of(text.toString())}, null);
        } catch (PyRaise ignored) {
            PyRuntime.stderr().writeText(text.toString());
        }
    }
}
