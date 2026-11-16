package org.python.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code _symtable} module: the raw symbol table {@code symtable.py} wraps.
 *
 * <p>The reference's {@code _symtable.symtable()} is a thin C function. It parses the source with the
 * compiler's own parser and hands back the top {@code symtable entry} — a read-only record holding the
 * block's {@code id}, {@code name}, {@code symbols}, {@code varnames}, {@code children}, {@code nested},
 * {@code type} and {@code lineno}. All of the questions a program asks ({@code get_type()},
 * {@code lookup()}, {@code get_symbols()}, {@code get_children()}, …) live in {@code symtable.py}, which
 * is pure Python and imports fine from here; this module only has to produce the record it wraps.
 *
 * <p>Parsing is the compiler's job in this runtime too, so the tree crosses the compiler seam. The
 * front end runs the reference's {@code _symtable.symtable} and writes the tree as the line-oriented
 * text {@link SourceCompiler#symtable} describes; this class rebuilds it. Nothing is invented: the
 * flags, the varnames, the children and their order are the reference's own, and the encoding
 * declaration of a bytes source is obeyed by the reference decoder before the tree is built.
 *
 * <p>One field cannot be the reference's: {@code id} is a C pointer there, unique only for as long as
 * the table lives. This runtime numbers the entries it builds from one counter, which preserves what
 * the field means to Python — an opaque identity, positive, distinct within the tree — without pretending
 * to be an address. {@code repr} prints it the way the reference prints its own.
 *
 * <p>The record's type is not filed in {@code builtins}, because the reference does not expose it
 * either; the only way to hold one is to call {@code symtable()}.
 */
final class SymtableModule {

    /** {@code symtable.symtable.__doc__}, the reference's own sentence. */
    private static final String SYMTABLE_DOC =
            "symtable($module, source, filename, startstr, /)\n"
            + "--\n"
            + "\n"
            + "Return symbol and scope dictionaries used internally by compiler.";

    /**
     * The record's type, named as CPython's {@code PySTEntry_Type} names it. It cannot be called: every
     * entry is made by {@code symtable()} from the tree the compiler answered.
     */
    private static final PyType ENTRY = PyType.builtin("symtable entry").withoutNew();

    /** The identifiers this runtime gives entries, standing where the reference keeps an address. */
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    /** The eight read-only fields, in the order CPython's member table declares them. */
    private static final Members FIELDS = Members.of(ENTRY)
            .addField("id", null, self -> PyInt.of(((Entry) self).identifier))
            .addField("name", null, self -> ((Entry) self).name)
            .addField("symbols", null, self -> ((Entry) self).symbols)
            .addField("varnames", null, self -> ((Entry) self).varnames)
            .addField("children", null, self -> ((Entry) self).children)
            .addField("nested", null, self -> PyInt.of(((Entry) self).nested))
            .addField("type", null, self -> PyInt.of(((Entry) self).type))
            .addField("lineno", null, self -> PyInt.of(((Entry) self).lineno));

    private SymtableModule() {}

    /** The module, with its constants and the one call. */
    static PyModule newModule() {
        PyModule module = new PyModule("_symtable");
        module.set("__doc__", PyNone.INSTANCE);
        // The reference fills the module in this order: name, doc, then the import bookkeeping, then
        // the call and the constants, and `vars()` publishes that order.
        PyRuntime.markBuiltIn(module, "_symtable");
        module.set("symtable", new PyNativeFunction("symtable", SymtableModule::symtable)
                .documented(SYMTABLE_DOC));
        constants(module);
        return module;
    }

    /**
     * {@code _symtable.symtable(source, filename, startstr)}.
     *
     * <p>The conversions run in the reference's order — source, filename, then the start string — so the
     * first bad argument is the one reported. A source may be text or anything with the buffer protocol;
     * a filename may be text, bytes, or a path-like object, and bytes are decoded the way the filesystem
     * decoder decodes them; the start string is exactly a string, and then exactly one of the three
     * modes.
     */
    private static PyObject symtable(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("_symtable.symtable()", kwNames);
        if (args.length != 3) {
            throw Slots.typeError("symtable expected 3 arguments, got %d", args.length);
        }
        PyObject source = args[0];
        boolean text = source instanceof PyStr;
        if (!text && !PyMemoryView.supportsBuffer(source)) {
            throw Slots.typeError("symtable() arg 1 must be a string or bytes object");
        }
        String fileName = fileName(args[1]);
        String mode = start(args[2]);
        if (text) {
            return parse(PyRuntime.symtableText(((PyStr) source).getValue(), fileName, mode));
        }
        return parse(PyRuntime.symtableBytes(PyMemoryView.bufferOctets(source), fileName, mode));
    }

    /** CPython's {@code unicode_fs_decoded}: text, decoded bytes, or whatever {@code __fspath__} answers. */
    private static String fileName(PyObject value) {
        if (value instanceof PyStr) {
            return ((PyStr) value).getValue();
        }
        if (value instanceof PyBytes) {
            return decoded(value);
        }
        PyObject path = value.getAttr("__fspath__");
        if (path != Slots.DECLINED && path != PyNone.INSTANCE) {
            PyObject answered = path.call(new PyObject[0], null);
            if (answered instanceof PyStr) {
                return ((PyStr) answered).getValue();
            }
            if (answered instanceof PyBytes) {
                return decoded(answered);
            }
            throw Slots.typeError("expected %s.__fspath__() to return str or bytes, not %s",
                    value.getType().filedName(), answered.getType().filedName());
        }
        throw Slots.typeError("expected str, bytes or os.PathLike object, not %s",
                value.getType().filedName());
    }

    /** The filesystem decoder: UTF-8 with surrogateescape, so an undecodable byte survives. */
    private static String decoded(PyObject bytes) {
        return Decoder.decode(PyMemoryView.bufferOctets(bytes), "utf-8", "surrogateescape");
    }

    /** Argument Clinic's converter for {@code startstr: str}, and then the three modes it accepts. */
    private static String start(PyObject value) {
        if (!(value instanceof PyStr)) {
            throw Slots.typeError("symtable() argument 3 must be str, not %s",
                    Slots.argumentName(value));
        }
        String mode = ((PyStr) value).getValue();
        if (!"exec".equals(mode) && !"eval".equals(mode) && !"single".equals(mode)) {
            throw Slots.valueError("symtable() arg 3 must be 'exec' or 'eval' or 'single'");
        }
        return mode;
    }

    /** Rebuild the tree the front end wrote: one header line, then symbols, varnames and children. */
    private static Entry parse(String text) {
        return new Cursor(text.split("\n", -1)).entry();
    }

    /** One raw entry, with the eight read-only fields the reference's record carries. */
    static final class Entry extends PyObject {

        final long identifier;

        final PyStr name;

        final PyDict symbols;

        final PyList varnames;

        final PyList children;

        final int nested;

        final int type;

        final int lineno;

        Entry(long identifier, PyStr name, PyDict symbols, PyList varnames, PyList children,
                int nested, int type, int lineno) {
            super(ENTRY);
            this.identifier = identifier;
            this.name = name;
            this.symbols = symbols;
            this.varnames = varnames;
            this.children = children;
            this.nested = nested;
            this.type = type;
            this.lineno = lineno;
        }

        /** {@code ste_repr}: {@code <symtable entry top(457…), line 0>}, the id in decimal. */
        @Override
        public String repr() {
            return "<symtable entry " + name.getValue() + "(" + identifier + "), line " + lineno + ">";
        }

        /** Identity, as the reference inherits from {@code object}: two entries are never equal. */
        @Override
        public long hash() {
            return PyHash.avoidMinusOne(System.identityHashCode(this));
        }
    }

    /** Walks the text one entry at a time; the counts tell it how much belongs to the entry it is in. */
    private static final class Cursor {

        private final String[] lines;

        private int at;

        Cursor(String[] lines) {
            this.lines = lines;
        }

        Entry entry() {
            String[] header = fields(lines[at++], 8);
            int type = Integer.parseInt(header[1]);
            int lineno = Integer.parseInt(header[2]);
            int nested = Integer.parseInt(header[3]);
            String name = decoded(header[4]);
            int symbolCount = Integer.parseInt(header[5]);
            int varnameCount = Integer.parseInt(header[6]);
            int childCount = Integer.parseInt(header[7]);
            PyDict symbols = PyDict.empty();
            for (int i = 0; i < symbolCount; i++) {
                String[] symbol = fields(lines[at++], 3);
                symbols.put(PyStr.of(decoded(symbol[1])), PyInt.of(Long.parseLong(symbol[2])));
            }
            List<PyObject> names = new ArrayList<>(varnameCount);
            for (int i = 0; i < varnameCount; i++) {
                names.add(PyStr.of(decoded(fields(lines[at++], 2)[1])));
            }
            List<PyObject> children = new ArrayList<>(childCount);
            for (int i = 0; i < childCount; i++) {
                children.add(entry());
            }
            return new Entry(NEXT_ID.getAndIncrement(), PyStr.of(name), symbols,
                    PyList.of(names.toArray(new PyObject[0])),
                    PyList.of(children.toArray(new PyObject[0])),
                    nested, type, lineno);
        }

        private static String[] fields(String line, int count) {
            String[] parts = line.split("\t", -1);
            if (parts.length != count) {
                throw Slots.runtimeError("_symtable: malformed entry from the compiler");
            }
            return parts;
        }

        private static String decoded(String base64) {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        }
    }

    /**
     * The constants: the three block kinds {@code symtable.py} compares against, the flag bits its
     * {@code Symbol} methods mask, and the five scope values {@code _getscope()} answers.
     */
    private static void constants(PyModule module) {
        put(module, "USE", 16);
        put(module, "DEF_GLOBAL", 1);
        put(module, "DEF_NONLOCAL", 8);
        put(module, "DEF_LOCAL", 2);
        put(module, "DEF_PARAM", 4);
        put(module, "DEF_TYPE_PARAM", 1024);
        put(module, "DEF_FREE_CLASS", 64);
        put(module, "DEF_IMPORT", 128);
        put(module, "DEF_BOUND", 134);
        put(module, "DEF_ANNOT", 256);
        put(module, "DEF_COMP_ITER", 512);
        put(module, "DEF_COMP_CELL", 2048);
        put(module, "TYPE_FUNCTION", 0);
        put(module, "TYPE_CLASS", 1);
        put(module, "TYPE_MODULE", 2);
        put(module, "TYPE_ANNOTATION", 3);
        put(module, "TYPE_TYPE_ALIAS", 4);
        put(module, "TYPE_TYPE_PARAMETERS", 5);
        put(module, "TYPE_TYPE_VARIABLE", 6);
        put(module, "LOCAL", 1);
        put(module, "GLOBAL_EXPLICIT", 2);
        put(module, "GLOBAL_IMPLICIT", 3);
        put(module, "FREE", 4);
        put(module, "CELL", 5);
        put(module, "SCOPE_OFF", 12);
        put(module, "SCOPE_MASK", 15);
    }

    private static void put(PyModule module, String name, long value) {
        module.set(name, PyInt.of(value));
    }
}
