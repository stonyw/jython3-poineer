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

        /** The callback given when the reference was made, or null for none. */
        default PyObject callback() {
            return null;
        }
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
        if (value instanceof PyInstance instance) {
            // A class that wrote its own __slots__ without '__weakref__' has no weak list to hand out,
            // so test_weakrefs' NoWeak is refused rather than quietly referenced. A class with no
            // __slots__ (or one that asked for the slot) answers a non-zero offset.
            return TypeLayout.weakrefoffset(instance.getType()) != 0;
        }
        return value instanceof PyType
                || value instanceof PyFunction
                || value instanceof PyBoundMethod
                || value instanceof PyBoundBuiltin
                || value instanceof PySet
                || value instanceof PyFrozenSet
                || value instanceof PyModule
                || value instanceof PyMemoryView
                || value instanceof PyGenerator
                || value instanceof PyIOStream
                // Every `_io` type carries a weak list in the reference — the four abstract bases too —
                // which is what lets `gzip.GzipFile`, whose base is `io.BufferedIOBase`, be the target
                // of the `weakref` `_WriteBufferStream` takes of it.
                || value instanceof IoBases.Value;
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
        return newRef(PyType.WEAK_REFERENCE, args, kwNames);
    }

    /**
     * The same constructor for a class written over {@code weakref.ref}, where the class decides which
     * kind of value the referent is stored in.
     *
     * <p>{@code ref.__new__(cls, obj, callback)} reaches this with {@code cls} as {@code declared}, and
     * {@code class X(ref)} reaches it through {@link Classes} with {@code X} in that place, which is what
     * keeps a subclass from quietly becoming a plain reference.
     */
    static PyObject newRef(PyType declared, PyObject[] args, String[] kwNames) {
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError("ref() takes no keyword arguments");
        }
        Arguments.between("__new__", args, null, 1, 2);
        PyObject referent = checked(args[0]);
        PyObject callback = args.length > 1 ? args[1] : null;
        Holder made =
                declared == PyType.WEAK_REFERENCE
                        ? new PyWeakRef(referent, callback)
                        : new PyWeakRefSubclass(declared, referent, callback);
        remember(made);
        return (PyObject) made;
    }

    /** {@code ref()}: no arguments, the referent while it lives and {@code None} after. */
    static PyObject call(Holder holder, PyObject[] args, PyTuple kwNames) {
        int keywords = kwNames == null ? 0 : kwNames.size();
        if (args.length != 0 || keywords != 0) {
            throw Slots.typeError(
                    "weakref expected 0 arguments, got %d", args.length + keywords);
        }
        PyObject live = holder.target();
        return live == null ? PyNone.INSTANCE : live;
    }

    /** {@code hash(ref)}: the referent's hash, and the refusal once it is gone. */
    static long hashOf(Holder holder) {
        PyObject live = holder.target();
        if (live == null) {
            throw Slots.typeError("weak object has gone away");
        }
        return live.hash();
    }

    /**
     * {@code a == b} for two references: whether both point at the same live object.
     *
     * <p>A dead reference stands for {@code None}, as {@code PyWeakref_GET_OBJECT} answers, so two dead
     * references are equal to each other and to nothing else.
     */
    static boolean equal(Holder self, PyObject other) {
        if (!(other instanceof Holder theirs)) {
            return false;
        }
        return self.target() == theirs.target();
    }

    /** {@code ref.__callback__}: the callback, or {@code None} when there is none. */
    static PyObject callbackOf(Holder holder) {
        PyObject callback = holder.callback();
        return callback == null ? PyNone.INSTANCE : callback;
    }

    /** {@code repr(ref)}: where the reference is, and what it points at when it still points at anything. */
    static String describe(Holder holder) {
        String at = "0x" + Integer.toHexString(System.identityHashCode(holder));
        PyObject live = holder.target();
        if (live == null) {
            return "<weakref at " + at + "; dead>";
        }
        return "<weakref at "
                + at
                + "; to '"
                + live.getType().name()
                + "' at 0x"
                + Integer.toHexString(System.identityHashCode(live))
                + ">";
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
