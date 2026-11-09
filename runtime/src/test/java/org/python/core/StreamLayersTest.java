package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The two layers under a reading stream, measured against CPython 3.14.10.
 *
 * <p>{@code m3/corpus/136_the_two_layers_under_a_reading_stream} walks the whole face over a pipe and
 * compares it answer for answer. Taken apart here is the order the layers ask one another in, which a
 * script sees only as a sentence: the buffer's guard is {@code CHECK_INITIALIZED} and lives in the
 * buffered body, so the rows inherited from {@code _IOBase} never reach it; the wrapper's guard is its
 * own and runs behind the flush it does first, which is why a buffer that has already let go of its
 * descriptor is refused by the flush's question and not by {@code detach}'s; and the descriptor is the
 * only one of the three that owns a closed flag, so the two flags a closed stream is asked about — the
 * derived {@code closed} and the {@code __IOBase_closed} filed in the dictionary — answer apart.
 *
 * <p>The source under the layers is a Java {@link InputStream} rather than a descriptor, which is what
 * a pipe is closest to: one {@code read(byte[])} per request, an answer as long as what came rather
 * than as long as what was asked, and an empty answer as the end. The 41 octets below are the ones the
 * corpus case reads, so a read of the store and a read of the descriptor answer different numbers here
 * too.
 */
class StreamLayersTest {

    /** The pipe case 136 reads: eight short lines, and no terminator on the last one. */
    private static final String OCTETS =
            "one\r\n" + "two\r\n" + "three\n" + "four\n" + "five\n" + "six\n" + "seven\n" + "eight";

    private static PyStream reading() {
        InputStream source = new ByteArrayInputStream(OCTETS.getBytes(StandardCharsets.UTF_8));
        return new PyStream("<stdin>", source, 0, "surrogateescape");
    }

    private static PyObject bufferOf(PyObject stream) {
        return Slots.getAttr(stream, "buffer");
    }

    private static PyObject rawOf(PyObject buffer) {
        return Slots.getAttr(buffer, "raw");
    }

    private static PyObject call(PyObject target, String row, PyObject... arguments) {
        return Slots.call(Slots.getAttr(target, row), arguments, null);
    }

    private static byte[] octets(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** What a row answers, in the words a program reads: the repr of the value, or the refusal. */
    private static String answered(PyObject target, String row, PyObject... arguments) {
        try {
            return Slots.repr(call(target, row, arguments));
        } catch (PyRaise refused) {
            return refused.getMessage();
        }
    }

    /** The same question asked of a name rather than of a row. */
    private static String read(PyObject target, String attribute) {
        try {
            return Slots.repr(Slots.getAttr(target, attribute));
        } catch (PyRaise refused) {
            return refused.getMessage();
        }
    }

    private static String written(PyObject target, String attribute, PyObject value) {
        try {
            target.setAttr(attribute, value);
            return "written";
        } catch (PyRaise refused) {
            return refused.getMessage();
        }
    }

    private static String deleted(PyObject target, String attribute) {
        try {
            target.delAttr(attribute);
            return "deleted";
        } catch (PyRaise refused) {
            return refused.getMessage();
        }
    }

    /** {@code iter(stream)}: the stream is its own iterator, or the row beneath refuses. */
    private static String iterated(PyObject target) {
        try {
            return Slots.iterator(target) == target ? "itself" : "another";
        } catch (PyRaise refused) {
            return refused.getMessage();
        }
    }

    /**
     * {@code next(stream)}: the line it hands back, or the {@code StopIteration} that ends the run.
     *
     * <p>The reference asks the iterator slot of the stream itself and never touches {@code iter} on the
     * way, which is what keeps the two refusals of a closed descriptor apart.
     */
    private static String advanced(PyObject target) {
        try {
            PyObject line = ((PyIterator) target).nextOrNull();
            return line == null ? "StopIteration: " : Slots.repr(line);
        } catch (PyRaise refused) {
            return refused.getMessage();
        }
    }

    /** The three objects, and the rows only the layer above has a name for. */
    @Test
    void everyLayerOfAReadingStreamIsAFaceOfTheOneUnderIt() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        assertThat(Slots.repr(buffer.getType())).isEqualTo("<class '_io.BufferedReader'>");
        assertThat(Slots.repr(raw.getType())).isEqualTo("<class '_io.FileIO'>");
        assertThat(buffer).isSameAs(bufferOf(stdin));
        assertThat(raw).isSameAs(rawOf(buffer));

        // The rows point down and not up: only a layer above has a name for the layer below it.
        assertThat(read(raw, "raw"))
                .isEqualTo("AttributeError: '_io.FileIO' object has no attribute 'raw'");
        assertThat(read(buffer, "buffer"))
                .isEqualTo("AttributeError: '_io.BufferedReader' object has no attribute 'buffer'");
        assertThat(read(stdin, "raw"))
                .isEqualTo("AttributeError: '_io.TextIOWrapper' object has no attribute 'raw'");
        // `closefd` is the descriptor's own field, and the layer above has no row that reaches it.
        assertThat(read(buffer, "closefd"))
                .isEqualTo("AttributeError: '_io.BufferedReader' object has no attribute 'closefd'");
        assertThat(read(raw, "closefd")).isEqualTo("False");

        // The three dictionaries say who owns what, and the three reprs print one stream in depth.
        assertThat(read(stdin, "__dict__")).isEqualTo("{'mode': 'r'}");
        assertThat(read(buffer, "__dict__")).isEqualTo("{}");
        assertThat(read(raw, "__dict__")).isEqualTo("{'name': '<stdin>'}");
        assertThat(stdin.repr())
                .isEqualTo("<_io.TextIOWrapper name='<stdin>' mode='r' encoding='utf-8'>");
        assertThat(buffer.repr()).isEqualTo("<_io.BufferedReader name='<stdin>'>");
        assertThat(raw.repr()).isEqualTo("<_io.FileIO name='<stdin>' mode='rb' closefd=False>");

        // State is asked down, so the two answers above the descriptor are the descriptor's answers.
        assertThat(read(raw, "mode")).isEqualTo("'rb'");
        assertThat(read(buffer, "mode")).isEqualTo("'rb'");
        assertThat(read(stdin, "mode")).isEqualTo("'r'");
        for (PyObject layer : new PyObject[] {raw, buffer, stdin}) {
            assertThat(read(layer, "closed")).isEqualTo("False");
            assertThat(read(layer, "name")).isEqualTo("'<stdin>'");
        }
    }

    /** One read of the descriptor fills the store, and each layer cuts the store its own way. */
    @Test
    void aReadOfTheStoreAndAReadOfTheDescriptorAnswerDifferentNumbers() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        // `read1(4)` asks the descriptor for four and answers four; the read under it is the *next* four,
        // because one call of the descriptor is one trip and gives no more than it was told to.
        assertThat(answered(buffer, "read1", PyInt.of(4))).isEqualTo("b'one\\r'");
        assertThat(answered(raw, "read", PyInt.of(4))).isEqualTo("b'\\ntwo'");
        // `peek(1)` only wants the store non-empty, so it brings in the rest of the pipe in one trip.
        assertThat(answered(buffer, "peek", PyInt.ONE))
                .isEqualTo("b'\\r\\nthree\\nfour\\nfive\\nsix\\nseven\\neight'");
        // What the store holds is cut without asking the descriptor again, and nothing consumes nothing.
        assertThat(answered(buffer, "read", PyInt.of(3))).isEqualTo("b'\\r\\nt'");
        assertThat(answered(buffer, "read", PyInt.ZERO)).isEqualTo("b''");
        assertThat(answered(buffer, "read1", PyInt.of(4))).isEqualTo("b'hree'");

        // The text layer above reads the characters those octet-level questions left behind.
        stdin.setAttr("_CHUNK_SIZE", PyInt.of(4));
        assertThat(answered(stdin, "read", PyInt.of(2))).isEqualTo("'\\nf'");
        assertThat(answered(stdin, "readline")).isEqualTo("'our\\n'");
        assertThat(answered(stdin, "readline", PyInt.of(3))).isEqualTo("'fiv'");
        assertThat(answered(stdin, "read", PyInt.of(-1))).isEqualTo("'e\\nsix\\nseven\\neight'");

        // And a dry descriptor answers the same nothing at every depth.
        assertThat(answered(stdin, "read")).isEqualTo("''");
        assertThat(answered(buffer, "read")).isEqualTo("b''");
        assertThat(answered(raw, "read")).isEqualTo("b''");
        assertThat(answered(buffer, "read1", PyInt.of(4))).isEqualTo("b''");
        assertThat(answered(buffer, "peek", PyInt.ONE)).isEqualTo("b''");
        assertThat(answered(buffer, "readlines")).isEqualTo("[]");
        assertThat(answered(stdin, "readlines")).isEqualTo("[]");
        // The stream is its own iterator, and an exhausted run ends the way `next` ends one.
        assertThat(iterated(buffer)).isEqualTo("itself");
        assertThat(iterated(raw)).isEqualTo("itself");
        assertThat(advanced(buffer)).isEqualTo("StopIteration: ");
        assertThat(advanced(raw)).isEqualTo("StopIteration: ");
    }

    /** Positions are refused in three vocabularies, because each layer asks in its own order. */
    @Test
    void eachLayerRefusesAPositionInWordsOfItsOwn() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        // The descriptor and the store above it both reach the operating system, which refuses a pipe.
        assertThat(answered(raw, "tell")).isEqualTo("OSError: [Errno 29] Illegal seek");
        assertThat(answered(buffer, "tell")).isEqualTo("OSError: [Errno 29] Illegal seek");
        // A whence is asked of the descriptor before anything else, so even 9 is the errno there.
        assertThat(answered(raw, "seek", PyInt.ZERO, PyInt.of(9)))
                .isEqualTo("OSError: [Errno 29] Illegal seek");
        // The buffer converts the whence itself and then asks whether the descriptor can seek at all.
        assertThat(answered(buffer, "seek", PyInt.ZERO, PyInt.of(9)))
                .isEqualTo("ValueError: whence value 9 unsupported");
        assertThat(answered(buffer, "seek", PyInt.ZERO, PyInt.of(3)))
                .isEqualTo("UnsupportedOperation: File or stream is not seekable.");
        assertThat(answered(buffer, "seek", PyInt.of(BigInteger.valueOf(2).pow(70))))
                .isEqualTo("UnsupportedOperation: File or stream is not seekable.");
        // The wrapper asks the question before it looks at the call, so every shape is one sentence.
        assertThat(answered(stdin, "seek", PyInt.ZERO))
                .isEqualTo("UnsupportedOperation: underlying stream is not seekable");
        assertThat(answered(stdin, "seek", PyInt.ZERO, PyBool.TRUE))
                .isEqualTo("UnsupportedOperation: underlying stream is not seekable");
        assertThat(answered(stdin, "tell"))
                .isEqualTo("UnsupportedOperation: underlying stream is not seekable");
        assertThat(answered(stdin, "seekable")).isEqualTo("False");
    }

    /** Readers refuse writing in the words of the row that asked, not of the stream. */
    @Test
    void readersRefuseWritingInThreeVocabularies() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        // `_IOBase.write` answers before it looks at anything, so the buffer's refusal is one word.
        assertThat(answered(buffer, "write", PyBytes.of(octets("x"))))
                .isEqualTo("UnsupportedOperation: write");
        assertThat(answered(buffer, "truncate")).isEqualTo("UnsupportedOperation: truncate");
        // The descriptor's own rows reach the mode, which has no 'w' in it.
        assertThat(answered(raw, "write", PyBytes.of(octets("x"))))
                .isEqualTo("UnsupportedOperation: File not open for writing");
        assertThat(answered(raw, "truncate")).isEqualTo("UnsupportedOperation: File not open for writing");
        // The value is converted before the mode is looked at, so a str is refused by its own type.
        assertThat(answered(raw, "write", PyStr.of("x")))
                .isEqualTo("TypeError: a bytes-like object is required, not 'str'");
        // Nothing to write is nothing to ask, so an empty run answers at both layers below the wrapper.
        assertThat(answered(buffer, "writelines", PyList.of())).isEqualTo("None");
        assertThat(answered(raw, "writelines", PyList.of())).isEqualTo("None");
        assertThat(answered(buffer, "writelines", PyList.of(PyBytes.of(octets("x")))))
                .isEqualTo("UnsupportedOperation: write");
        assertThat(answered(buffer, "writelines", PyNone.INSTANCE))
                .isEqualTo("TypeError: 'NoneType' object is not iterable");

        // The `_check` rows hand back the answer of the question they asked, and not a None.
        for (PyObject layer : new PyObject[] {raw, buffer, stdin}) {
            assertThat(answered(layer, "readable")).isEqualTo("True");
            assertThat(answered(layer, "writable")).isEqualTo("False");
            assertThat(answered(layer, "seekable")).isEqualTo("False");
            assertThat(answered(layer, "_checkReadable")).isEqualTo("True");
            assertThat(answered(layer, "_checkWritable"))
                    .isEqualTo("UnsupportedOperation: File or stream is not writable.");
            assertThat(answered(layer, "_checkSeekable"))
                    .isEqualTo("UnsupportedOperation: File or stream is not seekable.");
            assertThat(answered(layer, "_checkClosed")).isEqualTo("None");
            // One `_IOBase` row refuses all three streams and names the class it was reached through —
            // the same row the buffer and the descriptor answer, so a stream with no row of its own
            // would answer the dictionary of `object.__getstate__` instead.
            assertThat(answered(layer, "__getstate__"))
                    .startsWith("TypeError: cannot pickle '")
                    .endsWith("' instances");
        }
        assertThat(answered(stdin, "__getstate__", PyInt.ONE))
                .isEqualTo("TypeError: TextIOWrapper.__getstate__() takes no arguments (1 given)");
        assertThat(answered(raw, "__getstate__")).isEqualTo("TypeError: cannot pickle 'FileIO' instances");
        assertThat(answered(buffer, "__getstate__"))
                .isEqualTo("TypeError: cannot pickle 'BufferedReader' instances");
    }

    /** The name is a dictionary entry on the descriptor, and every repr above reads it through. */
    @Test
    void aNameWrittenOrDeletedOnTheDescriptorRewritesTheLayersAbove() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        assertThat(written(raw, "name", PyStr.of("other"))).isEqualTo("written");
        assertThat(read(raw, "__dict__")).isEqualTo("{'name': 'other'}");
        assertThat(raw.repr()).isEqualTo("<_io.FileIO name='other' mode='rb' closefd=False>");
        assertThat(read(buffer, "name")).isEqualTo("'other'");
        assertThat(read(stdin, "name")).isEqualTo("'other'");

        // Deleted, the descriptor falls back to the number it was opened from and the two layers above
        // lose the field: the attribute row the reprs use reads a missing name as no name to report.
        assertThat(deleted(raw, "name")).isEqualTo("deleted");
        assertThat(read(raw, "__dict__")).isEqualTo("{}");
        assertThat(raw.repr()).isEqualTo("<_io.FileIO fd=0 mode='rb' closefd=False>");
        assertThat(read(buffer, "name"))
                .isEqualTo("AttributeError: '_io.FileIO' object has no attribute 'name'");
        assertThat(read(stdin, "name"))
                .isEqualTo("AttributeError: '_io.FileIO' object has no attribute 'name'");
        assertThat(buffer.repr()).isEqualTo("<_io.BufferedReader>");
        assertThat(stdin.repr()).isEqualTo("<_io.TextIOWrapper mode='r' encoding='utf-8'>");

        // The getsets and the members refuse a write by the object asked, not by the object written.
        assertThat(written(raw, "closed", PyBool.FALSE))
                .isEqualTo("AttributeError: attribute 'closed' of '_io.FileIO' objects is not writable");
        assertThat(written(buffer, "mode", PyStr.of("wb")))
                .isEqualTo(
                        "AttributeError: attribute 'mode' of '_io.BufferedReader' objects is not"
                                + " writable");
        assertThat(written(stdin, "buffer", raw))
                .isEqualTo(
                        "AttributeError: attribute 'buffer' of '_io.TextIOWrapper' objects is not"
                                + " writable");
        assertThat(written(raw, "__dict__", PyDict.empty()))
                .isEqualTo(
                        "AttributeError: attribute '__dict__' of '_io._IOBase' objects is not writable");

        // `_finalizing` is a member rather than a getset, so it writes and refuses only a delete.
        assertThat(read(raw, "_finalizing")).isEqualTo("False");
        assertThat(written(raw, "_finalizing", PyBool.TRUE)).isEqualTo("written");
        assertThat(read(raw, "_finalizing")).isEqualTo("True");
        assertThat(deleted(raw, "_finalizing"))
                .isEqualTo("TypeError: can't delete numeric/char attribute");
    }

    /** The buffered guard lives in the buffered body, so the inherited rows answer their own answers. */
    @Test
    void aDetachedBufferRefusesItsOwnRowsAndAnswersTheInheritedOnes() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        assertThat(call(buffer, "detach")).isSameAs(raw);
        assertThat(read(buffer, "raw")).isEqualTo("None");

        for (String row :
                new String[] {
                    "read", "read1", "peek", "readline", "readlines", "truncate", "tell", "flush",
                    "close", "detach", "fileno", "isatty", "_checkClosed", "_checkReadable", "__enter__"
                }) {
            assertThat(answered(buffer, row))
                    .as("buffer.%s after detach", row)
                    .isEqualTo("ValueError: raw stream has been detached");
        }
        // The rows that ask something of the call answer it before the buffered body has said anything.
        assertThat(answered(buffer, "seek", PyInt.ZERO))
                .isEqualTo("ValueError: raw stream has been detached");
        assertThat(answered(buffer, "writelines", PyList.of(PyBytes.of(octets("x")))))
                .isEqualTo("ValueError: raw stream has been detached");
        assertThat(read(buffer, "name")).isEqualTo("ValueError: raw stream has been detached");
        assertThat(read(buffer, "mode")).isEqualTo("ValueError: raw stream has been detached");
        assertThat(read(buffer, "closed")).isEqualTo("ValueError: raw stream has been detached");
        assertThat(iterated(buffer)).isEqualTo("ValueError: raw stream has been detached");
        assertThat(advanced(buffer)).isEqualTo("ValueError: raw stream has been detached");

        // The four that never reach the buffered body: `_IOBase`'s rows, and the repr.
        assertThat(answered(buffer, "writable")).isEqualTo("False");
        assertThat(answered(buffer, "write", PyBytes.of(octets("x"))))
                .isEqualTo("UnsupportedOperation: write");
        assertThat(answered(buffer, "__getstate__"))
                .isEqualTo("TypeError: cannot pickle 'BufferedReader' instances");
        assertThat(buffer.repr()).isEqualTo("<_io.BufferedReader>");

        // The dictionary the layer owns is untouched by letting go of the descriptor.
        buffer.setAttr("_NOT_A_ROW", PyInt.ONE);
        assertThat(read(buffer, "__dict__")).isEqualTo("{'_NOT_A_ROW': 1}");
    }

    /** The wrapper's guard runs behind the flush it does first, which decides what it can still say. */
    @Test
    void aDetachedWrapperRefusesWhatAsksItsBufferAndKeepsItsOwnFields() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        assertThat(call(stdin, "detach")).isSameAs(buffer);

        // Every question that reaches the buffer is refused by the wrapper's own sentence, and the
        // buffer is gone rather than merely closed — so `seek` and `tell` never get to the
        // `underlying stream is not seekable` an attached stream answers.
        for (String row :
                new String[] {
                    "read", "readline", "readlines", "tell", "truncate", "flush", "readable", "writable",
                    "seekable", "fileno", "isatty", "_checkReadable", "_checkClosed", "detach",
                    "__enter__", "__exit__", "close"
                }) {
            assertThat(answered(stdin, row))
                    .as("wrapper.%s after detach", row)
                    .isEqualTo("ValueError: underlying buffer has been detached");
        }
        // The rows that take a value still refuse it in the wrapper's own words: the guard is the
        // wrapper's, but the flush that runs in front of it asks the buffer first and never gets there.
        assertThat(answered(stdin, "seek", PyInt.ZERO))
                .isEqualTo("ValueError: underlying buffer has been detached");
        assertThat(answered(stdin, "write", PyStr.of("x")))
                .isEqualTo("ValueError: underlying buffer has been detached");
        assertThat(answered(stdin, "writelines", PyList.of(PyStr.of("x"))))
                .isEqualTo("ValueError: underlying buffer has been detached");
        assertThat(iterated(stdin)).isEqualTo("ValueError: underlying buffer has been detached");
        assertThat(advanced(stdin)).isEqualTo("ValueError: underlying buffer has been detached");
        for (String attribute : new String[] {"name", "newlines", "_CHUNK_SIZE", "buffer", "closed"}) {
            assertThat(read(stdin, attribute))
                    .as("wrapper.%s after detach", attribute)
                    .isEqualTo("ValueError: underlying buffer has been detached");
        }
        assertThat(deleted(stdin, "_CHUNK_SIZE"))
                .isEqualTo(
                        "AttributeError: attribute '_CHUNK_SIZE' of '_io.TextIOWrapper' objects cannot"
                                + " be deleted");

        // What the stream reads from itself answers as it did before, and the repr loses only the name.
        assertThat(read(stdin, "mode")).isEqualTo("'r'");
        assertThat(read(stdin, "encoding")).isEqualTo("'utf-8'");
        assertThat(read(stdin, "errors")).isEqualTo("'surrogateescape'");
        assertThat(stdin.repr()).isEqualTo("<_io.TextIOWrapper mode='r' encoding='utf-8'>");
        assertThat(read(stdin, "__dict__")).isEqualTo("{'mode': 'r'}");
        assertThat(answered(stdin, "__getstate__"))
                .isEqualTo("TypeError: cannot pickle 'TextIOWrapper' instances");

        // The buffer did not go anywhere, and it has no idea the wrapper let go of it.
        assertThat(answered(buffer, "read", PyInt.of(4))).isEqualTo("b'one\\r'");
        assertThat(answered(buffer, "readline")).isEqualTo("b'\\n'");
        assertThat(read(buffer, "closed")).isEqualTo("False");
        assertThat(rawOf(buffer)).isSameAs(raw);
        assertThat(answered(buffer, "tell")).isEqualTo("OSError: [Errno 29] Illegal seek");
        assertThat(answered(buffer, "flush")).isEqualTo("None");
        assertThat(buffer.repr()).isEqualTo("<_io.BufferedReader name='<stdin>'>");

        // Detaching twice is refused, as is a wrapper that has closed what it no longer has.
        assertThat(answered(stdin, "detach")).isEqualTo("ValueError: underlying buffer has been detached");
        assertThat(answered(stdin, "close")).isEqualTo("ValueError: underlying buffer has been detached");
        assertThat(answered(stdin, "close", PyInt.ONE))
                .isEqualTo("TypeError: TextIOWrapper.close() takes no arguments (1 given)");
        // The buffer can still hand the descriptor over underneath the detached wrapper.
        assertThat(call(buffer, "detach")).isSameAs(raw);
        assertThat(answered(buffer, "read")).isEqualTo("ValueError: raw stream has been detached");
        assertThat(read(raw, "closed")).isEqualTo("False");
    }

    /** The closed flag a program reads and the one {@code _IOBase.close} files are asked apart. */
    @Test
    void closingTheDescriptorIsWhereTheTwoFlagsAreFiledApart() {
        PyStream stdin = reading();
        PyObject buffer = bufferOf(stdin);
        PyObject raw = rawOf(buffer);

        call(buffer, "detach");
        assertThat(answered(raw, "close")).isEqualTo("None");
        // The flag is filed next to the name, and a second close reads it and does nothing at all.
        assertThat(read(raw, "__dict__"))
                .isEqualTo("{'name': '<stdin>', '__IOBase_closed': True}");
        assertThat(read(raw, "closed")).isEqualTo("True");
        assertThat(raw.repr()).isEqualTo("<_io.FileIO [closed]>");
        assertThat(answered(raw, "close")).isEqualTo("None");
        // The buffer's own dictionary never sees the flag, because the buffer has no descriptor left.
        assertThat(read(buffer, "__dict__")).isEqualTo("{}");

        // The descriptor's rows refuse with its own sentence; the rows it inherits, with the base's.
        for (String row :
                new String[] {
                    "read", "readall", "readline", "truncate", "tell", "readable", "writable",
                    "seekable", "isatty", "fileno"
                }) {
            assertThat(answered(raw, row))
                    .as("raw.%s after close", row)
                    .isEqualTo("ValueError: I/O operation on closed file");
        }
        for (String row : new String[] {"readlines", "flush", "_checkClosed", "__enter__"}) {
            assertThat(answered(raw, row))
                    .as("raw.%s after close", row)
                    .isEqualTo("ValueError: I/O operation on closed file.");
        }
        // A row of the descriptor's own that a program hands a value to keeps the shorter sentence even
        // with the value in it, while `writelines` — an `_IOBase` row — answers with the full stop.
        assertThat(answered(raw, "write", PyBytes.of(octets("x"))))
                .isEqualTo("ValueError: I/O operation on closed file");
        assertThat(answered(raw, "seek", PyInt.ZERO))
                .isEqualTo("ValueError: I/O operation on closed file");
        assertThat(answered(raw, "writelines", PyList.of(PyBytes.of(octets("x")))))
                .isEqualTo("ValueError: I/O operation on closed file.");
        assertThat(iterated(raw)).isEqualTo("ValueError: I/O operation on closed file.");
        assertThat(advanced(raw)).isEqualTo("ValueError: I/O operation on closed file");
        assertThat(answered(raw, "write", PyStr.of("x")))
                .isEqualTo("TypeError: a bytes-like object is required, not 'str'");
        assertThat(answered(raw, "seek", PyInt.ZERO, PyStr.of("x")))
                .isEqualTo("TypeError: 'str' object cannot be interpreted as an integer");
        assertThat(answered(raw, "readinto", PyStr.of("x")))
                .isEqualTo("TypeError: readinto() argument 1 must be read-write bytes-like object, not str");
        // The name, the mode and the flag a program reads all still answer on a closed descriptor.
        assertThat(read(raw, "mode")).isEqualTo("'rb'");
        assertThat(read(raw, "name")).isEqualTo("'<stdin>'");
        assertThat(read(raw, "closefd")).isEqualTo("False");

        // The wrapper reads the descriptor's flag directly, so it notices the close its buffer never sees.
        assertThat(answered(buffer, "read")).isEqualTo("ValueError: raw stream has been detached");
        assertThat(read(stdin, "closed")).isEqualTo("ValueError: raw stream has been detached");
        assertThat(answered(stdin, "read")).isEqualTo("ValueError: I/O operation on closed file.");
        assertThat(answered(stdin, "tell")).isEqualTo("ValueError: I/O operation on closed file.");
        assertThat(stdin.repr()).isEqualTo("<_io.TextIOWrapper mode='r' encoding='utf-8'>");
    }

    /** A row a program files in the dictionary is the row the layer then runs. */
    @Test
    void aRowWrittenInTheDescriptorsDictionaryIsTheRowThatRuns() {
        PyStream stdin = reading();
        PyObject raw = rawOf(bufferOf(stdin));

        raw.setAttr("readable", new PyNativeFunction("readable", (args, kwNames) -> PyBool.FALSE));
        assertThat(answered(raw, "readable")).isEqualTo("False");
        // The `_check` row asks the stream's own row through Python, so it reports the refusal of the
        // answer rather than of the call.
        assertThat(answered(raw, "_checkReadable"))
                .isEqualTo("UnsupportedOperation: File or stream is not readable.");

        assertThat(deleted(raw, "readable")).isEqualTo("deleted");
        assertThat(answered(raw, "readable")).isEqualTo("True");
        assertThat(answered(raw, "_checkReadable")).isEqualTo("True");
    }
}
