package org.python.core;

/**
 * {@code annotationlib.ForwardRef}: a name whose value was not known when the annotation was read.
 *
 * <p>It is what the {@code FORWARDREF} format hands back in place of a name it could not resolve, and it
 * carries the name as text so that {@code evaluate} can try again later. The object itself is what the
 * reference exposes: the argument, the module and owner it was made with, the two flags, and an
 * {@code evaluate} that answers for one of the formats.
 *
 * <p>Evaluation is the slice of {@code eval} that {@link AnnotationEval} reads. A name this runtime cannot
 * resolve is a {@code NameError} under {@code VALUE} and this same object under {@code FORWARDREF}, which is
 * the difference the two formats exist to make.
 */
final class PyForwardRef extends PyObject {

    private static final String[] PARAMETERS = {"arg", "module", "owner", "is_argument", "is_class"};

    private static final String[] EVALUATE_NAMES = {"globals", "locals", "type_params", "owner", "format"};

    /** The reference's {@code __slots__}, in the order it declares them. */
    private static final PyObject SLOTS = PyTuple.of(new PyObject[] {
        PyStr.interned("__forward_is_argument__"),
        PyStr.interned("__forward_is_class__"),
        PyStr.interned("__forward_module__"),
        PyStr.interned("__weakref__"),
        PyStr.interned("__arg__"),
        PyStr.interned("__globals__"),
        PyStr.interned("__extra_names__"),
        PyStr.interned("__code__"),
        PyStr.interned("__ast_node__"),
        PyStr.interned("__cell__"),
        PyStr.interned("__owner__"),
        PyStr.interned("__stringifier_dict__"),
        PyStr.interned("__resolved_str_cache__")
    });

    private final PyStr argument;

    private final PyObject module;

    private final PyObject owner;

    private final boolean isArgument;

    private final boolean isClass;

    private PyForwardRef(
            PyStr argument, PyObject module, PyObject owner, boolean isArgument, boolean isClass) {
        super(PyType.FORWARD_REF);
        this.argument = argument;
        this.module = module;
        this.owner = owner;
        this.isArgument = isArgument;
        this.isClass = isClass;
    }

    /** {@code ForwardRef(arg, *, module=None, owner=None, is_argument=True, is_class=False)}. */
    static PyObject called(PyObject[] args, String[] keywordNames) {
        PyObject[] values =
                Arguments.unpackKeywords("ForwardRef", args, keywordNames, PARAMETERS, 1, 1);
        if (!(values[0] instanceof PyStr text)) {
            throw Slots.typeError(
                    "Forward reference must be a string -- got %s", values[0].repr());
        }
        boolean isArgument = values[3] == null || Slots.truth(values[3]);
        boolean isClass = values[4] != null && Slots.truth(values[4]);
        return new PyForwardRef(
                text,
                values[1] == null ? PyNone.INSTANCE : values[1],
                values[2] == null ? PyNone.INSTANCE : values[2],
                isArgument,
                isClass);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__forward_arg__", "__arg__", "__resolved_str_cache__" -> argument;
            case "__forward_module__" -> module;
            case "__forward_is_argument__" -> PyBool.of(isArgument);
            case "__forward_is_class__" -> PyBool.of(isClass);
            case "__owner__" -> owner;
            case "__globals__", "__weakref__", "__cell__", "__extra_names__", "__ast_node__",
                    "__stringifier_dict__", "__code__" -> PyNone.INSTANCE;
            case "__slots__" -> SLOTS;
            case "evaluate" -> new PyNativeFunction("evaluate", PyForwardRef::evaluate).withReceiver(this);
            default -> Slots.DECLINED;
        };
    }

    /**
     * {@code evaluate(*, globals=None, locals=None, type_params=None, owner=None, format=Format.VALUE)}.
     *
     * <p>{@code STRING} answers the argument itself. {@code VALUE} answers the value the name stands for and
     * complains when there is none; {@code FORWARDREF} answers this object instead, which is what makes the
     * two formats differ for an unresolved name. Every other format is a
     * {@code NotImplementedError} carrying the format, as the reference's lookup does.
     *
     * <p>The namespaces are the caller's, then the module this reference names, then the owner's — the same
     * order the reference tries. A {@code type_params} argument is accepted and dropped, because annotation
     * scopes are not carried by {@link AnnotationEval}.
     */
    private static PyObject evaluate(PyObject[] args, String[] keywordNames) {
        PyForwardRef self = (PyForwardRef) args[0];
        PyObject[] values = Arguments.unpackKeywords(
                "evaluate",
                java.util.Arrays.copyOfRange(args, 1, args.length),
                keywordNames,
                EVALUATE_NAMES,
                0,
                0);
        PyObject format = values[4] == null ? PyFormat.VALUE : values[4];
        if (isFormat(format, PyFormat.STRING)) {
            return self.argument;
        }
        if (!isFormat(format, PyFormat.VALUE) && !isFormat(format, PyFormat.FORWARDREF)) {
            throw PyRaise.raise(ExceptionTypes.NOT_IMPLEMENTED_ERROR, format);
        }
        PyObject globals = values[0] == PyNone.INSTANCE ? null : values[0];
        PyObject locals = values[1] == PyNone.INSTANCE ? null : values[1];
        PyObject owner = values[3] == null ? self.owner : values[3];
        if (globals == null && self.module instanceof PyStr moduleName) {
            PyObject home = PyRuntime.existingModule(moduleName.getValue());
            if (home instanceof PyModule found) {
                globals = found.dictionary();
            }
        }
        if (globals == null || locals == null) {
            PyObject[] namespaces = AnnotationLib.namespacesOf(owner);
            if (globals == null) {
                globals = namespaces[0];
            }
            if (locals == null) {
                locals = namespaces[1];
            }
        }
        try {
            return AnnotationEval.evaluate(self.argument.getValue(), globals, locals);
        } catch (PyRaise refusal) {
            if (isFormat(format, PyFormat.FORWARDREF)
                    && refusal.value().exceptionType().matches(ExceptionTypes.NAME_ERROR)) {
                return self;
            }
            throw refusal;
        }
    }

    private static boolean isFormat(PyObject value, PyFormat member) {
        return Slots.compareTrue(CompareOp.EQ, value, member);
    }

    @Override
    public PyObject richCompare(CompareOp comparison, PyObject other) {
        if (!(other instanceof PyForwardRef reference)) {
            return PyNotImplemented.INSTANCE;
        }
        return switch (comparison) {
            case EQ -> PyBool.of(sameAs(reference));
            case NE -> PyBool.of(!sameAs(reference));
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /** The reference compares the argument, module, globals identity, the flag, cell, owner and extras. */
    private boolean sameAs(PyForwardRef other) {
        return argument.getValue().equals(other.argument.getValue())
                && Slots.compareTrue(CompareOp.EQ, module, other.module)
                && isClass == other.isClass
                && Slots.compareTrue(CompareOp.EQ, owner, other.owner);
    }

    /** The tuple hash the reference's {@code __hash__} takes over the same pieces. */
    @Override
    public long hash() {
        return PyTuple.of(new PyObject[] {
            argument, module, PyInt.of(0), PyBool.of(isClass), PyNone.INSTANCE, owner, PyNone.INSTANCE
        }).hash();
    }

    @Override
    public String repr() {
        StringBuilder text = new StringBuilder("ForwardRef(").append(argument.repr());
        if (module != PyNone.INSTANCE) {
            text.append(", module=").append(module.repr());
        }
        if (isClass) {
            text.append(", is_class=True");
        }
        if (owner != PyNone.INSTANCE) {
            text.append(", owner=").append(owner.repr());
        }
        return text.append(')').toString();
    }

    @Override
    public String str() {
        return repr();
    }
}
