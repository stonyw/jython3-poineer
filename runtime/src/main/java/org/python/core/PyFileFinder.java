package org.python.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code FileFinder}: the importer {@code sys.path_hooks} makes for a directory.
 *
 * <p>The reference's {@code PathFinder} never reads a directory itself. It asks every hook on
 * {@code sys.path_hooks} to turn one {@code sys.path} entry into an importer, caches the answer in
 * {@code sys.path_importer_cache}, and then asks that importer for the name. This class is the
 * importer a directory answers with: it holds the directory and the {@code (loader, suffixes)} pairs
 * it was made for, and searches the package form of a name before the plain module form.
 *
 * <p>A directory with neither an {@code __init__.py} nor a module file is still a namespace portion:
 * {@code find_spec} answers a spec with no loader whose search locations are this directory, which is
 * how {@link PathFinder} merges the portions of one namespace package.
 */
final class PyFileFinder extends PyObject {

    private static final PyStr PATH = PyStr.interned("path");

    private static final Members METHODS = Members.of(PyType.FILE_FINDER)
            .addClass("path_hook", PyFileFinder::pathHook)
            .add("find_spec", "Try to find a spec for a module in this directory.",
                    PyFileFinder::findSpec)
            .addNoArgument("invalidate_caches", "Clear out the caches of this finder.",
                    self -> PyNone.INSTANCE);

    /** One {@code (loader, suffixes)} pair, in the order the finder was built with them. */
    private record Detail(PyType loader, List<String> suffixes) {
    }

    private final String basePath;
    private final List<Detail> details;
    private final PyDict dictionary;

    private PyFileFinder(String basePath, List<Detail> details) {
        super(PyType.FILE_FINDER);
        this.basePath = basePath;
        this.details = details;
        this.dictionary = PyDict.empty();
        dictionary.put(PATH, PyStr.of(basePath));
    }

    /** {@code FileFinder(path, *loader_details)}; the common case is the hook's own construction. */
    static PyFileFinder called(PyObject[] args, String[] kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        if (positional < 1) {
            throw Slots.typeError(
                    "FileFinder() missing 1 required positional argument: 'path'");
        }
        PyObject path = args[0];
        List<Detail> details = new ArrayList<>();
        for (int i = 1; i < positional; i++) {
            details.add(detail(args[i]));
        }
        if (details.isEmpty()) {
            details = defaultDetails();
        }
        return new PyFileFinder(normalize(Slots.str(path)), details);
    }

    /** The hook {@code sys.path_hooks} carries: a directory becomes a finder with these suffixes. */
    static PyObject defaultHook() {
        return hook(defaultDetails());
    }

    // --- the hook ------------------------------------------------------------------------

    /** {@code FileFinder.path_hook(*loader_details)}: the class method that makes a hook. */
    private static PyObject pathHook(PyObject[] args, String[] kwNames) {
        List<Detail> details = new ArrayList<>();
        for (PyObject argument : args) {
            details.add(detail(argument));
        }
        return hook(details.isEmpty() ? defaultDetails() : details);
    }

    /** {@code path_hook_for_FileFinder}: the closure {@code path_hook} answers with. */
    private static PyObject hook(List<Detail> details) {
        return new PyNativeFunction(
                "path_hook_for_FileFinder",
                (args, kwNames) -> hookFor(details, args, kwNames));
    }

    /** {@code path_hook_for_FileFinder(path)}: a finder for a directory, or the hook's refusal. */
    private static PyObject hookFor(List<Detail> details, PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "path_hook_for_FileFinder", args, kwNames, new String[] {"path"}, 1);
        String path = normalize(Slots.str(values[0]));
        if (!Files.isDirectory(Path.of(path))) {
            throw Slots.importError("only directories are supported");
        }
        return new PyFileFinder(path, details);
    }

    // --- find_spec -----------------------------------------------------------------------

    private static PyObject findSpec(PyObject self, PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "FileFinder.find_spec", args, kwNames,
                new String[] {"fullname", "target"}, 1);
        return ((PyFileFinder) self).specFor(Slots.str(values[0]));
    }

    private PyObject specFor(String fullname) {
        int dot = fullname.lastIndexOf('.');
        String tail = fullname.substring(dot + 1);
        if (tail.isEmpty()) {
            // A trailing dot names an empty component, which no directory holds.
            return PyNone.INSTANCE;
        }
        for (Detail detail : details) {
            if (detail.loader() != PyType.SOURCE_FILE_LOADER) {
                continue;
            }
            for (String suffix : detail.suffixes()) {
                Path directory = Path.of(basePath, tail);
                Path init = Path.of(directory.toString(), "__init__" + suffix);
                if (Files.isRegularFile(init)) {
                    return PyModuleSpec.source(
                            fullname,
                            new PySourceFileLoader(fullname, init.toString()),
                            init.toString(),
                            directory.toString());
                }
            }
            for (String suffix : detail.suffixes()) {
                Path file = Path.of(basePath, tail + suffix);
                if (Files.isRegularFile(file)) {
                    return PyModuleSpec.source(
                            fullname,
                            new PySourceFileLoader(fullname, file.toString()),
                            file.toString(),
                            null);
                }
            }
        }
        Path directory = Path.of(basePath, tail);
        if (Files.isDirectory(directory)) {
            // A namespace portion names the directory the name stands for, under this finder.
            return PyModuleSpec.namespace(
                    fullname, PyList.of(PyStr.of(directory.toString())));
        }
        return PyNone.INSTANCE;
    }

    // --- underneath ----------------------------------------------------------------------

    private static List<Detail> defaultDetails() {
        return List.of(
                new Detail(PyType.SOURCE_FILE_LOADER, List.of(".py")),
                new Detail(PyType.SOURCELESS_FILE_LOADER, List.of(".pyc")),
                new Detail(
                        PyType.EXTENSION_FILE_LOADER,
                        List.of(".cpython-314-darwin.so", ".abi3.so", ".so")));
    }

    private static Detail detail(PyObject value) {
        if (!(value instanceof PyTuple pair) || pair.size() != 2) {
            throw Slots.typeError(
                    "loader_details must be a sequence of (loader, suffixes) pairs");
        }
        if (!(pair.getItem(PyInt.of(0)) instanceof PyType loader)) {
            throw Slots.typeError("loader must be a type");
        }
        List<String> suffixes = new ArrayList<>();
        PyObject listed = pair.getItem(PyInt.of(1));
        long count = Numbers.size(Slots.length(listed));
        for (long i = 0; i < count; i++) {
            suffixes.add(Slots.str(listed.getItem(PyInt.of(i))));
        }
        return new Detail(loader, suffixes);
    }

    /** CPython's {@code _path_abspath}: {@code ''} and {@code '.'} are the process's directory. */
    private static String normalize(String path) {
        if (path.isEmpty() || path.equals(".")) {
            return System.getProperty("user.dir");
        }
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    // --- attributes ----------------------------------------------------------------------

    @Override
    public String repr() {
        return "FileFinder('" + basePath + "')";
    }

    @Override
    public PyObject getAttr(String attribute) {
        if (attribute.equals("__dict__")) {
            return dictionary;
        }
        PyObject value = dictionary.get(PyStr.interned(attribute));
        return value != null ? value : Members.find(this, attribute);
    }

    @Override
    public PyDict attributeStore() {
        return dictionary;
    }
}
