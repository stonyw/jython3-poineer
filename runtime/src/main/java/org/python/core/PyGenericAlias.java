package org.python.core;

import java.util.ArrayList;
import java.util.List;

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
    /** {@code __parameters__}, computed once from the arguments the first time it is asked for. */
    private PyTuple parameters;

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
     * {@code GenericAlias.__new__(cls, origin, arguments)}: the same value, told it was made as {@code cls}.
     *
     * <p>A class Python writes under the alias keeps the reference's layout — a generic alias struct with
     * a {@code Py_TYPE} of the subclass — so the value is the one {@link #of} would make and only the type
     * it reports differs. That is what lets {@code _collections_abc._CallableGenericAlias} answer its own
     * {@code __repr__} and {@code __reduce__} while {@code __origin__} and {@code __args__} still come from
     * this class.
     */
    static PyGenericAlias ofType(PyType type, PyType origin, PyObject[] arguments) {
        PyGenericAlias alias = new PyGenericAlias(origin, arguments);
        alias.type = type;
        return alias;
    }

    /**
     * {@code GenericAlias(origin, arguments)}: the call {@code __class_getitem__ = classmethod(GenericAlias)}
     * makes, which is how {@code _collections_abc} subscripts its own classes.
     *
     * <p>The two arguments are the same pair the constructor below takes, so the value is the same one
     * {@link #of} makes; keywords are refused because the type's {@code tp_new} counts positions only.
     */
    static PyGenericAlias called(PyObject[] args, String[] keywordNames) {
        if (keywordNames != null && keywordNames.length != 0) {
            throw Slots.typeError("GenericAlias() takes no keyword arguments");
        }
        if (args.length != 2) {
            throw Slots.typeError(
                    "GenericAlias() takes exactly 2 arguments (%d given)", args.length);
        }
        if (!(args[0] instanceof PyType origin)) {
            throw Slots.typeError("GenericAlias expects a type as the first argument");
        }
        return of(origin, args[1]);
    }

    /**
     * {@code list[T][int]}: the subscription that replaces each type variable the alias still holds.
     *
     * <p>The arguments of an alias are not all classes — {@code list[T]} keeps the parameter itself — so a
     * second subscript is where the parameter is filled in: {@code list[T][int]} is {@code list[int]} and
     * {@code (int | T)[int]} is {@code int}. An alias with no parameters left is refused in the same words
     * the reference uses for something that is not generic at all.
     */
    @Override
    public PyObject getItem(PyObject key) {
        PyObject[] newArguments = substitute(this, arguments, parameters(), key);
        return new PyGenericAlias(origin, newArguments);
    }

    /**
     * {@code ga_reduce}: the pair {@code (type(self), (origin, args))}, which is how pickle rebuilds an alias.
     *
     * <p>Rebuilding calls the type back with the two arguments the constructor takes, so an alias whose
     * members can be pickled can itself be pickled; {@code copy.copy} reaches the same pair through the
     * generic reduction when no dispatch entry claims the type.
     */
    private PyObject reduce(PyObject[] args, String[] kwNames) {
        Arguments.none("__reduce__", args, kwNames);
        return reduction();
    }

    /** The reduction itself, shared with the dictionary row {@code object.__reduce_ex__} dispatches to. */
    PyObject reduction() {
        return PyTuple.of(getType(), PyTuple.of(origin, PyTuple.of(arguments)));
    }

    /** {@code __parameters__}: the arguments' type variables, built once and kept. */
    private PyObject[] parameters() {
        if (parameters == null) {
            parameters = PyTuple.of(makeParameters(arguments));
        }
        return parameters.getArray();
    }

    /**
     * {@code _Py_make_parameters}: the type variables an argument tuple still holds, in order.
     *
     * <p>A class is a parameter of nothing and is skipped. Anything with {@code __typing_subst__} is a
     * parameter itself and is kept as it stands — not deduplicated, since the alias's {@code __parameters__}
     * keeps the count the substitution hook expects. Anything else contributes what its own
     * {@code __parameters__} says, and a bare tuple or list is walked as one, which is how the parameters
     * of {@code Callable[[T], S]} are found: the list is not a parameter, the T inside it is.
     */
    static PyObject[] makeParameters(PyObject[] arguments) {
        List<PyObject> parameters = new ArrayList<>();
        for (PyObject argument : arguments) {
            if (argument instanceof PyType) {
                continue;
            }
            if (Slots.findAttr(argument, "__typing_subst__") != null) {
                addIdentity(parameters, argument);
                continue;
            }
            PyObject nested = Slots.findAttr(argument, "__parameters__");
            if (nested == null && (argument instanceof PyTuple || argument instanceof PyList)) {
                nested = PyTuple.of(makeParameters(itemsOf(argument)));
            }
            if (nested instanceof PyTuple tuple) {
                for (int i = 0; i < tuple.size(); i++) {
                    addIdentity(parameters, tuple.at(i));
                }
            }
        }
        return parameters.toArray(new PyObject[0]);
    }

    /**
     * {@code _Py_subs_parameters}: this alias's arguments with every parameter replaced by the key's item.
     *
     * <p>The key is unpacked first — a tuple spreads and anything else is one item — and each parameter is
     * given the chance to rewrite the item through {@code __typing_prepare_subst__}. The count is checked
     * only after that, because the hook may add or remove defaults. Each argument is then left alone if it
     * is a class, recursed into if it is a tuple or list, fed to its own {@code __typing_subst__} if it has
     * one, and otherwise handed to {@link #subsTypeVariables} so a nested alias can fill itself in.
     */
    static PyObject[] substitute(
            PyObject source, PyObject[] args, PyObject[] parameters, PyObject item) {
        if (parameters.length == 0) {
            throw Slots.typeError("%s is not a generic class", source.repr());
        }
        PyObject[] items = unpackArgs(item);
        for (PyObject parameter : parameters) {
            if (parameter == PyNone.INSTANCE) {
                continue;
            }
            PyObject prepare = Slots.findAttr(parameter, "__typing_prepare_subst__");
            if (prepare == null || prepare == PyNone.INSTANCE) {
                continue;
            }
            item = Slots.call(prepare, new PyObject[] {source, PyTuple.of(items)}, null);
            items = unpackArgs(item);
        }
        if (items.length != parameters.length) {
            throw Slots.typeError(
                    "Too %s arguments for %s; actual %d, expected %d",
                    items.length > parameters.length ? "many" : "few",
                    source.repr(),
                    items.length,
                    parameters.length);
        }
        List<PyObject> substituted = new ArrayList<>(args.length);
        for (PyObject arg : args) {
            if (arg instanceof PyType) {
                substituted.add(arg);
                continue;
            }
            if (arg instanceof PyTuple || arg instanceof PyList) {
                PyObject[] nested = substitute(source, itemsOf(arg), parameters, PyTuple.of(items));
                substituted.add(arg instanceof PyTuple ? PyTuple.of(nested) : PyList.of(nested));
                continue;
            }
            boolean unpacked = isUnpackedTypeVarTuple(arg);
            PyObject replacement;
            PyObject subst = Slots.findAttr(arg, "__typing_subst__");
            if (subst != null) {
                int index = indexOfIdentity(parameters, arg);
                if (index < 0) {
                    throw Slots.typeError(
                            "argument %s with __typing_subst__ was not found in __parameters__",
                            arg.repr());
                }
                replacement = Slots.call(subst, new PyObject[] {items[index]}, null);
            } else {
                replacement = subsTypeVariables(arg, parameters, items);
            }
            if (!unpacked) {
                substituted.add(replacement);
            } else if (replacement instanceof PyTuple spread) {
                for (int i = 0; i < spread.size(); i++) {
                    substituted.add(spread.at(i));
                }
            } else {
                throw Slots.typeError(
                        "expected __typing_subst__ of %s objects to return a tuple, not %s",
                        arg.getType().name(), replacement.getType().name());
            }
        }
        return substituted.toArray(new PyObject[0]);
    }

    /**
     * {@code subs_tvars}: the arguments of a nested value with its own parameters filled in.
     *
     * <p>A value with no parameters is left as it is. Otherwise each of its parameters is looked up in the
     * outer parameter list and replaced by the matching item — a {@code TypeVarTuple} spreads a tuple item
     * — and the value is subscripted with the result, which is why {@code (list[T] | list[S])[int, str]}
     * reaches {@code list[int] | list[str]}.
     */
    private static PyObject subsTypeVariables(
            PyObject value, PyObject[] parameters, PyObject[] items) {
        PyObject nested = Slots.findAttr(value, "__parameters__");
        if (!(nested instanceof PyTuple tuple) || tuple.size() == 0) {
            return value;
        }
        List<PyObject> arguments = new ArrayList<>(tuple.size());
        for (int i = 0; i < tuple.size(); i++) {
            PyObject arg = tuple.at(i);
            int index = indexOfIdentity(parameters, arg);
            if (index < 0) {
                arguments.add(arg);
                continue;
            }
            PyObject item = items[index];
            if (parameters[index].getType() == PyType.TYPE_VAR_TUPLE
                    && item instanceof PyTuple spread) {
                for (int j = 0; j < spread.size(); j++) {
                    arguments.add(spread.at(j));
                }
                continue;
            }
            arguments.add(item);
        }
        return value.getItem(PyTuple.of(arguments.toArray(new PyObject[0])));
    }

    /** {@code _unpack_args}: a tuple key spreads into its items and any other key is one item. */
    private static PyObject[] unpackArgs(PyObject item) {
        if (item instanceof PyTuple tuple) {
            return tuple.getArray();
        }
        return new PyObject[] {item};
    }

    /** {@code _is_unpacked_typevartuple}: whether the argument was written {@code *Ts}. */
    private static boolean isUnpackedTypeVarTuple(PyObject arg) {
        PyObject flag = Slots.findAttr(arg, "__typing_is_unpacked_typevartuple__");
        return flag != null && Slots.truth(flag);
    }

    private static PyObject[] itemsOf(PyObject sequence) {
        if (sequence instanceof PyTuple tuple) {
            return tuple.getArray();
        }
        PyList list = (PyList) sequence;
        PyObject[] items = new PyObject[list.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = list.at(i);
        }
        return items;
    }

    private static void addIdentity(List<PyObject> parameters, PyObject item) {
        for (PyObject existing : parameters) {
            if (existing == item) {
                return;
            }
        }
        parameters.add(item);
    }

    private static int indexOfIdentity(PyObject[] parameters, PyObject value) {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == value) {
                return i;
            }
        }
        return -1;
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

    /**
     * {@code ga_mro_entries}: the origin, as the one-element tuple a base list splices in.
     *
     * <p>It is what makes {@code class C(List[int])} a subclass of {@code list}: {@code __build_class__}
     * asks every non-class base for this and replaces the base with the tuple it answers.
     */
    PyObject mroEntries() {
        return PyTuple.of(origin);
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__origin__" -> origin;
            case "__args__" -> PyTuple.of(arguments);
            // The parameters are the type variables the arguments were left as, so `list[T]` holds T
            // while `list[int]` holds none — which is also why only the second refuses a second subscript.
            case "__parameters__" -> PyTuple.of(parameters());
            case "__reduce__" -> new PyNativeFunction("__reduce__", this::reduce);
            default -> Slots.DECLINED;
        };
    }

    /**
     * {@code list[int] | int}: the alias joins the union of PEP 604 the way a class does.
     *
     * <p>The accepted operands are the class side's — a class, another alias, a union, {@code None} — so
     * {@code list[int] | 3} is refused in the operator's own words rather than becoming a union of a
     * subscript and an integer. A union on the right is spread, which is why {@code list[int] | (int | str)}
     * prints with the alias first.
     */
    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.OR && PyUnionType.operand(other)) {
            return PyUnionType.of(this, other);
        }
        return super.binaryOp(op, other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.OR && PyUnionType.operand(other)) {
            return PyUnionType.of(other, this);
        }
        return super.reflectedOp(op, other);
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
    static String displayed(PyObject value) {
        if (value == PyEllipsis.INSTANCE) {
            return "...";
        }
        if (value == PyType.NONE) {
            return "None";
        }
        if (value instanceof PyGenericAlias) {
            return value.repr();
        }
        // A union carries `__origin__` and `__args__` as well as `__qualname__`, and CPython's
        // `_Py_typing_type_repr` sends anything with that pair to the plain `repr` before it ever reads
        // the name — which is what makes `list[int | str]` print `int | str` rather than
        // `typing.Union`. The union is the only such value of this family, so it is named directly.
        if (value instanceof PyUnionType) {
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
