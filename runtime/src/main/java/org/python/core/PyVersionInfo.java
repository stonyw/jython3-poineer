package org.python.core;

/**
 * {@code sys.version_info}: the one named sequence this runtime builds.
 *
 * <p>It is a {@link PyTuple} that reports a type of its own, which is all a structseq is in CPython — a
 * tuple whose type declares names for its items. Those names are {@link Members#addField fields}, so a
 * read of {@code version.major} goes through a {@link PyMemberDescriptor} and the item underneath it, and
 * nothing about the tuple below stops working: a slice, a concatenation or a repeat is a plain tuple, a
 * hash is a tuple's, and {@code count} and {@code index} are the two {@code tuple} has.
 *
 * <p>The repr is the one place the type is not a tuple: CPython's {@code seq_repr} writes each visible
 * item as {@code name=value} and opens with {@code tp_name}, so the value reads as
 * {@code sys.version_info(major=3, ...)} where its own {@code str} and {@code repr} agree and a
 * {@code %d} of it is refused exactly as a tuple's would be.
 *
 * <p>One value exists. The type is declared with an empty {@code tp_new}, so calling it is refused and
 * the only route to a second version is {@link #replaced}, which CPython implements without a type check
 * of any kind: {@code __replace__(major=[])} is a version_info whose major is a list.
 */
public final class PyVersionInfo extends PyTuple {

    /** The names the type files, in item order — the fields, not the counts or {@code __match_args__}. */
    private static final String[] FIELDS = {
        "major", "minor", "micro", "releaselevel", "serial"
    };

    /**
     * The type's dictionary: three methods, five fields and six values, in the order CPython files them.
     *
     * <p>The counts and the two dunders among the values are plain entries rather than members, which is
     * why {@code version_info.n_fields} answers with a number read through the class while
     * {@code version_info.major} answers with a {@link PyMemberDescriptor}. {@code __repr__} is the one
     * name CPython's dictionary holds that has no table entry here: a value's display is a Java slot, and
     * a built-in type's dictionary is a deviation this runtime already records.
     */
    private static final Members METHODS = Members.of(PyType.VERSION_INFO)
            .addNoArgument("__reduce__", PyVersionInfo::reduced)
            .add("__replace__", "Return a copy of the structure with new values for the specified fields.",
                    PyVersionInfo::replaced)
            .addField("major", "Major release number")
            .addField("minor", "Minor release number")
            .addField("micro", "Patch release number")
            .addField("releaselevel", "'alpha', 'beta', 'candidate', or 'final'")
            .addField("serial", "Serial release number")
            .addValue("__doc__", PyType.VERSION_INFO.getAttr("__doc__"))
            .addValue("n_sequence_fields", PyInt.of(FIELDS.length))
            .addValue("n_fields", PyInt.of(FIELDS.length))
            .addValue("n_unnamed_fields", PyInt.of(0))
            .addValue("__match_args__", matchArguments());

    /** The one value {@code sys} files, and the one every copy is measured against. */
    private static final PyVersionInfo CURRENT =
            new PyVersionInfo(PyRuntime.versionItems());

    private PyVersionInfo(PyObject[] items) {
        super(items, PyType.VERSION_INFO);
    }

    static PyVersionInfo current() {
        return CURRENT;
    }

    /** {@code __match_args__}, the field names a positional pattern is matched against. */
    private static PyTuple matchArguments() {
        PyObject[] names = new PyObject[FIELDS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = PyStr.of(FIELDS[i]);
        }
        return PyTuple.of(names);
    }

    /** {@code self.name}: a field or a filed value first, then the methods the tuple has. */
    @Override
    public PyObject getAttr(String name) {
        PyObject filed = Members.filed(this, name);
        return filed == PyNotImplemented.INSTANCE ? super.getAttr(name) : filed;
    }

    /**
     * {@code version.major = 4}, and every other write, refused three ways by what the type files.
     *
     * <p>A field is refused by its own descriptor, in the words CPython's {@code PyMember_SetOne} uses
     * for a read-only one, and says nothing about the name. A filed name with no descriptor behind it —
     * a count, {@code __doc__}, a method {@code tuple} declares — is refused by the generic setter, which
     * names the type and the attribute. A name nobody files is refused twice over, for the name and for
     * the dictionary this value has not got.
     *
     * <p>{@code del} gives the same three answers, because CPython reaches this gate for both halves of
     * {@code setattr} before it asks what the write was meant to do.
     */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        throw refused(name);
    }

    @Override
    public PyObject delAttr(String name) {
        throw refused(name);
    }

    /** The refusal CPython's {@code _PyObject_GenericSetAttrWithDict} makes for this name. */
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

    /**
     * {@code __reduce__}: the type and the arguments that would rebuild the value.
     *
     * <p>Two items, and the second is itself a pair of the visible items and a dictionary of the rest —
     * {@code (cls, (tuple, {}))}. CPython's {@code structseq_reduce} always makes the pair, and the
     * dictionary is only ever filled by a named sequence with unnamed fields, which this one has none of.
     */
    private static PyObject reduced(PyObject self) {
        PyTuple items = PyTuple.of(((PyTuple) self).elements());
        return PyTuple.of(self.getType(), PyTuple.of(items, PyDict.empty()));
    }

    /**
     * {@code __replace__(**changes)}: a copy with the named fields swapped for the values given.
     *
     * <p>No positional argument is accepted and no value is checked. Each name the type files takes its
     * new item and the rest carry over; what is left over names a field the type does not have, and the
     * complaint lists those in the order the call wrote them — the same order CPython reads out of the
     * keyword dictionary after popping the fields from it.
     */
    private static PyObject replaced(PyObject self, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) > 0) {
            throw Slots.typeError("__replace__() takes no positional arguments");
        }
        PyTuple version = (PyTuple) self;
        PyObject[] copied = version.elements().clone();
        java.util.List<PyObject> unexpected = new java.util.ArrayList<>();
        // The keyword values are the trailing entries of `args`, and nothing is positional here: the
        // refusal above has already checked that.
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
        return new PyVersionInfo(copied);
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
