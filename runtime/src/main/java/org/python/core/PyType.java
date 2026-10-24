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
     * The three {@code tp_flags} bits a pattern match reads.
     *
     * <p>The numbers are this runtime's own; what is CPython's is which type declares which bit.
     * {@link #SEQUENCE} belongs to {@code list}, {@code tuple} and {@code range} — and to none of
     * {@code str}, {@code bytes}, {@code set} or a class Python wrote, however well one of those answers
     * {@code __getitem__} and {@code __len__}. {@link #MAPPING} belongs to {@code dict} and to
     * {@code frame}. {@link #MATCH_SELF} belongs to the built-ins that have no {@code __match_args__} of
     * their own and answer a class pattern's single positional by handing back the subject: {@code list},
     * {@code tuple}, {@code str}, {@code bytes}, {@code set}, {@code frozenset}, {@code dict}, {@code
     * int} and {@code float}. A written class declares none of the three, which is why a mapping pattern
     * refuses a class that writes {@code get} and {@code case Plain(x)} refuses a class that writes no
     * {@code __match_args__}.
     */
    static final int SEQUENCE = 1 << 0;
    static final int MAPPING = 1 << 1;
    static final int MATCH_SELF = 1 << 2;

    /**
     * Every type this runtime has made, in the order each was declared.
     *
     * <p>{@code X.__subclasses__()} reads it for the classes whose bases name {@code X}. CPython keeps a
     * list of them on each type and prunes it as a class dies; a class here is never collected, so one list
     * for the whole runtime answers the same question without the bookkeeping. It is declared above the
     * built-in types because they are what first fills it: {@code OBJECT} is constructed before anything
     * else in this class runs.
     */
    private static final java.util.List<PyType> MADE =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * The root of every class's inheritance chain, and the only type with no parent.
     *
     * It is declared before {@link #TYPE} because the two complete each other: {@code object} is an
     * instance of {@code type} and {@code type} is a subclass of {@code object}, and the second of
     * those is what makes {@code isinstance(x, object)} answer for every value.
     */
    public static final PyType OBJECT =
            new PyType("object", null)
                    .documented(
                            "The base class of the class hierarchy.\n\nWhen called, it accepts no arguments"
                                    + " and returns a new featureless\ninstance that has no instance"
                                    + " attributes and cannot be given any.\n");

    public static final PyType TYPE = new PyType("type", OBJECT);

    static {
        // Every type is an instance of `type`, including `type` itself and `object`.
        OBJECT.type = TYPE;
        TYPE.type = TYPE;
    }

    public static final PyType NONE = builtin("NoneType");
    public static final PyType NOTIMP = builtin("NotImplementedType");
    public static final PyType ELLIPSIS = builtin("ellipsis");
    public static final PyType INT = builtin("int").flagged(MATCH_SELF);
    /**
     * {@code bool} is a subtype of {@code int}, not merely compatible with it:
     * {@code isinstance(True, int)} is true, and that is what lets {@code sum([True, 1])} and
     * {@code {True: 1, 1: 2}} behave as ints that print as {@code True}.
     */
    public static final PyType BOOL = subclass("bool", INT);
    public static final PyType FLOAT = builtin("float").flagged(MATCH_SELF);
    public static final PyType COMPLEX = builtin("complex");

    /**
     * The two sentences CPython's {@code unicode_doc} and {@code bytes_doc} write for these types.
     *
     * <p>Each is a plain string in the type's own dictionary, which is what puts it in front of the
     * inherited {@code object.__doc__} and makes {@code bytes.__doc__} answer for {@code bytes} while a
     * class under it answers {@code None}. Both list the constructor's shapes before they say anything
     * about behaviour, because both are read first by {@code help()} and by the interactive echo.
     */
    private static final String STR_DOC =
            "str(object='') -> str\nstr(bytes_or_buffer[, encoding[, errors]]) -> str\n\nCreate a new"
                    + " string object from the given object. If encoding or\nerrors is specified, then the"
                    + " object must expose a data buffer\nthat will be decoded using the given encoding and"
                    + " error handler.\nOtherwise, returns the result of object.__str__() (if defined)\nor"
                    + " repr(object).\nencoding defaults to 'utf-8'.\nerrors defaults to 'strict'.";

    private static final String BYTES_DOC =
            "bytes(iterable_of_ints) -> bytes\nbytes(string, encoding[, errors]) -> bytes\nbytes"
                    + "(bytes_or_buffer) -> immutable copy of bytes_or_buffer\nbytes(int) -> bytes object"
                    + " of size given by the parameter initialized with null bytes\nbytes() -> empty bytes"
                    + " object\n\nConstruct an immutable array of bytes from:\n  - an iterable yielding"
                    + " integers in range(256)\n  - a text string encoded using the specified encoding\n "
                    + " - any object implementing the buffer API.\n  - an integer";

    public static final PyType STR = builtin("str").flagged(MATCH_SELF).documented(STR_DOC);
    public static final PyType BYTES = builtin("bytes").flagged(MATCH_SELF).documented(BYTES_DOC);
    public static final PyType TUPLE = builtin("tuple").flagged(SEQUENCE | MATCH_SELF);
    public static final PyType LIST = builtin("list").flagged(SEQUENCE | MATCH_SELF);
    public static final PyType DICT = builtin("dict").flagged(MAPPING | MATCH_SELF);
    public static final PyType SET = builtin("set").flagged(MATCH_SELF);
    public static final PyType FROZENSET = builtin("frozenset").flagged(MATCH_SELF);
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
    public static final PyType FRAME = builtin("frame").flagged(MAPPING);
    public static final PyType FUNCTION = builtin("function");
    public static final PyType BUILTIN_FUNCTION = builtin("builtin_function_or_method");
    public static final PyType METHOD = builtin("method");
    /** What {@code str.upper} is: a method reached through its type rather than through a value. */
    public static final PyType METHOD_DESCRIPTOR = builtin("method_descriptor");
    /** What {@code sys.version_info.major} is: a field of a value, reached the same way. */
    public static final PyType MEMBER_DESCRIPTOR = builtin("member_descriptor");
    /** What {@code type.__call__} is: a slot of a built-in type, reached the same way. */
    static final PyType WRAPPER_DESCRIPTOR = builtin("wrapper_descriptor");
    /** What {@code type.__prepare__} is before a class reads it: a class method of a built-in type. */
    static final PyType CLASS_METHOD_DESCRIPTOR = builtin("classmethod_descriptor");
    /** What {@code BaseException.args} is: a pair of C functions reached as an attribute. */
    static final PyType GETSET_DESCRIPTOR = builtin("getset_descriptor");
    /**
     * What {@code int.__dict__} is: the read-only window a type's dictionary opens onto itself.
     *
     * <p>Named here because the window is a value Python reads members off, and because its own class is
     * one of the types this file declares a table for — {@code mappingproxy} is not in {@code builtins},
     * which is what CPython's {@code static PyTypeObject mappingproxy_type} with no {@code tp_name} dot
     * gives it.
     */
    public static final PyType MAPPING_PROXY =
            builtin("mappingproxy").documented("Read-only proxy of a mapping.");
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
     * {@code type(co.co_lines())} and {@code type(co.co_positions())}.
     *
     * <p>Neither name is reachable from Python, and neither type has a method of its own: each is a
     * cursor into one code object's locations, which is why CPython gives it an {@code iternext} and no
     * sequence protocol. What a program can see of the difference between the two is the type's name.
     */
    public static final PyType LINE_ITERATOR = builtin("line_iterator");

    /** The positions sibling of {@link #LINE_ITERATOR}, one item per instruction rather than per run. */
    public static final PyType POSITIONS_ITERATOR = builtin("positions_iterator");
    /**
     * What calling a generator function answers: {@code type(gen)} is {@code generator}, and the name is
     * not in {@code builtins}, so {@code generator} is unreachable from Python — same as CPython, where
     * {@code TypesGenerator} is added to the structmember table but not to the builtins dict.
     */
    public static final PyType GENERATOR = builtin("generator");
    public static final PyType RANGE = builtin("range").flagged(SEQUENCE);
    /**
     * {@code super} is a type although nothing it hands out is one: a proxy holds a class, a value and
     * the order measured against that value, and reads names out of the classes after the class it was
     * given. It is named here because {@link Interpreter} compares a loaded {@code super} against it by
     * identity, exactly as CPython compares a pointer against {@code &PySuper_Type}.
     */
    public static final PyType SUPER = builtin("super");

    /**
     * {@code sys.version_info}'s own class: the one named sequence this runtime builds.
     *
     * <p>It is declared over {@code tuple}, which is what makes a version tuple index, hash, compare and
     * iterate exactly as the tuple it prints differently from. CPython builds it with
     * {@code Py_TPFLAGS_DISALLOW_INSTANTIATION}, so the only route to a second value of it is
     * {@code __replace__} on the one the interpreter makes at startup.
     */
    public static final PyType VERSION_INFO =
            namedSequence("sys.version_info")
                    .documented("sys.version_info\n\nVersion information as a named tuple.");

    private String name;

    /**
     * The name this type is written down with, which is CPython's {@code tp_name}.
     *
     * <p>It is the same as {@link #name} for every type except a named sequence, whose declared name carries
     * the module in front of it: {@code sys.version_info} is what {@code repr(type(x))}, what
     * {@code descriptor 'major' for 'sys.version_info' objects doesn't apply to a 'int' object} and what
     * {@code cannot create 'sys.version_info' instances} all print, while {@code __name__} is the half after
     * the dot. A Java type's declared name is its class name and has dots in it already, which is why this
     * field copies {@link #name} rather than splitting it.
     */
    private String filedName;

    /** Whether calling this type is refused the way an empty {@code tp_new} is. */
    private final boolean withoutInstances;

    /** The bits above that this type declares for itself; see {@link #hasFeature}. */
    private int features;

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
     * The dictionary this type's values inherit their members from, as one object kept for as long as
     * the type lives.
     *
     * <p>CPython's {@code tp_dict} is the same pointer for the whole life of a static type, and that is
     * what makes {@code int.__dict__ == int.__dict__} true while {@code int.__dict__ is int.__dict__} is
     * false: the read makes a new {@link PyMappingProxy} over the one dictionary, and two of those compare
     * by the mapping underneath them. Built lazily because the tables are written in the order {@link
     * TypeDict} declares them, which is after the types themselves.
     */
    private PyDict namespace;

    /**
     * Protected because a type declared outside this package has to be able to name its parent:
     * {@code PyJavaType} in the interop module is a {@code PyType} like any other, and the seam that
     * lets it exist is this constructor rather than a copy of the dispatch above it.
     */
    protected PyType(String name, PyType parent) {
        this(name, parent, false);
    }

    private PyType(String name, PyType parent, boolean withoutInstances) {
        this.name = name;
        this.filedName = name;
        this.parent = parent;
        this.withoutInstances = withoutInstances;
        MADE.add(this);
    }

    /** The types this runtime has made, for {@code X.__subclasses__()} to pick its children out of. */
    static PyType[] children() {
        return MADE.toArray(new PyType[0]);
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

    /**
     * A named sequence, declared by the dotted name CPython writes it down with.
     *
     * <p>{@code sys.version_info} is the whole of the point: the type is a {@code tuple} that reports a
     * name of its own, and the two halves of that name are used for different questions. Python reads the
     * last half as {@code __name__} and {@code __qualname__}, and the first as {@code __module__}, while a
     * message about the type quotes the name in full — including in {@code <class 'sys.version_info'>}.
     */
    private static PyType namedSequence(String filedName) {
        PyType type = new PyType(tailOf(filedName), TUPLE, true);
        type.filedName = filedName;
        type.type = TYPE;
        return type;
    }

    /** The half of a dotted name after the last dot, which is what Python calls the type. */
    private static String tailOf(String filedName) {
        int dot = filedName.lastIndexOf('.');
        return dot < 0 ? filedName : filedName.substring(dot + 1);
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
     * The pattern bits this type declares, named where the type is.
     *
     * <p>Only a built-in arrives with them: a class statement has no way to set them, which is CPython's
     * rule too — its {@code Py_TPFLAGS_SEQUENCE} lives in the static type's own {@code tp_flags}, and the
     * one route by which a written class can acquire it is {@code collections.abc} registration, which
     * needs the standard library this runtime does not import yet.
     */
    PyType flagged(int bits) {
        features = bits;
        return this;
    }

    /**
     * {@code PyType_HasFeature}: this type, or one it descends from, declares the bit.
     *
     * <p>The walk is the inheritance rather than a lookup of this type's own field, and it matters for
     * {@code bool}: {@code bool} declares nothing and carries {@link #MATCH_SELF} because {@code int}
     * does, which is what lets {@code case bool(flag)} hand back the {@code True}. A built-in's order is
     * its parent chain, so this is the same walk {@link #mro()} makes for a member.
     */
    boolean hasFeature(int bit) {
        for (PyType type : mro()) {
            if ((type.features & bit) != 0) {
                return true;
            }
        }
        return false;
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
     * The name a message about this type quotes: {@code sys.version_info}, {@code tuple}, {@code int},
     * {@code java.util.ArrayList}.
     *
     * <p>CPython's {@code PyErr_Format} calls that name {@code tp_name} and reaches for it in every
     * complaint that identifies a type from outside — a failed read, a refused write, a comparison neither
     * side answers, a format spec no {@code __format__} takes. {@link #name()} is the answer for
     * {@code __name__}, and for everything except a named sequence the two are the same string.
     */
    public final String filedName() {
        return filedName;
    }

    /** Whether this type can be called at all, which is CPython's empty {@code tp_new}. */
    final boolean declaresNoInstances() {
        return withoutInstances;
    }

    /**
     * {@code Point.__name__ = 'Other'}, which a class built by Python is allowed to do to itself.
     *
     * <p>Package-private because a built-in type's name is not the runtime's to change: CPython refuses
     * that on the type, and {@link #setAttr} here is the place that says so.
     */
    void setName(String name) {
        this.name = name;
        // CPython writes the new name into `tp_name` itself, so a renamed class is named by the new
        // string in every message as well as in `__name__`. Only a named sequence keeps the two apart,
        // and no sequence is a class Python wrote.
        this.filedName = name;
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
     * CPython's {@code tp_dictoffset != 0}: whether a value of this type keeps attributes of its own.
     *
     * <p>{@link Classes} asks this of the base before deciding whether the class it is building files a
     * {@code __dict__} descriptor, because the name belongs to the class that <em>introduces</em> the
     * storage: a derived class writes neither name and its values still have a dictionary, which the
     * attribute walk finds by going up. A built-in type answers where this runtime really does keep one —
     * {@code type}, whose values are classes and so have a namespace, {@code module} and {@code function},
     * whose attributes are dictionary entries, and the exception family, which carries {@code e.x = 1} — and
     * {@code object} itself answers where CPython does: the root has no dictionary, which is why {@code
     * object().__dict__} is the {@code AttributeError} it is. {@link GenericAttributes} asks the same
     * question before it writes a name it found nowhere on the class.
     */
    boolean hasValueDict() {
        return this == TYPE || this == MODULE || this == FUNCTION
                || matches(ExceptionTypes.BASE_EXCEPTION);
    }

    /**
     * CPython's {@code tp_weaklistoffset != 0}, the same question about the list of weak references whose
     * visible half is the {@code __weakref__} descriptor.
     *
     * <p>{@code type} is the one built-in here with such a list, which is why {@code class M(type)} files
     * neither name, and {@code ExceptionGroup} is the one exception with one — the reference interpreter
     * gives the list to that class alone, so a class under it files no {@code __weakref__} of its own
     * while a class under {@code Exception} still introduces the list for itself. A plain class keeps the
     * answer of whichever class in its order first introduced it, which {@link PyClass} stores.
     */
    boolean hasWeakRefList() {
        return this == TYPE || this == ExceptionTypes.EXCEPTION_GROUP;
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
            case "__module__" -> PyStr.of(moduleName());
            case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
            case "__base__" -> parent == null ? PyNone.INSTANCE : parent;
            case "__bases__" -> parent == null ? PyTuple.EMPTY : PyTuple.of(bases());
            case "__mro__" -> PyTuple.of(mro());
            case "__class__" -> type;
            case "__dict__" -> PyMappingProxy.of(namespace());
            default -> classMember(attribute);
        };
    }

    /**
     * The dictionary this type declares, as the one object a hundred reads of {@code __dict__} share.
     *
     * <p>Two sources, in this order: the members {@link TypeDict} keeps for the types the class system is
     * built on and for the exception family, then the {@link Members} table a built-in value class
     * compiles in. A type with neither — one of the iterators whose members this runtime does not have —
     * gets an empty dictionary, which is why {@code len(line_iterator.__dict__)} is shorter than
     * CPython's. {@link PyClass} answers this with the namespace its class statement filled.
     */
    PyDict namespace() {
        if (namespace == null) {
            namespace = TypeDict.declared(this);
        }
        return namespace;
    }

    /** The sentence this type answers {@code __doc__} with, or null where it declares none. */
    String docText() {
        return doc;
    }

    /**
     * The module a built-in type belongs to, which {@code type} takes from the name it is written down
     * as rather than from a dictionary.
     *
     * <p>A static type keeps no {@code __module__} of its own, so CPython's {@code type_module} splits
     * {@code tp_name} on its <em>last</em> dot and answers {@code builtins} where there is no dot to
     * split — which is what puts {@code datetime.date} in {@code datetime} and
     * {@code sys.version_info} in {@code sys}, however many dots the name carries before its tail.
     * A class Python wrote is not reached here: its own namespace holds the name, and
     * {@link PyClass#getAttr} asks that first.
     */
    String moduleName() {
        int dot = filedName.lastIndexOf('.');
        return dot < 0 ? "builtins" : filedName.substring(0, dot);
    }

    /**
     * The bases this type is declared over. One for every built-in but {@code ExceptionGroup}, which
     * CPython creates over {@code (BaseExceptionGroup, Exception)} and which therefore cannot be a parent
     * pointer twice.
     *
     * <p>Package-private because {@code __bases__} and {@code X.__subclasses__()} both read it: the first
     * answers a tuple of these, and the second asks which classes name this one among them.
     */
    PyType[] bases() {
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
                "cannot set '%s' attribute of immutable type '%s'", attribute, filedName);
    }

    /**
     * {@code del int.x}, which CPython refuses with the <em>setting</em> sentence: a delete is a set to
     * nothing and reaches the same gate at the top of {@code type.__setattr__}, before any name is looked
     * up. {@code del int.__class__} is what this slice found reading the wording back wrong.
     */
    @Override
    public PyObject delAttr(String attribute) {
        throw Slots.typeError(
                "cannot set '%s' attribute of immutable type '%s'", attribute, filedName);
    }

    /**
     * A built-in type takes every write itself, and {@code __class__} is no exception.
     *
     * <p>CPython's {@code type.__setattr__} refuses a write to a non-heap type before it looks the name up
     * anywhere, which is why {@code del int.__class__} complains about the immutable type rather than
     * about a name {@code object} answers for every other value. {@link PyClass} lifts this, because a
     * class Python built passes that gate and does reach the getset.
     */
    @Override
    boolean filedByItsClass(String name) {
        return true;
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

    /** A class is always callable: {@code tp_call} of {@code type} is {@code type_call}. */
    @Override
    public boolean isCallable() {
        return true;
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
        // CPython writes `<class 'module.name'>` for anything whose module is not `builtins`, and takes
        // `tp_name` for a built-in — the two answers are the same string for a named sequence, whose
        // module is the half of its written-down name before the dot.
        return "<class '" + filedName + "'>";
    }
}
