package org.python.javaclass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.python.core.PyDict;
import org.python.core.PyList;
import org.python.core.PyNone;
import org.python.core.PyNotImplemented;
import org.python.core.PyObject;
import org.python.core.PyStr;
import org.python.core.PyType;

/**
 * A Java package, as a Python module.
 *
 * <p>Its type is {@link PyType#MODULE}, so {@code type(java.util).__name__} says {@code module} and a
 * package behaves like the namespace it is. Nothing is loaded until it is asked for: {@code import
 * java.awt} costs one name lookup even though {@code java.awt} contains hundreds of classes, which is
 * why Jython 2.7 could afford to make {@code import java.util.*} the first line of every script.
 *
 * <p>One object per package name, so that {@code import java.util} and {@code from java.util import
 * ArrayList} reach the same dictionary, and so a class reached twice is the same class twice.
 */
public final class JavaPackage extends PyObject {

    private static final Map<String, JavaPackage> cache = new LinkedHashMap<>();

    private final String name;
    private final PyDict dictionary = PyDict.empty();

    /** The module for {@code dotted}, making it on first sight. */
    static synchronized JavaPackage of(String dotted) {
        return cache.computeIfAbsent(dotted, JavaPackage::new);
    }

    private JavaPackage(String name) {
        super(PyType.MODULE);
        this.name = name;
        dictionary.put(PyStr.interned("__name__"), PyStr.of(name));
        dictionary.put(PyStr.interned("__doc__"), PyStr.of("Java package " + name));
    }

    @Override
    public PyObject getAttr(String attribute) {
        PyObject loaded = dictionary.get(PyStr.interned(attribute));
        if (loaded != null) {
            return loaded;
        }
        PyObject member = member(attribute);
        if (member == null) {
            return PyNotImplemented.INSTANCE;
        }
        dictionary.put(PyStr.interned(attribute), member);
        return member;
    }

    /**
     * The class or subpackage {@code attribute} names in this package, or null for neither.
     *
     * <p>A subpackage wins over a class of the same name, which is the only ordering that can be right:
     * {@code java.util.List} and {@code java.util} itself cannot both be named {@code util}, but a
     * package and a class genuinely can share a name across Java's namespaces and Python has one.
     */
    private PyObject member(String attribute) {
        if (attribute.equals("__dict__")) {
            return dictionary;
        }
        if (attribute.equals("__all__")) {
            return published();
        }
        String nested = name.isEmpty() ? attribute : name + "." + attribute;
        if (PackageIndex.isPackage(nested)) {
            return of(nested);
        }
        return PyJavaType.classIn(name, attribute);
    }

    /**
     * Every name {@code from this package import *} binds, which is what makes Java's wildcard a Python
     * one.
     *
     * <p>A Java package has no public marker Python can read late — accessibility lives on the class, not
     * in the directory — so the list is the directory: the classes in it and the packages under it, nested
     * classes left out because {@code Widget$Builder} is not a name Python can write. Reading it is the
     * one thing that has to know a package's whole contents at once, and it happens only when asked.
     */
    private PyObject published() {
        List<String> names = PackageIndex.membersOf(name);
        PyObject[] items = new PyObject[names.size()];
        for (int i = 0; i < names.size(); i++) {
            items[i] = PyStr.of(names.get(i));
        }
        return PyList.of(items);
    }

    /**
     * Whether this package exists as Java sees it.
     *
     * <p>A head name such as {@code java} is not itself a package — no class lives in it — but
     * {@code import java.util} binds it, so "is a prefix of a package" counts as existing. That is the
     * distinction CPython's own finder makes with a namespace package, and it is what makes
     * {@code import java.sql} fail with {@code ModuleNotFoundError} while {@code import java} does not.
     */
    public static boolean exists(String dotted) {
        return PackageIndex.isPackage(dotted);
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        dictionary.put(PyStr.interned(attribute), value);
        return PyNone.INSTANCE;
    }

    public String name() {
        return name;
    }

    @Override
    public String repr() {
        return "<module '" + name + "'>";
    }
}
