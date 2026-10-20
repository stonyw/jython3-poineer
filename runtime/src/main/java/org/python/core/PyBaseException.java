package org.python.core;

import java.math.BigInteger;

/**
 * A raised Python exception, as a value.
 *
 * The type is held here rather than inferred from the Java class because the exception hierarchy is
 * open at Python level (M3): {@code class E(ValueError)} has to produce a value that is still a
 * {@code PyBaseException} in Java but reports {@code E} as its class. {@code exceptionType} is that
 * answer, and {@code getType()} is set to the same object so {@code type(e)} agrees with
 * {@code e.__class__}.
 *
 * No traceback yet. CPython 3.14 keeps exception state in the frame and reconstructs the traceback from
 * its exception table: the table is read and used, but a traceback needs the frame chain above it too,
 * which is a later M3 slice. Until then a raise carries only what the user wrote.
 */
public class PyBaseException extends PyObject {

    private final PyType exceptionType;

    /** {@code e.args}, kept as the tuple {@code str} and {@code repr} read from. */
    private PyObject[] args;

    /** The same arguments as the value {@code e.args} answers with, which is one object, not a copy. */
    private PyTuple arguments;

    /** {@code e.__dict__}, made the first time something is written to it. */
    private PyDict dictionary;

    /** The exception {@code raise X from Y} names, kept so that {@code __cause__} is readable. */
    private PyObject cause = PyNone.INSTANCE;

    /** The exception this one replaced, which CPython chains implicitly as {@code __context__}. */
    private PyObject context = PyNone.INSTANCE;

    /** Whether to hide {@code __context__} from the report, which {@code raise X from Y} decides. */
    private PyObject suppressContext = PyBool.FALSE;

    /** The {@code __traceback__} field, which {@code with_traceback} and the frame chain both write. */
    private PyObject traceback = PyNone.INSTANCE;

    /**
     * An exception raised as {@code exceptionType} with {@code args}.
     *
     * <p>Protected rather than package-private because the class system is not the only subclass this
     * needs: a Java failure crossing the boundary arrives as an exception whose attributes are read off
     * the {@code Throwable}, which is a subclass of this one and lives in the interop module.
     */
    protected PyBaseException(PyType exceptionType, PyObject[] args) {
        super(exceptionType);
        this.exceptionType = exceptionType;
        setArguments(args);
    }

    /** {@code BaseException.__init__} and {@code __new__}: the arguments of the call, taken over. */
    void setArguments(PyObject[] values) {
        args = values;
        arguments = PyTuple.of(values);
    }

    /** {@code e.args = value}: an iterable, converted to a tuple exactly as {@code tuple(value)}. */
    void setArguments(PyObject iterable) {
        java.util.List<PyObject> items = new java.util.ArrayList<>();
        PyIterator iterator = Slots.iterator(iterable);
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            items.add(item);
        }
        setArguments(items.toArray(new PyObject[0]));
    }

    /** {@code e.__traceback__ = value}: a traceback, or nothing at all. */
    void setTraceback(PyObject value) {
        if (value != PyNone.INSTANCE) {
            // No traceback type exists yet, so nothing this runtime can hand out would pass the check;
            // the refusal is CPython's own wording and a None is stored as CPython stores it.
            throw Slots.typeError("__traceback__ must be a traceback or None");
        }
        traceback = value;
    }

    /** The class the exception was raised as — {@code ValueError}, not always {@code BaseException}. */
    public PyType exceptionType() {
        return exceptionType;
    }

    public PyTuple args() {
        return arguments;
    }

    /**
     * {@code e == other}: {@code __eq__} when the class declares one, and identity as {@code object}
     * decides otherwise.
     *
     * <p>The same answer decides a {@code set} and a {@code dict} key, because {@link #hashCode} is the
     * {@link #hash()} below and {@link #equals} is this. CPython gives {@code BaseException} no
     * {@code tp_richcompare} of its own, so two exceptions built from the same class with the same
     * arguments are two values however they read: {@code Bare("x") == Bare("x")} is false until the class
     * says otherwise.
     */
    @Override
    public boolean eq(PyObject other) {
        PyObject answer = Specials.invoke(this, "__eq__", other);
        if (answer == PyNotImplemented.INSTANCE) {
            return this == other;
        }
        return Slots.truth(answer);
    }

    /**
     * {@code hash(e)}: {@code __hash__}, or the identity hash every exception inherits from {@code object}.
     *
     * <p>{@code BaseException} keeps {@code object}'s hash rather than none, so an exception is a set
     * member and a dictionary key like any other plain value, and two distinct values of the same class
     * with the same arguments hash apart. The one way an exception class loses that is the ordinary one:
     * a class that declares {@code __eq__} without a {@code __hash__} is given {@code __hash__ = None}
     * when it is built, and asking for its hash is the complaint about an unhashable type.
     */
    @Override
    public long hash() {
        PyObject declared = Specials.lookUp(exceptionType, "__hash__");
        if (declared == null) {
            return System.identityHashCode(this);
        }
        if (!(declared instanceof PyFunction function)) {
            throw Slots.typeError("unhashable type: '%s'", exceptionType.name());
        }
        BigInteger value = Numbers.asInteger(function.call(new PyObject[] {this}, null));
        if (value == null) {
            throw Slots.typeError("__hash__ method should return an integer");
        }
        return value.longValue();
    }

    /**
     * {@code str(e)}: {@code __str__} when the class declares one, and {@code BaseException_str}
     * otherwise.
     *
     * <p>The fallback is not the one a plain value has. {@code object}'s {@code tp_str} asks the repr
     * slot, so a class that writes only a {@code __repr__} is read with it; {@code BaseException} has a
     * {@code tp_str} of its own, so a written exception class that declares {@code __repr__} alone keeps
     * the argument rules below for {@code str(e)} while {@code repr(e)} changes around it.
     *
     * <p>CPython's rule, and it is observable — {@code str(ValueError(1, 2))} is {@code "(1, 2)"} while
     * {@code str(ValueError())} is {@code ""}. KeyError is the one exception with a rule of its own: its
     * single argument is the missing key, and CPython reports it with {@code repr} so that
     * {@code {}['']} reads as {@code KeyError: ''} rather than as a bare {@code KeyError}. The quotes are
     * CPython's, not a bug to smooth over.
     */
    @Override
    public String str() {
        PyObject declared = displayed("__str__");
        if (declared != null) {
            return display(declared, "__str__");
        }
        if (args.length == 1 && exceptionType.matches(ExceptionTypes.KEY_ERROR)) {
            return args[0].repr();
        }
        if (args.length == 0) {
            return "";
        }
        if (args.length == 1) {
            return args[0].str();
        }
        return PyTuple.of(args).repr();
    }

    /**
     * {@code repr(e)}: {@code __repr__} when the class declares one, and {@code BaseException_repr}
     * otherwise.
     *
     * <p>CPython's two halves of one format string: {@code %s(%R)} when there is exactly one argument, so
     * {@code ValueError(1)} reads without a tuple's trailing comma, and {@code %s%R} of the argument tuple
     * otherwise — which is how no argument at all still gets its parentheses, as {@code ValueError()}. The
     * class is named by {@code tp_name}, which for a class Python wrote is its plain {@code __name__}:
     * {@code Outer.E("x")} reprs as {@code E('x')}, without the enclosing class.
     */
    @Override
    public String repr() {
        PyObject declared = displayed("__repr__");
        if (declared != null) {
            return display(declared, "__repr__");
        }
        if (args.length == 1) {
            return exceptionType.name() + "(" + args[0].repr() + ")";
        }
        return exceptionType.name() + PyTuple.of(args).repr();
    }

    /** What the class's own method named {@code name} answers, or null when it declares none. */
    private PyObject displayed(String name) {
        PyFunction method = Specials.function(exceptionType, name);
        return method == null ? null : method.call(new PyObject[] {this}, null);
    }

    /** The check {@code PyObject_Str} and {@code PyObject_Repr} make of what a display method returned. */
    private static String display(PyObject answer, String name) {
        if (!(answer instanceof PyStr text)) {
            throw Slots.typeError("%s returned non-string (type %s)", name, answer.getType().name());
        }
        return text.getValue();
    }

    /**
     * {@code raise X from Y}: the cause, and with it the decision not to report a context.
     *
     * <p>CPython's {@code PyException_SetCause} sets both at once, so an explicit {@code from} always
     * suppresses the implicit chain — even when the two name the same exception, which is what
     * {@code except E as inner: raise F from inner} does.
     *
     * @param other the cause, or null for {@code from None}, which reports no cause at all
     */
    void setCause(PyBaseException other) {
        cause = other == null ? PyNone.INSTANCE : other;
        suppressContext = PyBool.TRUE;
    }

    /** The exception this one replaced, or {@code None} when nothing did. */
    PyObject context() {
        return context;
    }

    /** The implicit chain, built from the exception the enclosing handler was running for. */
    void setContext(PyBaseException other) {
        context = other == null ? PyNone.INSTANCE : other;
    }

    /**
     * The {@code __traceback__} field, which CPython reads as a field and not as an attribute — so a
     * class that files a property under the name is not asked what an escaped exception looks like.
     *
     * <p>A traceback is built from the frame chain above the raise, which this runtime does not keep
     * yet, and reporting none is honest where inventing a Java one would not be. The name of the
     * exception {@code with} hands to {@code __exit__} is read here, so that the day a chain is kept,
     * the third argument a context manager sees follows from it.
     */
    PyObject traceback() {
        return traceback;
    }

    /**
     * {@code e.name}: the exception's own fields first, then what CPython's generic read finds.
     *
     * <p>{@code args}, the chain and {@code __dict__} are getsets on {@code BaseException}, so they are
     * data descriptors and outrank anything the value holds under the same name — {@code class
     * Q(Exception): args = 5} leaves {@code Q().args} reading the empty tuple the value was made with.
     * The rest of the lookup is every other value's: a member the class declares and that can write
     * outranks the dictionary, the dictionary outranks a member that only reads, and {@code __module__}
     * is in that last group, which is why a class Python wrote answers it from its own dictionary while
     * {@code ValueError('x').__module__} is an {@code AttributeError} — a built-in type keeps its module
     * on the type, not in a dictionary an instance can walk.
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "args" -> arguments;
            case "__cause__" -> cause;
            case "__context__" -> context;
            case "__suppress_context__" -> suppressContext;
            case "__traceback__" -> traceback();
            case "__dict__" -> held();
            default -> owned(name);
        };
    }

    private PyObject owned(String name) {
        PyObject member = Specials.member(exceptionType, name);
        if (member != null && Descriptors.writes(member)) {
            return Descriptors.get(member, this, exceptionType);
        }
        PyObject kept = held().get(PyStr.interned(name));
        if (kept != null) {
            return kept;
        }
        if (member != null) {
            return Descriptors.get(member, this, exceptionType);
        }
        return name.equals("__class__")
                ? exceptionType
                : Specials.invoke(this, "__getattr__", PyStr.of(name));
    }

    /** {@code e.__dict__}, which a value of an exception class has had since CPython gave it one. */
    private PyDict held() {
        if (dictionary == null) {
            dictionary = PyDict.empty();
        }
        return dictionary;
    }

    /**
     * {@code e.name = value}, with the same order as the read and {@code __setattr__} above all of it.
     *
     * <p>The three chain fields are checked the way {@code PyException_SetCause} and its siblings check:
     * only an exception or {@code None} reaches {@code __cause__} and {@code __context__}, and the
     * complaint names what was tried. {@code args} takes any iterable and stores it as a tuple.
     */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        PyObject hook = Specials.lookUp(exceptionType, "__setattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name), value}, null);
        }
        switch (name) {
            case "args" -> {
                setArguments(value);
                return PyNone.INSTANCE;
            }
            case "__cause__" -> {
                cause = chain("exception cause", value);
                return PyNone.INSTANCE;
            }
            case "__context__" -> {
                context = chain("exception context", value);
                return PyNone.INSTANCE;
            }
            case "__suppress_context__" -> {
                suppressContext = value;
                return PyNone.INSTANCE;
            }
            case "__traceback__" -> {
                setTraceback(value);
                return PyNone.INSTANCE;
            }
            default -> {
            }
        }
        PyObject member = Specials.member(exceptionType, name);
        if (member != null && Descriptors.writes(member)) {
            Descriptors.set(member, this, value);
            return PyNone.INSTANCE;
        }
        held().put(PyStr.interned(name), value);
        return PyNone.INSTANCE;
    }

    /** The value a chain field accepts: an exception, or {@code None} to say there is none. */
    private static PyObject chain(String words, PyObject value) {
        if (value != PyNone.INSTANCE && !(value instanceof PyBaseException)) {
            throw Slots.typeError("%s must be None or derive from BaseException", words);
        }
        return value;
    }

    /**
     * {@code del e.name}: the arguments cannot be taken away, and everything else is the ordinary
     * deletion of a value's own attribute.
     */
    @Override
    public PyObject delAttr(String name) {
        PyObject hook = Specials.lookUp(exceptionType, "__delattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name)}, null);
        }
        if (name.equals("args")) {
            throw Slots.typeError("args may not be deleted");
        }
        PyStr key = PyStr.interned(name);
        if (held().get(key) == null) {
            throw Slots.attributeError(
                    "'%s' object has no attribute '%s'", exceptionType.name(), name);
        }
        held().remove(key);
        return PyNone.INSTANCE;
    }

    /** Whether this is the exception {@code clause} catches, by the class alone (M3 adds instances). */
    public boolean matches(PyType clause) {
        return exceptionType.matches(clause);
    }
}
