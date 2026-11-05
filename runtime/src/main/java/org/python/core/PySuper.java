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
 * <p>Nothing is writable. A super object keeps no dictionary of its own, so every assignment — including
 * {@code super().volume = 9} aimed at a parent's property setter — is refused with {@code AttributeError:
 * 'super' object has no attribute 'volume' and no __dict__ for setting new attributes}. The three names it
 * reports are {@code PyMemberDef}s marked read-only and refuse with {@code readonly attribute} and nothing
 * else, and the sentence its type files refuses with {@code 'super' object attribute '__doc__' is
 * read-only}.
 *
 * <p>CPython reads a second time when the walk finds nothing: {@code super_getattro} ends by handing the
 * name to {@code _PyObject_GenericGetAttrWithDict}, which walks {@code super} and then {@code object}. The
 * nine rows of {@link TypeDict}'s {@code superTable} are what that second read answers, with {@code
 * object}'s twenty-four behind them — which is why a proxy that has a value reads {@code object.__doc__}
 * where one without reads {@code super}'s own four hundred and seventy-one characters. The walk gets those
 * first whenever there is an order to walk at all, and it reaches {@code object} from any class below it.
 */
public final class PySuper extends PyObject {

    /** {@code super(C)}: the class is known, the value is not, and so no order can be walked. */
    static PySuper withoutValue(PyObject type) {
        return new PySuper(asType(type), null, null);
    }

    /**
     * {@code super.__new__(super)}: the proxy with neither half, which prints {@code NULL} twice.
     *
     * <p>{@code super_new} only asks the frame when it was handed no class at all, so the creator run by
     * hand allocates and hands back — it never refuses the arguments it did not use, and never fills what
     * it allocated. {@code super.__new__(super, int, 3)} is that same empty proxy.
     */
    static PySuper empty() {
        return new PySuper(null, null, null);
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
        // The slow way round, which is what lets a proxy stand in for the class it reports. Asked of
        // Slots rather than of the value, because a built-in value answers that name from `object` and a
        // Java refusal would otherwise read as a value that claims nothing.
        PyObject claimed = Slots.findAttr(obj, "__class__");
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

    private PyType thisClass;
    private PyObject obj;
    private PyType startType;

    private PySuper(PyType thisClass, PyObject obj, PyType startType) {
        super(PyType.SUPER);
        this.thisClass = thisClass;
        this.obj = obj;
        this.startType = startType;
    }

    /**
     * {@code super_init} run against this proxy: the three fields replaced, in place.
     *
     * <p>CPython fills the same object it was handed rather than building another, so the value a person
     * held on to changes which order it walks — {@code p.__init__(Base, self)} is the way to move a proxy
     * that was built without a value into one with one, and {@code p.__init__()} is the way to ask the
     * frame again. A refusal leaves every field as it was, because {@code super_init_impl} only assigns
     * once {@code supercheck} has answered.
     */
    void refill(PySuper built) {
        this.thisClass = built.thisClass;
        this.obj = built.obj;
        this.startType = built.startType;
    }

    /** {@code __thisclass__}: the class to start at, or {@code None} while the proxy has no class either. */
    PyObject reportedClass() {
        return thisClass == null ? PyNone.INSTANCE : thisClass;
    }

    /** {@code __self__}: the value whose order is walked, or {@code None} when none is. */
    PyObject reportedValue() {
        return obj == null ? PyNone.INSTANCE : obj;
    }

    /** {@code __self_class__}: the class the walk is measured against, or {@code None} for no walk. */
    PyObject reportedOrder() {
        return startType == null ? PyNone.INSTANCE : startType;
    }

    /**
     * {@code proxy.name}: the first class after the starting one that names it, with its descriptor run.
     *
     * <p>Only a value gets that walk, so {@code super(C)} — which has a class and no value, and so no
     * order to walk — reads nothing but its own type's book, exactly as CPython's first pass finds nothing
     * when {@code obj} is null.
     *
     * <p>The descriptor is asked for {@link #obj} unless the proxy was built in class form, when the
     * value <em>is</em> the order being walked. That is {@code do_super_lookup}'s one conditional, and it
     * is why {@code super(Meta, Meta).__prepare__} binds {@code Meta} as the receiver of a classmethod
     * while {@code super(C, instance).name} binds the instance: {@link Descriptors} has to be told in the
     * first case that there is no instance at all, or a {@code classmethod} would report the proxy.
     *
     * <p>What the walk passes goes to {@link TypeDict#read}, which is {@code
     * _PyObject_GenericGetAttrWithDict} over this type's order: the nine rows {@code super} files itself
     * first, then the twenty-four of {@code object}. Both halves answer the same read for the proxy, so
     * {@code super(int).__get__} is {@code super}'s own row bound to the proxy while {@code super(int,
     * 3).__reduce__} is {@code object}'s bound to the three it was built with.
     */
    @Override
    public PyObject getAttr(String attribute) {
        // `super_getattro` diverts the one nine-character name before the walk, precisely because the
        // walk would reach `object`'s getset and answer it for `su->obj`: the comment in that function
        // says the name has to give the class of the proxy, not the class of the value.
        if (attribute.equals(ClassAttribute.NAME)) {
            return ClassAttribute.read(this);
        }
        PyObject found = beyond(attribute);
        return found != null ? found : TypeDict.read(this, attribute);
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

    /**
     * {@code s.name = value}: the generic write, with the type's dictionary deciding which wording comes.
     *
     * <p>The three names the proxy reports are {@code super}'s {@code PyMemberDef}s, and CPython marks them
     * read-only, so their refusal is {@code readonly attribute} and names no attribute at all. {@code
     * __doc__} is filed as a plain string rather than as a descriptor that writes, which is the other
     * read-only sentence — the one that does name the attribute; {@code __class__} reaches {@code object}'s
     * getset and its type check; and a name nothing on the order files has nowhere to go, because a proxy
     * keeps no instance dictionary.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        GenericAttributes.write(this, attribute, value);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delAttr(String attribute) {
        GenericAttributes.delete(this, attribute);
        return PyNone.INSTANCE;
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    /**
     * {@code super_repr}: the class the walk starts at, then the class of the value it walks for.
     *
     * <p>Each half prints {@code NULL} when the proxy does not hold it, and the two are independent: a
     * {@code super(C)} says {@code <super: <class 'C'>, NULL>} and the proxy {@code super.__new__} makes
     * with nothing at all says {@code <super: <class 'NULL'>, NULL>}, which is the only way that value can
     * be seen to differ from the one {@code __init__} was later asked to fill in place.
     */
    @Override
    public String repr() {
        return "<super: <class '"
                + (thisClass == null ? "NULL" : thisClass.name())
                + "'>, "
                + (startType == null ? "NULL" : "<" + startType.name() + " object>")
                + ">";
    }
}
