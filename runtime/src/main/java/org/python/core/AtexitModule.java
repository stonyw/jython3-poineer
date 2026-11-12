package org.python.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code atexit}: the functions a program asks to have run when it finishes.
 *
 * <p>The reference's module is a small list of {@code (func, args, kwargs)} tuples, newest first, and
 * five methods over it — {@code Modules/atexitmodule.c}. {@code logging} imports it at module scope to
 * register its own shutdown, which is why the whole standard-library import chain stopped here.
 *
 * <p>The list is kept in the order a program builds it and read back the way the reference reads it:
 * {@code register} inserts at the front so the calls run last-registered-first, {@code unregister}
 * compares by {@code ==} from the end and removes the first match, and a name that was never
 * registered is not an error. Only the shutdown half is written here rather than in a finalization
 * step of its own: the launcher calls {@link #runExitFunctions()} just before it exits, which is the
 * one moment this runtime has for it.
 */
final class AtexitModule {

    private static final String DOC =
            "allow programmer to define multiple exit functions to be executed\n"
                    + "upon normal program termination.\n"
                    + "\n"
                    + "Two public functions, register and unregister, are defined.\n";

    /** One registered call: the function, the arguments after it, and the keyword names beside them. */
    private record Callback(PyObject function, PyObject[] args, PyTuple kwNames) {
    }

    /** Newest first, as {@code atexit_callbacks} holds them. */
    private static final List<Callback> callbacks = new ArrayList<>();

    private AtexitModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("atexit");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "atexit");
        module.set(
                "register",
                new PyNativeFunction("register", AtexitModule::register)
                        .documented(
                                "register($module, func, /, *args, **kwargs)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Register a function to be executed upon normal program termination\n"
                                        + "\n"
                                        + "    func - function to be called at exit\n"
                                        + "    args - optional arguments to pass to func\n"
                                        + "    kwargs - optional keyword arguments to pass to func\n"
                                        + "\n"
                                        + "    func is returned to facilitate usage as a decorator."));
        module.set(
                "_clear",
                PyNativeFunction.positional("_clear", args -> {
                    Arguments.none("atexit._clear()", args, null);
                    callbacks.clear();
                    return PyNone.INSTANCE;
                }).documented(
                        "_clear($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Clear the list of previously registered exit functions."));
        module.set(
                "unregister",
                PyNativeFunction.positional("unregister", args -> {
                    Arguments.exactlyOne("atexit.unregister()", args, null);
                    unregister(args[0]);
                    return PyNone.INSTANCE;
                }).documented(
                        "unregister($module, func, /)\n"
                                + "--\n"
                                + "\n"
                                + "Unregister an exit function which was previously registered using\n"
                                + "atexit.register\n"
                                + "\n"
                                + "    func - function to be unregistered"));
        module.set(
                "_run_exitfuncs",
                PyNativeFunction.positional("_run_exitfuncs", args -> {
                    Arguments.none("atexit._run_exitfuncs()", args, null);
                    runExitFunctions();
                    return PyNone.INSTANCE;
                }).documented(
                        "_run_exitfuncs($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Run all registered exit functions.\n"
                                + "\n"
                                + "If a callback raises an exception, it is logged with sys.unraisablehook."));
        module.set(
                "_ncallbacks",
                PyNativeFunction.positional("_ncallbacks", args -> {
                    Arguments.none("atexit._ncallbacks()", args, null);
                    return PyInt.of(callbacks.size());
                }).documented(
                        "_ncallbacks($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the number of registered exit functions."));
        return module;
    }

    /**
     * {@code register(func, *args, **kwargs)}: the function is filed and handed straight back.
     *
     * <p>The first argument is positional only — a keyword named {@code func} is one of the extra
     * keywords, exactly as in the reference, where the call is checked for a positional argument
     * before anything else — and it has to be callable, which is checked before anything is stored.
     */
    private static PyObject register(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional == 0) {
            throw Slots.typeError("register() takes at least 1 argument (%d given)", positional);
        }
        PyObject function = args[0];
        if (!function.isCallable()) {
            throw Slots.typeError("the first argument must be callable");
        }
        int names = kwNames == null ? 0 : kwNames.length;
        PyObject[] rest = Arrays.copyOfRange(args, 1, args.length);
        PyTuple keywords = null;
        if (names > 0) {
            PyObject[] written = new PyObject[names];
            for (int i = 0; i < names; i++) {
                written[i] = PyStr.interned(kwNames[i]);
            }
            keywords = PyTuple.of(written);
        }
        callbacks.add(0, new Callback(function, rest, keywords));
        return function;
    }

    /**
     * {@code unregister(func)}: removes the newest registration that compares equal to {@code func}.
     *
     * <p>An absent function is not an error — the reference's loop simply finds nothing — and the
     * comparison is {@code ==} rather than identity, which is what makes a bound method unregister the
     * registration it was made from.
     */
    private static void unregister(PyObject function) {
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            if (Slots.compareTrue(CompareOp.EQ, function, callbacks.get(i).function())) {
                callbacks.remove(i);
                return;
            }
        }
    }

    /**
     * Runs the callbacks newest first, dropping each before it runs so that a callback registering
     * another one is queued for the next round rather than run twice.
     *
     * <p>An exception inside a callback is reported the way the reference reports an unraisable one and
     * does not stop the rest, because a program's other cleanup still has to happen.
     */
    static void runExitFunctions() {
        while (!callbacks.isEmpty()) {
            Callback callback = callbacks.remove(0);
            try {
                Slots.call(callback.function(), callback.args(), callback.kwNames());
            } catch (PyRaise raised) {
                PyRuntime.stderr()
                        .writeText("Exception ignored in atexit callback "
                                + callback.function().repr() + ":\n");
                TracebackReport.print(raised.value());
            }
        }
    }
}
