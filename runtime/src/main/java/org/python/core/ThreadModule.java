package org.python.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * {@code _thread}: the primitives the {@code threading} module is written over.
 *
 * <p>A Python thread here is a Java thread. {@code start_joinable_thread} and
 * {@code start_new_thread} make one, set its daemon flag from the Python argument, hand it to
 * {@link Thread#start()}, and give it a number from {@link Gil#newIdent()}; the callable it runs is
 * the Python callable itself, so the reference's {@code ident}, {@code is_alive} and {@code join}
 * answers are the Java thread's own. The interpreter lock is what makes that safe rather than merely
 * possible: a frame takes it on entry, every switch point gives another thread a turn, and a wait on
 * a lock, a join or a sleep gives it up altogether.
 *
 * <p>The two lock types are the reference's two shapes: {@code lock} is a one-permit semaphore with
 * no owner, so a second {@code acquire} from the same thread waits like any other thread would, and
 * {@code RLock} adds an owner and a count so that re-entry succeeds and only the owner may release.
 * Both release the interpreter lock while they wait, which is what lets the holder finish.
 */
final class ThreadModule {

    private static final String DOC =
            "This module provides primitive operations to write multi-threaded programs.\n"
                    + "The 'threading' module provides a more convenient interface.";

    private static final String GET_IDENT_DOC =
            "get_ident($module, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return a non-zero integer that uniquely identifies the current thread\n"
                    + "amongst other threads that exist simultaneously.\n"
                    + "This may be used to identify per-thread resources.\n"
                    + "Even though on some platforms threads identities may appear to be\n"
                    + "allocated consecutive numbers this behavior should not be relied upon,\n"
                    + "rather the numbers should be treated as opaque identifiers.";

    private static final String NATIVE_ID_DOC =
            "get_native_id($module, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return the native integral Thread ID of this thread.\n"
                    + "This is a non-negative integer.\n"
                    + "This represents the Thread ID (as opposed to the thread name) as\n"
                    + "reported by the operating system.";

    private static final String ALLOCATE_DOC =
            "allocate_lock() -> lock object\n"
                    + "(allocate() is an obsolete synonym)\n"
                    + "\n"
                    + "Create a new lock object. See LockType.__doc__ for information about locks.";

    private static final String ACQUIRE_DOC =
            "acquire($self, /, blocking=True, timeout=-1)\n"
                    + "--\n"
                    + "\n"
                    + "Acquire the lock, blocking or non-blocking.";

    private static final String RELEASE_DOC =
            "release($self, /)\n" + "--\n" + "\n" + "Release the lock.";

    private static final String LOCKED_DOC =
            "locked($self, /)\n" + "--\n" + "\n" + "Return whether the lock is in the locked state.";

    private static final String ENTER_DOC =
            "acquire($self, /, blocking=True, timeout=-1)\n"
                    + "--\n"
                    + "\n"
                    + "Acquire the lock, blocking or non-blocking.";

    private static final String EXIT_DOC =
            "release($self, /)\n" + "--\n" + "\n" + "Release the lock.";

    private static final String HANDLE_IDENT_DOC = "The identifier of the thread this handle names.";

    private static final String HANDLE_JOIN_DOC =
            "join($self, /, timeout=None)\n" + "--\n" + "\n" + "Wait for the thread to finish.";

    private static final String HANDLE_IS_DONE_DOC =
            "is_done($self, /)\n" + "--\n" + "\n" + "Return whether the thread has finished.";

    private static final String HANDLE_SET_DONE_DOC =
            "_set_done($self, /)\n"
                    + "--\n"
                    + "\n"
                    + "Mark the thread as finished.  Raises RuntimeError if the thread\n"
                    + "hasn't started.";

    private static final String MAKE_HANDLE_DOC =
            "_make_thread_handle($module, ident, /)\n" + "--\n" + "\n" + "Create a thread handle.";

    private static final String START_JOINABLE_DOC =
            "start_joinable_thread($module, function, /, handle=None, daemon=True)\n"
                    + "--\n"
                    + "\n"
                    + "Start a new thread and return its handle.";

    private static final String START_NEW_DOC =
            "start_new_thread($module, function, args, kwargs={})\n"
                    + "--\n"
                    + "\n"
                    + "Start a new thread and return its identifier.";

    private static final String INTERRUPT_MAIN_DOC =
            "interrupt_main($module, signum=signal.SIGINT, /)\n"
                    + "--\n"
                    + "\n"
                    + "Interrupt the main thread.";

    private static final String EXIT_THREAD_DOC =
            "exit($module, /)\n" + "--\n" + "\n" + "Exit the current thread.";

    private static final String STACK_SIZE_DOC =
            "stack_size($module, size=None, /)\n" + "--\n" + "\n" + "Return the thread stack size.";

    private static final String SHUTDOWN_DOC =
            "_shutdown($module, /)\n"
                    + "--\n"
                    + "\n"
                    + "Finish shutting down the Python interpreter.";

    private static final String DAEMON_ALLOWED_DOC =
            "daemon_threads_allowed($module, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return True if daemon threads are allowed in the current interpreter.";

    private static final String MAIN_IDENT_DOC =
            "_get_main_thread_ident($module, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return the identifier of the main thread.";

    private static final String MAIN_INTERPRETER_DOC =
            "_is_main_interpreter($module, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return True if the current interpreter is the main one.";

    private static final String COUNT_DOC =
            "_count($module, /)\n" + "--\n" + "\n" + "Return the number of running threads.";

    private static final String R_LOCK_OWNED_DOC =
            "_is_owned($self, /)\n" + "--\n" + "\n" + "Return True if the current thread owns the lock.";

    private static final String R_LOCK_COUNT_DOC =
            "_recursion_count($self, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return how many times the current thread has taken the lock.";

    private static final String R_LOCK_RELEASE_DOC =
            "_release_save($self, /)\n"
                    + "--\n"
                    + "\n"
                    + "Release the lock and answer the recursion count it had.";

    private static final String R_LOCK_ACQUIRE_DOC =
            "_acquire_restore($self, state, /)\n"
                    + "--\n"
                    + "\n"
                    + "Take the lock and restore the recursion count it is given.";

    private static final String R_LOCK_FORK_DOC =
            "_at_fork_reinit($self, /)\n"
                    + "--\n"
                    + "\n"
                    + "Reset the lock to its unlocked state after a fork.";

    /** The four rows {@code _ThreadHandle} declares, all over one handle. */
    private static final Members HANDLE_ROWS =
            Members.of(PyType.THREAD_HANDLE)
                    .addField(
                            "ident", HANDLE_IDENT_DOC, self -> PyInt.of(((ThreadHandle) self).ident))
                    .add("join", HANDLE_JOIN_DOC, (self, args, kwNames) -> ((ThreadHandle) self).join(args, kwNames))
                    .add("is_done", HANDLE_IS_DONE_DOC, (self, args, kwNames) -> ((ThreadHandle) self).isDone(args, kwNames))
                    .add("_set_done", HANDLE_SET_DONE_DOC, (self, args, kwNames) -> ((ThreadHandle) self).setDone(args, kwNames));

    /** The rows both types file; the name is the one the program called. */
    private static final Members ROWS =
            Members.of(PyType.LOCK)
                    .add("acquire", ACQUIRE_DOC, (self, args, kwNames) -> ((Lock) self).acquire(args, kwNames))
                    .add("release", RELEASE_DOC, (self, args, kwNames) -> ((Lock) self).release(args, kwNames))
                    .add("locked", LOCKED_DOC, (self, args, kwNames) -> ((Lock) self).locked(args, kwNames))
                    .add("__enter__", ENTER_DOC, (self, args, kwNames) -> ((Lock) self).enter(args, kwNames))
                    .add("__exit__", EXIT_DOC, (self, args, kwNames) -> ((Lock) self).exit(args, kwNames));

    private static final Members R_LOCK_ROWS =
            Members.of(PyType.R_LOCK)
                    .add("acquire", ACQUIRE_DOC, (self, args, kwNames) -> ((Lock) self).acquire(args, kwNames))
                    .add("release", RELEASE_DOC, (self, args, kwNames) -> ((Lock) self).release(args, kwNames))
                    .add("locked", LOCKED_DOC, (self, args, kwNames) -> ((Lock) self).locked(args, kwNames))
                    .add("__enter__", ENTER_DOC, (self, args, kwNames) -> ((Lock) self).enter(args, kwNames))
                    .add("__exit__", EXIT_DOC, (self, args, kwNames) -> ((Lock) self).exit(args, kwNames))
                    // The five rows CPython's C RLock files for `threading.Condition`, which asks a
                    // lock whether it can give up and take back its whole recursion state at once.
                    // Without them Condition(RLock()) falls back to `acquire(False)` to test
                    // ownership, and for a re-entrant lock that test succeeds for the owner and
                    // reports the lock as not owned.
                    .add("_is_owned", R_LOCK_OWNED_DOC, (self, args, kwNames) -> ((Lock) self).isOwned(args, kwNames))
                    .add("_recursion_count", R_LOCK_COUNT_DOC, (self, args, kwNames) -> ((Lock) self).recursionCount(args, kwNames))
                    .add("_release_save", R_LOCK_RELEASE_DOC, (self, args, kwNames) -> ((Lock) self).releaseSave(args, kwNames))
                    .add("_acquire_restore", R_LOCK_ACQUIRE_DOC, (self, args, kwNames) -> ((Lock) self).acquireRestore(args, kwNames))
                    .add("_at_fork_reinit", R_LOCK_FORK_DOC, (self, args, kwNames) -> ((Lock) self).atForkReinit(args, kwNames));

    private ThreadModule() {
    }

    /** Build the module, in the reference's own name order. */
    static PyModule newModule() {
        PyModule module = new PyModule("_thread");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_thread");
        module.set("LockType", PyType.LOCK);
        module.set("get_ident",
                new PyNativeFunction("get_ident", ThreadModule::getIdent).documented(GET_IDENT_DOC));
        module.set("get_native_id",
                new PyNativeFunction("get_native_id", ThreadModule::getNativeId)
                        .documented(NATIVE_ID_DOC));
        module.set("allocate_lock",
                new PyNativeFunction("allocate_lock", ThreadModule::allocate)
                        .documented(ALLOCATE_DOC));
        module.set("allocate",
                new PyNativeFunction("allocate", ThreadModule::allocate).documented(ALLOCATE_DOC));
        // `lock` is a function in the reference, and `LockType` is the type it hands out.
        module.set("lock",
                new PyNativeFunction("lock", ThreadModule::allocate).documented(ALLOCATE_DOC));
        module.set("RLock", PyType.R_LOCK);
        module.set("_local", PyType.LOCAL);
        module.set("_ThreadHandle", PyType.THREAD_HANDLE);
        module.set("_make_thread_handle",
                new PyNativeFunction("_make_thread_handle", ThreadModule::makeThreadHandle)
                        .documented(MAKE_HANDLE_DOC));
        module.set("start_joinable_thread",
                new PyNativeFunction("start_joinable_thread", ThreadModule::startJoinableThread)
                        .documented(START_JOINABLE_DOC));
        module.set("start_new_thread",
                new PyNativeFunction("start_new_thread", ThreadModule::startNewThread)
                        .documented(START_NEW_DOC));
        module.set("start_new",
                new PyNativeFunction("start_new", ThreadModule::startNewThread)
                        .documented(START_NEW_DOC));
        module.set("interrupt_main",
                new PyNativeFunction("interrupt_main", ThreadModule::interruptMain)
                        .documented(INTERRUPT_MAIN_DOC));
        module.set("exit",
                new PyNativeFunction("exit", ThreadModule::exitThread).documented(EXIT_THREAD_DOC));
        module.set("stack_size",
                new PyNativeFunction("stack_size", ThreadModule::stackSize)
                        .documented(STACK_SIZE_DOC));
        module.set("_shutdown",
                new PyNativeFunction("_shutdown", ThreadModule::shutdown).documented(SHUTDOWN_DOC));
        module.set("daemon_threads_allowed",
                new PyNativeFunction("daemon_threads_allowed", ThreadModule::daemonThreadsAllowed)
                        .documented(DAEMON_ALLOWED_DOC));
        module.set("_get_main_thread_ident",
                new PyNativeFunction("_get_main_thread_ident", ThreadModule::getMainThreadIdent)
                        .documented(MAIN_IDENT_DOC));
        module.set("_is_main_interpreter",
                new PyNativeFunction("_is_main_interpreter", ThreadModule::isMainInterpreter)
                        .documented(MAIN_INTERPRETER_DOC));
        module.set("_count",
                new PyNativeFunction("_count", ThreadModule::count).documented(COUNT_DOC));
        // `_thread.error` is `RuntimeError` itself, not a subclass of it.
        module.set("error", ExceptionTypes.RUNTIME_ERROR);
        module.set("TIMEOUT_MAX", PyFloat.of(9223372036.0));
        return module;
    }

    /**
     * {@code _make_thread_handle(ident)}: a handle for a thread this runtime did not start —
     * {@code threading}'s main thread and its dummy threads.
     */
    private static PyObject makeThreadHandle(PyObject[] args, String[] kwNames) {
        Arguments.atLeast("_make_thread_handle", args, kwNames, 1);
        Arguments.atMost("_make_thread_handle", args, kwNames, 1);
        return ThreadHandle.started(args[0]);
    }

    /**
     * {@code start_joinable_thread(function, /, handle=None, daemon=True)}: start a Java thread that
     * runs {@code function} and answer the handle that names it.
     *
     * <p>The handle is the caller's when one is given — {@code threading.Thread} keeps the one it
     * made so that {@code is_alive} and {@code join} can read it — and a fresh one otherwise. The
     * thread's number is chosen here, before it starts, so that the answer is the same number the
     * new thread will report from {@code get_ident()} and the reference's {@code start_new_thread}
     * can hand it back.
     */
    private static PyObject startJoinableThread(PyObject[] args, String[] kwNames) {
        Arguments.between("start_joinable_thread", args, kwNames, 1, 3);
        PyObject[] values = Arguments.fill(
                "start_joinable_thread",
                args,
                kwNames,
                new String[] {"", "handle", "daemon"},
                new PyObject[] {null, PyNone.INSTANCE, PyBool.TRUE});
        ThreadHandle handle;
        if (values[1] instanceof PyNone) {
            handle = null;
        } else if (values[1] instanceof ThreadHandle given) {
            handle = given;
        } else {
            throw Slots.typeError("handle must be a thread handle");
        }
        return launch(values[0], new PyObject[0], null, handle, Slots.truth(values[2]));
    }

    /**
     * {@code start_new_thread(function, args, kwargs={})}: the old spelling, with the thread's number
     * as its answer.
     *
     * <p>{@code args} must be a tuple and {@code kwargs} a dictionary keyed by strings, as the
     * reference insists: the two are passed through to the callable unchanged.
     */
    private static PyObject startNewThread(PyObject[] args, String[] kwNames) {
        Arguments.between("start_new_thread", args, kwNames, 2, 3);
        if (!(args[1] instanceof PyTuple given)) {
            throw Slots.typeError("2nd arg must be a tuple");
        }
        PyTuple names = null;
        PyObject[] values = given.getArray();
        if (args.length > 2 && !(args[2] instanceof PyNone)) {
            if (!(args[2] instanceof PyDict keywords)) {
                throw Slots.typeError("3rd arg must be a dictionary of keyword arguments");
            }
            Map<PyObject, PyObject> items = keywords.getItems();
            PyObject[] keywordsNamed = new PyObject[items.size()];
            PyObject[] extra = new PyObject[items.size()];
            int at = 0;
            for (Map.Entry<PyObject, PyObject> entry : items.entrySet()) {
                if (!(entry.getKey() instanceof PyStr key)) {
                    throw Slots.typeError("keywords must be strings");
                }
                keywordsNamed[at] = key;
                extra[at] = entry.getValue();
                at++;
            }
            PyObject[] joined = new PyObject[values.length + extra.length];
            System.arraycopy(values, 0, joined, 0, values.length);
            System.arraycopy(extra, 0, joined, values.length, extra.length);
            values = joined;
            names = PyTuple.of(keywordsNamed);
        }
        // A thread made here is not one the interpreter waits for at exit, which is the reference
        // rule: only `threading.Thread` objects that are not daemons hold shutdown up, and a raw
        // `_thread` thread is one `threading` never heard of. `test_leak_without_join` leaks one on
        // purpose and expects the interpreter to exit anyway.
        return PyInt.of(launch(args[0], values, names, null, true).ident());
    }

    /**
     * {@code interrupt_main(signum=SIGINT, /)}: delivers the signal to its registered handler.
     *
     * <p>From the main thread the handler runs at once, which is what makes an interrupted
     * {@code asyncio.run()} end instead of waiting forever on a selector; with no handler installed
     * the default action is taken, so {@code SIGINT} raises {@code KeyboardInterrupt} from this call.
     * From any other thread the number is recorded and the main thread takes it at its next switch
     * point, because this thread cannot raise in the main thread from here.
     */
    private static volatile int pendingInterrupt;

    /** Why {@code interrupt_main} refuses a number: the platform's own range, one through NSIG-1. */
    private static int signalCount() {
        PyObject nsig = Slots.getAttr(PyRuntime.importModule("_signal"), "NSIG");
        return nsig instanceof PyInt value ? Numbers.asIndex(value).intValue() : 65;
    }

    /** {@code interrupt_main(signum=SIGINT)}: ask the main thread to take a signal. */
    private static PyObject interruptMain(PyObject[] args, String[] kwNames) {
        Arguments.between("interrupt_main", args, kwNames, 0, 1);
        int signum =
                args.length == 0 ? 2 : Numbers.asIndex(args[0]).intValue();
        if (signum <= 0 || signum >= signalCount()) {
            throw Slots.valueError("signal number out of range");
        }
        if (Gil.ident() == Gil.mainIdent()) {
            deliver(signum);
        } else {
            pendingInterrupt = signum;
        }
        return PyNone.INSTANCE;
    }

    /** Whether the main thread has a signal waiting for it. */
    static boolean holdsInterrupt() {
        return pendingInterrupt != 0 && Gil.ident() == Gil.mainIdent();
    }

    /** Take the waiting signal, if this is the main thread; called at a switch point or a join. */
    static void deliverPending() {
        if (!holdsInterrupt()) {
            return;
        }
        int signum = pendingInterrupt;
        pendingInterrupt = 0;
        deliver(signum);
    }

    /** Run the registered handler, or the default action, for one signal number. */
    private static void deliver(int signum) {
        PyObject handler =
                Slots.call(
                        Slots.getAttr(PyRuntime.importModule("_signal"), "getsignal"),
                        new PyObject[] {PyInt.of(signum)},
                        null);
        if (handler instanceof PyInt action) {
            // SIG_IGN (1) drops it, and so does SIG_DFL (0): this runtime installs no operating
            // system handler, so a signal with no Python handler has no action to take. Measured
            // against 3.14, where `interrupt_main` after `signal(SIGINT, SIG_DFL)` returns quietly.
            return;
        }
        Slots.call(handler, new PyObject[] {PyInt.of(signum), PyNone.INSTANCE}, null);
    }

    /** {@code exit()}: raise {@code SystemExit}, which is how a thread ends itself early. */
    private static PyObject exitThread(PyObject[] args, String[] kwNames) {
        Arguments.none("exit", args, kwNames);
        throw PyRaise.of((PyBaseException) Slots.call(ExceptionTypes.SYSTEM_EXIT, new PyObject[0], null));
    }

    /**
     * {@code stack_size(size=None)}: the stack new threads are given, zero meaning the platform's
     * own choice.
     *
     * <p>The value is the real one: {@link #launch} hands it to the {@link Thread} constructor, which
     * asks the JVM for that stack. The minimum is what the reference accepts on this platform;
     * anything smaller is refused before a thread is made.
     */
    private static PyObject stackSize(PyObject[] args, String[] kwNames) {
        Arguments.between("stack_size", args, kwNames, 0, 1);
        long previous = stackSizeBytes;
        if (args.length == 0) {
            return PyInt.of(previous);
        }
        long requested = Numbers.asIndex(args[0]).longValue();
        if (requested < 0) {
            throw Slots.valueError("size must be positive");
        }
        if (requested == 0) {
            // Zero means the platform's own choice, and the reference answers zero rather than the
            // value it held a moment ago — measured against 3.14, which agrees with itself here.
            stackSizeBytes = 0;
            return PyInt.of(0);
        }
        if (requested < MINIMUM_STACK) {
            throw Slots.valueError("size must be at least %d bytes", MINIMUM_STACK);
        }
        if (requested % PAGE_SIZE != 0) {
            throw Slots.valueError("size not valid: %d bytes", requested);
        }
        stackSizeBytes = requested;
        return PyInt.of(previous);
    }

    /** The smallest stack the reference accepts for a new thread on macOS, {@code PTHREAD_STACK_MIN}. */
    private static final long MINIMUM_STACK = 53248;

    /** The multiple the reference demands of a stack size; 4096 is what it checks on this host. */
    private static final long PAGE_SIZE = 4096;

    /** The stack size {@code stack_size} set, zero for the platform default. */
    private static volatile long stackSizeBytes;

    /**
     * {@code _shutdown()}: wait for every non-daemon thread that is still running.
     *
     * <p>CPython calls this from {@code threading._shutdown} on the way out of the interpreter; the
     * launcher that hosts this runtime calls {@link #shutdownAtExit()} at the same point. Daemon
     * threads are left alone — their Java threads are daemon threads, so they end with the process.
     */
    private static PyObject shutdown(PyObject[] args, String[] kwNames) {
        Arguments.none("_shutdown", args, kwNames);
        shutdownAtExit();
        return PyNone.INSTANCE;
    }

    /**
     * The launcher's share of {@code _thread._shutdown}.
     *
     * <p>The reference's {@code threading._shutdown()} is what does the work, and it must run first:
     * its {@code _threading_atexits} are the callbacks that stop the thread pool of
     * {@code concurrent.futures} — {@code _python_exit} puts a sentinel on each worker's queue and
     * joins it — and only then can the non-daemon threads be joined without waiting on a worker that
     * is waiting for a sentinel nobody has sent. So this calls it when {@code threading} is loaded,
     * and only falls back to joining the handles itself when it is not. The flag stops the
     * {@code _thread._shutdown} that {@code threading._shutdown()} calls back into from re-entering.
     */
    static void shutdownAtExit() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        try {
            PyObject modules = Slots.getAttr(PyRuntime.importModule("sys"), "modules");
            PyObject threading =
                    modules instanceof PyDict map ? map.get(PyStr.of("threading")) : null;
            if (threading != null) {
                Slots.call(Slots.getAttr(threading, "_shutdown"), new PyObject[0], null);
            }
        } catch (PyRaise ignored) {
            // A failing shutdown hook must not keep the process from exiting.
        } finally {
            for (ThreadHandle handle : List.copyOf(LIVE)) {
                if (!handle.daemon) {
                    handle.await(-1);
                }
            }
        }
    }

    private static volatile boolean shuttingDown;

    /** {@code daemon_threads_allowed()}: the reference answers what the interpreter allows. */
    private static PyObject daemonThreadsAllowed(PyObject[] args, String[] kwNames) {
        Arguments.none("daemon_threads_allowed", args, kwNames);
        return PyBool.TRUE;
    }

    private static PyObject getMainThreadIdent(PyObject[] args, String[] kwNames) {
        Arguments.none("_get_main_thread_ident", args, kwNames);
        return PyInt.of(Gil.mainIdent());
    }

    private static PyObject isMainInterpreter(PyObject[] args, String[] kwNames) {
        Arguments.none("_is_main_interpreter", args, kwNames);
        return PyBool.TRUE;
    }

    /** {@code _count()}: the Python threads alive other than the one this runtime started on. */
    private static PyObject count(PyObject[] args, String[] kwNames) {
        Arguments.none("_count", args, kwNames);
        return PyInt.of(Gil.live());
    }

    /**
     * {@code _thread._local}, and every class Python derives from it: one attribute dictionary per
     * thread, made the first time that thread touches the value.
     *
     * <p>CPython's {@code localobject} keeps its dictionaries in a map keyed by thread, and
     * {@code local_getattro} asks for the current thread's one before every read. This runtime refuses
     * to start a Python thread, so the map holds one entry in practice; a {@link ThreadLocal} is still
     * the faithful shape, and it means a host that gives this interpreter a second Java thread gets the
     * reference's answer rather than a shared dictionary.
     *
     * <p>The constructing thread is the one exception to the replay rule. {@code local_new} makes that
     * thread's dictionary without calling anything, because {@code type_call} runs {@code __init__} once
     * right after {@code tp_new} like it does for every other class; a later thread that finds no
     * dictionary makes one and then runs {@code __init__} with the arguments the constructor was given,
     * which is {@code _ldict}. A failing replay removes the half-made dictionary and raises the original
     * error.
     *
     * <p>{@code __dict__} is answered here rather than by a descriptor, because the reference special-cases
     * the name before it looks anywhere else and refuses both a write and a delete with its read-only
     * sentence. Everything else is the ordinary class machinery: a {@code __slots__} field stays out of
     * this dictionary, and a plain subclass writes its attributes into it.
     */
    static final class Local extends PyInstance {

        /** This value's dictionary in each thread that has touched it. */
        private final ThreadLocal<PyDict> dicts = new ThreadLocal<>();

        /** The arguments {@code __init__} is replayed with in a thread that has not run it yet. */
        private final PyObject[] initArgs;

        private final PyTuple initNames;

        Local(PyType kind, PyObject[] args, PyTuple kwNames) {
            super(kind, false);
            this.initArgs = args.clone();
            this.initNames = kwNames;
            dicts.set(PyDict.empty());
        }

        @Override
        PyDict instanceDict() {
            return threadDict();
        }

        /**
         * The dictionary {@code object.__setattr__} reaches, which is the value's own and not the one
         * the running thread reads: {@code _local}'s write slot passes the thread dictionary to {@code
         * _PyObject_GenericSetAttrWithDict} explicitly, so the generic route is left with the managed
         * dictionary a heap type over this base lays out — and a name written there is invisible to
         * {@code getattr}, exactly as it is in CPython.
         */
        @Override
        PyDict attributeStore() {
            return ensureInstanceDict();
        }

        @Override
        void writeAttribute(String name, PyObject value) {
            GenericAttributes.write(this, name, value, threadDict());
        }

        @Override
        void deleteAttribute(String name) {
            GenericAttributes.delete(this, name, threadDict());
        }

        @Override
        public PyObject getAttr(String name) {
            if (name.equals("__dict__")) {
                return threadDict();
            }
            return super.getAttr(name);
        }

        @Override
        public PyObject setAttr(String name, PyObject value) {
            if (name.equals("__dict__")) {
                throw readOnlyDict();
            }
            return super.setAttr(name, value);
        }

        @Override
        public PyObject delAttr(String name) {
            if (name.equals("__dict__")) {
                throw readOnlyDict();
            }
            return super.delAttr(name);
        }

        /** {@code local_setattro}'s one refusal: the name is managed, the dictionary is not replaced. */
        private PyRaise readOnlyDict() {
            return Slots.attributeError(
                    "'%s' object attribute '__dict__' is read-only", getType().filedName());
        }

        /** {@code _ldict}: the current thread's dictionary, made and initialized on the first touch. */
        private PyDict threadDict() {
            PyDict held = dicts.get();
            if (held != null) {
                return held;
            }
            held = PyDict.empty();
            dicts.set(held);
            try {
                replayInitializer();
            } catch (PyRaise failure) {
                dicts.remove();
                throw failure;
            }
            return held;
        }

        /**
         * The replay {@code _ldict} makes: only a {@code __init__} a class Python wrote, never
         * {@code object}'s, which has nothing to say about a thread.
         */
        private void replayInitializer() {
            PyObject declared = Specials.lookUp(getType(), "__init__");
            if (declared == null) {
                return;
            }
            PyObject[] withReceiver = new PyObject[initArgs.length + 1];
            withReceiver[0] = this;
            System.arraycopy(initArgs, 0, withReceiver, 1, initArgs.length);
            if (declared instanceof PyFunction init) {
                init.call(withReceiver, initNames);
            } else {
                Slots.call(declared, withReceiver, initNames);
            }
        }
    }

    /** Every handle whose Java thread has been started and has not finished, for {@code _shutdown}. */
    private static final List<ThreadHandle> LIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Start a Java thread that runs {@code function}, and answer the handle that names it.
     *
     * <p>The number is chosen before the thread starts and adopted by it first thing, so
     * {@code get_ident()} inside the callable answers what the starter already holds. The daemon flag
     * goes to the Java thread as it is: a daemon Python thread must not keep the JVM alive.
     */
    private static ThreadHandle launch(
            PyObject function,
            PyObject[] arguments,
            PyTuple names,
            ThreadHandle handle,
            boolean daemon) {
        ThreadHandle target = handle == null ? new ThreadHandle() : handle;
        if (target.started) {
            throw Slots.runtimeError("thread already started");
        }
        target.started = true;
        target.daemon = daemon;
        target.ident = Gil.newIdent();
        long size = stackSizeBytes;
        Thread javaThread =
                size > 0
                        ? new Thread(
                                null,
                                () -> target.run(function, arguments, names),
                                "python-thread-" + target.ident,
                                size)
                        : new Thread(
                                () -> target.run(function, arguments, names),
                                "python-thread-" + target.ident);
        javaThread.setDaemon(daemon);
        target.thread = javaThread;
        LIVE.add(target);
        Gil.threadStarted();
        javaThread.start();
        // The frame that started this thread was entered before there was anything to share, so it
        // takes the interpreter lock now; its own exit gives the hold back.
        Gil.ensureHeld();
        return target;
    }

    /**
     * {@code _ThreadHandle}: the joinable part of a thread, which {@code threading} keeps beside it.
     *
     * <p>A fresh handle belongs to a thread that has not started, so {@code join} and
     * {@code _set_done} refuse and {@code is_done} answers {@code False}. A handle made by
     * {@code _make_thread_handle} names a thread this runtime did not start — the main thread and
     * dummy threads — and has no Java thread to join.
     */
    static final class ThreadHandle extends PyObject {

        private volatile long ident;
        private volatile boolean started;
        private volatile boolean done;
        private volatile boolean daemon = true;
        private volatile Thread thread;

        ThreadHandle() {
            super(PyType.THREAD_HANDLE);
        }

        static ThreadHandle started(PyObject ident) {
            ThreadHandle handle = new ThreadHandle();
            handle.ident = Numbers.asIndex(ident).longValue();
            handle.started = true;
            return handle;
        }

        long ident() {
            return ident;
        }

        /** The body of the Java thread, run under the number chosen before it started. */
        private void run(PyObject function, PyObject[] arguments, PyTuple names) {
            Gil.adoptIdent(ident);
            try {
                Slots.call(function, arguments, names);
            } catch (PyRaise raised) {
                report(raised.value());
            } catch (Throwable failure) {
                reportUncaught(failure);
            } finally {
                done = true;
                LIVE.removeIf(live -> live == this);
                Gil.threadFinished();
            }
        }

        /**
         * Wait for the Java thread, giving up the interpreter lock while waiting.
         *
         * @param milliseconds how long to wait; a negative number waits until the thread ends
         */
        void await(long milliseconds) {
            Thread waiting = thread;
            if (waiting == null || waiting == Thread.currentThread()) {
                return;
            }
            int holds = Gil.suspend();
            try {
                if (milliseconds < 0) {
                    // The slice lets a signal aimed at the main thread in from a worker; a program
                    // that never asks for one pays one comparison per slice.
                    while (waiting.isAlive() && !holdsInterrupt()) {
                        waiting.join(10);
                    }
                } else {
                    long deadline = System.nanoTime() + milliseconds * 1_000_000L;
                    while (waiting.isAlive() && !holdsInterrupt()) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            break;
                        }
                        waiting.join(Math.max(1, Math.min(10, left / 1_000_000L)));
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                Gil.resume(holds);
            }
            deliverPending();
        }

        private PyObject join(PyObject[] args, String[] kwNames) {
            PyObject[] values = Arguments.fill(
                    "join",
                    args,
                    kwNames,
                    new String[] {"timeout"},
                    new PyObject[] {PyNone.INSTANCE});
            if (!started) {
                // A fresh `_ThreadHandle()` says this; the longer "cannot join thread before it is
                // started" sentence comes from `threading.Thread.join`, which checks its own event.
                throw Slots.runtimeError("thread not started");
            }
            if (thread == Thread.currentThread()) {
                // Capital C: the reference's handle and its `threading.Thread.join` disagree about
                // this sentence, and both spellings appear in the tests of the module.
                throw Slots.runtimeError("Cannot join current thread");
            }
            if (!done && values[0] instanceof PyNone) {
                await(-1);
            } else if (!done && !(values[0] instanceof PyNone)) {
                Double seconds = Numbers.floatOf(values[0]);
                if (seconds == null) {
                    throw Slots.typeError("timeout must be a number");
                }
                if (seconds > 0) {
                    await(Math.max(1, Math.round(seconds * 1000.0)));
                }
            }
            // A worker may have asked for the main thread's attention and finished before the join
            // got here; the signal is taken now rather than at an arbitrary later switch point.
            deliverPending();
            return PyNone.INSTANCE;
        }

        private PyObject isDone(PyObject[] args, String[] kwNames) {
            Arguments.none("is_done", args, kwNames);
            return PyBool.of(done);
        }

        private PyObject setDone(PyObject[] args, String[] kwNames) {
            Arguments.none("_set_done", args, kwNames);
            if (!started) {
                throw Slots.runtimeError("thread not started");
            }
            done = true;
            return PyNone.INSTANCE;
        }
    }

    /** Print an exception that escaped a thread's callable, the way uncaught Python errors read. */
    private static void report(PyBaseException error) {
        if (Classes.instanceOf(error, ExceptionTypes.SYSTEM_EXIT)) {
            return;
        }
        Gil.enter();
        try {
            PyRuntime.stderr().writeText(
                    "Exception in thread " + Thread.currentThread().getName() + ":\n");
            TracebackReport.print(error);
        } finally {
            Gil.exit();
        }
    }

    /** A Java failure that escaped the callable, which is a bug rather than a Python error. */
    private static void reportUncaught(Throwable failure) {
        Gil.enter();
        try {
            PyRuntime.stderr().writeText(
                    "Exception in thread " + Thread.currentThread().getName() + ": "
                            + failure.getClass().getName() + ": " + failure.getMessage() + "\n");
        } finally {
            Gil.exit();
        }
    }


    /** {@code get_ident()}: the current thread's number; the one this runtime started on is 1. */
    private static PyObject getIdent(PyObject[] args, String[] kwNames) {
        Arguments.none("get_ident", args, kwNames);
        return PyInt.of(Gil.ident());
    }

    /** {@code get_native_id()}: the operating system's number for the current thread. */
    private static PyObject getNativeId(PyObject[] args, String[] kwNames) {
        Arguments.none("get_native_id", args, kwNames);
        return PyInt.of(Thread.currentThread().threadId());
    }

    /** {@code allocate_lock()} and {@code lock()}: a new primitive lock. */
    private static PyObject allocate(PyObject[] args, String[] kwNames) {
        Arguments.none("lock", args, kwNames);
        return new Lock(PyType.LOCK);
    }

    /** The type's own constructor, {@code _thread.LockType()}. */
    static PyObject lockType(PyObject[] args, String[] kwNames) {
        Arguments.none("lock", args, kwNames);
        return new Lock(PyType.LOCK);
    }

    /** {@code _thread.RLock()}: the re-entrant one, which is the only kind this runtime needs. */
    static PyObject reentrant(PyObject[] args, String[] kwNames) {
        Arguments.none("RLock", args, kwNames);
        return new Lock(PyType.R_LOCK);
    }

    /**
     * A primitive lock, or a re-entrant one.
     *
     * <p>A plain {@code lock} is one permit with no owner: a second {@code acquire} from the thread
     * that holds it waits like any other, which is what makes {@code threading.Lock} behave. An
     * {@code RLock} remembers its owner and how many times it was taken, so the owner re-enters
     * without waiting and only the owner may release.
     *
     * <p>A wait gives up the interpreter lock for its whole duration — otherwise the thread that
     * would release this lock could not run, and every blocking test would hang.
     */
    static final class Lock extends PyObject {

        /** One permit, taken to hold the lock and given back to release it. */
        private final Semaphore gate = new Semaphore(1, true);

        /** The owner of an {@code RLock}, and how many times it has taken it; unused by a plain lock. */
        private volatile long owner;
        private int count;

        Lock(PyType type) {
            super(type);
        }

        private boolean reentrant() {
            return getType() == PyType.R_LOCK;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            String held = locked(new PyObject[0], null) == PyBool.TRUE ? "locked" : "unlocked";
            String at = " at 0x" + Long.toHexString(System.identityHashCode(this));
            if (reentrant()) {
                return "<" + held + " _thread.RLock object owner=" + owner + " count=" + count + at
                        + ">";
            }
            return "<" + held + " _thread.lock object" + at + ">";
        }

        private PyObject acquire(PyObject[] args, String[] kwNames) {
            PyObject[] values = Arguments.fill(
                    "acquire",
                    args,
                    kwNames,
                    new String[] {"blocking", "timeout"},
                    new PyObject[] {PyBool.TRUE, PyInt.of(-1)});
            if (reentrant() && owner == Gil.ident()) {
                count++;
                return PyBool.TRUE;
            }
            boolean blocking = Slots.truth(values[0]);
            double timeout = -1;
            if (values[1] instanceof PyNone) {
                timeout = -1;
            } else {
                Double asked = Numbers.floatOf(values[1]);
                if (asked == null) {
                    throw Slots.typeError("timeout must be a float or None");
                }
                timeout = asked;
            }
            // The reference reads the timeout with PyTime_FromSecondsObject before anything else, so
            // NaN is refused there, then refuses a timeout on a non-blocking call, then a negative one,
            // then one that will not fit a C PyTime_t.
            if (Double.isNaN(timeout)) {
                throw Slots.valueError("Invalid value NaN (not a number)");
            }
            if (!blocking && timeout >= 0) {
                throw Slots.valueError("can't specify a timeout for a non-blocking call");
            }
            if (timeout < -1) {
                throw Slots.valueError("timeout value must be a non-negative number");
            }
            if (timeout > 9223372036.0) {
                throw PyRaise.raise(
                        ExceptionTypes.OVERFLOW_ERROR, "timestamp out of range for C PyTime_t");
            }
            boolean taken;
            if (!blocking || timeout == 0) {
                taken = gate.tryAcquire();
            } else if (timeout < 0) {
                int holds = Gil.suspend();
                try {
                    gate.acquireUninterruptibly();
                } finally {
                    Gil.resume(holds);
                }
                taken = true;
            } else {
                int holds = Gil.suspend();
                try {
                    taken = gate.tryAcquire(
                            Math.max(1, Math.round(timeout * 1_000_000_000.0)), TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    taken = false;
                } finally {
                    Gil.resume(holds);
                }
            }
            if (taken && reentrant()) {
                owner = Gil.ident();
                count = 1;
            }
            return PyBool.of(taken);
        }

        private PyObject release(PyObject[] args, String[] kwNames) {
            Arguments.none("release", args, kwNames);
            if (reentrant()) {
                if (owner != Gil.ident()) {
                    throw Slots.runtimeError("cannot release un-acquired lock");
                }
                if (--count == 0) {
                    owner = 0;
                    gate.release();
                }
                return PyNone.INSTANCE;
            }
            if (gate.availablePermits() > 0) {
                throw Slots.runtimeError("release unlocked lock");
            }
            gate.release();
            return PyNone.INSTANCE;
        }

        /** {@code RLock._is_owned()}: whether the calling thread is the owner. */
        private PyObject isOwned(PyObject[] args, String[] kwNames) {
            Arguments.none("_is_owned", args, kwNames);
            return PyBool.of(owner == Gil.ident());
        }

        /** {@code RLock._recursion_count()}: the owner's count, and zero for anyone else. */
        private PyObject recursionCount(PyObject[] args, String[] kwNames) {
            Arguments.none("_recursion_count", args, kwNames);
            return PyInt.of(owner == Gil.ident() ? count : 0);
        }

        /**
         * {@code RLock._release_save()}: unlock completely and answer the state that was given up.
         *
         * <p>{@code Condition.wait} hands back the recursion depth through this rather than
         * {@code release()}, because a recursive holder has to be left with nothing held while it waits.
         * The answer is the pair the reference returns, {@code (count, owner ident)}, which is what
         * {@link #acquireRestore} takes back.
         */
        private PyObject releaseSave(PyObject[] args, String[] kwNames) {
            Arguments.none("_release_save", args, kwNames);
            if (owner != Gil.ident() || count == 0) {
                throw Slots.runtimeError("cannot release un-acquired lock");
            }
            int saved = count;
            long holder = owner;
            count = 0;
            owner = 0;
            gate.release();
            return PyTuple.of(PyInt.of(saved), PyInt.of(holder));
        }

        /** {@code RLock._acquire_restore(state)}: take the lock afresh at the saved depth. */
        private PyObject acquireRestore(PyObject[] args, String[] kwNames) {
            Arguments.exactlyOne("_acquire_restore", args, kwNames);
            PyObject state = args[0];
            long saved =
                    state instanceof PyTuple pair && pair.size() > 0
                            ? Numbers.asIndex(pair.at(0)).longValue()
                            : Numbers.asIndex(state).longValue();
            int holds = Gil.suspend();
            try {
                gate.acquireUninterruptibly();
            } finally {
                Gil.resume(holds);
            }
            owner = Gil.ident();
            count = (int) Math.max(1, saved);
            return PyNone.INSTANCE;
        }

        /** {@code RLock._at_fork_reinit()}: back to unlocked, whoever held it in the parent. */
        private PyObject atForkReinit(PyObject[] args, String[] kwNames) {
            Arguments.none("_at_fork_reinit", args, kwNames);
            owner = 0;
            count = 0;
            gate.drainPermits();
            gate.release();
            return PyNone.INSTANCE;
        }

        private PyObject locked(PyObject[] args, String[] kwNames) {
            Arguments.none("locked", args, kwNames);
            boolean held = reentrant() ? count > 0 : gate.availablePermits() == 0;
            return PyBool.of(held);
        }

        private PyObject enter(PyObject[] args, String[] kwNames) {
            acquire(args, kwNames);
            return this;
        }

        private PyObject exit(PyObject[] args, String[] kwNames) {
            release(new PyObject[0], null);
            return PyNone.INSTANCE;
        }
    }
}
