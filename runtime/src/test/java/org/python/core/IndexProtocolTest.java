package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * The two steps CPython takes wherever it wants a number that addresses something, measured against
 * CPython 3.14.10.
 *
 * <p>Corpus case 108 walks the protocol from Python: what each of the forty-odd readers asks, and in whose
 * words it refuses. What is pinned here is the machinery a script cannot point at from outside. First the
 * gate: {@code _PyIndex_Check} is {@code _PyType_Lookup}, so the row {@code int} files for its own
 * {@code __index__} counts — {@link Specials#lookUp} reads class namespaces only and finds nothing, which
 * is why both steps read through {@link Specials#member} rather than one reading through the other and the
 * two disagreeing about whether {@code 3} is an index at all.
 *
 * <p>Then the four widths. {@code PyNumber_AsSsize_t} is handed the exception to refuse with, so the same
 * number is a {@code ValueError} in {@code bytes.__contains__}, an {@code OverflowError} about a
 * {@code ssize_t} in {@code split}'s count, an {@code OverflowError} about an index in a repeat, and no
 * refusal at all in the clipping form a slice bound uses — the last of those silently reading a number no
 * word holds as the widest number that does. A script sees one of the four per face and cannot put them
 * beside each other.
 *
 * <p>Then the two orders that a working program never reaches: a repeat whose product overflows is refused
 * as memory for the two sequences that keep pointers and as a sentence for the two that keep octets and
 * code points, and a {@code __len__} answer is judged negative <em>before</em> it is measured, so
 * {@code -(2 ** 70)} is refused for its sign. And the one error {@code bytes} swallows:
 * {@code _Py_bytes_contains} clears whatever its reading raised, while {@code parse_args_finds_byte} keeps
 * it, so the same {@code __index__} body is silent inside {@code in} and loud inside {@code find}.
 */
class IndexProtocolTest {

    private static final BigInteger WIDE = BigInteger.valueOf(2).pow(70);
    private static final PyObject THREE = PyInt.of(3);

    /** {@code type(name, bases, {members})}, with the bases handed over as Java has them. */
    private static PyType type(String name, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(PyType.OBJECT), dict}, null);
    }

    /** A {@code def __index__} that answers a fixed value, standing in for the Python-written one. */
    private static PyNativeFunction answers(PyObject value) {
        return method("__index__", value);
    }

    /** A member Java wrote, named for the slot a program would have written it under. */
    private static PyNativeFunction method(String name, PyObject value) {
        return new PyNativeFunction(name, (args, kwNames) -> value);
    }

    /** A {@code def __index__} whose body raises, which is the only way to watch a reader swallow it. */
    private static PyNativeFunction raises(String message) {
        return new PyNativeFunction(
                "__index__",
                (args, kwNames) -> {
                    throw Slots.valueError("%s", message);
                });
    }

    private static PyObject valueOf(PyType kind) {
        return kind.call(new PyObject[0], null);
    }

    /** The exception a call dies with, in CPython's own words and as its own class. */
    private static void refused(Runnable call, PyType kind, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised -> {
                            assertThat(raised.value().exceptionType()).isSameAs(kind);
                            assertThat(raised.value().str()).isEqualTo(message);
                        });
    }

    /** One method of a value, read through its type and then called the way an attribute call does. */
    private static PyObject run(PyObject receiver, String name, PyObject... arguments) {
        return Slots.call(Slots.getAttr(receiver, name), arguments, null);
    }

    /** A value of a class of one's own, with a {@code __index__} answering {@code number}. */
    private static PyObject indexing(BigInteger number, String kind) {
        return valueOf(type(kind, "__index__", answers(PyInt.of(number))));
    }

    @Test
    void theGateIsTheLookupThatAlsoFindsABuiltInRow() {
        // int files __index__ as a wrapper row (Objects/typeobject.c's slot table), not in a class body:
        // a PyClass-only search would fail the gate on the very type that defines what an index is.
        assertThat(Specials.lookUp(PyType.INT, "__index__")).isNull();
        assertThat(Specials.member(PyType.INT, "__index__")).isNotNull();
        assertThat(Specials.member(PyType.INT, "__index__").getType().name())
                .isEqualTo("wrapper_descriptor");
        assertThat(Numbers.isIndex(THREE)).isTrue();
        assertThat(Numbers.asIndex(THREE)).isEqualTo(BigInteger.valueOf(3));
        // bool is int's child and inherits the row, so True addresses a sequence in CPython too.
        assertThat(Numbers.isIndex(PyBool.TRUE)).isTrue();
        assertThat(Numbers.asIndex(PyBool.FALSE)).isEqualTo(BigInteger.ZERO);
        // The three that fail the gate fail it before anything is called.
        assertThat(Numbers.isIndex(PyStr.of("3"))).isFalse();
        assertThat(Numbers.isIndex(PyFloat.of(3.0))).isFalse();
        assertThat(Numbers.isIndex(PyNone.INSTANCE)).isFalse();
        // And a class object is not an index, since type files no such row.
        PyType klass = type("Indexable", "__index__", answers(THREE));
        assertThat(Numbers.isIndex(klass)).isFalse();
        assertThat(Numbers.isIndex(valueOf(klass))).isTrue();
    }

    @Test
    void anAnswerThatIsNotANumberIsRefusedByTheConverterNotTheCaller() {
        PyObject text = valueOf(type("AnswersText", "__index__", answers(PyStr.of("three"))));
        refused(
                () -> Numbers.asIndex(text),
                ExceptionTypes.TYPE_ERROR,
                "__index__ returned non-int (type str)");
        PyObject nothing = valueOf(type("AnswersNone", "__index__", answers(PyNone.INSTANCE)));
        refused(
                () -> Numbers.asIndex(nothing),
                ExceptionTypes.TYPE_ERROR,
                "__index__ returned non-int (type NoneType)");
        // The gate lets both through: it asks only whether the name is filed, never what it answers.
        assertThat(Numbers.isIndex(text)).isTrue();
        assertThat(Numbers.isIndex(nothing)).isTrue();
        // A body that raises is loud in every reading — the error is not the converter's to keep.
        PyObject raised = valueOf(type("Raises", "__index__", raises("inside __index__")));
        refused(() -> Numbers.asIndex(raised), ExceptionTypes.VALUE_ERROR, "inside __index__");
        refused(() -> Numbers.repeat(raised), ExceptionTypes.VALUE_ERROR, "inside __index__");
    }

    @Test
    void eachWidthRefusalNamesTheReaderThatAskedForIt() {
        BigInteger widest = BigInteger.valueOf(2).pow(63).subtract(BigInteger.ONE);
        BigInteger negative = BigInteger.valueOf(2).pow(63).negate();
        // A count of work: PyNumber_AsSsize_t(n, PyExc_OverflowError) with its own sentence.
        assertThat(Numbers.size(PyInt.of(widest))).isEqualTo(widest.longValue());
        assertThat(Numbers.size(PyInt.of(negative))).isEqualTo(negative.longValue());
        refused(
                () -> Numbers.size(PyInt.of(widest.add(BigInteger.ONE))),
                ExceptionTypes.OVERFLOW_ERROR,
                "Python int too large to convert to C ssize_t");
        // A count asked of a value: the same width, refused as a bad value instead.
        refused(
                () -> Numbers.count(PyInt.of(WIDE)),
                ExceptionTypes.VALUE_ERROR,
                "cannot fit 'int' into an index-sized integer");
        // A repeat: the same width as an OverflowError, in the index-sized words.
        refused(
                () -> Numbers.repeat(PyInt.of(WIDE)),
                ExceptionTypes.OVERFLOW_ERROR,
                "cannot fit 'int' into an index-sized integer");
        // A slice bound: clipped, so the widest number that does fit is what the caller reads.
        assertThat(Numbers.clipped(PyInt.of(WIDE))).isEqualTo(Long.MAX_VALUE);
        assertThat(Numbers.clipped(PyInt.of(WIDE.negate()))).isEqualTo(Long.MIN_VALUE);
        assertThat(Numbers.clipped(THREE)).isEqualTo(3L);
        // And the sentence names the class asked, not the index it answered with.
        PyObject big = valueOf(type("Big", "__index__", answers(PyInt.of(WIDE))));
        refused(
                () -> Numbers.repeat(big),
                ExceptionTypes.OVERFLOW_ERROR,
                "cannot fit 'Big' into an index-sized integer");
        // An index is still only an int, so a position is refused one word earlier than a count.
        assertThat(Numbers.fitsInt(widest)).isFalse();
        refused(
                () -> Numbers.cInteger(PyInt.of(BigInteger.valueOf(2).pow(31))),
                ExceptionTypes.OVERFLOW_ERROR,
                "Python int too large to convert to C int");
    }

    @Test
    void aRepeatTooLongIsRefusedByTheKindOfSequenceThatWasAsked() {
        // The product test is CPython's `input_size > PY_SSIZE_T_MAX / n`, and the two families refuse it
        // differently: list and tuple reach PyErr_NoMemory, str and bytes have their own sentences.
        PyObject[] pair = new PyObject[] {THREE, PyStr.of("b")};
        assertThat(Sequences.repeat(pair, 0)).isEmpty();
        assertThat(Sequences.repeat(pair, -1)).isEmpty();
        assertThat(Sequences.repeat(pair, 2)).hasSize(4);
        refused(
                () -> Sequences.scaled(2, Long.MAX_VALUE, "repeated string is too long"),
                ExceptionTypes.OVERFLOW_ERROR,
                "repeated string is too long");
        refused(
                () -> Sequences.scaled(3, Long.MAX_VALUE, "repeated bytes are too long"),
                ExceptionTypes.OVERFLOW_ERROR,
                "repeated bytes are too long");
        // With no sentence of its own a repeat is refused as memory — and a bare MemoryError, which is
        // what PyErr_NoMemory makes and the only one of these refusals with no text after the class.
        refused(() -> Sequences.scaled(2, Long.MAX_VALUE, null), ExceptionTypes.MEMORY_ERROR, "");
        // An empty sequence is the empty result before the count is even measured.
        assertThat(Sequences.scaled(0, Long.MAX_VALUE, null)).isZero();
        assertThat(Sequences.scaled(4, 0, "repeated string is too long")).isZero();
        // The multiplier is read by sequence_repeat, which refuses a non-index in its own words.
        refused(
                () -> Sequences.multiplier(PyFloat.of(2.0)),
                ExceptionTypes.TYPE_ERROR,
                "can't multiply sequence by non-int of type 'float'");
        refused(
                () -> Sequences.multiplier(valueOf(type("NoIndex", "other", THREE))),
                ExceptionTypes.TYPE_ERROR,
                "can't multiply sequence by non-int of type 'NoIndex'");
    }

    @Test
    void aLengthIsJudgedNegativeBeforeItIsMeasured() {
        // slot_sq_length: _PyNumber_Index, then the sign, then the width — so a number that is both too
        // wide and negative is refused for being negative, which is the order a program cannot pin.
        refused(
                () -> Slots.length(lengthing(PyInt.of(WIDE.negate()))),
                ExceptionTypes.VALUE_ERROR,
                "__len__() should return >= 0");
        refused(
                () -> Slots.length(lengthing(PyInt.of(-1))),
                ExceptionTypes.VALUE_ERROR,
                "__len__() should return >= 0");
        refused(
                () -> Slots.length(lengthing(PyInt.of(WIDE))),
                ExceptionTypes.OVERFLOW_ERROR,
                "cannot fit 'int' into an index-sized integer");
        // Always 'int', because what is named is the converted number rather than the answer that arrived.
        PyObject wide = lengthing(valueOf(type("AnswersWide", "__index__", answers(PyInt.of(WIDE)))));
        refused(
                () -> Slots.length(wide),
                ExceptionTypes.OVERFLOW_ERROR,
                "cannot fit 'int' into an index-sized integer");
        refused(
                () -> Slots.length(lengthing(PyStr.of("three"))),
                ExceptionTypes.TYPE_ERROR,
                "'str' object cannot be interpreted as an integer");
        assertThat(Slots.length(lengthing(PyInt.of(3)))).isEqualTo(PyInt.of(3));
    }

    /** A value whose {@code __len__} answers exactly this, which is what {@code slot_sq_length} reads. */
    private static PyObject lengthing(PyObject answer) {
        return new AnswersLength(answer);
    }

    /** The Java stand-in for a class body that wrote {@code def __len__}: the slot, and nothing else. */
    private static final class AnswersLength extends PyObject {
        private final PyObject answer;

        AnswersLength(PyObject answer) {
            this.answer = answer;
        }

        @Override
        public PyObject length() {
            return answer;
        }
    }

    @Test
    void theTwoSliceBoundRefusalsDifferByWhetherNoneWasAlreadyRead() {
        PyObject text = valueOf(type("NotAnIndex", "other", THREE));
        // _PyEval_SliceIndex: a slice bound may be None, and its refusal says so.
        refused(
                () -> Numbers.sliceIndex(text),
                ExceptionTypes.TYPE_ERROR,
                "slice indices must be integers or None or have an __index__ method");
        // _PyEval_SliceIndexNotNone, which only list.index and tuple.index read their bounds through.
        refused(
                () -> Numbers.searchIndex(text),
                ExceptionTypes.TYPE_ERROR,
                "slice indices must be integers or have an __index__ method");
        // Both take the same clipping form of the width, so no bound is ever refused for being wide.
        assertThat(Sequences.searchStart(indexing(WIDE, "Wide"), 4)).isEqualTo(4);
        assertThat(Sequences.indexStop(indexing(WIDE, "Wide"), 4)).isEqualTo(4);
        assertThat(Sequences.searchStart(indexing(WIDE.negate(), "Deep"), 4)).isZero();
        assertThat(Sequences.indexStop(PyNone.INSTANCE, 4)).isEqualTo(4);
        assertThat(Sequences.searchStart(THREE, 4)).isEqualTo(3);
    }

    @Test
    void bytesSwallowsTheOctetReadingWhileFindKeepsIt() {
        PyObject loud = valueOf(type("Raises", "__index__", raises("inside __index__")));
        // _Py_bytes_contains guards its step to the buffer with `PyErr_Clear()`, which names no class:
        // the octet is not asked for again, and the value is refused as the missing buffer instead.
        refused(
                () -> PyBytes.of(new byte[] {'a', 'b'}).contains(loud),
                ExceptionTypes.TYPE_ERROR,
                "a bytes-like object is required, not 'Raises'");
        // parse_args_finds_byte has no such clear, so the same body is heard in find.
        refused(
                () -> run(PyBytes.of(new byte[] {'a', 'b'}), "find", loud),
                ExceptionTypes.VALUE_ERROR,
                "inside __index__");
        // A number out of the octet range is a ValueError in both, and a clip reads as a big number.
        refused(
                () -> PyBytes.of(new byte[] {'a'}).contains(PyInt.of(256)),
                ExceptionTypes.VALUE_ERROR,
                "byte must be in range(0, 256)");
        // The clipping form reads a number no word holds as the widest number that does, and the octet
        // range is then refused as a value — the same answer a plain 256 gets.
        refused(
                () -> PyBytes.of(new byte[] {'a'}).contains(PyInt.of(WIDE)),
                ExceptionTypes.VALUE_ERROR,
                "byte must be in range(0, 256)");
        refused(
                () -> run(PyBytes.of(new byte[] {'a'}), "index", PyInt.of(-1)),
                ExceptionTypes.VALUE_ERROR,
                "byte must be in range(0, 256)");
        // A run of octets is found as a buffer, which is the other half of the same test.
        assertThat(PyBytes.of(new byte[] {'a', 'b'}).contains(PyBytes.of(new byte[] {'b'})))
                .isSameAs(PyBool.TRUE);
    }

    @Test
    void theBytesConstructorKeepsAnOverflowAndDropsATypeError() {
        // bytes_new_impl clears only a TypeError on its way from the integer branch to FromObject, so the
        // two refusals of one too-wide number differ by which converter read it.
        PyObject wide = valueOf(type("Wide", "__index__", answers(PyInt.of(WIDE))));
        refused(
                () -> PyType.BYTES.call(new PyObject[] {wide}, null),
                ExceptionTypes.OVERFLOW_ERROR,
                "cannot fit 'Wide' into an index-sized integer");
        PyObject loud = valueOf(type("Raises", "__index__", raises("inside __index__")));
        refused(
                () -> PyType.BYTES.call(new PyObject[] {loud}, null),
                ExceptionTypes.VALUE_ERROR,
                "inside __index__");
        // A bad answer is a TypeError, which is dropped, and the iterator path then blames the class.
        PyObject text = valueOf(type("AnswersText", "__index__", answers(PyStr.of("three"))));
        refused(
                () -> PyType.BYTES.call(new PyObject[] {text}, null),
                ExceptionTypes.TYPE_ERROR,
                "cannot convert 'AnswersText' object to bytes");
        refused(
                () -> PyType.BYTES.call(new PyObject[] {PyFloat.of(2.0)}, null),
                ExceptionTypes.TYPE_ERROR,
                "cannot convert 'float' object to bytes");
        // A negative count is refused by the constructor itself, and a wide one is not a count at all.
        refused(
                () -> PyType.BYTES.call(new PyObject[] {PyInt.of(-1)}, null),
                ExceptionTypes.VALUE_ERROR,
                "negative count");
        assertThat(PyType.BYTES.call(new PyObject[] {PyInt.of(3)}, null).getType())
                .isSameAs(PyType.BYTES);
        // The items of an iterable are read as indexes, and the range is the constructor's own.
        refused(
                () -> PyType.BYTES.call(new PyObject[] {PyList.of(PyInt.of(256))}, null),
                ExceptionTypes.VALUE_ERROR,
                "bytes must be in range(0, 256)");
        refused(
                () -> PyType.BYTES.call(new PyObject[] {PyList.of(text)}, null),
                ExceptionTypes.TYPE_ERROR,
                "__index__ returned non-int (type str)");
    }

    @Test
    void theBufferRowReadsItsFlagsAsAnIndexAndRefusesWidthInItsOwnWords() {
        PyObject empty = PyBytes.of(new byte[0]);
        // wrap_buffer takes the flags through PyNumber_AsSsize_t and then measures them against an int.
        refused(
                () -> run(empty, "__buffer__", PyInt.of(1)),
                ExceptionTypes.BUFFER_ERROR,
                "Object is not writable.");
        refused(
                () -> run(empty, "__buffer__", PyInt.of(BigInteger.valueOf(2).pow(31))),
                ExceptionTypes.OVERFLOW_ERROR,
                "buffer flags out of range");
        // One below the widest negative an int holds, since the test is against INT_MIN itself.
        refused(
                () ->
                        run(
                                empty,
                                "__buffer__",
                                PyInt.of(BigInteger.valueOf(2).pow(31).negate().subtract(BigInteger.ONE))),
                ExceptionTypes.OVERFLOW_ERROR,
                "buffer flags out of range");
        // A number one wider is refused a step earlier, in the ssize_t form's own words.
        PyObject wide = valueOf(type("Wide", "__index__", answers(PyInt.of(WIDE))));
        refused(
                () -> run(empty, "__buffer__", wide),
                ExceptionTypes.OVERFLOW_ERROR,
                "cannot fit 'Wide' into an index-sized integer");
        refused(
                () -> run(empty, "__buffer__", PyStr.of("r")),
                ExceptionTypes.TYPE_ERROR,
                "'str' object cannot be interpreted as an integer");
        // A string files no such row at all, which is the asymmetry the plan has carried since slice 11.
        assertThat(TypeDict.declared(PyType.BYTES).get(PyStr.interned("__buffer__"))).isNotNull();
        assertThat(TypeDict.declared(PyType.STR).get(PyStr.interned("__buffer__"))).isNull();
    }
}
