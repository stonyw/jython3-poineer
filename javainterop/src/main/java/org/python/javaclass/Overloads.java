package org.python.javaclass;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.python.core.PyNone;
import org.python.core.PyObject;
import org.python.core.Slots;

/**
 * Which overload a call resolves to, and in what order the candidates are tried.
 *
 * <p>This is the one place in the interop layer where a judgement is made, so it is the one place the
 * design departs from Jython 2.7 on purpose. 2.7 keeps a ranking table for fixed-arity calls and a
 * second, independent cost scale for varargs, takes the first candidate its table matches rather than
 * the best one, has no ambiguity error at all, and defaults {@code Options.reflectedArgsLegacyMode} to
 * the worse of its two behaviours. Here there is one ordered search and one cost function, and the two
 * failures a caller can actually act on — nothing matched, and two matched equally — say different
 * things.
 *
 * <p>The order is Java's own, from JLS 15.12.2: the strict invocation phase (exact type, then widening,
 * then reference assignability) is searched first and its answer taken if it is non-empty; then the
 * loose phase, where boxing lives and where the conversions Python needs but Java has no name for are
 * filed; then variable arity. Only a phase with more than one candidate in it needs a winner, and the
 * tiebreak is Java's too — the pairwise most-specific test — with a cost sum in front of it, because
 * Python's {@code int} has no fixed width for the specificity test to compare.
 */
final class Overloads {

    private Overloads() {
    }

    /**
     * Select, convert, invoke.
     *
     * @param args the whole vectorcall argument list, keyword values included
     * @param kwNames the trailing keyword names, or null when every argument is positional
     * @param receiver the instance for a method call, or null for a static method or a constructor
     */
    static PyObject call(
            String name,
            List<? extends Executable> candidates,
            PyObject[] args,
            String[] kwNames,
            Object receiver) {
        int positional = args.length - (kwNames == null ? 0 : kwNames.length);
        if (kwNames != null && kwNames.length > 0) {
            throw Slots.typeError(
                    "%s() takes no keyword arguments: Java parameters are matched by position", name);
        }
        PyObject[] given = positional == args.length ? args : Arrays.copyOf(args, positional);
        List<? extends Executable> ordered = order(name, candidates);
        List<Candidate> applicable = rank(ordered, given);
        if (applicable.isEmpty()) {
            throw Slots.typeError(
                    "no matching overloads of %s() available, found %s; expected %s",
                    name, describeArguments(given), describeCandidates(name, ordered));
        }
        Candidate chosen = pick(name, applicable);
        Object[] arguments = chosen.convert(given);
        try {
            if (chosen.executable instanceof Method method) {
                Object result = method.invoke(receiver, arguments);
                return method.getReturnType() == void.class ? PyNone.INSTANCE : JavaConvert.toPython(result);
            }
            return JavaConvert.toPython(((Constructor<?>) chosen.executable).newInstance(arguments));
        } catch (InvocationTargetException failure) {
            throw JavaInterop.causedBy(failure.getTargetException());
        } catch (IllegalAccessException refused) {
            // It is the module system refusing, not the argument list: a package a module does not export
            // still imports and still shows its classes, so the only place the boundary can appear is
            // here — and it has to appear with Java's own name for it, not as a TypeError about arguments.
            throw JavaInterop.causedBy(refused);
        } catch (ReflectiveOperationException bad) {
            throw Slots.typeError(
                    "cannot call %s(%s): %s", name, parameterNames(chosen.executable), bad.getMessage());
        }
    }

    /**
     * Whether these candidates take these arguments at all, without invoking anything.
     *
     * <p>A caller that has two overload sets under one name — the statics of a class and its instance
     * methods — needs to know which set the arguments belong to before it commits to one. Asking
     * {@link #call} and catching its refusal would work, but it would also report the wrong candidate
     * list when neither set matches, so the question is asked of the ranking directly.
     */
    static boolean takes(
            String name, List<? extends Executable> candidates, PyObject[] args, String[] kwNames) {
        if (kwNames != null && kwNames.length > 0) {
            // Let the call itself report keywords, which Java has no way to express either.
            return true;
        }
        return !rank(order(name, candidates), args).isEmpty();
    }

    /** The public callables a class offers, minus the ones a bridge method adds for generics. */
    static List<Method> visibleMethods(Class<?> type) {
        List<Method> out = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (!method.isBridge() && !method.isSynthetic()) {
                out.add(invocable(method));
            }
        }
        return out;
    }

    static List<Constructor<?>> visibleConstructors(Class<?> type) {
        List<Constructor<?>> out = new ArrayList<>();
        for (Constructor<?> constructor : type.getConstructors()) {
            if (!constructor.isSynthetic()) {
                out.add(constructor);
            }
        }
        return out;
    }

    /**
     * The same method, as declared by a class an outside caller may name.
     *
     * <p>Java's most useful return values are instances of classes nobody can write: {@code List.of} gives
     * an {@code ImmutableCollections.ListN} and {@code stream()} a {@code ReferencePipeline}, each
     * package-private, each holding the public {@code get(int)} and {@code map} that reflection checks
     * against the class they were declared in. So binding what the runtime class declares makes a call
     * every Java program makes for free an {@code IllegalAccessException}. Java answers that by dispatching
     * through {@code List} or {@code Stream}; binding the same signature on the nearest public supertype is
     * the same dispatch, reached by reflection, and the virtual call still lands on the real override.
     */
    static Method invocable(Method method) {
        if (publiclyNamed(method.getDeclaringClass())) {
            return method;
        }
        List<Class<?>> frontier =
                new ArrayList<>(Arrays.asList(directSupertypes(method.getDeclaringClass())));
        Set<Class<?>> seen = new HashSet<>();
        for (int at = 0; at < frontier.size(); at++) {
            Class<?> candidate = frontier.get(at);
            if (!seen.add(candidate)) {
                continue;
            }
            if (publiclyNamed(candidate)) {
                Method declared = declaredIn(candidate, method);
                if (declared != null) {
                    return declared;
                }
            }
            frontier.addAll(Arrays.asList(directSupertypes(candidate)));
        }
        // Nothing public declares it, so the call is refused — by Java, in Java's own words.
        return method;
    }

    /**
     * Whether an outside caller may name {@code type}, which is what reflection requires before a call.
     *
     * <p>Every enclosing class counts, not just the innermost: {@code ImmutableCollections$ListN} is public
     * inside a package-private {@code ImmutableCollections}, and the pair is no more reachable than either
     * half. This is 2.7's {@code isAccessibleClass} test, and it says nothing about JPMS — a public class in
     * a package its module does not export passes here and is still refused at the call.
     */
    private static boolean publiclyNamed(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?>[] directSupertypes(Class<?> type) {
        Class<?>[] faces = type.getInterfaces();
        Class<?> parent = type.getSuperclass();
        if (parent == null) {
            return faces;
        }
        Class<?>[] all = Arrays.copyOf(faces, faces.length + 1);
        all[faces.length] = parent;
        return all;
    }

    /** {@code method}'s signature as {@code type} declares it, when it declares it publicly. */
    private static Method declaredIn(Class<?> type, Method method) {
        try {
            Method found = type.getDeclaredMethod(method.getName(), method.getParameterTypes());
            // An interface member is implicitly public, but a class's has to say so to be callable.
            return Modifier.isPublic(found.getModifiers()) ? found : null;
        } catch (NoSuchMethodException notThere) {
            return null;
        }
    }

    /**
     * Candidates in a stable order: shortest parameter list first, then by signature.
     *
     * <p>{@code getMethods()} makes no promise about order, and an interop layer that reports a different
     * overload list on each run — or resolves a tie by whichever copy of the class file the JVM happened
     * to read — is not testable. Order plays no part in which candidate wins, because ranking is by phase
     * and cost; it decides only what a failure message lists and which of two genuinely equal candidates
     * a caller is told about first.
     */
    private static List<? extends Executable> order(String name, List<? extends Executable> candidates) {
        if (candidates.size() < 2) {
            return candidates;
        }
        List<Executable> ordered = new ArrayList<>(candidates);
        ordered.sort(
                java.util.Comparator
                        .comparingInt((Executable candidate) -> candidate.getParameterCount())
                        .thenComparing(candidate -> signature(name, candidate)));
        return ordered;
    }

    // --- ranking -------------------------------------------------------------------------

    /** The applicable candidates, in the earliest phase any of them is reachable in. */
    private static List<Candidate> rank(List<? extends Executable> candidates, PyObject[] args) {
        List<List<Candidate>> phases = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Executable candidate : candidates) {
            Candidate scored = score(candidate, args);
            if (scored != null) {
                phases.get(scored.phase).add(scored);
            }
        }
        for (List<Candidate> phase : phases) {
            if (!phase.isEmpty()) {
                return phase;
            }
        }
        return List.of();
    }

    /** The candidate's shape as this call, or null when the call cannot reach it at all. */
    private static Candidate score(Executable candidate, PyObject[] args) {
        Class<?>[] parameters = candidate.getParameterTypes();
        boolean varargs = candidate.isVarArgs();
        int fixed = varargs ? parameters.length - 1 : parameters.length;
        if (args.length < fixed || (!varargs && args.length != fixed)) {
            return null;
        }
        int worst = JavaConvert.STRICT;
        int cost = 0;
        for (int i = 0; i < fixed; i++) {
            int reached = JavaConvert.phase(args[i], parameters[i]);
            if (reached == JavaConvert.IMPOSSIBLE) {
                return null;
            }
            worst = Math.max(worst, reached);
            cost += JavaConvert.distance(args[i], parameters[i]);
        }
        if (varargs) {
            Class<?> component = parameters[fixed].getComponentType();
            for (int i = fixed; i < args.length; i++) {
                int reached = JavaConvert.phase(args[i], component);
                if (reached == JavaConvert.IMPOSSIBLE) {
                    return null;
                }
                worst = Math.max(worst, reached);
                cost += JavaConvert.distance(args[i], component) + 4;
            }
            // Even an empty tail is the third phase: `join()` must lose to a zero-argument `join()`
            // if the class declares one, which is JLS 15.12.2's order for the same reason.
            worst = JavaConvert.VARARGS;
        }
        return new Candidate(candidate, parameters, worst, cost);
    }

    /** The single candidate to call out of one phase. */
    private static Candidate pick(String name, List<Candidate> applicable) {
        if (applicable.size() == 1) {
            return applicable.get(0);
        }
        int cheapest = applicable.stream().mapToInt(candidate -> candidate.cost).min().orElseThrow();
        List<Candidate> tied = applicable.stream().filter(one -> one.cost == cheapest).toList();
        if (tied.size() == 1) {
            return tied.get(0);
        }
        for (Candidate one : tied) {
            if (tied.stream().allMatch(other -> other == one || moreSpecific(one, other))) {
                return one;
            }
        }
        throw Slots.typeError(
                "call of %s() is ambiguous: %s and %s match equally",
                name, signature(name, tied.get(0)), signature(name, tied.get(1)));
    }

    /** JLS 15.12.2.5 in one loop: every parameter of {@code one} must fit into {@code other}'s. */
    private static boolean moreSpecific(Candidate one, Candidate other) {
        Class<?>[] mine = one.parameters;
        Class<?>[] theirs = other.parameters;
        if (mine.length != theirs.length) {
            return false;
        }
        for (int i = 0; i < mine.length; i++) {
            if (!JavaConvert.boxedForm(theirs[i]).isAssignableFrom(JavaConvert.boxedForm(mine[i]))) {
                return false;
            }
        }
        return true;
    }

    // --- the two failure messages --------------------------------------------------------

    /** The arguments by their Python type names, which is what the caller wrote them as. */
    private static String describeArguments(PyObject[] args) {
        List<String> names = new ArrayList<>();
        for (PyObject arg : args) {
            names.add(arg.getType().name());
        }
        return String.join(", ", names);
    }

    private static String describeCandidates(String name, List<? extends Executable> candidates) {
        List<String> lines = new ArrayList<>();
        for (Executable candidate : candidates) {
            lines.add(signature(name, candidate));
        }
        return String.join(", ", lines);
    }

    private static String signature(String name, Candidate candidate) {
        return signature(name, candidate.executable);
    }

    private static String signature(String name, Executable candidate) {
        return name + "(" + parameterNames(candidate) + ")";
    }

    private static String parameterNames(Executable candidate) {
        Class<?>[] parameters = candidate.getParameterTypes();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            boolean last = i == parameters.length - 1;
            names.add(
                    last && candidate.isVarArgs()
                            ? typeName(parameters[i].getComponentType()) + "..."
                            : typeName(parameters[i]));
        }
        return String.join(", ", names);
    }

    /**
     * A Java type in the spelling a Python reader expects: {@code int}, {@code str}, {@code object}.
     *
     * <p>Only {@code String} and {@code Object} are renamed. A Java type with no Python equivalent keeps
     * its own simple name, because {@code describe(Number, double)} is worth more in an error than
     * {@code describe(number, float)} would be — there is no {@code number} in Python.
     */
    private static String typeName(Class<?> type) {
        if (type == String.class) {
            return "str";
        }
        if (type == Object.class) {
            return "object";
        }
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getSimpleName();
    }

    // --- one applicable candidate --------------------------------------------------------

    private record Candidate(Executable executable, Class<?>[] parameters, int phase, int cost) {

        /** Turn the Python arguments into the Java objects this candidate's signature wants. */
        Object[] convert(PyObject[] args) {
            Object[] out = new Object[parameters.length];
            int fixed = executable.isVarArgs() ? parameters.length - 1 : parameters.length;
            for (int i = 0; i < Math.min(args.length, fixed); i++) {
                out[i] = require(JavaConvert.toJava(args[i], parameters[i]), i);
            }
            if (executable.isVarArgs()) {
                Class<?> component = parameters[fixed].getComponentType();
                Object passed = passedAsTheArray(args, fixed);
                if (passed != null) {
                    out[fixed] = passed;
                    return out;
                }
                Object tail = Array.newInstance(component, Math.max(0, args.length - fixed));
                for (int i = fixed; i < args.length; i++) {
                    Array.set(tail, i - fixed, require(JavaConvert.toJava(args[i], component), i));
                }
                out[fixed] = tail;
            }
            return out;
        }

        /**
         * One argument that already is the array Java would have built, used as it stands.
         *
         * <p>JLS 15.12.2.4 says a variable-arity call whose single trailing argument is assignment
         * compatible with the varargs parameter *is* that parameter, and reflection has no static type to
         * make the call with — {@code Arrays.asList(names)} with a {@code String[]} in hand is the two
         * element list in Java and a one element list of an array if the layer forgets the rule. The same
         * rule reaches a Python {@code list}, because the conversion table already reads a list as an
         * array of its elements; a caller who wants the list as one element has the fixed-arity overload,
         * which an earlier phase prefers.
         */
        private Object passedAsTheArray(PyObject[] args, int fixed) {
            if (args.length != fixed + 1) {
                return null;
            }
            PyObject only = args[fixed];
            if (JavaConvert.phase(only, parameters[fixed]) == JavaConvert.IMPOSSIBLE) {
                return null;
            }
            Object converted = JavaConvert.toJava(only, parameters[fixed]);
            return converted == JavaConvert.NO_CONVERSION ? null : converted;
        }

        private static Object require(Object converted, int position) {
            if (converted == JavaConvert.NO_CONVERSION) {
                throw Slots.typeError("cannot pass argument %d of this call", position);
            }
            return converted;
        }
    }
}
