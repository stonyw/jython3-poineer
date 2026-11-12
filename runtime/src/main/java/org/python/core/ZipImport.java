package org.python.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code zipimport}: the module and the importer a zip file on {@code sys.path} answers with.
 *
 * <p>The reference's {@code zipimport} reads a zip archive's central directory and serves the modules
 * inside it. This runtime does not do that yet: what is here is the module a program imports, the
 * {@code ZipImportError} it raises, and the {@code zipimporter} caller, so that the refusals a
 * program sees are the reference's. {@code sys.path_hooks} files the importer first, and every
 * directory on {@code sys.path} is refused by it exactly as the reference refuses one.
 *
 * <p>A caller that does name a zip file gets {@link Slots#notImplemented} naming zip import; that
 * omission is the milestone's, not a silent success.
 */
final class ZipImport {

    private ZipImport() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("zipimport");
        module.set("__doc__", PyStr.of("Provides access to the implicit import mechanism "
                + "for ZIP archives."));
        module.set("__package__", PyStr.of(""));
        module.set("__loader__", BuiltinImporter.type());
        module.set("__spec__", PyModuleSpec.builtin("zipimport", BuiltinImporter.type()));
        module.set("__all__", PyList.of(PyStr.of("ZipImportError"), PyStr.of("zipimporter")));
        module.set("ZipImportError", ExceptionTypes.ZIP_IMPORT_ERROR);
        module.set("zipimporter", PyType.ZIP_IMPORTER);
        return module;
    }

    /**
     * {@code zipimporter(path)}: the reference's own checks, in the reference's own order.
     *
     * <p>A path that is not a string fails in the argument parser before anything is opened; a path
     * that is missing or a directory is not a zip file and says only that; a regular file is opened and
     * its first four bytes decide, with the path named in the refusal when it is not an archive.
     */
    static PyObject zipImporter(PyObject[] args, String[] kwNames) {
        PyObject[] values = ImportArguments.bind(
                "zipimporter", args, kwNames, new String[] {"file"}, 1);
        PyObject given = values[0];
        if (!(given instanceof PyStr text)) {
            throw Slots.typeError(
                    "expected str, not <class '%s'>", given.getType().filedName());
        }
        String path = text.getValue();
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw PyRaise.raise(ExceptionTypes.ZIP_IMPORT_ERROR, "not a Zip file");
        }
        if (!isArchive(file)) {
            throw PyRaise.raise(
                    ExceptionTypes.ZIP_IMPORT_ERROR, "not a Zip file: '" + path + "'");
        }
        throw Slots.notImplemented("zip import");
    }

    /** The two signatures a zip archive starts with: a local header or an empty central directory. */
    private static boolean isArchive(Path file) {
        byte[] head = new byte[4];
        try (InputStream stream = Files.newInputStream(file)) {
            int read = stream.readNBytes(head, 0, head.length);
            return read == head.length
                    && head[0] == 'P'
                    && head[1] == 'K'
                    && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6));
        } catch (IOException unreadable) {
            return false;
        }
    }
}
