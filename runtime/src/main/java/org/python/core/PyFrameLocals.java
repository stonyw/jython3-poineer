package org.python.core;

/**
 * {@code frame.f_locals} for a frame whose names live in slots: the reference's {@code FrameLocalsProxy}.
 *
 * <p>Since 3.13 the reference has answered a function frame's {@code f_locals} with a mapping that is a
 * window onto the frame rather than a copy of it. That is not a detail: a suspended generator's variables
 * are what a program rewrites to steer it — {@code g.gi_frame.f_locals['.0'] = iter(...)} replaces the
 * iterator a generator expression is looping over, and {@code frame.f_locals} captured before an
 * assignment sees the assignment afterwards. A copy cannot do either, so the frame stays reachable from
 * here and every read goes through its slots.
 *
 * <p>Names the code object has no slot for still need somewhere to live, because the reference accepts
 * them: writing {@code f_locals['zz']} on a frame without a {@code zz} stores it beside the locals and
 * lists it afterwards, while {@code del} refuses with {@code ValueError} for every name the code holds.
 * Both halves are reproduced here rather than refused, since a refusal would be a difference a program
 * can see.
 */
final class PyFrameLocals extends PyObject {

    /** The frame whose slots this window reads and writes. */
    private final Frame frame;

    /** Names a program wrote that the code object has no slot for, in write order. */
    private final PyDict extra = PyDict.empty();

    PyFrameLocals(Frame frame) {
        super(PyType.FRAME_LOCALS);
        this.frame = frame;
    }

    // --- the mapping protocol ------------------------------------------------------------

    @Override
    public PyObject length() {
        return PyInt.of(bound().size() + extra.size());
    }

    /**
     * {@code proxy[key]}: a slot's current value, or the name in the extras.
     *
     * <p>A missing name is reported in the reference's words, {@code local variable 'x' is not defined},
     * and a non-string key is simply not a local — it is either an extra or missing.
     */
    @Override
    public PyObject getItem(PyObject key) {
        int slot = slotOf(key);
        if (slot >= 0) {
            PyObject value = frame.localSlotValue(slot);
            if (value != null) {
                return value;
            }
        }
        PyObject value = extra.get(key);
        if (value != null) {
            return value;
        }
        throw Slots.keyError(PyStr.of("local variable '" + key.repr() + "' is not defined"));
    }

    /**
     * {@code proxy[key] = value}: a local becomes the value the generator will read next, a cell takes it
     * into its box so the closure sees it, and anything else is kept beside the locals.
     */
    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        int slot = slotOf(key);
        if (slot >= 0) {
            frame.writeLocalSlot(slot, value);
        } else {
            extra.put(key, value);
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code del proxy[key]}: refused for a name the code holds, because a slot cannot be removed, and
     * answered for one kept in the extras.
     */
    @Override
    public PyObject delItem(PyObject key) {
        if (slotOf(key) >= 0) {
            throw Slots.valueError("cannot remove local variables from FrameLocalsProxy");
        }
        if (extra.remove(key) == null) {
            throw Slots.keyError(key);
        }
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject contains(PyObject key) {
        int slot = slotOf(key);
        if (slot >= 0 && frame.localSlotValue(slot) != null) {
            return PyBool.TRUE;
        }
        return PyBool.of(extra.get(key) != null);
    }

    /** Keys in locals-plus order, then the extras, which is the order the reference lists them in. */
    @Override
    public PyObject iterator() {
        return names().iterator();
    }

    /** {@code repr(proxy)} is a dict's, as {@code FrameLocalsProxy_repr} delegates to it. */
    @Override
    public String repr() {
        return snapshot().repr();
    }

    // --- members ------------------------------------------------------------------------

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    private static final Members METHODS = Members.of(PyType.FRAME_LOCALS)
            .add(
                    "get",
                    (self, args, kwNames) -> {
                        noKeywords("get", kwNames);
                        Arguments.between("get", args, kwNames, 1, 2);
                        PyFrameLocals proxy = (PyFrameLocals) self;
                        int slot = proxy.slotOf(args[0]);
                        PyObject found = slot >= 0 ? proxy.frame.localSlotValue(slot) : null;
                        if (found == null) {
                            found = proxy.extra.get(args[0]);
                        }
                        return found != null
                                ? found
                                : args.length > 1 ? args[1] : PyNone.INSTANCE;
                    })
            .addNoArgument("keys", self -> DictViews.keys(((PyFrameLocals) self).snapshot()))
            .addNoArgument("values", self -> DictViews.values(((PyFrameLocals) self).snapshot()))
            .addNoArgument("items", self -> DictViews.items(((PyFrameLocals) self).snapshot()))
            .addNoArgument("copy", self -> ((PyFrameLocals) self).snapshot());

    // --- the frame's names ----------------------------------------------------------------

    /** The slot {@code key} names, or -1 when it is not a local of this code object. */
    private int slotOf(PyObject key) {
        if (key instanceof PyStr name) {
            return frame.localSlotIndex(name.getValue());
        }
        return -1;
    }

    /** The bound locals in locals-plus order. */
    private PyDict bound() {
        PyDict names = PyDict.empty();
        if (frame.localsCleared()) {
            return names;
        }
        for (int i = 0; i < frame.localSlotCount(); i++) {
            PyObject value = frame.localSlotValue(i);
            if (value != null) {
                names.put(frame.localSlotName(i), value);
            }
        }
        return names;
    }

    /** The bound locals followed by the extras, which is what every whole-mapping read walks. */
    private PyDict snapshot() {
        PyDict names = bound();
        PyIterator keys = (PyIterator) extra.iterator();
        for (PyObject key = keys.nextOrNull(); key != null; key = keys.nextOrNull()) {
            names.put(key, extra.getItem(key));
        }
        return names;
    }

    /** The names in the same order {@link #snapshot()} lists them. */
    private PyList names() {
        PyList listing = PyList.empty();
        for (PyObject key : snapshotKeys()) {
            listing.add(key);
        }
        return listing;
    }

    private java.util.List<PyObject> snapshotKeys() {
        java.util.List<PyObject> keys = new java.util.ArrayList<>();
        if (!frame.localsCleared()) {
            for (int i = 0; i < frame.localSlotCount(); i++) {
                if (frame.localSlotValue(i) != null) {
                    keys.add(frame.localSlotName(i));
                }
            }
        }
        PyIterator extras = (PyIterator) extra.iterator();
        for (PyObject key = extras.nextOrNull(); key != null; key = extras.nextOrNull()) {
            keys.add(key);
        }
        return keys;
    }

    /** The refusal {@code get} files for a keyword argument it does not take. */
    private static void noKeywords(String name, String[] kwNames) {
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError(name + "() takes no keyword arguments");
        }
    }
}
