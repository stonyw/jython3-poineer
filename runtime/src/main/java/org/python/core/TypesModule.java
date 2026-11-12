package org.python.core;

/**
 * The {@code _types} module: the names the reference files the interpreter's own types under, as the
 * bare aliases {@code types} re-exports.
 *
 * <p>Every row here is a type this runtime already declares; the module exists so that code which asks
 * for {@code _types.FunctionType} — or, more often, {@code types.FunctionType} — reaches the same object
 * the interpreter uses for the check. Four rows name types nothing constructs yet
 * ({@code AsyncGeneratorType}, {@code CapsuleType}, {@code CoroutineType} and {@code UnionType}); they
 * are filed so a program can name and compare them, and refuse construction.
 */
final class TypesModule {

    private TypesModule() {
    }

    private static final String DOC = "Define names for built-in types.";

    /** The rows, in the order {@code _types.__dict__} lists them in the reference. */
    static PyModule newModule() {
        PyModule module = new PyModule("_types");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_types");
        module.set("AsyncGeneratorType", PyType.ASYNC_GENERATOR);
        module.set("BuiltinFunctionType", PyType.BUILTIN_FUNCTION);
        module.set("BuiltinMethodType", PyType.BUILTIN_FUNCTION);
        module.set("CapsuleType", PyType.CAPSULE);
        module.set("CellType", PyType.CELL);
        module.set("ClassMethodDescriptorType", PyType.CLASS_METHOD_DESCRIPTOR);
        module.set("CodeType", PyType.CODE);
        module.set("CoroutineType", PyType.COROUTINE);
        module.set("EllipsisType", PyType.ELLIPSIS);
        module.set("FrameType", PyType.FRAME);
        module.set("FunctionType", PyType.FUNCTION);
        module.set("GeneratorType", PyType.GENERATOR);
        module.set("GenericAlias", PyType.GENERIC_ALIAS);
        module.set("GetSetDescriptorType", PyType.GETSET_DESCRIPTOR);
        module.set("LambdaType", PyType.FUNCTION);
        module.set("MappingProxyType", PyType.MAPPING_PROXY);
        module.set("MemberDescriptorType", PyType.MEMBER_DESCRIPTOR);
        module.set("MethodDescriptorType", PyType.METHOD_DESCRIPTOR);
        module.set("MethodType", PyType.METHOD);
        module.set("MethodWrapperType", PyType.METHOD_WRAPPER);
        module.set("ModuleType", PyType.MODULE);
        module.set("NoneType", PyType.NONE);
        module.set("NotImplementedType", PyType.NOTIMP);
        module.set("SimpleNamespace", PyType.NAMESPACE);
        module.set("TracebackType", PyType.TRACEBACK);
        module.set("UnionType", PyType.UNION_TYPE);
        module.set("WrapperDescriptorType", PyType.WRAPPER_DESCRIPTOR);
        return module;
    }
}
