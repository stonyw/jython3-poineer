package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The encode behind {@code str.encode} and {@code bytes}, measured against CPython 3.14.10.
 *
 * <p>What one call answers is compared elsewhere: {@code m3/corpus/133_encode_handlers_and_the_runs_they_answer_for}
 * runs the 200-row grid, the name table and both argument faces against the reference. Taken apart here is what
 * a grid cannot show: that the handler is looked up <em>after</em> a character has failed, that the run a codec
 * collects is what the sentence then names, and that the escape table {@code ascii} reads is the very one
 * {@code backslashreplace} writes.
 *
 * <p>Three questions are asked in order and each has its own words. The parser's converters take the two names
 * ({@code Objects/clinic/unicodeobject.c.h:253} for {@code encode}, {@code Objects/bytesobject.c:2767} for
 * {@code bytes}), {@code _Py_normalize_encoding} (Objects/unicodeobject.c:3605) turns the spelling a program
 * wrote into the name the registry files, and only then does the codec start writing. {@code
 * unicode_encode_call_errorhandler} (Objects/unicodeobject.c:7322) is reached from inside the failure, which is
 * why a handler name nobody knows is harmless until something actually goes wrong.
 *
 * <p>What "something goes wrong" is differs by codec, and the difference is in the sentence. {@code
 * unicode_encode_ucs1} (Objects/unicodeobject.c:7378) walks a <em>maximal run</em> of characters over the
 * codec's limit — {@code 0x80} for ascii, {@code 0x100} for latin-1 — and utf-8 uses the same walk with the
 * surrogate range in place of a limit, so those three name a range and say {@code characters}. {@code
 * _PyUnicode_EncodeUTF16} (Objects/unicodeobject.c:6441) refuses one character and so always names a position
 * of its own.
 */
class EncoderTest {

    /** {@code text.encode(encoding, errors)} read out as the reference would print the answer. */
    private static String encoded(String text, String encoding, String errors) {
        return Slots.repr(Encoder.encode(text, encoding, errors));
    }

    /** {@code text.encode(arguments)} made the way a program makes it. */
    private static String encode(PyObject... arguments) {
        PyObject row = Slots.getAttr(PyStr.of("caf\u00e9"), "encode");
        return Slots.repr(Slots.call(row, arguments, null));
    }

    /** {@code bytes(arguments)} read out the same way. */
    private static String bytesOf(PyObject... arguments) {
        return Slots.repr(Slots.call(bytesRow(), arguments, null));
    }

    /** {@code bytes(...)} with the rest given by name. */
    private static String bytesNamed(String[] names, PyObject... arguments) {
        return Slots.repr(
                Slots.call(bytesRow(), arguments, PyBoundMethod.keywordTuple(names)));
    }

    private static PyObject bytesRow() {
        return Builtins.namespace().get(PyStr.interned("bytes"));
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
     * The spelling a program writes is not the name the registry files.
     *
     * <p>Letters, digits and the dot survive; every other <em>run</em> of characters becomes one underscore and
     * never a leading one. So the hyphen of {@code utf-8} and the underscore of {@code utf_8} reach the same
     * module, and {@code ANSI_X3.4-1968} keeps its two dots and is found by that spelling — while {@code
     * u-t-f8} normalizes to {@code u_t_f8}, which is nobody's module. The refusal quotes the original spelling,
     * not the normalized one, and the same table answers both faces of the call.
     */
    @Test
    void theNameIsNormalizedBeforeTheRegistryIsAsked() {
        assertThat(Encoder.codec("UTF-8")).isEqualTo("utf_8");
        assertThat(Encoder.codec("utf_8")).isEqualTo("utf_8");
        assertThat(Encoder.codec("utf8")).isEqualTo("utf_8");
        assertThat(Encoder.codec("U8")).isEqualTo("utf_8");
        assertThat(Encoder.codec("L1")).isEqualTo("latin_1");
        assertThat(Encoder.codec("8859")).isEqualTo("latin_1");
        assertThat(Encoder.codec("ISO_8859-1")).isEqualTo("latin_1");
        assertThat(Encoder.codec("iso-8859-1")).isEqualTo("latin_1");
        assertThat(Encoder.codec("ANSI_X3.4-1968")).isEqualTo("ascii");
        assertThat(Encoder.codec("us-ascii")).isEqualTo("ascii");
        assertThat(Encoder.codec("cp367")).isEqualTo("ascii");
        assertThat(Encoder.codec("u16")).isEqualTo("utf_16");
        assertThat(Encoder.codec("UTF-16LE")).isEqualTo("utf_16_le");
        assertThat(Encoder.codec("unicodebigunmarked")).isEqualTo("utf_16_be");
        assertThat(Encoder.codec("utf-8-sig")).isEqualTo("utf_8_sig");

        // The alias a name reaches through the table is the same one the other face reaches.
        assertThat(Slots.repr(
                        Slots.call(
                                Slots.getAttr(PyBytes.of(new byte[] {'c', (byte) 0xe9}), "decode"),
                                new PyObject[] {PyStr.of("L1")},
                                null)))
                .isEqualTo("'c\u00e9'");

        for (String refused : new String[] {"u-t-f8", "nope", "", "."}) {
            String name = refused;
            refuses("LookupError: unknown encoding: " + name, () -> Encoder.codec(name));
        }
    }

    /**
     * A limit, a surrogate range, or nothing at all: what each codec refuses to write.
     *
     * <p>ascii stops at {@code 0x80} and latin-1 at {@code 0x100}, so the character on each side of those two
     * boundaries answers differently. utf-8 has no limit — {@code 0x1f600} is a character it writes as four
     * octets — and refuses only the surrogate range. The utf-16 family refuses the same range, because a lone
     * surrogate is half a pair and nothing else, but writes a paired one as the pair itself. Dropping the lone
     * surrogate is where the two halves of the family differ: {@code utf-16} has already written its mark by the
     * time the character fails, so its answer is the mark alone.
     */
    @Test
    void theCodecsRefuseDifferentThings() {
        assertThat(encoded("caf\u007f", "ascii", "strict")).isEqualTo("b'caf\\x7f'");
        assertThat(encoded("caf\u00fe", "latin-1", "strict")).isEqualTo("b'caf\\xfe'");
        assertThat(encoded("caf\u0080", "ascii", "ignore")).isEqualTo("b'caf'");
        assertThat(encoded("caf\u0100", "latin-1", "ignore")).isEqualTo("b'caf'");
        assertThat(encoded("\ud83d\ude00", "utf-8", "strict")).isEqualTo("b'\\xf0\\x9f\\x98\\x80'");
        assertThat(encoded("\ud800", "utf-8", "ignore")).isEqualTo("b''");
        assertThat(encoded("\ud800", "utf-16", "ignore")).isEqualTo("b'\\xff\\xfe'");
        assertThat(encoded("\ud800", "utf-16-be", "ignore")).isEqualTo("b''");
        assertThat(encoded("\ud83d\ude00", "utf-16-be", "strict")).isEqualTo("b'\\xd8=\\xde\\x00'");
    }

    /**
     * The handler's name is settled inside the failure, so a name nobody knows is harmless until it is needed.
     *
     * <p>The same {@code nope} answers {@code b'x'} when nothing failed and {@code LookupError} when something
     * did, on both faces of the call. Case and spaces are kept: the table is keyed by the exact name, so {@code
     * IGNORE} and {@code ' ignore '} are two refusals beside {@code ignore} itself. The encoding is resolved
     * before any of this, so a codec nobody knows is refused even where a handler would have been.
     */
    @Test
    void aHandlerIsNamedOnlyOnceACharacterHasFailed() {
        assertThat(encoded("x", "ascii", "nope")).isEqualTo("b'x'");
        assertThat(bytesOf(PyStr.of("x"), PyStr.of("ascii"), PyStr.of("nope"))).isEqualTo("b'x'");
        refuses("LookupError: unknown error handler name 'nope'", () -> encoded("\u00e9", "ascii", "nope"));
        refuses(
                "LookupError: unknown error handler name 'nope'",
                () -> bytesOf(PyStr.of("\u00e9"), PyStr.of("ascii"), PyStr.of("nope")));
        refuses("LookupError: unknown error handler name 'IGNORE'", () -> encoded("\u00e9", "ascii", "IGNORE"));
        refuses("LookupError: unknown error handler name ' ignore '", () -> encoded("\u00e9", "ascii", " ignore "));
        refuses("LookupError: unknown encoding: nope", () -> encoded("x", "nope", "nope"));
    }

    /**
     * The handlers that answer for a run, each in its own shape.
     *
     * <p>{@code ignore} writes nothing at all, {@code replace} one {@code ?} for every character it dropped, and
     * the two text handlers write <em>text</em> which the codec then encodes again — so on ascii the escape
     * {@code backslashreplace} wrote arrives spelled out as the very octets of its own digits, and a repr of that
     * answer doubles every backslash it holds. On latin-1 the same handler keeps the {@code é} that codec can
     * write as its own octet and escapes only the run beside it. {@code replace} on utf-16 writes its {@code
     * ?} as a whole unit, because there an octet pair is a character.
     */
    @Test
    void eachHandlerWritesItsOwnAnswer() {
        String text = "caf\u00e9 \u65e5\u672c";

        assertThat(encoded(text, "ascii", "ignore")).isEqualTo("b'caf '");
        assertThat(encoded(text, "ascii", "replace")).isEqualTo("b'caf? ??'");
        assertThat(encoded(text, "ascii", "backslashreplace")).isEqualTo("b'caf\\\\xe9 \\\\u65e5\\\\u672c'");
        assertThat(encoded(text, "ascii", "xmlcharrefreplace")).isEqualTo("b'caf&#233; &#26085;&#26412;'");
        assertThat(encoded(text, "latin-1", "ignore")).isEqualTo("b'caf\\xe9 '");
        assertThat(encoded(text, "latin-1", "backslashreplace")).isEqualTo("b'caf\\xe9 \\\\u65e5\\\\u672c'");
        assertThat(encoded("\u65e5\u672c\ud83d\ude00", "ascii", "replace")).isEqualTo("b'???'");
        assertThat(encoded("\u65e5\u672c", "ascii", "xmlcharrefreplace")).isEqualTo("b'&#26085;&#26412;'");
        assertThat(encoded("a\ud800b", "utf-16", "replace")).isEqualTo("b'\\xff\\xfea\\x00?\\x00b\\x00'");
        assertThat(encoded("a\ud800b", "utf-16", "ignore")).isEqualTo("b'\\xff\\xfea\\x00b\\x00'");
    }

    /**
     * The run a collecting codec names, and the one character the other family names.
     *
     * <p>{@code position 5-6} is a pair of code points rather than of units, and the walk stops at the first
     * character the codec <em>can</em> write: {@code café 日本} has two runs and the sentence names the second.
     * utf-16-be refuses one at a time, so its neighbour answers with a single position.
     */
    @Test
    void aCollectingCodecRefusesARunAndTheUtf16FamilyOneCharacter() {
        refuses(
                "UnicodeEncodeError: 'ascii' codec can't encode character '\\xe9' in position 3:"
                        + " ordinal not in range(128)",
                () -> encoded("caf\u00e9 \u65e5\u672c", "ascii", "strict"));
        refuses(
                "UnicodeEncodeError: 'latin-1' codec can't encode characters in position 5-6:"
                        + " ordinal not in range(256)",
                () -> encoded("caf\u00e9 \u65e5\u672c", "latin-1", "strict"));
        refuses(
                "UnicodeEncodeError: 'utf-8' codec can't encode characters in position 1-2:"
                        + " surrogates not allowed",
                () -> encoded("a\udc80\udc81b", "utf-8", "strict"));
        refuses(
                "UnicodeEncodeError: 'utf-8' codec can't encode character '\\udc80' in position 1:"
                        + " surrogates not allowed",
                () -> encoded("a\udc80b", "utf-8", "strict"));
        refuses(
                "UnicodeEncodeError: 'utf-16-be' codec can't encode character '\\ud800' in position 1:"
                        + " surrogates not allowed",
                () -> encoded("a\ud800b", "utf-16-be", "strict"));
        assertThat(encoded("a\ud800b", "utf-16-be", "backslashreplace"))
                .isEqualTo("b'\\x00a\\x00\\\\\\x00u\\x00d\\x008\\x000\\x000\\x00b'");
    }

    /**
     * {@code surrogateescape} turns the octet a file name carried back into that octet, and only those.
     *
     * <p>The window is {@code \udc80} to {@code \udcff}, the range reserved for the round trip, and a character
     * outside it is re-raised from exactly where escaping stopped — which is why the run of two followed by an
     * {@code \xe9} names position 3 and a {@code \udc7f} beside a {@code \udc80} names position 1. On utf-8 the
     * handler has nothing to refuse beside it, so an escaped octet and an ordinary encoding appear in one answer.
     */
    @Test
    void surrogateescapeEscapesItsOwnWindowAndReRaisesTheRest() {
        assertThat(encoded("a\udc80\udcffb", "utf-8", "surrogateescape")).isEqualTo("b'a\\x80\\xffb'");
        assertThat(encoded("a\udc80\u00e9b", "utf-8", "surrogateescape")).isEqualTo("b'a\\x80\\xc3\\xa9b'");
        assertThat(bytesOf(PyStr.of("a\udc80b"), PyStr.of("utf-8"), PyStr.of("surrogateescape")))
                .isEqualTo("b'a\\x80b'");
        refuses(
                "UnicodeEncodeError: 'ascii' codec can't encode character '\\udc7f' in position 0:"
                        + " ordinal not in range(128)",
                () -> encoded("\udc7f", "ascii", "surrogateescape"));
        refuses(
                "UnicodeEncodeError: 'ascii' codec can't encode character '\\xe9' in position 3:"
                        + " ordinal not in range(128)",
                () -> encoded("a\udc80\udc81\u00e9b", "ascii", "surrogateescape"));
        refuses(
                "UnicodeEncodeError: 'ascii' codec can't encode character '\\udc7f' in position 1:"
                        + " ordinal not in range(128)",
                () -> encoded("\udc80\udc7f", "ascii", "surrogateescape"));
    }

    /**
     * {@code surrogatepass} writes a surrogate as itself, and refuses everywhere else.
     *
     * <p>utf-8 accepts the surrogate as its own three-byte form, which is a well-formed encoding of a character
     * nothing will decode back; utf-16 writes the unit as it stands, little-endian, beside the byte-order mark
     * that was already there. The codecs with a limit refuse it the way they refuse everything else, because a
     * handler only re-writes what the codec could not.
     */
    @Test
    void surrogatepassWritesTheSurrogateItselfWhereTheCodecCanCarryIt() {
        assertThat(encoded("a\udc80\udc81b", "utf-8", "surrogatepass"))
                .isEqualTo("b'a\\xed\\xb2\\x80\\xed\\xb2\\x81b'");
        assertThat(encoded("a\udc80b", "utf-16", "surrogatepass")).isEqualTo("b'\\xff\\xfea\\x00\\x80\\xdcb\\x00'");
        assertThat(encoded("\ud800", "utf-8-sig", "surrogatepass")).isEqualTo("b'\\xef\\xbb\\xbf\\xed\\xa0\\x80'");
        assertThat(bytesOf(PyStr.of("a\udc80b"), PyStr.of("utf-16"), PyStr.of("surrogatepass")))
                .isEqualTo("b'\\xff\\xfea\\x00\\x80\\xdcb\\x00'");
        refuses(
                "UnicodeEncodeError: 'ascii' codec can't encode character '\\udc80' in position 0:"
                        + " ordinal not in range(128)",
                () -> encoded("\udc80", "ascii", "surrogatepass"));
        refuses(
                "UnicodeEncodeError: 'latin-1' codec can't encode character '\\udc80' in position 0:"
                        + " ordinal not in range(256)",
                () -> encoded("\udc80", "latin-1", "surrogatepass"));
    }

    /**
     * The signature a codec writes before any character, which no handler can talk it out of.
     *
     * <p>{@code utf-16} opens with the little-endian mark and {@code utf-8-sig} with its three bytes even when
     * the text is empty, so the empty answer is not empty; the explicit-endian codecs mark nothing and the empty
     * answer is the empty bytes. {@code ignore} that drops every character of a text still leaves the mark.
     */
    @Test
    void thePrefixIsWrittenWhateverTheTextDoes() {
        assertThat(encoded("", "utf-16", "strict")).isEqualTo("b'\\xff\\xfe'");
        assertThat(encoded("", "utf-8-sig", "strict")).isEqualTo("b'\\xef\\xbb\\xbf'");
        assertThat(encoded("", "utf-16-be", "strict")).isEqualTo("b''");
        assertThat(encoded("", "utf-8", "strict")).isEqualTo("b''");
        assertThat(encoded("a\udc80b", "utf-16", "ignore")).isEqualTo("b'\\xff\\xfea\\x00b\\x00'");
        assertThat(encoded("\ud800", "utf-8-sig", "ignore")).isEqualTo("b'\\xef\\xbb\\xbf'");
    }

    /**
     * The escape table {@code ascii} reads is the one {@code backslashreplace} writes.
     *
     * <p>{@code PyObject_ASCII} asks for the repr, encodes that text through this handler and reads the octets
     * back as ASCII, so the widths, the lowercase digits and the characters handed on as they stand are one
     * table rather than two. A character the repr had escaped already is ASCII by the time this reaches it, and
     * is not escaped a second time.
     */
    @Test
    void theEscapeTableIsSharedWithAscii() {
        for (String text :
                new String[] {"caf\u00e9", "\u65e5", "\ud83d\ude00", "\t", "\u00a0", "\u007f", "\\xe9"}) {
            String repr = Slots.repr(PyStr.of(text));
            String throughTheCodec =
                    new String(
                            Encoder.encode(repr, "ascii", "backslashreplace").getValue(),
                            StandardCharsets.US_ASCII);

            assertThat(Encoder.asciiEscaped(repr)).as("%s through both doors", repr).isEqualTo(throughTheCodec);
        }

        assertThat(Encoder.asciiEscaped(Slots.repr(PyStr.of("caf\u00e9")))).isEqualTo("'caf\\xe9'");
        assertThat(Encoder.asciiEscaped(Slots.repr(PyStr.of("caf\\xe9")))).isEqualTo("'caf\\\\xe9'");
        assertThat(encoded("caf\\xe9", "ascii", "backslashreplace")).isEqualTo("b'caf\\\\xe9'");
    }

    /**
     * The two converters of {@code encode} name their own parameter, and {@code None} is {@code None}.
     *
     * <p>{@code _PyArg_BadArgument} (Python/getargs.c:622) formats the value's class with one exception written
     * into it: {@code None} is named as that word rather than as a type. Nothing else is asked of the value — a
     * {@code Marker} with a {@code __str__} that answers {@code utf-8} is refused by its class name — and an
     * embedded null is refused by the converter as {@code ValueError}, before the registry has the name.
     */
    @Test
    void theConvertersRefuseInTheirOwnWords() {
        refuses(
                "TypeError: encode() argument 'encoding' must be str, not None",
                () -> encode(PyNone.INSTANCE));
        refuses(
                "TypeError: encode() argument 'errors' must be str, not None",
                () -> encode(PyStr.of("ascii"), PyNone.INSTANCE));
        refuses(
                "TypeError: encode() argument 'errors' must be str, not int",
                () -> encode(PyStr.of("ascii"), PyInt.of(7)));
        refuses(
                "TypeError: encode() argument 'encoding' must be str, not bytes",
                () -> encode(PyBytes.of(new byte[] {'a'})));
        refuses(
                "ValueError: embedded null character",
                () -> encode(PyStr.of("as\u0000ci")));
        refuses(
                "ValueError: embedded null character",
                () -> encode(PyStr.of("ascii"), PyStr.of("ign\u0000re")));

        assertThat(encode(PyStr.of("ascii"), PyStr.of("ignore"))).isEqualTo("b'caf'");
        assertThat(encode()).isEqualTo("b'caf\\xc3\\xa9'");
    }

    /**
     * {@code bytes} asks the same two converters and a different first question.
     *
     * <p>{@code bytes_new_impl} (Objects/bytesobject.c:2767) branches on whether the first argument is text
     * before it asks anything else, so the three sentences of the pairing are about the <em>pair</em>: a count
     * with an encoding is {@code encoding without a string argument}, text with only an errors name is {@code
     * string argument without an encoding}, and an encoding named with no argument at all is the first again.
     * Once text, encoding and handler are all there the call is {@code str.encode} under another name, keywords
     * and all.
     */
    @Test
    void theBytesConstructorPairsItsArgumentsBeforeEncoding() {
        assertThat(bytesOf(PyStr.of("caf\u00e9"), PyStr.of("ascii"), PyStr.of("ignore"))).isEqualTo("b'caf'");
        assertThat(
                        bytesNamed(
                                new String[] {"encoding", "errors"},
                                PyStr.of("caf\u00e9"),
                                PyStr.of("ascii"),
                                PyStr.of("replace")))
                .isEqualTo("b'caf?'");

        refuses(
                "TypeError: encoding without a string argument",
                () -> bytesOf(PyInt.of(5), PyStr.of("ascii")));
        refuses(
                "TypeError: encoding without a string argument",
                () -> bytesNamed(new String[] {"encoding"}, PyStr.of("ascii")));
        refuses(
                "TypeError: string argument without an encoding",
                () -> bytesNamed(new String[] {"errors"}, PyStr.of("abc"), PyStr.of("ignore")));
        refuses(
                "TypeError: bytes() argument 'encoding' must be str, not None",
                () -> bytesOf(PyStr.of("x"), PyNone.INSTANCE));
        refuses(
                "TypeError: bytes() argument 'errors' must be str, not int",
                () -> bytesOf(PyStr.of("caf\u00e9"), PyStr.of("ascii"), PyInt.of(7)));
        refuses(
                "ValueError: embedded null character",
                () -> bytesOf(PyStr.of("x"), PyStr.of("as\u0000ci")));
        refuses(
                "TypeError: bytes() got an unexpected keyword argument 'enc'",
                () -> bytesNamed(new String[] {"enc"}, PyStr.of("x")));
        refuses(
                "TypeError: bytes() takes at most 3 arguments (4 given)",
                () -> bytesOf(PyStr.of("x"), PyStr.of("ascii"), PyStr.of("strict"), PyInt.ONE));
    }

    /** The method's own signature: two arguments, positional or named, and no third. */
    @Test
    void theEncodeRowKeepsItsCallingConvention() {
        PyObject row = Slots.getAttr(PyStr.of("\u00e9"), "encode");

        assertThat(Slots.repr(Slots.call(row, new PyObject[0], null))).isEqualTo("b'\\xc3\\xa9'");
        assertThat(Slots.repr(
                        Slots.call(
                                row,
                                new PyObject[] {PyStr.of("ignore")},
                                PyBoundMethod.keywordTuple(new String[] {"errors"}))))
                .isEqualTo("b'\\xc3\\xa9'");
        assertThat(
                        Slots.repr(
                                Slots.call(
                                        row,
                                        new PyObject[] {PyStr.of("ascii"), PyStr.of("ignore")},
                                        PyBoundMethod.keywordTuple(new String[] {"encoding", "errors"}))))
                .isEqualTo("b''");
        refuses(
                "TypeError: encode() takes at most 2 arguments (3 given)",
                () -> Slots.call(
                        row,
                        new PyObject[] {PyStr.of("ascii"), PyStr.of("ignore"), PyStr.of("x")},
                        null));
        refuses(
                "TypeError: encode() got an unexpected keyword argument 'enc'",
                () -> Slots.call(
                        row, new PyObject[] {PyStr.of("ascii")}, PyBoundMethod.keywordTuple(new String[] {"enc"})));
    }
}
