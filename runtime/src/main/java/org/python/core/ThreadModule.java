package org.python.core;

/**
 * {@code _thread}: the primitives the {@code threading} module is written over.
 *
 * <p>This runtime runs one thread, so the two lock types differ only in what they print. A lock is a
 * counter of how many times it has been acquired: {@code acquire} always succeeds and {@code release}
 * takes one off, which makes {@code RLock} exactly right and {@code lock} re-entrant where the
 * reference would leave the second {@code acquire} waiting. That re-entrancy, together with the
 * absent thread creation rows, is the named deviation this module carries.
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
        return module;
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
