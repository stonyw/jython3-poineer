package org.python.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code _testinternalcapi}: the interpreter-internal probes the test suite imports by name.
 *
 * <p>This is a test-support module, and the only reason to build one is to let a test run honestly or
 * skip honestly — not to make it pass. So only the questions whose answer this runtime really has are
 * filed here. {@code has_inline_values} follows the value's own state: a value of a class with the
 * inline-values layout starts with the array and keeps it until its dictionary materializes or is
 * detached, which is the same lifecycle the reference's {@code PyDictValues.valid} records.
 * {@code get_recursion_depth}
 * answers the number of Python frames standing on the caller, counted along the same {@code f_back}
 * chain {@code sys._getframe()} walks. {@code get_static_builtin_types} answers the types this runtime
 * files under {@code builtins}, which is the same set {@code test.support}'s own naive fallback walks.
 *
 * <p>Two names exist only to keep a caller's documented fallback reachable now that the module itself
 * imports: {@code identify_type_slot_wrappers} raises {@code NotImplementedError}, exactly what
 * {@code test.support} raised when the module was missing, so its attribute-scanning path still runs;
 * {@code run_in_subinterp_with_config} raises {@code NotImplementedError} because this runtime has no
 * sub-interpreters to run code in.
 *
 * <p>Everything else the reference module exposes — the GC header sizes, the C recursion budget, the
 * instruction-sequence builders — describes CPython's C layout. Those names are deliberately absent: a
 * caller that imports one by name gets the {@code ImportError} its own fallback already handles, and
 * {@code test.support} then skips or uses its documented alternative rather than being told a
 * comfortable lie.
 */
final class InternalCapiModule {

    private InternalCapiModule() {
    }

    private static final String DOC = "Test module for the internal C API.";

    static PyModule newModule() {
        PyModule module = new PyModule("_testinternalcapi");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_testinternalcapi");
        module.set(
                "has_inline_values",
                PyNativeFunction.positional("has_inline_values", args -> {
                    Arguments.exactlyOne("has_inline_values()", args, null);
                    // A value of a class whose layout includes inline values keeps the array until it
                    // grows past the class's key slots or its dictionary is detached; every other value
                    // has none, which is also the truth for a class that declares `__slots__`.
                    return PyBool.of(args[0] instanceof PyInstance held && held.hasInlineValues());
                }).documented(
                        "has_inline_values($module, obj, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return whether *obj* has an allocated inline values array."));
        module.set(
                "get_recursion_depth",
                PyNativeFunction.positional("get_recursion_depth", args -> {
                    Arguments.none("get_recursion_depth()", args, null);
                    int depth = 0;
                    for (Frame frame = Interpreter.currentFrame(); frame != null;
                            frame = frame.back()) {
                        depth++;
                    }
                    return PyInt.of(depth);
                }).documented(
                        "get_recursion_depth($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the Python recursion depth of the calling frame."));
        module.set(
                "get_static_builtin_types",
                PyNativeFunction.positional("get_static_builtin_types", args -> {
                    Arguments.none("get_static_builtin_types()", args, null);
                    List<PyObject> types = new ArrayList<>();
                    Set<PyType> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Map.Entry<PyObject, PyObject> entry :
                            PyRuntime.builtins().getItems().entrySet()) {
                        if (entry.getValue() instanceof PyType type
                                && type.moduleName().equals("builtins")
                                && seen.add(type)) {
                            types.add(type);
                        }
                    }
                    return PyList.of(types.toArray(new PyObject[0]));
                }).documented(
                        "get_static_builtin_types($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return a list of the built-in types of this interpreter."));
        module.set(
                "identify_type_slot_wrappers",
                PyNativeFunction.positional("identify_type_slot_wrappers", args -> {
                    Arguments.none("identify_type_slot_wrappers()", args, null);
                    // Slot wrappers are C function descriptors attached to static types. This
                    // runtime's types answer through Java methods and keep no such table, so there is
                    // nothing here to name. The error is the one `test.support` itself raises when
                    // the module is missing, which keeps its attribute-scanning fallback in charge.
                    throw Slots.notImplemented("type slot wrapper identification");
                }).documented(
                        "identify_type_slot_wrappers($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the names of the types' slot wrappers."));
        module.set(
                "run_in_subinterp_with_config",
                PyNativeFunction.positional("run_in_subinterp_with_config", args -> {
                    Arguments.exactly("run_in_subinterp_with_config()", args, null, 2);
                    // One process runs one interpreter here (see PyRuntime); there is no second
                    // interpreter to hand this code to, so the call cannot be answered.
                    throw Slots.notImplemented("running code in a sub-interpreter");
                }).documented(
                        "run_in_subinterp_with_config($module, code, config, /)\n"
                                + "--\n"
                                + "\n"
                                + "Run *code* in a sub-interpreter. Return 0 on success."));
        return module;
    }
}
