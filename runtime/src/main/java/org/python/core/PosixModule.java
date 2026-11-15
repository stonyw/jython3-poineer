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
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
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

    /**
     * The file-mode mask this runtime applies to the calls it makes itself.
     *
     * <p>A JVM has no {@code umask(2)} and no way to read the process's own, so the value starts out
     * as the one the process was started with — measured once by creating a directory with known bits
     * and reading back what the kernel allowed — and {@code os.umask} changes this field rather than
     * the process. That is the honest split: {@code mkdir} and {@code mkfifo} honour it here because
     * this runtime sets their final modes, while a call that goes straight to the operating system
     * still sees the process mask. {@code -1} means "not measured yet".
     */
    private static int umask = -1;

    /**
     * Where the runtime believes it is, for a child process that would inherit it.
     *
     * <p>{@code chdir} moves this path rather than the JVM's, so a child started without an explicit
     * {@code cwd} still has to be told: in the reference it would inherit the moved directory from the
     * process itself.
     */
    static String workingDirectory() {
        return cwd.toString();
    }

    /** The source {@code urandom} draws from; a fresh {@link SecureRandom} seeds itself on first use. */
    private static final SecureRandom RANDOM = new SecureRandom();

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
        module.set("DirEntry", PyType.DIR_ENTRY);
        module.set("scandir", function(
                "scandir",
                "scandir($module, path=None)\n--\n\n"
                        + "Return an iterator of DirEntry objects for given path.",
                Scandir::scandir));

        // The descriptor calls `subprocess` reaches for. There is no kernel table here, so the numbers
        // come from Fds: a pipe end, the null device, or a real file the `open` call made.
        module.set("open", function(
                "open",
                "open($module, path, flags, mode=511, *, dir_fd=None)\n--\n\n"
                        + "Open a file for low level IO.  Returns a file descriptor (integer).",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.parameters(
                            "open", args, kwNames,
                            new String[] {"path", "flags", "mode", "dir_fd"},
                            new PyObject[] {null, null, PyInt.of(511), PyNone.INSTANCE}, 0);
                    // Both are required but may be named, so the bound is zero and the missing one is
                    // reported here with the sentence clinic would use.
                    if (values[0] == null) {
                        throw Slots.typeError(
                                "open() missing required argument 'path' (pos 1)");
                    }
                    if (values[1] == null) {
                        throw Slots.typeError(
                                "open() missing required argument 'flags' (pos 2)");
                    }
                    FsPath path = fsPath(values[0], "open");
                    if (values[3] != null && values[3] != PyNone.INSTANCE) {
                        throw Slots.notImplemented("open() with dir_fd");
                    }
                    if ("/dev/null".equals(path.text())) {
                        return PyInt.of(Fds.devnull());
                    }
                    int flags = Numbers.asIndex(values[1]).intValue();
                    int access = flags & 3;
                    boolean write = access == 1 || access == 2;
                    boolean read = access == 0 || access == 2;
                    boolean create = (flags & 0x200) != 0;
                    boolean exclusive = (flags & 0x800) != 0;
                    boolean truncate = (flags & 0x400) != 0;
                    boolean append = (flags & 0x8) != 0;
                    Path target = resolve(path.text());
                    boolean existed = Files.exists(target);
                    int fd;
                    try {
                        fd = Fds.open(target, read, write, append, create, exclusive, truncate);
                    } catch (IOException error) {
                        throw failure(error, path);
                    }
                    if (create && !existed) {
                        // The kernel would have masked the mode with the process umask; creating
                        // through Java sets no mode at all, so the mask is applied here instead.
                        int created = Numbers.asIndex(values[2]).intValue() & ~umaskValue() & 0777;
                        try {
                            Files.setPosixFilePermissions(target, permissions(created));
                        } catch (IOException | UnsupportedOperationException ignored) {
                            // A file system without permissions still has the file; the mode is advisory.
                        }
                    }
                    return PyInt.of(fd);
                }));
        module.set("pipe", function(
                "pipe",
                "pipe($module, /)\n--\n\nCreate a pipe.\n\n"
                        + "Returns a tuple of two file descriptors:\n(read_fd, write_fd).",
                (args, kwNames) -> {
                    Arguments.none("pipe", args, kwNames);
                    int[] ends = Fds.pipe();
                    return PyTuple.of(PyInt.of(ends[0]), PyInt.of(ends[1]));
                }));
        module.set("close", function(
                "close",
                "close($module, fd, /)\n--\n\nClose a file descriptor.",
                (args, kwNames) -> {
                    Arguments.exactlyOne("close", args, kwNames);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    // A socket descriptor is not in the pipe table; the reference has one table, so
                    // closing a socket through os closes the socket.
                    if (SocketModule.owns(fd)) {
                        SocketModule.closeFd(fd);
                        return PyNone.INSTANCE;
                    }
                    Fds.close(fd);
                    return PyNone.INSTANCE;
                }));
        module.set("read", function(
                "read",
                "read($module, fd, length, /)\n--\n\nRead from a file descriptor.",
                (args, kwNames) -> {
                    Arguments.exactly("read", args, kwNames, 2);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    int length = Numbers.asIndex(args[1]).intValue();
                    if (SocketModule.owns(fd)) {
                        return PyBytes.of(SocketModule.readFd(fd, length));
                    }
                    byte[] bytes = Fds.read(fd, Math.max(length, 0));
                    if (bytes == null) {
                        throw wouldBlock();
                    }
                    return PyBytes.of(bytes);
                }));
        module.set("write", function(
                "write",
                "write($module, fd, data, /)\n--\n\nWrite bytes to a file descriptor.",
                (args, kwNames) -> {
                    Arguments.exactly("write", args, kwNames, 2);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    byte[] bytes = PyFileIO.octetsToWrite(args[1]);
                    if (SocketModule.owns(fd)) {
                        return PyInt.of(SocketModule.writeFd(fd, bytes));
                    }
                    int written = Fds.write(fd, bytes);
                    if (written == 0 && bytes.length > 0) {
                        throw wouldBlock();
                    }
                    return PyInt.of(written);
                }));
        module.set("sendfile", function(
                "sendfile",
                "sendfile($module, out_fd, in_fd, offset, count, /)\n--\n\n"
                        + "Copy count bytes from file descriptor in_fd to file descriptor out_fd.\n\n"
                        + "Starting at offset, copy count bytes from in_fd to out_fd until EOF is\n"
                        + "reached. Return the number of bytes sent.",
                (args, kwNames) -> {
                    Arguments.exactly("sendfile", args, kwNames, 4);
                    int out = intArgument(args[0]);
                    int in = intArgument(args[1]);
                    boolean fromCurrent = args[2] == PyNone.INSTANCE;
                    long offset = fromCurrent ? 0 : longArgument(args[2]);
                    long count = longArgument(args[3]);
                    if (!SocketModule.owns(out) || !Fds.isFile(in) || (!fromCurrent && offset < 0)) {
                        // sendfile(2) refuses a descriptor pair it cannot copy between (a pipe, a
                        // character device, a negative offset) with EINVAL rather than guessing.
                        throw PyRaise.of(PyOSError.create(
                                ExceptionTypes.OS_ERROR,
                                new PyObject[] {PyInt.of(22), PyStr.of("Invalid argument")}));
                    }
                    if (count <= 0) {
                        return PyInt.of(0);
                    }
                    java.io.RandomAccessFile file = Fds.file(in);
                    int wanted = (int) Math.min(count, 1 << 20);
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(wanted);
                    long position;
                    int read;
                    int holds = Gil.suspend();
                    try {
                        // A positional read leaves the handle's own position alone, which is what a
                        // given offset asks for; a NULL offset is the one case that advances it.
                        position = fromCurrent ? file.getFilePointer() : offset;
                        read = file.getChannel().read(buffer, position);
                    } catch (IOException error) {
                        throw PyRaise.of(PyOSError.create(
                                ExceptionTypes.OS_ERROR,
                                new PyObject[] {PyInt.of(5), PyStr.of("Input/output error")}));
                    } finally {
                        Gil.resume(holds);
                    }
                    if (read <= 0) {
                        return PyInt.of(0);
                    }
                    byte[] bytes =
                            read == wanted ? buffer.array() : Arrays.copyOf(buffer.array(), read);
                    // One non-blocking attempt, as sendfile(2) on a non-blocking descriptor is: what
                    // fits goes now and the rest is the caller's to retry, which is what makes
                    // socket.sendfile's selector wait and its timeout mean anything.
                    int sent = SocketModule.writeFdNonBlocking(out, bytes);
                    if (sent > 0 && fromCurrent) {
                        int nested = Gil.suspend();
                        try {
                            // Only what was sent is consumed, so a short write leaves the rest to be
                            // read again rather than dropping it.
                            file.seek(position + sent);
                        } catch (IOException error) {
                            throw PyRaise.of(PyOSError.create(
                                    ExceptionTypes.OS_ERROR,
                                    new PyObject[] {PyInt.of(5), PyStr.of("Input/output error")}));
                        } finally {
                            Gil.resume(nested);
                        }
                    }
                    return PyInt.of(sent);
                }));
        module.set("lseek", function(
                "lseek",
                "lseek($module, fd, position, whence, /)\n--\n\n"
                        + "Set the position of a file descriptor.  Return the new position.",
                (args, kwNames) -> {
                    Arguments.exactly("lseek", args, kwNames, 3);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    long position = longArgument(args[1]);
                    int whence = Numbers.asIndex(args[2]).intValue();
                    return PyInt.of(Fds.seek(fd, position, whence));
                }));
        module.set("readinto", function(
                "readinto",
                "readinto($module, fd, buffer, /)\n--\n\n"
                        + "Read into a buffer object from a file descriptor.",
                (args, kwNames) -> {
                    Arguments.exactly("readinto", args, kwNames, 2);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    PyObject target = args[1];
                    int length;
                    if (target instanceof PyByteArray run) {
                        length = run.size();
                    } else if (target instanceof PyMemoryView view && !view.readonly()) {
                        length = view.byteLength();
                    } else {
                        throw Slots.typeError(
                                "readinto() argument 2 must be read-write bytes-like object, not %s",
                                Slots.argumentName(target));
                    }
                    byte[] bytes = Fds.read(fd, length);
                    if (bytes == null) {
                        throw wouldBlock();
                    }
                    if (target instanceof PyByteArray run) {
                        System.arraycopy(bytes, 0, run.raw(), 0, bytes.length);
                    } else {
                        ((PyMemoryView) target).writeWindow(0, bytes, 0, bytes.length);
                    }
                    return PyInt.of(bytes.length);
                }));
        module.set("ftruncate", function(
                "ftruncate",
                "ftruncate($module, fd, length, /)\n--\n\n"
                        + "Truncate a file, specified by file descriptor, to a specific length.",
                (args, kwNames) -> {
                    Arguments.exactly("ftruncate", args, kwNames, 2);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    Fds.truncate(fd, longArgument(args[1]));
                    return PyNone.INSTANCE;
                }));
        module.set("dup", function(
                "dup",
                "dup($module, fd, /)\n--\n\nReturn a duplicate of a file descriptor.",
                (args, kwNames) -> {
                    Arguments.exactlyOne("dup", args, kwNames);
                    return PyInt.of(Fds.dup(Numbers.asIndex(args[0]).intValue()));
                }));
        module.set("closerange", function(
                "closerange",
                "closerange($module, fd_low, fd_high, /)\n--\n\n"
                        + "Closes all file descriptors in [fd_low, fd_high), ignoring any errors.\n"
                        + "The first fd_low file descriptors are ignored.",
                (args, kwNames) -> {
                    Arguments.exactly("closerange", args, kwNames, 2);
                    Fds.closerange(
                            Numbers.asIndex(args[0]).intValue(),
                            Numbers.asIndex(args[1]).intValue());
                    return PyNone.INSTANCE;
                }));
        module.set("get_inheritable", function(
                "get_inheritable",
                "get_inheritable($module, fd, /)\n--\n\n"
                        + "Get the close-on-exit flag of a file descriptor.",
                (args, kwNames) -> {
                    Arguments.exactlyOne("get_inheritable", args, kwNames);
                    return PyBool.of(Fds.inheritable(Numbers.asIndex(args[0]).intValue()));
                }));
        module.set("set_inheritable", function(
                "set_inheritable",
                "set_inheritable($module, fd, inheritable, /)\n--\n\n"
                        + "Set the close-on-exit flag of a file descriptor.",
                (args, kwNames) -> {
                    Arguments.exactly("set_inheritable", args, kwNames, 2);
                    Fds.setInheritable(
                            Numbers.asIndex(args[0]).intValue(), Slots.truth(args[1]));
                    return PyNone.INSTANCE;
                }));
        module.set("get_blocking", function(
                "get_blocking",
                "get_blocking($module, fd, /)\n--\n\n"
                        + "Get the blocking mode of the file descriptor.\n\n"
                        + "Return False if the O_NONBLOCK flag is set, True if the flag is cleared.",
                (args, kwNames) -> {
                    Arguments.exactlyOne("get_blocking", args, kwNames);
                    return PyBool.of(Fds.blocking(Numbers.asIndex(args[0]).intValue()));
                }));
        module.set("isatty", function(
                "isatty",
                "isatty($module, fd, /)\n--\n\nReturn True if the fd is connected to a terminal.",
                (args, kwNames) -> {
                    Arguments.exactlyOne("isatty", args, kwNames);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    if (fd >= 0 && fd <= 2) {
                        // The three inherited descriptors are the console's when the JVM was started
                        // with one; a JVM has no other way to ask, so this is what it says.
                        return PyBool.of(System.console() != null);
                    }
                    // Every other descriptor is a pipe, the null device or not open at all, and the
                    // reference answers False for all three: CPython 3.14 made isatty() swallow EBADF.
                    return PyBool.FALSE;
                }));
        module.set("fstat", function(
                "fstat",
                "fstat($module, fd, /)\n--\n\n"
                        + "Perform a stat system call on the given file descriptor.\n\n"
                        + "Like stat(), but for an open file descriptor.\n"
                        + "Equivalent to os.stat(fd).",
                (args, kwNames) -> {
                    Arguments.exactlyOne("fstat", args, kwNames);
                    return descriptorStat(intArgument(args[0]));
                }));
        module.set("set_blocking", function(
                "set_blocking",
                "set_blocking($module, fd, blocking, /)\n--\n\n"
                        + "Set the blocking mode of the file descriptor.\n\n"
                        + "Set the O_NONBLOCK flag if blocking is False,\n"
                        + "otherwise clear the O_NONBLOCK flag.",
                (args, kwNames) -> {
                    Arguments.exactly("set_blocking", args, kwNames, 2);
                    int fd = Numbers.asIndex(args[0]).intValue();
                    Fds.setBlocking(fd, Slots.truth(args[1]));
                    return PyNone.INSTANCE;
                }));
        module.set("waitpid", function(
                "waitpid",
                "waitpid($module, pid, options, /)\n--\n\n"
                        + "Wait for completion of a given child process.",
                (args, kwNames) -> {
                    Arguments.exactly("waitpid", args, kwNames, 2);
                    long pid = Numbers.asIndex(args[0]).longValue();
                    int options = Numbers.asIndex(args[1]).intValue();
                    return PosixSubprocessModule.waitpid(pid, options);
                }));
        module.set("kill", function(
                "kill",
                "kill($module, pid, signal, /)\n--\n\nKill a process with a signal.",
                (args, kwNames) -> {
                    Arguments.exactly("kill", args, kwNames, 2);
                    long pid = Numbers.asIndex(args[0]).longValue();
                    int sig = Numbers.asIndex(args[1]).intValue();
                    return processSignal(pid, sig);
                }));
        module.set("waitstatus_to_exitcode", function(
                "waitstatus_to_exitcode",
                "waitstatus_to_exitcode($module, /, status)\n--\n\n"
                        + "Convert a wait status to an exit code.",
                (args, kwNames) -> {
                    Arguments.exactlyOne("waitstatus_to_exitcode", args, kwNames);
                    return PyInt.of(exitCode(Numbers.asIndex(args[0]).intValue()));
                }));

        // The wait-status macros. CPython files them as functions built from C macros; the bit layout is
        // the POSIX/macOS one.
        module.set("WNOHANG", PyInt.of(0x1));
        module.set("WUNTRACED", PyInt.of(0x2));
        module.set("WIFEXITED", statusTest(
                "WIFEXITED", "WIFEXITED(status)\n--\n\nReturn True if the process has exited.",
                status -> (status & 0x7f) == 0));
        module.set("WEXITSTATUS", statusValue(
                "WEXITSTATUS", "WEXITSTATUS(status)\n--\n\nReturn the exit status of a process.",
                status -> (status & 0xff00) >> 8));
        module.set("WIFSIGNALED", statusTest(
                "WIFSIGNALED", "WIFSIGNALED(status)\n--\n\n"
                        + "Return True if the process was terminated by a signal.",
                status -> ((byte) ((status & 0x7f) + 1) >> 1) > 0));
        module.set("WTERMSIG", statusValue(
                "WTERMSIG", "WTERMSIG(status)\n--\n\n"
                        + "Return the signal that terminated the process.",
                status -> status & 0x7f));
        module.set("WIFSTOPPED", statusTest(
                "WIFSTOPPED", "WIFSTOPPED(status)\n--\n\n"
                        + "Return True if the process has been stopped.",
                status -> (status & 0xff) == 0x7f));
        module.set("WSTOPSIG", statusValue(
                "WSTOPSIG", "WSTOPSIG(status)\n--\n\n"
                        + "Return the signal that stopped the process.",
                status -> (status & 0xff00) >> 8));
        module.set("WCOREDUMP", statusTest(
                "WCOREDUMP", "WCOREDUMP(status)\n--\n\n"
                        + "Return True if the process dumped core.",
                status -> (status & 0x80) != 0));

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
        module.set("getpid", function(
                "getpid",
                "getpid($module, /)\n--\n\nReturn the current process id.",
                (args, kwNames) -> {
                    Arguments.none("getpid", args, kwNames);
                    return PyInt.of(ProcessHandle.current().pid());
                }));
        module.set("terminal_size", PyType.TERMINAL_SIZE);
        module.set("urandom", function(
                "urandom",
                "urandom($module, size, /)\n--\n\n"
                        + "Return a bytes object containing random bytes of length size.",
                (args, kwNames) -> {
                    Arguments.exactlyOne("urandom", args, kwNames);
                    int size = Numbers.asIndex(args[0]).intValueExact();
                    if (size < 0) {
                        throw Slots.valueError("negative argument not allowed");
                    }
                    byte[] bytes = new byte[size];
                    RANDOM.nextBytes(bytes);
                    return PyBytes.of(bytes);
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
                    PyObject[] values = Arguments.unpackKeywords(
                            "mkdir", args, kwNames,
                            new String[] {"path", "mode", "dir_fd"}, 1, 2);
                    FsPath path = fsPath(values[0], "mkdir");
                    int mode = values[1] == null ? 511 : intArgument(values[1]);
                    if (values[2] != null) {
                        throw dirFdUnavailable();
                    }
                    int actual = mode & ~umaskValue() & 0777;
                    Path target = resolve(path.text());
                    try {
                        Files.createDirectory(
                                target, PosixFilePermissions.asFileAttribute(permissions(actual)));
                    } catch (IOException e) {
                        throw failure(e, path);
                    }
                    try {
                        // `mkdir(2)` would have applied the mask at creation; `createDirectory` did its
                        // own, so the requested bits are set again after the fact. The special bits are
                        // already dropped above, as macOS drops them on a new directory.
                        Files.setPosixFilePermissions(target, permissions(actual));
                    } catch (IOException e) {
                        try {
                            Files.deleteIfExists(target);
                        } catch (IOException ignored) {
                            // The directory is left behind only if it cannot be removed either.
                        }
                        throw failure(e, path);
                    }
                    return PyNone.INSTANCE;
                }));
        module.set("umask", function(
                "umask",
                "umask($module, mask, /)\n--\n\n"
                        + "Set the current numeric umask and return the previous umask.",
                (args, kwNames) -> {
                    // The reference spells this one with its module-qualified name, not the bare one
                    // the other builtins in this file use.
                    Arguments.exactlyOne("posix.umask()", args, kwNames);
                    int requested = intArgument(args[0]);
                    int previous = umaskValue();
                    umask = requested;
                    return PyInt.of(previous);
                }));
        // The reference also takes `dir_fd` and `follow_symlinks` as keyword-only arguments; neither
        // has anything to change here, because there is no directory-fd notion and the Java call
        // follows a link the way the reference's default does — so both are left out of the signature
        // rather than accepted and ignored.
        module.set("chmod", function(
                "chmod",
                "chmod($module, path, mode, /)\n--\n\n"
                        + "Change the access permissions of a file.",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.parameters(
                            "chmod", args, kwNames, PATH_AND_MODE,
                            new PyObject[] {null, null}, 2);
                    FsPath path = fsPath(values[0], "chmod");
                    int mode = Numbers.asIndex(values[1]).intValueExact();
                    try {
                        Files.setPosixFilePermissions(resolve(path.text()), permissions(mode));
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
        module.set("readlink", function(
                "readlink",
                "readlink($module, path, *, dir_fd=None)\n--\n\n"
                        + "Return a string representing the path to which the symbolic link points.",
                (args, kwNames) -> {
                    FsPath path = single("readlink", args, kwNames);
                    Path target;
                    try {
                        target = Files.readSymbolicLink(resolve(path.text()));
                    } catch (IOException e) {
                        throw failure(e, path);
                    }
                    String text = target.toString();
                    return path.bytes()
                            ? PyBytes.of(text.getBytes(StandardCharsets.UTF_8))
                            : PyStr.of(text);
                }));
        module.set("symlink", function(
                "symlink",
                "symlink($module, src, dst, target_is_directory=False, *, dir_fd=None)\n--\n\n"
                        + "Create a symbolic link pointing to src named dst.",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.unpackKeywords(
                            "symlink", args, kwNames,
                            new String[] {"src", "dst", "target_is_directory", "dir_fd"}, 2, 3);
                    FsPath src = fsPath(values[0], "symlink", false, "src");
                    FsPath dst = fsPath(values[1], "symlink", false, "dst");
                    if (values[3] != null) {
                        throw dirFdUnavailable();
                    }
                    // `target_is_directory` is read and dropped: it exists for Windows, and this
                    // platform's symlink(2) has no such notion, which is why the reference ignores it.
                    Path target = resolve(dst.text());
                    Path linked;
                    try {
                        linked = Path.of(src.text());
                    } catch (InvalidPathException unrepresentable) {
                        throw failure(new NoSuchFileException(src.text()), src);
                    }
                    try {
                        // The target is stored exactly as given, so a relative one stays relative to
                        // the link's directory and readlink() answers the same bytes the call took.
                        Files.createSymbolicLink(target, linked);
                    } catch (IOException e) {
                        // The reference's message names both ends: 'src' -> 'dst'.
                        throw failure(e, src, dst);
                    }
                    return PyNone.INSTANCE;
                }));
        module.set("mkfifo", function(
                "mkfifo",
                "mkfifo($module, path, mode=438, *, dir_fd=None)\n--\n\n"
                        + "Create a FIFO (named pipe).",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.unpackKeywords(
                            "mkfifo", args, kwNames,
                            new String[] {"path", "mode", "dir_fd"}, 1, 2);
                    FsPath path = fsPath(values[0], "mkfifo");
                    int mode = values[1] == null ? 0666 : intArgument(values[1]);
                    if (values[2] != null) {
                        throw dirFdUnavailable();
                    }
                    Path target = resolve(path.text());
                    // A JVM cannot make a FIFO: there is no mkfifo in java.nio.file, and faking one
                    // with a regular file would make stat() lie about the type. The POSIX utility is
                    // the only honest route, so it is named here as the external dependency it is.
                    runCommand("/usr/bin/mkfifo", target.toString());
                    int actual = mode & ~umaskValue() & 07777;
                    try {
                        // chmod is used rather than a file-permission update because a FIFO may carry
                        // the sticky and setuid bits, which java.nio.file cannot spell. It refuses
                        // setgid exactly as mkfifo(2) does here, which is the behaviour to keep.
                        runCommand("/bin/chmod", Integer.toOctalString(actual), target.toString());
                    } catch (PyRaise refused) {
                        // mkfifo(2) with a setgid mode fails without creating anything; leave no FIFO
                        // behind so the failure looks the same to the caller.
                        try {
                            Files.deleteIfExists(target);
                        } catch (IOException ignored) {
                            // Nothing more can be done about a file that will not go away.
                        }
                        throw refused;
                    }
                    return PyNone.INSTANCE;
                }));
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
        module.set("utime", function(
                "utime",
                "utime($module, path, times=None, *, ns=None, dir_fd=None, follow_symlinks=True)\n"
                        + "--\n\nSet the access and modified time of path.",
                (args, kwNames) -> {
                    PyObject[] values = Arguments.parameters(
                            "utime", args, kwNames,
                            new String[] {"path", "times", "ns", "dir_fd", "follow_symlinks"},
                            new PyObject[] {null, PyNone.INSTANCE, PyNone.INSTANCE,
                                    PyNone.INSTANCE, PyBool.TRUE},
                            1);
                    FsPath path = fsPath(values[0], "utime");
                    if (values[3] != null && values[3] != PyNone.INSTANCE) {
                        throw Slots.notImplemented("utime() with dir_fd");
                    }
                    if (!Slots.truth(values[4])) {
                        // `_have_functions` is empty, so `os.supports_follow_symlinks` is too and the
                        // reference itself answers this way on a platform without utimensat.
                        throw Slots.notImplemented("utime() with follow_symlinks=False");
                    }
                    PyObject times = values[1] == null ? PyNone.INSTANCE : values[1];
                    PyObject ns = values[2] == null ? PyNone.INSTANCE : values[2];
                    FileTime atime;
                    FileTime mtime;
                    if (ns != PyNone.INSTANCE) {
                        if (times != PyNone.INSTANCE) {
                            throw Slots.valueError(
                                    "utime: you may specify either 'times' or 'ns' but not both");
                        }
                        PyObject[] pair = pair(ns, "ns");
                        atime = FileTime.from(Numbers.asIndex(pair[0]).longValue(),
                                TimeUnit.NANOSECONDS);
                        mtime = FileTime.from(Numbers.asIndex(pair[1]).longValue(),
                                TimeUnit.NANOSECONDS);
                    } else if (times != PyNone.INSTANCE) {
                        PyObject[] pair = pair(times, "times");
                        atime = seconds(pair[0]);
                        mtime = seconds(pair[1]);
                    } else {
                        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
                        atime = now;
                        mtime = now;
                    }
                    Path target = resolve(path.text());
                    BasicFileAttributeView view = Files.getFileAttributeView(
                            target, BasicFileAttributeView.class);
                    try {
                        if (isFifo(target)) {
                            // `setTimes` opens the path for attribute access, and opening a FIFO with no
                            // writer blocks until one appears — while the reference's `utimensat` never
                            // opens it. `touch` is the same syscall without the open.
                            touchTimes(target, atime, mtime);
                        } else {
                            view.setTimes(mtime, atime, null);
                        }
                    } catch (IOException e) {
                        throw failure(e, path);
                    }
                    return PyNone.INSTANCE;
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

    /** {@code waitstatus_to_exitcode}: the exit code, or the two statuses CPython refuses. */
    private static int exitCode(int status) {
        if (((byte) ((status & 0x7f) + 1) >> 1) > 0) {
            return -(status & 0x7f);
        }
        if ((status & 0x7f) == 0) {
            return (status & 0xff00) >> 8;
        }
        if ((status & 0xff) == 0x7f) {
            throw Slots.valueError("process is stopped");
        }
        throw Slots.valueError("invalid process status");
    }

    /** One of the wait-status macros that answers a bool. */
    private static PyNativeFunction statusTest(
            String name, String doc, java.util.function.IntPredicate test) {
        return function(name, doc, (args, kwNames) -> {
            Arguments.exactlyOne(name, args, kwNames);
            return PyBool.of(test.test(Numbers.asIndex(args[0]).intValue()));
        });
    }

    /** One of the wait-status macros that answers an integer. */
    private static PyNativeFunction statusValue(
            String name, String doc, java.util.function.IntUnaryOperator decoder) {
        return function(name, doc, (args, kwNames) -> {
            Arguments.exactlyOne(name, args, kwNames);
            return PyInt.of(decoder.applyAsInt(Numbers.asIndex(args[0]).intValue()));
        });
    }

    /** One positional argument, which is what every single-path call takes. */
    private static FsPath single(String name, PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.parameters(name, args, kwNames, PATH, NO_DEFAULTS, 1);
        return fsPath(values[0], name);
    }

    /** The {@code (atime, mtime)} pair {@code utime} takes, or the reference's refusal. */
    private static PyObject[] pair(PyObject value, String parameter) {
        if (value instanceof PyTuple tuple && tuple.size() == 2) {
            return tuple.getArray();
        }
        throw Slots.typeError(
                "utime: '%s' must be either a tuple of two ints or None", parameter);
    }

    /** One of the two seconds {@code utime(times=...)} takes, as the nanoseconds it sets. */
    private static FileTime seconds(PyObject value) {
        Double seconds = Numbers.floatOf(value);
        if (seconds == null) {
            throw Slots.typeError("utime: 'times' must be either a tuple of two ints or None");
        }
        // `utimes` reads the float as a `timeval`, so a fraction finer than the field is dropped rather
        // than rounded — the reference's own comment says os.utime() rounds towards minus infinity.
        return FileTime.from((long) Math.floor(seconds * 1_000_000_000L), TimeUnit.NANOSECONDS);
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
        // `follow_symlinks` is keyword-only for `stat` and absent from `lstat`; `dir_fd` is
        // keyword-only for both. `path` is the one positional argument in either spelling.
        PyObject[] values = follow
                ? Arguments.unpackKeywords(function, args, kwNames,
                        new String[] {"path", "dir_fd", "follow_symlinks"}, 1, 1)
                : Arguments.unpackKeywords(function, args, kwNames,
                        new String[] {"path", "dir_fd"}, 1, 1);
        PyObject pathValue = values[0];
        PyObject dirFd = values[1];
        PyObject followValue = follow ? values[2] : null;
        boolean follows = follow && (followValue == null || Slots.truth(followValue));

        // Only `stat` reads an integer as a descriptor; `lstat` has no descriptor form, which is why
        // its complaint leaves the integer out of the list of things it accepts.
        if (follow && isInteger(pathValue)) {
            int fd = fileDescriptor(pathValue);
            if (!follows) {
                throw Slots.valueError("%s: cannot use fd and follow_symlinks together", function);
            }
            if (dirFd != null) {
                throw Slots.valueError("%s: can't specify dir_fd without matching path", function);
            }
            // The reference reads `-1` as "no descriptor here" and falls back to the path `"-1"`, so
            // that a negative close() result can be handed straight to os.stat(). The failure it
            // reports names the integer itself, not a quoted path.
            if (fd == -1) {
                Path candidate = resolve("-1");
                if (!Files.exists(candidate)) {
                    throw PyRaise.of(PyOSError.create(
                            ExceptionTypes.OS_ERROR,
                            new PyObject[] {
                                PyInt.of(2), PyStr.of("No such file or directory"), PyInt.of(-1),
                            }));
                }
                return statPath(new FsPath("-1", false), true);
            }
            if (!Fds.isOpen(fd)) {
                throw PyRaise.of(PyOSError.create(
                        ExceptionTypes.OS_ERROR,
                        new PyObject[] {
                            PyInt.of(9), PyStr.of("Bad file descriptor"), PyInt.of(fd),
                        }));
            }
            return descriptorStat(fd);
        }
        if (dirFd != null) {
            throw dirFdUnavailable();
        }
        FsPath path = fsPath(pathValue, function, follow);
        return statPath(path, follows);
    }

    /** Whether a value is an integer the way the reference's path converter reads one. */
    private static boolean isInteger(PyObject value) {
        return value instanceof PyInt || value instanceof PyBool;
    }

    /**
     * An integer path argument read as a C {@code int} descriptor, with the reference's two range
     * complaints: it names which end of the range was crossed rather than a generic overflow.
     */
    private static int fileDescriptor(PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw Slots.overflowError("fd is greater than maximum");
        }
        if (number.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0) {
            throw Slots.overflowError("fd is less than minimum");
        }
        return number.intValue();
    }

    /**
     * {@code stat} over a descriptor this runtime's {@link Fds} table already knows.
     *
     * <p>A real file answers from the file itself. A pipe end or the null device has no file behind
     * it, so the mode names which it is and every other field is the empty value: 0o020000 (character
     * device) or 0o010000 (FIFO), with 0o666.
     */
    private static PyObject descriptorStat(int fd) {
        if (Fds.isFile(fd)) {
            return statPath(new FsPath(Fds.path(fd).toString(), false), true);
        }
        long size = Fds.size(fd);
        long mode = (Fds.isDevnull(fd) ? 8192 : 4096) | 438;
        return PyStatResult.of(
                new PyObject[] {
                    PyInt.of(mode),
                    PyInt.of(0),
                    PyInt.of(0),
                    PyInt.of(1),
                    PyInt.of(0),
                    PyInt.of(0),
                    PyInt.of(size),
                    PyInt.of(0),
                    PyInt.of(0),
                    PyInt.of(0),
                },
                0, 0, 0, 0, 0);
    }

    /** {@code stat} over a path this module already spelled, which is also what a {@code DirEntry} asks. */
    static PyObject statPath(FsPath path, boolean follows) {
        Path target;
        try {
            target = resolve(path.text());
        } catch (InvalidPathException unrepresentable) {
            // A path with a lone surrogate is one the reference encodes back to the byte it stands for
            // and then asks the file system about. The JVM cannot name that file, so the honest answer
            // is the one the file system gives for a name that is not there.
            throw failure(new NoSuchFileException(path.text()), path);
        }
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
                        // The ten items are the raw `stat` fields: whole seconds, as the C `time_t`
                        // holds them. The fractional part is what `st_atime` and `st_atime_ns` add.
                        PyInt.of(atime / 1_000_000_000L),
                        PyInt.of(mtime / 1_000_000_000L),
                        PyInt.of(ctime / 1_000_000_000L),
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

    /**
     * The nine permission bits of {@code mode}, in the form {@link Files} writes them.
     *
     * <p>The setuid, setgid and sticky bits have no counterpart here and are dropped: a JVM cannot set
     * them, and {@code chmod} is only ever asked for the rwx triad by the standard library.
     */
    private static Set<PosixFilePermission> permissions(int mode) {
        EnumSet<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        };
        for (int i = 0; i < order.length; i++) {
            if ((mode & (1 << (8 - i))) != 0) {
                perms.add(order[i]);
            }
        }
        return perms;
    }

    /**
     * The current mask, measured on first use.
     *
     * <p>The probe creates a directory asking for 0o777 and reads back what the kernel allowed; the
     * bits that vanished are the process's own mask. Doing it this way costs no external command and
     * is as accurate as the file system is, and the probe directory is removed again.
     */
    private static synchronized int umaskValue() {
        if (umask < 0) {
            umask = measureUmask();
        }
        return umask;
    }

    private static int measureUmask() {
        Path probe = null;
        Path asked = null;
        try {
            probe = Files.createTempDirectory("jy3-umask");
            asked = probe.resolve("probe");
            Files.createDirectory(asked, PosixFilePermissions.asFileAttribute(permissions(0777)));
            int granted = ((Number) Files.getAttribute(asked, "unix:mode")).intValue() & 0777;
            return 0777 & ~granted;
        } catch (IOException | RuntimeException unmeasurable) {
            // A file system that cannot answer leaves the usual value rather than a guess of zero.
            return 0022;
        } finally {
            if (asked != null) {
                try {
                    Files.deleteIfExists(asked);
                } catch (IOException ignored) {
                    // Best effort: the temporary directory goes away with the process anyway.
                }
            }
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // As above.
                }
            }
        }
    }

    /** The reference's refusal where a platform has no directory-relative calls. */
    private static PyRaise dirFdUnavailable() {
        return PyRaise.raise(
                ExceptionTypes.NOT_IMPLEMENTED_ERROR, "dir_fd unavailable on this platform");
    }

    /**
     * Whether {@code path} names a FIFO, the one file type whose attribute view cannot be opened.
     *
     * <p>{@code utime} is the only caller: setting times through Java opens the path, which a FIFO
     * without a writer holds open forever.
     */
    private static boolean isFifo(Path path) {
        try {
            Object mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return mode instanceof Number number && (number.intValue() & 0170000) == 0010000;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Set both times on a FIFO through {@code touch(1)}, whose {@code utimensat} does not open it.
     *
     * <p>{@code -d} takes an ISO 8601 instant with an explicit {@code Z} and nine fractional digits, so
     * the nanoseconds the caller asked for survive exactly, as they do on a regular file.
     */
    private static void touchTimes(Path path, FileTime atime, FileTime mtime) {
        runCommand("/usr/bin/touch", "-a", "-d", stamp(atime), path.toString());
        runCommand("/usr/bin/touch", "-m", "-d", stamp(mtime), path.toString());
    }

    /** {@code YYYY-MM-DDThh:mm:SS.nnnnnnnnnZ}, the spelling {@code touch -d} reads back exactly. */
    private static String stamp(FileTime time) {
        java.time.Instant instant = time.toInstant();
        java.time.LocalDateTime moment = java.time.LocalDateTime.ofEpochSecond(
                instant.getEpochSecond(), 0, java.time.ZoneOffset.UTC);
        return String.format(
                "%04d-%02d-%02dT%02d:%02d:%02d.%09dZ",
                moment.getYear(), moment.getMonthValue(), moment.getDayOfMonth(),
                moment.getHour(), moment.getMinute(), moment.getSecond(), instant.getNano());
    }

    /**
     * Run one POSIX command and turn its refusal into the exception the reference would have raised.
     *
     * <p>Two operations here have no JDK counterpart, so a utility is the implementation:
     * {@code mkfifo} (no FIFO API in {@code java.nio.file}) and setting a FIFO's times ({@code setTimes}
     * would open it and block). The utility's message ends in the C library's own wording, which is
     * exactly what {@link #errno} maps, so the error a program sees keeps the errno the reference's
     * system call failure would have carried.
     */
    private static void runCommand(String... command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException missingUtility) {
            throw PyRaise.raise(
                    ExceptionTypes.NOT_IMPLEMENTED_ERROR,
                    "the " + command[0] + " utility is needed on this platform");
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
        } catch (IOException failed) {
            process.destroyForcibly();
            throw Slots.osError("cannot run %s: %s", command[0], failed.getMessage());
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw Slots.osError("cannot run %s: %s", command[0], interrupted.getMessage());
        }
        if (process.exitValue() == 0) {
            return;
        }
        String text = output.strip();
        int colon = text.lastIndexOf(": ");
        String reason = colon >= 0 ? text.substring(colon + 2) : text;
        // The reference's mkfifo failure carries no file name, so neither does this one.
        int number = errno(new FileSystemException(null, null, reason));
        throw PyRaise.of(PyOSError.create(
                ExceptionTypes.OS_ERROR,
                new PyObject[] {PyInt.of(number), PyStr.of(strerror(number))}));
    }

    static Path resolve(String path) {
        try {
            return cwd.resolve(path);
        } catch (InvalidPathException unrepresentable) {
            // Names carrying a lone surrogate cannot be spelled to this JVM's file system at all. The
            // reference encodes them back to the byte the surrogate stands for and asks the file system,
            // whose answer for such a name is "not there"; that is what every caller here reports.
            throw failure(new NoSuchFileException(path), new FsPath(path, false));
        }
    }

    /**
     * A caller's path with this module's working directory applied.
     *
     * <p>{@code chdir} moves {@link #cwd} rather than the process, so every other way into the file
     * system — the {@code open} builtin included — has to ask here for the same answer. An absolute
     * path is returned unchanged, which is what {@link Path#resolve(String)} does.
     */
    static String absolute(String path) {
        try {
            return cwd.resolve(path).toString();
        } catch (InvalidPathException unrepresentable) {
            // A name carrying a lone surrogate is one CPython joins lexically and only then fails to
            // encode when it reaches the file system. The JVM refuses to name it at all, so answer the
            // same lexical join here and let the open itself report the missing file.
            return path.startsWith("/") ? path : cwd + "/" + path;
        }
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

    static FsPath fsPath(PyObject value, String function) {
        return fsPath(value, function, true, "path");
    }

    /**
     * The same conversion, with the reference's two spellings of the complaint.
     *
     * <p>A call that also takes a descriptor ({@code stat}, {@code open}) says
     * {@code "string, bytes, os.PathLike or integer"}; one that does not ({@code lstat}) leaves the
     * integer out, and the punctuation differs too, because CPython's two format strings are not the
     * same sentence. A call with more than one path names the argument it refused ({@code src},
     * {@code dst}).
     */
    static FsPath fsPath(PyObject value, String function, boolean descriptor) {
        return fsPath(value, function, descriptor, "path");
    }

    static FsPath fsPath(PyObject value, String function, boolean descriptor, String parameter) {
        value = Open.fspath(value);
        if (value instanceof PyStr text) {
            return new FsPath(text.getValue(), false);
        }
        if (value instanceof PyBytes bytes) {
            // Java has no surrogates to escape; a well-formed name is all this milestone promises.
            return new FsPath(new String(bytes.getValue(), StandardCharsets.UTF_8), true);
        }
        throw Slots.typeError(
                "%s: %s should be %s, not %s",
                function,
                parameter,
                descriptor ? "string, bytes, os.PathLike or integer" : "string, bytes or os.PathLike",
                value.getType().name());
    }

    /**
     * {@code kill(2)} over a {@link ProcessHandle}.
     *
     * <p>The JVM has no way to hand an arbitrary signal to another process: {@link ProcessHandle} offers
     * exactly two requests, {@code destroy()} (the polite one, which macOS delivers as {@code SIGTERM}) and
     * {@code destroyForcibly()} ({@code SIGKILL}). Signal 0 is the reference's existence probe and is
     * answered from {@code isAlive()}. Everything else — {@code SIGINT}, {@code SIGHUP}, the job-control
     * signals — names its number and is refused rather than silently mapped onto {@code SIGTERM}, because
     * a program that asked for {@code SIGHUP} and got a terminate would see the wrong exit status.
     */
    private static PyObject processSignal(long pid, int signal) {
        ProcessHandle child = ProcessHandle.of(pid).orElse(null);
        if (child == null || !child.isAlive()) {
            // `PyErr_SetFromErrno(PyExc_ProcessLookupError)` over ESRCH.
            throw PyRaise.of(PyOSError.create(
                    ExceptionTypes.OS_ERROR,
                    new PyObject[] {PyInt.of(3), PyStr.of(strerror(3))}));
        }
        switch (signal) {
            case 0 -> {
                return PyNone.INSTANCE;
            }
            case 9 -> {
                child.destroyForcibly();
                return PyNone.INSTANCE;
            }
            case 15 -> {
                child.destroy();
                return PyNone.INSTANCE;
            }
            default -> throw Slots.notImplemented(
                    "os.kill() can deliver only signal 0, SIGTERM (15) and SIGKILL (9): the JVM has no "
                            + "arbitrary signal delivery, so signal " + signal + " has no counterpart");
        }
    }

    static PyRaise failure(Exception error, FsPath path) {
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
     * The same failure for a call that takes two paths, whose message names both: {@code 'src' -> 'dst'}.
     *
     * <p>{@code symlink} is the only such call, and its refusal is spelled with the two file names
     * after {@code strerror} — CPython fills {@code filename} and {@code filename2}, which display as
     * one arrow.
     */
    static PyRaise failure(Exception error, FsPath first, FsPath second) {
        int number = errno(error);
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.OS_ERROR,
                new PyObject[] {
                    PyInt.of(number),
                    PyStr.of(strerror(number)),
                    first.bytes()
                            ? PyBytes.of(first.text().getBytes(StandardCharsets.UTF_8))
                            : PyStr.of(first.text()),
                    PyNone.INSTANCE,
                    second.bytes()
                            ? PyBytes.of(second.text().getBytes(StandardCharsets.UTF_8))
                            : PyStr.of(second.text()),
                }));
    }

    /**
     * {@code EAGAIN}: the answer {@code os.read} and {@code os.write} give a non-blocking descriptor
     * that has nothing to give, or no room to take.
     *
     * <p>The two descriptors say "would block" differently at the layer below — a read comes back
     * {@code null} and a write comes back zero — and both report the same two-argument error here, which
     * is the shape {@code os} has: no {@code characters_written}, because none of the bytes moved.
     */
    private static PyRaise wouldBlock() {
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.BLOCKING_IO_ERROR,
                new PyObject[] {PyInt.of(35), PyStr.of("Resource temporarily unavailable")}));
    }

    /** A {@code Py_off_t} argument: what the reference reads as a C long and refuses beyond it. */
    private static long longArgument(PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() >= Long.SIZE) {
            throw Slots.overflowError("Python int too large to convert to C long");
        }
        return number.longValue();
    }

    /** A C {@code int} argument: descriptor numbers are read as one, so an oversized number is
     * {@code OverflowError} rather than a silently truncated descriptor. */
    private static int intArgument(PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() >= Integer.SIZE) {
            throw Slots.overflowError("Python int too large to convert to C int");
        }
        return number.intValue();
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
        if (error instanceof NotLinkException) {
            // `readlink` on something that is not a link: the reference reports EINVAL.
            return 22;
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
    record FsPath(String text, boolean bytes) {
    }
}
