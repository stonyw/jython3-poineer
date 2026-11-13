package org.python.core;

/**
 * {@code array}: the module's two names, over the one type {@link PyArray} implements.
 *
 * <p>CPython's {@code arraymodule.c} adds {@code array} to the module's dictionary and computes
 * {@code typecodes} by walking the type table; the order it walks is the order the table is written
 * in, which is why the string here is not sorted.
 */
final class ArrayModule {

    /** The callable {@code __reduce_ex__} names from protocol three on. */
    private static final PyNativeFunction RECONSTRUCTOR =
            new PyNativeFunction("_array_reconstructor", PyArray::reconstruct);

    private ArrayModule() {
    }

    static PyObject reconstructor() {
        return RECONSTRUCTOR;
    }

    static PyModule newModule() {
        PyModule module = new PyModule("array");
        PyRuntime.markBuiltIn(module, "array");
        module.set("__doc__", PyStr.of(ArrayDocs.MODULE_DOC));
        module.set("array", PyType.ARRAY);
        module.set("ArrayType", PyType.ARRAY);
        module.set("typecodes", PyStr.of(PyArray.TYPECODES));
        module.set("_array_reconstructor", RECONSTRUCTOR);
        return module;
    }
}
