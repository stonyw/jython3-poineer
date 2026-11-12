package org.python.core;

/**
 * The {@code _io} module: the stream classes this runtime already had, gathered under the names the
 * reference files them with.
 *
 * <p>The classes themselves are older than this module — {@code PyStream}, {@code PyBufferedReader},
 * {@code PyFileIO} and the four bases answer the vocabulary — and {@code open} is the very function
 * {@code builtins} hands out, so the module is mostly wiring. {@code StringIO} and {@code BytesIO} are
 * the two new values, and {@code BytesIO} reports itself under the name {@code _io} gives it even though
 * the reference reaches the same type through {@code io}.
 *
 * <p>The two names nothing implements are filed as types anyway: {@code _BytesIOBuffer} is the private
 * buffer object behind {@code getbuffer()}, and {@code IncrementalNewlineDecoder} is used by codecs the
 * runtime does not run. Both refuse construction, which is a named deviation in the milestone notes.
 */
final class Io {

    private Io() {
    }

    /** {@code _io.__doc__}, which is {@code io.__doc__}: {@code io} is a Python wrapper around this. */
    private static final String DOC =
            "The io module provides the Python interfaces to stream handling. The\n"
                    + "builtin open function is defined in this module.\n"
                    + "\n"
                    + "At the top of the I/O hierarchy is the abstract base class IOBase. It\n"
                    + "defines the basic interface to a stream. Note, however, that there is no\n"
                    + "separation between reading and writing to streams; implementations are\n"
                    + "allowed to raise an OSError if they do not support a given operation.\n"
                    + "\n"
                    + "Extending IOBase is RawIOBase which deals simply with the reading and\n"
                    + "writing of raw bytes to a stream. FileIO subclasses RawIOBase to provide\n"
                    + "an interface to OS files.\n"
                    + "\n"
                    + "BufferedIOBase deals with buffering on a raw byte stream (RawIOBase). Its\n"
                    + "subclasses, BufferedWriter, BufferedReader, and BufferedRWPair buffer\n"
                    + "streams that are readable, writable, and both respectively.\n"
                    + "BufferedRandom provides a buffered interface to random access\n"
                    + "streams. BytesIO is a simple stream of in-memory bytes.\n"
                    + "\n"
                    + "Another IOBase subclass, TextIOBase, deals with the encoding and decoding\n"
                    + "of streams into text. TextIOWrapper, which extends it, is a buffered text\n"
                    + "interface to a buffered raw stream (`BufferedIOBase`). Finally, StringIO\n"
                    + "is an in-memory stream for text.\n"
                    + "\n"
                    + "Argument names are not part of the specification, and only the arguments\n"
                    + "of open() are intended to be used as keyword arguments.\n"
                    + "\n"
                    + "data:\n"
                    + "\n"
                    + "DEFAULT_BUFFER_SIZE\n"
                    + "\n"
                    + "   An int containing the default buffer size used by the module's buffered\n"
                    + "   I/O classes.\n";

    private static final String TEXT_ENCODING_DOC =
            "A helper function to choose the text encoding.\n"
                    + "\n"
                    + "When encoding is not None, this function returns it.\n"
                    + "Otherwise, this function returns the default text encoding\n"
                    + "(i.e. \"locale\" or \"utf-8\" depends on UTF-8 mode).\n"
                    + "\n"
                    + "This function emits an EncodingWarning if encoding is None and\n"
                    + "sys.flags.warn_default_encoding is true.\n"
                    + "\n"
                    + "This can be used in APIs with an encoding=None parameter.\n"
                    + "However, please consider using encoding=\"utf-8\" for new APIs.";

    private static final String OPEN_CODE_DOC =
            "Opens the provided file with the intent to import the contents.\n"
                    + "\n"
                    + "This may perform extra validation beyond open(), but is otherwise\n"
                    + "interchangeable with calling open(path, 'rb').";

    /** The rows, in the order {@code _io.__dict__} lists them in the reference. */
    static PyModule newModule() {
        PyModule module = new PyModule("_io");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_io");
        // `open` is the object `builtins` files; importing it from either name hands back one function.
        module.set("open", Open.function());
        module.set("text_encoding",
                new PyNativeFunction("text_encoding", Io::textEncoding).documented(TEXT_ENCODING_DOC));
        module.set("open_code",
                new PyNativeFunction("open_code", Io::openCode).documented(OPEN_CODE_DOC));
        module.set("DEFAULT_BUFFER_SIZE", PyInt.of(131072));
        module.set("UnsupportedOperation", ExceptionTypes.UNSUPPORTED_OPERATION);
        module.set("BlockingIOError", ExceptionTypes.BLOCKING_IO_ERROR);
        module.set("IncrementalNewlineDecoder", PyType.INCREMENTAL_NEWLINE_DECODER);
        module.set("_BytesIOBuffer", PyType.BYTES_IO_BUFFER);
        module.set("_IOBase", PyType.IO_BASE);
        module.set("_TextIOBase", PyType.TEXT_IO_BASE);
        module.set("_BufferedIOBase", PyType.BUFFERED_IO_BASE);
        module.set("_RawIOBase", PyType.RAW_IO_BASE);
        module.set("BytesIO", PyType.BYTES_IO);
        module.set("BufferedWriter", PyType.BUFFERED_WRITER);
        module.set("BufferedReader", PyType.BUFFERED_READER);
        module.set("BufferedRWPair", PyType.BUFFERED_RW_PAIR);
        module.set("BufferedRandom", PyType.BUFFERED_RANDOM);
        module.set("FileIO", PyType.FILE_IO);
        module.set("StringIO", PyType.STRING_IO);
        module.set("TextIOWrapper", PyStream.TYPE);
        return module;
    }

    /**
     * {@code text_encoding(encoding, stacklevel=2, /)}: the argument when it names an encoding, and
     * {@code 'utf-8'} when it is {@code None}.
     *
     * <p>The reference consults UTF-8 mode, which this runtime always has on, and warns through
     * {@code sys.flags.warn_default_encoding}, which is never true here — so the whole of the behaviour
     * is the choice between the two answers.
     */
    private static PyObject textEncoding(PyObject[] args, String[] kwNames) {
        Arguments.between("text_encoding", args, kwNames, 1, 2);
        PyObject encoding = args[0];
        return encoding == PyNone.INSTANCE ? PyStr.of("utf-8") : encoding;
    }

    /** {@code open_code(path)}: {@code open(path, 'rb')}, with the file object's own errors. */
    private static PyObject openCode(PyObject[] args, String[] kwNames) {
        if (kwNames == null || kwNames.length == 0) {
            if (args.length == 0) {
                throw Slots.typeError("open_code() missing required argument 'path' (pos 1)");
            }
            if (args.length > 1) {
                throw Slots.typeError(
                        "open_code() takes at most 1 argument (%d given)", args.length);
            }
        } else {
            Arguments.between("open_code", args, kwNames, 1, 1);
        }
        return Slots.call(
                Open.function(),
                new PyObject[] {args[0], PyStr.of("rb")},
                null);
    }
}
