package org.python.core;

import java.util.List;

/**
 * {@code _operator}: the C half of {@code operator}.
 *
 * <p>{@code Lib/operator.py} is the pure-Python implementation of the module, and its last statement is
 * {@code from _operator import *}: every name the C module answers shadows the Python definition above
 * it. That is what {@code test_tarfile} ran into — {@code glob.py} writes {@code concat_path =
 * operator.add} as a class attribute, and a C function is not a descriptor, so {@code self.concat_path
 * (path, text)} passes two arguments. With the Python fallback in place the attribute bound itself and
 * the call arrived with three.
 *
 * <p>So the functions here are the ones the reference declares over slots, and each is a thin call into
 * the same machinery the interpreter uses for the operator itself: {@link Slots#binary} for the
 * arithmetic and bitwise pairs (reflected methods and all), {@link Slots#augmented} for the {@code i*}
 * family, {@link Slots#compare} for the comparisons. One stated deviation: the reference's 3.14
 * {@code _operator} also carries {@code attrgetter}, {@code itemgetter} and {@code methodcaller} as types,
 * while here they stay in {@code operator.py}, whose classes are then not overridden by
 * {@code from _operator import *}. The {@code operator} surface is therefore the reference's — the three
 * names live one module lower than they do there.
 */
final class OperatorModule {

    private static final String DOC =
            "Operator interface.\n"
                    + "\n"
                    + "This module exports a set of functions implemented in C corresponding\n"
                    + "to the intrinsic operators of Python.  For example, operator.add(x, y)\n"
                    + "is equivalent to the expression x+y.  The function names are those\n"
                    + "used for special methods; variants without leading and trailing\n"
                    + "'__' are also provided for convenience.";

    private OperatorModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_operator");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_operator");

        // Comparison.
        module.set("lt", compare("lt", CompareOp.LT));
        module.set("le", compare("le", CompareOp.LE));
        module.set("eq", compare("eq", CompareOp.EQ));
        module.set("ne", compare("ne", CompareOp.NE));
        module.set("gt", compare("gt", CompareOp.GT));
        module.set("ge", compare("ge", CompareOp.GE));

        // Logical.
        module.set("not_", unary("not_", OperatorModule::not_));
        module.set("truth", unary("truth", value -> PyBool.of(Slots.truth(value))));
        module.set("is_", pair("is_", (left, right) -> PyBool.of(left == right)));
        module.set("is_not", pair("is_not", (left, right) -> PyBool.of(left != right)));
        module.set("is_none", unary("is_none", value -> PyBool.of(value instanceof PyNone)));
        module.set("is_not_none", unary("is_not_none", value -> PyBool.of(!(value instanceof PyNone))));

        // Arithmetic and bitwise.
        module.set("abs", unary("abs", Slots::absolute));
        module.set("add", binary("add", BinaryOp.ADD));
        module.set("and_", binary("and_", BinaryOp.AND));
        module.set("floordiv", binary("floordiv", BinaryOp.FLOORDIV));
        module.set("index", unary("index", OperatorModule::index));
        module.set("inv", unary("inv", value -> Slots.unary(UnaryOp.INVERT, value)));
        module.set("invert", unary("invert", value -> Slots.unary(UnaryOp.INVERT, value)));
        module.set("lshift", binary("lshift", BinaryOp.LSHIFT));
        module.set("mod", binary("mod", BinaryOp.MOD));
        module.set("mul", binary("mul", BinaryOp.MUL));
        module.set("matmul", binary("matmul", BinaryOp.MATMUL));
        module.set("neg", unary("neg", value -> Slots.unary(UnaryOp.NEGATIVE, value)));
        module.set("or_", binary("or_", BinaryOp.OR));
        module.set("pos", unary("pos", value -> Slots.unary(UnaryOp.POSITIVE, value)));
        module.set("pow", binary("pow", BinaryOp.POW));
        module.set("rshift", binary("rshift", BinaryOp.RSHIFT));
        module.set("sub", binary("sub", BinaryOp.SUB));
        module.set("truediv", binary("truediv", BinaryOp.TRUEDIV));
        module.set("xor", binary("xor", BinaryOp.XOR));

        // Sequence operations.
        module.set("concat", binary("concat", BinaryOp.ADD));
        module.set("contains", pair("contains", (container, item) -> PyBool.of(Slots.contains(container, item))));
        module.set("countOf", pair("countOf", OperatorModule::countOf));
        module.set("indexOf", pair("indexOf", OperatorModule::indexOf));
        module.set("getitem", pair("getitem", Slots::getItem));
        module.set("setitem", setItem());
        module.set("delitem", delItem());
        module.set("length_hint", lengthHint());
        module.set("call", call());

        // In-place variants, which fall back to the plain pair for an immutable answer.
        module.set("iadd", inplace("iadd", BinaryOp.ADD));
        module.set("iand", inplace("iand", BinaryOp.AND));
        module.set("iconcat", inplace("iconcat", BinaryOp.ADD));
        module.set("ifloordiv", inplace("ifloordiv", BinaryOp.FLOORDIV));
        module.set("ilshift", inplace("ilshift", BinaryOp.LSHIFT));
        module.set("imatmul", inplace("imatmul", BinaryOp.MATMUL));
        module.set("imod", inplace("imod", BinaryOp.MOD));
        module.set("imul", inplace("imul", BinaryOp.MUL));
        module.set("ior", inplace("ior", BinaryOp.OR));
        module.set("ipow", inplace("ipow", BinaryOp.POW));
        module.set("irshift", inplace("irshift", BinaryOp.RSHIFT));
        module.set("isub", inplace("isub", BinaryOp.SUB));
        module.set("itruediv", inplace("itruediv", BinaryOp.TRUEDIV));
        module.set("ixor", inplace("ixor", BinaryOp.XOR));

        module.set("_compare_digest", pair("_compare_digest", OperatorModule::compareDigest));
        return module;
    }

    // --- shapes -------------------------------------------------------------------------------------

    private static PyNativeFunction unary(String name, java.util.function.Function<PyObject, PyObject> body) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.onlyPositional(name, args, kwNames, 1);
            return body.apply(args[0]);
        });
    }

    private static PyNativeFunction pair(
            String name, java.util.function.BiFunction<PyObject, PyObject, PyObject> body) {
        return new PyNativeFunction(name, (args, kwNames) -> {
            Arguments.onlyPositional(name, args, kwNames, 2);
            return body.apply(args[0], args[1]);
        });
    }

    private static PyNativeFunction binary(String name, BinaryOp op) {
        return pair(name, (left, right) -> Slots.binary(op, left, right));
    }

    private static PyNativeFunction compare(String name, CompareOp op) {
        return pair(name, (left, right) -> Slots.compare(op, left, right));
    }

    /**
     * The {@code i*} family: {@code operator.iadd(a, b)} is {@code a += b} with the answer returned, so
     * a mutable left operand is asked for its in-place slot first and only its refusal falls back to the
     * plain pair.
     */
    private static PyNativeFunction inplace(String name, BinaryOp op) {
        return pair(name, (left, right) -> Slots.augmented(op, left, right, true));
    }

    // --- bodies -------------------------------------------------------------------------------------

    private static PyObject not_(PyObject value) {
        return PyBool.of(!Slots.truth(value));
    }

    private static PyObject index(PyObject value) {
        if (!Numbers.isIndex(value)) {
            throw Slots.typeError(
                    "'%s' object cannot be interpreted as an integer", value.getType().filedName());
        }
        return PyInt.of(Numbers.asIndex(value));
    }

    private static PyObject countOf(PyObject container, PyObject item) {
        List<PyObject> elements = Constructors.materialise(container);
        int count = 0;
        for (PyObject element : elements) {
            if (same(element, item)) {
                count++;
            }
        }
        return PyInt.of(count);
    }

    private static PyObject indexOf(PyObject container, PyObject item) {
        List<PyObject> elements = Constructors.materialise(container);
        for (int i = 0; i < elements.size(); i++) {
            if (same(elements.get(i), item)) {
                return PyInt.of(i);
            }
        }
        throw Slots.valueError("%s is not in %s", Slots.repr(item), container.getType().filedName());
    }

    private static boolean same(PyObject left, PyObject right) {
        return Slots.truth(Slots.compare(CompareOp.EQ, left, right));
    }

    private static PyNativeFunction lengthHint() {
        return new PyNativeFunction("length_hint", (args, kwNames) -> {
            Arguments.between("length_hint", args, kwNames, 1, 2);
            int fallback = args.length > 1 ? Numbers.asIndex(args[1]).intValue() : 0;
            return PyInt.of(Slots.lengthHint(args[0], fallback));
        });
    }

    private static PyNativeFunction setItem() {
        return new PyNativeFunction("setitem", (args, kwNames) -> {
            Arguments.onlyPositional("setitem", args, kwNames, 3);
            Slots.setItem(args[0], args[1], args[2]);
            return PyNone.INSTANCE;
        });
    }

    private static PyNativeFunction delItem() {
        return new PyNativeFunction("delitem", (args, kwNames) -> {
            Arguments.onlyPositional("delitem", args, kwNames, 2);
            Slots.delItem(args[0], args[1]);
            return PyNone.INSTANCE;
        });
    }

    /** {@code operator.call(obj, /, *args, **kwargs)}: the three-argument {@code PyObject_Call}. */
    private static PyNativeFunction call() {
        return new PyNativeFunction("call", (args, kwNames) -> {
            if (Arguments.positional(args, kwNames) < 1) {
                throw Slots.typeError("call() missing 1 required positional argument: 'obj'");
            }
            // The calling convention keeps the keyword values as the trailing entries of args, so both
            // the rest of the positionals and those values are what the callable is handed.
            PyObject[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            return Slots.call(args[0], rest, PyBoundMethod.keywordTuple(kwNames));
        });
    }

    /**
     * {@code hmac}'s constant-time comparison. A string and a bytes-like operand cannot be compared, and
     * a string must be ASCII because the reference compares the C string rather than coded code points.
     */
    private static PyObject compareDigest(PyObject left, PyObject right) {
        boolean leftIsText = left instanceof PyStr;
        boolean rightIsText = right instanceof PyStr;
        if (leftIsText != rightIsText) {
            throw Slots.typeError("unsupported operand types(s) or combination of types: '%s' and '%s'",
                    left.getType().filedName(), right.getType().filedName());
        }
        byte[] first;
        byte[] second;
        if (leftIsText) {
            first = asciiOctets((PyStr) left);
            second = asciiOctets((PyStr) right);
        } else {
            first = PyMemoryView.bufferOctets(left);
            second = PyMemoryView.bufferOctets(right);
        }
        int difference = first.length ^ second.length;
        int shorter = Math.min(first.length, second.length);
        for (int i = 0; i < shorter; i++) {
            difference |= first[i] ^ second[i];
        }
        return PyBool.of(difference == 0);
    }

    private static byte[] asciiOctets(PyStr text) {
        String value = text.getValue();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) {
                throw Slots.typeError("comparing strings with non-ASCII characters is not supported");
            }
        }
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
