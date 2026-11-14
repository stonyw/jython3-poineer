package org.python.core;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@code _warnings} module: the accelerator half of the reference's two-part warning subsystem.
 *
 * <p>The reference splits the subsystem in two. {@code _py_warnings.py} holds the portable algorithm,
 * and {@code _warnings} replaces a handful of names with C versions whose one behavioural difference
 * matters: they read the {@code warnings} module out of {@code sys.modules} at call time. The Python
 * halves use a module-level {@code _wm} bound once, when {@code warnings.py} last ran; the C halves do
 * not, which is what lets {@code unittest} point {@code sys.modules['warnings']} at another copy and
 * have {@code @deprecated} — imported from the first copy — report into the second copy's
 * {@code catch_warnings} list.
 *
 * <p>{@code warn} keeps that shape by swapping {@code _py_warnings._wm} and calling the reference's
 * own Python implementation; the stack walk, the category checks and the message plumbing are already
 * there and behave exactly as the reference's do. {@code warn_explicit}, on the other hand, is written
 * here in Java, because the C version differs from the Python one in ways the tests can see: string
 * module filters match by exact equality rather than {@code re.match}, the {@code error} action raises
 * the warning with no warnings frame on the traceback, a missing {@code filters}/{@code defaultaction}/
 * {@code onceregistry} falls back to the last value the module exposed, and {@code module_globals} is
 * asked for its loader before the file is read.
 *
 * <p>The module also owns the filter list the C module owns: it starts with the five default filters
 * {@code _warnings}'s {@code init_filters} installs, because {@code warnings.py} skips
 * {@code _setup_defaults()} whenever {@code from _warnings import ...} succeeds. The module entries are
 * the plain strings the C list holds, matched exactly.
 */
final class WarningsModule {

    /** The parameter order the reference's argument clinic declares for {@code warn_explicit}. */
    private static final String[] WARN_EXPLICIT_PARAMETERS =
            {"message", "category", "filename", "lineno", "module", "registry", "module_globals",
                "source"};

    /** The lock {@code _acquire_lock} and {@code _release_lock} share, reentrant as the C one is. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /**
     * The version of the filter list, bumped whenever it changes and compared against the
     * {@code 'version'} entry of every {@code __warningregistry__}.
     */
    private static long filtersVersion = 1;

    /** The last list {@code warnings.filters} answered with, which a deleted attribute falls back to. */
    private static PyList savedFilters;

    private static PyObject savedDefaultAction;
    private static PyDict savedOnceRegistry;
    private static PyContextVar contextVar;

    /**
     * True while {@code warn} is running the Python half, which is the one place the Python code hands
     * {@code warn_explicit} a {@code module_globals} the C implementation never passes: the C
     * {@code warn} looks the source line up from the file, so asking the loader again — and warning
     * about a globals dictionary that has no {@code __spec__} — would be one warning too many.
     */
    private static final ThreadLocal<Boolean> INSIDE_WARN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * The warning the {@code error} action turned into an exception while {@link #warn} was running
     * the Python half.
     *
     * <p>Raising it there would leave {@code _py_warnings.py} on the traceback, and the reference's C
     * {@code warn} leaves nothing but the caller's frame. Handing the exception out to the Java
     * caller instead — after the Python frame has returned — reproduces that: the frames that remain
     * are the ones between the user's code and the JVM.
     */
    private static final ThreadLocal<PyBaseException> DEFERRED = new ThreadLocal<>();

    private WarningsModule() {
    }

    static PyModule install() {
        PyModule module = new PyModule("_warnings");
        PyRuntime.markBuiltIn(module, "_warnings");
        savedFilters = defaultFilters();
        module.set("filters", savedFilters);
        savedDefaultAction = PyStr.of("default");
        module.set("_defaultaction", savedDefaultAction);
        savedOnceRegistry = PyDict.empty();
        module.set("_onceregistry", savedOnceRegistry);
        contextVar = PyContextVar.construct(new PyObject[] {PyStr.of("warnings_context")}, null);
        module.set("_warnings_context", contextVar);
        module.set("warn", new PyNativeFunction("warn", WarningsModule::warn));
        module.set("warn_explicit", new PyNativeFunction("warn_explicit", WarningsModule::warnExplicit));
        module.set("_filters_mutated_lock_held", new PyNativeFunction("_filters_mutated_lock_held",
                (args, keywordNames) -> {
                    filtersVersion++;
                    PyObject warnings = warningsModule();
                    if (optionalAttr(warnings, "_filters_version") != null) {
                        Slots.setAttr(warnings, "_filters_version", PyInt.of(filtersVersion));
                    }
                    return PyNone.INSTANCE;
                }));
        module.set("_acquire_lock", new PyNativeFunction("_acquire_lock", (args, keywordNames) -> {
            LOCK.lock();
            return PyNone.INSTANCE;
        }));
        module.set("_release_lock", new PyNativeFunction("_release_lock", (args, keywordNames) -> {
            try {
                LOCK.unlock();
            } catch (IllegalMonitorStateException unheld) {
                throw PyRaise.raise(ExceptionTypes.RUNTIME_ERROR, "cannot release un-acquired lock");
            }
            return PyNone.INSTANCE;
        }));
        return module;
    }

    /**
     * Call {@code _py_warnings.warn} with {@code _wm} bound to the module {@code import warnings}
     * answers with — the lookup the C code does on every call.
     */
    private static PyObject warn(PyObject[] args, String[] keywordNames) {
        PyObject pyWarnings = PyRuntime.importModule("_py_warnings");
        PyObject warnings = warningsModule();
        PyObject savedWm = Slots.getAttr(pyWarnings, "_wm");
        Slots.setAttr(pyWarnings, "_wm", warnings);
        boolean outer = INSIDE_WARN.get();
        INSIDE_WARN.set(Boolean.TRUE);
        PyBaseException outerDeferred = DEFERRED.get();
        DEFERRED.remove();
        try {
            PyObject result = Slots.call(
                    pyWarningsFunction(pyWarnings, "warn"), args, keywordNames(keywordNames));
            PyBaseException raised = DEFERRED.get();
            if (raised != null) {
                throw PyRaise.reraise(raised);
            }
            return result;
        } finally {
            INSIDE_WARN.set(outer);
            DEFERRED.set(outerDeferred);
            Slots.setAttr(pyWarnings, "_wm", savedWm);
        }
    }

    private static PyObject pyWarningsFunction(PyObject pyWarnings, String name) {
        return Slots.getAttr(pyWarnings, name);
    }

    /** The C {@code warn_explicit}, ported statement for statement. */
    private static PyObject warnExplicit(PyObject[] args, String[] keywordNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "warn_explicit", args, keywordNames, WARN_EXPLICIT_PARAMETERS, 4, 8);
        for (int i = 0; i < 4; i++) {
            if (values[i] == null) {
                throw Slots.typeError(
                        "warn_explicit() missing required argument '%s' (pos %d)",
                        WARN_EXPLICIT_PARAMETERS[i], i + 1);
            }
        }
        PyObject message = values[0];
        PyObject category = values[1];
        PyObject filename = values[2];
        int lineno = Numbers.cInteger(values[3]);
        PyObject module = values[4];
        PyObject registry = values[5];
        PyObject moduleGlobals = values[6];
        PyObject source = values[7];

        // A warning emitted during interpreter shutdown may be handed `module=None`; the reference
        // ignores it rather than trying to read a filter list that is already gone.
        if (module == PyNone.INSTANCE) {
            return PyNone.INSTANCE;
        }
        if (registry == PyNone.INSTANCE) {
            registry = null;
        } else if (registry != null && PyDict.bodyOf(registry) == null) {
            throw Slots.typeError("'registry' must be a dict or None");
        }
        if (moduleGlobals == PyNone.INSTANCE) {
            moduleGlobals = null;
        }
        if (INSIDE_WARN.get()) {
            moduleGlobals = null;
        }
        if (moduleGlobals != null && PyDict.bodyOf(moduleGlobals) == null) {
            throw Slots.typeError("module_globals must be a dict, not '%s'",
                    moduleGlobals.getType().filedName());
        }
        if (!(filename instanceof PyStr)) {
            throw Slots.typeError(
                    "warn_explicit() argument 'filename' must be str, not %s",
                    filename.getType().filedName());
        }
        if (module == null) {
            module = normalizeModule((PyStr) filename);
        }

        PyObject sourceLine = moduleGlobals == null ? null : sourceLine(moduleGlobals, lineno);

        PyObject text = message;
        if (Classes.instanceOf(message, warningCategory())) {
            text = PyStr.of(Slots.str(message));
            category = message.getType();
        } else {
            message = Slots.call(category, new PyObject[] {message}, null);
        }
        if (source == PyNone.INSTANCE) {
            source = null;
        }
        PyObject linenoObject = PyInt.of(lineno);
        PyObject key = PyTuple.of(text, category, linenoObject);

        LOCK.lock();
        try {
            PyDict registryDict = registry == null ? null : PyDict.bodyOf(registry);
            if (registryDict != null && alreadyWarned(registryDict, key, false)) {
                return PyNone.INSTANCE;
            }

            Filter matched = getFilter(category, text, lineno, module);
            String action = matched.action;
            if (action.equals("error")) {
                if (INSIDE_WARN.get()) {
                    DEFERRED.set((PyBaseException) message);
                    return PyNone.INSTANCE;
                }
                throw PyRaise.reraise((PyBaseException) message);
            }
            if (action.equals("ignore")) {
                return PyNone.INSTANCE;
            }

            long rc = 0;
            if (!action.equals("always") && !action.equals("all")) {
                if (registryDict != null) {
                    registryDict.put(key, PyBool.of(true));
                }
                if (action.equals("once")) {
                    PyDict once = registryDict != null ? registryDict : onceRegistry();
                    rc = updateRegistry(once, text, category);
                } else if (action.equals("module")) {
                    if (registryDict != null) {
                        rc = updateRegistry(registryDict, text, category);
                    }
                } else if (!action.equals("default")) {
                    throw Slots.runtimeError("Unrecognized action ("
                            + Slots.repr(matched.actionObject)
                            + ") in warnings.filters:\n "
                            + Slots.repr(matched.item));
                }
            }
            if (rc == 0) {
                show(message, category, text, filename, lineno, linenoObject, sourceLine, source);
            }
            return PyNone.INSTANCE;
        } finally {
            LOCK.unlock();
        }
    }

    /** {@code normalize_module}: what a warning with no module name is filed under. */
    private static PyObject normalizeModule(PyStr filename) {
        String name = filename.getValue();
        if (name.isEmpty()) {
            return PyStr.of("<unknown>");
        }
        if (name.endsWith(".py")) {
            return PyStr.of(name.substring(0, name.length() - 3));
        }
        return filename;
    }

    /** The filter a warning matches, and the tuple it came from for the error message. */
    private record Filter(String action, PyObject actionObject, PyObject item) {
    }

    private static Filter getFilter(PyObject category, PyObject text, int lineno, PyObject module) {
        PyObject context = PyContext.current().values.get(contextVar);
        if (context != null) {
            PyObject contextFilters = Slots.getAttr(context, "_filters");
            Filter found = filterSearch(category, text, lineno, module, "_warnings_context _filters",
                    asFilters(contextFilters));
            if (found != null) {
                return found;
            }
        }
        Filter found = filterSearch(category, text, lineno, module, "filters", globalFilters());
        if (found != null) {
            return found;
        }
        PyObject action = defaultAction();
        if (!(action instanceof PyStr)) {
            throw Slots.typeError("warnings.defaultaction must be a string, not '%s'",
                    action.getType().filedName());
        }
        return new Filter(((PyStr) action).getValue(), action, PyNone.INSTANCE);
    }

    private static PyList asFilters(PyObject value) {
        if (!(value instanceof PyList list)) {
            throw Slots.valueError("warnings._warnings_context _filters must be a list");
        }
        return list;
    }

    /** {@code warnings.filters}, or the last list it held when the attribute has been deleted. */
    private static PyList globalFilters() {
        PyObject filters = optionalAttr(warningsModule(), "filters");
        if (filters == null) {
            return savedFilters;
        }
        if (!(filters instanceof PyList list)) {
            throw Slots.valueError("warnings.filters must be a list");
        }
        savedFilters = list;
        return list;
    }

    private static PyObject defaultAction() {
        PyObject action = optionalAttr(warningsModule(), "defaultaction");
        if (action == null) {
            return savedDefaultAction;
        }
        if (!(action instanceof PyStr)) {
            throw Slots.typeError("warnings.defaultaction must be a string, not '%s'",
                    action.getType().filedName());
        }
        savedDefaultAction = action;
        return action;
    }

    private static PyDict onceRegistry() {
        PyObject registry = optionalAttr(warningsModule(), "onceregistry");
        if (registry == null) {
            return savedOnceRegistry;
        }
        PyDict dict = PyDict.bodyOf(registry);
        if (dict == null) {
            throw Slots.typeError("warnings.onceregistry must be a dict, not '%s'",
                    registry.getType().filedName());
        }
        savedOnceRegistry = dict;
        return dict;
    }

    private static Filter filterSearch(PyObject category, PyObject text, int lineno, PyObject module,
            String listName, PyList filters) {
        List<PyObject> items = filters.getItems();
        for (int i = 0; i < items.size(); i++) {
            PyObject item = items.get(i);
            if (!(item instanceof PyTuple)) {
                throw Slots.valueError("warnings.%s item %d isn't a 5-tuple", listName, i);
            }
            PyTuple tuple = (PyTuple) item;
            if (tuple.size() != 5) {
                throw Slots.valueError("warnings.%s item %d isn't a 5-tuple", listName, i);
            }
            PyObject action = tuple.at(0);
            if (!(action instanceof PyStr)) {
                throw Slots.typeError("action must be a string, not '%s'",
                        action.getType().filedName());
            }
            boolean goodMessage = matched(tuple.at(1), text);
            boolean goodModule = matched(tuple.at(3), module);
            boolean subclass = Classes.subclassOf(category, tuple.at(2));
            long ln = Numbers.asSsizeT(tuple.at(4));
            if (goodMessage && subclass && goodModule && (ln == 0 || lineno == ln)) {
                return new Filter(((PyStr) action).getValue(), action, item);
            }
        }
        return null;
    }

    /** {@code check_matched}: a string matches exactly, anything else is asked to {@code .match}. */
    private static boolean matched(PyObject pattern, PyObject argument) {
        if (pattern == null || pattern == PyNone.INSTANCE) {
            return true;
        }
        if (pattern.getType() == PyType.STR) {
            if (!(argument instanceof PyStr)) {
                throw Slots.typeError("Can't compare unicode to non-unicode");
            }
            return ((PyStr) pattern).getValue().equals(((PyStr) argument).getValue());
        }
        PyObject match = optionalAttr(pattern, "match");
        if (match == null) {
            return false;
        }
        PyObject result = Slots.call(match, new PyObject[] {argument}, null);
        return Slots.truth(result);
    }

    /** {@code already_warned}: compare the registry's version, clearing it when the filters moved on. */
    private static boolean alreadyWarned(PyDict registry, PyObject key, boolean shouldSet) {
        PyObject version = registry.get(PyStr.interned("version"));
        boolean updateVersion = version == null
                || version.getType() != PyType.INT
                || ((PyInt) version).getValue().longValue() != filtersVersion;
        if (updateVersion) {
            registry.getItems().clear();
            registry.put(PyStr.interned("version"), PyInt.of(filtersVersion));
        } else if (registry.get(key) != null) {
            if (Slots.truth(registry.get(key))) {
                return true;
            }
        }
        if (shouldSet) {
            registry.put(key, PyBool.of(true));
        }
        return false;
    }

    private static long updateRegistry(PyDict registry, PyObject text, PyObject category) {
        return alreadyWarned(registry, PyTuple.of(text, category), true) ? 1 : 0;
    }

    /** The source line the loader of {@code module_globals} can supply, or null when it cannot. */
    private static PyObject sourceLine(PyObject moduleGlobals, int lineno) {
        PyObject moduleName = PyDict.bodyOf(moduleGlobals).get(PyStr.interned("__name__"));
        if (moduleName == null) {
            return null;
        }
        PyObject loader = blessMyLoader(moduleGlobals);
        if (loader == PyNone.INSTANCE) {
            return null;
        }
        PyObject getSource = optionalAttr(loader, "get_source");
        if (getSource == null) {
            return null;
        }
        PyObject source = Slots.call(getSource, new PyObject[] {moduleName}, null);
        if (source == PyNone.INSTANCE) {
            return null;
        }
        // `str.splitlines` and not `source.splitlines`: the reference splits the unicode value
        // itself, so a subclass that overrides the method is not consulted.
        PyObject lines = Slots.call(
                Slots.getAttr(PyType.STR, "splitlines"), new PyObject[] {source}, null);
        List<PyObject> items = lines instanceof PyList list ? list.getItems() : null;
        if (items == null || lineno < 1 || lineno > items.size()) {
            return null;
        }
        return items.get(lineno - 1);
    }

    /**
     * {@code _PyImport_BlessMyLoader}, whose job is to find a loader in a module's globals without
     * trusting the one it finds.
     *
     * <p>{@code __loader__} is the loader Python programs wrote against; {@code __spec__.loader} is
     * the one the import system now believes in. When both are there and differ the old field wins
     * but the disagreement is deprecated. A globals dictionary with neither is a real error — the
     * source line cannot be looked up — while {@code __loader__ = None} with no {@code __spec__} is
     * simply "no loader", which is not.
     */
    private static PyObject blessMyLoader(PyObject moduleGlobals) {
        PyDict globals = PyDict.bodyOf(moduleGlobals);
        PyObject loader = globals.get(PyStr.interned("__loader__"));
        PyObject spec = globals.get(PyStr.interned("__spec__"));
        if (loader == null || loader == PyNone.INSTANCE) {
            if (spec == null) {
                return PyNone.INSTANCE;
            }
            if (spec == PyNone.INSTANCE) {
                throw Slots.valueError("Module globals is missing a __spec__.loader");
            }
            PyObject fromSpec = loaderAttribute(spec, true);
            if (fromSpec == PyNone.INSTANCE) {
                throw Slots.valueError("Module globals is missing a __spec__.loader");
            }
            return fromSpec;
        }
        if (spec == null || spec == PyNone.INSTANCE) {
            deprecate("Module globals is missing a __spec__.loader");
            return loader;
        }
        PyObject fromSpec = loaderAttribute(spec, false);
        if (fromSpec == null || fromSpec == PyNone.INSTANCE) {
            deprecate("Module globals is missing a __spec__.loader");
        } else if (fromSpec != loader) {
            deprecate("Module globals; __loader__ != __spec__.loader");
        }
        return loader;
    }

    /**
     * Read {@code spec.loader}. When {@code required}, a missing attribute is reported the way the C
     * helper reports it; otherwise it is null, which means "no loader there".
     */
    private static PyObject loaderAttribute(PyObject spec, boolean required) {
        try {
            return Slots.getAttr(spec, "loader");
        } catch (PyRaise missing) {
            if (!missing.value().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                throw missing;
            }
            if (!required) {
                return null;
            }
            throw PyRaise.raise(
                    ExceptionTypes.ATTRIBUTE_ERROR, "Module globals is missing a __spec__.loader");
        }
    }

    private static void deprecate(String message) {
        PyObject warnings = warningsModule();
        Slots.call(Slots.getAttr(warnings, "warn"),
                new PyObject[] {PyStr.of(message), builtin("DeprecationWarning")}, null);
    }

    /** {@code call_show_warning}: hand a {@code WarningMessage} to {@code warnings._showwarnmsg}. */
    private static void show(PyObject message, PyObject category, PyObject text, PyObject filename,
            int lineno, PyObject linenoObject, PyObject sourceLine, PyObject source) {
        PyObject warnings = warningsModule();
        PyObject show = optionalAttr(warnings, "_showwarnmsg");
        if (show == null) {
            showFallback(text, category, filename, lineno, sourceLine);
            return;
        }
        if (!Slots.isCallable(show)) {
            throw Slots.typeError("warnings._showwarnmsg() must be set to a callable");
        }
        PyObject warningMessage = optionalAttr(warnings, "WarningMessage");
        if (warningMessage == null) {
            throw Slots.runtimeError("unable to get warnings.WarningMessage");
        }
        PyObject rendered = Slots.call(warningMessage, new PyObject[] {
                message, category, filename, linenoObject, PyNone.INSTANCE, sourceLine, source}, null);
        Slots.call(show, new PyObject[] {rendered}, null);
    }

    /**
     * The C {@code show_warning}, used only when {@code warnings._showwarnmsg} has been deleted: it
     * writes the report itself and reads the file when the caller supplied no source line.
     */
    private static void showFallback(PyObject text, PyObject category, PyObject filename, int lineno,
            PyObject sourceLine) {
        PyObject name = Slots.getAttr(category, "__name__");
        PyObject file;
        try {
            file = Slots.getAttr(PyRuntime.importModule("sys"), "stderr");
        } catch (PyRaise missing) {
            if (!missing.value().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                throw missing;
            }
            file = null;
        }
        if (file == null) {
            PyRuntime.stderr().writeText("lost sys.stderr\n");
            return;
        }
        try {
            writeRaw(file, filename);
            writeRaw(file, ":" + lineno + ": ");
            writeRaw(file, name);
            writeRaw(file, ": ");
            writeRaw(file, text);
            writeRaw(file, "\n");
            if (sourceLine != null) {
                writeRaw(file, sourceLine);
                writeRaw(file, "\n");
            } else {
                displaySourceLine(file, filename, lineno);
            }
        } catch (PyRaise ignored) {
            // The C fallback clears any error it hits and lets the warning go.
        }
    }

    private static void displaySourceLine(PyObject file, PyObject filename, int lineno) {
        if (!(filename instanceof PyStr path)) {
            return;
        }
        String name = path.getValue();
        if (name.startsWith("<") && name.endsWith(">")) {
            return;
        }
        PyObject line = Slots.call(
                Slots.getAttr(PyRuntime.importModule("linecache"), "getline"),
                new PyObject[] {filename, PyInt.of(lineno)},
                null);
        if (!(line instanceof PyStr source) || source.getValue().isEmpty()) {
            return;
        }
        String stripped = source.getValue().replaceFirst("^[ \t\f]+", "");
        writeRaw(file, "  " + stripped + (stripped.endsWith("\n") ? "" : "\n"));
    }

    private static void writeRaw(PyObject file, String text) {
        writeRaw(file, PyStr.of(text));
    }

    private static void writeRaw(PyObject file, PyObject value) {
        Slots.call(Slots.getAttr(file, "write"), new PyObject[] {PyStr.of(Slots.str(value))}, null);
    }

    private static PyObject optionalAttr(PyObject target, String name) {
        try {
            return Slots.getAttr(target, name);
        } catch (PyRaise missing) {
            if (missing.value().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                return null;
            }
            throw missing;
        }
    }

    private static PyObject warningsModule() {
        return PyRuntime.importModule("warnings");
    }

    private static PyObject warningCategory() {
        return Slots.getAttr(PyRuntime.importModule("builtins"), "Warning");
    }

    private static PyTuple keywordNames(String[] names) {
        if (names == null) {
            return null;
        }
        PyObject[] items = new PyObject[names.length];
        for (int i = 0; i < names.length; i++) {
            items[i] = PyStr.interned(names[i]);
        }
        return PyTuple.of(items);
    }

    /** What {@code _warnings}'s {@code init_filters} leaves behind, in its order. */
    private static PyList defaultFilters() {
        PyObject deprecation = builtin("DeprecationWarning");
        return PyList.of(
                filter("default", deprecation, "__main__"),
                filter("ignore", deprecation, null),
                filter("ignore", builtin("PendingDeprecationWarning"), null),
                filter("ignore", builtin("ImportWarning"), null),
                filter("ignore", builtin("ResourceWarning"), null));
    }

    private static PyTuple filter(String action, PyObject category, String module) {
        PyObject pattern = module == null ? PyNone.INSTANCE : PyStr.of(module);
        return PyTuple.of(PyStr.interned(action), PyNone.INSTANCE, category, pattern, PyInt.of(0));
    }

    private static PyObject builtin(String name) {
        return Slots.getAttr(PyRuntime.importModule("builtins"), name);
    }
}
