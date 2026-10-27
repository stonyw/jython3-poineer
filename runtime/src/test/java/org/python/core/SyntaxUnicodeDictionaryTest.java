package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The eight dictionaries slice 36 files, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 79 to 84 read each table from Python. What is asserted here is the half a script reaches only
 * by accident. The first is <em>why</em> four of these eight file a {@code __new__} and four file none: {@code
 * type_ready_set_new} files the {@code tp_new} a static table names, so {@code UnicodeError} and its three
 * leaves — each of which declares {@code BaseException_new} beside its own {@code tp_init} — carry the row, and
 * carry it bound to themselves, while {@code SyntaxError} declares only an initialiser and so reads {@code
 * Exception}'s row, and the three classes under it declare neither. The second is that {@code SyntaxError}'s
 * table is the family's only one whose initialiser reads a <em>positional</em> and then reads a sequence out of
 * it: the nine fields are filled by two arguments rather than by names, the second of them parsed a second time
 * against a window of four to seven, and five items refused in the middle of that window rather than at either
 * end. The third is the two kinds of offset across the two tables: {@code SyntaxError} files nine {@code
 * _Py_T_OBJECT} pointers, so its {@code lineno} takes a string and reads {@code None} until filled, while the
 * leaves share the family's only {@code Py_T_PYSSIZET}s, which always read a number, refuse a delete, and refuse
 * a {@code float} in words no converter of the family uses. The fourth is the order the three codec sentences
 * read their fields in, which {@code UnicodeEncodeError_str} and its siblings settle once and for all: the
 * object is asked for last, so a broken {@code __str__} on the reason is the failure a program sees, and a
 * broken one on the encoding is a failure only two of the three can make.
 */
class SyntaxUnicodeDictionaryTest {

    /** The twelve names {@code SyntaxError} files: two rows, nine offsets, one sentence. */
    private static final List<String> SYNTAX_ORDER =
            List.of(
                    "__str__",
                    "__init__",
                    "msg",
                    "filename",
                    "lineno",
                    "offset",
                    "text",
                    "end_lineno",
                    "end_offset",
                    "print_file_and_line",
                    "_metadata",
                    "__doc__");

    /** The nine offsets of {@code SyntaxError_members}, all of them {@code _Py_T_OBJECT}. */
    private static final List<String> SYNTAX_FIELDS =
            List.of(
                    "msg",
                    "filename",
                    "lineno",
                    "offset",
                    "text",
                    "end_lineno",
                    "end_offset",
                    "print_file_and_line",
                    "_metadata");

    /** The nine names each of the three leaves files, its {@code __new__} first of all. */
    private static final List<String> UNICODE_ORDER =
            List.of(
                    "__new__",
                    "__str__",
                    "__init__",
                    "encoding",
                    "object",
                    "start",
                    "end",
                    "reason",
                    "__doc__");

    /** The five offsets of {@code UnicodeError_members}, the one table all three leaves read. */
    private static final List<String> UNICODE_FIELDS =
            List.of("encoding", "object", "start", "end", "reason");

    /** The three pointer offsets of that table, which take anything and read {@code None} until written. */
    private static final List<String> POINTER_FIELDS = List.of("encoding", "object", "reason");

    /** The two {@code Py_T_PYSSIZET} offsets, which always read a number and cannot be deleted at all. */
    private static final List<String> SIZE_FIELDS = List.of("start", "end");

    private static final String NEW_DOC =
            "Create and return a new object.  See help(type) for accurate signature.";

    private static final String INIT_DOC =
            "Initialize self.  See help(type(self)) for accurate signature.";

    private static final PyObject NONE = PyNone.INSTANCE;
    private static final PyObject TEXT = PyStr.of("a");
    private static final PyObject PATH = PyStr.of("/a/b/f.py");
    private static final PyObject CODEC = PyStr.of("utf-8");
    private static final PyObject ASCII = PyStr.of("ascii");
    private static final PyObject REASON = PyStr.of("n");
    private static final PyObject OCTETS = PyBytes.of(new byte[] {'x', (byte) 0xff, 'z'});
    private static final PyObject ZERO = PyInt.of(0);
    private static final PyObject ONE = PyInt.of(1);
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject FOUR = PyInt.of(4);
    private static final PyObject FIVE = PyInt.of(5);
    private static final PyObject NINE = PyInt.of(9);
    private static final PyObject WIDE = PyInt.of(BigInteger.TWO.pow(80));
    private static final PyObject SMALLEST = PyInt.of(BigInteger.TWO.pow(63).negate());

    /** The nine documents {@code SyntaxError_members} writes, in the order the table declares them. */
    private static final List<String> SYNTAX_DOCS =
            List.of(
                    "exception msg",
                    "exception filename",
                    "exception lineno",
                    "exception offset",
                    "exception text",
                    "exception end lineno",
                    "exception end offset",
                    "exception print_file_and_line",
                    "exception private metadata");

    /** {@code ('/a/b/f.py', 3, 5, 'text')}: the four-item detail every {@code SyntaxError} test starts from. */
    private static PyObject detail(PyObject... items) {
        return PyTuple.of(items);
    }

    /** The entry a type files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** The kind one filed entry reads as: {@code wrapper_descriptor}, {@code member_descriptor}, … */
    private static String kind(PyObject member) {
        return member.getType().name();
    }

    /** The names the window over a type's dictionary answers, in the order the dictionary kept them. */
    private static List<String> filed(PyType kind) {
        PyIterator keys =
                (PyIterator) ((PyMappingProxy) Slots.getAttr(kind, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** {@code dir(type)}: every name a read of the class answers, filed or borrowed. */
    private static List<String> visible(PyType kind) {
        PyList names =
                (PyList)
                        Slots.call(
                                Builtins.namespace().get(PyStr.interned("dir")),
                                new PyObject[] {kind},
                                null);
        List<String> seen = new ArrayList<>();
        for (int at = 0; at < names.size(); at++) {
            seen.add(((PyStr) names.at(at)).getValue());
        }
        return seen;
    }

    /** One name of a type's dictionary, called the way a program calls it: the receiver comes first. */
    private static PyObject call(PyType kind, String name, PyObject... arguments) {
        return Slots.call(entry(kind, name), arguments, null);
    }

    /** {@code Name(*arguments)}: the call a program makes, put through {@code type_call}. */
    private static PyObject make(PyType kind, PyObject... arguments) {
        return kind.call(arguments, null);
    }

    /** {@code Name(*head, n0=v0, …)}: a call with names, spelled the way the convention hands them. */
    private static PyObject given(PyType kind, PyObject[] head, String[] names, PyObject... values) {
        PyObject[] all = new PyObject[head.length + values.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(values, 0, all, head.length, values.length);
        return kind.call(all, names.length == 0 ? null : PyBoundMethod.keywordTuple(names));
    }

    /** {@code descriptor.__get__(receiver)}: the read half of a member row of the table. */
    private static PyObject getOf(PyType kind, String name, PyObject receiver) {
        return Slots.call(
                Slots.getAttr(entry(kind, name), "__get__"), new PyObject[] {receiver}, null);
    }

    /** {@code descriptor.__set__(receiver, value)}: the write half, reached without a read first. */
    private static void putOn(PyType kind, String name, PyObject receiver, PyObject value) {
        Slots.call(
                Slots.getAttr(entry(kind, name), "__set__"), new PyObject[] {receiver, value}, null);
    }

    /** {@code descriptor.__delete__(receiver)}: the third half, which a size offset refuses outright. */
    private static void takeOff(PyType kind, String name, PyObject receiver) {
        Slots.call(
                Slots.getAttr(entry(kind, name), "__delete__"), new PyObject[] {receiver}, null);
    }

    /** {@code Kind.__new__(Kind)}: the row that makes a value without ever running an initialiser. */
    private static PyObject fresh(PyType kind) {
        return Slots.call(Slots.getAttr(kind, "__new__"), new PyObject[] {kind}, null);
    }

    /** The same name read through a value and called, which is how a program reaches it. */
    private static PyObject run(PyObject receiver, String name, PyObject... arguments) {
        return Slots.call(Slots.getAttr(receiver, name), arguments, null);
    }

    /** The sentence one filed member answers {@code __doc__} with; {@code null} where it answers none. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == NONE ? null : ((PyStr) text).getValue();
    }

    /** One filed member's own {@code __qualname__}, which is the row's name and not the value's. */
    private static String named(PyType kind, String name) {
        return ((PyStr) Slots.getAttr(entry(kind, name), "__qualname__")).getValue();
    }

    /** {@code str(e)}: the text the class's own {@code tp_str} writes, or the family's. */
    private static String said(PyObject value) {
        return ((PyBaseException) value).str();
    }

    /** One of the fourteen offsets, read as the row reads it rather than as a dictionary entry. */
    private static PyObject field(PyObject value, String name) {
        return Slots.getAttr(value, name);
    }

    /** The instance dictionary a field write never touches, printed. */
    private static String dictOf(PyObject value) {
        return ((PyBaseException) value).held().repr();
    }

    /** {@code type(name, (base,), {})}, the three-argument creator asked for a class. */
    private static PyType classOf(String name, PyType base) {
        return (PyType)
                Classes.newType(
                        PyType.TYPE,
                        new PyObject[] {PyStr.of(name), PyTuple.of(base), PyDict.empty()},
                        null);
    }

    /** {@code TypeError}, {@code OverflowError} and friends in CPython's own words, which the runtime repeats. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    /** The value a call leaves in flight, which is the half of {@code PyRaise} a script has to catch. */
    private static PyBaseException caught(Throwing call) {
        try {
            call.run();
        } catch (PyRaise raised) {
            return raised.value();
        } catch (Throwable other) {
            throw new AssertionError("the call raised " + other, other);
        }
        throw new AssertionError("the call answered instead of raising");
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The eight tables, in the order {@code type_ready_fill_dict} merged the four C arrays into.
     *
     * <p>Four of the eight file a {@code __new__} and four file none, and the difference is what the static
     * table names: {@code UnicodeError} and its three leaves each declare {@code BaseException_new} beside their
     * own {@code tp_init}, so {@code type_ready_set_new} files the row before the merge begins and it stands
     * first of nine — bound to the class it belongs to, which is why four near-identical rows are four distinct
     * objects. {@code SyntaxError} declares only an initialiser, so its table opens with the two slot wrappers
     * and a read of {@code SyntaxError.__new__} answers {@code Exception}'s row; the three classes under it
     * declare neither, file one name each, and still count forty-two.
     */
    @Test
    void theEightTablesAreFiledInMergeOrderAndCountedAsCpythonCountsThem() {
        assertThat(filed(ExceptionTypes.SYNTAX_ERROR)).isEqualTo(SYNTAX_ORDER);
        assertThat(TypeDict.declared(ExceptionTypes.SYNTAX_ERROR).keys()).hasSize(12);
        assertThat(visible(ExceptionTypes.SYNTAX_ERROR)).hasSize(42);
        assertThat(entry(ExceptionTypes.SYNTAX_ERROR, "__new__")).isNull();
        assertThat(entry(ExceptionTypes.SYNTAX_ERROR, "__reduce__")).isNull();
        assertThat(entry(ExceptionTypes.SYNTAX_ERROR, "__getstate__")).isNull();

        // A row a class keeps is a row of the class that wrote it, and never copied down: the three classes
        // under `SyntaxError` file a sentence and nothing else, so all eleven other names are read out of
        // the table above them — including the two sizes and two of the pointers of a `SyntaxError` value.
        for (PyType below :
                List.of(
                        ExceptionTypes.INDENTATION_ERROR,
                        ExceptionTypes.TAB_ERROR,
                        ExceptionTypes.INCOMPLETE_INPUT_ERROR)) {
            assertThat(filed(below)).containsExactly("__doc__");
            assertThat(visible(below)).hasSize(42);
            assertThat(entry(below, "__new__")).isNull();
            assertThat(entry(below, "__str__")).isNull();
            assertThat(Slots.getAttr(below, "__str__")).isSameAs(entry(ExceptionTypes.SYNTAX_ERROR, "__str__"));
            assertThat(Slots.getAttr(below, "__init__"))
                    .isSameAs(entry(ExceptionTypes.SYNTAX_ERROR, "__init__"));
            assertThat(Slots.getAttr(below, "lineno")).isSameAs(entry(ExceptionTypes.SYNTAX_ERROR, "lineno"));
            assertThat(Slots.getAttr(below, "__new__"))
                    .isSameAs(entry(ExceptionTypes.EXCEPTION, "__new__"));
        }
        assertThat(Slots.getAttr(Slots.getAttr(ExceptionTypes.SYNTAX_ERROR, "__new__"), "__self__"))
                .isSameAs(ExceptionTypes.EXCEPTION);
        assertThat(((PyStr) Slots.getAttr(Slots.getAttr(ExceptionTypes.SYNTAX_ERROR, "__new__"), "__qualname__"))
                        .getValue())
                .isEqualTo("Exception.__new__");

        assertThat(filed(ExceptionTypes.UNICODE_ERROR)).containsExactly("__new__", "__doc__");
        assertThat(visible(ExceptionTypes.UNICODE_ERROR)).hasSize(33);
        assertThat(entry(ExceptionTypes.UNICODE_ERROR, "__str__")).isNull();
        assertThat(entry(ExceptionTypes.UNICODE_ERROR, "__init__")).isNull();
        assertThat(Slots.getAttr(ExceptionTypes.UNICODE_ERROR, "__str__"))
                .isSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__str__"));
        assertThat(Slots.getAttr(ExceptionTypes.UNICODE_ERROR, "__init__"))
                .isSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__init__"));

        for (PyType leaf :
                List.of(
                        ExceptionTypes.UNICODE_ENCODE_ERROR,
                        ExceptionTypes.UNICODE_DECODE_ERROR,
                        ExceptionTypes.UNICODE_TRANSLATE_ERROR)) {
            assertThat(filed(leaf)).isEqualTo(UNICODE_ORDER);
            assertThat(TypeDict.declared(leaf).keys()).hasSize(9);
            assertThat(visible(leaf)).hasSize(38);
            assertThat(entry(leaf, "__reduce__")).isNull();
            assertThat(entry(leaf, "__getstate__")).isNull();
            // `__new__` is a static method, so the class that filed it is bound into the row before the
            // merge: the read of the name answers the dictionary's own object, and not a fresh binding.
            assertThat(Slots.getAttr(leaf, "__new__")).isSameAs(entry(leaf, "__new__"));
            assertThat(Slots.getAttr(entry(leaf, "__new__"), "__self__")).isSameAs(leaf);
            assertThat(carried(leaf, "__new__")).isEqualTo(NEW_DOC);
        }
        assertThat(entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__new__"))
                .isNotSameAs(entry(ExceptionTypes.UNICODE_DECODE_ERROR, "__new__"));
        assertThat(entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__str__"))
                .isNotSameAs(entry(ExceptionTypes.UNICODE_DECODE_ERROR, "__str__"));
        assertThat(entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__init__"))
                .isNotSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__init__"));
        assertThat(Slots.getAttr(ExceptionTypes.UNICODE_ENCODE_ERROR, "__new__"))
                .isNotSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__new__"));

        // All three leaves read the one `PyMemberDef` table, so the translate class files an `encoding`
        // offset its sentence never reads and its initialiser never fills.
        assertThat(filed(ExceptionTypes.UNICODE_TRANSLATE_ERROR)).isEqualTo(UNICODE_ORDER);
        assertThat(entry(ExceptionTypes.UNICODE_TRANSLATE_ERROR, "encoding")).isNotNull();
    }

    /**
     * What each of the eight tables files a name as, and what that kind says about itself.
     *
     * <p>The {@code __new__} row is the only one of these names that is not a descriptor and not a sentence: it
     * is a {@code builtin_function_or_method} already bound to its class, and one of the few values of the
     * runtime that files no {@code __get__} at all — so {@code UnicodeEncodeError.__new__.__get__} is refused
     * where every other row of the two tables answers. The nine offsets of {@code SyntaxError} and the five of
     * the leaves all read {@code member_descriptor}, all hand out both halves, and all carry a document that
     * says what the offset holds; the one of the fourteen that breaks the pattern is {@code _metadata}, which
     * says {@code exception private metadata} because no program is meant to read it.
     */
    @Test
    void eachRowIsFiledAsTheKindCDeclaresItAsAndCarriesItsOwnSentence() {
        assertThat(kind(entry(ExceptionTypes.SYNTAX_ERROR, "__str__"))).isEqualTo("wrapper_descriptor");
        assertThat(kind(entry(ExceptionTypes.SYNTAX_ERROR, "__init__"))).isEqualTo("wrapper_descriptor");
        assertThat(entry(ExceptionTypes.SYNTAX_ERROR, "__str__").repr())
                .isEqualTo("<slot wrapper '__str__' of 'SyntaxError' objects>");
        assertThat(entry(ExceptionTypes.SYNTAX_ERROR, "__init__").repr())
                .isEqualTo("<slot wrapper '__init__' of 'SyntaxError' objects>");
        assertThat(entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__str__").repr())
                .isEqualTo("<slot wrapper '__str__' of 'UnicodeEncodeError' objects>");
        assertThat(named(ExceptionTypes.UNICODE_DECODE_ERROR, "__str__"))
                .isEqualTo("UnicodeDecodeError.__str__");
        assertThat(named(ExceptionTypes.UNICODE_DECODE_ERROR, "__init__"))
                .isEqualTo("UnicodeDecodeError.__init__");
        assertThat(carried(ExceptionTypes.SYNTAX_ERROR, "__str__")).isEqualTo("Return str(self).");
        assertThat(carried(ExceptionTypes.UNICODE_ENCODE_ERROR, "__str__")).isEqualTo("Return str(self).");
        assertThat(carried(ExceptionTypes.SYNTAX_ERROR, "__init__")).isEqualTo(INIT_DOC);
        assertThat(carried(ExceptionTypes.UNICODE_DECODE_ERROR, "__init__")).isEqualTo(INIT_DOC);
        assertThat(((PyStr) Slots.getAttr(entry(ExceptionTypes.SYNTAX_ERROR, "__str__"), "__name__"))
                        .getValue())
                .isEqualTo("__str__");

        for (String name : SYNTAX_FIELDS) {
            assertThat(kind(entry(ExceptionTypes.SYNTAX_ERROR, name)))
                    .as(name)
                    .isEqualTo("member_descriptor");
            assertThat(Slots.getAttr(entry(ExceptionTypes.SYNTAX_ERROR, name), "__get__"))
                    .as(name)
                    .isNotNull();
            assertThat(Slots.getAttr(entry(ExceptionTypes.SYNTAX_ERROR, name), "__set__"))
                    .as(name)
                    .isNotNull();
            assertThat(named(ExceptionTypes.SYNTAX_ERROR, name)).as(name)
                    .isEqualTo("SyntaxError." + name);
        }
        assertThat(entry(ExceptionTypes.SYNTAX_ERROR, "lineno").repr())
                .isEqualTo("<member 'lineno' of 'SyntaxError' objects>");
        assertThat(entry(ExceptionTypes.SYNTAX_ERROR, "msg").repr())
                .isEqualTo("<member 'msg' of 'SyntaxError' objects>");
        assertThat(documents(ExceptionTypes.SYNTAX_ERROR, SYNTAX_FIELDS)).isEqualTo(SYNTAX_DOCS);

        assertThat(kind(entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__new__")))
                .isEqualTo("builtin_function_or_method");
        assertThat(((PyStr) Slots.getAttr(entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__new__"), "__name__"))
                        .getValue())
                .isEqualTo("__new__");
        refuses(
                "AttributeError: 'builtin_function_or_method' object has no attribute '__get__'",
                () -> Slots.getAttr(entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__new__"), "__get__"));

        for (PyType leaf :
                List.of(
                        ExceptionTypes.UNICODE_ENCODE_ERROR,
                        ExceptionTypes.UNICODE_DECODE_ERROR,
                        ExceptionTypes.UNICODE_TRANSLATE_ERROR)) {
            for (String name : UNICODE_FIELDS) {
                assertThat(kind(entry(leaf, name))).as(leaf.name() + "." + name)
                        .isEqualTo("member_descriptor");
                assertThat(Slots.getAttr(entry(leaf, name), "__set__")).as(name).isNotNull();
            }
        }
        assertThat(entry(ExceptionTypes.UNICODE_DECODE_ERROR, "object").repr())
                .isEqualTo("<member 'object' of 'UnicodeDecodeError' objects>");
        assertThat(named(ExceptionTypes.UNICODE_DECODE_ERROR, "object"))
                .isEqualTo("UnicodeDecodeError.object");
        assertThat(documents(ExceptionTypes.UNICODE_DECODE_ERROR, UNICODE_FIELDS))
                .containsExactly(
                        "exception encoding",
                        "exception object",
                        "exception start",
                        "exception end",
                        "exception reason");
        // The translate class reads the same table, so its `encoding` offset carries the same sentence even
        // though nothing of the translate class ever fills or prints it.
        assertThat(carried(ExceptionTypes.UNICODE_TRANSLATE_ERROR, "encoding"))
                .isEqualTo("exception encoding");

        assertThat(((PyStr) entry(ExceptionTypes.SYNTAX_ERROR, "__doc__")).getValue())
                .isEqualTo("Invalid syntax.");
        assertThat(((PyStr) entry(ExceptionTypes.INDENTATION_ERROR, "__doc__")).getValue())
                .isEqualTo("Improper indentation.");
        assertThat(((PyStr) entry(ExceptionTypes.TAB_ERROR, "__doc__")).getValue())
                .isEqualTo("Improper mixture of spaces and tabs.");
        assertThat(((PyStr) entry(ExceptionTypes.INCOMPLETE_INPUT_ERROR, "__doc__")).getValue())
                .isEqualTo("incomplete input.");
        assertThat(((PyStr) entry(ExceptionTypes.UNICODE_ERROR, "__doc__")).getValue())
                .isEqualTo("Unicode related error.");
        assertThat(((PyStr) entry(ExceptionTypes.UNICODE_ENCODE_ERROR, "__doc__")).getValue())
                .isEqualTo("Unicode encoding error.");
        assertThat(((PyStr) entry(ExceptionTypes.UNICODE_DECODE_ERROR, "__doc__")).getValue())
                .isEqualTo("Unicode decoding error.");
        assertThat(((PyStr) entry(ExceptionTypes.UNICODE_TRANSLATE_ERROR, "__doc__")).getValue())
                .isEqualTo("Unicode translation error.");
        for (PyType kind :
                List.of(
                        ExceptionTypes.SYNTAX_ERROR,
                        ExceptionTypes.INDENTATION_ERROR,
                        ExceptionTypes.UNICODE_ENCODE_ERROR,
                        ExceptionTypes.UNICODE_TRANSLATE_ERROR)) {
            assertThat(kind(entry(kind, "__doc__"))).isEqualTo("str");
        }
    }

    /** The documents of one table's offsets, read in the order the table declares them. */
    private static List<String> documents(PyType kind, List<String> names) {
        List<String> said = new ArrayList<>();
        for (String name : names) {
            said.add(carried(kind, name));
        }
        return said;
    }

    /**
     * The initialiser that reads a positional and then reads a sequence out of it.
     *
     * <p>{@code PySyntaxError_init} is the family's only table that takes the message from a positional rather
     * than from a name: whatever the first argument is becomes {@code msg} — a list, a tuple, {@code None} — and
     * the second is handed to a second parse against four to seven items. That second parse runs on a tuple
     * unpack, so an {@code int} is refused as not iterable where a four-character string fills the four offsets
     * without complaint. The window refuses at both ends in {@code PyArg_ParseTuple}'s words — {@code function
     * takes at least 4 arguments (3 given)} and {@code at most 7 arguments (8 given)} — and one size in the
     * middle of it in its own: five items are refused as {@code end_offset must be provided when end_lineno is
     * provided}, the only sentence of the slice that says what is missing rather than how many. A third
     * positional leaves the second unread, and a re-initialiser with fewer arguments keeps the fields it was not
     * given.
     */
    @Test
    void theMessageComesFromAPositionalAndTheDetailFromASequenceInsideIt() {
        PyObject pair = make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(PATH, THREE, FIVE, TEXT));
        assertThat(field(pair, "msg")).isSameAs(TEXT);
        assertThat(field(pair, "filename")).isSameAs(PATH);
        assertThat(field(pair, "lineno").repr()).isEqualTo("3");
        assertThat(field(pair, "offset").repr()).isEqualTo("5");
        assertThat(field(pair, "text")).isSameAs(TEXT);
        assertThat(field(pair, "end_lineno")).isSameAs(NONE);
        assertThat(field(pair, "_metadata")).isSameAs(NONE);
        assertThat(((PyBaseException) pair).args().repr())
                .isEqualTo("('a', ('/a/b/f.py', 3, 5, 'a'))");
        assertThat(said(pair)).isEqualTo("a (f.py, line 3)");
        assertThat(pair.repr()).isEqualTo("SyntaxError('a', ('/a/b/f.py', 3, 5, 'a'))");

        assertThat(field(make(ExceptionTypes.SYNTAX_ERROR, TEXT), "filename")).isSameAs(NONE);
        // The message is kept whatever it is, and only the second of two positionals is read as a detail.
        PyObject listed = make(ExceptionTypes.SYNTAX_ERROR, PyList.of(ONE, TWO), detail(PATH, ONE, ONE, TEXT));
        assertThat(said(listed)).isEqualTo("[1, 2] (f.py, line 1)");
        assertThat(listed.repr()).isEqualTo("SyntaxError([1, 2], ('/a/b/f.py', 1, 1, 'a'))");
        PyObject tupled = make(ExceptionTypes.SYNTAX_ERROR, PyTuple.of(ONE, TWO));
        assertThat(said(tupled)).isEqualTo("(1, 2)");
        assertThat(said(make(ExceptionTypes.SYNTAX_ERROR, NONE))).isEqualTo("None");
        PyObject three = make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(PATH, ONE, TWO, TEXT), ONE);
        assertThat(field(three, "filename")).isSameAs(NONE);
        assertThat(((PyBaseException) three).args().repr()).isEqualTo("('a', ('/a/b/f.py', 1, 2, 'a'), 1)");
        assertThat(said(three)).isEqualTo("a");

        // Seven items are the whole struct, and the seventh is the field no sentence reads.
        PyObject seven =
                make(
                        ExceptionTypes.SYNTAX_ERROR,
                        TEXT,
                        detail(PATH, THREE, TWO, TEXT, NONE, NONE, PyStr.of("meta")));
        assertThat(field(seven, "_metadata").repr()).isEqualTo("'meta'");
        assertThat(field(seven, "print_file_and_line")).isSameAs(NONE);
        assertThat(said(seven)).isEqualTo("a (f.py, line 3)");
        PyObject asText = make(ExceptionTypes.SYNTAX_ERROR, TEXT, PyStr.of("abcd"));
        assertThat(
                        List.of(
                                field(asText, "filename"),
                                field(asText, "lineno"),
                                field(asText, "offset"),
                                field(asText, "text")))
                .containsExactly(PyStr.of("a"), PyStr.of("b"), PyStr.of("c"), PyStr.of("d"));
        assertThat(said(asText)).isEqualTo("a (a)");

        refuses(
                "TypeError: function takes at least 4 arguments (3 given)",
                () -> make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(ONE, TWO, THREE)));
        refuses(
                "TypeError: function takes at least 4 arguments (0 given)",
                () -> make(ExceptionTypes.SYNTAX_ERROR, TEXT, PyTuple.of()));
        refuses(
                "TypeError: end_offset must be provided when end_lineno is provided",
                () -> make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(PATH, THREE, TWO, TEXT, ONE)));
        refuses(
                "TypeError: function takes at most 7 arguments (8 given)",
                () ->
                        make(
                                ExceptionTypes.SYNTAX_ERROR,
                                TEXT,
                                detail(PATH, THREE, TWO, TEXT, ONE, TWO, THREE, FOUR)));
        refuses(
                "TypeError: 'int' object is not iterable",
                () -> make(ExceptionTypes.SYNTAX_ERROR, TEXT, FOUR));
        refuses(
                "TypeError: SyntaxError() takes no keyword arguments",
                () -> given(ExceptionTypes.SYNTAX_ERROR, new PyObject[] {TEXT}, new String[] {"msg"}, TEXT));
        refuses(
                "TypeError: SyntaxError() takes no keyword arguments",
                () -> given(ExceptionTypes.SYNTAX_ERROR, new PyObject[0], new String[] {"bad"}, ONE));

        // The refusal names the value's own class, because the row asks `_PyArg_NoKeywords` of
        // `Py_TYPE(self)` — and a class Python wrote inherits the whole table without copying it.
        PyType written = classOf("Broken", ExceptionTypes.SYNTAX_ERROR);
        refuses(
                "TypeError: Broken() takes no keyword arguments",
                () -> given(written, new PyObject[] {TEXT}, new String[] {"bad"}, ONE));
        PyObject inherited = make(written, TEXT);
        assertThat(field(inherited, "msg")).isSameAs(TEXT);
        assertThat(inherited.getType()).isSameAs(written);
        assertThat(inherited.repr()).isEqualTo("Broken('a')");
        assertThat(said(inherited)).isEqualTo("a");
        assertThat(entry(written, "__init__")).isNull();

        // Re-initialising fills from the new arguments and leaves the rest as the first call put them.
        PyObject again = make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(PATH, THREE, TWO, TEXT));
        call(ExceptionTypes.SYNTAX_ERROR, "__init__", again, PyStr.of("z"));
        assertThat(field(again, "msg").repr()).isEqualTo("'z'");
        assertThat(field(again, "filename")).isSameAs(PATH);
        assertThat(field(again, "lineno").repr()).isEqualTo("3");
        assertThat(((PyBaseException) again).args().repr()).isEqualTo("('z',)");
        call(ExceptionTypes.SYNTAX_ERROR, "__init__", again);
        assertThat(field(again, "msg").repr()).isEqualTo("'z'");
        assertThat(((PyBaseException) again).args().repr()).isEqualTo("()");

        refuses("TypeError: expected 0 arguments, got 1", () -> run(pair, "__str__", ONE));
        refuses("TypeError: expected 0 arguments, got 1", () -> call(ExceptionTypes.SYNTAX_ERROR, "__str__", pair, ONE));
        refuses(
                "TypeError: descriptor '__init__' requires a 'SyntaxError' object but received a 'int'",
                () -> call(ExceptionTypes.SYNTAX_ERROR, "__init__", FOUR));
        refuses(
                "TypeError: descriptor '__str__' requires a 'SyntaxError' object but received a 'AttributeError'",
                () ->
                        call(
                                ExceptionTypes.SYNTAX_ERROR,
                                "__str__",
                                make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT)));
        refuses(
                "TypeError: function takes at least 4 arguments (2 given)",
                () -> call(ExceptionTypes.SYNTAX_ERROR, "__init__", pair, PyStr.of("z"), detail(PATH, ONE)));
    }

    /**
     * The nine pointers of one table and the two sizes of the other, and the refusals only sizes make.
     *
     * <p>{@code SyntaxError_members} declares all nine of its offsets as {@code _Py_T_OBJECT}, which is why its
     * {@code lineno} will hold a string, why a field nobody filled, a field written {@code None} and a field
     * deleted are one thing to a read, and why none of the nine ever reaches {@code e.__dict__}. {@code
     * UnicodeError_members} declares two {@code Py_T_PYSSIZET}s, and {@code structmember}'s write of one is
     * {@code PyLong_AsSsize_t} on the object it was handed — which is why a {@code float}, a list and an object
     * that answers {@code __index__} are each refused as {@code an integer is required} rather than in the
     * numbered words of a
     * converter, why a {@code bool} is taken and read back as the number it stands for, why {@code 2**80}
     * overflows in the words of {@code ssize_t} rather than of an index, and why a delete is refused at all:
     * {@code can't delete numeric/char attribute}. A value made by {@code __new__} alone has never been written
     * at all, and its two sizes read {@code 0} where its three pointers read {@code None}.
     */
    @Test
    void theNinePointersOfOneTableAndTheTwoSizesOfTheOther() {
        PyObject value = make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(PATH, THREE, FIVE, TEXT));
        for (String name : SYNTAX_FIELDS) {
            Slots.setAttr(value, name, NONE);
            assertThat(field(value, name)).as(name).isSameAs(NONE);
            Slots.setAttr(value, name, TEXT);
            assertThat(field(value, name)).as(name).isSameAs(TEXT);
            Slots.delAttr(value, name);
            assertThat(field(value, name)).as(name).isSameAs(NONE);
            Slots.delAttr(value, name);
            assertThat(dictOf(value)).as(name).isEqualTo("{}");
        }
        // The two offsets a sentence reads as numbers are still pointers: a string written to `lineno` is
        // kept as one, and the sentence then answers without a line at all.
        PyObject textual = make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(PATH, THREE, FIVE, TEXT));
        PyObject line = PyStr.of("3");
        Slots.setAttr(textual, "lineno", line);
        assertThat(field(textual, "lineno")).isSameAs(line);
        assertThat(said(textual)).isEqualTo("a (f.py)");

        PyObject leaf = make(ExceptionTypes.UNICODE_DECODE_ERROR, CODEC, OCTETS, ONE, TWO, REASON);
        assertThat(field(leaf, "start").repr()).isEqualTo("1");
        assertThat(field(leaf, "end").repr()).isEqualTo("2");
        for (String name : POINTER_FIELDS) {
            Slots.setAttr(leaf, name, TWO);
            assertThat(field(leaf, name)).as(name).isEqualTo(TWO);
            Slots.setAttr(leaf, name, PyBool.of(true));
            assertThat(field(leaf, name)).as(name).isSameAs(PyBool.of(true));
            Slots.setAttr(leaf, name, NONE);
            assertThat(field(leaf, name)).as(name).isSameAs(NONE);
            Slots.delAttr(leaf, name);
            assertThat(field(leaf, name)).as(name).isSameAs(NONE);
            assertThat(dictOf(leaf)).as(name).isEqualTo("{}");
        }
        for (String name : SIZE_FIELDS) {
            Slots.setAttr(leaf, name, PyBool.of(true));
            assertThat(field(leaf, name).repr()).as(name).isEqualTo("1");
            assertThat(field(leaf, name).getType().name()).as(name).isEqualTo("int");
            Slots.setAttr(leaf, name, PyBool.of(false));
            assertThat(field(leaf, name).repr()).as(name).isEqualTo("0");
            Slots.setAttr(leaf, name, SMALLEST);
            assertThat(field(leaf, name).repr()).as(name).isEqualTo("-9223372036854775808");
            putOn(ExceptionTypes.UNICODE_DECODE_ERROR, name, leaf, FOUR);
            assertThat(field(leaf, name).repr()).as(name).isEqualTo("4");
            refuses("TypeError: an integer is required", () -> Slots.setAttr(leaf, name, TEXT));
            refuses("TypeError: an integer is required", () -> Slots.setAttr(leaf, name, PyFloat.of(1.0)));
            refuses("TypeError: an integer is required", () -> Slots.setAttr(leaf, name, PyList.of(ONE)));
            refuses("OverflowError: Python int too large to convert to C ssize_t", () -> Slots.setAttr(leaf, name, WIDE));
            refuses("TypeError: can't delete numeric/char attribute", () -> Slots.delAttr(leaf, name));
            refuses(
                    "TypeError: can't delete numeric/char attribute",
                    () -> takeOff(ExceptionTypes.UNICODE_DECODE_ERROR, name, leaf));
        }

        PyObject untouched = fresh(ExceptionTypes.UNICODE_ENCODE_ERROR);
        assertThat(field(untouched, "start").repr()).isEqualTo("0");
        assertThat(field(untouched, "end").repr()).isEqualTo("0");
        assertThat(field(untouched, "encoding")).isSameAs(NONE);
        assertThat(field(untouched, "object")).isSameAs(NONE);
        assertThat(field(untouched, "reason")).isSameAs(NONE);
        assertThat(said(untouched)).isEmpty();
        assertThat(untouched.repr()).isEqualTo("UnicodeEncodeError()");
        PyObject madeNotWritten = fresh(ExceptionTypes.SYNTAX_ERROR);
        assertThat(field(madeNotWritten, "msg")).isSameAs(NONE);
        assertThat(field(madeNotWritten, "lineno")).isSameAs(NONE);
        assertThat(said(madeNotWritten)).isEqualTo("None");
        assertThat(dictOf(madeNotWritten)).isEqualTo("{}");

        refuses(
                "TypeError: descriptor 'lineno' for 'SyntaxError' objects doesn't apply to a 'int' object",
                () -> getOf(ExceptionTypes.SYNTAX_ERROR, "lineno", FOUR));
        refuses(
                "TypeError: descriptor 'object' for 'UnicodeDecodeError' objects doesn't apply to a 'str' object",
                () -> getOf(ExceptionTypes.UNICODE_DECODE_ERROR, "object", TEXT));
        refuses(
                "TypeError: descriptor 'start' for 'UnicodeDecodeError' objects doesn't apply to a 'int' object",
                () -> getOf(ExceptionTypes.UNICODE_DECODE_ERROR, "start", FOUR));
        refuses(
                "TypeError: cannot set 'start' attribute of immutable type 'UnicodeDecodeError'",
                () -> Slots.setAttr(ExceptionTypes.UNICODE_DECODE_ERROR, "start", ONE));
        refuses(
                "TypeError: cannot set 'msg' attribute of immutable type 'SyntaxError'",
                () -> Slots.setAttr(ExceptionTypes.SYNTAX_ERROR, "msg", TEXT));
    }

    /**
     * The text {@code SyntaxError_str} writes, and the two fields it reads to write it.
     *
     * <p>Three shapes and one of them is not a shape: the message is always there, {@code filename} counts only
     * as a {@code str} and is then cut at its last separator, and {@code lineno} counts only as <em>exactly</em>
     * an {@code int}, so a {@code bool} and a {@code float} are not one and never earn a line. With neither of
     * the two the function answers {@code PyObject_Str(self->msg)} — the message's own text, and not {@code
     * BaseException_str}, which is why a three-argument value prints its message alone and why a pointer read
     * back as {@code None} prints as {@code None} rather than as nothing. The line is read by {@code
     * PyLong_AsLongAndOverflow}, and that function's overflow answer is the number it prints, so {@code 2**80}
     * and {@code -2**80} are both {@code line -1} while the smallest {@code long} prints in full.
     */
    @Test
    void theTextReadsTwoFieldsAndAsksTheMessageForTheRest() {
        PyObject both = make(ExceptionTypes.SYNTAX_ERROR, TEXT, detail(PATH, THREE, TWO, TEXT));
        assertThat(said(both)).isEqualTo("a (f.py, line 3)");
        PyObject named = make(ExceptionTypes.SYNTAX_ERROR);
        Slots.setAttr(named, "filename", PyStr.of("/x/y/f.txt"));
        assertThat(said(named)).isEqualTo("None (f.txt)");
        Slots.setAttr(named, "lineno", PyStr.of("3"));
        assertThat(said(named)).isEqualTo("None (f.txt)");
        Slots.setAttr(named, "lineno", PyBool.of(true));
        assertThat(said(named)).isEqualTo("None (f.txt)");
        Slots.setAttr(named, "lineno", THREE);
        assertThat(said(named)).isEqualTo("None (f.txt, line 3)");
        Slots.setAttr(named, "filename", FOUR);
        assertThat(said(named)).isEqualTo("None (line 3)");
        Slots.setAttr(named, "filename", NONE);
        assertThat(said(named)).isEqualTo("None (line 3)");
        Slots.delAttr(named, "lineno");
        assertThat(said(named)).isEqualTo("None");

        PyObject wide = make(ExceptionTypes.SYNTAX_ERROR, TEXT);
        Slots.setAttr(wide, "filename", PyStr.of("f.py"));
        Slots.setAttr(wide, "lineno", WIDE);
        assertThat(said(wide)).isEqualTo("a (f.py, line -1)");
        Slots.setAttr(wide, "lineno", PyInt.of(BigInteger.TWO.pow(80).negate()));
        assertThat(said(wide)).isEqualTo("a (f.py, line -1)");
        Slots.setAttr(wide, "lineno", SMALLEST);
        assertThat(said(wide)).isEqualTo("a (f.py, line -9223372036854775808)");
        Slots.setAttr(wide, "lineno", TWO);
        // The name is cut at the last separator it holds, and only when it is a `str`: a Windows path has no
        // separator to find, and an empty one still counts as a name — of nothing.
        Slots.setAttr(wide, "filename", PyStr.of("/"));
        assertThat(said(wide)).isEqualTo("a (, line 2)");
        Slots.setAttr(wide, "filename", PyStr.of("/a/b/"));
        assertThat(said(wide)).isEqualTo("a (, line 2)");
        Slots.setAttr(wide, "filename", PyStr.of(""));
        assertThat(said(wide)).isEqualTo("a (, line 2)");
        Slots.setAttr(wide, "filename", PyStr.of("../f.py"));
        assertThat(said(wide)).isEqualTo("a (f.py, line 2)");
        Slots.setAttr(wide, "filename", PyStr.of("C:\\tmp\\f.py"));
        assertThat(said(wide)).isEqualTo("a (C:\\tmp\\f.py, line 2)");

        // The four offsets the sentence never reads, and the class under it that reads the same two.
        PyObject crowded = make(ExceptionTypes.SYNTAX_ERROR, TEXT);
        Slots.setAttr(crowded, "offset", TWO);
        Slots.setAttr(crowded, "text", TEXT);
        Slots.setAttr(crowded, "end_lineno", TWO);
        Slots.setAttr(crowded, "_metadata", TEXT);
        assertThat(said(crowded)).isEqualTo("a");
        PyObject indented =
                make(ExceptionTypes.INDENTATION_ERROR, TEXT, detail(PyStr.of("/p/q.py"), THREE, TWO, PyStr.of("  x")));
        assertThat(said(indented)).isEqualTo("a (q.py, line 3)");
        assertThat(said(make(ExceptionTypes.TAB_ERROR, TEXT))).isEqualTo("a");
        assertThat(said(make(ExceptionTypes.INCOMPLETE_INPUT_ERROR, TEXT))).isEqualTo("a");
        assertThat(((PyStr) call(ExceptionTypes.SYNTAX_ERROR, "__str__", both)).getValue())
                .isEqualTo("a (f.py, line 3)");

        // The message is asked for its text in both shapes, so a `__str__` that fails is the failure the
        // program sees — with a detail filed beside it and without one.
        refuses(
                "RuntimeError: no text",
                () -> said(make(ExceptionTypes.SYNTAX_ERROR, new Failing(), detail(PATH, ONE, TWO, TEXT))));
        refuses("RuntimeError: no text", () -> said(make(ExceptionTypes.SYNTAX_ERROR, new Failing())));
    }

    /**
     * The three tables the leaves parse, in the words their converters use.
     *
     * <p>{@code UnicodeEncodeError_init} calls {@code BaseException_init} first, so the keyword refusal is the
     * base's — {@code _PyArg_NoKeywords(Py_TYPE(self)->tp_name, kwds)}, which names a class Python wrote as
     * itself — and it comes before the count, so {@code UnicodeEncodeError(encoding='utf-8')} is refused for a
     * name rather than for four missing arguments. The count itself is {@code PyArg_ParseTuple}'s, in the words
     * no class owns: {@code function takes exactly 5 arguments (0 given)}. Then the converters, each in its own
     * words: {@code 'U'} names the position and leaves the type unquoted — and {@code None} unquoted as
     * {@code None} rather than as {@code NoneType} — {@code 'n'} says an object cannot be interpreted as an
     * integer, and the decode's {@code 'O'} reads no type at all but turns to the buffer protocol afterwards,
     * which is the only one of these refusals that quotes.
     */
    @Test
    void theThreeTablesRefuseInTheirOwnConvertersWords() {
        PyObject encoded = make(ExceptionTypes.UNICODE_ENCODE_ERROR, CODEC, TEXT, ZERO, ONE, REASON);
        assertThat(field(encoded, "encoding")).isSameAs(CODEC);
        assertThat(field(encoded, "object")).isSameAs(TEXT);
        assertThat(field(encoded, "start").repr()).isEqualTo("0");
        assertThat(field(encoded, "reason").repr()).isEqualTo("'n'");
        assertThat(((PyBaseException) encoded).args().repr()).isEqualTo("('utf-8', 'a', 0, 1, 'n')");
        PyObject decoded = make(ExceptionTypes.UNICODE_DECODE_ERROR, CODEC, OCTETS, ONE, TWO, REASON);
        assertThat(field(decoded, "object")).isSameAs(OCTETS);
        assertThat(said(decoded)).isEqualTo("'utf-8' codec can't decode byte 0xff in position 1: n");
        PyObject translated = make(ExceptionTypes.UNICODE_TRANSLATE_ERROR, TEXT, ZERO, ONE, REASON);
        assertThat(field(translated, "encoding")).isSameAs(NONE);
        assertThat(((PyBaseException) translated).args().repr()).isEqualTo("('a', 0, 1, 'n')");

        refuses(
                "TypeError: function takes exactly 5 arguments (0 given)",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR));
        refuses(
                "TypeError: function takes exactly 5 arguments (4 given)",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, CODEC, TEXT, ZERO, ONE));
        refuses(
                "TypeError: function takes exactly 5 arguments (6 given)",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, CODEC, TEXT, ZERO, ONE, REASON, TWO));
        refuses(
                "TypeError: function takes exactly 4 arguments (3 given)",
                () -> make(ExceptionTypes.UNICODE_TRANSLATE_ERROR, TEXT, ZERO, ONE));
        refuses(
                "TypeError: function takes exactly 4 arguments (5 given)",
                () -> make(ExceptionTypes.UNICODE_TRANSLATE_ERROR, TEXT, ZERO, ONE, REASON, TWO));
        refuses(
                "TypeError: UnicodeEncodeError() takes no keyword arguments",
                () ->
                        given(
                                ExceptionTypes.UNICODE_ENCODE_ERROR,
                                new PyObject[0],
                                new String[] {"encoding"},
                                CODEC));
        refuses(
                "TypeError: UnicodeTranslateError() takes no keyword arguments",
                () ->
                        given(
                                ExceptionTypes.UNICODE_TRANSLATE_ERROR,
                                new PyObject[0],
                                new String[] {"reason"},
                                REASON));
        // The keyword is refused before the count is even asked, in the words of the value's own class.
        PyType written = classOf("Codec", ExceptionTypes.UNICODE_DECODE_ERROR);
        refuses(
                "TypeError: Codec() takes no keyword arguments",
                () -> given(written, new PyObject[0], new String[] {"bad"}, ONE));

        refuses(
                "TypeError: argument 1 must be str, not bytes",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, PyBytes.of(new byte[] {'u'}), TEXT, ZERO, ONE, REASON));
        refuses(
                "TypeError: argument 1 must be str, not None",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, NONE, TEXT, ZERO, ONE, REASON));
        refuses(
                "TypeError: argument 2 must be str, not bytes",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, CODEC, PyBytes.of(new byte[] {'x'}), ZERO, ONE, REASON));
        refuses(
                "TypeError: argument 5 must be str, not int",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, CODEC, TEXT, ZERO, ONE, TWO));
        refuses(
                "TypeError: argument 1 must be str, not bytes",
                () -> make(ExceptionTypes.UNICODE_TRANSLATE_ERROR, PyBytes.of(new byte[] {'x'}), ZERO, ONE, REASON));
        refuses(
                "TypeError: 'float' object cannot be interpreted as an integer",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, CODEC, TEXT, PyFloat.of(0.5), ONE, REASON));
        refuses(
                "OverflowError: Python int too large to convert to C ssize_t",
                () -> make(ExceptionTypes.UNICODE_ENCODE_ERROR, CODEC, TEXT, WIDE, ONE, REASON));
        refuses(
                "TypeError: a bytes-like object is required, not 'str'",
                () -> make(ExceptionTypes.UNICODE_DECODE_ERROR, CODEC, TEXT, ZERO, ONE, REASON));
    }

    /**
     * The six sentences, the gate that chooses between two of them, and the fields read before it.
     *
     * <p>Every one of the three returns the empty string the moment the object is still a null pointer, and
     * otherwise {@code str}s the reason, then the encoding, and only then looks at the object — which is why a
     * broken {@code __str__} on the reason is the failure a program sees, why the translate sentence never fails
     * on an encoding it does not read, and why a value whose object is the wrong kind of sequence is complained
     * about as {@code UnicodeError}, the base that owns the check rather than the leaf the value is. A field
     * that is still a null pointer prints {@code <NULL>}, which is {@code PyUnicode_FromFormat}'s answer and not
     * the interpreter's. The gate is arithmetic as much as bounds: {@code end == start + 1} with both ends inside
     * the value, and outside it the sentence prints the two positions, the second as one less than the field
     * holds, which is how an empty range prints {@code 1-0} and a negative one prints as it was written.
     */
    @Test
    void theSentenceReadsTheReasonFirstAndTheObjectOnlyAfterIt() {
        PyType encode = ExceptionTypes.UNICODE_ENCODE_ERROR;
        PyType decode = ExceptionTypes.UNICODE_DECODE_ERROR;
        PyType translate = ExceptionTypes.UNICODE_TRANSLATE_ERROR;
        PyObject nope = PyStr.of("nope");
        PyObject twain = PyStr.of("ab");
        PyObject behind = PyInt.of(-3);
        PyObject shortOf = PyInt.of(-1);

        assertThat(said(make(encode, ASCII, TEXT, ZERO, ONE, nope)))
                .isEqualTo("'ascii' codec can't encode character '\\x61' in position 0: nope");
        assertThat(said(make(encode, ASCII, PyStr.of("é"), ZERO, ONE, nope)))
                .isEqualTo("'ascii' codec can't encode character '\\xe9' in position 0: nope");
        assertThat(said(make(encode, ASCII, PyStr.of("℘"), ZERO, ONE, nope)))
                .isEqualTo("'ascii' codec can't encode character '\\u2118' in position 0: nope");
        assertThat(said(make(encode, ASCII, PyStr.of("😀"), ZERO, ONE, nope)))
                .isEqualTo("'ascii' codec can't encode character '\\U0001f600' in position 0: nope");
        assertThat(said(make(encode, ASCII, twain, ZERO, TWO, nope)))
                .isEqualTo("'ascii' codec can't encode characters in position 0-1: nope");
        assertThat(said(make(encode, ASCII, twain, ONE, ONE, nope)))
                .isEqualTo("'ascii' codec can't encode characters in position 1-0: nope");
        assertThat(said(make(encode, ASCII, twain, ZERO, NINE, nope)))
                .isEqualTo("'ascii' codec can't encode characters in position 0-8: nope");
        assertThat(said(make(encode, ASCII, twain, behind, shortOf, nope)))
                .isEqualTo("'ascii' codec can't encode characters in position -3--2: nope");
        assertThat(said(make(encode, ASCII, PyStr.of(""), ZERO, ZERO, REASON)))
                .isEqualTo("'ascii' codec can't encode characters in position 0--1: n");
        assertThat(said(make(decode, CODEC, PyBytes.of(new byte[] {0}), ZERO, ONE, nope)))
                .isEqualTo("'utf-8' codec can't decode byte 0x00 in position 0: nope");
        assertThat(said(make(decode, CODEC, PyBytes.of(new byte[] {0x61}), ZERO, ONE, REASON)))
                .isEqualTo("'utf-8' codec can't decode byte 0x61 in position 0: n");
        assertThat(said(make(decode, CODEC, OCTETS, ONE, THREE, nope)))
                .isEqualTo("'utf-8' codec can't decode bytes in position 1-2: nope");
        assertThat(said(make(decode, CODEC, OCTETS, FIVE, THREE, REASON)))
                .isEqualTo("'utf-8' codec can't decode bytes in position 5-2: n");
        assertThat(said(make(translate, PyStr.of("é"), ZERO, ONE, nope)))
                .isEqualTo("can't translate character '\\xe9' in position 0: nope");
        assertThat(said(make(translate, twain, ZERO, TWO, nope)))
                .isEqualTo("can't translate characters in position 0-1: nope");
        assertThat(said(make(translate, twain, behind, shortOf, REASON)))
                .isEqualTo("can't translate characters in position -3--2: n");

        PyObject written = make(encode, ASCII, TEXT, ZERO, ONE, REASON);
        Slots.setAttr(written, "encoding", NONE);
        assertThat(said(written)).isEqualTo("'None' codec can't encode character '\\x61' in position 0: n");
        Slots.setAttr(written, "encoding", PyInt.of(7));
        assertThat(said(written)).isEqualTo("'7' codec can't encode character '\\x61' in position 0: n");
        Slots.setAttr(written, "reason", NONE);
        assertThat(said(written)).isEqualTo("'7' codec can't encode character '\\x61' in position 0: None");
        Slots.setAttr(written, "reason", TWO);
        Slots.setAttr(written, "encoding", ASCII);
        assertThat(said(written)).isEqualTo("'ascii' codec can't encode character '\\x61' in position 0: 2");
        // The translate sentence reads no encoding at all, so the field the shared table hands it can hold
        // even a value whose own text refuses, and never reach the sentence.
        PyObject untranslated = make(translate, twain, ZERO, TWO, REASON);
        Slots.setAttr(untranslated, "encoding", PyStr.of("z"));
        assertThat(said(untranslated)).isEqualTo("can't translate characters in position 0-1: n");
        Slots.setAttr(untranslated, "encoding", new Failing());
        assertThat(said(untranslated)).isEqualTo("can't translate characters in position 0-1: n");

        // The object is checked last, by the base that owns the five fields, and a null one says nothing.
        Slots.setAttr(written, "object", OCTETS);
        refuses("TypeError: UnicodeError 'object' attribute must be a string", () -> said(written));
        PyObject decoded = make(decode, CODEC, OCTETS, ONE, TWO, REASON);
        Slots.setAttr(decoded, "object", TEXT);
        refuses("TypeError: UnicodeError 'object' attribute must be a bytes", () -> said(decoded));
        Slots.setAttr(decoded, "object", OCTETS);
        Slots.delAttr(decoded, "object");
        assertThat(said(decoded)).isEmpty();
        assertThat(said(fresh(encode))).isEmpty();

        // Two null pointers and one value: the only `<NULL>`s a sentence can print are the fields no
        // initialiser ever ran for, and each of the three words is the leaf's own.
        PyObject encodeFresh = fresh(encode);
        Slots.setAttr(encodeFresh, "object", TEXT);
        assertThat(said(encodeFresh))
                .isEqualTo("'<NULL>' codec can't encode characters in position 0--1: <NULL>");
        PyObject decodeFresh = fresh(decode);
        Slots.setAttr(decodeFresh, "object", OCTETS);
        assertThat(said(decodeFresh)).isEqualTo("'<NULL>' codec can't decode bytes in position 0--1: <NULL>");
        PyObject translateFresh = fresh(translate);
        Slots.setAttr(translateFresh, "object", TEXT);
        assertThat(said(translateFresh)).isEqualTo("can't translate characters in position 0--1: <NULL>");
        Slots.setAttr(encodeFresh, "reason", REASON);
        assertThat(said(encodeFresh)).isEqualTo("'<NULL>' codec can't encode characters in position 0--1: n");

        // A `__str__` that fails is the failure the program sees, and both the reason and the encoding are
        // asked before the object is looked at — so a value whose object is the wrong kind of sequence never
        // gets to be complained about. Only a field write can hang such a value on the offset: the initialiser
        // refuses a non-`str` in the words of its converter.
        refuses(
                "TypeError: argument 1 must be str, not object",
                () -> make(encode, new Failing(), TEXT, ZERO, ONE, REASON));
        PyObject noReason = make(decode, CODEC, OCTETS, ONE, TWO, REASON);
        Slots.setAttr(noReason, "reason", new Failing());
        Slots.setAttr(noReason, "object", TEXT);
        refuses("RuntimeError: no text", () -> said(noReason));
        PyObject noEncoding = make(encode, CODEC, TEXT, ZERO, ONE, REASON);
        Slots.setAttr(noEncoding, "encoding", new Failing());
        refuses("RuntimeError: no text", () -> said(noEncoding));

        // `UnicodeError` itself files no sentence: it falls back to the family's, which prints the arguments.
        assertThat(said(make(ExceptionTypes.UNICODE_ERROR, TEXT))).isEqualTo("a");
        assertThat(said(make(ExceptionTypes.UNICODE_ERROR, CODEC, OCTETS, ONE, TWO, PyStr.of("r"))))
                .isEqualTo("('utf-8', b'x\\xffz', 1, 2, 'r')");
        refuses(
                "AttributeError: 'UnicodeError' object has no attribute 'start'",
                () -> field(make(ExceptionTypes.UNICODE_ERROR, TEXT), "start"));
    }

    /**
     * The value a codec makes when it gives up, which is the same five fields a call makes.
     *
     * <p>{@code make_encode_exception} calls the leaf with {@code "sOnns"} and {@code
     * PyUnicodeDecodeError_Create} calls it with {@code "sy#nns"}: both doors are {@code type_call}, so the
     * codec's failure runs the same initialiser a hand-written one does and writes no sentence on the way. What
     * a program reads therefore comes from {@code UnicodeEncodeError_str} and from nowhere else, and a value
     * that carries a message without an {@code object} answers the empty string — which is exactly what a codec
     * that formatted its own sentence and filled nothing would have answered. {@code charmap_encode} groups the
     * characters it cannot write into one run, so {@code 'aéé'} is a single error over {@code 1-3} and prints
     * {@code characters in position 1-2}, while {@code 'éaé'} stops at {@code 0-1} and never reaches the second
     * run; a decode stops on the one octet it cannot read.
     */
    @Test
    void theCodecFillsTheFiveFieldsAndTheLeafWritesTheSentence() {
        PyBaseException one = caught(() -> run(PyStr.of("aé"), "encode", ASCII));
        assertThat(one.getType()).isSameAs(ExceptionTypes.UNICODE_ENCODE_ERROR);
        assertThat(said(one))
                .isEqualTo("'ascii' codec can't encode character '\\xe9' in position 1: "
                        + "ordinal not in range(128)");
        assertThat(field(one, "encoding")).isEqualTo(ASCII);
        assertThat(field(one, "object")).isEqualTo(PyStr.of("aé"));
        assertThat(field(one, "start")).isEqualTo(ONE);
        assertThat(field(one, "end")).isEqualTo(TWO);
        assertThat(field(one, "reason")).isEqualTo(PyStr.of("ordinal not in range(128)"));
        assertThat(dictOf(one)).isEqualTo("{}");

        // The five are both the fields and the arguments, so the value rebuilds its own sentence from them.
        assertThat(field(one, "args").repr())
                .isEqualTo("('ascii', 'aé', 1, 2, 'ordinal not in range(128)')");
        assertThat(said(make(ExceptionTypes.UNICODE_ENCODE_ERROR, field(one, "encoding"),
                        field(one, "object"), field(one, "start"), field(one, "end"), field(one, "reason"))))
                .isEqualTo(said(one));

        // The run is grouped, so one error covers both characters and the sentence prints the second position
        // one lower than the field holds — and the run closes at the first character the codec can write, so a
        // third bad character after a good one is a fresh, narrower error rather than a wider one.
        PyBaseException grouped = caught(() -> run(PyStr.of("aéé"), "encode", ASCII));
        assertThat(said(grouped))
                .isEqualTo("'ascii' codec can't encode characters in position 1-2: "
                        + "ordinal not in range(128)");
        assertThat(field(grouped, "start")).isEqualTo(ONE);
        assertThat(field(grouped, "end")).isEqualTo(THREE);
        assertThat(said(caught(() -> run(PyStr.of("éaé"), "encode", ASCII))))
                .isEqualTo("'ascii' codec can't encode character '\\xe9' in position 0: "
                        + "ordinal not in range(128)");

        // An astral character is one item at one index, and the width of it picks the escape.
        String astral = new String(Character.toChars(0x1f600)) + "a";
        assertThat(said(caught(() -> run(PyStr.of(astral), "encode", ASCII))))
                .isEqualTo("'ascii' codec can't encode character '\\U0001f600' in position 0: "
                        + "ordinal not in range(128)");

        // The name the sentence quotes is the codec's own, whichever spelling the call used.
        assertThat(field(caught(() -> run(PyStr.of("é"), "encode", PyStr.of("ASCII"))), "encoding"))
                .isEqualTo(ASCII);

        // A latin-1 failure is a run of a different width, refused by a different range.
        assertThat(said(caught(() -> run(PyStr.of("aĀ"), "encode", PyStr.of("latin-1")))))
                .isEqualTo("'latin-1' codec can't encode character '\\u0100' in position 1: "
                        + "ordinal not in range(256)");
        PyBaseException wide = caught(() -> run(PyStr.of("aĀā"), "encode", PyStr.of("latin-1")));
        assertThat(said(wide))
                .isEqualTo("'latin-1' codec can't encode characters in position 1-2: "
                        + "ordinal not in range(256)");
        assertThat(field(wide, "encoding")).isEqualTo(PyStr.of("latin-1"));
        assertThat(run(PyStr.of("aé"), "encode", PyStr.of("latin-1")).repr()).isEqualTo("b'a\\xe9'");

        // A decode carries the whole run of octets it was given and names the one it stopped on.
        byte[] three = {'a', (byte) 0xff, (byte) 0xfe, 'z'};
        PyBaseException octet = caught(() -> run(PyBytes.of(three), "decode", ASCII));
        assertThat(octet.getType()).isSameAs(ExceptionTypes.UNICODE_DECODE_ERROR);
        assertThat(said(octet))
                .isEqualTo("'ascii' codec can't decode byte 0xff in position 1: ordinal not in range(128)");
        assertThat(field(octet, "object")).isEqualTo(PyBytes.of(three));
        assertThat(field(octet, "start")).isEqualTo(ONE);
        assertThat(field(octet, "end")).isEqualTo(TWO);

        // A class Python wrote inherits the leaf's initialiser and its sentence, so the same five fields fill
        // the borrowed offsets and the borrowed `tp_str` reads them back.
        PyType written = classOf("Codec", ExceptionTypes.UNICODE_ENCODE_ERROR);
        PyObject inherited = make(written, ASCII, PyStr.of("aé"), ONE, TWO, REASON);
        assertThat(inherited.getType()).isSameAs(written);
        assertThat(field(inherited, "object")).isEqualTo(PyStr.of("aé"));
        assertThat(said(inherited))
                .isEqualTo("'ascii' codec can't encode character '\\xe9' in position 1: n");
    }

    /** A value whose {@code str} refuses, hung on a field the sentence reads before it looks at the object. */
    private static final class Failing extends PyObject {

        Failing() {
            super(PyType.OBJECT);
        }

        @Override
        public String str() {
            throw Slots.runtimeError("no text");
        }

        @Override
        public String repr() {
            return "Failing()";
        }
    }
}
