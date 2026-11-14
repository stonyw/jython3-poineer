package org.python.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code marshal}: Python's own serialisation format, as the reference writes it.
 *
 * <p>The format is not a document format but the shape of a {@code .pyc}, so the type codes and their
 * byte order are copied from {@code Python/marshal.c} rather than invented: the point of this module is
 * that bytes written here are read by the reference and the reference's bytes are read here. Every code
 * below is one {@code r_object} knows, including the ones this runtime never writes — {@code TYPE_INT64},
 * the {@code TYPE_FLOAT} text form, the interned string variants — because reading the reference's output
 * is half of the contract.
 *
 * <p>What is absent is named rather than approximated. Code objects ({@code TYPE_CODE}) make up most of a
 * real {@code .pyc}, and this runtime keeps its compiled form in Java objects whose layout is its own; it
 * therefore refuses a {@code TYPE_CODE} with a message of its own instead of inventing a layout. The
 * buffer forms ({@code bytearray}, {@code memoryview}) are written as {@code TYPE_STRING}, which is what
 * the reference does with them. {@code allow_code} is accepted for signature compatibility and has no
 * effect, because nothing here writes or reads a code object to begin with.
 *
 * <p>The version argument is not validated, and that is the reference's behaviour rather than an
 * oversight: {@code marshal.dumps(1, 5)} and {@code marshal.dumps(1, -1)} both answer, the writer asking
 * only "is this version at least 2" for binary floats and "at least 3" for the reference table. Version 4
 * adds the short ASCII string codes and version 5 is what {@code marshal.version} reports in 3.14.
 */
final class MarshalModule {

    private MarshalModule() {
    }

    // --- type codes ---------------------------------------------------------------------------

    private static final int TYPE_NULL = '0';
    private static final int TYPE_NONE = 'N';
    private static final int TYPE_FALSE = 'F';
    private static final int TYPE_TRUE = 'T';
    private static final int TYPE_STOPITER = 'S';
    private static final int TYPE_ELLIPSIS = '.';
    private static final int TYPE_INT = 'i';
    private static final int TYPE_INT64 = 'I';
    private static final int TYPE_FLOAT = 'f';
    private static final int TYPE_BINARY_FLOAT = 'g';
    private static final int TYPE_COMPLEX = 'x';
    private static final int TYPE_BINARY_COMPLEX = 'y';
    private static final int TYPE_LONG = 'l';
    private static final int TYPE_STRING = 's';
    private static final int TYPE_INTERNED = 't';
    private static final int TYPE_REF = 'r';
    private static final int TYPE_TUPLE = '(';

    /** A tuple of fewer than 256 items, written with a one octet count from version 4 on. */
    private static final int TYPE_SMALL_TUPLE = ')';
    private static final int TYPE_LIST = '[';
    private static final int TYPE_DICT = '{';
    private static final int TYPE_UNICODE = 'u';
    private static final int TYPE_SET = '<';
    private static final int TYPE_FROZENSET = '>';
    private static final int TYPE_ASCII = 'a';
    private static final int TYPE_ASCII_INTERNED = 'A';
    private static final int TYPE_SHORT_ASCII = 'z';
    private static final int TYPE_SHORT_ASCII_INTERNED = 'Z';
    private static final int TYPE_CODE = 'c';

    private static final int FLAG_REF = 0x80;

    /** {@code marshal.version} in CPython 3.14, and the default of {@code dumps}. */
    private static final int VERSION = 5;

    private static final String[] DUMPS_NAMES = {"", "", "allow_code"};
    private static final String[] DUMP_NAMES = {"", "", "", "allow_code"};
    private static final String[] LOADS_NAMES = {"", "allow_code"};
    private static final String[] LOAD_NAMES = {"", "allow_code"};

    private static final BigInteger VERSION_TWO = BigInteger.valueOf(2);
    private static final BigInteger VERSION_THREE = BigInteger.valueOf(3);
    private static final BigInteger VERSION_FOUR = BigInteger.valueOf(4);

    // --- the module ---------------------------------------------------------------------------

    static PyModule newModule() {
        PyModule module = new PyModule("marshal");
        module.set(
                "__doc__",
                PyStr.of(
                        "This module contains functions that can read and write Python values in a"
                                + " binary format."));
        PyRuntime.markBuiltIn(module, "marshal");
        module.set("version", PyInt.of(VERSION));
        module.set(
                "dumps",
                new PyNativeFunction("dumps", MarshalModule::dumps)
                        .documented(
                                "dumps($module, value, version=version, /, *, allow_code=True)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Return the bytes representation of a value."));
        module.set(
                "dump",
                new PyNativeFunction("dump", MarshalModule::dump)
                        .documented(
                                "dump($module, value, file, version=version, /, *, allow_code=True)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Write the bytes representation of a value to a file."));
        module.set(
                "loads",
                new PyNativeFunction("loads", MarshalModule::loads)
                        .documented(
                                "loads($module, bytes, /, *, allow_code=True)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Return the value represented by the given bytes."));
        module.set(
                "load",
                new PyNativeFunction("load", MarshalModule::load)
                        .documented(
                                "load($module, file, /, *, allow_code=True)\n"
                                        + "--\n"
                                        + "\n"
                                        + "Return the value read from an open file."));
        return module;
    }

    // --- the four entry points ----------------------------------------------------------------

    private static PyObject dumps(PyObject[] args, String[] kwNames) {
        PyObject[] given =
                Arguments.unpackKeywords("dumps", args, kwNames, DUMPS_NAMES, 1, 2);
        BigInteger version = given[1] == null ? BigInteger.valueOf(VERSION) : versionOf(given[1]);
        return PyBytes.of(write(given[0], version, allowCode(given[2])));
    }

    private static PyObject dump(PyObject[] args, String[] kwNames) {
        PyObject[] given =
                Arguments.unpackKeywords("dump", args, kwNames, DUMP_NAMES, 2, 3);
        BigInteger version = given[2] == null ? BigInteger.valueOf(VERSION) : versionOf(given[2]);
        byte[] octets = write(given[0], version, allowCode(given[3]));
        writeTo(given[1], octets);
        return PyInt.of(octets.length);
    }

    private static PyObject loads(PyObject[] args, String[] kwNames) {
        PyObject[] given =
                Arguments.unpackKeywords("loads", args, kwNames, LOADS_NAMES, 1, 1);
        byte[] octets = PyMemoryView.bufferOctets(given[0]);
        return read(new Input(octets), allowCode(given[1]));
    }

    private static PyObject load(PyObject[] args, String[] kwNames) {
        PyObject[] given =
                Arguments.unpackKeywords("load", args, kwNames, LOAD_NAMES, 1, 1);
        return read(Input.ofFile(given[0]), allowCode(given[1]));
    }

    /**
     * {@code allow_code}, whose default is True.
     *
     * <p>CPython 3.14 added this keyword to both halves when it made reading marshal data an explicit
     * trust decision. This runtime cannot read a code object in any case, so the only thing the flag
     * changes is which of the two refusals a {@code TYPE_CODE} meets.
     */
    private static boolean allowCode(PyObject value) {
        return value == null || Slots.truth(value);
    }

    private static BigInteger versionOf(PyObject value) {
        return Numbers.asIndex(value);
    }

    private static PyObject read(Input input, boolean allowCode) {
        Reader reader = new Reader(input, allowCode);
        PyObject value = reader.object();
        if (value == null) {
            // TYPE_NULL outside a dictionary. The reference reports the sentinel as an error here.
            throw Slots.typeError("NULL object in marshal data for object");
        }
        return value;
    }

    private static byte[] write(PyObject value, BigInteger version, boolean allowCode) {
        Writer writer = new Writer(version, allowCode);
        writer.object(value);
        return writer.octets();
    }

    // --- writing ------------------------------------------------------------------------------

    /** A growable octet buffer with the three endiannesses {@code marshal.c} uses. */
    private static final class Writer {

        private final BigInteger version;
        private final boolean references;
        private final Map<PyObject, Integer> seen = new IdentityHashMap<>();
        private int refs;
        private byte[] data = new byte[64];
        private int size;

        private final boolean allowCode;

        Writer(BigInteger version, boolean allowCode) {
            this.version = version;
            this.allowCode = allowCode;
            this.references = version.compareTo(VERSION_THREE) >= 0;
        }

        byte[] octets() {
            byte[] out = new byte[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }

        void object(PyObject value) {
            if (value == PyNone.INSTANCE) {
                putByte(TYPE_NONE);
                return;
            }
            if (value == PyBool.TRUE) {
                putByte(TYPE_TRUE);
                return;
            }
            if (value == PyBool.FALSE) {
                putByte(TYPE_FALSE);
                return;
            }
            if (value == PyEllipsis.INSTANCE) {
                putByte(TYPE_ELLIPSIS);
                return;
            }
            if (value == ExceptionTypes.STOP_ITERATION) {
                putByte(TYPE_STOPITER);
                return;
            }
            if (value instanceof PyBytes || value instanceof PyByteArray
                    || value instanceof PyMemoryView || value instanceof PyArray) {
                // The reference reads a buffer argument straight into TYPE_STRING and does not put it in
                // the reference table, because what it writes is a copy rather than the argument.
                byte[] octets = PyMemoryView.bufferOctets(value);
                putByte(TYPE_STRING);
                putInt(octets.length);
                putBytes(octets, 0, octets.length);
                return;
            }

            boolean flag = false;
            if (references) {
                Integer prior = seen.get(value);
                if (prior != null) {
                    putByte(TYPE_REF);
                    putInt(prior);
                    return;
                }
                flag = true;
                seen.put(value, refs++);
            }

            if (value instanceof PyInt number) {
                putInteger(number.getValue(), flag);
                return;
            }
            if (value instanceof PyFloat number) {
                double real = number.getValue();
                if (version.compareTo(VERSION_TWO) >= 0) {
                    putByte(TYPE_BINARY_FLOAT | (flag ? FLAG_REF : 0));
                    putDouble(real);
                } else {
                    putByte(TYPE_FLOAT | (flag ? FLAG_REF : 0));
                    putLegacyFloat(real);
                }
                return;
            }
            if (value instanceof PyComplex number) {
                if (version.compareTo(VERSION_TWO) >= 0) {
                    putByte(TYPE_BINARY_COMPLEX | (flag ? FLAG_REF : 0));
                    putDouble(number.real());
                    putDouble(number.imaginary());
                } else {
                    putByte(TYPE_COMPLEX | (flag ? FLAG_REF : 0));
                    putLegacyFloat(number.real());
                    putLegacyFloat(number.imaginary());
                }
                return;
            }
            if (value instanceof PyStr text) {
                byte[] octets = utf8(text.getValue());
                boolean ascii = octets.length == text.getValue().length();
                if (ascii && version.compareTo(BigInteger.valueOf(4)) >= 0) {
                    if (octets.length < 256) {
                        putByte(TYPE_SHORT_ASCII | (flag ? FLAG_REF : 0));
                        putByte(octets.length);
                    } else {
                        putByte(TYPE_ASCII | (flag ? FLAG_REF : 0));
                        putInt(octets.length);
                    }
                } else {
                    // Everything the short codes would say, TYPE_UNICODE says too: the reference reads
                    // it back as the same string, and versions before 4 were written this way anyway.
                    putByte(TYPE_UNICODE | (flag ? FLAG_REF : 0));
                    putInt(octets.length);
                }
                putBytes(octets, 0, octets.length);
                return;
            }
            if (value instanceof PyTuple tuple) {
                if (version.compareTo(VERSION_FOUR) >= 0 && tuple.size() < 256) {
                    putByte(TYPE_SMALL_TUPLE | (flag ? FLAG_REF : 0));
                    putByte(tuple.size());
                } else {
                    putByte(TYPE_TUPLE | (flag ? FLAG_REF : 0));
                    putInt(tuple.size());
                }
                for (int i = 0; i < tuple.size(); i++) {
                    object(tuple.at(i));
                }
                return;
            }
            if (value instanceof PyList list) {
                putByte(TYPE_LIST | (flag ? FLAG_REF : 0));
                putInt(list.size());
                for (int i = 0; i < list.size(); i++) {
                    object(list.at(i));
                }
                return;
            }
            if (value instanceof PyDict dict) {
                putByte(TYPE_DICT | (flag ? FLAG_REF : 0));
                for (PyObject key : dict.keys()) {
                    object(key);
                    object(dict.get(key));
                }
                putByte(TYPE_NULL);
                return;
            }
            if (value instanceof PySet set) {
                putByte((set instanceof PyFrozenSet ? TYPE_FROZENSET : TYPE_SET)
                        | (flag ? FLAG_REF : 0));
                putInt(set.size());
                for (PyObject item : set.items) {
                    object(item);
                }
                return;
            }
            if (value instanceof PyCode code) {
                // The reference has a TYPE_CODE writer, but this runtime's code objects are its own
                // form; writing one as reference bytes is a gap the milestone notes name rather than a
                // format invented here. The flag's own refusal is still the reference's sentence.
                if (!allowCode) {
                    throw PyRaise.raise(
                            ExceptionTypes.VALUE_ERROR, "marshalling code objects is disallowed");
                }
                throw PyRaise.raise(ExceptionTypes.VALUE_ERROR, "unmarshallable object");
            }
            throw PyRaise.raise(ExceptionTypes.VALUE_ERROR, "unmarshallable object");
        }

        /**
         * Integers are 32 bits when they fit, and otherwise the base-2<sup>15</sup> digit form.
         *
         * <p>{@code TYPE_INT64} is absent from the writer because the reference stopped writing it: a
         * value wider than an {@code int} goes straight to {@code TYPE_LONG}. The reader still knows
         * the code, because a stream from an older writer may carry one.
         */
        private void putInteger(BigInteger number, boolean flag) {
            if (number.bitLength() < 32) {
                putByte(TYPE_INT | (flag ? FLAG_REF : 0));
                putInt(number.intValue());
                return;
            }
            putByte(TYPE_LONG | (flag ? FLAG_REF : 0));
            BigInteger magnitude = number.abs();
            int digits = (magnitude.bitLength() + 14) / 15;
            putInt(number.signum() < 0 ? -digits : digits);
            for (int i = 0; i < digits; i++) {
                int digit = magnitude.shiftRight(15 * i).and(BigInteger.valueOf(0x7fff)).intValue();
                putByte(digit & 0xff);
                putByte((digit >>> 8) & 0xff);
            }
        }

        private void putLegacyFloat(double number) {
            String text;
            if (Double.isNaN(number)) {
                text = "nan";
            } else if (Double.isInfinite(number)) {
                text = number > 0 ? "inf" : "-inf";
            } else {
                text = Double.toString(number);
            }
            byte[] octets = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            putByte(octets.length);
            putBytes(octets, 0, octets.length);
        }

        private void putInt(int value) {
            putByte(value & 0xff);
            putByte((value >>> 8) & 0xff);
            putByte((value >>> 16) & 0xff);
            putByte((value >>> 24) & 0xff);
        }

        private void putDouble(double value) {
            long bits = Double.doubleToLongBits(value);
            for (int i = 0; i < 8; i++) {
                putByte((int) ((bits >>> (8 * i)) & 0xff));
            }
        }

        private void putByte(int value) {
            if (size == data.length) {
                byte[] grown = new byte[data.length * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = (byte) value;
        }

        private void putBytes(byte[] source, int from, int count) {
            for (int i = 0; i < count; i++) {
                putByte(source[from + i]);
            }
        }
    }

    // --- reading ------------------------------------------------------------------------------

    /** The bytes a {@code load} or {@code loads} reads, in whichever of the two shapes it arrived. */
    private static final class Input {

        private final byte[] data;
        private int at;
        private final PyObject file;
        private final PyObject read;

        Input(byte[] data) {
            this.data = data;
            this.file = null;
            this.read = null;
        }

        private Input(PyObject file, PyObject read) {
            this.data = null;
            this.file = file;
            this.read = read;
        }

        /**
         * A file-backed source: each request asks the file for exactly the octets it needs, so the rest
         * of the file is still there for the next {@code load} — which is how the reference's byte at a
         * time reader leaves it.
         */
        static Input ofFile(PyObject file) {
            return new Input(file, Slots.getAttr(file, "read"));
        }

        int readByte() {
            if (data != null) {
                if (at >= data.length) {
                    throw eof("EOF read where object expected");
                }
                return data[at++] & 0xff;
            }
            byte[] one = new byte[1];
            read(one, 0, 1);
            return one[0] & 0xff;
        }

        void read(byte[] into, int offset, int count) {
            if (data != null) {
                if (count > data.length - at) {
                    throw eof("marshal data too short");
                }
                System.arraycopy(data, at, into, offset, count);
                at += count;
                return;
            }
            int got = 0;
            while (got < count) {
                PyObject chunk = Slots.call(read, new PyObject[] {PyInt.of(count - got)}, null);
                if (chunk instanceof PyStr) {
                    throw Slots.typeError("file.read() returned not bytes but str");
                }
                byte[] octets;
                try {
                    octets = PyMemoryView.bufferOctets(chunk);
                } catch (PyRaise refused) {
                    throw Slots.typeError(
                            "file.read() returned not bytes but %s", chunk.getType().filedName());
                }
                if (octets.length == 0) {
                    throw eof("marshal data too short");
                }
                int take = Math.min(octets.length, count - got);
                System.arraycopy(octets, 0, into, offset + got, take);
                got += take;
                if (take < octets.length) {
                    // A file object that answered more than it was asked for; the surplus cannot be put
                    // back, so it is discarded rather than silently mistaken for the next object.
                    break;
                }
            }
        }
    }

    private static final class Reader {

        private final Input input;
        private final boolean allowCode;
        private final List<PyObject> refs = new ArrayList<>();

        Reader(Input input, boolean allowCode) {
            this.input = input;
            this.allowCode = allowCode;
        }

        PyObject object() {
            int code = input.readByte();
            int flag = code & FLAG_REF;
            int type = code & ~FLAG_REF;

            if (type == TYPE_NULL) {
                if (flag != 0) {
                    throw bad("bad marshal data (unknown type code)");
                }
                return null;
            }
            if (type == TYPE_REF) {
                int index = int32();
                if (index < 0 || index >= refs.size()) {
                    throw bad("bad marshal data (invalid reference)");
                }
                return refs.get(index);
            }
            if (type == TYPE_NONE) {
                return PyNone.INSTANCE;
            }
            if (type == TYPE_TRUE) {
                return PyBool.TRUE;
            }
            if (type == TYPE_FALSE) {
                return PyBool.FALSE;
            }
            if (type == TYPE_ELLIPSIS) {
                return PyEllipsis.INSTANCE;
            }
            if (type == TYPE_STOPITER) {
                return ExceptionTypes.STOP_ITERATION;
            }
            if (type == TYPE_INT) {
                return checked(PyInt.of(int32()), flag);
            }
            if (type == TYPE_INT64) {
                return checked(PyInt.of(int64()), flag);
            }
            if (type == TYPE_LONG) {
                return checked(longValue(), flag);
            }
            if (type == TYPE_BINARY_FLOAT) {
                return checked(PyFloat.of(double64()), flag);
            }
            if (type == TYPE_FLOAT) {
                return checked(PyFloat.of(legacyFloat()), flag);
            }
            if (type == TYPE_BINARY_COMPLEX) {
                double real = double64();
                return checked(PyComplex.of(real, double64()), flag);
            }
            if (type == TYPE_COMPLEX) {
                double real = legacyFloat();
                return checked(PyComplex.of(real, legacyFloat()), flag);
            }
            if (type == TYPE_STRING) {
                int length = length();
                byte[] octets = new byte[length];
                input.read(octets, 0, length);
                return checked(PyBytes.wrap(octets), flag);
            }
            if (type == TYPE_UNICODE || type == TYPE_INTERNED) {
                return checked(text(length()), flag);
            }
            if (type == TYPE_ASCII || type == TYPE_ASCII_INTERNED) {
                return checked(ascii(length()), flag);
            }
            if (type == TYPE_SHORT_ASCII || type == TYPE_SHORT_ASCII_INTERNED) {
                return checked(ascii(input.readByte()), flag);
            }
            if (type == TYPE_TUPLE || type == TYPE_SMALL_TUPLE) {
                int length = type == TYPE_SMALL_TUPLE ? input.readByte() : length();
                PyTuple tuple = PyTuple.filling(length);
                register(tuple, flag);
                for (int i = 0; i < length; i++) {
                    PyObject item = object();
                    if (item == null) {
                        throw Slots.typeError("NULL object in marshal data for tuple");
                    }
                    tuple.fill(i, item);
                }
                return tuple;
            }
            if (type == TYPE_LIST) {
                int length = length();
                PyList list = PyList.empty();
                register(list, flag);
                for (int i = 0; i < length; i++) {
                    PyObject item = object();
                    if (item == null) {
                        throw Slots.typeError("NULL object in marshal data for list");
                    }
                    list.add(item);
                }
                return list;
            }
            if (type == TYPE_DICT) {
                PyDict dict = PyDict.empty();
                register(dict, flag);
                for (;;) {
                    PyObject key = object();
                    if (key == null) {
                        return dict;
                    }
                    PyObject value = object();
                    if (value == null) {
                        throw Slots.typeError("NULL object in marshal data for dict");
                    }
                    dict.put(key, value);
                }
            }
            if (type == TYPE_SET || type == TYPE_FROZENSET) {
                int length = length();
                PySet set = PySet.empty();
                int index = register(set, flag);
                for (int i = 0; i < length; i++) {
                    PyObject item = object();
                    if (item == null) {
                        throw Slots.typeError("NULL object in marshal data for set");
                    }
                    set.add(item);
                }
                if (type == TYPE_SET) {
                    return set;
                }
                PyFrozenSet frozen = PyFrozenSet.of(set.items.toArray(new PyObject[0]));
                if (index >= 0) {
                    // A frozen set cannot be filled, so the table held its builder. Nothing a Python
                    // program can build refers back to a frozenset under construction, so the swap is
                    // only visible to a hand-made stream.
                    refs.set(index, frozen);
                }
                return frozen;
            }
            if (type == TYPE_CODE) {
                if (!allowCode) {
                    throw PyRaise.raise(
                            ExceptionTypes.VALUE_ERROR,
                            "unmarshalling code objects is disallowed");
                }
                throw PyRaise.raise(
                        ExceptionTypes.VALUE_ERROR,
                        "cannot unmarshal code objects: this runtime keeps code in its own form");
            }
            throw bad("bad marshal data (unknown type code)");
        }

        private int register(PyObject value, int flag) {
            if (flag == 0) {
                return -1;
            }
            refs.add(value);
            return refs.size() - 1;
        }

        private PyObject checked(PyObject value, int flag) {
            register(value, flag);
            return value;
        }

        private PyObject text(int length) {
            byte[] octets = new byte[length];
            input.read(octets, 0, length);
            return PyStr.of(utf8(octets));
        }

        private PyObject ascii(int length) {
            byte[] octets = new byte[length];
            input.read(octets, 0, length);
            char[] text = new char[length];
            for (int i = 0; i < length; i++) {
                text[i] = (char) (octets[i] & 0xff);
            }
            return PyStr.of(new String(text));
        }

        private int length() {
            int length = int32();
            if (length < 0) {
                throw bad("bad marshal data (negative length)");
            }
            return length;
        }

        private PyObject longValue() {
            int digits = int32();
            boolean negative = digits < 0;
            if (negative) {
                digits = -digits;
            }
            if (digits > 10_000_000) {
                throw bad("bad marshal data (long too large)");
            }
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < digits; i++) {
                int digit = (input.readByte() & 0xff) | ((input.readByte() & 0xff) << 8);
                value = value.or(BigInteger.valueOf(digit).shiftLeft(15 * i));
            }
            return PyInt.of(negative ? value.negate() : value);
        }

        private int int32() {
            byte[] octets = new byte[4];
            input.read(octets, 0, 4);
            return (octets[0] & 0xff)
                    | ((octets[1] & 0xff) << 8)
                    | ((octets[2] & 0xff) << 16)
                    | ((octets[3] & 0xff) << 24);
        }

        private long int64() {
            byte[] octets = new byte[8];
            input.read(octets, 0, 8);
            long value = 0;
            for (int i = 7; i >= 0; i--) {
                value = (value << 8) | (octets[i] & 0xff);
            }
            return value;
        }

        private double double64() {
            long bits = 0;
            byte[] octets = new byte[8];
            input.read(octets, 0, 8);
            for (int i = 7; i >= 0; i--) {
                bits = (bits << 8) | (octets[i] & 0xff);
            }
            return Double.longBitsToDouble(bits);
        }

        private double legacyFloat() {
            int length = input.readByte();
            byte[] octets = new byte[length];
            input.read(octets, 0, length);
            String text = new String(octets, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (text.equalsIgnoreCase("inf") || text.equalsIgnoreCase("infinity")
                    || text.equals("+inf")) {
                return Double.POSITIVE_INFINITY;
            }
            if (text.equalsIgnoreCase("-inf") || text.equalsIgnoreCase("-infinity")) {
                return Double.NEGATIVE_INFINITY;
            }
            if (text.equalsIgnoreCase("nan") || text.equalsIgnoreCase("-nan")) {
                return Double.NaN;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException malformed) {
                throw bad("bad marshal data (invalid float)");
            }
        }
    }

    // --- the two error shapes -------------------------------------------------------------------

    private static PyRaise eof(String message) {
        return PyRaise.raise(ExceptionTypes.EOF_ERROR, message);
    }

    private static PyRaise bad(String message) {
        return PyRaise.raise(ExceptionTypes.VALUE_ERROR, message);
    }

    // --- text -----------------------------------------------------------------------------------

    /**
     * UTF-8 with {@code surrogatepass}, which is what {@code marshal.c} uses: a lone surrogate is
     * written as its three-byte form rather than refused, because a string from a marshal stream may
     * legitimately hold one.
     */
    private static byte[] utf8(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int point;
            if (Character.isHighSurrogate(ch) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                point = Character.toCodePoint(ch, text.charAt(++i));
            } else {
                point = ch;
            }
            if (point < 0x80) {
                out.write(point);
            } else if (point < 0x800) {
                out.write(0xc0 | (point >>> 6));
                out.write(0x80 | (point & 0x3f));
            } else if (point < 0x10000) {
                out.write(0xe0 | (point >>> 12));
                out.write(0x80 | ((point >>> 6) & 0x3f));
                out.write(0x80 | (point & 0x3f));
            } else {
                out.write(0xf0 | (point >>> 18));
                out.write(0x80 | ((point >>> 12) & 0x3f));
                out.write(0x80 | ((point >>> 6) & 0x3f));
                out.write(0x80 | (point & 0x3f));
            }
        }
        return out.toByteArray();
    }

    /** The inverse of {@link #utf8}, accepting the surrogate forms it writes. */
    private static String utf8(byte[] octets) {
        StringBuilder out = new StringBuilder(octets.length);
        int at = 0;
        while (at < octets.length) {
            int lead = octets[at] & 0xff;
            if (lead < 0x80) {
                out.append((char) lead);
                at++;
                continue;
            }
            int extra;
            int point;
            if ((lead & 0xe0) == 0xc0) {
                extra = 1;
                point = lead & 0x1f;
            } else if ((lead & 0xf0) == 0xe0) {
                extra = 2;
                point = lead & 0x0f;
            } else if ((lead & 0xf8) == 0xf0) {
                extra = 3;
                point = lead & 0x07;
            } else {
                throw Slots.unicodeDecode("utf-8", octets, at, at + 1, "invalid start byte");
            }
            if (at + extra >= octets.length) {
                throw Slots.unicodeDecode(
                        "utf-8", octets, at, octets.length, "unexpected end of data");
            }
            for (int i = 1; i <= extra; i++) {
                int next = octets[at + i] & 0xff;
                if ((next & 0xc0) != 0x80) {
                    throw Slots.unicodeDecode(
                            "utf-8", octets, at, at + i + 1, "invalid continuation byte");
                }
                point = (point << 6) | (next & 0x3f);
            }
            out.appendCodePoint(point);
            at += extra + 1;
        }
        return out.toString();
    }

    // --- files ----------------------------------------------------------------------------------

    private static void writeTo(PyObject file, byte[] octets) {
        Slots.call(
                Slots.getAttr(file, "write"),
                new PyObject[] {PyBytes.wrap(octets)},
                null);
    }
}
