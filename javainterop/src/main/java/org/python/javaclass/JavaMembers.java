package org.python.javaclass;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import org.python.core.PyInt;
import org.python.core.PyObject;
import org.python.core.Slots;

/**
 * Java's bean naming conventions, read the way Python attributes are written.
 *
 * <p>{@code getX()}/{@code isX()}/{@code setX(v)} become the attribute {@code x}. The decapitalization
 * is Java Beans' own, {@code Introspector} rule and not a lowercasing: a name whose first two characters
 * are both uppercase keeps them, so {@code getURL()} stays {@code URL} rather than becoming {@code uRL},
 * which is what a Java caller would type and what 2.7 answers too.
 */
final class JavaMembers {

    private JavaMembers() {
    }

    /** The zero-argument accessor that names {@code property}, or null when the class has none. */
    static Method getterOf(Class<?> type, String property) {
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String name = method.getName();
            if (property.equals(propertyName(name, "get"))
                    || property.equals(propertyName(name, "is"))) {
                return method;
            }
        }
        return null;
    }

    /** The one-argument mutator that names {@code property}, or null when the class has none. */
    static Method setterOf(Class<?> type, String property) {
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 1
                    && !Modifier.isStatic(method.getModifiers())
                    && property.equals(propertyName(method.getName(), "set"))) {
                return method;
            }
        }
        return null;
    }

    /** {@code getName} is the property {@code name}; {@code getURL} is the property {@code URL}. */
    private static String propertyName(String methodName, String prefix) {
        if (!methodName.startsWith(prefix) || methodName.length() <= prefix.length()) {
            return null;
        }
        String tail = methodName.substring(prefix.length());
        // Java Beans leaves a name alone when it opens with two capitals, because `URL` is a word and
        // `uRL` is not; a plain lowercasing would hide `getURL` behind an attribute nobody can type.
        if (tail.length() > 1 && Character.isUpperCase(tail.charAt(1))) {
            return tail;
        }
        return Character.toLowerCase(tail.charAt(0)) + tail.substring(1);
    }

    /**
     * A Python index into a Java list or array, with Python's negative half.
     *
     * <p>{@code items[-1]} is Java's {@code items.get(size - 1)}. Out of range is {@code IndexError}
     * rather than Java's {@code IndexOutOfBoundsException}, because the caller wrote Python and CPython's
     * wording is the one they read.
     */
    static int index(PyObject key, int length, String typeName) {
        if (!(key instanceof PyInt number)) {
            throw Slots.typeError(
                    "%s indices must be integers or slices, not %s", typeName, key.getType().name());
        }
        BigInteger whole = number.getValue();
        if (whole.bitLength() > 31) {
            throw Slots.indexError("index out of range");
        }
        int at = whole.intValue();
        if (at < 0) {
            at += length;
        }
        if (at < 0 || at >= length) {
            throw Slots.indexError("index out of range");
        }
        return at;
    }
}
