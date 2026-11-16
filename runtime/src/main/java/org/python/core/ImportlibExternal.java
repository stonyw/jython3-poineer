package org.python.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * {@code importlib._bootstrap_external}: the part of the import machinery that names files.
 *
 * <p>This is a named approximation, not a port. The reference module is frozen bytecode built from
 * {@code Lib/importlib/_bootstrap_external.py}, and running that source would need {@code _imp},
 * {@code _io}, {@code _warnings} and the frozen-module bootstrap before this runtime's own importer
 * could exist — a replacement rather than a slice. What is here is the surface {@code py_compile}
 * and {@code importlib.util} actually read: the magic number, the two {@code .pyc} header writers,
 * the two path functions that name a cache file, and the three small helpers around them.
 *
 * <p>{@code _code_to_timestamp_pyc} and {@code _code_to_hash_pyc} need the code object's marshal
 * payload. {@link PyCode#marshalStream()} is that payload for any code this runtime compiled — the
 * stream the compiler produced is exactly {@code marshal.dumps(code)} — so a {@code .pyc} written
 * here holds a code object the reference interpreter can also load. A code object built by
 * {@code code.replace} or {@code code.__new__} has no such stream, and the writers refuse it by
 * name rather than writing a header over nothing.
 *
 * <p>Known gaps, all named: no {@code _classify_pyc} / {@code _validate_*_pyc} / {@code _fix_up_module}
 * / {@code _bless_my_loader}, no {@code FileLoader} / {@code SourceLoader} / {@code _LoaderBasics}
 * class bodies (only the four types this runtime already has are re-exported), and no
 * {@code _get_supported_file_loaders}. {@code debug_override} is honoured but does not warn, and
 * {@code _write_atomic} takes its permission from the process umask instead of the {@code mode}
 * argument, because this runtime has no {@code fchmod}.
 */
final class ImportlibExternal {

    /**
     * The four bytes every 3.14 {@code .pyc} begins with: {@code _imp.pyc_magic_number_token}
     * packed little-endian, with the {@code \r\n} that keeps a cache file from looking like text.
     */
    static final byte[] MAGIC_NUMBER = {0x2b, 0x0e, 0x0d, 0x0a};

    /** The reference's {@code path_sep} and {@code path_separators} on POSIX. */
    private static final String PATH_SEP = "/";

    private ImportlibExternal() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("importlib._bootstrap_external");
        module.set("__doc__", PyStr.of(
                "Access to the import machinery's file naming, as this runtime implements it."));
        module.set("__package__", PyStr.of("importlib"));
        // The reference publishes this frozen module under a name of its own, `_frozen_importlib_external`,
        // while `importlib._bootstrap_external` is the same module object and the name its own source
        // uses. The loader and spec say frozen rather than built-in for the same reason.
        module.set("__loader__", PyType.FROZEN_IMPORTER);
        module.set("__spec__", PyModuleSpec.frozen("_frozen_importlib_external"));
        module.set("MAGIC_NUMBER", PyBytes.of(MAGIC_NUMBER));
        module.set("path_sep", PyStr.of(PATH_SEP));
        module.set("path_separators", PyStr.of(PATH_SEP));

        PyList sources = PyList.of(PyStr.of(".py"));
        PyList bytecode = PyList.of(PyStr.of(".pyc"));
        PyList extensions = PyList.empty();
        module.set("SOURCE_SUFFIXES", sources);
        module.set("BYTECODE_SUFFIXES", bytecode);
        module.set("DEBUG_BYTECODE_SUFFIXES", bytecode);
        module.set("OPTIMIZED_BYTECODE_SUFFIXES", bytecode);
        module.set("EXTENSION_SUFFIXES", extensions);

        module.set("SourceFileLoader", PyType.SOURCE_FILE_LOADER);
        module.set("SourcelessFileLoader", PyType.SOURCELESS_FILE_LOADER);
        module.set("ExtensionFileLoader", PyType.EXTENSION_FILE_LOADER);
        module.set("NamespaceLoader", PyType.NAMESPACE_LOADER);

        module.set("_pack_uint32", PyNativeFunction.positional("_pack_uint32", args -> {
            Arguments.exactly("_pack_uint32", args, null, 1);
            return PyBytes.of(packUint32(asLong(args[0])));
        }).documented("Pack a non-negative int into four bytes, little-endian."));

        module.set("_code_to_timestamp_pyc",
                new PyNativeFunction("_code_to_timestamp_pyc", ImportlibExternal::codeToTimestampPyc)
                        .documented("Create a .pyc from a code object with timestamp invalidation."));
        module.set("_code_to_hash_pyc",
                new PyNativeFunction("_code_to_hash_pyc", ImportlibExternal::codeToHashPyc)
                        .documented("Create a .pyc from a code object with hash-based invalidation."));
        module.set("_calc_mode",
                PyNativeFunction.positional("_calc_mode", ImportlibExternal::calcMode)
                        .documented("Return the mode bits a cache file should be given."));
        module.set("_write_atomic",
                new PyNativeFunction("_write_atomic", ImportlibExternal::writeAtomic)
                        .documented("Write bytes to a file by way of a temporary neighbour."));
        module.set("cache_from_source",
                new PyNativeFunction("cache_from_source", ImportlibExternal::cacheFromSource)
                        .documented("Given a source path, return the path of its cache file."));
        module.set("source_from_cache",
                PyNativeFunction.positional("source_from_cache",
                                ImportlibExternal::sourceFromCache)
                        .documented("Given a cache path, return the path of its source."));
        module.set("decode_source",
                PyNativeFunction.positional("decode_source", ImportlibExternal::decodeSource)
                        .documented("Decode source bytes to text with universal newlines."));
        module.set("_get_sourcefile",
                PyNativeFunction.positional("_get_sourcefile",
                                ImportlibExternal::getSourceFile)
                        .documented("Convert a bytecode file path to a source path, where possible."));
        module.set("_get_cached",
                PyNativeFunction.positional("_get_cached", ImportlibExternal::getCached)
                        .documented("Return the cache path for a source name, if it has one."));
        return module;
    }

    // --- .pyc writers --------------------------------------------------------------------

    /**
     * {@code _code_to_timestamp_pyc(code, mtime=0, source_size=0)}.
     *
     * <p>Header is magic, a zero flags word, the source's mtime and its size, each as four
     * little-endian bytes; the payload is the code object's marshal stream.
     */
    private static PyObject codeToTimestampPyc(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "_code_to_timestamp_pyc", args, kwNames,
                new String[] {"code", "mtime", "source_size"}, 1, 3);
        byte[] payload = payloadOf(values[0]);
        long mtime = values[1] == null ? 0 : asLong(values[1]);
        long size = values[2] == null ? 0 : asLong(values[2]);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 16);
        out.writeBytes(MAGIC_NUMBER);
        out.writeBytes(packUint32(0));
        out.writeBytes(packUint32(mtime));
        out.writeBytes(packUint32(size));
        out.writeBytes(payload);
        return PyByteArray.of(out.toByteArray());
    }

    /**
     * {@code _code_to_hash_pyc(code, source_hash, checked=True)}.
     *
     * <p>Flags is {@code 0b1 | checked << 1}: bit 0 says "hash", bit 1 says "checked". Then the
     * source hash's eight bytes, then the payload.
     */
    private static PyObject codeToHashPyc(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "_code_to_hash_pyc", args, kwNames,
                new String[] {"code", "source_hash", "checked"}, 2, 3);
        byte[] payload = payloadOf(values[0]);
        byte[] sourceHash = octets("_code_to_hash_pyc", values[1]);
        if (sourceHash.length != 8) {
            throw Slots.valueError("source_hash must be 8 bytes, not %d", sourceHash.length);
        }
        int flags = 1;
        if (values[2] == null || Slots.truth(values[2])) {
            flags |= 2;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 16);
        out.writeBytes(MAGIC_NUMBER);
        out.writeBytes(packUint32(flags));
        out.writeBytes(sourceHash);
        out.writeBytes(payload);
        return PyByteArray.of(out.toByteArray());
    }

    /** The marshal stream of a code object, which is what a {@code .pyc} carries after its header. */
    private static byte[] payloadOf(PyObject code) {
        if (!(code instanceof PyCode pyCode)) {
            throw Slots.typeError(
                    "_code_to_*_pyc() argument 1 must be a code object, not %s",
                    Slots.argumentName(code));
        }
        byte[] payload = pyCode.marshalStream();
        if (payload == null) {
            throw Slots.typeError(
                    "this code object has no marshal stream to write: it was built by "
                            + "code.replace() or code.__new__() rather than by the compiler");
        }
        return payload;
    }

    private static byte[] packUint32(long value) {
        byte[] packed = new byte[4];
        for (int i = 0; i < 4; i++) {
            packed[i] = (byte) (value >>> (8 * i));
        }
        return packed;
    }

    // --- the two path functions ----------------------------------------------------------

    /**
     * {@code cache_from_source(path, debug_override=None, *, optimization=None)}.
     *
     * <p>{@code sys.pycache_prefix} is {@code None} in this runtime, so the cache always lives in a
     * {@code __pycache__} directory beside the source.
     */
    private static PyObject cacheFromSource(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "cache_from_source", args, kwNames,
                new String[] {"path", "debug_override", "optimization"}, 1, 2);
        PyObject optimization = values[2];
        if (values[1] != null) {
            if (optimization != null) {
                throw Slots.typeError("debug_override or optimization must be set to None");
            }
            // The reference also warns here; this runtime has no DeprecationWarning to raise from a
            // built-in, and the value it computes is the whole of what the caller sees.
            optimization = Slots.truth(values[1]) ? PyStr.of("") : PyInt.of(1);
        }
        String text = pathString(values[0]);
        int slash = text.lastIndexOf(PATH_SEP);
        String head = slash < 0 ? "" : text.substring(0, slash);
        String tail = slash < 0 ? text : text.substring(slash + 1);
        int dot = tail.lastIndexOf('.');
        String base = dot < 0 ? "" : tail.substring(0, dot);
        String sep = dot < 0 ? "" : ".";
        String rest = dot < 0 ? tail : tail.substring(dot + 1);
        String tag = PyRuntime.cacheTag();
        String almost = (base.isEmpty() ? rest : base) + sep + tag;
        // `sys.flags.optimize` is 0 in this runtime, which is the empty optimization level.
        String level = "";
        if (optimization != null && optimization != PyNone.INSTANCE) {
            level = Slots.str(optimization);
            if (!level.isEmpty()) {
                if (!isAlnum(level)) {
                    throw Slots.valueError("%s is not alphanumeric", PyStr.of(level).repr());
                }
                almost = almost + ".opt-" + level;
            }
        }
        String filename = almost + ".pyc";
        return PyStr.of(head.isEmpty() ? "__pycache__" + PATH_SEP + filename
                : head + PATH_SEP + "__pycache__" + PATH_SEP + filename);
    }

    /**
     * {@code source_from_cache(path)}: the inverse of {@link #cacheFromSource}, and, like the
     * reference, without a check that the tag is this interpreter's.
     */
    private static PyObject sourceFromCache(PyObject[] args) {
        Arguments.exactly("source_from_cache", args, null, 1);
        return PyStr.of(sourceFromCachePath(pathString(args[0])));
    }

    /** The body of {@link #sourceFromCache}, shared with {@code _get_sourcefile}. */
    private static String sourceFromCachePath(String path) {
        int slash = path.lastIndexOf(PATH_SEP);
        String head = slash < 0 ? "" : path.substring(0, slash);
        String filename = slash < 0 ? path : path.substring(slash + 1);
        int parentSlash = head.lastIndexOf(PATH_SEP);
        String parentHead = parentSlash < 0 ? "" : head.substring(0, parentSlash);
        String pycache = parentSlash < 0 ? head : head.substring(parentSlash + 1);
        if (!pycache.equals("__pycache__")) {
            throw Slots.valueError("__pycache__ not bottom-level directory in %s",
                    PyStr.of(path).repr());
        }
        int dots = 0;
        for (int i = 0; i < filename.length(); i++) {
            if (filename.charAt(i) == '.') {
                dots++;
            }
        }
        if (dots != 2 && dots != 3) {
            throw Slots.valueError("expected only 2 or 3 dots in %s", PyStr.of(filename).repr());
        }
        if (dots == 3) {
            int last = filename.lastIndexOf('.');
            String middle = filename.substring(0, last);
            String optimization = middle.substring(middle.lastIndexOf('.') + 1);
            if (!optimization.startsWith("opt-")) {
                throw Slots.valueError("optimization portion of filename does not start with 'opt-'");
            }
            String level = optimization.substring(4);
            if (!isAlnum(level)) {
                throw Slots.valueError("optimization level %s is not an alphanumeric value",
                        PyStr.of(optimization).repr());
            }
        }
        String base = filename.substring(0, filename.indexOf('.'));
        String sourceName = base + ".py";
        return parentHead.isEmpty() ? sourceName : parentHead + PATH_SEP + sourceName;
    }

    // --- the small helpers py_compile reads ----------------------------------------------

    /**
     * {@code _calc_mode(path)}: the mode a cache file is given, which keeps the source's own bits
     * and always adds write permission for the owner.
     */
    private static PyObject calcMode(PyObject[] args) {
        Arguments.exactly("_calc_mode", args, null, 1);
        String path = pathString(args[0]);
        long mode;
        try {
            Object value = Files.readAttributes(Path.of(path), "unix:mode").get("mode");
            mode = ((Number) value).longValue();
        } catch (IOException | RuntimeException e) {
            // The reference answers 0o666 when the stat fails, and this runtime has no other mode to
            // report on a file system that does not expose unix attributes.
            mode = 0x1B6; // 0o666
        }
        return PyInt.of(mode | 0x80); // 0o200
    }

    /** {@code _write_atomic(path, data, mode=0o666)}: write a neighbour, then replace. */
    private static PyObject writeAtomic(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords(
                "_write_atomic", args, kwNames, new String[] {"path", "data", "mode"}, 2, 3);
        String target = pathString(values[0]);
        byte[] content = octets("_write_atomic", values[1]);
        // `mode` would go to `os.open`; the JVM has no fchmod, so the file keeps what the umask
        // gives it. Named in the class comment.
        String temporary = target + "." + Integer.toHexString(System.identityHashCode(values[0]));
        Path temporaryPath = Path.of(temporary);
        try {
            try (OutputStream out = Files.newOutputStream(
                    temporaryPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                out.write(content);
            }
            try {
                Files.move(temporaryPath, Path.of(target),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryPath, Path.of(target), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException ignored) {
                // The reference also ignores the unlink: the write's failure is the error to report.
            }
            throw Slots.osError("%s", e.getMessage() == null ? target : e.getMessage());
        }
        return PyNone.INSTANCE;
    }

    /**
     * {@code decode_source(source_bytes)}: bytes to text, with the newlines universalised.
     *
     * <p>Only UTF-8 is detected here, with a leading BOM dropped; an encoding cookie naming another
     * codec is not honoured yet.
     */
    private static PyObject decodeSource(PyObject[] args) {
        Arguments.exactlyOne("decode_source", args, null);
        return PyStr.of(decodeSourceText(args[0]));
    }

    /** The bytes-to-text step, shared with {@code SourceLoader.source_to_code}. */
    static String decodeSourceText(PyObject value) {
        byte[] data = octets("decode_source", value);
        int start = 0;
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB
                && data[2] == (byte) 0xBF) {
            start = 3;
        }
        String text = new String(data, start, data.length - start, StandardCharsets.UTF_8);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** {@code _get_sourcefile(bytecode_path)}: the shape {@code test.test_import} imports. */
    private static PyObject getSourceFile(PyObject[] args) {
        Arguments.exactly("_get_sourcefile", args, null, 1);
        String bytecodePath = pathString(args[0]);
        if (bytecodePath.isEmpty()) {
            return PyNone.INSTANCE;
        }
        int dot = bytecodePath.lastIndexOf('.');
        String rest = dot < 0 ? "" : bytecodePath.substring(0, dot);
        String extension = dot < 0 ? bytecodePath : bytecodePath.substring(dot + 1);
        String lower = extension.toLowerCase();
        // `extension.lower()[-3:-1] != 'py'`: Python's slice clamps, so a two-letter extension
        // answers one character and fails this test.
        boolean pyExtension = lower.length() >= 3
                && lower.substring(lower.length() - 3, lower.length() - 1).equals("py");
        if (rest.isEmpty() || !pyExtension) {
            return PyStr.of(bytecodePath);
        }
        String sourcePath;
        try {
            sourcePath = sourceFromCachePath(bytecodePath);
        } catch (PyRaise e) {
            sourcePath = bytecodePath.substring(0, bytecodePath.length() - 1);
        }
        return Files.isRegularFile(Path.of(sourcePath))
                ? PyStr.of(sourcePath) : PyStr.of(bytecodePath);
    }

    /** {@code _get_cached(filename)}: a source name's cache, a cache's own name, or {@code None}. */
    private static PyObject getCached(PyObject[] args) {
        Arguments.exactly("_get_cached", args, null, 1);
        String filename = pathString(args[0]);
        if (filename.endsWith(".py")) {
            return cacheFromSource(new PyObject[] {PyStr.of(filename)}, new String[0]);
        }
        return filename.endsWith(".pyc") ? PyStr.of(filename) : PyNone.INSTANCE;
    }

    // --- shared conversions --------------------------------------------------------------

    /** A path argument as text: a {@code str} as is, bytes read as UTF-8. */
    static String pathString(PyObject value) {
        if (value instanceof PyStr str) {
            return str.getValue();
        }
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return new String(PyBytes.octets(value), StandardCharsets.UTF_8);
        }
        throw Slots.typeError(
                "expected str, bytes or os.PathLike object, not %s",
                value.getType().filedName());
    }

    /** A bytes-like argument as a byte array. */
    private static byte[] octets(String function, PyObject value) {
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return PyBytes.octets(value);
        }
        throw Slots.typeError("%s() argument must be a bytes-like object, not %s",
                function, Slots.argumentName(value));
    }

    /** A number read as the integer part, which is what {@code int(x)} does in the writers. */
    private static long asLong(PyObject value) {
        if (value instanceof PyFloat number) {
            return (long) number.getValue();
        }
        java.math.BigInteger integer = Numbers.longOf(value);
        if (integer == null) {
            throw Slots.typeError("an integer is required (got type %s)",
                    value.getType().filedName());
        }
        return integer.longValue();
    }

    /** Python's {@code str.isalnum} for the optimization levels the cache name may carry. */
    private static boolean isAlnum(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isLetterOrDigit(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }
}
