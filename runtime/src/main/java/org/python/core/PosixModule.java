package org.python.core;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The {@code posix} module: the handful of filesystem and environment calls the standard library needs
 * before it will import itself.
 *
 * <p>CPython's {@code posix} is the C module underneath {@code os}: {@code os} is a Python file that does
 * {@code from posix import *}, so every name here is also an {@code os} name. Only the calls that
 * {@code os}, {@code posixpath} and {@code contextlib} reach for on their import paths are built, plus the
 * constants and the two error spellings those modules read.
 *
 * <p>The work is done through {@link Files}, which leaves one difference a program can see: a JVM cannot
 * change the process's working directory, so {@link #cwd} is this module's own idea of it. Every path is
 * resolved against that field before it reaches the filesystem, and {@code chdir} moves the field. A
 * relative path read by some other facility — one that goes straight to the operating system — still lands
 * where the process started.
 */
final class PosixModule {

    private static final String DOC =
            "This module provides access to operating system functionality that is\n"
                    + "standardized by the C Standard and the POSIX standard (a thinly\n"
                    + "disguised Unix interface).  Refer to the library manual and\n"
                    + "corresponding Unix manual entries for more information on calls.";

    /**
     * {@code strerror} on the platform this runtime is measured on, in full.
     *
     * <p>CPython asks the C library, so the wording is the C library's rather than its own; these are the
     * strings macOS answers with, and {@link #strerror} falls back on {@code "Unknown error: N"} exactly
     * where the reference does.
     */
    private static final String[] STRERROR = {
            "Undefined error: 0",
            "Operation not permitted",
            "No such file or directory",
            "No such process",
            "Interrupted system call",
            "Input/output error",
            "Device not configured",
            "Argument list too long",
            "Exec format error",
            "Bad file descriptor",
            "No child processes",
            "Resource deadlock avoided",
            "Cannot allocate memory",
            "Permission denied",
            "Bad address",
            "Block device required",
            "Resource busy",
            "File exists",
            "Cross-device link",
            "Operation not supported by device",
            "Not a directory",
            "Is a directory",
            "Invalid argument",
            "Too many open files in system",
            "Too many open files",
            "Inappropriate ioctl for device",
            "Text file busy",
            "File too large",
            "No space left on device",
            "Illegal seek",
            "Read-only file system",
            "Too many links",
            "Broken pipe",
            "Numerical argument out of domain",
            "Result too large",
            "Resource temporarily unavailable",
            "Operation now in progress",
            "Operation already in progress",
            "Socket operation on non-socket",
            "Destination address required",
            "Message too long",
            "Protocol wrong type for socket",
            "Protocol not available",
            "Protocol not supported",
            "Socket type not supported",
            "Operation not supported",
            "Protocol family not supported",
            "Address family not supported by protocol family",
            "Address already in use",
            "Can't assign requested address",
            "Network is down",
            "Network is unreachable",
            "Network dropped connection on reset",
            "Software caused connection abort",
            "Connection reset by peer",
            "No buffer space available",
            "Socket is already connected",
            "Socket is not connected",
            "Can't send after socket shutdown",
            "Too many references: can't splice",
            "Operation timed out",
            "Connection refused",
            "Too many levels of symbolic links",
            "File name too long",
            "Host is down",
            "No route to host",
            "Directory not empty",
            "Too many processes",
            "Too many users",
            "Disc quota exceeded",
            "Stale NFS file handle",
            "Too many levels of remote in path",
            "RPC struct is bad",
            "RPC version wrong",
            "RPC prog. not avail",
            "Program version wrong",
            "Bad procedure for program",
            "No locks available",
            "Function not implemented",
            "Inappropriate file type or format",
            "Authentication error",
            "Need authenticator",
            "Device power is off",
            "Device error",
            "Value too large to be stored in data type",
            "Bad executable (or shared library)",
            "Bad CPU type in executable",
            "Shared library version mismatch",
            "Malformed Mach-o file",
            "Operation canceled",
            "Identifier removed",
            "No message of desired type",
            "Illegal byte sequence",
            "Attribute not found",
            "Bad message",
            "EMULTIHOP (Reserved)",
            "No message available on STREAM",
            "ENOLINK (Reserved)",
            "No STREAM resources",
            "Not a STREAM",
            "Protocol error",
            "STREAM ioctl timeout",
            "Operation not supported on socket",
            "Policy not found",
            "State not recoverable",
            "Previous owner died",
            "Interface output queue is full",
            "Capabilities insufficient",
    };

    private static final String[] PATH = {"path"};

    private static final String[] PATH_AND_MODE = {"path", "mode"};
    private static final PyObject[] PATH_AND_MODE_DEFAULTS = {null, PyInt.of(511)};

    private static final String[] PATH_AND_FOLLOW = {"path", "follow_symlinks"};
    private static final PyObject[] PATH_AND_FOLLOW_DEFAULTS = {null, PyBool.TRUE};

    private static final String[] ACCESS = {"path", "mode", "follow_symlinks"};
    private static final PyObject[] ACCESS_DEFAULTS =
            {null, PyInt.of(0), PyBool.TRUE};

    private static final String[] TWO_PATHS = {"src", "dst"};
    private static final PyObject[] NO_DEFAULTS = {};

    private static final String[] ENVIRONMENT = {"name", "value"};

    /** Where {@code getcwd} answers from, since a JVM cannot move the process. */
    private static Path cwd =
            Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();

    /** {@code posix.environ}: bytes keys and bytes values, shared with {@code os.environ}. */
    private static PyDict environ;

    private PosixModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("posix");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "posix");
        module.set("error", ExceptionTypes.OS_ERROR);
        module.set("stat_result", PyType.STAT_RESULT);
        module.set("environ", environ());

        // `os` asks whether this platform supports the *at() family by reading this name, and builds its
        // support sets from the HAVE_* strings in it. None are built here, so every set comes out empty —
        // the calls that take a file descriptor are named below so the import itself can finish.
        module.set("_have_functions", PyTuple.of());
        module.set("open", unimplemented("open"));
        module.set("scandir", unimplemented("scandir"));

        // The two access masks and the open flags a program reads off the module. The values are macOS's.
        module.set("F_OK", PyInt.of(0));
        module.set("X_OK", PyInt.of(1));
        module.set("W_OK", PyInt.of(2));
        module.set("R_OK", PyInt.of(4));
        module.set("O_RDONLY", PyInt.of(0));
        module.set("O_WRONLY", PyInt.of(1));
        module.set("O_RDWR", PyInt.of(2));
        module.set("O_ACCMODE", PyInt.of(3));
        module.set("O_NONBLOCK", PyInt.of(0x4));
        module.set("O_APPEND", PyInt.of(0x8));
        module.set("O_SHLOCK", PyInt.of(0x10));
        module.set("O_EXLOCK", PyInt.of(0x20));
        module.set("O_ASYNC", PyInt.of(0x40));
        module.set("O_SYNC", PyInt.of(0x80));
        module.set("O_NOFOLLOW", PyInt.of(0x100));
        module.set("O_CREAT", PyInt.of(0x200));
        module.set("O_TRUNC", PyInt.of(0x400));
        module.set("O_EXCL", PyInt.of(0x800));
        module.set("O_EVTONLY", PyInt.of(0x8000));
        module.set("O_NOCTTY", PyInt.of(0x20000));
        module.set("O_DIRECTORY", PyInt.of(0x100000));
        module.set("O_SYMLINK", PyInt.of(0x200000));
        module.set("O_DSYNC", PyInt.of(0x400000));
        module.set("O_CLOEXEC", PyInt.of(0x1000000));
        module.set("O_EXEC", PyInt.of(0x40000000));
        module.set("O_SEARCH", PyInt.of(0x40100000));
        module.set("O_FSYNC", PyInt.of(0x80));
        module.set("O_NDELAY", PyInt.of(0x4));
        module.set("O_NOFOLLOW_ANY", PyInt.of(0x20000000));

        module.set("getcwd", function(
                "getcwd",
                "getcwd($module, /)\n--\n\nReturn a unicode string representing the current working directory.",
                (args, kwNames) -> {
                    Arguments.none("getcwd", args, kwNames);
                    return PyStr.of(cwd.toString());
                }));
        module.set("getcwdb", function(
                "getcwdb",
                "getcwdb($module, /)\n--\n\nReturn a bytes string representing the current working directory.",
                (args, kwNames) -> {
                    Arguments.none("getcwdb", args, kwNames);
                    return PyBytes.of(cwd.toString().getBytes(StandardCharsets.UTF_8));
                }));
        module.set("chdir", function(
                "chdir",
                "chdir($module, path, /)\n--\n\nChange the current working directory to the specified path.",
                (args, kwNames) -> {
                    FsPath path = single("chdir", args, kwNames);
                    Path target = resolve(path.text());
                    if (!Files.exists(target)) {
                        throw failure(new NoSuchFileException(path.text()), path);
                    }
                    if (!Files.isDirectory(target)) {
                        throw failure(new NotDirectoryException(path.text()), path);
                    }
                    cwd = target;
                    System.setProperty("user.dir", cwd.toString());
                    return PyNone.INSTANCE;
                }));
        module.set("listdir", function(
                "listdir",
                "listdir($module, path=None, /)\n--\n\n"
                        + "Return a list containing the names of the files in the directory.",
                (args, kwNames) -> listdir(args, kwNames)));
        module.set("stat", function(
                "stat",
                "stat($module, path, *, dir_fd=None, follow_symlinks=True)\n--\n\n"
                        + "Perform a stat system call on the given path.",
                (args, kwNames) -> stat(args, kwNames, true)));
        module.set("lstat", function(
                "lstat",
                "lstat($module, path, *, dir_fd=None)\n--\n\n"
                        + "Perform a stat system call on the given path, without following symbolic links.",
                (args, kwNames) -> stat(args, kwNames, false)));
        module.set("mkdir", function(
                "mkdir",
                "mkdir($module, path, mode=511, *, dir_fd=None)\n--\n\n"
                        + "Create a directory.",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.parameters(
                            "mkdir", args, kwNames, PATH_AND_MODE, PATH_AND_MODE_DEFAULTS, 1);
                    FsPath path = fsPath(values[0], "mkdir");
                    try {
                        Files.createDirectory(resolve(path.text()));
                    } catch (IOException e) {
                        throw failure(e, path);
                    }
                    return PyNone.INSTANCE;
                }));
        module.set("rmdir", function(
                "rmdir",
                "rmdir($module, path, *, dir_fd=None)\n--\n\nRemove a directory.",
                (args, kwNames) -> {
                    FsPath path = single("rmdir", args, kwNames);
                    Path target = resolve(path.text());
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw failure(new NoSuchFileException(path.text()), path);
                    }
                    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw failure(new NotDirectoryException(path.text()), path);
                    }
                    try {
                        Files.delete(target);
                    } catch (IOException e) {
                        throw failure(e, path);
                    }
                    return PyNone.INSTANCE;
                }));
        module.set("remove", function(
                "remove",
                "remove($module, path, *, dir_fd=None)\n--\n\nRemove a file (same as unlink()).",
                (args, kwNames) -> remove("remove", args, kwNames)));
        module.set("unlink", function(
                "unlink",
                "unlink($module, path, *, dir_fd=None)\n--\n\nRemove a file (same as remove()).",
                (args, kwNames) -> remove("unlink", args, kwNames)));
        module.set("rename", function(
                "rename",
                "rename($module, src, dst, *, src_dir_fd=None, dst_dir_fd=None)\n--\n\n"
                        + "Rename a file or directory.",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.parameters(
                            "rename", args, kwNames, TWO_PATHS, NO_DEFAULTS, 2);
                    FsPath src = fsPath(values[0], "rename");
                    FsPath dst = fsPath(values[1], "rename");
                    try {
                        Files.move(resolve(src.text()), resolve(dst.text()),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw failure(e, src);
                    }
                    return PyNone.INSTANCE;
                }));
        module.set("access", function(
                "access",
                "access($module, path, mode, *, dir_fd=None, effective_ids=False, follow_symlinks=True)\n"
                        + "--\n\nUse the real uid/gid to test for access to a path.",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.parameters(
                            "access", args, kwNames, ACCESS, ACCESS_DEFAULTS, 2);
                    FsPath path = fsPath(values[0], "access");
                    int mode = Numbers.asIndex(values[1]).intValueExact();
                    return PyBool.of(allowed(resolve(path.text()), mode));
                }));
        module.set("cpu_count", function(
                "cpu_count",
                "cpu_count($module, /)\n--\n\nReturn the number of CPUs in the system; return None if indeterminable.",
                (args, kwNames) -> {
                    Arguments.none("cpu_count", args, kwNames);
                    return PyInt.of(Runtime.getRuntime().availableProcessors());
                }));
        module.set("strerror", function(
                "strerror",
                "strerror($module, code, /)\n--\n\nTranslate an error code to a message string.",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.parameters(
                            "strerror", args, kwNames, new String[] {"code"}, NO_DEFAULTS, 1);
                    return PyStr.of(strerror(Numbers.asIndex(values[0])));
                }));
        module.set("putenv", function(
                "putenv",
                "putenv($module, name, value, /)\n--\n\nChange or add an environment variable.",
                (args, kwNames) -> {
                    Arguments.parameters(
                            "putenv", args, kwNames, ENVIRONMENT, NO_DEFAULTS, 2);
                    // The reference's `putenv` changes the process environment only; `os.environ` is a
                    // mapping that `_Environ` keeps in step by writing its own `_data`. Doing both here
                    // would make `del os.environ[k]` fail, because `_Environ` deletes from `_data` too.
                    return PyNone.INSTANCE;
                }));
        module.set("unsetenv", function(
                "unsetenv",
                "unsetenv($module, name, /)\n--\n\nDelete an environment variable.",
                (args, kwNames) -> {
                    Arguments.parameters(
                            "unsetenv", args, kwNames, new String[] {"name"}, NO_DEFAULTS, 1);
                    return PyNone.INSTANCE;
                }));
        return module;
    }

    private static PyNativeFunction function(
            String name, String doc, PyNativeFunction.Body body) {
        return new PyNativeFunction(name, body).documented(doc);
    }

    private static PyNativeFunction unimplemented(String name) {
        return function(
                name,
                name + "($module, /)\n--\n\nNot implemented by this runtime.",
                (args, kwNames) -> {
                    throw PyRaise.raise(
                            ExceptionTypes.NOT_IMPLEMENTED_ERROR,
                            name + "() is not implemented by this runtime");
                });
    }

    /** One positional argument, which is what every single-path call takes. */
    private static FsPath single(String name, PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.parameters(name, args, kwNames, PATH, NO_DEFAULTS, 1);
        return fsPath(values[0], name);
    }

    private static PyObject listdir(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.parameters(
                "listdir", args, kwNames, PATH, new PyObject[] {PyNone.INSTANCE}, 0);
        FsPath path = values[0] == null || values[0] == PyNone.INSTANCE
                ? new FsPath(cwd.toString(), false)
                : fsPath(values[0], "listdir");
        Path directory = resolve(path.text());
        if (!Files.exists(directory)) {
            throw failure(new NoSuchFileException(path.text()), path);
        }
        if (!Files.isDirectory(directory)) {
            throw failure(new NotDirectoryException(path.text()), path);
        }
        PyList names = PyList.empty();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                names.add(path.bytes()
                        ? PyBytes.of(name.getBytes(StandardCharsets.UTF_8))
                        : PyStr.of(name));
            }
        } catch (IOException e) {
            throw failure(e, path);
        }
        return names;
    }

    private static PyObject stat(PyObject[] args, String[] kwNames, boolean follow) {
        String function = follow ? "stat" : "lstat";
        PyObject[] values = Arguments.parameters(
                function, args, kwNames, PATH_AND_FOLLOW, PATH_AND_FOLLOW_DEFAULTS, 1);
        FsPath path = fsPath(values[0], function);
        boolean follows = follow && Slots.truth(values[1]);
        Path target = resolve(path.text());
        LinkOption[] options = follows
                ? new LinkOption[0]
                : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
        try {
            Map<String, Object> unix = Files.readAttributes(
                    target, "unix:mode,ino,dev,rdev,nlink,uid,gid,ctime", options);
            BasicFileAttributes basic =
                    Files.readAttributes(target, BasicFileAttributes.class, options);
            long atime = basic.lastAccessTime().to(TimeUnit.NANOSECONDS);
            long mtime = basic.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            long ctime = ((FileTime) unix.get("ctime")).to(TimeUnit.NANOSECONDS);
            long birthtime = basic.creationTime().to(TimeUnit.NANOSECONDS);
            long rdev = number(unix, "rdev");
            return PyStatResult.of(
                    new PyObject[] {
                        PyInt.of(number(unix, "mode")),
                        PyInt.of(number(unix, "ino")),
                        PyInt.of(number(unix, "dev")),
                        PyInt.of(number(unix, "nlink")),
                        PyInt.of(number(unix, "uid")),
                        PyInt.of(number(unix, "gid")),
                        PyInt.of(basic.size()),
                        PyFloat.of(atime / 1e9),
                        PyFloat.of(mtime / 1e9),
                        PyFloat.of(ctime / 1e9),
                    },
                    atime, mtime, ctime, birthtime, rdev);
        } catch (IOException e) {
            throw failure(e, path);
        }
    }

    private static PyObject remove(String function, PyObject[] args, String[] kwNames) {
        FsPath path = single(function, args, kwNames);
        Path target = resolve(path.text());
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            // macOS `unlink` refuses a directory with EPERM, not EISDIR, and CPython reports exactly
            // what the call said, so `os.remove` on a directory is a PermissionError there.
            throw failure(new FileSystemException(path.text(), null, "Operation not permitted"), path);
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw failure(e, path);
        }
        return PyNone.INSTANCE;
    }

    private static boolean allowed(Path path, int mode) {
        if (mode == 0) {
            return Files.exists(path);
        }
        if ((mode & 4) != 0 && !Files.isReadable(path)) {
            return false;
        }
        if ((mode & 2) != 0 && !Files.isWritable(path)) {
            return false;
        }
        return (mode & 1) == 0 || Files.isExecutable(path);
    }

    private static Path resolve(String path) {
        return cwd.resolve(path);
    }

    /**
     * A caller's path with this module's working directory applied.
     *
     * <p>{@code chdir} moves {@link #cwd} rather than the process, so every other way into the file
     * system — the {@code open} builtin included — has to ask here for the same answer. An absolute
     * path is returned unchanged, which is what {@link Path#resolve(String)} does.
     */
    static String absolute(String path) {
        return cwd.resolve(path).toString();
    }

    private static long number(Map<String, Object> attributes, String name) {
        return ((Number) attributes.get(name)).longValue();
    }

    static String strerror(int code) {
        return code >= 0 && code < STRERROR.length ? STRERROR[code] : "Unknown error: " + code;
    }

    private static String strerror(BigInteger code) {
        return code.signum() >= 0 && code.compareTo(BigInteger.valueOf(STRERROR.length)) < 0
                ? STRERROR[code.intValue()]
                : "Unknown error: " + code;
    }

    private static byte[] environmentBytes(PyObject value, String function) {
        if (value instanceof PyBytes bytes) {
            return bytes.getValue();
        }
        if (value instanceof PyStr text) {
            return text.getValue().getBytes(StandardCharsets.UTF_8);
        }
        throw Slots.typeError("%s(): name and value must be str or bytes", function);
    }

    private static PyDict environ() {
        if (environ == null) {
            PyDict data = PyDict.empty();
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                data.put(
                        PyBytes.of(entry.getKey().getBytes(StandardCharsets.UTF_8)),
                        PyBytes.of(entry.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            environ = data;
        }
        return environ;
    }

    private static FsPath fsPath(PyObject value, String function) {
        if (value instanceof PyStr text) {
            return new FsPath(text.getValue(), false);
        }
        if (value instanceof PyBytes bytes) {
            // Java has no surrogates to escape; a well-formed name is all this milestone promises.
            return new FsPath(new String(bytes.getValue(), StandardCharsets.UTF_8), true);
        }
        throw Slots.typeError(
                "%s: path should be string, bytes, os.PathLike or integer, not %s",
                function, value.getType().name());
    }

    private static PyRaise failure(Exception error, FsPath path) {
        int number = errno(error);
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.OS_ERROR,
                new PyObject[] {
                    PyInt.of(number),
                    PyStr.of(strerror(number)),
                    path.bytes()
                            ? PyBytes.of(path.text().getBytes(StandardCharsets.UTF_8))
                            : PyStr.of(path.text()),
                }));
    }

    /**
     * The error number behind a Java failure, read off the exception where the JDK names one and off the
     * reason text where it does not.
     */
    private static int errno(Exception error) {
        if (error instanceof NoSuchFileException) {
            return 2;
        }
        if (error instanceof FileAlreadyExistsException) {
            return 17;
        }
        if (error instanceof DirectoryNotEmptyException) {
            return 66;
        }
        if (error instanceof NotDirectoryException) {
            return 20;
        }
        if (error instanceof AccessDeniedException) {
            return 13;
        }
        String reason = error instanceof FileSystemException files && files.getReason() != null
                ? files.getReason()
                : String.valueOf(error.getMessage());
        if (reason.contains("Operation not permitted")) {
            return 1;
        }
        if (reason.contains("No such file or directory")) {
            return 2;
        }
        if (reason.contains("Permission denied")) {
            return 13;
        }
        if (reason.contains("File exists")) {
            return 17;
        }
        if (reason.contains("Cross-device link")) {
            return 18;
        }
        if (reason.contains("Not a directory")) {
            return 20;
        }
        if (reason.contains("Is a directory")) {
            return 21;
        }
        if (reason.contains("Read-only file system")) {
            return 30;
        }
        if (reason.contains("Too many levels of symbolic links")) {
            return 62;
        }
        if (reason.contains("File name too long")) {
            return 63;
        }
        if (reason.contains("Directory not empty")) {
            return 66;
        }
        return 5;
    }

    /** A path as the caller spelled it, and whether it was spelled in bytes. */
    private record FsPath(String text, boolean bytes) {
    }
}
