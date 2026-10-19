package org.python.javaclass;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.python.core.ModuleFinder;
import org.python.core.PyNotImplemented;
import org.python.core.PyObject;
import org.python.core.PyRaise;
import org.python.core.PyRuntime;
import org.python.core.Slots;

/**
 * The Java half of the import system, installed as a finder rather than wired into the runtime.
 *
 * <p>{@code org.python.core} stays ignorant of Java: {@link PyRuntime} asks its registered finders for a
 * dotted name in order, and {@link #PACKAGES} answers for the ones that are Java packages. The embedding
 * module calls {@link #install()} at start up, and that call is the only place the two halves of the
 * product meet — which is what lets a build of Jython3 without {@code javainterop} still run Python.
 */
public final class JavaInterop {

    /** A package the JVM can name becomes a module; anything else is not this finder's business. */
    private static final ModuleFinder PACKAGES =
            dotted -> JavaPackage.exists(dotted) ? JavaPackage.of(dotted) : null;

    private static volatile boolean installed;

    private JavaInterop() {
    }

    /** Make {@code java.util} and every other package on the JVM importable. Safe to call twice. */
    public static void install() {
        if (!installed) {
            synchronized (JavaInterop.class) {
                if (!installed) {
                    PyRuntime.addModuleFinder(PACKAGES);
                    installed = true;
                }
            }
        }
    }

    /**
     * Turn a Java {@code Throwable} into the Python exception of the same name.
     *
     * <p>Its class is the {@link PyJavaType} of the Java class, so {@code e.__class__.__name__} is
     * {@code IllegalStateException} and an uncaught one reports {@code java.lang.IllegalStateException}.
     * {@code Throwable} inherits from Python's {@code Exception}, so the ordinary
     * {@code except Exception} clause reaches across the boundary too. A {@code PyRaise} coming back out
     * of a Java method — because Java called a Python callable, which raised — is passed through
     * unchanged, so a Python exception keeps its identity on the round trip.
     */
    static PyRaise causedBy(Throwable failure) {
        if (failure instanceof PyRaise python) {
            return python;
        }
        return PyRaise.of(new PyJavaThrowable(failure));
    }

    /**
     * A Python value wearing a Java interface.
     *
     * <p>Java's functional interfaces are what a Python caller most wants to satisfy — a comparator, a
     * listener, a {@code Function} — and {@link Proxy} is the JVM's own way to implement an interface at
     * run time, so no bytecode generation is needed here. {@code equals}, {@code hashCode} and
     * {@code toString} go to the value as well, because a proxy that answered them from {@code Object}
     * would surprise anyone who put one in a set.
     *
     * <p>Two shapes are worth distinguishing, because they answer a method call differently. A
     * <em>callable</em> — a function, a lambda, a bound method — has no members to look up, so every
     * abstract method of the interface is forwarded to it; that is what makes a lambda a
     * {@code Function} or a {@code Runnable}. A <em>namespace</em> — a module, and from M3 an instance —
     * is asked for each method by name, which is what lets one Python namespace implement an interface
     * with four methods in it. A name the namespace does not have is a Java failure rather than a Python
     * one: it is Java that called a method which does not exist.
     */
    public static <I> I proxy(PyObject value, Class<I> face) {
        ClassLoader loader = face.getClassLoader() == null
                ? JavaInterop.class.getClassLoader()
                : face.getClassLoader();
        return face.cast(Proxy.newProxyInstance(loader, new Class<?>[] {face}, new ToJava(value, face)));
    }

    /** One interface, one Python value, every abstract method forwarded to it or looked up on it. */
    private record ToJava(PyObject value, Class<?> face) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            Object[] given = arguments == null ? new Object[0] : arguments;
            String name = method.getName();
            if (given.length == 0 && name.equals("toString")) {
                return Slots.str(value);
            }
            if (given.length == 0 && name.equals("hashCode")) {
                return Long.valueOf(value.hash());
            }
            if (given.length == 1 && name.equals("equals")) {
                return proxy == given[0];
            }
            PyObject callable = memberCalled(name);
            PyObject[] wrapped = new PyObject[given.length];
            for (int i = 0; i < given.length; i++) {
                wrapped[i] = JavaConvert.toPython(given[i]);
            }
            PyObject result = Slots.call(callable, wrapped, null);
            if (method.getReturnType() == void.class) {
                return null;
            }
            Object converted = JavaConvert.toJava(result, method.getReturnType());
            if (converted == JavaConvert.NO_CONVERSION) {
                throw new ClassCastException(
                        face.getName() + "." + name + "() cannot return " + result.getType().name());
            }
            return converted;
        }

        /** The Python callable for one interface method: the value's member of that name, or itself. */
        private PyObject memberCalled(String name) {
            PyObject member = value.getAttr(name);
            if (member != PyNotImplemented.INSTANCE && JavaConvert.isCallable(member)) {
                return member;
            }
            if (JavaConvert.isCallable(value)) {
                return value;
            }
            throw new AbstractMethodError(
                    face.getName() + " has no member '" + name + "' in " + value.getType().name());
        }
    }
}
