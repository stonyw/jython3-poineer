package org.python.core;

/**
 * {@code NamespaceLoader}: the loader a namespace package's spec carries.
 *
 * <p>A namespace package has nothing to execute. The reference still gives it a loader so that
 * {@code module.__loader__} is not None and so the import protocol can be walked uniformly; that
 * loader's {@code create_module} answers None (the import system builds the module), {@code
 * exec_module} does nothing, and the three reading methods answer the empty source a namespace
 * package stands for.
 *
 * <p>The one field, {@code _path}, is the spec's search locations. {@code vars(loader)} shows it.
 */
final class PyNamespaceLoader extends PyObject {

    private static final PyStr PATH = PyStr.interned("_path");

    private static final Members METHODS = Members.of(PyType.NAMESPACE_LOADER)
            .addOne("is_package", "Return True if the module is a package.",
                    (self, argument) -> PyBool.of(true))
            .addOne("get_source", "Return the source of the module.",
                    (self, argument) -> PyStr.of(""))
            .addOne("get_code", "Return the code object of the module.",
                    (self, argument) ->
                            (PyObject) PyRuntime.compileSource("", "<string>"))
            .addOne("create_module", "Create a module from a spec.",
                    (self, argument) -> PyNone.INSTANCE)
            .addOne("exec_module", "Execute the module's code.",
                    (self, argument) -> PyNone.INSTANCE);

    private final PyDict fields;

    PyNamespaceLoader(PyObject path) {
        super(PyType.NAMESPACE_LOADER);
        this.fields = PyDict.empty();
        fields.put(PATH, path);
    }

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
