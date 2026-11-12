package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code time.struct_time}: the named sequence {@code gmtime}, {@code localtime} and {@code strptime}
 * answer with.
 *
 * <p>It is a {@link PyTuple} of nine items with eleven names, which is what a structseq is: the first
 * nine fields read the items underneath, and {@code tm_zone} and {@code tm_gmtoff} are read from two
 * fields this class carries beside the tuple. A value built from a nine-item sequence has neither, so
 * both answer {@code None}, exactly as the reference's do.
 *
 * <p>The constructor takes a sequence of nine, ten or eleven items and an optional dictionary supplying
 * the two extra names: {@code struct_time(seq, {'tm_zone': 'UTC'})}. A dictionary is not a sequence, so
 * {@code struct_time({})} answers the too-short complaint instead of iterating anything.
 */
public final class PyStructTime extends PyTuple {

    /** The names the nine items are filed under, in item order. */
    static final String[] FIELDS = {
        "tm_year", "tm_mon", "tm_mday", "tm_hour", "tm_min", "tm_sec", "tm_wday", "tm_yday",
        "tm_isdst"
    };

    private static final String[] PARAMETERS = {"sequence", "dict"};

    /**
     * The type's dictionary: ten fields, four values and {@code __reduce__}, filed in the order
     * CPython's {@code structseq} declarations write them.
     */
    private static final Members METHODS = Members.of(PyType.STRUCT_TIME)
            .addNoArgument("__reduce__", PyStructTime::reduced)
            .addField("tm_year", "Year with century as a decimal number.")
            .addField("tm_mon", "Month of year, range [1, 12]")
            .addField("tm_mday", "Day of month, range [1, 31]")
            .addField("tm_hour", "Hours, range [0, 23]")
            .addField("tm_min", "Minutes, range [0, 59]")
            .addField("tm_sec", "Seconds, range [0, 61])")
            .addField("tm_wday", "Day of week, range [0, 6], Monday is 0")
            .addField("tm_yday", "Day of year, range [1, 366]")
            .addField("tm_isdst", "1 if summer time is in effect, 0 if not, and -1 if unknown")
            .addField("tm_zone", "Abbreviation of timezone name",
                    self -> ((PyStructTime) self).zone)
            .addField("tm_gmtoff", "Offset from UTC in seconds",
                    self -> ((PyStructTime) self).offset)
            .addValue("__doc__", PyType.STRUCT_TIME.getAttr("__doc__"))
            .addValue("n_sequence_fields", PyInt.of(FIELDS.length))
            .addValue("n_fields", PyInt.of(FIELDS.length + 2))
            .addValue("n_unnamed_fields", PyInt.of(0))
            .addValue("__match_args__", matchArguments());

    /** {@code tm_zone}: a string, or {@code None} where the sequence supplied none. */
    private final PyObject zone;

    /** {@code tm_gmtoff}: an integer number of seconds, or {@code None}. */
    private final PyObject offset;

    private PyStructTime(PyObject[] items, PyObject zone, PyObject offset) {
        super(items, PyType.STRUCT_TIME);
        this.zone = zone;
        this.offset = offset;
    }

    /** A value the runtime builds: {@code gmtime} and {@code localtime} know both extras. */
    static PyStructTime of(PyObject[] items, PyObject zone, PyObject offset) {
        return new PyStructTime(items, zone, offset);
    }

    /** {@code time.struct_time(...)}: {@code sequence[, dict]}. */
    static PyObject called(PyObject[] args, PyTuple keywordNames) {
        String[] names = new String[keywordNames == null ? 0 : keywordNames.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = Numbers.asText(keywordNames.at(i));
        }
        PyObject[] values = Arguments.parameters(
                "struct_time", args, names, PARAMETERS,
                new PyObject[] {null, PyNone.INSTANCE}, 1);
        List<PyObject> sequence = drain(values[0]);
        int given = sequence.size();
        if (given < FIELDS.length) {
            throw Slots.typeError(
                    "structseq() takes an at least %d-sequence (%d-sequence given)",
                    FIELDS.length, given);
        }
        if (given > FIELDS.length + 2) {
            throw Slots.typeError(
                    "structseq() takes at most %d-sequence (%d-sequence given)",
                    FIELDS.length + 2, given);
        }
        PyObject[] items = new PyObject[FIELDS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = PyInt.of(Numbers.asIndex(sequence.get(i)));
        }
        PyObject zone = given > FIELDS.length ? sequence.get(FIELDS.length) : PyNone.INSTANCE;
        PyObject offset = given > FIELDS.length + 1 ? sequence.get(FIELDS.length + 1)
                : PyNone.INSTANCE;
        PyObject dictionary = values[1];
        if (dictionary != PyNone.INSTANCE) {
            zone = supplied(dictionary, "tm_zone", zone);
            offset = supplied(dictionary, "tm_gmtoff", offset);
        }
        return new PyStructTime(items, zone, offset);
    }

    /** The value of {@code name} in the dictionary, or {@code fallback} where it has none. */
    private static PyObject supplied(PyObject dictionary, String name, PyObject fallback) {
        PyObject key = PyStr.interned(name);
        PyObject found = dictionary.getType() == PyType.DICT
                ? ((PyDict) dictionary).get(key)
                : dictionary.getAttr("get").call(new PyObject[] {key, fallback}, null);
        return found == null ? fallback : found;
    }

    private static List<PyObject> drain(PyObject sequence) {
        List<PyObject> items = new ArrayList<>();
        PyIterator iterator;
        try {
            iterator = Slots.iterator(sequence);
        } catch (PyRaise raise) {
            throw raise;
        }
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            items.add(item);
        }
        return items;
    }

    private static PyTuple matchArguments() {
        PyObject[] names = new PyObject[FIELDS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = PyStr.interned(FIELDS[i]);
        }
        return PyTuple.of(names);
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

    /** The nine items, each named: {@code time.struct_time(tm_year=..., ...)}. */
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
     * {@code __reduce__}: {@code (cls, (tuple, {'tm_zone': ..., 'tm_gmtoff': ...}))}.
     *
     * <p>The dictionary is always present, and both names are in it even where the value is
     * {@code None} — {@code structseq_reduce} writes the pair unconditionally.
     */
    private static PyObject reduced(PyObject self) {
        PyStructTime time = (PyStructTime) self;
        PyDict extras = PyDict.empty();
        extras.put(PyStr.interned("tm_zone"), time.zone);
        extras.put(PyStr.interned("tm_gmtoff"), time.offset);
        return PyTuple.of(self.getType(), PyTuple.of(PyTuple.of(time.elements()), extras));
    }
}
