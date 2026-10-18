package org.python.front.cpython314;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.python.core.ExceptionTypes;
import org.python.core.PyBool;
import org.python.core.PyBytes;
import org.python.core.PyCode;
import org.python.core.PyComplex;
import org.python.core.PyDict;
import org.python.core.PyEllipsis;
import org.python.core.PyFloat;
import org.python.core.PyFrozenSet;
import org.python.core.PyInt;
import org.python.core.PyList;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PySet;
import org.python.core.PySlice;
import org.python.core.PyStr;
import org.python.core.PyTuple;
import org.python.core.PyType;

/**
 * Reader for CPython's marshal format, version 5 — the stream a {@code .pyc} file carries.
 *
 * The authority for this file is {@code Python/marshal.c} of the matching CPython release, whose
 * code-object write order and reference-table rules are reproduced rather than assumed. Two details
 * are easy to get wrong and both are load-bearing:
 *
 * <ul>
 *   <li>An object takes its reference-table slot in the same order the writer allocated it, and a
 *       container claims its slot before any child is read: tuple, list, dict and set register the
 *       instance itself, while frozenset, slice and code reserve the index and fill it afterwards.
 *   <li>Singletons — {@code None}, {@code True}, {@code False}, {@code Ellipsis}, the
 *       {@code StopIteration} class and {@code NULL} — never enter the reference table, so they
 *       consume no index.
 * </ul>
 *
 * Code is the only shape a 3.14 {@code .pyc} carries, but every type code the format defines is
 * read here, so that a stream from {@code marshal.dumps} of any version up to 5 is a supported
 * input. {@code TYPE_INT64} is legacy in exactly that sense: CPython's reader still accepts it.
 */
public final class MarshalReader {

    /** What {@code Include/marshal.h} calls Py_MARSHAL_VERSION; the only version read here. */
    public static final int VERSION = 5;

    private static final int FLAG_REF = 0x80;
    private static final int MAX_DEPTH = 2000;

    /** Digits on the wire are base 2**15, whatever the writer's internal digit width is. */
    private static final int MARSHAL_DIGIT_LIMIT = 1 << 15;

    private static final int TYPE_NULL = '0';
    private static final int TYPE_NONE = 'N';
    private static final int TYPE_FALSE = 'F';
    private static final int TYPE_TRUE = 'T';
    private static final int TYPE_STOPITER = 'S';
    private static final int TYPE_ELLIPSIS = '.';
    private static final int TYPE_BINARY_FLOAT = 'g';
    private static final int TYPE_BINARY_COMPLEX = 'y';
    private static final int TYPE_LONG = 'l';
    private static final int TYPE_STRING = 's';
    private static final int TYPE_TUPLE = '(';
    private static final int TYPE_LIST = '[';
    private static final int TYPE_DICT = '{';
    private static final int TYPE_CODE = 'c';
    private static final int TYPE_UNICODE = 'u';
    private static final int TYPE_SET = '<';
    private static final int TYPE_FROZENSET = '>';
    private static final int TYPE_SLICE = ':';
    private static final int TYPE_INTERNED = 't';
    private static final int TYPE_ASCII = 'a';
    private static final int TYPE_ASCII_INTERNED = 'A';
    private static final int TYPE_SHORT_ASCII = 'z';
    private static final int TYPE_SHORT_ASCII_INTERNED = 'Z';
    private static final int TYPE_INT = 'i';
    private static final int TYPE_SMALL_TUPLE = ')';
    private static final int TYPE_COMPLEX = 'x';
    private static final int TYPE_FLOAT = 'f';
    private static final int TYPE_INT64 = 'I';
    private static final int TYPE_REF = 'r';

    private final byte[] data;
    private final List<PyObject> refs = new ArrayList<>();
    private int pos;
    private int depth;

    private MarshalReader(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
    }

    /** Read a whole stream. A {@code null} result means it held a bare TYPE_NULL. */
    public static PyObject read(byte[] data) {
        return new MarshalReader(data, 0).readObject();
    }

    /** Read the single code object a {@code .pyc} payload holds. */
    public static PyCode readCode(byte[] data, int offset) {
        PyObject value = new MarshalReader(data, offset).readObject();
        if (!(value instanceof PyCode code)) {
            throw MarshalFormatException.badData(value == null
                    ? "expected a code object, found null"
                    : "expected a code object, found " + value.getType().name());
        }
        return code;
    }

    private PyObject readObject() {
        if (++depth > MAX_DEPTH) {
            throw MarshalFormatException.badData("recursion limit exceeded");
        }
        try {
            int code = readByte();
            int flag = (code & FLAG_REF) == 0 ? 0 : FLAG_REF;
            return switch (code & ~FLAG_REF) {
                case TYPE_NULL -> null;
                case TYPE_NONE -> PyNone.INSTANCE;
                case TYPE_FALSE -> PyBool.FALSE;
                case TYPE_TRUE -> PyBool.TRUE;
                case TYPE_STOPITER -> ExceptionTypes.STOP_ITERATION;
                case TYPE_ELLIPSIS -> PyEllipsis.INSTANCE;
                case TYPE_INT -> register(PyInt.of(readIntLE()), flag);
                case TYPE_INT64 -> register(PyInt.of(BigInteger.valueOf(readLongLE())), flag);
                case TYPE_LONG -> register(PyInt.of(readMarshaledLong()), flag);
                case TYPE_BINARY_FLOAT -> register(PyFloat.of(readDoubleLE()), flag);
                case TYPE_BINARY_COMPLEX -> register(
                        PyComplex.of(readDoubleLE(), readDoubleLE()), flag);
                case TYPE_FLOAT -> register(PyFloat.of(readTextDouble()), flag);
                case TYPE_COMPLEX -> register(PyComplex.of(readTextDouble(), readTextDouble()), flag);
                case TYPE_STRING -> register(PyBytes.wrap(readBytes(readSize())), flag);
                case TYPE_UNICODE -> register(PyStr.of(readUtf8(readSize())), flag);
                case TYPE_INTERNED -> register(PyStr.interned(readUtf8(readSize())), flag);
                case TYPE_ASCII -> register(PyStr.of(readLatin1(readSize())), flag);
                case TYPE_ASCII_INTERNED -> register(PyStr.interned(readLatin1(readSize())), flag);
                case TYPE_SHORT_ASCII -> register(PyStr.of(readLatin1(readByte())), flag);
                case TYPE_SHORT_ASCII_INTERNED ->
                        register(PyStr.interned(readLatin1(readByte())), flag);
                case TYPE_TUPLE -> readTuple(readSize(), flag);
                case TYPE_SMALL_TUPLE -> readTuple(readByte(), flag);
                case TYPE_LIST -> readList(flag);
                case TYPE_DICT -> readDict(flag);
                case TYPE_SET -> readSet(flag);
                case TYPE_FROZENSET -> readFrozenSet(flag);
                case TYPE_SLICE -> readSlice(flag);
                case TYPE_CODE -> readCodeObject(flag);
                case TYPE_REF -> readRef();
                default -> throw MarshalFormatException.badData(
                        "unknown type code 0x" + Integer.toHexString(code));
            };
        } finally {
            depth--;
        }
    }

    /** Append to the reference table, for objects that exist the moment they are read. */
    private PyObject register(PyObject value, int flag) {
        if (flag != 0) {
            refs.add(value);
        }
        return value;
    }

    /** Claim the index an object will occupy, for containers still being filled. */
    private int reserve(int flag) {
        if (flag == 0) {
            return -1;
        }
        refs.add(null);
        return refs.size() - 1;
    }

    private PyObject complete(PyObject value, int index) {
        if (index >= 0) {
            refs.set(index, value);
        }
        return value;
    }

    /**
     * {@code r_tuple}: the tuple is registered before its items exist, because a stream may and
     * sometimes does reference a tuple still being read — only frozenset, slice and code can be
     * reserved, the three types the writer marks incomplete.
     */
    private PyObject readTuple(int length, int flag) {
        PyTuple tuple = PyTuple.filling(length);
        register(tuple, flag);
        for (int i = 0; i < length; i++) {
            tuple.fill(i, nonNullChild("tuple"));
        }
        return tuple;
    }

    private PyObject readList(int flag) {
        int length = readSize();
        PyList list = PyList.empty();
        register(list, flag);
        for (int i = 0; i < length; i++) {
            list.add(nonNullChild("list"));
        }
        return list;
    }

    private PyObject readDict(int flag) {
        PyDict dict = PyDict.empty();
        register(dict, flag);
        while (true) {
            PyObject key = readObject();
            if (key == null) {
                break;
            }
            PyObject value = readObject();
            if (value == null) {
                break;
            }
            dict.put(key, value);
        }
        return dict;
    }

    private PyObject readSet(int flag) {
        int length = readSize();
        PySet set = PySet.empty();
        register(set, flag);
        for (int i = 0; i < length; i++) {
            set.add(nonNullChild("set"));
        }
        return set;
    }

    private PyObject readFrozenSet(int flag) {
        int length = readSize();
        int index = reserve(flag);
        Set<PyObject> entries = new LinkedHashSet<>();
        for (int i = 0; i < length; i++) {
            entries.add(nonNullChild("set"));
        }
        PyFrozenSet set = entries.isEmpty()
                ? PyFrozenSet.EMPTY
                : PyFrozenSet.of(entries.toArray(new PyObject[0]));
        return complete(set, index);
    }

    private PyObject readSlice(int flag) {
        int index = reserve(flag);
        PyObject start = orNone(readObject());
        PyObject stop = orNone(readObject());
        PyObject step = orNone(readObject());
        return complete(PySlice.of(start, stop, step), index);
    }

    /**
     * Field order copied from {@code w_object}'s {@code TYPE_CODE} branch: five ints, then code,
     * consts, names and the localsplus pair, then the three names, firstlineno and the two byte
     * tables.
     */
    private PyObject readCodeObject(int flag) {
        int index = reserve(flag);
        int argCount = readIntLE();
        int posOnlyArgCount = readIntLE();
        int kwOnlyArgCount = readIntLE();
        int stackSize = readIntLE();
        int flags = readIntLE();
        PyBytes code = expectBytes("co_code");
        PyTuple constants = expectTuple("co_consts");
        PyTuple names = expectTuple("co_names");
        PyTuple localsPlusNames = expectTuple("co_localsplusnames");
        PyBytes localsPlusKinds = expectBytes("co_localspluskinds");
        String fileName = expectText("co_filename");
        String name = expectText("co_name");
        String qualName = expectText("co_qualname");
        int firstLine = readIntLE();
        PyBytes lineTable = expectBytes("co_linetable");
        PyBytes exceptionTable = expectBytes("co_exceptiontable");

        Cpython314Code result = new Cpython314Code(
                argCount,
                posOnlyArgCount,
                kwOnlyArgCount,
                stackSize,
                flags,
                code,
                constants,
                names,
                localsPlusNames,
                localsPlusKinds,
                fileName,
                name,
                qualName,
                firstLine,
                lineTable,
                exceptionTable);
        return complete(result, index);
    }

    private PyObject readRef() {
        int index = readIntLE();
        if (index < 0 || index >= refs.size() || refs.get(index) == null) {
            throw MarshalFormatException.badData("invalid reference");
        }
        return refs.get(index);
    }

    private PyObject nonNullChild(String container) {
        PyObject item = readObject();
        if (item == null) {
            throw MarshalFormatException.badData("NULL object in marshal data for " + container);
        }
        return item;
    }

    private static PyObject orNone(PyObject value) {
        return value == null ? PyNone.INSTANCE : value;
    }

    private PyBytes expectBytes(String field) {
        PyObject value = nonNullChild(field);
        if (value instanceof PyBytes bytes) {
            return bytes;
        }
        throw MarshalFormatException.badData(field + " is not a bytes object");
    }

    private PyTuple expectTuple(String field) {
        PyObject value = nonNullChild(field);
        if (value instanceof PyTuple tuple) {
            return tuple;
        }
        throw MarshalFormatException.badData(field + " is not a tuple");
    }

    private String expectText(String field) {
        PyObject value = nonNullChild(field);
        if (value instanceof PyStr text) {
            return text.getValue();
        }
        throw MarshalFormatException.badData(field + " is not a string");
    }

    /**
     * {@code TYPE_LONG}: a signed count of 15-bit digits, then that many little-endian shorts.
     * Folded from the top down, since the stream is least-significant first.
     */
    private BigInteger readMarshaledLong() {
        int count = readIntLE();
        int digits = Math.abs(count);
        int[] limbs = new int[digits];
        for (int i = 0; i < digits; i++) {
            limbs[i] = readShortLE();
            if (limbs[i] >= MARSHAL_DIGIT_LIMIT) {
                throw MarshalFormatException.badData("digit out of range in long");
            }
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = digits - 1; i >= 0; i--) {
            value = value.shiftLeft(15).add(BigInteger.valueOf(limbs[i]));
        }
        return count < 0 ? value.negate() : value;
    }

    private int readByte() {
        if (pos >= data.length) {
            throw MarshalFormatException.truncated();
        }
        return data[pos++] & 0xff;
    }

    private byte[] readBytes(int length) {
        if (length < 0 || pos + length > data.length) {
            throw MarshalFormatException.truncated();
        }
        byte[] out = new byte[length];
        System.arraycopy(data, pos, out, 0, length);
        pos += length;
        return out;
    }

    private int readSize() {
        int size = readIntLE();
        if (size < 0) {
            throw MarshalFormatException.badData("negative size");
        }
        return size;
    }

    private int readIntLE() {
        byte[] b = readBytes(4);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    private long readLongLE() {
        byte[] b = readBytes(8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[i] & 0xffL);
        }
        return v;
    }

    private int readShortLE() {
        byte[] b = readBytes(2);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8;
    }

    private double readDoubleLE() {
        return Double.longBitsToDouble(readLongLE());
    }

    /**
     * Version 0's textual float: a length byte, then {@code %.17g}. The writer spells infinity and
     * NaN the C way, which {@link Double#parseDouble} rejects.
     */
    private double readTextDouble() {
        String text = new String(readBytes(readByte()), StandardCharsets.US_ASCII);
        switch (text) {
            case "inf":
            case "+inf":
                return Double.POSITIVE_INFINITY;
            case "-inf":
                return Double.NEGATIVE_INFINITY;
            case "nan":
                return Double.NaN;
            default:
                return Double.parseDouble(text);
        }
    }

    private String readUtf8(int length) {
        return SurrogatePass.decode(readBytes(length), 0, length);
    }

    private String readLatin1(int length) {
        return new String(readBytes(length), StandardCharsets.ISO_8859_1);
    }
}
