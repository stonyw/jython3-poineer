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
 * metatype so that {@code C.__class__} and every class-level read go through it. When that metatype is
 * not {@code type} but wins the derivation, {@code type_new}'s last word is a call to the winner's own
 * {@code __new__} — that is how {@code EnumType} receives {@code boundary} and {@code _simple}. What is
 * left out is {@code __mro_entries__} and {@code __orig_bases__}, and the {@code tp_basicsize} of a
 * compiled-in base other than {@code object} — every built-in root is taken to be sixteen bytes wide,
 * which is the named deviation {@link #solidBase} carries.
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
        PyTuple origBases = PyTuple.of(Arrays.copyOfRange(args, 2, positional));
        PyTuple bases = updateBases(origBases);
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
        // A dict subclass is a mapping as far as the class statement is concerned: its entries live in a
        // dictionary of its own, and the body's writes go through the mapping itself so that an override
        // of __setitem__ sees every name.
        PyDict dict = PyDict.bodyOf(namespace);
        if (dict == null) {
            throw Slots.typeError(
                    "%s.__prepare__() must return a mapping, not %s",
                    isclass ? ((PyType) meta).name() : "<metaclass>", namespace.getType().name());
        }

        PyObject returned = body.runClassBody(dict, namespace);
        if (bases != origBases) {
            // Kept when a base list was rewritten, which is how `class C(List[int])` still tells
            // `__init_subclass__` and typing what was written. The write goes through the mapping,
            // because a namespace with a `__setitem__` override has to see it.
            Slots.setItem(namespace, PyStr.interned("__orig_bases__"), origBases);
        }
        PyObject built =
                Slots.call(
                        meta,
                        joined(new PyObject[] {name, bases, namespace}, keywords),
                        PyBoundMethod.keywordTuple(left));
        checkClassCell(returned, name, built);
        return built;
    }

    /**
     * {@code update_bases}: a base list whose non-class entries may be replaced by their
     * {@code __mro_entries__}.
     *
     * <p>{@code List[int]} is an object, not a class, and the class it stands for is what the MRO is
     * calculated from — so a base that files {@code __mro_entries__} is asked for a tuple of real
     * bases, and the tuple it answers is spliced in place. A base that files nothing is left alone,
     * and when nothing was replaced the original tuple is returned, because the caller keeps that
     * identity as its signal not to file {@code __orig_bases__}.
     */
    private static PyTuple updateBases(PyTuple bases) {
        List<PyObject> replaced = null;
        for (int i = 0; i < bases.size(); i++) {
            PyObject base = bases.at(i);
            if (base instanceof PyType) {
                if (replaced != null) {
                    replaced.add(base);
                }
                continue;
            }
            PyObject method = optional(base, "__mro_entries__");
            if (method == null) {
                if (replaced != null) {
                    replaced.add(base);
                }
                continue;
            }
            PyObject entries = Slots.call(method, new PyObject[] {bases}, PyTuple.EMPTY);
            if (!(entries instanceof PyTuple tuple)) {
                throw Slots.typeError("__mro_entries__ must return a tuple");
            }
            if (replaced == null) {
                replaced = new ArrayList<>(i + tuple.size());
                for (int j = 0; j < i; j++) {
                    replaced.add(bases.at(j));
                }
            }
            for (int j = 0; j < tuple.size(); j++) {
                replaced.add(tuple.at(j));
            }
        }
        return replaced == null ? bases : PyTuple.of(replaced.toArray(new PyObject[0]));
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
        // A dict subclass is accepted here as CPython accepts it: the check is PyDict_Check, not
        // PyDict_CheckExact, so an EnumDict built by the class statement reaches this call.
        PyDict namespace = PyDict.bodyOf(args[2]);
        if (namespace == null) {
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
        // CPython hands the class to the winner's own `tp_new` when that is not `type_new`. A metatype
        // written in Python declares `__new__`, and `enum`'s `EnumType.__new__` is the one the standard
        // library needs here: `type('Color', (IntEnum,), body, boundary=..., _simple=True)` has to let
        // that method consume its keywords rather than let `object.__init_subclass__` refuse them.
        if (winner != metatype) {
            PyObject delegated = Specials.lookUp(winner, "__new__");
            if (delegated != null) {
                PyObject[] withMetatype = withSelf(args, winner);
                return delegated instanceof PyFunction creator
                        ? creator.call(withMetatype, PyBoundMethod.keywordTuple(kwNames))
                        : Slots.call(delegated, withMetatype, PyBoundMethod.keywordTuple(kwNames));
            }
        }
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
     * class derives from it — and every other built-in has Java slots no dictionary can stand in for.
     *
     * <p>The three descriptor wrappers are the exception on that last point: they have Java slots, but
     * {@link #allocateWrapper} can make the struct with the subclass as its type, so a class written over
     * one of them keeps the behaviour its members read. They are here because the standard library writes
     * exactly that — {@code abc}'s deprecated {@code abstractclassmethod}, {@code abstractstaticmethod} and
     * {@code abstractproperty} — and their absence kept {@code import abc} from getting past line 28.
     *
     * <p>{@code int} and {@code str} are the fourth kind: the two immutable values whose storage is a
     * single field, so a subclass is the same value with a dictionary beside it. {@link #allocate} gives
     * them {@code PyIntSubclass} and {@code PyStrSubclass}, and {@code enum} needs both — its
     * {@code IntEnum} and {@code StrEnum} are written exactly as {@code class IntEnum(int, ReprEnum)}.
     *
     * <p>Package-private because {@link #setBases} asks the same question: CPython's {@code best_base}
     * refuses a base without {@code Py_TPFLAGS_BASETYPE} with {@code type 'X' is not an acceptable base
     * type}, which is the refusal behind {@code X.__bases__ = (type(None),)}.
     */
    static boolean canDeriveFrom(PyType base) {
        return base instanceof PyClass
                || base == PyType.OBJECT
                || base == PyType.TYPE
                || base == PyType.DICT
                // `traceback.StackSummary` is `class StackSummary(list)`, so a list subclass has to be a
                // Python object with a list beside it — the same split `PyDictInstance` makes.
                || base == PyType.LIST
                || base == PyType.INT
                || base == PyType.STR
                || base == PyType.TUPLE
                || base.matches(ExceptionTypes.BASE_EXCEPTION)
                || base == PyType.CLASS_METHOD
                || base == PyType.STATIC_METHOD
                || base == PyType.PROPERTY
                // `types.GenericAlias` is a base since 3.11, which `_collections_abc` uses for the
                // `__class_getitem__` of the callable ABCs.
                || base == PyType.GENERIC_ALIAS
                // `weakref.ref` is a base the standard library writes: `weakref.WeakMethod` is a
                // `class WeakMethod(ref)` whose slots and methods a Python class body supplies, and
                // the reference's own storage is what `allocate` keeps beside the dictionary.
                || base == PyType.WEAK_REFERENCE
                // `io.py` is written over the four `_io` bases and over nothing else: `IOBase`,
                // `RawIOBase`, `BufferedIOBase` and `TextIOBase` are the ABCs the module declares, and
                // every concrete stream is one of the Java values the module only registers with them.
                || base == PyType.IO_BASE
                || base == PyType.TEXT_IO_BASE
                || base == PyType.RAW_IO_BASE
                || base == PyType.BUFFERED_IO_BASE
                // `random.py` is `class Random(_random.Random)` and asks `super().seed(a)` before any
                // generator exists, so a program-written class has to be able to sit over this one and
                // keep the generator beside its own dictionary.
                || base == PyType.RANDOM
                // `typing.py` builds `Generic` subclasses itself: `Protocol` is `class Protocol(Generic,
                // metaclass=_ProtocolMeta)`, and every user class written in the type-parameter syntax
                // lands here too.
                || base == PyType.TYPING_GENERIC;
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
    static PyClass declare(
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
        // `type_dict_set_doc` runs after the two slot descriptors are filed, so a class that says nothing
        // about itself ends its dictionary with `__doc__` while one with a docstring has it third.
        PyStr docKey = PyStr.interned("__doc__");
        boolean docWritten = dict.get(docKey) != null;
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
        // `type_new_impl` files the sentence here rather than leaving it in the dictionary: a `__doc__` the
        // body wrote as a string becomes the type's own text, and any other value — `None` above all, which
        // is what a class with no docstring ends with — leaves the field empty. Nothing writes it again, so
        // the table read off a class is cut from the text this build saw, whatever `__doc__` holds later.
        if (dict.get(docKey) instanceof PyStr stated) {
            kind.documented(stated.getValue());
        }
        kind.order = linearize(kind, bases);
        // `type_new_set_classcell` runs before the members are filed, so a class that names one of the
        // two cells in `__slots__` ends with the member descriptor under that name rather than the cell:
        // the cell is filled and taken out of the dictionary first.
        propagateCells(dict, kind);
        addSlotDescriptors(kind, dict);
        // A field the body declared under the name `__doc__` is already a member descriptor here, and it
        // is what the name must keep: `_SpecialForm` stores its own sentence through that slot, and the
        // default the build files below would otherwise make it read-only.
        if (!docWritten) {
            putDefault(dict, "__doc__", PyNone.INSTANCE);
        }
        setNames(dict, kind);
        initSubclass(kind, keywordValues, kwNames);
        return kind;
    }

    /**
     * {@code collect_slots}, {@code type_new_descriptors} and {@code type_add_getset}: the names a class
     * files for the storage its values keep, and the fields its {@code __slots__} declares.
     *
     * <p>The fields come first, because {@code PyType_Ready} walks {@code tp_members} before {@code
     * tp_getset}: {@code class DictSlot: __slots__ = ("__dict__", "b")} ends with {@code b} and then
     * {@code __dict__} beside it, and each field is a {@code member_descriptor} that reads and writes the
     * value's own storage for slots. A metatype is the one class whose values keep nothing of the kind —
     * a class writes every name into its own dictionary — so the fields it declares are not filed, which
     * is the same deviation that makes {@code K.a = 1} land in {@code vars(K)}.
     *
     * <p>Which halves of the storage a class introduces is a question about its base, not about its body:
     * CPython gives a dictionary and a weak reference list to a class whose primary base has neither, and
     * files a {@code getset_descriptor} for each under exactly those two names. A class under one that
     * already has them writes neither name — {@code vars(Sub)} has no {@code __dict__} although {@code
     * Sub().x = 1} works — because the entry belongs to the class that introduced the storage and the
     * lookup walks up to it.
     *
     * <p>{@code __slots__} also changes which storages are taken: a body that declares slots takes only
     * the two names it writes down there, so {@code __slots__ = ()} files neither and {@code __slots__ =
     * ("__dict__",)} files one. Each of those two is filed as {@code PyDict_SetDefaultRef} does it, so a
     * body that wrote one of them keeps what it wrote and the class simply has no descriptor to answer
     * with. The declared fields themselves also give the class its shape — one pointer each, recorded
     * through {@link PyClass#layout} — which is what {@code __bases__} compares.
     */
    private static void addSlotDescriptors(PyClass kind, PyDict dict) {
        PyType base = kind.parent();
        PyObject slots = dict.get(PyStr.interned("__slots__"));
        Declared declared = collect(slots, kind.name());
        // `type_new_set_attrs`: the size grows by one pointer per declared field, and by nothing for the
        // two storages that live outside the value. A compiled-in root stands for `object`'s own 16.
        int baseSize = size(base);
        kind.layout(baseSize + 8 * declared.layout().size(), slots == null ? null : declared.layout());
        if (!base.matches(PyType.TYPE)) {
            for (String field : declared.fields()) {
                // `collect_slots` asks the class dictionary before it lays out a single offset, so a body
                // that wrote the name as well as declaring it never reaches the descriptor at all.
                if (dict.get(PyStr.interned(field)) != null) {
                    throw Slots.valueError("'%s' in __slots__ conflicts with class variable", field);
                }
                dict.put(
                        PyStr.interned(field),
                        new PyMemberDescriptor(
                                kind,
                                field,
                                null,
                                self -> self.slotValue(kind, field),
                                (self, value) -> self.writeSlot(kind, field, value)));
            }
        }
        boolean primaryHasDict = base.hasValueDict();
        boolean primaryHasWeak = base.hasWeakRefList();
        boolean dictSlot = takesStorage(slots, declared.dict(), primaryHasDict, "__dict__");
        boolean weakSlot = takesStorage(slots, declared.weak(), primaryHasWeak, "__weakref__");
        // `type_new_slots_bases`: a class over two bases takes either storage when a secondary base has
        // it, which is what makes `class C(A, B)` with `A` slotted and `B` plain carry both — the new
        // class files the descriptor its primary base lacks, and the storage is the one `B`'s own values
        // use. Only the storages the primary base left open are looked at this way.
        PyType[] allBases = kind.bases();
        if (allBases.length > 1
                && ((!primaryHasDict && !dictSlot) || (!primaryHasWeak && !weakSlot))) {
            for (PyType other : allBases) {
                if (other == base) {
                    continue;
                }
                if (!primaryHasDict && !dictSlot && other.hasValueDict()) {
                    dictSlot = true;
                }
                if (!primaryHasWeak && !weakSlot && other.hasWeakRefList()) {
                    weakSlot = true;
                }
            }
        }
        kind.storesValueDict(dictSlot || primaryHasDict);
        kind.storesWeakRefs(weakSlot || primaryHasWeak);
        if (dictSlot) {
            putDefault(
                    dict,
                    "__dict__",
                    new PyGetSetDescriptor(
                            kind,
                            "__dict__",
                            VALUE_DICT_DOC,
                            PyInstance::valueDict,
                            PyInstance::writeDict));
        }
        if (weakSlot) {
            // The getter answers None forever: this runtime has no `weakref` module, so there is no list
            // for it to read. The setter is absent exactly as CPython's is, which is what makes the name a
            // data descriptor that refuses both writing and deleting.
            putDefault(
                    dict,
                    "__weakref__",
                    new PyGetSetDescriptor(
                            kind, "__weakref__", WEAKREF_LIST_DOC, self -> PyNone.INSTANCE, null));
        }
    }

    /**
     * What a body's {@code __slots__} declares: the fields it names, which storages it asks for, and the
     * shape those fields give the values.
     *
     * <p>{@code fields} is what descriptors are filed for, so a name written twice appears once — the
     * second descriptor would overwrite the first. {@code layout} is CPython's {@code ht_slots}, where a
     * repeated name keeps its second pointer, and it is sorted the way CPython sorts the tuple before
     * laying out offsets, so two classes that name the same fields in a different order compare equal.
     */
    private record Declared(
            List<String> fields, boolean dict, boolean weak, List<String> layout) {}

    /**
     * {@code collect_slots}: the names read out of {@code __slots__}, sorted, with the duplicates and the
     * two storage names set apart from the fields.
     *
     * <p>Three shapes are legal — a bare string is the one field it names, a dictionary declares its keys,
     * and anything else is iterated — and only a string is an item, which is the difference between {@code
     * __slots__ items must be strings, not 'int'} for one item and the {@code 'int' object is not
     * iterable} a bare number reaches before any item is looked at. {@code __slots__} itself stays in the
     * class dictionary beside the descriptors it produced, which is what makes {@code C.__slots__} a
     * question about the body rather than about the layout.
     *
     * <p>A field written with two leading underscores and no trailing pair is mangled against the class
     * name before anything is filed, exactly as the body's own {@code self.__a} was, so the descriptor and
     * the access are spelled the same way. {@code __slots__} itself keeps the names the body wrote.
     */
    private static Declared collect(PyObject slots, String className) {
        if (slots == null) {
            return new Declared(List.of(), false, false, List.of());
        }
        List<PyObject> items =
                slots instanceof PyStr only ? List.of(only) : Constructors.materialise(slots);
        List<String> fields = new ArrayList<>();
        List<String> layout = new ArrayList<>();
        boolean dict = false;
        boolean weak = false;
        for (PyObject item : items) {
            if (!(item instanceof PyStr name)) {
                throw Slots.typeError(
                        "__slots__ items must be strings, not '%s'", item.getType().filedName());
            }
            String field = mangle(name.str(), className);
            if (!PyStr.isIdentifier(field)) {
                throw Slots.typeError("__slots__ must be identifiers");
            }
            switch (field) {
                case "__dict__" -> dict = declares(dict, "__dict__");
                case "__weakref__" -> weak = declares(weak, "__weakref__");
                default -> {
                    layout.add(field);
                    if (!fields.contains(field)) {
                        fields.add(field);
                    }
                }
            }
        }
        // `collect_slots` sorts what it collected before any offset is laid out, so the shape two classes
        // are compared by does not depend on the order their bodies wrote the names in.
        layout.sort(null);
        fields.sort(null);
        return new Declared(fields, dict, weak, layout);
    }

    /**
     * {@code _Py_Mangle}: the name a private attribute is filed under.
     *
     * <p>Only a name with two leading underscores and no trailing pair is private, and the class part
     * loses its own leading underscores — {@code ___E} mangles as {@code _E}. A class name made only of
     * underscores has nothing to mangle with and leaves the name alone.
     */
    private static String mangle(String name, String className) {
        if (name.length() < 2
                || name.charAt(0) != '_'
                || name.charAt(1) != '_'
                || name.endsWith("__")) {
            return name;
        }
        int at = 0;
        while (at < className.length() && className.charAt(at) == '_') {
            at++;
        }
        if (at == className.length()) {
            return name;
        }
        return "_" + className.substring(at) + name;
    }

    /** The second time a body writes one of the two storage names is the time it is refused. */
    private static boolean declares(boolean alreadyDeclared, String name) {
        if (alreadyDeclared) {
            throw Slots.typeError("%s slot disallowed: we already got one", name);
        }
        return true;
    }

    /**
     * Whether a class takes one of the two storages, and what it says when it cannot take that one.
     *
     * <p>A body with no {@code __slots__} takes every storage its base lacks; a body that declares slots
     * takes a storage only where it wrote that storage's own name among them — {@code __slots__ = ()} is a
     * declared empty set of slots rather than no declaration. Writing the name where the base already has
     * the storage is not a silent no-op but the refusal {@code collect_slots} makes before any part of the
     * class is built, in the one sentence it shares with writing the name twice.
     */
    private static boolean takesStorage(
            PyObject slots, boolean declared, boolean baseHas, String name) {
        if (declared && baseHas) {
            throw Slots.typeError("%s slot disallowed: we already got one", name);
        }
        return !baseHas && (slots == null || declared);
    }

    /** {@code PyDict_SetDefaultRef}: a name the class body already wrote is left alone. */
    private static void putDefault(PyDict dict, String name, PyObject descriptor) {
        PyStr key = PyStr.interned(name);
        if (dict.get(key) == null) {
            dict.put(key, descriptor);
        }
    }

    /** The two sentences {@code subtype_getsets} files beside the two descriptors. */
    private static final String VALUE_DICT_DOC = "dictionary for instance variables";

    private static final String WEAKREF_LIST_DOC = "list of weak references to the object";

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
            // A namespace built by hand can hold a key that is not a name -- `type("C", (), {1: 2})`
            // is the test that does. CPython keeps the pair in the dictionary and warns; nothing in
            // this runtime can warn yet, so the pair is kept and only passed over here: a key that
            // is not a string cannot be the name of a member.
            if (names[i] instanceof PyStr name) {
                Descriptors.setName(members[i], kind, name.str());
            }
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

    // --- type_set_bases ---------------------------------------------------------------------

    /**
     * {@code type_set_bases}: {@code C.__bases__ = (...)}, with every refusal CPython makes first.
     *
     * <p>The checks run in the order {@code type_set_bases_unlocked} runs them, because a program can
     * see which one answered: the tuple's shape, then each item's type, then the inheritance cycle, then
     * {@link #bestBase} and the layout comparison against the base the class has now. Only after all of
     * them does the tuple replace the old one, and only then is the order recomputed — for this class and
     * every class below it, since their linearizations name it.
     *
     * <p>A failure while recomputing puts every order back in reverse, and puts this class's bases and
     * parent back too unless a reentrant assignment replaced them meanwhile. That is CPython's {@code
     * undo}/{@code bail}, and it is what makes {@code test_mutable_bases_with_failing_mro} leave {@code
     * D} deriving from what it derived from before the custom {@code mro} raised.
     *
     * <p>The layout comparison is CPython's {@code shape_differs} over {@link PyClass#instanceSize} and
     * {@code same_slots_added} over {@link PyClass#slotLayout}: a class that declared fields has a
     * different shape from one that did not, even when its base is the same. A Python value is still one
     * {@link PyInstance} whatever its class declares, so those sizes are a model rather than an
     * allocation, and a compiled-in base contributes {@code object}'s sixteen bytes whichever one it is.
     */
    static void setBases(PyClass type, PyTuple newBases) {
        if (newBases.size() == 0) {
            throw Slots.typeError(
                    "can only assign non-empty tuple to %s.__bases__, not ()", type.filedName());
        }
        for (int i = 0; i < newBases.size(); i++) {
            PyObject item = newBases.at(i);
            if (!(item instanceof PyType candidate)) {
                throw Slots.typeError(
                        "%s.__bases__ must be tuple of classes, not '%s'",
                        type.filedName(), item.getType().name());
            }
            // Two ways to close a loop: the new base's own order already names this class, or a
            // reentrant assignment has already pointed its parent chain back here.
            if (contains(candidate.mro(), type) || parentChainContains(candidate, type)) {
                throw Slots.typeError("a __bases__ item causes an inheritance cycle");
            }
        }

        PyType newBase = bestBase(newBases);
        compatibleForAssignment(type.parent(), newBase, "__bases__");

        PyTuple oldBases = type.baseTuple();
        PyType oldBase = type.parent();
        type.adoptBases(newBases);
        type.setParent(newBase);

        List<ReplacedOrder> saved = new ArrayList<>();
        try {
            installHierarchy(type, saved);
        } catch (RuntimeException failure) {
            undoOrders(saved);
            // `bail`: a reentrant assignment may already own the tuple, in which case it is not ours
            // to put back.
            if (type.baseTuple() == newBases) {
                type.adoptBases(oldBases);
                type.setParent(oldBase);
            }
            throw failure;
        }
    }

    /**
     * {@code best_base}: the one base whose storage every value of the new class has to fit.
     *
     * <p>The winner is the most derived solid base, and the answer is the <em>declared</em> base that
     * contributed it rather than the solid one — which is why {@code D.__bases__ = (C, list)} names
     * {@code list} in its error even though {@code list} is its own solid base.
     */
    private static PyType bestBase(PyTuple bases) {
        PyType winner = null;
        PyType base = null;
        for (int i = 0; i < bases.size(); i++) {
            PyType candidateBase = (PyType) bases.at(i);
            if (!canDeriveFrom(candidateBase)) {
                throw Slots.typeError(
                        "type '%s' is not an acceptable base type", candidateBase.filedName());
            }
            PyType candidate = solidBase(candidateBase);
            if (winner == null) {
                winner = candidate;
                base = candidateBase;
            } else if (contains(winner.mro(), candidate)) {
                // The winner already derives from this candidate's storage.
            } else if (contains(candidate.mro(), winner)) {
                winner = candidate;
                base = candidateBase;
            } else {
                throw Slots.typeError("multiple bases have instance lay-out conflict");
            }
        }
        return base;
    }

    /**
     * {@code solid_base}: the nearest ancestor whose instance shape this type keeps.
     *
     * <p>CPython walks up while {@code shape_differs} is false for {@code tp_basicsize}/{@code
     * tp_itemsize} and returns the first ancestor that differs. Here that is {@link PyClass#instanceSize}
     * alone, since every value is one {@link PyInstance} and no class has items. Every built-in root is
     * sixteen bytes wide in that model — the named deviation when the real {@code tp_basicsize} differs —
     * and that is still enough to keep {@code L(list)} and {@code object} apart, because {@code list} is
     * its own solid base either way.
     */
    private static PyType solidBase(PyType type) {
        if (!(type instanceof PyClass held)) {
            return type;
        }
        PyType parent = held.parent();
        if (parent == null) {
            return type;
        }
        PyType base = solidBase(parent);
        return held.instanceSize() == size(base) ? base : type;
    }

    /** CPython's {@code tp_basicsize} as this runtime models it: sixteen for a compiled-in root. */
    private static int size(PyType type) {
        return type instanceof PyClass held ? held.instanceSize() : 16;
    }

    /**
     * {@code compatible_with_tp_base}: whether this class keeps its parent's storage and allocator.
     *
     * <p>CPython compares sizes, both offsets, the GC flag and {@code tp_dealloc}. Of those, this runtime
     * can differ on the size and on whether the dictionary or weak-reference storage moved into the
     * preheader, so those three are compared and the rest are constants: a class over a built-in base
     * inherits that base's allocator and can never be compatible with it, which is what the "parent is
     * itself written in Python" test says.
     */
    private static boolean compatibleWithBase(PyType type) {
        if (!(type instanceof PyClass held)) {
            return false;
        }
        PyType parent = held.parent();
        return parent instanceof PyClass written
                && held.instanceSize() == written.instanceSize()
                && held.hasValueDict() == parent.hasValueDict()
                && held.hasWeakRefList() == parent.hasWeakRefList();
    }

    /**
     * {@code compatible_for_assignment}: the two refusals {@code __bases__} can make about layout.
     *
     * <p>The deallocator sentence compares the two classes this assignment swaps between, and it comes
     * first: a heap class and a compiled-in one do not free their values the same way. The layout
     * sentence compares what is left after walking each side up its compatible chain, and it names the
     * new base before the old one, as CPython does. After those sizes, CPython makes two more comparisons
     * that it reports with the same sentence: whether the dictionary is inlined into the value and
     * whether the value carries a preheader at all. Both follow from the two storage flags here, and both
     * are checked after the size comparison so that the sentence is raised for the same reason it is in
     * CPython.
     */
    static void compatibleForAssignment(
            PyType oldto, PyType newto, String attribute) {
        if (newto != null && oldto != null && gcAllocated(oldto) != gcAllocated(newto)) {
            throw Slots.typeError(
                    "%s assignment: '%s' deallocator differs from '%s'",
                    attribute, newto.filedName(), oldto.filedName());
        }
        PyType newbase = newto;
        PyType oldbase = oldto;
        while (newbase != null && compatibleWithBase(newbase)) {
            newbase = newbase.parent();
        }
        while (oldbase != null && compatibleWithBase(oldbase)) {
            oldbase = oldbase.parent();
        }
        if (newbase != oldbase
                && (newbase == null
                        || oldbase == null
                        || newbase.parent() != oldbase.parent()
                        || !sameSlotsAdded(newbase, oldbase)
                        || inlinesValues(oldto) != inlinesValues(newto)
                        || hasWeakRefs(oldto) != hasWeakRefs(newto))) {
            throw Slots.typeError(
                    "%s assignment: '%s' object layout differs from '%s'",
                    attribute, newto.filedName(), oldto.filedName());
        }
    }

    /**
     * {@code same_slots_added}: whether two classes add the same fields over the same parent.
     *
     * <p>CPython requires the same {@code tp_base}, both {@code ht_slots} tuples to be equal when either
     * declares any, and the resulting size to fall out of the field count. That is exactly what the
     * sorted {@link PyClass#slotLayout} and the modelled {@code tp_basicsize} say here: a null layout is
     * no declaration and an empty one is {@code __slots__ = ()}, and both add nothing, so they stand for
     * the same zero fields.
     */
    private static boolean sameSlotsAdded(PyType a, PyType b) {
        if (!(a instanceof PyClass first) || !(b instanceof PyClass second)) {
            return false;
        }
        if (first.parent() != second.parent()) {
            return false;
        }
        List<String> declared = first.slotLayout();
        List<String> other = second.slotLayout();
        if (declared != null && other != null && !declared.equals(other)) {
            return false;
        }
        return fields(declared) == fields(other);
    }

    /** How many pointers a layout accounts for, with no declaration counting as none. */
    private static int fields(List<String> layout) {
        return layout == null ? 0 : layout.size();
    }

    /** {@code Py_TPFLAGS_INLINE_VALUES}: a heap class whose dictionary lives in the value. */
    private static boolean inlinesValues(PyType type) {
        return type.hasValueDict();
    }

    /** {@code Py_TPFLAGS_MANAGED_WEAKREF}, the other half of the preheader comparison. */
    private static boolean hasWeakRefs(PyType type) {
        return type.hasWeakRefList();
    }

    /**
     * Whether this type's values are collected by the tracing collector, which is the half of
     * {@code tp_free} a compiled-in type can differ on.
     *
     * <p>{@code object}, {@code int}, {@code str}, {@code float} and {@code bytes} allocate without GC
     * headers and free with {@code PyObject_Del}; every other type here — heap classes included — frees
     * with {@code PyObject_GC_Del}. That pairing is what makes {@code list} and {@code dict} pass the
     * deallocator check and fail on layout instead.
     */
    private static boolean gcAllocated(PyType type) {
        if (type instanceof PyClass) {
            return true;
        }
        return type != PyType.OBJECT
                && type != PyType.INT
                && type != PyType.STR
                && type != PyType.FLOAT
                && type != PyType.BYTES;
    }

    /**
     * {@code mro_hierarchy_for_complete_type}: install the order here, then below.
     *
     * <p>Returns 0 when a custom {@code mro} reentered and replaced the order itself, in which case the
     * caller must not treat the result as its own. CPython records every replaced order so the undo loop
     * can restore it, which is what {@code saved} is for.
     */
    private static void installHierarchy(PyClass kind, List<ReplacedOrder> saved) {
        PyType[] oldOrder = kind.order;
        PyType[] newOrder;
        if (kind.getType() == PyType.TYPE) {
            newOrder = linearize(kind, kind.baseTuple());
            kind.order = newOrder;
        } else {
            // `mro_implementation_unlocked` is what `type.mro` answers, and a custom mro usually calls
            // it through super(). Installing the default order first is what lets that call see the new
            // bases rather than the stale order. The provisional order is put back if the custom mro
            // raises: CPython only assigns tp_mro after mro() returns, so a failing mro leaves the old
            // order in place.
            PyType[] provisional = linearize(kind, kind.baseTuple());
            kind.order = provisional;
            try {
                PyObject invoked = kind.getAttr("mro");
                PyObject result = Slots.call(invoked, new PyObject[0], null);
                if (kind.order != provisional) {
                    // Reentrance: someone else has already installed an order for this class.
                    return;
                }
                newOrder = mroInvoke(kind, result);
                kind.order = newOrder;
            } catch (RuntimeException failure) {
                if (kind.order == provisional) {
                    kind.order = oldOrder;
                }
                throw failure;
            }
        }
        saved.add(new ReplacedOrder(kind, oldOrder, newOrder));

        for (PyType candidate : PyType.children()) {
            if (candidate instanceof PyClass child && contains(child.bases(), kind)) {
                installHierarchy(child, saved);
            }
        }
    }

    /** The undo half of {@code mro_hierarchy_for_complete_type}, newest first. */
    private static void undoOrders(List<ReplacedOrder> saved) {
        for (int i = saved.size() - 1; i >= 0; i--) {
            ReplacedOrder entry = saved.get(i);
            if (entry.type().order == entry.after()) {
                entry.type().order = entry.before();
            }
        }
    }

    /** One class whose order a {@code __bases__} write replaced, with the order it had and the one installed. */
    private record ReplacedOrder(PyClass type, PyType[] before, PyType[] after) {
    }

    /**
     * {@code mro_invoke} after a custom {@code mro}: turn what it returned into an order and check it.
     *
     * <p>The custom path is CPython's {@code mro_check}: every item has to be a class, and its solid
     * base has to sit in this class's solid base's order. No check is made that the first item is the
     * class itself — {@code test_altmro}'s {@code PerverseMetaType} returns the order reversed.
     */
    private static PyType[] mroInvoke(PyClass kind, PyObject result) {
        List<PyObject> items;
        if (result instanceof PyTuple tuple) {
            items = new ArrayList<>(tuple.size());
            for (int i = 0; i < tuple.size(); i++) {
                items.add(tuple.at(i));
            }
        } else if (result instanceof PyList list) {
            items = list.getItems();
        } else {
            throw Slots.typeError("'%s' object is not iterable", result.getType().name());
        }
        if (items.isEmpty()) {
            throw Slots.typeError("type MRO must not be empty");
        }
        PyType solid = solidBase(kind);
        PyType[] order = new PyType[items.size()];
        for (int i = 0; i < order.length; i++) {
            PyObject item = items.get(i);
            if (!(item instanceof PyType base)) {
                throw Slots.typeError(
                        "mro() returned a non-class ('%s')", item.getType().name());
            }
            if (!contains(solid.mro(), solidBase(base))) {
                throw Slots.typeError(
                        "mro() returned base with unsuitable layout ('%s')", base.filedName());
            }
            order[i] = base;
        }
        return order;
    }

    /** Whether this type appears in the given order. */
    private static boolean contains(PyType[] order, PyType needle) {
        for (PyType candidate : order) {
            if (candidate == needle) {
                return true;
            }
        }
        return false;
    }

    /** {@code type_is_subtype_base_chain}: whether this type's parent chain reaches the needle. */
    private static boolean parentChainContains(PyType type, PyType needle) {
        for (PyType walk = type; walk != null; walk = walk.parent()) {
            if (walk == needle) {
                return true;
            }
        }
        return false;
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
        PyObject declared = Specials.member(made, "__init__");
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
            // which keeps the positionals and throws the names away without reading them: `Bare("x")` is a
            // value and `Bare("x") == 1` is not an arity complaint about a class that declares nothing.
            // Every refusal of a named argument therefore comes from the initialiser the order answers
            // with, and `Classes.construct` reaches that row through the order — the inherited
            // `BaseException_init` says it in the name of the class the value was made as, and a class that
            // declares an `__init__` of its own is initialised by `slot_tp_init`, which names the method
            // instead: `Named(1, "a", flag=2)` of an `OSError` whose body wrote `__init__` says
            // `Named.__init__() got an unexpected keyword argument`, because `oserror_use_init` let
            // `OSError_new` past the keywords and the function is the one that cannot bind them.
            if (kind.matches(ExceptionTypes.OS_ERROR)
                    && kwNames != null
                    && kwNames.size() > 0
                    && !Specials.defines(kind, "__init__")) {
                throw Slots.typeError("%s() takes no keyword arguments", kind.name());
            }
            return kind.matches(ExceptionTypes.OS_ERROR)
                    ? PyOSError.create(kind, Arguments.positionals(args, names))
                    : new PyBaseException(kind, Arguments.positionals(args, names));
        }
        PyObject wrapper = allocateWrapper(kind);
        if (wrapper != null) {
            return wrapper;
        }
        // A class written over one of the four `_io` bases inherits the base's value: an instance
        // dictionary and the rows the base files. The concrete streams stay the Java values `_io` names;
        // what `io.py` adds here is the ABC layer that registers them.
        if (kind.matches(PyType.IO_BASE)) {
            return new IoBases.Value(kind);
        }
        // A class whose first base is an immutable value inherits that value's creator, not `object`'s:
        // the value is fixed here, by whatever `int` or `str` would have made of the arguments, and the
        // shape the subtype keeps it in is decided by the class being instantiated. `__init__` still runs
        // afterwards (`construct` asks the order for it), which is how an `IntEnum` writes `_value_` and
        // friends beside a number that could not change.
        if (kind.parent() == PyType.INT) {
            PyInt made = (PyInt) Constructors.parsedInteger(args, names);
            return new PyIntSubclass(made.getValue(), kind);
        }
        if (kind.parent() == PyType.STR) {
            PyStr made = (PyStr) Constructors.instantiate(PyType.STR, args, kwNames);
            return new PyStrSubclass(made.getValue(), kind);
        }
        if (kind.parent() == PyType.TUPLE) {
            PyTuple made = (PyTuple) Constructors.instantiate(PyType.TUPLE, args, kwNames);
            return new PyTupleSubclass(made.getArray(), kind);
        }
        // A class written over `weakref.ref` inherits the reference's creator: the referent and the
        // callback are read off the same two positionals, and the value is the subclass's, which is
        // what lets `class X(ref)` keep a dictionary beside the reference.
        if (kind.matches(PyType.WEAK_REFERENCE)) {
            return Weakref.newRef(kind, args, names);
        }
        // A class written over `_random.Random` inherits its creator: the seed is read off the same one
        // positional and the state stays beside the subclass's dictionary, which is what lets
        // `class Random(_random.Random)` draw the same stream as the base.
        if (kind.matches(PyType.RANDOM)) {
            return RandomModule.constructing(kind, args, names);
        }
        if (excess(args, kwNames) && inheritsOnlyObjectInit(kind)) {
            throw Slots.typeError("%s() takes no arguments", kind.name());
        }
        if (kind.matches(PyType.DICT)) {
            return new PyDictInstance(kind);
        }
        // `list.__new__` allocates the subtype it was handed, which for a Python class is an instance
        // dictionary beside an empty list; `list.__init__` then fills that list from the argument.
        if (kind.matches(PyType.LIST)) {
            return new PyListInstance(kind);
        }
        return new PyInstance(kind);
    }

    /**
     * {@code tp_alloc} for the three descriptor wrappers a Python class may derive from.
     *
     * <p>CPython's {@code classmethod_new}, {@code staticmethod_new} and {@code property_new} are
     * {@code object_new}, so the value they hand back is a {@code classmethod} struct whose type is the
     * subclass — {@code cm_callable}, {@code cm_dict} and the two property fields are all there, and the
     * initialiser the order answers with is the wrapper's own. There is no {@code tp_alloc} table here,
     * so the same thing is done the way this runtime can: the Java object the wrapper's members read is
     * made, its type is rewritten to the class being called ({@link PyObject}'s {@code type} field, which
     * is what a subtype's allocation sets), and the members therefore behave as the wrapper's do while
     * {@code type(x)} and {@code repr} name the subclass.
     *
     * @return the value, or null for a class that is not one of the three
     */
    private static PyObject allocateWrapper(PyClass kind) {
        PyObject value;
        if (kind.matches(PyType.CLASS_METHOD)) {
            value = new PyClassMethod();
        } else if (kind.matches(PyType.STATIC_METHOD)) {
            value = new PyStaticMethod();
        } else if (kind.matches(PyType.PROPERTY)) {
            value = new PyProperty();
        } else {
            return null;
        }
        value.type = kind;
        return value;
    }

    /**
     * Whether the nearest {@code __init__} a class inherits is {@code object}'s own.
     *
     * <p>{@code object_new} refuses arguments only while the init slot is still {@code object_init}, so
     * the question is which owner in the order declares the name first, not whether the class's own body
     * does. A class that inherits {@code classmethod.__init__} must let that initialiser see the
     * arguments and complain in its own words, while a class that only reaches {@code object} refuses
     * before any call in the words {@code C() takes no arguments}.
     */
    private static boolean inheritsOnlyObjectInit(PyClass kind) {
        for (PyType owner : kind.mro()) {
            if (Specials.declared(owner, "__init__") != null) {
                return owner == PyType.OBJECT;
            }
        }
        return true;
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
        if (classes instanceof PyUnionType union) {
            // A union is scanned in the order it was written, so `isinstance(1, int | list[int])` is true
            // without ever reaching the parameterized half, while the other order complains about it.
            for (PyObject member : union.members()) {
                if (instanceOf(value, member)) {
                    return true;
                }
            }
            return false;
        }
        if (classes instanceof PyGenericAlias) {
            throw Slots.typeError("isinstance() argument 2 cannot be a parameterized generic");
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
        if (classes instanceof PyUnionType union) {
            for (PyObject member : union.members()) {
                if (subclassOf(derived, member)) {
                    return true;
                }
            }
            return false;
        }
        if (classes instanceof PyGenericAlias) {
            throw Slots.typeError("issubclass() argument 2 cannot be a parameterized generic");
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
