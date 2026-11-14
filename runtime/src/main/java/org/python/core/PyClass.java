package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A class Python wrote: a {@link PyType} whose members live in a dictionary rather than in a Java
 * table, and whose values are {@link PyInstance}s.
 *
 * <p>It is a subclass of {@link PyType} rather than a parallel kind of thing, because that is what a
 * class is: it is an instance of {@code type}, it is callable, {@code isinstance} walks its order, and
 * every place the runtime already asks "is this a type?" — the method form of {@code LOAD_ATTR},
 * {@code Slots}'s refusal wordings, {@code isinstance} — has to keep asking it correctly. The seam a
 * {@code PyJavaType} uses to exist is the same seam this one uses.
 *
 * <p>Two of the names a type answers are not in the dictionary. {@code __name__} never is, and
 * {@code __qualname__} is taken out of it while the class is being built, because both are also part
 * of the type's own identity. Everything else — {@code __module__}, {@code __doc__},
 * {@code __firstlineno__}, {@code __static_attributes__} — stays where the class body put it.
 */
public class PyClass extends PyType {

    /** The class dictionary, which is what {@code __dict__} of a class is. */
    private final PyDict namespace;

    /**
     * The bases as {@code type} received them, already defaulted to {@code (object,)} when empty.
     *
     * <p>Not final: {@code C.__bases__ = (...)} replaces the tuple, and both {@code __bases__} and the
     * order recomputation have to read the replacement.
     */
    private PyTuple bases;

    private PyStr qualName;

    /**
     * Whether this class keeps the two slots whose descriptors {@link Classes} files, in CPython's terms
     * {@code tp_dictoffset} and {@code tp_weaklistoffset}.
     *
     * <p>Each is true when this class introduced the storage or one of its bases did, which is what makes
     * the question {@link Classes#declare} asks of a base answerable for a class Python wrote: a class
     * under {@code Thing} has both and files neither.
     */
    private boolean valueDict;

    private boolean weakRefs;

    /**
     * CPython's {@code tp_basicsize}, as far as this runtime needs it: the shape a class's values
     * occupy, which is what two bases are compared by when one replaces the other.
     *
     * <p>Nothing is allocated from this number — a Python value is always one {@link PyInstance} — and it
     * is not an absolute size: the walk starts at 16, {@code object}'s own {@code tp_basicsize}, for a
     * compiled-in base. A field declared in {@code __slots__} adds one pointer, so {@code __slots__ =
     * ("x",)} is eight bytes more than its base while {@code __slots__ = ("__dict__",)} is the same size,
     * the dictionary and the weak-reference list being kept outside the value.
     */
    private int instanceSize;

    /**
     * CPython's {@code ht_slots}: the fields the body declared, sorted, with duplicates kept; null for a
     * body that wrote no {@code __slots__} at all.
     *
     * <p>Null and an empty list differ only to a reader of {@code __slots__}: for layout they add the same
     * nothing, which is what makes {@code __slots__ = ()} interchangeable with no declaration when the
     * two classes are compared.
     */
    private List<String> slotLayout;

    /** CPython's {@code SHARED_KEYS_MAX_SIZE}: the number of key slots a class's keys start with. */
    private static final int SHARED_KEYS_MAX_SIZE = 30;

    /**
     * CPython's {@code ht_cached_keys}: the names an instance's inline values array reserves slots for,
     * in the order they were added.
     *
     * <p>This runtime keeps every attribute in a {@link PyDict}, but the length of this list and {@link
     * #cachedUsable} together are what {@code shared_keys_usable_size} reports for the class, and that
     * number is the size of the array each new value allocates ({@code _PyObject_InitInlineValues}). A
     * write of a name the list does not hold either claims a slot from {@link #cachedUsable} or, when
     * none is left, makes the value materialize its dictionary and become a value without inline values.
     * {@code __static_attributes__} fills the list at class creation, which is {@code make_new_keys}.
     */
    private final List<String> cachedKeys = new ArrayList<>();

    /** {@code dk_usable}: key slots not yet claimed; one is spent by each value the class makes. */
    private int cachedUsable = SHARED_KEYS_MAX_SIZE;

    PyClass(
            String name,
            PyTuple bases,
            PyDict namespace,
            String qualName,
            PyType metatype) {
        super(name, storageBase(bases));
        this.type = metatype;
        this.bases = bases;
        this.namespace = namespace;
        this.qualName = PyStr.of(qualName);
        PyObject declared = namespace.get(PyStr.interned("__static_attributes__"));
        if (declared instanceof PyTuple attributes) {
            for (int i = 0; i < attributes.size(); i++) {
                if (attributes.at(i) instanceof PyStr attribute) {
                    cachedKeys.add(attribute.getValue());
                }
            }
        }
    }

    /**
     * The base whose storage this class's values occupy: {@code tp_base}, which is {@code best_base}'s
     * winner and not always {@code __bases__[0]}.
     *
     * <p>{@code type('C', (B, int), {})} over a plain {@code B} has {@code int} for its base: a class
     * that introduces no layout of its own — which a plain Python class with a managed dictionary does
     * not — lets a later base supply the storage, and modes and identity reads follow {@code tp_base}.
     * An empty tuple is {@code object}, the default {@code type_new} writes first.
     */
    private static PyType storageBase(PyTuple bases) {
        if (bases.size() == 0) {
            return PyType.OBJECT;
        }
        return Classes.bestBase(bases);
    }

    /**
     * The bookkeeping half of {@code _PyObject_InitInlineValues}: one new value spends one key slot.
     *
     * <p>The reference sizes the array it allocates at {@code shared_keys_usable_size} — the keys known
     * plus the remaining budget — so any insertion {@code insert_split_key} later accepts lands inside
     * an array allocated earlier. What this runtime has to mirror is only the budget being spent.
     */
    void spendInlineSlot() {
        if (cachedUsable > 1) {
            cachedUsable--;
        }
    }

    /** Whether these keys already name {@code name}, so no new slot has to be claimed for it. */
    boolean holdsInlineKey(String name) {
        return cachedKeys.contains(name);
    }

    /**
     * {@code insert_split_key}: claim a slot for one new name.
     *
     * @return false when the budget is spent, which is the reference's {@code DKIX_EMPTY}: the value
     *     writing the name materializes its dictionary and keeps no inline values from then on
     */
    boolean claimInlineKey(String name) {
        if (cachedUsable <= 0) {
            return false;
        }
        cachedKeys.add(name);
        cachedUsable--;
        return true;
    }

    /** The two slot storages this class's values have, told to it by the class system. */
    void storesValueDict(boolean stored) {
        valueDict = stored;
    }

    void storesWeakRefs(boolean stored) {
        weakRefs = stored;
    }

    /** The instance shape and declared fields, told to it by the class system as it files the slots. */
    void layout(int size, List<String> slots) {
        instanceSize = size;
        slotLayout = slots;
    }

    /**
     * Whether {@code __abstractmethods__} was set to a non-empty value on this class.
     *
     * <p>CPython's {@code Py_TPFLAGS_IS_ABSTRACT} is not a property of the class body: a class whose
     * namespace already holds {@code __abstractmethods__} is born without the bit, and only
     * {@code type_set_abstractmethods} — the setter behind the attribute — sets or clears it. {@code
     * abc.ABCMeta} writes that attribute after the class exists, which is why the flag follows the
     * setter and not the namespace.
     */
    private boolean abstract_;

    /** Told to it by the {@code __abstractmethods__} setter; see {@link #abstract_}. */
    void markAbstract(boolean isAbstract) {
        abstract_ = isAbstract;
    }

    boolean isAbstract() {
        return abstract_;
    }

    /** This class's part of CPython's {@code tp_basicsize}; see {@link #instanceSize}. */
    int instanceSize() {
        return instanceSize;
    }

    /** The declared field names as {@code ht_slots} holds them, or null with no {@code __slots__}. */
    List<String> slotLayout() {
        return slotLayout;
    }

    @Override
    boolean hasValueDict() {
        return valueDict;
    }

    @Override
    boolean hasWeakRefList() {
        return weakRefs;
    }

    /** The dictionary the class body filled, which is where {@link Specials} reads a declaration. */
    @Override
    PyDict namespace() {
        return namespace;
    }

    /** The tuple {@code C.__bases__} answers and {@code type_set_bases} replaces. */
    PyTuple baseTuple() {
        return bases;
    }

    @Override
    PyType[] bases() {
        PyType[] declared = new PyType[bases.size()];
        for (int i = 0; i < declared.length; i++) {
            declared[i] = (PyType) bases.at(i);
        }
        return declared;
    }

    /**
     * The write half of {@code type_set_bases}: called once the new tuple has passed every refusal
     * CPython makes, and before the order is recomputed.
     */
    void adoptBases(PyTuple value) {
        bases = value;
    }

    /**
     * {@code Point.__name__}, {@code Point.__mro__}, {@code Point.as_text}.
     *
     * <p>A class-level read runs the descriptor protocol with no value: {@code Point.as_text} stays a
     * {@code function}, {@code Point.counted} hands over the property itself rather than asking for a
     * value it has no instance to compute, and {@code Point.kind} gives back the class. Binding to a
     * receiver happens on the value, in {@link PyInstance#getAttr}.
     *
     * <p>The names a class keeps for its own identity are answered here, and CPython answers them from
     * getset descriptors on {@code type} — data descriptors, so they outrank anything the class body
     * wrote. {@code __class__} is the metatype rather than {@code type}, which is what a program reads
     * back after {@code class Sub(Meta, tag=3)}. The metatype step that decides the rest is
     * {@link PyType#classMember}, and it is why a {@code property} written in a metaclass answers for a
     * class that never mentions the name — and why a {@code __getattr__} written there answers for a name
     * nobody mentions at all, which is the last step here rather than the first.
     *
     * <p>A metatype that declares {@code __getattribute__} replaces this whole read, which is CPython's
     * {@code slot_tp_getattr_hook} for types: the hook is called first and owns every name, and only an
     * {@code AttributeError} from it lets the metatype's {@code __getattr__} be asked. The read that
     * skips the hook — {@code type.__getattribute__}, and the half a hook delegates to — is
     * {@link #genericGetAttr}.
     */
    @Override
    public PyObject getAttr(String attribute) {
        PyObject hook = Specials.lookUp(getType(), "__getattribute__");
        if (hook != null) {
            PyObject name = PyStr.of(attribute);
            try {
                return Slots.call(
                        Descriptors.get(hook, this, getType()), new PyObject[] {name}, PyTuple.EMPTY);
            } catch (PyRaise raised) {
                if (!raised.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                    throw raised;
                }
                PyObject fallback = Specials.invoke(this, "__getattr__", name);
                if (fallback != Slots.DECLINED) {
                    return fallback;
                }
                throw raised;
            }
        }
        PyObject answered = genericGetAttr(attribute);
        // With no hook, the metatype's `__getattr__` is still the fallback every missing name reaches,
        // which is `slot_tp_getattr_hook`'s second half.
        return answered == Slots.DECLINED
                ? Specials.invoke(this, "__getattr__", PyStr.of(attribute))
                : answered;
    }

    /**
     * {@code type.__getattribute__(Point, 'as_text')}: the read with no hook in it.
     *
     * <p>This is what {@code type}'s own {@code __getattribute__} answers with, so the name a metaclass
     * hands back to {@code super()} cannot come round to the hook again. A name that is nowhere is
     * refused here rather than returned, and — unlike the ordinary read — no {@code __getattr__} is
     * asked; the fallback belongs to the hook, not to the generic walk beneath it.
     */
    @Override
    PyObject genericGetAttr(String attribute) {
        PyObject identity =
                switch (attribute) {
                    case "__qualname__" -> qualName;
                    case "__bases__" -> bases;
                    case "__mro__" -> filingOrder()
                            ? PyNone.INSTANCE
                            : PyTuple.of(mro());
                    case "__class__" -> type;
                    default -> null;
                };
        if (identity != null) {
            return identity;
        }
        // `__dict__` is a getset descriptor on `type` like the names above, and its answer has to be
        // this class's own dictionary; asking classMember would hand the dictionary to a metaclass's
        // property first, which is a thing a program can write but never means by `C.__dict__`.
        if (attribute.equals("__dict__")) {
            return PyMappingProxy.of(namespace);
        }
        PyObject member = classMember(attribute);
        if (member != Slots.DECLINED) {
            return member;
        }
        // The rest of the names a type answers for itself — `__name__`, `__base__` — are the built-in
        // type's own, and a class has nothing to say about them. A name nobody answers stays declined,
        // which is what lets the complaining read and the optional read differ above this point.
        return super.getAttr(attribute);
    }

    /**
     * {@code Point.kind = 'point'}, and {@code Point.__name__ = 'Other'}, and their {@code del}.
     *
     * <p>Two steps in CPython's order. A metatype that declares {@code __setattr__} owns every name its
     * classes are given, because {@code tp_setattro} for such a class is {@code slot_tp_setattro} and that
     * is what {@code setattr} reaches — the same rule {@link PyInstance} follows for a value's own class.
     * With no hook to run, what is left is {@link #write}.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        PyObject hook = Specials.lookUp(getType(), "__setattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(attribute), value}, null);
        }
        write(attribute, value);
        return PyNone.INSTANCE;
    }

    /** {@code del Point.kind}, which only removes what this class's own dictionary holds. */
    @Override
    public PyObject delAttr(String attribute) {
        PyObject hook = Specials.lookUp(getType(), "__delattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(attribute)}, null);
        }
        write(attribute, null);
        return PyNone.INSTANCE;
    }

    /**
     * {@code type_setattro}: the metatype's descriptor first, this class's own dictionary after it.
     *
     * <p>The name is looked up in the metatype's order rather than in the class's, and only a member there
     * that can write takes it — which is what makes {@code C.__doc__ = 'text'} a filing rather than an
     * assignment, and what makes a {@code property} a metaclass declares answer for its classes. The
     * dictionary step then takes everything else, including a name the metatype files but cannot write:
     * {@code C.__basicsize__ = 1} lands in the dictionary, because a read-only member is not a gate.
     *
     * <p>This is also the half {@code type.__setattr__} answers with. It has to be reached without the
     * hook above, or {@code super().__setattr__(name, value)} inside a metaclass's own {@code __setattr__}
     * would only come back round to the same method.
     */
    void write(String attribute, PyObject value) {
        PyObject member = Specials.member(getType(), attribute);
        if (member != null && Descriptors.writes(member)) {
            if (value == null) {
                Descriptors.delete(member, this);
            } else {
                Descriptors.set(member, this, value);
            }
            return;
        }
        // {@code type_update_dict}. A delete of a name the dictionary does not hold is CPython's odd
        // sentence: the setter it calls is a dictionary write of NULL, whose KeyError the gate above turns
        // into this AttributeError rather than the usual read failure.
        PyStr key = PyStr.interned(attribute);
        if (value == null) {
            if (namespace.remove(key) == null) {
                throw Slots.failedRead(
                        this, attribute, "type object '%s' has no attribute '%s'", name(), attribute);
            }
            return;
        }
        namespace.put(key, value);
    }

    /**
     * The name the type is called by, which is not a dictionary entry but part of what it is.
     *
     * <p>{@code C.__name__ = 'Other'} leaves {@code repr(C)} reading the module and the qualified name,
     * which are the two things CPython keeps apart from {@code tp_name}.
     */
    void writeName(PyObject value) {
        setName(text("__name__", value));
    }

    /** {@code C.__qualname__ = 'Outer.Inner'}: the dotted half of the same identity. */
    void writeQualName(PyObject value) {
        qualName = PyStr.of(text("__qualname__", value));
    }

    /**
     * A class Python built has a dictionary to write into, so it does not take the name the way a
     * compiled-in type does — and {@code C.__class__ = M} is therefore {@code object}'s to answer, which
     * is what lets a written class be given a new metatype and refuses it anything else.
     */
    @Override
    boolean filedByItsClass(String name) {
        return false;
    }

    /**
     * The name the type is called by, which CPython requires to be text.
     *
     * <p>{@code can only assign string to Point.__name__, not 'int'} — the only place the class system
     * refuses a value rather than storing it and letting a later read complain. The second refusal is the
     * one {@code type_set_name} makes before it copies the text anywhere: a name is a C string there, so
     * an embedded null character is a name that cannot be written down.
     */
    private String text(String attribute, PyObject value) {
        if (!(value instanceof PyStr name)) {
            throw Slots.typeError(
                    "can only assign string to %s.%s, not '%s'", name(), attribute, value.getType().name());
        }
        Classes.checkUtf8(name.getValue());
        if (name.getValue().indexOf('\0') >= 0) {
            throw Slots.valueError("type name must not contain null characters");
        }
        return name.getValue();
    }

    /**
     * The name a member of this class reports itself with, which is the qualified one.
     *
     * <p>{@code Outer.Inner.__init_subclass__() takes no keyword arguments} — CPython binds a
     * {@code METH_CLASS} member to the class and takes {@code tp_qualname} for the complaint, so a
     * nested class names its enclosing one even when the hook was inherited from a base.
     */
    @Override
    String dottedName() {
        return qualName.str();
    }

    /** The module this class was defined in, which {@code type} takes from the frame that built it. */
    PyObject module() {
        return namespace.get(PyStr.interned("__module__"));
    }

    /**
     * {@code <class 'mymodule outer.<locals>.Inner'>}: module then qualified name.
     *
     * <p>CPython leaves the module out when the class has none, which is the state a class made by a
     * three-argument {@code type()} call is in until the calling frame can be asked for its
     * {@code __name__}.
     */
    @Override
    public String repr() {
        // A class whose metatype declares `__repr__` answers with it, the way every value answers with
        // its type's: that is what makes `repr(Color)` read `<enum 'Color'>` rather than
        // `<class 'enum.Color'>`, since `EnumType` declares one. `Specials.lookUp` walks class
        // dictionaries only, so `type`'s own `__repr__` is not seen here and `repr(Plain)` cannot end
        // up asking itself for the same answer.
        PyObject declared = Specials.lookUp(getType(), "__repr__");
        if (declared != null) {
            PyObject answer =
                    Slots.call(
                            Descriptors.get(declared, this, getType()), new PyObject[0], null);
            if (!(answer instanceof PyStr text)) {
                throw Slots.typeError(
                        "__repr__ returned non-string (type %s)", answer.getType().name());
            }
            return text.getValue();
        }
        return "<class '" + qualifiedName() + "'>";
    }

    @Override
    public String qualifiedName() {
        return module() instanceof PyStr text
                ? text.getValue() + "." + qualName.str()
                : qualName.str();
    }
}
