package org.python.core;

/**
 * {@code x.__class__} — the one name every value answers, whatever its type turns out to be.
 *
 * <p>CPython files it once, as a getset of {@code object}, so the walk that reads an attribute reaches it
 * for an {@code int}, a frame, a class and a generator alike without any of those types saying a word.
 * That is why the answer is here rather than in a {@link Members} table or in a case of thirty value
 * classes: {@link Slots} asks for it only after the value's own type has refused the name, which is what
 * leaves a class that files {@code __class__} in its body — the shadow a proxy writes to pass for the
 * thing it stands in for — the owner of the name, exactly as CPython's lookup order makes it.
 *
 * <p>{@code dir()} has listed the name for as long as this runtime has had {@code dir()}, because
 * {@link Dirs} copies {@code sorted(object.__dict__)}; the read is what was missing beside the listing,
 * and an attempt to use the listed name answered {@code AttributeError}.
 *
 * <p>Writing it is a different thing from writing an attribute. CPython's {@code object_set_class} swaps
 * the value's own type, so {@code type(x)} changes with it and the value's dictionary stays where it was,
 * and it allows that only between two types whose storage agrees. Everything else is refused by one of
 * three sentences, in this order: the value handed over is not a class, one of the two types is a built-in
 * that cannot be swapped for another, the two layouts differ. {@link #write} runs that order, and answers
 * the layout question the way {@code compatible_for_assignment} does for the types this runtime can build
 * — walk each one up past the classes Python wrote, to the built-in type whose storage both of them
 * inherit, and compare that. What has no model here is {@code __slots__}, which {@link Classes} already
 * declines to build: two written classes differing only in their slots would be refused by CPython's
 * layout check and swapped here.
 */
final class ClassAttribute {

    /** The name, which is what {@link Slots} matches a refused read, write or delete against. */
    static final String NAME = "__class__";

    private ClassAttribute() {
    }

    /** {@code x.__class__}: the value's own type, which is the same answer {@code type(x)} gives. */
    static PyObject read(PyObject target) {
        return target.getType();
    }

    /**
     * {@code x.__class__ = value}: {@code object_set_class}, refusals first.
     *
     * <p>The exemption CPython keeps for module subclasses is kept here too, because a module's storage is
     * a dictionary and swapping one module class for another is the case the exemption exists for.
     */
    static void write(PyObject target, PyObject value) {
        if (!(value instanceof PyType claimed)) {
            throw Slots.typeError(
                    "__class__ must be set to a class, not '%s' object", value.getType().name());
        }
        PyType current = target.getType();
        if (!swappable(current, claimed)) {
            throw Slots.typeError(
                    "__class__ assignment only supported for mutable types or ModuleType subclasses");
        }
        if (inheritedFrom(current) != inheritedFrom(claimed)) {
            throw Slots.typeError(
                    "__class__ assignment: '%s' object layout differs from '%s'",
                    claimed.name(), current.name());
        }
        target.reassignedTo(claimed);
    }

    /** {@code del x.__class__}, which no value allows: the name is {@code object}'s to keep. */
    static void delete() {
        throw Slots.typeError("can't delete __class__ attribute");
    }

    /**
     * Whether either type may be swapped for the other at all.
     *
     * <p>CPython reads this off {@code Py_TPFLAGS_IMMUTABLETYPE}, and the only types this runtime marks
     * that way are the ones Python did not build — so a class made by {@link Classes} is mutable and
     * {@code int}, {@code frame} and {@code module} are not. Two module classes are the pair exempted from
     * the rule.
     */
    private static boolean swappable(PyType current, PyType claimed) {
        if (builtByPython(current) && builtByPython(claimed)) {
            return true;
        }
        return current.matches(PyType.MODULE) && claimed.matches(PyType.MODULE);
    }

    private static boolean builtByPython(PyType kind) {
        return kind instanceof PyClass;
    }

    /**
     * The built-in type whose storage a type inherits, which is what CPython compares before it allows a
     * swap: {@code class E(Exception)} and {@code class E2(E)} both rest on {@code Exception} and may be
     * swapped for each other, while {@code E} and a plain class rest on different types and may not.
     */
    private static PyType inheritedFrom(PyType kind) {
        PyType built = kind;
        while (builtByPython(built)) {
            built = built.parent();
        }
        return built;
    }
}
