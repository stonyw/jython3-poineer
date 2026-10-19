package org.python.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a program as its own process, which is how a user runs it.
 *
 * <p>In-process would be shorter and would miss the point: {@code print} has to reach the OS
 * descriptors through {@code sys.stdout}, and a stream that a test swapped in is not the stream a
 * milestone's acceptance script is measured against. The same reasoning is why the launcher's exit
 * code is read from the process rather than returned by a method.
 */
public final class Jy3Process {

    /** A script that stops answering is a bug to find, not a test to wait out. */
    private static final long TIMEOUT_SECONDS = 120;

    private Jy3Process() {
    }

    /** {@code jy3 <arguments>}, with the working directory the test's own. */
    public static Result jy3(Path work, String... arguments) throws Exception {
        return run(work, jy3Command(), List.of(arguments), null);
    }

    /** The launcher, in this same JVM's classpath, as a process rather than as a method call. */
    public static List<String> jy3Command() {
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isEmpty()) {
            throw new IllegalStateException(
                    "this test must run with the runtime classpath visible to java.class.path");
        }
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                "org.python.util.jy3");
    }

    public static Result run(
            Path work, List<String> command, List<String> arguments, String input) throws Exception {
        List<String> line = new ArrayList<>(command);
        line.addAll(arguments);
        Process process =
                new ProcessBuilder(line).directory(work.toFile()).redirectErrorStream(false).start();
        if (input != null) {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        String out = read(process.getInputStream());
        String err = read(process.getErrorStream());
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    command + " " + arguments + " did not finish in " + TIMEOUT_SECONDS + "s");
        }
        return new Result(process.exitValue(), out, err);
    }

    /** A resource on the test classpath, so an acceptance script is one file a human can also run. */
    public static String resource(String name) {
        try (InputStream in =
                Jy3Process.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("test resource " + name + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path write(Path work, String name, String source) throws IOException {
        return Files.writeString(work.resolve(name), source);
    }

    /**
     * The last non-empty line of a stream, which is where a Python report puts its complaint.
     *
     * <p>A traceback needs the frame chain and the code object's exception table (M3), so the two
     * things a test can compare today are the last line and the exit code.
     */
    public static String lastLine(String text) {
        String trimmed = text.strip();
        int end = trimmed.lastIndexOf('\n');
        return end < 0 ? trimmed : trimmed.substring(end + 1);
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        stream.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public record Result(int exit, String out, String err) {}
}
