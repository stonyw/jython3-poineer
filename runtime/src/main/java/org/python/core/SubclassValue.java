package org.python.core;

import java.math.BigInteger;

/**
 * The attribute and display half of a value whose class Python wrote under an immutable built-in.
 *
 * <p>{@link PyIntSubclass} and {@link PyStrSubclass} already hold a value and inherit every arithmetic
 * or text slot from {@link PyInt} and {@link PyStr}; what they do not inherit is the storage a class
 * statement promises. This is that storage's reader and writer, in {@link PyInstance}'s order: a
 * descriptor that writes, then the instance dictionary, then the class and its bases — including the
 * built-in base's own table, which {@link Specials#lookUp} cannot see because it walks only the class
 * namespaces — then {@code __class__} and {@code __getattr__}.
 *
 * <p>The display pair is here too, and for the same reason: {@code str(self)} asks the class before it
 * asks the value, since a class under {@code int} may write {@code __str__} and expect it to win.
 */
final class SubclassValue {

    private SubclassValue() {
    }

    /** {@code self.name}, in {@link PyInstance}'s order. */
    static PyObject getAttr(PyObject self, PyDict store, String name) {
        PyType type = self.getType();
        PyObject member = Specials.lookUp(type, name);
        if (member != null && Descriptors.writes(member)) {
            return Descriptors.get(member, self, type);
        }
        PyObject held = store == null ? null : store.get(PyStr.interned(name));
        if (held != null) {
            return held;
        }
        if (member != null) {
            return Descriptors.get(member, self, type);
        }
        PyObject inherited = Members.find(self, name);
        if (inherited == Slots.DECLINED) {
            inherited = TypeDict.read(self, name);
        }
        if (!Slots.declined(inherited)) {
            return inherited;
        }
        return name.equals("__class__")
                ? type
                : Specials.invoke(self, "__getattr__", PyStr.of(name));
    }

    /** {@code self.name = value}: the class's {@code __setattr__}, or the generic write. */
    static PyObject setAttr(PyObject self, String name, PyObject value) {
        PyObject hook = Specials.lookUp(self.getType(), "__setattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {self, PyStr.of(name), value}, null);
        }
        GenericAttributes.write(self, name, value);
        return PyNone.INSTANCE;
    }

    /** {@code del self.name}: the class's {@code __delattr__}, or the generic delete. */
    static PyObject delAttr(PyObject self, String name) {
        PyObject hook = Specials.lookUp(self.getType(), "__delattr__");
        if (hook instanceof PyFunction function) {
            return function.call(new PyObject[] {self, PyStr.of(name)}, null);
        }
        GenericAttributes.delete(self, name);
        return PyNone.INSTANCE;
    }

    /** Whether the class, rather than {@code object}, owns writes under {@code name}. */
    static boolean filedByItsClass(PyType type, String name) {
        return Specials.lookUp(type, name) != null
                || Specials.lookUp(type, "__setattr__") != null
                || Specials.lookUp(type, "__delattr__") != null;
    }

    /** {@code str(self)}: {@code __str__}, then {@code __repr__}, then the value's own text. */
    static String str(PyObject self, java.util.function.Supplier<String> fallback) {
        PyObject answer = displayed(self, "__str__");
        if (answer == null) {
            answer = displayed(self, "__repr__");
        }
        if (answer == null) {
            return fallback.get();
        }
        if (!(answer instanceof PyStr text)) {
            throw Slots.typeError(
                    "__str__ returned non-string (type %s)", answer.getType().name());
        }
        return text.getValue();
    }

    /** {@code repr(self)}: {@code __repr__}, then the value's own text. */
    static String repr(PyObject self, java.util.function.Supplier<String> fallback) {
        PyObject answer = displayed(self, "__repr__");
        if (answer == null) {
            return fallback.get();
        }
        if (!(answer instanceof PyStr text)) {
            throw Slots.typeError(
                    "__repr__ returned non-string (type %s)", answer.getType().name());
        }
        return text.getValue();
    }

    /**
     * {@code hash(self)}: the first {@code __hash__} in the order the class's bases are walked, which is
     * the order the slot itself resolves in.
     *
     * <p>That order is the whole point of {@code IntEnum}: {@code int} stands ahead of {@code Enum} in
     * its line, so the value's hash answers and {@code Enum.__hash__}, further down, never runs. A class
     * that writes {@code __hash__ = None} is unhashable, and one that writes a function has it called
     * with the value.
     */
    static long hash(PyObject self, java.util.function.LongSupplier fallback) {
        PyType type = self.getType();
        for (PyType owner : type.mro()) {
            PyObject declared =
                    owner instanceof PyClass wrote
                            ? wrote.namespace().get(PyStr.interned("__hash__"))
                            : null;
            if (declared == null) {
                // The built-in base's own `__hash__` is a slot of the value, not a name in a dictionary.
                if (owner == PyType.INT || owner == PyType.STR || owner == PyType.TUPLE) {
                    return fallback.getAsLong();
                }
                continue;
            }
            if (!(declared instanceof PyFunction function)) {
                throw Slots.typeError("unhashable type: '%s'", type.name());
            }
            BigInteger value = Numbers.asInteger(function.call(new PyObject[] {self}, null));
            if (value == null) {
                throw Slots.typeError("__hash__ method should return an integer");
            }
            return value.longValue();
        }
        return fallback.getAsLong();
    }

    /** {@code self == other}: the class's {@code __eq__} when it declares one, else the value's. */
    static boolean eq(
            PyObject self, PyObject other, java.util.function.BooleanSupplier fallback) {
        PyObject answer = Specials.invoke(self, "__eq__", other);
        return Slots.declined(answer) ? fallback.getAsBoolean() : Slots.truth(answer);
    }

    /** {@code self(...)}: only a class that declares {@code __call__} can be called. */
    static PyObject call(PyObject self, PyObject[] args, PyTuple kwNames) {
        PyObject declared = Specials.member(self.getType(), "__call__");
        if (declared == null) {
            throw Slots.typeError("'%s' object is not callable", self.getType().name());
        }
        if (declared instanceof PyMethodDescriptor unbound) {
            PyObject[] withSelf = new PyObject[args.length + 1];
            withSelf[0] = self;
            System.arraycopy(args, 0, withSelf, 1, args.length);
            return unbound.call(withSelf, kwNames);
        }
        return Slots.call(Descriptors.get(declared, self, self.getType()), args, kwNames);
    }

    /** Whether the class files a {@code __call__}, whatever stands under the name. */
    static boolean isCallable(PyObject self) {
        return Specials.member(self.getType(), "__call__") != null;
    }

    /**
     * The {@code __str__} or {@code __repr__} the class files, first in lookup order and of whatever
     * kind.
     *
     * <p>The kind matters: {@code enum} gives {@code IntEnum} {@code __str__ = int.__repr__}, a built-in
     * descriptor, and a search that only accepted Python functions would skip past it to
     * {@code Enum.__str__} and print {@code <Size.S: 1>} where CPython prints {@code 1}.
     */
    private static PyObject displayed(PyObject self, String name) {
        PyObject declared = Specials.lookUp(self.getType(), name);
        if (declared == null) {
            return null;
        }
        return Slots.call(Descriptors.get(declared, self, self.getType()), new PyObject[0], null);
    }
}
