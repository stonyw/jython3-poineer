package org.python.core;

/**
 * Type objects.
 *
 * This slice needs a type to be an instance of {@code type}, to carry a name, and to be callable as a
 * constructor; bases beyond the single parent an exception class needs, the MRO, the metaclass
 * protocol and slot lookup on user-defined classes arrive with the class system at M3, and are
 * deliberately not stubbed here.
 *
 * The built-in types are {@code static final} fields rather than a registry, so that a slot
 * implementation can compare against its type by identity — which is what CPython does, and what
 * makes {@code isinstance(x, int)} on a {@code PyInt} a pointer test.
 */
public class PyType extends PyObject {

    /**
     * The root of every class's inheritance chain, and the only type with no parent.
     *
     * It is declared before {@link #TYPE} because the two complete each other: {@code object} is an
     * instance of {@code type} and {@code type} is a subclass of {@code object}, and the second of
     * those is what makes {@code isinstance(x, object)} answer for every value.
     */
    public static final PyType OBJECT = new PyType("object", null);

    public static final PyType TYPE = new PyType("type", OBJECT);

    static {
        // Every type is an instance of `type`, including `type` itself and `object`.
        OBJECT.type = TYPE;
        TYPE.type = TYPE;
    }

    public static final PyType NONE = builtin("NoneType");
    public static final PyType NOTIMP = builtin("NotImplementedType");
    public static final PyType ELLIPSIS = builtin("ellipsis");
    public static final PyType INT = builtin("int");
    /**
     * {@code bool} is a subtype of {@code int}, not merely compatible with it:
     * {@code isinstance(True, int)} is true, and that is what lets {@code sum([True, 1])} and
     * {@code {True: 1, 1: 2}} behave as ints that print as {@code True}.
     */
    public static final PyType BOOL = subclass("bool", INT);
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
    public static final PyType FUNCTION = builtin("function");
    public static final PyType BUILTIN_FUNCTION = builtin("builtin_function_or_method");
    public static final PyType METHOD = builtin("method");
    public static final PyType MODULE = builtin("module");
    public static final PyType CELL = builtin("cell");
    public static final PyType ITERATOR = builtin("iterator");
    public static final PyType RANGE = builtin("range");

    private final String name;

    /**
     * The one base this type has.
     *
     * A built-in type's base is {@code object} and an exception class's is the exception it derives
     * from, because both {@code except} matching and {@code isinstance} are a walk up this chain.
     * User-defined classes with several bases and a computed MRO are M3, at which point this field
     * becomes the head of that walk rather than the whole of it.
     */
    private final PyType parent;

    private PyType(String name, PyType parent) {
        this.name = name;
        this.parent = parent;
    }

    /**
     * A built-in type: an instance of {@code type} whose values descend from {@code object}.
     *
     * Package-private so that a runtime object outside the core types above — the text stream
     * {@code sys.stdout} is, for one — can declare its own rather than borrow {@link #declare}, whose
     * named parent is the point of that call.
     */
    static PyType builtin(String name) {
        PyType type = new PyType(name, OBJECT);
        type.type = TYPE;
        return type;
    }

    /** A built-in type that is a subclass of another one, which {@code bool} and {@code int} are. */
    private static PyType subclass(String name, PyType parent) {
        PyType type = new PyType(name, parent);
        type.type = TYPE;
        return type;
    }

    /** A class in the exception hierarchy, constructible by calling it. */
    static PyType declare(String name, PyType parent) {
        return subclass(name, parent);
    }

    public final String name() {
        return name;
    }

    public final PyType parent() {
        return parent;
    }

    /**
     * {@code int.__name__} and {@code int.__base__}.
     *
     * Only the two the exception hierarchy can already answer truthfully; {@code __bases__} as a
     * tuple, and a {@code __module__} that is not always {@code builtins}, wait for M3.
     */
    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__", "__qualname__" -> PyStr.of(name);
            case "__base__" -> parent == null ? PyNone.INSTANCE : parent;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * Python {@code isinstance} for the builtin types: a walk up {@link #parent}, by identity. A
     * {@code PyInt} is not an instance of {@code PyFloat}, so {@code int} and {@code float} are
     * unrelated here even though the numeric tower treats a value of one as acceptable to the other.
     */
    public boolean matches(PyType other) {
        for (PyType t = this; t != null; t = t.parent) {
            if (t == other) {
                return true;
            }
        }
        return false;
    }

    /** {@code ValueError("x")}, {@code list(range(3))} — a type is its own constructor. */
    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return Constructors.instantiate(this, args, kwNames);
    }

    /**
     * Types hash by name rather than by address. CPython uses the address, which is not reproducible
     * across runs; name is stable and collisions are legal.
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
