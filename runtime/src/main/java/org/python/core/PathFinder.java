package org.python.core;

/**
 * {@code _frozen_importlib_external.PathFinder}: the last finder in {@code sys.meta_path}, and the
 * one that reads {@code sys.path}.
 *
 * <p>The reference does not read a directory here. For every {@code sys.path} entry it asks the hooks
 * on {@code sys.path_hooks} to turn that entry into an importer, remembers the answer in {@code
 * sys.path_importer_cache} under the entry it was made for, and asks the importer for the spec. A
 * directory answers a {@link PyFileFinder}; an entry no hook accepts is cached as None and skipped.
 *
 * <p>A directory that holds a subdirectory of the name, with no {@code __init__.py} in it, is a
 * <em>namespace portion</em> rather than a package. Every such portion on the path contributes its
 * directory to one merged {@code _NamespacePath}, and the spec that comes back has no loader and no
 * origin. That merge is what makes a namespace package span two {@code sys.path} entries.
 *
 * <p>The class dictionary is built key by key, as {@link BuiltinImporter}'s is, so that
 * {@code PathFinder.__name__} and {@code PathFinder.__module__} are the names the reference shows.
 */
final class PathFinder {

    /** The class itself, made the first time the import system needs it. */
    private static PyType type;

    /** The function a namespace package holds as {@code _path_finder}. */
    private static PyNativeFunction pathFinderFunction;

    private PathFinder() {
    }

    static synchronized PyType type() {
        if (type == null) {
            type = define();
        }
        return type;
    }

    private static PyType define() {
        PyDict namespace = PyDict.empty();
        namespace.put(PyStr.interned("__module__"), PyStr.of("_frozen_importlib_external"));
        // Named like the class body that defines it; a heap type's dictionary holds no `__qualname__`.
        namespace.put(PyStr.interned("__qualname__"), PyStr.of("PathFinder"));
        namespace.put(
                PyStr.interned("__doc__"),
                PyStr.of(
                        "PathFinder implements find_spec() by searching sys.path.\n"
                                + "\n"
                                + "For a top-level module, it searches sys.path, and for a submodule,\n"
                                + "it searches the parent package's __path__.\n"));
        namespace.put(
                PyStr.interned("find_spec"),
                new PyClassMethod(new PyNativeFunction("find_spec", PathFinder::findSpec)));
        namespace.put(PyStr.interned("__static_attributes__"), PyTuple.of());
        PyType made =
                (PyType)
                        Classes.newType(
                                PyType.TYPE,
                                new PyObject[] {
                                    PyStr.of("PathFinder"), PyTuple.of(PyType.OBJECT), namespace
                                },
                                null);
        return made.filedUnder("_frozen_importlib_external.PathFinder");
    }

    /**
     * {@code find_spec(fullname, path=None, target=None)}: the first spec on {@code sys.path}, or
     * {@code None}.
     *
     * <p>A full name that is not a string is refused the way the reference refuses it — through the
     * {@code rpartition} the tail is cut with — rather than by a type check of this method's own.
     */
    private static PyObject findSpec(PyObject[] args, String[] kwNames) {
        PyObject[] values =
                BuiltinImporter.bind(
                        "PathFinder.find_spec",
                        args,
                        kwNames,
                        new String[] {"cls", "fullname", "path", "target"},
                        2);
        PyObject fullname = values[1];
        if (!(fullname instanceof PyStr name)) {
            throw Slots.attributeError(
                    "'%s' object has no attribute 'rpartition'",
                    fullname.getType().filedName());
        }
        // A caller that has no path of its own passes None, and `sys.meta_path`'s walk does exactly
        // that for a top-level name, so both spellings mean "read sys.path".
        PyObject path = values[2];
        if (path == null || path instanceof PyNone) {
            path = PyRuntime.sysAttribute("path");
        }
        PyModuleSpec found = findSpecFor(name.getValue(), path);
        return found == null ? PyNone.INSTANCE : found;
    }

    /**
     * The search itself: finders from the hooks, then one merged namespace spec when every portion
     * had no loader, or null when nothing on the path holds the name.
     *
     * <p>A regular package or module answers as soon as its finder does; only an entry that answers a
     * spec without a loader is a portion, and those are accumulated rather than returned, because the
     * namespace package is the merge of all of them.
     */
    static PyModuleSpec findSpecFor(String dotted, PyObject path) {
        PyList portions = PyList.empty();
        boolean sawPortion = false;
        long count = Numbers.size(Slots.length(path));
        for (long i = 0; i < count; i++) {
            PyObject entry = path.getItem(PyInt.of(i));
            PyObject finder = importerFor(entry);
            if (finder == null) {
                continue;
            }
            PyObject found = Slots.call(
                    Slots.getAttr(finder, "find_spec"),
                    new PyObject[] {PyStr.of(dotted), PyNone.INSTANCE},
                    null);
            if (!(found instanceof PyModuleSpec spec)) {
                continue;
            }
            PyObject loader = Slots.getAttr(spec, "loader");
            if (!(loader instanceof PyNone)) {
                return spec;
            }
            PyObject locations = Slots.getAttr(spec, "submodule_search_locations");
            if (locations instanceof PyNone) {
                continue;
            }
            long locationCount = Numbers.size(Slots.length(locations));
            for (long j = 0; j < locationCount; j++) {
                portions.add(locations.getItem(PyInt.of(j)));
            }
            sawPortion = true;
        }
        if (sawPortion) {
            PyNamespacePath merged = new PyNamespacePath(dotted, portions, pathFinder());
            return PyModuleSpec.namespace(dotted, merged);
        }
        return null;
    }

    private static synchronized PyNativeFunction pathFinder() {
        if (pathFinderFunction == null) {
            pathFinderFunction = new PyNativeFunction(
                    "_get_spec",
                    (args, kwNames) -> {
                        PyObject[] values = ImportArguments.bind(
                                "_get_spec", args, kwNames,
                                new String[] {"name", "path"}, 2);
                        return findSpecFor(Slots.str(values[0]), values[1]);
                    });
        }
        return pathFinderFunction;
    }

    /**
     * {@code _path_importer_cache}: the finder cached for one {@code sys.path} entry, or null.
     *
     * <p>The key is the entry as it stands, which is what the reference keys on: a relative entry
     * stays relative in the cache and {@code ''} stays {@code ''}. A hook that raises ImportError
     * does not own the entry; any other failure is the program's and is raised.
     */
    private static PyObject importerFor(PyObject entry) {
        PyObject cacheValue = PyRuntime.sysAttribute("path_importer_cache");
        PyDict cache = cacheValue instanceof PyDict dict ? dict : null;
        if (cache != null) {
            PyObject cached = cache.get(entry);
            if (cached != null) {
                return cached instanceof PyNone ? null : cached;
            }
        }
        PyObject hooks = PyRuntime.sysAttribute("path_hooks");
        PyObject finder = null;
        long count = Numbers.size(Slots.length(hooks));
        for (long i = 0; i < count; i++) {
            PyObject hook = hooks.getItem(PyInt.of(i));
            try {
                finder = Slots.call(hook, new PyObject[] {entry}, null);
                break;
            } catch (PyRaise failure) {
                if (!failure.value().exceptionType().matches(ExceptionTypes.IMPORT_ERROR)) {
                    throw failure;
                }
            }
        }
        if (cache != null) {
            cache.put(entry, finder == null ? PyNone.INSTANCE : finder);
        }
        return finder;
    }
}
