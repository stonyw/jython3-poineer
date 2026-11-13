package org.python.core;

import java.util.Arrays;

/**
 * {@code _typing}: the type-variable objects {@code typing.py} is written on top of.
 *
 * <p>The reference module is C. It owns {@code Generic}, the three type-parameter kinds, the two views a
 * {@code ParamSpec} hands out for {@code *args} and {@code **kwargs}, {@code TypeAliasType}, the
 * {@code NoDefault} sentinel and {@code _idfunc}; every other name in {@code typing} is Python. This is
 * the same split, with the same delegation: {@code Generic}'s subscript and its
 * {@code __init_subclass__} are C hooks in the reference that call {@code typing._generic_class_getitem}
 * and {@code typing._generic_init_subclass}, and they call the same two functions here.
 *
 * <p>Keeping {@code Generic} a static type rather than a class {@code typing.py} builds is what makes
 * {@code Generic} and {@code Protocol} comparable by identity inside those two hooks, which is how the
 * reference refuses {@code Generic[int]} and {@code Generic[]}.
 */
final class TypingModule {

    private TypingModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_typing");
        PyRuntime.markBuiltIn(module, "_typing");
        module.set("TypeAliasType", PyType.TYPE_ALIAS_TYPE);
        module.set("TypeVar", PyType.TYPE_VAR);
        module.set("ParamSpec", PyType.PARAM_SPEC);
        module.set("ParamSpecArgs", PyType.PARAM_SPEC_ARGS);
        module.set("ParamSpecKwargs", PyType.PARAM_SPEC_KWARGS);
        module.set("TypeVarTuple", PyType.TYPE_VAR_TUPLE);
        module.set("Generic", PyType.TYPING_GENERIC);
        module.set("Union", PyType.UNION_TYPE);
        module.set("NoDefault", NoDefault.INSTANCE);
        module.set("_idfunc", new PyNativeFunction("_idfunc", (args, kwNames) -> {
            Arguments.exactlyOne("_idfunc", args, kwNames);
            return args[0];
        }));
        return module;
    }

    // --- the tables ---------------------------------------------------------------------------

    private static final Members TYPE_VAR_MEMBERS = Members.of(PyType.TYPE_VAR)
            .addField("__name__", null, self -> ((TypeVarValue) self).name)
            .addField("__module__", null, self -> ((TypeVarValue) self).module)
            .addField("__covariant__", null, self -> PyBool.of(((TypeVarValue) self).covariant))
            .addField("__contravariant__", null,
                    self -> PyBool.of(((TypeVarValue) self).contravariant))
            .addField("__infer_variance__", null,
                    self -> PyBool.of(((TypeVarValue) self).inferVariance))
            .addField("__bound__", null, self -> ((TypeVarValue) self).boundOrNone())
            .addField("__constraints__", null,
                    self -> constraintsOf(((TypeVarValue) self).constraints))
            .addField("__default__", null,
                    self -> force(((TypeVarValue) self).defaultValue))
            .addNoArgument("has_default", self -> PyBool.of(((TypeVarValue) self).hasDefault()))
            .addNoArgument("__reduce__", self -> ((TypeVarValue) self).name)
            .addOne("__typing_subst__", (self, argument) -> argument)
            .add("__typing_prepare_subst__",
                    (self, args, kwNames) -> ((TypeVarValue) self).prepareSubst(args, kwNames));

    private static final Members PARAM_SPEC_MEMBERS = Members.of(PyType.PARAM_SPEC)
            .addField("__name__", null, self -> ((ParamSpecValue) self).name)
            .addField("__module__", null, self -> ((ParamSpecValue) self).module)
            .addField("__covariant__", null, self -> PyBool.of(((ParamSpecValue) self).covariant))
            .addField("__contravariant__", null,
                    self -> PyBool.of(((ParamSpecValue) self).contravariant))
            .addField("__infer_variance__", null,
                    self -> PyBool.of(((ParamSpecValue) self).inferVariance))
            .addField("__bound__", null, self -> ((ParamSpecValue) self).boundOrNone())
            .addField("__default__", null,
                    self -> force(((ParamSpecValue) self).defaultValue))
            // Not cached: the reference builds a fresh view for every read, and only compares equal.
            .addField("args", null, self -> new ParamSpecArgsValue((ParamSpecValue) self))
            .addField("kwargs", null, self -> new ParamSpecKwargsValue((ParamSpecValue) self))
            .addNoArgument("has_default", self -> PyBool.of(((ParamSpecValue) self).hasDefault()))
            .addOne("__typing_subst__", (self, argument) -> argument)
            .add("__typing_prepare_subst__",
                    (self, args, kwNames) -> ((ParamSpecValue) self).prepareSubst(args, kwNames));

    private static final Members TYPE_VAR_TUPLE_MEMBERS = Members.of(PyType.TYPE_VAR_TUPLE)
            .addField("__name__", null, self -> ((TypeVarTupleValue) self).name)
            .addField("__module__", null, self -> ((TypeVarTupleValue) self).module)
            .addField("__default__", null,
                    self -> force(((TypeVarTupleValue) self).defaultValue))
            .addNoArgument("has_default",
                    self -> PyBool.of(((TypeVarTupleValue) self).hasDefault()))
            .addOne("__typing_subst__", (self, argument) -> {
                throw Slots.typeError("Substitution of bare TypeVarTuple is not supported");
            })
            .add("__typing_prepare_subst__",
                    (self, args, kwNames) ->
                            ((TypeVarTupleValue) self).prepareSubst(args, kwNames));

    private static final Members PARAM_SPEC_ARGS_MEMBERS = Members.of(PyType.PARAM_SPEC_ARGS)
            .addField("__origin__", null, self -> ((ParamSpecArgsValue) self).origin);

    private static final Members PARAM_SPEC_KWARGS_MEMBERS = Members.of(PyType.PARAM_SPEC_KWARGS)
            .addField("__origin__", null, self -> ((ParamSpecKwargsValue) self).origin);

    private static final Members TYPE_ALIAS_TYPE_MEMBERS = Members.of(PyType.TYPE_ALIAS_TYPE)
            .addField("__name__", null, self -> ((TypeAliasTypeValue) self).name)
            .addField("__module__", null, self -> ((TypeAliasTypeValue) self).module)
            .addField("__value__", null, self -> force(((TypeAliasTypeValue) self).value))
            .addField("__type_params__", null,
                    self -> PyTuple.of(((TypeAliasTypeValue) self).typeParams))
            .addField("__parameters__", null,
                    self -> PyTuple.of(((TypeAliasTypeValue) self).typeParams));

    /**
     * {@code Union}'s subscript, which the reference keeps beside the type rather than in {@code typing}.
     *
     * <p>{@code Union[int, str]} is {@code int | str}; one member answers itself, and no members is the
     * one refusal.
     */
    private static final Members UNION_MEMBERS = Members.of(PyType.UNION_TYPE)
            .addClassNamed("__class_getitem__",
                    (type, args, kwNames) -> {
                        Arguments.exactlyOne("Union.__class_getitem__", args, kwNames);
                        return union(args[0]);
                    });

    /**
     * {@code Generic}'s two hooks, which are the C type's whole behaviour.
     *
     * <p>Both are looked up in {@code typing} at the moment of the call rather than captured when this
     * class is loaded: {@code typing} is what imported this module, so its own definitions do not exist
     * yet while {@code _typing} is being built.
     */
    private static final Members GENERIC_MEMBERS = Members.of(PyType.TYPING_GENERIC)
            // The reference forwards the arguments as they arrived: `Generic[int]` is two, and no
            // argument at all is `_generic_class_getitem() missing 1 required positional argument`.
            .addClassNamed("__class_getitem__",
                    (type, args, kwNames) -> typingCall("_generic_class_getitem",
                            joined(type, args), kwNames))
            .addClassNamed("__init_subclass__",
                    (type, args, kwNames) -> typingCall("_generic_init_subclass",
                            joined(type, args), kwNames));

    // --- the constructors ---------------------------------------------------------------------

    static PyObject typeVar(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        boolean[] seen = new boolean[kwNames == null ? 0 : kwNames.length];
        PyObject byName = keyword(args, kwNames, positional, "name", seen, "typevar");
        PyObject name;
        int first;
        if (positional >= 1) {
            if (byName != null) {
                throw Slots.typeError("typevar() got multiple values for argument 'name'");
            }
            name = args[0];
            first = 1;
        } else if (byName != null) {
            name = byName;
            first = 0;
        } else {
            throw Slots.typeError("typevar() missing required argument 'name' (pos 1)");
        }
        PyStr text = nameOf(name, "typevar");
        int count = positional - first;
        if (count == 1) {
            throw Slots.typeError("A single constraint is not allowed");
        }
        PyObject[] constraints = Arrays.copyOfRange(args, first, positional);
        PyObject bound = keyword(args, kwNames, positional, "bound", seen, "typevar");
        boolean covariant = flag(args, kwNames, positional, "covariant", seen, "typevar");
        boolean contravariant =
                flag(args, kwNames, positional, "contravariant", seen, "typevar");
        boolean inferVariance =
                flag(args, kwNames, positional, "infer_variance", seen, "typevar");
        PyObject defaultValue = keyword(args, kwNames, positional, "default", seen, "typevar");
        rejectUnknown(kwNames, seen, "typevar");
        if (covariant && contravariant) {
            throw Slots.valueError("Bivariant types are not supported.");
        }
        if (inferVariance && (covariant || contravariant)) {
            throw Slots.valueError("Variance cannot be specified with infer_variance.");
        }
        return new TypeVarValue(text, callerModule(), covariant, contravariant, inferVariance,
                bound, constraints, defaultValue == null ? NoDefault.INSTANCE : defaultValue);
    }

    static PyObject paramSpec(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 1) {
            throw Slots.typeError(
                    "paramspec() takes exactly 1 positional argument (%d given)", positional);
        }
        boolean[] seen = new boolean[kwNames == null ? 0 : kwNames.length];
        PyObject byName = keyword(args, kwNames, positional, "name", seen, "paramspec");
        PyObject name;
        if (positional == 1) {
            if (byName != null) {
                throw Slots.typeError("paramspec() got multiple values for argument 'name'");
            }
            name = args[0];
        } else if (byName != null) {
            name = byName;
        } else {
            throw Slots.typeError("paramspec() missing required argument 'name' (pos 1)");
        }
        PyStr text = nameOf(name, "paramspec");
        PyObject bound = keyword(args, kwNames, positional, "bound", seen, "paramspec");
        boolean covariant = flag(args, kwNames, positional, "covariant", seen, "paramspec");
        boolean contravariant =
                flag(args, kwNames, positional, "contravariant", seen, "paramspec");
        boolean inferVariance =
                flag(args, kwNames, positional, "infer_variance", seen, "paramspec");
        PyObject defaultValue = keyword(args, kwNames, positional, "default", seen, "paramspec");
        rejectUnknown(kwNames, seen, "paramspec");
        if (covariant && contravariant) {
            throw Slots.valueError("Bivariant types are not supported.");
        }
        if (inferVariance && (covariant || contravariant)) {
            throw Slots.valueError("Variance cannot be specified with infer_variance.");
        }
        return new ParamSpecValue(text, callerModule(), covariant, contravariant, inferVariance,
                bound, defaultValue == null ? NoDefault.INSTANCE : defaultValue);
    }

    static PyObject typeVarTuple(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 1) {
            throw Slots.typeError(
                    "typevartuple() takes exactly 1 positional argument (%d given)", positional);
        }
        boolean[] seen = new boolean[kwNames == null ? 0 : kwNames.length];
        PyObject byName = keyword(args, kwNames, positional, "name", seen, "typevartuple");
        PyObject name;
        if (positional == 1) {
            if (byName != null) {
                throw Slots.typeError("typevartuple() got multiple values for argument 'name'");
            }
            name = args[0];
        } else if (byName != null) {
            name = byName;
        } else {
            throw Slots.typeError("typevartuple() missing required argument 'name' (pos 1)");
        }
        PyStr text = nameOf(name, "typevartuple");
        PyObject defaultValue =
                keyword(args, kwNames, positional, "default", seen, "typevartuple");
        rejectUnknown(kwNames, seen, "typevartuple");
        return new TypeVarTupleValue(text, callerModule(),
                defaultValue == null ? NoDefault.INSTANCE : defaultValue);
    }

    static PyObject typeAliasType(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 2) {
            throw Slots.typeError(
                    "typealias() takes exactly 2 positional arguments (%d given)", positional);
        }
        boolean[] seen = new boolean[kwNames == null ? 0 : kwNames.length];
        PyObject byName = keyword(args, kwNames, positional, "name", seen, "typealias");
        PyObject valueByName = keyword(args, kwNames, positional, "value", seen, "typealias");
        PyObject name;
        PyObject value;
        if (positional >= 1) {
            if (byName != null) {
                throw Slots.typeError("typealias() got multiple values for argument 'name'");
            }
            name = args[0];
        } else if (byName != null) {
            name = byName;
        } else {
            throw Slots.typeError("typealias() missing required argument 'name' (pos 1)");
        }
        if (positional >= 2) {
            if (valueByName != null) {
                throw Slots.typeError("typealias() got multiple values for argument 'value'");
            }
            value = args[1];
        } else if (valueByName != null) {
            value = valueByName;
        } else {
            throw Slots.typeError("typealias() missing required argument 'value' (pos 2)");
        }
        PyStr text = nameOf(name, "typealias");
        PyObject byParams = keyword(args, kwNames, positional, "type_params", seen, "typealias");
        rejectUnknown(kwNames, seen, "typealias");
        PyObject[] typeParams = byParams == null ? new PyObject[0] : typeParamsOf(byParams);
        return new TypeAliasTypeValue(text, callerModule(), value, typeParams);
    }

    static PyObject paramSpecArgs(PyObject[] args, String[] kwNames) {
        return new ParamSpecArgsValue(originOf(args, kwNames, "paramspecargs"));
    }

    static PyObject paramSpecKwargs(PyObject[] args, String[] kwNames) {
        return new ParamSpecKwargsValue(originOf(args, kwNames, "paramspeckwargs"));
    }

    /**
     * {@code Generic()}: a bare instance, and nothing to hand it.
     *
     * <p>CPython gives {@code Generic} a {@code tp_new} of {@code object}'s and a {@code tp_name} of
     * {@code typing.Generic}, which is why its refusal carries the module and why {@code Generic} itself
     * is instantiable.
     */
    static PyObject generic(PyObject[] args, String[] kwNames) {
        if (args.length != 0 || kwNames != null) {
            throw Slots.typeError("typing.Generic() takes no arguments");
        }
        return new PyInstance(PyType.TYPING_GENERIC);
    }

    /** {@code NoDefaultType()}: the one sentinel, whichever way it is asked for. */
    static PyObject noDefault(PyObject[] args, String[] kwNames) {
        if (args.length != 0 || kwNames != null) {
            throw Slots.typeError("NoDefaultType() takes no arguments");
        }
        return NoDefault.INSTANCE;
    }

    // --- the values ---------------------------------------------------------------------------

    /**
     * The thunk a PEP 695 parameter or alias carries in place of a value.
     *
     * <p>The compiler cannot evaluate a bound, a default or a {@code type} statement's value where it
     * builds the parameter, because a name it mentions may not exist yet; it leaves a function of the
     * annotation format instead. Every read runs it at {@code Format.VALUE} and keeps the answer, which
     * is why defining {@code class C[T: Missing]} succeeds and reading {@code __bound__} raises {@code
     * NameError}.
     */
    static final class Lazy extends PyObject {

        private final PyObject thunk;
        private PyObject value;

        private Lazy(PyObject thunk) {
            super(PyType.OBJECT);
            this.thunk = thunk;
        }

        private PyObject force() {
            if (value == null) {
                value = Slots.call(thunk, new PyObject[] {PyInt.of(PyFunction.VALUE_FORMAT)}, null);
            }
            return value;
        }

        @Override
        public String repr() {
            return "<type parameter thunk>";
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }
    }

    private static PyObject lazy(PyObject thunk) {
        return new Lazy(thunk);
    }

    /** {@code value}, run first if it is a thunk; {@code null} stays {@code null}. */
    static PyObject force(PyObject value) {
        return value instanceof Lazy lazy ? lazy.force() : value;
    }

    /** The constraints a {@code TypeVar} was declared with, evaluating them on first read. */
    private static PyObject constraintsOf(PyObject[] constraints) {
        if (constraints.length == 1 && constraints[0] instanceof Lazy lazy) {
            PyObject evaluated = lazy.force();
            return evaluated instanceof PyTuple tuple
                    ? tuple
                    : PyTuple.of(new PyObject[] {evaluated});
        }
        return PyTuple.of(constraints);
    }

    // --- what the compiler's PEP 695 intrinsics build -----------------------------------------
    //
    // A parameter the compiler makes is distinguishable from one a person writes: its module is
    // `typing`, and its variance is inferred rather than declared. The reference hardcodes both in
    // `_Py_make_typevar`, and they are observable — `repr` drops the tilde for an inferred variable.

    private static final PyStr PARAMETER_MODULE = PyStr.interned("typing");

    /** {@code INTRINSIC_TYPEVAR}: {@code TypeVar(name)} in the module whose frame declared it. */
    static PyObject typeVarParameter(PyStr name) {
        return new TypeVarValue(name, PARAMETER_MODULE, false, false, true, null, new PyObject[0],
                NoDefault.INSTANCE);
    }

    /** {@code INTRINSIC_PARAMSPEC}: {@code ParamSpec(name)} for the {@code **P} spelling. */
    static PyObject paramSpecParameter(PyStr name) {
        return new ParamSpecValue(name, PARAMETER_MODULE, false, false, true, null,
                NoDefault.INSTANCE);
    }

    /** {@code INTRINSIC_TYPEVARTUPLE}: {@code TypeVarTuple(name)} for the {@code *Ts} spelling. */
    static PyObject typeVarTupleParameter(PyStr name) {
        return new TypeVarTupleValue(name, PARAMETER_MODULE, NoDefault.INSTANCE);
    }

    /** {@code INTRINSIC_TYPEVAR_WITH_BOUND}: the bound is read from {@code thunk} on demand. */
    static PyObject typeVarWithBound(PyStr name, PyObject thunk) {
        return new TypeVarValue(name, PARAMETER_MODULE, false, false, true, lazy(thunk),
                new PyObject[0], NoDefault.INSTANCE);
    }

    /** {@code INTRINSIC_TYPEVAR_WITH_CONSTRAINTS}: the thunk answers the whole constraint tuple. */
    static PyObject typeVarWithConstraints(PyStr name, PyObject thunk) {
        return new TypeVarValue(name, PARAMETER_MODULE, false, false, true, null,
                new PyObject[] {lazy(thunk)}, NoDefault.INSTANCE);
    }

    /** {@code INTRINSIC_SET_TYPEPARAM_DEFAULT}: the default is a thunk, read like a bound is. */
    static PyObject withDefault(PyObject parameter, PyObject thunk) {
        PyObject held = lazy(thunk);
        if (parameter instanceof TypeVarValue value) {
            value.defaultValue = held;
        } else if (parameter instanceof ParamSpecValue value) {
            value.defaultValue = held;
        } else if (parameter instanceof TypeVarTupleValue value) {
            value.defaultValue = held;
        } else {
            throw Slots.typeError("Expected a type parameter, got '%s'",
                    parameter.getType().filedName());
        }
        return parameter;
    }

    /** {@code INTRINSIC_SET_FUNCTION_TYPE_PARAMS}: the function keeps the tuple and is handed back. */
    static PyObject setFunctionTypeParams(PyObject function, PyObject params) {
        function.setAttr("__type_params__", params);
        return function;
    }

    /**
     * {@code INTRINSIC_SUBSCRIPT_GENERIC}: {@code Generic[params]}, the base a {@code class C[T]} has.
     *
     * <p>It goes through typing's own {@code _generic_class_getitem} rather than building an alias here,
     * so the base a PEP 695 class gets is exactly the one a person writes as {@code Generic[T]}; the
     * subscription's arguments travel as one tuple, which is how {@code Generic[T, U]} spells them.
     */
    static PyObject genericBase(PyObject params) {
        return typingCall("_generic_class_getitem",
                new PyObject[] {PyType.TYPING_GENERIC, params}, null);
    }

    /** {@code INTRINSIC_TYPEALIAS}: a {@code type} statement, whose value waits for a read. */
    static PyObject typeAlias(PyStr name, PyObject thunk, PyObject[] typeParams) {
        return new TypeAliasTypeValue(name, callerModule(), lazy(thunk), typeParams);
    }

    /** The sentinel {@code typing.NoDefault}, whose type is {@code builtins.NoDefaultType}. */
    static final class NoDefault extends PyObject {

        static final NoDefault INSTANCE = new NoDefault();

        private NoDefault() {
            super(PyType.NO_DEFAULT_TYPE);
        }

        @Override
        public String repr() {
            return "typing.NoDefault";
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }
    }

    /** {@code TypeVar('T')}: a name, a variance, and the bound or constraints it was declared with. */
    static final class TypeVarValue extends PyObject {

        private final PyStr name;
        private final PyStr module;
        private final boolean covariant;
        private final boolean contravariant;
        private final boolean inferVariance;
        private final PyObject bound;
        private final PyObject[] constraints;
        private PyObject defaultValue;

        private TypeVarValue(PyStr name, PyStr module, boolean covariant, boolean contravariant,
                boolean inferVariance, PyObject bound, PyObject[] constraints,
                PyObject defaultValue) {
            super(PyType.TYPE_VAR);
            this.name = name;
            this.module = module;
            this.covariant = covariant;
            this.contravariant = contravariant;
            this.inferVariance = inferVariance;
            this.bound = bound;
            this.constraints = constraints;
            this.defaultValue = defaultValue;
        }

        private PyObject boundOrNone() {
            return bound == null ? PyNone.INSTANCE : force(bound);
        }

        private boolean hasDefault() {
            return defaultValue != NoDefault.INSTANCE;
        }

        @Override
        public PyObject binaryOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(this, other) : super.binaryOp(op, other);
        }

        @Override
        public PyObject reflectedOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(other, this) : super.reflectedOp(op, other);
        }

        @Override
        public String repr() {
            String text = name.getValue();
            if (covariant) {
                return "+" + text;
            }
            if (contravariant) {
                return "-" + text;
            }
            // A variance-inferred parameter is written bare; an ordinary one keeps the tilde.
            return inferVariance ? text : "~" + text;
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }

        private PyObject prepareSubst(PyObject[] args, String[] kwNames) {
            return TypingModule.prepareSubst(this, args, kwNames);
        }
    }

    /** {@code ParamSpec('P')}: the type-parameter kind a callable's signature is captured in. */
    static final class ParamSpecValue extends PyObject {

        private final PyStr name;
        private final PyStr module;
        private final boolean covariant;
        private final boolean contravariant;
        private final boolean inferVariance;
        private final PyObject bound;
        private PyObject defaultValue;

        private ParamSpecValue(PyStr name, PyStr module, boolean covariant, boolean contravariant,
                boolean inferVariance, PyObject bound, PyObject defaultValue) {
            super(PyType.PARAM_SPEC);
            this.name = name;
            this.module = module;
            this.covariant = covariant;
            this.contravariant = contravariant;
            this.inferVariance = inferVariance;
            this.bound = bound;
            this.defaultValue = defaultValue;
        }

        private PyObject boundOrNone() {
            return bound == null ? PyNone.INSTANCE : force(bound);
        }

        private boolean hasDefault() {
            return defaultValue != NoDefault.INSTANCE;
        }

        @Override
        public PyObject binaryOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(this, other) : super.binaryOp(op, other);
        }

        @Override
        public PyObject reflectedOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(other, this) : super.reflectedOp(op, other);
        }

        @Override
        public String repr() {
            String text = name.getValue();
            if (covariant) {
                return "+" + text;
            }
            if (contravariant) {
                return "-" + text;
            }
            return inferVariance ? text : "~" + text;
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }

        private PyObject prepareSubst(PyObject[] args, String[] kwNames) {
            return TypingModule.prepareSubst(this, args, kwNames);
        }
    }

    /** {@code TypeVarTuple('Ts')}: the parameter kind written {@code *Ts}. */
    static final class TypeVarTupleValue extends PyObject {

        private final PyStr name;
        private final PyStr module;
        private PyObject defaultValue;

        private TypeVarTupleValue(PyStr name, PyStr module, PyObject defaultValue) {
            super(PyType.TYPE_VAR_TUPLE);
            this.name = name;
            this.module = module;
            this.defaultValue = defaultValue;
        }

        private boolean hasDefault() {
            return defaultValue != NoDefault.INSTANCE;
        }

        @Override
        public PyObject binaryOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(this, other) : super.binaryOp(op, other);
        }

        @Override
        public PyObject reflectedOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(other, this) : super.reflectedOp(op, other);
        }

        @Override
        public String repr() {
            return name.getValue();
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }

        private PyObject prepareSubst(PyObject[] args, String[] kwNames) {
            return TypingModule.prepareSubst(this, args, kwNames);
        }
    }

    /** {@code P.args}: a view of a {@code ParamSpec}'s positional half. */
    static final class ParamSpecArgsValue extends PyObject {

        private final PyObject origin;

        private ParamSpecArgsValue(PyObject origin) {
            super(PyType.PARAM_SPEC_ARGS);
            this.origin = origin;
        }

        @Override
        public String repr() {
            return originName(origin) + ".args";
        }

        @Override
        public boolean eq(PyObject other) {
            return other instanceof ParamSpecArgsValue view && view.origin == origin;
        }
    }

    /** {@code P.kwargs}: a view of a {@code ParamSpec}'s keyword half. */
    static final class ParamSpecKwargsValue extends PyObject {

        private final PyObject origin;

        private ParamSpecKwargsValue(PyObject origin) {
            super(PyType.PARAM_SPEC_KWARGS);
            this.origin = origin;
        }

        @Override
        public String repr() {
            return originName(origin) + ".kwargs";
        }

        @Override
        public boolean eq(PyObject other) {
            return other instanceof ParamSpecKwargsValue view && view.origin == origin;
        }
    }

    /** {@code TypeAliasType('A', int)}: a name for a value, written lazily. */
    static final class TypeAliasTypeValue extends PyObject {

        private final PyStr name;
        private final PyStr module;
        private final PyObject value;
        private final PyObject[] typeParams;

        private TypeAliasTypeValue(PyStr name, PyStr module, PyObject value,
                PyObject[] typeParams) {
            super(PyType.TYPE_ALIAS_TYPE);
            this.name = name;
            this.module = module;
            this.value = value;
            this.typeParams = typeParams;
        }

        @Override
        public PyObject binaryOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(this, other) : super.binaryOp(op, other);
        }

        @Override
        public PyObject reflectedOp(BinaryOp op, PyObject other) {
            return op == BinaryOp.OR ? PyUnionType.checked(other, this) : super.reflectedOp(op, other);
        }

        @Override
        public PyObject getItem(PyObject key) {
            throw Slots.typeError("Only generic type aliases are subscriptable");
        }

        @Override
        public String repr() {
            return name.getValue();
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** {@code Union[...]}: the checked builder, which is also what {@code types.UnionType[...]} uses. */
    private static PyObject union(PyObject key) {
        PyObject[] items = key instanceof PyTuple tuple ? tuple.elements()
                : new PyObject[] {key};
        return PyUnionType.fromTuple(items);
    }

    private static PyObject typingCall(String name, PyObject[] args, String[] kwNames) {
        PyObject typing = PyRuntime.existingModule("typing");
        if (typing == null) {
            throw Slots.typeError("typing is not imported");
        }
        return Slots.call(typing.getAttr(name), args, keywordTuple(kwNames));
    }

    /** The keyword names a native body carries, as the tuple {@link Slots#call} wants. */
    private static PyTuple keywordTuple(String[] kwNames) {
        if (kwNames == null) {
            return null;
        }
        PyObject[] names = new PyObject[kwNames.length];
        for (int i = 0; i < kwNames.length; i++) {
            names[i] = PyStr.interned(kwNames[i]);
        }
        return PyTuple.of(names);
    }

    private static PyObject[] joined(PyObject first, PyObject[] rest) {
        PyObject[] all = new PyObject[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private static PyStr nameOf(PyObject name, String function) {
        if (name instanceof PyStr text) {
            return text;
        }
        throw Slots.typeError("%s() argument 'name' must be str, not %s",
                function, name.getType().name());
    }

    private static PyStr callerModule() {
        Frame caller = Interpreter.currentFrame();
        PyObject name = caller == null ? null : caller.globals().get(PyStr.interned("__name__"));
        return name instanceof PyStr text ? text : PyStr.interned("builtins");
    }

    private static PyObject originOf(PyObject[] args, String[] kwNames, String function) {
        int positional = Arguments.positional(args, kwNames);
        PyObject origin = positional >= 1 ? args[0] : null;
        boolean named = false;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!kwNames[i].equals("origin")) {
                    throw Slots.typeError("%s() got an unexpected keyword argument '%s'",
                            function, kwNames[i]);
                }
                named = true;
                origin = args[positional + i];
            }
        }
        if (positional > 1 || (positional >= 1 && named)) {
            throw Slots.typeError(
                    "%s() takes at most 1 argument (%d given)", function, args.length);
        }
        if (origin == null) {
            throw Slots.typeError("%s() missing required argument 'origin' (pos 1)", function);
        }
        return origin;
    }

    private static PyObject[] typeParamsOf(PyObject given) {
        PyObject[] params = given instanceof PyTuple tuple ? tuple.elements()
                : new PyObject[] {given};
        for (PyObject param : params) {
            if (!(param instanceof TypeVarValue)
                    && !(param instanceof ParamSpecValue)
                    && !(param instanceof TypeVarTupleValue)) {
                throw Slots.typeError("Expected a type param, got %s", param.repr());
            }
        }
        return params;
    }

    /** The name a {@code ParamSpec} view prints in front of {@code .args}. */
    private static String originName(PyObject origin) {
        if (origin instanceof TypeVarValue view) {
            return view.name.getValue();
        }
        if (origin instanceof ParamSpecValue view) {
            return view.name.getValue();
        }
        if (origin instanceof TypeVarTupleValue view) {
            return view.name.getValue();
        }
        return origin.repr();
    }

    private static PyObject keyword(PyObject[] args, String[] kwNames, int positional, String name,
            boolean[] seen, String function) {
        if (kwNames == null) {
            return null;
        }
        PyObject found = null;
        for (int i = 0; i < kwNames.length; i++) {
            if (kwNames[i].equals(name)) {
                found = args[positional + i];
                seen[i] = true;
            }
        }
        return found;
    }

    private static boolean flag(PyObject[] args, String[] kwNames, int positional, String name,
            boolean[] seen, String function) {
        PyObject value = keyword(args, kwNames, positional, name, seen, function);
        return value != null && Slots.truth(value);
    }

    private static void rejectUnknown(String[] kwNames, boolean[] seen, String function) {
        if (kwNames == null) {
            return;
        }
        for (int i = 0; i < kwNames.length; i++) {
            if (!seen[i]) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", function, kwNames[i]);
            }
        }
    }

    /**
     * {@code __typing_prepare_subst__}: fill in a trailing default, or report too few arguments.
     *
     * <p>The argument list a generic is subscripted with has to line up with the alias's
     * {@code __parameters__}; a parameter that has a default may stand for the one argument that was left
     * off, and a parameter past the end is the reference's {@code Too few arguments} sentence.
     */
    private static PyObject prepareSubst(PyObject parameter, PyObject[] args, String[] kwNames) {
        Arguments.exactly("typevar.__typing_prepare_subst__", args, kwNames, 2);
        PyObject alias = args[0];
        PyObject[] given = args[1] instanceof PyTuple tuple ? tuple.elements()
                : new PyObject[] {args[1]};
        int at = indexOf(alias, parameter);
        if (at < 0) {
            throw Slots.typeError("%s is not in %s", parameter.repr(), alias.repr());
        }
        if (at == given.length && hasDefault(parameter)) {
            PyObject[] extended = Arrays.copyOf(given, given.length + 1);
            extended[given.length] = defaultValue(parameter);
            given = extended;
        }
        if (at >= given.length) {
            throw Slots.typeError("Too few arguments for %s", alias.repr());
        }
        return PyTuple.of(given);
    }

    private static int indexOf(PyObject alias, PyObject parameter) {
        PyObject parameters = alias.getAttr("__parameters__");
        if (!(parameters instanceof PyTuple tuple)) {
            return -1;
        }
        for (int i = 0; i < tuple.size(); i++) {
            if (tuple.at(i) == parameter) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasDefault(PyObject parameter) {
        return parameter.getAttr("has_default").isCallable()
                && Slots.truth(Slots.call(parameter.getAttr("has_default"),
                        new PyObject[0], null));
    }

    private static PyObject defaultValue(PyObject parameter) {
        return parameter.getAttr("__default__");
    }
}
