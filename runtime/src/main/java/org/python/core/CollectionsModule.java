package org.python.core;

/**
 * The {@code _collections} module: the types {@code collections} cannot write in Python.
 *
 * <p>Two are here. {@code deque} is not replaceable by a fallback — {@code collections} registers it with
 * {@code MutableSequence} and {@code contextlib.ExitStack} is built on it — while {@code defaultdict} is
 * imported without one, so {@code collections.defaultdict} exists only when this module exports it. The
 * reference's module also holds {@code OrderedDict}, the cursor over a deque and {@code _tuplegetter};
 * {@code collections} imports each of those inside a {@code try} and keeps its own Python implementation
 * when the import fails, so their absence costs nothing this round.
 *
 * <p>{@code defaultdict} is built here as a class over {@code dict} rather than as a value class of its
 * own. Its whole protocol is the pair {@code __missing__} and {@code default_factory}, and {@link
 * PyDictInstance} already asks the first when a key is absent — so a class whose namespace files both
 * answers as the reference's does without a new slot anywhere. Only {@code __repr__} has to say
 * {@code defaultdict(...)} rather than the entries alone.
 */
final class CollectionsModule {

    private CollectionsModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_collections");
        PyRuntime.markBuiltIn(module, "_collections");
        module.set("deque", PyType.DEQUE);
        module.set("defaultdict", defaultDictType());
        return module;
    }

    /**
     * {@code collections.defaultdict}: {@code dict} with a factory called for the key that is absent.
     *
     * <p>The class is filed under {@code collections} because that is where the reference names it, and
     * the name is read off the class rather than the module it happens to live in.
     */
    private static PyClass defaultDictType() {
        PyDict namespace = PyDict.empty();
        namespace.put(PyStr.interned("__module__"), PyStr.of("collections"));
        namespace.put(PyStr.interned("__qualname__"), PyStr.of("defaultdict"));
        namespace.put(
                PyStr.interned("__init__"),
                PyNativeFunction.method("__init__", CollectionsModule::defaultDictInit));
        namespace.put(
                PyStr.interned("__missing__"),
                PyNativeFunction.method("__missing__", CollectionsModule::defaultDictMissing));
        namespace.put(
                PyStr.interned("__repr__"),
                PyNativeFunction.method("__repr__", CollectionsModule::defaultDictRepr));
        return Classes.declare(
                "defaultdict", PyTuple.of(PyType.DICT), namespace, PyType.TYPE, new PyObject[0], null);
    }

    /**
     * {@code defaultdict(default_factory=None, /, **kwargs)}: the factory is kept on the instance and every
     * keyword pair becomes an entry.
     */
    private static PyObject defaultDictInit(PyObject[] args, String[] kwNames) {
        int given = Arguments.positional(args, kwNames);
        if (given > 2) {
            throw Slots.typeError("defaultdict expected at most 1 argument, got %d", given - 1);
        }
        Slots.setAttr(args[0], "default_factory", given == 2 ? args[1] : PyNone.INSTANCE);
        if (kwNames != null) {
            PyDict entries = PyDict.bodyOf(args[0]);
            for (int i = 0; i < kwNames.length; i++) {
                entries.setItem(PyStr.of(kwNames[i]), args[given + i]);
            }
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code d[key]} for an absent key: the factory's answer, filed under the key as it is returned.
     *
     * <p>No factory is the absent key's own {@code KeyError} — the one the entries raised, re-raised with
     * the same key so the message stays {@code 'name'}.
     */
    private static PyObject defaultDictMissing(PyObject[] args, String[] kwNames) {
        PyObject factory = Slots.getAttr(args[0], "default_factory");
        if (factory == PyNone.INSTANCE) {
            throw PyRaise.raise(ExceptionTypes.KEY_ERROR, args[1]);
        }
        PyObject value = Slots.call(factory, new PyObject[0], null);
        PyDict.bodyOf(args[0]).setItem(args[1], value);
        return value;
    }

    /** {@code repr(d)}: {@code defaultdict(<class 'int'>, {...})}, the factory before the entries. */
    private static PyObject defaultDictRepr(PyObject[] args, String[] kwNames) {
        return PyStr.of("defaultdict("
                + Slots.getAttr(args[0], "default_factory").repr()
                + ", "
                + PyDict.bodyOf(args[0]).repr()
                + ")");
    }
}
