package org.python.core;

import java.util.Arrays;

/**
 * The members the built-ins the class system is built on keep in their own dictionaries: {@code type}'s
 * {@code __new__}, {@code __init__}, {@code __call__}, {@code mro}, {@code __prepare__} and the two
 * instance tests, {@code object}'s {@code __init_subclass__}, {@code BaseException}'s two halves of
 * making a value plus {@code with_traceback}, and {@code BaseExceptionGroup}'s two halves plus the three
 * methods that work over the children it carries.
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

    /** {@code type.__new__(mcs, name, bases, dict)}: the metaclass idiom, and no descriptor at all. */
    static final PyObject NEW =
            new Member(PyType.TYPE, "__new__", Shape.RAW, null, NEW_DOC, TypeDict::newMember);

    /** {@code type.__init__(cls, name, bases, dict)}: the checks {@code type_init} makes and nothing else. */
    static final PyObject INIT =
            new Member(
                    PyType.TYPE, "__init__", Shape.WRAPPER, null, INIT_DOC, TypeDict::initMember);

    /** {@code type.__call__(cls, *args)}: {@code type_call} for the class given as the receiver. */
    static final PyObject CALL =
            new Member(
                    PyType.TYPE,
                    "__call__",
                    Shape.WRAPPER,
                    null,
                    "Call self as a function.",
                    TypeDict::callMember);

    /** {@code cls.mro()}: the lookup order as a list. */
    static final PyObject MRO =
            new Member(
                    PyType.TYPE,
                    "mro",
                    Shape.METHOD,
                    null,
                    "Return a type's method resolution order.",
                    TypeDict::mroMember);

    /** {@code type.__prepare__(mcs, name, bases, **kwds)}: always a plain dict, whatever it is asked. */
    static final PyObject PREPARE =
            new Member(
                    PyType.TYPE,
                    "__prepare__",
                    Shape.CLASSMETHOD,
                    null,
                    "Create the namespace for the class statement",
                    (args, kwNames, qualified) -> PyDict.empty());

    /** {@code type.__instancecheck__(cls, inst)}: the test {@code isinstance} makes without a hook. */
    static final PyObject INSTANCE_CHECK =
            new Member(
                    PyType.TYPE,
                    "__instancecheck__",
                    Shape.METHOD,
                    null,
                    "Check if an object is an instance.",
                    TypeDict::instanceCheck);

    /** {@code type.__subclasscheck__(cls, sub)}: the test {@code issubclass} makes without a hook. */
    static final PyObject SUBCLASS_CHECK =
            new Member(
                    PyType.TYPE,
                    "__subclasscheck__",
                    Shape.METHOD,
                    null,
                    "Check if a class is a subclass.",
                    TypeDict::subclassCheck);

    /** {@code object.__init_subclass__(cls, **kwds)}: the hook that refuses what it was not asked for. */
    static final PyObject INIT_SUBCLASS =
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
     * <p>CPython reports this one name under {@code Exception} rather than under the class holding it,
     * because its argument table was written for that class: {@code Bare.__new__.__qualname__} is
     * {@code Exception.__new__}.
     */
    static final PyObject EXCEPTION_NEW =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "__new__",
                    Shape.RAW,
                    "Exception.__new__",
                    NEW_DOC,
                    TypeDict::exceptionNew);

    /** {@code BaseException.__init__(self, *args)}: the arguments of the call, taken over. */
    static final PyObject EXCEPTION_INIT =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "__init__",
                    Shape.WRAPPER,
                    null,
                    INIT_DOC,
                    TypeDict::exceptionInit);

    /** {@code e.with_traceback(traceback)}: the one method an exception value answers with. */
    static final PyObject WITH_TRACEBACK =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION,
                    "with_traceback",
                    Shape.METHOD,
                    null,
                    "Set self.__traceback__ to tb and return self.",
                    TypeDict::withTraceback);

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
    static final PyObject GROUP_NEW =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "__new__",
                    Shape.RAW,
                    null,
                    NEW_DOC,
                    TypeDict::groupNew);

    /** {@code BaseExceptionGroup.__init__(self, *args)}: the arguments taken over, keywords refused. */
    static final PyObject GROUP_INIT =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "__init__",
                    Shape.WRAPPER,
                    null,
                    INIT_DOC,
                    TypeDict::groupInit);

    /** {@code g.derive(exceptions)}: a group of the same shape holding the children given. */
    static final PyObject GROUP_DERIVE =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "derive",
                    Shape.METHOD,
                    TypeDict::groupDerive);

    /** {@code g.split(matcher)}: the matching part and the rest, as a two-tuple. */
    static final PyObject GROUP_SPLIT =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "split",
                    Shape.METHOD,
                    TypeDict::groupSplit);

    /** {@code g.subgroup(matcher)}: the matching part alone, or {@code None}. */
    static final PyObject GROUP_SUBGROUP =
            new Member(
                    ExceptionTypes.BASE_EXCEPTION_GROUP,
                    "subgroup",
                    Shape.METHOD,
                    TypeDict::groupSubgroup);

    private TypeDict() {
    }

    /**
     * The raw member {@code owner}'s own dictionary holds under {@code name}.
     *
     * @return the member, or null when this type keeps nothing there — which is every type but the four
     *     the class system is built on
     */
    static PyObject member(PyType owner, String name) {
        if (owner == PyType.TYPE) {
            return switch (name) {
                case "__new__" -> NEW;
                case "__init__" -> INIT;
                case "__call__" -> CALL;
                case "mro" -> MRO;
                case "__prepare__" -> PREPARE;
                case "__instancecheck__" -> INSTANCE_CHECK;
                case "__subclasscheck__" -> SUBCLASS_CHECK;
                default -> null;
            };
        }
        if (owner == PyType.OBJECT) {
            return name.equals("__init_subclass__") ? INIT_SUBCLASS : null;
        }
        if (owner == ExceptionTypes.BASE_EXCEPTION) {
            return switch (name) {
                case "__new__" -> EXCEPTION_NEW;
                case "__init__" -> EXCEPTION_INIT;
                case "with_traceback" -> WITH_TRACEBACK;
                default -> null;
            };
        }
        // ExceptionGroup's own dictionary holds nothing that is read here, so every one of these names
        // reaches a group through BaseExceptionGroup in its order.
        if (owner == ExceptionTypes.BASE_EXCEPTION_GROUP) {
            return switch (name) {
                case "__new__" -> GROUP_NEW;
                case "__init__" -> GROUP_INIT;
                case "derive" -> GROUP_DERIVE;
                case "split" -> GROUP_SPLIT;
                case "subgroup" -> GROUP_SUBGROUP;
                default -> null;
            };
        }
        return null;
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
     * <p>The three refusals are {@code type_new}'s shape in the exception family's words, and keyword
     * arguments are read and thrown away — {@code Bare.__new__(Bare, flag=1)} makes a value with no
     * arguments at all, which is what a static method whose table only names positionals does.
     */
    private static PyObject exceptionNew(PyObject[] args, String[] kwNames, String qualified) {
        int given = Arguments.positional(args, kwNames);
        if (given < 1) {
            throw Slots.typeError("BaseException.__new__(): not enough arguments");
        }
        PyObject first = args[0];
        if (!(first instanceof PyType kind)) {
            throw Slots.typeError(
                    "BaseException.__new__(X): X is not a type object (%s)",
                    first.getType().name());
        }
        if (!kind.matches(ExceptionTypes.BASE_EXCEPTION)) {
            throw Slots.typeError(
                    "BaseException.__new__(%s): %s is not a subtype of BaseException",
                    kind.name(), kind.name());
        }
        return new PyBaseException(kind, Arrays.copyOfRange(args, 1, given));
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
     * which is how {@code raise e.with_traceback(tb)} keeps reporting the exception it was handed.
     */
    private static PyObject withTraceback(PyObject[] args, String[] kwNames, String qualified) {
        PyBaseException self =
                (PyBaseException)
                        methodReceiver(
                                "with_traceback", ExceptionTypes.BASE_EXCEPTION, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        int given = Arguments.positional(rest, kwNames);
        if (given != 1) {
            throw Slots.typeError(
                    "BaseException.with_traceback() takes exactly one argument (%d given)", given);
        }
        self.setTraceback(rest[0]);
        return self;
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
     * <p>The arity complaints name {@code BaseExceptionGroup} however the value is classed, because they
     * are made from the method's own name rather than from the receiver the way a {@code __qualname__}
     * read is.
     */
    private static PyObject groupDerive(PyObject[] args, String[] kwNames, String qualified) {
        PyObject self =
                methodReceiver("derive", ExceptionTypes.BASE_EXCEPTION_GROUP, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne("BaseExceptionGroup.derive()", rest, kwNames);
        return PyBaseExceptionGroup.derive(self, rest[0]);
    }

    /** {@code BaseExceptionGroup_split}: the two halves a clause is built out of. */
    private static PyObject groupSplit(PyObject[] args, String[] kwNames, String qualified) {
        PyObject self = methodReceiver("split", ExceptionTypes.BASE_EXCEPTION_GROUP, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne("BaseExceptionGroup.split()", rest, kwNames);
        return PyBaseExceptionGroup.split(self, rest[0]);
    }

    /** {@code BaseExceptionGroup_subgroup}: the matching half alone, or {@code None}. */
    private static PyObject groupSubgroup(PyObject[] args, String[] kwNames, String qualified) {
        PyObject self =
                methodReceiver("subgroup", ExceptionTypes.BASE_EXCEPTION_GROUP, args, kwNames);
        PyObject[] rest = tail(args, kwNames);
        Arguments.exactlyOne("BaseExceptionGroup.subgroup()", rest, kwNames);
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
     * name from the bound class when it binds a {@code METH_CLASS} method.
     */
    private static PyObject initSubclass(PyObject[] args, String[] kwNames, String qualified) {
        String owner = args[0] instanceof PyType kind ? kind.dottedName() : args[0].getType().name();
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError("%s.__init_subclass__() takes no keyword arguments", owner);
        }
        if (args.length > 1) {
            throw Slots.typeError(
                    "%s.__init_subclass__() takes no arguments (%d given)", owner, args.length - 1);
        }
        return PyNone.INSTANCE;
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
            return body.apply(args, PyBoundMethod.keywordNames(kwNames), qualified);
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
                case "__get__" ->
                        new PyNativeFunction(
                                "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames));
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
                case CLASSMETHOD -> "<classmethod_descriptor for method '" + name + "'>";
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

        Bound(
                PyType declaring,
                String name,
                Shape shape,
                Method body,
                PyObject receiver,
                String doc) {
            super(shape.bound());
            this.declaring = declaring;
            this.name = name;
            this.shape = shape;
            this.body = body;
            this.receiver = receiver;
            this.doc = doc;
        }

        @Override
        public PyObject call(PyObject[] args, PyTuple kwNames) {
            PyObject[] withReceiver = new PyObject[args.length + 1];
            withReceiver[0] = receiver;
            System.arraycopy(args, 0, withReceiver, 1, args.length);
            return body.apply(
                    withReceiver, PyBoundMethod.keywordNames(kwNames), qualifiedName());
        }

        /**
         * The name the arity complaint and {@code __qualname__} both use.
         *
         * <p>CPython's {@code meth_get__qualname__} reads the class of whatever the method bound to: a
         * value's own class, so {@code E("x").with_traceback} says {@code E.with_traceback} even though
         * {@code BaseException} is what declares the method, or the class itself when the receiver is one.
         * A bound wrapper is the exception — it keeps the name of the type that declares the slot, which
         * is why {@code Plain.__call__.__qualname__} is {@code type.__call__} while {@code C.mro} of a
         * class {@code C} is {@code C.mro}.
         */
        private String qualifiedName() {
            String owner =
                    shape == Shape.WRAPPER
                            ? declaring.name()
                            : receiver instanceof PyType kind
                                    ? kind.name()
                                    : receiver.getType().name();
            return owner + "." + name;
        }

        @Override
        public PyObject getAttr(String attribute) {
            return switch (attribute) {
                case "__name__" -> PyStr.of(name);
                case "__qualname__" -> PyStr.of(qualifiedName());
                case "__self__" -> receiver;
                case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
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
