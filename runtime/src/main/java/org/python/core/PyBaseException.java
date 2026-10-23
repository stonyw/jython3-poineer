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
 * The traceback is the exception's own to carry. CPython 3.14 builds it as an error travels out of each
 * frame — {@link PyTraceback#logFrame} is that step — and a raise of an exception that already has one
 * keeps it, which is why {@code raise e} from a handler shows the frame it was caught in twice rather
 * than once. Until the frame chain existed this field could only ever be {@code None}.
 */
public class PyBaseException extends PyObject {

    private PyType exceptionType;

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
     * {@code StopIteration.value}, the value a generator's {@code return} hands out.
     *
     * <p>It lives on the exception rather than in its arguments because CPython declares it as a member of
     * {@code PyStopIterationObject} beside {@code args}, and keeps the two independent: {@code StopIteration
     * (1).value} is {@code 1}, and re-assigning {@code args} leaves it {@code 1} all the same. Only an
     * exception of that class or a subclass of it has the field, which is why it is null — read as {@code
     * None} — on every other value, and why deleting it is allowed and reads back as {@code None} rather
     * than complaining.
     *
     * <p>Deviation, and the only way to reach it: CPython fills this in {@code StopIteration_init}, so
     * {@code StopIteration.__new__(StopIteration, 1).value} is {@code None} there and {@code 1} here, and a
     * subclass whose {@code __init__} skips {@code super().__init__} loses the value in CPython but keeps it
     * here. The runtime builds an exception's arguments and its value in the one step.
     */
    private PyObject stopIterationValue;

    /**
     * {@code NameError.name} and {@code AttributeError.name}: the name the read that failed was looking for.
     *
     * <p>Nothing a script raises by hand carries it — {@code NameError('countr', 'counter').name} is
     * {@code None}, however many arguments the call gave, because CPython fills this in on the two routes
     * where a name was actually missed: {@code _PyEval_FormatExcCheckArg} for a name read and {@code
     * _PyObject_SetAttributeErrorContext} for a member read. {@link #asMissingName} and {@link
     * #asFailedRead} are those two routes, and the report is the only thing that reads what they leave
     * behind — it is where the question "did you mean" is asked of it.
     *
     * <p>{@code UnboundLocalError} has the member because it derives from {@code NameError}, and never uses
     * it: the sentence about a local the frame never reached names no variable to CPython, which is why
     * that error is the one that cannot be asked about.
     */
    private PyObject failureName;

    /**
     * {@code AttributeError.obj}: the object the failed read looked the name up on.
     *
     * <p>{@code AttributeError}'s alone — {@code NameError} has no such member, and reading {@code obj} off
     * one is an {@code AttributeError} like reading it off any other class that does not declare it. It is
     * the object as it was given to the read, so {@code e.obj is thing} holds, and it is the object whose
     * members the report asks about when it wonders what was meant.
     */
    private PyObject failureObject;

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
        if (carriesValue()) {
            stopIterationValue = args.length > 0 ? args[0] : PyNone.INSTANCE;
        }
    }

    /** Whether this class has the {@code value} member: {@code StopIteration} and what derives from it. */
    private boolean carriesValue() {
        return exceptionType.matches(ExceptionTypes.STOP_ITERATION);
    }

    /**
     * Whether this class has the {@code name} member: the two classes that report a missed name and
     * everything under them, which is where {@code UnboundLocalError} joins it through {@code NameError}.
     */
    private boolean carriesName() {
        return exceptionType.matches(ExceptionTypes.NAME_ERROR)
                || exceptionType.matches(ExceptionTypes.ATTRIBUTE_ERROR);
    }

    /** Whether this class has the {@code obj} member: {@code AttributeError} and what derives from it. */
    private boolean carriesObject() {
        return exceptionType.matches(ExceptionTypes.ATTRIBUTE_ERROR);
    }

    /**
     * {@code _PyEval_FormatExcCheckArg} for a name that was not there: the read names what it missed, so
     * that the report can ask about it.
     */
    void asMissingName(String name) {
        failureName = PyStr.of(name);
    }

    /**
     * {@code _PyObject_SetAttributeErrorContext}: the two members a failed member read leaves behind, hung
     * on the error after its message has been chosen.
     */
    void asFailedRead(PyObject target, String name) {
        failureName = PyStr.of(name);
        failureObject = target;
    }

    /** The name this error was made about, or null where the read named none. */
    PyObject failureName() {
        return failureName;
    }

    /** The object a failed member read was reading off, or null where there was none. */
    PyObject failureObject() {
        return failureObject;
    }

    /**
     * The value of a {@code StopIteration}, read the way the runtime reads it rather than the way Python
     * asks: {@code _PyGen_FetchStopIterationValue} takes the member directly, and a bare one is worth
     * {@code None}.
     */
    PyObject value() {
        return stopIterationValue == null ? PyNone.INSTANCE : stopIterationValue;
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
        if (value != PyNone.INSTANCE && !(value instanceof PyTraceback)) {
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
    PyObject displayed(String name) {
        PyFunction method = Specials.function(exceptionType, name);
        return method == null ? null : method.call(new PyObject[] {this}, null);
    }

    /** The check {@code PyObject_Str} and {@code PyObject_Repr} make of what a display method returned. */
    static String display(PyObject answer, String name) {
        if (!(answer instanceof PyStr text)) {
            throw Slots.typeError("%s returned non-string (type %s)", name, answer.getType().name());
        }
        return text.getValue();
    }

    /**
     * {@code PyException_SetCause}: the cause, and with it the decision not to report a context.
     *
     * <p>CPython sets both in the one function, which {@code __cause__}'s setter and {@code
     * exceptiongroup_subset} both call. So an explicit {@code from} always suppresses the implicit chain —
     * even when the two name the same exception, which is what {@code except E as inner: raise F from
     * inner} does, and even when the cause named is {@code None}, which is what {@code raise F from None}
     * and a split of a group with no cause both do.
     *
     * @param other the cause, or null for {@code from None}, which reports no cause at all
     */
    void setCause(PyObject other) {
        cause = other == null ? PyNone.INSTANCE : other;
        suppressContext = PyBool.TRUE;
    }

    /** The cause as the report reads it: {@code BaseException.__cause__}, field and all. */
    PyObject cause() {
        return cause;
    }

    /** The exception this one replaced, or {@code None} when nothing did. */
    PyObject context() {
        return context;
    }

    /**
     * The implicit chain, built from the exception the enclosing handler was running for.
     *
     * <p>{@code PyException_SetContext} writes the field and leaves {@link #suppressContext} to the cause
     * that decides it, which is why a chain CPython builds for itself still reports a context.
     */
    void setContext(PyObject other) {
        context = other == null ? PyNone.INSTANCE : other;
    }

    /**
     * {@code self->notes}: the {@code __notes__} entry, read as the field CPython keeps notes in.
     *
     * <p>An exception with no notes answers with {@code PyNotImplemented} instead of raising, which is what
     * lets a comparison of two of them say "neither has any" without a third case. This runtime files them
     * in the dictionary because {@code add_note}, the only other way CPython writes the field, is not
     * implemented here — so the two are the same list a person can read and replace.
     */
    PyObject notes() {
        PyObject kept = dictionary == null ? null : dictionary.get(PyStr.interned("__notes__"));
        return kept == null ? PyNotImplemented.INSTANCE : kept;
    }

    /**
     * The {@code __traceback__} field, which CPython reads as a field and not as an attribute — so a
     * class that files a property under the name is not asked what an escaped exception looks like.
     *
     * <p>The chain is built as the error travels out of each frame, by {@link PyTraceback#logFrame}, and
     * an exception that reaches a handler with one keeps it: what is read here is exactly what was hung
     * on the front. The value {@code with} hands to {@code __exit__} is read here for the same reason, so
     * a context manager sees the traceback the raiser had.
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
            case "value" ->
                    carriesValue()
                            ? (stopIterationValue == null ? PyNone.INSTANCE : stopIterationValue)
                            : owned(name);
            case "name" ->
                    carriesName()
                            ? (failureName == null ? PyNone.INSTANCE : failureName)
                            : owned(name);
            case "obj" ->
                    carriesObject()
                            ? (failureObject == null ? PyNone.INSTANCE : failureObject)
                            : owned(name);
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

    /**
     * {@code e.__class__ = E2}: the class the value reports, in both the places it is kept.
     *
     * <p>An exception holds its class twice — the pointer every value has, and the one whose name the
     * display and whose {@code __init__} the constructor read — so a swap written through {@code object}'s
     * getset has to move both. Only ever called by {@link ClassAttribute}, and only for a pair of classes
     * that rest on the same built-in, which is what keeps a {@code ValueError} from becoming an {@code int}
     * with exception storage.
     */
    @Override
    void reassignedTo(PyType kind) {
        super.reassignedTo(kind);
        exceptionType = kind;
    }

    /**
     * Whether this value's own class takes the write, in the same two ways a plain class does.
     *
     * <p>The member question is put to {@link Specials#declared} rather than to the namespace alone because
     * an exception's class may rest on a built-in that declares something under the name; {@code __class__}
     * is not one of those names, so a value of {@code ValueError} still lets {@code object} answer.
     */
    @Override
    boolean filedByItsClass(String name) {
        return Specials.member(exceptionType, name) != null
                || Specials.lookUp(exceptionType, "__setattr__") != null
                || Specials.lookUp(exceptionType, "__delattr__") != null;
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
     * complaint names what was tried. Writing the cause goes through {@link #setCause}, so {@code e.__cause__
     * = None} hides the context exactly as {@code raise X from None} does — the assignment is the same
     * function CPython calls, not a field write of its own. {@code args} takes any iterable and stores it
     * as a tuple.
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
            case "value" -> {
                if (carriesValue()) {
                    stopIterationValue = value;
                    return PyNone.INSTANCE;
                }
            }
            case "name" -> {
                if (carriesName()) {
                    failureName = value;
                    return PyNone.INSTANCE;
                }
            }
            case "obj" -> {
                if (carriesObject()) {
                    failureObject = value;
                    return PyNone.INSTANCE;
                }
            }
            case "__cause__" -> {
                setCause(chain("exception cause", value));
                return PyNone.INSTANCE;
            }
            case "__context__" -> {
                setContext(chain("exception context", value));
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
     *
     * <p>{@code StopIteration.value} is the exception, because a member of {@code _Py_T_OBJECT} type
     * deletes by writing null to the field: {@code del si.value} succeeds, reading it afterwards gives
     * {@code None}, and deleting it a second time is still silent.
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
        if (name.equals("value") && carriesValue()) {
            stopIterationValue = null;
            return PyNone.INSTANCE;
        }
        if (name.equals("name") && carriesName()) {
            failureName = null;
            return PyNone.INSTANCE;
        }
        if (name.equals("obj") && carriesObject()) {
            failureObject = null;
            return PyNone.INSTANCE;
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
