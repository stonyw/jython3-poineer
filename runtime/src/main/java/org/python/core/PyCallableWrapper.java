package org.python.core;

/**
 * The half of {@code staticmethod} and {@code classmethod} that is the same C struct: a callable and a
 * dictionary.
 *
 * <p>{@code funcobject.c} declares {@code {PyObject *callable; PyObject *dict;}} twice over, and the two
 * halves behave alike wherever they are not the descriptor itself. The callable starts as {@code None}
 * because {@code sm_new} and {@code cm_new} fill it with nothing — {@code staticmethod.__new__(staticmethod)}
 * is a wrapper around {@code None} that only {@code __init__} fills later — and the dictionary starts
 * unmade, because {@code PyObject_GenericGetDict} is what makes it on the first read. That laziness is
 * visible: {@code vars(staticmethod(f))} holds the four names {@code functools_wraps} copied and nothing
 * else, while a wrapper nobody ever asked has an empty one.
 *
 * <p>The copy is the reason a wrapper has a dictionary at all. {@code sm_init} and {@code cm_init} set the
 * callable and then run {@code functools_copy_attr} over {@code __module__, __name__, __qualname__,
 * __doc__}, each read with {@code PyObject_GetOptionalAttr} and so skipped when the wrapped value has
 * nothing under it — which is why {@code staticmethod(5).__dict__} holds only a {@code __doc__}, and why
 * the four are <em>copies</em>: renaming the function afterwards leaves the wrapper's {@code __name__}
 * where it was. A third way makes these objects and does not copy at all: {@code PyStaticMethod_New} is
 * what a {@code METH_STATIC} row of a built-in's method table is built with, and such a wrapper answers no
 * name of its own.
 *
 * <p>The reading order is CPython's generic one, and it is the order that decides three measured answers:
 * a member that can write outranks the dictionary, the dictionary outranks a member that only reads, and
 * {@code __doc__} is the value the type files rather than a descriptor — so {@code del wrapper.__doc__}
 * uncovers the class's sentence again instead of removing it. {@code __annotations__} and {@code
 * __annotate__} are the two names with a third rule, in {@link #wrappedAttribute}: the dictionary first,
 * then the callable, with the answer cached into the dictionary so the next read never asks it again.
 */
abstract class PyCallableWrapper extends PyObject implements Descriptors.Protocol {

    /** {@code functools_wraps}' four names, in the order it copies them. */
    private static final String[] WRAPPED_NAMES = {
        "__module__", "__name__", "__qualname__", "__doc__"
    };

    /** {@code sm_callable} / {@code cm_callable}, {@code None} until {@code __init__} fills it. */
    private PyObject callable = PyNone.INSTANCE;

    /** {@code sm_dict} / {@code cm_dict}, made by the first read or write that needs one. */
    private PyDict dictionary;

    PyCallableWrapper(PyType kind) {
        super(kind);
    }

    /** The callable the wrapper holds — {@code __func__} and, for these two kinds, {@code __wrapped__}. */
    PyObject wrapped() {
        return callable;
    }

    /** {@code PyStaticMethod_New} / {@code PyClassMethod_New}: the field, with no copy of the names. */
    void attach(PyObject function) {
        callable = function;
    }

    /**
     * {@code sm_init} / {@code cm_init} past its two gates: the field, then the wrapped one's identity.
     *
     * <p>Written through {@link Slots#setAttr} rather than put into {@link #held()} because that is what
     * {@code PyObject_SetAttr} is, and a subclass of a wrapper that files a property under one of the four
     * names is asked for it.
     */
    void initialise(PyObject function) {
        attach(function);
        for (String name : WRAPPED_NAMES) {
            PyObject value = Slots.findAttr(function, name);
            if (value != null) {
                Slots.setAttr(this, name, value);
            }
        }
    }

    /** {@code PyObject_GenericGetDict}: the dictionary, made on the way if it was never made. */
    final PyDict held() {
        if (dictionary == null) {
            dictionary = PyDict.empty();
        }
        return dictionary;
    }

    @Override
    final PyDict attributeStore() {
        return held();
    }

    /** {@code PyObject_GenericSetDict}, and {@code del wrapper.__dict__} through the same pointer. */
    void replaceDict(PyObject value) {
        if (value == null) {
            throw Slots.typeError("cannot delete __dict__");
        }
        if (!(value instanceof PyDict replacement)) {
            throw Slots.typeError(
                    "__dict__ must be set to a dictionary, not a '%s'", value.getType().filedName());
        }
        dictionary = replacement;
    }

    /**
     * {@code wrapper.name}: CPython's four-step generic read over this value's class and its own
     * dictionary.
     *
     * <p>Nothing here is asked of the dictionary before the class is asked whether it owns the name, which
     * is what makes the two {@code Py_READONLY} members take {@code __func__} away from anything a program
     * writes under it, and what lets a name the type files as a plain value be shadowed by the dictionary.
     */
    @Override
    public PyObject getAttr(String name) {
        PyType kind = getType();
        PyObject member = Specials.member(kind, name);
        if (member != null && Descriptors.writes(member)) {
            return Descriptors.get(member, this, kind);
        }
        PyObject kept = dictionary == null ? null : dictionary.get(PyStr.interned(name));
        if (kept != null) {
            return kept;
        }
        return member == null ? PyNotImplemented.INSTANCE : Descriptors.get(member, this, kind);
    }

    /**
     * {@code descriptor_get_wrapped_attribute}: the entry, or the callable's own answer cached into it.
     *
     * <p>The read that fills the cache is {@code PyObject_GetAttr} and not an optional one, so a callable
     * with nothing under the name complains about itself — {@code staticmethod(5).__annotations__} is
     * {@code AttributeError: 'int' object has no attribute '__annotations__'} whatever the wrapper holds.
     */
    final PyObject wrappedAttribute(String name) {
        PyDict store = held();
        PyStr key = PyStr.interned(name);
        PyObject kept = store.get(key);
        if (kept != null) {
            return kept;
        }
        PyObject value = Slots.getAttr(callable, name);
        store.put(key, value);
        return value;
    }

    /**
     * {@code descriptor_set_wrapped_attribute}: the dictionary, and nothing else.
     *
     * <p>The write never reaches the callable, and neither does the delete — which is why a wrapper's
     * {@code __annotations__} can be set, unset and then read back as the function's own again. A delete
     * of a name that was never written is {@code PyDict_DelItem}'s {@code KeyError} turned into the
     * attribute complaint, in the wrapper's words and not the callable's.
     */
    final void writeWrappedAttribute(String name, PyObject value) {
        PyDict store = held();
        PyStr key = PyStr.interned(name);
        if (value == null) {
            if (store.get(key) == null) {
                throw Slots.attributeError(
                        "'%s' object has no attribute '%s'", getType().name(), name);
            }
            store.remove(key);
            return;
        }
        store.put(key, value);
    }

    /**
     * {@code _PyObject_IsAbstract} of the callable: the name read off it like any other, and judged.
     *
     * <p>Nothing here is asked of the callable's type rather than of the callable, which is what lets a class
     * that holds {@code True} under the name answer true while a class that holds {@code None} answers false —
     * and a wrapper around {@code None}, the state {@code __new__} leaves, answers false too. A name nothing
     * answers at all is false rather than a complaint, which is why {@code staticmethod(5)} says false where
     * {@code staticmethod(5).__annotations__} complains.
     */
    final PyObject abstractFlag() {
        PyObject answer = Slots.findAttr(callable, "__isabstractmethod__");
        if (answer == null || answer == PyNone.INSTANCE) {
            return PyBool.FALSE;
        }
        return Slots.truth(answer) ? PyBool.TRUE : PyBool.FALSE;
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    @Override
    public String repr() {
        return "<" + getType().name() + "(" + callable.repr() + ")>";
    }
}
