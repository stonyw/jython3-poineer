package org.python.core;

import java.util.Arrays;
import java.util.Set;

/**
 * {@code _io.BytesIO}: octets held in memory rather than in a descriptor.
 *
 * <p>There is no translation here, which is the whole difference from {@link PyStringIO}: every row
 * counts octets, a write replaces exactly as many as it brings, and seeking past the end and writing
 * fills the gap with NUL. The buffer never shrinks except through {@code truncate}, and truncation
 * never extends it.
 */
final class PyBytesIO extends PyIOStream {

    private static final Members METHODS = Members.of(PyType.BYTES_IO)
            .add("read", PyBytesIO::read)
            .add("read1", PyBytesIO::read)
            .add("readline", PyBytesIO::readline)
            .add("readlines", PyBytesIO::readlines)
            .add("write", PyBytesIO::write)
            .add("writelines", PyBytesIO::writelines)
            .add("seek", PyBytesIO::seek)
            .add("tell", PyBytesIO::tell)
            .add("truncate", PyBytesIO::truncate)
            .add("getvalue", PyBytesIO::getvalue)
            .add("isatty", PyBytesIO::isatty)
            .add("readable", PyBytesIO::readable)
            .add("writable", PyBytesIO::writable)
            .add("seekable", PyBytesIO::seekable)
            .add("flush", PyBytesIO::flush)
            .add("fileno", PyBytesIO::fileno)
            .add("close", (self, args, kwNames) -> ((PyBytesIO) self).closeRow(args, kwNames))
            .add("__enter__", (self, args, kwNames) -> ((PyBytesIO) self).enter(args, kwNames))
            .add("__exit__", (self, args, kwNames) -> ((PyBytesIO) self).exit(args))
            .add("__iter__", PyBytesIO::iterRow)
            .add("__next__", PyBytesIO::nextRow);

    private byte[] data = new byte[0];

    private int length;

    private int position;

    private boolean closed;

    private PyBytesIO(PyType type) {
        super(type);
    }

    /** {@code BytesIO(initial_bytes=b'')}, whose one argument may be any bytes-like object. */
    static PyObject create(PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords(
                "BytesIO", args, kwNames, new String[] {"initial_bytes"}, 0, 1);
        PyBytesIO stream = new PyBytesIO(PyType.BYTES_IO);
        if (given[0] != null) {
            byte[] initial = octetsOf(given[0]);
            stream.data = initial.clone();
            stream.length = initial.length;
        }
        return stream;
    }

    /** What the writes accept, and what a wrong one refuses with the reference's sentence. */
    private static byte[] octetsOf(PyObject value) {
        if (value instanceof PyBytes bytes) {
            return bytes.getValue();
        }
        if (value instanceof PyByteArray array) {
            return array.raw();
        }
        if (value instanceof PyMemoryView view) {
            return view.toBytes("C").getValue();
        }
        throw Slots.typeError(
                "a bytes-like object is required, not '%s'", value.getType().name());
    }

    @Override
    boolean closedFlag() {
        return closed;
    }

    @Override
    Set<String> notWritable() {
        return Set.of();
    }

    @Override
    Set<String> readOnly() {
        return Set.of("closed");
    }

    @Override
    public PyObject close() {
        closed = true;
        data = new byte[0];
        length = 0;
        position = 0;
        return PyNone.INSTANCE;
    }

    private PyObject closeRow(PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.close()", args, kwNames);
        return close();
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "closed" -> PyBool.of(closed);
            default -> super.getAttr(attribute);
        };
    }

    @Override
    public PyObject iterator() {
        return this;
    }

    @Override
    public PyObject nextOrNull() {
        checkOpen();
        return readLine(-1);
    }

    private static PyObject nextRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.__next__()", args, kwNames);
        return ((PyBytesIO) self).stepped();
    }

    private static PyObject iterRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.__iter__()", args, kwNames);
        ((PyBytesIO) self).checkOpen();
        return self;
    }

    private static PyObject read(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("read", args, kwNames, 1);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        long wanted = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        int start = Math.min(stream.position, stream.length);
        int end = wanted < 0
                ? stream.length
                : (int) Math.min((long) stream.length, start + wanted);
        byte[] answer = Arrays.copyOfRange(stream.data, start, end);
        stream.position = end;
        return PyBytes.wrap(answer);
    }

    private static PyObject readline(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("readline", args, kwNames, 1);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        long wanted = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        PyObject line = stream.readLine(wanted);
        return line == null ? PyBytes.wrap(new byte[0]) : line;
    }

    /** The line at the cursor, or null at the end: the shared half of {@code readline} and iterate. */
    private PyObject readLine(long wanted) {
        if (position >= length) {
            return null;
        }
        if (wanted == 0) {
            return PyBytes.wrap(new byte[0]);
        }
        int start = position;
        int end = start;
        while (end < length) {
            if (data[end] == '\n') {
                end++;
                break;
            }
            end++;
            if (wanted > 0 && end - start >= wanted) {
                break;
            }
        }
        position = end;
        return PyBytes.wrap(Arrays.copyOfRange(data, start, end));
    }

    private static PyObject readlines(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("readlines", args, kwNames, 1);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        long hint = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        PyList answer = PyList.empty();
        long total = 0;
        while (true) {
            PyObject line = stream.readLine(-1);
            if (line == null) {
                return answer;
            }
            answer.add(line);
            total += ((PyBytes) line).getValue().length;
            if (hint > 0 && total >= hint) {
                return answer;
            }
        }
    }

    private static PyObject write(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("BytesIO.write()", args, kwNames);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        byte[] written = octetsOf(args[0]);
        stream.ensure(stream.position + written.length);
        if (stream.position > stream.length) {
            Arrays.fill(stream.data, stream.length, stream.position, (byte) 0);
        }
        System.arraycopy(written, 0, stream.data, stream.position, written.length);
        stream.position += written.length;
        stream.length = Math.max(stream.length, stream.position);
        return PyInt.of(written.length);
    }

    /** Make room for {@code wanted} octets, which is the one place the array grows. */
    private void ensure(int wanted) {
        if (wanted <= data.length) {
            return;
        }
        data = Arrays.copyOf(data, Math.max(wanted, Math.max(16, data.length * 2)));
    }

    private static PyObject writelines(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("BytesIO.writelines()", args, kwNames);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        PyIterator lines = Slots.iterator(args[0]);
        PyObject line;
        while ((line = lines.nextOrNull()) != null) {
            write(stream, new PyObject[] {line}, null);
        }
        return PyNone.INSTANCE;
    }

    private static PyObject seek(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("BytesIO.seek()", kwNames);
        Arguments.between("BytesIO.seek", args, kwNames, 1, 2);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        long pos = Numbers.size(args[0]);
        long whence = args.length < 2 ? 0 : Numbers.size(args[1]);
        long base;
        switch ((int) whence) {
            case 0 -> base = 0;
            case 1 -> base = stream.position;
            case 2 -> base = stream.length;
            default -> throw Slots.valueError("whence must be 0, 1 or 2");
        }
        long target = base + pos;
        if (target < 0) {
            throw Slots.valueError("negative seek position %d", target);
        }
        stream.position = (int) target;
        return PyInt.of(stream.position);
    }

    private static PyObject tell(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.tell()", args, kwNames);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        return PyInt.of(stream.position);
    }

    private static PyObject truncate(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("truncate", args, kwNames, 1);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        long size = args.length == 0 ? stream.position : Numbers.optionalSize(args[0], stream.position);
        if (size < 0) {
            throw Slots.valueError("negative size");
        }
        if (size < stream.length) {
            stream.length = (int) size;
        }
        return PyInt.of(size);
    }

    private static PyObject getvalue(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.getvalue()", args, kwNames);
        PyBytesIO stream = (PyBytesIO) self;
        stream.checkOpen();
        return PyBytes.wrap(Arrays.copyOf(stream.data, stream.length));
    }

    private static PyObject isatty(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.isatty()", args, kwNames);
        ((PyBytesIO) self).checkOpen();
        return PyBool.FALSE;
    }

    private static PyObject readable(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.readable()", args, kwNames);
        ((PyBytesIO) self).checkOpen();
        return PyBool.TRUE;
    }

    private static PyObject writable(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.writable()", args, kwNames);
        ((PyBytesIO) self).checkOpen();
        return PyBool.TRUE;
    }

    private static PyObject seekable(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.seekable()", args, kwNames);
        ((PyBytesIO) self).checkOpen();
        return PyBool.TRUE;
    }

    private static PyObject flush(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.flush()", args, kwNames);
        ((PyBytesIO) self).checkOpen();
        return PyNone.INSTANCE;
    }

    private static PyObject fileno(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("BytesIO.fileno()", args, kwNames);
        throw Slots.unsupportedOperation("fileno");
    }

    @Override
    public String repr() {
        return "<"
                + getType().filedName()
                + " object at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ">";
    }
}
