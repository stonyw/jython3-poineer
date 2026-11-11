package org.python.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.python.util.Jy3Process.Result;

/**
 * M1's done criterion, measured rather than declared: the same source, run by CPython 3.14 and by
 * {@code jy3}, answers the same way.
 *
 * <p>Every comparison here is against the reference interpreter that also compiled the source, so a
 * divergence is either the runtime's or the reference's, never a stale expectation somebody wrote
 * down in 2026. That is the whole value of a differential test and the reason these run the launcher
 * as a process: {@code print} reaches the OS descriptor, which is what a user sees.
 *
 * <p>One case compares less than the whole stream on purpose: a usage error is matched on its exit status
 * and on the tail of its message, because the program's own name and the working directory are both in
 * the sentence. Everything else is compared byte for byte, the report an uncaught exception leaves on
 * stderr included — that report is M3 slice 9's, and it is matched against the reference asked to leave
 * its anchors out.
 */
class Jy3LauncherTest {

    @TempDir
    Path work;

    @Test
    void helloWorldPrintsExactlyWhatCpythonPrints() throws Exception {
        Path script = write("hello.py", resource("m1/hello.py"));

        Result reference = cpython(script);
        Result jy3 = jy3(script);

        assertThat(jy3.out()).isEqualTo(reference.out());
        assertThat(jy3.out()).contains("hello world");
        assertThat(jy3.exit()).isEqualTo(reference.exit());
        assertThat(jy3.err()).isEmpty();
    }

    @Test
    void arithmeticAndControlFlowAgreeOverARangeOfValues() throws Exception {
        Path script = write("numbers.py", """
                for n in range(1, 40):
                    print(n, n * n, 2 ** n, n // 7, n % 7, -n, abs(-n))
                total = 0
                i = 0
                while i < 1000:
                    total += i
                    i += 1
                print("while:", total)
                print("big:", 2 ** 200 + 1, (10 ** 30) // 7, (10 ** 30) % 7)
                print("floats:", 1 / 3, 7 / 2, -7 / 2, 7 // 2, -7 // 2, 2.5 ** 0.5)
                print("bools:", True + True, False == 0, 1 < 2 <= 2 < 3, not 0)
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    @Test
    void functionCallsAgreeOnReturnValuesAndArguments() throws Exception {
        Path script = write("calls.py", """
                def varargs(a, *rest, **keywords):
                    return (a, rest, tuple(sorted(keywords)))

                def defaults(x, y=2, *, z=3):
                    return x - y * z

                def fib(n):
                    return n if n < 2 else fib(n - 1) + fib(n - 2)

                def make(n):
                    def add(other):
                        return other + n
                    return add

                print(varargs(1, 2, 3, key="v", other=9))
                print(defaults(100), defaults(100, 5), defaults(100, y=5, z=1))
                print(fib(15))
                print(make(3)(4), make(0)(0))
                packed = [1, 2]
                print(defaults(*packed), varargs(*[1], **{"k": 2}))
                print(list(map(abs, [-1, 2, -3])), list(filter(lambda v: v % 2, range(8))))
                print(sorted([defaults(i) for i in range(3)]))
                print("a", "b", sep="|", end="!\\n")
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    @Test
    void stringsAndContainersPrintAsCPythonPrintsThem() throws Exception {
        Path script = write("containers.py", """
                text = "Hello, World"
                print(text.upper(), text.split(", ")[1], "%-10s|" % text[:5], f"{len(text)=}")
                print("-".join(text.split()))
                listing = [1, [2, {"three": ("four", b"bytes")}]]
                print(listing, listing[1][0], listing[1][1], listing[1][2][::-1][:1])
                del listing[0]
                print(listing, len(listing), 1 in listing, {3, 1, 2}, {"a": 1} | {"b": 2})
                print(tuple(sorted("banana", key=str.lower)), list(enumerate("abc", 3)))
                print({x: x * x for x in range(4)}, sorted({x for x in "aabcc"}))
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    @Test
    void rangeArithmeticAgreesAtTheBoundaries() throws Exception {
        Path script = write("ranges.py", """
                for spec in [(0,), (1,), (5, 5), (3, 10), (10, 0, -2), (0, 10, 3), (1, 10, 3),
                             (-5, 5, 2), (10, 10, 2), (0, -3, -1), (2 ** 62, 2 ** 62 + 3),
                             (0, 2 ** 70, 2 ** 69)]:
                    r = range(*spec)
                    print(spec, len(r), r[0] if len(r) else "-", r[-1] if len(r) else "-",
                          list(r)[:4], 7 in r, -1 in r, r == range(*spec), str(r))
                """);

        assertThat(jy3(script).out()).isEqualTo(cpython(script).out());
    }

    /**
     * The language surface M1 claims, one file per area, under {@code src/test/resources/m1/corpus}.
     *
     * <p>Each case is a script rather than a Java assertion so that growing M1 means adding a file,
     * not inventing an expectation. Nothing here needs a class body or a generator: {@code class} and a
     * bare {@code (x for x in y)} belong to later M3 slices, and so does {@code try}, whose catching is
     * measured by the corpus below. {@code int.to_bytes} is a further slice still.
     */
    private static final List<String> CORPUS =
            List.of(
                    "01_int_arithmetic.py",
                    "02_float_arithmetic.py",
                    "03_comparison.py",
                    "04_control_flow.py",
                    "05_functions.py",
                    "06_closures.py",
                    "07_strings.py",
                    "08_containers.py",
                    "09_slices_and_range.py",
                    "10_builtins_and_sys.py");

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void aCorpusScriptAnswersExactlyAsCpythonAnswers(String scriptName) throws Exception {
        answersExactlyAsCpython("m1/corpus", scriptName);
    }

    private static List<String> corpus() {
        return CORPUS;
    }

    /**
     * M3's language surface, one file per area, under {@code src/test/resources/m3/corpus}.
     *
     * <p>The same rule as M1's corpus: no expectation is written down, the reference interpreter that
     * compiled the script also answers it. Each script here ends on its own two feet; the ones that end on
     * an escaped exception live beside them in {@code m3/uncaught}, where the report is the thing being
     * compared. Nothing here prints a value whose {@code repr} carries an address, which is the one thing
     * the two interpreters can never agree on.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("m3Corpus")
    void anM3CorpusScriptAnswersExactlyAsCpythonAnswers(String scriptName) throws Exception {
        answersExactlyAsCpython("m3/corpus", scriptName);
    }

    private static List<String> m3Corpus() {
        return List.of(
                "01_handlers.py",
                "02_finally_and_reraise.py",
                "03_chaining.py",
                "04_exc_info.py",
                "05_classes.py",
                "06_inheritance.py",
                "07_dunders.py",
                "08_inherited_members.py",
                "09_descriptors.py",
                "10_super.py",
                "11_super_attribute_form.py",
                "12_metaclass.py",
                "13_class_hooks.py",
                "14_with.py",
                "15_exceptions.py",
                "16_traceback.py",
                "17_generators.py",
                "18_generator_send.py",
                "19_generator_throw_close.py",
                "20_generator_exc_state.py",
                "21_except_star.py",
                "22_group_construction.py",
                "23_group_methods.py",
                "24_group_names.py",
                "25_match_mapping.py",
                "26_match_sequence.py",
                "27_match_class.py",
                "28_yield_from_delegation.py",
                "29_yield_from_values.py",
                "30_yield_from_protocols.py",
                "31_class_body_cells.py",
                "32_empty_slots.py",
                "33_error_names.py",
                "34_namespace_builtins.py",
                "35_dir_protocol.py",
                "36_sort_ordering.py",
                "37_code_positions.py",
                "38_class_attribute.py",
                "39_add_note.py",
                "40_default_hook_answers.py",
                "41_hook_replaced_and_removed.py",
                "42_version_info_named.py",
                "43_structseq_members.py",
                "44_bytes_search.py",
                "45_bytes_cut.py",
                "46_range_and_size.py",
                "47_bytes_padding.py",
                "48_bytes_case.py",
                "49_bytes_hex.py",
                "50_bytes_translate.py",
                "51_int_to_bytes.py",
                "52_int_from_bytes.py",
                "53_exception_names.py",
                "54_oserror_fields.py",
                "55_type_namespace.py",
                "56_descriptor_kinds.py",
                "57_class_attribute_writes.py",
                "58_metaclass_side_write.py",
                "59_bytes_dictionary.py",
                "60_str_dictionary.py",
                "61_call_slot.py",
                "62_list_dictionary.py",
                "63_tuple_dictionary.py",
                "64_dict_dictionary.py",
                "65_set_dictionary.py",
                "66_frozenset_dictionary.py",
                "67_int_dictionary.py",
                "68_bool_dictionary.py",
                "69_float_dictionary.py",
                "70_float_slots.py",
                "71_float_methods.py",
                "72_exception_dictionary.py",
                "73_oserror_dictionary.py",
                "74_attributeerror_dictionary.py",
                "75_nameerror_dictionary.py",
                "76_stopiteration_dictionary.py",
                "77_systemexit_dictionary.py",
                "78_importerror_dictionary.py",
                "79_syntaxerror_dictionary.py",
                "80_syntaxerror_fields.py",
                "81_syntaxerror_display.py",
                "82_unicode_dictionary.py",
                "83_unicode_initialisers.py",
                "84_codec_sentence.py",
                "85_group_dictionary.py",
                "86_group_fields.py",
                "87_all_any.py",
                "88_inlined_walk.py",
                "89_function_rows.py",
                "90_builtin_docs.py",
                "91_bytes_docs.py",
                "92_typedict_tables.py",
                "93_str_tables.py",
                "94_set_tables.py",
                "95_list_tuple_tables.py",
                "96_dict_tables.py",
                "97_int_tables.py",
                "98_float_tables.py",
                "99_invented_tables.py",
                "100_descr_tables.py",
                "101_wrapper_tables.py",
                "102_property_table.py",
                "103_method_wrapper_table.py",
                "104_bound_method_table.py",
                "105_complex_table.py",
                "106_dict_view_tables.py",
                "107_not_implemented.py",
                "108_index_protocol.py",
                "109_conversion_slots.py",
                "110_slice_rows.py",
                "111_range_rows.py",
                "112_range_iterators.py",
                "113_descriptor_get.py",
                "114_sequence_iterators.py",
                "115_hash_iterators.py",
                "116_reversed_cursor.py",
                "117_cursor_builtin_types.py",
                "118_constructor_tables.py",
                "119_exception_sentences.py",
                "120_singleton_books.py",
                "121_super_book.py",
                "122_getset_write_half.py",
                "123_code_storage_half.py",
                "124_code_slot_rows.py",
                "125_code_replace.py",
                "126_code_branches.py",
                "127_code_new.py",
                "128_cursor_books.py",
                "129_displayhook_and_sentences.py",
                "130_print_stream_and_pieces.py",
                "131_ascii_repr_rewritten.py",
                "132_input_and_the_line_it_reads.py",
                "133_encode_handlers_and_the_runs_they_answer_for.py",
                "134_decode_handlers_and_the_octets_they_answer_for.py",
                "137_the_four_books_under_every_stream.py",
                "138_textiowrappers_own_book.py",
                "155_length_hint.py",
                "156_bytearray.py",
                "157_bytearray_capacity.py",
                "158_bytearray_readonly.py",
                "159_bytearray_final.py",
                "160_memoryview_first.py", "161_memoryview_slice.py",
                "162_memoryview_compare_iter.py", "163_memoryview_release_context.py",
                "164_memoryview_bytes_list.py", "165_memoryview_cast_buffer.py",
                "166_memoryview_readonly_methods.py",
                "167_utf32_charmap_and_unicode_escape.py",
                "168_namereplace_and_the_name_table.py");
    }

    /**
     * M3 slices 85, 86, 89, 90 and 91's stream face: the scripts whose questions are asked of
     * {@code sys.stdin} or answered on {@code sys.stdout}, so both children get the same pipe and the same
     * closed descriptor at the end of it.
     *
     * <p>The pipe is the case, not an accident of plumbing. Asked of a regular file, {@code seek} and
     * {@code tell} answer with a position and this runtime has no raw layer to ask; asked of a pipe both
     * sides refuse in the same words, so the refusals themselves are what is compared — as are the
     * characters a read answers when the octets under them stop halfway through a line, and the number of
     * octets a {@code read} of the descriptor hands back when the reader above it wanted more.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("m3PipeCorpus")
    void anM3CorpusScriptReadsItsPipeExactlyAsCpythonReadsIt(
            String scriptName, byte[] pipe) throws Exception {
        Path script = write(scriptName, resource("m3/corpus/" + scriptName));

        Result reference =
                Jy3Process.runWithOctets(
                        work, cpythonCommand(), List.of(script.toString()), pipe);
        Result jy3 =
                Jy3Process.runWithOctets(
                        work, Jy3Process.jy3Command(), List.of(script.toString()), pipe);

        assertThat(jy3.exit()).as("%s exit", scriptName).isEqualTo(reference.exit());
        assertThat(jy3.out()).as("%s stdout", scriptName).isEqualTo(reference.out());
        assertThat(jy3.err()).as("%s stderr", scriptName).isEqualTo(reference.err());
    }

    private static List<Arguments> m3PipeCorpus() {
        return List.of(
                Arguments.of("135_stream_reads_and_the_position_refusals.py", STDIN_OCTETS),
                Arguments.of("136_the_two_layers_under_a_reading_stream.py", LAYERS_OCTETS),
                Arguments.of("139_reconfigure_newlines_translate_and_the_guard.py", TRANSLATE_OCTETS),
                Arguments.of("140_reconfigure_newlines_keep_and_the_reads_around_them.py", KEEP_OCTETS),
                Arguments.of("141_reconfigure_crlf_and_the_last_character.py", CRLF_OCTETS),
                Arguments.of("142_reconfigure_cr_and_the_lines_it_cuts.py", CR_OCTETS),
                Arguments.of("143_reconfigure_encoding_rebuilds_the_decoder.py", LATIN1_OCTETS),
                Arguments.of("144_reconfigure_utf8_sig_reads_the_mark_once.py", SIG_OCTETS),
                Arguments.of("145_reconfigure_utf16_reads_its_mark_and_holds_a_pair.py", UTF16_OCTETS),
                Arguments.of("146_reconfigure_utf16_le_holds_an_odd_octet.py", UTF16LE_OCTETS),
                Arguments.of("147_reconfigure_names_refused_and_accepted.py", NAMES_OCTETS),
                Arguments.of("148_reconfigure_encoding_writes_with_the_new_codec.py", NO_OCTETS),
                Arguments.of("149_reconfigure_utf16_writes_native_order_without_a_mark.py", NO_OCTETS),
                Arguments.of("150_reconfigure_write_names_refused_and_accepted.py", NO_OCTETS),
                Arguments.of("151_textio_init_rebuilds_the_layer.py", LATIN1_OCTETS),
                Arguments.of("152_textio_init_refusals_before_the_clear.py", LATIN1_OCTETS),
                Arguments.of("153_textio_init_refusals_after_the_clear.py", LATIN1_OCTETS),
                Arguments.of("154_textio_init_calling_convention.py", LATIN1_OCTETS));
    }

    /**
     * The pipe case 135 reads: ten whole lines, and then the end of the descriptor.
     *
     * <p>The six {@code '\r'} characters inside are ordinary text — five of them sit before a line's own
     * terminator and one ends {@code three} in the middle of a line — because the stream was opened with
     * {@code newline='\n'} and translates nothing, and the octet counts differ from the character counts
     * on purpose: a snake is four octets and a backslash-escaped accent two, so the reads that the script
     * makes in four-octet chunks answer in units the chunks cannot cut.
     *
     * <p>It stops on a clean end rather than on a misencoded tail because {@code errors} is {@code strict}:
     * a broken octet raises {@code UnicodeDecodeError}, whose report carries a {@code <frozen codecs>}
     * frame and PEP 657 anchors this runtime does not draw yet, so a misencoded tail would compare the
     * report rather than the reads.
     */
    private static final byte[] STDIN_OCTETS =
            octets(
                    "one\r\n",
                    "two\r\n",
                    "three\rcaf\u00e9\r\n",
                    "\ud83d\udc0dx\n",
                    "abc\ud83d\udc0d\ud83d\udc0ddef\n",
                    "six\r\n",
                    "seven\n",
                    "eight\n",
                    "nine\r\n",
                    "tail\r\n");

    /**
     * The pipe case 136 reads: eight short lines, 41 octets, and no terminator on the last one.
     *
     * <p>Nothing here is astral or misencoded — the text layer reads plain ASCII — because this case is
     * about how the octets are divided rather than what they decode to. The sizes are chosen so that a
     * read of the store and a read of the descriptor answer different numbers: the first {@code read1(4)}
     * asks the descriptor for four octets and answers {@code b'one\r'}, so the {@code raw.read(4)} under
     * it is the *next* four and answers {@code b'\ntwo'}, and the {@code peek(1)} below those reaches the
     * end of the pipe in one trip and answers the 33 octets left. The line with no terminator is what a
     * {@code readall} of a dry descriptor, and an {@code iter} that has nothing to hand back, are asked to
     * agree about.
     */
    private static final byte[] LAYERS_OCTETS =
            octets(
                    "one\r\n",
                    "two\r\n",
                    "three\n",
                    "four\n",
                    "five\n",
                    "six\n",
                    "seven\n",
                    "eight");

    /**
     * The pipe case 139 reads: three endings inside seventeen octets, the last of them a '\r' with nothing
     * after it.
     *
     * <p>The stream is read in three-octet chunks, so the first {@code '\r\n'} is cut in two and the pair
     * only appears once the second chunk is in front of the held character, and the middle {@code '\r\r\n'}
     * is two endings in a row. The trailing '\r' is the one with no next character at all: at the end of the
     * descriptor it is a lone ending rather than half of a pair.
     */
    private static final byte[] TRANSLATE_OCTETS = octets("ab\r\ncd\r\r\nlast\r");

    /**
     * The pipe case 140 reads: the same shorthand without a trailing return, at a two-octet chunk size.
     *
     * <p>The counting reads cross both a chunk boundary and a held '\r', which is where a count in
     * characters and a count in octets part company: the held character is not a character of the answer
     * until the chunk after it says which ending it belongs to.
     */
    private static final byte[] KEEP_OCTETS = octets("a\rb\r\nc\nd");

    /**
     * The pipe case 141 reads: a pair, a lone return before a pair, and a lone return at the end.
     *
     * <p>Only {@code '\r\n'} ends a line here, so the first '\r' of {@code '\r\r\n'} is text and the
     * trailing '\r' of the pipe is text too — the end of the descriptor leaves nothing to pair it with.
     */
    private static final byte[] CRLF_OCTETS = octets("ab\r\ncd\r\r\nef\r");

    /**
     * The pipe case 142 reads: one ending of each kind, at a two-octet chunk size.
     *
     * <p>With {@code '\r'} the only ending, the second line both begins with the '\n' of a pair and ends on
     * a '\r' of its own, which is the shape that tells a single-character mode apart from the pair.
     */
    private static final byte[] CR_OCTETS = octets("a\rb\r\nc\n");

    /**
     * The pipe case 143 reads: seven octets, the fourth of them above ASCII, under {@code latin-1}.
     *
     * <p>{@code 0xe9} is an error to the UTF-8 the stream opens as and a character to the codec it is moved
     * to, which is the whole difference the case is about: the same octets, read before and after the
     * {@code reconfigure}, cannot answer the same way.
     */
    private static final byte[] LATIN1_OCTETS =
            octets("caf", new byte[] {(byte) 0xe9}, "\n");

    /**
     * The pipe case 144 reads: a mark, a letter, a second mark, and one more letter before the ending.
     *
     * <p>The chunk size is two, so the opening mark arrives as {@code b'\xef\xbb'} and {@code b'\xbfa'} and
     * has to be held across the boundary before it can be recognised; the second mark begins a chunk whole
     * and is a character, because the stream's mark is only the one at its start.
     */
    private static final byte[] SIG_OCTETS =
            octets(
                    new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                    "a",
                    new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                    "b\n");

    /**
     * The pipe case 145 reads: a little-endian mark, {@code 'ok'}, an astral character, and an ending.
     *
     * <p>Two octets a chunk puts the high surrogate of the astral character in a chunk of its own, which is
     * what makes the segment hold it back rather than hand the handler an unfinished pair.
     */
    private static final byte[] UTF16_OCTETS =
            octets(
                    new byte[] {(byte) 0xff, (byte) 0xfe},
                    new byte[] {0x6f, 0x00, 0x6b, 0x00},
                    new byte[] {0x3d, (byte) 0xd8, 0x00, (byte) 0xde},
                    new byte[] {0x0a, 0x00});

    /**
     * The pipe case 146 reads: the same text without a mark, in three-octet chunks, so every boundary falls
     * inside a unit or inside the pair.
     *
     * <p>Ten octets, and the codec named outright — {@code utf-16-le} decides no order and reads no mark,
     * so the only thing the decoder has to hold is what the chunk cut in half.
     */
    private static final byte[] UTF16LE_OCTETS =
            octets(
                    new byte[] {0x68, 0x00, 0x69, 0x00},
                    new byte[] {0x3d, (byte) 0xd8, 0x00, (byte) 0xde},
                    new byte[] {0x0a, 0x00});

    /**
     * The pipe case 147 reads: two octets of ASCII around one that {@code ascii} refuses.
     *
     * <p>Nothing is read until the last {@code reconfigure}, so every name the script hands over is handed
     * over before the lock; the {@code 0x80} is what finally decides whether the handler that was stored
     * without a lookup is the one the decoder walks.
     */
    private static final byte[] NAMES_OCTETS =
            octets("a", new byte[] {(byte) 0x80}, "b\n");

    /**
     * The pipe cases 148 to 150 read: nothing at all.
     *
     * <p>Those three are about the other direction. What they compare is the octets the script's writes put
     * on {@code sys.stdout}, and a descriptor with nothing on it is what makes the reading half of the
     * stream stay out of the way.
     */
    private static final byte[] NO_OCTETS = new byte[0];

    /** UTF-8 text and raw octets, end to end, which is what a pipe carries. */
    private static byte[] octets(Object... parts) {
        ByteArrayOutputStream pipe = new ByteArrayOutputStream();
        for (Object part : parts) {
            if (part instanceof String text) {
                pipe.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            } else {
                pipe.writeBytes((byte[]) part);
            }
        }
        return pipe.toByteArray();
    }

    private void answersExactlyAsCpython(String folder, String scriptName) throws Exception {
        answersExactlyAsCpython(folder, scriptName, cpythonCommand());
    }

    private void answersExactlyAsCpython(String folder, String scriptName, List<String> command)
            throws Exception {
        Path script = write(scriptName, resource(folder + "/" + scriptName));

        Result reference = Jy3Process.run(work, command, List.of(script.toString()), null);
        Result jy3 = jy3(script);

        assertThat(jy3.exit()).as("%s exit", scriptName).isEqualTo(reference.exit());
        assertThat(jy3.out()).as("%s stdout", scriptName).isEqualTo(reference.out());
        assertThat(jy3.err()).as("%s stderr", scriptName).isEqualTo(reference.err());
    }

    /**
     * M3 slice 9's report: an exception nobody caught, printed the way CPython prints it.
     *
     * <p>The reference runs with {@code -X no_debug_ranges}, and that is not a convenience. CPython's
     * default report puts an anchor line under each frame — the {@code ~~~^~~~} of PEP 657, built from
     * the column ranges this runtime does not carry yet — and switching the ranges off makes the
     * interpreter answer with the report its own C printer writes, which is the one {@code jy3}
     * transcribes. The missing anchor is the named difference of this slice and is recorded in PLAN.md.
     *
     * <p>Unlike the M3 corpus, every script here ends on an uncaught exception: the report is what is
     * being compared. Stdout is compared alongside it because a script catches and prints before it
     * fails, and the absolute path each frame names is the temp directory's own spelling of itself, so
     * these cases cover that too.
     *
     * <p>From slice 20 the folder also answers who writes that text. A program may replace
     * {@code sys.excepthook}, make it fail, make it exit, or take the name away, and each of those is a
     * different document with a different status beside it — including an interrupt, which leaves at 130
     * whether the program raised it or only the hook did.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("uncaughtReports")
    void anUncaughtExceptionIsReportedAsCpythonReportsIt(String scriptName) throws Exception {
        answersExactlyAsCpython("m3/uncaught", scriptName, cpythonWithoutAnchorsCommand());
    }

    private static List<String> uncaughtReports() {
        return List.of(
                "01_frames.py",
                "02_cause.py",
                "03_handled_then_raise.py",
                "04_context.py",
                "05_operation_in_handler.py",
                "06_from_none.py",
                "07_limit.py",
                "08_repeated_line.py",
                "09_notes.py",
                "10_notes_tuple.py",
                "11_notes_string.py",
                "12_empty_message.py",
                "13_str_fails.py",
                "14_type_names.py",
                "15_deep_chain.py",
                "16_context_of_cause.py",
                "17_zero_limit.py",
                "18_cleared_traceback.py",
                "19_bare_raise_without_an_exception.py",
                "20_exit_status.py",
                "21_limit_that_is_not_a_number.py",
                "22_limit_of_true_is_one_frame.py",
                "23_context_cycle.py",
                "24_generator_frames.py",
                "25_generator_pep479.py",
                "26_generator_throw_escape.py",
                "27_generator_nested.py",
                "28_generator_throw_from_handler.py",
                "29_group_plain.py",
                "30_group_nested.py",
                "31_group_chain.py",
                "32_group_width.py",
                "33_group_depth.py",
                "34_group_notes_lines.py",
                "35_except_star_escape.py",
                "36_group_single_child.py",
                "37_base_exception_group.py",
                "38_group_traceback_limit.py",
                "39_group_context_escape.py",
                "40_group_repeated_child.py",
                "41_group_escape_from_clause.py",
                "42_match_guard_escape.py",
                "43_match_attribute_escape.py",
                "44_match_positional_refusal.py",
                "45_match_comparison_escape.py",
                "46_match_name_that_is_not_a_class.py",
                "47_match_duplicate_attribute.py",
                "48_yield_from_escape.py",
                "49_yield_from_throw_escape.py",
                "50_yield_from_pep479.py",
                "51_yield_from_send_refused.py",
                "52_yield_from_close_refused.py",
                "53_yield_from_depth.py",
                "54_class_body_free_variable.py",
                "55_class_body_cell_emptied.py",
                "56_suggest_a_local_name.py",
                "57_suggest_a_builtin_by_case.py",
                "58_suggest_the_first_of_two.py",
                "59_suggest_an_attribute_of_self.py",
                "60_suggest_a_forgotten_import.py",
                "61_suggest_an_import_and_a_name.py",
                "62_suggest_an_attribute.py",
                "63_suggest_a_private_attribute.py",
                "64_suggest_an_attribute_of_a_class.py",
                "65_suggest_an_attribute_of_an_int.py",
                "66_suggest_nothing_for_an_unbound_local.py",
                "67_suggest_for_an_empty_cell.py",
                "68_suggest_under_a_limit_of_zero.py",
                "69_suggest_nothing_when_too_far.py",
                "70_suggest_an_underscored_member_of_self.py",
                "71_suggest_through_a_written_dir.py",
                "72_suggest_nothing_when_the_dir_fails.py",
                "73_suggest_onto_a_complaint_that_said_nothing.py",
                "74_suggest_nothing_for_another_error_class.py",
                "75_suggest_nothing_without_a_name.py",
                "76_suggest_nothing_once_the_name_is_deleted.py",
                "77_suggest_nothing_for_a_name_that_is_not_text.py",
                "78_dir_that_raises_escapes.py",
                "79_notes_added_one_by_one.py",
                "80_hook_reports_in_its_own_words.py",
                "81_hook_raises_its_own_error.py",
                "82_hook_exits_the_program.py",
                "83_hook_name_is_missing.py",
                "84_hook_that_is_nothing.py",
                "85_uncaught_interrupt_status.py",
                "86_hook_raises_an_interrupt.py",
                "87_last_names_before_the_hook.py",
                "88_traceback_handed_to_the_display.py",
                "89_interrupt_and_a_hook_that_exits.py");
    }

    @Test
    void aCommandOnTheCommandLineRunsAndNamesItselfInArgv() throws Exception {
        List<String> program =
                List.of("import sys; print(sys.argv); print(sum([int(a) for a in sys.argv[1:]]))");
        List<String> arguments = List.of("3", "4");

        Result jy3 = jy3(commandOptions(program, arguments));
        assertThat(jy3.exit()).isZero();
        assertThat(jy3.out()).isEqualTo("['-c', '3', '4']\n7\n");
        assertThat(jy3.out()).isEqualTo(cpython(commandOptions(program, arguments)).out());
    }

    @Test
    void aProgramOnStdinRunsAndReportsStdinInArgv() throws Exception {
        String program = "import sys; print(sys.argv); print('from stdin')\n";
        List<String> arguments = List.of("-", "a", "b");

        Result jy3 = jy3(arguments, program);
        assertThat(jy3.out()).isEqualTo("['-', 'a', 'b']\nfrom stdin\n");
        assertThat(jy3.out()).isEqualTo(cpython(arguments, program).out());
    }

    @Test
    void aSyntaxErrorIsTheReferenceInterpretersOwnReport() throws Exception {
        Path script = write("broken.py", "def f(:\n");

        Result jy3 = jy3(script);
        Result reference = cpython(script);

        assertThat(jy3.exit()).isEqualTo(reference.exit());
        assertThat(jy3.err()).isEqualTo(reference.err());
    }

    /**
     * The whole report is compared, not the complaint alone.
     *
     * <p>The frame lines above the message are what M3 slice 9 added, and they are why this test asks the
     * reference for its anchor-free report: see {@link #anUncaughtExceptionIsReportedAsCpythonReportsIt}.
     */
    @Test
    void anUncaughtExceptionNamesItsTypeAndFails() throws Exception {
        Path script = write("raises.py", "raise ValueError('bad value')\n");

        Result jy3 = jy3(script);
        Result reference = cpythonWithoutAnchors(List.of(script.toString()));

        assertThat(jy3.exit()).isEqualTo(1);
        assertThat(jy3.err()).isEqualTo(reference.err());
        assertThat(jy3.err())
                .isEqualTo(
                        "Traceback (most recent call last):\n"
                                + "  File \""
                                + script.toAbsolutePath()
                                + "\", line 1, in <module>\n"
                                + "    raise ValueError('bad value')\n"
                                + "ValueError: bad value\n");
    }

    /**
     * The refusals a call meets before any value exists, each in its own run, worded as the reference
     * words it.
     *
     * <p>A corpus script cannot carry these: one uncaught exception ends the program, so every
     * wording needs a process of its own. The whole report is compared, which makes these the cases that
     * cover a {@code <string>} frame: a command has no file to read a source line from, so the command
     * itself has to be what the report quotes, the way CPython hands the text to {@code linecache}. The
     * passing lines are here for the same reason as the refusals — each one used to answer wrongly rather
     * than loudly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("signatureCases")
    void aSignatureIsEnforcedAsCpythonEnforcesIt(String program) throws Exception {
        List<String> arguments = commandOptions(List.of(program), List.of());

        Result jy3 = jy3(arguments);
        Result reference = cpythonWithoutAnchors(arguments);

        assertThat(jy3.exit()).as(program).isEqualTo(reference.exit());
        assertThat(jy3.out()).as(program).isEqualTo(reference.out());
        assertThat(jy3.err()).as(program).isEqualTo(reference.err());
    }

    private static List<String> signatureCases() {
        return List.of(
                "def f(a, /): pass\nf(a=1)",
                "def f(a, b=2, /, c=3): pass\nf(a=1, b=2)",
                "def f(a, b=2, /, c=3, **kw): pass\nf(a=1)",
                "def f(a, b=2, /, c=3, **kw): pass\nprint(f(1, a=2))",
                "def f(a): pass\nf(1, 2)",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass\nprint(f(1, 2, 3, e=7))",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass"
                        + "\nprint(f(*[1, 2, 3], **{'e': 7, 'g': 8}))",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass"
                        + "\nprint(f(1, 2, 3, 4, 5, 6, e=7, z=8))",
                "def f(a, b, /, c, d=4, *rest, e, g=6, **kw): pass\nf(1, 2, 3, e=7, g=8, k=9)");
    }

    /**
     * Behaviours whose whole point is a value, not a message: the slice defaults, the folding table,
     * the keyword-only method and the dict comprehension's pair.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("valueCases")
    void aValueIsComputedAsCpythonComputesIt(String program) throws Exception {
        List<String> arguments = commandOptions(List.of(program), List.of());

        Result jy3 = jy3(arguments);
        Result reference = cpython(arguments);

        assertThat(jy3.exit()).as(program).isZero();
        assertThat(jy3.out()).as(program).isEqualTo(reference.out());
    }

    private static List<String> valueCases() {
        return List.of(
                "print([1, 2, 3][::-1], 'abc'[1::-1], (1, 2, 3)[::-1], 'abc'[4::-1])",
                "print([x for x in range(6)][::-2], [1, 2, 3][-1:0], [1, 2, 3][0:-1])",
                "print('ß'.casefold(), 'ﬁ'.casefold(), 'ŉ'.casefold(), 'İ'.casefold())",
                "print('x'.isupper(), 'X'.isupper(), 'x'.islower(), 'A'.islower(), 'a1'.islower())",
                "print('{:>8}|{:<8}|{:^8}|{a:{w}}'.format('a', 'b', 'c', a='z', w=5))",
                "print({k: k * k for k in range(4)}, {k: 'v' for k in 'ab'})",
                "x = [3, 1, 2]\nx.sort(reverse=True)\nprint(x)\nx.sort(key=str, reverse=False)\nprint(x)",
                "print(-7.5 ** 2, (-7.5) ** 3, (-8) ** 0.5 == (-8) ** 0.5, 2 ** -3, (-2) ** -3)",
                "for i, ch in enumerate('abc'):\n    print(i, ch)",
                "a, *b, c = [1, 2, 3, 4]\nprint(a, b, c)\n(x, y), z = (1, 2), 3\nprint(x, y, z)");
    }

    @Test
    void sysExitDecidesTheStatus() throws Exception {
        for (String argument : List.of("()", "(0)", "(3)", "(1,)", "('bye')")) {
            List<String> program = List.of("import sys; sys.exit" + argument);

            Result jy3 = jy3(commandOptions(program, List.of()));
            Result reference = cpython(commandOptions(program, List.of()));

            assertThat(jy3.exit()).as("sys.exit" + argument).isEqualTo(reference.exit());
            assertThat(jy3.err()).as("sys.exit" + argument).isEqualTo(reference.err());
        }
    }

    @Test
    void aScriptThatIsNotThereIsAUsageError() throws Exception {
        Result jy3 = jy3(List.of("nosuch.py"));
        Result reference = cpython(List.of("nosuch.py"));

        // CPython resolves the name before complaining, so the path in the message is the working
        // directory's own spelling of itself; only the tail of the sentence is comparable.
        assertThat(jy3.exit()).isEqualTo(reference.exit());
        assertThat(jy3.exit()).isEqualTo(2);
        assertThat(complaint(jy3.err())).isEqualTo(complaint(reference.err()));
        assertThat(jy3.err()).startsWith("jy3: ");
    }

    @Test
    void aReservedHarnessTargetIsReportedAsNotImplemented() throws Exception {
        Result result = jy3(List.of("--harness", "test_bool"));

        // The protocol the golden harness reads: 0 pass, 1 fail, 2 skip, 3 not implemented.
        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.err()).contains("test_bool").contains("not implemented");
    }

    // --- running -------------------------------------------------------------------------
    //
    // The process plumbing is Jy3Process, shared with the interop tests: two copies of "start the
    // launcher, drain both streams, refuse to wait forever" drift, and the drift looks like a product
    // bug when it shows up in only one of them.

    private Result jy3(List<String> arguments) throws Exception {
        return Jy3Process.run(work, Jy3Process.jy3Command(), arguments, null);
    }

    private Result jy3(Path script) throws Exception {
        return jy3(List.of(script.toString()));
    }

    private Result jy3(List<String> arguments, String stdin) throws Exception {
        return Jy3Process.run(work, Jy3Process.jy3Command(), arguments, stdin);
    }

    private Result cpython(List<String> arguments) throws Exception {
        return Jy3Process.run(work, cpythonCommand(), arguments, null);
    }

    private Result cpython(Path script) throws Exception {
        return cpython(List.of(script.toString()));
    }

    private Result cpython(List<String> arguments, String stdin) throws Exception {
        return Jy3Process.run(work, cpythonCommand(), arguments, stdin);
    }

    /**
     * The reference asked for the report without anchors: {@code -X no_debug_ranges} leaves the PEP 657
     * ranges unrecorded, and the report CPython then writes is the one its own C printer writes.
     */
    private Result cpythonWithoutAnchors(List<String> arguments) throws Exception {
        return Jy3Process.run(work, cpythonWithoutAnchorsCommand(), arguments, null);
    }

    private static List<String> cpythonWithoutAnchorsCommand() {
        return cpythonCommand(List.of("-X", "no_debug_ranges"));
    }

    private static List<String> cpythonCommand() {
        return cpythonCommand(List.of());
    }

    /** The reference interpreter, with any interpreter options ahead of the script. */
    private static List<String> cpythonCommand(List<String> options) {
        String configured = System.getProperty("jython3.refPython");
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv("JY3_REF_PYTHON");
        }
        List<String> command =
                new ArrayList<>(
                        List.of(configured == null || configured.isEmpty() ? "python3.14" : configured));
        command.addAll(options);
        return command;
    }

    private static List<String> commandOptions(List<String> program, List<String> arguments) {
        List<String> options = new ArrayList<>();
        options.add("-c");
        options.add(program.get(0));
        options.addAll(arguments);
        return options;
    }

    private Path write(String name, String source) throws IOException {
        return Jy3Process.write(work, name, source);
    }

    /** M1's acceptance script, kept in one place so the test and a human run the same file. */
    private static String resource(String name) {
        return Jy3Process.resource(name);
    }

    /** The part of a launcher complaint that names neither the program nor the working directory. */
    private static String complaint(String stderr) {
        int at = stderr.indexOf("can't open file");
        return at < 0 ? stderr : stderr.substring(at);
    }
}
