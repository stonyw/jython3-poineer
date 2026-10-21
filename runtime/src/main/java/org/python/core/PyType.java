package org.python.core;

/**
 * Type objects.
 *
 * A type is an instance of a metatype, carries a name, is callable as a constructor, and answers for
 * the members its values inherit: {@link #mro()} is the order both a member lookup and {@code
 * isinstance} walk, and a class built by the class system ({@link PyClass}) computes it with CPython's
 * C3 merge instead of using the single-parent chain a built-in type declares — except for {@code
 * ExceptionGroup}, the one built-in over two bases, whose order comes from that same merge. Every class
 * the class system builds is an instance of {@code type} unless a metaclass was named for it, in which
 * case {@link #classMember} reads its members through that metatype first — the protocol {@link TypeDict}
 * exists to answer.
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
    /**
     * {@code sys.exc_info()[2]} and {@code e.__traceback__}.
     *
     * <p>Named here because a traceback is a value Python reads attributes off, and because CPython's
     * {@code PyTraceBack_Type} is a type in {@code builtins} like any other — its values are instances of
     * it and nothing else.
     */
    public static final PyType TRACEBACK = builtin("traceback");
    /** One activation of one code object, as Python sees it through {@code tb_frame} and {@code f_back}. */
    public static final PyType FRAME = builtin("frame");
    public static final PyType FUNCTION = builtin("function");
    public static final PyType BUILTIN_FUNCTION = builtin("builtin_function_or_method");
    public static final PyType METHOD = builtin("method");
    /** What {@code str.upper} is: a method reached through its type rather than through a value. */
    public static final PyType METHOD_DESCRIPTOR = builtin("method_descriptor");
    /** What {@code type.__call__} is: a slot of a built-in type, reached the same way. */
    static final PyType WRAPPER_DESCRIPTOR = builtin("wrapper_descriptor");
    /** What {@code type.__prepare__} is before a class reads it: a class method of a built-in type. */
    static final PyType CLASS_METHOD_DESCRIPTOR = builtin("classmethod_descriptor");
    /** What {@code Plain.__call__} is: a built-in slot already bound to a value. */
    static final PyType METHOD_WRAPPER = builtin("method-wrapper");
    /** The three members that are nothing but the descriptor protocol, each its own type. */
    public static final PyType PROPERTY = builtin("property");
    public static final PyType CLASS_METHOD = builtin("classmethod");
    public static final PyType STATIC_METHOD = builtin("staticmethod");
    public static final PyType MODULE = builtin("module");
    public static final PyType CELL = builtin("cell");
    public static final PyType ITERATOR = builtin("iterator");
    /**
     * What calling a generator function answers: {@code type(gen)} is {@code generator}, and the name is
     * not in {@code builtins}, so {@code generator} is unreachable from Python — same as CPython, where
     * {@code TypesGenerator} is added to the structmember table but not to the builtins dict.
     */
    public static final PyType GENERATOR = builtin("generator");
    public static final PyType RANGE = builtin("range");
    /**
     * {@code super} is a type although nothing it hands out is one: a proxy holds a class, a value and
     * the order measured against that value, and reads names out of the classes after the class it was
     * given. It is named here because {@link Interpreter} compares a loaded {@code super} against it by
     * identity, exactly as CPython compares a pointer against {@code &PySuper_Type}.
     */
    public static final PyType SUPER = builtin("super");

    private String name;

    /**
     * The sentence this type answers {@code __doc__} with, or null where it declares none.
     *
     * <p>CPython keeps this text in the type's own dictionary as the {@code __doc__} entry of a static
     * type, which is what makes {@code BaseExceptionGroup.__doc__} a sentence while {@code
     * ExceptionGroup.__doc__} is {@code None} — the second type declares no doc of its own, and
     * {@code type_getdoc} answers {@code None} rather than walking up to the first one it can find. A
     * class Python wrote keeps the name in its namespace and is answered there, before this field.
     */
    private String doc;

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
     * The bases as more than a parent, which only {@code ExceptionGroup} among the built-ins has; null for
     * every type whose inheritance a single {@link #parent} pointer describes.
     */
    private PyType[] declaredBases;

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

    /**
     * The type's own doc, declared where the type is.
     *
     * <p>Only the sentences a built-in type is written down with arrive here: everything a Python class
     * says about itself is in the namespace the class statement collected.
     */
    PyType documented(String text) {
        doc = text;
        return this;
    }

    /**
     * A built-in type declared over more bases than one parent pointer can carry, which is exactly
     * {@code ExceptionGroup}: CPython makes it with {@code PyErr_NewException} over {@code
     * (BaseExceptionGroup, Exception)}, so its {@code __base__} is the first of those while its order is
     * the C3 merge of both. The merge is {@link Classes#linearize}, the same one a class statement runs —
     * a static type is not exempt from it, and hand-writing the order here would be a second answer to the
     * same question.
     */
    static PyType declare(String name, PyType... bases) {
        PyType type = new PyType(name, bases[0]);
        type.type = TYPE;
        if (bases.length > 1) {
            type.declaredBases = bases;
            type.order = Classes.linearize(type, PyTuple.of(bases));
        }
        return type;
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
     * single base — {@code bool}, {@code int}, {@code object}. The one built-in with two bases is
     * {@code ExceptionGroup}, whose order {@link #declare(String, PyType...)} linearizes here rather than
     * walking a parent pointer. A class built by the class system arrives with its linearization in {@link
     * #order}, computed once because multiple inheritance cannot be walked as a parent pointer and because
     * {@code X.__mro__ is X.__mro__} has to keep being the same order.
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
     *
     * <p>The names that are not methods at all — {@code __name__}, {@code __base__}, {@code __mro__} —
     * are answered above, as CPython answers them from {@code type}'s getset descriptors; everything
     * else, including the class-system members, goes through {@link #classMember}.
     */
    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__", "__qualname__" -> PyStr.of(name);
            case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
            case "__base__" -> parent == null ? PyNone.INSTANCE : parent;
            case "__bases__" -> parent == null ? PyTuple.EMPTY : PyTuple.of(bases());
            case "__mro__" -> PyTuple.of(mro());
            case "__class__" -> type;
            default -> classMember(attribute);
        };
    }

    /**
     * The bases this type is declared over. One for every built-in but {@code ExceptionGroup}, which
     * CPython creates over {@code (BaseExceptionGroup, Exception)} and which therefore cannot be a parent
     * pointer twice.
     */
    private PyType[] bases() {
        if (declaredBases != null) {
            return declaredBases;
        }
        return parent == null ? new PyType[0] : new PyType[] {parent};
    }

    /**
     * {@code X.name} in CPython's {@code _Py_type_getattro_impl} order: a data descriptor of the
     * metatype owns the name, then this type and its bases, then the metatype's other members.
     *
     * <p>The middle step reads with no receiver and the last two with the class as the receiver, and that
     * difference is what a program sees. A class whose own order holds {@code type} — anything built by
     * {@code type}, and any metaclass — finds the member there and gets it back unbound, so
     * {@code Meta.mro()} complains about an unbound method; a class whose order does not, like {@code int}
     * or {@code Plain}, is left with the metatype step and gets the same method bound to itself, which is
     * why {@code Plain.mro()} answers.
     *
     * @return the attribute, or {@link PyNotImplemented} when neither this type's order nor the
     *     metatype's names it, which is what {@link Slots#getAttr} turns into the complaint about a type
     *     object having no such attribute
     */
    final PyObject classMember(String attribute) {
        PyObject fromMeta = Specials.member(type, attribute);
        if (fromMeta != null && Descriptors.writes(fromMeta)) {
            return Descriptors.get(fromMeta, this, type);
        }
        PyObject declared = Specials.member(this, attribute);
        if (declared != null) {
            return Descriptors.get(declared, null, this);
        }
        return fromMeta == null
                ? PyNotImplemented.INSTANCE
                : Descriptors.get(fromMeta, this, type);
    }

    /**
     * The name a member of this class reports itself with.
     *
     * <p>{@code Outer.Inner.__init_subclass__() takes no keyword arguments} is worded with this rather
     * than with {@code __name__}, because CPython builds the qualified name of a bound {@code METH_CLASS}
     * method out of the class it bound to — {@code type->tp_qualname}, {@code __qualname__} in Python.
     */
    String dottedName() {
        return name;
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

    /**
     * {@code ValueError("x")}, {@code list(range(3))} — a type is its own constructor.
     *
     * <p>A class is called through its metatype first, which is {@code slot_tp_call} doing the work of
     * {@code Py_TYPE(cls)->tp_call}: a metaclass that declares {@code __call__} owns every call of every
     * class it built, and reaches ordinary construction only by asking for it — {@code type.__call__(cls,
     * *args)} — because that call comes back to {@link Classes#typeCall} and not here. A class whose
     * metatype is {@code type} has no such declaration to find, since {@code type}'s own {@code __call__}
     * is the slot this method is standing in for, and the {@link Specials} lookup reads only the
     * namespaces of classes Python wrote.
     */
    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        PyObject declared = Specials.invoke(this, "__call__", args, kwNames);
        if (declared != PyNotImplemented.INSTANCE) {
            return declared;
        }
        return Classes.typeCall(this, args, kwNames);
    }

    /**
     * {@code C[key]}: the two questions {@code PyObject_GetItem} puts to a class, in its order.
     *
     * <p>A {@code __getitem__} on the metatype is answered before {@code __class_getitem__}, because the
     * first is a real {@code mp_subscript} slot and the special case for classes in the generic
     * {@code PyObject_GetItem} only runs when the type has no subscript of its own. Nothing here is
     * subscriptable by itself: a refusal leaves {@link Slots#getItem} to word it as {@code type 'C' is not
     * subscriptable}, which is what CPython says for {@code int[int]}, and the two built-in families that
     * do answer in CPython — {@code type[int]} and {@code list[int]} — hand back a {@code types.GenericAlias},
     * which this runtime has no model of.
     */
    @Override
    public PyObject getItem(PyObject key) {
        PyObject fromMeta = Specials.invoke(this, "__getitem__", key);
        if (fromMeta != PyNotImplemented.INSTANCE) {
            return fromMeta;
        }
        PyObject generic = getAttr("__class_getitem__");
        return generic == PyNotImplemented.INSTANCE
                ? PyNotImplemented.INSTANCE
                : Slots.call(generic, new PyObject[] {key}, null);
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
