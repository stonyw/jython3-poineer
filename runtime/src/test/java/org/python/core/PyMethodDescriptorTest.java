package org.python.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A method reached through its type, measured against CPython 3.14.8.
 *
 * <p>{@code str.upper("abc")} is the unbound half of the descriptor protocol that {@code "abc".upper()}
 * already exercises from the other side, and it is the form a Java-facing caller keeps reaching for:
 * {@code sorted(words, key=str.lower)}, and a {@code Function} argument that is a method rather than a
 * lambda.
 */
class PyMethodDescriptorTest {

    /**
     * A method table is registered while the value class that declares it is loaded, so a type nobody
     * has made a value of yet has no table to answer from. Making one of each here keeps these tests
     * independent of which one the JVM happens to run first.
     */
    @BeforeAll
    static void loadTheValueClassesTheseTestsAsk() throws ClassNotFoundException {
        for (Class<?> values : new Class<?>[] {PyStr.class, PyInt.class, PyBool.class}) {
            Class.forName(values.getName());
        }
    }

    /** {@code repr(str.upper)}, {@code type(str.upper)} and the names CPython gives it. */
    @Test
    void looksLikeCpythonsMethodDescriptor() {
        PyObject upper = PyType.STR.getAttr("upper");

        assertThat(upper).isInstanceOf(PyMethodDescriptor.class);
        assertThat(upper.repr()).isEqualTo("<method 'upper' of 'str' objects>");
        assertThat(upper.getType().name()).isEqualTo("method_descriptor");
        assertThat(Slots.getAttr(upper, "__name__")).isEqualTo(PyStr.of("upper"));
        assertThat(Slots.getAttr(upper, "__qualname__")).isEqualTo(PyStr.of("str.upper"));
        assertThat(Slots.isCallable(upper)).isTrue();

        // The type's dictionary holds one descriptor, so asking twice gives the same object back.
        assertThat(PyType.STR.getAttr("upper")).isSameAs(upper);
    }

    /** {@code str.upper("abc")}, {@code int.bit_length(5)}: the value is the first argument. */
    @Test
    void takesItsReceiverAsTheFirstArgument() {
        assertThat(call(PyType.STR, "upper", PyStr.of("abc"))).isEqualTo(PyStr.of("ABC"));
        assertThat(call(PyType.INT, "bit_length", PyInt.of(5))).isEqualTo(PyInt.of(3));
        assertThat(call(PyType.STR, "find", PyStr.of("abc"), PyStr.of("c"))).isEqualTo(PyInt.of(2));
    }

    /**
     * A value whose type answers nothing for the method is refused in the value's own words.
     *
     * <p>{@code bool} is a subtype of {@code int}, so the receiver passes the type check, but reaching an
     * inherited method through the parent's table is the MRO walk that M3's class system owns. Until it
     * exists the two spellings have to agree with each other — which is what a caller such as
     * {@code Optional.map(int)} depends on — so {@code int.bit_length(True)} says exactly what
     * {@code True.bit_length()} says rather than reaching past the value's own answer.
     */
    @Test
    void bothSpellingsOfTheSameMethodAgree() {
        assertThat(messageOf(() -> Slots.getAttr(PyBool.TRUE, "bit_length")))
                .isEqualTo("AttributeError: 'bool' object has no attribute 'bit_length'")
                .isEqualTo(messageOf(() -> call(PyType.INT, "bit_length", PyBool.TRUE)));
    }

    private static String messageOf(ThrowableAssert.ThrowingCallable call) {
        return catchThrowable(call).getMessage();
    }

    /** {@code str.upper()} — CPython 3.14's wording for a descriptor nobody has given a value yet. */
    @Test
    void namesItselfUnboundWhenNothingWasPassed() {
        assertThatThrownBy(() -> call(PyType.STR, "upper"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "TypeError: unbound method str.upper()"
                                                        + " needs an argument"));
    }

    /** {@code str.upper(5)}: the value has to be one the descriptor was defined for. */
    @Test
    void refusesAValueOfTheWrongType() {
        assertThatThrownBy(() -> call(PyType.STR, "upper", PyInt.of(5)))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "TypeError: descriptor 'upper' for 'str' objects"
                                                        + " doesn't apply to a 'int' object"));
    }

    /** The method's own argument checks still run, and report the call as one argument too many. */
    @Test
    void theExtraArgumentIsCountedByTheMethod() {
        assertThatThrownBy(() -> call(PyType.STR, "upper", PyStr.of("abc"), PyStr.of("x")))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo("TypeError: str.upper() takes no arguments (1 given)"));
    }

    /** {@code str.nonsense} is an {@code AttributeError} naming the type, not a silent {@code None}. */
    @Test
    void aTypeHasOnlyItsOwnMethods() {
        assertThatThrownBy(() -> Slots.getAttr(PyType.STR, "nonsense"))
                .isInstanceOfSatisfying(
                        PyRaise.class,
                        raised ->
                                assertThat(raised.getMessage())
                                        .isEqualTo(
                                                "AttributeError: type object 'str' has no"
                                                        + " attribute 'nonsense'"));
    }

    private static PyObject call(PyType type, String name, PyObject... arguments) {
        PyObject method = Slots.getAttr(type, name);
        return Slots.call(method, arguments, null);
    }
}
