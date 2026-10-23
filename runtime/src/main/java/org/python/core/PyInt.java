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
     * {@code real}, {@code imag}, {@code numerator} and {@code denominator} are the four properties
     * that make an integer look like the rest of the tower to a generic numeric function; they are
     * attributes rather than methods, so they cannot live in {@link #METHODS}.
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "real", "numerator" -> this;
            case "imag" -> ZERO;
            case "denominator" -> ONE;
            default -> Members.find(this, name);
        };
    }

    private static final Members METHODS = Members.of(PyType.INT)
            .addNoArgument("bit_length", self -> PyInt.of(
                    ((PyInt) self).value.abs().bitLength()))
            .addNoArgument("conjugate", self -> self)
            .addNoArgument("as_integer_ratio", self -> PyTuple.of((PyInt) self, ONE))
            .add("to_bytes", PyInt::toBytes)
            .addClassNamed("from_bytes", PyInt::fromBytes);

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
