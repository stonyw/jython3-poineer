package org.jython3.tools.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference backend: asks CPython 3.14 itself to run the test through its own regrtest driver.
 *
 * <p>Its role is to prove the instrument. If this backend reports surprises against an empty
 * expected list, the harness is wrong, not the tests.
 */
public final class CpythonBackend implements Backend {

    /**
     * {@code 0:00:00 load avg: 1.34 [1/1] test_grammar passed}
     *
     * <p>MULTILINE is load-bearing: without it {@code $} only matches end-of-input and the result
     * line is never found, which would silently degrade classification to exit-code guessing.
     */
    private static final Pattern RESULT_LINE =
            Pattern.compile("\\[\\d+/\\d+]\\s+\\S+\\s+(.*?)\\s*$", Pattern.MULTILINE);

    private final String python;
    private final Path libPython;
    private final Duration timeout;

    public CpythonBackend(String python, Path libPython, Duration timeout) {
        this.python = python;
        this.libPython = libPython;
        this.timeout = timeout;
    }

    @Override
    public String id() {
        return "cpython-ref";
    }

    @Override
    public Grading.Result run(String test) {
        long start = System.nanoTime();
        if (!Files.isDirectory(libPython.resolve("test"))) {
            return new Grading.Result(
                    test,
                    Grading.Status.CRASH,
                    -1,
                    elapsed(start),
                    "vendored test tree missing: run `gradle :harness:syncCpythonTests`");
        }
        List<String> cmd =
                List.of(
                        python,
                        "-m",
                        "test",
                        "--timeout=" + Math.max(1, timeout.toSeconds() - 5),
                        "-v",
                        test);
        try {
            Backend.Proc p =
                    Backend.exec(cmd, libPython.toString(), Map.of("PYTHONPATH", libPython.toString()), timeout);
            if (p.timedOut()) {
                return new Grading.Result(test, Grading.Status.TIMEOUT, -1, elapsed(start), tail(p.output()));
            }
            return new Grading.Result(test, classify(p), p.exitCode(), elapsed(start), tail(p.output()));
        } catch (IOException e) {
            return new Grading.Result(
                    test, Grading.Status.CRASH, -1, elapsed(start), "cannot launch " + python + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Grading.Result(test, Grading.Status.CRASH, -1, elapsed(start), "interrupted");
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    static Grading.Status classify(Backend.Proc p) {
        String outcome = null;
        Matcher m = RESULT_LINE.matcher(p.output());
        while (m.find()) {
            // Last match wins: regrtest re-reports failures at the end of the run.
            outcome = m.group(1);
        }
        if (outcome == null) {
            // No per-test line at all: bad invocation, or the interpreter died before reporting.
            return p.exitCode() == 0 ? Grading.Status.PASS : Grading.Status.CRASH;
        }
        String o = outcome.toLowerCase();
        if (o.startsWith("passed")) {
            return Grading.Status.PASS;
        }
        if (o.startsWith("skipped") || o.startsWith("warning -- the test result is skipped")) {
            return Grading.Status.SKIP;
        }
        if (o.startsWith("failed")) {
            return Grading.Status.FAIL;
        }
        return Grading.Status.CRASH;
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        if (trimmed.length() <= 1_000) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - 1_000);
    }
}
