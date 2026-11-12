package org.python.core;

/**
 * {@code _NamespacePath}: the {@code __path__} a namespace package carries.
 *
 * <p>A namespace package has no file of its own; its path is the merge of every {@code sys.path}
 * entry that holds a directory of the same name. The reference keeps that merge in a list-like object
 * rather than a plain list so that it can be rebuilt when the parent path or the import system's epoch
 * changes. A program sees the list's behaviour ({@code len}, indexing, membership, iteration) and the
 * fields beside it, which is what this class answers.
 *
 * <p>The five fields are the reference's, in the reference's order: {@code _name}, {@code _path},
 * {@code _last_parent_path}, {@code _last_epoch} and {@code _path_finder}. {@code vars(path)} is the
 * dictionary a program reads them from.
 */
final class PyNamespacePath extends PyObject {

    private static final PyStr NAME = PyStr.interned("_name");
    private static final PyStr PATH = PyStr.interned("_path");
    private static final PyStr LAST_PARENT = PyStr.interned("_last_parent_path");
    private static final PyStr LAST_EPOCH = PyStr.interned("_last_epoch");
    private static final PyStr PATH_FINDER = PyStr.interned("_path_finder");

    private static final Members METHODS = Members.of(PyType.NAMESPACE_PATH)
            .addOne("append", "Append a new path item.", PyNamespacePath::append)
            .addNoArgument("_recalculate", PyNamespacePath::recalculate)
            .addNoArgument("_get_parent_path", PyNamespacePath::parentPath)
            .addNoArgument("_find_parent_path_names", PyNamespacePath::parentPathNames);

    private final PyDict fields;
    private final PyList path;

    PyNamespacePath(String name, PyList path, PyObject pathFinder) {
        super(PyType.NAMESPACE_PATH);
        this.path = path;
        this.fields = PyDict.empty();
        fields.put(NAME, PyStr.of(name));
        fields.put(PATH, path);
        fields.put(LAST_PARENT, parentPathOf(name));
        fields.put(LAST_EPOCH, PyInt.of(0));
        fields.put(PATH_FINDER, pathFinder);
    }

    /** The merged directories as a plain list, which is what a module's repr shows. */
    PyList portions() {
        return path;
    }

    // --- the list a reader sees ----------------------------------------------------------

    @Override
    public PyObject length() {
        return path.length();
    }

    @Override
    public PyObject getItem(PyObject key) {
        return path.getItem(key);
    }

    @Override
    public PyObject contains(PyObject item) {
        return path.contains(item);
    }

    @Override
    public PyObject iterator() {
        return path.iterator();
    }

    @Override
    public String repr() {
        return "_NamespacePath(" + path.repr() + ")";
    }

    // --- the methods ---------------------------------------------------------------------

    /** {@code append(item)}: one more directory, in the order the portions were found. */
    private static PyObject append(PyObject self, PyObject item) {
        PyNamespacePath namespacePath = (PyNamespacePath) self;
        namespacePath.path.add(item);
        return PyNone.INSTANCE;
    }

    /**
     * {@code _recalculate()}: remember a new parent path.
     *
     * <p>The reference rebuilds {@code _path} here when the parent's path or the import system's epoch
     * changed. This runtime's {@code sys.path} is read afresh on every import and the merged portions
     * are already the answer for the current path, so the rebuild is left out; the parent path and the
     * epoch are still recorded, which is what {@code vars(path)} shows.
     */
    private static PyObject recalculate(PyObject self) {
        PyNamespacePath namespacePath = (PyNamespacePath) self;
        PyObject parent = namespacePath.parentPath();
        if (!Slots.compareTrue(CompareOp.EQ, parent, namespacePath.fields.get(LAST_PARENT))) {
            namespacePath.fields.put(LAST_PARENT, parent);
            namespacePath.fields.put(LAST_EPOCH, PyInt.of(0));
        }
        return PyNone.INSTANCE;
    }

    /** {@code _get_parent_path()}: the parent package's {@code __path__}, or an empty tuple. */
    private static PyObject parentPath(PyObject self) {
        return ((PyNamespacePath) self).parentPath();
    }

    /** {@code _find_parent_path_names()}: {@code (parent, '__path__')}, or {@code (None, None)}. */
    private static PyObject parentPathNames(PyObject self) {
        return ((PyNamespacePath) self).parentPathNames();
    }

    // --- underneath ----------------------------------------------------------------------

    private PyObject parentPath() {
        PyObject names = parentPathNames();
        PyObject parent = names.getItem(PyInt.of(0));
        if (parent instanceof PyNone) {
            return PyTuple.of();
        }
        PyObject module = PyRuntime.existingModule(Slots.str(parent));
        if (module == null) {
            return PyTuple.of();
        }
        PyObject locations = Slots.findAttr(module, "__path__");
        if (locations == null) {
            return PyTuple.of();
        }
        return copy(locations);
    }

    private PyObject parentPathNames() {
        String name = ((PyStr) fields.get(NAME)).getValue();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return PyTuple.of(PyNone.INSTANCE, PyNone.INSTANCE);
        }
        return PyTuple.of(PyStr.of(name.substring(0, dot)), PyStr.of("__path__"));
    }

    private static PyObject parentPathOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return PyTuple.of();
        }
        PyObject parent = PyRuntime.existingModule(name.substring(0, dot));
        if (parent == null) {
            return PyTuple.of();
        }
        PyObject locations = Slots.findAttr(parent, "__path__");
        return locations == null ? PyTuple.of() : copy(locations);
    }

    private static PyObject copy(PyObject values) {
        long count = Numbers.size(Slots.length(values));
        PyObject[] items = new PyObject[(int) count];
        for (int i = 0; i < count; i++) {
            items[i] = values.getItem(PyInt.of(i));
        }
        return PyTuple.of(items);
    }

    // --- attributes ----------------------------------------------------------------------

    @Override
    public PyObject getAttr(String attribute) {
        if (attribute.equals("__dict__")) {
            return fields;
        }
        PyObject value = fields.get(PyStr.interned(attribute));
        return value != null ? value : Members.find(this, attribute);
    }

    @Override
    public PyDict attributeStore() {
        return fields;
    }
}
