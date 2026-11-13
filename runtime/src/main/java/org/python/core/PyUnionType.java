package org.python.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code int | str}: the union PEP 604 spells with the bar.
 *
 * <p>The value keeps its members in the order they were written and, separately, splits them by whether
 * they can be hashed. That split is the whole of {@code union_hash}'s odd behaviour: a union of unhashable
 * classes is still constructible, its hash is refused with the message of the first member that still
 * cannot be hashed, and once every member can be hashed the refusal becomes the count of members the
 * union was built without hashes for — the hash is computed on every call rather than stored at
 * construction, so the sentence it fails with can change while the union itself cannot.
 *
 * <p>Two builders write these values, and the difference between them is the reference's, not this
 * runtime's. {@code type.__or__} and an alias's bar accept only operands that are already unionable and
 * build unchecked, which is why {@code int | 3} is an unsupported operand rather than a union with an
 * integer in it. A union's own bar, {@code types.UnionType[...]} and {@code typing.Union[...]} are checked:
 * every member goes through {@code typing._type_check}, so {@code "str"} becomes a {@code ForwardRef} and
 * {@code 3} is kept as it is. A union member is flattened into the union being built, and a member already
 * present is skipped — by hash for the hashable ones and by comparison for the rest.
 */
final class PyUnionType extends PyObject {

    private final PyObject[] members;
    private final Set<PyObject> hashable;
    private final List<PyObject> unhashable;
    /** {@code __parameters__}, computed once from the members the first time it is asked for. */
    private PyTuple parameters;

    private PyUnionType(PyObject[] members, Set<PyObject> hashable, List<PyObject> unhashable) {
        super(PyType.UNION_TYPE);
        this.members = members;
        this.hashable = hashable;
        this.unhashable = unhashable;
    }

    /**
     * {@code is_unionable}: what {@code |} accepts on either side before it builds anything.
     *
     * <p>A class, an alias, another union, {@code None} and a type alias are all types in the sense the
     * operator means; everything else declines, which makes {@code int | 3} the ordinary unsupported
     * operand sentence.
     */
    static boolean operand(PyObject value) {
        return value instanceof PyType
                || value instanceof PyGenericAlias
                || value instanceof PyUnionType
                || value == PyNone.INSTANCE
                || value.getType() == PyType.TYPE_ALIAS_TYPE;
    }

    /** {@code _Py_union_type_or}: the unchecked union {@code left | right} builds. */
    static PyObject of(PyObject left, PyObject right) {
        Builder builder = new Builder();
        builder.addUnchecked(left);
        builder.addUnchecked(right);
        return builder.make();
    }

    /** {@code union_nb_or}: the checked union a bar written after a union makes. */
    static PyObject checked(PyObject left, PyObject right) {
        Builder builder = new Builder();
        builder.addChecked(left);
        builder.addChecked(right);
        return builder.make();
    }

    /** {@code _Py_union_from_tuple}: {@code types.UnionType[...]} and {@code typing.Union[...]}. */
    static PyObject fromTuple(PyObject[] items) {
        Builder builder = new Builder();
        for (PyObject item : items) {
            builder.addChecked(item);
        }
        return builder.make();
    }

    /**
     * {@code type_check}: a member that is already a type, or one {@code typing} can turn into one.
     *
     * <p>{@code None} is {@code NoneType}; anything unionable is kept as it stands; everything else is
     * handed to {@code typing._type_check}, which is where a string becomes a {@code ForwardRef} and an
     * integer is allowed through as itself.
     */
    static PyObject typeCheck(PyObject argument, String message) {
        if (argument == PyNone.INSTANCE) {
            return PyType.NONE;
        }
        if (operand(argument)) {
            return argument;
        }
        // `type_check` imports `typing` on demand rather than reading it out of `sys.modules`: a union
        // built before anything imported `typing` still gets the string-to-`ForwardRef` rewrite. An
        // embedded run whose stdlib has no `typing` keeps the argument as written instead of failing.
        PyObject typing;
        try {
            typing = PyRuntime.importModule("typing");
        } catch (PyRaise missing) {
            return argument;
        }
        if (typing == null) {
            throw Slots.typeError("%s Got %s.", message, argument.repr());
        }
        return Slots.call(
                typing.getAttr("_type_check"), new PyObject[] {argument, PyStr.of(message)}, null);
    }

    /** The members in the order they were written; {@code isinstance} walks them in that order. */
    PyObject[] members() {
        return members;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__args__" -> PyTuple.of(members);
            case "__parameters__" -> PyTuple.of(parameters());
            case "__origin__" -> PyType.UNION_TYPE;
            case "__name__", "__qualname__" -> PyStr.interned("Union");
            // `union_getattro` reads the module off the type rather than answering `'types'`, so the name
            // a union reports is the same one the type built into `typing` reports.
            case "__module__" -> PyType.UNION_TYPE.getAttr("__module__");
            case "__or__" -> new PyNativeFunction("__or__", this::orRow);
            case "__ror__" -> new PyNativeFunction("__ror__", this::rorRow);
            case "__getitem__" -> new PyNativeFunction("__getitem__", this::getItemRow);
            case "__mro_entries__" -> new PyNativeFunction("__mro_entries__", this::mroEntriesRow);
            default -> Slots.DECLINED;
        };
    }

    /** {@code union_getitem}: the members with their parameters filled in and unioned again. */
    @Override
    public PyObject getItem(PyObject key) {
        return fromTuple(PyGenericAlias.substitute(this, members, parameters(), key));
    }

    private PyObject[] parameters() {
        if (parameters == null) {
            parameters = PyTuple.of(PyGenericAlias.makeParameters(members));
        }
        return parameters.getArray();
    }

    private PyObject getItemRow(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("__getitem__", args, kwNames);
        return getItem(args[0]);
    }

    private PyObject orRow(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("__or__", args, kwNames);
        return checked(this, args[0]);
    }

    private PyObject rorRow(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("__ror__", args, kwNames);
        return checked(args[0], this);
    }

    private PyObject mroEntriesRow(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("__mro_entries__", args, kwNames);
        throw Slots.typeError("Cannot subclass %s", repr());
    }

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.OR ? checked(this, other) : super.binaryOp(op, other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.OR ? checked(other, this) : super.reflectedOp(op, other);
    }

    /**
     * {@code union_hash}: the hashable members' hash, or the refusal of the first member that fails.
     *
     * <p>The retry is deliberate: a class whose metatype answers {@code __hash__} with a method that
     * raises at construction time may answer with a number later, so a union that could not be hashed when
     * it was built is not condemned to stay that way. Only when every member now hashes does the union
     * settle for the count in the message.
     */
    @Override
    public long hash() {
        if (unhashable != null) {
            for (PyObject member : unhashable) {
                member.hash();
            }
            throw Slots.typeError("union contains %d unhashable elements", unhashable.size());
        }
        return PyFrozenSet.of(hashable.toArray(new PyObject[0])).hash();
    }

    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyUnionType union)) {
            return false;
        }
        if (!hashable.equals(union.hashable)) {
            return false;
        }
        if (unhashable == null || union.unhashable == null) {
            return unhashable == union.unhashable;
        }
        if (unhashable.size() != union.unhashable.size()) {
            return false;
        }
        for (PyObject member : unhashable) {
            if (!contains(union.unhashable, member)) {
                return false;
            }
        }
        for (PyObject member : union.unhashable) {
            if (!contains(unhashable, member)) {
                return false;
            }
        }
        return true;
    }

    /** {@code PySequence_Contains} over a list of unhashable members: the stored one is the left side. */
    private static boolean contains(List<PyObject> existing, PyObject candidate) {
        for (PyObject item : existing) {
            if (item == candidate || item.eq(candidate)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String repr() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append(PyGenericAlias.displayed(members[i]));
        }
        return out.toString();
    }

    /** The two builders' shared state: the ordered members and the split that hashing needs. */
    private static final class Builder {

        private final List<PyObject> args = new ArrayList<>();
        private final Set<PyObject> hashable = new LinkedHashSet<>();
        private List<PyObject> unhashable;

        void addChecked(PyObject value) {
            if (value == PyNone.INSTANCE) {
                value = PyType.NONE;
            } else if (value instanceof PyUnionType union) {
                for (PyObject member : union.members) {
                    addChecked(member);
                }
                return;
            } else {
                value = typeCheck(value, "Union[arg, ...]: each arg must be a type.");
            }
            addUnchecked(value);
        }

        void addUnchecked(PyObject value) {
            if (value == PyNone.INSTANCE) {
                value = PyType.NONE;
            }
            if (value instanceof PyUnionType union) {
                for (PyObject member : union.members) {
                    addUnchecked(member);
                }
                return;
            }
            boolean hashableValue;
            try {
                value.hash();
                hashableValue = true;
            } catch (RuntimeException notHashable) {
                // The reference clears whatever the hash attempt raised and files the member apart, so
                // the union is built and the failure is repeated only when someone asks for its hash.
                hashableValue = false;
            }
            if (hashableValue) {
                if (!hashable.add(value)) {
                    return;
                }
            } else {
                if (unhashable == null) {
                    unhashable = new ArrayList<>();
                }
                if (contains(unhashable, value)) {
                    return;
                }
                unhashable.add(value);
            }
            args.add(value);
        }

        PyObject make() {
            if (args.isEmpty()) {
                throw Slots.typeError("Cannot take a Union of no types.");
            }
            if (args.size() == 1) {
                return args.get(0);
            }
            return new PyUnionType(
                    args.toArray(new PyObject[0]), hashable, unhashable);
        }
    }
}
