package org.python.core;

/**
 * {@code int | str}: a union of classes, what PEP 604 makes the type in {@code type.__or__} and what
 * Python 3.14 files under {@code typing.Union}.
 *
 * <p>The reference stores the members as a tuple and, since 3.14, allows the union to hold values that are
 * not classes at all — {@code (int | str) | 3} is a union too — while the operator on a class still refuses
 * anything but a class, a union, or {@code None}. The two rules live apart here as well: {@link
 * #operand} is the class-side test and {@link #of} is the builder both sides use, so the looser union keeps
 * its own rule without weakening the strict one.
 *
 * <p>Members are flattened and deduplicated on the way in, which is why {@code int | int} is {@code int}
 * rather than a union of one, and why a union only ever holds values that are not themselves unions. The
 * printed form is the same {@code _Py_typing_type_repr} an alias uses, so a class of another module keeps
 * its dotted name and {@code None} is written as the word.
 */
final class PyUnionType extends PyObject {

    private final PyObject[] members;

    private PyUnionType(PyObject[] members) {
        super(PyType.UNION_TYPE);
        this.members = members;
    }

    /**
     * Whether {@code type.__or__} may absorb this value: another class, a parameterized generic, a union,
     * or {@code None}.
     *
     * <p>The alias is admitted because 3.14 lets a class and an alias make a union — {@code int | list[int]}
     * is one — and the alias's own operator admits a class by the same test.
     */
    static boolean operand(PyObject value) {
        return value instanceof PyType
                || value instanceof PyGenericAlias
                || value instanceof PyUnionType
                || value == PyNone.INSTANCE;
    }

    /**
     * {@code left | right}: the union the two values make, or the one value left after deduplication.
     *
     * <p>A single survivor is returned as itself rather than wrapped, which is what makes {@code int | int}
     * be {@code int} in the reference too. {@code None} is stored as {@code NoneType}, since that is the
     * member {@code __args__} shows.
     */
    static PyObject of(PyObject left, PyObject right) {
        PyObject[] flat = flatten(left, right);
        PyObject[] unique = new PyObject[flat.length];
        int kept = 0;
        for (PyObject candidate : flat) {
            boolean seen = false;
            for (int i = 0; i < kept && !seen; i++) {
                seen = unique[i] == candidate || unique[i].eq(candidate);
            }
            if (!seen) {
                unique[kept++] = candidate;
            }
        }
        if (kept == 1) {
            return unique[0];
        }
        PyObject[] members = new PyObject[kept];
        System.arraycopy(unique, 0, members, 0, kept);
        return new PyUnionType(members);
    }

    /** The two operands' members in order, with a union spread and {@code None} written as its type. */
    private static PyObject[] flatten(PyObject left, PyObject right) {
        PyObject[] lefts = membersOf(left);
        PyObject[] rights = membersOf(right);
        PyObject[] flat = new PyObject[lefts.length + rights.length];
        System.arraycopy(lefts, 0, flat, 0, lefts.length);
        System.arraycopy(rights, 0, flat, lefts.length, rights.length);
        return flat;
    }

    private static PyObject[] membersOf(PyObject value) {
        if (value instanceof PyUnionType union) {
            return union.members;
        }
        if (value == PyNone.INSTANCE) {
            return new PyObject[] {PyType.NONE};
        }
        return new PyObject[] {value};
    }

    /** The members, in the order the union was built; {@code __args__} is this tuple. */
    PyObject[] members() {
        return members;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__args__" -> PyTuple.of(members);
            // A union of classes has no type variables left in it, so there are none to report.
            case "__parameters__" -> PyTuple.EMPTY;
            // Read as attributes, the two operators are the same builders the operator protocol runs.
            case "__or__" -> new PyNativeFunction("__or__", this::orRow);
            case "__ror__" -> new PyNativeFunction("__ror__", this::rorRow);
            default -> Slots.DECLINED;
        };
    }

    private PyObject orRow(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("__or__", args, kwNames);
        return of(this, args[0]);
    }

    private PyObject rorRow(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("__ror__", args, kwNames);
        return of(args[0], this);
    }

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.OR) {
            // A union is the loose half of PEP 604: any value joins, and the builder flattens it in.
            return of(this, other);
        }
        return super.binaryOp(op, other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.OR) {
            return of(other, this);
        }
        return super.reflectedOp(op, other);
    }

    /** {@code (int | str)[0]}: a union is not a class and has nothing to index. */
    @Override
    public PyObject getItem(PyObject key) {
        throw Slots.typeError("%s is not a generic class", repr());
    }

    /**
     * {@code u == v}: the same members in any order, which is how {@code int | str} equals {@code str | int}.
     *
     * <p>The reference compares the members as a set, so length and membership are the whole answer; the
     * order a union prints in is kept from the way it was written but does not enter the comparison.
     */
    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyUnionType union) || union.members.length != members.length) {
            return false;
        }
        for (PyObject member : members) {
            boolean found = false;
            for (PyObject candidate : union.members) {
                if (member == candidate || member.eq(candidate)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** {@code union_hash}: the hash of the members seen as a frozenset. */
    @Override
    public long hash() {
        return PyFrozenSet.of(members).hash();
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
}
