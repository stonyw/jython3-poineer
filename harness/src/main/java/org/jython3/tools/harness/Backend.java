package org.jython3.tools.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** A backend executes one Python regression test and reports what it saw. */
public interface Backend {

    String id();

    Grading.Result run(String test);

    record Proc(int exitCode, String output, boolean timedOut) {}

    static Proc exec(List<String> command, String workDir, Map<String, String> env, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new java.io.File(workDir));
        pb.redirectErrorStream(true);
        env.forEach((k, v) -> pb.environment().put(k, v));

        Process p = pb.start();
        // Drain before waiting: a full pipe buffer deadlocks the child before it can exit.
        String output =
                new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return new Proc(-1, output, true);
        }
        return new Proc(p.exitValue(), output, false);
    }
}
