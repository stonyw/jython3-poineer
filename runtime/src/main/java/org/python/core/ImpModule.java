package org.python.core;

/**
 * {@code _imp}: the low-level import machinery {@code importlib} is written on, as far as this
 * runtime has it.
 *
 * <p>The reference's module is the C half of {@code Lib/importlib}, and this runtime's importer is
 * written in Java instead, so most of the reference's surface names something that does not exist
 * here. What is answered is what a program or the test suite actually asks: the two test-only
 * overrides (from {@code test.support.import_helper}), the extension-suffix list this runtime can
 * honestly call empty because it loads no native extensions, and the two predicates over the module
 * tables the importer already owns.
 *
 * <p>The overrides are honoured as values rather than as behaviour: there are no frozen modules and
 * no sub-interpreters to switch, so {@code _override_frozen_modules_for_tests} records the mode it was
 * handed and returns None, while {@code _override_multi_interp_extensions_check} refuses in the main
 * interpreter with the reference's own words.
 */
final class ImpModule {

    private ImpModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_imp");
        module.set("__doc__", PyStr.of("(Extremely) low-level import machinery bits as used by importlib."));
        PyRuntime.markBuiltIn(module, "_imp");
        module.set(
                "_override_frozen_modules_for_tests",
                PyNativeFunction.positional("_override_frozen_modules_for_tests", args -> {
                    Arguments.exactlyOne("_imp._override_frozen_modules_for_tests()", args, null);
                    Numbers.asIndex(args[0]);
                    // Nothing here is frozen, so the mode has nothing to switch; the reference's own
                    // function answers None as well.
                    return PyNone.INSTANCE;
                }).documented(
                        "_override_frozen_modules_for_tests($module, override, /)\n"
                                + "--\n"
                                + "\n"
                                + "(internal-only) Override PyConfig.use_frozen_modules.\n"
                                + "\n"
                                + "(-1: \"off\", 1: \"on\", 0: no override)\n"
                                + "See frozen_modules() in Lib/test/support/import_helper.py."));
        module.set(
                "_override_multi_interp_extensions_check",
                PyNativeFunction.positional("_override_multi_interp_extensions_check", args -> {
                    Arguments.exactlyOne("_imp._override_multi_interp_extensions_check()", args, null);
                    Numbers.asIndex(args[0]);
                    throw Slots.runtimeError(
                            "_imp._override_multi_interp_extensions_check() cannot be used in the main interpreter");
                }).documented(
                        "_override_multi_interp_extensions_check($module, override, /)\n"
                                + "--\n"
                                + "\n"
                                + "(internal-only) Override PyInterpreterConfig.check_multi_interp_extensions.\n"
                                + "\n"
                                + "(-1: \"never\", 1: \"always\", 0: no override)"));
        module.set(
                "extension_suffixes",
                PyNativeFunction.positional("extension_suffixes", args -> {
                    Arguments.none("_imp.extension_suffixes()", args, null);
                    // No native extension can be loaded, so there is no suffix to recognise.
                    return PyList.empty();
                }).documented(
                        "extension_suffixes($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Returns the list of file suffixes used to identify extension modules."));
        module.set(
                "is_builtin",
                PyNativeFunction.positional("is_builtin", args -> {
                    Arguments.exactlyOne("_imp.is_builtin()", args, null);
                    return PyBool.of(PyRuntime.isBuiltinModuleName(text("is_builtin", args[0])));
                }).documented(
                        "is_builtin($module, name, /)\n"
                                + "--\n"
                                + "\n"
                                + "Returns True if the module name corresponds to a built-in module."));
        module.set(
                "is_frozen",
                PyNativeFunction.positional("is_frozen", args -> {
                    Arguments.exactlyOne("_imp.is_frozen()", args, null);
                    text("is_frozen", args[0]);
                    return PyBool.FALSE;
                }).documented(
                        "is_frozen($module, name, /)\n"
                                + "--\n"
                                + "\n"
                                + "Returns True if the module name corresponds to a frozen module."));
        return module;
    }

    /** The name argument, refused the way {@code _imp}'s own C functions refuse a non-string. */
    private static String text(String function, PyObject value) {
        if (!(value instanceof PyStr name)) {
            throw Slots.typeError(
                    function + "() argument must be str, not %s", Slots.argumentName(value));
        }
        return name.getValue();
    }
}
