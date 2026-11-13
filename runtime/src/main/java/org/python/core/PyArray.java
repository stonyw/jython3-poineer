package org.python.core;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * {@code array.array}: a run of machine-sized items, stored as CPython stores them — little-endian
 * octets in one buffer, with the Python-visible items read out of that buffer on demand.
 *
 * <p>The buffer is the whole point of the type and is what the rest of this file is shaped by. Every
 * element is {@code itemsize} octets wide and lives at {@code index * itemsize}, so {@code tobytes} is
 * a copy of a prefix of it, {@code memoryview} looks straight into it, and {@code frombytes} is a
 * {@code memcpy}. A resize is only refused while something else is looking into the buffer: CPython
 * counts the exports and says {@code cannot resize an array that is exporting buffers}, while the
 * operations that overwrite octets without moving them — {@code byteswap}, {@code reverse}, a slice
 * assignment of the same length — are allowed to run.
 *
 * <p>The capacity is allowed to exceed the length, exactly as CPython's is, because a {@code
 * __sizeof__} and a growth pattern are both observable. A list or tuple given to the constructor is
 * copied into an exact allocation; every other initializer grows the way appending does.
 */
class PyArray extends PyObject {

    /** {@code array.typecodes}, in CPython's order. */
    static final String TYPECODES = "bBuwhHiIlLqQfd";

    /** The sentence a bad code is refused with, written once. */
    private static final String BAD_TYPECODE =
            "bad typecode (must be b, B, u, w, h, H, i, I, l, L, q, Q, f or d)";

    /** {@code sizeof(arrayobject)} on a 64-bit build, which {@code __sizeof__} adds to the buffer. */
    private static final int BASE_SIZE = 64;

    private char typecode;
    private int itemsize;

    /** The raw little-endian buffer; its length is {@code capacity * itemsize}. */
    private byte[] data;

    private int count;

    /** How many live {@code memoryview}s look into {@link #data}. */
    private int exports;

    PyArray(PyType kind, char typecode) {
        super(kind);
        this.typecode = typecode;
        this.itemsize = itemsizeOf(typecode);
        this.data = new byte[0];
    }

    /** {@code array(typecode[, initializer])}, the entry {@code Constructors} and {@code Classes} use. */
    static PyArray called(PyType kind, PyObject[] args, String[] kwNames) {
        return build(kind, args, kwNames, false);
    }

    /** The value a class under {@code array.array} is allocated as; {@code __new__} does all the work. */
    static PyArray subclass(PyType kind, PyObject[] args, String[] kwNames) {
        return build(kind, args, kwNames, true);
    }

    private static PyArray build(
            PyType kind, PyObject[] args, String[] kwNames, boolean subclass) {
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError("array.array() takes no keyword arguments");
        }
        if (args.length < 1) {
            throw Slots.typeError("array() takes at least 1 argument (0 given)");
        }
        if (args.length > 2) {
            throw Slots.typeError("array() takes at most 2 arguments (%d given)", args.length);
        }
        char code = codeOf(args[0]);
        PyArray array = subclass && kind != PyType.ARRAY
                ? new PyArraySubclass(kind, code)
                : new PyArray(kind, code);
        if (args.length == 2) {
            array.initialize(args[1]);
        }
        return array;
    }

    /** The one-character code, refused in CPython's words. */
    private static char codeOf(PyObject value) {
        if (!(value instanceof PyStr text)) {
            throw Slots.typeError(
                    "array() argument 1 must be a unicode character, not %s",
                    value == PyNone.INSTANCE ? "None" : value.getType().filedName());
        }
        if (text.codePointLength() != 1) {
            throw Slots.typeError(
                    "array() argument 1 must be a unicode character, not a string of length %d",
                    text.codePointLength());
        }
        char code = (char) text.codePointAt(0);
        if (TYPECODES.indexOf(code) < 0) {
            throw Slots.valueError(BAD_TYPECODE);
        }
        return code;
    }

    /** {@code itemsize} for each code, as the reference build's C compiler lays them out. */
    static int itemsizeOf(char code) {
        return switch (code) {
            case 'b', 'B' -> 1;
            case 'h', 'H' -> 2;
            case 'i', 'I', 'u', 'w', 'f' -> 4;
            case 'l', 'L', 'q', 'Q', 'd' -> 8;
            default -> throw new IllegalArgumentException("bad typecode " + code);
        };
    }

    /** Whether the code stores text rather than a number. */
    private static boolean isUnicode(char code) {
        return code == 'u' || code == 'w';
    }

    // --- the initializer -----------------------------------------------------------------

    private void initialize(PyObject initializer) {
        if (initializer instanceof PyList list) {
            allocateExact(list.size());
            for (int i = 0; i < list.size(); i++) {
                store(i, list.at(i));
            }
        } else if (initializer instanceof PyTuple tuple) {
            allocateExact(tuple.size());
            for (int i = 0; i < tuple.size(); i++) {
                store(i, tuple.at(i));
            }
        } else if (initializer instanceof PyStr text) {
            if (!isUnicode(typecode)) {
                throw Slots.typeError(
                        "cannot use a str to initialize an array with typecode '%c'", typecode);
            }
            for (int i = 0; i < text.codePointLength(); i++) {
                appendCodePoint(text.codePointAt(i));
            }
        } else if (initializer instanceof PyBytes octets) {
            appendRaw(octets.getValue());
        } else if (initializer instanceof PyByteArray octets) {
            appendRaw(octets.raw());
        } else if (initializer instanceof PyArray that
                && isUnicode(that.typecode)
                && !isUnicode(typecode)) {
            throw Slots.typeError(
                    "cannot use a unicode array to initialize an array with typecode '%c'", typecode);
        } else {
            // A memoryview is walked as a sequence rather than read as a buffer: the reference does
            // the same, so `array('i', memoryview(b'abcd'))` is four items of one octet each.
            PyIterator iterator = Slots.iterator(initializer);
            for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
                append(item);
            }
        }
    }

    private void allocateExact(int wanted) {
        data = new byte[wanted * itemsize];
        count = wanted;
    }

    // --- storage -------------------------------------------------------------------------

    private int capacity() {
        return data.length / itemsize;
    }

    /** Room for {@code needed} items, with the slack an append-driven growth leaves. */
    private void ensureCapacity(int needed) {
        if (needed <= capacity()) {
            return;
        }
        int grown = needed + (needed >> 3) + (needed < 9 ? 3 : 6);
        data = Arrays.copyOf(data, grown * itemsize);
    }

    /** {@code array_resize}'s refusal: only a length change conflicts with a live export. */
    private void requireResizable(int newCount) {
        if (exports > 0 && newCount != count) {
            throw Slots.bufferError("cannot resize an array that is exporting buffers");
        }
    }

    private void resizeTo(int newCount) {
        requireResizable(newCount);
        if (newCount == 0) {
            count = 0;
            return;
        }
        ensureCapacity(newCount);
        count = newCount;
    }

    /** Appends whole elements, which is how {@code frombytes}/{@code fromfile} add their octets. */
    private void appendRaw(byte[] raw) {
        if (raw.length % itemsize != 0) {
            throw Slots.valueError("bytes length not a multiple of item size");
        }
        int added = raw.length / itemsize;
        int start = count;
        resizeTo(count + added);
        System.arraycopy(raw, 0, data, start * itemsize, raw.length);
    }

    // --- reading and writing one element -------------------------------------------------

    int itemSizeValue() {
        return itemsize;
    }

    int size() {
        return count;
    }

    char typecodeValue() {
        return typecode;
    }

    private long unsigned(int index) {
        return switch (itemsize) {
            case 1 -> data[index] & 0xffL;
            case 2 -> (data[index] & 0xffL) | ((data[index + 1] & 0xffL) << 8);
            case 4 -> (data[index] & 0xffL)
                    | ((data[index + 1] & 0xffL) << 8)
                    | ((data[index + 2] & 0xffL) << 16)
                    | ((data[index + 3] & 0xffL) << 24);
            default -> (data[index] & 0xffL)
                    | ((data[index + 1] & 0xffL) << 8)
                    | ((data[index + 2] & 0xffL) << 16)
                    | ((data[index + 3] & 0xffL) << 24)
                    | ((data[index + 4] & 0xffL) << 32)
                    | ((data[index + 5] & 0xffL) << 40)
                    | ((data[index + 6] & 0xffL) << 48)
                    | ((data[index + 7] & 0xffL) << 56);
        };
    }

    private void put(long value, int index) {
        for (int i = 0; i < itemsize; i++) {
            data[index + i] = (byte) (value >>> (8 * i));
        }
    }

    private void putDouble(double value, int index) {
        put(Double.doubleToRawLongBits(value), index);
    }

    /** One item, as the Python value the reference returns for this code. */
    PyObject item(int index) {
        int at = index * itemsize;
        return switch (typecode) {
            case 'b' -> PyInt.of((byte) unsigned(at));
            case 'B' -> PyInt.of(unsigned(at));
            case 'h' -> PyInt.of((short) unsigned(at));
            case 'H' -> PyInt.of(unsigned(at));
            case 'i' -> PyInt.of((int) unsigned(at));
            case 'I' -> PyInt.of(unsigned(at));
            case 'l', 'q' -> PyInt.of(unsigned(at));
            case 'L', 'Q' -> PyInt.of(new BigInteger(Long.toUnsignedString(unsigned(at))));
            case 'u', 'w' -> PyStr.of(new String(Character.toChars(ordinal(unsigned(at)))));
            case 'f' -> PyFloat.of(Float.intBitsToFloat((int) unsigned(at)));
            case 'd' -> PyFloat.of(Double.longBitsToDouble(unsigned(at)));
            default -> throw new IllegalStateException();
        };
    }

    /**
     * One {@code u} item as a code point: the refusal is {@code chr}'s, because that is what
     * {@code array_item} calls to make the character.
     */
    private static int ordinal(long raw) {
        if (raw > Character.MAX_CODE_POINT) {
            throw Slots.valueError("chr() arg not in range(0x110000)");
        }
        return (int) raw;
    }

    /** Stores one Python value at {@code index}, in each code's own conversion and refusal. */
    private void store(int index, PyObject value) {
        int at = index * itemsize;
        if (isUnicode(typecode)) {
            put(codePointOf(value), at);
        } else if (typecode == 'f') {
            put(Float.floatToRawIntBits((float) realNumber(value)), at);
        } else if (typecode == 'd') {
            putDouble(realNumber(value), at);
        } else {
            BigInteger number = Numbers.asIndex(value);
            put(narrow(number), at);
        }
    }

    /** {@code PyFloat_AsDouble}'s one refusal, in the reference's words. */
    private static double realNumber(PyObject value) {
        Double number = Numeric.doubleOf(value);
        if (number == null) {
            throw Slots.typeError(
                    "must be real number, not %s", value.getType().filedName());
        }
        return number;
    }

    /** The code point an item of a {@code u}/{@code w} array holds. */
    private static int codePointOf(PyObject value) {
        if (!(value instanceof PyStr text)) {
            throw Slots.typeError(
                    "array item must be a unicode character, not %s", value.getType().filedName());
        }
        if (text.codePointLength() != 1) {
            throw Slots.typeError(
                    "array item must be a unicode character, not a string of length %d",
                    text.codePointLength());
        }
        return text.codePointAt(0);
    }

    /** The two's-complement octets an integer contributes, refused per code in CPython's words. */
    private long narrow(BigInteger number) {
        switch (typecode) {
            case 'b' -> {
                checkRange(number, -128, 127, "signed char");
                return number.longValue();
            }
            case 'B' -> {
                checkRange(number, 0, 255, "unsigned byte integer");
                return number.longValue();
            }
            case 'h' -> {
                checkRange(number, -32768, 32767, "signed short integer");
                return number.longValue();
            }
            case 'H' -> {
                checkRange(number, 0, 65535, "unsigned short");
                return number.longValue();
            }
            case 'i' -> {
                checkRange(number, Integer.MIN_VALUE, Integer.MAX_VALUE, "signed integer");
                return number.longValue();
            }
            case 'I' -> {
                if (number.signum() < 0) {
                    throw Slots.overflowError("can't convert negative value to unsigned int");
                }
                if (number.bitLength() > 32) {
                    throw Slots.overflowError("unsigned int is greater than maximum");
                }
                return number.longValue();
            }
            case 'l' -> {
                if (number.bitLength() > 63) {
                    throw Slots.overflowError("Python int too large to convert to C long");
                }
                return number.longValue();
            }
            case 'L' -> {
                if (number.signum() < 0) {
                    throw Slots.overflowError("can't convert negative value to unsigned int");
                }
                if (number.bitLength() > 64) {
                    throw Slots.overflowError("Python int too large to convert to C unsigned long");
                }
                return number.longValue();
            }
            case 'q' -> {
                if (number.bitLength() > 63) {
                    throw Slots.overflowError("int too big to convert");
                }
                return number.longValue();
            }
            case 'Q' -> {
                if (number.signum() < 0) {
                    throw Slots.overflowError("can't convert negative int to unsigned");
                }
                if (number.bitLength() > 64) {
                    throw Slots.overflowError("int too big to convert");
                }
                return number.longValue();
            }
            default -> throw new IllegalStateException();
        }
    }

    private static void checkRange(BigInteger number, long low, long high, String noun) {
        if (number.compareTo(BigInteger.valueOf(high)) > 0) {
            throw Slots.overflowError("%s is greater than maximum", noun);
        }
        if (number.compareTo(BigInteger.valueOf(low)) < 0) {
            throw Slots.overflowError("%s is less than minimum", noun);
        }
    }

    // --- the sequence surface ------------------------------------------------------------

    private void append(PyObject value) {
        resizeTo(count + 1);
        store(count - 1, value);
    }

    private void appendCodePoint(int codePoint) {
        resizeTo(count + 1);
        put(codePoint, (count - 1) * itemsize);
    }

    @Override
    public PyObject length() {
        return PyInt.of(count);
    }

    @Override
    public PyObject truth() {
        return PyBool.of(count != 0);
    }

    @Override
    public PyObject iterator() {
        return SequenceIterators.of(this);
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    @Override
    public PyObject getItem(PyObject key) {
        if (key instanceof PySlice slice) {
            Slices.Range range = Slices.of(slice, count);
            PyArray out = new PyArray(PyType.ARRAY, typecode);
            out.allocateExact(range.count());
            for (int i = 0; i < range.count(); i++) {
                System.arraycopy(
                        data, range.at(i) * itemsize, out.data, i * itemsize, itemsize);
            }
            return out;
        }
        int index = arrayIndex(key, "array index out of range");
        return item(index);
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        if (key instanceof PySlice slice) {
            assignSlice(slice, value);
            return PyNone.INSTANCE;
        }
        store(arrayIndex(key, "array assignment index out of range"), value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delItem(PyObject key) {
        if (key instanceof PySlice slice) {
            Slices.Range range = Slices.of(slice, count);
            int removed = range.count();
            int left = count - removed;
            requireResizable(left);
            if (range.step() == 1) {
                System.arraycopy(
                        data,
                        (range.start() + removed) * itemsize,
                        data,
                        range.start() * itemsize,
                        (count - range.start() - removed) * itemsize);
            } else {
                // Delete from the highest index down so every index still points at the item it named.
                int[] indices = new int[removed];
                for (int i = 0; i < removed; i++) {
                    indices[i] = range.at(i);
                }
                Arrays.sort(indices);
                for (int i = removed - 1; i >= 0; i--) {
                    int at = indices[i];
                    System.arraycopy(
                            data,
                            (at + 1) * itemsize,
                            data,
                            at * itemsize,
                            (count - at - 1) * itemsize);
                    count--;
                }
            }
            count = left;
            return PyNone.INSTANCE;
        }
        int index = arrayIndex(key, "array assignment index out of range");
        System.arraycopy(
                data, (index + 1) * itemsize, data, index * itemsize, (count - index - 1) * itemsize);
        count--;
        return PyNone.INSTANCE;
    }

    /** A non-negative index, refused in the words an array uses rather than a sequence's. */
    private int arrayIndex(PyObject key, String outOfRange) {
        if (!Numbers.isIndex(key)) {
            throw Slots.typeError("array indices must be integers");
        }
        return Numbers.wrapIndex(key, Numbers.asIndex(key), count, outOfRange);
    }

    /** {@code a[slice] = value}: only another array of the same name, with the same length for strides. */
    private void assignSlice(PySlice slice, PyObject value) {
        if (!(value instanceof PyArray source)) {
            throw Slots.typeError(
                    "can only assign array (not \"%s\") to array slice", value.getType().name());
        }
        if (source.typecode != typecode) {
            throw Slots.typeError("bad argument type for built-in operation");
        }
        Slices.Range range = Slices.of(slice, count);
        if (range.step() != 1) {
            if (range.count() != source.count) {
                throw Slots.valueError(
                        "attempt to assign array of size %d to extended slice of size %d",
                        source.count, range.count());
            }
            for (int i = 0; i < range.count(); i++) {
                System.arraycopy(
                        source.data,
                        i * itemsize,
                        data,
                        range.at(i) * itemsize,
                        itemsize);
            }
            return;
        }
        int removed = range.count();
        int next = count - removed + source.count;
        requireResizable(next);
        ensureCapacity(next);
        int start = range.start();
        // Move the tail out of the way first, then write the incoming octets into the gap.
        System.arraycopy(
                data,
                (start + removed) * itemsize,
                data,
                (start + source.count) * itemsize,
                (count - start - removed) * itemsize);
        System.arraycopy(source.data, 0, data, start * itemsize, source.count * itemsize);
        count = next;
    }

    /** Appends {@code value}'s items; a same-typed array is copied as octets, anything else walked. */
    private void extendWith(PyObject value) {
        if (value instanceof PyArray source && source.typecode == typecode) {
            appendRaw(Arrays.copyOf(source.data, source.count * itemsize));
            return;
        }
        PyIterator iterator = Slots.iterator(value);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            append(item);
        }
    }

    private int firstIndexOf(PyObject value) {
        for (int i = 0; i < count; i++) {
            if (item(i).eq(value)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public PyObject contains(PyObject value) {
        return PyBool.of(firstIndexOf(value) >= 0);
    }

    // --- comparison, hashing and display -------------------------------------------------

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (!(other instanceof PyArray that)) {
            return PyNotImplemented.INSTANCE;
        }
        return Sequences.compare(cmp, elements(), that.elements());
    }

    private PyObject[] elements() {
        PyObject[] out = new PyObject[count];
        for (int i = 0; i < count; i++) {
            out[i] = item(i);
        }
        return out;
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyArray that
                && count == that.count
                && Arrays.equals(
                        Arrays.copyOf(data, count * itemsize),
                        Arrays.copyOf(that.data, that.count * that.itemsize));
    }

    @Override
    public long hash() {
        throw Slots.typeError("unhashable type: '%s'", getType().filedName());
    }

    @Override
    public String repr() {
        String name = getType().name();
        if (count == 0) {
            return name + "('" + typecode + "')";
        }
        if (isUnicode(typecode)) {
            return name + "('" + typecode + "', " + PyStr.of(toUnicode()).repr() + ")";
        }
        StringBuilder out = new StringBuilder();
        out.append(name).append("('").append(typecode).append("', [");
        for (int i = 0; i < count; i++) {
            if (i != 0) {
                out.append(", ");
            }
            out.append(item(i).repr());
        }
        return out.append("])").toString();
    }

    // --- the binary operators ------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            if (!(other instanceof PyArray that)) {
                throw Slots.typeError(
                        "can only append array (not \"%s\") to array", other.getType().name());
            }
            if (that.typecode != typecode) {
                throw Slots.typeError("bad argument type for built-in operation");
            }
            PyArray out = new PyArray(PyType.ARRAY, typecode);
            out.allocateExact(count + that.count);
            System.arraycopy(data, 0, out.data, 0, count * itemsize);
            System.arraycopy(
                    that.data, 0, out.data, count * itemsize, that.count * itemsize);
            return out;
        }
        if (op == BinaryOp.MUL) {
            return repeated(Sequences.multiplier(other));
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.MUL ? binaryOp(op, other) : PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            if (!(other instanceof PyArray that)) {
                throw Slots.typeError(
                        "can only extend array with array (not \"%s\")", other.getType().name());
            }
            if (that.typecode != typecode) {
                throw Slots.typeError("can only extend with array of same kind");
            }
            if (that.count != 0) {
                appendRaw(Arrays.copyOf(that.data, that.count * itemsize));
            }
            return this;
        }
        if (op == BinaryOp.MUL) {
            long times = Sequences.multiplier(other);
            requireResizable((int) Math.min(Integer.MAX_VALUE, count * times));
            PyArray out = repeated(times);
            replaceWith(out);
            return this;
        }
        return PyNotImplemented.INSTANCE;
    }

    /** Copies the bytes of {@code source} into this value, keeping this value's identity. */
    private void replaceWith(PyArray source) {
        data = Arrays.copyOf(source.data, source.data.length);
        count = source.count;
    }

    private PyArray repeated(long times) {
        long total = count * times;
        if (times < 0) {
            total = 0;
        }
        if (total > Integer.MAX_VALUE / Math.max(1, itemsize)) {
            throw Slots.memoryError();
        }
        PyArray out = new PyArray(PyType.ARRAY, typecode);
        out.allocateExact((int) total);
        for (int i = 0; i < total; i++) {
            System.arraycopy(
                    data, (int) (i % count) * itemsize, out.data, i * itemsize, itemsize);
        }
        return out;
    }

    // --- the methods ---------------------------------------------------------------------

    private static final Members MEMBERS =
            Members.of(PyType.ARRAY)
                    .addOne("append", ArrayDocs.APPEND_DOC, (self, value) -> {
                        ((PyArray) self).append(value);
                        return PyNone.INSTANCE;
                    })
                    .addNoArgument("buffer_info", ArrayDocs.BUFFER_INFO_DOC, self -> {
                        PyArray array = (PyArray) self;
                        // A JVM buffer has no address to publish; the length is the part a program uses.
                        return PyTuple.of(PyInt.of(0), PyInt.of(array.count));
                    })
                    .addNoArgument("byteswap", ArrayDocs.BYTESWAP_DOC, self -> {
                        ((PyArray) self).byteswap();
                        return PyNone.INSTANCE;
                    })
                    .addNoArgument("clear", ArrayDocs.CLEAR_DOC, self -> {
                        PyArray array = (PyArray) self;
                        array.requireResizable(0);
                        array.count = 0;
                        return PyNone.INSTANCE;
                    })
                    .addNoArgument("copy", ArrayDocs.COPY_DOC, self -> ((PyArray) self).copyOf())
                    .addOne("count", ArrayDocs.COUNT_DOC, (self, value) -> {
                        PyArray array = (PyArray) self;
                        int found = 0;
                        for (int i = 0; i < array.count; i++) {
                            if (array.item(i).eq(value)) {
                                found++;
                            }
                        }
                        return PyInt.of(found);
                    })
                    .add("extend", ArrayDocs.EXTEND_DOC, (self, args, kwNames) -> {
                        Arguments.onlyPositional("extend", args, kwNames, 1);
                        ((PyArray) self).extendWith(args[0]);
                        return PyNone.INSTANCE;
                    })
                    .addOne("frombytes", ArrayDocs.FROMBYTES_DOC, (self, value) -> {
                        ((PyArray) self).fromBytes(value);
                        return PyNone.INSTANCE;
                    })
                    .add("fromfile", ArrayDocs.FROMFILE_DOC, (self, args, kwNames) -> {
                        Arguments.onlyPositional("fromfile", args, kwNames, 2);
                        ((PyArray) self).fromFile(args[0], args[1]);
                        return PyNone.INSTANCE;
                    })
                    .addOne("fromlist", ArrayDocs.FROMLIST_DOC, (self, value) -> {
                        PyArray array = (PyArray) self;
                        if (!(value instanceof PyList list)) {
                            throw Slots.typeError(
                                    "fromlist() argument must be a list, not '%s'",
                                    value.getType().name());
                        }
                        for (int i = 0; i < list.size(); i++) {
                            array.append(list.at(i));
                        }
                        return PyNone.INSTANCE;
                    })
                    .addOne("fromunicode", ArrayDocs.FROMUNICODE_DOC, (self, value) -> {
                        ((PyArray) self).fromUnicode(value);
                        return PyNone.INSTANCE;
                    })
                    .add("index", ArrayDocs.INDEX_DOC, (self, args, kwNames) -> {
                        Arguments.noKeywords("array.index()", kwNames);
                        Arguments.between("index", args, kwNames, 1, 3);
                        return ((PyArray) self).indexOf(args);
                    })
                    .add("insert", ArrayDocs.INSERT_DOC, (self, args, kwNames) -> {
                        Arguments.noKeywords("array.insert()", kwNames);
                        Arguments.exactly("insert", args, kwNames, 2);
                        ((PyArray) self).insertAt(args[0], args[1]);
                        return PyNone.INSTANCE;
                    })
                    .add("pop", ArrayDocs.POP_DOC, (self, args, kwNames) -> {
                        Arguments.noKeywords("array.pop()", kwNames);
                        Arguments.atMost("pop", args, kwNames, 1);
                        return ((PyArray) self).popAt(args);
                    })
                    .addOne("remove", ArrayDocs.REMOVE_DOC, (self, value) -> {
                        PyArray array = (PyArray) self;
                        int at = array.firstIndexOf(value);
                        if (at < 0) {
                            throw Slots.valueError("array.remove(x): x not in array");
                        }
                        array.deleteAt(at);
                        return PyNone.INSTANCE;
                    })
                    .addNoArgument("reverse", ArrayDocs.REVERSE_DOC, self -> {
                        ((PyArray) self).reverse();
                        return PyNone.INSTANCE;
                    })
                    .addNoArgument("tobytes", ArrayDocs.TOBYTES_DOC, self -> ((PyArray) self).toBytes())
                    .add("tofile", ArrayDocs.TOFILE_DOC, (self, args, kwNames) -> {
                        Arguments.onlyPositional("tofile", args, kwNames, 1);
                        ((PyArray) self).toFile(args[0]);
                        return PyNone.INSTANCE;
                    })
                    .addNoArgument("tolist", ArrayDocs.TOLIST_DOC, self -> ((PyArray) self).toList())
                    .addNoArgument("tounicode", ArrayDocs.TOUNICODE_DOC, self -> {
                        PyArray array = (PyArray) self;
                        array.requireUnicode("tounicode");
                        return PyStr.of(array.toUnicode());
                    })
                    .addNoArgument("__sizeof__", ArrayDocs.SIZEOF_DOC, self -> {
                        PyArray array = (PyArray) self;
                        return PyInt.of(BASE_SIZE + array.data.length);
                    })
                    .addNoArgument("__copy__", ArrayDocs.COPY_DOC, self -> ((PyArray) self).copyOf())
                    .add("__deepcopy__", ArrayDocs.DEEPCOPY_DOC, (self, args, kwNames) -> {
                        Arguments.exactlyOne("array.__deepcopy__", args, kwNames);
                        return ((PyArray) self).copyOf();
                    })
                    .addField("typecode", "The typecode character used to create the array.",
                            self -> PyStr.of(String.valueOf(((PyArray) self).typecode)))
                    .addField("itemsize", "The size, in bytes, of one array item.",
                            self -> PyInt.of(((PyArray) self).itemsize));

    private void requireUnicode(String method) {
        if (!isUnicode(typecode)) {
            throw Slots.valueError(
                    "%s() may only be called on unicode type arrays ('u' or 'w')", method);
        }
    }

    private void byteswap() {
        for (int i = 0; i < count; i++) {
            int at = i * itemsize;
            for (int lo = 0, hi = itemsize - 1; lo < hi; lo++, hi--) {
                byte swap = data[at + lo];
                data[at + lo] = data[at + hi];
                data[at + hi] = swap;
            }
        }
    }

    private void reverse() {
        for (int lo = 0, hi = count - 1; lo < hi; lo++, hi--) {
            for (int i = 0; i < itemsize; i++) {
                byte swap = data[lo * itemsize + i];
                data[lo * itemsize + i] = data[hi * itemsize + i];
                data[hi * itemsize + i] = swap;
            }
        }
    }

    private void deleteAt(int index) {
        System.arraycopy(
                data, (index + 1) * itemsize, data, index * itemsize, (count - index - 1) * itemsize);
        count--;
    }

    private void insertAt(PyObject where, PyObject value) {
        long at = Numbers.asIndex(where).longValue();
        if (at < 0) {
            at += count;
            if (at < 0) {
                at = 0;
            }
        } else if (at > count) {
            at = count;
        }
        resizeTo(count + 1);
        System.arraycopy(data, (int) at * itemsize, data, (int) (at + 1) * itemsize,
                (count - (int) at - 1) * itemsize);
        store((int) at, value);
    }

    private PyObject popAt(PyObject[] args) {
        if (count == 0) {
            throw Slots.indexError("pop from empty array");
        }
        int index = args.length == 0
                ? count - 1
                : Numbers.wrapIndex(args[0], Numbers.asIndex(args[0]), count, "pop index out of range");
        PyObject value = item(index);
        deleteAt(index);
        return value;
    }

    private PyObject indexOf(PyObject[] args) {
        long start = 0;
        long stop = count;
        if (args.length >= 2) {
            start = sliceIndex(args[1]);
            if (start < 0) {
                start += count;
            }
        }
        if (args.length == 3) {
            stop = sliceIndex(args[2]);
            if (stop < 0) {
                stop += count;
            }
        }
        start = Math.max(0, Math.min(start, count));
        stop = Math.max(0, Math.min(stop, count));
        for (int i = (int) start; i < stop; i++) {
            if (item(i).eq(args[0])) {
                return PyInt.of(i);
            }
        }
        throw Slots.valueError("array.index(x): x not in array");
    }

    private static long sliceIndex(PyObject value) {
        if (!Numbers.isIndex(value)) {
            throw Slots.typeError("slice indices must be integers or have an __index__ method");
        }
        return Numbers.asIndex(value).longValue();
    }

    private void fromBytes(PyObject value) {
        byte[] raw;
        if (value instanceof PyBytes octets) {
            raw = octets.getValue();
        } else if (value instanceof PyByteArray octets) {
            raw = Arrays.copyOf(octets.raw(), octets.size());
        } else if (value instanceof PyMemoryView) {
            raw = PyMemoryView.bufferOctets(value);
        } else {
            throw Slots.typeError("a bytes-like object is required, not '%s'", value.getType().name());
        }
        appendRaw(raw);
    }

    private void fromUnicode(PyObject value) {
        requireUnicode("fromunicode");
        if (!(value instanceof PyStr text)) {
            throw Slots.typeError(
                    "fromunicode() argument must be str, not %s", value.getType().name());
        }
        for (int i = 0; i < text.codePointLength(); i++) {
            appendCodePoint(text.codePointAt(i));
        }
    }

    private void fromFile(PyObject file, PyObject requested) {
        long wanted = Numbers.asIndex(requested).longValue();
        if (wanted < 0) {
            throw Slots.valueError("negative count");
        }
        if (wanted == 0) {
            return;
        }
        long nbytes = wanted * itemsize;
        PyObject data = Slots.call(
                Slots.getAttr(file, "read"), new PyObject[] {PyInt.of(nbytes)}, null);
        if (!(data instanceof PyBytes)) {
            throw Slots.typeError("read() didn't return bytes");
        }
        byte[] raw = ((PyBytes) data).getValue();
        if (raw.length % itemsize != 0) {
            throw Slots.valueError("bytes length not a multiple of item size");
        }
        appendRaw(raw);
        if (raw.length != nbytes) {
            throw PyRaise.raise(ExceptionTypes.EOF_ERROR, "read() didn't return enough bytes");
        }
    }

    private void toFile(PyObject file) {
        Slots.call(Slots.getAttr(file, "write"), new PyObject[] {toBytes()}, null);
    }

    PyBytes toBytes() {
        return PyBytes.of(Arrays.copyOf(data, count * itemsize));
    }

    PyList toList() {
        PyObject[] out = new PyObject[count];
        for (int i = 0; i < count; i++) {
            out[i] = item(i);
        }
        return PyList.of(out);
    }

    /** The text a {@code u} array holds, read one UCS-4 code point at a time. */
    private String toUnicode() {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            long code = unsigned(i * itemsize);
            if (code > Character.MAX_CODE_POINT) {
                throw Slots.valueError(
                        "character U+%08x is not in range [U+0000; U+10ffff]", code);
            }
            out.appendCodePoint((int) code);
        }
        return out.toString();
    }

    private PyArray copyOf() {
        PyArray out = new PyArray(PyType.ARRAY, typecode);
        out.allocateExact(count);
        System.arraycopy(data, 0, out.data, 0, count * itemsize);
        return out;
    }

    // --- the buffer interface ------------------------------------------------------------

    /** The format a {@code memoryview} reports; the deprecated {@code u} is published as {@code w}. */
    String memoryFormatName() {
        return typecode == 'u' ? "w" : String.valueOf(typecode);
    }

    byte byteAt(int index) {
        return data[index];
    }

    void setByteAt(int index, byte value) {
        data[index] = value;
    }

    /** The octets a reader may copy out; the slack past the length is not part of the value. */
    byte[] rawBytes() {
        return Arrays.copyOf(data, count * itemsize);
    }

    void exportBuffer() {
        exports++;
    }

    void releaseBuffer() {
        if (exports > 0) {
            exports--;
        }
    }

    // --- the module's two entry points ---------------------------------------------------

    /**
     * {@code _array_reconstructor(array_type, typecode, mformat_code, bytes)}.
     *
     * <p>The callable {@code __reduce_ex__} puts in a pickle from protocol three on. The four
     * arguments are read in the reference's order, so each refusal names its own mistake: the type
     * before the subtype, the typecode before the machine format, the machine format before the
     * octets. A machine format equal to the typecode's own is the fast path — the octets are copied
     * as they stand. Every other format is read item by item: an integer format names its own
     * typecode (so a {@code uint64} arrives as {@code 'Q'}), a float format keeps the requested one,
     * and a string format decodes to text the requested unicode array then reads.
     */
    static PyObject reconstruct(PyObject[] args, String[] kwNames) {
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError("array._array_reconstructor() takes no keyword arguments");
        }
        if (args.length != 4) {
            throw Slots.typeError(
                    "_array_reconstructor expected 4 arguments, got %d", args.length);
        }
        if (!(args[0] instanceof PyType kind)) {
            throw Slots.typeError(
                    "first argument must be a type object, not %s", args[0].getType().filedName());
        }
        if (!kind.matches(PyType.ARRAY)) {
            throw Slots.typeError("%s is not a subtype of array.array", kind.name());
        }
        char code = reconstructorCode(args[1]);
        int mformat;
        try {
            mformat = Numbers.asIndex(args[2]).intValueExact();
        } catch (ArithmeticException tooWide) {
            throw Slots.overflowError("Python int too large to convert to C int");
        }
        int width = machineWidth(mformat);
        if (width == 0) {
            throw Slots.valueError("third argument must be a valid machine format code.");
        }
        if (!(args[3] instanceof PyBytes octets)) {
            throw Slots.typeError(
                    "fourth argument should be bytes, not %s", args[3].getType().filedName());
        }
        byte[] raw = octets.getValue();
        if (raw.length % width != 0) {
            throw Slots.valueError("bytes length not a multiple of item size");
        }
        if (mformat == machineFormatOf(code)) {
            return make(kind, code, octets);
        }
        boolean big = mformat % 2 == 1;
        int unit = mformat <= 19 ? 2 : 4;
        Charset charset = Charset.forName(unit == 2
                ? (big ? "UTF-16BE" : "UTF-16LE")
                : (big ? "UTF-32BE" : "UTF-32LE"));
        if (mformat <= 1) {
            return make(kind, integerCode(mformat), integers(raw, mformat, width));
        }
        if (mformat <= 13) {
            return make(kind, integerCode(mformat), integers(raw, mformat, width));
        }
        if (mformat <= 17) {
            return make(kind, code, floats(raw, mformat, width));
        }
        PyStr text = PyStr.of(new String(raw, charset));
        return make(kind, code, text);
    }

    /** The one-character second argument, with the clinic's two refusals. */
    private static char reconstructorCode(PyObject value) {
        if (value instanceof PyStr text && text.codePointLength() == 1) {
            char code = (char) text.codePointAt(0);
            if (TYPECODES.indexOf(code) >= 0) {
                return code;
            }
            throw Slots.valueError("second argument must be a valid type code");
        }
        if (value instanceof PyStr text) {
            throw Slots.typeError(
                    "_array_reconstructor(): argument 2 must be a unicode character, not a string of length %d",
                    text.codePointLength());
        }
        throw Slots.typeError(
                "_array_reconstructor() argument 2 must be a unicode character, not %s",
                value.getType().filedName());
    }

    /** The {@code mformat_code} each typecode's own machine format answers. */
    static int machineFormatOf(char code) {
        boolean big = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        int end = big ? 1 : 0;
        return switch (code) {
            case 'b' -> 1;
            case 'B' -> 0;
            case 'h' -> 4 + end;
            case 'H' -> 2 + end;
            case 'i' -> 8 + end;
            case 'I' -> 6 + end;
            case 'l', 'q' -> 12 + end;
            case 'L', 'Q' -> 10 + end;
            case 'f' -> 14 + end;
            case 'd' -> 16 + end;
            case 'u', 'w' -> 20 + end;
            default -> -1;
        };
    }

    /** The width of one machine-format item, or zero for a code the reference does not know. */
    private static int machineWidth(int mformat) {
        return switch (mformat) {
            case 0, 1 -> 1;
            case 2, 3, 4, 5 -> 2;
            case 6, 7, 8, 9, 14, 15, 18, 19 -> 4;
            case 10, 11, 12, 13, 16, 17, 20, 21 -> 8;
            default -> 0;
        };
    }

    /** The descriptor an integer machine format lands on: the last matching one wins. */
    private static char integerCode(int mformat) {
        return switch (mformat) {
            case 0 -> 'B';
            case 1 -> 'b';
            case 2, 3 -> 'H';
            case 4, 5 -> 'h';
            case 6, 7 -> 'I';
            case 8, 9 -> 'i';
            case 10, 11 -> 'Q';
            default -> 'q';
        };
    }

    /** One machine-format integer per item, read with the format's width and signedness. */
    private static PyList integers(byte[] raw, int mformat, int width) {
        boolean big = mformat % 2 == 1;
        boolean signed = mformat == 1 || mformat == 4 || mformat == 5 || mformat == 8
                || mformat == 9 || mformat == 12 || mformat == 13;
        PyObject[] out = new PyObject[raw.length / width];
        for (int i = 0; i < out.length; i++) {
            long value = 0;
            for (int octet = 0; octet < width; octet++) {
                int at = i * width + (big ? octet : width - 1 - octet);
                value = (value << 8) | (raw[at] & 0xffL);
            }
            if (signed) {
                int shift = 64 - width * 8;
                value = (value << shift) >> shift;
                out[i] = PyInt.of(value);
            } else if (width == 8 && value < 0) {
                out[i] = PyInt.of(new BigInteger(Long.toUnsignedString(value)));
            } else {
                out[i] = PyInt.of(value);
            }
        }
        return PyList.of(out);
    }

    /** One machine-format float per item. */
    private static PyList floats(byte[] raw, int mformat, int width) {
        boolean big = mformat % 2 == 1;
        PyObject[] out = new PyObject[raw.length / width];
        for (int i = 0; i < out.length; i++) {
            long bits = 0;
            for (int octet = 0; octet < width; octet++) {
                int at = i * width + (big ? octet : width - 1 - octet);
                bits = (bits << 8) | (raw[at] & 0xffL);
            }
            out[i] = width == 4
                    ? PyFloat.of(Float.intBitsToFloat((int) bits))
                    : PyFloat.of(Double.longBitsToDouble(bits));
        }
        return PyList.of(out);
    }

    /** {@code make_array}: a fresh array of {@code kind} with one initializer. */
    private static PyArray make(PyType kind, char code, PyObject initializer) {
        return build(
                kind,
                new PyObject[] {PyStr.of(String.valueOf(code)), initializer},
                null,
                kind != PyType.ARRAY);
    }
}
