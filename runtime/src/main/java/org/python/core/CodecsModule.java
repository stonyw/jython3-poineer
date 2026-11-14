package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code _codecs} module: the registry the {@code codecs} module is written on top of.
 *
 * <p>{@code codecs.py} begins with {@code from _codecs import *}, so every public name here becomes a
 * name in {@code codecs} as well. The four that matter are {@code encode}, {@code decode},
 * {@code lookup} and {@code register}; the per-codec functions ({@code utf_8_encode} and its
 * neighbours) are the last group, and the escape and charmap families are named gaps — nothing in the
 * measured stdlib reaches for them before the import machinery is complete.
 *
 * <p>{@code lookup} consults the built-in fourteen first and the registered search functions afterwards.
 * CPython consults the registry first and lets an entry override a built-in; the difference is only
 * visible to a program that registers a replacement for one of the fourteen, which is not a thing the
 * stdlib does.
 */
final class CodecsModule {

    private static final String DOC =
            "Codec registry and base classes.\n"
            + "\n"
            + "This module defines base classes for standard Python codecs (encoders and\n"
            + "decoders) and provides access to the internal Python codec registry.";

    /** The canonical names {@link Encoder} knows and the names {@code lookup} files them under. */
    private static final String[][] CODECS = {
        {"ascii", "ascii"},
        {"latin_1", "iso8859-1"},
        {"utf_8", "utf-8"},
        {"utf_8_sig", "utf-8-sig"},
        {"utf_16", "utf-16"},
        {"utf_16_le", "utf-16-le"},
        {"utf_16_be", "utf-16-be"},
        {"utf_32", "utf-32"},
        {"utf_32_le", "utf-32-le"},
        {"utf_32_be", "utf-32-be"},
        {"utf_7", "utf-7"},
        {"cp1252", "cp1252"},
        {"unicode_escape", "unicode-escape"},
        {"raw_unicode_escape", "raw-unicode-escape"}
    };

    /** The search functions {@code codecs.register} filed, most recent last. */
    private static final List<PyObject> SEARCHES = new ArrayList<>();

    private CodecsModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_codecs");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_codecs");

        module.set("encode", PyNativeFunction.positional("encode", args -> {
            Arguments.between("encode", args, null, 1, 3);
            String encoding = args.length > 1 ? Numbers.asText(args[1]) : "utf-8";
            String errors = args.length > 2 ? Numbers.asText(args[2]) : "strict";
            if (!(args[0] instanceof PyStr text)) {
                throw Slots.typeError("argument should be a str object, not '%s'",
                        args[0].getType().filedName());
            }
            return Encoder.encode(text.getValue(), encoding, errors);
        }));
        module.set("decode", PyNativeFunction.positional("decode", args -> {
            Arguments.between("decode", args, null, 1, 3);
            String encoding = args.length > 1 ? Numbers.asText(args[1]) : "utf-8";
            String errors = args.length > 2 ? Numbers.asText(args[2]) : "strict";
            return PyStr.of(Decoder.decode(octets(args[0]), encoding, errors));
        }));
        module.set("lookup", PyNativeFunction.positional("lookup", args -> {
            Arguments.exactlyOne("lookup", args, null);
            return lookup(Numbers.asText(args[0]));
        }));
        module.set("register", PyNativeFunction.positional("register", args -> {
            Arguments.exactlyOne("register", args, null);
            SEARCHES.add(args[0]);
            return PyNone.INSTANCE;
        }));
        module.set("unregister", PyNativeFunction.positional("unregister", args -> {
            Arguments.exactlyOne("unregister", args, null);
            SEARCHES.remove(args[0]);
            return PyNone.INSTANCE;
        }));
        module.set("register_error", new PyNativeFunction("register_error", (args, kwNames) -> {
            if (kwNames != null) {
                throw Slots.typeError("_codecs.register_error() takes no keyword arguments");
            }
            if (args.length != 2) {
                throw Slots.typeError("register_error expected 2 arguments, got %d", args.length);
            }
            ErrorHandlers.register(
                    name(args[0], "register_error() argument 1 must be str, not %s"), args[1]);
            return PyNone.INSTANCE;
        }));
        module.set("lookup_error", new PyNativeFunction("lookup_error", (args, kwNames) -> {
            if (kwNames != null) {
                throw Slots.typeError("_codecs.lookup_error() takes no keyword arguments");
            }
            if (args.length != 1) {
                throw Slots.typeError(
                        "_codecs.lookup_error() takes exactly one argument (%d given)", args.length);
            }
            return ErrorHandlers.lookup(
                    name(args[0], "lookup_error() argument must be str, not %s"));
        }));
        module.set("_unregister_error", new PyNativeFunction("_unregister_error", (args, kwNames) -> {
            if (kwNames != null) {
                throw Slots.typeError("_codecs._unregister_error() takes no keyword arguments");
            }
            if (args.length != 1) {
                throw Slots.typeError(
                        "_codecs._unregister_error() takes exactly one argument (%d given)",
                        args.length);
            }
            return PyBool.of(ErrorHandlers.unregister(
                    name(args[0], "_unregister_error() argument must be str, not %s")));
        }));

        for (String[] codec : CODECS) {
            addCodec(module, codec[0], codec[1]);
        }
        addExtendedDecode(module, "utf_16");
        addExtendedDecode(module, "utf_32");

        module.set("readbuffer_encode", PyNativeFunction.positional("readbuffer_encode", args -> {
            Arguments.between("readbuffer_encode", args, null, 1, 2);
            byte[] data = args[0] instanceof PyStr text
                    ? text.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : octets(args[0]);
            return PyTuple.of(PyBytes.wrap(data), PyInt.of(data.length));
        }));
        module.set("charmap_build", PyNativeFunction.positional("charmap_build", args -> {
            Arguments.exactlyOne("charmap_build", args, null);
            if (!(args[0] instanceof PyStr text)) {
                throw Slots.typeError("charmap_build() argument must be a string");
            }
            PyDict mapping = PyDict.empty();
            for (int at = 0; at < text.getValue().length(); at++) {
                String character = text.getValue().substring(at, at + 1);
                if (mapping.get(PyStr.of(character)) == null) {
                    mapping.put(PyStr.of(character), PyInt.of(at));
                }
            }
            return mapping;
        }));
        return module;
    }

    // --- the registry --------------------------------------------------------------------------------

    private static PyObject lookup(String name) {
        String canonical = null;
        try {
            canonical = Encoder.codec(name);
        } catch (PyRaise unknown) {
            // Not one of the fourteen: a search function may still know it, so fall through.
        }
        if (canonical != null) {
            for (String[] codec : CODECS) {
                if (codec[0].equals(canonical)) {
                    return codecInfo(codec[0], codec[1]);
                }
            }
        }
        for (int at = SEARCHES.size() - 1; at >= 0; at--) {
            PyObject answer = SEARCHES.get(at).call(new PyObject[] {PyStr.of(name)}, null);
            if (answer != PyNone.INSTANCE) {
                return asCodecInfo(answer);
            }
        }
        if (name.indexOf('.') >= 0) {
            return asCodecInfo(PyRuntime.importModule(name).getAttr("getregentry")
                    .call(new PyObject[0], null));
        }
        throw Slots.lookupError("unknown encoding: " + name);
    }

    /**
     * The text of an argument the registry requires to be a str, refused the way {@code PyArg_Parse}
     * refuses it — with the name of the type it was handed, and {@code None} rather than {@code
     * NoneType}, which is the {@code tp_name} that type files.
     */
    private static String name(PyObject value, String format) {
        if (!(value instanceof PyStr text)) {
            throw Slots.typeError(
                    format, value == PyNone.INSTANCE ? "None" : value.getType().name());
        }
        return text.getValue();
    }

    private static PyObject codecInfo(String canonical, String display) {
        PyObject encode = PyNativeFunction.positional(canonical + "_encode", args -> {
            Arguments.between(canonical + "_encode", args, null, 1, 2);
            String errors = args.length > 1 ? Numbers.asText(args[1]) : "strict";
            String text = Numbers.asText(args[0]);
            return PyTuple.of(
                    Encoder.encode(text, canonical, errors), PyInt.of(text.length()));
        });
        PyObject decode = PyNativeFunction.positional(canonical + "_decode", args -> {
            Arguments.between(canonical + "_decode", args, null, 1, 3);
            String errors = args.length > 1 ? Numbers.asText(args[1]) : "strict";
            byte[] data = octets(args[0]);
            return PyTuple.of(PyStr.of(Decoder.decode(data, canonical, errors)),
                    PyInt.of(data.length));
        });
        return PyCodecInfo.of(encode, decode, PyStr.of(display));
    }

    /** A search function may answer with a {@code CodecInfo} or with the items one is built from. */
    private static PyObject asCodecInfo(PyObject answer) {
        if (answer.getType() == PyType.TUPLE) {
            PyObject[] items = ((PyTuple) answer).getArray();
            if (items.length < 2) {
                throw Slots.typeError(
                        "codec search functions must return 4-tuples");
            }
            PyObject streamreader = items.length > 2 ? items[2] : PyNone.INSTANCE;
            PyObject streamwriter = items.length > 3 ? items[3] : PyNone.INSTANCE;
            PyObject name = items.length > 6 ? items[6] : PyNone.INSTANCE;
            return new PyCodecInfo(
                    new PyObject[] {items[0], items[1], streamreader, streamwriter},
                    items.length > 4 ? items[4] : PyNone.INSTANCE,
                    items.length > 5 ? items[5] : PyNone.INSTANCE,
                    name);
        }
        return answer;
    }

    // --- the per-codec functions ---------------------------------------------------------------------

    private static void addCodec(PyModule module, String canonical, String display) {
        module.set(canonical + "_encode", PyNativeFunction.positional(
                canonical + "_encode", args -> {
                    Arguments.between(canonical + "_encode", args, null, 1, 2);
                    String errors = args.length > 1 ? Numbers.asText(args[1]) : "strict";
                    if (!(args[0] instanceof PyStr text)) {
                        throw Slots.typeError(
                                "argument should be a str object, not '%s'",
                                args[0].getType().filedName());
                    }
                    return PyTuple.of(Encoder.encode(text.getValue(), canonical, errors),
                            PyInt.of(text.getValue().length()));
                }));
        module.set(canonical + "_decode", PyNativeFunction.positional(
                canonical + "_decode", args -> {
                    Arguments.between(canonical + "_decode", args, null, 1, 3);
                    String errors = args.length > 1 ? Numbers.asText(args[1]) : "strict";
                    byte[] data = octets(args[0]);
                    return PyTuple.of(PyStr.of(Decoder.decode(data, canonical, errors)),
                            PyInt.of(data.length));
                }));
    }

    /** {@code utf_16_ex_decode}: the text, how much was consumed, and the byte order it settled on. */
    private static void addExtendedDecode(PyModule module, String canonical) {
        module.set(canonical + "_ex_decode", PyNativeFunction.positional(
                canonical + "_ex_decode", args -> {
                    Arguments.between(canonical + "_ex_decode", args, null, 2, 4);
                    String errors = Numbers.asText(args[1]);
                    byte[] data = octets(args[0]);
                    int order = canonical.equals("utf_16") ? utf16Order(data) : utf32Order(data);
                    return PyTuple.of(PyStr.of(Decoder.decode(data, canonical, errors)),
                            PyInt.of(data.length), PyInt.of(order));
                }));
    }

    /** {@code -1} for little-endian, {@code 1} for big-endian, {@code 0} when no mark says either. */
    private static int utf16Order(byte[] data) {
        if (data.length >= 2) {
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
                return -1;
            }
            if ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
                return 1;
            }
        }
        return 0;
    }

    private static int utf32Order(byte[] data) {
        if (data.length >= 4) {
            if (data[0] == (byte) 0xFF && data[1] == (byte) 0xFE
                    && data[2] == 0 && data[3] == 0) {
                return -1;
            }
            if (data[0] == 0 && data[1] == 0 && data[2] == (byte) 0xFE
                    && data[3] == (byte) 0xFF) {
                return 1;
            }
        }
        return 0;
    }

    // --- reading a bytes argument --------------------------------------------------------------------

    private static byte[] octets(PyObject value) {
        if (value instanceof PyBytes || value instanceof PyByteArray) {
            return PyBytes.octets(value);
        }
        throw Slots.typeError("a bytes-like object is required, not '%s'",
                value.getType().filedName());
    }
}
