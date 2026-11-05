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
     *
     * <p>{@code object_doc} ({@code Objects/typeobject.c:7993}) is the one sentence here welded to the
     * shortest table there is: {@code object()} takes nothing, so the table is the two brackets and the
     * read of {@code __text_signature__} is nothing but them.
     */
    public static final PyType OBJECT =
            new PyType("object", null)
                    .documented(
                            "object()\n"
                                    + "--\n"
                                    + "\n"
                                    + "The base class of the class hierarchy.\n\nWhen called, it accepts no"
                                    + " arguments and returns a new featureless\ninstance that has no instance"
                                    + " attributes and cannot be given any.\n");

    public static final PyType TYPE =
            new PyType("type", OBJECT)
                    .documented(
                            "type(object) -> the object's type\n"
                                    + "type(name, bases, dict, **kwds) -> a new type");

    static {
        // Every type is an instance of `type`, including `type` itself and `object`.
        OBJECT.type = TYPE;
        TYPE.type = TYPE;
    }

    /**
     * The two sentences CPython's {@code long_doc} and {@code bool_doc} write for the number and its
     * two values.
     *
     * <p>Each is a plain string in the type's own dictionary, and each says what the constructor accepts
     * before it says anything else — but only one of the two is read as a table. {@code long_doc} opens with
     * two usage recipes, {@code int([x]) -> integer} and {@code int(x, base=10) -> integer}, and a recipe is
     * not a table: {@code find_signature} reaches the bracket after the name and {@code skip_signature} then
     * looks for the rule under it, which a recipe never writes, so {@code int.__text_signature__} is
     * {@code None} and {@code int.__doc__} is the whole text. {@code bool_doc}
     * ({@code Objects/boolobject.c:112}) writes its rule by hand — {@code bool(object=False, /)}, then the
     * brackets-dashes-blank-line rule, then the sentence — and so {@code bool} is one of the sixteen books
     * here whose table is read apart from its sentence. The reference's seventeenth name with a table is
     * {@code memoryview}, whose type this runtime has not built.
     *
     * <p>The integer's is transcribed exactly, including its last line: 3.14.8 breaks "integer literal"
     * across a newline and loses the {@code l}, and the dictionary holds that text, so a program that prints
     * {@code int.__doc__} reads the broken word here too. The bool's is the one type sentence here that says
     * a class cannot be inherited from, and {@code bool} files no {@code __init__} and no {@code __hash__} of
     * its own to go with it.
     */
    private static final String INT_DOC =
            "int([x]) -> integer\n"
                    + "int(x, base=10) -> integer\n"
                    + "\n"
                    + "Convert a number or string to an integer, or return 0 if no arguments\n"
                    + "are given.  If x is a number, return x.__int__().  For floating-point\n"
                    + "numbers, this truncates towards zero.\n"
                    + "\n"
                    + "If x is not a number or if base is given, then x must be a string,\n"
                    + "bytes, or bytearray instance representing an integer literal in the\n"
                    + "given base.  The literal can be preceded by '+' or '-' and be surrounded\n"
                    + "by whitespace.  The base defaults to 10.  Valid bases are 0 and 2-36.\n"
                    + "Base 0 means to interpret the base from the string as an integer\n"
                    + "iteral.\n"
                    + ">>> int('0b100', base=0)\n"
                    + "4";

    /** {@code bool_doc}'s table and sentence, the first of the six builtins that write their own. */
    private static final String BOOL_DOC =
            "bool(object=False, /)\n"
                    + "--\n"
                    + "\n"
                    + "Returns True when the argument is true, False otherwise.\n"
                    + "The builtins True and False are the only two instances of the class bool.\n"
                    + "The class bool is a subclass of the class int, and cannot be subclassed.";

    /**
     * The two halves {@code float_new__doc__} ({@code Objects/clinic/floatobject.c.h:209}) writes.
     *
     * <p>The name is a little off: what {@code PyFloat_Type} files as its {@code tp_doc} is not
     * {@code float_doc} but the constructor's own clinic text, whose {@code float(x=0, /)} line the type
     * reads as its {@code __text_signature__}. The sentence is shorter than {@code int}'s for a reason:
     * {@code float}'s constructor takes no base, so there is no literal grammar to describe, and it names
     * the two kinds it accepts rather than the number it returns.
     */
    private static final String FLOAT_DOC =
            "float(x=0, /)\n"
                    + "--\n"
                    + "\n"
                    + "Convert a string or number to a floating-point number, if possible.";

    public static final PyType NONE = builtin("NoneType");
    public static final PyType NOTIMP = builtin("NotImplementedType");
    public static final PyType ELLIPSIS = builtin("ellipsis");
    public static final PyType INT = builtin("int").flagged(MATCH_SELF).documented(INT_DOC);
    /**
     * {@code bool} is a subtype of {@code int}, not merely compatible with it:
     * {@code isinstance(True, int)} is true, and that is what lets {@code sum([True, 1])} and
     * {@code {True: 1, 1: 2}} behave as ints that print as {@code True}.
     */
    public static final PyType BOOL = subclass("bool", INT).documented(BOOL_DOC);
    public static final PyType FLOAT = builtin("float").flagged(MATCH_SELF).documented(FLOAT_DOC);

    /**
     * The two halves {@code complex_new__doc__} ({@code Objects/clinic/complexobject.c.h:93}) writes.
     *
     * <p>{@code tp_doc} holds the whole internal text, argument table and all, and {@code type} cuts the
     * table out before answering either name — the dictionary's {@code __doc__} and {@code complex.__doc__}
     * are both the sentence left behind. The four lines are the constructor's four shapes, in the order the
     * clinic's paragraph puts them, and unlike {@link #FLOAT_DOC} this one runs to a paragraph because the
     * two halves of {@code complex(real, imag)} need naming separately. It is also one of the five tables of
     * the sixteen that name no positional-only marker, so {@code complex(real=0, imag=0)} lets both names be
     * written as keywords.
     */
    private static final String COMPLEX_DOC =
            "complex(real=0, imag=0)\n"
                    + "--\n"
                    + "\n"
                    + "Create a complex number from a string or numbers.\n"
                    + "\n"
                    + "If a string is given, parse it as a complex number.\n"
                    + "If a single number is given, convert it to a complex number.\n"
                    + "If the 'real' or 'imag' arguments are given, create a complex number\n"
                    + "with the specified real and imaginary components.";

    public static final PyType COMPLEX = builtin("complex").documented(COMPLEX_DOC);

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

    /**
     * The two texts CPython files as {@code list___init____doc__} and {@code tuple_new__doc__}.
     *
     * <p>Neither type writes its own {@code tp_doc}: {@code PyList_Type} files the clinic text of
     * {@code list.__init__} ({@code Objects/clinic/listobject.c.h:398}) and {@code PyTuple_Type} that of
     * {@code tuple.__new__} ({@code Objects/clinic/tupleobject.c.h:75}), so the constructor's table is what
     * the type reads as its own {@code __text_signature__} — {@code (iterable=(), /)} for both, though the
     * two sentences disagree about what that one argument does. Neither names a constructor the way
     * {@link #STR_DOC} does: a list and a tuple say what they <em>are</em> — mutable, immutable — and then
     * the one rule about the argument, which is that it has to be an iterable. Each is the reason
     * {@code list.__doc__} and {@code tuple.__doc__} are not the {@code None} a value type with nothing to
     * say about itself answers.
     */
    private static final String LIST_DOC =
            "list(iterable=(), /)\n"
                    + "--\n"
                    + "\n"
                    + "Built-in mutable sequence.\n"
                    + "\n"
                    + "If no argument is given, the constructor creates a new empty list.\n"
                    + "The argument must be an iterable if specified.";

    private static final String TUPLE_DOC =
            "tuple(iterable=(), /)\n"
                    + "--\n"
                    + "\n"
                    + "Built-in immutable sequence.\n"
                    + "\n"
                    + "If no argument is given, the constructor returns an empty tuple.\n"
                    + "If iterable is specified the tuple is initialized from iterable's items.\n"
                    + "\n"
                    + "If the argument is a tuple, the return value is the same object.";

    /**
     * The sentence CPython's {@code dictionary_doc} writes for {@code dict}.
     *
     * <p>The one type sentence here that reads as a constructor's manual rather than as a description:
     * five lines, each naming one way the class is called, and the last of them an example. {@code
     * list.__doc__} and {@code tuple.__doc__} say what those types are; this says what {@code dict(...)}
     * does with what it is handed, which is why it is the longest of the four.
     */
    private static final String DICT_DOC =
            "dict() -> new empty dictionary\n"
                    + "dict(mapping) -> new dictionary initialized from a mapping object's\n"
                    + "    (key, value) pairs\n"
                    + "dict(iterable) -> new dictionary initialized as if via:\n"
                    + "    d = {}\n"
                    + "    for k, v in iterable:\n"
                    + "        d[k] = v\n"
                    + "dict(**kwargs) -> new dictionary initialized with the name=value pairs\n"
                    + "    in the keyword argument list.  For example:  dict(one=1, two=2)";

    public static final PyType STR = builtin("str").flagged(MATCH_SELF).documented(STR_DOC);
    public static final PyType BYTES = builtin("bytes").flagged(MATCH_SELF).documented(BYTES_DOC);
    public static final PyType TUPLE =
            builtin("tuple").flagged(SEQUENCE | MATCH_SELF).documented(TUPLE_DOC);
    public static final PyType LIST =
            builtin("list").flagged(SEQUENCE | MATCH_SELF).documented(LIST_DOC);
    /**
     * The two texts {@code set_doc} ({@code Objects/setobject.c:2602}) and {@code frozenset_doc} ({@code :2694})
     * write for the pair of unique values.
     *
     * <p>Each is one line once the table and the rule in front of it are cut away, and they are the only two
     * type sentences here that promise nothing about order: a set's order is an artefact of the table it is
     * stored in, which is why everything a program can check about one is a count, a membership or a content
     * comparison and never a position. Both are written by hand in the same file as the type rather than by
     * clinic, and both weld the same table — {@code set(iterable=(), /)} and
     * {@code frozenset(iterable=(), /)} — which is what {@code __text_signature__} answers.
     */
    private static final String SET_DOC =
            "set(iterable=(), /)\n--\n\nBuild an unordered collection of unique elements.";

    private static final String FROZENSET_DOC =
            "frozenset(iterable=(), /)\n"
                    + "--\n"
                    + "\n"
                    + "Build an immutable unordered collection of unique elements.";

    public static final PyType DICT =
            builtin("dict").flagged(MAPPING | MATCH_SELF).documented(DICT_DOC);
    public static final PyType SET =
            builtin("set").flagged(MATCH_SELF).documented(SET_DOC);
    public static final PyType FROZENSET =
            builtin("frozenset").flagged(MATCH_SELF).documented(FROZENSET_DOC);
    /**
     * The three windows a dict opens onto itself, which are not names in {@code builtins}.
     *
     * <p>{@code type({}.keys())} is the only way Python reaches them: CPython writes {@code
     * PyDictKeys_Type}, {@code PyDictValues_Type} and {@code PyDictItems_Type} (all three in {@code
     * Objects/dictobject.c}) with a dotless {@code tp_name} and registers none in the builtins table, so
     * each reads {@code builtins} as its module by the same rule that gives {@code mappingproxy} its name.
     * None of the three has a {@code tp_new}, and only the first and the last fill {@code tp_richcompare} —
     * which is what decides that two of them file the six comparison rows and a trailing {@code None} for
     * {@code __hash__} while {@code dict_values} files neither.
     */
    static final PyType DICT_KEYS = builtin("dict_keys").withoutNew();

    static final PyType DICT_VALUES = builtin("dict_values").withoutNew();
    static final PyType DICT_ITEMS = builtin("dict_items").withoutNew();
    /**
     * The one sentence CPython's {@code slice_doc} writes for the slicing type (Objects/sliceobject.c:340).
     *
     * <p>It is the only {@code tp_doc} here that lists two constructor shapes on their own lines and then
     * says nothing about behaviour, and it carries no argument table: the text opens with {@code slice(stop)},
     * which the signature rule reads as a table only to give up on when no {@code --} follows the closing
     * bracket. So the whole thing is the sentence, and {@code slice.__text_signature__} is {@code None}.
     */
    private static final String SLICE_DOC =
            "slice(stop)\nslice(start, stop[, step])\n\nCreate a slice object.\n\nThis is used for"
                    + " extended slicing (e.g. a[0:10:2]).";

    public static final PyType SLICE = builtin("slice").documented(SLICE_DOC);
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
    public static final PyType BUILTIN_FUNCTION =
            builtin("builtin_function_or_method").withoutNew();
    public static final PyType METHOD = builtin("method");
    /** What {@code str.upper} is: a method reached through its type rather than through a value. */
    public static final PyType METHOD_DESCRIPTOR = builtin("method_descriptor").withoutNew();
    /** What {@code sys.version_info.major} is: a field of a value, reached the same way. */
    public static final PyType MEMBER_DESCRIPTOR = builtin("member_descriptor").withoutNew();
    /** What {@code type.__call__} is: a slot of a built-in type, reached the same way. */
    static final PyType WRAPPER_DESCRIPTOR = builtin("wrapper_descriptor").withoutNew();
    /** What {@code type.__prepare__} is before a class reads it: a class method of a built-in type. */
    static final PyType CLASS_METHOD_DESCRIPTOR =
            builtin("classmethod_descriptor").withoutNew();
    /** What {@code BaseException.args} is: a pair of C functions reached as an attribute. */
    static final PyType GETSET_DESCRIPTOR = builtin("getset_descriptor").withoutNew();
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
    static final PyType METHOD_WRAPPER = builtin("method-wrapper").withoutNew();
    /**
     * The two sentences {@code staticmethod_doc} and {@code classmethod_doc} write for the wrappers.
     *
     * <p>Each opens with the three lines clinic would have generated for it — name, one argument marked
     * positional-only, rule under it — but no clinic wrote these: {@code funcobject.c} hand-writes the table
     * above the prose, and that is the whole reason {@code staticmethod.__text_signature__} answers {@code
     * (function, /)} where a type whose sentence this file transcribes without one answers {@code None}.
     * {@code _PyType_DocWithoutSignature} cuts the three away on the way into {@code __doc__}, so what a
     * program reads is the prose the docs teach with, and the row the dictionary files is that same text
     * rather than a member over a field, as {@code property} files its. The two end by pointing at each
     * other — {@code staticmethod} at {@code classmethod}, {@code classmethod} at {@code staticmethod} —
     * which is how a person reading {@code help()} finds the one they meant.
     */
    private static final String STATICMETHOD_DOC =
            "staticmethod(function, /)\n"
                    + "--\n"
                    + "\n"
                    + "Convert a function to be a static method.\n\n"
                    + "A static method does not receive an implicit first argument.\n"
                    + "To declare a static method, use this idiom:\n\n     class C:\n"
                    + "         @staticmethod\n         def f(arg1, arg2, argN):\n"
                    + "             ...\n\n"
                    + "It can be called either on the class (e.g. C.f()) or on an instance\n"
                    + "(e.g. C().f()). Both the class and the instance are ignored, and\n"
                    + "neither is passed implicitly as the first argument to the method.\n\n"
                    + "Static methods in Python are similar to those found in Java or C++.\n"
                    + "For a more advanced concept, see the classmethod builtin.";

    private static final String CLASSMETHOD_DOC =
            "classmethod(function, /)\n"
                    + "--\n"
                    + "\n"
                    + "Convert a function to be a class method.\n\n"
                    + "A class method receives the class as implicit first argument,\n"
                    + "just like an instance method receives the instance.\n"
                    + "To declare a class method, use this idiom:\n\n  class C:\n"
                    + "      @classmethod\n      def f(cls, arg1, arg2, argN):\n          ...\n\n"
                    + "It can be called either on the class (e.g. C.f()) or on an instance\n"
                    + "(e.g. C().f()).  The instance is ignored except for its class.\n"
                    + "If a class method is called for a derived class, the derived class\n"
                    + "object is passed as the implied first argument.\n\n"
                    + "Class methods are different than C++ or Java static methods.\n"
                    + "If you want those, see the staticmethod builtin.";

    /**
     * The three members that are nothing but the descriptor protocol, each its own type.
     *
     * <p>{@code property}'s sentence is not written beside its {@code PyTypeObject}, the way the two wrappers
     * above write theirs: it is {@code property_init}'s clinic text, whose first three lines are the argument
     * table and the rule under it, and here it is stored whole — the 805 characters the source holds, of
     * which {@code _PyType_DocWithoutSignature} cuts 55 off the front before anything reads them. That is why
     * the value a program reads is the 750 below the table, why {@code __text_signature__} is the 43 bracket
     * characters with the name cut off too, and why the row named {@code __doc__} in the dictionary is a
     * member over the property's own field, with nothing to do with the class's.
     */
    private static final String PROPERTY_DOC =
            "property(fget=None, fset=None, fdel=None, doc=None)\n"
                    + "--\n"
                    + "\n"
                    + "Property attribute.\n"
                    + "\n"
                    + "  fget\n"
                    + "    function to be used for getting an attribute value\n"
                    + "  fset\n"
                    + "    function to be used for setting an attribute value\n"
                    + "  fdel\n"
                    + "    function to be used for del'ing an attribute\n"
                    + "  doc\n"
                    + "    docstring\n"
                    + "\n"
                    + "Typical use is to define a managed attribute x:\n"
                    + "\n"
                    + "class C(object):\n"
                    + "    def getx(self): return self._x\n"
                    + "    def setx(self, value): self._x = value\n"
                    + "    def delx(self): del self._x\n"
                    + "    x = property(getx, setx, delx, \"I'm the 'x' property.\")\n"
                    + "\n"
                    + "Decorators make defining new properties or modifying existing ones easy:\n"
                    + "\n"
                    + "class C(object):\n"
                    + "    @property\n"
                    + "    def x(self):\n"
                    + "        \"I am the 'x' property.\"\n"
                    + "        return self._x\n"
                    + "    @x.setter\n"
                    + "    def x(self, value):\n"
                    + "        self._x = value\n"
                    + "    @x.deleter\n"
                    + "    def x(self):\n"
                    + "        del self._x";

    public static final PyType PROPERTY = builtin("property").documented(PROPERTY_DOC);
    public static final PyType CLASS_METHOD =
            builtin("classmethod").documented(CLASSMETHOD_DOC);
    public static final PyType STATIC_METHOD =
            builtin("staticmethod").documented(STATICMETHOD_DOC);
    public static final PyType MODULE = builtin("module");
    public static final PyType CELL = builtin("cell");
    public static final PyType ITERATOR = builtin("iterator");
    /**
     * What {@code list[int]} is: the one value of a parameterized built-in type this runtime makes.
     *
     * <p>It is filed under {@code types} rather than {@code builtins}, which is what CPython's
     * {@code GaObject} with its {@code tp_name = "types.GenericAlias"} gives it: Python reads the last
     * half as {@code __name__} and the first as {@code __module__}, while a message about the value
     * quotes the name in full. Nothing here gives a program a route to the type itself — {@code types} is
     * not imported yet — so the only way to see it is the way the subscripting built-in types answer.
     */
    public static final PyType GENERIC_ALIAS =
            builtin("GenericAlias").filedUnder("types.GenericAlias");

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
    /**
     * The sentence CPython's {@code range_doc} writes for the sequence type (Objects/rangeobject.c:159).
     *
     * <p>It lists two constructor shapes on their own lines and then explains the arithmetic in eight lines
     * all, and like {@link #SLICE_DOC} it carries no argument table: the signature rule reads the first line
     * as a table only to give up when no {@code --} follows it, so {@code range.__text_signature__} is {@code
     * None} and {@code range.__doc__} keeps the whole thing — the longest {@code tp_doc} of the types here,
     * and the only one that says what its values produce rather than what they are.
     */
    private static final String RANGE_DOC =
            "range(stop) -> range object\n"
                    + "range(start, stop[, step]) -> range object\n"
                    + "\n"
                    + "Return an object that produces a sequence of integers from start (inclusive)\n"
                    + "to stop (exclusive) by step.  range(i, j) produces i, i+1, i+2, ..., j-1.\n"
                    + "start defaults to 0, and stop is omitted!  range(4) produces 0, 1, 2, 3.\n"
                    + "These are exactly the valid indices for a list of 4 elements.\n"
                    + "When step is given, it specifies the increment (or decrement).";

    public static final PyType RANGE = builtin("range").documented(RANGE_DOC).flagged(SEQUENCE);

    /**
     * The cursor a range hands out when its three bounds and its length all fit a C {@code long}.
     *
     * <p>Two types where one would do, and CPython's reason is arithmetic rather than meaning: {@code
     * range_iter} keeps {@code start}, {@code step} and {@code len} as C longs and steps them, which is
     * the faster walk, and falls back to {@link #LONG_RANGE_ITERATOR} only when a bound, a step, or the
     * count that would come off them does not fit. Both file the same six names, and their two bodies
     * disagree in one place a program can see: the fast one reads its new index as {@code PyLong_AsLong},
     * so it takes a bool, an {@code __index__}, and overflows at sixty-four bits, while the wide one
     * demands an exact int and clips in arbitrary precision.
     */
    static final PyType RANGE_ITERATOR = builtin("range_iterator").withoutNew();

    /** The cursor beside it, which counts in Python integers and so never runs out of width. */
    static final PyType LONG_RANGE_ITERATOR = builtin("longrange_iterator").withoutNew();

    /**
     * The six cursors the four sequence types hand out, each named for the type it walks.
     *
     * <p>CPython writes them in the four C files that need them — {@code listobject.c} two, {@code
     * tupleobject.c}, {@code bytesobject.c} and {@code unicodeobject.c} one each, the last of those two —
     * and the six dictionaries are the same six names in the same order, because each {@code Xiter_methods}
     * list files the same three {@code PyMethodDef} rows over an {@code it_index} and an {@code it_seq}.
     * Three of the six differ from the other three in one thing a program can see: the list cursors read
     * the list as it is now, so an item appended during the walk is walked into, while the other three hold
     * a source that cannot change and so report the position they ran out at rather than a spent one.
     *
     * <p>Two of them walk a {@code str}, and the pair is CPython's {@code unicode_iter} asking the string
     * whether it is ASCII: the answer is a different type rather than a different cursor, because the two
     * {@code tp_iternext}s read the same buffer at different widths and only one of them can hand back a
     * shared one-character value.
     */
    static final PyType LIST_ITERATOR = builtin("list_iterator").withoutNew();

    /** The same list, walked from its last item back to its first — what {@code reversed(list)} answers. */
    static final PyType LIST_REVERSE_ITERATOR = builtin("list_reverseiterator").withoutNew();

    /** {@code type(iter(()))}, which keeps its index when it runs out and drops only the tuple. */
    static final PyType TUPLE_ITERATOR = builtin("tuple_iterator").withoutNew();

    /** {@code type(iter(b''))}, whose items are the numbers of the octets. */
    static final PyType BYTES_ITERATOR = builtin("bytes_iterator").withoutNew();

    /** The cursor of a string with a code point at or above 128 in it. */
    static final PyType STR_ITERATOR = builtin("str_iterator").withoutNew();

    /** The cursor beside it, for a string whose every code point is under 128. */
    static final PyType STR_ASCII_ITERATOR = builtin("str_ascii_iterator").withoutNew();

    /**
     * The seven cursors the two hash tables hand out.
     *
     * <p>One struct and one method table cover all seven: {@code dictiter_methods} ({@code
     * Objects/dictobject.c:5211}) is filed by each of the six dict cursors and {@code setiter_methods}
     * ({@code Objects/setobject.c:895}) by the set's, and both are two {@code PyMethodDef} rows — the
     * estimate and the pickle, with no {@code __setstate__} beside them. The six dict types differ only in
     * what their {@code tp_iternext} hands back and in which end they start at; the one set type is handed
     * out by {@code set} and {@code frozenset} alike, and by {@code mappingproxy} for the keys.
     *
     * <p>None of them can be made from Python. {@code tp_new} is null in all seven, and {@code tp_alloc}
     * is {@code Py_GenericAlloc}, which is the pair that writes {@code cannot create 'X' instances}.
     */
    static final PyType DICT_KEY_ITERATOR = builtin("dict_keyiterator").withoutNew();

    /** {@code type(iter(d.values()))}, which hands back the value at the position it reached. */
    static final PyType DICT_VALUE_ITERATOR = builtin("dict_valueiterator").withoutNew();

    /** {@code type(iter(d.items()))}, the one cursor that builds a pair as it steps. */
    static final PyType DICT_ITEM_ITERATOR = builtin("dict_itemiterator").withoutNew();

    /** The three cursors that walk a dict from the last entry to the first. */
    static final PyType DICT_REVERSE_KEY_ITERATOR =
            builtin("dict_reversekeyiterator").withoutNew();

    static final PyType DICT_REVERSE_VALUE_ITERATOR =
            builtin("dict_reversevalueiterator").withoutNew();

    static final PyType DICT_REVERSE_ITEM_ITERATOR =
            builtin("dict_reverseitemiterator").withoutNew();

    /** The cursor {@code set} and {@code frozenset} share, which is the one that names no table. */
    static final PyType SET_ITERATOR = builtin("set_iterator").withoutNew();

    /**
     * {@code reversed_new__doc__}: what the constructor says of itself, filed as the type's {@code tp_doc}.
     *
     * <p>The argument table is kept welded to the front of the sentence because {@code type_get_doc} and
     * {@code type_get_text_signature} read the two halves out of one text: {@code reversed.__doc__} is the
     * sentence with the table cut off it, and {@code reversed.__text_signature__} is nothing but the table.
     * Of the sixteen cursor books of the walking family this is the one {@code tp_doc} that carries a table,
     * and the one that carries anything at all; the four cursors a builtin call makes below carry theirs too.
     */
    private static final String REVERSED_DOC =
            "reversed(sequence, /)\n--\n\nReturn a reverse iterator over the values of the given sequence.";

    /**
     * {@code reversed} itself: the name in {@code builtins} that is a type rather than a function, and the
     * only cursor a <em>source</em> hands out that a program can make for itself.
     *
     * <p>The fifteen cursor types that walk a source are what their sources answer, and none of them can be
     * built from Python — {@code tp_new} is null in every one, and each refuses its own name with {@code
     * cannot create '%s' instances}. This one has a {@code tp_new} because the call that
     * makes it <em>is</em> the builtin: {@code reversed_vectorcall} ({@code Objects/enumobject.c:424}) is
     * the type's own vectorcall, so {@code reversed(x)} and {@code reversed.__new__(reversed, x)} are two
     * spellings of one constructor, and the {@code __reversed__} question and the sequence test are both
     * asked inside it ({@code :380}). That is also why the cursor is one of the five books of twenty that
     * report a sentence of its own — {@code tp_doc} is {@code reversed_new__doc__}, the constructor's — and
     * why its dictionary has seven names, the most of any cursor: the six a sequence cursor files with the
     * constructor on top, and two more than the five a hash cursor files.
     *
     * <p>It is a cursor only for what has no cursor: a list, a range, a dict, a view and a window each answer
     * {@code __reversed__} with a type of their own, and what this one makes is the walk for a tuple, a
     * string, an octet run and any class that subscripts by position, which read their source at every step
     * rather than a copy of it.
     */
    public static final PyType REVERSED = builtin("reversed").documented(REVERSED_DOC);

    /**
     * The four sentences the other cursor builtins write, each with its argument table welded to the front.
     *
     * <p>{@code enum_new__doc__} comes from {@code enumobject.c} beside the one above; the three others are
     * {@code zip_doc}, {@code map_doc} and {@code filter_doc}, and all four are filed as {@code tp_doc} of
     * their own type because all four builtins <em>are</em> their types. {@code PyNativeFunction}
     * transcribed the same texts while these names were still functions here, and a type reads the two halves
     * apart: {@code __doc__} is the sentence with the table cut off it and {@code __text_signature__} is
     * nothing but the table.
     */
    private static final String ENUMERATE_DOC =
            "enumerate(iterable, start=0)\n"
                    + "--\n"
                    + "\n"
                    + "Return an enumerate object.\n"
                    + "\n"
                    + "  iterable\n"
                    + "    an object supporting iteration\n"
                    + "\n"
                    + "The enumerate object yields pairs containing a count (from start, which\n"
                    + "defaults to zero) and a value yielded by the iterable argument.\n"
                    + "\n"
                    + "enumerate is useful for obtaining an indexed list:\n"
                    + "    (0, seq[0]), (1, seq[1]), (2, seq[2]), ...";

    private static final String ZIP_DOC =
            "zip(*iterables, strict=False)\n"
                    + "--\n"
                    + "\n"
                    + "The zip object yields n-length tuples, where n is the number of\n"
                    + "iterables passed as positional arguments to zip().  The i-th element\n"
                    + "in every tuple comes from the i-th iterable argument to zip().  This\n"
                    + "continues until the shortest argument is exhausted.\n"
                    + "\n"
                    + "If strict is true and one of the arguments is exhausted before the\n"
                    + "others, raise a ValueError.\n"
                    + "\n"
                    + "   >>> list(zip('abcdefg', range(3), range(4)))\n"
                    + "   [('a', 0, 0), ('b', 1, 1), ('c', 2, 2)]";

    private static final String MAP_DOC =
            "map(function, iterable, /, *iterables, strict=False)\n"
                    + "--\n"
                    + "\n"
                    + "Make an iterator that computes the function using arguments from\n"
                    + "each of the iterables.  Stops when the shortest iterable is exhausted.\n"
                    + "\n"
                    + "If strict is true and one of the arguments is exhausted before the\n"
                    + "others, raise a ValueError.";

    private static final String FILTER_DOC =
            "filter(function, iterable, /)\n"
                    + "--\n"
                    + "\n"
                    + "Return an iterator yielding those items of iterable for which\n"
                    + "function(item) is true.  If function is None, return the items that\n"
                    + "are true.";

    /**
     * {@code super_doc} ({@code Objects/typeobject.c}): fourteen lines and a final newline, and the eighth
     * book of the family that opens like a table and is not one.
     *
     * <p>The text starts with the name and a bracket, so {@code find_signature} hits it on the first
     * character — and then walks fourteen lines looking for the {@code )\n--\n\n} that never comes, because
     * what follows the recipe is prose and sample code, not a rule. {@code skip_signature} gives up, so
     * {@code __text_signature__} answers {@code None} and {@code __doc__} answers all four hundred and
     * seventy-one characters, indents and the trailing newline included. Two of the lines are indented by
     * four because the sentence they finish breaks there, and the last bracket pair on line three is
     * {@code isinstance(obj, type)}'s, not a signature's — which is the one way this text could have
     * looked like a table and does not.
     */
    private static final String SUPER_DOC =
            "super() -> same as super(__class__, <first argument>)\n"
                    + "super(type) -> unbound super object\n"
                    + "super(type, obj) -> bound super object; requires isinstance(obj, type)\n"
                    + "super(type, type2) -> bound super object; requires\n"
                    + "    issubclass(type2, type)\n"
                    + "Typical use to call a cooperative superclass method:\n"
                    + "class C(B):\n"
                    + "    def meth(self, arg):\n"
                    + "        super().meth(arg)\n"
                    + "This works for class methods too:\n"
                    + "class C(B):\n"
                    + "    @classmethod\n"
                    + "    def cmeth(cls, arg):\n"
                    + "        super().cmeth(arg)\n";

    /**
     * The four cursor builtins that are types, which is what makes their names askable.
     *
     * <p>{@code _PyBuiltin_Init} ({@code Python/bltinmodule.c:3434}) files {@code enumerate}, {@code filter},
     * {@code map} and {@code zip} with {@code SETBUILTIN} and the type rather than with a function, so {@code
     * type(enumerate("ab")) is enumerate} holds and a book of five or six names stands behind the value.
     * Every one of the four files the same five rows — {@code __new__}, the two slot copies, the pickle and
     * the constructor's own sentence — plus one name apiece for what only this cursor can carry: {@code
     * enumerate} is the generic of the family and files {@code __class_getitem__}, while {@code zip} and
     * {@code map} hold a flag no argument list can reach and file {@code __setstate__}. {@code filter} has
     * neither, so its book is the five and {@code dir()} is twenty-six against the twenty-seven of the other
     * three and the twenty-four of {@code object}. Of those five rows {@code object} files three itself, so
     * what a book gains over its twenty-four names is the two slots and, for the first three, that one name
     * apiece.
     *
     * <p>Every one of the four has a {@code tp_new}, and three of them a hand-written {@code tp_vectorcall}
     * beside it, which is why {@code map} counts its arguments in words no clinic table wrote while {@code
     * zip}, which has no vectorcall at all, reaches the same {@code zip_new} whichever spelling the call used.
     * {@code enumerate} is the one where the two paths disagree: see {@link Enumerate#calling}.
     */
    public static final PyType ENUMERATE = builtin("enumerate").documented(ENUMERATE_DOC);

    static final PyType ZIP = builtin("zip").documented(ZIP_DOC);
    static final PyType MAP = builtin("map").documented(MAP_DOC);
    static final PyType FILTER = builtin("filter").documented(FILTER_DOC);

    /**
     * {@code super} is a type although nothing it hands out is one: a proxy holds a class, a value and
     * the order measured against that value, and reads names out of the classes after the class it was
     * given. It is named here because {@link Interpreter} compares a loaded {@code super} against it by
     * identity, exactly as CPython compares a pointer against {@code &PySuper_Type}.
     */
    public static final PyType SUPER = builtin("super").documented(SUPER_DOC);

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

    /** Whether calling this type is refused the way an empty {@code tp_new} is; see {@link #withoutNew}. */
    private boolean withoutInstances;

    /** The bits above that this type declares for itself; see {@link #hasFeature}. */
    private int features;

    /**
     * The sentence this type answers {@code __doc__} with, or null where it declares none.
     *
     * <p>CPython keeps this text in the type's own dictionary as the {@code __doc__} entry of a static
     * type, which is what makes {@code BaseExceptionGroup.__doc__} a sentence while {@code
     * ExceptionGroup.__doc__} is {@code None} — the second type declares no doc of its own, and
     * {@code type_get_doc} answers {@code None} rather than walking up to the first one it can find. A class
     * Python wrote is still answered from its namespace, before this field, but the field holds a copy of
     * what its body said: {@code type_get_text_signature} reads it for every type, heap or not, which is how
     * a class whose docstring opens with an argument table reports that table while {@code __doc__} and the
     * dictionary keep the string whole.
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
        this.name = name;
        this.filedName = name;
        this.parent = parent;
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
        PyType type = new PyType(tailOf(filedName), TUPLE).withoutNew();
        type.filedName = filedName;
        type.type = TYPE;
        return type;
    }

    /** The half of a dotted name after the last dot, which is what Python calls the type. */
    private static String tailOf(String filedName) {
        int dot = filedName.lastIndexOf('.');
        return dot < 0 ? filedName : filedName.substring(dot + 1);
    }

    /** A built-in type named for its own parent, which is how an exception class and a dict view arrive. */
    static PyType declare(String name, PyType parent) {
        return subclass(name, parent);
    }

    /**
     * The type's own doc, declared where the type is.
     *
     * <p>A built-in type's sentence is written down beside its declaration, and a class Python wrote is
     * copied here once, at the build: {@code type_new_impl} keeps the {@code __doc__} its body collected when
     * that name holds a string and nothing at all when it holds anything else, and {@code type_set_doc} never
     * writes the field again. That is why {@code C.__doc__ = text} moves the namespace and the read of the
     * name but not the table {@link TypeDict} cuts out of this field.
     */
    PyType documented(String text) {
        doc = text;
        return this;
    }

    /**
     * The dotted name CPython writes a built-in down with when it is not one of the {@code builtins}.
     *
     * <p>{@code types.GenericAlias} is the only one here so far: the type's bare name is what Python reads
     * as {@code __name__}, and the half before the dot is what it reads as {@code __module__} — which is
     * how {@code type(list[int])} can be named correctly without the {@code types} module existing.
     */
    PyType filedUnder(String dotted) {
        filedName = dotted;
        return this;
    }

    /**
     * {@code Py_TPFLAGS_DISALLOW_INSTANTIATION}: this type's {@code tp_new} is a null pointer.
     *
     * <p>A type declared this way exists only as what another class's work hands out — {@code
     * sys.version_info}, the three windows a dict opens, and the seven kinds the class system is made of,
     * from {@code builtin_function_or_method} and {@code method-wrapper} down to the five descriptors. The
     * refusal comes before any argument is read, and every route reaches it from the same slot: {@code
     * type_call} looks at the slot before it looks at the arguments, and {@code object_new} and {@code
     * tp_new_wrapper} look at it for the class they were handed — which is how {@code dict_keys()}, {@code
     * object.__new__(dict_keys)} and {@code dict_keys.__new__(dict_keys)} all say {@code cannot create
     * 'dict_keys' instances}.
     */
    PyType withoutNew() {
        withoutInstances = true;
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
     *
     * <p>The two wrappers join the four because {@code funcobject.c} gives both a {@code tp_dictoffset},
     * which is where {@code functools_wraps} files the wrapped callable's {@code __module__}, {@code
     * __name__}, {@code __qualname__} and {@code __doc__} as the wrapper's own — the four names {@code
     * staticmethod(f).__dict__} holds after one call of {@code sm_init}. {@code property} is the third of
     * the family and keeps none: its struct has no dictionary, which is why {@code p.__dict__} is the
     * {@code AttributeError} it is while {@code p.fget} is a field.
     */
    boolean hasValueDict() {
        return this == TYPE
                || this == MODULE
                || this == FUNCTION
                || this == STATIC_METHOD
                || this == CLASS_METHOD
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
            case "__doc__" -> TypeDict.docOf(this);
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
                ? Slots.DECLINED
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
        if (declared != Slots.DECLINED) {
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
     * subscriptable}, which is what CPython says for {@code int[int]}. The two built-in families that do
     * answer — {@code list[int]} and {@code tuple[int, str]} — file a {@code __class_getitem__} and hand
     * back a {@link PyGenericAlias}; {@code type[int]} is answered in CPython by a {@code mp_subscript} of
     * {@code type} itself, which is not a name its dictionary files, and so still refuses here.
     */
    @Override
    public PyObject getItem(PyObject key) {
        PyObject fromMeta = Specials.invoke(this, "__getitem__", key);
        if (fromMeta != Slots.DECLINED) {
            return fromMeta;
        }
        PyObject generic = getAttr("__class_getitem__");
        return generic == Slots.DECLINED
                ? Slots.DECLINED
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
