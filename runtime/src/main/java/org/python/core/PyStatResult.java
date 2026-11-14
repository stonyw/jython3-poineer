package org.python.core;

/**
 * {@code os.stat_result}: the named sequence {@code os.stat} and {@code os.lstat} answer with.
 *
 * <p>It is a structseq of ten items — mode, inode, device, link count, owner, group, size and the three
 * timestamps — and five more names beside them, which are the nanosecond forms of the timestamps, the
 * device of the special file and the birth time. The remaining fields the reference's macOS value carries
 * ({@code st_blksize}, {@code st_blocks}, {@code st_flags} and {@code st_gen}) are named gaps: the Java
 * file system does not expose them, and {@code n_fields} counts only what this class can answer.
 */
public final class PyStatResult extends PyTuple {

    /** The names the ten items are filed under, in item order. */
    static final String[] FIELDS = {
        "st_mode", "st_ino", "st_dev", "st_nlink", "st_uid", "st_gid", "st_size", "st_atime", "st_mtime",
        "st_ctime"
    };

    /** The five names this class carries beside the tuple. */
    private static final String[] EXTRA_FIELDS = {
        "st_atime_ns", "st_mtime_ns", "st_ctime_ns", "st_birthtime", "st_birthtime_ns"
    };

    private static final Members METHODS = Members.of(PyType.STAT_RESULT)
            .addNoArgument("__reduce__", PyStatResult::reduced)
            .addField("st_mode", "protection bits.")
            .addField("st_ino", "inode number.")
            .addField("st_dev", "device.")
            .addField("st_nlink", "number of hard links.")
            .addField("st_uid", "user ID of owner.")
            .addField("st_gid", "group ID of owner.")
            .addField("st_size", "total size, in bytes.")
            // The three item times are whole seconds, as the C `time_t` holds them; the float the
            // attribute answers with is the same instant plus the fraction from the `_ns` fields. A
            // value built by `os.stat_result(sequence)` has no fraction to add, and answers the item
            // itself — which the reference leaves as the integer it was given.
            .addField("st_atime", "time of last access.",
                    self -> ((PyStatResult) self).time(((PyStatResult) self).atimeNs, 7))
            .addField("st_mtime", "time of last modification.",
                    self -> ((PyStatResult) self).time(((PyStatResult) self).mtimeNs, 8))
            .addField("st_ctime", "time of last change.",
                    self -> ((PyStatResult) self).time(((PyStatResult) self).ctimeNs, 9))
            .addField("st_atime_ns", "time of last access in nanoseconds",
                    self -> nanos(((PyStatResult) self).atimeNs))
            .addField("st_mtime_ns", "time of last modification in nanoseconds",
                    self -> nanos(((PyStatResult) self).mtimeNs))
            .addField("st_ctime_ns", "time of last change in nanoseconds",
                    self -> nanos(((PyStatResult) self).ctimeNs))
            .addField("st_birthtime", "time of creation",
                    self -> {
                        Long ns = ((PyStatResult) self).birthtimeNs;
                        return ns == null ? PyNone.INSTANCE : PyFloat.of(ns / 1_000_000_000.0);
                    })
            .addField("st_birthtime_ns", "time of creation in nanoseconds",
                    self -> nanos(((PyStatResult) self).birthtimeNs))
            .addField("st_rdev", "device type (if inode device).",
                    self -> PyInt.of(((PyStatResult) self).rdev))
            .addValue("__doc__", PyType.STAT_RESULT.getAttr("__doc__"))
            .addValue("n_sequence_fields", PyInt.of(FIELDS.length))
            .addValue("n_fields", PyInt.of(FIELDS.length + EXTRA_FIELDS.length + 1))
            .addValue("n_unnamed_fields", PyInt.of(0));

    /**
     * {@code st_atime_ns}, {@code st_mtime_ns} and {@code st_ctime_ns}, or null for a value a program
     * built from a sequence, which was given no fraction and answers {@code None} for each.
     */
    private final Long atimeNs;
    private final Long mtimeNs;
    private final Long ctimeNs;

    /** {@code st_birthtime_ns}, which the item times do not carry; null when the file system has none. */
    private final Long birthtimeNs;

    /** {@code st_rdev}. */
    private final long rdev;

    private PyStatResult(
            PyObject[] items, Long atimeNs, Long mtimeNs, Long ctimeNs, Long birthtimeNs, long rdev) {
        super(items, PyType.STAT_RESULT);
        this.atimeNs = atimeNs;
        this.mtimeNs = mtimeNs;
        this.ctimeNs = ctimeNs;
        this.birthtimeNs = birthtimeNs;
        this.rdev = rdev;
    }

    /** A value the file system read, with the five extras the ten items cannot hold. */
    static PyStatResult of(
            PyObject[] items, long atimeNs, long mtimeNs, long ctimeNs, long birthtimeNs, long rdev) {
        return new PyStatResult(
                items, atimeNs, mtimeNs, ctimeNs, birthtimeNs, rdev);
    }

    /**
     * {@code os.stat_result(sequence)}: the ten items, with no fraction or birth time to name.
     *
     * <p>The reference refuses a sequence shorter or longer than the ten items, with the two sentences
     * a struct sequence writes, and keeps the items as the integers it was handed.
     */
    static PyObject called(PyObject[] args, PyTuple keywordNames) {
        // `structseq_new` unpacks one positional; a keyword one is reported as the missing positional,
        // and any other count as the dict the constructor never had.
        if (keywordNames != null && keywordNames.size() > 0) {
            throw Slots.typeError("structseq() missing required argument 'sequence' (pos 1)");
        }
        if (args.length != 1) {
            throw Slots.typeError("os.stat_result() takes a dict as second arg, if any");
        }
        java.util.List<PyObject> values = new java.util.ArrayList<>();
        PyIterator iterator = Slots.iterator(args[0]);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            values.add(item);
        }
        if (values.size() < FIELDS.length) {
            throw Slots.typeError(
                    "os.stat_result() takes an at least %d-sequence (%d-sequence given)",
                    FIELDS.length, values.size());
        }
        // A longer sequence is accepted and the extra items are ignored, as `structseq_new` does.
        PyObject[] items = new PyObject[FIELDS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = PyInt.of(Numbers.asIndex(values.get(i)).longValue());
        }
        return new PyStatResult(items, null, null, null, null, 0);
    }

    /** {@code __reduce__}: {@code (stat_result, (items,))}, which is what the reference pickles. */
    private static PyObject reduced(PyObject self) {
        return PyTuple.of(self.getType(),
                PyTuple.of(PyTuple.of(((PyStatResult) self).elements())));
    }

    /** An {@code _ns} field: the number, or {@code None} when the value has none. */
    private static PyObject nanos(Long value) {
        return value == null ? PyNone.INSTANCE : PyInt.of(value.longValue());
    }

    /** {@code st_atime}, {@code st_mtime} or {@code st_ctime}: the item plus the fraction, if any. */
    private PyObject time(Long value, int item) {
        return value == null ? at(item) : PyFloat.of(value / 1_000_000_000.0);
    }

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

    private PyRaise refused(String name) {
        if (Members.filesField(this, name)) {
            return Slots.attributeError("readonly attribute");
        }
        String kind = getType().filedName();
        if (Members.files(this, name)) {
            return Slots.attributeError("'%s' object attribute '%s' is read-only", kind, name);
        }
        return Slots.attributeError(
                "'%s' object has no attribute '%s' and no __dict__ for setting new attributes",
                kind, name);
    }

    /** The ten items, each named: {@code os.stat_result(st_mode=..., ...)}. */
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
}
