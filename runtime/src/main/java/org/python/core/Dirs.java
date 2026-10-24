package org.python.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The names a value answers {@code dir()} with, and the {@code __dir__} protocol behind them.
 *
 * <p>{@code dir()} has two halves that do not agree with each other. With no argument it sorts the keys of
 * the calling frame's {@code locals()}, so it is the frame's scope and nothing else. With an argument it
 * asks the value itself: {@code _PyObject_LookupSpecial(obj, "__dir__")} is a lookup on the <em>type</em>,
 * which is why an instance attribute named {@code __dir__} is ignored, what it finds is called with no
 * arguments, and its items are taken in whatever order they arrive, duplicates and all. There is no third
 * answer any more: 3.14 retired {@code default_dir()}, so a value the lookup order leaves without a
 * {@code __dir__} is a {@code TypeError} rather than a walk. {@code object} declares one and every order
 * ends at {@code object}, so no ordinary value can get there — but a class that files {@code __dir__ =
 * None} does get called, because the lookup hands back the {@code None} it found and only the call can
 * complain about it.
 *
 * <p>The two walks are {@code object.__dir__} and {@code type.__dir__}, and both merge names into a
 * <em>dictionary</em>: {@code __dir__ for generic objects: returns __dict__, __class__, and recursively up
 * the __class__.__bases__ chain}. Each of those three names is an ordinary attribute read, which is what
 * makes the walk answerable at all — a value's own {@code __dict__} that is not a real dictionary, like
 * the window a class publishes, contributes nothing, and a {@code __class__} member a class wrote — a
 * property, say — is the class the walk merges, not the one the value was made from. Three consequences
 * worth naming. A name the class and {@code object} both declare is offered once, so the answer is a set
 * rather than a list of the MRO's dictionaries. The order of those dictionaries is explicitly undefined by
 * {@code merge_class_dict}, and only its keys are read, so the bases walk here may be the linearisation.
 * And {@code type.__dir__} merges the class and its bases alone — {@code "We deliberately don't suck up
 * its __class__, as methods belonging to the metaclass would probably be more confusing than helpful"},
 * so {@code dir(Thing)} answers the members a person can inherit rather than the members of {@code type}.
 */
final class Dirs {

    private Dirs() {
    }

    /**
     * What {@code dir(obj)} sorts: the items the {@code __dir__} the order declares answers with.
     *
     * <p>Refusals escape, which is the whole point of the shape: a {@code __dir__} that is not callable, or
     * that wants an argument, or that raises while it runs, leaves {@code dir()} with the same complaint
     * the call itself would have raised. Nothing answering at all is the one case 3.14 made a complaint
     * instead of a walk.
     */
    static List<PyObject> itemsOf(PyObject target) {
        PyObject hook = lookupSpecial(target, "__dir__");
        if (hook == null) {
            throw Slots.typeError("object does not provide __dir__");
        }
        return Constructors.materialise(Slots.call(hook, new PyObject[0], null));
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
     * The names the two default {@code __dir__} walks answer with, each of them once, none of them called.
     *
     * <p>{@link Suggestions} reads the names behind a {@code __dir__} that refused, which is where CPython's
     * {@code _get_safe___dir__} lands too, so this is the walk without the call in front of it.
     */
    static List<String> defaultNames(PyObject target) {
        return target instanceof PyType kind ? classNames(kind) : valueNames(target);
    }

    /** {@code type.__dir__}: the class's own dictionary first, then its bases', and never its metatype's. */
    static List<String> classNames(PyType kind) {
        Set<String> names = new LinkedHashSet<>();
        mergeOrder(names, kind);
        return new ArrayList<>(names);
    }

    /** {@code object.__dir__}: the value's own dictionary, then the order of the class it calls itself. */
    static List<String> valueNames(PyObject target) {
        Set<String> names = new LinkedHashSet<>();
        // The merge only takes a real dictionary, which is why a class answers nothing of its own here:
        // `Thing.__dict__` is a window, and `object.__dir__(Thing)` is the metatype's names alone.
        if (Slots.findAttr(target, "__dict__") instanceof PyDict held) {
            collectKeys(names, held.keys());
        }
        // The class is read, not taken from the value's type: `__class__` is a member a class may answer
        // with a property, and CPython merges the class that answer names.
        if (Slots.findAttr(target, "__class__") instanceof PyType declared) {
            mergeOrder(names, declared);
        }
        return new ArrayList<>(names);
    }

    /** {@code merge_class_dict}: one class's own dictionary, then the same for every class it descends. */
    private static void mergeOrder(Set<String> names, PyType kind) {
        for (PyType owner : kind.mro()) {
            collectKeys(names, owner.namespace().keys());
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
