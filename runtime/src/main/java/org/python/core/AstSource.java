package org.python.core;

import java.util.Map;

/**
 * Writes a tree of {@code _ast} nodes as the constructor expression the reference compiler reads.
 *
 * <p>The reference owns the node semantics — which field names a node has, what a value in each
 * field must be, which mode accepts which root — and the runtime owns the node objects. Passing the
 * tree across as text keeps that split: the reference rebuilds its own nodes from this expression and
 * validates them itself, so a missing field or a wrong node type is refused with the sentence CPython
 * would have used, not with an imitation written here. The expression is evaluated in the reference
 * driver, not by this runtime.
 *
 * <p>Values that have an exact Python literal get one ({@code str}, {@code bytes}, {@code int},
 * {@code tuple}, ...). Values the reference will reject as a field type are still carried with their
 * own type name ({@link #opaque}), so the refusal names the type the user actually passed. Nothing is
 * guessed: a value this writer cannot express becomes an object whose class has the same name, and
 * the reference decides whether that is legal.
 */
final class AstSource {

    private AstSource() {
    }

    /**
     * The expression that rebuilds {@code tree} in the reference interpreter.
     *
     * @throws PyRaise when reading a field raises something other than {@code AttributeError}, which
     *     is the same propagation {@code PyObject_GetOptionalAttr} makes on the reference side
     */
    static String write(PyObject tree) {
        PyModule ast = AstModule.install();
        StringBuilder out = new StringBuilder();
        write(out, tree, (PyType) ast.get("AST"));
        return out.toString();
    }

    /** Whether {@code value} is an {@code _ast.AST} too, and therefore compiles as a tree. */
    static boolean isAst(PyObject value) {
        PyModule ast = AstModule.install();
        return Classes.subclassOf(value.getType(), ast.get("AST"));
    }

    private static void write(StringBuilder out, PyObject value, PyType astRoot) {
        if (value instanceof PyStr text) {
            quote(out, text.getValue());
        } else if (value instanceof PyBytes bytes) {
            out.append("_jy3_bytes('").append(base64(bytes.getValue())).append("')");
        } else if (value instanceof PyByteArray || value instanceof PyMemoryView) {
            out.append("_jy3_bytearray('")
                    .append(base64(PyMemoryView.bufferOctets(value)))
                    .append("')");
        } else if (value instanceof PyBool flag) {
            out.append(flag == PyBool.TRUE ? "True" : "False");
        } else if (value instanceof PyInt number) {
            out.append(number.getValue());
        } else if (value instanceof PyFloat number) {
            out.append("float.fromhex('").append(hex(number.getValue())).append("')");
        } else if (value instanceof PyComplex number) {
            out.append("_jy3_complex('").append(hex(number.real())).append("', '")
                    .append(hex(number.imaginary())).append("')");
        } else if (value == PyNone.INSTANCE) {
            out.append("None");
        } else if (value == PyEllipsis.INSTANCE) {
            out.append("Ellipsis");
        } else if (value instanceof PyTuple tuple) {
            out.append('(');
            for (int i = 0; i < tuple.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                write(out, tuple.at(i), astRoot);
            }
            if (tuple.size() == 1) {
                out.append(',');
            }
            out.append(')');
        } else if (value instanceof PyList list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                write(out, list.at(i), astRoot);
            }
            out.append(']');
        } else if (value instanceof PyDict map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<PyObject, PyObject> entry : map.getItems().entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                write(out, entry.getKey(), astRoot);
                out.append(": ");
                write(out, entry.getValue(), astRoot);
            }
            out.append('}');
        } else if (value instanceof PySet set) {
            if (set.size() == 0) {
                out.append("set()");
            } else {
                braces(out, set.elements(), astRoot);
            }
        } else if (value instanceof PyFrozenSet set) {
            out.append("frozenset(");
            braces(out, set.elements(), astRoot);
            out.append(')');
        } else if (Classes.subclassOf(value.getType(), astRoot)) {
            node(out, value, astRoot);
        } else {
            opaque(out, value.getType().name());
        }
    }

    private static void braces(StringBuilder out, PyObject[] items, PyType astRoot) {
        out.append('{');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            write(out, items[i], astRoot);
        }
        out.append('}');
    }

    /**
     * One node: a plain constructor when the reference knows the class, otherwise a class created
     * under the same name and the nearest known base. A node class defined by the user keeps its
     * name, which is what the reference puts in "expected some sort of ..." reports.
     */
    private static void node(StringBuilder out, PyObject value, PyType astRoot) {
        PyType type = value.getType();
        String base = nearestKnown(type, astRoot);
        boolean known = base.equals(type.name());
        if (known) {
            out.append(type.name()).append('(');
        } else {
            out.append("_jy3_node(");
            quote(out, type.name());
            out.append(", ").append(base).append(", {");
        }
        boolean first = true;
        for (String name : names(type, "_fields")) {
            PyObject field = optional(value, name);
            if (field == null) {
                continue;
            }
            if (!first) {
                out.append(", ");
            }
            first = false;
            if (known) {
                out.append(name).append('=');
            } else {
                quote(out, name);
                out.append(": ");
            }
            write(out, field, astRoot);
        }
        for (String name : names(type, "_attributes")) {
            PyObject field = optional(value, name);
            if (field == null) {
                continue;
            }
            if (!first) {
                out.append(", ");
            }
            first = false;
            if (known) {
                out.append(name).append('=');
            } else {
                quote(out, name);
                out.append(": ");
            }
            write(out, field, astRoot);
        }
        if (known) {
            out.append(')');
        } else {
            out.append("})");
        }
    }

    /** The first class on the node's MRO the {@code _ast} module knows, or {@code AST}. */
    private static String nearestKnown(PyType type, PyType astRoot) {
        PyModule ast = AstModule.install();
        for (PyType candidate : type.mro()) {
            if (ast.get(candidate.name()) instanceof PyType) {
                return candidate.name();
            }
        }
        return "AST";
    }

    /** The names a node's class declares in {@code _fields} or {@code _attributes}. */
    private static String[] names(PyType type, String attribute) {
        PyObject declared = Specials.member(type, attribute);
        if (!(declared instanceof PyTuple tuple)) {
            return new String[0];
        }
        String[] out = new String[tuple.size()];
        for (int i = 0; i < tuple.size(); i++) {
            out[i] = tuple.at(i) instanceof PyStr name ? name.getValue() : "";
        }
        return out;
    }

    /**
     * The field's value, or {@code null} when the node simply does not have it.
     *
     * <p>{@code PyObject.getAttr} answers {@link Slots#DECLINED} for a name an instance does not hold,
     * and a type that refuses the read outright raises {@code AttributeError}; both mean "not set" to
     * the reference's {@code PyObject_GetOptionalAttr}, so both mean the same here.
     */
    private static PyObject optional(PyObject node, String name) {
        PyObject found;
        try {
            found = node.getAttr(name);
        } catch (PyRaise raised) {
            if (!raised.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                throw raised;
            }
            return null;
        }
        return found == Slots.DECLINED ? null : found;
    }

    private static void opaque(StringBuilder out, String typeName) {
        out.append("_jy3_opaque(");
        quote(out, typeName);
        out.append(')');
    }

    /** A Python string literal with every code unit escaped, so lone surrogates survive too. */
    private static void quote(StringBuilder out, String text) {
        out.append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c >= 0x20 && c < 0x7f) {
                        out.append(c);
                    } else {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                }
            }
        }
        out.append('\'');
    }

    /** {@code float.hex}, written here so the reference's parser sees one exact decimal value. */
    private static String hex(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        return Double.toHexString(value);
    }

    private static String base64(byte[] content) {
        return java.util.Base64.getEncoder().encodeToString(content);
    }
}
