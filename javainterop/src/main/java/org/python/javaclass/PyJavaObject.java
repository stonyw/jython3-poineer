package org.python.javaclass;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.python.core.PyBool;
import org.python.core.PyInt;
import org.python.core.PyIterator;
import org.python.core.PyNone;
import org.python.core.PyNotImplemented;
import org.python.core.PyObject;
import org.python.core.Slots;

/**
 * A Java object, as a Python value.
 *
 * <p>The Java object is kept inside rather than copied, so identity survives a round trip: a widget
 * handed to a Java method is the same widget that comes back, and mutating it through Python mutates it
 * for Java. That is why {@link JavaConvert#toJava} can unwrap this type by asking for the field.
 *
 * <p>Three of Python's protocols are reinterpreted here rather than merely forwarded, and each is the
 * mapping Jython 2.7 established and Python readers expect: a {@code List} indexes with {@code x[i]} and
 * answers {@code len}; a {@code Map} is indexed by key and iterates its keys, like a {@code dict}; a
 * {@code getX()}/{@code isX()} pair is readable as an attribute and its {@code setX} is writable as one.
 * An array gets the same indexing as a list. PLAN.md names a {@code PyArray} type for arrays, and one
 * Python-visible type per Java array class is M2-c work — the distinction only becomes observable when a
 * class declares {@code f(int[])} and the caller has to know which array came back.
 */
public final class PyJavaObject extends PyObject {

    private final Object target;

    PyJavaObject(Object target) {
        super(PyJavaType.of(target.getClass()));
        this.target = target;
    }

    /** The Java object behind this value. */
    public Object target() {
        return target;
    }

    private Class<?> reflected() {
        return target.getClass();
    }

    // --- attributes ----------------------------------------------------------------------

    /**
     * Field, then bean property, then method — the order a Java reader looks them up in.
     *
     * <p>A real field wins over an accessor that decapitalizes to the same name, which is 2.7's rule and
     * the one that keeps {@code widget.size} reading the field even if the class later grows a
     * {@code getSize()}: a field is a fact about the object's layout, an accessor a claim about it.
     */
    @Override
    public PyObject getAttr(String attribute) {
        return memberOf(target, this, attribute);
    }

    /**
     * The attribute {@code attribute} of the Java object {@code target}, presented as a member of
     * {@code owner}.
     *
     * <p>{@code owner} is an argument rather than always {@code this} because one Java object can be
     * reached by two Python values: {@link PyJavaThrowable} is a Python exception whose attributes are
     * the {@code Throwable}'s methods, and the method it exposes has to be bound to the exception, not
     * to a wrapper sitting behind it. {@code LOAD_ATTR} clears its receiver slot only for a callable
     * bound to the very object it looked the attribute up on, so binding to anything else hands the
     * receiver over twice and {@code e.getMessage()} reads as {@code e.getMessage(e)}.
     */
    static PyObject memberOf(Object target, PyObject owner, String attribute) {
        if (attribute.equals("__class__")) {
            return owner.getType();
        }
        Class<?> reflected = target.getClass();
        Field field = instanceField(reflected, attribute);
        if (field != null) {
            return readField(target, field);
        }
        Method getter = JavaMembers.getterOf(reflected, attribute);
        if (getter != null) {
            return JavaConvert.toPython(invokeOn(owner, target, getter));
        }
        List<Method> methods = JavaMethods.named(reflected, attribute);
        if (!methods.isEmpty()) {
            return JavaMethods.instance(owner, attribute, methods, target);
        }
        return PyNotImplemented.INSTANCE;
    }

    /** {@code widget.size = 3} writes the field; {@code widget.name = "x"} calls {@code setName}. */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        Field field = instanceField(reflected(), attribute);
        if (field != null) {
            try {
                field.set(target, JavaConvert.toJava(value, field.getType()));
            } catch (IllegalAccessException | IllegalArgumentException hidden) {
                throw Slots.attributeError(
                        "'%s' object attribute '%s' is not writable", getType().name(), attribute);
            }
            return PyNone.INSTANCE;
        }
        Method setter = JavaMembers.setterOf(reflected(), attribute);
        if (setter != null) {
            invokeOn(
                    this,
                    target,
                    setter,
                    JavaConvert.toJava(value, setter.getParameterTypes()[0]));
            return PyNone.INSTANCE;
        }
        return super.setAttr(attribute, value);
    }

    private static Field instanceField(Class<?> reflected, String name) {
        try {
            Field field = reflected.getField(name);
            return Modifier.isStatic(field.getModifiers()) ? null : field;
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static PyObject readField(Object target, Field field) {
        try {
            return JavaConvert.toPython(field.get(target));
        } catch (IllegalAccessException hidden) {
            return PyNotImplemented.INSTANCE;
        }
    }

    /**
     * Call one member directly, which is only ever done once its shape is already known.
     *
     * <p>An overload set goes through {@link Overloads}; this path exists for the single accessor a bean
     * property names, where Java itself has already resolved the choice.
     */
    private static Object invokeOn(PyObject owner, Object target, Method method, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw JavaInterop.causedBy(failure.getTargetException());
        } catch (IllegalAccessException | IllegalArgumentException hidden) {
            throw Slots.attributeError(
                    "'%s' object has no readable attribute '%s'",
                    owner.getType().name(), method.getName());
        }
    }

    // --- Python's own view of the value --------------------------------------------------

    /** {@code str(widget)} is Java's {@code toString()} — the answer a Java caller gets. */
    @Override
    public String str() {
        return String.valueOf(target);
    }

    @Override
    public String repr() {
        return str();
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyJavaObject java && target.equals(java.target);
    }

    @Override
    public long hash() {
        return target.hashCode();
    }

    // --- container protocol --------------------------------------------------------------

    @Override
    public PyObject length() {
        if (target instanceof Collection<?> collection) {
            return PyInt.of(collection.size());
        }
        if (target instanceof Map<?, ?> map) {
            return PyInt.of(map.size());
        }
        if (reflected().isArray()) {
            return PyInt.of(Array.getLength(target));
        }
        return PyNotImplemented.INSTANCE;
    }

    /** A {@code Map} is indexed by key; a {@code List} or an array by position, negatives included. */
    @Override
    public PyObject getItem(PyObject key) {
        if (target instanceof Map<?, ?> map) {
            return JavaConvert.toPython(map.get(JavaConvert.javaOf(key)));
        }
        if (target instanceof List<?> list) {
            int at = JavaMembers.index(key, list.size(), getType().name());
            return JavaConvert.toPython(list.get(at));
        }
        if (reflected().isArray()) {
            int at = JavaMembers.index(key, Array.getLength(target), getType().name());
            return JavaConvert.toPython(Array.get(target, at));
        }
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code items[0] = "z"} and {@code sizes["a"] = 1}.
     *
     * <p>A list's element type is erased at run time, so the value is converted to {@code Object} and
     * Java's own check inside {@code set} rejects a wrong one — the same {@code ClassCastException} a
     * Java caller gets, arriving as the Java exception it is.
     */
    @Override
    @SuppressWarnings("unchecked")
    public PyObject setItem(PyObject key, PyObject value) {
        if (target instanceof Map) {
            ((Map<Object, Object>) target).put(JavaConvert.javaOf(key), JavaConvert.javaOf(value));
            return PyNone.INSTANCE;
        }
        if (target instanceof List) {
            List<Object> list = (List<Object>) target;
            list.set(JavaMembers.index(key, list.size(), getType().name()), JavaConvert.javaOf(value));
            return PyNone.INSTANCE;
        }
        if (reflected().isArray()) {
            Array.set(
                    target, JavaMembers.index(key, Array.getLength(target), getType().name()),
                    JavaConvert.javaOf(value));
            return PyNone.INSTANCE;
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PyObject delItem(PyObject key) {
        if (target instanceof Map) {
            ((Map<Object, Object>) target).remove(JavaConvert.javaOf(key));
            return PyNone.INSTANCE;
        }
        if (target instanceof List<?> list) {
            list.remove(JavaMembers.index(key, list.size(), getType().name()));
            return PyNone.INSTANCE;
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject contains(PyObject item) {
        Object wanted = JavaConvert.javaOf(item);
        if (target instanceof Map<?, ?> map) {
            return PyBool.of(map.containsKey(wanted));
        }
        if (target instanceof Collection<?> collection) {
            return PyBool.of(collection.contains(wanted));
        }
        if (reflected().isArray()) {
            for (int i = 0; i < Array.getLength(target); i++) {
                if (Objects.equals(wanted, Array.get(target, i))) {
                    return PyBool.of(true);
                }
            }
            return PyBool.of(false);
        }
        return PyNotImplemented.INSTANCE;
    }

    /** A {@code Map} iterates its keys, because Python reads a mapping by its keys. */
    @Override
    public PyObject iterator() {
        if (target instanceof Map<?, ?> map) {
            return over(map.keySet().iterator());
        }
        if (target instanceof Collection<?> collection) {
            return over(collection.iterator());
        }
        if (target instanceof Iterator<?> source) {
            return over(source);
        }
        if (reflected().isArray()) {
            PyObject[] items = new PyObject[Array.getLength(target)];
            for (int i = 0; i < items.length; i++) {
                items[i] = JavaConvert.toPython(Array.get(target, i));
            }
            return PyIterator.of(items);
        }
        return PyNotImplemented.INSTANCE;
    }

    /**
     * Iteration that keeps asking the Java iterator, rather than draining it first.
     *
     * <p>{@code java.util.Iterator} is single-use and sometimes endless, so copying it into a list would
     * be wrong for {@code for x in scanner} as well as slow for a large collection.
     */
    private static PyObject over(Iterator<?> source) {
        return new PyIterator() {
            @Override
            public PyObject nextOrNull() {
                return source.hasNext() ? JavaConvert.toPython(source.next()) : null;
            }
        };
    }
}
