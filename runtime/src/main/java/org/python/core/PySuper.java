package org.python.core;

/**
 * {@code super}: the proxy that answers from the class after the one it was given.
 *
 * <p>A super object holds three things — the class to start at, the value whose order to walk, and the
 * type that walk is measured against — and only one operation: read a name from the first class after
 * the starting one. That skip is the whole of cooperative multiple inheritance, and it is why
 * {@code Bottom.sound}'s {@code super().sound()} lands in {@code Left} while {@code super(Right, self)}
 * lands in {@code Speak}: the search is not "my parent", it is "the rest of this value's order".
 *
 * <p>The descriptor protocol still runs at the far end. CPython's {@code _PySuper_Lookup} finds the
 * member in the raw class dictionaries and then calls its {@code __get__} with the proxy's own value and
 * that value's type, so a method reached through super binds to the instance the proxy was built with —
 * never to the proxy, and never to the class it started from. A classmethod therefore reports the
 * subclass, and a parent's {@code property} is computed for the child.
 *
 * <p>Nothing is writable. A super object has no dictionary and its type has no {@code tp_setattro}, so
 * every assignment — including {@code super().volume = 9} aimed at a parent's property setter — is
 * refused with {@code AttributeError: 'super' object has no attribute 'volume' and no __dict__ for
 * setting new attributes}, and the three names it does own refuse with {@code readonly attribute}.
 *
 * <p>CPython reads a second time when the walk finds nothing: it takes {@code super}'s own type's order
 * past {@code super} itself, which is {@code object}, and so hands back {@code object.__init__} bound to
 * the value — or to the proxy, when the proxy has no value. Those members are slot wrappers, and this
 * runtime models no slot wrappers at all, so a name the walk passes is refused here and {@link
 * #getAttr} answers only the few names the proxy really owns.
 */
public final class PySuper extends PyObject {

    /** {@code super(C)}: the class is known, the value is not, and so no order can be walked. */
    static PySuper withoutValue(PyObject type) {
        return new PySuper(asType(type), null, null);
    }

    /**
     * {@code super(C, x)}, which is also what both forms of {@code LOAD_SUPER_ATTR} build.
     *
     * <p>CPython hands {@code None} to the same initializer as a missing second argument, so {@code
     * super(C, None)} is the valueless proxy that {@code super(C)} builds rather than a complaint about
     * {@code NoneType} not being a subtype of {@code C}.
     *
     * <p>{@code supercheck} then decides which order to walk, in three answers: a class that derives from
     * the starting one walks its own order, a value walks its type's, and a value that only claims a
     * class — a proxy that answers {@code __class__} — is taken at its word. Anything else is refused by
     * naming what the second argument was.
     */
    static PySuper of(PyObject type, PyObject obj) {
        PyType kind = asType(type);
        if (obj == PyNone.INSTANCE) {
            return new PySuper(kind, null, null);
        }
        return new PySuper(kind, obj, superCheck(kind, obj));
    }

    /** {@code supercheck}: the class whose order the proxy is allowed to walk past {@code kind}. */
    private static PyType superCheck(PyType kind, PyObject obj) {
        // A class as the second argument is the class form, and it is the only form a metaclass
        // assignment is made from: `super(Meta, Meta)` walks Meta's own bases.
        if (obj instanceof PyType owner && owner.matches(kind)) {
            return owner;
        }
        if (obj.getType().matches(kind)) {
            return obj.getType();
        }
        // The slow way round, which is what lets a proxy stand in for the class it reports.
        PyObject claimed = obj.getAttr("__class__");
        if (claimed instanceof PyType icls && icls != obj.getType() && icls.matches(kind)) {
            return icls;
        }
        boolean classForm = obj instanceof PyType;
        throw notAnInstance(
                kind,
                classForm ? "type" : "instance of",
                classForm ? ((PyType) obj).name() : obj.getType().name());
    }

    private static PyRaise notAnInstance(PyType kind, String asWhat, String name) {
        return Slots.typeError(
                "super(type, obj): obj (%s %s) is not an instance or subtype of type (%s).",
                asWhat, name, kind.name());
    }

    /**
     * {@code super()} with no arguments at all: the caller's own frame names both halves.
     *
     * <p>CPython asks the frame that is executing the call — not the method that contains it — for its
     * first argument, and asks that frame's function for the {@code __class__} cell the class body left
     * behind. The argument is read first and is the half that can be missing two ways: a frame whose
     * code has no parameter at all is {@code super(): no arguments}, which is why a lambda cannot use
     * the no-argument form even inside a method, while a frame that has since deleted its {@code self}
     * is {@code super(): arg[0] deleted} — a complaint about the argument, and made before the cell is
     * looked at, so it comes first even for a function that has no {@code __class__} to read. A {@code
     * None} argument counts as present: it reaches {@link #of} and comes back as the valueless proxy.
     *
     * <p>A {@code super().name} reads this way too whenever the compiler did not take the shortcut: a
     * name write, a one-argument {@code super(C).name}, and every implicit form in a module that names
     * {@code super} in its own module-level code are compiled as a call of the loaded object, so the
     * frame has to be asked. Where the compiler does take it, both halves come off the value stack and
     * {@link Interpreter} answers from there — and a {@code self} deleted by then is refused there as the
     * ordinary empty-local read ({@code LOAD_FAST_CHECK}), not as one of these messages.
     */
    static PySuper fromFrame() {
        Frame caller = Interpreter.currentFrame();
        if (caller == null || caller.code().argCount() < 1) {
            throw Slots.runtimeError("super(): no arguments");
        }
        PyObject value = caller.firstArgument();
        if (value == null) {
            throw Slots.runtimeError("super(): arg[0] deleted");
        }
        PyCell klass = caller.classVar();
        if (klass == null) {
            throw Slots.runtimeError("super(): __class__ cell not found");
        }
        PyObject type = klass.value();
        if (type == null) {
            throw Slots.runtimeError("super(): empty __class__ cell");
        }
        return of(type, value);
    }

    private static PyType asType(PyObject type) {
        if (type instanceof PyType kind) {
            return kind;
        }
        throw Slots.typeError("super() argument 1 must be a type, not %s", type.getType().name());
    }

    private final PyType thisClass;
    private final PyObject obj;
    private final PyType startType;

    private PySuper(PyType thisClass, PyObject obj, PyType startType) {
        super(PyType.SUPER);
        this.thisClass = thisClass;
        this.obj = obj;
        this.startType = startType;
    }

    /**
     * {@code proxy.name}: the first class after the starting one that names it, with its descriptor run.
     *
     * <p>Only a value gets that walk, so {@code super(C)} — which has a class and no value, and so no
     * order to walk — reads nothing but the names below, exactly as CPython's first pass finds nothing
     * when {@code obj} is null.
     *
     * <p>The descriptor is asked for {@link #obj} unless the proxy was built in class form, when the
     * value <em>is</em> the order being walked. That is {@code do_super_lookup}'s one conditional, and it
     * is why {@code super(Meta, Meta).__prepare__} binds {@code Meta} as the receiver of a classmethod
     * while {@code super(C, instance).name} binds the instance: {@link Descriptors} has to be told in the
     * first case that there is no instance at all, or a {@code classmethod} would report the proxy.
     */
    @Override
    public PyObject getAttr(String attribute) {
        PyObject found = beyond(attribute);
        if (found != null) {
            return found;
        }
        return switch (attribute) {
            case "__self__" -> obj == null ? PyNone.INSTANCE : obj;
            case "__self_class__" -> startType == null ? PyNone.INSTANCE : startType;
            case "__thisclass__" -> thisClass;
            case "__class__" -> PyType.SUPER;
            default -> PyNotImplemented.INSTANCE;
        };
    }

    /** The member {@code attribute} in the part of the order the proxy is allowed to see. */
    private PyObject beyond(String attribute) {
        if (startType == null) {
            return null;
        }
        PyType[] order = startType.mro();
        for (int at = 0; at < order.length; at++) {
            if (order[at] != thisClass) {
                continue;
            }
            for (int next = at + 1; next < order.length; next++) {
                PyObject member = Specials.declared(order[next], attribute);
                if (member != null) {
                    return Descriptors.get(member, obj == startType ? null : obj, startType);
                }
            }
            return null;
        }
        return null;
    }

    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        throw Slots.attributeError(readonly(attribute) ? "readonly attribute" : writable(attribute));
    }

    @Override
    public PyObject delAttr(String attribute) {
        throw Slots.attributeError(readonly(attribute) ? "readonly attribute" : writable(attribute));
    }

    /** The three names the proxy reads for itself, and the only three it refuses a write by name. */
    private static boolean readonly(String attribute) {
        return attribute.equals("__self__")
                || attribute.equals("__self_class__")
                || attribute.equals("__thisclass__");
    }

    /**
     * CPython's own complaint about a value with nowhere to put a new attribute: it is the generic
     * {@code setattr} refusal, and it is what a write through super always becomes.
     */
    private static String writable(String attribute) {
        return "'super' object has no attribute '" + attribute + "'"
                + " and no __dict__ for setting new attributes";
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    @Override
    public String repr() {
        return "<super: <class '"
                + thisClass.name()
                + "'>, "
                + (startType == null ? "NULL" : "<" + startType.name() + " object>")
                + ">";
    }
}
