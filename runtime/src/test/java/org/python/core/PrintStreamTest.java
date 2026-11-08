package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code print} and the stream it asks for, measured against CPython 3.14.10.
 *
 * <p>What a call leaves on the terminal is compared elsewhere: {@code m3/corpus/130_print_stream_and_pieces}
 * runs the builtin against the reference, piece for piece. Taken apart here is the order inside one call,
 * which a program sees only by asking a stream to remember what it was handed and when. {@code
 * builtin_print_impl} (Python/bltinmodule.c:2231) settles the stream before it looks at the separator, so a
 * deleted {@code sys.stdout} is a {@code lost sys.stdout} rather than a complaint about {@code sep}, and a
 * {@code sys.stdout} of {@code None} answers without writing anything — even when {@code sep} was an integer,
 * which is the one state where {@code print} is quieter than {@code sys.displayhook}, whose refusal of the
 * same two states {@link DisplayhookTest} measures beside this one.
 *
 * <p>Naming a {@code file} skips the read of the name altogether, and {@code file=None} is the same call as
 * naming nothing: the reference compares the argument against {@code Py_None} rather than asking whether one
 * was given. The four parameters are keyword-only, so an unknown name is refused by the calling convention
 * before the body is entered, and a keyword value arrives as a trailing entry of the argument array — which
 * is what {@link Arguments#positional} splits back off.
 */
class PrintStreamTest {

    /** A stream that keeps the pieces it was handed, in the order they arrived. */
    private static final class Recorder {

        final PyModule stream;
        final List<String> pieces = new ArrayList<>();
        final List<String> otherCalls = new ArrayList<>();

        Recorder(boolean withFlush) {
            stream = new PyModule("sink");
            stream.set(
                    "write",
                    new PyNativeFunction(
                            "write",
                            (args, kwNames) -> {
                                pieces.add(((PyStr) args[0]).getValue());
                                return PyNone.INSTANCE;
                            }));
            if (withFlush) {
                stream.set(
                        "flush",
                        new PyNativeFunction(
                                "flush",
                                (args, kwNames) -> {
                                    otherCalls.add("flush");
                                    return PyNone.INSTANCE;
                                }));
            }
        }
    }

    private PyObject printer() {
        return Builtins.namespace().get(PyStr.interned("print"));
    }

    /** One call: the positionals, then the keyword values, with the names that file them. */
    private PyObject printed(PyObject[] positional, PyObject[] keywordValues, String[] keywordNames) {
        PyObject[] arguments = new PyObject[positional.length + keywordValues.length];
        System.arraycopy(positional, 0, arguments, 0, positional.length);
        System.arraycopy(keywordValues, 0, arguments, positional.length, keywordValues.length);
        return Slots.call(
                printer(),
                arguments,
                keywordNames.length == 0 ? null : PyBoundMethod.keywordTuple(keywordNames));
    }

    /** {@code print(*values, file=stream)}. */
    private PyObject into(Recorder stream, PyObject... values) {
        return printed(values, new PyObject[] {stream.stream}, new String[] {"file"});
    }

    /**
     * {@code print(*values, name=value, ..., file=stream)}: the extra parameters are named ahead of {@code
     * file}, which is the order the row carries them in.
     */
    private PyObject intoWith(
            Recorder stream, PyObject[] values, PyObject[] keywordValues, String[] keywordNames) {
        PyObject[] allValues = new PyObject[keywordValues.length + 1];
        System.arraycopy(keywordValues, 0, allValues, 0, keywordValues.length);
        allValues[keywordValues.length] = stream.stream;
        String[] allNames = new String[keywordNames.length + 1];
        System.arraycopy(keywordNames, 0, allNames, 0, keywordNames.length);
        allNames[keywordNames.length] = "file";
        return printed(values, allValues, allNames);
    }

    @AfterEach
    void putTheStreamBack() {
        PyRuntime.setSysAttribute("stdout", PyRuntime.sysAttribute("__stdout__"));
    }

    /**
     * One call is several writes, and the pieces are not assembled first.
     *
     * <p>The reference calls the stream once per argument, once per separator and once for the ending, so
     * arguments and separators arrive interleaved rather than as one line. {@code print()} writes the newline
     * alone; an empty separator is still written, because the call is made before the text of it is looked at;
     * and a separator with nothing to separate is never reached, so {@code print(sep='X', end='Y')} writes one
     * piece. {@code None} for either parameter is the parameter's own default rather than an instruction to
     * print nothing.
     */
    @Test
    void everyArgumentSeparatorAndEndingIsItsOwnCall() {
        Recorder stream = new Recorder(true);
        into(stream, PyInt.ONE, PyStr.of("a"), PyList.of(PyInt.of(2)));
        assertThat(stream.pieces).containsExactly("1", " ", "a", " ", "[2]", "\n");

        stream.pieces.clear();
        intoWith(
                stream,
                new PyObject[] {PyInt.ONE, PyStr.of("a"), PyList.of(PyInt.of(2))},
                new PyObject[] {PyStr.of("|"), PyStr.of("!\n")},
                new String[] {"sep", "end"});
        assertThat(stream.pieces).containsExactly("1", "|", "a", "|", "[2]", "!\n");

        stream.pieces.clear();
        into(stream);
        assertThat(stream.pieces).containsExactly("\n");

        stream.pieces.clear();
        intoWith(
                stream,
                new PyObject[] {PyInt.ONE, PyInt.of(2), PyInt.of(3)},
                new PyObject[] {PyStr.of("")},
                new String[] {"sep"});
        assertThat(stream.pieces).containsExactly("1", "", "2", "", "3", "\n");

        stream.pieces.clear();
        intoWith(
                stream,
                new PyObject[0],
                new PyObject[] {PyStr.of("X"), PyStr.of("Y")},
                new String[] {"sep", "end"});
        assertThat(stream.pieces).containsExactly("Y");

        stream.pieces.clear();
        intoWith(
                stream,
                new PyObject[] {PyInt.ONE, PyInt.of(2)},
                new PyObject[] {PyNone.INSTANCE, PyNone.INSTANCE},
                new String[] {"sep", "end"});
        assertThat(stream.pieces).containsExactly("1", " ", "2", "\n");
    }

    /**
     * The text is what {@code str} answers, and it is asked for as each value is reached.
     *
     * <p>{@code Py_PRINT_RAW} is the difference between this call and {@code sys.displayhook}: the same string
     * arrives here as its own characters and there quoted, which is the quoted half of the pair {@link
     * DisplayhookTest} measures. A value whose own text refuses is refused <em>after</em> the separator that
     * precedes it has been written, and nothing after it arrives.
     */
    @Test
    void theTextIsStrAndItIsAskedForAsEachValueIsReached() {
        Recorder stream = new Recorder(true);
        into(stream, PyStr.of("plain"), PyStr.of("quoted"));
        assertThat(stream.pieces).containsExactly("plain", " ", "quoted", "\n");
        assertThat(Slots.str(PyStr.of("plain"))).isEqualTo("plain").isNotEqualTo(Slots.repr(PyStr.of("plain")));

        stream.pieces.clear();
        assertThatThrownBy(
                        () ->
                                into(
                                        stream,
                                        PyStr.of("first"),
                                        new Refusing(),
                                        PyStr.of("last")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).isEqualTo("ValueError: nope"));
        assertThat(stream.pieces).containsExactly("first", " ");

        stream.pieces.clear();
        assertThatThrownBy(
                        () ->
                                intoWith(
                                        stream,
                                        new PyObject[] {new Refusing(), PyStr.of("x")},
                                        new PyObject[] {PyStr.of("|")},
                                        new String[] {"sep"}))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        refused -> assertThat(refused.getMessage()).isEqualTo("ValueError: nope"));
        assertThat(stream.pieces).isEmpty();
    }

    /**
     * {@code flush} is judged for truth, and it is a second member called after the last write.
     *
     * <p>The clinic row converts the parameter with {@code PyObject_IsTrue}, so a non-empty string flushes and
     * an empty one does not, and the reference then reads {@code flush} off the stream and calls it with no
     * arguments once every piece of the line has gone.
     */
    @Test
    void flushIsJudgedForTruthAndCalledLast() {
        Recorder stream = new Recorder(true);
        intoWith(stream, new PyObject[] {PyStr.of("v")}, new PyObject[] {PyInt.ONE}, new String[] {"flush"});
        assertThat(stream.pieces).containsExactly("v", "\n");
        assertThat(stream.otherCalls).containsExactly("flush");

        for (PyObject falsy :
                new PyObject[] {PyStr.of(""), PyInt.ZERO, PyList.of(), PyNone.INSTANCE, PyFloat.of(0)}) {
            stream.pieces.clear();
            intoWith(stream, new PyObject[] {PyStr.of("v")}, new PyObject[] {falsy}, new String[] {"flush"});
        }
        assertThat(stream.otherCalls).as("only the first call flushed").hasSize(1);

        for (PyObject truthy : new PyObject[] {PyStr.of("x"), PyInt.ONE, PyList.of(PyInt.ONE)}) {
            stream.pieces.clear();
            stream.otherCalls.clear();
            intoWith(stream, new PyObject[] {PyStr.of("v")}, new PyObject[] {truthy}, new String[] {"flush"});
            assertThat(stream.pieces).containsExactly("v", "\n");
            assertThat(stream.otherCalls).containsExactly("flush");
        }
    }

    /**
     * The stream is used through its members, not its class.
     *
     * <p>An integer named as {@code file} is refused when the first piece is written — for {@code write} here,
     * for {@code flush} after a whole line has arrived there — and the line that was written before the second
     * refusal stays written. The recorder files its {@code write} the way a module files a name, so the refusal
     * about the missing {@code flush} is the module's own, which names the namespace it was asked about; the
     * same complaint from a Python class names the class, and the differential case covers that side.
     */
    @Test
    void aStreamIsRefusedForTheMemberItDoesNotHave() {
        Recorder bare = new Recorder(false);
        refuses(
                "AttributeError: module 'sink' has no attribute 'flush'",
                () ->
                        intoWith(
                                bare,
                                new PyObject[] {PyInt.ONE},
                                new PyObject[] {PyInt.ONE},
                                new String[] {"flush"}));
        assertThat(bare.pieces).containsExactly("1", "\n");

        refuses(
                "AttributeError: 'int' object has no attribute 'write'",
                () -> printed(new PyObject[] {PyInt.ONE}, new PyObject[] {PyInt.ZERO}, new String[] {"file"}));
        refuses(
                "AttributeError: 'int' object has no attribute 'write'",
                () -> printed(new PyObject[0], new PyObject[] {PyInt.ZERO}, new String[] {"file"}));
        assertThat(bare.pieces).hasSize(2);
    }

    /**
     * The name is read at the call, and a named {@code file} skips the read.
     *
     * <p>Two calls in a row can be answered by two different streams, which is how a program that swaps {@code
     * sys.stdout} between them sees the difference. {@code file=None} is the same call as naming nothing and so
     * reads the name; a stream named at the call is used as it stands, in whatever state the name is in.
     */
    @Test
    void theNameIsReadAtTheCallAndANamedFileSkipsIt() {
        Recorder first = new Recorder(true);
        Recorder second = new Recorder(true);
        PyRuntime.setSysAttribute("stdout", first.stream);
        printed(new PyObject[] {PyStr.of("one")}, new PyObject[0], new String[0]);
        PyRuntime.setSysAttribute("stdout", second.stream);
        printed(new PyObject[] {PyStr.of("two")}, new PyObject[0], new String[0]);
        assertThat(first.pieces).containsExactly("one", "\n");
        assertThat(second.pieces).containsExactly("two", "\n");

        Recorder named = new Recorder(true);
        PyRuntime.setSysAttribute("stdout", PyNone.INSTANCE);
        assertThat(into(named, PyInt.ONE)).isSameAs(PyNone.INSTANCE);
        assertThat(named.pieces).containsExactly("1", "\n");

        Recorder read = new Recorder(true);
        PyRuntime.setSysAttribute("stdout", read.stream);
        printed(new PyObject[] {PyInt.of(2)}, new PyObject[] {PyNone.INSTANCE}, new String[] {"file"});
        assertThat(read.pieces).containsExactly("2", "\n");
    }

    /**
     * The name is read before the separator is checked, and a missing name is the runtime's own refusal.
     *
     * <p>{@code _PySys_GetRequiredAttr} reads the module's dictionary, not its attributes, so taking the name
     * away leaves nothing for {@code print} to find and the sentence it writes is {@code lost sys.stdout} —
     * before {@code sep} has been looked at, which is why a call that would be refused for an integer
     * separator is refused for the stream instead. Naming a {@code file} in the same state answers normally,
     * and the separator check then does run.
     */
    @Test
    void aGoneNameIsRefusedBeforeTheSeparatorIsLookedAt() {
        Slots.delAttr(PyRuntime.importModule("sys"), "stdout");
        assertThat(PyRuntime.sysAttribute("stdout")).isNull();

        refuses(
                "RuntimeError: lost sys.stdout",
                () -> printed(new PyObject[] {PyInt.ONE}, new PyObject[0], new String[0]));
        refuses(
                "RuntimeError: lost sys.stdout",
                () -> printed(new PyObject[] {PyInt.ONE}, new PyObject[] {PyInt.of(3)}, new String[] {"sep"}));
        refuses(
                "RuntimeError: lost sys.stdout",
                () -> printed(new PyObject[0], new PyObject[0], new String[0]));
    }

    /**
     * A stream of {@code None} is silence rather than a refusal.
     *
     * <p>The early return sits above both the separator's and the ending's own check, so a call that would have
     * been refused for {@code sep=3} is answered here by nothing at all — the one state where this builtin is
     * quieter than {@code sys.displayhook}, which requires the name to be a stream. {@code print} still answers
     * {@code None}, which is what every call of it answers.
     */
    @Test
    void aStreamOfNoneIsSilenceRatherThanARefusal() {
        PyRuntime.setSysAttribute("stdout", PyNone.INSTANCE);

        assertThat(printed(new PyObject[] {PyInt.ONE}, new PyObject[0], new String[0]))
                .isSameAs(PyNone.INSTANCE);
        assertThat(printed(new PyObject[] {PyInt.ONE}, new PyObject[] {PyInt.of(3)}, new String[] {"sep"}))
                .isSameAs(PyNone.INSTANCE);
        assertThat(printed(new PyObject[] {PyInt.ONE}, new PyObject[] {PyStr.of("x")}, new String[] {"flush"}))
                .isSameAs(PyNone.INSTANCE);
        assertThat(printed(new PyObject[0], new PyObject[0], new String[0])).isSameAs(PyNone.INSTANCE);
    }

    /**
     * The two checks, and which of them comes first.
     *
     * <p>{@code sep} before {@code end}, and both before any write, so nothing reaches the stream from a call
     * that was turned away. The complaint names the type as {@code tp_name} does — bare, {@code int} and {@code
     * bytes} and {@code list} — while {@code None} is the default rather than a refusal.
     */
    @Test
    void theSeparatorIsCheckedBeforeTheEndingAndNeitherBeforeAWrite() {
        Recorder stream = new Recorder(true);

        for (PyObject[] pair :
                new PyObject[][] {
                    {PyInt.of(3), PyStr.of("int")},
                    {PyBytes.of(new byte[] {'-'}), PyStr.of("bytes")},
                    {PyList.of(PyStr.of("a")), PyStr.of("list")},
                    {PyTuple.of(PyStr.of("a")), PyStr.of("tuple")},
                    {PyFloat.of(1.5), PyStr.of("float")},
                }) {
            String name = ((PyStr) pair[1]).getValue();
            refuses(
                    "TypeError: sep must be None or a string, not " + name,
                    () -> intoWith(stream, new PyObject[] {PyInt.ONE}, new PyObject[] {pair[0]}, new String[] {"sep"}));
            refuses(
                    "TypeError: end must be None or a string, not " + name,
                    () -> intoWith(stream, new PyObject[] {PyInt.ONE}, new PyObject[] {pair[0]}, new String[] {"end"}));
        }

        refuses(
                "TypeError: sep must be None or a string, not int",
                () ->
                        intoWith(
                                stream,
                                new PyObject[] {PyInt.ONE},
                                new PyObject[] {PyInt.of(3), PyInt.of(4)},
                                new String[] {"sep", "end"}));
        assertThat(stream.pieces).isEmpty();
    }

    /** A name the row does not carry is refused by the convention, before the body is entered. */
    @Test
    void anUnknownNameIsRefusedByTheCallingConvention() {
        refuses(
                "TypeError: print() got an unexpected keyword argument 'other'",
                () -> printed(new PyObject[] {PyInt.ONE}, new PyObject[] {PyInt.of(2)}, new String[] {"other"}));

        // An empty unpacking names nothing, which the convention reads as no keyword at all.
        Recorder stream = new Recorder(true);
        PyRuntime.setSysAttribute("stdout", stream.stream);
        assertThat(Slots.call(printer(), new PyObject[] {PyInt.ONE}, PyTuple.of())).isSameAs(PyNone.INSTANCE);
        assertThat(stream.pieces).containsExactly("1", "\n");
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /** A value with no text to give. */
    private static final class Refusing extends PyObject {

        @Override
        public String str() {
            throw Slots.valueError("nope");
        }
    }
}
