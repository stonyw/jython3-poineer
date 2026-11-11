package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code _frozen_importlib.BuiltinImporter}: the loader the reference files as the value of
 * {@code builtins.__loader__} and {@code sys.__loader__}.
 *
 * <p>Only the half of the loader that can be answered without a module spec is here. A name that is
 * not in {@code sys.builtin_module_names} is refused the way the reference refuses it, and the three
 * reads that are nothing but a constant — no code, no source, not a package — answer that constant.
 * Everything that would need a {@code ModuleSpec} or a module actually built from one is left to the
 * import system of M4, and says so rather than pretending.
 *
 * <p>The class dictionary is built the long way, key by key, because {@code vars()} of the reference
 * class is a measured table: same fourteen names, same order, same docstrings.
 */
final class BuiltinImporter {

    /** The class itself, made the first time a module asks for a loader. */
    private static PyType type;

    private BuiltinImporter() {
    }

    static synchronized PyType type() {
        if (type == null) {
            type = define();
        }
        return type;
    }

    private static PyType define() {
        PyDict namespace = PyDict.empty();
        namespace.put(PyStr.interned("__module__"), PyStr.of("_frozen_importlib"));
        // Named like the class body that defines it; `Classes.declare` drops this key, as a heap type's
        // dictionary in 3.14 holds no `__qualname__`, which is what makes the measured order below.
        namespace.put(PyStr.interned("__qualname__"), PyStr.of("BuiltinImporter"));
        namespace.put(PyStr.interned("__firstlineno__"), PyInt.of(974));
        namespace.put(
                PyStr.interned("__doc__"),
                PyStr.of(
                        "Meta path import for built-in modules.\n"
                                + "\n"
                                + "All methods are either class or static methods to avoid the need to\n"
                                + "instantiate the class.\n"
                                + "\n"));
        namespace.put(PyStr.interned("_ORIGIN"), PyStr.of("built-in"));
        namespace.put(
                PyStr.interned("find_spec"),
                new PyClassMethod(new PyNativeFunction("find_spec", BuiltinImporter::findSpec)));
        namespace.put(
                PyStr.interned("create_module"),
                new PyClassMethod(
                        new PyNativeFunction("create_module", BuiltinImporter::createModule)
                                .documented("Create a built-in module")));
        namespace.put(
                PyStr.interned("exec_module"),
                new PyStaticMethod(
                        new PyNativeFunction("exec_module", BuiltinImporter::execModule)
                                .documented("Exec a built-in module")));
        namespace.put(
                PyStr.interned("get_code"),
                new PyClassMethod(
                        new PyNativeFunction("get_code", BuiltinImporter::getCode)
                                .documented(
                                        "Return None as built-in modules do not have code objects.")));
        namespace.put(
                PyStr.interned("get_source"),
                new PyClassMethod(
                        new PyNativeFunction("get_source", BuiltinImporter::getSource)
                                .documented(
                                        "Return None as built-in modules do not have source code.")));
        namespace.put(
                PyStr.interned("is_package"),
                new PyClassMethod(
                        new PyNativeFunction("is_package", BuiltinImporter::isPackage)
                                .documented(
                                        "Return False as built-in modules are never packages.")));
        namespace.put(
                PyStr.interned("load_module"),
                new PyClassMethod(
                        new PyNativeFunction("_load_module_shim", BuiltinImporter::loadModule)
                                .documented(
                                        "Load the specified module into sys.modules and return it.\n"
                                                + "\n"
                                                + "This method is deprecated.  Use loader.exec_module()"
                                                + " instead.\n"
                                                + "\n")));
        namespace.put(PyStr.interned("__static_attributes__"), PyTuple.of());
        return (PyType)
                Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {
                            PyStr.of("BuiltinImporter"), PyTuple.of(PyType.OBJECT), namespace
                        },
                        null);
    }

    /**
     * {@code find_spec(fullname, path=None, target=None)} of the load path: {@code None} for a name
     * the built-in table does not hold, and the spec for one it does — which is M4's, because a spec
     * is the type the import system brings with it.
     */
    private static PyObject findSpec(PyObject[] args, String[] kwNames) {
        PyObject[] values =
                bind(
                        "BuiltinImporter.find_spec",
                        args,
                        kwNames,
                        new String[] {"cls", "fullname", "path", "target"},
                        2);
        PyObject fullname = values[1];
        if (!(fullname instanceof PyStr)) {
            // `_imp.is_builtin` names itself here rather than the class, and names the argument's type
            // as an argument rather than as a type: `not int`, `not None`.
            throw Slots.typeError(
                    "is_builtin() argument must be str, not %s", Slots.argumentName(fullname));
        }
        if (PyRuntime.isBuiltinModuleName(((PyStr) fullname).getValue())) {
            throw importSystem("find_spec");
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code create_module(spec)}: reads {@code spec.name} before anything else, which is why a value
     * with no name refuses as an attribute error and a name outside the built-in table as an import
     * error. Building the module the name stands for needs the import system (M4).
     */
    private static PyObject createModule(PyObject[] args, String[] kwNames) {
        PyObject[] values =
                bind(
                        "BuiltinImporter.create_module",
                        args,
                        kwNames,
                        new String[] {"cls", "spec"},
                        2);
        PyObject name = Slots.getAttr(values[1], "name");
        if (!builtin(name)) {
            throw notBuiltin(name);
        }
        throw importSystem("create_module");
    }

    /** {@code exec_module(module)}: nothing to run, because a built-in module has no code. */
    private static PyObject execModule(PyObject[] args, String[] kwNames) {
        bind("BuiltinImporter.exec_module", args, kwNames, new String[] {"module"}, 1);
        return PyNone.INSTANCE;
    }

    /** {@code get_code(fullname)}: a constant {@code None} once the name is known to be built in. */
    private static PyObject getCode(PyObject[] args, String[] kwNames) {
        return absent(
                bind(
                        "BuiltinImporter.get_code",
                        args,
                        kwNames,
                        new String[] {"cls", "fullname"},
                        2)[1],
                "get_code");
    }

    /** {@code get_source(fullname)}: the same constant, for the same reason. */
    private static PyObject getSource(PyObject[] args, String[] kwNames) {
        return absent(
                bind(
                        "BuiltinImporter.get_source",
                        args,
                        kwNames,
                        new String[] {"cls", "fullname"},
                        2)[1],
                "get_source");
    }

    /** {@code is_package(fullname)}: {@code False}, because a built-in module is never a package. */
    private static PyObject isPackage(PyObject[] args, String[] kwNames) {
        PyObject fullname =
                bind(
                        "BuiltinImporter.is_package",
                        args,
                        kwNames,
                        new String[] {"cls", "fullname"},
                        2)[1];
        requireBuiltin(fullname);
        return PyBool.FALSE;
    }

    /**
     * {@code load_module(fullname)}: the deprecated shim, which reaches {@code is_package} through
     * {@code spec_from_loader} and therefore refuses a name outside the built-in table before it can
     * load anything.
     */
    private static PyObject loadModule(PyObject[] args, String[] kwNames) {
        PyObject fullname =
                bind(
                        "_load_module_shim",
                        args,
                        kwNames,
                        new String[] {"self", "fullname"},
                        2)[1];
        requireBuiltin(fullname);
        throw importSystem("load_module");
    }

    private static PyObject absent(PyObject fullname, String method) {
        requireBuiltin(fullname);
        return PyNone.INSTANCE;
    }

    /** {@code _requires_builtin}: a name the built-in table does not hold is an import error. */
    private static void requireBuiltin(PyObject fullname) {
        if (!builtin(fullname)) {
            throw notBuiltin(fullname);
        }
    }

    private static boolean builtin(PyObject name) {
        return name instanceof PyStr text && PyRuntime.isBuiltinModuleName(text.getValue());
    }

    /**
     * {@code ImportError("%R is not a built-in module", name=...)} — the message quotes the name the
     * way {@code %R} does and the value rides along as {@code e.name} unchanged.
     */
    private static PyRaise notBuiltin(PyObject fullname) {
        PyBaseException error =
                (PyBaseException)
                        Slots.call(
                                ExceptionTypes.IMPORT_ERROR,
                                new PyObject[] {
                                    PyStr.of(Slots.repr(fullname) + " is not a built-in module"),
                                    fullname
                                },
                                PyBoundMethod.keywordTuple(new String[] {"name"}));
        return PyRaise.of(error);
    }

    /** The one thing this loader cannot answer before M4 has given it a {@code ModuleSpec}. */
    private static PyRaise importSystem(String method) {
        return Slots.notImplemented(
                "BuiltinImporter."
                        + method
                        + " for a built-in module (needs ModuleSpec, which the import system adds"
                        + " at M4)");
    }

    /**
     * Bind a frozen function's arguments the way Python's own binder does: positional first, then
     * keywords by name, then the reports for a name given twice, a name nobody declared, and the
     * required names still missing.
     *
     * <p>{@code names} includes the receiver a classmethod prepends, because the reference counts it
     * in "takes N positional arguments".
     */
    private static PyObject[] bind(
            String qualified,
            PyObject[] args,
            String[] kwNames,
            String[] names,
            int required) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        if (positional > names.length) {
            String shape =
                    required == names.length
                            ? names.length + " positional argument" + (names.length == 1 ? "" : "s")
                            : "from " + required + " to " + names.length + " positional arguments";
            throw Slots.typeError(
                    "%s() takes %s but %d were given", qualified, shape, positional);
        }
        PyObject[] values = new PyObject[names.length];
        System.arraycopy(args, 0, values, 0, positional);
        for (int i = 0; i < keywords; i++) {
            int index = indexOf(names, kwNames[i]);
            if (index < 0) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", qualified, kwNames[i]);
            }
            if (values[index] != null) {
                throw Slots.typeError(
                        "%s() got multiple values for argument '%s'", qualified, kwNames[i]);
            }
            values[index] = args[positional + i];
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < required; i++) {
            if (values[i] == null) {
                missing.add(names[i]);
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) {
                    list.append(i == missing.size() - 1 ? " and " : ", ");
                }
                list.append('\'').append(missing.get(i)).append('\'');
            }
            throw Slots.typeError(
                    "%s() missing %d required positional argument%s: %s",
                    qualified,
                    missing.size(),
                    missing.size() == 1 ? "" : "s",
                    list);
        }
        return values;
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
