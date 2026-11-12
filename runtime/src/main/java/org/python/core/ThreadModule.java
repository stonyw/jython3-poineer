package org.python.core;

/**
 * {@code _thread}: the primitives the {@code threading} module is written over.
 *
 * <p>This runtime runs one thread, so the two lock types differ only in what they print. A lock is a
 * counter of how many times it has been acquired: {@code acquire} always succeeds and {@code release}
 * takes one off, which makes {@code RLock} exactly right and {@code lock} re-entrant where the
 * reference would leave the second {@code acquire} waiting. Starting a thread is the one row that
 * cannot be faked, so {@code start_joinable_thread} and {@code start_new_thread} answer with the
 * reference's own {@code can't start new thread}; everything {@code threading} asks about a thread it
 * has not started — its handle, its identity, whether daemon threads are allowed — is answered here so
 * that the module imports and every single-threaded test can run. That re-entrancy and that refusal
 * are the named deviations this module carries.
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
                    .add("__exit__", EXIT_DOC, (self, args, kwNames) -> ((Lock) self).exit(args, kwNames));

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
     * {@code start_joinable_thread(function, /, handle=None, daemon=True)}: refused.
     *
     * <p>This runtime has one thread and no way to make another, so the reference's own message is
     * the honest answer. {@code threading.Thread.start()} propagates it to its caller, which is what
     * keeps a test that needs a second thread failing at the call rather than waiting forever.
     */
    private static PyObject startJoinableThread(PyObject[] args, String[] kwNames) {
        Arguments.between("start_joinable_thread", args, kwNames, 1, 3);
        throw Slots.runtimeError("can't start new thread");
    }

    /** {@code start_new_thread(function, args, kwargs={})}: the same refusal, in the old spelling. */
    private static PyObject startNewThread(PyObject[] args, String[] kwNames) {
        Arguments.between("start_new_thread", args, kwNames, 2, 3);
        throw Slots.runtimeError("can't start new thread");
    }

    /**
     * {@code interrupt_main(signum=SIGINT, /)}: nothing to interrupt, so the signal is dropped.
     *
     * <p>The reference schedules a {@code KeyboardInterrupt} in the main thread and returns before it
     * arrives; with only that thread, returning is all there is to do until the interpreter checks
     * for interrupts, which this runtime does not do.
     */
    private static PyObject interruptMain(PyObject[] args, String[] kwNames) {
        Arguments.between("interrupt_main", args, kwNames, 0, 1);
        return PyNone.INSTANCE;
    }

    /** {@code exit()}: raise {@code SystemExit}, which is how a thread ends itself early. */
    private static PyObject exitThread(PyObject[] args, String[] kwNames) {
        Arguments.none("exit", args, kwNames);
        throw PyRaise.of((PyBaseException) Slots.call(ExceptionTypes.SYSTEM_EXIT, new PyObject[0], null));
    }

    /** {@code stack_size(size=None)}: no stack of its own to size, so the answer is always zero. */
    private static PyObject stackSize(PyObject[] args, String[] kwNames) {
        Arguments.between("stack_size", args, kwNames, 0, 1);
        return PyInt.of(0);
    }

    /** {@code _shutdown()}: there are no other threads to wait for. */
    private static PyObject shutdown(PyObject[] args, String[] kwNames) {
        Arguments.none("_shutdown", args, kwNames);
        return PyNone.INSTANCE;
    }

    /** {@code daemon_threads_allowed()}: the reference answers what the interpreter allows. */
    private static PyObject daemonThreadsAllowed(PyObject[] args, String[] kwNames) {
        Arguments.none("daemon_threads_allowed", args, kwNames);
        return PyBool.TRUE;
    }

    private static PyObject getMainThreadIdent(PyObject[] args, String[] kwNames) {
        Arguments.none("_get_main_thread_ident", args, kwNames);
        return PyInt.of(1);
    }

    private static PyObject isMainInterpreter(PyObject[] args, String[] kwNames) {
        Arguments.none("_is_main_interpreter", args, kwNames);
        return PyBool.TRUE;
    }

    /** {@code _count()}: the number of threads running other than the main one, which is none. */
    private static PyObject count(PyObject[] args, String[] kwNames) {
        Arguments.none("_count", args, kwNames);
        return PyInt.of(0);
    }

    /**
     * {@code _thread._local}: the attribute dictionary behind {@code threading.local}.
     *
     * <p>CPython keeps one dictionary per thread and hands each thread the one it made. This runtime
     * has one thread, so one dictionary is the whole of it; the shape a program sees — plain
     * attributes, {@code __dict__} naming that same dictionary, {@code del} working on it — is the
     * same either way.
     */
    static final class Local extends PyObject {

        private final PyDict contents = PyDict.empty();

        Local() {
            super(PyType.LOCAL);
        }

        @Override
        public PyObject getAttr(String name) {
            if (name.equals("__dict__")) {
                return contents;
            }
            PyObject value = contents.get(PyStr.interned(name));
            return value == null ? super.getAttr(name) : value;
        }

        @Override
        public PyObject setAttr(String name, PyObject value) {
            if (name.equals("__dict__")) {
                throw Slots.attributeError("readonly attribute");
            }
            contents.put(PyStr.interned(name), value);
            return PyNone.INSTANCE;
        }

        @Override
        public PyObject delAttr(String name) {
            if (name.equals("__dict__")) {
                throw Slots.attributeError("readonly attribute");
            }
            if (contents.remove(PyStr.interned(name)) == null) {
                throw Slots.attributeError(
                        "'%s' object has no attribute '%s'", getType().filedName(), name);
            }
            return PyNone.INSTANCE;
        }
    }

    /**
     * {@code _ThreadHandle}: the joinable part of a thread, which {@code threading} keeps beside it.
     *
     * <p>A fresh handle belongs to a thread that has not started, so {@code join} and
     * {@code _set_done} refuse and {@code is_done} answers {@code False}; the handles this runtime
     * hands out are only ever the ones {@code _make_thread_handle} makes for threads it did not
     * start, and those are never joined.
     */
    static final class ThreadHandle extends PyObject {

        private long ident;
        private boolean started;
        private boolean done;

        ThreadHandle() {
            super(PyType.THREAD_HANDLE);
        }

        static ThreadHandle started(PyObject ident) {
            ThreadHandle handle = new ThreadHandle();
            handle.ident = Numbers.asIndex(ident).longValue();
            handle.started = true;
            return handle;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            return "<_thread._ThreadHandle object: ident=" + ident + ">";
        }

        private PyObject join(PyObject[] args, String[] kwNames) {
            Arguments.fill("join", args, kwNames, new String[] {"timeout"}, new PyObject[] {PyNone.INSTANCE});
            if (!started) {
                throw Slots.runtimeError("thread not started");
            }
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


    /** {@code get_ident()}: one process, so one number, and the reference's is never zero. */
    private static PyObject getIdent(PyObject[] args, String[] kwNames) {
        Arguments.none("get_ident", args, kwNames);
        return PyInt.of(1);
    }

    /** {@code get_native_id()}: likewise one number for the one thread. */
    private static PyObject getNativeId(PyObject[] args, String[] kwNames) {
        Arguments.none("get_native_id", args, kwNames);
        return PyInt.of(1);
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
     * A held lock, as one counter.
     *
     * <p>{@code acquire} cannot block here, because there is no other thread to wait for; it answers
     * whether the call would have succeeded, which for this runtime is always {@code True}.
     */
    static final class Lock extends PyObject {

        private int count;

        Lock(PyType type) {
            super(type);
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        @Override
        public String repr() {
            String held = count > 0 ? "locked" : "unlocked";
            String at = " at 0x" + Long.toHexString(System.identityHashCode(this));
            if (getType() == PyType.R_LOCK) {
                int owner = count > 0 ? 1 : 0;
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
            if (!Slots.truth(values[0])) {
                return PyBool.FALSE;
            }
            count++;
            return PyBool.TRUE;
        }

        private PyObject release(PyObject[] args, String[] kwNames) {
            Arguments.none("release", args, kwNames);
            if (count == 0) {
                throw Slots.runtimeError("release unlocked lock");
            }
            count--;
            return PyNone.INSTANCE;
        }

        private PyObject locked(PyObject[] args, String[] kwNames) {
            Arguments.none("locked", args, kwNames);
            return count > 0 ? PyBool.TRUE : PyBool.FALSE;
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
