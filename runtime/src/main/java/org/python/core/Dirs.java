package org.python.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The names a value answers {@code dir()} with, and the {@code __dir__} protocol behind them.
 *
 * <p>{@code dir()} has two halves that do not agree with each other. With no argument it sorts the keys of
 * the calling frame's {@code locals()}, so it is the frame's scope and nothing else. With an argument it
 * asks the value itself: {@code _PyObject_LookupSpecial(obj, "__dir__")} is a lookup on the <em>type</em>,
 * which is why an instance attribute named {@code __dir__} is ignored, and what it finds is called with no
 * arguments and its items are taken in whatever order they arrive, duplicates and all. Only when nothing
 * answers does the default walk run — and CPython never has to fall back that way, because {@code object}
 * itself declares {@code __dir__} and so every value inherits one. This runtime's built-in types keep
 * their members in {@link Members} tables and Java switches instead of a dictionary, so none of them
 * declares it and the walk here is what answers for them.
 *
 * <p>That walk is {@code object.__dir__} and {@code type.__dir__}, and both merge names into a
 * <em>dictionary</em>: {@code __dir__ for generic objects: returns __dict__, __class__, and recursively up
 * the __class__.__bases__ chain}, with the class's own dictionary first and the metatype's deliberately
 * left out. Two consequences worth naming. A name the class and {@code object} both declare is offered
 * once, so the answer is a set rather than a list of the MRO's dictionaries; and a value whose {@code
 * __dict__} is not a dictionary at all — a property that answers a list, say — contributes nothing, since
 * the merge only accepts a real dict.
 *
 * <p>Two readings are named omissions rather than pretences. A module answers {@code dir()} from its own
 * dictionary alone, {@code module.__dir__}, which is neither of the two walks above; this runtime reaches
 * a module through no import machinery, so the difference is only visible as {@code dir(sys)}. And the
 * names a built-in type's dictionary holds — the numeric tower's {@code real} and {@code imag}, the dunders
 * CPython copies into every static type — are not in the tables this runtime has, so a {@code dir()} of an
 * {@code int} is shorter than CPython's by more than half.
 */
final class Dirs {

    private Dirs() {
    }

    /**
     * The names {@code object} declares.
     *
     * <p>Measured against {@code sorted(object.__dict__)} of the reference interpreter, which is exactly
     * these 24. Every lookup order ends at {@code object}, and no {@link Members} table of this runtime
     * holds them, because {@code object} answers its operations with Java slots on {@link PyObject}.
     */
    private static final List<String> OBJECT_NAMES =
            List.of(
                    "__class__",
                    "__delattr__",
                    "__dir__",
                    "__doc__",
                    "__eq__",
                    "__format__",
                    "__ge__",
                    "__getattribute__",
                    "__getstate__",
                    "__gt__",
                    "__hash__",
                    "__init__",
                    "__init_subclass__",
                    "__le__",
                    "__lt__",
                    "__ne__",
                    "__new__",
                    "__reduce__",
                    "__reduce_ex__",
                    "__repr__",
                    "__setattr__",
                    "__sizeof__",
                    "__str__",
                    "__subclasshook__");

    /**
     * What {@code dir(obj)} sorts: the items {@code __dir__} answers with, or the default walk's names.
     *
     * <p>Refusals escape, which is the whole point of the shape: a {@code __dir__} that is not callable, or
     * that wants an argument, or that raises while it runs, leaves {@code dir()} with the same complaint
     * the call itself would have raised.
     */
    static List<PyObject> itemsOf(PyObject target) {
        PyObject hook = lookupSpecial(target, "__dir__");
        if (hook != null) {
            return Constructors.materialise(Slots.call(hook, new PyObject[0], null));
        }
        List<PyObject> names = new ArrayList<>();
        for (String name : defaultNames(target)) {
            names.add(PyStr.of(name));
        }
        return names;
    }

    /**
     * CPython's {@code _PyObject_LookupSpecial}: the member the lookup order declares, bound to this value,
     * with the value's own dictionary never asked.
     *
     * @return what would be called, or null where nothing in the order names it
     */
    private static PyObject lookupSpecial(PyObject target, String name) {
        PyType owner = target.getType();
        PyObject member = Specials.member(owner, name);
        return member == null ? null : Descriptors.get(member, target, owner);
    }

    /**
     * The names the default {@code __dir__} answers with, each of them once.
     *
     * <p>A class is walked as {@code type.__dir__} walks it — its own dictionary and its bases' — while a
     * value adds its own {@code __dict__} in front of its class's names, as {@code object.__dir__} does.
     */
    static List<String> defaultNames(PyObject target) {
        Set<String> names = new LinkedHashSet<>();
        if (target instanceof PyType kind) {
            for (PyType owner : kind.mro()) {
                collectOwn(names, owner);
            }
        } else {
            if (Slots.findAttr(target, "__dict__") instanceof PyDict held) {
                collectKeys(names, held.keys());
            }
            for (PyType owner : target.getType().mro()) {
                collectOwn(names, owner);
            }
        }
        return new ArrayList<>(names);
    }

    /** The names one class in the order declares: a Python dictionary, a built-in table, or {@code object}'s. */
    private static void collectOwn(Set<String> names, PyType owner) {
        if (owner instanceof PyClass declaring) {
            collectKeys(names, declaring.namespace().keys());
        }
        Members table = Members.tableOf(owner);
        if (table != null) {
            names.addAll(Arrays.asList(table.names()));
        }
        if (owner == PyType.OBJECT) {
            names.addAll(OBJECT_NAMES);
        }
    }

    /** The keys that are text, added in the order they were given. */
    private static void collectKeys(Set<String> names, PyObject[] keys) {
        for (PyObject key : keys) {
            if (key instanceof PyStr text) {
                names.add(text.getValue());
            }
        }
    }
}
