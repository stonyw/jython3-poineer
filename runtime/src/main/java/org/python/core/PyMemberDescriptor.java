package org.python.core;

/**
 * A field of a built-in value, reached through the type: {@code sys.version_info.major}, {@code
 * OSError.errno}.
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
 *
 * <p>The two kinds of offset it reaches are both here, because CPython has one descriptor type for both.
 * A named sequence reads its item out of the tuple underneath, and is read-only because every one of
 * those tables is written {@code Py_READONLY}. An exception's field is written either way: a {@code
 * _Py_T_OBJECT} takes any value and deletes by writing nothing, which is what makes {@code del
 * e.errno} silent and {@code e.errno} afterwards {@code None}, while a {@code T_BOOL} refuses both the
 * value that is not a bool and the delete, in the two sentences its setter and deleter have.
 */
final class PyMemberDescriptor extends PyObject implements Descriptors.Protocol {

    /** {@code PyMemberDef} for a field that is not an item underneath: the read of the offset. */
    interface Reader {
        PyObject read(PyObject self);
    }

    /**
     * The writing half of an offset, or null where the table declares the field read-only. {@code value}
     * is null for {@code del}, which a {@code _Py_T_OBJECT} takes by emptying the field and a {@code
     * T_BOOL} refuses.
     */
    interface Writer {
        void write(PyObject self, PyObject value);
    }

    private final PyType owner;
    private final String name;

    /** Which item of the value this field is, in the order the type declared its fields. */
    private final int index;

    /** The sentence CPython's {@code PyMemberDef} carries, or {@code None} where the table has none. */
    private final String doc;

    /** The read of an offset that is not an item, or null when {@link #index} is the field. */
    private final Reader reader;

    /** The write of an offset that has one, or null when the field is read-only. */
    private final Writer writer;

    PyMemberDescriptor(PyType owner, String name, int index, String doc) {
        this(owner, name, index, doc, null, null);
    }

    /** A field of a value that is not a sequence: {@code OSError.errno}, {@code BaseException.value}. */
    PyMemberDescriptor(PyType owner, String name, String doc, Reader reader, Writer writer) {
        this(owner, name, -1, doc, reader, writer);
    }

    private PyMemberDescriptor(
            PyType owner, String name, int index, String doc, Reader reader, Writer writer) {
        super(PyType.MEMBER_DESCRIPTOR);
        this.owner = owner;
        this.name = name;
        this.index = index;
        this.doc = doc;
        this.reader = reader;
        this.writer = writer;
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
        if (writer == null) {
            throw Slots.attributeError("readonly attribute");
        }
        writer.write(obj, value);
    }

    /** A {@code PyMemberDef} is writable storage as far as the lookup is concerned, even read-only. */
    @Override
    public boolean writes() {
        return true;
    }

    /** {@code PyMember_GetOne}: the item of a named sequence, or the answer of the offset's own read. */
    private PyObject read(PyObject self) {
        return index < 0 ? reader.read(self) : ((PyTuple) self).at(index);
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
            // The three halves of the descriptor protocol are the kind's own rows, not this field's:
            // `add_operators` copies `tp_descr_get` and `tp_descr_set` into the dictionary
            // `member_descriptor` files, and copies the writing slot twice over as the `slotdefs` entries
            // `__set__` and `__delete__`. Both of those two refuse a keyword in the wrapper's own words and
            // then count what arrived, but only the writing half has a name to put in front of the count:
            // `wrap_descr_delete` hands `PyArg_UnpackTuple` a NULL, so it says nothing about who was asked,
            // while `wrap_descr_set` names itself.
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
