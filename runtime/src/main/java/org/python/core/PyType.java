package org.python.core;

/**
 * Type objects.
 *
 * A type is an instance of {@code type}, carries a name, is callable as a constructor, and answers for
 * the members its values inherit: {@link #mro()} is the order both a member lookup and {@code
 * isinstance} walk, and a class built by the class system ({@link PyClass}) computes it with CPython's
 * C3 merge instead of using the single-parent chain a built-in type declares. The metaclass protocol is
 * a later M3 slice, so every class here is built by {@code type} and is an instance of {@code type}.
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
    /** What {@code str.upper} is: a method reached through its type rather than through a value. */
    public static final PyType METHOD_DESCRIPTOR = builtin("method_descriptor");
    /** The three members that are nothing but the descriptor protocol, each its own type. */
    public static final PyType PROPERTY = builtin("property");
    public static final PyType CLASS_METHOD = builtin("classmethod");
    public static final PyType STATIC_METHOD = builtin("staticmethod");
    public static final PyType MODULE = builtin("module");
    public static final PyType CELL = builtin("cell");
    public static final PyType ITERATOR = builtin("iterator");
    public static final PyType RANGE = builtin("range");

    private String name;

    /**
     * The one base this type has.
     *
     * A built-in type's base is {@code object} and an exception class's is the exception it derives
     * from. For a class built by the class system this is {@code __base__} — the first of its bases —
     * and the lookup that matters is {@link #mro()}, which a class computes once when it is built and
     * stores here.
     */
    private final PyType parent;

    /**
     * This type's lookup order, or null while it is still only the parent chain.
     *
     * Package-visible because a class built by {@link Classes} arrives with its linearization already
     * computed: C3 is not a walk up {@link #parent}, and asking for it twice would give two different
     * arrays for the same class.
     */
    PyType[] order;

    /**
     * Protected because a type declared outside this package has to be able to name its parent:
     * {@code PyJavaType} in the interop module is a {@code PyType} like any other, and the seam that
     * lets it exist is this constructor rather than a copy of the dispatch above it.
     */
    protected PyType(String name, PyType parent) {
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

    /**
     * {@code Point.__name__ = 'Other'}, which a class built by Python is allowed to do to itself.
     *
     * <p>Package-private because a built-in type's name is not the runtime's to change: CPython refuses
     * that on the type, and {@link #setAttr} here is the place that says so.
     */
    void setName(String name) {
        this.name = name;
    }

    /**
     * How an uncaught exception of this class is named.
     *
     * CPython qualifies a class that does not live in {@code builtins} — {@code mymodule.Error} — and
     * a Java exception is never in {@code builtins}, so {@code java.lang.IllegalStateException} is the
     * answer the interop layer has to be able to give. A builtin type keeps its bare name.
     */
    public String qualifiedName() {
        return name;
    }

    public final PyType parent() {
        return parent;
    }

    /**
     * The order this type's members are looked up in: itself, then its bases, then {@code object}.
     *
     * A built-in type has only the chain its parents declare, and that is already C3's answer for a
     * single base — {@code bool}, {@code int}, {@code object}. A class built by the class system arrives
     * with its linearization in {@link #order}, computed once because multiple inheritance cannot be
     * walked as a parent pointer and because {@code X.__mro__ is X.__mro__} has to keep being the same
     * order.
     *
     * <p>The array is the type's own and is shared with every caller: read it, do not change it.
     */
    public PyType[] mro() {
        if (order == null) {
            java.util.List<PyType> chain = new java.util.ArrayList<>();
            for (PyType type = this; type != null; type = type.parent) {
                chain.add(type);
            }
            order = chain.toArray(new PyType[0]);
        }
        return order;
    }

    /**
     * {@code int.__name__}, {@code int.__base__}, {@code int.bit_length}.
     *
     * A method reached through the type rather than through one of its values is a
     * {@link PyMethodDescriptor}, the same object CPython keeps in the type's own dictionary: it takes
     * the value as its first argument, which is what makes {@code str.upper("abc")} and
     * {@code sorted(words, key=str.lower)} answer.
     *
     * <p>A type has the methods it inherits as well as the ones it declares, so the lookup walks
     * {@link #mro()} and stops at the first table that defines the name — which is why
     * {@code bool.bit_length} is {@code int.bit_length} and not an {@code AttributeError}.
     */
    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__", "__qualname__" -> PyStr.of(name);
            case "__base__" -> parent == null ? PyNone.INSTANCE : parent;
            case "__bases__" -> parent == null ? PyTuple.EMPTY : PyTuple.of(parent);
            case "__mro__" -> PyTuple.of(mro());
            case "__class__" -> type;
            case "mro" -> MRO.withReceiver(this);
            default -> inherited(attribute);
        };
    }

    /**
     * {@code int.mro()}: the order as a list, of the type the method was reached through.
     *
     * <p>The receiver arrives as {@code args[0]} because {@link PyNativeFunction#withReceiver} attaches it,
     * which is what lets {@code Point.mro()} and {@code int.mro()} share one body and each name itself
     * {@code Point.mro()} in an arity message.
     */
    private static final PyNativeFunction MRO =
            new PyNativeFunction(
                    "mro",
                    (args, kwNames) -> {
                        PyType owner = (PyType) args[0];
                        String qualified = owner.name() + ".mro()";
                        if (kwNames != null) {
                            throw Slots.typeError("%s takes no keyword arguments", qualified);
                        }
                        if (args.length != 1) {
                            throw Slots.typeError(
                                    "%s takes no arguments (%d given)", qualified, args.length - 1);
                        }
                        return PyList.of(owner.mro());
                    });

    /** The member named {@code attribute} in this type's lookup order, or a refusal if none has it. */
    private PyObject inherited(String attribute) {
        for (PyType owner : mro()) {
            Members methods = Members.tableOf(owner);
            if (methods == null) {
                continue;
            }
            PyObject descriptor = methods.describe(attribute);
            if (descriptor != PyNotImplemented.INSTANCE) {
                return descriptor;
            }
        }
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code int.x = 1}.
     *
     * <p>A built-in type's members are compiled in, so there is nowhere to put the value and CPython
     * says that rather than reporting a missing attribute. {@link PyClass} overrides both this and
     * {@link #delAttr} because a class does have a dictionary to write into.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        throw Slots.typeError(
                "cannot set '%s' attribute of immutable type '%s'", attribute, name);
    }

    @Override
    public PyObject delAttr(String attribute) {
        throw Slots.typeError(
                "cannot delete '%s' attribute of immutable type '%s'", attribute, name);
    }

    /**
     * Python {@code isinstance} for the builtin types: a walk of {@link #mro()}, by identity. A
     * {@code PyInt} is not an instance of {@code PyFloat}, so {@code int} and {@code float} are
     * unrelated here even though the numeric tower treats a value of one as acceptable to the other.
     */
    public boolean matches(PyType other) {
        for (PyType candidate : mro()) {
            if (candidate == other) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code isinstance(value, self)}: the question asked of the type that has to answer it.
     *
     * <p>It is a method on the required type rather than a walk the caller performs, because a Java
     * class has interfaces and several supertypes — {@code isinstance(x, java.io.Serializable)} is true
     * with no parent pointer leading there — and because the answer for a Python value can depend on
     * whether that value is acceptable where the class is expected, which only the class's own type
     * can judge.
     */
    public boolean instanceOf(PyObject value) {
        return value.getType().matches(this);
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
