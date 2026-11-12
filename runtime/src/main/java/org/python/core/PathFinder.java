package org.python.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code _frozen_importlib_external.PathFinder}: the last finder in {@code sys.meta_path}, and the
 * one that reads {@code sys.path}.
 *
 * <p>Its {@code find_spec} is the whole of the search for this milestone: for every directory on
 * {@code sys.path} it looks for a package directory before a plain module file — the reference's
 * {@code FileFinder} makes the same choice — and answers the spec of the first one that exists. A
 * name nothing holds answers {@code None}, which is what tells the import system to go on with the
 * next finder and, eventually, to refuse.
 *
 * <p>What is not here yet is the machinery underneath the reference's version: {@code sys.path_hooks}
 * and the importer cache that turns a directory into a {@code FileFinder}, namespace packages, and
 * the loaders for extension modules and bytecode. A directory is read directly, which is the same
 * answer for the files a script can write in this milestone.
 *
 * <p>The class dictionary is built key by key, as {@link BuiltinImporter}'s is, so that
 * {@code PathFinder.__name__} and {@code PathFinder.__module__} are the names the reference shows.
 */
final class PathFinder {

    /** The class itself, made the first time the import system needs it. */
    private static PyType type;

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
        String dotted = name.getValue();
        int dot = dotted.lastIndexOf('.');
        String tail = dot < 0 ? dotted : dotted.substring(dot + 1);

        // A caller that has no path of its own passes None, and `sys.meta_path`'s walk does exactly
        // that for a top-level name, so both spellings mean "read sys.path".
        PyObject path = values[2];
        if (path == null || path instanceof PyNone) {
            path = PyRuntime.sysAttribute("path");
        }
        long count = Numbers.size(Slots.length(path));
        for (long i = 0; i < count; i++) {
            PyObject entry = path.getItem(PyInt.of(i));
            if (!(entry instanceof PyStr directory)) {
                continue;
            }
            PyModuleSpec found = search(directory.getValue(), dotted, tail);
            if (found != null) {
                return found;
            }
        }
        return PyNone.INSTANCE;
    }

    /** One {@code sys.path} entry: the package first, then the module file beside it. */
    private static PyModuleSpec search(String directory, String fullname, String tail) {
        // A directory entry always has a name, so the empty tail of `pkg.` names nothing at all —
        // the reference's FileFinder misses it for the same reason and answers None.
        if (tail.isEmpty()) {
            return null;
        }
        String base = directory.isEmpty() ? System.getProperty("user.dir") : directory;
        String packageDirectory = join(base, tail);
        String init = join(packageDirectory, "__init__.py");
        if (Files.isRegularFile(Path.of(init))) {
            PySourceFileLoader loader = new PySourceFileLoader(fullname, init);
            return PyModuleSpec.source(fullname, loader, init, packageDirectory);
        }
        String file = join(base, tail + ".py");
        if (Files.isRegularFile(Path.of(file))) {
            PySourceFileLoader loader = new PySourceFileLoader(fullname, file);
            return PyModuleSpec.source(fullname, loader, file, null);
        }
        return null;
    }

    private static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }
}
