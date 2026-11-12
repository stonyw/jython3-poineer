package org.python.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * The {@code binascii} module: conversion between binary data and ASCII.
 *
 * <p>Only the surface the standard library actually calls is built here, but each row answers with the
 * reference's own sentences: the same {@code Error} for a bad alphabet, the same split between
 * {@code Excess padding} and {@code Excess data} in a strict base64 decode, and the same treatment of
 * whitespace in a uuencoded line.
 */
final class BinasciiModule {

    private static final String DOC = "Conversion between binary data and ASCII";

    private static final String A2B_BASE64_DOC =
            "Decode a line of base64 data.\n"
            + "\n"
            + "  strict_mode\n"
            + "    When set to True, bytes that are not part of the base64 standard are not allowed.\n"
            + "    The same applies to excess data after padding (= / ==).";

    private static final String B2A_BASE64_DOC = "Base64-code line of data.";

    private static final String A2B_QP_DOC = "Decode a string of qp-encoded data.";

    private static final String B2A_QP_DOC =
            "Encode a string using quoted-printable encoding.\n"
            + "\n"
            + "On encoding, when istext is set, newlines are not encoded, and white\n"
            + "space at end of lines is.  When istext is not set, \\r and \\n (CR/LF)\n"
            + "are both encoded.  When quotetabs is set, space and tabs are encoded.";

    private static final String A2B_UU_DOC = "Decode a line of uuencoded data.";

    private static final String B2A_UU_DOC = "Uuencode line of data.";

    private static final String A2B_HEX_DOC =
            "Binary data of hexadecimal representation.\n"
            + "\n"
            + "hexstr must contain an even number of hex digits (upper or lower case).\n"
            + "This function is also available as \"unhexlify()\".";

    private static final String B2A_HEX_DOC =
            "Hexadecimal representation of binary data.\n"
            + "\n"
            + "  sep\n"
            + "    An optional single character or byte to separate hex bytes.\n"
            + "  bytes_per_sep\n"
            + "    How many bytes between separators.  Positive values count from the\n"
            + "    right, negative values count from the left.\n"
            + "\n"
            + "The return value is a bytes object.  This function is also\n"
            + "available as \"hexlify()\".";

    private static final String HEXLIFY_DOC =
            B2A_HEX_DOC.replace("\"b2a_hex()\"", "\"hexlify()\"");

    private static final String UNHEXLIFY_DOC =
            "Binary data of hexadecimal representation.\n"
            + "\n"
            + "hexstr must contain an even number of hex digits (upper or lower case).";

    private static final String CRC32_DOC = "Compute CRC-32 incrementally.";

    private static final String CRC_HQX_DOC = "Compute CRC-CCITT incrementally.";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final int[] CRC32_TABLE = new int[256];

    private static final int[] CRC_HQX_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xEDB88320 : crc >>> 1;
            }
            CRC32_TABLE[i] = crc;
            int hqx = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                hqx = (hqx & 0x8000) != 0 ? (hqx << 1) ^ 0x1021 : hqx << 1;
            }
            CRC_HQX_TABLE[i] = hqx & 0xffff;
        }
    }

    private BinasciiModule() {}

    static PyModule newModule() {
        PyModule module = new PyModule("binascii");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "binascii");
        module.set("Error", ExceptionTypes.BINASCII_ERROR);
        module.set("Incomplete", ExceptionTypes.BINASCII_INCOMPLETE);
        module.set("a2b_base64", new PyNativeFunction("a2b_base64", BinasciiModule::a2bBase64)
                .documented(A2B_BASE64_DOC));
        module.set("b2a_base64", new PyNativeFunction("b2a_base64", BinasciiModule::b2aBase64)
                .documented(B2A_BASE64_DOC));
        module.set("a2b_qp", new PyNativeFunction("a2b_qp", BinasciiModule::a2bQp)
                .documented(A2B_QP_DOC));
        module.set("b2a_qp", new PyNativeFunction("b2a_qp", BinasciiModule::b2aQp)
                .documented(B2A_QP_DOC));
        module.set("a2b_uu", new PyNativeFunction("a2b_uu", BinasciiModule::a2bUu)
                .documented(A2B_UU_DOC));
        module.set("b2a_uu", new PyNativeFunction("b2a_uu", BinasciiModule::b2aUu)
                .documented(B2A_UU_DOC));
        module.set("a2b_hex", new PyNativeFunction("a2b_hex", BinasciiModule::a2bHex)
                .documented(A2B_HEX_DOC));
        module.set("b2a_hex", new PyNativeFunction("b2a_hex", BinasciiModule::b2aHex)
                .documented(B2A_HEX_DOC));
        module.set("unhexlify", new PyNativeFunction("unhexlify", BinasciiModule::a2bHex)
                .documented(UNHEXLIFY_DOC));
        module.set("hexlify", new PyNativeFunction("hexlify", BinasciiModule::b2aHex)
                .documented(HEXLIFY_DOC));
        module.set("crc32", new PyNativeFunction("crc32", BinasciiModule::crc32)
                .documented(CRC32_DOC));
        module.set("crc_hqx", new PyNativeFunction("crc_hqx", BinasciiModule::crcHqx)
                .documented(CRC_HQX_DOC));
        return module;
    }

    // --- argument reading --------------------------------------------------------------

    private static PyRaise error(String message) {
        return PyRaise.raise(ExceptionTypes.BINASCII_ERROR, message);
    }

    /** A copy of the octets a buffer argument exports, in CPython's own refusal's words. */
    private static byte[] octets(PyObject value) {
        return PyMemoryView.bufferOctets(value);
    }

    /** The one positional argument a conversion takes, refused by both name and count. */
    private static PyObject onePositional(String name, PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (args.length > 1 || (kwNames != null && kwNames.length > 0)) {
            throw Slots.typeError(
                    "%s() takes exactly 1 positional argument (%d given)", name, args.length);
        }
        if (positional == 0) {
            throw Slots.typeError(
                    "%s() missing required argument 'data' (pos 1)", name);
        }
        return args[0];
    }

    /** The one keyword these functions take, or the refusal the reference prints for another. */
    private static boolean keyword(
            String qualified, String wanted, PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 1) {
            throw Slots.typeError(
                    "%s() takes exactly 1 positional argument (%d given)", qualified, positional);
        }
        boolean flag = false;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!wanted.equals(kwNames[i])) {
                    throw Slots.typeError(
                            "%s() got an unexpected keyword argument '%s'", qualified, kwNames[i]);
                }
                flag = Slots.truth(args[positional + i]);
            }
        }
        return flag;
    }

    // --- base64 ------------------------------------------------------------------------

    private static int base64Value(int c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        return -1;
    }

    private static PyRaise invalidCount(int count) {
        return error(String.format(
                "Invalid base64-encoded string: number of data characters (%d) cannot be 1 more"
                        + " than a multiple of 4",
                count));
    }

    private static PyObject a2bBase64(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional == 0) {
            throw Slots.typeError(
                    "a2b_base64() missing required argument 'data' (pos 1)");
        }
        if (positional > 1) {
            throw Slots.typeError(
                    "a2b_base64() takes exactly 1 positional argument (%d given)", positional);
        }
        boolean strict = keyword("a2b_base64", "strict_mode", args, kwNames);
        byte[] data = octets(args[0]);
        return PyBytes.wrap(strict ? decodeStrict(data) : decodeLoose(data));
    }

    /** The lenient decoder: it drops everything that is neither a digit nor padding. */
    private static byte[] decodeLoose(byte[] data) {
        int[] values = new int[data.length];
        int count = 0;
        int pads = 0;
        for (byte raw : data) {
            int c = raw & 0xff;
            int value = base64Value(c);
            if (value >= 0) {
                values[count++] = value;
            } else if (c == '=') {
                pads++;
            }
        }
        int remainder = count % 4;
        if (remainder == 1) {
            throw invalidCount(count);
        }
        int required = (4 - remainder) % 4;
        if (pads < required) {
            throw error("Incorrect padding");
        }
        return assemble(values, count);
    }

    /** The strict decoder: every character must be a digit or padding, in its one position. */
    private static byte[] decodeStrict(byte[] data) {
        int[] values = new int[data.length];
        int count = 0;
        int quadPosition = 0;
        boolean done = false;
        for (byte raw : data) {
            int c = raw & 0xff;
            int value = base64Value(c);
            if (value >= 0) {
                if (done) {
                    throw error("Excess data after padding");
                }
                values[count++] = value;
                if (++quadPosition == 4) {
                    quadPosition = 0;
                }
            } else if (c == '=') {
                if (done) {
                    throw error("Excess padding not allowed");
                }
                if (quadPosition == 0) {
                    throw error("Leading padding not allowed");
                }
                if (quadPosition == 1) {
                    throw error("Incorrect padding");
                }
                if (++quadPosition == 4) {
                    done = true;
                    quadPosition = 0;
                }
            } else {
                throw error("Only base64 data is allowed");
            }
        }
        if (!done && quadPosition != 0) {
            if (quadPosition == 1) {
                throw invalidCount(count);
            }
            throw error("Incorrect padding");
        }
        return assemble(values, count);
    }

    /** Decode {@code count} six-bit values, keeping only the whole octets they spell. */
    private static byte[] assemble(int[] values, int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(count * 3 / 4 + 3);
        int bits = 0;
        int have = 0;
        for (int i = 0; i < count; i++) {
            bits = (bits << 6) | values[i];
            have += 6;
            if (have >= 8) {
                have -= 8;
                out.write((bits >> have) & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static PyObject b2aBase64(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional == 0) {
            throw Slots.typeError(
                    "b2a_base64() missing required argument 'data' (pos 1)");
        }
        if (positional > 1) {
            throw Slots.typeError(
                    "b2a_base64() takes exactly 1 positional argument (%d given)", positional);
        }
        boolean newline = true;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!"newline".equals(kwNames[i])) {
                    throw Slots.typeError(
                            "b2a_base64() got an unexpected keyword argument '%s'", kwNames[i]);
                }
                newline = Slots.truth(args[positional + i]);
            }
        }
        byte[] data = octets(args[0]);
        int total = (data.length + 2) / 3 * 4 + (newline ? 1 : 0);
        byte[] out = new byte[total];
        int at = 0;
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            int triple = (b0 << 16) | (b1 << 8) | b2;
            out[at++] = (byte) "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                    .charAt((triple >> 18) & 0x3f);
            out[at++] = (byte) "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                    .charAt((triple >> 12) & 0x3f);
            int third = (triple >> 6) & 0x3f;
            int fourth = triple & 0x3f;
            out[at++] = (byte) (i + 1 < data.length
                    ? "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".charAt(third)
                    : '=');
            out[at++] = (byte) (i + 2 < data.length
                    ? "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".charAt(fourth)
                    : '=');
        }
        if (newline) {
            out[at] = '\n';
        }
        return PyBytes.wrap(out);
    }

    // --- hexadecimal -------------------------------------------------------------------

    private static PyObject a2bHex(PyObject[] args, String[] kwNames) {
        PyObject value = onePositional("unhexlify", args, kwNames);
        byte[] text;
        if (value instanceof PyStr str) {
            String literal = str.getValue();
            for (int i = 0; i < literal.length(); i++) {
                if (literal.charAt(i) > 0x7f) {
                    throw Slots.valueError(
                            "string argument should contain only ASCII characters");
                }
            }
            text = literal.getBytes(StandardCharsets.US_ASCII);
        } else {
            text = octets(value);
        }
        if (text.length % 2 != 0) {
            throw error("Odd-length string");
        }
        byte[] out = new byte[text.length / 2];
        for (int i = 0; i < out.length; i++) {
            int high = hexDigit(text[2 * i]);
            int low = hexDigit(text[2 * i + 1]);
            if (high < 0 || low < 0) {
                throw error("Non-hexadecimal digit found");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return PyBytes.wrap(out);
    }

    private static int hexDigit(byte raw) {
        int c = raw & 0xff;
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static PyObject b2aHex(PyObject[] args, String[] kwNames) {
        String qualified = "hexlify";
        int positional = Arguments.positional(args, kwNames);
        PyObject data = null;
        PyObject sep = PyNone.INSTANCE;
        PyObject perSep = PyInt.of(1);
        if (positional > 0) {
            data = args[0];
        }
        if (positional > 1) {
            sep = args[1];
        }
        if (positional > 2) {
            perSep = args[2];
        }
        if (positional > 3) {
            throw Slots.typeError(
                    "%s() takes at most 3 arguments (%d given)", qualified, positional);
        }
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject given = args[positional + i];
                switch (kwNames[i]) {
                    case "sep" -> sep = given;
                    case "bytes_per_sep" -> perSep = given;
                    default -> throw Slots.typeError(
                            "%s() got an unexpected keyword argument '%s'", qualified, kwNames[i]);
                }
            }
        }
        if (data == null) {
            throw Slots.typeError("%s() missing required argument 'data' (pos 1)", qualified);
        }
        byte[] octets = octets(data);
        byte[] written = formatHex(octets, sep, perSep, qualified);
        return PyBytes.wrap(written);
    }

    /** {@code hexlify}, with the separators a caller may ask for between whole groups. */
    private static byte[] formatHex(byte[] data, PyObject sep, PyObject perSep, String qualified) {
        byte[] separator = null;
        if (sep != PyNone.INSTANCE) {
            if (sep instanceof PyStr str) {
                String literal = str.getValue();
                if (literal.length() != 1) {
                    throw Slots.valueError("sep must be length 1.");
                }
                separator = literal.getBytes(StandardCharsets.US_ASCII);
            } else {
                separator = octets(sep);
                if (separator.length != 1) {
                    throw Slots.valueError("sep must be length 1.");
                }
            }
        }
        int step = 0;
        if (separator != null) {
            BigInteger count = Numbers.asIndex(perSep);
            if (count.signum() > 0) {
                step = count.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
            } else if (count.signum() < 0) {
                step = -count.abs().min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
            }
        }
        if (step == 0 || data.length < 2) {
            byte[] out = new byte[data.length * 2];
            for (int i = 0; i < data.length; i++) {
                out[2 * i] = (byte) HEX[(data[i] >> 4) & 0xf];
                out[2 * i + 1] = (byte) HEX[data[i] & 0xf];
            }
            return out;
        }
        int magnitude = Math.abs(step);
        int separators = (data.length - 1) / magnitude;
        byte[] out = new byte[data.length * 2 + separators];
        int at = 0;
        for (int i = 0; i < data.length; i++) {
            out[at++] = (byte) HEX[(data[i] >> 4) & 0xf];
            out[at++] = (byte) HEX[data[i] & 0xf];
            if (i == data.length - 1) {
                break;
            }
            boolean boundary = step > 0
                    ? (data.length - 1 - i) % magnitude == 0
                    : (i + 1) % magnitude == 0;
            if (boundary) {
                out[at++] = separator[0];
            }
        }
        return out;
    }

    // --- uuencode ----------------------------------------------------------------------

    private static PyObject a2bUu(PyObject[] args, String[] kwNames) {
        byte[] line = octets(onePositional("a2b_uu", args, kwNames));
        if (line.length == 0) {
            throw error("Missing length byte");
        }
        int length = (line[0] - 32) & 0x3f;
        byte[] out = new byte[length];
        int at = 0;
        int bits = 0;
        int have = 0;
        for (int i = 1; i < line.length && at < length; i++) {
            int c = line[i] & 0xff;
            if (c == '\n' || c == '\r') {
                break;
            }
            bits = (bits << 6) | ((c - 32) & 0x3f);
            have += 6;
            if (have >= 8) {
                have -= 8;
                out[at++] = (byte) ((bits >> have) & 0xff);
            }
        }
        return PyBytes.wrap(out);
    }

    private static PyObject b2aUu(PyObject[] args, String[] kwNames) {
        byte[] data = octets(onePositional("b2a_uu", args, kwNames));
        if (data.length > 45) {
            throw Slots.valueError("At most 45 bytes at once");
        }
        int groups = (data.length + 2) / 3;
        byte[] out = new byte[1 + groups * 4 + 1];
        int at = 0;
        out[at++] = (byte) ((data.length & 0x3f) + 32);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            int triple = (b0 << 16) | (b1 << 8) | b2;
            out[at++] = (byte) (((triple >> 18) & 0x3f) + 32);
            out[at++] = (byte) (((triple >> 12) & 0x3f) + 32);
            out[at++] = (byte) (((triple >> 6) & 0x3f) + 32);
            out[at++] = (byte) ((triple & 0x3f) + 32);
        }
        out[at] = '\n';
        return PyBytes.wrap(out);
    }

    // --- quoted-printable --------------------------------------------------------------

    private static PyObject a2bQp(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional == 0) {
            throw Slots.typeError("a2b_qp() missing required argument 'data' (pos 1)");
        }
        boolean header = keyword("a2b_qp", "header", args, kwNames);
        byte[] data = octets(args[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; ) {
            int c = data[i] & 0xff;
            if (c == '=') {
                if (i + 1 < data.length && data[i + 1] == '\n') {
                    i += 2;
                    continue;
                }
                if (i + 2 < data.length && data[i + 1] == '\r' && data[i + 2] == '\n') {
                    i += 3;
                    continue;
                }
                if (i + 2 < data.length) {
                    int high = hexDigit(data[i + 1]);
                    int low = hexDigit(data[i + 2]);
                    if (high >= 0 && low >= 0) {
                        out.write((high << 4) | low);
                        i += 3;
                        continue;
                    }
                }
                out.write('=');
                i++;
                continue;
            }
            if (header && c == '_') {
                out.write(' ');
                i++;
                continue;
            }
            out.write(c);
            i++;
        }
        return PyBytes.wrap(out.toByteArray());
    }

    private static PyObject b2aQp(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional == 0) {
            throw Slots.typeError("b2a_qp() missing required argument 'data' (pos 1)");
        }
        PyObject[] values = Arguments.fill(
                "b2a_qp",
                args,
                kwNames,
                new String[] {"data", "quotetabs", "istext", "header"},
                new PyObject[] {PyBool.FALSE, PyBool.TRUE, PyBool.FALSE});
        boolean quoteTabs = Slots.truth(values[1]);
        boolean isText = Slots.truth(values[2]);
        boolean header = Slots.truth(values[3]);
        byte[] data = octets(values[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 8);
        int col = 0;
        for (int i = 0; i < data.length; i++) {
            int c = data[i] & 0xff;
            boolean atEnd = i + 1 == data.length || data[i + 1] == '\n';
            if (isText && c == '\r') {
                out.write('\r');
                col = 0;
                continue;
            }
            if (c == '\n') {
                out.write('\n');
                col = 0;
                continue;
            }
            if (c == ' ' && header) {
                if (isText && !atEnd && col + 1 >= 76) {
                    softBreak(out);
                    col = 0;
                }
                out.write('_');
                col++;
                continue;
            }
            boolean literal = c >= 33 && c <= 126 && c != '=';
            if ((c == ' ' || c == '\t') && !quoteTabs && !atEnd) {
                literal = true;
            }
            if (literal) {
                if (isText && !atEnd && col + 1 >= 76) {
                    softBreak(out);
                    col = 0;
                }
                out.write(c);
                col++;
            } else {
                if (isText && !atEnd && col + 3 >= 76) {
                    softBreak(out);
                    col = 0;
                }
                out.write('=');
                out.write(HEX[(c >> 4) & 0xf]);
                out.write(HEX[c & 0xf]);
                col += 3;
            }
        }
        return PyBytes.wrap(out.toByteArray());
    }

    private static void softBreak(ByteArrayOutputStream out) {
        out.write('=');
        out.write('\n');
    }

    // --- checksums ---------------------------------------------------------------------

    private static PyObject crc32(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.fill(
                "crc32",
                args,
                kwNames,
                new String[] {"data", "crc"},
                new PyObject[] {PyInt.of(0)});
        if (values[0] == null) {
            throw Slots.typeError("crc32() missing required argument 'data' (pos 1)");
        }
        byte[] data = octets(values[0]);
        int crc = (int) Numbers.clipped(values[1]) ^ 0xFFFFFFFF;
        for (byte b : data) {
            crc = CRC32_TABLE[(crc ^ b) & 0xff] ^ (crc >>> 8);
        }
        return PyInt.of((crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL);
    }

    private static PyObject crcHqx(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.fill(
                "crc_hqx",
                args,
                kwNames,
                new String[] {"data", "crc"},
                new PyObject[] {null});
        if (values[0] == null) {
            throw Slots.typeError("crc_hqx() missing required argument 'data' (pos 1)");
        }
        if (values[1] == null) {
            throw Slots.typeError("crc_hqx() missing required argument 'crc' (pos 2)");
        }
        byte[] data = octets(values[0]);
        int crc = (int) Numbers.clipped(values[1]) & 0xffff;
        for (byte b : data) {
            crc = ((crc << 8) & 0xffff) ^ CRC_HQX_TABLE[((crc >> 8) ^ (b & 0xff)) & 0xff];
        }
        return PyInt.of(crc);
    }
}
