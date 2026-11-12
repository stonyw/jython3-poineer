package org.python.core;

/**
 * The {@code _contextvars} module and the {@code contextvars} wrapper that serves as the public name.
 *
 * <p>CPython's {@code _contextvars} is a C module and {@code contextvars.py} is nine lines that
 * re-export it. That file cannot load here yet: it imports {@code _collections_abc}, which reaches an
 * {@code async def} before the class statements M6-a has not written, so importing the reference file
 * raises {@code SyntaxError}. The wrapper below is the same surface installed twice, once under each
 * name, with the type objects and {@code copy_context} shared so that
 * {@code contextvars.ContextVar is _contextvars.ContextVar} holds.
 */
final class ContextvarsModule {

    private static final PyNativeFunction COPY_CONTEXT = new PyNativeFunction("copy_context",
            (args, kwNames) -> {
                Arguments.none("_contextvars.copy_context()", args, kwNames);
                return PyContext.current().copy();
            });

    private ContextvarsModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_contextvars");
        module.set("__doc__", PyStr.of("Context Variables"));
        install(module);
        return module;
    }

    /**
     * {@code contextvars}: the same four names, its own {@code __all__}, and no docstring, which is
     * what the reference file leaves {@code __doc__} as.
     */
    static PyModule newWrapper() {
        PyModule module = new PyModule("contextvars");
        module.set("__all__", PyTuple.of(
                PyStr.of("Context"),
                PyStr.of("ContextVar"),
                PyStr.of("Token"),
                PyStr.of("copy_context")));
        install(module);
        return module;
    }

    private static void install(PyModule module) {
        module.set("Context", PyType.CONTEXT);
        module.set("ContextVar", PyType.CONTEXT_VAR);
        module.set("Token", PyType.CONTEXT_TOKEN);
        module.set("copy_context", COPY_CONTEXT);
    }
}
