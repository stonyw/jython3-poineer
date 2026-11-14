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
        registerAsMutableSequence();
        return module;
    }

    /**
     * {@code array_module_exec}'s call to {@code collections.abc.MutableSequence.register}: it is what
     * makes {@code isinstance(array.array('B'), MutableSequence)} true, since 3.14 has no
     * {@code MutableSequence.__subclasshook__} of its own to answer for a C type. The lookup is not
     * guarded, so an import with {@code MutableSequence} deleted fails the way gh-153210 made the
     * reference fail rather than silently leaving the type unregistered.
     */
    private static void registerAsMutableSequence() {
        PyObject abc;
        try {
            abc = PyRuntime.importModule("collections.abc");
        } catch (PyRaise missing) {
            // A run with no standard library at all has no `collections.abc` to register against.
            // The reference's C module would fail there too, but this runtime is often used without
            // one, and the registration is the only thing `array` needs from it.
            if (missing.value().exceptionType() == ExceptionTypes.MODULE_NOT_FOUND_ERROR) {
                return;
            }
            throw missing;
        }
        // The lookup is not guarded: an import with `MutableSequence` deleted fails the way gh-153210
        // made the reference fail rather than silently leaving the type unregistered.
        PyObject base = Slots.getAttr(abc, "MutableSequence");
        Slots.call(Slots.getAttr(base, "register"), new PyObject[] {PyType.ARRAY}, null);
    }
}
