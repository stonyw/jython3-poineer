package org.python.core;

import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 * The {@code open} builtin: {@code _io}'s argument layer over the stream classes this runtime already
 * has.
 *
 * <p>What the reference decides here, and the order it decides it in, is the part worth keeping. The
 * clinic converts {@code mode}, {@code buffering}, {@code encoding}, {@code errors}, {@code newline}
 * and {@code closefd} before the name is looked at at all, and a name that is neither an integer nor a
 * path is refused before the mode string is parsed. The mode string is then read a character at a
 * time, where a repeated flag is an {@code invalid mode} and a mix of {@code b} and {@code t} is
 * refused only after the whole string has been walked. {@code _io.FileIO} next converts the name —
 * which is where a number that is not an integer is refused — rejects an empty mode with the capital
 * sentence, refuses {@code closefd=False} with a file name, and hands {@code opener} the name and the
 * flags. Only once the file is open does a text stream refuse {@code buffering=0}, an illegal
 * {@code newline} and an unknown {@code encoding}, in that order.
 *
 * <p>Buffering is chosen the way {@code _io} chooses it: a text stream always sits on a
 * {@link PyBufferedReader} over a {@link PyFileIO}, and so does a binary one unless
 * {@code buffering=0} asked for the descriptor itself. A size above one is accepted and ignored — the
 * layer is the same, only the chunk the reference would read is larger.
 *
 * <p>Only reading is wired. {@code w}, {@code x}, {@code a} and {@code +} are refused by name, as are
 * a descriptor number, a bytes path and an opener. The reference opens all of those; the deviation is
 * recorded in {@code PLAN-progress.md} rather than half-built here.
 */
final class Open {

    /** The eight rows of the clinic signature, in the order a positional argument fills them. */
    private static final String[] PARAMETERS = {
        "file", "mode", "buffering", "encoding", "errors", "newline", "closefd", "opener"
    };

    private Open() {
    }

    static PyNativeFunction function() {
        return new PyNativeFunction("open", Open::open).documented(DOC).moduleNamed("_io");
    }

    private static PyObject open(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.unpackKeywords("open", args, kwNames, PARAMETERS, 1, 8);
        String mode = modeText(values[1]);
        int buffering = bufferingValue(values[2]);
        String encoding = optionalText("encoding", values[3]);
        String errors = optionalText("errors", values[4]);
        String newline = optionalText("newline", values[5]);
        boolean closefd = values[6] == null || Slots.truth(values[6]);
        PyObject opener = values[7];

        PyObject file = values[0];
        String path = null;
        boolean bytesPath = false;
        boolean descriptorNumber = false;
        boolean numeric = false;
        if (file instanceof PyStr text) {
            path = text.getValue();
        } else if (file instanceof PyBytes) {
            bytesPath = true;
        } else if (Numbers.isNumber(file)) {
            numeric = true;
            descriptorNumber = file instanceof PyInt || file instanceof PyBool;
        } else {
            throw pathTypeError(file);
        }

        Mode parsed = parseMode(mode);
        if (parsed.binary) {
            if (encoding != null) {
                throw Slots.valueError("binary mode doesn't take an encoding argument");
            }
            if (errors != null) {
                throw Slots.valueError("binary mode doesn't take an errors argument");
            }
            if (newline != null) {
                throw Slots.valueError("binary mode doesn't take a newline argument");
            }
        }
        if (numeric && !descriptorNumber) {
            throw pathTypeError(file);
        }
        if (parsed.count() == 0) {
            throw Slots.valueError(
                    "Must have exactly one of create/read/write/append mode and at most one plus");
        }
        if (descriptorNumber) {
            throw Slots.notImplemented("open() with a file descriptor");
        }
        if (bytesPath) {
            throw Slots.notImplemented("open() with a bytes path");
        }
        if (!closefd) {
            throw Slots.valueError("Cannot use closefd=False with file name");
        }
        if (opener != null && opener != PyNone.INSTANCE) {
            checkOpener(opener, path);
        }
        if (!parsed.readOnly()) {
            throw Slots.notImplemented("open() in mode '" + mode + "'");
        }

        PyFileIO raw = descriptor(path);
        if (parsed.binary) {
            return buffering == 0 ? raw : new PyBufferedReader(raw);
        }
        try {
            return text(raw, path, buffering, encoding, errors, newline, mode);
        } catch (RuntimeException failure) {
            raw.close();
            throw failure;
        }
    }

    private static String modeText(PyObject value) {
        if (value == null) {
            return "r";
        }
        if (value instanceof PyStr text) {
            return text.getValue();
        }
        throw Slots.typeError(
                "open() argument 'mode' must be str, not %s", Slots.argumentName(value));
    }

    /**
     * {@code buffering}, whose default is {@code -1} and whose range is a C {@code int}.
     *
     * <p>Every negative value means the default, so the sign is kept rather than clamped: the
     * caller only asks whether it is zero or one.
     */
    private static int bufferingValue(PyObject value) {
        if (value == null) {
            return -1;
        }
        return Numbers.cInteger(value);
    }

    private static String optionalText(String parameter, PyObject value) {
        if (value == null || value == PyNone.INSTANCE) {
            return null;
        }
        if (value instanceof PyStr text) {
            return text.getValue();
        }
        throw Slots.typeError(
                "open() argument '%s' must be str or None, not %s",
                parameter, Slots.argumentName(value));
    }

    private static PyRaise pathTypeError(PyObject file) {
        return Slots.typeError(
                "expected str, bytes or os.PathLike object, not %s", Slots.argumentName(file));
    }

    /**
     * {@code _io}'s {@code parse_mode}: the flags of a mode string, one character at a time.
     *
     * <p>The two checks after the loop are not in the order the sentences suggest. A string with a
     * repeated {@code b} is an {@code invalid mode} even when it also carries a {@code t}, because the
     * duplicate is met inside the loop and the text/binary clash is only asked about afterwards.
     */
    private static Mode parseMode(String mode) {
        Mode parsed = new Mode();
        for (int i = 0; i < mode.length(); i++) {
            switch (mode.charAt(i)) {
                case 'x' -> parsed.creating = once(parsed.creating, mode);
                case 'r' -> parsed.reading = once(parsed.reading, mode);
                case 'w' -> parsed.writing = once(parsed.writing, mode);
                case 'a' -> parsed.appending = once(parsed.appending, mode);
                case 'b' -> parsed.binary = once(parsed.binary, mode);
                case 't' -> parsed.text = once(parsed.text, mode);
                case '+' -> parsed.updating = once(parsed.updating, mode);
                default -> throw invalidMode(mode);
            }
        }
        if (parsed.text && parsed.binary) {
            throw Slots.valueError("can't have text and binary mode at once");
        }
        if (parsed.count() > 1) {
            throw Slots.valueError("must have exactly one of create/read/write/append mode");
        }
        return parsed;
    }

    private static boolean once(boolean seen, String mode) {
        if (seen) {
            throw invalidMode(mode);
        }
        return true;
    }

    private static PyRaise invalidMode(String mode) {
        return Slots.valueError("invalid mode: '%s'", mode);
    }

    /**
     * Calls {@code opener(name, flags)} the way {@code _io} does, as far as its two refusals go.
     *
     * <p>The descriptor it would return cannot be opened here, so a well-formed answer ends in a
     * named refusal after the two checks that are observable: a non-callable is
     * {@code 'int' object is not callable} and a non-integer is {@code expected integer from opener}.
     */
    private static void checkOpener(PyObject opener, String path) {
        if (!Slots.isCallable(opener)) {
            throw Slots.typeError("'%s' object is not callable", Slots.argumentName(opener));
        }
        PyObject answer = Slots.call(
                opener, new PyObject[] {PyStr.of(path), PyInt.of(0)}, null);
        if (!(answer instanceof PyInt) && !(answer instanceof PyBool)) {
            throw Slots.typeError("expected integer from opener");
        }
        throw Slots.notImplemented("open() with an opener");
    }

    /**
     * The {@link PyFileIO} under the read: a {@link FileInputStream} wearing the reference's three
     * layers of refusal.
     *
     * <p>The descriptor number is {@code -1}, because the JVM hands out no such number; the stream
     * still answers {@code name}, {@code mode} and {@code closefd} as a {@code FileIO} does.
     */
    private static PyFileIO descriptor(String path) {
        if (path.indexOf('\0') >= 0) {
            throw Slots.valueError("embedded null byte");
        }
        try {
            return new PyFileIO(path, new FileInputStream(path), -1, true);
        } catch (FileNotFoundException failure) {
            throw failureOf(failure, path);
        } catch (SecurityException failure) {
            throw osFailure(ExceptionTypes.PERMISSION_ERROR, 13, "Permission denied", path);
        }
    }

    private static PyRaise failureOf(FileNotFoundException failure, String path) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("Is a directory")) {
            return osFailure(ExceptionTypes.IS_A_DIRECTORY_ERROR, 21, "Is a directory", path);
        }
        if (message.contains("Not a directory")) {
            return osFailure(ExceptionTypes.NOT_A_DIRECTORY_ERROR, 20, "Not a directory", path);
        }
        if (message.contains("Permission denied")) {
            return osFailure(ExceptionTypes.PERMISSION_ERROR, 13, "Permission denied", path);
        }
        return osFailure(
                ExceptionTypes.FILE_NOT_FOUND_ERROR, 2, "No such file or directory", path);
    }

    private static PyRaise osFailure(PyType kind, int number, String text, String path) {
        return PyRaise.of(PyOSError.create(
                kind, new PyObject[] {PyInt.of(number), PyStr.of(text), PyStr.of(path)}));
    }

    /**
     * The text layer, and the three refusals {@code _io} saves for after the file is open.
     *
     * <p>{@code buffering=0} comes first, then an illegal {@code newline}, then an unknown
     * {@code encoding} — the order {@code _io.open} and {@code TextIOWrapper.__init__} run in, which is
     * why opening a missing file with a bad codec reports the missing file.
     */
    private static PyStream text(PyFileIO raw, String path, int buffering, String encoding,
            String errors, String newline, String mode) {
        if (buffering == 0) {
            throw Slots.valueError("can't have unbuffered text I/O");
        }
        checkNewline(newline);
        String spelling = encoding == null
                ? "utf-8"
                : encoding.equals("locale") ? "UTF-8" : encoding;
        return new PyStream(
                path,
                new PyBufferedReader(raw),
                -1,
                spelling,
                mode,
                errors == null ? "strict" : errors,
                newline,
                buffering == 1);
    }

    private static void checkNewline(String newline) {
        if (newline == null || newline.isEmpty() || newline.equals("\n")
                || newline.equals("\r") || newline.equals("\r\n")) {
            return;
        }
        throw Slots.valueError("illegal newline value: %s", newline);
    }

    /** The flags {@code parse_mode} filled in. */
    private static final class Mode {
        boolean creating;
        boolean reading;
        boolean writing;
        boolean appending;
        boolean updating;
        boolean binary;
        boolean text;

        int count() {
            return (creating ? 1 : 0) + (reading ? 1 : 0) + (writing ? 1 : 0) + (appending ? 1 : 0);
        }

        boolean readOnly() {
            return !creating && !writing && !appending && !updating;
        }
    }

    private static final String DOC =
            "open($module, /, file, mode='r', buffering=-1, encoding=None,\n"
            +             "     errors=None, newline=None, closefd=True, opener=None)\n--\n\n"
            +             "Open file and return a stream.  Raise OSError upon failure.\n\n"
            +             "file is either a text or byte string giving the name (and the path\n"
            +             "if the file isn't in the current working directory) of the file to\n"
            +             "be opened or an integer file descriptor of the file to be\n"
            +             "wrapped.  (If a file descriptor is given, it is closed when the\n"
            +             "returned I/O object is closed, unless closefd is set to False.)\n\n"
            +             "mode is an optional string that specifies the mode in which the file\n"
            +             "is opened.  It defaults to 'r' which means open for reading in text\n"
            +             "mode.  Other common values are 'w' for writing (truncating the file if\n"
            +             "it already exists), 'x' for creating and writing to a new file, and\n"
            +             "'a' for appending (which on some Unix systems, means that all writes\n"
            +             "append to the end of the file regardless of the current seek position).\n"
            +             "In text mode, if encoding is not specified the encoding used is platform\n"
            +             "dependent: locale.getencoding() is called to get the current locale\n"
            +             "encoding.  (For reading and writing raw bytes use binary mode and leave\n"
            +             "encoding unspecified.)  The available modes are:\n\n"
            +             "========= ==========================================================\nCharacter Meaning\n"
            +             "--------- ----------------------------------------------------------\n"
            +             "'r'       open for reading (default)\n'w'       open for writing, truncating the file first\n"
            +             "'x'       create a new file and open it for writing\n"
            +             "'a'       open for writing, appending to the end of the file if it\n          exists\n"
            +             "'b'       binary mode\n't'       text mode (default)\n"
            +             "'+'       open a disk file for updating (reading and writing)\n"
            +             "========= ==========================================================\n\n"
            +             "The default mode is 'rt' (open for reading text).  For binary random\n"
            +             "access, the mode 'w+b' opens and truncates the file to 0 bytes, while\n"
            +             "'r+b' opens the file without truncation.  The 'x' mode implies 'w' and\n"
            +             "raises an `FileExistsError` if the file already exists.\n\n"
            +             "Python distinguishes between files opened in binary and text modes,\n"
            +             "even when the underlying operating system doesn't.  Files opened in\n"
            +             "binary mode (appending 'b' to the mode argument) return contents as\n"
            +             "bytes objects without any decoding.  In text mode (the default, or when\n"
            +             "'t' is appended to the mode argument), the contents of the file are\n"
            +             "returned as strings, the bytes having been first decoded using a\n"
            +             "platform-dependent encoding or using the specified encoding if given.\n\n"
            +             "buffering is an optional integer used to set the buffering policy.\n"
            +             "Pass 0 to switch buffering off (only allowed in binary mode), 1 to\n"
            +             "select line buffering (only usable in text mode), and an integer > 1 to\n"
            +             "indicate the size of a fixed-size chunk buffer.   When no buffering\n"
            +             "argument is given, the default buffering policy works as follows:\n\n"
            +             "* Binary files are buffered in fixed-size chunks; the size of the buffer\n"
            +             "  is max(min(blocksize, 8 MiB), DEFAULT_BUFFER_SIZE) when the device\n"
            +             "  block size is available.\n"
            +             "  On most systems, the buffer will typically be 128 kilobytes long.\n\n"
            +             "* \"Interactive\" text files (files for which isatty() returns True)\n"
            +             "  use line buffering.  Other text files use the policy described above\n  for binary files.\n\n"
            +             "encoding is the name of the encoding used to decode or encode the\n"
            +             "file.  This should only be used in text mode.  The default encoding is\n"
            +             "platform dependent, but any encoding supported by Python can be\n"
            +             "passed.  See the codecs module for the list of supported encodings.\n\n"
            +             "errors is an optional string that specifies how encoding errors are to\n"
            +             "be handled---this argument should not be used in binary mode.  Pass\n"
            +             "'strict' to raise a ValueError exception if there is an encoding error\n"
            +             "(the default of None has the same effect), or pass 'ignore' to ignore\n"
            +             "errors.  (Note that ignoring encoding errors can lead to data loss.)\n"
            +             "See the documentation for codecs.register or run 'help(codecs.Codec)'\n"
            +             "for a list of the permitted encoding error strings.\n\n"
            +             "newline controls how universal newlines works (it only applies to text\n"
            +             "mode).  It can be None, '', '\\n', '\\r', and '\\r\\n'.  It works as\nfollows:\n\n"
            +             "* On input, if newline is None, universal newlines mode is enabled.\n"
            +             "  Lines in the input can end in '\\n', '\\r', or '\\r\\n', and these are\n"
            +             "  translated into '\\n' before being returned to the caller.  If it is\n"
            +             "  '', universal newline mode is enabled, but line endings are returned\n"
            +             "  to the caller untranslated.  If it has any of the other legal values,\n"
            +             "  input lines are only terminated by the given string, and the line\n"
            +             "  ending is returned to the caller untranslated.\n\n"
            +             "* On output, if newline is None, any '\\n' characters written are\n"
            +             "  translated to the system default line separator, os.linesep.  If\n"
            +             "  newline is '' or '\\n', no translation takes place.  If newline is any\n"
            +             "  of the other legal values, any '\\n' characters written are translated\n"
            +             "  to the given string.\n\n"
            +             "If closefd is False, the underlying file descriptor will be kept open\n"
            +             "when the file is closed.  This does not work when a file name is given\n"
            +             "and must be True in that case.\n\n"
            +             "A custom opener can be used by passing a callable as *opener*.  The\n"
            +             "underlying file descriptor for the file object is then obtained by\n"
            +             "calling *opener* with (*file*, *flags*).  *opener* must return an open\n"
            +             "file descriptor (passing os.open as *opener* results in functionality\n"
            +             "similar to passing None).\n\nopen() returns a file object whose type depends on the mode, and\n"
            +             "through which the standard file operations such as reading and writing\n"
            +             "are performed.  When open() is used to open a file in a text mode ('w',\n"
            +             "'r', 'wt', 'rt', etc.), it returns a TextIOWrapper.  When used to open\n"
            +             "a file in a binary mode, the returned class varies: in read binary\n"
            +             "mode, it returns a BufferedReader; in write binary and append binary\n"
            +             "modes, it returns a BufferedWriter, and in read/write mode, it returns\na BufferedRandom.\n\n"
            +             "It is also possible to use a string or bytearray as a file for both\n"
            +             "reading and writing.  For strings StringIO can be used like a file\n"
            +             "opened in a text mode, and for bytes a BytesIO can be used like a file\n"
            +             "opened in a binary mode.";
}
