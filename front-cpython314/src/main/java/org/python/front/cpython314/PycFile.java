package org.python.front.cpython314;

import org.python.core.PyCode;

/**
 * A {@code .pyc} container: the 16-byte CPython 3.14 header and the marshal stream behind it.
 *
 * The magic number is checked rather than ignored. A 3.13 object would otherwise be read as a
 * slightly different — and entirely plausible — pile of garbage, which is the version-skew failure
 * this project has already been bitten by once.
 */
public record PycFile(int magic, int flags, long sourceStamp, long sourceLength, byte[] payload) {

    /** The four header bytes {@code importlib.util.MAGIC_NUMBER} reports for CPython 3.14. */
    public static final int MAGIC_3_14 = 0x0a0d0e2b;

    private static final int HEADER_SIZE = 16;
    private static final int FLAG_HASH_BASED = 1;

    public static PycFile parse(byte[] file) {
        if (file.length < HEADER_SIZE) {
            throw MarshalFormatException.badData("file shorter than a pyc header");
        }
        int magic = readIntLE(file, 0);
        if (magic != MAGIC_3_14) {
            throw MarshalFormatException.badData(String.format(
                    "pyc magic %08x is not CPython 3.14's %08x", magic, MAGIC_3_14));
        }
        return new PycFile(
                magic,
                readIntLE(file, 4),
                readIntLE(file, 8) & 0xffffffffL,
                readIntLE(file, 12) & 0xffffffffL,
                java.util.Arrays.copyOfRange(file, HEADER_SIZE, file.length));
    }

    public boolean isHashBased() {
        return (flags & FLAG_HASH_BASED) != 0;
    }

    /** For a hash-based pyc the two stamp fields are one 64-bit source hash. */
    public long sourceHash() {
        return (sourceStamp << 32) | (sourceLength & 0xffffffffL);
    }

    public PyCode readCode() {
        return MarshalReader.readCode(payload, 0);
    }

    private static int readIntLE(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16
                | (b[at + 3] & 0xff) << 24;
    }
}
