package org.python.core;

/**
 * The {@code errno} module: the operating system's error numbers and the names they go by.
 *
 * <p>CPython's {@code errno.py} is a generated table of constants plus one dictionary; nothing in it is
 * computed at run time. The table here is the reference's own macOS table — the platform this runtime is
 * measured on — so a name the reference does not define is absent rather than zero, which is what
 * {@code hasattr(errno, "EWOULDBLOCK")} and {@code errno.errorcode} both depend on.
 *
 * <p>{@code errorcode} is built from the constants rather than listed a second time, and {@code 35} is
 * filed once: {@code EAGAIN} and {@code EWOULDBLOCK} are the same number, and the reference's dictionary
 * keeps the first name it wrote, which the table below preserves.
 */
final class ErrnoModule {

    private static final String DOC =
            "This module makes available standard errno system symbols.\n"
            + "\n"
            + "The value of each symbol is the corresponding integer value,\n"
            + "e.g., on most Unix systems, errno.ENOENT equals the integer 2.\n"
            + "The dictionary errno.errorcode maps numeric codes to symbol names,\n"
            + "e.g., errno.errorcode[errno.ENOENT] equals 'ENOENT'.  Symbols that\n"
            + "are not relevant to the underlying system are not defined.";

    /**
     * The reference's {@code errno} table for macOS, in the order {@code Lib/errno.py} writes it.
     *
     * <p>Each row is a name and its number. {@code EWOULDBLOCK} shares {@code EAGAIN}'s 35 and is kept
     * out of {@code errorcode} below for the same reason the reference's dictionary answers
     * {@code 'EAGAIN'} for it.
     */
    private static final Object[][] CONSTANTS = {
        {"E2BIG", 7}, {"EACCES", 13}, {"EADDRINUSE", 48}, {"EADDRNOTAVAIL", 49},
        {"EAFNOSUPPORT", 47}, {"EAGAIN", 35}, {"EALREADY", 37}, {"EAUTH", 80},
        {"EBADARCH", 86}, {"EBADEXEC", 85}, {"EBADF", 9}, {"EBADMACHO", 88},
        {"EBADMSG", 94}, {"EBADRPC", 72}, {"EBUSY", 16}, {"ECANCELED", 89},
        {"ECHILD", 10}, {"ECONNABORTED", 53}, {"ECONNREFUSED", 61}, {"ECONNRESET", 54},
        {"EDEADLK", 11}, {"EDESTADDRREQ", 39}, {"EDEVERR", 83}, {"EDOM", 33},
        {"EDQUOT", 69}, {"EEXIST", 17}, {"EFAULT", 14}, {"EFBIG", 27},
        {"EFTYPE", 79}, {"EHOSTDOWN", 64}, {"EHOSTUNREACH", 65}, {"EIDRM", 90},
        {"EILSEQ", 92}, {"EINPROGRESS", 36}, {"EINTR", 4}, {"EINVAL", 22},
        {"EIO", 5}, {"EISCONN", 56}, {"EISDIR", 21}, {"ELOOP", 62},
        {"EMFILE", 24}, {"EMLINK", 31}, {"EMSGSIZE", 40}, {"EMULTIHOP", 95},
        {"ENAMETOOLONG", 63}, {"ENEEDAUTH", 81}, {"ENETDOWN", 50}, {"ENETRESET", 52},
        {"ENETUNREACH", 51}, {"ENFILE", 23}, {"ENOATTR", 93}, {"ENOBUFS", 55},
        {"ENODATA", 96}, {"ENODEV", 19}, {"ENOENT", 2}, {"ENOEXEC", 8},
        {"ENOLCK", 77}, {"ENOLINK", 97}, {"ENOMEM", 12}, {"ENOMSG", 91},
        {"ENOPOLICY", 103}, {"ENOPROTOOPT", 42}, {"ENOSPC", 28}, {"ENOSR", 98},
        {"ENOSTR", 99}, {"ENOSYS", 78}, {"ENOTBLK", 15}, {"ENOTCAPABLE", 107},
        {"ENOTCONN", 57}, {"ENOTDIR", 20}, {"ENOTEMPTY", 66}, {"ENOTRECOVERABLE", 104},
        {"ENOTSOCK", 38}, {"ENOTSUP", 45}, {"ENOTTY", 25}, {"ENXIO", 6},
        {"EOPNOTSUPP", 102}, {"EOVERFLOW", 84}, {"EOWNERDEAD", 105}, {"EPERM", 1},
        {"EPFNOSUPPORT", 46}, {"EPIPE", 32}, {"EPROCLIM", 67}, {"EPROCUNAVAIL", 76},
        {"EPROGMISMATCH", 75}, {"EPROGUNAVAIL", 74}, {"EPROTO", 100},
        {"EPROTONOSUPPORT", 43}, {"EPROTOTYPE", 41}, {"EPWROFF", 82}, {"EQFULL", 106},
        {"ERANGE", 34}, {"EREMOTE", 71}, {"EROFS", 30}, {"ERPCMISMATCH", 73},
        {"ESHLIBVERS", 87}, {"ESHUTDOWN", 58}, {"ESOCKTNOSUPPORT", 44}, {"ESPIPE", 29},
        {"ESRCH", 3}, {"ESTALE", 70}, {"ETIME", 101}, {"ETIMEDOUT", 60},
        {"ETOOMANYREFS", 59}, {"ETXTBSY", 26}, {"EUSERS", 68}, {"EWOULDBLOCK", 35},
        {"EXDEV", 18},
    };

    private ErrnoModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("errno");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "errno");
        PyDict errorcode = PyDict.empty();
        for (Object[] row : CONSTANTS) {
            String name = (String) row[0];
            int number = (Integer) row[1];
            module.set(name, PyInt.of(number));
            // The first name written for a number is the one the reference's dictionary keeps, and
            // EWOULDBLOCK is the second for 35.
            if (!name.equals("EWOULDBLOCK")) {
                errorcode.put(PyInt.of(number), PyStr.interned(name));
            }
        }
        module.set("errorcode", errorcode);
        return module;
    }
}
