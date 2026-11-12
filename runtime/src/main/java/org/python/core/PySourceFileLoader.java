package org.python.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code SourceFileLoader}: the loader {@link PathFinder} hands back for a {@code .py} file.
 *
 * <p>It is the part of the import system a script can see. {@code module.__loader__} is one of these,
 * and its methods are the protocol the import system itself calls: {@code create_module} builds the
 * module, {@code exec_module} runs it, and {@code get_filename}/{@code is_package}/{@code get_source}
 * answer what a reader asks about the file behind it.
 *
 * <p>Compilation is not this class's: {@link PyRuntime#compileSource} hands the source to whatever
 * front end the build installed, which keeps design D2's direction — this module does not depend on
 * {@code front-cpython314}.
 */
public final class PySourceFileLoader extends PyObject {

    private static final PyStr NAME = PyStr.interned("name");
    private static final PyStr PATH = PyStr.interned("path");

    private static final Members METHODS = Members.of(PyType.SOURCE_FILE_LOADER)
            .add("get_filename", "Return the path to the source file as found by the finder.",
                    PySourceFileLoader::getFilename)
            .add("is_package", "Return True if the module is a package.",
                    PySourceFileLoader::isPackage)
            .add("create_module", "Create a module from a spec, or None to let the import system.",
                    PySourceFileLoader::createModule)
            .add("exec_module", "Execute the module's code in the module's namespace.",
                    PySourceFileLoader::execModule)
            .add("load_module", "Load a module by name, the way the old import protocol did.",
                    PySourceFileLoader::loadModule)
            .add("get_source", "Return the text of the source file.",
                    PySourceFileLoader::getSource)
            .add("get_code", "Compile the source file and return its code object.",
                    PySourceFileLoader::getCode);

    private final PyDict dictionary;

    PySourceFileLoader(String fullname, String path) {
        super(PyType.SOURCE_FILE_LOADER);
        this.dictionary = PyDict.empty();
        dictionary.put(NAME, PyStr.of(fullname));
        dictionary.put(PATH, PyStr.of(path));
    }

    /** {@code SourceFileLoader(fullname, path)}; the errors name the base class, as 3.14's do. */
    static PySourceFileLoader called(PyObject[] args, String[] kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        if (positional > 2) {
            throw Slots.typeError(
                    "FileLoader.__init__() takes 3 positional arguments but %d were given",
                    positional + 1);
        }
        PyObject[] values = ImportArguments.bind(
                "FileLoader.__init__", args, kwNames, new String[] {"fullname", "path"}, 2);
        return new PySourceFileLoader(Slots.str(values[0]), Slots.str(values[1]));
    }

    // --- the protocol the import system calls --------------------------------------------

    private static PyObject createModule(PyObject self, PyObject[] args, String[] kwNames) {
        ImportArguments.bind(
                "SourceFileLoader.create_module", args, kwNames, new String[] {"spec"}, 1);
        // The import system builds the module when a loader has nothing special to say.
        return PyNone.INSTANCE;
    }

    private static PyObject execModule(PyObject self, PyObject[] args, String[] kwNames) {
        ImportArguments.bind(
                "SourceFileLoader.exec_module", args, kwNames, new String[] {"module"}, 1);
        PySourceFileLoader loader = (PySourceFileLoader) self;
        PyObject module = args[0];
        PyObject name = Slots.getAttr(module, "__name__");
        PyCode code = loader.codeFor(name);
        PyRuntime.exec(code, (PyModule) module, null);
        return PyNone.INSTANCE;
    }

    private static PyObject loadModule(PyObject self, PyObject[] args, String[] kwNames) {
        ImportArguments.bind(
                "SourceFileLoader.load_module", args, kwNames, new String[] {"fullname"}, 1);
        PySourceFileLoader loader = (PySourceFileLoader) self;
        loader.checkName(args[0]);
        return PyRuntime.importModule(Slots.str(args[0]));
    }

    // --- reading the file ----------------------------------------------------------------

    private static PyObject getFilename(PyObject self, PyObject[] args, String[] kwNames) {
        ImportArguments.bind(
                "SourceFileLoader.get_filename", args, kwNames, new String[] {"fullname"}, 1);
        PySourceFileLoader loader = (PySourceFileLoader) self;
        loader.checkName(args[0]);
        return loader.dictionary.get(PATH);
    }

    private static PyObject isPackage(PyObject self, PyObject[] args, String[] kwNames) {
        ImportArguments.bind(
                "SourceFileLoader.is_package", args, kwNames, new String[] {"fullname"}, 1);
        PySourceFileLoader loader = (PySourceFileLoader) self;
        loader.checkName(args[0]);
        String path = loader.path();
        int slash = path.lastIndexOf('/');
        String base = path.substring(slash + 1);
        return PyBool.of(base.startsWith("__init__."));
    }

    private static PyObject getSource(PyObject self, PyObject[] args, String[] kwNames) {
        ImportArguments.bind(
                "SourceFileLoader.get_source", args, kwNames, new String[] {"fullname"}, 1);
        PySourceFileLoader loader = (PySourceFileLoader) self;
        loader.checkName(args[0]);
        String source = loader.source();
        return source == null ? PyNone.INSTANCE : PyStr.of(source);
    }

    private static PyObject getCode(PyObject self, PyObject[] args, String[] kwNames) {
        ImportArguments.bind(
                "SourceFileLoader.get_code", args, kwNames, new String[] {"fullname"}, 1);
        // A front end's code object implements PyCode and is a PyObject, which is the same assumption
        // `PyFunction.__code__` makes when it hands one to a program.
        return (PyObject) ((PySourceFileLoader) self).codeFor(args[0]);
    }

    // --- underneath ----------------------------------------------------------------------

    /** {@code _check_name}: a loader only loads the module it was made for. */
    private void checkName(PyObject fullname) {
        String given = fullname instanceof PyStr text ? text.getValue() : Slots.str(fullname);
        String own = ((PyStr) dictionary.get(NAME)).getValue();
        if (!own.equals(given)) {
            throw Slots.importError("loader for %s cannot handle %s", own, given);
        }
    }

    private PyCode codeFor(PyObject fullname) {
        checkName(fullname);
        String source = source();
        if (source == null) {
            throw Slots.importError("cannot read %s", path());
        }
        return PyRuntime.compileSource(source, path());
    }

    private String source() {
        try {
            return Files.readString(Path.of(path()), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return null;
        }
    }

    private String path() {
        return ((PyStr) dictionary.get(PATH)).getValue();
    }

    // --- attributes ----------------------------------------------------------------------

    @Override
    public PyObject getAttr(String attribute) {
        if (attribute.equals("__dict__")) {
            return dictionary;
        }
        PyObject value = dictionary.get(PyStr.interned(attribute));
        return value != null ? value : Members.find(this, attribute);
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        dictionary.put(PyStr.interned(attribute), value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        return dictionary.remove(PyStr.interned(attribute)) == null
                ? Slots.DECLINED
                : PyNone.INSTANCE;
    }

    @Override
    public PyDict attributeStore() {
        return dictionary;
    }
}
