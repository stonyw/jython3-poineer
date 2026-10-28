package org.python.core;

import java.math.BigInteger;

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
public final class PyInstance extends PyObject {

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
     * <p>A class that declares {@code __slots__} without {@code __dict__} among them has no use for this
     * one, and CPython gives its values none at all; here the dictionary is still there but nothing reaches
     * it, because {@link GenericAttributes} asks {@link PyType#hasValueDict()} rather than whether this
     * field is null.
     */
    private PyDict dictionary;

    PyInstance(PyType kind) {
        super(kind);
        this.dictionary = kind == PyType.OBJECT ? null : PyDict.empty();
    }

    /** {@code subtype_dict}: the read of the descriptor a class files for the storage it introduces. */
    static PyObject valueDict(PyObject self) {
        PyInstance held = (PyInstance) self;
        if (held.dictionary == null) {
            held.dictionary = PyDict.empty();
        }
        return held.dictionary;
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
        PyInstance held = (PyInstance) self;
        if (value == null) {
            held.dictionary = PyDict.empty();
            return;
        }
        if (!(value instanceof PyDict replacement)) {
            throw Slots.typeError(
                    "__dict__ must be set to a dictionary, not a '%s'", value.getType().filedName());
        }
        held.dictionary = replacement;
    }

    /**
     * The values of the fields this class declared in {@code __slots__}, kept apart from {@code __dict__}
     * because CPython keeps them apart too: {@code __slots__ = ("__dict__", "b")} stores {@code b} in an
     * offset beside the dictionary, so setting it leaves the name out of {@code vars(self)} all the same.
     */
    private PyDict slotValues;

    @Override
    PyObject slotValue(String name) {
        PyObject held = slotValues == null ? null : slotValues.get(PyStr.interned(name));
        if (held == null) {
            throw Slots.attributeError(
                    "'%s' object has no attribute '%s'", getType().filedName(), name);
        }
        return held;
    }

    @Override
    void writeSlot(String name, PyObject value) {
        PyStr key = PyStr.interned(name);
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
            slotValues = PyDict.empty();
        }
        slotValues.put(key, value);
    }

    // --- attributes ----------------------------------------------------------------------

    /**
     * {@code self.name}, in CPython's order: a descriptor that writes, then the instance's own
     * dictionary, then the class and its bases, then {@code __getattr__}.
     *
     * <p>The class is consulted first and the answer run through {@link Descriptors} because that is
     * what the descriptor protocol is: a function found there comes back bound, which is the whole of how
     * {@code p.as_text()} passes {@code p} as {@code self} without anyone writing that down, and a member
     * that can write owns the name outright. A member that can only read is the one thing the dictionary
     * outranks, which is what lets {@code a.quiet = "mine"} shadow it.
     *
     * <p>{@code __dict__} is answered by that walk rather than here, by the {@code getset_descriptor}
     * {@link Classes} files on the class that introduces the storage. A class that files no descriptor —
     * one under {@code object} that declares {@code __slots__}, or {@code object} itself — therefore has no
     * {@code __dict__} to read even though this runtime has not taken its dictionary away from it, which is
     * the named deviation about slot layout wearing a second hat.
     */
    @Override
    public PyObject getAttr(String name) {
        return owned(name);
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
        PyObject member = Specials.lookUp(type, name);
        if (member != null && Descriptors.writes(member)) {
            return Descriptors.get(member, this, type);
        }
        PyObject held = dictionary == null ? null : dictionary.get(PyStr.interned(name));
        if (held != null) {
            return held;
        }
        if (member != null) {
            return Descriptors.get(member, this, type);
        }
        return name.equals("__class__")
                ? type
                : Specials.invoke(this, "__getattr__", PyStr.of(name));
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
        PyObject hook = Specials.lookUp(type, "__setattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name), value}, null);
        }
        GenericAttributes.write(this, name, value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code del self.name}, the other half of {@link #setAttr} and the same two-step: the class's {@code
     * __delattr__} if it wrote one, {@link GenericAttributes} otherwise.
     */
    @Override
    public PyObject delAttr(String name) {
        PyObject hook = Specials.lookUp(type, "__delattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name)}, null);
        }
        GenericAttributes.delete(this, name);
        return PyNone.INSTANCE;
    }

    /** {@code _PyObject_GetManagedDict}: the dictionary {@link GenericAttributes} writes through. */
    @Override
    PyDict attributeStore() {
        return dictionary;
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
            if (custom != PyNotImplemented.INSTANCE) {
                return custom;
            }
            PyObject equal = Specials.invoke(this, "__eq__", other);
            return equal == PyNotImplemented.INSTANCE
                    ? PyBool.of(this != other)
                    : PyBool.of(!Slots.truth(equal));
        }
        PyObject answer = Specials.invoke(this, cmp.dunder(), other);
        if (answer != PyNotImplemented.INSTANCE) {
            return answer;
        }
        return cmp == CompareOp.EQ ? PyBool.of(this == other) : PyNotImplemented.INSTANCE;
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
     */
    @Override
    public PyObject iterator() {
        PyObject walking = Specials.invoke(this, "__iter__");
        if (walking != PyNotImplemented.INSTANCE) {
            return walking;
        }
        return Specials.function(type, "__getitem__") == null
                ? PyNotImplemented.INSTANCE
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
        if (answer == PyNotImplemented.INSTANCE) {
            return this == other;
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
    private String defaultDisplay() {
        return "<" + type.name() + " object at " + System.identityHashCode(this) + ">";
    }
}
