package org.python.core;

/**
 * The {@code __import__} builtin: the five parameters it binds, the branch that turns a relative name
 * into an absolute one, and the walk over its from-list.
 *
 * <p>The two import statements are compiled to {@code IMPORT_NAME} and {@code IMPORT_FROM}, which call
 * {@link #importName} and {@link #cannotImport} here, so the builtin and the statements answer with one
 * implementation. What the builtin itself never raises — the {@code cannot import name} complaint — is
 * left to {@code IMPORT_FROM}, because a from-list that names something absent is silent when it is
 * passed to {@code __import__} by hand.
 */
final class Import {

    /** The parameter list, in the order {@code __text_signature__} prints it. */
    private static final String[] PARAMETERS = {
        "name", "globals", "locals", "fromlist", "level"
    };

    private Import() {
    }

    /**
     * {@code __import__}: bind the arguments, then import.
     *
     * <p>The refusals are the ones CPython's argument parser words for a clinic function of five
     * optional-after-one parameters, which is why the positions it names are counted from one.
     */
    static PyObject builtin(PyObject[] args, String[] kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - keywords;
        if (positional > PARAMETERS.length) {
            throw Slots.typeError(
                    "__import__() takes at most %d arguments (%d given)",
                    PARAMETERS.length, args.length);
        }
        PyObject[] values = new PyObject[PARAMETERS.length];
        System.arraycopy(args, 0, values, 0, positional);
        for (int i = 0; i < keywords; i++) {
            String keyword = kwNames[i];
            int slot = slotOf(keyword);
            if (slot < 0) {
                throw Slots.typeError(
                        "__import__() got an unexpected keyword argument '%s'", keyword);
            }
            if (slot < positional) {
                throw Slots.typeError(
                        "argument for __import__() given by name ('%s') and position (%d)",
                        keyword, slot + 1);
            }
            values[slot] = args[positional + i];
        }
        if (values[0] == null) {
            throw Slots.typeError("__import__() missing required argument 'name' (pos 1)");
        }
        return importName(
                values[0],
                values[1] == null ? PyNone.INSTANCE : values[1],
                values[3] == null ? PyNone.INSTANCE : values[3],
                values[4] == null ? PyInt.of(0) : values[4]);
    }

    private static int slotOf(String keyword) {
        for (int i = 0; i < PARAMETERS.length; i++) {
            if (PARAMETERS[i].equals(keyword)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code PyImport_ImportModuleLevelObject}: the name, made absolute when the level is positive,
     * loaded, and either the leaf or — for a plain {@code import a.b} — the head handed back.
     *
     * @param nameValue the module name, which has to be a string
     * @param globals the caller's globals, read only to find the package a relative name is relative to
     * @param fromlist the names a from-import asked for; a null or empty one stands for a plain import
     * @param levelValue zero for an absolute import, one or more for a relative one
     */
    static PyObject importName(
            PyObject nameValue, PyObject globals, PyObject fromlist, PyObject levelValue) {
        String requested = stringName(nameValue);
        if (requested.indexOf('\0') >= 0) {
            // A NUL cannot name a path, and CPython's finders answer the attempt as a module that is
            // simply not there; letting the character reach a file name would make the JVM refuse it
            // before any import machinery sees it. The message reprs the name, so the NUL is escaped.
            throw PyRaise.of((PyBaseException) Slots.call(
                    ExceptionTypes.MODULE_NOT_FOUND_ERROR,
                    new PyObject[] {
                        PyStr.of("No module named " + PyStr.of(requested).repr()),
                        PyStr.of(requested)
                    },
                    PyBoundMethod.keywordTuple(new String[] {"name"})));
        }
        int level = levelOf(levelValue);
        String base = "";
        if (level > 0) {
            base = packageBase(globals, level);
        } else if (requested.isEmpty()) {
            throw Slots.valueError("Empty module name");
        }
        String name = level > 0
                ? (requested.isEmpty() ? base : base + "." + requested)
                : requested;
        PyObject module = PyRuntime.importModule(name);
        // What tells a plain import from a from-import is the truth of the from-list, not its size: an
        // empty tuple, an empty list and None all stand for "import name", while an object of any other
        // kind stands for a from-import — even one that cannot be iterated, whose complaint is raised
        // only later and only when the module is a package to walk.
        if (fromlist == null || !Slots.truth(fromlist)) {
            // The head a plain dotted import hands back is the name as the caller asked for it: the
            // first component of a relative name, put under the package it was resolved against. A
            // name with no dot at all is its own answer, even when the resolution added dots — which
            // is why `__import__('sibling', level=1)` answers the module while `import a.b` answers `a`.
            int dot = requested.indexOf('.');
            if (dot < 0) {
                return module;
            }
            String head = requested.substring(0, dot);
            return PyRuntime.importModule(level > 0 ? base + "." + head : head);
        }
        handleFromlist(module, fromlist);
        return module;
    }

    private static String stringName(PyObject nameValue) {
        if (!(nameValue instanceof PyStr name)) {
            throw Slots.typeError("module name must be a string");
        }
        return name.getValue();
    }

    private static int levelOf(PyObject levelValue) {
        int level = Numbers.cInteger(levelValue);
        if (level < 0) {
            throw Slots.valueError("level must be >= 0");
        }
        return level;
    }

    /**
     * {@code _calc___package__} and the depth walk beside it: the package a relative name is relative to.
     *
     * <p>{@code __package__} wins when it is there and not {@code None}; otherwise {@code __spec__.parent}
     * is asked, and only when both are absent does the fallback run — the module's own {@code __name__},
     * minus its last component unless the module has a {@code __path__} and so is the package itself.
     * One level up is that package's parent, and a walk past the top-level one is an error.
     */
    private static String packageBase(PyObject globals, int level) {
        if (!(globals instanceof PyDict namespace)) {
            throw Slots.typeError("globals must be a dict");
        }
        String base = packageOf(namespace);
        if (base.isEmpty()) {
            throw Slots.importError("attempted relative import with no known parent package");
        }
        for (int i = 0; i < level - 1; i++) {
            int dot = base.lastIndexOf('.');
            if (dot < 0) {
                throw Slots.importError("attempted relative import beyond top-level package");
            }
            base = base.substring(0, dot);
        }
        return base;
    }

    private static String packageOf(PyDict namespace) {
        PyObject packageValue = namespace.get(PyStr.interned("__package__"));
        if (packageValue != null && packageValue != PyNone.INSTANCE) {
            if (!(packageValue instanceof PyStr packageName)) {
                throw Slots.typeError("package must be a string");
            }
            return packageName.getValue();
        }
        PyObject spec = namespace.get(PyStr.interned("__spec__"));
        if (spec != null && spec != PyNone.INSTANCE) {
            PyObject parent = Slots.findAttr(spec, "parent");
            return parent instanceof PyStr text ? text.getValue() : "";
        }
        // Neither package source is there, so the name-and-path fallback below runs; CPython says so
        // with an ImportWarning before it does, which is how a relative import from a module with
        // neither `__package__` nor `__spec__` is diagnosable. The warning is filtered out by
        // default, so it never changes the answer unless a caller is recording warnings.
        try {
            PyObject warnings = PyRuntime.importModule("warnings");
            Slots.call(
                    Slots.getAttr(warnings, "warn"),
                    new PyObject[] {
                        PyStr.of(
                                "can't resolve package from __spec__ or __package__,"
                                        + " falling back on __name__ and __path__"),
                        ExceptionTypes.IMPORT_WARNING
                    },
                    null);
        } catch (PyRaise unwarnable) {
            // The reference's C warning path imports `warnings` on demand too, so a run whose
            // `sys.path` has no stdlib cannot deliver this one either — the import itself fails
            // before any filter could be consulted. Nothing else is swallowed.
            PyObject missing = Slots.findAttr(unwarnable.value(), "name");
            if (unwarnable.value().getType() != ExceptionTypes.MODULE_NOT_FOUND_ERROR
                    || !(missing instanceof PyStr text)
                    || !text.getValue().equals("warnings")) {
                throw unwarnable;
            }
        }
        PyObject moduleName = namespace.get(PyStr.interned("__name__"));
        if (moduleName == null) {
            throw Slots.keyError(PyStr.of("'__name__' not in globals"));
        }
        if (!(moduleName instanceof PyStr moduleText)) {
            throw Slots.typeError("__name__ must be a string");
        }
        String name = moduleText.getValue();
        if (namespace.get(PyStr.interned("__path__")) != null) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    /**
     * {@code _handle_fromlist}: load the submodules a from-list names, so that the attribute
     * {@code IMPORT_FROM} reads next is there.
     *
     * <p>Only a package has anything to walk: every name of a module's own is already an attribute, and
     * CPython returns before it even checks that the entries are strings — which is why
     * {@code __import__('helper', fromlist=(3,))} is not an error while the same list over a package is.
     *
     * <p>A submodule that is not there is not an error here either. The from-import that follows names it
     * in the {@code cannot import name} complaint; a call to {@code __import__} by hand simply gets its
     * module back.
     */
    private static void handleFromlist(PyObject module, PyObject fromlist) {
        if (Slots.findAttr(module, "__path__") == null) {
            return;
        }
        String moduleName = moduleName(module);
        PyIterator names = Slots.iterator(fromlist);
        PyObject item;
        while ((item = names.nextOrNull()) != null) {
            if (!(item instanceof PyStr text)) {
                throw Slots.typeError(
                        "Item in ``from list'' must be str, not %s", item.getType().name());
            }
            String wanted = text.getValue();
            if (wanted.equals("*")) {
                handleStar(module, moduleName);
            } else if (Slots.findAttr(module, wanted) == null) {
                loadSubmodule(moduleName, wanted);
            }
        }
    }

    /** The {@code *} of a from-list: every name the package's own {@code __all__} names. */
    private static void handleStar(PyObject module, String moduleName) {
        PyObject published = Slots.findAttr(module, "__all__");
        if (published == null) {
            return;
        }
        PyIterator names = Slots.iterator(published);
        PyObject item;
        while ((item = names.nextOrNull()) != null) {
            if (!(item instanceof PyStr text)) {
                throw Slots.typeError(
                        "Item in %s.__all__ must be str, not %s",
                        moduleName, item.getType().name());
            }
            if (Slots.findAttr(module, text.getValue()) == null) {
                loadSubmodule(moduleName, text.getValue());
            }
        }
    }

    /**
     * Import {@code module.attribute} and forget the complaint when the submodule is what is missing.
     *
     * <p>That distinction is CPython's: a {@code ModuleNotFoundError} naming exactly the module that was
     * asked for means there is no such submodule, which the caller reports its own way; anything deeper —
     * a failure while running the submodule — is a real one and travels on.
     */
    private static void loadSubmodule(String moduleName, String attribute) {
        if (moduleName == null || moduleName.isEmpty()) {
            return;
        }
        String dotted = moduleName + "." + attribute;
        try {
            PyRuntime.importModule(dotted);
        } catch (PyRaise missing) {
            if (!missing.value().exceptionType().matches(ExceptionTypes.MODULE_NOT_FOUND_ERROR)) {
                throw missing;
            }
            PyObject name = Slots.findAttr(missing.value(), "name");
            if (!(name instanceof PyStr text && text.getValue().equals(dotted))) {
                throw missing;
            }
        }
    }

    /** {@code module.__name__}, or an empty string for an object that is not a module. */
    static String moduleName(PyObject module) {
        PyObject name = Slots.findAttr(module, "__name__");
        return name instanceof PyStr text ? text.getValue() : "";
    }

    /**
     * The {@code ImportError} an {@code IMPORT_FROM} raises when the name is neither an attribute nor a
     * submodule: {@code cannot import name 'nope' from 'helper' (/path/helper.py)}.
     *
     * <p>The three fields it fills are the ones a program reads back — {@code e.name}, {@code e.path} and
     * {@code e.name_from} — and the location is the one CPython prints for a module with no
     * {@code __file__}.
     */
    static PyRaise cannotImport(PyObject module, String attribute) {
        String moduleName = moduleName(module);
        if (moduleName.isEmpty()) {
            moduleName = module.getType().name();
        }
        PyObject file = Slots.findAttr(module, "__file__");
        String location = file instanceof PyStr text
                ? "(" + text.getValue() + ")"
                : "(unknown location)";
        String message = "cannot import name '"
                + attribute
                + "' from '"
                + moduleName
                + "' "
                + location;
        PyBaseException error = (PyBaseException) Slots.call(
                ExceptionTypes.IMPORT_ERROR,
                new PyObject[] {
                    PyStr.of(message),
                    PyStr.of(moduleName),
                    file == null ? PyNone.INSTANCE : file,
                    PyStr.of(attribute)
                },
                PyBoundMethod.keywordTuple(new String[] {"name", "path", "name_from"}));
        return PyRaise.of(error);
    }
}
