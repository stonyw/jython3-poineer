package org.python.core;

import java.math.BigInteger;

/**
 * {@code _stat}: the platform's file-mode constants and the calls that read them.
 *
 * <p>CPython's {@code _stat} is the C half of the {@code stat} module: it carries the numbers the C
 * library's {@code sys/stat.h} defines, which differ between platforms, and four kinds of helper —
 * {@code S_IMODE}, {@code S_IFMT}, the {@code S_IS*} predicates and {@code filemode}. The Python file
 * {@code stat.py} spells out fallbacks for everything and then ends with
 * {@code try: from _stat import *}, so the C values win when this module exists and the fallbacks stand
 * when it does not. That fallback is why the module was missing quietly: {@code import stat} worked, but
 * four macOS constants came out with the wrong numbers, {@code filemode} was the Python spelling, and
 * {@code test_stat} skipped every C-stat test with "need _stat extension".
 *
 * <p>The values below are the ones this runtime's reference platform answers with — macOS, read from
 * {@code _stat} itself rather than copied from another system's headers. {@code SF_SETTABLE} is
 * {@code 0x3fff0000} rather than the {@code 0xffff0000} the Python fallback carries, {@code S_IFWHT} is
 * a real value, and {@code SF_SUPPORTED} and {@code SF_SYNTHETIC} exist at all.
 *
 * <p>{@code S_ISDOOR} and {@code S_ISPORT} are always false, as the C module's are where the platform
 * has no such file types; {@code S_ISWHT} answers for macOS's whiteouts. Every entry point reads its
 * mode as a C {@code unsigned short}, which is why a mode above {@code 0xFFFF} is refused rather than
 * masked.
 */
final class StatModule {

    private static final String DOC =
            "S_IFMT_: file type bits\n"
                    + "S_IFDIR: directory\n"
                    + "S_IFCHR: character device\n"
                    + "S_IFBLK: block device\n"
                    + "S_IFREG: regular file\n"
                    + "S_IFIFO: FIFO (named pipe)\n"
                    + "S_IFLNK: symbolic link\n"
                    + "S_IFSOCK: socket file\n"
                    + "S_IFDOOR: door\n"
                    + "S_IFPORT: event port\n"
                    + "S_IFWHT: whiteout\n"
                    + "\n"
                    + "S_ISUID: set UID bit\n"
                    + "S_ISGID: set GID bit\n"
                    + "S_ENFMT: file locking enforcement\n"
                    + "S_ISVTX: sticky bit\n"
                    + "S_IREAD: Unix V7 synonym for S_IRUSR\n"
                    + "S_IWRITE: Unix V7 synonym for S_IWUSR\n"
                    + "S_IEXEC: Unix V7 synonym for S_IXUSR\n"
                    + "S_IRWXU: mask for owner permissions\n"
                    + "S_IRUSR: read by owner\n"
                    + "S_IWUSR: write by owner\n"
                    + "S_IXUSR: execute by owner\n"
                    + "S_IRWXG: mask for group permissions\n"
                    + "S_IRGRP: read by group\n"
                    + "S_IWGRP: write by group\n"
                    + "S_IXGRP: execute by group\n"
                    + "S_IRWXO: mask for others (not in group) permissions\n"
                    + "S_IROTH: read by others\n"
                    + "S_IWOTH: write by others\n"
                    + "S_IXOTH: execute by others\n"
                    + "\n"
                    + "UF_SETTABLE: owner settable flags\n"
                    + "UF_NODUMP: do not dump file\n"
                    + "UF_IMMUTABLE: file may not be changed\n"
                    + "UF_APPEND: file may only be appended to\n"
                    + "UF_OPAQUE: directory is opaque when viewed through a union stack\n"
                    + "UF_NOUNLINK: file may not be renamed or deleted\n"
                    + "UF_COMPRESSED: macOS: file is compressed\n"
                    + "UF_TRACKED: macOS: used for handling document IDs\n"
                    + "UF_DATAVAULT: macOS: entitlement needed for I/O\n"
                    + "UF_HIDDEN: macOS: file should not be displayed\n"
                    + "SF_SETTABLE: superuser settable flags\n"
                    + "SF_ARCHIVED: file may be archived\n"
                    + "SF_IMMUTABLE: file may not be changed\n"
                    + "SF_APPEND: file may only be appended to\n"
                    + "SF_NOUNLINK: file may not be renamed or deleted\n"
                    + "SF_SNAPSHOT: file is a snapshot file\n"
                    + "SF_FIRMLINK: macOS: file is a firmlink\n"
                    + "SF_DATALESS: macOS: file is a dataless object\n"
                    + "SF_SUPPORTED: macOS: supported superuser flags\n"
                    + "SF_SYNTHETIC: macOS: synthetic superuser flags\n";

    /** The mask {@code S_IFMT} keeps: {@code 0o170000}. */
    private static final int FILE_TYPE = 0170000;

    private StatModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_stat");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_stat");

        // Indices into the tuple `os.stat` returns, in struct order.
        module.set("ST_MODE", PyInt.of(0));
        module.set("ST_INO", PyInt.of(1));
        module.set("ST_DEV", PyInt.of(2));
        module.set("ST_NLINK", PyInt.of(3));
        module.set("ST_UID", PyInt.of(4));
        module.set("ST_GID", PyInt.of(5));
        module.set("ST_SIZE", PyInt.of(6));
        module.set("ST_ATIME", PyInt.of(7));
        module.set("ST_MTIME", PyInt.of(8));
        module.set("ST_CTIME", PyInt.of(9));

        // File type bits. `S_IFDOOR` and `S_IFPORT` are zero because this platform has no such
        // files, which is the same answer the C module gives where the macros are absent.
        module.set("S_IFDIR", PyInt.of(0040000));
        module.set("S_IFCHR", PyInt.of(0020000));
        module.set("S_IFBLK", PyInt.of(0060000));
        module.set("S_IFREG", PyInt.of(0100000));
        module.set("S_IFIFO", PyInt.of(0010000));
        module.set("S_IFLNK", PyInt.of(0120000));
        module.set("S_IFSOCK", PyInt.of(0140000));
        module.set("S_IFDOOR", PyInt.of(0));
        module.set("S_IFPORT", PyInt.of(0));
        module.set("S_IFWHT", PyInt.of(0160000));

        // Permission bits, from POSIX.
        module.set("S_ISUID", PyInt.of(04000));
        module.set("S_ISGID", PyInt.of(02000));
        module.set("S_ENFMT", PyInt.of(02000));
        module.set("S_ISVTX", PyInt.of(01000));
        module.set("S_IREAD", PyInt.of(00400));
        module.set("S_IWRITE", PyInt.of(00200));
        module.set("S_IEXEC", PyInt.of(00100));
        module.set("S_IRWXU", PyInt.of(00700));
        module.set("S_IRUSR", PyInt.of(00400));
        module.set("S_IWUSR", PyInt.of(00200));
        module.set("S_IXUSR", PyInt.of(00100));
        module.set("S_IRWXG", PyInt.of(00070));
        module.set("S_IRGRP", PyInt.of(00040));
        module.set("S_IWGRP", PyInt.of(00020));
        module.set("S_IXGRP", PyInt.of(00010));
        module.set("S_IRWXO", PyInt.of(00007));
        module.set("S_IROTH", PyInt.of(00004));
        module.set("S_IWOTH", PyInt.of(00002));
        module.set("S_IXOTH", PyInt.of(00001));

        // The user-settable file flags.
        module.set("UF_SETTABLE", PyInt.of(0x0000ffffL));
        module.set("UF_NODUMP", PyInt.of(0x00000001L));
        module.set("UF_IMMUTABLE", PyInt.of(0x00000002L));
        module.set("UF_APPEND", PyInt.of(0x00000004L));
        module.set("UF_OPAQUE", PyInt.of(0x00000008L));
        module.set("UF_NOUNLINK", PyInt.of(0x00000010L));
        module.set("UF_COMPRESSED", PyInt.of(0x00000020L));
        module.set("UF_TRACKED", PyInt.of(0x00000040L));
        module.set("UF_DATAVAULT", PyInt.of(0x00000080L));
        module.set("UF_HIDDEN", PyInt.of(0x00008000L));

        // The superuser-settable file flags. `SF_SETTABLE` is the macOS mask, not the Python
        // fallback's wider one; `SF_SUPPORTED` and `SF_SYNTHETIC` are only known to the C module,
        // and `SF_RESTRICTED` is deliberately absent because this platform's C module has no such
        // name — `stat.py` supplies it, and `from _stat import *` leaves it standing.
        module.set("SF_SETTABLE", PyInt.of(0x3fff0000L));
        module.set("SF_ARCHIVED", PyInt.of(0x00010000L));
        module.set("SF_IMMUTABLE", PyInt.of(0x00020000L));
        module.set("SF_APPEND", PyInt.of(0x00040000L));
        module.set("SF_NOUNLINK", PyInt.of(0x00100000L));
        module.set("SF_SNAPSHOT", PyInt.of(0x00200000L));
        module.set("SF_FIRMLINK", PyInt.of(0x00800000L));
        module.set("SF_DATALESS", PyInt.of(0x40000000L));
        module.set("SF_SUPPORTED", PyInt.of(0x009f0000L));
        module.set("SF_SYNTHETIC", PyInt.of(0xc0000000L));

        module.set("S_IMODE", function("S_IMODE",
                "S_IMODE($module, mode, /)\n--\n\nReturn the portion of the file's mode that can be"
                        + " set by os.chmod().",
                (args, kwNames) -> {
                    Arguments.exactly("S_IMODE", args, kwNames, 1);
                    return PyInt.of(mode(args[0]) & 07777);
                }));
        module.set("S_IFMT", function("S_IFMT",
                "S_IFMT($module, mode, /)\n--\n\nReturn the portion of the file's mode that describes"
                        + " the file type.",
                (args, kwNames) -> {
                    Arguments.exactly("S_IFMT", args, kwNames, 1);
                    return PyInt.of(mode(args[0]) & FILE_TYPE);
                }));
        is(module, "S_ISDIR", "directory", 0040000);
        is(module, "S_ISCHR", "character special device file", 0020000);
        is(module, "S_ISBLK", "block special device file", 0060000);
        is(module, "S_ISREG", "regular file", 0100000);
        is(module, "S_ISFIFO", "FIFO (named pipe)", 0010000);
        is(module, "S_ISLNK", "symbolic link", 0120000);
        is(module, "S_ISSOCK", "socket file", 0140000);
        is(module, "S_ISWHT", "whiteout", 0160000);
        // No door or event port exists here, and the C module answers False where its platform has
        // no such file type either.
        module.set("S_ISDOOR", function("S_ISDOOR",
                "S_ISDOOR($module, mode, /)\n--\n\nReturn True if mode is from a door.",
                (args, kwNames) -> {
                    Arguments.exactly("S_ISDOOR", args, kwNames, 1);
                    mode(args[0]);
                    return PyBool.FALSE;
                }));
        module.set("S_ISPORT", function("S_ISPORT",
                "S_ISPORT($module, mode, /)\n--\n\nReturn True if mode is from an event port.",
                (args, kwNames) -> {
                    Arguments.exactly("S_ISPORT", args, kwNames, 1);
                    mode(args[0]);
                    return PyBool.FALSE;
                }));
        module.set("filemode", function("filemode",
                "filemode($module, mode, /)\n--\n\nConvert a file's mode to a string of the form"
                        + " '-rwxrwxrwx'.",
                (args, kwNames) -> {
                    Arguments.exactly("filemode", args, kwNames, 1);
                    return PyStr.of(fileMode(mode(args[0])));
                }));
        return module;
    }

    private static void is(PyModule module, String name, String what, int type) {
        module.set(name, function(name,
                name + "($module, mode, /)\n--\n\nReturn True if mode is from a " + what + ".",
                (args, kwNames) -> {
                    Arguments.exactly(name, args, kwNames, 1);
                    return PyBool.of((mode(args[0]) & FILE_TYPE) == type);
                }));
    }

    private static PyNativeFunction function(
            String name, String doc, PyNativeFunction.Body body) {
        return new PyNativeFunction(name, body).documented(doc);
    }

    /**
     * The mode argument, read the way the C module's {@code unsigned short} converter reads it.
     *
     * <p>A negative mode is the unsigned-conversion refusal; anything above {@code 0xFFFF} is
     * {@code "mode out of range"}. The distinction is the reference's, and it is why a mask like
     * {@code 0xffffffff} is refused rather than quietly truncated.
     */
    private static int mode(PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.signum() < 0) {
            throw Slots.overflowError("can't convert negative value to unsigned int");
        }
        if (number.bitLength() > 16) {
            throw Slots.overflowError("mode out of range");
        }
        return number.intValue();
    }

    /** {@code filemode}: one type character and nine permission characters, as the C table spells them. */
    private static String fileMode(int mode) {
        StringBuilder text = new StringBuilder(10);
        switch (mode & FILE_TYPE) {
            case 0120000 -> text.append('l');
            // A socket is also `S_IFREG|S_IFDIR`, and the C table tests it before either of them.
            case 0140000 -> text.append('s');
            case 0100000 -> text.append('-');
            case 0060000 -> text.append('b');
            case 0040000 -> text.append('d');
            case 0020000 -> text.append('c');
            case 0010000 -> text.append('p');
            case 0160000 -> text.append('w');
            default -> text.append('?');
        }
        text.append((mode & 00400) != 0 ? 'r' : '-');
        text.append((mode & 00200) != 0 ? 'w' : '-');
        text.append(execute(mode, 00100, 04000, 's', 'S'));
        text.append((mode & 00040) != 0 ? 'r' : '-');
        text.append((mode & 00020) != 0 ? 'w' : '-');
        text.append(execute(mode, 00010, 02000, 's', 'S'));
        text.append((mode & 00004) != 0 ? 'r' : '-');
        text.append((mode & 00002) != 0 ? 'w' : '-');
        text.append(execute(mode, 00001, 01000, 't', 'T'));
        return text.toString();
    }

    private static char execute(int mode, int bit, int special, char both, char onlySpecial) {
        boolean executable = (mode & bit) != 0;
        boolean flagged = (mode & special) != 0;
        if (executable && flagged) {
            return both;
        }
        if (flagged) {
            return onlySpecial;
        }
        return executable ? 'x' : '-';
    }
}
