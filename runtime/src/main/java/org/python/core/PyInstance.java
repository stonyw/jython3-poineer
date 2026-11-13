package org.python.core;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * A value of a class Python wrote: the receiver every method found on {@link PyClass} gets, and the
 * owner of the {@code __dict__} that {@code self.x = 1} writes into.
 *
 * <p>Every operation on it is decided twice over. As an attribute, it is the ordinary case of
 * {@link Slots#getAttr}: what the instance holds wins, and the class is asked only when it does not —
 * which is why {@code held.shared = "shadowed"} changes one value and leaves the class alone. As an
 * operand, it has no Java slot of its own, so each one of {@link PyObject}'s refusals is answered by
 * the double-underscore name its class declares, found through {@link Specials} in lookup order. That
 * is CPython's {@code slot_tp_*} machinery, which is a wrapper installed on the type rather than a
 * virtual method on the value; the two land on the same answer, and this is the half that says which
 * name is asked for which operation.
 */
public class PyInstance extends PyObject {

    /**
     * {@code self.__dict__}: the attributes this one value holds, which a class does not.
     *
     * <p>Null for a value of {@code object} itself, which CPython gives no dictionary: {@code object()}
     * answers {@code AttributeError} for {@code __dict__} and refuses every attribute write in {@link
     * GenericAttributes}' words. {@code object.__new__(object)} is what makes one.
     *
     * <p>Not final, because {@code self.__dict__ = {...}} replaces the whole dictionary rather than writing
     * a name into it, and {@code del self.__dict__} leaves an empty one behind for the next read.
     *
     * <p>A class that declares {@code __slots__} without {@code __dict__} among them has none: this field
     * is null for its values, as CPython leaves the pointer null, and {@code hasattr(value, "__dict__")}
     * is false because the class files no descriptor for the name.
     */
    private PyDict dictionary;

    PyInstance(PyType kind) {
        this(kind, kind.hasValueDict());
    }

    /**
     * A value whose dictionary is kept elsewhere, as {@code _thread._local}'s is: the field stays null
     * and {@link #instanceDict} is overridden to name the storage the subclass owns.
     */
    PyInstance(PyType kind, boolean storesDict) {
        super(kind);
        this.dictionary = storesDict ? PyDict.empty() : null;
    }

    /** {@code _PyObject_GetDictPtr}: where this value keeps its attributes, or null when nowhere. */
    PyDict instanceDict() {
        return dictionary;
    }

    /** The dictionary a read or write may create, which is the lazy half of {@link #instanceDict}. */
    PyDict ensureInstanceDict() {
        if (dictionary == null) {
            dictionary = PyDict.empty();
        }
        return dictionary;
    }

    /** {@code subtype_dict}: the read of the descriptor a class files for the storage it introduces. */
    static PyObject valueDict(PyObject self) {
        if (self instanceof PyInstance held) {
            return held.ensureInstanceDict();
        }
        return self.attributeStore();
    }

    /**
     * {@code subtype_setdict}, which {@code del} reaches through the same pointer with nothing handed
     * over.
     *
     * <p>The refusal is about the value, since CPython moves the pointer rather than copying into the
     * dictionary: {@code t.__dict__ = 5} names the number, and {@code del t.__dict__} leaves an empty
     * dictionary standing for the next read to find.
     */
    static void writeDict(PyObject self, PyObject value) {
        PyDict replacement;
        if (value == null) {
            replacement = PyDict.empty();
        } else if (value instanceof PyDict dict) {
            replacement = dict;
        } else {
            throw Slots.typeError(
                    "__dict__ must be set to a dictionary, not a '%s'", value.getType().filedName());
        }
        if (self instanceof PyInstance held) {
            held.dictionary = replacement;
            return;
        }
        self.replaceAttributeStore(replacement);
    }

    /**
     * The values of the fields this class declared in {@code __slots__}, kept apart from {@code __dict__}
     * because CPython keeps them apart too: {@code __slots__ = ("__dict__", "b")} stores {@code b} in an
     * offset beside the dictionary, so setting it leaves the name out of {@code vars(self)} all the same.
     *
     * <p>Keyed by the declaring class as well as the name, because a subclass may declare a field its base
     * already declared and CPython gives the two descriptors different offsets: {@code Sub.a = 1} writes
     * the subclass's cell while {@code Base.__dict__["a"].__get__(sub)} reads the base's, which is empty
     * and so raises.
     */
    private Map<SlotKey, PyObject> slotValues;

    /** One declared field: the class that laid it out and the name it gave it. */
    private record SlotKey(PyType owner, String name) {}

    @Override
    PyObject slotValue(PyType owner, String name) {
        PyObject held = slotValues == null ? null : slotValues.get(new SlotKey(owner, name));
        if (held == null) {
            throw Slots.attributeError(
                    "'%s' object has no attribute '%s'", getType().filedName(), name);
        }
        return held;
    }

    @Override
    void writeSlot(PyType owner, String name, PyObject value) {
        SlotKey key = new SlotKey(owner, name);
        if (value == null) {
            // `member_object_set` reached by `del`, which finds nothing to empty and complains with the
            // bare name: no class, no attribute sentence, only `AttributeError: a`.
            if (slotValues == null || slotValues.get(key) == null) {
                throw Slots.attributeError("%s", name);
            }
            slotValues.remove(key);
            return;
        }
        if (slotValues == null) {
            slotValues = new HashMap<>();
        }
        slotValues.put(key, value);
    }

    /**
     * {@code x.__class__ = Y}: the type changes, and the fields this value fills stay filled.
     *
     * <p>CPython keeps a declared field at an offset, and two classes are only swapped when they agree on
     * their fields, so the cell a name was written to is the cell the new class's descriptor reads. The
     * store here is keyed by the class that declared the field, so the entries this value's own class
     * declared are re-keyed to the class taking over; the ones an ancestor declared keep their owner,
     * because that descriptor is still the one the lookup finds.
     */
    @Override
    void reassignedTo(PyType kind) {
        PyType previous = type;
        super.reassignedTo(kind);
        if (slotValues == null || previous == kind) {
            return;
        }
        Map<SlotKey, PyObject> moved = new HashMap<>();
        for (Map.Entry<SlotKey, PyObject> entry : slotValues.entrySet()) {
            SlotKey key = entry.getKey();
            moved.put(
                    key.owner() == previous ? new SlotKey(kind, key.name()) : key,
                    entry.getValue());
        }
        slotValues = moved;
    }

    // --- attributes ----------------------------------------------------------------------

    /**
     * {@code self.name}: {@code slot_tp_getattr_hook} when the class declares {@code __getattribute__},
     * and the generic read otherwise.
     *
     * <p>CPython's hook is two calls, not one. The class's {@code __getattribute__} is asked first and
     * owns every name outright; only an {@code AttributeError} from it lets {@code __getattr__} be asked,
     * and a hook that declines in any other way has already answered. The generic read stays the ordinary
     * path for a class that declares neither name, which is every class the built-in types and the
     * hot path below are made of.
     *
     * <p>{@code object.__getattribute__} is the generic read and not the hook: a class that overrides the
     * name and delegates to {@code object}'s copy must not come back round to itself. That is why the
     * explicit fallback goes to {@link #genericGetAttr}, which skips the lookup of the hook entirely.
     */
    @Override
    public PyObject getAttr(String name) {
        PyObject hook = Specials.lookUp(type, "__getattribute__");
        if (hook == null) {
            return owned(name);
        }
        try {
            return call(hook, PyStr.of(name));
        } catch (PyRaise raised) {
            if (!raised.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                throw raised;
            }
            PyObject fallback = callHook("__getattr__", PyStr.of(name));
            // The hook's answer is the value even when it is `NotImplemented`: CPython returns whatever
            // `__getattr__` said, and only a class with no hook at all leaves the error standing.
            if (fallback != Slots.DECLINED) {
                return fallback;
            }
            throw raised;
        }
    }

    /**
     * {@code object.__getattribute__(self, name)}: the generic read, never the declared hook.
     *
     * <p>This is the half a hook delegates to. It walks the order the same way {@link #owned} does but
     * stops at the missing name with CPython's ordinary sentence instead of asking {@code __getattr__},
     * because the {@code __getattr__} fallback belongs to the hook that called it, not to the generic
     * read underneath.
     */
    @Override
    PyObject genericGetAttr(String name) {
        PyObject held = genericLookup(name);
        if (held != Slots.DECLINED) {
            return held;
        }
        throw Slots.attributeError(
                "'%s' object has no attribute '%s'", getType().filedName(), name);
    }

    /**
     * {@code self.name} for every other name, with {@code __class__} answered where CPython answers it.
     *
     * <p>{@code __class__} is a getset of {@code object}, so it sits <em>inside</em> the type lookup
     * rather than above it: a class that files a member under that name — a property, or the class itself
     * written out — is what the walk finds first, and only a class that declares nothing falls back to
     * the value's real type. That is the whole of how {@code isinstance(p, int)} is true for a
     * {@code Proxy} that claims to be an {@code int}, and it is why the fallback comes before
     * {@code __getattr__} rather than after: {@code object}'s getset outranks a {@code __getattr__} the
     * same way it outranks the instance dictionary.
     */
    private PyObject owned(String name) {
        PyObject held = genericLookup(name);
        return held == Slots.DECLINED ? callHook("__getattr__", PyStr.of(name)) : held;
    }

    /**
     * The walk without the hooks: a descriptor that both writes and binds, the instance dictionary, the
     * class order, the dictionary a built-in base publishes, and {@code __class__}.
     *
     * <p>A member that declares {@code __set__} but no {@code __get__} is data but has no getter, and
     * CPython only lets the data half jump the queue when there is a getter to run — {@code f != NULL &&
     * PyDescr_IsData(descr)}. So the instance dictionary is consulted first and the member is handed back
     * only when the dictionary has nothing, which is what makes {@code test_set_and_no_get}'s 42 win over
     * the descriptor that just wrote it.
     *
     * <p>Shared by the ordinary read and by {@code object.__getattribute__}, which differ only in what
     * they do with a name nothing holds — one asks {@code __getattr__}, the other raises.
     *
     * <p>The built-in bases come from {@link TypeDict#read} rather than from {@link Specials#lookUp}
     * because the latter sees only the namespaces Python classes wrote. {@code object} files
     * {@code __reduce_ex__} and {@code __getstate__} in a table of its own, and CPython's type walk
     * reaches them before {@code __getattr__} — so a class with a {@code __getattr__} that refuses every
     * name still inherits the pickle helpers, which is what {@code test_issue24097} asks for.
     */
    private PyObject genericLookup(String name) {
        PyObject member = Specials.lookUp(type, name);
        if (member != null && Descriptors.writes(member) && Descriptors.binds(member)) {
            return Descriptors.get(member, this, type);
        }
        PyDict store = instanceDict();
        PyObject held = store == null ? null : store.get(PyStr.interned(name));
        if (held != null) {
            return held;
        }
        if (member != null) {
            return Descriptors.get(member, this, type);
        }
        PyObject inherited = inherited(name);
        if (!Slots.declined(inherited)) {
            return inherited;
        }
        PyObject published = TypeDict.read(this, name);
        if (!Slots.declined(published)) {
            return published;
        }
        return name.equals(ClassAttribute.NAME) ? type : Slots.DECLINED;
    }

    /**
     * The member the class order files under {@code hook}, bound to this value and called; or
     * {@link Slots#DECLINED} when nothing in the order names it.
     *
     * <p>Binding goes through {@link Descriptors} so that every way a class can write the hook works:
     * a plain function arrives bound to this value, a {@code staticmethod} arrives bare, and
     * {@code object}'s own wrapper arrives as the bound generic read. The lookup is made afresh on every
     * call because a class is allowed to gain or lose one of these names at any time.
     */
    private PyObject callHook(String hook, PyObject... arguments) {
        PyObject member = Specials.lookUp(type, hook);
        return member == null ? Slots.DECLINED : call(member, arguments);
    }

    private PyObject call(PyObject member, PyObject... arguments) {
        return Slots.call(Descriptors.get(member, this, type), arguments, PyTuple.EMPTY);
    }

    /**
     * A member this value inherits from a built-in base rather than from the class Python wrote.
     *
     * <p>{@link Specials#lookUp} only sees the class namespaces, which is enough for a class whose
     * whole order is written in Python. A subclass of a built-in base also inherits that base's own
     * dictionary, and those names have to be answered before {@code __class__} and
     * {@code __getattr__} — the same place CPython's walk of the type order reaches them. A value
     * whose bases are all Python classes has nothing to add here.
     *
     * @return the member, or {@link Slots#DECLINED} for a name no built-in base declares
     */
    PyObject inherited(String name) {
        return Slots.DECLINED;
    }

    /**
     * Whether this value's own class takes the write that {@code object} would otherwise answer.
     *
     * <p>Both ways a class can do that are Python-level ways: filing a member under the name — which is
     * what makes {@code class Proxy: __class__ = int} keep the write in the instance dictionary, since a
     * plain value in the dictionary is no descriptor and CPython's lookup stops at it — and declaring
     * {@code __setattr__} or {@code __delattr__}, which owns every write before any descriptor is read.
     */
    @Override
    boolean filedByItsClass(String name) {
        return Specials.lookUp(type, name) != null
                || Specials.lookUp(type, "__setattr__") != null
                || Specials.lookUp(type, "__delattr__") != null;
    }

    /**
     * {@code self.name = value}: {@code slot_tp_setattro} when the class declares the method, {@link
     * GenericAttributes} when it does not.
     *
     * <p>The method is called rather than consulted for advice, and it is the class's declaration that
     * decides whether the instance dictionary is written at all — which is why a class that logs its
     * assignments has to write to {@code self.__dict__} itself to store anything. Without one, the
     * dictionary is the fallback only for a name no member of the class owns: {@code thing.keep = 7} goes
     * to {@code Kept.__set__} and leaves {@code thing.__dict__} empty.
     *
     * <p>Asking the class rather than the value is what makes {@code super().__setattr__(name, value)} stop
     * instead of coming back round to the same method: CPython's {@code object.__setattr__} is the generic
     * write, which never consults a {@code __setattr__} the value's own class declared, so this hook is run
     * once and the half below it is where the name really goes.
     */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        PyObject hook = callHook("__setattr__", PyStr.of(name), value);
        if (!Slots.declined(hook)) {
            return hook;
        }
        writeAttribute(name, value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code del self.name}, the other half of {@link #setAttr} and the same two-step: the class's {@code
     * __delattr__} if it wrote one, {@link GenericAttributes} otherwise.
     */
    @Override
    public PyObject delAttr(String name) {
        PyObject hook = callHook("__delattr__", PyStr.of(name));
        if (!Slots.declined(hook)) {
            return hook;
        }
        deleteAttribute(name);
        return PyNone.INSTANCE;
    }

    /**
     * The generic write, in the one place a subclass whose dictionary moves can redirect it: {@code
     * _thread._local} writes into the current thread's dictionary, which no descriptor names.
     */
    void writeAttribute(String name, PyObject value) {
        GenericAttributes.write(this, name, value);
    }

    /** The generic delete, the other half of {@link #writeAttribute}. */
    void deleteAttribute(String name) {
        GenericAttributes.delete(this, name);
    }

    /** {@code _PyObject_GetManagedDict}: the dictionary {@link GenericAttributes} writes through. */
    @Override
    PyDict attributeStore() {
        return instanceDict();
    }

    // --- calls ---------------------------------------------------------------------------

    /**
     * {@code self(...)}: only a class that declares {@code __call__} can be called.
     *
     * <p>The name is read off the type, as every special name is, and then whatever stands under it is
     * called: a function Python wrote binds to the value, an unbound built-in method takes the value as its
     * first argument, and anything else is called as it stands — which is why {@code __call__ = 5} is
     * refused as an {@code int} rather than as the class, and {@code __call__ = str.upper} by the
     * descriptor's own complaint about a receiver it was not written for. Only a class that files nothing
     * under the name is refused as itself, and that refusal is every value's own.
     */
    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        PyObject declared = Specials.member(getType(), "__call__");
        if (declared == null) {
            return super.call(args, kwNames);
        }
        if (declared instanceof PyMethodDescriptor unbound) {
            PyObject[] withSelf = new PyObject[args.length + 1];
            withSelf[0] = this;
            System.arraycopy(args, 0, withSelf, 1, args.length);
            return unbound.call(withSelf, kwNames);
        }
        return Slots.call(Descriptors.get(declared, this, getType()), args, kwNames);
    }

    /**
     * {@code callable(x)} for a value a class statement made: the class's own question, because
     * {@code type_new} fills {@code tp_call} only for a class that files a {@code __call__}. What stands
     * under the name does not matter — {@code callable(x)} is true for {@code __call__ = None}, and the
     * call itself is what finds out that a {@code NoneType} answers no calls.
     */
    @Override
    public boolean isCallable() {
        return Specials.member(getType(), "__call__") != null;
    }

    // --- operators -----------------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return Specials.invoke(this, op.dunder(), other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return Specials.invoke(this, op.reflectedDunder(), other);
    }

    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        return Specials.invoke(this, op.inPlaceDunder(), other);
    }

    @Override
    public PyObject unaryOp(UnaryOp op) {
        return Specials.invoke(this, op.dunder());
    }

    /**
     * {@code self cmp other}, from the method of that name — except that a class declaring only
     * {@code __eq__} still answers {@code !=}, because {@code object.__ne__} exists and negates it.
     *
     * <p>The answer is whatever the method returns, not a {@code bool}: {@code x < y} can hand back a
     * string, and only an {@code if} or a {@code while} forces it to one. Equality is the exception,
     * and identity is its fallback, exactly as {@link Slots#compare} falls back when both types refuse.
     */
    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        if (cmp == CompareOp.NE) {
            PyObject custom = Specials.invoke(this, "__ne__", other);
            if (!Slots.declined(custom)) {
                return custom;
            }
            PyObject equal = Specials.invoke(this, "__eq__", other);
            return Slots.declined(equal)
                    ? PyBool.of(!sameValue(other))
                    : PyBool.of(!Slots.truth(equal));
        }
        PyObject answer = Specials.invoke(this, cmp.dunder(), other);
        if (!Slots.declined(answer)) {
            return answer;
        }
        return cmp == CompareOp.EQ ? PyBool.of(sameValue(other)) : PyNotImplemented.INSTANCE;
    }

    /**
     * The answer when the class wrote no {@code __eq__}: the namespace family compares its pairs, as the
     * base type's {@code tp_richcompare} does for every subtype, and everything else is identity.
     */
    private boolean sameValue(PyObject other) {
        return type.matches(PyType.NAMESPACE) && PyNamespace.isNamespace(other)
                ? PyNamespace.sameContents(this, other)
                : this == other;
    }

    // --- container protocol --------------------------------------------------------------

    // `__len__` and `__bool__` are asked of the namespace by `PyObject`, which is what makes a class that
    // files `None` under either name refuse the same way whichever of its values is asked.

    @Override
    public PyObject getItem(PyObject key) {
        return Specials.invoke(this, "__getitem__", key);
    }

    @Override
    public PyObject setItem(PyObject key, PyObject value) {
        return Specials.invoke(this, "__setitem__", key, value);
    }

    @Override
    public PyObject delItem(PyObject key) {
        return Specials.invoke(this, "__delitem__", key);
    }

    @Override
    public PyObject contains(PyObject item) {
        return Specials.invoke(this, "__contains__", item);
    }

    /**
     * {@code iter(self)}: {@code __iter__}, or the older sequence protocol, or a refusal.
     *
     * <p>A class with only {@code __getitem__} is still iterable, which is Python 3's survival of
     * CPython's sequence iterator: it indexes from zero and stops at the first {@code IndexError}. A
     * class with only {@code __next__} is not iterable at all — it is an iterator-like thing that only
     * {@code next()} can drive. See {@link #declaredIterator}.
     *
     * <p>What counts is that the name is filed, not that its value is a function: CPython fills
     * {@code sq_item} from the name's presence, so {@code __getitem__ = property(...)} still makes the
     * class a sequence, and each index runs the property's getter — {@code test_properties} leans on
     * exactly that. {@link Indexed} invokes through {@link Specials#invoke}, so the descriptor protocol
     * runs there as it would for a plain function.
     */
    @Override
    public PyObject iterator() {
        PyObject walking = Specials.invoke(this, "__iter__");
        if (walking != Slots.DECLINED) {
            return walking;
        }
        return Specials.lookUp(type, "__getitem__") == null
                ? Slots.DECLINED
                : new Indexed();
    }

    /**
     * This instance as an iterator, which it is when its class declares {@code __next__}.
     *
     * <p>{@code next(x)} asks this and {@code iter(x)} does not: CPython's {@code PyIter_Check} looks at
     * the iteration slot alone, and the class system fills that slot from {@code __next__} only.
     *
     * @return the iterator, or null when there is no {@code __next__} to drive
     */
    PyIterator declaredIterator() {
        return Specials.function(type, "__next__") == null ? null : new Walking();
    }

    /** {@code iter(self)}, driven by the {@code __next__} this class declares. */
    private final class Walking extends PyIterator {

        /** The {@code StopIteration} the step that ended the iteration was made of, kept for one read. */
        private PyBaseException stopped;

        @Override
        public PyObject nextOrNull() {
            stopped = null;
            try {
                return Specials.invoke(PyInstance.this, "__next__");
            } catch (PyRaise raised) {
                if (!raised.value().exceptionType().matches(ExceptionTypes.STOP_ITERATION)) {
                    throw raised;
                }
                // Kept rather than re-thrown: a `for` loop ends on the null, and only whoever asks for
                // the exception — `next(it)`, and nothing else here — is owed the one it was made with.
                stopped = raised.value();
                return null;
            }
        }

        @Override
        public PyBaseException exhaustion() {
            PyBaseException value = stopped;
            stopped = null;
            return value;
        }
    }

    /** The sequence protocol: index from zero up, and let the first {@code IndexError} end it. */
    private final class Indexed extends PyIterator {

        private int at;

        @Override
        public PyObject nextOrNull() {
            try {
                return Slots.getItem(PyInstance.this, PyInt.of(at++));
            } catch (PyRaise past) {
                if (!past.value().exceptionType().matches(ExceptionTypes.INDEX_ERROR)) {
                    throw past;
                }
                return null;
            }
        }
    }

    // --- identity ------------------------------------------------------------------------

    /**
     * Java equality, which is how a {@code dict} key and a {@code set} member are matched.
     *
     * <p>{@link #richCompare} already lets {@code __eq__} decide {@code ==}, but the containers go
     * through {@link PyObject#equals} after finding a bucket by {@link #hash}, and CPython's
     * {@code tp_richcompare} is what answers there too — a {@code dict} keyed by two values whose
     * {@code __eq__} agrees is one entry wide, not two.
     */
    @Override
    public boolean eq(PyObject other) {
        PyObject answer = Specials.invoke(this, "__eq__", other);
        if (Slots.declined(answer)) {
            return sameValue(other);
        }
        return Slots.truth(answer);
    }

    /**
     * {@code hash(self)}: {@code __hash__}, or the identity hash {@code object} gives.
     *
     * <p>Two refusals look alike from Python and are not alike here. A class that declares
     * {@code __eq__} and no {@code __hash__} is given {@code __hash__ = None} when it is built, so the
     * name is present and holds {@code None}, and asking it for a hash is the {@code TypeError} that
     * says unhashable. A class that declares neither has nothing under the name at all, and hashes by
     * identity like every other value.
     */
    @Override
    public long hash() {
        PyObject declared = Specials.lookUp(type, "__hash__");
        if (declared == null) {
            if (type.matches(PyType.NAMESPACE)) {
                // `SimpleNamespace` fills `tp_hash` with nothing, which CPython turns into
                // `__hash__ = None`, and a class written over it inherits that refusal.
                throw Slots.typeError("unhashable type: '%s'", type.filedName());
            }
            return System.identityHashCode(PyInstance.this);
        }
        if (!(declared instanceof PyFunction function)) {
            throw Slots.typeError("unhashable type: '%s'", type.name());
        }
        PyObject answer = function.call(new PyObject[] {PyInstance.this}, null);
        BigInteger value = Numbers.asInteger(answer);
        if (value == null) {
            throw Slots.typeError("__hash__ method should return an integer");
        }
        return value.longValue();
    }

    /**
     * {@code str(self)}: {@code __str__}, or {@code __repr__} as {@code object.__str__} falls back.
     *
     * <p>The complaint about a non-string names {@code __str__} whichever of the two produced it, which
     * is what CPython does because {@code object.__str__} calls the repr slot and {@code PyObject_Str}
     * is the one that checks the answer.
     */
    @Override
    public String str() {
        PyObject answer = displayed("__str__");
        if (answer == null) {
            answer = displayed("__repr__");
        }
        if (answer == null) {
            if (type.matches(PyType.NAMESPACE)) {
                // `object.__str__` falls back to the repr slot, and a class over `SimpleNamespace`
                // inherits `namespace_repr` rather than writing a Python-level method.
                return PyNamespace.display(type.name(), ensureInstanceDict(), this);
            }
            return defaultDisplay();
        }
        if (!(answer instanceof PyStr text)) {
            throw Slots.typeError(
                    "__str__ returned non-string (type %s)", answer.getType().name());
        }
        return text.getValue();
    }

    @Override
    public String repr() {
        PyObject answer = displayed("__repr__");
        if (answer == null) {
            if (type.matches(PyType.NAMESPACE)) {
                // `namespace_repr` is the slot a class written over `SimpleNamespace` inherits, and it
                // prints the class's own name: `Spam(a=1)`.
                return PyNamespace.display(type.name(), ensureInstanceDict(), this);
            }
            return defaultDisplay();
        }
        if (!(answer instanceof PyStr text)) {
            throw Slots.typeError(
                    "__repr__ returned non-string (type %s)", answer.getType().name());
        }
        return text.getValue();
    }

    /** What the class's own method named {@code name} answers, or null when it declares none. */
    private PyObject displayed(String name) {
        PyFunction method = Specials.function(type, name);
        return method == null ? null : method.call(new PyObject[] {this}, null);
    }

    /** CPython's {@code object.__repr__} says where the value is; an address this runtime has no use for. */
    String defaultDisplay() {
        return "<" + type.filedName() + " object at " + System.identityHashCode(this) + ">";
    }
}
