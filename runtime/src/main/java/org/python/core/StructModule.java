package org.python.core;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code _struct} module: C struct layout, packing and unpacking.
 *
 * <p>{@code struct.py} is only a re-export of this module, so everything Python sees here is what
 * CPython's C implementation shows: the same format grammar, the same native alignment, the same
 * sentences when a value or a buffer does not fit.
 */
final class StructModule {

    private static final String DOC =
            "Functions to convert between Python values and C structs.\n"
            + "Python bytes objects are used to hold the data representing the C struct.\n"
            + "The format string (explained below) describes the layout of data\n"
            + "in the C struct.\n"
            + "\n"
            + "The optional first format char indicates byte order, size and alignment:\n"
            + "  @: native order, size & alignment (default)\n"
            + "  =: native order, std. size & alignment\n"
            + "  <: little-endian, std. size & alignment\n"
            + "  >: big-endian, std. size & alignment\n"
            + "  !: same as >\n"
            + "\n"
            + "The remaining characters indicate types of args and must match exactly;\n"
            + "these can be preceded by a decimal repeat count:\n"
            + "  x: pad byte (no data); c: char; b: signed byte; B: unsigned byte;\n"
            + "  ?: _Bool; h: short; H: unsigned short; i: int; I: unsigned int;\n"
            + "  l: long; L: unsigned long; q: long long; Q: unsigned long long;\n"
            + "  f: float; d: double; e: half-float;\n"
            + "  F: float complex; D: double complex.\n"
            + "Special cases (preceding decimal count indicates length):\n"
            + "  s: byte string (array of char); p: Pascal string (with count byte).\n"
            + "Special cases (only available in native format):\n"
            + "  n: ssize_t; N: size_t;\n"
            + "  P: an integer type that is wide enough to hold a pointer.\n"
            + "Whitespace between formats is ignored.\n"
            + "\n"
            + "The variable struct.error is an exception raised on errors.\n";

    private static final String CALCSIZE_DOC =
            "Return size in bytes of the struct described by the format string.";

    private static final String PACK_DOC =
            "pack(format, v1, v2, ...) -> bytes\n"
            + "\n"
            + "Return a bytes object containing the values v1, v2, ... packed according\n"
            + "to the format string.  See help(struct) for more on format strings.";

    private static final String PACK_INTO_DOC =
            "pack_into(format, buffer, offset, v1, v2, ...)\n"
            + "\n"
            + "Pack the values v1, v2, ... according to the format string and write\n"
            + "the packed bytes into the writable buffer buf starting at offset.  Note\n"
            + "that the offset is a required argument.  A negative offset counts from\n"
            + "the end of the buffer.  See help(struct) for more on format strings.";

    private static final String UNPACK_DOC =
            "Return a tuple containing values unpacked according to the format string.\n"
            + "\n"
            + "The buffer's size in bytes must be calcsize(format).\n"
            + "\n"
            + "See help(struct) for more on format strings.";

    private static final String UNPACK_FROM_DOC =
            "Return a tuple containing values unpacked according to the format string.\n"
            + "\n"
            + "The buffer's size, minus offset, must be at least calcsize(format).\n"
            + "A negative offset counts from the end of the buffer.\n"
            + "\n"
            + "See help(struct) for more on format strings.";

    private static final String ITER_UNPACK_DOC =
            "Return an iterator yielding tuples unpacked from the given bytes.\n"
            + "\n"
            + "The bytes are unpacked according to the format string, like\n"
            + "a repeated invocation of unpack_from().\n"
            + "\n"
            + "Requires that the bytes length be a multiple of the format struct size.";

    private static final String CLEAR_CACHE_DOC = "Clear the internal cache.";

    /** The compiled formats, keyed by the format text, and emptied by {@code _clearcache}. */
    private static final Map<String, Spec> CACHE = new ConcurrentHashMap<>();

    private static final boolean NATIVE_LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private StructModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_struct");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_struct");
        module.set("Struct", PyType.STRUCT);
        module.set("error", ExceptionTypes.STRUCT_ERROR);
        module.set("_clearcache", new PyNativeFunction("_clearcache", (args, kwNames) -> {
            Arguments.none("_struct._clearcache", args, kwNames);
            CACHE.clear();
            return PyNone.INSTANCE;
        }).documented(CLEAR_CACHE_DOC));
        module.set("calcsize", new PyNativeFunction("calcsize", StructModule::calcsize)
                .documented(CALCSIZE_DOC));
        module.set("pack", new PyNativeFunction("pack", StructModule::pack).documented(PACK_DOC));
        module.set("pack_into", new PyNativeFunction("pack_into", StructModule::packInto)
                .documented(PACK_INTO_DOC));
        module.set("unpack", new PyNativeFunction("unpack", StructModule::unpack)
                .documented(UNPACK_DOC));
        module.set("unpack_from", new PyNativeFunction("unpack_from", StructModule::unpackFrom)
                .documented(UNPACK_FROM_DOC));
        module.set("iter_unpack", new PyNativeFunction("iter_unpack", StructModule::iterUnpack)
                .documented(ITER_UNPACK_DOC));
        return module;
    }

    // --- the module functions ---------------------------------------------------------------

    private static PyObject calcsize(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_struct.calcsize() takes no keyword arguments");
        }
        if (args.length != 1) {
            throw Slots.typeError("_struct.calcsize() takes exactly one argument (%d given)",
                    args.length);
        }
        return PyInt.of(BigInteger.valueOf(parse(args[0]).size));
    }

    private static PyObject pack(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_struct.pack() takes no keyword arguments");
        }
        if (args.length == 0) {
            throw fail("missing format argument");
        }
        Spec spec = parse(args[0]);
        return PyBytes.of(packValues(spec, args, 1, args.length - 1));
    }

    private static PyObject packInto(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_struct.pack_into() takes no keyword arguments");
        }
        if (args.length == 0) {
            throw fail("missing format argument");
        }
        Spec spec = parse(args[0]);
        if (args.length < 2) {
            throw fail("pack_into expected buffer argument");
        }
        if (args.length < 3) {
            throw fail("pack_into expected offset argument");
        }
        writeInto(spec, args[1], offsetOf(args[2]), args, 3, args.length - 3);
        return PyNone.INSTANCE;
    }

    private static PyObject unpack(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_struct.unpack() takes no keyword arguments");
        }
        if (args.length != 2) {
            throw Slots.typeError("unpack expected 2 arguments, got %d", args.length);
        }
        Spec spec = parse(args[0]);
        byte[] data = PyMemoryView.bufferOctets(args[1]);
        if (data.length != spec.size) {
            throw fail("unpack requires a buffer of %d bytes", spec.size);
        }
        return PyTuple.of(readValues(spec, data, 0));
    }

    private static PyObject unpackFrom(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional == 0) {
            throw Slots.typeError("unpack_from() takes at least 1 positional argument (%d given)",
                    positional);
        }
        if (positional < 2) {
            throw Slots.typeError("unpack_from() missing required argument 'buffer' (pos 2)");
        }
        if (args.length > 3) {
            throw Slots.typeError("unpack_from() takes at most 3 arguments (%d given)",
                    args.length);
        }
        PyObject offset = PyInt.of(0);
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!kwNames[i].equals("offset")) {
                    throw Slots.typeError(
                            "unpack_from() got an unexpected keyword argument '%s'", kwNames[i]);
                }
                offset = args[positional + i];
            }
        } else if (args.length > 2) {
            offset = args[2];
        }
        Spec spec = parse(args[0]);
        byte[] data = PyMemoryView.bufferOctets(args[1]);
        int at = normalize(offsetOf(offset), data.length, spec.size, false, true);
        return PyTuple.of(readValues(spec, data, at));
    }

    private static PyObject iterUnpack(PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_struct.iter_unpack() takes no keyword arguments");
        }
        if (args.length != 2) {
            throw Slots.typeError("iter_unpack expected 2 arguments, got %d", args.length);
        }
        Spec spec = parse(args[0]);
        byte[] data = PyMemoryView.bufferOctets(args[1]);
        return iterator(spec, data);
    }

    private static PyObject iterator(Spec spec, byte[] data) {
        if (spec.size == 0) {
            throw fail("cannot iteratively unpack with a struct of length 0");
        }
        if (data.length % spec.size != 0) {
            throw fail("iterative unpacking requires a buffer of a multiple of %d bytes",
                    spec.size);
        }
        return new UnpackIterator(spec, data);
    }

    /** The {@code offset} of a {@code pack_into} or {@code unpack_from}, as a long. */
    private static long offsetOf(PyObject value) {
        return Numbers.asIndex(value).longValue();
    }

    /**
     * The offset a read or write starts at, checked against the buffer it lands in.
     *
     * <p>A negative offset counts from the end, and the two refusals keep the offset as the caller
     * wrote it: CPython reports {@code -5} where it would report {@code 3}.
     */
    private static int normalize(long offset, int length, long needed, boolean writing,
            boolean unpacking) {
        long at = offset < 0 ? length + offset : offset;
        if (at < 0) {
            throw fail("offset %d out of range for %d-byte buffer", offset, length);
        }
        if (at + needed > length) {
            if (offset < 0) {
                throw fail(writing ? "no space to pack %d bytes at offset %d"
                        : "not enough data to unpack %d bytes at offset %d", needed, offset);
            }
            throw fail(unpacking
                    ? "unpack_from requires a buffer of at least %d bytes for unpacking %d bytes "
                            + "at offset %d (actual buffer size is %d)"
                    : "pack_into requires a buffer of at least %d bytes for packing %d bytes "
                            + "at offset %d (actual buffer size is %d)",
                    at + needed, needed, offset, length);
        }
        return (int) at;
    }

    /** The writable length of a {@code pack_into} target, or the buffer-protocol refusal. */
    private static int writableLength(PyObject buffer) {
        if (buffer instanceof PyByteArray run) {
            return run.size();
        }
        if (buffer instanceof PyMemoryView view && !view.readonly()) {
            return view.byteLength();
        }
        throw Slots.typeError("argument must be read-write bytes-like object, not %s",
                buffer.getType().filedName());
    }

    private static void writeInto(Spec spec, PyObject buffer, long offset, PyObject[] values,
            int from, int count) {
        int length = writableLength(buffer);
        int at = normalize(offset, length, spec.size, true, false);
        byte[] packed = packValues(spec, values, from, count);
        if (buffer instanceof PyByteArray run) {
            System.arraycopy(packed, 0, run.raw(), at, packed.length);
        } else {
            ((PyMemoryView) buffer).writeWindow(at, packed, 0, packed.length);
        }
    }

    // --- packing ----------------------------------------------------------------------------

    private static byte[] packValues(Spec spec, PyObject[] values, int from, int count) {
        if (count != spec.items.size()) {
            throw fail("pack expected %d items for packing (got %d)", spec.items.size(), count);
        }
        byte[] out = new byte[(int) spec.size];
        for (int i = 0; i < count; i++) {
            write(spec, spec.items.get(i), out, values[from + i]);
        }
        return out;
    }

    private static void write(Spec spec, Item item, byte[] out, PyObject value) {
        int at = (int) item.offset;
        boolean little = spec.little;
        switch (item.code) {
            case 'b', 'h', 'i', 'l', 'q', 'n' -> putInteger(
                    out, at, item.unit, integer(value), true, item.code, little);
            case 'B', 'H', 'I', 'L', 'Q', 'N' -> putInteger(
                    out, at, item.unit, integer(value), false, item.code, little);
            case 'P' -> putPointer(out, at, item.unit, integer(value), little);
            case '?' -> out[at] = Slots.truth(value) ? (byte) 1 : 0;
            case 'c' -> {
                byte[] octets = bytesLike(value, 'c');
                if (octets.length != 1) {
                    throw fail("char format requires a bytes object of length 1");
                }
                out[at] = octets[0];
            }
            case 's', 'p' -> putString(out, at, item.width, value, item.code);
            case 'e' -> putHalf(out, at, floatValue(value), little);
            case 'f' -> putFloat(out, at, floatValue(value), little);
            case 'd' -> putDouble(out, at, floatValue(value), little);
            case 'F' -> {
                double[] pair = complex(value);
                putFloat(out, at, pair[0], little);
                putFloat(out, at + 4, pair[1], little);
            }
            case 'D' -> {
                double[] pair = complex(value);
                putDouble(out, at, pair[0], little);
                putDouble(out, at + 8, pair[1], little);
            }
            default -> throw fail("bad char in struct format");
        }
    }

    private static BigInteger integer(PyObject value) {
        if (!Numbers.isIndex(value)) {
            throw fail("required argument is not an integer");
        }
        return Numbers.asIndex(value);
    }

    private static void putInteger(byte[] out, int at, int width, BigInteger value,
            boolean signed, char code, boolean little) {
        BigInteger bits = BigInteger.ONE.shiftLeft(width * 8);
        BigInteger low = signed ? bits.shiftRight(1).negate() : BigInteger.ZERO;
        BigInteger high = signed ? bits.shiftRight(1).subtract(BigInteger.ONE)
                : bits.subtract(BigInteger.ONE);
        if (value.compareTo(low) < 0 || value.compareTo(high) > 0) {
            throw fail("'%c' format requires %s <= number <= %s", code, low, high);
        }
        putRaw(out, at, width, value.signum() < 0 ? value.add(bits) : value, little);
    }

    /**
     * A pointer-sized integer: it takes a negative value all the way down and an unsigned one up
     * to {@code 2**64 - 1}, and anything beyond is refused with its own sentence.
     */
    private static void putPointer(byte[] out, int at, int width, BigInteger value,
            boolean little) {
        BigInteger low = BigInteger.ONE.shiftLeft(63).negate();
        BigInteger high = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        if (value.compareTo(low) < 0 || value.compareTo(high) > 0) {
            throw fail("int too large to convert");
        }
        putRaw(out, at, width, value.signum() < 0
                ? value.add(BigInteger.ONE.shiftLeft(64)) : value, little);
    }

    private static void putRaw(byte[] out, int at, int width, BigInteger value, boolean little) {
        for (int i = 0; i < width; i++) {
            out[at + (little ? i : width - 1 - i)] = (byte) value.shiftRight(i * 8).intValue();
        }
    }

    private static void putString(byte[] out, int at, int width, PyObject value, char code) {
        byte[] octets = bytesLike(value, code);
        if (code == 's') {
            int copy = Math.min(width, octets.length);
            System.arraycopy(octets, 0, out, at, copy);
        } else if (width > 0) {
            int copy = Math.min(width - 1, Math.min(octets.length, 255));
            out[at] = (byte) copy;
            System.arraycopy(octets, 0, out, at + 1, copy);
        }
    }

    private static byte[] bytesLike(PyObject value, char code) {
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return PyBytes.octets(value);
        }
        throw fail("argument for '%c' must be a bytes object", code);
    }

    private static double floatValue(PyObject value) {
        Double number = Numbers.floatOf(value);
        if (number == null) {
            throw fail("required argument is not a float");
        }
        return number;
    }

    private static double[] complex(PyObject value) {
        if (value instanceof PyComplex number) {
            return new double[] {number.real(), number.imaginary()};
        }
        return new double[] {floatValue(value), 0};
    }

    private static void putFloat(byte[] out, int at, double value, boolean little) {
        float narrowed = (float) value;
        if (!Float.isFinite(narrowed) && Double.isFinite(value)) {
            throw Slots.overflowError("float too large to pack with f format");
        }
        putRaw(out, at, 4, BigInteger.valueOf(Float.floatToIntBits(narrowed) & 0xffffffffL), little);
    }

    private static void putDouble(byte[] out, int at, double value, boolean little) {
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) {
            out[at + (little ? i : 7 - i)] = (byte) (bits >>> (i * 8));
        }
    }

    private static void putHalf(byte[] out, int at, double value, boolean little) {
        int half = toHalf(value);
        if ((half & 0x7c00) == 0x7c00 && (half & 0x03ff) == 0 && Double.isFinite(value)) {
            throw Slots.overflowError("float too large to pack with e format");
        }
        out[at + (little ? 0 : 1)] = (byte) half;
        out[at + (little ? 1 : 0)] = (byte) (half >> 8);
    }

    /** IEEE 754 binary16, rounded to nearest with ties to even. */
    private static int toHalf(double value) {
        float single = (float) value;
        int bits = Float.floatToIntBits(single);
        int sign = (bits >>> 16) & 0x8000;
        int exponent = (bits >>> 23) & 0xff;
        int mantissa = bits & 0x7fffff;
        if (exponent == 0xff) {
            return sign | (mantissa == 0 ? 0x7c00 : 0x7e00);
        }
        int shifted = exponent - 127 + 15;
        if (shifted >= 0x1f) {
            return sign | 0x7c00;
        }
        if (shifted <= 0) {
            if (shifted < -10) {
                return sign;
            }
            mantissa |= 0x800000;
            int shift = 14 - shifted;
            int half = mantissa >>> shift;
            int rest = mantissa & ((1 << shift) - 1);
            int midway = 1 << (shift - 1);
            if (rest > midway || (rest == midway && (half & 1) == 1)) {
                half++;
            }
            return sign | half;
        }
        int half = (shifted << 10) | (mantissa >>> 13);
        int rest = mantissa & 0x1fff;
        if (rest > 0x1000 || (rest == 0x1000 && (half & 1) == 1)) {
            half++;
        }
        return sign | half;
    }

    private static double fromHalf(int half) {
        int sign = (half & 0x8000) != 0 ? -1 : 1;
        int exponent = (half >>> 10) & 0x1f;
        int mantissa = half & 0x03ff;
        if (exponent == 0) {
            return mantissa == 0 ? (sign < 0 ? -0.0 : 0.0) : sign * mantissa * Math.pow(2, -24);
        }
        if (exponent == 0x1f) {
            return mantissa == 0 ? sign * Double.POSITIVE_INFINITY : Double.NaN;
        }
        return sign * (1 + mantissa / 1024.0) * Math.pow(2, exponent - 15);
    }

    // --- unpacking --------------------------------------------------------------------------

    private static PyObject[] readValues(Spec spec, byte[] data, int base) {
        PyObject[] values = new PyObject[spec.items.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = read(spec, spec.items.get(i), data, base);
        }
        return values;
    }

    private static PyObject read(Spec spec, Item item, byte[] data, int base) {
        int at = base + (int) item.offset;
        boolean little = spec.little;
        return switch (item.code) {
            case 'b', 'h', 'i', 'l', 'q', 'n' ->
                    PyInt.of(integer(data, at, item.unit, little, true));
            case 'B', 'H', 'I', 'L', 'Q', 'N', 'P' ->
                    PyInt.of(integer(data, at, item.unit, little, false));
            case '?' -> PyBool.of(data[at] != 0);
            case 'c' -> PyBytes.of(new byte[] {data[at]});
            case 's' -> PyBytes.of(Arrays.copyOfRange(data, at, at + item.width));
            case 'p' -> {
                int length = item.width == 0 ? 0 : Math.min(data[at] & 0xff, item.width - 1);
                yield PyBytes.of(Arrays.copyOfRange(data, at + 1, at + 1 + length));
            }
            case 'e' -> PyFloat.of(fromHalf((int) bits(data, at, 2, little)));
            case 'f' -> PyFloat.of(Float.intBitsToFloat((int) bits(data, at, 4, little)));
            case 'd' -> PyFloat.of(Double.longBitsToDouble(bits(data, at, 8, little)));
            case 'F' -> PyComplex.of(
                    Float.intBitsToFloat((int) bits(data, at, 4, little)),
                    Float.intBitsToFloat((int) bits(data, at + 4, 4, little)));
            case 'D' -> PyComplex.of(
                    Double.longBitsToDouble(bits(data, at, 8, little)),
                    Double.longBitsToDouble(bits(data, at + 8, 8, little)));
            default -> throw fail("bad char in struct format");
        };
    }

    /** The bits of a field, least significant first: the byte order decides which end is low. */
    private static long bits(byte[] data, int at, int width, boolean little) {
        long value = 0;
        if (little) {
            for (int i = 0; i < width; i++) {
                value |= (data[at + i] & 0xffL) << (8 * i);
            }
        } else {
            for (int i = 0; i < width; i++) {
                value = (value << 8) | (data[at + i] & 0xffL);
            }
        }
        return value;
    }

    private static BigInteger integer(byte[] data, int at, int width, boolean little,
            boolean signed) {
        byte[] ascending = new byte[width];
        for (int i = 0; i < width; i++) {
            ascending[little ? width - 1 - i : i] = data[at + i];
        }
        BigInteger value = new BigInteger(1, ascending);
        if (signed && (ascending[width - 1] & 0x80) != 0) {
            value = value.subtract(BigInteger.ONE.shiftLeft(width * 8));
        }
        return value;
    }

    // --- the format language ----------------------------------------------------------------

    private static Spec parse(PyObject format) {
        String text = formatText(format);
        Spec cached = CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        Spec spec = analyse(new Spec(format, text), text);
        CACHE.putIfAbsent(text, spec);
        return spec;
    }

    private static String formatText(PyObject format) {
        if (format instanceof PyStr text) {
            return text.getValue();
        }
        if (format instanceof PyBytes) {
            return new String(PyBytes.octets(format), StandardCharsets.ISO_8859_1);
        }
        if (format instanceof PyByteArray) {
            // CPython caches formats in a dict keyed by the format object itself, and a bytearray
            // cannot be a key; the sentence comes from that lookup rather than from a check.
            throw Slots.typeError(
                    "cannot use 'bytearray' as a dict key (unhashable type: 'bytearray')");
        }
        throw Slots.typeError("Struct() argument 1 must be a str or bytes object, not %s",
                format.getType().filedName());
    }

    private static Spec analyse(Spec spec, String format) {
        int length = format.length();
        int at = 0;
        char order = '@';
        if (length > 0 && "=<>!@".indexOf(format.charAt(0)) >= 0) {
            order = format.charAt(0);
            at = 1;
        }
        boolean nativeMode = order == '@';
        spec.little = switch (order) {
            case '<' -> true;
            case '>', '!' -> false;
            default -> NATIVE_LITTLE;
        };
        long size = 0;
        while (at < length) {
            char code = format.charAt(at);
            if (isSpace(code)) {
                at++;
                continue;
            }
            long count = 1;
            if (code >= '0' && code <= '9') {
                count = 0;
                while (at < length && format.charAt(at) >= '0' && format.charAt(at) <= '9') {
                    count = count * 10 + (format.charAt(at) - '0');
                    if (count > 1_000_000_000_000L) {
                        throw fail("total struct size too long");
                    }
                    at++;
                }
                if (at >= length) {
                    throw fail("repeat count given without format specifier");
                }
                code = format.charAt(at);
            }
            int unit = unitSize(code, nativeMode);
            if (unit == 0 || "=<>!@".indexOf(code) >= 0) {
                throw fail("bad char in struct format");
            }
            if (!nativeMode && (code == 'P' || code == 'N')) {
                throw fail("bad char in struct format");
            }
            if (nativeMode) {
                size += padding(size, alignment(code));
            }
            if (code == 'x') {
                size = add(size, count);
                at++;
                continue;
            }
            boolean special = code == 's' || code == 'p';
            long total = special ? count : count * unit;
            if (special) {
                spec.items.add(new Item(code, size, 1, (int) count));
            } else {
                for (long i = 0; i < count; i++) {
                    spec.items.add(new Item(code, size + i * unit, unit, unit));
                }
            }
            size = add(size, total);
            at++;
        }
        spec.size = size;
        return spec;
    }

    private static long add(long size, long amount) {
        try {
            return Math.addExact(size, amount);
        } catch (ArithmeticException overflow) {
            throw fail("total struct size too long");
        }
    }

    private static long padding(long size, int alignment) {
        int remainder = (int) (size % alignment);
        return remainder == 0 ? 0 : alignment - remainder;
    }

    private static boolean isSpace(char code) {
        return code == ' ' || code == '\t' || code == '\n' || code == '\r' || code == '\f'
                || code == 0x0b;
    }

    /** The per-element width of a code, or 0 when the code is not one. */
    private static int unitSize(char code, boolean nativeMode) {
        return switch (code) {
            case 'x', 'c', 'b', 'B', '?', 's', 'p' -> 1;
            case 'h', 'H', 'e' -> 2;
            case 'i', 'I', 'f' -> 4;
            case 'l', 'L' -> nativeMode ? 8 : 4;
            case 'q', 'Q', 'n', 'N', 'P', 'd', 'F' -> 8;
            case 'D' -> 16;
            default -> 0;
        };
    }

    private static int alignment(char code) {
        return switch (code) {
            case 'h', 'H', 'e' -> 2;
            case 'i', 'I', 'f', 'F' -> 4;
            case 'l', 'L', 'q', 'Q', 'd', 'n', 'N', 'P', 'D' -> 8;
            default -> 1;
        };
    }

    private static PyRaise fail(String format, Object... args) {
        return PyRaise.raise(ExceptionTypes.STRUCT_ERROR, String.format(format, args));
    }

    // --- Struct -----------------------------------------------------------------------------

    /** A compiled format: the items, the total size, and the byte order they are written in. */
    static final class Spec {
        final PyObject format;
        final String text;
        final List<Item> items = new ArrayList<>();
        boolean little;
        long size;

        Spec(PyObject format, String text) {
            this.format = format;
            this.text = text;
        }
    }

    /** One value the format asks for: its code, where it goes, and how wide one element is. */
    private static final class Item {
        final char code;
        final long offset;
        final int unit;
        final int width;

        Item(char code, long offset, int unit, int width) {
            this.code = code;
            this.offset = offset;
            this.unit = unit;
            this.width = width;
        }
    }

    static PyObject compile(PyObject[] args, String[] kwNames) {
        if (args.length > 1) {
            throw Slots.typeError("Struct() takes at most 1 argument (%d given)", args.length);
        }
        int positional = Arguments.positional(args, kwNames);
        PyObject format = null;
        if (positional > 0) {
            format = args[0];
        } else if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (kwNames[i].equals("format")) {
                    format = args[positional + i];
                }
            }
        }
        if (format == null) {
            throw Slots.typeError("Struct() missing required argument 'format' (pos 1)");
        }
        return new Struct(parse(format));
    }

    static final class Struct extends PyObject {

        final Spec spec;

        Struct(Spec spec) {
            super(PyType.STRUCT);
            this.spec = spec;
        }

        @Override
        public PyObject getAttr(String name) {
            PyObject field = Members.filed(this, name);
            if (field != Slots.DECLINED) {
                return field;
            }
            PyObject method = Members.find(this, name);
            return method == Slots.DECLINED ? super.getAttr(name) : method;
        }

        @Override
        public String repr() {
            return "Struct(" + spec.format.repr() + ")";
        }
    }

    private static PyObject structPack(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("Struct.pack() takes no keyword arguments");
        }
        return PyBytes.of(packValues(((Struct) self).spec, args, 0, args.length));
    }

    private static PyObject structPackInto(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("_struct.pack_into() takes no keyword arguments");
        }
        if (args.length < 1) {
            throw fail("pack_into expected buffer argument");
        }
        if (args.length < 2) {
            throw fail("pack_into expected offset argument");
        }
        writeInto(((Struct) self).spec, args[0], offsetOf(args[1]), args, 2, args.length - 2);
        return PyNone.INSTANCE;
    }

    private static PyObject structUnpack(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("Struct.unpack() takes no keyword arguments");
        }
        if (args.length != 1) {
            throw Slots.typeError("Struct.unpack() takes exactly one argument (%d given)",
                    args.length);
        }
        Spec spec = ((Struct) self).spec;
        byte[] data = PyMemoryView.bufferOctets(args[0]);
        if (data.length != spec.size) {
            throw fail("unpack requires a buffer of %d bytes", spec.size);
        }
        return PyTuple.of(readValues(spec, data, 0));
    }

    private static PyObject structUnpackFrom(PyObject self, PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional == 0) {
            throw Slots.typeError("unpack_from() missing required argument 'buffer' (pos 1)");
        }
        if (args.length > 2) {
            throw Slots.typeError("unpack_from() takes at most 2 arguments (%d given)",
                    args.length);
        }
        PyObject offset = PyInt.of(0);
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!kwNames[i].equals("offset")) {
                    throw Slots.typeError(
                            "unpack_from() got an unexpected keyword argument '%s'", kwNames[i]);
                }
                offset = args[positional + i];
            }
        } else if (args.length > 1) {
            offset = args[1];
        }
        Spec spec = ((Struct) self).spec;
        byte[] data = PyMemoryView.bufferOctets(args[0]);
        int at = normalize(offsetOf(offset), data.length, spec.size, false, true);
        return PyTuple.of(readValues(spec, data, at));
    }

    private static PyObject structIterUnpack(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("Struct.iter_unpack() takes no keyword arguments");
        }
        if (args.length != 1) {
            throw Slots.typeError("Struct.iter_unpack() takes exactly one argument (%d given)",
                    args.length);
        }
        return iterator(((Struct) self).spec, PyMemoryView.bufferOctets(args[0]));
    }

    /** The cursor {@code iter_unpack} hands back. */
    static final class UnpackIterator extends PyIterator {

        private final Spec spec;
        private final byte[] data;
        private int at;

        UnpackIterator(Spec spec, byte[] data) {
            super(PyType.UNPACK_ITERATOR);
            this.spec = spec;
            this.data = data;
        }

        @Override
        public PyObject nextOrNull() {
            if (at >= data.length) {
                return null;
            }
            PyObject value = PyTuple.of(readValues(spec, data, at));
            at += (int) spec.size;
            return value;
        }

        @Override
        public String repr() {
            return "<_struct.unpack_iterator object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }
    }

    static {
        Members struct = Members.of(PyType.STRUCT);
        struct.addField("format", "format string", self -> ((Struct) self).spec.format);
        struct.addField("size", "size of the struct", self ->
                PyInt.of(BigInteger.valueOf(((Struct) self).spec.size)));
        struct.add("pack", "pack(v1, v2, ...) -> bytes", StructModule::structPack);
        struct.add("pack_into", "pack_into(buffer, offset, v1, v2, ...)",
                StructModule::structPackInto);
        struct.add("unpack", "unpack(buffer) -> (v1, v2, ...)", StructModule::structUnpack);
        struct.add("unpack_from", "unpack_from(buffer, offset=0) -> (v1, v2, ...)",
                StructModule::structUnpackFrom);
        struct.add("iter_unpack", "iter_unpack(buffer) -> iterator",
                StructModule::structIterUnpack);
    }
}
