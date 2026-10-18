package org.python.front.cpython314;

/**
 * UTF-8 that admits lone surrogates, because {@code marshal} writes strings with
 * {@code surrogatepass} and a code object's docstring or constant may legally contain one.
 */
final class SurrogatePass {

    private SurrogatePass() {
    }

    static String decode(byte[] in, int offset, int length) {
        if (offset + length > in.length) {
            throw MarshalFormatException.truncated();
        }
        StringBuilder out = new StringBuilder(length);
        int end = offset + length;
        for (int i = offset; i < end; ) {
            int b = in[i++] & 0xff;
            if (b < 0x80) {
                out.append((char) b);
                continue;
            }
            int extra = b >= 0xf0 ? 3 : b >= 0xe0 ? 2 : 1;
            if (i + extra > end) {
                throw MarshalFormatException.badData("incomplete UTF-8 sequence");
            }
            int cp = b & (0x3f >> extra);
            for (int k = 0; k < extra; k++) {
                cp = (cp << 6) | (in[i++] & 0x3f);
            }
            if (cp <= 0xffff) {
                out.append((char) cp);
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }
}
