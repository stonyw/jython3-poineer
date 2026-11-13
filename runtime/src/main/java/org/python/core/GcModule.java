package org.python.core;

import java.math.BigInteger;

/**
 * {@code gc}: the collector's control surface, as much of it as this runtime can answer.
 *
 * <p>Objects here are reclaimed by the JVM, not by a collector this runtime drives, so the calls that
 * would steer one answer the smallest true thing rather than imitating a collection: {@code collect()}
 * reports zero objects freed, {@code get_count()} reports an empty generation counter, and the switches
 * are kept as flags a program can read back. The three calls that would have to enumerate the heap —
 * {@code get_objects}, {@code get_referrers} and {@code get_referents} — raise
 * {@link ExceptionTypes#NOT_IMPLEMENTED_ERROR} instead of answering an empty list, because an empty list
 * would say "nothing is alive" rather than "this runtime cannot see the heap".
 *
 * <p>{@code gc} is a built-in module in the reference, and it is filed as one here, which is what
 * {@code sys.builtin_module_names} and {@code __spec__.origin} then report. It exists at all because
 * {@code test_generators} and {@code test_descr} import it before anything else.
 */
final class GcModule {

    private static final String DOC =
            "This module provides an interface to the optional garbage collector.  It\n"
                    + "provides the ability to disable the collector, tune the collection frequency,\n"
                    + "and set debugging options.";

    /** The reference's {@code DEBUG_*} bits. */
    private static final int DEBUG_STATS = 1;
    private static final int DEBUG_COLLECTABLE = 2;
    private static final int DEBUG_UNCOLLECTABLE = 4;
    private static final int DEBUG_SAVEALL = 32;
    private static final int DEBUG_LEAK = DEBUG_COLLECTABLE | DEBUG_UNCOLLECTABLE | DEBUG_SAVEALL;

    /** One process, one flag: the collector runs or it does not, and only these calls say which. */
    private static boolean enabled = true;

    private static int debug;

    /** The generation thresholds, kept so that a program that sets them reads them back. */
    private static int threshold0 = 2000;
    private static int threshold1 = 10;
    private static int threshold2 = 10;

    private static final PyList GARBAGE = PyList.empty();
    private static final PyList CALLBACKS = PyList.empty();

    private GcModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("gc");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "gc");

        module.set("DEBUG_STATS", PyInt.of(DEBUG_STATS));
        module.set("DEBUG_COLLECTABLE", PyInt.of(DEBUG_COLLECTABLE));
        module.set("DEBUG_UNCOLLECTABLE", PyInt.of(DEBUG_UNCOLLECTABLE));
        module.set("DEBUG_SAVEALL", PyInt.of(DEBUG_SAVEALL));
        module.set("DEBUG_LEAK", PyInt.of(DEBUG_LEAK));
        module.set("garbage", GARBAGE);
        module.set("callbacks", CALLBACKS);

        module.set("collect", new PyNativeFunction("collect", GcModule::collect)
                .documented(
                        "collect($module, /, generation=2)\n"
                                + "--\n"
                                + "\n"
                                + "Run a garbage collection."));
        module.set("enable", PyNativeFunction.positional("enable", args -> {
            Arguments.none("enable()", args, null);
            enabled = true;
            return PyNone.INSTANCE;
        }));
        module.set("disable", PyNativeFunction.positional("disable", args -> {
            Arguments.none("disable()", args, null);
            enabled = false;
            return PyNone.INSTANCE;
        }));
        module.set("isenabled", PyNativeFunction.positional("isenabled", args -> {
            Arguments.none("isenabled()", args, null);
            return PyBool.of(enabled);
        }));
        module.set("get_count", PyNativeFunction.positional("get_count", args -> {
            Arguments.none("get_count()", args, null);
            // No generation of ours is waiting to be collected: the JVM's generations are not these.
            return PyTuple.of(PyInt.of(0), PyInt.of(0), PyInt.of(0));
        }));
        module.set("get_stats", PyNativeFunction.positional("get_stats", args -> {
            Arguments.none("get_stats()", args, null);
            // One entry per generation, each counting nothing: this runtime has no such generations.
            PyList stats = PyList.empty();
            stats.add(stat());
            stats.add(stat());
            stats.add(stat());
            return stats;
        }));
        module.set("get_debug", PyNativeFunction.positional("get_debug", args -> {
            Arguments.none("get_debug()", args, null);
            return PyInt.of(debug);
        }));
        module.set("set_debug", PyNativeFunction.positional("set_debug", args -> {
            Arguments.exactly("set_debug", args, null, 1);
            debug = integer("set_debug", args[0]);
            return PyNone.INSTANCE;
        }));
        module.set("get_threshold", PyNativeFunction.positional("get_threshold", args -> {
            Arguments.none("get_threshold()", args, null);
            return PyTuple.of(PyInt.of(threshold0), PyInt.of(threshold1), PyInt.of(threshold2));
        }));
        module.set("set_threshold", PyNativeFunction.positional("set_threshold", args -> {
            Arguments.between("set_threshold", args, null, 1, 3);
            int first = integer("set_threshold", args[0]);
            int second = args.length > 1 ? integer("set_threshold", args[1]) : threshold1;
            int third = args.length > 2 ? integer("set_threshold", args[2]) : threshold2;
            if (first < 0 || second < 0 || third < 0) {
                throw Slots.valueError("threshold must be a non-negative integer");
            }
            threshold0 = first;
            threshold1 = second;
            threshold2 = third;
            return PyNone.INSTANCE;
        }));
        module.set("freeze", PyNativeFunction.positional("freeze", args -> {
            Arguments.none("freeze()", args, null);
            return PyNone.INSTANCE;
        }));
        module.set("unfreeze", PyNativeFunction.positional("unfreeze", args -> {
            Arguments.none("unfreeze()", args, null);
            return PyNone.INSTANCE;
        }));
        module.set("get_freeze_count", PyNativeFunction.positional("get_freeze_count", args -> {
            Arguments.none("get_freeze_count()", args, null);
            return PyInt.of(0);
        }));
        module.set("get_objects", new PyNativeFunction("get_objects", GcModule::getObjects)
                .documented(
                        "get_objects($module, /, generation=None)\n"
                                + "--\n"
                                + "\n"
                                + "Return a list of all objects tracked by the collector, excluding the\n"
                                + "list returned."));
        module.set("get_referrers", new PyNativeFunction("get_referrers", GcModule::getReferrers)
                .documented(
                        "get_referrers($module, /, *objs)\n"
                                + "--\n"
                                + "\n"
                                + "Return a list of objects that directly refer to any of objs."));
        module.set("get_referents", new PyNativeFunction("get_referents", GcModule::getReferents)
                .documented(
                        "get_referents($module, /, *objs)\n"
                                + "--\n"
                                + "\n"
                                + "Return a list of objects directly referred to by any of the arguments."));
        return module;
    }

    /**
     * {@code collect(generation=2)}: the JVM does the work, so the count of objects freed is zero.
     *
     * <p>The request is passed on rather than swallowed, because the standard library's {@code
     * test.support.gc_collect} asks three times and then reads a weak reference: without a collection
     * the reference still answers, and a test that checks reclamation sees an object the interpreter has
     * already dropped. A {@code System.gc()} is a hint to the JVM rather than a promise, but it is the
     * only handle this runtime has on the heap.
     */
    private static PyObject collect(PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.fill("collect", args, kwNames,
                new String[] {"generation"}, new PyObject[] {PyInt.of(2)});
        int generation = integer("collect", given[0]);
        if (generation < 0 || generation > 2) {
            throw Slots.valueError(
                    "invalid collection generation: %d (must be in the range 0..2)", generation);
        }
        System.gc();
        return PyInt.of(0);
    }

    private static PyObject getObjects(PyObject[] args, String[] kwNames) {
        Arguments.atMostQualified("get_objects", args, kwNames, 1);
        throw Slots.notImplemented(
                "gc.get_objects() cannot see the JVM heap; it is not implemented (M6-a)");
    }

    private static PyObject getReferrers(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("get_referrers()", kwNames);
        throw Slots.notImplemented(
                "gc.get_referrers() cannot see the JVM heap; it is not implemented (M6-a)");
    }

    private static PyObject getReferents(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("get_referents()", kwNames);
        throw Slots.notImplemented(
                "gc.get_referents() cannot see the JVM heap; it is not implemented (M6-a)");
    }

    /** One generation's counters, as {@code get_stats} reports them. */
    private static PyDict stat() {
        PyDict stat = PyDict.empty();
        stat.put(PyStr.interned("collections"), PyInt.of(0));
        stat.put(PyStr.interned("collected"), PyInt.of(0));
        stat.put(PyStr.interned("uncollectable"), PyInt.of(0));
        return stat;
    }

    private static int integer(String name, PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() > 31) {
            throw Slots.overflowError("Python int too large to convert to C int");
        }
        return number.intValue();
    }
}
