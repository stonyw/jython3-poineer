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
 * reads them. {@link #buildClass} is {@code builtin___build_class__}, {@link #newType} is
 * {@code type_new}, {@link #linearize} is {@code mro_implementation} with its {@code pmerge}, and
 * {@link #typeCall} is {@code type_call}.
 *
 * <p>The metaclass protocol is the interesting half. A class statement names its metatype — by
 * {@code metaclass=}, by the class of its first base, or by the most derived of the bases' own classes —
 * lets that metatype choose the namespace the body writes into with {@code __prepare__}, hands it the
 * leftover keyword arguments as well as the three it always gets, and files the result under that
 * metatype so that {@code C.__class__} and every class-level read go through it. What is left out is the
 * part of {@code type_new} that has no model here: {@code __slots__} and instance layout,
 * {@code __mro_entries__} and {@code __orig_bases__}, subclassing a built-in type whose members are Java
 * slots, and the {@code tp_new} delegation that lets a metatype whose own creator is not {@code type}
 * build the class instead — every metatype this runtime makes is built by {@code type_new}, so there is
 * nothing to delegate to.
 */
final class Classes {

    private Classes() {
    }

    // --- the class statement ---------------------------------------------------------------

    /**
     * {@code __build_class__(func, name, *bases, metaclass=?, **kwds)}: the helper a {@code class}
     * statement calls.
     *
     * <p>Four things about it are easy to get wrong and are copied exactly. The body runs against the
     * namespace as its <em>locals</em> while its global names still come from the module the class is
     * written in, and what the body returns is the {@code __class__} cell rather than the class: the
     * class comes from calling the metaclass on the finished namespace, and the two answers are then
     * compared, which is how a method that stole {@code __class__} is caught. {@code metaclass} is taken
     * out of the keyword arguments before anything is called with them, so a metaclass never sees the
     * word among its {@code **kwds}. The namespace is asked of {@code __prepare__} with two positionals
     * and the leftovers, which is why a {@code __prepare__} written without {@code @classmethod} sees
     * the class name where {@code mcs} belongs. And when no metaclass is named, the one used is
     * {@code type} for a class with no bases and the class of the first base otherwise — that is what
     * makes {@code class C(1)} be refused by {@code int} rather than by the class system.
     */
    static PyObject buildClass(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional < 2) {
            throw Slots.typeError("__build_class__: not enough arguments");
        }
        if (!(args[0] instanceof PyFunction body)) {
            throw Slots.typeError("__build_class__: func must be a function");
        }
        if (!(args[1] instanceof PyStr name)) {
            throw Slots.typeError("__build_class__: name is not a string");
        }
        PyTuple bases = PyTuple.of(Arrays.copyOfRange(args, 2, positional));
        PyObject[] keywords = Arrays.copyOfRange(args, positional, args.length);
        String[] left = kwNames;

        PyObject meta = null;
        boolean isclass = false;
        if (kwNames != null) {
            int at = indexOf(kwNames, "metaclass");
            if (at >= 0) {
                meta = keywords[at];
                // A `metaclass` that is not a class is used exactly as it stands: no order can be
                // calculated from it, and calling it is what builds the class.
                isclass = meta instanceof PyType;
                left = without(kwNames, at);
                keywords = without(keywords, at);
            }
        }
        if (meta == null) {
            meta = bases.size() == 0 ? PyType.TYPE : bases.at(0).getType();
            isclass = true;
        }
        if (isclass) {
            meta = calculateMetaclass((PyType) meta, bases);
        }

        PyObject prepare = optional(meta, "__prepare__");
        PyObject namespace =
                prepare == null
                        ? PyDict.empty()
                        : Slots.call(
                                prepare,
                                joined(new PyObject[] {name, bases}, keywords),
                                PyBoundMethod.keywordTuple(left));
        if (!(namespace instanceof PyDict dict)) {
            throw Slots.typeError(
                    "%s.__prepare__() must return a mapping, not %s",
                    isclass ? ((PyType) meta).name() : "<metaclass>", namespace.getType().name());
        }

        PyObject returned = body.runClassBody(dict);
        PyObject built =
                Slots.call(
                        meta,
                        joined(new PyObject[] {name, bases, dict}, keywords),
                        PyBoundMethod.keywordTuple(left));
        checkClassCell(returned, name, built);
        return built;
    }

    /**
     * {@code _PyType_CalculateMetaclass}: the most derived of the bases' own classes wins.
     *
     * <p>A base whose metaclass is neither the winner nor a subclass of it is the conflict every
     * {@code class C(A, B)} with two unrelated metaclasses reports, and it is reported before any
     * namespace is prepared — which is why such a class never reaches its own body's names.
     */
    private static PyType calculateMetaclass(PyType winner, PyTuple bases) {
        for (int i = 0; i < bases.size(); i++) {
            PyType tmptype = bases.at(i).getType();
            if (winner.matches(tmptype)) {
                continue;
            }
            if (tmptype.matches(winner)) {
                winner = tmptype;
                continue;
            }
            throw Slots.typeError(
                    "metaclass conflict: the metaclass of a derived class"
                            + " must be a (non-strict) subclass of the metaclasses of all its bases");
        }
        return winner;
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
     * {@code type('Point', (), {...})}, reached from {@link #typeCall} and from {@code Constructors}'s
     * three-argument {@code type}.
     */
    static PyObject newType(PyObject[] args, String[] kwNames) {
        return newType(PyType.TYPE, args, kwNames);
    }

    /**
     * {@code type_new}: the three-argument call that makes a class, whatever metatype it is asked to make
     * it for.
     *
     * <p>The refusals come in CPython's order and each names the position it rejected, because a
     * namespace passed where a tuple belongs is the mistake a hand-built class makes most often. The
     * count comes first — {@code type.__new__()} says {@code takes exactly 3 arguments (1 given)} about a
     * call that also has the wrong kind of first argument, because {@code PyArg_ParseTuple} only reaches
     * the argument it cannot use once it has one to look at. An empty base tuple means {@code object} and
     * is written back into {@code __bases__} as such.
     */
    static PyObject newType(PyType metatype, PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional != 3) {
            throw Slots.typeError(
                    "type.__new__() takes exactly 3 arguments (%d given)", positional);
        }
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
            if (base instanceof PyType || optional(base, "__mro_entries__") == null) {
                continue;
            }
            // `type()` cannot ask a base what it stands in for; that resolution is `types.new_class`'s.
            throw Slots.typeError(
                    "type() doesn't support MRO entry resolution; use types.new_class()");
        }
        PyType winner = calculateMetaclass(metatype, bases);
        // CPython hands the class to the winner's own `tp_new` when that is not `type_new`. Every
        // metatype here is built by `type_new`, so there is nothing to hand it to.
        for (int i = 0; i < bases.size(); i++) {
            PyObject base = bases.at(i);
            if (!(base instanceof PyType kind)) {
                throw Slots.typeError("bases must be types");
            }
            if (!canDeriveFrom(kind)) {
                throw Slots.notImplemented("subclassing '" + kind.name() + "'");
            }
        }
        return declare(
                name.getValue(),
                bases,
                namespace,
                winner,
                Arrays.copyOfRange(args, 3, args.length),
                kwNames);
    }

    /**
     * Which types a Python class may take as a base.
     *
     * <p>A {@link PyClass} always, since its members are a dictionary and that is what deriving from
     * another class means. {@code type} is the one built-in on the list, because a metaclass is written
     * exactly that way and a class made by {@link #newType} needs nothing a {@link PyClass} does not
     * have. {@code object} and the exception hierarchy are the two other built-in families whose values
     * this runtime already builds — an exception class carries its args and traceback whatever Python
     * class derives from it — and every other built-in has Java slots no dictionary can stand in for,
     * which is the refusal a {@code class C(str)} still gets.
     */
    private static boolean canDeriveFrom(PyType base) {
        return base instanceof PyClass
                || base == PyType.OBJECT
                || base == PyType.TYPE
                || base.matches(ExceptionTypes.BASE_EXCEPTION);
    }

    /**
     * {@code type_new_impl} once the bases are settled: copy the namespace, give the class its identity
     * names, compute the order it inherits by, tell each member the class it belongs to, then report the
     * new class to its bases.
     *
     * <p>The copy is what makes {@code X.__dict__ is namespace} false, exactly as it is in CPython, and
     * it is why a class built by hand and the namespace it was built from go their separate ways
     * afterwards. The last step is the one a program sees: {@code __init_subclass__} runs with the new
     * class as its only receiver and the class statement's leftover keywords as its own arguments, which
     * is how {@code class Sub(Base, tag=3)} reaches {@code Base}.
     */
    private static PyClass declare(
            String name,
            PyTuple bases,
            PyDict given,
            PyType metatype,
            PyObject[] keywordValues,
            String[] kwNames) {
        PyDict dict = given.copy();

        // `type_new_set_module`: the frame that built the class names its module, unless the body already
        // did — which a class statement's body always does, since the compiler writes `__module__` first.
        if (dict.get(PyStr.interned("__module__")) == null) {
            Frame caller = Interpreter.currentFrame();
            PyObject module =
                    caller == null ? null : caller.globals().get(PyStr.interned("__name__"));
            if (module != null) {
                dict.put(PyStr.interned("__module__"), module);
            }
        }

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
        autoWrap(dict, "__new__", false);
        autoWrap(dict, "__init_subclass__", true);
        autoWrap(dict, "__class_getitem__", true);
        // Defining equality without a hash makes a class unhashable, which CPython expresses by
        // putting None under the name in the declaring class's own dictionary.
        if (dict.get(PyStr.interned("__eq__")) != null
                && dict.get(PyStr.interned("__hash__")) == null) {
            dict.put(PyStr.interned("__hash__"), PyNone.INSTANCE);
        }

        PyClass kind = new PyClass(name, bases, dict, qualified, metatype);
        kind.order = linearize(kind, bases);
        propagateCells(dict, kind);
        setNames(dict, kind);
        initSubclass(kind, keywordValues, kwNames);
        return kind;
    }

    /**
     * {@code type_new_staticmethod} and {@code type_new_classmethod}: a plain function filed under one
     * of these names is not what the class ends up holding.
     *
     * <p>{@code __new__} becomes a static method — a class that makes its own values is not sent
     * {@code self} as an instance, and the class arrives at the call instead — and
     * {@code __init_subclass__} and {@code __class_getitem__} become class methods. {@code __prepare__}
     * is deliberately absent from that list, which is the trap in writing a metaclass in Python: a plain
     * {@code __prepare__} is handed the new class's name where its {@code mcs} belongs, and the
     * {@code TypeError} says so about a method the program never called.
     */
    private static void autoWrap(PyDict dict, String name, boolean classMethod) {
        PyObject member = dict.get(PyStr.interned(name));
        if (!(member instanceof PyFunction function)) {
            return;
        }
        dict.put(
                PyStr.interned(name),
                classMethod ? new PyClassMethod(function) : new PyStaticMethod(function));
    }

    /**
     * {@code type_new_init_subclass}: {@code super(type, type).__init_subclass__(**kwds)}.
     *
     * <p>The proxy is built from the new class on both sides, which is what makes the hook start at the
     * class's <em>bases</em> rather than at itself: a class that defines {@code __init_subclass__} does
     * not get it called about its own definition, only its subclasses do. The keyword arguments are the
     * class statement's leftovers, so {@code class Sub(Base, tag=3)} reaches {@code Base} with
     * {@code tag} and never mentions {@code Sub}.
     */
    private static void initSubclass(PyClass kind, PyObject[] values, String[] kwNames) {
        PyObject hook = Slots.getAttr(PySuper.of(kind, kind), "__init_subclass__");
        Slots.call(hook, values, PyBoundMethod.keywordTuple(kwNames));
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
     * reads a name from the scope around it — or asks for its own dictionary by name — leaves the
     * {@code __classdict__} cell behind. Both are filled here and both names are taken out of the
     * dictionary, which is why neither appears in {@code X.__dict__} of a finished class.
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
     *
     * <p>Package-private rather than private to the class system because {@link PyType#declare(String,
     * PyType...)} has the same question to answer for {@code ExceptionGroup}: a built-in type over two
     * bases is linearized by C3 like any other, not by a rule of its own.
     */
    static PyType[] linearize(PyType kind, PyTuple bases) {
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
     * {@code type_call}, the slot Python runs when it calls a class.
     *
     * <p>{@code type} itself answers the one-argument question before anything is built — {@code type(x)}
     * names the value's class — and refuses a call that is neither one nor three arguments. Both of those
     * belong to {@code type} alone: a metaclass that derives from it takes the three arguments and calls
     * {@code __new__} like any other class, which is what {@code Meta('C', (), {})} means.
     *
     * <p>A built-in receiver has its own {@code tp_new} and {@code tp_init}, and {@code Constructors}
     * dispatches those by identity, so asking it is the same answer for every type this runtime builds.
     */
    static PyObject typeCall(PyType kind, PyObject[] args, PyTuple kwNames) {
        String[] names = PyBoundMethod.keywordNames(kwNames);
        if (kind == PyType.TYPE) {
            int positional = Arguments.positional(args, names);
            if (positional == 1) {
                if (kwNames != null && kwNames.size() > 0) {
                    throw Slots.typeError("type() takes no keyword arguments");
                }
                return args[0].getType();
            }
            if (positional != 3) {
                throw Slots.typeError("type() takes 1 or 3 arguments");
            }
            PyObject built = newType(PyType.TYPE, args, names);
            typeInit(args, names);
            return built;
        }
        if (kind instanceof PyClass declaring) {
            return construct(declaring, args, kwNames);
        }
        if (kind.declaresNoInstances()) {
            // CPython's `type_call` looks at the slot before it looks at the arguments, so a named
            // sequence is refused for the same reason however many items are handed to it.
            throw Slots.typeError("cannot create '%s' instances", kind.filedName());
        }
        return Constructors.instantiate(kind, args, kwNames);
    }

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
     * {@code tp_new}: the class's own {@code __new__} with the class as its first argument,
     * {@code type_new} for a metaclass that declares none, {@code BaseException_new} for an exception
     * subclass, and {@code object.__new__} otherwise.
     *
     * <p>The middle two cases are what the two families inherit instead of declaring: a metaclass takes
     * the three arguments and makes a class rather than a value of {@code Meta}, so the class it hands
     * back is the instance and its own class is {@code Meta}; an exception subclass takes any number of
     * arguments and keeps them, which is why {@code Bare("x")} is a value and {@code Bare("x") == 1} is
     * not an arity complaint about a class that declares nothing. The last case is the one place a
     * constructor refuses arguments before a method has said anything about them, and it refuses only
     * when nothing in the order declares {@code __init__} either — CPython's {@code object_new} checks
     * the init slot for exactly that reason, so that a class with an {@code __init__} of its own gets the
     * arity message that names the method rather than one that names the class.
     */
    private static PyObject allocate(PyClass kind, PyObject[] args, PyTuple kwNames) {
        PyObject declared = Specials.lookUp(kind, "__new__");
        if (declared != null) {
            PyObject[] withClass = withSelf(args, kind);
            return declared instanceof PyFunction creator
                    ? creator.call(withClass, kwNames)
                    : Slots.call(declared, withClass, kwNames);
        }
        String[] names = PyBoundMethod.keywordNames(kwNames);
        if (kind.matches(PyType.TYPE)) {
            PyObject built = newType(kind, args, names);
            typeInit(args, names);
            return built;
        }
        if (kind.matches(ExceptionTypes.BASE_EXCEPTION_GROUP)) {
            // A class a person wrote under a group inherits the group's creator, which takes exactly the
            // two arguments — and is never promoted: `Named("m", [ValueError()])` stays a `Named`, since
            // only the two built-in classes are ever swapped for the narrower one.
            return PyBaseExceptionGroup.create(
                    kind,
                    Arguments.positionals(args, names),
                    kwNames != null && kwNames.size() > 0);
        }
        if (kind.matches(ExceptionTypes.BASE_EXCEPTION)) {
            // CPython's exception subclasses inherit `BaseException`'s creator rather than `object`'s,
            // which keeps the arguments on the value: `Bare("x").args` is `("x",)` even when the class
            // declares no `__init__` at all, and that is what makes a written exception raisable.
            if (kwNames != null && kwNames.size() > 0) {
                throw Slots.typeError("%s() takes no keyword arguments", kind.name());
            }
            return new PyBaseException(kind, args);
        }
        if (excess(args, kwNames) && !Specials.defines(kind, "__init__")) {
            throw Slots.typeError("%s() takes no arguments", kind.name());
        }
        return new PyInstance(kind);
    }

    /** {@code type_init}: the two things a class built by a metatype call is checked for afterwards. */
    static void typeInit(PyObject[] args, String[] kwNames) {
        int given = Arguments.positional(args, kwNames);
        boolean keywords = kwNames != null && kwNames.length > 0;
        if (keywords && given == 1) {
            throw Slots.typeError("type.__init__() takes no keyword arguments");
        }
        if (given != 1 && given != 3) {
            throw Slots.typeError("type.__init__() takes 1 or 3 arguments");
        }
    }

    /**
     * {@code object_isinstance}: the test {@code isinstance} makes once no {@code __instancecheck__} has
     * answered, and the one {@code type.__instancecheck__} always makes.
     *
     * <p>It starts at the value's class rather than at the value, which is why {@code isinstance(x, type)}
     * is false for anything that is not a class even though every class is an <em>instance</em> of
     * {@code type}. A value that answers {@code __class__} with a different class is taken at its word —
     * that is how a proxy gets to pass for the thing it stands in for.
     *
     * <p>The first question is put to the required type instead of being answered from the candidate's
     * parent chain, because a Java class has interfaces and several supertypes and no single parent
     * pointer reaches them; {@link PyType#instanceOf} is where that answer lives.
     */
    static boolean plainInstanceOf(PyType kind, PyObject value) {
        if (kind.instanceOf(value)) {
            return true;
        }
        PyObject claimed = optional(value, "__class__");
        return claimed instanceof PyType icls && icls != value.getType() && icls.matches(kind);
    }

    /**
     * {@code recursive_issubclass}: the walk {@code issubclass} falls back to, and the whole of what
     * {@code type.__subclasscheck__} does.
     *
     * <p>CPython reaches this only when both arguments are classes, or complains about whichever one is
     * not. A value that merely looks like a class — anything with a {@code __bases__}, which
     * {@code check_class} accepts — is not modelled, and is refused as the first argument rather than
     * walked.
     */
    static boolean plainSubclass(PyType kind, PyObject sub) {
        if (!(sub instanceof PyType derived)) {
            throw Slots.typeError("issubclass() arg 1 must be a class");
        }
        return derived.matches(kind);
    }

    // --- isinstance and issubclass ---------------------------------------------------------

    /**
     * {@code object_recursive_isinstance}: the whole of what {@code isinstance(x, C)} asks.
     *
     * <p>The order is the one CPython keeps and each step answers a different question. A value whose own
     * class <em>is</em> the required class matches before anything is asked, which is why a metaclass
     * {@code __instancecheck__} that refuses {@code C()} still sees {@code isinstance(C(), C)} as true.
     * {@code type} needs no hook because its answer is known. A tuple is scanned in order and its first
     * match wins, so {@code isinstance(x, (int, 1))} is true for an {@code int} and only complains about
     * the {@code 1} when nothing matched. Then, and only then, comes the hook — read from the
     * <em>metatype's</em> members and called with the value as its one argument, which is why
     * {@code __instancecheck__} written in the class's own body is never run: {@code C.__instancecheck__}
     * is a member of {@code C}, not of {@code type}, and a class answering for its own instances is not
     * what this protocol is.
     */
    static boolean instanceOf(PyObject value, PyObject classes) {
        if (value.getType() == classes) {
            return true;
        }
        if (classes == PyType.TYPE) {
            return plainInstanceOf(PyType.TYPE, value);
        }
        if (classes instanceof PyTuple tuple) {
            for (int i = 0; i < tuple.size(); i++) {
                if (instanceOf(value, tuple.at(i))) {
                    return true;
                }
            }
            return false;
        }
        PyObject checker = hook(classes, "__instancecheck__");
        if (checker != null) {
            return Slots.truth(Slots.call(checker, new PyObject[] {value}, null));
        }
        if (!(classes instanceof PyType kind)) {
            throw Slots.typeError(
                    "isinstance() arg 2 must be a type, a tuple of types, or a union");
        }
        return plainInstanceOf(kind, value);
    }

    /**
     * {@code object_issubclass}: the whole of what {@code issubclass(A, B)} asks.
     *
     * <p>Same shape as {@link #instanceOf}, with the same three shortcuts and the same hook read from
     * {@code B}'s metatype — and with the argument complaints of its own, since a class test can be
     * refused on either side. A tuple is scanned in order and its first match wins, which is why
     * {@code issubclass(int, (int, 1))} is true: the {@code 1} is only complained about when nothing
     * matched.
     */
    static boolean subclassOf(PyObject derived, PyObject classes) {
        if (classes == PyType.TYPE) {
            return derived == classes || plainSubclass(PyType.TYPE, derived);
        }
        if (classes instanceof PyTuple tuple) {
            for (int i = 0; i < tuple.size(); i++) {
                if (subclassOf(derived, tuple.at(i))) {
                    return true;
                }
            }
            return false;
        }
        PyObject checker = hook(classes, "__subclasscheck__");
        if (checker != null) {
            return Slots.truth(Slots.call(checker, new PyObject[] {derived}, null));
        }
        if (!(classes instanceof PyType kind)) {
            if (!(derived instanceof PyType)) {
                throw Slots.typeError("issubclass() arg 1 must be a class");
            }
            throw Slots.typeError(
                    "issubclass() arg 2 must be a class, a tuple of classes, or a union");
        }
        return plainSubclass(kind, derived);
    }

    /**
     * {@code _PyObject_LookupSpecial}: the member {@code target}'s own metatype holds under {@code name},
     * bound to {@code target} and ready to call.
     *
     * <p>Both {@code None} and no member at all mean "no hook", which is how {@code __hash__ = None}
     * stops a class from being hashed and, here, how a class that wrote {@code __instancecheck__ = None}
     * is left with the ordinary test. The receiver is the class rather than its metatype, so a metaclass
     * that defines the hook sees the class {@code isinstance} was asked about as its {@code cls}.
     */
    private static PyObject hook(PyObject target, String name) {
        PyType metatype = target.getType();
        PyObject member = Specials.member(metatype, name);
        if (member == null || member == PyNone.INSTANCE) {
            return null;
        }
        return Descriptors.get(member, target, metatype);
    }

    // --- the calling convention ------------------------------------------------------------

    /** {@code PyObject_GetOptionalAttr}: null for a value with nothing under the name. */
    private static PyObject optional(PyObject target, String name) {
        return Slots.findAttr(target, name);
    }

    /** The position of {@code name} in a call's keyword names, or -1 when the call did not give it. */
    private static int indexOf(String[] kwNames, String name) {
        for (int i = 0; i < kwNames.length; i++) {
            if (kwNames[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code positionals} then the keyword values, which is the layout every callee reads. */
    private static PyObject[] joined(PyObject[] positionals, PyObject[] keywords) {
        PyObject[] all = new PyObject[positionals.length + keywords.length];
        System.arraycopy(positionals, 0, all, 0, positionals.length);
        System.arraycopy(keywords, 0, all, positionals.length, keywords.length);
        return all;
    }

    private static String[] without(String[] values, int at) {
        String[] rest = new String[values.length - 1];
        System.arraycopy(values, 0, rest, 0, at);
        System.arraycopy(values, at + 1, rest, at, values.length - at - 1);
        return rest;
    }

    private static PyObject[] without(PyObject[] values, int at) {
        PyObject[] rest = new PyObject[values.length - 1];
        System.arraycopy(values, 0, rest, 0, at);
        System.arraycopy(values, at + 1, rest, at, values.length - at - 1);
        return rest;
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
