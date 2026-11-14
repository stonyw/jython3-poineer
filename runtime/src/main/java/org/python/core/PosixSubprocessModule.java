package org.python.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code _posixsubprocess}: the one function {@code subprocess} calls to start a child.
 *
 * <p>There is no {@code fork} and no {@code execve} on the JVM, so this is <b>not</b> the reference's
 * {@code fork_exec}. It is the same call, in the same position, with the same argument list, built out of
 * {@link ProcessBuilder}: the parent's pipes are {@link Fds} descriptors, a pumping thread carries bytes
 * between each pipe end and the {@link Process} stream it stands for, and the returned integer is the
 * child's pid so that {@code os.waitpid} can keep its part of the bargain.
 *
 * <p>What the child would do for itself after {@code fork} has no counterpart here, and each such
 * argument is refused by name rather than ignored: {@code preexec_fn}, {@code start_new_session}, a
 * process group other than the parent's, {@code gid}/{@code gids}/{@code uid} and a child {@code umask}.
 * Those are {@link ExceptionTypes#NOT_IMPLEMENTED_ERROR} with the argument's name in the message.
 *
 * <p>Three further deviations are silent because the reference's answers are still the observable ones:
 * {@code close_fds} and {@code fds_to_keep} have nothing to close (the JVM child inherits none of these
 * descriptors), {@code restore_signals} is redundant (a Java child starts with default dispositions), and
 * only the three standard streams can be redirected — a fourth descriptor cannot be handed to the child.
 */
final class PosixSubprocessModule {

    /** The pids this runtime has started, until {@code os.waitpid} reaps them. */
    private static final Map<Long, Child> CHILDREN = new HashMap<>();

    /** {@code WNOHANG}, repeated here so {@code waitpid} does not depend on the os module's constants. */
    private static final int WNOHANG = 1;

    /** What a child that could not be started reports, which is the reference's own status after failure. */
    private static final int EXEC_FAILED_STATUS = 255 << 8;

    private static final class Child {
        final Process process;

        Child(Process process) {
            this.process = process;
        }
    }

    private PosixSubprocessModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("_posixsubprocess");
        PyRuntime.markBuiltIn(module, "_posixsubprocess");
        module.set("fork_exec", new PyNativeFunction("fork_exec", PosixSubprocessModule::forkExec)
                .documented("fork_exec(args, executable_list, close_fds, fds_to_keep, cwd, env_list,\n"
                        + "          p2cread, p2cwrite, c2pread, c2pwrite, errread, errwrite,\n"
                        + "          errpipe_read, errpipe_write, restore_signals, start_new_session,\n"
                        + "          process_group, gid, gids, uid, umask, preexec_fn)\n"
                        + "--\n\n"
                        + "Start a child process and return its pid, the way this runtime can: with\n"
                        + "ProcessBuilder rather than fork and exec."));
        return module;
    }

    // ---- the call ----------------------------------------------------------------------------

    private static PyObject forkExec(PyObject[] args, String[] kwNames) {
        Arguments.exactly("fork_exec", args, kwNames, 22);
        List<String> argv = texts(args[0]);
        if (argv.isEmpty()) {
            throw Slots.valueError("fork_exec() needs at least one argument");
        }
        List<String> executableList = texts(args[1]);
        String cwd = args[4] == PyNone.INSTANCE ? null : text(args[4]);
        Map<String, String> environment = args[5] == PyNone.INSTANCE ? null : environment(args[5]);
        int p2cread = number(args[6]);
        int c2pwrite = number(args[9]);
        int errwrite = number(args[11]);
        int errpipeWrite = number(args[13]);
        boolean startNewSession = Slots.truth(args[15]);
        int processGroup = number(args[16]);
        int umask = number(args[20]);
        PyObject preexec = args[21];

        if (preexec != PyNone.INSTANCE) {
            throw unimplemented("preexec_fn is not supported: the JVM cannot run Python in a forked child");
        }
        if (startNewSession) {
            throw unimplemented("start_new_session is not supported: a JVM child cannot call setsid()");
        }
        if (processGroup != -1) {
            throw unimplemented("process_group is not supported: a JVM child cannot join another group");
        }
        if (args[17] != PyNone.INSTANCE || args[18] != PyNone.INSTANCE || args[19] != PyNone.INSTANCE) {
            throw unimplemented("gid, gids and uid are not supported: a JVM child cannot switch user");
        }
        if (umask >= 0) {
            throw unimplemented("umask is not supported: a JVM child cannot set its creation mask");
        }

        ProcessBuilder builder = new ProcessBuilder(executable(argv, executableList));
        if (cwd != null) {
            builder.directory(new File(cwd));
        }
        if (environment != null) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        mapStandardStreams(builder, p2cread, c2pwrite, errwrite);

        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            int number = errno(failure);
            String report = exceptionName(number) + ":" + Integer.toHexString(number) + ":"
                    + message(number);
            Fds.writeQuietly(errpipeWrite, report.getBytes(StandardCharsets.UTF_8));
            return PyInt.of(registerFailed());
        }

        long pid = process.pid();
        synchronized (CHILDREN) {
            CHILDREN.put(pid, new Child(process));
        }
        if (p2cread > 2 && Fds.known(p2cread) && !Fds.isDevnull(p2cread)) {
            pumpToChild(process, Fds.retain(p2cread));
        }
        if (c2pwrite > 2 && Fds.known(c2pwrite) && !Fds.isDevnull(c2pwrite)) {
            pumpFromChild(process.getInputStream(), Fds.retain(c2pwrite));
        }
        if (errwrite > 2 && Fds.known(errwrite) && !Fds.isDevnull(errwrite)
                && errwrite != c2pwrite) {
            pumpFromChild(process.getErrorStream(), Fds.retain(errwrite));
        }
        return PyInt.of(pid);
    }

    // ---- waiting -----------------------------------------------------------------------------

    /**
     * {@code waitpid(2)} over the children {@link #forkExec} started.
     *
     * <p>The status is encoded the way the reference's is: the exit code in the second byte, which is
     * what {@code WIFEXITED} and {@code WEXITSTATUS} read. A reaped child is forgotten, so a second
     * {@code waitpid} answers {@code ChildProcessError} as it does there.
     */
    static PyObject waitpid(long pid, int options) {
        Child child;
        synchronized (CHILDREN) {
            child = CHILDREN.get(pid);
        }
        if (child == null) {
            throw childProcessError();
        }
        if ((options & WNOHANG) != 0 && child.process.isAlive()) {
            return PyTuple.of(PyInt.of(0), PyInt.of(0));
        }
        int status;
        try {
            status = child.process.waitFor() << 8;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw Slots.osError("waitpid() was interrupted");
        }
        synchronized (CHILDREN) {
            CHILDREN.remove(pid);
        }
        return PyTuple.of(PyInt.of(pid), PyInt.of(status));
    }

    /** The child register for a start that failed: {@code waitpid} still has to answer it once. */
    private static long registerFailed() {
        long pid = 2_000_000L + System.nanoTime() % 1_000_000L;
        synchronized (CHILDREN) {
            CHILDREN.put(pid, new Child(new Failed(EXEC_FAILED_STATUS)));
        }
        return pid;
    }

    /** A child that never started, reporting the status a failed {@code exec} leaves behind. */
    private static final class Failed extends Process {
        private final int status;

        Failed(int status) {
            this.status = status;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return status >> 8;
        }

        @Override
        public int exitValue() {
            return status >> 8;
        }

        @Override
        public void destroy() {
        }
    }

    private static PyRaise childProcessError() {
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.CHILD_PROCESS_ERROR,
                new PyObject[] {PyInt.of(10), PyStr.of("No child processes")}));
    }

    // ---- the child's three streams -----------------------------------------------------------

    private static void mapStandardStreams(ProcessBuilder builder, int p2cread, int c2pwrite,
            int errwrite) {
        if (p2cread < 0 || p2cread <= 2) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        } else if (Fds.isDevnull(p2cread)) {
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        } else if (Fds.known(p2cread)) {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        } else {
            throw unimplemented("the child's stdin can only be a pipe, the null device or an "
                    + "inherited stream");
        }

        if (c2pwrite < 0 || c2pwrite <= 2) {
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        } else if (Fds.isDevnull(c2pwrite)) {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        } else if (Fds.known(c2pwrite)) {
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        } else {
            throw unimplemented("the child's stdout can only be a pipe, the null device or an "
                    + "inherited stream");
        }

        if (errwrite == c2pwrite && c2pwrite > 2) {
            builder.redirectErrorStream(true);
        } else if (errwrite < 0 || errwrite <= 2) {
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        } else if (Fds.isDevnull(errwrite)) {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        } else if (Fds.known(errwrite)) {
            builder.redirectError(ProcessBuilder.Redirect.PIPE);
        } else {
            throw unimplemented("the child's stderr can only be a pipe, the null device or an "
                    + "inherited stream");
        }
    }

    /** Carries what the child writes into the pipe end the parent will read. */
    private static void pumpFromChild(InputStream source, int fd) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = source) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    Fds.writeQuietly(fd, Arrays.copyOf(buffer, read));
                }
            } catch (IOException ignored) {
                // The child went away mid-stream; end-of-file is the same answer.
            } finally {
                Fds.closeQuietly(fd);
            }
        }, "jy3-child-output");
        thread.setDaemon(true);
        thread.start();
    }

    /** Carries what the parent writes on the pipe into the child's stdin. */
    private static void pumpToChild(Process process, int fd) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = Fds.inputStream(fd);
                    OutputStream out = process.getOutputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException | PyRaise ignored) {
                // Either end closing early is ordinary; the child sees end-of-file.
            } finally {
                Fds.closeQuietly(fd);
            }
        }, "jy3-child-input");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- argument decoding -------------------------------------------------------------------

    /**
     * The command, with its first element replaced by the first candidate that exists.
     *
     * <p>{@code subprocess} hands a bare name in {@code args[0]} and the {@code PATH} expansions in
     * {@code executable_list}; the reference's child tries them in order in {@code execve}. Choosing the
     * candidate here is the same walk, done in the parent because there is no child to do it.
     */
    private static List<String> executable(List<String> argv, List<String> candidates) {
        // `execve` cannot carry a NUL inside any argument: the reference's child raises ValueError before
        // it ever looks at the file, and Java would raise InvalidPathException out of `Path.of` instead.
        for (String argument : argv) {
            if (argument.indexOf('\0') >= 0) {
                throw Slots.valueError("embedded null byte");
            }
        }
        String chosen = null;
        for (String candidate : candidates) {
            if (candidate.indexOf('\0') >= 0) {
                throw Slots.valueError("embedded null byte");
            }
            if (Files.isExecutable(Path.of(candidate))) {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null && !candidates.isEmpty()) {
            chosen = candidates.get(0);
        }
        List<String> command = new ArrayList<>(argv);
        if (chosen != null) {
            command.set(0, chosen);
        }
        return command;
    }

    private static Map<String, String> environment(PyObject list) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (PyObject entry : items(list)) {
            String text = text(entry);
            if (text.indexOf('\0') >= 0) {
                throw Slots.valueError("embedded null byte");
            }
            int equals = text.indexOf('=');
            if (equals > 0) {
                environment.put(text.substring(0, equals), text.substring(equals + 1));
            }
        }
        return environment;
    }

    private static List<String> texts(PyObject sequence) {
        List<String> texts = new ArrayList<>();
        for (PyObject item : items(sequence)) {
            texts.add(text(item));
        }
        return texts;
    }

    private static List<PyObject> items(PyObject sequence) {
        if (sequence instanceof PyList list) {
            return list.getItems();
        }
        if (sequence instanceof PyTuple tuple) {
            List<PyObject> items = new ArrayList<>(tuple.size());
            for (int i = 0; i < tuple.size(); i++) {
                items.add(tuple.at(i));
            }
            return items;
        }
        throw Slots.typeError("expected a list or tuple, not %s", sequence.getType().name());
    }

    private static String text(PyObject value) {
        if (value instanceof PyStr str) {
            return str.getValue();
        }
        if (value instanceof PyBytes bytes) {
            return new String(bytes.getValue(), StandardCharsets.UTF_8);
        }
        throw Slots.typeError("expected str or bytes, not %s", value.getType().name());
    }

    private static int number(PyObject value) {
        return Numbers.asIndex(value).intValue();
    }

    // ---- failure translation -----------------------------------------------------------------

    /** The error number a failed {@code ProcessBuilder.start()} stands for. */
    private static int errno(IOException failure) {
        String text = failure.getMessage() == null ? "" : failure.getMessage();
        int marker = text.indexOf("error=");
        if (marker >= 0) {
            int end = marker + 6;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            if (end > marker + 6) {
                return Integer.parseInt(text.substring(marker + 6, end));
            }
        }
        if (text.contains("No such file")) {
            return 2;
        }
        if (text.contains("Permission denied")) {
            return 13;
        }
        return 2;
    }

    /** The exception the reference's child reports for this error number. */
    private static String exceptionName(int number) {
        return switch (number) {
            case 2 -> "FileNotFoundError";
            case 13, 1 -> "PermissionError";
            case 20 -> "NotADirectoryError";
            default -> "OSError";
        };
    }

    private static String message(int number) {
        return switch (number) {
            case 2 -> "No such file or directory";
            case 13, 1 -> "Permission denied";
            case 20 -> "Not a directory";
            case 8 -> "Exec format error";
            default -> "Cannot start the child process";
        };
    }

    private static PyRaise unimplemented(String message) {
        return PyRaise.raise(ExceptionTypes.NOT_IMPLEMENTED_ERROR, message);
    }
}
