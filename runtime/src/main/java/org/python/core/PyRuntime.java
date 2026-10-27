package org.python.core;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state one interpreter process owns: the builtins namespace, the {@code sys} module, the two text
 * streams, and the modules that have been imported.
 *
 * This is deliberately the only global in the runtime. It is what {@code LOAD_NAME}'s fallback to
 * builtins reads, what {@code import} answers from, and what {@code print} writes through — so it has
 * to exist before the first frame does, and be the same one for every frame after.
 *
 * <p>One process runs one interpreter, single-threaded, as CPython's own {@code PyRuntimeState} still
 * assumes. Free-threading changes this class and nothing above it.
 */
public final class PyRuntime {

    /** The version of Python this runtime aims at, as {@code sys.hexversion} encodes it. */
    private static final int VERSION_MAJOR = 3;
    private static final int VERSION_MINOR = 14;
    private static final int VERSION_MICRO = 0;

    /**
     * How far into that release this runtime claims to be: a final one, of no serial.
     *
     * <p>Both halves are kept because {@code sys.version_info} reads them as a name and a count while
     * {@code sys.hexversion} packs them into its low byte as CPython's {@code PY_RELEASE_LEVEL_FINAL}
     * nibble over the serial.
     */
    private static final String RELEASE_LEVEL = "final";
    private static final int RELEASE_SERIAL = 0;

    private static PyDict builtins;
    private static PyModule sysModule;
    private static PyModule main;
    private static PyStream stdout;
    private static PyStream stderr;
    private static final Map<String, PyObject> modules = new LinkedHashMap<>();

    /**
     * One item of {@code tstate->exc_info}: the exception a handler is running for, and the item the
     * handler outside it was running for.
     *
     * <p>The chain is there because CPython's two reads of it differ. Every ordinary one — {@code
     * sys.exc_info()}, a bare {@code raise}, the implicit {@code __context__} — goes through {@code
     * _PyErr_GetTopmostException}, which steps past the items holding nothing. {@code PUSH_EXC_INFO} and
     * {@code _PyErr_ChainStackItem} read the head itself, and that is what lets a generator which is
     * handling nothing at all chain nothing onto a {@code throw}, even though the code that stepped it is
     * standing in a handler of its own.
     */
    private static final class Handled {
        private PyObject value;
        private final Handled outer;

        Handled(PyObject value, Handled outer) {
            this.value = value;
            this.outer = outer;
        }
    }

    /**
     * The head of that chain — per thread, because CPython's is per thread state.
     *
     * <p>The bottom item belongs to the thread and is never taken off; {@code None} in it is CPython's
     * "no handler here", which the walking read above moves straight past.
     */
    private static final ThreadLocal<Handled> handledException =
            ThreadLocal.withInitial(() -> new Handled(PyNone.INSTANCE, null));

    /** What a milestone has installed to answer imports the runtime cannot. */
    private static final List<ModuleFinder> finders = new ArrayList<>();

    private PyRuntime() {
    }

    /** Build the namespaces. Idempotent, because a caller may not know whether it is first. */
    public static synchronized void startup() {
        if (builtins != null) {
            return;
        }
        // The streams exist before sys, because sys's dictionary holds them.
        stdout = new PyStream("<stdout>",
                new PrintStream(new FileOutputStream(FileDescriptor.out)), 1,
                "surrogateescape", System.console() != null);
        stderr = new PyStream("<stderr>",
                new PrintStream(new FileOutputStream(FileDescriptor.err)), 2,
                "backslashreplace", true);

        builtins = Builtins.namespace();
        sysModule = buildSys();
        modules.put("sys", sysModule);
        modules.put("builtins", new PyModule("builtins", builtins));
    }

    /** The dictionary a module frame falls back to for a name it does not define. */
    public static PyDict builtins() {
        startup();
        return builtins;
    }

    public static PyStream stdout() {
        startup();
        return stdout;
    }

    public static PyStream stderr() {
        startup();
        return stderr;
    }

    /**
     * {@code import name}.
     *
     * The two namespaces the runtime builds itself are the first answer, then whatever a milestone has
     * installed: the interop module registers the finder that turns {@code java.util} into a package,
     * and the M4 finder will add the one that walks {@code sys.path}. A name no finder owns is
     * reported as missing rather than answered with an empty module.
     */
    public static PyObject importModule(String dotted) {
        startup();
        PyObject module = modules.get(dotted);
        if (module != null) {
            return module;
        }
        for (ModuleFinder finder : finders) {
            PyObject found = finder.find(dotted);
            if (found != null) {
                // Cached like any other module, because a second import must be the same object:
                // a package holds its loaded classes, and two copies would hold two states.
                modules.put(dotted, found);
                return found;
            }
        }
        throw PyRaise.raise(
                ExceptionTypes.MODULE_NOT_FOUND_ERROR,
                "No module named '" + dotted + "'");
    }

    /** Install a finder, in order of preference: the first that answers wins. */
    public static synchronized void addModuleFinder(ModuleFinder finder) {
        startup();
        if (!finders.contains(finder)) {
            finders.add(finder);
        }
    }

    // --- the exception a handler is running for -----------------------------------------

    /**
     * The exception the innermost handler that has one is running for: {@code
     * _PyErr_GetTopmostException}.
     *
     * <p>Items holding nothing are stepped over rather than reported, which is what lets a generator
     * parked with no handler of its own leave the caller's exception as the answer both to {@code
     * sys.exc_info()} and to where an error raised inside it gets chained.
     */
    public static PyObject handledException() {
        Handled item = handledException.get();
        while (item.value == PyNone.INSTANCE && item.outer != null) {
            item = item.outer;
        }
        return item.value;
    }

    /** The head of the chain, which is what {@code PUSH_EXC_INFO} and a {@code throw} are read from. */
    static PyObject handlerValue() {
        return handledException.get().value;
    }

    /**
     * Store the exception the innermost handler is running for, as {@code PUSH_EXC_INFO} and
     * {@code POP_EXCEPT} do.
     */
    public static void setHandledException(PyObject value) {
        handledException.get().value = value;
    }

    /**
     * Put a generator's own record at the head of the chain for the length of one step.
     *
     * <p>A new item rather than a swap of the head's value: CPython's {@code gi_exc_state} is an item with
     * a {@code previous_item} link, so the caller's handler stays underneath it — out of reach of the two
     * reads above, and back in reach the moment the step is over.
     */
    static void openHandlerItem(PyObject value) {
        handledException.set(new Handled(value, handledException.get()));
    }

    /** Take the head off and hand back what it was holding, which is the generator's record to keep. */
    static PyObject closeHandlerItem() {
        Handled item = handledException.get();
        handledException.set(item.outer);
        return item.value;
    }

    // --- running code --------------------------------------------------------------------

    /**
     * {@code __main__}: the namespace a script runs in, and the one an embedding caller shares with it.
     *
     * <p>Created on first use and kept for the life of the process, because the point of this module is
     * that names written into it are readable afterwards — by the next line the same caller executes,
     * and by the next caller in the same JVM.
     */
    public static synchronized PyModule mainModule() {
        startup();
        if (main == null) {
            main = namespace(new PyModule("__main__"));
            modules.put("__main__", main);
        }
        return main;
    }

    /**
     * Prepare a module the runtime has been handed for its top level, as {@code import} would.
     *
     * <p>The names CPython's {@code _init_module_attrs} gives every module it loads are given here too,
     * because {@code dir()} and {@code globals()} of a script are measured against the reference
     * interpreter's and answer with the names a module holds rather than with the ones a script wrote.
     * Three of them really are {@code None} for a script run by its path — {@code __package__},
     * {@code __spec__} and {@code __cached__} — {@code __doc__} is the module's own docstring and only
     * stays {@code None} when it has none, and {@code __loader__} alone is a placeholder here: the
     * reference interpreter's is a {@code SourceFileLoader}, and the import machinery that would own one
     * is M4's.
     */
    public static PyModule namespace(PyModule module) {
        startup();
        if (module.get("__builtins__") == null) {
            module.set("__builtins__", builtins);
        }
        for (String prepared : PREPARED_NAMES) {
            if (module.get(prepared) == null) {
                module.set(prepared, PyNone.INSTANCE);
            }
        }
        return module;
    }

    /** The module attributes {@code import} writes before a module's code runs. */
    private static final String[] PREPARED_NAMES = {
        "__doc__", "__package__", "__loader__", "__spec__", "__cached__"
    };

    /**
     * Run a module's top-level code object in {@code module}.
     *
     * @param fileName what {@code __file__} reports; a script's own path, or null for code with no
     *     file, which is how CPython leaves {@code -c}, stdin and an embedded snippet
     */
    public static void exec(PyCode code, PyModule module, String fileName) {
        if (fileName != null) {
            namespace(module).set("__file__", PyStr.of(fileName));
        }
        // A module's top level returns None; what a script means is the namespace it leaves behind.
        Interpreter.run(frame(code, namespace(module)));
    }

    /**
     * Run one expression's code object in {@code module} and give back what it evaluated to.
     *
     * <p>Only an {@code eval}-mode code object returns a value, which is why this takes a different
     * code object from {@link #exec} rather than a flag.
     */
    public static PyObject eval(PyCode code, PyModule module) {
        return Interpreter.run(frame(code, namespace(module)));
    }

    private static Frame frame(PyCode code, PyModule module) {
        return new Frame(code, module.dictionary(), new PyObject[code.localCount()]);
    }

    /**
     * {@code sys.argv}, as the launcher sets it after parsing its own options.
     *
     * <p>Element 0 is how the program was invoked, not what the OS handed over: {@code -c} for a
     * command, {@code -} for stdin, and for a script the path exactly as the user typed it.
     */
    public static void setArgv(String programName, List<String> rest) {
        startup();
        PyObject[] values = new PyObject[rest.size() + 1];
        values[0] = PyStr.of(programName);
        for (int i = 0; i < rest.size(); i++) {
            values[i + 1] = PyStr.of(rest.get(i));
        }
        sysModule.set("argv", PyList.of(values));
    }

    /**
     * {@code sys.name}, or null where the module has no such entry.
     *
     * <p>{@code _PySys_GetAttrString} is how CPython reaches {@code sys.tracebacklimit} from the
     * traceback printer, and this is the same read: a knob a script sets, not a name the runtime may
     * assume is there.
     */
    public static PyObject sysAttribute(String name) {
        startup();
        return sysModule.get(name);
    }

    /**
     * {@code sys.<name> = value}, which is how the launcher files the four {@code last_} names.
     *
     * <p>CPython's {@code _PyErr_PrintEx} sets them with {@code _PySys_SetAttr} before it reads the hook,
     * and none of the four exists until an exception escapes a program: nothing here creates them at
     * startup.
     */
    public static void setSysAttribute(String name, PyObject value) {
        startup();
        sysModule.set(name, value);
    }

    /** Drain both streams, which a process about to exit must do because stdout is block-buffered. */
    public static void flushStreams() {
        if (stdout != null) {
            stdout.flush();
        }
        if (stderr != null) {
            stderr.flush();
        }
    }

    // --- sys -----------------------------------------------------------------------------

    /** The Python level this runtime aims at, as {@code sys.version_info} also reports it. */
    public static String pythonVersion() {
        return VERSION_MAJOR + "." + VERSION_MINOR + "." + VERSION_MICRO;
    }

    /**
     * The items of {@code sys.version_info}, in the order its type files its fields.
     *
     * <p>Kept here rather than in {@link PyVersionInfo} because the three numbers are the ones this
     * class already owns and {@code hexversion} is a fourth reading of the same decision. A release
     * level and a serial go with them, and both are fixed: this runtime emulates a final release and
     * no candidate of one.
     */
    static PyObject[] versionItems() {
        return new PyObject[] {
            PyInt.of(VERSION_MAJOR),
            PyInt.of(VERSION_MINOR),
            PyInt.of(VERSION_MICRO),
            PyStr.of(RELEASE_LEVEL),
            PyInt.of(RELEASE_SERIAL)
        };
    }

    private static PyModule buildSys() {
        PyModule sys = new PyModule("sys");
        sys.set("stdout", stdout);
        sys.set("stderr", stderr);
        sys.set("__stdout__", stdout);
        sys.set("__stderr__", stderr);
        sys.set("platform", PyStr.of(System.getProperty("os.name", "unknown")
                .toLowerCase(java.util.Locale.ROOT)));
        sys.set("maxsize", PyInt.of(Long.MAX_VALUE));
        sys.set("maxunicode", PyInt.of(0x10FFFF));
        sys.set("hexversion", PyInt.of(hexVersion()));
        sys.set("api_version", PyInt.of(1013));
        sys.set("byteorder", PyStr.of("little"));
        sys.set("version", PyStr.of(pythonVersion() + " (Jython3 on the JVM)"));
        sys.set("executable", PyStr.of(System.getProperty("jy3.executable", "jy3")));
        sys.set("prefix", PyStr.of(System.getProperty("jy3.prefix", "")));
        sys.set("base_prefix", PyStr.of(System.getProperty("jy3.prefix", "")));
        sys.set("version_info", PyVersionInfo.current());
        sys.set("argv", PyList.of(PyStr.of("jy3")));
        sys.set("path", PyList.empty());
        sys.set("modules", PyDict.empty());
        sys.set("builtin_module_names",
                PyTuple.of(PyStr.of("builtins"), PyStr.of("sys")));
        sys.set("defaultencoding", PyStr.of("utf-8"));
        sys.set("getdefaultencoding", PyNativeFunction.positional(
                "getdefaultencoding", args -> PyStr.of("utf-8")));
        sys.set("exc_info", PyNativeFunction.positional("exc_info", args -> excInfo()));
        sys.set("exit", new PyNativeFunction("exit", PyRuntime::systemExit));
        // COPY_SYS_ATTR gives the two names the one object, so `sys.excepthook is sys.__excepthook__`
        // reads true until a script replaces the first of them — and then keeps reading true of the
        // second, which still holds this object.
        PyObject excepthook =
                new PyNativeFunction("excepthook", PyRuntime::excepthook)
                        .documented(
                                "Handle an exception by displaying it with a traceback on sys.stderr.");
        sys.set("excepthook", excepthook);
        sys.set("__excepthook__", excepthook);
        return sys;
    }

    /**
     * {@code sys.excepthook}: the printer CPython installs as the default, with its argument table in
     * front.
     *
     * <p>Two of the three arguments are what the display uses and one is not. The type is ignored —
     * {@code sys_excepthook_impl} hands the value to {@code PyErr_Display} and lets the value's own class
     * name the complaint, which is why {@code sys.excepthook(TypeError, ValueError('a'), None)} reads
     * {@code ValueError: a}. The traceback has one use: {@code _PyErr_Display} hangs it on the value where
     * the value carries none, because otherwise nothing would show it (CPython's issue 18776). Where the
     * value already travelled with its own, the argument is dropped.
     *
     * <p>A value that is not an exception is not refused by the table — the arguments are never type
     * checked. The Python printer rejects it, the display falls back to the C one, and the C one writes
     * its one line of complaint and shows nothing else; {@link TracebackReport} transcribes the Python
     * printer, so this is where that fallback has to be written out.
     */
    private static PyObject excepthook(PyObject[] args, String[] kwNames) {
        // The keyword refusal comes from the call machinery, before the body is entered, and it spells
        // the function as module and name: cfunction_check_kwargs formats _PyObject_FunctionStr(func).
        Arguments.noKeywords("sys.excepthook()", kwNames);
        Arguments.exactly("excepthook", args, kwNames, 3);
        if (!(args[1] instanceof PyBaseException value)) {
            stderr().writeText(
                    "TypeError: print_exception(): Exception expected for value, "
                            + args[1].getType().name()
                            + " found\n");
            return PyNone.INSTANCE;
        }
        if (args[2] instanceof PyTraceback handed && value.traceback() == PyNone.INSTANCE) {
            value.setTraceback(handed);
        }
        TracebackReport.print(value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code sys.exc_info()}: the handled exception as a {@code (type, value, traceback)} triple.
     *
     * <p>The third is the traceback the exception travelled in on, which is the one thing a handler can
     * report that a fresh exception could not: it names the frames above the raise, including this handler's
     * own frame at the call that failed. Type and value are exactly what {@code PUSH_EXC_INFO} stored.
     */
    private static PyObject excInfo() {
        if (!(handledException() instanceof PyBaseException error)) {
            return PyTuple.of(PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE);
        }
        return PyTuple.of(error.getType(), error, error.traceback());
    }

    /**
     * {@code sys.hexversion}'s last byte: the release level this runtime claims, shifted up against its
     * serial. {@code 0xF0} is final, serial zero.
     */
    private static int hexVersion() {
        return (VERSION_MAJOR << 24) | (VERSION_MINOR << 16) | (VERSION_MICRO << 8) | 0xF0;
    }

    /**
     * {@code sys.exit(n)}, whose whole meaning is the exception the launcher catches.
     *
     * <p>The value is made by calling the class rather than by building one, because that is what {@code
     * PyErr_SetObject} does — {@code _PyErr_CreateExc} runs {@code SystemExit}'s creator and then its
     * initialiser, and the initialiser is the only thing that fills {@code e.code}. No argument and {@code
     * None} are the same exit and both hand the class nothing, which is why a call of {@code sys.exit(None)}
     * has empty arguments while {@code sys.exit((1, 2))} keeps the pair as one.
     */
    private static PyObject systemExit(PyObject[] args, String[] kwNames) {
        Arguments.atMost("exit", args, kwNames, 1);
        PyObject[] given = args.length == 0 || args[0] == PyNone.INSTANCE ? new PyObject[0] : args;
        throw PyRaise.of((PyBaseException) Slots.call(ExceptionTypes.SYSTEM_EXIT, given, null));
    }
}
