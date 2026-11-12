package org.python.core;

import java.util.Set;

/**
 * {@code _io.StringIO}: text held in memory rather than in a descriptor.
 *
 * <p>The buffer is kept already translated. {@code newline} decides that translation once, in the
 * constructor, and every write then goes through the same table — which is what makes {@code
 * getvalue()} of a stream built with {@code newline=None} answer the universal-newline form rather
 * than what was written. {@code newline=''} is the one mode that keeps the octets it was given and
 * only records which line endings it has seen.
 *
 * <p>The recorded endings are the {@code newlines} attribute, and they are collected from the
 * untranslated text so that a universal stream still reports {@code '\r\n'} rather than {@code '\n'}.
 * The tuple's order is fixed — carriage return, line feed, then the pair — because that is the order
 * CPython's scanner appends them in, not the order they were met.
 */
final class PyStringIO extends PyIOStream {

    /** The three endings {@code newlines} can report, in the order the reference lists them. */
    private static final int CR = 1;
    private static final int LF = 2;
    private static final int CRLF = 4;

    private static final Members METHODS = Members.of(PyType.STRING_IO)
            .add("read", PyStringIO::read)
            .add("readline", PyStringIO::readline)
            .add("readlines", PyStringIO::readlines)
            .add("write", PyStringIO::write)
            .add("writelines", PyStringIO::writelines)
            .add("seek", PyStringIO::seek)
            .add("tell", PyStringIO::tell)
            .add("truncate", PyStringIO::truncate)
            .add("getvalue", PyStringIO::getvalue)
            .add("isatty", PyStringIO::isatty)
            .add("readable", PyStringIO::readable)
            .add("writable", PyStringIO::writable)
            .add("seekable", PyStringIO::seekable)
            .add("flush", PyStringIO::flush)
            .add("fileno", PyStringIO::fileno)
            .add("close", (self, args, kwNames) -> ((PyStringIO) self).closeRow(args, kwNames))
            .add("__enter__", (self, args, kwNames) -> ((PyStringIO) self).enter(args, kwNames))
            .add("__exit__", (self, args, kwNames) -> ((PyStringIO) self).exit(args))
            .add("__iter__", PyStringIO::iterRow)
            .add("__next__", PyStringIO::nextRow);

    private final StringBuilder buffer = new StringBuilder();

    private int position;

    private boolean closed;

    /** The newline the stream translates to: {@code "\n"}, {@code ""}, {@code null} or a spelling. */
    private final String newline;

    /** Which of {@link #CR}, {@link #LF} and {@link #CRLF} the untranslated text has held. */
    private int endings;

    private PyStringIO(PyType type, String initial, String newline) {
        super(type);
        this.newline = newline;
        if (initial != null && !initial.isEmpty()) {
            if (newline == null || newline.isEmpty()) {
                track(initial);
            }
            buffer.append(translate(initial));
        }
    }

    /** {@code StringIO(initial_value='', newline='\n')}, whose two arguments are both optional. */
    static PyObject create(PyObject[] args, String[] kwNames) {
        PyObject[] given = Arguments.unpackKeywords(
                "StringIO", args, kwNames, new String[] {"initial_value", "newline"}, 0, 2);
        String initial = "";
        if (given[0] != null && given[0] != PyNone.INSTANCE) {
            if (!(given[0] instanceof PyStr text)) {
                throw Slots.typeError(
                        "initial_value must be str or None, not %s", given[0].getType().name());
            }
            initial = text.getValue();
        }
        String newline = "\n";
        if (given[1] != null) {
            if (given[1] == PyNone.INSTANCE) {
                newline = null;
            } else if (!(given[1] instanceof PyStr spelling)) {
                throw Slots.typeError(
                        "newline must be str or None, not %s", given[1].getType().name());
            } else {
                newline = spelling.getValue();
                if (!newline.equals("\n")
                        && !newline.isEmpty()
                        && !newline.equals("\r")
                        && !newline.equals("\r\n")) {
                    throw Slots.valueError("illegal newline value: %s", Slots.repr(given[1]));
                }
            }
        }
        return new PyStringIO(PyType.STRING_IO, initial, newline);
    }

    /** The one table the three translation modes answer through. */
    private String translate(String text) {
        if (newline == null) {
            return text.replace("\r\n", "\n").replace('\r', '\n');
        }
        if (newline.isEmpty() || newline.equals("\n")) {
            return text;
        }
        return text.replace("\n", newline);
    }

    /** Record which endings the text held before it was translated, for {@code newlines}. */
    private void track(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    endings |= CRLF;
                    i++;
                } else {
                    endings |= CR;
                }
            } else if (c == '\n') {
                endings |= LF;
            }
        }
    }

    /** {@code newlines}: None, the one ending, or the tuple of them in the reference's order. */
    private PyObject newlinesObject() {
        if (endings == 0) {
            return PyNone.INSTANCE;
        }
        if (endings == CR) {
            return PyStr.of("\r");
        }
        if (endings == LF) {
            return PyStr.of("\n");
        }
        if (endings == CRLF) {
            return PyStr.of("\r\n");
        }
        PyObject[] found = new PyObject[Integer.bitCount(endings)];
        int at = 0;
        if ((endings & CR) != 0) {
            found[at++] = PyStr.of("\r");
        }
        if ((endings & LF) != 0) {
            found[at++] = PyStr.of("\n");
        }
        if ((endings & CRLF) != 0) {
            found[at] = PyStr.of("\r\n");
        }
        return PyTuple.of(found);
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
        return Set.of("closed", "newlines", "line_buffering");
    }

    @Override
    public PyObject close() {
        closed = true;
        return PyNone.INSTANCE;
    }

    private PyObject closeRow(PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.close()", args, kwNames);
        return close();
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "closed" -> PyBool.of(closed);
            case "newlines" -> {
                checkGetter();
                yield newlinesObject();
            }
            case "line_buffering" -> PyBool.FALSE;
            default -> super.getAttr(attribute);
        };
    }

    @Override
    public PyObject iterator() {
        checkOpen();
        return this;
    }

    @Override
    public PyObject nextOrNull() {
        checkGetter();
        return readLine(-1);
    }

    private static PyObject nextRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.__next__()", args, kwNames);
        return ((PyStringIO) self).stepped();
    }

    private static PyObject iterRow(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.__iter__()", args, kwNames);
        ((PyStringIO) self).checkOpen();
        return self;
    }

    private static PyObject read(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("read", args, kwNames, 1);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        long wanted = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        int start = Math.min(stream.position, stream.buffer.length());
        int end = wanted < 0
                ? stream.buffer.length()
                : (int) Math.min((long) stream.buffer.length(), start + wanted);
        String answer = stream.buffer.substring(start, end);
        stream.position = end;
        return PyStr.of(answer);
    }

    private static PyObject readline(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("readline", args, kwNames, 1);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        long wanted = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        PyObject line = stream.readLine(wanted);
        return line == null ? PyStr.of("") : line;
    }

    /** The line at the cursor, or null at the end: the shared half of {@code readline} and iterate. */
    private PyObject readLine(long wanted) {
        if (position >= buffer.length()) {
            return null;
        }
        if (wanted == 0) {
            return PyStr.of("");
        }
        int start = position;
        int limit = buffer.length();
        int end = start;
        while (end < limit) {
            if (buffer.charAt(end) == '\n') {
                end++;
                break;
            }
            end++;
            if (wanted > 0 && end - start >= wanted) {
                break;
            }
        }
        position = end;
        return PyStr.of(buffer.substring(start, end));
    }

    private static PyObject readlines(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("readlines", args, kwNames, 1);
        PyStringIO stream = (PyStringIO) self;
        stream.checkOpen();
        long hint = args.length == 0 ? -1 : Numbers.optionalSize(args[0], -1);
        PyList answer = PyList.empty();
        long length = 0;
        while (true) {
            PyObject line = stream.readLine(-1);
            if (line == null) {
                return answer;
            }
            answer.add(line);
            length += ((PyStr) line).getValue().length();
            if (hint > 0 && length > hint) {
                return answer;
            }
        }
    }

    private static PyObject write(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("StringIO.write()", args, kwNames);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        if (!(args[0] instanceof PyStr text)) {
            throw Slots.typeError(
                    "string argument expected, got '%s'", args[0].getType().name());
        }
        String raw = text.getValue();
        if (stream.newline == null || stream.newline.isEmpty()) {
            stream.track(raw);
        }
        String written = stream.translate(raw);
        if (stream.position > stream.buffer.length()) {
            while (stream.buffer.length() < stream.position) {
                stream.buffer.append('\0');
            }
            stream.buffer.append(written);
        } else {
            int end = stream.position + written.length();
            if (end <= stream.buffer.length()) {
                stream.buffer.replace(stream.position, end, written);
            } else {
                stream.buffer.replace(stream.position, stream.buffer.length(), written);
            }
        }
        stream.position += written.length();
        return PyInt.of(written.length());
    }

    private static PyObject writelines(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("StringIO.writelines()", args, kwNames);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        PyIterator lines = Slots.iterator(args[0]);
        PyObject line;
        while ((line = lines.nextOrNull()) != null) {
            write(stream, new PyObject[] {line}, null);
        }
        return PyNone.INSTANCE;
    }

    private static PyObject seek(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("StringIO.seek()", kwNames);
        Arguments.between("StringIO.seek", args, kwNames, 1, 2);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        long pos = Numbers.size(args[0]);
        long whence = args.length < 2 ? 0 : Numbers.size(args[1]);
        long base;
        switch ((int) whence) {
            case 0 -> base = 0;
            case 1 -> base = stream.position;
            case 2 -> base = stream.buffer.length();
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
        Arguments.none("StringIO.tell()", args, kwNames);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        return PyInt.of(stream.position);
    }

    private static PyObject truncate(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.atMost("truncate", args, kwNames, 1);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        long size = args.length == 0 ? stream.position : Numbers.optionalSize(args[0], stream.position);
        if (size < 0) {
            throw Slots.valueError("negative size");
        }
        if (size < stream.buffer.length()) {
            stream.buffer.setLength((int) size);
        }
        return PyInt.of(size);
    }

    private static PyObject getvalue(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.getvalue()", args, kwNames);
        PyStringIO stream = (PyStringIO) self;
        stream.checkGetter();
        return PyStr.of(stream.buffer.toString());
    }

    private static PyObject isatty(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.isatty()", args, kwNames);
        ((PyStringIO) self).checkOpen();
        return PyBool.FALSE;
    }

    private static PyObject readable(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.readable()", args, kwNames);
        ((PyStringIO) self).checkGetter();
        return PyBool.TRUE;
    }

    private static PyObject writable(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.writable()", args, kwNames);
        ((PyStringIO) self).checkGetter();
        return PyBool.TRUE;
    }

    private static PyObject seekable(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.seekable()", args, kwNames);
        ((PyStringIO) self).checkGetter();
        return PyBool.TRUE;
    }

    private static PyObject flush(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.flush()", args, kwNames);
        return PyNone.INSTANCE;
    }

    private static PyObject fileno(PyObject self, PyObject[] args, String[] kwNames) {
        Arguments.none("StringIO.fileno()", args, kwNames);
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
