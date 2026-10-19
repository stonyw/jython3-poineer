package org.python.core;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code sys.stdout} and {@code sys.stderr}: a text stream over a Java file descriptor.
 *
 * CPython builds these in {@code _io}, and only their being reachable from {@code print} and from
 * {@code sys} matters here. What does matter is that both paths write through this one object: a
 * script that interleaves {@code print} with {@code sys.stdout.write} has to see one buffer, not two
 * that flush at different times.
 *
 * <p>The stream re-encodes as UTF-8 whatever it is given, which is why {@code encoding} reads
 * {@code 'utf-8'} rather than echoing the JVM's {@code stdout.encoding}. The two agree under the
 * build's {@code -Dfile.encoding=UTF-8}, and disagreeing is visible rather than silent.
 */
public final class PyStream extends PyObject {

    static final PyType TYPE = PyType.builtin("TextIOWrapper");

    private final String name;
    private final PrintStream out;
    private final String errors;
    private final boolean lineBuffered;
    private final int fd;

    private boolean closed;

    /**
     * @param autoFlush whether the descriptor is drained on every newline, which is what a terminal
     *     gets and a pipe does not
     */
    PyStream(String name, PrintStream target, int fd, String errors, boolean autoFlush) {
        super(TYPE);
        this.name = name;
        this.out = new PrintStream(target, autoFlush, StandardCharsets.UTF_8);
        this.errors = errors;
        this.lineBuffered = autoFlush;
        this.fd = fd;
    }

    /** Write from Java, the way {@code print} does, without going through the {@code write} slot. */
    public void writeText(String text) {
        if (closed) {
            throw Slots.valueError("I/O operation on closed file.");
        }
        out.print(text);
    }

    public void flush() {
        out.flush();
    }

    /** {@code s.write(text)}, which answers with how many characters it took. */
    private PyObject write(PyObject text) {
        String value = Numbers.asText(text);
        if (value == null) {
            throw Slots.typeError(
                    "write() argument must be str, not %s", text.getType().name());
        }
        writeText(value);
        return PyInt.of(value.codePointCount(0, value.length()));
    }

    private static final Members METHODS = Members.of(TYPE)
            .addOne("write", (self, text) -> ((PyStream) self).write(text))
            .addOne("writelines", (self, lines) -> {
                PyIterator iterator = Slots.iterator(lines);
                PyObject line;
                while ((line = iterator.nextOrNull()) != null) {
                    ((PyStream) self).write(line);
                }
                return PyNone.INSTANCE;
            })
            .addNoArgument("flush", self -> {
                ((PyStream) self).flush();
                return PyNone.INSTANCE;
            })
            .addNoArgument("close", self -> {
                ((PyStream) self).closed = true;
                ((PyStream) self).flush();
                return PyNone.INSTANCE;
            })
            .addNoArgument("isatty", self -> PyBool.of(self instanceof PyStream s && s.isTty()))
            .addNoArgument("writable", self -> PyBool.of(!((PyStream) self).closed))
            .addNoArgument("readable", self -> PyBool.FALSE);

    private boolean isTty() {
        // Java reports one console for the process; CPython asks each descriptor. Which descriptor is
        // attached to a terminal is M4's problem, along with the REPL that would notice.
        return System.console() != null;
    }

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "encoding" -> PyStr.of("utf-8");
            case "errors" -> PyStr.of(errors);
            case "name" -> PyStr.of(name);
            case "mode" -> PyStr.of("w");
            case "closed" -> PyBool.of(closed);
            case "line_buffering" -> PyBool.of(lineBuffered);
            case "write_through" -> PyBool.FALSE;
            case "fileno" -> new PyNativeFunction("fileno", (args, kwNames) -> {
                Arguments.none("fileno", args, kwNames);
                return PyInt.of(fd);
            });
            case "__doc__" -> PyNone.INSTANCE;
            default -> METHODS.bind(this, attribute);
        };
    }

    @Override
    public String repr() {
        return "<_io.TextIOWrapper name='" + name + "' mode='w' encoding='utf-8'>";
    }
}
