package org.python.core;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * The modules this runtime builds itself, and therefore the ones {@link BuiltinImporter} claims.
     *
     * <p>Until M4 there is no finder and no {@code sys.path}, so the answer to "is this a built-in
     * module?" is exactly this list; the reference interpreter's is longer because it also holds the
     * extension modules, which are imports this runtime answers in Java instead.
     */
    private static final String[] BUILTIN_MODULE_NAMES =
            {"builtins", "sys", "_ast", "_io", "_tokenize", "_opcode", "_signal", "_string", "_types",
                "_weakref", "itertools", "_thread", "atexit", "_imp", "_random",
                "_struct", "binascii", "_codecs", "errno", "math", "time", "posix", "_collections",
                "unicodedata", "resource", "_typing"};

    /** Whether {@code name} is one of the modules this runtime builds itself. */
    static boolean isBuiltinModuleName(String name) {
        for (String builtIn : BUILTIN_MODULE_NAMES) {
            if (builtIn.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static PyDict builtins;
    private static PyModule sysModule;
    private static PyModule main;
    private static PyStream stdout;
    private static PyStream stderr;
    private static PyStream stdin;
    /**
     * What {@code sys.modules} starts out as, and the one mapping the import machinery reads.
     *
     * <p>It is a real dict because the reference interpreter's is: {@code sys.modules} answers every
     * dict question and a script may write into it. The field and the attribute are the same object
     * only until a script rebinds {@code sys.modules}, which in CPython replaces the attribute without
     * touching the interpreter's own mapping — {@code import} keeps reading this field either way.
     */
    private static PyDict modules;

    /**
     * The directories {@code import} searches, as the launcher set them: the script's directory first,
     * then {@code PYTHONPATH}. {@code sys.path} is a list over this same value, so a program that
     * rewrites the list is honoured and a program that reads it sees what this process started with.
     */
    private static List<String> sysPath = List.of("");

    /**
     * What {@code sys.getrecursionlimit} answers, which is a number this runtime stores and does not act
     * on: frames live on the JVM's stack, so depth is bounded by the thread's own size rather than by
     * this value. The reference's default is 1000, and it is what a program reads here until it sets
     * another.
     */
    private static int recursionLimit = 1000;

    /**
     * The compiler an imported source file goes through, installed by the layer that owns one.
     *
     * <p>The design rule that keeps {@code runtime} below {@code front} lives here: a finder can find
     * a {@code .py} file and a loader can read it, but only the embedding layer can turn its text into
     * code, so it hands that ability in rather than the runtime reaching up for it.
     */
    private static SourceCompiler sourceCompiler;

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
        // The streams exist before sys, because sys's dictionary holds them. Both reading and writing
        // start `strict` in 3.14 — measured, `sys.stdin.errors` and `sys.stdout.errors` — and only the
        // report on stderr keeps `backslashreplace`, so that writing the exception text cannot itself
        // fail. `reconfigure` already writes this same pair into the two writable streams when it is
        // asked without an `errors`, which is why its corpora agreed before this line did.
        stdout = new PyStream("<stdout>",
                new PrintStream(new FileOutputStream(FileDescriptor.out)), 1,
                "strict", System.console() != null);
        stderr = new PyStream("<stderr>",
                new PrintStream(new FileOutputStream(FileDescriptor.err)), 2,
                "backslashreplace", true);
        // `input` reads this one, and `PyFile_GetLine` asks it for a line rather than the descriptor.
        stdin = new PyStream("<stdin>", System.in, 0, "strict");

        modules = PyDict.empty();
        builtins = Builtins.namespace();
        sysModule = buildSys();
        PyModule home = new PyModule("builtins", builtins);
        PyModule templateLib = buildTemplateLib();
        PyModule annotationLib = AnnotationLib.newModule();
        // `importlib.util` is a submodule of `importlib`, so the parent holds it as an attribute
        // before either is filed: `import importlib.util` then reads the one this runtime built.
        PyModule importLib = Importlib.newModule();
        PyModule importLibUtil = Importlib.newUtil();
        PyModule machinery = Importlib.newMachinery();
        PyModule zipImport = ZipImport.newModule();
        importLib.set("util", importLibUtil);
        importLib.set("machinery", machinery);
        // The module whose classes and functions predate it: `_io` gathers the streams under the
        // names the reference files them with.
        PyModule ioModule = Io.newModule();
        // The one name `tokenize.py` takes from C, which is what `traceback.py` imports at module scope.
        PyModule tokenizeModule = TokenizeModule.newModule();
        // The C half of `opcode`, which `dis` imports and `inspect` reaches through it.
        PyModule opcodeModule = OpcodeModule.newModule();
        // The C half of `signal`, which `unittest` reaches through `unittest.signals`.
        PyModule signalModule = SignalModule.newModule();
        // The two helpers `string.Formatter` is built on, which the `string` package imports at module
        // scope — the first import of `logging`, and so of `test.support`.
        PyModule stringModule = StringModule.newModule();
        // The two module objects whose names are types this runtime already had, and the seven the
        // class system cannot make yet: they are gathered here so that a program can ask a type's name
        // and module without importing the Python wrapper, `types` or `weakref`.
        PyModule typesModule = TypesModule.newModule();
        PyModule weakrefModule = Weakref.newModule();
        // The two extension modules `reprlib` imports at its top, and the ones that let the first
        // standard-library module written in Python run on top of this runtime's own machinery.
        PyModule itertoolsModule = Itertools.newModule();
        PyModule threadModule = ThreadModule.newModule();
        // `logging` registers a shutdown function with it at module scope, which is the first import of
        // it on the road to `test.support`.
        PyModule atexitModule = AtexitModule.newModule();
        // `test.support.import_helper` imports it at module scope; what it asks for is answered
        // against this runtime's own module tables.
        PyModule impModule = ImpModule.newModule();
        // `random.py` is written as `class Random(_random.Random)`, so the whole module is the base
        // type and the six rows it files.
        PyModule randomModule = RandomModule.newModule();
        // `test.support.os_helper` normalizes a file name with this module on `darwin`, and every
        // test target imports that helper at module scope.
        PyModule unicodeDataModule = UnicodeDataModule.newModule();
        // `test.support` reads and lowers the core-file and stack limits at import time, and every one
        // of the seven criterion targets imports it.
        PyModule resourceModule = ResourceModule.newModule();
        // The C half of `typing`, which `typing.py` imports on its first line: `TypeVar` and the rest
        // of the type-parameter family are types this runtime declares, `Generic` included.
        PyModule typingModule = TypingModule.newModule();
        PyModule structModule = StructModule.newModule();
        PyModule binasciiModule = BinasciiModule.newModule();
        PyModule errnoModule = ErrnoModule.newModule();
        PyModule codecsModule = CodecsModule.newModule();
        PyModule mathModule = MathModule.newModule();
        PyModule timeModule = TimeModule.newModule();
        // The two modules `re` needs before any of its own Python code can run: the engine boundary
        // and the one decorator `re/__init__.py` imports at module scope.
        PyModule sreModule = SreModule.newModule();
        // The one module `warnings` needs at module scope, under both the private and the public
        // name: `_py_warnings` asks for `_contextvars` and a program imports `contextvars`.
        PyModule contextvarsModule = ContextvarsModule.newModule();
        PyModule contextvarsWrapper = ContextvarsModule.newWrapper();
        // The filesystem and environment calls `os` re-exports, and the one container `collections`
        // cannot import without: `contextlib` imports `deque` from there at module scope.
        PyModule posixModule = PosixModule.newModule();
        // The two modules `subprocess` imports: the C fork/exec half, which this runtime answers over
        // ProcessBuilder, and the readiness call `selectors` needs before `communicate` can loop.
        PyModule posixSubprocessModule = PosixSubprocessModule.newModule();
        PyModule selectModule = SelectModule.newModule();
        PyModule collectionsModule = CollectionsModule.newModule();
        // The collector's control surface, which `test_generators` and `test_descr` import first.
        PyModule gcModule = GcModule.newModule();
        modules.put(PyStr.interned("sys"), sysModule);
        modules.put(PyStr.interned("builtins"), home);
        modules.put(PyStr.interned("string.templatelib"), templateLib);
        modules.put(PyStr.interned("annotationlib"), annotationLib);
        modules.put(PyStr.interned("importlib"), importLib);
        modules.put(PyStr.interned("importlib.util"), importLibUtil);
        modules.put(PyStr.interned("importlib.machinery"), machinery);
        modules.put(PyStr.interned("zipimport"), zipImport);
        modules.put(PyStr.interned("_io"), ioModule);
        modules.put(PyStr.interned("_tokenize"), tokenizeModule);
        modules.put(PyStr.interned("_opcode"), opcodeModule);
        modules.put(PyStr.interned("_signal"), signalModule);
        modules.put(PyStr.interned("_string"), stringModule);
        modules.put(PyStr.interned("_types"), typesModule);
        modules.put(PyStr.interned("_weakref"), weakrefModule);
        modules.put(PyStr.interned("itertools"), itertoolsModule);
        modules.put(PyStr.interned("_thread"), threadModule);
        modules.put(PyStr.interned("atexit"), atexitModule);
        modules.put(PyStr.interned("_imp"), impModule);
        modules.put(PyStr.interned("_random"), randomModule);
        modules.put(PyStr.interned("unicodedata"), unicodeDataModule);
        modules.put(PyStr.interned("resource"), resourceModule);
        modules.put(PyStr.interned("_posixsubprocess"), posixSubprocessModule);
        modules.put(PyStr.interned("select"), selectModule);
        modules.put(PyStr.interned("_typing"), typingModule);
        modules.put(PyStr.interned("_struct"), structModule);
        modules.put(PyStr.interned("binascii"), binasciiModule);
        modules.put(PyStr.interned("errno"), errnoModule);
        modules.put(PyStr.interned("_codecs"), codecsModule);
        modules.put(PyStr.interned("math"), mathModule);
        modules.put(PyStr.interned("time"), timeModule);
        modules.put(PyStr.interned("_sre"), sreModule);
        // `functools` is deliberately not filed here either: the reference's `functools.py` is the
        // module programs mean, and every `_functools` import in it is optional, so it needs nothing
        // from this runtime. Only a run with no standard library reaches `#buildFunctoolsModule`.
        modules.put(PyStr.interned("_contextvars"), contextvarsModule);
        modules.put(PyStr.interned("contextvars"), contextvarsWrapper);
        modules.put(PyStr.interned("posix"), posixModule);
        modules.put(PyStr.interned("_collections"), collectionsModule);
        modules.put(PyStr.interned("gc"), gcModule);
        // `string` itself is deliberately not filed here: when a standard library is reachable its
        // `string/__init__.py` is the package every caller means, and only a run with no `string`
        // module anywhere gets the stub {@link #buildStringPackage()} builds, through `rebuild`.
        // The two modules CPython builds during initialization are imported from built-in code, so
        // their loader is the one loader that answers without a finder, and their spec says the same:
        // origin 'built-in', no search locations. The order of the three writes is the order the
        // reference's dictionaries are measured in.
        for (PyModule builtIn : new PyModule[] {home, sysModule}) {
            String name = ((PyStr) builtIn.get("__name__")).getValue();
            markBuiltIn(builtIn, name);
        }
        belongTo(home);
        belongTo(sysModule);
        belongTo(templateLib);
        belongTo(annotationLib);
        belongTo(importLib);
        belongTo(importLibUtil);
        belongTo(machinery);
        belongTo(zipImport);
        belongTo(ioModule);
        belongTo(tokenizeModule);
        belongTo(signalModule);
        belongTo(stringModule);
        belongTo(typesModule);
        belongTo(weakrefModule);
        belongTo(itertoolsModule);
        belongTo(threadModule);
        belongTo(atexitModule);
        belongTo(impModule);
        belongTo(randomModule);
        belongTo(unicodeDataModule);
        belongTo(resourceModule);
        belongTo(typingModule);
        belongTo(structModule);
        belongTo(binasciiModule);
        belongTo(errnoModule);
        belongTo(codecsModule);
        belongTo(mathModule);
        belongTo(timeModule);
        belongTo(sreModule);
        belongTo(contextvarsModule);
        belongTo(contextvarsWrapper);
        belongTo(posixModule);
        belongTo(collectionsModule);
        belongTo(gcModule);
    }

    /**
     * The three metadata writes every built-in module carries, in the order the reference makes them.
     *
     * <p>Called from a module's own builder right after its {@code __doc__}, because a dictionary keeps
     * insertion order and the reference lists {@code __package__}, {@code __loader__} and {@code __spec__}
     * before any of the module's own names.
     */
    static void markBuiltIn(PyModule module, String name) {
        module.set("__package__", PyStr.of(""));
        module.set("__loader__", BuiltinImporter.type());
        module.set("__spec__", PyModuleSpec.builtin(name, BuiltinImporter.type()));
    }

    /**
     * Where the modules this runtime builds for itself are said to live.
     *
     * <p>Nothing is read from here: those modules exist because this runtime implements them, and a
     * {@code SourceFileLoader} spec still has to name an origin for {@code __file__},
     * {@code __spec__.origin} and the {@code __cached__} derived from them. The prefix is the only
     * part a script could tell apart from an installed interpreter's, so the corpus reads the tail.
     */
    static final String STDLIB = "/usr/local/lib/python3.14";

    /**
     * {@code string}: the package whose {@code templatelib} this runtime answers for itself.
     *
     * <p>Only what a script can see is here. The package's own constants and functions are not
     * carried yet; {@code __file__}, {@code __cached__} and the spec are, because
     * {@code string.templatelib}'s parent has to exist before the child can be found again.
     */
    private static PyModule buildStringPackage() {
        String directory = STDLIB + "/string";
        String origin = directory + "/__init__.py";
        PySourceFileLoader loader = new PySourceFileLoader("string", origin);
        PyModuleSpec spec = PyModuleSpec.source("string", loader, origin, directory);
        PyModule module = new PyModule("string");
        module.set("__doc__", PyStr.of("A collection of string constants."));
        module.set("__package__", PyStr.of("string"));
        module.set("__loader__", loader);
        module.set("__spec__", spec);
        module.set("__file__", Slots.getAttr(spec, "origin"));
        module.set("__cached__", Slots.getAttr(spec, "cached"));
        module.set("__path__", Slots.getAttr(spec, "submodule_search_locations"));
        // Bound here as `import string.templatelib` would bind it on the parent, unless a program
        // dropped the child from `sys.modules` and has not asked for it again.
        PyObject child = modules.get(PyStr.interned("string.templatelib"));
        if (child != null) {
            module.set("templatelib", child);
        }
        return namespace(module);
    }

    /**
     * {@code string.templatelib}: the two types a t-string literal produces and the two helpers PEP 750
     * files beside them.
     *
     * <p>The module is built here rather than read from disk, but it reports the metadata a
     * {@code SourceFileLoader} would give it: an origin under {@link #STDLIB}, the bytecode path
     * derived from that origin, and a loader whose {@code name} is the dotted name. Without those, a
     * script that compares {@code __spec__.origin} with {@code __file__} would see a module no loader
     * could have produced.
     */
    private static PyModule buildTemplateLib() {
        String directory = STDLIB + "/string";
        String origin = directory + "/templatelib.py";
        PySourceFileLoader loader = new PySourceFileLoader("string.templatelib", origin);
        PyModuleSpec spec = PyModuleSpec.source("string.templatelib", loader, origin, null);
        PyModule module = new PyModule("string.templatelib");
        module.set("__doc__", PyStr.of("Support for template string literals (t-strings)."));
        module.set("__package__", PyStr.of("string"));
        module.set("__loader__", loader);
        module.set("__spec__", spec);
        module.set("__file__", Slots.getAttr(spec, "origin"));
        module.set("__cached__", Slots.getAttr(spec, "cached"));
        module.set("Template", PyType.TEMPLATE);
        module.set("Interpolation", PyType.INTERPOLATION);
        module.set("convert", new PyNativeFunction("convert", PyRuntime::templateConvert)
                .documented("Convert *obj* using formatted string literal semantics."));
        module.set("_template_unpickle",
                new PyNativeFunction("_template_unpickle", PyRuntime::templateUnpickle));
        return namespace(module);
    }

    /**
     * {@code convert(obj, conversion, /)}: the {@code !s}, {@code !r} and {@code !a} applied by hand.
     *
     * <p>The argument errors are a Python function's rather than a clinic's, because the reference
     * writes this one in {@code string/templatelib.py}; the shapes below are the ones its binder gives.
     */
    private static PyObject templateConvert(PyObject[] args, String[] kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = Arguments.positional(args, kwNames);
        boolean objByKeyword = false;
        boolean conversionByKeyword = false;
        PyObject conversionValue = null;
        for (int i = 0; i < keywords; i++) {
            if (kwNames[i].equals("obj")) {
                objByKeyword = true;
            } else if (kwNames[i].equals("conversion")) {
                conversionByKeyword = true;
                conversionValue = args[positional + i];
            } else {
                throw Slots.typeError(
                        "convert() got an unexpected keyword argument '%s'", kwNames[i]);
            }
        }
        if (objByKeyword) {
            throw Slots.typeError(
                    "convert() got some positional-only arguments passed as keyword arguments: 'obj'");
        }
        if (positional > 2) {
            throw Slots.typeError(
                    "convert() takes 2 positional arguments but %d were given", positional);
        }
        if (positional == 2 && conversionByKeyword) {
            throw Slots.typeError("convert() got multiple values for argument 'conversion'");
        }
        if (positional == 0 && !conversionByKeyword) {
            throw Slots.typeError(
                    "convert() missing 2 required positional arguments: 'obj' and 'conversion'");
        }
        if (positional == 0) {
            throw Slots.typeError("convert() missing 1 required positional argument: 'obj'");
        }
        if (positional == 1 && !conversionByKeyword) {
            throw Slots.typeError("convert() missing 1 required positional argument: 'conversion'");
        }
        PyObject obj = args[0];
        PyObject conversion = positional == 2 ? args[1] : conversionValue;
        if (conversion == PyNone.INSTANCE) {
            return obj;
        }
        if (conversion instanceof PyStr spec) {
            switch (spec.getValue()) {
                case "s":
                    return PyStr.of(Slots.str(obj));
                case "r":
                    return PyStr.of(Slots.repr(obj));
                case "a":
                    return PyStr.of(Percent.ascii(Slots.repr(obj)));
                default:
                    break;
            }
        }
        throw Slots.valueError("invalid conversion specifier: %s", Slots.str(conversion));
    }

    /**
     * {@code _template_unpickle(*args)}: the halves a {@code Template.__reduce__} handed out, zipped
     * back into constructor arguments.
     *
     * <p>It is a Python function in the reference, so a wrong number of arguments is its own
     * {@code ValueError}; each half is any iterable, and the shorter one is padded with nothing.
     */
    private static PyObject templateUnpickle(PyObject[] args, String[] kwNames) {
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError(
                    "_template_unpickle() got an unexpected keyword argument '%s'", kwNames[0]);
        }
        if (args.length != 2) {
            throw Slots.valueError("Template expects tuple of length 2 to unpickle");
        }
        PyIterator strings = Slots.iterator(args[0]);
        PyIterator interpolations = Slots.iterator(args[1]);
        ArrayList<PyObject> parts = new ArrayList<>();
        while (true) {
            PyObject text = strings.nextOrNull();
            PyObject interpolation = interpolations.nextOrNull();
            if (text == null && interpolation == null) {
                break;
            }
            if (text != null) {
                parts.add(text);
            }
            if (interpolation != null) {
                parts.add(interpolation);
            }
        }
        return PyTemplate.merged(parts.toArray(new PyObject[0]));
    }

    /**
     * {@code PyModule_AddFunctions}: the module a function's dictionary is becomes the function's owner,
     * and its name the value {@code __module__} reads.
     *
     * <p>Run here rather than as each function was registered, because this runtime writes the built-in
     * table before the dictionary it lives in is wrapped in the module that owns it. Both fields show
     * through reads and nowhere through a call: {@code all.__self__} is the module and {@code all.__module__}
     * is {@code 'builtins'}, while {@code all}'s one-argument body never sees either, which is what
     * {@code ($module, iterable, /)} of its own argument table is recording.
     */
    private static void belongTo(PyModule home) {
        for (PyObject entry : home.dictionary().getItems().values()) {
            if (entry instanceof PyNativeFunction function) {
                function.belongingTo(home);
            }
        }
    }

    /** The dictionary a module frame falls back to for a name it does not define. */
    public static PyDict builtins() {
        startup();
        return builtins;
    }

    /**
     * {@code _PyEval_GetBuiltin} for one of the names CPython writes as {@code &_Py_ID(name)}.
     *
     * <p>Read at the call rather than held from here, which is what lets a program rebind {@code iter} and
     * have a cursor's {@code __reduce__} name the replacement — {@code listiter_reduce_general} calls this
     * before it touches the iterator's own pointers for exactly that reason (issue {@code #101765}). A name
     * that is not in the dictionary is refused the way the C function refuses it: an {@code AttributeError}
     * carrying the name and nothing else, so {@code str(exc)} is the name and {@code exc.name} is
     * {@code None}, because {@code _PyEval_GetBuiltin} writes that error only when the lookup left none.
     */
    static PyObject builtin(String name) {
        PyObject callable = builtins().get(PyStr.interned(name));
        if (callable == null) {
            throw Slots.attributeError("%s", name);
        }
        return callable;
    }

    public static PyStream stdout() {
        startup();
        return stdout;
    }

    public static PyStream stderr() {
        startup();
        return stderr;
    }

    public static PyStream stdin() {
        startup();
        return stdin;
    }

    /**
     * {@code import name}.
     *
     * <p>The answer, in the order the reference asks it: a module already in {@code sys.modules},
     * then {@code sys.meta_path} — each finder's {@code find_spec}, and the first spec that comes back
     * is loaded — and only then whatever a milestone installed through {@link #addModuleFinder}. A name
     * no finder owns is reported as missing rather than answered with an empty module.
     *
     * <p>{@code sys.meta_path} is read on every import rather than captured at startup, because a
     * program is allowed to put a finder of its own there and expects it to be consulted.
     */
    public static PyObject importModule(String dotted) {
        startup();
        PyObject module = modules.get(PyStr.interned(dotted));
        if (module != null) {
            return cached(module, dotted);
        }
        // `_ast` is a built-in that cannot be built at startup: its node classes are written in Python
        // and need the injected compiler, which is handed in after `startup()` runs. The first import
        // builds it, and every later one reads the mapping like any other module.
        if (dotted.equals("_ast")) {
            return cached(AstModule.install(), dotted);
        }
        int dot = dotted.lastIndexOf('.');
        if (dot <= 0) {
            return find(dotted, PyNone.INSTANCE);
        }
        // A dotted name is found from the outside in: the parent is imported first, and only then is the
        // child looked for in the parent's `__path__`. That order is what makes `pkg` answer for itself
        // when `pkg.sub` is what was asked for, and what makes the parent's own code see a child it
        // imported itself rather than a second copy.
        String parentName = dotted.substring(0, dot);
        String tail = dotted.substring(dot + 1);
        PyObject parent = importModule(parentName);
        // Crazy side-effects: the parent may have run the child's import while it was being loaded, so
        // the mapping is asked again before anything is found.
        PyObject loaded = modules.get(PyStr.interned(dotted));
        if (loaded != null) {
            return cached(loaded, dotted);
        }
        PyObject path = Slots.findAttr(parent, "__path__");
        if (path == null) {
            throw missingModule(
                    "No module named '" + dotted + "'; '" + parentName + "' is not a package",
                    dotted);
        }
        PyObject child = find(dotted, path);
        // The name is bound on the package as an attribute, which is what `import a.b` then reads and
        // what makes `a.b` reachable without a second import.
        Slots.setAttr(parent, tail, child);
        return child;
    }

    /**
     * What {@code sys.modules} answered, or the complaint for a module a program filed as {@code None}.
     *
     * <p>A {@code None} entry is how the reference blocks an import without removing the name, so the
     * answer is a failure rather than the value.
     */
    private static PyObject cached(PyObject module, String dotted) {
        if (module instanceof PyNone) {
            throw missingModule("import of " + dotted + " halted; None in sys.modules", dotted);
        }
        return module;
    }

    /**
     * The search itself: every finder on {@code sys.meta_path} in turn, then the ones a milestone
     * installed, and a sentence naming the module when none of them answers.
     *
     * @param path the parent package's {@code __path__} for a dotted name, {@code None} for a top-level one
     */
    private static PyObject find(String dotted, PyObject path) {
        PyObject metaPath = sysAttribute("meta_path");
        long findersInPath = Numbers.size(Slots.length(metaPath));
        for (long i = 0; i < findersInPath; i++) {
            PyObject finder = metaPath.getItem(PyInt.of(i));
            PyObject found = Slots.call(
                    Slots.getAttr(finder, "find_spec"),
                    new PyObject[] {PyStr.of(dotted), path, PyNone.INSTANCE},
                    null);
            if (found instanceof PyModuleSpec spec) {
                return load(spec);
            }
        }
        for (ModuleFinder finder : finders) {
            PyObject found = finder.find(dotted);
            if (found != null) {
                // Cached like any other module, because a second import must be the same object:
                // a package holds its loaded classes, and two copies would hold two states.
                modules.put(PyStr.interned(dotted), found);
                return found;
            }
        }
        PyModule rebuilt = rebuild(dotted);
        if (rebuilt != null) {
            modules.put(PyStr.interned(dotted), rebuilt);
            return rebuilt;
        }
        throw missingModule("No module named '" + dotted + "'", dotted);
    }

    /**
     * {@code _find_spec}: what the finders answer for a name, without loading anything.
     *
     * <p>The walk is {@link #find}'s, and the difference is what the caller does with the answer: this
     * one is handed back to Python, so a name nothing owns is {@code null} here rather than a raised
     * error. A name already in {@code sys.modules} keeps the spec it was loaded from — that spec is
     * the only one {@code reload} may reuse.
     *
     * @param path the parent package's {@code __path__} for a dotted name, {@code None} for a top-level one
     * @param target the module being reloaded, or {@code null} when nothing is being reloaded
     */
    static PyModuleSpec findSpec(String dotted, PyObject path, PyObject target) {
        PyObject metaPath = sysAttribute("meta_path");
        long findersInPath = Numbers.size(Slots.length(metaPath));
        boolean isReload = existingModule(dotted) != null;
        for (long i = 0; i < findersInPath; i++) {
            PyObject finder = metaPath.getItem(PyInt.of(i));
            PyObject found = Slots.call(
                    Slots.getAttr(finder, "find_spec"),
                    new PyObject[] {
                        PyStr.of(dotted), path, target == null ? PyNone.INSTANCE : target
                    },
                    null);
            if (found instanceof PyModuleSpec spec) {
                if (!isReload) {
                    PyObject loaded = existingModule(dotted);
                    if (loaded != null) {
                        PyObject filed = Slots.findAttr(loaded, "__spec__");
                        if (filed instanceof PyModuleSpec existing) {
                            return existing;
                        }
                    }
                }
                return spec;
            }
        }
        PyModule rebuilt = rebuild(dotted);
        if (rebuilt == null) {
            return null;
        }
        PyObject spec = rebuilt.get("__spec__");
        return spec instanceof PyModuleSpec answer ? answer : null;
    }

    /**
     * Build again a module this runtime answers for itself, by name, or {@code null}.
     *
     * <p>These modules are filed at startup, so an ordinary import finds them in {@code sys.modules}
     * and never asks a finder. Once a program deletes one, the finder walk has no file to find — the
     * origin in each spec names a module this runtime builds, not one it reads — so the answer comes
     * from the same place the module first came from. CPython's own finders do this for its frozen and
     * built-in modules. {@code sys} and {@code builtins} are deliberately not here: a second import of
     * those answers a half-initialized shell in the reference, which comes from initialization rather
     * than from a finder, and imitating it would leave a program with a {@code sys} it cannot use.
     */
    private static PyModule rebuild(String dotted) {
        switch (dotted) {
            case "string":
                return buildStringPackage();
            case "functools":
                return FunctoolsModule.newModule();
            case "string.templatelib":
                return buildTemplateLib();
            case "annotationlib":
                return AnnotationLib.newModule();
            case "importlib":
                return Importlib.newModule();
            case "importlib.util":
                return Importlib.newUtil();
            case "importlib.machinery":
                return Importlib.newMachinery();
            case "zipimport":
                return ZipImport.newModule();
            default:
                return null;
        }
    }

    /** A {@code ModuleNotFoundError} that names the module a program can read back from {@code e.name}. */
    private static PyRaise missingModule(String message, String name) {
        PyBaseException error = (PyBaseException) Slots.call(
                ExceptionTypes.MODULE_NOT_FOUND_ERROR,
                new PyObject[] {PyStr.of(message), PyStr.of(name)},
                PyBoundMethod.keywordTuple(new String[] {"name"}));
        return PyRaise.of(error);
    }

    /**
     * {@code _load_unlocked}: build the module from the spec, file it, fill in the names every module
     * carries, and run it.
     *
     * <p>The module is filed before it runs, which is what makes a module that imports itself answer
     * rather than recurse. The attribute order is the reference's — {@code __package__},
     * {@code __loader__}, {@code __spec__}, then the optional {@code __path__}, {@code __file__} and
     * {@code __cached__} — because {@code dir()} and {@code __dict__} show it.
     */
    private static PyObject load(PyModuleSpec spec) {
        String name = ((PyStr) Slots.getAttr(spec, "name")).getValue();
        PyObject loader = Slots.getAttr(spec, "loader");
        if (loader instanceof PyNone) {
            // A spec with no loader but with search locations is a namespace package, and the
            // reference gives it a NamespaceLoader of its own before `create_module` is asked.
            PyObject locations = Slots.getAttr(spec, "submodule_search_locations");
            if (!(locations instanceof PyNone)) {
                loader = new PyNamespaceLoader(locations);
                spec.setAttr("loader", loader);
            }
        }
        PyObject created = Slots.call(
                Slots.getAttr(loader, "create_module"), new PyObject[] {spec}, null);
        PyModule module = created instanceof PyModule made ? made : new PyModule(name);
        modules.put(PyStr.interned(name), module);
        initialiseAttributes(module, spec);
        try {
            Slots.call(Slots.getAttr(loader, "exec_module"), new PyObject[] {module}, null);
        } catch (PyRaise failure) {
            // `_load_unlocked` takes the name back out when the module's body raises, so that a failed
            // import leaves no half-built module for the next one to find. A package whose child failed
            // keeps its own entry: only the name being loaded is removed.
            modules.remove(PyStr.interned(name));
            throw failure;
        }
        return module;
    }

    /** {@code _init_module_attrs}: the six names a freshly built module takes from its spec. */
    static void initialiseAttributes(PyModule module, PyModuleSpec spec) {
        if (module.get("__package__") == null) {
            module.set("__package__", Slots.getAttr(spec, "parent"));
        }
        if (module.get("__loader__") == null) {
            module.set("__loader__", Slots.getAttr(spec, "loader"));
        }
        if (module.get("__spec__") == null) {
            module.set("__spec__", spec);
        }
        PyObject locations = Slots.getAttr(spec, "submodule_search_locations");
        if (module.get("__path__") == null && !(locations instanceof PyNone)) {
            // The list the spec holds, not a copy: `module.__path__ is spec.submodule_search_locations`
            // is true in the reference, and a package that appends to one must move the other.
            module.set("__path__", locations);
        }
        if (Slots.truth(Slots.getAttr(spec, "has_location"))) {
            if (module.get("__file__") == null) {
                module.set("__file__", Slots.getAttr(spec, "origin"));
            }
            PyObject cached = Slots.getAttr(spec, "cached");
            if (module.get("__cached__") == null && !(cached instanceof PyNone)) {
                module.set("__cached__", cached);
            }
        }
        if (Slots.getAttr(spec, "loader") instanceof PyNamespaceLoader
                && module.get("__file__") == null) {
            // A namespace package has no file, but the reference still binds the name, to None.
            module.set("__file__", PyNone.INSTANCE);
        }
    }

    /**
     * The module already filed under {@code dotted}, or {@code null}.
     *
     * <p>{@code get_annotations(..., eval_str=True)} reads a class's globals through {@code sys.modules},
     * and a name that was never imported must not be created by that read.
     */
    static PyObject existingModule(String dotted) {
        startup();
        return modules.get(PyStr.interned(dotted));
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
            // `__main__` is the one module CPython gives the builtins module itself for
            // `__builtins__`; every other module carries the builtins dictionary.
            main.set("__builtins__", modules.get(PyStr.interned("builtins")));
            modules.put(PyStr.interned("__main__"), main);
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
     * stays {@code None} when it has none, and {@code __loader__} is the placeholder a module has
     * before anything says where it came from. The launcher fills that last one in for
     * {@code __main__} through {@link #execMain}, which is where the three ways a program can be
     * named start to differ.
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
     * Run the top-level code of the program a launcher was handed, in {@code __main__}.
     *
     * <p>The three ways CPython can be named differ only in what {@code __main__} says about itself,
     * and the difference is made here: a script has its own path in {@code __file__} and a
     * {@code SourceFileLoader} over that path; standard input has {@code '<stdin>'} there and the
     * built-in importer in the loader slot; a {@code -c} command has neither {@code __file__} nor
     * {@code __cached__}, because it has no file at all. {@code __spec__} is {@code None} in all
     * three — the top level is run, not imported — and {@code __cached__}, wherever it exists, is
     * {@code None} because nothing is written beside the source.
     *
     * @param fileName the script's resolved path, {@code "<stdin>"} for standard input, or null for
     *     {@code -c}
     */
    public static void execMain(PyCode code, String fileName) {
        PyModule module = mainModule();
        namespace(module);
        if (fileName == null) {
            module.set("__loader__", BuiltinImporter.type());
            module.dictionary().remove(PyStr.interned("__file__"));
            module.dictionary().remove(PyStr.interned("__cached__"));
        } else {
            module.set("__file__", PyStr.of(fileName));
            module.set("__cached__", PyNone.INSTANCE);
            module.set("__loader__", fileName.equals("<stdin>")
                    ? BuiltinImporter.type()
                    : new PySourceFileLoader("__main__", fileName));
        }
        Interpreter.run(frame(code, module));
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

    /**
     * The directories this process searches for imports, in order.
     *
     * <p>The launcher calls this before {@link #startup()}: the first entry is the script's directory
     * (or the empty string, which means the working directory, for {@code -c} and stdin), and
     * {@code PYTHONPATH} follows. A later call is honoured too, because an embedder may have its own
     * idea of where modules live.
     */
    public static void setSysPath(List<String> directories) {
        sysPath = List.copyOf(directories);
        if (sysModule != null) {
            sysModule.set("path", pathList());
        }
    }

    private static PyList pathList() {
        PyObject[] entries = new PyObject[sysPath.size()];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = PyStr.of(sysPath.get(i));
        }
        return PyList.of(entries);
    }

    /**
     * Install the compiler that turns an imported file's text into code.
     *
     * <p>Package-private in spirit but public in fact because the embedding module is another package;
     * it is the one upward edge {@code runtime} is allowed, and it is a value handed down, not a type
     * depended on.
     */
    public static void setSourceCompiler(SourceCompiler compiler) {
        sourceCompiler = compiler;
    }

    /** Whether a compiler has been handed in, which {@code _ast} needs to build its node classes. */
    static boolean hasSourceCompiler() {
        return sourceCompiler != null;
    }

    /**
     * File a module this runtime built for itself, so later imports answer from the mapping.
     *
     * <p>{@code _ast} is built on first import rather than at startup, and this is where that build
     * lands: one write, the same one {@code startup()} makes for every module it builds eagerly.
     */
    static void registerBuiltInModule(String name, PyModule module) {
        modules.put(PyStr.interned(name), module);
    }

    /**
     * Parse {@code source} into the tree of {@code _ast} nodes, rebuilding it on this side of the seam.
     *
     * <p>The compiler answers a constructor expression — {@code Module(body=[Assign(...)])} — written
     * against the node classes this runtime defines. Compiling and running it here is what makes the
     * objects runtime-owned: nothing in {@code runtime} knows how the reference parser lays out a
     * tree, and nothing in the reference interpreter knows what these node objects are.
     *
     * @param mode the {@code ast.parse} mode: {@code exec}, {@code eval}, {@code single} or
     *     {@code func_type}
     */
    static PyObject parseAst(String source, String fileName, String mode) {
        if (sourceCompiler == null) {
            throw Slots.notImplemented("ast needs a compiler (setSourceCompiler)");
        }
        String text = sourceCompiler.parseAst(source, fileName, mode);
        PyModule ast = (PyModule) importModule("_ast");
        PyDict globals = ast.dictionary();
        return runIn(compileExpression(text, "<ast>"), globals, globals);
    }

    /** Compile the text of an imported file with the installed compiler. */
    static PyCode compileSource(String source, String fileName) {
        if (sourceCompiler == null) {
            throw Slots.notImplemented(
                    "importing a source file needs a compiler (setSourceCompiler)");
        }
        return sourceCompiler.compile(source, fileName);
    }

    /** Compile one expression with the installed compiler, which is what {@code eval()} runs. */
    static PyCode compileExpression(String source, String fileName) {
        if (sourceCompiler == null) {
            throw Slots.notImplemented("eval() needs a compiler (setSourceCompiler)");
        }
        return sourceCompiler.compileExpression(source, fileName);
    }

    /**
     * Run a code object in the pair of namespaces a caller supplied, as {@code exec()} and {@code eval()}
     * do.
     *
     * <p>Only an {@code eval}-mode code object returns a value; a module's and a {@code single} one's
     * answer is {@code None} by construction.
     */
    static PyObject runIn(PyCode code, PyDict globals, PyDict locals) {
        return Interpreter.run(new Frame(code, globals, locals, new PyObject[code.localCount()]));
    }

    /**
     * Run the callbacks {@code atexit.register} collected, newest first.
     *
     * <p>This runtime has no separate finalization step, so the launcher calls this at the one moment
     * there is for it, just before the process exits — which is also the moment {@code logging} expects
     * its handlers to be flushed.
     */
    public static void runExitFunctions() {
        AtexitModule.runExitFunctions();
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

    /**
     * {@code sys.flags}: the interpreter's start-up switches.
     *
     * <p>Only {@code context_aware_warnings} and {@code ignore_environment} are answered, because they
     * are the only members this tree reads: {@code _py_warnings} asks the first once at module scope to
     * decide whether {@code catch_warnings} keeps its filters in a context variable or in the global
     * list, and {@code _colorize.can_colorize} asks the second before it looks at
     * {@code PYTHON_COLORS}. Both values are 0 — the reference's without {@code -X
     * context_aware_warnings} and without {@code -E} respectively — so the global filter list and the
     * environment are the paths taken. The rest of the reference's members — {@code verbose},
     * {@code utf8_mode} and the others — are not answered yet, and the milestone notes name the gap.
     */
    private static PyNamespace flags() {
        PyNamespace namespace = new PyNamespace();
        namespace.set("context_aware_warnings", PyInt.of(0));
        namespace.set("ignore_environment", PyInt.of(0));
        return namespace;
    }

    /**
     * {@code sys.hash_info}: what the hash functions are built on.
     *
     * <p>The numeric members are this runtime's actual constants — {@link PyHash#MODULUS},
     * {@link PyHash#INF}, {@link PyHash#IMAG}, and 0 for NaN — so they answer the same questions the
     * reference's do. {@code algorithm} names what {@link PyHash} really does rather than the
     * reference's {@code siphash13}: text hashing here is a deterministic FNV-1a with no per-process
     * seed, which is why {@code seed_bits} is 0 and {@code cutoff} is 0 as well.
     */
    private static PyNamespace hashInfo() {
        PyNamespace namespace = new PyNamespace();
        namespace.set("width", PyInt.of(64));
        namespace.set("modulus", PyInt.of(PyHash.MODULUS.longValue()));
        namespace.set("inf", PyInt.of(PyHash.INF));
        namespace.set("nan", PyInt.of(0));
        namespace.set("imag", PyInt.of(PyHash.IMAG));
        namespace.set("algorithm", PyStr.of("fnv-1a"));
        namespace.set("hash_bits", PyInt.of(64));
        namespace.set("seed_bits", PyInt.of(0));
        namespace.set("cutoff", PyInt.of(0));
        return namespace;
    }

    /**
     * {@code sys._jit}: the observation point for a just-in-time compiler.
     *
     * <p>This runtime has no JIT, so all three answers are False — which is what the reference answers
     * for a build without one, and what {@code test.support} reads once at module scope to decide
     * whether the JIT-only tests run.
     */
    private static PyModule jitModule() {
        PyModule module = new PyModule("sys._jit");
        module.set("__doc__", PyStr.of("Utilities for observing just-in-time compilation."));
        module.set("__package__", PyNone.INSTANCE);
        module.set("__loader__", PyNone.INSTANCE);
        module.set("__spec__", PyNone.INSTANCE);
        module.set(
                "is_active",
                PyNativeFunction.positional("is_active", args -> {
                    Arguments.none("is_active()", args, null);
                    return PyBool.FALSE;
                }).documented(
                        "is_active($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return True if the topmost Python frame is currently executing JIT code "
                                + "(implies sys._jit.is_enabled()), and False otherwise."));
        module.set(
                "is_available",
                PyNativeFunction.positional("is_available", args -> {
                    Arguments.none("is_available()", args, null);
                    return PyBool.FALSE;
                }).documented(
                        "is_available($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return True if the current Python executable supports JIT compilation, "
                                + "and False otherwise."));
        module.set(
                "is_enabled",
                PyNativeFunction.positional("is_enabled", args -> {
                    Arguments.none("is_enabled()", args, null);
                    return PyBool.FALSE;
                }).documented(
                        "is_enabled($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return True if JIT compilation is enabled for the current Python process "
                                + "(implies sys._jit.is_available()), and False otherwise."));
        return module;
    }

    /**
     * {@code sys.implementation}: the namespace a program reads this implementation's identity from.
     *
     * <p>The six names are CPython 3.14's and in its order, because the order is what {@code repr} prints.
     * {@code name} and {@code cache_tag} claim CPython 3.14 on purpose: that is the behaviour this
     * runtime reproduces and what the standard library branches on. {@code version} is the same object
     * {@code sys.version_info} is, so the identity a program can rely on holds here as it does there —
     * the numbers themselves are this runtime's, which is why the two differ from the reference's build.
     *
     * <p>{@code supports_isolated_interpreters} is false: there are no sub-interpreters here to isolate,
     * and saying true would invite a program into a feature this runtime does not have. That is the one
     * value that differs from the reference on purpose; the milestone notes name it.
     */
    private static PyNamespace implementation() {
        PyNamespace namespace = new PyNamespace();
        namespace.set("name", PyStr.of("cpython"));
        namespace.set("cache_tag", PyStr.of("cpython-" + VERSION_MAJOR + VERSION_MINOR));
        namespace.set("version", PyVersionInfo.current());
        namespace.set("hexversion", PyInt.of(hexVersion()));
        namespace.set("_multiarch", PyStr.of(multiarch()));
        namespace.set("supports_isolated_interpreters", PyBool.FALSE);
        return namespace;
    }

    /**
     * {@code sys.implementation._multiarch}: the platform spelling CPython's build records.
     *
     * <p>Only the macOS value is reproduced, because that is the platform this runtime is measured on;
     * elsewhere the name is empty rather than a guess, and the milestone notes name the gap.
     */
    private static String multiarch() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.startsWith("mac") ? "darwin" : "";
    }

    /**
     * {@code sys.platform}: the short spelling CPython records for the host, which is not the JVM's
     * {@code os.name} — macOS is {@code mac os x} there and {@code darwin} here.
     *
     * <p>Only the three platforms this runtime is measured on are spelled out; elsewhere the JVM's own
     * name is the honest answer rather than a guess.
     */
    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.startsWith("mac")) {
            return "darwin";
        }
        if (os.startsWith("windows")) {
            return "win32";
        }
        if (os.startsWith("linux")) {
            return "linux";
        }
        return os;
    }

    private static PyModule buildSys() {
        PyModule sys = new PyModule("sys");
        // COPY_SYS_ATTR gives the three standard files a second name each, and the second name is the
        // same object: `sys.stdin is sys.__stdin__` reads true at startup and keeps reading true of the
        // second name after a program has replaced the first.
        sys.set("stdin", stdin);
        sys.set("__stdin__", stdin);
        sys.set("stdout", stdout);
        sys.set("__stdout__", stdout);
        sys.set("stderr", stderr);
        sys.set("__stderr__", stderr);
        sys.set("platform", PyStr.of(platform()));
        if (platform().equals("darwin")) {
            // A macOS framework build records its framework's name here. This runtime is not one, and
            // `sysconfig` only reads the name to decide whether it is truthy before choosing where the
            // per-user packages live, so the empty string is the honest answer and the `.local` road.
            sys.set("_framework", PyStr.of(""));
        }
        sys.set("maxsize", PyInt.of(Long.MAX_VALUE));
        sys.set("maxunicode", PyInt.of(0x10FFFF));
        sys.set("hexversion", PyInt.of(hexVersion()));
        sys.set("api_version", PyInt.of(1013));
        sys.set("byteorder", PyStr.of("little"));
        // This runtime builds no ABI flags of its own; `sysconfig` splices the name into the
        // `_sysconfigdata_*` module it looks for, and the reference's macOS build spells it empty.
        sys.set("abiflags", PyStr.of(""));
        sys.set("version", PyStr.of(pythonVersion() + " (Jython3 on the JVM)"));
        sys.set("executable", PyStr.of(System.getProperty("jy3.executable", "jy3")));
        sys.set("prefix", PyStr.of(System.getProperty("jy3.prefix", "")));
        sys.set("base_prefix", PyStr.of(System.getProperty("jy3.prefix", "")));
        // A normal installation keeps both pairs equal, and `sysconfig` reads all four at module scope.
        sys.set("exec_prefix", PyStr.of(System.getProperty("jy3.prefix", "")));
        sys.set("base_exec_prefix", PyStr.of(System.getProperty("jy3.prefix", "")));
        sys.set("platlibdir", PyStr.of("lib"));
        sys.set("version_info", PyVersionInfo.current());
        sys.set("hash_info", hashInfo());
        sys.set("_jit", jitModule());
        sys.set("implementation", implementation());
        // `_py_warnings` reads both at module scope: the flags to choose between the context-variable
        // and the global filter list, and the option list to replay `-W` switches.
        sys.set("flags", flags());
        sys.set("warnoptions", PyList.of());
        sys.set("argv", PyList.of(PyStr.of("jy3")));
        sys.set("path", pathList());
        // The reference's meta_path has three entries; this runtime has no frozen importer because it
        // has no frozen modules, and names that omission in the milestone notes. The other two are the
        // order the reference searches them in, which is what `import` walks below.
        sys.set("meta_path", PyList.of(BuiltinImporter.type(), PathFinder.type()));
        // The reference's two hooks, in its order: zip archives first, then directories. A zip file
        // is only refused for now, so every real entry falls through to the directory hook.
        sys.set("path_hooks", PyList.of(PyType.ZIP_IMPORTER, PyFileFinder.defaultHook()));
        sys.set("path_importer_cache", PyDict.empty());
        sys.set("modules", modules);
        sys.set("builtin_module_names",
                PyTuple.of(
                        PyStr.of("builtins"),
                        PyStr.of("sys"),
                        PyStr.of("_ast"),
                        PyStr.of("_io"),
                        PyStr.of("_tokenize"),
                        PyStr.of("_opcode"),
                        PyStr.of("_signal"),
                        PyStr.of("_string"),
                        PyStr.of("_types"),
                        PyStr.of("_weakref"),
                        PyStr.of("posix"),
                        PyStr.of("_collections"),
                        PyStr.of("gc"),
                        PyStr.of("atexit"),
                        PyStr.of("_imp"),
                        PyStr.of("_random"),
                        PyStr.of("unicodedata"),
                        PyStr.of("resource"),
                        PyStr.of("_posixsubprocess"),
                        PyStr.of("select"),
                        PyStr.of("_typing")));
        sys.set("defaultencoding", PyStr.of("utf-8"));
        // `bdb.py` reads `sys.monitoring.events` at module scope and registers itself as a tool when the
        // attribute exists, so a debugger cannot be imported without it.
        sys.set("monitoring", Monitoring.newModule());
        // `subprocess` audits every spawn before it forks. This runtime installs no hooks, so the call
        // is the no-op the reference performs when none are installed — but the name has to exist.
        sys.set("audit", new PyNativeFunction(
                        "audit",
                        (args, kwNames) -> {
                            Arguments.noKeywords("sys.audit()", kwNames);
                            if (args.length < 1) {
                                throw Slots.typeError(
                                        "audit() takes at least 1 argument (0 given)");
                            }
                            return PyNone.INSTANCE;
                        })
                .documented("audit(event, /, *args)\n--\n\n"
                        + "Passes the event and arguments to all audit hooks."));
        sys.set("getdefaultencoding", noArguments(
                "getdefaultencoding", () -> PyStr.of("utf-8"),
                "getdefaultencoding($module, /)\n"
                        + "--\n"
                        + "\n"
                        + "Return the current default encoding used by the Unicode implementation."));
        // `os` builds `fsencode`/`fsdecode` out of these two at import time, so both must answer before
        // the first `import os` runs: names this runtime hands to Java are UTF-8, and the reference's
        // escape hatch for bytes that are not is the same handler.
        sys.set("getfilesystemencoding", noArguments(
                "getfilesystemencoding", () -> PyStr.of("utf-8"),
                "getfilesystemencoding($module, /)\n"
                        + "--\n"
                        + "\n"
                        + "Return the encoding used to convert Unicode filenames to OS filenames."));
        sys.set("getfilesystemencodeerrors", noArguments(
                "getfilesystemencodeerrors", () -> PyStr.of("surrogateescape"),
                "getfilesystemencodeerrors($module, /)\n"
                        + "--\n"
                        + "\n"
                        + "Return the error mode used to convert Unicode filenames to OS filenames."));
        // `collections.namedtuple` interns the typename before it builds the class, and `tokenize.py`
        // calls it at module scope, so the name has to answer before `unittest` can import.
        sys.set("intern", new PyNativeFunction(
                        "intern",
                        (args, kwNames) -> {
                            Arguments.exactlyOne("intern", args, kwNames);
                            if (!(args[0] instanceof PyStr text)) {
                                throw Slots.typeError("intern() argument must be str, not %s",
                                        args[0].getType().filedName());
                            }
                            return PyStr.interned(text.getValue());
                        })
                .documented("intern($module, string, /)\n"
                        + "--\n"
                        + "\n"
                        + "\"\"Intern'' the given string.\n"
                        + "\n"
                        + "This enters the string in the (global) table of interned strings whose\n"
                        + "purpose is to speed up dictionary lookups."));
        // `inspect.unwrap` reads this while `dataclasses` builds a class, which is the first thing
        // `unittest` imports that asks. The interpreter's stack is Java's, so the number is bookkeeping:
        // a program that raises it may still overflow where the reference would not, and the milestone
        // notes say so.
        sys.set("getrecursionlimit", noArguments(
                "getrecursionlimit", () -> PyInt.of(recursionLimit),
                "getrecursionlimit($module, /)\n"
                        + "--\n"
                        + "\n"
                        + "Return the current value of the recursion limit.\n"
                        + "\n"
                        + "The recursion limit is the maximum depth of the Python interpreter\n"
                        + "stack.  This limit prevents infinite recursion from causing an overflow\n"
                        + "of the C stack and crashing Python."));
        sys.set("setrecursionlimit", new PyNativeFunction(
                        "setrecursionlimit",
                        (args, kwNames) -> {
                            Arguments.exactlyOne("setrecursionlimit", args, kwNames);
                            BigInteger asked = Numbers.asIndex(args[0]);
                            if (asked.bitLength() > 31) {
                                throw Slots.overflowError(
                                        "Python int too large to convert to C int");
                            }
                            if (asked.signum() <= 0) {
                                throw Slots.valueError(
                                        "recursion limit must be greater or equal than 1");
                            }
                            recursionLimit = asked.intValue();
                            return PyNone.INSTANCE;
                        })
                .documented("setrecursionlimit($module, limit, /)\n"
                        + "--\n"
                        + "\n"
                        + "Set the maximum depth of the Python interpreter stack to n.\n"
                        + "\n"
                        + "This limit prevents infinite recursion from causing an overflow of the C\n"
                        + "stack and crashing Python.  The highest possible limit is platform-\n"
                        + "dependent."));
        sys.set(
                "exc_info",
                noArguments(
                        "exc_info",
                        PyRuntime::excInfo,
                        "exc_info($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return current exception information: (type, value, traceback).\n"
                                + "\n"
                                + "Return information about the most recent exception caught by an except\n"
                                + "clause in the current stack frame or in an older stack frame."));
        sys.set(
                "exception",
                noArguments(
                        "exception",
                        PyRuntime::handledValue,
                        "exception($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the current exception.\n"
                                + "\n"
                                + "Return the exception currently being handled, or None if no exception\n"
                                + "is currently being handled."));
        sys.set(
                "_getframe",
                new PyNativeFunction("_getframe", PyRuntime::getframe)
                        .documented(
                                "_getframe($module, depth=0, /)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Return a frame object from the call stack.\n"
                                        + "\n"
                                        + "If optional integer depth is given, return the frame object"
                                        + " that many\n"
                                        + "calls below the top of the stack.  If that is deeper than the"
                                        + " call\n"
                                        + "stack, ValueError is raised.  The default for depth is zero,"
                                        + " returning\n"
                                        + "the frame at the top of the call stack.\n"
                                        + "\n"
                                        + "This function should be used for internal and specialized"
                                        + " purposes\n"
                                        + "only."));
        // COPY_SYS_ATTR files the original a second time under the doubled name, and files the very same
        // object: `sys.__displayhook__ is sys.displayhook` reads true at startup, and keeps reading true of
        // the second name after a program has replaced the first.
        PyObject displayhook =
                new PyNativeFunction("displayhook", PyRuntime::displayhook)
                        .documented(
                                "displayhook($module, object, /)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Print an object to sys.stdout and also save it in builtins._");
        sys.set("displayhook", displayhook);
        sys.set("__displayhook__", displayhook);
        sys.set(
                "exit",
                new PyNativeFunction("exit", PyRuntime::systemExit)
                        .documented(
                                "exit($module, status=None, /)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Exit the interpreter by raising SystemExit(status).\n"
                                        + "\n"
                                        + "If the status is omitted or None, it defaults to zero (i.e.,"
                                        + " success).\n"
                                        + "If the status is an integer, it will be used as the system exit"
                                        + " status.\n"
                                        + "If it is another kind of object, it will be printed and the"
                                        + " system\n"
                                        + "exit status will be one (i.e., failure)."));
        // COPY_SYS_ATTR gives the two names the one object, so `sys.excepthook is sys.__excepthook__`
        // reads true until a script replaces the first of them — and then keeps reading true of the
        // second, which still holds this object.
        PyObject excepthook =
                new PyNativeFunction("excepthook", PyRuntime::excepthook)
                        .documented(
                                "excepthook($module, exctype, value, traceback, /)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Handle an exception by displaying it with a traceback on"
                                        + " sys.stderr.");
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
     * {@code sys._getframe}: the frame {@code depth} calls above the one that asked for it.
     *
     * <p>The argument is a C {@code int} read through {@code _PyLong_AsInt}, which is why a {@code str} gets
     * the index refusal and {@code 2 ** 100} overflows; a keyword is refused by the call machinery before
     * the body, and the count says {@code _getframe expected at most 1 argument, got 2} — bare, without the
     * module qualifier. A negative depth is not an error in the reference: the walk only runs while depth is
     * positive, so the top frame comes back. Only running off the end of the chain is a {@code ValueError}.
     */
    private static PyObject getframe(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("sys._getframe()", kwNames);
        Arguments.atMost("_getframe", args, kwNames, 1);
        int depth = args.length == 0 ? 0 : Numbers.cInteger(args[0]);
        Frame frame = Interpreter.currentFrame();
        while (depth > 0 && frame != null) {
            frame = frame.back();
            depth--;
        }
        if (frame == null) {
            throw Slots.valueError("call stack is not deep enough");
        }
        return frame.frameObject();
    }

    /**
     * {@code sys.displayhook}: what the interpreter shows for the last expression, asked for by name.
     *
     * <p>{@code sys_displayhook} (Python/sysmodule.c:751) does five things, in an order a program can see.
     * It reads the builtins module first, before it looks at the value at all. {@code None} is not a result
     * worth showing, and a {@code None} leaves both the stream and the saved name untouched. Any other value
     * is saved as {@code None} first — the C comment gives the reason, "Before, set '_' to None to avoid
     * recursion", for a value whose own {@code __repr__} reads {@code _} back — and only then is {@code
     * sys.stdout} required: a stdout deleted, or replaced by {@code None}, is the same refusal rather than
     * silence. The repr and the newline are two separate calls to {@code write}, and the value is saved last,
     * which is why a value whose repr refuses is the one value {@code _} never gets.
     */
    private static PyObject displayhook(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("sys.displayhook()", args, kwNames);
        PyObject builtins = importModule("builtins");
        PyObject value = args[0];
        if (value == PyNone.INSTANCE) {
            return PyNone.INSTANCE;
        }
        Slots.setAttr(builtins, "_", PyNone.INSTANCE);
        PyObject stream = sysAttribute("stdout");
        if (stream == null || stream == PyNone.INSTANCE) {
            throw Slots.runtimeError("lost sys.stdout");
        }
        // `PyFile_WriteObject(o, outf, 0)` writes the repr — quoted, for a string — and the newline that
        // follows it is a second, separate call with the one character printed raw.
        write(stream, value.repr());
        write(stream, "\n");
        Slots.setAttr(builtins, "_", value);
        return PyNone.INSTANCE;
    }

    /** One {@code sys.stdout.write} of a line of text, reached through the stream's own member. */
    private static void write(PyObject stream, String text) {
        Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of(text)}, null);
    }

    /**
     * A {@code METH_NOARGS} member of {@code sys}, with the sentence its clinic row carries.
     *
     * <p>The calling convention refuses both kinds of argument before the body is entered, and names the
     * function as module and name while doing it: {@code sys.exc_info(1)} is "takes no arguments (1 given)"
     * and {@code sys.exc_info(x=1)} is "takes no keyword arguments". {@link
     * PyNativeFunction#positional} files only the second of those, and a row whose table is {@code
     * ($module, /)} has no room for the first either.
     */
    private static PyNativeFunction noArguments(
            String name, java.util.function.Supplier<PyObject> body, String internal) {
        return new PyNativeFunction(
                        name,
                        (args, kwNames) -> {
                            Arguments.none("sys." + name + "()", args, kwNames);
                            return body.get();
                        })
                .documented(internal);
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
     * {@code sys.exception()}: the value of the handled exception, or None.
     *
     * <p>That is the second item of {@link #excInfo()} and nothing else — the sentence CPython 3.11
     * added for the common case of wanting only the instance.
     */
    private static PyObject handledValue() {
        return handledException() instanceof PyBaseException error ? error : PyNone.INSTANCE;
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
        // `METH_FASTCALL` with no keyword flag: the calling machine refuses the named argument before
        // `sys_exit` ever runs `_PyArg_CheckPositional`, so `sys.exit(x=1)` never reaches the exit at all.
        Arguments.noKeywords("sys.exit()", kwNames);
        Arguments.atMost("exit", args, kwNames, 1);
        PyObject[] given = args.length == 0 || args[0] == PyNone.INSTANCE ? new PyObject[0] : args;
        throw PyRaise.of((PyBaseException) Slots.call(ExceptionTypes.SYSTEM_EXIT, given, null));
    }
}
