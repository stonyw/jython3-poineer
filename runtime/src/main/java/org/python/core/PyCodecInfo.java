package org.python.core;

/**
 * {@code codecs.CodecInfo}: what {@code _codecs.lookup} answers with.
 *
 * <p>{@code codecs.py} declares it as {@code class CodecInfo(tuple)}, which this runtime cannot run
 * until tuple subclassing exists, so it is built in Java and filed under the name that module would
 * have given it. A value is a four-item tuple — {@code encode}, {@code decode}, {@code streamreader},
 * {@code streamwriter} — with three names read from fields beside it, which is what CPython's
 * {@code __new__} does by writing them into the instance dictionary.
 *
 * <p>{@code _is_text_encoding} is a value of the type rather than of the value, exactly as the
 * reference declares it, because that is the only name {@code _pyio} asks about.
 */
final class PyCodecInfo extends PyTuple {

    private static final String[] PARAMETERS = {
        "encode", "decode", "streamreader", "streamwriter", "incrementalencoder",
        "incrementaldecoder", "name"
    };

    /**
     * The type's dictionary: the four names the items answer, the three the fields answer, and the one
     * value {@code _pyio} reads.
     */
    private static final Members METHODS = Members.of(PyType.CODEC_INFO)
            .addField("encode", "The encoding function")
            .addField("decode", "The decoding function")
            .addField("streamreader", "The stream reader class")
            .addField("streamwriter", "The stream writer class")
            .addValue("_is_text_encoding", PyBool.TRUE)
            .addValue("__doc__", PyType.CODEC_INFO.getAttr("__doc__"));

    /** {@code incrementalencoder}: not one of the four items, so carried beside them as CPython does. */
    private final PyObject incrementalEncoder;

    /** {@code incrementaldecoder}: the same. */
    private final PyObject incrementalDecoder;

    /** {@code name}: the encoding this value describes, or {@code None}. */
    private final PyObject name;

    PyCodecInfo(PyObject[] items, PyObject incrementalEncoder,
            PyObject incrementalDecoder, PyObject name) {
        super(items, PyType.CODEC_INFO);
        this.incrementalEncoder = incrementalEncoder;
        this.incrementalDecoder = incrementalDecoder;
        this.name = name;
    }

    /** The value {@code _codecs.lookup} builds for one of the codecs this runtime knows. */
    static PyCodecInfo of(
            PyObject encode,
            PyObject decode,
            PyObject incrementalEncoder,
            PyObject incrementalDecoder,
            PyObject name) {
        return new PyCodecInfo(
                new PyObject[] {encode, decode, PyNone.INSTANCE, PyNone.INSTANCE},
                incrementalEncoder, incrementalDecoder, name);
    }

    /** {@code codecs.CodecInfo(encode, decode, streamreader=None, ..., name=None)}. */
    static PyObject called(PyObject[] args, PyTuple keywordNames) {
        String[] names = new String[keywordNames == null ? 0 : keywordNames.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = Numbers.asText(keywordNames.at(i));
        }
        PyObject[] values = Arguments.parameters(
                "CodecInfo", args, names, PARAMETERS,
                new PyObject[] {null, null, PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE,
                    PyNone.INSTANCE, PyNone.INSTANCE},
                2);
        return new PyCodecInfo(
                new PyObject[] {values[0], values[1], values[2], values[3]},
                values[4], values[5], values[6]);
    }

    @Override
    public PyObject getAttr(String name) {
        PyObject filed = Members.filed(this, name);
        if (filed != Slots.DECLINED) {
            return filed;
        }
        if (name.equals("incrementalencoder")) {
            return incrementalEncoder;
        }
        if (name.equals("incrementaldecoder")) {
            return incrementalDecoder;
        }
        if (name.equals("name")) {
            return this.name;
        }
        return super.getAttr(name);
    }

    /** {@code <codecs.CodecInfo object for encoding utf-8 at 0x…>}, CPython's display. */
    @Override
    public String repr() {
        return String.format("<%s object for encoding %s at %#x>",
                getType().filedName(), Slots.str(name), System.identityHashCode(this));
    }
}
