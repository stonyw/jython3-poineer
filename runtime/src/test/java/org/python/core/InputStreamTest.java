package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code input}, {@code sys.stdin} and the dictionary a stream keeps, measured against CPython 3.14.10.
 *
 * <p>What a call answers is compared elsewhere: {@code m3/corpus/132_input_and_the_line_it_reads} runs the
 * builtin against the reference with Python-side stream doubles. Taken apart here is the half a double
 * cannot reach — the descriptor read behind {@code PyStream.readline}, which {@code PyFile_GetLine}
 * (Objects/fileobject.c:54) hands straight to {@code builtin_input_impl} (Python/bltinmodule.c:2335) —
 * and the order inside one call, which a program sees only by asking three streams to remember what they
 * were handed and when.
 *
 * <p>The descriptor is cut only at the newline the stream was opened with: {@code sys.stdin} is opened with
 * {@code newline='\n'}, so nothing is translated and a {@code '\r'} is text — {@code "two\r\n"} answers as
 * the five characters it is, and {@code "a\rb\n"} as one line of four. The chunking behind those answers is
 * what {@code m3/corpus/135_stream_reads_and_the_position_refusals} compares against the reference over a
 * pipe; asked here against a Java descriptor it is the same loop with a different source of octets, and it
 * is what a double cannot reach: reads ask the descriptor for {@code _CHUNK_SIZE} octets, counts are
 * answered in characters, and the character a chunk cuts in half waits for the octets that complete it. An
 * empty answer is the end of the descriptor and becomes {@code EOFError}, and exactly one character comes
 * off the end of any other answer — the object itself when there was nothing to cut, a fresh value when
 * there was.
 *
 * <p>Four of the refusals the call makes are cleared and two are not. Both flushes are tried and their
 * errors dropped, as is a {@code fileno} that is missing or refuses when called; the conversion of what
 * that call answered is heard, in the conversion's own words, and arrives before a prompt has been
 * written. The three stream names are required in order, and the calling convention is settled before any
 * of them is read.
 *
 * <p>The other direction is asked of a stream whose octets land in a buffer rather than on a descriptor,
 * since a double cannot read them back. What a write encodes with is the codec {@code reconfigure} left
 * behind. A mark belongs to the encoder rather than to the stream's first octet: {@code utf-8-sig}'s
 * incremental encoder carries its own, so every {@code reconfigure} that builds one puts the mark back in
 * front of the next text — the empty string included — while {@code utf-16}, whose mark depends on a window
 * only a seekable descriptor opens, writes native-order units and none.
 */
class InputStreamTest {

    /** What each double was asked, in the order it was asked. */
    private final List<String> asked = new ArrayList<>();

    /** What each write of the prompt arrived with. */
    private final List<String> written = new ArrayList<>();

    private static InputStream descriptor(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static PyStream reading(String text) {
        return new PyStream("<stdin>", descriptor(text), 0, "surrogateescape");
    }

    /** A descriptor that is not text at all, which is what the chunking and the handler are asked about. */
    private static PyStream reading(byte[] octets) {
        return new PyStream("<stdin>", new ByteArrayInputStream(octets), 0, "surrogateescape");
    }

    /** {@code s._CHUNK_SIZE = size}, so a read asks the descriptor for the smallest chunk the field allows. */
    private static void chunk(PyObject stream, long size) {
        stream.setAttr("_CHUNK_SIZE", PyInt.of(size));
    }

    private static PyObject read(PyObject stream, PyObject count) {
        return call(stream, "read", count);
    }

    private static PyObject row(String name) {
        return Builtins.namespace().get(PyStr.interned(name));
    }

    /** {@code readline()} of a stream, by the member the call asks for. */
    private static PyObject lineOf(PyObject stream) {
        return Slots.call(Slots.getAttr(stream, "readline"), new PyObject[0], null);
    }

    private static PyObject call(PyObject target, String member, PyObject... arguments) {
        return Slots.call(Slots.getAttr(target, member), arguments, null);
    }

    /** A member that records the ask and then answers the given refusal. */
    private PyObject refusing(String label, PyRaise refusal) {
        return new PyNativeFunction(
                label,
                (args, kwNames) -> {
                    asked.add(label);
                    throw refusal;
                });
    }

    /** A member that is there and is not callable, which the reference reaches and clears. */
    private static PyObject nothing() {
        return PyNone.INSTANCE;
    }

    /**
     * A stream that answers one fixed line and hands back {@code descriptor} for its {@code fileno} — null
     * being the instruction to refuse that call rather than answer it.
     */
    private PyObject answering(PyObject answer, PyObject descriptor) {
        PyModule stream = new PyModule("in");
        stream.set(
                "fileno",
                new PyNativeFunction(
                        "fileno",
                        (args, kwNames) -> {
                            asked.add("in.fileno");
                            if (descriptor == null) {
                                throw Slots.osError("no fd");
                            }
                            return descriptor;
                        }));
        stream.set(
                "readline",
                new PyNativeFunction(
                        "readline",
                        (args, kwNames) -> {
                            asked.add("in.readline");
                            return answer;
                        }));
        return stream;
    }

    /** A stream that writes into the log and says which members it was asked for. */
    private PyObject logging(String name) {
        PyModule stream = new PyModule(name);
        stream.set(
                "write",
                new PyNativeFunction(
                        "write",
                        (args, kwNames) -> {
                            asked.add(name + ".write");
                            String piece = ((PyStr) args[0]).getValue();
                            written.add(piece);
                            return PyInt.of(piece.length());
                        }));
        stream.set(
                "flush",
                new PyNativeFunction(
                        "flush",
                        (args, kwNames) -> {
                            asked.add(name + ".flush");
                            return PyNone.INSTANCE;
                        }));
        return stream;
    }

    /**
     * {@code input(*arguments)} against three doubles that keep score.
     *
     * <p>The descriptor is what {@code sys.stdin.fileno()} answers, since that is the only {@code fileno}
     * the call asks for; null refuses the call instead of returning a number.
     */
    private PyObject askedOf(PyObject answer, PyObject descriptor, PyObject... arguments) {
        streams(answer, descriptor);
        return Slots.call(row("input"), arguments, null);
    }

    private static void named(PyObject callable, String keyword) {
        Slots.call(
                callable,
                new PyObject[] {PyStr.of("go: ")},
                PyBoundMethod.keywordTuple(new String[] {keyword}));
    }

    @AfterEach
    void putTheStreamsBack() {
        for (String name : new String[] {"stdin", "stdout", "stderr"}) {
            PyRuntime.setSysAttribute(name, PyRuntime.sysAttribute("__" + name + "__"));
        }
    }

    /**
     * One descriptor, cut only at the newline it was opened with.
     *
     * <p>No line ending is translated, so a {@code '\r'} is ordinary text: the pair is answered as part of
     * the line it ends rather than as a line of its own, and the line the descriptor runs out on is answered
     * whole, with no terminator. What follows that is the empty text, the answer that means the end.
     */
    @Test
    void everyLineEndsAtTheNewlineItWasOpenedWith() {
        PyStream stream = reading("one\ntwo\r\nthree\rfour");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'one\\n'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'two\\r\\n'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'three\\rfour'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("''");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("''");
    }

    /** A {@code '\r'} in the middle of a line is one of its characters, and the line ends at the next. */
    @Test
    void aCarriageReturnInAMiddleIsNotATerminator() {
        PyStream stream = reading("a\rb\nc\n");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'a\\rb\\n'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'c\\n'");
    }

    /**
     * The count is characters and the chunk is octets.
     *
     * <p>With {@code _CHUNK_SIZE} at four, one read of the descriptor is asked for four octets — which is
     * fewer characters than four once a snake is among them, and more text than one read is owed besides. A
     * count takes the characters it is owed and leaves the rest of the chunk for the read after it, so three
     * reads of three cross two line endings rather than stopping at one. A count of zero — which is also
     * {@code False}, a bool being a count like any other — consumes nothing at all.
     */
    @Test
    void aCountIsCharactersAndAChunkIsOctets() {
        PyStream stream = reading("one\r\ntwo\r\n\ud83d\udc0dx\n");
        chunk(stream, 4);
        assertThat(Slots.repr(read(stream, PyInt.of(3)))).isEqualTo("'one'");
        assertThat(Slots.repr(read(stream, PyInt.of(3)))).isEqualTo("'\\r\\nt'");
        assertThat(Slots.repr(read(stream, PyInt.of(5)))).isEqualTo("'wo\\r\\n\ud83d\udc0d'");
        assertThat(Slots.repr(read(stream, PyInt.ONE))).isEqualTo("'x'");
        assertThat(Slots.repr(read(stream, PyInt.ZERO))).isEqualTo("''");
        assertThat(Slots.repr(read(stream, PyInt.of(2)))).isEqualTo("'\\n'");
        assertThat(Slots.repr(read(stream, PyInt.ONE))).isEqualTo("''");
    }

    /**
     * The character a chunk cuts in half waits for the chunk that completes it.
     *
     * <p>At two octets a chunk a snake arrives as two reads of two, and the decoder holds the lead rather
     * than reporting it: the character comes out whole when the continuation reaches it, and the reads that
     * follow are the ones a whole descriptor would have answered. Nothing here is broken UTF-8, only cut,
     * which is why {@code errors} never gets asked.
     */
    @Test
    void aCharacterSplitAcrossChunksIsReassembled() {
        PyStream stream =
                reading(
                        new byte[] {
                            (byte) 0xF0, (byte) 0x9F, (byte) 0x90, (byte) 0x8D, 'x', '\n', 'a', 'b', 'c'
                        });
        chunk(stream, 2);
        assertThat(Slots.repr(read(stream, PyInt.ONE))).isEqualTo("'\ud83d\udc0d'");
        assertThat(Slots.repr(read(stream, PyInt.ONE))).isEqualTo("'x'");
        assertThat(Slots.repr(read(stream, PyInt.of(2)))).isEqualTo("'\\na'");
        assertThat(Slots.repr(read(stream, PyInt.ONE))).isEqualTo("'b'");
        assertThat(Slots.repr(read(stream, PyInt.of(-1)))).isEqualTo("'c'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("''");
    }

    /**
     * What the handler answers for, which is an octet that cannot be completed rather than one that is
     * waiting.
     *
     * <p>A lead with no continuation after it is not a character in progress: the surrogate escape {@code
     * sys.stdin} is opened with answers one surrogate for it and the read goes on with the rest of the line.
     * The end of the descriptor is the other way the same sentence is reached — the tail the last chunk was
     * holding has no chunk after it, so each octet left over becomes a surrogate of its own.
     */
    @Test
    void anUncompletableOctetGoesToTheHandler() {
        PyStream broken = reading(new byte[] {'a', (byte) 0xC3, 'b', '\n'});
        assertThat(Slots.repr(read(broken, PyInt.of(-1)))).isEqualTo("'a\\udcc3b\\n'");
        assertThat(Slots.repr(read(broken, PyInt.of(-1)))).isEqualTo("''");

        PyStream shortTail = reading(new byte[] {'c', (byte) 0xE2, (byte) 0x82});
        chunk(shortTail, 2);
        assertThat(Slots.repr(read(shortTail, PyInt.of(-1)))).isEqualTo("'c\\udce2\\udc82'");
    }

    /**
     * A limit cuts inside a line and leaves the rest of it, terminator included.
     *
     * <p>The limit is compared against the terminator the read has found, not against the line it has
     * settled on, so {@code readline(2)} answers the first two characters of {@code "abcde\n"} and the next
     * call answers the four that were left behind. A limit of zero consumes nothing, a negative limit is the
     * whole line however long it is, and a limit bigger than the line is simply the line.
     */
    @Test
    void aLimitCutsInsideALineAndLeavesTheRest() {
        PyStream stream = reading("abcde\nnext\n");
        chunk(stream, 4);
        assertThat(Slots.repr(call(stream, "readline", PyInt.of(2)))).isEqualTo("'ab'");
        assertThat(Slots.repr(call(stream, "readline", PyInt.ZERO))).isEqualTo("''");
        assertThat(Slots.repr(call(stream, "readline", PyInt.of(99)))).isEqualTo("'cde\\n'");
        assertThat(Slots.repr(call(stream, "readline", PyInt.of(-2)))).isEqualTo("'next\\n'");
        assertThat(Slots.repr(call(stream, "readline", PyInt.of(-1)))).isEqualTo("''");
    }

    /**
     * The hint counts characters, and the line that overshoots it is kept.
     *
     * <p>{@code readlines} is the {@code _IOBase} row, and its loop is this stream's own iteration: each
     * line is measured against what the hint has left, and the walk leaves after the line that no longer
     * fits — that line included, which is why a hint of five over lines of five, five, three and four
     * characters gives two lines and a hint of one gives the first of them. Zero, a negative count and
     * {@code False} are the whole descriptor, {@code True} is one line, and {@code None} asks the same
     * question as no hint at all.
     */
    @Test
    void theHintKeepsTheLineThatOvershootsWithIt() {
        assertThat(Slots.repr(call(reading("1234\n5678\nab\nlast"), "readlines", PyInt.of(5))))
                .isEqualTo("['1234\\n', '5678\\n']");
        assertThat(Slots.repr(call(reading("1234\n5678\nab\nlast"), "readlines", PyInt.of(1))))
                .isEqualTo("['1234\\n']");
        assertThat(Slots.repr(call(reading("1234\n5678\nab\nlast"), "readlines", PyBool.TRUE)))
                .isEqualTo("['1234\\n']");
        assertThat(Slots.repr(call(reading("1234\n5678\nab\nlast"), "readlines", PyInt.ZERO)))
                .isEqualTo("['1234\\n', '5678\\n', 'ab\\n', 'last']");
        assertThat(Slots.repr(call(reading("1234\n5678\nab\nlast"), "readlines", PyInt.of(-2))))
                .isEqualTo("['1234\\n', '5678\\n', 'ab\\n', 'last']");
        assertThat(Slots.repr(call(reading("1234\n5678\nab\nlast"), "readlines", PyNone.INSTANCE)))
                .isEqualTo("['1234\\n', '5678\\n', 'ab\\n', 'last']");
    }

    /**
     * The stream is its own cursor, and the walk and the reads share the one it has.
     *
     * <p>{@code iter(stdin)} is the stream, and a step of it is {@code readline()} with no limit: the same
     * pending chunk, the same terminator and the same end of the descriptor, at which the iterator answers a
     * bare {@code StopIteration} and the reads answer nothing. What one of them consumes, the other finds
     * gone.
     */
    @Test
    void theWalkAndTheReadsShareOneCursor() {
        PyStream stream = reading("one\ntwo\nthree\n");
        chunk(stream, 4);
        assertThat(Slots.iterator(stream)).isSameAs(stream);
        assertThat(Slots.repr(stream.nextOrNull())).isEqualTo("'one\\n'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'two\\n'");
        assertThat(stream.nextOrNull()).isEqualTo(PyStr.of("three\n"));
        assertThat(stream.nextOrNull()).isNull();
        assertThat(Slots.repr(call(stream, "readlines"))).isEqualTo("[]");
        assertThat(Slots.repr(read(stream, PyInt.of(-1)))).isEqualTo("''");
    }

    /**
     * The chunk the stream is told to use, and the five counts that are not one.
     *
     * <p>{@code _CHUNK_SIZE} reads 8192 until it is rewritten, and the write converts as {@code
     * PyNumber_AsSsize_t} with a {@code ValueError} to overflow with: {@code 1.5} and {@code None} are
     * refused by their type before the count is looked at, {@code 0} and {@code -3} are refused as counts
     * that are not strictly positive, and {@code 2 ** 70} cannot fit an index — the same sentence {@code
     * read(2 ** 70)} answers with, but as a {@code ValueError} rather than the {@code OverflowError} that
     * row's conversion raises. A delete reaches a row with a setter and no deleter, and the field is the
     * stream's rather than a buffer's, so a write reaches it even after the stream has closed.
     */
    @Test
    void theChunkSizeFieldTakesOnlyAPositiveCount() {
        PyStream stream = reading("one\n");
        assertThat(Slots.getAttr(stream, "_CHUNK_SIZE")).isEqualTo(PyInt.of(8192));
        chunk(stream, 4);
        assertThat(Slots.getAttr(stream, "_CHUNK_SIZE")).isEqualTo(PyInt.of(4));

        Object[][] refused = {
            {PyFloat.of(1.5), "TypeError: 'float' object cannot be interpreted as an integer"},
            {PyNone.INSTANCE, "TypeError: 'NoneType' object cannot be interpreted as an integer"},
            {PyInt.ZERO, "ValueError: a strictly positive integer is required"},
            {PyInt.of(-3), "ValueError: a strictly positive integer is required"},
            {
                PyInt.of(BigInteger.valueOf(2).pow(70)),
                "ValueError: cannot fit 'int' into an index-sized integer"
            },
        };
        for (Object[] row : refused) {
            assertThatThrownBy(() -> stream.setAttr("_CHUNK_SIZE", (PyObject) row[0]))
                    .as("%s is no chunk", Slots.repr((PyObject) row[0]))
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refusal -> assertThat(refusal.getMessage()).isEqualTo((String) row[1]));
        }
        assertThat(Slots.getAttr(stream, "_CHUNK_SIZE")).isEqualTo(PyInt.of(4));

        assertThatThrownBy(() -> stream.delAttr("_CHUNK_SIZE"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refusal ->
                                assertThat(refusal.getMessage())
                                        .isEqualTo(
                                                "AttributeError: attribute '_CHUNK_SIZE' of"
                                                        + " '_io.TextIOWrapper' objects cannot be"
                                                        + " deleted"));

        call(stream, "close");
        stream.setAttr("_CHUNK_SIZE", PyInt.of(100));
        assertThat(Slots.getAttr(stream, "_CHUNK_SIZE")).isEqualTo(PyInt.of(100));
    }

    /**
     * A chunk wider than one Java array, which is the one place a rewritten size cannot be answered the
     * same way.
     *
     * <p>The reference hands {@code _CHUNK_SIZE} straight to the raw descriptor, which allocates the buffer
     * inside itself; here the same request would allocate a Java array, so one read asks for at most 64 KiB
     * however big the field is. The answers come out the same either way because the loops reassemble what
     * each read hands out — which is what this row is really asking of a size of {@code 2 ** 40} and a
     * descriptor two hundred thousand characters long.
     */
    @Test
    void anImpossibleChunkStillAnswersTheTextItHolds() {
        String text = "x".repeat(200_000) + "\n";
        PyStream stream = reading(text);
        stream.setAttr("_CHUNK_SIZE", PyInt.of(BigInteger.valueOf(2).pow(40)));
        assertThat(Slots.getAttr(stream, "_CHUNK_SIZE"))
                .isEqualTo(PyInt.of(BigInteger.valueOf(2).pow(40)));
        String answer = ((PyStr) read(stream, PyInt.of(-1))).getValue();
        assertThat(answer).hasSize(200_001).endsWith("x\n");
        assertThat(Slots.repr(read(stream, PyInt.of(-1)))).isEqualTo("''");
    }

    /**
     * The position questions, which have nothing under them to answer.
     *
     * <p>{@code seekable()} asks the layer beneath the stream and there is none here, so it says False and
     * {@code seek} and {@code tell} refuse in the reference's own words for a pipe — however the call is
     * shaped, and whether the cookie is a number, {@code None}, text or {@code 2 ** 70}, since the cookie
     * travels unconverted because nothing but a seeking stream would ever read it. {@code whence} is
     * converted, and first. {@code truncate} answers the refusal its buffer would have made, which is the
     * word alone.
     */
    @Test
    void thePositionQuestionsHaveNothingUnderThem() {
        PyStream stream = reading("one\n");
        assertThat(call(stream, "seekable")).isSameAs(PyBool.FALSE);

        String refusal = "UnsupportedOperation: underlying stream is not seekable";
        for (PyObject cookie :
                new PyObject[] {
                    PyInt.ZERO,
                    PyInt.of(-1),
                    PyNone.INSTANCE,
                    PyStr.of("x"),
                    PyInt.of(BigInteger.valueOf(2).pow(70))
                }) {
            assertThatThrownBy(() -> call(stream, "seek", cookie))
                    .as("seek(%s)", Slots.repr(cookie))
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            raised -> assertThat(raised.getMessage()).isEqualTo(refusal));
        }
        for (PyObject whence : new PyObject[] {PyInt.ONE, PyBool.TRUE}) {
            assertThatThrownBy(() -> call(stream, "seek", PyInt.ZERO, whence))
                    .as("seek(0, %s)", Slots.repr(whence))
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            raised -> assertThat(raised.getMessage()).isEqualTo(refusal));
        }
        assertThatThrownBy(() -> call(stream, "tell"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> assertThat(raised.getMessage()).isEqualTo(refusal));

        for (PyObject position : new PyObject[] {PyNone.INSTANCE, PyInt.of(5)}) {
            assertThatThrownBy(() -> call(stream, "truncate", position))
                    .as("truncate(%s)", Slots.repr(position))
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            raised ->
                                    assertThat(raised.getMessage())
                                            .isEqualTo("UnsupportedOperation: truncate"));
        }

        assertThatThrownBy(() -> call(stream, "seek", PyInt.ZERO, PyFloat.of(1.5)))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "TypeError: 'float' object cannot be interpreted as"
                                                        + " an integer"));
    }

    /**
     * The three questions a subclass asks, and the answer the stream gave.
     *
     * <p>{@code _PyIOBase_check_*} calls the stream's own question and refuses unless the answer is exactly
     * True — and when it is True, hands that bool back, which is why {@code stdin._checkReadable()} is True
     * rather than None. {@code _checkClosed} is the one whose whole job is the closed check, so it is the one
     * that answers with the full stop; the other three reach their question first, and a closed stream is
     * refused by that getter's sentence, which has none.
     */
    @Test
    void theCheckQuestionsRefuseUnlessTheAnswerIsExactlyTrue() {
        PyStream reading = reading("one\n");
        PyStream writing = new PyStream("<stdout>", System.out, 1, "backslashreplace", false);

        assertThat(call(reading, "_checkReadable")).isSameAs(PyBool.TRUE);
        assertThat(call(writing, "_checkWritable")).isSameAs(PyBool.TRUE);
        assertThat(call(reading, "_checkClosed")).isSameAs(PyNone.INSTANCE);

        assertThatThrownBy(() -> call(reading, "_checkWritable"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "UnsupportedOperation: File or stream is not"
                                                        + " writable."));
        assertThatThrownBy(() -> call(writing, "_checkReadable"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "UnsupportedOperation: File or stream is not"
                                                        + " readable."));
        assertThatThrownBy(() -> call(reading, "_checkSeekable"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "UnsupportedOperation: File or stream is not"
                                                        + " seekable."));

        call(reading, "close");
        assertThatThrownBy(() -> call(reading, "_checkReadable"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo("ValueError: I/O operation on closed file"));
        assertThatThrownBy(() -> call(reading, "_checkClosed"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo("ValueError: I/O operation on closed file."));
    }

    /**
     * The row that refuses names the class that files it.
     *
     * <p>A C function's sentence is built from {@code ml_name} and the qualified name of the type that files
     * the row, so {@code read}, {@code readline}, {@code seek}, {@code tell}, {@code truncate} and {@code
     * write} — the stream's own rows — name {@code TextIOWrapper}, while {@code readlines}, {@code
     * writelines} and the {@code _check} rows, which the stream inherits from {@code _io._IOBase}, name
     * that. The unpacking family has no type to name and says only {@code read expected at most 1 argument,
     * got 2}, and a keyword is the other family's sentence: the same rule, with the parentheses.
     */
    @Test
    void aRefusalNamesTheRowThatFilesIt() {
        String[][] refused = {
            {"read two", "TypeError", "read expected at most 1 argument, got 2"},
            {"read float", "TypeError", "argument should be integer or None, not 'float'"},
            {"read big", "OverflowError", "cannot fit 'int' into an index-sized integer"},
            {"readlines float", "TypeError", "argument should be integer or None, not 'float'"},
            {"readlines two", "TypeError", "readlines expected at most 1 argument, got 2"},
            {"readline float", "TypeError", "'float' object cannot be interpreted as an integer"},
            {"readline none", "TypeError", "'NoneType' object cannot be interpreted as an integer"},
            {"readline big", "OverflowError", "Python int too large to convert to C ssize_t"},
            {"seek noargs", "TypeError", "seek expected at least 1 argument, got 0"},
            {"seek three", "TypeError", "seek expected at most 2 arguments, got 3"},
            {"seek float", "TypeError", "'float' object cannot be interpreted as an integer"},
            {"tell arg", "TypeError", "TextIOWrapper.tell() takes no arguments (1 given)"},
            {"truncate two", "TypeError", "truncate expected at most 1 argument, got 2"},
            {"seekable arg", "TypeError", "TextIOWrapper.seekable() takes no arguments (1 given)"},
            {"write noarg", "TypeError", "TextIOWrapper.write() takes exactly one argument (0 given)"},
            {"write two", "TypeError", "TextIOWrapper.write() takes exactly one argument (2 given)"},
            {"write bytes", "TypeError", "write() argument must be str, not bytes"},
            {"write none", "TypeError", "write() argument must be str, not None"},
            {"writelines noarg", "TypeError", "_IOBase.writelines() takes exactly one argument (0 given)"},
            {"writelines two", "TypeError", "_IOBase.writelines() takes exactly one argument (2 given)"},
            {"writelines none", "TypeError", "'NoneType' object is not iterable"},
            {"checkClosed arg", "TypeError", "_IOBase._checkClosed() takes no arguments (1 given)"},
        };
        for (String[] row : refused) {
            assertThatThrownBy(() -> ask(row[0]))
                    .as("%s", row[0])
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            raised ->
                                    assertThat(raised.getMessage())
                                            .isEqualTo(row[1] + ": " + row[2]));
        }

        for (String member :
                new String[] {
                    "read", "readline", "readlines", "seek", "truncate", "write", "writelines"
                }) {
            assertThatThrownBy(() -> namedSize(member))
                    .as("%s named", member)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            raised ->
                                    assertThat(raised.getMessage())
                                            .endsWith(" takes no keyword arguments"));
        }
        assertThatThrownBy(() -> namedSize("_checkWritable"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "TypeError: _IOBase._checkWritable() takes no keyword"
                                                        + " arguments"));
    }

    /** The call the table above asks for, of a stream with one line under it. */
    private PyObject ask(String question) {
        PyStream stream = reading("one\n");
        return switch (question) {
            case "read two" -> call(stream, "read", PyInt.ONE, PyInt.ONE);
            case "read float" -> call(stream, "read", PyFloat.of(1.5));
            case "read big" -> call(stream, "read", PyInt.of(BigInteger.valueOf(2).pow(70)));
            case "readlines float" -> call(stream, "readlines", PyFloat.of(1.5));
            case "readlines two" -> call(stream, "readlines", PyInt.ONE, PyInt.ONE);
            case "readline float" -> call(stream, "readline", PyFloat.of(1.5));
            case "readline none" -> call(stream, "readline", PyNone.INSTANCE);
            case "readline big" -> call(stream, "readline", PyInt.of(BigInteger.valueOf(2).pow(70)));
            case "seek noargs" -> call(stream, "seek");
            case "seek three" -> call(stream, "seek", PyInt.ZERO, PyInt.ZERO, PyInt.ZERO);
            case "seek float" -> call(stream, "seek", PyInt.ZERO, PyFloat.of(1.5));
            case "tell arg" -> call(stream, "tell", PyInt.ONE);
            case "truncate two" -> call(stream, "truncate", PyInt.ONE, PyInt.ONE);
            case "seekable arg" -> call(stream, "seekable", PyInt.ONE);
            case "write noarg" -> call(stream, "write");
            case "write two" -> call(stream, "write", PyStr.of("a"), PyStr.of("b"));
            case "write bytes" -> call(stream, "write", PyBytes.of(new byte[] {'x'}));
            case "write none" -> call(stream, "write", PyNone.INSTANCE);
            case "writelines noarg" -> call(stream, "writelines");
            case "writelines two" -> call(stream, "writelines", PyList.empty(), PyList.empty());
            case "writelines none" -> call(stream, "writelines", PyNone.INSTANCE);
            case "checkClosed arg" -> call(stream, "_checkClosed", PyInt.ONE);
            default -> throw new IllegalArgumentException(question);
        };
    }

    /** {@code s.<member>(1, size = 2)}: a keyword handed to a row that takes none. */
    private PyObject namedSize(String member) {
        PyStream stream = reading("one\n");
        return Slots.call(
                Slots.getAttr(stream, member),
                new PyObject[] {PyInt.ONE, PyInt.of(2)},
                PyBoundMethod.keywordTuple(new String[] {"size"}));
    }

    /** The descriptor is decoded as UTF-8, which is what {@code encoding} has always answered here. */
    @Test
    void theDescriptorIsTextAndNotBytes() {
        PyStream stream = reading("café\n");
        assertThat(((PyStr) lineOf(stream)).getValue()).isEqualTo("café\n");
        assertThat(Slots.getAttr(stream, "encoding")).isEqualTo(PyStr.of("utf-8"));
    }

    /**
     * The answers of {@code input}, and the one character it takes off the end of them.
     *
     * <p>A line with a terminator is cut to a fresh value and a line without one is the very object the
     * stream answered with — the call rebuilds no text it has no reason to shorten. An empty answer is the
     * end of the descriptor and becomes {@code EOFError}, and the prompt is written first, in one call of
     * {@code write} with the text exactly as it was given.
     */
    @Test
    void oneAnswerLineAndOneCharacterOffIt() {
        assertThat(askedOf(PyStr.of("one\n"), PyInt.ZERO, PyStr.of("go: ")))
                .isEqualTo(PyStr.of("one"));
        assertThat(written).containsExactly("go: ");
        assertThat(asked)
                .containsExactly("err.flush", "in.fileno", "out.write", "out.flush", "in.readline");

        assertThat(askedOf(PyStr.of("two\r\n"), PyInt.ZERO)).isEqualTo(PyStr.of("two\r"));
        assertThat(written).isEmpty();
        assertThat(asked).containsExactly("err.flush", "in.fileno", "out.flush", "in.readline");

        PyStr kept = PyStr.of("no terminator");
        assertThat(askedOf(kept, PyInt.ZERO, PyStr.of("p> "))).isSameAs(kept);

        assertThatThrownBy(() -> askedOf(PyStr.of(""), PyInt.ZERO, PyStr.of("go: ")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("EOFError: EOF when reading a line"));
    }

    /**
     * An answer of the wrong kind, in the two ways the reference means that.
     *
     * <p>Octets are legal: the call cuts the terminator off them too and hands back bytes, which is what a
     * stream that was never wrapped in text answers with. Anything else is refused by the unpacking, under
     * the name of the object that answered rather than of the caller.
     */
    @Test
    void octetsAreKeptAndAnythingElseIsRefused() {
        assertThat(
                        askedOf(
                                PyBytes.of("raw\n".getBytes(StandardCharsets.UTF_8)),
                                PyInt.ZERO))
                .isEqualTo(PyBytes.of("raw".getBytes(StandardCharsets.UTF_8)));

        PyBytes kept = PyBytes.of("raw".getBytes(StandardCharsets.UTF_8));
        assertThat(askedOf(kept, PyInt.ZERO)).isSameAs(kept);

        for (PyObject answer : new PyObject[] {PyInt.of(7), PyNone.INSTANCE}) {
            assertThatThrownBy(() -> askedOf(answer, PyInt.ZERO, PyStr.of("go: ")))
                    .as("%s answers", Slots.repr(answer))
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo(
                                                    "TypeError: object.readline() returned"
                                                            + " non-string"));
        }
    }

    /**
     * The four refusals the call drops.
     *
     * <p>Both flushes are tried and whatever they answer is cleared, and so is a {@code fileno} that raises
     * or is not there at all: the error is dropped and the line is still read, prompt written and all,
     * which is the reference's {@code PyErr_Clear()} after {@code _PyFile_Flush} and after the {@code
     * fileno} call.
     */
    @Test
    void fourRefusalsAreTriedAndDropped() {
        String[] expected = {"err.flush", "in.fileno", "out.write", "out.flush", "in.readline"};
        PyObject prompt = PyStr.of("go: ");

        PyModule stderr = (PyModule) streams(PyStr.of("one\n"), PyInt.ZERO)[2];
        stderr.set("flush", refusing("err.flush", Slots.osError("no flush")));
        assertThat(Slots.call(row("input"), new PyObject[] {prompt}, null))
                .isEqualTo(PyStr.of("one"));
        assertThat(asked).containsExactly(expected);
        assertThat(written).containsExactly("go: ");

        PyModule stdout = (PyModule) streams(PyStr.of("one\n"), PyInt.ZERO)[1];
        stdout.set("flush", refusing("out.flush", Slots.valueError("no flush")));
        assertThat(Slots.call(row("input"), new PyObject[] {prompt}, null))
                .isEqualTo(PyStr.of("one"));
        assertThat(asked).containsExactly(expected);

        PyModule stdin = (PyModule) streams(PyStr.of("one\n"), PyInt.ZERO)[0];
        stdin.set("fileno", refusing("in.fileno", Slots.osError("no fd")));
        assertThat(Slots.call(row("input"), new PyObject[] {prompt}, null))
                .isEqualTo(PyStr.of("one"));
        assertThat(asked).containsExactly(expected);

        stdout = (PyModule) streams(PyStr.of("one\n"), PyInt.ZERO)[1];
        stdout.set("flush", nothing());
        assertThat(Slots.call(row("input"), new PyObject[] {prompt}, null))
                .isEqualTo(PyStr.of("one"));
        // Nothing is logged for the flush that is not a callable, because the call is never made.
        assertThat(asked).containsExactly("err.flush", "in.fileno", "out.write", "in.readline");
    }

    /** Install the three scorekeeping doubles and hand them back, so one member can be turned sour. */
    private PyObject[] streams(PyObject answer, PyObject descriptor) {
        asked.clear();
        written.clear();
        PyObject stdin = answering(answer, descriptor);
        PyObject stdout = logging("out");
        PyObject stderr = logging("err");
        PyRuntime.setSysAttribute("stdin", stdin);
        PyRuntime.setSysAttribute("stdout", stdout);
        PyRuntime.setSysAttribute("stderr", stderr);
        return new PyObject[] {stdin, stdout, stderr};
    }

    /**
     * The refusals the call hears, and the descriptor numbers it does not look at.
     *
     * <p>The <em>conversion</em> of what {@code fileno} answered is outside the clearing, and both of its
     * sentences belong to the conversion — {@code 'float' object cannot be interpreted as an integer} and
     * {@code Python int too large to convert to C long} — and arrive before the prompt is written. A
     * descriptor number itself is never compared against anything, since this runtime never reads a terminal
     * through the {@code readline} library, so {@code -1} and {@code 2**31} are as good an answer as {@code
     * 0}; only a value 64 bits wide is too wide for the C long the reference would have stored it in.
     */
    @Test
    void theConversionIsHeardBeforeThePromptIsWritten() {
        Object[][] refused = {
            {PyFloat.of(1.5), "TypeError: 'float' object cannot be interpreted"},
            {PyStr.of("x"), "TypeError: 'str' object cannot be interpreted"},
            {PyNone.INSTANCE, "TypeError: 'NoneType' object cannot be interpreted"},
        };
        for (Object[] row : refused) {
            assertThatThrownBy(
                            () -> askedOf(PyStr.of("one\n"), (PyObject) row[0], PyStr.of("go: ")))
                    .as("%s is a descriptor", Slots.repr((PyObject) row[0]))
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refusal ->
                                    assertThat(refusal.getMessage())
                                            .startsWith((String) row[1])
                                            .endsWith("as an integer"));
            assertThat(written).isEmpty();
            assertThat(asked).containsExactly("err.flush", "in.fileno");
        }

        assertThatThrownBy(
                        () ->
                                askedOf(
                                        PyStr.of("one\n"),
                                        PyInt.of(BigInteger.valueOf(2).pow(70)),
                                        PyStr.of("go: ")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refusal ->
                                assertThat(refusal.getMessage())
                                        .isEqualTo(
                                                "OverflowError: Python int too large to convert"
                                                        + " to C long"));

        for (PyObject descriptor :
                new PyObject[] {
                    PyInt.of(-1), PyInt.ZERO, PyInt.of(2), PyInt.of(BigInteger.valueOf(2).pow(31))
                }) {
            assertThat(askedOf(PyStr.of("one\n"), descriptor, PyStr.of("go: ")))
                    .as("%s is a descriptor like any other", Slots.repr(descriptor))
                    .isEqualTo(PyStr.of("one"));
        }
    }

    /**
     * The three names, in the order they are read, and what a missing one costs.
     *
     * <p>{@code _PySys_GetRequiredAttr} (Objects/sysmodule.c:79) reads the sys dictionary for each name and
     * answers {@code lost sys.<name>} for the one that is not there — and for the one that is there and
     * holds {@code None}, in the same words. Nothing else of the call happens: no flush, no write, no read.
     */
    @Test
    void aStreamNameThatIsGoneOrNoneIsRefusedAlone() {
        for (String name : new String[] {"stdin", "stdout", "stderr"}) {
            for (int state = 0; state < 2; state++) {
                askedOf(PyStr.of("one\n"), PyInt.ZERO);
                if (state == 0) {
                    PyRuntime.setSysAttribute(name, PyNone.INSTANCE);
                } else {
                    PyRuntime.setSysAttribute(name, null);
                }
                asked.clear();
                written.clear();
                assertThatThrownBy(
                                () ->
                                        Slots.call(
                                                row("input"),
                                                new PyObject[] {PyStr.of("go: ")},
                                                null))
                        .as("%s %s", name, state == 0 ? "is None" : "is gone")
                        .isInstanceOfSatisfying(
                                PyRaise.class,
                                refused ->
                                        assertThat(refused.getMessage())
                                                .isEqualTo("RuntimeError: lost sys." + name));
                assertThat(asked).isEmpty();
                assertThat(written).isEmpty();
            }
        }
    }

    /**
     * The calling convention, settled before the names are read.
     *
     * <p>{@code input} is a METH_FASTCALL row with one positional-only parameter, so a second argument is
     * the count family's sentence and any keyword at all is refused by the same rule that refuses a name —
     * both before {@code sys.stdin} is asked for, which is what the empty log says. Unpacking nothing is an
     * argument list of length zero, and is the same call as naming nothing.
     */
    @Test
    void theCallingConventionIsAnsweredFirst() {
        assertThatThrownBy(
                        () ->
                                askedOf(
                                        PyStr.of("one\n"),
                                        PyInt.ZERO,
                                        PyStr.of("a"),
                                        PyStr.of("b")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "TypeError: input expected at most 1 argument, got"
                                                        + " 2"));
        assertThat(asked).isEmpty();
        assertThat(written).isEmpty();

        for (String keyword : new String[] {"prompt", "x"}) {
            assertThatThrownBy(() -> named(row("input"), keyword))
                    .as("%s named", keyword)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo(
                                                    "TypeError: input() takes no keyword"
                                                            + " arguments"));
        }

        assertThat(askedOf(PyStr.of("one\n"), PyInt.ZERO)).isEqualTo(PyStr.of("one"));
        assertThat(asked).containsExactly("err.flush", "in.fileno", "out.flush", "in.readline");
    }

    /**
     * The reading stream's own face, and the three ways a closed one divides.
     *
     * <p>The getters that ask about the descriptor refuse with the reference's sentence and no full stop,
     * the methods of the stream refuse the same sentence with one, and the plain members go on answering.
     * {@code writable()} of a closed <em>reading</em> stream is the exception: the reference refuses only
     * the getter of the direction the stream had, so this one still says False. Ahead of all of them comes
     * the unpacking and the conversion, which is why a closed stream still refuses {@code read(1.5)} in the
     * converter's words — the closed check is part of the body, and the body is not reached.
     */
    @Test
    void aClosedStreamDividesThreeWays() {
        PyStream stream = reading("one\n");
        assertThat(Slots.getAttr(stream, "mode")).isEqualTo(PyStr.of("r"));
        assertThat(call(stream, "flush")).isSameAs(PyNone.INSTANCE);
        assertThat(call(stream, "close")).isSameAs(PyNone.INSTANCE);
        assertThat(call(stream, "close")).isSameAs(PyNone.INSTANCE);

        for (String getter : new String[] {"readable", "fileno", "isatty"}) {
            assertThatThrownBy(() -> call(stream, getter))
                    .as("%s of a closed stream", getter)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo("ValueError: I/O operation on closed file"));
        }
        assertThatThrownBy(() -> call(stream, "flush"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("ValueError: I/O operation on closed file."));
        assertThatThrownBy(() -> call(stream, "readline"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("ValueError: I/O operation on closed file."));
        assertThatThrownBy(() -> call(stream, "write", PyStr.of("x")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("ValueError: I/O operation on closed file."));

        assertThat(call(stream, "writable")).isSameAs(PyBool.FALSE);
        assertThat(Slots.getAttr(stream, "name")).isEqualTo(PyStr.of("<stdin>"));
        assertThat(Slots.getAttr(stream, "errors")).isEqualTo(PyStr.of("surrogateescape"));
        assertThat(Slots.repr(stream))
                .isEqualTo("<_io.TextIOWrapper name='<stdin>' mode='r' encoding='utf-8'>");

        // Conversion runs ahead of the closed check, so these four are refused by their own converter and
        // never reach the full stop.
        Object[][] convertedFirst = {
            {
                "read",
                PyFloat.of(1.5),
                "TypeError: argument should be integer or None, not 'float'"
            },
            {
                "readlines",
                PyFloat.of(1.5),
                "TypeError: argument should be integer or None, not 'float'"
            },
            {"readline", PyFloat.of(1.5), "TypeError: 'float' object cannot be interpreted as an integer"},
            {
                "seek",
                new PyObject[] {PyInt.ZERO, PyStr.of("x")},
                "TypeError: 'str' object cannot be interpreted as an integer"
            },
        };
        for (Object[] row : convertedFirst) {
            PyObject[] arguments =
                    row[1] instanceof PyObject[] all ? all : new PyObject[] {(PyObject) row[1]};
            assertThatThrownBy(() -> Slots.call(Slots.getAttr(stream, (String) row[0]), arguments, null))
                    .as("%s of a closed stream is refused by its converter", row[0])
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused -> assertThat(refused.getMessage()).isEqualTo((String) row[2]));
        }
        for (String member : new String[] {"read", "readline", "readlines", "flush"}) {
            assertThatThrownBy(() -> call(stream, member))
                    .as("%s of a closed stream", member)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo("ValueError: I/O operation on closed file."));
        }
        assertThatThrownBy(() -> Slots.iterator(stream).nextOrNull())
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("ValueError: I/O operation on closed file."));
    }

    /**
     * The other direction of the same type, refused.
     *
     * <p>The reference calls this {@code io.UnsupportedOperation} — a type that is both an {@code OSError}
     * and a {@code ValueError}, and which this runtime files the same way in {@link ExceptionTypes} — and
     * each stream refuses only the direction it was not opened for: {@code not writable} off the reading one
     * and {@code not readable} off the writing one, in the stream's own methods and in the {@code _check}
     * questions alike. The closed check runs before either, so a stream that has been closed refuses the
     * other direction's write with {@code I/O operation on closed file.} rather than with {@code not
     * writable}.
     */
    @Test
    void theOtherDirectionOfAStreamIsRefusedInWords() {
        PyStream reading = reading("one\n");
        PyStream writing = new PyStream("<stdout>", System.out, 1, "backslashreplace", false);
        for (String member : new String[] {"write", "writelines"}) {
            assertThatThrownBy(
                            () ->
                                    call(
                                            reading,
                                            member,
                                            member.equals("write")
                                                    ? PyStr.of("x")
                                                    : PyList.of(PyStr.of("x"))))
                    .as("%s of a reading stream", member)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused -> {
                                assertThat(refused.getMessage())
                                        .isEqualTo("UnsupportedOperation: not writable");
                                assertThat(refused.value().getType())
                                        .isSameAs(ExceptionTypes.UNSUPPORTED_OPERATION);
                            });
        }
        for (String member : new String[] {"read", "readline", "readlines"}) {
            assertThatThrownBy(() -> call(writing, member))
                    .as("%s of a writing stream", member)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused -> {
                                assertThat(refused.getMessage())
                                        .isEqualTo("UnsupportedOperation: not readable");
                                assertThat(refused.value().getType())
                                        .isSameAs(ExceptionTypes.UNSUPPORTED_OPERATION);
                            });
        }
        assertThatThrownBy(() -> Slots.iterator(writing).nextOrNull())
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("UnsupportedOperation: not readable"));

        call(reading, "close");
        assertThatThrownBy(() -> call(reading, "write", PyStr.of("x")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo("ValueError: I/O operation on closed file."));
    }

    /**
     * {@code mode} is a dictionary entry, and the repr reads it.
     *
     * <p>The interpreter's own stream creation files the entry as it makes the stream and the type declares
     * no member of that name, so a program can rewrite it: {@code s.mode = 'z'} lands in the same
     * dictionary as any other attribute, both the read and the repr follow it, and a deleted entry leaves
     * the repr with no mode field at all. {@code s.__dict__} is the one dictionary, and it is not
     * replaceable.
     */
    @Test
    void modeLivesInTheStreamDictionary() {
        PyStream stream = reading("one\n");
        PyObject dictionary = Slots.getAttr(stream, "__dict__");
        assertThat(Slots.getAttr(stream, "__dict__")).isSameAs(dictionary);
        assertThat(Slots.repr(dictionary)).isEqualTo("{'mode': 'r'}");
        assertThat(Slots.getAttr(stream, "mode")).isEqualTo(PyStr.of("r"));
        assertThat(Slots.findAttr(PyStream.TYPE, "mode")).isNull();

        stream.setAttr("mode", PyStr.of("z"));
        assertThat(Slots.getAttr(stream, "mode")).isEqualTo(PyStr.of("z"));
        assertThat(Slots.repr(stream))
                .isEqualTo("<_io.TextIOWrapper name='<stdin>' mode='z' encoding='utf-8'>");

        stream.delAttr("mode");
        assertThatThrownBy(() -> Slots.getAttr(stream, "mode"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "AttributeError: '_io.TextIOWrapper' object has no"
                                                        + " attribute 'mode'"));
        assertThat(Slots.repr(stream))
                .isEqualTo("<_io.TextIOWrapper name='<stdin>' encoding='utf-8'>");

        assertThatThrownBy(() -> stream.setAttr("__dict__", PyDict.empty()))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "AttributeError: attribute '__dict__' of"
                                                        + " '_io._IOBase' objects is not writable"));
    }

    /**
     * A name of the program's own, and the seven the stream refuses.
     *
     * <p>An attribute written on a stream lands in the same dictionary as {@code mode} and is refused by
     * name once deleted. The members the reference really does file refuse a write in two sentences that
     * split by the kind of row: a getset with no setter names the attribute it refused, a {@code
     * Py_READONLY} field names nothing, and a delete reaches the same row with nothing handed over, so it
     * is refused in the same words as the write.
     */
    @Test
    void aStreamKeepsItsOwnAttributesAndRefusesSevenFiledNames() {
        PyStream stream = reading("one\n");
        stream.setAttr("spam", PyInt.ONE);
        assertThat(Slots.getAttr(stream, "spam")).isSameAs(PyInt.ONE);
        assertThat(Slots.repr(Slots.getAttr(stream, "__dict__")))
                .isEqualTo("{'mode': 'r', 'spam': 1}");
        stream.delAttr("spam");
        assertThatThrownBy(() -> Slots.getAttr(stream, "spam"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "AttributeError: '_io.TextIOWrapper' object has no"
                                                        + " attribute 'spam'"));

        for (String name : new String[] {"name", "closed", "errors"}) {
            assertThatThrownBy(() -> stream.setAttr(name, PyStr.of("x")))
                    .as("%s written", name)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo(
                                                    "AttributeError: attribute '"
                                                            + name
                                                            + "' of '_io.TextIOWrapper' objects is"
                                                            + " not writable"));
            assertThatThrownBy(() -> stream.delAttr(name))
                    .as("%s deleted", name)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo(
                                                    "AttributeError: attribute '"
                                                            + name
                                                            + "' of '_io.TextIOWrapper' objects is"
                                                            + " not writable"));
        }
        for (String name : new String[] {"encoding", "line_buffering", "write_through"}) {
            assertThatThrownBy(() -> stream.setAttr(name, PyStr.of("x")))
                    .as("%s written", name)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo("AttributeError: readonly attribute"));
            assertThatThrownBy(() -> stream.delAttr(name))
                    .as("%s deleted", name)
                    .isInstanceOfSatisfying(
                            PyRaise.class,
                            refused ->
                                    assertThat(refused.getMessage())
                                            .isEqualTo("AttributeError: readonly attribute"));
        }
    }

    /**
     * The name of the type the streams are, which is the name a message about them carries.
     *
     * <p>CPython writes the type down as {@code _io.TextIOWrapper} and splits {@code tp_name} on its last
     * dot for {@code __module__}, so a complaint about the value quotes the dotted name as well.
     */
    @Test
    void theStreamTypeIsFiledUnderIo() {
        assertThat(Slots.getAttr(PyStream.TYPE, "__module__")).isEqualTo(PyStr.of("_io"));
        assertThat(Slots.getAttr(PyStream.TYPE, "__name__")).isEqualTo(PyStr.of("TextIOWrapper"));
        assertThat(Slots.repr(PyStream.TYPE)).isEqualTo("<class '_io.TextIOWrapper'>");
        assertThat(reading("one\n").getType()).isSameAs(PyStream.TYPE);
        // The names the reading face is filed are all there; the buffer layer beneath it is not, and a
        // name only that layer declares is refused by the lookup rather than by the stream.
        assertThatThrownBy(() -> Slots.getAttr(reading("one\n"), "readinto"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "AttributeError: '_io.TextIOWrapper' object has no"
                                                        + " attribute 'readinto'"));
    }

    /**
     * A writing stream whose octets land in a buffer, which is the only way a test can read them back.
     *
     * <p>The stream is opened the way {@code sys.stdout} is — {@code backslashreplace}, unflushed — so the
     * only thing that differs from the real one is where the octets go.
     */
    private static final class Writer {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final PyStream stream =
                new PyStream(
                        "<stdout>",
                        new PrintStream(sink, true, StandardCharsets.UTF_8),
                        1,
                        "backslashreplace",
                        false);

        byte[] octets() {
            return sink.toByteArray();
        }

        PyObject write(String text) {
            return Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of(text)}, null);
        }

        /**
         * {@code stream.reconfigure(**pairs)}, the pairs being name and value in turn.
         *
         * <p>{@code reconfigure} takes no positional argument, so every value travels as a keyword: the
         * names are the tuple and the values are the positionals, which is the calling convention this
         * runtime gives a keyword call.
         */
        PyObject reconfigure(String... pairs) {
            int count = pairs.length / 2;
            PyObject[] values = new PyObject[count];
            PyObject[] keywords = new PyObject[count];
            for (int i = 0; i < count; i++) {
                keywords[i] = PyStr.interned(pairs[2 * i]);
                values[i] = PyStr.of(pairs[2 * i + 1]);
            }
            return Slots.call(
                    Slots.getAttr(stream, "reconfigure"), values, PyTuple.of(keywords));
        }
    }

    /**
     * The encoding {@code reconfigure} named is the one the next write encodes with.
     *
     * <p>Five characters leave as five octets under {@code latin-1}, the accented one as {@code 0xe9} —
     * where the UTF-8 the stream opened as would have written two. The count the write answers is
     * characters, which is what the reference's {@code write} answers regardless of codec.
     */
    @Test
    void aReconfiguredWriterEncodesWithItsNewCodec() {
        Writer writer = new Writer();
        writer.reconfigure("encoding", "latin-1");

        assertThat(Slots.str(writer.write("caf\u00e9\n"))).isEqualTo("5");
        assertThat(writer.octets()).isEqualTo(new byte[] {'c', 'a', 'f', (byte) 0xe9, '\n'});
        assertThat(Slots.str(Slots.getAttr(writer.stream, "encoding"))).isEqualTo("latin-1");
    }

    /**
     * A mark belongs to the encoder that writes it, and {@code reconfigure} builds a new encoder.
     *
     * <p>{@code utf-8-sig} carries its mark in the incremental encoder itself, so the empty write hands it
     * text and gets the mark back, and asking for the same codec again builds another encoder whose own
     * first write carries a second mark. {@code utf-16} is not like that: the window its mark depends on is
     * armed only for a descriptor that can seek, so a stream like this one — which cannot — writes the
     * native-order unit and no mark, before or after a {@code reconfigure}, and {@code utf-16-le} named
     * outright writes none either.
     */
    @Test
    void aReconfiguredEncoderWritesItsOwnMark() {
        Writer sig = new Writer();
        sig.reconfigure("encoding", "utf-8-sig");
        sig.write("");
        sig.write("a");
        sig.reconfigure("encoding", "utf-8-sig");
        sig.write("b");
        assertThat(sig.octets())
                .isEqualTo(
                        new byte[] {
                            (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a',
                            (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'b'
                        });

        Writer later = new Writer();
        later.write("a");
        later.reconfigure("encoding", "utf-16");
        later.write("b");
        later.reconfigure("encoding", "utf-16-le");
        later.write("c");
        assertThat(later.octets()).isEqualTo(new byte[] {'a', 'b', 0, 'c', 0});
    }

    /**
     * The handler is looked up when a character is refused, not when it is named, and a refused write
     * writes nothing.
     *
     * <p>{@code backslashreplace} and {@code replace} are the two text answers, so the ascii codec turns
     * them into octets of their own. {@code 'nope'} is stored without complaint and only the write that
     * needs it asks the registry; before either, the strict write that fails leaves the buffer exactly as
     * it was, because the whole text is encoded before the first octet goes out.
     */
    @Test
    void aReconfiguredWriterAnswersRefusalsWithItsNewHandler() {
        Writer writer = new Writer();
        writer.reconfigure("encoding", "ascii", "errors", "backslashreplace");
        writer.write("\u00e9\n");
        assertThat(writer.octets())
                .isEqualTo(new byte[] {'\\', 'x', 'e', '9', '\n'});

        writer.reconfigure("errors", "replace");
        writer.write("\u00e9\n");
        assertThat(writer.octets())
                .isEqualTo(new byte[] {'\\', 'x', 'e', '9', '\n', '?', '\n'});

        writer.reconfigure("encoding", "ascii");
        assertThatThrownBy(() -> writer.write("a\u00e9\n"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "UnicodeEncodeError: 'ascii' codec can't encode"
                                                    + " character '\\xe9' in position 1: ordinal not"
                                                    + " in range(128)"));
        assertThat(writer.octets()).hasSize(7);

        writer.reconfigure("errors", "nope");
        writer.write("plain");
        assertThatThrownBy(() -> writer.write("\u00e9"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "LookupError: unknown error handler name 'nope'"));
        assertThat(writer.octets()).hasSize(12);
    }

    /**
     * A buffer double: the rows {@code __init__} asks of a layer, and the answers it is built to give.
     *
     * <p>The five questions of a constructor are recorded in the order they are asked, which is what the
     * order of the first test is about; {@code read1} hands back {@code answer} once and then nothing, and
     * {@code write} records the octets it was handed. A null {@code answer} leaves {@code read1} out, which
     * is how a buffer that has none is spelled.
     */
    private static PyObject layer(
            boolean readable,
            boolean writable,
            boolean seekable,
            long tell,
            String answer,
            List<byte[]> written,
            List<String> asked) {
        PyModule buffer = new PyModule("buffer");
        buffer.set("name", PyStr.of("<buffer>"));
        buffer.set("closed", PyBool.FALSE);
        buffer.set(
                "readable",
                new PyNativeFunction(
                        "readable",
                        (args, kwNames) -> {
                            asked.add("readable");
                            return PyBool.of(readable);
                        }));
        buffer.set(
                "writable",
                new PyNativeFunction(
                        "writable",
                        (args, kwNames) -> {
                            asked.add("writable");
                            return PyBool.of(writable);
                        }));
        buffer.set(
                "seekable",
                new PyNativeFunction(
                        "seekable",
                        (args, kwNames) -> {
                            asked.add("seekable");
                            return PyBool.of(seekable);
                        }));
        buffer.set(
                "tell",
                new PyNativeFunction(
                        "tell",
                        (args, kwNames) -> {
                            asked.add("tell");
                            return PyInt.of(tell);
                        }));
        if (answer != null) {
            String[] pending = {answer};
            buffer.set(
                    "read1",
                    new PyNativeFunction(
                            "read1",
                            (args, kwNames) -> {
                                asked.add("read1");
                                byte[] octets = pending[0].getBytes(StandardCharsets.UTF_8);
                                pending[0] = "";
                                return PyBytes.of(octets);
                            }));
        }
        buffer.set(
                "write",
                new PyNativeFunction(
                        "write",
                        (args, kwNames) -> {
                            asked.add("write");
                            byte[] octets = ((PyBytes) args[0]).getValue();
                            written.add(octets);
                            return PyInt.of(octets.length);
                        }));
        buffer.set(
                "flush",
                new PyNativeFunction(
                        "flush",
                        (args, kwNames) -> {
                            asked.add("flush");
                            return PyNone.INSTANCE;
                        }));
        return buffer;
    }

    /** {@code stream.__init__(buffer, ...)}, which is the constructor body run a second time. */
    private static PyObject reinit(PyStream stream, PyObject... arguments) {
        return stream.reinitialize(arguments, null);
    }

    /**
     * A second {@code __init__} stores the buffer and rebuilds the codec, and clears what the last one
     * counted.
     *
     * <p>The stream starts over a Java descriptor and with {@code _CHUNK_SIZE} at three; the layer it is
     * handed is a real {@link PyBufferedReader}, so the read that follows crosses the layer rather than a
     * double. {@code latin-1} makes the accent one character where the UTF-8 it opened as would have
     * refused the octet, the handler and both flags are the ones passed, and the chunk size is the
     * reference's default again because the constructor clears it before a parameter can set it.
     */
    @Test
    void aSecondInitStoresTheBufferAndRebuildsTheCodec() {
        byte[] latin1 = {'c', 'a', 'f', (byte) 0xe9, '\n'};
        PyBufferedReader layer =
                new PyBufferedReader(
                        new PyFileIO(
                                "<stdin>", new ByteArrayInputStream(latin1), 0, false));
        PyStream stream = reading("unreached");
        chunk(stream, 3);

        assertThat(Slots.str(reinit(stream, layer, PyStr.of("latin-1"), PyStr.of("replace"))))
                .isEqualTo("None");
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("latin-1");
        assertThat(Slots.str(Slots.getAttr(stream, "errors"))).isEqualTo("replace");
        assertThat(Slots.str(Slots.getAttr(stream, "line_buffering"))).isEqualTo("False");
        assertThat(Slots.str(Slots.getAttr(stream, "write_through"))).isEqualTo("False");
        assertThat(Slots.getAttr(stream, "buffer")).isSameAs(layer);
        assertThat(Slots.str(Slots.getAttr(stream, "_CHUNK_SIZE"))).isEqualTo("8192");
        assertThat(Slots.str(Slots.getAttr(stream, "name"))).isEqualTo("<stdin>");
        assertThat(Slots.str(call(stream, "readable"))).isEqualTo("True");
        assertThat(Slots.str(call(stream, "writable"))).isEqualTo("False");
        assertThat(Slots.str(call(stream, "seekable"))).isEqualTo("False");
        assertThat(Slots.str(call(stream, "read"))).isEqualTo("caf\u00e9\n");
    }

    /**
     * The layer is asked its three directions in the constructor's order, and reads go through
     * {@code read1} when the layer has one.
     *
     * <p>{@code writable} after {@code readable} and {@code seekable} after both is the order the reference
     * asks them in, and it decides what the stream answers afterwards: the directions are remembered, not
     * asked again. The text is the layer's, handed over one chunk at a time and running out on the empty
     * answer.
     */
    @Test
    void aSecondInitAsksTheLayerItsDirectionsInOrder() {
        List<String> asked = new ArrayList<>();
        PyObject layer = layer(true, false, false, 0, "hi", new ArrayList<>(), asked);
        PyStream stream = reading("");

        reinit(stream, layer);

        assertThat(asked).containsExactly("readable", "writable", "seekable");
        assertThat(Slots.str(call(stream, "read", PyInt.of(2)))).isEqualTo("hi");
        assertThat(Slots.str(call(stream, "read", PyInt.of(2)))).isEqualTo("");
        assertThat(asked).containsExactly("readable", "writable", "seekable", "read1", "read1");
    }

    /** Both flags are the truth of whatever was passed, not the integer a stricter signature would take. */
    @Test
    void theTwoFlagsAreTheTruthOfWhateverWasPassed() {
        PyStream stream = reading("");
        PyObject layer = layer(true, false, false, 0, null, new ArrayList<>(), new ArrayList<>());

        reinit(stream, layer, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE, PyInt.of(1), PyStr.of("x"));
        assertThat(Slots.str(Slots.getAttr(stream, "line_buffering"))).isEqualTo("True");
        assertThat(Slots.str(Slots.getAttr(stream, "write_through"))).isEqualTo("True");

        reinit(stream, layer, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE, PyStr.of(""));
        assertThat(Slots.str(Slots.getAttr(stream, "line_buffering"))).isEqualTo("False");
        assertThat(Slots.str(Slots.getAttr(stream, "write_through"))).isEqualTo("False");
    }

    /**
     * A type refused by the parser leaves the stream as it was, and a value or a handler refused by the
     * impl leaves it torn with the fields it had.
     *
     * <p>{@code encoding} and {@code newline} are converted before the impl runs, so nothing moves:
     * {@code repr} still answers and the codec is still UTF-8. {@code errors} and an illegal
     * {@code newline} value are refused after {@code ok} was cleared, so every row that checks the state
     * refuses with the uninitialized sentence, while {@code encoding} still answers the old name and
     * {@code closed} still says True.
     */
    @Test
    void aRefusalBeforeTheClearLeavesTheOldFieldsButNoWorkingStream() {
        PyStream stream = reading("x");

        assertThatThrownBy(() -> reinit(stream, layer(true, false, false, 0, null, new ArrayList<>(), new ArrayList<>()), PyInt.of(1)))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).contains("argument 'encoding' must be str or None, not int"));
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("utf-8");
        assertThat(stream.repr()).contains("TextIOWrapper");

        assertThatThrownBy(() -> reinit(stream, layer(true, false, false, 0, null, new ArrayList<>(), new ArrayList<>()), PyNone.INSTANCE, PyNone.INSTANCE, PyInt.of(1)))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).contains("argument 'newline' must be str or None, not int"));
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("utf-8");

        assertThatThrownBy(() -> reinit(stream, layer(true, false, false, 0, null, new ArrayList<>(), new ArrayList<>()), PyNone.INSTANCE, PyNone.INSTANCE, PyStr.of("x")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).isEqualTo("ValueError: illegal newline value: x"));
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("utf-8");
        assertThat(Slots.str(Slots.getAttr(stream, "closed"))).isEqualTo("True");
        assertThatThrownBy(() -> stream.repr())
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).isEqualTo("ValueError: I/O operation on uninitialized object"));
        assertThatThrownBy(() -> Slots.getAttr(stream, "errors"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).isEqualTo("ValueError: I/O operation on uninitialized object"));

        reinit(stream, layer(true, false, false, 0, "ok", new ArrayList<>(), new ArrayList<>()));
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("utf-8");
        assertThat(Slots.str(call(stream, "read", PyInt.of(2)))).isEqualTo("ok");
    }

    /**
     * A name refused after the clear leaves no buffer to ask, and a buffer that is not an object is stored
     * before the first question reaches it.
     *
     * <p>The unknown name is looked up before the buffer is stored, so {@code buffer} itself refuses with
     * the uninitialized sentence; the saved layer is what puts the stream back. An integer buffer is
     * stored, and the encoding set, before {@code readable} is asked of it, so the stream answers the
     * integer for {@code buffer} and hears its own {@code AttributeError} from the questions aimed at it.
     */
    @Test
    void aRefusalAfterTheClearLeavesNoBufferOrTheWrongOne() {
        List<String> asked = new ArrayList<>();
        PyObject saved = layer(true, false, false, 0, "back", new ArrayList<>(), asked);
        PyStream stream = reading("");

        assertThatThrownBy(() -> reinit(stream, saved, PyStr.of("nope")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).isEqualTo("LookupError: unknown encoding: nope"));
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("None");
        assertThat(Slots.str(Slots.getAttr(stream, "closed"))).isEqualTo("True");
        assertThatThrownBy(() -> Slots.getAttr(stream, "buffer"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).isEqualTo("ValueError: I/O operation on uninitialized object"));

        reinit(stream, saved);
        assertThat(Slots.str(call(stream, "read", PyInt.of(4)))).isEqualTo("back");

        assertThatThrownBy(() -> reinit(stream, PyInt.of(1)))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).contains("AttributeError"));
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("utf-8");
        assertThat(Slots.str(Slots.getAttr(stream, "buffer"))).isEqualTo("1");
        assertThatThrownBy(() -> call(stream, "readable"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).contains("AttributeError"));
    }

    /**
     * A rebuilt writer encodes into its new buffer, and the newline is what flushes it.
     *
     * <p>{@code write} hands the layer octets rather than text, so the codec the constructor built is
     * visible in what arrives; {@code line_buffering} adds the flush after a text that holds a newline and
     * not after one that does not, while {@code write_through} writes immediately and leaves the flush to
     * the program — the reference's two flags differ in exactly that.
     */
    @Test
    void aRebuiltWriterEncodesIntoItsNewBuffer() {
        List<byte[]> written = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        PyObject layer = layer(false, true, false, 0, null, written, asked);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PyStream stream =
                new PyStream("<stdout>", new PrintStream(sink, true, StandardCharsets.UTF_8), 1, "backslashreplace", false);

        reinit(stream, layer, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE, PyInt.of(1));
        assertThat(Slots.str(Slots.getAttr(stream, "encoding"))).isEqualTo("utf-8");
        assertThat(Slots.str(call(stream, "write", PyStr.of("a\n")))).isEqualTo("2");
        assertThat(asked).containsExactly("readable", "writable", "seekable", "write", "flush");
        assertThat(written.get(0)).isEqualTo(new byte[] {'a', '\n'});
        assertThat(sink.toByteArray()).isEmpty();

        Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of("b")}, null);
        assertThat(asked).containsExactly("readable", "writable", "seekable", "write", "flush", "write");

        reinit(stream, layer, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE, PyInt.of(0), PyStr.of("1"));
        Slots.call(Slots.getAttr(stream, "write"), new PyObject[] {PyStr.of("c\n")}, null);
        assertThat(asked).containsExactly("readable", "writable", "seekable", "write", "flush", "write", "readable", "writable", "seekable", "write");
        assertThat(written.get(2)).isEqualTo(new byte[] {'c', '\n'});
    }

    /**
     * The mark {@code utf-16} writes belongs to the window the constructor looks through: a seekable layer
     * at position zero arms it, and one that is not at zero does not.
     *
     * <p>The two layers differ in nothing but what they answer {@code tell}. The first write of each is
     * the native-order unit with the mark in front for the armed one, and the same units without it for
     * the layer that was already past the start.
     */
    @Test
    void aRebuiltUtf16WriterWritesItsMarkOnlyAtTheStartOfASeekableLayer() {
        List<byte[]> atStart = new ArrayList<>();
        PyStream armed =
                new PyStream("<stdout>", new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), 1, "backslashreplace", false);
        reinit(armed, layer(false, true, true, 0, null, atStart, new ArrayList<>()), PyStr.of("utf-16"));
        Slots.call(Slots.getAttr(armed, "write"), new PyObject[] {PyStr.of("hi")}, null);
        assertThat(atStart.get(0)).isEqualTo(new byte[] {(byte) 0xff, (byte) 0xfe, 'h', 0, 'i', 0});

        List<byte[]> later = new ArrayList<>();
        PyStream unarmed =
                new PyStream("<stdout>", new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), 1, "backslashreplace", false);
        reinit(unarmed, layer(false, true, true, 5, null, later, new ArrayList<>()), PyStr.of("utf-16"));
        Slots.call(Slots.getAttr(unarmed, "write"), new PyObject[] {PyStr.of("hi")}, null);
        assertThat(later.get(0)).isEqualTo(new byte[] {'h', 0, 'i', 0});
    }
}
