package org.python.core;

/**
 * A field of a built-in value, reached through the type: {@code sys.version_info.major}.
 *
 * <p>This is what CPython's {@code PyMemberDescr_Type} is — one object per field, kept in the type's
 * dictionary, that reads a slot of the value rather than calling anything. It is the member half of the
 * same family as a {@link PyMethodDescriptor}, and the two differ in exactly one respect: a method
 * descriptor finds a body, and this one finds an item.
 *
 * <p>Two things about it are easy to lose and both are visible from Python. It is a <em>data</em>
 * descriptor: CPython gives it a {@code tp_descr_set} even when the field is read-only, because the
 * setter is what refuses, so a field outranks an instance dictionary the way a property does. And every
 * refusal of the receiver quotes {@code tp_name}, the name the type is written down as, while the
 * qualified name it reports is the type's {@code tp_qualname} — the dotted half comes off for
 * {@code __qualname__} and stays for {@code doesn't apply to a 'sys.version_info' objects}.
 */
final class PyMemberDescriptor extends PyObject implements Descriptors.Protocol {

    private final PyType owner;
    private final String name;

    /** Which item of the value this field is, in the order the type declared its fields. */
    private final int index;

    /** The sentence CPython's {@code PyMemberDef} carries, or {@code None} where the table has none. */
    private final String doc;

    PyMemberDescriptor(PyType owner, String name, int index, String doc) {
        super(PyType.MEMBER_DESCRIPTOR);
        this.owner = owner;
        this.name = name;
        this.index = index;
        this.doc = doc;
    }

    /**
     * The field as an attribute: itself through the class, the item underneath through a value.
     *
     * <p>{@code obj} is null for the class access, which is what makes {@code type(v).major} the
     * descriptor and {@code v.major} the number.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject unused) {
        return obj == null ? this : read(checked(obj));
    }

    /**
     * {@code descr.major = value}, which CPython's {@code PyMember_SetOne} refuses before looking at
     * the value: a read-only field is read-only whatever arrives, and {@code del} is the same call with
     * nothing handed over.
     */
    @Override
    public void write(PyObject obj, PyObject value) {
        checked(obj);
        throw Slots.attributeError("readonly attribute");
    }

    /** A {@code PyMemberDef} is writable storage as far as the lookup is concerned, even read-only. */
    @Override
    public boolean writes() {
        return true;
    }

    /** {@code PyMember_GetOne} for a {@code T_OBJECT} field: the item, with nothing asked of it. */
    private PyObject read(PyObject self) {
        return ((PyTuple) self).at(index);
    }

    /** The receiver, checked against the type whose field this is. */
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
            case "__qualname__" -> PyStr.of(owner.name() + "." + name);
            case "__doc__" -> doc == null ? PyNone.INSTANCE : PyStr.of(doc);
            case "__objclass__" -> owner;
            case "__get__" -> new PyNativeFunction(
                    "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames));
            // The two halves of `tp_descr_set`, reached as the slot wrappers CPython installs them as.
            // Both refuse a keyword in the wrapper's own words and then count what arrived, but only the
            // writing half has a name to put in front of the count: `__delete__` says nothing about who
            // was asked, `__set__` says it was itself.
            case "__set__" -> new PyNativeFunction(
                    "__set__",
                    (args, kwNames) -> {
                        Arguments.noWrapperKeywords("__set__", kwNames);
                        Arguments.exactly("__set__", args, kwNames, 2);
                        write(args[0], args[1]);
                        return PyNone.INSTANCE;
                    });
            case "__delete__" -> new PyNativeFunction(
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
        return other instanceof PyMemberDescriptor one
                && one.name.equals(name)
                && one.owner == owner;
    }

    @Override
    public long hash() {
        return PyHash.textHash(owner.name() + "." + name);
    }

    @Override
    public String repr() {
        return "<member '" + name + "' of '" + owner.filedName() + "' objects>";
    }
}
