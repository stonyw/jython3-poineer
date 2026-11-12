package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code _string}: the two helpers {@code string.Formatter} is written on top of.
 *
 * <p>CPython exports these from {@code Objects/unicodeobject.c} because the markup parser lives beside
 * {@code str.format}; the {@code string} package's {@code Formatter} class does not parse anything
 * itself. {@code logging} imports {@code string.Template}, which is the reason the whole package has to
 * import at all, and the package's first line is {@code import _string}.
 *
 * <p>Both functions follow CPython's {@code MarkupIterator} and {@code FieldNameIterator} step for step,
 * including where the errors are raised and what they say, because the case-by-case behaviour of a
 * format string is what {@code test_fstring} measures. The one named deviation is timing: CPython hands
 * back a lazy iterator, so a malformed string only fails at the first {@code next()}, while this runtime
 * parses eagerly and therefore raises from the call. Nothing in the standard library depends on the
 * difference — {@code Formatter.parse} is only ever consumed immediately.
 */
final class StringModule {

    private StringModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_string");
        module.set("__doc__", PyStr.of("string helper module"));
        PyRuntime.markBuiltIn(module, "_string");
        module.set(
                "formatter_field_name_split",
                PyNativeFunction.positional("formatter_field_name_split", StringModule::fieldNameSplit)
                        .documented("split the argument as a field name"));
        module.set(
                "formatter_parser",
                PyNativeFunction.positional("formatter_parser", StringModule::formatterParser)
                        .documented("parse the argument as a format string"));
        return module;
    }

    /**
     * One parsed field: the name before {@code !}/{@code :}, the format spec after it, and the
     * conversion character when the source had a {@code !}.
     *
     * @param next where the parser stands once the field's closing brace has been consumed
     */
    private record Field(String name, String spec, String conversion, int next) {
    }

    /**
     * {@code formatter_parser}: the string split into literal runs and fields.
     *
     * <p>Escaped braces belong to the literal run they appear in — {@code "a{{b"} is two rows,
     * {@code ("a{", None, None, None)} and {@code ("b", None, None, None)} — which is why a row is
     * emitted at every field start and once more for whatever text is left over, and why an empty
     * format string answers no rows at all.
     */
    private static PyObject formatterParser(PyObject[] args) {
        Arguments.exactlyOne("_string.formatter_parser", args, null);
        String text = text(args[0]);
        List<PyObject> rows = new ArrayList<>();
        int at = 0;
        int end = text.length();
        while (at < end) {
            int start = at;
            int c = 0;
            boolean markup = false;
            while (at < end) {
                c = text.charAt(at++);
                if (c == '{' || c == '}') {
                    markup = true;
                    break;
                }
            }
            if (!markup) {
                rows.add(literalRow(text.substring(start, at)));
                break;
            }
            int length = at - start;
            if (c == '}' && (at >= end || text.charAt(at) != '}')) {
                throw Slots.valueError("Single '}' encountered in format string");
            }
            if (at >= end && c == '{') {
                throw Slots.valueError("Single '{' encountered in format string");
            }
            if (at < end && text.charAt(at) == c) {
                // A doubled brace stands for itself: keep the first one and step past the second.
                at++;
                markup = false;
            } else {
                length--;
            }
            String literal = text.substring(start, start + length);
            if (!markup) {
                rows.add(literalRow(literal));
                continue;
            }
            Field field = parseField(text, at, end);
            at = field.next();
            rows.add(
                    PyTuple.of(
                            PyStr.of(literal),
                            PyStr.of(field.name()),
                            PyStr.of(field.spec()),
                            field.conversion() == null
                                    ? PyNone.INSTANCE
                                    : PyStr.of(field.conversion())));
        }
        return PyIterator.of(rows.toArray(new PyObject[0]));
    }

    /**
     * {@code parse_field}: the field name, the conversion and the format spec, in CPython's scan order.
     *
     * <p>The field name runs to the first {@code }}, {@code !} or {@code :}, except that a
     * {@code [...]} subscript is stepped over whole — its contents may hold any of the three. That is
     * also why an unterminated subscript reports the missing brace rather than its own bracket: the
     * scan simply reaches the end of the string with a character that is not a terminator.
     */
    private static Field parseField(String text, int start, int end) {
        int at = start;
        int c = 0;
        while (at < end) {
            c = text.charAt(at++);
            if (c == '{') {
                throw Slots.valueError("unexpected '{' in field name");
            }
            if (c == '[') {
                while (at < end && text.charAt(at) != ']') {
                    at++;
                }
                continue;
            }
            if (c == '}' || c == ':' || c == '!') {
                break;
            }
        }
        String name = text.substring(start, Math.max(start, at - 1));
        if (c == '!' || c == ':') {
            String conversion = null;
            if (c == '!') {
                if (at >= end) {
                    throw Slots.valueError(
                            "end of string while looking for conversion specifier");
                }
                int codepoint = text.codePointAt(at);
                conversion = new String(Character.toChars(codepoint));
                at += Character.charCount(codepoint);
                if (at < end) {
                    c = text.charAt(at++);
                    if (c == '}') {
                        return new Field(name, "", conversion, at);
                    }
                    if (c != ':') {
                        throw Slots.valueError("expected ':' after conversion specifier");
                    }
                }
            }
            int specStart = at;
            int depth = 1;
            while (at < end) {
                c = text.charAt(at++);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return new Field(
                                name, text.substring(specStart, at - 1), conversion, at);
                    }
                }
            }
            throw Slots.valueError("unmatched '{' in format spec");
        }
        if (c != '}') {
            throw Slots.valueError("expected '}' before end of string");
        }
        return new Field(name, "", null, at);
    }

    /**
     * {@code formatter_field_name_split}: the leading index-or-name and a cursor over the rest.
     *
     * <p>The first part is an {@code int} only when every one of its characters is a decimal digit, so
     * {@code "1"} is the index one and {@code "x"} is the attribute name {@code "x"}; the same test
     * decides each {@code [...]} subscript, where a non-numeric spelling is a string key.
     */
    private static PyObject fieldNameSplit(PyObject[] args) {
        Arguments.exactlyOne("_string.formatter_field_name_split", args, null);
        String text = text(args[0]);
        int end = text.length();
        int at = 0;
        while (at < end) {
            char c = text.charAt(at);
            if (c == '.' || c == '[') {
                break;
            }
            at++;
        }
        String first = text.substring(0, at);
        PyObject index = integer(first);
        PyObject firstObject = index != null ? index : PyStr.of(first);
        List<PyObject> rest = new ArrayList<>();
        while (at < end) {
            char c = text.charAt(at++);
            boolean attribute;
            String name;
            if (c == '.') {
                attribute = true;
                int nameStart = at;
                while (at < end) {
                    char next = text.charAt(at);
                    if (next == '.' || next == '[') {
                        break;
                    }
                    at++;
                }
                name = text.substring(nameStart, at);
            } else if (c == '[') {
                attribute = false;
                int nameStart = at;
                boolean closed = false;
                while (at < end) {
                    if (text.charAt(at++) == ']') {
                        closed = true;
                        break;
                    }
                }
                if (!closed) {
                    throw Slots.valueError("Missing ']' in format string");
                }
                name = text.substring(nameStart, at - 1);
            } else {
                throw Slots.valueError(
                        "Only '.' or '[' may follow ']' in format field specifier");
            }
            if (name.isEmpty()) {
                throw Slots.valueError("Empty attribute in format string");
            }
            PyObject value = attribute ? null : integer(name);
            rest.add(PyTuple.of(PyBool.of(attribute), value != null ? value : PyStr.of(name)));
        }
        return PyTuple.of(firstObject, PyIterator.of(rest));
    }

    /**
     * {@code get_integer}: the value of an all-decimal string, or null when it is not one.
     *
     * <p>Decimal digits are Unicode's {@code Nd} category rather than ASCII, which is what makes
     * {@code "{١}".format(...)} index the same as {@code "{1}"}; a string with any other character in
     * it — including a digit that is not {@code Nd}, such as {@code "²"} — stays a name.
     */
    private static PyObject integer(String value) {
        if (value.isEmpty()) {
            return null;
        }
        long accumulator = 0;
        for (int at = 0; at < value.length(); ) {
            int codepoint = value.codePointAt(at);
            at += Character.charCount(codepoint);
            int digit = Character.digit(codepoint, 10);
            if (digit < 0) {
                return null;
            }
            if (accumulator > (Long.MAX_VALUE - digit) / 10) {
                throw Slots.valueError("Too many decimal digits in format string");
            }
            accumulator = accumulator * 10 + digit;
        }
        return PyInt.of(accumulator);
    }

    private static PyObject literalRow(String literal) {
        return PyTuple.of(PyStr.of(literal), PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE);
    }

    /** {@code PyUnicode_Check}: a {@code str}, or anything derived from it. */
    private static String text(PyObject value) {
        if (!(value instanceof PyStr)) {
            // `%T`, which unlike an argument converter's `%s` names None by its type: `NoneType`.
            throw Slots.typeError("expected str, got %s", value.getType().filedName());
        }
        return ((PyStr) value).getValue();
    }
}
