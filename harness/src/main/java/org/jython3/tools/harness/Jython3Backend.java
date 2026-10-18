package org.jython3.tools.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Backend for Jython3 itself, driven through the {@code jy3} launcher.
 *
 * <p>Protocol (documented in {@code org.python.util.jy3}): the launcher runs one regrtest target and
 * exits 0 = passed, 1 = failed, 2 = skipped, 3 = not implemented, anything else = crash.
 */
public final class Jython3Backend implements Backend {

    private final Path jy3;
    private final Path libPython;
    private final Duration timeout;

    public Jython3Backend(Path jy3, Path libPython, Duration timeout) {
        this.jy3 = jy3;
        this.libPython = libPython;
        this.timeout = timeout;
    }

    @Override
    public String id() {
        return "jy3";
    }

    @Override
    public Grading.Result run(String test) {
        long start = System.nanoTime();
        if (jy3 == null || !Files.isExecutable(jy3)) {
            return new Grading.Result(
                    test,
                    Grading.Status.NOT_IMPLEMENTED,
                    3,
                    elapsed(start),
                    "jy3 launcher absent (build :embedding:installDist)");
        }
        try {
            Backend.Proc p =
                    Backend.exec(
                            List.of(jy3.toString(), "--harness", test),
                            libPython.toString(),
                            Map.of(),
                            timeout);
            if (p.timedOut()) {
                return new Grading.Result(test, Grading.Status.TIMEOUT, -1, elapsed(start), tail(p.output()));
            }
            Grading.Status s =
                    switch (p.exitCode()) {
                        case 0 -> Grading.Status.PASS;
                        case 1 -> Grading.Status.FAIL;
                        case 2 -> Grading.Status.SKIP;
                        case 3 -> Grading.Status.NOT_IMPLEMENTED;
                        default -> Grading.Status.CRASH;
                    };
            return new Grading.Result(test, s, p.exitCode(), elapsed(start), tail(p.output()));
        } catch (IOException e) {
            return new Grading.Result(test, Grading.Status.CRASH, -1, elapsed(start), "cannot launch jy3: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Grading.Result(test, Grading.Status.CRASH, -1, elapsed(start), "interrupted");
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 1_000 ? trimmed : trimmed.substring(trimmed.length() - 1_000);
    }
}
