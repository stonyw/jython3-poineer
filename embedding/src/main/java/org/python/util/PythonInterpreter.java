package org.python.util;

import org.python.core.ExceptionTypes;
import org.python.core.PyModule;
import org.python.core.PyObject;
import org.python.core.PyRaise;
import org.python.core.PyRuntime;
import org.python.core.Slots;
import org.python.front.cpython314.Cpython314Compiler;
import org.python.javaclass.JavaConvert;
import org.python.javaclass.JavaInterop;

/**
 * The Java side of Jython3: run Python from Java, in this process.
 *
 * <p>This is the embedding API MVP④ asked for. It is a small surface on purpose — statements, one
 * expression, names in and out, a function by name, and a namespace wearing a Java interface — because
 * every one of those is a crossing the caller cannot make for themselves from outside: {@link PyObject}
 * needs the runtime to build, a frame needs a namespace, and a Java interface needs a proxy.
 *
 * <p><b>Two namespaces, and the difference is the point.</b> {@link #interp()} is {@code __main__}, the
 * same module a script runs in, so an embedder and a script share their names. A {@code new}
 * interpreter is its own module: Jython 2.7 gives every one of them {@code __main__} and lets them
 * write into the same dictionary, which means two subsystems that each embed an interpreter erase each
 * other's globals by accident. Both read as a module top level, so {@code __name__} is {@code
 * "__main__"} either way; what is isolated is the dictionary behind it.
 *
 * <p><b>Conversions are the boundary's, not the caller's.</b> Every argument and every result crosses
 * through {@link JavaConvert}: a {@code String} in is a {@code str}, a {@code str} out is a
 * {@code String}, a {@code list} out is a {@code java.util.List}, and a Java object set here stays the
 * Java object Python walks with its own container protocol. Nothing is copied on the way through, which
 * is why {@code set("items", list)} then {@code items.add(...)} in Python is visible in {@code list}.
 *
 * <p><b>Failures are Java-visible.</b> A Python exception crosses as {@link PyRaise}, an unchecked
 * exception whose {@code value()} is the exception itself, so a caller can read its type and arguments
 * rather than only its text. A Java exception thrown underneath Python code comes back as a
 * {@code PyRaise} too, and {@code value()} of that is a {@link org.python.javaclass.PyJavaThrowable}
 * holding the original {@code Throwable}. {@link Cpython314Compiler.CompilationRefused} is the source's
 * own fault and is reported as CPython reports it.
 */
public class PythonInterpreter {

    /** What CPython names the code of {@code -c}, which is the shape of a snippet. */
    private static final String SOURCE_NAME = "<string>";

    private final PyModule namespace;

    /**
     * The interpreter over {@code __main__}: the one namespace a script in this process would share.
     *
     * <p>Successive calls give the same namespace, not the same object, because the namespace is the
     * thing with state and an interpreter is only a way of reaching it.
     */
    public static PythonInterpreter interp() {
        return new PythonInterpreter(PyRuntime.mainModule());
    }

    /** An interpreter with a namespace of its own, so that two embedders cannot see each other. */
    public PythonInterpreter() {
        this(new PyModule("__main__"));
    }

    /** An interpreter over a module this caller already has, which is how M4's REPL will resume one. */
    public PythonInterpreter(PyModule module) {
        JavaInterop.install();
        this.namespace = module;
    }

    // --- running -------------------------------------------------------------------------

    /**
     * Run statements, as a module's top level would, and leave their names in this namespace.
     *
     * <p>{@code __file__} is deliberately not touched: a snippet has no file, and the script that may
     * already have set it on a shared {@code __main__} should keep it.
     */
    public void exec(String source) {
        PyRuntime.exec(Cpython314Compiler.compileSource(source, SOURCE_NAME), namespace, null);
    }

    /** Evaluate one expression and give back the Java object its Python value stands for. */
    public Object eval(String source) {
        return JavaConvert.toJava(evaluate(source));
    }

    /**
     * Evaluate one expression as {@code required}.
     *
     * <p>This is {@link #eval(String)} for the case where the caller knows what it wants and the value
     * can be coerced: a {@code str} into a {@code String}, an {@code int} into a {@code Long}, a
     * callable into an interface. A value that cannot be coerced is a caller's mistake, so it is
     * reported as one rather than answered with a {@code null} that looks like an empty result.
     */
    public <T> T eval(String source, Class<T> required) {
        PyObject value = evaluate(source);
        Object converted = JavaConvert.toJava(value, required);
        if (converted == JavaConvert.NO_CONVERSION) {
            throw new IllegalArgumentException(
                    "'" + source + "' evaluates to " + value.getType().name()
                            + ", which is not a " + required.getName());
        }
        return required.cast(converted);
    }

    /**
     * Call the Python function this namespace knows as {@code name}, with Java arguments.
     *
     * <p>A name that is not there is a {@code NameError}, which is what Python would have said. Jython
     * 2.7 answers {@code null} instead, and a caller then has to work out whether the function returned
     * nothing or never existed.
     */
    public Object invoke(String name, Object... arguments) {
        PyObject callable = getPyObject(name);
        if (callable == null) {
            throw PyRaise.raise(
                    ExceptionTypes.NAME_ERROR, "name '" + name + "' is not defined");
        }
        PyObject[] given = new PyObject[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            given[i] = JavaConvert.toPython(arguments[i]);
        }
        return JavaConvert.toJava(Slots.call(callable, given, null));
    }

    /**
     * This namespace as an implementation of {@code face}, one member per abstract method.
     *
     * <p>What Python defines here is a module, so a method is a function stored under the Java name —
     * {@code def onOpen(title)} for {@code Panel.onOpen(String)}. An instance wearing an interface is
     * the same lookup once M3's class system gives Python objects members.
     */
    public <I> I export(Class<I> face) {
        return JavaInterop.proxy(namespace, face);
    }

    // --- names ---------------------------------------------------------------------------

    /** Bind a Java value under a Python name, converting it the way the boundary converts everything. */
    public void set(String name, Object value) {
        namespace.set(name, JavaConvert.toPython(value));
    }

    /** The Python value bound to {@code name}, or {@code null} if nothing is. */
    public PyObject getPyObject(String name) {
        return namespace.get(name);
    }

    /** The Java object {@code name} stands for here, or {@code null} if the name is not bound. */
    public Object get(String name) {
        PyObject value = getPyObject(name);
        return value == null ? null : JavaConvert.toJava(value);
    }

    /** {@code name} as {@code required}, which is {@link #get(String)} where a coercion is wanted. */
    public <T> T get(String name, Class<T> required) {
        PyObject value = getPyObject(name);
        if (value == null) {
            return null;
        }
        Object converted = JavaConvert.toJava(value, required);
        if (converted == JavaConvert.NO_CONVERSION) {
            throw new IllegalArgumentException(
                    "'" + name + "' is " + value.getType().name()
                            + ", which is not a " + required.getName());
        }
        return required.cast(converted);
    }

    // --- internals -----------------------------------------------------------------------

    private PyObject evaluate(String source) {
        return PyRuntime.eval(
                Cpython314Compiler.compileExpression(source, SOURCE_NAME), namespace);
    }
}
