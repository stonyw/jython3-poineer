package org.python.core;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code resource}: the resource limits a program can read and the usage counters it can sample.
 *
 * <p>The reference module is a thin wrapper over {@code getrlimit}, {@code setrlimit} and
 * {@code getrusage}. This runtime sits on a JVM, which exposes none of the three, so the constants are
 * the reference's macOS table and the limits are this module's own bookkeeping: {@code getrlimit}
 * answers the values the process started with, and {@code setrlimit} records a new pair where the
 * reference would ask the kernel. A program that reads back what it wrote sees the reference's answer;
 * a program that expects the kernel itself to enforce a lowered limit does not, which is the one
 * deviation this surface carries.
 *
 * <p>The starting values are measured on macOS: a stack of 8 MiB with a 64 MiB ceiling, 1024 file
 * descriptors, 10666 processes. The five limits this platform leaves unlimited answer
 * {@link #RLIM_INFINITY}, as the reference's do.
 */
final class ResourceModule {

    /** The nine resource numbers, in the reference's order. */
    static final int RLIMIT_CPU = 0;
    static final int RLIMIT_FSIZE = 1;
    static final int RLIMIT_DATA = 2;
    static final int RLIMIT_STACK = 3;
    static final int RLIMIT_CORE = 4;
    static final int RLIMIT_AS = 5;
    /** {@code RLIMIT_RSS} is the same resource as {@code RLIMIT_AS} on every platform here. */
    static final int RLIMIT_RSS = 5;
    static final int RLIMIT_MEMLOCK = 6;
    static final int RLIMIT_NPROC = 7;
    static final int RLIMIT_NOFILE = 8;

    /** The number of distinct resources, and so the width of {@link #limits}. */
    private static final int RESOURCES = 9;

    static final long RLIM_INFINITY = Long.MAX_VALUE;

    static final int RUSAGE_SELF = 0;
    static final int RUSAGE_CHILDREN = -1;

    /** The page size {@code getpagesize} answers, which is what an arm64 macOS kernel uses. */
    private static final int PAGESIZE = 16384;

    /**
     * The soft and hard limit of each resource, as this module last recorded them.
     *
     * <p>Row {@code i} is resource {@code i}: element 0 the soft limit, element 1 the hard one. The
     * initial values are the reference's own answers on a freshly started macOS process.
     */
    private static final long[][] limits = {
        {RLIM_INFINITY, RLIM_INFINITY},                 // CPU
        {RLIM_INFINITY, RLIM_INFINITY},                 // FSIZE
        {RLIM_INFINITY, RLIM_INFINITY},                 // DATA
        {8372224L, 67092480L},                          // STACK
        {0L, RLIM_INFINITY},                            // CORE
        {RLIM_INFINITY, RLIM_INFINITY},                 // AS / RSS
        {RLIM_INFINITY, RLIM_INFINITY},                 // MEMLOCK
        {10666L, 16000L},                               // NPROC
        {1048575L, RLIM_INFINITY},                      // NOFILE
    };

    private ResourceModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("resource");
        PyRuntime.markBuiltIn(module, "resource");
        module.set("RLIMIT_CPU", PyInt.of(RLIMIT_CPU));
        module.set("RLIMIT_FSIZE", PyInt.of(RLIMIT_FSIZE));
        module.set("RLIMIT_DATA", PyInt.of(RLIMIT_DATA));
        module.set("RLIMIT_STACK", PyInt.of(RLIMIT_STACK));
        module.set("RLIMIT_CORE", PyInt.of(RLIMIT_CORE));
        module.set("RLIMIT_AS", PyInt.of(RLIMIT_AS));
        module.set("RLIMIT_RSS", PyInt.of(RLIMIT_RSS));
        module.set("RLIMIT_MEMLOCK", PyInt.of(RLIMIT_MEMLOCK));
        module.set("RLIMIT_NPROC", PyInt.of(RLIMIT_NPROC));
        module.set("RLIMIT_NOFILE", PyInt.of(RLIMIT_NOFILE));
        module.set("RLIM_INFINITY", PyInt.of(RLIM_INFINITY));
        module.set("RUSAGE_SELF", PyInt.of(RUSAGE_SELF));
        module.set("RUSAGE_CHILDREN", PyInt.of(RUSAGE_CHILDREN));
        module.set("error", ExceptionTypes.OS_ERROR);
        module.set("struct_rusage", PyType.STRUCT_RUSAGE);
        // The reference files these four with no docstring at all, which is what `None` means here.
        module.set("getrlimit", plain("getrlimit", ResourceModule::getrlimit));
        module.set("setrlimit", plain("setrlimit", ResourceModule::setrlimit));
        module.set("getrusage", plain("getrusage", ResourceModule::getrusage));
        module.set("getpagesize", plain("getpagesize", args -> {
            Arguments.none("resource.getpagesize()", args, null);
            return PyInt.of(PAGESIZE);
        }));
        return module;
    }

    /**
     * A function that refuses every keyword argument by its module-qualified name.
     *
     * <p>{@code PyNativeFunction.positional} says just {@code getrlimit() takes no keyword arguments};
     * the reference's C functions are qualified, and 3.14 reports {@code resource.getrlimit()}.
     */
    private static PyNativeFunction plain(
            String name, java.util.function.Function<PyObject[], PyObject> body) {
        return new PyNativeFunction(name, (args, keywordNames) -> {
            if (keywordNames != null) {
                throw Slots.typeError("resource.%s() takes no keyword arguments", name);
            }
            return body.apply(args);
        });
    }

    /** {@code getrlimit(resource)}: the recorded pair, which a program may have set earlier. */
    private static PyObject getrlimit(PyObject[] args) {
        Arguments.exactlyOne("resource.getrlimit()", args, null);
        int resource = resource(args[0]);
        long[] pair = limits[resource];
        return PyTuple.of(PyInt.of(pair[0]), PyInt.of(pair[1]));
    }

    /**
     * {@code setrlimit(resource, limits)}: records the pair this module answers with afterwards.
     *
     * <p>Nothing is asked of the kernel, so a program that lowers a limit does not actually get it
     * enforced; what it reads back is what it wrote.
     */
    private static PyObject setrlimit(PyObject[] args) {
        Arguments.exactly("setrlimit", args, null, 2);
        int resource = resource(args[0]);
        List<PyObject> pair = items(args[1]);
        if (pair.size() != 2) {
            throw Slots.valueError("expected a tuple of 2 integers");
        }
        long soft = Numbers.asIndex(pair.get(0)).longValueExact();
        long hard = Numbers.asIndex(pair.get(1)).longValueExact();
        if (soft > hard) {
            // The kernel's own refusal for a soft limit above the hard one, worded as `errno` words it.
            throw Slots.valueError("current limit exceeds maximum limit");
        }
        limits[resource][0] = soft;
        limits[resource][1] = hard;
        return PyNone.INSTANCE;
    }

    /** {@code getrusage(who)}: the sample the JVM can speak to, and zeros for the rest. */
    private static PyObject getrusage(PyObject[] args) {
        Arguments.exactlyOne("resource.getrusage()", args, null);
        int who = Numbers.asIndex(args[0]).intValue();
        if (who != RUSAGE_SELF && who != RUSAGE_CHILDREN) {
            throw Slots.valueError("invalid who parameter");
        }
        if (who == RUSAGE_CHILDREN) {
            return StructRusage.zeros();
        }
        double cpuSeconds = Math.max(0L, processCpuNanos()) / 1e9;
        return StructRusage.of(cpuSeconds, 0.0);
    }

    /** The resource number of a call's first argument, or the reference's complaint. */
    private static int resource(PyObject value) {
        int resource = Numbers.asIndex(value).intValue();
        if (resource < 0 || resource >= RESOURCES) {
            throw Slots.valueError("invalid resource specified");
        }
        return resource;
    }

    /** The total CPU nanoseconds the JVM exposes for this process, or zero where it exposes none. */
    private static long processCpuNanos() {
        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            Class<?> extended = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (extended.isInstance(bean)) {
                return (Long) extended.getMethod("getProcessCpuTime").invoke(bean);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A JVM without the extended bean simply reports no processor time.
        }
        return 0L;
    }

    private static List<PyObject> drain(PyObject sequence) {
        try {
            return items(sequence);
        } catch (PyRaise notIterable) {
            throw Slots.typeError("constructor requires a sequence");
        }
    }

    /** The items of {@code sequence}, leaving a non-iterable to say what it is. */
    private static List<PyObject> items(PyObject sequence) {
        List<PyObject> items = new ArrayList<>();
        PyIterator iterator = Slots.iterator(sequence);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            items.add(item);
        }
        return items;
    }

    /**
     * {@code resource.struct_rusage}: the sixteen counters {@code getrusage} answers with.
     *
     * <p>Two of the sixteen are seconds as floats, which is the reference's shape on every platform
     * that writes {@code struct rusage}; the rest are counts. A value this runtime builds names only
     * the two times it can measure, and every other field reads zero.
     */
    static final class StructRusage extends PyTuple {

        /** The names of the sixteen items, in item order. */
        static final String[] FIELDS = {
            "ru_utime", "ru_stime", "ru_maxrss", "ru_ixrss", "ru_idrss", "ru_isrss", "ru_minflt",
            "ru_majflt", "ru_nswap", "ru_inblock", "ru_oublock", "ru_msgsnd", "ru_msgrcv",
            "ru_nsignals", "ru_nvcsw", "ru_nivcsw"
        };

        private static final String[] PARAMETERS = {"sequence", "dict"};

        /** The type's dictionary: sixteen fields and the four structseq values. */
        private static final Members METHODS = Members.of(PyType.STRUCT_RUSAGE)
                .addNoArgument("__reduce__", StructRusage::reduced)
                .addField("ru_utime", "user time used")
                .addField("ru_stime", "system time used")
                .addField("ru_maxrss", "maximum resident set size")
                .addField("ru_ixrss", "integral shared memory size")
                .addField("ru_idrss", "integral unshared data size")
                .addField("ru_isrss", "integral unshared stack size")
                .addField("ru_minflt", "page reclaims")
                .addField("ru_majflt", "page faults")
                .addField("ru_nswap", "swaps")
                .addField("ru_inblock", "block input operations")
                .addField("ru_oublock", "block output operations")
                .addField("ru_msgsnd", "messages sent")
                .addField("ru_msgrcv", "messages received")
                .addField("ru_nsignals", "signals received")
                .addField("ru_nvcsw", "voluntary context switches")
                .addField("ru_nivcsw", "involuntary context switches")
                .addValue("__doc__", PyType.STRUCT_RUSAGE.getAttr("__doc__"))
                .addValue("n_sequence_fields", PyInt.of(FIELDS.length))
                .addValue("n_fields", PyInt.of(FIELDS.length))
                .addValue("n_unnamed_fields", PyInt.of(0))
                .addValue("__match_args__", matchArguments());

        private StructRusage(PyObject[] items) {
            super(items, PyType.STRUCT_RUSAGE);
        }

        /** A value whose first two counters are the two times and whose rest are zero. */
        static StructRusage of(double user, double system) {
            PyObject[] items = new PyObject[FIELDS.length];
            items[0] = PyFloat.of(user);
            items[1] = PyFloat.of(system);
            for (int i = 2; i < items.length; i++) {
                items[i] = PyInt.of(0);
            }
            return new StructRusage(items);
        }

        /** A value with every counter at zero, which is what a process without children reports. */
        static StructRusage zeros() {
            return of(0.0, 0.0);
        }

        /**
         * {@code resource.struct_rusage(sequence[, dict])}: exactly sixteen items.
         *
         * <p>The reference's structseq constructor never coerces an item — a sequence of integers
         * stays integers — and its dictionary names fields the sequence did not fill. Since a
         * sixteen-item sequence fills every field, any non-empty dictionary duplicates one, which is
         * the reference's complaint.
         */
        static PyObject called(PyObject[] args, PyTuple keywordNames) {
            String[] names = new String[keywordNames == null ? 0 : keywordNames.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = Numbers.asText(keywordNames.at(i));
            }
            PyObject[] values = Arguments.parameters(
                    "struct_rusage", args, names, PARAMETERS,
                    new PyObject[] {null, PyNone.INSTANCE}, 1);
            List<PyObject> sequence = drain(values[0]);
            if (sequence.size() != FIELDS.length) {
                throw Slots.typeError(
                        "resource.struct_rusage() takes a %d-sequence (%d-sequence given)",
                        FIELDS.length, sequence.size());
            }
            PyObject dictionary = values[1];
            if (dictionary != PyNone.INSTANCE) {
                if (dictionary.getType() != PyType.DICT) {
                    throw Slots.typeError(
                            "resource.struct_rusage() takes a dict as second arg, if any");
                }
                if (((PyDict) dictionary).size() != 0) {
                    throw Slots.typeError(
                            "resource.struct_rusage() got duplicate or unexpected field name(s)");
                }
            }
            return new StructRusage(sequence.toArray(new PyObject[0]));
        }

        private static PyTuple matchArguments() {
            PyObject[] names = new PyObject[FIELDS.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = PyStr.interned(FIELDS[i]);
            }
            return PyTuple.of(names);
        }

        @Override
        public PyObject getAttr(String name) {
            PyObject filed = Members.filed(this, name);
            return filed == Slots.DECLINED ? super.getAttr(name) : filed;
        }

        @Override
        public PyObject setAttr(String name, PyObject value) {
            throw refused(name);
        }

        @Override
        public PyObject delAttr(String name) {
            throw refused(name);
        }

        private PyRaise refused(String name) {
            if (Members.filesField(this, name)) {
                return Slots.attributeError("readonly attribute");
            }
            String kind = getType().filedName();
            if (Members.files(this, name)) {
                return Slots.attributeError(
                        "'%s' object attribute '%s' is read-only", kind, name);
            }
            return Slots.attributeError(
                    "'%s' object has no attribute '%s' and no __dict__ for setting new attributes",
                    kind, name);
        }

        /** The sixteen items, each named: {@code resource.struct_rusage(ru_utime=..., ...)}. */
        @Override
        public String repr() {
            StringBuilder out = new StringBuilder(getType().filedName()).append('(');
            for (int i = 0; i < FIELDS.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(FIELDS[i]).append('=').append(at(i).repr());
            }
            return out.append(')').toString();
        }

        /** {@code __reduce__}: {@code (cls, (tuple,))}, with no extras to carry. */
        private static PyObject reduced(PyObject self) {
            return PyTuple.of(self.getType(), PyTuple.of(PyTuple.of(((PyTuple) self).elements())));
        }
    }
}
