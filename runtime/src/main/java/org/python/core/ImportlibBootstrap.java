package org.python.core;

/**
 * {@code importlib._bootstrap} (also published as {@code _frozen_importlib}): the names the reference
 * keeps in the frozen bootstrap module.
 *
 * <p>The reference implements this module in Python and freezes its code object; the functions below
 * are the same operations, answered by this runtime's import machinery instead of a second Python
 * implementation. {@code _load} and {@code _load_unlocked} go to {@link PyRuntime#loadSpec}, {@code
 * _find_spec} to {@link PyRuntime#findSpec}, and {@code _find_and_load} to {@link
 * PyRuntime#importModule}; the rest are the small pure functions ({@code _sanity_check}, {@code
 * _calc___package__}, {@code _handle_fromlist}, …) ported from the reference's source.
 *
 * <p>Deliberately absent, because this runtime has no counterpart to put behind them, rather than
 * present as empty shells: the module-lock family ({@code _ModuleLock}, {@code _get_module_lock},
 * {@code _lock_unlock_module}), {@code _setup} (the reference seeds a fresh interpreter's import
 * state, which is already installed here), {@code _install}/{@code _install_external_importers},
 * {@code _bootstrap_inner} and {@code _bootstrap_external}'s C accelerator hooks. Callers of
 * {@code _load} — which is all {@code pydoc} uses — are unaffected.
 */
final class ImportlibBootstrap {

    private ImportlibBootstrap() {
    }

    private static final String[] LOAD = {"spec"};
    private static final String[] FIND_AND_LOAD = {"name", "import_"};
    private static final String[] GCD_IMPORT = {"name", "package", "level"};
    private static final String[] FIND_SPEC = {"name", "path", "target"};
    private static final String[] SANITY = {"name", "package", "level"};
    private static final String[] RESOLVE = {"name", "package", "level"};
    private static final String[] HANDLE_FROM_LIST = {"module", "fromlist", "import_", "recursive"};
    private static final String[] NEW_MODULE = {"name"};
    private static final String[] SPEC_FROM_LOADER = {"name", "loader", "origin", "is_package"};
    private static final String[] EXEC = {"spec", "module"};

    static PyModule newModule(
            PyModule bootstrapExternal, PyModule util, PyModule imp, PyModule thread, PyModule weakref) {
        PyModule module = new PyModule("importlib._bootstrap");
        module.set("__doc__", PyStr.of("Core implementation of import.\n\n"
                + "This module is NOT meant to be directly imported! It has been designed such\n"
                + "that it can be bootstrapped into Python as the implementation of import. As\n"
                + "such it requires the injection of specific modules and attributes in order to\n"
                + "work. One should use importlib as the public-facing version of this module."));
        module.set("__package__", PyStr.of("importlib"));
        module.set("__loader__", PyType.FROZEN_IMPORTER);
        module.set("__spec__", PyModuleSpec.frozen("_frozen_importlib"));
        module.set("sys", PyRuntime.sysModule());
        module.set("_imp", imp);
        module.set("_thread", thread);
        module.set("_weakref", weakref);
        module.set("_bootstrap_external", bootstrapExternal);
        module.set("BuiltinImporter", BuiltinImporter.type());
        module.set("FrozenImporter", PyType.FROZEN_IMPORTER);
        module.set("ModuleSpec", PyType.MODULE_SPEC);
        // The reference's `module_from_spec` is one function object shared with importlib.util.
        module.set("module_from_spec", util.getAttr("module_from_spec"));
        module.set("spec_from_loader", new PyNativeFunction(
                "spec_from_loader", ImportlibBootstrap::specFromLoader));
        module.set("_load", new PyNativeFunction("_load", ImportlibBootstrap::load));
        module.set("_load_unlocked",
                new PyNativeFunction("_load_unlocked", ImportlibBootstrap::loadUnlocked));
        module.set("_exec", new PyNativeFunction("_exec", ImportlibBootstrap::exec));
        module.set("_load_backward_compatible", new PyNativeFunction(
                "_load_backward_compatible", ImportlibBootstrap::loadBackwardCompatible));
        module.set("_find_spec", new PyNativeFunction("_find_spec", ImportlibBootstrap::findSpec));
        module.set("_find_and_load", new PyNativeFunction(
                "_find_and_load", ImportlibBootstrap::findAndLoad));
        module.set("_find_and_load_unlocked", new PyNativeFunction(
                "_find_and_load_unlocked", ImportlibBootstrap::findAndLoad));
        module.set("_gcd_import", new PyNativeFunction("_gcd_import", ImportlibBootstrap::gcdImport));
        module.set("_sanity_check", new PyNativeFunction(
                "_sanity_check", ImportlibBootstrap::sanityCheck));
        module.set("_calc___package__", new PyNativeFunction(
                "_calc___package__", ImportlibBootstrap::calcPackage));
        module.set("_resolve_name", new PyNativeFunction(
                "_resolve_name", ImportlibBootstrap::resolveName));
        module.set("_handle_fromlist", new PyNativeFunction(
                "_handle_fromlist", ImportlibBootstrap::handleFromList));
        module.set("_call_with_frames_removed", new PyNativeFunction(
                "_call_with_frames_removed", ImportlibBootstrap::callWithFramesRemoved));
        module.set("_new_module", new PyNativeFunction("_new_module", ImportlibBootstrap::newModule0));
        return module;
    }

    private static PyObject load(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_load", args, kwNames, LOAD, 1);
        return PyRuntime.loadSpec(spec(values[0], "_load"));
    }

    private static PyObject loadUnlocked(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_load_unlocked", args, kwNames, LOAD, 1);
        return PyRuntime.loadSpec(spec(values[0], "_load_unlocked"));
    }

    private static PyObject exec(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_exec", args, kwNames, EXEC, 2);
        PyModuleSpec spec = spec(values[0], "_exec");
        PyObject module = values[1];
        String name = spec.getAttr("name").str();
        PyDict moduleTable = PyRuntime.moduleTable();
        if (moduleTable.get(PyStr.interned(name)) != module) {
            moduleTable.put(PyStr.interned(name), module);
        }
        PyObject loader = spec.getAttr("loader");
        if (loader instanceof PyNone) {
            PyRuntime.initialiseAttributes((PyModule) module, spec);
        } else {
            PyObject execModule = Slots.findAttr(loader, "exec_module");
            if (execModule != null) {
                Slots.call(execModule, new PyObject[] {module}, null);
            } else {
                PyObject loadModule = Slots.findAttr(loader, "load_module");
                if (loadModule == null) {
                    throw Slots.importError("loader for %s cannot handle %s", name, name);
                }
                return Slots.call(loadModule, new PyObject[] {PyStr.of(name)}, null);
            }
        }
        // The reference moves the entry to the end of sys.modules, so the order reflects when the
        // module actually started executing rather than when its spec was filed.
        moduleTable.remove(PyStr.interned(name));
        moduleTable.put(PyStr.interned(name), module);
        return module;
    }

    private static PyObject loadBackwardCompatible(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "_load_backward_compatible", args, kwNames, LOAD, 1);
        PyModuleSpec spec = spec(values[0], "_load_backward_compatible");
        String name = spec.getAttr("name").str();
        PyObject loader = spec.getAttr("loader");
        PyObject loadModule = Slots.findAttr(loader, "load_module");
        if (loadModule == null) {
            throw Slots.importError("loader for %s cannot handle %s", name, name);
        }
        Slots.call(loadModule, new PyObject[] {PyStr.of(name)}, null);
        PyObject module = PyRuntime.existingModule(name);
        if (module == null) {
            throw Slots.importError("load_module() failed to import the module %s", name);
        }
        return module;
    }

    private static PyObject findSpec(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_find_spec", args, kwNames, FIND_SPEC, 2);
        if (!(values[0] instanceof PyStr)) {
            throw Slots.typeError("_find_spec() argument must be str, not %s", typeName(values[0]));
        }
        PyObject path = values[1] == null ? PyNone.INSTANCE : values[1];
        PyObject target = values[2] == null ? PyNone.INSTANCE : values[2];
        PyModuleSpec found = PyRuntime.findSpec(values[0].str(), path, target);
        return found == null ? PyNone.INSTANCE : found;
    }

    private static PyObject findAndLoad(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "_find_and_load", args, kwNames, FIND_AND_LOAD, 2);
        String name = requireStr(values[0], "_find_and_load");
        PyObject filed = PyRuntime.existingModule(name);
        if (filed instanceof PyNone) {
            throw missingModule(name);
        }
        if (filed != null) {
            return filed;
        }
        return PyRuntime.importModule(name);
    }

    private static PyObject gcdImport(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_gcd_import", args, kwNames, GCD_IMPORT, 1);
        String name = requireStr(values[0], "_gcd_import");
        PyObject packageObject = values[1];
        long level = values[2] == null ? 0 : Numbers.asIndex(values[2]).longValue();
        if (level > 0) {
            if (packageObject == null || packageObject instanceof PyNone) {
                throw Slots.importError(
                        "attempted relative import with no known parent package");
            }
            name = resolve(name, requireStr(packageObject, "_gcd_import"), level);
        }
        PyObject filed = PyRuntime.existingModule(name);
        if (filed instanceof PyNone) {
            throw missingModule(name);
        }
        return filed != null ? filed : PyRuntime.importModule(name);
    }

    private static PyObject sanityCheck(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_sanity_check", args, kwNames, SANITY, 3);
        PyObject nameObject = values[0];
        PyObject packageObject = values[1];
        long level = Numbers.asIndex(values[2]).longValue();
        if (!(nameObject instanceof PyStr)) {
            throw Slots.typeError("module name must be str, not %s", typeName(nameObject));
        }
        if (level < 0) {
            throw Slots.valueError("level must be >= 0");
        }
        if (level > 0) {
            if (!(packageObject instanceof PyStr)) {
                throw Slots.typeError("__package__ not set to a string");
            }
            if (((PyStr) packageObject).getValue().isEmpty()) {
                throw Slots.importError(
                        "attempted relative import with no known parent package");
            }
        }
        if (((PyStr) nameObject).getValue().isEmpty() && level == 0) {
            throw Slots.valueError("Empty module name");
        }
        return PyNone.INSTANCE;
    }

    private static PyObject calcPackage(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "_calc___package__", args, kwNames, new String[] {"globals"}, 1);
        if (!(values[0] instanceof PyDict)) {
            throw Slots.typeError("globals must be a dict");
        }
        PyDict globals = (PyDict) values[0];
        PyObject packageObject = globals.get(PyStr.interned("__package__"));
        PyObject spec = globals.get(PyStr.interned("__spec__"));
        if (packageObject != null && !(packageObject instanceof PyNone)) {
            if (spec != null && !(spec instanceof PyNone)) {
                PyObject parent = Slots.getAttr(spec, "parent");
                if (!Slots.compareTrue(CompareOp.EQ, packageObject, parent)) {
                    PyRuntime.warn(
                            "__package__ != __spec__.parent ("
                                    + packageObject.repr() + " != " + parent.repr() + ")",
                            "DeprecationWarning");
                }
            }
            return packageObject;
        }
        if (spec != null && !(spec instanceof PyNone)) {
            return Slots.getAttr(spec, "parent");
        }
        PyRuntime.warn("can't resolve package from __spec__ or __package__, "
                + "falling back on __name__ and __path__", "ImportWarning");
        PyObject name = globals.get(PyStr.interned("__name__"));
        if (name == null) {
            throw Slots.keyError(PyStr.interned("__name__"));
        }
        if (globals.get(PyStr.interned("__path__")) != null) {
            return name;
        }
        return PyStr.of(baseName(requireStr(name, "_calc___package__")));
    }

    private static PyObject resolveName(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_resolve_name", args, kwNames, RESOLVE, 3);
        return PyStr.of(resolve(
                requireStr(values[0], "_resolve_name"),
                requireStr(values[1], "_resolve_name"),
                Numbers.asIndex(values[2]).longValue()));
    }

    private static PyObject handleFromList(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "_handle_fromlist", args, kwNames, HANDLE_FROM_LIST, 3);
        PyObject module = values[0];
        PyObject fromlist = values[1];
        PyObject import_ = values[2];
        boolean recursive = values[3] != null && Slots.truth(values[3]);
        if (Slots.findAttr(module, "__path__") == null) {
            return module;
        }
        String moduleName = Slots.getAttr(module, "__name__").str();
        PyIterator names = Slots.iterator(fromlist);
        for (PyObject item = names.nextOrNull(); item != null; item = names.nextOrNull()) {
            if (!(item instanceof PyStr)) {
                String where = recursive
                        ? "import " + module.repr()
                        : "from " + module.repr() + " import *";
                throw Slots.typeError("%s only supports importing attributes", where);
            }
            String fromName = ((PyStr) item).getValue();
            if (fromName.equals("*")) {
                PyObject all = Slots.findAttr(module, "__all__");
                if (!recursive && all != null) {
                    handleFromList(new PyObject[] {module, all, import_, PyBool.TRUE}, new String[0]);
                }
            } else if (Slots.findAttr(module, fromName) == null) {
                String dotted = moduleName + "." + fromName;
                try {
                    Slots.call(import_, new PyObject[] {PyStr.of(dotted)}, null);
                } catch (PyRaise failed) {
                    if (failed.value().exceptionType()
                                    == ExceptionTypes.MODULE_NOT_FOUND_ERROR) {
                        PyObject excName = Slots.findAttr(failed.value(), "name");
                        PyObject filed = PyRuntime.existingModule(dotted);
                        if (excName instanceof PyStr
                                && ((PyStr) excName).getValue().equals(dotted)
                                && filed != null && !(filed instanceof PyNone)) {
                            continue;
                        }
                    }
                    throw failed;
                }
            }
        }
        return module;
    }

    private static PyObject callWithFramesRemoved(PyObject[] args, String[] kwNames) {
        if (args.length == 0) {
            throw Slots.typeError("_call_with_frames_removed() missing 1 required positional "
                    + "argument: 'f'");
        }
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        PyObject[] rest = new PyObject[positional - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return Slots.call(args[0], rest, PyBoundMethod.keywordTuple(kwNames));
    }

    private static PyObject newModule0(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind("_new_module", args, kwNames, NEW_MODULE, 1);
        return new PyModule(requireStr(values[0], "_new_module"));
    }

    /**
     * {@code spec_from_loader}: {@code ModuleSpec(name, loader, origin=…, is_package=…)} with the
     * reference's fallbacks — {@code loader._ORIGIN}, then {@code spec_from_file_location} for a
     * loader that has a filename, then {@code loader.is_package}.
     */
    private static PyObject specFromLoader(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "spec_from_loader", args, kwNames, SPEC_FROM_LOADER, 2);
        PyObject name = values[0];
        PyObject loader = values[1];
        PyObject origin = values[2] == null ? PyNone.INSTANCE : values[2];
        PyObject isPackage = values[3];
        if (origin instanceof PyNone) {
            PyObject loaderOrigin = Slots.findAttr(loader, "_ORIGIN");
            origin = loaderOrigin == null ? PyNone.INSTANCE : loaderOrigin;
        }
        if (!Slots.truth(origin) && Slots.findAttr(loader, "get_filename") != null) {
            PyObject external = PyRuntime.existingModule("importlib._bootstrap_external");
            PyObject specFromFileLocation =
                    Slots.getAttr(external, "spec_from_file_location");
            if (isPackage instanceof PyNone) {
                return Slots.call(specFromFileLocation, new PyObject[] {name, loader},
                        PyBoundMethod.keywordTuple(new String[] {"loader"}));
            }
            PyObject locations = Slots.truth(isPackage)
                    ? PyList.empty()
                    : PyNone.INSTANCE;
            return Slots.call(specFromFileLocation, new PyObject[] {name, loader, locations},
                    PyBoundMethod.keywordTuple(
                            new String[] {"loader", "submodule_search_locations"}));
        }
        if (isPackage instanceof PyNone) {
            PyObject method = Slots.findAttr(loader, "is_package");
            if (method != null) {
                try {
                    isPackage = Slots.call(method, new PyObject[] {name}, null);
                } catch (PyRaise failed) {
                    if (failed.value().exceptionType() != ExceptionTypes.IMPORT_ERROR) {
                        throw failed;
                    }
                    isPackage = PyNone.INSTANCE;
                }
            } else {
                isPackage = PyBool.FALSE;
            }
        }
        return PyModuleSpec.called(
                new PyObject[] {name, loader, origin, isPackage},
                new String[] {"origin", "is_package"});
    }

    private static PyModuleSpec spec(PyObject value, String function) {
        if (!(value instanceof PyModuleSpec)) {
            throw Slots.typeError("%s() argument must be a ModuleSpec, not %s",
                    function, typeName(value));
        }
        return (PyModuleSpec) value;
    }

    private static String requireStr(PyObject value, String function) {
        if (!(value instanceof PyStr)) {
            throw Slots.typeError("%s() argument must be str, not %s", function, typeName(value));
        }
        return ((PyStr) value).getValue();
    }

    private static String typeName(PyObject value) {
        return "<class '" + value.getType().filedName() + "'>";
    }

    /** {@code package.rsplit('.', level - 1)} with the reference's "beyond top-level" error. */
    private static String resolve(String name, String packageName, long level) {
        String[] bits = packageName.split("\\.", -1);
        int wanted = (int) level - 1;
        if (wanted < 0 || wanted >= bits.length) {
            throw Slots.importError("attempted relative import beyond top-level package");
        }
        StringBuilder base = new StringBuilder(bits[0]);
        for (int i = 1; i <= wanted; i++) {
            base.append('.').append(bits[i]);
        }
        return name.isEmpty() ? base.toString() : base + "." + name;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static PyRaise missingModule(String name) {
        PyBaseException error = (PyBaseException) Slots.call(
                ExceptionTypes.MODULE_NOT_FOUND_ERROR,
                new PyObject[] {
                    PyStr.of("import of " + name + " halted; None in sys.modules"), PyStr.of(name)
                },
                PyBoundMethod.keywordTuple(new String[] {"name"}));
        return PyRaise.of(error);
    }
}
