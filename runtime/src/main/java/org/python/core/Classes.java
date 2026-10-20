package org.python.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Building a class, and calling one: {@code __build_class__}, the three-argument {@code type()}, the
 * C3 linearization every class inherits by, and the {@code __new__}/{@code __init__} pair that turns a
 * class into a value.
 *
 * <p>Each piece is a transcription of the CPython 3.14 function of the same name, down to the order the
 * refusals come in, because the messages are what a Python program reports and CPython's own test suite
 * reads them. {@link #buildClass} is {@code builtin___build_class__}, {@link #newType} is the half of
 * {@code type_new} that this runtime implements, {@link #linearize} is {@code mro_implementation} with
 * its {@code pmerge}, and {@link #construct} is {@code type_call}.
 *
 * <p>What is left out is the metaclass protocol. Every class here is built by {@code type}, so the
 * lookup order is a list of types rather than a slot table a metaclass chose, {@code __prepare__} has
 * no say in what the body writes into, and a class statement with keyword arguments is refused instead
 * of being silently handed to {@code type}. Subclassing a built-in type is refused for the same reason:
 * a {@link PyClass}'s members are a dictionary, and the compiled-in Java slots of {@code int} or
 * {@code str} have nowhere to be carried into it.
 */
final class Classes {

    private Classes() {
    }

    // --- the class statement ---------------------------------------------------------------

    /**
     * {@code __build_class__(func, name, *bases)}: the helper a {@code class} statement calls.
     *
     * <p>Two things about it are easy to get wrong and are copied exactly. The body runs against the
     * namespace as its <em>locals</em> while its global names still come from the module the class is
     * written in, and what the body returns is the {@code __class__} cell rather than the class: the
     * class comes from calling the metaclass on the finished namespace, and the two answers are then
     * compared, which is how a method that stole {@code __class__} is caught.
     */
    static PyObject buildClass(PyObject[] args, String[] kwNames) {
        int positional = kwNames == null ? args.length : args.length - kwNames.length;
        if (positional < 2) {
            throw Slots.typeError("__build_class__: not enough arguments");
        }
        if (!(args[0] instanceof PyFunction body)) {
            throw Slots.typeError("__build_class__: func must be a function");
        }
        if (!(args[1] instanceof PyStr name)) {
            throw Slots.typeError("__build_class__: name is not a string");
        }
        if (kwNames != null) {
            // CPython pops `metaclass` from these and asks it to build the class, giving it
            // `__prepare__` a chance to choose the namespace first. Neither exists yet.
            throw Slots.notImplemented("a class with keyword arguments");
        }
        PyTuple bases = PyTuple.of(Arrays.copyOfRange(args, 2, positional));
        // With no metaclass named, the metatype is `type` for a class with no bases and the type of the
        // first base otherwise — which is why `class C(1)` is refused by `int`, not by the class system.
        PyObject meta = bases.size() == 0 ? PyType.TYPE : bases.at(0).getType();

        PyDict namespace = PyDict.empty();
        PyObject returned = body.runClassBody(namespace);
        PyObject built = Slots.call(meta, new PyObject[] {name, bases, namespace}, null);
        checkClassCell(returned, name, built);
        return built;
    }

    /**
     * The two ways a body's {@code __class__} cell can disagree with the class that was built.
     *
     * <p>An empty cell means the body never stored the class into it — {@code type()} was called with
     * the namespace by hand and {@code __classcell__} was left behind; a cell holding another class
     * means the body was compiled for one class and handed to another. Both are CPython's, both name
     * the class and the cell's content by repr.
     */
    private static void checkClassCell(PyObject returned, PyStr name, PyObject built) {
        if (!(built instanceof PyType) || !(returned instanceof PyCell cell)) {
            return;
        }
        PyObject held = cell.value();
        if (held == null) {
            throw Slots.runtimeError(
                    "__class__ not set defining "
                            + Slots.repr(name)
                            + " as "
                            + Slots.repr(built)
                            + ". Was __classcell__ propagated to type.__new__?");
        }
        if (held != built) {
            throw Slots.typeError("__class__ set to %s defining %s as %s", held, name, built);
        }
    }

    // --- type(name, bases, dict) ------------------------------------------------------------

    /**
     * {@code type('Point', (), {...})}, reached from {@code Constructors}'s three-argument {@code type}
     * and from {@link #buildClass}'s call on the metatype.
     *
     * <p>The argument checks come in CPython's order — {@code PyArg_ParseTuple}'s "UO!O!" before the
     * bases are looked at — and each names the position it rejected, because a namespace passed where a
     * tuple belongs is the mistake a hand-built class makes most often.
     */
    static PyObject newType(PyObject[] args) {
        if (!(args[0] instanceof PyStr name)) {
            throw Slots.typeError(
                    "type.__new__() argument 1 must be str, not %s", args[0].getType().name());
        }
        if (!(args[1] instanceof PyTuple declared)) {
            throw Slots.typeError(
                    "type.__new__() argument 2 must be tuple, not %s", args[1].getType().name());
        }
        if (!(args[2] instanceof PyDict namespace)) {
            throw Slots.typeError(
                    "type.__new__() argument 3 must be dict, not %s", args[2].getType().name());
        }
        // An empty base tuple means object, and is written back into __bases__ as such.
        PyTuple bases = declared.size() == 0 ? PyTuple.of(PyType.OBJECT) : declared;
        for (int i = 0; i < bases.size(); i++) {
            PyObject base = bases.at(i);
            if (!(base instanceof PyType kind)) {
                throw Slots.typeError(
                        "metaclass conflict: the metaclass of a derived class must be a"
                                + " (non-strict) subclass of the metaclasses of all its bases");
            }
            if (!canDeriveFrom(kind)) {
                throw Slots.notImplemented("subclassing '" + kind.name() + "'");
            }
        }
        return declare(name.getValue(), bases, namespace);
    }

    /**
     * Which types a Python class may take as a base.
     *
     * <p>A {@link PyClass} always, since its members are a dictionary and that is what deriving from
     * another class means. {@code object} and the exception hierarchy are the two built-in families
     * whose values this runtime already builds — an exception class carries its args and traceback
     * whatever Python class derives from it — and every other built-in has Java slots no dictionary can
     * stand in for.
     */
    private static boolean canDeriveFrom(PyType base) {
        return base instanceof PyClass
                || base == PyType.OBJECT
                || base.matches(ExceptionTypes.BASE_EXCEPTION);
    }

    /**
     * {@code type_new} once its arguments are in hand: copy the namespace, take the identity names out
     * of it, settle {@code __hash__}, then compute the order the class inherits by.
     *
     * <p>The copy is what makes {@code X.__dict__ is namespace} false, exactly as it is in CPython, and
     * it is why a class built by hand and the namespace it was built from go their separate ways
     * afterwards.
     */
    private static PyClass declare(String name, PyTuple bases, PyDict given) {
        PyDict dict = given.copy();

        String qualified = name;
        PyStr qualNameKey = PyStr.interned("__qualname__");
        PyObject qualName = dict.get(qualNameKey);
        if (qualName != null) {
            if (!(qualName instanceof PyStr text)) {
                throw Slots.typeError(
                        "type __qualname__ must be a str, not %s", qualName.getType().name());
            }
            qualified = text.getValue();
            // Read and removed: the qualified name is part of the type's identity, not an entry a
            // later assignment to X.__dict__ could change.
            dict.remove(qualNameKey);
        }
        if (dict.get(PyStr.interned("__doc__")) == null) {
            dict.put(PyStr.interned("__doc__"), PyNone.INSTANCE);
        }
        // Defining equality without a hash makes a class unhashable, which CPython expresses by
        // putting None under the name in the declaring class's own dictionary.
        if (dict.get(PyStr.interned("__eq__")) != null
                && dict.get(PyStr.interned("__hash__")) == null) {
            dict.put(PyStr.interned("__hash__"), PyNone.INSTANCE);
        }

        PyClass kind = new PyClass(name, bases, dict, qualified);
        kind.order = linearize(kind, bases);
        propagateCells(dict, kind);
        setNames(dict, kind);
        return kind;
    }

    /**
     * {@code insert_members} with {@code check_set_name}: each member tells the class it belongs to.
     *
     * <p>The dictionary order is the order the class body wrote the names in, and it is observable: a
     * {@code __set_name__} that reads a counter sees the members declared before it and none declared
     * after. The class is handed over as the owner, so a descriptor can keep it — that is how a property
     * learns the {@code __qualname__} it reports — and a member with nothing to be told is passed over
     * without a lookup being made on it.
     */
    private static void setNames(PyDict dict, PyClass kind) {
        PyObject[] names = dict.keys();
        PyObject[] members = dict.values();
        for (int i = 0; i < names.length; i++) {
            Descriptors.setName(members[i], kind, ((PyStr) names[i]).str());
        }
    }

    /**
     * {@code type_new_set_classcell} and {@code type_new_set_classdictcell}.
     *
     * <p>A body that mentions {@code __class__} leaves its cell behind under that name, and a body that
     * uses {@code locals()} leaves the {@code __classdict__} cell behind. Both are filled here and both
     * names are taken out of the dictionary, which is why neither appears in {@code X.__dict__} of a
     * finished class.
     */
    private static void propagateCells(PyDict dict, PyClass kind) {
        PyStr classCellKey = PyStr.interned("__classcell__");
        PyObject classCell = dict.get(classCellKey);
        if (classCell != null) {
            if (!(classCell instanceof PyCell cell)) {
                throw Slots.typeError(
                        "__classcell__ must be a nonlocal cell, not %s", classCell.getType());
            }
            cell.set(kind);
            dict.remove(classCellKey);
        }
        PyStr classDictCellKey = PyStr.interned("__classdictcell__");
        PyObject classDictCell = dict.get(classDictCellKey);
        if (classDictCell != null) {
            if (!(classDictCell instanceof PyCell cell)) {
                throw Slots.typeError(
                        "__classdictcell__ must be a nonlocal cell, not %s", classDictCell.getType());
            }
            // The live dictionary, not the snapshot the body saw: a method that reads
            // __classdict__ is meant to see what the class holds now.
            cell.set(dict);
            dict.remove(classDictCellKey);
        }
    }

    // --- the C3 linearization --------------------------------------------------------------

    /**
     * CPython's {@code mro_implementation_unlocked}, for a class being built.
     *
     * <p>A single base needs no merge — its own order, prefixed by the new class, is already C3's
     * answer, which is why {@code Point.__mro__} is {@code (Point, object)} without any of the machinery
     * below being consulted. From two bases up, each base contributes its own order and the declared
     * bases contribute themselves as the last sequence.
     */
    private static PyType[] linearize(PyClass kind, PyTuple bases) {
        if (bases.size() == 1) {
            PyType[] tail = ((PyType) bases.at(0)).mro();
            PyType[] order = new PyType[tail.length + 1];
            order[0] = kind;
            System.arraycopy(tail, 0, order, 1, tail.length);
            return order;
        }
        checkDuplicates(bases);

        List<PyType[]> sequences = new ArrayList<>();
        PyType[] declared = new PyType[bases.size()];
        for (int i = 0; i < bases.size(); i++) {
            PyType base = (PyType) bases.at(i);
            sequences.add(base.mro());
            declared[i] = base;
        }
        sequences.add(declared);

        List<PyType> result = new ArrayList<>();
        result.add(kind);
        merge(result, sequences);
        return result.toArray(new PyType[0]);
    }

    /** {@code check_duplicates}, which comes before the order is even asked for. */
    private static void checkDuplicates(PyTuple bases) {
        for (int i = 0; i < bases.size(); i++) {
            PyObject first = bases.at(i);
            for (int j = i + 1; j < bases.size(); j++) {
                if (bases.at(j) == first) {
                    throw Slots.typeError(
                            "duplicate base class %s", ((PyType) first).name());
                }
            }
        }
    }

    /**
     * {@code pmerge}: take the head of some sequence that is nobody's tail, append it, and start over.
     *
     * <p>The two gotos of the original are the whole of it, and they go different ways. A candidate that
     * is somebody's tail is passed over and the scan moves on to the next sequence ({@code goto skip}),
     * while one that is accepted restarts the scan from the first sequence ({@code goto again}), so the
     * earliest base still able to speak is always the one that decides the next entry. Only a scan that
     * reaches the end without accepting anything is a conflict — unless every sequence was exhausted,
     * which is the normal end.
     */
    private static void merge(List<PyType> result, List<PyType[]> sequences) {
        int[] remain = new int[sequences.size()];
        int count = sequences.size();
        again:
        while (true) {
            int exhausted = 0;
            scan:
            for (int i = 0; i < count; i++) {
                PyType[] sequence = sequences.get(i);
                if (remain[i] >= sequence.length) {
                    exhausted++;
                    continue;
                }
                PyType candidate = sequence[remain[i]];
                for (int j = 0; j < count; j++) {
                    if (tailContains(sequences.get(j), remain[j], candidate)) {
                        // Someone still has this class coming after something else, so it cannot go
                        // into the order yet.
                        continue scan;
                    }
                }
                result.add(candidate);
                for (int j = 0; j < count; j++) {
                    PyType[] other = sequences.get(j);
                    if (remain[j] < other.length && other[remain[j]] == candidate) {
                        remain[j]++;
                    }
                }
                continue again;
            }
            if (exhausted != count) {
                throw mroConflict(sequences, remain);
            }
            return;
        }
    }

    /** {@code tail_contains}: whether the class appears after position {@code from} in this sequence. */
    private static boolean tailContains(PyType[] sequence, int from, PyType needle) {
        for (int j = from + 1; j < sequence.length; j++) {
            if (sequence[j] == needle) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code set_mro_error}: name the classes that were each somebody's next candidate.
     *
     * <p>CPython collects them in a dict keyed by the type, which both deduplicates them and keeps the
     * order the bases were declared in; the same is done here with the class as the key, and the names
     * it prints are {@code __name__}s joined as CPython joins them.
     */
    private static PyRaise mroConflict(List<PyType[]> sequences, int[] remain) {
        Map<PyType, Boolean> candidates = new LinkedHashMap<>();
        for (int i = 0; i < sequences.size(); i++) {
            PyType[] sequence = sequences.get(i);
            if (remain[i] < sequence.length) {
                candidates.put(sequence[remain[i]], Boolean.TRUE);
            }
        }
        StringBuilder names = new StringBuilder();
        for (PyType candidate : candidates.keySet()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(candidate.name());
        }
        return Slots.typeError(
                "Cannot create a consistent method resolution order (MRO) for bases %s", names);
    }

    // --- calling a class -------------------------------------------------------------------

    /**
     * {@code Point(1, 2)}: {@code type.__call__} for a class Python wrote.
     *
     * <p>{@code __new__} first, then {@code __init__} — and the second of those only when the first made
     * something that actually is an instance of the class being called. A {@code __new__} that hands
     * back a value of its own is how a class pretends to be something else, and CPython lets it: the
     * object is returned as it came back, without its caller's {@code __init__} ever being asked to
     * initialize it.
     */
    static PyObject construct(PyClass kind, PyObject[] args, PyTuple kwNames) {
        PyObject self = allocate(kind, args, kwNames);
        PyType made = self.getType();
        if (!made.matches(kind)) {
            return self;
        }
        PyObject declared = Specials.lookUp(made, "__init__");
        if (declared == null) {
            return self;
        }
        PyObject[] withReceiver = withSelf(args, self);
        PyObject answer =
                declared instanceof PyFunction init
                        ? init.call(withReceiver, kwNames)
                        : Slots.call(declared, withReceiver, kwNames);
        if (answer != PyNone.INSTANCE) {
            throw Slots.typeError("__init__() should return None, not '%s'", answer.getType().name());
        }
        return self;
    }

    /**
     * {@code tp_new}: the class's own {@code __new__} with the class as its first argument, or
     * {@code object.__new__} when it declares none.
     *
     * <p>The fallback is the one place a constructor refuses arguments before a method has said anything
     * about them, and it refuses only when nothing in the order declares {@code __init__} either —
     * CPython's {@code object_new} checks the init slot for exactly that reason, so that a class with an
     * {@code __init__} of its own gets the arity message that names the method rather than one that
     * names the class.
     */
    private static PyObject allocate(PyClass kind, PyObject[] args, PyTuple kwNames) {
        PyObject declared = Specials.lookUp(kind, "__new__");
        if (declared != null) {
            PyObject[] withClass = withSelf(args, kind);
            return declared instanceof PyFunction creator
                    ? creator.call(withClass, kwNames)
                    : Slots.call(declared, withClass, kwNames);
        }
        if (excess(args, kwNames) && !Specials.defines(kind, "__init__")) {
            throw Slots.typeError("%s() takes no arguments", kind.name());
        }
        return new PyInstance(kind);
    }

    /** Whether the call brought anything at all, which is {@code object}'s test before it makes a value. */
    private static boolean excess(PyObject[] args, PyTuple kwNames) {
        return args.length > 0 || (kwNames != null && kwNames.size() > 0);
    }

    /** {@code self}, then the arguments as the caller gave them — keyword values stay at the tail. */
    private static PyObject[] withSelf(PyObject[] arguments, PyObject receiver) {
        PyObject[] all = new PyObject[arguments.length + 1];
        all[0] = receiver;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return all;
    }
}
