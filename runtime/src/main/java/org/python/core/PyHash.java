package org.python.core;

import java.math.BigInteger;

/**
 * Hashing, split by what has to match CPython and what cannot.
 *
 * Numeric hashes must match: Python guarantees {@code hash(1) == hash(1.0) == hash(True)}, so a
 * {@code {1: 'a'}} lookup has to find the key written as {@code True}. Text and byte hashes cannot
 * match — CPython seeds SipHash with a per-process random value — so they are deterministic here
 * instead, which makes set iteration order reproducible in tests.
 */
public final class PyHash {

    /** 2**61 - 1, the numeric hash modulus. */
    public static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(61).subtract(BigInteger.ONE);

    public static final long INF = 314159;
    public static final long IMAG = 1000003;

    // xxhash64 constants, as used by CPython's tuple hash. Hex, because two of them exceed
    // Long.MAX_VALUE and Java has no unsigned decimal literal.
    private static final long XXPRIME_1 = 0x9E3779B185EBCA87L;
    private static final long XXPRIME_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long XXPRIME_5 = 0x27D4EB2F165667C5L;

    private PyHash() {
    }

    /** -1 signals "error" in CPython's C API, so it is never a legitimate hash. */
    public static long avoidMinusOne(long h) {
        return h == -1 ? -2 : h;
    }

    /**
     * {@code hash(int)}. CPython short-circuits values that fit one internal digit, but the
     * general path is the same function: reduce the magnitude mod 2**61-1, then restore the sign.
     */
    public static long intHash(BigInteger value) {
        long x = value.abs().mod(MODULUS).longValue();
        if (value.signum() < 0) {
            x = -x;
        }
        return avoidMinusOne(x);
    }

    /**
     * {@code hash(float)}, the CPython {@code _Py_HashDouble} loop: pull 28 bits of the mantissa
     * at a time and rotate the exponent into the same modulus. Bit-for-bit, so that
     * {@code hash(10.0**20)} equals {@code hash(10**20)}.
     */
    public static long floatHash(double v) {
        if (Double.isNaN(v)) {
            // CPython hashes NaN by object address, which is not reproducible by design.
            return 0;
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? INF : -INF;
        }
        double m = Math.abs(v);
        if (m == 0.0) {
            return 0;
        }
        int e = Math.getExponent(m) + 1;
        m = Math.scalb(m, -e);

        long x = 0;
        while (m != 0.0) {
            x = ((x << 28) & MODULUS.longValue()) | (x >> (61 - 28));
            m *= 268435456.0;
            e -= 28;
            long y = (long) m;
            m -= y;
            x += y;
            if (x >= MODULUS.longValue()) {
                x -= MODULUS.longValue();
            }
        }
        e = e >= 0 ? e % 61 : 61 - 1 - ((-1 - e) % 61);
        x = ((x << e) & MODULUS.longValue()) | (x >> (61 - e));
        if (v < 0) {
            x = -x;
        }
        return avoidMinusOne(x);
    }

    /** {@code hash(complex)}: the two part hashes combined, not mixed. */
    public static long complexHash(double real, double imag) {
        return avoidMinusOne(floatHash(real) + IMAG * floatHash(imag));
    }

    /** {@code hash(tuple)}, CPython's single-lane xxhash64 over the element hashes. */
    public static long tupleHash(long[] lanes) {
        long acc = XXPRIME_5;
        for (long lane : lanes) {
            acc += lane * XXPRIME_2;
            acc = (acc << 31) | (acc >>> 33);
            acc *= XXPRIME_1;
        }
        acc += lanes.length ^ (XXPRIME_5 ^ 3527539L);
        return acc == -1 ? 1546275796L : acc;
    }

    /** {@code hash(slice)}: the same three-step fold as a tuple, but without its length term. */
    public static long sliceHash(long start, long stop, long step) {
        long acc = XXPRIME_5;
        for (long lane : new long[] {start, stop, step}) {
            acc += lane * XXPRIME_2;
            acc = (acc << 31) | (acc >>> 33);
            acc *= XXPRIME_1;
        }
        return acc == -1 ? 1546275796L : acc;
    }

    /** Deterministic 64-bit FNV-1a over UTF-8 bytes, for str/bytes. */
    public static long textHash(String s) {
        return bytesHash(encodeUtf8(s));
    }

    public static long bytesHash(byte[] bytes) {
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h = (h ^ (b & 0xff)) * 0x100000001b3L;
        }
        return avoidMinusOne(h);
    }

    /**
     * The surrogatepass encoding CPython writes into a marshal stream: a lone surrogate becomes its
     * own three-byte sequence instead of raising.
     *
     * A high surrogate followed by a low one is folded into one four-byte sequence, which is what
     * CPython does for a real astral character and not what it does for two separately stored
     * surrogates. Java cannot tell those apart — both are the same two {@code char}s — so the fold
     * is made for the common case and the exotic one is lost. Only adjacent surrogates coming from
     * invalid data are affected; a surrogate with anything else next to it round-trips exactly.
     */
    public static byte[] encodeUtf8(String s) {
        byte[] out = new byte[s.length() * 3];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            int cp = s.charAt(i);
            if (Character.isHighSurrogate((char) cp) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                cp = Character.toCodePoint((char) cp, s.charAt(++i));
            }
            if (cp < 0x80) {
                out[n++] = (byte) cp;
            } else if (cp < 0x800) {
                out[n++] = (byte) (0xc0 | (cp >> 6));
                out[n++] = (byte) (0x80 | (cp & 0x3f));
            } else if (cp < 0x10000) {
                out[n++] = (byte) (0xe0 | (cp >> 12));
                out[n++] = (byte) (0x80 | ((cp >> 6) & 0x3f));
                out[n++] = (byte) (0x80 | (cp & 0x3f));
            } else {
                out[n++] = (byte) (0xf0 | (cp >> 18));
                out[n++] = (byte) (0x80 | ((cp >> 12) & 0x3f));
                out[n++] = (byte) (0x80 | ((cp >> 6) & 0x3f));
                out[n++] = (byte) (0x80 | (cp & 0x3f));
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
