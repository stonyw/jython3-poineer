package org.python.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CJK codec family: {@code _multibytecodec} and the six per-language modules that register their
 * codecs with it.
 *
 * <p>In the reference these are C codecs with hand-written tables ({@code Modules/cjkcodecs}). This
 * runtime has no such tables, but the JVM does have the character sets themselves, so every codec whose
 * character set the JDK ships is built on {@link Charset}: the mapping between bytes and characters is
 * the JDK's, the walk over sequences, the error ranges and the handler protocol are written here to match
 * {@code multibytecodec.c}. Where a codec has no JDK character set ({@code shift_jis_2004},
 * {@code euc_jis_2004}, {@code euc_jisx0213}, {@code shift_jisx0213}, {@code iso2022_jp_1},
 * {@code iso2022_jp_2004}, {@code iso2022_jp_3} and {@code iso2022_jp_ext}) {@code getcodec} answers the
 * reference's own refusal — {@code LookupError: no such codec is supported.} — and the gap is named in
 * PLAN.md rather than papered over.
 *
 * <p>Three families are stateful and cannot be a flat table lookup. {@code hz}, {@code iso2022_jp} and
 * {@code iso2022_kr} are written as small state machines over the JDK's GB2312, EUC-JP and EUC-KR tables,
 * because their incremental encoders and decoders must carry shift state across calls — the reference's
 * {@code IncrementalEncoder('iso2022_jp').encode('あ')} is {@code b'\x1b$B$"'} with no return to ASCII,
 * and it keeps that state until the next call or the final flush. {@code iso2022_jp_2} is delegated to
 * the JDK's own {@code ISO-2022-JP-2} encoder and decoder; its incremental state is not carried between
 * calls, which is the one named deviation of that codec.
 *
 * <p>The four types in {@code _multibytecodec} are ordinary classes rather than C types: the codec is a
 * class with {@code encode}/{@code decode} methods, and the incremental and stream classes are bases the
 * {@code encodings} modules subclass. {@code MultibyteStreamReader} and {@code MultibyteStreamWriter}
 * deliberately define no read/write methods at all, so the real work is inherited from
 * {@code codecs.StreamReader}/{@code codecs.StreamWriter} further down the subclass's MRO — those are
 * written against the same {@code (result, consumed)} protocol the codec's own methods answer with.
 */
final class MultibyteCodecModule {

    private static final String MULTIBYTECODEC = "_multibytecodec";

    /** The modules this class builds, in the order the runtime files them. */
    static final List<String> MODULES = List.of(
            MULTIBYTECODEC, "_codecs_cn", "_codecs_hk", "_codecs_jp", "_codecs_kr", "_codecs_tw",
            "_codecs_iso2022");

    /** How a codec reaches its characters: a flat table, or one of the four state machines. */
    private enum Kind { STATELESS, HZ, ISO2022_JP, ISO2022_KR, ISO2022_JP2 }

    /** One codec: the name the reference reports, the JDK character set behind it, and its shape. */
    private record Spec(String name, String charset, Kind kind) {}

    private static final Map<String, Spec> SPECS = new HashMap<>();

    /** What each {@code _codecs_XX} module's {@code getcodec} answers, in the reference's order. */
    private static final Map<String, List<String>> EXPORTS = new HashMap<>();

    static {
        add(new Spec("gb2312", "GB2312", Kind.STATELESS));
        add(new Spec("gbk", "GBK", Kind.STATELESS));
        add(new Spec("gb18030", "GB18030", Kind.STATELESS));
        add(new Spec("hz", null, Kind.HZ));
        add(new Spec("big5hkscs", "Big5-HKSCS", Kind.STATELESS));
        add(new Spec("shift_jis", "Shift_JIS", Kind.STATELESS));
        add(new Spec("cp932", "windows-31j", Kind.STATELESS));
        add(new Spec("euc_jp", "EUC-JP", Kind.STATELESS));
        add(new Spec("euc_kr", "EUC-KR", Kind.STATELESS));
        add(new Spec("cp949", "x-windows-949", Kind.STATELESS));
        add(new Spec("johab", "x-Johab", Kind.STATELESS));
        add(new Spec("big5", "Big5", Kind.STATELESS));
        add(new Spec("cp950", "Big5", Kind.STATELESS));
        add(new Spec("iso2022_kr", "EUC-KR", Kind.ISO2022_KR));
        add(new Spec("iso2022_jp", "EUC-JP", Kind.ISO2022_JP));
        add(new Spec("iso2022_jp_2", "ISO-2022-JP-2", Kind.ISO2022_JP2));

        EXPORTS.put("_codecs_cn", List.of("gb2312", "gbk", "gb18030", "hz"));
        EXPORTS.put("_codecs_hk", List.of("big5hkscs"));
        EXPORTS.put("_codecs_jp", List.of(
                "shift_jis", "cp932", "euc_jp", "shift_jis_2004", "euc_jis_2004", "euc_jisx0213",
                "shift_jisx0213"));
        EXPORTS.put("_codecs_kr", List.of("euc_kr", "cp949", "johab"));
        EXPORTS.put("_codecs_tw", List.of("big5", "cp950"));
        EXPORTS.put("_codecs_iso2022", List.of(
                "iso2022_kr", "iso2022_jp", "iso2022_jp_1", "iso2022_jp_2", "iso2022_jp_2004",
                "iso2022_jp_3", "iso2022_jp_ext"));
    }

    private static void add(Spec spec) {
        SPECS.put(spec.name(), spec);
    }

    private static PyClass codecType;
    private static PyClass incrementalEncoderType;
    private static PyClass incrementalDecoderType;
    private static PyClass streamReaderType;
    private static PyClass streamWriterType;

    private static final byte[] EMPTY = new byte[0];

    /**
     * What {@link Arguments} must see for a bound method here: it counts arguments as a vectorcall
     * receives them, so the receiver this module's methods are handed has to come off first.
     */
    private static PyObject[] own(PyObject[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private MultibyteCodecModule() {}

    // --- module building ---------------------------------------------------------------------

    /** One of the seven modules, by name. */
    static PyModule newModule(String name) {
        return MULTIBYTECODEC.equals(name) ? newMultibyteCodec() : newLanguageModule(name);
    }

    private static PyModule newMultibyteCodec() {
        PyModule module = new PyModule(MULTIBYTECODEC);
        module.set("MultibyteIncrementalEncoder", incrementalEncoderType());
        module.set("MultibyteIncrementalDecoder", incrementalDecoderType());
        module.set("MultibyteStreamReader", streamReaderType());
        module.set("MultibyteStreamWriter", streamWriterType());
        module.set("__create_codec", PyNativeFunction.positional("__create_codec", args -> {
            Arguments.exactlyOne("__create_codec", args, null);
            // The reference takes a capsule a C codec module made. No capsule exists in this runtime,
            // so every argument is the one this function refuses.
            throw Slots.valueError("argument type invalid");
        }));
        PyRuntime.markBuiltIn(module, MULTIBYTECODEC);
        return module;
    }

    private static PyModule newLanguageModule(String name) {
        PyModule module = new PyModule(name);
        List<String> exported = EXPORTS.get(name);
        module.set("getcodec", PyNativeFunction.positional("getcodec", args -> {
            Arguments.exactlyOne("getcodec", args, null);
            if (!(args[0] instanceof PyStr text)) {
                throw Slots.typeError("getcodec() argument must be str, not %s",
                        args[0].getType().filedName());
            }
            String wanted = text.getValue();
            Spec spec = exported.contains(wanted) ? SPECS.get(wanted) : null;
            if (spec == null) {
                throw Slots.lookupError("no such codec is supported.");
            }
            return newCodec(spec);
        }));
        PyRuntime.markBuiltIn(module, name);
        return module;
    }

    // --- the classes -------------------------------------------------------------------------

    private static synchronized PyClass codecType() {
        if (codecType == null) {
            PyDict namespace = PyDict.empty();
            namespace.put(PyStr.interned("__module__"), PyStr.of(MULTIBYTECODEC));
            namespace.put(PyStr.interned("__qualname__"), PyStr.of("MultibyteCodec"));
            namespace.put(PyStr.interned("__new__"),
                    PyNativeFunction.method("__new__", (args, kwNames) -> {
                        throw Slots.typeError(
                                "cannot create '%s.MultibyteCodec' instances", MULTIBYTECODEC);
                    }));
            namespace.put(PyStr.interned("encode"),
                    PyNativeFunction.method("encode", MultibyteCodecModule::codecEncode));
            namespace.put(PyStr.interned("decode"),
                    PyNativeFunction.method("decode", MultibyteCodecModule::codecDecode));
            codecType = Classes.declare("MultibyteCodec", PyTuple.of(PyType.OBJECT), namespace,
                    PyType.TYPE, new PyObject[0], null);
        }
        return codecType;
    }

    private static PyClass incrementalEncoderType() {
        if (incrementalEncoderType == null) {
            PyDict namespace = baseNamespace("MultibyteIncrementalEncoder");
            namespace.put(PyStr.interned("__init__"),
                    PyNativeFunction.method("__init__", MultibyteCodecModule::encoderInit));
            namespace.put(PyStr.interned("encode"),
                    PyNativeFunction.method("encode", MultibyteCodecModule::encoderEncode));
            namespace.put(PyStr.interned("getstate"),
                    PyNativeFunction.method("getstate", (args, kwNames) -> {
                        Arguments.none(
                                "MultibyteIncrementalEncoder.getstate()", own(args), kwNames);
                        return encoderState(args[0]);
                    }));
            namespace.put(PyStr.interned("setstate"),
                    PyNativeFunction.method("setstate", (args, kwNames) -> {
                        Arguments.exactlyOne(
                                "MultibyteIncrementalEncoder.setstate()", own(args), kwNames);
                        return encoderSetState(args[0], args[1]);
                    }));
            namespace.put(PyStr.interned("reset"),
                    PyNativeFunction.method("reset", (args, kwNames) -> {
                        Arguments.none("MultibyteIncrementalEncoder.reset()", own(args), kwNames);
                        Spec spec = specOfIncremental(args[0]);
                        setStateValue(args[0], initialState(spec, true));
                        Slots.setAttr(args[0], "_pending", PyBytes.of(EMPTY));
                        return PyNone.INSTANCE;
                    }));
            incrementalEncoderType = Classes.declare("MultibyteIncrementalEncoder",
                    PyTuple.of(PyType.OBJECT), namespace, PyType.TYPE, new PyObject[0], null);
        }
        return incrementalEncoderType;
    }

    private static PyClass incrementalDecoderType() {
        if (incrementalDecoderType == null) {
            PyDict namespace = baseNamespace("MultibyteIncrementalDecoder");
            namespace.put(PyStr.interned("__init__"),
                    PyNativeFunction.method("__init__", MultibyteCodecModule::decoderInit));
            namespace.put(PyStr.interned("decode"),
                    PyNativeFunction.method("decode", MultibyteCodecModule::decoderDecode));
            namespace.put(PyStr.interned("getstate"),
                    PyNativeFunction.method("getstate", (args, kwNames) -> {
                        Arguments.none(
                                "MultibyteIncrementalDecoder.getstate()", own(args), kwNames);
                        return decoderState(args[0]);
                    }));
            namespace.put(PyStr.interned("setstate"),
                    PyNativeFunction.method("setstate", (args, kwNames) -> {
                        Arguments.exactlyOne(
                                "MultibyteIncrementalDecoder.setstate()", own(args), kwNames);
                        return decoderSetState(args[0], args[1]);
                    }));
            namespace.put(PyStr.interned("reset"),
                    PyNativeFunction.method("reset", (args, kwNames) -> {
                        Arguments.none("MultibyteIncrementalDecoder.reset()", own(args), kwNames);
                        Spec spec = specOfIncremental(args[0]);
                        Slots.setAttr(args[0], "_pending", PyBytes.of(EMPTY));
                        setStateValue(args[0], initialState(spec, false));
                        return PyNone.INSTANCE;
                    }));
            incrementalDecoderType = Classes.declare("MultibyteIncrementalDecoder",
                    PyTuple.of(PyType.OBJECT), namespace, PyType.TYPE, new PyObject[0], null);
        }
        return incrementalDecoderType;
    }

    /**
     * {@code MultibyteStreamReader}: a base whose only job is to refuse a subclass that forgot
     * {@code codec}, exactly as the C {@code streamreader_init} does. The reading itself comes from
     * {@code codecs.StreamReader}, later in the subclass's MRO.
     */
    private static PyClass streamReaderType() {
        if (streamReaderType == null) {
            PyDict namespace = baseNamespace("MultibyteStreamReader");
            namespace.put(PyStr.interned("__init__"),
                    PyNativeFunction.method("__init__", (args, kwNames) -> {
                        PyObject[] call = streamArguments("StreamReader", args, kwNames);
                        requireCodec(args[0]);
                        Slots.setAttr(args[0], "stream", call[0]);
                        Slots.setAttr(args[0], "errors", call[1]);
                        Slots.setAttr(args[0], "bytebuffer", PyBytes.of(EMPTY));
                        Slots.setAttr(args[0], "_empty_charbuffer", PyStr.of(""));
                        Slots.setAttr(args[0], "charbuffer", PyStr.of(""));
                        Slots.setAttr(args[0], "linebuffer", PyNone.INSTANCE);
                        return PyNone.INSTANCE;
                    }));
            streamReaderType = Classes.declare("MultibyteStreamReader",
                    PyTuple.of(PyType.OBJECT), namespace, PyType.TYPE, new PyObject[0], null);
        }
        return streamReaderType;
    }

    /** {@code MultibyteStreamWriter}, the write half of the same arrangement. */
    private static PyClass streamWriterType() {
        if (streamWriterType == null) {
            PyDict namespace = baseNamespace("MultibyteStreamWriter");
            namespace.put(PyStr.interned("__init__"),
                    PyNativeFunction.method("__init__", (args, kwNames) -> {
                        PyObject[] call = streamArguments("StreamWriter", args, kwNames);
                        requireCodec(args[0]);
                        Slots.setAttr(args[0], "stream", call[0]);
                        Slots.setAttr(args[0], "errors", call[1]);
                        return PyNone.INSTANCE;
                    }));
            streamWriterType = Classes.declare("MultibyteStreamWriter",
                    PyTuple.of(PyType.OBJECT), namespace, PyType.TYPE, new PyObject[0], null);
        }
        return streamWriterType;
    }

    private static PyDict baseNamespace(String name) {
        PyDict namespace = PyDict.empty();
        namespace.put(PyStr.interned("__module__"), PyStr.of(MULTIBYTECODEC));
        namespace.put(PyStr.interned("__qualname__"), PyStr.of(name));
        return namespace;
    }

    /** A codec instance: state lives in the instance dictionary under {@code _name}. */
    private static PyObject newCodec(Spec spec) {
        PyObject made = Slots.call(
                Slots.findAttr(PyType.OBJECT, "__new__"), new PyObject[] {codecType()}, null);
        Slots.setAttr(made, "_name", PyStr.of(spec.name()));
        return made;
    }

    private static Spec specOf(PyObject codec) {
        PyObject name = Slots.findAttr(codec, "_name");
        if (!(name instanceof PyStr text)) {
            throw Slots.typeError("codec is unexpected type");
        }
        Spec spec = SPECS.get(text.getValue());
        if (spec == null) {
            throw Slots.typeError("codec is unexpected type");
        }
        return spec;
    }

    /** The class-level {@code codec} attribute, which is how the {@code encodings} modules wire a subtype. */
    private static Spec specOfIncremental(PyObject self) {
        requireCodec(self);
        return specOf(Slots.findAttr(self.getType(), "codec"));
    }

    private static void requireCodec(PyObject self) {
        if (Slots.findAttr(self.getType(), "codec") == null) {
            throw Slots.attributeError("type object '%s' has no attribute 'codec'",
                    self.getType().qualifiedName());
        }
    }

    // --- the codec object's own methods ------------------------------------------------------

    private static PyObject codecEncode(PyObject[] args, String[] kwNames) {
        PyObject[] call = callArguments("encode", "errors", args, kwNames);
        // The reference converts anything that is no str with PyObject_Str, so b'a' encodes as "b'a'".
        String text = textOf(call[0]);
        String errors = codecErrors("encode", call[1]);
        Spec spec = specOf(args[0]);
        byte[] encoded = encode(spec, text, errors, new int[] {0}, true);
        return PyTuple.of(PyBytes.of(encoded), PyInt.of(text.length()));
    }

    private static PyObject codecDecode(PyObject[] args, String[] kwNames) {
        PyObject[] call = callArguments("decode", "errors", args, kwNames);
        byte[] data = octets(call[0]);
        String errors = codecErrors("decode", call[1]);
        Spec spec = specOf(args[0]);
        StringBuilder text = new StringBuilder(data.length);
        decodeInto(spec, data, errors, true, new int[] {0}, text);
        return PyTuple.of(PyStr.of(text.toString()), PyInt.of(data.length));
    }

    // --- incremental encoder -----------------------------------------------------------------

    private static PyObject encoderInit(PyObject[] args, String[] kwNames) {
        requireCodec(args[0]);
        Slots.setAttr(args[0], "errors", incrementalErrors("IncrementalEncoder", args, kwNames));
        Slots.setAttr(args[0], "_state", PyInt.of(initialState(specOfIncremental(args[0]), true)));
        Slots.setAttr(args[0], "_pending", PyBytes.of(EMPTY));
        return PyNone.INSTANCE;
    }

    private static PyObject encoderEncode(PyObject[] args, String[] kwNames) {
        PyObject[] call = callArguments("encode", "final", args, kwNames);
        boolean last = call[1] != null && Slots.truth(call[1]);
        PyObject self = args[0];
        Spec spec = specOfIncremental(self);
        String text = textOf(call[0]);
        byte[] pending = pendingOf(self).getValue();
        if (pending.length > 0) {
            // What `setstate` left behind is encoded first, exactly as the reference prepends it.
            text = new String(pending, StandardCharsets.UTF_8) + text;
            Slots.setAttr(self, "_pending", PyBytes.of(EMPTY));
        }
        int[] state = {modeOf(spec, self)};
        byte[] encoded = encode(spec, text, errorsOf(self), state, last);
        storeMode(spec, self, state[0]);
        return PyBytes.of(encoded);
    }

    private static PyObject decoderInit(PyObject[] args, String[] kwNames) {
        requireCodec(args[0]);
        Slots.setAttr(args[0], "errors", incrementalErrors("IncrementalDecoder", args, kwNames));
        Slots.setAttr(args[0], "_state", PyInt.of(initialState(specOfIncremental(args[0]), false)));
        Slots.setAttr(args[0], "_pending", PyBytes.of(EMPTY));
        return PyNone.INSTANCE;
    }

    private static PyObject decoderDecode(PyObject[] args, String[] kwNames) {
        PyObject[] call = callArguments("decode", "final", args, kwNames);
        boolean last = call[1] != null && Slots.truth(call[1]);
        PyObject self = args[0];
        Spec spec = specOfIncremental(self);
        byte[] pending = pendingOf(self).getValue();
        byte[] more = octets(call[0]);
        byte[] all = new byte[pending.length + more.length];
        System.arraycopy(pending, 0, all, 0, pending.length);
        System.arraycopy(more, 0, all, pending.length, more.length);
        int[] state = {modeOf(spec, self)};
        StringBuilder text = new StringBuilder(all.length);
        int cut = decodeInto(spec, all, errorsOf(self), last, state, text);
        storeMode(spec, self, state[0]);
        Slots.setAttr(self, "_pending",
                PyBytes.of(cut < 0 ? EMPTY : Arrays.copyOfRange(all, cut, all.length)));
        return PyStr.of(text.toString());
    }

    // --- reading a call's optional arguments -------------------------------------------------

    /**
     * The two leading parameters of the codec and incremental methods: {@code input} and one optional
     * second, named {@code errors} on the codec and {@code final} on the incremental codec.
     *
     * <p>Both methods are clinic functions, so their refusals are the clinic ones, in clinic's order:
     * a count over two is a count complaint, a name the call does not know is refused only after the
     * required parameter has been accounted for, and giving one parameter by name as well as by
     * position names both.
     */
    private static PyObject[] callArguments(
            String name, String second, PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        int given = positional - 1;
        int keywords = kwNames == null ? 0 : kwNames.length;
        if (given > 2) {
            throw Slots.typeError("%s() takes at most 2 arguments (%d given)", name, given);
        }
        PyObject input = given >= 1 ? args[1] : null;
        PyObject other = given >= 2 ? args[2] : null;
        for (int at = 0; at < keywords; at++) {
            String key = kwNames[at];
            PyObject value = args[positional + at];
            if ("input".equals(key)) {
                if (input != null) {
                    throw Slots.typeError(
                            "argument for %s() given by name ('input') and position (1)", name);
                }
                input = value;
            } else if (second.equals(key)) {
                if (other != null) {
                    throw Slots.typeError(
                            "argument for %s() given by name ('%s') and position (2)", name, key);
                }
                other = value;
            } else if (input == null) {
                // Clinic reports the parameter it never got before the name it does not know.
                throw Slots.typeError("%s() missing required argument 'input' (pos 1)", name);
            } else {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", name, key);
            }
        }
        if (input == null) {
            throw Slots.typeError("%s() missing required argument 'input' (pos 1)", name);
        }
        return new PyObject[] {input, other};
    }

    /**
     * A codec method's {@code errors} parameter, which clinic declares as {@code str} accepting
     * {@code None}: the sentence quotes the parameter by name and names both types it takes.
     */
    private static String codecErrors(String name, PyObject value) {
        if (value == null || value == PyNone.INSTANCE) {
            return "strict";
        }
        if (value instanceof PyStr text) {
            return text.getValue();
        }
        throw Slots.typeError(
                "%s() argument 'errors' must be str or None, not %s",
                name, value.getType().filedName());
    }

    /**
     * An {@code errors} parameter parsed by {@code PyArg_ParseTupleAndKeywords} with {@code 's'},
     * whose refusal names the position rather than the parameter.
     */
    private static PyObject plainErrors(String owner, int at, PyObject value) {
        if (value == null || value == PyNone.INSTANCE) {
            return PyStr.of("strict");
        }
        if (value instanceof PyStr) {
            return value;
        }
        throw Slots.typeError(
                "%s() argument %d must be str, not %s",
                owner, at, Slots.argumentName(value));
    }

    /**
     * The {@code O|s:StreamReader} parameter list: a stream any object may be, then {@code errors}.
     * The two names are the only ones {@code PyArg_ParseTupleAndKeywords} was given.
     */
    private static PyObject[] streamArguments(String owner, PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        int given = positional - 1;
        int keywords = kwNames == null ? 0 : kwNames.length;
        if (given > 2) {
            throw Slots.typeError("%s() takes at most 2 arguments (%d given)", owner, given);
        }
        if (keywords > 2) {
            throw Slots.typeError("%s() takes at most 2 keyword arguments (%d given)",
                    owner, keywords);
        }
        PyObject stream = given >= 1 ? args[1] : null;
        PyObject errors = given >= 2 ? args[2] : null;
        for (int at = 0; at < keywords; at++) {
            String key = kwNames[at];
            PyObject value = args[positional + at];
            if ("stream".equals(key)) {
                if (stream != null) {
                    throw Slots.typeError(
                            "argument for %s() given by name ('stream') and position (1)", owner);
                }
                stream = value;
            } else if ("errors".equals(key)) {
                if (errors != null) {
                    throw Slots.typeError(
                            "argument for %s() given by name ('errors') and position (2)", owner);
                }
                errors = value;
            } else if (stream == null) {
                throw Slots.typeError("%s() missing required argument 'stream' (pos 1)", owner);
            } else {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", owner, key);
            }
        }
        if (stream == null) {
            throw Slots.typeError("%s() missing required argument 'stream' (pos 1)", owner);
        }
        return new PyObject[] {stream, plainErrors(owner, 2, errors)};
    }

    /**
     * The {@code |s:IncrementalEncoder} parameter list: one optional {@code errors}, and PyArg's
     * count complaints, which say {@code keyword argument} when only names were given.
     */
    private static PyObject incrementalErrors(String owner, PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        int given = positional - 1;
        int keywords = kwNames == null ? 0 : kwNames.length;
        if (given > 1) {
            throw Slots.typeError("%s() takes at most 1 argument (%d given)", owner, given);
        }
        if (keywords > 1) {
            throw Slots.typeError("%s() takes at most 1 keyword argument (%d given)",
                    owner, keywords);
        }
        PyObject errors = given >= 1 ? args[1] : null;
        if (keywords == 1) {
            if (!"errors".equals(kwNames[0])) {
                throw Slots.typeError(
                        "%s() got an unexpected keyword argument '%s'", owner, kwNames[0]);
            }
            if (errors != null) {
                throw Slots.typeError(
                        "argument for %s() given by name ('errors') and position (1)", owner);
            }
            errors = args[positional];
        }
        return plainErrors(owner, 1, errors);
    }

    /**
     * The reference's {@code PyLong_FromByteArray}: the little-endian integer the nine (encoder) or
     * eight (decoder) state bytes pack into, which is what {@code getstate()} answers.
     */
    private static BigInteger packLe(byte[] bytes) {
        BigInteger value = BigInteger.ZERO;
        for (int at = bytes.length - 1; at >= 0; at--) {
            value = value.shiftLeft(8).or(BigInteger.valueOf(bytes[at] & 0xff));
        }
        return value;
    }

    /** The other direction: {@code width} low bytes of an integer, least significant first. */
    private static byte[] unpackLe(BigInteger value, int width) {
        byte[] out = new byte[width];
        BigInteger rest = value;
        BigInteger mask = BigInteger.valueOf(0xff);
        for (int at = 0; at < width; at++) {
            out[at] = (byte) rest.and(mask).intValue();
            rest = rest.shiftRight(8);
        }
        return out;
    }

    private static BigInteger stateValue(PyObject self) {
        PyObject state = Slots.findAttr(self, "_state");
        return state == null ? BigInteger.ZERO : Numbers.longOf(state);
    }

    private static void setStateValue(PyObject self, BigInteger value) {
        Slots.setAttr(self, "_state", PyInt.of(value));
    }

    /** The eight state bytes the codec's {@code state.c} holds, in the reference's order. */
    private static byte[] stateBytes(PyObject self) {
        return unpackLe(stateValue(self), 8);
    }

    /**
     * The engine's own small state, read out of the eight bytes: a mode number for the state
     * machines, and the untouched bytes' meaning for the flat codecs.
     *
     * <p>{@code iso2022_jp} keeps its current G0 designation in {@code state.c[0]}: {@code 'B'} is
     * ASCII, {@code 'J'} is JIS X 0201 Roman, {@code 'B'|0x80} is JIS X 0208 reached by {@code ESC $ B}
     * and {@code '@'|0x80} the same plane reached by {@code ESC $ @}, and any other byte is the
     * invalid designation {@code setstate} can install. {@code iso2022_kr} keeps its designation in
     * {@code state.c[1]} ({@code 'C'|0x80} once {@code ESC $ ) C} has been seen) and its SO/SI shift in
     * the low bit of {@code state.c[4]}.
     */
    private static int modeOf(Spec spec, PyObject self) {
        byte[] c = stateBytes(self);
        return switch (spec.kind()) {
            case STATELESS, ISO2022_JP2 -> 0;
            case HZ -> c[0] & 0xff;
            case ISO2022_JP -> {
                int g0 = c[0] & 0xff;
                if (g0 == 0x42) {
                    yield 0;
                }
                if (g0 == 0x4a) {
                    yield 2;
                }
                if (g0 == 0xc2) {
                    yield 1;
                }
                if (g0 == 0xc0) {
                    // `ESC $ @`: the same JIS X 0208 plane, designated the other way. The reference
                    // keeps the byte it saw, so `getstate` tells the two apart.
                    yield 4;
                }
                yield 3;
            }
            case ISO2022_KR -> {
                int g0 = c[0] & 0xff;
                int g1 = c[1] & 0xff;
                int flags = c[4] & 0xff;
                int mode = 0;
                if (g0 != 0x42 || (g1 != 0x42 && g1 != 0xc3)) {
                    mode |= 4;
                }
                if (g1 == 0xc3) {
                    mode |= 1;
                }
                if ((flags & 1) != 0) {
                    mode |= 2;
                }
                yield mode;
            }
        };
    }

    /** Write the engine's state back into the eight bytes, except for the codecs that have none. */
    private static void storeMode(Spec spec, PyObject self, int mode) {
        byte[] c = stateBytes(self);
        switch (spec.kind()) {
            case STATELESS, ISO2022_JP2 -> {
                return;
            }
            case HZ -> {
                c[0] = (byte) mode;
                c[1] = 0;
                c[2] = 0;
                c[3] = 0;
                c[4] = 0;
                c[5] = 0;
                c[6] = 0;
                c[7] = 0;
            }
            case ISO2022_JP -> {
                c[0] = (byte) switch (mode) {
                    case 1 -> 0xc2;
                    case 2 -> 0x4a;
                    case 3 -> 0xff;
                    case 4 -> 0xc0;
                    default -> 0x42;
                };
                // G1 and G2 are left as they were: the reference's init sets both to 'B' for the
                // decoder and to 'B'/0 for the encoder, and nothing in this codec ever rewrites
                // them, so `getstate` keeps whichever of the two the instance started with.
            }
            case ISO2022_KR -> {
                c[0] = 0x42;
                c[1] = (byte) ((mode & 1) != 0 ? 0xc3 : 0x42);
                c[2] = (mode & 4) != 0 ? (byte) 0xff : (byte) 0x42;
                c[3] = 0;
                c[4] = (byte) ((mode & 2) != 0 ? 1 : 0);
                c[5] = 0;
                c[6] = 0;
                c[7] = 0;
            }
        }
        setStateValue(self, packLe(c));
    }

    /** What {@code encoder_init} and {@code decoder_init} leave in {@code state.c}. */
    private static BigInteger initialState(Spec spec, boolean encoder) {
        byte[] c = new byte[8];
        switch (spec.kind()) {
            case STATELESS, ISO2022_JP2 -> {
            }
            case HZ -> {
            }
            case ISO2022_JP -> {
                c[0] = 0x42;
                c[1] = 0x42;
                if (!encoder) {
                    c[2] = 0x42;
                }
            }
            case ISO2022_KR -> {
                c[0] = 0x42;
                c[1] = 0x42;
                c[2] = (byte) (encoder ? 0 : 0x42);
            }
        }
        return packLe(c);
    }

    private static PyBytes pendingOf(PyObject self) {
        PyObject pending = Slots.findAttr(self, "_pending");
        return pending instanceof PyBytes bytes ? bytes : PyBytes.of(EMPTY);
    }

    /**
     * {@code getstate()} of an incremental encoder: the pending UTF-8 bytes behind a length byte, then
     * the eight state bytes, as one little-endian integer.
     */
    private static PyObject encoderState(PyObject self) {
        byte[] pending = pendingOf(self).getValue();
        byte[] c = stateBytes(self);
        byte[] all = new byte[1 + pending.length + 8];
        all[0] = (byte) pending.length;
        System.arraycopy(pending, 0, all, 1, pending.length);
        System.arraycopy(c, 0, all, 1 + pending.length, 8);
        return PyInt.of(packLe(all));
    }

    /**
     * {@code setstate()} of an incremental encoder, with the reference's refusals in its order: an
     * integer that does not fit seventeen bytes, a pending buffer longer than eight, and pending bytes
     * that are not UTF-8.
     */
    private static PyObject encoderSetState(PyObject self, PyObject state) {
        Spec spec = specOfIncremental(self);
        BigInteger value = integerOf(state);
        if (value.signum() < 0 || value.bitLength() > 17 * 8) {
            throw Slots.overflowError("Python int too large to convert to C unsigned long");
        }
        byte[] all = unpackLe(value, 17);
        int pendingLength = all[0] & 0xff;
        if (pendingLength > 8) {
            throw PyRaise.raise(ExceptionTypes.UNICODE_ERROR, "pending buffer too large");
        }
        byte[] pending = Arrays.copyOfRange(all, 1, 1 + pendingLength);
        requireUtf8(spec, pending);
        byte[] c = Arrays.copyOfRange(all, 1 + pendingLength, 9 + pendingLength);
        Slots.setAttr(self, "_pending", PyBytes.of(pending));
        setStateValue(self, packLe(c));
        return PyNone.INSTANCE;
    }

    /** {@code getstate()} of an incremental decoder: the pending octets and the state integer. */
    private static PyObject decoderState(PyObject self) {
        return PyTuple.of(pendingOf(self), PyInt.of(stateValue(self)));
    }

    /**
     * {@code setstate()} of an incremental decoder: a {@code (bytes, int)} pair, the octets no longer
     * than eight, the integer no wider than eight bytes.
     */
    private static PyObject decoderSetState(PyObject self, PyObject state) {
        Spec spec = specOfIncremental(self);
        if (!(state instanceof PyTuple pair)) {
            throw Slots.typeError(
                    "setstate() argument must be tuple, not %s", Slots.argumentName(state));
        }
        if (pair.size() != 2) {
            throw Slots.typeError("setstate() takes exactly 2 arguments (%d given)", pair.size());
        }
        if (!(pair.at(0) instanceof PyBytes pending) || !(pair.at(1) instanceof PyInt)) {
            // PyArg's `S` and `O!` share one sentence, the one after the semicolon.
            throw Slots.typeError("setstate(): illegal state argument");
        }
        BigInteger value = integerOf(pair.at(1));
        if (value.signum() < 0 || value.bitLength() > 8 * 8) {
            throw Slots.overflowError("Python int too large to convert to C unsigned long");
        }
        byte[] bytes = pending.getValue();
        if (bytes.length > 8) {
            throw Slots.unicodeDecode(
                    spec.name(), bytes, 0, bytes.length, "pending buffer too large");
        }
        Slots.setAttr(self, "_pending", PyBytes.of(bytes));
        setStateValue(self, value);
        return PyNone.INSTANCE;
    }

    private static BigInteger integerOf(PyObject value) {
        if (!(value instanceof PyInt)) {
            throw Slots.typeError(
                    "setstate() argument must be int, not %s", Slots.argumentName(value));
        }
        return Numbers.longOf(value);
    }

    /** The pending bytes must be UTF-8, which is the only shape an encoder can hold them in. */
    private static void requireUtf8(Spec spec, byte[] pending) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(pending));
        } catch (CharacterCodingException refused) {
            throw Slots.unicodeDecode(spec.name(), pending, 0, pending.length,
                    "invalid start byte");
        }
    }

    private static String errorsOf(PyObject self) {
        PyObject errors = Slots.findAttr(self, "errors");
        return errors == null ? "strict" : Numbers.asText(errors);
    }

    /** The octets of a bytes-like argument, or the buffer protocol's refusal. */
    private static byte[] octets(PyObject value) {
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return PyBytes.octets(value);
        }
        throw Slots.typeError("a bytes-like object is required, not '%s'",
                value.getType().filedName());
    }

    private static String textOf(PyObject value) {
        return value instanceof PyStr text ? text.getValue() : value.str();
    }

    // --- encoding ----------------------------------------------------------------------------

    /** The replacement a handler answered with: text to route through the codec, or raw octets. */
    private record EncodeReply(String text, byte[] raw, int resume) {}

    private static byte[] encode(Spec spec, String text, String errors, int[] state, boolean last) {
        return switch (spec.kind()) {
            case STATELESS -> encodeStateless(spec, text, errors);
            case HZ -> encodeHz(spec, text, errors, state, last);
            case ISO2022_JP -> encodeIso2022Jp(spec, text, errors, state, last);
            case ISO2022_KR -> encodeIso2022Kr(spec, text, errors, state, last);
            case ISO2022_JP2 -> encodeStreaming(spec, text, errors);
        };
    }

    /**
     * The flat-table encoders, one code point at a time.
     *
     * <p>The reference's CJK encoders answer {@code 1} at the first character they cannot write, so an
     * error covers exactly one character rather than a run — which is why the walk here hands the handler
     * a range of one and resumes where the handler says.
     */
    private static byte[] encodeStateless(Spec spec, String text, String errors) {
        Charset charset = Charset.forName(spec.charset());
        CharsetEncoder probe = charset.newEncoder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder run = new StringBuilder();
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            if (probe.canEncode(one)) {
                run.append(one);
                at += width;
                continue;
            }
            flushRun(run, charset, out);
            EncodeReply reply = encodeError(spec, text, at, at + width, errors, charset);
            if (reply.text() != null) {
                byte[] bytes = encodeQuietly(reply.text(), charset);
                out.write(bytes, 0, bytes.length);
            } else {
                out.write(reply.raw(), 0, reply.raw().length);
            }
            at = reply.resume();
        }
        flushRun(run, charset, out);
        return out.toByteArray();
    }

    private static void flushRun(StringBuilder run, Charset charset, ByteArrayOutputStream out) {
        if (run.length() == 0) {
            return;
        }
        byte[] bytes = run.toString().getBytes(charset);
        out.write(bytes, 0, bytes.length);
        run.setLength(0);
    }

    private static byte[] encodeQuietly(String text, Charset charset) {
        return text.getBytes(charset);
    }

    /** {@code hz}: ASCII outside {@code ~{...~}}, GB2312 in two-byte pairs between them. */
    private static byte[] encodeHz(Spec spec, String text, String errors, int[] state, boolean last) {
        Charset gb = Charset.forName("GB2312");
        CharsetEncoder probe = gb.newEncoder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean[] gbMode = {state[0] == 1};
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            if (codePoint < 0x80 || probe.canEncode(one)) {
                hzPut(out, one, gbMode, gb, probe);
                at += width;
                continue;
            }
            EncodeReply reply = encodeError(spec, text, at, at + width, errors, gb);
            if (reply.text() != null) {
                hzPut(out, reply.text(), gbMode, gb, probe);
            } else {
                out.write(reply.raw(), 0, reply.raw().length);
            }
            at = reply.resume();
        }
        if (gbMode[0] && last) {
            out.write('~');
            out.write('}');
            gbMode[0] = false;
        }
        state[0] = gbMode[0] ? 1 : 0;
        return out.toByteArray();
    }

    private static void hzPut(
            ByteArrayOutputStream out, String text, boolean[] gbMode, Charset gb, CharsetEncoder probe) {
        for (int at = 0; at < text.length(); ) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            if (codePoint < 0x80) {
                if (gbMode[0]) {
                    out.write('~');
                    out.write('}');
                    gbMode[0] = false;
                }
                if (codePoint == '~') {
                    out.write('~');
                }
                out.write(codePoint);
            } else if (probe.canEncode(one)) {
                if (!gbMode[0]) {
                    out.write('~');
                    out.write('{');
                    gbMode[0] = true;
                }
                byte[] bytes = one.getBytes(gb);
                // HZ carries GB2312 in seven bits: each octet is the GB2312 octet minus 0x80.
                out.write((bytes[0] & 0xff) - 0x80);
                out.write((bytes[1] & 0xff) - 0x80);
            } else {
                out.write('?');
            }
            at += width;
        }
    }

    /**
     * {@code iso2022_jp}: ASCII, JIS X 0201 Roman and JIS X 0208, with {@code ESC ( B} / {@code
     * ESC ( J} / {@code ESC $ B} between them.
     */
    private static byte[] encodeIso2022Jp(Spec spec, String text, String errors, int[] state, boolean last) {
        Charset euc = Charset.forName("EUC-JP");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] g0 = {state[0]};
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            if (codePoint < 0x80 || codePoint == 0xa5 || codePoint == 0x203e
                    || jisBytes(one, euc) != null) {
                jpPut(out, one, g0, euc);
                at += width;
                continue;
            }
            EncodeReply reply = encodeError(spec, text, at, at + width, errors, euc);
            if (reply.text() != null) {
                jpPut(out, reply.text(), g0, euc);
            } else {
                out.write(reply.raw(), 0, reply.raw().length);
            }
            at = reply.resume();
        }
        if (g0[0] != 0 && last) {
            jpDesignate(out, 0, g0);
        }
        state[0] = g0[0];
        return out.toByteArray();
    }

    /** The two JIS X 0208 bytes of one character, or null when the character is not in that set. */
    private static byte[] jisBytes(String one, Charset euc) {
        byte[] bytes = one.getBytes(euc);
        return bytes.length == 2 && (bytes[0] & 0xff) >= 0xa1 ? bytes : null;
    }

    private static void jpPut(ByteArrayOutputStream out, String text, int[] g0, Charset euc) {
        for (int at = 0; at < text.length(); ) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            if (codePoint == 0xa5 || codePoint == 0x203e) {
                // JIS X 0201 Roman: the two graphics that differ from ASCII, which the reference
                // reaches through its own designation rather than through ISO 8859-1.
                jpDesignate(out, 2, g0);
                out.write(codePoint == 0xa5 ? 0x5c : 0x7e);
            } else if (codePoint < 0x80) {
                jpDesignate(out, 0, g0);
                out.write(codePoint);
            } else {
                byte[] bytes = jisBytes(one, euc);
                if (bytes == null) {
                    out.write('?');
                } else {
                    jpDesignate(out, 1, g0);
                    out.write((bytes[0] & 0xff) - 0x80);
                    out.write((bytes[1] & 0xff) - 0x80);
                }
            }
            at += width;
        }
    }

    /** Move G0 to {@code want}, writing the escape only when the state is not already there. */
    private static void jpDesignate(ByteArrayOutputStream out, int want, int[] g0) {
        if (g0[0] == want) {
            return;
        }
        switch (want) {
            case 0 -> {
                out.write(0x1b);
                out.write('(');
                out.write('B');
            }
            case 1 -> {
                out.write(0x1b);
                out.write('$');
                out.write('B');
            }
            case 2 -> {
                out.write(0x1b);
                out.write('(');
                out.write('J');
            }
            default -> {
                return;
            }
        }
        g0[0] = want;
    }

    /** {@code iso2022_kr}: ASCII, and KSC 5601 designated once and shifted in with SO/SI. */
    private static byte[] encodeIso2022Kr(Spec spec, String text, String errors, int[] state, boolean last) {
        Charset euc = Charset.forName("EUC-KR");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean[] designated = {(state[0] & 1) != 0};
        boolean[] shifted = {(state[0] & 2) != 0};
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            byte[] bytes = codePoint < 0x80 ? null : krBytes(one, euc);
            if (codePoint < 0x80 || bytes != null) {
                krPut(out, one, designated, shifted, euc);
                at += width;
                continue;
            }
            EncodeReply reply = encodeError(spec, text, at, at + width, errors, euc);
            if (reply.text() != null) {
                krPut(out, reply.text(), designated, shifted, euc);
            } else {
                out.write(reply.raw(), 0, reply.raw().length);
            }
            at = reply.resume();
        }
        if (shifted[0] && last) {
            out.write(0x0f);
            shifted[0] = false;
        }
        state[0] = (designated[0] ? 1 : 0) | (shifted[0] ? 2 : 0);
        return out.toByteArray();
    }

    private static byte[] krBytes(String one, Charset euc) {
        byte[] bytes = one.getBytes(euc);
        return bytes.length == 2 && (bytes[0] & 0xff) >= 0xa1 ? bytes : null;
    }

    private static void krPut(
            ByteArrayOutputStream out,
            String text,
            boolean[] designated,
            boolean[] shifted,
            Charset euc) {
        for (int at = 0; at < text.length(); ) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            if (codePoint < 0x80) {
                if (shifted[0]) {
                    out.write(0x0f);
                    shifted[0] = false;
                }
                out.write(codePoint);
            } else {
                byte[] bytes = krBytes(one, euc);
                if (bytes == null) {
                    out.write('?');
                } else {
                    if (!designated[0]) {
                        out.write(0x1b);
                        out.write('$');
                        out.write(')');
                        out.write('C');
                        designated[0] = true;
                    }
                    if (!shifted[0]) {
                        out.write(0x0e);
                        shifted[0] = true;
                    }
                    out.write((bytes[0] & 0xff) - 0x80);
                    out.write((bytes[1] & 0xff) - 0x80);
                }
            }
            at += width;
        }
    }

    /** {@code iso2022_jp_2}: delegated to the JDK's own encoder, one call per whole string. */
    private static byte[] encodeStreaming(Spec spec, String text, String errors) {
        Charset charset = Charset.forName(spec.charset());
        CharsetEncoder probe = charset.newEncoder();
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder run = new StringBuilder();
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            int width = Character.charCount(codePoint);
            String one = text.substring(at, at + width);
            if (probe.canEncode(one)) {
                run.append(one);
                at += width;
                continue;
            }
            feed(encoder, run, out);
            run.setLength(0);
            EncodeReply reply = encodeError(spec, text, at, at + width, errors, charset);
            if (reply.text() != null) {
                feed(encoder, new StringBuilder(reply.text()), out);
            } else {
                out.write(reply.raw(), 0, reply.raw().length);
            }
            at = reply.resume();
        }
        feed(encoder, run, out);
        finishEncoder(encoder, out);
        return out.toByteArray();
    }

    private static void finishEncoder(CharsetEncoder encoder, ByteArrayOutputStream out) {
        while (true) {
            ByteBuffer buffer = ByteBuffer.allocate(64);
            CoderResult result = encoder.encode(CharBuffer.allocate(0), buffer, true);
            buffer.flip();
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            out.write(chunk, 0, chunk.length);
            if (!result.isOverflow()) {
                break;
            }
        }
        flushEncoder(encoder, out);
    }

    private static void feed(
            CharsetEncoder encoder, CharSequence text, ByteArrayOutputStream out) {
        if (text.length() == 0) {
            return;
        }
        CharBuffer in = CharBuffer.wrap(text);
        while (true) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(32, in.remaining() * 8 + 32));
            CoderResult result = encoder.encode(in, buffer, false);
            buffer.flip();
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            out.write(chunk, 0, chunk.length);
            if (result.isUnderflow()) {
                return;
            }
            if (!result.isOverflow()) {
                encoder.reset();
                return;
            }
        }
    }

    private static void flushEncoder(CharsetEncoder encoder, ByteArrayOutputStream out) {
        while (true) {
            ByteBuffer buffer = ByteBuffer.allocate(64);
            CoderResult result = encoder.flush(buffer);
            buffer.flip();
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            out.write(chunk, 0, chunk.length);
            if (!result.isOverflow()) {
                return;
            }
        }
    }

    /**
     * One refusal on the encode side: the exception the handler is shown, and its answer routed back
     * through the codec — text as text, octets (which only {@code surrogateescape} answers with) as raw
     * bytes, exactly as {@code multibytecodec_encerror} writes the two shapes.
     */
    private static EncodeReply encodeError(
            Spec spec, String text, int start, int end, String errors, Charset charset) {
        // The reference's error positions count characters, not UTF-16 units, so an astral character
        // is one position wide however many units this runtime stores it in.
        int first = text.codePointCount(0, start);
        int last = first + text.codePointCount(start, end);
        PyRaise failure =
                Slots.unicodeEncode(spec.name(), text, first, last, "illegal multibyte sequence");
        PyObject answer = Slots.call(
                ErrorHandlers.lookup(errors), new PyObject[] {failure.value()}, null);
        PyObject replacement = Slots.getItem(answer, PyInt.of(0));
        int resume = resumePoint(
                Numbers.longOf(Slots.getItem(answer, PyInt.of(1))),
                text.codePointCount(0, text.length()));
        int units = text.offsetByCodePoints(0, resume);
        if (replacement instanceof PyStr str) {
            return new EncodeReply(str.getValue(), null, units);
        }
        if (replacement instanceof PyBytes bytes) {
            return new EncodeReply(null, bytes.getValue(), units);
        }
        throw Slots.typeError("encode error handler returned wrong type");
    }

    // --- decoding ----------------------------------------------------------------------------

    /** Walk {@code data}, appending to {@code text}; answer where an unfinished tail starts, or -1. */
    private static int decodeInto(
            Spec spec, byte[] data, String errors, boolean last, int[] state, StringBuilder text) {
        return switch (spec.kind()) {
            case STATELESS -> decodeStateless(spec, data, errors, last, text);
            case HZ -> decodeHz(spec, data, errors, last, state, text);
            case ISO2022_JP -> decodeIso2022Jp(spec, data, errors, last, state, text);
            case ISO2022_KR -> decodeIso2022Kr(spec, data, errors, last, state, text);
            case ISO2022_JP2 -> decodeStreaming(spec, data, errors, last, text);
        };
    }

    private static int decodeStateless(
            Spec spec, byte[] data, String errors, boolean last, StringBuilder text) {
        Charset charset = Charset.forName(spec.charset());
        int size = data.length;
        int at = 0;
        while (at < size) {
            int octet = data[at] & 0xff;
            int need = unitLength(spec.name(), data, at, size);
            if (need == 0) {
                String one = decodeExact(charset, data, at, 1);
                if (one != null) {
                    text.append(one);
                    at += 1;
                    continue;
                }
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                continue;
            }
            if (size - at < need) {
                if (!last) {
                    return at;
                }
                at = decodeError(spec, data, at, size, "incomplete multibyte sequence", errors, text);
                continue;
            }
            String one = decodeExact(charset, data, at, need);
            if (one != null) {
                text.append(one);
                at += need;
                continue;
            }
            at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
        }
        return -1;
    }

    /**
     * How many octets the sequence starting at {@code at} wants: 0 for a character that stands alone,
     * otherwise the width of its lead — with the three shapes that are not simply two.
     *
     * <p>A high octet that no table starts is still given width 2, because the reference's decoders test
     * the remaining length before they test the octet, so a lone illegal octet at the end of the input is
     * reported as {@code incomplete} rather than {@code illegal}.
     */
    private static int unitLength(String codec, byte[] data, int at, int size) {
        int octet = data[at] & 0xff;
        if (octet < 0x80) {
            return 0;
        }
        switch (codec) {
            case "shift_jis":
                if (octet >= 0xa1 && octet <= 0xdf) {
                    return 0;
                }
                if (octet == 0x80 || octet == 0xa0 || octet >= 0xfd) {
                    return 1;
                }
                return 2;
            case "cp932":
                if (octet == 0x80 || octet == 0xa0 || (octet >= 0xa1 && octet <= 0xdf) || octet >= 0xfd) {
                    return 0;
                }
                return 2;
            case "euc_jp":
                return octet == 0x8f ? 3 : 2;
            case "gb18030":
                if (at + 1 < size && data[at + 1] >= 0x30 && data[at + 1] <= 0x39) {
                    return 4;
                }
                return 2;
            default:
                return 2;
        }
    }

    /** One sequence through the JDK's decoder, or null when it does not decode. */
    private static String decodeExact(Charset charset, byte[] data, int at, int length) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(data, at, length));
            return decoded.toString();
        } catch (CharacterCodingException refused) {
            return null;
        }
    }

    /** {@code hz} decoding: ASCII, {@code ~{...~}} GB mode, {@code ~~} for a literal tilde. */
    private static int decodeHz(
            Spec spec, byte[] data, String errors, boolean last, int[] state, StringBuilder text) {
        Charset gb = Charset.forName("GB2312");
        boolean gbMode = state[0] == 1;
        int size = data.length;
        int at = 0;
        while (at < size) {
            int octet = data[at] & 0xff;
            if (octet == '~') {
                if (at + 1 >= size) {
                    if (!last) {
                        state[0] = gbMode ? 1 : 0;
                        return at;
                    }
                    at = decodeError(spec, data, at, size, "incomplete multibyte sequence", errors, text);
                    continue;
                }
                int next = data[at + 1] & 0xff;
                if (next == '~') {
                    text.append('~');
                    at += 2;
                } else if (next == '\n') {
                    at += 2;
                } else if (next == '{') {
                    gbMode = true;
                    at += 2;
                } else if (next == '}') {
                    gbMode = false;
                    at += 2;
                } else {
                    at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                }
                continue;
            }
            if (!gbMode) {
                if (octet < 0x80) {
                    text.append((char) octet);
                    at += 1;
                } else {
                    at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                }
                continue;
            }
            if (size - at < 2) {
                if (!last) {
                    state[0] = 1;
                    return at;
                }
                at = decodeError(spec, data, at, size, "incomplete multibyte sequence", errors, text);
                continue;
            }
            String one = null;
            int first = octet;
            int second = data[at + 1] & 0xff;
            if (first >= 0x21 && first <= 0x7e && second >= 0x21 && second <= 0x7e) {
                byte[] pair = {(byte) (first + 0x80), (byte) (second + 0x80)};
                one = decodeExact(gb, pair, 0, 2);
            }
            if (one != null) {
                text.append(one);
                at += 2;
            } else {
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
            }
        }
        state[0] = gbMode ? 1 : 0;
        return -1;
    }

    /** {@code iso2022_jp} decoding: ASCII, JIS X 0201 Roman and JIS X 0208, and the escapes between. */
    private static int decodeIso2022Jp(
            Spec spec, byte[] data, String errors, boolean last, int[] state, StringBuilder text) {
        Charset euc = Charset.forName("EUC-JP");
        int mode = state[0];
        int size = data.length;
        int at = 0;
        while (at < size) {
            int octet = data[at] & 0xff;
            if (octet == 0x1b) {
                if (at + 1 >= size) {
                    if (!last) {
                        state[0] = mode;
                        return at;
                    }
                    at = decodeError(spec, data, at, size, "incomplete multibyte sequence", errors, text);
                    continue;
                }
                int second = data[at + 1] & 0xff;
                int third = at + 2 < size ? data[at + 2] & 0xff : -1;
                if (second == '$') {
                    if (third < 0) {
                        if (!last) {
                            state[0] = mode;
                            return at;
                        }
                        at = decodeError(
                                spec, data, at, size, "incomplete multibyte sequence", errors, text);
                        continue;
                    }
                    if (third == 'B' || third == '@') {
                        // `ESC $ B` and `ESC $ @` name the same plane; the byte seen is what the
                        // state keeps, which is how the reference reports them differently.
                        mode = third == 'B' ? 1 : 4;
                        at += 3;
                    } else {
                        at = decodeError(
                                spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                    }
                    continue;
                }
                if (second == '(') {
                    if (third < 0) {
                        if (!last) {
                            state[0] = mode;
                            return at;
                        }
                        at = decodeError(
                                spec, data, at, size, "incomplete multibyte sequence", errors, text);
                        continue;
                    }
                    if (third == 'B') {
                        mode = 0;
                        at += 3;
                    } else if (third == 'J') {
                        mode = 2;
                        at += 3;
                    } else {
                        at = decodeError(
                                spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                    }
                    continue;
                }
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                continue;
            }
            if (mode == 3) {
                // A designation `setstate` installed that names no charset: every graphic octet is
                // refused one by one, which is what the reference's own decoder does with it.
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                continue;
            }
            if (mode == 1 || mode == 4) {
                if (size - at < 2) {
                    if (!last) {
                        state[0] = mode;
                        return at;
                    }
                    at = decodeError(spec, data, at, size, "incomplete multibyte sequence", errors, text);
                    continue;
                }
                int first = octet;
                int second = data[at + 1] & 0xff;
                if (first >= 0x21 && first <= 0x7e && second >= 0x21 && second <= 0x7e) {
                    byte[] pair = {(byte) (first + 0x80), (byte) (second + 0x80)};
                    String one = decodeExact(euc, pair, 0, 2);
                    if (one != null) {
                        text.append(one);
                        at += 2;
                        continue;
                    }
                }
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                continue;
            }
            if (octet >= 0x80) {
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                continue;
            }
            if (mode == 2) {
                if (octet == 0x5c) {
                    text.append('\u00a5');
                } else if (octet == 0x7e) {
                    text.append('\u203e');
                } else {
                    text.append((char) octet);
                }
            } else {
                text.append((char) octet);
            }
            at += 1;
        }
        state[0] = mode;
        return -1;
    }

    /** {@code iso2022_kr} decoding: 7-bit KSC 5601 shifts, with {@code ESC $ ) C} designating it. */
    private static int decodeIso2022Kr(
            Spec spec, byte[] data, String errors, boolean last, int[] state, StringBuilder text) {
        Charset euc = Charset.forName("EUC-KR");
        boolean designated = (state[0] & 1) != 0;
        boolean shifted = (state[0] & 2) != 0;
        boolean invalid = (state[0] & 4) != 0;
        int size = data.length;
        int at = 0;
        while (at < size) {
            int octet = data[at] & 0xff;
            if (octet == 0x1b) {
                if (size - at < 2) {
                    if (!last) {
                        state[0] = (designated ? 1 : 0) | (shifted ? 2 : 0);
                        return at;
                    }
                    at = decodeError(spec, data, at, size, "incomplete multibyte sequence", errors, text);
                    continue;
                }
                if (data[at + 1] == '$') {
                    if (size - at < 4) {
                        if (!last) {
                            state[0] = (designated ? 1 : 0) | (shifted ? 2 : 0);
                            return at;
                        }
                        at = decodeError(
                                spec, data, at, size, "incomplete multibyte sequence", errors, text);
                        continue;
                    }
                    if (data[at + 2] == ')' && data[at + 3] == 'C') {
                        designated = true;
                        at += 4;
                        continue;
                    }
                }
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                continue;
            }
            if (octet == 0x0e) {
                shifted = true;
                at += 1;
                continue;
            }
            if (octet == 0x0f) {
                shifted = false;
                at += 1;
                continue;
            }
            if (invalid) {
                at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                continue;
            }
            if (!shifted) {
                if (octet < 0x80) {
                    text.append((char) octet);
                    at += 1;
                } else {
                    at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
                }
                continue;
            }
            if (size - at < 2) {
                if (!last) {
                    state[0] = (designated ? 1 : 0) | (shifted ? 2 : 0);
                    return at;
                }
                at = decodeError(spec, data, at, size, "incomplete multibyte sequence", errors, text);
                continue;
            }
            int first = octet;
            int second = data[at + 1] & 0xff;
            if (first >= 0x21 && first <= 0x7e && second >= 0x21 && second <= 0x7e) {
                byte[] pair = {(byte) (first + 0x80), (byte) (second + 0x80)};
                String one = decodeExact(euc, pair, 0, 2);
                if (one != null) {
                    text.append(one);
                    at += 2;
                    continue;
                }
            }
            at = decodeError(spec, data, at, at + 1, "illegal multibyte sequence", errors, text);
        }
        state[0] = (designated ? 1 : 0) | (shifted ? 2 : 0);
        return -1;
    }

    /** {@code iso2022_jp_2}: delegated to the JDK's own decoder. */
    private static int decodeStreaming(
            Spec spec, byte[] data, String errors, boolean last, StringBuilder text) {
        Charset charset = Charset.forName(spec.charset());
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(data);
        while (true) {
            CharBuffer buffer = CharBuffer.allocate(Math.max(32, in.remaining() * 2 + 32));
            CoderResult result = decoder.decode(in, buffer, last);
            buffer.flip();
            text.append(buffer);
            if (result.isUnderflow()) {
                if (last) {
                    while (true) {
                        CharBuffer tail = CharBuffer.allocate(16);
                        CoderResult flushed = decoder.flush(tail);
                        tail.flip();
                        text.append(tail);
                        if (!flushed.isOverflow()) {
                            break;
                        }
                    }
                }
                break;
            }
            if (result.isOverflow()) {
                continue;
            }
            int start = in.position();
            int resume = decodeError(spec, data, start, Math.min(start + 1, data.length),
                    "illegal multibyte sequence", errors, text);
            in.position(resume);
        }
        return !last && in.hasRemaining() ? in.position() : -1;
    }

    /**
     * One refusal on the decode side: the exception the handler is shown and the text it answers with.
     * The walk resumes where the handler says, not at the end of the range it was shown.
     */
    private static int decodeError(
            Spec spec,
            byte[] data,
            int start,
            int end,
            String reason,
            String errors,
            StringBuilder text) {
        PyRaise failure = Slots.unicodeDecode(spec.name(), data, start, end, reason);
        PyObject answer = Slots.call(
                ErrorHandlers.lookup(errors), new PyObject[] {failure.value()}, null);
        PyObject replacement = Slots.getItem(answer, PyInt.of(0));
        int resume = resumePoint(Numbers.longOf(Slots.getItem(answer, PyInt.of(1))), data.length);
        if (replacement instanceof PyStr str) {
            text.append(str.getValue());
        } else if (replacement instanceof PyBytes bytes) {
            text.append(new String(bytes.getValue(), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        return resume;
    }

    /**
     * An error handler's resumption index, checked the way {@code multibytecodec} checks it: a negative
     * index counts from the end of the buffer, and anything outside it is an {@code IndexError} rather
     * than a silent wrap.
     */
    private static int resumePoint(BigInteger answer, int length) {
        BigInteger resume = answer.signum() < 0
                ? answer.add(BigInteger.valueOf(length))
                : answer;
        if (resume.signum() < 0 || resume.compareTo(BigInteger.valueOf(length)) > 0) {
            throw Slots.indexError("position %s from error handler out of bounds", answer);
        }
        return resume.intValueExact();
    }
}
