package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The five dictionaries slice 35 files, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 74 to 78 read each table from Python, one row per line. What is asserted here is the half a
 * script reaches only by accident. The first is <em>which</em> row a call of a class that files nothing runs:
 * {@code type_call} is {@code tp_new} and then {@code tp_init}, and the second half is looked up through the
 * method resolution order, so {@code UnboundLocalError('m', name='n')} binds {@code NameError}'s field and
 * {@code UnboundLocalError('m', bad=1)} is refused in {@code NameError()}'s words — while a keyword handed to
 * {@code StopIteration} is refused in the caller's own words, because that row parses no names at all and
 * answers with {@code _PyArg_NoKeywords}, which reads {@code Py_TYPE(self)}. The second is the shape of the
 * three parses that do read names: {@code |$OO}, {@code |$O} and {@code |$OOO} are read against an argument
 * tuple the creator has just emptied, so no positional counts against them and {@code takes at most 2 keyword
 * arguments (3 given)} counts only names. The third is the difference between a null pointer and {@code None}
 * in the two {@code __getstate__}s of the family, which decides how many places {@code __reduce__} answers
 * with, and whether the third one is the value's own dictionary or a copy of it.
 */
class FamilyFieldsDictionaryTest {

    /** The six names {@code AttributeError} files, its two members in {@code AttributeError_members}'s order. */
    private static final List<String> ATTRIBUTE_ORDER =
            List.of("__init__", "__getstate__", "__reduce__", "name", "obj", "__doc__");

    /** The three names {@code NameError} files: one initialiser, one field, one sentence. */
    private static final List<String> NAME_ORDER = List.of("__init__", "name", "__doc__");

    /** The three names {@code StopIteration} files, with {@code value} where {@code NameError} has {@code name}. */
    private static final List<String> STOP_ORDER = List.of("__init__", "value", "__doc__");

    /** The three names {@code SystemExit} files, with {@code code} in the same third place. */
    private static final List<String> EXIT_ORDER = List.of("__init__", "code", "__doc__");

    /** The eight names {@code ImportError} files, its four members in {@code PyImportErrorObject}'s order. */
    private static final List<String> IMPORT_ORDER =
            List.of("__str__", "__init__", "__reduce__", "msg", "name", "path", "name_from", "__doc__");

    /** The four offsets of {@code ImportError_members}, the largest member table of the five. */
    private static final List<String> IMPORT_FIELDS = List.of("msg", "name", "path", "name_from");

    /** The nine offsets of the slice, each beside the class that files it. */
    private static final PyType[] OWNERS = {
        ExceptionTypes.ATTRIBUTE_ERROR,
        ExceptionTypes.ATTRIBUTE_ERROR,
        ExceptionTypes.NAME_ERROR,
        ExceptionTypes.STOP_ITERATION,
        ExceptionTypes.SYSTEM_EXIT,
        ExceptionTypes.IMPORT_ERROR,
        ExceptionTypes.IMPORT_ERROR,
        ExceptionTypes.IMPORT_ERROR,
        ExceptionTypes.IMPORT_ERROR
    };

    private static final String[] FIELDS = {
        "name", "obj", "name", "value", "code", "msg", "name", "path", "name_from"
    };

    /** The sentence every {@code tp_init} row carries, which no class of the family rewrites. */
    private static final String INIT_DOC =
            "Initialize self.  See help(type(self)) for accurate signature.";

    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject TEXT = PyStr.of("a");
    private static final PyObject NAME = PyStr.of("n");
    private static final PyObject OTHER = PyStr.of("g");
    private static final PyObject NONE = PyNone.INSTANCE;

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

    /** The names one class adds to what every exception of the family already answers. */
    private static List<String> added(PyType kind) {
        List<String> extra = new ArrayList<>(visible(kind));
        extra.removeAll(visible(ExceptionTypes.BASE_EXCEPTION));
        return extra;
    }

    /** One name of a type's dictionary, called the way a program calls it: the receiver comes first. */
    private static PyObject call(PyType kind, String name, PyObject... arguments) {
        return Slots.call(entry(kind, name), arguments, null);
    }

    /** {@code Name(*arguments)}: the call a program makes, put through {@code type_call}. */
    private static PyObject make(PyType kind, PyObject... arguments) {
        return kind.call(arguments, null);
    }

    /**
     * {@code Name(*head, n0=v0, n1=v1, …)}: a call with names, spelled the way the convention hands them —
     * the keyword values stacked behind the positionals, their names in the same order.
     */
    private static PyObject given(PyType kind, PyObject[] head, String[] names, PyObject... values) {
        PyObject[] all = new PyObject[head.length + values.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(values, 0, all, head.length, values.length);
        return kind.call(all, names.length == 0 ? null : PyBoundMethod.keywordTuple(names));
    }

    /** {@code Kind('a', name='n')}: the one call every table of the slice is there to answer. */
    private static PyObject messageAndName(PyType kind) {
        return given(kind, new PyObject[] {TEXT}, new String[] {"name"}, NAME);
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

    /** The same name read through a value and called, which is how a program reaches it. */
    private static PyObject run(PyObject receiver, String name, PyObject... arguments) {
        return Slots.call(Slots.getAttr(receiver, name), arguments, null);
    }

    /**
     * One row of a type's dictionary bound to a value by its {@code __get__}: the same function {@code
     * super().__init__()} reaches, as distinct from the row called with the receiver as its first argument.
     */
    private static PyObject boundRow(PyType owner, String name, PyObject receiver) {
        return Slots.call(
                Slots.getAttr(entry(owner, name), "__get__"),
                new PyObject[] {receiver, receiver.getType()},
                null);
    }

    /** The sentence one filed member answers {@code __doc__} with; {@code null} where it answers none. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == NONE ? null : ((PyStr) text).getValue();
    }

    /** {@code e.__dict__} as it stands, printed: the instance dictionary a field write never touches. */
    private static String dictOf(PyObject value) {
        return ((PyBaseException) value).held().repr();
    }

    /** {@code type(name, (base,), {members})}, the three-argument creator asked for a class. */
    private static PyType classOf(String name, PyType base, Object... members) {
        PyDict dict = PyDict.empty();
        for (int at = 0; at < members.length; at += 2) {
            dict.put(PyStr.interned((String) members[at]), (PyObject) members[at + 1]);
        }
        return (PyType)
                Classes.newType(
                        PyType.TYPE, new PyObject[] {PyStr.of(name), PyTuple.of(base), dict}, null);
    }

    /** {@code TypeError} and {@code AttributeError} in CPython's own words, which the runtime repeats. */
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

    /** The third place of a reduction, which the two {@code __getstate__}s of the family fill differently. */
    private static PyObject places(PyObject reduced) {
        return ((PyTuple) reduced).at(2);
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The five tables, in the order {@code type_ready_fill_dict} merged the four C arrays into.
     *
     * <p>None of the five files {@code __new__}: {@code BaseException}'s creator is the one they run, and a
     * slot a class keeps is a row of the class that wrote it, never copied down. {@code add_operators} comes
     * before {@code tp_methods}, so {@code ImportError}'s own {@code tp_str} is its first row and {@code
     * AttributeError}'s {@code __init__} sits above its two {@code method_descriptor}s — and because the merge
     * files only a wrapper whose slot differs from the immediate base's, {@code AttributeError} files no
     * {@code __str__} and no {@code __repr__} at all, so those two reads answer with the very objects {@code
     * BaseException}'s dictionary holds. Twenty-nine of every one of these {@code dir} counts are names none
     * of the five files.
     */
    @Test
    void theFiveTablesAreFiledInMergeOrderAndCountedAsCpythonCountsThem() {
        assertThat(filed(ExceptionTypes.ATTRIBUTE_ERROR)).isEqualTo(ATTRIBUTE_ORDER);
        assertThat(filed(ExceptionTypes.NAME_ERROR)).isEqualTo(NAME_ORDER);
        assertThat(filed(ExceptionTypes.STOP_ITERATION)).isEqualTo(STOP_ORDER);
        assertThat(filed(ExceptionTypes.SYSTEM_EXIT)).isEqualTo(EXIT_ORDER);
        assertThat(filed(ExceptionTypes.IMPORT_ERROR)).isEqualTo(IMPORT_ORDER);

        assertThat(TypeDict.declared(ExceptionTypes.ATTRIBUTE_ERROR).keys()).hasSize(6);
        assertThat(TypeDict.declared(ExceptionTypes.NAME_ERROR).keys()).hasSize(3);
        assertThat(TypeDict.declared(ExceptionTypes.STOP_ITERATION).keys()).hasSize(3);
        assertThat(TypeDict.declared(ExceptionTypes.SYSTEM_EXIT).keys()).hasSize(3);
        assertThat(TypeDict.declared(ExceptionTypes.IMPORT_ERROR).keys()).hasSize(8);
        assertThat(visible(ExceptionTypes.ATTRIBUTE_ERROR)).hasSize(35);
        assertThat(visible(ExceptionTypes.NAME_ERROR)).hasSize(34);
        assertThat(visible(ExceptionTypes.STOP_ITERATION)).hasSize(34);
        assertThat(visible(ExceptionTypes.SYSTEM_EXIT)).hasSize(34);
        assertThat(visible(ExceptionTypes.IMPORT_ERROR)).hasSize(37);

        assertThat(added(ExceptionTypes.ATTRIBUTE_ERROR)).containsExactlyInAnyOrder("name", "obj");
        assertThat(added(ExceptionTypes.NAME_ERROR)).containsExactly("name");
        assertThat(added(ExceptionTypes.STOP_ITERATION)).containsExactly("value");
        assertThat(added(ExceptionTypes.SYSTEM_EXIT)).containsExactly("code");
        assertThat(added(ExceptionTypes.IMPORT_ERROR))
                .containsExactlyInAnyOrderElementsOf(IMPORT_FIELDS);

        // The two classes that file the sentence and nothing else run their parent's whole table.
        assertThat(filed(ExceptionTypes.UNBOUND_LOCAL_ERROR)).containsExactly("__doc__");
        assertThat(filed(ExceptionTypes.MODULE_NOT_FOUND_ERROR)).containsExactly("__doc__");
        assertThat(visible(ExceptionTypes.UNBOUND_LOCAL_ERROR)).hasSize(34);
        assertThat(visible(ExceptionTypes.MODULE_NOT_FOUND_ERROR)).hasSize(37);

        for (PyType kind :
                List.of(
                        ExceptionTypes.ATTRIBUTE_ERROR,
                        ExceptionTypes.NAME_ERROR,
                        ExceptionTypes.STOP_ITERATION,
                        ExceptionTypes.SYSTEM_EXIT,
                        ExceptionTypes.IMPORT_ERROR)) {
            assertThat(entry(kind, "__new__")).isNull();
            // The one row of the family a read does not hand out as it stands: `__new__` is a static method,
            // so the class the name is read through is bound into the answer, and the answer is the row of
            // the nearest class that filed one — {@code Exception} for the four under it, and {@code
            // BaseException} itself for {@code SystemExit}, which derives from nothing else.
            PyType owner =
                    kind == ExceptionTypes.SYSTEM_EXIT
                            ? ExceptionTypes.BASE_EXCEPTION
                            : ExceptionTypes.EXCEPTION;
            assertThat(Slots.getAttr(kind, "__new__")).isSameAs(entry(owner, "__new__"));
            assertThat(Slots.getAttr(Slots.getAttr(kind, "__new__"), "__self__")).isSameAs(owner);
            assertThat(((PyStr) Slots.getAttr(Slots.getAttr(kind, "__new__"), "__qualname__")).getValue())
                    .isEqualTo(owner.name() + ".__new__");
            assertThat(kind(Slots.getAttr(kind, "__new__"))).isEqualTo("builtin_function_or_method");
            assertThat(Slots.getAttr(kind, "__new__")).isSameAs(Slots.getAttr(kind, "__new__"));
        }
        assertThat(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__str__")).isNull();
        assertThat(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__repr__")).isNull();
        // A slot a class keeps is a row of the class that wrote it, and never copied down: the read of
        // `__str__` here answers with the base's row, while `__new__` — the one name of the family that is
        // a static method — is bound to the class it is read through and so is never the same object twice.
        assertThat(Slots.getAttr(ExceptionTypes.ATTRIBUTE_ERROR, "__str__"))
                .isSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__str__"));
        assertThat(Slots.getAttr(ExceptionTypes.ATTRIBUTE_ERROR, "__repr__"))
                .isSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__repr__"));
        assertThat(Slots.getAttr(ExceptionTypes.NAME_ERROR, "__reduce__"))
                .isSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__reduce__"));
        assertThat(Slots.getAttr(ExceptionTypes.MODULE_NOT_FOUND_ERROR, "__str__"))
                .isSameAs(entry(ExceptionTypes.IMPORT_ERROR, "__str__"));

        // A read answers with the one object the dictionary holds, so a row filed twice is caught here.
        for (String name : List.of("__init__", "__getstate__", "__reduce__", "name", "obj")) {
            assertThat(Slots.getAttr(ExceptionTypes.ATTRIBUTE_ERROR, name))
                    .isSameAs(entry(ExceptionTypes.ATTRIBUTE_ERROR, name));
        }
        for (String name : IMPORT_FIELDS) {
            assertThat(Slots.getAttr(ExceptionTypes.IMPORT_ERROR, name))
                    .isSameAs(entry(ExceptionTypes.IMPORT_ERROR, name));
        }
    }

    /**
     * What each of the five tables files a name as, and what that kind says about itself.
     *
     * <p>{@code ImportError} is the only one of the five with a {@code wrapper_descriptor} for {@code __str__}
     * — the only one whose text is read from a field rather than from the arguments — and the only one whose
     * {@code __reduce__} is its own rather than {@code BaseException}'s. {@code AttributeError} is the only one
     * with a {@code __getstate__} of its own. The nine member rows are the family's whole set of {@code
     * _Py_T_OBJECT} offsets outside {@code OSError}, and they are the only rows of these tables whose sentence
     * says what they hold rather than what they do; the two {@code method_descriptor}s and the getset's null
     * document answer {@code None}.
     */
    @Test
    void eachTableFilesItsRowsAsTheKindCDeclarationsThemAs() {
        assertThat(kind(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__init__")))
                .isEqualTo("wrapper_descriptor");
        assertThat(kind(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__getstate__")))
                .isEqualTo("method_descriptor");
        assertThat(kind(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__reduce__")))
                .isEqualTo("method_descriptor");
        assertThat(kind(entry(ExceptionTypes.NAME_ERROR, "name"))).isEqualTo("member_descriptor");
        assertThat(kind(entry(ExceptionTypes.STOP_ITERATION, "value")))
                .isEqualTo("member_descriptor");
        assertThat(kind(entry(ExceptionTypes.SYSTEM_EXIT, "code"))).isEqualTo("member_descriptor");
        assertThat(kind(entry(ExceptionTypes.IMPORT_ERROR, "__str__")))
                .isEqualTo("wrapper_descriptor");
        assertThat(kind(entry(ExceptionTypes.IMPORT_ERROR, "__reduce__")))
                .isEqualTo("method_descriptor");
        for (String name : List.of("name", "obj")) {
            assertThat(kind(entry(ExceptionTypes.ATTRIBUTE_ERROR, name)))
                    .isEqualTo("member_descriptor");
        }
        for (String name : IMPORT_FIELDS) {
            assertThat(kind(entry(ExceptionTypes.IMPORT_ERROR, name))).isEqualTo("member_descriptor");
        }
        assertThat(kind(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__doc__"))).isEqualTo("str");
        assertThat(kind(entry(ExceptionTypes.IMPORT_ERROR, "__doc__"))).isEqualTo("str");

        assertThat(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__init__").repr())
                .isEqualTo("<slot wrapper '__init__' of 'AttributeError' objects>");
        assertThat(entry(ExceptionTypes.ATTRIBUTE_ERROR, "__getstate__").repr())
                .isEqualTo("<method '__getstate__' of 'AttributeError' objects>");
        assertThat(entry(ExceptionTypes.ATTRIBUTE_ERROR, "name").repr())
                .isEqualTo("<member 'name' of 'AttributeError' objects>");
        assertThat(entry(ExceptionTypes.IMPORT_ERROR, "__str__").repr())
                .isEqualTo("<slot wrapper '__str__' of 'ImportError' objects>");
        assertThat(entry(ExceptionTypes.IMPORT_ERROR, "__reduce__").repr())
                .isEqualTo("<method '__reduce__' of 'ImportError' objects>");

        assertThat(carried(ExceptionTypes.ATTRIBUTE_ERROR, "__init__")).isEqualTo(INIT_DOC);
        assertThat(carried(ExceptionTypes.NAME_ERROR, "__init__")).isEqualTo(INIT_DOC);
        assertThat(carried(ExceptionTypes.IMPORT_ERROR, "__str__")).isEqualTo("Return str(self).");
        assertThat(carried(ExceptionTypes.ATTRIBUTE_ERROR, "__getstate__")).isNull();
        assertThat(carried(ExceptionTypes.ATTRIBUTE_ERROR, "__reduce__")).isNull();
        assertThat(carried(ExceptionTypes.IMPORT_ERROR, "__reduce__")).isNull();
        assertThat(carried(ExceptionTypes.ATTRIBUTE_ERROR, "name")).isEqualTo("attribute name");
        assertThat(carried(ExceptionTypes.ATTRIBUTE_ERROR, "obj")).isEqualTo("object");
        assertThat(carried(ExceptionTypes.NAME_ERROR, "name")).isEqualTo("name");
        assertThat(carried(ExceptionTypes.STOP_ITERATION, "value"))
                .isEqualTo("generator return value");
        assertThat(carried(ExceptionTypes.SYSTEM_EXIT, "code")).isEqualTo("exception code");
        assertThat(carried(ExceptionTypes.IMPORT_ERROR, "msg")).isEqualTo("exception message");
        assertThat(carried(ExceptionTypes.IMPORT_ERROR, "name")).isEqualTo("module name");
        assertThat(carried(ExceptionTypes.IMPORT_ERROR, "path")).isEqualTo("module path");
        assertThat(carried(ExceptionTypes.IMPORT_ERROR, "name_from"))
                .isEqualTo("name imported from module");

        assertThat(((PyStr) entry(ExceptionTypes.ATTRIBUTE_ERROR, "__doc__")).getValue())
                .isEqualTo("Attribute not found.");
        assertThat(((PyStr) entry(ExceptionTypes.NAME_ERROR, "__doc__")).getValue())
                .isEqualTo("Name not found globally.");
        assertThat(((PyStr) entry(ExceptionTypes.UNBOUND_LOCAL_ERROR, "__doc__")).getValue())
                .isEqualTo("Local name referenced but not bound to a value.");
        assertThat(((PyStr) entry(ExceptionTypes.STOP_ITERATION, "__doc__")).getValue())
                .isEqualTo("Signal the end from iterator.__next__().");
        assertThat(((PyStr) entry(ExceptionTypes.SYSTEM_EXIT, "__doc__")).getValue())
                .isEqualTo("Request to exit from the interpreter.");
        assertThat(((PyStr) entry(ExceptionTypes.IMPORT_ERROR, "__doc__")).getValue())
                .isEqualTo("Import can't find module, or can't find name in module.");
        assertThat(((PyStr) entry(ExceptionTypes.MODULE_NOT_FOUND_ERROR, "__doc__")).getValue())
                .isEqualTo("Module not found.");
    }

    /**
     * The three keyword-only tables, read against an argument tuple the creator has just emptied.
     *
     * <p>{@code AttributeError_init}, {@code NameError_init} and {@code ImportError_init} call {@code
     * PyArg_ParseTupleAndKeywords} on a tuple of their own making, which {@code tp_new} has left empty: the
     * positionals reach {@code e.args} and never a parameter slot, so {@code AttributeError('a', 2, name='n')}
     * binds and keeps two arguments, {@code NameError(name='n')} keeps none and reprs as {@code NameError()},
     * and the count in {@code takes at most 2 keyword arguments (3 given)} counts names only. A name the table
     * does not have is complained about by {@code error_unexpected_keyword_arg}, which offers the nearest of
     * <em>that table</em>: {@code nme} is offered {@code name}, {@code obj} is refused with no offer by {@code
     * NameError} whose table holds one name, and of two leftovers the first is the one named. Nothing but the
     * name is checked — {@code ImportError('m', name=1)} keeps the {@code 1}.
     */
    @Test
    void theThreeTablesAreParsedAgainstNoPositionalAtAll() {
        assertThat(((PyBaseException) make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT, OTHER)).args().repr())
                .isEqualTo("('a', 'g')");
        assertThat(Slots.getAttr(make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT, OTHER), "name"))
                .isSameAs(NONE);
        assertThat(messageAndName(ExceptionTypes.ATTRIBUTE_ERROR).repr())
                .isEqualTo("AttributeError('a')");
        assertThat(
                        ((PyBaseException)
                                        given(
                                                ExceptionTypes.ATTRIBUTE_ERROR,
                                                new PyObject[] {TEXT, TWO},
                                                new String[] {"name"},
                                                NAME))
                                .args()
                                .repr())
                .isEqualTo("('a', 2)");
        assertThat(given(ExceptionTypes.NAME_ERROR, new PyObject[0], new String[] {"name"}, NAME).repr())
                .isEqualTo("NameError()");
        assertThat(
                        ((PyBaseException)
                                        given(
                                                ExceptionTypes.NAME_ERROR,
                                                new PyObject[] {TEXT, TWO, ONE},
                                                new String[] {"name"},
                                                NAME))
                                .args()
                                .repr())
                .isEqualTo("('a', 2, 1)");

        PyObject carrier = PyList.of(ONE, TWO);
        PyObject both =
                given(
                        ExceptionTypes.ATTRIBUTE_ERROR,
                        new PyObject[] {TEXT},
                        new String[] {"name", "obj"},
                        NAME,
                        carrier);
        assertThat(Slots.getAttr(both, "name")).isSameAs(NAME);
        assertThat(Slots.getAttr(both, "obj")).isSameAs(carrier);
        assertThat(messageAndName(ExceptionTypes.IMPORT_ERROR).repr())
                .isEqualTo("ImportError('a')");
        // `BaseException_repr` reads the arguments and nothing else, so the name the class files a field for
        // never reaches the display — the message alone is the whole second half.
        assertThat(messageAndName(ExceptionTypes.MODULE_NOT_FOUND_ERROR).repr())
                .isEqualTo("ModuleNotFoundError('a')");
        assertThat(Slots.getAttr(given(ExceptionTypes.IMPORT_ERROR, new PyObject[] {TEXT},
                new String[] {"name"}, ONE), "name")).isEqualTo(ONE);

        refuses(
                "TypeError: AttributeError() takes at most 2 keyword arguments (3 given)",
                () ->
                        given(
                                ExceptionTypes.ATTRIBUTE_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"name", "obj", "extra"},
                                NAME,
                                ONE,
                                ONE));
        refuses(
                "TypeError: AttributeError() got an unexpected keyword argument 'nme'. Did you mean 'name'?",
                () ->
                        given(
                                ExceptionTypes.ATTRIBUTE_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"nme"},
                                NAME));
        refuses(
                "TypeError: AttributeError() got an unexpected keyword argument 'objj'. Did you mean 'obj'?",
                () ->
                        given(
                                ExceptionTypes.ATTRIBUTE_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"name", "objj"},
                                NAME,
                                ONE));
        refuses(
                "TypeError: AttributeError() got an unexpected keyword argument 'bad'",
                () ->
                        given(
                                ExceptionTypes.ATTRIBUTE_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"bad"},
                                ONE));
        refuses(
                "TypeError: NameError() takes at most 1 keyword argument (2 given)",
                () ->
                        given(
                                ExceptionTypes.NAME_ERROR,
                                new PyObject[0],
                                new String[] {"name", "obj"},
                                NAME,
                                ONE));
        refuses(
                "TypeError: NameError() got an unexpected keyword argument 'obj'",
                () ->
                        given(
                                ExceptionTypes.NAME_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"obj"},
                                ONE));
        refuses(
                "TypeError: NameError() got an unexpected keyword argument 'nme'. Did you mean 'name'?",
                () ->
                        given(
                                ExceptionTypes.NAME_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"nme"},
                                NAME));
        refuses(
                "TypeError: ImportError() takes at most 3 keyword arguments (4 given)",
                () ->
                        given(
                                ExceptionTypes.IMPORT_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"name", "path", "name_from", "extra"},
                                NAME,
                                TEXT,
                                OTHER,
                                ONE));
        refuses(
                "TypeError: ImportError() got an unexpected keyword argument 'bad'",
                () ->
                        given(
                                ExceptionTypes.IMPORT_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"bad"},
                                ONE));
        refuses(
                "TypeError: ImportError() got an unexpected keyword argument 'pathn'. Did you mean 'path'?",
                () ->
                        given(
                                ExceptionTypes.IMPORT_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"pathn"},
                                TEXT));
        refuses(
                "TypeError: ImportError() got an unexpected keyword argument 'namee'. Did you mean 'name'?",
                () ->
                        given(
                                ExceptionTypes.IMPORT_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"namee", "pathh"},
                                NAME,
                                TEXT));
    }

    /**
     * Which row a call runs, and in whose words a refusal is made.
     *
     * <p>A class that files no {@code __init__} of its own does not skip the step: {@code type_call} looks the
     * name up through the order and runs what it finds, so {@code UnboundLocalError('m', name='n')} fills
     * {@code NameError}'s offset and {@code ModuleNotFoundError('m', name='n')} fills the one {@code
     * ImportError} declares — and both are refused for a name the inherited table has not got, in that table's
     * words rather than their own. {@code StopIteration} and {@code SystemExit} are the opposite case: their
     * row parses nothing and answers with {@code _PyArg_NoKeywords}, which reads the type of the <em>value</em>
     * after {@code tp_new} has fixed it, so a class Python wrote under {@code StopIteration} is refused as
     * itself. A class that writes its own {@code __init__} takes the order's first answer and keeps the
     * arguments the creator stored, because the row that would have rewritten them never runs.
     */
    @Test
    void aCallOfAClassThatFilesNothingRunsTheRowOfTheClassThatDoes() {
        PyObject unbound = messageAndName(ExceptionTypes.UNBOUND_LOCAL_ERROR);
        assertThat(unbound.getType()).isSameAs(ExceptionTypes.UNBOUND_LOCAL_ERROR);
        assertThat(Slots.getAttr(unbound, "name").repr()).isEqualTo("'n'");
        PyObject module = messageAndName(ExceptionTypes.MODULE_NOT_FOUND_ERROR);
        assertThat(Slots.getAttr(module, "name").repr()).isEqualTo("'n'");
        assertThat(Slots.getAttr(module, "msg").repr()).isEqualTo("'a'");
        assertThat(unbound.repr()).isEqualTo("UnboundLocalError('a')");

        refuses(
                "TypeError: NameError() got an unexpected keyword argument 'bad'",
                () ->
                        given(
                                ExceptionTypes.UNBOUND_LOCAL_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"bad"},
                                ONE));
        refuses(
                "TypeError: ImportError() got an unexpected keyword argument 'bad'",
                () ->
                        given(
                                ExceptionTypes.MODULE_NOT_FOUND_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"bad"},
                                ONE));

        PyType local = classOf("Local", ExceptionTypes.UNBOUND_LOCAL_ERROR);
        assertThat(Slots.getAttr(messageAndName(local), "name").repr()).isEqualTo("'n'");
        refuses(
                "TypeError: NameError() got an unexpected keyword argument 'bad'",
                () -> given(local, new PyObject[] {TEXT}, new String[] {"bad"}, ONE));
        PyType missing = classOf("Missing", ExceptionTypes.MODULE_NOT_FOUND_ERROR);
        refuses(
                "TypeError: ImportError() takes at most 3 keyword arguments (4 given)",
                () ->
                        given(
                                missing,
                                new PyObject[] {TEXT},
                                new String[] {"name", "path", "name_from", "extra"},
                                NAME,
                                TEXT,
                                OTHER,
                                ONE));

        // The two rows that parse nothing, naming the value rather than the row behind it.
        refuses(
                "TypeError: StopIteration() takes no keyword arguments",
                () ->
                        given(
                                ExceptionTypes.STOP_ITERATION,
                                new PyObject[] {TEXT},
                                new String[] {"value"},
                                ONE));
        refuses(
                "TypeError: SystemExit() takes no keyword arguments",
                () -> given(ExceptionTypes.SYSTEM_EXIT, new PyObject[0], new String[] {"code"}, ONE));
        refuses(
                "TypeError: ValueError() takes no keyword arguments",
                () ->
                        given(
                                ExceptionTypes.VALUE_ERROR,
                                new PyObject[] {TEXT},
                                new String[] {"name"},
                                ONE));
        PyType stopped = classOf("Stopped", ExceptionTypes.STOP_ITERATION);
        refuses(
                "TypeError: Stopped() takes no keyword arguments",
                () -> given(stopped, new PyObject[] {TEXT}, new String[] {"flag"}, ONE));
        assertThat(make(stopped, TEXT).getType()).isSameAs(stopped);

        PyType own =
                classOf(
                        "Own",
                        ExceptionTypes.ATTRIBUTE_ERROR,
                        "__init__",
                        new PyNativeFunction("__init__", (args, kwNames) -> NONE));
        PyObject kept = given(own, new PyObject[] {TEXT}, new String[] {"name"}, NAME);
        assertThat(((PyBaseException) kept).args().repr()).isEqualTo("('a',)");
        assertThat(Slots.getAttr(kept, "name")).isSameAs(NONE);
        assertThat(run(kept, "__getstate__").repr()).isEqualTo("{'args': ('a',)}");

        // The same inherited row reached by hand, bound to the value the way `super().__init__()` binds it.
        PyObject hand = make(ExceptionTypes.MODULE_NOT_FOUND_ERROR);
        Slots.call(
                boundRow(ExceptionTypes.IMPORT_ERROR, "__init__", hand),
                new PyObject[] {TEXT, NAME},
                PyBoundMethod.keywordTuple(new String[] {"name"}));
        assertThat(((PyBaseException) hand).args().repr()).isEqualTo("('a',)");
        assertThat(Slots.getAttr(hand, "name").repr()).isEqualTo("'n'");
        assertThat(Slots.getAttr(hand, "msg").repr()).isEqualTo("'a'");
        PyObject child = classOf("Under", ExceptionTypes.ATTRIBUTE_ERROR).call(new PyObject[0], null);
        assertThat(
                        Slots.call(
                                        boundRow(ExceptionTypes.ATTRIBUTE_ERROR, "__getstate__", child),
                                        new PyObject[0],
                                        null)
                                .repr())
                .isEqualTo("{'args': ()}");
    }

    /**
     * The nine offsets of the five tables: unset, written, written {@code None}, and deleted.
     *
     * <p>{@code PyMember_GetOne} answers {@code OR_NONE}, so a field nobody filled, a field written {@code
     * None} and a field deleted are one thing to a read. The write and the delete go to the offset and not to
     * the instance dictionary, which is what makes these nine names data descriptors a dictionary entry cannot
     * shadow — {@code e.name = 'a'} leaves {@code e.__dict__} empty — and a second {@code del e.name} answers
     * the same {@code None} rather than an {@code AttributeError}. The {@code obj} of {@code AttributeError} is
     * the one field that keeps the very object it was handed, identity and all.
     */
    @Test
    void aFieldNeverFilledADeletedFieldAndAFieldWrittenNoneReadAlike() {
        for (int at = 0; at < FIELDS.length; at++) {
            PyType owner = OWNERS[at];
            String field = FIELDS[at];
            assertThat(Slots.getAttr(make(owner), field)).as(field).isSameAs(NONE);

            PyObject written = make(owner);
            Slots.setAttr(written, field, TEXT);
            assertThat(Slots.getAttr(written, field)).isSameAs(TEXT);
            assertThat(dictOf(written)).isEqualTo("{}");
            Slots.setAttr(written, field, NONE);
            assertThat(Slots.getAttr(written, field)).isSameAs(NONE);
            Slots.delAttr(written, field);
            assertThat(Slots.getAttr(written, field)).isSameAs(NONE);
            Slots.delAttr(written, field);
            assertThat(dictOf(written)).isEqualTo("{}");
        }

        // The write half reached as a descriptor, which fills the same offset the parser fills.
        PyObject through = make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT);
        putOn(ExceptionTypes.ATTRIBUTE_ERROR, "name", through, TWO);
        assertThat(Slots.getAttr(through, "name")).isEqualTo(TWO);
        assertThat(dictOf(through)).isEqualTo("{}");
        assertThat(run(through, "__getstate__").repr()).isEqualTo("{'name': 2, 'args': ('a',)}");

        // A row of one class read over a value of another, in the member's own words.
        refuses(
                "TypeError: descriptor 'name' for 'AttributeError' objects doesn't apply to a 'int' object",
                () -> getOf(ExceptionTypes.ATTRIBUTE_ERROR, "name", ONE));
        refuses(
                "TypeError: descriptor 'name' for 'AttributeError' objects doesn't apply to a 'ImportError' object",
                () ->
                        getOf(
                                ExceptionTypes.ATTRIBUTE_ERROR,
                                "name",
                                make(ExceptionTypes.IMPORT_ERROR, TEXT)));
        refuses(
                "TypeError: descriptor 'value' for 'StopIteration' objects doesn't apply to a 'SystemExit' object",
                () ->
                        getOf(
                                ExceptionTypes.STOP_ITERATION,
                                "value",
                                make(ExceptionTypes.SYSTEM_EXIT, ONE)));
        assertThat(getOf(ExceptionTypes.NAME_ERROR, "name",
                messageAndName(ExceptionTypes.NAME_ERROR))).isSameAs(NAME);

        // The interpreter's own failure leaves both fields set with the row never running, and the row then
        // writes both back out of a parse that named neither.
        PyObject carrier = PyList.of(ONE);
        PyBaseException failed = caught(() -> Slots.getAttr(carrier, "nope"));
        assertThat(failed.getType()).isSameAs(ExceptionTypes.ATTRIBUTE_ERROR);
        assertThat(Slots.getAttr(failed, "name").repr()).isEqualTo("'nope'");
        assertThat(Slots.getAttr(failed, "obj")).isSameAs(carrier);
        call(ExceptionTypes.ATTRIBUTE_ERROR, "__init__", failed, TEXT);
        assertThat(Slots.getAttr(failed, "name")).isSameAs(NONE);
        assertThat(Slots.getAttr(failed, "obj")).isSameAs(NONE);
        assertThat(((PyBaseException) failed).args().repr()).isEqualTo("('a',)");
    }

    /**
     * The one {@code __getstate__} of the family that is not {@code object}'s, and the reduction it forces.
     *
     * <p>{@code AttributeError_getstate} answers a dictionary always: a copy of the instance dictionary —
     * reading it makes none, so {@code e.__getstate__() is e.__dict__} is false whichever way the value was
     * made — with {@code args} in it because {@code BaseException_new} leaves that pointer non-null even for a
     * call with no arguments, and {@code name} only where the pointer is set. A name written as {@code None}
     * counts and a deleted one does not, and {@code obj} is not in the state at all. {@code
     * AttributeError_reduce} therefore answers three places every time where {@code BaseException} answers two
     * or three on the same pointers, and every other class of the slice keeps {@code object}'s answer — {@code
     * None} until a write makes a dictionary.
     */
    @Test
    void attributeStateAlwaysCarriesTheArgumentsAndNeverTheObject() {
        assertThat(run(make(ExceptionTypes.ATTRIBUTE_ERROR), "__getstate__").repr())
                .isEqualTo("{'args': ()}");
        assertThat(
                        run(
                                call(ExceptionTypes.BASE_EXCEPTION, "__new__",
                                        ExceptionTypes.ATTRIBUTE_ERROR),
                                "__getstate__")
                                .repr())
                .isEqualTo("{'args': ()}");
        assertThat(run(make(ExceptionTypes.NAME_ERROR, TEXT), "__getstate__")).isSameAs(NONE);

        PyObject bare = make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT);
        assertThat(run(bare, "__getstate__")).isNotSameAs(Slots.getAttr(bare, "__dict__"));
        PyObject fresh = make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT);
        assertThat(run(fresh, "__getstate__")).isNotSameAs(NONE);
        assertThat(dictOf(fresh)).isEqualTo("{}");

        PyObject named = messageAndName(ExceptionTypes.ATTRIBUTE_ERROR);
        Slots.setAttr(named, "x", TWO);
        assertThat(run(named, "__getstate__").repr())
                .isEqualTo("{'x': 2, 'name': 'n', 'args': ('a',)}");
        assertThat(run(named, "__reduce__").repr())
                .isEqualTo("(<class 'AttributeError'>, ('a',), {'x': 2, 'name': 'n', 'args': ('a',)})");
        Slots.delAttr(named, "name");
        assertThat(run(named, "__getstate__").repr()).isEqualTo("{'x': 2, 'args': ('a',)}");

        assertThat(
                        run(
                                given(
                                        ExceptionTypes.ATTRIBUTE_ERROR,
                                        new PyObject[0],
                                        new String[] {"name"},
                                        NONE),
                                "__getstate__")
                                .repr())
                .isEqualTo("{'name': None, 'args': ()}");

        PyObject onlyObject =
                given(
                        ExceptionTypes.ATTRIBUTE_ERROR,
                        new PyObject[] {TEXT},
                        new String[] {"obj"},
                        THREE);
        assertThat(run(onlyObject, "__getstate__").repr()).isEqualTo("{'args': ('a',)}");
        assertThat(((PyTuple) run(onlyObject, "__reduce__")).size()).isEqualTo(3);
        assertThat(places(run(onlyObject, "__reduce__")).repr()).isEqualTo("{'args': ('a',)}");

        // The same two rows, reached under a class that files neither.
        PyType sub = classOf("Sub", ExceptionTypes.ATTRIBUTE_ERROR);
        PyObject ofSub = messageAndName(sub);
        assertThat(run(ofSub, "__getstate__").repr()).isEqualTo("{'name': 'n', 'args': ('a',)}");
        assertThat(run(ofSub, "__reduce__").repr())
                .isEqualTo("(<class 'Sub'>, ('a',), {'name': 'n', 'args': ('a',)})");

        // Re-initialising writes both fields from the parse either way, which is how a name is lost.
        PyObject again = messageAndName(ExceptionTypes.ATTRIBUTE_ERROR);
        call(ExceptionTypes.ATTRIBUTE_ERROR, "__init__", again, PyStr.of("z"));
        assertThat(((PyBaseException) again).args().repr()).isEqualTo("('z',)");
        assertThat(Slots.getAttr(again, "name")).isSameAs(NONE);
        assertThat(run(again, "__reduce__").repr())
                .isEqualTo("(<class 'AttributeError'>, ('z',), {'args': ('z',)})");

        refuses(
                "TypeError: AttributeError.__reduce__() takes no arguments (1 given)",
                () -> run(make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT), "__reduce__", ONE));
        refuses(
                "TypeError: AttributeError.__getstate__() takes no keyword arguments",
                () ->
                        Slots.call(
                                Slots.getAttr(make(ExceptionTypes.ATTRIBUTE_ERROR), "__getstate__"),
                                new PyObject[] {ONE},
                                PyBoundMethod.keywordTuple(new String[] {"x"})));
        refuses(
                "TypeError: descriptor '__reduce__' for 'AttributeError' objects doesn't apply to a 'int' object",
                () -> call(ExceptionTypes.ATTRIBUTE_ERROR, "__reduce__", ONE));
        refuses(
                "TypeError: descriptor '__getstate__' for 'AttributeError' objects doesn't apply to a 'ImportError' object",
                () ->
                        call(
                                ExceptionTypes.ATTRIBUTE_ERROR,
                                "__getstate__",
                                make(ExceptionTypes.IMPORT_ERROR, TEXT)));
        refuses(
                "TypeError: descriptor '__init__' requires a 'AttributeError' object but received a 'int'",
                () -> call(ExceptionTypes.ATTRIBUTE_ERROR, "__init__", ONE));
        refuses(
                "TypeError: descriptor '__init__' of 'AttributeError' object needs an argument",
                () -> call(ExceptionTypes.ATTRIBUTE_ERROR, "__init__"));
    }

    /**
     * The state {@code ImportError_getstate} adds to a reduction, and the message it never adds.
     *
     * <p>Where none of {@code name}, {@code path} and {@code name_from} is set, {@code __reduce__} answers the
     * pair, exactly as {@code BaseException} does. Where a field is set the state is the dictionary itself —
     * {@code Py_NewRef} and not a copy — so a value whose dictionary has been read at all reduces to three
     * places whose third is that very object, and with a field set it is a copy with the three names added
     * after the dictionary's own entries, in the order the class files its members. {@code msg} never counts:
     * it is what {@code str(e)} reads, and the arguments carry it, so deleting the name a call gave takes the
     * third place away again even though the message is still standing.
     */
    @Test
    void importStateIsTheDictionaryItselfUntilAFieldIsSet() {
        PyObject bare = make(ExceptionTypes.IMPORT_ERROR);
        assertThat(Slots.getAttr(bare, "msg")).isSameAs(NONE);
        assertThat(((PyBaseException) make(ExceptionTypes.IMPORT_ERROR, TEXT)).str()).isEqualTo("a");
        assertThat(((PyBaseException) make(ExceptionTypes.IMPORT_ERROR)).str()).isEmpty();
        assertThat(((PyBaseException) make(ExceptionTypes.IMPORT_ERROR, THREE)).str()).isEqualTo("3");
        assertThat(Slots.getAttr(make(ExceptionTypes.IMPORT_ERROR, THREE), "msg")).isEqualTo(THREE);
        assertThat(((PyBaseException) make(ExceptionTypes.IMPORT_ERROR, TEXT, OTHER)).str())
                .isEqualTo("('a', 'g')");
        PyObject overwritten = make(ExceptionTypes.IMPORT_ERROR, TEXT);
        Slots.setAttr(overwritten, "msg", THREE);
        // The field is no longer a `str`, which is the one test the row makes, so the arguments answer.
        assertThat(((PyBaseException) overwritten).str()).isEqualTo("a");
        assertThat(Slots.getAttr(overwritten, "msg")).isEqualTo(THREE);

        // `msg` is filled by a test on the count, which is why two arguments have no message at all.
        assertThat(((PyBaseException) make(ExceptionTypes.IMPORT_ERROR, TEXT, OTHER)).memberField("msg"))
                .isSameAs(NONE);

        PyObject pair = make(ExceptionTypes.IMPORT_ERROR, TEXT);
        assertThat(((PyTuple) run(pair, "__reduce__")).size()).isEqualTo(2);
        assertThat(run(pair, "__reduce__").repr()).isEqualTo("(<class 'ImportError'>, ('a',))");
        PyObject read = make(ExceptionTypes.IMPORT_ERROR, TEXT);
        Slots.getAttr(read, "__dict__");
        assertThat(((PyTuple) run(read, "__reduce__")).size()).isEqualTo(3);
        assertThat(Slots.getAttr(read, "__dict__")).isSameAs(places(run(read, "__reduce__")));

        PyObject written = make(ExceptionTypes.IMPORT_ERROR, TEXT);
        Slots.setAttr(written, "x", TWO);
        assertThat(run(written, "__reduce__").repr()).isEqualTo("(<class 'ImportError'>, ('a',), {'x': 2})");

        PyObject named =
                given(
                        ExceptionTypes.IMPORT_ERROR,
                        new PyObject[] {TEXT},
                        new String[] {"name", "path", "name_from"},
                        NAME,
                        TEXT,
                        OTHER);
        Slots.setAttr(named, "x", TWO);
        assertThat(Slots.getAttr(named, "__dict__")).isNotSameAs(places(run(named, "__reduce__")));
        assertThat(run(named, "__reduce__").repr())
                .isEqualTo(
                        "(<class 'ImportError'>, ('a',), {'x': 2, 'name': 'n', 'path': 'a', 'name_from': 'g'})");
        Slots.delAttr(named, "name");
        Slots.delAttr(named, "path");
        Slots.delAttr(named, "name_from");
        assertThat(run(named, "__reduce__").repr()).isEqualTo("(<class 'ImportError'>, ('a',), {'x': 2})");

        assertThat(run(messageAndName(ExceptionTypes.IMPORT_ERROR), "__reduce__").repr())
                .isEqualTo("(<class 'ImportError'>, ('a',), {'name': 'n'})");
        assertThat(run(messageAndName(ExceptionTypes.MODULE_NOT_FOUND_ERROR), "__reduce__").repr())
                .isEqualTo("(<class 'ModuleNotFoundError'>, ('a',), {'name': 'n'})");
        assertThat(run(make(ExceptionTypes.MODULE_NOT_FOUND_ERROR, TEXT, OTHER), "__reduce__").repr())
                .isEqualTo("(<class 'ModuleNotFoundError'>, ('a', 'g'))");

        refuses(
                "TypeError: ImportError.__reduce__() takes no arguments (1 given)",
                () -> run(make(ExceptionTypes.IMPORT_ERROR, TEXT), "__reduce__", ONE));
        refuses(
                "TypeError: descriptor '__reduce__' for 'ImportError' objects doesn't apply to a 'int' object",
                () -> call(ExceptionTypes.IMPORT_ERROR, "__reduce__", ONE));
        refuses(
                "TypeError: descriptor '__reduce__' for 'ImportError' objects doesn't apply to a 'AttributeError' object",
                () ->
                        call(
                                ExceptionTypes.IMPORT_ERROR,
                                "__reduce__",
                                make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT)));
        refuses(
                "TypeError: descriptor '__str__' requires a 'ImportError' object but received a 'AttributeError'",
                () ->
                        call(
                                ExceptionTypes.IMPORT_ERROR,
                                "__str__",
                                make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT)));
    }

    /**
     * The two leaves whose field comes from a positional, and the two ways they treat an empty call.
     *
     * <p>{@code StopIteration_init} and {@code SystemExit_init} read the same arguments and answer two
     * different questions. One positional is the field for both, but two are the first of them for {@code
     * StopIteration} and a tuple of them for {@code SystemExit}, and none clears {@code value} to {@code None}
     * where it leaves {@code code} exactly as it stood — so {@code e.__init__()} on a stopped iterator loses
     * what it carried and the same call on an exit keeps it, while the arguments empty in both. Neither class
     * files a state, so neither field reaches a reduction: {@code StopIteration('a')} is the pair, and the
     * value is not in it.
     */
    @Test
    void stopIterationClearsWhatSystemExitLeavesStanding() {
        assertThat(Slots.getAttr(make(ExceptionTypes.STOP_ITERATION, TEXT, OTHER), "value"))
                .isSameAs(TEXT);
        assertThat(make(ExceptionTypes.STOP_ITERATION, TEXT, OTHER).repr())
                .isEqualTo("StopIteration('a', 'g')");
        assertThat(Slots.getAttr(make(ExceptionTypes.SYSTEM_EXIT, TEXT, OTHER), "code").repr())
                .isEqualTo("('a', 'g')");
        assertThat(Slots.getAttr(make(ExceptionTypes.SYSTEM_EXIT, THREE), "code")).isEqualTo(THREE);
        assertThat(Slots.getAttr(make(ExceptionTypes.STOP_ITERATION), "value")).isSameAs(NONE);
        assertThat(Slots.getAttr(make(ExceptionTypes.SYSTEM_EXIT), "code")).isSameAs(NONE);
        assertThat(
                        Slots.getAttr(
                                call(ExceptionTypes.BASE_EXCEPTION, "__new__",
                                        ExceptionTypes.STOP_ITERATION),
                                "value"))
                .isSameAs(NONE);

        PyObject stopped = make(ExceptionTypes.STOP_ITERATION, THREE);
        call(ExceptionTypes.STOP_ITERATION, "__init__", stopped);
        assertThat(Slots.getAttr(stopped, "value")).isSameAs(NONE);
        assertThat(((PyBaseException) stopped).args().repr()).isEqualTo("()");
        PyObject exiting = make(ExceptionTypes.SYSTEM_EXIT, THREE);
        call(ExceptionTypes.SYSTEM_EXIT, "__init__", exiting);
        assertThat(Slots.getAttr(exiting, "code")).isEqualTo(THREE);
        assertThat(((PyBaseException) exiting).args().repr()).isEqualTo("()");
        call(ExceptionTypes.SYSTEM_EXIT, "__init__", exiting, TEXT, OTHER);
        assertThat(Slots.getAttr(exiting, "code").repr()).isEqualTo("('a', 'g')");

        assertThat(run(make(ExceptionTypes.STOP_ITERATION, TEXT), "__reduce__").repr())
                .isEqualTo("(<class 'StopIteration'>, ('a',))");
        assertThat(run(make(ExceptionTypes.STOP_ITERATION, TEXT), "__getstate__")).isSameAs(NONE);
        assertThat(run(make(ExceptionTypes.SYSTEM_EXIT, THREE), "__reduce__").repr())
                .isEqualTo("(<class 'SystemExit'>, (3,))");
        assertThat(((PyBaseException) make(ExceptionTypes.SYSTEM_EXIT)).str()).isEmpty();
        assertThat(((PyBaseException) make(ExceptionTypes.SYSTEM_EXIT, TEXT, OTHER)).str())
                .isEqualTo("('a', 'g')");

        // The exit the runtime itself raises is made through the class, so its code is the one it was given.
        PyBaseException exit =
                caught(
                        () ->
                                Slots.call(
                                        PyRuntime.sysAttribute("exit"),
                                        new PyObject[] {THREE},
                                        null));
        assertThat(exit.getType()).isSameAs(ExceptionTypes.SYSTEM_EXIT);
        assertThat(Slots.getAttr(exit, "code")).isEqualTo(THREE);
        assertThat(((PyBaseException) exit).args().repr()).isEqualTo("(3,)");
        PyBaseException nothing =
                caught(
                        () ->
                                Slots.call(
                                        PyRuntime.sysAttribute("exit"), new PyObject[0], null));
        assertThat(Slots.getAttr(nothing, "code")).isSameAs(NONE);
        assertThat(((PyBaseException) nothing).args().repr()).isEqualTo("()");
    }

    /**
     * The sentence one class of the family reads from a field rather than from its arguments.
     *
     * <p>{@code ImportError_str} prints {@code msg} when it is exactly a {@code str} and hands every other
     * answer to {@code BaseException_str}, which is why {@code str()} of a two-argument value is the pair and
     * of a value whose message was overwritten with an {@code int} is that number as the argument tuple reads
     * it. {@code ModuleNotFoundError} files no row and runs the same function, and so does a class Python wrote
     * under it — while a {@code __str__} in the class body is the answer the order finds first, and the base's
     * wrapper still runs the base's rule over such a value.
     */
    @Test
    void theStrRowReadsTheFieldWhereTheBasesWrapperReadsTheArguments() {
        PyObject value = make(ExceptionTypes.IMPORT_ERROR, TEXT);
        Slots.setAttr(value, "msg", OTHER);
        assertThat(((PyStr) call(ExceptionTypes.IMPORT_ERROR, "__str__", value)).getValue())
                .isEqualTo("g");
        assertThat(((PyStr) call(ExceptionTypes.BASE_EXCEPTION, "__str__", value)).getValue())
                .isEqualTo("a");
        assertThat(((PyBaseException) value).str()).isEqualTo("g");
        // The row checks only that the value derives from the class that filed it, which is how the class
        // under this one that files no `__str__` of its own runs this function as its own.
        assertThat(
                        ((PyStr)
                                        call(
                                                ExceptionTypes.IMPORT_ERROR,
                                                "__str__",
                                                messageAndName(
                                                        ExceptionTypes.MODULE_NOT_FOUND_ERROR)))
                                .getValue())
                .isEqualTo("a");

        assertThat(((PyBaseException) make(ExceptionTypes.MODULE_NOT_FOUND_ERROR, TEXT)).str())
                .isEqualTo("a");
        assertThat(((PyBaseException) make(ExceptionTypes.MODULE_NOT_FOUND_ERROR, TEXT, OTHER)).str())
                .isEqualTo("('a', 'g')");
        assertThat(((PyBaseException) make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT, OTHER)).str())
                .isEqualTo("('a', 'g')");
        assertThat(((PyBaseException) make(ExceptionTypes.ATTRIBUTE_ERROR, THREE)).str())
                .isEqualTo("3");
        refuses(
                "TypeError: descriptor '__str__' requires a 'ImportError' object but received a 'AttributeError'",
                () ->
                        call(
                                ExceptionTypes.IMPORT_ERROR,
                                "__str__",
                                make(ExceptionTypes.ATTRIBUTE_ERROR, TEXT)));
    }
}
