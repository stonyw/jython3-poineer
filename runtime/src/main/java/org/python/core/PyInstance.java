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

    /** {@code self.__dict__}: the attributes this one value holds, which a class does not. */
    private final PyDict dictionary = PyDict.empty();

    PyInstance(PyClass kind) {
        super(kind);
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
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "__dict__" -> dictionary;
            default -> owned(name);
        };
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
        PyObject held = dictionary.get(PyStr.interned(name));
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
     * {@code self.name = value}, which a descriptor or a {@code __setattr__} may take over.
     *
     * <p>The method is called rather than consulted for advice, and it is the class's declaration that
     * decides whether the instance dictionary is written at all — which is why a class that logs its
     * assignments has to write to {@code self.__dict__} itself to store anything. Without one, the
     * dictionary is the fallback only for a name no member of the class owns: {@code thing.keep = 7} goes
     * to {@code Kept.__set__} and leaves {@code thing.__dict__} empty.
     */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        PyObject hook = Specials.lookUp(type, "__setattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name), value}, null);
        }
        PyObject member = Specials.lookUp(type, name);
        if (member != null && Descriptors.writes(member)) {
            Descriptors.set(member, this, value);
            return PyNone.INSTANCE;
        }
        dictionary.put(PyStr.interned(name), value);
        return PyNone.INSTANCE;
    }

    /**
     * {@code del self.name}, the other half of {@link #setAttr}.
     *
     * <p>A descriptor that owns the name is asked before the dictionary, exactly as it is when writing,
     * and the {@code AttributeError} naming the value's class is what CPython says when the name is on the
     * class but not on the value — an attribute the instance does not have, rather than one that is merely
     * not deletable from here.
     */
    @Override
    public PyObject delAttr(String name) {
        PyObject hook = Specials.lookUp(type, "__delattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {this, PyStr.of(name)}, null);
        }
        PyObject member = Specials.lookUp(type, name);
        if (member != null && Descriptors.writes(member)) {
            Descriptors.delete(member, this);
            return PyNone.INSTANCE;
        }
        PyStr key = PyStr.interned(name);
        if (dictionary.get(key) == null) {
            throw Slots.attributeError("'%s' object has no attribute '%s'", type.name(), name);
        }
        dictionary.remove(key);
        return PyNone.INSTANCE;
    }

    // --- calls ---------------------------------------------------------------------------

    /** {@code self(...)}: only a class that declares {@code __call__} can be called. */
    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        return Specials.invoke(this, "__call__", args, kwNames);
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

    @Override
    public PyObject length() {
        return Specials.invoke(this, "__len__");
    }

    @Override
    public PyObject truth() {
        return Specials.invoke(this, "__bool__");
    }

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

        @Override
        public PyObject nextOrNull() {
            try {
                return Specials.invoke(PyInstance.this, "__next__");
            } catch (PyRaise stopped) {
                if (!stopped.value().exceptionType().matches(ExceptionTypes.STOP_ITERATION)) {
                    throw stopped;
                }
                return null;
            }
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
