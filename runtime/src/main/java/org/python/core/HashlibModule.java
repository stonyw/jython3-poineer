package org.python.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code _hashlib}, {@code _md5}, {@code _sha1}, {@code _sha2}, {@code _sha3} and {@code _blake2}:
 * the extension modules the reference's {@code hashlib.py} is a wrapper around.
 *
 * <p>Everything the JVM's providers can answer is answered by {@link MessageDigest}: MD5, SHA-1,
 * the SHA-2 family, the SHA-3 family and SHA-512/224, SHA-512/256. SHAKE has no variable-length
 * entry in the JVM's API (its {@code SHAKE128} is fixed at 32 bytes), so the Keccak sponge is built
 * here; BLAKE2 has no JVM provider at all, so it is built here too. The OpenSSL-only algorithms
 * {@code ripemd160}, {@code sm3} and {@code md5-sha1} are not available and are not claimed.
 */
final class HashlibModule {

    private HashlibModule() {
    }

    // --- one algorithm's state -----------------------------------------------------------------

    /** The part of a hash object that changes as data is fed to it. */
    interface Engine {
        void update(byte[] data);

        Engine copy();

        /** The digest of everything fed so far; {@code length} is only used by SHAKE. */
        byte[] digest(int length);
    }

    private static final class DigestEngine implements Engine {
        private final MessageDigest digest;

        DigestEngine(String algorithm) {
            try {
                this.digest = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(algorithm + " missing from this JVM", e);
            }
        }

        private DigestEngine(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void update(byte[] data) {
            digest.update(data);
        }

        @Override
        public Engine copy() {
            try {
                return new DigestEngine((MessageDigest) digest.clone());
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException("digest has no clone", e);
            }
        }

        @Override
        public byte[] digest(int length) {
            try {
                return ((MessageDigest) digest.clone()).digest();
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException("digest has no clone", e);
            }
        }
    }

    /** The keyed wrapper {@code _hashlib.hmac_new} builds around one digest. */
    private static final class HmacEngine implements Engine {
        private final String name;
        private final int digestSize;
        private final int blockSize;
        private final Engine inner;
        private final Engine outer;

        HmacEngine(HashObject prototype, byte[] key) {
            this.name = prototype.name;
            this.digestSize = prototype.digestSize;
            this.blockSize = prototype.blockSize;
            byte[] usable = key;
            if (usable.length > blockSize) {
                Engine shrink = newHash(name).engine;
                shrink.update(usable);
                usable = shrink.digest(digestSize);
            }
            byte[] ipad = new byte[blockSize];
            byte[] opad = new byte[blockSize];
            for (int i = 0; i < blockSize; i++) {
                byte value = i < usable.length ? usable[i] : 0;
                ipad[i] = (byte) (value ^ 0x36);
                opad[i] = (byte) (value ^ 0x5C);
            }
            inner = newHash(name).engine;
            outer = newHash(name).engine;
            inner.update(ipad);
            outer.update(opad);
        }

        private HmacEngine(String name, int digestSize, int blockSize, Engine inner, Engine outer) {
            this.name = name;
            this.digestSize = digestSize;
            this.blockSize = blockSize;
            this.inner = inner;
            this.outer = outer;
        }

        @Override
        public void update(byte[] data) {
            inner.update(data);
        }

        @Override
        public Engine copy() {
            return new HmacEngine(name, digestSize, blockSize, inner.copy(), outer.copy());
        }

        @Override
        public byte[] digest(int length) {
            Engine finishingInner = inner.copy();
            Engine finishingOuter = outer.copy();
            finishingOuter.update(finishingInner.digest(digestSize));
            return finishingOuter.digest(digestSize);
        }
    }

    /** SHAKE's sponge: absorb the message, then squeeze as many bytes as the caller asks for. */
    static final class ShakeEngine implements Engine {
        private final long[] state = new long[25];
        private final int rate;
        private int position;

        ShakeEngine(int rate) {
            this.rate = rate;
        }

        private ShakeEngine(ShakeEngine other) {
            System.arraycopy(other.state, 0, state, 0, 25);
            this.rate = other.rate;
            this.position = other.position;
        }

        @Override
        public void update(byte[] data) {
            for (byte octet : data) {
                state[position >>> 3] ^= (octet & 0xFFL) << (8 * (position & 7));
                position++;
                if (position == rate) {
                    keccak();
                    position = 0;
                }
            }
        }

        @Override
        public Engine copy() {
            return new ShakeEngine(this);
        }

        @Override
        public byte[] digest(int length) {
            if (length < 0) {
                throw Slots.valueError("length must be non-negative");
            }
            ShakeEngine squeeze = new ShakeEngine(this);
            squeeze.state[squeeze.position >>> 3] ^= 0x1FL << (8 * (squeeze.position & 7));
            squeeze.state[(squeeze.rate - 1) >>> 3] ^= 0x80L << (8 * ((squeeze.rate - 1) & 7));
            byte[] out = new byte[length];
            int at = 0;
            while (at < length) {
                keccak(squeeze.state);
                int take = Math.min(squeeze.rate, length - at);
                for (int i = 0; i < take; i++) {
                    out[at + i] = (byte) (squeeze.state[i >>> 3] >>> (8 * (i & 7)));
                }
                at += take;
            }
            return out;
        }

        private void keccak() {
            keccak(state);
        }

        private static final int[] ROTC = {
            1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
        };

        private static final int[] PILN = {
            10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
        };

        private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
        };

        private static void keccak(long[] s) {
            long[] bc = new long[5];
            for (int round = 0; round < 24; round++) {
                for (int i = 0; i < 5; i++) {
                    bc[i] = s[i] ^ s[i + 5] ^ s[i + 10] ^ s[i + 15] ^ s[i + 20];
                }
                for (int i = 0; i < 5; i++) {
                    long t = bc[(i + 4) % 5] ^ Long.rotateLeft(bc[(i + 1) % 5], 1);
                    for (int j = 0; j < 25; j += 5) {
                        s[j + i] ^= t;
                    }
                }
                long t = s[1];
                for (int i = 0; i < 24; i++) {
                    int j = PILN[i];
                    long saved = s[j];
                    s[j] = Long.rotateLeft(t, ROTC[i]);
                    t = saved;
                }
                for (int j = 0; j < 25; j += 5) {
                    for (int i = 0; i < 5; i++) {
                        bc[i] = s[j + i];
                    }
                    for (int i = 0; i < 5; i++) {
                        s[j + i] ^= (~bc[(i + 1) % 5]) & bc[(i + 2) % 5];
                    }
                }
                s[0] ^= RC[round];
            }
        }
    }

    /** BLAKE2b/BLAKE2s, RFC 7693, including the parameter block CPython exposes as keywords. */
    static final class Blake2Engine implements Engine {
        private static final long[] IV64 = {
            0x6A09E667F3BCC908L, 0xBB67AE8584CAA73BL, 0x3C6EF372FE94F82BL, 0xA54FF53A5F1D36F1L,
            0x510E527FADE682D1L, 0x9B05688C2B3E6C1FL, 0x1F83D9ABFB41BD6BL, 0x5BE0CD19137E2179L
        };

        private static final int[] IV32 = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
        };

        private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}
        };

        private final boolean wide;
        private final int outlen;
        private final int blockSize;
        private final byte[] buffer;
        private int buffered;
        private final long[] h = new long[8];
        private long counterLow;
        private long counterHigh;
        private final boolean lastNode;

        Blake2Engine(
                boolean wide,
                int outlen,
                int keyLength,
                byte[] salt,
                byte[] person,
                int fanout,
                int depth,
                long leafSize,
                long nodeOffset,
                int nodeDepth,
                int innerSize,
                boolean lastNode) {
            this.wide = wide;
            this.outlen = outlen;
            this.blockSize = wide ? 128 : 64;
            this.buffer = new byte[blockSize];
            this.lastNode = lastNode;
            int paramSize = wide ? 64 : 32;
            byte[] param = new byte[paramSize];
            param[0] = (byte) outlen;
            param[1] = (byte) keyLength;
            param[2] = (byte) fanout;
            param[3] = (byte) depth;
            putLittle(param, 4, leafSize, 4);
            if (wide) {
                putLittle(param, 8, nodeOffset, 8);
                param[16] = (byte) nodeDepth;
                param[17] = (byte) innerSize;
                System.arraycopy(salt, 0, param, 32, 16);
                System.arraycopy(person, 0, param, 48, 16);
            } else {
                putLittle(param, 8, nodeOffset, 6);
                param[14] = (byte) nodeDepth;
                param[15] = (byte) innerSize;
                System.arraycopy(salt, 0, param, 16, 8);
                System.arraycopy(person, 0, param, 24, 8);
            }
            if (wide) {
                for (int i = 0; i < 8; i++) {
                    h[i] = IV64[i] ^ readLittleLong(param, i * 8);
                }
            } else {
                for (int i = 0; i < 8; i++) {
                    h[i] = (IV32[i] ^ readLittleInt(param, i * 4)) & 0xFFFFFFFFL;
                }
            }
        }

        private Blake2Engine(Blake2Engine other) {
            this.wide = other.wide;
            this.outlen = other.outlen;
            this.blockSize = other.blockSize;
            this.buffer = other.buffer.clone();
            this.buffered = other.buffered;
            System.arraycopy(other.h, 0, h, 0, 8);
            this.counterLow = other.counterLow;
            this.counterHigh = other.counterHigh;
            this.lastNode = other.lastNode;
        }

        @Override
        public void update(byte[] data) {
            int at = 0;
            while (at < data.length) {
                if (buffered == blockSize) {
                    increment(blockSize);
                    compress(buffer, 0, false);
                    buffered = 0;
                }
                int take = Math.min(blockSize - buffered, data.length - at);
                System.arraycopy(data, at, buffer, buffered, take);
                buffered += take;
                at += take;
            }
        }

        @Override
        public Engine copy() {
            return new Blake2Engine(this);
        }

        @Override
        public byte[] digest(int length) {
            Blake2Engine state = new Blake2Engine(this);
            state.increment(state.buffered);
            java.util.Arrays.fill(state.buffer, state.buffered, state.blockSize, (byte) 0);
            state.compress(state.buffer, 0, true);
            byte[] out = new byte[state.outlen];
            for (int i = 0; i < state.outlen; i++) {
                int word = i >>> (state.wide ? 3 : 2);
                int shift = state.wide ? 8 * (i & 7) : 8 * (i & 3);
                out[i] = (byte) (state.h[word] >>> shift);
            }
            return out;
        }

        private void increment(int amount) {
            counterLow += amount;
            if (counterLow < amount) {
                counterHigh++;
            }
        }

        private void compress(byte[] block, int offset, boolean last) {
            if (wide) {
                long[] m = new long[16];
                for (int i = 0; i < 16; i++) {
                    m[i] = readLittleLong(block, offset + i * 8);
                }
                long[] v = new long[16];
                System.arraycopy(h, 0, v, 0, 8);
                System.arraycopy(IV64, 0, v, 8, 8);
                v[12] ^= counterLow;
                v[13] ^= counterHigh;
                if (last) {
                    v[14] = ~v[14];
                    if (lastNode) {
                        v[15] = ~v[15];
                    }
                }
                for (int round = 0; round < 12; round++) {
                    byte[] s = SIGMA[round % 10];
                    g64(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
                    g64(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
                    g64(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
                    g64(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
                    g64(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
                    g64(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
                    g64(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
                    g64(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
                }
                for (int i = 0; i < 8; i++) {
                    h[i] ^= v[i] ^ v[i + 8];
                }
            } else {
                long[] m = new long[16];
                for (int i = 0; i < 16; i++) {
                    m[i] = readLittleInt(block, offset + i * 4) & 0xFFFFFFFFL;
                }
                long[] v = new long[16];
                System.arraycopy(h, 0, v, 0, 8);
                for (int i = 0; i < 8; i++) {
                    v[8 + i] = IV32[i] & 0xFFFFFFFFL;
                }
                v[12] ^= counterLow & 0xFFFFFFFFL;
                v[13] ^= counterHigh & 0xFFFFFFFFL;
                if (last) {
                    v[14] = ~v[14] & 0xFFFFFFFFL;
                    if (lastNode) {
                        v[15] = ~v[15] & 0xFFFFFFFFL;
                    }
                }
                for (int round = 0; round < 10; round++) {
                    byte[] s = SIGMA[round];
                    g32(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
                    g32(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
                    g32(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
                    g32(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
                    g32(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
                    g32(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
                    g32(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
                    g32(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
                }
                for (int i = 0; i < 8; i++) {
                    h[i] = (h[i] ^ v[i] ^ v[i + 8]) & 0xFFFFFFFFL;
                }
            }
        }

        private static void g64(long[] v, int a, int b, int c, int d, long x, long y) {
            v[a] = v[a] + v[b] + x;
            v[d] = Long.rotateRight(v[d] ^ v[a], 32);
            v[c] = v[c] + v[d];
            v[b] = Long.rotateRight(v[b] ^ v[c], 24);
            v[a] = v[a] + v[b] + y;
            v[d] = Long.rotateRight(v[d] ^ v[a], 16);
            v[c] = v[c] + v[d];
            v[b] = Long.rotateRight(v[b] ^ v[c], 63);
        }

        private static void g32(long[] v, int a, int b, int c, int d, long x, long y) {
            v[a] = (v[a] + v[b] + x) & 0xFFFFFFFFL;
            v[d] = rot32(v[d] ^ v[a], 16);
            v[c] = (v[c] + v[d]) & 0xFFFFFFFFL;
            v[b] = rot32(v[b] ^ v[c], 12);
            v[a] = (v[a] + v[b] + y) & 0xFFFFFFFFL;
            v[d] = rot32(v[d] ^ v[a], 8);
            v[c] = (v[c] + v[d]) & 0xFFFFFFFFL;
            v[b] = rot32(v[b] ^ v[c], 7);
        }

        private static long rot32(long value, int bits) {
            return ((value >>> bits) | (value << (32 - bits))) & 0xFFFFFFFFL;
        }

        private static void putLittle(byte[] target, int offset, long value, int bytes) {
            for (int i = 0; i < bytes; i++) {
                target[offset + i] = (byte) (value >>> (8 * i));
            }
        }

        private static long readLittleLong(byte[] source, int offset) {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value |= (long) (source[offset + i] & 0xFF) << (8 * i);
            }
            return value;
        }

        private static int readLittleInt(byte[] source, int offset) {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                value |= (source[offset + i] & 0xFF) << (8 * i);
            }
            return value;
        }
    }

    // --- the shared hash object ----------------------------------------------------------------

    static final class HashObject extends PyObject {

        private final String name;
        private final int digestSize;
        private final int blockSize;
        private Engine engine;

        HashObject(PyType type, String name, int digestSize, int blockSize, Engine engine) {
            super(type);
            this.name = name;
            this.digestSize = digestSize;
            this.blockSize = blockSize;
            this.engine = engine;
        }

        private HashObject(HashObject other) {
            this(other.getType(), other.name, other.digestSize, other.blockSize, other.engine.copy());
        }

        private static boolean membersFiled;

        static void members() {
            if (membersFiled) {
                return;
            }
            membersFiled = true;
            Members.of(PyType.HASH)
                    .add("update", (self, args, kw) -> ((HashObject) self).update(args, kw))
                    .add("digest", (self, args, kw) -> ((HashObject) self).digest(args, kw))
                    .add("hexdigest", (self, args, kw) -> ((HashObject) self).hexdigest(args, kw))
                    .add("copy", (self, args, kw) -> ((HashObject) self).copy(args, kw));
            Members.of(PyType.HASHXOF)
                    .add("update", (self, args, kw) -> ((HashObject) self).update(args, kw))
                    .add("digest", (self, args, kw) -> ((HashObject) self).digestXof(args, kw))
                    .add("hexdigest", (self, args, kw) -> ((HashObject) self).hexdigestXof(args, kw))
                    .add("copy", (self, args, kw) -> ((HashObject) self).copy(args, kw));
            for (PyType type : new PyType[] {PyType.BLAKE2B, PyType.BLAKE2S, PyType.HMAC}) {
                Members.of(type)
                        .add("update", (self, args, kw) -> ((HashObject) self).update(args, kw))
                        .add("digest", (self, args, kw) -> ((HashObject) self).digest(args, kw))
                        .add("hexdigest", (self, args, kw) -> ((HashObject) self).hexdigest(args, kw))
                        .add("copy", (self, args, kw) -> ((HashObject) self).copy(args, kw));
            }
        }

        @Override
        public PyObject getAttr(String name) {
            switch (name) {
                case "name":
                    return PyStr.of(this.name);
                case "digest_size":
                    return PyInt.of(digestSize);
                case "block_size":
                    return PyInt.of(blockSize);
                default:
                    return Members.find(this, name);
            }
        }

        /** The type-qualified name the reference puts in its arity complaints. */
        private String qualifier() {
            if (getType() == PyType.HASH) {
                return "HASH";
            }
            if (getType() == PyType.HASHXOF) {
                return "HASHXOF";
            }
            if (getType() == PyType.HMAC) {
                return "HMAC";
            }
            return name;
        }

        private PyObject update(PyObject[] args, String[] kwNames) {
            Arguments.exactlyOne(qualifier() + ".update()", args, kwNames);
            engine.update(octets(args[0]));
            return PyNone.INSTANCE;
        }

        private PyObject digest(PyObject[] args, String[] kwNames) {
            Arguments.none(qualifier() + ".digest()", args, kwNames);
            return PyBytes.of(engine.digest(0));
        }

        private PyObject hexdigest(PyObject[] args, String[] kwNames) {
            Arguments.none(qualifier() + ".hexdigest()", args, kwNames);
            return PyStr.of(hex(engine.digest(0)));
        }

        private PyObject digestXof(PyObject[] args, String[] kwNames) {
            int length = length(args, kwNames, "digest");
            return PyBytes.of(engine.digest(length));
        }

        private PyObject hexdigestXof(PyObject[] args, String[] kwNames) {
            int length = length(args, kwNames, "hexdigest");
            return PyStr.of(hex(engine.digest(length)));
        }

        private static int length(PyObject[] args, String[] kwNames, String method) {
            String[] names = {"length"};
            PyObject[] values = Arguments.fill(
                    method, args, kwNames, names, new PyObject[] {null});
            Arguments.atMostQualified(method, args, kwNames, 1);
            Arguments.required(method, names, values, 0);
            long value = sizeArgument(values[0]);
            return (int) Math.min(value, Integer.MAX_VALUE);
        }

        private PyObject copy(PyObject[] args, String[] kwNames) {
            Arguments.none(qualifier() + ".copy()", args, kwNames);
            return new HashObject(this);
        }

        @Override
        public String repr() {
            String address = Integer.toHexString(System.identityHashCode(this));
            if (getType() == PyType.HMAC) {
                return "<" + name.substring("hmac-".length()) + " HMAC object @ 0x" + address + ">";
            }
            if (getType() == PyType.HASH || getType() == PyType.HASHXOF) {
                return "<" + name + " " + (getType() == PyType.HASH ? "_hashlib.HASH" : "_hashlib.HASHXOF")
                        + " object @ 0x" + address + ">";
            }
            return "<_blake2." + name + " object at 0x" + address + ">";
        }
    }

    /**
     * SHAKE's {@code length}, which the reference reads as a {@code Py_ssize_t} and then hands to
     * {@code PyBytes_FromStringAndSize}: a value that does not fit is an {@code OverflowError}, and a
     * negative one is the {@code SystemError} that C function raises rather than a {@code ValueError}.
     */
    private static long sizeArgument(PyObject value) {
        java.math.BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() > 63) {
            throw PyRaise.raise(
                    ExceptionTypes.OVERFLOW_ERROR, "Python int too large to convert to C ssize_t");
        }
        long length = number.longValue();
        if (length < 0) {
            throw PyRaise.raise(
                    ExceptionTypes.SYSTEM_ERROR, "Negative size passed to PyBytes_FromStringAndSize");
        }
        return length;
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte octet : data) {
            text.append(Character.forDigit((octet >>> 4) & 0xF, 16));
            text.append(Character.forDigit(octet & 0xF, 16));
        }
        return text.toString();
    }

    // --- algorithm construction ----------------------------------------------------------------

    private static final String[] NAMES = {
        "md5", "sha1", "sha224", "sha256", "sha384", "sha512", "sha512_224", "sha512_256",
        "sha3_224", "sha3_256", "sha3_384", "sha3_512", "shake_128", "shake_256", "blake2b", "blake2s"
    };

    private static PyObject construct(String name, PyObject[] args, String[] kwNames, String function) {
        // `usedforsecurity` and `string` are keyword-only: only `data` — and, for `new`, `name` — may
        // arrive positionally, so `sha256(b'x', b'y')` is an arity complaint rather than a second value.
        int limit = function.equals("new") ? 2 : 1;
        int positional = Arguments.positional(args, kwNames);
        if (positional > limit) {
            throw Slots.typeError(
                    "%s() takes at most %d positional argument%s (%d given)",
                    function, limit, limit == 1 ? "" : "s", positional);
        }
        PyObject[] values = Arguments.fill(
                function,
                args,
                kwNames,
                new String[] {"data", "usedforsecurity", "string"},
                new PyObject[] {PyNone.INSTANCE, PyBool.TRUE, PyNone.INSTANCE});
        return construct(name, values[0], values[2], function);
    }

    /**
     * {@code name(data=b'', *, usedforsecurity=True, string=<deprecated>)}.
     *
     * <p>The reference still accepts {@code string} and refuses it only when {@code data} was given too;
     * the two are otherwise the same argument under two names.
     */
    private static PyObject construct(
            String name, PyObject data, PyObject string, String function) {
        if (string != null && string != PyNone.INSTANCE) {
            if (data != null && data != PyNone.INSTANCE) {
                throw Slots.typeError(
                        "'data' and 'string' are mutually exclusive and support for 'string' keyword"
                                + " parameter is slated for removal in a future version.");
            }
            data = string;
        }
        HashObject object = newHash(name);
        if (data != null && data != PyNone.INSTANCE) {
            object.engine.update(octets(data));
        }
        return object;
    }

    /** The buffer an argument exports, in the two refusals {@code _hashlib} gives. */
    private static byte[] octets(PyObject value) {
        if (value instanceof PyStr) {
            throw Slots.typeError("Strings must be encoded before hashing");
        }
        if (!(value instanceof PyBytes || value instanceof PyByteArray || value instanceof PyMemoryView)) {
            throw Slots.typeError("object supporting the buffer API required");
        }
        return PyMemoryView.bufferOctets(value);
    }

    /**
     * The buffer an argument exports, in the refusal {@code PyObject_GetBuffer} gives.
     *
     * <p>{@code hmac_new}'s key and {@code pbkdf2_hmac}'s password and salt are plain buffers, so a
     * {@code str} there is "a bytes-like object is required" rather than the "must be encoded" sentence
     * the digest constructors use for their data.
     */
    private static byte[] bufferOctets(PyObject value) {
        if (!(value instanceof PyBytes || value instanceof PyByteArray || value instanceof PyMemoryView)) {
            throw Slots.typeError(
                    "a bytes-like object is required, not '%s'", Slots.argumentName(value));
        }
        return PyMemoryView.bufferOctets(value);
    }

    private static PyRaise unsupportedDigest() {
        return PyRaise.raise(ExceptionTypes.UNSUPPORTED_DIGESTMOD_ERROR, "[digital envelope routines] unsupported");
    }

    static HashObject newHash(String name) {
        switch (name) {
            case "md5":
                return new HashObject(PyType.HASH, "md5", 16, 64, new DigestEngine("MD5"));
            case "sha1":
                return new HashObject(PyType.HASH, "sha1", 20, 64, new DigestEngine("SHA-1"));
            case "sha224":
                return new HashObject(PyType.HASH, "sha224", 28, 64, new DigestEngine("SHA-224"));
            case "sha256":
                return new HashObject(PyType.HASH, "sha256", 32, 64, new DigestEngine("SHA-256"));
            case "sha384":
                return new HashObject(PyType.HASH, "sha384", 48, 128, new DigestEngine("SHA-384"));
            case "sha512":
                return new HashObject(PyType.HASH, "sha512", 64, 128, new DigestEngine("SHA-512"));
            case "sha512_224":
                return new HashObject(PyType.HASH, "sha512_224", 28, 128, new DigestEngine("SHA-512/224"));
            case "sha512_256":
                return new HashObject(PyType.HASH, "sha512_256", 32, 128, new DigestEngine("SHA-512/256"));
            case "sha3_224":
                return new HashObject(PyType.HASH, "sha3_224", 28, 144, new DigestEngine("SHA3-224"));
            case "sha3_256":
                return new HashObject(PyType.HASH, "sha3_256", 32, 136, new DigestEngine("SHA3-256"));
            case "sha3_384":
                return new HashObject(PyType.HASH, "sha3_384", 48, 104, new DigestEngine("SHA3-384"));
            case "sha3_512":
                return new HashObject(PyType.HASH, "sha3_512", 64, 72, new DigestEngine("SHA3-512"));
            case "shake_128":
                return new HashObject(PyType.HASHXOF, "shake_128", 0, 168, new ShakeEngine(168));
            case "shake_256":
                return new HashObject(PyType.HASHXOF, "shake_256", 0, 136, new ShakeEngine(136));
            case "blake2b":
                return new HashObject(PyType.BLAKE2B, "blake2b", 64, 128,
                        new Blake2Engine(true, 64, 0, new byte[16], new byte[16], 1, 1, 0, 0, 0, 0, false));
            case "blake2s":
                return new HashObject(PyType.BLAKE2S, "blake2s", 32, 64,
                        new Blake2Engine(false, 32, 0, new byte[8], new byte[8], 1, 1, 0, 0, 0, 0, false));
            default:
                throw unsupportedDigest();
        }
    }

    private static PyObject blake2(String name, PyObject[] args, String[] kwNames) {
        boolean wide = name.equals("blake2b");
        int maxDigest = wide ? 64 : 32;
        int maxSalt = wide ? 16 : 8;
        int positional = Arguments.positional(args, kwNames);
        if (positional > 1) {
            throw Slots.typeError("%s() takes at most 1 positional argument (%d given)", name, positional);
        }
        byte[] data = positional == 1 ? octets(args[0]) : new byte[0];
        int digestSize = maxDigest;
        byte[] key = new byte[0];
        byte[] salt = new byte[maxSalt];
        byte[] person = new byte[maxSalt];
        int fanout = 1;
        int depth = 1;
        long leafSize = 0;
        long nodeOffset = 0;
        int nodeDepth = 0;
        int innerSize = 0;
        boolean lastNode = false;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject value = args[positional + i];
                switch (kwNames[i]) {
                    case "data":
                        data = octets(value);
                        break;
                    case "digest_size":
                        digestSize = (int) Numbers.clipped(value);
                        break;
                    case "key":
                        key = bufferOctets(value);
                        break;
                    case "salt":
                        salt = padded(value, maxSalt, "salt");
                        break;
                    case "person":
                        person = padded(value, maxSalt, "person");
                        break;
                    case "fanout":
                        fanout = (int) Numbers.clipped(value);
                        break;
                    case "depth":
                        depth = (int) Numbers.clipped(value);
                        break;
                    case "leaf_size":
                        leafSize = Numbers.clipped(value);
                        break;
                    case "node_offset":
                        nodeOffset = Numbers.clipped(value);
                        break;
                    case "node_depth":
                        nodeDepth = (int) Numbers.clipped(value);
                        break;
                    case "inner_size":
                        innerSize = (int) Numbers.clipped(value);
                        break;
                    case "last_node":
                        lastNode = Slots.truth(value);
                        break;
                    case "usedforsecurity":
                        break;
                    default:
                        throw Slots.typeError("%s() got an unexpected keyword argument '%s'", name, kwNames[i]);
                }
            }
        }
        if (digestSize < 1 || digestSize > maxDigest) {
            throw Slots.valueError(
                    "digest_size for %s must be between 1 and %d bytes, here it is %d",
                    wide ? "Blake2b" : "Blake2s", maxDigest, digestSize);
        }
        if (key.length > maxDigest) {
            throw Slots.valueError("maximum key length is %d bytes", maxDigest);
        }
        if (leafSize < 0) {
            throw Slots.valueError("Cannot convert negative int");
        }
        if (depth < 1 || depth > 255) {
            throw Slots.valueError("depth must be between 1 and 255");
        }
        if (fanout < 0 || fanout > 255) {
            throw Slots.valueError("fanout must be between 0 and 255");
        }
        if (nodeDepth < 0 || nodeDepth > 255) {
            throw Slots.valueError("node_depth must be between 0 and 255");
        }
        if (innerSize < 0 || innerSize > maxDigest) {
            throw Slots.valueError("inner_size must be between 0 and %d", maxDigest);
        }
        Blake2Engine engine = new Blake2Engine(
                wide, digestSize, key.length, salt, person, fanout, depth, leafSize, nodeOffset,
                nodeDepth, innerSize, lastNode);
        HashObject object = new HashObject(
                wide ? PyType.BLAKE2B : PyType.BLAKE2S, name, digestSize, wide ? 128 : 64, engine);
        if (key.length != 0) {
            byte[] padded = new byte[wide ? 128 : 64];
            System.arraycopy(key, 0, padded, 0, key.length);
            engine.update(padded);
        }
        if (data.length != 0) {
            engine.update(data);
        }
        return object;
    }

    private static byte[] padded(PyObject value, int size, String what) {
        byte[] bytes = bufferOctets(value);
        if (bytes.length > size) {
            throw Slots.valueError("maximum %s length is %d bytes", what, size);
        }
        return java.util.Arrays.copyOf(bytes, size);
    }

    // --- PBKDF2 --------------------------------------------------------------------------------

    private static PyObject pbkdf2(PyObject[] args, String[] kwNames) {
        String[] names = {"hash_name", "password", "salt", "iterations", "dklen"};
        PyObject[] values = Arguments.fill(
                "pbkdf2_hmac",
                args,
                kwNames,
                names,
                new PyObject[] {PyNone.INSTANCE});
        for (int at = 0; at < 4; at++) {
            Arguments.required("pbkdf2_hmac", names, values, at);
        }
        if (!(values[0] instanceof PyStr)) {
            throw Slots.typeError(
                    "pbkdf2_hmac() argument 'hash_name' must be str, not %s",
                    Slots.argumentName(values[0]));
        }
        String hashName = ((PyStr) values[0]).getValue();
        byte[] password = bufferOctets(values[1]);
        byte[] salt = bufferOctets(values[2]);
        long iterations = Numbers.clipped(values[3]);
        HashObject prototype;
        try {
            prototype = newHash(hashName);
        } catch (PyRaise e) {
            throw unsupportedDigest();
        }
        if (iterations < 1) {
            throw Slots.valueError("iteration value must be greater than 0.");
        }
        long requested = values[4] == PyNone.INSTANCE
                ? prototype.digestSize
                : Numbers.clipped(values[4]);
        if (requested <= 0) {
            throw Slots.valueError("key length must be greater than 0.");
        }
        if (requested > 0xFFFFFFFFL) {
            throw Slots.overflowError("key length is too great.");
        }
        int dklen = (int) requested;
        return PyBytes.of(pbkdf2(hashName, password, salt, iterations, dklen));
    }

    private static byte[] pbkdf2(String name, byte[] password, byte[] salt, long iterations, int dklen) {
        HashObject template = newHash(name);
        int blockSize = template.blockSize;
        byte[] key = password;
        if (key.length > blockSize) {
            HashObject shrink = newHash(name);
            shrink.engine.update(key);
            key = shrink.engine.digest(0);
        }
        byte[] padded = java.util.Arrays.copyOf(key, blockSize);
        byte[] ipad = new byte[blockSize];
        byte[] opad = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            ipad[i] = (byte) (padded[i] ^ 0x36);
            opad[i] = (byte) (padded[i] ^ 0x5C);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int blocks = (dklen + template.digestSize - 1) / template.digestSize;
        for (int block = 1; block <= blocks; block++) {
            byte[] u = hmac(name, ipad, opad, concat(salt, new byte[] {
                (byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block }));
            byte[] accumulator = u.clone();
            for (long round = 1; round < iterations; round++) {
                u = hmac(name, ipad, opad, u);
                for (int i = 0; i < accumulator.length; i++) {
                    accumulator[i] ^= u[i];
                }
            }
            out.write(accumulator, 0, accumulator.length);
        }
        return java.util.Arrays.copyOf(out.toByteArray(), dklen);
    }

    private static byte[] hmac(String name, byte[] ipad, byte[] opad, byte[] data) {
        HashObject inner = newHash(name);
        inner.engine.update(ipad);
        inner.engine.update(data);
        byte[] first = inner.engine.digest(0);
        HashObject outer = newHash(name);
        outer.engine.update(opad);
        outer.engine.update(first);
        return outer.engine.digest(0);
    }

    private static PyObject hmacNew(PyObject[] args, String[] kwNames) {
        String[] names = {"key", "msg", "digestmod"};
        PyObject[] values = Arguments.fill(
                "hmac_new",
                args,
                kwNames,
                names,
                new PyObject[] {PyNone.INSTANCE, null});
        Arguments.required("hmac_new", names, values, 0);
        Arguments.required("hmac_new", names, values, 2);
        HashObject prototype;
        if (values[2] instanceof PyStr named) {
            prototype = newHash(named.getValue());
        } else {
            PyObject made = Slots.call(values[2], new PyObject[0], null);
            if (!(made instanceof HashObject hash)) {
                throw Slots.typeError("invalid digestmod");
            }
            prototype = hash;
        }
        HashObject object = new HashObject(
                PyType.HMAC,
                "hmac-" + prototype.name,
                prototype.digestSize,
                prototype.blockSize,
                new HmacEngine(prototype, octets(values[0])));
        if (values[1] != PyNone.INSTANCE) {
            object.engine.update(octets(values[1]));
        }
        return object;
    }

    private static PyObject hmacDigest(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.fill(
                "hmac_digest",
                args,
                kwNames,
                new String[] {"key", "msg", "digest"},
                new PyObject[] {PyNone.INSTANCE, null});
        Arguments.required("hmac_digest", new String[] {"key", "msg", "digest"}, values, 0);
        Arguments.required("hmac_digest", new String[] {"key", "msg", "digest"}, values, 2);
        PyObject made = hmacNew(new PyObject[] {values[0], values[1], values[2]}, null);
        return PyBytes.of(((HashObject) made).engine.digest(0));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }

    // --- the modules ---------------------------------------------------------------------------

    static PyModule[] newModules() {
        HashObject.members();
        PyModule hashlib = new PyModule("_hashlib");
        PyRuntime.markBuiltIn(hashlib, "_hashlib");
        hashlib.set("HASH", PyType.HASH);
        hashlib.set("HASHXOF", PyType.HASHXOF);
        hashlib.set("HMAC", PyType.HMAC);
        hashlib.set("UnsupportedDigestmodError", ExceptionTypes.UNSUPPORTED_DIGESTMOD_ERROR);
        hashlib.set("new", new PyNativeFunction("new", HashlibModule::newHash));
        hashlib.set("pbkdf2_hmac", new PyNativeFunction("pbkdf2_hmac", HashlibModule::pbkdf2));
        hashlib.set("hmac_new", new PyNativeFunction("hmac_new", HashlibModule::hmacNew));
        hashlib.set("hmac_digest", new PyNativeFunction("hmac_digest", HashlibModule::hmacDigest));
        hashlib.set("compare_digest", new PyNativeFunction("compare_digest", HashlibModule::compareDigest));
        hashlib.set("get_fips_mode", new PyNativeFunction("get_fips_mode", (a, k) -> PyInt.of(0)));
        List<PyObject> names = new ArrayList<>();
        for (String name : NAMES) {
            hashlib.set("openssl_" + name, new PyNativeFunction(
                    "openssl_" + name,
                    (args, kwNames) -> construct(name, args, kwNames, "openssl_" + name)));
            names.add(PyStr.of(name));
        }
        hashlib.set("openssl_md_meth_names", PyFrozenSet.of(names.toArray(new PyObject[0])));

        PyModule md5 = alphabet("_md5", "md5");
        PyModule sha1 = alphabet("_sha1", "sha1");
        PyModule sha2 = alphabet("_sha2", "sha224", "sha256", "sha384", "sha512", "sha512_224", "sha512_256");
        PyModule sha3 = alphabet("_sha3", "sha3_224", "sha3_256", "sha3_384", "sha3_512", "shake_128", "shake_256");
        PyModule blake2 = new PyModule("_blake2");
        PyRuntime.markBuiltIn(blake2, "_blake2");
        blake2.set("blake2b", new PyNativeFunction("blake2b", (args, kw) -> blake2("blake2b", args, kw)));
        blake2.set("blake2s", new PyNativeFunction("blake2s", (args, kw) -> blake2("blake2s", args, kw)));
        return new PyModule[] {hashlib, md5, sha1, sha2, sha3, blake2};
    }

    private static PyModule alphabet(String moduleName, String... names) {
        PyModule module = new PyModule(moduleName);
        PyRuntime.markBuiltIn(module, moduleName);
        for (String name : names) {
            module.set(name, new PyNativeFunction(name, (args, kw) -> construct(name, args, kw, name)));
        }
        return module;
    }

    private static PyObject newHash(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 2) {
            throw Slots.typeError(
                    "new() takes at most 2 positional arguments (%d given)", positional);
        }
        PyObject[] values = Arguments.fill(
                "new",
                args,
                kwNames,
                new String[] {"name", "data", "usedforsecurity", "string"},
                new PyObject[] {null, PyNone.INSTANCE, PyBool.TRUE, PyNone.INSTANCE});
        Arguments.required("new", new String[] {"name", "data", "usedforsecurity", "string"}, values, 0);
        if (!(values[0] instanceof PyStr)) {
            throw Slots.typeError(
                    "new() argument 'name' must be str, not %s", Slots.argumentName(values[0]));
        }
        String name = ((PyStr) values[0]).getValue();
        return construct(name, values[1], values[3], "new");
    }

    private static PyObject compareDigest(PyObject[] args, String[] kwNames) {
        Arguments.exactly("compare_digest", args, kwNames, 2);
        byte[] a = digestBytes(args[0]);
        byte[] b = digestBytes(args[1]);
        if (a.length != b.length) {
            return PyBool.FALSE;
        }
        int difference = 0;
        for (int i = 0; i < a.length; i++) {
            difference |= a[i] ^ b[i];
        }
        return PyBool.of(difference == 0);
    }

    /** One side of {@code compare_digest}: a buffer, or a str of ASCII only. */
    private static byte[] digestBytes(PyObject value) {
        if (value instanceof PyStr text) {
            for (int i = 0; i < text.getValue().length(); i++) {
                if (text.getValue().charAt(i) > 0x7F) {
                    throw Slots.typeError("comparing strings with non-ASCII characters is not supported");
                }
            }
            return text.toUtf8();
        }
        return PyMemoryView.bufferOctets(value);
    }
}
