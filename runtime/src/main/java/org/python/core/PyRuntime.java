package org.python.core;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
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

    private static PyDict builtins;
    private static PyModule sysModule;
    private static PyStream stdout;
    private static PyStream stderr;
    private static final Map<String, PyObject> modules = new LinkedHashMap<>();

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
     * Only the two namespaces the runtime itself builds are reachable, which is the whole of what M1
     * claims. The finder that walks {@code sys.path} and reads {@code .pyc} files is M4; until then a
     * missing module is reported as missing rather than silently returning an empty one.
     */
    public static PyObject importModule(String dotted) {
        startup();
        PyObject module = modules.get(dotted);
        if (module != null) {
            return module;
        }
        throw PyRaise.raise(
                ExceptionTypes.MODULE_NOT_FOUND_ERROR,
                "No module named '" + dotted + "'");
    }

    // --- running code --------------------------------------------------------------------

    /**
     * Run a module's top-level code object in a fresh module namespace.
     *
     * @param fileName what {@code __file__} reports; a script's own path, or null for code with no
     *     file, which is how CPython leaves {@code -c} and stdin
     */
    public static PyModule run(PyCode code, String moduleName, String fileName) {
        startup();
        PyModule module = new PyModule(moduleName);
        if (fileName != null) {
            module.set("__file__", PyStr.of(fileName));
        }
        module.set("__builtins__", builtins);
        Frame frame = new Frame(code, module.dictionary(), new PyObject[code.localCount()]);
        // A module's top level returns None; what a script means is the namespace it leaves behind.
        Interpreter.run(frame);
        return module;
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
        sys.set("version_info", versionInfo());
        sys.set("argv", PyList.of(PyStr.of("jy3")));
        sys.set("path", PyList.empty());
        sys.set("modules", PyDict.empty());
        sys.set("builtin_module_names",
                PyTuple.of(PyStr.of("builtins"), PyStr.of("sys")));
        sys.set("defaultencoding", PyStr.of("utf-8"));
        sys.set("getdefaultencoding", PyNativeFunction.positional(
                "getdefaultencoding", args -> PyStr.of("utf-8")));
        sys.set("exit", new PyNativeFunction("exit", PyRuntime::systemExit));
        return sys;
    }

    /**
     * A tuple, not the named sequence CPython has.
     *
     * {@code sys.version_info.major} needs a structseq, which needs the class system at M3. The
     * comparison and indexing every script actually uses already work from a tuple.
     */
    private static PyTuple versionInfo() {
        return PyTuple.of(
                PyInt.of(VERSION_MAJOR),
                PyInt.of(VERSION_MINOR),
                PyInt.of(VERSION_MICRO),
                PyStr.of("final"),
                PyInt.of(0));
    }

    private static int hexVersion() {
        return (VERSION_MAJOR << 24) | (VERSION_MINOR << 16) | (VERSION_MICRO << 8) | 0xF0;
    }

    /** {@code sys.exit(n)}, whose whole meaning is the exception the launcher catches. */
    private static PyObject systemExit(PyObject[] args, String[] kwNames) {
        Arguments.atMost("exit", args, kwNames, 1);
        if (args.length == 0) {
            throw PyRaise.of(new PyBaseException(ExceptionTypes.SYSTEM_EXIT, new PyObject[0]));
        }
        throw PyRaise.of(new PyBaseException(ExceptionTypes.SYSTEM_EXIT, args));
    }
}
