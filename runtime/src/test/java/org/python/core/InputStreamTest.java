package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
 * <p>The descriptor is cut at a newline, and a {@code '\r'} that ends a line is translated into one rather
 * than answered as a line of its own: a pair split across two reads is still one line, because the stream
 * that held the first byte swallows the second. An empty answer is the end of the descriptor and becomes
 * {@code EOFError}, and exactly one character comes off the end of any other answer — the object itself
 * when there was nothing to cut, a fresh value when there was.
 *
 * <p>Four of the refusals the call makes are cleared and two are not. Both flushes are tried and their
 * errors dropped, as is a {@code fileno} that is missing or refuses when called; the conversion of what
 * that call answered is heard, in the conversion's own words, and arrives before a prompt has been
 * written. The three stream names are required in order, and the calling convention is settled before any
 * of them is read.
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
     * One descriptor, cut at its newlines — and at the {@code '\r'} the reference counts as one.
     *
     * <p>A line ends at the first {@code '\n'} or {@code '\r'} the read meets, and the terminator is
     * answered as part of the line, translated: a lone {@code '\r'} comes back as {@code '\n'}, and so does
     * the pair. What is left once the descriptor runs out is the empty text, the answer that means the end.
     */
    @Test
    void everyLineCarriesItsTerminator() {
        PyStream stream = reading("one\ntwo\r\nthree\rfour");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'one\\n'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'two\\n'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'three\\n'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("'four'");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("''");
        assertThat(Slots.repr(lineOf(stream))).isEqualTo("''");
    }

    /**
     * A {@code '\r\n'} is one line even when the two halves arrive apart.
     *
     * <p>The stream holds the {@code '\r'} rather than answering it, so the {@code '\n'} that follows it is
     * swallowed by the next read instead of turning up as the empty line a naive cut would hand back — which
     * is what the reference's newline translation costs, and what {@code input} then strips.
     */
    @Test
    void aHeldCarriageReturnIsNotALineOfItsOwn() {
        PyStream stream = reading("a\r\n\nb\n");
        assertThat(((PyStr) lineOf(stream)).getValue()).isEqualTo("a\n");
        assertThat(((PyStr) lineOf(stream)).getValue()).isEqualTo("\n");
        assertThat(((PyStr) lineOf(stream)).getValue()).isEqualTo("b\n");
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
     * the getter of the direction the stream had, so this one still says False.
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
    }

    /**
     * The other direction of the same type, refused.
     *
     * <p>The reference calls this {@code io.UnsupportedOperation}, a type that is both an {@code OSError}
     * and a {@code ValueError}; this runtime has no {@code io} module to hold it, so the refusal is a plain
     * {@code OSError} carrying the reference's sentence, and the {@code OSError} half is all a program can
     * read back.
     */
    @Test
    void theOtherDirectionOfAStreamIsRefusedInWords() {
        PyStream reading = reading("one\n");
        PyStream writing = new PyStream("<stdout>", System.out, 1, "backslashreplace", false);
        assertThatThrownBy(() -> call(reading, "write", PyStr.of("x")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> {
                            assertThat(refused.getMessage()).isEqualTo("OSError: not writable");
                            assertThat(refused.value().getType()).isSameAs(ExceptionTypes.OS_ERROR);
                        });
        assertThatThrownBy(() -> call(reading, "writelines", PyList.of(PyStr.of("x"))))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage()).isEqualTo("OSError: not writable"));
        assertThatThrownBy(() -> call(writing, "readline"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage()).isEqualTo("OSError: not readable"));
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
        assertThatThrownBy(() -> Slots.getAttr(reading("one\n"), "read"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused ->
                                assertThat(refused.getMessage())
                                        .isEqualTo(
                                                "AttributeError: '_io.TextIOWrapper' object has no"
                                                        + " attribute 'read'"));
    }
}
