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
            .addField("st_mode", "protection bits.")
            .addField("st_ino", "inode number.")
            .addField("st_dev", "device.")
            .addField("st_nlink", "number of hard links.")
            .addField("st_uid", "user ID of owner.")
            .addField("st_gid", "group ID of owner.")
            .addField("st_size", "total size, in bytes.")
            .addField("st_atime", "time of last access.")
            .addField("st_mtime", "time of last modification.")
            .addField("st_ctime", "time of last change.")
            .addField("st_atime_ns", "time of last access in nanoseconds",
                    self -> PyInt.of(((PyStatResult) self).atimeNs))
            .addField("st_mtime_ns", "time of last modification in nanoseconds",
                    self -> PyInt.of(((PyStatResult) self).mtimeNs))
            .addField("st_ctime_ns", "time of last change in nanoseconds",
                    self -> PyInt.of(((PyStatResult) self).ctimeNs))
            .addField("st_birthtime", "time of creation",
                    self -> PyFloat.of(((PyStatResult) self).birthtimeNs / 1_000_000_000.0))
            .addField("st_birthtime_ns", "time of creation in nanoseconds",
                    self -> PyInt.of(((PyStatResult) self).birthtimeNs))
            .addField("st_rdev", "device type (if inode device).",
                    self -> PyInt.of(((PyStatResult) self).rdev))
            .addValue("__doc__", PyType.STAT_RESULT.getAttr("__doc__"))
            .addValue("n_sequence_fields", PyInt.of(FIELDS.length))
            .addValue("n_fields", PyInt.of(FIELDS.length + EXTRA_FIELDS.length + 1))
            .addValue("n_unnamed_fields", PyInt.of(0));

    /** {@code st_atime_ns}, {@code st_mtime_ns} and {@code st_ctime_ns}. */
    private final long atimeNs;
    private final long mtimeNs;
    private final long ctimeNs;

    /** {@code st_birthtime_ns}, which the item times do not carry. */
    private final long birthtimeNs;

    /** {@code st_rdev}. */
    private final long rdev;

    private PyStatResult(
            PyObject[] items, long atimeNs, long mtimeNs, long ctimeNs, long birthtimeNs, long rdev) {
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
        return new PyStatResult(items, atimeNs, mtimeNs, ctimeNs, birthtimeNs, rdev);
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
