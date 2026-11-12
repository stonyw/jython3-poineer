package org.python.core;

import java.nio.file.Path;

/**
 * {@code importlib} and {@code importlib.util}: the two modules a program names when it imports by
 * string rather than by statement.
 *
 * <p>The statements are already answered by {@link Import} and {@link PyRuntime}; what is here is the
 * surface those modules put on top of the same machinery. {@code import_module} is the statement's
 * name argument with the relative-name rules; {@code util.find_spec} asks the finders without
 * loading; {@code spec_from_file_location} and {@code module_from_spec} build a module by hand; and
 * {@code reload} runs a module's body again in the namespace it already has.
 *
 * <p>Not here yet, because this milestone has no finder that would answer them: {@code
 * invalidate_caches} is a walk over {@code sys.meta_path} rather than a cache to clear, and the
 * bytecode loaders are unbuilt. Everything here answers for the {@code .py} files a script can write.
 */
final class Importlib {

    /** The names {@code importlib.__all__} publishes, in the reference's order. */
    private static final String[] ALL = {
        "__import__", "import_module", "invalidate_caches", "reload"
    };

    private Importlib() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("importlib");
        module.set("__doc__", PyStr.of("A pure Python implementation of import."));
        module.set("__package__", PyStr.of("importlib"));
        // A package is a name with a search path; this one's `__path__` is never searched, because
        // every submodule it has is built here rather than found on disk.
        PyList path = PyList.of(PyStr.of("importlib"));
        module.set("__path__", path);
        module.set("__loader__", BuiltinImporter.type());
        PyModuleSpec spec = PyModuleSpec.builtin("importlib", BuiltinImporter.type());
        // The reference's spec and `__path__` are one list, the same identity `_init_module_attrs`
        // gives every package.
        spec.setAttr("submodule_search_locations", path);
        module.set("__spec__", spec);
        PyObject[] names = new PyObject[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            names[i] = PyStr.of(ALL[i]);
        }
        module.set("__all__", PyList.of(names));
        module.set("__import__", new PyNativeFunction("__import__", Import::builtin)
                .documented("Import a module.\n\n"
                        + "The 'name' argument is the name of the module to import.  The module is\n"
                        + "loaded and returned."));
        module.set("import_module", new PyNativeFunction("import_module", Importlib::importModule)
                .documented("Import a module.\n\n"
                        + "The 'package' argument is required to perform a relative import for '.'."));
        module.set("invalidate_caches",
                new PyNativeFunction("invalidate_caches", Importlib::invalidateCaches)
                        .documented("Call invalidate_caches() on all meta_path finders."));
        module.set("reload", new PyNativeFunction("reload", Importlib::reload)
                .documented("Reload the module and return it.\n\n"
                        + "The module must have been successfully imported before."));
        return module;
    }

    /**
     * {@code importlib.machinery}: the names the import machinery is made of.
     *
     * <p>What is published here is the registry a program reads: the two finders beside {@code
     * PathFinder}, the loaders a spec can carry, the three suffix lists and the function that joins
     * them. The two debug suffix names are attributes here rather than module-level names reached
     * through {@code __getattr__}, which is the reference's spelling and a difference a program can
     * see in {@code vars()}; the milestone notes it.
     */
    static PyModule newMachinery() {
        PyModule module = new PyModule("importlib.machinery");
        module.set("__doc__", PyStr.of("Importers and path hooks"));
        module.set("__package__", PyStr.of("importlib"));
        module.set("__loader__", BuiltinImporter.type());
        module.set("__spec__", PyModuleSpec.builtin("importlib.machinery", BuiltinImporter.type()));
        module.set("__all__", names(
                "AppleFrameworkLoader", "BYTECODE_SUFFIXES", "BuiltinImporter",
                "DEBUG_BYTECODE_SUFFIXES", "EXTENSION_SUFFIXES", "ExtensionFileLoader",
                "FileFinder", "FrozenImporter", "ModuleSpec", "NamespaceLoader",
                "OPTIMIZED_BYTECODE_SUFFIXES", "PathFinder", "SOURCE_SUFFIXES",
                "SourceFileLoader", "SourcelessFileLoader", "WindowsRegistryFinder",
                "all_suffixes"));
        PyList sources = PyList.of(PyStr.of(".py"));
        PyList bytecode = PyList.of(PyStr.of(".pyc"));
        PyList extensions = PyList.of(
                PyStr.of(".cpython-314-darwin.so"), PyStr.of(".abi3.so"), PyStr.of(".so"));
        module.set("SOURCE_SUFFIXES", sources);
        module.set("BYTECODE_SUFFIXES", bytecode);
        module.set("DEBUG_BYTECODE_SUFFIXES", bytecode);
        module.set("OPTIMIZED_BYTECODE_SUFFIXES", bytecode);
        module.set("EXTENSION_SUFFIXES", extensions);
        module.set("all_suffixes", new PyNativeFunction("all_suffixes", (args, kwNames) -> {
            Arguments.unpackKeywords("all_suffixes", args, kwNames, new String[0], 0, 0);
            PyList joined = PyList.empty();
            for (PyObject suffix : sources.getItems()) {
                joined.add(suffix);
            }
            for (PyObject suffix : bytecode.getItems()) {
                joined.add(suffix);
            }
            for (PyObject suffix : extensions.getItems()) {
                joined.add(suffix);
            }
            return joined;
        }).documented("Return a list of all the suffixes used by the import machinery."));
        module.set("ModuleSpec", PyType.MODULE_SPEC);
        module.set("BuiltinImporter", BuiltinImporter.type());
        module.set("FrozenImporter", PyType.FROZEN_IMPORTER);
        module.set("PathFinder", PathFinder.type());
        module.set("FileFinder", PyType.FILE_FINDER);
        module.set("SourceFileLoader", PyType.SOURCE_FILE_LOADER);
        module.set("SourcelessFileLoader", PyType.SOURCELESS_FILE_LOADER);
        module.set("ExtensionFileLoader", PyType.EXTENSION_FILE_LOADER);
        module.set("AppleFrameworkLoader", PyType.APPLE_FRAMEWORK_LOADER);
        module.set("NamespaceLoader", PyType.NAMESPACE_LOADER);
        module.set("WindowsRegistryFinder", PyType.WINDOWS_REGISTRY_FINDER);
        return module;
    }

    /**
     * {@code importlib.util}: the helpers a program that builds imports by hand reaches for.
     *
     * <p>The five names the reference re-exports from {@code importlib._bootstrap_external} are the
     * same objects here, because {@code util.py} imports them rather than wrapping them: a program
     * that asks whether {@code importlib.util.cache_from_source is
     * importlib._bootstrap_external.cache_from_source} still gets {@code True}.
     */
    static PyModule newUtil(PyModule bootstrapExternal) {
        PyModule module = new PyModule("importlib.util");
        module.set("__doc__", PyStr.of("Utility code for constructing importers, etc."));
        module.set("__package__", PyStr.of("importlib"));
        module.set("__loader__", BuiltinImporter.type());
        module.set("__spec__", PyModuleSpec.builtin("importlib.util", BuiltinImporter.type()));
        module.set("find_spec", new PyNativeFunction("find_spec", Importlib::findSpec)
                .documented("Find the spec for a module, optionally relative to the specified\n"
                        + "package."));
        module.set("resolve_name",
                new PyNativeFunction("resolve_name", Importlib::resolveName)
                        .documented("Resolve a relative module name to an absolute one."));
        module.set("spec_from_file_location",
                new PyNativeFunction("spec_from_file_location", Importlib::specFromFileLocation)
                        .documented("Return a module spec for a file location."));
        module.set("module_from_spec",
                new PyNativeFunction("module_from_spec", Importlib::moduleFromSpec)
                        .documented("Create a module from a spec."));
        // The five names `importlib.util` re-exports from `_bootstrap_external` in the reference,
        // minus the two loaders and `spec_from_loader` this runtime has none of.
        module.set("MAGIC_NUMBER", bootstrapExternal.getAttr("MAGIC_NUMBER"));
        module.set("cache_from_source", bootstrapExternal.getAttr("cache_from_source"));
        module.set("source_from_cache", bootstrapExternal.getAttr("source_from_cache"));
        module.set("decode_source", bootstrapExternal.getAttr("decode_source"));
        module.set("source_hash", new PyNativeFunction("source_hash", (args, kwNames) -> {
            PyObject[] values = Arguments.unpackKeywords(
                    "source_hash", args, kwNames, new String[] {"source_bytes"}, 1, 1);
            if (!(values[0] instanceof PyBytes) && !(values[0] instanceof PyByteArray)) {
                throw Slots.typeError(
                        "source_hash() argument 1 must be read-only bytes-like object, not %s",
                        Slots.argumentName(values[0]));
            }
            return PyBytes.of(ImpModule.sourceHash(
                    ImpModule.PYC_MAGIC_NUMBER_TOKEN, PyBytes.octets(values[0])));
        }).documented("Return the hash of a source bytes-like object."));
        module.set("__all__", names(
                "MAGIC_NUMBER", "cache_from_source", "decode_source", "find_spec",
                "module_from_spec", "resolve_name", "source_from_cache", "source_hash",
                "spec_from_file_location"));
        return module;
    }

    // --- importlib -----------------------------------------------------------------------

    /**
     * {@code import_module(name, package=None)}: the import statement's name, with a relative name
     * resolved against the package the caller names.
     *
     * <p>A name that is not a string fails in the reference's own first step, {@code
     * name.startswith('.'')}, which is why the complaint names {@code startswith} rather than this
     * method's parameter.
     */
    private static PyObject importModule(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "import_module", args, kwNames, new String[] {"name", "package"}, 1, 2);
        String name = stringStartingWithDot(values[0], "startswith");
        PyObject packageValue = values[1];
        int level = 0;
        if (name.startsWith(".")) {
            if (packageValue == null || !Slots.truth(packageValue)) {
                throw Slots.typeError(
                        "the 'package' argument is required to perform a relative import for '%s'",
                        name);
            }
            while (level < name.length() && name.charAt(level) == '.') {
                level++;
            }
        }
        if (level == 0 && name.isEmpty()) {
            throw Slots.valueError("Empty module name");
        }
        if (level > 0 && !(packageValue instanceof PyStr)) {
            throw Slots.typeError("__package__ not set to a string");
        }
        String rest = name.substring(level);
        String absolute = level == 0
                ? rest
                : resolveFrom(rest, ((PyStr) packageValue).getValue(), level);
        return PyRuntime.importModule(absolute);
    }

    /** {@code invalidate_caches()}: every finder that keeps a cache is asked to forget it. */
    private static PyObject invalidateCaches(PyObject[] args, String[] kwNames) {
        Arguments.unpackKeywords(
                "invalidate_caches", args, kwNames, new String[0], 0, 0);
        PyObject metaPath = PyRuntime.sysAttribute("meta_path");
        long count = Numbers.size(Slots.length(metaPath));
        for (long i = 0; i < count; i++) {
            PyObject finder = metaPath.getItem(PyInt.of(i));
            PyObject method = Slots.findAttr(finder, "invalidate_caches");
            if (method != null) {
                Slots.call(method, new PyObject[0], null);
            }
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code reload(module)}: find the module's spec again and run its body in the namespace it
     * already has.
     *
     * <p>The name is taken from the module rather than from the caller, and the module has to be the
     * one {@code sys.modules} holds: a copy that was never filed has no namespace for the body to
     * land in.
     */
    private static PyObject reload(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "reload", args, kwNames, new String[] {"module"}, 1, 1);
        PyObject module = values[0];
        String name = reloadName(module);
        if (PyRuntime.existingModule(name) != module) {
            throw importErrorNamed("module " + name + " not in sys.modules", name);
        }
        if (name.equals("__main__")) {
            throw Slots.importError("reload() of __main__ is not supported");
        }
        int dot = name.lastIndexOf('.');
        String parentName = dot < 0 ? "" : name.substring(0, dot);
        PyObject packagePath = PyNone.INSTANCE;
        if (!parentName.isEmpty()) {
            PyObject parent = PyRuntime.existingModule(parentName);
            if (parent == null || parent instanceof PyNone) {
                throw importErrorNamed(
                        "parent '" + parentName + "' not in sys.modules", parentName);
            }
            PyObject path = Slots.findAttr(parent, "__path__");
            if (path != null) {
                packagePath = path;
            }
        }
        PyModuleSpec spec = PyRuntime.findSpec(name, packagePath, module);
        if (spec == null) {
            throw moduleNotFoundNamed(
                    "spec not found for the module '" + name + "'", name);
        }
        // `_exec` writes the spec back before running the body, so the module's `__spec__` is the
        // one this reload found even if the finder answered with a fresh object.
        Slots.setAttr(module, "__spec__", spec);
        PyObject loader = Slots.getAttr(spec, "loader");
        if (loader instanceof PyNone) {
            throw importErrorNamed("missing loader", name);
        }
        PyObject exec = Slots.findAttr(loader, "exec_module");
        if (exec != null) {
            Slots.call(exec, new PyObject[] {module}, null);
        }
        PyObject filed = PyRuntime.existingModule(name);
        return filed == null ? module : filed;
    }

    /** {@code module.__spec__.name}, or {@code module.__name__}, or the refusal a non-module gets. */
    private static String reloadName(PyObject module) {
        PyObject spec = Slots.findAttr(module, "__spec__");
        if (spec != null && !(spec instanceof PyNone)) {
            PyObject name = Slots.findAttr(spec, "name");
            if (name != null && !(name instanceof PyNone)) {
                return Slots.str(name);
            }
        }
        PyObject name = Slots.findAttr(module, "__name__");
        if (name == null || name instanceof PyNone) {
            throw Slots.typeError("reload() argument must be a module");
        }
        return Slots.str(name);
    }

    // --- importlib.util ------------------------------------------------------------------

    /**
     * {@code find_spec(name, package=None)}: the spec a finder would load, without loading it.
     *
     * <p>A name already in {@code sys.modules} answers that module's own spec rather than a fresh
     * one, which is what makes two calls with the same name answer the same object.
     */
    private static PyObject findSpec(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "find_spec", args, kwNames, new String[] {"name", "package"}, 1, 2);
        String name = stringStartingWithDot(values[0], "startswith");
        String fullname = name.startsWith(".")
                ? resolveName(name, values[1])
                : name;
        PyObject loaded = PyRuntime.existingModule(fullname);
        if (loaded == null) {
            return unloadedSpec(fullname);
        }
        if (loaded instanceof PyNone) {
            return PyNone.INSTANCE;
        }
        PyObject spec = Slots.findAttr(loaded, "__spec__");
        if (spec == null) {
            throw Slots.valueError("%s.__spec__ is not set", name);
        }
        if (spec instanceof PyNone) {
            throw Slots.valueError("%s.__spec__ is None", name);
        }
        return spec;
    }

    /** The finder walk for a name nothing has imported yet, with the parent imported first. */
    private static PyObject unloadedSpec(String fullname) {
        int dot = fullname.lastIndexOf('.');
        String parentName = dot < 0 ? "" : fullname.substring(0, dot);
        PyObject parentPath = PyNone.INSTANCE;
        if (!parentName.isEmpty()) {
            PyObject parent = PyRuntime.importModule(parentName);
            PyObject path = Slots.findAttr(parent, "__path__");
            if (path == null) {
                throw moduleNotFoundNamed(
                        "__path__ attribute not found on '" + parentName
                                + "' while trying to find '" + fullname + "'",
                        fullname);
            }
            parentPath = path;
        }
        PyModuleSpec spec = PyRuntime.findSpec(fullname, parentPath, null);
        return spec == null ? PyNone.INSTANCE : spec;
    }

    /** {@code resolve_name(name, package)}: a relative name made absolute, or left alone. */
    private static PyObject resolveName(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "resolve_name", args, kwNames, new String[] {"name", "package"}, 2, 2);
        return PyStr.of(resolveName(stringStartingWithDot(values[0], "startswith"), values[1]));
    }

    private static String resolveName(String name, PyObject packageValue) {
        if (!name.startsWith(".")) {
            return name;
        }
        if (packageValue == null || packageValue instanceof PyNone
                || !Slots.truth(packageValue)) {
            throw Slots.importError(
                    "no package specified for '%s' (required for relative module names)", name);
        }
        if (!(packageValue instanceof PyStr packageName)) {
            throw Slots.failedRead(
                    packageValue, "rsplit",
                    "'%s' object has no attribute 'rsplit'",
                    packageValue.getType().filedName());
        }
        int level = 0;
        while (level < name.length() && name.charAt(level) == '.') {
            level++;
        }
        return resolveFrom(name.substring(level), packageName.getValue(), level);
    }

    /**
     * {@code _resolve_name}: the package truncated by {@code level - 1} dots, then the rest joined.
     *
     * <p>Climbing past the top-level package is the reference's {@code ImportError}, and the message
     * is the same one the import statement uses.
     */
    private static String resolveFrom(String rest, String packageName, int level) {
        int cut = packageName.length();
        for (int i = 0; i < level - 1; i++) {
            int at = packageName.lastIndexOf('.', cut - 1);
            if (at < 0) {
                throw Slots.importError(
                        "attempted relative import beyond top-level package");
            }
            cut = at;
        }
        String base = packageName.substring(0, cut);
        return rest.isEmpty() ? base : base + "." + rest;
    }

    /**
     * {@code spec_from_file_location(name, location=None, *, loader=None,
     * submodule_search_locations=_POPULATE)}.
     *
     * <p>Only a {@code .py} file is understood; anything else answers {@code None}, which is how the
     * reference says no loader supports the location.
     */
    private static PyObject specFromFileLocation(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "spec_from_file_location", args, kwNames,
                new String[] {"name", "location", "loader", "submodule_search_locations"}, 1, 2);
        if (!(values[0] instanceof PyStr named)) {
            throw Slots.typeError("module name must be str");
        }
        String name = named.getValue();
        PyObject loaderValue = values[2];
        boolean explicitLoader = loaderValue != null && !(loaderValue instanceof PyNone);
        String location;
        if (values[1] == null || values[1] instanceof PyNone) {
            location = "<unknown>";
            if (explicitLoader) {
                PyObject filename = Slots.findAttr(loaderValue, "get_filename");
                if (filename != null) {
                    try {
                        location = Slots.str(
                                Slots.call(filename, new PyObject[] {PyStr.of(name)}, null));
                    } catch (PyRaise ignored) {
                        // The reference only lets an ImportError here choose '<unknown>'.
                    }
                }
            }
        } else {
            location = Path.of(pathString(values[1]))
                    .toAbsolutePath().normalize().toString();
        }
        PyObject loader;
        if (explicitLoader) {
            loader = loaderValue;
        } else {
            if (!location.endsWith(".py")) {
                return PyNone.INSTANCE;
            }
            loader = new PySourceFileLoader(name, location);
        }
        PyObject locations = values[3];
        if (locations == null) {
            locations = isPackage(loader, name) ? PyList.empty() : PyNone.INSTANCE;
        }
        if (locations instanceof PyList list && list.size() == 0) {
            // `_bootstrap_external` appends the directory so a package can be searched beside itself.
            int slash = location.lastIndexOf('/');
            if (slash >= 0) {
                list.add(PyStr.of(location.substring(0, slash)));
            }
        }
        return PyModuleSpec.located(name, loader, location, locations);
    }

    private static boolean isPackage(PyObject loader, String name) {
        PyObject method = Slots.findAttr(loader, "is_package");
        if (method == null) {
            return false;
        }
        try {
            return Slots.truth(Slots.call(method, new PyObject[] {PyStr.of(name)}, null));
        } catch (PyRaise ignored) {
            // A loader that refuses the name leaves the spec without search locations.
            return false;
        }
    }

    /** {@code module_from_spec(spec)}: the module a spec describes, before its body has run. */
    private static PyObject moduleFromSpec(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "module_from_spec", args, kwNames, new String[] {"spec"}, 1, 1);
        if (!(values[0] instanceof PyModuleSpec spec)) {
            throw Slots.typeError(
                    "module_from_spec() argument must be a module spec, not %s",
                    values[0].getType().filedName());
        }
        String name = ((PyStr) Slots.getAttr(spec, "name")).getValue();
        PyObject loader = Slots.getAttr(spec, "loader");
        PyModule module;
        if (loader instanceof PyNone) {
            if (Slots.getAttr(spec, "submodule_search_locations") instanceof PyNone) {
                throw importErrorNamed("missing loader", name);
            }
            module = new PyModule(name);
        } else {
            PyObject create = Slots.findAttr(loader, "create_module");
            if (create == null) {
                if (Slots.findAttr(loader, "exec_module") != null) {
                    throw importErrorNamed(
                            "loaders that define exec_module() must also define create_module()",
                            name);
                }
                module = new PyModule(name);
            } else {
                PyObject created = Slots.call(create, new PyObject[] {spec}, null);
                module = created instanceof PyModule made ? made : new PyModule(name);
            }
        }
        PyRuntime.initialiseAttributes(module, spec);
        return module;
    }

    // --- shared --------------------------------------------------------------------------

    /** A list of the given strings, in the order given. */
    private static PyList names(String... names) {
        PyObject[] values = new PyObject[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = PyStr.of(names[i]);
        }
        return PyList.of(values);
    }

    /** A name the reference reads with {@code startswith}; a non-string fails in that read. */
    private static String stringStartingWithDot(PyObject value, String method) {
        if (!(value instanceof PyStr text)) {
            throw Slots.failedRead(
                    value, method,
                    "'%s' object has no attribute '%s'",
                    value.getType().filedName(), method);
        }
        return text.getValue();
    }

    private static String pathString(PyObject value) {
        if (value instanceof PyStr text) {
            return text.getValue();
        }
        throw Slots.typeError(
                "expected str, bytes or os.PathLike object, not %s",
                value.getType().filedName());
    }

    private static PyRaise importErrorNamed(String message, String name) {
        PyBaseException error = (PyBaseException) Slots.call(
                ExceptionTypes.IMPORT_ERROR,
                new PyObject[] {PyStr.of(message), PyStr.of(name)},
                PyBoundMethod.keywordTuple(new String[] {"name"}));
        return PyRaise.of(error);
    }

    private static PyRaise moduleNotFoundNamed(String message, String name) {
        PyBaseException error = (PyBaseException) Slots.call(
                ExceptionTypes.MODULE_NOT_FOUND_ERROR,
                new PyObject[] {PyStr.of(message), PyStr.of(name)},
                PyBoundMethod.keywordTuple(new String[] {"name"}));
        return PyRaise.of(error);
    }
}
