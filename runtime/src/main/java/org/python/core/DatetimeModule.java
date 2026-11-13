package org.python.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code _datetime}: the six date and time types {@code datetime.py} takes with {@code from _datetime
 * import *}.
 *
 * <p>CPython builds this module in C. The same types exist a second time in Python as
 * {@code _pydatetime.py}, and that source is vendored beside this class as {@code _datetime.py} — the
 * arithmetic and the field checks are identical, so running it here gives the same values rather than
 * an imitation of them. One line changed: {@code operator} is a Python module and a built-in module
 * must not need the standard library, so the runtime injects {@code __jy3_index__} before the source
 * runs and the import became an assignment.
 *
 * <p>Built on first import for the same reason {@code _ast} is: the source needs the injected compiler,
 * which is handed in after {@code startup()}. Until then the name answers from the built-in table and
 * the module table has nothing under it.
 */
final class DatetimeModule {

    /** The Python source, a resource so the vendored file stays readable as the file it is. */
    private static final String SOURCE = "/org/python/core/_datetime.py";

    private static PyModule module;

    private DatetimeModule() {
    }

    /** The module, built on first use. Synchronized because two frames may import it in one process. */
    static synchronized PyModule install() {
        if (module != null) {
            return module;
        }
        if (!PyRuntime.hasSourceCompiler()) {
            throw Slots.notImplemented("_datetime needs a compiler (setSourceCompiler)");
        }
        PyModule built = PyRuntime.namespace(new PyModule("_datetime"));
        PyRuntime.markBuiltIn(built, "_datetime");
        PyDict globals = built.dictionary();
        globals.put(PyStr.interned("__jy3_index__"),
                PyNativeFunction.positional("index", DatetimeModule::index));
        PyRuntime.runIn(PyRuntime.compileSource(source(), "<_datetime>"), globals, globals);
        // The source names itself `datetime` while its class statements run, which is how
        // `_datetime.date.__module__` reads "datetime" in the reference too. The module's own name is
        // `_datetime`, and that is what `import` and `__name__` have to answer, so it goes back now.
        built.set("__name__", PyStr.interned("_datetime"));
        PyRuntime.registerBuiltInModule("_datetime", built);
        module = built;
        return built;
    }

    /** {@code operator.index(obj)}, which the two field checks call to type their arguments. */
    private static PyObject index(PyObject[] args) {
        Arguments.exactlyOne("index", args, null);
        return PyInt.of(Numbers.asIndex(args[0]));
    }

    private static String source() {
        try (InputStream stream = DatetimeModule.class.getResourceAsStream(SOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the runtime is missing " + SOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }
}
