package org.python.core;

import java.math.BigInteger;

/**
 * {@code _signal}: the C half of the {@code signal} module, as much of it as the standard library
 * this runtime runs asks for.
 *
 * <p>There is no signal delivery to imitate: a Python program here cannot receive {@code SIGINT}
 * from the operating system, because the interpreter is a JVM process and the handlers would be
 * JVM-wide rather than per-runtime. So what is kept is the bookkeeping the reference keeps in
 * Python-visible state — which handler a signal number was last given — and the constants and the
 * one callable {@code signal.py} and {@code unittest.signals} read at module scope.
 *
 * <p>{@code SIGKILL} is here for {@code subprocess.Popen.kill}, which names it before it asks whether
 * there is still a process to signal; the standard library's own test harness calls that in a
 * {@code finally} on every child it starts.
 *
 * <p>The handler table lives in this module's builder rather than in a static field, so two runtimes
 * in one JVM do not see each other's handlers. {@code SIGINT} starts out holding
 * {@link #defaultIntHandler}, which is what the reference's initialization installs: reading
 * {@code signal.getsignal(signal.SIGINT)} before anything is set answers that function rather than
 * {@code SIG_DFL}, and {@code unittest.signals} relies on being able to call it.
 */
final class SignalModule {

    /** The two handler sentinels, whose numbers the reference fixes at 0 and 1. */
    private static final int SIG_DFL = 0;
    private static final int SIG_IGN = 1;

    /** The one signal {@code signal.py} has to be able to name for {@code unittest} to import. */
    private static final int SIGINT = 2;

    /** The POSIX number; {@code subprocess.kill} names it, and it is only read, never delivered. */
    private static final int SIGKILL = 9;

    /** macOS's {@code NSIG}: the size of the signal table, one past the highest valid number. */
    private static final int NSIG = 32;

    /**
     * macOS's signal numbers, one per name.
     *
     * <p>{@code signal.py} builds {@code signal.Signals} out of every upper-case name this module
     * defines, so the whole table has to be present and not just the numbers the runtime itself
     * names. They are read, never delivered.
     */
    private static final Object[][] SIGNALS = {
        {"SIGHUP", 1}, {"SIGINT", 2}, {"SIGQUIT", 3}, {"SIGILL", 4},
        {"SIGTRAP", 5}, {"SIGABRT", 6}, {"SIGEMT", 7}, {"SIGFPE", 8},
        {"SIGKILL", 9}, {"SIGBUS", 10}, {"SIGSEGV", 11}, {"SIGSYS", 12},
        {"SIGPIPE", 13}, {"SIGALRM", 14}, {"SIGTERM", 15}, {"SIGURG", 16},
        {"SIGSTOP", 17}, {"SIGTSTP", 18}, {"SIGCONT", 19}, {"SIGCHLD", 20},
        {"SIGTTIN", 21}, {"SIGTTOU", 22}, {"SIGIO", 23}, {"SIGXCPU", 24},
        {"SIGXFSZ", 25}, {"SIGVTALRM", 26}, {"SIGPROF", 27}, {"SIGWINCH", 28},
        {"SIGINFO", 29}, {"SIGUSR1", 30}, {"SIGUSR2", 31},
    };

    private SignalModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_signal");
        PyRuntime.markBuiltIn(module, "_signal");

        module.set("SIG_DFL", PyInt.of(SIG_DFL));
        module.set("SIG_IGN", PyInt.of(SIG_IGN));
        module.set("SIGINT", PyInt.of(SIGINT));
        module.set("SIGKILL", PyInt.of(SIGKILL));
        for (Object[] signal : SIGNALS) {
            module.set((String) signal[0], PyInt.of((Integer) signal[1]));
        }
        // NSIG is the number of entries in the platform's signal table, and the reference's own value
        // here is macOS's 32. A signal number past it is out of range in the reference too; this
        // runtime does not deliver signals, so the number is only ever read.
        module.set("NSIG", PyInt.of(NSIG));
        // `signal.valid_signals` wraps each of these numbers in the `Signals` enum, so the answer is
        // the whole table the platform has, which here is 1 through NSIG-1.
        module.set("valid_signals", PyNativeFunction.positional("valid_signals", args -> {
            Arguments.none("valid_signals", args, null);
            PyObject[] numbers = new PyObject[NSIG - 1];
            for (int n = 1; n < NSIG; n++) {
                numbers[n - 1] = PyInt.of(n);
            }
            return PySet.of(numbers);
        }));

        // One table for this module: keyed by signal number, holding the handler last installed.
        PyDict handlers = PyDict.empty();
        PyObject defaultIntHandler =
                new PyNativeFunction("default_int_handler", SignalModule::defaultIntHandler);
        module.set("default_int_handler", defaultIntHandler);
        handlers.put(PyInt.of(SIGINT), defaultIntHandler);

        module.set("getsignal", PyNativeFunction.positional("getsignal", args -> {
            Arguments.exactly("getsignal", args, null, 1);
            int number = signalNumber("getsignal", args[0]);
            PyObject handler = handlers.get(PyInt.of(number));
            return handler == null ? PyInt.of(SIG_DFL) : handler;
        }));
        module.set("signal", PyNativeFunction.positional("signal", args -> {
            Arguments.exactly("signal", args, null, 2);
            int number = signalNumber("signal", args[0]);
            PyObject handler = checkedHandler(args[1]);
            PyObject previous = handlers.get(PyInt.of(number));
            handlers.put(PyInt.of(number), handler);
            return previous == null ? PyInt.of(SIG_DFL) : previous;
        }));
        // The descriptor a signal handler would write a byte to. There is no delivery to wake anyone
        // from here (see the class comment), so the number is kept for the caller that saved it and
        // handed back on the next call, exactly as the reference's save-and-restore pattern expects;
        // nothing is ever written to it.
        int[] wakeupFd = {-1};
        module.set("set_wakeup_fd", PyNativeFunction.positional("set_wakeup_fd", args -> {
            Arguments.between("set_wakeup_fd", args, null, 1, 2);
            BigInteger fd = Numbers.asIndex(args[0]);
            if (fd.bitLength() > 31 || fd.intValue() < -1) {
                throw Slots.valueError("invalid fd");
            }
            int previous = wakeupFd[0];
            wakeupFd[0] = fd.intValue();
            return PyInt.of(previous);
        }));
        return module;
    }

    /**
     * {@code default_int_handler(signum, frame)}: what {@code SIGINT} does unless a program replaces
     * it, which is to raise {@code KeyboardInterrupt} with no message.
     */
    private static PyObject defaultIntHandler(PyObject[] args, String[] kwNames) {
        Arguments.exactly("default_int_handler", args, null, 2);
        throw PyRaise.raise(ExceptionTypes.KEYBOARD_INTERRUPT);
    }

    /**
     * The signal number, checked the way the reference's clinic converter checks it: an index whose
     * magnitude has to fit a C {@code int}, and a value that is a real signal rather than signal 0.
     */
    private static int signalNumber(String name, PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() > 31) {
            throw Slots.overflowError("Python int too large to convert to C int");
        }
        int signum = number.intValue();
        if (signum < 1) {
            throw Slots.valueError("signal number out of range");
        }
        return signum;
    }

    /**
     * The handler a write is allowed to store: the two sentinels, {@code None} (the reference treats
     * it as {@code SIG_DFL}), or anything callable.
     */
    private static PyObject checkedHandler(PyObject handler) {
        if (handler == PyNone.INSTANCE) {
            return PyInt.of(SIG_DFL);
        }
        if (Slots.isCallable(handler)) {
            return handler;
        }
        if (Numbers.isIndex(handler)) {
            BigInteger number = Numbers.asIndex(handler);
            if (number.equals(BigInteger.valueOf(SIG_DFL))
                    || number.equals(BigInteger.valueOf(SIG_IGN))) {
                return handler;
            }
        }
        throw Slots.typeError(
                "signal handler must be signal.SIG_IGN, signal.SIG_DFL, or a callable object");
    }
}
