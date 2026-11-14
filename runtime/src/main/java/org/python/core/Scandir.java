package org.python.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code os.scandir}: a directory read as a stream of {@link DirEntry} values.
 *
 * <p>CPython's {@code posix.scandir} is one C function returning a cursor that wraps a {@code DIR *}.
 * The JVM has no {@code readdir}, so the cursor here wraps a {@link DirectoryStream}, and each entry
 * carries the two numbers {@code readdir} would have left in the caller's {@code struct dirent}: the
 * inode and the file type. Those two are read once, when the entry is handed out, and every later
 * question about them — {@code inode()}, {@code is_dir()}, {@code is_symlink()} — is answered from
 * them, so a file removed after the scan is still described as it was when it was listed.
 *
 * <p>{@code stat()} is the exception: it asks the file system every time, which is why it raises after
 * the file is gone. That split is the reference's own, not an approximation of it.
 */
final class Scandir {

    private static final int S_IFMT = 0xF000;
    private static final int S_IFDIR = 0x4000;
    private static final int S_IFREG = 0x8000;
    private static final int S_IFLNK = 0xA000;

    private static final String[] FOLLOW = {"follow_symlinks"};
    private static final PyObject[] FOLLOW_DEFAULT = {PyBool.TRUE};

    private Scandir() {
    }

    // ---- the two types ---------------------------------------------------------------------

    static final Members DIR_ENTRY_MEMBERS = Members.of(PyType.DIR_ENTRY)
            .addField("name", "the entry's base filename, relative to the scandir() path",
                    self -> ((DirEntry) self).name)
            .addField("path", "the entry's full path name",
                    self -> ((DirEntry) self).path)
            .add("__fspath__", (self, args, kwNames) -> {
                Arguments.none("DirEntry.__fspath__()", args, kwNames);
                return ((DirEntry) self).path;
            })
            .addNoArgument("inode", "return inode of the entry; cached by scandir()",
                    self -> PyInt.of(((DirEntry) self).inode))
            .add("is_dir", "return True if the entry is a directory; cached by scandir()",
                    (self, args, kwNames) -> PyBool.of(
                            ((DirEntry) self).isDirectory(follows(args, kwNames, "is_dir"))))
            .add("is_file", "return True if the entry is a regular file; cached by scandir()",
                    (self, args, kwNames) -> PyBool.of(
                            ((DirEntry) self).isFile(follows(args, kwNames, "is_file"))))
            .add("is_symlink",
                    "return True if the entry is a symbolic link; cached by scandir()",
                    (self, args, kwNames) -> {
                        Arguments.none("DirEntry.is_symlink()", args, kwNames);
                        return PyBool.of((((DirEntry) self).mode & S_IFMT) == S_IFLNK);
                    })
            .addNoArgument("is_junction", "return True if the entry is a junction; cached by scandir()",
                    self -> PyBool.FALSE)
            .add("__reduce_ex__", (self, args, kwNames) -> {
                // A DirEntry names a moment in a directory, not a value that can be replayed later;
                // the reference refuses the pickle from object.__reduce_ex__ and says whose it is.
                throw Slots.typeError("cannot pickle '%s' object", "posix.DirEntry");
            })
            .add("stat", "return stat_result object for the entry; cached by scandir()",
                    (self, args, kwNames) -> ((DirEntry) self)
                            .stat(follows(args, kwNames, "stat")));

    static final Members SCANDIR_MEMBERS = Members.of(PyType.SCANDIR_ITERATOR)
            .addNoArgument("close", "close the iterator", self -> {
                ((Cursor) self).close();
                return PyNone.INSTANCE;
            })
            .add("__enter__", (self, args, kwNames) -> {
                Arguments.none("ScandirIterator.__enter__()", args, kwNames);
                return self;
            })
            .add("__exit__", (self, args, kwNames) -> {
                ((Cursor) self).close();
                return PyNone.INSTANCE;
            })
            .add("__del__", (self, args, kwNames) -> {
                ((Cursor) self).close();
                return PyNone.INSTANCE;
            });

    /** The {@code follow_symlinks} keyword every query but {@code inode()} and {@code is_symlink} takes. */
    private static boolean follows(PyObject[] args, String[] kwNames, String name) {
        PyObject[] values = Arguments.parameters(
                "DirEntry." + name, args, kwNames, FOLLOW, FOLLOW_DEFAULT, 0);
        return Slots.truth(values[0]);
    }

    // ---- DirEntry --------------------------------------------------------------------------

    /** {@code os.DirEntry}: one name the cursor handed out, with the two numbers it was read with. */
    static final class DirEntry extends PyObject {

        /** The path the caller spelled, so {@code entry.path} can be joined in its own kind. */
        private final PosixModule.FsPath route;

        private final String entryName;

        /** {@code entry.name}: str or bytes, following the kind of the scanned path. */
        final PyObject name;

        /** {@code entry.path}: {@code os.path.join(route, name)}. */
        final PyObject path;

        /** {@code d_ino}, read once with the entry. */
        final long inode;

        /** The {@code st_mode} {@code lstat} gave when the entry was read, zero if that failed. */
        final int mode;

        DirEntry(PosixModule.FsPath route, String entryName, long inode, int mode) {
            super(PyType.DIR_ENTRY);
            this.route = route;
            this.entryName = entryName;
            this.inode = inode;
            this.mode = mode;
            boolean bytes = route.bytes();
            this.name = bytes
                    ? PyBytes.of(entryName.getBytes(StandardCharsets.UTF_8))
                    : PyStr.of(entryName);
            this.path = bytes
                    ? PyBytes.of(join(route.text(), entryName).getBytes(StandardCharsets.UTF_8))
                    : PyStr.of(join(route.text(), entryName));
        }

        /** {@code os.path.join}, as a plain string: a path ending in a separator needs none added. */
        private static String join(String root, String name) {
            if (root.isEmpty() || root.endsWith("/")) {
                return root + name;
            }
            return root + "/" + name;
        }

        private String pathText() {
            return path instanceof PyBytes bytes
                    ? new String(bytes.getValue(), StandardCharsets.UTF_8)
                    : ((PyStr) path).getValue();
        }

        boolean isDirectory(boolean follow) {
            if ((mode & S_IFMT) == S_IFDIR) {
                return true;
            }
            return follow && (mode & S_IFMT) == S_IFLNK && followsTo(true);
        }

        boolean isFile(boolean follow) {
            if ((mode & S_IFMT) == S_IFREG) {
                return true;
            }
            return follow && (mode & S_IFMT) == S_IFLNK && followsTo(false);
        }

        /** Whether the link's target is a directory ({@code wantDirectory}) or a regular file. */
        private boolean followsTo(boolean wantDirectory) {
            try {
                PyStatResult result = (PyStatResult) PosixModule.statPath(
                        new PosixModule.FsPath(pathText(), route.bytes()), true);
                long target = ((PyInt) Slots.getAttr(result, "st_mode")).getValue().intValue();
                int kind = (int) target & S_IFMT;
                return wantDirectory ? kind == S_IFDIR : kind == S_IFREG;
            } catch (PyRaise gone) {
                return false;
            }
        }

        PyObject stat(boolean follow) {
            return PosixModule.statPath(new PosixModule.FsPath(pathText(), route.bytes()), follow);
        }

        @Override
        public String repr() {
            String shown = name instanceof PyBytes bytes
                    ? "b'" + entryName + "'"
                    : "'" + entryName + "'";
            return "<DirEntry " + shown + ">";
        }
    }

    // ---- the cursor ------------------------------------------------------------------------

    /** {@code type(os.scandir(path))}: a cursor over a directory, closed when it runs out. */
    static final class Cursor extends PyIterator {

        private final Path directory;

        private final PosixModule.FsPath route;

        private final DirectoryStream<Path> stream;

        private final java.util.Iterator<Path> cursor;

        private boolean closed;

        Cursor(Path directory, PosixModule.FsPath route, DirectoryStream<Path> stream) {
            super(PyType.SCANDIR_ITERATOR);
            this.directory = directory;
            this.route = route;
            this.stream = stream;
            this.cursor = stream.iterator();
        }

        @Override
        public PyObject nextOrNull() {
            synchronized (this) {
                if (closed) {
                    return null;
                }
                Path child;
                try {
                    if (!cursor.hasNext()) {
                        close();
                        return null;
                    }
                    child = cursor.next();
                } catch (RuntimeException ended) {
                    close();
                    return null;
                }
                Path full = directory.resolve(child.getFileName());
                long inode = 0;
                int mode = 0;
                try {
                    Map<String, Object> unix =
                            Files.readAttributes(full, "unix:mode,ino", LinkOption.NOFOLLOW_LINKS);
                    inode = ((Number) unix.get("ino")).longValue();
                    mode = ((Number) unix.get("mode")).intValue();
                } catch (IOException vanished) {
                    // The entry went away between readdir and lstat; the reference would have handed
                    // out a name with only the d_type it read. Nothing to cache, then.
                }
                return new DirEntry(route, child.getFileName().toString(), inode, mode);
            }
        }

        void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // The directory was already gone; there is nothing left to release.
                }
            }
        }

        @Override
        public String repr() {
            return "<" + getType().filedName() + " object at 0x"
                    + Integer.toHexString(System.identityHashCode(this)) + ">";
        }
    }

    // ---- os.scandir ------------------------------------------------------------------------

    /** The scandir argument must be a path; the wording names every kind the reference takes. */
    private static PyObject scandirPath(PyObject value) {
        if (value instanceof PyStr || value instanceof PyBytes) {
            return value;
        }
        if (value instanceof PyInt || value instanceof PyBool) {
            // The reference reads a number as a descriptor; this runtime's table holds no directory
            // descriptors, so the refusal is named rather than dressed up as a type error.
            throw Slots.notImplemented("scandir() with a file descriptor");
        }
        throw Slots.typeError(
                "scandir: path should be string, bytes, os.PathLike, integer or None, not %s",
                Slots.argumentName(value));
    }

    static PyObject scandir(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.parameters(
                "scandir", args, kwNames, new String[] {"path"},
                new PyObject[] {PyNone.INSTANCE}, 0);
        PosixModule.FsPath path = values[0] == null || values[0] == PyNone.INSTANCE
                ? new PosixModule.FsPath(".", false)
                : PosixModule.fsPath(scandirPath(values[0]), "scandir");
        if (path.text().isEmpty()) {
            throw PosixModule.failure(new NoSuchFileException(path.text()), path);
        }
        Path directory = PosixModule.resolve(path.text());
        if (!Files.exists(directory)) {
            throw PosixModule.failure(new NoSuchFileException(path.text()), path);
        }
        if (!Files.isDirectory(directory)) {
            throw PosixModule.failure(new NotDirectoryException(path.text()), path);
        }
        try {
            return new Cursor(directory, path, Files.newDirectoryStream(directory));
        } catch (IOException error) {
            throw PosixModule.failure(error, path);
        }
    }
}
