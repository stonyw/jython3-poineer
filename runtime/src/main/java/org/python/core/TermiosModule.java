package org.python.core;

/**
 * The {@code termios} module: the Posix terminal line-discipline calls.
 *
 * <p>The JVM has no terminal line discipline. {@code java.io} and {@code java.nio} move bytes through
 * descriptors, but nothing reads or writes a {@code struct termios}, and this runtime has no JNI, so a
 * real terminal cannot be configured from here. What is left is the boundary the reference itself shows
 * on the same descriptors, and that boundary is what this module reproduces exactly:
 *
 * <ul>
 *   <li>Argument parsing follows the generated Argument Clinic block, including its two different
 *       "expected N arguments" sentences (the {@code METH_O} functions name themselves with the module
 *       prefix and say "takes exactly one argument", the {@code METH_FASTCALL} ones count bare), the
 *       keyword refusal that comes before the count, {@code PyObject_AsFileDescriptor}'s four answers
 *       for a value that is not a descriptor, and the C {@code int} overflow sentence.
 *   <li>For a descriptor this runtime can see is not a terminal, the answer is the one the kernel gives
 *       the reference on the same descriptor: {@code EBADF} for a closed or unknown number,
 *       {@code ENODEV} for {@code /dev/null}, {@code ENOTTY} for a pipe, a socket or a regular file
 *       (all measured against CPython 3.14.10 on this machine).
 *   <li>For the three inherited descriptors while the JVM was started from a terminal — the case
 *       {@code os.isatty} already answers {@code True} for — there is nothing to read or write, so the
 *       call stops with {@code NotImplementedError} rather than inventing attributes. Nothing is faked;
 *       the gap is named in PLAN.md §3.27 as well as here.
 * </ul>
 *
 * <p>{@code tty} and {@code pty} are Python files in the standard library. {@code tty} needs nothing
 * from here beyond the constants and {@code error}: {@code setraw}/{@code setcbreak} read the mode with
 * {@code tcgetattr}, and {@code cfmakeraw}/{@code cfmakecbreak} are pure Python over an attribute list,
 * so they answer exactly as the reference does. {@code pty} imports, but {@code openpty()} needs
 * {@code os.openpty} — a master/slave pair the JVM cannot create — and that absence is named in PLAN.md
 * too.
 */
final class TermiosModule {

    /** {@code termios.__doc__}, the reference's own sentence for the module. */
    private static final String DOC =
            "This module provides an interface to the Posix calls for tty I/O control.\n"
            + "For a complete description of these calls, see the Posix or Unix manual\n"
            + "pages. It is only available for those Unix versions that support Posix\n"
            + "termios style tty I/O control.\n"
            + "\n"
            + "All functions in this module take a file descriptor fd as their first\n"
            + "argument. This can be an integer file descriptor, such as returned by\n"
            + "sys.stdin.fileno(), or a file object, such as sys.stdin itself.";

    private static final String TCGETATTR_DOC =
            "tcgetattr($module, fd, /)\n"
            + "--\n"
            + "\n"
            + "Get the tty attributes for file descriptor fd.\n"
            + "\n"
            + "Returns a list [iflag, oflag, cflag, lflag, ispeed, ospeed, cc]\n"
            + "where cc is a list of the tty special characters (each a string of\n"
            + "length 1, except the items with indices VMIN and VTIME, which are\n"
            + "integers when these fields are defined).  The interpretation of the\n"
            + "flags and the speeds as well as the indexing in the cc array must be\n"
            + "done using the symbolic constants defined in this module.";

    private static final String TCSETATTR_DOC =
            "tcsetattr($module, fd, when, attributes, /)\n"
            + "--\n"
            + "\n"
            + "Set the tty attributes for file descriptor fd.\n"
            + "\n"
            + "The attributes to be set are taken from the attributes argument, which\n"
            + "is a list like the one returned by tcgetattr(). The when argument\n"
            + "determines when the attributes are changed: termios.TCSANOW to\n"
            + "change immediately, termios.TCSADRAIN to change after transmitting all\n"
            + "queued output, or termios.TCSAFLUSH to change after transmitting all\n"
            + "queued output and discarding all queued input.";

    private static final String TCSENDBREAK_DOC =
            "tcsendbreak($module, fd, duration, /)\n"
            + "--\n"
            + "\n"
            + "Send a break on file descriptor fd.\n"
            + "\n"
            + "A zero duration sends a break for 0.25-0.5 seconds; a nonzero duration\n"
            + "has a system dependent meaning.";

    private static final String TCDRAIN_DOC =
            "tcdrain($module, fd, /)\n"
            + "--\n"
            + "\n"
            + "Wait until all output written to file descriptor fd has been transmitted.";

    private static final String TCFLUSH_DOC =
            "tcflush($module, fd, queue, /)\n"
            + "--\n"
            + "\n"
            + "Discard queued data on file descriptor fd.\n"
            + "\n"
            + "The queue selector specifies which queue: termios.TCIFLUSH for the input\n"
            + "queue, termios.TCOFLUSH for the output queue, or termios.TCIOFLUSH for\n"
            + "both queues.";

    private static final String TCFLOW_DOC =
            "tcflow($module, fd, action, /)\n"
            + "--\n"
            + "\n"
            + "Suspend or resume input or output on file descriptor fd.\n"
            + "\n"
            + "The action argument can be termios.TCOOFF to suspend output,\n"
            + "termios.TCOON to restart output, termios.TCIOFF to suspend input,\n"
            + "or termios.TCION to restart input.";

    private static final String TCGETWINSIZE_DOC =
            "tcgetwinsize($module, fd, /)\n"
            + "--\n"
            + "\n"
            + "Get the tty winsize for file descriptor fd.\n"
            + "\n"
            + "Returns a tuple (ws_row, ws_col).";

    private static final String TCSETWINSIZE_DOC =
            "tcsetwinsize($module, fd, winsize, /)\n"
            + "--\n"
            + "\n"
            + "Set the tty winsize for file descriptor fd.\n"
            + "\n"
            + "The winsize to be set is taken from the winsize argument, which\n"
            + "is a two-item tuple (ws_row, ws_col) like the one returned by\n"
            + "tcgetwinsize().";

    private TermiosModule() {}

    /** The module, with its {@code error} class, its constants and its eight calls. */
    static PyModule newModule() {
        PyModule module = new PyModule("termios");
        module.set("__doc__", PyStr.of(DOC));
        module.set("error", ExceptionTypes.TERMIOS_ERROR);
        constants(module);
        module.set("tcgetattr", function("tcgetattr", TCGETATTR_DOC, TermiosModule::tcgetattr));
        module.set("tcsetattr", function("tcsetattr", TCSETATTR_DOC, TermiosModule::tcsetattr));
        module.set("tcsendbreak", function("tcsendbreak", TCSENDBREAK_DOC, TermiosModule::tcsendbreak));
        module.set("tcdrain", function("tcdrain", TCDRAIN_DOC, TermiosModule::tcdrain));
        module.set("tcflush", function("tcflush", TCFLUSH_DOC, TermiosModule::tcflush));
        module.set("tcflow", function("tcflow", TCFLOW_DOC, TermiosModule::tcflow));
        module.set("tcgetwinsize",
                function("tcgetwinsize", TCGETWINSIZE_DOC, TermiosModule::tcgetwinsize));
        module.set("tcsetwinsize",
                function("tcsetwinsize", TCSETWINSIZE_DOC, TermiosModule::tcsetwinsize));
        PyRuntime.markBuiltIn(module, "termios");
        return module;
    }

    private static PyNativeFunction function(String name, String doc, PyNativeFunction.Body body) {
        return new PyNativeFunction(name, body).documented(doc);
    }

    private static void put(PyModule module, String name, long value) {
        module.set(name, PyInt.of(value));
    }

    // --- the calls ----------------------------------------------------------------------------

    private static PyObject tcgetattr(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("termios.tcgetattr()", args, kwNames);
        throw terminal(descriptor(args[0]));
    }

    private static PyObject tcsetattr(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("termios.tcsetattr()", kwNames);
        if (args.length != 3) {
            throw Slots.typeError("tcsetattr expected 3 arguments, got %d", args.length);
        }
        int fd = descriptor(args[0]);
        Numbers.cInteger(args[1]);
        PyObject attributes = args[2];
        if (!(attributes instanceof PyList list) || list.size() != 7) {
            throw Slots.typeError("tcsetattr, arg 3: must be 7 element list");
        }
        throw terminal(fd);
    }

    private static PyObject tcsendbreak(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("termios.tcsendbreak()", kwNames);
        if (args.length != 2) {
            throw Slots.typeError("tcsendbreak expected 2 arguments, got %d", args.length);
        }
        int fd = descriptor(args[0]);
        Numbers.cInteger(args[1]);
        throw terminal(fd);
    }

    private static PyObject tcdrain(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("termios.tcdrain()", args, kwNames);
        throw terminal(descriptor(args[0]));
    }

    private static PyObject tcflush(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("termios.tcflush()", kwNames);
        if (args.length != 2) {
            throw Slots.typeError("tcflush expected 2 arguments, got %d", args.length);
        }
        int fd = descriptor(args[0]);
        Numbers.cInteger(args[1]);
        throw terminal(fd);
    }

    private static PyObject tcflow(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("termios.tcflow()", kwNames);
        if (args.length != 2) {
            throw Slots.typeError("tcflow expected 2 arguments, got %d", args.length);
        }
        int fd = descriptor(args[0]);
        Numbers.cInteger(args[1]);
        throw terminal(fd);
    }

    private static PyObject tcgetwinsize(PyObject[] args, String[] kwNames) {
        Arguments.exactlyOne("termios.tcgetwinsize()", args, kwNames);
        throw terminal(descriptor(args[0]));
    }

    private static PyObject tcsetwinsize(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("termios.tcsetwinsize()", kwNames);
        if (args.length != 2) {
            throw Slots.typeError("tcsetwinsize expected 2 arguments, got %d", args.length);
        }
        int fd = descriptor(args[0]);
        PyObject winsize = args[1];
        boolean twoItems = Sequences.isSequence(winsize);
        if (twoItems) {
            try {
                twoItems = Numbers.size(Slots.length(winsize)) == 2;
            } catch (PyRaise refused) {
                twoItems = false;
            }
        }
        if (!twoItems) {
            throw Slots.typeError("tcsetwinsize, arg 2: must be a two-item sequence");
        }
        Numbers.cLong(Slots.getItem(winsize, PyInt.of(0)));
        Numbers.cLong(Slots.getItem(winsize, PyInt.of(1)));
        throw terminal(fd);
    }

    // --- descriptors and the one refusal ------------------------------------------------------

    /**
     * {@code PyObject_AsFileDescriptor}: an {@code int}, or a value whose {@code fileno()} answers one.
     *
     * <p>The negative-number sentence is the one 3.14 added to that helper; it is a {@code ValueError},
     * not the {@code ValueError} the syscall layer would produce, and it comes before any descriptor
     * table is consulted.
     */
    private static int descriptor(PyObject value) {
        if (value instanceof PyInt) {
            return descriptorNumber(value);
        }
        PyObject fileno = value.getAttr("fileno");
        if (fileno == Slots.DECLINED) {
            throw Slots.typeError("argument must be an int, or have a fileno() method.");
        }
        PyObject answered = fileno.call(new PyObject[0], null);
        if (!(answered instanceof PyInt)) {
            throw Slots.typeError("fileno() returned a non-integer");
        }
        return descriptorNumber(answered);
    }

    private static int descriptorNumber(PyObject value) {
        int fd = Numbers.cInteger(value);
        if (fd < 0) {
            throw Slots.valueError("file descriptor cannot be a negative integer (%d)", fd);
        }
        return fd;
    }

    /**
     * The answer for this descriptor. A terminal is the one thing this module cannot grant, and it says
     * so; everything else is the errno the reference's own {@code ioctl} raises here.
     */
    private static PyRaise terminal(int fd) {
        if (fd >= 3 && !Fds.isOpen(fd)) {
            return termiosError(9);
        }
        if (nullDevice(fd)) {
            return termiosError(19);
        }
        if (fd < 3 && System.console() != null) {
            // `os.isatty` answers True for these, so the descriptor is the terminal the JVM started
            // from — and the JVM has no line discipline to read or write through it.
            return Slots.notImplemented("the tty line discipline");
        }
        return termiosError(25);
    }

    /**
     * Whether the descriptor is the null device, by either of the two ways this runtime opens one.
     *
     * <p>{@code os.open('/dev/null', ...)} files a descriptor with no handle at all, which
     * {@link Fds#isDevnull} answers. The {@code open()} a script writes files the same path as an
     * ordinary handle, so it is recognised by where it was opened instead — the kernel gives an
     * {@code ioctl} on either one {@code ENODEV}.
     */
    private static boolean nullDevice(int fd) {
        if (Fds.isDevnull(fd)) {
            return true;
        }
        return Fds.isFile(fd) && "/dev/null".equals(Fds.path(fd).toString());
    }

    private static PyRaise termiosError(int code) {
        return PyRaise.raise(ExceptionTypes.TERMIOS_ERROR,
                PyInt.of(code), PyStr.of(PosixModule.strerror(code)));
    }

    /** The 197 integer constants the reference publishes for macOS. */
    private static void constants(PyModule module) {
        put(module, "ALTWERASE", 512);
        put(module, "B0", 0);
        put(module, "B110", 110);
        put(module, "B115200", 115200);
        put(module, "B1200", 1200);
        put(module, "B134", 134);
        put(module, "B14400", 14400);
        put(module, "B150", 150);
        put(module, "B1800", 1800);
        put(module, "B19200", 19200);
        put(module, "B200", 200);
        put(module, "B230400", 230400);
        put(module, "B2400", 2400);
        put(module, "B28800", 28800);
        put(module, "B300", 300);
        put(module, "B38400", 38400);
        put(module, "B4800", 4800);
        put(module, "B50", 50);
        put(module, "B57600", 57600);
        put(module, "B600", 600);
        put(module, "B7200", 7200);
        put(module, "B75", 75);
        put(module, "B76800", 76800);
        put(module, "B9600", 9600);
        put(module, "BRKINT", 2);
        put(module, "BS0", 0);
        put(module, "BS1", 32768);
        put(module, "BSDLY", 32768);
        put(module, "CCAR_OFLOW", 1048576);
        put(module, "CCTS_OFLOW", 65536);
        put(module, "CDSR_OFLOW", 524288);
        put(module, "CDSUSP", 25);
        put(module, "CDTR_IFLOW", 262144);
        put(module, "CEOF", 4);
        put(module, "CEOL", 255);
        put(module, "CEOT", 4);
        put(module, "CERASE", 127);
        put(module, "CFLUSH", 15);
        put(module, "CIGNORE", 1);
        put(module, "CINTR", 3);
        put(module, "CKILL", 21);
        put(module, "CLNEXT", 22);
        put(module, "CLOCAL", 32768);
        put(module, "CQUIT", 28);
        put(module, "CR0", 0);
        put(module, "CR1", 4096);
        put(module, "CR2", 8192);
        put(module, "CR3", 12288);
        put(module, "CRDLY", 12288);
        put(module, "CREAD", 2048);
        put(module, "CRPRNT", 18);
        put(module, "CRTSCTS", 196608);
        put(module, "CRTS_IFLOW", 131072);
        put(module, "CS5", 0);
        put(module, "CS6", 256);
        put(module, "CS7", 512);
        put(module, "CS8", 768);
        put(module, "CSIZE", 768);
        put(module, "CSTART", 17);
        put(module, "CSTOP", 19);
        put(module, "CSTOPB", 1024);
        put(module, "CSUSP", 26);
        put(module, "CWERASE", 23);
        put(module, "ECHO", 8);
        put(module, "ECHOCTL", 64);
        put(module, "ECHOE", 2);
        put(module, "ECHOK", 4);
        put(module, "ECHOKE", 1);
        put(module, "ECHONL", 16);
        put(module, "ECHOPRT", 32);
        put(module, "EXTA", 19200);
        put(module, "EXTB", 38400);
        put(module, "EXTPROC", 2048);
        put(module, "FF0", 0);
        put(module, "FF1", 16384);
        put(module, "FFDLY", 16384);
        put(module, "FIOASYNC", 2147772029L);
        put(module, "FIOCLEX", 536897025);
        put(module, "FIONBIO", 2147772030L);
        put(module, "FIONCLEX", 536897026);
        put(module, "FIONREAD", 1074030207);
        put(module, "FLUSHO", 8388608);
        put(module, "HUPCL", 16384);
        put(module, "ICANON", 256);
        put(module, "ICRNL", 256);
        put(module, "IEXTEN", 1024);
        put(module, "IGNBRK", 1);
        put(module, "IGNCR", 128);
        put(module, "IGNPAR", 4);
        put(module, "IMAXBEL", 8192);
        put(module, "INLCR", 64);
        put(module, "INPCK", 16);
        put(module, "ISIG", 128);
        put(module, "ISTRIP", 32);
        put(module, "IUTF8", 16384);
        put(module, "IXANY", 2048);
        put(module, "IXOFF", 1024);
        put(module, "IXON", 512);
        put(module, "MDMBUF", 1048576);
        put(module, "NCCS", 20);
        put(module, "NL0", 0);
        put(module, "NL1", 256);
        put(module, "NL2", 512);
        put(module, "NL3", 768);
        put(module, "NLDLY", 768);
        put(module, "NOFLSH", 2147483648L);
        put(module, "NOKERNINFO", 33554432);
        put(module, "OCRNL", 16);
        put(module, "OFDEL", 131072);
        put(module, "OFILL", 128);
        put(module, "ONLCR", 2);
        put(module, "ONLRET", 64);
        put(module, "ONOCR", 32);
        put(module, "ONOEOT", 8);
        put(module, "OPOST", 1);
        put(module, "OXTABS", 4);
        put(module, "PARENB", 4096);
        put(module, "PARMRK", 8);
        put(module, "PARODD", 8192);
        put(module, "PENDIN", 536870912);
        put(module, "TAB0", 0);
        put(module, "TAB1", 1024);
        put(module, "TAB2", 2048);
        put(module, "TAB3", 4);
        put(module, "TABDLY", 3076);
        put(module, "TCIFLUSH", 1);
        put(module, "TCIOFF", 3);
        put(module, "TCIOFLUSH", 3);
        put(module, "TCION", 4);
        put(module, "TCOFLUSH", 2);
        put(module, "TCOOFF", 1);
        put(module, "TCOON", 2);
        put(module, "TCSADRAIN", 1);
        put(module, "TCSAFLUSH", 2);
        put(module, "TCSANOW", 0);
        put(module, "TCSASOFT", 16);
        put(module, "TIOCCONS", 2147775586L);
        put(module, "TIOCEXCL", 536900621);
        put(module, "TIOCGETD", 1074033690);
        put(module, "TIOCGPGRP", 1074033783);
        put(module, "TIOCGSIZE", 1074295912);
        put(module, "TIOCGWINSZ", 1074295912);
        put(module, "TIOCMBIC", 2147775595L);
        put(module, "TIOCMBIS", 2147775596L);
        put(module, "TIOCMGET", 1074033770);
        put(module, "TIOCMSET", 2147775597L);
        put(module, "TIOCM_CAR", 64);
        put(module, "TIOCM_CD", 64);
        put(module, "TIOCM_CTS", 32);
        put(module, "TIOCM_DSR", 256);
        put(module, "TIOCM_DTR", 2);
        put(module, "TIOCM_LE", 1);
        put(module, "TIOCM_RI", 128);
        put(module, "TIOCM_RNG", 128);
        put(module, "TIOCM_RTS", 4);
        put(module, "TIOCM_SR", 16);
        put(module, "TIOCM_ST", 8);
        put(module, "TIOCNOTTY", 536900721);
        put(module, "TIOCNXCL", 536900622);
        put(module, "TIOCOUTQ", 1074033779);
        put(module, "TIOCPKT", 2147775600L);
        put(module, "TIOCPKT_DATA", 0);
        put(module, "TIOCPKT_DOSTOP", 32);
        put(module, "TIOCPKT_FLUSHREAD", 1);
        put(module, "TIOCPKT_FLUSHWRITE", 2);
        put(module, "TIOCPKT_NOSTOP", 16);
        put(module, "TIOCPKT_START", 8);
        put(module, "TIOCPKT_STOP", 4);
        put(module, "TIOCSCTTY", 536900705);
        put(module, "TIOCSETD", 2147775515L);
        put(module, "TIOCSPGRP", 2147775606L);
        put(module, "TIOCSSIZE", 2148037735L);
        put(module, "TIOCSTI", 2147578994L);
        put(module, "TIOCSWINSZ", 2148037735L);
        put(module, "TOSTOP", 4194304);
        put(module, "VDISCARD", 15);
        put(module, "VDSUSP", 11);
        put(module, "VEOF", 0);
        put(module, "VEOL", 1);
        put(module, "VEOL2", 2);
        put(module, "VERASE", 3);
        put(module, "VINTR", 8);
        put(module, "VKILL", 5);
        put(module, "VLNEXT", 14);
        put(module, "VMIN", 16);
        put(module, "VQUIT", 9);
        put(module, "VREPRINT", 6);
        put(module, "VSTART", 12);
        put(module, "VSTATUS", 18);
        put(module, "VSTOP", 13);
        put(module, "VSUSP", 10);
        put(module, "VT0", 0);
        put(module, "VT1", 65536);
        put(module, "VTDLY", 65536);
        put(module, "VTIME", 17);
        put(module, "VWERASE", 4);
        put(module, "_POSIX_VDISABLE", 255);
    }
}
