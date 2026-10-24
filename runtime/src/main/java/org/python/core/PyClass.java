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

    /** The two slot storages this class's values have, told to it by the class system. */
    void storesValueDict(boolean stored) {
        valueDict = stored;
    }

    void storesWeakRefs(boolean stored) {
        weakRefs = stored;
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
            return PyMappingProxy.of(namespace);
        }
        PyObject member = classMember(attribute);
        if (member != PyNotImplemented.INSTANCE) {
            return member;
        }
        // The rest of the names a type answers for itself — `__name__`, `__base__` — are the built-in
        // type's own, and a class has nothing to say about them.
        PyObject answered = super.getAttr(attribute);
        return answered == PyNotImplemented.INSTANCE
                ? Specials.invoke(this, "__getattr__", PyStr.of(attribute))
                : answered;
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
        return "<class '" + qualifiedName() + "'>";
    }

    @Override
    public String qualifiedName() {
        return module() instanceof PyStr text
                ? text.getValue() + "." + qualName.str()
                : qualName.str();
    }
}
