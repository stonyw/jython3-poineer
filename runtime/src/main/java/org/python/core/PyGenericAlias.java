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
 * origin, so {@code list[int].append} is {@code list.append} and {@code dir()} of the value is the origin's.
 * Only the names an alias answers for itself are filed here, and a subscript of an alias is the refusal
 * CPython makes too — {@code list[int]} is not itself a class that can be subscripted again.
 *
 * <p>The call is carried, and it is the half that makes an alias of {@code enumerate} worth having: {@code
 * ga_vectorcall} ({@code genericaliasobject.c:662}) asks nothing of the arguments the alias files and hands
 * the whole list to the origin, so {@code enumerate[int]("ab")} is the cursor {@code enumerate("ab")} makes.
 * The one thing it does that is not a forward is {@code set_orig_class}, which writes {@code __orig_class__}
 * onto the value it built and clears the refusal when that value will not take a new attribute — and every
 * value an origin here makes is a builtin's, so nothing a program can read differs.
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

    /**
     * {@code enumerate[int]("ab")}: the call the origin makes, with nothing about the alias in it.
     *
     * <p>The keyword names travel with the positionals exactly as they arrived, so {@code list[int](x=1)}
     * refuses in {@code list}'s own words rather than in a sentence about an alias.
     */
    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return Slots.call(origin, args, kwNames);
    }

    /** {@code callable(list[int])}: true, because the origin is what a call of this value reaches. */
    @Override
    public boolean isCallable() {
        return true;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__origin__" -> origin;
            case "__args__" -> PyTuple.of(arguments);
            // The parameters are the type variables the arguments were left as; every argument here is a
            // class already, so there are none — which is also why `list[int]` cannot be reused as one.
            case "__parameters__" -> PyTuple.EMPTY;
            default -> Slots.DECLINED;
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
            // A list argument is the ParamSpec's shape of the same thing: the brackets stay and every item
            // is written by `_Py_typing_type_repr`, so `list[[int]]` names the class inside rather than
            // repr-ing the list that holds it.
            out.append(
                    arguments[i] instanceof PyList items ? shown(items) : displayed(arguments[i]));
        }
        if (arguments.length == 0) {
            // `list[()]` keeps the empty parentheses rather than printing `list[]`.
            out.append("()");
        }
        return out.append(']').toString();
    }

    /** {@code ga_repr_items_list}: the items of a list argument, each written as an argument is. */
    private static String shown(PyList items) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(displayed(items.at(i)));
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
