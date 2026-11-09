package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code ascii} and the pass it runs over a repr's text, measured against CPython 3.14.10.
 *
 * <p>What the builtin answers is compared elsewhere: {@code m3/corpus/131_ascii_repr_rewritten} runs it
 * against the reference, escape for escape. Taken apart here is what the pass is a pass <em>over</em>. {@code
 * PyObject_ASCII} (Objects/object.c:840) asks for the repr once and hands back that very object when it is
 * already ASCII, and otherwise runs the text through the encoder's {@code backslashreplace} handler — the same
 * three widths that handler writes, four characters where two digits carry the value, six where four do, ten
 * where eight do, the digits always lowercase — and a character the repr had already escaped is ASCII by the
 * time this pass reaches it, so it is handed on unchanged.
 *
 * <p>That is why the answer is literal text rather than an escape: one backslash rather than a decoded
 * character, and a raw newline written by a program's own {@code __repr__} survives as a newline where the
 * non-ASCII neighbour beside it becomes six characters. The pass runs over text rather than over values, so
 * it reaches whatever the repr wrote: each element of a container brings its own text, and the {@code str} is
 * never asked — the reverse of what {@link PrintStreamTest} measures for {@code print}, and the same half
 * {@link DisplayhookTest} shows the displayhook writing, escaped here where the hook leaves it raw.
 *
 * <p>The row is a {@code METH_O} one: the argument is positional-only, and the calling family's own
 * sentences settle a count or a name before the repr is asked at all.
 */
class AsciiEscapeTest {

    /** A value whose repr writes exactly the text this test wants rewritten. */
    private static final class Says extends PyObject {

        private final String text;

        Says(String text) {
            this.text = text;
        }

        @Override
        public String repr() {
            return text;
        }
    }

    /** A repr that counts the times it was asked, and can be made to refuse. */
    private static final class Counted extends PyObject {

        int calls;
        boolean refuses;

        @Override
        public String repr() {
            if (refuses) {
                throw Slots.valueError("no text");
            }
            calls++;
            return "caf\u00e9";
        }

        @Override
        public String str() {
            return "the str, never asked";
        }
    }

    /**
     * The row as the running interpreter holds it.
     *
     * <p>Read off the module rather than off {@link Builtins#namespace()}: the module a builtin belongs to is
     * written onto it as the module is built, so a namespace rebuilt in a test answers {@code None} for {@code
     * __module__} where the interpreter's own row answers {@code builtins}.
     */
    private PyObject row() {
        return Slots.getAttr(PyRuntime.importModule("builtins"), "ascii");
    }

    /** {@code ascii(value)} read out as words. */
    private String escaped(PyObject value) {
        return ((PyStr) Slots.call(row(), new PyObject[] {value}, null)).getValue();
    }

    /**
     * The three widths, and where one stops and the next begins.
     *
     * <p>{@code 0x7f} is the last character that is handed on as it stands, {@code 0x80} and {@code 0xff} are
     * the ends of the two-digit escape, {@code 0x100} and {@code 0xffff} of the four-digit one, and
     * {@code 0x10000} and {@code 0x10ffff} of the eight-digit one. A character outside the plane reaches the
     * third width as one escape rather than as two, which is what the length says: ten characters beyond the
     * quotes, not two runs of six.
     */
    @Test
    void theWidthOfTheCharacterPicksTheEscape() {
        assertThat(escaped(PyStr.of("\u007f"))).isEqualTo("'\\x7f'");
        assertThat(escaped(PyStr.of("\u0080"))).isEqualTo("'\\x80'");
        assertThat(escaped(PyStr.of("\u00e9"))).isEqualTo("'\\xe9'");
        assertThat(escaped(PyStr.of("\u00ff"))).isEqualTo("'\\xff'");
        assertThat(escaped(PyStr.of("\u0100"))).isEqualTo("'\\u0100'");
        assertThat(escaped(PyStr.of("\u65e5"))).isEqualTo("'\\u65e5'");
        assertThat(escaped(PyStr.of("\uffff"))).isEqualTo("'\\uffff'");

        String planeOne = new String(Character.toChars(0x10000));
        String last = new String(Character.toChars(0x10FFFF));
        assertThat(escaped(PyStr.of(planeOne))).isEqualTo("'\\U00010000'");
        assertThat(escaped(PyStr.of(last))).isEqualTo("'\\U0010ffff'");
        assertThat(escaped(PyStr.of(planeOne)).length() - 2).isEqualTo(10);
        assertThat(escaped(PyStr.of(new String(Character.toChars(0x1f600)))))
                .as("one escape, not a pair of them")
                .isEqualTo("'\\U0001f600'");
        assertThat(escaped(PyStr.of("\u0041"))).isEqualTo("'A'");
    }

    /**
     * What the repr had escaped already is not escaped a second time.
     *
     * <p>Every answer here is the repr's own text, character for character, because the pass found nothing
     * above {@code 0x7f} in it: the newline had become {@code \n} and the byte {@code 0x85} had become {@code
     * \x85} before this builtin was reached. The reference keeps the repr <em>object</em> for such a value and
     * hands it back unwrapped; that identity is a difference of this runtime's, so the texts are compared.
     */
    @Test
    void aCharacterTheReprHadEscapedIsHandedOnUnchanged() {
        for (PyObject value :
                new PyObject[] {
                    PyStr.of("\n"),
                    PyStr.of("\t"),
                    PyStr.of("\\"),
                    PyStr.of("\u0000"),
                    PyStr.of("\u0085"),
                    PyStr.of("\u00a0"),
                    PyInt.ONE,
                    PyFloat.of(2.5),
                    PyNone.INSTANCE,
                    PyBytes.of(new byte[] {'c', (byte) 0xff}),
                }) {
            assertThat(escaped(value)).as("%s answers its own repr", value).isEqualTo(Slots.repr(value));
        }

        assertThat(escaped(PyStr.of("\n"))).isEqualTo("'\\n'");
        assertThat(escaped(PyStr.of("\u0085"))).isEqualTo("'\\x85'");
        assertThat(escaped(PyBytes.of(new byte[] {'c', (byte) 0xff}))).isEqualTo("b'c\\xff'");
    }

    /**
     * The pass runs on the text a repr wrote, so characters below the mark stay characters.
     *
     * <p>A {@code __repr__} that returns a raw newline hands that newline into the answer as it stands, next
     * to the escapes its non-ASCII neighbours became; the DEL too, which is a character rather than an escape
     * for {@code repr} of a program's own text. Quoting the answer is what shows the two kinds apart: the
     * newline is escaped by <em>that</em> pass and every backslash of an escape doubles.
     */
    @Test
    void charactersBelowTheMarkSurviveAsCharacters() {
        String answer = escaped(new Says("a\nb\u007fc\u00e9\u0100"));

        assertThat(answer).isEqualTo("a\nb\u007fc\\xe9\\u0100");
        assertThat(answer).contains("\n").contains("\u007f").doesNotContain("\u00e9");
        assertThat(Slots.repr(PyStr.of(answer))).isEqualTo("'a\\nb\\x7fc\\\\xe9\\\\u0100'");
        assertThat(answer.length()).isEqualTo(15);
    }

    /**
     * Each element brings its own text, and the container's punctuation is never touched.
     *
     * <p>The brackets, the commas and the quotes are ASCII and pass as they stood, so the only changes are
     * inside what each element's repr answered — a nested list included, since the inner repr is text in the
     * outer one before this pass begins. A value whose repr has no quotes of its own arrives without them,
     * beside a string of the same characters that arrives with: the escaping is the same because the text the
     * repr answered is the same apart from that.
     */
    @Test
    void everyElementContributesItsOwnText() {
        assertThat(escaped(PyList.of(PyStr.of("caf\u00e9"), PyStr.of("\u65e5\u672c"))))
                .isEqualTo("['caf\\xe9', '\\u65e5\\u672c']");

        PyDict held = PyDict.empty();
        held.put(PyStr.of("caf\u00e9"), PyStr.of("\u65e5"));
        assertThat(escaped(held)).isEqualTo("{'caf\\xe9': '\\u65e5'}");

        assertThat(escaped(PyList.of(PyList.of(PyStr.of("\u00e9"))))).isEqualTo("[['\\xe9']]");
        assertThat(escaped(PyList.of(new Says("raw\u00e9"), PyStr.of("raw\u00e9"))))
                .isEqualTo("[raw\\xe9, 'raw\\xe9']");
        assertThat(escaped(PyList.of(PyBytes.of(new byte[] {(byte) 0xff})))).isEqualTo("[b'\\xff']");
        assertThat(escaped(PyTuple.of(PyStr.of("'x'"), PyStr.of("it's")))).isEqualTo("(\"'x'\", \"it's\")");
    }

    /**
     * The repr is asked once per call, and whatever it refuses is what the caller hears.
     *
     * <p>{@code str} is never reached: the same value answers its own {@code str()} when that is asked for
     * directly and the escaped repr when {@code ascii} is asked. A repr that raises arrives at the caller
     * unchanged, because there is no second try to make and no text to escape.
     */
    @Test
    void theReprIsAskedOnceAndItsRefusalIsTheAnswer() {
        Counted value = new Counted();
        assertThat(escaped(value)).isEqualTo("caf\\xe9");
        assertThat(value.calls).isEqualTo(1);
        assertThat(escaped(value)).isEqualTo("caf\\xe9");
        assertThat(value.calls).isEqualTo(2);
        assertThat(Slots.str(value)).isEqualTo("the str, never asked");
        assertThat(value.calls).as("the str did not reach the repr").isEqualTo(2);

        Counted refusing = new Counted();
        refusing.refuses = true;
        assertThatThrownBy(() -> escaped(refusing))
                .isInstanceOfSatisfying(
                        PyRaise.class, refused -> assertThat(refused.getMessage()).isEqualTo("ValueError: no text"));
    }

    /** The argument is positional-only, and the counting family settles counts and names. */
    @Test
    void theCallingConventionRefusesBeforeTheReprIsAsked() {
        Counted value = new Counted();

        refuses(
                "TypeError: ascii() takes exactly one argument (0 given)",
                () -> Slots.call(row(), new PyObject[0], null));
        refuses(
                "TypeError: ascii() takes exactly one argument (2 given)",
                () -> Slots.call(row(), new PyObject[] {value, PyInt.ONE}, null));
        refuses(
                "TypeError: ascii() takes no keyword arguments",
                () -> Slots.call(row(), new PyObject[] {PyInt.ONE}, PyBoundMethod.keywordTuple(new String[] {"obj"})));
        assertThat(value.calls).as("no refusal reached the repr").isZero();

        // An empty unpacking names nothing, which the convention reads as no keyword at all.
        assertThat(Slots.call(row(), new PyObject[] {PyInt.ONE}, PyTuple.of())).isInstanceOf(PyStr.class);
    }

    /** The row is the one the reference's clinic block writes. */
    @Test
    void theRowCarriesItsOwnNameSignatureAndBook() {
        PyObject row = row();

        assertThat(Slots.getAttr(row, "__name__")).isEqualTo(PyStr.of("ascii"));
        assertThat(Slots.getAttr(row, "__qualname__")).isEqualTo(PyStr.of("ascii"));
        assertThat(Slots.getAttr(row, "__module__")).isEqualTo(PyStr.of("builtins"));
        assertThat(Slots.getAttr(row, "__text_signature__")).isEqualTo(PyStr.of("($module, obj, /)"));
        assertThat(((PyStr) Slots.getAttr(row, "__doc__")).getValue())
                // The clinic block writes each of the three markers doubled (Python/bltinmodule.c:415) and the
                // book the row answers with keeps it that way.
                .isEqualTo(
                        "Return an ASCII-only representation of an object.\n"
                                + "\n"
                                + "As repr(), return a string containing a printable representation of an\n"
                                + "object, but escape the non-ASCII characters in the string returned by\n"
                                + "repr() using \\\\x, \\\\u or \\\\U escapes. This generates a string similar\n"
                                + "to that returned by repr() in Python 2.");
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }
}
