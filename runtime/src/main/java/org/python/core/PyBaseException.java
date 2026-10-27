package org.python.core;

import java.math.BigInteger;
import java.util.Map;

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
     * <p>It is filled by {@code StopIteration_init} rather than by the creator, which is what makes {@code
     * StopIteration.__new__(StopIteration, 1).value} {@code None} and a subclass whose {@code __init__}
     * skips {@code super().__init__} lose the value: the field belongs to the initialiser, exactly as the
     * offset does. The runtime reads a bare one as {@code None} anyway, which is {@code
     * _PyGen_FetchStopIterationValue} doing the same.
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
     * {@code SystemExit.code}: the one argument of the call, all of them when the call gave more than one,
     * and nothing when it gave none.
     *
     * <p>{@code SystemExit_init} is the only initialiser of the family that leaves the field alone rather
     * than filling it — {@code SystemExit().code} and {@code e.__init__()} on a value that had a code both
     * keep what was there, while {@code StopIteration_init} clears its field to {@code None} in the same
     * circumstances. The status the process ends with is read off {@code e.args} rather than from here, so
     * a code written onto the field by hand is a name the value reports and not a decision the interpreter
     * makes; the two only come apart that way, since {@code SystemExit_init} writes both.
     */
    private PyObject exitCode;

    /**
     * {@code ImportError}'s four offsets, in the order its {@code PyMemberDef} table files them.
     *
     * <p>The {@code name} of an {@code ImportError} is a different field from the {@code name} that {@code
     * NameError} and {@code AttributeError} keep: the three classes are siblings, CPython gives each its own
     * struct, and one value is only ever asked through the offset its own class derives from — which is why
     * {@code ImportError('m', name='mod').name} is the module and {@code NameError('m', name='var').name}
     * the variable. A class cannot hold both layouts: {@code class Both(NameError, ImportError)} is refused
     * for an instance lay-out conflict before its body has run, and {@code type} refuses the same pair from
     * either side.
     */
    private PyObject importMessage;

    private PyObject importedName;
    private PyObject importPath;
    private PyObject importedFrom;

    /**
     * An exception raised as {@code exceptionType} with {@code args}: {@code BaseException_new}, which
     * keeps what it was handed and fills no field of its own.
     *
     * <p>Protected rather than package-private because the class system is not the only subclass this
     * needs: a Java failure crossing the boundary arrives as an exception whose attributes are read off
     * the {@code Throwable}, which is a subclass of this one and lives in the interop module.
     *
     * <p>The five fields below this one belong to the initialiser, not here: {@code StopIteration(1)} gets
     * its {@code value} from {@code StopIteration_init}, and a value made by the creator alone — {@code
     * StopIteration.__new__(StopIteration, 1)} — has none. A call of a class runs the two in CPython's
     * order, which is {@link Constructors#exception} for a built-in one and {@link Classes#construct} for
     * a class a person wrote.
     */
    protected PyBaseException(PyType exceptionType, PyObject[] args) {
        super(exceptionType);
        this.exceptionType = exceptionType;
        setArguments(args);
    }

    /**
     * Whether the {@code name} this value keeps is {@code ImportError}'s, which is a different offset from
     * the one {@code NameError} and {@code AttributeError} keep even though all three are read by that name.
     *
     * <p>The three families cannot be combined into one class, so the class the value was made as answers
     * the question the descriptor's declaring class would otherwise have to answer.
     */
    private boolean takesImportFields() {
        return exceptionType.matches(ExceptionTypes.IMPORT_ERROR);
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
     * {@code PyMember_GetOne} over one of the family's {@code _Py_T_OBJECT} offsets.
     *
     * <p>A field that was never filled and a field that was deleted both read as {@code None} — the member
     * has no {@code Py_T_OBJECT_EX} in it, so the null pointer is turned into the singleton on the way
     * out — which is what makes {@code SystemExit().code} an answer rather than the complaint a missing
     * attribute is. The two are still not the same to the value, and {@link #memberPointer} is what tells
     * them apart.
     */
    PyObject memberField(String which) {
        return orNone(memberPointer(which));
    }

    /**
     * {@code self->X} as the pointer it is: null where nothing ever filled the field, {@code None} — a
     * value — where {@code None} was written or {@code e.name = None} assigned.
     *
     * <p>The two {@code __getstate__}s of the family test the pointer rather than the value it answers with,
     * which is what puts {@code 'name': None} in the state of {@code AttributeError('a', name=None)} and
     * leaves the state of the same value with the name never named empty. A deleted field is the same as an
     * unfilled one to them.
     */
    PyObject memberPointer(String which) {
        return switch (which) {
            case "value" -> stopIterationValue;
            case "name" -> takesImportFields() ? importedName : failureName;
            case "obj" -> failureObject;
            case "code" -> exitCode;
            case "msg" -> importMessage;
            case "path" -> importPath;
            case "name_from" -> importedFrom;
            default ->
                    throw Slots.runtimeError("the exception family files no member named '" + which + "'");
        };
    }

    /** {@code PyMember_SetOne} over the same offsets, with {@code null} standing for {@code del}. */
    void writeMemberField(String which, PyObject value) {
        switch (which) {
            case "value" -> stopIterationValue = value;
            case "name" -> {
                if (takesImportFields()) {
                    importedName = value;
                } else {
                    failureName = value;
                }
            }
            case "obj" -> failureObject = value;
            case "code" -> exitCode = value;
            case "msg" -> importMessage = value;
            case "path" -> importPath = value;
            case "name_from" -> importedFrom = value;
            default ->
                    throw Slots.runtimeError("the exception family files no member named '" + which + "'");
        }
    }

    /** {@code OR_NONE}, the macro that lets a field no one filled read as {@code None}. */
    private static PyObject orNone(PyObject field) {
        return field == null ? PyNone.INSTANCE : field;
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
        if (exceptionType.matches(ExceptionTypes.KEY_ERROR)) {
            return keyErrorText();
        }
        return exceptionType.matches(ExceptionTypes.IMPORT_ERROR)
                ? importErrorText()
                : described();
    }

    /**
     * {@code BaseException_str} itself, without the {@code __str__} question first.
     *
     * <p>Split out because {@link PyOSError} replaces this half and keeps the half above it: a class under
     * {@code OSError} that declares a {@code __str__} is asked for it, and one that does not gets the
     * error number and the file names rather than these rules. The rules are the argument count's alone —
     * which is what {@code BaseException.__str__(KeyError('k'))} reads as {@code 'k'} without quotes while
     * {@code str()} of the same value quotes it, {@link #keyErrorText} being a different function.
     */
    String described() {
        if (args.length == 0) {
            return "";
        }
        if (args.length == 1) {
            return args[0].str();
        }
        return PyTuple.of(args).repr();
    }

    /**
     * {@code KeyError_str}: the one argument as a {@code repr}, and {@code BaseException_str} for every
     * other count.
     *
     * <p>Nine classes of the family write a {@code tp_str} of their own — {@code BaseException} and {@code
     * BaseExceptionGroup}, {@code OSError}, {@code ImportError}, {@code SyntaxError}, {@code KeyError} and
     * the three Unicode errors — and this runtime has filed the four of those that exist here. The quotes
     * are CPython's deliberate trade: {@code {}['']} reports {@code KeyError: ''} rather than a bare
     * {@code KeyError}, and an explanatory sentence handed to a {@code KeyError} is put in quotes along
     * with it.
     */
    String keyErrorText() {
        return args.length == 1 ? args[0].repr() : described();
    }

    /**
     * {@code ImportError_str}: the message the class keeps in its first field, and {@code
     * BaseException_str} for everything the field does not hold.
     *
     * <p>The check is {@code PyUnicode_CheckExact} rather than a plain read, so a {@code msg} written by
     * hand as a number or a list is not converted but walked past: {@code str(ImportError('a', 'b'))} is
     * {@code "('a', 'b')}, because the two-argument call left the field empty, and {@code e.msg = ['x']}
     * afterwards is the same sentence about the arguments rather than the list. Deleting the field puts the
     * value back on that route, which is how {@code del e.msg} changes {@code str(e)} without changing
     * {@code e.args}.
     */
    String importErrorText() {
        return importMessage instanceof PyStr text ? text.getValue() : described();
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
        return represented();
    }

    /**
     * {@code BaseException_repr} itself, without the {@code __repr__} question first.
     *
     * <p>The half the {@code wrapper_descriptor} {@code BaseException} files calls directly, which is why
     * {@code BaseException.__repr__(e)} of an {@code OSError} says {@code OSError(...)} over the arguments
     * the value was made with and not whatever the narrower class has to say for itself.
     */
    String represented() {
        if (args.length == 1) {
            return exceptionType.name() + "(" + args[0].repr() + ")";
        }
        return exceptionType.name() + PyTuple.of(args).repr();
    }

    /**
     * {@code BaseException.__reduce__}: the class, the arguments, and the dictionary only where the value
     * already has one.
     *
     * <p>{@code self->args && self->dict} is a test of two pointers rather than of two contents, so what
     * decides the two-tuple from the three-tuple is whether anything has been written to the value — and
     * therefore whether {@code e.__dict__} has ever been asked for. Reading it is not a look: {@code
     * PyObject_GenericGetDict} fills the field on the way out, so the next {@code __reduce__} of a value
     * that was never touched reports the empty dictionary that read left behind.
     */
    PyObject reduced() {
        PyDict kept = dictionary;
        PyObject arguments = reducedArguments();
        return kept == null
                ? PyTuple.of(exceptionType, arguments)
                : PyTuple.of(exceptionType, arguments, kept);
    }

    /** {@code self->args} as the reduction reports it, which is the half {@code OSError} rewrites. */
    PyObject reducedArguments() {
        return arguments;
    }

    /**
     * {@code AttributeError_getstate}: the name the value keeps and the arguments it was called with, hung
     * on a copy of whatever dictionary it has.
     *
     * <p>The test CPython makes is of two pointers, and {@code self->args} is never one of them: {@code
     * BaseException_new} gives every value a tuple, however few arguments the call gave, so the state of an
     * {@code AttributeError} is always a dictionary and always carries {@code args}. Only the name is
     * offered conditionally, which is what leaves {@code AttributeError('a', name=None)} a state with
     * {@code 'name': None} in it and the same value with the name never named a state without the key at
     * all. The {@code obj} field is deliberately not in the state: CPython says there is no telling whether
     * the object a failed read looked the name up on can be pickled.
     */
    PyObject attributeState() {
        PyDict state = dictionary == null ? PyDict.empty() : dictionary.copy();
        if (failureName != null) {
            state.put(PyStr.interned("name"), failureName);
        }
        state.put(PyStr.interned("args"), arguments);
        return state;
    }

    /** {@code AttributeError_reduce}: three places every time, because the state never comes back empty. */
    PyObject attributeReduced() {
        return PyTuple.of(exceptionType, arguments, attributeState());
    }

    /**
     * {@code ImportError_getstate}: the three fields a failed import names, and never the message.
     *
     * <p>{@code msg} is what {@code str(e)} reads and is not in the state — the arguments carry it, since
     * the field is only ever filled from a one-argument call. Where none of the three is set the answer is
     * the value's own dictionary as it stands, {@code Py_NewRef} rather than a copy, so {@code
     * e.__reduce__()[2] is e.__dict__} holds; where one of them is, the state is a copy and the fields are
     * added to it in the order the class files its four.
     */
    PyObject importState() {
        if (importedName == null && importPath == null && importedFrom == null) {
            return dictionary == null ? PyNone.INSTANCE : dictionary;
        }
        PyDict state = dictionary == null ? PyDict.empty() : dictionary.copy();
        if (importedName != null) {
            state.put(PyStr.interned("name"), importedName);
        }
        if (importPath != null) {
            state.put(PyStr.interned("path"), importPath);
        }
        if (importedFrom != null) {
            state.put(PyStr.interned("name_from"), importedFrom);
        }
        return state;
    }

    /**
     * {@code ImportError_reduce}: the pair when there is no state to restore, the triple when there is.
     *
     * <p>A third place is added by {@code state != Py_None} rather than by the state being empty, which is
     * how this class differs from {@code BaseException}'s rule of two pointers: an {@code ImportError}
     * whose only field was deleted reduces to a pair even after its dictionary has been read.
     */
    PyObject importReduced() {
        PyObject state = importState();
        return state == PyNone.INSTANCE
                ? PyTuple.of(exceptionType, arguments)
                : PyTuple.of(exceptionType, arguments, state);
    }

    /**
     * {@code BaseException.__setstate__}: the entries of the state, each written as an attribute.
     *
     * <p>{@code None} says there is nothing to restore and does no work at all; anything that is neither
     * {@code None} nor a dictionary is refused before the first key is looked at. After that the write is
     * {@code PyObject_SetAttr}, so a name that is not text is complained about in the attribute setter's
     * own words, and a name the class files a descriptor for — {@code args}, {@code __class__}, one of an
     * {@code OSError}'s five — goes through that descriptor rather than landing beside it. The entries are
     * taken in the order the dictionary holds them, which is the order they were written.
     */
    void restored(PyObject state) {
        if (state == PyNone.INSTANCE) {
            return;
        }
        if (!(state instanceof PyDict given)) {
            throw Slots.typeError("state is not a dictionary");
        }
        for (Map.Entry<PyObject, PyObject> entry : given.getItems().entrySet()) {
            Slots.setAttr(this, namedAttribute(entry.getKey()), entry.getValue());
        }
    }

    /** The key of a state, in the words {@code PyObject_SetAttr} chooses for one that is not text. */
    private static String namedAttribute(PyObject key) {
        if (key instanceof PyStr text) {
            return text.getValue();
        }
        throw Slots.typeError("attribute name must be string, not '%s'", key.getType().name());
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

    /** The chain flag {@code BaseException} files as a {@code T_BOOL} member. */
    PyObject suppressContext() {
        return suppressContext;
    }

    /**
     * {@code e.name}: what CPython's generic read finds, and nothing before it.
     *
     * <p>{@link #owned} is CPython's {@code _PyType_Lookup} order: the first class in the method resolution
     * order that names the attribute decides, so {@code class Q(Exception): args = 5} answers {@code 5}
     * where the base's getset would have answered the tuple, and a property filed under {@code args} is
     * asked before the field is read at all. The six names {@code BaseException} itself files — {@code
     * args}, the chain, {@code __suppress_context__} and {@code __dict__} — are descriptors like any other,
     * and so are the nine the five classes under it file for their own offsets: {@code StopIteration.value},
     * {@code NameError.name}, {@code SystemExit.code}, {@code AttributeError}'s two and {@code
     * ImportError}'s four. Each of those is a {@code _Py_T_OBJECT} offset whose read turns a field nobody
     * filled into {@code None}, which is what makes {@code SystemExit().code} an answer where {@code
     * NameError('x').code} — a name the class files nothing under — is the complaint. A class that files a
     * value over one of them is asked instead, because the member is found by the lookup and not by the
     * struct. The four-step order {@link #owned} answers by is every other value's: a member that can write
     * outranks the dictionary, the dictionary outranks a member that only reads, and {@code __module__} is in
     * that last group — which is why a class Python wrote answers it from its own dictionary while {@code
     * ValueError('x').__module__} is an {@code AttributeError}, a built-in type keeping its module on the
     * type rather than in a dictionary an instance can walk.
     */
    @Override
    public PyObject getAttr(String name) {
        return owned(name);
    }

    private PyObject owned(String name) {
        PyObject member = Specials.member(exceptionType, name);
        if (member != null && Descriptors.writes(member)) {
            return Descriptors.get(member, this, exceptionType);
        }
        // `_PyObject_GetDictPtr`, not `PyObject_GenericGetDict`: a read of an attribute never makes the
        // dictionary, which is what lets `__reduce__` still tell a value nothing wrote to from one that has
        // a `__dict__` someone only looked at.
        PyObject kept = dictionary == null ? null : dictionary.get(PyStr.interned(name));
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
    PyDict held() {
        if (dictionary == null) {
            dictionary = PyDict.empty();
        }
        return dictionary;
    }

    /** The same dictionary, offered to {@link GenericAttributes} for every name it writes plainly. */
    @Override
    PyDict attributeStore() {
        return held();
    }

    /**
     * {@code e.__dict__ = {...}}, which {@code BaseException}'s getset answers with and {@code del} reaches
     * with nothing handed over.
     *
     * <p>An exception's dictionary cannot be deleted at all — that refusal is {@link #delAttr}'s — so the
     * two sentences here are the type check and nothing else: {@code TypeError: __dict__ must be set to a
     * dictionary, not a 'int'}, naming the value rather than the class the way {@code subtype_setdict}
     * does for every other value.
     */
    void replaceDict(PyObject value) {
        if (value == null) {
            throw Slots.typeError("cannot delete __dict__");
        }
        if (!(value instanceof PyDict replacement)) {
            throw Slots.typeError(
                    "__dict__ must be set to a dictionary, not a '%s'", value.getType().filedName());
        }
        dictionary = replacement;
    }

    /**
     * The values of the fields a class under {@code Exception} declared in {@code __slots__}, kept apart
     * from the exception's own dictionary the way CPython keeps an offset apart from {@code tp_dictoffset}:
     * {@code vars(e)} stays empty when such a field is set.
     */
    private PyDict slotValues;

    @Override
    PyObject slotValue(String name) {
        PyObject held = slotValues == null ? null : slotValues.get(PyStr.interned(name));
        if (held == null) {
            throw Slots.attributeError(
                    "'%s' object has no attribute '%s'", exceptionType.filedName(), name);
        }
        return held;
    }

    @Override
    void writeSlot(String name, PyObject value) {
        PyStr key = PyStr.interned(name);
        if (value == null) {
            if (slotValues == null || slotValues.get(key) == null) {
                throw Slots.attributeError("%s", name);
            }
            slotValues.remove(key);
            return;
        }
        if (slotValues == null) {
            slotValues = PyDict.empty();
        }
        slotValues.put(key, value);
    }

    /**
     * {@code e.name = value}, with the same order as the read and {@code __setattr__} above all of it.
     *
     * <p>The names {@code BaseException} files as descriptors, and the nine the five classes under it file
     * for their own offsets, are written by those descriptors, which is where CPython's checks live — see
     * {@link #writeArguments} and its siblings for the first group and {@link PyMemberDescriptor} for the
     * second — and the walk that finds them is {@link GenericAttributes}, the same {@code _PyType_Lookup}
     * the read walks, so {@code class Q(Exception): args = 5} leaves the assignment to the instance
     * dictionary rather than to the field. A {@code _Py_T_OBJECT} offset takes any value and writes the
     * pointer, which is why {@code e.name = 5} keeps the {@code 5} rather than refusing it the way {@code
     * __suppress_context__} does.
     */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        PyObject hook = Specials.lookUp(exceptionType, "__setattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name), value}, null);
        }
        GenericAttributes.write(this, name, value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code BaseException_set_args}: any iterable, stored as the tuple {@code tuple(value)} makes.
     *
     * <p>{@code value} null is {@code del e.args}, which reaches the same pointer with NULL handed over and
     * is refused there: {@code args} is a C array of arguments, not a dictionary entry, and CPython's
     * setter is the only place that says so.
     */
    void writeArguments(PyObject value) {
        if (value == null) {
            throw Slots.typeError("args may not be deleted");
        }
        setArguments(value);
    }

    /** {@code BaseException_set_traceback}: a traceback, {@code None}, and nothing deleted. */
    void writeTraceback(PyObject value) {
        if (value == null) {
            throw Slots.typeError("__traceback__ may not be deleted");
        }
        setTraceback(value);
    }

    /**
     * {@code BaseException_set_cause}: an exception or {@code None}, written through {@link #setCause}.
     *
     * <p>The assignment is the same function {@code raise X from Y} calls, so {@code e.__cause__ = None}
     * hides the context exactly as {@code raise X from None} does — the flag travels with the field.
     */
    void writeCause(PyObject value) {
        if (value == null) {
            throw Slots.typeError("__cause__ may not be deleted");
        }
        setCause(chain("exception cause", value));
    }

    /** {@code BaseException_set_context}: an exception or {@code None}, leaving the flag alone. */
    void writeContext(PyObject value) {
        if (value == null) {
            throw Slots.typeError("__context__ may not be deleted");
        }
        setContext(chain("exception context", value));
    }

    /**
     * {@code BaseException}'s {@code __suppress_context__} member, which is a {@code T_BOOL} offset.
     *
     * <p>Two refusals come out of {@code PyMember_SetOne} rather than out of this field: a value that is
     * not a bool is refused although {@code bool(value)} would have answered, because the member is the
     * one kind of offset that checks its type instead of converting, and {@code del} is the sentence that
     * a numeric or char offset cannot be emptied at all.
     */
    void writeSuppressContext(PyObject value) {
        if (value == null) {
            throw Slots.typeError("can't delete numeric/char attribute");
        }
        if (!(value instanceof PyBool)) {
            throw Slots.typeError("attribute value type must be bool");
        }
        suppressContext = value;
    }

    /** The value a chain field accepts: an exception, or {@code None} to say there is none. */
    private static PyObject chain(String words, PyObject value) {
        if (value != PyNone.INSTANCE && !(value instanceof PyBaseException)) {
            throw Slots.typeError("%s must be None or derive from BaseException", words);
        }
        return value;
    }

    /**
     * {@code del e.name}: the name is taken away in the order it would have been read.
     *
     * <p>The six names {@code BaseException} files refuse in the writing half of their own descriptors,
     * which is where CPython's {@code ex_del} and {@code PyMember_SetOne} say so — {@code args} and the
     * chain by name, {@code __dict__} in the words {@code PyObject_GenericSetDict} chooses, {@code
     * __suppress_context__} as the numeric attribute it is, and {@code __class__} in the sentence {@code
     * object}'s own getset keeps for itself. All four are reached through {@link GenericAttributes}, which
     * is also where a name the class files as something else — a property a subclass wrote over the field —
     * is asked rather than refused, so {@code Guarded} gets its {@code AttributeError: property 'errno' of
     * 'Guarded' object has no deleter} instead of a complaint about the instance dictionary.
     *
     * <p>{@code StopIteration.value} and its eight siblings empty the offset by writing null to the
     * pointer: {@code del si.value} is silent, reading it afterwards gives {@code None}, and deleting it a
     * second time is still silent, because the member has no {@code Py_T_OBJECT_EX} in it and so has no
     * second sentence for "already gone".
     */
    @Override
    public PyObject delAttr(String name) {
        PyObject hook = Specials.lookUp(exceptionType, "__delattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name)}, null);
        }
        GenericAttributes.delete(this, name);
        return PyNone.INSTANCE;
    }

    /** Whether this is the exception {@code clause} catches, by the class alone (M3 adds instances). */
    public boolean matches(PyType clause) {
        return exceptionType.matches(clause);
    }
}
