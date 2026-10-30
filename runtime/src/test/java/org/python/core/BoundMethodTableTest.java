package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The record a built-in type's method table hands out when one of its values reads it, measured against
 * CPython 3.14.8.
 *
 * <p>The corpus script reads the whole of {@code "text".join} from Python. What is pinned here is the
 * machinery those reads run through. The first piece is what the read makes: {@code method_get}
 * (Objects/descrobject.c:137) answers with {@code PyCFunction_NewEx(ml, obj, NULL)}, so the value is the same
 * {@code PyCFunctionObject} {@code len} is and reads the sixteen-name dictionary of slice 39 — not {@code
 * method}, which {@code PyMethod_Type} reserves for the {@code __get__} of a function Python wrote, and not
 * {@code method-wrapper}, which is what the slot rows of the same family hand out. The {@code NULL} of that
 * call is half of what a program then sees: {@code __module__} reads {@code None} on a method and {@code
 * 'builtins'} on {@code len}, while {@code __name__} and {@code __qualname__} come out of the row the way the
 * unbound descriptor answers them.
 *
 * <p>The second piece is that five of the sixteen names are reads of the <em>row</em> and one is a read of the
 * <em>receiver</em>, and the two are qualified differently: {@code meth_get__qualname__} asks {@code
 * Py_TYPE(m_self)} rather than the class that declares the method, so the bool that inherits {@code
 * int.bit_length} reports {@code bool.bit_length}. That same question of the descriptor's own reads answers
 * nothing: a {@code method_descriptor} keeps the class under {@code __objclass__} and has no {@code __self__}
 * at all, which is what the record of the bound read is. The third piece is the one writable field among the
 * sixteen — {@code meth_members} files {@code __module__} over {@code m_module} while {@code meth_getsets}
 * files five reads with no setter, so a write of {@code __name__} is refused by the getset and a {@code del}
 * of it by the same setter handed nothing; a write of one of the nine slot rows is refused for the reason
 * {@code object.__setattr__} always gives, and a name out of the sixteen is refused because the record has no
 * dictionary to keep it in.
 *
 * <p>The fourth is {@code meth_richcompare}: the receiver by pointer, then the C function pointer of the row.
 * Two reads of one method of one value are therefore equal without ever being the same object, a method of a
 * second value unequal even when the two values are equal strings, and the four orderings and every
 * comparison against something that is not a function of this kind decline the question — which leaves the
 * operator falling back to identity. {@code meth_hash} asks {@code PyObject_GenericHash} of the receiver
 * (Python/pyhash.c:143), which is the address and not the value. The fifth is that a call of the record runs
 * the row's body with the receiver in front of it, and the receiver the body sees is the one the declaring
 * type can read, since a bool is an {@code int} to CPython and a separate class here — which is why the
 * {@code __self__} of {@code True.bit_length} stays the bool.
 */
class BoundMethodTableTest {

    /** The sixteen names {@code methodobject.c} files, in the order the record keeps them. */
    private static final List<String> ORDER =
            List.of(
                    "__repr__",
                    "__hash__",
                    "__call__",
                    "__lt__",
                    "__le__",
                    "__eq__",
                    "__ne__",
                    "__gt__",
                    "__ge__",
                    "__reduce__",
                    "__module__",
                    "__doc__",
                    "__name__",
                    "__qualname__",
                    "__self__",
                    "__text_signature__");

    /** The five names {@code meth_getsets} files with no setter, so that a write reaches nothing. */
    private static final List<String> READ_ONLY =
            List.of("__name__", "__qualname__", "__self__", "__doc__", "__text_signature__");

    /** The one string the reads of this table are taken through, so that the receiver has an address. */
    private static final PyObject TEXT = PyStr.of("text");

    /** {@code "text".join}: the read every row of this table is run over. */
    private static PyObject bound() {
        return Slots.getAttr(TEXT, "join");
    }

    /** A second read of the same row on the same value, which is a fresh record of its own. */
    private static PyObject again() {
        return Slots.getAttr(TEXT, "join");
    }

    /** {@code str.join} as the type keeps it — the row the two reads above are both read out of. */
    private static PyObject row() {
        return TypeDict.declared(PyType.STR).get(PyStr.interned("join"));
    }

    /** The entry {@code builtin_function_or_method} files under {@code name}. */
    private static PyObject entry(String name) {
        return TypeDict.declared(PyType.BUILTIN_FUNCTION).get(PyStr.interned(name));
    }

    /** {@code kind.__dict__[name](...args)}: a row of the record's own dictionary, called directly. */
    private static PyObject filed(String name, PyObject... args) {
        return Slots.call(entry(name), args, null);
    }

    /** The names a kind's own dictionary keeps, in the order it keeps them. */
    private static List<String> declared(PyType owner) {
        List<String> names = new ArrayList<>();
        for (PyObject key : TypeDict.declared(owner).getItems().keySet()) {
            names.add(((PyStr) key).getValue());
        }
        return names;
    }

    /** {@code builtins.name} as a program reads it. */
    private static PyObject builtin(String name) {
        return PyRuntime.builtins().get(PyStr.interned(name));
    }

    private static void refuses(String message, Throwing call) {
        assertThatThrownBy(() -> call.run())
                .isInstanceOfSatisfying(
                        PyRaise.class, raised -> assertThat(raised.getMessage()).isEqualTo(message));
    }

    private interface Throwing {
        void run() throws Throwable;
    }

    /**
     * The kind a value read is, and the sixteen names it therefore reads.
     *
     * <p>{@code method_get} makes a {@code PyCFunctionObject}, so the dictionary behind {@code "text".join} is
     * the one {@code len} reads — nine slot rows, one {@code PyMethodDef}, one {@code PyMemberDef} and five
     * {@code PyGetSetDef}s — and the record has no store of its own, which {@code vars()} and a read of {@code
     * __dict__} both say. The four names a <em>descriptor</em> or a Python function answers are the four a
     * read of this table has no record for.
     */
    @Test
    void aValueReadIsTheSameRecordABuiltinFunctionIs() {
        PyObject join = bound();

        assertThat(join.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(declared(PyType.BUILTIN_FUNCTION)).isEqualTo(ORDER);
        assertThat(ORDER).hasSize(16);
        assertThat(builtin("len").getType()).isSameAs(join.getType());

        // Not `method`, which is what a function Python wrote binds into, and not `method-wrapper`.
        assertThat(Slots.getAttr(PyInt.of(3), "__add__").getType())
                .isSameAs(PyType.METHOD_WRAPPER);
        assertThat(PyType.METHOD).isNotSameAs(PyType.BUILTIN_FUNCTION);

        for (String name : List.of("__objclass__", "__func__", "__get__", "__dict__")) {
            refuses(
                    "AttributeError: 'builtin_function_or_method' object has no attribute '" + name + "'",
                    () -> Slots.getAttr(join, name));
        }
        refuses(
                "TypeError: vars() argument must have __dict__ attribute",
                () -> Slots.call(builtin("vars"), new PyObject[] {join}, null));
    }

    /**
     * The five reads out of the row, and the receiver that qualifies one of them.
     *
     * <p>{@code meth_get__qualname__} asks the class of whatever the read bound to, so a method a bool
     * inherits from {@code int} calls itself {@code bool.bit_length} while {@code int.bit_length} itself is
     * qualified by the type the read ran on. The sentence and the argument table are cut out of the row's own
     * {@code ml_doc} by the same two functions the descriptor reads, so both halves answer one text. The
     * descriptor keeps its class under {@code __objclass__} and answers no {@code __self__} at all.
     */
    @Test
    void fiveReadsComeOutOfTheRowAndTheReceiverQualifiesThem() {
        PyObject join = bound();

        assertThat(Slots.getAttr(join, "__name__")).isEqualTo(PyStr.of("join"));
        assertThat(Slots.getAttr(join, "__qualname__")).isEqualTo(PyStr.of("str.join"));
        assertThat(Slots.getAttr(join, "__self__")).isSameAs(TEXT);
        assertThat(Slots.getAttr(join, "__doc__")).isEqualTo(Slots.getAttr(row(), "__doc__"));
        assertThat(Slots.getAttr(join, "__text_signature__")).isEqualTo(PyStr.of("($self, iterable, /)"));
        assertThat(Slots.getAttr(join, "__text_signature__"))
                .isEqualTo(Slots.getAttr(row(), "__text_signature__"));

        PyObject bitLength = Slots.getAttr(PyBool.TRUE, "bit_length");
        assertThat(bitLength.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(bitLength, "__name__")).isEqualTo(PyStr.of("bit_length"));
        assertThat(Slots.getAttr(bitLength, "__qualname__")).isEqualTo(PyStr.of("bool.bit_length"));
        assertThat(Slots.getAttr(bitLength, "__self__")).isSameAs(PyBool.TRUE);

        PyObject half = PyFloat.of(1.5);
        PyObject onFloat = Slots.getAttr(half, "is_integer");
        assertThat(onFloat.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(onFloat, "__qualname__")).isEqualTo(PyStr.of("float.is_integer"));
        assertThat(Slots.getAttr(onFloat, "__self__")).isSameAs(half);

        // The row it was read out of is a descriptor: it names its own class and keeps no receiver.
        assertThat(row().getType().name()).isEqualTo("method_descriptor");
        assertThat(Slots.getAttr(row(), "__objclass__")).isSameAs(PyType.STR);
        assertThat(Slots.getAttr(row(), "__qualname__")).isEqualTo(PyStr.of("str.join"));
        refuses(
                "AttributeError: 'method_descriptor' object has no attribute '__self__'",
                () -> Slots.getAttr(row(), "__self__"));
    }

    /**
     * The one field a read starts with empty, and the four routes a write takes.
     *
     * <p>{@code method_get} hands {@code PyCFunction_NewEx} a null module, so {@code __module__} reads {@code
     * None} on a method and {@code 'builtins'} on {@code len}, and a program can fill the field, empty it with
     * {@code del} — twice, since emptying an empty field is not a failure — and read {@code None} afterwards.
     * The write goes to one record: a second read of the same row starts empty again, and the row itself has
     * no such name to read. The five getsets answer nothing to a write, and the same to a {@code del}, because
     * {@code object} hands the delete to the setter with nothing as the value; so do the nine slot rows, for
     * the reason every read-only descriptor gets; and a name out of the sixteen is refused for the dictionary
     * the record does not have, on the way in and on the way out.
     */
    @Test
    void oneFieldStartsEmptyAndTakesEveryWriteWhileFiveReadsRefuseThem() {
        PyObject join = bound();

        assertThat(Slots.getAttr(join, "__module__")).isSameAs(PyNone.INSTANCE);
        Slots.setAttr(join, "__module__", PyStr.of("written"));
        assertThat(Slots.getAttr(join, "__module__")).isEqualTo(PyStr.of("written"));
        Slots.delAttr(join, "__module__");
        assertThat(Slots.getAttr(join, "__module__")).isSameAs(PyNone.INSTANCE);
        Slots.delAttr(join, "__module__");

        assertThat(again()).isNotSameAs(join);
        assertThat(Slots.getAttr(again(), "__module__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(builtin("len"), "__module__")).isEqualTo(PyStr.of("builtins"));
        refuses(
                "AttributeError: 'method_descriptor' object has no attribute '__module__'",
                () -> Slots.getAttr(row(), "__module__"));

        for (String name : READ_ONLY) {
            String writable =
                    "AttributeError: attribute '" + name + "' of 'builtin_function_or_method' objects"
                            + " is not writable";
            refuses(writable, () -> Slots.setAttr(join, name, PyInt.of(5)));
            refuses(writable, () -> Slots.delAttr(join, name));
        }
        for (String name : List.of("__repr__", "__call__", "__hash__", "__eq__")) {
            String readonly =
                    "AttributeError: 'builtin_function_or_method' object attribute '" + name
                            + "' is read-only";
            refuses(readonly, () -> Slots.setAttr(join, name, PyInt.of(5)));
        }
        String nowhere =
                "AttributeError: 'builtin_function_or_method' object has no attribute 'junk'"
                        + " and no __dict__ for setting new attributes";
        refuses(nowhere, () -> Slots.setAttr(join, "junk", PyInt.of(5)));
        refuses(nowhere, () -> Slots.delAttr(join, "junk"));
    }

    /**
     * {@code meth_richcompare}: the receiver by pointer and the row's function, and no ranking at all.
     *
     * <p>Every read makes a fresh record, so two of them are equal without being one object; a method read
     * through a second value is unequal, and so is one read through a different string that happens to carry
     * the same characters, because the receiver is checked by address. Anything that is not a function of this
     * kind is declined, which leaves the operator to fall back on identity, and the four orderings rank
     * nothing. {@code meth_hash} asks the address of the receiver, so two reads of one method hash alike.
     */
    @Test
    void equalityIsTheReceiverAndTheRowAndTheFourOrderingsRankNeither() {
        PyObject join = bound();
        PyObject second = again();

        assertThat(join).isNotSameAs(second);
        assertThat(Slots.compare(CompareOp.EQ, join, second)).isSameAs(PyBool.TRUE);
        assertThat(Slots.compare(CompareOp.NE, join, second)).isSameAs(PyBool.FALSE);
        assertThat(join.hash()).isEqualTo(second.hash());
        assertThat(filed("__eq__", join, second)).isSameAs(PyBool.TRUE);
        assertThat(filed("__ne__", join, second)).isSameAs(PyBool.FALSE);

        PyObject elsewhere = Slots.getAttr(PyStr.of("text"), "join");
        assertThat(elsewhere).isNotSameAs(join);
        assertThat(Slots.compare(CompareOp.EQ, join, elsewhere)).isSameAs(PyBool.FALSE);

        PyObject number = PyInt.of(5);
        assertThat(filed("__eq__", join, number)).isSameAs(PyNotImplemented.INSTANCE);
        assertThat(Slots.compare(CompareOp.EQ, join, number)).isSameAs(PyBool.FALSE);
        assertThat(Slots.compare(CompareOp.NE, join, number)).isSameAs(PyBool.TRUE);

        for (CompareOp cmp : List.of(CompareOp.LT, CompareOp.LE, CompareOp.GT, CompareOp.GE)) {
            assertThat(filed(cmp.dunder(), join, second)).as(cmp.symbol()).isSameAs(PyNotImplemented.INSTANCE);
            assertThat(filed(cmp.dunder(), join, number)).as(cmp.symbol()).isSameAs(PyNotImplemented.INSTANCE);
            refuses(
                    "TypeError: '" + cmp.symbol() + "' not supported between instances of"
                            + " 'builtin_function_or_method' and 'builtin_function_or_method'",
                    () -> Slots.compare(cmp, join, second));
        }
    }

    /**
     * {@code meth_reduce}: the receiver and the row's name for {@code getattr} to find the method again.
     *
     * <p>Only a function whose {@code m_self} is nothing or a module pickles as the bare name, which is what
     * {@code len} answers; a method bound to a value names the value, a method bound over a class names the
     * class, and a {@code method_descriptor} read out of a type's dictionary answers the same pair as the
     * record it binds into, because the reduce is a read of the record and not of the way the record was
     * reached.
     */
    @Test
    void theReduceRowNamesTheReceiverWhileAnUnboundBuiltinNamesOnlyItself() {
        PyObject getattr = builtin("getattr");
        PyObject join = bound();
        PyTuple reduced = (PyTuple) Slots.call(Slots.getAttr(join, "__reduce__"), new PyObject[0], null);

        assertThat(reduced.at(0)).isSameAs(getattr);
        assertThat(reduced.at(1)).isEqualTo(PyTuple.of(TEXT, PyStr.of("join")));
        PyObject found = Slots.call(reduced.at(0), ((PyTuple) reduced.at(1)).getArray(), null);
        assertThat(found).isNotSameAs(join);
        assertThat(Slots.compare(CompareOp.EQ, found, join)).isSameAs(PyBool.TRUE);
        assertThat(filed("__reduce__", join)).isEqualTo(reduced);

        assertThat(Slots.call(Slots.getAttr(builtin("len"), "__reduce__"), new PyObject[0], null))
                .isEqualTo(PyStr.of("len"));
        PyObject mro = Slots.getAttr(PyType.INT, "mro");
        assertThat(mro.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.call(Slots.getAttr(mro, "__reduce__"), new PyObject[0], null))
                .isEqualTo(PyTuple.of(getattr, PyTuple.of(PyType.INT, PyStr.of("mro"))));
        PyObject count = Slots.getAttr(PyTuple.of(PyInt.of(1)), "count");
        assertThat(Slots.call(Slots.getAttr(count, "__reduce__"), new PyObject[0], null))
                .isEqualTo(PyTuple.of(getattr, PyTuple.of(PyTuple.of(PyInt.of(1)), PyStr.of("count"))));
    }

    /**
     * Which read binds what, and the two that bind nothing.
     *
     * <p>A row of a value class's table binds the value; a row registered {@code METH_CLASS} binds the class
     * the read ran on whatever it was handed, whether the read ran on the class or on one of its values, and
     * runs over it; a {@code METH_STATIC} row hands back the one function the dictionary holds, which is why
     * the same object answers through the type and through a value of it. A slot binds to a
     * {@code method-wrapper}, and the descriptor's own {@code __get__} makes the same record the attribute
     * read makes.
     */
    @Test
    void aTableRowBindsTheValueWhileAClassMethodBindsTheClassItWasReadThrough() {
        assertThat(bound().getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(PyInt.of(3), "__add__").getType()).isSameAs(PyType.METHOD_WRAPPER);

        PyObject throughValue = Slots.getAttr(PyDict.empty(), "fromkeys");
        PyObject throughClass = Slots.getAttr(PyType.DICT, "fromkeys");
        for (PyObject fromKeys : List.of(throughValue, throughClass)) {
            assertThat(fromKeys.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
            assertThat(Slots.getAttr(fromKeys, "__self__")).isSameAs(PyType.DICT);
            assertThat(Slots.getAttr(fromKeys, "__qualname__")).isEqualTo(PyStr.of("dict.fromkeys"));
            refuses(
                    "TypeError: 'int' object is not iterable",
                    () -> Slots.call(fromKeys, new PyObject[] {PyInt.of(5)}, null));
        }

        // A static method is the one function, read through the type or through any value of it.
        PyObject maketrans = Slots.getAttr(PyType.BYTES, "maketrans");
        assertThat(maketrans).isSameAs(Slots.getAttr(PyBytes.of(new byte[] {'a'}), "maketrans"));
        assertThat(Slots.getAttr(maketrans, "__self__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(maketrans, "__qualname__")).isEqualTo(PyStr.of("bytes.maketrans"));

        // The descriptor's own `__get__` and the attribute read make the same record over the same receiver.
        PyObject throughGet = Descriptors.get(row(), TEXT, PyType.STR);
        assertThat(throughGet.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(throughGet).isNotSameAs(bound());
        assertThat(Slots.getAttr(throughGet, "__self__")).isSameAs(TEXT);
        assertThat(Slots.getAttr(throughGet, "__qualname__")).isEqualTo(PyStr.of("str.join"));
        assertThat(Slots.compare(CompareOp.EQ, throughGet, bound())).isSameAs(PyBool.TRUE);
    }

    /**
     * The call runs the body over the receiver, and refuses in the row's own sentence.
     *
     * <p>The count and the keyword rules are the table body's, so the bound read, the {@code __call__} row of
     * the record and the value's own attribute all say {@code str.join() takes …} — the row read out of the
     * type is the one that says it needs an argument, and that row applied to a foreign value says whose
     * method it is before it counts anything. A {@code getset_descriptor} read out of the dictionary is not
     * callable at all, and the other rows refuse a value of the wrong kind before running.
     */
    @Test
    void theCallRunsTheBodyAndRefusesInTheRowOwnSentence() {
        PyObject join = bound();
        PyObject pair = PyTuple.of(PyStr.of("a"), PyStr.of("b"));

        assertThat(Slots.call(join, new PyObject[] {pair}, null)).isEqualTo(PyStr.of("atextb"));
        assertThat(Slots.call(join, new PyObject[] {PyTuple.of(PyStr.of("a"))}, PyTuple.EMPTY))
                .isEqualTo(PyStr.of("a"));
        refuses(
                "TypeError: str.join() takes exactly one argument (0 given)",
                () -> Slots.call(join, new PyObject[0], null));
        refuses(
                "TypeError: str.join() takes exactly one argument (2 given)",
                () -> Slots.call(join, new PyObject[] {pair, pair}, null));
        refuses(
                "TypeError: str.join() takes no keyword arguments",
                () -> Slots.call(join, new PyObject[] {pair, PyStr.of("a")}, PyTuple.of(PyStr.interned("x"))));
        refuses(
                "TypeError: str.join() takes exactly one argument (0 given)",
                () -> filed("__call__", join));

        refuses(
                "TypeError: unbound method str.join() needs an argument",
                () -> Slots.call(row(), new PyObject[0], null));
        refuses(
                "TypeError: descriptor 'join' for 'str' objects doesn't apply to a 'int' object",
                () -> Slots.call(row(), new PyObject[] {PyInt.of(5)}, null));
        refuses(
                "TypeError: descriptor '__repr__' requires a 'builtin_function_or_method' object"
                        + " but received a 'int'",
                () -> filed("__repr__", PyInt.of(5)));
        refuses(
                "TypeError: 'getset_descriptor' object is not callable",
                () -> filed("__name__", PyInt.of(5)));

        // The rows run over the record: the read answers for itself, in the words it was written with.
        assertThat(filed("__repr__", join)).isEqualTo(PyStr.of(join.repr()));
        assertThat(filed("__hash__", join)).isEqualTo(PyInt.of(join.hash()));
    }

    /**
     * A row registered with a flag and no text carries that flag into the record it binds into.
     *
     * <p>{@code meth_get__text_signature__} calls {@code _PyType_GetTextSignatureFromInternalDoc} with {@code
     * ml_flags}, so {@code b"ab".__getnewargs__} answers {@code ($self, /)} and {@code None} for its sentence
     * exactly as the filed row does, and {@code (5).conjugate} answers the flag over the sentence it does
     * carry. {@code __self__} is the value, and it is the value the body runs over. The kind files no {@code
     * tp_new} of its own, so the record cannot be made by hand either.
     */
    @Test
    void aRowWithNoTextCarriesItsFlagIntoTheRecordAsWell() {
        PyObject octets = PyBytes.of(new byte[] {'a', 'b'});
        PyObject argument = Slots.getAttr(octets, "__getnewargs__");
        PyObject asRow = TypeDict.declared(PyType.BYTES).get(PyStr.interned("__getnewargs__"));

        assertThat(argument.getType()).isSameAs(PyType.BUILTIN_FUNCTION);
        assertThat(Slots.getAttr(argument, "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(argument, "__doc__")).isSameAs(PyNone.INSTANCE);
        assertThat(Slots.getAttr(argument, "__qualname__")).isEqualTo(PyStr.of("bytes.__getnewargs__"));
        assertThat(Slots.getAttr(argument, "__text_signature__"))
                .isEqualTo(Slots.getAttr(asRow, "__text_signature__"));
        assertThat(Slots.getAttr(asRow, "__doc__")).isSameAs(PyNone.INSTANCE);

        PyTuple reduced = (PyTuple) Slots.call(argument, new PyObject[0], null);
        assertThat(reduced.size()).isEqualTo(1);
        assertThat(reduced.at(0)).isSameAs(octets);

        PyObject conjugate = Slots.getAttr(PyInt.of(5), "conjugate");
        assertThat(Slots.getAttr(conjugate, "__text_signature__")).isEqualTo(PyStr.of("($self, /)"));
        assertThat(Slots.getAttr(conjugate, "__doc__"))
                .isEqualTo(PyStr.of("Returns self, the complex conjugate of any int."));
        assertThat(Slots.getAttr(conjugate, "__qualname__")).isEqualTo(PyStr.of("int.conjugate"));
        assertThat(Slots.call(conjugate, new PyObject[0], null)).isEqualTo(PyInt.of(5));

        refuses(
                "TypeError: cannot create 'builtin_function_or_method' instances",
                () -> PyType.BUILTIN_FUNCTION.call(new PyObject[0], null));
    }
}
