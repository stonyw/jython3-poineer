package org.python.core;

/**
 * The part of {@code eval} that annotations use: a name, a dotted path, a subscription, or a constant.
 *
 * <p>{@code ForwardRef.evaluate} and {@code get_annotations(..., eval_str=True)} both hand a string to
 * CPython's evaluator, and this runtime has no evaluator to hand it to — a code object here starts life as
 * the product of the front end's compiler, and the runtime has no source-level entry point. What the two
 * callers actually need is narrower than the language: an annotation is written as {@code int},
 * {@code typing.Sequence[str]}, {@code list[int]}, {@code "text"}, {@code 42} or {@code ...}. Those parse
 * here, resolve against the caller's namespaces, and anything else is refused as the syntax it is.
 *
 * <p>What is not carried is named rather than faked: operators (including the {@code X | Y} union spelling),
 * calls, comprehension and every other expression shape this parser does not know are {@code SyntaxError},
 * because answering them with a plausible wrong value would be worse than saying no.
 */
final class AnnotationEval {

    /** The source being read, and where the read has reached. */
    private final String source;

    private int index;

    private AnnotationEval(String source) {
        this.source = source;
    }

    /**
     * Evaluate {@code source} against {@code globals} and {@code locals}, then the builtins.
     *
     * @param globals the globals to look names up in first, or null
     * @param locals the locals to look names up in before {@code globals}, or null
     * @throws PyRaise {@code SyntaxError} for an expression this parser does not read, {@code NameError} for
     *     a name no namespace has
     */
    static PyObject evaluate(String source, PyObject globals, PyObject locals) {
        AnnotationEval reader = new AnnotationEval(source);
        PyObject value = reader.expression(globals, locals);
        reader.skipSpace();
        if (reader.index != source.length()) {
            throw reader.refusal();
        }
        return value;
    }

    /** The sentence CPython's compiler raises for a source it cannot parse in an annotation. */
    static PyRaise syntaxError(String source) {
        String message = "Forward reference must be an expression -- got '" + source + "'";
        PyRaise failure = PyRaise.raise(ExceptionTypes.SYNTAX_ERROR, message);
        failure.value().asSyntaxMessage(message);
        return failure;
    }

    private PyRaise refusal() {
        return syntaxError(source);
    }

    private PyObject expression(PyObject globals, PyObject locals) {
        return postfix(atom(globals, locals), globals, locals);
    }

    /** An atom followed by any number of {@code .name} and {@code [key]} reads. */
    private PyObject atom(PyObject globals, PyObject locals) {
        skipSpace();
        if (index >= source.length()) {
            throw refusal();
        }
        char first = source.charAt(index);
        if (first == '(') {
            index++;
            PyObject inner = expression(globals, locals);
            skipSpace();
            if (index >= source.length() || source.charAt(index) != ')') {
                throw refusal();
            }
            index++;
            return inner;
        }
        if (source.startsWith("...", index)) {
            index += 3;
            return PyEllipsis.INSTANCE;
        }
        if (first == '"' || first == '\'') {
            return PyStr.of(stringLiteral(first));
        }
        if (first == '-' || Character.isDigit(first)) {
            return number();
        }
        if (Character.isJavaIdentifierStart(first)) {
            return name(globals, locals);
        }
        throw refusal();
    }

    private PyObject postfix(PyObject value, PyObject globals, PyObject locals) {
        while (true) {
            skipSpace();
            if (index >= source.length()) {
                return value;
            }
            char next = source.charAt(index);
            if (next == '.') {
                index++;
                skipSpace();
                String attribute = identifier();
                value = Slots.getAttr(value, attribute);
            } else if (next == '[') {
                index++;
                value = Slots.getItem(value, subscript(globals, locals));
            } else {
                return value;
            }
        }
    }

    /** {@code a, b, c} inside brackets becomes one tuple key, as CPython's subscript does. */
    private PyObject subscript(PyObject globals, PyObject locals) {
        PyObject first = expression(globals, locals);
        skipSpace();
        if (index < source.length() && source.charAt(index) == ',') {
            java.util.List<PyObject> items = new java.util.ArrayList<>();
            items.add(first);
            while (index < source.length() && source.charAt(index) == ',') {
                index++;
                skipSpace();
                if (index < source.length() && source.charAt(index) == ']') {
                    break;
                }
                items.add(expression(globals, locals));
                skipSpace();
            }
            if (index >= source.length() || source.charAt(index) != ']') {
                throw refusal();
            }
            index++;
            return PyTuple.of(items.toArray(new PyObject[0]));
        }
        if (index >= source.length() || source.charAt(index) != ']') {
            throw refusal();
        }
        index++;
        return first;
    }

    private PyObject name(PyObject globals, PyObject locals) {
        String name = identifier();
        switch (name) {
            case "None":
                return PyNone.INSTANCE;
            case "True":
                return PyBool.TRUE;
            case "False":
                return PyBool.FALSE;
            default:
                break;
        }
        PyObject value = lookup(locals, name);
        if (value == null) {
            value = lookup(globals, name);
        }
        if (value == null) {
            value = PyRuntime.builtins().get(PyStr.interned(name));
        }
        if (value == null) {
            throw Slots.nameError(name);
        }
        return value;
    }

    private static PyObject lookup(PyObject namespace, String name) {
        if (namespace == null || namespace == PyNone.INSTANCE) {
            return null;
        }
        PyObject key = PyStr.interned(name);
        if (namespace instanceof PyDict dict) {
            return dict.get(key);
        }
        try {
            return Slots.getItem(namespace, key);
        } catch (PyRaise refusal) {
            if (refusal.value().exceptionType().matches(ExceptionTypes.KEY_ERROR)) {
                return null;
            }
            throw refusal;
        }
    }

    private String identifier() {
        int start = index;
        while (index < source.length() && Character.isJavaIdentifierPart(source.charAt(index))) {
            index++;
        }
        if (start == index) {
            throw refusal();
        }
        return source.substring(start, index);
    }

    /** A number with an optional sign, fraction and exponent; the decimal point decides its type. */
    private PyObject number() {
        int start = index;
        if (source.charAt(index) == '-') {
            index++;
        }
        boolean fractional = false;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (Character.isDigit(c) || c == '_') {
                index++;
            } else if (c == '.' && !fractional) {
                fractional = true;
                index++;
            } else if ((c == 'e' || c == 'E') && index + 1 < source.length()) {
                fractional = true;
                index++;
                if (source.charAt(index) == '+' || source.charAt(index) == '-') {
                    index++;
                }
            } else {
                break;
            }
        }
        String text = source.substring(start, index).replace("_", "");
        try {
            return fractional ? PyFloat.of(Double.parseDouble(text)) : PyInt.of(Long.parseLong(text));
        } catch (NumberFormatException notANumber) {
            throw refusal();
        }
    }

    /** A quoted string, with the handful of escapes an annotation can plausibly carry. */
    private String stringLiteral(char quote) {
        index++;
        StringBuilder text = new StringBuilder();
        while (index < source.length()) {
            char c = source.charAt(index++);
            if (c == quote) {
                return text.toString();
            }
            if (c != '\\' || index >= source.length()) {
                text.append(c);
                continue;
            }
            char escaped = source.charAt(index++);
            switch (escaped) {
                case 'n' -> text.append('\n');
                case 't' -> text.append('\t');
                case 'r' -> text.append('\r');
                default -> text.append(escaped);
            }
        }
        throw refusal();
    }

    private void skipSpace() {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
    }
}
