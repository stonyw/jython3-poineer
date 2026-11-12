package org.python.core;

/**
 * A module spec: what an importer hands the import system to say where a module comes from.
 *
 * <p>CPython's {@code ModuleSpec} is a Python object with eight fields; this is the same eight, in the
 * same order, because a script can read them back with {@code vars(spec)} and compare the dictionary.
 * Three of them are computed rather than stored ({@code parent}, {@code has_location}, {@code cached}),
 * and they are answered here the way 3.14's properties answer them.
 */
public final class PyModuleSpec extends PyObject {

    private static final PyStr NAME = PyStr.interned("name");
    private static final PyStr LOADER = PyStr.interned("loader");
    private static final PyStr ORIGIN = PyStr.interned("origin");
    private static final PyStr LOADER_STATE = PyStr.interned("loader_state");
    private static final PyStr LOCATIONS = PyStr.interned("submodule_search_locations");
    private static final PyStr UNINITIALIZED = PyStr.interned("_uninitialized_submodules");
    private static final PyStr SET_FILEATTR = PyStr.interned("_set_fileattr");
    private static final PyStr CACHED = PyStr.interned("_cached");

    /** The eight fields in {@code ModuleSpec.__dict__} order. */
    private static final PyStr[] FIELDS = {
        NAME, LOADER, ORIGIN, LOADER_STATE, LOCATIONS, UNINITIALIZED, SET_FILEATTR, CACHED
    };

    private final PyDict fields;

    private PyModuleSpec(PyDict fields) {
        super(PyType.MODULE_SPEC);
        this.fields = fields;
    }

    /** The spec a built-in module has: origin {@code 'built-in'}, no file, not a package. */
    static PyModuleSpec builtin(String name, PyType loader) {
        return new PyModuleSpec(fields(
                PyStr.of(name), loader, PyStr.interned("built-in"), PyNone.INSTANCE,
                PyNone.INSTANCE, false));
    }

    /**
     * The spec a {@code .py} file has: an origin on disk and {@code has_location} set.
     *
     * @param packageDirectory the directory a package's {@code __path__} is, or null for a plain module
     */
    static PyModuleSpec source(
            String name, PySourceFileLoader loader, String origin, String packageDirectory) {
        PyObject locations =
                packageDirectory == null ? PyNone.INSTANCE : PyList.of(PyStr.of(packageDirectory));
        return located(name, loader, origin, locations);
    }

    /**
     * A spec for something on disk whose search locations the caller already decided.
     *
     * <p>{@code importlib.util.spec_from_file_location} builds this directly: it may be handed a
     * loader of its own, and a caller may say whether the file is a package at all.
     */
    static PyModuleSpec located(
            String name, PyObject loader, String origin, PyObject locations) {
        return new PyModuleSpec(fields(
                PyStr.of(name), loader, PyStr.of(origin), PyNone.INSTANCE, locations, true));
    }

    private static PyDict fields(
            PyObject name, PyObject loader, PyObject origin, PyObject loaderState,
            PyObject locations, boolean fileattr) {
        PyDict values = PyDict.empty();
        values.put(NAME, name);
        values.put(LOADER, loader);
        values.put(ORIGIN, origin);
        values.put(LOADER_STATE, loaderState);
        values.put(LOCATIONS, locations);
        values.put(UNINITIALIZED, PyList.empty());
        values.put(SET_FILEATTR, PyBool.of(fileattr));
        values.put(CACHED, PyNone.INSTANCE);
        return values;
    }

    /** {@code ModuleSpec(name, loader, *, origin=None, loader_state=None, is_package=None)}. */
    static PyModuleSpec called(PyObject[] args, String[] kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        if (positional > 2) {
            // The receiver is counted in this sentence, which is why it is not shared with the binder.
            throw Slots.typeError(
                    "ModuleSpec.__init__() takes 3 positional arguments but %d were given",
                    positional + 1);
        }
        PyObject[] values = ImportArguments.bind(
                "ModuleSpec.__init__", args, kwNames,
                new String[] {"name", "loader", "origin", "loader_state", "is_package"}, 2);
        PyObject origin = values[2] == null ? PyNone.INSTANCE : values[2];
        PyObject loaderState = values[3] == null ? PyNone.INSTANCE : values[3];
        boolean isPackage = values[4] != null && Slots.truth(values[4]);
        PyObject locations = isPackage ? PyList.empty() : PyNone.INSTANCE;
        PyDict fields = fields(values[0], values[1], origin, loaderState, locations, false);
        return new PyModuleSpec(fields);
    }

    // --- what a reader sees --------------------------------------------------------------

    @Override
    public PyObject getAttr(String attribute) {
        switch (attribute) {
            case "__dict__":
                return copyOfFields();
            case "parent":
                return parent();
            case "has_location":
                return fields.get(SET_FILEATTR);
            case "cached":
                return cached();
            default:
                PyObject value = fields.get(PyStr.interned(attribute));
                return value == null ? Slots.DECLINED : value;
        }
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        if (attribute.equals("parent")) {
            throw Slots.attributeError(
                    "property 'parent' of 'ModuleSpec' object has no setter");
        }
        if (attribute.equals("__dict__")) {
            throw Slots.attributeError("readonly attribute");
        }
        if (attribute.equals("has_location")) {
            fields.put(SET_FILEATTR, PyBool.of(Slots.truth(value)));
            return PyNone.INSTANCE;
        }
        if (attribute.equals("cached")) {
            // The property's setter writes the field behind it, which is the name `vars(spec)` shows.
            fields.put(CACHED, value);
            return PyNone.INSTANCE;
        }
        fields.put(PyStr.interned(attribute), value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        if (attribute.equals("parent") || attribute.equals("has_location")
                || attribute.equals("cached")) {
            throw Slots.attributeError(
                    "property '%s' of 'ModuleSpec' object has no deleter", attribute);
        }
        if (fields.remove(PyStr.interned(attribute)) == null) {
            throw Slots.attributeError(
                    "'ModuleSpec' object has no attribute '%s'", attribute);
        }
        return PyNone.INSTANCE;
    }

    @Override
    public PyDict attributeStore() {
        return fields;
    }

    /**
     * {@code parent}: the package directory's own name, or the dotted name without its last component.
     *
     * <p>A package answers with its full name because loading it is what makes it a package; a plain
     * module answers with its parent package name, which is what {@code __package__} wants.
     */
    private PyObject parent() {
        if (fields.get(LOCATIONS) != PyNone.INSTANCE) {
            return fields.get(NAME);
        }
        PyObject name = fields.get(NAME);
        if (name instanceof PyStr text) {
            int dot = text.getValue().lastIndexOf('.');
            return PyStr.of(dot < 0 ? "" : text.getValue().substring(0, dot));
        }
        throw Slots.attributeError(
                "'%s' object has no attribute 'rpartition'", name.getType().name());
    }

    /**
     * {@code cached}: where the bytecode for this origin would live, computed once and kept.
     *
     * <p>Only a {@code .py} origin has one. The formula is {@code cache_from_source}'s: the file moves
     * to {@code __pycache__} beside it and its stem gains the interpreter tag.
     */
    private PyObject cached() {
        PyObject stored = fields.get(CACHED);
        if (stored != PyNone.INSTANCE) {
            return stored;
        }
        PyObject origin = fields.get(ORIGIN);
        if (origin != PyNone.INSTANCE && Slots.truth(fields.get(SET_FILEATTR))) {
            PyObject computed = cachedFrom(origin);
            fields.put(CACHED, computed);
            return computed;
        }
        return stored;
    }

    private static PyObject cachedFrom(PyObject origin) {
        if (!(origin instanceof PyStr text) || !text.getValue().endsWith(".py")) {
            return PyNone.INSTANCE;
        }
        String path = text.getValue();
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "" : path.substring(0, slash + 1);
        String name = path.substring(slash + 1, path.length() - ".py".length());
        return PyStr.of(directory + "__pycache__/" + name + ".cpython-314.pyc");
    }

    private PyDict copyOfFields() {
        PyDict copy = PyDict.empty();
        for (PyStr field : FIELDS) {
            copy.put(field, fields.get(field));
        }
        return copy;
    }

    /** The representation 3.14 builds: the four fields it holds, each with {@code repr}. */
    @Override
    public String repr() {
        String name = Slots.repr(fields.get(NAME));
        String loader = Slots.repr(fields.get(LOADER));
        PyObject origin = fields.get(ORIGIN);
        PyObject locations = fields.get(LOCATIONS);
        if (locations == PyNone.INSTANCE) {
            return origin == PyNone.INSTANCE
                    ? "ModuleSpec(name=" + name + ", loader=" + loader + ")"
                    : "ModuleSpec(name=" + name + ", loader=" + loader
                            + ", origin=" + Slots.repr(origin) + ")";
        }
        return origin == PyNone.INSTANCE
                ? "ModuleSpec(name=" + name + ", loader=" + loader
                        + ", submodule_search_locations=" + Slots.repr(locations) + ")"
                : "ModuleSpec(name=" + name + ", loader=" + loader
                        + ", origin=" + Slots.repr(origin)
                        + ", submodule_search_locations=" + Slots.repr(locations) + ")";
    }
}
