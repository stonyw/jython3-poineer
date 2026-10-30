package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The argument table CPython invents out of a row's flags, measured against CPython 3.14.8.
 *
 * <p>{@code _PyType_GetTextSignatureFromInternalDoc} (Objects/typeobject.c:909) reads a row two ways: it
 * cuts a table out of the {@code ml_doc} when the text opens with the row's own name and a {@code --} rule,
 * and when nothing comes of that it asks {@code signature_from_flags} (the same file, line 888) — a six-case
 * switch over {@code METH_NOARGS} and {@code METH_O} crossed with {@code METH_CLASS} and {@code METH_STATIC}.
 * Corpus case 99 reads the twenty-one rows whose text names no table. What is pinned here is the switch the
 * reference keeps behind the text: all six of its answers, including the two static shapes no row of these
 * dictionaries reaches, the mask over {@code METH_COEXIST}, the order of the two reads, and the fact that a
 * bound row carries its flag with it — which is how {@code int.__subclasshook__.__text_signature__} reads the
 * same table as the row filed in {@code object}.
 */
class InventedTableTest {

    /** {@code methodobject.h}, as far as {@code signature_from_flags} reads it. */
    private static final int NOARGS = PyNativeFunction.METH_NOARGS;

    private static final int ONE = PyNativeFunction.METH_O;
    private static final int ON_TYPE = PyNativeFunction.METH_CLASS;
    private static final int ON_VALUE = PyNativeFunction.METH_STATIC;

    /** The three conventions the switch has no case for, and the one flag it masks off before deciding. */
    private static final int VARARGS = 0x0001;

    private static final int KEYWORDS = 0x0002;
    private static final int FASTCALL = 0x0100;
    private static final int COEXIST = 0x0040;

    /** The five kinds the reference sweep asks, which are the five that answer this name at all. */
    private static final List<String> READERS =
            List.of(
                    "method_descriptor",
                    "wrapper_descriptor",
                    "classmethod_descriptor",
                    "staticmethod_descriptor",
                    "builtin_function_or_method");

    /** The twenty-five dictionaries case 99 sweeps. */
    private static final List<PyType> SWEPT =
            List.of(
                    PyType.OBJECT,
                    PyType.TYPE,
                    PyType.MAPPING_PROXY,
                    PyType.BUILTIN_FUNCTION,
                    PyType.BYTES,
                    PyType.STR,
                    PyType.LIST,
                    PyType.TUPLE,
                    PyType.INT,
                    PyType.BOOL,
                    PyType.FLOAT,
                    PyType.DICT,
                    PyType.SET,
                    PyType.FROZENSET,
                    ExceptionTypes.BASE_EXCEPTION,
                    ExceptionTypes.EXCEPTION,
                    ExceptionTypes.OS_ERROR,
                    ExceptionTypes.ATTRIBUTE_ERROR,
                    ExceptionTypes.IMPORT_ERROR,
                    ExceptionTypes.NAME_ERROR,
                    ExceptionTypes.STOP_ITERATION,
                    ExceptionTypes.SYSTEM_EXIT,
                    ExceptionTypes.SYNTAX_ERROR,
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    ExceptionTypes.EXCEPTION_GROUP);

    private static final PyObject SAMPLE = PyBytes.of(new byte[] {'a', 'b'});

    /** The row a type files under {@code name}, out of its dictionary rather than by reading the name. */
    private static PyObject row(PyType kind, String name) {
        return TypeDict.declared(kind).get(PyStr.interned(name));
    }

    /** {@code row.__text_signature__}, as the Java string, or {@code null} where it answers {@code None}. */
    private static String table(PyObject member) {
        PyObject read = Slots.getAttr(member, "__text_signature__");
        return read == PyNone.INSTANCE ? null : ((PyStr) read).getValue();
    }

    /** {@code row.__doc__}, the same way — the whole sentence for a text that carries no rule. */
    private static String text(PyObject member) {
        PyObject read = Slots.getAttr(member, "__doc__");
        return read == PyNone.INSTANCE ? null : ((PyStr) read).getValue();
    }

    /** The names a type's own dictionary files, in the order it kept them. */
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

    /** {@code signature_from_flags} alone: the rule run over a row whose text is missing entirely. */
    private static String invented(int flags) {
        return read("x", null, flags);
    }

    /** Both reads at once, in the order CPython tries them: the text first, the flag where it names none. */
    private static String read(String name, String internal, int flags) {
        PyObject read = PyNativeFunction.signatureOf(name, internal, flags);
        return read == PyNone.INSTANCE ? null : ((PyStr) read).getValue();
    }

    /** {@code TypeError} and {@code AttributeError} in CPython's own words, which the runtime repeats. */
    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The six answers of the switch, and the nought every other calling convention falls through to.
     *
     * <p>Only four of the six name a receiver this runtime files: the two static shapes are what a {@code
     * staticmethod} registered like them would read, and no row of these dictionaries is — every static
     * clinic files comes with a text of its own, which the read above answers from first. {@code
     * METH_COEXIST} is masked off, so a row filed twice over two names keeps its table while a receiver flag
     * without an argument convention — and the crossing of class with static — both answer nothing.
     */
    @Test
    void oneSwitchAnswersSixTablesAndNothingForEveryOtherConvention() {
        assertThat(invented(NOARGS)).isEqualTo("($self, /)");
        assertThat(invented(NOARGS | ON_TYPE)).isEqualTo("($type, /)");
        assertThat(invented(NOARGS | ON_VALUE)).isEqualTo("()");
        assertThat(invented(ONE)).isEqualTo("($self, object, /)");
        assertThat(invented(ONE | ON_TYPE)).isEqualTo("($type, object, /)");
        assertThat(invented(ONE | ON_VALUE)).isEqualTo("(object, /)");

        assertThat(invented(COEXIST | ONE)).isEqualTo("($self, object, /)");
        assertThat(invented(COEXIST | ONE | ON_TYPE)).isEqualTo("($type, object, /)");
        assertThat(invented(COEXIST)).isNull();
        assertThat(invented(ON_TYPE)).isNull();
        assertThat(invented(NOARGS | ON_TYPE | ON_VALUE)).isNull();

        // The conventions with no case: a row handed an argument list, keywords, or the vectorcall pair.
        assertThat(invented(0)).isNull();
        assertThat(invented(VARARGS)).isNull();
        assertThat(invented(VARARGS | KEYWORDS)).isNull();
        assertThat(invented(FASTCALL)).isNull();
        assertThat(invented(FASTCALL | KEYWORDS)).isNull();

        // Nothing in these dictionaries reaches the static pair, and the sweep proves it name by name.
        List<String> statics = new ArrayList<>();
        for (PyType kind : SWEPT) {
            for (String name : filed(kind)) {
                PyObject member = row(kind, name);
                String said = reader(member) ? table(member) : null;
                if ("()".equals(said) || "(object, /)".equals(said)) {
                    statics.add(kind.name() + "." + name);
                }
            }
        }
        assertThat(statics).isEmpty();
    }

    /**
     * The order of the two reads: a text that carries a table is cut before the switch is ever tried.
     *
     * <p>{@code bytes.join} and {@code int.__format__} are registered here by {@link Members#addOne}, which
     * writes {@code METH_O} — whose invented table would be {@code ($self, object, /)} — beside a clinic text
     * that names its own arguments. The text wins, exactly as it does in the reference, and the flag is never
     * read; it would show only on a row whose clinic block carries no table at all.
     */
    @Test
    void aTextThatCarriesATableIsCutBeforeTheSwitchIsReached() {
        String counted =
                "count($self, sub[, start[, end]], /)\n--\n\nReturn the number of non-overlapping"
                        + " occurrences of subsection sub in the range [start, end].";
        assertThat(read("count", counted, ONE)).isEqualTo("($self, sub[, start[, end]], /)");
        assertThat(read("count", counted, 0)).isEqualTo("($self, sub[, start[, end]], /)");
        assertThat(read("str.count", counted, ONE)).isEqualTo("($self, sub[, start[, end]], /)");

        // A sentence that names no arguments, and a row with no sentence at all, are both the flag's work.
        assertThat(read("keys", "D.keys() -> a set-like object", NOARGS)).isEqualTo("($self, /)");
        assertThat(read("keys", null, NOARGS)).isEqualTo("($self, /)");
        assertThat(read("keys", null, 0)).isNull();

        // The name has to open the text: `D.update([E, ]**F)` is not `update(`, and METH_VARARGS invents
        // nothing, so this one row reads None in both runtimes.
        assertThat(read("update", "D.update([E, ]**F) -> None.  Update D from E and F.", VARARGS)).isNull();

        // The same order on the rows themselves.
        assertThat(table(row(PyType.BYTES, "join"))).isEqualTo("($self, iterable_of_bytes, /)");
        assertThat(table(row(PyType.STR, "count"))).isEqualTo("($self, sub[, start[, end]], /)");
        assertThat(table(row(PyType.INT, "__format__"))).isEqualTo("($self, format_spec, /)");
        assertThat(table(row(PyType.DICT, "update"))).isNull();
        assertThat(text(row(PyType.DICT, "update"))).startsWith("D.update([E, ]**F) -> None.");
    }

    /**
     * Eight of the fourteen hand-written rows, and the sentences they keep.
     *
     * <p>Case 99 counts twenty rows whose text names no table: {@code object}'s two hooks, the five views and
     * the subscript of {@code mappingproxy} (the eight here), the six subscripts the next test walks, {@code
     * int}'s four whole-number names and the two {@code __getnewargs__}s the test after that reads. None of
     * the fourteen files a sentence naming arguments, so nothing is cut from the text: {@code __doc__} answers
     * the whole of it while the table comes out of the flag. A flag invents a table and touches nothing else.
     */
    @Test
    void theEightHandWrittenRowsOfTheFourteenAnswerTheirTablesFromAFlag() {
        assertThat(table(row(PyType.OBJECT, "__init_subclass__"))).isEqualTo("($type, /)");
        assertThat(table(row(PyType.OBJECT, "__subclasshook__"))).isEqualTo("($type, object, /)");
        List<String> views = List.of("keys", "values", "items", "copy", "__reversed__");
        for (String view : views) {
            assertThat(table(row(PyType.MAPPING_PROXY, view))).as(view).isEqualTo("($self, /)");
            assertThat(row(PyType.MAPPING_PROXY, view).getType().name())
                    .as(view)
                    .isEqualTo("method_descriptor");
        }
        assertThat(table(row(PyType.MAPPING_PROXY, "__class_getitem__")))
                .isEqualTo("($type, object, /)");

        assertThat(text(row(PyType.MAPPING_PROXY, "keys")))
                .isEqualTo("D.keys() -> a set-like object providing a view on D's keys");
        assertThat(text(row(PyType.OBJECT, "__subclasshook__")))
                .isEqualTo(
                        "Abstract classes can override this to customize issubclass().\n"
                                + "\n"
                                + "This is invoked early on by abc.ABCMeta.__subclasscheck__().\n"
                                + "It should return True, False or NotImplemented.  If it returns\n"
                                + "NotImplemented, the normal algorithm is used.  Otherwise, it\n"
                                + "overrides the normal algorithm (and the outcome is cached).\n");
        // Two names, one row each, and neither cut: the sentence is the whole `ml_doc`.
        assertThat(text(row(PyType.OBJECT, "__init_subclass__"))).contains("The default implementation");
        assertThat(text(row(PyType.OBJECT, "__init_subclass__"))).doesNotContain("--");

        // The flag is not writable: it is a fact of the row, as the `PyMethodDef` it came from is. The kind
        // files the name as a getset with no setter, so the write is refused by the descriptor rather than by
        // the dictionary the row has not got.
        refuses(
                "AttributeError: attribute '__text_signature__' of 'method_descriptor' objects"
                        + " is not writable",
                () -> Slots.setAttr(row(PyType.STR, "count"), "__text_signature__", PyStr.of("()")));
        refuses(
                "AttributeError: attribute '__text_signature__' of 'method_descriptor' objects"
                        + " is not writable",
                () -> Slots.delAttr(row(PyType.STR, "count"), "__text_signature__"));
    }

    /**
     * Seven types file the same row over the same two flags and keep seven different sentences.
     *
     * <p>All seven PEP 585 notes are hand-written, so all seven tables are the flag's work; {@code
     * Table.genericSubscript} files the pair once rather than at each of the seven. The mappingproxy row is
     * the one this slice also corrects on the calling half: {@code descrobject.c:1180} registers {@code
     * Py_GenericAlias} beside {@code METH_O|METH_CLASS}, so the seventh subscript aliases like its six
     * siblings instead of handing back {@code NotImplemented} and leaving the class not subscriptable.
     */
    @Test
    void sevenGenericSubscriptsShareOneShapeAndKeepSevenSentences() {
        List<String> sentences = new ArrayList<>();
        for (PyType owner :
                List.of(
                        PyType.MAPPING_PROXY,
                        PyType.LIST,
                        PyType.TUPLE,
                        PyType.DICT,
                        PyType.SET,
                        PyType.FROZENSET,
                        ExceptionTypes.BASE_EXCEPTION_GROUP)) {
            PyObject member = row(owner, "__class_getitem__");
            assertThat(member.getType().name()).isEqualTo("classmethod_descriptor");
            assertThat(table(member)).as(owner.name()).isEqualTo("($type, object, /)");
            assertThat(Slots.getAttr(member, "__name__")).isEqualTo(PyStr.of("__class_getitem__"));
            assertThat(Slots.call(member, new PyObject[] {owner, PyType.INT}, null).repr())
                    .as(owner.name())
                    .isEqualTo(owner.name() + "[int]");
            sentences.add(text(member));
        }
        assertThat(sentences).hasSize(7).doesNotHaveDuplicates();

        // The subscript reaches the class the way the row does, and the alias remembers both halves.
        PyObject aliased = PyType.MAPPING_PROXY.getItem(PyType.INT);
        assertThat(aliased.repr()).isEqualTo("mappingproxy[int]");
        assertThat(Slots.getAttr(aliased, "__origin__")).isSameAs(PyType.MAPPING_PROXY);
        assertThat(Slots.getAttr(aliased, "__args__").repr()).isEqualTo("(<class 'int'>,)");
    }

    /**
     * A row with no sentence at all still answers a table, and the calls are untouched by it.
     *
     * <p>{@code str} and {@code bytes} file {@code __getnewargs__} with no {@code ml_doc} beside the pointer,
     * so {@code __doc__} is {@code None} in both runtimes while {@code ($self, /)} is the flag's answer to a
     * text that is missing entirely. {@code int}'s four whole-number names are the other shape of the same
     * registration: a sentence, and no table in it.
     */
    @Test
    void aFlagAnswersForARowThatCarriesNoTextAndChangesNoCall() {
        List<String> sentences = new ArrayList<>();
        for (String name : List.of("conjugate", "__trunc__", "__floor__", "__ceil__")) {
            assertThat(table(row(PyType.INT, name))).as(name).isEqualTo("($self, /)");
            sentences.add(text(row(PyType.INT, name)));
        }
        assertThat(sentences).doesNotHaveDuplicates().allMatch(said -> said.endsWith("."));
        assertThat(text(row(PyType.INT, "conjugate")))
                .isEqualTo("Returns self, the complex conjugate of any int.");
        assertThat(text(row(PyType.INT, "__floor__")))
                .isEqualTo("Flooring an Integral returns itself.");

        for (PyType kind : List.of(PyType.BYTES, PyType.STR)) {
            PyObject member = row(kind, "__getnewargs__");
            assertThat(member).isInstanceOf(PyMethodDescriptor.class);
            assertThat(Slots.getAttr(member, "__doc__")).isSameAs(PyNone.INSTANCE);
            assertThat(table(member)).isEqualTo("($self, /)");
        }
        // One of the four names is inherited whole rather than filed again under `bool`.
        assertThat(row(PyType.BOOL, "conjugate")).isNull();
        assertThat(Slots.getAttr(PyType.BOOL, "conjugate"))
                .isSameAs(Slots.getAttr(PyType.INT, "conjugate"));

        // The arity sentences are the ones `addNoArgument` has always said, flag or no flag.
        refuses(
                "TypeError: int.conjugate() takes no arguments (1 given)",
                () -> Slots.call(row(PyType.INT, "conjugate"),
                        new PyObject[] {PyInt.of(5), PyInt.of(1)}, null));
        refuses(
                "TypeError: unbound method int.conjugate() needs an argument",
                () -> Slots.call(row(PyType.INT, "conjugate"), new PyObject[0], null));
        refuses(
                "TypeError: str.__getnewargs__() takes no arguments (1 given)",
                () -> Slots.call(row(PyType.STR, "__getnewargs__"),
                        new PyObject[] {PyStr.of("a"), PyStr.of("b")}, null));
        assertThat(Slots.call(row(PyType.INT, "conjugate"), new PyObject[] {PyInt.of(5)}, null))
                .isEqualTo(PyInt.of(5));
        assertThat(Slots.call(row(PyType.BYTES, "__getnewargs__"), new PyObject[] {SAMPLE}, null))
                .isEqualTo(PyTuple.of(SAMPLE));
    }

    /**
     * Every bound row carries the table its filed row was read with.
     *
     * <p>{@code object}'s two hooks bind over the class they were read from, and the bound half reads the
     * same two rules as the filed row: that is {@code methodForm} and {@code Bound} carrying the flag through
     * the binding. A row of a value class's own {@code Members} table binds over the value, and since {@code
     * method_get} makes a {@code PyCFunctionObject} of it rather than a {@code PyMethodObject}, the read
     * answers the same two rules too — the dictionary that files {@code __text_signature__} is the one {@code
     * builtin_function_or_method} files, not the one {@code method} files.
     */
    @Test
    void everyBoundRowCarriesTheTableItsFiledRowWasReadWith() {
        PyObject hook = Slots.getAttr(PyType.INT, "__subclasshook__");
        assertThat(hook.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(table(hook)).isEqualTo("($type, object, /)");
        assertThat(Slots.getAttr(hook, "__self__")).isSameAs(PyType.INT);
        assertThat(hook).isNotSameAs(row(PyType.OBJECT, "__subclasshook__"));

        PyObject subclassed = Slots.getAttr(PyType.INT, "__init_subclass__");
        assertThat(table(subclassed)).isEqualTo("($type, /)");

        // The bound half of a `method_descriptor` on a value: the same table, read from the same row, over a
        // receiver the read keeps. A row whose text names nothing answers the flag in both halves.
        assertThat(table(Slots.getAttr(PyType.BYTES, "__getnewargs__"))).isEqualTo("($self, /)");
        PyObject bound = Slots.getAttr(SAMPLE, "__getnewargs__");
        assertThat(bound.getType().name()).isEqualTo("builtin_function_or_method");
        assertThat(table(bound)).isEqualTo("($self, /)");
        assertThat(Slots.getAttr(bound, "__name__")).isEqualTo(PyStr.of("__getnewargs__"));
        assertThat(Slots.getAttr(bound, "__self__")).isSameAs(SAMPLE);
        assertThat(Slots.getAttr(bound, "__module__")).isSameAs(PyNone.INSTANCE);
        assertThat(((PyTuple) Slots.call(bound, new PyObject[0], null)).at(0)).isSameAs(SAMPLE);
    }

    /**
     * The census the flags close: one filed row of the twenty-five dictionaries answers no table.
     *
     * <p>{@code dict.update} is the row that stays silent in both runtimes, and for the same two reasons
     * working together — its {@code ml_doc} opens with {@code D.update(} so {@code find_signature} never
     * starts, and the row is registered {@code METH_VARARGS}, one of the conventions the switch answers NULL
     * for.
     */
    @Test
    void oneFiledRowOfTheTwentyFiveDictionariesAnswersNoTable() {
        List<String> silent = new ArrayList<>();
        for (PyType kind : SWEPT) {
            for (String name : filed(kind)) {
                PyObject member = row(kind, name);
                if (reader(member) && table(member) == null) {
                    silent.add(kind.name() + "." + name);
                }
            }
        }
        assertThat(silent).isEqualTo(List.of("dict.update"));
        assertThat(SWEPT).hasSize(25);
    }

    /** Whether a filed row is one of the five kinds the reference sweep asks about. */
    private static boolean reader(PyObject member) {
        return READERS.contains(member.getType().name());
    }
}
