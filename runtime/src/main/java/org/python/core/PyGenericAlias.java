package org.python.core;

/**
 * {@code list[int]}: a built-in type read through the subscript that files its arguments.
 *
 * <p>{@code type_getitem} asks the class for {@code __class_getitem__} and calls it with the key, and the
 * method it finds is the one CPython's {@code GenericAlias} type is built to answer with: the origin is the
 * class that was subscripted, the arguments are whatever the key was, and the whole value is a name and a
 * pair of brackets — {@code list[int]} prints as the text it is made of and nothing else. A tuple key
 * spreads, so {@code list[int, str]} keeps two arguments and {@code list[()]} keeps none; any other key is
 * one argument of its own, which is why {@code list[1:2]} keeps a slice and prints it as one.
 *
 * <p>What this runtime does not carry is the alias's other half: CPython reads an unknown name off the
 * origin, so {@code list[int].append} is {@code list.append} and {@code list[int]()} is {@code []}, and
 * {@code dir()} of the value is the origin's. Only the names an alias answers for itself are filed here,
 * and a subscript of an alias is the refusal CPython makes too — {@code list[int]} is not itself a class
 * that can be subscripted again.
 */
public final class PyGenericAlias extends PyObject {

    private final PyType origin;
    private final PyObject[] arguments;

    private PyGenericAlias(PyType origin, PyObject[] arguments) {
        super(PyType.GENERIC_ALIAS);
        this.origin = origin;
        this.arguments = arguments;
    }

    /** {@code origin[key]}: a tuple key spreads into arguments and any other key is an argument alone. */
    static PyGenericAlias of(PyType origin, PyObject key) {
        return new PyGenericAlias(
                origin, key instanceof PyTuple pair ? pair.getArray() : new PyObject[] {key});
    }

    /**
     * {@code list[int][0]}: an alias is a subscription, not a class, so it has nothing to index.
     *
     * <p>The complaint quotes the alias's own {@code repr()}, which is why the message reads
     * {@code list[int] is not a generic class} rather than naming the type of the value.
     */
    @Override
    public PyObject getItem(PyObject key) {
        throw Slots.typeError("%s is not a generic class", repr());
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__origin__" -> origin;
            case "__args__" -> PyTuple.of(arguments);
            // The parameters are the type variables the arguments were left as; every argument here is a
            // class already, so there are none — which is also why `list[int]` cannot be reused as one.
            case "__parameters__" -> PyTuple.EMPTY;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public boolean eq(PyObject other) {
        if (!(other instanceof PyGenericAlias alias) || alias.origin != origin) {
            return false;
        }
        if (alias.arguments.length != arguments.length) {
            return false;
        }
        for (int i = 0; i < arguments.length; i++) {
            if (!arguments[i].eq(alias.arguments[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long hash() {
        // `ga_hash` XORs the origin's own hash with the hash of the arguments seen as one tuple, so
        // `hash(list[int]) == hash(list) ^ hash((int,))` is a fact about the value a program can check.
        return origin.hash() ^ PyTuple.of(arguments).hash();
    }

    @Override
    public String repr() {
        StringBuilder out = new StringBuilder(displayed(origin)).append('[');
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(displayed(arguments[i]));
        }
        if (arguments.length == 0) {
            // `list[()]` keeps the empty parentheses rather than printing `list[]`.
            out.append("()");
        }
        return out.append(']').toString();
    }

    /**
     * {@code _Py_typing_type_repr}: how the origin and each argument are written, which is not always their
     * {@code repr}.
     *
     * <p>A class is named by its qualified name, and the module only goes in front when the name has to be
     * told apart from a builtin's — so {@code list[int]} writes the one word and a class of this module's own
     * writes two. Anything with no qualified name, or with none of the pair an alias is made of, is written
     * as itself: {@code list[3]} and {@code list[1:2]} are the numbers and the slice, not their types. The
     * two names given first are the two a person writes instead of a class: {@code list[None]} says the word
     * {@code None} for the type of that value, and {@code list[Ellipsis]} writes the three dots.
     */
    private static String displayed(PyObject value) {
        if (value == PyEllipsis.INSTANCE) {
            return "...";
        }
        if (value == PyType.NONE) {
            return "None";
        }
        if (value instanceof PyGenericAlias) {
            return value.repr();
        }
        PyObject qualified = Slots.findAttr(value, "__qualname__");
        if (qualified == null) {
            return value.repr();
        }
        PyObject module = Slots.findAttr(value, "__module__");
        if (module == null || module == PyNone.INSTANCE) {
            return value.repr();
        }
        if (module instanceof PyStr named && named.getValue().equals("builtins")) {
            return qualified.str();
        }
        return module.str() + "." + qualified.str();
    }
}
