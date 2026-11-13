package org.python.core;

/**
 * {@code sys.get_asyncgen_hooks()}'s answer: the two hooks the interpreter currently holds.
 *
 * <p>CPython declares this as a {@code structseq} of two fields, which is what {@link PyVersionInfo} is
 * too: a {@link PyTuple} that reports a type of its own and names its items. Those names are
 * {@link Members#addField fields}, so a read of {@code hooks.firstiter} goes through a
 * {@link PyMemberDescriptor} and the item underneath it, while everything else about the tuple still
 * works — unpacking, indexing, hashing, comparing, and the {@code count}/{@code index} pair.
 *
 * <p>The field is read-only and says so in CPython's words: {@code readonly attribute}, because the
 * member table declares the name without {@code READONLY} set on an item that has no writable slot.
 * {@code __replace__} is the type's own way to a second value, and it checks the field names but not the
 * values put in them.
 */
final class PyAsyncGenHooks extends PyTuple {

    /** The names the type files, in item order. */
    private static final String[] FIELDS = {"firstiter", "finalizer"};

    /** The type's dictionary: three methods, two fields and five values, as CPython files them. */
    private static final Members MEMBERS = Members.of(PyType.ASYNCGEN_HOOKS)
            .addNoArgument("__reduce__", PyAsyncGenHooks::reduced)
            .add("__replace__",
                    "Return a copy of the structure with new values for the specified fields.",
                    PyAsyncGenHooks::replaced)
            .addField("firstiter", "Hook to intercept first iteration")
            .addField("finalizer", "Hook to intercept finalization")
            .addValue("__doc__", PyType.ASYNCGEN_HOOKS.getAttr("__doc__"))
            .addValue("n_sequence_fields", PyInt.of(FIELDS.length))
            .addValue("n_fields", PyInt.of(FIELDS.length))
            .addValue("n_unnamed_fields", PyInt.of(0))
            .addValue("__match_args__", matchArguments());

    PyAsyncGenHooks(PyObject firstiter, PyObject finalizer) {
        super(new PyObject[] {firstiter, finalizer}, PyType.ASYNCGEN_HOOKS);
    }

    private PyAsyncGenHooks(PyObject[] items) {
        super(items, PyType.ASYNCGEN_HOOKS);
    }

    /** {@code __match_args__}, the field names a positional pattern is matched against. */
    private static PyTuple matchArguments() {
        PyObject[] names = new PyObject[FIELDS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = PyStr.of(FIELDS[i]);
        }
        return PyTuple.of(names);
    }

    /** {@code self.name}: a field first, then whatever the tuple underneath answers. */
    @Override
    public PyObject getAttr(String name) {
        PyObject filed = Members.filed(this, name);
        return filed == Slots.DECLINED ? super.getAttr(name) : filed;
    }

    @Override
    public PyObject setAttr(String name, PyObject value) {
        throw refused(name);
    }

    @Override
    public PyObject delAttr(String name) {
        throw refused(name);
    }

    /** The three answers {@code _PyObject_GenericSetAttrWithDict} gives for this name. */
    private PyRaise refused(String name) {
        if (Members.filesField(this, name)) {
            return Slots.attributeError("readonly attribute");
        }
        String kind = getType().filedName();
        if (Members.files(this, name)) {
            return Slots.attributeError(
                    "'%s' object attribute '%s' is read-only", kind, name);
        }
        return Slots.attributeError(
                "'%s' object has no attribute '%s' and no __dict__ for setting new attributes",
                kind,
                name);
    }

    /** {@code repr(self)}: the items named, in the order the type files them. */
    @Override
    public String repr() {
        StringBuilder out = new StringBuilder(getType().filedName()).append('(');
        for (int i = 0; i < FIELDS.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(FIELDS[i]).append('=').append(at(i).repr());
        }
        return out.append(')').toString();
    }

    /** {@code __reduce__}: the type and the pair a structseq is rebuilt from. */
    private static PyObject reduced(PyObject self) {
        PyTuple items = PyTuple.of(((PyTuple) self).elements());
        return PyTuple.of(self.getType(), PyTuple.of(items, PyDict.empty()));
    }

    /** {@code __replace__(**changes)}: the named fields swapped, and nothing else checked. */
    private static PyObject replaced(PyObject self, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) > 0) {
            throw Slots.typeError("__replace__() takes no positional arguments");
        }
        PyObject[] copied = ((PyTuple) self).elements().clone();
        java.util.List<PyObject> unexpected = new java.util.ArrayList<>();
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                int field = field(kwNames[i]);
                if (field < 0) {
                    unexpected.add(PyStr.of(kwNames[i]));
                    continue;
                }
                copied[field] = args[i];
            }
        }
        if (!unexpected.isEmpty()) {
            throw Slots.typeError(
                    "Got unexpected field name(s): %s", PyList.of(unexpected.toArray(new PyObject[0])));
        }
        return new PyAsyncGenHooks(copied);
    }

    /** Which item the field {@code name} is, or -1 where the type files no such field. */
    private static int field(String name) {
        for (int i = 0; i < FIELDS.length; i++) {
            if (FIELDS[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
