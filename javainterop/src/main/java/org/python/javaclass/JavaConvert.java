package org.python.javaclass;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.python.core.PyBool;
import org.python.core.PyBoundMethod;
import org.python.core.PyDict;
import org.python.core.PyFloat;
import org.python.core.PyFunction;
import org.python.core.PyInt;
import org.python.core.PyList;
import org.python.core.PyNativeFunction;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.PyStr;
import org.python.core.PyTuple;
import org.python.core.Slots;

/**
 * The two directions of the boundary.
 *
 * <p>Java arriving in Python is a table, and a short one: {@code null} becomes {@code None}, a
 * {@code String} becomes {@code str}, every integral box becomes an {@code int} of whatever width the
 * value needs, {@code void} becomes {@code None}, and anything else becomes a value of its own Java
 * type. That half is cheap because a Java value already knows its class.
 *
 * <p>Python going into a Java parameter is the hard half, and it is asked twice per call: first
 * {@link #phase}, "can this value reach that type at all", which decides which overloads are
 * applicable; then {@link #toJava}, "hand over the object". Both read the same rules, so a candidate
 * cannot be selected by one table and argued into by another — the drift a two-table design gets into
 * (Jython 2.7 keeps one ranking table for fixed-arity calls and a second, independent cost scale for
 * varargs, and its own {@code Options.reflectedArgsLegacyMode} defaults to the worse of the two).
 *
 * <p>The phases are Java's own, from JLS 15.12.2: strict conversion first — exact and widened
 * primitives, reference assignability — then loose, which is where boxing lives, and where the
 * conversions Python needs but Java has no name for are filed: narrowing an {@code int} that fits, a
 * {@code list} becoming a {@code List}, a callable becoming a single-abstract-method interface. A call
 * that could be satisfied in an earlier phase is never answered in a later one, which is why
 * {@code describe(int)} beats {@code describe(Integer)} without either table having to say so.
 */
public final class JavaConvert {

    /**
     * Returned where {@code null} would mean "the Java parameter is null".
     *
     * <p>A refusal cannot be {@code null}, because {@code None} converts to {@code null} for every
     * reference type. Jython 2.7 makes the same choice with {@code Py.NoConversion} for the same
     * reason, and keeps it a value instead of an exception so that overload selection can try the next
     * candidate without unwinding.
     */
    public static final Object NO_CONVERSION = new Object();

    static final int STRICT = 0;
    static final int LOOSE = 1;
    static final int VARARGS = 2;
    static final int IMPOSSIBLE = -1;

    /** Primitive widening, in the order JLS 5.1.2 allows it; the distance along this row is the cost. */
    private static final Class<?>[] WIDENING = {
        byte.class, short.class, int.class, long.class, float.class, double.class
    };

    private static final Map<Class<?>, Class<?>> UNBOXED = Map.of(
            Integer.class, int.class,
            Long.class, long.class,
            Short.class, short.class,
            Byte.class, byte.class,
            Float.class, float.class,
            Double.class, double.class,
            Boolean.class, boolean.class,
            Character.class, char.class);

    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
            int.class, Integer.class,
            long.class, Long.class,
            short.class, Short.class,
            byte.class, Byte.class,
            float.class, Float.class,
            double.class, Double.class,
            boolean.class, Boolean.class,
            char.class, Character.class);

    private JavaConvert() {
    }

    // --- Java -> Python -------------------------------------------------------------------

    /** Wrap a value that came out of Java. */
    public static PyObject toPython(Object value) {
        if (value == null) {
            return PyNone.INSTANCE;
        }
        if (value instanceof PyObject already) {
            return already;
        }
        if (value instanceof String text) {
            return PyStr.of(text);
        }
        if (value instanceof Character one) {
            return PyStr.of(String.valueOf(one));
        }
        if (value instanceof Boolean flag) {
            return PyBool.of(flag);
        }
        if (value instanceof Double || value instanceof Float) {
            return PyFloat.of(((Number) value).doubleValue());
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return PyInt.of(((Number) value).longValue());
        }
        if (value instanceof Long whole) {
            return PyInt.of(whole);
        }
        if (value instanceof BigInteger big) {
            return PyInt.of(big);
        }
        if (value instanceof Class<?> kind) {
            return PyJavaType.of(kind);
        }
        if (value instanceof byte[] bytes) {
            return org.python.core.PyBytes.of(bytes);
        }
        return new PyJavaObject(value);
    }

    // --- what a Python value is, in Java terms -------------------------------------------

    /**
     * The Java type a Python value stands for when nothing more specific has been asked of it.
     *
     * <p>An unbounded Python {@code int} reports the narrowest primitive that holds it, because that is
     * what a Java literal of the same value would be, and {@code BigInteger} only once the value has
     * outgrown {@code long}.
     */
    static Class<?> canonical(PyObject value) {
        if (value instanceof PyStr) {
            return String.class;
        }
        if (value instanceof PyBool) {
            return boolean.class;
        }
        if (value instanceof PyInt number) {
            BigInteger big = number.getValue();
            if (big.bitLength() <= 31) {
                return int.class;
            }
            return big.bitLength() <= 63 ? long.class : BigInteger.class;
        }
        if (value instanceof PyFloat) {
            return double.class;
        }
        if (value instanceof PyNone) {
            return null;
        }
        if (value instanceof PyList || value instanceof PyTuple) {
            return List.class;
        }
        if (value instanceof PyDict) {
            return Map.class;
        }
        if (value instanceof PyJavaObject java) {
            return java.target().getClass();
        }
        return PyObject.class;
    }

    /** Which of Java's searches admits {@code value} as {@code target}, or {@link #IMPOSSIBLE}. */
    static int phase(PyObject value, Class<?> target) {
        if (target == PyObject.class) {
            return STRICT;
        }
        if (value instanceof PyNone) {
            return target.isPrimitive() ? IMPOSSIBLE : STRICT;
        }
        if (value instanceof PyJavaObject java) {
            Object target0 = java.target();
            if (target.isInstance(target0)) {
                return STRICT;
            }
            // A boxed Java number can still be unboxed into a primitive parameter, which is Java's
            // loose invocation phase and no more generous than that.
            return boxedForm(unbox(target)).isAssignableFrom(boxedForm(target0.getClass()))
                    ? LOOSE
                    : IMPOSSIBLE;
        }
        Class<?> from = canonical(value);
        if (from == null) {
            return IMPOSSIBLE;
        }
        if (from == PyObject.class) {
            if (target.isInterface() && isCallable(value)) {
                return LOOSE;
            }
            return assignable(from, target) ? STRICT : IMPOSSIBLE;
        }
        if (from == String.class) {
            if (target == String.class || target == CharSequence.class) {
                return STRICT;
            }
            if (unbox(target) == char.class) {
                // One character is a character, which is Java's own rule for a one-letter String and
                // stays out of the strict phase because Java only applies it to literals.
                return ((String) materialize(value)).length() == 1 ? LOOSE : IMPOSSIBLE;
            }
            return assignable(from, target) ? STRICT : IMPOSSIBLE;
        }
        if (isNumeric(from) || isNumeric(target)) {
            return numericPhase(value, from, target);
        }
        if (from == List.class) {
            if (target == List.class || target == Collection.class || target == Iterable.class) {
                return LOOSE;
            }
            if (target.isArray()) {
                return elementPhases(value, target.getComponentType()) ? LOOSE : IMPOSSIBLE;
            }
            return assignable(from, target) ? LOOSE : IMPOSSIBLE;
        }
        if (from == Map.class) {
            return assignable(from, target) ? LOOSE : IMPOSSIBLE;
        }
        return assignable(from, target) ? STRICT : IMPOSSIBLE;
    }

    private static int numericPhase(PyObject value, Class<?> from, Class<?> target) {
        if (unbox(target) == boolean.class) {
            return from == boolean.class ? STRICT : (isNumeric(from) ? LOOSE : IMPOSSIBLE);
        }
        if (from == boolean.class) {
            // Python's bool is an int, so True reaches a numeric parameter — at the loose phase,
            // because Java would refuse it outright.
            return isNumeric(target) ? LOOSE : IMPOSSIBLE;
        }
        if (target.isPrimitive()) {
            if (!isNumeric(from)
                    || from == BigInteger.class && target != double.class && target != float.class) {
                return IMPOSSIBLE;
            }
            if (from == target) {
                return STRICT;
            }
            if (wideningSteps(from, target) >= 0) {
                return STRICT;
            }
            if (from == double.class) {
                return target == float.class ? LOOSE : IMPOSSIBLE;
            }
            return fitsNarrowly(value, target) ? LOOSE : IMPOSSIBLE;
        }
        // A reference parameter: box what the value already is and see if it lands.
        Class<?> boxed = boxedForm(from);
        if (target.isAssignableFrom(boxed)) {
            return from == target ? STRICT : LOOSE;
        }
        // A Python int has no width of its own until something asks for one, so it also reaches a
        // wrapper whose primitive holds it. Java refuses the same pair of types and means it; this is
        // the loose phase, which is where the conversions only Python asks for are filed. It is what
        // lets f(Long) take 7, and f(Character) take 65, once f(int) and f(Integer) are not there.
        return value instanceof PyInt && fitsNarrowly(value, unbox(target)) ? LOOSE : IMPOSSIBLE;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class
                || type == long.class
                || type == short.class
                || type == byte.class
                || type == char.class
                || type == float.class
                || type == double.class
                || type == boolean.class
                || type == BigInteger.class
                || UNBOXED.containsKey(type)
                || type == Number.class;
    }

    /** Distance along Java's widening row, or -1 when no widening exists. */
    static int wideningSteps(Class<?> from, Class<?> to) {
        int a = rank(from);
        int b = rank(to);
        return a >= 0 && b > a ? b - a : -1;
    }

    private static int rank(Class<?> type) {
        for (int i = 0; i < WIDENING.length; i++) {
            if (WIDENING[i] == type) {
                return i;
            }
        }
        return -1;
    }

    static boolean fitsNarrowly(PyObject value, Class<?> primitive) {
        BigInteger big = ((PyInt) value).getValue();
        return switch (primitive.getName()) {
            case "byte" -> big.bitLength() <= 7;
            case "short" -> big.bitLength() <= 15;
            case "int" -> big.bitLength() <= 31;
            case "long" -> big.bitLength() <= 63;
            case "char" -> big.signum() >= 0 && big.bitLength() <= 16;
            case "float", "double" -> true;
            default -> false;
        };
    }

    private static boolean assignable(Class<?> from, Class<?> to) {
        return !from.isPrimitive() && !to.isPrimitive() && to.isAssignableFrom(from);
    }

    private static boolean elementPhases(PyObject value, Class<?> component) {
        for (PyObject item : elements(value)) {
            if (phase(item, component) == IMPOSSIBLE) {
                return false;
            }
        }
        return true;
    }

    /**
     * How far {@code target} stands from what {@code value} already is, used only to separate two
     * candidates the phase left tied. Zero is exact, {@code Object} is one hop from everything, and an
     * interface is nearer than {@code Object} — which is what makes {@code f(String)} beat
     * {@code f(Object)} and {@code f(CharSequence)} beat {@code f(Object)}.
     */
    static int distance(PyObject value, Class<?> target) {
        if (target == PyObject.class) {
            return 0;
        }
        if (value instanceof PyNone) {
            return 1;
        }
        if (value instanceof PyJavaObject java) {
            return hops(java.target().getClass(), target);
        }
        Class<?> from = canonical(value);
        if (from == target) {
            return 0;
        }
        if (isNumeric(from) && isNumeric(target)) {
            if (target == boxedForm(from)) {
                // The value's own type in its own box is as near as a reference parameter gets, and
                // nearer than the widened one: a Python int is an Integer before it is a Long.
                return 0;
            }
            int widening = wideningSteps(from, unbox(target));
            if (widening >= 0) {
                return widening;
            }
            if (from == double.class && target == float.class) {
                return 1;
            }
            return target == Number.class || target == BigInteger.class ? 6 : 5;
        }
        if (from == String.class && (target == String.class || target == CharSequence.class)) {
            return target == String.class ? 0 : 1;
        }
        if (from == List.class || from == Map.class || target.isArray()) {
            return 3;
        }
        if (target == Object.class) {
            return 1;
        }
        Class<?> boxed = boxedForm(from);
        return target.isAssignableFrom(boxed) ? 2 + hops(boxed, target) : 7;
    }

    /** Inheritance distance between two reference types, interfaces counting as one step each. */
    static int hops(Class<?> from, Class<?> to) {
        if (from == to) {
            return 0;
        }
        if (!to.isAssignableFrom(from)) {
            return 8;
        }
        int best = 8;
        Class<?> parent = from.getSuperclass();
        if (parent != null) {
            best = Math.min(best, 1 + hops(parent, to));
        }
        for (Class<?> face : from.getInterfaces()) {
            best = Math.min(best, 1 + hops(face, to));
        }
        return best;
    }

    // --- Python -> Java -------------------------------------------------------------------

    /** The Java object to hand a parameter of type {@code target}, or {@link #NO_CONVERSION}. */
    public static Object toJava(PyObject value, Class<?> target) {
        if (phase(value, target) == IMPOSSIBLE) {
            return NO_CONVERSION;
        }
        if (target == PyObject.class) {
            return value;
        }
        if (value instanceof PyNone) {
            return null;
        }
        if (value instanceof PyJavaObject java) {
            return java.target();
        }
        if (target == boolean.class || target == Boolean.class) {
            return truth(value);
        }
        if (target == char.class || target == Character.class) {
            if (value instanceof PyStr text && text.getValue().length() == 1) {
                return text.getValue().charAt(0);
            }
            if (canonical(value) == int.class || canonical(value) == long.class) {
                return (char) ((PyInt) value).getValue().intValue();
            }
            return NO_CONVERSION;
        }
        if (isNumeric(unbox(target)) || target == Number.class || target == BigInteger.class) {
            return number(value, unbox(target), target);
        }
        if (target.isArray() && (value instanceof PyList || value instanceof PyTuple)) {
            return array(value, target);
        }
        if (target == String.class || target == CharSequence.class) {
            return ((PyStr) value).getValue();
        }
        if ((value instanceof PyList || value instanceof PyTuple) && collectionish(target)) {
            List<Object> out = new ArrayList<>();
            for (PyObject item : elements(value)) {
                out.add(javaOf(item));
            }
            return out;
        }
        if (value instanceof PyDict dictionary && mapish(target)) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<PyObject, PyObject> entry : dictionary.getItems().entrySet()) {
                out.put(javaOf(entry.getKey()), javaOf(entry.getValue()));
            }
            return out;
        }
        if (target == Object.class) {
            return javaOf(value);
        }
        if (target.isInterface() && isCallable(value)) {
            return JavaInterop.proxy(value, target);
        }
        return NO_CONVERSION;
    }

    /** The Java object a Python value becomes when nothing has been asked of it. */
    public static Object toJava(PyObject value) {
        return javaOf(value);
    }

    /** The Java object a Python value becomes when the parameter only said {@code Object}. */
    static Object javaOf(PyObject value) {
        if (value instanceof PyJavaObject java) {
            return java.target();
        }
        if (value instanceof PyNone) {
            // null, and not the None object, because a Java caller has no way to compare against None
            // and every Java API already uses null for "there is no value here".
            return null;
        }
        if (value instanceof PyStr text) {
            return text.getValue();
        }
        if (value instanceof PyBool flag) {
            return flag.getValue();
        }
        if (value instanceof PyInt number) {
            BigInteger big = number.getValue();
            if (big.bitLength() <= 31) {
                return big.intValue();
            }
            return big.bitLength() <= 63 ? big.longValue() : big;
        }
        if (value instanceof PyFloat decimal) {
            return decimal.getValue();
        }
        if (value instanceof PyList || value instanceof PyTuple) {
            List<Object> out = new ArrayList<>();
            for (PyObject item : elements(value)) {
                out.add(javaOf(item));
            }
            return out;
        }
        if (value instanceof PyDict dictionary) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<PyObject, PyObject> entry : dictionary.getItems().entrySet()) {
                out.put(javaOf(entry.getKey()), javaOf(entry.getValue()));
            }
            return out;
        }
        return value;
    }

    private static Object materialize(PyObject value) {
        return value instanceof PyStr text ? text.getValue() : Slots.str(value);
    }

    private static boolean truth(PyObject value) {
        return value instanceof PyBool flag
                ? flag.getValue()
                : value instanceof PyInt number
                        ? number.getValue().signum() != 0
                        : Slots.truth(value);
    }

    /**
     * The {@code Number} a Python value becomes for a numeric parameter.
     *
     * <p>The box handed back is deliberately the narrowest one the declared type allows, because
     * reflection unwraps it into a primitive parameter but will not convert an {@code Integer} into a
     * {@code long} on its own.
     */
    private static Object number(PyObject value, Class<?> primitive, Class<?> declared) {
        if (value instanceof PyInt number) {
            return fromWhole(number.getValue(), primitive, declared);
        }
        if (value instanceof PyBool flag) {
            return fromWhole(flag.getValue() ? BigInteger.ONE : BigInteger.ZERO, primitive, declared);
        }
        if (value instanceof PyFloat decimal) {
            return fromDecimal(decimal.getValue(), primitive, declared);
        }
        if (value instanceof PyJavaObject java && java.target() instanceof Number boxed) {
            return fromBoxed(boxed, primitive, declared);
        }
        return NO_CONVERSION;
    }

    private static Object fromWhole(BigInteger big, Class<?> primitive, Class<?> declared) {
        return switch (primitive.getName()) {
            case "byte" -> big.byteValue();
            case "short" -> big.shortValue();
            case "int" -> big.intValue();
            case "long" -> big.longValue();
            case "float" -> big.floatValue();
            case "double" -> big.doubleValue();
            default -> reference(big, declared);
        };
    }

    private static Object fromDecimal(double wide, Class<?> primitive, Class<?> declared) {
        return switch (primitive.getName()) {
            case "byte" -> (byte) wide;
            case "short" -> (short) wide;
            case "int" -> (int) wide;
            case "long" -> (long) wide;
            case "float" -> (float) wide;
            default -> reference(wide, declared);
        };
    }

    private static Object fromBoxed(Number boxed, Class<?> primitive, Class<?> declared) {
        if (primitive == declared && declared.isInstance(boxed)) {
            return boxed;
        }
        return switch (primitive.getName()) {
            case "byte" -> boxed.byteValue();
            case "short" -> boxed.shortValue();
            case "int" -> boxed.intValue();
            case "long" -> boxed.longValue();
            case "float" -> boxed.floatValue();
            case "double" -> boxed.doubleValue();
            default -> reference(boxed, declared);
        };
    }

    /**
     * The box a reference parameter of {@code declared} type needs for a value of {@code wide}.
     *
     * <p>Compared in boxed form on both sides, because {@code double.class.isAssignableFrom(Double.class)}
     * is false and yet a {@code Double} is exactly what a {@code double} parameter wants: reflection
     * unwraps it, and a {@code declared} of {@code double} here means the parameter was primitive.
     */
    private static Object reference(Object wide, Class<?> declared) {
        return boxedForm(declared).isAssignableFrom(boxedForm(wide.getClass())) ? wide : NO_CONVERSION;
    }

    private static Object array(PyObject value, Class<?> target) {
        Class<?> component = target.getComponentType();
        List<PyObject> items = elements(value);
        Object out = Array.newInstance(component, items.size());
        for (int i = 0; i < items.size(); i++) {
            Object element = toJava(items.get(i), component);
            if (element == NO_CONVERSION) {
                return NO_CONVERSION;
            }
            Array.set(out, i, element);
        }
        return out;
    }

    static List<PyObject> elements(PyObject sequence) {
        if (sequence instanceof PyList list) {
            return list.getItems();
        }
        return List.of(((PyTuple) sequence).getArray());
    }

    private static boolean collectionish(Class<?> target) {
        return target == List.class || target == Collection.class || target == Iterable.class;
    }

    private static boolean mapish(Class<?> target) {
        return target == Map.class;
    }

    static boolean isCallable(PyObject value) {
        return value instanceof PyFunction
                || value instanceof PyNativeFunction
                || value instanceof PyBoundMethod
                || value instanceof PyJavaType;
    }

    static Class<?> unbox(Class<?> target) {
        Class<?> primitive = UNBOXED.get(target);
        return primitive == null ? target : primitive;
    }

    /** The wrapper class of a primitive, or the type itself when it is already a reference type. */
    static Class<?> boxedForm(Class<?> type) {
        Class<?> box = BOXED.get(type);
        return box == null ? type : box;
    }
}
