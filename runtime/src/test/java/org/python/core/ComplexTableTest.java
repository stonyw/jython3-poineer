package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The thirty-one names {@code complex} files, measured against CPython 3.14.8.
 *
 * <p>Corpus case 105 reads the whole table from Python, one line per answer. What is pinned here is the half a
 * script cannot point at. First the table as the complete ordered map it is: the constructor clinic writes,
 * then the twenty-two slots {@code complex_as_number} files in struct order, then the five of {@code
 * complex_methods} (Objects/complexobject.c:1331), the two of {@code complex_members} and the type's own
 * sentence — and the number struct leaves {@code nb_inplace_add} and the two coercions empty, so the eight
 * arithmetic rows {@code float} files are absent here rather than present and declining. A reordered or
 * duplicated member row is only caught by comparing the dictionary, the {@link Members} table and an ordinary
 * read of the same name.
 *
 * <p>Then the two-stage constructor, which is the one Python-visible question this runtime answered wrongly
 * before the table was filed: {@code actual_complex_new} hands back the object it was given for a lone exact
 * complex, so {@code complex(z) is z} is an identity no {@code repr} shows. Then the three Java bodies the
 * twenty-two wrappers run through — {@link Numeric#complexSlot}, which declines a side that is not a number,
 * {@link Numeric#complexCompare}, which declines the four orderings before it looks at the other side, and
 * {@link Numeric#complexPower}, which keeps a modulus for itself and says what the type says about it. Then
 * the two fields, which are {@code Py_T_DOUBLE}s marked {@code Py_READONLY} and so are {@code
 * member_descriptor}s rather than the {@code getset_descriptor}s the same two names are on {@code float} and
 * {@code int}: one read, one refusal of a write, a different kind of row. And last the five sentences and the
 * argument tables {@code signature_from_flags} invents for them, since {@code __getnewargs__} is the only row
 * of the five whose clinic text has nothing written under it.
 */
class ComplexTableTest {

    /** The thirty-one names {@code complex} files, in the order its dictionary answers them. */
    private static final List<String> ORDER =
            List.of(
                    "__new__", "__repr__", "__hash__", "__lt__", "__le__", "__eq__", "__ne__", "__gt__",
                    "__ge__", "__add__", "__radd__", "__sub__", "__rsub__", "__mul__", "__rmul__", "__pow__",
                    "__rpow__", "__neg__", "__pos__", "__abs__", "__bool__", "__truediv__", "__rtruediv__",
                    "from_number", "conjugate", "__complex__", "__getnewargs__", "__format__", "real", "imag",
                    "__doc__");

    /** The twenty-two rows {@code complex_as_number}, {@code tp_repr} and {@code tp_hash} file as wrappers. */
    private static final List<String> WRAPPERS = ORDER.subList(1, 23);

    /** The five rows {@code complex_methods} files, in the order it writes them. */
    private static final List<String> METHODS =
            List.of("from_number", "conjugate", "__complex__", "__getnewargs__", "__format__");

    /** The four rows of those five that {@code complex_methods} writes without {@code METH_CLASS}. */
    private static final List<String> VALUE_ROWS =
            List.of("conjugate", "__complex__", "__getnewargs__", "__format__");

    /** The two rows {@code complex_members} files, each one {@code Py_READONLY}. */
    private static final List<String> FIELDS = List.of("real", "imag");

    /** The eight rows {@code float} files as arithmetic and this type leaves out of its number struct. */
    private static final List<String> NOT_FILED =
            List.of(
                    "__mod__", "__rmod__", "__divmod__", "__rdivmod__", "__floordiv__", "__rfloordiv__",
                    "__int__", "__float__");

    private static final PyObject VALUE = PyComplex.of(1.0, 2.0);
    private static final PyObject TWO = PyComplex.of(2.0, 0.0);
    private static final PyObject ZERO = PyComplex.of(0.0, 0.0);
    private static final PyObject ONE = PyInt.ONE;
    private static final PyObject TWO_INT = PyInt.of(2);
    private static final PyObject THREE = PyInt.of(3);
    private static final PyObject TEXT = PyStr.of("1+2j");

    /** The entry {@code complex} files under {@code name}, out of its dictionary rather than by reading it. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.COMPLEX).get(PyStr.interned(name));
    }

    /** {@code builtins.name} as a program reads it. */
    private static PyObject builtin(String name) {
        return PyRuntime.builtins().get(PyStr.interned(name));
    }

    /** The names a kind's own dictionary keeps, in the order the dictionary kept them. */
    private static List<String> filed(PyType owner) {
        PyIterator keys = (PyIterator) ((PyMappingProxy) Slots.getAttr(owner, "__dict__")).keysIterator();
        List<String> names = new ArrayList<>();
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** {@code complex(*args)}, the way the interpreter calls a type. */
    private static PyObject complexOf(PyObject... args) {
        return PyType.COMPLEX.call(args, null);
    }

    /** {@code complex(**kwargs)}, which is the only way the two named parts get reached. */
    private static PyObject complexWith(PyTuple kwNames, PyObject... args) {
        return PyType.COMPLEX.call(args, kwNames);
    }

    /** {@code complex.__dict__[name](*args)}: a filed row, called without an attribute read. */
    private static PyObject call(String name, PyObject... args) {
        return Slots.call(entry(name), args, null);
    }

    /** {@code complex.__dict__[field].__get__(*args)}: the descriptor protocol, called as Python calls it. */
    private static PyObject getRow(String field, PyObject... args) {
        return Slots.call(Slots.getAttr(entry(field), "__get__"), args, null);
    }

    /** A filed row called with keywords beside the arguments, which is what sends it to the parser. */
    private static PyObject calledWith(String name, PyTuple kwNames, PyObject... args) {
        return Slots.call(entry(name), args, kwNames);
    }

    private static void refuses(String message, Throwing action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The thirty-one rows in the order the three C lists wrote them.
     *
     * <p>The dictionary is filled in filing order, so the twenty-two wrappers land between the constructor and
     * the five methods, and the two fields land last but one. {@code len(vars(complex))} is thirty-one while
     * {@code dir(complex)} is forty-four: the thirteen names only the second has are {@code object}'s, which a
     * complex borrows without writing a line for. The four rows read off the class are the rows themselves —
     * dictionary, table and ordinary answer one object — while {@code from_number} is not, because a {@code
     * classmethod_descriptor} binds the type on the way out and so answers a fresh record every read.
     */
    @Test
    void thirtyOneRowsFileTheOrderTheCListsWrote() {
        assertThat(filed(PyType.COMPLEX)).isEqualTo(ORDER);
        assertThat(ORDER).hasSize(31);
        assertThat(TypeDict.declared(PyType.COMPLEX).keys()).hasSize(31);
        assertThat(Arrays.asList(Members.tableOf(PyType.COMPLEX).names()))
                .containsExactlyInAnyOrderElementsOf(METHODS);

        assertThat(ORDER).startsWith("__new__").endsWith("__doc__");
        assertThat(WRAPPERS).hasSize(22).startsWith("__repr__").endsWith("__rtruediv__");
        assertThat(ORDER.indexOf("__hash__") - ORDER.indexOf("__new__")).isEqualTo(2);
        assertThat(ORDER.indexOf("__ge__") - ORDER.indexOf("__lt__")).isEqualTo(5);
        assertThat(ORDER.indexOf("__add__") - ORDER.indexOf("__ge__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__rpow__") - ORDER.indexOf("__pow__")).isEqualTo(1);
        assertThat(ORDER.indexOf("__bool__") - ORDER.indexOf("__abs__")).isEqualTo(1);
        assertThat(ORDER.indexOf("from_number") - ORDER.indexOf("__rtruediv__")).isEqualTo(1);
        assertThat(ORDER.subList(23, 28)).isEqualTo(METHODS);
        assertThat(ORDER.indexOf("imag") - ORDER.indexOf("real")).isEqualTo(1);

        assertThat(entry("__new__").getType().name()).isEqualTo("builtin_function_or_method");
        for (String name : WRAPPERS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("wrapper_descriptor");
        }
        assertThat(entry("from_number").getType().name()).isEqualTo("classmethod_descriptor");
        for (String name : VALUE_ROWS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("method_descriptor");
            assertThat(entry(name)).isSameAs(Slots.getAttr(PyType.COMPLEX, name));
            assertThat(entry(name)).isSameAs(Members.tableOf(PyType.COMPLEX).describe(name));
        }
        for (String name : FIELDS) {
            assertThat(entry(name).getType().name()).as(name).isEqualTo("member_descriptor");
            assertThat(entry(name)).isSameAs(Slots.getAttr(PyType.COMPLEX, name));
        }
        assertThat(entry("__doc__").getType()).isSameAs(PyType.STR);

        // The classmethod binds, so the read is never the row; the wrapper row is, because it is a slot.
        assertThat(Members.tableOf(PyType.COMPLEX).describe("from_number")).isSameAs(entry("from_number"));
        assertThat(Slots.getAttr(PyType.COMPLEX, "from_number")).isNotSameAs(entry("from_number"));
        assertThat(Slots.getAttr(PyType.COMPLEX, "__add__")).isSameAs(entry("__add__"));

        // `complex_as_number` stops at `nb_or`: these eight rows are absent, not filed and declining.
        for (String name : NOT_FILED) {
            assertThat(TypeDict.declared(PyType.COMPLEX).get(PyStr.interned(name))).as(name).isNull();
            refuses(
                    "AttributeError: type object 'complex' has no attribute '" + name + "'",
                    () -> Slots.getAttr(PyType.COMPLEX, name));
        }
        assertThat(TypeDict.declared(PyType.FLOAT).get(PyStr.interned("__mod__"))).isNotNull();
        assertThat(ORDER).doesNotContain("__str__", "__init__", "__sizeof__", "__getattribute__");
    }

    /**
     * The five sentences clinic writes, the two the member table carries and the tables the flags invent.
     *
     * <p>{@code complex_methods} is written whole through the {@code *_METHODDEF} macros, so all five rows
     * carry an argument table and {@code __getnewargs__} is the silence of a table with nothing written under
     * it — its {@code ml_doc} is {@code "__getnewargs__($self, /)\n--\n\n"}, and the half that survives {@code
     * cut_out_signature} is nothing. The two fields are the only rows of the table with no argument table at
     * all: a {@code member_descriptor} keeps a {@code __doc__}, a {@code __qualname__} and its {@code
     * __objclass__} and answers no {@code __text_signature__}. The twenty-two wrappers speak {@code
     * wrapper_descriptor}'s three sentences, which are the same three every other type answers.
     */
    @Test
    void fiveSentencesComeOutOfClinicWhileTheTwoFieldsComeOutOfTheMemberTable() {
        assertThat(Slots.getAttr(entry("__new__"), "__doc__"))
                .isEqualTo(
                        PyStr.of("Create and return a new object.  See help(type) for accurate signature."));
        assertThat(Slots.getAttr(entry("from_number"), "__doc__"))
                .isEqualTo(PyStr.of("Convert number to a complex floating-point number."));
        assertThat(Slots.getAttr(entry("conjugate"), "__doc__"))
                .isEqualTo(
                        PyStr.of(
                                "Return the complex conjugate of its argument. (3-4j).conjugate() =="
                                        + " 3+4j."));
        assertThat(Slots.getAttr(entry("__complex__"), "__doc__"))
                .isEqualTo(PyStr.of("Convert this value to exact type complex."));
        assertThat(Slots.getAttr(entry("__format__"), "__doc__"))
                .isEqualTo(PyStr.of("Convert to a string according to format_spec."));
        assertThat(Slots.getAttr(entry("__getnewargs__"), "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(entry("real"), "__doc__"))
                .isEqualTo(PyStr.of("the real part of a complex number"));
        assertThat(Slots.getAttr(entry("imag"), "__doc__"))
                .isEqualTo(PyStr.of("the imaginary part of a complex number"));
        assertThat(Slots.getAttr(entry("__repr__"), "__doc__")).isEqualTo(PyStr.of("Return repr(self)."));
        assertThat(Slots.getAttr(entry("__add__"), "__doc__")).isEqualTo(PyStr.of("Return self+value."));
        assertThat(Slots.getAttr(entry("__eq__"), "__doc__")).isEqualTo(PyStr.of("Return self==value."));
        assertThat(Slots.getAttr(entry("__pow__"), "__doc__"))
                .isEqualTo(PyStr.of("Return pow(self, value, mod)."));

        assertThat(Slots.getAttr(entry("__new__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($type, *args, **kwargs)"));
        assertThat(Slots.getAttr(entry("from_number"), "__text_signature__"))
                .isEqualTo(PyStr.of("($type, number, /)"));
        assertThat(Slots.getAttr(entry("__format__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, format_spec, /)"));
        assertThat(Slots.getAttr(entry("__pow__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, value, mod=None, /)"));
        assertThat(Slots.getAttr(entry("__rpow__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, value, mod=None, /)"));
        assertThat(Slots.getAttr(entry("__sub__"), "__text_signature__"))
                .isEqualTo(PyStr.of("($self, value, /)"));
        assertThat(Slots.getAttr(entry("__bool__"), "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));
        for (String name : FIELDS) {
            refuses(
                    "AttributeError: 'member_descriptor' object has no attribute '__text_signature__'",
                    () -> Slots.getAttr(entry(name), "__text_signature__"));
        }

        for (String name : ORDER) {
            if (name.equals("__doc__")) {
                continue;
            }
            assertThat(Slots.getAttr(entry(name), "__qualname__"))
                    .as(name)
                    .isEqualTo(PyStr.of("complex." + name));
        }
        for (String name : FIELDS) {
            assertThat(Slots.getAttr(entry(name), "__objclass__")).isSameAs(PyType.COMPLEX);
            assertThat(Slots.getAttr(entry(name), "__name__")).isEqualTo(PyStr.of(name));
            assertThat(entry(name).repr()).isEqualTo("<member '" + name + "' of 'complex' objects>");
        }
    }

    /**
     * The type's sentence is {@code complex_new__doc__} with its argument table cut out of it.
     *
     * <p>{@code tp_doc} is the constructor row's own text, so the six lines a program reads off {@code
     * complex.__doc__} begin with the shapes the constructor takes and carry no {@code --} rule: the table is
     * taken out by {@code cut_out_signature}, which leaves the sentence whole and means the row is a plain
     * string in the dictionary rather than a {@code getset_descriptor}. It is filed once and read twice, so
     * the two answers are equal without being one object, and a value reads it through the type as the type
     * does.
     */
    @Test
    void theTypeSentenceIsTheNewRowTextWithItsTableCutOutOfIt() {
        PyObject throughClass = Slots.getAttr(PyType.COMPLEX, "__doc__");
        PyObject throughValue = Slots.getAttr(VALUE, "__doc__");

        assertThat(throughClass).isEqualTo(entry("__doc__"));
        assertThat(throughClass).isNotSameAs(entry("__doc__"));
        assertThat(throughValue).isEqualTo(throughClass);
        assertThat(throughClass.getType()).isSameAs(PyType.STR);

        List<String> lines = Arrays.asList(((PyStr) throughClass).getValue().split("\n"));
        assertThat(lines).hasSize(6);
        assertThat(lines.get(0)).isEqualTo("Create a complex number from a string or numbers.");
        assertThat(lines.get(1)).isEmpty();
        assertThat(lines.get(5)).isEqualTo("with the specified real and imaginary components.");
        assertThat(((PyStr) throughClass).getValue()).doesNotContain("--");
        assertThat(((PyStr) entry("__doc__")).getValue()).startsWith("Create a complex number");
    }

    /**
     * The two fields are offsets, so they read a number and refuse every way of writing one.
     *
     * <p>{@code complex_members} files both as {@code Py_T_DOUBLE} with {@code Py_READONLY}, which makes them
     * {@code member_descriptor}s rather than the {@code getset_descriptor}s the same two names are on {@code
     * float} and {@code int} — one read, one refusal, a different kind of row. The read answers a fresh {@code
     * float}, so neither field hands back the receiver: {@code x.real is x} is a rule of {@code float} alone,
     * and a complex's {@code real} is equal to the int {@code 1} without being it. All four refusals come from
     * {@code PyMember_SetOne} before it looks at the value, so {@code del}, {@code __set__} and {@code
     * __delete__} say the same two words an assignment does; and the receiver check is the descriptor's, which
     * quotes {@code tp_name} and the field rather than the slot.
     */
    @Test
    void theTwoFieldsAreMembersAndRefuseEveryWriteBeforeLookingAtTheValue() {
        assertThat(Slots.getAttr(VALUE, "real")).isEqualTo(PyFloat.of(1.0));
        assertThat(Slots.getAttr(VALUE, "imag")).isEqualTo(PyFloat.of(2.0));
        assertThat(Slots.getAttr(VALUE, "real").getType()).isSameAs(PyType.FLOAT);
        assertThat(Slots.getAttr(PyType.FLOAT, "real").getType().name()).isEqualTo("getset_descriptor");
        assertThat(Slots.getAttr(PyType.INT, "imag").getType().name()).isEqualTo("getset_descriptor");

        // A read answers the number and never the receiver, and the two parts of a fresh zero are two zeros.
        assertThat(Slots.getAttr(VALUE, "real")).isNotSameAs(VALUE);
        assertThat(Slots.getAttr(VALUE, "imag")).isNotSameAs(VALUE);
        assertThat(Slots.getAttr(Slots.getAttr(VALUE, "real"), "real"))
                .isEqualTo(Slots.getAttr(VALUE, "real"));
        assertThat(Slots.getAttr(complexOf(), "real")).isEqualTo(PyFloat.of(0.0));
        assertThat(Slots.getAttr(complexOf(PyStr.of("1j")), "imag")).isEqualTo(PyFloat.of(1.0));
        assertThat(Slots.compare(CompareOp.EQ, Slots.getAttr(VALUE, "real"), ONE)).isSameAs(PyBool.TRUE);

        String readonly = "AttributeError: readonly attribute";
        refuses(readonly, () -> Slots.setAttr(VALUE, "real", PyFloat.of(3.0)));
        refuses(readonly, () -> Slots.delAttr(VALUE, "imag"));
        refuses(
                readonly,
                () ->
                        Slots.call(
                                Slots.getAttr(entry("real"), "__set__"),
                                new PyObject[] {VALUE, PyFloat.of(3.0)},
                                null));
        refuses(
                readonly,
                () -> Slots.call(Slots.getAttr(entry("imag"), "__delete__"), new PyObject[] {VALUE}, null));
        assertThat(Slots.getAttr(VALUE, "real")).isEqualTo(PyFloat.of(1.0));

        refuses(
                "TypeError: descriptor 'real' for 'complex' objects doesn't apply to a 'int' object",
                () -> getRow("real", ONE));

        // With no receiver the field is the descriptor itself, whatever class it was asked of — and there is
        // no instance dictionary for a data descriptor to outrank.
        assertThat(getRow("real", PyNone.INSTANCE, PyType.INT)).isSameAs(entry("real"));
        assertThat(getRow("imag", PyNone.INSTANCE, PyType.COMPLEX)).isSameAs(entry("imag"));
        assertThat(getRow("real", VALUE)).isEqualTo(PyFloat.of(1.0));
        assertThat(((Descriptors.Protocol) entry("real")).writes()).isTrue();
        refuses(
                "AttributeError: 'complex' object has no attribute '__dict__'",
                () -> Slots.getAttr(VALUE, "__dict__"));
        refuses(
                "TypeError: vars() argument must have __dict__ attribute",
                () -> Slots.call(builtin("vars"), new PyObject[] {VALUE}, null));
    }

    /**
     * The constructor is two stages, and the first one answers the value it was handed.
     *
     * <p>{@code actual_complex_new} settles a lone exact complex with {@code Py_NewRef} before the clinic half
     * is reached at all, so {@code complex(z) is z} — and the same is true of the {@code __new__} row read
     * directly, since {@code tp_new} is that one function. Every other answer is a fresh object, including the
     * {@code (1+2j)} that comes back equal to a complex already written that way. The gates are the
     * constructor's own words, because {@code __new__} is the row clinic writes and not a slot wrapper: a
     * foreign class names itself, a non-type says what arrived, and no argument at all says not enough.
     */
    @Test
    void aLoneComplexIsTheValueItWasHandedWhileEveryOtherShapeIsFresh() {
        PyObject fresh = PyComplex.of(1.0, 2.0);
        assertThat(complexOf(VALUE)).isSameAs(VALUE);
        assertThat(complexOf(fresh)).isSameAs(fresh);
        assertThat(fresh).isNotSameAs(VALUE);
        assertThat(call("__new__", PyType.COMPLEX, VALUE)).isSameAs(VALUE);
        assertThat(complexOf(ONE, TWO_INT)).isNotSameAs(VALUE);

        assertThat(complexOf(ONE)).isEqualTo(PyComplex.of(1.0, 0.0));
        assertThat(complexOf(PyFloat.of(1.0), PyInt.of(2))).isEqualTo(VALUE);
        assertThat(complexOf(PyInt.of(2))).isEqualTo(PyComplex.of(2.0, 0.0));
        assertThat(complexOf(PyBool.TRUE, PyBool.FALSE)).isEqualTo(PyComplex.of(1.0, 0.0));
        assertThat(complexOf()).isEqualTo(ZERO);
        assertThat(complexOf().repr()).isEqualTo("0j");

        // The string half, which is a parse only when no imaginary part arrived beside it.
        assertThat(complexOf(TEXT)).isEqualTo(VALUE);
        assertThat(complexOf(PyStr.of("(1+2j)"))).isEqualTo(VALUE);
        assertThat(complexOf(PyStr.of("1+2j "))).isEqualTo(VALUE);
        assertThat(complexOf(PyStr.of("1j"))).isEqualTo(PyComplex.of(0.0, 1.0));
        refuses(
                "TypeError: complex() argument 'real' must be a real number, not str",
                () -> complexOf(TEXT, TEXT));
        refuses(
                "ValueError: complex() arg is a malformed string", () -> complexOf(PyStr.of("1 - 2j")));

        assertThat(complexWith(PyTuple.of(PyStr.interned("imag")), THREE)).isEqualTo(PyComplex.of(0.0, 3.0));
        assertThat(complexWith(PyTuple.of(PyStr.interned("real")), ONE)).isEqualTo(PyComplex.of(1.0, 0.0));
        assertThat(complexWith(PyTuple.of(PyStr.interned("real"), PyStr.interned("imag")), ONE, TWO_INT))
                .isEqualTo(VALUE);
        refuses(
                "TypeError: complex() takes at most 2 arguments (3 given)",
                () -> complexOf(ONE, TWO, ONE));
        refuses(
                "TypeError: complex() argument must be a string or a number, not NoneType",
                () -> complexOf(PyNone.INSTANCE));
        refuses(
                "TypeError: complex() argument must be a string or a number, not bytes",
                () -> complexOf(PyBytes.of(new byte[] {'1'})));
        refuses(
                "TypeError: complex() got an unexpected keyword argument 'junk'",
                () -> complexWith(PyTuple.of(PyStr.interned("junk")), ONE, TWO));

        refuses(
                "TypeError: complex.__new__(int): int is not a subtype of complex",
                () -> call("__new__", PyType.INT));
        refuses(
                "TypeError: complex.__new__(str): str is not a subtype of complex",
                () -> call("__new__", PyType.STR));
        refuses(
                "TypeError: complex.__new__(X): X is not a type object (int)",
                () -> call("__new__", ONE));
        refuses("TypeError: complex.__new__(): not enough arguments", () -> call("__new__"));
    }

    /**
     * The five methods, called over the value and over the type.
     *
     * <p>{@code complex_complex} answers the receiver itself, so {@code z.__complex__()} is {@code z} — the one
     * identity among the five. {@code complex_getnewargs} answers the two doubles as two floats, which is what
     * {@code copy} and {@code pickle} read back into the constructor. {@code from_number} is the one {@code
     * METH_CLASS} row: it is reached through the type, takes exactly one argument and no keyword, and refuses
     * anything that is not a real number in the words its own body writes rather than the constructor's.
     */
    @Test
    void theFiveMethodsRunOverTheValueAndTheOneOfThemOverTheType() {
        assertThat(call("conjugate", VALUE)).isEqualTo(PyComplex.of(1.0, -2.0));
        assertThat(call("__complex__", VALUE)).isSameAs(VALUE);
        PyObject arguments = call("__getnewargs__", VALUE);
        assertThat(arguments.getType()).isSameAs(PyType.TUPLE);
        assertThat(((PyTuple) arguments).at(0)).isEqualTo(PyFloat.of(1.0));
        assertThat(((PyTuple) arguments).at(1)).isEqualTo(PyFloat.of(2.0));
        assertThat(((PyTuple) arguments).at(0).getType()).isSameAs(PyType.FLOAT);
        assertThat(call("__format__", VALUE, PyStr.of(""))).isEqualTo(PyStr.of("(1+2j)"));
        assertThat(call("__format__", ZERO, PyStr.of(""))).isEqualTo(PyStr.of("0j"));

        refuses(
                "TypeError: __format__() argument must be str, not int",
                () -> call("__format__", VALUE, ONE));

        assertThat(call("from_number", PyType.COMPLEX, ONE)).isEqualTo(PyComplex.of(1.0, 0.0));
        assertThat(call("from_number", PyType.COMPLEX, PyFloat.of(2.5)))
                .isEqualTo(PyComplex.of(2.5, 0.0));
        assertThat(call("from_number", PyType.COMPLEX, VALUE)).isSameAs(VALUE);
        assertThat(call("from_number", PyType.COMPLEX, PyBool.TRUE)).isEqualTo(PyComplex.of(1.0, 0.0));
        assertThat(call("from_number", PyType.COMPLEX, PyInt.of(BigInteger.valueOf(2).pow(70))))
                .isEqualTo(PyComplex.of(1.1805916207174113e+21, 0.0));

        for (PyObject bad : List.of(TEXT, PyNone.INSTANCE, PyBytes.of(new byte[] {'1'}))) {
            refuses(
                    "TypeError: must be real number, not " + bad.getType().name(),
                    () -> call("from_number", PyType.COMPLEX, bad));
        }
        refuses(
                "TypeError: complex.from_number() takes exactly one argument (0 given)",
                () -> call("from_number", PyType.COMPLEX));
        refuses(
                "TypeError: complex.from_number() takes exactly one argument (2 given)",
                () -> call("from_number", PyType.COMPLEX, ONE, TWO));
        refuses(
                "TypeError: descriptor 'from_number' of 'complex' object needs an argument",
                () ->
                        calledWith(
                                "from_number",
                                PyTuple.of(PyStr.interned("number")),
                                PyType.COMPLEX));
    }

    /**
     * The two reads a value answers, and the sixteen names behind them.
     *
     * <p>{@code method_get} makes a {@code PyCFunctionObject} for a row of {@code complex_methods}, so {@code
     * z.conjugate} reads as {@code builtin_function_or_method} with the value under {@code __self__} — slice
     * 54's rule, reached from a table this slice files — while a slot row binds a {@code method-wrapper} over
     * the same value. {@code complex.from_number} is the third shape: a class method binds the type it was read
     * through, and the silent row answers {@code None} for its sentence and still carries the table its flags
     * describe.
     */
    @Test
    void aRowOfThisTableBindsTheValueWhileASlotRowBindsAWrapper() {
        PyObject conjugate = Slots.getAttr(VALUE, "conjugate");
        assertThat(conjugate.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(conjugate, "__self__")).isSameAs(VALUE);
        assertThat(Slots.getAttr(conjugate, "__qualname__")).isEqualTo(PyStr.of("complex.conjugate"));
        assertThat(Slots.getAttr(conjugate, "__name__")).isEqualTo(PyStr.of("conjugate"));
        assertThat(Slots.getAttr(conjugate, "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(conjugate, "__doc__")).isEqualTo(Slots.getAttr(entry("conjugate"), "__doc__"));
        assertThat(Slots.call(conjugate, new PyObject[0], null)).isEqualTo(PyComplex.of(1.0, -2.0));

        PyObject silent = Slots.getAttr(VALUE, "__getnewargs__");
        assertThat(Slots.getAttr(silent, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(silent, "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));

        PyObject added = Slots.getAttr(VALUE, "__add__");
        assertThat(added.getType().name()).isEqualTo("method-wrapper");
        assertThat(Slots.getAttr(added, "__self__")).isSameAs(VALUE);
        assertThat(Slots.getAttr(added, "__doc__")).isEqualTo(PyStr.of("Return self+value."));

        PyObject fromNumber = Slots.getAttr(PyType.COMPLEX, "from_number");
        assertThat(fromNumber.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(fromNumber, "__self__")).isSameAs(PyType.COMPLEX);
        assertThat(Slots.getAttr(fromNumber, "__qualname__")).isEqualTo(PyStr.of("complex.from_number"));
        assertThat(Slots.call(fromNumber, new PyObject[] {ONE}, null)).isEqualTo(PyComplex.of(1.0, 0.0));
    }

    /**
     * The twenty-two wrappers, and the three gates they run before their bodies.
     *
     * <p>{@code wrapperdescr_call} checks the receiver against the declaring type, refuses a keyword in its own
     * words, then counts what arrived — three sentences, all of them {@code wrapper_descriptor}'s and none of
     * them the body's, and the count it prints is what the slot needs rather than what arrived with the
     * receiver taken out of it. Then the bodies answer as CPython's do: {@code CHECK_BINOP} declines a side
     * that is not a number, {@code complex_richcompare} declines the four orderings even between two numbers,
     * and {@code complex_pow} keeps a modulus for itself with the {@code ValueError} the reference writes.
     * {@code complex_pos} is the one wrapper that answers the receiver rather than a number of its own.
     */
    @Test
    void theTwentyTwoWrappersCheckTheReceiverThenCountAndFinallyDecline() {
        assertThat(call("__add__", VALUE, ONE)).isEqualTo(PyComplex.of(2.0, 2.0));
        assertThat(call("__radd__", VALUE, ONE)).isEqualTo(PyComplex.of(2.0, 2.0));
        assertThat(call("__sub__", VALUE, ONE)).isEqualTo(PyComplex.of(0.0, 2.0));
        assertThat(call("__mul__", VALUE, TWO)).isEqualTo(PyComplex.of(2.0, 4.0));
        assertThat(call("__truediv__", VALUE, TWO)).isEqualTo(PyComplex.of(0.5, 1.0));
        assertThat(call("__rtruediv__", VALUE, TWO)).isEqualTo(PyComplex.of(0.4, -0.8));
        assertThat(call("__neg__", VALUE)).isEqualTo(PyComplex.of(-1.0, -2.0));
        assertThat(call("__pos__", VALUE)).isSameAs(VALUE);
        assertThat(Slots.unary(UnaryOp.POSITIVE, VALUE)).isSameAs(VALUE);
        assertThat(Slots.unary(UnaryOp.NEGATIVE, VALUE)).isNotSameAs(VALUE);
        assertThat(call("__abs__", VALUE)).isEqualTo(PyFloat.of(Math.hypot(1.0, 2.0)));
        assertThat(call("__bool__", VALUE)).isSameAs(PyBool.TRUE);
        assertThat(call("__bool__", ZERO)).isSameAs(PyBool.FALSE);
        assertThat(call("__repr__", VALUE)).isEqualTo(PyStr.of("(1+2j)"));
        assertThat(call("__hash__", VALUE)).isEqualTo(PyInt.of(2000007));

        // `complex_richcompare` is EQ and NE only, and the four orderings are filed for the decline.
        for (CompareOp cmp : List.of(CompareOp.LT, CompareOp.LE, CompareOp.GT, CompareOp.GE)) {
            assertThat(call(cmp.dunder(), VALUE, ONE)).as(cmp.symbol()).isSameAs(PyNotImplemented.INSTANCE);
            assertThat(call(cmp.dunder(), VALUE, TWO))
                    .as(cmp.symbol())
                    .isSameAs(PyNotImplemented.INSTANCE);
            refuses(
                    "TypeError: '"
                            + cmp.symbol()
                            + "' not supported between instances of 'complex' and 'int'",
                    () -> Slots.compare(cmp, VALUE, ONE));
        }
        assertThat(call("__eq__", VALUE, ONE)).isSameAs(PyBool.FALSE);
        assertThat(call("__eq__", VALUE, VALUE)).isSameAs(PyBool.TRUE);
        assertThat(call("__ne__", VALUE, TWO)).isSameAs(PyBool.TRUE);
        assertThat(call("__eq__", VALUE, TEXT)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.compare(CompareOp.EQ, VALUE, TEXT)).isSameAs(PyBool.FALSE);

        for (BinaryOp op :
                List.of(
                        BinaryOp.ADD,
                        BinaryOp.SUB,
                        BinaryOp.MUL,
                        BinaryOp.TRUEDIV,
                        BinaryOp.FLOORDIV,
                        BinaryOp.MOD,
                        BinaryOp.MATMUL,
                        BinaryOp.AND)) {
            assertThat(Numeric.complexSlot(op, VALUE, TEXT)).as(op.name()).isSameAs(PyNotImplemented.INSTANCE);
            assertThat(Numeric.complexSlot(op, TEXT, VALUE))
                    .as(op.name())
                    .isSameAs(PyNotImplemented.INSTANCE);
        }
        refuses("ValueError: complex modulo", () -> call("__pow__", VALUE, TWO, ONE));
        refuses("ValueError: complex modulo", () -> Numeric.complexPower(VALUE, TWO, ONE));

        // The three gates of `wrapperdescr_call`, in the order the wrapper runs them.
        refuses(
                "TypeError: descriptor '__neg__' requires a 'complex' object but received a 'int'",
                () -> call("__neg__", ONE));
        refuses(
                "TypeError: descriptor '__repr__' requires a 'complex' object but received a 'bool'",
                () -> call("__repr__", PyBool.TRUE));
        refuses(
                "TypeError: descriptor '__hash__' requires a 'complex' object but received a 'str'",
                () -> call("__hash__", TEXT));
        refuses(
                "TypeError: wrapper __add__() takes no keyword arguments",
                () -> calledWith("__add__", PyTuple.of(PyStr.interned("other")), VALUE, ONE));
        refuses("TypeError: expected 1 argument, got 0", () -> call("__add__", VALUE));
        refuses("TypeError: expected 1 argument, got 2", () -> call("__add__", VALUE, ONE, TWO));
        refuses("TypeError: expected 0 arguments, got 1", () -> call("__neg__", VALUE, ONE));
        refuses(
                "TypeError: descriptor '__neg__' of 'complex' object needs an argument",
                () -> call("__neg__"));
    }

    /**
     * The arithmetic the twenty-two rows run, at the Java end.
     *
     * <p>{@code complex_op} is the one body behind the eight add, subtract, multiply and divide rows and their
     * reflected twins, and it answers the pair of doubles CPython answers; {@code complex_pow} reaches the
     * polar form even for an integral exponent, since this runtime writes no {@code _Py_c_powi} fast path. Two
     * numbers are compared by the imaginary part first: {@code (1+0j)} is the complex that equals the int
     * {@code 1}, and the same real part written with a nonzero imaginary half is not — which is the rule the
     * {@code __eq__} row and the {@code ==} operator have to answer alike.
     */
    @Test
    void theNumberBodiesAnswerTheSameTwoDoublesTheCStructDoes() {
        assertThat(Slots.binary(BinaryOp.ADD, ONE, VALUE)).isEqualTo(PyComplex.of(2.0, 2.0));
        assertThat(Slots.binary(BinaryOp.TRUEDIV, TWO, VALUE)).isEqualTo(PyComplex.of(0.4, -0.8));
        assertThat(Slots.binary(BinaryOp.SUB, ONE, VALUE)).isEqualTo(PyComplex.of(0.0, -2.0));
        refuses(
                "TypeError: unsupported operand type(s) for %: 'complex' and 'complex'",
                () -> Slots.binary(BinaryOp.MOD, VALUE, TWO));

        assertThat(Numeric.complexSlot(BinaryOp.MUL, VALUE, VALUE)).isEqualTo(PyComplex.of(-3.0, 4.0));
        assertThat(Numeric.complexSlot(BinaryOp.TRUEDIV, VALUE, TWO)).isEqualTo(PyComplex.of(0.5, 1.0));
        assertThat(Numeric.complexSlot(BinaryOp.FLOORDIV, VALUE, TWO)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Numeric.complexSlot(BinaryOp.MOD, VALUE, TWO)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Numeric.complexSlot(BinaryOp.POW, VALUE, PyComplex.of(1.0, 1.0)))
                .isEqualTo(PyComplex.of(-0.24720004426291722, 0.6964504870825432));
        assertThat(Numeric.complexPower(VALUE, PyComplex.of(1.0, 1.0), PyNone.INSTANCE))
                .isEqualTo(PyComplex.of(-0.24720004426291722, 0.6964504870825432));

        assertThat(Numeric.complexCompare(CompareOp.EQ, PyComplex.of(1.0, 0.0), ONE)).isSameAs(PyBool.TRUE);
        assertThat(Numeric.complexCompare(CompareOp.EQ, PyComplex.of(1.0, 1.0), ONE))
                .isSameAs(PyBool.FALSE);
        assertThat(Numeric.complexCompare(CompareOp.NE, PyComplex.of(1.0, 0.0), ONE))
                .isSameAs(PyBool.FALSE);
        assertThat(Numeric.complexCompare(CompareOp.EQ, VALUE, TEXT))
                .isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Numeric.complexCompare(CompareOp.LT, VALUE, TWO)).isSameAs(PyNotImplemented.INSTANCE);
    }
}
