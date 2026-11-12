package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code os.terminal_size}: the {@code (columns, lines)} pair a terminal reports.
 *
 * <p>It is a struct sequence of two items, so the value is a plain {@link PyTuple} of two integers with
 * the two names {@code columns} and {@code lines} filed over them — {@code shutil.get_terminal_size}
 * reads {@code .columns} and {@code unittest} warns against the terminal's width, which is the whole
 * reason this runtime needs the type at all.
 *
 * <p>The constructor takes one sequence of exactly two items: {@code os.terminal_size((80, 24))}. The
 * reference's {@code structseq_new} takes a flat view of whatever it is handed, so a string is a
 * two-sequence of its characters and a dictionary a sequence of its keys; both are reproduced here by
 * iterating the same way.
 */
public final class PyTerminalSize extends PyTuple {

    /** The names the two items are filed under, in item order. */
    static final String[] FIELDS = {"columns", "lines"};

    /** The type's dictionary: two fields and the four values every struct sequence carries. */
    private static final Members METHODS = Members.of(PyType.TERMINAL_SIZE)
            .addNoArgument("__reduce__", PyTerminalSize::reduced)
            .addField("columns", "the width of the terminal window in characters")
            .addField("lines", "the height of the terminal window in characters")
            .addValue("__doc__", PyType.TERMINAL_SIZE.getAttr("__doc__"))
            .addValue("n_sequence_fields", PyInt.of(FIELDS.length))
            .addValue("n_fields", PyInt.of(FIELDS.length))
            .addValue("n_unnamed_fields", PyInt.of(0))
            .addValue("__match_args__", matchArguments());

    private PyTerminalSize(PyObject[] items) {
        super(items, PyType.TERMINAL_SIZE);
    }

    /** A size the runtime knows: {@code os.get_terminal_size} answers one. */
    static PyTerminalSize of(int columns, int lines) {
        return new PyTerminalSize(new PyObject[] {PyInt.of(columns), PyInt.of(lines)});
    }

    /** {@code os.terminal_size(sequence, /)}: one sequence of exactly two items. */
    static PyObject called(PyObject[] args, String[] keywordNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "structseq", args, keywordNames, new String[] {"sequence"}, 1, 1);
        List<PyObject> sequence;
        try {
            sequence = drain(values[0]);
        } catch (PyRaise notASequence) {
            // `PySequence_Fast` replaces whatever the object's own iterator said with this sentence.
            throw Slots.typeError("constructor requires a sequence");
        }
        if (sequence.size() != FIELDS.length) {
            throw Slots.typeError(
                    "os.terminal_size() takes a %d-sequence (%d-sequence given)",
                    FIELDS.length, sequence.size());
        }
        return new PyTerminalSize(sequence.toArray(new PyObject[0]));
    }

    private static List<PyObject> drain(PyObject sequence) {
        List<PyObject> items = new ArrayList<>();
        PyIterator iterator = Slots.iterator(sequence);
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

    /** The two items, each named: {@code os.terminal_size(columns=80, lines=24)}. */
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

    /** {@code __reduce__}: {@code (cls, (tuple, {}))}, the empty dictionary included. */
    private static PyObject reduced(PyObject self) {
        PyTerminalSize size = (PyTerminalSize) self;
        return PyTuple.of(size.getType(), PyTuple.of(PyTuple.of(size.elements()), PyDict.empty()));
    }
}
