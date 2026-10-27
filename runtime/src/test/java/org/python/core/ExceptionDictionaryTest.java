package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The three dictionaries the exception family files of its own, measured against CPython 3.14.8.
 *
 * <p>Corpus cases 72 and 73 read {@code BaseException}'s fifteen rows, {@code KeyError}'s two and {@code
 * OSError}'s ten from Python, one line per answer. What is asserted here is the half a script cannot point
 * at: the order the four C tables are merged into, which only the dictionary sees; and the one object the
 * dictionary and a read of the class hand out for the same name, so a duplicated or reordered member row is
 * caught here rather than in a printed row. Then the two decisions a wrapper hides. The first is which
 * function a slot wrapper runs — the one of the type that <em>filed</em> it, not the {@code tp_str} the
 * value's own class would run — which is why {@code BaseException.__str__(e)} answers over the arguments of
 * an {@code OSError} and of a {@code KeyError} while {@code str(e)} answers over their fields and their
 * {@code repr}. The second is {@code oserror_use_init}, which {@code OSError_new} and {@code OSError_init}
 * share and which a script only sees as an exception that came out empty; here it is asserted as the switch
 * itself, over four classes that answer it four different ways. Then the five fields, whose delete writes a
 * null pointer a read cannot tell from a field that was never filled — and the getset among them, which has
 * no such answer and refuses by name.
 */
class ExceptionDictionaryTest {

    /** The fifteen names {@code BaseException} files, in the order its dictionary answers them. */
    private static final List<String> BASE_ORDER =
            List.of(
                    "__new__",
                    "__repr__",
                    "__str__",
                    "__init__",
                    "__reduce__",
                    "__setstate__",
                    "with_traceback",
                    "add_note",
                    "__suppress_context__",
                    "__dict__",
                    "args",
                    "__traceback__",
                    "__context__",
                    "__cause__",
                    "__doc__");

    /** The two names {@code KeyError} files: the {@code tp_str} it writes, and the sentence it carries. */
    private static final List<String> KEY_ORDER = List.of("__str__", "__doc__");

    /** The ten names {@code OSError} files, the four members in {@code OSError_members}'s own order. */
    private static final List<String> OS_ORDER =
            List.of(
                    "__new__",
                    "__str__",
                    "__init__",
                    "__reduce__",
                    "errno",
                    "strerror",
                    "filename",
                    "filename2",
                    "characters_written",
                    "__doc__");

    /** The five names that make {@code dir(OSError)} five longer than {@code dir(BaseException)}. */
    private static final List<String> FIELDS =
            List.of("errno", "strerror", "filename", "filename2", "characters_written");

    /** The four {@code _Py_T_OBJECT} offsets, as {@code OSError_members} lists them. */
    private static final List<String> MEMBERS = List.of("errno", "strerror", "filename", "filename2");

    /** The one keyword these methods refuse, spelled as the calling convention hands it over. */
    private static final PyTuple FLAG = PyBoundMethod.keywordTuple(new String[] {"flag"});

    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO = PyInt.of(2);
    private static final PyObject UNMAPPED = PyInt.of(9999);
    private static final PyObject TEXT = PyStr.of("a");
    private static final PyObject FILE = PyStr.of("f");
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

    /** One name of a type's dictionary, called the way a program calls it: the receiver comes first. */
    private static PyObject call(PyType kind, String name, PyObject... arguments) {
        return Slots.call(entry(kind, name), arguments, null);
    }

    /** {@code Name(*arguments)}: the call a program makes, put through {@code type_call}. */
    private static PyObject make(PyType kind, PyObject... arguments) {
        return kind.call(arguments, null);
    }

    /** {@code Name(positionals..., flag=1)}: the one keyword, with its value appended as a call would. */
    private static PyObject calledWithKeyword(PyType kind, PyObject... arguments) {
        PyObject[] all = Arrays.copyOf(arguments, arguments.length + 1);
        all[arguments.length] = ONE;
        return kind.call(all, FLAG);
    }

    /** {@code descriptor.__get__(receiver)}: the read half of a member or a getset of the table. */
    private static PyObject getOf(PyType kind, String name, PyObject receiver) {
        return Slots.call(
                Slots.getAttr(entry(kind, name), "__get__"), new PyObject[] {receiver}, null);
    }

    /** The same name read through a value and called, which is how a method of the class reaches it. */
    private static PyObject run(PyObject receiver, String name, PyObject... arguments) {
        return Slots.call(Slots.getAttr(receiver, name), arguments, null);
    }

    /** The sentence one filed member answers {@code __doc__} with; {@code null} where it answers none. */
    private static String carried(PyType kind, String name) {
        PyObject text = Slots.getAttr(entry(kind, name), "__doc__");
        return text == NONE ? null : ((PyStr) text).getValue();
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

    /** One entry of the state dictionary {@code __setstate__} is handed. */
    private static PyDict state(PyObject key, PyObject value) {
        PyDict state = PyDict.empty();
        state.put(key, value);
        return state;
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
     * The fifteen rows of {@code BaseException}, in the order the four C tables are merged into.
     *
     * <p>{@code type_ready_fill_dict} runs {@code add_operators} before {@code type_add_methods}, so the three
     * slot wrappers — {@code tp_repr}, {@code tp_str} and {@code tp_init} — sit between {@code __new__} and
     * the four of {@code BaseException_methods}; {@code __suppress_context__} is the one row of {@code
     * BaseException_members} and comes next, then the five of {@code BaseException_getset} with {@code
     * __dict__} first among them, then the type's own sentence. Eighteen of the thirty-three names {@code
     * dir} answers are {@code object}'s, which this class borrows without filing a row for.
     */
    @Test
    void fifteenRowsFileTheOrderTheCTablesWereMergedIn() {
        assertThat(filed(ExceptionTypes.BASE_EXCEPTION)).isEqualTo(BASE_ORDER);
        assertThat(TypeDict.declared(ExceptionTypes.BASE_EXCEPTION).keys()).hasSize(15);
        assertThat(visible(ExceptionTypes.BASE_EXCEPTION)).hasSize(33);
        assertThat(BASE_ORDER).startsWith("__new__").endsWith("__doc__");
        assertThat(BASE_ORDER.subList(1, 4)).containsExactly("__repr__", "__str__", "__init__");
        assertThat(BASE_ORDER.subList(4, 8))
                .containsExactly("__reduce__", "__setstate__", "with_traceback", "add_note");
        assertThat(BASE_ORDER.subList(8, 14))
                .containsExactly(
                        "__suppress_context__", "__dict__", "args", "__traceback__", "__context__",
                        "__cause__");

        assertThat(kind(entry(ExceptionTypes.BASE_EXCEPTION, "__new__")))
                .isEqualTo("builtin_function_or_method");
        for (String name : List.of("__repr__", "__str__", "__init__")) {
            assertThat(kind(entry(ExceptionTypes.BASE_EXCEPTION, name)))
                    .isEqualTo("wrapper_descriptor");
        }
        for (String name : List.of("__reduce__", "__setstate__", "with_traceback", "add_note")) {
            assertThat(kind(entry(ExceptionTypes.BASE_EXCEPTION, name)))
                    .isEqualTo("method_descriptor");
        }
        assertThat(kind(entry(ExceptionTypes.BASE_EXCEPTION, "__suppress_context__")))
                .isEqualTo("member_descriptor");
        for (String name : List.of("__dict__", "args", "__traceback__", "__context__", "__cause__")) {
            assertThat(kind(entry(ExceptionTypes.BASE_EXCEPTION, name)))
                    .isEqualTo("getset_descriptor");
        }
        assertThat(kind(entry(ExceptionTypes.BASE_EXCEPTION, "__doc__"))).isEqualTo("str");

        // The rows a read answers with the very object the dictionary holds: one per name, so a
        // duplicated member row or a table merged twice shows up as two objects where CPython has one.
        for (String name : BASE_ORDER) {
            if (name.equals("__dict__") || name.equals("__doc__")) {
                continue;
            }
            assertThat(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, name))
                    .isSameAs(entry(ExceptionTypes.BASE_EXCEPTION, name));
        }
        // The one name a class never reads as its own row: `type.__dict__` is the proxy over the table.
        assertThat(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, "__dict__"))
                .isNotSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__dict__"));
        // `__doc__` is the second name whose read is not its row: `type_get_doc` of a class outside the
        // heap builds the sentence again from the C document, so the row and the read agree and are two.
        assertThat(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, "__doc__"))
                .isNotSameAs(entry(ExceptionTypes.BASE_EXCEPTION, "__doc__"))
                .isEqualTo(entry(ExceptionTypes.BASE_EXCEPTION, "__doc__"));
        // A class in the heap is the other half of the same rule: the getset finds no C document and reads
        // the dictionary instead, which is the row itself.
        PyType docfiled = classOf("Documented", ExceptionTypes.BASE_EXCEPTION);
        assertThat(Slots.getAttr(docfiled, "__doc__"))
                .isSameAs(entry(docfiled, "__doc__"));

        // The eight names only `dir` has, and the two rows a leaf of the family files beside them.
        List<String> borrowed = new ArrayList<>(visible(ExceptionTypes.BASE_EXCEPTION));
        borrowed.removeAll(BASE_ORDER);
        assertThat(borrowed).hasSize(18).doesNotContain("__str__", "args", "__dict__");
        assertThat(filed(ExceptionTypes.VALUE_ERROR)).containsExactly("__new__", "__doc__");
        assertThat(filed(ExceptionTypes.FILE_NOT_FOUND_ERROR)).containsExactly("__doc__");
    }

    /**
     * {@code KeyError}'s two rows, and the names it adds to nothing.
     *
     * <p>{@code KeyError} writes a {@code tp_str} and no other slot, so {@code add_operators} files the one
     * wrapper and its own sentence is the only other row: two names, and a {@code dir} of thirty-three because
     * every other name it answers is {@code BaseException}'s. The wrapper it files is not the base one, but
     * the creator it reads is — a class that writes no {@code tp_new} of its own borrows {@code
     * BaseException}'s row rather than filing a second one.
     */
    @Test
    void keyErrorFilesOnlyItsOwnStrAndItsSentence() {
        assertThat(filed(ExceptionTypes.KEY_ERROR)).isEqualTo(KEY_ORDER);
        assertThat(TypeDict.declared(ExceptionTypes.KEY_ERROR).keys()).hasSize(2);
        assertThat(visible(ExceptionTypes.KEY_ERROR)).hasSize(33);
        assertThat(kind(entry(ExceptionTypes.KEY_ERROR, "__str__")))
                .isEqualTo("wrapper_descriptor");
        assertThat(carried(ExceptionTypes.KEY_ERROR, "__str__")).isEqualTo("Return str(self).");

        List<String> added = new ArrayList<>(visible(ExceptionTypes.KEY_ERROR));
        added.removeAll(visible(ExceptionTypes.BASE_EXCEPTION));
        assertThat(added).isEmpty();
        assertThat(Slots.getAttr(ExceptionTypes.KEY_ERROR, "__str__"))
                .isNotSameAs(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, "__str__"));
        assertThat(entry(ExceptionTypes.KEY_ERROR, "__new__")).isNull();
        assertThat(entry(ExceptionTypes.BASE_EXCEPTION, "__new__")).isNotNull();
        assertThat(entry(ExceptionTypes.KEY_ERROR, "__str__").repr())
                .isEqualTo("<slot wrapper '__str__' of 'KeyError' objects>");
    }

    /**
     * The ten rows {@code OSError} files, and the five fields that make its {@code dir} five longer.
     *
     * <p>The same merge explains this table, with one difference in the wrappers: {@code OSError} writes a
     * {@code tp_str} and an {@code tp_init} but keeps {@code BaseException}'s {@code tp_repr}, so {@code
     * add_operators} files the two and leaves {@code __repr__} out of the dictionary entirely — which is why
     * the same read of {@code __repr__} answers with the base's row while {@code __str__} is a row of its own.
     * After the one {@code method_descriptor} come the four offsets of {@code OSError_members}, in the order
     * {@code PyOSErrorObject} declares its fields, and the one getset of {@code OSError_getset}, which no
     * other class of the three files at all.
     */
    @Test
    void oserrorFilesTheFiveFieldsNoOtherClassOfTheThreeKeeps() {
        assertThat(filed(ExceptionTypes.OS_ERROR)).isEqualTo(OS_ORDER);
        assertThat(TypeDict.declared(ExceptionTypes.OS_ERROR).keys()).hasSize(10);
        assertThat(visible(ExceptionTypes.OS_ERROR)).hasSize(38);
        assertThat(OS_ORDER).startsWith("__new__").endsWith("__doc__");
        assertThat(OS_ORDER.subList(1, 4)).containsExactly("__str__", "__init__", "__reduce__");
        assertThat(OS_ORDER.subList(4, 9)).containsExactlyElementsOf(FIELDS);

        List<String> added = new ArrayList<>(visible(ExceptionTypes.OS_ERROR));
        added.removeAll(visible(ExceptionTypes.BASE_EXCEPTION));
        assertThat(added).containsExactlyInAnyOrderElementsOf(FIELDS);

        assertThat(kind(entry(ExceptionTypes.OS_ERROR, "__new__")))
                .isEqualTo("builtin_function_or_method");
        assertThat(kind(entry(ExceptionTypes.OS_ERROR, "__reduce__")))
                .isEqualTo("method_descriptor");
        for (String name : MEMBERS) {
            assertThat(kind(entry(ExceptionTypes.OS_ERROR, name))).isEqualTo("member_descriptor");
        }
        assertThat(kind(entry(ExceptionTypes.OS_ERROR, "characters_written")))
                .isEqualTo("getset_descriptor");

        // The two rows this class does not file, and the three of the base it does not read either.
        assertThat(OS_ORDER).doesNotContain("__repr__", "__setstate__", "add_note", "args");
        assertThat(Slots.getAttr(ExceptionTypes.OS_ERROR, "__repr__"))
                .isSameAs(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, "__repr__"));
        assertThat(Slots.getAttr(ExceptionTypes.OS_ERROR, "__str__"))
                .isNotSameAs(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, "__str__"));
        assertThat(Slots.getAttr(ExceptionTypes.OS_ERROR, "__init__"))
                .isNotSameAs(Slots.getAttr(ExceptionTypes.BASE_EXCEPTION, "__init__"));

        // The narrower classes reach every one of these rows by inheritance and file only their sentence.
        assertThat(filed(ExceptionTypes.FILE_NOT_FOUND_ERROR)).containsExactly("__doc__");
        assertThat(filed(ExceptionTypes.BLOCKING_IO_ERROR)).containsExactly("__doc__");
        assertThat(((PyStr) entry(ExceptionTypes.OS_ERROR, "__doc__")).getValue())
                .isEqualTo("Base class for I/O related errors.");
        assertThat(((PyStr) entry(ExceptionTypes.BASE_EXCEPTION, "__doc__")).getValue())
                .isEqualTo("Common base class for all exceptions");
        assertThat(((PyStr) entry(ExceptionTypes.KEY_ERROR, "__doc__")).getValue())
                .isEqualTo("Mapping key not found.");
    }

    /**
     * What each of the six kinds answers about itself, and the sentences the C declarations write.
     *
     * <p>The {@code __new__} row is a {@code builtin_function_or_method}, which is not a descriptor at all: it
     * files {@code __self__} and no {@code __get__}, and beside the type's sentence it is the only row with no
     * {@code __objclass__}. Each of the four descriptor kinds files a {@code __get__} and names the type that
     * declared it, and the sentence is a value rather than a member. The four {@code OSError} members are the
     * only rows of the three tables whose sentences say what they hold rather than what they do, and {@code
     * OSError_getset} writes {@code characters_written} with a null {@code doc}.
     */
    @Test
    void eachKindCarriesWhatItsCDeclarationFilesAboutIt() {
        for (String name : BASE_ORDER) {
            PyObject member = entry(ExceptionTypes.BASE_EXCEPTION, name);
            boolean aValue = name.equals("__doc__");
            boolean notADescriptor = aValue || name.equals("__new__");
            assertThat(member.getAttr("__qualname__") != PyNotImplemented.INSTANCE)
                    .isEqualTo(!aValue);
            assertThat(member.getAttr("__objclass__") != PyNotImplemented.INSTANCE)
                    .isEqualTo(!notADescriptor);
            assertThat(member.getAttr("__get__") != PyNotImplemented.INSTANCE)
                    .isEqualTo(!notADescriptor);
            assertThat(member.getAttr("__self__") != PyNotImplemented.INSTANCE)
                    .isEqualTo(name.equals("__new__"));
        }
        assertThat(entry(ExceptionTypes.BASE_EXCEPTION, "__new__").getAttr("__self__"))
                .isSameAs(ExceptionTypes.BASE_EXCEPTION);
        assertThat(carried(ExceptionTypes.BASE_EXCEPTION, "__new__"))
                .isEqualTo(
                        "Create and return a new object.  See help(type) for accurate signature.");
        assertThat(carried(ExceptionTypes.BASE_EXCEPTION, "__init__"))
                .isEqualTo("Initialize self.  See help(type(self)) for accurate signature.");
        assertThat(BASE_ORDER.stream()
                        .filter(name -> carried(ExceptionTypes.BASE_EXCEPTION, name) != null)
                        .toList())
                .containsExactly(
                        "__new__", "__repr__", "__str__", "__init__", "with_traceback", "add_note",
                        "__doc__");

        assertThat(carried(ExceptionTypes.OS_ERROR, "errno")).isEqualTo("POSIX exception code");
        assertThat(carried(ExceptionTypes.OS_ERROR, "strerror")).isEqualTo("exception strerror");
        assertThat(carried(ExceptionTypes.OS_ERROR, "filename")).isEqualTo("exception filename");
        assertThat(carried(ExceptionTypes.OS_ERROR, "filename2"))
                .isEqualTo("second exception filename");
        assertThat(carried(ExceptionTypes.OS_ERROR, "characters_written")).isNull();
        assertThat(carried(ExceptionTypes.OS_ERROR, "__reduce__")).isNull();
        assertThat(carried(ExceptionTypes.BASE_EXCEPTION, "__suppress_context__")).isNull();
        assertThat(carried(ExceptionTypes.BASE_EXCEPTION, "args")).isNull();

        // A member read through a value is the bound form, not the row: the descriptor binds a receiver.
        PyObject value = make(ExceptionTypes.OS_ERROR, ONE, TEXT);
        assertThat(Slots.getAttr(value, "errno")).isNotSameAs(entry(ExceptionTypes.OS_ERROR, "errno"));
        assertThat(((PyInt) Slots.getAttr(value, "errno")).getValue().intValue()).isEqualTo(1);
        assertThat(Slots.getAttr(entry(ExceptionTypes.OS_ERROR, "errno"), "__objclass__"))
                .isSameAs(ExceptionTypes.OS_ERROR);
        assertThat(entry(ExceptionTypes.OS_ERROR, "errno").repr())
                .isEqualTo("<member 'errno' of 'OSError' objects>");
        assertThat(entry(ExceptionTypes.OS_ERROR, "characters_written").repr())
                .isEqualTo("<attribute 'characters_written' of 'OSError' objects>");
        assertThat(entry(ExceptionTypes.BASE_EXCEPTION, "args").repr())
                .isEqualTo("<attribute 'args' of 'BaseException' objects>");
        assertThat(entry(ExceptionTypes.BASE_EXCEPTION, "__reduce__").repr())
                .isEqualTo("<method '__reduce__' of 'BaseException' objects>");
    }

    /**
     * Which function a slot wrapper runs, and what it says about a receiver of another class.
     *
     * <p>A {@code wrapper_descriptor} calls the slot function of the type that <em>filed</em> it, not the
     * {@code tp_str} the value's own class would run: {@code BaseException.__str__(OSError(1, "a"))} answers
     * over the arguments, {@code "(1, 'a')"}, while {@code str(e)} reads the fields and answers {@code [Errno
     * 1] a}. {@code KeyError} is the one class of the three whose {@code tp_str} keeps {@code BaseException}'s
     * rule for every count but one — a single argument is the missing key, and CPython reports it with {@code
     * repr} — so the base's wrapper on {@code KeyError("a")} answers {@code a} and its own {@code 'a'}. A
     * class Python wrote wins the display, and the base's wrapper still runs the base's rule under it.
     */
    @Test
    void theDisplayAWrapperRunsIsTheOneOfTheTypeThatFiledIt() {
        PyObject osError = make(ExceptionTypes.OS_ERROR, ONE, TEXT);
        assertThat(((PyStr) call(ExceptionTypes.BASE_EXCEPTION, "__str__", osError)).getValue())
                .isEqualTo("(1, 'a')");
        assertThat(((PyBaseException) osError).str()).isEqualTo("[Errno 1] a");
        assertThat(((PyStr) call(ExceptionTypes.BASE_EXCEPTION, "__repr__", osError)).getValue())
                .isEqualTo("PermissionError(1, 'a')");
        assertThat(((PyBaseException) osError).repr()).isEqualTo("PermissionError(1, 'a')");

        PyObject key = make(ExceptionTypes.KEY_ERROR, TEXT);
        assertThat(((PyStr) call(ExceptionTypes.BASE_EXCEPTION, "__str__", key)).getValue())
                .isEqualTo("a");
        assertThat(((PyBaseException) key).str()).isEqualTo("'a'");
        assertThat(((PyStr) call(ExceptionTypes.KEY_ERROR, "__str__", key)).getValue())
                .isEqualTo("'a'");
        assertThat(((PyStr) call(ExceptionTypes.KEY_ERROR, "__str__", make(ExceptionTypes.KEY_ERROR, ONE, TWO)))
                        .getValue())
                .isEqualTo("(1, 2)");

        // `BaseException_str`'s three counts, and `BaseException_repr`'s two halves of one format.
        assertThat(((PyBaseException) make(ExceptionTypes.VALUE_ERROR, ONE, TWO)).str())
                .isEqualTo("(1, 2)");
        assertThat(((PyBaseException) make(ExceptionTypes.VALUE_ERROR)).str()).isEmpty();
        assertThat(((PyBaseException) make(ExceptionTypes.VALUE_ERROR, ONE)).repr())
                .isEqualTo("ValueError(1)");
        assertThat(((PyBaseException) make(ExceptionTypes.VALUE_ERROR, ONE, TWO)).repr())
                .isEqualTo("ValueError(1, 2)");
        assertThat(((PyBaseException) make(ExceptionTypes.VALUE_ERROR)).repr())
                .isEqualTo("ValueError()");

        // The wrappers check their receiver, and each kind complains in its own voice.
        refuses(
                "TypeError: descriptor '__str__' requires a 'KeyError' object but received a 'BaseException'",
                () -> call(ExceptionTypes.KEY_ERROR, "__str__", make(ExceptionTypes.BASE_EXCEPTION)));
        refuses(
                "TypeError: descriptor '__str__' requires a 'OSError' object but received a 'ValueError'",
                () -> call(ExceptionTypes.OS_ERROR, "__str__", make(ExceptionTypes.VALUE_ERROR)));
        refuses(
                "TypeError: descriptor '__str__' requires a 'BaseException' object but received a 'str'",
                () -> call(ExceptionTypes.BASE_EXCEPTION, "__str__", PyStr.of("x")));
        refuses(
                "TypeError: descriptor '__str__' of 'OSError' object needs an argument",
                () -> call(ExceptionTypes.OS_ERROR, "__str__"));
        refuses(
                "TypeError: descriptor '__reduce__' for 'BaseException' objects doesn't apply to a 'str' object",
                () -> call(ExceptionTypes.BASE_EXCEPTION, "__reduce__", PyStr.of("x")));

        // A class under BaseException that writes no slot of its own is still called with arguments.
        PyType bare = classOf("Bare", ExceptionTypes.BASE_EXCEPTION);
        assertThat(make(bare, TEXT).getType()).isSameAs(bare);
        assertThat(((PyBaseException) make(bare, ONE, TWO)).str()).isEqualTo("(1, 2)");
    }

    /**
     * The state a value hands back for pickling, and the one it refuses to be handed.
     *
     * <p>{@code BaseException.__reduce__} is two items while nothing has been written to the value: a read of
     * {@code args} or of {@code vars(e)} does not make the instance dictionary exist, because the lookup runs
     * through {@code _PyObject_GetDictPtr}, which never creates it. A write, or an {@code add_note}, makes it
     * three. {@code OSError} puts the file names back into the second item, and a value given five arguments
     * reports five with {@code None} in the fourth place, because that is the only way {@code OSError(*args)}
     * can be handed a second file name on a platform that stores no {@code winerror}.
     */
    @Test
    void theReductionReportsTheCallThatWouldMakeTheValueAgain() {
        PyObject plain = make(ExceptionTypes.BASE_EXCEPTION, TEXT);
        assertThat(call(ExceptionTypes.BASE_EXCEPTION, "__reduce__", plain).repr())
                .isEqualTo("(<class 'BaseException'>, ('a',))");
        assertThat(run(make(ExceptionTypes.KEY_ERROR, TEXT), "__reduce__").repr())
                .isEqualTo("(<class 'KeyError'>, ('a',))");
        assertThat(((PyBaseException) plain).held().repr()).isEqualTo("{}");
        Slots.setAttr(plain, "z", TWO);
        assertThat(call(ExceptionTypes.BASE_EXCEPTION, "__reduce__", plain).repr())
                .isEqualTo("(<class 'BaseException'>, ('a',), {'z': 2})");

        PyObject named = make(ExceptionTypes.OS_ERROR, UNMAPPED, TEXT, FILE);
        assertThat(call(ExceptionTypes.OS_ERROR, "__reduce__", named).repr())
                .isEqualTo("(<class 'OSError'>, (9999, 'a', 'f'))");
        Slots.setAttr(named, "filename2", OTHER);
        assertThat(call(ExceptionTypes.OS_ERROR, "__reduce__", named).repr())
                .isEqualTo("(<class 'OSError'>, (9999, 'a', 'f', None, 'g'))");
        PyObject emptied = make(ExceptionTypes.OS_ERROR, UNMAPPED, TEXT, FILE);
        Slots.setAttr(emptied, "filename", NONE);
        assertThat(call(ExceptionTypes.OS_ERROR, "__reduce__", emptied).repr())
                .isEqualTo("(<class 'OSError'>, (9999, 'a', None))");
        assertThat(call(ExceptionTypes.OS_ERROR, "__reduce__", make(ExceptionTypes.OS_ERROR, TEXT)).repr())
                .isEqualTo("(<class 'OSError'>, ('a',))");
        assertThat(
                        call(
                                        ExceptionTypes.OS_ERROR,
                                        "__reduce__",
                                        make(ExceptionTypes.OS_ERROR, UNMAPPED, TEXT, FILE, NONE, OTHER))
                                .repr())
                .isEqualTo("(<class 'OSError'>, (9999, 'a', 'f', None, 'g'))");
        // A count is not a file name, so all three arguments stay where the call put them.
        assertThat(run(make(ExceptionTypes.BLOCKING_IO_ERROR, ONE, TEXT, TWO), "__reduce__").repr())
                .isEqualTo("(<class 'BlockingIOError'>, (1, 'a', 2))");

        refuses(
                "TypeError: unbound method BaseException.__reduce__() needs an argument",
                () -> call(ExceptionTypes.BASE_EXCEPTION, "__reduce__"));
        refuses(
                "TypeError: BaseException.__reduce__() takes no keyword arguments",
                () ->
                        Slots.call(
                                entry(ExceptionTypes.BASE_EXCEPTION, "__reduce__"),
                                new PyObject[] {plain, ONE},
                                FLAG));
        refuses(
                "TypeError: BaseException.__reduce__() takes no arguments (1 given)",
                () ->
                        Slots.call(
                                entry(ExceptionTypes.BASE_EXCEPTION, "__reduce__"),
                                new PyObject[] {make(ExceptionTypes.BASE_EXCEPTION, TEXT), ONE},
                                null));

        // `__setstate__` takes the dictionary and nothing else: not None, which leaves the value alone.
        PyObject kept = make(ExceptionTypes.BASE_EXCEPTION, TEXT);
        call(ExceptionTypes.BASE_EXCEPTION, "__setstate__", kept, state(PyStr.interned("k"), TWO));
        assertThat(((PyBaseException) kept).held().repr()).isEqualTo("{'k': 2}");
        call(ExceptionTypes.BASE_EXCEPTION, "__setstate__", kept, NONE);
        assertThat(((PyBaseException) kept).held().repr()).isEqualTo("{'k': 2}");
        refuses(
                "TypeError: state is not a dictionary",
                () -> call(ExceptionTypes.BASE_EXCEPTION, "__setstate__", kept, ONE));
        refuses(
                "TypeError: attribute name must be string, not 'int'",
                () -> call(ExceptionTypes.BASE_EXCEPTION, "__setstate__", kept, state(ONE, TWO)));
        refuses(
                "TypeError: __class__ must be set to a class, not 'int' object",
                () ->
                        call(
                                ExceptionTypes.BASE_EXCEPTION,
                                "__setstate__",
                                kept,
                                state(PyStr.interned("__class__"), ONE)));
        refuses(
                "TypeError: 'int' object is not iterable",
                () ->
                        call(
                                ExceptionTypes.BASE_EXCEPTION,
                                "__setstate__",
                                kept,
                                state(PyStr.interned("args"), ONE)));
        refuses(
                "TypeError: BaseException.__setstate__() takes exactly one argument (0 given)",
                () -> call(ExceptionTypes.BASE_EXCEPTION, "__setstate__", kept));
        refuses(
                "TypeError: BaseException.__setstate__() takes exactly one argument (2 given)",
                () ->
                        Slots.call(
                                entry(ExceptionTypes.BASE_EXCEPTION, "__setstate__"),
                                new PyObject[] {kept, PyDict.empty(), PyDict.empty()},
                                null));
    }

    /**
     * A field that was never filled, a field written as {@code None}, and a field deleted.
     *
     * <p>The last two are one state to a read and two to a display: {@code PyMember_GetOne} answers {@code
     * OR_NONE} for a null pointer, so {@code del e.errno} and a never-filled {@code errno} read alike, while
     * {@code OSError_str} tests the pointer and so says nothing about a deleted file and {@code -> None} about
     * a file written as {@code None}. Every one of the four takes its delete twice over, since writing the
     * null pointer is an answer rather than a refusal — and the getset among the five refuses its read and
     * its second delete alike, because {@code written} is a C word with {@code -1} in it and no {@code
     * OR_NONE} to fall back on.
     */
    @Test
    void aDeletedFieldReadsAsNoneAndADeletedCountRefusesByName() {
        PyObject bare = make(ExceptionTypes.OS_ERROR);
        for (String name : MEMBERS) {
            assertThat(Slots.getAttr(bare, name)).isSameAs(NONE);
        }
        refuses("AttributeError: characters_written",
                () -> Slots.getAttr(bare, "characters_written"));

        PyObject filled = make(ExceptionTypes.OS_ERROR, ONE, TEXT, FILE, NONE, OTHER);
        assertThat(((PyOSError) filled).str()).isEqualTo("[Errno 1] a: 'f' -> 'g'");
        Slots.setAttr(filled, "filename2", NONE);
        assertThat(Slots.getAttr(filled, "filename2")).isSameAs(NONE);
        assertThat(((PyOSError) filled).str()).isEqualTo("[Errno 1] a: 'f' -> None");
        Slots.delAttr(filled, "filename2");
        assertThat(((PyOSError) filled).str()).isEqualTo("[Errno 1] a: 'f'");
        Slots.delAttr(filled, "filename2");
        assertThat(Slots.getAttr(filled, "filename2")).isSameAs(NONE);
        Slots.delAttr(filled, "errno");
        assertThat(((PyOSError) filled).str()).isEqualTo("[Errno None] a: 'f'");
        assertThat(Slots.getAttr(filled, "errno")).isSameAs(NONE);

        // The count is the one field of the five with no answer for being absent, in either direction.
        PyObject counted = make(ExceptionTypes.BLOCKING_IO_ERROR, ONE, TEXT, TWO);
        assertThat(Slots.getAttr(counted, "characters_written").repr()).isEqualTo("2");
        Slots.delAttr(counted, "characters_written");
        refuses("AttributeError: characters_written",
                () -> Slots.getAttr(counted, "characters_written"));
        refuses(
                "TypeError: 'str' object cannot be interpreted as an integer",
                () -> Slots.setAttr(counted, "characters_written", TEXT));
        Slots.setAttr(counted, "characters_written", TWO);
        assertThat(Slots.getAttr(counted, "characters_written").repr()).isEqualTo("2");

        refuses(
                "TypeError: descriptor 'errno' for 'OSError' objects doesn't apply to a 'BaseException' object",
                () -> getOf(ExceptionTypes.OS_ERROR, "errno", make(ExceptionTypes.BASE_EXCEPTION)));
        assertThat(getOf(ExceptionTypes.OS_ERROR, "errno", bare)).isSameAs(NONE);
        assertThat(getOf(ExceptionTypes.OS_ERROR, "strerror", bare)).isSameAs(NONE);
    }

    /**
     * The one decision {@code OSError_new} and {@code OSError_init} share, over four classes.
     *
     * <p>{@code oserror_use_init} asks whether the class wrote an {@code __init__} and no {@code __new__}, and
     * one answer runs through both halves: a class that wrote one is left empty by the creator — no arguments,
     * no error number, no promotion to a narrower class — and is filled only if that {@code __init__} calls
     * the base. A class that wrote {@code __new__} too parses in the creator it reached, and a class that
     * wrote neither is made whole by {@code OSError_new} and then {@code OSError_init} returns without
     * touching it. The keyword complaint names which half answered: the method for a class that wrote an
     * {@code __init__}, the class for one that did not.
     */
    @Test
    void oneDecisionDecidesWhetherTheCreatorOrTheInitialiserParses() {
        PyType skipping =
                classOf(
                        "Skip",
                        ExceptionTypes.OS_ERROR,
                        "__init__",
                        new PyNativeFunction("__init__", (args, kw) -> NONE));
        PyType filing = classOf("Filed", ExceptionTypes.OS_ERROR, "errno", PyInt.of(42));

        // A class that defers is made empty, is not promoted, and is filled only when it asks.
        PyObject skipped = make(skipping, ONE, TEXT, FILE);
        assertThat(((PyBaseException) skipped).args().repr()).isEqualTo("()");
        assertThat(Slots.getAttr(skipped, "errno")).isSameAs(NONE);
        assertThat(((PyBaseException) skipped).str()).isEmpty();
        assertThat(make(skipping, TWO, TEXT).getType()).isSameAs(skipping);
        call(ExceptionTypes.OS_ERROR, "__init__", skipped, TWO, PyStr.of("two"));
        assertThat(((PyBaseException) skipped).args().repr()).isEqualTo("(2, 'two')");
        assertThat(Slots.getAttr(skipped, "errno").repr()).isEqualTo("2");

        // A class that writes neither is whole from the creator, and its init then does nothing at all.
        PyObject filed = make(filing, ONE, TEXT, FILE);
        assertThat(((PyBaseException) filed).args().repr()).isEqualTo("(1, 'a')");
        assertThat(Slots.getAttr(filed, "strerror").str()).isEqualTo("a");
        call(ExceptionTypes.OS_ERROR, "__init__", filed, TWO, PyStr.of("two"));
        assertThat(((PyBaseException) filed).args().repr()).isEqualTo("(1, 'a')");
        assertThat(Slots.getAttr(filed, "strerror").str()).isEqualTo("a");
        assertThat(make(filing, TWO, TEXT).getType()).isSameAs(filing);

        // A class that writes `__new__` as well parses in the creator it reached.
        PyType both =
                classOf(
                        "Both",
                        ExceptionTypes.OS_ERROR,
                        "__new__",
                        new PyNativeFunction(
                                "__new__",
                                (args, kw) ->
                                        Slots.call(
                                                entry(ExceptionTypes.OS_ERROR, "__new__"),
                                                args,
                                                null)),
                        "__init__",
                        new PyNativeFunction("__init__", (args, kw) -> NONE));
        PyObject reached = make(both, ONE, TEXT, FILE);
        assertThat(((PyBaseException) reached).args().repr()).isEqualTo("(1, 'a')");
        assertThat(((PyOSError) reached).str()).isEqualTo("[Errno 1] a: 'f'");

        // The creator reached by hand defers too, and OSError itself is promoted by it.
        PyObject handmade =
                call(ExceptionTypes.OS_ERROR, "__new__", skipping, ONE, TEXT, FILE);
        assertThat(((PyBaseException) handmade).args().repr()).isEqualTo("()");
        assertThat(
                        call(ExceptionTypes.OS_ERROR, "__new__", ExceptionTypes.OS_ERROR, ONE, TEXT, FILE)
                                .repr())
                .isEqualTo("PermissionError(1, 'a')");
        assertThat(call(ExceptionTypes.OS_ERROR, "__new__", filing, ONE, TEXT).repr())
                .contains("(1, 'a')");

        refuses(
                "TypeError: Filed() takes no keyword arguments",
                () -> calledWithKeyword(filing, ONE, TEXT));
        refuses(
                "TypeError: OSError() takes no keyword arguments",
                () -> calledWithKeyword(ExceptionTypes.OS_ERROR, ONE, TEXT));
    }

    /**
     * A class under {@code OSError} that files one of the five names for itself.
     *
     * <p>{@code _PyType_Lookup} walks the order once and finds the class's own value before {@code
     * OSError}'s member descriptor, so {@code class Filed(OSError): errno = 42} reads {@code 42} on the class
     * and on a value of it while {@code str()} still reads the field and answers {@code [Errno 1] a}. A write
     * goes to the instance dictionary, where it shadows the class row; a delete takes the dictionary name back
     * and the read returns to {@code 42}; and a delete of a name the dictionary never held refuses, because a
     * plain value is not a descriptor and has no deleting half to ask.
     */
    @Test
    void aClassRowUnderOSErrorOverrulesTheMemberForTheReadAlone() {
        PyType filing = classOf("Filed", ExceptionTypes.OS_ERROR, "errno", PyInt.of(42));
        assertThat(Slots.getAttr(filing, "errno").repr()).isEqualTo("42");
        PyObject value = make(filing, ONE, TEXT, FILE);
        assertThat(Slots.getAttr(value, "errno")).isSameAs(Slots.getAttr(filing, "errno"));
        assertThat(((PyOSError) value).str()).isEqualTo("[Errno 1] a: 'f'");

        Slots.setAttr(value, "errno", PyInt.of(7));
        assertThat(((PyBaseException) value).held().repr()).isEqualTo("{'errno': 7}");
        assertThat(Slots.getAttr(value, "errno").repr()).isEqualTo("7");
        Slots.delAttr(value, "errno");
        assertThat(Slots.getAttr(value, "errno").repr()).isEqualTo("42");
        refuses(
                "AttributeError: 'Filed' object has no attribute 'errno'",
                () -> Slots.delAttr(make(filing, ONE, TEXT), "errno"));
        assertThat(getOf(ExceptionTypes.OS_ERROR, "errno", value).repr()).isEqualTo("1");

        // A class under BaseException that files a getset's name wins the read and shares the write.
        PyType shadowing =
                classOf("Shadow", ExceptionTypes.BASE_EXCEPTION, "args", PyStr.of("mine"));
        PyObject shadow = make(shadowing, TEXT);
        assertThat(Slots.getAttr(shadow, "args")).isSameAs(Slots.getAttr(shadowing, "args"));
        assertThat(getOf(ExceptionTypes.BASE_EXCEPTION, "args", shadow).repr()).isEqualTo("('a',)");
        Slots.setAttr(shadow, "args", PyTuple.of(ONE, TWO));
        assertThat(((PyBaseException) shadow).held().repr()).isEqualTo("{'args': (1, 2)}");
        assertThat(Slots.getAttr(shadow, "args").repr()).isEqualTo("(1, 2)");
        // The dictionary holds the pair and the field holds the one the call gave: the display reads the
        // second, so the write changes what a read of the name answers and nothing else.
        assertThat(getOf(ExceptionTypes.BASE_EXCEPTION, "args", shadow).repr()).isEqualTo("('a',)");
        assertThat(((PyBaseException) shadow).str()).isEqualTo("a");
        assertThat(((PyBaseException) shadow).args().repr()).isEqualTo("('a',)");
    }

    /**
     * What the six data descriptors of {@code BaseException} will not let a value do.
     *
     * <p>Six of the fifteen rows are data descriptors — the one {@code T_BOOL} member and the five getsets —
     * so they are asked before the instance dictionary and before any row a class under {@code BaseException}
     * files. Their setters check what arrives, that a cause derives from {@code BaseException} and a traceback
     * is one; their deleting halves refuse in three different voices, one per kind of storage behind the name,
     * and none of the six can be deleted at all.
     */
    @Test
    void aDataDescriptorOfTheBaseRefusesTheDeletesAValueAsksFor() {
        PyObject value = make(ExceptionTypes.BASE_EXCEPTION, TEXT);
        assertThat(Slots.getAttr(value, "args").repr()).isEqualTo("('a',)");
        assertThat(((PyBaseException) value).held().repr()).isEqualTo("{}");
        assertThat(Slots.getAttr(value, "__suppress_context__")).isSameAs(PyBool.FALSE);
        assertThat(Slots.getAttr(value, "__traceback__")).isSameAs(NONE);
        assertThat(Slots.getAttr(value, "__cause__")).isSameAs(NONE);
        assertThat(Slots.getAttr(value, "__context__")).isSameAs(NONE);

        Slots.setAttr(value, "args", PyTuple.of(ONE, TWO));
        assertThat(Slots.getAttr(value, "args").repr()).isEqualTo("(1, 2)");
        assertThat(((PyBaseException) value).str()).isEqualTo("(1, 2)");
        Slots.setAttr(value, "__suppress_context__", PyBool.TRUE);
        assertThat(Slots.getAttr(value, "__suppress_context__")).isSameAs(PyBool.TRUE);
        Slots.setAttr(value, "__cause__", make(ExceptionTypes.VALUE_ERROR));
        assertThat(Slots.getAttr(value, "__cause__").repr()).isEqualTo("ValueError()");

        refuses("TypeError: args may not be deleted", () -> Slots.delAttr(value, "args"));
        refuses("TypeError: cannot delete __dict__", () -> Slots.delAttr(value, "__dict__"));
        refuses(
                "TypeError: can't delete numeric/char attribute",
                () -> Slots.delAttr(value, "__suppress_context__"));
        refuses(
                "TypeError: exception cause must be None or derive from BaseException",
                () -> Slots.setAttr(value, "__cause__", ONE));
        refuses(
                "TypeError: __traceback__ must be a traceback or None",
                () -> Slots.setAttr(value, "__traceback__", ONE));

        // The note is the one name a value grows without a field behind it, and it shows in the reduction.
        PyObject noted = make(ExceptionTypes.BASE_EXCEPTION, TEXT);
        assertThat(run(noted, "add_note", PyStr.of("n")).repr()).isEqualTo("None");
        assertThat(Slots.getAttr(noted, "__notes__").repr()).isEqualTo("['n']");
        assertThat(call(ExceptionTypes.BASE_EXCEPTION, "__reduce__", noted).repr())
                .isEqualTo("(<class 'BaseException'>, ('a',), {'__notes__': ['n']})");
        refuses(
                "TypeError: add_note() argument must be str, not int",
                () -> run(noted, "add_note", ONE));
    }
}
