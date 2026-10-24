package org.python.core;

/**
 * An attribute that is a pair of C functions rather than a slot of the value: {@code
 * BaseException.args}, {@code object.__class__}, {@code OSError.characters_written}.
 *
 * <p>CPython's {@code PyGetSetDescr_Type} is the third way a type can file an attribute — a {@code
 * PyMethodDef} answers calls, a {@code PyMemberDef} answers an offset, and a {@code PyGetSetDef} answers
 * two pointers. Which of the three a name is shows through the window over the dictionary: {@code
 * type(int.__dict__['real']).__name__} is {@code getset_descriptor} and {@code
 * type(OSError.__dict__['errno']).__name__} is {@code member_descriptor}, and the two differ in that a
 * member is storage while this is behaviour with no arguments beyond the receiver.
 *
 * <p>Two facts about it are easy to lose and both are visible from Python. It is a <em>data</em>
 * descriptor whatever it holds, because {@code PyDescr_IsData} asks the descriptor's own type for a {@code
 * tp_descr_set} and {@code getset_descriptor} always has one: a field with no setter still outranks the
 * instance dictionary and then refuses the write in its own words, which is the whole of why {@code
 * __weakref__} cannot be shadowed by a value a class instance holds. And its deleting half is the same
 * pointer with nothing handed over, which is why {@code del e.args} reaches {@code ex_del} and complains in
 * the field's own words rather than reporting a missing attribute.
 *
 * <p>The name CPython prints is {@code type->tp_name} of the class that declares it — dotted for a named
 * sequence, the last component for a class nested in another — while {@code __qualname__} is that class's
 * {@code tp_qualname}, so {@code Outer.Inner} files an attribute called {@code Inner.__dict__} whose
 * qualified name mentions {@code Outer}.
 */
final class PyGetSetDescriptor extends PyObject implements Descriptors.Protocol {

    /** {@code PyGetSetDef::get}, for the receiver this descriptor was read through. */
    interface Getter {
        PyObject read(PyObject self);
    }

    /**
     * {@code PyGetSetDef::set}: {@code value} is null for {@code del obj.name}, which CPython reaches
     * through the same pointer with {@code NULL} handed over.
     */
    interface Setter {
        void write(PyObject self, PyObject value);
    }

    private final PyType owner;
    private final String name;
    private final String doc;
    private final Getter getter;
    private final Setter setter;

    PyGetSetDescriptor(
            PyType owner, String name, String doc, Getter getter, Setter setter) {
        super(PyType.GETSET_DESCRIPTOR);
        this.owner = owner;
        this.name = name;
        this.doc = doc;
        this.getter = getter;
        this.setter = setter;
    }

    /**
     * The attribute as a read finds it: itself through the class, the answer of the getter through a
     * value.
     *
     * <p>The receiver is checked against the declaring class before anything is read, which is what makes
     * {@code int.__dict__['real'].__get__('x')} refuse in the descriptor's words rather than fail inside
     * a getter written for a number.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject unused) {
        return obj == null ? this : getter.read(checked(obj));
    }

    @Override
    public boolean writes() {
        // `PyDescr_IsData` asks the descriptor's *type* for a `tp_descr_set`, and a getset descriptor
        // always has one: the half that would write is checked later, inside `descr_set`. So `__weakref__`
        // outranks an instance dictionary that holds one, and `t.__dict__["__weakref__"] = 5` is a name
        // the read never sees.
        return true;
    }

    @Override
    public void write(PyObject obj, PyObject value) {
        checked(obj);
        if (setter == null) {
            throw Slots.attributeError(
                    "attribute '%s' of '%s' objects is not writable", name, owner.filedName());
        }
        setter.write(obj, value);
    }

    /** The receiver, checked against the type whose attribute this is. */
    private PyObject checked(PyObject self) {
        if (!owner.instanceOf(self)) {
            throw Slots.typeError(
                    "descriptor '%s' for '%s' objects doesn't apply to a '%s' object",
                    name, owner.filedName(), self.getType().filedName());
        }
        return self;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> PyStr.of(name);
            case "__qualname__" -> PyStr.of(owner.dottedName() + "." + name);
            case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
            case "__objclass__" -> owner;
            case "__get__" ->
                    new PyNativeFunction(
                            "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames));
            case "__set__" ->
                    new PyNativeFunction(
                            "__set__",
                            (args, kwNames) -> {
                                Arguments.noWrapperKeywords("__set__", kwNames);
                                Arguments.exactly("__set__", args, kwNames, 2);
                                write(args[0], args[1]);
                                return PyNone.INSTANCE;
                            });
            case "__delete__" ->
                    new PyNativeFunction(
                            "__delete__",
                            (args, kwNames) -> {
                                Arguments.noWrapperKeywords("__delete__", kwNames);
                                Arguments.exactlyUnnamed(args, 1);
                                write(args[0], null);
                                return PyNone.INSTANCE;
                            });
            default -> PyNotImplemented.INSTANCE;
        };
    }

    @Override
    public boolean eq(PyObject other) {
        return other instanceof PyGetSetDescriptor one
                && one.name.equals(name)
                && one.owner == owner;
    }

    @Override
    public long hash() {
        return PyHash.textHash(owner.name() + "." + name);
    }

    @Override
    public String repr() {
        return "<attribute '" + name + "' of '" + owner.filedName() + "' objects>";
    }
}
