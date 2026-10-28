package org.python.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The members the built-ins the class system is built on keep in their own dictionaries: {@code type}'s
 * {@code __new__}, {@code __init__}, {@code __call__}, {@code mro}, {@code __prepare__} and the two
 * instance tests, {@code object}'s {@code __init_subclass__}, {@code BaseException}'s two halves of
 * making a value plus {@code with_traceback} and {@code add_note}, and {@code BaseExceptionGroup}'s two
 * halves plus the three methods that work over the children it carries.
 *
 * <p>These are the names a metaclass either calls through {@code super()} or has inherited without
 * writing, so they have to be findable the way a class dictionary is findable — by {@link
 * Specials#member} and by {@link PySuper}, in the order a class's bases are walked. They are kept apart
 * from {@link Members} for two reasons. No {@code Members} table declares a double-underscore name at
 * all, since a dunder is a Java slot on {@link PyObject} there; and giving {@code object} a full table
 * would put these names in front of every value lookup in the runtime, which is not what CPython does —
 * {@code (3).bit_length} walks {@code int}'s table, while {@code 3 .__init_subclass__} never gets that
 * far.
 *
 * <p>The exception family's three are what make {@code class E(Exception)} a raisable class: {@code E}
 * inherits {@code __new__} and {@code __init__} the way CPython passes them down, so
 * {@code super().__init__("boom")} inside an {@code E.__init__} reaches one that exists and takes over
 * the arguments the creator already stored.
 *
 * <p>The group family's five are the same inheritance one level down, and they are what {@code except*}
 * works over: {@code split} is the method the driver calls when a clause matches part of a group, and
 * {@code derive} is the one every part of a split is built with — which is why a subset of children comes
 * back as an {@code ExceptionGroup} however the group it came from was classed.
 *
 * <p>CPython spells these names four different ways, and the difference is visible from Python. A
 * {@code wrapper_descriptor} ({@code __init__}, {@code __call__}) and a {@code method_descriptor}
 * ({@code mro}, {@code __instancecheck__}) answer themselves when a class whose order holds them is
 * asked, and bind to that class when only its metatype has them — which is why {@code Meta.mro()}
 * complains about an unbound method while {@code Plain.mro()} hands back an order. A
 * {@code classmethod_descriptor} ({@code __prepare__}, {@code __init_subclass__}) is never unbound and
 * binds the class the lookup ran on rather than any value, so {@code Plain.__prepare__.__self__} is
 * {@code type} while {@code Meta.__prepare__.__self__} is {@code Meta}. And {@code __new__} is a plain
 * function object sitting in the dictionary, which is what makes {@code Meta.__new__ is type.__new__}
 * hold while {@code Meta.mro is type.mro} does not.
 *
 * <p>Three details of CPython's own are not modelled. There is no {@code tp_new_wrapper} check that a
 * subtype's {@code __new__} slot still belongs to the type it derives from, so {@code type.__new__} of
 * an odd metatype is accepted rather than refused as "not safe"; {@code object} keeps only {@code
 * __init_subclass__} here, so a class that declares no {@code __new__} or {@code __init__} reads the
 * metatype's rather than {@code object}'s; and a bound wrapper prints the type's own name where CPython
 * prints the address of the object it bound to.
 */
final class TypeDict {

    /**
     * The two sentences CPython writes once and lets every class inherit.
     *
     * <p>{@code object.__new__} and {@code object.__init__} are the {@code ml_doc} of the builtins that
     * {@code BaseException}, {@code BaseExceptionGroup} and {@code type} all hand down, so
     * {@code BaseExceptionGroup.__new__.__doc__} is the first of these two and
     * {@code BaseExceptionGroup.__init__.__doc__} the second — the class the read came from never
     * changes the text, only which class {@code __qualname__} names.
     */
    private static final String NEW_DOC =
            "Create and return a new object.  See help(type) for accurate signature.";

    private static final String INIT_DOC =
            "Initialize self.  See help(type(self)) for accurate signature.";

    /**
     * The sentences CPython's {@code object.c} and {@code type.c} write next to the methods they declare.
     *
     * <p>Each is the {@code ml_doc} of one {@code PyMethodDef}, so it is read off the descriptor and stays
     * the same however the method is reached — bound to a class, bound to a value, or called unbound
     * through {@code X.__dict__['name']}. {@link #SUBCLASS_HOOK_DOC} is the one that runs to a paragraph
     * because {@code __subclasshook__} is a protocol a written class is meant to implement.
     */
    private static final String SUBCLASS_HOOK_DOC =
            "Abstract classes can override this to customize issubclass().\n\nThis is invoked early on by"
                    + " abc.ABCMeta.__subclasscheck__().\nIt should return True, False or NotImplemented."
                    + "  If it returns\nNotImplemented, the normal algorithm is used.  Otherwise, it\n"
                    + "overrides the normal algorithm (and the outcome is cached).\n";

    private static final String FORMAT_DOC =
            "Default object formatter.\n\nReturn str(self) if format_spec is empty. Raise TypeError"
                    + " otherwise.";

    private static final String SIZEOF_DOC = "Size of object in memory, in bytes.";

    private static final String SUBCLASSES_DOC = "Return a list of immediate subclasses.";

    private static final String DIR_DOC = "Default dir() implementation.";

    private static final String TYPE_DIR_DOC = "Specialized __dir__ implementation for types.";

    private static final String TYPE_SIZEOF_DOC = "Return memory consumption of the type object.";

    private static final String PICKLE_DOC = "Helper for pickle.";

    /** The four sentences {@code mappingproxy_methods} writes over the mapping underneath a window. */
    private static final String GET_DOC =
            "Return the value for key if key is in the mapping, else default.";

    private static final String KEYS_DOC =
            "D.keys() -> a set-like object providing a view on D's keys";

    private static final String VALUES_DOC =
            "D.values() -> an object providing a view on D's values";

    private static final String ITEMS_DOC =
            "D.items() -> a set-like object providing a view on D's items";

    private static final String COPY_DOC = "D.copy() -> a shallow copy of D";

    private static final String REVERSED_DOC = "D.__reversed__() -> reverse iterator";

    private static final String PROXY_CLASS_GETITEM_DOC =
            "mappingproxy objects are generic over two types, signifying (respectively) the types of"
                    + " their keys and values";

    /** {@code object.__class__}'s own sentence, which is the only {@code PyGetSetDef} doc here. */
    private static final String CLASS_DOC = "the object's class";

    /** The slot wrappers' one-line docs, which {@code object.c} and {@code type.c} share verbatim. */
    private static final String REPR_DOC = "Return repr(self).";

    private static final String HASH_DOC = "Return hash(self).";
    private static final String STR_DOC = "Return str(self).";
    private static final String GETATTR_DOC = "Return getattr(self, name).";
    private static final String SETATTR_DOC = "Implement setattr(self, name, value).";
    private static final String DELATTR_DOC = "Implement delattr(self, name).";
    private static final String ITER_DOC = "Implement iter(self).";
    private static final String LEN_DOC = "Return len(self).";
    private static final String GETITEM_DOC = "Return self[key].";
    private static final String CONTAINS_DOC = "Return bool(key in self).";
    private static final String LT_DOC = "Return self<value.";
    private static final String LE_DOC = "Return self<=value.";
    private static final String EQ_DOC = "Return self==value.";
    private static final String NE_DOC = "Return self!=value.";
    private static final String GT_DOC = "Return self>value.";
    private static final String GE_DOC = "Return self>=value.";
    private static final String OR_DOC = "Return self|value.";
    private static final String ROR_DOC = "Return value|self.";
    private static final String IOR_DOC = "Return self|=value.";

    /**
     * The six sentences the {@code PyNumberMethods} half of a set's struct carries.
     *
     * <p>{@code set} and {@code frozenset} are the only types here whose operators are number slots rather
     * than sequence or mapping ones, which is why a set's dictionary has {@code __sub__} where a list's has
     * {@code __add__} — {@code -} is {@code nb_subtract} for a set and nothing at all for a list. The four
     * in-place slots say {@code Return} where {@code list}'s {@code __iadd__} says {@code Implement}: the
     * sentence comes from the slot's own row in {@code slotdefs}, and only the sequence protocol's
     * {@code sq_inplace_*} rows were worded that way.
     */
    private static final String SUB_DOC = "Return self-value.";

    private static final String RSUB_DOC = "Return value-self.";
    private static final String AND_DOC = "Return self&value.";
    private static final String RAND_DOC = "Return value&self.";
    private static final String XOR_DOC = "Return self^value.";
    private static final String RXOR_DOC = "Return value^self.";
    private static final String ISUB_DOC = "Return self-=value.";
    private static final String IAND_DOC = "Return self&=value.";
    private static final String IXOR_DOC = "Return self^=value.";

    /**
     * The sentences a value type's own slots carry, which {@code object} has none of.
     *
     * <p>Each is the {@code docstring} of one {@code PySlotDef} in the type's {@code tp_as_number} or
     * {@code tp_as_sequence}, so {@code bytes.__dict__['__mod__'].__doc__} says what the {@code %} of an
     * octet run does and not what the {@code %} of anything else does. The three sentences a value type
     * declares as a {@code PyMethodDef} — {@code bytes.__bytes__}, {@code str.__format__} and {@code
     * str.__sizeof__} — are not here: they belong to the table of the type that declares the method, and
     * are written beside its body in {@link PyBytes} and {@link PyStr}.
     */
    private static final String MOD_DOC = "Return self%value.";

    private static final String RMOD_DOC = "Return value%self.";
    private static final String ADD_DOC = "Return self+value.";
    private static final String MUL_DOC = "Return self*value.";
    private static final String RMUL_DOC = "Return value*self.";

    private static final String BUFFER_DOC =
            "Return a buffer object that exposes the underlying memory of the object.";

    /**
     * The twenty-five sentences {@code longobject.c}'s {@code PyNumberMethods} carries.
     *
     * <p>Every one is the {@code docstring} of a {@code PySlotDef} row, so the phrase names the operator and
     * nothing else: {@code int} and {@code bool} are the only types here whose number slots are worth filing,
     * and {@code bool}'s three are the same rows worded the same way. The five that are not of the "Return
     * self<em>op</em>value" shape are the unary ones — {@code -self}, {@code +self}, {@code abs(self)},
     * {@code ~self} and {@code True if self else False} — which {@code slotdefs.py} writes without a
     * {@code Return} because the slot answers the receiver rather than a pair of them.
     *
     * <p>{@code __index__}'s is the longest sentence any slot here carries, and it is the only one that
     * describes a promise rather than a call: the slot says what it is fit for, which is why {@code
     * int.__dict__['__index__'].__doc__} reads like documentation and {@code __float__}'s reads like a
     * signature.
     */
    private static final String RADD_DOC = "Return value+self.";

    private static final String DIVMOD_DOC = "Return divmod(self, value).";
    private static final String RDIVMOD_DOC = "Return divmod(value, self).";
    private static final String POW_DOC = "Return pow(self, value, mod).";
    private static final String RPOW_DOC = "Return pow(value, self, mod).";
    private static final String NEG_DOC = "-self";
    private static final String POS_DOC = "+self";
    private static final String ABS_DOC = "abs(self)";
    private static final String TRUTH_DOC = "True if self else False";
    private static final String INVERT_DOC = "~self";
    private static final String LSHIFT_DOC = "Return self<<value.";
    private static final String RLSHIFT_DOC = "Return value<<self.";
    private static final String RSHIFT_DOC = "Return self>>value.";
    private static final String RRSHIFT_DOC = "Return value>>self.";
    private static final String TO_INT_DOC = "int(self)";
    private static final String TO_FLOAT_DOC = "float(self)";
    private static final String FLOORDIV_DOC = "Return self//value.";
    private static final String RFLOORDIV_DOC = "Return value//self.";
    private static final String TRUEDIV_DOC = "Return self/value.";
    private static final String RTRUEDIV_DOC = "Return value/self.";

    private static final String INDEX_DOC =
            "Return self converted to an integer, if self is suitable for use as an index into a list.";

    /**
     * The four sentences {@code long_getseters} writes over the parts of a number.
     *
     * <p>All four are worded for the complex number a value might be, and an {@code int} answers them with
     * itself, nothing, itself and one: {@code real} and {@code numerator} are {@code long_long}, which hands
     * back the receiver rather than a copy of it, so {@code BIG.real is BIG} says true.
     */
    private static final String REAL_DOC = "the real part of a complex number";

    private static final String IMAG_DOC = "the imaginary part of a complex number";
    private static final String NUMERATOR_DOC =
            "the numerator of a rational number in lowest terms";
    private static final String DENOMINATOR_DOC =
            "the denominator of a rational number in lowest terms";

    /**
     * The three slots a mutable sequence files that an octet run does not.
     *
     * <p>{@code sq_ass_item} and {@code sq_ass_slice} are one {@code __setitem__} and {@code sq_slice}
     * with an argument to put in it, and {@code sq_inplace_concat} and {@code sq_inplace_repeat} are the
     * two that change the receiver and hand it back — which is why their sentences say {@code Implement}
     * rather than {@code Return}: the answer is the same list, not a new one.
     */
    private static final String SETITEM_DOC = "Set self[key] to value.";

    private static final String DELITEM_DOC = "Delete self[key].";
    private static final String IADD_DOC = "Implement self+=value.";
    private static final String IMUL_DOC = "Implement self*=value.";

    /**
     * The five sentences the collection types write over {@code __class_getitem__}.
     *
     * <p>All five are PEP 585 declarations rather than {@code PyMethodDef} docstrings, and the tuple's goes
     * on for four paragraphs because a tuple's arguments are per position: it has to say what {@code
     * tuple[int, str]} means before it can say what {@code tuple[int, ...]} means instead. The other four run
     * to one line each, and {@code dict}'s is the only one of them that counts — a mapping is generic over
     * two types because it has two places for a value to sit. The two sets spell the same thing out in the
     * plural, because a set holds any number of elements of one type rather than one element per position.
     */
    private static final String LIST_CLASS_GETITEM_DOC =
            "lists are generic over the type of their contents";

    private static final String TUPLE_CLASS_GETITEM_DOC =
            "Tuples are generic over the types of their contents.\n"
                    + "\n"
                    + "For example, use ``tuple[int, str]`` for a pair whose first element\n"
                    + "is an int and second element is a string.\n"
                    + "\n"
                    + "Tuples also support the form ``tuple[T, ...]`` to indicate\n"
                    + "an arbitrary length tuple of elements of type T.";

    private static final String DICT_CLASS_GETITEM_DOC =
            "dicts are generic over two types, signifying (respectively) the types of their keys and values";

    private static final String SET_CLASS_GETITEM_DOC =
            "sets are generic over the type of their elements";

    private static final String FROZENSET_CLASS_GETITEM_DOC =
            "frozensets are generic over the type of their elements";

    /**
     * {@code BaseExceptionGroup_methods}'s {@code __class_getitem__} (exceptions.c:1762), the one generic
     * subscript of the exception family.
     *
     * <p>Like the five the collections file it is {@code METH_O|METH_CLASS} over {@code Py_GenericAlias}, so
     * the difference is only the capital: they describe themselves in the lower case of a note about the
     * type's own name, and this row runs as a sentence because {@code Exception groups} is a subject rather
     * than a name.
     */
    private static final String GROUP_CLASS_GETITEM_DOC =
            "Exception groups are generic over the type of their contained exceptions";

    /** {@code type.__new__(mcs, name, bases, dict)}: the metaclass idiom, and no descriptor at all. */
    static final Member NEW =
            new Member(PyType.TYPE, "__new__", Shape.RAW, null, NEW_DOC, TypeDict::newMember);

    /** {@code type.__init__(cls, name, bases, dict)}: the checks {@code type_init} makes and nothing else. */
    static final Member INIT =
            new Member(
                    PyType.TYPE, "__init__", Shape.WRAPPER, null, INIT_DOC, TypeDict::initMember);

    /** {@code type.__call__(cls, *args)}: {@code type_call} for the class given as the receiver. */
    static final Member CALL =
            new Member(
                    PyType.TYPE,
                    "__call__",
                    Shape.WRAPPER,
                    null,
                    "Call self as a function.",
                    TypeDict::callMember);

    /** {@code cls.mro()}: the lookup order as a list. */
    static final Member MRO =
            new Member(
                    PyType.TYPE,
                    "mro",
                    Shape.METHOD,
                    null,
                    "Return a type's method resolution order.",
                    TypeDict::mroMember);

    /** {@code type.__prepare__(mcs, name, bases, **kwds)}: always a plain dict, whatever it is asked. */
    static final Member PREPARE =
            new Member(
                    PyType.TYPE,
                    "__prepare__",
                    Shape.CLASSMETHOD,
                    null,
                    "Create the namespace for the class statement",
                    TypeDict::prepare);

    /** {@code type.__instancecheck__(cls, inst)}: the test {@code isinstance} makes without a hook. */
    static final Member INSTANCE_CHECK =
            new Member(
                    PyType.TYPE,
                    "__instancecheck__",
                    Shape.METHOD,
                    null,
                    "Check if an object is an instance.",
                    TypeDict::instanceCheck);

    /** {@code type.__subclasscheck__(cls, sub)}: the test {@code issubclass} makes without a hook. */
    static final Member SUBCLASS_CHECK =
            new Member(
                    PyType.TYPE,
                    "__subclasscheck__",
                    Shape.METHOD,
                    null,
                    "Check if a class is a subclass.",
                    TypeDict::subclassCheck);

    /** {@code object.__init_subclass__(cls, **kwds)}: the hook that refuses what it was not asked for. */
    static final Member INIT_SUBCLASS =
            new Member(
                    PyType.OBJECT,
                    "__init_subclass__",
                    Shape.CLASSMETHOD,
                    null,
                    "This method is called when a class is subclassed.\n\nThe default implementation does"
                            + " nothing. It may be\noverridden to extend subclasses.\n",
                    TypeDict::initSubclass);

    /**
     * {@code BaseException.__new__(cls, *args)}: the creator every exception class inherits, which keeps
     * the arguments it was handed on the value it makes.
     *
     * <p>Each class of the family files its own wrapper over this one body, and names itself in it: {@code
     * BaseException.__dict__['__new__'].__qualname__} is {@code BaseException.__new__} and the row beside
     * it under {@code Exception} says {@code Exception.__new__}, even though the two hold pointers to the
     * same C function. That is why {@code Bare.__new__} for an {@code E(Exception)} reads {@code
     * Exception.__new__} — the walk of the order stops at the nearest class that files a wrapper, and the
     * wrapper carries the name of the class it was filed under.
     */
    static final Member EXCEPTION_NEW =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "__new__",
                    Shape.RAW,
                    null,
                    NEW_DOC,
                    exceptionNewFor(ExceptionTypes.BASE_EXCEPTION));

    /** The {@code BaseException_new} gates, told which class of the family the call came in through. */
    private static Method exceptionNewFor(PyType declaring) {
        return (args, kwNames, qualified) -> exceptionNew(declaring, args, kwNames);
    }

    /** {@code BaseException.__init__(self, *args)}: the arguments of the call, taken over. */
    static final Member EXCEPTION_INIT =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "__init__",
                    Shape.WRAPPER,
                    null,
                    INIT_DOC,
                    TypeDict::exceptionInit);

    /** {@code e.with_traceback(traceback)}: the one method an exception value answers with. */
    static final Member WITH_TRACEBACK =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "with_traceback",
                    Shape.METHOD,
                    null,
                    "Set self.__traceback__ to tb and return self.",
                    TypeDict::withTraceback);

    /**
     * {@code e.add_note(note)}: a note appended to the list the value keeps under {@code __notes__}.
     *
     * <p>The name appears on the value only once a note has been added, and the list is written through
     * the attribute rather than kept in a field, which is what lets a class that files one of its own
     * under that name — or a value whose {@code __setattr__} throws the write away — be answered the way
     * CPython answers them.
     */
    static final Member ADD_NOTE =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "add_note",
                    Shape.METHOD,
                    null,
                    "Add a note to the exception",
                    TypeDict::addNote);

    /**
     * {@code BaseException.__reduce__}: the value as the call that would rebuild it.
     *
     * <p>The one member of the family that answers with a {@code method_descriptor} and has nothing to say
     * about itself: CPython's clinic block for it names no sentence, so {@code __doc__} is {@code None}
     * where every neighbour of it has a line.
     */
    static final Member EXCEPTION_REDUCE =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "__reduce__",
                    Shape.METHOD,
                    null,
                    null,
                    TypeDict::exceptionReduce);

    /** {@code BaseException.__setstate__}: the entries of a pickled state, written back as attributes. */
    static final Member EXCEPTION_SETSTATE =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "__setstate__",
                    Shape.METHOD,
                    null,
                    null,
                    TypeDict::exceptionSetstate);

    /** {@code OSError.__reduce__}: the same two or three places, with the file names put back. */
    static final Member OS_ERROR_REDUCE =
            new Member(
                    ExceptionTypes.OS_ERROR,
                    "__reduce__",
                    Shape.METHOD,
                    null,
                    null,
                    TypeDict::oSErrorReduce);

    /**
     * {@code BaseExceptionGroup.__new__(cls, message, exceptions)}: the creator both group classes and
     * any subclass of them inherit.
     *
     * <p>Unlike {@code BaseException}'s, this one reports itself under the class that holds it —
     * {@code ExceptionGroup.__new__.__qualname__} is {@code BaseExceptionGroup.__new__} — and keywords
     * are read and thrown away rather than refused, because the two arguments are parsed out of the
     * positional tuple only. The refusal for a named argument belongs to {@code __init__}, which runs
     * after the class has already been chosen.
     */
    static final Member GROUP_NEW =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "__new__",
                    Shape.RAW,
                    null,
                    NEW_DOC,
                    TypeDict::groupNew);

    /** {@code BaseExceptionGroup.__init__(self, *args)}: the arguments taken over, keywords refused. */
    static final Member GROUP_INIT =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "__init__",
                    Shape.WRAPPER,
                    null,
                    INIT_DOC,
                    TypeDict::groupInit);

    /** {@code g.derive(exceptions)}: a group of the same shape holding the children given. */
    static final Member GROUP_DERIVE =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "derive",
                    Shape.METHOD,
                    TypeDict::groupDerive);

    /** {@code g.split(matcher)}: the matching part and the rest, as a two-tuple. */
    static final Member GROUP_SPLIT =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "split",
                    Shape.METHOD,
                    TypeDict::groupSplit);

    /** {@code g.subgroup(matcher)}: the matching part alone, or {@code None}. */
    static final Member GROUP_SUBGROUP =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "subgroup",
                    Shape.METHOD,
                    TypeDict::groupSubgroup);

    private TypeDict() {
    }

    /**
     * The one dictionary each built-in type keeps, as CPython's {@code tp_dict} is one pointer per type.
     *
     * <p>{@code int.__dict__ == int.__dict__} is true while {@code int.__dict__ is int.__dict__} is false
     * because the read makes a fresh {@link PyMappingProxy} over this one dictionary every time.
     */
    private static final Map<PyType, PyDict> DECLARED = new ConcurrentHashMap<>();

    /**
     * The members {@code owner} files in its own dictionary, built the first time the type is asked.
     *
     * <p>Put into {@link #DECLARED} empty and filled afterwards rather than built and then added, because
     * filling a table can read another type's table — {@link #fromTable} runs a value class's static
     * initialiser, and the sentences and constants it files may read a type's {@code __doc__}, which is a
     * name in the very dictionary being filled. A table added only after it was finished would be built
     * twice during that recursion, and the second reader would hold a window over the half of it the first
     * build had filled.
     */
    static PyDict declared(PyType owner) {
        PyDict built = DECLARED.get(owner);
        if (built == null) {
            built = PyDict.empty();
            DECLARED.put(owner, built);
            fill(owner, built);
        }
        return built;
    }

    private static void fill(PyType owner, PyDict into) {
        if (owner == PyType.OBJECT) {
            objectTable(into);
            return;
        }
        if (owner == PyType.TYPE) {
            typeTable(into);
            return;
        }
        if (owner == PyType.MAPPING_PROXY) {
            proxyTable(into);
            return;
        }
        if (owner == PyType.BYTES) {
            bytesTable(into);
            return;
        }
        if (owner == PyType.STR) {
            strTable(into);
            return;
        }
        if (owner == PyType.LIST) {
            listTable(into);
            return;
        }
        if (owner == PyType.TUPLE) {
            tupleTable(into);
            return;
        }
        if (owner == PyType.DICT) {
            dictTable(into);
            return;
        }
        if (owner == PyType.SET) {
            setTable(into);
            return;
        }
        if (owner == PyType.FROZENSET) {
            frozenSetTable(into);
            return;
        }
        if (owner == PyType.INT) {
            intTable(into);
            return;
        }
        if (owner == PyType.BOOL) {
            boolTable(into);
            return;
        }
        if (owner == PyType.FLOAT) {
            floatTable(into);
            return;
        }
        if (owner.matches(ExceptionTypes.BASE_EXCEPTION)) {
            exceptionTable(owner, into);
            return;
        }
        fromTable(owner, into);
    }

    /**
     * {@code p.name} for the window {@code p}: the entry under the name, run through its descriptor.
     *
     * <p>A window has no dictionary of its own, so the members are read from the type's table — which is
     * what CPython's {@code PyDescr_NewGetSet} list for {@code mappingproxy} is. The walk stops at the
     * first type that files the name, and {@code object} answers {@code __class__} for the window the
     * same way it does for every other value.
     */
    static PyObject read(PyObject self, String name) {
        PyStr key = PyStr.interned(name);
        for (PyType owner : self.getType().mro()) {
            PyObject entry = owner.namespace().get(key);
            if (entry != null) {
                return Descriptors.get(entry, self, owner);
            }
        }
        return PyNotImplemented.INSTANCE;
    }

    /**
     * {@code object}'s own dictionary, as the reference interpreter files it: twenty-four names.
     *
     * <p>Fourteen are slot wrappers — the C functions every value inherits and that nothing below
     * {@code object} replaces unless a type declares its own. Three are pickle's helpers, one is the
     * formatter, one the size, one {@code dir()}'s default, and two are {@code METH_CLASS} methods that
     * bind the class they were read from rather than any value. {@code __class__} is the only getset, and
     * {@code __doc__} is not a descriptor at all: a static type's sentence is a plain string in its
     * dictionary, which is why {@code BaseException.__doc__} is read out of the class rather than
     * inherited.
     */
    private static void objectTable(PyDict into) {
        PyType owner = PyType.OBJECT;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::objectNew);
        table.wrapper("__repr__", REPR_DOC, TypeDict::objectRepr);
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__str__", STR_DOC, args -> PyStr.of(args[0].str()));
        table.wrapper("__getattribute__", GETATTR_DOC, TypeDict::objectGetAttribute);
        table.wrapper("__setattr__", SETATTR_DOC, TypeDict::objectSetAttribute);
        table.wrapper("__delattr__", DELATTR_DOC, TypeDict::objectDelAttribute);
        table.wrapper("__lt__", LT_DOC, (args, kw, q) -> identity(args, CompareOp.LT));
        table.wrapper("__le__", LE_DOC, (args, kw, q) -> identity(args, CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, (args, kw, q) -> identity(args, CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, (args, kw, q) -> identity(args, CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, (args, kw, q) -> identity(args, CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, (args, kw, q) -> identity(args, CompareOp.GE));
        table.wrapper("__init__", INIT_DOC, TypeDict::objectInit);
        table.method("__reduce_ex__", PICKLE_DOC, TypeDict::objectReduceEx);
        table.method("__reduce__", PICKLE_DOC, TypeDict::objectReduce);
        table.method("__getstate__", PICKLE_DOC, TypeDict::objectState);
        table.classMethod("__subclasshook__", SUBCLASS_HOOK_DOC, TypeDict::subclassHook);
        table.put("__init_subclass__", INIT_SUBCLASS);
        table.method("__format__", FORMAT_DOC, TypeDict::objectFormat);
        table.method("__sizeof__", SIZEOF_DOC, TypeDict::objectSize);
        table.method("__dir__", DIR_DOC, TypeDict::objectDir);
        table.getset("__class__", CLASS_DOC, ClassAttribute::read, TypeDict::writeClass);
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code type}'s own dictionary: the thirty-four names every class inherits through its metatype.
     *
     * <p>Five of the names already existed as {@link #NEW}, {@link #INIT}, {@link #CALL}, {@link #MRO},
     * {@link #PREPARE}, {@link #INSTANCE_CHECK} and {@link #SUBCLASS_CHECK}, and are filed here as the
     * same objects so that {@code type.__dict__['mro'] is type.mro} stays true. The rest are new: six
     * {@code member_descriptor}s over the C struct's own fields — which this runtime has no layout for, so
     * the four sizes and offsets answer nought — and twelve {@code getset_descriptor}s over the names a
     * class reports about itself, which are now the half of {@link PyType#getAttr} a program can reach
     * through the dictionary.
     */
    private static void typeTable(PyDict into) {
        PyType owner = PyType.TYPE;
        Table table = new Table(owner, into);
        table.put("__new__", NEW);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.put("__call__", CALL);
        table.wrapper("__getattribute__", GETATTR_DOC, TypeDict::typeGetAttribute);
        table.wrapper("__setattr__", SETATTR_DOC, TypeDict::typeSetAttribute);
        table.wrapper("__delattr__", DELATTR_DOC, TypeDict::typeDelAttribute);
        table.put("__init__", INIT);
        table.wrapper("__or__", OR_DOC, (args, kw, q) -> PyNotImplemented.INSTANCE);
        table.wrapper("__ror__", ROR_DOC, (args, kw, q) -> PyNotImplemented.INSTANCE);
        table.put("mro", MRO);
        table.method("__subclasses__", SUBCLASSES_DOC, TypeDict::subclasses);
        table.put("__prepare__", PREPARE);
        table.put("__instancecheck__", INSTANCE_CHECK);
        table.put("__subclasscheck__", SUBCLASS_CHECK);
        table.method("__dir__", TYPE_DIR_DOC, TypeDict::typeDir);
        table.method("__sizeof__", TYPE_SIZEOF_DOC, TypeDict::typeSize);
        table.member("__basicsize__");
        table.member("__itemsize__");
        table.member("__flags__");
        table.member("__weakrefoffset__");
        table.member("__base__", TypeDict::baseOf);
        table.member("__dictoffset__");
        table.getset("__name__", null, TypeDict::nameOf, writesIdentity("__name__"));
        table.getset("__qualname__", null, TypeDict::nameOf, writesIdentity("__qualname__"));
        table.getset("__bases__", null, TypeDict::basesOf, writesBases());
        table.getset("__mro__", null, TypeDict::mroOf, refuses("__mro__"));
        table.getset("__module__", null, TypeDict::moduleOf, writesModule());
        table.getset(
                "__abstractmethods__", null, TypeDict::abstractMethods, files("__abstractmethods__"));
        table.getset("__dict__", null, TypeDict::dictOf, refuses("__dict__"));
        table.getset("__doc__", null, TypeDict::docOf, writes("__doc__"));
        table.getset("__text_signature__", null, kind -> PyNone.INSTANCE, refuses("__text_signature__"));
        table.getset("__annotations__", null, TypeDict::annotationsOf, files("__annotations__"));
        table.getset("__annotate__", null, TypeDict::annotateOf, writesAnnotate());
        table.getset("__type_params__", null, TypeDict::typeParamsOf, writes("__type_params__"));
    }

    /**
     * {@code mappingproxy}'s own dictionary: the twenty-five names a window answers itself with.
     *
     * <p>Seventeen are the slots that hand the <em>mapping</em> underneath to CPython rather than the
     * window — which is why {@code p | {}} answers a dict — and six are {@code PyMethodDef}s over the same
     * mapping. There is no {@code __setitem__} or {@code __delitem__} among them, and that absence is the
     * whole of why writing through a window complains about a missing attribute rather than about a type
     * that will not be written.
     */
    private static void proxyTable(PyDict into) {
        PyType owner = PyType.MAPPING_PROXY;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::proxyNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__str__", STR_DOC, args -> PyStr.of(args[0].str()));
        table.wrapper("__lt__", LT_DOC, (args, kw, q) -> windowed(args, CompareOp.LT));
        table.wrapper("__le__", LE_DOC, (args, kw, q) -> windowed(args, CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, (args, kw, q) -> windowed(args, CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, (args, kw, q) -> windowed(args, CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, (args, kw, q) -> windowed(args, CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, (args, kw, q) -> windowed(args, CompareOp.GE));
        table.wrapper("__iter__", "Implement iter(self).", args -> args[0].iterator());
        table.wrapper("__or__", "Return self|value.", TypeDict::windowOr);
        table.wrapper("__ror__", "Return value|self.", TypeDict::windowOr);
        table.wrapper("__ior__", IOR_DOC, TypeDict::refuseIor);
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.wrapper("__getitem__", GETITEM_DOC, (args, kw, q) -> window(args[0]).getItem(args[1]));
        table.wrapper(
                "__contains__", CONTAINS_DOC, (args, kw, q) -> window(args[0]).contains(args[1]));
        table.method("get", GET_DOC, TypeDict::proxyGet);
        table.method("keys", KEYS_DOC, args -> proxyView(args[0], "keys"));
        table.method("values", VALUES_DOC, args -> proxyView(args[0], "values"));
        table.method("items", ITEMS_DOC, args -> proxyView(args[0], "items"));
        table.method("copy", COPY_DOC, args -> window(args[0]).copy());
        table.classMethod(
                "__class_getitem__", PROXY_CLASS_GETITEM_DOC, (args, kw, q) -> PyNotImplemented.INSTANCE);
        table.method("__reversed__", REVERSED_DOC, args -> reversedKeys(window(args[0])));
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code bytes}'s own dictionary: the sixty-five names an octet run files.
     *
     * <p>Nineteen of them are slot wrappers. CPython copies a static type's {@code tp_as_sequence} and
     * {@code tp_as_number} halves into that type's own dictionary instead of leaving them inherited, so
     * {@code bytes.__dict__['__len__']} is a name a program can read while this runtime's {@code int} has
     * no such entry — the asymmetry the plan has carried since slice 11. {@code __buffer__} is the private
     * one of the nineteen: the slot a {@code memoryview} is built from, and the only name here a person has
     * no ordinary way to call. The two method descriptors before the methods are pickle's {@code
     * __getnewargs__} and {@code __bytes__}, declared by the value class so that a read of {@code
     * b''.__bytes__} and the dictionary's own entry are one method; the forty-four after them are the rest
     * of {@link PyBytes}'s table, filed in the order the reference interpreter's dictionary shows them —
     * which is not the order its {@code bytes_methods} list was written in, and not this runtime's
     * declaration order either.
     */
    private static void bytesTable(PyDict into) {
        PyType owner = PyType.BYTES;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::octetsNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__str__", STR_DOC, args -> PyStr.of(args[0].str()));
        table.wrapper("__lt__", LT_DOC, comparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, comparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, comparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, comparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, comparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, comparing(CompareOp.GE));
        table.wrapper("__iter__", ITER_DOC, args -> args[0].iterator());
        table.wrapper("__buffer__", BUFFER_DOC, TypeDict::exposeBuffer);
        table.wrapper("__mod__", MOD_DOC, TypeDict::modulo);
        table.wrapper("__rmod__", RMOD_DOC, TypeDict::reflectedMod);
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.wrapper("__getitem__", GETITEM_DOC, args -> args[0].getItem(args[1]));
        table.wrapper("__add__", ADD_DOC, TypeDict::joined);
        table.wrapper("__mul__", MUL_DOC, TypeDict::repeated);
        table.wrapper("__rmul__", RMUL_DOC, TypeDict::repeated);
        table.wrapper("__contains__", CONTAINS_DOC, args -> args[0].contains(args[1]));
        table.named("__getnewargs__", "__bytes__");
        table.named(
                "capitalize", "center", "count", "decode", "endswith", "expandtabs", "find", "fromhex",
                "hex", "index", "isalnum", "isalpha", "isascii", "isdigit", "islower", "isspace", "istitle",
                "isupper", "join", "ljust", "lower", "lstrip", "maketrans", "partition", "replace",
                "removeprefix", "removesuffix", "rfind", "rindex", "rjust", "rpartition", "rsplit", "rstrip",
                "split", "splitlines", "startswith", "strip", "swapcase", "title", "translate", "upper",
                "zfill");
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code str}'s own dictionary: the seventy names a run of text files.
     *
     * <p>The same nineteen slots as {@code bytes}, less {@code __buffer__} — a string has no memory to
     * expose, which is why {@code memoryview('ab')} is refused while {@code memoryview(b'ab')} is not — and
     * then forty-six methods, four of which sit after them in the dictionary rather than inside the list:
     * {@code __format__} and {@code maketrans} and the two names pickle uses, {@code __sizeof__} and {@code
     * __getnewargs__}. {@code maketrans} is filed as the {@code staticmethod} its table holds, so the
     * dictionary reads as a wrapper while {@code str.maketrans} reads as the function underneath it.
     */
    private static void strTable(PyDict into) {
        PyType owner = PyType.STR;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::textNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__str__", STR_DOC, args -> PyStr.of(args[0].str()));
        table.wrapper("__lt__", LT_DOC, comparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, comparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, comparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, comparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, comparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, comparing(CompareOp.GE));
        table.wrapper("__iter__", ITER_DOC, args -> args[0].iterator());
        table.wrapper("__mod__", MOD_DOC, TypeDict::modulo);
        table.wrapper("__rmod__", RMOD_DOC, TypeDict::reflectedMod);
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.wrapper("__getitem__", GETITEM_DOC, args -> args[0].getItem(args[1]));
        table.wrapper("__add__", ADD_DOC, TypeDict::joined);
        table.wrapper("__mul__", MUL_DOC, TypeDict::repeated);
        table.wrapper("__rmul__", RMUL_DOC, TypeDict::repeated);
        table.wrapper("__contains__", CONTAINS_DOC, args -> args[0].contains(args[1]));
        table.named(
                "encode", "replace", "split", "rsplit", "join", "capitalize", "casefold", "title", "center",
                "count", "expandtabs", "find", "partition", "index", "ljust", "lower", "lstrip", "rfind",
                "rindex", "rjust", "rstrip", "rpartition", "splitlines", "strip", "swapcase", "translate",
                "upper", "startswith", "endswith", "removeprefix", "removesuffix", "isascii", "islower",
                "isupper", "istitle", "isspace", "isdecimal", "isdigit", "isnumeric", "isalpha", "isalnum",
                "isidentifier", "isprintable", "zfill", "format", "format_map");
        table.named("__format__");
        table.named("maketrans");
        table.named("__sizeof__", "__getnewargs__");
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code list}'s own dictionary: the thirty-six names a mutable sequence files.
     *
     * <p>Eighteen of them are slots, and the two that differ from an octet run's are the interesting ones.
     * {@code __hash__} is not a slot at all: a list is mutable, so CPython files {@code None} under the name
     * and the inherited {@code object.__hash__} is out of reach — {@code list.__hash__ is None} is the whole
     * of why {@code hash([])} complains about unhashability rather than about a missing name. And
     * {@code __getitem__} is a {@code METH_O} method here, not the {@code sq_item} copy {@code bytes} and
     * {@code tuple} file, because {@code list_subscript} answers a slice as well as an index and is written
     * down in {@code list_methods} rather than copied out of the sequence protocol. The two in-place slots
     * sit after {@code __contains__}, and the eleven methods a person calls are the entries of
     * {@code list_methods} that follow them, with {@code sort} — the one with two keyword parameters — last.
     */
    private static void listTable(PyDict into) {
        PyType owner = PyType.LIST;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::listsNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.value("__hash__", PyNone.INSTANCE);
        table.wrapper("__lt__", LT_DOC, comparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, comparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, comparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, comparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, comparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, comparing(CompareOp.GE));
        table.wrapper("__iter__", ITER_DOC, args -> args[0].iterator());
        table.wrapper("__init__", INIT_DOC, TypeDict::listInit);
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.named("__getitem__");
        table.wrapper("__setitem__", SETITEM_DOC, args -> args[0].setItem(args[1], args[2]));
        table.wrapper("__delitem__", DELITEM_DOC, args -> args[0].delItem(args[1]));
        table.wrapper("__add__", ADD_DOC, TypeDict::joined);
        table.wrapper("__mul__", MUL_DOC, TypeDict::repeated);
        table.wrapper("__rmul__", RMUL_DOC, TypeDict::repeated);
        table.wrapper("__contains__", CONTAINS_DOC, args -> args[0].contains(args[1]));
        table.wrapper("__iadd__", IADD_DOC, TypeDict::joinedInPlace);
        table.wrapper("__imul__", IMUL_DOC, TypeDict::repeatedInPlace);
        table.named("__reversed__", "__sizeof__");
        table.named(
                "clear", "copy", "append", "insert", "extend", "pop", "remove", "index", "count", "reverse",
                "sort");
        table.classMethod(
                "__class_getitem__",
                LIST_CLASS_GETITEM_DOC,
                (args, kwNames, qualified) -> classGetItem(owner, args, kwNames, qualified));
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code tuple}'s own dictionary: the twenty-one names an immutable sequence files.
     *
     * <p>Fifteen slots, and every one of them the sequence protocol's — {@code __getitem__} is the
     * {@code sq_item} copy here, where {@code list} files a method, because {@code tuple_subscript} is
     * {@code PySequence_GetItem} over the same slot. {@code __hash__} is a slot and not {@code None}, which
     * is the whole difference between a dictionary key and the {@code TypeError} a list gets. Then pickle's
     * {@code __getnewargs__} — the one entry in either table whose clinic {@code ml_doc} is nothing but a
     * signature header, so {@code _PyType_GetDocFromInternalDoc} has nothing left to hand back and the name
     * says nothing about itself — then {@code index} and {@code count}, the only two
     * methods a value of this type has, then the generic subscript and the type's own sentence. There is no
     * {@code __init__}: a tuple is built by {@code __new__} alone, which is why {@code (1,).__init__} is the
     * one inherited from {@code object} and refuses a second argument.
     */
    private static void tupleTable(PyDict into) {
        PyType owner = PyType.TUPLE;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::tuplesNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__lt__", LT_DOC, comparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, comparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, comparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, comparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, comparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, comparing(CompareOp.GE));
        table.wrapper("__iter__", ITER_DOC, args -> args[0].iterator());
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.wrapper("__getitem__", GETITEM_DOC, args -> args[0].getItem(args[1]));
        table.wrapper("__add__", ADD_DOC, TypeDict::joined);
        table.wrapper("__mul__", MUL_DOC, TypeDict::repeated);
        table.wrapper("__rmul__", RMUL_DOC, TypeDict::repeated);
        table.wrapper("__contains__", CONTAINS_DOC, args -> args[0].contains(args[1]));
        table.named("__getnewargs__");
        table.named("index", "count");
        table.classMethod(
                "__class_getitem__",
                TUPLE_CLASS_GETITEM_DOC,
                (args, kwNames, qualified) -> classGetItem(owner, args, kwNames, qualified));
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code dict}'s own dictionary: the thirty-four names a mapping files.
     *
     * <p>Fifteen of them are slot wrappers, and the shape of the other nineteen is the story of this table.
     * Four of the nineteen are slots that CPython writes down as {@code PyMethodDef}s instead of copying out
     * of a protocol — {@code __getitem__} because {@code dict_subscript} answers a missing key as loudly as
     * a hit, {@code __contains__} because {@code dict_as_sequence} has nothing but that test, {@code
     * __sizeof__} and {@code __reversed__} because neither is a protocol at all — and so all four count
     * their arguments as methods do, naming themselves qualified where a wrapper says only {@code expected 1
     * argument, got 0}. Two more are {@code METH_CLASS}: {@code fromkeys}, which is a mapping's constructor
     * spelled out as a method, and {@code __class_getitem__}, the generic subscript every one of these four
     * collection types now files. The thirteen left are the methods a person calls, and they are filed
     * between the two class methods rather than in one run: {@code update} ends the first twelve and
     * {@code clear} starts the last two.
     *
     * <p>Three names a reader might look for are not here. {@code __hash__} is filed as the value {@code
     * None}, as it is for {@code list}: a dict is mutable, and {@code hash({})} is refused by the dictionary
     * rather than by a callable. There is no {@code __str__}, because {@code dict} leaves {@code tp_str} null
     * and inherits {@code PyObject_Str}, where {@code bytes} and {@code str} each keep a sentence of their
     * own. And all six comparisons are filed although only the two that ask whether the entries match can
     * answer: {@code {} < {}} is a decline, and the operator that got it says so in its own words.
     */
    private static void dictTable(PyDict into) {
        PyType owner = PyType.DICT;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::dictsNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.value("__hash__", PyNone.INSTANCE);
        table.wrapper("__lt__", LT_DOC, comparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, comparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, comparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, comparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, comparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, comparing(CompareOp.GE));
        table.wrapper("__iter__", ITER_DOC, args -> args[0].iterator());
        table.wrapper("__init__", INIT_DOC, TypeDict::dictInit);
        table.wrapper("__or__", OR_DOC, TypeDict::merged);
        table.wrapper("__ror__", ROR_DOC, TypeDict::reflectedMerge);
        table.wrapper("__ior__", IOR_DOC, TypeDict::mergedInPlace);
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.named("__getitem__");
        table.wrapper("__setitem__", SETITEM_DOC, args -> args[0].setItem(args[1], args[2]));
        table.wrapper("__delitem__", DELITEM_DOC, args -> args[0].delItem(args[1]));
        table.named("__contains__", "__sizeof__");
        table.named("get", "setdefault", "pop", "popitem", "keys", "items", "values", "update");
        table.named("fromkeys");
        table.named("clear", "copy", "__reversed__");
        table.classMethod(
                "__class_getitem__",
                DICT_CLASS_GETITEM_DOC,
                (args, kwNames, qualified) -> classGetItem(owner, args, kwNames, qualified));
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code set}'s own dictionary: the forty-six names a mutable collection of uniques files.
     *
     * <p>Twenty-two of them are slot wrappers, and they are the reason this table looks unlike the other
     * collections'. A set's operators are {@code PyNumberMethods} entries — {@code nb_subtract} for {@code
     * -}, {@code nb_and} for {@code &}, {@code nb_or} for {@code |}, {@code nb_xor} for {@code ^}, each with
     * a reflected twin and, for the mutable half of the family, an in-place one — so a set files
     * {@code __sub__} where a list files {@code __add__}, and the four in-place slots sit between the
     * forward operators and {@code __len__}. {@code __hash__} is filed as the value {@code None}, as for
     * {@code list} and {@code dict}: a set changes, and {@code hash({1})} is refused by that entry rather
     * than by a callable.
     *
     * <p>{@code __contains__} is the twenty-third name that is not a wrapper. {@code sq_contains} is
     * {@code set_contains}, and the clinic block beside it is marked {@code @coexist}, so the method writes
     * over the slot's entry instead of adding a second one: the name keeps its place right after {@code
     * __len__} while reading as a {@code method_descriptor} and counting its argument in method words
     * ({@code set.__contains__() takes exactly one argument (0 given)}) rather than in the wrapper's bare
     * {@code expected 1 argument, got 0}. {@code __reduce__} and {@code __sizeof__} are the other two
     * methods among the slots' names, and neither is a protocol at all.
     *
     * <p>The nineteen method rows after it are {@code set_methods}, which is written in alphabetical order
     * and so reads as no grouping at all: {@code add} and {@code clear} come first, {@code pop} and {@code
     * remove} and {@code update} are scattered among them, and the four {@code *_update} names are not
     * together. {@code __init__} is a slot here although {@code frozenset} leaves it inherited, and
     * {@code set.__new__} is the one constructor in this family that ignores its argument outright.
     */
    private static void setTable(PyDict into) {
        PyType owner = PyType.SET;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::setsNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.value("__hash__", PyNone.INSTANCE);
        table.wrapper("__lt__", LT_DOC, comparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, comparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, comparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, comparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, comparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, comparing(CompareOp.GE));
        table.wrapper("__iter__", ITER_DOC, args -> args[0].iterator());
        table.wrapper("__init__", INIT_DOC, TypeDict::setInit);
        table.wrapper("__sub__", SUB_DOC, operating(BinaryOp.SUB));
        table.wrapper("__rsub__", RSUB_DOC, reflectedOperating(BinaryOp.SUB));
        table.wrapper("__and__", AND_DOC, operating(BinaryOp.AND));
        table.wrapper("__rand__", RAND_DOC, reflectedOperating(BinaryOp.AND));
        table.wrapper("__xor__", XOR_DOC, operating(BinaryOp.XOR));
        table.wrapper("__rxor__", RXOR_DOC, reflectedOperating(BinaryOp.XOR));
        table.wrapper("__or__", OR_DOC, operating(BinaryOp.OR));
        table.wrapper("__ror__", ROR_DOC, reflectedOperating(BinaryOp.OR));
        table.wrapper("__isub__", ISUB_DOC, operatingInPlace(BinaryOp.SUB));
        table.wrapper("__iand__", IAND_DOC, operatingInPlace(BinaryOp.AND));
        table.wrapper("__ixor__", IXOR_DOC, operatingInPlace(BinaryOp.XOR));
        table.wrapper("__ior__", IOR_DOC, operatingInPlace(BinaryOp.OR));
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.named("__contains__");
        table.named(
                "add", "clear", "copy", "discard", "difference", "difference_update", "intersection",
                "intersection_update", "isdisjoint", "issubset", "issuperset", "pop", "__reduce__",
                "remove", "__sizeof__", "symmetric_difference", "symmetric_difference_update", "union",
                "update");
        table.classMethod(
                "__class_getitem__",
                SET_CLASS_GETITEM_DOC,
                (args, kwNames, qualified) -> classGetItem(owner, args, kwNames, qualified));
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code frozenset}'s own dictionary: the thirty-two names an immutable one files.
     *
     * <p>The same table less the eight names that change a value — {@code add}, {@code clear}, {@code
     * discard}, {@code pop}, {@code remove}, and the three {@code *_update} methods the in-place slots call —
     * and less {@code __init__}, which {@code frozenset} leaves null so that {@code f.__init__(1, 2)} is the
     * inherited {@code object.__init__} ignoring extras rather than a refusal. Its {@code __hash__} is the
     * slot, not {@code None}: {@code hash(frozenset())} answers, and that single difference is the whole of
     * why a frozenset is a dictionary key and a set is not. The four in-place operators are absent from the
     * number half as well, which is why {@code f |= other} names a new frozenset instead of complaining.
     *
     * <p>Two rows are the frozenset's own {@code PyMethodDef}s rather than the shared {@code SET_*} ones —
     * {@code __contains__} and {@code copy} — and both carry the sentence the set's version carries, because
     * CPython's clinic blocks repeat it verbatim. The ten method rows are {@code frozenset_methods}, and the
     * one name a reader looks for and does not find is {@code update}: a frozenset has no way to be given
     * more values, only the eight that return a set with them in.
     */
    private static void frozenSetTable(PyDict into) {
        PyType owner = PyType.FROZENSET;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::frozenSetsNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__lt__", LT_DOC, comparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, comparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, comparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, comparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, comparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, comparing(CompareOp.GE));
        table.wrapper("__iter__", ITER_DOC, args -> args[0].iterator());
        table.wrapper("__sub__", SUB_DOC, operating(BinaryOp.SUB));
        table.wrapper("__rsub__", RSUB_DOC, reflectedOperating(BinaryOp.SUB));
        table.wrapper("__and__", AND_DOC, operating(BinaryOp.AND));
        table.wrapper("__rand__", RAND_DOC, reflectedOperating(BinaryOp.AND));
        table.wrapper("__xor__", XOR_DOC, operating(BinaryOp.XOR));
        table.wrapper("__rxor__", RXOR_DOC, reflectedOperating(BinaryOp.XOR));
        table.wrapper("__or__", OR_DOC, operating(BinaryOp.OR));
        table.wrapper("__ror__", ROR_DOC, reflectedOperating(BinaryOp.OR));
        table.wrapper("__len__", LEN_DOC, args -> args[0].length());
        table.named("__contains__");
        table.named(
                "copy", "difference", "intersection", "isdisjoint", "issubset", "issuperset",
                "__reduce__", "__sizeof__", "symmetric_difference", "union");
        table.classMethod(
                "__class_getitem__",
                FROZENSET_CLASS_GETITEM_DOC,
                (args, kwNames, qualified) -> classGetItem(owner, args, kwNames, qualified));
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code int}'s own dictionary: the sixty-two names a number files.
     *
     * <p>Three groups, in the order {@code type_ready} walks the struct. First the constructor, which is
     * {@code int.__new__} written by clinic rather than a {@code tp_new_wrapper} over a slot. Then the
     * forty-two {@code PyNumberMethods} and {@code tp_repr}/{@code tp_hash}/{@code tp_richcompare} rows —
     * twenty-eight of them in the order {@code slotdefs.py} emits, which puts {@code __int__} and {@code
     * __float__} between {@code __ror__} and {@code __floordiv__} because the coercion slots come before
     * the rest of the number block. Then the fourteen {@code long_methods} and the four {@code
     * long_getseters}, and the sentence the type carries for itself.
     *
     * <p>What is <em>not</em> here is worth as much as what is: there is no {@code __str__}, because {@code
     * int} leaves {@code tp_str} to {@code object} and {@code object.__repr__} is the number's own digits
     * through {@code tp_repr}; and there is no {@code __init__}, because a number is already full by the
     * time its creator hands it back. The comparisons are {@code long_richcompare}, which opens with {@code
     * CHECK_BINOP}, so {@code int.__eq__(5, 5.0)} reports the decline the operator would have answered for
     * itself, and the four coercion slots answer with {@code long_long} where CPython does: {@code +5},
     * {@code int(5)} and {@code operator.index(5)} all hand back the very same object, while {@code +True}
     * hands back the integer the bool stands for.
     */
    private static void intTable(PyDict into) {
        PyType owner = PyType.INT;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::intsNew);
        table.wrapper("__repr__", REPR_DOC, TypeDict::integerRepr);
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__lt__", LT_DOC, integerComparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, integerComparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, integerComparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, integerComparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, integerComparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, integerComparing(CompareOp.GE));
        table.wrapper("__add__", ADD_DOC, integerOperating(BinaryOp.ADD));
        table.wrapper("__radd__", RADD_DOC, reflectedIntegerOperating(BinaryOp.ADD));
        table.wrapper("__sub__", SUB_DOC, integerOperating(BinaryOp.SUB));
        table.wrapper("__rsub__", RSUB_DOC, reflectedIntegerOperating(BinaryOp.SUB));
        table.wrapper("__mul__", MUL_DOC, integerOperating(BinaryOp.MUL));
        table.wrapper("__rmul__", RMUL_DOC, reflectedIntegerOperating(BinaryOp.MUL));
        table.wrapper("__mod__", MOD_DOC, integerOperating(BinaryOp.MOD));
        table.wrapper("__rmod__", RMOD_DOC, reflectedIntegerOperating(BinaryOp.MOD));
        table.wrapper("__divmod__", DIVMOD_DOC, TypeDict::dividing);
        table.wrapper("__rdivmod__", RDIVMOD_DOC, TypeDict::reflectedDividing);
        table.wrapper("__pow__", POW_DOC, TypeDict::raising);
        table.wrapper("__rpow__", RPOW_DOC, TypeDict::reflectedRaising);
        table.wrapper("__neg__", NEG_DOC, args -> Numeric.unary(UnaryOp.NEGATIVE, args[0]));
        table.wrapper("__pos__", POS_DOC, args -> Numeric.unary(UnaryOp.POSITIVE, args[0]));
        table.wrapper("__abs__", ABS_DOC, args -> Numeric.unary(UnaryOp.ABSOLUTE, args[0]));
        table.wrapper("__bool__", TRUTH_DOC, args -> args[0].truth());
        table.wrapper("__invert__", INVERT_DOC, args -> Numeric.unary(UnaryOp.INVERT, args[0]));
        table.wrapper("__lshift__", LSHIFT_DOC, integerOperating(BinaryOp.LSHIFT));
        table.wrapper("__rlshift__", RLSHIFT_DOC, reflectedIntegerOperating(BinaryOp.LSHIFT));
        table.wrapper("__rshift__", RSHIFT_DOC, integerOperating(BinaryOp.RSHIFT));
        table.wrapper("__rrshift__", RRSHIFT_DOC, reflectedIntegerOperating(BinaryOp.RSHIFT));
        table.wrapper("__and__", AND_DOC, integerOperating(BinaryOp.AND));
        table.wrapper("__rand__", RAND_DOC, reflectedIntegerOperating(BinaryOp.AND));
        table.wrapper("__xor__", XOR_DOC, integerOperating(BinaryOp.XOR));
        table.wrapper("__rxor__", RXOR_DOC, reflectedIntegerOperating(BinaryOp.XOR));
        table.wrapper("__or__", OR_DOC, integerOperating(BinaryOp.OR));
        table.wrapper("__ror__", ROR_DOC, reflectedIntegerOperating(BinaryOp.OR));
        table.wrapper("__int__", TO_INT_DOC, args -> Numeric.unary(UnaryOp.POSITIVE, args[0]));
        table.wrapper("__float__", TO_FLOAT_DOC, args -> PyFloat.of(Numeric.asDouble(args[0])));
        table.wrapper("__floordiv__", FLOORDIV_DOC, integerOperating(BinaryOp.FLOORDIV));
        table.wrapper("__rfloordiv__", RFLOORDIV_DOC, reflectedIntegerOperating(BinaryOp.FLOORDIV));
        table.wrapper("__truediv__", TRUEDIV_DOC, integerOperating(BinaryOp.TRUEDIV));
        table.wrapper("__rtruediv__", RTRUEDIV_DOC, reflectedIntegerOperating(BinaryOp.TRUEDIV));
        table.wrapper("__index__", INDEX_DOC, args -> Numeric.unary(UnaryOp.POSITIVE, args[0]));
        table.named(
                "conjugate", "bit_length", "bit_count", "to_bytes", "from_bytes", "as_integer_ratio",
                "__trunc__", "__floor__", "__ceil__", "__round__", "__getnewargs__", "__format__",
                "__sizeof__", "is_integer");
        table.getset("real", REAL_DOC, PyInt::longLong, null);
        table.getset("imag", IMAG_DOC, self -> PyInt.ZERO, null);
        table.getset("numerator", NUMERATOR_DOC, PyInt::longLong, null);
        table.getset("denominator", DENOMINATOR_DOC, self -> PyInt.ONE, null);
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code bool}'s own dictionary: the ten names a truth value files, which is all it has of its own.
     *
     * <p>{@code boolobject.c} fills three slots of the number struct and nothing else: {@code nb_invert}
     * and the three bitwise pairs. The consequence is that a bool borrows everything it actually does —
     * {@code bool.__hash__} and {@code bool.__eq__} are {@code int}'s wrappers, and reading them prints
     * {@code of 'int' objects} — and that {@code bool.__dict__} holds neither. Its {@code __new__} is the
     * one constructor here that refuses keywords <em>before</em> it counts arguments, because {@code
     * bool_new} asks {@code _PyArg_NoKeywords} first and {@code PyArg_UnpackTuple} second.
     *
     * <p>The seven bitwise rows are the pair of C functions that answer a bool with a bool and anything
     * else with {@code long}'s slot, so {@code bool.__and__(True, True)} is {@code True} while {@code
     * bool.__and__(True, 2)} is the integer {@code 0}.
     */
    private static void boolTable(PyDict into) {
        PyType owner = PyType.BOOL;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::boolsNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.wrapper("__invert__", INVERT_DOC, args -> Numeric.unary(UnaryOp.INVERT, args[0]));
        table.wrapper("__and__", AND_DOC, boolBit(BinaryOp.AND));
        table.wrapper("__rand__", RAND_DOC, reflectedBoolBit(BinaryOp.AND));
        table.wrapper("__xor__", XOR_DOC, boolBit(BinaryOp.XOR));
        table.wrapper("__rxor__", RXOR_DOC, reflectedBoolBit(BinaryOp.XOR));
        table.wrapper("__or__", OR_DOC, boolBit(BinaryOp.OR));
        table.wrapper("__ror__", ROR_DOC, reflectedBoolBit(BinaryOp.OR));
        table.value("__doc__", docValue(owner));
    }

    /**
     * {@code float}'s own dictionary: the forty-seven names a real number files.
     *
     * <p>Three groups again, in the order {@code type_ready} walks the struct: the constructor, then the
     * thirty {@code PyFloatMethods} rows in the order {@code slotdefs.py} emits them, then the thirteen
     * {@code float_methods} sentences and the two {@code float_getset}. There is no {@code __str__} and
     * no {@code __init__}, for the two reasons {@link #intTable} gives, and there is no {@code
     * __invert__}, {@code __lshift__} or {@code __and__} either: {@code floatobject.c} fills no bitwise slot
     * at all, which is what makes {@code float.__and__(1.5, 3)} a name that is simply not there rather than
     * one that is there and declines.
     *
     * <p>Two of the thirty are the identity cases. {@code nb_positive}, {@code tp_float}, {@code
     * float.conjugate} and the {@code real} read are all the very same C function, {@code float_float}, and
     * each hands back the value it was given rather than a copy — {@code +x is x} and {@code x.real is x}
     * both say true — while {@code imag} is the one of the four that builds a fresh zero. {@code nb_int} is
     * {@code float.__trunc__} and nothing else, which is why the wrapper truncates toward zero and refuses
     * the two values that have no integer part.
     *
     * <p>The comparisons are {@code float_richcompare}, which opens with {@code CHECK_BINOP} like {@code
     * long_richcompare} does, so {@code float.__eq__(1.5, 'x')} reports the decline; what that function does
     * with an integer it cannot name is the four-test ladder in {@link Numeric#floatCompare}, and it is the
     * reason {@code 9007199254740992.0 == 2**53 + 1} is false where the two are the same double.
     */
    private static void floatTable(PyDict into) {
        PyType owner = PyType.FLOAT;
        Table table = new Table(owner, into);
        table.raw("__new__", NEW_DOC, TypeDict::floatsNew);
        table.wrapper("__repr__", REPR_DOC, args -> PyStr.of(args[0].repr()));
        table.wrapper("__hash__", HASH_DOC, args -> PyInt.of(args[0].hash()));
        table.wrapper("__lt__", LT_DOC, floatComparing(CompareOp.LT));
        table.wrapper("__le__", LE_DOC, floatComparing(CompareOp.LE));
        table.wrapper("__eq__", EQ_DOC, floatComparing(CompareOp.EQ));
        table.wrapper("__ne__", NE_DOC, floatComparing(CompareOp.NE));
        table.wrapper("__gt__", GT_DOC, floatComparing(CompareOp.GT));
        table.wrapper("__ge__", GE_DOC, floatComparing(CompareOp.GE));
        table.wrapper("__add__", ADD_DOC, floatOperating(BinaryOp.ADD));
        table.wrapper("__radd__", RADD_DOC, reflectedFloatOperating(BinaryOp.ADD));
        table.wrapper("__sub__", SUB_DOC, floatOperating(BinaryOp.SUB));
        table.wrapper("__rsub__", RSUB_DOC, reflectedFloatOperating(BinaryOp.SUB));
        table.wrapper("__mul__", MUL_DOC, floatOperating(BinaryOp.MUL));
        table.wrapper("__rmul__", RMUL_DOC, reflectedFloatOperating(BinaryOp.MUL));
        table.wrapper("__mod__", MOD_DOC, floatOperating(BinaryOp.MOD));
        table.wrapper("__rmod__", RMOD_DOC, reflectedFloatOperating(BinaryOp.MOD));
        table.wrapper("__divmod__", DIVMOD_DOC, TypeDict::floatDividing);
        table.wrapper("__rdivmod__", RDIVMOD_DOC, TypeDict::reflectedFloatDividing);
        table.wrapper("__pow__", POW_DOC, TypeDict::floatRaising);
        table.wrapper("__rpow__", RPOW_DOC, TypeDict::reflectedFloatRaising);
        table.wrapper("__neg__", NEG_DOC, args -> Numeric.unary(UnaryOp.NEGATIVE, args[0]));
        table.wrapper("__pos__", POS_DOC, args -> Numeric.unary(UnaryOp.POSITIVE, args[0]));
        table.wrapper("__abs__", ABS_DOC, args -> Numeric.unary(UnaryOp.ABSOLUTE, args[0]));
        table.wrapper("__bool__", TRUTH_DOC, args -> args[0].truth());
        table.wrapper(
                "__int__",
                TO_INT_DOC,
                args -> Numeric.longFromDouble(((PyFloat) args[0]).getValue()));
        table.wrapper("__float__", TO_FLOAT_DOC, args -> Numeric.floatFloat(args[0]));
        table.wrapper("__floordiv__", FLOORDIV_DOC, floatOperating(BinaryOp.FLOORDIV));
        table.wrapper("__rfloordiv__", RFLOORDIV_DOC, reflectedFloatOperating(BinaryOp.FLOORDIV));
        table.wrapper("__truediv__", TRUEDIV_DOC, floatOperating(BinaryOp.TRUEDIV));
        table.wrapper("__rtruediv__", RTRUEDIV_DOC, reflectedFloatOperating(BinaryOp.TRUEDIV));
        table.named(
                "from_number",
                "conjugate",
                "__trunc__",
                "__floor__",
                "__ceil__",
                "__round__",
                "as_integer_ratio",
                "fromhex",
                "hex",
                "is_integer",
                "__getnewargs__",
                "__getformat__",
                "__format__");
        table.getset("real", REAL_DOC, Numeric::floatFloat, null);
        table.getset("imag", IMAG_DOC, self -> PyFloat.of(0.0), null);
        table.value("__doc__", docValue(owner));
    }

    /** {@code float_richcompare}: the gate is {@code CHECK_BINOP}, so a non-number is declined. */
    private static Method floatComparing(CompareOp cmp) {
        return (args, kwNames, qualified) -> Numeric.floatCompare(cmp, args[0], args[1]);
    }

    /** {@code float_add} and its seven siblings: the two-operand slot of the receiver, decline included. */
    private static Method floatOperating(BinaryOp op) {
        return (args, kwNames, qualified) -> Numeric.floatSlot(op, args[0], args[1]);
    }

    /** {@code RBINSLOT}'s swap: the same C function with the operands the other way round. */
    private static Method reflectedFloatOperating(BinaryOp op) {
        return (args, kwNames, qualified) -> Numeric.floatSlot(op, args[1], args[0]);
    }

    /** {@code float_divmod}: the pair, which no {@link BinaryOp} computes. */
    private static PyObject floatDividing(PyObject[] args, String[] kwNames, String qualified) {
        return Numeric.floatDivmod(args[0], args[1]);
    }

    private static PyObject reflectedFloatDividing(
            PyObject[] args, String[] kwNames, String qualified) {
        return Numeric.floatDivmod(args[1], args[0]);
    }

    /** {@code float_pow}: the third argument may only be the absence it defaults to. */
    private static PyObject floatRaising(PyObject[] args, String[] kwNames, String qualified) {
        return Numeric.floatPower(args[0], args[1], modulus(args));
    }

    private static PyObject reflectedFloatRaising(
            PyObject[] args, String[] kwNames, String qualified) {
        return Numeric.floatPower(args[1], args[0], modulus(args));
    }

    /** {@code float_new} over a {@code tp_new} reached through the dictionary: the class gates first. */
    private static PyObject floatsNew(PyObject[] args, String[] kwNames, String qualified) {
        declaredClass(PyType.FLOAT, args, kwNames);
        return Constructors.floating(tail(args, kwNames), kwNames);
    }

    /** {@code long_repr}: the digits of the value, which a bool is asked for as the integer it stands for. */
    private static PyObject integerRepr(PyObject[] args) {
        return PyStr.of(Numbers.asInteger(args[0]).toString());
    }

    /**
     * {@code long_new} over a {@code tp_new_wrapper}: the class gates, then clinic's own argument table.
     *
     * <p>The arguments after the class reach the constructor rather than the vectorcall fast path, which is
     * what makes the two spellings of an over-long call disagree: {@code int(2, 3, 4)} says {@code int
     * expected at most 2 arguments, got 3} and {@code int.__new__(int, 2, 3, 4)} says {@code int() takes at
     * most 2 arguments (3 given)}.
     */
    private static PyObject intsNew(PyObject[] args, String[] kwNames, String qualified) {
        declaredClass(PyType.INT, args, kwNames);
        return Constructors.parsedInteger(tail(args, kwNames), kwNames);
    }

    /**
     * {@code bool_new}: keywords, then count, then the truth of whatever was left.
     *
     * <p>Both refusals name {@code bool} rather than the class handed over, and neither of them is about the
     * value: {@code bool.__new__(bool, [])} is {@code False} because {@code PyObject_IsTrue} says so, and
     * the answer is always one of the two singletons, so {@code bool.__new__(bool, 1) is True} holds however
     * the number was written. A call that gave nothing asks the truth of {@code bool_new}'s own default,
     * which is {@code Py_False} — the same answer {@code bool()} gives, and not the truth of the class.
     */
    private static PyObject boolsNew(PyObject[] args, String[] kwNames, String qualified) {
        declaredClass(PyType.BOOL, args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("bool() takes no keyword arguments");
        }
        int given = Arguments.positional(args, kwNames) - 1;
        if (given > 1) {
            throw Slots.typeError("bool expected at most 1 argument, got %d", given);
        }
        return PyBool.of(given > 0 && Slots.truth(args[1]));
    }

    /** {@code long_richcompare}: the gate is {@code CHECK_BINOP}, so a float pair is declined. */
    private static Method integerComparing(CompareOp cmp) {
        return (args, kwNames, qualified) -> Numeric.integerCompare(cmp, args[0], args[1]);
    }

    /** {@code long_<em>op</em>}: the two-operand slot of the receiver, decline included. */
    private static Method integerOperating(BinaryOp op) {
        return (args, kwNames, qualified) -> Numeric.integerSlot(op, args[0], args[1]);
    }

    /** {@code RBINSLOT}'s swap: the same C function with the operands the other way round. */
    private static Method reflectedIntegerOperating(BinaryOp op) {
        return (args, kwNames, qualified) -> Numeric.integerSlot(op, args[1], args[0]);
    }

    /** {@code long_divmod}: the pair, which no {@link BinaryOp} computes. */
    private static PyObject dividing(PyObject[] args, String[] kwNames, String qualified) {
        return Numeric.integerDivmod(args[0], args[1]);
    }

    private static PyObject reflectedDividing(PyObject[] args, String[] kwNames, String qualified) {
        return Numeric.integerDivmod(args[1], args[0]);
    }

    /**
     * {@code long_pow}: the one wrapper handed a third argument, which may be {@code None}.
     *
     * <p>{@code wrap_ternaryfunc} reads it out of a one-or-two-long argument list and defaults it to {@code
     * Py_None}, which is why {@code int.__pow__(2, 3)} and {@code int.__pow__(2, 3, None)} answer the same
     * eight and why the count this wrapper refuses is stated as a range: {@code expected 1 or 2 arguments,
     * got 0}.
     */
    private static PyObject raising(PyObject[] args, String[] kwNames, String qualified) {
        return Builtins.integerPower(args[0], args[1], modulus(args));
    }

    private static PyObject reflectedRaising(PyObject[] args, String[] kwNames, String qualified) {
        return Builtins.integerPower(args[1], args[0], modulus(args));
    }

    /** The third argument of a power slot, which {@code wrap_ternaryfunc} leaves as {@code None}. */
    private static PyObject modulus(PyObject[] args) {
        return args.length > 2 ? args[2] : PyNone.INSTANCE;
    }

    /** {@code bool_and} and its two siblings: a bool out of two bools, {@code long}'s slot out of any other. */
    private static Method boolBit(BinaryOp op) {
        return (args, kwNames, qualified) -> bitwiseOfTruth(op, args[0], args[1]);
    }

    private static Method reflectedBoolBit(BinaryOp op) {
        return (args, kwNames, qualified) -> bitwiseOfTruth(op, args[1], args[0]);
    }

    /**
     * The bitwise slot of {@code boolobject.c}, which is written twice over.
     *
     * <p>The pair of tests is what a reader would expect and the fall-through is not: a bool asked of a
     * float declines, because {@code long_and} has nothing to read, but a bool asked of a number answers
     * with an {@code int} — {@code True & 2} is {@code 0} and not {@code False} — so the only calls that
     * keep the answer a truth value are the ones where both sides already are one.
     */
    private static PyObject bitwiseOfTruth(BinaryOp op, PyObject left, PyObject right) {
        if (left instanceof PyBool first && right instanceof PyBool second) {
            return PyBool.of(
                    switch (op) {
                        case AND -> first.getValue() && second.getValue();
                        case OR -> first.getValue() || second.getValue();
                        default -> first.getValue() != second.getValue();
                    });
        }
        return Numeric.integerSlot(op, left, right);
    }

    /**
     * {@code set_new}: the class is checked and the rest of the call is thrown away.
     *
     * <p>{@code set_new} hands {@code make_new_set} a null iterable whatever it was given, so {@code
     * set.__new__(set, 'ab')} is an empty set where {@code set('ab')} is two characters, and {@code
     * set.__new__(set, x=1)} answers without so much as refusing the keyword. It is the only constructor in
     * this family that ignores an argument outright: {@code list_new} and {@code dict_new} still read one to
     * size the value, and {@code frozenset_new} parses it because a frozen value has to be full before it
     * can be hashed.
     */
    private static PyObject setsNew(PyObject[] args, String[] kwNames, String qualified) {
        declaredClass(PyType.SET, args, kwNames);
        return PySet.empty();
    }

    /**
     * {@code frozenset_new}: one argument, no keywords, and the argument read by the value being made.
     *
     * <p>The two refusals come in CPython's order and name different things. Keywords are refused for the
     * class whose dictionary holds the slot — and only for a class that does not bring its own {@code
     * tp_init}, which is what lets a subclass of {@code frozenset} have an initializer of its own — while
     * the count is unpacked against {@code type->tp_name}, the class that was handed over. What is left is
     * {@code make_new_frozenset}, whose one extra rule is that a frozenset handed to its own type comes back
     * as itself.
     */
    private static PyObject frozenSetsNew(PyObject[] args, String[] kwNames, String qualified) {
        declaredClass(PyType.FROZENSET, args, kwNames);
        Arguments.noKeywords("frozenset()", kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.atMost(PyType.FROZENSET.name(), rest, kwNames, 1);
        return PyFrozenSet.collected(rest.length == 0 ? null : rest[0]);
    }

    /**
     * {@code set_init}: emptied first, then filled, in that order.
     *
     * <p>Both refusals come before anything is touched — the keyword one names the class the slot was
     * written for and the count one the receiver's own type, because {@code PyArg_UnpackTuple} is handed
     * {@code Py_TYPE(self)->tp_name}. After them the values already in the set go, which is what makes
     * {@code s.__init__(s)} an empty set where {@code s.union(s)} holds everything it started with, and
     * {@code s.__init__(5)} a refusal that still leaves an empty set behind. {@code frozenset} files no such
     * name: its {@code tp_init} is null, so {@code f.__init__([1])} is {@code object}'s answer of {@code
     * None} and a frozenset that keeps whatever it had.
     */
    private static PyObject setInit(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.noKeywords("set()", kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.atMost(args[0].getType().name(), rest, kwNames, 1);
        ((PySet) args[0]).initialized(rest.length == 0 ? null : rest[0]);
        return PyNone.INSTANCE;
    }

    /**
     * {@code nb_subtract} and its three siblings, filed as the slots the number protocol copies.
     *
     * <p>A set's operators are the only ones here to arrive through {@code tp_as_number}, and they gate on
     * their operand the way {@code set_sub} does: {@code s - [1]} is a decline, not an iterable error, so
     * the operator goes on to try the list's own {@code __rsub__} and reports the pair it could not make
     * work. {@code s.union([1])} takes the same list without complaint, and that is the whole difference
     * between the method and the slot.
     */
    private static Method operating(BinaryOp op) {
        return (args, kwNames, qualified) -> args[0].binaryOp(op, args[1]);
    }

    /** {@code RBINSLOT} over the same four C functions, with the operands the other way round. */
    private static Method reflectedOperating(BinaryOp op) {
        return (args, kwNames, qualified) -> args[1].binaryOp(op, args[0]);
    }

    /** {@code nb_inplace_*}: the receiver changed and handed back, which is what {@code s -= t} binds. */
    private static Method operatingInPlace(BinaryOp op) {
        return (args, kwNames, qualified) -> args[0].inplaceOp(op, args[1]);
    }

    /**
     * {@code dict_init}: the one argument the constructor takes, merged into a dictionary that already
     * exists.
     *
     * <p>Not a clear-then-fill: {@code dict_update_common} merges over the receiver, which is why {@code
     * d.__init__()} leaves {@code d} exactly as it was and {@code d.__init__(d)} is a no-op rather than an
     * accident. The complaint about a second positional argument names the class rather than the method,
     * because {@code dict_init} passes {@code "dict"} as the name {@code _PyArg_CheckPositional} writes.
     */
    private static PyObject dictInit(PyObject[] args, String[] kwNames, String qualified) {
        ((PyDict) args[0]).mergeFrom(tail(args, kwNames), kwNames, "dict");
        return PyNone.INSTANCE;
    }

    /**
     * {@code dict_new}: the class is checked and nothing else is read.
     *
     * <p>{@code dict.__new__(dict, 'ab')} is an empty dict where {@code dict('ab')} is a {@code
     * TypeError}, because the constructor also runs {@code dict_init} over the argument and the slot does
     * not — the same difference {@code list} files, and the same one {@code int} and {@code str} do not,
     * since those two need their argument just to know how big to make the value.
     */
    private static PyObject dictsNew(PyObject[] args, String[] kwNames, String qualified) {
        declaredClass(PyType.DICT, args, kwNames);
        return PyDict.empty();
    }

    /**
     * {@code nb_or}, {@code nb_inplace_or} and the {@code RBINSLOT} beside them.
     *
     * <p>PEP 584 gave a mapping three of the four, and the reflected one is the odd pair: {@code
     * d.__ror__(e)} is CPython handing {@code nb_or} its two operands the other way round, so the answer
     * starts from {@code e}, keeps {@code e}'s order, and lets the entries of {@code d} override — the order
     * the two dictionaries were asked, not the order they were written.
     */
    private static PyObject merged(PyObject[] args) {
        return args[0].binaryOp(BinaryOp.OR, args[1]);
    }

    private static PyObject reflectedMerge(PyObject[] args) {
        return args[1].binaryOp(BinaryOp.OR, args[0]);
    }

    /** {@code nb_inplace_or}: the receiver, merged into, and handed back as the same dictionary. */
    private static PyObject mergedInPlace(PyObject[] args) {
        return args[0].inplaceOp(BinaryOp.OR, args[1]);
    }

    /**
     * {@code list(iterable)} as a slot wrapper: the same one argument the constructor takes, read as the slot
     * takes it.
     *
     * <p>{@code list___init___impl} parses the argument, empties the receiver and then extends it, so the
     * iterable is read <em>after</em> anything was thrown away — which is why {@code l.__init__(l)} is an
     * empty list where {@code l.extend(l)} doubles one, and why {@code l.__init__(5)} refuses and still
     * leaves an empty list behind. The two refusals come first, so a wrong count changes nothing.
     */
    private static PyObject listInit(PyObject[] args, String[] kwNames, String qualified) {
        PyList list = (PyList) args[0];
        list.initialized(Constructors.listed(tail(args, kwNames), kwNames));
        return PyNone.INSTANCE;
    }

    /**
     * {@code list.__new__}: the class is checked and the rest of the call is thrown away.
     *
     * <p>{@code list_new} reads one argument, and only to work out how much to allocate — so {@code
     * list.__new__(list, 'ab')} is an empty list where {@code list('ab')} is {@code ['a', 'b']}, the
     * difference being that the constructor also runs {@code __init__} and the slot does not.
     */
    private static PyObject listsNew(PyObject[] args, String[] kwNames, String qualified) {
        declaredClass(PyType.LIST, args, kwNames);
        return PyList.empty();
    }

    private static PyObject tuplesNew(PyObject[] args, String[] kwNames, String qualified) {
        return constructorNew(PyType.TUPLE, args, kwNames);
    }

    /**
     * {@code C[key]} for the types that file {@code __class_getitem__}: the key, kept as the arguments.
     *
     * <p>Nothing about the key is checked, which is why {@code list[3]} is an alias of {@code 3} and not a
     * complaint; a tuple key is the argument list itself, so {@code list[()]} keeps none. The class the read
     * bound to is the origin, and that is the only part of the answer that has to be a class.
     *
     * <p>A {@code classmethod_descriptor} is called through {@code classmethoddescr_call}, which takes the
     * class out of the argument list before the body counts anything — so an entry read straight from the
     * dictionary and given nothing complains that the descriptor needs an argument, one given a non-class says
     * the class would have been its second, and one given a class that could never hold the values it builds
     * says so before the key is read at all. A read through the type supplies that class itself, and the
     * refusals after that point are the wrapped {@code METH_O}'s own.
     */
    private static PyObject classGetItem(
            PyType declaring, PyObject[] args, String[] kwNames, String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError(
                    "descriptor '%s' of '%s' object needs an argument", name, declaring.name());
        }
        if (!(args[0] instanceof PyType origin)) {
            throw Slots.typeError(
                    "descriptor '%s' for type '%s' needs a type, not a '%s' as arg 2",
                    name, declaring.filedName(), args[0].getType().filedName());
        }
        if (!origin.matches(declaring)) {
            throw Slots.typeError(
                    "descriptor '%s' requires a subtype of '%s' but received '%s'",
                    name, declaring.filedName(), origin.filedName());
        }
        Arguments.exactlyOne(qualified + "()", tail(args, kwNames), kwNames);
        return PyGenericAlias.of(origin, args[1]);
    }

    /** {@code sq_inplace_concat}, filed as {@code __iadd__}: the receiver, extended. */
    private static PyObject joinedInPlace(PyObject[] args) {
        return args[0].inplaceOp(BinaryOp.ADD, args[1]);
    }

    /** {@code sq_inplace_repeat}, filed as {@code __imul__}: the count is read as an index first. */
    private static PyObject repeatedInPlace(PyObject[] args) {
        if (Numbers.asInteger(args[1]) == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", args[1].getType().name());
        }
        return args[0].inplaceOp(BinaryOp.MUL, args[1]);
    }

    /**
     * {@code tp_new_wrapper} over a value type: the four gates, then the constructor.
     *
     * <p>The gates are {@link #declaredClass}'s, and every one of the four refusals names the type whose
     * dictionary holds them rather than the one handed over: {@code bytes.__new__(int, 3)} says {@code int}
     * is not a subtype of {@code bytes}. What is left then goes to that type's own constructor, which is what
     * makes {@code bytes.__new__(bytes, 'ab', 'utf8')} an octet run and {@code bytes.__new__(bytes, 'ab')}
     * the {@code TypeError} about a missing encoding.
     */
    private static PyObject constructorNew(PyType declaring, PyObject[] args, String[] kwNames) {
        declaredClass(declaring, args, kwNames);
        return Constructors.instantiate(
                declaring, tail(args, kwNames), PyBoundMethod.keywordTuple(kwNames));
    }

    /**
     * {@code tp_new_wrapper}'s four refusals, before any class is asked to build anything.
     *
     * <p>The first three name the type whose dictionary holds the {@code __new__} rather than the one
     * handed over: {@code bytes.__new__(int, 3)} says {@code int} is not a subtype of {@code bytes}, and a
     * call with nothing at all says the type's own {@code __new__} was not given enough arguments.
     *
     * <p>The fourth reads the creator behind the name. {@code tp_new_wrapper} walks back from the subtype
     * to the nearest class whose {@code tp_new} is written in C and refuses when that creator is not the
     * one the call was made through — so {@code int.__new__(bool)} complains that {@code bool} builds its
     * values a different way, while {@code Exception.__new__(ValueError, 'x')} answers because the two
     * exception classes hold the very same {@code BaseException_new}. What is left after the four is the
     * subtype the constructor is asked to build.
     */
    private static PyType declaredClass(PyType declaring, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError("%s.__new__(): not enough arguments", declaring.name());
        }
        if (!(args[0] instanceof PyType subtype)) {
            throw Slots.typeError(
                    "%s.__new__(X): X is not a type object (%s)",
                    declaring.name(), args[0].getType().name());
        }
        if (!subtype.matches(declaring)) {
            throw Slots.typeError(
                    "%s.__new__(%s): %s is not a subtype of %s",
                    declaring.name(), subtype.name(), subtype.name(), declaring.name());
        }
        PyType staticBase = staticBaseOf(subtype);
        if (staticBase.declaresNoInstances()) {
            // The walk reached a class with no creator at all — the one refusal that names nothing else.
            throw Slots.typeError("cannot create '%s' instances", staticBase.filedName());
        }
        if (creatorOf(staticBase) != creatorOf(declaring)) {
            throw Slots.typeError(
                    "%s.__new__(%s) is not safe, use %s.__new__()",
                    declaring.name(), subtype.name(), staticBase.name());
        }
        return subtype;
    }

    /**
     * CPython's {@code staticbase} walk: the nearest class in the chain whose creator is written in C.
     *
     * <p>A class Python wrote with a {@code __new__} of its own has {@code slot_tp_new}, which is a
     * trampoline rather than a creator and is stepped over; a class that inherited a creator keeps it.
     * That is what makes {@code object.__new__(Plain)} allowed — {@code Plain} holds {@code object_new}
     * itself, so the two creators are one — while {@code object.__new__(E)} of an {@code E(Exception)} is
     * refused, and refuses under the name of the class the walk stopped at rather than of any of the ones
     * the call could have named.
     */
    private static PyType staticBaseOf(PyType kind) {
        for (PyType base = kind; base != null; base = base.parent()) {
            if (!runsPythonNew(base)) {
                return base;
            }
        }
        // `object` files a creator written in C, so a class with a parent chain always reaches one.
        throw Slots.runtimeError("no C creator behind '" + kind.name() + "'");
    }

    /**
     * Whether {@code tp_new} for this class is {@code slot_tp_new}, which is what {@code update_one_slot}
     * files whenever the {@code __new__} the order finds is not a creator's own wrapper.
     *
     * <p>The distinction is the entry rather than the name: {@code object} and {@code Exception} both
     * file {@code __new__} and both leave the slot in C, because what their dictionaries hold is the
     * {@code builtin_function_or_method} that wraps a creator. A Python function, a {@code staticmethod}
     * or a {@code classmethod} in that position is the trampoline.
     */
    private static boolean runsPythonNew(PyType kind) {
        if (!(kind instanceof PyClass)) {
            return false;
        }
        for (PyType owner : kind.mro()) {
            PyObject filed = owner.namespace().get(PyStr.interned("__new__"));
            if (filed != null) {
                return !(filed instanceof Member member && member.shape == Shape.RAW);
            }
        }
        return false;
    }

    /**
     * The class whose C creator {@code tp_new} holds for this type, which is how two siblings are compared.
     *
     * <p>A creator is shared more often than a name is: {@code BaseException_new} is the {@code tp_new} of
     * every exception that declares no behaviour of its own, {@code OSError_new} that of the whole {@code
     * OSError} subtree, and a class Python wrote that declares nothing keeps the pointer it was born with.
     * That is what lets {@code Exception.__new__(ValueError, 'x')} answer while {@code
     * BaseException.__new__(OSError)} refuses, in spite of an {@code OSError} being one of those too.
     */
    private static PyType creatorOf(PyType kind) {
        if (kind.matches(ExceptionTypes.OS_ERROR)) {
            return ExceptionTypes.OS_ERROR;
        }
        if (kind.matches(ExceptionTypes.BASE_EXCEPTION)) {
            return ExceptionTypes.BASE_EXCEPTION;
        }
        if (kind instanceof PyClass) {
            // An inherited pointer, which names the class it was copied from rather than this one.
            return creatorOf(kind.parent());
        }
        return kind;
    }

    private static PyObject octetsNew(PyObject[] args, String[] kwNames, String qualified) {
        return constructorNew(PyType.BYTES, args, kwNames);
    }

    private static PyObject textNew(PyObject[] args, String[] kwNames, String qualified) {
        return constructorNew(PyType.STR, args, kwNames);
    }

    /**
     * {@code tp_richcompare} as a slot wrapper: the value's own comparison, decline included.
     *
     * <p>The answer is what the slot gives and nothing is made of it afterwards, which is what puts
     * {@code NotImplemented} in front of a program that asks for the slot rather than running the
     * operator: {@code b'ab' < 3} is refused by the driver that tries the reflected slot next, while
     * {@code b'ab'.__lt__(3)} reports what the slot itself declined.
     */
    private static Method comparing(CompareOp cmp) {
        return (args, kwNames, qualified) -> args[0].richCompare(cmp, args[1]);
    }

    /** {@code sq_concat}, and {@code sq_repeat} in the same shape: the forward slot of the receiver. */
    private static PyObject joined(PyObject[] args) {
        return args[0].binaryOp(BinaryOp.ADD, args[1]);
    }

    private static PyObject modulo(PyObject[] args) {
        return args[0].binaryOp(BinaryOp.MOD, args[1]);
    }

    /**
     * {@code RBINSLOT}'s reflected wrapper, which hands the C function its operands the other way round.
     *
     * <p>That swap is the whole of {@code __rmod__}: {@code '%s'.__rmod__('ab')} is {@code 'ab' % '%s'} and
     * not a second way of asking the left-hand string, and a value on the right whose type has no modulo
     * slot at all leaves the wrapper with nothing to report but the decline.
     */
    private static PyObject reflectedMod(PyObject[] args) {
        PyObject other = args[1];
        return other instanceof PyStr || other instanceof PyBytes
                ? other.binaryOp(BinaryOp.MOD, args[0])
                : PyNotImplemented.INSTANCE;
    }

    /**
     * {@code sq_repeat}: the count arrives as an index, which the operator's wording is not.
     *
     * <p>{@code b'ab' * 1.5} complains about multiplying a sequence, because the operator tries the int's
     * own {@code __mul__} and reports the pair that failed; {@code b'ab'.__mul__(1.5)} is the slot asked
     * directly and says only what it could not read. The number itself is then the value's to check, so an
     * index too wide for a machine word keeps {@link PyBytes}'s own refusal.
     */
    private static PyObject repeated(PyObject[] args) {
        if (Numbers.asInteger(args[1]) == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", args[1].getType().name());
        }
        return args[0].binaryOp(BinaryOp.MUL, args[1]);
    }

    /**
     * {@code wrap_buffer}: the one private slot a value type files, and the window its flags keep.
     *
     * <p>Three refusals arrive before any buffer is made, and all three are printable. A number that is
     * not one is {@code PyNumber_AsSsize_t}'s complaint about interpretation; a number too wide for a
     * machine word is the same complaint as an overflow, which is why {@code __buffer__(2 ** 70)} says
     * "index-sized" where {@code __buffer__(2 ** 31)} — a number that fits the word but not the {@code int}
     * the flags are kept in — says they are out of range. What is left is the flags themselves, and a
     * bytes or a str asks that its memory not be written to: {@code b''.__buffer__(-1)} asks for every flag
     * there is, including that one, and is refused as not writable.
     *
     * <p>The buffer a valid call would answer with is this runtime's gap: there is no {@code memoryview}
     * yet, and so no object to hand back.
     */
    private static PyObject exposeBuffer(PyObject[] args, String[] kwNames, String qualified) {
        java.math.BigInteger flags = Numbers.asInteger(args[1]);
        if (flags == null) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", args[1].getType().name());
        }
        if (flags.bitLength() > 63) {
            throw Slots.overflowError("cannot fit 'int' into an index-sized integer");
        }
        if (flags.bitLength() > 31) {
            throw Slots.overflowError("buffer flags out of range");
        }
        if ((flags.intValue() & 1) != 0) {
            throw Slots.bufferError("Object is not writable.");
        }
        throw Slots.notImplemented("memoryview");
    }

    /**
     * The dictionary one class of the exception family files.
     *
     * <p>The order is CPython's {@code type_ready_fill_dict}: {@code add_operators} first and so the slot
     * wrappers ahead of everything else, {@code tp_new}'s row over the class before them, then {@code
     * tp_methods}, {@code tp_members}, {@code tp_getset}, and the sentence last. {@code BaseException}'s
     * fifteen names, {@code KeyError}'s two and {@code OSError}'s ten are all of that; every other class of
     * the family files what it inherits and its own {@code __doc__}, except the ones that keep fields of
     * their own — {@code AttributeError} and {@code NameError}, {@code StopIteration} and {@code
     * SystemExit}, {@code ImportError}, {@code SyntaxError} and the three Unicode errors — and except the
     * two groups, which are the family's odd pair: {@code BaseExceptionGroup} writes {@code tp_repr} as
     * well as {@code tp_str} and so files two {@code wrapper_descriptor}s where its siblings file one, and
     * {@code ExceptionGroup} is the only class of the family made by {@code PyErr_NewException} rather than
     * declared as a static struct, which is why it is the only one whose dictionary holds a
     * {@code __module__} of its own.
     */
    private static void exceptionTable(PyType owner, PyDict into) {
        Table table = new Table(owner, into);
        if (owner == ExceptionTypes.BASE_EXCEPTION_GROUP) {
            table.put("__new__", GROUP_NEW);
            table.wrapper(
                    "__repr__", REPR_DOC, args -> PyStr.of(((PyBaseException) args[0]).represented()));
            table.wrapper(
                    "__str__", STR_DOC, args -> PyStr.of(((PyBaseException) args[0]).described()));
            table.put("__init__", GROUP_INIT);
            table.classMethod(
                    "__class_getitem__",
                    GROUP_CLASS_GETITEM_DOC,
                    (args, kwNames, qualified) -> classGetItem(owner, args, kwNames, qualified));
            table.put("derive", GROUP_DERIVE);
            table.put("split", GROUP_SPLIT);
            table.put("subgroup", GROUP_SUBGROUP);
            table.member(
                    "message",
                    "exception message",
                    self -> ((PyBaseExceptionGroup) self).groupMessage(),
                    null);
            table.member(
                    "exceptions",
                    "nested exceptions",
                    self -> ((PyBaseExceptionGroup) self).children(),
                    null);
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.BASE_EXCEPTION) {
            table.put("__new__", EXCEPTION_NEW);
            table.wrapper(
                    "__repr__", REPR_DOC, args -> PyStr.of(((PyBaseException) args[0]).represented()));
            table.wrapper(
                    "__str__", STR_DOC, args -> PyStr.of(((PyBaseException) args[0]).described()));
            table.put("__init__", EXCEPTION_INIT);
            table.put("__reduce__", EXCEPTION_REDUCE);
            table.put("__setstate__", EXCEPTION_SETSTATE);
            table.put("with_traceback", WITH_TRACEBACK);
            table.put("add_note", ADD_NOTE);
            // The five fields CPython keeps in `PyBaseExceptionObject`, filed in the order its tables put
            // them: `BaseException_members` before `BaseException_getset`, and inside the getsets
            // `__dict__` first because that is where the `PyGetSetDef` writes it.
            table.member(
                    "__suppress_context__",
                    self -> ((PyBaseException) self).suppressContext(),
                    (self, value) -> ((PyBaseException) self).writeSuppressContext(value));
            // The one storage `BaseException` keeps in a dictionary rather than in a C field, and the
            // reason a class under `Exception` files `__weakref__` but no `__dict__` of its own. CPython
            // declares no sentence beside it, and its deleting half refuses by name rather than by type.
            table.getset(
                    "__dict__",
                    null,
                    self -> ((PyBaseException) self).held(),
                    (self, value) -> ((PyBaseException) self).replaceDict(value));
            table.getset(
                    "args",
                    null,
                    self -> ((PyBaseException) self).args(),
                    (self, value) -> ((PyBaseException) self).writeArguments(value));
            table.getset(
                    "__traceback__",
                    null,
                    self -> ((PyBaseException) self).traceback(),
                    (self, value) -> ((PyBaseException) self).writeTraceback(value));
            table.getset(
                    "__context__",
                    null,
                    self -> ((PyBaseException) self).context(),
                    (self, value) -> ((PyBaseException) self).writeContext(value));
            table.getset(
                    "__cause__",
                    null,
                    self -> ((PyBaseException) self).cause(),
                    (self, value) -> ((PyBaseException) self).writeCause(value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.KEY_ERROR) {
            // A class of the family that writes a `tp_str` of its own, so the wrapper it files is a row
            // here rather than the base one it would otherwise inherit. `add_operators` puts that row
            // ahead of everything the class declares itself, and `KeyError` declares nothing else — which
            // is why `__str__` is the only name here besides the sentence.
            table.wrapper("__str__", STR_DOC, args -> PyStr.of(((PyBaseException) args[0]).keyErrorText()));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.OS_ERROR) {
            table.raw(
                    "__new__",
                    NEW_DOC,
                    (args, kwNames, qualified) -> exceptionNew(owner, args, kwNames));
            table.wrapper(
                    "__str__", STR_DOC, args -> PyStr.of(((PyOSError) args[0]).errorText()));
            table.wrapper("__init__", INIT_DOC, TypeDict::oSErrorInit);
            table.put("__reduce__", OS_ERROR_REDUCE);
            // The four offsets and the one getset, in the order the two tables put them: `OSError_members`
            // before `OSError_getset`, and the members in the order `PyOSErrorObject` declares its fields.
            // The getset carries no sentence, as CPython's `PyGetSetDef` writes none for it.
            table.member(
                    "errno",
                    "POSIX exception code",
                    self -> ((PyOSError) self).memberField("errno"),
                    (self, value) -> ((PyOSError) self).writeMemberField("errno", value));
            table.member(
                    "strerror",
                    "exception strerror",
                    self -> ((PyOSError) self).memberField("strerror"),
                    (self, value) -> ((PyOSError) self).writeMemberField("strerror", value));
            table.member(
                    "filename",
                    "exception filename",
                    self -> ((PyOSError) self).memberField("filename"),
                    (self, value) -> ((PyOSError) self).writeMemberField("filename", value));
            table.member(
                    "filename2",
                    "second exception filename",
                    self -> ((PyOSError) self).memberField("filename2"),
                    (self, value) -> ((PyOSError) self).writeMemberField("filename2", value));
            table.getset(
                    "characters_written",
                    null,
                    self -> ((PyOSError) self).charactersWritten(),
                    (self, value) -> ((PyOSError) self).writeCharactersWritten(value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.ATTRIBUTE_ERROR) {
            // The two fields the class keeps, the state that carries one of them, and an initialiser that
            // reads its names out of a table no positional can reach — the three things that make {@code
            // AttributeError('a', name='n')} and {@code NameError('a', name='n')} the same call in two
            // different classes, and the reason this is the one leaf of the family with a `__getstate__`
            // of its own filed beside it.
            table.wrapper("__init__", INIT_DOC, TypeDict::attributeErrorInit);
            table.method("__getstate__", null, TypeDict::attributeGetstate);
            table.method("__reduce__", null, TypeDict::attributeReduce);
            table.member(
                    "name",
                    "attribute name",
                    self -> ((PyBaseException) self).memberField("name"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("name", value));
            table.member(
                    "obj",
                    "object",
                    self -> ((PyBaseException) self).memberField("obj"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("obj", value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.NAME_ERROR) {
            // `UnboundLocalError` derives from here and declares nothing, so it runs this initialiser and
            // files no row of its own — which is why a bad name handed to one of the two is complained
            // about in the words of `NameError()`, the C function behind the row, and not of the class.
            table.wrapper("__init__", INIT_DOC, TypeDict::nameErrorInit);
            table.member(
                    "name",
                    "name",
                    self -> ((PyBaseException) self).memberField("name"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("name", value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.STOP_ITERATION) {
            // The value a generator's `return` hands out, and the initialiser that fills it: this class is
            // the one of the five whose field is set from the first positional rather than named, and the
            // one that clears it back to `None` when a call gives nothing.
            table.wrapper("__init__", INIT_DOC, TypeDict::stopIterationInit);
            table.member(
                    "value",
                    "generator return value",
                    self -> ((PyBaseException) self).memberField("value"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("value", value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.SYSTEM_EXIT) {
            // `code`, and the only initialiser of the family that leaves its field alone rather than
            // filling it — `SystemExit()` clears nothing, and `e.__init__()` of a value that had a code
            // keeps it.
            table.wrapper("__init__", INIT_DOC, TypeDict::systemExitInit);
            table.member(
                    "code",
                    "exception code",
                    self -> ((PyBaseException) self).memberField("code"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("code", value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.IMPORT_ERROR) {
            // The one class of the family whose `str` reads a field rather than the arguments, and the one
            // whose reduction adds three of them to the state — `ModuleNotFoundError` runs this
            // initialiser, files no row of its own, and is named in none of its refusals.
            table.wrapper("__str__", STR_DOC, args -> PyStr.of(((PyBaseException) args[0]).importErrorText()));
            table.wrapper("__init__", INIT_DOC, TypeDict::importErrorInit);
            table.method("__reduce__", null, TypeDict::importErrorReduce);
            table.member(
                    "msg",
                    "exception message",
                    self -> ((PyBaseException) self).memberField("msg"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("msg", value));
            table.member(
                    "name",
                    "module name",
                    self -> ((PyBaseException) self).memberField("name"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("name", value));
            table.member(
                    "path",
                    "module path",
                    self -> ((PyBaseException) self).memberField("path"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("path", value));
            table.member(
                    "name_from",
                    "name imported from module",
                    self -> ((PyBaseException) self).memberField("name_from"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("name_from", value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.SYNTAX_ERROR) {
            // The nine offsets, the display that reads two of them and the initialiser that fills eight, in
            // the order the two tables merge. There is no `__new__` row here: the class declares a `tp_init`
            // of its own, so `BaseException_new` is not copied into its table and `SyntaxError.__new__` is
            // the base's, reached through the method resolution order rather than filed beside it.
            table.wrapper(
                    "__str__", STR_DOC, args -> PyStr.of(((PyBaseException) args[0]).syntaxErrorText()));
            table.wrapper("__init__", INIT_DOC, TypeDict::syntaxErrorInit);
            table.member(
                    "msg",
                    "exception msg",
                    self -> ((PyBaseException) self).memberField("msg"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("msg", value));
            table.member(
                    "filename",
                    "exception filename",
                    self -> ((PyBaseException) self).memberField("filename"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("filename", value));
            table.member(
                    "lineno",
                    "exception lineno",
                    self -> ((PyBaseException) self).memberField("lineno"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("lineno", value));
            table.member(
                    "offset",
                    "exception offset",
                    self -> ((PyBaseException) self).memberField("offset"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("offset", value));
            table.member(
                    "text",
                    "exception text",
                    self -> ((PyBaseException) self).memberField("text"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("text", value));
            table.member(
                    "end_lineno",
                    "exception end lineno",
                    self -> ((PyBaseException) self).memberField("end_lineno"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("end_lineno", value));
            table.member(
                    "end_offset",
                    "exception end offset",
                    self -> ((PyBaseException) self).memberField("end_offset"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("end_offset", value));
            table.member(
                    "print_file_and_line",
                    "exception print_file_and_line",
                    self -> ((PyBaseException) self).memberField("print_file_and_line"),
                    (self, value) ->
                            ((PyBaseException) self).writeMemberField("print_file_and_line", value));
            table.member(
                    "_metadata",
                    "exception private metadata",
                    self -> ((PyBaseException) self).memberField("_metadata"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("_metadata", value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.UNICODE_ENCODE_ERROR
                || owner == ExceptionTypes.UNICODE_DECODE_ERROR
                || owner == ExceptionTypes.UNICODE_TRANSLATE_ERROR) {
            // One `PyMemberDef` table shared by the three leaves, which is why `UnicodeTranslateError` —
            // the class with no encoding to speak of — files `encoding` all the same and reads `None` from
            // it. The two sizes are the only `Py_T_PYSSIZET` offsets the family has besides {@code
            // OSError.characters_written}, and the creator is here because the three leaves declare a
            // `tp_new` of their own in C: `BaseException_new`, the same function the base keeps.
            table.raw(
                    "__new__",
                    NEW_DOC,
                    (args, kwNames, qualified) -> exceptionNew(owner, args, kwNames));
            table.wrapper(
                    "__str__",
                    STR_DOC,
                    args -> PyStr.of(((PyBaseException) args[0]).unicodeErrorText()));
            table.wrapper(
                    "__init__",
                    INIT_DOC,
                    (args, kwNames, qualified) -> unicodeErrorInit(owner, args, kwNames));
            table.member(
                    "encoding",
                    "exception encoding",
                    self -> ((PyBaseException) self).memberField("encoding"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("encoding", value));
            table.member(
                    "object",
                    "exception object",
                    self -> ((PyBaseException) self).memberField("object"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("object", value));
            table.member(
                    "start",
                    "exception start",
                    self -> PyInt.of(((PyBaseException) self).memberNumber("start")),
                    (self, value) -> ((PyBaseException) self).writeMemberNumber("start", value));
            table.member(
                    "end",
                    "exception end",
                    self -> PyInt.of(((PyBaseException) self).memberNumber("end")),
                    (self, value) -> ((PyBaseException) self).writeMemberNumber("end", value));
            table.member(
                    "reason",
                    "exception reason",
                    self -> ((PyBaseException) self).memberField("reason"),
                    (self, value) -> ((PyBaseException) self).writeMemberField("reason", value));
            table.value("__doc__", docValue(owner));
            return;
        }
        if (owner == ExceptionTypes.EXCEPTION_GROUP) {
            // The one class of the family that is not a static struct: `create_exception_group_class` hands
            // `builtins.ExceptionGroup` to `PyErr_NewException`, which writes the half before the dot into
            // the dictionary as `__module__` (errors.c:1321-1331) and then calls `type()` with the rest.
            table.value("__module__", PyStr.of("builtins"));
            // The one built-in of the exception family with a weak reference list of its own, in spite of
            // the fact that nothing under {@code BaseException} has one — which is why a class under {@code
            // ExceptionGroup} files neither name, taking both from here.
            table.getset(
                    "__weakref__",
                    "list of weak references to the object",
                    self -> PyNone.INSTANCE,
                    null);
            table.value("__doc__", docValue(owner));
            return;
        }
        if (ownsNew(owner)) {
            table.raw(
                    "__new__",
                    NEW_DOC,
                    (args, kwNames, qualified) -> exceptionNew(owner, args, kwNames));
        }
        table.value("__doc__", docValue(owner));
    }

    /**
     * The classes the reference interpreter files a {@code __new__} of their own for.
     *
     * <p>It is not the leaves that have one: {@code KeyError}, {@code NameError}, {@code StopIteration},
     * {@code SystemExit}, {@code SyntaxError}, {@code ImportError} and {@code AttributeError} answer with
     * {@code __init__} and their fields instead, and every class under {@code OSError} and under
     * {@code Warning} that declares nothing inherits. What decides it is CPython's own {@code tp_new} —
     * {@code BaseException_new} is copied into the static table of the types that have no behaviour of
     * their own, and the ones that do leave the slot to the class they came from.
     */
    private static boolean ownsNew(PyType owner) {
        return OWN_NEW.contains(owner.name());
    }

    private static final Set<String> OWN_NEW =
            Set.of(
                    "Exception",
                    "ArithmeticError",
                    "LookupError",
                    "OverflowError",
                    "ZeroDivisionError",
                    "FloatingPointError",
                    "ValueError",
                    "UnicodeError",
                    "UnicodeEncodeError",
                    "UnicodeDecodeError",
                    "UnicodeTranslateError",
                    "TypeError",
                    "IndexError",
                    "StopAsyncIteration",
                    "RuntimeError",
                    "RecursionError",
                    "NotImplementedError",
                    "PythonFinalizationError",
                    "ReferenceError",
                    "MemoryError",
                    "AssertionError",
                    "EOFError",
                    "BufferError",
                    "SystemError",
                    "OSError",
                    "Warning",
                    "BytesWarning",
                    "DeprecationWarning",
                    "EncodingWarning",
                    "FutureWarning",
                    "ImportWarning",
                    "PendingDeprecationWarning",
                    "ResourceWarning",
                    "RuntimeWarning",
                    "SyntaxWarning",
                    "UnicodeWarning",
                    "UserWarning",
                    "KeyboardInterrupt",
                    "GeneratorExit");

    /**
     * The dictionary a built-in value type files: its {@link Members} table, and the sentence beside it.
     *
     * <p>{@code int.__dict__} in the reference interpreter holds sixty-two names, because CPython copies
     * every inherited slot wrapper into every static type's dictionary. This runtime's tables hold the
     * methods Python calls by name and nothing else, so {@code len(int.__dict__)} is the shorter answer and
     * {@code dir(int)} with it — the deviation the plan has named since slice 11.
     */
    private static void fromTable(PyType owner, PyDict into) {
        Table table = new Table(owner, into);
        Members methods = Members.tableOf(owner);
        if (methods != null) {
            for (String name : methods.names()) {
                table.put(name, methods.describe(name));
            }
        }
        if (!table.holds("__doc__")) {
            table.value("__doc__", docValue(owner));
        }
    }

    /** The {@code __doc__} entry of a static type: a string, or {@code None} where the type says nothing. */
    private static PyObject docValue(PyType owner) {
        String text = owner.docText();
        return text == null ? PyNone.INSTANCE : PyStr.of(text);
    }

    /** {@code type.__doc__}, and of every class: the sentence the type declares for itself, or {@code None}. */
    private static PyObject docOf(PyObject target) {
        if (target instanceof PyClass declaring) {
            // A heap type's doc is its own dictionary's, and is never inherited: `class D(C): pass`
            // reports None however much C had to say about itself.
            PyObject filed = declaring.namespace().get(PyStr.interned("__doc__"));
            return filed == null ? PyNone.INSTANCE : filed;
        }
        return docValue((PyType) target);
    }

    /** {@code type.__module__}: what the class was written down as, or what its body said. */
    private static PyObject moduleOf(PyObject target) {
        if (target instanceof PyClass declaring) {
            PyObject filed = declaring.namespace().get(PyStr.interned("__module__"));
            if (filed != null) {
                return filed;
            }
        }
        return PyStr.of(((PyType) target).moduleName());
    }

    /** {@code type.__abstractmethods__}: only ever set by {@code ABCMeta}, and refused while unset. */
    private static PyObject abstractMethods(PyObject target) {
        PyObject filed = ((PyType) target).namespace().get(PyStr.interned("__abstractmethods__"));
        if (filed == null) {
            throw Slots.attributeError("__abstractmethods__");
        }
        return filed;
    }

    /** {@code type.__annotations__}: the dictionary the body filled, made empty when nothing was annotated. */
    private static PyObject annotationsOf(PyObject target) {
        PyType kind = (PyType) target;
        PyStr key = PyStr.interned("__annotations__");
        PyObject filed = kind.namespace().get(key);
        if (filed != null) {
            return filed;
        }
        if (!(kind instanceof PyClass)) {
            throw Slots.typeError(
                    "type object '%s' has no attribute '__annotations__'", kind.filedName());
        }
        PyDict built = PyDict.empty();
        kind.namespace().put(key, built);
        return built;
    }

    /**
     * The setter of a name that takes any value and is filed in the class's own dictionary.
     *
     * <p>{@code __doc__} and {@code __type_params__} are the two: {@code type_set_doc} and {@code
     * type_set_type_params} each ask {@link #checkSpecial} first and then hand the value to the dictionary
     * without looking at it, which is why {@code C.__doc__ = 5} succeeds and the read afterwards reports an
     * {@code int}.
     */
    private static PyGetSetDescriptor.Setter writes(String name) {
        return (target, value) -> {
            checkSpecial(target, name, value);
            file((PyClass) target, name, value);
        };
    }

    /**
     * The setter of {@code __module__}, which is the one name a class files that also takes a filing off.
     *
     * <p>{@code type_set_module} pops {@code __firstlineno__} before it stores the new module, because the
     * line number was read out of the frame that built the class under the old one — so {@code
     * C.__module__ = 'other'} leaves {@code C.__firstlineno__} asking for its AttributeError.
     */
    private static PyGetSetDescriptor.Setter writesModule() {
        return (target, value) -> {
            checkSpecial(target, "__module__", value);
            PyClass kind = (PyClass) target;
            kind.namespace().remove(PyStr.interned("__firstlineno__"));
            file(kind, "__module__", value);
        };
    }

    /**
     * The setter of the two names of identity: {@code __name__} and {@code __qualname__}.
     *
     * <p>These are the only two a class keeps out of its dictionary — {@code C.__name__} is never in {@code
     * C.__dict__}, which is why {@link #writes} cannot serve them and why their text check lives on {@link
     * PyClass} beside the fields it writes.
     */
    private static PyGetSetDescriptor.Setter writesIdentity(String name) {
        return (target, value) -> {
            checkSpecial(target, name, value);
            PyClass kind = (PyClass) target;
            if (name.equals("__name__")) {
                kind.writeName(value);
            } else {
                kind.writeQualName(value);
            }
        };
    }

    /**
     * The setter of {@code __bases__}: {@code type_set_bases}, as far as this runtime takes it.
     *
     * <p>Three answers, in CPython's order. The two refusals of {@link #checkSpecial} come first, so {@code
     * del C.__bases__} complains about an immutable type rather than about a name. A value that is not a
     * tuple is then refused by name — {@code can only assign tuple to C.__bases__, not NoneType} — and a
     * tuple that is one reaches the deviation this filing carries: the reference interpreter recomputes the
     * linearisation for a class already made, and this runtime has no way to build a second order for one,
     * so it answers with the refusal a type with no setter at all gives.
     */
    private static PyGetSetDescriptor.Setter writesBases() {
        return (target, value) -> {
            checkSpecial(target, "__bases__", value);
            if (!(value instanceof PyTuple)) {
                throw Slots.typeError(
                        "can only assign tuple to %s.__bases__, not %s",
                        ((PyType) target).filedName(), value.getType().name());
            }
            throw Slots.attributeError(
                    "attribute '__bases__' of 'type' objects is not writable");
        };
    }

    /**
     * {@code check_set_special_type_attr}: the two refusals every one of {@code type}'s setters asks
     * before it looks at the value it was handed.
     *
     * <p>A type Python did not build is refused before anything else is looked at, and the same words serve
     * a delete as a write — which is why {@code del int.__name__} says {@code cannot set}. A class that is
     * asked to take one of its identity names back out is refused in the delete's own words, because {@code
     * __name__}, {@code __qualname__}, {@code __module__}, {@code __doc__}, {@code __bases__}, {@code
     * __type_params__} and {@code __firstlineno__} are a type's own rather than entries it can lose.
     */
    private static void checkSpecial(PyObject target, String name, PyObject value) {
        if (!(target instanceof PyClass)) {
            throw Slots.typeError(
                    "cannot set '%s' attribute of immutable type '%s'", name, immutableName(target));
        }
        if (value == null) {
            throw Slots.typeError(
                    "cannot delete '%s' attribute of immutable type '%s'",
                    name,
                    ((PyType) target).filedName());
        }
    }

    /** The dictionary write a setter ends with, which is {@code type_update_dict}'s own step. */
    private static void file(PyClass kind, String name, PyObject value) {
        kind.namespace().put(PyStr.interned(name), value);
    }

    /**
     * The setter of a name a class keeps in its own dictionary and can take back out again.
     *
     * <p>{@code __abstractmethods__} and {@code __annotations__} are the two, and they share the one
     * answer that is not any other name's: a delete of a filing that is not there is the bare {@code
     * AttributeError} naming the attribute, because CPython raises it from inside the setter when {@code
     * PyDict_Pop} reports nothing removed. A write takes any value at all — {@code
     * type_set_abstractmethods} only asks {@code PyObject_IsTrue} of it.
     *
     * <p>{@code __annotations__} deviates on the write: the reference interpreter puts a value there under
     * {@code __annotations_cache__} when the dictionary holds no {@code __annotations__} yet, and this
     * runtime files the name itself, so the two disagree about what a following {@code C.__dict__} lists.
     */
    private static PyGetSetDescriptor.Setter files(String name) {
        return (target, value) -> {
            if (!(target instanceof PyClass kind)) {
                throw Slots.typeError(
                        "cannot set '%s' attribute of immutable type '%s'", name, immutableName(target));
            }
            PyStr key = PyStr.interned(name);
            if (value == null) {
                if (kind.namespace().remove(key) == null) {
                    throw Slots.attributeError(name);
                }
                return;
            }
            kind.namespace().put(key, value);
        };
    }

    /**
     * The setter of {@code __annotate__}: the callable a class body's annotations are kept behind.
     *
     * <p>Two refusals are this name's own and are worded unlike the other names': a delete drops the
     * quotes around the name <em>and</em> comes before the immutable-type check, so {@code del
     * int.__annotate__} is refused as {@code __annotate__} rather than as {@code int}; and a value that is
     * neither callable nor {@code None} is refused before it is filed. {@code type_set_annotate} files it
     * under {@code __annotate_func__} rather than under its own name, which this runtime does not have —
     * see the {@code __annotate__} entry in the plan.
     */
    private static PyGetSetDescriptor.Setter writesAnnotate() {
        return (target, value) -> {
            if (value == null) {
                throw Slots.typeError("cannot delete __annotate__ attribute");
            }
            checkSpecial(target, "__annotate__", value);
            if (value != PyNone.INSTANCE && !Slots.isCallable(value)) {
                throw Slots.typeError("__annotate__ must be callable or None");
            }
            file((PyClass) target, "__annotate__", value);
        };
    }

    /**
     * The setter of a name CPython gives no setter to at all.
     *
     * <p>{@code __mro__}, {@code __dict__} and {@code __text_signature__} are getsets whose {@code setter}
     * is null, and {@code descr_set} answers that in the words of the attribute rather than of the type —
     * and it answers it <em>for a delete too</em>, because CPython hands the same {@code NULL} value to the
     * same function and only the fall-through to the dictionary would have taken a name away. Before that
     * refusal a built-in type is refused in the usual words, which the reference interpreter asks first.
     */
    private static PyGetSetDescriptor.Setter refuses(String name) {
        return (target, value) -> {
            if (!(target instanceof PyClass)) {
                throw Slots.typeError(
                        "cannot set '%s' attribute of immutable type '%s'", name, immutableName(target));
            }
            throw Slots.attributeError(
                    "attribute '%s' of 'type' objects is not writable", name);
        };
    }

    /** The name the two immutable-type refusals quote, which is a built-in type's as written down. */
    private static String immutableName(PyObject target) {
        return ((PyType) target).filedName();
    }

    /**
     * {@code x.__class__ = C}: the swap {@code object}'s getset answers with, and {@code del x.__class__},
     * which no value allows.
     */
    private static void writeClass(PyObject target, PyObject value) {
        if (value == null) {
            ClassAttribute.delete();
        }
        ClassAttribute.write(target, value);
    }

    /** {@code type.__name__} and {@code type.__qualname__}, which this runtime keeps as one name. */
    private static PyObject nameOf(PyObject target) {
        return PyStr.of(((PyType) target).name());
    }

    /** {@code type.__bases__}: the tuple of the bases the type was declared over. */
    private static PyObject basesOf(PyObject target) {
        return PyTuple.of(((PyType) target).bases());
    }

    /** {@code type.__mro__}: the same order {@code mro()} answers with, as a tuple. */
    private static PyObject mroOf(PyObject target) {
        return PyTuple.of(((PyType) target).mro());
    }

    /** {@code type.__base__}: the first of those, or {@code None} for {@code object} itself. */
    private static PyObject baseOf(PyObject target) {
        PyType parent = ((PyType) target).parent();
        return parent == null ? PyNone.INSTANCE : parent;
    }

    /** {@code type.__dict__}: a fresh window over the one dictionary, every time. */
    private static PyObject dictOf(PyObject target) {
        return PyMappingProxy.of(((PyType) target).namespace());
    }

    /**
     * {@code type.__annotate__}: the function 3.14 files for a class body that annotated anything.
     *
     * <p>A class Python wrote answers {@code None} when its body said nothing, because {@code type_new}
     * files the name with nothing in it; a built-in type has no such entry and says so in the words the
     * attribute lookup uses, which is what {@code int.__annotate__} is.
     */
    private static PyObject annotateOf(PyObject target) {
        PyType kind = (PyType) target;
        if (!(kind instanceof PyClass)) {
            throw Slots.typeError(
                    "type object '%s' has no attribute '__annotate__'", kind.filedName());
        }
        PyObject filed = kind.namespace().get(PyStr.interned("__annotate__"));
        return filed == null ? PyNone.INSTANCE : filed;
    }

    /**
     * {@code type.__type_params__}: the PEP 695 parameters, which this runtime never files.
     *
     * <p>A class answers the empty tuple because no {@code class C[T]} declaration has reached the runtime,
     * and a write of anything is filed like any other name — which is what makes the read afterwards give
     * back what was written.
     */
    private static PyObject typeParamsOf(PyObject target) {
        PyObject filed =
                ((PyType) target).namespace().get(PyStr.interned("__type_params__"));
        return filed == null ? PyTuple.EMPTY : filed;
    }

    /**
     * {@code object.__new__(cls)}: the one class a bare {@code object} creator is allowed to make.
     *
     * <p>Four gates, then {@code object_new}'s own fifth. The four are {@code tp_new_wrapper}'s and are the
     * ones every other constructor in this file runs: {@code object} is refused for anything whose creator
     * is not {@code object_new}, which is why {@code object.__new__(int)} complains that {@code int} builds
     * its values a different way while a class Python wrote with no {@code __new__} of its own is accepted,
     * and one with a {@code __new__} of its own is accepted too — {@code slot_tp_new} is stepped over on the
     * way back, and what is left at the end of the chain is {@code object} itself.
     *
     * <p>The fifth is inside {@code object_new} and looks at the arguments. Extra ones are refused twice over,
     * and the two refusals name different classes: a value type that reached here with its own creator says
     * the sentence about {@code object.__new__} taking one argument, and a class whose {@code tp_init} is
     * still {@code object_init} says {@code Plain() takes no arguments} in the name of the class being made.
     * A class with an {@code __init__} of its own takes the extras and throws them away, which is what {@code
     * object.__new__(C, 5)} is.
     */
    private static PyObject objectNew(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = declaredClass(PyType.OBJECT, args, kwNames);
        if (Arguments.positional(args, kwNames) > 1 || hasKeywords(kwNames)) {
            if (runsPythonNew(kind)) {
                throw Slots.typeError(
                        "object.__new__() takes exactly one argument (the type to instantiate)");
            }
            if (inheritsObjectInit(kind)) {
                throw Slots.typeError("%s() takes no arguments", kind.name());
            }
        }
        return new PyInstance(kind);
    }

    /**
     * Whether {@code tp_init} for this class is still {@code object_init}, which is what {@code object_new}
     * asks when it decides whose arity complaint to make.
     *
     * <p>The answer is the first class in the order that files an {@code __init__} at all: a class Python
     * wrote that names none is left with {@code object}'s, and one that names one has a pointer of its own
     * in the slot even when what it says is only {@code pass}.
     */
    private static boolean inheritsObjectInit(PyType kind) {
        for (PyType owner : kind.mro()) {
            if (owner.namespace().get(PyStr.interned("__init__")) != null) {
                return owner == PyType.OBJECT;
            }
        }
        return true;
    }

    /**
     * {@code object.__init__}, which takes its receiver and nothing else — for a class whose values the
     * default creator makes.
     *
     * <p>The refusal names the initializer rather than the call, because that is what {@code object_init}
     * says when {@code tp_new} is still {@code object_new} and somebody handed arguments anyway. A type
     * that fills its own {@code tp_new} — every built-in here, and any class that wrote {@code __new__} —
     * takes them and throws them away, which is what {@code object.__init__(3, 1)} is.
     */
    private static PyObject objectInit(PyObject[] args, String[] kwNames, String qualified) {
        if (args.length > 1 && !makesItsOwnValues(args[0].getType())) {
            throw Slots.typeError(
                    "object.__init__() takes exactly one argument (the instance to initialize)");
        }
        return PyNone.INSTANCE;
    }

    private static boolean makesItsOwnValues(PyType kind) {
        if (kind == PyType.OBJECT) {
            return false;
        }
        return !(kind instanceof PyClass) || Specials.lookUp(kind, "__new__") != null;
    }

    /** {@code object.__repr__}: the address this runtime gives a value, which is not CPython's. */
    private static PyObject objectRepr(PyObject[] args, String[] kwNames, String qualified) {
        return PyStr.of(
                "<" + args[0].getType().filedName() + " object at 0x"
                        + Long.toHexString(System.identityHashCode(args[0]))
                        + ">");
    }

    /**
     * {@code object.__eq__} and its five siblings: identity, and nothing else.
     *
     * <p>{@code object_richcompare} answers equality out of the pointer and declines the four orderings,
     * so a program that calls {@code object.__lt__(3, 4)} by hand gets {@code NotImplemented} and the
     * complaint about two ints from the operator that called it.
     */
    private static PyObject identity(PyObject[] args, CompareOp cmp) {
        boolean same = args[0] == (args.length > 1 ? args[1] : null);
        return switch (cmp) {
            case EQ -> PyBool.of(same);
            case NE -> PyBool.of(!same);
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /**
     * The same six for a window, with the mapping underneath doing the comparing.
     *
     * <p>Two windows are equal when they are windows over the one dictionary rather than when their
     * contents agree, which is why {@code object.__dict__ == int.__dict__} is false although both hold
     * {@code __new__}; the four orderings go to the dictionaries, in the words of the operator CPython
     * reaches after swapping them — {@code p < p} complains about {@code '>'} between two dicts.
     */
    private static PyObject windowed(PyObject[] args, CompareOp cmp) {
        PyObject other = args.length > 1 ? args[1] : null;
        PyDict left = window(args[0]);
        if (other instanceof PyMappingProxy) {
            PyDict right = window(other);
            return switch (cmp) {
                case EQ -> PyBool.of(left == right);
                case NE -> PyBool.of(left != right);
                default -> Slots.compare(cmp.reflected(), left, right);
            };
        }
        return Slots.compare(cmp, left, other);
    }

    /** The mapping a window stands for, which is the operand CPython hands to every one of its slots. */
    private static PyDict window(PyObject value) {
        return ((PyMappingProxy) value).mapping();
    }

    /**
     * {@code reversed(p)}: the keys from the last one written back to the first.
     *
     * <p>The order is the mapping's insertion order, not the sorted order {@code dir()} takes, which is
     * what makes {@code list(reversed(C.__dict__))} end with the two names a class body writes last.
     */
    private static PyObject reversedKeys(PyDict mapping) {
        PyObject[] keys = mapping.keys();
        PyObject[] backwards = new PyObject[keys.length];
        for (int at = 0; at < keys.length; at++) {
            backwards[at] = keys[keys.length - 1 - at];
        }
        return PyIterator.of(backwards);
    }

    /** {@code p | q} and {@code q | p} reached as the slot: the operands the window stands for. */
    private static PyObject windowOr(PyObject[] args, String[] kwNames, String qualified) {
        PyObject other = args[1];
        return Slots.binary(
                BinaryOp.OR, window(args[0]), other instanceof PyMappingProxy ? window(other) : other);
    }

    /** {@code p |= q}: the one in-place slot the type fills, and it only refuses. */
    private static PyObject refuseIor(PyObject[] args, String[] kwNames, String qualified) {
        throw Slots.typeError("'|=' is not supported by mappingproxy; use '|' instead");
    }

    /**
     * {@code object.__getstate__}: the instance dictionary, or {@code None} where there is nothing.
     *
     * <p>The name in its refusals is the one {@code object.c} writes, so {@code (3).__getstate__(x=1)}
     * says {@code object.__getstate__() takes no keyword arguments} where a read of the same method reports
     * itself as {@code int.__getstate__}.
     */
    private static PyObject objectState(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        PyObject found = Slots.findAttr(args[0], "__dict__");
        return found instanceof PyDict held && held.size() > 0 ? held : PyNone.INSTANCE;
    }

    /**
     * {@code object.__format__(format_spec)}: the empty spec, and the one sentence that names the value's
     * own class.
     *
     * <p>The arity complaint is written under {@code object} however the method was reached —
     * {@code A().__format__()} says {@code object.__format__() takes exactly one argument (0 given)} —
     * because CPython's {@code object.c} names its own function there, and not the class of the value it
     * was handed. The refusal of a spec that is not text is the one exception, and writes itself
     * {@code __format__()} bare.
     */
    private static PyObject objectFormat(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.exactlyOne(qualified + "()", tail(args, kwNames), kwNames);
        PyObject spec = tail(args, kwNames)[0];
        if (!(spec instanceof PyStr)) {
            throw Slots.typeError(
                    "__format__() argument must be str, not %s", Slots.argumentName(spec));
        }
        if (((PyStr) spec).getValue().isEmpty()) {
            return PyStr.of(args[0].str());
        }
        throw Slots.typeError(
                "unsupported format string passed to %s.__format__", args[0].getType().name());
    }

    /** {@code object.__sizeof__}: this runtime has no struct, so it counts nothing. */
    private static PyObject objectSize(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return PyInt.of(0);
    }

    /** {@code object.__dir__}: the value's own dictionary and its class's order. */
    private static PyObject objectDir(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return nameList(Dirs.valueNames(args[0]));
    }

    /** The list a {@code __dir__} answers with, in the order the merge kept the names. */
    private static PyObject nameList(List<String> names) {
        PyObject[] items = new PyObject[names.size()];
        for (int at = 0; at < items.length; at++) {
            items[at] = PyStr.of(names.get(at));
        }
        return PyList.of(items);
    }

    /** {@code cls.__subclasses__()}: the classes that name this one among their bases. */
    private static PyObject subclasses(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        List<PyObject> children = new ArrayList<>();
        for (PyType child : PyType.children()) {
            for (PyType base : child.bases()) {
                if (base == args[0]) {
                    children.add(child);
                    break;
                }
            }
        }
        return PyList.of(children.toArray(new PyObject[0]));
    }

    /** {@code mappingproxy(mapping)}: a window over the mapping that was handed over. */
    private static PyObject proxyNew(PyObject[] args, String[] kwNames, String qualified) {
        if (Arguments.positional(args, kwNames) != 1) {
            throw Slots.typeError("mappingproxy() takes exactly one argument (%d given)",
                    Arguments.positional(args, kwNames));
        }
        return args[0] instanceof PyDict held
                ? PyMappingProxy.of(held)
                : PyMappingProxy.of(((PyMappingProxy) args[0]).mapping());
    }

    private static PyObject proxyGet(PyObject[] args, String[] kwNames, String qualified) {
        PyDict held = ((PyMappingProxy) args[0]).mapping();
        PyObject[] rest = tail(args, kwNames);
        if (rest.length < 1 || rest.length > 2) {
            throw Slots.typeError("get() takes at least 1 argument (%d given)", rest.length);
        }
        PyObject found = held.get(rest[0]);
        return found == null ? (rest.length > 1 ? rest[1] : PyNone.INSTANCE) : found;
    }

    /** {@code p.keys()} and its two siblings: the dict's own view over the same mapping. */
    private static PyObject proxyView(PyObject self, String name) {
        return Slots.call(
                Slots.getAttr(((PyMappingProxy) self).mapping(), name), new PyObject[0], null);
    }

    /**
     * A member of the class system, named by the class it was reached through.
     *
     * <p>The name arrives rather than being read off the first argument because CPython complains under
     * the name the read gave: {@code int.mro()} takes no arguments, and the same method called the long
     * way over says {@code type.mro()}.
     */
    private interface Method {
        PyObject apply(PyObject[] args, String[] kwNames, String qualified);
    }

    /**
     * {@code tp_new_wrapper}, then {@code type_new}.
     *
     * <p>The three refusals are CPython's and they come in this order: an argument list with nothing in
     * it is not even a metatype, the first argument has to be one, and the class being built has to be a
     * subclass of the type whose dictionary holds the method — which is why {@code type.__new__(int, ...)}
     * complains about {@code int} rather than building anything.
     */
    private static PyObject newMember(PyObject[] args, String[] kwNames, String qualified) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError("type.__new__(): not enough arguments");
        }
        PyObject first = args[0];
        if (!(first instanceof PyType subtype)) {
            throw Slots.typeError(
                    "type.__new__(X): X is not a type object (%s)", first.getType().name());
        }
        if (!subtype.matches(PyType.TYPE)) {
            throw Slots.typeError(
                    "type.__new__(%s): %s is not a subtype of type", subtype.name(), subtype.name());
        }
        return Classes.newType(subtype, tail(args, kwNames), kwNames);
    }

    /** {@code type_init}: the argument count is the whole of what a class's own initialiser checks. */
    private static PyObject initMember(PyObject[] args, String[] kwNames, String qualified) {
        wrapperReceiver("__init__", args, kwNames);
        Classes.typeInit(tail(args, kwNames), kwNames);
        return PyNone.INSTANCE;
    }

    /** {@code type_call} for the class given as the receiver. */
    private static PyObject callMember(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = wrapperReceiver("__call__", args, kwNames);
        return Classes.typeCall(kind, tail(args, kwNames), PyBoundMethod.keywordTuple(kwNames));
    }

    /** {@code cls.mro()}: the order as a list, of the class the method was read from. */
    private static PyObject mroMember(PyObject[] args, String[] kwNames, String qualified) {
        PyType owner = (PyType) methodReceiver("mro", PyType.TYPE, args, kwNames);
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return PyList.of(owner.mro());
    }

    /** {@code isinstance} without a hook: the walk of the order, asked of the class itself. */
    private static PyObject instanceCheck(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind =
                (PyType) methodReceiver("__instancecheck__", PyType.TYPE, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        return PyBool.of(Classes.plainInstanceOf(kind, rest[0]));
    }

    /** {@code issubclass} without a hook: the same walk, one class against another. */
    private static PyObject subclassCheck(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = (PyType) methodReceiver("__subclasscheck__", PyType.TYPE, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        return PyBool.of(Classes.plainSubclass(kind, rest[0]));
    }

    /**
     * {@code BaseException_new}: a value of the class given, keeping the arguments as they were given.
     *
     * <p>The refusals are {@code tp_new_wrapper}'s, in its order, and name the class whose dictionary the
     * call was made through — {@code ValueError.__new__(KeyError, 'k')} says {@code KeyError} is not a
     * subtype of {@code ValueError} rather than of {@code BaseException}. Keywords are thrown away by
     * {@code BaseException_new}, which has no table to read them against: {@code Bare.__new__(Bare, flag=1)}
     * makes a value with no arguments at all. {@code OSError_new} is the exception that has one, so the
     * same call through the {@code OSError} row refuses — under the name of the class it was handed, which
     * is why {@code OSError.__new__(Sub, 1, "a", flag=2)} says {@code Sub()} — and refuses only when the
     * class parses here: the one that defers to its own {@code __init__} is past every question {@code
     * OSError_new} asks, and comes back empty.
     */
    private static PyObject exceptionNew(
            PyType declaring, PyObject[] args, String[] kwNames) {
        int given = Arguments.positional(args, kwNames);
        PyType kind = declaredClass(declaring, args, kwNames);
        PyObject[] rest = Arrays.copyOfRange(args, 1, given);
        if (creatorOf(declaring) != ExceptionTypes.OS_ERROR) {
            return new PyBaseException(kind, rest);
        }
        if (hasKeywords(kwNames) && !PyOSError.defersToInit(kind)) {
            throw Slots.typeError("%s() takes no keyword arguments", kind.name());
        }
        return PyOSError.create(kind, rest);
    }

    /**
     * {@code BaseException_init}: the arguments of the call, taken over as the value's own.
     *
     * <p>This is the method {@code super().__init__("boom")} reaches from inside an exception class, and
     * the reason an {@code __init__} that names its own arguments still leaves {@code e.args} and
     * therefore {@code str(e)} reading what that call said rather than what the class was called with.
     */
    private static PyObject exceptionInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver("__init__", ExceptionTypes.BASE_EXCEPTION, args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s() takes no keyword arguments", self.getType().name());
        }
        self.setArguments(tail(args, kwNames));
        return PyNone.INSTANCE;
    }

    /**
     * {@code BaseException_with_traceback}: the same value, with a traceback attached to it.
     *
     * <p>The one argument is checked as {@code __traceback__} is and the value answers with itself,
     * which is how {@code raise e.with_traceback(tb)} keeps reporting the exception it was handed. The
     * two refusals of the argument list name the method under the name the read gave it — so {@code
     * e.with_traceback()} of a {@code ValueError} says {@code BaseException.with_traceback()}, because
     * the method form of {@code LOAD_ATTR} calls the descriptor with the receiver rather than binding it,
     * while {@code m = e.with_traceback; m()} says {@code ValueError.with_traceback()}, because that
     * {@code m} is a bound copy that reads its name off the value it was made from.
     */
    private static PyObject withTraceback(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver(
                                "with_traceback", ExceptionTypes.BASE_EXCEPTION, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        self.setTraceback(rest[0]);
        return self;
    }

    /**
     * {@code BaseException_add_note}: the note the argument table already accepted, put on a list.
     *
     * <p>The table runs first, so a note that is not text is complained about before anything looks at
     * {@code __notes__} — and in its own words, naming the method bare and the value's class, with
     * {@code None} spelled as the value rather than as {@code NoneType}. Then the list is read as an
     * attribute and written back as one: nothing is missing from the value until the first note, a class
     * that files a list under the name has every one of its instances append to <em>that</em> list, and a
     * {@code __setattr__} that swallows the write loses the note exactly as CPython's does. A
     * {@code __notes__} that is there and is not a list is refused without appending, which is why the
     * check is {@code PyList_Check} and not a walk of the sequence protocol.
     */
    private static PyObject addNote(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver("add_note", ExceptionTypes.BASE_EXCEPTION, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        PyObject note = rest[0];
        if (!(note instanceof PyStr)) {
            throw Slots.typeError(
                    "add_note() argument must be str, not %s",
                    note == PyNone.INSTANCE ? "None" : note.getType().name());
        }
        PyObject found = Slots.findAttr(self, "__notes__");
        PyList notes;
        if (found == null) {
            notes = PyList.empty();
            Slots.setAttr(self, "__notes__", notes);
        } else if (found instanceof PyList list) {
            notes = list;
        } else {
            throw Slots.typeError("Cannot add note: __notes__ is not a list");
        }
        notes.add(note);
        return PyNone.INSTANCE;
    }

    /**
     * {@code BaseException_reduce}: the class, the arguments, and the dictionary only where there is one.
     *
     * <p>Two shapes and no third, because the test CPython makes is of two pointers rather than of two
     * contents: a value nothing has ever been written to reduces to a pair, and one that has — or whose
     * {@code __dict__} has only ever been <em>read</em>, which is enough to fill the field — to a triple.
     * {@code OSError} rewrites the middle of the pair and leaves this rule to stand.
     */
    private static PyObject exceptionReduce(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver("__reduce__", ExceptionTypes.BASE_EXCEPTION, args, kwNames);
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return self.reduced();
    }

    /**
     * {@code BaseException_setstate}: the state, written back one entry at a time.
     *
     * <p>The refusal of a state that is neither {@code None} nor a dictionary is the method's own and comes
     * before anything is looked at; after that each entry is an ordinary assignment, so every name the
     * class files a descriptor for — {@code args}, {@code __class__}, one of an {@code OSError}'s five —
     * is written through it and never lands in the dictionary beside it. That is also how a state key that
     * is not text is found out about, in the words the attribute setter keeps for the name rather than in
     * this method's.
     */
    private static PyObject exceptionSetstate(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver("__setstate__", ExceptionTypes.BASE_EXCEPTION, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        self.restored(rest[0]);
        return PyNone.INSTANCE;
    }

    /**
     * {@code OSError_init}: the arguments, but only for a class that left them to be parsed here.
     *
     * <p>{@code oserror_use_init} is the whole of the method for everything else — {@code OSError} itself
     * and every one of the classes under it included, since a value of one of those was made whole by
     * {@code OSError_new}. So {@code e.__init__(2, "b")} of a plain {@code OSError} answers {@code None}
     * and changes nothing at all, and only a class that declares an {@code __init__} of its own and no
     * {@code __new__} has the call re-read the arguments and re-fill the five fields.
     */
    private static PyObject oSErrorInit(PyObject[] args, String[] kwNames, String qualified) {
        PyOSError self =
                (PyOSError) exceptionReceiver("__init__", ExceptionTypes.OS_ERROR, args, kwNames);
        if (!PyOSError.defersToInit(self.getType())) {
            return PyNone.INSTANCE;
        }
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s() takes no keyword arguments", self.getType().name());
        }
        self.initialize(tail(args, kwNames));
        return PyNone.INSTANCE;
    }

    /**
     * {@code OSError_reduce}: the same pair or triple, over the arguments the class was really called with.
     *
     * <p>Filed under {@code OSError} rather than inherited, which is what makes the refusal of a value of
     * another family name {@code OSError} and the qualified name of the row {@code OSError.__reduce__} even
     * for a {@code FileNotFoundError}.
     */
    private static PyObject oSErrorReduce(PyObject[] args, String[] kwNames, String qualified) {
        PyOSError self =
                (PyOSError) methodReceiver("__reduce__", ExceptionTypes.OS_ERROR, args, kwNames);
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return self.reduced();
    }

    /**
     * {@code AttributeError_init}: the arguments taken over, then the two fields the call may name.
     *
     * <p>CPython reads the names against a tuple it has just made empty, so the positionals reach {@code
     * e.args} and nothing else — {@code AttributeError('a', 1, name='n')} is a value with two arguments and
     * a name — and the sentence that refuses a name the table does not have is the C function's, not the
     * class's: a {@code class Sub(AttributeError)} called with {@code bad=1} is refused as {@code
     * AttributeError()}, the way {@code UnboundLocalError} is refused as {@code NameError()}. Both fields
     * are written from the parse either way, which is what makes {@code e.__init__('z')} clear a name the
     * value had rather than leave it standing.
     */
    private static PyObject attributeErrorInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver("__init__", ExceptionTypes.ATTRIBUTE_ERROR, args, kwNames);
        self.setArguments(positionalsAfterReceiver(args, kwNames));
        PyObject[] fields =
                Arguments.keywordOnly("AttributeError", args, kwNames, new String[] {"name", "obj"});
        self.writeMemberField("name", fields[0]);
        self.writeMemberField("obj", fields[1]);
        return PyNone.INSTANCE;
    }

    /** {@code NameError_init}: the arguments taken over, and the one name a call may hand with them. */
    private static PyObject nameErrorInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver("__init__", ExceptionTypes.NAME_ERROR, args, kwNames);
        self.setArguments(positionalsAfterReceiver(args, kwNames));
        PyObject[] fields =
                Arguments.keywordOnly("NameError", args, kwNames, new String[] {"name"});
        self.writeMemberField("name", fields[0]);
        return PyNone.INSTANCE;
    }

    /**
     * {@code StopIteration_init}: the arguments, and the value the first of them is.
     *
     * <p>Two things separate this from {@code SystemExit_init}, which reads the same arguments: the field
     * is cleared to {@code None} when the call gives nothing, so {@code e.__init__()} on a value that had a
     * value loses it, and {@code StopIteration(1, 2).value} is the one argument and not a tuple of both.
     * Keywords are refused here rather than parsed, because {@code BaseException_init} is handed the call's
     * own keyword dictionary and answers it with {@code _PyArg_NoKeywords} — under the class the value was
     * made as, which is how a subclass gets its own name in the sentence.
     */
    private static PyObject stopIterationInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver("__init__", ExceptionTypes.STOP_ITERATION, args, kwNames);
        PyObject[] values = positionalsAfterReceiver(args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s() takes no keyword arguments", self.getType().name());
        }
        self.setArguments(values);
        self.writeMemberField("value", values.length == 0 ? PyNone.INSTANCE : values[0]);
        return PyNone.INSTANCE;
    }

    /**
     * {@code SystemExit_init}: the arguments, and the code they add up to.
     *
     * <p>One argument is the code, more than one are the code as a tuple, and none is the case that leaves
     * the field exactly as it was — {@code SystemExit('a').__init__()} keeps {@code 'a'} where the same
     * call on a {@code StopIteration} empties it. The exit status the launcher ends with is read from the
     * arguments rather than from this field, so the two only come apart when a code is written by hand.
     */
    private static PyObject systemExitInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver("__init__", ExceptionTypes.SYSTEM_EXIT, args, kwNames);
        PyObject[] values = positionalsAfterReceiver(args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s() takes no keyword arguments", self.getType().name());
        }
        self.setArguments(values);
        if (values.length == 1) {
            self.writeMemberField("code", values[0]);
        } else if (values.length > 1) {
            self.writeMemberField("code", PyTuple.of(values));
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code ImportError_init}: the arguments, the three names, and the message a one-argument call is.
     *
     * <p>{@code msg} is the only field of the family filled from the arguments rather than from a name,
     * and the only one filled by an {@code if} on the count: two arguments leave it empty, which is what
     * puts {@code str(ImportError('a', 'b'))} back on {@code BaseException_str} and out of the field the
     * class files for it. The three names all take any value — the parse is {@code |$OOO}, and no
     * converter looks at what arrives — so {@code ImportError('m', name=1)} keeps the {@code 1}.
     */
    private static PyObject importErrorInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver("__init__", ExceptionTypes.IMPORT_ERROR, args, kwNames);
        PyObject[] values = positionalsAfterReceiver(args, kwNames);
        self.setArguments(values);
        PyObject[] fields =
                Arguments.keywordOnly(
                        "ImportError", args, kwNames, new String[] {"name", "path", "name_from"});
        self.writeMemberField("name", fields[0]);
        self.writeMemberField("path", fields[1]);
        self.writeMemberField("name_from", fields[2]);
        self.writeMemberField("msg", values.length == 1 ? values[0] : null);
        return PyNone.INSTANCE;
    }

    /**
     * {@code SyntaxError_init}: the message the first argument is, and the seven the second one iterates to.
     *
     * <p>The second argument is not read as a tuple of fields but <em>as a sequence</em> — {@code
     * PySequence_Tuple} runs first, which is why {@code SyntaxError('m', "abcd")} names {@code 'a'} as its
     * file and why {@code SyntaxError('m', 42)} is the complaint about an int not being iterable rather than
     * about a field it cannot fill. The parse that follows is {@code OOOO|OOO} with no name after the colon,
     * so its two refusals say {@code function}, and no converter looks at a value: a {@code lineno} of
     * {@code 'x'} is kept, and only the display decides that it is not a line.
     *
     * <p>Three of the seven slots are written whether the call reached them or not, which is what makes
     * {@code e.__init__('m', ('f', 1, 2, 't'))} clear an {@code end_lineno} the value had. The rule about
     * {@code end_offset} is asked after all seven are written, so a call that names the end line and no end
     * offset leaves the value holding both — and then refuses the call.
     */
    private static PyObject syntaxErrorInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver("__init__", ExceptionTypes.SYNTAX_ERROR, args, kwNames);
        PyObject[] values = positionalsAfterReceiver(args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s() takes no keyword arguments", self.getType().name());
        }
        self.setArguments(values);
        if (values.length >= 1) {
            self.writeMemberField("msg", values[0]);
        }
        if (values.length == 2) {
            PyObject[] info = Constructors.materialise(values[1]).toArray(new PyObject[0]);
            Arguments.parsed(info.length, 4, 7);
            self.writeMemberField("filename", info[0]);
            self.writeMemberField("lineno", info[1]);
            self.writeMemberField("offset", info[2]);
            self.writeMemberField("text", info[3]);
            self.writeMemberField("end_lineno", info.length > 4 ? info[4] : null);
            self.writeMemberField("end_offset", info.length > 5 ? info[5] : null);
            self.writeMemberField("_metadata", info.length > 6 ? info[6] : null);
            if (info.length == 5) {
                throw Slots.typeError(
                        "end_offset must be provided when end_lineno is provided");
            }
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code UnicodeEncodeError_init}, {@code UnicodeDecodeError_init} and {@code
     * UnicodeTranslateError_init}: five names in three orders.
     *
     * <p>The parse is the legacy kind, so it counts only what arrived positionally and refuses by the
     * converter that broke — {@code argument 1 must be str, not bytes} for the {@code 'U'} of a call that
     * handed over octets, {@code 'float' object cannot be interpreted as an integer} for the {@code 'n'} of
     * a call that handed over a half. {@code UnicodeTranslateError} has four arguments and no encoding at
     * all, which is why this takes the class rather than reading one table: the leaf fills four of the five
     * offsets its {@code PyMemberDef} shares, and leaves {@code encoding} exactly as it found it.
     *
     * <p>{@code BaseException_init} runs first, as it does for every class of the family, so the arguments
     * of a refused call are still the value's own — and the object a decode was handed is converted
     * <em>after</em> the five have been read, which is why a float in the {@code start} of a call whose
     * object is a string is complained about as an index rather than as a buffer.
     */
    private static PyObject unicodeErrorInit(
            PyType owner, PyObject[] args, String[] kwNames) {
        PyBaseException self = (PyBaseException) exceptionReceiver("__init__", owner, args, kwNames);
        PyObject[] values = positionalsAfterReceiver(args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s() takes no keyword arguments", self.getType().name());
        }
        self.setArguments(values);
        boolean translate = owner == ExceptionTypes.UNICODE_TRANSLATE_ERROR;
        Arguments.parsed(values.length, translate ? 4 : 5, translate ? 4 : 5);
        PyObject encoding = null;
        PyObject object;
        long start;
        long end;
        PyObject reason;
        if (translate) {
            object = Arguments.text(1, values[0]);
            start = Numbers.size(values[1]);
            end = Numbers.size(values[2]);
            reason = Arguments.text(4, values[3]);
        } else {
            boolean decode = owner == ExceptionTypes.UNICODE_DECODE_ERROR;
            encoding = Arguments.text(1, values[0]);
            object = decode ? values[1] : Arguments.text(2, values[1]);
            start = Numbers.size(values[2]);
            end = Numbers.size(values[3]);
            reason = Arguments.text(5, values[4]);
            if (decode) {
                object = octets(object);
            }
        }
        if (!translate) {
            self.writeMemberField("encoding", encoding);
        }
        self.writeMemberField("object", object);
        self.writeUnicodeRange(start, end);
        self.writeMemberField("reason", reason);
        return PyNone.INSTANCE;
    }

    /** {@code PyBytes_Check} and then {@code PyObject_GetBuffer}: the object a decode keeps. */
    private static PyObject octets(PyObject object) {
        if (object instanceof PyBytes) {
            return object;
        }
        throw Slots.typeError(
                "a bytes-like object is required, not '%s'", object.getType().name());
    }

    /**
     * {@code AttributeError_getstate}: the fields beside the dictionary, in a copy of it.
     *
     * <p>The one {@code __getstate__} the family files for itself; every other class answers with {@code
     * object}'s, which looks at nothing but the dictionary. Reading it makes no dictionary — CPython reads
     * {@code self->dict} rather than asking for the attribute — and the state is a copy of what is there,
     * so {@code e.__getstate__() is e.__dict__} is false whichever way the value was made.
     */
    private static PyObject attributeGetstate(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver("__getstate__", ExceptionTypes.ATTRIBUTE_ERROR, args, kwNames);
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return self.attributeState();
    }

    /**
     * {@code AttributeError_reduce}: always three places, because the state always has the arguments in it.
     *
     * <p>{@code BaseException}'s rule of two pointers cannot be this class's: {@code self->args} is a
     * tuple even for a call that gave no arguments, so the third place is never missing and the pair is
     * never the answer.
     */
    private static PyObject attributeReduce(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver("__reduce__", ExceptionTypes.ATTRIBUTE_ERROR, args, kwNames);
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return self.attributeReduced();
    }

    /**
     * {@code ImportError_reduce}: the pair when no field is set and there is nothing to restore.
     *
     * <p>The state this class hands the third place is {@code ImportError_getstate}'s answer, which {@code
     * object}'s {@code __getstate__} is not: {@code msg} never counts, and {@code name}, {@code path} and
     * {@code name_from} count only as pointers — so deleting the name the call gave takes the third place
     * away again, and a value whose dictionary was only ever read still reduces to a pair.
     */
    private static PyObject importErrorReduce(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver("__reduce__", ExceptionTypes.IMPORT_ERROR, args, kwNames);
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return self.importReduced();
    }

    /** The arguments of a call to one of the family's methods, with the receiver stripped off. */
    private static PyObject[] positionalsAfterReceiver(PyObject[] args, String[] kwNames) {
        return Arrays.copyOfRange(args, 1, Arguments.positional(args, kwNames));
    }

    /**
     * {@code BaseExceptionGroup_new}: the two arguments, and then the class the value is made as.
     *
     * <p>The class argument is stripped and the keywords thrown away, exactly as {@code tp_new_wrapper}
     * and a {@code PyArg_ParseTuple} that names no keyword table do, so the call that reaches here by
     * hand is checked by {@link PyBaseExceptionGroup#create} and nothing else.
     */
    private static PyObject groupNew(PyObject[] args, String[] kwNames, String qualified) {
        int given = Arguments.positional(args, kwNames);
        if (given < 1) {
            throw Slots.typeError("BaseExceptionGroup.__new__(): not enough arguments");
        }
        PyObject first = args[0];
        if (!(first instanceof PyType kind)) {
            throw Slots.typeError(
                    "BaseExceptionGroup.__new__(X): X is not a type object (%s)",
                    first.getType().name());
        }
        if (!kind.matches(ExceptionTypes.BASE_EXCEPTION_GROUP)) {
            throw Slots.typeError(
                    "BaseExceptionGroup.__new__(%s): %s is not a subtype of BaseExceptionGroup",
                    kind.name(), kind.name());
        }
        return PyBaseExceptionGroup.create(
                kind, Arrays.copyOfRange(args, 1, given), false);
    }

    /**
     * {@code BaseExceptionGroup_init}: the keyword refusal {@code BaseException_init} does not make,
     * worded under the class the value is.
     *
     * <p>A promoted group is therefore refused as {@code ExceptionGroup() takes no keyword arguments} —
     * {@code _PyArg_NoKeywords} reads {@code Py_TYPE(self)} after {@code __new__} has already decided
     * which of the two classes the value belongs to.
     */
    private static PyObject groupInit(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        exceptionReceiver(
                                "__init__", ExceptionTypes.BASE_EXCEPTION_GROUP, args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s() takes no keyword arguments", self.getType().name());
        }
        self.setArguments(tail(args, kwNames));
        return PyNone.INSTANCE;
    }

    /**
     * {@code BaseExceptionGroup_derive}: a group of this one's shape, holding the children given.
     *
     * <p>Like {@link #withTraceback} and {@link #addNote}, this family's three name themselves by the read
     * that reached them: {@code g.split()} of an {@code ExceptionGroup} complains as {@code
     * BaseExceptionGroup.split()}, which is the descriptor called with the receiver, while {@code
     * m = g.split; m()} complains as {@code ExceptionGroup.split()}, which is the bound copy naming the
     * class of what it was made from.
     */
    private static PyObject groupDerive(PyObject[] args, String[] kwNames, String qualified) {
        PyObject self =
                methodReceiver("derive", ExceptionTypes.BASE_EXCEPTION_GROUP, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        return PyBaseExceptionGroup.derive(self, rest[0]);
    }

    /** {@code BaseExceptionGroup_split}: the two halves a clause is built out of. */
    private static PyObject groupSplit(PyObject[] args, String[] kwNames, String qualified) {
        PyObject self = methodReceiver("split", ExceptionTypes.BASE_EXCEPTION_GROUP, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        return PyBaseExceptionGroup.split(self, rest[0]);
    }

    /** {@code BaseExceptionGroup_subgroup}: the matching half alone, or {@code None}. */
    private static PyObject groupSubgroup(PyObject[] args, String[] kwNames, String qualified) {
        PyObject self =
                methodReceiver("subgroup", ExceptionTypes.BASE_EXCEPTION_GROUP, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne(qualified + "()", rest, kwNames);
        return PyBaseExceptionGroup.subgroup(self, rest[0]);
    }

    /** Whether the call named anything, which a zero-length keyword list does not. */
    private static boolean hasKeywords(String[] kwNames) {
        return kwNames != null && kwNames.length > 0;
    }

    /**
     * The exception a {@code wrapper_descriptor} of an exception family was called on.
     *
     * <p>Same two refusals as {@link #wrapperReceiver}, in the declaring type's own words: an empty
     * argument list reaches the first, and anything that is not an exception the second. The type is the
     * one holding the method, so a group's {@code __init__} complains about {@code BaseExceptionGroup}
     * even when the value it was read from is an {@code ExceptionGroup}.
     */
    private static PyObject exceptionReceiver(
            String name, PyType declaring, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError(
                    "descriptor '%s' of '%s' object needs an argument", name, declaring.name());
        }
        if (!declaring.instanceOf(args[0])) {
            throw Slots.typeError(
                    "descriptor '%s' requires a '%s' object but received a '%s'",
                    name, declaring.name(), args[0].getType().name());
        }
        return args[0];
    }

    /**
     * The value a {@code method_descriptor} was called on, checked against the type that declares it.
     *
     * <p>{@code Bare.with_traceback()} says the receiver is missing in the unbound method's words, and
     * {@code Bare.with_traceback("x")} that the method does not apply to what was handed over.
     */
    private static PyObject methodReceiver(
            String name, PyType declaring, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError("unbound method %s.%s() needs an argument", declaring.name(), name);
        }
        if (!declaring.instanceOf(args[0])) {
            throw Slots.typeError(
                    "descriptor '%s' for '%s' objects doesn't apply to a '%s' object",
                    name, declaring.name(), args[0].getType().name());
        }
        return args[0];
    }

    /**
     * {@code object.__init_subclass__}, which takes nothing.
     *
     * <p>The two refusals name the class the hook was bound to by its qualified name —
     * {@code Outer.Inner.__init_subclass__() takes no keyword arguments} — because CPython builds that
     * name from the bound class when it binds a {@code METH_CLASS} method. Reached unbound, the class
     * arrives as the first argument instead, and so does the refusal of anything that is not a class.
     */
    private static PyObject initSubclass(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = classReceiver("__init_subclass__", PyType.OBJECT, args, kwNames);
        if (hasKeywords(kwNames)) {
            throw Slots.typeError("%s.__init_subclass__() takes no keyword arguments", kind.dottedName());
        }
        int given = Arguments.positional(args, kwNames) - 1;
        if (given > 0) {
            throw Slots.typeError(
                    "%s.__init_subclass__() takes no arguments (%d given)", kind.dottedName(), given);
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code type.__prepare__(mcs, name, bases, **kwds)}: a plain dict, whatever the body asked for.
     *
     * <p>No argument is checked, because {@code type_prepare} checks none: the reference interpreter
     * answers {@code {}} for {@code type.__prepare__('s', 'n', ())} as readily as for the call the class
     * statement makes. A metaclass that wants a different namespace overrides it, and the override is what
     * a program sees.
     */
    private static PyObject prepare(PyObject[] args, String[] kwNames, String qualified) {
        return PyDict.empty();
    }

    /**
     * The class a {@code wrapper_descriptor} was called on.
     *
     * <p>An unbound one takes its receiver as an argument rather than keeping it, and these two refusals
     * are {@code supercheck}'s own: the first is what reading the argument list empty reaches, the second
     * what reaches it with anything that is not a class.
     */
    private static PyType wrapperReceiver(String name, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError("descriptor '%s' of 'type' object needs an argument", name);
        }
        if (!(args[0] instanceof PyType kind)) {
            throw Slots.typeError(
                    "descriptor '%s' requires a 'type' object but received a '%s'",
                    name, args[0].getType().name());
        }
        return kind;
    }

    /** The arguments a wrapper consumed its receiver from, still in vectorcall layout. */
    private static PyObject[] tail(PyObject[] args, String[] kwNames) {
        PyObject[] rest = new PyObject[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    /** The same argument list with {@code receiver} in front of it, which is how a bound method runs. */
    private static PyObject[] prepend(PyObject receiver, PyObject[] args) {
        PyObject[] withReceiver = new PyObject[args.length + 1];
        withReceiver[0] = receiver;
        System.arraycopy(args, 0, withReceiver, 1, args.length);
        return withReceiver;
    }

    // --- the attribute slots ---------------------------------------------------------------

    /**
     * {@code object.__getattribute__(x, name)}: the lookup every value inherits.
     *
     * <p>The name is checked before anything is read, and the read is the ordinary one — a slot wrapper
     * calls {@code PyObject_GetAttr}, which starts the lookup again from the top rather than reaching into
     * the dictionary beside it.
     */
    private static PyObject objectGetAttribute(PyObject[] args, String[] kwNames, String qualified) {
        return Slots.getAttr(args[0], attributeName(args[1]));
    }

    /**
     * {@code object.__setattr__(x, name, value)}: {@link GenericAttributes}' write, and nothing above it.
     *
     * <p>This is the reason {@code super().__setattr__(name, value)} ends rather than comes back round: the
     * method a class declares is not consulted here, so the call reaches the descriptor the class files, or
     * the dictionary, exactly once. Going through {@link Slots} instead would run the value's own {@code
     * __setattr__} again from the top of the order and recurse.
     *
     * <p>The Carlo Verre hack comes first: {@code object.__setattr__(int, 'x', 1)} is refused rather than
     * allowed to land a name in a class dictionary behind {@code type_setattro}'s back. The sentence names
     * the receiver's <em>metatype</em>, which is why a class and a {@code type} subclass both answer {@code
     * type object}.
     */
    private static PyObject objectSetAttribute(PyObject[] args, String[] kwNames, String qualified) {
        hackChecked(args[0], "__setattr__");
        GenericAttributes.write(args[0], attributeName(args[1]), args[2]);
        return PyNone.INSTANCE;
    }

    /** {@code object.__delattr__(x, name)}: the same write with the name taken back out. */
    private static PyObject objectDelAttribute(PyObject[] args, String[] kwNames, String qualified) {
        hackChecked(args[0], "__delattr__");
        GenericAttributes.delete(args[0], attributeName(args[1]));
        return PyNone.INSTANCE;
    }

    /**
     * {@code hackcheck}: the guard {@code wrap_setattr} and {@code wrap_delattr} run before handing the
     * value to the slot they wrap.
     *
     * <p>It looks for the C-level function the receiver's type actually uses for the write and refuses the
     * call when the wrapped one it is about to make would jump over an override in between — for a class,
     * {@code type_setattro}. {@code object.__getattribute__} has no such guard, since reading is the same
     * function wherever in the MRO it came from.
     */
    private static void hackChecked(PyObject receiver, String what) {
        if (receiver instanceof PyType kind) {
            throw Slots.typeError("can't apply this %s to %s object", what, kind.getType().filedName());
        }
    }

    /**
     * {@code type.__getattribute__(cls, name)}: the metatype's order, asked of the class it was given.
     *
     * <p>The receiver has to be a class before the name is read, which is why {@code type.__getattribute__
     * ('x', 'upper')} complains about the {@code str} it was handed rather than about a name {@code str}
     * does answer.
     */
    private static PyObject typeGetAttribute(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = wrapperReceiver("__getattribute__", args, kwNames);
        return Slots.getAttr(kind, attributeName(args[1]));
    }

    /**
     * {@code type.__setattr__(cls, name, value)}: a class's own dictionary, or the gate above it.
     *
     * <p>{@code type_setattro} checks that the type is one Python built <em>before</em> it looks at the
     * name, so {@code type.__setattr__(int, 'x', 1)} and {@code type.__setattr__(int, 3, 4)} are both
     * refused as an immutable type, the second with the name printed in its own repr — the one place in
     * this file where a non-text name reaches that far.
     *
     * <p>The write is {@link PyClass#write} rather than {@link Slots#setAttr} because this is the half with
     * no hook in it: a metaclass's {@code super().__setattr__(name, value)} has to land the name, not look
     * the metaclass's own {@code __setattr__} up again.
     */
    private static PyObject typeSetAttribute(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = wrapperReceiver("__setattr__", args, kwNames);
        if (!(kind instanceof PyClass declaring)) {
            throw Slots.typeError(
                    "cannot set %s attribute of immutable type '%s'", named(args[1]), kind.filedName());
        }
        declaring.write(attributeName(args[1]), args[2]);
        return PyNone.INSTANCE;
    }

    /** {@code type.__delattr__(cls, name)}: the delete, refused with the <em>setting</em> sentence. */
    private static PyObject typeDelAttribute(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = wrapperReceiver("__delattr__", args, kwNames);
        if (!(kind instanceof PyClass declaring)) {
            throw Slots.typeError(
                    "cannot set %s attribute of immutable type '%s'", named(args[1]), kind.filedName());
        }
        declaring.write(attributeName(args[1]), null);
        return PyNone.INSTANCE;
    }

    /** The name an attribute slot was handed: text, or CPython's refusal of anything else. */
    private static String attributeName(PyObject value) {
        if (value instanceof PyStr text) {
            return text.getValue();
        }
        throw Slots.typeError("attribute name must be string, not '%s'", value.getType().name());
    }

    /** The name as {@code type_setattro} prints it: quoted for text, and in its own repr otherwise. */
    private static String named(PyObject value) {
        return value instanceof PyStr text ? "'" + text.getValue() + "'" : text(value);
    }

    private static String text(PyObject value) {
        try {
            return Slots.repr(value);
        } catch (PyRaise refused) {
            return "<unprintable " + value.getType().name() + " object>";
        }
    }

    /**
     * {@code object.__subclasshook__}: the test {@code issubclass} lets a class answer for itself.
     *
     * <p>It declines every question, because the abstract bases that would answer it are in the standard
     * library this runtime does not import. The name in the arity complaint is the class the read bound
     * the hook to, in its dotted form: {@code Outer.Inner.__subclasshook__()}.
     */
    private static PyObject subclassHook(PyObject[] args, String[] kwNames, String qualified) {
        PyType kind = classReceiver("__subclasshook__", PyType.OBJECT, args, kwNames);
        Arguments.exactlyOne(kind.dottedName() + ".__subclasshook__()", tail(args, kwNames), kwNames);
        return PyNotImplemented.INSTANCE;
    }

    /** {@code type.__dir__(cls)}: the class's own dictionary first, and never its metatype's. */
    private static PyObject typeDir(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return nameList(Dirs.classNames((PyType) args[0]));
    }

    /** {@code type.__sizeof__(cls)}: the size of a type this runtime has no layout for. */
    private static PyObject typeSize(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return PyInt.of(0);
    }

    /**
     * {@code object.__reduce_ex__(protocol)}: pickle's other helper.
     *
     * <p>The reference interpreter answers a real recipe here — {@code copyreg.__newobj__} plus the value
     * and its state — for a protocol of two or more, and this runtime has no {@code copyreg} to reach.
     * The refusal is the one {@code __reduce__} gives, which is what CPython falls back to for protocol
     * zero and one.
     *
     * <p>Both halves name themselves {@code object.__reduce_ex__} however they were reached:
     * {@code A().__reduce_ex__(1, 2)} says {@code object.__reduce_ex__() takes exactly one argument (2
     * given)} although {@code A().__reduce_ex__.__qualname__} is {@code A.__reduce_ex__}, because the
     * sentence is written into {@code object.c} rather than built from the value.
     */
    private static PyObject objectReduceEx(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.exactlyOne(qualified + "()", tail(args, kwNames), kwNames);
        return unpicklable(args[0]);
    }

    private static PyObject objectReduce(PyObject[] args, String[] kwNames, String qualified) {
        Arguments.none(qualified + "()", tail(args, kwNames), kwNames);
        return unpicklable(args[0]);
    }

    /** The one sentence both halves of pickle's protocol answer with, naming the value's class. */
    private static PyObject unpicklable(PyObject value) {
        throw Slots.typeError("cannot pickle '%s' object", value.getType().filedName());
    }

    /**
     * The class a {@code classmethod_descriptor} was called with.
     *
     * <p>The two refusals are {@code descr_classmethod_call}'s own: the first is what an empty argument
     * list reaches, the second what reaches it with anything that is not a type — which is why the message
     * counts the class as arg 2 and names the declaring type rather than the one asked.
     */
    private static PyType classReceiver(String name, PyType declaring, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError(
                    "descriptor '%s' of '%s' object needs an argument", name, declaring.name());
        }
        if (!(args[0] instanceof PyType kind)) {
            throw Slots.typeError(
                    "descriptor '%s' for type '%s' needs a type, not a '%s' as arg 2",
                    name, declaring.name(), args[0].getType().filedName());
        }
        return kind;
    }

    /**
     * {@code wrapperdescr_call}'s four gates, before a slot wrapper runs.
     *
     * <p>Two of the four gates run whatever slot is being called, and the other two only for the slots that
     * count their arguments. {@code tp_init} and {@code tp_call} hand the whole argument list, keywords
     * included, to the slot they wrap, so their own wrappers never complain about how the call was written
     * or how long the list is: {@code object.__init__(3, x=1)} answers {@code None} and
     * {@code type.__init__(A, x=1)} is refused by the count the slot itself makes. What they do not escape
     * is the emptiness and the receiver check, which {@code wrapperdescr_call} runs before it has worked
     * out which slot it is calling — {@code dict.__dict__['__init__'](3)} says the {@code int} is not a
     * {@code dict} rather than reaching the {@code dict_init} that would cast it.
     *
     * <p>The order of the rest is visible and not arbitrary: the receiver comes first, because
     * {@code bytes.__dict__['__len__'](3, x=1)} complains about the {@code int} rather than about the
     * keyword, and {@code (3, 4)} complains about the {@code int} rather than about the count. The keyword
     * comes next, because {@code bytes.__dict__['__len__'](b'', x=1)} has by then nothing left to complain
     * about except how the call was written.
     *
     * <p>Every type is checked against its own values. {@code object} files the {@code tp_*} slots of
     * everything and so refuses nothing, since every value is one of its own; {@code type} files the
     * metaclass slots, which a value cannot be asked for; and {@code bytes} and {@code str} each file the
     * seventeen sequence and number slots their structs fill in, which is where the phrase "requires a
     * 'bytes' object" comes from. {@code int} files the whole of its number struct bar the in-place half,
     * and the two coercions {@code __int__} and {@code __float__} sit in the middle of that block rather
     * than at the end.
     */
    private static void wrapperCall(
            PyType declaring, String name, PyObject[] args, String[] kwNames) {
        if (Arguments.positional(args, kwNames) < 1) {
            throw Slots.typeError(
                    "descriptor '%s' of '%s' object needs an argument", name, declaring.name());
        }
        if (!declaring.instanceOf(args[0])) {
            throw Slots.typeError(
                    "descriptor '%s' requires a '%s' object but received a '%s'",
                    name, declaring.name(), args[0].getType().name());
        }
        int extra = slotExtra(name);
        if (extra < 0) {
            return;
        }
        Arguments.noWrapperKeywords(name, kwNames);
        slotArity(name, extra, Arguments.positional(args, kwNames) - 1);
    }

    /**
     * The number of arguments a slot wrapper takes besides its receiver, which is a property of the slot
     * and not of the type that files it.
     *
     * <p>{@code wrapperdescr_call} counts them before it calls anything, and the two that answer a whole
     * argument list — {@code tp_init} and {@code tp_call} — are the ones it leaves uncounted. Every other
     * slot here is {@code PyObject_} something over the receiver plus one or two arguments; only
     * {@code tp_ass_subscript} is handed two, which is why a {@code list} that was told to set an item
     * without a value counts to one and says so. The seven that answer the receiver alone are the five
     * unary number slots and the two coercions beside them: {@code wrap_unaryfunc} and {@code
     * wrap_inquirypred} both call {@code check_num_args(args, 0)}.
     */
    private static int slotExtra(String name) {
        return switch (name) {
            case "__setattr__", "__setitem__" -> 2;
            case "__init__", "__call__" -> -1;
            case "__repr__",
                    "__str__",
                    "__hash__",
                    "__iter__",
                    "__len__",
                    "__neg__",
                    "__pos__",
                    "__abs__",
                    "__bool__",
                    "__invert__",
                    "__int__",
                    "__float__",
                    "__index__" -> 0;
            default -> 1;
        };
    }

    /**
     * {@code wrapperdescr_call}'s refusal of the wrong count.
     *
     * <p>A slot that takes only its receiver says the number bare — {@code expected 0 arguments, got 1} —
     * because there is no method name that would help. Three wrappers name themselves, and each is the
     * because of the C function behind it: {@code wrap_setattr} hands {@code PyArg_UnpackTuple} the name
     * {@code __setattr__}, {@code wrap_ass_subscript} hands it {@code __setitem__}, and {@code wrap_buffer}
     * hands it {@code __buffer__}. Everything else, including the {@code __delattr__} and
     * {@code __getattribute__} that sit beside the first and the {@code __delitem__} beside the second,
     * passes no name and so prints none.
     *
     * <p>{@code __pow__} and {@code __rpow__} are the two that state a range rather than a number, because
     * {@code check_pow_args} is written for a slot that may or may not be handed a modulus: {@code
     * int.__pow__(2)} says {@code expected 1 or 2 arguments, got 0} where every other one-argument slot
     * would say {@code expected 1 argument, got 0}.
     */
    private static void slotArity(String name, int need, int got) {
        if (got == need) {
            return;
        }
        if (name.equals("__pow__") || name.equals("__rpow__")) {
            if (got >= 1 && got <= 2) {
                return;
            }
            throw Slots.typeError("expected 1 or 2 arguments, got %d", got);
        }
        String plural = need == 1 ? "" : "s";
        if (name.equals("__setattr__")
                || name.equals("__setitem__")
                || name.equals("__buffer__")) {
            throw Slots.typeError(
                    "%s expected %d argument%s, got %d", name, need, plural, got);
        }
        throw Slots.typeError("expected %d argument%s, got %d", need, plural, got);
    }

    /**
     * The names one type files, written into the one dictionary every window over it shows.
     *
     * <p>Each of the seven ways is one of CPython's: a {@code PyMethodDef} with the {@code METH_*} flag
     * that decides what binds to it, a {@code PyGetSetDef}, a {@code PyMemberDef}, or the entry a
     * {@code tp_dict} holds outright. The tables above read as those lists, in the order {@code X.__dict__}
     * shows them.
     */
    private static final class Table {

        private final PyType owner;
        private final PyDict into;

        Table(PyType owner, PyDict into) {
            this.owner = owner;
            this.into = into;
        }

        /** Whether the name is filed already, which is how a type's own {@code __doc__} survives. */
        boolean holds(String name) {
            return into.get(PyStr.interned(name)) != null;
        }

        /** An entry another part of the runtime already keeps, filed as this type's own. */
        void put(String name, PyObject entry) {
            into.put(PyStr.interned(name), entry);
        }

        /** A value the dictionary holds outright: a string, a {@code None}, a class's own constant. */
        void value(String name, PyObject entry) {
            put(name, entry);
        }

        /** {@code slot_tp_*}: the C function behind a double-underscore slot. */
        void wrapper(String name, String doc, Method body) {
            put(name, new Member(owner, name, Shape.WRAPPER, null, doc, body));
        }

        void wrapper(String name, String doc, Receiver body) {
            wrapper(name, doc, (args, kwNames, qualified) -> body.apply(args));
        }

        /** {@code METH_VARARGS} over a value: the method a value of this type answers. */
        void method(String name, String doc, Method body) {
            put(name, new Member(owner, name, Shape.METHOD, null, doc, body));
        }

        void method(String name, String doc, Receiver body) {
            method(name, doc, (args, kwNames, qualified) -> body.apply(args));
        }

        /** {@code METH_CLASS}: binds the class the read ran on, and never a value. */
        void classMethod(String name, String doc, Method body) {
            put(name, new Member(owner, name, Shape.CLASSMETHOD, null, doc, body));
        }

        /**
         * The names a {@link Members} table declares, filed in the order the dictionary shows them.
         *
         * <p>A table's order is its declaration order, and the reference interpreter does not keep one
         * order: {@code bytes} files {@code maketrans} between {@code lstrip} and {@code partition}, where
         * {@link Members#names} would collect it at the tail with the names that take no receiver, and
         * {@code str} puts {@code __format__} and {@code __sizeof__} after the forty-six methods a person
         * calls. Each entry is the one {@link Members#describe} hands out, so the dictionary and a read of
         * the type hold the same object rather than two that compare equal.
         */
        void named(String... names) {
            Members methods = Members.tableOf(owner);
            for (String name : names) {
                PyObject entry = methods.describe(name);
                if (entry == PyNotImplemented.INSTANCE) {
                    throw Slots.runtimeError(
                            "the " + owner.name() + " table files no member named '" + name + "'");
                }
                put(name, entry);
            }
        }

        /** {@code __new__}: a plain static method in the dictionary, which is not a descriptor at all. */
        void raw(String name, String doc, Method body) {
            put(name, new Member(owner, name, Shape.RAW, null, doc, body));
        }

        /** {@code PyGetSetDef}: two C functions, the second one null where the name cannot be written. */
        void getset(String name, String doc, PyGetSetDescriptor.Getter read, PyGetSetDescriptor.Setter write) {
            put(name, new PyGetSetDescriptor(owner, name, doc, read, write));
        }

        /**
         * {@code PyMemberDef} over a field of the type's own struct.
         *
         * <p>The four sizes and offsets answer nought, because a type here is a Java object rather than a
         * {@code PyTypeObject} with a layout to measure; {@code __base__} is the one offset this runtime
         * does keep, and is named by the read that answers it.
         */
        void member(String name) {
            member(name, self -> PyInt.of(0));
        }

        void member(String name, PyMemberDescriptor.Reader read) {
            member(name, null, read, null);
        }

        /**
         * An offset the reference interpreter lets a value be written through: {@code
         * BaseException.__suppress_context__} is a {@code T_BOOL}, the exception fields a {@code
         * _Py_T_OBJECT}.
         */
        void member(
                String name,
                PyMemberDescriptor.Reader read,
                PyMemberDescriptor.Writer write) {
            member(name, null, read, write);
        }

        /**
         * The same offset with the sentence its {@code PyMemberDef} carries: the four of {@code OSError}
         * each say what they are, and {@code BaseException.__suppress_context__} says nothing.
         */
        void member(
                String name,
                String doc,
                PyMemberDescriptor.Reader read,
                PyMemberDescriptor.Writer write) {
            put(name, new PyMemberDescriptor(owner, name, doc, read, write));
        }
    }

    /** A member that takes its receiver and nothing else, which most of {@code object}'s and {@code
     * mappingproxy}'s slots do. */
    private interface Receiver {
        PyObject apply(PyObject[] args);
    }

    /**
     * How CPython keeps one of these names, which is what decides what reading it hands out.
     *
     * <p>{@link #WRAPPER} and {@link #METHOD} bind to the value — or, for a lookup over a class's
     * order, to nothing at all; {@link #CLASSMETHOD} binds to the class the lookup ran on whatever else
     * it was given; {@link #RAW} is not a descriptor and so binds nothing.
     */
    private enum Shape {
        WRAPPER,
        METHOD,
        CLASSMETHOD,
        RAW;

        /** The type of the member as its dictionary holds it. */
        PyType describing() {
            return switch (this) {
                case WRAPPER -> PyType.WRAPPER_DESCRIPTOR;
                case METHOD -> PyType.METHOD_DESCRIPTOR;
                case CLASSMETHOD -> PyType.CLASS_METHOD_DESCRIPTOR;
                case RAW -> PyType.BUILTIN_FUNCTION;
            };
        }

        /** The type of the member once a class has been bound to it. */
        PyType bound() {
            return this == WRAPPER ? PyType.METHOD_WRAPPER : PyType.BUILTIN_FUNCTION;
        }
    }

    /**
     * A member of the class system, as the type's own dictionary holds it.
     *
     * <p>Reading it is the only work {@link #asAttribute} does: everything the runtime asks of these
     * names is either this object itself or a {@link Bound} copy of it with a class attached.
     */
    private static final class Member extends PyObject implements Descriptors.Protocol {

        private final PyType declaring;
        private final String name;
        private final Shape shape;
        private final String qualified;
        private final String doc;
        private final Method body;

        Member(PyType declaring, String name, Shape shape, Method body) {
            this(declaring, name, shape, null, null, body);
        }

        /**
         * The four-argument form with the qualified name spelled out, for the one member CPython names
         * for a different class than the one holding it — {@code BaseException.__new__}, whose argument
         * table says {@code Exception.__new__}.
         */
        Member(PyType declaring, String name, Shape shape, String qualified, Method body) {
            this(declaring, name, shape, qualified, null, body);
        }

        /**
         * The full form, which also says what CPython's {@code ml_doc} for this member holds and, when
         * the name it reports is not the declaring class's, what {@code __qualname__} is.
         */
        Member(PyType declaring, String name, Shape shape, String qualified, String doc, Method body) {
            super(shape.describing());
            this.declaring = declaring;
            this.name = name;
            this.shape = shape;
            this.qualified = qualified == null ? declaring.name() + "." + name : qualified;
            this.doc = doc;
            this.body = body;
        }

        @Override
        public PyObject call(PyObject[] args, PyTuple kwNames) {
            String[] keywords = PyBoundMethod.keywordNames(kwNames);
            switch (shape) {
                case WRAPPER -> wrapperCall(declaring, name, args, keywords);
                // `methoddescr_call` wants its receiver among the arguments it was handed and checks it
                // before the body counts anything: `object.__dir__(x=1)` is refused as an absent argument
                // rather than as a keyword.
                case METHOD -> methodReceiver(name, declaring, args, keywords);
                // A `classmethod_descriptor` takes its class out of the argument list, and the bodies that
                // bind one ask for it themselves; `__new__` binds nothing at all.
                case CLASSMETHOD, RAW -> {}
            }
            return body.apply(args, keywords, qualified);
        }

        /** Every kind a dictionary files is callable: a slot, a method, a class method, {@code __new__}. */
        @Override
        public boolean isCallable() {
            return true;
        }

        @Override
        public PyObject asAttribute(PyObject obj, PyObject owner) {
            PyObject receiver =
                    switch (shape) {
                        case CLASSMETHOD -> owner;
                        case RAW -> null;
                        case WRAPPER, METHOD -> obj;
                    };
            return receiver == null ? this : new Bound(declaring, name, shape, body, receiver, doc);
        }

        @Override
        public PyObject getAttr(String attribute) {
            return switch (attribute) {
                case "__name__" -> PyStr.of(name);
                case "__qualname__" -> PyStr.of(qualified);
                case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
                case "__self__" -> shape == Shape.RAW ? declaring : PyNotImplemented.INSTANCE;
                case "__objclass__" -> shape == Shape.RAW ? PyNotImplemented.INSTANCE : declaring;
                // A `builtin_function_or_method` is not a descriptor at all — `__new__` has no `__get__` to
                // ask — while each of the three descriptor kinds files one, which is what `hasattr` reads.
                case "__get__" ->
                        shape == Shape.RAW
                                ? PyNotImplemented.INSTANCE
                                : new PyNativeFunction(
                                        "__get__",
                                        (args, kwNames) -> Descriptors.called(this, args, kwNames));
                default -> PyNotImplemented.INSTANCE;
            };
        }

        @Override
        public boolean eq(PyObject other) {
            // One member per name, shared by every read, so `type.__call__ is Meta.__call__` holds.
            return other == this;
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }

        @Override
        public String repr() {
            String owner = declaring.name();
            return switch (shape) {
                case WRAPPER -> "<slot wrapper '" + name + "' of '" + owner + "' objects>";
                case METHOD -> "<method '" + name + "' of '" + owner + "' objects>";
                case CLASSMETHOD -> "<method '" + name + "' of '" + owner + "' objects>";
                // CPython names the type of the receiver here, and a class's own type is `type` however
                // far down the exception hierarchy the static method sits.
                case RAW -> "<built-in method " + name + " of type object>";
            };
        }
    }

    /**
     * The same member with a class attached to it, which is what a read through a value's type hands out.
     *
     * <p>A fresh object every time, as CPython's {@code PyCFunction_NewEx} is: {@code int.mro is int.mro}
     * is false, and the argument the class supplies at the call is the receiver the body would otherwise
     * have been given by hand.
     */
    private static final class Bound extends PyObject {

        private final PyType declaring;
        private final String name;
        private final Shape shape;
        private final Method body;
        private final PyObject receiver;
        private final String doc;

        /** Whether the method form of {@code LOAD_ATTR} is the one holding this copy. */
        private final boolean leftUnbound;

        Bound(
                PyType declaring,
                String name,
                Shape shape,
                Method body,
                PyObject receiver,
                String doc) {
            this(declaring, name, shape, body, receiver, doc, false);
        }

        Bound(
                PyType declaring,
                String name,
                Shape shape,
                Method body,
                PyObject receiver,
                String doc,
                boolean leftUnbound) {
            super(shape.bound());
            this.declaring = declaring;
            this.name = name;
            this.shape = shape;
            this.body = body;
            this.receiver = receiver;
            this.doc = doc;
            this.leftUnbound = leftUnbound;
        }

        /**
         * The copy the method form hands to {@code CALL}.
         *
         * <p>A {@code method_descriptor} is the one member CPython leaves unbound there — it puts the
         * descriptor and the receiver on the stack together — so a refusal made during that call is written
         * under the class that declares the method. Every other kind is already bound by the read, and a
         * read of {@code int.mro} or {@code e.args} gives the same answer either way.
         */
        @Override
        PyObject methodForm() {
            return shape == Shape.METHOD && !leftUnbound
                    ? new Bound(declaring, name, shape, body, receiver, doc, true)
                    : this;
        }

        @Override
        public PyObject call(PyObject[] args, PyTuple kwNames) {
            String[] keywords = PyBoundMethod.keywordNames(kwNames);
            PyObject[] withReceiver = prepend(receiver, args);
            if (shape == Shape.WRAPPER) {
                wrapperCall(declaring, name, withReceiver, keywords);
            }
            return body.apply(withReceiver, keywords, qualifiedName());
        }

        @Override
        public boolean isCallable() {
            return true;
        }

        /**
         * The name the arity complaint and {@code __qualname__} both use.
         *
         * <p>CPython's {@code meth_get__qualname__} reads the class of whatever the method bound to: a
         * value's own class, so {@code E("x").with_traceback} says {@code E.with_traceback} even though
         * {@code BaseException} is what declares the method, or the class itself when the receiver is one.
         * A bound wrapper is the exception — it keeps the name of the type that declares the slot, which
         * is why {@code Plain.__call__.__qualname__} is {@code type.__call__} while {@code C.mro} of a
         * class {@code C} is {@code C.mro}. The copy the method form of {@code LOAD_ATTR} leaves unbound is
         * the other exception, and only a call ever sees that name.
         */
        private String qualifiedName() {
            if (leftUnbound) {
                return declaring.name() + "." + name;
            }
            String owner =
                    shape == Shape.WRAPPER
                            ? declaring.name()
                            : receiver instanceof PyType kind
                                    ? kind.dottedName()
                                    : receiver.getType().dottedName();
            return owner + "." + name;
        }

        @Override
        public PyObject getAttr(String attribute) {
            return switch (attribute) {
                case "__name__" -> PyStr.of(name);
                case "__qualname__" -> PyStr.of(qualifiedName());
                case "__self__" -> receiver;
                case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
                // Only a bound slot wrapper keeps the type that declared its slot: CPython reads the class
                // out of the wrapper record a method-wrapper still points at, and a built-in method bound
                // to a value — `b''.upper`, `bytes.fromhex` — has no such record to read.
                case "__objclass__" ->
                        shape == Shape.WRAPPER ? declaring : PyNotImplemented.INSTANCE;
                default -> PyNotImplemented.INSTANCE;
            };
        }

        @Override
        public boolean eq(PyObject other) {
            // CPython's method_compare: the method it wraps and the object it wrapped, both by identity.
            // So two reads of the same member off the same value answer equal without being one object —
            // `named.split == named.split` is true and `named.split is named.split` is false, because
            // each read makes a fresh bound copy the way PyCFunction_NewEx does.
            return other instanceof Bound bound
                    && bound.declaring == declaring
                    && bound.name.equals(name)
                    && bound.receiver == receiver;
        }

        @Override
        public long hash() {
            return name.hashCode() * 31 + receiver.hash();
        }

        @Override
        public String repr() {
            return shape == Shape.WRAPPER
                    ? "<method-wrapper '" + name + "' of " + receiver.getType().name() + " object>"
                    : "<built-in method " + name + " of " + receiver.getType().name() + " object>";
        }
    }
}
