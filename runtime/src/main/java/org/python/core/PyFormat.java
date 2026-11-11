package org.python.core;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code annotationlib.Format}: the four ways {@link AnnotationLib} can be asked to read annotations.
 *
 * <p>In CPython the type is an {@code IntEnum}, so a member is an {@code int} that carries a name and a
 * value of its own. This runtime carries the same half of that: every member is a {@link PyInt} whose type
 * is {@link PyType#FORMAT}, which is what makes {@code Format.VALUE == 1} true, makes a member a usable
 * dictionary key beside the integer it equals, and makes arithmetic on one hand back a plain {@code int}.
 *
 * <p>The metatype's share of {@code EnumType} is carried by {@link PyType#FORMAT} itself: subscripting,
 * iteration, length and containment read {@link #members()}, which keeps the four members in declaration
 * order, and {@code __members__} is a mapping proxy over that table. The four members exist as constants,
 * and the class dictionary files them as values so that {@code Format.VALUE} and {@code Format.VALUE.STRING}
 * both resolve. A write to {@code name} or {@code value} is refused in the words CPython's {@code Enum}
 * uses; any other name is kept on the member, because an enum member has a {@code __dict__} and CPython
 * lets one be written.
 */
final class PyFormat extends PyInt {

    static final PyFormat VALUE = new PyFormat("VALUE", 1);

    static final PyFormat VALUE_WITH_FAKE_GLOBALS = new PyFormat("VALUE_WITH_FAKE_GLOBALS", 2);

    static final PyFormat FORWARDREF = new PyFormat("FORWARDREF", 3);

    static final PyFormat STRING = new PyFormat("STRING", 4);

    /** The four members in the order CPython declares them, which is also the order of their values. */
    private static final PyFormat[] VALUES = {
        VALUE, VALUE_WITH_FAKE_GLOBALS, FORWARDREF, STRING
    };

    /**
     * The type's dictionary: the four members and {@code __doc__}.
     *
     * <p>{@code __doc__} is {@code None} because the reference type has no docstring, and this runtime would
     * otherwise answer with an empty one.
     */
    private static final Members MEMBERS = Members.of(PyType.FORMAT)
            .addValue("VALUE", VALUE)
            .addValue("VALUE_WITH_FAKE_GLOBALS", VALUE_WITH_FAKE_GLOBALS)
            .addValue("FORWARDREF", FORWARDREF)
            .addValue("STRING", STRING)
            .addValue("__doc__", PyNone.INSTANCE);

    private final String name;

    private final long value;

    /** The names an enum member accepts beyond its two own, as CPython's {@code Enum} does. */
    private final Map<String, PyObject> extras = new LinkedHashMap<>();

    /**
     * The member table, keyed by name, in the order the type declares the members.
     *
     * <p>This is what the metatype answers {@code __members__} with, and the dictionary
     * {@code Format['VALUE']} reads: one insertion-ordered table rather than one per read, because a
     * mapping proxy over a fresh copy would disagree with the last write to a member's own dictionary.
     */
    private static PyDict members;

    /** The names and members of {@link #VALUES}, in declaration order. */
    static PyDict members() {
        if (members == null) {
            PyDict table = PyDict.empty();
            for (PyFormat member : VALUES) {
                table.put(PyStr.interned(member.name), member);
            }
            members = table;
        }
        return members;
    }

    private PyFormat(String name, long value) {
        super(BigInteger.valueOf(value), PyType.FORMAT);
        this.name = name;
        this.value = value;
    }

    /**
     * {@code Format(value, ...)}: the member whose value equals the argument.
     *
     * <p>CPython's {@code EnumType.__call__} binds {@code value} and {@code names} by hand, which is why
     * the refusals name the metaclass rather than the class, and why a second positional argument sends the
     * call down the functional-API path whose failure quotes the whole argument pair. One integer, float or
     * complex equal to a member's value picks that member; anything else is refused in the enum's words.
     */
    static PyObject called(PyObject[] args, String[] keywordNames) {
        int positional = Arguments.positional(args, keywordNames);
        int keywords = keywordNames == null ? 0 : keywordNames.length;
        PyObject named = null;
        PyObject names = null;
        boolean valueGiven = false;
        boolean namesGiven = false;
        for (int i = 0; i < keywords; i++) {
            String keyword = keywordNames[i];
            PyObject argument = args[positional + i];
            switch (keyword) {
                case "value" -> {
                    if (positional > 0) {
                        throw Slots.typeError(
                                "EnumType.__call__() got multiple values for argument 'value'");
                    }
                    named = argument;
                    valueGiven = true;
                }
                case "names" -> {
                    names = argument;
                    namesGiven = true;
                }
                case "module", "qualname", "type", "start", "boundary" -> {
                    // Read by the functional API, which this type does not have; parsed and dropped.
                }
                default -> throw Slots.typeError(
                        "EnumType.__call__() got an unexpected keyword argument '%s'", keyword);
            }
        }
        if (positional == 0 && !valueGiven) {
            throw Slots.typeError(
                    "EnumType.__call__() missing 1 required positional argument: 'value'");
        }
        if (positional > 1 || namesGiven) {
            java.util.List<PyObject> shown = new java.util.ArrayList<>();
            for (int i = 0; i < positional; i++) {
                shown.add(args[i]);
            }
            if (positional == 0) {
                shown.add(named);
            }
            if (namesGiven) {
                shown.add(names);
            }
            throw Slots.valueError(
                    "%s is not a valid Format",
                    PyTuple.of(shown.toArray(new PyObject[0])).repr());
        }
        PyObject candidate = positional == 1 ? args[0] : named;
        for (PyFormat member : VALUES) {
            if (Slots.compareTrue(CompareOp.EQ, candidate, member)) {
                return member;
            }
        }
        throw Slots.valueError("%s is not a valid Format", candidate.repr());
    }

    /** {@code m.name}: the member's own name, before the values the type files. */
    @Override
    public PyObject getAttr(String attribute) {
        switch (attribute) {
            case "name", "_name_":
                return PyStr.interned(name);
            case "value", "_value_":
                return PyInt.of(value);
            case "_sort_order_":
                return PyInt.of(sortOrder());
            case "__objclass__":
                return PyType.FORMAT;
            case "__dict__":
                return PyMappingProxy.of(memberNamespace());
            default:
                PyObject extra = extras.get(attribute);
                if (extra != null) {
                    return extra;
                }
                return Members.find(this, attribute);
        }
    }

    /**
     * {@code vars(m)}: the four entries {@code Enum} files on every member, then whatever was written.
     *
     * <p>The reference keeps these in the member's instance dictionary and exposes it through a mapping
     * proxy, so {@code vars(Format.VALUE)} reads {@code _value_}, {@code _name_}, {@code __objclass__} and
     * {@code _sort_order_} before any name a caller added.
     */
    private PyDict memberNamespace() {
        PyDict dictionary = PyDict.empty();
        dictionary.put(PyStr.interned("_value_"), PyInt.of(value));
        dictionary.put(PyStr.interned("_name_"), PyStr.interned(name));
        dictionary.put(PyStr.interned("__objclass__"), PyType.FORMAT);
        dictionary.put(PyStr.interned("_sort_order_"), PyInt.of(sortOrder()));
        for (Map.Entry<String, PyObject> entry : extras.entrySet()) {
            dictionary.put(PyStr.interned(entry.getKey()), entry.getValue());
        }
        return dictionary;
    }

    /** This member's position in the declaration order, which is its {@code _sort_order_}. */
    private int sortOrder() {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == this) {
                return i;
            }
        }
        return VALUES.length;
    }

    /**
     * {@code m.name = ...} and {@code m.value = ...}: refused by {@code Enum}, which keeps both read-only.
     *
     * <p>Every other name is written onto the member, in the order it was first written.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        if (attribute.equals("name") || attribute.equals("value")) {
            throw Slots.attributeError("<enum 'Enum'> cannot set attribute '%s'", attribute);
        }
        extras.put(attribute, value);
        return PyNone.INSTANCE;
    }

    /** {@code del m.name} and {@code del m.value}: refused; any other name is removed if it is there. */
    @Override
    public PyObject delAttr(String attribute) {
        if (attribute.equals("name") || attribute.equals("value")) {
            throw Slots.attributeError("<enum 'Enum'> cannot delete attribute '%s'", attribute);
        }
        if (extras.remove(attribute) == null) {
            throw Slots.attributeError(
                    "'%s' object has no attribute '%s'", getType().name(), attribute);
        }
        return PyNone.INSTANCE;
    }

    /** {@code repr(m)}: {@code <Format.VALUE: 1>}, the shape {@code enum} gives every member. */
    @Override
    public String repr() {
        return "<Format." + name + ": " + value + ">";
    }

    /** {@code str(m)}: an {@code IntEnum} prints as the integer it is. */
    @Override
    public String str() {
        return Long.toString(value);
    }
}
