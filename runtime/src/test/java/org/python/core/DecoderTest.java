package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The decode behind {@code bytes.decode} and {@code str}, measured against CPython 3.14.10.
 *
 * <p>What one call answers is compared elsewhere: {@code
 * m3/corpus/134_decode_handlers_and_the_octets_they_answer_for} runs the 200-row grid, the UTF-16 question
 * table, the field reads and both argument faces against the reference. Taken apart here is what a grid does
 * not show on its own: that a refusal's range is a run of <em>octets</em> whose width comes from the sequence
 * the walk was in the middle of, that the position a handler sends the walk back to is the handler's own and
 * not the end of that range, and that the name in an error sentence is the name the codec settled on rather
 * than the one the caller wrote.
 *
 * <p>Three things change from the encode half ({@link EncoderTest}). A codec that fails on values collects a
 * run of them when writing and refuses them one at a time when reading, so {@code b"\x80\x81\x82"} is three
 * sentences where {@code "\u0080\u0081\u0082"} is one. The ranges are one to three octets wide and their
 * widths are decided by {@code utf8_decode} (Objects/stringlib/codecs.h:449) and {@code utf16_decode} (:501)
 * rather than by the handler. And {@code xmlcharrefreplace} and {@code namereplace} substitute a character
 * reference for a character the codec could not write; a run of octets that will not decode contains no
 * character, so both answer {@code TypeError} from {@code wrong_exception_type} (Python/codecs.c:675).
 */
class DecoderTest {

    /** {@code b.decode(encoding, errors)} read out as the reference would print the answer. */
    private static String decoded(byte[] octets, String encoding, String errors) {
        return Slots.repr(PyStr.of(Decoder.decode(octets, encoding, errors)));
    }

    /** {@code b.decode(encoding)}: the same call with the handler the default names. */
    private static String decoded(byte[] octets, String encoding) {
        return decoded(octets, encoding, "strict");
    }

    /** {@code octets.decode(arguments)} made the way a program makes it. */
    private static String decode(byte[] octets, PyObject... arguments) {
        return Slots.repr(Slots.call(Slots.getAttr(PyBytes.of(octets), "decode"), arguments, null));
    }

    /** {@code str(octets, arguments)}: the same decoder behind the third constructor. */
    private static String strOf(PyObject... arguments) {
        return Slots.repr(
                Slots.call(Builtins.namespace().get(PyStr.interned("str")), arguments, null));
    }

    /** {@code str(octets, ...)} with the rest given by name. */
    private static String strNamed(String[] names, PyObject... arguments) {
        return Slots.repr(
                Slots.call(
                        Builtins.namespace().get(PyStr.interned("str")),
                        arguments,
                        PyBoundMethod.keywordTuple(names)));
    }

    /** The octets of a row, written as numbers so a sequence shape reads as one. */
    private static byte[] run(int... octets) {
        byte[] data = new byte[octets.length];
        for (int at = 0; at < octets.length; at++) {
            data[at] = (byte) octets[at];
        }
        return data;
    }

    /** {@code (exc.encoding, exc.object, exc.start, exc.end, exc.reason)} for the refusal one call makes. */
    private static String fields(byte[] octets, String encoding, String errors) {
        try {
            Decoder.decode(octets, encoding, errors);
        } catch (PyRaise refused) {
            PyObject error = refused.value();
            return "%s | %s | %s | %s | %s".formatted(
                    Slots.getAttr(error, "encoding").repr(),
                    Slots.getAttr(error, "object").repr(),
                    Slots.getAttr(error, "start").repr(),
                    Slots.getAttr(error, "end").repr(),
                    Slots.getAttr(error, "reason").repr());
        }
        return "no refusal";
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The names are read after the run is known to be non-empty, and a handler only once an octet fails.
     *
     * <p>{@code b"".decode("nope")} and {@code b"".decode("utf-32")} are {@code ''} — the length check is in
     * front of the registry — while one octet of the same encoding name is refused in the registry's words.
     * latin-1 is the third way out of the handler question: it reads every octet there is, so a 256-octet run
     * answered by {@code nope} never asks what {@code nope} is.
     */
    @Test
    void theNamesAreReadAfterTheRunAndOnlyWhenItFails() {
        assertThat(decoded(run(), "nope", "nope2")).isEqualTo("''");
        assertThat(decoded(run(), "utf-32", "strict")).isEqualTo("''");
        assertThat(decoded(run('x'), "ascii", "nope")).isEqualTo("'x'");
        refuses(
                "LookupError: unknown error handler name 'nope'",
                () -> decoded(run(0), "utf-16", "nope"));
        refuses(
                "LookupError: unknown encoding: nope",
                () -> decoded(run('x'), "nope", "strict"));

        byte[] every = new byte[256];
        StringBuilder all = new StringBuilder();
        for (int octet = 0; octet < 256; octet++) {
            every[octet] = (byte) octet;
            all.append((char) octet);
        }
        assertThat(Decoder.decode(every, "latin-1", "nope")).isEqualTo(all.toString());
    }

    /**
     * What a refusal covers is a range of octets, and its width comes from the sequence shape.
     *
     * <p>The same three values that made one run of three characters when encoding make three refusals of one
     * octet each when decoding, so {@code ignore} answers the empty string for all of them and {@code replace}
     * answers three {@code \ufffd}. That is the difference from the encode half, where {@code replace} writes a
     * {@code ?} per character: one two-octet refusal is <em>one</em> {@code \ufffd}, and the three refusals
     * utf-8 makes of {@code b"\xf0\x80\x80"} are three.
     */
    @Test
    void aRefusalCoversOctetsAndReplaceAnswersOneCharacterForAllOfThem() {
        assertThat(decoded(run(0x80, 0x81, 0x82), "utf-8", "ignore")).isEqualTo("''");
        assertThat(decoded(run(0x80, 0x81, 0x82), "utf-8", "replace")).isEqualTo("'\ufffd\ufffd\ufffd'");
        assertThat(decoded(run(0xf0, 0x80, 0x80), "utf-8", "replace")).isEqualTo("'\ufffd\ufffd\ufffd'");
        assertThat(decoded(run(0xe0, 0xa0), "utf-8", "replace")).isEqualTo("'\ufffd'");
        assertThat(decoded(run(0xf0, 0x9f, 0x92), "utf-8", "backslashreplace"))
                .isEqualTo("'\\\\xf0\\\\x9f\\\\x92'");
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0x80 in position 0:"
                        + " invalid start byte",
                () -> decoded(run(0x80, 0x81, 0x82), "utf-8", "strict"));
        refuses(
                "UnicodeEncodeError: 'ascii' codec can't encode characters in position 0-2:"
                        + " ordinal not in range(128)",
                () -> Encoder.encode("\u0080\u0081\u0082", "ascii", "strict"));
    }

    /**
     * The lead byte picks the family, and the family says how many octets the sequence wanted.
     *
     * <p>An octet no family owns is refused as its own single octet. An octet that fails the second or third
     * place of a sequence is refused with the lead in the range and the offender left out, which is why the
     * sentence names the lead and why a second-place failure covers one octet and a third-place one covers
     * two. A sequence that runs out of input is refused by everything that did arrive.
     *
     * <p>The order the 4-byte family asks its questions in is what makes three of a four-octet sequence answer
     * like two: {@code codecs.h:180} judges the second octet <em>before</em> it notices the run is short, so
     * {@code b"\xf0\x80\x80"} is refused as the lead's own octet, exactly as {@code b"\xf0\x00"} is. Only a
     * second octet that survives the {@code 0x90}/{@code 0xF4} test gets to the {@code unexpected end of data}
     * that covers all three.
     */
    @Test
    void theUtf8LeadDecidesTheRangeAndTheOrderItIsJudgedIn() {
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xf5 in position 0: invalid start byte",
                () -> decoded(run(0xf5, 0xff), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xc0 in position 0: invalid start byte",
                () -> decoded(run(0xc0, 0x80), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xa0 in position 0: invalid start byte",
                () -> decoded(run(0xa0, 0x80), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xed in position 0:"
                        + " invalid continuation byte",
                () -> decoded(run(0xed, 0xa0, 0x80), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xf4 in position 0:"
                        + " invalid continuation byte",
                () -> decoded(run(0xf4, 0x90, 0x80, 0x80), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xf0 in position 0:"
                        + " invalid continuation byte",
                () -> decoded(run(0xf0, 0x80, 0x80), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xf0 in position 0: unexpected end of data",
                () -> decoded(run(0xf0), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode bytes in position 0-1: unexpected end of data",
                () -> decoded(run(0xe0, 0xa0), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode bytes in position 0-2: unexpected end of data",
                () -> decoded(run(0xf0, 0x9f, 0x92), "utf-8"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xc3 in position 0: unexpected end of data",
                () -> decoded(run(0xc3), "utf-8"));

        assertThat(decoded(run(0xf0, 0x9f, 0x92, 0xa9), "utf-8")).isEqualTo("'💩'");
        assertThat(decoded(run(0xf4, 0x8f, 0xbf, 0xbf), "utf-8")).isEqualTo("'\\U0010ffff'");
        assertThat(decoded(run(0xe0, 0xa0, 0x80), "utf-8")).isEqualTo("'ࠀ'");
        assertThat(decoded(run(0xc3, 0xa9, 'e'), "utf-8")).isEqualTo("'ée'");
    }

    /**
     * UTF-16 asks four questions, and the order it asks them in is what the rows read.
     *
     * <p>A low surrogate is {@code illegal encoding} for its own two octets, asked before anything behind it is
     * looked at. A high surrogate with fewer than two octets behind it is {@code unexpected end of data}, and
     * its range reaches to the true end rather than to the end of the surrogate. A high surrogate with a unit
     * behind it that is not a low one is {@code illegal UTF-16 surrogate} covering the high unit only, so the
     * unit behind it is read again once the handler answers and {@code ignore} on four octets is the {@code 'a'}
     * that survived. {@code truncated data} is the leftover odd octet, and the only refusal whose range is
     * <em>sized</em> rather than shaped.
     */
    @Test
    void utf16AsksItsFourQuestionsInTheOrderTheWalkMeetsThem() {
        refuses(
                "UnicodeDecodeError: 'utf-16-be' codec can't decode bytes in position 0-1: illegal encoding",
                () -> decoded(run(0xdc, 0x00), "utf-16-be"));
        refuses(
                "UnicodeDecodeError: 'utf-16-be' codec can't decode bytes in position 0-2:"
                        + " unexpected end of data",
                () -> decoded(run(0xd8, 0x00, 0x00), "utf-16-be"));
        refuses(
                "UnicodeDecodeError: 'utf-16-be' codec can't decode bytes in position 0-1:"
                        + " illegal UTF-16 surrogate",
                () -> decoded(run(0xd8, 0x00, 0x00, 'a'), "utf-16-be"));
        refuses(
                "UnicodeDecodeError: 'utf-16-le' codec can't decode byte 0x00 in position 2: truncated data",
                () -> decoded(run(0xd8, 0x00, 0x00), "utf-16-le"));

        assertThat(decoded(run(0xd8, 0x00, 0x00, 'a'), "utf-16-be", "ignore")).isEqualTo("'a'");
        assertThat(decoded(run(0xd8, 0x00, 0x00, 'a'), "utf-16-be", "replace")).isEqualTo("'\ufffda'");
        assertThat(decoded(run(0xdc, 0x00), "utf-16-be", "replace")).isEqualTo("'\ufffd'");
        assertThat(decoded(run(0xdc, 0x00), "utf-16-be", "ignore")).isEqualTo("''");
        assertThat(decoded(run(0xdc, 0x00, 0xd8, 0x00), "utf-16-be", "replace"))
                .isEqualTo("'\ufffd\ufffd'");
        assertThat(decoded(run(0xd8, 0x00, 0x00), "utf-16-be", "ignore")).isEqualTo("''");
        assertThat(decoded(run(0xd8, 0x00, 0xdc, 0x00), "utf-16-be")).isEqualTo("'𐀀'");
        assertThat(decoded(run(0xd8, 0x00, 0x00, 'a'), "utf-16-be", "backslashreplace"))
                .isEqualTo("'\\\\xd8\\\\x00a'");
    }

    /**
     * The name and the run an error describes are the codec's own, not the caller's.
     *
     * <p>{@code utf-16} negotiates an order from the mark at the front and, with no mark, reads the order this
     * machine stores words in; every refusal after that names the order it settled on, so the unmarked codec
     * and {@code utf-16-le} answer one sentence here. {@code utf-8-sig} takes three octets off the front when
     * they are the mark, and the error it raises describes the run behind them — position, object and all. A
     * prefix shorter than three octets is not a mark and is read as the octets it is.
     */
    @Test
    void theNameAndTheRunAnErrorDescribesAreTheCodecsOwn() {
        refuses(
                "UnicodeDecodeError: 'utf-16-le' codec can't decode byte 0x00 in position 2: truncated data",
                () -> decoded(run(0xd8, 0x00, 0x00), "utf-16"));
        assertThat(decoded(run(0xff, 0xfe, 'A', 0x00), "utf-16")).isEqualTo("'A'");
        assertThat(decoded(run(0xfe, 0xff, 0x00, 'A'), "utf-16")).isEqualTo("'A'");
        assertThat(decoded(run(0xff, 0xfe, 'A', 0x00), "utf-16-le")).isEqualTo("'\\ufeffA'");
        assertThat(decoded(run(0xef, 0xbb, 0xbf, 'a', 'b'), "utf-8-sig")).isEqualTo("'ab'");

        assertThat(fields(run(0xef, 0xbb, 0xbf, 'a', 0xe9), "utf-8-sig", "strict"))
                .isEqualTo("'utf-8' | b'a\\xe9' | 1 | 2 | 'unexpected end of data'");
        assertThat(fields(run(0xef, 0xbb, 0xbf, 0xa9), "utf-8-sig", "strict"))
                .isEqualTo("'utf-8' | b'\\xa9' | 0 | 1 | 'invalid start byte'");
        assertThat(fields(run(0xef, 0xbb), "utf-8-sig", "strict"))
                .isEqualTo("'utf-8' | b'\\xef\\xbb' | 0 | 2 | 'unexpected end of data'");
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode bytes in position 0-1: unexpected end of data",
                () -> decoded(run(0xef, 0xbb), "utf-8-sig"));
    }

    /**
     * {@code surrogateescape} answers for part of a range and the codec answers for the rest.
     *
     * <p>The handler can only stand for an octet from {@code 0x80} up, so it walks from the front of the range
     * and stops at the first ASCII octet — and stops at four, because that is the longest sequence any codec
     * fails on. The position it hands back is where it stopped, which can be <em>inside</em> the range it was
     * given: {@code b"\xdc\x00"} read big-endian escapes {@code 0xdc}, resumes at octet 1, and the odd octet
     * left there is refused again. Having escaped nothing at all it declines, and the codec's own refusal comes
     * back unchanged.
     */
    @Test
    void surrogateescapeStopsInsideTheRangeAndReRaisesWhatItLeft() {
        assertThat(decoded(run('a', 0x80, 0x81, 'b'), "utf-8", "surrogateescape"))
                .isEqualTo("'a\\udc80\\udc81b'");
        assertThat(decoded(run('a', 0x80, 0xff, 'b'), "ascii", "surrogateescape"))
                .isEqualTo("'a\\udc80\\udcffb'");
        assertThat(decoded(run(0xd8, 0x00, 0x00), "utf-16-be", "surrogateescape"))
                .isEqualTo("'\\udcd8\\x00'");
        refuses(
                "UnicodeDecodeError: 'utf-16-be' codec can't decode byte 0x00 in position 1: truncated data",
                () -> decoded(run(0xdc, 0x00), "utf-16-be", "surrogateescape"));
        refuses(
                "UnicodeDecodeError: 'utf-16-be' codec can't decode byte 0x61 in position 3: truncated data",
                () -> decoded(run(0xd8, 0x00, 0x00, 'a'), "utf-16-be", "surrogateescape"));
        refuses(
                "UnicodeDecodeError: 'utf-16-le' codec can't decode bytes in position 0-1:"
                        + " illegal UTF-16 surrogate",
                () -> decoded(run(0x00, 0xd8, 'a', 0x00), "utf-16", "surrogateescape"));
    }

    /**
     * {@code surrogatepass} reads the codec's name first, then one unit of that codec's width.
     *
     * <p>{@code get_standard_encoding} (Python/codecs.c) knows {@code utf-8}, {@code utf-16-le} and {@code
     * utf-16-be} and nobody else, so the codecs with a limit re-raise the refusal as it arrived. Where the name
     * is one it knows, the width the handler answers for is its own rather than the range's: a three-octet
     * refusal of a two-octet codec is answered with one unit and the leftover refused again, and the one-octet
     * refusal utf-8 makes of {@code b"\xed\xa0\x80"} is answered with all three octets.
     */
    @Test
    void surrogatepassReadsOneUnitOfTheCodecsOwnWidth() {
        assertThat(decoded(run(0xd8, 0x00), "utf-16-be", "surrogatepass")).isEqualTo("'\\ud800'");
        assertThat(decoded(run(0xdc, 0x00, 0xd8, 0x00), "utf-16-be", "surrogatepass"))
                .isEqualTo("'\\udc00\\ud800'");
        assertThat(decoded(run(0xd8, 0x00, 0x00, 'a'), "utf-16-be", "surrogatepass"))
                .isEqualTo("'\\ud800a'");
        assertThat(decoded(run(0xed, 0xa0, 0x80), "utf-8", "surrogatepass")).isEqualTo("'\\ud800'");
        refuses(
                "UnicodeDecodeError: 'utf-16-be' codec can't decode byte 0x00 in position 2: truncated data",
                () -> decoded(run(0xd8, 0x00, 0x00), "utf-16-be", "surrogatepass"));
        refuses(
                "UnicodeDecodeError: 'ascii' codec can't decode byte 0x80 in position 0:"
                        + " ordinal not in range(128)",
                () -> decoded(run(0x80), "ascii", "surrogatepass"));
        refuses(
                "UnicodeDecodeError: 'utf-8' codec can't decode byte 0xe9 in position 3:"
                        + " unexpected end of data",
                () -> decoded(run(0x63, 0x61, 0x66, 0xe9), "utf-8", "surrogatepass"));
    }

    /**
     * The two names that substitute a character reference have nothing to say about a run of octets.
     *
     * <p>{@code wrong_exception_type} (Python/codecs.c:675) is reached because the handler is asking about a
     * character and the error it was handed describes bytes. The sentence is the same on every codec, and a run
     * that decodes cleanly never reaches it at all.
     */
    @Test
    void theTextHandlersRefuseToAnswerADecodeError() {
        refuses(
                "TypeError: don't know how to handle UnicodeDecodeError in error callback",
                () -> decoded(run(0x80), "utf-8", "xmlcharrefreplace"));
        refuses(
                "TypeError: don't know how to handle UnicodeDecodeError in error callback",
                () -> decoded(run(0xd8, 0x00, 0x00), "utf-8", "namereplace"));
        refuses(
                "TypeError: don't know how to handle UnicodeDecodeError in error callback",
                () -> decoded(
                        run(0x63, 0x61, 0x66, 0xe9, 0x20, 0xe6, 0x97, 0xa5, 0xe6, 0x9c, 0xac),
                        "utf-16-be",
                        "xmlcharrefreplace"));
        assertThat(decoded(run('a', 'b'), "utf-8", "namereplace")).isEqualTo("'ab'");
    }

    /**
     * The registry clips a handler's name and not an encoding's.
     *
     * <p>{@code %.400s} (Python/codecs.c:667) is in the handler sentence and not in {@code unknown encoding:
     * %s}, so the same 405 characters reach the two sentences at different lengths — and the encode half
     * answers the same clip, because it is one registry behind both faces.
     */
    @Test
    void theRegistryQuotesFourHundredCharactersOfAHandlerName() {
        refuses(
                "LookupError: unknown error handler name '" + "x".repeat(400) + "'",
                () -> decoded(run(0x80), "ascii", "x".repeat(405)));
        refuses(
                "LookupError: unknown encoding: " + "y".repeat(405),
                () -> decoded(run(0x80), "y".repeat(405), "strict"));
        refuses(
                "LookupError: unknown error handler name '" + "x".repeat(400) + "'",
                () -> Encoder.encode("\u00e9", "ascii", "x".repeat(405)));
    }

    /**
     * A handler named with a case or a space is a different name, and an unknown one is harmless until the walk
     * needs it.
     *
     * <p>The table is keyed by the exact name, so {@code IGNORE} and {@code ' ignore '} are two refusals beside
     * {@code ignore} itself — and they are refusals only once some octet has actually failed. The encoding is
     * resolved before any of this, so a codec nobody knows is refused even where a handler would have been.
     */
    @Test
    void theHandlerNameIsKeptAsWrittenAndAskedLate() {
        assertThat(decoded(run('x'), "ascii", "nope")).isEqualTo("'x'");
        assertThat(decoded(run(0x80), "latin-1", "nope")).isEqualTo("'\\x80'");
        refuses(
                "LookupError: unknown error handler name 'IGNORE'",
                () -> decoded(run(0x80), "ascii", "IGNORE"));
        refuses(
                "LookupError: unknown error handler name ' ignore '",
                () -> decoded(run(0x80), "ascii", " ignore "));
        refuses(
                "LookupError: unknown encoding: nope",
                () -> decoded(run('x'), "nope", "nope"));
    }

    /**
     * The two converters of {@code decode} name their own parameter, and {@code None} is {@code None}.
     *
     * <p>Both parameters are Clinic {@code str} converters, and a {@code Marker} with a {@code __str__} that
     * answers {@code utf-8} is refused by its own class name rather than asked. An embedded null is refused by
     * the converter before the registry has the name, and the row keeps its signature: two arguments,
     * positional or named, and nothing else.
     */
    @Test
    void theConvertersRefuseInTheirOwnWords() {
        assertThat(decoded(run(0xc3, 0xa9), "utf-8")).isEqualTo("'é'");
        assertThat(decode(run(0x80), PyStr.of("ascii"), PyStr.of("ignore"))).isEqualTo("''");
        assertThat(
                        Slots.repr(
                                Slots.call(
                                        Slots.getAttr(PyBytes.of(run(0x80)), "decode"),
                                        new PyObject[] {PyStr.of("ascii"), PyStr.of("ignore")},
                                        PyBoundMethod.keywordTuple(
                                                new String[] {"encoding", "errors"}))))
                .isEqualTo("''");

        refuses(
                "TypeError: decode() argument 'encoding' must be str, not None",
                () -> decode(run('x'), PyNone.INSTANCE));
        refuses(
                "TypeError: decode() argument 'errors' must be str, not None",
                () -> decode(run('x'), PyStr.of("ascii"), PyNone.INSTANCE));
        refuses(
                "TypeError: decode() argument 'errors' must be str, not int",
                () -> decode(run('x'), PyStr.of("ascii"), PyInt.of(7)));
        refuses(
                "ValueError: embedded null character",
                () -> decode(run('x'), PyStr.of("as\u0000ci")));
        refuses(
                "ValueError: embedded null character",
                () -> decode(run('x'), PyStr.of("ascii"), PyStr.of("ign\u0000re")));
        refuses(
                "TypeError: decode() takes at most 2 arguments (3 given)",
                () -> decode(run('x'), PyStr.of("ascii"), PyStr.of("strict"), PyInt.ONE));
        refuses(
                "TypeError: decode() got an unexpected keyword argument 'enc'",
                () -> Slots.call(
                        Slots.getAttr(PyBytes.of(run('x')), "decode"),
                        new PyObject[] {PyStr.of("ascii")},
                        PyBoundMethod.keywordTuple(new String[] {"enc"})));
    }

    /**
     * {@code str(b, encoding, errors)} is the same decoder behind the constructor's own parser.
     *
     * <p>The rows that decode read like their neighbours on the {@code decode} face, and the refusals about the
     * two names are worded from {@code str()}'s argument list rather than {@code decode()}'s. The pairing
     * questions — what a non-bytes first argument is, and how many arguments the constructor takes — belong to
     * {@code str} and not to the codec.
     */
    @Test
    void theStrConstructorReachesTheSameDecoder() {
        assertThat(strOf(PyBytes.of(run(0xc3, 0xa9)), PyStr.of("utf-8"))).isEqualTo("'é'");
        assertThat(
                        strNamed(
                                new String[] {"encoding", "errors"},
                                PyBytes.of(run(0xc3, 0xa9)),
                                PyStr.of("utf-8"),
                                PyStr.of("strict")))
                .isEqualTo("'é'");
        assertThat(strOf(PyBytes.of(run(0x63, 0x61, 0x66, 0xe9)), PyStr.of("utf-8"), PyStr.of("ignore")))
                .isEqualTo("'caf'");
        assertThat(
                        strOf(
                                PyBytes.of(run('a', 0x80, 0x81, 'b')),
                                PyStr.of("utf-8"),
                                PyStr.of("surrogateescape")))
                .isEqualTo("'a\\udc80\\udc81b'");
        assertThat(strOf(PyBytes.of(run()), PyStr.of("nope"), PyStr.of("nope2"))).isEqualTo("''");

        refuses(
                "LookupError: unknown encoding: nope",
                () -> strOf(PyBytes.of(run('x')), PyStr.of("nope")));
        refuses(
                "TypeError: str() argument 'encoding' must be str, not int",
                () -> strOf(PyBytes.of(run('x')), PyInt.of(7)));
        refuses(
                "TypeError: decoding to str: need a bytes-like object, int found",
                () -> strOf(PyInt.of(1), PyStr.of("utf-8")));
        refuses(
                "TypeError: str expected at most 3 arguments, got 4",
                () -> strOf(
                        PyBytes.of(run('x')), PyStr.of("ascii"), PyStr.of("strict"), PyInt.ONE));
        refuses(
                "TypeError: str() got an unexpected keyword argument 'enc'",
                () -> strNamed(new String[] {"enc"}, PyBytes.of(run('x'))));
    }

    /** One name table, read in both directions: what the encoder wrote, the decoder reads back. */
    @Test
    void theRoundTripAgreesWithTheEncodeHalf() {
        String[][] bothWays = {
            {"caf\u00e9", "utf-8"},
            {"caf\u00e9", "latin-1"},
            {"caf\u00e9", "utf-16"},
            {"caf\u00e9", "utf-8-sig"},
            {"\u65e5\u672c", "utf-8"},
            {"\u65e5\u672c", "utf-16"},
            {"\u65e5\u672c", "utf-8-sig"},
            {"\u0000a\u007f", "latin-1"},
            {"\u0000a\u007f", "utf-16"},
            {"\u00a0", "latin-1"},
            {"\u00a0", "utf-8"},
        };
        for (String[] row : bothWays) {
            byte[] written = Encoder.encode(row[0], row[1], "strict").getValue();
            assertThat(Decoder.decode(written, row[1], "strict"))
                    .as("%s through %s and back", row[0], row[1])
                    .isEqualTo(row[0]);
        }
        assertThat(
                        Decoder.decode(
                                Encoder.encode("a\udc80b", "utf-8", "surrogateescape").getValue(),
                                "utf-8",
                                "surrogateescape"))
                .isEqualTo("a\udc80b");
        assertThat(
                        Decoder.decode(
                                Encoder.encode("\ud800", "utf-8", "surrogatepass").getValue(),
                                "utf-8",
                                "surrogatepass"))
                .isEqualTo("\ud800");
        assertThat(decoded(run(0xe9), "L1")).isEqualTo("'é'");
        refuses("LookupError: unknown encoding: u-t-f8", () -> decoded(run('x'), "u-t-f8", "strict"));
    }
}
