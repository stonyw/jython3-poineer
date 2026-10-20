package org.python.core;

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

    /** The bases as {@code type} received them, already defaulted to {@code (object,)} when empty. */
    private final PyTuple bases;

    private PyStr qualName;

    PyClass(
            String name,
            PyTuple bases,
            PyDict namespace,
            String qualName,
            PyType metatype) {
        super(name, bases.size() == 0 ? PyType.OBJECT : (PyType) bases.at(0));
        this.type = metatype;
        this.bases = bases;
        this.namespace = namespace;
        this.qualName = PyStr.of(qualName);
    }

    /** The dictionary the class body filled, which is where {@link Specials} reads a declaration. */
    PyDict namespace() {
        return namespace;
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
     * class that never mentions the name.
     */
    @Override
    public PyObject getAttr(String attribute) {
        PyObject identity =
                switch (attribute) {
                    case "__qualname__" -> qualName;
                    case "__bases__" -> bases;
                    case "__mro__" -> PyTuple.of(mro());
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
            return namespace;
        }
        PyObject member = classMember(attribute);
        // The rest of the names a type answers for itself — `__name__`, `__base__` — are the
        // built-in type's own, and a class has nothing to say about them.
        return member == PyNotImplemented.INSTANCE ? super.getAttr(attribute) : member;
    }

    /**
     * {@code Point.kind = 'point'}, and {@code Point.__name__ = 'Other'}.
     *
     * <p>Writing into the dictionary is what makes both a re-assignment of an inherited name and a new
     * class attribute land where the lookup will find it — and where {@code del} can remove it again,
     * which is how a shadow is taken back off.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        return switch (attribute) {
            case "__name__" -> {
                setName(text(attribute, value));
                yield PyNone.INSTANCE;
            }
            case "__qualname__" -> {
                qualName = PyStr.of(text(attribute, value));
                yield PyNone.INSTANCE;
            }
            default -> {
                namespace.put(PyStr.interned(attribute), value);
                yield PyNone.INSTANCE;
            }
        };
    }

    /** {@code del Point.kind}, which only removes what this class's own dictionary holds. */
    @Override
    public PyObject delAttr(String attribute) {
        // The two names that are part of the type's identity are not in the dictionary to take away,
        // and CPython refuses their deletion as a type's, not as an attribute's.
        if (attribute.equals("__name__") || attribute.equals("__qualname__")) {
            throw Slots.typeError(
                    "cannot delete '%s' attribute of immutable type '%s'", attribute, name());
        }
        if (namespace.remove(PyStr.interned(attribute)) == null) {
            throw Slots.attributeError(
                    "type object '%s' has no attribute '%s'", name(), attribute);
        }
        return PyNone.INSTANCE;
    }

    /**
     * The name the type is called by, which CPython requires to be text.
     *
     * {@code can only assign string to Point.__name__, not 'int'} — the only place the class system
     * refuses a value rather than storing it and letting a later read complain.
     */
    private String text(String attribute, PyObject value) {
        if (!(value instanceof PyStr name)) {
            throw Slots.typeError(
                    "can only assign string to %s.%s, not '%s'", name(), attribute, value.getType().name());
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
        return "<class '" + qualifiedName() + "'>";
    }

    @Override
    public String qualifiedName() {
        return module() instanceof PyStr text
                ? text.getValue() + "." + qualName.str()
                : qualName.str();
    }
}
