package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The two questions {@code LOAD_SPECIAL} asks before a body runs, and the exception spelling
 * {@code WITH_EXCEPT_START} reports in, measured against CPython 3.14.8.
 *
 * <p>The corpus script measures the statement: which names are read, in what order, what each form of
 * member is called with, and which sentence a value that cannot be entered is refused with. This is the
 * pair of decisions behind it, which a script reaches only through the statement and so cannot pin — the
 * lookup that goes to the type and not to the value, and the judgement of whether a member takes the
 * value as an argument or binds to it. The classes here are made by {@link Classes#newType} and their
 * members are the Java-written kinds {@link DescriptorsTest} uses.
 */
class ContextManagerTest {

    /** {@code type(name, bases, {members})}, with the bases handed over as Java has them. */
    private static PyType type(String name, PyType[] bases, Object... members) {
        PyDict dict = PyDict.empty();
        for (int i = 0; i < members.length; i += 2) {
            dict.put(PyStr.interned((String) members[i]), (PyObject) members[i + 1]);
        }
        return (PyType) Classes.newType(PyType.TYPE, new PyObject[] {
            PyStr.of(name), PyTuple.of(bases), dict
        }, null);
    }

    /** A method Java wrote, standing in for the one Python would have named in the class body. */
    private static PyNativeFunction function(String name, PyObject answer) {
        return new PyNativeFunction(name, (args, kwNames) -> answer);
    }

    private static PyObject value(PyType kind) {
        return kind.call(new PyObject[0], null);
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

    /** {@code class Watched: def __enter__} — the names {@code with} reads, and where it reads them. */
    @Test
    void theProtocolNamesAreReadOffTheTypeAndNotOffTheValue() {
        PyType watched =
                type(
                        "Watched",
                        new PyType[] {PyType.OBJECT},
                        "__enter__",
                        function("__enter__", PyStr.of("entered")));
        PyObject held = value(watched);

        assertThat(Specials.member(held.getType(), "__enter__")).isNotNull();
        assertThat(Specials.member(held.getType(), "__exit__")).isNull();

        // The instance's own dictionary holds the second half and is not asked: this is the one rule
        // that makes `held.__enter__ = f` useless to `with`, and why the instruction cannot be written
        // as an ordinary attribute read.
        held.setAttr("__exit__", function("__exit__", PyBool.FALSE));
        assertThat(Specials.member(held.getType(), "__exit__")).isNull();
        assertThat(held.getAttr("__exit__")).isNotNull();
    }

    /** Whether a member answers for itself, which is the {@code tp_descr_get} slot CPython asks. */
    @Test
    void onlyAMemberThatAnswersForItselfIsReadThrough() {
        assertThat(Descriptors.binds(function("__enter__", PyNone.INSTANCE)))
                .as("a built-in function has no getter, so CPython leaves the value for the call")
                .isFalse();
        assertThat(Descriptors.binds(PyStr.of("a value filed under the name"))).isFalse();
        assertThat(Descriptors.binds(PyInt.of(1))).isFalse();

        PyObject wrapped = function("__get__", PyNone.INSTANCE);
        assertThat(Descriptors.binds(new PyStaticMethod(wrapped))).isTrue();
        assertThat(Descriptors.binds(new PyClassMethod(wrapped))).isTrue();
        assertThat(Descriptors.binds(new PyProperty(wrapped, PyNone.INSTANCE,
                PyNone.INSTANCE, PyNone.INSTANCE)))
                .isTrue();

        // A class Python wrote that declares `__get__` is a descriptor by the same slot, and the
        // corpus reads one: only a member Java wrote can be seen here, and `Descriptors` asks a
        // declared getter of `PyFunction` alone, exactly as the rest of the runtime asks a dunder.
        PyType plain = type("Plain", new PyType[] {PyType.OBJECT});
        assertThat(Descriptors.binds(value(plain))).isFalse();
        PyObject justAValue = PyStr.of("just a value");
        assertThat(Descriptors.get(justAValue, PyNone.INSTANCE, PyType.STR)).isSameAs(justAValue);
    }

    /**
     * The one member that is called with the value as an argument rather than bound to it: an unbound
     * method of a built-in type, which is CPython's {@code Py_TPFLAGS_METHOD_DESCRIPTOR} case.
     */
    @Test
    void anUnboundMethodOfABuiltInTypeTakesTheValueAsItsReceiver() {
        PyObject upper = Specials.member(PyType.STR, "upper");
        assertThat(upper).isInstanceOf(PyMethodDescriptor.class);

        // Read through a value it binds, and called as it stands it takes the value as its first
        // argument; either way the answer is the same method, which is why `LOAD_SPECIAL` can leave the
        // receiver slot filled for this family only.
        assertThat(Slots.call(upper, new PyObject[] {PyStr.of("abc")}, null))
                .isInstanceOfSatisfying(PyStr.class, text -> assertThat(text.getValue()).isEqualTo("ABC"));
        refuses(
                "TypeError: descriptor 'upper' for 'str' objects doesn't apply to a 'int' object",
                () -> Slots.call(upper, new PyObject[] {PyInt.of(3)}, null));

        // A function written in Java is not of that family: CPython clears the receiver and calls it
        // with the arguments alone, which is why `__enter__ = len` is refused for having none.
        PyObject length = PyRuntime.builtins().get(PyStr.interned("len"));
        assertThat(Descriptors.get(length, PyStr.of("abc"), PyType.STR)).isSameAs(length);
        refuses(
                "TypeError: len() takes exactly one argument (0 given)",
                () -> Slots.call(length, new PyObject[0], null));
    }

    /** {@code repr(e)} and {@code str(e)}: one argument stands on its own, several are a tuple. */
    @Test
    void oneArgumentIsReprWithoutTheTupleAroundIt() {
        assertThat(raised(ExceptionTypes.VALUE_ERROR).repr()).isEqualTo("ValueError()");
        assertThat(raised(ExceptionTypes.VALUE_ERROR, PyInt.of(1)).repr()).isEqualTo("ValueError(1)");
        assertThat(raised(ExceptionTypes.VALUE_ERROR, PyInt.of(1), PyInt.of(2)).repr())
                .isEqualTo("ValueError(1, 2)");
        assertThat(raised(ExceptionTypes.KEY_ERROR, PyStr.of("k")).repr())
                .isEqualTo("KeyError('k')");
        assertThat(raised(ExceptionTypes.VALUE_ERROR,
                        raised(ExceptionTypes.TYPE_ERROR, PyStr.of("inner")))
                        .repr())
                .isEqualTo("ValueError(TypeError('inner'))");

        // `str` of the same values: the lone argument unquoted, several in the tuple's spelling, and
        // KeyError the one exception that reports its key with `repr` whatever the count.
        assertThat(raised(ExceptionTypes.VALUE_ERROR).str()).isEmpty();
        assertThat(raised(ExceptionTypes.VALUE_ERROR, PyInt.of(1)).str()).isEqualTo("1");
        assertThat(raised(ExceptionTypes.VALUE_ERROR, PyInt.of(1), PyInt.of(2)).str())
                .isEqualTo("(1, 2)");
        assertThat(raised(ExceptionTypes.KEY_ERROR, PyStr.of("k")).str()).isEqualTo("'k'");
        assertThat(raised(ExceptionTypes.KEY_ERROR, PyInt.of(3)).str()).isEqualTo("3");
    }

    /**
     * The value {@code __exit__} is handed for a failure that never travelled a Python frame. An entry is
     * made as an error leaves a frame, so one made in Java and never raised has no chain to report.
     */
    @Test
    void anExceptionThatEscapedNoFrameCarriesNoTraceback() {
        PyBaseException error = raised(ExceptionTypes.VALUE_ERROR, PyStr.of("kept"));
        assertThat(error.traceback()).isSameAs(PyNone.INSTANCE);
        assertThat(error.getAttr("__traceback__")).isSameAs(PyNone.INSTANCE);
    }

    private static PyBaseException raised(PyType kind, PyObject... arguments) {
        return PyRaise.raise(kind, arguments).value();
    }
}
