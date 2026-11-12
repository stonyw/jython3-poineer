package org.python.core;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code _weakref} module: {@code ref}, {@code proxy}, and the bookkeeping the module offers about
 * them.
 *
 * <p>The references are Java {@link WeakReference}s, so dying is the collector's job rather than this
 * module's. The one thing Java does not answer is "which references point at this object", because a
 * {@code WeakReference} cannot be walked backwards — hence {@link #LIVE}, an ordered list of the holders
 * a program still holds, kept weak itself so the list never keeps a reference object alive. Entries are
 * pruned whenever the list is read, which is enough for {@code getweakrefcount} and
 * {@code getweakrefs} to agree with the reference's answers.
 *
 * <p>Callbacks are stored on the reference and never invoked; Java has no hook that runs at the moment
 * a referent is collected. That is the named deviation this module carries.
 */
final class Weakref {

    private Weakref() {
    }

    /** A reference or proxy, as far as the registry can see one. */
    interface Holder {

        /** The live referent, or null once it has been collected. */
        PyObject target();
    }

    /** Newest first, which is the order the reference lists a type's weak references in. */
    private static final List<WeakReference<Holder>> LIVE = new ArrayList<>();

    private static final String DOC = "Weak-reference support module.";

    private static final String PROXY_DOC =
            "Create a proxy object that weakly references 'object'.\n"
                    + "\n"
                    + "'callback', if given, is called with a reference to the\n"
                    + "proxy when 'object' is about to be finalized.";

    private static final String COUNT_DOC = "Return the number of weak references to 'object'.";

    private static final String REFS_DOC =
            "Return a list of all weak reference objects pointing to 'object'.";

    private static final String REMOVE_DEAD_DOC =
            "Atomically remove key from dict if it points to a dead weakref.";

    /** Whether CPython gives this kind of object a weak list. */
    static boolean isWeakrefable(PyObject value) {
        return value instanceof PyInstance
                || value instanceof PyType
                || value instanceof PyFunction
                || value instanceof PyBoundMethod
                || value instanceof PyBoundBuiltin
                || value instanceof PySet
                || value instanceof PyFrozenSet
                || value instanceof PyModule
                || value instanceof PyMemoryView
                || value instanceof PyGenerator
                || value instanceof PyIOStream;
    }

    /** The rows, in the order {@code _weakref.__dict__} lists them in the reference. */
    static PyModule newModule() {
        PyModule module = new PyModule("_weakref");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_weakref");
        module.set("getweakrefcount",
                new PyNativeFunction("getweakrefcount", Weakref::count).documented(COUNT_DOC));
        module.set("_remove_dead_weakref",
                new PyNativeFunction("_remove_dead_weakref", Weakref::removeDead)
                        .documented(REMOVE_DEAD_DOC));
        module.set("getweakrefs",
                new PyNativeFunction("getweakrefs", Weakref::refs).documented(REFS_DOC));
        module.set("proxy", new PyNativeFunction("proxy", Weakref::proxy).documented(PROXY_DOC));
        // `weakref.ref` *is* the type; calling it runs the constructor filed under the same object.
        module.set("ref", PyType.WEAK_REFERENCE);
        module.set("ReferenceType", PyType.WEAK_REFERENCE);
        module.set("ProxyType", PyType.WEAK_PROXY);
        module.set("CallableProxyType", PyType.WEAK_CALLABLE_PROXY);
        return module;
    }

    /**
     * {@code weakref.ref(object, callback=None)}, which is the type's own constructor.
     *
     * <p>The reference makes {@code ref} and {@code ReferenceType} one object, so the call arrives through
     * {@link Constructors} with the keyword names already unpacked; the inherited {@code __new__}'s
     * argument-count sentence is what a wrong call reads, and the keyword refusal is the type's own.
     */
    static PyObject newRef(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("ref() takes no keyword arguments");
        }
        Arguments.between("__new__", args, null, 1, 2);
        PyObject referent = checked(args[0]);
        PyWeakRef made = new PyWeakRef(referent, args.length > 1 ? args[1] : null);
        remember(made);
        return made;
    }

    private static PyObject proxy(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("_weakref.proxy()", kwNames);
        Arguments.between("proxy", args, null, 1, 2);
        PyWeakProxy made = new PyWeakProxy(checked(args[0]));
        remember(made);
        return made;
    }

    private static PyObject count(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("_weakref.getweakrefcount", args, kwNames);
        int found = 0;
        for (Holder holder : live()) {
            if (holder.target() == args[0]) {
                found++;
            }
        }
        return PyInt.of(found);
    }

    private static PyObject refs(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("_weakref.getweakrefs", args, kwNames);
        PyList found = PyList.empty();
        for (Holder holder : live()) {
            if (holder.target() == args[0]) {
                found.add((PyObject) holder);
            }
        }
        return found;
    }

    /**
     * {@code _remove_dead_weakref(d, key)}: drop the entry when its reference has died.
     *
     * <p>{@code PyObject_ClearWeakRefs} uses it in {@code __del__}-shaped cleanup; a program can call it
     * directly, and the reference's own tests do. A key the dict does not hold is not an error: the
     * reference's implementation looks the entry up and returns when there is none.
     */
    private static PyObject removeDead(PyObject[] args, String[] kwNames) {
        Arguments.exactly("_remove_dead_weakref", args, kwNames, 2);
        if (!(args[0] instanceof PyDict mapping)) {
            throw Slots.typeError(
                    "_remove_dead_weakref() argument 1 must be dict, not %s",
                    args[0].getType().name());
        }
        PyObject held = mapping.get(args[1]);
        if (held == null) {
            return PyNone.INSTANCE;
        }
        if (!(held instanceof Holder holder)) {
            throw Slots.typeError("not a weakref");
        }
        if (holder.target() == null) {
            mapping.remove(args[1]);
        }
        return PyNone.INSTANCE;
    }

    /** The object, or the {@code TypeError} a value without a weak list earns. */
    private static PyObject checked(PyObject value) {
        if (!isWeakrefable(value)) {
            throw Slots.typeError(
                    "cannot create weak reference to '%s' object", value.getType().name());
        }
        return value;
    }

    private static void remember(Holder holder) {
        LIVE.add(0, new WeakReference<>(holder));
    }

    /** The holders a program still holds, with the entries of collected ones dropped. */
    private static List<Holder> live() {
        List<Holder> live = new ArrayList<>(LIVE.size());
        LIVE.removeIf(entry -> {
            Holder holder = entry.get();
            if (holder == null) {
                return true;
            }
            live.add(holder);
            return false;
        });
        return live;
    }
}
