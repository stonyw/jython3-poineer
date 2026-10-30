package org.python.core;

public final class PyComplex extends PyObject {

    private final double real;
    private final double imaginary;

    private PyComplex(double real, double imaginary) {
        super(PyType.COMPLEX);
        this.real = real;
        this.imaginary = imaginary;
    }

    public static PyComplex of(double real, double imaginary) {
        return new PyComplex(real, imaginary);
    }

    public double real() {
        return real;
    }

    public double imaginary() {
        return imaginary;
    }

    PyFloat realPart() {
        return PyFloat.of(real);
    }

    boolean imaginaryIsZero() {
        return imaginary == 0.0;
    }

    // --- numeric slots ---------------------------------------------------------------------

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        return Numeric.apply(op, this, other);
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return Numeric.apply(op, other, this);
    }

    @Override
    public PyObject unaryOp(UnaryOp op) {
        return Numeric.unary(op, this);
    }

    /**
     * A complex is false only when both parts are zero.
     *
     * Ordering is not here because it is not a slot of {@code complex} in CPython either: the base
     * class declines an ordering test, and {@link Slots} turns two declinations into the error that
     * names both types.
     */
    @Override
    public PyObject truth() {
        return PyBool.of(real != 0.0 || imaginary != 0.0);
    }

    /**
     * The five names of {@code complex_methods}, bound.
     *
     * <p>{@code real} and {@code imag} are not here: they are {@code member_descriptor}s filed in the
     * type's own dictionary, which the ordinary read reaches through {@link TypeDict} — and they are read
     * twice over in this file, once by the descriptor and once by {@link #realPart()}.
     */
    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    private static final Members METHODS = Members.of(PyType.COMPLEX)
            .addNoArgument("conjugate", PyComplex::conjugated)
            .addNoArgument("__complex__", PyComplex::exactly)
            .addNoArgument("__getnewargs__", PyComplex::newArguments)
            .addOne("__format__", PyComplex::formatted)
            .addClassNamed("from_number", PyComplex::fromNumber)
            // The five sentences `complex_methods` files (Objects/complexobject.c:1331), every one of them
            // read out of `Objects/clinic/complexobject.c.h` through the `*_METHODDEF` macro the table
            // names — `complex` writes no row by hand, and so all five carry a table of their own.
            // `__getnewargs__` is the silence of a table with nothing written under it, `from_number` is
            // the one `$type` row whose `METH_CLASS` flag makes `complex.__dict__` file a
            // `classmethod_descriptor` while the other four are plain `method_descriptor`s, and
            // `__format__` is the one `METH_O`.
            .document(
                    "from_number",
                    "from_number($type, number, /)\n"
                            + "--\n"
                            + "\n"
                            + "Convert number to a complex floating-point number.")
            .document(
                    "conjugate",
                    "conjugate($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return the complex conjugate of its argument."
                            + " (3-4j).conjugate() == 3+4j.")
            .document(
                    "__complex__",
                    "__complex__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Convert this value to exact type complex.")
            .document("__getnewargs__", "__getnewargs__($self, /)\n--\n\n")
            .document(
                    "__format__",
                    "__format__($self, format_spec, /)\n"
                            + "--\n"
                            + "\n"
                            + "Convert to a string according to format_spec.");

    /** {@code complex_conjugate}: the same real part and the imaginary part the other sign. */
    private static PyObject conjugated(PyObject self) {
        PyComplex z = (PyComplex) self;
        return PyComplex.of(z.real(), -z.imaginary());
    }

    /** {@code complex___complex___impl}: the value itself, since nothing under it needs converting. */
    private static PyObject exactly(PyObject self) {
        return self;
    }

    /** {@code complex___getnewargs___impl}: the two doubles {@code __new__} was built from. */
    private static PyObject newArguments(PyObject self) {
        PyComplex z = (PyComplex) self;
        return PyTuple.of(PyFloat.of(z.real()), PyFloat.of(z.imaginary()));
    }

    /**
     * {@code complex.__format__} over the one spec this runtime reads.
     *
     * <p>With nothing between the braces CPython asks for {@code str(self)} and so answers the repr; the
     * float-shaped specs are the writer's own half, which the plan names as the next of this type's
     * readings. The refusal of a spec that is not text is the clinic's, and names itself bare.
     */
    private static PyObject formatted(PyObject self, PyObject spec) {
        if (!(spec instanceof PyStr text)) {
            throw Slots.typeError(
                    "__format__() argument must be str, not %s", Slots.argumentName(spec));
        }
        if (text.getValue().isEmpty()) {
            return PyStr.of(self.str());
        }
        throw Slots.typeError(
                "unsupported format string passed to %s.__format__", self.getType().name());
    }

    /**
     * {@code complex_from_number}: the number read as the pair it stands for.
     *
     * <p>A complex passes straight through — {@code complex.from_number(z) is z} — because
     * {@code _PyNumber_ConvertRealToDouble} is not what this row calls; anything else real becomes the
     * float it converts to with nothing in the imaginary direction, and the refusal of a string or a
     * {@code None} is the conversion's own, naming no caller.
     */
    private static PyObject fromNumber(PyObject type, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("complex.from_number()", args, kwNames);
        if (args[0] instanceof PyComplex taken) {
            return taken;
        }
        Double real = Numeric.doubleOf(args[0]);
        if (real == null) {
            throw Slots.typeError(
                    "must be real number, not %s", args[0].getType().filedName());
        }
        return PyComplex.of(real, 0.0);
    }

    @Override
    public long hash() {
        return PyHash.complexHash(real, imaginary);
    }

    /**
     * A complex is the most general of the numeric types, so it answers comparisons with float and
     * int itself rather than passing them back — {@code float} and {@code int} both defer here, and
     * a complex that deferred too would recurse.
     */
    @Override
    public boolean eq(PyObject other) {
        if (other instanceof PyComplex c) {
            return real == c.real && imaginary == c.imaginary;
        }
        if (other instanceof PyFloat f) {
            return imaginary == 0.0 && real == f.getValue();
        }
        if (other instanceof PyInt || other instanceof PyBool) {
            return imaginary == 0.0 && realPart().eq(other);
        }
        return false;
    }

    /**
     * CPython's {@code complex_repr}: a positive real zero hides the real part and the parentheses
     * with it, every part is a float repr without the {@code .0}, and the imaginary part always
     * carries a sign.
     */
    @Override
    public String repr() {
        if (real == 0.0 && Double.compare(real, -0.0) != 0) {
            return PyFloat.format(imaginary, false) + "j";
        }
        return "(" + PyFloat.format(real, false) + signedImaginary() + "j)";
    }

    private String signedImaginary() {
        String part = PyFloat.format(imaginary, false);
        return part.startsWith("-") ? part : "+" + part;
    }
}
