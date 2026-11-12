package org.python.core;

import java.math.BigInteger;

/**
 * {@code _opcode}: the C half of the {@code opcode} module.
 *
 * <p>The Python half ({@code opcode.py}, {@code _opcode_metadata.py}) is standard-library code this
 * runtime runs rather than rebuilds, so {@code opmap}, {@code opname}, {@code HAVE_ARGUMENT},
 * {@code EXTENDED_ARG} and {@code cmp_op} are whatever those files compute — the reference's own
 * numbers, not a copy. What only the C module can answer is here: the per-number predicates
 * {@code opcode.py} turns into {@code hasarg}/{@code hasconst}/…, and the four constant lists
 * {@code dis} reads through it. They come from {@link Opcodes}, generated off the reference
 * interpreter by {@code runtime/tools/gen_opcodes.py}.
 *
 * <p>Two members are named gaps rather than answers. {@code stack_effect} needs the interpreter's
 * own per-opcode push/pop counts, which this runtime does not keep; the function exists because
 * {@code opcode.py} imports it at module scope, and raises rather than guessing. {@code get_executor}
 * has the same shape: it can only ever be asked about a code object, and this runtime has none, so
 * every reachable call fails the reference's own type check.
 */
final class OpcodeModule {

    private OpcodeModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_opcode");
        PyRuntime.markBuiltIn(module, "_opcode");

        // Build flags, as the reference build reports them: specialization is on, and the
        // free-threaded specialization switch is on in the interpreter this table was read from.
        module.set("ENABLE_SPECIALIZATION", PyInt.of(1));
        module.set("ENABLE_SPECIALIZATION_FT", PyInt.of(1));

        module.set("stack_effect", new PyNativeFunction("stack_effect", OpcodeModule::stackEffect)
                .documented(
                        "stack_effect($module, opcode, oparg=None, /, *, jump=None)\n"
                                + "--\n"
                                + "\n"
                                + "Compute the stack effect of the opcode."));

        module.set("has_arg", predicate("has_arg", Opcodes::hasArg));
        module.set("has_const", predicate("has_const", Opcodes::hasConst));
        module.set("has_name", predicate("has_name", Opcodes::hasName));
        module.set("has_jump", predicate("has_jump", Opcodes::hasJump));
        module.set("has_free", predicate("has_free", Opcodes::hasFree));
        module.set("has_local", predicate("has_local", Opcodes::hasLocal));
        module.set("has_exc", predicate("has_exc", Opcodes::hasExc));

        module.set("is_valid", predicate("is_valid", Opcodes::valid));

        module.set("get_intrinsic1_descs", PyNativeFunction.positional("get_intrinsic1_descs", args -> {
            Arguments.none("get_intrinsic1_descs()", args, null);
            return strings(Opcodes.intrinsic1Descs());
        }));
        module.set("get_intrinsic2_descs", PyNativeFunction.positional("get_intrinsic2_descs", args -> {
            Arguments.none("get_intrinsic2_descs()", args, null);
            return strings(Opcodes.intrinsic2Descs());
        }));
        module.set("get_special_method_names", PyNativeFunction.positional("get_special_method_names", args -> {
            Arguments.none("get_special_method_names()", args, null);
            return strings(Opcodes.specialMethodNames());
        }));
        module.set("get_nb_ops", PyNativeFunction.positional("get_nb_ops", args -> {
            Arguments.none("get_nb_ops()", args, null);
            PyList ops = PyList.empty();
            for (String[] pair : Opcodes.nbOps()) {
                ops.add(PyTuple.of(PyStr.of(pair[0]), PyStr.of(pair[1])));
            }
            return ops;
        }));
        module.set("get_specialization_stats", PyNativeFunction.positional("get_specialization_stats", args -> {
            Arguments.none("get_specialization_stats()", args, null);
            // Only a `Py_STATS` build keeps counters; the reference build answers None.
            return PyNone.INSTANCE;
        }));
        module.set("get_executor", PyNativeFunction.positional("get_executor", args -> {
            Arguments.exactly("get_executor", args, null, 2);
            BigInteger offset = Numbers.asIndex(args[1]);
            if (offset.bitLength() > 31) {
                throw Slots.overflowError("Python int too large to convert to C int");
            }
            throw Slots.typeError(
                    "expected a code object, not '%s'", args[0].getType().filedName());
        }));
        return module;
    }

    /** One {@code has_*}/{@code is_valid} question, answered from the generated table. */
    private static PyNativeFunction predicate(String name, java.util.function.IntPredicate answer) {
        return PyNativeFunction.positional(name, args -> {
            Arguments.exactly(name, args, null, 1);
            return PyBool.of(answer.test(index(name, args[0])));
        });
    }

    private static PyList strings(String[] values) {
        PyList list = PyList.empty();
        for (String value : values) {
            list.add(PyStr.of(value));
        }
        return list;
    }

    /**
     * The reference's {@code stack_effect}. Its answer is the interpreter's push/pop counts, which
     * this runtime does not carry, so it is a named gap: present because {@code opcode.py} imports
     * it, and refusing rather than returning a number it cannot compute.
     */
    private static PyObject stackEffect(PyObject[] args, String[] kwNames) {
        throw PyRaise.raise(
                ExceptionTypes.NOT_IMPLEMENTED_ERROR,
                "stack_effect is not available in this runtime: it needs the interpreter's "
                        + "per-opcode push and pop counts");
    }

    private static int index(String name, PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() > 31) {
            throw Slots.overflowError("Python int too large to convert to C int");
        }
        return number.intValue();
    }
}
