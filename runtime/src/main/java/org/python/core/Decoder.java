package org.python.core;

import java.util.Arrays;

/**
 * {@code bytes.decode}, and the error handlers it walks when an octet run will not decode.
 *
 * <p>The decode half is the encode half seen from the other side of the codec, and three things change. A
 * refusal covers <em>octets</em> rather than characters, so the handler is handed a range of bytes and answers
 * with text plus the position the walk goes back to — and that position is the handler's own, not the end of
 * the range it was given. The ranges are one to three octets wide and come from the shape of the sequence,
 * not from a run of similar offenders: {@code b"\x80\x81\x82"} is three refusals of one octet each where the
 * same three characters above {@code 0x80} were a single run when encoding. And two of the eight handler
 * names name a <em>character</em> the codec could not write; an octet run that will not decode has no
 * character in it, so {@code xmlcharrefreplace} and {@code namereplace} answer {@code TypeError: don't know
 * how to handle UnicodeDecodeError in error callback} ({@code wrong_exception_type}, Python/codecs.c:675).
 *
 * <p>Two codecs ask three of their questions inside their own walk. {@code ascii} and {@code utf-8} carry
 * {@code ignore}, {@code replace} and {@code surrogateescape} as fast paths that never mention the registry,
 * and every range they cover is either one octet or made of nothing but octets from {@code 0x80} up — which
 * is why the fast answer and the registry's answer agree wherever a program can tell them apart. The other
 * names go to the registry, and so does every refusal of {@code utf-16}, which has no fast path at all. That
 * is what makes its {@code surrogateescape} rows the ones that show the generic rule: the handler will not
 * escape an ASCII octet, so it answers for less of the range than it was given and the walk resumes
 * <em>inside</em> the unit it was refusing — {@code b"\xdc\x00"} read big-endian escapes {@code 0xdc},
 * resumes at 1, and then refuses the odd octet left over as {@code truncated data}.
 *
 * <p>The handler's name is settled at the first refusal and not before, as it is when encoding, and the
 * encoding's name is settled before the walk but after the empty check: every decoder returns the empty
 * string before it looks anything up, so {@code b"".decode("nope")} is {@code ''} while {@code
 * b"x".decode("nope")} is refused in the registry's words.
 */
final class Decoder {

    /** The eight names the registry files, as the decode half answers them. */
    private enum Handler {
        STRICT,
        IGNORE,
        REPLACE,
        BACKSLASHREPLACE,
        SURROGATEESCAPE,
        SURROGATEPASS,

        /** {@code xmlcharrefreplace} and {@code namereplace}, which have nothing to say about octets. */
        REFUSED
    }

    /** What a handler gives back: the text it writes, and the octet the walk resumes at. */
    private record Answer(String text, int resume) {}

    /** The order a UTF-16 run is read in, the name its errors carry, and the octets it opens by skipping. */
    private record Utf16(boolean big, String reported, int skip) {}

    private static final String HEX = "0123456789abcdef";

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private Decoder() {}

    /** {@code b.decode(encoding, errors)} and {@code str(b, encoding, errors)}: the same call. */
    static String decode(byte[] data, String encoding, String errors) {
        if (data.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(data.length);
        switch (Encoder.codec(encoding)) {
            case "ascii" -> oneByte(out, data, errors, "ascii", 0x80, "ordinal not in range(128)");
            case "latin_1" -> oneByte(out, data, errors, "latin-1", 0x100, "ordinal not in range(256)");
            case "utf_8" -> utf8(out, data, errors);
            case "utf_8_sig" ->
                    utf8(out, marked(data) ? Arrays.copyOfRange(data, 3, data.length) : data, errors);
            case "utf_16" -> utf16(out, data, errors, stateful(data));
            case "utf_16_le" -> utf16(out, data, errors, new Utf16(false, "utf-16-le", 0));
            default -> utf16(out, data, errors, new Utf16(true, "utf-16-be", 0));
        }
        return out.toString();
    }

    /**
     * The one-octet codecs: {@code unicode_decode_utf16}'s simpler cousins, each of which reads a byte and
     * asks whether it is under the codec's limit.
     *
     * <p>{@code latin-1} accepts every octet there is, so its walk never reaches a handler and never reads a
     * name: {@code b"\x80".decode("latin-1", "nope")} answers {@code '\x80'}.
     */
    private static void oneByte(
            StringBuilder out,
            byte[] data,
            String errors,
            String reported,
            int limit,
            String reason) {
        for (int at = 0; at < data.length; ) {
            int octet = data[at] & 0xff;
            if (octet < limit) {
                out.append((char) octet);
                at++;
                continue;
            }
            at = handled(errors, reported, data, out, at, at + 1, reason);
        }
    }

    /**
     * {@code utf8_decode} (Objects/stringlib/codecs.h:449) as {@code unicode_decode_utf8_impl} (:5308) reads
     * it: the lead byte picks the family, and the family says how many octets the sequence wanted.
     *
     * <p>The four answers a family can give are not the same width. An octet no family owns, and an octet
     * that could own one but cannot own this sequence — {@code 0xC0}, {@code 0xC1}, anything from {@code
     * 0xF5} up, a continuation byte where a lead belongs — is refused as one octet of {@code invalid start
     * byte}. An octet that fails the second or third place of a sequence is refused with the lead in the
     * range and the offender left out: {@code invalid continuation byte} says {@code can't decode byte 0xe0},
     * naming the lead, because what the decoder could not finish was the sequence the lead started. A
     * sequence that runs out of input is refused by everything that did arrive, {@code can't decode bytes in
     * position 0-1}, because those octets are the whole of what is left.
     */
    private static void utf8(StringBuilder out, byte[] data, String errors) {
        String reported = "utf-8";
        for (int at = 0; at < data.length; ) {
            int lead = data[at] & 0xff;
            if (lead < 0x80) {
                out.append((char) lead);
                at++;
                continue;
            }
            if (lead < 0xC2) {
                at = handled(errors, reported, data, out, at, at + 1, "invalid start byte");
                continue;
            }
            int left = data.length - at;
            if (lead < 0xE0) {
                if (left < 2) {
                    at = handled(errors, reported, data, out, at, data.length, "unexpected end of data");
                    continue;
                }
                int second = data[at + 1] & 0xff;
                if (!isContinuation(second)) {
                    at = handled(errors, reported, data, out, at, at + 1, "invalid continuation byte");
                    continue;
                }
                out.append((char) ((lead << 6) + second - ((0xC0 << 6) + 0x80)));
                at += 2;
                continue;
            }
            if (lead < 0xF0) {
                // The 0xE0 and 0xED leads narrow what a second octet may be, and the check runs on a
                // sequence that has not arrived yet as well as on one that has.
                if (left < 2) {
                    at = handled(errors, reported, data, out, at, data.length, "unexpected end of data");
                    continue;
                }
                int second = data[at + 1] & 0xff;
                if (left < 3) {
                    if (!isContinuation(second) || (second < 0xA0 ? lead == 0xE0 : lead == 0xED)) {
                        at = handled(errors, reported, data, out, at, at + 1, "invalid continuation byte");
                    } else {
                        at = handled(errors, reported, data, out, at, data.length, "unexpected end of data");
                    }
                    continue;
                }
                int third = data[at + 2] & 0xff;
                if (!isContinuation(second)
                        || (lead == 0xE0 && second < 0xA0)
                        || (lead == 0xED && second >= 0xA0)) {
                    at = handled(errors, reported, data, out, at, at + 1, "invalid continuation byte");
                    continue;
                }
                if (!isContinuation(third)) {
                    at = handled(errors, reported, data, out, at, at + 2, "invalid continuation byte");
                    continue;
                }
                out.append((char) ((lead << 12) + (second << 6) + third - ((0xE0 << 12) + (0x80 << 6) + 0x80)));
                at += 3;
                continue;
            }
            if (lead >= 0xF5) {
                at = handled(errors, reported, data, out, at, at + 1, "invalid start byte");
                continue;
            }
            if (left < 4) {
                // The second octet is judged before the walk notices the sequence is short, so a run of
                // three answers the same range as a run of two: code 2, the lead's own octet alone.
                if (left < 2) {
                    at = handled(errors, reported, data, out, at, data.length, "unexpected end of data");
                    continue;
                }
                int second = data[at + 1] & 0xff;
                if (!isContinuation(second) || (second < 0x90 ? lead == 0xF0 : lead == 0xF4)) {
                    at = handled(errors, reported, data, out, at, at + 1, "invalid continuation byte");
                    continue;
                }
                if (left < 3) {
                    at = handled(errors, reported, data, out, at, data.length, "unexpected end of data");
                    continue;
                }
                if (!isContinuation(data[at + 2] & 0xff)) {
                    at = handled(errors, reported, data, out, at, at + 2, "invalid continuation byte");
                } else {
                    at = handled(errors, reported, data, out, at, data.length, "unexpected end of data");
                }
                continue;
            }
            int second = data[at + 1] & 0xff;
            int third = data[at + 2] & 0xff;
            int fourth = data[at + 3] & 0xff;
            if (!isContinuation(second)
                    || (lead == 0xF0 && second < 0x90)
                    || (lead == 0xF4 && second >= 0x90)) {
                at = handled(errors, reported, data, out, at, at + 1, "invalid continuation byte");
                continue;
            }
            if (!isContinuation(third)) {
                at = handled(errors, reported, data, out, at, at + 2, "invalid continuation byte");
                continue;
            }
            if (!isContinuation(fourth)) {
                at = handled(errors, reported, data, out, at, at + 3, "invalid continuation byte");
                continue;
            }
            out.appendCodePoint(
                    (lead << 18)
                            + (second << 12)
                            + (third << 6)
                            + fourth
                            - ((0xF0 << 18) + (0x80 << 12) + (0x80 << 6) + 0x80));
            at += 4;
        }
    }

    /**
     * {@code utf16_decode} (Objects/stringlib/codecs.h:501) as {@code PyUnicode_DecodeUTF16Stateful} (:6296)
     * reads it: two octets at a time, and a high surrogate reaches into the pair behind it.
     *
     * <p>The four refusals are asked in the order the walk meets them, and the order is what the rows read.
     * A unit that is a surrogate but not a high one is {@code illegal encoding} for its own two octets, asked
     * before anything is looked at behind it. A high surrogate with no whole pair behind it is {@code
     * unexpected end of data}, and its range reaches to the end of the run rather than to the end of the
     * surrogate: {@code b"\xd8\x00\x00"} reads big-endian says {@code can't decode bytes in position 0-2}.
     * A high surrogate with a pair behind it that is not a low one is {@code illegal UTF-16 surrogate}, and
     * its range is the high surrogate's two octets only — the unit behind it is left for the walk to read
     * again, which is why {@code b"\xd8\x00\x00a".decode("utf-16-be", "ignore")} is {@code 'a'}. An odd octet
     * with nothing else left is {@code truncated data}, and it is the one refusal a two-octet codec reaches
     * only at the end, because the pairs before it were read normally.
     *
     * <p>The end the surrogate checks stop at is one octet short of the real end, which is why a high
     * surrogate that has exactly one octet behind it is refused as {@code unexpected end of data} covering
     * that octet, and not as something about the octet itself.
     */
    private static void utf16(
            StringBuilder out, byte[] data, String errors, Utf16 order) {
        String reported = order.reported();
        for (int at = order.skip(); at < data.length; ) {
            if (data.length - at < 2) {
                at = handled(errors, reported, data, out, at, data.length, "truncated data");
                continue;
            }
            int first = unit(data, at, order.big());
            at += 2;
            if (!isSurrogate(first)) {
                out.append((char) first);
                continue;
            }
            if (first >= 0xDC00) {
                at = handled(errors, reported, data, out, at - 2, at, "illegal encoding");
                continue;
            }
            if (data.length - at < 2) {
                at = handled(errors, reported, data, out, at - 2, data.length, "unexpected end of data");
                continue;
            }
            int second = unit(data, at, order.big());
            if (second < 0xDC00 || second > 0xDFFF) {
                at = handled(errors, reported, data, out, at - 2, at, "illegal UTF-16 surrogate");
                continue;
            }
            at += 2;
            out.appendCodePoint(0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00));
        }
    }

    /** {@code utf-16} with no order given: the mark at the front decides, and this machine's order decides. */
    private static Utf16 stateful(byte[] data) {
        if (data.length >= 2) {
            int mark = ((data[1] & 0xff) << 8) | (data[0] & 0xff);
            if (mark == 0xFEFF) {
                return new Utf16(false, "utf-16-le", 2);
            }
            if (mark == 0xFFFE) {
                return new Utf16(true, "utf-16-be", 2);
            }
        }
        // A run with no mark is read in the order the machine stores words in, and its errors name that
        // order: on CPython's little-endian builds `b"\x00".decode("utf-16")` is refused by 'utf-16-le'.
        return new Utf16(false, "utf-16-le", 0);
    }

    private static int unit(byte[] data, int at, boolean big) {
        int first = data[at] & 0xff;
        int second = data[at + 1] & 0xff;
        return big ? (first << 8) | second : (second << 8) | first;
    }

    /** {@code _Py_normalize_encoding}'s other use: the mark {@code utf-8-sig} takes off the front. */
    private static boolean marked(byte[] data) {
        return data.length >= 3
                && data[0] == UTF8_BOM[0]
                && data[1] == UTF8_BOM[1]
                && data[2] == UTF8_BOM[2];
    }

    /**
     * One refusal, answered: the handler writes its text and the walk goes back to the position it names.
     */
    private static int handled(
            String errors,
            String reported,
            byte[] data,
            StringBuilder out,
            int start,
            int stop,
            String reason) {
        Answer answer = answer(named(errors), reported, data, start, stop, reason);
        out.append(answer.text());
        return answer.resume();
    }

    /**
     * What one handler answers for the octets at {@code [start, stop)}.
     *
     * <p>The three substitution names write the same answer for a range of any width: {@code ignore} nothing,
     * {@code replace} <em>one</em> {@code \ufffd} however many octets the codec gave up on, {@code
     * backslashreplace} an escape per octet. That is the difference from the encode half, where {@code
     * replace} writes a {@code ?} per character.
     */
    private static Answer answer(
            Handler handler,
            String reported,
            byte[] data,
            int start,
            int stop,
            String reason) {
        switch (handler) {
            case STRICT:
                throw Slots.unicodeDecode(reported, data, start, stop, reason);
            case IGNORE:
                return new Answer("", stop);
            case REPLACE:
                return new Answer("\ufffd", stop);
            case BACKSLASHREPLACE: {
                StringBuilder escaped = new StringBuilder(4 * (stop - start));
                for (int at = start; at < stop; at++) {
                    int octet = data[at] & 0xff;
                    escaped
                            .append('\\')
                            .append('x')
                            .append(HEX.charAt(octet >> 4))
                            .append(HEX.charAt(octet & 0xf));
                }
                return new Answer(escaped.toString(), stop);
            }
            case SURROGATEESCAPE:
                return escape(reported, data, start, stop, reason);
            case SURROGATEPASS:
                return pass(reported, data, start, stop, reason);
            case REFUSED:
                throw Slots.typeError(
                    "don't know how to handle UnicodeDecodeError in error callback");
        }
        throw new AssertionError(handler);
    }

    /**
     * {@code _PyCodec_SurrogateEscapeUnicodeDecodeError} (Python/codecs.c:1340): the octet each of up to four
     * non-ASCII bytes stands for.
     *
     * <p>The handler reads from the front of the range, stops at the first octet below {@code 0x80} because
     * an ASCII octet has no surrogate to stand for it, and stops at four because that is the longest sequence
     * any codec can fail on. Having escaped nothing at all it declines, and the codec's own refusal stands —
     * which is the answer {@code b"\x00\xd8a\x00".decode("utf-16", "surrogateescape")} gives, unchanged,
     * because the range it was given opened with an ASCII octet.
     */
    private static Answer escape(String reported, byte[] data, int start, int stop, String reason) {
        StringBuilder escaped = new StringBuilder();
        int at = start;
        while (at < stop && escaped.length() < 4 && (data[at] & 0xff) >= 0x80) {
            escaped.append((char) (0xDC00 + (data[at] & 0xff)));
            at++;
        }
        if (at == start) {
            throw Slots.unicodeDecode(reported, data, start, stop, reason);
        }
        return new Answer(escaped.toString(), at);
    }

    /**
     * {@code _PyCodec_SurrogatePassUnicodeDecodeError} (Python/codecs.c:1249): one surrogate, read from the
     * front of the range, whatever else the range covers.
     *
     * <p>The handler asks the codec's own name first — {@code get_standard_encoding} knows {@code utf-8},
     * {@code utf-16-le} and {@code utf-16-be} and nobody else, so an {@code ascii} refusal is re-raised as it
     * arrived. Then it reads one unit of the codec's width at the position the codec complained about, and if
     * that unit is not a surrogate it re-raises too. So the width the handler answers for is its own and not
     * the range's: {@code b"\xd8\x00\x00"} complains about three octets and the handler takes two, and the
     * walk resumes on the leftover, while {@code b"\xed\xa0\x80"} complains about the lead's own octet and
     * the handler takes all three and returns the surrogate they stand for.
     */
    private static Answer pass(String reported, byte[] data, int start, int stop, String reason) {
        int width =
                switch (reported) {
                    case "utf-8" -> 3;
                    case "utf-16-le", "utf-16-be" -> 2;
                    default -> 0;
                };
        int ch = 0;
        if (width != 0 && data.length - start >= width) {
            int first = data[start] & 0xff;
            int second = data[start + 1] & 0xff;
            if (width == 3) {
                int third = data[start + 2] & 0xff;
                if ((first & 0xf0) == 0xE0 && isContinuation(second) && isContinuation(third)) {
                    ch = ((first & 0x0f) << 12) + ((second & 0x3f) << 6) + (third & 0x3f);
                }
            } else {
                ch = reported.endsWith("-be")
                        ? (first << 8) | second
                        : (second << 8) | first;
            }
        }
        if (!isSurrogate(ch)) {
            throw Slots.unicodeDecode(reported, data, start, stop, reason);
        }
        return new Answer(String.valueOf((char) ch), start + width);
    }

    /** A byte that may stand in the second, third or fourth place of a UTF-8 sequence. */
    private static boolean isContinuation(int octet) {
        return (octet & 0xC0) == 0x80;
    }

    private static boolean isSurrogate(int codePoint) {
        return codePoint >= 0xD800 && codePoint <= 0xDFFF;
    }

    /** The registry's answer to a handler name: the six it answers for octets, and the two it does not. */
    private static Handler named(String errors) {
        return switch (errors) {
            case "strict" -> Handler.STRICT;
            case "ignore" -> Handler.IGNORE;
            case "replace" -> Handler.REPLACE;
            case "backslashreplace" -> Handler.BACKSLASHREPLACE;
            case "surrogateescape" -> Handler.SURROGATEESCAPE;
            case "surrogatepass" -> Handler.SURROGATEPASS;
            case "xmlcharrefreplace", "namereplace" -> Handler.REFUSED;
            default -> throw Slots.lookupError(
                    "unknown error handler name '" + Encoder.clipped(errors) + "'");
        };
    }
}
