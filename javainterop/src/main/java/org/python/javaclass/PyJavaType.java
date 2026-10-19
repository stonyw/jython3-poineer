package org.python.javaclass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.python.core.ExceptionTypes;
import org.python.core.PyNotImplemented;
import org.python.core.PyObject;
import org.python.core.PyStr;
import org.python.core.PyTuple;
import org.python.core.PyType;
import org.python.core.Slots;

/**
 * A Java class, as a Python class object.
 *
 * <p>It is a {@link PyType} rather than a separate kind of value, so that {@code isinstance},
 * {@code type(x).__name__} and an {@code except} clause all reach the same code they reach for a Python
 * class. The chain it inherits through is the Java one — {@code getSuperclass()}, terminated at
 * {@link PyType#OBJECT} so that every class has a root — which lets the inherited {@code matches} answer
 * the parent half of {@code isinstance}. It cannot answer the interface half, because a {@code PyType}
 * has one parent and a Java class has many supertypes, and that is what {@link #instanceOf} is for: it
 * asks the Java class itself.
 *
 * <p>One instance per {@code Class}, cached, because {@code util.ArrayList is ArrayList} has to be true
 * and because two copies of a class would mean two copies of its static state.
 */
public final class PyJavaType extends PyType {

    private static final Map<Class<?>, PyJavaType> cache = new ConcurrentHashMap<>();

    private final Class<?> reflected;

    /**
     * The Python view of {@code type}, created on first sight and kept from then on.
     *
     * <p>The supertype chain is built from the top down rather than by the constructor asking for its
     * own parent, because that would be a recursive update of the cache while the cache is building the
     * entry above it — which {@code ConcurrentHashMap} does not promise to survive.
     */
    public static PyJavaType of(Class<?> type) {
        PyJavaType known = cache.get(type);
        if (known != null) {
            return known;
        }
        List<Class<?>> missing = new ArrayList<>();
        for (Class<?> walk = type; walk != null && !cache.containsKey(walk); walk = ancestorOf(walk)) {
            missing.add(walk);
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            cache.computeIfAbsent(missing.get(i), PyJavaType::new);
        }
        return cache.get(type);
    }

    private PyJavaType(Class<?> type) {
        super(type.getSimpleName(), parentOf(type));
        this.reflected = type;
        // A Java class is itself an instance of `type`, exactly as a Python class is.
        this.type = PyType.TYPE;
    }

    /** The class this object reflects, for callers that need to ask Java a question of their own. */
    public Class<?> javaClass() {
        return reflected;
    }

    /** The class named {@code simpleName} in {@code packageName}, or null when there is none. */
    static PyObject classIn(String packageName, String simpleName) {
        Class<?> found = PackageIndex.load(packageName + "." + simpleName);
        return found == null ? null : of(found);
    }

    /** The superclass to build a Python parent from, or null where a fixed {@code PyType} takes over. */
    private static Class<?> ancestorOf(Class<?> type) {
        return type == Throwable.class ? null : type.getSuperclass();
    }

    private static PyType parentOf(Class<?> type) {
        if (type == Throwable.class) {
            // The one place the two hierarchies are joined on purpose: `except Exception` has to reach a
            // Java failure, or an ordinary Python handler silently misses it. Everything below Throwable
            // descends from Python's Exception by inheriting this link.
            return ExceptionTypes.EXCEPTION;
        }
        Class<?> superclass = type.getSuperclass();
        // An interface, a primitive and java.lang.Object itself all have no superclass. OBJECT is the
        // root in Python's sense either way; instanceOf is what consults the Java hierarchy.
        return superclass == null ? PyType.OBJECT : of(superclass);
    }

    @Override
    public String qualifiedName() {
        // The binary name, with `$` for a nested class: `java.lang.IllegalStateException` in an
        // exception report and `Widget$Builder` in a repr are both what a Java reader would write.
        return reflected.getName();
    }

    /** {@code Widget()}, {@code ArrayList(3)}: a Java class is constructed by calling it. */
    @Override
    public PyObject call(PyObject[] args, PyTuple kwNames) {
        if (reflected.isInterface()) {
            throw Slots.typeError(
                    "%s is an interface and cannot be constructed; pass an implementation",
                    reflected.getName());
        }
        return Overloads.call(
                reflected.getSimpleName(),
                Overloads.visibleConstructors(reflected),
                args,
                names(kwNames),
                null);
    }

    /** The trailing keyword names of a vectorcall list, as Java strings. */
    private static String[] names(PyTuple kwNames) {
        if (kwNames == null) {
            return null;
        }
        String[] out = new String[kwNames.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((PyStr) kwNames.at(i)).getValue();
        }
        return out;
    }

    /**
     * Statics, constants and nested classes are the attributes of a Java class.
     *
     * <p>An instance method is too, and Java's own unbound spelling stays available:
     * {@code Widget.getName(w)} passes the receiver as the first argument, as 2.7 does. When one name
     * covers both an instance method and a static one — {@code Point2D.distance} — the class answers
     * both, and {@link JavaMethods#onClass} is where the call decides which was meant.
     *
     * <p>What the class does not declare is then looked for on the {@code java.lang.Class} it reflects,
     * last and weakest: a Java class is an instance of that class too, so {@code getName()},
     * {@code isInterface()} and {@code getDeclaredFields()} — Java's own {@code Integer.class.getName()} —
     * are reachable from Python, which is what a reader who came to introspect came for.
     */
    @Override
    public PyObject getAttr(String attribute) {
        if (attribute.equals("__module__")) {
            return PyStr.of(PackageIndex.packageOf(reflected));
        }
        PyObject inherited = super.getAttr(attribute);
        if (inherited != PyNotImplemented.INSTANCE) {
            return inherited;
        }
        Class<?> nested = PackageIndex.load(reflected.getName() + "$" + attribute);
        if (nested != null) {
            return of(nested);
        }
        List<Method> withName = JavaMethods.named(reflected, attribute);
        if (!withName.isEmpty()) {
            return JavaMethods.onClass(this, attribute, withName);
        }
        Field constant = staticField(attribute);
        if (constant != null) {
            return readConstant(constant);
        }
        List<Method> reflective = JavaMethods.named(Class.class, attribute);
        if (reflective.isEmpty()) {
            return PyNotImplemented.INSTANCE;
        }
        // Bound to the Class object rather than unbound: it is this object's own method, in Java's sense,
        // and `Integer.getName()` reads the class the same way `Integer.class.getName()` does in Java.
        return JavaMethods.instance(this, attribute, reflective, reflected);
    }

    private PyObject readConstant(Field constant) {
        try {
            return JavaConvert.toPython(constant.get(null));
        } catch (IllegalAccessException hidden) {
            return PyNotImplemented.INSTANCE;
        }
    }

    private Field staticField(String name) {
        try {
            Field field = reflected.getField(name);
            return Modifier.isStatic(field.getModifiers()) ? field : null;
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    /**
     * {@code isinstance(value, self)}, asked of the Java class rather than of the parent chain.
     *
     * <p>A Python {@code str} is not a {@code CharSequence} here even though it converts into one
     * perfectly well for a call: {@code isinstance} is a statement about what a value <em>is</em>, and
     * a conversion is a statement about what a call accepts. Jython 2.7 draws the same line.
     */
    @Override
    public boolean instanceOf(PyObject value) {
        if (value instanceof PyJavaObject java) {
            return reflected.isInstance(java.target());
        }
        if (value instanceof PyJavaType other) {
            return reflected.isAssignableFrom(other.reflected);
        }
        return super.instanceOf(value);
    }

    @Override
    public String repr() {
        return "<class '" + reflected.getName() + "'>";
    }
}
