package org.python.core;

/**
 * An attribute that is a computation rather than a value: {@code property}.
 *
 * <p>A property holds up to three functions — read, write, delete — and is a descriptor whose whole job
 * is to hand the value it was found on to the right one. That makes it the case where both of CPython's
 * rules about descriptors are visible at once: reading {@code m.span} runs {@code fget(m)}, and writing
 * {@code m.span = 5} runs {@code fset(m, 5)} <em>instead of</em> putting 5 in {@code m.__dict__}, because
 * a property always has a {@code tp_descr_set} and so is a data descriptor whatever it holds. The
 * consequence is that assigning to a getter-only property is an {@code AttributeError} rather than a
 * shadow, which is the message this class has to word itself.
 *
 * <p>The name in that message is the one {@code __set_name__} was given, which is why the complaint reads
 * {@code property 'fixed' of 'Measured' object has no setter} rather than merely {@code property of ...}.
 * A property built by hand and never placed in a class falls back to its getter's own {@code __name__} —
 * to that one function only, as {@code property_name} is written — and a property with neither has no
 * name to read at all, which is a refusal rather than {@code None}.
 *
 * <p>Everything a program can reach of this struct is a row of {@code property}'s own dictionary: the
 * three functions are {@code PyMemberDef}s that cannot be written, the docstring is the fourth that can,
 * and the two names beside them are {@code PyGetSetDef}s — one with a setter of its own and one without.
 * {@code property_init} is the only route to the fields, which is what makes {@code p.__init__(f)} a
 * refill of the same object rather than a change of one half: the docstring it files is either the {@code
 * doc} argument or, when that says nothing, the getter's, and the flag that records which decides whether
 * a later {@code p.getter(g)} keeps that sentence or goes to get a new one from {@code g}.
 */
public final class PyProperty extends PyObject implements Descriptors.Protocol {

    /** {@code property_init}'s parameters, in the order its keyword table lists them. */
    static final String[] PARAMETERS = {"fget", "fset", "fdel", "doc"};

    /** Which of the three functions one of the two decorator rows replaces. */
    static final int READ = 0;
    static final int WRITE = 1;
    static final int REMOVE = 2;

    /** {@code prop_get}, {@code prop_set}, {@code prop_del}: null is the half that is not there. */
    private PyObject getter;
    private PyObject setter;
    private PyObject deleter;

    /** {@code prop_doc}: null, and {@code _Py_T_OBJECT} reads it as {@code None}. */
    private PyObject documentation;

    /** {@code prop_name}: whatever {@code __set_name__} or the getset last handed over. */
    private PyObject name;

    /** {@code getter_doc}: whether the sentence came off the getter rather than from a caller. */
    private boolean getterDoc;

    /** {@code PyType_GenericNew}: the struct {@code property.__new__(property)} hands back empty. */
    PyProperty() {
        super(PyType.PROPERTY);
    }

    /** {@code property(fget, fset, fdel, doc)}: a fresh value, then {@code tp_init} over it. */
    PyProperty(PyObject getter, PyObject setter, PyObject deleter, PyObject doc) {
        this();
        refill(getter, setter, deleter, doc);
    }

    /**
     * {@code property_init_impl}: every field emptied, then four of them filled again.
     *
     * <p>{@code None} and an argument the call left out are the same thing here, and {@code doc=None} does
     * not mean "no docstring" but "take the getter's" — which is the only place the flag below is raised.
     * A name the getter cannot answer, because it is {@code None} or has no {@code __doc__} of its own,
     * leaves the docstring null rather than filing {@code None} in it.
     */
    void refill(PyObject fget, PyObject fset, PyObject fdel, PyObject doc) {
        getter = present(fget);
        setter = present(fset);
        deleter = present(fdel);
        documentation = null;
        name = null;
        getterDoc = false;
        if (doc != null && doc != PyNone.INSTANCE) {
            documentation = doc;
            return;
        }
        if (getter == null) {
            return;
        }
        // The getter's own sentence, read with `PyObject_GetOptionalAttr` and so not a complaint when the
        // function has nothing under the name — and `None` is what a function with no docstring says, which
        // is not a sentence either.
        PyObject found = Slots.findAttr(getter, "__doc__");
        if (found != null && found != PyNone.INSTANCE) {
            documentation = found;
            getterDoc = true;
        }
    }

    /**
     * {@code property_copy}: the three functions with one of them replaced, put through a fresh init.
     *
     * <p>A replacement that is {@code None} — or the argument a {@code .getter(f)} call did not bring — is
     * the field it replaces, so {@code p.setter(f)} keeps both the getter and the deleter. The docstring is
     * the one place the copy does not simply carry the old value across: when that value was the getter's
     * own, the copy hands {@code None} as the {@code doc} argument so that the init goes and asks whichever
     * getter it now has. A sentence a person wrote into {@code property(..., doc=...)} is theirs, and is
     * kept whichever half of the property is swapped.
     */
    PyProperty copied(PyObject replacement, int which) {
        PyObject get = kept(which == READ ? replacement : null, getter);
        PyObject set = kept(which == WRITE ? replacement : null, setter);
        PyObject del = kept(which == REMOVE ? replacement : null, deleter);
        PyObject doc = getterDoc && get != PyNone.INSTANCE ? PyNone.INSTANCE : orNone(documentation);
        PyProperty copy = new PyProperty();
        copy.refill(get, set, del, doc);
        copy.name = name;
        return copy;
    }

    /** {@code property_copy}'s first three blocks: the new function, or the one the property already had. */
    private static PyObject kept(PyObject replacement, PyObject previous) {
        return replacement == null || replacement == PyNone.INSTANCE
                ? orNone(previous)
                : replacement;
    }

    /** {@code None} and an omitted argument are the same thing to CPython's property. */
    private static PyObject present(PyObject function) {
        return function == null || function == PyNone.INSTANCE ? null : function;
    }

    /** What Python reads an absent half of the property: {@code None}, and never nothing at all. */
    private static PyObject orNone(PyObject field) {
        return field == null ? PyNone.INSTANCE : field;
    }

    /** {@code fget}, {@code fset} and {@code fdel}: the three {@code Py_READONLY} members. */
    PyObject function(int which) {
        return orNone(which == READ ? getter : which == WRITE ? setter : deleter);
    }

    /** {@code __doc__} as the writable member reads it, with the null field answering {@code None}. */
    PyObject documentation() {
        return orNone(documentation);
    }

    /** {@code p.__doc__ = text} and {@code del p.__doc__}, both through the same offset. */
    void writeDocumentation(PyObject value) {
        documentation = value;
    }

    /** {@code __name__}: {@code property_name}, and a refusal where that finds nothing to answer. */
    PyObject nameValue() {
        PyObject found = nameOrNull();
        if (found == null) {
            throw Slots.attributeError(
                    "'%s' object has no attribute '%s'", getType().filedName(), "__name__");
        }
        return found;
    }

    /** {@code property_set__name__}, which stores whatever arrived and takes null for the delete. */
    void writeName(PyObject value) {
        name = value;
    }

    /** {@code property_get___isabstractmethod__}: the first of the three functions that says it is one. */
    PyObject abstractFlag() {
        for (PyObject function : new PyObject[] {getter, setter, deleter}) {
            if (isAbstract(function)) {
                return PyBool.TRUE;
            }
        }
        return PyBool.FALSE;
    }

    /** {@code _PyObject_IsAbstract} of one function: the name read off it like any other, and judged. */
    private static boolean isAbstract(PyObject function) {
        if (function == null) {
            return false;
        }
        PyObject answer = Slots.findAttr(function, "__isabstractmethod__");
        return answer != null && answer != PyNone.INSTANCE && Slots.truth(answer);
    }

    /**
     * The property as an attribute: itself through the class, the getter's answer through a value.
     *
     * <p>Reading it off the class has to hand the property back, both because that is what {@code C.span}
     * and {@code type(C.span).__name__} answer and because it is how a subclass's {@code @span.setter}
     * finds the property it is meant to copy. {@code property_descr_get} asks the same question of {@code
     * None} as of a class that was never given, which is why {@code p.__get__(None, int)} and {@code
     * None.span} both answer the property rather than running its getter.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        if (obj == null || obj == PyNone.INSTANCE) {
            return this;
        }
        if (getter == null) {
            throw Slots.attributeError(
                    "property%s of '%s' object has no getter", named(), obj.getType().dottedName());
        }
        return Slots.call(getter, new PyObject[] {obj}, null);
    }

    /** A property always has a writing half, even when it holds no function behind it. */
    @Override
    public boolean writes() {
        return true;
    }

    /**
     * {@code obj.name = value}, or {@code del obj.name} when {@code value} is null.
     *
     * <p>The two refusals are worded alike with only the last name swapped, and name the value's own class
     * rather than the property's, which is what makes the sentence useful to whoever wrote the class. It is
     * the class as it is qualified, because CPython takes {@code PyType_GetQualName} for it: a metaclass
     * defined inside a function reports {@code make.<locals>.Meta} there.
     */
    @Override
    public void write(PyObject obj, PyObject value) {
        PyObject function = value == null ? deleter : setter;
        if (function == null) {
            throw Slots.attributeError(
                    "property%s of '%s' object has no %s",
                    named(),
                    obj.getType().dottedName(),
                    value == null ? "deleter" : "setter");
        }
        Slots.call(
                function,
                value == null ? new PyObject[] {obj} : new PyObject[] {obj, value},
                null);
    }

    /** {@code __set_name__}: the name the class dictionary filed this property under. */
    void setName(PyObject name) {
        this.name = name;
    }

    /** {@code property_name}: the name that was given, else the getter's own, else nothing. */
    private PyObject nameOrNull() {
        return name != null ? name : getter == null ? null : Slots.findAttr(getter, "__name__");
    }

    /** The name as the complaint quotes it — with its leading space, or nothing when there is none. */
    private String named() {
        PyObject found = nameOrNull();
        return found == null ? "" : " " + found.repr();
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }
}
