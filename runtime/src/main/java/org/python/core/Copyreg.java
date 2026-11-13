package org.python.core;

/**
 * {@code copyreg}, built only when the interpreter has no module of that name to import.
 *
 * <p>{@code object.__reduce_ex__} is not written in C. The reference files its two recipes in {@code
 * copyreg.py}: below protocol two the whole walk is {@code copyreg._reduce_ex}, above it the new call is
 * {@code copyreg.__newobj__} or {@code copyreg.__newobj_ex__}, and the slots half of the state is {@code
 * copyreg._slotnames}. Those names are read back as functions whenever a value is reduced, so a runtime
 * that cannot import {@code copyreg} cannot reduce anything at all — which is what a launcher run with no
 * stdlib used to be. The five are built here instead, in the shapes the reference's Python defines.
 *
 * <p>A real module always wins. {@link #module()} asks the import machinery first and only builds these
 * rows when the answer is "no such module"; the built module is never entered in {@code sys.modules}. That
 * keeps the reference's own {@code copyreg} — the one {@code pickle}, {@code copy} and every stdlib
 * module that registers a pickler write into — in charge whenever it exists, and makes this class a stand-in
 * for the missing standard library rather than a replacement for it. Only the five names the reduce path
 * reads are here; the registries {@code pickle} shares with the reference are not, because {@code pickle}
 * itself does not exist without a stdlib either.
 *
 * <p>The two recipes are the reference's, sentence for sentence, because {@code test_descr} compares the
 * tuples position by position and a corpus can print the refusal text: {@code _reduce_ex} finds the first
 * base that is not a heap type, carries that base's contents as the second argument, merges the instance
 * dictionary and the set slots into the state, and refuses a slots class that inherits {@code object.
 * __getstate__} below protocol two. {@code __newobj__} and {@code __newobj_ex__} build the value through
 * its class's own {@code __new__}, and {@code _reconstructor} rebuilds it from the base and the state.
 * {@code _slotnames} walks the order, skips {@code __dict__} and {@code __weakref__}, mangles a name that
 * starts with two underscores and does not end with two, and caches the answer on the class as {@code
 * __slotnames__} when it can.
 */
final class Copyreg {

    /** The module built when {@code copyreg} cannot be imported, or null before it is first asked for. */
    private static volatile PyModule built;

    private Copyreg() {
    }

    /**
     * The module the reduce path reads its five names from: the real {@code copyreg} when one is importable,
     * the one built here otherwise.
     */
    static PyObject module() {
        PyObject found = importable();
        if (found != null) {
            return found;
        }
        PyModule answer = built;
        if (answer == null) {
            synchronized (Copyreg.class) {
                answer = built;
                if (answer == null) {
                    answer = build();
                    built = answer;
                }
            }
        }
        return answer;
    }

    /**
     * {@code import copyreg}, with the absence of the module reported as null rather than as the failure.
     *
     * <p>Nothing is cached: a program is allowed to put a directory on {@code sys.path} after it has already
     * reduced something, and the next reduce then has to find the reference's module rather than this one.
     */
    private static PyObject importable() {
        try {
            return PyRuntime.importModule("copyreg");
        } catch (PyRaise missing) {
            if (missing.value().exceptionType().matches(ExceptionTypes.MODULE_NOT_FOUND_ERROR)) {
                return null;
            }
            throw missing;
        }
    }

    /** The function object a below-protocol-two recipe names in its first position. */
    static PyObject reconstructor() {
        return module().getAttr("_reconstructor");
    }

    private static PyModule build() {
        PyModule module = new PyModule("copyreg");
        module.set(
                "__doc__",
                PyStr.of(
                        "Helper to provide extensibility for pickle.\n\nThis is only useful to add"
                                + " pickle support for extension types defined in C\nlanguages."));
        module.set("_reconstructor", new PyNativeFunction("_reconstructor", Copyreg::reconstruct));
        module.set("__newobj__", new PyNativeFunction("__newobj__", Copyreg::newObject));
        module.set("__newobj_ex__", new PyNativeFunction("__newobj_ex__", Copyreg::newObjectEx));
        module.set("_slotnames", new PyNativeFunction("_slotnames", Copyreg::slotNames));
        module.set("_reduce_ex", new PyNativeFunction("_reduce_ex", Copyreg::reduceEx));
        return module;
    }

    /** {@code _reconstructor(cls, base, state)}: {@code base.__new__} and, when base wrote one, its init. */
    private static PyObject reconstruct(PyObject[] args, String[] kwNames) {
        PyObject cls = args[0];
        PyObject base = args[1];
        PyObject state = args[2];
        if (base == PyType.OBJECT) {
            return Slots.call(
                    Slots.findAttr(base, "__new__"), new PyObject[] {cls}, null);
        }
        PyObject made =
                Slots.call(Slots.findAttr(base, "__new__"), new PyObject[] {cls, state}, null);
        PyObject init = Slots.findAttr(base, "__init__");
        if (init != Slots.findAttr(PyType.OBJECT, "__init__")) {
            Slots.call(init, new PyObject[] {made, state}, null);
        }
        return made;
    }

    /** {@code __newobj__(cls, *args)}: the class's own {@code __new__} with the class in front. */
    private static PyObject newObject(PyObject[] args, String[] kwNames) {
        PyObject[] call = new PyObject[args.length];
        System.arraycopy(args, 0, call, 0, args.length);
        return Slots.call(Slots.findAttr(args[0], "__new__"), call, null);
    }

    /** {@code __newobj_ex__(cls, args, kwargs)}: the same call with the keywords the class asked for. */
    private static PyObject newObjectEx(PyObject[] args, String[] kwNames) {
        PyObject cls = args[0];
        PyTuple positional = (PyTuple) args[1];
        PyDict keywords = (PyDict) args[2];
        PyObject[] call = new PyObject[positional.size() + keywords.size() + 1];
        call[0] = cls;
        for (int i = 0; i < positional.size(); i++) {
            call[i + 1] = positional.at(i);
        }
        PyObject[] names = new PyObject[keywords.size()];
        int at = positional.size() + 1;
        int index = 0;
        for (java.util.Map.Entry<PyObject, PyObject> entry : keywords.getItems().entrySet()) {
            names[index++] = entry.getKey();
            call[at++] = entry.getValue();
        }
        return Slots.call(
                Slots.findAttr(cls, "__new__"), call, PyTuple.of(names));
    }

    /** {@code _slotnames(cls)}: the names a class's slots carry, cached on the class. */
    private static PyObject slotNames(PyObject[] args, String[] kwNames) {
        return TypeDict.slotNamesDirect((PyType) args[0]);
    }

    /** {@code _reduce_ex(self, proto)}: the recipe protocols below two are answered with. */
    private static PyObject reduceEx(PyObject[] args, String[] kwNames) {
        return TypeDict.legacyRecipe(args[0], Numbers.cInteger(args[1]), reconstructor());
    }
}
