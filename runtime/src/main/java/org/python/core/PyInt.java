package org.python.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

/** Python's {@code int}: arbitrary precision, with the small-value cache CPython relies on. */
public final class PyInt extends PyObject {

    /** CPython caches -5..256; identity of those values is observable through {@code is}. */
    private static final int CACHE_MIN = -5;
    private static final int CACHE_MAX = 256;
    private static final PyInt[] CACHE = new PyInt[CACHE_MAX - CACHE_MIN + 1];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new PyInt(BigInteger.valueOf(i + CACHE_MIN));
        }
    }

    /** The two values the rest of the tower keeps needing — both from the cache, so they are shared. */
    public static final PyInt ZERO = of(0);
    public static final PyInt ONE = of(1);

    private final BigInteger value;

    private PyInt(BigInteger value) {
        super(PyType.INT);
        this.value = value;
    }

    public static PyInt of(long v) {
        if (v >= CACHE_MIN && v <= CACHE_MAX) {
            return CACHE[(int) (v - CACHE_MIN)];
        }
        return new PyInt(BigInteger.valueOf(v));
    }

    public static PyInt of(BigInteger v) {
        if (v.bitLength() < 32) {
            return of(v.longValue());
        }
        return new PyInt(v);
    }

    public BigInteger getValue() {
        return value;
    }

    /** True when the value survives a round trip through {@code long} exactly. */
    public boolean fitsLong() {
        return value.bitLength() < 64;
    }

    // --- numeric slots ---------------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return Numeric.apply(op, this, other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        // The tower is closed: any number on either side is computed here, so nothing reflects back.
        return Numeric.apply(op, other, this);
    }

    @Override
    public PyObject unaryOp(UnaryOp op) {
        return Numeric.unary(op, this);
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return Numeric.compare(cmp, this, other);
    }

    /** Zero is the only false integer, and it is false on its value, not on a length it does not have. */
    @Override
    public PyObject truth() {
        return PyBool.of(value.signum() != 0);
    }

    /**
     * The attributes an {@code int} answers.
     *
     * <p>Nothing here but the members' table: {@code real} and the other three properties are
     * {@code getset_descriptor}s filed in {@code int}'s own dictionary, which the ordinary read reaches
     * through {@link TypeDict}. An {@code int} has no dictionary of its own to write into, so no name here
     * answers a store.
     */
    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    /**
     * {@code long_long}: the value itself when it already is an {@code int}, the number a bool stands for.
     *
     * <p>Identity is the whole of it, and it is observable: {@code (5).__int__() is 5} and {@code
     * BIG.__floor__() is BIG} both say true because the C function returns a new reference to the object it
     * was handed. That is also why the answer for a {@code bool} is the {@code int} beside it rather than
     * the bool itself — {@code type(+True)} is {@code int} even though {@code +True == True}.
     */
    static PyObject longLong(PyObject self) {
        return self instanceof PyInt exact ? exact : PyInt.of(Numbers.asInteger(self));
    }

    /** The sentences {@code long_methods} writes beside the twelve functions it lists. */
    private static final String CONJUGATE_DOC = "Returns self, the complex conjugate of any int.";

    private static final String BIT_LENGTH_DOC =
            "Number of bits necessary to represent self in binary.\n\n>>> bin(37)\n'0b100101'\n>>>"
                    + " (37).bit_length()\n6";

    private static final String BIT_COUNT_DOC =
            "Number of ones in the binary representation of the absolute value of self.\n\nAlso known as"
                    + " the population count.\n\n>>> bin(13)\n'0b1101'\n>>> (13).bit_count()\n3";

    private static final String TO_BYTES_DOC =
            "Return an array of bytes representing an integer.\n\n  length\n    Length of bytes object to"
                    + " use.  An OverflowError is raised if\n    the integer is not representable with the"
                    + " given number of bytes.\n    Default is length 1.\n  byteorder\n    The byte order used"
                    + " to represent the integer.  If byteorder is\n    'big', the most significant byte is at"
                    + " the beginning of the byte\n    array.  If byteorder is 'little', the most significant"
                    + " byte is at\n    the end of the byte array.  To request the native byte order of\n"
                    + "    the host system, use sys.byteorder as the byte order value.\n    Default is to use"
                    + " 'big'.\n  signed\n    Determines whether two's complement is used to represent the\n"
                    + "    integer.  If signed is False and a negative integer is given,\n    an OverflowError"
                    + " is raised.";

    private static final String FROM_BYTES_DOC =
            "Return the integer represented by the given array of bytes.\n\n  bytes\n    Holds the array of"
                    + " bytes to convert.  The argument must either\n    support the buffer protocol or be an"
                    + " iterable object producing\n    bytes.  Bytes and bytearray are examples of built-in"
                    + " objects that\n    support the buffer protocol.\n  byteorder\n    The byte order used to"
                    + " represent the integer.  If byteorder is\n    'big', the most significant byte is at the"
                    + " beginning of the byte\n    array.  If byteorder is 'little', the most significant byte"
                    + " is at\n    the end of the byte array.  To request the native byte order of\n    the host"
                    + " system, use sys.byteorder as the byte order value.\n    Default is to use 'big'.\n "
                    + " signed\n    Indicates whether two's complement is used to represent the\n    integer.";

    private static final String RATIO_DOC =
            "Return a pair of integers, whose ratio is equal to the original int.\n\nThe ratio is in lowest"
                    + " terms and has a positive denominator.\n\n>>> (10).as_integer_ratio()\n(10, 1)\n>>>"
                    + " (-10).as_integer_ratio()\n(-10, 1)\n>>> (0).as_integer_ratio()\n(0, 1)";

    private static final String TRUNC_DOC = "Truncating an Integral returns itself.";
    private static final String FLOOR_DOC = "Flooring an Integral returns itself.";
    private static final String CEIL_DOC = "Ceiling of an Integral returns itself.";

    private static final String ROUND_DOC =
            "Rounding an Integral returns itself.\n\nRounding with an ndigits argument also returns an"
                    + " integer.";

    private static final String FORMAT_SPEC_DOC = "Convert to a string according to format_spec.";
    private static final String VALUE_SIZEOF_DOC = "Returns size in memory, in bytes.";

    private static final String IS_INTEGER_DOC =
            "Returns True. Exists for duck type compatibility with float.is_integer.";

    /**
     * {@code long_methods}, in the order the reference interpreter lists it and therefore in the order
     * {@code int.__dict__} shows it.
     *
     * <p>Five of these answer with the receiver itself — {@code conjugate} and the three ways of asking an
     * integer what it rounds to, and {@code __int__} beside them, all {@code long_long} in C's spelling —
     * and {@code as_integer_ratio} hands that same object out as the numerator of a ratio over one.
     * {@code __getnewargs__} is the one that copies, so the tuple it returns holds a fresh value once the
     * number is too big for the small-int cache, which is what pickle's round trip through {@code
     * int(*args)} is written for.
     */
    private static final Members METHODS = Members.of(PyType.INT)
            .addNoArgument("conjugate", CONJUGATE_DOC, PyInt::longLong)
            .addNoArgument(
                    "bit_length", BIT_LENGTH_DOC, self -> PyInt.of(((PyInt) self).value.abs().bitLength()))
            .addNoArgument(
                    "bit_count", BIT_COUNT_DOC, self -> PyInt.of(((PyInt) self).value.abs().bitCount()))
            .add("to_bytes", TO_BYTES_DOC, PyInt::toBytes)
            .addClassNamed("from_bytes", FROM_BYTES_DOC, PyInt::fromBytes)
            .addNoArgument("as_integer_ratio", RATIO_DOC, self -> PyTuple.of(longLong(self), ONE))
            .addNoArgument("__trunc__", TRUNC_DOC, PyInt::longLong)
            .addNoArgument("__floor__", FLOOR_DOC, PyInt::longLong)
            .addNoArgument("__ceil__", CEIL_DOC, PyInt::longLong)
            .add("__round__", ROUND_DOC, PyInt::roundTo)
            .addNoArgument("__getnewargs__", null, self -> PyTuple.of(PyInt.of(((PyInt) self).value)))
            .addOne("__format__", FORMAT_SPEC_DOC, PyInt::formatted)
            .addNoArgument("__sizeof__", VALUE_SIZEOF_DOC, PyInt::sizeInBytes)
            .addNoArgument("is_integer", IS_INTEGER_DOC, self -> PyBool.TRUE);

    // --- rounding, presenting and measuring -------------------------------------------------

    /** {@code int.__round__}: self for no place asked or a place at or after the point, else a ten-power. */
    private static PyObject roundTo(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("int.__round__()", kwNames);
        Arguments.between("__round__", args, kwNames, 0, 1);
        BigInteger value = ((PyInt) self).value;
        // `int___round___impl` reads a `None` the way an omitted argument is read, before `_PyNumber_Index`.
        if (args.length == 0 || args[0] == PyNone.INSTANCE) {
            return PyInt.longLong(self);
        }
        BigInteger digits = Numbers.asInteger(args[0]);
        if (digits == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", args[0].getType().name());
        }
        if (digits.signum() >= 0) {
            // Rounding to a place after the point asks for the whole number, however far that place is.
            return PyInt.longLong(self);
        }
        BigInteger places = digits.negate();
        if (!Numbers.fitsInt(places) || places.intValue() > decimalDigits(value)) {
            return PyInt.ZERO;
        }
        // self - divmod_near(self, 10**-ndigits)[1], which is the nearest multiple of that power of ten.
        return PyInt.of(Numeric.nearMultiple(value, BigInteger.TEN.pow(places.intValue())));
    }

    /** {@code int.__format__(x, spec)}: the spec arrives as text and nothing else. */
    private static PyObject formatted(PyObject self, PyObject spec) {
        if (!(spec instanceof PyStr text)) {
            throw Slots.typeError(
                    "__format__() argument must be str, not %s", Slots.argumentName(spec));
        }
        return PyStr.of(Formats.apply(self, text.getValue()));
    }

    /**
     * {@code long.__sizeof__}: the header plus the digits the value needs, with room for one even for zero.
     *
     * <p>Thirty bits to a digit on a 64-bit build, which is why {@code 2**30} is four bytes bigger than
     * {@code 2**30 - 1} while the sign adds nothing: {@code -(2**30)} is the same size as its positive.
     */
    private static PyObject sizeInBytes(PyObject self) {
        int digits = Math.max(1, (((PyInt) self).value.abs().bitLength() + 29) / 30);
        return PyInt.of(24 + 4L * digits);
    }

    /** How many decimal digits a value is written with, which is what a rounding power must clear. */
    private static int decimalDigits(BigInteger value) {
        return value.abs().toString().length();
    }

    // --- the octet run a value is, and the value an octet run is ------------------------------

    /** The names {@code int.to_bytes} answers to, in the order its parser reads them. */
    private static final String[] TO_BYTES = {"length", "byteorder", "signed"};

    /** The names {@code int.from_bytes} answers to, which begin with the run rather than the width. */
    private static final String[] FROM_BYTES = {"bytes", "byteorder", "signed"};

    /**
     * {@code int.to_bytes(length=1, byteorder='big', *, signed=False)}.
     *
     * <p>The length defaults to one rather than to whatever the value needs, which is why
     * {@code (256).to_bytes()} overflows and {@code (255).to_bytes()} does not. The four complaints
     * inside the implementation are asked in CPython's order and not in the order they read: the
     * byteorder's <em>value</em> first — {@code to_bytes(-1, "native")} is about the order — then that
     * the width is not negative, then the array of that width is allocated, and only once it is here
     * does the value get asked whether it is negative without being allowed to be, or whether it fills
     * the run. An enormous length therefore runs out of memory before anybody knows whether the value
     * fits, and {@code (-1).to_bytes(-1)} is about the width rather than about the sign.
     */
    private static PyObject toBytes(PyObject self, PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords("to_bytes", args, kwNames, TO_BYTES, 0, 2);
        BigInteger value = ((PyInt) self).value;
        long length = given[0] == null ? 1 : Numbers.size(given[0]);
        boolean little =
                littleEndian(given[1] == null ? null : text("to_bytes", "byteorder", given[1]));
        boolean signed = given[2] != null && Slots.truth(given[2]);
        if (length < 0) {
            throw Slots.valueError("length argument must be non-negative");
        }
        byte[] bytes = new byte[Numbers.total(length)];
        if (value.signum() < 0 && !signed) {
            throw Slots.overflowError("can't convert negative int to unsigned");
        }
        // The octets past the value's own run are sign octets, which is what makes -1 nine bytes of 0xff.
        Arrays.fill(bytes, (byte) (value.signum() < 0 ? 0xff : 0x00));
        byte[] shortest = shortestRun(value, signed);
        if (shortest.length > bytes.length) {
            throw Slots.overflowError("int too big to convert");
        }
        System.arraycopy(shortest, 0, bytes, bytes.length - shortest.length, shortest.length);
        return PyBytes.wrap(little ? backwards(bytes) : bytes);
    }

    /**
     * {@code int.from_bytes(bytes, byteorder='big', *, signed=False)}, built through the type asked.
     *
     * <p>The byteorder is chosen before the run is read, so {@code from_bytes("x", "native")} complains
     * about the order rather than about the {@code str}, and a run nobody can read is only reached once
     * the order is settled. What comes back is an {@code int} when the type was, and a value of the type
     * it was reached through otherwise — {@code bool.from_bytes(b'\xff')} is {@code True}, because
     * CPython hands {@code METH_CLASS} methods the type and calls it with the number it worked out.
     */
    private static PyObject fromBytes(PyObject type, PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords("from_bytes", args, kwNames, FROM_BYTES, 1, 2);
        boolean little =
                littleEndian(given[1] == null ? null : text("from_bytes", "byteorder", given[1]));
        boolean signed = given[2] != null && Slots.truth(given[2]);
        byte[] run = octets(given[0]);
        if (little) {
            run = backwards(run);
        }
        // An empty run is zero before either sign question is asked, which is why b'' answers 0 signed.
        BigInteger value = run.length == 0
                ? BigInteger.ZERO
                : signed ? new BigInteger(run) : new BigInteger(1, run);
        if (type == PyType.INT) {
            return PyInt.of(value);
        }
        return Slots.call(type, new PyObject[] {PyInt.of(value)}, null);
    }

    /**
     * The same run read from the other end, which is what {@code 'little'} asks of it.
     *
     * <p>A copy rather than a reversal in place, because {@link #octets} hands back the array a
     * {@code bytes} value keeps as its own.
     */
    private static byte[] backwards(byte[] run) {
        byte[] other = new byte[run.length];
        for (int i = 0; i < run.length; i++) {
            other[i] = run[run.length - 1 - i];
        }
        return other;
    }

    /**
     * {@code _PyLong_AsByteArray}'s answer for a value that fits, which is Java's own minimal
     * two's-complement run less the sign octet an unsigned value has no use for.
     *
     * <p>Java already guarantees the top octet carries the right sign bit — 128 needs {@code 00 80} and
     * -128 needs only {@code 80} — so a run this short never fails the sign test CPython makes when its
     * array is filled exactly. Zero is the one value whose {@code toByteArray} run is not empty.
     */
    private static byte[] shortestRun(BigInteger value, boolean signed) {
        if (value.signum() == 0) {
            return new byte[0];
        }
        byte[] run = value.toByteArray();
        return signed || run[0] != 0 ? run : Arrays.copyOfRange(run, 1, run.length);
    }

    /** {@code _PyUnicode_Equal} against the two spellings both methods know; no byteorder is {@code 'big'}. */
    private static boolean littleEndian(String byteorder) {
        if (byteorder == null || byteorder.equals("big")) {
            return false;
        }
        if (byteorder.equals("little")) {
            return true;
        }
        throw Slots.valueError("byteorder must be either 'little' or 'big'");
    }

    /**
     * The clinic's {@code unicode} converter, which asks for text but never reads past the request.
     *
     * <p>The name it writes is {@code %T}'s: {@code None} is called {@code None} rather than by the name
     * of its type, which is what {@code to_bytes(1, None)} says.
     */
    private static String text(String method, String parameter, PyObject value) {
        if (value instanceof PyStr asked) {
            return asked.getValue();
        }
        throw Slots.typeError(
                "%s() argument '%s' must be str, not %s",
                method, parameter, Slots.argumentName(value));
    }

    /**
     * {@code PyObject_Bytes}: the octets a run of bytes answers {@code from_bytes} with.
     *
     * <p>Only what the value already is comes out of it whole. Everything else is asked for
     * {@code __bytes__} first and otherwise read one item at a time, converting as it goes, so a generator
     * is left half-run when an item is refused — the one difference from the way {@code bytes(x)} drains
     * its argument before writing a single octet. A {@code str} is refused without being iterated, because
     * CPython tests for text on the way to the iterator and then names the value's own type.
     */
    private static byte[] octets(PyObject value) {
        if (value instanceof PyBytes run) {
            return run.getValue();
        }
        PyObject answered = Specials.invoke(value, "__bytes__");
        if (answered != PyNotImplemented.INSTANCE) {
            if (!(answered instanceof PyBytes bytes)) {
                throw Slots.typeError(
                        "__bytes__ returned non-bytes (type %s)", answered.getType().name());
            }
            return bytes.getValue();
        }
        if (value instanceof PyStr || !Slots.isIterable(value)) {
            throw Slots.typeError("cannot convert '%s' object to bytes", value.getType().name());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PyIterator iterator = Slots.iterator(value);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            BigInteger octet = Numbers.asInteger(item);
            if (octet == null) {
                throw Slots.typeError(
                        "'%s' object cannot be interpreted as an integer", item.getType().name());
            }
            if (octet.signum() < 0 || octet.compareTo(OCTET_MAX) >= 0) {
                throw Slots.valueError("bytes must be in range(0, 256)");
            }
            out.write(octet.byteValue());
        }
        return out.toByteArray();
    }

    /** {@code 256}, the first value {@code bytes must be in range(0, 256)} refuses. */
    private static final BigInteger OCTET_MAX = BigInteger.valueOf(256);

    @Override
    public long hash() {
        return PyHash.intHash(value);
    }

    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyInt i) {
            return value.equals(i.value);
        }
        if (other instanceof PyBool b) {
            return b.eq(this);
        }
        if (other instanceof PyFloat f) {
            double d = f.getValue();
            return !Double.isNaN(d) && !Double.isInfinite(d)
                    && new java.math.BigDecimal(value).compareTo(new java.math.BigDecimal(d)) == 0;
        }
        if (other instanceof PyComplex c) {
            return c.imaginaryIsZero() && eq(c.realPart());
        }
        return false;
    }

    @Override
    public String repr() {
        return value.toString();
    }
}
