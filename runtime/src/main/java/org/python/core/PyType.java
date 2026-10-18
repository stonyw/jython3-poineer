package org.python.core;

/**
 * Type objects. This slice needs only enough to give every value a {@code __class__}; bases, MRO,
 * the metaclass protocol and slot lookup arrive with the class system at M3, and are deliberately
 * not stubbed here.
 */
public class PyType extends PyObject {

    public static final PyType TYPE = root("type");

    public static final PyType NONE = builtin("NoneType");
    public static final PyType NOTIMP = builtin("NotImplementedType");
    public static final PyType ELLIPSIS = builtin("ellipsis");
    public static final PyType BOOL = builtin("bool");
    public static final PyType INT = builtin("int");
    public static final PyType FLOAT = builtin("float");
    public static final PyType COMPLEX = builtin("complex");
    public static final PyType STR = builtin("str");
    public static final PyType BYTES = builtin("bytes");
    public static final PyType TUPLE = builtin("tuple");
    public static final PyType LIST = builtin("list");
    public static final PyType DICT = builtin("dict");
    public static final PyType SET = builtin("set");
    public static final PyType FROZENSET = builtin("frozenset");
    public static final PyType SLICE = builtin("slice");
    public static final PyType CODE = builtin("code");
    public static final PyType ITERATOR = builtin("iterator");
    public static final PyType STOPITERATION = builtin("StopIteration");

    private final String name;

    private PyType(String name) {
        this.name = name;
    }

    /** Every type in this slice is an instance of {@code type}; {@code type} itself is fixed up. */
    private static PyType root(String name) {
        PyType t = new PyType(name);
        t.type = t;
        return t;
    }

    private static PyType builtin(String name) {
        PyType t = new PyType(name);
        t.type = TYPE;
        return t;
    }

    public final String name() {
        return name;
    }

    /**
     * Types hash by name rather than by address. CPython uses the address, which is not
     * reproducible across runs; name is stable and collisions are legal.
     */
    @Override
    public long hash() {
        return PyHash.textHash(name);
    }

    @Override
    public String repr() {
        return "<class '" + name + "'>";
    }
}
