package org.python.core;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The eight error handlers {@code _codecs} answers for, and the table a program writes into with
 * {@code register_error}.
 *
 * <p>None of the eight is written in Python. {@code codecs.py:1114} only asks this registry for
 * {@code "strict"} and its neighbours, once, at import time — the same call a program makes — and the
 * handler it gets back is the builtin function {@code _PyCodec_InitRegistry} filed (Python/codecs.c:1577).
 *
 * <p>Each is a function of one argument, the exception a codec raised, answering a two-tuple
 * {@code (replacement, new_position)}. Which of the three Unicode errors it was handed decides what it can
 * answer: {@code ignore} answers an empty text and the end of the run for all three, {@code replace} writes
 * {@code '?'} per character when encoding but one {@code U+FFFD} when decoding, because a run of octets has
 * no width to spend, and {@code backslashreplace} writes one escape per character or per octet. {@code
 * xmlcharrefreplace} and {@code namereplace} only make sense when encoding, so the other two shapes get the
 * sentence {@code don't know how to handle <type> in error callback} instead — and so does any exception
 * that is no Unicode error at all, and any value that is no exception. {@code strict} is the one with a
 * sentence of its own for that last case: {@code codec must pass exception instance}.
 *
 * <p>{@code surrogateescape} and {@code surrogatepass} are the pair only some codecs can carry. The first
 * turns the octets {@code 0x80..0xff} into the lone surrogates {@code 0xdc80..0xdcff} and back, at most
 * four of them per run and never across an ASCII octet; the second carries a real surrogate through the
 * three codecs written in code units — UTF-8, UTF-16 and UTF-32, and not their {@code -sig} spelling. Both
 * refuse a run they cannot spell by re-raising the exception itself, which is why the traceback of a
 * failed {@code surrogatepass} names the codec's own complaint and not a copy of it.
 *
 * <p>The registry is one table per process, and {@code register_error} files a name exactly as it was
 * given: {@code 'STRICT'} is a name of its own, and {@code lookup_error('STRICT')} is refused until
 * somebody registers it. A registration may shadow a built-in, so the built-ins are kept in a table of
 * their own rather than as defaults in one map: {@code _unregister_error} refuses every built-in name with
 * {@code ValueError} and leaves the shadow standing, answers {@code True} for a registration it removed,
 * and {@code False} for a name nobody registered.
 */
final class ErrorHandlers {

    private static final String STRICT_DOC =
            "Implements the 'strict' error handling, which raises a UnicodeError on coding errors.";

    private static final String IGNORE_DOC =
            "Implements the 'ignore' error handling, which ignores malformed data and continues.";

    private static final String REPLACE_DOC =
            "Implements the 'replace' error handling, which replaces malformed data with a replacement"
            + " marker.";

    private static final String XMLCHARREFREPLACE_DOC =
            "Implements the 'xmlcharrefreplace' error handling, which replaces an unencodable character"
            + " with the appropriate XML character reference.";

    private static final String BACKSLASHREPLACE_DOC =
            "Implements the 'backslashreplace' error handling, which replaces malformed data with a"
            + " backslashed escape sequence.";

    private static final String NAMEREPLACE_DOC =
            "Implements the 'namereplace' error handling, which replaces an unencodable character with a"
            + " \\N{...} escape sequence.";

    /** The eight, filed under the names a call names them by. */
    private static final Map<String, PyObject> BUILT_IN = new LinkedHashMap<>();

    /** What {@code register_error} filed: read before {@link #BUILT_IN}, so a name may shadow one. */
    private static final Map<String, PyObject> REGISTERED = new LinkedHashMap<>();

    static {
        BUILT_IN.put("strict",
                new PyNativeFunction("strict_errors", ErrorHandlers::strict).documented(STRICT_DOC));
        BUILT_IN.put("ignore",
                new PyNativeFunction("ignore_errors", ErrorHandlers::ignore).documented(IGNORE_DOC));
        BUILT_IN.put("replace",
                new PyNativeFunction("replace_errors", ErrorHandlers::replace).documented(REPLACE_DOC));
        BUILT_IN.put("xmlcharrefreplace",
                new PyNativeFunction("xmlcharrefreplace_errors", ErrorHandlers::xmlcharrefreplace)
                        .documented(XMLCHARREFREPLACE_DOC));
        BUILT_IN.put("backslashreplace",
                new PyNativeFunction("backslashreplace_errors", ErrorHandlers::backslashreplace)
                        .documented(BACKSLASHREPLACE_DOC));
        BUILT_IN.put("namereplace",
                new PyNativeFunction("namereplace_errors", ErrorHandlers::namereplace)
                        .documented(NAMEREPLACE_DOC));
        BUILT_IN.put("surrogateescape",
                new PyNativeFunction("surrogateescape", ErrorHandlers::surrogateescape));
        BUILT_IN.put("surrogatepass",
                new PyNativeFunction("surrogatepass", ErrorHandlers::surrogatepass));
    }

    private ErrorHandlers() {
    }

    /** {@code _codecs.lookup_error}: the registered handler, else the built-in, else a refusal. */
    static PyObject lookup(String name) {
        PyObject handler = REGISTERED.get(name);
        if (handler == null) {
            handler = BUILT_IN.get(name);
        }
        if (handler == null) {
            throw Slots.lookupError("unknown error handler name '" + Encoder.clipped(name) + "'");
        }
        return handler;
    }

    /** {@code _codecs.register_error}: file a callable under a name, shadowing a built-in if need be. */
    static void register(String name, PyObject handler) {
        if (!handler.isCallable()) {
            throw Slots.typeError("handler must be callable");
        }
        REGISTERED.put(name, handler);
    }

    /** {@code _codecs._unregister_error}: whether there was a registration to remove. */
    static boolean unregister(String name) {
        if (BUILT_IN.containsKey(name)) {
            throw Slots.valueError("cannot un-register built-in error handler '%s'", name);
        }
        return REGISTERED.remove(name) != null;
    }

    // --- the eight ---------------------------------------------------------------------------------

    private static PyObject strict(PyObject[] args, String[] kwNames) {
        single(args, kwNames, "strict_errors");
        if (!(args[0] instanceof PyBaseException exc)) {
            throw Slots.typeError("codec must pass exception instance");
        }
        throw PyRaise.reraise(exc);
    }

    private static PyObject ignore(PyObject[] args, String[] kwNames) {
        return answered(PyStr.of(""), unicode(args, kwNames, "ignore_errors"));
    }

    private static PyObject replace(PyObject[] args, String[] kwNames) {
        PyBaseException exc = unicode(args, kwNames, "replace_errors");
        long run = exc.memberNumber("end") - exc.memberNumber("start");
        return switch (direction(exc)) {
            case ENCODE -> answered(PyStr.of("?".repeat((int) run)), exc);
            case DECODE -> answered(PyStr.of("\uFFFD"), exc);
            case TRANSLATE -> answered(PyStr.of("\uFFFD".repeat((int) run)), exc);
        };
    }

    private static PyObject xmlcharrefreplace(PyObject[] args, String[] kwNames) {
        PyBaseException exc = unicode(args, kwNames, "xmlcharrefreplace_errors");
        if (direction(exc) != Direction.ENCODE) {
            throw unhandled(exc);
        }
        StringBuilder text = new StringBuilder();
        for (int codePoint : points(exc)) {
            text.append("&#").append(codePoint).append(';');
        }
        return answered(PyStr.of(text.toString()), exc);
    }

    private static PyObject backslashreplace(PyObject[] args, String[] kwNames) {
        PyBaseException exc = unicode(args, kwNames, "backslashreplace_errors");
        StringBuilder text = new StringBuilder();
        if (direction(exc) == Direction.DECODE) {
            byte[] data = ((PyBytes) exc.memberPointer("object")).getValue();
            for (long at = exc.memberNumber("start"); at < exc.memberNumber("end"); at++) {
                text.append(String.format("\\x%02x", data[(int) at] & 0xff));
            }
        } else {
            for (int codePoint : points(exc)) {
                text.append(Encoder.escapeOf(codePoint));
            }
        }
        return answered(PyStr.of(text.toString()), exc);
    }

    private static PyObject namereplace(PyObject[] args, String[] kwNames) {
        PyBaseException exc = unicode(args, kwNames, "namereplace_errors");
        if (direction(exc) != Direction.ENCODE) {
            throw unhandled(exc);
        }
        StringBuilder text = new StringBuilder();
        for (int codePoint : points(exc)) {
            String name = UnicodeNames.nameOf(codePoint);
            if (name == null) {
                text.append(Encoder.escapeOf(codePoint));
            } else {
                text.append("\\N{").append(name).append('}');
            }
        }
        return answered(PyStr.of(text.toString()), exc);
    }

    private static PyObject surrogateescape(PyObject[] args, String[] kwNames) {
        PyBaseException exc = unicode(args, kwNames, "surrogateescape");
        return switch (direction(exc)) {
            case ENCODE -> escapedOctets(exc);
            case DECODE -> escapedSurrogates(exc);
            case TRANSLATE -> throw unhandled(exc);
        };
    }

    private static PyObject surrogatepass(PyObject[] args, String[] kwNames) {
        PyBaseException exc = unicode(args, kwNames, "surrogatepass");
        return switch (direction(exc)) {
            case ENCODE -> passedUnits(exc);
            case DECODE -> passedCharacter(exc);
            case TRANSLATE -> throw unhandled(exc);
        };
    }

    // --- what the four non-obvious ones need -------------------------------------------------------

    /** {@code surrogateescape} encoding: one octet per {@code 0xdc80..0xdcff}, or nothing at all. */
    private static PyObject escapedOctets(PyBaseException exc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int codePoint : points(exc)) {
            if (codePoint < 0xDC80 || codePoint > 0xDCFF) {
                throw PyRaise.reraise(exc);
            }
            out.write(codePoint - 0xDC00);
        }
        return PyTuple.of(PyBytes.wrap(out.toByteArray()), PyInt.of(exc.memberNumber("end")));
    }

    /** {@code surrogateescape} decoding: four non-ASCII octets at most, and never across an ASCII one. */
    private static PyObject escapedSurrogates(PyBaseException exc) {
        byte[] data = ((PyBytes) exc.memberPointer("object")).getValue();
        long start = exc.memberNumber("start");
        long end = exc.memberNumber("end");
        StringBuilder text = new StringBuilder();
        for (long at = start; at < end && at - start < 4; at++) {
            int octet = data[(int) at] & 0xff;
            if (octet < 0x80) {
                break;
            }
            text.append((char) (0xDC00 + octet));
        }
        if (text.length() == 0) {
            throw PyRaise.reraise(exc);
        }
        return PyTuple.of(PyStr.of(text.toString()), PyInt.of(start + text.length()));
    }

    /** {@code surrogatepass} encoding: the code units the codec of the exception writes for the run. */
    private static PyObject passedUnits(PyBaseException exc) {
        PyBytes units = Encoder.surrogateUnits(
                run(exc), ((PyStr) exc.memberPointer("encoding")).getValue());
        if (units == null) {
            throw PyRaise.reraise(exc);
        }
        return PyTuple.of(units, PyInt.of(exc.memberNumber("end")));
    }

    /** {@code surrogatepass} decoding: the one character one code unit spells, if it is a surrogate. */
    private static PyObject passedCharacter(PyBaseException exc) {
        PyStr character = Encoder.surrogateCharacter(
                ((PyBytes) exc.memberPointer("object")).getValue(),
                ((PyStr) exc.memberPointer("encoding")).getValue(),
                exc.memberNumber("start"),
                exc.memberNumber("end"));
        if (character == null) {
            throw PyRaise.reraise(exc);
        }
        return PyTuple.of(character, PyInt.of(exc.memberNumber("end")));
    }

    // --- the plumbing every handler shares ---------------------------------------------------------

    /** One positional argument and no keywords, the way every builtin in the table is called. */
    private static void single(PyObject[] args, String[] kwNames, String name) {
        if (kwNames != null) {
            throw Slots.typeError("%s() takes no keyword arguments", name);
        }
        if (args.length != 1) {
            throw Slots.typeError("%s() takes exactly one argument (%d given)", name, args.length);
        }
    }

    /**
     * The exception a handler was handed, where it is one of the three the codecs raise.
     *
     * <p>Anything else — no exception at all, or an exception that has nothing to do with coding — is
     * answered in the registry's own words, naming its type the way {@code tp_name} prints it.
     */
    private static PyBaseException unicode(PyObject[] args, String[] kwNames, String name) {
        single(args, kwNames, name);
        if (args[0] instanceof PyBaseException exc && direction(exc) != null) {
            return exc;
        }
        throw unhandled(args[0]);
    }

    /** Which of the three shapes a handler was handed, or null where it is no Unicode error. */
    private static Direction direction(PyBaseException exc) {
        if (Classes.subclassOf(exc.getType(), ExceptionTypes.UNICODE_ENCODE_ERROR)) {
            return Direction.ENCODE;
        }
        if (Classes.subclassOf(exc.getType(), ExceptionTypes.UNICODE_DECODE_ERROR)) {
            return Direction.DECODE;
        }
        if (Classes.subclassOf(exc.getType(), ExceptionTypes.UNICODE_TRANSLATE_ERROR)) {
            return Direction.TRANSLATE;
        }
        return null;
    }

    private static PyRaise unhandled(PyBaseException exc) {
        return unhandled((PyObject) exc);
    }

    private static PyRaise unhandled(PyObject value) {
        return Slots.typeError(
                "don't know how to handle %s in error callback", value.getType().name());
    }

    /** {@code (replacement, end)}: every handler but the escaping pair answers the end of the run. */
    private static PyObject answered(PyObject replacement, PyBaseException exc) {
        return PyTuple.of(replacement, PyInt.of(exc.memberNumber("end")));
    }

    /** The characters of a text-shaped error's object, in the run the error complains about. */
    private static String run(PyBaseException exc) {
        String text = ((PyStr) exc.memberPointer("object")).getValue();
        int from = text.offsetByCodePoints(0, (int) exc.memberNumber("start"));
        int to = text.offsetByCodePoints(0, (int) exc.memberNumber("end"));
        return text.substring(from, to);
    }

    private static int[] points(PyBaseException exc) {
        return run(exc).codePoints().toArray();
    }

    /** Which of the three Unicode errors a handler answers for. */
    private enum Direction {
        ENCODE,
        DECODE,
        TRANSLATE
    }
}
