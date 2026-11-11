package org.python.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;

/**
 * {@code sys.stdin.buffer.raw}: the descriptor itself, which is the one object of the three that owns a
 * closed flag and the only one that ever touches an octet.
 *
 * <p>{@code _io.FileIO} makes exactly one system call per {@code read}, which is what separates it from
 * the reader above it: {@code raw.read(8192)} over a pipe that has thirty-seven octets in it answers
 * thirty-seven, and a second call is needed to learn whether that was the end. A negative request is the
 * exception — {@code read(-1)} is {@code readall()}, which loops until a read comes back empty. This
 * runtime asks a Java {@link InputStream} rather than a descriptor, and the two agree on that shape: one
 * {@code read(byte[])} per request, however much of the array it fills.
 *
 * <p>The name is not a member of the class. {@code _io} files no {@code name} row here and puts the
 * string in the instance dictionary as part of constructing the object, which is why {@code raw.name =
 * 'x'} answers None, why {@code raw.__dict__} is {@code {'name': 'x'}} and why the two layers above read
 * a value a program may have rewritten — or deleted, which is the third answer: the repr of the
 * descriptor falls back to the number it was opened from, the two reprs above lose their name field, and
 * a read of the name reports the AttributeError of the object that owns it rather than of the one asked.
 *
 * <p>The refusals are the ones a descriptor that cannot write and cannot seek gives. {@code write} and
 * {@code truncate} answer {@code io.UnsupportedOperation} because the mode has no 'w' in it, and {@code
 * seek} and {@code tell} reach the operating system, which answers {@code ESPIPE} — errno 29, quoted in
 * the reference's own words since Java reports no errno of its own.
 *
 * <p>What a value is converted to, and when, is the other half of the face. Clinic runs first, so {@code
 * read('x')} and {@code seek(0, 'x')} are refused as types on a descriptor that has been closed, while
 * {@code read(-2)}, {@code seek('x')} and {@code truncate('x')} — whose parameters the body converts
 * late, or never converts at all — reach the descriptor and answer {@code I/O operation on closed file}.
 */
public final class PyFileIO extends PyIOStream {

    /** {@code io.DEFAULT_BUFFER_SIZE}: what one read of the descriptor is asked for at most. */
    private static final int MAX_READ = 1 << 16;

    /** The errno a pipe answers when it is asked for a position: {@code ESPIPE}. */
    private static final int ESPIPE = 29;

    private final InputStream in;
    private final int fd;

    private boolean closed;

    /** {@code closefd}, which is False for the three streams the interpreter builds itself. */
    private final boolean closefd;

    PyFileIO(String name, InputStream source, int fd, boolean closefd) {
        super(PyType.FILE_IO);
        this.in = source;
        this.fd = fd;
        this.closefd = closefd;
        // The constructor files the name in the dictionary rather than in a slot, which is where a read
        // of `raw.name` and every repr above this one look for it.
        attributeStore().put(PyStr.interned("name"), PyStr.of(name));
    }

    @Override
    boolean closedFlag() {
        return closed;
    }

    @Override
    Set<String> notWritable() {
        return Set.of("mode", "closed", "closefd", "_blksize");
    }

    @Override
    Set<String> readOnly() {
        return Set.of();
    }

    /**
     * One {@code read(2)}, cut to what one Java array can hold: the octets that came, and no more.
     *
     * <p>An empty answer is the end of the descriptor, which is what the loops here and the reader above
     * both read it as. A read that comes back short is not an error and is not filled in — that is the
     * whole contract of this layer — and a request of nothing asks the descriptor nothing.
     *
     * <p>This is the half of the traffic that stays in Java. The reference fills the store above it
     * through {@code raw.readinto(memoryview)}, and this runtime has no memoryview to hand over yet, so
     * the octets a {@code peek} or a {@code read1} needs arrive here directly rather than through a row a
     * program could rewrite. Every other question the two layers above ask goes through Python.
     */
    byte[] octets(long wanted) {
        checkGetter();
        if (wanted <= 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[(int) Math.min(wanted, MAX_READ)];
        try {
            int got = in.read(buffer);
            return got <= 0 ? new byte[0] : Arrays.copyOf(buffer, got);
        } catch (IOException refusal) {
            throw Slots.osError("reading error");
        }
    }

    /** {@code raw.read(n)}: one read of the descriptor, and {@code readall()} for anything negative. */
    private PyObject read(long wanted) {
        checkGetter();
        return wanted < 0 ? readAllValue() : PyBytes.of(octets(wanted));
    }

    /** {@code raw.readall()}, which is the loop {@code read(-1)} cannot avoid. */
    private PyObject readAllValue() {
        checkGetter();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        while (true) {
            byte[] chunk = octets(MAX_READ);
            if (chunk.length == 0) {
                return PyBytes.of(all.toByteArray());
            }
            all.write(chunk, 0, chunk.length);
        }
    }

    /**
     * {@code _IOBase.readline(hint)} for a stream that reads one octet at a time.
     *
     * <p>The base class has no store to scan, so the row asks this stream's own {@code read} — through
     * Python, which is why a closed descriptor is refused by {@code read}'s sentence rather than by this
     * row's, and only the first octet of whatever answers is kept. The hint counts octets, and a hint of
     * zero is not a request for nothing: the loop runs once and stops after the octet it read.
     */
    private PyObject readline(long hint) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        PyObject read = Slots.getAttr(this, "read");
        while (true) {
            byte[] answer = octetsOf(Slots.call(read, new PyObject[] {PyInt.ONE}, null));
            if (answer.length == 0) {
                return PyBytes.of(line.toByteArray());
            }
            byte one = answer[0];
            line.write(one);
            if (one == '\n' || (hint >= 0 && line.size() >= hint)) {
                return PyBytes.of(line.toByteArray());
            }
        }
    }

    /** The same row's own conversion and refusal order, with the base class's name in the sentence. */
    private PyObject readlineRow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("_IOBase.readline()", kwNames);
        Arguments.atMost("readline", args, kwNames, 1);
        return readline(args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1));
    }

    /** What the row that answers a {@code read} has to have answered. */
    private static byte[] octetsOf(PyObject answer) {
        if (!(answer instanceof PyBytes octets)) {
            throw Slots.osError("read() should return bytes");
        }
        return octets.getValue();
    }

    /**
     * {@code raw.write(data)}: the value is turned into a buffer before the descriptor is looked at, and
     * the descriptor is looked at before the mode is.
     */
    private PyObject write(PyObject data) {
        if (!(data instanceof PyBytes)) {
            // The buffer converter names the type that arrived without quoting it, which is `a bytes-like
            // object is required, not 'str'` with the quotes the message itself carries.
            throw Slots.typeError(
                    "a bytes-like object is required, not '%s'", Slots.argumentName(data));
        }
        checkGetter();
        throw Slots.unsupportedOperation("File not open for writing");
    }

    /**
     * {@code raw.close()}, which is where the flag every layer above this one reads gets set.
     *
     * <p>The base class's body runs first and the descriptor lets go afterwards: {@code RawIOBase.close}
     * flushes, files {@code __IOBase_closed} in the dictionary and answers None a second time, and only
     * then does this row drop the descriptor. Since {@code closefd} is False for the streams the
     * interpreter builds, the number survives in the repr while the flag says there is nothing behind it.
     */
    @Override
    public PyObject close() {
        PyObject answer = internalClose();
        closed = true;
        if (closefd) {
            try {
                in.close();
            } catch (IOException ignored) {
                // The answer is the Python one; a descriptor the JVM already lost cannot change it.
            }
        }
        return answer;
    }

    /**
     * {@code raw.seek(cookie, whence)}: the whence is converted by clinic and the position is not, which
     * is what decides the order of the three sentences below.
     *
     * <p>No whence is refused here: {@code lseek} is reached first and a pipe answers {@code ESPIPE}
     * whatever it was told to count from, so {@code seek(0, 9)} is the errno rather than the {@code whence
     * value 9 unsupported} the buffered reader above gives.
     */
    private PyObject seek(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("FileIO.seek()", kwNames);
        Arguments.between("seek", args, kwNames, 1, 2);
        if (args.length > 1) {
            Numbers.cInteger(args[1]);
        }
        checkGetter();
        Numbers.cLong(args[0]);
        throw illegalSeek();
    }

    private PyObject tell() {
        checkGetter();
        throw illegalSeek();
    }

    /** {@code ESPIPE} in the words the reference uses, since Java reports no errno of its own. */
    private static PyRaise illegalSeek() {
        return PyRaise.of(
                PyOSError.create(
                        ExceptionTypes.OS_ERROR,
                        new PyObject[] {PyInt.of(ESPIPE), PyStr.of("Illegal seek")}));
    }

    private PyObject seekable() {
        checkGetter();
        return PyBool.FALSE;
    }

    /** {@code fileno}: the descriptor number, which is the one thing here a Java runtime knows exactly. */
    private PyObject fileno() {
        checkGetter();
        if (fd < 0) {
            throw Slots.notImplemented("fileno() on a stream opened by open()");
        }
        return PyInt.of(fd);
    }

    private PyObject isatty() {
        checkGetter();
        // Java reports one console for the process; CPython asks this descriptor.
        return PyBool.of(fd >= 0 && System.console() != null);
    }

    private static final Members METHODS = Members.of(PyType.FILE_IO)
            // Clinic's `(self, size=-1, /)`: one positional-only parameter with a default, so nothing may
            // be named at all and an overshoot counts arguments rather than positionals.
            .add("read", (self, args, kwNames) ->
                    ((PyFileIO) self).read(sizeArgument("read", args, kwNames)))
            .addNoArgument("readall", self -> ((PyFileIO) self).readAllValue())
            .add("readinto", (self, args, kwNames) -> {
                Arguments.onlyPositional("readinto", args, kwNames, 1);
                throw Slots.typeError(
                        "readinto() argument 1 must be read-write bytes-like object, not %s",
                        Slots.argumentName(args[0]));
            })
            .add("readline", (self, args, kwNames) ->
                    ((PyFileIO) self).readlineRow(args, kwNames))
            .add("readlines", (self, args, kwNames) -> ((PyIOStream) self).readLines(args, kwNames))
            .add("write", (self, args, kwNames) -> {
                Arguments.onlyPositional("write", args, kwNames, 1);
                return ((PyFileIO) self).write(args[0]);
            })
            .add("writelines", (self, args, kwNames) -> ((PyIOStream) self).writeLines(args, kwNames))
            // Clinic hands this one an unconverted `object`: the closed and mode refusals come first, so
            // `truncate('x')` is the mode sentence rather than the type one the reference only reaches
            // on a descriptor opened for writing.
            .add("truncate", (self, args, kwNames) -> {
                Arguments.parameters(
                        "truncate",
                        args,
                        kwNames,
                        new String[] {"_"},
                        new PyObject[] {PyNone.INSTANCE},
                        0);
                ((PyFileIO) self).checkGetter();
                throw Slots.unsupportedOperation("File not open for writing");
            })
            .add("seek", (self, args, kwNames) -> ((PyFileIO) self).seek(args, kwNames))
            .addNoArgument("tell", self -> ((PyFileIO) self).tell())
            .addNoArgument("seekable", self -> ((PyFileIO) self).seekable())
            .addNoArgument("readable", self -> {
                ((PyFileIO) self).checkGetter();
                return PyBool.TRUE;
            })
            .addNoArgument("writable", self -> {
                ((PyFileIO) self).checkGetter();
                return PyBool.FALSE;
            })
            .addNoArgument("fileno", self -> ((PyFileIO) self).fileno())
            .addNoArgument("isatty", self -> ((PyFileIO) self).isatty())
            // A raw stream has nothing of its own to drain, and `flush` is the base class's row that says so.
            .add("flush", (self, args, kwNames) -> {
                Arguments.none("_IOBase.flush()", args, kwNames);
                ((PyIOStream) self).checkOpen();
                return PyNone.INSTANCE;
            })
            // The one refusal in `_io` that counts nothing: this `close` is reached through a `cls`
            // parameter, so neither the positionals nor the names belong to a table with bounds in it.
            .add("close", (self, args, kwNames) -> {
                if (args.length != 0 || kwNames != null) {
                    throw Slots.typeError("close() takes no arguments");
                }
                return ((PyFileIO) self).close();
            })
            .add("_checkClosed", (self, args, kwNames) ->
                    ((PyIOStream) self).checkClosedRow(args, kwNames))
            .add("_checkReadable", (self, args, kwNames) ->
                    ((PyIOStream) self).checked(
                            "_checkReadable", "not readable", args, kwNames,
                            () -> ask(self, "readable")))
            .add("_checkSeekable", (self, args, kwNames) ->
                    ((PyIOStream) self).checked(
                            "_checkSeekable", "not seekable", args, kwNames,
                            () -> ask(self, "seekable")))
            .add("_checkWritable", (self, args, kwNames) ->
                    ((PyIOStream) self).checked(
                            "_checkWritable", "not writable", args, kwNames,
                            () -> ask(self, "writable")))
            .add("_dealloc_warn", (self, args, kwNames) ->
                    ((PyIOStream) self).deallocWarn(args, kwNames))
            .add("__getstate__", (self, args, kwNames) -> ((PyIOStream) self).getState(args, kwNames))
            .add("__enter__", (self, args, kwNames) -> ((PyIOStream) self).enter(args, kwNames))
            .add("__exit__", (self, args, kwNames) -> ((PyIOStream) self).exit(args));

    /** {@code _PyIOBase_check_readable} and its two neighbours ask the stream's own row through Python. */
    private static PyObject ask(PyObject self, String row) {
        return Slots.call(Slots.getAttr(self, row), new PyObject[0], null);
    }

    /**
     * Clinic's {@code (self, size=-1, /)} slot read as a size: {@code None} is the default, a call that
     * named the parameter is refused because the slot cannot be named, and a value that is neither is
     * refused the way {@code getarg_psize} refuses it.
     */
    private static long sizeArgument(String name, PyObject[] args, String[] kwNames) {
        PyObject[] slots =
                Arguments.parameters(
                        name,
                        args,
                        kwNames,
                        new String[] {"_"},
                        new PyObject[] {PyNone.INSTANCE},
                        0);
        return slots[0] == null || slots[0] instanceof PyNone
                ? -1
                : Numbers.optionalSize(slots[0], -1);
    }

    /**
     * {@code iter(raw)}: the descriptor is its own cursor.
     *
     * <p>The iterator row runs the base class's closed guard, which is the sentence with the full stop —
     * unlike the {@code readline} it hands each line to the cursor, which refuses by its own name.
     */
    @Override
    public PyObject iterator() {
        checkOpen();
        return this;
    }

    @Override
    public PyObject nextOrNull() {
        PyObject line = ask(this, "readline");
        if (!(line instanceof PyBytes bytes) || bytes.getValue().length == 0) {
            return null;
        }
        return bytes;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "closed" -> PyBool.of(closed);
            case "mode" -> PyStr.of("rb");
            case "closefd" -> PyBool.of(closefd);
            // `_blksize` is the block size `fstat` answers for the descriptor, which Java reports no
            // equivalent of; the row is not filed here, so reading it is the AttributeError it would be
            // on a stream with no such row. See the slice note in PLAN-progress.md.
            default -> super.getAttr(attribute);
        };
    }

    /**
     * {@code repr(raw)}: the name out of the dictionary, or the descriptor number when there is no name
     * left to read.
     *
     * <p>The second form is what {@code del raw.name} leaves behind — the constructor files the name in
     * the dictionary rather than in a slot, so removing it removes the half of the repr that read it.
     */
    @Override
    public String repr() {
        if (closed) {
            return "<_io.FileIO [closed]>";
        }
        PyObject name = attributeStore().get(PyStr.interned("name"));
        return "<_io.FileIO "
                + (name == null ? "fd=" + fd : "name=" + Slots.repr(name))
                + " mode='rb' closefd="
                + (closefd ? "True" : "False")
                + ">";
    }
}
