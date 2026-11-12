package org.python.core;

/**
 * {@code _imp}: the low-level import machinery {@code importlib} is written on, as far as this
 * runtime has it.
 *
 * <p>The reference's module is the C half of {@code Lib/importlib}, and this runtime's importer is
 * written in Java instead, so most of the reference's surface names something that does not exist
 * here. What is answered is what a program or the test suite actually asks: the two test-only
 * overrides (from {@code test.support.import_helper}), the extension-suffix list this runtime can
 * honestly call empty because it loads no native extensions, and the two predicates over the module
 * tables the importer already owns.
 *
 * <p>The overrides are honoured as values rather than as behaviour: there are no frozen modules and
 * no sub-interpreters to switch, so {@code _override_frozen_modules_for_tests} records the mode it was
 * handed and returns None, while {@code _override_multi_interp_extensions_check} refuses in the main
 * interpreter with the reference's own words.
 */
final class ImpModule {

    /**
     * {@code pyc_magic_number_token}: bytes 1–2 of the {@code .pyc} header, as one integer.
     *
     * <p>It is the magic CPython 3.14 writes, {@code 0x0A0D0E2B}, read little-endian. Both users of it
     * stay here: {@code importlib._bootstrap_external.MAGIC_NUMBER} is its four little-endian bytes,
     * and {@code source_hash} takes it as its key.
     */
    static final int PYC_MAGIC_NUMBER_TOKEN = 0x0A0D0E2B;

    private ImpModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_imp");
        module.set("__doc__", PyStr.of("(Extremely) low-level import machinery bits as used by importlib."));
        PyRuntime.markBuiltIn(module, "_imp");
        module.set(
                "_override_frozen_modules_for_tests",
                PyNativeFunction.positional("_override_frozen_modules_for_tests", args -> {
                    Arguments.exactlyOne("_imp._override_frozen_modules_for_tests()", args, null);
                    Numbers.asIndex(args[0]);
                    // Nothing here is frozen, so the mode has nothing to switch; the reference's own
                    // function answers None as well.
                    return PyNone.INSTANCE;
                }).documented(
                        "_override_frozen_modules_for_tests($module, override, /)\n"
                                + "--\n"
                                + "\n"
                                + "(internal-only) Override PyConfig.use_frozen_modules.\n"
                                + "\n"
                                + "(-1: \"off\", 1: \"on\", 0: no override)\n"
                                + "See frozen_modules() in Lib/test/support/import_helper.py."));
        module.set(
                "_override_multi_interp_extensions_check",
                PyNativeFunction.positional("_override_multi_interp_extensions_check", args -> {
                    Arguments.exactlyOne("_imp._override_multi_interp_extensions_check()", args, null);
                    Numbers.asIndex(args[0]);
                    throw Slots.runtimeError(
                            "_imp._override_multi_interp_extensions_check() cannot be used in the main interpreter");
                }).documented(
                        "_override_multi_interp_extensions_check($module, override, /)\n"
                                + "--\n"
                                + "\n"
                                + "(internal-only) Override PyInterpreterConfig.check_multi_interp_extensions.\n"
                                + "\n"
                                + "(-1: \"never\", 1: \"always\", 0: no override)"));
        module.set(
                "extension_suffixes",
                PyNativeFunction.positional("extension_suffixes", args -> {
                    Arguments.none("_imp.extension_suffixes()", args, null);
                    // No native extension can be loaded, so there is no suffix to recognise.
                    return PyList.empty();
                }).documented(
                        "extension_suffixes($module, /)\n"
                                + "--\n"
                                + "\n"
                                + "Returns the list of file suffixes used to identify extension modules."));
        module.set(
                "is_builtin",
                PyNativeFunction.positional("is_builtin", args -> {
                    Arguments.exactlyOne("_imp.is_builtin()", args, null);
                    return PyBool.of(PyRuntime.isBuiltinModuleName(text("is_builtin", args[0])));
                }).documented(
                        "is_builtin($module, name, /)\n"
                                + "--\n"
                                + "\n"
                                + "Returns True if the module name corresponds to a built-in module."));
        module.set(
                "is_frozen",
                PyNativeFunction.positional("is_frozen", args -> {
                    Arguments.exactlyOne("_imp.is_frozen()", args, null);
                    text("is_frozen", args[0]);
                    return PyBool.FALSE;
                }).documented(
                        "is_frozen($module, name, /)\n"
                                + "--\n"
                                + "\n"
                                + "Returns True if the module name corresponds to a frozen module."));
        module.set("pyc_magic_number_token", PyInt.of(PYC_MAGIC_NUMBER_TOKEN));
        module.set(
                "source_hash",
                PyNativeFunction.positional("source_hash", args -> {
                    Arguments.exactly("_imp.source_hash", args, null, 2);
                    if (!(args[1] instanceof PyBytes || args[1] instanceof PyByteArray)) {
                        throw Slots.typeError(
                                "source_hash() argument 2 must be read-only bytes-like object, not %s",
                                Slots.argumentName(args[1]));
                    }
                    long key = Numbers.asIndex(args[0]).longValue();
                    return PyBytes.of(sourceHash(key, PyBytes.octets(args[1])));
                }).documented(
                        "source_hash($module, key, source, /)\n"
                                + "--\n"
                                + "\n"
                                + "Return the hash of a source bytes-like object."));
        return module;
    }

    /**
     * {@code _imp.source_hash}: eight bytes of siphash13 keyed by the magic number.
     *
     * <p>The reference writes {@code Py_HashBuffer} over the source with {@code key} as the first half
     * of the siphash key and zero as the second, and answers the result little-endian. Nothing else in
     * this runtime needs that digest — {@code hash()} is Java's and is not this — so the rounds are
     * written out here rather than taken from a shared hasher. {@code importlib.util.source_hash} is
     * this same function with the magic number as its key, so it calls this one rather than repeating
     * the rounds.
     */
    static byte[] sourceHash(long key, byte[] source) {
        long k0 = key;
        long k1 = 0;
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;
        int length = source.length;
        int whole = length & ~7;
        for (int at = 0; at < whole; at += 8) {
            long word = littleEndian64(source, at);
            v3 ^= word;
            v0 += v1;
            v1 = Long.rotateLeft(v1, 13);
            v1 ^= v0;
            v0 = Long.rotateLeft(v0, 32);
            v2 += v3;
            v3 = Long.rotateLeft(v3, 16);
            v3 ^= v2;
            v0 += v3;
            v3 = Long.rotateLeft(v3, 21);
            v3 ^= v0;
            v2 += v1;
            v1 = Long.rotateLeft(v1, 17);
            v1 ^= v2;
            v2 = Long.rotateLeft(v2, 32);
            v0 ^= word;
        }
        long tail = ((long) length & 0xff) << 56;
        for (int i = 0; i < (length & 7); i++) {
            tail |= ((long) (source[whole + i] & 0xff)) << (8 * i);
        }
        v3 ^= tail;
        v0 += v1;
        v1 = Long.rotateLeft(v1, 13);
        v1 ^= v0;
        v0 = Long.rotateLeft(v0, 32);
        v2 += v3;
        v3 = Long.rotateLeft(v3, 16);
        v3 ^= v2;
        v0 += v3;
        v3 = Long.rotateLeft(v3, 21);
        v3 ^= v0;
        v2 += v1;
        v1 = Long.rotateLeft(v1, 17);
        v1 ^= v2;
        v2 = Long.rotateLeft(v2, 32);
        v0 ^= tail;
        v2 ^= 0xff;
        for (int round = 0; round < 3; round++) {
            v0 += v1;
            v1 = Long.rotateLeft(v1, 13);
            v1 ^= v0;
            v0 = Long.rotateLeft(v0, 32);
            v2 += v3;
            v3 = Long.rotateLeft(v3, 16);
            v3 ^= v2;
            v0 += v3;
            v3 = Long.rotateLeft(v3, 21);
            v3 ^= v0;
            v2 += v1;
            v1 = Long.rotateLeft(v1, 17);
            v1 ^= v2;
            v2 = Long.rotateLeft(v2, 32);
        }
        long hash = v0 ^ v1 ^ v2 ^ v3;
        byte[] out = new byte[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (hash >>> (8 * i));
        }
        return out;
    }

    private static long littleEndian64(byte[] data, int at) {
        long word = 0;
        for (int i = 0; i < 8; i++) {
            word |= ((long) (data[at + i] & 0xff)) << (8 * i);
        }
        return word;
    }

    /** The name argument, refused the way {@code _imp}'s own C functions refuse a non-string. */
    private static String text(String function, PyObject value) {
        if (!(value instanceof PyStr name)) {
            throw Slots.typeError(
                    function + "() argument must be str, not %s", Slots.argumentName(value));
        }
        return name.getValue();
    }
}
