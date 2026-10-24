package org.python.core;

/**
 * An attribute that is a computation rather than a value: {@code property}.
 *
 * <p>A property holds up to three functions — read, write, delete — and is a descriptor whose whole
 * job is to hand the value it was found on to the right one. That makes it the case where both of
 * CPython's rules about descriptors are visible at once: reading {@code m.span} runs {@code fget(m)},
 * and writing {@code m.span = 5} runs {@code fset(m, 5)} <em>instead of</em> putting 5 in
 * {@code m.__dict__}, because a property always has a {@code tp_descr_set} and so is a data descriptor
 * whatever it holds. The consequence is that assigning to a getter-only property is an
 * {@code AttributeError} rather than a shadow, which is the message this class has to word itself.
 *
 * <p>The name in that message is the one {@code __set_name__} was given, which is why the complaint
 * reads {@code property 'fixed' of 'Measured' object has no setter} rather than merely
 * {@code property of ...}. A property built by hand and never placed in a class falls back to the first
 * of its functions' own {@code __name__}, as CPython's {@code property_get_name} does, and a property
 * with neither has no {@code __name__} to read at all.
 */
public final class PyProperty extends PyObject implements Descriptors.Protocol {

    private final PyObject getter;
    private final PyObject setter;
    private final PyObject deleter;

    /** The text Python reads as {@code __doc__}: the {@code doc} argument, or the getter's own. */
    private final PyObject documentation;

    /** What {@code __set_name__} reported, or null while the property has not been placed in a class. */
    private PyObject name;

    /**
     * The four arguments of {@code property(fget, fset, fdel, doc)}, where an absent argument and
     * {@code None} are the same thing to CPython: that half of the property is missing, and — for the
     * docstring — that it should be taken from the getter instead.
     */
    PyProperty(PyObject getter, PyObject setter, PyObject deleter, PyObject doc) {
        this(getter, setter, deleter, doc == null || doc == PyNone.INSTANCE
                ? docstringOf(present(getter))
                : doc, null, true);
    }

    /** {@code p.getter(f)} and its siblings: the same property with one function replaced. */
    private PyProperty(PyProperty copied, PyObject replacement, int which) {
        this(
                which == 0 ? replacement : copied.getter,
                which == 1 ? replacement : copied.setter,
                which == 2 ? replacement : copied.deleter,
                // The docstring is the property's own from here on: CPython's property_copy keeps it
                // rather than re-deriving it from whichever function was just replaced.
                copied.documentation,
                copied.name,
                false);
    }

    private PyProperty(
            PyObject getter,
            PyObject setter,
            PyObject deleter,
            PyObject documentation,
            PyObject name,
            boolean replaceNone) {
        super(PyType.PROPERTY);
        this.getter = replaceNone ? present(getter) : getter;
        this.setter = replaceNone ? present(setter) : setter;
        this.deleter = replaceNone ? present(deleter) : deleter;
        this.documentation = documentation;
        this.name = name;
    }

    /** {@code None} and an omitted argument are the same thing to CPython's property. */
    private static PyObject present(PyObject function) {
        return function == null || function == PyNone.INSTANCE ? null : function;
    }

    private static PyObject docstringOf(PyObject function) {
        return function == null ? PyNone.INSTANCE : orNone(function.getAttr("__doc__"));
    }

    /**
     * What Python reads an absent half of the property: {@code None}, and never nothing at all.
     *
     * <p>{@code p.fset} on a getter-only property is {@code None} — which is also how {@code fget} reads
     * on a write-only one, and how {@code __doc__} reads when neither the {@code doc} argument nor the
     * getter's own docstring had anything to say.
     */
    private static PyObject orNone(PyObject answer) {
        return answer == null || answer == PyNotImplemented.INSTANCE ? PyNone.INSTANCE : answer;
    }

    /**
     * The property as an attribute: itself through the class, the getter's answer through a value.
     *
     * <p>Reading it off the class has to hand the property back, both because that is what
     * {@code C.span} and {@code type(C.span).__name__} answer and because it is how a subclass's
     * {@code @span.setter} finds the property it is meant to copy.
     */
    @Override
    public PyObject asAttribute(PyObject obj, PyObject owner) {
        if (obj == null) {
            return this;
        }
        if (getter == null) {
            throw Slots.attributeError(
                    "property%s of '%s' object has no getter", quotedName(), obj.getType().name());
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
     * the class as it is *qualified*, because CPython takes {@code PyType_GetQualName} for it: a metaclass
     * defined inside a function reports {@code make.<locals>.Meta} there.
     */
    @Override
    public void write(PyObject obj, PyObject value) {
        PyObject function = value == null ? deleter : setter;
        if (function == null) {
            throw Slots.attributeError(
                    "property%s of '%s' object has no %s",
                    quotedName(),
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

    /** The name CPython's {@code property_get_name} arrives at: the one given, else a function's. */
    private String nameOf() {
        if (name instanceof PyStr text) {
            return text.getValue();
        }
        for (PyObject function : new PyObject[] {getter, setter, deleter}) {
            if (function != null && function.getAttr("__name__") instanceof PyStr text) {
                return text.getValue();
            }
        }
        return null;
    }

    /** The name as the complaint quotes it — with its leading space, or nothing when there is none. */
    private String quotedName() {
        String found = nameOf();
        return found == null ? "" : " '" + found + "'";
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "fget" -> orNone(getter);
            case "fset" -> orNone(setter);
            case "fdel" -> orNone(deleter);
            case "__doc__" -> documentation;
            case "__name__" -> nameOrRefusal();
            case "getter" -> decorator("getter", 0);
            case "setter" -> decorator("setter", 1);
            case "deleter" -> decorator("deleter", 2);
            case "__get__" -> new PyNativeFunction(
                    "__get__", (args, kwNames) -> Descriptors.called(this, args, kwNames));
            case "__set__" ->
                    new PyNativeFunction(
                            "__set__",
                            (args, kwNames) -> {
                                if (kwNames != null) {
                                    throw Slots.typeError(
                                            "wrapper __set__() takes no keyword arguments");
                                }
                                Arguments.exactly("__set__", args, kwNames, 2);
                                write(args[0], args[1]);
                                return PyNone.INSTANCE;
                            });
            case "__delete__" ->
                    new PyNativeFunction(
                            "__delete__",
                            (args, kwNames) -> {
                                if (kwNames != null) {
                                    throw Slots.typeError(
                                            "wrapper __delete__() takes no keyword arguments");
                                }
                                if (args.length != 1) {
                                    // CPython's descr_delete passes no name to its unpacking, so the
                                    // message that comes out names nothing but the count.
                                    throw Slots.typeError(
                                            "expected 1 argument, got %d", args.length);
                                }
                                write(args[0], null);
                                return PyNone.INSTANCE;
                            });
            case "__set_name__" ->
                    new PyNativeFunction(
                            "__set_name__",
                            (args, kwNames) -> {
                                Arguments.noKeywords(
                                        "property.__set_name__()", kwNames);
                                if (args.length != 2) {
                                    throw Slots.typeError(
                                            "__set_name__() takes 2 positional arguments"
                                                    + " but %d were given",
                                            args.length);
                                }
                                setName(args[1]);
                                return PyNone.INSTANCE;
                            });
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /** A property with no name anywhere has no {@code __name__}, which is a refusal rather than None. */
    private PyObject nameOrRefusal() {
        String found = nameOf();
        return found == null ? PyNotImplemented.INSTANCE : PyStr.of(found);
    }

    private PyNativeFunction decorator(String name, int which) {
        return new PyNativeFunction(
                name,
                (args, kwNames) -> {
                    Arguments.exactlyOne("property." + name + "()", args, kwNames);
                    return new PyProperty(this, present(args[0]), which);
                });
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }
}
