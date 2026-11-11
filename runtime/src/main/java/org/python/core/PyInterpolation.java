package org.python.core;

/**
 * {@code string.templatelib.Interpolation}: one substitution inside a {@link PyTemplate} (PEP 750).
 *
 * <p>It keeps what the source said rather than what the substitution looks like once formatted: the
 * evaluated {@code value}, the text of the expression, the one-character {@code conversion} (or
 * {@code None}), and the {@code format_spec} (empty when the literal had none). A template can
 * therefore be inspected, translated or rendered by something other than {@code str.format}.
 */
public final class PyInterpolation extends PyObject {

    private final PyObject value;
    private final PyStr expression;
    private final PyObject conversion;
    private final PyStr formatSpec;

    private PyInterpolation(
            PyObject value, PyStr expression, PyObject conversion, PyStr formatSpec) {
        super(PyType.INTERPOLATION);
        this.value = value;
        this.expression = expression;
        this.conversion = conversion;
        this.formatSpec = formatSpec;
    }

    /**
     * What {@code BUILD_INTERPOLATION} hands over, with the conversion encoded as CPython encodes it:
     * {@code 0} for none and {@code 1}, {@code 2}, {@code 3} for {@code !s}, {@code !r}, {@code !a}.
     */
    static PyInterpolation of(
            PyObject value, PyObject expression, int conversion, PyObject formatSpec) {
        return new PyInterpolation(
                value,
                (PyStr) expression,
                conversionOf(conversion),
                formatSpec == null ? PyStr.of("") : (PyStr) formatSpec);
    }

    private static PyObject conversionOf(int code) {
        switch (code) {
            case 0:
                return PyNone.INSTANCE;
            case 1:
                return PyStr.of("s");
            case 2:
                return PyStr.of("r");
            case 3:
                return PyStr.of("a");
            default:
                throw new IllegalArgumentException("conversion " + code);
        }
    }

    /** The value of {@code Interpolation(value, expression='', conversion=None, format_spec='')}. */
    static PyObject creating(PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords(
                "Interpolation",
                args,
                kwNames,
                new String[] {"value", "expression", "conversion", "format_spec"},
                1,
                4);
        PyObject expression = given[1] == null ? PyStr.of("") : given[1];
        PyObject conversion = given[2] == null ? PyNone.INSTANCE : given[2];
        PyObject formatSpec = given[3] == null ? PyStr.of("") : given[3];
        if (!(expression instanceof PyStr text)) {
            throw Slots.typeError(
                    "Interpolation() argument 'expression' must be str, not %s",
                    Slots.argumentName(expression));
        }
        if (conversion != PyNone.INSTANCE) {
            if (!(conversion instanceof PyStr conversionText)) {
                throw Slots.typeError(
                        "Interpolation() argument 'conversion' must be str, not %s",
                        Slots.argumentName(conversion));
            }
            String code = conversionText.getValue();
            if (!code.equals("s") && !code.equals("r") && !code.equals("a")) {
                throw Slots.valueError(
                        "Interpolation() argument 'conversion' must be one of 's', 'a' or 'r'");
            }
        }
        if (!(formatSpec instanceof PyStr format)) {
            throw Slots.typeError(
                    "Interpolation() argument 'format_spec' must be str, not %s",
                    Slots.argumentName(formatSpec));
        }
        return new PyInterpolation(given[0], text, conversion, format);
    }

    public PyObject value() {
        return value;
    }

    public PyStr expression() {
        return expression;
    }

    public PyObject conversion() {
        return conversion;
    }

    public PyStr formatSpec() {
        return formatSpec;
    }

    @Override
    public String repr() {
        return "Interpolation(" + Slots.repr(value) + ", " + Slots.repr(expression) + ", "
                + Slots.repr(conversion) + ", " + Slots.repr(formatSpec) + ")";
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    PyObject reduce() {
        return PyTuple.of(
                PyType.INTERPOLATION, PyTuple.of(value, expression, conversion, formatSpec));
    }
}
